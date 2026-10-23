#!/usr/bin/env python3
"""Bounded offline JVM execution of exact Live View entries, control transport and cleanup ownership."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

from android_host_runtime import AndroidHostRuntime, add_runtime_arguments


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jars", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--phase", choices=("baseline", "current"), default="baseline")
    parser.add_argument("--ref")
    add_runtime_arguments(parser)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    ref = args.ref or ("4163aad8" if args.phase == "baseline" else "WORKTREE")
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
        "START": ("    suspend fun startLiveView(", "    suspend fun stopLiveView("),
        "STOP": ("    suspend fun stopLiveView(", "    private suspend fun stopLiveViewOwned("),
        "STOP_OWNED": (
            "    private suspend fun stopLiveViewOwned(",
            "    private suspend fun withLiveViewStartCleanup(",
        ),
        "CLEANUP": ("    private suspend fun withLiveViewStartCleanup(", "    suspend fun tapFocus("),
        "JPEG": ("    private suspend fun startJpegLiveView(", "    private suspend fun validateFirstLiveViewJpeg("),
        "CCAPI_START": (
            "    private suspend fun startCcapiLiveView(",
            "    private suspend fun startMultipartLiveView(",
        ),
        "CCAPI_STOP": ("    private suspend fun stopCcapiLiveView(", "    private suspend fun startRtpLiveView("),
        "RTP": ("    private suspend fun startRtpLiveView(", "    private fun supportsMediaDelete("),
        "POST": ("    private suspend fun postOk(", "    private suspend fun putOk("),
        "DELETE": ("    private suspend fun deleteOk(", "    private fun newCameraCall("),
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
        assert ".retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)" in strict
        multipart = client.split("    private suspend fun startMultipartLiveView(", 1)[1].split(
            "    private suspend fun stopCcapiLiveView(", 1
        )[0]
        assert multipart.count("deleteOk(operation.closeStream.path, liveViewControl = true)") == 2
        assert "startCcapiLiveView(request, operation.startLiveView.path)" in multipart
        assert "stopCcapiLiveView(operation.stopLiveView, operation.stopMethod)" in multipart
        factory = client.split("    private fun newCameraCall(", 1)[1].split("    private suspend fun requestJson(", 1)[
            0
        ]
        assert "return (if (captureConfiguration) captureConfigurationHttpClient else mutationHttpClient)" in factory
        assert "override fun isOneShot() = true" in factory
    fixture_hashes = {}
    for fixture in sorted((repo / "scripts/validation/fixtures/android-liveview-transport-host").glob("*.kt")):
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
    compiled = subprocess.run(command, capture_output=True, text=True, timeout=180)
    (output / "compile.log").write_text(compiled.stdout + compiled.stderr)
    receipt = {
        "phase": args.phase,
        "ref": ref,
        "source_sha256": hashes,
        "extracted_methods_sha256": extracts,
        "source_guards": [
            "strict client caps callTimeout at 15 seconds and disables retries/redirects",
            (
                "multipart closeStream DELETE opts in in both maintenance and ordinary branches"
                "; start/stop remain wired to shared controls"
            ),
            "production call factory routes strict mutation to the capped client and wraps its body as one-shot",
        ]
        if args.phase == "current"
        else [],
        "fixture_sha256": fixture_hashes,
        "verified_tools": host.verified,
        "runtime_contract": host.receipt(),
        "compile_exit": compiled.returncode,
        ("boundary"): (
            "Exact production startLiveView, JPEG/RTP entries, original Stop ownership, pos"
            "tOk/deleteOk/requestOk and bounded readers; synthetic Call/Response/InputStrea"
            "m and frame/RTP collaborators. Multipart wiring statically checked. No real Ok"
            "Http, socket, Android or device execution."
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
                "dev.openeos.control.data.LiveViewTransportTest",
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
            "Live View Stop response must be bounded",
            "Stop transport must remain bounded even in NonCancellable",
            "Live View cancellation must retire Start and reach Stop",
            "Cancelled Start must dispatch its original Stop",
            "POST-off Start and Stop must be bounded",
            "Live View Start response must be bounded",
            "RTP Stop response must be bounded",
            "Every size fallback and Stop must be bounded",
            "RTP cancellation must reach the matching Stop",
            "AUTO cannot accept oversized Start or Stop",
            "Unknown-length Start must enforce the streaming byte limit",
            "Truncated Stop cannot be acknowledged",
        )
        success = (
            (
                executed.returncode == 1
                and "Tests run: 12,  Failures: 12" in log
                and all(failure in log for failure in expected_failures)
            )
            if args.phase == "baseline"
            else (executed.returncode == 0 and "OK (12 tests)" in log)
        )
        receipt["expected_result_verified"] = success
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
