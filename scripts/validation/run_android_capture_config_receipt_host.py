#!/usr/bin/env python3
"""Bounded offline JVM execution of exact capture-configuration entries, JSON transport and evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
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
    ref = args.ref or ("faefa520" if args.phase == "baseline" else "WORKTREE")
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
    for name in ("CaptureConfigurationEvidence.kt", "CaptureConfigurationJson.kt", "ShootingSettingJson.kt", "StrictMediaJson.kt"):
        target = output / name; target.write_text(source(name)); files.append(target)
    client = source("CcapiClient.kt")
    markers = {
        "CREATE": ("    suspend fun createDirectory(", "    suspend fun setFileNaming("),
        "NAMING": ("    suspend fun setFileNaming(", "    /** Explicit recovery is a fresh bounded read"),
        "POST": ("    private suspend fun postJson(", "    private suspend fun patchJson("),
        "PUT": ("    private suspend fun putJson(", "    private suspend fun deleteJson("),
        "REQUEST_JSON": ("    private suspend fun requestJson(", "    private suspend fun requestEventJson("),
        "CONSTANTS": ('private const val DIRECTORY_SELECTION_SETTING_KEY', 'private val STILL_FILENAME_MODES'),
        "VALUE": ('private fun CameraFileNaming.value(', 'private fun JSONObject.toValidatedFileRange('),
    }
    methods = {}
    for name, (begin, end) in markers.items():
        assert client.count(begin) == client.count(end) == 1
        methods[name] = begin + client.split(begin, 1)[1].split(end, 1)[0]
        extracts[name] = digest(methods[name].encode())
    models = source("CameraModels.kt")
    begin, end = "enum class CameraFileNamingField(", "data class LiveViewFrame("
    assert models.count(begin) == models.count(end) == 1
    model = begin + models.split(begin, 1)[1].split(end, 1)[0]
    extracts["MODELS"] = digest(model.encode())
    target = output / "CaptureModels.kt"
    target.write_text("package dev.openeos.control.data\n" + model); files.append(target)
    fixture_hashes = {}
    for fixture in sorted((repo / "scripts/validation/fixtures/android-capture-config-receipt-host").glob("*.kt")):
        data = fixture.read_text(); fixture_hashes[fixture.name] = digest(data.encode())
        for name, method in methods.items():
            marker = ("    " if name not in {"CONSTANTS", "VALUE"} else "") + "// HOST_" + name + "\n"
            assert data.count(marker) == (1 if fixture.name == "HostClient.kt" else 0)
            data = data.replace(marker, method + "\n")
        target = output / fixture.name; target.write_text(data); files.append(target)
    jvm = ["java", "-XX:ActiveProcessorCount=1", "-Xmx256m"]
    command = jvm + ["-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", runtime, "-d", str(output / "tests.jar"), *map(str, files)]
    compiled = subprocess.run(command, check=False, capture_output=True, text=True, timeout=180)
    (output / "compile.log").write_text(compiled.stdout + compiled.stderr)
    receipt = {"phase": args.phase, "ref": ref, "sources": [{"filename": k, "sha256": v} for k, v in hashes.items()], "extracts": [{"name": k, "sha256": v} for k, v in extracts.items()],
        "fixtures": [{"filename": k, "sha256": v} for k, v in fixture_hashes.items()], "tools": [{"filename": k, "sha256": v} for k, v in verified.items()], "compile_exit": compiled.returncode,
        "boundary": "Byte-exact production createDirectory/setFileNaming/postJson/putJson/requestJson, directory/naming definitions, value accessor; full production CaptureConfigurationEvidence/Json, ShootingSettingJson, StrictMediaJson and naming model. HTTP Call/Response/InputStream, operation discovery, readback loading and typed JSON decoding are synthetic. No real OkHttp, socket, Android, UI or device execution.",
        "jvm_heap_limit_mb": 256, "active_processor_count": 1}
    success = False
    if compiled.returncode: print(compiled.stdout + compiled.stderr)
    else:
        executed = subprocess.run(jvm + ["-cp", str(output / "tests.jar") + os.pathsep + runtime, "org.junit.runner.JUnitCore", "dev.openeos.control.data.CaptureConfigReceiptTest"], check=False, capture_output=True, text=True, timeout=30)
        log = executed.stdout + executed.stderr; print(log)
        (output / "junit.log").write_text(log); receipt["junit_exit"] = executed.returncode
        expected_failures = ("directory206NeverAcknowledges", "directoryRangeNeverAcknowledges", "directoryEmptyRangeNeverAcknowledges",
            "naming206NeverAcknowledges", "namingRangeNeverAcknowledges", "namingEmptyRangeNeverAcknowledges")
        success = (executed.returncode == 1 and "Tests run: 12,  Failures: 6" in log and all(
            failure in log for failure in expected_failures)) if args.phase == "baseline" else (executed.returncode == 0 and "OK (12 tests)" in log)
        receipt["expected_result_verified"] = success
    unchanged = ref != "WORKTREE" or all(digest((repo / name).read_bytes()) == value for name, value in hashes.items())
    receipt["source_snapshot_unchanged"] = unchanged
    receipt["runner"] = {"filename": str(Path(__file__).relative_to(repo)), "sha256": digest(Path(__file__).read_bytes())}
    success = success and unchanged
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if success else 1
if __name__ == "__main__": sys.exit(main())
