package dev.openeos.control.ui

import dev.openeos.control.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MaintenanceWorkflowTest {
    private val owner = Any()
    private val target = MaintenanceClockTarget(1_700_000_000_000, 28_800, false)
    private val reference = MaintenanceClockReference(1_699_999_990, 1_700_000_010, 480, false)
    private val observation = MaintenanceClockObservation(1_700_000_000, 480, false)
    private fun request(action: MaintenanceAction = MaintenanceAction.CLOCK_SYNC, off: Boolean = false) = MaintenanceRequest(action, target.takeIf { action == MaintenanceAction.CLOCK_SYNC }, off)
    private fun attempt(request: MaintenanceRequest = request(), access: MaintenanceAccess, owns: () -> Boolean = { true },
        safe: () -> Boolean = { true }, timeout: Long = 1000) = MaintenanceAttempt(
            MaintenanceDraft(MaintenanceOwner(owner, 3), java.util.UUID.randomUUID().toString(), request), access, owns, safe, timeout, {})
    private fun access(dispatch: suspend (MaintenanceRequest, MaintenanceEvidence) -> Unit = { _, e -> e.beforeDispatch(); e.responseReceived(); if (e.request.clock != null) e.reference(reference) },
        read: suspend (MaintenanceRequest) -> MaintenanceReadback = { request -> MaintenanceReadback(observation.takeIf { request.clock != null }) },
        events: suspend () -> Unit = {}, stop: suspend () -> Unit = {}, restore: suspend () -> Unit = {}) =
        MaintenanceAccess("synthetic", stopEvents = events, stopLiveView = stop, dispatch = dispatch, read = read, restore = restore)

    @Test fun oneWriteOneInitialClockRead() = runBlocking {
        var writes = 0; var reads = 0
        val a = attempt(access = access(dispatch = { _, e -> writes++; e.beforeDispatch(); e.responseReceived(); e.reference(reference) }, read = { reads++; MaintenanceReadback(observation) }))
        a.run(); assertEquals(1, writes); assertEquals(1, reads)
        assertEquals(MaintenanceClockEvidence.MATCHED, a.snapshot().evidence.clockRead)
    }
    @Test fun timeAdvancesWithoutChangingInitialProofOrReference() = runBlocking {
        var reads = 0
        val a = attempt(access = access(read = { MaintenanceReadback(if (++reads == 1) observation else observation.copy(epochSeconds = observation.epochSeconds + 3600)) }))
        a.run(); val initial = a.snapshot().evidence
        a.recover(); assertEquals(initial.clockRead, a.snapshot().evidence.clockRead)
        assertEquals(initial.clockReference, a.snapshot().evidence.clockReference)
        assertEquals(initial.observedClock, a.snapshot().evidence.observedClock)
        assertEquals(observation.epochSeconds + 3600, a.snapshot().evidence.recoveryClock?.epochSeconds)
    }
    @Test fun laterRecoveryFailureDoesNotEraseInitialProof() = runBlocking {
        var reads = 0; val a = attempt(access = access(read = { if (++reads > 1) error("offline"); MaintenanceReadback(observation) }))
        a.run(); a.recover(); assertEquals(MaintenanceClockEvidence.MATCHED, a.snapshot().evidence.clockRead)
        assertEquals(observation, a.snapshot().evidence.observedClock); assertEquals(MaintenanceAvailability.UNAVAILABLE, a.snapshot().evidence.availability)
    }
    @Test fun acceptedWriteSurvivesInitialReadFailureAndRecoveryNeverUpgradesInitialRead() = runBlocking {
        var reads = 0; var writes = 0
        val a = attempt(access = access(dispatch = { _, e -> writes++; e.beforeDispatch(); e.responseReceived(); e.reference(reference) },
            read = { if (++reads == 1) error("read failed"); MaintenanceReadback(observation) }))
        a.run(); a.recover(); assertEquals(1, writes); assertEquals(2, reads)
        assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, a.snapshot().evidence.command)
        assertEquals(MaintenanceClockEvidence.UNAVAILABLE, a.snapshot().evidence.clockRead)
        assertEquals(observation, a.snapshot().evidence.recoveryClock)
    }
    @Test fun lostResponseGetsNoAutomaticReadOrReplay() = runBlocking {
        var writes = 0; var reads = 0
        val a = attempt(access = access(dispatch = { _, e -> e.beforeDispatch(); writes++; error("lost") }, read = { reads++; MaintenanceReadback(observation) }))
        a.run(); assertEquals(0, reads); a.recover(); assertEquals(1, reads); assertEquals(1, writes)
        assertEquals(MaintenanceCommandEvidence.UNKNOWN, a.snapshot().evidence.command)
    }
    @Test fun eventStopFailurePreventsLiveViewStopAndAction() = runBlocking {
        var stops = 0; var writes = 0
        val a = attempt(request(MaintenanceAction.SENSOR_CLEANING), access(events = { error("event stop lost") }, stop = { stops++ }, dispatch = { _, _ -> writes++ }))
        a.run(); assertEquals(0, stops); assertEquals(0, writes)
        assertEquals(MaintenanceStopEvidence.UNKNOWN, a.snapshot().evidence.eventStop)
        assertEquals(MaintenanceCommandEvidence.NOT_SENT, a.snapshot().evidence.command)
        assertFalse(a.restore())
    }
    @Test fun liveViewStopFailurePreventsActionAndRestore() = runBlocking {
        var writes = 0; var restores = 0
        val a = attempt(request(MaintenanceAction.SENSOR_CLEANING), access(stop = { error("stop lost") }, dispatch = { _, _ -> writes++ }, restore = { restores++ }))
        a.run(); assertEquals(0, writes); assertEquals(MaintenanceStopEvidence.STOPPED, a.snapshot().evidence.eventStop)
        assertEquals(MaintenanceStopEvidence.UNKNOWN, a.snapshot().evidence.liveViewStop); assertFalse(a.restore()); assertEquals(0, restores)
    }
    @Test fun cleanKeepPowerDoesNotAutomaticallyRestoreAndEveryRestoreRequiresFreshRead() = runBlocking {
        val order = mutableListOf<String>()
        val a = attempt(request(MaintenanceAction.SENSOR_CLEANING), access(events = { order += "events" }, stop = { order += "stop" },
            dispatch = { _, e -> order += "command"; e.beforeDispatch(); e.responseReceived() }, read = { order += "read"; MaintenanceReadback() }, restore = { order += "restore" }))
        a.run(); assertEquals(listOf("events", "stop", "command", "read"), order)
        a.recover(); assertEquals("read", order.last()); assertFalse("restore" in order)
        assertTrue(a.restore()); assertEquals(listOf("read", "restore"), order.takeLast(2))
        val count = order.size; assertFalse(a.restore()); assertEquals(count, order.size)
    }
    @Test fun sleepAndPowerOffHaveNoAutomaticFollowupOrRestore() = runBlocking {
        for (r in listOf(request(MaintenanceAction.SLEEP), request(MaintenanceAction.SENSOR_CLEANING, true))) {
            var reads = 0; var restores = 0
            val a = attempt(r, access(read = { reads++; MaintenanceReadback() }, restore = { restores++ }))
            a.run(); assertEquals(0, reads); assertFalse(a.restore()); assertEquals(0, restores); assertEquals(0, reads)
            a.recover(); assertEquals(1, reads); assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, a.snapshot().evidence.command)
        }
    }
    @Test fun freshProbeFailureDoesNotReuseEarlierReachableForRestore() = runBlocking {
        var reads = 0; var restores = 0
        val a = attempt(request(MaintenanceAction.SENSOR_CLEANING), access(read = { if (++reads > 1) error("offline"); MaintenanceReadback() }, restore = { restores++ }))
        a.run(); assertFalse(a.restore()); assertEquals(0, restores); assertEquals(2, reads)
    }
    @Test fun recordingOrBulbSafetyIsRecheckedAfterProbe() = runBlocking {
        var safe = true; var reads = 0; var restores = 0
        val a = attempt(request(MaintenanceAction.SENSOR_CLEANING), access(read = { if (++reads > 1) safe = false; MaintenanceReadback() }, restore = { restores++ }), safe = { safe })
        a.run(); assertFalse(a.restore()); assertEquals(0, restores)
    }
    @Test fun restoreFailureKeepsUnknownAndNeverRepeatsStart() = runBlocking {
        var starts = 0; var reads = 0
        val a = attempt(request(MaintenanceAction.SENSOR_CLEANING), access(read = { reads++; MaintenanceReadback() }, restore = { starts++; error("response lost") }))
        a.run(); assertFalse(a.restore()); assertEquals(MaintenanceRestoreEvidence.UNKNOWN, a.snapshot().evidence.restore)
        val count = reads; assertFalse(a.restore()); assertEquals(1, starts); assertEquals(count, reads)
    }
    @Test fun cancellationBeforeDispatchDoesNothing() = runBlocking {
        var writes = 0; val a = attempt(access = access(dispatch = { _, e -> e.beforeDispatch(); writes++ }))
        a.cancel(); a.run(); assertEquals(0, writes); assertEquals(MaintenanceCommandEvidence.NOT_SENT, a.snapshot().evidence.command)
    }
    @Test fun lateResponseAfterCancellationRemainsHistoryButDoesNotReadOrRestore() = runBlocking {
        var reads = 0; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val a = attempt(access = access(dispatch = { _, e -> e.beforeDispatch(); entered.complete(Unit); withContext(NonCancellable) { release.await(); e.responseReceived() } }, read = { reads++; MaintenanceReadback(observation) }))
        val job = launch { a.run() }; entered.await(); a.cancel(); job.cancel(); release.complete(Unit); job.join()
        assertEquals(0, reads); assertTrue(a.snapshot().cancelled); assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, a.snapshot().evidence.command)
    }
    @Test fun lateStopResponseIsRetainedButNoCommandAfterCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var writes = 0
        val a = attempt(request(MaintenanceAction.SLEEP), access(events = { entered.complete(Unit); release.await() }, dispatch = { _, _ -> writes++ }))
        val job = launch { a.run() }; entered.await(); a.cancel(); job.cancel(); release.complete(Unit); job.join()
        assertEquals(MaintenanceStopEvidence.STOPPED, a.snapshot().evidence.eventStop); assertEquals(0, writes)
    }
    @Test fun ownerReplacementRejectsLateReadAndAnyFurtherRecovery() = runBlocking {
        var owns = true; var reads = 0
        val a = attempt(access = access(read = { reads++; owns = false; MaintenanceReadback(observation) }), owns = { owns })
        a.run(); assertEquals(MaintenanceClockEvidence.UNAVAILABLE, a.snapshot().evidence.clockRead)
        assertNull(a.recover()); assertEquals(1, reads)
    }
    @Test fun cancelledRecoveryCannotPublishNoncancellableLateSample() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var reads = 0
        val a = attempt(access = access(read = { if (++reads == 1) MaintenanceReadback(observation) else {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }; MaintenanceReadback(observation.copy(epochSeconds = 1_700_009_000)) } }))
        a.run(); val job = launch { a.recover() }; entered.await(); a.cancel(); job.cancel(); release.complete(Unit); job.join()
        assertNull(a.snapshot().evidence.recoveryClock); assertEquals(observation, a.snapshot().evidence.observedClock)
    }
    @Test fun concurrentRecoveryIsAdmittedOnce() = runBlocking {
        var reads = 0; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val a = attempt(access = access(dispatch = { _, e -> e.beforeDispatch(); error("lost") }, read = { reads++; entered.complete(Unit); release.await(); MaintenanceReadback(observation) }))
        a.run(); val job = launch { a.recover() }; entered.await(); assertNull(a.recover()); release.complete(Unit); job.join(); assertEquals(1, reads)
    }
    @Test fun readBudgetHasNoRetry() = runBlocking {
        var reads = 0; val a = attempt(access = access(read = { reads++; delay(1000); MaintenanceReadback(observation) }), timeout = 10)
        a.run(); assertEquals(1, reads); assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, a.snapshot().evidence.command)
    }
    @Test fun tokensCannotBeReusedEvenAfterManyNewIntents() {
        val ledger = MaintenanceDraftLedger(); val original = MaintenanceDraft(MaintenanceOwner(owner, 3), "token", request())
        assertTrue(ledger.claim(original)); repeat(100) { assertTrue(ledger.claim(original.copy(token = "new-$it"))) }
        assertFalse(ledger.claim(original)); assertFalse(ledger.claim(original.copy(owner = MaintenanceOwner(Any(), 4))))
    }
    @Test fun historyBoundedWithoutMutatingEarlierAttempt() {
        var history = emptyList<MaintenanceReceipt>()
        repeat(30) { val a = attempt(access = access()); history = maintenanceHistory(history, a.snapshot()) }
        assertEquals(20, history.size); assertEquals(20, history.map { it.id }.distinct().size)
    }
    @Test fun duplicateDispatchForbidden() {
        val e = MaintenanceEvidence(request()); e.beforeDispatch(); assertTrue(runCatching { e.beforeDispatch() }.isFailure)
    }
    @Test fun bridgeInitialUnavailableNeverCreatesAnAutomaticReadRetry() = runBlocking {
        var reads = 0; val a = attempt(access = access(dispatch = { _, e -> e.beforeDispatch(); e.responseReceived(); e.initialUnavailable() }, read = { reads++; MaintenanceReadback(observation) }))
        a.run(); assertEquals(0, reads)
    }
    @Test fun recoveryWithoutOriginalReferenceIsObservationOnly() = runBlocking {
        val a = attempt(access = access(dispatch = { _, e -> e.beforeDispatch(); error("response lost") }))
        a.run(); a.recover(); assertNull(a.snapshot().evidence.clockReference); assertNull(a.snapshot().evidence.observedClock)
        assertEquals(observation, a.snapshot().evidence.recoveryClock); assertEquals(MaintenanceCommandEvidence.UNKNOWN, a.snapshot().evidence.command)
    }
    @Test fun eventStopDeadlinePreventsEveryLaterStep() = runBlocking {
        var writes = 0; var stops = 0
        val a = attempt(request(MaintenanceAction.SLEEP), access(events = { delay(10_000) }, stop = { stops++ }, dispatch = { _, _ -> writes++ }), timeout = 10)
        a.run(); assertEquals(0, stops); assertEquals(0, writes)
        assertEquals(MaintenanceStopEvidence.UNKNOWN, a.snapshot().evidence.eventStop); assertFalse(a.snapshot().cancelled)
    }
    @Test fun commandDeadlineRetainsUnknownWithoutReadOrReplay() = runBlocking {
        var reads = 0; var writes = 0
        val a = attempt(access = access(dispatch = { _, e -> e.beforeDispatch(); writes++; delay(10_000) }, read = { reads++; MaintenanceReadback(observation) }), timeout = 10)
        a.run(); assertEquals(1, writes); assertEquals(0, reads); assertFalse(a.snapshot().cancelled)
        assertEquals(MaintenanceCommandEvidence.UNKNOWN, a.snapshot().evidence.command)
    }
    @Test fun restoreDeadlineRemainsUnknownAndCannotReplay() = runBlocking {
        var starts = 0
        val a = attempt(request(MaintenanceAction.SENSOR_CLEANING), access(restore = { starts++; delay(10_000) }), timeout = 10)
        a.run(); assertFalse(a.restore()); assertFalse(a.restore()); assertEquals(1, starts)
        assertEquals(MaintenanceRestoreEvidence.UNKNOWN, a.snapshot().evidence.restore)
    }
    @Test fun originalCallerCancellationInterruptsBoundedPhaseIoAndPreventsNextDependency() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val cancelledIo = CompletableDeferred<Unit>(); var writes = 0; var next = 0
        val a = attempt(request(MaintenanceAction.SLEEP), access(events = {
            val watchers = maintenanceCancellationWatchers { cancelledIo.complete(Unit) }
            try { entered.complete(Unit); cancelledIo.await(); currentCoroutineContext()[MaintenanceIoContext]!!.requireActive(); next++ }
            finally { watchers.forEach { it.cancel() } }
        }, dispatch = { _, _ -> writes++ }))
        val job = launch { a.run() }; entered.await(); a.cancel(); job.cancel(); withTimeout(1000) { cancelledIo.await(); job.join() }
        assertEquals(0, writes); assertEquals(0, next); assertEquals(MaintenanceStopEvidence.UNKNOWN, a.snapshot().evidence.eventStop)
    }
    @Test fun phaseTimeoutCancelsActualIoWatcher() = runBlocking {
        val cancelledIo = CompletableDeferred<Unit>()
        val a = attempt(request(MaintenanceAction.SLEEP), access(events = {
            val watchers = maintenanceCancellationWatchers { cancelledIo.complete(Unit) }
            try { awaitCancellation() } finally { watchers.forEach { it.cancel() } }
        }), timeout = 10)
        a.run(); assertTrue(cancelledIo.isCompleted); assertEquals(MaintenanceCommandEvidence.NOT_SENT, a.snapshot().evidence.command)
    }
    @Test fun noLiveViewStillRequiresEventStopAndExplicitControlRestore() = runBlocking {
        var restores = 0
        val base = access(restore = { restores++ })
        val noPreview = MaintenanceAccess(base.origin, eventStopRequired = true, liveViewStopRequired = false,
            stopEvents = base.stopEvents, stopLiveView = {}, dispatch = base.dispatch, read = base.read, restore = base.restore)
        val a = attempt(request(MaintenanceAction.SENSOR_CLEANING), noPreview); a.run()
        assertEquals(MaintenanceStopEvidence.NOT_REQUIRED, a.snapshot().evidence.liveViewStop)
        assertEquals(MaintenanceStopEvidence.STOPPED, a.snapshot().evidence.eventStop)
        assertEquals(0, restores); assertTrue(a.restore()); assertEquals(1, restores)
    }
    @Test fun newerStopWarningCannotBeClearedByAReceiptOrNoOpRestore() {
        for (running in listOf(false, true)) for (required in listOf(false, true)) {
            assertFalse(maintenanceRestoreStopSafe(true, required, running, afterRestore = false))
            assertFalse(maintenanceRestoreStopSafe(true, required, running, afterRestore = true))
        }
        assertFalse(maintenanceRestoreStopSafe(false, true, false, afterRestore = true))
        assertFalse(maintenanceRestoreStopSafe(false, true, true, afterRestore = false))
        assertTrue(maintenanceRestoreStopSafe(false, true, true, afterRestore = true))
        assertTrue(maintenanceRestoreStopSafe(false, false, false, afterRestore = true))
    }
    @Test fun retiredCompletedReceiptKeepsInitialProofAndRejectsRecovery() = runBlocking {
        var reads = 0; val a = attempt(access = access(read = { reads++; MaintenanceReadback(observation) }))
        a.run(); a.retire(); assertFalse(a.snapshot().cancelled); assertTrue(a.snapshot().retired)
        assertNull(a.recover()); assertFalse(a.restore()); assertEquals(1, reads)
        assertEquals(MaintenanceClockEvidence.MATCHED, a.snapshot().evidence.clockRead)
    }

    @Test fun retainedStopPolicyCreatesFreshCallerScopeAfterCancelledAttempt() = runBlocking {
        val oldJob = Job(); oldJob.cancel()
        val old = MaintenanceIoContext(coroutineContext + oldJob) { throw CancellationException("retired attempt") }
        var oldCalls = 0
        withContext(old) { assertTrue(runCatching { maintenanceStopRecoveryScope { currentCoroutineContext()[MaintenanceIoContext]!!.requireActive(); oldCalls++ } }.isFailure) }
        var freshCalls = 0
        maintenanceStopRecoveryScope {
            currentCoroutineContext()[MaintenanceIoContext]!!.requireActive()
            assertNotSame(old, currentCoroutineContext()[MaintenanceIoContext]); freshCalls++
        }
        assertEquals(0, oldCalls); assertEquals(1, freshCalls)
    }
    @Test fun explicitStopRecoveryHasOwnDeadlineAndCancelsActualIo() = runBlocking {
        val cancelledIo = CompletableDeferred<Unit>()
        val failure = runCatching { maintenanceStopRecoveryScope(timeoutMillis = 10) {
            val watchers = maintenanceCancellationWatchers { cancelledIo.complete(Unit) }
            try { awaitCancellation() } finally { watchers.forEach { it.cancel() } }
        } }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException); assertTrue(cancelledIo.isCompleted)
    }
    @Test fun explicitStopRecoveryStillHonorsCurrentCallerCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val cancelledIo = CompletableDeferred<Unit>()
        val job = launch { maintenanceStopRecoveryScope {
            val watchers = maintenanceCancellationWatchers { cancelledIo.complete(Unit) }
            try { entered.complete(Unit); awaitCancellation() } finally { watchers.forEach { it.cancel() } }
        } }
        entered.await(); job.cancelAndJoin(); assertTrue(cancelledIo.isCompleted)
    }
    @Test fun maintenanceHoldAllowsOnlyExplicitSafeStopRecovery() {
        assertTrue(maintenanceMayRetryStop(true, true, false, true))
        assertFalse(maintenanceMayRetryStop(false, true, false, true))
        assertFalse(maintenanceMayRetryStop(true, false, false, true))
        assertFalse(maintenanceMayRetryStop(true, true, true, true))
        assertFalse(maintenanceMayRetryStop(true, true, false, false))
    }

    @Test fun explicitCloseRetiresOnceWithoutTurningUnknownStopIntoProof() = runBlocking {
        val calls = mutableListOf<String>()
        val unknown = maintenanceCloseOnce(true, stop = { calls += "stop"; error("lost") },
            retireRemote = { calls += "retire" }, retireLocal = { calls += "local" })
        assertTrue(unknown); assertEquals(listOf("stop", "retire", "local"), calls)
    }
    @Test fun explicitCloseRetirementFailureHasNoFallbackAndKeepsStopWarning() = runBlocking {
        val calls = mutableListOf<String>()
        val failure = runCatching { maintenanceCloseOnce(true, stop = { calls += "stop"; error("lost") },
            retireRemote = { calls += "retire"; error("unsupported") }, retireLocal = { calls += "local" }) }.exceptionOrNull()
        assertTrue(failure is MaintenanceRetirementUnconfirmedException)
        assertTrue((failure as MaintenanceRetirementUnconfirmedException).stopUnconfirmed)
        assertEquals(listOf("stop", "retire", "local"), calls)
    }
    @Test fun explicitCloseWithoutStopObligationOnlyRetiresAndStillClosesLocallyOnFailure() = runBlocking {
        val calls = mutableListOf<String>()
        val failure = runCatching { maintenanceCloseOnce(false, stop = { calls += "forbidden stop" },
            retireRemote = { calls += "retire"; error("lost") }, retireLocal = { calls += "local" }) }.exceptionOrNull()
        assertFalse((failure as MaintenanceRetirementUnconfirmedException).stopUnconfirmed)
        assertEquals(listOf("retire", "local"), calls)
    }
    @Test fun cancelledExplicitCloseNeverStartsRemoteRetirement() = runBlocking {
        val calls = mutableListOf<String>(); val entered = CompletableDeferred<Unit>()
        val job = launch { runCatching { maintenanceCloseOnce(true, stop = { entered.complete(Unit); awaitCancellation() },
            retireRemote = { calls += "forbidden retire" }, retireLocal = { calls += "local" }) } }
        entered.await(); job.cancelAndJoin(); assertEquals(listOf("local"), calls)
    }

    @Test fun maintenanceClosePreservesPhysicalReleaseThenStopThenRetirementOrder() = runBlocking {
        val calls = mutableListOf<String>()
        assertFalse(maintenanceCloseOnce(true, releasePhysical = { maintenancePhysicalReleaseOnce(
            autofocus = { calls += "focus" }, shutter = { calls += "shutter" }) },
            stop = { calls += "stop" }, retireRemote = { calls += "retire" }, retireLocal = { calls += "local" }))
        assertEquals(listOf("focus", "shutter", "stop", "retire", "local"), calls)
    }
    @Test fun failedFocusReleaseStillAttemptsOwnedShutterButCannotCrossStopSafetyGate() = runBlocking {
        val calls = mutableListOf<String>()
        val failure = runCatching { maintenanceCloseOnce(true, releasePhysical = { maintenancePhysicalReleaseOnce(
            autofocus = { calls += "focus"; error("focus lost") }, shutter = { calls += "shutter" }) },
            stop = { calls += "stop" }, retireRemote = { calls += "retire" }, retireLocal = { calls += "local" }) }.exceptionOrNull()
        assertTrue(failure is MaintenanceRetirementUnconfirmedException)
        failure as MaintenanceRetirementUnconfirmedException
        assertTrue(failure.physicalReleaseUnconfirmed); assertTrue(failure.stopUnconfirmed); assertFalse(failure.retirementUnconfirmed)
        assertEquals(listOf("focus", "shutter", "retire", "local"), calls)
    }
    @Test fun physicalReleaseStopAndServerRetirementFailuresRemainIndependent() = runBlocking {
        val calls = mutableListOf<String>()
        val failure = runCatching { maintenanceCloseOnce(true, releasePhysical = { calls += "release"; error("lost") },
            stop = { calls += "stop"; error("lost") }, retireRemote = { calls += "retire"; error("release pending") },
            retireLocal = { calls += "local" }) }.exceptionOrNull() as MaintenanceRetirementUnconfirmedException
        assertTrue(failure.physicalReleaseUnconfirmed); assertTrue(failure.stopUnconfirmed); assertTrue(failure.retirementUnconfirmed)
        assertEquals(listOf("release", "retire", "local"), calls)
    }
    @Test fun bothPhysicalReleaseFailuresKeepBothOriginalCauses() = runBlocking {
        val focus = IllegalStateException("focus lost"); val shutter = IllegalStateException("shutter lost")
        val failure = runCatching { maintenancePhysicalReleaseOnce(autofocus = { throw focus }, shutter = { throw shutter }) }.exceptionOrNull()
        assertSame(shutter, failure); assertTrue(shutter.suppressed.contains(focus))
    }

}
