package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem

internal data class MediaTypeFixture(
    val schemaVersion: Int,
    val cases: List<MediaTypeVector>,
    val freshMetadataCases: List<MediaTypeFreshVector>,
    val selectionCases: List<MediaTypeSelectionVector>,
    val mimeAliasCases: List<MediaTypeMimeAliasVector>,
)
internal data class MediaTypeVector(
    val id: String, val item: CameraMediaItem, val classification: String,
    val isPhoto: Boolean, val isVideo: Boolean, val automaticJpegEligible: Boolean,
)
internal data class MediaTypeFreshVector(
    val id: String, val original: CameraMediaItem, val metadata: CameraMediaItem,
    val fresh: Boolean, val expectedAdmitted: Boolean,
)
internal data class MediaTypeSelectionVector(
    val id: String, val intent: String, val listingCases: List<String>,
    val knownCaseIds: List<String>, val expectedCaseId: String?,
)
internal data class MediaTypeMimeAliasVector(
    val id: String, val fields: Map<String, Any?>, val expectedValid: Boolean, val expectedValue: String?,
)
