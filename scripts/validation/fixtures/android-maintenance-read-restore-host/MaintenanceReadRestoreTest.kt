package dev.openeos.control.ui

import dev.openeos.control.data.*
import java.io.*
import java.util.concurrent.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

private enum class BlockedPhase { INITIAL_READ, RECOVERY_READ, RESTORE_READ, RESTORE_START }
private const val JOIN_MESSAGE = "Blocked maintenance read or strict restore Start must release the registered job before Disconnect cleanup"
private fun outcome() = JSONObject().put("version", 1).put("engine", "synthetic-engine")
    .put("operation", "sensor-cleaning").put("autoPowerOff", false).put("responseEvidence", "response-received")
private fun readback() = JSONObject().put("version", 1).put("engine", "synthetic-engine")
    .put("operation", "sensor-cleaning").put("observationEvidence", "reachable").put("clockObservation", JSONObject.NULL)

private class ReadRestoreRig(val phase: BlockedPhase) {
    val client = ExtractedMaintenanceClient(true)
    val queue = QueueDispatcher()
    val originalOwner = requireNotNull(client.owner)
    val repository = HostRepository(client)
    val vm = ExtractedMaintenanceViewModel(repository, queue)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val bodyClosed = CountDownLatch(1)
    val draft = vm.draft(autoPowerOff = false)
    var blockEnabled = phase == BlockedPhase.INITIAL_READ
    var readResponse = readback().toString()
    var startResponse = JSONObject().put("active", true).put("liveViewStopUnconfirmed", false)
        .put("restoreToken", "synthetic_restore_token_000000001").put("restoreOutcome", "started").toString()
    lateinit var id: String
    lateinit var originalJob: Job
    lateinit var blockedCall: HostCall
    val requests get() = client.requests.map { "${it.method} ${it.url.encodedPath}" }
    val reads get() = client.requests.count { it.method == "GET" }
    val starts get() = client.requests.count { it.url.encodedPath.endsWith("/start") }
    val dispatches get() = client.requests.count { it.url.encodedPath.endsWith("/outcome") }
    init {
        client.respond = { request, call ->
            val read = request.method == "GET"
            val start = request.url.encodedPath.endsWith("/start")
            when {
                blockEnabled && ((read && phase != BlockedPhase.RESTORE_START) || (start && phase == BlockedPhase.RESTORE_START)) -> {
                    blockedCall = call
                    if (start) {
                        assertTrue("Strict Stop duty must be recorded before Start I/O", client.liveViewStopRequired)
                        assertTrue("Start must retain strict maintenance Stop responsibility", client.maintenanceStopOwned)
                        assertFalse(repository.liveViewRunning)
                    }
                    Response(200, ResponseBody(object : ByteArrayInputStream((if (read) readResponse else startResponse).toByteArray()) {
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            entered.countDown(); check(release.await(45, TimeUnit.SECONDS)); return super.read(bytes, offset, length)
                        }
                        override fun close() { bodyClosed.countDown(); super.close() }
                    }))
                }
                request.url.encodedPath.endsWith("/outcome") -> client.jsonResponse(outcome())
                read -> client.jsonResponse(readback())
                else -> client.validResponse(request)
            }
        }
    }
    fun start() {
        vm.submitMaintenance(draft)
        id = requireNotNull(vm.state.maintenanceReceipt).id
        if (phase != BlockedPhase.INITIAL_READ) {
            assertTrue(queue.until { vm.activeJob == null })
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, receipt().evidence.command)
            assertEquals(MaintenanceAvailability.REACHABLE, receipt().evidence.availability)
            assertEquals(MaintenanceStopEvidence.STOPPED, receipt().evidence.liveViewStop)
            assertTrue(receipt().restoreAvailable)
            assertFalse(repository.liveViewRunning)
            assertFalse(client.liveViewStopRequired)
            blockEnabled = true
            if (phase == BlockedPhase.RECOVERY_READ) vm.recoverMaintenance(id) else vm.restoreMaintenance(id)
        }
        originalJob = requireNotNull(vm.cameraOperationJobs[CameraOperation.MAINTENANCE])
        assertSame(originalJob, vm.activeJob)
        assertTrue(queue.until { entered.count == 0L })
        assertEquals(1, dispatches)
        assertEquals(if (phase == BlockedPhase.INITIAL_READ) 1 else 2, reads)
        assertEquals(if (phase == BlockedPhase.RESTORE_START) 1 else 0, starts)
        assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, receipt().evidence.command)
    }
    fun receipt() = vm.state.maintenanceHistory.single { it.id == id }
    fun finish() {
        release.countDown()
        assertTrue(queue.until { bodyClosed.count == 0L })
        assertTrue(queue.until { originalJob.isCompleted })
        if (blockedCall.enqueueCount.get() > 0) assertTrue(queue.until { blockedCall.callbacksCompleted.get() > 0 })
        queue.drain()
    }
    fun assertCleanupWhileHeld(waitMillis: Long = 500) {
        assertEquals(1L, release.count)
        val completed = queue.until(waitMillis) { repository.closed && !vm.state.connectionTeardownPending }
        if (!completed) {
            assertEquals("The Disconnect job has not yet passed its real registered-job join", 0, repository.disconnects)
            assertTrue(vm.state.connectionTeardownPending)
            assertFalse(originalJob.isCompleted)
            println("Actual runtime counterexample: phase=$phase; repository.disconnect=0; registeredJob.completed=false; held=${release.count == 1L}; execute=${blockedCall.executeCount}; enqueue=${blockedCall.enqueueCount}")
        }
        assertTrue(JOIN_MESSAGE, completed)
        assertEquals(1, repository.disconnects)
        assertTrue(originalJob.isCompleted)
        assertFalse(client.sessionPresent)
        assertTrue(originalOwner.closed)
        assertEquals(1L, release.count)
        val tail = if (phase == BlockedPhase.RESTORE_START) listOf(
            "POST /v1/session/synthetic-session/maintenance/control/liveview/stop",
            "DELETE /v1/session/synthetic-session/maintenance/control/session")
        else listOf("DELETE /v1/session/synthetic-session/maintenance/control/session")
        assertEquals(tail, requests.takeLast(tail.size))
        assertTrue("Every request must remain in the strict maintenance namespace", requests.all { "/maintenance/" in it })
        assertFalse("Late Start must not mark repository live view running", repository.liveViewRunning)
    }
}
private fun cancelProbe(phase: BlockedPhase, assertLate: Boolean = false) {
    val rig = ReadRestoreRig(phase); rig.start()
    try {
        rig.vm.cancelMaintenance(rig.id); rig.queue.drain()
        assertTrue(rig.queue.until { rig.blockedCall.cancelCount.get() > 0 })
        assertTrue(rig.receipt().cancelled)
        rig.vm.disconnect(); rig.queue.drain()
        assertTrue(rig.receipt().retired)
        // Restore mutation deliberately retains its bounded NonCancellable phase. Its default deadline is real.
        rig.assertCleanupWhileHeld(if (phase == BlockedPhase.RESTORE_START) 17_000 else 500)
        if (assertLate) {
            val original = rig.receipt()
            val replacement = rig.client.replaceSession()
            val before = rig.vm.replaceConnection()
            val replacementStop = rig.client.liveViewOwnerIdentity
            val requests = rig.requests
            rig.finish()
            assertEquals(original, rig.receipt())
            assertEquals(before, rig.vm.state)
            assertSame(replacement, rig.client.sessionIdentity)
            assertFalse(replacement.closed)
            assertSame(replacementStop, rig.client.liveViewOwnerIdentity)
            assertEquals(requests, rig.requests)
            assertEquals(0, rig.vm.restoredUiCalls)
            assertFalse(rig.repository.liveViewRunning)
        }
    } finally { rig.finish() }
}
private fun realDeadlineProbe() {
    val rig = ReadRestoreRig(BlockedPhase.INITIAL_READ); val began = System.nanoTime(); rig.start()
    try {
        assertTrue(rig.queue.until(20_000) { rig.blockedCall.cancelCount.get() > 0 })
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        println("Actual unmodified 15000ms maintenance read deadline: ${elapsed}ms; producer held=${rig.release.count == 1L}")
        assertTrue(elapsed in 14_000..19_500)
        assertEquals(1L, rig.release.count)
        rig.vm.disconnect(); rig.queue.drain()
        rig.assertCleanupWhileHeld()
        assertEquals(MaintenanceAvailability.UNAVAILABLE, rig.receipt().evidence.availability)
    } finally { rig.finish() }
}
class BaselineMaintenanceReadRestoreTest {
    @Test(timeout = 7000) fun cancelledInitialReadMustReleaseRegisteredJob() = cancelProbe(BlockedPhase.INITIAL_READ)
    @Test(timeout = 7000) fun cancelledExplicitRecoveryMustReleaseRegisteredJob() = cancelProbe(BlockedPhase.RECOVERY_READ)
    @Test(timeout = 7000) fun cancelledRestoreFreshReadMustReleaseRegisteredJob() = cancelProbe(BlockedPhase.RESTORE_READ)
    @Test(timeout = 25000) fun realFifteenSecondReadDeadlineMustReleaseRegisteredJob() = realDeadlineProbe()
    @Test(timeout = 25000) fun cancelledStrictRestoreStartMustReleaseRegisteredJobAtPhaseDeadline() = cancelProbe(BlockedPhase.RESTORE_START)
}
class FixedMaintenanceReadRestoreTest {
    @Test(timeout = 7000) fun cancelledInitialReadReleasesJobAndLateBodyCannotPublish() = cancelProbe(BlockedPhase.INITIAL_READ, true)
    @Test(timeout = 7000) fun cancelledExplicitRecoveryReleasesJobAndLateBodyCannotPublish() = cancelProbe(BlockedPhase.RECOVERY_READ, true)
    @Test(timeout = 7000) fun cancelledRestoreFreshReadReleasesJobAndLateBodyCannotStart() = cancelProbe(BlockedPhase.RESTORE_READ, true)
    @Test(timeout = 25000) fun realFifteenSecondReadDeadlineReleasesRegisteredJob() = realDeadlineProbe()
    @Test(timeout = 7000) fun strictRestoreStartRetainsRegisteredJoinUntilItsBoundedPhaseEnds() {
        val rig = ReadRestoreRig(BlockedPhase.RESTORE_START); rig.start()
        try {
            rig.vm.cancelMaintenance(rig.id); rig.vm.disconnect(); rig.queue.drain()
            assertTrue(rig.queue.until { rig.blockedCall.cancelCount.get() > 0 })
            assertFalse(rig.queue.until(200) { rig.originalJob.isCompleted })
            assertEquals(0, rig.repository.disconnects)
            assertEquals(1L, rig.release.count)
            assertTrue(rig.client.liveViewStopRequired); assertTrue(rig.client.maintenanceStopOwned)
            assertEquals(0, rig.blockedCall.executeCount.get()); assertEquals(1, rig.blockedCall.enqueueCount.get())
        } finally { rig.finish() }
        assertTrue(rig.queue.until { rig.repository.closed })
        assertEquals(listOf("POST /v1/session/synthetic-session/maintenance/control/liveview/stop",
            "DELETE /v1/session/synthetic-session/maintenance/control/session"), rig.requests.takeLast(2))
        assertEquals(0, rig.vm.restoredUiCalls)
    }

