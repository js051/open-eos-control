"""Structural AVC startup guards. These do not establish native decoder/color behavior."""

import base64
import hashlib
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "android/app/src/main/java/dev/openeos/control/data"


class H264StartupSourceTests(unittest.TestCase):
    def test_platform_format_uses_exact_config_csd_and_dimensions_without_color_override(self):
        source = (DATA / "AndroidH264DecoderFormat.kt").read_text()
        self.assertIn('"video/avc", configuration.width, configuration.height', source)
        self.assertIn('setByteBuffer("csd-0", ByteBuffer.wrap(configuration.csd0))', source)
        self.assertIn('setByteBuffer("csd-1", ByteBuffer.wrap(configuration.csd1))', source)
        self.assertNotIn("KEY_COLOR_", source)
        self.assertNotIn("COLOR_STANDARD_", source)
        self.assertIn("FEATURE_LowLatency", source)
        self.assertIn("setInteger(MediaFormat.KEY_OPERATING_RATE, 30)", source)

    def test_production_and_native_pixel_test_use_the_same_format_builder(self):
        session = (DATA / "AndroidCcapiRtpSession.kt").read_text()
        fixture = (ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/NativeRtpLutRenderingTest.kt").read_text()
        self.assertIn("createNativeRtpAvcFormat(", session)
        self.assertIn("createNativeRtpAvcFormat(prepared.configuration, codec)", fixture)
        self.assertNotIn("MediaFormat.createVideoFormat", session)
        self.assertNotIn("MediaFormat.createVideoFormat", fixture)
        self.assertNotIn("concatenateParameterSets", session)
        self.assertNotIn("latestSps", session)
        self.assertNotIn("latestPps", session)

    def test_original_fixture_and_first_picture_acceptance_are_unchanged(self):
        fixture = (ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/NativeRtpLutRenderingTest.kt").read_text()
        encoded = re.search(r'Base64.getDecoder\(\).decode\("([A-Za-z0-9+/=]+)"\)', fixture)
        self.assertIsNotNone(encoded)
        self.assertEqual(
            hashlib.sha256(base64.b64decode(encoded.group(1), validate=True)).hexdigest(),
            "b48b0a7e9a6a54e6b3f34298ef8cbde86034fe1ed850bb3035f1d35c43d88fdf",
        )
        first = fixture.split("private suspend fun render(", 1)[1].split("if (swap && overrideLut == null)", 1)[0]
        self.assertEqual(first.count("codec.queueInputBuffer("), 1)
        self.assertIn("val picture = prepared.mediaBytes", first)
        self.assertIn("codec.queueInputBuffer(index, 0, picture.size, 1_000, 0)", first)
        self.assertIn("TimeUnit.SECONDS.toNanos(6)", first)
        for expression in (
            "kotlin.math.abs(before[channel] - expected[index][channel]) <= 18",
            "kotlin.math.abs(after[channel] - before[2 - channel]) <= 3",
            "kotlin.math.abs(transformed[channel] - numeric[channel] * 255f) <= 6f",
        ):
            self.assertIn(expression, fixture)
        self.assertNotIn("BUFFER_FLAG_END_OF_STREAM", fixture)

    def test_wait_and_dequeued_slot_retirement_do_not_invent_input(self):
        session = (DATA / "AndroidCcapiRtpSession.kt").read_text()
        seed_wait = session.split("private suspend fun awaitDecoderSeed(", 1)[1].split(
            "private suspend fun decodeTo(", 1
        )[0]
        self.assertIn("accessUnits.receiveCatching().getOrNull() ?: return null", seed_wait)
        queue = session.split("private fun queueDecoderInput(", 1)[1].split(
            "private fun reportFailure(", 1
        )[0]
        self.assertIn("nativeRtpInputSlotAdmission(ownsDecoder(targetSurface, generation)", queue)
        self.assertIn("NativeRtpInputSlotAdmission.RETIRE_CODEC", queue)
        self.assertIn("restartDecoder(targetSurface)", queue)
        self.assertEqual(queue.count("codec.queueInputBuffer("), 1)
        self.assertIn("codec.queueInputBuffer(inputIndex, 0, encoded.size, presentationTimeUs, 0)", queue)
        self.assertLess(queue.index("codec.queueInputBuffer("), queue.index("decoderSeeds.submitted("))
        self.assertNotIn("BUFFER_FLAG_END_OF_STREAM", session)


if __name__ == "__main__":
    unittest.main()
