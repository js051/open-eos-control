package dev.openeos.control.data

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Progress from a native API, with optional pixels captured from that exact GPU frame. */
data class NativeLiveViewRenderedFrame(
    val surface: Surface,
    val decoderGeneration: Long,
    val formatGeneration: Long,
    val sequence: Long,
    val renderedAtNanos: Long,
    val identity: NativeRtpFrameIdentity = NativeRtpFrameIdentity(decoderGeneration, formatGeneration, 0, 0, sequence),
    val evidence: NativeFrameEvidence = NativeFrameEvidence.CODEC_RENDER_CALLBACK,
    val pixels: NativeRtpFramePixels? = null,
)

private val noRenderedFrames = MutableStateFlow<NativeLiveViewRenderedFrame?>(null)
private val noVideoLut = MutableStateFlow(NativeVideoLutStatus.OFF)

sealed interface NativeLiveViewEvent {
    data class FrameRendered(
        val encodedBytes: Int,
        val atMillis: Long,
        val width: Int,
        val height: Int,
        val evidence: NativeFrameEvidence = NativeFrameEvidence.CODEC_RENDER_CALLBACK,
        val frameId: Long? = null,
    ) : NativeLiveViewEvent

    data class VideoSizeChanged(
        val width: Int,
        val height: Int,
    ) : NativeLiveViewEvent

    data class AudioStatusChanged(
        val status: NativeLiveViewAudioStatus,
    ) : NativeLiveViewEvent

    data class Failed(
        val message: String,
    ) : NativeLiveViewEvent
}

data class NativeLiveViewAudioStatus(
    val advertised: Boolean = false,
    val available: Boolean = false,
    val enabled: Boolean = false,
    val codec: String? = null,
    val rtpPort: Int? = null,
    val rtpClockRate: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val packetsReceived: Long = 0,
    val accessUnitsReceived: Long = 0,
    val decodedAccessUnits: Long = 0,
    val playedSampleFrames: Long = 0,
    val droppedAccessUnits: Long = 0,
    val underruns: Int = 0,
    val lastPacketAtMillis: Long? = null,
    val lastPcmAtMillis: Long? = null,
    val error: String? = null,
) {
    companion object {
        val None = NativeLiveViewAudioStatus()
    }
}

data class NativeLiveViewVideoStatus(
    val rtpPort: Int? = null,
    val datagramsReceived: Long = 0,
    val accessUnitsReceived: Long = 0,
    val keyFramesReceived: Long = 0,
    val lastDatagramAtMillis: Long? = null,
    val lastAccessUnitAtMillis: Long? = null,
    val hasSequenceParameterSet: Boolean = false,
    val hasPictureParameterSet: Boolean = false,
    val ready: Boolean = false,
    val decodedFrames: Long = 0,
    val presentationFrames: Long = 0,
    val lastDecodedAtMillis: Long? = null,
    val lastPresentationAtMillis: Long? = null,
    val error: String? = null,
) {
    companion object {
        val None = NativeLiveViewVideoStatus()
    }
}

interface NativeLiveViewSession : AutoCloseable {
    val source: LiveViewSource
    val sourceUrl: String
    val contentType: String
    val audioStatus: NativeLiveViewAudioStatus
        get() = NativeLiveViewAudioStatus.None
    val videoStatus: NativeLiveViewVideoStatus
        get() = NativeLiveViewVideoStatus.None
    val renderedFrames: StateFlow<NativeLiveViewRenderedFrame?>
        get() = noRenderedFrames

    val supportsVideoLut: Boolean get() = false
    val videoLutStatus: StateFlow<NativeVideoLutStatus> get() = noVideoLut
    fun setVideoLut(lut: NativeVideoLut?) = Unit
    fun setMonitorSamplingEnabled(enabled: Boolean) = Unit

    fun start()
    /** Completes only after real decoded output. ACKs, SPS/PPS/IDR and queue intent are insufficient. */
    suspend fun awaitReady(timeoutMillis: Long)
    fun attachSurface(surface: Surface)
    fun detachSurface(surface: Surface)
    fun setTargetFps(fps: Int)
    fun setRenderingEnabled(enabled: Boolean)
    fun setAudioEnabled(enabled: Boolean) = Unit
    fun setListener(listener: ((NativeLiveViewEvent) -> Unit)?)
    override fun close()
}

fun interface CcapiRtpSessionFactory {
    fun create(description: CcapiRtpSessionDescription, destinationAddress: String): NativeLiveViewSession
}
