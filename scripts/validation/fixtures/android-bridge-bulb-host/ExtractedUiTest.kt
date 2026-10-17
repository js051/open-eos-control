package dev.openeos.control.ui

import dev.openeos.control.data.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Test

class ExtractedUiTest {
    @Test(timeout = 5000) fun newerActiveUiObservationPreventsResumeDespiteFreshCoreReleaseBit() {
        val rig = Rig()
        val response = CompletableDeferred<Unit>()
        rig.stopAction = { response.await(); rig.duty = false; status(false) }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        val newerActive = status(true)
        rig.vm._uiState.update { it.copy(status = newerActive, error = "newer active observation") }
        response.complete(Unit); rig.queue.drain()
        assertSame(newerActive, rig.vm.state.status)
        assertEquals("newer active observation", rig.vm.state.error)
        assertFalse(rig.vm.state.bridgeBulbStopRequired)
        assertEquals(0, rig.vm.liveViewResumes)
    }

    @Test(timeout = 5000) fun disconnectDoesNotJoinIgnoredCancelStartBeforeRepositoryCleanup() {
        val rig = Rig()
        val gate = CompletableDeferred<Unit>()
        rig.startAction = { withContext(NonCancellable) { gate.await() }; status(true) }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.disconnect(); rig.queue.drain()
        assertEquals(1, rig.retirements)
        assertEquals(1, rig.repository.disconnects)
        assertFalse(gate.isCompleted)
        val disconnected = rig.vm.state
        gate.complete(Unit); rig.queue.drain()
        assertEquals(disconnected, rig.vm.state)
    }

    @Test(timeout = 5000) fun disconnectDoesNotJoinBlockedPostStopLiveViewResume() {
        val rig = Rig()
        val resumeGate = CompletableDeferred<Unit>()
        rig.vm.resumeAction = { withContext(NonCancellable) { resumeGate.await() } }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        assertFalse(rig.duty)
        assertTrue(rig.vm.state.bridgeBulbStopPending)
        rig.vm.disconnect(); rig.queue.drain()
        assertEquals(1, rig.retirements)
        assertEquals(1, rig.repository.disconnects)
        assertFalse(resumeGate.isCompleted)
        val disconnected = rig.vm.state
        resumeGate.complete(Unit); rig.queue.drain()
        assertEquals(disconnected, rig.vm.state)
    }

    @Test(timeout = 5000) fun nullCachedStopKeepsNewerStatusWarningAndDoesNotResumeLiveView() {
        val rig = Rig()
        rig.freshRelease = false
        rig.stopAction = { rig.duty = false; null }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        val later = status(false).copy(shutterReleaseUnconfirmed = true)
        rig.vm._uiState.update { it.copy(status = later, shutterReleaseUnconfirmed = true, error = "newer warning") }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        assertSame(later, rig.vm.state.status)
        assertTrue(rig.vm.state.shutterReleaseUnconfirmed)
        assertFalse(rig.vm.state.bridgeBulbStopRequired)
        assertEquals(0, rig.vm.liveViewResumes)
    }

    @Test(timeout = 5000) fun actualClientCachedReceiptReturnsSanitizedSnapshotToActualUi() {
        val client = ExtractedOwnedClient()
        val queue = QueueDispatcher()
        val vm = ExtractedBulbViewModel(HostRepository { client.prepareOwnedBulb() }, queue)
        vm.toggleBulbExposure(); queue.drain(); queue.until { !vm.state.bridgeBulbStartPending }
        assertTrue(vm.state.bulbExposureActive)
        client.respond = { request, _ -> client.jsonResponse(JSONObject()
            .put("ownerId", JSONObject(request.body!!).opt("ownerId")).put("outcome", "released")) }
        vm.toggleBulbExposure(); queue.drain(); queue.until { !vm.state.bridgeBulbStopPending }
        assertNotNull(vm.state.status)
        assertNull(vm.state.status?.bulbExposureActive)
        assertFalse(vm.state.bridgeBulbStopRequired)
        assertEquals(0, vm.liveViewResumes)
        assertEquals(2, client.requests.size)
    }

