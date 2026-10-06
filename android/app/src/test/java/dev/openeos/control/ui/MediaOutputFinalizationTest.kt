package dev.openeos.control.ui

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaOutputFinalizationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completedFileSurvivesCancellationReturningFromIo() = runTest {
        val destination = temporary.newFile()
        val owner = Job()
        var cleanupCalls = 0
        var completionCalls = 0
        val transfer = launch(owner) {
            withMediaOutputFinalization(
                cleanupIncomplete = { cleanupCalls++; destination.delete() },
                onFinalized = { completionCalls++ },
            ) { finalized ->
                withContext(Dispatchers.IO) {
                    destination.outputStream().use { it.write(ORIGINAL) }
                    finalized.confirm()
                    owner.cancel()
                    destination
                }
            }
        }
        transfer.join()
        assertTrue(transfer.isCancelled)
        assertEquals(0, cleanupCalls)
        assertEquals(1, completionCalls)
        assertArrayEquals(ORIGINAL, destination.readBytes())
    }

    @Test fun incompleteFileIsRemovedWhenCancellationPrecedesFinalization() = runTest {
        val destination = temporary.newFile()
        val owner = Job()
        var cleanupCalls = 0
        var completionCalls = 0
        val transfer = launch(owner) {
            withMediaOutputFinalization(
                cleanupIncomplete = { cleanupCalls++; destination.delete() },
                onFinalized = { completionCalls++ },
            ) { finalized ->
                withContext(Dispatchers.IO) {
                    destination.outputStream().use { it.write(ORIGINAL.take(2).toByteArray()) }
                    owner.cancel()
                    coroutineContext.ensureActive()
                    finalized.confirm()
                }
            }
        }
        transfer.join()
        assertTrue(transfer.isCancelled)
        assertEquals(1, cleanupCalls)
        assertEquals(0, completionCalls)
        assertFalse(destination.exists())
    }

    @Test fun failingReceiptObserverCannotFailOrRemoveACompletedOriginal() = runTest {
        val destination = temporary.newFile()
        var cleanupCalls = 0
        val result = withMediaOutputFinalization(
            cleanupIncomplete = { cleanupCalls++; destination.delete() },
            onFinalized = { throw IOException("Synthetic receipt storage failure") },
        ) { finalized ->
            destination.outputStream().use { it.write(ORIGINAL) }
            finalized.confirm()
            "saved"
        }
        assertEquals("saved", result)
        assertEquals(0, cleanupCalls)
        assertArrayEquals(ORIGINAL, destination.readBytes())
    }

    @Test fun cleanupFailureIsTypedWhileTheOriginalTransferFailureRemainsPrimary() = runTest {
        val destination = temporary.newFile()
        val failure = IOException("Synthetic original transfer failure")
        val cleanupFailure = IOException("Synthetic cleanup failure")
        val observed = runCatching {
            withMediaOutputFinalization(
                cleanupIncomplete = { throw cleanupFailure },
            ) {
                destination.writeBytes(byteArrayOf(1))
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, observed)
        assertEquals(1, failure.suppressed.size)
        assertTrue(failure.suppressed.single() is IncompleteMediaCleanupException)
        assertSame(cleanupFailure, failure.suppressed.single().cause)
        assertTrue(failure.hasUnconfirmedMediaCleanup())
    }

    @Test fun confirmingTwiceEmitsOnlyOneCompletion() = runTest {
        var completions = 0
        var cleanups = 0
        withMediaOutputFinalization(
            cleanupIncomplete = { cleanups++ },
            onFinalized = { completions++ },
        ) {
            it.confirm()
            it.confirm()
        }
        assertEquals(1, completions)
        assertEquals(0, cleanups)
    }

    @Test fun laterNonTransferFailureStillCannotDeleteFinalizedOutput() = runTest {
        val destination = temporary.newFile()
        val failure = IOException("Synthetic failure after finalization")
        var cleanups = 0
        val observed = runCatching {
            withMediaOutputFinalization(cleanupIncomplete = { cleanups++; destination.delete() }) {
                destination.writeBytes(ORIGINAL)
                it.confirm()
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, observed)
        assertEquals(0, cleanups)
        assertArrayEquals(ORIGINAL, destination.readBytes())
    }

    @Test fun alreadyCancelledParentStillRegistersAndFinishesOwnedCleanup() = runTest {
        val destination = temporary.newFile()
        val sentinel = temporary.newFile().apply { writeBytes(ORIGINAL) }
        val parent = Job().apply { cancel() }
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var registered: Job? = null
        var cleanupCalls = 0
        var transferCalls = 0
        val output = MediaOutputFinalization(cleanupIncomplete = {
            assertTrue("Registration must precede cleanup", registered != null)
            cleanupCalls++
            cleanupEntered.complete(Unit)
            releaseCleanup.await()
            assertTrue(destination.delete())
        })
        val transfer = CoroutineScope(coroutineContext + parent).launchAdmittedMediaOutput(
            output,
            register = { registered = it },
        ) {
            currentCoroutineContext().ensureActive()
            transferCalls++
        }
        try {
            cleanupEntered.await()
            assertSame(transfer, registered)
            assertFalse("The returned job owns the cleanup lifetime", transfer.isCompleted)
            releaseCleanup.complete(Unit)
            transfer.join()
            assertTrue(transfer.isCancelled)
            assertEquals(1, cleanupCalls)
            assertEquals(0, transferCalls)
            assertFalse(destination.exists())
            assertArrayEquals(ORIGINAL, sentinel.readBytes())
        } finally {
            releaseCleanup.complete(Unit)
            transfer.cancelAndJoin()
        }
    }

    @Test fun cancellingWithTheOriginalCancellationDoesNotSuppressItself() = runTest {
        val cancellation = CancellationException("Synthetic original cancellation")
        val owner = Job()
        var observed: Throwable? = null
        // Stay on the cleanup dispatcher so ensureActive can return the original cancellation;
        // dispatcher-boundary recovery is covered separately by the receipt race test.
        val transfer = launch(owner + Dispatchers.IO) {
            observed = runCatching {
                withMediaOutputFinalization(cleanupIncomplete = { throw IOException("Synthetic cleanup refusal") }) {
                    owner.cancel(cancellation)
                    throw cancellation
                }
            }.exceptionOrNull()
        }
        transfer.join()
        val failure = requireNotNull(observed)
        assertTrue(failure is CancellationException)
        assertEquals(cancellation.message, failure.message)
        // Coroutine stack recovery may copy the cancellation, retaining the original as cause.
        assertTrue(failure === cancellation || failure.cause === cancellation || failure.suppressed.any { it === cancellation })
        assertTrue(failure.hasUnconfirmedMediaCleanup())
        assertFalse("The escaping exception must never suppress itself", failure.suppressed.any { it === failure })
        assertFalse("The original cancellation must never suppress itself", cancellation.suppressed.any { it === cancellation })
    }

    private companion object {
        val ORIGINAL = byteArrayOf(7, 12, 25, 41, 59, 61)
    }
}
