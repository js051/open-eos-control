package dev.openeos.control.ui

import dev.openeos.control.data.MediaDeleteAttempt
import dev.openeos.control.data.MediaDeleteOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaDeleteBatchTest {
    private val connection = Any()
    private val owner = MediaDeleteOwner(connection, 7)
    private fun request(id: String, token: String? = "epoch-1") = MediaDeleteRequest(id, "SAME.JPG", MediaDeleteSource.CAMERA_CARD, selectionToken = token)

    @Test fun exactRosterRetainsCaseUnicodeAndFirstOccurrence() {
        assertEquals(listOf("folder/A", "folder/a", "é", "e\u0301", " "),
            mediaDeleteRoster(listOf("folder/A", "folder/a", "é", "e\u0301", "folder/A", " ")))
        assertEquals(1000, mediaDeleteRoster((0..999).map(Int::toString) + listOf("0")).size)
        assertTrue(runCatching { mediaDeleteRoster((0..1000).map(Int::toString)) }.isFailure)
        assertTrue(runCatching { mediaDeleteRoster(listOf("A", "")) }.isFailure)
    }
    @Test fun dispatchIsSingleUseAndCancellationCannotEraseConfirmedAck() {
        val attempt = MediaDeleteAttempt(); attempt.beforeDispatch(); attempt.confirm(); attempt.cancel()
        assertTrue(runCatching { attempt.beforeDispatch() }.isFailure)
        assertEquals(MediaDeleteOutcome.CONFIRMED, attempt.finish())
    }
    @Test fun cancellationAtActualGateIsNotSentAndStopsRemainder() = runBlocking {
        val control = MediaDeleteBatchControl(); var calls = 0
        var final: MediaDeleteReceipt? = null
        runCatching { executeMediaDeleteBatch("gate", owner, listOf(request("A"), request("B")), control,
            { true }, { null }, { _, evidence -> calls++; control.cancel(); evidence.beforeDispatch() },
            onChanged = { final = it }) }
        assertEquals(1, calls)
        assertEquals(listOf(MediaDeleteOutcome.NOT_SENT, MediaDeleteOutcome.NOT_STARTED), final!!.rows.map { it.outcome })
        assertFalse(final!!.running)
    }
    @Test fun lostAckStopsAndGenericErrorsNeverBecomeRejections() = runBlocking {
        var calls = 0
        val result = executeMediaDeleteBatch("lost", owner, listOf(request("A"), request("B")), MediaDeleteBatchControl(),
            { true }, { null }, { _, evidence -> calls++; evidence.beforeDispatch(); error("Synthetic lost ACK") })
        assertEquals(1, calls)
        assertEquals(listOf(MediaDeleteOutcome.UNCERTAIN, MediaDeleteOutcome.NOT_STARTED), result.rows.map { it.outcome })
    }
    @Test fun typedRejectionContinuesButKnownProtectionSkipsWithoutDispatch() = runBlocking {
        val calls = mutableListOf<String>()
        val result = executeMediaDeleteBatch("refused", owner, listOf(request("A"), request("B"), request("C")), MediaDeleteBatchControl(),
            { true }, { if (it.id == "B") MediaDeleteReason.PROTECTED else null }, { row, evidence ->
                evidence.beforeDispatch(); calls += row.id
                if (row.id == "A") { evidence.onRejected(); error("Synthetic typed refusal") } else evidence.onConfirmed()
            })
        assertEquals(listOf("A", "C"), calls)
        assertEquals(listOf(MediaDeleteOutcome.REJECTED, MediaDeleteOutcome.SKIPPED, MediaDeleteOutcome.CONFIRMED), result.rows.map { it.outcome })
    }
    @Test fun receiptBeforePromptCancellationIsPreservedAndNoNextItemStarts() = runBlocking {
        var result: MediaDeleteReceipt? = null
        val confirmed = mutableListOf<String>()
        runCatching { executeMediaDeleteBatch("cancel", owner, listOf(request("A"), request("B")), MediaDeleteBatchControl(),
            { true }, { null }, { _, evidence -> evidence.beforeDispatch(); evidence.onConfirmed(); throw CancellationException("Return cancelled") },
            onConfirmed = { confirmed += it.id }, onChanged = { result = it }) }
        assertEquals(listOf("A"), confirmed)
        assertEquals(listOf(MediaDeleteOutcome.CONFIRMED, MediaDeleteOutcome.NOT_STARTED), result!!.rows.map { it.outcome })
    }
    @Test fun newOwnerAndReselectedItemAreNeverConsumed() {
        val receipt = MediaDeleteReceipt("selection", owner,
            listOf(MediaDeleteRow(request("A", "epoch-1"), MediaDeleteOutcome.CONFIRMED),
                MediaDeleteRow(request("B", "epoch-2"), MediaDeleteOutcome.REJECTED)))
        val original = linkedMapOf("A" to "epoch-1", "B" to "epoch-2", "new" to "epoch-3")
        assertEquals(linkedMapOf("B" to "epoch-2", "new" to "epoch-3"), consumeConfirmedDeleteSelection(original, receipt, connection, 7))
        assertEquals(original, consumeConfirmedDeleteSelection(original, receipt, Any(), 7))
        assertEquals(original, consumeConfirmedDeleteSelection(original, receipt, connection, 8))
        val reselected = original + ("A" to "epoch-4")
        assertEquals(reselected, consumeConfirmedDeleteSelection(reselected, receipt, connection, 7))
    }
    @Test fun ownerChangeAtGatePreventsDispatchAndRetainsInformationalResult() = runBlocking {
        var current = true; var result: MediaDeleteReceipt? = null
        runCatching { executeMediaDeleteBatch("owner", owner, listOf(request("A"), request("B")), MediaDeleteBatchControl(),
            { current }, { null }, { _, evidence -> current = false; evidence.beforeDispatch() }, onChanged = { result = it }) }
        assertEquals(listOf(MediaDeleteOutcome.NOT_SENT, MediaDeleteOutcome.NOT_STARTED), result!!.rows.map { it.outcome })
        assertEquals(MediaDeleteReason.OWNER_CHANGED, result!!.rows.first().reason)
    }
    @Test fun historyIsBoundedAndUpdatesExistingAttemptWithoutDuplicate() {
        var history = emptyList<MediaDeleteReceipt>()
        repeat(25) { history = retainMediaDeleteReceipt(history, MediaDeleteReceipt("batch-$it", owner, listOf(MediaDeleteRow(request("A"))))) }
        assertEquals(20, history.size)
        assertEquals("batch-24", history.first().attemptId)
        history = retainMediaDeleteReceipt(history, history.first().copy(running = false))
        assertEquals(20, history.size)
        val large = MediaDeleteReceipt("large", owner, (0 until 1000).map { MediaDeleteRow(request(it.toString())) })
        history = retainMediaDeleteReceipt(history, large)
        history = retainMediaDeleteReceipt(history, large.copy(attemptId = "large2"))
        assertEquals(2000, history.sumOf { it.rows.size })
        assertEquals(2, history.size)
    }
    @Test fun changingLoadedProtectionBetweenPreflightAndGateVetoesBeforeSend() = runBlocking {
        var protected = false; var result: MediaDeleteReceipt? = null
        executeMediaDeleteBatch("veto", owner, listOf(request("A")), MediaDeleteBatchControl(),
            { true }, { mediaDeletePreflight(it, true, it.id, it.source, protected, null) },
            { _, evidence -> protected = true; evidence.beforeDispatch(); fail("Must not send") }, onChanged = { result = it })
        assertEquals(MediaDeleteOutcome.NOT_SENT, result!!.rows.single().outcome)
    }
}