    @Test(timeout = 5000) fun lateStartKeepsNewerUiStatusAndWarning() {
        val rig = Rig()
        val response = CompletableDeferred<Unit>()
        rig.startAction = { response.await(); status(true) }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        val newer = status(false).copy(shutterReleaseUnconfirmed = true)
        rig.vm._uiState.update { it.copy(status = newer, error = "newer status warning") }
        response.complete(Unit); rig.queue.drain()
        assertSame(newer, rig.vm.state.status)
        assertEquals("newer status warning", rig.vm.state.error)
        assertTrue(rig.vm.state.bridgeBulbStopRequired)
    }

    @Test(timeout = 5000) fun inFlightStopKeepsNewerUiStatusAndWarning() {
        val rig = Rig()
        val response = CompletableDeferred<Unit>()
        rig.stopAction = { response.await(); rig.duty = false; status(false) }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        val newer = status(false).copy(shutterReleaseUnconfirmed = true)
        rig.vm._uiState.update { it.copy(status = newer, error = "newer status warning") }
        response.complete(Unit); rig.queue.drain()
        assertSame(newer, rig.vm.state.status)
        assertEquals("newer status warning", rig.vm.state.error)
        assertTrue(rig.vm.state.shutterReleaseUnconfirmed)
        assertFalse(rig.vm.state.bridgeBulbStopRequired)
        assertEquals(0, rig.vm.liveViewResumes)
    }

    @Test(timeout = 5000) fun actualAdmissionKeepsStopAndStatusAvailableAcrossOwnedPhases() {
        for (active in listOf(false, true)) {
            val pending = HostUiState(status = status(active), bridgeBulbStopRequired = true,
                bridgeBulbStartPending = !active, pendingOperations = setOf(CameraOperation.CAPTURE))
            assertFalse(pending.isBusy(CameraOperation.SHUTTER_RELEASE))
            assertFalse(pending.isBusy(CameraOperation.STATUS))
            for (operation in CameraOperation.entries - setOf(CameraOperation.SHUTTER_RELEASE, CameraOperation.STATUS)) {
                assertTrue("Owned duty must block $operation", pending.isBusy(operation))
            }
            assertTrue(pending.copy(bridgeBulbStopPending = true).isBusy(CameraOperation.SHUTTER_RELEASE))
        }
    }

    @Test(timeout = 5000) fun reservesBeforeLaunchAndNextTapStopsDespiteCaptureBusy() {
        val rig = Rig()
        rig.vm.toggleBulbExposure()
        assertEquals(1, rig.repository.reservations)
        assertTrue(rig.vm.state.bridgeBulbStopRequired)
        assertTrue(rig.vm.state.bridgeBulbStartPending)
        assertTrue(CameraOperation.CAPTURE in rig.vm.state.pendingOperations)
        assertEquals(0, rig.starts)
        rig.vm.toggleBulbExposure()
        assertTrue(rig.vm.state.bridgeBulbStopPending)
        rig.queue.drain()
        assertEquals(0, rig.starts)
        assertEquals(1, rig.stops)
        assertFalse(rig.vm.state.bridgeBulbStopRequired)
        assertTrue(rig.vm.state.pendingOperations.isEmpty())
    }

    @Test(timeout = 5000) fun ignoredStartCancellationCannotClearNewStopOrCaptureBusy() {
        val rig = Rig()
        val startGate = CompletableDeferred<Unit>()
        val stopGate = CompletableDeferred<Unit>()
        rig.startAction = { withContext(NonCancellable) { startGate.await() }; status(true) }
        rig.stopAction = { stopGate.await(); rig.duty = false; status(false) }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.newCaptureBusy()
        startGate.complete(Unit); rig.queue.drain()
        assertTrue(rig.vm.state.bridgeBulbStopPending)
        assertTrue(CameraOperation.SHUTTER_RELEASE in rig.vm.state.pendingOperations)
        assertTrue(CameraOperation.CAPTURE in rig.vm.state.pendingOperations)
        assertEquals(CameraAppError.BRIDGE_BULB_STOP_PENDING.messageToken, rig.vm.state.error)
        stopGate.complete(Unit); rig.queue.drain()
        assertFalse(rig.vm.state.bridgeBulbStopRequired)
        assertTrue(CameraOperation.CAPTURE in rig.vm.state.pendingOperations)
    }

