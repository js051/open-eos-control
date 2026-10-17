package dev.openeos.control.ui

import dev.openeos.control.data.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

private class Rig(val action: MaintenanceAction = MaintenanceAction.SENSOR_CLEANING) {
    val client = ExtractedMaintenanceClient(false)
    val queue = QueueDispatcher()
    val originalOwner = requireNotNull(client.owner)
    val repository = HostRepository(client)
    val vm = ExtractedMaintenanceViewModel(repository, queue)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val bodyClosed = CountDownLatch(1)
    val draft = vm.draft(action)
    var rawResponse = outcome(action).toString()
    var responseCode = 200
    val outcomeCalls get() = client.calls.filter { it.request.url.encodedPath.endsWith("/outcome") }
    val outcomeCount get() = outcomeCalls.size
    lateinit var id: String
    lateinit var originalJob: Job
    init {
        client.respond = { request, _ ->
            when {
                request.url.encodedPath.endsWith("/outcome") -> Response(responseCode, ResponseBody(object : ByteArrayInputStream(rawResponse.toByteArray()) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        entered.countDown(); check(release.await(35, TimeUnit.SECONDS)); return super.read(bytes, offset, length)
                    }
                    override fun close() { bodyClosed.countDown(); super.close() }
                }))
                request.method == "GET" -> client.jsonResponse(readback(action))
                else -> client.validResponse(request)
            }
        }
    }
    fun start() {
        vm.submitMaintenance(draft)
        originalJob = requireNotNull(vm.cameraOperationJobs[CameraOperation.MAINTENANCE])
        assertSame(originalJob, vm.activeJob)
        id = requireNotNull(vm.state.maintenanceReceipt).id
        assertTrue(queue.until { entered.count == 0L })
        assertEquals(MaintenanceCommandEvidence.UNKNOWN, receipt().evidence.command)
        assertEquals(1, outcomeCount)
        assertEquals(MaintenanceStopEvidence.NOT_REQUIRED, receipt().evidence.eventStop)
        assertEquals(MaintenanceStopEvidence.NOT_REQUIRED, receipt().evidence.liveViewStop)
    }
    fun receipt() = vm.state.maintenanceHistory.single { it.id == id }
    fun finish() {
        release.countDown()
        assertTrue(queue.until { bodyClosed.count == 0L })
        assertTrue(queue.until { originalJob.isCompleted })
        if (outcomeCalls.single().enqueueCount.get() > 0) {
            assertTrue("Late callback must fully return after evidence publication", queue.until { outcomeCalls.single().callbacksCompleted.get() > 0 })
        }
        queue.drain()
    }
    fun assertCleanupWhileHeld(message: String) {
        assertEquals(1L, release.count)
        val completed = queue.until(500) { repository.closed && !vm.state.connectionTeardownPending }
        // These facts establish the actual registered-job join boundary, not a source guard.
        if (!completed) {
            assertEquals(0, repository.disconnects)
            assertTrue(vm.state.connectionTeardownPending)
            assertFalse(originalJob.isCompleted)
        }
        assertTrue(message, completed)
        assertEquals(1, repository.disconnects)
        assertTrue(originalJob.isCompleted)
        assertFalse(client.sessionPresent)
        assertTrue(originalOwner.closed)
        assertEquals(1L, release.count)
        assertEquals(listOf("POST /v1/session/synthetic-session/maintenance/${action.wireName}/outcome",
            "DELETE /v1/session/synthetic-session/maintenance/control/session"),
            client.requests.map { "${it.method} ${it.url.encodedPath}" })
    }
}
private fun outcome(action: MaintenanceAction): JSONObject = JSONObject().put("version", 1).put("engine", "synthetic-engine")
    .put("operation", action.wireName).put("autoPowerOff", action == MaintenanceAction.SENSOR_CLEANING)
    .put("responseEvidence", "response-received").also {
        if (action == MaintenanceAction.CLOCK_SYNC) {
            it.put("clockReference", JSONObject().put("earliestEpochSeconds", 1_700_000_000L).put("latestEpochSeconds", 1_700_000_002L)
                .put("utcOffsetMinutes", 0).put("daylightSaving", false))
            it.put("clockObservation", clock(1_700_000_001L)).put("clockReadEvidence", "matched")
        }
    }
private fun clock(epoch: Long) = JSONObject().put("epochSeconds", epoch).put("utcOffsetMinutes", 0).put("daylightSaving", false)
private fun readback(action: MaintenanceAction): JSONObject = JSONObject().put("version", 1).put("engine", "synthetic-engine")
    .put("operation", action.wireName).put("observationEvidence", if (action == MaintenanceAction.CLOCK_SYNC) "observed" else "reachable")
    .put("clockObservation", if (action == MaintenanceAction.CLOCK_SYNC) clock(1_700_000_090L) else JSONObject.NULL)
