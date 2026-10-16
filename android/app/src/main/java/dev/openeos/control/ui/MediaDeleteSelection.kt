package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraTransport

/** All values are captured when the permanent-delete confirmation opens. */
class MediaDeleteSelection(items: List<CameraMediaItem>, val owner: MediaDeleteOwner, transport: CameraTransport?,
    selectionTokens: Map<String, String> = emptyMap(), val hiddenCount: Int = 0, val unloadedCount: Int = 0, bridgeEngine: String? = null, bridgeEndpoint: String? = null,
) : AbstractList<CameraMediaItem>() {
    init { mediaDeleteRoster(items.map(CameraMediaItem::id)) }
    private val snapshot = java.util.Collections.unmodifiableList(items.distinctBy(CameraMediaItem::id).toList())
    val attemptId: String = java.util.UUID.randomUUID().toString()
    private var consumed = false
    @Synchronized fun claimConfirmation(connection: Any?, generation: Long, busy: Boolean): Boolean {
        if (!canAdmitMediaDeletion(owner, connection, generation, confirmed = true, consumed = consumed, busy = busy)) return false
        consumed = true
        return true
    }
    val requests: List<MediaDeleteRequest> = java.util.Collections.unmodifiableList(snapshot.map { MediaDeleteRequest(it.id, it.name, mediaDeleteSource(it.id, transport, bridgeEngine, bridgeEndpoint),
        it.protected, it.deleteWritable, selectionTokens[it.id]) })
    override val size: Int get() = snapshot.size
    override fun get(index: Int): CameraMediaItem = snapshot[index]
}

/** UUID epochs remain unique when the media composition leaves and re-enters under the same owner. */
internal fun newMediaDeleteSelectionToken(): String = java.util.UUID.randomUUID().toString()

internal fun mediaDeleteSource(id: String, transport: CameraTransport?, bridgeEngine: String? = null, bridgeEndpoint: String? = null): MediaDeleteSource = when {
    id.isEmpty() || transport == null -> MediaDeleteSource.UNKNOWN
    transport == CameraTransport.DESKTOP_BRIDGE -> when {
        bridgeEndpoint == null || dev.openeos.control.data.mediaDeleteEndpoint(bridgeEndpoint) != bridgeEndpoint -> MediaDeleteSource.UNKNOWN
        bridgeEngine == "libgphoto2" && Regex("gphoto2-host:[A-Za-z0-9_-]+").matches(id) -> MediaDeleteSource.DESKTOP_HOST
        bridgeEngine == "libgphoto2" && Regex("gphoto2:[A-Za-z0-9_-]+").matches(id) -> MediaDeleteSource.CAMERA_CARD
        bridgeEngine == "ccapi" && Regex("ccapi:[A-Za-z0-9_-]+").matches(id) -> MediaDeleteSource.CAMERA_CARD
        else -> MediaDeleteSource.UNKNOWN
    }
    id.startsWith("usb-host:") -> if (transport == CameraTransport.USB_PTP && id.length > 9) MediaDeleteSource.ANDROID_PRIVATE else MediaDeleteSource.UNKNOWN
    id.startsWith("gphoto2-host:") || id.startsWith("gphoto2:") || id.startsWith("ccapi:") -> MediaDeleteSource.UNKNOWN
    transport == CameraTransport.USB_PTP -> if (runCatching { dev.openeos.control.data.mediaDeletePtpHandle(id) }.isSuccess)
        MediaDeleteSource.CAMERA_CARD else MediaDeleteSource.UNKNOWN
    else -> MediaDeleteSource.CAMERA_CARD
}
