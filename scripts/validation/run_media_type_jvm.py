"""Offline actual Kotlin media classification, selection and JPEG policy/runner tests.

The fixed JSON fixture is converted to DATA only; formal org.json loading and
Android/Gradle integration are NOT_RUN. Existing pinned tools only, no download.
"""

import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jvm-tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    args.output.mkdir(parents=True, exist_ok=True)
    receipt = json.loads((args.jvm_tools / "installation-receipt.json").read_text())
    for artifact in receipt["artifacts"]:
        actual = hashlib.sha256((args.jvm_tools / artifact["file"]).read_bytes()).hexdigest()
        if actual != artifact["sha256"]:
            raise ValueError(f"Pinned tool checksum mismatch: {artifact['file']}")
    source = root / "contracts/media-type/v1/media-type-vectors.json"
    data = source.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if len(data) != 22867 or digest != "d24efab5bb73d5edc1d222395322f4295c4c31a3ae180d8b3d9293ce4e4b7d14":
        raise ValueError("Unexpected fixed media type contract")
    fixture = json.loads(data)

    def q(value):
        if value is None:
            return "null"
        return json.dumps(value, ensure_ascii=True).replace("$", "\\$")

    def listing(values):
        return "listOf(" + ",\n".join(values) + ")"

    def item(value):
        return (f"CameraMediaItem({q(value['id'])}, {q(value['name'])}, {q(value['kind'])}, "
                f"contentType = {q(value['contentType'])})")

    cases = []
    for value in fixture["cases"]:
        expected = value["expected"]
        cases.append(f"MediaTypeVector({q(value['id'])}, {item(value['item'])}, "
                     f"{q(expected['classification'])}, {q(expected['isPhoto'])}, "
                     f"{q(expected['isVideo'])}, {q(expected['automaticJpegEligible'])})")
    fresh = [f"MediaTypeFreshVector({q(v['id'])}, {item(v['original'])}, {item(v['metadata'])}, "
             f"{q(v['fresh'])}, {q(v['expectedAdmitted'])})" for v in fixture["freshMetadataCases"]]
    selections = [f"MediaTypeSelectionVector({q(v['id'])}, {q(v['intent'])}, "
                  f"{listing(map(q, v['listingCases']))}, {listing(map(q, v['knownCaseIds']))}, "
                  f"{q(v['expectedCaseId'])})" for v in fixture["selectionCases"]]
    def field_value(value):
        if isinstance(value, dict):
            return "mapOf<String, Any?>(" + ", ".join(f"{q(k)} to {field_value(v)}" for k, v in value.items()) + ")"
        if isinstance(value, list):
            return listing(map(field_value, value))
        return q(value)

    aliases = [f"MediaTypeMimeAliasVector({q(v['id'])}, {field_value(v['fields'])}, "
               f"{q(v['expectedValid'])}, {q(v['expectedValue'])})" for v in fixture["mimeAliasCases"]]
    provider = args.output / "OfflineMediaTypeFixtureProvider.kt"
    provider.write_text("\n".join([
        "// DATA only: formal org.json/resource loader NOT_RUN.",
        "package dev.openeos.control.ui", "import dev.openeos.control.data.CameraMediaItem",
        f"internal fun loadMediaTypeFixture() = MediaTypeFixture({fixture['schemaVersion']},",
        f"{listing(cases)}, {listing(fresh)}, {listing(selections)}, {listing(aliases)})", "",
    ]))
    model_path = root / "android/app/src/main/java/dev/openeos/control/data/CameraModels.kt"
    model = model_path.read_text()
    start = model.index("/** Authoritative source folder metadata;")
    end = model.index("data class CameraMediaUploadResult(", start)
    section = model[start:end]
    model_copy = args.output / "ExactCameraMediaModels.kt"
    model_copy.write_text("package dev.openeos.control.data\n\n" + section)

    def cp(names):
        return os.pathsep.join(str(args.jvm_tools / f"{name}.jar") for name in names)

    compiler = cp(["kotlin-compiler-embeddable-2.2.21", "kotlin-stdlib-2.2.21",
                   "kotlin-script-runtime-2.2.21", "kotlin-reflect-1.6.10", "kotlin-daemon-embeddable-2.2.21",
                   "kotlinx-coroutines-core-jvm-1.8.0", "annotations-23.0.0"])
    runtime = cp(["kotlin-stdlib-2.2.21", "kotlinx-coroutines-core-jvm-1.9.0",
                  "annotations-23.0.0", "junit-4.13.2", "hamcrest-core-1.3"])
    sources = ["android/app/src/main/java/dev/openeos/control/data/CameraMediaClassification.kt"]
    sources += [f"android/app/src/main/java/dev/openeos/control/ui/{name}.kt" for name in
                ["MediaCaptureDate", "MediaLibrary", "MediaReviewSelection",
                 "ForegroundJpegImport", "ForegroundJpegImportRunner"]]
    tests = ["MediaTypeContractTest", "ForegroundJpegImportPolicyTest"]
    sources += [f"android/app/src/test/java/dev/openeos/control/ui/{name}.kt" for name in ["MediaTypeFixture", *tests]]
    target = args.output / "media-type-tests.jar"
    compile_result = subprocess.run([
        "java", "-Xmx768m", "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", runtime, "-d", str(target),
        *[str(root / s) for s in sources], str(provider), str(model_copy),
    ], check=False, capture_output=True, text=True, timeout=120)
    (args.output / "compile.log").write_text(compile_result.stdout + compile_result.stderr)
    result = {
        "scope": ("Actual production classifier/library/review/JPEG policy and runner "
                  "with unchanged test bodies and fixed DATA provider"),
        "not_run": ["Formal org.json:20240303 resource loader", "Gradle resource mapping", "full Android SDK build",
                    "native adapter HTTP/USB tests", "Compose/ViewModel UI integration",
                    "MediaStore/Files integration", "physical camera"],
        "fixture_sha256": digest, "classification_vectors": len(cases), "fresh_metadata_vectors": len(fresh),
        "selection_vectors": len(selections), "mime_alias_vectors": len(aliases),
        "sources": {s: hashlib.sha256((root / s).read_bytes()).hexdigest() for s in sources},
        "model_source_sha256": hashlib.sha256(model_path.read_bytes()).hexdigest(),
        "model_section_sha256": hashlib.sha256(section.encode()).hexdigest(),
        "model_section_start_line": model[:start].count("\n") + 1,
        "model_section_end_line": model[:end].count("\n"), "compile_exit": compile_result.returncode,
    }
    if compile_result.returncode == 0:
        run = subprocess.run(["java", "-Xmx256m", "-cp", str(target) + os.pathsep + runtime,
                              "org.junit.runner.JUnitCore", *[f"dev.openeos.control.ui.{name}" for name in tests]],
                             check=False, capture_output=True, text=True, timeout=60)
        (args.output / "junit.log").write_text(run.stdout + run.stderr)
        result["junit_exit"] = run.returncode
        print(run.stdout + run.stderr)
    else:
        print(compile_result.stdout + compile_result.stderr)
    (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print("NOT_RUN: " + "; ".join(result["not_run"]))
    return compile_result.returncode or result.get("junit_exit", 1)


if __name__ == "__main__":
    raise SystemExit(main())
