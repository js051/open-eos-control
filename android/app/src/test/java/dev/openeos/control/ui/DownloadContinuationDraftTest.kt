package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryEntry
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryRequest
import dev.openeos.control.data.DownloadWorklistRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DownloadContinuationDraftTest {
    @Test fun identicalLabelsNeverCreateAutomaticMappings() {
        val draft = draft()
        assertEquals(draft.rows.first().filename, draft.sources.first().name)
        assertTrue(draft.mappings.isEmpty())
        assertFalse(draft.canConfirm)
        assertTrue(draft.selections.isEmpty())
    }

    @Test fun userCanMapDistinctSameNamedOriginalsButCannotMapOneSourceTwice() {
        val initial = draft()
        val first = requireNotNull(initial.map(initial.rows[0].rowId, "source-a"))
        assertNull(first.map(initial.rows[1].rowId, "source-a"))
        val second = requireNotNull(first.map(initial.rows[1].rowId, "source-b"))
        assertEquals(listOf("source-a", "source-b"), second.selectedItems.map { it.id })
        assertTrue(second.canConfirm)
        assertNull(second.map("missing-row", "source-b"))
        assertNull(second.map(initial.rows[0].rowId, "not-loaded"))
    }

    @Test fun unknownResultRequiresNewAcknowledgementAfterEveryMappingChange() {
        val initial = draft(unknown = true)
        val mapped = requireNotNull(initial.map(initial.rows[0].rowId, "source-a"))
        assertTrue(mapped.requiresUnknownConfirmation)
        assertFalse(mapped.canConfirm)
        val checked = mapped.copy(unknownConfirmed = true)
        assertTrue(checked.canConfirm)
        val changed = requireNotNull(checked.map(initial.rows[0].rowId, "source-b"))
        assertFalse(changed.unknownConfirmed)
        assertFalse(changed.canConfirm)
        val removed = requireNotNull(checked.map(initial.rows[0].rowId, null))
        assertFalse(removed.requiresUnknownConfirmation)
        assertFalse(removed.canConfirm)
    }

    @Test fun subsetKeepsOriginalRowOrderAndOnlyLocalOriginAssociation() {
        val initial = draft()
        val second = requireNotNull(initial.map(initial.rows[1].rowId, "source-a"))
        val both = requireNotNull(second.map(initial.rows[0].rowId, "source-b"))
        assertEquals(initial.rows.map { it.rowId }, both.selections.map { it.origin.rowId })
        assertTrue(both.selections.all { it.origin.worklistId == initial.worklistId })
        assertEquals(listOf("source-b", "source-a"), both.selectedItems.map { it.id })
        assertEquals(listOf("SAME.JPG", "SAME.JPG"), both.selections.map { it.filename })
    }

    @Test fun queuedAncestorWithUncertainDescendantRequiresAcknowledgementInNewAdmission() {
        val original = draft()
        val initial = original.copy(uncertainRowIds = setOf(original.rows[0].rowId))
        assertNull(initial.rows[0].receipt)
        val mapped = requireNotNull(initial.map(initial.rows[0].rowId, "source-a"))
        assertFalse(mapped.canConfirm)
        assertFalse(mapped.selections.single().uncertaintyAcknowledged)
        val checked = mapped.copy(unknownConfirmed = true)
        assertTrue(checked.canConfirm)
        assertTrue(checked.selections.single().uncertaintyAcknowledged)
    }

    private fun draft(unknown: Boolean = false): DownloadContinuationDraft {
        val rows = List(2) { index ->
            DownloadWorklistRow(UUID.randomUUID().toString(), "SAME.JPG",
                if (unknown && index == 0) DownloadHistoryEntry(UUID.randomUUID().toString(), "SAME.JPG",
                    DownloadHistoryDestination.GALLERY, 1L, null, DownloadHistoryOutcome.UNCONFIRMED) else null)
        }
        return DownloadContinuationDraft(UUID.randomUUID().toString(), UUID.randomUUID().toString(), rows,
            listOf(CameraMediaItem("source-a", "SAME.JPG", "image", 123L),
                CameraMediaItem("source-b", "SAME.JPG", "image", 123L)),
            generation = 1L, connection = CameraInfo(true, "SYNTHETIC", "SYNTHETIC", "test"),
            historyRequest = DownloadHistoryRequest(Any(), Any()))
    }
}
