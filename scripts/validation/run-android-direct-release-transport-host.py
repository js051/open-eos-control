#!/usr/bin/env python3
"""Bounded offline JVM execution of exact Direct CCAPI AF/Bulb entries, transport and original release ownership."""

from __future__ import annotations

import argparse
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
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    ref = args.ref or ("25a67ff9" if args.phase == "baseline" else "WORKTREE")
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

    def source(name):
        path = "android/app/src/main/java/dev/openeos/control/data/" + name
        data = (
            (repo / path).read_bytes()
            if ref == "WORKTREE"
            else subprocess.check_output(["git", "show", ref + ":" + path], cwd=repo)
        )
        hashes[path] = digest(data)
        return data.decode()

    for name in ("HeldAutofocusSession.kt", "ShutterReleaseSession.kt", "ShootingSettingJson.kt", "StrictMediaJson.kt"):
        target = output / name
        target.write_text(source(name))
        files.append(target)
    client = source("CcapiClient.kt")
    markers = {
        "BULB": ("    suspend fun startBulbExposure()", "    suspend fun holdAutofocus("),
        "AF": ("    suspend fun holdAutofocus(", "    suspend fun driveFocus("),
        "COMMAND": ("    private suspend fun commandOk(", "    private fun apiPath("),
        "REQUEST_OK": ("    private suspend fun requestOk(", "    private suspend fun requestLiveViewFrame("),
    }
    methods = {}
    for name, (begin, end) in markers.items():
        assert client.count(begin) == client.count(end) == 1
        methods[name] = begin + client.split(begin, 1)[1].split(end, 1)[0]
        extracts[name] = digest(methods[name].encode())
    if args.phase == "current":
        strict = client.split("private val captureConfigurationHttpClient", 1)[1].split(
            "private val mutationHttpClient", 1
        )[0]
        assert "callTimeout(15, TimeUnit.SECONDS)" in strict
        assert "httpClient.callTimeoutMillis == 0" in strict and "httpClient.callTimeoutMillis > 15_000" in strict
        assert ".retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)" in strict
        factory = client.split("    private fun newCameraCall(", 1)[1].split("    private suspend fun requestJson(", 1)[
            0
        ]
        assert "return (if (captureConfiguration) captureConfigurationHttpClient else mutationHttpClient)" in factory
        assert "override fun isOneShot() = true" in factory
        assert (
            ".authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE).cache(null)"
            in strict
        )
        assert client.count("releaseControl = true") == 10
        assert "stillCapture = true" not in methods["AF"] + methods["BULB"]
        assert "releaseControl = releaseControl" in methods["COMMAND"]
        body = methods["REQUEST_OK"].split("val body = response.body", 1)[1]
        assert body.index("readShootingSettingResponseText") < body.index("liveViewControl || releaseControl")
        for name in ("HeldAutofocusSession.kt", "ShutterReleaseSession.kt"):
            original = subprocess.check_output(
                ["git", "show", "25a67ff9:android/app/src/main/java/dev/openeos/control/data/" + name], cwd=repo
            )
            assert source(name).encode() == original, "Release owner must remain byte-for-byte unchanged"
    fixture_hashes = {}
    for fixture in sorted((repo / "scripts/validation/fixtures/android-direct-release-transport-host").glob("*.kt")):
        data = fixture.read_text()
        fixture_hashes[fixture.name] = digest(data.encode())
        for name, method in methods.items():
            data = data.replace("    // HOST_" + name + "\n", method + "\n")
        data = data.replace(
            "/* HOST_COMBINED_CONTROL */",
            "requestOk(Request(), stillCapture = true"
            + (", releaseControl = true" if "releaseControl: Boolean" in methods["REQUEST_OK"] else "")
            + ")",
        )
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
        "phase": args.phase,
        "ref": ref,
        "source_sha256": hashes,
        "extracted_methods_sha256": extracts,
        "source_guards": [
            "strict client caps callTimeout at 15 seconds and disables retries/redirects",
            "production call factory routes strict mutation to the capped client and wraps its body as one-shot",
            (
                "strict JSON receipts precede raw-text control receipts if flags combine; actua"
                "l AF/Bulb callsites do not set stillCapture"
            ),
            "both release owners are byte-for-byte identical to baseline",
        ]
        if args.phase == "current"
        else [],
        "fixture_sha256": fixture_hashes,
        "verified_tools": verified,
        "compile_exit": compiled.returncode,
        ("boundary"): (
            "Exact production held/pulse/manual AF and Bulb entries, commandOk/requestOk, c"
            "omplete HeldAutofocusSession/ShutterReleaseSession and bounded readers; synthe"
            "tic Call/Response/InputStream, operation discovery, status and simulator postJ"
            "son. Strict-client factory source guards are separate. No real OkHttp, socket,"
            " Android or device execution."
        ),
        "jvm_heap_limit_mb": 256,
        "active_processor_count": 1,
    }
    success = False
    if compiled.returncode:
        print(compiled.stdout + compiled.stderr)
    else:
        executed = subprocess.run(
            jvm
            + [
                "-cp",
                str(output / "tests.jar") + os.pathsep + runtime,
                "org.junit.runner.JUnitCore",
                "dev.openeos.control.data.DirectReleaseTransportTest",
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        log = executed.stdout + executed.stderr
        print(log)
        (output / "junit.log").write_text(log)
        receipt["junit_exit"] = executed.returncode
        expected_failures = (
            "Held AF cancellation must reach the original Stop",
            "Held AF Stop must reject oversized response",
            "Pulse AF Start and Stop must be bounded",
            "Manual PUT autofocus fallback must be bounded",
            "Explicit half-press and release must be bounded",
            "Bulb Start and Stop must be bounded",
            "Cancelled Bulb press must reach original release",
            "Unknown Bulb release must retain its obligation",
            "Unknown-length AF Stop must enforce the byte limit",
            "Truncated Bulb release cannot be acknowledged",
            "NonCancellable Bulb release must select bounded producer",
            "AF Start must enforce the byte limit before the held callback",
        )
        success = (
            (
                executed.returncode == 1
                and "Tests run: 14,  Failures: 12" in log
                and all(failure in log for failure in expected_failures)
            )
            if args.phase == "baseline"
            else (executed.returncode == 0 and "OK (14 tests)" in log)
        )
        receipt["expected_result_verified"] = success
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
