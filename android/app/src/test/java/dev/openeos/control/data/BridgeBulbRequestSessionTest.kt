package dev.openeos.control.data

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BridgeBulbRequestSessionTest {
    @Test(timeout = 5000) fun unownedShortControlStillAcceptsFreshDualFalseRecoveryAfterLostStop() = runBlocking {
        val legacy = BridgeShutterReleaseSession("synthetic-legacy-session")
        legacy.markUnconfirmed()
        val result = legacy.stop(release = { throw IOException("synthetic lost short-control Stop") },
            read = { bulbStatus(false) })
        assertEquals(false, result.shutterReleaseUnconfirmed)
        assertFalse(legacy.hasUnownedReleaseResponsibility)
        assertFalse(legacy.hasReleaseResponsibility)
        assertFalse(legacy.unconfirmed)
    }

    @Test(timeout = 5000) fun unownedShortControlRejectsMissingOrNonFalseRecoveryEvidence() = runBlocking {
        for ((active, warning) in listOf(false to null, null to false, true to false, false to true, null to null)) {
            val legacy = BridgeShutterReleaseSession("synthetic-legacy-session")
            legacy.markUnconfirmed()
            val outcome = runCatching {
                legacy.stop(release = { throw IOException("synthetic lost short-control Stop") },
                    read = { bulbStatus(false).copy(bulbExposureActive = active, shutterReleaseUnconfirmed = warning) })
            }
            assertTrue(outcome.isFailure)
            assertTrue(legacy.hasUnownedReleaseResponsibility)
            assertTrue(legacy.unconfirmed)
            assertFalse(legacy.bulb.hasReleaseResponsibility)
        }
    }

    @Test(timeout = 5000) fun freshReleaseObservationExpiresAfterNewerStatusAndCachedStop() = runBlocking {
        val owner = session()
        val reservation = owner.reserve()
        owner.start(reservation) { BridgeBulbReceipt(it, "started", bulbStatus(true)) }
        owner.stop(reservation) { BridgeBulbReceipt(it, "released", bulbStatus(false)) }
        assertTrue(owner.hasFreshReleaseObservation(reservation))
        owner.observe(bulbStatus(false))
        assertFalse(owner.hasFreshReleaseObservation(reservation))
        owner.stop(reservation) { error("Cached Stop must not dispatch") }
        assertFalse(owner.hasFreshReleaseObservation(reservation))
        val secondOwner = session()
        val secondReservation = secondOwner.reserve()
        secondOwner.start(secondReservation) { BridgeBulbReceipt(it, "started", bulbStatus(true)) }
        secondOwner.stop(secondReservation) { BridgeBulbReceipt(it, "released", bulbStatus(false)) }
        assertTrue(secondOwner.hasFreshReleaseObservation(secondReservation))
        secondOwner.stop(secondReservation) { error("Repeated saved-owner Stop must not dispatch") }
        assertFalse(secondOwner.hasFreshReleaseObservation(secondReservation))
    }

    @Test(timeout = 5000) fun cachedTerminalWithoutStatusNeverCreatesFreshReleaseObservation() = runBlocking {
        for (outcome in listOf("released", "cancelled-before-start")) {
            val owner = session()
            val reservation = owner.reserve()
            owner.start(reservation) { BridgeBulbReceipt(it, "started", bulbStatus(true)) }
            val status = owner.stop(reservation) { BridgeBulbReceipt(it, outcome) }
            assertNotNull(status)
            assertNull(status?.bulbExposureActive)
            assertFalse(owner.hasFreshReleaseObservation(reservation))
            assertFalse(owner.hasReleaseResponsibility)
        }
    }

    @Test(timeout = 5000) fun oldHandleCannotRetireNewerReservedOwner() = runBlocking {
        val owner = session()
        val first = owner.reserve()
        owner.start(first) { BridgeBulbReceipt(it, "started", bulbStatus(true)) }
        owner.stop(first) { BridgeBulbReceipt(it, "released", bulbStatus(false)) }
        val second = owner.reserve()
        owner.requestRetirement(first)
        owner.start(second) { BridgeBulbReceipt(it, "started", bulbStatus(true)) }
        owner.stop(second) { BridgeBulbReceipt(it, "released", bulbStatus(false)) }
        assertNotNull(owner.reserve())
    }

    @Test(timeout = 5000) fun savedRetirementAfterReleaseRequiresBoundedCleanupAndRejectsReservation() = runBlocking {
        val owner = session()
        val reservation = owner.reserve()
        owner.start(reservation) { BridgeBulbReceipt(it, "started", bulbStatus(true)) }
        owner.stop(reservation) { BridgeBulbReceipt(it, "released", bulbStatus(false)) }
        assertFalse(owner.requiresBoundedRetirement)
        owner.requestRetirement(reservation)
        assertTrue(owner.requiresBoundedRetirement)
        assertTrue(owner.retire())
        assertTrue(runCatching { owner.reserve() }.isFailure)
    }

    private fun session(): BridgeBulbRequestSession {
        val sequence = AtomicInteger()
        return BridgeBulbRequestSession(newOwnerId = { "synthetic-owner-${sequence.incrementAndGet()}" })
    }

    @Test(timeout = 5000) fun pendingStartDoesNotPreventStopAndLateStartCannotRestoreDuty() = runBlocking {
        val owner = session()
        val startEntered = CompletableDeferred<String>()
        val finishStart = CompletableDeferred<Unit>()
        val start = async(Dispatchers.Default) { runCatching {
            owner.start(press = { id ->
                startEntered.complete(id)
                withContext(NonCancellable) { finishStart.await() }
                BridgeBulbReceipt(id, "started", bulbStatus(true))
            })
        } }
        val id = startEntered.await()
        val stopped = CompletableDeferred<String>()
        val stop = async(Dispatchers.Default) {
            owner.stop { stopId ->
                stopped.complete(stopId)
                BridgeBulbReceipt(stopId, "cancelled-before-start", bulbStatus(false))
            }
        }
        try {
            assertEquals(id, withTimeout(1000) { stopped.await() })
            stop.await()
            assertFalse(owner.hasReleaseResponsibility)
            assertFalse(owner.hasConfirmedStart)
        } finally {
            finishStart.complete(Unit)
            start.await()
            stop.await()
        }
        assertFalse(owner.hasReleaseResponsibility)
        assertFalse(owner.hasConfirmedStart)
    }

    @Test(timeout = 5000) fun reserveThenStopBeforeStartNeverDispatchesPress() = runBlocking {
        val owner = session()
        val reservation = owner.reserve()
        var presses = 0
        val stopOwnerIds = mutableListOf<String>()
        owner.stop(reservation) { id ->
            stopOwnerIds += id
            BridgeBulbReceipt(id, "cancelled-before-start", bulbStatus(false))
        }
        val startResult = runCatching {
            owner.start(reservation) { id -> presses++; BridgeBulbReceipt(id, "started", bulbStatus(true)) }
        }
        assertTrue(startResult.isFailure)
        assertEquals(0, presses)
        assertEquals(1, stopOwnerIds.size)
        assertFalse(owner.hasReleaseResponsibility)
    }

    @Test(timeout = 5000) fun ordinaryStatusNeverDischargesOwnedDuty() = runBlocking {
        val owner = session()
        owner.start(press = { BridgeBulbReceipt(it, "started", bulbStatus(true)) })
        owner.observe(bulbStatus(false))
        assertTrue(owner.hasReleaseResponsibility)
        val failedStop = runCatching { owner.stop { throw IOException("synthetic lost response") } }
        assertTrue(failedStop.isFailure)
        owner.observe(bulbStatus(false))
        assertTrue(owner.hasReleaseResponsibility)
        assertTrue(owner.unconfirmed)
    }

    @Test(timeout = 5000) fun wrongOwnerCannotAcknowledgeStartOrStop() = runBlocking {
        val owner = session()
        val start = runCatching { owner.start(press = { BridgeBulbReceipt("different-owner", "started", bulbStatus(true)) }) }
        assertTrue(start.isFailure)
        assertTrue(owner.hasReleaseResponsibility)
        assertTrue(owner.unconfirmed)
        val stop = runCatching { owner.stop { BridgeBulbReceipt("different-owner", "released", bulbStatus(false)) } }
        assertTrue(stop.isFailure)
        assertTrue(owner.hasReleaseResponsibility)
        assertTrue(owner.unconfirmed)
    }

    @Test(timeout = 5000) fun onlyExactTerminalOutcomesDischargeDuty() = runBlocking {
        for (outcome in listOf("started", "stopped", "Released", "", "released ")) {
            val owner = session()
            owner.start(press = { BridgeBulbReceipt(it, "started", bulbStatus(true)) })
            assertTrue("Unexpected terminal outcome $outcome must fail", runCatching {
                owner.stop { BridgeBulbReceipt(it, outcome, bulbStatus(false)) }
            }.isFailure)
            assertTrue(owner.hasReleaseResponsibility)
        }
        for (outcome in listOf("released", "cancelled-before-start")) {
            val owner = session()
            owner.start(press = { BridgeBulbReceipt(it, "started", bulbStatus(true)) })
            owner.stop { BridgeBulbReceipt(it, outcome) }
            assertFalse(owner.hasReleaseResponsibility)
        }
    }

    @Test(timeout = 5000) fun duplicateStopsShareOnePendingAttempt() = runBlocking {
        val owner = session()
        owner.start(press = { BridgeBulbReceipt(it, "started", bulbStatus(true)) })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val send: suspend (String) -> BridgeBulbReceipt = { id ->
            calls.incrementAndGet(); entered.complete(Unit); release.await()
            BridgeBulbReceipt(id, "released", bulbStatus(false))
        }
        val first = async(Dispatchers.Default) { owner.stop(send) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { owner.stop(send) }
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(1, calls.get())
        assertFalse(owner.hasReleaseResponsibility)
    }

    @Test(timeout = 5000) fun oldSavedStopCannotReleaseANewerOwner() = runBlocking {
        val owner = session()
        val first = owner.reserve()
        owner.start(first) { BridgeBulbReceipt(it, "started", bulbStatus(true)) }
        owner.stop(first) { BridgeBulbReceipt(it, "released", bulbStatus(false)) }
        val second = owner.reserve()
        owner.start(second) { BridgeBulbReceipt(it, "started", bulbStatus(true)) }
        val newId = owner.ownerId
        var staleWireCalls = 0
        runCatching { owner.stop(first) { staleWireCalls++; BridgeBulbReceipt(it, "released", bulbStatus(false)) } }
        assertEquals(0, staleWireCalls)
        assertEquals(newId, owner.ownerId)
        assertTrue(owner.hasReleaseResponsibility)
        assertTrue(owner.hasConfirmedStart)
    }

    @Test(timeout = 5000) fun closedSessionCannotBeRearmedByLateStartReceipt() = runBlocking {
        val owner = session()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val start = async(Dispatchers.Default) { runCatching {
            owner.start(press = { id ->
                entered.complete(Unit); withContext(NonCancellable) { release.await() }
                BridgeBulbReceipt(id, "started", bulbStatus(true))
            })
        } }
        entered.await()
        owner.close()
        release.complete(Unit)
        start.await()
        assertTrue(owner.closed)
        assertFalse(owner.hasConfirmedStart)
        assertTrue(runCatching { owner.reserve() }.isFailure)
    }

    @Test(timeout = 5000) fun cachedTerminalReceiptPreservesNewerObservedWarning() = runBlocking {
        val owner = session()
        owner.start(press = { BridgeBulbReceipt(it, "started", bulbStatus(true)) })
        owner.stop { BridgeBulbReceipt(it, "released", bulbStatus(false)) }
        owner.observe(bulbStatus(false).copy(shutterReleaseUnconfirmed = true))
        var replayedStops = 0
        val status = owner.stop { replayedStops++; BridgeBulbReceipt(it, "released", bulbStatus(false)) }
        assertEquals(0, replayedStops)
        assertTrue("A cached receipt must preserve the newer warning or withhold a stale display snapshot",
            status == null || status.shutterReleaseUnconfirmed == true)
        assertFalse(owner.hasReleaseResponsibility)
    }

    @Test(timeout = 5000) fun lateStartSnapshotCannotOverwriteNewerObservedWarning() = runBlocking {
        val owner = session()
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val start = async(Dispatchers.Default) {
            owner.start(press = { id -> entered.complete(Unit); response.await(); BridgeBulbReceipt(id, "started", bulbStatus(true)) })
        }
        entered.await()
        owner.observe(bulbStatus(false).copy(shutterReleaseUnconfirmed = true))
        response.complete(Unit)
        assertEquals(true, start.await().shutterReleaseUnconfirmed)
        assertEquals(true, owner.displayStatus?.shutterReleaseUnconfirmed)
        assertTrue(owner.hasReleaseResponsibility)
    }

    @Test(timeout = 5000) fun inFlightStopReceiptClearsOnlyDutyAndPreservesNewerWarning() = runBlocking {
        val owner = session()
        owner.start(press = { BridgeBulbReceipt(it, "started", bulbStatus(true)) })
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val stop = async(Dispatchers.Default) {
            owner.stop { id -> entered.complete(Unit); response.await(); BridgeBulbReceipt(id, "released", bulbStatus(false)) }
        }
        entered.await()
        owner.observe(bulbStatus(false).copy(shutterReleaseUnconfirmed = true))
        response.complete(Unit)
        assertEquals(true, stop.await()?.shutterReleaseUnconfirmed)
        assertEquals(true, owner.displayStatus?.shutterReleaseUnconfirmed)
        assertFalse(owner.hasReleaseResponsibility)
    }
}

private fun bulbStatus(active: Boolean) = CameraStatus(
    connected = true, batteryLevel = null, batteryStatus = "synthetic", recording = false,
    mode = "photo", mediaAvailable = true, remainingMinutes = null,
    exposure = ExposureState("100", "bulb", "4.0", "auto"),
    bulbExposureActive = active, shutterReleaseUnconfirmed = false,
)
