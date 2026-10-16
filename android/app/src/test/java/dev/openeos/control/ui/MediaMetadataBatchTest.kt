package dev.openeos.control.ui

import dev.openeos.control.data.MediaMetadataField
import dev.openeos.control.data.MediaMetadataReadback
import dev.openeos.control.data.MediaMetadataTarget
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MediaMetadataBatchTest {
    private val target = MediaMetadataTarget(MediaMetadataField.RATING, 3)
    private fun requests(count: Int = 3) = (0 until count).map { MediaMetadataRequest("item-$it", "same.JPG", target) }
    private fun readback(request: MediaMetadataRequest) = MediaMetadataReadback(request.id, rating = 3)

    @Test fun snapshotsExactIdsAndTargetDeduplicatesFirstOccurrenceInOrder() = runBlocking {
        val input = requests().toMutableList().apply { add(1, first().copy(name = "duplicate")) }
        val writes = mutableListOf<String>()
        val result = executeMediaMetadataBatch(input, { true }, { null }, {
            input.clear(); writes += it.id; readback(it)
        }, { it })
        assertEquals(listOf("item-0", "item-1", "item-2"), writes)
        assertEquals(3, result.confirmed)
        assertTrue(result.rows.all { it.request.name == "same.JPG" && it.request.target == target })
    }
    @Test fun failedPreflightAndUnloadedRowsDoNotSendButOtherEligibleRowsContinue() = runBlocking {
        val writes = mutableListOf<String>()
        val result = executeMediaMetadataBatch(requests(), { true }, {
            when(it.id) { "item-0" -> MediaMetadataReason.NOT_WRITABLE; "item-2" -> MediaMetadataReason.UNLOADED; else -> null }
        }, { writes += it.id; readback(it) }, { it })
        assertEquals(listOf("item-1"), writes)
        assertEquals(listOf(MediaMetadataOutcome.FAILED_NOT_SENT, MediaMetadataOutcome.CONFIRMED, MediaMetadataOutcome.NOT_STARTED), result.rows.map { it.outcome })
    }
    @Test fun everyErrorAfterDispatchStopsWithoutRetryAndRetainsEarlierConfirmation() = runBlocking {
        val writes = mutableListOf<String>()
        val result = executeMediaMetadataBatch(requests(), { true }, { null }, {
            writes += it.id
            if (it.id == "item-1") throw IOException("lost response")
            readback(it)
        }, { it })
        assertEquals(listOf("item-0", "item-1"), writes)
        assertEquals(listOf(MediaMetadataOutcome.CONFIRMED, MediaMetadataOutcome.UNCERTAIN, MediaMetadataOutcome.NOT_STARTED), result.rows.map { it.outcome })
    }
    @Test fun mismatchedIdOrUnknownOrDifferentValueNeverPublishesToCache() = runBlocking {
        for (bad in listOf(MediaMetadataReadback("item-0", rating = 2), MediaMetadataReadback("item-0"), MediaMetadataReadback("ITEM-0", rating = 3))) {
            var writes = 0; var published = 0
            val result = executeMediaMetadataBatch(requests(), { true }, { null }, { writes++; bad }, { it }, { published++ })
            assertEquals(1, writes); assertEquals(0, published)
            assertEquals(1, result.uncertain); assertEquals(2, result.notStarted)
        }
    }
    @Test fun cancellationBeforeDispatchLeavesEveryRowUnstarted() = runBlocking {
        var receipt: MediaMetadataBatchResult? = null; var writes = 0
        val job = launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()
            executeMediaMetadataBatch(requests(), { true }, {
                self.cancel(); null
            }, { writes++; readback(it) }, { it }, onChanged = { receipt = it })
        }
        job.start(); job.join()
        assertEquals(0, writes); assertEquals(3, receipt?.notStarted); assertFalse(requireNotNull(receipt).running)
    }
    @Test fun cancellationInFlightRetainsConfirmationsAndMarksUnansweredRowUncertain() = runBlocking {
        var receipt: MediaMetadataBatchResult? = null; val writes = mutableListOf<String>()
        val dispatched = CompletableDeferred<Unit>()
        val job = launch {
            executeMediaMetadataBatch(requests(), { true }, { null }, {
                writes += it.id
                if (it.id == "item-1") { dispatched.complete(Unit); awaitCancellation() }
                readback(it)
            }, { it }, onChanged = { receipt = it })
        }
        dispatched.await(); job.cancelAndJoin()
        assertEquals(listOf("item-0", "item-1"), writes)
        assertEquals(listOf(MediaMetadataOutcome.CONFIRMED, MediaMetadataOutcome.UNCERTAIN, MediaMetadataOutcome.NOT_STARTED), receipt?.rows?.map { it.outcome })
    }
    @Test fun sameOwnerLateExactConfirmationCanSurviveCancellationButStopsRemaining() = runBlocking {
        var receipt: MediaMetadataBatchResult? = null; var writes = 0
        val job = launch {
            executeMediaMetadataBatch(requests(), { true }, { null }, {
                writes++; currentCoroutineContext().cancel(); readback(it)
            }, { it }, onChanged = { receipt = it })
        }
        job.join()
        assertEquals(1, writes); assertEquals(1, receipt?.confirmed); assertEquals(2, receipt?.notStarted)
    }
    @Test fun ownerReplacementRetiresInFlightResultWithoutUpdatingReplacementCacheOrSendingMore() = runBlocking {
        var current = true; var writes = 0; var publications = 0
        val result = executeMediaMetadataBatch(requests(), { current }, { null }, {
            writes++; current = false; readback(it)
        }, { it }, { publications++ })
        assertEquals(1, writes); assertEquals(0, publications)
        assertEquals(1, result.uncertain); assertEquals(2, result.notStarted)
    }
    @Test fun ownerLossBeforeDispatchSendsNothing() = runBlocking {
        var current = true; var writes = 0
        val result = executeMediaMetadataBatch(requests(), { current }, { current = false; null }, { writes++; readback(it) }, { it })
        assertEquals(0, writes); assertEquals(3, result.notStarted)
    }
    @Test fun emptyOrOverLimitBatchRejectsAsAWholeAnd1000IsAllowed() = runBlocking {
        var writes = 0
        for (count in listOf(0, 1001)) {
            val error = runCatching { executeMediaMetadataBatch(requests(count), { true }, { null }, { writes++; readback(it) }, { it }) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException); assertEquals(0, writes)
        }
        assertEquals(1000, executeMediaMetadataBatch(requests(1000), { true }, { null }, { readback(it) }, { it }).confirmed)
    }
    @Test fun rosterCountsHiddenLoadedAndUnloadedSeparatelyWithoutNormalizingIds() {
        val result = mediaMetadataRoster(listOf("é", "gone", "e\u0301", "é"), setOf("é", "e\u0301"), setOf("é"))
        assertEquals(listOf("é", "gone", "e\u0301"), result.ids)
        assertEquals(listOf("é", "e\u0301"), result.eligibleIds)
        assertEquals(1, result.hiddenCount); assertEquals(1, result.unloadedCount)
    }
    @Test fun currentLoadedEligibilityControlsWritableAndArchiveEvidenceWithoutUsingStaleSelectionValues() {
        for (field in MediaMetadataField.entries) {
            assertEquals(MediaMetadataReason.UNSUPPORTED, mediaMetadataPreflight(field, false, "item", "item", false, true))
            assertEquals(MediaMetadataReason.UNLOADED, mediaMetadataPreflight(field, true, "item", null, false, true))
            // A formerly writable selected copy cannot override a currently unwritable row.
            assertEquals(MediaMetadataReason.NOT_WRITABLE, mediaMetadataPreflight(field, true, "item", "item", false, false))
            assertNull(mediaMetadataPreflight(field, true, "item", "item", false, true))
        }
        // A selected copy's old archive=false must not make current archive=unknown writable.
        assertEquals(MediaMetadataReason.UNKNOWN_METADATA,
            mediaMetadataPreflight(MediaMetadataField.ARCHIVE, true, "item", "item", null, true))
        assertNull(mediaMetadataPreflight(MediaMetadataField.ARCHIVE, true, "item", "item", true, null))
        assertNull(mediaMetadataPreflight(MediaMetadataField.RATING, true, "item", "item", null, null))
    }

    @Test fun frozenOwnerRequiresSameReferenceAndGenerationEvenWhenValuesAndIdsAreReused() {
        data class Connection(val name: String)
        val first = Connection("same"); val replacement = Connection("same")
        val owner = MediaMetadataOwner(first, 7)
        assertTrue(owner.matches(first, 7))
        assertFalse(owner.matches(replacement, 7))
        assertFalse(owner.matches(first, 8))
    }
    @Test fun retiredReceiptKeepsConfirmedAndUnsentRowsButDisclosesInFlightUncertainty() {
        val requests = requests()
        val receipt = MediaMetadataBatchResult(listOf(
            MediaMetadataRow(requests[0], MediaMetadataOutcome.CONFIRMED),
            MediaMetadataRow(requests[1], MediaMetadataOutcome.IN_FLIGHT),
            MediaMetadataRow(requests[2]),
        ), running = true).retiredMetadataReceipt()
        assertFalse(receipt.running)
        assertEquals(listOf(MediaMetadataOutcome.CONFIRMED, MediaMetadataOutcome.UNCERTAIN,
            MediaMetadataOutcome.NOT_STARTED), receipt.rows.map { it.outcome })
        assertEquals(MediaMetadataReason.OWNER_CHANGED, receipt.rows[1].reason)
        assertEquals(requests.map { it.id }, receipt.rows.map { it.request.id })
    }

}
