"""Controlled source contracts, not Kotlin compilation or native rendering evidence."""

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
JAVA = ROOT / "android/app/src/main/java/dev/openeos/control"


class NativeRtpMonitoringSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sampler = (JAVA / "ui/NativeRtpMonitorSampler.kt").read_text()
        cls.gate = (JAVA / "ui/NativeFrameSamplingGate.kt").read_text()
        cls.session = (JAVA / "data/AndroidCcapiRtpSession.kt").read_text()
        cls.ui = (JAVA / "ui/CameraComponents.kt").read_text()

    def test_sampling_only_uses_the_decoder_surface_and_bounded_srgb_bitmap(self):
        self.assertEqual(1, self.sampler.count("PixelCopy.request("))
        self.assertIn("PixelCopy.request(target, bitmap", self.sampler)
        self.assertIn("frame.surface !== target", self.sampler)
        self.assertIn("analysisDimensions(width, height)", self.sampler)
        self.assertIn("Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB)", self.sampler)
        code = re.sub(r"/\*.*?\*/|//[^\n]*", "", self.sampler, flags=re.DOTALL)
        self.assertNotRegex(code, r"\b(Window|MediaProjection|ImageReader|TextureView)\b")

    def test_capture_is_frame_driven_and_expiry_never_resamples(self):
        self.assertIn("session.renderedFrames.collect { nativeSampler.onFrame(session, it) }", self.ui)
        self.assertIn("setOnFrameRenderedListener", self.session)
        self.assertIn("pendingRendered.size > MAX_PENDING_FRAME_STATS", self.session)
        self.assertNotRegex(self.sampler, r"\b(while|delay|Timer|scheduleAtFixedRate)\s*\(")
        expiry = self.sampler.split("private fun expire()", 1)[1].split("override fun close()", 1)[0]
        self.assertNotIn("onFrame(", expiry)
        self.assertNotIn("PixelCopy", expiry)

    def test_invalidation_and_close_do_not_release_an_outstanding_capture(self):
        invalidate = self.gate.split("fun invalidate(", 1)[1].split("fun begin(", 1)[0]
        self.assertNotIn("inFlight = null", invalidate)
        close = self.sampler.split("override fun close()", 1)[1]
        self.assertNotIn("nativePixelCopyInFlight.set(false)", close)
        self.assertIn("if (!gate.busy) scope.cancel()", close)
        self.assertIn("nativePixelCopyInFlight.compareAndSet(false, true)", self.sampler)
        self.assertIn(
            "nativePixelCopyStartedAtNanos.get()?.let { now - it < NativeFrameSamplingGate.INTERVAL_NANOS }",
            self.sampler,
        )
        self.assertIn("nativePixelCopyStartedAtNanos.set(now)", self.sampler)

    def test_lifecycle_owner_and_lut_contract_are_wired(self):
        self.assertIn("LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { nativeSampler.pause() }", self.ui)
        self.assertIn("state.liveViewTemperatureAllowed && lifecycleState.isAtLeast", self.ui)
        self.assertIn("key(state.nativeLiveViewSession)", self.ui)
        self.assertIn("sampler.detach(session, holder.surface)", self.ui)
        self.assertIn("state.nativeLiveViewSession != null -> key", self.ui)
        self.assertLess(
            self.ui.index("state.nativeLiveViewSession != null -> key"),
            self.ui.index("lutPreviewActive && lutPreviewBitmap != null -> Image"),
        )
        self.assertIn("val pixelSettings = settings", self.sampler)
        self.assertIn("setVideoLut(nativeLut)", self.ui)
        self.assertIn("PixelCopy.request(target, bitmap", self.sampler)
        self.assertNotIn("applyCubeLut", self.sampler)

    def test_monotonic_freshness_has_a_fixed_budget_and_survives_new_frames(self):
        self.assertIn("INTERVAL_NANOS = 250_000_000L", self.gate)
        self.assertIn("MAX_AGE_NANOS = 1_000_000_000L", self.gate)
        self.assertIn("renderedAtNanos < it", self.gate)
        completion = self.gate.split("fun complete(", 1)[1].split("companion object", 1)[0]
        self.assertNotIn("lastSequence", completion)
        self.assertIn("inFlight !== ticket", completion)
        self.assertIn("fresh(ticket.renderedAtNanos, nowNanos)", completion)
        self.assertNotIn("System.currentTimeMillis", self.sampler)

    def test_replaced_decoder_cannot_publish_failure_to_the_same_surface(self):
        decode = self.session.split("private suspend fun decodeTo(", 1)[1].split("private fun queueDecoderInput(", 1)[0]
        failure = decode.split('reportFailure("Canon H.264 decode failed"', 1)[0].rsplit(
            "catch (exception: Exception)", 1
        )[1]
        self.assertIn("decoderGeneration.get() == generation", failure)
        self.assertIn("surface.get() === targetSurface", failure)

    def test_status_resources_exist_in_both_languages(self):
        required = set(re.findall(r"R.string.(native_(?:monitor|lut)_\w+)", self.ui)) | {"native_lut_preview_contract"}
        for qualifier in ("values", "values-zh-rTW"):
            resources = ET.parse(ROOT / f"android/app/src/main/res/{qualifier}/strings.xml").getroot()
            actual = {node.attrib.get("name") for node in resources}
            self.assertTrue(required <= actual, required - actual)


if __name__ == "__main__":
    unittest.main()
