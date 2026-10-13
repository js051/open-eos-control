package dev.openeos.control.data

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A render notification is a freshness signal, not the identity of later PixelCopy pixels. */
data class NativeLiveViewRenderedFrame(
    val surface: Surface,
    val decoderGeneration: Long,
    val formatGeneration: Long,
    val sequence: Long,
    val renderedAtNanos: Long,
)

private val noRenderedFrames = MutableStateFlow<NativeLiveViewRenderedFrame?>(null)

sealed interface NativeLiveViewEvent {
    data class FrameRendered(
        val encodedBytes: Int,
        val atMillis: Long,
        val width: Int,
        val height: Int,
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

    fun start()
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
