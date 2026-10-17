"""App-error ownership/resource wiring only; not an Android build or native locale test."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / "android/app/src/main"
UI = MAIN / "java/dev/openeos/control/ui"


class AndroidAppErrorLanguageSourceTests(unittest.TestCase):
    def test_disconnect_uses_imported_failure_and_context_free_warning(self):
        source = (UI / "CameraViewModel.kt").read_text()
        self.assertIn("import dev.openeos.control.data.MaintenanceRetirementUnconfirmedException", source)
        body = source.split("    fun disconnect() {", 1)[1].split("    /** Explicit receipt recovery", 1)[0]
        self.assertIn("CameraAppError.MAINTENANCE_RETIREMENT_UNCONFIRMED.messageToken", body)
        self.assertNotIn("appContext", body)
        self.assertNotIn("getString", body)
        self.assertIn("if (cameraSessionGeneration == disconnectGeneration)", body)
        self.assertIn("it.shutterDisconnectWarning || failure.physicalReleaseUnconfirmed", body)
        self.assertIn("it.liveViewDisconnectWarning || failure.stopUnconfirmed", body)

    def test_only_typed_top_level_failures_produce_app_tokens(self):
        source = (UI / "CameraViewModel.kt").read_text()
        formatter = source.split("    private fun formatException(", 1)[1].split("    private fun startLiveViewLoopIfNeeded()", 1)[0]
        self.assertIn("if (exception is CameraAppException)", formatter)
        self.assertIn("exception.error.messageToken", formatter)
        self.assertIn('append(exception.message ?: "Unknown error")', formatter)
        self.assertIn('append(cause.message ?: "Unknown cause")', formatter)
        helper = (UI / "CameraAppError.kt").read_text()
        self.assertIn("firstOrNull { it.messageToken == message }", helper)
        self.assertNotIn("startsWith(", helper)
        self.assertNotIn("contains(", helper)

    def test_upload_checks_keep_original_conditions_and_use_typed_app_errors(self):
        source = (UI / "CameraViewModel.kt").read_text()
        body = source.split("    fun uploadMedia(context: Context, sourceUri: Uri)", 1)[1].split("\n    fun ", 1)[0]
        for condition, reason in (
            ("name.isEmpty()", "UPLOAD_FILENAME_UNKNOWN"),
            ("copied > MAX_MEDIA_UPLOAD_BYTES", "UPLOAD_TOO_LARGE"),
            ("resolvedSize !in 1L..MAX_MEDIA_UPLOAD_BYTES", "UPLOAD_SIZE_INVALID"),
            ("receipt == null", "UPLOAD_RECEIPT_MISSING"),
        ):
            self.assertIn(f"if ({condition}) throw CameraAppException(CameraAppError.{reason})", body)
        self.assertIn("?: throw CameraAppException(CameraAppError.UPLOAD_SOURCE_UNAVAILABLE)", body)
        self.assertIn("catch (exception: CancellationException)", body)
        self.assertIn("attempt.finish()", body)
        self.assertIn("MediaUploadStatus.CONFIRMED", body)

    def test_ui_resolves_current_locale_without_rewriting_unrecognized_diagnostics(self):
        source = (UI / "CameraComponents.kt").read_text()
        body = source.split("fun ErrorBanner(", 1)[1].split("@Composable", 1)[0]
        self.assertIn("cameraAppErrorResource(error) ?: userFacingCameraErrorResource(error)", body)
        self.assertIn("?.let { stringResource(it) } ?: error", body)
        locale = (UI / "AppLanguage.kt").read_text()
        self.assertIn("AppCompatDelegate.setApplicationLocales(locales)", locale)
        connection = (UI / "ConnectionRecoveryComponents.kt").read_text()
        self.assertIn("stringResource(connectionRecoveryMessage(recovery))", connection)

    def test_every_app_error_has_english_and_traditional_chinese_resources(self):
        names = set(re.findall(r"R\.string\.(\w+)", (UI / "CameraAppError.kt").read_text()))
        self.assertEqual(6, len(names))
        localized = []
        for folder in ("values", "values-zh-rTW"):
            rows = ET.parse(MAIN / "res" / folder / "strings.xml").getroot().findall("string")
            keys = [row.attrib["name"] for row in rows]
            self.assertEqual(len(keys), len(set(keys)))
            values = {row.attrib["name"]: row.text for row in rows}
            for name in names:
                self.assertTrue(values.get(name), name)
            localized.append(values)
        for name in names:
            self.assertNotEqual(localized[0][name], localized[1][name])


if __name__ == "__main__":
    unittest.main()
