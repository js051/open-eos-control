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
        for (phase in DownloadHistoryWritePhase.entries) {
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
            valid.put("schema", 2).toString().toByteArray(),
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
        repeat(MAX_DOWNLOAD_HISTORY_ENTRIES) {
            store.begin(batch, "ITEM_$it.JPG", DownloadHistoryDestination.DOCUMENT)
        }
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
        assertEquals(setOf("schema", "records"), JSONObject(bytes).keys().asSequence().toSet())
        assertEquals(setOf("receiptId", "filename", "destination", "startedAtMillis", "finishedAtMillis", "outcome"), JSONObject(bytes).row().keys().asSequence().toSet())
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
