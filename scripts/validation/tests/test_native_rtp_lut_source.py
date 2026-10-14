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


if __name__ == "__main__":
    unittest.main()
