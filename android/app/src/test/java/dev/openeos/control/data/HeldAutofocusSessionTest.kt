package dev.openeos.control.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HeldAutofocusSessionTest {
    @Test fun cancelledFocusRetainsARejectedReleaseForRetry() = runTest {
        val session = HeldAutofocusSession()
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Throwable?>()
        var stops = 0
        val job = launch {
            result.complete(runCatching {
                session.hold({}, { if (++stops == 1) throw IOException("lost release") }) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }.exceptionOrNull())
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(result.await() is AutofocusReleaseException)
        session.retryStop()
        assertEquals(2, stops)
    }

    @Test fun releaseRunsAfterCancellation() = runTest {
        val session = HeldAutofocusSession()
        val started = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val job = launch {
            session.hold({ writes += "start" }, { writes += "stop" }) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        assertEquals(listOf("start"), writes)
        job.cancelAndJoin()
        session.retryStop()
        assertEquals(listOf("start", "stop"), writes)
    }

    @Test fun ambiguousStartFailureStillSendsStop() = runTest {
        val session = HeldAutofocusSession()
        var stops = 0
        var enteredHold = false
        val result = runCatching {
            session.hold({ throw IOException("lost start response") }, { stops++ }) { enteredHold = true }
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertFalse(enteredHold)
        assertEquals(1, stops)
    }

    @Test fun failedStopRemainsOwnedUntilExplicitRetryAndCannotRestart() = runTest {
        val session = HeldAutofocusSession()
        var starts = 0
        var stops = 0
        val result = runCatching {
            session.hold({ starts++ }, { if (++stops == 1) throw IOException("lost stop response") }) {}
        }
        assertTrue(result.exceptionOrNull() is AutofocusReleaseException)
        assertTrue(runCatching { session.hold({ starts++ }, {}, {}) }.isFailure)
        assertEquals(1, starts)
        session.retryStop()
        session.retryStop()
        assertEquals(2, stops)
        session.hold({ starts++ }, { stops++ }, {})
        assertEquals(2, starts)
        assertEquals(3, stops)
    }

    @Test fun stopFailureTakesPriorityWithoutLosingStartFailure() = runTest {
        val failure = runCatching {
            HeldAutofocusSession().hold({ throw IOException("start") }, { throw IOException("stop") }, {})
        }.exceptionOrNull()!!
        assertTrue(failure is AutofocusReleaseException)
        assertEquals("stop", generateSequence(failure) { it.cause }.last().message)
        assertEquals("start", failure.suppressed.single().message)
    }
}
