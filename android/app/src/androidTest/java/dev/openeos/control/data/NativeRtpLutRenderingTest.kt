package dev.openeos.control.data

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.ui.CubeLut
import dev.openeos.control.ui.LiveViewMonitorSettings
import dev.openeos.control.ui.NativeRtpMonitorSampler
import dev.openeos.control.ui.analysisDimensions
import dev.openeos.control.ui.analyzeLiveViewPixels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real decoder/GLES/Surface/PixelCopy evidence; no camera or native API is mocked. */
@RunWith(AndroidJUnit4::class)
class NativeRtpLutRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fixture = Base64.getDecoder().decode("AAAAAWdkEAqsuI9NQICAUAAAAwAQAAADACBAAAAAAWjuASyyLAAAAAFliIQ6/vOwfmWVcMjc9396sU68Xq2KSZ/Z1yk56ylhRBv1U+mU30I0Mj4UmV3uYf7yU5xO2A99Sa1rES0kusaJZgSJBlnVOP5+YjUvqLBSBdOb96m0lFYjjsqbyoKCtoK2/Ui3FUTivt/uYo03ooGWdmlKBYZOq6rTjaSQ+vk=")
    private data class Target(val surface: Surface, val width: Int, val height: Int)

    @Test fun realH264ToGpuSurfacePreservesRowsAndPostLutScopes() = runBlocking {
        val ready = CompletableFuture<Target>()
        compose.setContent {
            AndroidView(factory = { context -> SurfaceView(context).also { view ->
                view.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        ready.complete(Target(holder.surface, width, height))
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                })
            } }, modifier = Modifier.size(128.dp, 96.dp))
        }
        val target = ready.get(8, TimeUnit.SECONDS)
        val identity = render(target, swap = false)
        val swapped = render(target, swap = true)
        val curvedLut = NativeVideoLut(2, floatArrayOf(-.1f, .05f, -.2f), floatArrayOf(.9f, .85f, 1.1f),
            (0..1).flatMap { b -> (0..1).flatMap { g -> (0..1).flatMap { r ->
                listOf(r * g * 1.6f - .2f, g * b * 1.2f, b * r * 1.4f)
            } } }.toFloatArray())
        val curved = render(target, swap = false, overrideLut = curvedLut)
        val expected = arrayOf(intArrayOf(224, 40, 56), intArrayOf(48, 200, 72),
            intArrayOf(40, 80, 216), intArrayOf(176, 144, 104))
        val positions = arrayOf(16 to 12, 48 to 12, 16 to 36, 48 to 36)
        try {
            for ((index, point) in positions.withIndex()) {
                val before = channels(identity.getPixel(point.first, point.second))
                val after = channels(swapped.getPixel(point.first, point.second))
                val transformed = channels(curved.getPixel(point.first, point.second))
                val numeric = curvedLut.sample(before[0] / 255f, before[1] / 255f, before[2] / 255f)
                for (channel in 0..2) {
                    assertTrue("Decoder SDR conversion or quadrant orientation differs", kotlin.math.abs(before[channel] - expected[index][channel]) <= 18)
                    assertTrue("LUT must swap red/blue once in native video", kotlin.math.abs(after[channel] - before[2 - channel]) <= 3)
                    assertTrue("Real GPU domain/interpolation must match the numeric oracle",
                        kotlin.math.abs(transformed[channel] - numeric[channel] * 255f) <= 6f)
                }
            }
        } finally { identity.recycle(); swapped.recycle(); curved.recycle() }
    }

    private fun verifyPostLutScopes(target: Target) {
        // Only notification timing is controlled; every pixel is the actual native GPU output.
        val session = ObservedSurfaceSession(target.surface)
        val lut = CubeLut("Synthetic swap", 2, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), table(true))
        val cases = listOf(
            LiveViewMonitorSettings(histogramVisible = true, cubeLut = lut),
            LiveViewMonitorSettings(waveformVisible = true, cubeLut = lut),
            LiveViewMonitorSettings(zebraThresholdPercent = 70, cubeLut = lut),
            LiveViewMonitorSettings(falseColorEnabled = true, cubeLut = lut),
            LiveViewMonitorSettings(focusPeakingEnabled = true, cubeLut = lut),
        )
        lateinit var sampler: NativeRtpMonitorSampler
        onMain { sampler = NativeRtpMonitorSampler(); sampler.attach(session, target.surface, target.width, target.height) }
        try {
            for ((sequence, settings) in cases.withIndex()) {
                Thread.sleep(270) // Respect the production global 4 Hz cadence, including earlier tests.
                onMain {
                    sampler.configure(session, true, settings, 0f)
                    // A newly observed format retires its first notification; a subsequent one is fresh.
                    repeat(2) { index ->
                        session.renderedFrames.value = NativeLiveViewRenderedFrame(target.surface, 1, 1,
                            sequence * 2L + index + 2L, System.nanoTime())
                        sampler.onFrame(session, session.renderedFrames.value)
                    }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (sampler.result.value.analysis == null && System.nanoTime() < deadline) Thread.sleep(10)
                val actual = requireNotNull(sampler.result.value.analysis) { "Real post-LUT Surface did not reach the bounded sampler" }
                val dimensions = analysisDimensions(target.width, target.height)
                val sample = copy(target.surface, dimensions.first, dimensions.second)
                try {
                    val pixels = IntArray(sample.width * sample.height)
                    sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                    val expected = analyzeLiveViewPixels(pixels, sample.width, sample.height,
                        settings.zebraThresholdPercent, settings.focusPeakingEnabled, settings.falseColorEnabled, settings.waveformVisible)
                    assertArrayEquals(expected.histogram, actual.histogram)
                    assertEquals(expected.waveform == null, actual.waveform == null)
                    expected.waveform?.let { assertArrayEquals(it.density, requireNotNull(actual.waveform).density) }
                    assertEquals(expected.overlayPixels == null, actual.overlayPixels == null)
                    expected.overlayPixels?.let { assertArrayEquals(it, requireNotNull(actual.overlayPixels)) }
                } finally { sample.recycle() }
            }
            onMain { sampler.pause() }
            assertNull(sampler.result.value.analysis)
        } finally { onMain { sampler.close() } }
    }

    private suspend fun render(target: Target, swap: Boolean, overrideLut: NativeVideoLut? = null): Bitmap {
        val completed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val lut = overrideLut ?: NativeVideoLut(2, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), table(swap))
        val renderer = NativeRtpLutRenderer(target.surface, lut, { true }, { completed.countDown() }, { failed.countDown() })
        var codec: MediaCodec? = null
        try {
            val inputSurface = renderer.inputSurface()
            codec = MediaCodec.createDecoderByType("video/avc")
            codec.configure(MediaFormat.createVideoFormat("video/avc", 64, 48), inputSurface, null, 0)
            codec.start()
            val index = codec.dequeueInputBuffer(TimeUnit.SECONDS.toMicros(2))
            assertTrue(index >= 0)
            requireNotNull(codec.getInputBuffer(index)).put(fixture)
            codec.queueInputBuffer(index, 0, fixture.size, 1_000, 0)
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
            while (completed.count > 0 && failed.count > 0 && System.nanoTime() < deadline) {
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) codec.releaseOutputBuffer(output, true)
            }
            assertEquals("Real GPU pipeline failed", 1L, failed.count)
            assertEquals("Decoder/GPU did not present fixture", 0L, completed.count)
            if (swap && overrideLut == null) verifyPostLutScopes(target)
            return copy(target.surface, 64, 48)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            renderer.close()
        }
    }

    private fun copy(surface: Surface, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val done = CompletableFuture<Int>()
        PixelCopy.request(surface, bitmap, { done.complete(it) }, Handler(Looper.getMainLooper()))
        assertEquals(PixelCopy.SUCCESS, done.get(4, TimeUnit.SECONDS).toInt())
        return bitmap
    }

    private fun table(swap: Boolean): FloatArray = (0..1).flatMap { b -> (0..1).flatMap { g -> (0..1).flatMap { r ->
        if (swap) listOf(b.toFloat(), g.toFloat(), r.toFloat()) else listOf(r.toFloat(), g.toFloat(), b.toFloat())
    } } }.toFloatArray()
    private fun channels(pixel: Int) = intArrayOf(pixel shr 16 and 255, pixel shr 8 and 255, pixel and 255)
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** Only the notification owner is controlled. Pixel source is the actual decoded/GPU Surface. */
    private class ObservedSurfaceSession(surface: Surface) : NativeLiveViewSession {
        override val source = LiveViewSource.CCAPI_RTP
        override val sourceUrl = "rtp://synthetic.invalid/fixture"
        override val contentType = "video/H264"
        override val renderedFrames = MutableStateFlow<NativeLiveViewRenderedFrame?>(
            NativeLiveViewRenderedFrame(surface, 1, 1, 1, System.nanoTime()))
        override fun start() = Unit
        override suspend fun awaitReady(timeoutMillis: Long) = Unit
        override fun attachSurface(surface: Surface) = Unit
        override fun detachSurface(surface: Surface) = Unit
        override fun setTargetFps(fps: Int) = Unit
        override fun setRenderingEnabled(enabled: Boolean) = Unit
        override fun setListener(listener: ((NativeLiveViewEvent) -> Unit)?) = Unit
        override fun close() = Unit
    }
}
