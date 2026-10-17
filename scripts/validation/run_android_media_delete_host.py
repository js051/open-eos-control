#!/usr/bin/env python3
"""Offline bounded JVM proof of exact production media DELETE receipt and state mutation."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jars", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--phase", choices=("baseline", "current"), required=True)
    parser.add_argument("--ref")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    ref = args.ref or ("f7b5f440" if args.phase == "baseline" else "WORKTREE")
    if ref != "WORKTREE":
        ref = subprocess.check_output(
            ["git", "rev-parse", ref], cwd=repo, text=True
        ).strip()
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=True)
    jars = args.jars.resolve()
    verified = {}
    for artifact in json.loads((jars / "installation-receipt.json").read_text())[
        "artifacts"
    ]:
        path = jars / artifact["file"]
        digest = sha256(path.read_bytes())
        if digest != artifact["sha256"]:
            raise ValueError("Existing tool checksum mismatch: " + path.name)
        verified[path.name] = digest

    def classpath(names):
        paths = [jars / (name + ".jar") for name in names]
        for path in paths:
            if path.name not in verified:
                raise ValueError("Required existing tool missing: " + path.name)
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
    sources, extracts, fixtures, files = [], [], [], []

    def source(path):
        data = (
            (repo / path).read_bytes()
            if ref == "WORKTREE"
            else subprocess.check_output(["git", "show", ref + ":" + path], cwd=repo)
        )
        sources.append({"path": path, "sha256": sha256(data)})
        return data.decode()

    prefix = "android/app/src/main/java/dev/openeos/control/"
    for name in ("MediaDeleteHttp.kt", "MediaDeleteEvidence.kt"):
        target = output / name
        target.write_text(source(prefix + "data/" + name))
        files.append(target)
    methods = {}
    for key, path, begin, end in [
        (
            "DELETE",
            prefix + "data/CcapiClient.kt",
            "    suspend fun deleteMedia(",
            "    suspend fun startLiveView(",
        ),
        (
            "UI_DELETE",
            prefix + "ui/CameraViewModel.kt",
            "    private fun CameraUiState.withDeletedMedia(",
            "    private fun CameraUiState.withVerifiedMediaMetadata(",
        ),
    ]:
        text = source(path)
        assert text.count(begin) == text.count(end) == 1
        method = begin + text.split(begin, 1)[1].split(end, 1)[0]
        original = subprocess.check_output(
            ["git", "show", "f7b5f440:" + path], cwd=repo
        ).decode()
        assert method == begin + original.split(begin, 1)[1].split(end, 1)[0]
        methods[key] = method
        extracts.append(
            {"path": path, "method": key, "sha256": sha256(method.encode())}
        )
    fixture_root = repo / "scripts/validation/fixtures/android-media-delete-host"
    for fixture in sorted(fixture_root.glob("*.kt")):
        text = fixture.read_text()
        fixtures.append(
            {"path": str(fixture.relative_to(repo)), "sha256": sha256(text.encode())}
        )
        for key, method in methods.items():
            text = text.replace("    // HOST_" + key + "\n", method + "\n")
        target = output / fixture.name
        target.write_text(text)
        files.append(target)
    jvm = ["java", "-XX:ActiveProcessorCount=1", "-Xmx256m"]
    compiled = subprocess.run(
        jvm
        + [
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
        ],
        check=False,
        capture_output=True,
        text=True,
        timeout=180,
    )
    (output / "compile.log").write_text(compiled.stdout + compiled.stderr)
    receipt = {
        "phase": args.phase,
        "ref": ref,
        "sources": sources,
        "extracts": extracts,
        "fixtures": fixtures,
        "verified_tools": [
            {"path": name, "sha256": digest} for name, digest in verified.items()
        ],
        "compile_exit": compiled.returncode,
        "jvm_heap_limit_mb": 256,
        "active_processor_count": 1,
        "boundary": "Exact full MediaDeleteHttp and MediaDeleteEvidence; exact CcapiClient.deleteMedia and "
        "CameraViewModel.withDeletedMedia. HTTP/Okio, models and confirmation callback are synthetic. "
        "No full ViewModel, repository, OkHttp, socket, Android lifecycle or physical camera execution. "
        "Formal MockWebServer tests authored separately, NOT_RUN.",
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
                "dev.openeos.control.data.MediaDeleteHostTest",
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
        success = (
            (
                executed.returncode == 1
                and "Tests run: 10,  Failures: 3" in log
                and all(
                    name in log
                    for name in (
                        "range204RetainsItem",
                        "emptyRange204RetainsItem",
                        "mixedCaseRange204RetainsItem",
                    )
                )
            )
            if args.phase == "baseline"
            else (executed.returncode == 0 and "OK (10 tests)" in log)
        )
    receipt["expected_result_verified"] = success
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if success else 1


if __name__ == "__main__":
    raise SystemExit(main())
