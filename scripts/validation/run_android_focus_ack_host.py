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
    ref = args.ref or ("27e6e3cb" if args.phase == "baseline" else "WORKTREE")
    if ref != "WORKTREE":
        ref = subprocess.check_output(
            ["git", "rev-parse", ref], cwd=repo, text=True
        ).strip()
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
        baseline = subprocess.check_output(
            [
                "git",
                "show",
                "27e6e3cb:android/app/src/main/java/dev/openeos/control/data/CcapiClient.kt",
            ],
            cwd=repo,
        ).decode()
        for name in ("DRIVE", "COORDINATES", "COMMAND"):
            begin, end = markers[name]
            assert (
                methods[name] == begin + baseline.split(begin, 1)[1].split(end, 1)[0]
            ), name + " must remain byte-for-byte unchanged"
        # This gate changes receipt classification only, not any client, reader or owner.
        begin = "            // Focus receipt integrity is independent of bounded-body/strict-client flags."
        end = "                // A partial representation cannot acknowledge the original physical control."
        request_ok = methods["REQUEST_OK"]
        restored = (
            request_ok[: request_ok.index(begin)]
            + "            if (stillCapture || liveViewControl || releaseControl) {\n"
            + request_ok[request_ok.index(end) :]
        )
        start, stop = markers["REQUEST_OK"]
        original = start + baseline.split(start, 1)[1].split(stop, 1)[0]
        assert restored == original, (
            "Only the isolated receipt gate may change requestOk"
        )
    fixture_hashes = {}
    for fixture in sorted(
        (repo / "scripts/validation/fixtures/android-focus-ack-host").glob("*.kt")
    ):
        data = fixture.read_text()
        fixture_hashes[fixture.name] = digest(data.encode())
        for name, method in methods.items():
            data = data.replace("    // HOST_" + name + "\n", method + "\n")
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
    compiled = subprocess.run(
        command, check=False, capture_output=True, text=True, timeout=180
    )
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
        "verified_tools": verified,
        "compile_exit": compiled.returncode,
        "boundary": "Exact production driveFocus, tapFocus, clickWhiteBalance, commandOk and requestOk. Call/Response/InputStream, discovery, geometry and status are synthetic. This is not full Android/OkHttp, socket, browser or device execution. Native MockWebServer tests are authored separately and NOT_RUN.",
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
