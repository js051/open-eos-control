from __future__ import annotations

import base64
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.ci import export_ios_ui_evidence as evidence


# Deliberately synthetic 1x1 PNG, test tree and manifest; not recorded device data.
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9L8AAAAASUVORK5CYII="
)
GEOMETRY = (
    "[OEC_SHUTTER_DISCONNECT_GEOMETRY] before-tap\n"
    "window=(0.0, 0.0, 400.0, 800.0)\n"
    "more-actions-sheet-exists=true\n"
    "disconnect-menu-button count=1\n"
    "disconnect-menu-button[0] enabled=true hittable=true frame=(20.0, 500.0, 360.0, 48.0)\n"
    "release-shutter-button[0] disconnect-overlap=(inf, inf, 0.0, 0.0) contains-disconnect-center=false\n"
    "camera-model-status-exists=true\n"
).encode()


def test_tree():
    return {"testNodes": [{"nodeType": "Test Suite", "children": [
        {"nodeType": "Test Case", "nodeIdentifier": f"OpenEOSControlUITests/{name}()",
         "result": "Passed"} for name in evidence.TESTS
    ]}]}


class IOSEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.staging = self.root / "staging"
        self.output = self.root / "output"
        self.bundle = self.root / "synthetic.xcresult"
        for path in (self.staging, self.output, self.bundle):
            path.mkdir()
        self.report = {"files": [], "bytes": 0, "warnings": []}

    def manifest(self, *entries):
        (self.staging / "manifest.json").write_text(json.dumps([{"attachments": list(entries)}]))

    def attachment(self, name="previous-warning-and-current-stop", filename="synthetic.png", data=PNG):
        (self.staging / filename).write_bytes(data)
        return {"suggestedHumanReadableName": name, "exportedFileName": filename}

    def collect(self, **kwargs):
        evidence.collect_attachments(self.staging, self.output, evidence.TESTS[0], self.report, **kwargs)

    def test_selects_only_exact_requested_test_cases_and_deduplicates_runs(self):
        tree = test_tree()
        children = tree["testNodes"][0]["children"]
        children.extend([
            {"nodeType": "Test Case", "nodeIdentifier": "OtherClass/" + evidence.TESTS[0], "result": "Passed"},
            {"nodeType": "Test Case", "nodeIdentifier": "OpenEOSControlUITests/testOther", "result": "Passed"},
            {"nodeType": "Test Case", "nodeIdentifier": children[0]["nodeIdentifier"], "result": "Failed",
             "children": [{"nodeType": "Failure Message", "name": "Do not publish this private field"}]},
        ])
        selected = evidence.selected_tests(tree)
        self.assertEqual(set(selected), set(evidence.TESTS))
        self.assertEqual(set(selected[evidence.TESTS[0]]["results"]), {"Passed", "Failed"})
        self.assertNotIn("private", json.dumps(selected))

    def test_rejects_unknown_test_tree_and_json_schemas(self):
        for payload in ([], {}, {"testNodes": [{"children": "invalid"}]}):
            with self.assertRaises(evidence.EvidenceError):
                evidence.selected_tests(payload)
        with self.assertRaises(evidence.EvidenceError):
            evidence.read_json(b"not json")
        with patch.object(evidence, "MAX_JSON_BYTES", 2):
            with self.assertRaises(evidence.EvidenceError):
                evidence.read_json(b"[1]")

    def test_accepts_human_name_and_xcode_counter_uuid_suffix(self):
        name = "shutter-recovery-disconnect-before-tap"
        self.assertEqual(evidence.attachment_name(name, evidence.TESTS[0]), name)
        self.assertEqual(evidence.attachment_name(name + "_0_AAAAAAAA-AAAA.png", evidence.TESTS[0]), name)
        self.assertIsNone(evidence.attachment_name("private-camera.png", evidence.TESTS[0]))
        self.assertIsNone(evidence.attachment_name(name + "../outside.png", evidence.TESTS[0]))

    def test_filters_video_unrelated_and_other_test_attachments(self):
        selected = self.attachment()
        video = self.attachment("Screen Recording", "movie.mp4", b"synthetic video")
        unrelated = self.attachment("private-camera", "other.png")
        other_test = self.attachment(
            "shutter-recovery-english-UICTContentSizeCategoryXS-active-1", "matrix.png"
        )
        self.manifest(selected, video, unrelated, other_test)
        self.collect()
        self.assertEqual(len(self.report["files"]), 1)
        self.assertEqual(next(self.output.iterdir()).read_bytes(), PNG)

    def test_selects_all_hit_target_phases_but_only_four_matrix_samples(self):
        for case in evidence.HIT_TARGET_CASES:
            for name in (f"shutter-recovery-disconnect-{case}-before-disconnect",
                         f"shutter-recovery-disconnect-{case}-before-disconnect-geometry",
                         f"shutter-recovery-sheet-stop-{case}"):
                self.assertEqual(evidence.attachment_name(name + "_0_AAAA.png", evidence.TESTS[2]), name)
            geometry = GEOMETRY.replace(b"before-tap", (case + "-before-disconnect").encode())
            self.assertEqual(evidence.geometry_text(geometry), geometry)
        for sample in evidence.MATRIX_SAMPLES:
            self.assertEqual(evidence.attachment_name(sample, evidence.TESTS[1]), sample)
        self.assertIsNone(evidence.attachment_name(
            "shutter-recovery-english-UICTContentSizeCategoryXS-active-4", evidence.TESTS[1]
        ))

    def test_accepts_allowlisted_geometry_but_never_arbitrary_diagnostics(self):
        self.manifest(self.attachment("shutter-recovery-disconnect-before-tap-geometry", "geometry.txt", GEOMETRY))
        self.collect()
        self.assertEqual(len(self.report["files"]), 1)
        self.assertEqual(next(self.output.iterdir()).read_bytes(), GEOMETRY)
        with self.assertRaises(evidence.EvidenceError):
            evidence.geometry_text(GEOMETRY + b"camera-serial=SYNTHETIC-PRIVATE\n")
        scientific = GEOMETRY.replace(b"20.0", b"-2.2e-13")
        self.assertEqual(evidence.geometry_text(scientific), scientific)

    def test_rejects_traversal_and_symlinks(self):
        self.manifest({"suggestedHumanReadableName": "previous-warning-and-current-stop", "exportedFileName": "../outside.png"})
        with self.assertRaises(evidence.EvidenceError):
            self.collect()
        (self.root / "outside.png").write_bytes(PNG)
        (self.staging / "link.png").symlink_to(self.root / "outside.png")
        self.manifest({"suggestedHumanReadableName": "previous-warning-and-current-stop", "exportedFileName": "link.png"})
        with self.assertRaises(evidence.EvidenceError):
            self.collect()

    def test_enforces_per_file_total_and_count_limits_without_overflow(self):
        self.manifest(self.attachment(), self.attachment("shutter-recovery-disconnect-before-tap", "second.png"))
        self.collect(budget=len(PNG))
        self.assertEqual(self.report["bytes"], len(PNG))
        self.assertEqual(len(self.report["files"]), 1)
        self.assertTrue(self.report["warnings"])
        self.assertLessEqual(sum(p.stat().st_size for p in self.output.iterdir()), len(PNG))
        with patch.object(evidence, "MAX_PNG_BYTES", 1):
            self.collect()
        self.assertEqual(len(self.report["files"]), 1)
        with patch.object(evidence, "MAX_FILES", 1):
            self.collect()
        self.assertEqual(len(self.report["files"]), 1)

    def test_rejects_non_png_and_unsupported_manifest(self):
        self.manifest(self.attachment(data=b"not a PNG"))
        with self.assertRaises(evidence.EvidenceError):
            self.collect()
        (self.staging / "manifest.json").write_text('{"attachments": []}')
        with self.assertRaises(evidence.EvidenceError):
            self.collect()

    def test_runtime_help_fails_closed_without_legacy_or_full_export(self):
        calls = []

        def unsupported(arguments):
            calls.append(arguments)
            return b"old tool help without required flags"

        report = evidence.export_evidence(self.bundle, self.output, run=unsupported)
        self.assertEqual(report["status"], "incomplete")
        self.assertEqual(calls, [["help", "export", "attachments"]])
        self.assertTrue(report["warnings"])

    def test_missing_bundle_does_not_invoke_xcode_and_produces_summary(self):
        def should_not_run(_arguments):
            self.fail("xcresulttool should not be called without a bundle")

        report = evidence.export_evidence(self.root / "missing.xcresult", self.output, run=should_not_run)
        with patch.dict("os.environ", {"GITHUB_STEP_SUMMARY": str(self.root / "step.md")}):
            evidence.write_summary(self.output, report)
        summary = (self.output / "summary.md").read_text()
        self.assertIn("incomplete", summary)
        self.assertEqual((self.root / "step.md").read_text(), summary)

    def test_fake_tool_exports_scoped_attachments_and_reports_test_failure_separately(self):
        calls = []

        def fake_xcode(arguments):
            calls.append(arguments)
            if arguments[0] == "help":
                return b"--path --output-path --test-id"
            if arguments[0] == "get":
                tree = test_tree()
                tree["testNodes"][0]["children"][0]["result"] = "Failed"
                return json.dumps(tree).encode()
            self.assertEqual(arguments[:2], ["export", "attachments"])
            self.assertIn("--test-id", arguments)
            directory = Path(arguments[arguments.index("--output-path") + 1])
            test = arguments[arguments.index("--test-id") + 1]
            if evidence.TESTS[0] in test:
                name = "previous-warning-and-current-stop"
            elif evidence.TESTS[1] in test:
                name = evidence.MATRIX_SAMPLES[0]
            else:
                name = "shutter-recovery-sheet-stop-" + evidence.HIT_TARGET_CASES[0]
            (directory / "synthetic.png").write_bytes(PNG)
            (directory / "manifest.json").write_text(json.dumps([{"attachments": [
                {"exportedFileName": "synthetic.png", "suggestedHumanReadableName": name}
            ]}]))
            return b""

        report = evidence.export_evidence(self.bundle, self.output, run=fake_xcode)
        self.assertEqual(report["status"], "exported")
        self.assertEqual(report["tests"][evidence.TESTS[0]], ["Failed"])
        self.assertEqual(len(report["files"]), len(evidence.TESTS))
        self.assertEqual(len([args for args in calls if args[0] == "export"]), len(evidence.TESTS))
        first_export = next(args for args in calls if args[0] == "export")
        self.assertIn(evidence.TESTS[2], first_export[-1])
        evidence.write_summary(self.output, report)
        self.assertLessEqual(sum(p.stat().st_size for p in self.output.iterdir()), evidence.MAX_ARTIFACT_BYTES)

    def test_attempts_remaining_tests_after_an_export_failure(self):
        exports = []

        def fake_xcode(arguments):
            if arguments[0] == "help":
                return b"--path --output-path --test-id"
            if arguments[0] == "get":
                return json.dumps(test_tree()).encode()
            exports.append(arguments)
            raise evidence.EvidenceError("Synthetic command failure")

        report = evidence.export_evidence(self.bundle, self.output, run=fake_xcode)
        self.assertEqual(set(report["tests"]), set(evidence.TESTS))
        self.assertEqual(report["status"], "incomplete")
        self.assertEqual(len(report["warnings"]), 2 * len(evidence.TESTS))
        self.assertEqual(len(exports), len(evidence.TESTS))


if __name__ == "__main__":
    unittest.main()
