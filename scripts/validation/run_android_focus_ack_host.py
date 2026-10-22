#!/usr/bin/env python3
"""Bounded offline JVM execution of exact drive, Tap AF and Click WB entries and receipt boundary."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

from android_host_runtime import AndroidHostRuntime, add_runtime_arguments

FOCUS_RECEIPT_PATH = Path("docs/validation/focus-ack-receipts-2026-10-17.json")
FOCUS_RECEIPT_SHA256 = "6e98a74df4925532bb33760f649194ac8b889aebed534b1a3dda0b77c1ebd45c"
FOCUS_BASELINE_REF = "27e6e3cbba2faaaebe48923124608f47dee5410d"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify_current_focus_guard(receipt_path, methods, restored):
    """Bind the unchanged current-source proof to the original committed red/green evidence."""
    try:
        data = receipt_path.read_bytes()
    except OSError as error:
        raise ValueError("Original focus receipt is missing or unreadable") from error
    if digest(data) != FOCUS_RECEIPT_SHA256:
        raise ValueError("Original focus receipt checksum mismatch")
    receipt = json.loads(data)
    if receipt["baseline"] != FOCUS_BASELINE_REF:
        raise ValueError("Original focus receipt baseline mismatch")
    phases = {entry["phase"]: entry for entry in receipt["android_extracts"]}
    method_hashes = {}
    for phase in ("red", "green"):
        if phases[phase]["expected_result_verified"] is not True:
            raise ValueError("Original focus receipt has unverified " + phase + " evidence")
        method_hashes[phase] = {
            entry["file"]: entry["sha256"] for entry in phases[phase]["methods"]
        }
    if set(methods) != {"DRIVE", "COORDINATES", "COMMAND", "REQUEST_OK"}:
        raise ValueError("Current focus method set differs from original evidence")
    for name, method in methods.items():
        if digest(method.encode()) != method_hashes["green"][name]:
            raise ValueError("Current focus method differs from pinned green receipt: " + name)
        if name != "REQUEST_OK" and method_hashes["red"][name] != method_hashes["green"][name]:
            raise ValueError("Original focus receipts disagree on unchanged method: " + name)
    if digest(restored.encode()) != method_hashes["red"]["REQUEST_OK"]:
        raise ValueError("Inverse receipt predicate does not restore original requestOk")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jars", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--phase", choices=("baseline", "current"), default="baseline")
    parser.add_argument("--ref")
    add_runtime_arguments(parser)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    ref = args.ref or ("27e6e3cb" if args.phase == "baseline" else "WORKTREE")
    if ref != "WORKTREE":
        ref = subprocess.check_output(["git", "rev-parse", ref], cwd=repo, text=True).strip()
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=True)
    host = AndroidHostRuntime(
        repo, args.jars, java=args.java, receipt=args.tool_receipt, gradle_manifest=args.gradle_manifest,
    )
    compiler = host.compiler_classpath
    runtime = host.test_classpath
    hashes = {}
    extracts = {}
    files = []

    def source(name):
        path = "android/app/src/main/java/dev/openeos/control/data/" + name
        data = (
            (repo / path).read_bytes()
            if ref == "WORKTREE"
            else subprocess.check_output(["git", "show", ref + ":" + path], cwd=repo)
        )
        hashes[path] = digest(data)
        return data.decode()

    for name in ("ShootingSettingJson.kt", "StrictMediaJson.kt"):
        target = output / name
        target.write_text(source(name))
        files.append(target)
    client = source("CcapiClient.kt")
    markers = {
        "DRIVE": (
            "    suspend fun driveFocus(",
            "    suspend fun setLiveViewMagnification(",
        ),
        "COORDINATES": ("    suspend fun tapFocus(", "    fun liveViewFrameUrl("),
        "COMMAND": (
            "    private suspend fun commandOk(",
            "    private suspend fun withRecoverableFocusRelease(",
        ),
        "REQUEST_OK": (
            "    private suspend fun requestOk(",
            "    private suspend fun requestLiveViewFrame(",
        ),
    }
    methods = {}
    for name, (begin, end) in markers.items():
        assert client.count(begin) == client.count(end) == 1
        methods[name] = begin + client.split(begin, 1)[1].split(end, 1)[0]
        extracts[name] = digest(methods[name].encode())
    if args.phase == "current":
        # This gate changes receipt classification only, not any client, reader or owner.
        begin = "            // Focus receipt integrity is independent of bounded-body/strict-client flags."
        end = "                // A partial representation cannot acknowledge the original physical control."
        request_ok = methods["REQUEST_OK"]
        restored = (
            request_ok[: request_ok.index(begin)]
            + "            if (stillCapture || liveViewControl || releaseControl) {\n"
            + request_ok[request_ok.index(end) :]
        )
        verify_current_focus_guard(repo / FOCUS_RECEIPT_PATH, methods, restored)
    fixture_hashes = {}
    for fixture in sorted((repo / "scripts/validation/fixtures/android-focus-ack-host").glob("*.kt")):
        data = fixture.read_text()
        fixture_hashes[fixture.name] = digest(data.encode())
        for name, method in methods.items():
            data = data.replace("    // HOST_" + name + "\n", method + "\n")
        target = output / fixture.name
        target.write_text(data)
        files.append(target)
    jvm = host.command(tests=False)
    command = jvm + [
        "-cp",
        compiler,
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib",
        "-no-reflect",
        "-jvm-target",
        "17",
        "-classpath",
        runtime,
        "-d",
        str(output / "tests.jar"),
        *map(str, files),
    ]
    compiled = subprocess.run(command, check=False, capture_output=True, text=True, timeout=180)
    (output / "compile.log").write_text(compiled.stdout + compiled.stderr)
    receipt = {
        "phase": args.phase,
        "ref": ref,
        "source_sha256": hashes,
        "extracted_methods_sha256": extracts,
        "source_guards": [
            "all three public entries and commandOk are byte-for-byte identical to baseline",
            "removing only the independent receipt predicate restores the original requestOk method",
        ]
        if args.phase == "current"
        else [],
        "fixture_sha256": fixture_hashes,
        "verified_tools": host.verified,
        "runtime_contract": host.receipt(),
        "compile_exit": compiled.returncode,
        ("boundary"): (
            "Exact production driveFocus, tapFocus, clickWhiteBalance, commandOk and reques"
            "tOk. Call/Response/InputStream, discovery, geometry and status are synthetic. "
            "This is not full Android/OkHttp, socket, browser or device execution. Native M"
            "ockWebServer tests are authored separately and NOT_RUN."
        ),
        "jvm_heap_limit_mb": 256,
        "active_processor_count": 1,
    }
    success = False
    if compiled.returncode:
        print(compiled.stdout + compiled.stderr)
    else:
        executed = subprocess.run(
            host.command(tests=True)
            + [
                "-cp",
                str(output / "tests.jar") + os.pathsep + runtime,
                "org.junit.runner.JUnitCore",
                "dev.openeos.control.data.FocusAckTest",
            ],
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
        log = executed.stdout + executed.stderr
        print(log)
        (output / "junit.log").write_text(log)
        receipt["junit_exit"] = executed.returncode
        expected_failures = tuple(
            control + variant + "CannotSucceed"
            for control in ("drive", "tap", "whiteBalance")
            for variant in ("206", "Range", "EmptyRange")
        )
        success = (
            (
                executed.returncode == 1
                and "Tests run: 13,  Failures: 9" in log
                and all(failure in log for failure in expected_failures)
            )
            if args.phase == "baseline"
            else (executed.returncode == 0 and "OK (13 tests)" in log)
        )
        receipt["expected_result_verified"] = success
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
