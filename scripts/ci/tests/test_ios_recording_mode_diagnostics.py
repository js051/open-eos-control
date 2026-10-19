"""Source/privacy guards only; these do not execute Swift or the iOS UI journey."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
UI_TESTS = ROOT / "ios/OpenEOSControl/UITests/OpenEOSControlUITests.swift"


class RecordingModeDiagnosticsSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = UI_TESTS.read_text()
        cls.journey = cls.source.split(
            "func testCanonicalRecordingStopReviewsNewlyVisibleVideoAndPreparesOriginalInBothLanguages()", 1
        )[1].split("\n    @MainActor", 1)[0]
        cls.failure = cls.journey.split("} catch {", 1)[1].split("throw error", 1)[0]

    def test_failure_only_output_preserves_timeout_and_original_evidence(self):
        self.assertEqual(self.source.count('print("[OEC_RECORDING_MODE_FAILURE]'), 1)
        self.assertIn('print("[OEC_RECORDING_MODE_FAILURE]', self.failure)
        self.assertIn('XCTAttachment(string: fields.joined(separator: "\\n"))', self.failure)
        self.assertIn('attachment.name = "recording-mode-failure-\\(language)"', self.failure)
        self.assertIn('attachment.lifetime = .keepAlways', self.failure)
        self.assertIn('addScreenshot(name: "recording-mode-failure-\\(language)")', self.failure)
        self.assertIn("throw error", self.journey)
        self.assertIn('waitForSimulatorState { state in state["movie_mode"] as? String == "on" }', self.journey)
        for forbidden in [".tap()", "tapForAsyncTest(", "Task.sleep", "waitForSimulatorState", "simulatorRequest"]:
            self.assertNotIn(forbidden, self.failure)

    def test_terminal_snapshot_precedes_raw_alert_attachment_fields(self):
        before, after = self.failure.split("var terminalFields = fields", 1)
        self.assertNotIn("text.label", before)
        self.assertNotIn("alert-text-", before)
        self.assertIn('let label = String(text.label.prefix(512))', after)
        self.assertIn('fields.append("alert-text-\\(index)=\\(label)")', after)
        terminal_appends = re.findall(r'terminalFields\.append\((.*)\)', after)
        self.assertEqual(terminal_appends, [
            '"alert-text-\\(index)-exists=\\(exists)"',
            '"alert-text-\\(index)-kind=\\(Self.recordingModeFailureAlertKind(label))"',
        ])
        self.assertIn("for index in 0..<2", after)
        for forbidden in ["debugDescription", "error.localizedDescription", "url", "identifier", "token"]:
            self.assertNotIn(forbidden, after)

    def test_classifier_returns_only_fixed_allowlisted_values(self):
        classifier = self.source.split(
            "private static func recordingModeFailureAlertKind(_ label: String) -> String {", 1
        )[1].split("\n    private ", 1)[0]
        outputs = re.findall(r'return "([^"\n]+)"', classifier)
        self.assertEqual(outputs, ["operation-failed-title", "settings-refresh-invalidated",
                                   "movie-mode-on-not-advertised", "shutter-release-required",
                                   "discovery-incomplete", "unclassified"])
        self.assertEqual(classifier.count("return "), len(outputs))
        self.assertNotIn("contains(", classifier)
        self.assertNotIn("hasPrefix(", classifier)
        self.assertNotIn("\\(", classifier)
        self.assertIn('default:\n            return "unclassified"', classifier)

    def test_recording_and_media_causality_assertions_remain(self):
        for assertion in [
            'XCTAssertTrue(photo.isSelected',
            'XCTAssertFalse(mode.buttons[videoMode].isSelected)',
            'state["recording"] as? Bool == true',
            '(state["record_start_count"] as? NSNumber)?.intValue == 1',
            'state["recording"] as? Bool == false',
            '(state["record_stop_count"] as? NSNumber)?.intValue == 1',
            '(stopped["capture_count"] as? NSNumber)?.intValue == 0',
            '!(stopped["media_ids"] as? [String] ?? []).contains("NEWLY_VISIBLE_RECORDING.MP4")',
            'waitForLabel(status, containing: notReady, timeout: 15)',
            'waitForLabel(status, containing: available, timeout: 15)',
        ]:
            self.assertIn(assertion, self.journey)


class CaptureModeActionSourceTests(unittest.TestCase):
    """Source contracts only; native AX and touch behavior require Apple UI tests."""

    def test_explicit_mode_actions_only_edit_owned_draft(self):
        source = (ROOT / "ios/OpenEOSControl/App/CameraControlView.swift").read_text()
        picker = source.split("private struct CaptureModePicker: View {", 1)[1].split(
            "private struct ExposureStrip", 1)[0]
        self.assertIn('modeButton(.photo, title: "photo")', picker)
        self.assertIn('modeButton(.video, title: "video")', picker)
        self.assertNotIn('Picker("capture_mode", selection:', picker)
        action = picker.split("private func modeButton(", 1)[1]
        for required in ("guard canSelectMode else { return }", "draft = mode", "revision = UUID()",
                         ".frame(maxWidth: .infinity, minHeight: 44)",
                         ".accessibilityAddTraits(selected ? .isSelected : [])"):
            self.assertIn(required, action)
        for guard in ("!camera.recordingStopRequired", "!camera.bulbExposureActive",
                      "camera.canStartShootingSetting", "!camera.isBusy(.capture)", "!camera.isBusy(.recording)"):
            self.assertIn(guard, picker)
        self.assertNotIn("setCaptureMode(", action)
        self.assertEqual(picker.count("await camera.setCaptureMode("), 1)
        for guard in ("if let selected = draft", "revision == submittedRevision", "owner == editorOwner",
                      "sessionOwner == camera.mediaLibrarySessionID", "selected == camera.captureMode"):
            self.assertIn(guard, picker)

    def test_native_journeys_preserve_selection_and_single_write_checks(self):
        source = UI_TESTS.read_text()
        self.assertNotIn('app.segmentedControls["capture-mode-picker"]', source)
        self.assertEqual(source.count('app.otherElements["capture-mode-picker"]'), 4)
        self.assertIn("segment.isSelected && !mode.buttons[other].isSelected", source)
        self.assertIn("mode.buttons[videoMode].isSelected && !photo.isSelected", source)
        self.assertIn("XCTAssertGreaterThanOrEqual(segment.frame.height, 44)", source)
        self.assertIn('(state["movie_mode_update_count"] as? NSNumber)?.intValue == 2', source)


if __name__ == "__main__":
    unittest.main()
