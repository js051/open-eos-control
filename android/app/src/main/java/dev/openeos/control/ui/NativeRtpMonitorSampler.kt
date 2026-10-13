package dev.openeos.control.ui

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import dev.openeos.control.data.NativeLiveViewRenderedFrame
import dev.openeos.control.data.NativeLiveViewSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class NativeMonitorStatus { OFF, WAITING, SAMPLED, NO_DATA, FAILED, STALE }

internal data class NativeMonitorResult(
    val status: NativeMonitorStatus = NativeMonitorStatus.OFF,
    val analysis: LiveViewMonitorAnalysis? = null,
)

// Also bounds overlap if a whole Compose screen is disposed/recreated before PixelCopy returns.
private val nativePixelCopyInFlight = AtomicBoolean(false)
private val nativePixelCopyStartedAtNanos = AtomicReference<Long?>(null)

/** Samples only decoder surfaces. Never copies a Window or replaces native video with bitmaps. */
internal class NativeRtpMonitorSampler : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = NativeFrameSamplingGate()
    private val resultState = MutableStateFlow(NativeMonitorResult())
    val result = resultState.asStateFlow()
    private var owner: NativeLiveViewSession? = null
    private var attachedOwner: NativeLiveViewSession? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var enabled = false
    private var closed = false
    private var settings = LiveViewMonitorSettings()
    private var rotation = 0f
    private var formatOwner: Pair<Long, Long>? = null
    private var expiry: Runnable? = null

    fun matches(owner: NativeLiveViewSession?, settings: LiveViewMonitorSettings, rotation: Float): Boolean =
        !closed && enabled && this.owner === owner && this.settings == settings.copy(cubeLut = null) && this.rotation == rotation

    fun pause() {
        enabled = false
        invalidate()
    }

    fun configure(owner: NativeLiveViewSession?, enabled: Boolean, settings: LiveViewMonitorSettings, rotation: Float) {
        if (closed) return
        val active = enabled && owner != null && settings.needsPixelAnalysis
        // LUT selection has no effect on the unprocessed native surface.
        val pixelSettings = settings.copy(cubeLut = null)
        if (this.owner === owner && this.enabled == active && this.settings == pixelSettings && this.rotation == rotation) return
        if (this.owner !== owner) {
            if (attachedOwner !== owner) surface = null
            formatOwner = null
        }
        this.owner = owner
        this.enabled = active
        this.settings = pixelSettings
        this.rotation = rotation
        invalidate()
    }

    fun attach(owner: NativeLiveViewSession, surface: Surface, width: Int, height: Int) {
        if (closed) return
        // surfaceChanged also marks buffer/layout replacement when the Surface wrapper is reused.
        attachedOwner = owner
        this.surface = surface
        this.width = width
        this.height = height
        formatOwner = null
        invalidate()
    }

    fun detach(owner: NativeLiveViewSession, surface: Surface) {
        if (attachedOwner !== owner || this.surface !== surface) return
        attachedOwner = null
        this.surface = null
        formatOwner = null
        invalidate()
    }

    fun onFrame(owner: NativeLiveViewSession, frame: NativeLiveViewRenderedFrame?) {
        if (closed || this.owner !== owner || !enabled) return
        if (frame == null) {
            formatOwner = null
            invalidate()
            return
        }
        val target = surface ?: return
        if (attachedOwner !== owner || frame.surface !== target || !target.isValid || width <= 0 || height <= 0) return
        val currentFormat = frame.decoderGeneration to frame.formatGeneration
        if (formatOwner != currentFormat) {
            formatOwner = currentFormat
            invalidate()
        }
        val now = System.nanoTime()
        if (!NativeFrameSamplingGate.fresh(frame.renderedAtNanos, now)) {
            expire()
            return
        }
        if (nativePixelCopyStartedAtNanos.get()?.let { now - it < NativeFrameSamplingGate.INTERVAL_NANOS } == true) return
        if (!nativePixelCopyInFlight.compareAndSet(false, true)) return
        val ticket = gate.begin(frame.sequence, frame.renderedAtNanos, now)
        if (ticket == null) {
            nativePixelCopyInFlight.set(false)
            return
        }
        nativePixelCopyStartedAtNanos.set(now)
        val capturedSettings = settings
        val (sampleWidth, sampleHeight) = analysisDimensions(width, height)
        val bitmap = try {
            Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
        } catch (_: RuntimeException) {
            finish(ticket, NativeMonitorStatus.FAILED, null)
            return
        }
        try {
            PixelCopy.request(target, bitmap, { status ->
                if (closed || !gate.owns(ticket) || status != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    finish(ticket, if (status == PixelCopy.ERROR_SOURCE_NO_DATA) NativeMonitorStatus.NO_DATA else NativeMonitorStatus.FAILED, null)
                } else {
                    // Physical ownership lasts through analysis; no cancellation can open a second slot.
                    scope.launch {
                        val analysis = try {
                            withContext(Dispatchers.Default) {
                                val pixels = IntArray(sampleWidth * sampleHeight)
                                bitmap.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
                                analyzeLiveViewPixels(
                                    pixels, sampleWidth, sampleHeight,
                                    capturedSettings.zebraThresholdPercent,
                                    capturedSettings.focusPeakingEnabled,
                                    capturedSettings.falseColorEnabled,
                                    capturedSettings.waveformVisible,
                                )
                            }
                        } catch (_: Exception) {
                            null
                        } finally {
                            bitmap.recycle()
                        }
                        finish(ticket, if (analysis == null) NativeMonitorStatus.FAILED else NativeMonitorStatus.SAMPLED, analysis)
                    }
                }
            }, handler)
        } catch (_: RuntimeException) {
            bitmap.recycle()
            finish(ticket, NativeMonitorStatus.FAILED, null)
        }
    }

    private fun finish(ticket: NativeFrameSamplingGate.Ticket, status: NativeMonitorStatus, analysis: LiveViewMonitorAnalysis?) {
        val owned = gate.owns(ticket)
        val currentFrame = owner?.renderedFrames?.value
        val accepted = gate.complete(ticket, System.nanoTime()) && surface?.isValid == true &&
            currentFrame?.surface === surface &&
            currentFrame?.let { it.decoderGeneration to it.formatGeneration } == formatOwner
        nativePixelCopyInFlight.set(false)
        if (closed) {
            scope.cancel()
            return
        }
        if (!enabled || !owned) return
        cancelExpiry()
        resultState.value = if (accepted) NativeMonitorResult(status, analysis) else NativeMonitorResult(NativeMonitorStatus.STALE)
        if (accepted && analysis != null) {
            // One-shot freshness expiry; this never requests another capture.
            val delayMillis = ((ticket.renderedAtNanos + NativeFrameSamplingGate.MAX_AGE_NANOS - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
            expiry = Runnable { if (gate.owns(ticket)) expire() }.also { handler.postDelayed(it, delayMillis) }
        }
    }

    private fun invalidate() {
        gate.invalidate(System.nanoTime())
        cancelExpiry()
        resultState.value = NativeMonitorResult(if (enabled && !closed) NativeMonitorStatus.WAITING else NativeMonitorStatus.OFF)
    }

    private fun cancelExpiry() {
        expiry?.let(handler::removeCallbacks)
        expiry = null
    }

    private fun expire() {
        cancelExpiry()
        resultState.value = NativeMonitorResult(NativeMonitorStatus.STALE)
    }

    override fun close() {
        closed = true
        enabled = false
        owner = null
        attachedOwner = null
        surface = null
        invalidate()
        // Do not cancel work holding PixelCopy's destination. Its callback/finally owns release.
        if (!gate.busy) scope.cancel()
    }
}