    @Test(timeout = 7000) fun cancelledReadCannotEraseOriginalRetirementErrorOrPhysicalWarning() {
        val rig = ReadRestoreRig(BlockedPhase.INITIAL_READ); rig.start()
        try {
            rig.client.setFocusFailure(IllegalArgumentException("synthetic original autofocus release"))
            val old = rig.client.respond
            rig.client.respond = { request, call -> if (request.method == "DELETE") Response(503, null) else old(request, call) }
            rig.vm.disconnect(); rig.queue.drain(); rig.assertCleanupWhileHeld()
            val before = rig.vm.state
            assertEquals(CameraAppError.MAINTENANCE_RETIREMENT_UNCONFIRMED.messageToken, before.error)
            assertTrue(before.shutterDisconnectWarning)
            rig.finish()
            assertEquals(before, rig.vm.state)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun unrelatedRegisteredJobStillMustJoinBeforeDisconnectCleanup() {
        val rig = ReadRestoreRig(BlockedPhase.INITIAL_READ); rig.start()
        val gate = CompletableDeferred<Unit>()
        try {
            rig.vm.cancelMaintenance(rig.id)
            assertTrue(rig.queue.until { rig.originalJob.isCompleted })
            val other = rig.vm.viewModelScope.launch { withContext(NonCancellable) { gate.await() } }
            rig.vm.cameraOperationJobs[CameraOperation.FOCUS] = other
            rig.queue.drain(); rig.vm.disconnect(); rig.queue.drain()
            assertFalse(rig.queue.until(200) { rig.repository.disconnects > 0 })
            assertFalse(other.isCompleted); assertEquals(1L, rig.release.count)
            gate.complete(Unit); rig.assertCleanupWhileHeld()
        } finally { gate.complete(Unit); rig.finish() }
    }

    @Test(timeout = 7000) fun cancelledReadCannotPublishAfterNewWarningWithoutDisconnect() {
        val rig = ReadRestoreRig(BlockedPhase.RESTORE_READ); rig.start()
        try {
            rig.vm.cancelMaintenance(rig.id)
            assertTrue(rig.queue.until { rig.originalJob.isCompleted })
            rig.vm._uiState.value = rig.vm.state.copy(error = "new safety warning", shutterReleaseUnconfirmed = true)
            val before = rig.vm.state
            val receipt = rig.receipt()
            val requests = rig.requests
            rig.finish()
            assertEquals(before, rig.vm.state); assertEquals(receipt, rig.receipt()); assertEquals(requests, rig.requests)
            assertEquals(0, rig.starts); assertFalse(rig.repository.liveViewRunning)
            assertEquals(0, rig.vm.restoredUiCalls)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun freshRestoreReadRechecksNewWarningBeforeStarting() {
        val rig = ReadRestoreRig(BlockedPhase.RESTORE_READ); rig.start()
        try {
            rig.vm._uiState.value = rig.vm.state.copy(error = "new safety warning", shutterReleaseUnconfirmed = true)
            rig.finish()
            assertEquals("new safety warning", rig.vm.state.error); assertTrue(rig.vm.state.shutterReleaseUnconfirmed)
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, rig.receipt().evidence.command)
            assertEquals(MaintenanceAvailability.REACHABLE, rig.receipt().evidence.availability)
            assertEquals(MaintenanceRestoreEvidence.NOT_REQUESTED, rig.receipt().evidence.restore)
            assertEquals(0, rig.starts); assertEquals(0, rig.vm.restoredUiCalls)
            assertFalse(rig.repository.liveViewRunning)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun replacementWithIdenticalSessionTextRejectsFreshReadAndRestore() {
        val rig = ReadRestoreRig(BlockedPhase.RESTORE_READ); rig.start()
        try {
            val replacement = rig.client.replaceSession()
            val before = rig.vm.replaceConnection()
            val stop = rig.client.liveViewOwnerIdentity
            rig.finish()
            assertEquals(before.copy(maintenanceHistory = rig.vm.state.maintenanceHistory), rig.vm.state)
            assertSame(replacement, rig.client.sessionIdentity); assertFalse(replacement.closed)
            assertSame(stop, rig.client.liveViewOwnerIdentity)
            assertEquals(0, rig.starts); assertEquals(0, rig.vm.restoredUiCalls)
            assertFalse(rig.repository.liveViewRunning)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun lateReadCannotReviveEvictedOriginalHistory() {
        val rig = ReadRestoreRig(BlockedPhase.RECOVERY_READ); rig.start()
        try {
            rig.vm.cancelMaintenance(rig.id)
            assertTrue(rig.queue.until { rig.originalJob.isCompleted })
            rig.vm._uiState.value = rig.vm.state.copy(maintenanceHistory = emptyList())
            val before = rig.vm.state
            rig.finish(); assertEquals(before, rig.vm.state); assertTrue(rig.vm.state.maintenanceHistory.isEmpty())
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun lateOldReadCannotOverwriteNewerAttemptOrReplayItsCommand() {
        val rig = ReadRestoreRig(BlockedPhase.INITIAL_READ); rig.start()
        try {
            rig.vm.cancelMaintenance(rig.id)
            assertTrue(rig.queue.until { rig.originalJob.isCompleted })
            rig.blockEnabled = false
            val newerDraft = rig.vm.draft(autoPowerOff = false)
            rig.vm.submitMaintenance(newerDraft)
            assertTrue(rig.queue.until { rig.vm.activeJob == null })
            val before = rig.vm.state
            assertNotEquals(rig.id, requireNotNull(before.maintenanceReceipt).id)
            val oldReceipt = rig.receipt(); val requests = rig.requests
            rig.finish()
            assertEquals(before, rig.vm.state); assertEquals(oldReceipt, rig.receipt()); assertEquals(requests, rig.requests)
            assertEquals(2, rig.dispatches); assertEquals(0, rig.starts)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun malformedLateReadCannotChangeRetiredHistoryOrReplacement() {
        val rig = ReadRestoreRig(BlockedPhase.RECOVERY_READ); rig.readResponse = "{malformed"; rig.start()
        try {
            rig.vm.disconnect(); rig.queue.drain(); rig.assertCleanupWhileHeld()
            val replacement = rig.client.replaceSession(); val before = rig.vm.replaceConnection()
            rig.finish()
            assertEquals(before, rig.vm.state); assertSame(replacement, rig.client.sessionIdentity)
            assertEquals(0, rig.starts); assertEquals(1, rig.dispatches)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun currentExplicitRecoveryReadsOnceAndDoesNotRestoreOrReplay() {
        val rig = ReadRestoreRig(BlockedPhase.RECOVERY_READ); rig.start()
        try {
            rig.finish()
            assertFalse(rig.receipt().recovering); assertFalse(rig.receipt().running)
            assertEquals(MaintenanceAvailability.REACHABLE, rig.receipt().evidence.availability)
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, rig.receipt().evidence.command)
            assertEquals(MaintenanceRestoreEvidence.NOT_REQUESTED, rig.receipt().evidence.restore)
            assertEquals(2, rig.reads); assertEquals(1, rig.dispatches); assertEquals(0, rig.starts)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun currentExplicitRestoreRequiresFreshReadAndPreservesStrictStopDuty() {
        val rig = ReadRestoreRig(BlockedPhase.RESTORE_READ); rig.start()
        try {
            rig.finish()
            assertEquals(MaintenanceRestoreEvidence.RESTORED, rig.receipt().evidence.restore)
            assertEquals(2, rig.reads); assertEquals(1, rig.dispatches); assertEquals(1, rig.starts)
            assertTrue(rig.repository.liveViewRunning)
            assertTrue(rig.client.liveViewStopRequired); assertTrue(rig.client.maintenanceStopOwned)
            assertFalse(rig.vm.state.maintenanceSuspended); assertTrue(rig.vm.state.liveViewAutoRefresh)
            assertEquals(3, rig.vm.restoredUiCalls)
            val call = rig.client.calls.single { it.request.url.encodedPath.endsWith("/start") }
            assertEquals(0, call.executeCount.get()); assertEquals(1, call.enqueueCount.get())
            rig.vm.disconnect(); assertTrue(rig.queue.until { rig.repository.closed })
            assertEquals(listOf("POST /v1/session/synthetic-session/maintenance/control/liveview/stop",
                "DELETE /v1/session/synthetic-session/maintenance/control/session"), rig.requests.takeLast(2))
        } finally { rig.finish() }
    }

}
