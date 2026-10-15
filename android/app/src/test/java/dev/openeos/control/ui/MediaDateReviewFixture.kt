package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem

internal data class MediaDateReviewFixture(
    val schemaVersion: Int,
    val instantResolution: String,
    val calendar: String,
    val zones: List<String>,
    val kinds: List<String>,
    val precisionOrder: List<String>,
    val unknownAndTies: String,
    val vectors: List<MediaDateVector>,
    val sortItems: List<CameraMediaItem>,
    val sortCases: List<MediaDateSortVector>,
    val reviewItems: List<CameraMediaItem>,
    val reviewCases: List<MediaReviewVector>,
)
internal data class MediaDateVector(
    val id: String, val raw: String?, val kind: String, val instantMillis: Long?,
    val display: Map<String, MediaDateDisplayVector>,
)
internal data class MediaDateDisplayVector(val dateKey: String?, val timeKey: String?)
internal data class MediaDateSortVector(val zone: String, val sort: String, val expectedIds: List<String>)
internal data class MediaReviewVector(val kind: String, val previousIds: Set<String>, val expectedId: String?)
