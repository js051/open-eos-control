"""Decode-failure wiring/resource guards; not Android decoding or Compose execution."""

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / "android/app/src/main"
UI = MAIN / "java/dev/openeos/control/ui"


class AndroidLiveViewDecodeLanguageSourceTests(unittest.TestCase):
    def test_production_null_decode_keeps_typed_details_and_existing_retirement(self):
        source = (UI / "CameraViewModel.kt").read_text()
        reader = source.split("    private suspend fun readLiveViewFrameInternal(", 1)[1].split(
            "    private fun clearLiveViewPresentation()", 1
        )[0]
        self.assertIn("BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)", reader)
        self.assertIn("?: throw LiveViewFrameDecodeException(frame.bytes.size, frame.contentType)", reader)
        self.assertIn("catch (exception: CancellationException) {\n            throw exception", reader)
        self.assertIn("retireFailedLiveView(generation, owner, formatException(exception))", reader)
        retirement = source.split("    private fun retireFailedLiveView(", 1)[1].split(
            "    private fun formatException(", 1
        )[0]
        self.assertIn("if (generation != liveViewGeneration || !repository.ownsLiveView(owner)) return", retirement)
        self.assertIn("if (!repository.retireLiveView(owner)) return", retirement)
        self.assertIn("clearLiveViewPresentation()", retirement)
        self.assertIn("error = message, errorOperation = CameraOperation.LIVE_VIEW", retirement)
        self.assertIn("queueLiveViewReconciliation()", retirement)

    def test_only_top_level_decode_exception_emits_the_new_token(self):
        source = (UI / "CameraViewModel.kt").read_text()
        formatter = source.split("    private fun formatException(", 1)[1].split(
            "    private fun startLiveViewLoopIfNeeded()", 1
        )[0]
        self.assertIn("else if (exception is LiveViewFrameDecodeException)", formatter)
        self.assertIn("exception.failure.messageToken", formatter)
        self.assertIn('append(exception.message ?: "Unknown error")', formatter)
        self.assertIn('append(cause.message ?: "Unknown cause")', formatter)
        self.assertNotIn("LiveViewFrameDecode", (UI / "CameraAppError.kt").read_text())

    def test_decode_rendering_precedes_existing_safety_mapper_and_raw_fallback(self):
        source = (UI / "CameraComponents.kt").read_text()
        banner = source.split("fun ErrorBanner(", 1)[1].split("@Composable", 1)[0]
        self.assertIn(
            (
                "liveViewDecodeFailureMessage(error) ?: (cameraAppErrorResource(error) ?: userF"
                "acingCameraErrorResource(error))"
            ),
            banner,
        )
        self.assertIn("?.let { stringResource(it) } ?: error", banner)
        helper = (UI / "LiveViewDecodeFailure.kt").read_text()
        self.assertIn("takeIf { it.messageToken == message }", helper)
        self.assertIn("require(byteCount >= 0)", helper)
        self.assertIn(
            "failure.contentType ?: stringResource(R.string.camera_error_live_view_content_type_unknown)", helper
        )
        self.assertNotIn("trim(", helper)

    def test_both_languages_have_matching_typed_placeholders(self):
        names = {"camera_error_live_view_decode_failed", "camera_error_live_view_content_type_unknown"}
        translations = []
        for folder in ("values", "values-zh-rTW"):
            rows = ET.parse(MAIN / "res" / folder / "strings.xml").getroot().findall("string")
            keys = [row.attrib["name"] for row in rows]
            self.assertEqual(len(keys), len(set(keys)))
            values = {row.attrib["name"]: row.text for row in rows}
            for name in names:
                self.assertTrue(values.get(name), name)
            self.assertEqual(
                ["%1$d", "%2$s"], re.findall(r"%\d+\$[a-z]", values["camera_error_live_view_decode_failed"])
            )
            translations.append(values)
        for name in names:
            self.assertNotEqual(translations[0][name], translations[1][name])


if __name__ == "__main__":
    unittest.main()
