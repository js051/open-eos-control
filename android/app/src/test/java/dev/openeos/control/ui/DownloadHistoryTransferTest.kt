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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadHistoryTransferTest {
    @get:Rule val temporary = TemporaryFolder()

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
    private fun truncatedResponse() = originalResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
    private companion object { val ORIGINAL = byteArrayOf(4, 17, 30, 46, 59, 61) }
}
