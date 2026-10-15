"""XcodeGen source-wiring guards; these do not execute generation or Swift."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PROJECT_ROOT = ROOT / "ios/OpenEOSControl"
PROJECT = PROJECT_ROOT / "project.yml"
SHARED_SWIFT = "../OpenEOSCore/Tests/OpenEOSCoreTests/URLSessionPendingBulbStopTests.swift"
CONTRACTS = {
    "../../contracts/media-upload/v1/upload-outcome-vectors.json",
    "../../contracts/media-delivery/v1/outcome-vectors.json",
    "../../contracts/live-view-lut/v1/numeric-vectors.json",
    "../../contracts/media-date-review/v1/date-review-vectors.json",
    "../../contracts/capture-review-evidence/v1/review-evidence-vectors.json",
    "../../contracts/media-type/v1/media-type-vectors.json",
    "../../contracts/jpeg-original/v1/jpeg-original-vectors.json",
    "../../contracts/media-mutation/v1/media-mutation-vectors.json",
    "../../contracts/media-mutation/v1/media-mutation-eligibility-vectors.json",
}


def source_entries(target):
    """Read this spec's explicit source blocks without adding a YAML dependency."""
    text = PROJECT.read_text()
    target_block = text.split(f"\n  {target}:\n", 1)[1]
    source_block = target_block.split("\n    sources:\n", 1)[1].split("\n    dependencies:", 1)[0]
    return re.findall(r"^      - path: ([^\n]+)\n((?:        [^\n]*\n)*)", source_block + "\n", re.MULTILINE)


class IOSProjectSourcePathTests(unittest.TestCase):
    def test_every_external_source_opts_out_of_intermediate_ancestor_groups(self):
        entries = source_entries("OpenEOSControlTests")
        external = [(path, settings) for path, settings in entries if path.startswith("../")]
        self.assertEqual(len(external), 10)
        self.assertEqual({path for path, _ in external}, CONTRACTS | {SHARED_SWIFT})
        for path, settings in external:
            with self.subTest(path=path):
                self.assertIn("        createIntermediateGroups: false\n", settings)
                self.assertNotIn("optional:", settings)
                self.assertNotIn("excludes:", settings)
                self.assertNotIn("buildPhase: none", settings)
                self.assertNotIn("group:", settings)

    def test_all_declared_sources_exist_without_duplicate_or_absolute_paths(self):
        count = 0
        for target in ("OpenEOSControl", "OpenEOSControlTests", "OpenEOSControlUITests"):
            entries = source_entries(target)
            self.assertEqual(len(entries), len({path for path, _ in entries}))
            for path, _ in entries:
                with self.subTest(target=target, path=path):
                    self.assertFalse(Path(path).is_absolute())
                    resolved = (PROJECT_ROOT / path).resolve()
                    self.assertTrue(resolved.is_relative_to(ROOT))
                    self.assertTrue(resolved.exists())
                    count += 1
        self.assertEqual(count, 15)

    def test_contract_resources_and_shared_real_swift_test_remain_required(self):
        entries = dict(source_entries("OpenEOSControlTests"))
        for path in CONTRACTS:
            self.assertIn("        buildPhase: resources\n", entries[path])
            self.assertTrue((PROJECT_ROOT / path).is_file())
        self.assertEqual(len({Path(path).name for path in CONTRACTS}), len(CONTRACTS))
        self.assertNotIn("buildPhase:", entries[SHARED_SWIFT])  # .swift keeps its inferred Compile Sources phase.
        source = (PROJECT_ROOT / SHARED_SWIFT).read_text()
        self.assertIn("final class URLSessionPendingBulbStopTests: XCTestCase", source)
        self.assertGreaterEqual(len(re.findall(r"^    func test", source, re.MULTILINE)), 6)
        package = (ROOT / "ios/OpenEOSCore/Package.swift").read_text()
        self.assertIn('.testTarget(name: "OpenEOSCoreTests", dependencies: ["OpenEOSCore"])', package)

    def test_internal_groups_and_app_ui_test_wiring_are_preserved(self):
        options = PROJECT.read_text().split("\noptions:\n", 1)[1].split("\npackages:", 1)[0]
        self.assertIn("  createIntermediateGroups: true\n", options + "\n")
        app = dict(source_entries("OpenEOSControl"))
        tests = dict(source_entries("OpenEOSControlTests"))
        ui = dict(source_entries("OpenEOSControlUITests"))
        self.assertEqual(set(app), {"App"})
        self.assertIn("        excludes:\n          - Fixtures\n", tests["Tests"])
        self.assertIn("        buildPhase: resources\n", tests["Tests/Fixtures"])
        self.assertEqual(set(ui), {"UITests", "Tests/Fixtures/valid-h264-baseline.mp4"})
        self.assertIn("        buildPhase: resources\n", ui["Tests/Fixtures/valid-h264-baseline.mp4"])
        for entries in (app, tests, ui):
            for path, settings in entries.items():
                if not path.startswith("../"):
                    self.assertNotIn("createIntermediateGroups:", settings)


if __name__ == "__main__":
    unittest.main()
