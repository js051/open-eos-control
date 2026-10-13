package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CcapiClient
import dev.openeos.control.data.DOWNLOAD_HISTORY_SNAPSHOT_NAME
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryFileStorage
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryStore
import dev.openeos.control.data.DownloadHistoryWarning
import dev.openeos.control.data.DownloadHistoryWritePhase
import dev.openeos.control.data.MAX_DOWNLOAD_HISTORY_ENTRIES
import dev.openeos.control.data.DownloadWorklistAdmission
import dev.openeos.control.data.DownloadWorklistContinuationSelection
import dev.openeos.control.data.DownloadWorklistOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** Controlled source tests; a cold reader uses the real installed snapshot, never live state. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannedMediaDownloadBatchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun allFiftyRowsAreDurableBeforeTheFirstDestinationOrOriginalRead() = runTest(timeout = 15.seconds) {
        val directory = temporary.newFolder()
        val store = history(directory)
        val items = items(50)
        val batch = batch(store, items)
        val destination = File(temporary.newFolder(), items.first().name)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(originalResponse())
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
            assertTrue(batch.prepare())
            val planned = DownloadHistoryFileStorage(directory).readSnapshot().worklists.single()
            assertEquals(items.map { it.name }, planned.rows.map { it.filename })
            assertEquals(50, planned.rows.map { it.rowId }.distinct().size)
            assertTrue(planned.rows.all { it.receipt == null })
            assertFalse(destination.exists())
            assertEquals(0, server.requestCount)

            batch.transfer(0) { completed ->
                val installed = DownloadHistoryFileStorage(directory).readSnapshot().worklists.single()
                assertEquals(planned.worklistId, installed.worklistId)
                assertEquals(planned.rows.map { it.rowId }, installed.rows.map { it.rowId })
                assertEquals(DownloadHistoryOutcome.IN_PROGRESS, installed.rows.first().receipt?.outcome)
                assertTrue(installed.rows.drop(1).all { it.receipt == null })
                assertFalse(destination.exists())
                assertEquals(0, server.requestCount)
                withMediaOutputFinalization(cleanupIncomplete = { destination.delete() }, onFinalized = completed) { output ->
                    destination.outputStream().use { client.downloadMedia(items.first(), it) }
                    output.confirm()
                }
            }
            store.awaitIdle()
            assertArrayEquals(ORIGINAL, destination.readBytes())
            assertEquals(1, server.requestCount)
            val finished = DownloadHistoryFileStorage(directory).readSnapshot()
            assertTrue(finished.entries.isEmpty())
            assertEquals(DownloadHistoryOutcome.COMPLETED, finished.worklists.single().rows.first().receipt?.outcome)
            assertTrue(finished.worklists.single().rows.drop(1).all { it.receipt == null })
        } finally {
            server.shutdown()
        }
    }

    @Test fun coldReaderShowsFiveCompletedOneUnconfirmedAndFortyFourQueuedWithoutResuming() = runTest(timeout = 15.seconds) {
        val directory = temporary.newFolder()
        val outputs = temporary.newFolder()
        val store = history(directory)
        val items = items(50)
        val batch = batch(store, items)
        assertTrue(batch.prepare())
        repeat(5) { index ->
            batch.transfer(index) { completed ->
                File(outputs, items[index].name).writeBytes(ORIGINAL)
                completed()
            }
        }
        store.awaitIdle()
        val sixthAdmitted = CompletableDeferred<Unit>()
        var callbacks = 0
        val pending = launch {
            batch.transfer(5) {
                callbacks++
                sixthAdmitted.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            sixthAdmitted.await()
            val beforeRead = snapshot(directory).readBytes()
            val installed = DownloadHistoryFileStorage(directory).readSnapshot().worklists.single()
            assertEquals(DownloadHistoryOutcome.IN_PROGRESS, installed.rows[5].receipt?.outcome)
            // A separate store has only the disk roster, with no live token or resume callback.
            val cold = history(directory)
            cold.awaitIdle()
            val rows = cold.state.value.worklists.single().rows
            assertEquals(items.map { it.name }, rows.map { it.filename })
            assertEquals(5, rows.count { it.receipt?.outcome == DownloadHistoryOutcome.COMPLETED })
            assertEquals(DownloadHistoryOutcome.UNCONFIRMED, rows[5].receipt?.outcome)
            assertEquals(44, rows.count { it.receipt == null })
            assertTrue(rows.drop(6).all { it.receipt == null })
            assertTrue(cold.state.value.entries.isEmpty())
            assertEquals(1, callbacks)
            assertEquals(5, requireNotNull(outputs.listFiles()).size)
            items.take(5).forEach { assertArrayEquals(ORIGINAL, File(outputs, it.name).readBytes()) }
            assertArrayEquals("Cold loading must not rewrite or start this roster", beforeRead, snapshot(directory).readBytes())
        } finally {
            pending.cancelAndJoin()
        }
    }

    @Test fun everyRowWriteFailureStopsBeforeDestinationCreationOrOriginalGet() = runTest(timeout = 15.seconds) {
        val server = MockWebServer()
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
            for (failedPhase in DownloadHistoryWritePhase.entries) {
                val directory = temporary.newFolder()
                var fail = false
                val store = history(directory) { phase ->
                    if (fail && phase == failedPhase) throw IOException("Synthetic row commit failure")
                }
                val items = items(3)
                val batch = batch(store, items)
                assertTrue(batch.prepare())
                fail = true
                val destination = File(temporary.newFolder(), items.first().name)
                var actions = 0
                // A regression that wrongly opens later rows must fail assertions, not wait
                // forever for an unqueued HTTP response.
                repeat(items.size) { server.enqueue(originalResponse()) }
                val failure = runCatching {
                    executeMediaBatch(items, MediaBatchOperation.DOWNLOAD) { item ->
                        actions++
                        batch.transfer(items.indexOf(item)) { completed ->
                            destination.outputStream().use { client.downloadMedia(item, it) }
                            completed()
                        }
                    }
                }.exceptionOrNull()
                assertTrue("$failedPhase must stop the roster", failure is MediaWorklistAdmissionException)
                assertEquals(ADMISSION_FAILED, failure?.message)
                assertEquals(1, actions)
                assertFalse(destination.exists())
                assertEquals(0, server.requestCount)
                assertEquals(DownloadHistoryWarning.WRITE_FAILED, store.state.value.warning)
                val rows = DownloadHistoryFileStorage(directory).readSnapshot().worklists.single().rows
                assertTrue(rows.drop(1).all { it.receipt == null })
                if (failedPhase == DownloadHistoryWritePhase.AFTER_MOVE) {
                    assertEquals(DownloadHistoryOutcome.IN_PROGRESS, rows.first().receipt?.outcome)
                    assertEquals(DownloadHistoryOutcome.UNCONFIRMED, store.state.value.worklists.single().rows.first().receipt?.outcome)
                } else {
                    assertTrue(rows.all { it.receipt == null })
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun confirmedAbsentPlanWriteReturnsWarningSignalAndKeepsManualSaveAvailable() = runTest(timeout = 15.seconds) {
        val directory = temporary.newFolder()
        val store = history(directory) { phase ->
            if (phase == DownloadHistoryWritePhase.BEFORE_MOVE) throw IOException("Synthetic plan write failure")
        }
        val items = items(2)
        val batch = batch(store, items)
        assertFalse("False asks the caller to show the manual-save warning", batch.prepare())
        assertEquals(DownloadHistoryWarning.WRITE_FAILED, store.state.value.warning)
        val destination = File(temporary.newFolder(), items.first().name)
        batch.transfer(0) { completed ->
            destination.writeBytes(ORIGINAL)
            completed()
        }
        store.awaitIdle()
        assertArrayEquals(ORIGINAL, destination.readBytes())
        assertTrue(DownloadHistoryFileStorage(directory).readSnapshot().worklists.isEmpty())
        assertTrue(store.state.value.entries.isEmpty())
        assertTrue(store.state.value.worklists.isEmpty())
    }

    @Test fun capacityRejectionDoesNotEvictOrTruncateAndStillAllowsManualSave() = runTest(timeout = 15.seconds) {
        val directory = temporary.newFolder()
        val store = history(directory)
        assertTrue(batch(store, items(MAX_DOWNLOAD_HISTORY_ENTRIES)).prepare())
        val before = snapshot(directory).readBytes()
        val overflow = items(2)
        val batch = batch(store, overflow)
        assertFalse("The caller must warn that this entire selection is untracked", batch.prepare())
        val outputs = temporary.newFolder()
        repeat(overflow.size) { index ->
            batch.transfer(index) { completed ->
                File(outputs, overflow[index].name).writeBytes(ORIGINAL)
                completed()
            }
        }
        store.awaitIdle()
        assertArrayEquals(before, snapshot(directory).readBytes())
        assertEquals(MAX_DOWNLOAD_HISTORY_ENTRIES, store.state.value.worklists.single().rows.size)
        assertTrue(store.state.value.worklists.single().rows.all { it.receipt == null })
        overflow.forEach { assertArrayEquals(ORIGINAL, File(outputs, it.name).readBytes()) }
    }

    @Test fun installedButUnconfirmedPlanStopsInsteadOfFallingBackToManual() = runTest(timeout = 15.seconds) {
        verifyUnknownPlanCommit(corruptReadback = false)
    }

    @Test fun unreadablePlanCommitStopsInsteadOfFallingBackToManual() = runTest(timeout = 15.seconds) {
        verifyUnknownPlanCommit(corruptReadback = true)
    }

    private suspend fun TestScope.verifyUnknownPlanCommit(corruptReadback: Boolean) {
        val directory = temporary.newFolder()
        val store = history(directory) { phase ->
            if (phase == DownloadHistoryWritePhase.AFTER_MOVE) {
                if (corruptReadback) snapshot(directory).writeText("{")
                throw IOException("Synthetic uncertain plan commit")
            }
        }
        val items = items(50)
        val batch = batch(store, items)
        val destination = File(temporary.newFolder(), items.first().name)
        var attempted = false
        val failure = runCatching {
            batch.prepare()
            batch.transfer(0) { completed ->
                attempted = true
                destination.writeBytes(ORIGINAL)
                completed()
            }
        }.exceptionOrNull()
        assertTrue(failure is MediaWorklistAdmissionException)
        assertEquals(ADMISSION_UNKNOWN, failure?.message)
        assertFalse(attempted)
        assertFalse(destination.exists())
        assertEquals(DownloadHistoryWarning.WRITE_FAILED, store.state.value.warning)
        if (corruptReadback) {
            assertFalse(store.state.value.writable)
            assertEquals("{", snapshot(directory).readText())
        } else {
            val rows = DownloadHistoryFileStorage(directory).readSnapshot().worklists.single().rows
            assertEquals(50, rows.size)
            assertTrue(rows.all { it.receipt == null })
        }
    }

    @Test fun cancellationWhileWholePlanCommitWaitsLeavesOnlyQueuedRowsAndNoOutput() = runTest(timeout = 15.seconds) {
        verifyCommitCancellation(cancelDuringPlan = true)
    }

    @Test fun cancellationWhileRowCommitWaitsOwnsAndCancelsReceiptBeforeAnyOutput() = runTest(timeout = 15.seconds) {
        verifyCommitCancellation(cancelDuringPlan = false)
    }

    private suspend fun TestScope.verifyCommitCancellation(cancelDuringPlan: Boolean) {
        val directory = temporary.newFolder()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val gateNextWrite = AtomicBoolean(cancelDuringPlan)
        val store = DownloadHistoryStore(DownloadHistoryFileStorage(directory) { phase ->
            if (phase == DownloadHistoryWritePhase.BEFORE_MOVE && gateNextWrite.compareAndSet(true, false)) {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS)) { "Synthetic commit gate was not released" }
            }
        }, backgroundScope, Dispatchers.IO, { 1_000L })
        val items = items(3)
        val batch = batch(store, items)
        if (!cancelDuringPlan) {
            assertTrue(batch.prepare())
            gateNextWrite.set(true)
        }
        val destination = File(temporary.newFolder(), items.first().name)
        var callbacks = 0
        val transfer = launch {
            if (cancelDuringPlan) batch.prepare()
            executeMediaBatch(items, MediaBatchOperation.DOWNLOAD) { item ->
                batch.transfer(items.indexOf(item)) { completed ->
                    callbacks++
                    destination.writeBytes(ORIGINAL)
                    completed()
                }
            }
        }
        try {
            // The writer blocks on real I/O. Use a real-clock bound, not runTest's virtual clock.
            withContext(Dispatchers.Default) { withTimeout(5_000L) { entered.await() } }
            transfer.cancel(CancellationException("Synthetic cancel while journal is committing"))
            assertFalse("Admission must still resolve ownership", transfer.isCompleted)
            assertFalse(destination.exists())
            release.countDown()
            transfer.join()
            store.awaitIdle()
            assertTrue(transfer.isCancelled)
            assertEquals(0, callbacks)
            assertFalse(destination.exists())
            val rows = DownloadHistoryFileStorage(directory).readSnapshot().worklists.single().rows
            assertEquals(3, rows.size)
            assertTrue(rows.drop(1).all { it.receipt == null })
            if (cancelDuringPlan) assertNull(rows.first().receipt)
            else assertEquals(DownloadHistoryOutcome.CANCELLED, rows.first().receipt?.outcome)
        } finally {
            release.countDown()
            transfer.cancelAndJoin()
        }
    }

    @Test fun sameIdReplacementDuringPlanAdmissionRetiresExactOwner() = runTest(timeout = 15.seconds) {
        verifyOwnerReplacement(replaceDuringPlan = true)
    }

    @Test fun sameIdReplacementDuringRowAdmissionCancelsOnlyTheAdmittedReceipt() = runTest(timeout = 15.seconds) {
        verifyOwnerReplacement(replaceDuringPlan = false)
    }

    private suspend fun TestScope.verifyOwnerReplacement(replaceDuringPlan: Boolean) {
        data class Owner(val requestId: String)
        val captured = Owner("synthetic-same-request-id")
        var current = captured
        var replaceOnNextWrite = replaceDuringPlan
        val replaced = CompletableDeferred<Unit>()
        val directory = temporary.newFolder()
        val store = history(directory) { phase ->
            if (phase == DownloadHistoryWritePhase.BEFORE_MOVE && replaceOnNextWrite) {
                replaceOnNextWrite = false
                current = Owner(captured.requestId)
                replaced.complete(Unit)
            }
        }
        val items = items(3)
        val batch = batch(store, items, isCurrent = { current === captured })
        if (!replaceDuringPlan) {
            assertTrue(batch.prepare())
            replaceOnNextWrite = true
        }
        var callbacks = 0
        val transfer = launch {
            if (replaceDuringPlan) batch.prepare()
            batch.transfer(0) { callbacks++ }
        }
        try {
            replaced.await()
            transfer.join()
            store.awaitIdle()
            assertEquals(captured.requestId, current.requestId)
            assertFalse(captured === current)
            assertTrue(transfer.isCancelled)
            assertEquals(0, callbacks)
            val rows = DownloadHistoryFileStorage(directory).readSnapshot().worklists.single().rows
            assertTrue(rows.drop(1).all { it.receipt == null })
            if (replaceDuringPlan) assertNull(rows.first().receipt)
            else assertEquals(DownloadHistoryOutcome.CANCELLED, rows.first().receipt?.outcome)
        } finally {
            transfer.cancelAndJoin()
        }
    }

    @Test fun clearDuringFirstSavePreservesRealCopiesAndLaterRowsDoNotRebuildHistory() = runTest(timeout = 15.seconds) {
        val directory = temporary.newFolder()
        val store = history(directory)
        val items = items(3)
        val batch = batch(store, items)
        val outputs = temporary.newFolder()
        val firstCopyClosed = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var finalized = 0
        var result: MediaBatchResult? = null
        assertTrue(batch.prepare())
        val transfer = launch {
            result = executeMediaBatch(items, MediaBatchOperation.DOWNLOAD) { item ->
                batch.transfer(items.indexOf(item)) { completed ->
                    val destination = File(outputs, item.name)
                    withMediaOutputFinalization(cleanupIncomplete = { destination.delete() }, onFinalized = { completed(); finalized++ }) { output ->
                        destination.writeBytes(ORIGINAL)
                        if (item == items.first()) {
                            firstCopyClosed.complete(Unit)
                            releaseFirst.await()
                        }
                        output.confirm()
                    }
                }
            }
        }
        try {
            firstCopyClosed.await()
            assertTrue(store.clear())
            assertTrue(DownloadHistoryFileStorage(directory).readSnapshot().worklists.isEmpty())
            assertArrayEquals(ORIGINAL, File(outputs, items.first().name).readBytes())
            releaseFirst.complete(Unit)
            transfer.join()
            store.awaitIdle()
            assertFalse(transfer.isCancelled)
            assertEquals(3, finalized)
            assertEquals(3, requireNotNull(result).succeededItems)
            items.forEach { assertArrayEquals(ORIGINAL, File(outputs, it.name).readBytes()) }
            val cold = history(directory)
            cold.awaitIdle()
            assertTrue(cold.state.value.entries.isEmpty())
            assertTrue(cold.state.value.worklists.isEmpty())
        } finally {
            releaseFirst.complete(Unit)
            transfer.cancelAndJoin()
        }
    }

    @Test fun finalizedCopyStaysCompletedWhenCancellationArrivesBeforeTransferReturns() = runTest(timeout = 15.seconds) {
        val directory = temporary.newFolder()
        val store = history(directory)
        val batch = batch(store, items(2))
        assertTrue(batch.prepare())
        val destination = temporary.newFile()
        val finalized = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleanups = 0
        val transfer = launch {
            batch.transfer(0) { completed ->
                withMediaOutputFinalization(cleanupIncomplete = { cleanups++; destination.delete() }, onFinalized = completed) { output ->
                    destination.writeBytes(ORIGINAL)
                    output.confirm()
                    output.confirm()
                    withContext(NonCancellable) {
                        finalized.complete(Unit)
                        release.await()
                    }
                }
            }
        }
        try {
            finalized.await()
            transfer.cancel()
            release.complete(Unit)
            transfer.join()
            store.awaitIdle()
            assertTrue(transfer.isCancelled)
            assertEquals(0, cleanups)
            assertArrayEquals(ORIGINAL, destination.readBytes())
            val cold = history(directory)
            cold.awaitIdle()
            val rows = cold.state.value.worklists.single().rows
            assertEquals(DownloadHistoryOutcome.COMPLETED, rows.first().receipt?.outcome)
            assertNull(rows.last().receipt)
        } finally {
            release.complete(Unit)
            transfer.cancelAndJoin()
        }
    }

    @Test fun failedCleanupKeepsReceiptRiskAndOrdinaryFailureStillAllowsNextPlannedRow() = runTest(timeout = 15.seconds) {
        val directory = temporary.newFolder()
        val store = history(directory)
        val items = items(2)
        val batch = batch(store, items)
        val outputs = temporary.newFolder()
        var attempts = 0
        var cleanups = 0
        assertTrue(batch.prepare())
        val result = executeMediaBatch(items, MediaBatchOperation.DOWNLOAD) { item ->
            batch.transfer(items.indexOf(item)) { completed ->
                retryMediaRead(longArrayOf(0, 0)) {
                    val destination = File(outputs, item.name)
                    withMediaOutputFinalization(cleanupIncomplete = {
                        cleanups++
                        throw IOException("Synthetic provider refused partial cleanup")
                    }, onFinalized = completed) { output ->
                        if (item == items.first()) {
                            attempts++
                            destination.writeBytes(ORIGINAL.take(2).toByteArray())
                            throw IOException("Synthetic original read interrupted")
                        }
                        destination.writeBytes(ORIGINAL)
                        output.confirm()
                    }
                }
            }
        }
        store.awaitIdle()
        assertEquals(1, attempts)
        assertEquals(1, cleanups)
        assertEquals(1, result.succeededItems)
        assertEquals(listOf(items.first().name), result.failedItemNames)
        assertArrayEquals(ORIGINAL.take(2).toByteArray(), File(outputs, items.first().name).readBytes())
        assertArrayEquals(ORIGINAL, File(outputs, items.last().name).readBytes())
        val cold = history(directory)
        cold.awaitIdle()
        val rows = cold.state.value.worklists.single().rows
        assertEquals(DownloadHistoryOutcome.FAILED, rows.first().receipt?.outcome)
        assertTrue(requireNotNull(rows.first().receipt).cleanupUnconfirmed)
        assertEquals(DownloadHistoryOutcome.COMPLETED, rows.last().receipt?.outcome)
        assertFalse(requireNotNull(rows.last().receipt).cleanupUnconfirmed)
    }

    @Test fun typedAdmissionFailureStopsDownloadQueueWithoutMarkingLaterRowsFailed() = runTest(timeout = 15.seconds) {
        val items = items(3)
        val visited = mutableListOf<String>()
        val progress = mutableListOf<MediaBatchProgress>()
        val admissionFailure = MediaWorklistAdmissionException(ADMISSION_FAILED)
        val failure = runCatching {
            executeMediaBatch(items, MediaBatchOperation.DOWNLOAD, onProgress = { progress += it }) { item ->
                visited += item.name
                throw admissionFailure
            }
        }.exceptionOrNull()
        assertSame(admissionFailure, failure)
        assertEquals(listOf(items.first().name), visited)
        assertEquals(1, progress.size)
        assertEquals(0, progress.single().completedItems)
    }

    @Test fun ordinaryDownloadFailureContinuesRemainingRows() = runTest(timeout = 15.seconds) {
        val items = items(3)
        val visited = mutableListOf<String>()
        val result = executeMediaBatch(items, MediaBatchOperation.DOWNLOAD) { item ->
            visited += item.name
            if (item == items.first()) throw IOException("Synthetic ordinary original read failure")
        }
        assertEquals(items.map { it.name }, visited)
        assertEquals(2, result.succeededItems)
        assertEquals(listOf(items.first().name), result.failedItemNames)
        assertEquals(3, result.totalItems)
    }

    @Test fun typedAdmissionFailureDoesNotChangeNonDownloadOperationControls() = runTest(timeout = 15.seconds) {
        val items = items(3)
        for (operation in MediaBatchOperation.entries.filter { it != MediaBatchOperation.DOWNLOAD }) {
            val visited = mutableListOf<String>()
            val result = executeMediaBatch(items, operation) { item ->
                visited += item.name
                if (item == items.first()) throw MediaWorklistAdmissionException(ADMISSION_FAILED)
            }
            assertEquals("$operation must keep its existing per-item failure handling", items.map { it.name }, visited)
            assertEquals(2, result.succeededItems)
            assertEquals(listOf(items.first().name), result.failedItemNames)
            assertEquals(operation, result.operation)
        }
    }

    @Test fun continuationCannotFallBackAfterCapacityOrStorageAdmissionFailure() = runTest {
        for (full in listOf(false, true)) {
            var fail = false
            val store = history(temporary.newFolder()) { phase ->
                if (fail && phase == DownloadHistoryWritePhase.BEFORE_MOVE) throw IOException("Synthetic failure")
            }
            val token = (store.admitWorklist(store.captureRequest(), List(if (full) 100 else 1) { "OLD.JPG" },
                DownloadHistoryDestination.FOLDER) as DownloadWorklistAdmission.Admitted).token
            store.releaseWorklist(token)
            store.awaitIdle()
            val original = store.state.value.worklists.single()
            val continuation = PlannedMediaDownloadBatch(store, store.captureRequest(), listOf("NEW.JPG"),
                DownloadHistoryDestination.FOLDER, { true }, ADMISSION_FAILED, ADMISSION_UNKNOWN,
                listOf(DownloadWorklistContinuationSelection(DownloadWorklistOrigin(original.worklistId,
                    original.rows.first().rowId), "NEW.JPG")))
            fail = !full
            assertTrue(runCatching { continuation.prepare() }.exceptionOrNull() is MediaWorklistAdmissionException)
            var opened = false
            assertTrue(runCatching { continuation.transfer(0) { opened = true } }.exceptionOrNull() is MediaWorklistAdmissionException)
            assertFalse(opened)
            assertEquals(listOf(original), store.state.value.worklists)
            continuation.release()
        }
    }

    @Test fun clearBetweenContinuationRowsStopsWithoutUntrackedTransfer() = runTest {
        val store = history(temporary.newFolder())
        val token = (store.admitWorklist(store.captureRequest(), listOf("OLD.JPG"),
            DownloadHistoryDestination.FOLDER) as DownloadWorklistAdmission.Admitted).token
        store.releaseWorklist(token)
        store.awaitIdle()
        val original = store.state.value.worklists.single()
        val continuation = PlannedMediaDownloadBatch(store, store.captureRequest(), listOf("NEW.JPG"),
            DownloadHistoryDestination.FOLDER, { true }, ADMISSION_FAILED, ADMISSION_UNKNOWN,
            listOf(DownloadWorklistContinuationSelection(DownloadWorklistOrigin(original.worklistId,
                original.rows.single().rowId), "NEW.JPG")))
        assertTrue(continuation.prepare())
        assertTrue(store.clear())
        var opened = false
        assertTrue(runCatching { continuation.transfer(0) { opened = true } }.exceptionOrNull() is MediaWorklistAdmissionException)
        assertFalse(opened)
        assertTrue(store.state.value.worklists.isEmpty())
        continuation.release()
    }

    @Test fun retiredOwnerAndReleasedContinuationCannotStartAnOriginal() = runTest {
        val store = history(temporary.newFolder())
        val token = (store.admitWorklist(store.captureRequest(), listOf("OLD.JPG"),
            DownloadHistoryDestination.FOLDER) as DownloadWorklistAdmission.Admitted).token
        store.releaseWorklist(token)
        store.awaitIdle()
        val original = store.state.value.worklists.single()
        var current = true
        val continuation = PlannedMediaDownloadBatch(store, store.captureRequest(), listOf("NEW.JPG"),
            DownloadHistoryDestination.FOLDER, { current }, ADMISSION_FAILED, ADMISSION_UNKNOWN,
            listOf(DownloadWorklistContinuationSelection(DownloadWorklistOrigin(original.worklistId,
                original.rows.single().rowId), "NEW.JPG")))
        assertTrue(continuation.prepare())
        current = false
        var opened = false
        assertTrue(runCatching { continuation.transfer(0) { opened = true } }.exceptionOrNull() is CancellationException)
        continuation.release()
        current = true
        assertTrue(runCatching { continuation.transfer(0) { opened = true } }.exceptionOrNull() is IllegalStateException)
        store.awaitIdle()
        assertFalse(opened)
        assertTrue(store.state.value.activeWorklistIds.isEmpty())
        assertEquals(original, store.state.value.worklists.last())
    }

    private fun TestScope.history(directory: File, beforeWrite: (DownloadHistoryWritePhase) -> Unit = {}) = DownloadHistoryStore(
        DownloadHistoryFileStorage(directory, beforeWrite), backgroundScope, StandardTestDispatcher(testScheduler), { 1_000L },
    )

    private fun batch(store: DownloadHistoryStore, items: List<CameraMediaItem>, isCurrent: () -> Boolean = { true }) =
        PlannedMediaDownloadBatch(store, store.captureRequest(), items.map { it.name }, DownloadHistoryDestination.FOLDER,
            isCurrent, ADMISSION_FAILED, ADMISSION_UNKNOWN)

    private fun items(count: Int) = (1..count).map { index ->
        CameraMediaItem("SYNTHETIC_$index.JPG", "SYNTHETIC_$index.JPG", "image", ORIGINAL.size.toLong())
    }

    private fun originalResponse() = MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(ORIGINAL))
    private fun snapshot(directory: File) = File(directory, DOWNLOAD_HISTORY_SNAPSHOT_NAME)

    private companion object {
        const val ADMISSION_FAILED = "Synthetic worklist row admission failed"
        const val ADMISSION_UNKNOWN = "Synthetic worklist plan admission is unconfirmed"
        val ORIGINAL = byteArrayOf(4, 17, 30, 46, 59, 61)
    }
}
