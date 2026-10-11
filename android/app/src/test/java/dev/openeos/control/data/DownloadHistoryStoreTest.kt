package dev.openeos.control.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadHistoryStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun schemaOneRecordsUpgradeWithoutLosingOutcomesAndRetainCleanupRiskOnRestart() = runTest {
        val directory = temporaryFolder.newFolder()
        snapshot(directory).writeText(fixtureJson().toString())
        val store = newStore(directory)
        store.awaitIdle()
        assertEquals(listOf(fixtureEntry()), store.state.value.entries)
        assertFalse(store.state.value.entries.single().cleanupUnconfirmed)

        val receipt = store.begin(store.captureRequest(), "SYNTHETIC-CANCELLED.JPG", DownloadHistoryDestination.FOLDER)
        store.recordFinished(receipt, DownloadHistoryOutcome.CANCELLED, cleanupUnconfirmed = true)
        store.awaitIdle()
        assertEquals(3, JSONObject(snapshot(directory).readText()).getInt("schema"))
        assertEquals(fixtureEntry(), store.state.value.entries.last())
        val reopened = newStore(directory)
        reopened.awaitIdle()
        assertEquals(store.state.value.entries, reopened.state.value.entries)
        assertTrue(reopened.state.value.entries.first().cleanupUnconfirmed)
        assertEquals(DownloadHistoryOutcome.CANCELLED, reopened.state.value.entries.first().outcome)
    }

    @Test fun schemaTwoRejectsMalformedRiskAndRiskOnCompletedOutputWithoutRewritingInput() = runTest {
        val invalid = listOf<Any>(JSONObject.NULL, 1, "true", JSONObject()).map { value ->
            fixtureJson().put("schema", 2).apply { row().put("outcome", "FAILED").put("cleanupUnconfirmed", value) }
        } + fixtureJson().put("schema", 2).apply { row().put("cleanupUnconfirmed", true) }
        for (fixture in invalid) {
            val directory = temporaryFolder.newFolder()
            val original = fixture.toString().toByteArray()
            snapshot(directory).writeBytes(original)
            val store = newStore(directory)
            store.awaitIdle()
            assertEquals(DownloadHistoryWarning.READ_FAILED, store.state.value.warning)
            assertNull(store.begin(store.captureRequest(), "NEW.JPG", DownloadHistoryDestination.FOLDER))
            assertArrayEquals(original, snapshot(directory).readBytes())
        }
    }

    @Test fun lateCleanupRiskCannotDowngradeCompletedOrRestoreClearedReceipts() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val saved = store.begin(store.captureRequest(), "SAVED.JPG", DownloadHistoryDestination.DOCUMENT)
        store.recordFinished(saved, DownloadHistoryOutcome.COMPLETED, cleanupUnconfirmed = true)
        store.recordFinished(saved, DownloadHistoryOutcome.FAILED, cleanupUnconfirmed = true)
        store.awaitIdle()
        assertEquals(DownloadHistoryOutcome.COMPLETED, store.state.value.entries.single().outcome)
        assertFalse(store.state.value.entries.single().cleanupUnconfirmed)
        val pending = store.begin(store.captureRequest(), "CANCELLED.JPG", DownloadHistoryDestination.FOLDER)
        assertTrue(store.clear())
        store.recordFinished(pending, DownloadHistoryOutcome.CANCELLED, cleanupUnconfirmed = true)
        store.awaitIdle()
        assertTrue(store.state.value.entries.isEmpty())
        assertTrue(DownloadHistoryFileStorage(directory).read().isEmpty())
    }

    @Test fun missingSnapshotLoadsEmptyWithoutCreatingAFile() = runTest {
        val directory = File(temporaryFolder.root, "not-created-yet")
        val store = newStore(directory)
        assertTrue(store.state.value.loading)
        assertFalse(store.state.value.writable)
        store.awaitIdle()
        assertTrue(store.state.value.entries.isEmpty())
        assertTrue(store.state.value.writable)
        assertFalse(store.state.value.loading)
        assertFalse(directory.exists())
    }

    @Test fun completedAndPendingReceiptsSurviveRecreationWithoutInventedSuccess() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val request = store.captureRequest()
        val completed = store.begin(request, "COMPLETED.JPG", DownloadHistoryDestination.GALLERY)
        store.recordFinished(completed, DownloadHistoryOutcome.COMPLETED)
        val pending = store.begin(request, "PENDING.CR3", DownloadHistoryDestination.FOLDER)
        store.awaitIdle()
        assertEquals(DownloadHistoryOutcome.IN_PROGRESS, store.state.value.entries.first().outcome)
        val beforeReopen = snapshot(directory).readBytes()

        val reopened = newStore(directory)
        reopened.awaitIdle()

        assertEquals(listOf(pending!!.receiptId, completed!!.receiptId), reopened.state.value.entries.map { it.receiptId })
        assertEquals(listOf(DownloadHistoryOutcome.UNCONFIRMED, DownloadHistoryOutcome.COMPLETED), reopened.state.value.entries.map { it.outcome })
        assertNull(reopened.state.value.entries[0].finishedAtMillis)
        assertNotNull(reopened.state.value.entries[1].finishedAtMillis)
        assertArrayEquals(beforeReopen, snapshot(directory).readBytes())
    }

    @Test fun requestCapturedBeforeInitializationIsNotInvalidatedByDiskLoad() = runTest {
        val directory = temporaryFolder.newFolder()
        DownloadHistoryFileStorage(directory).write(listOf(fixtureEntry()))
        val store = newStore(directory)
        val request = store.captureRequest()
        val admitted = async(start = CoroutineStart.UNDISPATCHED) {
            store.begin(request, "AFTER_LOAD.JPG", DownloadHistoryDestination.DOCUMENT)
        }
        assertFalse(admitted.isCompleted)
        val receipt = admitted.await()
        assertNotNull(receipt)
        assertFalse(store.state.value.loading)
        assertEquals(2, DownloadHistoryFileStorage(directory).read().size)
        assertEquals(receipt!!.receiptId, DownloadHistoryFileStorage(directory).read().first().receiptId)
    }

    @Test fun beginReturnsOnlyAfterPendingSnapshotIsInstalled() = runTest {
        val directory = temporaryFolder.newFolder()
        var phaseReached = false
        val store = newStore(directory, beforeWrite = {
            if (it == DownloadHistoryWritePhase.BEFORE_MOVE) {
                phaseReached = true
                assertFalse(snapshot(directory).exists())
            }
        })
        val receipt = store.begin(store.captureRequest(), "START.JPG", DownloadHistoryDestination.GALLERY)
        assertTrue(phaseReached)
        assertEquals(receipt!!.receiptId, DownloadHistoryFileStorage(directory).read().single().receiptId)
        assertEquals(DownloadHistoryOutcome.IN_PROGRESS, DownloadHistoryFileStorage(directory).read().single().outcome)
    }

    @Test fun eachFailedWriteStagePreservesPreviousSnapshotAndReportsOnlyGenericWarning() = runTest {
        for (phase in DownloadHistoryWritePhase.entries.filter { it != DownloadHistoryWritePhase.AFTER_MOVE }) {
            val directory = temporaryFolder.newFolder()
            DownloadHistoryFileStorage(directory).write(listOf(fixtureEntry()))
            val original = snapshot(directory).readBytes()
            val store = newStore(directory, beforeWrite = {
                if (it == phase) throw IOException("synthetic-secret content://private/example")
            })
            val receipt = store.begin(store.captureRequest(), "NEW.JPG", DownloadHistoryDestination.FOLDER)
            assertNotNull(receipt)
            assertEquals(DownloadHistoryWarning.WRITE_FAILED, store.state.value.warning)
            assertEquals(2, store.state.value.entries.size)
            assertArrayEquals(original, snapshot(directory).readBytes())
            assertEquals(listOf(DOWNLOAD_HISTORY_SNAPSHOT_NAME), directory.list()!!.toList())
        }
    }

    @Test fun corruptFutureOversizedAndDuplicateInputStayByteIdenticalUntilExplicitReset() = runTest {
        val valid = fixtureJson()
        val invalidInputs = listOf(
            "{".toByteArray(),
            valid.put("schema", 3).toString().toByteArray(),
            ByteArray(MAX_DOWNLOAD_HISTORY_SNAPSHOT_BYTES + 1) { ' '.code.toByte() },
            fixtureJson().apply { getJSONArray("records").put(getJSONArray("records").getJSONObject(0)) }.toString().toByteArray(),
            byteArrayOf(0xC3.toByte(), 0x28),
            (fixtureJson().toString() + "\u0000unexpected-suffix").toByteArray(),
            (fixtureJson().toString() + " unexpected-suffix").toByteArray(),
        )
        for (bytes in invalidInputs) {
            val directory = temporaryFolder.newFolder()
            snapshot(directory).writeBytes(bytes)
            val store = newStore(directory)
            val receipt = store.begin(store.captureRequest(), "MUST_NOT_REPLACE.JPG", DownloadHistoryDestination.GALLERY)
            assertNull(receipt)
            assertFalse(store.state.value.writable)
            assertEquals(DownloadHistoryWarning.READ_FAILED, store.state.value.warning)
            assertArrayEquals(bytes, snapshot(directory).readBytes())
            assertTrue(store.clear())
            assertTrue(DownloadHistoryFileStorage(directory).read().isEmpty())
            assertTrue(store.state.value.writable)
            assertNull(store.state.value.warning)
        }
    }

    @Test fun strictSchemaRejectsCoercedNumbersUnknownFieldsAndInvalidReceipts() = runTest {
        val variants = listOf<(JSONObject) -> Unit>(
            { it.put("schema", "1") },
            { it.put("schema", 1.5) },
            { it.put("cameraSerial", "SYNTHETIC-SERIAL-PRIVATE") },
            { it.row().put("startedAtMillis", "1000") },
            { it.row().put("startedAtMillis", 1000.5) },
            { it.row().put("startedAtMillis", -1) },
            { it.row().put("finishedAtMillis", "2000") },
            { it.row().put("receiptId", "1-1-1-1-1") },
            { it.row().put("receiptId", "F2B2DFB2-26F0-42D0-A6A4-F97D3110C70A") },
            { it.row().put("outcome", "SUCCESS_MAYBE") },
            { it.row().put("destination", "content://private/example") },
            { it.row().put("filename", "/private/user/PHOTO.JPG") },
            { it.row().put("filename", "X".repeat(MAX_DOWNLOAD_HISTORY_FILENAME_LENGTH + 1)) },
            { it.row().put("finishedAtMillis", JSONObject.NULL) },
            { it.row().put("outcome", "IN_PROGRESS") },
            { it.row().remove("filename") },
            { it.put("records", JSONArray(List(101) { JSONObject(fixtureJson().row().toString()).put("receiptId", UUID.randomUUID().toString()) })) },
        )
        for (mutate in variants) {
            val directory = temporaryFolder.newFolder()
            val bytes = fixtureJson().apply(mutate).toString().toByteArray()
            snapshot(directory).writeBytes(bytes)
            val store = newStore(directory)
            store.awaitIdle()
            assertEquals(String(bytes), DownloadHistoryWarning.READ_FAILED, store.state.value.warning)
            assertFalse(store.state.value.writable)
            assertArrayEquals(bytes, snapshot(directory).readBytes())
        }
    }

    @Test fun clearingAnActiveBatchRejectsLateCompletionAndRemainingItems() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val oldBatch = store.captureRequest()
        val receipt = store.begin(oldBatch, "FIRST.JPG", DownloadHistoryDestination.FOLDER)
        assertTrue(store.clear())
        store.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
        assertNull(store.begin(oldBatch, "SECOND.JPG", DownloadHistoryDestination.FOLDER))
        store.awaitIdle()
        assertTrue(store.state.value.entries.isEmpty())
        assertTrue(DownloadHistoryFileStorage(directory).read().isEmpty())
        val newReceipt = store.begin(store.captureRequest(), "FIRST.JPG", DownloadHistoryDestination.FOLDER)
        assertNotNull(newReceipt)
        assertEquals(1, DownloadHistoryFileStorage(directory).read().size)
    }

    @Test fun oldRequestQueuedDuringSuccessfulClearCannotRepopulateSnapshot() = runTest {
        val directory = temporaryFolder.newFolder()
        var duringMove: (() -> Unit)? = null
        val store = newStore(directory, beforeWrite = {
            if (it == DownloadHistoryWritePhase.BEFORE_MOVE) duringMove?.invoke()
        })
        val oldBatch = store.captureRequest()
        store.begin(oldBatch, "FIRST.JPG", DownloadHistoryDestination.GALLERY)
        var queued: kotlinx.coroutines.Deferred<DownloadHistoryReceipt?>? = null
        duringMove = {
            duringMove = null
            queued = async(start = CoroutineStart.UNDISPATCHED) {
                store.begin(store.captureRequest(), "DURING_CLEAR.JPG", DownloadHistoryDestination.GALLERY)
            }
        }
        assertTrue(store.clear())
        assertNull(queued!!.await())
        assertTrue(DownloadHistoryFileStorage(directory).read().isEmpty())
    }

    @Test fun failedClearPreservesRecordsEpochAndAbilityToFinishOldBatch() = runTest {
        val directory = temporaryFolder.newFolder()
        var fail = false
        val store = newStore(directory, beforeWrite = {
            if (fail && it == DownloadHistoryWritePhase.BEFORE_MOVE) throw IOException("synthetic failure")
        })
        val batch = store.captureRequest()
        val receipt = store.begin(batch, "ACTIVE.JPG", DownloadHistoryDestination.FOLDER)
        val before = snapshot(directory).readBytes()
        fail = true
        assertFalse(store.clear())
        assertEquals(DownloadHistoryWarning.CLEAR_FAILED, store.state.value.warning)
        assertEquals(DownloadHistoryOutcome.IN_PROGRESS, store.state.value.entries.single().outcome)
        assertArrayEquals(before, snapshot(directory).readBytes())
        fail = false
        store.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
        assertNotNull(store.begin(batch, "NEXT.JPG", DownloadHistoryDestination.FOLDER))
        store.awaitIdle()
        assertEquals(DownloadHistoryOutcome.COMPLETED, store.state.value.entries[1].outcome)
    }

    @Test fun failedResetOfUnreadableSnapshotLeavesItPreservedAndWriteDisabled() = runTest {
        val directory = temporaryFolder.newFolder()
        val corrupt = "{truncated-history".toByteArray()
        snapshot(directory).writeBytes(corrupt)
        val store = newStore(directory, beforeWrite = {
            if (it == DownloadHistoryWritePhase.BEFORE_MOVE) throw IOException("synthetic failure")
        })
        store.awaitIdle()
        assertFalse(store.clear())
        assertFalse(store.state.value.writable)
        assertEquals(DownloadHistoryWarning.CLEAR_FAILED, store.state.value.warning)
        assertNull(store.begin(store.captureRequest(), "NEXT.JPG", DownloadHistoryDestination.DOCUMENT))
        assertArrayEquals(corrupt, snapshot(directory).readBytes())
    }

    @Test fun evictionNeverAllowsAnOldCompletionToReinsertItsReceipt() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val batch = store.captureRequest()
        val oldest = store.begin(batch, "OLDEST.JPG", DownloadHistoryDestination.DOCUMENT)
        store.recordFinished(oldest, DownloadHistoryOutcome.COMPLETED)
        repeat(MAX_DOWNLOAD_HISTORY_ENTRIES) {
            val item = store.begin(batch, "ITEM_$it.JPG", DownloadHistoryDestination.DOCUMENT)
            store.recordFinished(item, DownloadHistoryOutcome.COMPLETED)
        }
        store.awaitIdle()
        val before = snapshot(directory).readBytes()
        store.recordFinished(oldest, DownloadHistoryOutcome.COMPLETED)
        store.awaitIdle()
        assertEquals(MAX_DOWNLOAD_HISTORY_ENTRIES, store.state.value.entries.size)
        assertFalse(store.state.value.entries.any { it.receiptId == oldest!!.receiptId })
        assertArrayEquals(before, snapshot(directory).readBytes())
        assertEquals("ITEM_99.JPG", DownloadHistoryFileStorage(directory).read().first().filename)
    }

    @Test fun repeatedFilenamesHaveIndependentReceiptsAndFirstTerminalResultWins() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val batch = store.captureRequest()
        val first = store.begin(batch, "SAME.JPG", DownloadHistoryDestination.GALLERY)
        val second = store.begin(batch, "SAME.JPG", DownloadHistoryDestination.GALLERY)
        assertNotEquals(first!!.receiptId, second!!.receiptId)
        store.recordFinished(first, DownloadHistoryOutcome.COMPLETED)
        store.recordFinished(first, DownloadHistoryOutcome.FAILED)
        store.recordFinished(second, DownloadHistoryOutcome.CANCELLED)
        store.awaitIdle()
        assertEquals(listOf(DownloadHistoryOutcome.CANCELLED, DownloadHistoryOutcome.COMPLETED), DownloadHistoryFileStorage(directory).read().map { it.outcome })
    }

    @Test fun clockReversalDoesNotChangeInsertionOrderOrRejectRealFinishTimes() = runTest {
        val directory = temporaryFolder.newFolder()
        var clock = 2000L
        val store = newStore(directory, nowMillis = { clock })
        val request = store.captureRequest()
        store.begin(request, "EARLIER.JPG", DownloadHistoryDestination.DOCUMENT)
        clock = 1000L
        val latest = store.begin(request, "LATER.JPG", DownloadHistoryDestination.DOCUMENT)
        clock = 500L
        store.recordFinished(latest, DownloadHistoryOutcome.COMPLETED)
        store.awaitIdle()
        val reopened = newStore(directory)
        reopened.awaitIdle()
        assertEquals(listOf("LATER.JPG", "EARLIER.JPG"), reopened.state.value.entries.map { it.filename })
        assertEquals(500L, reopened.state.value.entries.first().finishedAtMillis)
    }

    @Test fun persistedLabelsExcludeUrisSecretsPrivateDirectoriesAndControlCharacters() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val request = store.captureRequest()
        val inputs = listOf(
            "https://SYNTHETIC-SERIAL-PRIVATE.example/media/PHOTO.JPG?token=SYNTHETIC-SECRET" to "download",
            "content://SYNTHETIC-PROVIDER/private/PHOTO.JPG" to "download",
            "/private/SYNTHETIC-USER/photos/PHOTO.JPG?token=SYNTHETIC-SECRET" to "PHOTO.JPG",
            "C:\\fixture-private\\SYNTHETIC-USER\\PHOTO.JPG#SYNTHETIC-SECRET" to "PHOTO.JPG",
            "PH\nOTO\u0000.JPG\u202e" to "PHOTO.JPG",
            "IMG.JPG?token=SYNTHETIC-SECRET" to "IMG.JPG",
            "X".repeat(1000) to "X".repeat(MAX_DOWNLOAD_HISTORY_FILENAME_LENGTH),
            ".." to "download",
        )
        for ((input, expected) in inputs) {
            store.begin(request, input, DownloadHistoryDestination.DOCUMENT)
            assertEquals(expected, store.state.value.entries.first().filename)
        }
        val bytes = snapshot(directory).readText()
        listOf("SYNTHETIC-SECRET", "SYNTHETIC-USER", "SYNTHETIC-SERIAL-PRIVATE", "SYNTHETIC-PROVIDER", "content://", "https://", "/private/", "token=").forEach {
            assertFalse(it, bytes.contains(it))
        }
        assertEquals(setOf("schema", "records", "worklists"), JSONObject(bytes).keys().asSequence().toSet())
        assertEquals(setOf("receiptId", "filename", "destination", "startedAtMillis", "finishedAtMillis", "outcome", "cleanupUnconfirmed"), JSONObject(bytes).row().keys().asSequence().toSet())
    }

    @Test fun terminalWriteFailureKeepsHonestLiveResultAndRestartUnconfirmed() = runTest {
        val directory = temporaryFolder.newFolder()
        var fail = false
        val store = newStore(directory, beforeWrite = {
            if (fail && it == DownloadHistoryWritePhase.BEFORE_SYNC) throw IOException("synthetic failure")
        })
        val receipt = store.begin(store.captureRequest(), "PHOTO.JPG", DownloadHistoryDestination.GALLERY)
        val pendingBytes = snapshot(directory).readBytes()
        fail = true
        store.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
        store.awaitIdle()
        assertEquals(DownloadHistoryOutcome.COMPLETED, store.state.value.entries.single().outcome)
        assertEquals(DownloadHistoryWarning.WRITE_FAILED, store.state.value.warning)
        assertArrayEquals(pendingBytes, snapshot(directory).readBytes())
        val reopened = newStore(directory)
        reopened.awaitIdle()
        assertEquals(DownloadHistoryOutcome.UNCONFIRMED, reopened.state.value.entries.single().outcome)
    }

    @Test fun laterSuccessfulWriteRecoversInMemoryReceiptsAndClearsPersistenceWarning() = runTest {
        val directory = temporaryFolder.newFolder()
        var fail = true
        val store = newStore(directory, beforeWrite = {
            if (fail && it == DownloadHistoryWritePhase.BEFORE_TEMP_WRITE) throw IOException("synthetic failure")
        })
        val receipt = store.begin(store.captureRequest(), "PHOTO.JPG", DownloadHistoryDestination.GALLERY)
        assertEquals(DownloadHistoryWarning.WRITE_FAILED, store.state.value.warning)
        assertFalse(snapshot(directory).exists())
        fail = false
        store.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
        store.awaitIdle()
        assertNull(store.state.value.warning)
        assertEquals(DownloadHistoryOutcome.COMPLETED, DownloadHistoryFileStorage(directory).read().single().outcome)
    }

    @Test fun nonterminalNotificationsCannotRewriteTheJournal() = runTest {
        val directory = temporaryFolder.newFolder()
        var writes = 0
        val store = newStore(directory, beforeWrite = {
            if (it == DownloadHistoryWritePhase.BEFORE_MOVE) writes++
        })
        val receipt = store.begin(store.captureRequest(), "PHOTO.JPG", DownloadHistoryDestination.GALLERY)
        repeat(100) { store.recordFinished(receipt, DownloadHistoryOutcome.IN_PROGRESS) }
        store.recordFinished(receipt, DownloadHistoryOutcome.UNCONFIRMED)
        store.awaitIdle()
        assertEquals(1, writes)
        assertEquals(DownloadHistoryOutcome.IN_PROGRESS, DownloadHistoryFileStorage(directory).read().single().outcome)
    }

    @Test fun cancellationAfterAdmissionRetainsReceiptForExplicitCancelledOutcome() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        var receipt: DownloadHistoryReceipt? = null
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    receipt = store.begin(store.captureRequest(), "CANCEL.JPG", DownloadHistoryDestination.GALLERY)
                }
                coroutineContext.ensureActive()
            } finally {
                store.recordFinished(receipt, DownloadHistoryOutcome.CANCELLED)
            }
        }
        operation.cancel()
        operation.join()
        store.awaitIdle()
        assertNotNull(receipt)
        assertEquals(DownloadHistoryOutcome.CANCELLED, DownloadHistoryFileStorage(directory).read().single().outcome)
    }

    @Test fun cancellingClearCallerDoesNotCancelProcessOwnedReset() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val oldRequest = store.captureRequest()
        store.begin(oldRequest, "ACTIVE.JPG", DownloadHistoryDestination.GALLERY)
        val clearing = async(start = CoroutineStart.UNDISPATCHED) { store.clear() }
        clearing.cancel()
        clearing.join()
        store.awaitIdle()
        assertTrue(DownloadHistoryFileStorage(directory).read().isEmpty())
        assertNull(store.begin(oldRequest, "LATE.JPG", DownloadHistoryDestination.GALLERY))
    }

    @Test fun closedProcessOwnerRejectsNewWorkAndReleasesPendingWaiters() = runTest {
        val directory = temporaryFolder.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val store = DownloadHistoryStore(directory, owner, dispatcher)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            store.begin(store.captureRequest(), "PHOTO.JPG", DownloadHistoryDestination.GALLERY)
        }
        owner.cancel()
        runCurrent()
        assertNull(waiting.await())
        assertFalse(store.clear())
        assertEquals(DownloadHistoryWarning.STOPPED, store.state.value.warning)
        assertFalse(snapshot(directory).exists())
    }

    @Test fun receiptFromAnotherStoreCannotFinishOrBeginHere() = runTest {
        val first = newStore(temporaryFolder.newFolder())
        val secondDirectory = temporaryFolder.newFolder()
        val second = newStore(secondDirectory)
        val request = first.captureRequest()
        val receipt = first.begin(request, "FIRST.JPG", DownloadHistoryDestination.GALLERY)
        assertNull(second.begin(request, "WRONG.JPG", DownloadHistoryDestination.GALLERY))
        second.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
        second.awaitIdle()
        assertTrue(second.state.value.entries.isEmpty())
        assertFalse(snapshot(secondDirectory).exists())
    }

    @Test fun fiftySelectedRowsRestartAsFiveCompletedOneUnknownAndFortyFourQueued() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val names = List(50) { "SELECTED_$it.JPG" }
        val token = store.admittedWorklist(names)
        assertEquals(names, DownloadHistoryFileStorage(directory).readSnapshot().worklists.single().rows.map { it.filename })
        assertTrue(store.state.value.worklists.single().rows.all { it.receipt == null })
        repeat(5) { index ->
            val receipt = store.startedRow(token, index)
            store.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
        }
        store.startedRow(token, 5)
        store.awaitIdle()
        val beforeRestart = snapshot(directory).readBytes()
        val reopened = newStore(directory)
        reopened.awaitIdle()
        val rows = reopened.state.value.worklists.single().rows
        assertEquals(names, rows.map { it.filename })
        assertEquals(5, rows.count { it.receipt?.outcome == DownloadHistoryOutcome.COMPLETED })
        assertEquals(1, rows.count { it.receipt?.outcome == DownloadHistoryOutcome.UNCONFIRMED })
        assertEquals(44, rows.count { it.receipt == null })
        assertTrue(reopened.state.value.entries.isEmpty())
        assertArrayEquals(beforeRestart, snapshot(directory).readBytes())
        // A disk roster does not carry a live owner capability into another process/store.
        assertEquals(DownloadWorklistFailure.INVALID_REQUEST,
            (reopened.beginWorklistRow(token, 6) as DownloadWorklistRowStart.Rejected).reason)
        assertArrayEquals(beforeRestart, snapshot(directory).readBytes())
    }

    @Test fun wholePlanAdmissionIsAtomicAndPreMoveFailureProvesPlanAbsent() = runTest {
        for (phase in DownloadHistoryWritePhase.entries.filter { it != DownloadHistoryWritePhase.AFTER_MOVE }) {
            val directory = temporaryFolder.newFolder()
            snapshot(directory).writeText(fixtureJson().toString())
            val original = snapshot(directory).readBytes()
            val store = newStore(directory, beforeWrite = {
                if (it == phase) throw IOException("synthetic-private failure")
            })
            val result = store.admitWorklist(store.captureRequest(), List(50) { "SELECTED_$it.JPG" }, DownloadHistoryDestination.FOLDER)
                as DownloadWorklistAdmission.Rejected
            assertEquals(DownloadWorklistFailure.WRITE_FAILED, result.reason)
            assertTrue(result.planAbsent)
            assertTrue(store.state.value.worklists.isEmpty())
            assertEquals(listOf(fixtureEntry()), store.state.value.entries)
            assertEquals(DownloadHistoryWarning.WRITE_FAILED, store.state.value.warning)
            assertArrayEquals(original, snapshot(directory).readBytes())
            assertEquals(listOf(DOWNLOAD_HISTORY_SNAPSHOT_NAME), directory.list()!!.toList())
        }
    }

    @Test fun admissionThrowAfterMoveNeverReportsAbsentOrRollsBackInstalledWholePlan() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory, beforeWrite = {
            if (it == DownloadHistoryWritePhase.AFTER_MOVE) throw IOException("synthetic-after-commit")
        })
        val result = store.admitWorklist(store.captureRequest(), List(50) { "SELECTED_$it.JPG" }, DownloadHistoryDestination.FOLDER)
            as DownloadWorklistAdmission.Rejected
        assertEquals(DownloadWorklistFailure.WRITE_FAILED, result.reason)
        assertFalse(result.planAbsent)
        assertEquals(50, store.state.value.worklists.single().rows.size)
        assertTrue(store.state.value.worklists.single().rows.all { it.receipt == null })
        assertEquals(store.state.value.worklists, DownloadHistoryFileStorage(directory).readSnapshot().worklists)
        assertEquals(DownloadHistoryWarning.WRITE_FAILED, store.state.value.warning)
    }

    @Test fun unreadablePostCommitReadbackDisablesFurtherWritesAndCannotClaimPlanAbsent() = runTest {
        val directory = temporaryFolder.newFolder()
        var writes = 0
        val store = newStore(directory, beforeWrite = {
            if (it == DownloadHistoryWritePhase.AFTER_MOVE) {
                writes++
                snapshot(directory).writeText("{synthetic-unreadable-after-install")
                throw IOException("synthetic-after-commit")
            }
        })
        val result = store.admitWorklist(store.captureRequest(), listOf("PHOTO.JPG"), DownloadHistoryDestination.GALLERY)
            as DownloadWorklistAdmission.Rejected
        assertFalse(result.planAbsent)
        assertFalse(store.state.value.writable)
        assertEquals(DownloadWorklistFailure.WRITE_FAILED, result.reason)
        assertNull(store.begin(store.captureRequest(), "LATE.JPG", DownloadHistoryDestination.DOCUMENT))
        assertEquals(DownloadWorklistFailure.STORAGE_UNAVAILABLE,
            (store.admitWorklist(store.captureRequest(), listOf("LATE.JPG"), DownloadHistoryDestination.GALLERY)
                as DownloadWorklistAdmission.Rejected).reason)
        assertEquals(1, writes)
        assertEquals("{synthetic-unreadable-after-install", snapshot(directory).readText())
    }

    @Test fun eachRowAdmissionFailurePreservesWholeRosterAndNeverReturnsReceipt() = runTest {
        for (phase in DownloadHistoryWritePhase.entries) {
            val directory = temporaryFolder.newFolder()
            var fail = false
            val store = newStore(directory, beforeWrite = {
                if (fail && it == phase) throw IOException("synthetic-row-failure")
            })
            val token = store.admittedWorklist(listOf("FIRST.JPG", "SECOND.JPG", "THIRD.JPG"))
            val first = store.startedRow(token, 0)
            store.recordFinished(first, DownloadHistoryOutcome.COMPLETED)
            store.awaitIdle()
            val before = snapshot(directory).readBytes()
            fail = true
            assertEquals(DownloadWorklistFailure.WRITE_FAILED,
                (store.beginWorklistRow(token, 1) as DownloadWorklistRowStart.Rejected).reason)
            val rows = store.state.value.worklists.single().rows
            assertEquals(3, rows.size)
            assertEquals(DownloadHistoryOutcome.COMPLETED, rows[0].receipt!!.outcome)
            assertNull(rows[2].receipt)
            if (phase == DownloadHistoryWritePhase.AFTER_MOVE) {
                assertEquals(DownloadHistoryOutcome.UNCONFIRMED, rows[1].receipt!!.outcome)
                assertEquals(DownloadHistoryOutcome.IN_PROGRESS,
                    DownloadHistoryFileStorage(directory).readSnapshot().worklists.single().rows[1].receipt!!.outcome)
                assertEquals(DownloadWorklistFailure.ROW_NOT_QUEUED,
                    (store.beginWorklistRow(token, 1) as DownloadWorklistRowStart.Rejected).reason)
            } else {
                assertNull(rows[1].receipt)
                assertArrayEquals(before, snapshot(directory).readBytes())
            }
        }
    }

    @Test fun sharedCapRejectsWholePlanWithoutEvictionAndLegacyOnlyEvictsTerminalLegacy() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        store.admittedWorklist(List(60) { "SELECTED_$it.JPG" })
        repeat(40) {
            val receipt = store.begin(store.captureRequest(), "LEGACY_$it.JPG", DownloadHistoryDestination.GALLERY)
            store.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
        }
        store.awaitIdle()
        val plans = store.state.value.worklists
        val before = snapshot(directory).readBytes()
        val result = store.admitWorklist(store.captureRequest(), listOf("EXTRA.JPG"), DownloadHistoryDestination.FOLDER)
            as DownloadWorklistAdmission.Rejected
        assertEquals(DownloadWorklistFailure.CAPACITY, result.reason)
        assertTrue(result.planAbsent)
        assertArrayEquals(before, snapshot(directory).readBytes())
        assertNotNull(store.begin(store.captureRequest(), "NEW_LEGACY.JPG", DownloadHistoryDestination.GALLERY))
        assertEquals(plans, store.state.value.worklists)
        assertEquals(40, store.state.value.entries.size)
        assertFalse(store.state.value.entries.any { it.filename == "LEGACY_0.JPG" })
        assertEquals(100, DownloadHistoryFileStorage(directory).readSnapshot().let { it.entries.size + it.worklists.sumOf { plan -> plan.rows.size } })
    }

    @Test fun liveAndColdUnknownLegacyRowsAreNeverEvictedAtCapacity() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        repeat(100) { assertNotNull(store.begin(store.captureRequest(), "LIVE_$it.JPG", DownloadHistoryDestination.GALLERY)) }
        val before = snapshot(directory).readBytes()
        assertNull(store.begin(store.captureRequest(), "MUST_NOT_EVICT.JPG", DownloadHistoryDestination.GALLERY))
        assertEquals(DownloadHistoryWarning.CAPACITY_REACHED, store.state.value.warning)
        val reopened = newStore(directory)
        reopened.awaitIdle()
        assertTrue(reopened.state.value.entries.all { it.outcome == DownloadHistoryOutcome.UNCONFIRMED })
        assertNull(reopened.begin(reopened.captureRequest(), "MUST_NOT_EVICT_UNKNOWN.JPG", DownloadHistoryDestination.GALLERY))
        assertArrayEquals(before, snapshot(directory).readBytes())
    }

    @Test fun evenCompletedWorklistsAreRetainedAndOversizedOrEmptyPlansAreNotTruncated() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val token = store.admittedWorklist(listOf("DONE.JPG"))
        store.recordFinished(store.startedRow(token, 0), DownloadHistoryOutcome.COMPLETED)
        repeat(99) { store.begin(store.captureRequest(), "LIVE_$it.JPG", DownloadHistoryDestination.GALLERY) }
        store.awaitIdle()
        val before = snapshot(directory).readBytes()
        assertNull(store.begin(store.captureRequest(), "EXTRA.JPG", DownloadHistoryDestination.GALLERY))
        assertEquals(DownloadWorklistFailure.CAPACITY,
            (store.admitWorklist(store.captureRequest(), List(101) { "X.JPG" }, DownloadHistoryDestination.GALLERY)
                as DownloadWorklistAdmission.Rejected).reason)
        assertEquals(DownloadWorklistFailure.INVALID_SELECTION,
            (store.admitWorklist(store.captureRequest(), emptyList(), DownloadHistoryDestination.GALLERY)
                as DownloadWorklistAdmission.Rejected).reason)
        assertArrayEquals(before, snapshot(directory).readBytes())
        assertEquals(DownloadHistoryOutcome.COMPLETED, store.state.value.worklists.single().rows.single().receipt!!.outcome)
    }

    @Test fun throwingAtomicMoveNeverClaimsPlanAbsentEvenWhenOldBytesRemain() = runTest {
        val directory = temporaryFolder.newFolder()
        val storage = DownloadHistoryFileStorage.withAtomicMoveForTest(directory) { _, _ -> throw IOException("move-unknown") }
        val store = DownloadHistoryStore(storage, backgroundScope, StandardTestDispatcher(testScheduler), { 1000L })
        val result = store.admitWorklist(store.captureRequest(), listOf("FIRST.JPG"), DownloadHistoryDestination.FOLDER)
            as DownloadWorklistAdmission.Rejected
        assertFalse(result.planAbsent)
        assertFalse(snapshot(directory).exists())
        assertTrue(store.state.value.worklists.isEmpty())
    }

    @Test fun atomicMoveThenThrowPreservesInstalledPlanAndUnconfirmedRowWithoutCapabilities() = runTest {
        val directory = temporaryFolder.newFolder()
        var fail = true
        val storage = DownloadHistoryFileStorage.withAtomicMoveForTest(directory) { source, destination ->
            java.nio.file.Files.move(source, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            if (fail) throw IOException("move-completion-unknown")
        }
        val store = DownloadHistoryStore(storage, backgroundScope, StandardTestDispatcher(testScheduler), { 1000L })
        val rejected = store.admitWorklist(store.captureRequest(), listOf("UNKNOWN.JPG"), DownloadHistoryDestination.FOLDER)
            as DownloadWorklistAdmission.Rejected
        assertFalse(rejected.planAbsent)
        assertEquals(1, storage.readSnapshot().worklists.size)
        fail = false
        assertTrue(store.clear())
        val token = store.admittedWorklist(listOf("FIRST.JPG", "SECOND.JPG"))
        fail = true
        assertTrue(store.beginWorklistRow(token, 0) is DownloadWorklistRowStart.Rejected)
        assertEquals(DownloadHistoryOutcome.UNCONFIRMED, store.state.value.worklists.single().rows[0].receipt!!.outcome)
        assertEquals(DownloadHistoryOutcome.IN_PROGRESS, storage.readSnapshot().worklists.single().rows[0].receipt!!.outcome)
        assertNull(store.state.value.worklists.single().rows[1].receipt)
        assertFalse(store.clear())
        assertTrue(store.state.value.worklists.isEmpty())
        assertEquals(DownloadWorklistFailure.RETIRED_REQUEST,
            (store.beginWorklistRow(token, 1) as DownloadWorklistRowStart.Rejected).reason)
    }

    @Test fun ambiguousEmptyClearDisablesWritesAndCannotReconstructMemoryOnlyReceipt() = runTest {
        for (install in listOf(false, true)) {
            val directory = temporaryFolder.newFolder()
            var calls = 0
            val storage = DownloadHistoryFileStorage.withAtomicMoveForTest(directory) { source, destination ->
                calls++
                if (calls > 1 && install) java.nio.file.Files.move(source, destination,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                throw IOException("move-completion-unknown")
            }
            val store = DownloadHistoryStore(storage, backgroundScope, StandardTestDispatcher(testScheduler), { 1000L })
            val receipt = store.begin(store.captureRequest(), "MEMORY_ONLY.JPG", DownloadHistoryDestination.GALLERY)
            assertNotNull(receipt)
            assertFalse(snapshot(directory).exists())
            assertFalse(store.clear())
            assertFalse(store.state.value.writable)
            val callsAtClear = calls
            store.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
            store.awaitIdle()
            assertEquals(callsAtClear, calls)
            assertEquals(DownloadHistoryOutcome.COMPLETED, store.state.value.entries.single().outcome)
            assertEquals(DownloadHistorySnapshot(), storage.readSnapshot())
            assertEquals(DownloadHistoryWarning.CLEAR_FAILED, store.state.value.warning)
        }
    }

    @Test fun liveLegacyCompletionSurvivesDisabledPersistenceWithoutWritingUnknownDisk() = runTest {
        val directory = temporaryFolder.newFolder()
        var fail = false
        val store = newStore(directory, beforeWrite = {
            if (fail && it == DownloadHistoryWritePhase.AFTER_MOVE) {
                snapshot(directory).writeText("{unknown")
                throw IOException("synthetic-uncertain-installation")
            }
        })
        val receipt = store.begin(store.captureRequest(), "LEGACY.JPG", DownloadHistoryDestination.GALLERY)
        assertNotNull(receipt)
        fail = true
        assertTrue(store.admitWorklist(store.captureRequest(), listOf("NEXT.JPG"), DownloadHistoryDestination.GALLERY)
            is DownloadWorklistAdmission.Rejected)
        assertFalse(store.state.value.writable)
        val unknownDisk = snapshot(directory).readBytes()
        store.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
        store.awaitIdle()
        assertEquals(DownloadHistoryOutcome.COMPLETED, store.state.value.entries.single().outcome)
        assertFalse(store.state.value.writable)
        assertEquals(DownloadHistoryWarning.WRITE_FAILED, store.state.value.warning)
        assertArrayEquals(unknownDisk, snapshot(directory).readBytes())
    }

    @Test fun knownCompletionSurvivesDisabledPersistenceWithoutRewritingUnknownDiskOrClearedEpoch() = runTest {
        val directory = temporaryFolder.newFolder()
        var fail = false
        val store = newStore(directory, beforeWrite = {
            if (fail && it == DownloadHistoryWritePhase.AFTER_MOVE) {
                snapshot(directory).writeText("{unknown")
                throw IOException("synthetic-uncertain-installation")
            }
        })
        val token = store.admittedWorklist(listOf("FIRST.JPG", "SECOND.JPG"))
        val first = store.startedRow(token, 0)
        fail = true
        assertTrue(store.beginWorklistRow(token, 1) is DownloadWorklistRowStart.Rejected)
        assertFalse(store.state.value.writable)
        val unknownDisk = snapshot(directory).readBytes()
        store.recordFinished(first, DownloadHistoryOutcome.COMPLETED)
        store.awaitIdle()
        assertEquals(DownloadHistoryOutcome.COMPLETED, store.state.value.worklists.single().rows[0].receipt!!.outcome)
        assertFalse(store.state.value.writable)
        assertEquals(DownloadHistoryWarning.WRITE_FAILED, store.state.value.warning)
        assertArrayEquals(unknownDisk, snapshot(directory).readBytes())
        fail = false
        assertTrue(store.clear())
        store.recordFinished(first, DownloadHistoryOutcome.COMPLETED)
        store.awaitIdle()
        assertTrue(store.state.value.worklists.isEmpty())
        assertEquals(DownloadHistorySnapshot(), DownloadHistoryFileStorage(directory).readSnapshot())
    }

    @Test fun clearRetiresPlanAndQueuedRowCommandsWithoutRebuildingOldRoster() = runTest {
        val directory = temporaryFolder.newFolder()
        var duringMove: (() -> Unit)? = null
        val store = newStore(directory, beforeWrite = {
            if (it == DownloadHistoryWritePhase.BEFORE_MOVE) duringMove?.invoke()
        })
        val request = store.captureRequest()
        val token = store.admittedWorklist(listOf("FIRST.JPG", "SECOND.JPG"), request)
        val first = store.startedRow(token, 0)
        var queued: kotlinx.coroutines.Deferred<DownloadWorklistRowStart>? = null
        var plan: kotlinx.coroutines.Deferred<DownloadWorklistAdmission>? = null
        duringMove = {
            duringMove = null
            queued = async(start = CoroutineStart.UNDISPATCHED) { store.beginWorklistRow(token, 1) }
            plan = async(start = CoroutineStart.UNDISPATCHED) {
                store.admitWorklist(request, listOf("THIRD.JPG"), DownloadHistoryDestination.GALLERY)
            }
        }
        assertTrue(store.clear())
        assertEquals(DownloadWorklistFailure.RETIRED_REQUEST, (queued!!.await() as DownloadWorklistRowStart.Rejected).reason)
        assertEquals(DownloadWorklistFailure.RETIRED_REQUEST, (plan!!.await() as DownloadWorklistAdmission.Rejected).reason)
        store.recordFinished(first, DownloadHistoryOutcome.COMPLETED)
        store.awaitIdle()
        assertTrue(store.state.value.worklists.isEmpty())
        assertEquals(DownloadHistorySnapshot(), DownloadHistoryFileStorage(directory).readSnapshot())
        assertEquals(1, store.admittedWorklist(listOf("FRESH.JPG")).let { store.state.value.worklists.size })
    }

    @Test fun postMoveClearFailureStaysVisibleButRetiresOldTokensAndCannotReconstructRows() = runTest {
        val directory = temporaryFolder.newFolder()
        var fail = false
        val store = newStore(directory, beforeWrite = {
            if (fail && it == DownloadHistoryWritePhase.AFTER_MOVE) throw IOException("synthetic-after-clear")
        })
        val token = store.admittedWorklist(listOf("FIRST.JPG", "SECOND.JPG"))
        val first = store.startedRow(token, 0)
        fail = true
        assertFalse(store.clear())
        assertEquals(DownloadHistoryWarning.CLEAR_FAILED, store.state.value.warning)
        assertTrue(store.state.value.worklists.isEmpty())
        assertEquals(DownloadWorklistFailure.RETIRED_REQUEST,
            (store.beginWorklistRow(token, 1) as DownloadWorklistRowStart.Rejected).reason)
        store.recordFinished(first, DownloadHistoryOutcome.COMPLETED)
        store.awaitIdle()
        assertEquals(DownloadHistorySnapshot(), DownloadHistoryFileStorage(directory).readSnapshot())
        assertEquals(DownloadHistoryWarning.CLEAR_FAILED, store.state.value.warning)
    }

    @Test fun postMoveClearRetiresInMemoryOnlyLegacyReceiptsEvenWhenOldDiskWasAlreadyEmpty() = runTest {
        val directory = temporaryFolder.newFolder()
        var failure = DownloadHistoryWritePhase.BEFORE_TEMP_WRITE
        val store = newStore(directory, beforeWrite = {
            if (it == failure) throw IOException("synthetic-write-failure")
        })
        val request = store.captureRequest()
        val receipt = store.begin(request, "MEMORY_ONLY.JPG", DownloadHistoryDestination.GALLERY)
        assertNotNull(receipt)
        assertFalse(snapshot(directory).exists())
        // A proved pre-write failure keeps the old contract, even with no disk receipts.
        assertFalse(store.clear())
        assertEquals(1, store.state.value.entries.size)
        failure = DownloadHistoryWritePhase.AFTER_MOVE
        assertFalse(store.clear())
        assertTrue(store.state.value.entries.isEmpty())
        assertEquals(DownloadHistoryWarning.CLEAR_FAILED, store.state.value.warning)
        store.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
        assertNull(store.begin(request, "LATE.JPG", DownloadHistoryDestination.GALLERY))
        store.awaitIdle()
        assertEquals(DownloadHistorySnapshot(), DownloadHistoryFileStorage(directory).readSnapshot())
    }

    @Test fun failedUnchangedClearPreservesPlanOwnerAndUnreadableClearDoesNotPretendRetired() = runTest {
        val directory = temporaryFolder.newFolder()
        var failure: DownloadHistoryWritePhase? = null
        val store = newStore(directory, beforeWrite = {
            if (it == failure) {
                if (it == DownloadHistoryWritePhase.AFTER_MOVE) snapshot(directory).writeText("{unknown")
                throw IOException("synthetic-clear-failure")
            }
        })
        val token = store.admittedWorklist(listOf("FIRST.JPG", "SECOND.JPG"))
        failure = DownloadHistoryWritePhase.BEFORE_MOVE
        assertFalse(store.clear())
        failure = null
        assertNotNull(store.startedRow(token, 0))
        failure = DownloadHistoryWritePhase.AFTER_MOVE
        assertFalse(store.clear())
        assertFalse(store.state.value.writable)
        assertEquals(DownloadWorklistFailure.STORAGE_UNAVAILABLE,
            (store.beginWorklistRow(token, 1) as DownloadWorklistRowStart.Rejected).reason)
        assertEquals("{unknown", snapshot(directory).readText())
    }

    @Test fun cancelledPlanAndRowWaitersKeepCapabilitiesWhenCallerTakesNonCancellableOwnership() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        var token: DownloadWorklistToken? = null
        val selection = async(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { token = store.admittedWorklist(listOf("FIRST.JPG", "SECOND.JPG")) }
            coroutineContext.ensureActive()
        }
        selection.cancel()
        selection.join()
        assertNotNull(token)
        var receipt: DownloadHistoryReceipt? = null
        val original = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { receipt = store.startedRow(token!!, 0) }
                coroutineContext.ensureActive()
            } finally { store.recordFinished(receipt, DownloadHistoryOutcome.CANCELLED) }
        }
        original.cancel()
        original.join()
        store.awaitIdle()
        assertEquals(DownloadHistoryOutcome.CANCELLED, store.state.value.worklists.single().rows[0].receipt!!.outcome)
        assertNull(store.state.value.worklists.single().rows[1].receipt)
        assertEquals(DownloadWorklistFailure.ROW_NOT_QUEUED,
            (store.beginWorklistRow(token!!, 0) as DownloadWorklistRowStart.Rejected).reason)
    }

    @Test fun stoppedOwnerRejectsQueuedPlanWaitersWithoutPretendingUserCleared() = runTest {
        val directory = temporaryFolder.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val store = DownloadHistoryStore(directory, owner, dispatcher)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            store.admitWorklist(store.captureRequest(), listOf("FIRST.JPG"), DownloadHistoryDestination.GALLERY)
        }
        owner.cancel()
        runCurrent()
        val result = waiting.await() as DownloadWorklistAdmission.Rejected
        assertEquals(DownloadWorklistFailure.STOPPED, result.reason)
        assertTrue(result.planAbsent)
        assertFalse(snapshot(directory).exists())
    }

    @Test fun oldSchemaMigrationPreservesReceiptsAndDoesNotRewriteUntilWholePlanAdmission() = runTest {
        for (schema in 1..2) {
            val directory = temporaryFolder.newFolder()
            val old = fixtureJson().put("schema", schema).apply { if (schema == 2) row().put("cleanupUnconfirmed", false) }
            snapshot(directory).writeText(old.toString())
            val before = snapshot(directory).readBytes()
            val store = newStore(directory)
            store.awaitIdle()
            assertArrayEquals(before, snapshot(directory).readBytes())
            store.admittedWorklist(listOf("QUEUED.JPG"))
            assertEquals(3, JSONObject(snapshot(directory).readText()).getInt("schema"))
            assertEquals(listOf(fixtureEntry()), DownloadHistoryFileStorage(directory).readSnapshot().entries)
            val reopened = newStore(directory)
            reopened.awaitIdle()
            assertEquals(store.state.value, reopened.state.value)
        }
    }

    @Test fun strictWorklistSchemaRejectsExtraIdentityFieldsCoercionMismatchesAndDuplicatesWithoutRewrite() = runTest {
        val seed = temporaryFolder.newFolder()
        val store = newStore(seed)
        val token = store.admittedWorklist(listOf("PHOTO.JPG", "SECOND.JPG"))
        store.startedRow(token, 0)
        val valid = snapshot(seed).readText()
        val variants = listOf<(JSONObject) -> Unit>(
            { it.put("schema", 4) },
            { it.put("camera", "synthetic") },
            { it.plan().put("cameraId", "synthetic") },
            { it.plan().put("createdAtMillis", "1000") },
            { it.plan().put("rows", JSONArray()) },
            { it.plan().put("destination", "content://synthetic/private") },
            { it.planRow().put("mediaId", "synthetic") },
            { it.planRow().put("filename", "/private/PHOTO.JPG") },
            { it.planRow().put("receipt", "pending") },
            { it.planRow().put("rowId", it.plan().getString("worklistId")) },
            { it.planRow().getJSONObject("receipt").put("receiptId", it.planRow().getString("rowId")) },
            { it.planRow().getJSONObject("receipt").put("filename", "DIFFERENT.JPG") },
            { it.planRow().getJSONObject("receipt").put("destination", "DOCUMENT") },
            { it.planRow().getJSONObject("receipt").put("outcome", "QUEUED") },
            { it.planRow().getJSONObject("receipt").put("finishedAtMillis", 1001) },
            { it.planRow().getJSONObject("receipt").put("cleanupUnconfirmed", true) },
            { it.getJSONArray("worklists").put(JSONObject(it.plan().toString())) },
            { it.put("records", JSONArray(List(99) { JSONObject(fixtureJson().row().toString())
                .put("receiptId", UUID.randomUUID().toString()).put("cleanupUnconfirmed", false) })) },
        )
        for (mutate in variants) {
            val directory = temporaryFolder.newFolder()
            val original = JSONObject(valid).apply(mutate).toString().toByteArray()
            snapshot(directory).writeBytes(original)
            val invalid = newStore(directory)
            invalid.awaitIdle()
            assertEquals(DownloadHistoryWarning.READ_FAILED, invalid.state.value.warning)
            assertFalse(invalid.state.value.writable)
            assertArrayEquals(original, snapshot(directory).readBytes())
        }
    }

    @Test fun strictOldJsonRejectsDuplicateKeysCommentsUnquotedKeysTrailingCommasAndCoercedIntegers() = runTest {
        val valid = fixtureJson().toString()
        val variants = listOf(
            valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
            valid.replace("\"schema\":1", "\"schema\":1,\"\\u0073chema\":1"),
            valid.replace("\"schema\":1", "schema:1"),
            valid.replace("\"schema\":1", "\"schema\":1/*comment*/"),
            valid.replace("\"schema\":1", "\"schema\":01"),
            valid.replace("\"schema\":1", "\"schema\":1e0"),
            valid.dropLast(1) + ",}",
        )
        for (input in variants) {
            assertNotEquals(valid, input)
            val directory = temporaryFolder.newFolder()
            snapshot(directory).writeText(input)
            val store = newStore(directory)
            store.awaitIdle()
            assertEquals(DownloadHistoryWarning.READ_FAILED, store.state.value.warning)
            assertEquals(input, snapshot(directory).readText())
        }
    }

    @Test fun rosterLabelsAreSanitizedAndEveryRepeatedNameHasIndependentLocalIds() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val inputs = listOf(
            "content://SYNTHETIC-PROVIDER/private/PHOTO.JPG",
            "https://SYNTHETIC-CAMERA/PHOTO.JPG?token=SYNTHETIC-SECRET",
            "/private/SYNTHETIC-USER/PHOTO.JPG#SYNTHETIC-SECRET",
            "C:\\private\\SYNTHETIC-USER\\PHOTO.JPG?token=SYNTHETIC-SECRET",
            "PH\nOTO\u0000.JPG\u202e", "PHOTO.JPG", "PHOTO.JPG",
        )
        val token = store.admittedWorklist(inputs)
        assertEquals(listOf("download", "download", "PHOTO.JPG", "PHOTO.JPG", "PHOTO.JPG", "PHOTO.JPG", "PHOTO.JPG"),
            store.state.value.worklists.single().rows.map { it.filename })
        val first = store.startedRow(token, 5)
        val second = store.startedRow(token, 6)
        store.recordFinished(first, DownloadHistoryOutcome.COMPLETED)
        store.recordFinished(first, DownloadHistoryOutcome.FAILED, cleanupUnconfirmed = true)
        store.recordFinished(second, DownloadHistoryOutcome.CANCELLED, cleanupUnconfirmed = true)
        store.awaitIdle()
        val plan = store.state.value.worklists.single()
        val ids = listOf(plan.worklistId) + plan.rows.map { it.rowId } + plan.rows.mapNotNull { it.receipt?.receiptId }
        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { assertEquals(4, UUID.fromString(it).version()) }
        assertEquals(DownloadHistoryOutcome.COMPLETED, plan.rows[5].receipt!!.outcome)
        assertFalse(plan.rows[5].receipt!!.cleanupUnconfirmed)
        assertTrue(plan.rows[6].receipt!!.cleanupUnconfirmed)
        val bytes = snapshot(directory).readText()
        listOf("SYNTHETIC", "content://", "https://", "/private/", "token=", "mediaId", "cameraId", "session").forEach {
            assertFalse(it, bytes.contains(it))
        }
        val reopened = newStore(directory)
        reopened.awaitIdle()
        assertEquals(plan, reopened.state.value.worklists.single())
    }

    @Test fun worklistTerminalWriteFailureKeepsLiveOutcomeButColdPendingIsUnknown() = runTest {
        val directory = temporaryFolder.newFolder()
        var fail = false
        val store = newStore(directory, beforeWrite = {
            if (fail && it == DownloadHistoryWritePhase.BEFORE_MOVE) throw IOException("synthetic-terminal-failure")
        })
        val token = store.admittedWorklist(listOf("FIRST.JPG", "SECOND.JPG"))
        val first = store.startedRow(token, 0)
        val before = snapshot(directory).readBytes()
        fail = true
        store.recordFinished(first, DownloadHistoryOutcome.COMPLETED)
        store.awaitIdle()
        assertEquals(DownloadHistoryOutcome.COMPLETED, store.state.value.worklists.single().rows[0].receipt!!.outcome)
        assertEquals(DownloadHistoryWarning.WRITE_FAILED, store.state.value.warning)
        assertArrayEquals(before, snapshot(directory).readBytes())
        val reopened = newStore(directory)
        reopened.awaitIdle()
        assertEquals(DownloadHistoryOutcome.UNCONFIRMED, reopened.state.value.worklists.single().rows[0].receipt!!.outcome)
        assertNull(reopened.state.value.worklists.single().rows[1].receipt)
    }

    private suspend fun DownloadHistoryStore.admittedWorklist(
        filenames: List<String>,
        request: DownloadHistoryRequest = captureRequest(),
    ) = (admitWorklist(request, filenames, DownloadHistoryDestination.FOLDER) as DownloadWorklistAdmission.Admitted).token

    private suspend fun DownloadHistoryStore.startedRow(token: DownloadWorklistToken, index: Int) =
        (beginWorklistRow(token, index) as DownloadWorklistRowStart.Started).receipt

    private fun JSONObject.plan(): JSONObject = getJSONArray("worklists").getJSONObject(0)
    private fun JSONObject.planRow(): JSONObject = plan().getJSONArray("rows").getJSONObject(0)

    private fun TestScope.newStore(
        directory: File,
        beforeWrite: (DownloadHistoryWritePhase) -> Unit = {},
        nowMillis: () -> Long = { 1000L },
    ) = DownloadHistoryStore(
        DownloadHistoryFileStorage(directory, beforeWrite),
        backgroundScope,
        StandardTestDispatcher(testScheduler),
        nowMillis,
    )

    private fun snapshot(directory: File) = File(directory, DOWNLOAD_HISTORY_SNAPSHOT_NAME)

    private fun fixtureEntry() = DownloadHistoryEntry(
        "f2b2dfb2-26f0-42d0-a6a4-f97d3110c70a", "FIXTURE.JPG",
        DownloadHistoryDestination.GALLERY, 1000L, 2000L, DownloadHistoryOutcome.COMPLETED,
    )

    private fun fixtureJson(): JSONObject = JSONObject().put("schema", 1).put("records", JSONArray().put(
        JSONObject().put("receiptId", fixtureEntry().receiptId).put("filename", "FIXTURE.JPG")
            .put("destination", "GALLERY").put("startedAtMillis", 1000L)
            .put("finishedAtMillis", 2000L).put("outcome", "COMPLETED"),
    ))

    private fun JSONObject.row(): JSONObject = getJSONArray("records").getJSONObject(0)
}
