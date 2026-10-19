#!/usr/bin/env python3
"""Bounded, offline JVM execution of exact maintenance dispatch, registered operation job, and Disconnect join."""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jars", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--phase", choices=("baseline", "current"), default="baseline")
    parser.add_argument("--ref")
    parser.add_argument("--test-class", action="append", help="Run only these compiled JUnit classes (may repeat)")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    ref = args.ref or ("1ca763aa62a6a5fed229b03b3cb3dacf500b32f6" if args.phase == "baseline" else "WORKTREE")
    if ref != "WORKTREE":
        ref = subprocess.check_output(["git", "rev-parse", ref], cwd=repo, text=True).strip()
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=True)
    jars = args.jars.resolve()
    manifest = json.loads((jars / "installation-receipt.json").read_text())
    verified = {}
    for artifact in manifest["artifacts"]:
        path = jars / artifact["file"]
        actual = digest(path.read_bytes())
        if actual != artifact["sha256"]:
            raise ValueError(f"Existing tool checksum mismatch: {path.name}")
        verified[path.name] = actual

    def classpath(names):
        paths = [jars / (name + ".jar") for name in names]
        for path in paths:
            if path.name not in verified:
                raise ValueError(f"Required tool missing: {path.name}")
        return os.pathsep.join(map(str, paths))

    compiler = classpath(
        [
            "kotlin-compiler-embeddable-2.2.21",
            "kotlin-stdlib-2.2.21",
            "kotlin-script-runtime-2.2.21",
            "kotlin-reflect-1.6.10",
            "kotlin-daemon-embeddable-2.2.21",
            "kotlinx-coroutines-core-jvm-1.8.0",
            "annotations-23.0.0",
        ]
    )
    runtime = classpath(
        [
            "kotlin-stdlib-2.2.21",
            "kotlinx-coroutines-core-jvm-1.9.0",
            "annotations-23.0.0",
            "junit-4.13.2",
            "hamcrest-core-1.3",
        ]
    )
    hashes = {}
    extracts = {}
    files = []

    def source(name, package="data"):
        path = "android/app/src/main/java/dev/openeos/control/" + package + "/" + name
        data = (
            (repo / path).read_bytes()
            if ref == "WORKTREE"
            else subprocess.check_output(["git", "show", ref + ":" + path], cwd=repo)
        )
        hashes[path] = digest(data)
        return data.decode()

    def between(text, begin, end):
        if text.count(begin) != 1 or text.count(end) != 1:
            raise ValueError("Ambiguous production boundary: " + begin)
        value = begin + text.split(begin, 1)[1].split(end, 1)[0]
        extracts[begin.strip()] = digest(value.encode())
        return value

    for name in (
        "BridgeShutterReleaseSession.kt",
        "ShutterReleaseSession.kt",
        "BridgeBulbRequestSession.kt",
        "BridgeBulbDeadline.kt",
        "MaintenanceEvidence.kt",
        "ShootingSettingJson.kt",
        "StrictMediaJson.kt",
    ):
        target = output / name
        target.write_text(source(name))
        files.append(target)
    target = output / "MaintenanceWorkflow.kt"
    target.write_text(source("MaintenanceWorkflow.kt", "ui"))
    files.append(target)
    if args.phase == "current":
        path = "android/app/src/test/java/dev/openeos/control/ui/MaintenanceWorkflowTest.kt"
        data = (
            (repo / path).read_bytes()
            if ref == "WORKTREE"
            else subprocess.check_output(["git", "show", ref + ":" + path], cwd=repo)
        )
        hashes[path] = digest(data)
        target = output / "MaintenanceWorkflowTest.kt"
        target.write_bytes(data)
        files.append(target)
    # requestJson includes the narrow capture recovery helper; retain its production route enum.
    capture = source("CaptureConfigurationEvidence.kt")
    target = output / "CaptureConfigurationOperation.kt"
    target.write_text(
        "package dev.openeos.control.data\n"
        + between(capture, "enum class CaptureConfigurationOperation(", "enum class CaptureConfigurationWriteEvidence")
    )
    files.append(target)
    client = source("DesktopBridgeClient.kt")
    methods = between(client, "    suspend fun retireMaintenanceLocally()", "    suspend fun closeConnectionAttempt()")
    methods += between(client, "    suspend fun stopEventPolling()", "    suspend fun capabilities()")
    methods += between(client, "    fun maintenanceAccess(", "    private fun JSONObject.toMaintenanceMap()")
    methods += between(client, "    private fun JSONObject.toMaintenanceMap()", "    suspend fun syncCameraClock()")
    methods += between(client, "    suspend fun startLiveView(request:", "    fun liveViewFrameUrl(")
    methods += between(client, "    private suspend fun postJson(", "    private suspend fun postEmptyJson(")
    methods += between(client, "    private suspend fun requestJson(", "    private suspend fun requestEventJson(")
    methods += between(client, "    private fun requireCurrentSession(", "    private object BridgeShutterStart")
    ui = source("CameraViewModel.kt", "ui")
    ui_methods = between(
        ui, "    fun submitMaintenance(draft: MaintenanceDraft) {", "    fun recoverMaintenance(id: String)"
    )
    ui_methods += between(ui, "    fun disconnect() {", "    /** Explicit receipt recovery")
    ui_methods += between(ui, "    private fun launchCameraOperation(", "    private fun refreshCapabilityEvidence()")
    state = between(
        client,
        "    private class MaintenanceRestoreOwner"
        if "    private class MaintenanceRestoreOwner" in client
        else "    private class MaintenanceBridgeOwnerContext",
        "    val liveViewStopRequired:",
    )
    fixture_dir = repo / "scripts/validation/fixtures/android-maintenance-dispatch-host"
    fixture_hashes = {}
    for fixture in sorted(fixture_dir.glob("*.kt")):
        if args.phase == "baseline" and fixture.name == "MaintenanceAtomicFenceTest.kt":
            continue
        data = fixture.read_text()
        fixture_hashes[fixture.name] = digest(data.encode())
        data = data.replace("    // HOST_PRODUCTION_LIVE_VIEW_STATE", state)
        if "// HOST_PRODUCTION_METHODS" in data:
            assert data.count("// HOST_PRODUCTION_METHODS") == 1
            data = data.replace("    // HOST_PRODUCTION_METHODS", methods)
        if "// HOST_PRODUCTION_UI_METHODS" in data:
            assert data.count("// HOST_PRODUCTION_UI_METHODS") == 1
            data = data.replace("    // HOST_PRODUCTION_UI_METHODS", ui_methods)
        target = output / fixture.name
        target.write_text(data)
        files.append(target)
    jvm = ["java", "-XX:ActiveProcessorCount=1", "-Xmx256m"]
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
    compiled = subprocess.run(command, capture_output=True, text=True, timeout=180)
    (output / "compile.log").write_text(compiled.stdout + compiled.stderr)
    receipt = {
        "at": datetime.datetime.now(datetime.UTC).isoformat(),
        "phase": args.phase,
        "ref": ref,
        "source_sha256": hashes,
        "extracted_methods_sha256": extracts,
        "fixture_sha256": fixture_hashes,
        "verified_tools": verified,
        "compile_exit": compiled.returncode,
        "jvm_heap_limit_mb": 256,
        "active_processor_count": 1,
        ("boundary"): (
            "Entire production MaintenanceWorkflow and MaintenanceEvidence, BridgeShutterRe"
            "leaseSession, ShutterReleaseSession, BridgeBulbRequestSession, BridgeBulbDeadl"
            "ine, ShootingSettingJson and StrictMediaJson; byte-exact CameraViewModel submi"
            "tMaintenance/cancelMaintenance/publishMaintenanceStopWarning, full launchCamer"
            "aOperation registered-job lifetime and disconnect join; byte-exact DesktopBrid"
            "geClient maintenance close/local retirement, maintenanceAccess, maintenanceJso"
            "n and cleanup helpers, live-view Start/Stop, postJson, requestJson, stopEventP"
            "olling, exact-owner helpers. Explicit synthetic HTTP Call/Response/InputStream"
            ", typed-registry JSON, model, focus and unrelated transport adapters. No Andro"
            "id/OkHttp/Compose/device execution."
        ),
    }
    success = False
    if compiled.returncode:
        print(compiled.stdout + compiled.stderr)
    else:
        test = "BaselineMaintenanceDispatchTest" if args.phase == "baseline" else "FixedMaintenanceDispatchTest"
        tests = ["dev.openeos.control.ui." + test]
        if args.phase == "current":
            tests += [
                "dev.openeos.control.ui.MaintenanceWorkflowTest",
                "dev.openeos.control.data.MaintenanceAtomicFenceTest",
                "dev.openeos.control.data.MaintenanceDispatchStrictTest",
            ]
        if args.test_class:
            tests = args.test_class
        receipt["test_classes"] = tests
        executed = subprocess.run(
            jvm + ["-cp", str(output / "tests.jar") + os.pathsep + runtime, "org.junit.runner.JUnitCore", *tests],
            capture_output=True,
            text=True,
            timeout=70,
        )
        log = executed.stdout + executed.stderr
        print(log)
        (output / "junit.log").write_text(log)
        receipt["junit_exit"] = executed.returncode
        if args.phase == "baseline":
            names = [
                "cancelledDispatchMustReleaseRegisteredJobBeforeDisconnectCleanup",
                "realFifteenSecondDispatchDeadlineMustReleaseDisconnectJoin",
            ]
            success = (
                executed.returncode == 1
                and "Tests run: 2,  Failures: 2" in log
                and all(name in log for name in names)
                and log.count(

                        "java.lang.AssertionError: Disconnect must reach repository cleanup while the c"
                        "ancelled original maintenance response body is still held"

                )
                == 1
                and log.count(

                        "java.lang.AssertionError: Real 15-second maintenance dispatch timeout must rel"
                        "ease the registered job joined by Disconnect while its body is still held"

                )
                == 1
            )
            receipt["expected_runtime_red_cases_verified"] = success
        else:
            success = executed.returncode == 0 and "OK (" in log
            receipt["runtime_tests_passed"] = success
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
