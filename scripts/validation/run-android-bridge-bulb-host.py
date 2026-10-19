#!/usr/bin/env python3
"""Run bounded JVM-only production Bulb tests using already-installed, receipt-verified jars.

This runner never downloads dependencies, invokes Gradle, or builds native code.
The baseline mode deliberately proves production counterexamples, not syntax failures.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--jars", type=Path, required=True, help="Existing JVM tool directory with installation-receipt.json"
    )
    parser.add_argument("--out", type=Path, required=True, help="Separate evidence/output directory")
    parser.add_argument("--ref", help="Git commit to read; defaults to immutable baseline or current worktree by phase")
    parser.add_argument("--phase", choices=("baseline", "current"), default="baseline")
    parser.add_argument(
        "--ui-source", type=Path, help="Optional saved production CameraViewModel source for counterexample evidence"
    )
    parser.add_argument("--test-class", action="append", help="Run only these compiled JUnit classes (may repeat)")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    ref = args.ref or ("4faea09a2697b192bb84133f0531704dab302146" if args.phase == "baseline" else "WORKTREE")
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=True)
    jars = args.jars.resolve()
    manifest = json.loads((jars / "installation-receipt.json").read_text())
    verified = {}
    for artifact in manifest["artifacts"]:
        path = jars / artifact["file"]
        actual = sha256(path.read_bytes())
        if actual != artifact["sha256"]:
            raise ValueError(f"Existing tool checksum mismatch: {path.name}")
        verified[path.name] = actual

    def classpath(names: list[str]) -> str:
        paths = [jars / (name + ".jar") for name in names]
        for path in paths:
            if path.name not in verified:
                raise ValueError(f"Required tool missing from verified receipt: {path.name}")
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
    source_hashes = {}

    def source(name: str, directory: str = "main", package: str = "data") -> str:
        relative = f"android/app/src/{directory}/java/dev/openeos/control/{package}/" + name
        data = (
            (repo / relative).read_bytes()
            if ref == "WORKTREE"
            else subprocess.check_output(["git", "show", ref + ":" + relative], cwd=repo)
        )
        source_hashes[relative] = sha256(data)
        return data.decode()

    session = source("BridgeShutterReleaseSession.kt")
    (output / "BridgeShutterReleaseSession.kt").write_text(session)
    generic_session = source("ShutterReleaseSession.kt")
    (output / "ShutterReleaseSession.kt").write_text(generic_session)

    def between(text: str, begin: str, end: str) -> str:
        if text.count(begin) != 1 or text.count(end) != 1:
            raise ValueError(f"Ambiguous production declaration boundary: {begin}")
        return begin + text.split(begin, 1)[1].split(end, 1)[0]

    fixtures = repo / "scripts/validation/fixtures/android-bridge-bulb-host"
    compile_files = [output / "BridgeShutterReleaseSession.kt", output / "ShutterReleaseSession.kt"]
    methods = ""
    fixture_hashes = {}
    if args.phase == "baseline":
        client = source("DesktopBridgeClient.kt")
        methods = between(
            client, "    suspend fun startBulbExposure(): CameraStatus {", "    suspend fun holdAutofocus("
        )
        methods += between(
            client,
            "    private fun parseBulbStatus(body: JSONObject): CameraStatus {",
            "    private suspend fun readBridgeStatus(",
        )
        fixture = fixtures / "BaselineOwnershipCounterexampleTest.kt"
        fixture_text = fixture.read_text()
        assert fixture_text.count("    // HOST_PRODUCTION_BULB_METHODS") == 1
        target = output / fixture.name
        target.write_text(fixture_text.replace("    // HOST_PRODUCTION_BULB_METHODS", methods))
        fixture_hashes[fixture.name] = sha256(fixture.read_bytes())
        compile_files.append(target)
        test_classes = ["dev.openeos.control.data.BaselineOwnershipCounterexampleTest"]
        boundary = (
            "Entire unchanged production BridgeShutterReleaseSession and ShutterReleaseSess"
            "ion; byte-exact DesktopBridgeClient Bulb methods. Synthetic CameraStatus, type"
            "d-map JSON, endpoint and HTTP adapters. No full Android/OkHttp/Compose/device "
            "execution."
        )
    else:
        for name in ("BridgeBulbRequestSession.kt", "BridgeBulbDeadline.kt", "BridgeBulbAccess.kt"):
            target = output / name
            target.write_text(source(name))
            compile_files.append(target)
        for name in (
            "BridgeBulbRequestSessionTest.kt",
            "BridgeBulbCallbackTest.kt",
            "BridgeBulbObservationRaceTest.kt",
        ):
            target = output / name
            target.write_text(source(name, "test"))
            compile_files.append(target)
        fixture = fixtures / "HostModels.kt"
        target = output / fixture.name
        target.write_bytes(fixture.read_bytes())
        fixture_hashes[fixture.name] = sha256(fixture.read_bytes())
        compile_files.append(target)
        client = source("DesktopBridgeClient.kt")
        methods = between(client, "    fun prepareOwnedBulb(): BridgeBulbAccess {", "    suspend fun holdAutofocus(")
        methods += between(client, "    private fun parseBulbStatus(", "    private suspend fun readBridgeStatus(")
        methods += between(client, "    private fun requireCurrentSession(", "    private fun sessionForRequest(")
        methods += between(client, "    suspend fun close() {", "    suspend fun info(): CameraInfo {")
        methods += between(
            client, "    suspend fun status(): CameraStatus {", "    suspend fun pollEvent(): CameraEvent {"
        )
        methods += between(client, "    private fun observeBridgeStatus(", "    private fun requireCurrentSession(")
        methods += between(client, "    private fun sessionForRequest(", "    private object BridgeShutterStart")
        methods += between(client, "    private fun requireBridgeRequestAllowed(", "    private fun newBridgeCall(")
        retirement = between(
            source("ConnectionRetirement.kt"),
            "internal fun bridgeSessionRetirementAcknowledged(",
            "internal class ConnectionRetirementPendingException",
        )
        fixture = fixtures / "ExtractedClientTest.kt"
        fixture_text = fixture.read_text()
        assert fixture_text.count("    // HOST_PRODUCTION_OWNED_METHODS") == 1
        target = output / fixture.name
        target.write_text(
            fixture_text.replace("    // HOST_PRODUCTION_OWNED_METHODS", methods).replace(
                "// HOST_PRODUCTION_RETIREMENT_ACK", retirement
            )
        )
        fixture_hashes[fixture.name] = sha256(fixture.read_bytes())
        compile_files.append(target)
        if args.ui_source:
            ui = args.ui_source.read_text()
            source_hashes["saved-production-CameraViewModel.kt"] = sha256(ui.encode())
        else:
            ui = source("CameraViewModel.kt", package="ui")
        ui_state = source("CameraUiState.kt", package="ui")
        ui_owner = between(ui, "    private class BridgeBulbUiOwner(", "    private var disconnectJob:")
        ui_methods = between(ui, "    fun toggleBulbExposure() {", "    fun retryShutterRelease() {")
        disconnect = between(ui, "    fun disconnect() {", "    /** Explicit receipt recovery")
        is_busy = between(
            ui_state,
            "    fun isBusy(operation: CameraOperation): Boolean =",
            "\n}\n\ninternal val FOREGROUND_IMPORT_ACTIVE_PHASES",
        )
        fixture = fixtures / "ExtractedUiTest.kt"
        target = output / fixture.name
        fixture_text = fixture.read_text()
        for marker in (
            "    // HOST_PRODUCTION_UI_OWNER",
            "    // HOST_PRODUCTION_UI_METHODS",
            "    // HOST_PRODUCTION_IS_BUSY",
            "    // HOST_PRODUCTION_DISCONNECT",
        ):
            assert fixture_text.count(marker) == 1
        target.write_text(
            fixture_text.replace("    // HOST_PRODUCTION_UI_OWNER", ui_owner)
            .replace("    // HOST_PRODUCTION_UI_METHODS", ui_methods)
            .replace("    // HOST_PRODUCTION_IS_BUSY", is_busy)
            .replace("    // HOST_PRODUCTION_DISCONNECT", disconnect)
        )
        fixture_hashes[fixture.name] = sha256(fixture.read_bytes())
        source_hashes["extracted-UI-owner-methods-isBusy-disconnect"] = sha256(
            (ui_owner + ui_methods + is_busy + disconnect).encode()
        )
        compile_files.append(target)
        repository = source("CameraRepository.kt")
        repository_disconnect = between(
            repository,
            "    private suspend fun disconnectLocked(): Boolean {",
            "    suspend fun refreshStatus(): CameraStatus",
        )
        connection_retirement = source("ConnectionRetirement.kt")
        warnings = (
            "internal enum class PreviousConnectionSafetyWarning"
            + connection_retirement.split("internal enum class PreviousConnectionSafetyWarning", 1)[1]
        )
        fixture = fixtures / "ExtractedRepositoryTest.kt"
        target = output / fixture.name
        target.write_text(
            fixture.read_text()
            .replace("    // HOST_PRODUCTION_REPOSITORY_DISCONNECT", repository_disconnect)
            .replace("// HOST_PRODUCTION_PREVIOUS_WARNINGS", warnings)
        )
        fixture_hashes[fixture.name] = sha256(fixture.read_bytes())
        source_hashes["extracted-repository-disconnect-previous-warning-classifier"] = sha256(
            (repository_disconnect + warnings).encode()
        )
        compile_files.append(target)
        test_classes = [
            "dev.openeos.control.data.BridgeBulbRequestSessionTest",
            "dev.openeos.control.data.BridgeBulbCallbackTest",
            "dev.openeos.control.data.BridgeBulbObservationRaceTest",
            "dev.openeos.control.data.ExtractedClientTest",
            "dev.openeos.control.ui.ExtractedUiTest",
            "dev.openeos.control.data.ExtractedRepositoryTest",
        ]
        boundary = (
            "Entire unchanged production owned state/deadline/access and legacy shutter cla"
            "sses; byte-exact DesktopBridgeClient owned preparation, Start/Stop, callback/b"
            "ody loop, parser, status, admission, session identity and close, plus retireme"
            "nt ACK helper; byte-exact CameraViewModel toggle/owned UI/retirement/disconnec"
            "t methods and CameraUiState.isBusy. Synthetic model, JSON registry, Call/Respo"
            "nse/body, status-read, repository, dispatcher/lifecycle and unrelated interloc"
            "k adapters. No full Android/OkHttp/Compose/device execution."
        )
    jvm = ["java", "-XX:ActiveProcessorCount=1", "-Xmx256m"]
    if args.test_class:
        test_classes = args.test_class
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
        *map(str, compile_files),
    ]
    compile_result = subprocess.run(command, capture_output=True, text=True, timeout=180)
    (output / "compile.log").write_text(compile_result.stdout + compile_result.stderr)
    receipt = {
        "at": datetime.datetime.now(datetime.UTC).isoformat(),
        "phase": args.phase,
        "ref": ref,
        "boundary": boundary,
        "source_sha256": source_hashes,
        "extracted_methods_sha256": sha256(methods.encode()),
        "fixture_sha256": fixture_hashes,
        "verified_tools": verified,
        "compile_exit": compile_result.returncode,
        "jvm_heap_limit_mb": 256,
        "active_processor_count": 1,
    }
    receipt["test_classes"] = test_classes
    success = False
    if compile_result.returncode:
        print(compile_result.stdout + compile_result.stderr)
    else:
        execution = subprocess.run(
            jvm
            + ["-cp", str(output / "tests.jar") + os.pathsep + runtime, "org.junit.runner.JUnitCore", *test_classes],
            capture_output=True,
            text=True,
            timeout=30,
        )
        log = execution.stdout + execution.stderr
        (output / "junit.log").write_text(log)
        print(log)
        receipt["junit_exit"] = execution.returncode
        if args.phase == "baseline":
            expected = [
                "pendingStartMustNotPreventMatchingStopDispatch",
                "statusGetMustNotProveReleaseAfterAmbiguousStop",
                "retiredSessionMustNotBeRearmedByLateStart",
                "startMustUseOwnedRouteAndOneStringOwner",
            ]
            success = (
                execution.returncode == 1
                and "Tests run: 4,  Failures: 4" in log
                and all(name in log for name in expected)
            )
            receipt["expected_runtime_red_cases_verified"] = success
        else:
            success = execution.returncode == 0 and "OK (" in log
            receipt["runtime_tests_passed"] = success
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
