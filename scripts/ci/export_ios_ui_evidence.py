#!/usr/bin/env python3
"""Manually export bounded, synthetic-only shutter recovery evidence.

This helper is not wired to an automatic CI export/upload step. It reads an
existing result bundle only; it neither runs tests nor changes their outcome.
Requires an existing macOS host with Xcode 16+ xcresulttool; no installs or
legacy/full dumps.
Apple documents the modern commands and their runtime help in:
https://developer.apple.com/documentation/xcode-release-notes/xcode-16_3-release-notes
The original xcresult remains the source of truth, including failure details.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


TESTS = (
    "testPreviousConnectionWarningStaysSeparateFromNewConnectionStop",
    "testShutterRecoveryStopRemainsReachableAcrossLanguagesFontsAndRotation",
    "testRecoverySheetStopAndDisconnectHaveIndependentHitTargets",
)
HIT_TARGET_CASES = (
    "english-UICTContentSizeCategoryXS-1-active",
    "english-UICTContentSizeCategoryXS-3-unknown",
    "traditionalChinese-UICTContentSizeCategoryAccessibilityXXXL-1-unknown",
    "traditionalChinese-UICTContentSizeCategoryAccessibilityXXXL-4-active",
)
RECONNECT_PHASES = ("before-reconnect", "after-reconnect", "failed-reconnect")
MATRIX_SAMPLES = (
    "shutter-recovery-english-UICTContentSizeCategoryXS-active-1",
    "shutter-recovery-english-UICTContentSizeCategoryAccessibilityXXXL-unknown-3",
    "shutter-recovery-traditionalChinese-UICTContentSizeCategoryXS-unknown-1",
    "shutter-recovery-traditionalChinese-UICTContentSizeCategoryAccessibilityXXXL-active-4",
)
MAX_ARTIFACT_BYTES = 24 * 1024 * 1024  # Below the 32 MiB materialization limit, even zipped.
SUMMARY_RESERVE = 64 * 1024
MAX_PNG_BYTES = 4 * 1024 * 1024
MAX_TEXT_BYTES = 16 * 1024
MAX_JSON_BYTES = 2 * 1024 * 1024
MAX_FILES = 64
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
RESULTS = {"Passed", "Failed", "Skipped", "Expected Failure", "Unknown"}


class EvidenceError(Exception):
    """Messages must be fixed diagnostics, never raw tool output or device data."""


def command(arguments: list[str]) -> bytes:
    """Bound time and bytes read; never publish arbitrary stdout/stderr."""
    try:
        with tempfile.TemporaryFile() as output:
            process = subprocess.run(
                ["xcrun", "xcresulttool", *arguments], stdout=output,
                stderr=subprocess.DEVNULL, timeout=90, check=False,
            )
            if process.returncode:
                operation = " ".join(arguments[:2])
                raise EvidenceError(f"xcresulttool {operation} failed (exit {process.returncode}); original xcresult retained")
            output.seek(0)
            data = output.read(MAX_JSON_BYTES + 1)
            if len(data) > MAX_JSON_BYTES:
                raise EvidenceError("xcresulttool output exceeded the JSON/help size limit")
            return data
    except FileNotFoundError as error:
        raise EvidenceError("Xcode xcresulttool is unavailable") from error
    except subprocess.TimeoutExpired as error:
        raise EvidenceError("xcresulttool command exceeded the 90-second limit") from error


def read_json(data: bytes):
    if len(data) > MAX_JSON_BYTES:
        raise EvidenceError("JSON exceeded the size limit")
    try:
        return json.loads(data)
    except (ValueError, UnicodeError) as error:
        raise EvidenceError("xcresulttool returned invalid JSON") from error


def selected_tests(payload: object) -> dict[str, dict]:
    if not isinstance(payload, dict) or not isinstance(payload.get("testNodes"), list):
        raise EvidenceError("Unsupported xcresulttool test tree schema")
    selected: dict[str, dict] = {}
    pending = list(payload["testNodes"])
    while pending:
        node = pending.pop()
        if not isinstance(node, dict):
            raise EvidenceError("Unsupported xcresulttool test node schema")
        children = node.get("children", [])
        if not isinstance(children, list):
            raise EvidenceError("Unsupported xcresulttool test children schema")
        pending.extend(children)
        if node.get("nodeType") != "Test Case":
            continue
        identifier = node.get("nodeIdentifier", "")
        for name in TESTS:
            if identifier not in (f"OpenEOSControlUITests/{name}", f"OpenEOSControlUITests/{name}()"):
                continue
            result = node.get("result")
            result = result if isinstance(result, str) and result in RESULTS else "Unknown"
            entry = selected.setdefault(name, {"identifier": identifier, "results": []})
            if result not in entry["results"]:
                entry["results"].append(result)
    return selected


def attachment_name(suggested: object, test: str) -> str | None:
    if not isinstance(suggested, str) or len(suggested) > 256:
        return None
    if test == TESTS[0]:
        base = r"(?:previous-warning-and-current-stop|shutter-recovery-disconnect-(?:before|after)-tap(?:-geometry)?)"
    elif test == TESTS[1]:
        base = "(?:" + "|".join(map(re.escape, MATRIX_SAMPLES)) + ")"
    elif test == TESTS[2]:
        cases = "(?:" + "|".join(map(re.escape, HIT_TARGET_CASES)) + ")"
        reconnect = "(?:" + "|".join(RECONNECT_PHASES) + ")"
        base = (rf"(?:shutter-recovery-disconnect-{cases}-before-disconnect(?:-geometry)?|"
                rf"shutter-recovery-connect-{cases}-{reconnect}(?:-geometry)?|"
                rf"shutter-recovery-sheet-stop-{cases})")
    else:
        return None
    # Xcode may append a counter/UUID and a file extension to the supplied name.
    match = re.fullmatch(rf"({base})(?:_[A-Za-z0-9-]+)*(?:\.(?:png|txt))?", suggested)
    return match[1] if match else None


def attachment_priority(name: str) -> tuple[int, bool, str]:
    if name.startswith("shutter-recovery-connect-"):
        priority = 0 if name.removesuffix("-geometry").endswith("-failed-reconnect") else 2
    else:
        priority = 1
    # Keep the failed-wait diagnosis first, followed by original hit-target proof.
    # Within each group, preserve the small geometry files before larger PNGs.
    return priority, not name.endswith("-geometry"), name


def geometry_text(data: bytes) -> bytes:
    """Accept only fixed IDs, geometry and booleans emitted by the UI fixture."""
    try:
        text = data.decode("utf-8")
    except UnicodeError as error:
        raise EvidenceError("Geometry attachment is not UTF-8") from error
    number = r"(?:[+-]?(?:\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|inf|nan))"
    rect = rf"\({number}, {number}, {number}, {number}\)"
    control = r"(?:disconnect-menu-button|release-shutter-button|shutter-button|more-actions-button|connect-button)"
    phases = "|".join(map(re.escape, ("before-tap", "after-tap", *(
        case + "-before-disconnect" for case in HIT_TARGET_CASES
    ))))
    patterns = (
        rf"\[OEC_SHUTTER_DISCONNECT_GEOMETRY\] (?:{phases})",
        rf"window={rect}",
        r"more-actions-sheet-exists=(?:true|false)",
        rf"{control} count=\d+",
        rf"{control}\[[0-3]\] enabled=(?:true|false) hittable=(?:true|false) frame={rect}",
        rf"(?:release-shutter-button|shutter-button)\[[0-3]\] disconnect-overlap={rect} contains-disconnect-center=(?:true|false)",
        r"(?:shutter-release-warning|previous-shutter-release-warning|camera-model-status)-exists=(?:true|false)",
    )
    lines = text.splitlines()
    if lines and lines[0].startswith("[OEC_SHUTTER_CONNECT_GEOMETRY]"):
        reconnect_phases = "|".join(re.escape(f"{case}-{phase}")
                                    for case in HIT_TARGET_CASES for phase in RECONNECT_PHASES)
        peers = (r"(?:offline-preview-button|release-shutter-button|disconnect-menu-button|"
                 r"more-actions-button|preset-http-button|preset-https-button|preset-simulator-button)")
        buttons = rf"(?:connect-button|{peers})"
        # Keep the new reconnect schema separate; do not broaden disconnect text.
        patterns = (
            rf"\[OEC_SHUTTER_CONNECT_GEOMETRY\] (?:{reconnect_phases})",
            rf"(?:window|connection-scroll-view-frame|connect-visible-frame)={rect}",
            r"(?:connection-scroll-view-exists|connect-center-in-viewport|connect-fully-in-viewport|"
            r"shutter-release-warning-exists|previous-shutter-release-warning-exists|camera-model-status-exists|"
            r"model-fixture-session-1|model-fixture-session-2|offline-preview-visible)=(?:true|false)",
            r"(?:keyboard|alert)-count=\d+",
            rf"{buttons} count=\d+",
            rf"{buttons}\[[0-3]\] enabled=(?:true|false) hittable=(?:true|false) frame={rect}",
            rf"{peers}\[[0-3]\] connect-overlap={rect} contains-connect-center=(?:true|false)",
        )
    if not lines or len(lines) > 128 or any(
        not any(re.fullmatch(pattern, line) for pattern in patterns) for line in lines
    ):
        raise EvidenceError("Geometry attachment contains unsupported fields")
    return ("\n".join(lines) + "\n").encode("utf-8")


def collect_attachments(staging: Path, output: Path, test: str, report: dict,
                        budget: int = MAX_ARTIFACT_BYTES - SUMMARY_RESERVE) -> None:
    manifest = staging / "manifest.json"
    if not manifest.is_file() or manifest.is_symlink() or manifest.stat().st_size > MAX_JSON_BYTES:
        raise EvidenceError("Missing, unsafe or oversized attachment manifest")
    groups = read_json(manifest.read_bytes())
    if not isinstance(groups, list):
        raise EvidenceError("Unsupported attachment manifest schema")
    candidates = []
    for group in groups:
        if not isinstance(group, dict) or not isinstance(group.get("attachments"), list):
            raise EvidenceError("Unsupported attachment manifest group schema")
        for attachment in group["attachments"]:
            if not isinstance(attachment, dict):
                raise EvidenceError("Unsupported attachment entry schema")
            name = attachment_name(attachment.get("suggestedHumanReadableName"), test)
            if name is not None:
                candidates.append((name, attachment.get("exportedFileName")))
    if len(candidates) > 1024:
        raise EvidenceError("Attachment manifest exceeded the entry limit")
    for name, filename in sorted(candidates, key=lambda item: attachment_priority(item[0])):
        if not isinstance(filename, str) or Path(filename).name != filename or "\\" in filename:
            raise EvidenceError("Unsafe attachment filename")
        source = staging / filename
        if source.is_symlink() or not source.is_file():
            raise EvidenceError("Missing or unsafe selected attachment")
        is_text = name.endswith("-geometry")
        extension = ".txt" if is_text else ".png"
        if source.suffix.lower() != extension:
            report["warnings"].append("Selected attachment has an unsupported file type")
            continue
        limit = MAX_TEXT_BYTES if is_text else MAX_PNG_BYTES
        if source.stat().st_size > limit:
            report["warnings"].append(f"Omitted oversized attachment: {name}")
            continue
        data = source.read_bytes()
        if is_text:
            data = geometry_text(data)
        elif not data.startswith(PNG_SIGNATURE):
            raise EvidenceError("Selected screenshot is not a PNG")
        if len(report["files"]) >= MAX_FILES or report["bytes"] + len(data) > budget:
            report["warnings"].append(f"Omitted attachment at artifact size/count limit: {name}")
            continue
        # Preserve retry evidence without trusting Xcode's exported UUID/paths.
        destination = f"{len(report['files']) + 1:02d}-{name}{extension}"
        (output / destination).write_bytes(data)
        report["bytes"] += len(data)
        report["files"].append({"file": destination, "test": test, "bytes": len(data)})


def export_evidence(bundle: Path, output: Path, run=command) -> dict:
    report = {"scope": "Synthetic iOS Simulator shutter recovery; no physical-camera validation",
              "status": "incomplete", "tests": {}, "files": [], "bytes": 0, "warnings": []}
    if not bundle.is_dir():
        report["warnings"].append("No xcresult bundle; tests may not have started")
        return report
    try:
        for arguments, flags in (
            (["help", "export", "attachments"], ("--path", "--output-path", "--test-id")),
            (["help", "get", "test-results", "tests"], ("--path",)),
        ):
            help_text = run(arguments).decode("utf-8", errors="replace")
            if any(flag not in help_text for flag in flags):
                raise EvidenceError("Required Xcode 16+ commands/flags unsupported; original xcresult retained")
        tests = selected_tests(read_json(run(["get", "test-results", "tests", "--path", str(bundle)])))
        # Prioritize direct Stop/Disconnect hit-target proof, then the previous
        # connection flow, then four representative language/font/orientation PNGs.
        for test in (TESTS[2], TESTS[0], TESTS[1]):
            if test not in tests:
                report["warnings"].append(f"Test not found in xcresult: {test}")
                continue
            report["tests"][test] = tests[test]["results"]
            before = len(report["files"])
            try:
                with tempfile.TemporaryDirectory(prefix="ios-ui-export-") as temporary:
                    staging = Path(temporary)
                    run(["export", "attachments", "--path", str(bundle),
                         "--output-path", str(staging), "--test-id", tests[test]["identifier"]])
                    collect_attachments(staging, output, test, report)
            except EvidenceError as error:
                report["warnings"].append(f"{test}: {error}")
            if len(report["files"]) == before:
                report["warnings"].append(f"No selected attachments exported: {test}")
        if not report["warnings"]:
            report["status"] = "exported"
    except EvidenceError as error:
        report["warnings"].append(str(error))
    except OSError:
        report["warnings"].append("Evidence export encountered a filesystem error")
    return report


def write_summary(output: Path, report: dict) -> None:
    # Never dump test logs, failure messages, device IDs, raw manifests or metadata.
    lines = ["# iOS shutter recovery UI evidence", "", report["scope"], "",
             f"Evidence status: {report['status']}",
             f"Exported {len(report['files'])} files / {report['bytes']} bytes (24 MiB artifact cap).",
             "Test outcomes below are independent of evidence export success.",
             "Manual export only: this helper does not run tests or arrange artifact uploads.",
             "Selected evidence includes up to four representative matrix PNGs from the supplied bundle.", ""]
    lines.extend(f"- {test}: {', '.join(results)}" for test, results in report["tests"].items())
    lines.extend(f"- Warning: {warning}" for warning in dict.fromkeys(report["warnings"]))
    lines.extend(["", "## Attachments", ""])
    lines.extend(f"- [{item['file']}]({item['file']}) ({item['bytes']} bytes)" for item in report["files"])
    summary = "\n".join(lines) + "\n"
    if len(summary.encode()) > SUMMARY_RESERVE:
        raise EvidenceError("Evidence summary exceeded reserved size")
    (output / "summary.md").write_text(summary, encoding="utf-8")
    print(summary)
    if report["status"] != "exported":
        print("::warning::iOS UI evidence is incomplete; inspect the evidence summary and original xcresult")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
            stream.write(summary)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--result-bundle", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    # Refuse reused output so stale files cannot evade the artifact byte cap.
    args.output_dir.mkdir(parents=True, exist_ok=False)
    report = export_evidence(args.result_bundle, args.output_dir)
    write_summary(args.output_dir, report)
    return 0 if report["status"] == "exported" else 1


if __name__ == "__main__":
    raise SystemExit(main())
