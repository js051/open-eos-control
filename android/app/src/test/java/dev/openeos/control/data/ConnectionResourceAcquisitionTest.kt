package dev.openeos.control.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Host coroutine ownership evidence. No HTTP, Android lifecycle or physical device is exercised. */
class ConnectionResourceAcquisitionTest {
    @Test fun cancelledBeforeAcquisitionDoesNotDispatch() = runBlocking {
        var calls = 0
        val job = launch(start = CoroutineStart.LAZY) {
            acquireConnectionResource(acquire = { calls++; Any() }, retain = {})
        }
        job.cancelAndJoin()
        assertEquals(0, calls)
    }

    @Test fun cancelledDuringAcquisitionRetainsExactOwnerButCannotPublish() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resource = Any()
        var retained: Any? = null
        var published = false
        var closed: Any? = null
        val job = launch {
            try {
                acquireConnectionResource(acquire = {
                    entered.complete(Unit)
                    withContext(Dispatchers.Default) { release.await(); resource }
                }, retain = { retained = it })
                published = true
            } finally {
                withContext(NonCancellable) { closed = retained }
            }
        }
        entered.await()
        job.cancel()
        assertNull(retained)
        release.complete(Unit)
        withTimeout(2_000) { job.join() }
        assertSame(resource, retained)
        assertSame(resource, closed)
        assertFalse(published)
    }

    @Test fun lateAcquisitionFailureCannotBecomeSuccessOrAnOwner() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var retained = false
        var published = false
        var failed = false
        val job = launch {
            try {
                acquireConnectionResource(acquire = {
                    entered.complete(Unit); release.await(); throw IllegalStateException("synthetic")
                }, retain = { _: Unit -> retained = true })
                published = true
            } catch (_: Exception) { failed = true }
        }
        entered.await(); job.cancel(); release.complete(Unit)
        withTimeout(2_000) { job.join() }
        assertFalse(retained); assertFalse(published); assertTrue(failed)
    }

    @Test fun successfulOwnerIsRetainedBeforeReturning() = runBlocking {
        val owner = Any(); var retained: Any? = null
        acquireConnectionResource(acquire = { owner }, retain = { retained = it })
        currentCoroutineContext().ensureActive()
        assertSame(owner, retained)
    }
    @Test fun malformedSessionIdsCannotBecomeCleanupTargets() {
        for (value in listOf(null, 12, true, "", " ", "../other", "owner/path", "owner?token=value", "a".repeat(129))) {
            assertNull(bridgeCreatedSessionId(value))
        }
        assertEquals("synthetic-owner_1", bridgeCreatedSessionId("synthetic-owner_1"))
    }
    @Test fun onlyEmpty204AcknowledgesSessionRetirement() {
        assertTrue(bridgeSessionRetirementAcknowledged(204, ""))
        for (status in listOf(200, 201, 202, 206, 302, 404, 500)) assertFalse(bridgeSessionRetirementAcknowledged(status, ""))
        for (body in listOf(" ", "{}", "synthetic")) assertFalse(bridgeSessionRetirementAcknowledged(204, body))
    }
    @Test fun typedPreviousSafetyCodesDoNotBecomeOrdinaryConnectionErrors() {
        for ((code, warning) in listOf(
            "SHUTTER_RELEASE_UNCONFIRMED" to PreviousConnectionSafetyWarning.SHUTTER,
            "LIVE_VIEW_STOP_UNCONFIRMED" to PreviousConnectionSafetyWarning.LIVE_VIEW,
            "RECORDING_COMMAND_UNCONFIRMED" to PreviousConnectionSafetyWarning.RECORDING)) {
            assertEquals(setOf(warning), previousConnectionSafetyWarnings(
                DesktopBridgeException(code = code, message = "synthetic", statusCode = 502)))
        }
    }
}
