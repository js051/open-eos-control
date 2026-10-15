package dev.openeos.control.data

internal fun CameraMediaItem.metadataReadback() = MediaMetadataReadback(
    id = id, protected = protected, archived = archived, rating = rating, rotationDegrees = rotationDegrees,
)

internal fun CameraMediaItem.metadataWritable(field: MediaMetadataField): Boolean = when (field) {
    MediaMetadataField.PROTECTION -> protectionWritable != false
    MediaMetadataField.ARCHIVE -> archiveWritable != false
    MediaMetadataField.RATING -> ratingWritable != false
    MediaMetadataField.ROTATION -> rotationWritable != false
}

/** A mutation receipt proves only its requested field, never a replacement item's eligibility. */
internal fun CameraMediaItem.withVerifiedMetadata(target: MediaMetadataTarget, readback: CameraMediaItem): CameraMediaItem {
    check(target.matches(id, readback.metadataReadback())) { "Media mutation readback is unconfirmed." }
    return when (target.field) {
        MediaMetadataField.PROTECTION -> copy(protected = readback.protected)
        MediaMetadataField.ARCHIVE -> copy(archived = readback.archived)
        MediaMetadataField.RATING -> copy(rating = readback.rating)
        MediaMetadataField.ROTATION -> copy(rotationDegrees = readback.rotationDegrees)
    }
}

/** A caller snapshot is not authority over an existing current exact-ID row. */
internal fun currentMediaMetadataItem(
    caller: CameraMediaItem,
    loaded: List<CameraMediaItem>,
    preview: CameraMediaItem? = null,
    captureReview: CameraMediaItem? = null,
): CameraMediaItem = loaded.firstOrNull { it.id == caller.id }
    ?: preview?.takeIf { it.id == caller.id }
    ?: captureReview?.takeIf { it.id == caller.id }
    ?: caller // Preserve the existing fresh-detail entry when no current record exists.
