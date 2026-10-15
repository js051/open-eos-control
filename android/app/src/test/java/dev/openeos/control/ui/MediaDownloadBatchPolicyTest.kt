package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** The download stop boundary is independent of Android providers and metadata batch policy. */
class MediaDownloadBatchPolicyTest {
    private val items = List(3) { CameraMediaItem("synthetic-$it", "SYNTHETIC_$it.JPG", "image", 3) }

    @Test fun cleanFailureStopsDownloadWithoutCallingRemainingRows() = runBlocking {
        val visited = mutableListOf<String>()
        val result = executeMediaBatch(items, MediaBatchOperation.DOWNLOAD) { item ->
            visited += item.id
            throw IOException("Synthetic original failure")
        }
        assertEquals(listOf(items.first().id), visited)
        assertEquals(0, result.succeededItems)
        assertEquals(listOf(items.first().name), result.failedItemNames)
        assertEquals(2, result.notStartedItems)
    }

    @Test fun failedCleanupStopsDownloadBeforeAnotherOutputIsCreated() = runBlocking {
        var outputCount = 0
        var cleanupCount = 0
        var completionCount = 0
        val failure = IOException("Synthetic read interrupted")
        val result = executeMediaBatch(items, MediaBatchOperation.DOWNLOAD) {
            outputCount++
            withMediaOutputFinalization(
                cleanupIncomplete = { cleanupCount++; throw IOException("Synthetic cleanup refused") },
                onFinalized = { completionCount++ },
            ) { throw failure }
        }
        assertTrue(failure.hasUnconfirmedMediaCleanup())
        assertEquals(1, outputCount)
        assertEquals(1, cleanupCount)
        assertEquals(0, completionCount)
        assertEquals(1, result.failedItems)
        assertEquals(2, result.notStartedItems)
    }

    @Test fun earlierCompletionSurvivesFailureAndUntouchedRowsAreNotFailures() = runBlocking {
        val visited = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val result = executeMediaBatch(items, MediaBatchOperation.DOWNLOAD, { progress += it.completedItems }) { item ->
            visited += item.id
            if (item == items[1]) throw IOException("Synthetic second item failed")
        }
        assertEquals(items.take(2).map { it.id }, visited)
        assertEquals(listOf(0, 1), progress)
        assertEquals(1, result.succeededItems)
        assertEquals(listOf(items[1].name), result.failedItemNames)
        assertEquals(1, result.notStartedItems)
    }

    @Test fun cancellationStaysCancellationAndNeverStartsTheNextRow() = runBlocking {
        val visited = mutableListOf<String>()
        val cancelled = CancellationException("Synthetic user cancellation")
        val failure = runCatching {
            executeMediaBatch(items, MediaBatchOperation.DOWNLOAD) { item -> visited += item.id; throw cancelled }
        }.exceptionOrNull()
        assertSame(cancelled, failure)
        assertEquals(listOf(items.first().id), visited)
    }

    @Test fun lateConfirmedCloseSurvivesCancellationWithoutContinuing() = runBlocking {
        val job = Job()
        var started = 0
        var confirmed = 0
        var cleaned = 0
        val transfer = launch(job) {
            executeMediaBatch(items, MediaBatchOperation.DOWNLOAD) {
                started++
                withMediaOutputFinalization(cleanupIncomplete = { cleaned++ }, onFinalized = { confirmed++ }) { output ->
                    withContext(Dispatchers.IO) { output.confirm(); job.cancel() }
                }
            }
        }
        transfer.join()
        assertTrue(transfer.isCancelled)
        assertEquals(1, started)
        assertEquals(1, confirmed)
        assertEquals(0, cleaned)
    }

    @Test fun nonDownloadBatchesKeepTheirExistingContinueOnFailurePolicy() = runBlocking {
        for (operation in MediaBatchOperation.entries.filter { it != MediaBatchOperation.DOWNLOAD }) {
            val visited = mutableListOf<String>()
            val result = executeMediaBatch(items, operation) { item ->
                visited += item.id
                if (item == items.first()) throw IOException("Synthetic non-download failure")
            }
            assertEquals(items.map { it.id }, visited)
            assertEquals(2, result.succeededItems)
            assertEquals(listOf(items.first().name), result.failedItemNames)
            assertEquals(0, result.notStartedItems)
        }
    }
}
