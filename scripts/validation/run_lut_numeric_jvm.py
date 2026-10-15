"""Offline numeric checks with existing pinned Kotlin tools; never installs/downloads.

The checked-in org.json resource loader is NOT run by this harness. Python reads
the fixed JSON and emits only a data provider, then compiles the actual production
LUTs and unchanged numeric JUnit test body. No expected output is computed here.
This is not an Android SDK/Gradle build, Bitmap test, or native GPU execution.
"""

import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jvm-tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    fixture_path = root / "contracts/live-view-lut/v1/numeric-vectors.json"
    data = fixture_path.read_bytes()
    if len(data) > 64 * 1024:
        raise ValueError("Numeric LUT contract exceeds 64 KiB")
    fixture = json.loads(data)
    receipt = json.loads((args.jvm_tools / "installation-receipt.json").read_text())
    for artifact in receipt["artifacts"]:
        path = args.jvm_tools / artifact["file"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != artifact["sha256"]:
            raise ValueError(f"Pinned tool checksum mismatch: {path.name}")
    args.output.mkdir(parents=True, exist_ok=True)

    def quoted(value):
        return json.dumps(value)

    def floats(values):
        return "floatArrayOf(" + ", ".join(f"{value}f" for value in values) + ")"

    def ints(values):
        return "null" if values is None else "intArrayOf(" + ", ".join(map(str, values)) + ")"

    luts = [f"NumericLut({quoted(v['id'])}, {v['size']}, {floats(v['domainMin'])}, "
            f"{floats(v['domainMax'])}, {floats(v['values'])})" for v in fixture["luts"]]
    vectors = [f"NumericLutVector({quoted(v['id'])}, {quoted(v['lutId'])}, {floats(v['inputRgb'])}, "
               f"{floats(v['expectedRgb'])}, {ints(v.get('inputRgb8'))}, {ints(v.get('expectedRgb8'))})"
               for v in fixture["vectors"]]
    quantization = [f"NumericQuantizationVector({quoted(v['id'])}, {floats(v['rgb'])}, {ints(v['expectedRgb8'])})"
                    for v in fixture["quantizationVectors"]]
    provider = args.output / "OfflineNumericFixtureProvider.kt"
    provider.write_text("\n".join([
        "// Offline DATA provider only. org.json/resource mapping is NOT exercised.",
        "package dev.openeos.control.ui",
        "internal fun loadLiveViewLutNumericFixture() = LiveViewLutNumericFixture(",
        f"    byteCount = {len(data)}, schemaVersion = {fixture['schemaVersion']},",
        f"    colorEncoding = {quoted(fixture['colorEncoding'])},",
        f"    interpolation = {quoted(fixture['interpolation'])}, tableOrder = {quoted(fixture['tableOrder'])},",
        (f"    cpuAbsoluteTolerance = {fixture['cpuAbsoluteTolerance']}f, "
         f"uint8Rounding = {quoted(fixture['uint8Rounding'])},"),
        "    luts = listOf(" + ",\n        ".join(luts) + "),",
        "    vectors = listOf(" + ",\n        ".join(vectors) + "),",
        "    quantizationVectors = listOf(" + ",\n        ".join(quantization) + "),",
        ")", "",
    ]))

    def classpath(names):
        return os.pathsep.join(str(args.jvm_tools / f"{name}.jar") for name in names)

    compiler = classpath([
        "kotlin-compiler-embeddable-2.2.21", "kotlin-stdlib-2.2.21", "kotlin-script-runtime-2.2.21",
        "kotlin-reflect-1.6.10", "kotlin-daemon-embeddable-2.2.21", "kotlinx-coroutines-core-jvm-1.8.0",
        "annotations-23.0.0",
    ])
    runtime = classpath([
        "kotlin-stdlib-2.2.21", "kotlinx-coroutines-core-jvm-1.9.0", "annotations-23.0.0",
        "junit-4.13.2", "hamcrest-core-1.3",
    ])
    sources = [
        "android/app/src/main/java/dev/openeos/control/ui/CubeLut.kt",
        "android/app/src/main/java/dev/openeos/control/data/NativeVideoLut.kt",
        "android/app/src/test/java/dev/openeos/control/ui/LiveViewLutNumericFixture.kt",
        "android/app/src/test/java/dev/openeos/control/ui/LiveViewLutNumericContractTest.kt",
        "android/app/src/test/java/dev/openeos/control/data/NativeVideoLutTest.kt",
    ]
    output_jar = args.output / "lut-numeric-tests.jar"
    command = ["java", "-Xmx768m", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
               "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", runtime, "-d", str(output_jar),
               *[str(root / source) for source in sources], str(provider)]
    compiled = subprocess.run(command, check=False, capture_output=True, text=True, timeout=120)
    (args.output / "compile.log").write_text(compiled.stdout + compiled.stderr)
    result = {
        "scope": "Production CubeLut + NativeVideoLut and unchanged numeric JUnit body with fixed JSON data provider",
        "not_run": [
            "org.json resource loader", "Gradle resource mapping", "full Android SDK build",
            "Bitmap adapter", "native GPU/device",
        ],
        "fixture_sha256": hashlib.sha256(data).hexdigest(),
        "sources": {source: hashlib.sha256((root / source).read_bytes()).hexdigest() for source in sources},
        "compile_exit": compiled.returncode,
    }
    if compiled.returncode == 0:
        run = subprocess.run([
            "java", "-Xmx256m", "-cp", str(output_jar) + os.pathsep + runtime, "org.junit.runner.JUnitCore",
            "dev.openeos.control.ui.LiveViewLutNumericContractTest", "dev.openeos.control.data.NativeVideoLutTest",
        ], check=False, capture_output=True, text=True, timeout=60)
        (args.output / "junit.log").write_text(run.stdout + run.stderr)
        result["junit_exit"] = run.returncode
        print(run.stdout + run.stderr)
    else:
        print(compiled.stdout + compiled.stderr)
    (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print("NOT_RUN: " + "; ".join(result["not_run"]))
    return compiled.returncode or result.get("junit_exit", 1)


if __name__ == "__main__":
    raise SystemExit(main())
