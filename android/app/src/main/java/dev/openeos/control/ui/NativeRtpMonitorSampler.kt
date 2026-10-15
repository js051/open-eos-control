package dev.openeos.control.ui

import android.os.Handler
import android.os.Looper
import android.view.Surface
import dev.openeos.control.data.NativeFrameEvidence
import dev.openeos.control.data.NativeLiveViewRenderedFrame
import dev.openeos.control.data.NativeLiveViewSession
import dev.openeos.control.data.NativeRtpFrameIdentity
import dev.openeos.control.data.NativeRtpMonitorEvidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal enum class NativeMonitorStatus { OFF, WAITING, SAMPLED, NO_DATA, FAILED, STALE }

internal data class NativeMonitorResult(
    val status: NativeMonitorStatus = NativeMonitorStatus.OFF,
    val analysis: LiveViewMonitorAnalysis? = null,
    val identity: NativeRtpFrameIdentity? = null,
    val evidence: NativeFrameEvidence? = null,
    val sampledAtNanos: Long? = null,
)

// Retiring a screen never opens a second physical analysis slot before its work finishes.
private val nativeAnalysisInFlight = AtomicBoolean(false)

/** Analyzes immutable pixels from the exact GPU draw, never a later asynchronous Surface copy. */
internal class NativeRtpMonitorSampler : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = NativeFrameSamplingGate()
    private val resultState = MutableStateFlow(NativeMonitorResult())
    val result = resultState.asStateFlow()
    private var owner: NativeLiveViewSession? = null
    private var attachedOwner: NativeLiveViewSession? = null
    private var surface: Surface? = null
    private var enabled = false
    private var closed = false
    private var settings = LiveViewMonitorSettings()
    private var rotation = 0f
    private var formatOwner: NativeRtpFrameIdentity? = null
    private var expiry: Runnable? = null

    fun matches(owner: NativeLiveViewSession?, settings: LiveViewMonitorSettings, rotation: Float): Boolean =
        !closed && enabled && this.owner === owner && this.settings == settings && this.rotation == rotation

    fun pause() {
        enabled = false
        owner?.setMonitorSamplingEnabled(false)
        invalidate()
    }

    fun configure(owner: NativeLiveViewSession?, enabled: Boolean, settings: LiveViewMonitorSettings, rotation: Float) {
        if (closed) return
        val active = enabled && owner != null && settings.needsPixelAnalysis
        if (this.owner === owner && this.enabled == active && this.settings == settings && this.rotation == rotation) return
        if (this.owner !== owner) {
            this.owner?.setMonitorSamplingEnabled(false)
            if (attachedOwner !== owner) surface = null
            formatOwner = null
        }
        this.owner = owner
        this.enabled = active
        this.settings = settings
        this.rotation = rotation
        owner?.setMonitorSamplingEnabled(active)
        invalidate()
    }

    fun attach(owner: NativeLiveViewSession, surface: Surface, width: Int, height: Int) {
        if (closed) return
        attachedOwner = owner
        this.surface = surface.takeIf { width > 0 && height > 0 }
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
        if (attachedOwner !== owner || frame.surface !== target || !target.isValid) return
        if (!sameRenderer(formatOwner, frame.identity)) {
            formatOwner = frame.identity
            invalidate(frame.renderedAtNanos)
        }
        val now = System.nanoTime()
        if (!NativeFrameSamplingGate.fresh(frame.renderedAtNanos, now)) { expire(); return }
        val pixels = frame.pixels ?: run {
            if (frame.evidence == NativeFrameEvidence.CODEC_RENDER_CALLBACK) {
                resultState.value = NativeMonitorResult(NativeMonitorStatus.NO_DATA)
            }
            return
        }
        if (!nativeAnalysisInFlight.compareAndSet(false, true)) return
        val ticket = gate.begin(frame.sequence, frame.renderedAtNanos, now)
        if (ticket == null) { nativeAnalysisInFlight.set(false); return }
        val capturedSettings = settings
        scope.launch {
            val analysis = try {
                withContext(Dispatchers.Default) {
                    analyzeLiveViewPixels(pixels.copyPixels(), pixels.width, pixels.height,
                        capturedSettings.zebraThresholdPercent, capturedSettings.focusPeakingEnabled,
                        capturedSettings.falseColorEnabled, capturedSettings.waveformVisible)
                }
            } catch (_: Exception) { null }
            finish(owner, frame, ticket, analysis)
        }
    }

    private fun finish(capturedOwner: NativeLiveViewSession, frame: NativeLiveViewRenderedFrame,
        ticket: NativeFrameSamplingGate.Ticket, analysis: LiveViewMonitorAnalysis?) {
        val owned = gate.owns(ticket)
        val current = owner?.renderedFrames?.value
        val now = System.nanoTime()
        val identity = frame.identity
        val evidence = NativeRtpMonitorEvidence(
            active = enabled && !closed && surface?.isValid == true,
            sameOwner = owner === capturedOwner && current?.surface === surface,
            sameRenderer = sameRenderer(current?.identity, identity),
            foregroundRequired = true, foreground = enabled, terminal = capturedOwner.videoStatus.error != null,
            stopPending = !closed,
            sampleFrame = identity.frameId, currentFrame = frame.identity.frameId,
            sampleLut = identity.lutRevision, currentLut = current?.identity?.lutRevision,
            sampleAgeMs = (now - ticket.startedAtNanos) / 1_000_000.0,
            frameAgeMs = (now - frame.renderedAtNanos) / 1_000_000.0,
            maxAgeMs = NativeFrameSamplingGate.MAX_AGE_NANOS / 1_000_000.0,
            evidence = when (frame.evidence) {
                NativeFrameEvidence.CODEC_RENDER_CALLBACK -> "presentation-confirmed"
                NativeFrameEvidence.EGL_SURFACE_SUBMISSION -> "surface-submitted"
            },
        ).evaluate()
        val accepted = gate.complete(ticket, now) && evidence.canPublishSubmittedAnalysis
        nativeAnalysisInFlight.set(false)
        if (closed) { scope.cancel(); return }
        if (!enabled || !owned) return
        cancelExpiry()
        resultState.value = if (accepted && analysis != null) {
            NativeMonitorResult(NativeMonitorStatus.SAMPLED, analysis, frame.identity, frame.evidence, frame.renderedAtNanos)
        } else NativeMonitorResult(if (accepted) NativeMonitorStatus.FAILED else NativeMonitorStatus.STALE)
        if (accepted && analysis != null) {
            val delayMillis = ((ticket.renderedAtNanos + NativeFrameSamplingGate.MAX_AGE_NANOS - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
            expiry = Runnable { if (gate.owns(ticket)) expire() }.also { handler.postDelayed(it, delayMillis) }
        }
    }

    private fun sameRenderer(first: NativeRtpFrameIdentity?, second: NativeRtpFrameIdentity): Boolean =
        first != null && first.decoderGeneration == second.decoderGeneration && first.formatGeneration == second.formatGeneration &&
            first.renderingGeneration == second.renderingGeneration && first.lutRevision == second.lutRevision

    private fun invalidate(atNanos: Long = System.nanoTime()) {
        gate.invalidate(atNanos)
        cancelExpiry()
        resultState.value = NativeMonitorResult(if (enabled && !closed) NativeMonitorStatus.WAITING else NativeMonitorStatus.OFF)
    }

    private fun cancelExpiry() { expiry?.let(handler::removeCallbacks); expiry = null }
    private fun expire() { cancelExpiry(); resultState.value = NativeMonitorResult(NativeMonitorStatus.STALE) }

    override fun close() {
        owner?.setMonitorSamplingEnabled(false)
        closed = true
        enabled = false
        owner = null
        attachedOwner = null
        surface = null
        invalidate()
        if (!gate.busy) scope.cancel()
    }
}
