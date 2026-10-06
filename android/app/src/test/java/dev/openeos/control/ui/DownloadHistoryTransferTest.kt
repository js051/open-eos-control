package dev.openeos.control.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CcapiClient
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryFileStorage
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryStore
import dev.openeos.control.data.DownloadHistoryWarning
import dev.openeos.control.data.DownloadHistoryWritePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadHistoryTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedCleanupStopsAutomaticReadsBeforeAnotherPartialIsCreated() = runTest {
        val history = history(temporary.newFolder())
        val output = temporary.newFolder()
        val sentinel = File(output, "SYNTHETIC-UNRELATED.bin").apply { writeBytes(ORIGINAL) }
        // The response must pass content sniffing and deliver a real output prefix before
        // disconnecting; a six-byte fixture fails while still inside the sniff buffer.
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val item = CameraMediaItem("SYNTHETIC.JPG", "SYNTHETIC.JPG", "image", payload.size.toLong())
        val server = MockWebServer()
        var created = 0
        var cleanupCalls = 0
        server.start()
        try {
            repeat(3) { server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(payload)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)) }
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
            val failure = runCatching {
                withDownloadHistoryReceipt(history, history.captureRequest(), item.name, DownloadHistoryDestination.FOLDER) { completed ->
                    retryMediaRead(longArrayOf(0, 0)) {
                        val file = File(output, "partial-${++created}.bin")
                        withMediaOutputFinalization(
                            cleanupIncomplete = { cleanupCalls++; throw IOException("Synthetic provider refused cleanup") },
                            onFinalized = completed,
                        ) { finalized ->
                            file.outputStream().use { client.downloadMedia(item, it) }
                            finalized.confirm()
                        }
                    }
                }
            }.exceptionOrNull()
            history.awaitIdle()

            assertTrue(failure is IOException)
            assertEquals("Unconfirmed cleanup must not create another partial", 1, created)
            assertEquals(1, cleanupCalls)
            assertEquals(1, server.requestCount)
            assertEquals(2, requireNotNull(output.listFiles()).size)
            assertTrue(File(output, "partial-1.bin").length() > 0)
            assertArrayEquals(ORIGINAL, sentinel.readBytes())
            assertEquals(DownloadHistoryOutcome.FAILED, history.state.value.entries.single().outcome)
            assertTrue(history.state.value.entries.single().cleanupUnconfirmed)
        } finally {
            server.shutdown()
        }
    }

    @Test fun cancelledCleanupFailureKeepsCancellationAndRiskAfterHistoryReload() = runTest {
        val directory = temporary.newFolder()
        val history = history(directory)
        val partial = temporary.newFile()
        var attempts = 0
        val transfer = launch {
            withDownloadHistoryReceipt(history, history.captureRequest(), "SYNTHETIC.JPG", DownloadHistoryDestination.DOCUMENT) { completed ->
                retryMediaRead(longArrayOf(0, 0)) {
                    attempts++
                    withMediaOutputFinalization(
                        cleanupIncomplete = { throw IOException("Synthetic delete refusal") },
                        onFinalized = completed,
                    ) {
                        partial.writeBytes(ORIGINAL.take(2).toByteArray())
                        throw CancellationException("Synthetic cancelled save")
                    }
                }
            }
        }
        transfer.join()
        history.awaitIdle()
        assertTrue(transfer.isCancelled)
        assertEquals(1, attempts)
        assertArrayEquals(ORIGINAL.take(2).toByteArray(), partial.readBytes())
        assertEquals(DownloadHistoryOutcome.CANCELLED, history.state.value.entries.single().outcome)
        assertTrue(history.state.value.entries.single().cleanupUnconfirmed)
        val reopened = history(directory)
        reopened.awaitIdle()
        assertEquals(DownloadHistoryOutcome.CANCELLED, reopened.state.value.entries.single().outcome)
        assertTrue(reopened.state.value.entries.single().cleanupUnconfirmed)
    }

    @Test fun jobCancellationDuringRejectedCleanupKeepsRiskOnTheEscapingCancellation() = runTest {
        val directory = temporary.newFolder()
        val history = history(directory)
        val partial = temporary.newFile()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val originalFailure = IOException("Synthetic interrupted original")
        var output: MediaOutputFinalization? = null
        var escaping: Throwable? = null
        var completionCause: Throwable? = null
        var completionCleanupUnconfirmed = false
        val transfer = launch {
            try {
                withDownloadHistoryReceipt(history, history.captureRequest(), "SYNTHETIC.JPG", DownloadHistoryDestination.DOCUMENT) { completed ->
                    withMediaOutputFinalization(
                        cleanupIncomplete = {
                            cleanupEntered.complete(Unit)
                            releaseCleanup.await()
                            throw IOException("Synthetic provider refused cleanup")
                        },
                        onFinalized = completed,
                    ) { owner ->
                        output = owner
                        partial.writeBytes(ORIGINAL.take(2).toByteArray())
                        throw originalFailure
                    }
                }
            } catch (failure: Throwable) {
                escaping = failure
                throw failure
            }
        }
        transfer.invokeOnCompletion {
            completionCause = it
            completionCleanupUnconfirmed = requireNotNull(output).cleanupUnconfirmed
        }
        try {
            cleanupEntered.await()
            transfer.cancel(CancellationException("Synthetic user cancellation during cleanup"))
            releaseCleanup.complete(Unit)
            transfer.join()
        } finally {
            releaseCleanup.complete(Unit)
            transfer.cancelAndJoin()
        }
        history.awaitIdle()
        assertTrue(transfer.isCancelled)
        // Job completion can retain the first cancellation while stack recovery supplies a
        // different throwable at the receipt boundary. UI reads the owner's cleanup result.
        assertTrue(completionCause is CancellationException)
        assertTrue(escaping is CancellationException)
        assertTrue(requireNotNull(escaping).hasUnconfirmedMediaCleanup())
        assertTrue(requireNotNull(escaping).suppressed.any { it === originalFailure })
        assertTrue(completionCleanupUnconfirmed)
        assertArrayEquals(ORIGINAL.take(2).toByteArray(), partial.readBytes())
        assertEquals(DownloadHistoryOutcome.CANCELLED, history.state.value.entries.single().outcome)
        assertTrue("Prompt cancellation must not erase the cleanup failure", history.state.value.entries.single().cleanupUnconfirmed)
        val reopened = history(directory)
        reopened.awaitIdle()
        assertEquals(DownloadHistoryOutcome.CANCELLED, reopened.state.value.entries.single().outcome)
        assertTrue(reopened.state.value.entries.single().cleanupUnconfirmed)
    }

    @Test fun admittedOutputCancelledBeforeDispatchIsCleanedWithoutOriginalHttp() = runTest {
        verifyPredispatchCancellation(refuseCleanup = false)
    }

    @Test fun admittedOutputCancelledBeforeDispatchPersistsRejectedCleanup() = runTest {
        verifyPredispatchCancellation(refuseCleanup = true)
    }

    private suspend fun TestScope.verifyPredispatchCancellation(refuseCleanup: Boolean) {
        val directory = temporary.newFolder()
        val history = history(directory)
        val destination = temporary.newFile()
        val sentinel = temporary.newFile().apply { writeBytes(ORIGINAL) }
        val server = MockWebServer()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleanups = 0
        var registered: Job? = null
        var completionCleanupUnconfirmed: Boolean? = null
        val output = MediaOutputFinalization(cleanupIncomplete = {
            cleanups++
            cleanupEntered.complete(Unit)
            releaseCleanup.await()
            if (refuseCleanup) throw IOException("Synthetic cleanup refusal")
            assertTrue(destination.delete())
        })
        val download = AdmittedMediaDownload(output, history, history.captureRequest(), "SYNTHETIC.JPG", DownloadHistoryDestination.DOCUMENT)
        server.start()
        try {
            server.enqueue(originalResponse())
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
            val transfer = launchAdmittedMediaOutput(output, register = { job ->
                registered = job
                job.invokeOnCompletion { completionCleanupUnconfirmed = output.cleanupUnconfirmed }
            }) {
                download.run {
                    destination.outputStream().use { client.downloadMedia(syntheticItem(), it) }
                    download.confirm {}
                }
            }
            try {
                assertEquals(transfer, registered)
                transfer.cancel()
                cleanupEntered.await()
                assertFalse(transfer.isCompleted)
                history.awaitIdle()
                assertEquals(DownloadHistoryOutcome.IN_PROGRESS, history.state.value.entries.single().outcome)
                releaseCleanup.complete(Unit)
                transfer.join()
                history.awaitIdle()
                assertTrue(transfer.isCancelled)
                assertEquals(1, cleanups)
                assertEquals(refuseCleanup, completionCleanupUnconfirmed)
                assertEquals(0, server.requestCount)
                val reopened = history(directory)
                reopened.awaitIdle()
                assertEquals(DownloadHistoryOutcome.CANCELLED, reopened.state.value.entries.single().outcome)
                assertEquals(refuseCleanup, reopened.state.value.entries.single().cleanupUnconfirmed)
                assertEquals(refuseCleanup, destination.exists())
                assertArrayEquals(ORIGINAL, sentinel.readBytes())
            } finally {
                releaseCleanup.complete(Unit)
                transfer.cancelAndJoin()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun admittedOutputCancelledWhileJoiningOldReadIsCleanedWithoutOriginalHttp() = runTest {
        verifyJoinCancellation(refuseCleanup = false)
    }

    @Test fun admittedOutputCancelledWhileJoiningOldReadPersistsRejectedCleanup() = runTest {
        verifyJoinCancellation(refuseCleanup = true)
    }

    private suspend fun TestScope.verifyJoinCancellation(refuseCleanup: Boolean) {
        val directory = temporary.newFolder()
        val history = history(directory)
        val destination = temporary.newFile()
        val sentinel = temporary.newFile().apply { writeBytes(ORIGINAL) }
        val oldReadEntered = CompletableDeferred<Unit>()
        val releaseOldRead = CompletableDeferred<Unit>()
        val joinEntered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val server = MockWebServer()
        var cleanups = 0
        var completionCleanupUnconfirmed: Boolean? = null
        val oldRead = launch {
            withContext(NonCancellable) {
                oldReadEntered.complete(Unit)
                releaseOldRead.await()
            }
        }
        val output = MediaOutputFinalization(cleanupIncomplete = {
            cleanups++
            cleanupEntered.complete(Unit)
            releaseCleanup.await()
            if (refuseCleanup) throw IOException("Synthetic cleanup refusal")
            assertTrue(destination.delete())
        })
        val download = AdmittedMediaDownload(output, history, history.captureRequest(), "SYNTHETIC.JPG", DownloadHistoryDestination.DOCUMENT)
        server.start()
        try {
            oldReadEntered.await()
            oldRead.cancel()
            server.enqueue(originalResponse())
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
            val transfer = launchAdmittedMediaOutput(output, register = { job ->
                job.invokeOnCompletion { completionCleanupUnconfirmed = output.cleanupUnconfirmed }
            }) {
                download.run {
                    joinEntered.complete(Unit)
                    oldRead.join()
                    destination.outputStream().use { client.downloadMedia(syntheticItem(), it) }
                    download.confirm {}
                }
            }
            try {
                joinEntered.await()
                assertFalse(oldRead.isCompleted)
                transfer.cancel()
                cleanupEntered.await()
                history.awaitIdle()
                assertEquals(DownloadHistoryOutcome.IN_PROGRESS, history.state.value.entries.single().outcome)
                assertFalse(transfer.isCompleted)
                releaseCleanup.complete(Unit)
                transfer.join()
                history.awaitIdle()
                assertTrue(transfer.isCancelled)
                assertEquals(1, cleanups)
                assertEquals(refuseCleanup, completionCleanupUnconfirmed)
                assertEquals(0, server.requestCount)
                val reopened = history(directory)
                reopened.awaitIdle()
                assertEquals(DownloadHistoryOutcome.CANCELLED, reopened.state.value.entries.single().outcome)
                assertEquals(refuseCleanup, reopened.state.value.entries.single().cleanupUnconfirmed)
                assertEquals(refuseCleanup, destination.exists())
                assertArrayEquals(ORIGINAL, sentinel.readBytes())
            } finally {
                releaseCleanup.complete(Unit)
                transfer.cancelAndJoin()
            }
        } finally {
            releaseOldRead.complete(Unit)
            oldRead.cancelAndJoin()
            server.shutdown()
        }
    }

    @Test fun admissionCancellationCleansOwnedOutputBeforeRecordingCancelledReceipt() = runTest {
        verifyAdmissionCancellation(refuseCleanup = false)
    }

    @Test fun admissionCancellationPersistsRejectedCleanupBeforeRecordingCancelledReceipt() = runTest {
        verifyAdmissionCancellation(refuseCleanup = true)
    }

    private suspend fun TestScope.verifyAdmissionCancellation(refuseCleanup: Boolean) {
        val directory = temporary.newFolder()
        val destination = temporary.newFile()
        val sentinel = temporary.newFile().apply { writeBytes(ORIGINAL) }
        val admissionEntered = CompletableDeferred<Unit>()
        val releaseAdmission = CountDownLatch(1)
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val history = DownloadHistoryStore(DownloadHistoryFileStorage(directory) { phase ->
            if (phase == DownloadHistoryWritePhase.BEFORE_MOVE && !admissionEntered.isCompleted) {
                admissionEntered.complete(Unit)
                check(releaseAdmission.await(5, TimeUnit.SECONDS)) { "Admission gate was not released" }
            }
        }, backgroundScope, Dispatchers.IO, { 1000L })
        val server = MockWebServer()
        var cleanups = 0
        var completionCleanupUnconfirmed: Boolean? = null
        val output = MediaOutputFinalization(cleanupIncomplete = {
            cleanups++
            cleanupEntered.complete(Unit)
            releaseCleanup.await()
            if (refuseCleanup) throw IOException("Synthetic cleanup refusal")
            assertTrue(destination.delete())
        })
        val download = AdmittedMediaDownload(output, history, history.captureRequest(), "SYNTHETIC.JPG", DownloadHistoryDestination.DOCUMENT)
        server.start()
        try {
            server.enqueue(originalResponse())
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
            val transfer = launchAdmittedMediaOutput(output, register = { job ->
                job.invokeOnCompletion { completionCleanupUnconfirmed = output.cleanupUnconfirmed }
            }) {
                download.run {
                    destination.outputStream().use { client.downloadMedia(syntheticItem(), it) }
                    download.confirm {}
                }
            }
            try {
                admissionEntered.await()
                transfer.cancel(CancellationException("Synthetic cancellation while receipt admission waits"))
                assertFalse(transfer.isCompleted)
                releaseAdmission.countDown()
                cleanupEntered.await()
                history.awaitIdle()
                assertEquals("The first terminal write must wait for cleanup", DownloadHistoryOutcome.IN_PROGRESS,
                    history.state.value.entries.single().outcome)
                assertFalse(transfer.isCompleted)
                releaseCleanup.complete(Unit)
                transfer.join()
                history.awaitIdle()
                assertTrue(transfer.isCancelled)
                assertEquals(1, cleanups)
                assertEquals(refuseCleanup, completionCleanupUnconfirmed)
                assertEquals(0, server.requestCount)
                assertEquals(refuseCleanup, destination.exists())
                assertArrayEquals(ORIGINAL, sentinel.readBytes())
                val reopened = history(directory)
                reopened.awaitIdle()
                assertEquals(DownloadHistoryOutcome.CANCELLED, reopened.state.value.entries.single().outcome)
                assertEquals(refuseCleanup, reopened.state.value.entries.single().cleanupUnconfirmed)
            } finally {
                releaseAdmission.countDown()
                releaseCleanup.complete(Unit)
                transfer.cancelAndJoin()
            }
        } finally {
            releaseAdmission.countDown()
            releaseCleanup.complete(Unit)
            history.awaitIdle()
            server.shutdown()
        }
    }

    @Test fun admittedOutputFinalizedBeforeIoReturnSurvivesJobCancellation() = runTest {
        val history = history(temporary.newFolder())
        val destination = temporary.newFile()
        val sentinel = temporary.newFile().apply { writeBytes(ORIGINAL) }
        val parent = Job()
        var cleanups = 0
        var completions = 0
        val output = MediaOutputFinalization(cleanupIncomplete = { cleanups++; destination.delete() })
        val download = AdmittedMediaDownload(output, history, history.captureRequest(), "SYNTHETIC.JPG", DownloadHistoryDestination.DOCUMENT)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(originalResponse())
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
            val transfer = CoroutineScope(coroutineContext + parent).launchAdmittedMediaOutput(output, register = {}) {
                download.run {
                    withContext(Dispatchers.IO) {
                        destination.outputStream().use { client.downloadMedia(syntheticItem(), it) }
                        download.confirm { completions++ }
                        parent.cancel()
                    }
                }
            }
            try {
                transfer.join()
                history.awaitIdle()
                assertTrue(transfer.isCancelled)
                assertEquals(0, cleanups)
                assertEquals(1, completions)
                assertEquals(1, server.requestCount)
                assertEquals(DownloadHistoryOutcome.COMPLETED, history.state.value.entries.single().outcome)
                assertFalse(history.state.value.entries.single().cleanupUnconfirmed)
                assertArrayEquals(ORIGINAL, destination.readBytes())
                assertArrayEquals(ORIGINAL, sentinel.readBytes())
            } finally {
                transfer.cancelAndJoin()
            }
        } finally {
            parent.cancel()
            server.shutdown()
        }
    }

    @Test fun mixedBatchUsesOneDurableReceiptPerOriginalAcrossRealHttpRetries() = runTest {
        val directory = temporary.newFolder()
        val history = history(directory)
        val token = history.captureRequest()
        val items = (1..3).map { CameraMediaItem("SYNTHETIC_$it.JPG", "SYNTHETIC_$it.JPG", "image", ORIGINAL.size.toLong()) }
        val outputs = temporary.newFolder()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(originalResponse())
            server.enqueue(truncatedResponse())
            server.enqueue(originalResponse())
            repeat(2) { server.enqueue(truncatedResponse()) }
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
            val result = executeMediaBatch(items, MediaBatchOperation.DOWNLOAD) { item ->
                withDownloadHistoryReceipt(history, token, item.name, DownloadHistoryDestination.FOLDER) { completed ->
                    // This is independently read from the installed disk snapshot before output I/O.
                    assertEquals(DownloadHistoryOutcome.IN_PROGRESS, DownloadHistoryFileStorage(directory).read().first().outcome)
                    retryMediaRead(longArrayOf(0)) {
                        val file = File(outputs, item.name)
                        withMediaOutputFinalization(cleanupIncomplete = { file.delete() }, onFinalized = completed) { finalized ->
                            file.outputStream().use { client.downloadMedia(item, it) }
                            finalized.confirm()
                        }
                    }
                }
            }
            history.awaitIdle()
            assertEquals(2, result.succeededItems)
            assertEquals(listOf(items.last().name), result.failedItemNames)
            assertEquals(5, server.requestCount)
            assertEquals(3, history.state.value.entries.size)
            assertEquals(listOf(DownloadHistoryOutcome.FAILED, DownloadHistoryOutcome.COMPLETED, DownloadHistoryOutcome.COMPLETED), history.state.value.entries.map { it.outcome })
            assertEquals(3, history.state.value.entries.map { it.receiptId }.distinct().size)
            assertArrayEquals(ORIGINAL, File(outputs, items[0].name).readBytes())
            assertArrayEquals(ORIGINAL, File(outputs, items[1].name).readBytes())
            assertFalse(File(outputs, items[2].name).exists())
        } finally {
            server.shutdown()
        }
    }

    @Test fun cancellationKeepsCompletedOriginalAndExcludesUnstartedBatchItems() = runTest {
        val history = history(temporary.newFolder())
        val token = history.captureRequest()
        val secondStarted = CompletableDeferred<Unit>()
        val files = temporary.newFolder()
        val items = (1..3).map { CameraMediaItem("$it", "SYNTHETIC_$it.JPG", "image") }
        val job = launch {
            executeMediaBatch(items, MediaBatchOperation.DOWNLOAD) { item ->
                withDownloadHistoryReceipt(history, token, item.name, DownloadHistoryDestination.FOLDER) { completed ->
                    val file = File(files, item.name)
                    withMediaOutputFinalization(cleanupIncomplete = { file.delete() }, onFinalized = completed) { finalized ->
                        file.writeBytes(ORIGINAL)
                        if (item.id == "2") {
                            secondStarted.complete(Unit)
                            awaitCancellation()
                        }
                        finalized.confirm()
                    }
                }
            }
        }
        secondStarted.await()
        job.cancelAndJoin()
        history.awaitIdle()
        assertEquals(listOf(DownloadHistoryOutcome.CANCELLED, DownloadHistoryOutcome.COMPLETED), history.state.value.entries.map { it.outcome })
        assertArrayEquals(ORIGINAL, File(files, items[0].name).readBytes())
        assertFalse(File(files, items[1].name).exists())
        assertFalse(File(files, items[2].name).exists())
    }

    @Test fun cancellationDuringPendingCommitStillOwnsReceiptAndDoesNoDestinationIo() = runTest {
        val owner = Job()
        var outputOpened = false
        val history = history(temporary.newFolder()) { phase ->
            if (phase == DownloadHistoryWritePhase.BEFORE_MOVE) owner.cancel()
        }
        val job = launch(owner) {
            withDownloadHistoryReceipt(history, history.captureRequest(), "SYNTHETIC.JPG", DownloadHistoryDestination.DOCUMENT) {
                outputOpened = true
            }
        }
        job.join()
        history.awaitIdle()
        assertTrue(job.isCancelled)
        assertFalse(outputOpened)
        assertEquals(DownloadHistoryOutcome.CANCELLED, history.state.value.entries.single().outcome)
    }

    @Test fun failedTerminalJournalWriteLeavesSavedBytesAndReloadsPendingAsUnconfirmed() = runTest {
        val directory = temporary.newFolder()
        var failWrites = false
        val history = history(directory) { if (failWrites) throw IOException("Synthetic disk failure") }
        val file = temporary.newFile()
        var saved = false
        var transfers = 0
        withDownloadHistoryReceipt(history, history.captureRequest(), "SYNTHETIC.JPG", DownloadHistoryDestination.DOCUMENT) { completed ->
            retryMediaRead(longArrayOf(0)) {
                transfers++
                withMediaOutputFinalization(cleanupIncomplete = { file.delete() }, onFinalized = { completed(); saved = true }) { finalized ->
                    file.outputStream().use { it.write(ORIGINAL) }
                    failWrites = true
                    finalized.confirm()
                }
            }
        }
        history.awaitIdle()
        assertTrue(saved)
        assertEquals(1, transfers)
        assertArrayEquals(ORIGINAL, file.readBytes())
        assertEquals(DownloadHistoryOutcome.COMPLETED, history.state.value.entries.single().outcome)
        assertEquals(DownloadHistoryWarning.WRITE_FAILED, history.state.value.warning)
        val reloaded = history(directory)
        reloaded.awaitIdle()
        assertEquals(DownloadHistoryOutcome.UNCONFIRMED, reloaded.state.value.entries.single().outcome)
    }

    @Test fun failedOutputCloseCannotRecordCompletionEvenWithAllBytesWritten() = runTest {
        val history = history(temporary.newFolder())
        val file = temporary.newFile()
        val failure = IOException("Synthetic output close failure")
        var saved = false
        val observed = runCatching {
            withDownloadHistoryReceipt(history, history.captureRequest(), "SYNTHETIC.JPG", DownloadHistoryDestination.DOCUMENT) { completed ->
                withMediaOutputFinalization(cleanupIncomplete = { file.delete() }, onFinalized = { completed(); saved = true }) { finalized ->
                    object : FilterOutputStream(file.outputStream()) {
                        override fun close() { super.close(); throw failure }
                    }.use { it.write(ORIGINAL) }
                    finalized.confirm()
                }
            }
        }.exceptionOrNull()
        history.awaitIdle()
        assertEquals(failure, observed)
        assertFalse(saved)
        assertFalse(file.exists())
        assertEquals(DownloadHistoryOutcome.FAILED, history.state.value.entries.single().outcome)
    }

    @Test fun clearingBetweenBatchItemsDoesNotRepopulateOldRequestButNewRequestRecords() = runTest {
        val history = history(temporary.newFolder())
        val token = history.captureRequest()
        var transfers = 0
        repeat(3) { index ->
            withDownloadHistoryReceipt(history, token, "SYNTHETIC_$index.JPG", DownloadHistoryDestination.GALLERY) { completed ->
                transfers++
                completed()
            }
            if (index == 0) assertTrue(history.clear())
        }
        history.awaitIdle()
        assertEquals(3, transfers)
        assertTrue(history.state.value.entries.isEmpty())
        withDownloadHistoryReceipt(history, history.captureRequest(), "NEW.JPG", DownloadHistoryDestination.GALLERY) { it() }
        history.awaitIdle()
        assertEquals("NEW.JPG", history.state.value.entries.single().filename)
    }

    @Test fun corruptHistoryDoesNotBlockOriginalOrOverwriteCorruptInput() = runTest {
        val directory = temporary.newFolder()
        val snapshot = File(directory, "history.json").apply { writeText("{broken synthetic history") }
        val bytes = snapshot.readBytes()
        val history = history(directory)
        val file = temporary.newFile()
        withDownloadHistoryReceipt(history, history.captureRequest(), "SYNTHETIC.JPG", DownloadHistoryDestination.DOCUMENT) { completed ->
            withMediaOutputFinalization(cleanupIncomplete = { file.delete() }, onFinalized = completed) {
                file.outputStream().use { it.write(ORIGINAL) }
                it.confirm()
            }
        }
        history.awaitIdle()
        assertArrayEquals(ORIGINAL, file.readBytes())
        assertArrayEquals(bytes, snapshot.readBytes())
        assertEquals(DownloadHistoryWarning.READ_FAILED, history.state.value.warning)
        assertTrue(history.state.value.entries.isEmpty())
    }

    @Test fun historyFactoryFailureLeavesViewModelUsableWithoutCreatingFallbackFiles() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = object : ContextWrapper(null) { override fun getApplicationContext(): Context = this }
        val viewModel = CameraViewModel(downloadHistoryFactory = { throw IOException("Synthetic private path unavailable") })
        try {
            viewModel.initializeDownloadHistory(context)
            viewModel.enterOfflinePreview()
            runCurrent()
            assertTrue(viewModel.uiState.value.previewMode)
            assertEquals(DownloadHistoryWarning.STOPPED, viewModel.downloadHistoryState.value.warning)
            assertFalse(viewModel.downloadHistoryState.value.loading)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.history(directory: File, beforeWrite: (DownloadHistoryWritePhase) -> Unit = {}) = DownloadHistoryStore(
        DownloadHistoryFileStorage(directory, beforeWrite), backgroundScope, StandardTestDispatcher(testScheduler), { 1000L },
    )
    private fun originalResponse() = MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(ORIGINAL))
    private fun syntheticItem() = CameraMediaItem("SYNTHETIC.JPG", "SYNTHETIC.JPG", "image", ORIGINAL.size.toLong())
    private fun truncatedResponse() = originalResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
    private companion object { val ORIGINAL = byteArrayOf(4, 17, 30, 46, 59, 61) }
}
