#!/usr/bin/env python3
"""Bounded, offline JVM execution of exact production maintenance cleanup methods."""
from __future__ import annotations
import argparse, datetime, hashlib, json, os, subprocess, sys
from pathlib import Path

def digest(data): return hashlib.sha256(data).hexdigest()
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jars", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--phase", choices=("baseline", "current"), default="baseline")
    parser.add_argument("--ref")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    ref = args.ref or ("f9eb13d2" if args.phase == "baseline" else "WORKTREE")
    if ref != "WORKTREE": ref = subprocess.check_output(["git", "rev-parse", ref], cwd=repo, text=True).strip()
    output = args.out.resolve(); output.mkdir(parents=True, exist_ok=True)
    jars = args.jars.resolve()
    manifest = json.loads((jars / "installation-receipt.json").read_text())
    verified = {}
    for artifact in manifest["artifacts"]:
        path = jars / artifact["file"]
        actual = digest(path.read_bytes())
        if actual != artifact["sha256"]: raise ValueError(f"Existing tool checksum mismatch: {path.name}")
        verified[path.name] = actual
    def classpath(names):
        paths = [jars / (name + ".jar") for name in names]
        for path in paths:
            if path.name not in verified: raise ValueError(f"Required tool missing: {path.name}")
        return os.pathsep.join(map(str, paths))
    compiler = classpath(["kotlin-compiler-embeddable-2.2.21", "kotlin-stdlib-2.2.21", "kotlin-script-runtime-2.2.21", "kotlin-reflect-1.6.10", "kotlin-daemon-embeddable-2.2.21", "kotlinx-coroutines-core-jvm-1.8.0", "annotations-23.0.0"])
    runtime = classpath(["kotlin-stdlib-2.2.21", "kotlinx-coroutines-core-jvm-1.9.0", "annotations-23.0.0", "junit-4.13.2", "hamcrest-core-1.3"])
    hashes = {}; extracts = {}; files = []
    def source(name):
        path = "android/app/src/main/java/dev/openeos/control/data/" + name
        data = (repo / path).read_bytes() if ref == "WORKTREE" else subprocess.check_output(["git", "show", ref + ":" + path], cwd=repo)
        hashes[path] = digest(data); return data.decode()
    def between(text, begin, end):
        if text.count(begin) != 1 or text.count(end) != 1: raise ValueError("Ambiguous production boundary: " + begin)
        value = begin + text.split(begin, 1)[1].split(end, 1)[0]
        extracts[begin.strip()] = digest(value.encode()); return value
    for name in ("BridgeShutterReleaseSession.kt", "ShutterReleaseSession.kt", "BridgeBulbRequestSession.kt", "BridgeBulbDeadline.kt", "MaintenanceEvidence.kt", "ShootingSettingJson.kt", "StrictMediaJson.kt"):
        target = output / name; target.write_text(source(name)); files.append(target)
    client = source("DesktopBridgeClient.kt")
    methods = between(client, "    suspend fun retireMaintenanceLocally()", "    suspend fun closeConnectionAttempt()")
    methods += between(client, "    suspend fun stopEventPolling()", "    suspend fun capabilities()")
    methods += between(client, "    fun maintenanceAccess(", "    private fun JSONObject.toMaintenanceMap()")
    methods += between(client, "    private fun JSONObject.toMaintenanceMap()", "    suspend fun syncCameraClock()")
    methods += between(client, "    suspend fun startLiveView(request:", "    fun liveViewFrameUrl(")
    methods += between(client, "    private suspend fun postJson(", "    private suspend fun postEmptyJson(")
    methods += between(client, "    private suspend fun requestJson(", "    private suspend fun requestEventJson(")
    methods += between(client, "    private fun requireCurrentSession(", "    private object BridgeShutterStart")
    fixture_dir = repo / "scripts/validation/fixtures/android-maintenance-cleanup-host"
    fixture_hashes = {}
    for fixture in sorted(fixture_dir.glob("*.kt")):
        data = fixture.read_text(); fixture_hashes[fixture.name] = digest(data.encode())
        if "// HOST_PRODUCTION_METHODS" in data:
            assert data.count("// HOST_PRODUCTION_METHODS") == 1
            data = data.replace("    // HOST_PRODUCTION_METHODS", methods)
        target = output / fixture.name; target.write_text(data); files.append(target)
    jvm = ["java", "-XX:ActiveProcessorCount=1", "-Xmx256m"]
    command = jvm + ["-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", runtime, "-d", str(output / "tests.jar"), *map(str, files)]
    compiled = subprocess.run(command, capture_output=True, text=True, timeout=180)
    (output / "compile.log").write_text(compiled.stdout + compiled.stderr)
    receipt = {"at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "phase": args.phase, "ref": ref, "source_sha256": hashes, "extracted_methods_sha256": extracts, "fixture_sha256": fixture_hashes, "verified_tools": verified, "compile_exit": compiled.returncode, "jvm_heap_limit_mb":256, "active_processor_count":1,
        "boundary": "Entire production MaintenanceEvidence, BridgeShutterReleaseSession, ShutterReleaseSession, BridgeBulbRequestSession, BridgeBulbDeadline, ShootingSettingJson and StrictMediaJson; byte-exact DesktopBridgeClient maintenance close/local retirement, maintenanceAccess, maintenanceJson and cleanup helpers, live-view Start/Stop, postJson, requestJson, stopEventPolling, exact-owner helpers. Explicit synthetic HTTP Call/Response/InputStream, typed-registry JSON, model, focus and unrelated transport adapters. No Android/OkHttp/Compose/device execution."}
    success = False
    if compiled.returncode: print(compiled.stdout + compiled.stderr)
    else:
        test = "BaselineMaintenanceCleanupTest" if args.phase == "baseline" else "FixedMaintenanceCleanupTest"
        executed = subprocess.run(jvm + ["-cp", str(output / "tests.jar") + os.pathsep + runtime, "org.junit.runner.JUnitCore", "dev.openeos.control.data." + test], capture_output=True, text=True, timeout=45)
        log = executed.stdout + executed.stderr; print(log)
        (output / "junit.log").write_text(log); receipt["junit_exit"] = executed.returncode
        if args.phase == "baseline":
            names = ["cancelledStrictStopMustReturnWhileExecuteIgnoresCancel", "cancelledSessionDeleteMustReturnWhileExecuteIgnoresCancel", "cancelledBodyReadMustReturnWhileBodyIgnoresCancel", "realFifteenSecondStopDeadlineMustReturnWhileBodyIgnoresCancel"]
            success = (executed.returncode == 1 and "Tests run: 4,  Failures: 4" in log and all(name in log for name in names)
                and log.count("java.lang.AssertionError: Exact production close must return after caller cancellation while its transport/body still ignores cancel") == 3
                and log.count("java.lang.AssertionError: After the real 15-second Stop timeout, exact production close must return before the ignored-cancel body finishes") == 1)
            receipt["expected_runtime_red_cases_verified"] = success
        else:
            success = executed.returncode == 0 and "OK (" in log
            receipt["runtime_tests_passed"] = success
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if success else 1
if __name__ == "__main__": sys.exit(main())
