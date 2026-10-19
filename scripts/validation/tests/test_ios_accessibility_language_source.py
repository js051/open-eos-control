"""Existing accessibility caller/resource wiring; not UIKit or VoiceOver execution."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"


def selected_focus_button_language(source):
    return (
        "@EnvironmentObject private var language: AppLanguageStore" in source
        and 'button.accessibilityLabel = language.string("held_autofocus")' in source
        and (
            'button.accessibilityValue = language.string(camera.heldAutofocusPressed ? "hel'
            'd_autofocus_active" : "held_autofocus_released")'
        )
        in source
        and 'button.accessibilityHint = language.string("held_autofocus_hint")' in source
        and "NSLocalizedString(" not in source
    )


def selected_live_view_accessibility_language(source):
    content = source.split("private struct LiveViewSurfaceContent: View {", 1)[1]
    overlay = source.split("private struct CameraReportedFocusOverlay: View {", 1)[1]
    return (
        "@EnvironmentObject private var language: AppLanguageStore" in content.split("var body:", 1)[0]
        and "@EnvironmentObject private var language: AppLanguageStore" in overlay.split("var body:", 1)[0]
        and 'language.format("live_view_magnify_to", target.rawValue)' in content
        and 'info.frames.map { language.string("camera_focus_" + $0.status.rawValue.lowercased()) }' in overlay
    )


class IOSAccessibilityLanguageSourceTests(unittest.TestCase):
    def test_held_autofocus_observes_selected_language_at_uikit_update(self):
        source = (APP / "HeldAutofocusButton.swift").read_text()
        self.assertTrue(selected_focus_button_language(source))
        self.assertIn(
            "button.addTarget(context.coordinator, action: #selector(Coordinator.down), for: .touchDown)", source
        )
        self.assertIn("[.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit]", source)
        self.assertIn(
            (
                "static func dismantleUIView(_ uiView: AccessibleFocusButton, coordinator: Coor"
                "dinator) { coordinator.releaseHold() }"
            ),
            source,
        )
        self.assertIn("camera.releaseHeldAutofocus(owner: owner)", source)
        self.assertIn("if owner != nil { releaseHold() } else { down() }", source)

    def test_magnification_and_focus_overlay_use_selected_store_without_changing_bulb(self):
        source = (APP / "LiveViewSurface.swift").read_text()
        self.assertTrue(selected_live_view_accessibility_language(source))
        self.assertIn('NSLocalizedString("bulb_exposure_time", comment: "")', source)
        self.assertIn("Task { await camera.setLiveViewMagnification(target) }", source)
        self.assertIn("CameraFocusInfo.isFresh(sampledAt: sampledAt, now: context.date)", source)
        self.assertIn("if !values.contains(value) { values.append(value) }", source)
        self.assertIn('.accessibilityIdentifier("camera-reported-focus-frames")', source)

    def test_existing_accessibility_keys_resolve_and_preserve_format_shape(self):
        keys = [
            "held_autofocus",
            "held_autofocus_active",
            "held_autofocus_released",
            "held_autofocus_hint",
            "camera_focus_standby",
            "camera_focus_focused",
            "camera_focus_unfocused",
            "camera_focus_servo_off",
            "camera_focus_focusing",
            "camera_focus_unknown",
        ]
        for language in ["en", "zh-Hant"]:
            resource = (APP / f"Resources/{language}.lproj/Localizable.strings").read_text()
            for key in keys:
                self.assertRegex(resource, rf'(?m)^"{key}" = "[^\n]+";$')
            value = re.search(r'^"live_view_magnify_to" = "(.*)";$', resource, re.M)
            self.assertIsNotNone(value)
            self.assertEqual(value[1].count("%d"), 1)

    def test_authored_native_test_hosts_real_representable_and_reuses_button(self):
        source = (ROOT / "ios/OpenEOSControl/Tests/AccessibilityLanguageTests.swift").read_text()
        self.assertEqual(len(re.findall(r"^    func test", source, re.M)), 3)
        for token in [
            "UIHostingController(rootView: HeldAutofocusButton(camera: state).environmentObject(language))",
            "AppLanguage.english, .traditionalChinese, .english",
            "button.accessibilityHint == hint",
            "focusButton(in: host.view) === button",
            "XCTAssertFalse(state.heldAutofocusPressed)",
            "CameraFocusStatus.allCases",
            "window.rootViewController = nil",
            "func testHeldAFAdmissionCannotBeOverriddenByExternalEnablement()",
            "button.setControlAdmission(false, environmentEnabled: true)",
            "button.setControlAdmission(true, environmentEnabled: false)",
            "XCTAssertEqual(activations, 0)",
            "XCTAssertEqual(activations, 1)",
        ]:
            self.assertIn(token, source)


if __name__ == "__main__":
    unittest.main()
