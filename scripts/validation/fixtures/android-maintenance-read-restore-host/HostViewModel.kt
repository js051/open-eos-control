package dev.openeos.control.ui

import dev.openeos.control.data.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Only unrelated Android/model/media seams are synthetic. The marked methods are exact production. */
internal class QueueDispatcher : CoroutineDispatcher() {
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    fun drain() {
        var runs = 0
        while (true) { val task = queue.poll() ?: break; check(++runs < 10000); task.run() }
    }
    fun until(millis: Long = 1500, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000
        do { drain(); if (condition()) return true; Thread.sleep(5) } while (System.nanoTime() < deadline)
        drain(); return condition()
    }
}
internal enum class CameraOperation { CONNECT, STATUS, SETTING, DIRECTORY, CLOCK, MAINTENANCE, POWER, CAPTURE, RECORDING, FOCUS, LIVE_VIEW, MEDIA, USB, BRIDGE, SHUTTER_RELEASE }
internal enum class ForegroundImportStopReason { USER, SESSION_CHANGED }
internal enum class CameraImportHandoffOrigin { CAMERA }
internal enum class AutofocusHoldState { IDLE, HOLDING, RELEASE_FAILED }
internal enum class CameraAppError { MAINTENANCE_RETIREMENT_UNCONFIRMED; val messageToken get() = name }
internal class CameraInfo(val id: String = "synthetic-camera")
internal class HostHandoff { fun cancelUnlaunched(origin: CameraImportHandoffOrigin) {} }
internal class HostCancelable { fun cancel() {} }
internal class HostFrameRead(val job: Job)
internal class ForegroundJpegImportStatus
internal class ConnectionAttemptTarget
internal enum class HostRecoveryReason { FAILED }
internal class HostRecovery(val reason: HostRecoveryReason = HostRecoveryReason.FAILED)
internal class HostMetadata(val running: Boolean = false)
internal class MediaWorklistAdmissionException : Exception()
internal class AdmittedMediaDownload { val output = Unit; suspend fun run(block: suspend () -> Unit) = block() }
internal fun CoroutineScope.launchAdmittedMediaOutput(output: Unit, register: (Job) -> Unit, block: suspend () -> Unit): Job =
    launch(start = CoroutineStart.LAZY) { block() }.also { register(it); it.start() }
