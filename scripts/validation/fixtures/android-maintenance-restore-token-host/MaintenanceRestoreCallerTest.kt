package dev.openeos.control.ui

import dev.openeos.control.data.*
import java.io.*
import java.util.concurrent.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

private enum class BlockedPhase { INITIAL_READ, RECOVERY_READ, RESTORE_READ, RESTORE_START }
private const val RESTORE_TOKEN = "synthetic_restore_token_000000001"
private const val JOIN_MESSAGE = "Blocked maintenance read or strict restore Start must release the registered job before Disconnect cleanup"
private fun outcome() = JSONObject().put("version", 1).put("engine", "synthetic-engine")
    .put("operation", "sensor-cleaning").put("autoPowerOff", false).put("responseEvidence", "response-received")
private fun readback() = JSONObject().put("version", 1).put("engine", "synthetic-engine")
    .put("operation", "sensor-cleaning").put("observationEvidence", "reachable").put("clockObservation", JSONObject.NULL)

private class ReadRestoreRig(val phase: BlockedPhase, val beforeBody: Boolean = false) {
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
        .put("restoreToken", RESTORE_TOKEN).put("restoreOutcome", "started").toString()
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
                    if (beforeBody) { entered.countDown(); check(release.await(45, TimeUnit.SECONDS)) }
                    Response(200, ResponseBody(object : ByteArrayInputStream((if (read) readResponse else startResponse).toByteArray()) {
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            entered.countDown(); check(release.await(45, TimeUnit.SECONDS)); return super.read(bytes, offset, length)
                        }
                        override fun close() { bodyClosed.countDown(); super.close() }
                    }))
                }
                request.url.encodedPath.endsWith("/outcome") -> client.jsonResponse(outcome())
                read -> client.jsonResponse(readback())
                else -> client.jsonResponse(JSONObject().put("active", start).put("liveViewStopUnconfirmed", false)
                    .put("restoreToken", RESTORE_TOKEN).put("restoreOutcome", if (start) "started" else "stopped"))
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
private fun cancelProbe(phase: BlockedPhase, assertLate: Boolean = false, beforeBody: Boolean = false) {
    val rig = ReadRestoreRig(phase, beforeBody); rig.start()
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
    val rig = ReadRestoreRig(BlockedPhase.RESTORE_START); val began = System.nanoTime(); rig.start()
    try {
        assertTrue(rig.queue.until(20_000) { rig.blockedCall.cancelCount.get() > 0 })
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        println("Actual unmodified 15000ms maintenance restore Start deadline: ${elapsed}ms; producer held=${rig.release.count == 1L}")
        assertTrue(elapsed in 14_000..19_500)
        assertEquals(1L, rig.release.count)
        rig.vm.disconnect(); rig.queue.drain()
        rig.assertCleanupWhileHeld()
        assertEquals(MaintenanceRestoreEvidence.UNKNOWN, rig.receipt().evidence.restore)
    } finally { rig.finish() }
}
class FixedRestoreTokenCallerTest {
    @Test(timeout = 25000) fun cancelledRestoreStartIsBoundedAndLateBodyCannotPublishToReplacement() = cancelProbe(BlockedPhase.RESTORE_START, true)
    @Test(timeout = 25000) fun ignoredCancellationBeforeResponseIsBounded() = cancelProbe(BlockedPhase.RESTORE_START, true, beforeBody = true)
    @Test(timeout = 25000) fun realDefaultRestoreStartDeadlineReleasesRegisteredJob() = realDeadlineProbe()
    @Test(timeout = 7000) fun missingTokenOldBridgeShowsLocalizedTypedReceiptWithoutStart() {
        val client = ExtractedMaintenanceClient(true); val queue = QueueDispatcher()
        val repository = HostRepository(client); val vm = ExtractedMaintenanceViewModel(repository, queue)
        client.respond = { request, _ -> when {
            request.url.encodedPath.endsWith("/outcome") -> client.jsonResponse(outcome())
            request.method == "GET" -> client.jsonResponse(readback())
            else -> client.validResponse(request)
        } }
        vm.submitMaintenance(vm.draft(autoPowerOff = false)); assertTrue(queue.until { vm.activeJob == null })
        val id = requireNotNull(vm.state.maintenanceReceipt).id
        vm.restoreMaintenance(id); assertTrue(queue.until { vm.activeJob == null })
        assertEquals(MaintenanceRestoreEvidence.BRIDGE_UNAVAILABLE, vm.state.maintenanceReceipt?.evidence?.restore)
        assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, vm.state.maintenanceReceipt?.evidence?.command)
        assertTrue(client.requests.none { it.url.encodedPath.endsWith("/start") })
        assertFalse(repository.liveViewRunning); assertFalse(client.liveViewStopRequired)
        assertTrue(vm.state.maintenanceSuspended); assertEquals(0, vm.restoredUiCalls)
    }
}

class RestoreTokenUiWarningTest {
    @Test(timeout = 7000) fun newerUiStatusSurvivesCachedRestoreStopReceipt() = runBlocking {
        val client = ExtractedMaintenanceClient(true); val queue = QueueDispatcher()
        val repository = HostRepository(client); val vm = ExtractedMaintenanceViewModel(repository, queue)
        client.respond = { sent, _ -> client.jsonResponse(JSONObject().put("active", sent.url.encodedPath.endsWith("/start"))
            .put("liveViewStopUnconfirmed", false).put("restoreToken", RESTORE_TOKEN)
            .put("restoreOutcome", if (sent.url.encodedPath.endsWith("/start")) "started" else "stopped")) }
        val access = client.maintenanceAccess(MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING), LiveViewRequest())
        access.stopLiveView(); access.restore()
        vm._uiState.value = vm.state.copy(maintenanceSuspended = true, needsLiveViewStopRecovery = true,
            liveViewStopUnconfirmed = true, status = CameraStatus(liveViewStopUnconfirmed = true))
        val newer = CameraStatus(liveViewStopUnconfirmed = true, bulbExposureActive = false)
        client.respond = { _, _ -> vm._uiState.value = vm.state.copy(status = newer, liveViewStopUnconfirmed = true)
            client.jsonResponse(JSONObject().put("restoreToken", RESTORE_TOKEN).put("restoreOutcome", "stopped")) }
        vm.restartLiveView(); assertTrue(queue.until { vm.state.pendingOperations.isEmpty() })
        assertSame("Newer UI observation must survive a cached receipt", newer, vm.state.status)
        assertTrue(vm.state.liveViewStopUnconfirmed); assertFalse(client.liveViewStopRequired)
        assertFalse(repository.liveViewRunning); assertTrue(vm.state.maintenanceSuspended)
    }
}
