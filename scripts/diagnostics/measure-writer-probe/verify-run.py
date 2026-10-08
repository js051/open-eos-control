#!/usr/bin/env python3
"""Validate diagnostic runtime markers and unchanged original failing-run test prefix.

This is an evidence check, never a replacement for any normal test/CI gate. A crash can produce
valid diagnostic evidence even though the full product test run failed. Read its explicit status.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

MANIFEST = "compose-1.9.3;entries=3;classWrites=17;guards=4;probe=v2"
ORIGINAL_ZIP_SHA = "ffcfddf20e082af307cb5a7b79a32358f79b96280ab47390af3cebe5b42c1e5f"


def tests_from_zip(path):
    with zipfile.ZipFile(path) as archive:
        paths = [p for p in archive.namelist() if p.endswith(".xml") and "androidTest-results/" in p]
        if len(paths) != 1:
            raise ValueError(f"Expected one unsharded connected-test XML, got {len(paths)}")
        root = ET.fromstring(archive.read(paths[0]))
    return list(root.iter("testcase"))


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def check_invocation(invocation):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", invocation):
        raise ValueError("Independent fresh invocation identity is required")


def expected_command(invocation):
    return ["./gradlew", ":app:connectedDebugAndroidTest",
            "-Pandroid.testInstrumentationRunnerArguments.requireSimulator=true",
            "-PeosMeasureWriterProbe=true", "-PeosMeasureWriterInvocation=" + invocation]


def check_pre_run_hash(path, expected):
    if not re.fullmatch(r"[0-9a-f]{64}", expected) or sha256(path) != expected:
        raise ValueError("Pre-run manifest differs from the independently recorded current job hash")


def check_pre_run_manifest(pre):
    check_invocation(pre.get("invocation", ""))
    ci, checkout = pre.get("ci", {}), pre.get("checkout", {})
    if pre.get("schema") != "eos-measure-writer-pre-run-v1" or not pre.get("created_at_utc"):
        raise ValueError("Independent pre-run host manifest is required")
    if pre.get("command") != expected_command(pre["invocation"]) or pre.get("working_directory") != "android":
        raise ValueError("Full unfiltered diagnostic command differs")
    if ci.get("job") not in ("android-ui", "android-ui-api36"):
        raise ValueError("Expected the unchanged API34 or API36 CI job")
    if any(not re.fullmatch(r"[1-9][0-9]*", ci.get(key, "")) for key in ("run_id", "run_attempt")):
        raise ValueError("CI run and attempt identities are required")
    if any(not ci.get(key) for key in ("repository", "ref", "workflow")):
        raise ValueError("CI repository/ref/workflow identity is required")
    if any(not re.fullmatch(r"[0-9a-f]{40}", checkout.get(key, "")) for key in ("commit", "tree")):
        raise ValueError("Exact checkout commit and tree are required")
    if ci.get("sha") != checkout["commit"]:
        raise ValueError("CI SHA differs from the actual checkout")
    if not re.fullmatch(r"[0-9a-f]{64}", pre.get("source_snapshot_sha256", "")):
        raise ValueError("Pre-run scoped source snapshot hash is required")
    return pre


def check_run_manifest(manifest, pre_run_manifest, app_apk, test_apk, source_snapshot):
    invocation = manifest.get("invocation", "")
    check_invocation(invocation)
    if manifest.get("schema") != "eos-measure-writer-run-v1" or type(manifest.get("gradle_exit")) is not int:
        raise ValueError("Post-run host receipt with original Gradle status is required")
    if not 0 <= manifest["gradle_exit"] <= 255:
        raise ValueError("Invalid original Gradle exit status")
    for field, path in (("pre_run_manifest_sha256", pre_run_manifest), ("app_apk_sha256", app_apk),
                        ("test_apk_sha256", test_apk), ("source_snapshot_sha256", source_snapshot)):
        expected = manifest.get(field, "")
        if not re.fullmatch(r"[0-9a-f]{64}", expected) or sha256(path) != expected:
            raise ValueError(f"Current external artifact/source hash differs: {field}")
    pre = check_pre_run_manifest(json.loads(pre_run_manifest.read_text()))
    if pre["invocation"] != invocation or pre["source_snapshot_sha256"] != manifest["source_snapshot_sha256"]:
        raise ValueError("Post-run receipt differs from the independent pre-run freeze")
    if manifest["app_apk_sha256"] == manifest["test_apk_sha256"]:
        raise ValueError("App and instrumentation artifacts must be distinct")
    return manifest


def read_probe_reports(path, expected):
    if path.is_dir():
        # Only probe files from the existing collector route, never broader device logs.
        files = (("connected_android_test_additional_output/" + str(p.relative_to(path)), p.read_bytes())
                 for p in path.rglob("eos-measure-writer-*.*") if p.is_file())
        return collect_probe_reports(files, expected)
    with zipfile.ZipFile(path) as archive:
        return collect_probe_reports(((name, archive.read(name)) for name in archive.namelist()
                                      if Path(name).name.startswith("eos-measure-writer-")), expected)


def collect_probe_reports(files, expected):
    invocation = expected["invocation"]
    prefix = "eos-measure-writer-" + invocation + "-"
    reports = {}
    expected_session = None
    for name, data in files:
        basename = Path(name).name
        if "connected_android_test_additional_output/" not in name or not basename.startswith(prefix):
            continue
        suffix = basename.removeprefix(prefix)
        if re.fullmatch(r"[0-9]+-[0-9]+-(start|fatal|finish)\.pending", suffix):
            raise ValueError("Uncommitted output for this invocation; export did not complete")
        match = re.fullmatch(r"([0-9]+)-([0-9]+)-(start|fatal|finish)\.txt", suffix)
        if not match:
            continue
        session, pid, kind = match.groups()
        if expected_session is None:
            expected_session = (session, pid)
        if (session, pid) != expected_session or kind in reports:
            raise ValueError("Multiple process/session outputs for one fresh invocation")
        report = data.decode("utf-8")
        if f"INVOCATION {invocation}\n" not in report or f"SESSION token={session} pid={pid}\n" not in report:
            raise ValueError("Committed filename/header invocation or session mismatch")
        apk_line = f"APK app={expected['app_apk_sha256']} test={expected['test_apk_sha256']}\n"
        if apk_line not in report:
            raise ValueError("Runtime app/test APK hash differs from current external artifacts")
        reports[kind] = report
    start = reports.get("start", "")
    if "START_COMPLETE" not in start or "OUTPUT_ROUTE collectorArgument=true" not in start:
        raise ValueError("Current invocation's committed startup proof missing")
    if not any(kind in reports for kind in ("fatal", "finish")):
        raise ValueError("No committed crash/final report for the current invocation")
    for kind in ("fatal", "finish"):
        if kind in reports and ("REPORT_COMPLETE" not in reports[kind] or "BOUNDARY " + kind not in reports[kind]):
            raise ValueError("Crash/final artifact incomplete or boundary mismatched")
    return reports


def check_runtime_proof(log):
    if "EXPECTED " + MANIFEST not in log or "APPLIED " + MANIFEST not in log:
        raise ValueError("Missing opt-in runner or applied-transform marker")
    if "PROOF_INVALID" in log or "broken=true" in log:
        raise ValueError("Recorder proof invalid or capacity/error flag set")
    proofs = re.findall(r"manifest=" + re.escape(MANIFEST) + r" entries=(\d+) pre=(\d+) post=(\d+) broken=false", log)
    if not any(all(int(n) > 0 for n in proof) for proof in proofs):
        raise ValueError("No actual entry and PRE/POST writer execution proof")
    has_completed_proof = "PROOF_VALID" in log and "REPORT_COMPLETE" in log
    has_dump = any("END " + reason in log for reason in ("FIRST_OFF_MAIN_ENTRY", "FIRST_GUARD_FAILURE"))
    if not (has_completed_proof or has_dump):
        raise ValueError("Neither complete final runtime proof nor a complete trigger dump")
    return has_completed_proof


def validate(original, actual, log):
    original_pairs = [(c.attrib["classname"], c.attrib["name"]) for c in original]
    actual_pairs = [(c.attrib["classname"], c.attrib["name"]) for c in actual]
    if len(original_pairs) != 100 or actual_pairs[:100] != original_pairs:
        raise ValueError("Original same-process first-100 test order differs or was not reached")
    has_completed_proof = check_runtime_proof(log)
    failures = sum(bool(c.findall("failure") or c.findall("error")) for c in actual)
    skipped = sum(bool(c.findall("skipped")) for c in actual)
    xml_356_zero_failures = len(actual) == 356 and failures == 0 and skipped == 0 and has_completed_proof and "BOUNDARY finish" in log and "BOUNDARY fatal" not in log
    return {"diagnostic_evidence": "VALID", "original_prefix": 100, "tests": len(actual),
            "failures": failures, "skipped": skipped, "xml_356_cases_zero_failures_and_skips": xml_356_zero_failures,
            "authoritative_full_matrix_identity_checked": False,
            "causal_resolution": False,
            "note": "A diagnostic pass alone cannot clear release HOLD; instrumentation changes timing."}


def check_retained_bundle(result_zip, expected, external):
    stem = "connected_android_test_additional_output/eos-measure-writer-host-" + expected["invocation"] + "/"
    hashes = {name: sha256(path) for name, path in external.items()}
    hashes["build.log"] = expected.get("build_log_sha256", "")
    with zipfile.ZipFile(result_zip) as archive:
        if any(("/" + entry).endswith("/" + stem + "run-manifest.pending") for entry in archive.namelist()):
            raise ValueError("Current host package has an uncommitted completion marker")
        for name, digest in hashes.items():
            matches = [entry for entry in archive.namelist() if ("/" + entry).endswith("/" + stem + name)]
            if len(matches) != 1 or hashlib.sha256(archive.read(matches[0])).hexdigest() != digest:
                raise ValueError("Missing or changed current host package: " + name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--original-zip", required=True, type=Path)
    parser.add_argument("--result-zip", required=True, type=Path)
    parser.add_argument("--run-manifest", required=True, type=Path,
                        help="Post-run host receipt, separate from the independent pre-run freeze")
    parser.add_argument("--pre-run-manifest", required=True, type=Path,
                        help="Host identity recorded before launch, never inferred from discovered runtime proof")
    parser.add_argument("--expected-pre-run-sha256", required=True,
                        help="Independent current CI step's pre-launch hash, never taken from the result ZIP")
    parser.add_argument("--app-apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--source-snapshot", required=True, type=Path)
    args = parser.parse_args()
    if hashlib.sha256(args.original_zip.read_bytes()).hexdigest() != ORIGINAL_ZIP_SHA:
        parser.error("Original evidence ZIP hash differs")
    try:
        check_pre_run_hash(args.pre_run_manifest, args.expected_pre_run_sha256)
        expected = check_run_manifest(json.loads(args.run_manifest.read_text()), args.pre_run_manifest,
                                      args.app_apk, args.test_apk, args.source_snapshot)
        check_retained_bundle(args.result_zip, expected,
                              {"run-manifest.json": args.run_manifest, "pre-run-manifest.json": args.pre_run_manifest,
                               "app-debug.apk": args.app_apk, "app-debug-androidTest.apk": args.test_apk,
                               "source-snapshot.json": args.source_snapshot})
        reports = read_probe_reports(args.result_zip, expected)
        evidence = "\n".join(reports.values())
        result = validate(tests_from_zip(args.original_zip), tests_from_zip(args.result_zip), evidence)
        result["retained_reports"] = sorted(reports)
        result["invocation"] = expected["invocation"]
        result["process_and_apk_identity_bound"] = True
        result["external_source_snapshot_sha256"] = expected["source_snapshot_sha256"]
        result["gradle_exit"] = expected["gradle_exit"]
        result["xml_356_cases_zero_failures_and_skips"] &= expected["gradle_exit"] == 0
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
