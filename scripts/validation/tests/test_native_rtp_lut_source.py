"""Structural resource/ownership guards; these do not execute Android or the GPU."""

import base64
import hashlib
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "android/app/src/main/java/dev/openeos/control/data"


class NativeRtpLutSourceTests(unittest.TestCase):
    def test_fixed_h264_vui_fully_tags_bt709_without_changing_original_pixels(self):
        source = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/NativeRtpLutRenderingTest.kt"
        ).read_text()
        encoded = re.search(r'Base64.getDecoder\(\).decode\("([A-Za-z0-9+/=]+)"\)', source)
        self.assertIsNotNone(encoded)
        payload = base64.b64decode(encoded.group(1), validate=True)
        units = [nal for nal in re.split(b"\x00\x00(?:\x00)?\x01", payload) if nal]
        self.assertEqual([nal[0] & 31 for nal in units], [7, 8, 5])
        # The original PPS and IDR are unchanged. Only the SPS color description changed.
        self.assertEqual(hashlib.sha256(units[1]).hexdigest(),
                         "8c597ccf6b30196213ac90a0a77aa2fdbe81c8f4671b98f1e45ab23cf6120561")
        self.assertEqual(hashlib.sha256(units[2]).hexdigest(),
                         "bababe1c97a0947745672fb7e993c535b57609e997faec2f92f5661abf75be6d")
        rbsp = units[0].replace(b"\x00\x00\x03", b"\x00\x00")
        bits = "".join(f"{byte:08b}" for byte in rbsp)
        offset = 0

        def take(count):
            nonlocal offset
            self.assertLessEqual(offset + count, len(bits))
            value = int(bits[offset:offset + count], 2) if count else 0
            offset += count
            return value

        def ue():
            zeros = 0
            while take(1) == 0:
                zeros += 1
                self.assertLess(zeros, 32)
            return (1 << zeros) - 1 + take(zeros)

        # Parse this fixed high-profile 4:2:0 SPS, failing if its shape changes.
        self.assertEqual((take(8), take(8)), (0x67, 100))
        take(16)  # Constraint flags and level.
        self.assertEqual((ue(), ue(), ue(), ue()), (0, 1, 0, 0))  # SPS id, 420, 8-bit luma/chroma.
        self.assertEqual((take(1), take(1)), (0, 0))  # No bypass or scaling matrix.
        self.assertEqual((ue(), ue(), ue(), take(1)), (0, 2, 0, 0))  # Frame number, POC, refs, gaps.
        self.assertEqual((ue(), ue(), take(1), take(1), take(1)), (3, 2, 1, 1, 0))  # 64x48, no crop.
        self.assertEqual((take(1), take(1), take(1)), (1, 0, 0))  # VUI present; no SAR/overscan.
        self.assertEqual((take(1), take(3), take(1), take(1)), (1, 5, 0, 1))  # Limited-range color description.
        self.assertEqual((take(8), take(8), take(8)), (1, 1, 1))  # BT.709 primaries, transfer, matrix.
        self.assertIn("assertFixtureColorFormat(format)", source)
        for key, value in (("STANDARD", "STANDARD_BT709"), ("RANGE", "RANGE_LIMITED"),
                           ("TRANSFER", "TRANSFER_SDR_VIDEO")):
            self.assertIn(f"MediaFormat.KEY_COLOR_{key} to MediaFormat.COLOR_{value}", source)
        self.assertIn('outputFormat != "unobserved"', source)

    def test_decoder_targets_gpu_input_and_sampler_targets_visible_surface(self):
        session = (DATA / "AndroidCcapiRtpSession.kt").read_text()
        gpu = (DATA / "NativeRtpLutRenderer.kt").read_text()
        self.assertIn("codec.configure(format, codecSurface, null, 0)", session)
        self.assertIn("renderer.inputSurface()", session)
        self.assertIn("eglCreateWindowSurface(display, config, target", gpu)
        self.assertIn("source.getTransformMatrix(transform)", gpu)
        self.assertIn("eglSwapBuffers(display, window)", gpu)
        self.assertNotIn("target.release()", gpu)
        self.assertIn("lutRenderer?.close()", session)
        self.assertLess(session.index("codec?.release()"), session.index("lutRenderer?.close()"))

    def test_retirement_is_bounded_and_drained_before_replacement(self):
        gpu = (DATA / "NativeRtpLutRenderer.kt").read_text()
        session = (DATA / "AndroidCcapiRtpSession.kt").read_text()
        self.assertIn("pendingDraw.compareAndSet(false, true)", gpu)
        self.assertIn("presentationGate.configure(false)", gpu)
        self.assertIn("retired.await()", gpu)
        self.assertIn("withContext(NonCancellable)", gpu)
        self.assertIn("launchDrainedNativeVideoJob(scope, previous)", session)
        self.assertIn("continuity.admit(sequence, accessUnit.keyFrame)", session)
        self.assertIn("continuity.dropped()", session)

    def test_float_lut_interpolates_before_clamping_and_color_guard_precedes_presentation(self):
        gpu = (DATA / "NativeRtpLutRenderer.kt").read_text()
        session = (DATA / "AndroidCcapiRtpSession.kt").read_text()
        self.assertIn("GLES30.GL_RGBA32F", gpu)
        self.assertIn("texelFetch(cube", gpu)
        self.assertIn("nativeLutH264ProfileSupported(latestSps)", session)
        self.assertIn("NativeVideoLutStatus.UNSUPPORTED_COLOR", session)
        self.assertIn("decodeSurface(targetSurface, generation, null)", session)

    def test_native_fixture_retains_numeric_assertions_and_bounded_failure_evidence(self):
        fixture = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/NativeRtpLutRenderingTest.kt"
        ).read_text()
        self.assertIn("kotlin.math.abs(before[channel] - expected[index][channel]) <= 18", fixture)
        self.assertIn("kotlin.math.abs(after[channel] - before[2 - channel]) <= 3", fixture)
        self.assertIn("kotlin.math.abs(transformed[channel] - numeric[channel] * 255f) <= 6f", fixture)
        self.assertIn("positions.joinToString", fixture)
        self.assertIn("val format = codec.outputFormat", fixture)
        self.assertIn("outputFormat = describeFormat(format)", fixture)
        self.assertIn("decoder = codec.name.take(96)", fixture)
        self.assertIn("NATIVE_RTP_LUT ${evidence.joinToString", fixture)
        self.assertNotIn("format.toString()", fixture)
        self.assertNotIn("takeScreenshot", fixture)

    def test_native_fixture_retires_prior_captures_when_a_later_phase_fails(self):
        fixture = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/NativeRtpLutRenderingTest.kt"
        ).read_text()
        self.assertLess(
            fixture.index("val captured = mutableListOf<Bitmap>()"), fixture.index('render(target, "identity"')
        )
        self.assertIn("captured.forEach(Bitmap::recycle)", fixture)
        self.assertIn("if (primaryFailure != null || retired.isFailure) captured?.recycle()", fixture)
        self.assertIn("primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure", fixture)


if __name__ == "__main__":
    unittest.main()
