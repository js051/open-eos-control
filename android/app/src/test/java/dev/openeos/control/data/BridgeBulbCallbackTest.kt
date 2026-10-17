package dev.openeos.control.data

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Production callback awaiter; the callback/body producer is explicitly synthetic. */
class BridgeBulbCallbackTest {
    @Test(timeout = 5000) fun ignoredCancelAndBlockedResponseBodyCannotExtendTotalDeadline() = runBlocking {
        val releaseBody = CountDownLatch(1)
        val callbackDone = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val startedAt = System.nanoTime()
        try {
            val outcome = runCatching {
                awaitBridgeBulbCallback<String>(timeoutMillis = 80) { complete ->
                    Thread {
                        try {
                            releaseBody.await()
                            complete(Result.success("synthetic late body"))
                        } finally { callbackDone.countDown() }
                    }.apply { isDaemon = true; start() }
                    val cancel: () -> Unit = { cancellations.incrementAndGet(); Unit }
                    cancel
                }
            }
            assertTrue(outcome.exceptionOrNull() is TimeoutCancellationException)
            assertTrue("The awaiter must return without waiting for the ignored cancellation or body",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 1500)
            assertEquals(1, cancellations.get())
            assertEquals(1L, releaseBody.count)
        } finally {
            releaseBody.countDown()
            assertTrue(callbackDone.await(1, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 5000) fun duplicateAndLateCallbacksCannotReplaceTheFirstReceipt() = runBlocking {
        val cancellations = AtomicInteger()
        val value = awaitBridgeBulbCallback<String>(timeoutMillis = 1000) { complete ->
            complete(Result.success("first"))
            complete(Result.failure(IOException("synthetic duplicate")))
            complete(Result.success("third"))
            val cancel: () -> Unit = { cancellations.incrementAndGet(); Unit }
            cancel
        }
        assertEquals("first", value)
        assertEquals(0, cancellations.get())
    }

    @Test(timeout = 5000) fun callbackFailurePreservesOriginalFailure() = runBlocking {
        val original = IOException("synthetic body failure")
        val outcome = runCatching {
            awaitBridgeBulbCallback<String>(timeoutMillis = 1000) { complete ->
                complete(Result.failure(original))
                val cancel: () -> Unit = { }
                cancel
            }
        }
        assertTrue(generateSequence(outcome.exceptionOrNull()) { it.cause }.any { it === original })
    }

    @Test(timeout = 5000) fun registrationFailureDoesNotWaitForTimeout() = runBlocking {
        val original = IOException("synthetic enqueue failure")
        val outcome = runCatching {
            awaitBridgeBulbCallback<String>(timeoutMillis = 1000) { throw original }
        }
        assertTrue(generateSequence(outcome.exceptionOrNull()) { it.cause }.any { it === original })
    }

    @Test(timeout = 5000) fun callerCancellationCancelsOneAttemptWithoutReplay() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val cancellations = AtomicInteger()
        val job = launch {
            awaitBridgeBulbCallback<String>(timeoutMillis = 1000) {
                attempts.incrementAndGet()
                entered.complete(Unit)
                val cancel: () -> Unit = { cancellations.incrementAndGet(); Unit }
                cancel
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, attempts.get())
        assertEquals(1, cancellations.get())
    }
}