private const val CANCEL_JOIN_MESSAGE = "Disconnect must reach repository cleanup while the cancelled original maintenance response body is still held"
private const val DEADLINE_JOIN_MESSAGE = "Real 15-second maintenance dispatch timeout must release the registered job joined by Disconnect while its body is still held"
private fun cancelDisconnectProbe(assertLate: Boolean) {
    val rig = Rig(); rig.start()
    try {
        rig.vm.cancelMaintenance(rig.id); rig.queue.drain()
        assertTrue(rig.queue.until { rig.outcomeCalls.single().cancelCount.get() > 0 })
        assertTrue(rig.receipt().cancelled)
        rig.vm.disconnect(); rig.queue.drain()
        assertTrue(rig.receipt().retired)
        rig.assertCleanupWhileHeld(CANCEL_JOIN_MESSAGE)
        assertFalse(rig.receipt().running)
        assertEquals(MaintenanceAvailability.UNAVAILABLE, rig.receipt().evidence.availability)
        if (assertLate) {
            val replacement = rig.client.replaceSession()
            val before = rig.vm.replaceConnection()
            val replacementLiveView = rig.client.liveViewOwnerIdentity
            val originalId = rig.id
            rig.finish()
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, rig.receipt().evidence.command)
            assertEquals(originalId, rig.receipt().id)
            assertTrue(rig.receipt().cancelled); assertTrue(rig.receipt().retired); assertFalse(rig.receipt().running)
            assertSame(replacement, rig.client.sessionIdentity)
            assertFalse(replacement.closed)
            assertSame(replacementLiveView, rig.client.liveViewOwnerIdentity)
            assertEquals(before.copy(maintenanceHistory = rig.vm.state.maintenanceHistory), rig.vm.state)
            assertEquals("replacement warning", rig.vm.state.error)
            assertEquals(1, rig.outcomeCount)
            assertFalse(rig.client.requests.any { it.method == "GET" })
        }
    } finally { rig.finish() }
}
private fun realDeadlineProbe() {
    val rig = Rig(); val began = System.nanoTime(); rig.start()
    try {
        // No shortened timeout and no caller cancellation before the production deadline.
        assertTrue(rig.queue.until(20_000) { rig.outcomeCalls.single().cancelCount.get() > 0 })
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        println("Actual production maintenance dispatch deadline: ${elapsed}ms; producer held=${rig.release.count == 1L}")
        assertTrue("The unmodified default dispatch deadline must expire at about 15 seconds", elapsed in 14_000..19_500)
        assertEquals(1L, rig.release.count)
        rig.vm.disconnect(); rig.queue.drain()
        rig.assertCleanupWhileHeld(DEADLINE_JOIN_MESSAGE)
        assertFalse(rig.receipt().running)
        assertTrue(rig.receipt().retired)
        assertEquals(MaintenanceCommandEvidence.UNKNOWN, rig.receipt().evidence.command)
    } finally { rig.finish() }
}
class BaselineMaintenanceDispatchTest {
    @Test(timeout = 7000) fun cancelledDispatchMustReleaseRegisteredJobBeforeDisconnectCleanup() = cancelDisconnectProbe(false)
    @Test(timeout = 25000) fun realFifteenSecondDispatchDeadlineMustReleaseDisconnectJoin() = realDeadlineProbe()
}
class FixedMaintenanceDispatchTest {
    @Test(timeout = 7000) fun cancelledDispatchReleasesDisconnectAndLateResponseIsOriginalHistoryOnly() = cancelDisconnectProbe(true)
    @Test(timeout = 25000) fun realDefaultDeadlineReleasesTheRegisteredDisconnectJoin() = realDeadlineProbe()

