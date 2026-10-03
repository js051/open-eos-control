package dev.openeos.control.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ShutterReleaseSessionTest {
    @Test fun failedReleaseRetainsOriginalStopAndPreventsAnotherPress() = runTest {
        val session = ShutterReleaseSession()
        var presses = 0
        var releases = 0
        val failure = runCatching {
            session.pressAndRelease({ presses++ }, { if (++releases == 1) throw IOException("lost release") })
        }.exceptionOrNull()
        assertTrue(failure is ShutterReleaseException)
        assertTrue(session.hasPendingRelease)
        assertTrue(session.releaseUnconfirmed)
        assertTrue(runCatching { session.start({ presses++ }, {}) }.isFailure)
        session.retryRelease()
        session.retryRelease()
        assertFalse(session.hasPendingRelease)
        assertFalse(session.releaseUnconfirmed)
        assertEquals(1, presses)
        assertEquals(2, releases)
    }

    @Test fun ambiguousPressAndFailedReleasePreserveBothFailures() = runTest {
        val session = ShutterReleaseSession()
        val pressFailure = IOException("lost press response")
        val releaseFailure = IOException("release rejected")
        val failure = runCatching {
            session.start({ throw pressFailure }, { throw releaseFailure })
        }.exceptionOrNull()!!
        assertTrue(failure is ShutterReleaseException)
        // Coroutine stacktrace recovery may copy a typed exception and retain the original as its cause.
        // Preserve the stricter contract: the exact release failure is the primary chain's root,
        // and the exact press failure survives as suppressed evidence on that chain.
        val primaryChain = generateSequence(failure) { it.cause }.toList()
        assertSame(releaseFailure, primaryChain.last())
        val suppressedCauses = primaryChain.flatMap { it.suppressed.toList() }
            .flatMap { generateSequence(it) { cause -> cause.cause }.toList() }
        assertTrue("The original press failure must remain suppressed evidence", suppressedCauses.any { it === pressFailure })
    }

    @Test fun cancellingPressStillReleasesAndRetainsFailedStopForRetry() = runTest {
        val session = ShutterReleaseSession()
        val entered = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Throwable?>()
        var releases = 0
        val job = launch {
            outcome.complete(runCatching {
                session.start({ entered.complete(Unit); awaitCancellation() }, {
                    if (++releases == 1) throw IOException("release rejected")
                })
            }.exceptionOrNull())
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(outcome.await() is ShutterReleaseException)
        session.retryRelease()
        assertEquals(2, releases)
    }

    @Test fun successfulHeldPressIsReleasedOnlyByExplicitStop() = runTest {
        val session = ShutterReleaseSession()
        val writes = mutableListOf<String>()
        session.start({ writes += "press" }, { writes += "release" })
        assertEquals(listOf("press"), writes)
        assertFalse(session.releaseUnconfirmed)
        session.retryRelease()
        assertEquals(listOf("press", "release"), writes)
    }
}
