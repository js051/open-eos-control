package dev.openeos.control.ui

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Real finalization primitive; no Android SDK/provider or coroutine-test substitution. */
class MediaDeliveryOutcomeContractTest {
    @Test fun confirmedCloseSurvivesPromptCancellation() = runBlocking {
        var confirmations = 0
        var deletions = 0
        val job = Job()
        val transfer = launch(job) {
            withMediaOutputFinalization(cleanupIncomplete = { deletions++ }, onFinalized = { confirmations++ }) { output ->
                withContext(Dispatchers.IO) {
                    output.confirm()
                    job.cancel()
                }
            }
        }
        transfer.join()
        assertTrue(transfer.isCancelled)
        assertEquals(ContractExpectations.closeAfterCancelConfirmed, confirmations == 1)
        assertEquals(ContractExpectations.deleteCompletedOutput, deletions != 0)
    }

    @Test fun cleanupFailureIsVisibleAndDoesNotInventCompletion() = runBlocking {
        var confirmations = 0
        var cleanups = 0
        val original = IOException("Synthetic transfer interrupted")
        val failure = runCatching {
            withMediaOutputFinalization(cleanupIncomplete = { cleanups++; throw IOException("Synthetic cleanup refused") },
                onFinalized = { confirmations++ }) { throw original }
        }.exceptionOrNull()
        assertSame(original, failure)
        assertEquals(ContractExpectations.cleanupFailureNeedsInspection, requireNotNull(failure).hasUnconfirmedMediaCleanup())
        assertEquals(ContractExpectations.cleanupFailureConfirmed, confirmations != 0)
        assertEquals(1, cleanups)
    }

    @Test fun confirmationObserverFailureCannotDeleteOutput() = runBlocking {
        var deletions = 0
        val result = withMediaOutputFinalization(cleanupIncomplete = { deletions++ },
            onFinalized = { throw IOException("Synthetic history unavailable") }) {
            it.confirm()
            "original transfer result"
        }
        assertEquals("original transfer result", result)
        assertEquals(ContractExpectations.deleteAfterTerminalHistoryFailure, deletions != 0)
    }

    @Test fun cancellationBeforeCheckpointCleansOnceWithoutConfirmation() = runBlocking {
        var confirmations = 0
        var cleanups = 0
        val output = MediaOutputFinalization(cleanupIncomplete = { cleanups++ }, onFinalized = { confirmations++ })
        val failure = runCatching { output.protect { throw CancellationException("Synthetic cancelled") } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(output.isFinalized)
        assertFalse(requireNotNull(failure).hasUnconfirmedMediaCleanup())
        assertEquals(0, confirmations)
        assertEquals(1, cleanups)
    }

    @Test fun repeatedConfirmationRemainsOneCheckpoint() = runBlocking {
        var confirmations = 0
        var cleanups = 0
        val output = MediaOutputFinalization(cleanupIncomplete = { cleanups++ }, onFinalized = { confirmations++ })
        output.protect { it.confirm(); it.confirm() }
        assertTrue(output.isFinalized)
        assertEquals(1, confirmations)
        assertEquals(0, cleanups)
    }
}
