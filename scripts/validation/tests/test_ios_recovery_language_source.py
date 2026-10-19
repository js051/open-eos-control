"""Selected-language recovery source/resource checks; NOT Swift/XCTest/UI execution."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"


def shared_language_owner(app, state):
    constructions = re.findall(r"CameraAppState\(([^\n]+)", app)
    return bool(
        constructions
        and all(value.startswith("language: language,") for value in constructions)
        and "_language = StateObject(wrappedValue: language)" in app
        and "private let language: AppLanguageStore" in state
        and "self.language = language ?? AppLanguageStore(defaults: defaults)" in state
    )


def selected_recovery_messages(state, control):
    return (
        'lastError = language.string("lut_render_failed")' in state
        and 'language.format("lut_import_failed", error.localizedDescription)' in state
        and 'language.format("recording_original_response"' in control
        and 'language.format("recording_current_observation"' in control
        and "NSLocalizedString(" not in state
        and "NSLocalizedString(" not in control
    )


class IOSRecoveryLanguageSourceTests(unittest.TestCase):
    def test_every_app_entrypoint_shares_the_current_language_owner(self):
        app, state = (APP / "OpenEOSControlApp.swift").read_text(), (APP / "CameraAppState.swift").read_text()
        self.assertTrue(shared_language_owner(app, state))
        self.assertEqual(app.count("CameraAppState(language: language,"), 5)
        self.assertEqual(app.count("let language = AppLanguageStore()"), 1)
        self.assertIn(".environmentObject(language)", app)
        self.assertIn(".environment(\\.locale, language.locale)", app)
        self.assertNotIn("AppleLanguages", app + state)

    def test_app_owned_recovery_keys_use_existing_selected_bundle_helpers(self):
        state, control = (APP / "CameraAppState.swift").read_text(), (APP / "CameraControlView.swift").read_text()
        self.assertTrue(selected_recovery_messages(state, control))
        for key in [
            "live_view_render_stalled",
            "shutter_af_unavailable",
            "capture_status_readback_failed",
            "maintenance_partial_failure",
            "recording_command_rejected",
            "recording_start_not_confirmed",
            "lut_render_failed",
        ]:
            self.assertIn(f'language.string("{key}")', state)
        self.assertIn("language.string(current.statusKey)", state)
        self.assertIn("language.string(current.readbackKey)", state)
        for key in [
            "recording_response_",
            "recording_observed_active",
            "recording_observed_stopped",
            "recording_observed_unavailable",
        ]:
            self.assertIn(key, control)
        language = (APP / "AppLanguage.swift").read_text()
        self.assertIn('case .english: languageCode = "en"', language)
        self.assertIn('case .traditionalChinese: languageCode = "zh-Hant"', language)
        self.assertIn("case .system: languageCode = nil", language)
        self.assertIn("locale: locale, arguments: arguments", language)

    def test_raw_diagnostics_and_error_ownership_are_unchanged(self):
        state = (APP / "CameraAppState.swift").read_text()
        record = state.split("private func record(_ error: Error)", 1)[1].split("private func removeDownloadedFile", 1)[
            0
        ]
        self.assertIn("lastError = error.localizedDescription", record)
        self.assertNotIn("language.", record)
        self.assertIn("didSet { lastErrorOwner = UUID() }", state)
        self.assertIn('language.format("lut_import_failed", error.localizedDescription)', state)
        self.assertNotIn("language.string(error.localizedDescription)", state)
        self.assertIn("if cubeLutImportErrorOwner == lastErrorOwner { lastError = nil }", state)

    def test_recovery_templates_have_matching_placeholder_counts_in_both_bundles(self):
        for language in ["en", "zh-Hant"]:
            source = (APP / f"Resources/{language}.lproj/Localizable.strings").read_text()
            for key, count in [
                ("lut_import_failed", 1),
                ("recording_original_response", 2),
                ("recording_current_observation", 1),
            ]:
                value = re.search(rf'^"{key}"\s*=\s*"(.*)";$', source, re.M)
                self.assertIsNotNone(value, (language, key))
                self.assertEqual(value[1].count("%@"), count)

    def test_native_regressions_cover_current_selection_saved_default_and_raw_text(self):
        source = (ROOT / "ios/OpenEOSControl/Tests/RecoveryLanguageTests.swift").read_text()
        self.assertEqual(len(re.findall(r"^    func test", source, re.M)), 5)
        for token in [
            "AppLanguage.english, .traditionalChinese, .english",
            "state.reportCubeLutRenderFailure()",
            "state.reportCubeLutImportError(SyntheticRecoveryError(raw: raw))",
            "CancellationError()",
            "language.select(.system)",
            "CameraAppState(defaults: defaults)",
            "XCTAssertEqual(state.lastError, precedingError)",
            'language.string("recording_response_unconfirmed")',
        ]:
            self.assertIn(token, source)
        project = (ROOT / "ios/OpenEOSControl/project.yml").read_text()
        self.assertIn("      - path: Tests\n", project)


if __name__ == "__main__":
    unittest.main()
