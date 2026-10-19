"""Source guards only; actual segmented-control behavior needs Apple UI tests."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class IOSCaptureModeLayoutSourceTests(unittest.TestCase):
    def test_empty_draft_reserves_geometry_but_has_no_action(self):
        source = (ROOT / "ios/OpenEOSControl/App/CameraControlView.swift").read_text()
        picker = source.split("private struct CaptureModePicker: View", 1)[1]
        self.assertIn("if let selected = draft", picker)
        for guard in (
            ".hidden()\n                .overlay {\n                    if let selected = draft",
            ".frame(minHeight: 44)",
            ".disabled(!camera.canStartShootingSetting || selected == camera.captureMode",
            "revision == submittedRevision, owner == editorOwner",
            "sessionOwner == camera.mediaLibrarySessionID",
        ):
            self.assertIn(guard, picker)

    def test_native_tests_keep_single_selection_and_verify_stable_geometry(self):
        source = (ROOT / "ios/OpenEOSControl/UITests/OpenEOSControlUITests.swift").read_text()
        self.assertIn('XCTAssertFalse(app.buttons["capture-mode-apply"].exists', source)
        self.assertEqual(source.count("XCTAssertEqual(mode.frame.minY, pickerFrameBeforeSelection.minY"), 2)

    def test_apple_sdk_probe_does_not_construct_or_discover(self):
        source = (ROOT / "ios/OpenEOSControl/Tests/ImageCapturePTPAppleCompilationTests.swift").read_text()
        self.assertIn("requirePortConformance(ImageCapturePTPApplePort.self)", source)
        self.assertIn("throw XCTSkip", source)
        for forbidden in ("ImageCapturePTPApplePort(", ".make(", ".perform(", ".start("):
            self.assertNotIn(forbidden, source)
