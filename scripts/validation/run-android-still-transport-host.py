#!/usr/bin/env python3
"""Bounded offline JVM execution of exact still requestOk and mandatory release ownership."""
from __future__ import annotations
import argparse, hashlib, json, os, subprocess, sys
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
    ref = args.ref or ("34669de2" if args.phase == "baseline" else "WORKTREE")
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
    for name in ("ShutterReleaseSession.kt", "ShootingSettingJson.kt", "StrictMediaJson.kt"):
        target = output / name; target.write_text(source(name)); files.append(target)
    client = source("CcapiClient.kt")
    if args.phase == "current":
        capture = client.split("    suspend fun captureStill(", 1)[1].split("    suspend fun startBulbExposure()", 1)[0]
        assert capture.count("stillCapture = true") == 3, "Direct and manual press/release must opt in"
        command = client.split("    private suspend fun commandOk(", 1)[1].split("    private suspend fun withRecoverableFocusRelease(", 1)[0]
        assert "stillCapture = stillCapture" in command
        strict = client.split("private val captureConfigurationHttpClient", 1)[1].split("private val mutationHttpClient", 1)[0]
        assert "callTimeout(15, TimeUnit.SECONDS)" in strict
        assert ".retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)" in strict
    begin = "    private suspend fun requestOk("
    end = "    private suspend fun requestLiveViewFrame("
    assert client.count(begin) == client.count(end) == 1
    method = begin + client.split(begin, 1)[1].split(end, 1)[0]
    extracts["requestOk"] = digest(method.encode())
    fixture_hashes = {}
    for fixture in sorted((repo / "scripts/validation/fixtures/android-still-transport-host").glob("*.kt")):
        data = fixture.read_text(); fixture_hashes[fixture.name] = digest(data.encode())
        data = data.replace("    // HOST_REQUEST_OK", method)
        data = data.replace("/* HOST_COMMAND_CALL */", "requestOk(Request()" + (", stillCapture = true" if "stillCapture: Boolean" in method else "") + ")")
        target = output / fixture.name; target.write_text(data); files.append(target)
    jvm = ["java", "-XX:ActiveProcessorCount=1", "-Xmx256m"]
    command = jvm + ["-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", runtime, "-d", str(output / "tests.jar"), *map(str, files)]
    compiled = subprocess.run(command, capture_output=True, text=True, timeout=180)
    (output / "compile.log").write_text(compiled.stdout + compiled.stderr)
    receipt = {"phase": args.phase, "ref": ref, "source_sha256": hashes, "extracted_methods_sha256": extracts,
        "fixture_sha256": fixture_hashes, "verified_tools": verified, "compile_exit": compiled.returncode,
        "boundary": "Exact production requestOk, ShutterReleaseSession and bounded JSON readers; synthetic synchronous Call/Response/InputStream. No real OkHttp, socket, Android or device execution.",
        "jvm_heap_limit_mb": 256, "active_processor_count": 1}
    success = False
    if compiled.returncode: print(compiled.stdout + compiled.stderr)
    else:
        executed = subprocess.run(jvm + ["-cp", str(output / "tests.jar") + os.pathsep + runtime, "org.junit.runner.JUnitCore", "dev.openeos.control.data.StillTransportTest"], capture_output=True, text=True, timeout=30)
        log = executed.stdout + executed.stderr; print(log)
        (output / "junit.log").write_text(log); receipt["junit_exit"] = executed.returncode
        success = (executed.returncode == 1 and "Tests run: 3,  Failures: 3" in log and all(message in log for message in ("Still cancellation must join the stopped producer", "Cancelled press must reach its matching release", "Still response must enforce its byte limit"))) if args.phase == "baseline" else (executed.returncode == 0 and "OK (3 tests)" in log)
        receipt["expected_result_verified"] = success
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if success else 1
if __name__ == "__main__": sys.exit(main())
