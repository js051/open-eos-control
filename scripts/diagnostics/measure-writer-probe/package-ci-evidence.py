#!/usr/bin/env python3
"""Two-phase host receipt for this diagnostic branch's unchanged full UI-test command.

No Gradle, adb, network, publication, or base/parent Git object access. The snapshot is
full scoped source bytes, not a patch or proof of the complete build's provenance.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[3]
HOST = Path("app/build/eos-measure-writer-host")
OUTPUT = Path("app/build/outputs/connected_android_test_additional_output")
SOURCE_PATHS = (
    "android/build.gradle.kts", "android/settings.gradle.kts", "android/gradle.properties",
    "android/gradlew", "android/gradlew.bat", "android/gradle/libs.versions.toml",
    "android/gradle/wrapper/gradle-wrapper.properties", "android/app/build.gradle.kts",
    "android/buildSrc/build.gradle.kts", "android/buildSrc/settings.gradle.kts",
    "android/buildSrc/src", "android/measureWriterProbe", "android/measureWriterProbeAndroidTest",
    "scripts/ci/run-android-ui-tests.sh", "scripts/ci/tests/test_android_ui_diagnostics.py",
    "scripts/diagnostics/measure-writer-probe", "docs/android-measure-writer-probe.md",
)
spec = importlib.util.spec_from_file_location("verify_run", Path(__file__).with_name("verify-run.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode("utf-8").strip()


def encoded_json(value):
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode("utf-8")


def source_snapshot():
    # Git's tracked/nonignored source selection excludes build outputs and caches.
    names = git("ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", *SOURCE_PATHS)
    files = {}
    for name in sorted(set(names.split("\0")) - {""}):
        path = ROOT / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("Source snapshot requires regular checked-out files")
        data = path.read_bytes()
        files[name] = {"sha256": hashlib.sha256(data).hexdigest(), "utf8": data.decode("utf-8")}
    if not files:
        raise ValueError("Source snapshot is empty")
    return encoded_json({"schema": "eos-measure-writer-scoped-source-v1", "files": files})


def prepare():
    if Path.cwd().resolve() != ROOT / "android":
        raise ValueError("Run the diagnostic wrapper from this checkout's android directory")
    ci = {key: os.environ.get("GITHUB_" + key.upper(), "")
          for key in ("run_id", "run_attempt", "job", "sha", "repository", "ref", "workflow")}
    checkout = {"commit": git("rev-parse", "HEAD"), "tree": git("rev-parse", "HEAD^{tree}")}
    invocation = uuid.uuid4().hex
    snapshot = source_snapshot()
    pre = {"schema": "eos-measure-writer-pre-run-v1", "invocation": invocation,
           "created_at_utc": datetime.now(timezone.utc).isoformat(), "ci": ci, "checkout": checkout,
           "command": verifier.expected_command(invocation), "working_directory": "android",
           "source_snapshot_sha256": hashlib.sha256(snapshot).hexdigest()}
    verifier.check_pre_run_manifest(pre)
    directory = HOST / invocation
    directory.mkdir(parents=True, exist_ok=False)
    (directory / "source-snapshot.json").write_bytes(snapshot)
    (directory / "pre-run-manifest.json").write_bytes(encoded_json(pre))
    print(invocation, verifier.sha256(directory / "pre-run-manifest.json"))


def package(invocation, pre_run_sha256, gradle_exit, log_exit):
    verifier.check_invocation(invocation)
    staging = HOST / invocation
    pre_path = staging / "pre-run-manifest.json"
    verifier.check_pre_run_hash(pre_path, pre_run_sha256)
    pre = json.loads(pre_path.read_text())
    verifier.check_pre_run_manifest(pre)
    if pre["invocation"] != invocation:
        raise ValueError("Pre-run invocation differs")
    # Separate from AGP's collector directory: AGP may recreate it during the run.
    target = OUTPUT / ("eos-measure-writer-host-" + invocation)
    target.mkdir(parents=True, exist_ok=False)
    for name in ("pre-run-manifest.json", "source-snapshot.json", "build.log"):
        shutil.copyfile(staging / name, target / name)
    if source_snapshot() != (target / "source-snapshot.json").read_bytes():
        raise ValueError("Scoped source changed after the pre-run freeze")
    if {"commit": git("rev-parse", "HEAD"), "tree": git("rev-parse", "HEAD^{tree}")} != pre["checkout"]:
        raise ValueError("Checkout identity changed after the pre-run freeze")
    for source, name in ((Path("app/build/outputs/apk/debug/app-debug.apk"), "app-debug.apk"),
                         (Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"), "app-debug-androidTest.apk")):
        if source.is_symlink() or not source.is_file() or source.stat().st_size == 0:
            raise ValueError("Current app/test APK is missing, empty, or not a regular file")
        shutil.copyfile(source, target / name)
    if log_exit:
        raise ValueError("Build-log capture failed; the current run is not fully packaged")
    receipt = {"schema": "eos-measure-writer-run-v1", "invocation": invocation,
               "completed_at_utc": datetime.now(timezone.utc).isoformat(), "gradle_exit": gradle_exit,
               "pre_run_manifest_sha256": verifier.sha256(target / "pre-run-manifest.json"),
               "source_snapshot_sha256": verifier.sha256(target / "source-snapshot.json"),
               "app_apk_sha256": verifier.sha256(target / "app-debug.apk"),
               "test_apk_sha256": verifier.sha256(target / "app-debug-androidTest.apk"),
               "build_log_sha256": verifier.sha256(target / "build.log")}
    verifier.check_run_manifest(receipt, target / "pre-run-manifest.json", target / "app-debug.apk",
                                target / "app-debug-androidTest.apk", target / "source-snapshot.json")
    reports = verifier.read_probe_reports(OUTPUT, receipt)
    verifier.check_runtime_proof("\n".join(reports.values()))
    if gradle_exit == 0 and ("finish" not in reports or "fatal" in reports):
        raise ValueError("Successful Gradle run lacks a current normal-finish report")
    publish_receipt(target, receipt)


def publish_receipt(target, receipt):
    # The enclosing invocation directory was created exclusively for this package.
    # Publish last, after successful write/flush/close. No fallible work follows.
    pending = target / "run-manifest.pending"
    if (target / "run-manifest.json").exists():
        raise ValueError("Host completion marker already exists")
    with pending.open("xb") as output:
        output.write(encoded_json(receipt))
        output.flush()
    pending.replace(target / "run-manifest.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    commands.add_parser("prepare")
    finish = commands.add_parser("package")
    finish.add_argument("--invocation", required=True)
    finish.add_argument("--pre-run-sha256", required=True)
    finish.add_argument("--gradle-exit", required=True, type=int)
    finish.add_argument("--log-exit", required=True, type=int)
    args = parser.parse_args()
    try:
        if args.action == "prepare":
            prepare()
        else:
            package(args.invocation, args.pre_run_sha256, args.gradle_exit, args.log_exit)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, "EOS measure-writer host evidence failed: " + str(error) + "\n")


if __name__ == "__main__":
    main()