    @Test(timeout = 5000) fun duplicateStopTapsCoalesceWhileStopIsPending() {
        val rig = Rig()
        val stopGate = CompletableDeferred<Unit>()
        rig.stopAction = { stopGate.await(); rig.duty = false; status(false) }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        assertEquals(1, rig.stops)
        stopGate.complete(Unit); rig.queue.drain()
        assertFalse(rig.vm.state.bridgeBulbStopRequired)
    }

    @Test(timeout = 5000) fun lateStopCannotClearReplacementSessionWarningsOrBusy() {
        val rig = Rig()
        val stopGate = CompletableDeferred<Unit>()
        rig.stopAction = { withContext(NonCancellable) { stopGate.await() }; rig.duty = false; status(false) }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.replaceConnection()
        stopGate.complete(Unit); rig.queue.drain()
        assertEquals("replacement warning", rig.vm.state.error)
        assertTrue(rig.vm.state.shutterReleaseUnconfirmed)
        assertEquals(setOf(CameraOperation.CAPTURE, CameraOperation.SHUTTER_RELEASE), rig.vm.state.pendingOperations)
    }

    @Test(timeout = 5000) fun stopTimeoutPublishesRetryableWarningInsteadOfStalePendingText() {
        val rig = Rig()
        rig.stopAction = { withTimeout(15) { awaitCancellation() } }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.queue.until { !rig.vm.state.bridgeBulbStopPending }
        assertTrue(rig.vm.state.bridgeBulbStopRequired)
        assertTrue(rig.vm.state.bridgeBulbUnconfirmed)
        assertEquals(CameraAppError.BRIDGE_BULB_RELEASE_REQUIRED.messageToken, rig.vm.state.error)
        assertEquals(CameraOperation.SHUTTER_RELEASE, rig.vm.state.errorOperation)
        assertTrue(rig.vm.state.pendingOperations.isEmpty())
    }

    @Test(timeout = 5000) fun terminalReceiptNeverClearsSeparatelyObservedShutterWarning() {
        val rig = Rig()
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        rig.vm.addUnrelatedWarning()
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        assertFalse(rig.vm.state.bridgeBulbStopRequired)
        assertTrue(rig.vm.state.shutterReleaseUnconfirmed)
        assertEquals(0, rig.vm.liveViewResumes)
    }

    @Test(timeout = 5000) fun oldBridge404ShowsOwnershipUnavailableAndRetainsStop() {
        val rig = Rig()
        rig.startAction = { throw DesktopBridgeException(404) }
        rig.vm.toggleBulbExposure(); rig.queue.drain()
        assertTrue(rig.vm.state.bridgeBulbStopRequired)
        assertTrue(rig.vm.state.bridgeBulbUnconfirmed)
        assertEquals(CameraAppError.BRIDGE_BULB_OWNERSHIP_UNAVAILABLE.messageToken, rig.vm.state.error)
        assertEquals(0, rig.stops)
    }
}