    @Test(timeout = 7000) fun sameDraftCannotDispatchAgainAfterCancellation() {
        val rig = Rig(); rig.start()
        try {
            rig.vm.submitMaintenance(rig.draft)
            rig.vm.cancelMaintenance(rig.id)
            assertTrue(rig.queue.until { rig.originalJob.isCompleted })
            rig.vm.submitMaintenance(rig.draft); rig.queue.drain()
            assertEquals(1, rig.outcomeCount)
            assertEquals(1L, rig.release.count)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun malformedLateResponseDoesNotUpgradeRetiredEvidenceOrReplacementState() {
        val rig = Rig(); rig.rawResponse = "{malformed"
        rig.start()
        try {
            rig.vm.disconnect(); rig.queue.drain(); rig.assertCleanupWhileHeld(CANCEL_JOIN_MESSAGE)
            val replacement = rig.client.replaceSession(); val before = rig.vm.replaceConnection()
            rig.finish()
            assertEquals(MaintenanceCommandEvidence.UNKNOWN, rig.receipt().evidence.command)
            assertEquals(before, rig.vm.state)
            assertSame(replacement, rig.client.sessionIdentity)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun mismatchedLateOutcomeCannotUpgradeOriginalHistory() {
        val rig = Rig(); rig.rawResponse = outcome(MaintenanceAction.SLEEP).toString(); rig.start()
        try {
            rig.vm.disconnect(); rig.queue.drain(); rig.assertCleanupWhileHeld(CANCEL_JOIN_MESSAGE)
            rig.finish()
            assertEquals(MaintenanceCommandEvidence.UNKNOWN, rig.receipt().evidence.command)
            assertTrue(rig.receipt().retired)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun httpFailureKeepsUnknownAndDoesNotReadOrReplay() {
        val rig = Rig()
        rig.client.respond = { request, _ -> if (request.url.encodedPath.endsWith("/outcome")) Response(503, null) else rig.client.validResponse(request) }
        rig.vm.submitMaintenance(rig.draft)
        val id = requireNotNull(rig.vm.state.maintenanceReceipt).id
        assertTrue(rig.queue.until { rig.vm.activeJob == null })
        val receipt = rig.vm.state.maintenanceHistory.single { it.id == id }
        assertEquals(MaintenanceCommandEvidence.UNKNOWN, receipt.evidence.command)
        assertFalse(receipt.running)
        assertEquals(MaintenanceAvailability.UNAVAILABLE, receipt.evidence.availability)
        assertEquals(1, rig.outcomeCount)
        assertFalse(rig.client.requests.any { it.method == "GET" })
    }

    @Test(timeout = 7000) fun cancellationOfClockDispatchRetainsLateCompleteReceiptInOriginalHistoryOnly() {
        val rig = Rig(MaintenanceAction.CLOCK_SYNC); rig.start()
        try {
            rig.vm.cancelMaintenance(rig.id)
            assertTrue(rig.queue.until { rig.originalJob.isCompleted })
            assertEquals(MaintenanceClockEvidence.UNAVAILABLE, rig.receipt().evidence.clockRead)
            val before = rig.vm.state
            rig.finish()
            val late = rig.receipt().evidence
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, late.command)
            assertEquals(MaintenanceClockEvidence.MATCHED, late.clockRead)
            assertEquals(1_700_000_001L, late.observedClock?.epochSeconds)
            assertEquals(before.copy(maintenanceHistory = rig.vm.state.maintenanceHistory), rig.vm.state)
            assertNull(rig.vm.state.lastClockSyncAtMillis)
            assertFalse(rig.client.requests.any { it.method == "GET" })
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun lateClockReceiptDoesNotOverwriteNewerExplicitRecoveryObservation() {
        val rig = Rig(MaintenanceAction.CLOCK_SYNC); rig.start()
        try {
            rig.vm.cancelMaintenance(rig.id)
            assertTrue(rig.queue.until { rig.originalJob.isCompleted })
            runBlocking { assertNotNull(rig.vm.attempt(rig.id).recover()) }
            val recovered = rig.receipt().evidence
            assertEquals(1_700_000_090L, recovered.recoveryClock?.epochSeconds)
            assertEquals(MaintenanceAvailability.REACHABLE, recovered.availability)
            rig.finish()
            val late = rig.receipt().evidence
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, late.command)
            assertEquals(recovered.recoveryClock, late.recoveryClock)
            assertEquals(recovered.availability, late.availability)
            assertEquals(1_700_000_001L, late.observedClock?.epochSeconds)
            assertEquals(1, rig.outcomeCount)
            assertEquals(1, rig.client.requests.count { it.method == "GET" })
        } finally { rig.finish() }
    }
    @Test(timeout = 7000) fun duplicateCallbackAppliesOriginalResponseExactlyOnce() {
        val client = ExtractedMaintenanceClient(false)
        client.callbackCopies = 2
        val responses = CopyOnWriteArrayList<Response>()
        client.respond = { _, _ -> client.jsonResponse(outcome(MaintenanceAction.SENSOR_CLEANING)).also { responses += it } }
        val request = MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING, autoPowerOff = true)
        val publications = AtomicInteger()
        val evidence = MaintenanceEvidence(request, publish = { publications.incrementAndGet() },
            eventStopRequired = false, liveViewStopRequired = false)
        runBlocking { client.maintenanceAccess(request, null).dispatch(request, evidence) }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (client.calls.single().callbacksCompleted.get() < 2 && System.nanoTime() < until) Thread.sleep(5)
        assertEquals(2, client.calls.single().callbacksCompleted.get())
        assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, evidence.snapshot().command)
        assertEquals("Before-dispatch plus exactly one original receipt publication", 2, publications.get())
        assertEquals(1, client.calls.single().enqueueCount.get())
        assertEquals(2, responses.size)
        assertTrue(responses.all { it.closes.get() == 1 })
    }

    @Test(timeout = 7000) fun lateResponseCannotReviveEvictedOriginalHistory() {
        val rig = Rig(); rig.start()
        try {
            rig.vm.disconnect(); rig.queue.drain(); rig.assertCleanupWhileHeld(CANCEL_JOIN_MESSAGE)
            rig.vm._uiState.value = rig.vm.state.copy(maintenanceHistory = emptyList())
            val before = rig.vm.state
            rig.finish()
            assertEquals(before, rig.vm.state)
            assertTrue(rig.vm.state.maintenanceHistory.isEmpty())
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, rig.vm.attempt(rig.id).snapshot().evidence.command)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun validLateCommandWithMalformedClockChildKeepsResponseButNoClockProof() {
        val rig = Rig(MaintenanceAction.CLOCK_SYNC)
        rig.rawResponse = outcome(MaintenanceAction.CLOCK_SYNC).put("clockReference", "bad")
            .put("clockObservation", JSONObject().put("epochSeconds", "bad")).toString()
        rig.start()
        try {
            rig.vm.cancelMaintenance(rig.id)
            assertTrue(rig.queue.until { rig.originalJob.isCompleted })
            rig.finish()
            val late = rig.receipt().evidence
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, late.command)
            assertEquals(MaintenanceClockEvidence.UNAVAILABLE, late.clockRead)
            assertNull(late.observedClock)
            assertNull(rig.vm.state.lastClockSyncAtMillis)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun lateClockResponseCannotEraseNewerFailedRecoveryAvailability() {
        val rig = Rig(MaintenanceAction.CLOCK_SYNC); rig.start()
        try {
            rig.vm.cancelMaintenance(rig.id)
            assertTrue(rig.queue.until { rig.originalJob.isCompleted })
            val oldRespond = rig.client.respond
            rig.client.respond = { request, call -> if (request.method == "GET") Response(503, null) else oldRespond(request, call) }
            runBlocking { assertNull(rig.vm.attempt(rig.id).recover()) }
            assertEquals(MaintenanceAvailability.UNAVAILABLE, rig.receipt().evidence.availability)
            rig.finish()
            val late = rig.receipt().evidence
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, late.command)
            assertEquals(MaintenanceClockEvidence.MATCHED, late.clockRead)
            assertEquals(MaintenanceAvailability.UNAVAILABLE, late.availability)
            assertNull(late.recoveryClock)
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun unrelatedRegisteredJobStillMustJoinBeforeDisconnectCleanup() {
        val rig = Rig(); rig.start()
        val otherGate = CompletableDeferred<Unit>()
        try {
            rig.vm.cancelMaintenance(rig.id)
            assertTrue(rig.queue.until { rig.originalJob.isCompleted })
            val other = rig.vm.viewModelScope.launch { withContext(NonCancellable) { otherGate.await() } }
            rig.vm.cameraOperationJobs[CameraOperation.FOCUS] = other
            rig.queue.drain()
            rig.vm.disconnect(); rig.queue.drain()
            assertFalse(rig.queue.until(200) { rig.repository.disconnects > 0 })
            assertFalse(other.isCompleted)
            assertEquals(1L, rig.release.count)
            otherGate.complete(Unit)
            rig.assertCleanupWhileHeld(CANCEL_JOIN_MESSAGE)
        } finally { otherGate.complete(Unit); rig.finish() }
    }

    @Test(timeout = 7000) fun lateResponsePreservesOriginalRetirementErrorAndPhysicalReleaseWarning() {
        val rig = Rig(); rig.start()
        try {
            rig.client.setFocusFailure(IllegalArgumentException("synthetic original autofocus release"))
            val oldRespond = rig.client.respond
            rig.client.respond = { request, call -> if (request.method == "DELETE") Response(503, null) else oldRespond(request, call) }
            rig.vm.disconnect(); rig.queue.drain(); rig.assertCleanupWhileHeld(CANCEL_JOIN_MESSAGE)
            val before = rig.vm.state
            assertEquals(CameraAppError.MAINTENANCE_RETIREMENT_UNCONFIRMED.messageToken, before.error)
            assertTrue(before.shutterDisconnectWarning)
            rig.finish()
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, rig.receipt().evidence.command)
            assertEquals(before.copy(maintenanceHistory = rig.vm.state.maintenanceHistory), rig.vm.state)
        } finally { rig.finish() }
    }

    @Test(timeout = 25000) fun realClockDeadlineRetainsLateOriginalProofOnlyAfterRetirement() {
        val rig = Rig(MaintenanceAction.CLOCK_SYNC); val began = System.nanoTime(); rig.start()
        try {
            assertTrue(rig.queue.until(20_000) { rig.originalJob.isCompleted })
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
            println("Actual production clock dispatch deadline: ${elapsed}ms; producer held=${rig.release.count == 1L}")
            assertTrue(elapsed in 14_000..19_500)
            assertEquals(1L, rig.release.count)
            assertEquals(MaintenanceClockEvidence.UNAVAILABLE, rig.receipt().evidence.clockRead)
            assertEquals(MaintenanceCommandEvidence.UNKNOWN, rig.receipt().evidence.command)
            rig.vm.disconnect(); rig.queue.drain(); rig.assertCleanupWhileHeld(DEADLINE_JOIN_MESSAGE)
            val replacement = rig.client.replaceSession(); val before = rig.vm.replaceConnection()
            rig.finish()
            val late = rig.receipt().evidence
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, late.command)
            assertEquals(MaintenanceClockEvidence.MATCHED, late.clockRead)
            assertEquals(1_700_000_001L, late.observedClock?.epochSeconds)
            assertEquals(before.copy(maintenanceHistory = rig.vm.state.maintenanceHistory), rig.vm.state)
            assertEquals(777L, rig.vm.state.lastClockSyncAtMillis)
            assertSame(replacement, rig.client.sessionIdentity)
            assertFalse(rig.client.requests.any { it.method == "GET" })
        } finally { rig.finish() }
    }

    @Test(timeout = 7000) fun callbackIoFailureKeepsUnknownWithoutReadOrReplay() {
        val rig = Rig()
        rig.client.respond = { _, _ -> throw IOException("synthetic connection failure") }
        rig.vm.submitMaintenance(rig.draft)
        val id = requireNotNull(rig.vm.state.maintenanceReceipt).id
        assertTrue(rig.queue.until { rig.vm.activeJob == null })
        val receipt = rig.vm.state.maintenanceHistory.single { it.id == id }
        assertEquals(MaintenanceCommandEvidence.UNKNOWN, receipt.evidence.command)
        assertFalse(receipt.running)
        assertEquals(MaintenanceAvailability.UNAVAILABLE, receipt.evidence.availability)
        assertEquals(1, rig.outcomeCount)
        assertFalse(rig.client.requests.any { it.method == "GET" })
    }

    @Test(timeout = 7000) fun completeCurrentSensorResponseFinishesOnceWithoutReadOrRestore() {
        val rig = Rig()
        rig.client.respond = { _, _ -> rig.client.jsonResponse(outcome(MaintenanceAction.SENSOR_CLEANING)) }
        rig.vm.submitMaintenance(rig.draft)
        assertTrue(rig.queue.until { rig.vm.activeJob == null })
        val receipt = requireNotNull(rig.vm.state.maintenanceReceipt)
        assertFalse(receipt.running); assertFalse(receipt.cancelled); assertFalse(receipt.retired)
        assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, receipt.evidence.command)
        assertEquals(MaintenanceRestoreEvidence.NOT_REQUESTED, receipt.evidence.restore)
        assertEquals(1, rig.outcomeCount)
        assertEquals(1, rig.client.requests.size)
    }

    @Test(timeout = 7000) fun completeCurrentClockResponsePublishesMatchedProofWithoutExtraRead() {
        val rig = Rig(MaintenanceAction.CLOCK_SYNC)
        rig.client.respond = { _, _ -> rig.client.jsonResponse(outcome(MaintenanceAction.CLOCK_SYNC)) }
        rig.vm.submitMaintenance(rig.draft)
        assertTrue(rig.queue.until { rig.vm.activeJob == null })
        val receipt = requireNotNull(rig.vm.state.maintenanceReceipt)
        assertFalse(receipt.running)
        assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, receipt.evidence.command)
        assertEquals(MaintenanceClockEvidence.MATCHED, receipt.evidence.clockRead)
        assertEquals(rig.draft.request.clock!!.epochMillis, rig.vm.state.lastClockSyncAtMillis)
        assertEquals(1, rig.outcomeCount)
        assertEquals(1, rig.client.requests.size)
    }

}
