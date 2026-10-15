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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real decoder/GLES/Surface/PixelCopy evidence; no camera or native API is mocked. */
@RunWith(AndroidJUnit4::class)
class NativeRtpLutRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    // Synthetic 8-bit limited-range BT.709: VUI primaries/transfer/matrix are ALL 1.
    // A matrix-only tag leaves SD-size primaries to the decoder's BT.601 default,
    // producing a mixed color standard and a different SurfaceTexture conversion.
    private val fixture = Base64.getDecoder().decode("AAAAAWdkEAqsuI9NQEBAUAAAAwAQAAADACBAAAAAAWjuASyyLAAAAAFliIQ6/vOwfmWVcMjc9396sU68Xq2KSZ/Z1yk56ylhRBv1U+mU30I0Mj4UmV3uYf7yU5xO2A99Sa1rES0kusaJZgSJBlnVOP5+YjUvqLBSBdOb96m0lFYjjsqbyoKCtoK2/Ui3FUTivt/uYo03ooGWdmlKBYZOq6rTjaSQ+vk=")
    private data class Target(val surface: Surface, val width: Int, val height: Int)
    private val positions = arrayOf(16 to 12, 48 to 12, 16 to 36, 48 to 36)

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
        val evidence = mutableListOf<String>() // At most three fixed-size, synthetic frame summaries.
        val captured = mutableListOf<Bitmap>()
        try {
            val identity = render(target, "identity", evidence, swap = false).also(captured::add)
            val swapped = render(target, "swap", evidence, swap = true).also(captured::add)
            val curvedLut = NativeVideoLut(2, floatArrayOf(-.1f, .05f, -.2f), floatArrayOf(.9f, .85f, 1.1f),
                (0..1).flatMap { b -> (0..1).flatMap { g -> (0..1).flatMap { r ->
                    listOf(r * g * 1.6f - .2f, g * b * 1.2f, b * r * 1.4f)
                } } }.toFloatArray())
            val curved = render(target, "curved", evidence, swap = false, overrideLut = curvedLut).also(captured::add)
            val expected = arrayOf(intArrayOf(224, 40, 56), intArrayOf(48, 200, 72),
                intArrayOf(40, 80, 216), intArrayOf(176, 144, 104))
            for ((index, point) in positions.withIndex()) {
                val before = channels(identity.getPixel(point.first, point.second))
                val after = channels(swapped.getPixel(point.first, point.second))
                val transformed = channels(curved.getPixel(point.first, point.second))
                val numeric = curvedLut.sample(before[0] / 255f, before[1] / 255f, before[2] / 255f)
                for (channel in 0..2) {
                    val location = "quadrant=$index xy=${point.first},${point.second} channel=$channel"
                    assertTrue("Decoder SDR conversion or quadrant orientation differs: $location " +
                        "actual=${before[channel]} expected=${expected[index][channel]} tolerance=18",
                        kotlin.math.abs(before[channel] - expected[index][channel]) <= 18)
                    assertTrue("LUT must swap red/blue once in native video: $location " +
                        "actual=${after[channel]} expected=${before[2 - channel]} tolerance=3",
                        kotlin.math.abs(after[channel] - before[2 - channel]) <= 3)
                    assertTrue("Real GPU domain/interpolation must match the numeric oracle: $location " +
                        "actual=${transformed[channel]} expected=${numeric[channel] * 255f} tolerance=6",
                        kotlin.math.abs(transformed[channel] - numeric[channel] * 255f) <= 6f)
                }
            }
        } catch (failure: Throwable) {
            // Keep the original assertion/cause and include actual pixels in the CI failure line.
            // No screenshots, device identifiers, user frames, or unbounded MediaFormat dumps.
            throw AssertionError("${failure.message}; NATIVE_RTP_LUT ${evidence.joinToString(" | ")}", failure)
        } finally {
            // Earlier captures must also retire if a later decoder/copy/scope phase fails.
            captured.forEach(Bitmap::recycle)
        }
    }

    private fun verifyPostLutScopes(target: Target, nextFrame: () -> NativeLiveViewRenderedFrame, onCase: (Int) -> Unit) {
        // Every case receives a new actual decoder/GPU draw, its original identity and timestamp.
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
                onCase(sequence)
                Thread.sleep(270) // Respect the production global 4 Hz cadence, including earlier tests.
                onMain { sampler.configure(session, true, settings, 0f) }
                val frame = nextFrame()
                val exactPixels = requireNotNull(frame.pixels)
                onMain {
                    session.renderedFrames.value = frame
                    sampler.onFrame(session, frame)
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (sampler.result.value.analysis == null && System.nanoTime() < deadline) Thread.sleep(10)
                val actual = requireNotNull(sampler.result.value.analysis) { "Real post-LUT Surface did not reach the bounded sampler" }
                val expected = analyzeLiveViewPixels(exactPixels.copyPixels(), exactPixels.width, exactPixels.height,
                    settings.zebraThresholdPercent, settings.focusPeakingEnabled, settings.falseColorEnabled, settings.waveformVisible)
                assertArrayEquals(expected.histogram, actual.histogram)
                assertEquals(expected.waveform == null, actual.waveform == null)
                expected.waveform?.let { assertArrayEquals(it.density, requireNotNull(actual.waveform).density) }
                assertEquals(expected.overlayPixels == null, actual.overlayPixels == null)
                expected.overlayPixels?.let { assertArrayEquals(it, requireNotNull(actual.overlayPixels)) }
            }
            onMain { sampler.pause() }
            assertNull(sampler.result.value.analysis)
        } finally { onMain { sampler.close() } }
    }

    private suspend fun render(
        target: Target,
        label: String,
        evidence: MutableList<String>,
        swap: Boolean,
        overrideLut: NativeVideoLut? = null,
    ): Bitmap {
        val completed = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val presentedTimeUs = AtomicLong(Long.MIN_VALUE)
        val submittedFrame = AtomicReference<NativeLiveViewRenderedFrame?>()
        val lut = overrideLut ?: NativeVideoLut(2, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), table(swap))
        val renderer = NativeRtpLutRenderer(target.surface, lut, { true }, { timeUs, pixels ->
            submittedFrame.set(NativeLiveViewRenderedFrame(target.surface, 1, 1, timeUs, System.nanoTime(),
                evidence = NativeFrameEvidence.EGL_SURFACE_SUBMISSION, pixels = pixels))
            presentedTimeUs.set(timeUs)
            completed.countDown()
        }, { failed.countDown() }, { true })
        var codec: MediaCodec? = null
        var phase = "input-surface"
        var decoder = "unavailable"
        var outputFormat = "unobserved"
        var outputCount = 0
        var lastOutputTimeUs = Long.MIN_VALUE
        var pixels = "uncopied"
        var captured: Bitmap? = null
        var primaryFailure: Throwable? = null
        try {
            val inputSurface = renderer.inputSurface()
            phase = "decoder-create"
            codec = MediaCodec.createDecoderByType("video/avc")
            decoder = codec.name.take(96)
            phase = "decoder-configure"
            codec.configure(MediaFormat.createVideoFormat("video/avc", 64, 48), inputSurface, null, 0)
            phase = "decoder-start"
            codec.start()
            phase = "input-dequeue"
            val index = codec.dequeueInputBuffer(TimeUnit.SECONDS.toMicros(2))
            assertTrue(index >= 0)
            requireNotNull(codec.getInputBuffer(index)).put(fixture)
            codec.queueInputBuffer(index, 0, fixture.size, 1_000, 0)
            phase = "decode-present"
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
            while (completed.count > 0 && failed.count > 0 && System.nanoTime() < deadline) {
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    outputFormat = describeFormat(format)
                    assertFixtureColorFormat(format)
                }
                if (output >= 0) {
                    outputCount++
                    lastOutputTimeUs = info.presentationTimeUs
                    codec.releaseOutputBuffer(output, true)
                }
            }
            assertEquals("Real GPU pipeline failed", 1L, failed.count)
            assertEquals("Decoder/GPU did not present fixture", 0L, completed.count)
            assertTrue("Decoder must report the fixture's color format", outputFormat != "unobserved")
            if (swap && overrideLut == null) {
                var nextTimeUs = 2_000L
                val activeCodec = requireNotNull(codec)
                verifyPostLutScopes(target, nextFrame = {
                    val expectedTimeUs = nextTimeUs++
                    val inputIndex = activeCodec.dequeueInputBuffer(TimeUnit.SECONDS.toMicros(2))
                    check(inputIndex >= 0)
                    requireNotNull(activeCodec.getInputBuffer(inputIndex)).put(fixture)
                    activeCodec.queueInputBuffer(inputIndex, 0, fixture.size, expectedTimeUs, 0)
                    val nextDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                    while (submittedFrame.get()?.sequence != expectedTimeUs && System.nanoTime() < nextDeadline) {
                        val output = activeCodec.dequeueOutputBuffer(info, 10_000)
                        if (output >= 0) activeCodec.releaseOutputBuffer(output, true)
                    }
                    requireNotNull(submittedFrame.get()).also { check(it.sequence == expectedTimeUs && it.pixels != null) }
                }) { phase = "post-lut-scopes-$it" }
            }
            phase = "pixel-copy"
            return copy(target.surface, 64, 48).also { bitmap ->
                captured = bitmap
                pixels = positions.joinToString(";") { (x, y) -> "$x,$y:${channels(bitmap.getPixel(x, y)).joinToString(",")}" }
                phase = "captured"
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val stopped = runCatching { codec?.stop() }
            val released = runCatching { codec?.release() }
            val retired = runCatching { renderer.close() }
            if (primaryFailure != null || retired.isFailure) captured?.recycle()
            fun Result<*>.outcome() = exceptionOrNull()?.javaClass?.simpleName ?: "ok"
            evidence += "$label phase=$phase target=${target.width}x${target.height} decoder=$decoder " +
                "format=[$outputFormat] outputs=$outputCount outputPts=$lastOutputTimeUs " +
                "presentedPts=${presentedTimeUs.get()} failed=${failed.count == 0L} rgb=[$pixels] " +
                "cleanup=${stopped.outcome()}/${released.outcome()}/${retired.outcome()}"
            retired.exceptionOrNull()?.let { cleanupFailure ->
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private fun assertFixtureColorFormat(format: MediaFormat) {
        for ((key, expected) in listOf(
            MediaFormat.KEY_COLOR_STANDARD to MediaFormat.COLOR_STANDARD_BT709,
            MediaFormat.KEY_COLOR_RANGE to MediaFormat.COLOR_RANGE_LIMITED,
            MediaFormat.KEY_COLOR_TRANSFER to MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        )) {
            assertTrue("Explicit BT.709 fixture is missing $key", format.containsKey(key))
            assertEquals("Explicit BT.709 fixture has unexpected $key", expected, format.getInteger(key))
        }
    }

    /** Only fixed numeric keys from our synthetic stream; never codec/private/vendor metadata. */
    private fun describeFormat(format: MediaFormat): String = listOf(
        MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, "crop-left", "crop-top", "crop-right", "crop-bottom",
        MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER, "bit-depth",
    ).joinToString(",") { key ->
        "$key=${if (format.containsKey(key)) runCatching { format.getInteger(key).toString() }.getOrDefault("non-integer") else "absent"}"
    }

    private fun copy(surface: Surface, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val done = CompletableFuture<Int>()
        PixelCopy.request(surface, bitmap, { done.complete(it) }, Handler(Looper.getMainLooper()))
        assertEquals("PixelCopy ${width}x$height status", PixelCopy.SUCCESS, done.get(4, TimeUnit.SECONDS).toInt())
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