internal val CAPABILITY_EVIDENCE_OPERATIONS = emptySet<CameraOperation>()
internal val LIVE_VIEW_INTERLOCK_OPERATIONS = emptySet<CameraOperation>()
internal data class HostUiState(
    val connected: Boolean = true, val info: CameraInfo? = CameraInfo(), val previewMode: Boolean = false,
    val mediaSessionGeneration: Long = 1L, val recordingStopAvailable: Boolean = false,
    val bulbExposureActive: Boolean = false, val shutterReleaseUnconfirmed: Boolean = false,
    val needsLiveViewStopRecovery: Boolean = false, val autofocusHoldState: AutofocusHoldState = AutofocusHoldState.IDLE,
    val maintenanceReceipt: MaintenanceReceipt? = null, val maintenanceHistory: List<MaintenanceReceipt> = emptyList(),
    val maintenanceSuspended: Boolean = false, val lastClockSyncAtMillis: Long? = null,
    val liveViewBitmap: Any? = null, val liveViewFrameUrl: String? = null, val nativeLiveViewSession: Any? = null,
    val liveViewAutoRefresh: Boolean = false, val liveViewFrameRateFps: Int = 15,
    val liveViewStopUnconfirmed: Boolean = false, val pendingOperations: Set<CameraOperation> = emptySet(),
    val connectionTeardownPending: Boolean = false, val liveViewDisconnectWarning: Boolean = false,
    val shutterDisconnectWarning: Boolean = false, val recordingDisconnectWarning: Boolean = false,
    val error: String? = null, val errorOperation: CameraOperation? = null, val baseUrl: String = "synthetic",
    val foregroundJpegImport: ForegroundJpegImportStatus = ForegroundJpegImportStatus(),
    val mediaMetadataBatch: HostMetadata? = null, val connectionRecovery: HostRecovery? = null,
) {
    val busy get() = pendingOperations.isNotEmpty()
    fun supports(feature: CameraFeature) = true
    fun isBusy(operation: CameraOperation) = busy
    fun needsShutterDisconnectWarning() = shutterReleaseUnconfirmed
    fun withClearedSession(baseUrl: String, error: String?) = copy(baseUrl = baseUrl, error = error,
        connected = false, info = null, maintenanceReceipt = null)
}
internal class HostRepository(val client: ExtractedMaintenanceClient) {
    var disconnects = 0
    var closed = false
    var backend = client
    var liveViewRunning = client.initialLiveView
    var liveViewRequest: LiveViewRequest? = LiveViewRequest()
    var liveViewAttemptGeneration = 0L
    // HOST_PRODUCTION_REPOSITORY_METHODS
    fun isLiveViewStopRequired() = client.liveViewStopRequired
    fun isLiveViewRunning() = liveViewRunning
    fun nativeLiveViewSession(): Any? = null
    fun setNativeLiveViewRenderingEnabled(value: Boolean) {}
    suspend fun disconnect(): Boolean {
        disconnects++
        return try { client.closeMaintenanceSession() } finally { closed = true }
    }
}
internal class ExtractedMaintenanceViewModel(val repository: HostRepository, val queue: QueueDispatcher) {
    val viewModelScope = CoroutineScope(SupervisorJob() + queue)
    val _uiState = MutableStateFlow(HostUiState())
    val state get() = _uiState.value
    var cameraSessionGeneration = 1L
    var cameraStateRevision = 0L
    val cameraOperationJobs = mutableMapOf<CameraOperation, Job>()
    private val handoffOwner = HostHandoff()
    private var activeCaptureConfigurationAttempt: HostCancelable? = null
    private var activeShootingSettingAttempt: HostCancelable? = null
    private var maintenanceAttempt: MaintenanceAttempt? = null
    private var activeMaintenanceAttempt: MaintenanceAttempt? = null
    private val maintenanceAttempts = linkedMapOf<String, MaintenanceAttempt>()
    private val maintenanceEventRestore = linkedMapOf<String, Boolean>()
    private val maintenanceDrafts = MaintenanceDraftLedger()
    private var maintenanceJob: Job? = null
    private var mediaDeleteControl: HostCancelable? = null
    private var mediaUploadAttempt: HostCancelable? = null
    private val liveViewReconciliationJobs = mutableSetOf<Job>()
    private val liveViewFrameReads = mutableSetOf<HostFrameRead>()
    private var mediaUploadCleanupJob: Job? = null
    private var disconnectJob: Job? = null
    private var heldAutofocusJob: Job? = null
    private var mediaUploadJob: Job? = null
    private var liveViewJob: Job? = null
    private var focusInfoJob: Job? = null
    private var eventPollingJob: Job? = null
    private var mediaLibraryJob: Job? = null
    private var eventMediaJob: Job? = null
    private var liveViewGeneration = 0L
    private var lastPhotoShootingMode: String? = null
    private var appInForeground = true
    val activeJob get() = maintenanceJob
    fun draft(action: MaintenanceAction = MaintenanceAction.SENSOR_CLEANING, autoPowerOff: Boolean = true) =
        MaintenanceDraft(MaintenanceOwner(state.info, state.mediaSessionGeneration), java.util.UUID.randomUUID().toString(),
            MaintenanceRequest(action, clock = if (action == MaintenanceAction.CLOCK_SYNC) MaintenanceClockTarget(1_700_000_000_000, 0, false) else null,
                autoPowerOff = action == MaintenanceAction.SENSOR_CLEANING && autoPowerOff))
    fun attempt(id: String) = requireNotNull(maintenanceAttempts[id])
    fun replaceConnection(): HostUiState {
        cameraSessionGeneration++
        _uiState.value = HostUiState(info = CameraInfo(), mediaSessionGeneration = state.mediaSessionGeneration + 1,
            maintenanceHistory = state.maintenanceHistory, error = "replacement warning",
            pendingOperations = setOf(CameraOperation.CAPTURE), shutterReleaseUnconfirmed = true,
            lastClockSyncAtMillis = 777L)
        return state
    }
    private fun stopForegroundImport(reason: ForegroundImportStopReason): Job? = null
    private fun retireRecordingAttempt(state: HostUiState) = state.recordingDisconnectWarning
    private fun retireOwnedBridgeBulbJobs(): List<Job> = emptyList()
    private fun stopHeldAutofocus() {}; private fun stopCameraFocusInfoLoop() {}; private fun stopLiveViewLoop() {}
    private fun stopEventPollingLoop() {}; private fun detachNativeLiveViewListener() {}; private fun resetMediaLibraryLoad() {}
    private fun cancelCaptureReview() {}; private fun closeMediaStream() {}; private fun cancelMediaDownload() {}
    private fun cancelMediaThumbnailLoads() {}; private fun resetFrameMetrics() {}; private fun publishConnectionRetirementWarning() {}
    private fun cancelMediaLibraryLoad() {}; private fun cancelDownloadContinuation() {}; private fun refreshCapabilityEvidence() {}
    private fun connectionRecoveryFor(target: ConnectionAttemptTarget, error: Exception): HostRecovery? = null
    private fun markShutterReleaseUnconfirmed() {}; private fun queueLiveViewReconciliation() {}
    private fun formatException(error: Exception) = "Synthetic ${error.javaClass.simpleName}"
    var restoredUiCalls = 0
    private fun configureNativeLiveViewSession(native: Any?, fps: Int) { restoredUiCalls++ }
    private fun startEventPollingIfSupported() { restoredUiCalls++ }
    private fun refreshLiveViewFrameInternal(reportErrors: Boolean) { restoredUiCalls++ }
    private fun startLiveViewLoopIfNeeded() { restoredUiCalls++ }
    // HOST_PRODUCTION_UI_METHODS
}
