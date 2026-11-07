package dev.openeos.control.ui

import dev.openeos.control.data.DownloadHistoryEntry
import dev.openeos.control.data.DownloadHistoryState
import org.junit.Assert.assertEquals

/** Queued rows are not receipts, and a receipt must never be counted in both storage paths. */
internal fun DownloadHistoryState.actualReceipts(): List<DownloadHistoryEntry> {
    val receipts = entries + worklists.flatMap { plan -> plan.rows.mapNotNull { it.receipt } }
    assertEquals("Every actual attempt has exactly one receipt", receipts.size,
        receipts.map(DownloadHistoryEntry::receiptId).toSet().size)
    return receipts
}
