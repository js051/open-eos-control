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
        self.assertNotIn("PixelCopy.request(", self.sampler)
        self.assertIn("frame.surface !== target", self.sampler)
        self.assertIn("val pixels = frame.pixels ?: run", self.sampler)
        self.assertIn("analyzeLiveViewPixels(pixels.copyPixels(), pixels.width, pixels.height", self.sampler)
        gpu = (JAVA / "data/NativeRtpLutRenderer.kt").read_text()
        pixels = (JAVA / "data/NativeRtpFrameProgress.kt").read_text()
        self.assertIn("minOf(1.0, 320.0 / maxOf(width, height))", gpu)
        self.assertIn("GLES30.GL_RGBA8", gpu)
        self.assertIn("GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)", gpu)
        self.assertLess(gpu.index("readFramePixels(width[0], height[0])"), gpu.index("check(EGL14.eglSwapBuffers"))
        self.assertIn("presented(presentationTimeUs, sample)", gpu)
        self.assertIn("require(width in 1..320 && height in 1..320 && argb.size == width * height)", pixels)
        self.assertIn("fun copyPixels(): IntArray = argb.copyOf()", pixels)
        self.assertIn("nativeLutColorSupported", self.session)
        self.assertIn("NativeVideoLutStatus.UNSUPPORTED_COLOR", self.session)
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
        self.assertNotIn("nativeAnalysisInFlight.set(false)", close)
        self.assertIn("if (!gate.busy) scope.cancel()", close)
        self.assertIn("private val nativeAnalysisInFlight = AtomicBoolean(false)", self.sampler)
        self.assertIn("nativeAnalysisInFlight.compareAndSet(false, true)", self.sampler)
        self.assertIn("if (ticket == null) { nativeAnalysisInFlight.set(false); return }", self.sampler)
        finish = self.sampler.split("private fun finish(", 1)[1].split("private fun sameRenderer", 1)[0]
        self.assertLess(finish.index("gate.complete(ticket, now)"), finish.index("nativeAnalysisInFlight.set(false)"))
        self.assertNotIn("lastStartedAt =", invalidate)
        gpu = (JAVA / "data/NativeRtpLutRenderer.kt").read_text()
        self.assertIn("sampleEnabled() && now - lastSampleNanos >= 250_000_000L", gpu)

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
        self.assertIn("val capturedSettings = settings", self.sampler)
        self.assertLess(self.sampler.index("val capturedSettings = settings"), self.sampler.index("scope.launch {"))
        for field in ("zebraThresholdPercent", "focusPeakingEnabled", "falseColorEnabled", "waveformVisible"):
            self.assertIn("capturedSettings." + field, self.sampler)
        self.assertIn("sameOwner = owner === capturedOwner && current?.surface === surface", self.sampler)
        self.assertIn("sameRenderer = sameRenderer(current?.identity, identity)", self.sampler)
        self.assertIn("sampleLut = identity.lutRevision, currentLut = current?.identity?.lutRevision", self.sampler)
        self.assertIn("setVideoLut(nativeLut)", self.ui)
        self.assertIn("pixels.copyPixels()", self.sampler)
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