private fun status(active: Boolean) = CameraStatus(bulbExposureActive = active, shutterReleaseUnconfirmed = false)
private class Rig {
    val queue = QueueDispatcher()
    var duty = false
    var starts = 0
    var stops = 0
    var retirements = 0
    var freshRelease = true
    var startAction: suspend () -> CameraStatus = { status(true) }
    var stopAction: suspend () -> CameraStatus? = { duty = false; status(false) }
    val repository = HostRepository {
        duty = true
        BridgeBulbAccess(start = { starts++; startAction() }, stop = { stops++; stopAction() }, releaseRequired = { duty },
            hasFreshReleaseObservation = { freshRelease }, retire = { retirements++ })
    }
    val vm = ExtractedBulbViewModel(repository, queue)
}
private class QueueDispatcher : CoroutineDispatcher() {
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    fun drain() {
        var runs = 0
        while (true) { val task = queue.poll() ?: break; check(++runs < 1000); task.run() }
    }
    fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 1_000_000_000
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(5); drain() }
        check(condition()) { "Synthetic UI queue did not reach expected state" }
    }
}
private enum class CameraOperation { CONNECT, STATUS, SETTING, DIRECTORY, CLOCK, MAINTENANCE, POWER, CAPTURE, RECORDING, FOCUS, LIVE_VIEW, MEDIA, USB, BRIDGE, SHUTTER_RELEASE }
private enum class CameraTransport { DESKTOP_BRIDGE, OTHER }
private enum class ForegroundImportStopReason { USER, SESSION_CHANGED }
private enum class CameraImportHandoffOrigin { CAMERA }
private enum class CameraAppError {
    BRIDGE_BULB_RELEASE_REQUIRED, BRIDGE_BULB_START_PENDING, BRIDGE_BULB_STOP_PENDING, BRIDGE_BULB_OWNERSHIP_UNAVAILABLE, MAINTENANCE_RETIREMENT_UNCONFIRMED;
    val messageToken get() = name
}
private object SystemClock { fun elapsedRealtime() = 1234L }
private data class CameraInfo(val id: String = "synthetic-camera")
private class HostForegroundOwner { val operation: Job? = null }
private class HostHandoff { fun cancelUnlaunched(origin: CameraImportHandoffOrigin) {} }
private class HostCancelable { fun cancel() {} }
private class MaintenanceAttempt { fun retire() {} }
private enum class ForegroundImportPhase { OFF, BASELINING, WATCHING, WAITING, SAVING, STOPPING }
private data class ForegroundImportState(val phase: ForegroundImportPhase = ForegroundImportPhase.OFF)
private enum class AutofocusHoldState { IDLE, HOLDING }
private val FOREGROUND_IMPORT_ACTIVE_PHASES = ForegroundImportPhase.entries.toSet() - ForegroundImportPhase.OFF
// These unrelated interlock sets are adapter boundaries; corresponding state flags stay idle.
private val FOREGROUND_IMPORT_CONTROL_OPERATIONS = emptySet<CameraOperation>()
private val HELD_AF_INTERLOCK_OPERATIONS = emptySet<CameraOperation>()
private val LIVE_VIEW_INTERLOCK_OPERATIONS = emptySet<CameraOperation>()
private data class HostUiState(
    val connected: Boolean = true,
    val info: CameraInfo? = CameraInfo(),
    val previewMode: Boolean = false,
    val transport: CameraTransport = CameraTransport.DESKTOP_BRIDGE,
    val bulbMode: Boolean = true,
    val status: CameraStatus? = status(false),
    val bridgeBulbStopRequired: Boolean = false,
    val bridgeBulbStartPending: Boolean = false,
    val bridgeBulbStopPending: Boolean = false,
    val bridgeBulbUnconfirmed: Boolean = false,
    val shutterReleaseUnconfirmed: Boolean = false,
    val pendingOperations: Set<CameraOperation> = emptySet(),
    val captureFeedback: String? = null,
    val error: String? = null,
    val errorOperation: CameraOperation? = null,
    val hudVisible: Boolean = false,
    val bulbStartedAtMillis: Long? = null,
    val connectionRetirementUnconfirmed: Boolean = false,
    val connectionTeardownPending: Boolean = false,
    val recordingStopAvailable: Boolean = false,
    val maintenanceSuspended: Boolean = false,
    val needsLiveViewStopRecovery: Boolean = false,
    val pendingRecordingCommand: String? = null,
    val foregroundJpegImport: ForegroundImportState = ForegroundImportState(),
    val autofocusHoldState: AutofocusHoldState = AutofocusHoldState.IDLE,
    val baseUrl: String = "synthetic",
    val liveViewDisconnectWarning: Boolean = false,
    val shutterDisconnectWarning: Boolean = false,
    val recordingDisconnectWarning: Boolean = false,
) {
    val bulbExposureActive get() = status?.bulbExposureActive == true
    // HOST_PRODUCTION_IS_BUSY
    fun supports(feature: CameraFeature) = true
    fun needsShutterDisconnectWarning() = shutterReleaseUnconfirmed || bridgeBulbStopRequired
    fun withClearedSession(baseUrl: String, error: String?) = copy(baseUrl = baseUrl, error = error, connected = false,
        info = null, status = null, bridgeBulbStopRequired = false, bridgeBulbStartPending = false,
        bridgeBulbStopPending = false, bridgeBulbUnconfirmed = false)
}
private class HostRepository(private val prepare: () -> BridgeBulbAccess) {
    var reservations = 0
    var disconnects = 0
    fun prepareBridgeBulb(): BridgeBulbAccess? { reservations++; return prepare() }
    suspend fun startBulbExposure(): CameraStatus = error("Legacy Start must not execute in owned UI cases")
    suspend fun stopBulbExposure(): CameraStatus = error("Legacy Stop must not execute in owned UI cases")
    suspend fun disconnect(): Boolean { disconnects++; return false }
}
private class ExtractedBulbViewModel(val repository: HostRepository, queue: QueueDispatcher) {
    val viewModelScope = CoroutineScope(SupervisorJob() + queue)
    val _uiState = MutableStateFlow(HostUiState())
    val state get() = _uiState.value
    var cameraSessionGeneration = 1L
    var cameraStateRevision = 0L
    val cameraOperationJobs = mutableMapOf<CameraOperation, Job>()
    private val foregroundImportOwner: HostForegroundOwner? = null
    private val handoffOwner = HostHandoff()
    private var activeCaptureConfigurationAttempt: HostCancelable? = null
    private var activeShootingSettingAttempt: HostCancelable? = null
    private val maintenanceAttempts = mutableMapOf<Int, MaintenanceAttempt>()
    private var mediaDeleteControl: HostCancelable? = null
    private var mediaUploadAttempt: HostCancelable? = null
    private val liveViewReconciliationJobs = mutableSetOf<Job>()
    private var mediaUploadCleanupJob: Job? = null
    private var disconnectJob: Job? = null
    private var heldAutofocusJob: Job? = null
    private var mediaUploadJob: Job? = null
    private var liveViewGeneration = 0L
    private var lastPhotoShootingMode: String? = null
    var liveViewResumes = 0
    var resumeAction: suspend () -> Unit = {}
    fun newCaptureBusy() {
        cameraOperationJobs[CameraOperation.CAPTURE] = Job()
        _uiState.update { it.copy(pendingOperations = it.pendingOperations + CameraOperation.CAPTURE) }
    }
    fun replaceConnection() {
        cameraSessionGeneration++
        cameraOperationJobs[CameraOperation.CAPTURE] = Job()
        cameraOperationJobs[CameraOperation.SHUTTER_RELEASE] = Job()
        _uiState.value = HostUiState(info = CameraInfo(), error = "replacement warning", shutterReleaseUnconfirmed = true,
            pendingOperations = setOf(CameraOperation.CAPTURE, CameraOperation.SHUTTER_RELEASE))
    }
    fun addUnrelatedWarning() { _uiState.update { it.copy(shutterReleaseUnconfirmed = true) } }
    private suspend fun pauseLiveViewForBulb() {}
    private suspend fun resumeLiveViewAfterBulb() { liveViewResumes++; resumeAction() }
    private fun stopForegroundImport(reason: ForegroundImportStopReason): Job? = null
    private fun runCamera(operation: CameraOperation, block: suspend () -> Unit) { viewModelScope.launch { block() } }
    private fun latestCameraStatus(status: CameraStatus, revision: Long) = status
    private fun showCaptureSuccess() {}
    private fun markShutterReleaseUnconfirmed() { _uiState.update { it.copy(shutterReleaseUnconfirmed = true) } }
    private fun formatException(error: Exception) = "Synthetic ${error.javaClass.simpleName}"
    private fun retireRecordingAttempt(state: HostUiState) = state.recordingDisconnectWarning
    private fun stopHeldAutofocus() {}; private fun stopCameraFocusInfoLoop() {}; private fun stopLiveViewLoop() {}
    private fun stopEventPollingLoop() {}; private fun detachNativeLiveViewListener() {}; private fun resetMediaLibraryLoad() {}
    private fun cancelCaptureReview() {}; private fun closeMediaStream() {}; private fun cancelMediaDownload() {}
    private fun cancelMediaThumbnailLoads() {}; private fun resetFrameMetrics() {}; private fun publishConnectionRetirementWarning() {}

    // HOST_PRODUCTION_UI_OWNER
    // HOST_PRODUCTION_UI_METHODS
    // HOST_PRODUCTION_DISCONNECT
}
