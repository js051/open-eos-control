package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import java.util.UUID

internal enum class CameraMediaPickerKind { DOWNLOAD_DOCUMENT, DOWNLOAD_FOLDER, UPLOAD }

/**
 * Owned by one ViewModel, never saved across process death. A request keeps its original
 * camera identity and generation even while the activity or its album is not composed.
 * An invalidated request still occupies the slot until its picker returns or fails to launch.
 */
internal class CameraMediaPickerRequests {
    private data class Request(
        val id: String,
        val kind: CameraMediaPickerKind,
        val generation: Long,
        val connection: CameraInfo,
        val items: List<CameraMediaItem>,
    )

    private var pending: Request? = null

    sealed interface Result {
        data class Ready(val items: List<CameraMediaItem>) : Result
        data object Expired : Result
        data object Ignored : Result
    }

    fun begin(
        kind: CameraMediaPickerKind,
        generation: Long,
        connection: CameraInfo,
        items: List<CameraMediaItem> = emptyList(),
    ): String? {
        if (pending != null) return null
        val selected = items.distinctBy(CameraMediaItem::id)
        require(when (kind) {
            CameraMediaPickerKind.DOWNLOAD_DOCUMENT -> selected.size == 1
            CameraMediaPickerKind.DOWNLOAD_FOLDER -> selected.isNotEmpty()
            CameraMediaPickerKind.UPLOAD -> selected.isEmpty()
        }) { "The media picker request has no valid selection." }
        return UUID.randomUUID().toString().also { id ->
            pending = Request(id, kind, generation, connection, selected)
        }
    }

    fun consume(
        kind: CameraMediaPickerKind,
        id: String?,
        generation: Long,
        connection: CameraInfo?,
    ): Result {
        if (id == null) return Result.Ignored
        val request = pending
        if (request == null || request.id != id || request.kind != kind) return Result.Expired
        pending = null
        // Equal device descriptions and reused media IDs do not identify the same session.
        return if (request.generation == generation && request.connection === connection) {
            Result.Ready(request.items)
        } else Result.Expired
    }

    fun cancel(kind: CameraMediaPickerKind, id: String?): Boolean {
        val request = pending ?: return false
        if (request.kind != kind || request.id != id) return false
        pending = null
        return true
    }
}
