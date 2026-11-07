package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryRequest
import dev.openeos.control.data.DownloadWorklistContinuationSelection
import dev.openeos.control.data.DownloadWorklistOrigin
import dev.openeos.control.data.DownloadWorklistRow

/** Ephemeral, user-authored associations. Never saved in a Bundle, preferences or history. */
internal data class DownloadContinuationDraft(
    val id: String,
    val worklistId: String,
    val rows: List<DownloadWorklistRow>,
    val sources: List<CameraMediaItem>,
    val mappings: Map<String, CameraMediaItem> = emptyMap(),
    val unknownConfirmed: Boolean = false,
    internal val generation: Long,
    internal val connection: CameraInfo,
    internal val historyRequest: DownloadHistoryRequest,
    /** Includes uncertain descendants, so selecting an ancestor cannot bypass the warning. */
    val uncertainRowIds: Set<String> = emptySet(),
) {
    val selectedRows: List<DownloadWorklistRow> get() = rows.filter { it.rowId in mappings }
    val requiresUnknownConfirmation: Boolean get() = selectedRows.any {
        it.rowId in uncertainRowIds || it.receipt?.outcome == DownloadHistoryOutcome.UNCONFIRMED || it.receipt?.cleanupUnconfirmed == true
    }
    val canConfirm: Boolean get() = mappings.isNotEmpty() && (!requiresUnknownConfirmation || unknownConfirmed)
    val selectedItems: List<CameraMediaItem> get() = selectedRows.map { requireNotNull(mappings[it.rowId]) }
    val selections: List<DownloadWorklistContinuationSelection> get() = selectedRows.map {
        DownloadWorklistContinuationSelection(DownloadWorklistOrigin(worklistId, it.rowId), requireNotNull(mappings[it.rowId]).name,
            uncertaintyAcknowledged = unknownConfirmed)
    }

    fun map(rowId: String, sourceId: String?): DownloadContinuationDraft? {
        if (rows.none { it.rowId == rowId }) return null
        if (sourceId == null) return copy(mappings = mappings - rowId, unknownConfirmed = false)
        val source = sources.singleOrNull { it.id == sourceId } ?: return null
        if (mappings.any { (otherRow, item) -> otherRow != rowId && item.id == sourceId }) return null
        return copy(mappings = mappings + (rowId to source), unknownConfirmed = false)
    }
}
