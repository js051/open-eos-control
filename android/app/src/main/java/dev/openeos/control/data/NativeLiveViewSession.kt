package dev.openeos.control.data

import android.view.Surface

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

    data class Failed(
        val message: String,
    ) : NativeLiveViewEvent
}

interface NativeLiveViewSession : AutoCloseable {
    val source: LiveViewSource
    val sourceUrl: String
    val contentType: String

    fun start()
    fun attachSurface(surface: Surface)
    fun detachSurface(surface: Surface)
    fun setTargetFps(fps: Int)
    fun setRenderingEnabled(enabled: Boolean)
    fun setListener(listener: ((NativeLiveViewEvent) -> Unit)?)
    override fun close()
}

fun interface CcapiRtpSessionFactory {
    fun create(description: CcapiRtpSessionDescription, destinationAddress: String): NativeLiveViewSession
}
