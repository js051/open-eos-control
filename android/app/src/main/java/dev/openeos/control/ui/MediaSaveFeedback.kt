package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaTransferProgress

/** Feedback belongs to an exact media ID in one save request, never to a display filename. */
sealed interface MediaSaveFeedback {
    data object SelectingDestination : MediaSaveFeedback
    data object Queued : MediaSaveFeedback
    data class Saving(val progress: CameraMediaTransferProgress) : MediaSaveFeedback
    data class Saved(val location: String) : MediaSaveFeedback
    data class Failed(val message: String) : MediaSaveFeedback
    data object Cancelled : MediaSaveFeedback
}

internal val MediaSaveFeedback.isPending: Boolean
    get() = this is MediaSaveFeedback.SelectingDestination || this is MediaSaveFeedback.Queued ||
        this is MediaSaveFeedback.Saving

/** Callback admission uses request and connection identity as well as the camera generation. */
internal class MediaSaveRequest(
    val generation: Long,
    val connection: CameraInfo,
    val pickerId: String? = null,
) {
    fun owns(current: MediaSaveRequest?, generation: Long, connection: CameraInfo?): Boolean =
        this === current && this.generation == generation && this.connection === connection
}
