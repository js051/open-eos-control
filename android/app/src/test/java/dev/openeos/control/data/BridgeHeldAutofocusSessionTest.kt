package dev.openeos.control.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BridgeHeldAutofocusSessionTest {
    @Test fun normalHoldUsesOneOwnerForStartAndStop() = runBlocking {
        val commands = mutableListOf<Pair<String, Boolean>>()
        val session = BridgeHeldAutofocusSession(command = { id, start -> commands += id to start }, newOwnerId = { "owner-1" })
        session.hold {
            assertEquals(true, session.active)
            assertTrue(session.hasReleaseResponsibility)
        }
        assertEquals(listOf("owner-1" to true, "owner-1" to false), commands)
        assertFalse(session.hasReleaseResponsibility)
        assertEquals(false, session.active)
        session.retryStop()
        assertEquals(2, commands.size)
    }

    @Test fun failedStartAndStopRetainOriginalOwnerAndFailures() = runBlocking {
        val commands = mutableListOf<Pair<String, Boolean>>()
        var failStop = true
        val session = BridgeHeldAutofocusSession(command = { id, start ->
            commands += id to start
            if (start || failStop) error(if (start) "Synthetic lost start" else "Synthetic failed stop")
        }, newOwnerId = { "original-owner" })
        val failure = runCatching { session.hold { fail("Unconfirmed start must not enter hold") } }.exceptionOrNull()
        assertTrue(failure is AutofocusReleaseException)
        assertEquals(1, failure!!.suppressed.size)
        assertNull(session.active)
        assertTrue(session.stopUnconfirmed)
        assertTrue(runCatching { session.hold {} }.isFailure)
        failStop = false
        session.retryStop()
        session.retryStop()
        assertEquals(listOf("original-owner" to true, "original-owner" to false, "original-owner" to false), commands)
        assertFalse(session.hasReleaseResponsibility)
    }

    @Test fun cancellationDuringStartStillReleasesOwner() = runBlocking {
        val commands = mutableListOf<Pair<String, Boolean>>()
        val entered = CompletableDeferred<Unit>()
        val session = BridgeHeldAutofocusSession(command = { id, start ->
            commands += id to start
            if (start) { entered.complete(Unit); awaitCancellation() }
        }, newOwnerId = { "cancelled-owner" })
        val job = launch { session.hold { fail("Start never acknowledged") } }
        entered.await()
        job.cancelAndJoin()
        assertEquals(listOf("cancelled-owner" to true, "cancelled-owner" to false), commands)
        assertFalse(session.hasReleaseResponsibility)
    }

    @Test fun cancellationWhileHeldStopsExactlyOnce() = runBlocking {
        val commands = mutableListOf<Boolean>()
        val entered = CompletableDeferred<Unit>()
        val session = BridgeHeldAutofocusSession(command = { _, start -> commands += start })
        val job = launch { session.hold { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        job.cancelAndJoin()
        assertEquals(listOf(true, false), commands)
    }

    @Test fun oldGestureFinallyCannotReleaseNewerOwner() = runBlocking {
        val commands = mutableListOf<Pair<String, Boolean>>()
        var next = 0
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val session = BridgeHeldAutofocusSession(command = { id, start -> commands += id to start }, newOwnerId = { "owner-${++next}" })
        val first = launch(start = CoroutineStart.UNDISPATCHED) { session.hold { firstRelease.await() } }
        session.retryStop()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { session.hold { secondRelease.await() } }
        firstRelease.complete(Unit)
        first.join()
        assertEquals(listOf("owner-1" to true, "owner-1" to false, "owner-2" to true), commands)
        assertEquals(true, session.active)
        secondRelease.complete(Unit)
        second.join()
        assertEquals("owner-2" to false, commands.last())
    }

    @Test fun closeReleasesActiveHoldAndRejectsFutureStarts() = runBlocking {
        val commands = mutableListOf<Boolean>()
        val release = CompletableDeferred<Unit>()
        val session = BridgeHeldAutofocusSession(command = { _, start -> commands += start })
        val job = launch(start = CoroutineStart.UNDISPATCHED) { session.hold { release.await() } }
        session.close()
        assertTrue(runCatching { session.hold {} }.isFailure)
        release.complete(Unit)
        job.join()
        assertEquals(listOf(true, false), commands)
    }

    @Test fun failedCloseRetainsStopOnlyRecovery() = runBlocking {
        val commands = mutableListOf<Boolean>()
        var failStop = true
        val release = CompletableDeferred<Unit>()
        val session = BridgeHeldAutofocusSession(command = { _, start ->
            commands += start
            if (!start && failStop) error("Synthetic failed stop")
        })
        val job = launch(start = CoroutineStart.UNDISPATCHED) { session.hold { release.await() } }
        assertTrue(runCatching { session.close() }.exceptionOrNull() is AutofocusReleaseException)
        assertTrue(session.hasReleaseResponsibility)
        assertNull(session.active)
        failStop = false
        session.retryStop()
        release.complete(Unit)
        job.join()
        assertEquals(listOf(true, false, false), commands)
    }
}
