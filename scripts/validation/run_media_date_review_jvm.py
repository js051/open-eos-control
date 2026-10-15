"""Offline media date/review checks with existing pinned Kotlin tools; no network/install.

Only the JSON resource loading layer is replaced by a fixed DATA provider. The
production parser, library comparator and review policy and all test bodies run
unchanged. A byte-exact, hashed DTO section is extracted from CameraModels.kt;
no Android APIs are stubbed. This is not a full Android SDK/Gradle/UI build.
"""

import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path

from capture_review_evidence_fixture import emit_capture_review_evidence_provider


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jvm-tools", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    args.output.mkdir(parents=True, exist_ok=True)
    receipt = json.loads((args.jvm_tools / "installation-receipt.json").read_text())
    for artifact in receipt["artifacts"]:
        if (
            hashlib.sha256((args.jvm_tools / artifact["file"]).read_bytes()).hexdigest()
            != artifact["sha256"]
        ):
            raise ValueError(f"Pinned tool checksum mismatch: {artifact['file']}")
    fixture_path = root / "contracts/media-date-review/v1/date-review-vectors.json"
    data = fixture_path.read_bytes()
    if len(data) > 65536:
        raise ValueError("Fixture exceeds 64 KiB")
    fixture = json.loads(data)

    def q(value):
        return (
            "null"
            if value is None
            else json.dumps(value, ensure_ascii=True).replace("$", "\\$")
        )

    def strings(values, constructor="listOf"):
        return constructor + "(" + ", ".join(q(value) for value in values) + ")"

    def item(value):
        return (f"CameraMediaItem({q(value['id'])}, {q(value['name'])}, {q(value['kind'])}, "
                f"captureTime = {q(value['captureTime'])})")

    def listing(values):
        return "listOf(\n        " + ",\n        ".join(values) + ")"

    vectors = []
    for value in fixture["vectors"]:
        display = (
            "mapOf("
            + ", ".join(
                f"{q(zone)} to MediaDateDisplayVector({q(expected['dateKey'])}, {q(expected['timeKey'])})"
                for zone, expected in value["display"].items()
            )
            + ")"
        )
        millis = (
            "null" if value["instantMillis"] is None else f"{value['instantMillis']}L"
        )
        vectors.append(
            f"MediaDateVector({q(value['id'])}, {q(value['raw'])}, {q(value['kind'])}, {millis}, {display})"
        )
    sorts = [
        f"MediaDateSortVector({q(v['zone'])}, {q(v['sort'])}, {strings(v['expectedIds'])})"
        for v in fixture["sortCases"]
    ]
    reviews = [
        f"MediaReviewVector({q(v['kind'])}, {strings(v['previousIds'], 'setOf')}, {q(v['expectedId'])})"
        for v in fixture["reviewCases"]
    ]
    provider = args.output / "OfflineMediaDateFixtureProvider.kt"
    provider.write_text(
        "\n".join(
            [
                "// Fixed DATA only; org.json/resource loader is NOT exercised by this harness.",
                "package dev.openeos.control.ui",
                "import dev.openeos.control.data.CameraMediaItem",
                "internal fun loadMediaDateReviewFixture() = MediaDateReviewFixture(",
                f"    schemaVersion = {fixture['schemaVersion']}, "
                f"instantResolution = {q(fixture['instantResolution'])},",
                f"    calendar = {q(fixture['calendar'])}, zones = {strings(fixture['displayZones'])},",
                f"    kinds = {strings(fixture['kinds'])}, "
                f"precisionOrder = {strings(fixture['dateSortPrecisionOrder'])},",
                f"    unknownAndTies = {q(fixture['unknownAndTies'])}, vectors = {listing(vectors)},",
                f"    sortItems = {listing(map(item, fixture['sortItems']))}, sortCases = {listing(sorts)},",
                f"    reviewItems = {listing(map(item, fixture['reviewItems']))}, reviewCases = {listing(reviews)},",
                ")",
                "",
            ]
        )
    )
    evidence_provider, evidence_metadata = emit_capture_review_evidence_provider(root, args.output)
    model_path = (
        root / "android/app/src/main/java/dev/openeos/control/data/CameraModels.kt"
    )
    model = model_path.read_text()
    start = model.index("/** Authoritative source folder metadata;")
    end = model.index("data class CameraMediaThumbnail(", start)
    model_section = model[start:end]
    model_copy = args.output / "ExactCameraMediaModels.kt"
    model_copy.write_text("package dev.openeos.control.data\n\n" + model_section)

    def cp(names):
        return os.pathsep.join(str(args.jvm_tools / f"{name}.jar") for name in names)

    compiler = cp(
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
    runtime = cp(
        [
            "kotlin-stdlib-2.2.21",
            "kotlinx-coroutines-core-jvm-1.9.0",
            "annotations-23.0.0",
            "junit-4.13.2",
            "hamcrest-core-1.3",
        ]
    )
    tests = [
        "MediaDateReviewContractTest",
        "MediaReviewSelectionTest",
        "CaptureReviewEvidenceTest",
        "CaptureReviewEvidenceContractTest",
        "MediaDateRangeTest",
        "MediaRatingFilterTest",
    ]
    sources = [
        f"android/app/src/main/java/dev/openeos/control/ui/{name}.kt"
        for name in ["MediaCaptureDate", "MediaLibrary", "MediaReviewSelection"]
    ]
    sources += [
        "android/app/src/main/java/dev/openeos/control/data/CameraMediaClassification.kt",
        "android/app/src/test/java/dev/openeos/control/ui/MediaDateReviewFixture.kt",
        "android/app/src/test/java/dev/openeos/control/ui/CaptureReviewEvidenceFixture.kt",
    ]
    sources += [
        f"android/app/src/test/java/dev/openeos/control/ui/{name}.kt" for name in tests
    ]
    target = args.output / "media-date-review-tests.jar"
    compile_result = subprocess.run(
        [
            "java",
            "-Xmx768m",
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
            str(target),
            *[str(root / s) for s in sources],
            str(provider),
            str(evidence_provider),
            str(model_copy),
        ],
        check=False,
        capture_output=True,
        text=True,
        timeout=120,
    )
    (args.output / "compile.log").write_text(
        compile_result.stdout + compile_result.stderr
    )
    result = {
        "scope": ("Unchanged production parser/library/review + exact hashed DTO section + "
                  "unchanged JUnit tests, fixed DATA provider"),
        "not_run": [
            "org.json:20240303 resource loader",
            "Gradle resource mapping",
            "full Android SDK build",
            "Compose timezone broadcast/lifecycle UI",
            "ViewModel HTTP integration (including actual malformed-list adapter decoding)",
            "Compose review-evidence UI",
            "foreground import integration",
            "physical camera",
        ],
        "review_evidence_fixture": evidence_metadata,
        "fixture_sha256": hashlib.sha256(data).hexdigest(),
        "fixture_vectors": len(fixture["vectors"]),
        "fixture_zones": len(fixture["displayZones"]),
        "sources": {
            s: hashlib.sha256((root / s).read_bytes()).hexdigest() for s in sources
        },
        "model_source_sha256": hashlib.sha256(model_path.read_bytes()).hexdigest(),
        "model_section_sha256": hashlib.sha256(model_section.encode()).hexdigest(),
        "model_section_start_line": model[:start].count("\n") + 1,
        "model_section_end_line": model[:end].count("\n"),
        "compile_exit": compile_result.returncode,
    }
    if compile_result.returncode == 0:
        run = subprocess.run(
            [
                "java",
                "-Xmx256m",
                "-cp",
                str(target) + os.pathsep + runtime,
                "org.junit.runner.JUnitCore",
                *[f"dev.openeos.control.ui.{name}" for name in tests],
            ],
            check=False,
            capture_output=True,
            text=True,
            timeout=60,
        )
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
