"""Structural resource/ownership guards; these do not execute Android or the GPU."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "android/app/src/main/java/dev/openeos/control/data"


class NativeRtpLutSourceTests(unittest.TestCase):
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
        self.assertIn("describeFormat(codec.outputFormat)", fixture)
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
