package dev.openeos.control.ui

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.MaintenanceAction
import dev.openeos.control.data.MaintenanceStopEvidence
import dev.openeos.control.data.MaintenanceRestoreEvidence
import dev.openeos.control.data.maintenanceMayRetryStop
import dev.openeos.control.data.maintenanceRestoreStopSafe
import dev.openeos.control.data.MaintenanceRequest
import dev.openeos.control.data.MaintenanceClockTarget
import dev.openeos.control.data.MaintenanceClockEvidence
import dev.openeos.control.data.MaintenanceCommandEvidence
import dev.openeos.control.data.MaintenanceRetirementUnconfirmedException
import dev.openeos.control.R
import dev.openeos.control.data.CaptureConfigurationOperation
import dev.openeos.control.data.CaptureConfigurationReadback
import dev.openeos.control.data.CaptureConfigurationReadEvidence
import dev.openeos.control.data.CaptureConfigurationRequest
import dev.openeos.control.data.CaptureConfigurationWriteEvidence
import dev.openeos.control.data.ShootingSettingRequest
import dev.openeos.control.data.ShootingSettingObservation
import dev.openeos.control.data.acceptsShootingSettingValue
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.AutofocusReleaseException
import dev.openeos.control.data.ShutterReleaseException
import dev.openeos.control.data.BridgeBulbAccess
import dev.openeos.control.data.DesktopBridgeException
import dev.openeos.control.data.CaptureStatusReadbackException
import dev.openeos.control.data.RecordingStatusReadbackException
import dev.openeos.control.data.RecordingCommandUnconfirmedException
import dev.openeos.control.data.RecordingRequestEvidence
import dev.openeos.control.data.RecordingCommand
import dev.openeos.control.data.RecordingAccess
import dev.openeos.control.data.RecordingReceipt
import dev.openeos.control.data.RecordingObservation
import dev.openeos.control.data.RecordingResponseEvidence
import dev.openeos.control.data.RecordingObservationSource
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraFileNamingField
import dev.openeos.control.data.canStartMediaUpload
import dev.openeos.control.data.MediaDeleteOutcome
import dev.openeos.control.data.MediaUploadAttempt
import dev.openeos.control.data.MediaUploadEvidence
import dev.openeos.control.data.MediaUploadStatus
import dev.openeos.control.data.ownsMediaUpload
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.MediaMetadataField
import dev.openeos.control.data.MediaMetadataTarget
import dev.openeos.control.data.metadataReadback
import dev.openeos.control.data.currentMediaMetadataItem
import dev.openeos.control.data.withVerifiedMetadata
import dev.openeos.control.data.metadataWritable
import dev.openeos.control.data.isVisualMedia
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.liveViewRecordingInterlocked
import dev.openeos.control.data.LiveViewLifecycleEvidence
import dev.openeos.control.data.CameraLiveViewOwner
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CameraSession
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryState
import dev.openeos.control.data.DownloadHistoryStore
import dev.openeos.control.data.DownloadHistoryWarning
import dev.openeos.control.data.DownloadWorklistOrigin
import dev.openeos.control.data.DownloadContinuationStatus
import dev.openeos.control.data.continuationStatus
import dev.openeos.control.data.FocusDriveDirection
import dev.openeos.control.data.FocusDriveStep
import dev.openeos.control.data.LiveViewRequest
import dev.openeos.control.data.LiveViewMagnification
import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.LiveViewSource
import dev.openeos.control.data.MAX_PTP_OBJECT_BYTES
import dev.openeos.control.data.NativeLiveViewEvent
import dev.openeos.control.data.NativeLiveViewAudioStatus
import dev.openeos.control.data.NativeLiveViewSession
import dev.openeos.control.data.UsbPtpDiagnosticScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLConnection
import java.util.UUID
import kotlin.coroutines.coroutineContext

private data class MediaUploadMetadata(
    val name: String,
    val sizeBytes: Long?,
)

private data class MediaLibraryBatch(
    val items: List<CameraMediaItem>,
    val hasMore: Boolean,
)

internal const val RECENT_MEDIA_ITEMS = 60
private const val RECENT_MEDIA_REQUEST_ITEMS = RECENT_MEDIA_ITEMS + 1
internal const val CAPTURE_REVIEW_REQUEST_ITEMS = 8
private const val MAX_CONCURRENT_MEDIA_THUMBNAILS = 2
private const val MEDIA_THUMBNAIL_MAX_EDGE = 512
private val MEDIA_THUMBNAIL_RETRY_DELAYS_MILLIS = longArrayOf(250L, 750L)
private val CAPTURE_REVIEW_RETRY_DELAYS_MILLIS = longArrayOf(250L, 750L, 1_500L)
internal val MEDIA_READ_RETRY_DELAYS_MILLIS = longArrayOf(300L, 900L)

private fun maximumItemsFor(scope: MediaLibraryScope): Int? =
    RECENT_MEDIA_REQUEST_ITEMS.takeIf { scope == MediaLibraryScope.RECENT }

private fun List<CameraMediaItem>.toMediaLibraryBatch(scope: MediaLibraryScope): MediaLibraryBatch =
    if (scope == MediaLibraryScope.RECENT) {
        MediaLibraryBatch(take(RECENT_MEDIA_ITEMS), size > RECENT_MEDIA_ITEMS)
    } else {
        MediaLibraryBatch(this, false)
    }

internal suspend fun executeMediaBatch(
    items: List<CameraMediaItem>,
    operation: MediaBatchOperation,
    onProgress: (MediaBatchProgress) -> Unit = {},
    action: suspend (CameraMediaItem) -> Unit,
): MediaBatchResult {
    val uniqueItems = items.distinctBy(CameraMediaItem::id)
    val failures = mutableListOf<String>()
    var succeeded = 0
    for ((index, item) in uniqueItems.withIndex()) {
        coroutineContext.ensureActive()
        onProgress(
            MediaBatchProgress(
                operation = operation,
                completedItems = index,
                totalItems = uniqueItems.size,
                currentItemName = item.name,
            ),
        )
        try {
            action(item)
            succeeded += 1
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Storage admission failed before any destination/original I/O.
            // Stop the remaining roster rather than pretending each row failed.
            if (operation == MediaBatchOperation.DOWNLOAD && exception is MediaWorklistAdmissionException) throw exception
            exception.printStackTrace()
            failures += item.name
            // A failed or uncertain download retires this batch. Keep earlier
            // confirmations and leave later durable rows unstarted for explicit
            // continuation; metadata/mutation batches retain their own policy.
            if (operation == MediaBatchOperation.DOWNLOAD) break
        }
    }
    return MediaBatchResult(
        operation = operation,
        totalItems = uniqueItems.size,
        succeededItems = succeeded,
        failedItemNames = failures,
    )
}

internal suspend fun <Result> retryMediaRead(
    retryDelaysMillis: LongArray = MEDIA_READ_RETRY_DELAYS_MILLIS,
    action: suspend () -> Result,
): Result {
    for (attempt in 0..retryDelaysMillis.size) {
        try {
            return action()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            // A new attempt may create another document. Never accumulate partials when
            // the previous attempt could not release its output responsibility.
            if (exception.hasUnconfirmedMediaCleanup() || attempt >= retryDelaysMillis.size) throw exception
            delay(retryDelaysMillis[attempt])
        }
    }
    error("Media read retry exhausted without a result.")
}

private fun deleteIncompleteMediaDocument(resolver: ContentResolver, destination: Uri) {
    // SAF destinations are newly created by CreateDocument or createMediaDocument.
    // DocumentsProvider.delete is final and unsupported; document operations use call().
    check(DocumentsContract.deleteDocument(resolver, destination)) {
        "Android could not remove the incomplete download."
    }
}

private fun createMediaDocument(
    resolver: ContentResolver,
    destinationTree: Uri,
    item: CameraMediaItem,
): Uri {
    val parent = DocumentsContract.buildDocumentUriUsingTree(
        destinationTree,
        DocumentsContract.getTreeDocumentId(destinationTree),
    )
    val filename = item.name.substringAfterLast('/').ifBlank { "camera-media" }
    val contentType = item.contentType
        ?.substringBefore(';')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: URLConnection.guessContentTypeFromName(filename)
        ?: "application/octet-stream"
    return DocumentsContract.createDocument(resolver, parent, contentType, filename)
        ?: throw CameraAppException(CameraAppError.DOWNLOAD_DOCUMENT_CREATE_FAILED)
}

internal fun mergeRecentMedia(
    recent: List<CameraMediaItem>,
    existing: List<CameraMediaItem>,
): List<CameraMediaItem> {
    val recentIds = recent.mapTo(hashSetOf(), CameraMediaItem::id)
    return recent + existing.filterNot { it.id in recentIds }
}

internal fun isRetryableMediaThumbnailFailure(exception: Exception): Boolean = exception is IOException

internal fun mediaThumbnailSampleSize(width: Int, height: Int, maximumEdge: Int = MEDIA_THUMBNAIL_MAX_EDGE): Int {
    if (width <= 0 || height <= 0 || maximumEdge <= 0) return 1
    var sample = 1
    while (width / sample > maximumEdge || height / sample > maximumEdge) {
        if (sample > Int.MAX_VALUE / 2) return sample
        sample *= 2
    }
    return sample
}

class CameraViewModel(
    private val repository: CameraRepository = CameraRepository(),
    private val downloadHistoryFactory: (Context) -> DownloadHistoryStore = DownloadHistoryProvider::get,
    private val deliveredJpegStore: DeliveredJpegStore = DeliveredJpegStore(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
    // Local display preferences survive camera-session reset and settings dismissal.
    private val cubeLutImporter = CubeLutImporter(viewModelScope) { imported ->
        _uiState.update { current -> current.copy(
            monitorSettings = current.monitorSettings.copy(cubeLut = imported.lut),
            cubeLutImport = imported.status,
        ) }
    }
    private val _downloadHistoryState = MutableStateFlow(DownloadHistoryState())
    val downloadHistoryState: StateFlow<DownloadHistoryState> = _downloadHistoryState.asStateFlow()
    private val _downloadContinuation = MutableStateFlow<DownloadContinuationDraft?>(null)
    internal val downloadContinuation: StateFlow<DownloadContinuationDraft?> = _downloadContinuation.asStateFlow()
    private data class ContinuationPicker(val id: String, val draft: DownloadContinuationDraft?, val request: MediaSaveRequest)
    private var continuationPicker: ContinuationPicker? = null
    private var continuationSaveRequest: MediaSaveRequest? = null
    @Volatile private var retiredContinuationRequest: MediaSaveRequest? = null
    private val handoffOwner by lazy { CameraImportHandoffOwner<CameraImportHandoffSession>(viewModelScope) }
    private val savedJpegController by lazy { SavedJpegHandoffController(deliveredJpegStore, handoffOwner, viewModelScope) }
    internal val savedJpegState: StateFlow<SavedJpegUiState> get() = savedJpegController.state
    internal val cameraImportHandoffState: StateFlow<CameraImportHandoffState<CameraImportHandoffSession>> get() = handoffOwner.state
    // Internal test-APK seam only; normal app flows always use the contract receiver package.
    internal var cameraImportTargetPackage: String = dev.openeos.control.importing.CameraImportAndroidIntentV1.OPEN_NEGATIVE_PACKAGE
    private var downloadHistoryStore: DownloadHistoryStore? = null
    private var downloadHistoryInitialized = false
    private var liveViewJob: Job? = null
    private class LiveViewFrameRead(val job: Job, var stopped: Boolean = false)
    private val liveViewFrameReads = mutableSetOf<LiveViewFrameRead>()
    @Volatile private var cameraSessionGeneration = 0L
    private val mediaPickerRequests = CameraMediaPickerRequests()
    private var cameraStateRevision = 0L
    private val cameraOperationJobs = mutableMapOf<CameraOperation, Job>()
    private class HttpMediaPreviewRead {
        var job: Job? = null
        var cancelled = false
        fun cancel() {
            cancelled = true
            job?.cancel()
        }
    }
    private var httpMediaPreviewRead: HttpMediaPreviewRead? = null
    private class BridgeBulbUiOwner(val access: BridgeBulbAccess, val generation: Long, val connection: CameraInfo?) {
        var command = 0L
        var startJob: Job? = null
        var stopJob: Job? = null
    }
    private var bridgeBulbUiOwner: BridgeBulbUiOwner? = null
    private var disconnectJob: Job? = null
    private var observedConnectionRetirementRevision = 0L
    private val liveViewReconciliationJobs = mutableSetOf<Job>()
    private var eventMediaJob: Job? = null
    private var mediaUploadCleanupJob: Job? = null
    private val liveViewTransitionMutex = Mutex()
    private var appInForeground = true
    private var liveViewGeneration = 0L
    private var focusFeedbackJob: Job? = null
    private var heldAutofocusJob: Job? = null
    private var heldAutofocusRelease: CompletableDeferred<Unit>? = null
    private var focusInfoJob: Job? = null
    private val focusInfoReadGate = CameraFocusReadGate()
    private var focusInfoGeneration = 0L
    private var eventPollingJob: Job? = null
    private var eventPollingGeneration = 0L
    private var mediaDownloadJob: Job? = null
    private class ForegroundImportOwner(
        val generation: Long,
        val connection: CameraInfo,
        val output: ForegroundJpegImportOutput,
        val location: String,
    ) {
        lateinit var runner: ForegroundJpegImportRunner
        var job: Job? = null
        var operation: Job? = null
        var saveRequest: MediaSaveRequest? = null
        var started = false
    }
    private var foregroundImportOwner: ForegroundImportOwner? = null
    private var foregroundImportPreferences: SharedPreferences? = null
    private val foregroundImportWarningListener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
        if (key == KEY_FOREGROUND_IMPORT_CLEANUP_WARNING) {
            _uiState.update { it.copy(foregroundImportCleanupUnconfirmed = preferences.getBoolean(key, false)) }
        }
    }
    @Volatile private var mediaSaveRequest: MediaSaveRequest? = null
    private var mediaUploadJob: Job? = null
    private var maintenanceAttempt: MaintenanceAttempt? = null
    private var activeMaintenanceAttempt: MaintenanceAttempt? = null
    private val maintenanceAttempts = linkedMapOf<String, MaintenanceAttempt>()
    private val maintenanceEventRestore = linkedMapOf<String, Boolean>()
    private val maintenanceDrafts = MaintenanceDraftLedger()
    private var maintenanceJob: Job? = null
    private var maintenanceStopRecoveryJob: Job? = null
    private var shootingSettingAttempt: ShootingSettingAttempt? = null
    private var activeShootingSettingAttempt: ShootingSettingAttempt? = null
    private val shootingSettingAttempts = linkedMapOf<String, ShootingSettingAttempt>()
    private val shootingSettingDrafts = ShootingSettingDraftLedger()
    private var shootingSettingJob: Job? = null
    private var captureConfigurationAttempt: CaptureConfigurationAttempt? = null
    private var activeCaptureConfigurationAttempt: CaptureConfigurationAttempt? = null
    private val captureConfigurationAttempts = linkedMapOf<String, CaptureConfigurationAttempt>()
    private var captureConfigurationJob: Job? = null
    private val consumedCaptureConfigurationDrafts = CaptureConfigurationDraftLedger()
    @Volatile private var mediaDeleteControl: MediaDeleteBatchControl? = null
    @Volatile private var mediaDeleteJob: Job? = null
    @Volatile private var mediaUploadAttempt: MediaUploadAttempt? = null
    private var mediaLibraryJob: Job? = null
    private var mediaLibraryGeneration = 0L
    private var mediaEventDemandGeneration = 0L
    private var captureReviewJob: Job? = null
    private var captureReviewGeneration = 0L
    private data class CaptureReviewAttempt(
        val previousIds: Set<String>,
        val sessionGeneration: Long,
        val intent: MediaReviewIntent,
    )
    private var pendingCaptureReview: CaptureReviewAttempt? = null
    private class RecordingAttempt(
        val command: RecordingCommand, val generation: Long, val connection: CameraInfo?,
        val previousIds: Set<String>, val access: RecordingAccess?, val priorStopRequired: Boolean,
    ) {
        val id = UUID.randomUUID().toString()
        val requestEvidence = RecordingRequestEvidence(command)
        var inFlight = true
        var retired = false
        var cancelled = false
        var requestCancelled = false
        var reviewStarted = false
        var reviewRetired = false
        var retainedStopRequired: Boolean? = null
        fun receipt(sameOwner: Boolean = true) = RecordingReceipt(command, requestEvidence.responseEvidence(),
            requestEvidence.observation, retired, cancelled, sameOwner, priorStopRequired,
            id = id, requestCancelled = requestCancelled, retainedStopRequired = retainedStopRequired)
    }
    // Retained identity blocks old reads; its captured handle cannot retarget another backend.
    private var recordingAttempt: RecordingAttempt? = null
    private fun ownsRecordingAttempt(attempt: RecordingAttempt): Boolean =
        recordingAttempt === attempt && attempt.generation == cameraSessionGeneration &&
            _uiState.value.info === attempt.connection && !attempt.retired

    private fun storeRecordingReceipt(attempt: RecordingAttempt, current: Boolean) {
        val receipt = attempt.receipt(sameOwner = current)
        _uiState.update { state -> state.copy(
            recordingReceipt = if (current) receipt else state.recordingReceipt,
            recordingHistory = (listOf(receipt) + state.recordingHistory.filterNot { it.id == receipt.id }).take(20)) }
    }

    private fun markRecordingPending(attempt: RecordingAttempt, unconfirmed: Boolean = false) {
        // The flag is presentation only; the actual receipt is authoritative and never upgraded by reads.
        if (!ownsRecordingAttempt(attempt)) { storeRecordingReceipt(attempt, current = false); return }
        adoptRecordingStatus(attempt, _uiState.value.status)
    }

    private fun retireRecordingAttempt(state: CameraUiState): Boolean {
        val attempt = recordingAttempt?.takeIf { it.connection === state.info }
        val admitted = attempt?.requestEvidence?.retire() == true
        attempt?.let { it.retired = true; storeRecordingReceipt(it, current = false) }
        return state.recordingDisconnectWarning || state.recordingStopAvailable ||
            (attempt?.inFlight == true && admitted)
    }

    private fun adoptRecordingStatus(attempt: RecordingAttempt, status: CameraStatus?) {
        if (!ownsRecordingAttempt(attempt)) return
        if (!attempt.cancelled) attempt.requestEvidence.observe(status?.recordingObservation)
        val receipt = attempt.receipt()
        val observed = receipt.observation?.takeIf { receipt.canPublish }
        val pending = receipt.stopRequired && observed?.explicit != true
        val display = status?.let { value -> if (observed != null) value.copy(recording = observed.recording,
            recordingObservation = observed) else value }
        _uiState.update { it.copy(status = display ?: it.status,
            recordingReceipt = receipt,
            recordingHistory = (listOf(receipt) + it.recordingHistory.filterNot { old -> old.id == receipt.id }).take(20),
            recordingCommandUnconfirmed = receipt.response == RecordingResponseEvidence.UNKNOWN,
            pendingRecordingCommand = attempt.command.takeIf { pending },
            recordingStartNotConfirmed = attempt.command == RecordingCommand.START && observed?.recording == false,
            captureMode = if (receipt.stopRequired || attempt.command == RecordingCommand.STOP) CaptureMode.VIDEO else it.captureMode) }
        if (receipt.mayReviewStoppedVideo && !attempt.reviewStarted && !attempt.reviewRetired && !_uiState.value.previewMode) {
            attempt.reviewStarted = true
            pendingCaptureReview = CaptureReviewAttempt(attempt.previousIds, attempt.generation, intent = MediaReviewIntent.VIDEO)
            refreshCaptureReview()
        }
    }
    private data class CaptureReviewCandidates(
        val ids: Set<String>,
        val sessionGeneration: Long,
        val connection: CameraInfo?,
    )
    private var captureReviewCandidates: CaptureReviewCandidates? = null
    private val mediaThumbnailJobs = mutableMapOf<String, Job>()
    private val mediaThumbnailSemaphore = Semaphore(MAX_CONCURRENT_MEDIA_THUMBNAILS)
    private var mediaThumbnailGeneration = 0
    private val frameTimesMillis = ArrayDeque<Long>()
    private var preferencesLoaded = false
    private var networkRoutingConfigured = false
    private var lastPhotoShootingMode: String? = null

    internal fun initializeDownloadHistory(context: Context) {
        if (downloadHistoryInitialized) return
        downloadHistoryInitialized = true
        try {
            val store = downloadHistoryFactory(context.applicationContext)
            downloadHistoryStore = store
            _downloadHistoryState.value = store.state.value
            viewModelScope.launch { store.state.collect { _downloadHistoryState.value = it } }
        } catch (_: Exception) {
            // History initialization is optional. It cannot prevent camera setup or downloads.
            _downloadHistoryState.value = DownloadHistoryState(loading = false, warning = DownloadHistoryWarning.STOPPED)
        }
    }

    fun clearDownloadHistory() {
        cancelDownloadContinuation()
        continuationSaveRequest?.let { request ->
            // Stop capabilities/progress now, but retain this exact UI owner until cleanup
            // settles so a genuine incomplete-file warning cannot disappear with history.
            retiredContinuationRequest = request
            mediaDownloadJob?.cancel()
        }
        val store = downloadHistoryStore ?: return
        viewModelScope.launch { store.clear() }
    }

    internal fun beginDownloadContinuation(worklistId: String): Boolean {
        val state = _uiState.value
        val store = downloadHistoryStore ?: return false
        if (!canStartMediaPickerTransfer(state, CameraMediaPickerKind.DOWNLOAD_FOLDER) ||
            foregroundImportOwner != null || continuationPicker != null || !store.state.value.writable ||
            worklistId in store.state.value.continuationBlockedWorklistIds) return false
        val worklist = store.state.value.worklists.singleOrNull { it.worklistId == worklistId } ?: return false
        val rows = worklist.rows.filter {
            store.state.value.continuationStatus(DownloadWorklistOrigin(worklistId, it.rowId)) != DownloadContinuationStatus.UNAVAILABLE
        }
        val sources = state.mediaItems.toList()
        if (rows.isEmpty() || sources.isEmpty()) return false
        _downloadContinuation.value = DownloadContinuationDraft(
            id = UUID.randomUUID().toString(), worklistId = worklistId, rows = rows, sources = sources,
            generation = cameraSessionGeneration, connection = requireNotNull(state.info),
            historyRequest = store.captureRequest(),
            uncertainRowIds = rows.filter { store.state.value.continuationStatus(DownloadWorklistOrigin(worklistId, it.rowId)) ==
                DownloadContinuationStatus.UNCERTAIN }.map { it.rowId }.toSet(),
        )
        return true
    }

    private fun ownsDownloadContinuation(draft: DownloadContinuationDraft): Boolean =
        draft.generation == cameraSessionGeneration && draft.connection === _uiState.value.info &&
            _uiState.value.connected && !_uiState.value.previewMode

    internal fun mapDownloadContinuation(draftId: String, rowId: String, sourceId: String?): Boolean {
        val draft = _downloadContinuation.value?.takeIf { it.id == draftId && ownsDownloadContinuation(it) } ?: return false
        val mapped = draft.map(rowId, sourceId) ?: return false
        _downloadContinuation.value = mapped
        return true
    }

    internal fun confirmDownloadContinuationUnknown(draftId: String, checked: Boolean) {
        val draft = _downloadContinuation.value?.takeIf { it.id == draftId && ownsDownloadContinuation(it) } ?: return
        _downloadContinuation.value = draft.copy(unknownConfirmed = checked)
    }

    internal fun cancelDownloadContinuation() {
        _downloadContinuation.value = null
        // Keep a tombstone until the picker returns: its URI must never become an ordinary save.
        continuationPicker?.let { picker ->
            mediaSaveRequest?.takeIf { it.pickerId == picker.id }?.let { finishPendingMediaSave(it, MediaSaveFeedback.Cancelled) }
        }
        continuationPicker = continuationPicker?.copy(draft = null)
    }

    private fun confirmedDownloadContinuation(draftId: String): DownloadContinuationDraft? {
        val draft = _downloadContinuation.value?.takeIf { it.id == draftId && ownsDownloadContinuation(it) && it.canConfirm } ?: return null
        val state = _uiState.value
        if (!canStartMediaPickerTransfer(state, CameraMediaPickerKind.DOWNLOAD_FOLDER) || foregroundImportOwner != null ||
            draft.selectedItems.any { selected -> state.mediaItems.none { it == selected } }) return null
        return draft
    }

    internal fun continueDownloadToGallery(context: Context, draftId: String): Boolean {
        val draft = confirmedDownloadContinuation(draftId) ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !draft.selectedItems.all(::canSaveMediaToGallery)) return false
        _downloadContinuation.value = null
        downloadMediaBatchInternal(context, draft.selectedItems, continuation = draft)
        return true
    }

    internal fun beginContinuationFolderPicker(draftId: String): String? {
        val draft = confirmedDownloadContinuation(draftId) ?: return null
        val id = beginMediaPicker(CameraMediaPickerKind.DOWNLOAD_FOLDER, draft.selectedItems) ?: return null
        continuationPicker = ContinuationPicker(id, draft, requireNotNull(mediaSaveRequest))
        _downloadContinuation.value = null
        return id
    }

    fun initialize(context: Context) {
        initializeDownloadHistory(context)
        if (!networkRoutingConfigured) {
            repository.configureAndroidNetworkRouting(context.applicationContext)
            networkRoutingConfigured = true
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { CameraImportHandoffStorage(context.applicationContext).cleanupExpiredSessions(
                excludedSessionIds = setOfNotNull(handoffOwner.state.value.active?.reservation?.sessionId),
            ) }
        }
        if (preferencesLoaded) return
        preferencesLoaded = true
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        foregroundImportPreferences = preferences
        preferences.registerOnSharedPreferenceChangeListener(foregroundImportWarningListener)
        _uiState.update {
            it.copy(
                foregroundImportCleanupUnconfirmed = preferences.getBoolean(KEY_FOREGROUND_IMPORT_CLEANUP_WARNING, false),
                connectionTarget = preferences.getString(KEY_CONNECTION_TARGET, null)
                    ?.let { value -> runCatching { ConnectionTarget.valueOf(value) }.getOrNull() }
                    ?: it.connectionTarget,
                baseUrl = preferences.getString(KEY_BASE_URL, it.baseUrl) ?: it.baseUrl,
                username = preferences.getString(KEY_USERNAME, it.username) ?: it.username,
                bridgeBaseUrl = preferences.getString(KEY_BRIDGE_BASE_URL, it.bridgeBaseUrl) ?: it.bridgeBaseUrl,
            )
        }
        if (_uiState.value.usbDiagnostics.scannedAtMillis == 0L) {
            refreshUsbDiagnostics(context.applicationContext)
        }
    }

    fun setUiMode(mode: UiMode) {
        val previewToCancel = httpMediaPreviewRead.takeIf { mode != UiMode.MEDIA }
        if (mode != UiMode.CONTROL) stopHeldAutofocus()
        if (mode == UiMode.MEDIA) invalidateCameraFocusInfo()
        if (mode != UiMode.MEDIA) _uiState.value.mediaStreamSource?.close()
        _uiState.update {
            it.copy(
                uiMode = mode,
                activeSettingPicker = null,
                mediaPreviewItem = if (mode == UiMode.MEDIA) it.mediaPreviewItem else null,
                mediaPreviewBytes = if (mode == UiMode.MEDIA) it.mediaPreviewBytes else null,
                mediaPreviewLoading = mode == UiMode.MEDIA && it.mediaPreviewLoading,
                mediaStreamSource = if (mode == UiMode.MEDIA) it.mediaStreamSource else null,
            )
        }
        previewToCancel?.cancel()
        if (mode == UiMode.MEDIA && _uiState.value.mediaItems.isEmpty()) refreshMedia()
    }

    fun setMediaDateRange(
        range: MediaDateRange?,
        connection: dev.openeos.control.data.CameraInfo? = _uiState.value.info,
        generation: Long = _uiState.value.mediaSessionGeneration,
    ) {
        // A display-only filter never changes loaded media, transfer ownership, or capture review.
        // A queued callback from a dismissed old-session form must not alter the new session.
        _uiState.update {
            it.withMediaDateRangeForSession(range, connection, generation)
        }
    }

    fun setMediaRatingFilter(
        filter: MediaRatingFilter,
        connection: dev.openeos.control.data.CameraInfo? = _uiState.value.info,
        generation: Long = _uiState.value.mediaSessionGeneration,
    ) {
        _uiState.update { it.withMediaRatingFilterForSession(filter, connection, generation) }
    }

    fun setMediaFolderFilter(
        filter: MediaFolderFilter,
        connection: dev.openeos.control.data.CameraInfo? = _uiState.value.info,
        generation: Long = _uiState.value.mediaSessionGeneration,
    ) {
        _uiState.update { it.withMediaFolderFilterForSession(filter, connection, generation) }
    }

    fun setMediaLibraryScope(scope: MediaLibraryScope) {
        val state = _uiState.value
        if (state.mediaLibraryScope == scope || state.isBusy(CameraOperation.MEDIA)) return
        if (state.mediaLibraryLoading) invalidateMediaLibraryLoad(MediaLibraryLoadStatus.CANCELLED)
        _uiState.update { current ->
            val retained = if (scope == MediaLibraryScope.RECENT) {
                current.mediaItems.take(RECENT_MEDIA_ITEMS)
            } else {
                current.mediaItems
            }
            current.withEventMediaItems(retained).copy(
                mediaLibraryScope = scope,
                mediaLibraryHasMore = scope == MediaLibraryScope.RECENT &&
                    (current.mediaLibraryHasMore || current.mediaItems.size > RECENT_MEDIA_ITEMS),
            )
        }
        if (state.connected && !state.previewMode) refreshMedia()
    }

    fun setCaptureMode(mode: CaptureMode) {
        val state = _uiState.value
        if (state.captureMode == mode || !captureModeSwitchEnabled(state)) return
        val setting = state.capabilities?.captureModeSetting()
        if (setting?.key?.isShootingModeKey() == true && setting.currentCaptureMode() == CaptureMode.PHOTO) {
            lastPhotoShootingMode = setting.value
        }
        val target = setting?.valueForCaptureMode(mode, lastPhotoShootingMode)
        if (target != null && target != setting.value) {
            setCameraSetting(setting.key, target)
        } else {
            _uiState.update { it.copy(captureMode = mode, activeSettingPicker = null) }
        }
    }

    fun setHudVisible(visible: Boolean) {
        if (!visible) stopHeldAutofocus()
        _uiState.update { it.copy(hudVisible = visible) }
    }

    fun setGridVisible(visible: Boolean) = _uiState.update { it.copy(showGrid = visible) }

    fun setOperatorConfirmation(feature: CameraFeature, confirmed: Boolean) {
        _uiState.update { current ->
            val eligible = feature in physicalValidationSummary(current).eligibleFeatures
            current.copy(
                operatorConfirmedFeatures = when {
                    confirmed && eligible -> current.operatorConfirmedFeatures + feature
                    !confirmed -> current.operatorConfirmedFeatures - feature
                    else -> current.operatorConfirmedFeatures
                },
            )
        }
    }

    fun setHistogramVisible(visible: Boolean) = updateMonitorSettings {
        copy(histogramVisible = visible, waveformVisible = if (visible) false else waveformVisible)
    }

    fun setWaveformVisible(visible: Boolean) = updateMonitorSettings {
        copy(waveformVisible = visible, histogramVisible = if (visible) false else histogramVisible)
    }

    fun setZebraThreshold(thresholdPercent: Int?) = updateMonitorSettings {
        copy(zebraThresholdPercent = thresholdPercent?.coerceIn(50, 100))
    }

    fun setFalseColorEnabled(enabled: Boolean) = updateMonitorSettings { copy(falseColorEnabled = enabled) }

    fun setFocusPeakingEnabled(enabled: Boolean) = updateMonitorSettings { copy(focusPeakingEnabled = enabled) }

    fun setFrameGuide(guide: LiveViewFrameGuide) = updateMonitorSettings { copy(frameGuide = guide) }

    fun setSafeAreaVisible(visible: Boolean) = updateMonitorSettings { copy(safeAreaVisible = visible) }

    fun setDesqueeze(desqueeze: LiveViewDesqueeze) = updateMonitorSettings { copy(desqueeze = desqueeze) }

    fun beginCubeLutPicker(): String? = cubeLutImporter.beginPicker()

    fun restoreCubeLutPicker(ticket: String) = cubeLutImporter.restorePicker(ticket)

    fun importCubeLut(context: Context, ticket: String, uri: Uri?) {
        val resolver = context.applicationContext.contentResolver
        cubeLutImporter.completePicker(ticket, uri?.let { selected ->
            suspend { readCubeLutDocument(resolver, selected) }
        })
    }

    fun reportCubeLutPickerFailure(ticket: String, message: String) = cubeLutImporter.pickerFailed(ticket, message)

    fun cancelCubeLutImport() = cubeLutImporter.cancel()

    fun clearCubeLut() = cubeLutImporter.remove()

    private fun updateMonitorSettings(update: LiveViewMonitorSettings.() -> LiveViewMonitorSettings) {
        _uiState.update { it.copy(monitorSettings = it.monitorSettings.update()) }
    }

    fun openSettingPicker(picker: SettingPicker) {
        stopHeldAutofocus()
        _uiState.update { it.copy(activeSettingPicker = picker) }
    }

    fun closeSettingPicker() {
        maintenanceStopRecoveryJob?.cancel()
        activeMaintenanceAttempt?.let { cancelMaintenance(it.id) }
        activeShootingSettingAttempt?.let { cancelShootingSetting(it.id) }
        _uiState.update { it.copy(activeSettingPicker = null) }
    }

    fun setConnectionTarget(target: ConnectionTarget) {
        if (_uiState.value.connected || _uiState.value.connectionTarget == target) return
        cancelConnectionAttempt()
        _uiState.update { it.copy(connectionTarget = target, error = null, errorOperation = null, connectionRecovery = null) }
    }

    fun setBaseUrl(value: String) {
        if (_uiState.value.connected || _uiState.value.baseUrl == value) return
        cancelConnectionAttempt()
        stopLiveViewLoop()
        _uiState.update { it.withClearedSession(baseUrl = value, error = null) }
    }

    fun setUsername(value: String) {
        if (_uiState.value.connected || _uiState.value.username == value) return
        cancelConnectionAttempt()
        _uiState.update { it.copy(username = value, error = null, errorOperation = null, connectionRecovery = null) }
    }

    fun setPassword(value: String) {
        if (_uiState.value.connected || _uiState.value.password == value) return
        cancelConnectionAttempt()
        _uiState.update { it.copy(password = value, error = null, errorOperation = null, connectionRecovery = null) }
    }

    fun setBridgeBaseUrl(value: String) {
        if (_uiState.value.connected || _uiState.value.bridgeBaseUrl == value) return
        cancelConnectionAttempt()
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = it.baseUrl, error = null).copy(
                bridgeBaseUrl = value,
                bridgeScanCompleted = false,
                bridgeCameras = emptyList(),
                selectedBridgeCameraId = null,
            )
        }
    }

    fun setBridgeToken(value: String) {
        if (_uiState.value.connected || _uiState.value.bridgeToken == value) return
        cancelConnectionAttempt()
        _uiState.update {
            it.copy(
                bridgeToken = value,
                bridgeScanCompleted = false,
                connectionRecovery = null,
                bridgeCameras = emptyList(),
                selectedBridgeCameraId = null,
                error = null,
                errorOperation = null,
            )
        }
    }

    fun selectBridgeCamera(cameraId: String) {
        if (_uiState.value.connected || _uiState.value.selectedBridgeCameraId == cameraId) return
        cancelConnectionAttempt()
        _uiState.update { state ->
            state.copy(
                selectedBridgeCameraId = cameraId.takeIf { id -> state.bridgeCameras.any { it.id == id } },
                connectionRecovery = null,
                error = null,
                errorOperation = null,
            )
        }
    }

    fun useDirectCameraPreset() {
        if (_uiState.value.connected) return
        cancelConnectionAttempt()
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = CameraRepository.DEFAULT_CAMERA_BASE_URL, error = null)
                .copy(ccapiSimulatorMode = false)
        }
    }

    fun useDirectCameraHttpsPreset() {
        if (_uiState.value.connected) return
        cancelConnectionAttempt()
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = CameraRepository.DEFAULT_CAMERA_HTTPS_URL, error = null)
                .copy(ccapiSimulatorMode = false)
        }
    }

    fun useDevSimulatorPreset() {
        if (_uiState.value.connected) return
        cancelConnectionAttempt()
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = CameraRepository.DEV_EMULATOR_SIMULATOR_URL, error = null)
                .copy(ccapiSimulatorMode = true)
        }
    }

    /** An accepted edit or Cancel abandons setup; only another explicit action starts it again. */
    fun cancelConnectionAttempt() {
        val state = _uiState.value
        if (!state.connected && (CameraOperation.CONNECT in state.pendingOperations ||
                CameraOperation.BRIDGE in state.pendingOperations)) {
            // Reuse the session-owned cancellation, join and cleanup path. A new attempt waits
            // for this teardown before it can replace the repository backend.
            disconnect()
        }
    }

    fun enterOfflinePreview() {
        // Switching to the local preview abandons a connection attempt just like Disconnect.
        disconnect()
        stopHeldAutofocus()
        stopCameraFocusInfoLoop()
        stopLiveViewLoop()
        stopEventPollingLoop()
        cancelCaptureReview()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        _uiState.update { it.withOfflinePreview() }
    }

    fun clearError() {
        _uiState.update { it.dismissVisibleCameraMessage() }
    }

    fun refreshUsbDiagnostics(context: Context) = runCamera(CameraOperation.USB) {
        val diagnostics = UsbPtpDiagnosticScanner().scan(context.applicationContext)
        _uiState.update { it.copy(usbDiagnostics = diagnostics) }
    }

    fun requestUsbPermission(context: Context, deviceName: String) = runCamera(CameraOperation.USB) {
        val scanner = UsbPtpDiagnosticScanner()
        scanner.requestPermission(context.applicationContext, deviceName)
        val diagnostics = scanner.scan(context.applicationContext)
        _uiState.update { it.copy(usbDiagnostics = diagnostics) }
    }

    fun connect() = runCamera(CameraOperation.CONNECT, connectionAttempt = ConnectionAttemptTarget.CCAPI) {
        stopEventPollingLoopAndJoin()
        stopLiveViewLoop()
        detachNativeLiveViewListener()
        resetMediaLibraryLoad()
        cancelCaptureReview()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        validateConnectionAddress(_uiState.value.baseUrl, ConnectionAttemptTarget.CCAPI)
        val session = repository.connect(
            baseUrl = _uiState.value.baseUrl,
            username = _uiState.value.username,
            password = _uiState.value.password,
            simulatorMode = _uiState.value.ccapiSimulatorMode,
            startLiveView = appInForeground && _uiState.value.liveViewAutoRefresh,
            request = LiveViewRequest(
                fps = _uiState.value.liveViewFrameRateFps,
                size = _uiState.value.liveViewSize,
                source = _uiState.value.liveViewSource,
            ),
        )
        applyConnectedSession(session)
    }

    fun connectUsb(deviceName: String, vendorId: Int, productId: Int) = runCamera(CameraOperation.CONNECT, connectionAttempt = ConnectionAttemptTarget.USB) {
        stopEventPollingLoopAndJoin()
        stopLiveViewLoop()
        detachNativeLiveViewListener()
        resetMediaLibraryLoad()
        cancelCaptureReview()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        val session = repository.connectUsb(
            deviceName = deviceName,
            vendorId = vendorId,
            productId = productId,
            startLiveView = appInForeground && _uiState.value.liveViewAutoRefresh,
            request = LiveViewRequest(
                fps = _uiState.value.liveViewFrameRateFps,
                size = _uiState.value.liveViewSize,
                source = _uiState.value.liveViewSource,
            ),
        )
        applyConnectedSession(session)
    }

    fun scanDesktopBridge() = runCamera(CameraOperation.BRIDGE, connectionAttempt = ConnectionAttemptTarget.DESKTOP_BRIDGE) {
        val state = _uiState.value
        _uiState.update { it.copy(bridgeScanCompleted = false) }
        validateConnectionAddress(state.bridgeBaseUrl, ConnectionAttemptTarget.DESKTOP_BRIDGE)
        val generation = cameraSessionGeneration
        val cameras = repository.discoverBridgeCameras(
            baseUrl = state.bridgeBaseUrl,
            token = state.bridgeToken,
        )
        coroutineContext.ensureActive()
        if (generation != cameraSessionGeneration) return@runCamera
        _uiState.update { current ->
            val selected = current.selectedBridgeCameraId
                ?.takeIf { id -> cameras.any { it.id == id } }
                ?: cameras.singleOrNull()?.id
            current.copy(
                bridgeCameras = cameras,
                bridgeScanCompleted = true,
                selectedBridgeCameraId = selected,
            )
        }
    }

    fun connectBridge() = runCamera(CameraOperation.CONNECT, connectionAttempt = ConnectionAttemptTarget.DESKTOP_BRIDGE) {
        stopEventPollingLoopAndJoin()
        stopLiveViewLoop()
        detachNativeLiveViewListener()
        resetMediaLibraryLoad()
        cancelCaptureReview()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        val state = _uiState.value
        val selectedCamera = state.bridgeCameras.firstOrNull { it.id == state.selectedBridgeCameraId }
        validateConnectionAddress(state.bridgeBaseUrl, ConnectionAttemptTarget.DESKTOP_BRIDGE)
        val session = repository.connectBridge(
            baseUrl = state.bridgeBaseUrl,
            token = state.bridgeToken,
            cameraId = state.selectedBridgeCameraId,
            cameraEngine = selectedCamera?.engine,
            startLiveView = appInForeground && state.liveViewAutoRefresh,
            request = LiveViewRequest(
                fps = state.liveViewFrameRateFps,
                size = state.liveViewSize,
                source = state.liveViewSource,
            ),
        )
        applyConnectedSession(session)
    }

    private suspend fun applyConnectedSession(session: CameraSession) {
        coroutineContext.ensureActive()
        mediaUploadAttempt = null
        val supportedFps = _uiState.value.liveViewFrameRateFps.coerceIn(
            session.capabilities.liveView.minFps,
            session.capabilities.liveView.maxFps,
        )
        repository.updateLiveViewRequest(fps = supportedFps)
        configureNativeLiveViewSession(session.nativeLiveViewSession, supportedFps)
        val activeSource = session.activeLiveViewSource ?: session.nativeLiveViewSession?.source ?: when {
            session.liveViewRequest.source != LiveViewSource.AUTO -> session.liveViewRequest.source
            LiveViewSource.CCAPI_JPEG_POLLING in session.capabilities.liveView.sources -> LiveViewSource.CCAPI_JPEG_POLLING
            else -> session.capabilities.liveView.defaultSource
        }
        val captureMode = captureModeFrom(session.capabilities)
        _uiState.update {
            it.copy(
                transport = session.transport,
                info = session.info,
                mediaDeleteReceipt = null,
                activeMediaUploadName = null,
                mediaUploadProgress = null,
                lastUploadedMediaName = null,
                mediaUploadStatus = null,
                mediaUploadRefreshFailed = false,
                status = session.status,
                shutterReleaseUnconfirmed = session.status.shutterReleaseUnconfirmed == true,
                liveViewStopUnconfirmed = session.liveViewStartError != null && repository.isLiveViewStopRequired(),
                liveViewStreamFailed = false,
                capabilities = session.capabilities,
                networkDiagnostics = session.networkDiagnostics,
                liveViewFrameUrl = session.liveViewFrameUrl,
                liveViewBitmap = null,
                nativeLiveViewSession = session.nativeLiveViewSession,
                liveViewFrameRateFps = supportedFps,
                liveViewSize = session.liveViewRequest.size,
                liveViewSource = activeSource,
                liveViewMagnification = session.capabilities.liveView.currentMagnification,
                liveViewCoordinateRefreshPending = false,
                liveViewDiagnostics = session.nativeLiveViewSession?.let { native ->
                    LiveViewDiagnostics(contentType = native.contentType, sourceUrl = native.sourceUrl)
                } ?: it.liveViewDiagnostics,
                liveViewAudioStatus = session.nativeLiveViewSession?.audioStatus
                    ?: NativeLiveViewAudioStatus.None,
                captureMode = captureMode ?: it.captureMode,
                error = session.liveViewStartError,
                errorOperation = session.liveViewStartError?.let { CameraOperation.LIVE_VIEW },
            )
        }
        adoptShutterReleaseStatus(session.status)
        if (!appInForeground || !_uiState.value.liveViewAutoRefresh) {
            reconcileLiveView()
        } else if (session.liveViewStartRetired) {
            // An off/on race can retire startup before this control session is published.
            // Reconcile only that explicit retirement, never an ordinary failed Start.
            queueLiveViewReconciliation()
        } else if (session.capabilities.matrix.supports(CameraFeature.LIVE_VIEW) && session.status.temperature?.liveViewAllowed != false) {
            if (session.nativeLiveViewSession == null) {
                if (refreshLiveViewFrameInternal(reportErrors = true)) startLiveViewLoopIfNeeded()
            }
        }
        refreshCaptureReview()
        startEventPollingIfSupported()
        startCameraFocusInfoLoop()
    }

    fun rememberConnection(context: Context) {
        val state = _uiState.value
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, state.baseUrl)
            .putString(KEY_USERNAME, state.username)
            .putString(KEY_BRIDGE_BASE_URL, state.bridgeBaseUrl)
            .putString(KEY_CONNECTION_TARGET, state.connectionTarget.name)
            .apply()
    }

    private fun publishConnectionRetirementWarning() {
        val revision = repository.connectionRetirementRevision
        if (revision <= observedConnectionRetirementRevision) return
        observedConnectionRetirementRevision = revision
        val warnings = repository.connectionRetirementSafetyWarnings
        _uiState.update { it.copy(connectionRetirementUnconfirmed = true,
            shutterDisconnectWarning = it.shutterDisconnectWarning || dev.openeos.control.data.PreviousConnectionSafetyWarning.SHUTTER in warnings,
            liveViewDisconnectWarning = it.liveViewDisconnectWarning || dev.openeos.control.data.PreviousConnectionSafetyWarning.LIVE_VIEW in warnings,
            recordingDisconnectWarning = it.recordingDisconnectWarning || dev.openeos.control.data.PreviousConnectionSafetyWarning.RECORDING in warnings) }
    }

    fun disconnect() {
        handoffOwner.cancelUnlaunched(CameraImportHandoffOrigin.CAMERA)
        val foregroundImport = stopForegroundImport(ForegroundImportStopReason.SESSION_CHANGED)
        val previousState = _uiState.value
        val recordingWarning = retireRecordingAttempt(previousState)
        val liveViewWarning = previousState.liveViewDisconnectWarning || previousState.needsLiveViewStopRecovery
        val shutterWarning = previousState.needsShutterDisconnectWarning()
        activeCaptureConfigurationAttempt?.cancel()
        activeShootingSettingAttempt?.cancel()
        maintenanceAttempts.values.toList().forEach(MaintenanceAttempt::retire)
        mediaDeleteControl?.cancel()
        val ownedBulbJobs = retireOwnedBridgeBulbJobs()
        mediaUploadAttempt?.cancel()
        cameraSessionGeneration += 1
        val disconnectGeneration = cameraSessionGeneration
        val operationJobs = cameraOperationJobs.values.toList() + liveViewReconciliationJobs.toList() + listOfNotNull(foregroundImport)
        cameraOperationJobs.clear()
        liveViewReconciliationJobs.clear()
        operationJobs.forEach(Job::cancel)
        val uploadCleanupJob = mediaUploadCleanupJob
        mediaUploadCleanupJob = null
        uploadCleanupJob?.cancel()
        val previousDisconnect = disconnectJob
        stopHeldAutofocus()
        val focusJob = heldAutofocusJob
        stopCameraFocusInfoLoop()
        liveViewGeneration += 1
        stopLiveViewLoop()
        stopEventPollingLoop()
        detachNativeLiveViewListener()
        resetMediaLibraryLoad()
        cancelCaptureReview()
        closeMediaStream()
        cancelMediaDownload()
        val uploadJob = mediaUploadJob
        mediaUploadJob = null
        uploadJob?.cancel()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        if (_uiState.value.previewMode) {
            _uiState.update {
                it.withClearedSession(baseUrl = it.baseUrl, error = null).copy(
                    pendingOperations = emptySet(), shutterDisconnectWarning = shutterWarning,
                    recordingDisconnectWarning = recordingWarning,
                    liveViewDisconnectWarning = liveViewWarning,
                )
            }
            return
        }
        val job = viewModelScope.launch(NonCancellable, start = CoroutineStart.LAZY) {
            previousDisconnect?.join()
            operationJobs.filterNot { it in ownedBulbJobs }.forEach { it.join() }
            uploadJob?.join()
            uploadCleanupJob?.join()
            focusJob?.join()
            try {
                val unconfirmed = repository.disconnect()
                if (unconfirmed && cameraSessionGeneration == disconnectGeneration) _uiState.update { it.copy(liveViewDisconnectWarning = true) }
            } catch (failure: MaintenanceRetirementUnconfirmedException) {
                if (cameraSessionGeneration == disconnectGeneration) _uiState.update { it.copy(
                    error = if (failure.retirementUnconfirmed) CameraAppError.MAINTENANCE_RETIREMENT_UNCONFIRMED.messageToken else it.error,
                    shutterDisconnectWarning = it.shutterDisconnectWarning || failure.physicalReleaseUnconfirmed,
                    liveViewDisconnectWarning = it.liveViewDisconnectWarning || failure.stopUnconfirmed) }
            }
        }
        disconnectJob = job
        _uiState.update { it.copy(connectionTeardownPending = true) }
        job.invokeOnCompletion {
            publishConnectionRetirementWarning()
            if (disconnectJob === job) {
                disconnectJob = null
                _uiState.update { it.copy(connectionTeardownPending = false) }
            }
        }
        job.start()
        _uiState.update {
            it.withClearedSession(baseUrl = it.baseUrl, error = null).copy(
                pendingOperations = emptySet(), shutterDisconnectWarning = shutterWarning,
                recordingDisconnectWarning = recordingWarning,
                liveViewDisconnectWarning = liveViewWarning,
            )
        }
    }

    /** Explicit receipt recovery is exactly one retained-owner read, never global status or Live View. */
    fun refreshRecordingStatus() = runCamera(CameraOperation.STATUS) {
        if (_uiState.value.previewMode) return@runCamera
        val attempt = recordingAttempt ?: return@runCamera
        if (!ownsRecordingAttempt(attempt) || attempt.inFlight) return@runCamera
        val revision = cameraStateRevision
        attempt.retainedStopRequired = attempt.receipt().withObservation(null).stopRequired
        attempt.requestEvidence.beginObservationRead()
        val observed = try { attempt.access?.read() }
        catch (failure: Exception) {
            if (revision == cameraStateRevision && ownsRecordingAttempt(attempt)) adoptRecordingStatus(attempt, _uiState.value.status)
            throw failure
        }
        coroutineContext.ensureActive()
        if (revision != cameraStateRevision || !ownsRecordingAttempt(attempt)) return@runCamera
        if (observed?.explicit == true && observed.sequence > attempt.requestEvidence.observationFloor) {
            attempt.cancelled = false
            attempt.requestEvidence.observe(observed)
        }
        adoptRecordingStatus(attempt, _uiState.value.status)
    }

    fun refresh() = runCamera(CameraOperation.STATUS) {
        if (_uiState.value.previewMode) return@runCamera
        val generation = cameraSessionGeneration
        val connection = _uiState.value.info
        val attempt = recordingAttempt
        val (status, capabilities) = readCameraStateSnapshot()
        val snapshotRevision = cameraStateRevision
        val networkDiagnostics = repository.refreshNetworkDiagnostics()
        coroutineContext.ensureActive()
        if (generation != cameraSessionGeneration || _uiState.value.info !== connection ||
            recordingAttempt !== attempt || snapshotRevision != cameraStateRevision) return@runCamera
        if (attempt != null && ownsRecordingAttempt(attempt) && !attempt.cancelled) adoptRecordingStatus(attempt, status)
        val captureMode = captureModeFrom(capabilities)
        _uiState.update {
            it.copy(
                status = status,
                captureStatusReadbackFailed = false,
                recordingStartNotConfirmed = it.recordingStartNotConfirmed && status.recording != true,
                capabilities = capabilities,
                networkDiagnostics = networkDiagnostics,
                captureMode = captureMode ?: it.captureMode,
                liveViewMagnification = capabilities.liveView.currentMagnification
                    ?: it.liveViewMagnification?.takeIf { value -> value in capabilities.liveView.magnifications },
            )
        }
        refreshLiveViewFrameInternal(reportErrors = true)
    }

    fun refreshLiveViewFrame() {
        if (!_uiState.value.connected || _uiState.value.previewMode) return
        viewModelScope.launch {
            refreshLiveViewFrameInternal(reportErrors = true)
        }
    }

    fun setLiveViewAutoRefresh(enabled: Boolean) {
        if (!enabled) stopHeldAutofocus()
        _uiState.update { it.copy(liveViewAutoRefresh = enabled) }
        if (_uiState.value.previewMode) return
        queueLiveViewReconciliation()
    }

    fun setAppForeground(foreground: Boolean) {
        if (!foreground) maintenanceStopRecoveryJob?.cancel()
        if (!foreground) activeMaintenanceAttempt?.let { cancelMaintenance(it.id) }
        if (!foreground) stopForegroundImport(ForegroundImportStopReason.BACKGROUND)
        if (!foreground) stopHeldAutofocus()
        if (!foreground && _uiState.value.bridgeBulbStopRequired) stopOwnedBridgeBulb()
        if (appInForeground == foreground) return
        appInForeground = foreground
        if (!foreground) setRtpAudioEnabled(false)
        queueLiveViewReconciliation()
    }

    private var liveViewLifecycleStopOwner: CameraLiveViewOwner? = null

    private fun queueLiveViewReconciliation(restart: Boolean = false) {
        if (_uiState.value.maintenanceSuspended) return
        invalidateCameraFocusInfo()
        if (!appInForeground || !_uiState.value.liveViewAutoRefresh) {
            liveViewGeneration += 1
            repository.cancelPendingLiveViewStart()
            if (repository.isLiveViewRunning()) {
                liveViewLifecycleStopOwner = repository.liveViewOwner()
                repository.retireLiveView(checkNotNull(liveViewLifecycleStopOwner))
            }
            stopLiveViewLoop()
            detachNativeLiveViewListener()
            repository.setNativeLiveViewRenderingEnabled(false)
            clearLiveViewPresentation()
            _uiState.update { it.copy(liveViewStopPending = repository.isLiveViewStopRequired() &&
                !repository.isLiveViewRunning() && !it.needsLiveViewStopRecovery) }
        } else if (restart && _uiState.value.nativeLiveViewSession == null) {
            // Retire an admitted startup probe before waiting for the transition mutex.
            // Its original owner must settle Stop before reconciliation can admit a replacement.
            repository.cancelPendingLiveViewStart()
            if (liveViewFrameReads.any { !it.stopped }) {
                // Do not pause native rendering or bypass a camera-command interlock.
                liveViewGeneration += 1
                cancelLiveViewFrameReads()
            }
        }
        val generation = cameraSessionGeneration
        val connection = _uiState.value.info
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            if (generation != cameraSessionGeneration) return@launch
            try {
                reconcileLiveView(restart, generation)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    if (generation == cameraSessionGeneration && it.info === connection && it.connected) {
                        it.copy(error = formatException(exception), errorOperation = CameraOperation.LIVE_VIEW)
                    } else it
                }
            }
        }
        liveViewReconciliationJobs += job
        job.invokeOnCompletion { liveViewReconciliationJobs.remove(job) }
        job.start()
    }

    fun setRtpAudioEnabled(enabled: Boolean) {
        val state = _uiState.value
        val session = state.nativeLiveViewSession ?: return
        if (!enabled) {
            session.setAudioEnabled(false)
            _uiState.update { it.copy(liveViewAudioStatus = session.audioStatus) }
            return
        }
        if (!appInForeground || !state.liveViewAutoRefresh || state.liveViewSource != LiveViewSource.CCAPI_RTP || !state.liveViewAudioStatus.available) return
        session.setAudioEnabled(true)
    }

    fun setLiveViewFrameRate(fps: Int) {
        val liveView = _uiState.value.capabilities?.liveView
        val clampedFps = fps.coerceIn(
            liveView?.minFps ?: MIN_LIVE_VIEW_FPS,
            liveView?.maxFps ?: MAX_LIVE_VIEW_FPS,
        )
        val changed = _uiState.value.liveViewFrameRateFps != clampedFps
        if (!_uiState.value.previewMode) repository.updateLiveViewRequest(fps = clampedFps)
        _uiState.update { it.copy(liveViewFrameRateFps = clampedFps) }
        if (changed && _uiState.value.connected && !_uiState.value.previewMode && _uiState.value.liveViewAutoRefresh) {
            startLiveViewLoopIfNeeded()
        }
    }

    fun setLiveViewSource(source: LiveViewSource) {
        val state = _uiState.value
        if (source == state.liveViewSource || source !in state.capabilities?.liveView?.sources.orEmpty()) return
        if (state.previewMode) {
            _uiState.update { it.copy(liveViewSource = source) }
            return
        }
        repository.updateLiveViewRequest(source = source)
        _uiState.update { it.copy(liveViewSource = source) }
        restartLiveView()
    }

    fun setLiveViewSize(size: LiveViewSize) {
        if (_uiState.value.liveViewSize == size) return
        if (_uiState.value.liveViewSource == LiveViewSource.CCAPI_RTP) return
        if (_uiState.value.previewMode) {
            _uiState.update { it.copy(liveViewSize = size) }
            return
        }
        repository.updateLiveViewRequest(size = size)
        _uiState.update { it.copy(liveViewSize = size) }
        restartLiveView()
    }

    fun restartLiveView() {
        if (!_uiState.value.maintenanceSuspended) { queueLiveViewReconciliation(restart = true); return }
        val state = _uiState.value
        if (!maintenanceMayRetryStop(state.maintenanceSuspended, state.needsLiveViewStopRecovery,
                state.pendingOperations.isNotEmpty(), state.connected && !state.previewMode && appInForeground &&
                    !state.bulbExposureActive && !state.shutterReleaseUnconfirmed)) return
        val stop = repository.maintenanceStopRecoveryAccess()
        val generation = cameraSessionGeneration
        val job = launchCameraOperation(CameraOperation.LIVE_VIEW, onRegistered = { maintenanceStopRecoveryJob = it }) {
            stop()
            coroutineContext.ensureActive()
            if (generation == cameraSessionGeneration && _uiState.value.info === state.info) {
                _uiState.update {
                    // A receipt-only owned Stop resolves this request, never a newer UI observation.
                    if (it.status !== state.status || it.liveViewStopUnconfirmed != state.liveViewStopUnconfirmed)
                        it.copy(liveViewAutoRefresh = false)
                    else it.copy(liveViewStopUnconfirmed = false,
                        status = it.status?.copy(liveViewStopUnconfirmed = false), liveViewAutoRefresh = false)
                }
                // This explicit Stop does not release maintenance hold, restore events, or replay the action.
            }
        }
        job?.invokeOnCompletion { if (maintenanceStopRecoveryJob === job) maintenanceStopRecoveryJob = null }
    }

    private suspend fun reconcileLiveView(
        restart: Boolean = false,
        generation: Long = cameraSessionGeneration,
    ) = liveViewTransitionMutex.withLock {
        coroutineContext.ensureActive()
        if (generation != cameraSessionGeneration) return@withLock
        val state = _uiState.value
        if (state.maintenanceSuspended) return@withLock
        if (
            !state.connected || state.previewMode ||
                (!state.supports(CameraFeature.LIVE_VIEW) && !repository.isLiveViewStopRequired())
        ) return@withLock
        // Do not change the camera's remote-view session in the middle of a bulb exposure.
        if (state.bulbExposureActive || state.shutterReleaseUnconfirmed || state.bridgeBulbStopRequired) return@withLock
        if (liveViewRecordingInterlocked(CameraOperation.RECORDING in state.pendingOperations,
                state.pendingRecordingCommand != null, state.recordingStopAvailable)) return@withLock
        if (state.pendingOperations.any { it in LIVE_VIEW_INTERLOCK_OPERATIONS }) return@withLock
        val stopOnly = state.needsLiveViewStopRecovery ||
            (!state.supports(CameraFeature.LIVE_VIEW) && repository.isLiveViewStopRequired())
        val enabled = !stopOnly && appInForeground && state.liveViewAutoRefresh && state.liveViewTemperatureAllowed
        val hasPresentation = state.nativeLiveViewSession != null || state.liveViewBitmap != null || state.liveViewFrameUrl != null
        if (enabled == repository.isLiveViewRunning() && !restart && (!enabled || hasPresentation) &&
            (enabled || !repository.isLiveViewStopRequired())) return@withLock
        cameraStateRevision += 1
        _uiState.update { it.copy(pendingOperations = it.pendingOperations + CameraOperation.LIVE_VIEW) }
        try {
            liveViewGeneration += 1
            stopLiveViewLoop()
            detachNativeLiveViewListener()
            _uiState.update {
                it.copy(liveViewBitmap = null, liveViewFrameUrl = null, nativeLiveViewSession = null,
                    focusPoint = null, focusFeedback = null, liveViewDiagnostics = LiveViewDiagnostics(),
                    liveViewAudioStatus = NativeLiveViewAudioStatus.None)
            }
            val effectiveRequest = repository.setLiveViewEnabled(enabled, restart ||
                liveViewLifecycleStopOwner?.let(repository::ownsLiveView) == true)
            liveViewLifecycleStopOwner = null
            coroutineContext.ensureActive()
            if (generation != cameraSessionGeneration || _uiState.value.info !== state.info) return@withLock
            _uiState.update {
                it.copy(
                    liveViewStopUnconfirmed = false,
                    liveViewStopPending = false,
                    status = if (stopOnly) it.status?.copy(liveViewStopUnconfirmed = false) else it.status,
                    liveViewAutoRefresh = if (stopOnly) false else it.liveViewAutoRefresh,
                    liveViewStreamFailed = if (enabled) false else it.liveViewStreamFailed,
                    error = if (it.errorOperation == CameraOperation.LIVE_VIEW && (enabled || !it.liveViewStreamFailed)) null else it.error,
                    errorOperation = it.errorOperation.takeUnless { operation ->
                        operation == CameraOperation.LIVE_VIEW && (enabled || !it.liveViewStreamFailed)
                    },
                )
            }
            if (!repository.isLiveViewRunning() || !enabled || !appInForeground || !_uiState.value.liveViewAutoRefresh || !_uiState.value.connected) return@withLock
            val capabilities = repository.refreshCapabilities()
            coroutineContext.ensureActive()
            if (generation != cameraSessionGeneration || _uiState.value.info !== state.info) return@withLock
            val nativeSession = repository.nativeLiveViewSession()
            configureNativeLiveViewSession(nativeSession, _uiState.value.liveViewFrameRateFps)
            _uiState.update {
                it.copy(
                    capabilities = capabilities,
                    nativeLiveViewSession = nativeSession,
                    liveViewSource = repository.activeLiveViewSource() ?: nativeSession?.source ?: it.liveViewSource,
                    liveViewSize = effectiveRequest.size,
                    liveViewBitmap = null,
                    liveViewFrameUrl = null,
                    liveViewMagnification = null,
                    liveViewDiagnostics = nativeSession?.let { session ->
                        LiveViewDiagnostics(contentType = session.contentType, sourceUrl = session.sourceUrl)
                    } ?: LiveViewDiagnostics(),
                    liveViewAudioStatus = nativeSession?.audioStatus
                        ?: NativeLiveViewAudioStatus.None,
                )
            }
            resetFrameMetrics()
            if (nativeSession == null) {
                if (refreshLiveViewFrameInternal(reportErrors = true)) startLiveViewLoopIfNeeded()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (generation == cameraSessionGeneration && _uiState.value.info === state.info) {
                _uiState.update { it.copy(liveViewStopUnconfirmed = repository.isLiveViewStopRequired(), liveViewStopPending = false) }
                throw exception
            }
        } finally {
            if (generation == cameraSessionGeneration && _uiState.value.info === state.info) {
                cameraStateRevision += 1
                _uiState.update { it.copy(
                    pendingOperations = it.pendingOperations - CameraOperation.LIVE_VIEW,
                    liveViewStopUnconfirmed = it.liveViewStopUnconfirmed ||
                        (repository.isLiveViewStopRequired() && !repository.isLiveViewRunning()),
                ) }
            }
        }
    }

    fun setIso(value: String) {
        if (updatePreviewExposure { it.copy(iso = value) }) return
        setCameraSetting("iso", value)
    }

    fun setShutter(value: String) {
        if (updatePreviewExposure { it.copy(shutter = value) }) return
        setCameraSetting("shutter", value)
    }

    fun setAperture(value: String) {
        if (updatePreviewExposure { it.copy(aperture = value) }) return
        setCameraSetting("aperture", value)
    }

    fun setWhiteBalance(value: String) {
        if (updatePreviewExposure { it.copy(whiteBalance = value) }) return
        setCameraSetting("whitebalance", value)
    }

    fun setCameraSetting(key: String, value: String) {
        val state = _uiState.value
        if (state.previewMode) {
            val observed = runCatching { ShootingSettingObservation(key, value) }.getOrNull() ?: return
            adoptShootingSettingObservation(observed)
            return
        }
        val request = runCatching { ShootingSettingRequest(key, value) }.getOrNull() ?: return
        submitShootingSetting(ShootingSettingDraft(ShootingSettingOwner(state.info, state.mediaSessionGeneration),
            UUID.randomUUID().toString(), 0L, request))
    }

    fun submitShootingSetting(draft: ShootingSettingDraft) {
        val state = _uiState.value
        if (!state.connected || state.previewMode || state.busy || state.isBusy(CameraOperation.SETTING) || shootingSettingJob != null ||
            !draft.owner.matches(state.info, state.mediaSessionGeneration)) return
        if (draft.request.key.isCaptureModeKey() && !captureModeSwitchEnabled(state)) return
        val control = state.shootingSettingControl(draft.request.key) ?: return
        if (!acceptsShootingSettingValue(control, draft.request.requestedValue)) return
        val access = try { repository.shootingSettingAccess(draft.request) } catch (_: Exception) {
            _uiState.update { it.copy(error = "This setting does not expose a safe scoped read/write route.", errorOperation = CameraOperation.SETTING) }
            return
        }
        if (!shootingSettingDrafts.claim(draft)) return
        val generation = cameraSessionGeneration
        fun owns() = generation == cameraSessionGeneration && draft.owner.matches(_uiState.value.info, _uiState.value.mediaSessionGeneration)
        lateinit var attempt: ShootingSettingAttempt
        attempt = ShootingSettingAttempt(draft, access, ::owns) { receipt ->
            _uiState.update { current ->
                val history = current.shootingSettingHistory.map { if (it.id == receipt.id) receipt else it }
                if (owns() && shootingSettingAttempt === attempt) current.copy(shootingSettingReceipt = receipt, shootingSettingHistory = history)
                else current.copy(shootingSettingHistory = history)
            }
        }
        shootingSettingAttempt = attempt
        shootingSettingAttempts[attempt.id] = attempt
        _uiState.update { it.copy(shootingSettingReceipt = attempt.snapshot(),
            shootingSettingHistory = shootingSettingHistory(it.shootingSettingHistory, attempt.snapshot())) }
        shootingSettingAttempts.keys.retainAll(_uiState.value.shootingSettingHistory.mapTo(hashSetOf()) { it.id })
        val job = launchCameraOperation(CameraOperation.SETTING, onRegistered = {
            shootingSettingJob = it; activeShootingSettingAttempt = attempt
        }) {
            val revision = cameraStateRevision
            val observed = attempt.run()
            if (observed != null && owns() && !attempt.snapshot().cancelled && revision == cameraStateRevision) {
                adoptShootingSettingObservation(observed)
            }
        }
        job?.invokeOnCompletion {
            attempt.finishBeforeStart()
            if (shootingSettingJob === job) {
                shootingSettingJob = null
                if (activeShootingSettingAttempt === attempt) activeShootingSettingAttempt = null
            }
        }
        if (job == null) attempt.finishBeforeStart()
    }

    private fun adoptShootingSettingObservation(observed: ShootingSettingObservation) {
        _uiState.update { current ->
            val updated = current.withShootingSettingObservation(observed)
            updated.copy(captureMode = if (observed.key.isCaptureModeKey())
                updated.capabilities?.let(::captureModeFrom) ?: updated.captureMode else updated.captureMode)
        }
    }

    fun cancelShootingSetting(id: String) {
        val attempt = activeShootingSettingAttempt?.takeIf { it.id == id } ?: return
        if (!attempt.draft.owner.matches(_uiState.value.info, _uiState.value.mediaSessionGeneration)) return
        attempt.cancel()
        shootingSettingJob?.cancel()
    }

    fun recoverShootingSetting(id: String) {
        val attempt = shootingSettingAttempts[id] ?: return
        val state = _uiState.value
        if (!state.connected || state.busy || state.isBusy(CameraOperation.SETTING) || shootingSettingJob != null ||
            state.shootingSettingHistory.none { it.id == id } ||
            !attempt.draft.owner.matches(state.info, state.mediaSessionGeneration)) return
        val generation = cameraSessionGeneration
        val job = launchCameraOperation(CameraOperation.SETTING, onRegistered = {
            shootingSettingJob = it; activeShootingSettingAttempt = attempt
        }) {
            val revision = cameraStateRevision
            val observed = attempt.recover()
            if (observed != null && generation == cameraSessionGeneration && revision == cameraStateRevision &&
                attempt.draft.owner.matches(_uiState.value.info, _uiState.value.mediaSessionGeneration)) {
                adoptShootingSettingObservation(observed)
            }
        }
        job?.invokeOnCompletion { if (shootingSettingJob === job) {
            shootingSettingJob = null
            if (activeShootingSettingAttempt === attempt) activeShootingSettingAttempt = null
        } }
    }

    fun syncCameraClock() = submitMaintenance(MaintenanceDraft(
        MaintenanceOwner(_uiState.value.info, _uiState.value.mediaSessionGeneration), UUID.randomUUID().toString(),
        MaintenanceRequest(MaintenanceAction.CLOCK_SYNC, MaintenanceClockTarget.now())))

    fun submitMaintenance(draft: MaintenanceDraft) {
        val state = _uiState.value
        val feature = when (draft.request.action) {
            MaintenanceAction.CLOCK_SYNC -> CameraFeature.CAMERA_CLOCK_SYNC
            MaintenanceAction.SENSOR_CLEANING -> CameraFeature.SENSOR_CLEANING
            MaintenanceAction.SLEEP -> CameraFeature.CAMERA_SLEEP
        }
        if (!state.connected || state.previewMode || state.busy || maintenanceJob != null ||
            state.recordingStopAvailable || state.bulbExposureActive || state.shutterReleaseUnconfirmed || state.needsLiveViewStopRecovery ||
            state.autofocusHoldState != AutofocusHoldState.IDLE || !state.supports(feature) ||
            !draft.owner.matches(state.info, state.mediaSessionGeneration)) return
        val access = try { repository.maintenanceAccess(draft.request, eventPollingJob != null) } catch (_: Exception) { return }
        if (!maintenanceDrafts.claim(draft)) return
        val generation = cameraSessionGeneration
        fun owns() = generation == cameraSessionGeneration && draft.owner.matches(_uiState.value.info, _uiState.value.mediaSessionGeneration)
        lateinit var attempt: MaintenanceAttempt
        attempt = MaintenanceAttempt(draft, access, ::owns, canRestore = {
            val latest = _uiState.value
            owns() && maintenanceAttempt === attempt && !latest.recordingStopAvailable && !latest.bulbExposureActive &&
                !latest.shutterReleaseUnconfirmed && maintenanceRestoreStopSafe(latest.needsLiveViewStopRecovery,
                    repository.isLiveViewStopRequired(), repository.isLiveViewRunning(), afterRestore = false) &&
                latest.autofocusHoldState == AutofocusHoldState.IDLE && appInForeground
        }) { receipt ->
            _uiState.update { current ->
                val history = current.maintenanceHistory.map { if (it.id == receipt.id) receipt else it }
                if (owns() && maintenanceAttempt === attempt && !receipt.cancelled && !receipt.retired) {
                    current.copy(maintenanceReceipt = receipt, maintenanceHistory = history,
                        lastClockSyncAtMillis = if (receipt.evidence.command == MaintenanceCommandEvidence.RESPONSE_RECEIVED &&
                            receipt.evidence.clockRead == MaintenanceClockEvidence.MATCHED) draft.request.clock?.epochMillis
                            ?: current.lastClockSyncAtMillis else current.lastClockSyncAtMillis)
                } else current.copy(maintenanceHistory = history)
            }
        }
        maintenanceAttempt = attempt
        maintenanceAttempts[attempt.id] = attempt
        maintenanceEventRestore[attempt.id] = eventPollingJob != null
        _uiState.update { it.copy(maintenanceReceipt = attempt.snapshot(),
            maintenanceHistory = maintenanceHistory(it.maintenanceHistory, attempt.snapshot()),
            maintenanceSuspended = it.maintenanceSuspended || draft.request.needsStop) }
        maintenanceAttempts.keys.retainAll(_uiState.value.maintenanceHistory.mapTo(hashSetOf()) { it.id })
        maintenanceEventRestore.keys.retainAll(maintenanceAttempts.keys)
        val retiredJobs = if (draft.request.needsStop) {
            liveViewGeneration += 1
            val jobs = listOfNotNull(eventPollingJob, liveViewJob, focusInfoJob) + liveViewReconciliationJobs.toList() +
                liveViewFrameReads.map { it.job }
            stopLiveViewLoop(); stopEventPollingLoop(); stopCameraFocusInfoLoop(); detachNativeLiveViewListener()
            repository.setNativeLiveViewRenderingEnabled(false)
            jobs.forEach(Job::cancel)
            cancelMediaDownload(); cancelMediaThumbnailLoads()
            _uiState.update { it.copy(liveViewBitmap = null, liveViewFrameUrl = null, nativeLiveViewSession = null) }
            jobs
        } else emptyList()
        val job = launchCameraOperation(CameraOperation.MAINTENANCE, onRegistered = {
            maintenanceJob = it; activeMaintenanceAttempt = attempt
        }) {
            retiredJobs.forEach { it.join() }
            attempt.run()
            publishMaintenanceStopWarning(attempt, generation)
        }
        job?.invokeOnCompletion {
            attempt.finishBeforeStart()
            if (maintenanceJob === job) { maintenanceJob = null; if (activeMaintenanceAttempt === attempt) activeMaintenanceAttempt = null }
        }
        if (job == null) attempt.finishBeforeStart()
    }

    private fun publishMaintenanceStopWarning(attempt: MaintenanceAttempt, generation: Long) {
        if (generation != cameraSessionGeneration || maintenanceAttempt !== attempt ||
            !attempt.draft.owner.matches(_uiState.value.info, _uiState.value.mediaSessionGeneration)) return
        val evidence = attempt.snapshot().evidence
        if (evidence.liveViewStop == MaintenanceStopEvidence.UNKNOWN ||
            (evidence.restore == MaintenanceRestoreEvidence.UNKNOWN && repository.isLiveViewStopRequired())) {
            _uiState.update { it.copy(liveViewStopUnconfirmed = true) }
        }
    }

    fun cancelMaintenance(id: String) {
        val attempt = activeMaintenanceAttempt?.takeIf { it.id == id } ?: return
        if (!attempt.draft.owner.matches(_uiState.value.info, _uiState.value.mediaSessionGeneration)) return
        attempt.cancel(); maintenanceJob?.cancel()
    }

    fun recoverMaintenance(id: String) = continueMaintenance(id, restore = false)
    fun restoreMaintenance(id: String) = continueMaintenance(id, restore = true)

    private fun continueMaintenance(id: String, restore: Boolean) {
        val attempt = maintenanceAttempts[id] ?: return
        val state = _uiState.value
        if (!state.connected || state.previewMode || maintenanceJob != null || state.pendingOperations.isNotEmpty() ||
            state.maintenanceHistory.none { it.id == id } || !attempt.draft.owner.matches(state.info, state.mediaSessionGeneration)) return
        if (restore && (maintenanceAttempt !== attempt || !canRestoreMaintenance(attempt.snapshot(), true) ||
                state.recordingStopAvailable || state.bulbExposureActive || state.shutterReleaseUnconfirmed ||
                state.needsLiveViewStopRecovery || repository.isLiveViewStopRequired() || state.autofocusHoldState != AutofocusHoldState.IDLE || !appInForeground)) return
        val generation = cameraSessionGeneration
        val job = launchCameraOperation(CameraOperation.MAINTENANCE, onRegistered = {
            maintenanceJob = it; activeMaintenanceAttempt = attempt
        }) {
            if (!restore) attempt.recover()
            else if (attempt.restore() && generation == cameraSessionGeneration && maintenanceAttempt === attempt &&
                !attempt.snapshot().cancelled && !attempt.snapshot().retired && !_uiState.value.recordingStopAvailable &&
                !_uiState.value.bulbExposureActive && !_uiState.value.shutterReleaseUnconfirmed &&
                maintenanceRestoreStopSafe(_uiState.value.needsLiveViewStopRecovery,
                    repository.isLiveViewStopRequired(), repository.isLiveViewRunning(), afterRestore = true) &&
                attempt.draft.owner.matches(_uiState.value.info, _uiState.value.mediaSessionGeneration)) {
                _uiState.update { it.copy(maintenanceSuspended = false, liveViewAutoRefresh = repository.isLiveViewRunning()) }
                val native = repository.nativeLiveViewSession()
                configureNativeLiveViewSession(native, _uiState.value.liveViewFrameRateFps)
                _uiState.update { it.copy(nativeLiveViewSession = native) }
                if (maintenanceEventRestore[id] == true) startEventPollingIfSupported()
                if (native == null && repository.isLiveViewRunning()) {
                    refreshLiveViewFrameInternal(reportErrors = true); startLiveViewLoopIfNeeded()
                }
            }
            if (restore) publishMaintenanceStopWarning(attempt, generation)
        }
        job?.invokeOnCompletion { if (maintenanceJob === job) {
            maintenanceJob = null; if (activeMaintenanceAttempt === attempt) activeMaintenanceAttempt = null
        } }
    }

    fun createDirectory(name: String) = submitCaptureConfiguration(CaptureConfigurationDraft(
        CaptureConfigurationOwner(_uiState.value.info, _uiState.value.mediaSessionGeneration),
        UUID.randomUUID().toString(), 0, CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, name)))

    fun setFileNaming(field: CameraFileNamingField, value: String) = submitCaptureConfiguration(CaptureConfigurationDraft(
        CaptureConfigurationOwner(_uiState.value.info, _uiState.value.mediaSessionGeneration),
        UUID.randomUUID().toString(), 0, CaptureConfigurationRequest(CaptureConfigurationOperation.SET_FILE_NAMING, value, field)))

    fun submitCaptureConfiguration(draft: CaptureConfigurationDraft) {
        val state = _uiState.value
        if (!state.connected || state.previewMode || state.busy || captureConfigurationJob != null ||
            !draft.owner.matches(state.info, state.mediaSessionGeneration)) return
        val request = draft.request
        when (request.operation) {
            CaptureConfigurationOperation.CREATE_DIRECTORY -> if (!state.supports(CameraFeature.DIRECTORY_CONTROL) ||
                !Regex("^(?:[A-Z0-9_]{5})?$").matches(request.requestedValue)) return
            CaptureConfigurationOperation.SET_FILE_NAMING -> if (!state.supports(CameraFeature.FILE_NAMING_CONTROL) ||
                state.capabilities?.fileNaming?.accepts(requireNotNull(request.field), request.requestedValue) != true) return
        }
        if (!consumedCaptureConfigurationDrafts.claim(draft)) return
        val generation = cameraSessionGeneration
        val access = repository.captureConfigurationAccess()
        lateinit var attempt: CaptureConfigurationAttempt
        fun owns(): Boolean = generation == cameraSessionGeneration &&
            draft.owner.matches(_uiState.value.info, _uiState.value.mediaSessionGeneration)
        attempt = CaptureConfigurationAttempt(draft, access, ::owns) { receipt ->
            _uiState.update { current ->
                // Late results may settle an existing bounded row only. They never reinsert
                // evicted history or mutate a replacement session's receipt, locks or editor.
                val history = current.captureConfigurationHistory.map { if (it.id == receipt.id) receipt else it }
                if (!canPublishCaptureConfiguration(receipt, captureConfigurationAttempt?.id,
                    current.info, current.mediaSessionGeneration) || generation != cameraSessionGeneration) {
                    current.copy(captureConfigurationHistory = history)
                } else current.copy(captureConfigurationHistory = history, captureConfigurationReceipt = receipt,
                    lastCreatedDirectoryName = captureConfigurationDirectorySummary(receipt, current.lastCreatedDirectoryName))
            }
        }
        captureConfigurationAttempt = attempt
        captureConfigurationAttempts[attempt.id] = attempt
        _uiState.update { it.copy(captureConfigurationReceipt = attempt.snapshot(),
            captureConfigurationHistory = captureConfigurationHistory(it.captureConfigurationHistory, attempt.snapshot())) }
        val retainedIds = _uiState.value.captureConfigurationHistory.mapTo(hashSetOf()) { it.id }
        captureConfigurationAttempts.keys.retainAll(retainedIds)
        val operation = if (request.operation == CaptureConfigurationOperation.CREATE_DIRECTORY) CameraOperation.DIRECTORY else CameraOperation.SETTING
        val job = launchCameraOperation(operation, onRegistered = { captureConfigurationJob = it; activeCaptureConfigurationAttempt = attempt }) {
            val updated = attempt.run()
            if (owns() && !attempt.snapshot().cancelled) {
                val snapshot = attempt.snapshot().evidence
                _uiState.update { current -> current.copy(capabilities = current.capabilities?.let { capabilities ->
                    capabilities.copy(fileNaming = updated ?: capabilities.fileNaming,
                        advancedSettings = if (snapshot.readbackEvidence == CaptureConfigurationReadEvidence.REFRESHED)
                            mergeCaptureConfigurationDirectorySettings(capabilities.advancedSettings,
                                CaptureConfigurationReadback(directorySelection = snapshot.observedValue, directoryValues = snapshot.directoryValues), owns())
                        else capabilities.advancedSettings)
                }) }
            }
        }
        job?.invokeOnCompletion {
            attempt.finishBeforeStart()
            if (captureConfigurationJob === job) {
                captureConfigurationJob = null
                if (activeCaptureConfigurationAttempt === attempt) activeCaptureConfigurationAttempt = null
            }
        }
        if (job == null) attempt.finishBeforeStart()
    }

    fun cancelCaptureConfiguration(id: String) {
        val attempt = activeCaptureConfigurationAttempt?.takeIf { it.id == id } ?: return
        if (!attempt.draft.owner.matches(_uiState.value.info, _uiState.value.mediaSessionGeneration)) return
        attempt.cancel()
        captureConfigurationJob?.cancel()
    }

    fun recoverCaptureConfiguration(id: String) {
        val attempt = captureConfigurationAttempts[id] ?: return
        val state = _uiState.value
        if (state.captureConfigurationHistory.none { it.id == id }) return
        if (!state.connected || state.busy || captureConfigurationJob != null ||
            !attempt.draft.owner.matches(state.info, state.mediaSessionGeneration)) return
        val generation = cameraSessionGeneration
        val job = launchCameraOperation(CameraOperation.DIRECTORY, onRegistered = { captureConfigurationJob = it; activeCaptureConfigurationAttempt = attempt }) {
            val readback = attempt.recover()
            if (generation == cameraSessionGeneration && activeCaptureConfigurationAttempt === attempt &&
                attempt.draft.owner.matches(_uiState.value.info, _uiState.value.mediaSessionGeneration)) {
                readback?.let { observed -> _uiState.update { current -> current.copy(capabilities = current.capabilities?.let { capabilities ->
                    capabilities.copy(fileNaming = observed.fileNaming ?: capabilities.fileNaming,
                        advancedSettings = mergeCaptureConfigurationDirectorySettings(capabilities.advancedSettings, observed,
                            attempt.draft.owner.matches(current.info, current.mediaSessionGeneration)))
                }) } }
            }
        }
        job?.invokeOnCompletion { if (captureConfigurationJob === job) {
            captureConfigurationJob = null
            if (activeCaptureConfigurationAttempt === attempt) activeCaptureConfigurationAttempt = null
        } }
    }

    fun sleepCamera() = submitMaintenance(MaintenanceDraft(
        MaintenanceOwner(_uiState.value.info, _uiState.value.mediaSessionGeneration), UUID.randomUUID().toString(),
        MaintenanceRequest(MaintenanceAction.SLEEP)))

    fun cleanSensor(autoPowerOff: Boolean) = submitMaintenance(MaintenanceDraft(
        MaintenanceOwner(_uiState.value.info, _uiState.value.mediaSessionGeneration), UUID.randomUUID().toString(),
        MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING, autoPowerOff = autoPowerOff)))

    fun toggleRecording() {
        val state = _uiState.value
        val stop = state.recordingStopAvailable
        val previous = recordingAttempt?.takeIf(::ownsRecordingAttempt)
        // A retained same-owner Stop survives a disconnected status or vanished capability.
        if ((!state.connected && !(stop && previous != null)) || (!stop &&
            (!state.supports(CameraFeature.VIDEO_RECORDING) || !state.movieRecordingTemperatureAllowed))) return
        if (stop && foregroundImportOwner?.operation?.isActive == true) stopForegroundImport(ForegroundImportStopReason.USER)
        runCamera(CameraOperation.RECORDING) {
            if (_uiState.value.info !== state.info) return@runCamera
            val attempt = RecordingAttempt(if (stop) RecordingCommand.STOP else RecordingCommand.START,
                cameraSessionGeneration, state.info,
                previous?.previousIds?.takeIf { stop && previous.command == RecordingCommand.STOP && !previous.reviewRetired } ?: visibleMediaIds(),
                previous?.access ?: if (state.previewMode) null else repository.recordingAccess(), stop)
            // A deliberate new Start invalidates an older Stop's pending media read.
            if (!stop) cancelCaptureReview()
            recordingAttempt = attempt
            _uiState.update { it.copy(recordingStartNotConfirmed = false) }
            try {
                val status = if (state.previewMode) {
                    attempt.requestEvidence.beginRequest(); attempt.requestEvidence.acknowledge()
                    requireNotNull(state.status).copy(recording = !stop,
                        recordingObservation = RecordingObservation(!stop, 1, RecordingObservationSource.SIMULATOR_STATUS))
                } else withContext(attempt.requestEvidence) { requireNotNull(attempt.access).dispatch(attempt.command) }
                coroutineContext.ensureActive()
                if (ownsRecordingAttempt(attempt)) adoptRecordingStatus(attempt, status)
            } catch (failure: CancellationException) {
                attempt.cancelled = true; attempt.requestCancelled = true
                attempt.requestEvidence.beginObservationRead()
                if (attempt.requestEvidence.unresolved) markRecordingPending(attempt, unconfirmed = true)
                else if (ownsRecordingAttempt(attempt)) {
                    storeRecordingReceipt(attempt, current = false)
                    recordingAttempt = previous
                }
                throw failure
            } catch (failure: Exception) {
                if (attempt.requestEvidence.attempted || attempt.requestEvidence.responseEvidence() == RecordingResponseEvidence.NOT_REQUIRED) {
                    markRecordingPending(attempt)
                } else if (ownsRecordingAttempt(attempt)) {
                    storeRecordingReceipt(attempt, current = false)
                    recordingAttempt = previous
                }
                if (failure !is RecordingStatusReadbackException && failure !is RecordingCommandUnconfirmedException) throw failure
            } finally {
                attempt.inFlight = false
                storeRecordingReceipt(attempt, current = ownsRecordingAttempt(attempt))
            }
        }
    }

    fun setShutterAutofocus(enabled: Boolean) {
        val state = _uiState.value
        if (state.canChangeShutterAutofocus() && (enabled || state.capabilities?.shutterAutofocusSupported == true)) {
            _uiState.update { it.copy(shutterAutofocus = enabled) }
        }
    }

    fun captureStill() = runCamera(CameraOperation.CAPTURE) {
        if (_uiState.value.recordingStopAvailable) return@runCamera
        // A newly admitted photo owns future selection, even if the earlier Stop has not yet been observed.
        recordingAttempt?.takeIf(::ownsRecordingAttempt)?.reviewRetired = true
        val autofocus = _uiState.value.shutterAutofocus
        if (_uiState.value.previewMode) {
            showCaptureSuccess()
            return@runCamera
        }
        val previousReviewIds = visibleMediaIds()
        // A newer shutter attempt supersedes every older review, even before its ACK arrives.
        cancelCaptureReview()
        val generation = cameraSessionGeneration
        val connection = _uiState.value.info
        fun stillOwnsCapture() = generation == cameraSessionGeneration && _uiState.value.info === connection
        val revision = cameraStateRevision
        val result = try {
            repository.captureStill(autofocus = autofocus)
        } catch (_: CaptureStatusReadbackException) {
            coroutineContext.ensureActive()
            if (!stillOwnsCapture()) return@runCamera
            _uiState.update { it.copy(captureStatusReadbackFailed = true) }
            null
        }
        coroutineContext.ensureActive()
        if (!stillOwnsCapture()) return@runCamera
        pendingCaptureReview = CaptureReviewAttempt(previousReviewIds, generation, intent = MediaReviewIntent.PHOTO)
        refreshCaptureReview()
        // The command already returned successfully. A revision-reconciliation read can also
        // fail, but must not turn that acknowledgement back into a failed shutter command.
        if (result != null) {
            val status = try {
                latestCameraStatus(result, revision)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                if (!stillOwnsCapture()) return@runCamera
                _uiState.update { it.copy(captureStatusReadbackFailed = true) }
                null
            }
            coroutineContext.ensureActive()
            if (!stillOwnsCapture()) return@runCamera
            if (status != null) {
                _uiState.update { it.copy(status = status, captureStatusReadbackFailed = false) }
                showCaptureSuccess()
            }
        }
        if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
    }

    fun toggleBulbExposure() {
        val state = _uiState.value
        if (state.bridgeBulbStopRequired) { stopOwnedBridgeBulb(); return }
        if (state.transport == CameraTransport.DESKTOP_BRIDGE && !state.previewMode && !state.bulbExposureActive) {
            startOwnedBridgeBulb()
            return
        }
        // A safety stop never waits for local Gallery cleanup before reaching the camera.
        if (_uiState.value.bulbExposureActive && foregroundImportOwner?.operation?.isActive == true) {
            stopForegroundImport(ForegroundImportStopReason.USER)
        }
        runCamera(CameraOperation.CAPTURE) {
            val active = _uiState.value.bulbExposureActive
            if (!active && (!_uiState.value.bulbMode || !_uiState.value.supports(CameraFeature.BULB_EXPOSURE))) {
                return@runCamera
            }
            if (_uiState.value.previewMode) {
                val status = requireNotNull(_uiState.value.status).copy(bulbExposureActive = !active)
                _uiState.update {
                    it.copy(
                        status = status,
                        bulbStartedAtMillis = if (active) null else SystemClock.elapsedRealtime(),
                    )
                }
                if (active) showCaptureSuccess()
                return@runCamera
            }
            pauseLiveViewForBulb()
            try {
                val revision = cameraStateRevision
                val response = if (active) repository.stopBulbExposure() else repository.startBulbExposure()
                val status = latestCameraStatus(response, revision)
                coroutineContext.ensureActive()
                _uiState.update {
                    it.copy(
                        status = status,
                        bulbStartedAtMillis = if (status.bulbExposureActive == true) {
                            it.bulbStartedAtMillis ?: SystemClock.elapsedRealtime()
                        } else {
                            null
                        },
                    )
                }
                if (active && status.bulbExposureActive != true) {
                    showCaptureSuccess()
                    resumeLiveViewAfterBulb()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                coroutineContext.ensureActive()
                if (exception is ShutterReleaseException) markShutterReleaseUnconfirmed()
                else if (!active) resumeLiveViewAfterBulb()
                throw exception
            }
        }

    }

    private fun ownsBridgeBulb(owner: BridgeBulbUiOwner, command: Long = owner.command): Boolean =
        bridgeBulbUiOwner === owner && owner.command == command &&
            owner.generation == cameraSessionGeneration && _uiState.value.info === owner.connection

    private fun startOwnedBridgeBulb() {
        val state = _uiState.value
        if (!state.connected || state.isBusy(CameraOperation.CAPTURE) || !state.bulbMode ||
            !state.supports(CameraFeature.BULB_EXPOSURE)) return
        // Reserve before launch/pause: a Stop from the very next UI event owns this same request.
        val access = try { repository.prepareBridgeBulb() ?: return } catch (error: Exception) {
            _uiState.update { it.copy(error = CameraAppError.BRIDGE_BULB_RELEASE_REQUIRED.messageToken, errorOperation = CameraOperation.CAPTURE) }
            return
        }
        val owner = BridgeBulbUiOwner(access, cameraSessionGeneration, state.info)
        bridgeBulbUiOwner = owner
        val command = owner.command
        cameraStateRevision += 1
        _uiState.update { it.copy(bridgeBulbStopRequired = true, bridgeBulbStartPending = true,
            bridgeBulbStopPending = false, bridgeBulbUnconfirmed = false,
            pendingOperations = it.pendingOperations + CameraOperation.CAPTURE,
            captureFeedback = null, error = CameraAppError.BRIDGE_BULB_START_PENDING.messageToken, errorOperation = null, hudVisible = true) }
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                pauseLiveViewForBulb()
                val response = access.start()
                coroutineContext.ensureActive()
                if (!ownsBridgeBulb(owner, command)) return@launch
                _uiState.update {
                    val display = if (it.status === state.status) response else it.status
                    it.copy(status = display, error = it.error.takeUnless { message -> message == CameraAppError.BRIDGE_BULB_START_PENDING.messageToken },
                        bulbStartedAtMillis = if (display?.bulbExposureActive == true) SystemClock.elapsedRealtime() else null)
                }
            } catch (error: TimeoutCancellationException) {
                if (ownsBridgeBulb(owner, command)) publishOwnedBulbFailure(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (ownsBridgeBulb(owner, command)) publishOwnedBulbFailure(error)
            } finally {
                if (ownsBridgeBulb(owner, command)) _uiState.update { it.copy(bridgeBulbStartPending = false,
                    pendingOperations = it.pendingOperations - CameraOperation.CAPTURE) }
                if (cameraOperationJobs[CameraOperation.CAPTURE] === job) cameraOperationJobs.remove(CameraOperation.CAPTURE)
                if (owner.startJob === job) owner.startJob = null
            }
        }
        owner.startJob = job
        cameraOperationJobs[CameraOperation.CAPTURE] = job
        job.start()
    }

    private fun retireOwnedBridgeBulbJobs(): Set<Job> {
        val owner = bridgeBulbUiOwner?.takeIf { ownsBridgeBulb(it) } ?: return emptySet()
        owner.command += 1
        owner.access.retire()
        return listOfNotNull(owner.startJob, owner.stopJob).toSet()
    }

    private fun publishOwnedBulbFailure(error: Exception) {
        val unavailable = generateSequence<Throwable>(error) { it.cause }.any {
            it is DesktopBridgeException && it.statusCode in setOf(404, 405)
        }
        val reason = if (unavailable) CameraAppError.BRIDGE_BULB_OWNERSHIP_UNAVAILABLE else CameraAppError.BRIDGE_BULB_RELEASE_REQUIRED
        _uiState.update { it.copy(bridgeBulbUnconfirmed = true, captureFeedback = null,
            error = reason.messageToken, errorOperation = CameraOperation.SHUTTER_RELEASE, hudVisible = true) }
    }

    private fun stopOwnedBridgeBulb() {
        val owner = bridgeBulbUiOwner ?: return
        if (!ownsBridgeBulb(owner) || owner.stopJob?.isActive == true) return
        owner.command += 1
        val command = owner.command
        val statusBeforeStop = _uiState.value.status
        owner.startJob?.cancel() // Local cancellation is never release evidence.
        cameraStateRevision += 1
        _uiState.update { it.copy(bridgeBulbStartPending = false, bridgeBulbStopPending = true,
            pendingOperations = it.pendingOperations - CameraOperation.CAPTURE + CameraOperation.SHUTTER_RELEASE,
            captureFeedback = null, error = CameraAppError.BRIDGE_BULB_STOP_PENDING.messageToken, errorOperation = null) }
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val response = owner.access.stop()
                coroutineContext.ensureActive()
                if (!ownsBridgeBulb(owner, command)) return@launch
                check(!owner.access.releaseRequired()) { "Bridge has not acknowledged this Bulb owner." }
                _uiState.update {
                    // The client sanitizes its own stale Start snapshot on a cached receipt.
                    // A newer UI observation is independent and must survive this older command.
                    val display = if (it.status === statusBeforeStop) response ?: it.status else it.status
                    // The owned Stop is settled before preview reconciliation. Leaving its
                    // own SHUTTER_RELEASE marker until finally would block that reconciliation
                    // after backgrounding has retired the old preview. Other interlocks remain.
                    it.copy(status = display, bridgeBulbStopRequired = false, bridgeBulbStopPending = false,
                        pendingOperations = it.pendingOperations - CameraOperation.SHUTTER_RELEASE,
                        bridgeBulbUnconfirmed = false, bulbStartedAtMillis = null,
                        error = it.error.takeUnless { message -> message == CameraAppError.BRIDGE_BULB_STOP_PENDING.messageToken },
                        shutterReleaseUnconfirmed = it.shutterReleaseUnconfirmed || display?.shutterReleaseUnconfirmed == true)
                }
                // A terminal receipt without status is not a fresh physical-stop observation.
                val current = _uiState.value
                if (!current.shutterReleaseUnconfirmed && current.status === response &&
                    response?.bulbExposureActive == false && response.shutterReleaseUnconfirmed == false &&
                    owner.access.hasFreshReleaseObservation()) resumeLiveViewAfterBulb()
            } catch (error: TimeoutCancellationException) {
                if (ownsBridgeBulb(owner, command)) {
                    if (owner.access.releaseRequired()) publishOwnedBulbFailure(error)
                    else _uiState.update { it.copy(error = formatException(error), errorOperation = CameraOperation.LIVE_VIEW) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (ownsBridgeBulb(owner, command)) {
                    if (owner.access.releaseRequired()) publishOwnedBulbFailure(error)
                    else _uiState.update { it.copy(error = formatException(error), errorOperation = CameraOperation.LIVE_VIEW) }
                }
            } finally {
                if (ownsBridgeBulb(owner, command)) {
                    _uiState.update { it.copy(bridgeBulbStopPending = false,
                        pendingOperations = it.pendingOperations - CameraOperation.SHUTTER_RELEASE) }
                    if (!owner.access.releaseRequired()) bridgeBulbUiOwner = null
                }
                if (cameraOperationJobs[CameraOperation.SHUTTER_RELEASE] === job) cameraOperationJobs.remove(CameraOperation.SHUTTER_RELEASE)
                if (owner.stopJob === job) owner.stopJob = null
            }
        }
        owner.stopJob = job
        cameraOperationJobs[CameraOperation.SHUTTER_RELEASE] = job
        job.start()
    }

    fun retryShutterRelease() {
        if (_uiState.value.bridgeBulbStopRequired) { stopOwnedBridgeBulb(); return }
        val state = _uiState.value
        if (!state.connected || state.previewMode || !state.shutterReleaseUnconfirmed) return
        val generation = cameraSessionGeneration
        runCamera(CameraOperation.SHUTTER_RELEASE) {
            repository.retryShutterRelease()
            coroutineContext.ensureActive()
            // The stop was confirmed. A later status-read failure must not pretend the release failed.
            _uiState.update {
                it.copy(
                    shutterReleaseUnconfirmed = false,
                    status = it.status?.copy(bulbExposureActive = false, shutterReleaseUnconfirmed = false),
                    bulbStartedAtMillis = null,
                )
            }
            try {
                val revision = cameraStateRevision
                val status = latestCameraStatus(repository.refreshStatus(), revision)
                _uiState.update { it.copy(status = status) }
            } finally {
                if (generation == cameraSessionGeneration && _uiState.value.info === state.info) {
                    resumeLiveViewAfterBulb()
                }
            }
        }
    }

    private fun markShutterReleaseUnconfirmed() {
        liveViewGeneration += 1
        pauseLiveViewForBulb()
        invalidateCameraFocusInfo()
        _uiState.update {
            it.copy(shutterReleaseUnconfirmed = true,
                status = it.status?.copy(bulbExposureActive = null, shutterReleaseUnconfirmed = true),
                bulbStartedAtMillis = null, captureFeedback = null, hudVisible = true)
        }
    }

    private fun adoptShutterReleaseStatus(status: CameraStatus) {
        _uiState.update { it.adoptLiveViewStopWarning(status) }
        if (status.shutterReleaseUnconfirmed == true && !_uiState.value.bridgeBulbStopRequired) markShutterReleaseUnconfirmed()
    }

    fun startHeldAutofocus() {
        val state = _uiState.value
        if (!appInForeground || !state.canStartHeldAutofocus() || heldAutofocusJob?.isActive == true) return
        val release = CompletableDeferred<Unit>()
        heldAutofocusRelease = release
        invalidateCameraFocusInfo()
        focusFeedbackJob?.cancel()
        launchHeldAutofocus(AutofocusHoldState.STARTING) {
            if (!release.isCompleted) repository.holdAutofocus {
                if (_uiState.value.info === state.info) _uiState.update {
                    it.copy(autofocusHoldState = if (release.isCompleted) AutofocusHoldState.RELEASING else AutofocusHoldState.HOLDING)
                }
                try {
                    // A lost gesture must not create an unbounded remote AF command.
                    withTimeoutOrNull(30_000) { release.await() }
                } finally {
                    if (_uiState.value.info === state.info) {
                        _uiState.update { it.copy(autofocusHoldState = AutofocusHoldState.RELEASING) }
                    }
                }
            }
        }
    }

    fun stopHeldAutofocus() {
        heldAutofocusRelease?.complete(Unit)
        _uiState.update {
            if (it.autofocusHoldState in setOf(AutofocusHoldState.STARTING, AutofocusHoldState.HOLDING)) {
                it.copy(autofocusHoldState = AutofocusHoldState.RELEASING)
            } else it
        }
    }

    fun retryHeldAutofocusStop() {
        if (!_uiState.value.connected || _uiState.value.autofocusHoldState != AutofocusHoldState.RELEASE_FAILED ||
            heldAutofocusJob?.isActive == true) return
        launchHeldAutofocus(AutofocusHoldState.RELEASING) { repository.retryAutofocusStop() }
    }

    private fun launchHeldAutofocus(phase: AutofocusHoldState, block: suspend () -> Unit) {
        val connection = _uiState.value.info
        _uiState.update {
            it.copy(autofocusHoldState = phase, pendingOperations = it.pendingOperations + CameraOperation.FOCUS,
                error = null, errorOperation = null, focusFeedback = null)
        }
        heldAutofocusJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var releaseFailed = false
            try {
                block()
                if (_uiState.value.info === connection) refreshCapabilityEvidence()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                releaseFailed = exception is AutofocusReleaseException
                if (_uiState.value.info === connection) {
                    _uiState.update { it.copy(error = formatException(exception), errorOperation = CameraOperation.FOCUS) }
                }
            } finally {
                heldAutofocusRelease = null
                if (_uiState.value.info === connection) {
                    _uiState.update {
                        it.copy(autofocusHoldState = if (releaseFailed) AutofocusHoldState.RELEASE_FAILED else AutofocusHoldState.IDLE,
                            pendingOperations = if (releaseFailed) it.pendingOperations else it.pendingOperations - CameraOperation.FOCUS)
                    }
                    if (!releaseFailed) queueLiveViewReconciliation()
                }
            }
        }.also(Job::start)
    }

    fun autofocus() {
        if (_uiState.value.isBusy(CameraOperation.FOCUS) || _uiState.value.isBusy(CameraOperation.LIVE_VIEW)) return
        invalidateCameraFocusInfo()
        focusFeedbackJob?.cancel()
        _uiState.update { it.copy(focusFeedback = FocusFeedback.FOCUSING) }
        if (_uiState.value.previewMode) {
            _uiState.update { it.copy(focusFeedback = FocusFeedback.ACCEPTED) }
            clearFocusFeedbackAfter(FocusFeedback.ACCEPTED)
            return
        }
        runCamera(
            operation = CameraOperation.FOCUS,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            val connection = _uiState.value.info
            val revision = cameraStateRevision
            val status = latestCameraStatus(repository.autofocus(), revision)
            if (_uiState.value.info !== connection) return@runCamera
            _uiState.update { it.copy(status = status, focusFeedback = FocusFeedback.ACCEPTED) }
            clearFocusFeedbackAfter(FocusFeedback.ACCEPTED)
            if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
        }
    }

    fun halfPressShutter() {
        if (_uiState.value.isBusy(CameraOperation.FOCUS) || _uiState.value.isBusy(CameraOperation.LIVE_VIEW)) return
        invalidateCameraFocusInfo()
        focusFeedbackJob?.cancel()
        _uiState.update { it.copy(focusFeedback = FocusFeedback.FOCUSING) }
        if (_uiState.value.previewMode) {
            _uiState.update { it.copy(focusFeedback = FocusFeedback.ACCEPTED) }
            clearFocusFeedbackAfter(FocusFeedback.ACCEPTED)
            return
        }
        runCamera(
            operation = CameraOperation.FOCUS,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            val connection = _uiState.value.info
            val revision = cameraStateRevision
            val status = latestCameraStatus(repository.halfPressShutter(), revision)
            if (_uiState.value.info !== connection) return@runCamera
            _uiState.update { it.copy(status = status, focusFeedback = FocusFeedback.ACCEPTED) }
            clearFocusFeedbackAfter(FocusFeedback.ACCEPTED)
            if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
        }
    }

    fun driveFocus(direction: FocusDriveDirection, step: FocusDriveStep) {
        if (_uiState.value.isBusy(CameraOperation.FOCUS) || _uiState.value.isBusy(CameraOperation.LIVE_VIEW)) return
        invalidateCameraFocusInfo()
        focusFeedbackJob?.cancel()
        _uiState.update { it.copy(focusFeedback = FocusFeedback.FOCUSING) }
        if (_uiState.value.previewMode) {
            _uiState.update { it.copy(focusFeedback = FocusFeedback.ACCEPTED) }
            clearFocusFeedbackAfter(FocusFeedback.ACCEPTED)
            return
        }
        val generation = cameraSessionGeneration
        val connection = _uiState.value.info
        val presentationGeneration = liveViewGeneration
        val liveViewOwner = repository.liveViewOwner()
        fun ownsFocusResult() = generation == cameraSessionGeneration && _uiState.value.info === connection &&
            presentationGeneration == liveViewGeneration && repository.ownsLiveView(liveViewOwner)
        runCamera(
            operation = CameraOperation.FOCUS,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            try {
                repository.driveFocus(direction, step)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // Retirement revokes presentation, not the pending physical call or
                // its FOCUS interlock. Do not replace a newer Live View failure.
                coroutineContext.ensureActive()
                if (!ownsFocusResult()) return@runCamera
                throw exception
            }
            coroutineContext.ensureActive()
            if (!ownsFocusResult()) return@runCamera
            _uiState.update { it.copy(focusFeedback = FocusFeedback.ACCEPTED) }
            clearFocusFeedbackAfter(FocusFeedback.ACCEPTED)
            if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
        }
    }

    fun setLiveViewMagnification(magnification: LiveViewMagnification) {
        val state = _uiState.value
        if (state.autofocusHoldState != AutofocusHoldState.IDLE || state.isBusy(CameraOperation.LIVE_VIEW) ||
            state.isBusy(CameraOperation.SETTING) || state.isBusy(CameraOperation.FOCUS)) return
        if (
            state.captureMode != CaptureMode.PHOTO ||
            !state.supports(CameraFeature.LIVE_VIEW_MAGNIFICATION) ||
            magnification !in state.capabilities?.liveView?.magnifications.orEmpty()
        ) return
        if (state.previewMode) {
            _uiState.update { it.copy(liveViewMagnification = magnification) }
            return
        }
        val generation = cameraSessionGeneration
        val connection = state.info
        val liveViewOwner = repository.liveViewOwner()
        invalidateCameraFocusInfo()
        liveViewGeneration += 1
        val presentationGeneration = liveViewGeneration
        fun ownsMagnification() = generation == cameraSessionGeneration && _uiState.value.info === connection &&
            presentationGeneration == liveViewGeneration && repository.ownsLiveView(liveViewOwner)
        stopLiveViewLoop()
        state.nativeLiveViewSession?.setRenderingEnabled(false)
        _uiState.update { it.copy(liveViewCoordinateRefreshPending = true, liveViewBitmap = null, liveViewFrameUrl = null) }
        runCamera(CameraOperation.LIVE_VIEW) {
            if (!ownsMagnification()) return@runCamera
            try {
                val result = repository.setLiveViewMagnification(magnification)
                coroutineContext.ensureActive()
                if (!ownsMagnification()) return@runCamera
                if (result.ok) _uiState.update { it.copy(liveViewMagnification = result.magnification) }
            } finally {
                // Rebind only this owner's post-command presentation. Buffered native camera
                // timing still needs device evidence; queued old listener callbacks are rejected.
                if (ownsMagnification()) {
                    liveViewGeneration += 1
                    configureNativeLiveViewSession(_uiState.value.nativeLiveViewSession, _uiState.value.liveViewFrameRateFps)
                    startLiveViewLoopIfNeeded()
                }
            }
        }
    }

    fun refreshMedia() {
        val initialState = _uiState.value
        if (!initialState.connected || initialState.previewMode || initialState.mediaLibraryLoading ||
            initialState.isBusy(CameraOperation.MEDIA)
        ) return
        val scope = initialState.mediaLibraryScope
        // A completed gallery read may recover only the review that owned it at admission.
        val reviewAttempt = pendingCaptureReview
        val reviewGeneration = captureReviewGeneration
        val sessionGeneration = cameraSessionGeneration
        val connection = initialState.info
        val generation = ++mediaLibraryGeneration
        cancelMediaThumbnailLoads()
        _uiState.value.mediaStreamSource?.close()
        _uiState.update {
            it.copy(
                mediaThumbnailLoadingIds = emptySet(),
                mediaPreviewItem = null,
                mediaPreviewBytes = null,
                mediaPreviewLoading = false,
                mediaStreamSource = null,
                mediaLibraryLoading = true,
                mediaLibraryLoadStatus = MediaLibraryLoadStatus.LOADING,
            )
        }
        val job = viewModelScope.launch {
            try {
                val items = repository.listMedia(maximumItemsFor(scope)) { partialItems ->
                    if (generation == mediaLibraryGeneration) {
                        val batch = partialItems.toMediaLibraryBatch(scope)
                        applyMediaItems(batch.items, batch.hasMore)
                    }
                }
                coroutineContext.ensureActive()
                if (generation != mediaLibraryGeneration) return@launch
                val batch = items.toMediaLibraryBatch(scope)
                val capabilities = runCatching { repository.refreshCapabilities() }.getOrNull()
                coroutineContext.ensureActive()
                if (generation != mediaLibraryGeneration) return@launch
                _uiState.update {
                    it.withEventMediaItems(batch.items, preservePreview = true).copy(
                        mediaLibraryHasMore = batch.hasMore,
                        mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE,
                        capabilities = capabilities ?: it.capabilities,
                        lastDownloadedMediaName = null,
                        lastDownloadLocation = null,
                        lastUploadedMediaName = if (it.mediaUploadStatus == MediaUploadStatus.CONFIRMED) it.lastUploadedMediaName else null,
                        mediaUploadRefreshFailed = false,
                        lastDeletedMediaName = null,
                        lastMediaBatchResult = null,
                    )
                }
                refreshCapabilityEvidence()
                // Partial pages are gallery progress only. Do not let an older successful
                // read resolve a newer capture, read-only retry, or replacement connection.
                if (reviewAttempt != null && pendingCaptureReview === reviewAttempt &&
                    reviewGeneration == captureReviewGeneration && sessionGeneration == cameraSessionGeneration &&
                    _uiState.value.info === connection) {
                    refreshCaptureReview(batch.items)
                }
            } catch (exception: CancellationException) {
                if (generation == mediaLibraryGeneration) {
                    _uiState.update {
                        it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.CANCELLED)
                    }
                }
                throw exception
            } catch (exception: Exception) {
                exception.printStackTrace()
                if (generation == mediaLibraryGeneration && _uiState.value.transport == CameraTransport.DESKTOP_BRIDGE) {
                    mediaEventDemandGeneration++
                }
                _uiState.update {
                    if (generation != mediaLibraryGeneration) return@update it
                    it.copy(
                        mediaLibraryLoadStatus = MediaLibraryLoadStatus.FAILED,
                        error = formatException(exception),
                        errorOperation = CameraOperation.MEDIA,
                    )
                }
            } finally {
                if (generation == mediaLibraryGeneration) {
                    _uiState.update { it.copy(mediaLibraryLoading = false) }
                }
            }
        }
        mediaLibraryJob = job
        job.invokeOnCompletion {
            if (mediaLibraryJob === job) mediaLibraryJob = null
        }
    }

    fun openCaptureReview() {
        val state = _uiState.value
        if (!state.supports(CameraFeature.MEDIA_BROWSER)) return
        val item = state.captureReviewItem ?: return
        val needsRefresh = state.mediaItems.none { it.id == item.id }
        // A read already in flight may have snapshotted the card before this known capture.
        if (needsRefresh && state.mediaLibraryLoading) cancelMediaLibraryLoad()
        setUiMode(UiMode.MEDIA)
        // setUiMode starts an empty album; a populated but older album needs the same refresh.
        if (needsRefresh && !_uiState.value.mediaLibraryLoading) refreshMedia()
        if (item.isVisualMedia && (item.previewAvailable || item.isVideo)) openMediaPreview(item)
    }

    fun cancelMediaLibraryLoad() {
        if (!_uiState.value.mediaLibraryLoading) return
        invalidateMediaLibraryLoad(MediaLibraryLoadStatus.CANCELLED)
    }

    fun loadMediaThumbnail(item: CameraMediaItem) {
        if (!item.isVisualMedia) return
        val state = _uiState.value
        // An opaque ID can be reused by a later listing or metadata readback. An old tile
        // must neither touch the replacement's cache nor start a read for its predecessor.
        if (state.mediaItems.none { it.hasSameThumbnailSource(item) }) return
        state.mediaThumbnails[item.id]?.let {
            _uiState.update { current ->
                if (item.id !in current.mediaThumbnails ||
                    current.mediaItems.none { it.hasSameThumbnailSource(item) }) return@update current
                current.copy(mediaThumbnails = touchMediaCacheEntry(current.mediaThumbnails, item.id))
            }
            return
        }
        if (
            state.previewMode || state.mediaListingDefersThumbnails ||
            !state.supports(CameraFeature.MEDIA_THUMBNAIL) ||
            item.id in state.mediaThumbnailLoadingIds ||
            item.id in mediaThumbnailJobs
        ) return

        val generation = mediaThumbnailGeneration
        _uiState.update { it.copy(mediaThumbnailLoadingIds = it.mediaThumbnailLoadingIds + item.id) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val bitmap = mediaThumbnailSemaphore.withPermit { fetchMediaThumbnailBitmap(item) }
                if (
                    generation != mediaThumbnailGeneration ||
                    mediaThumbnailJobs[item.id] !== coroutineContext[Job]
                ) return@launch
                _uiState.update { current ->
                    if (current.mediaItems.none { it.hasSameThumbnailSource(item) }) return@update current
                    val retained = current.mediaThumbnails.entries
                        .asSequence()
                        .filterNot { it.key == item.id }
                        .toList()
                        .takeLast(MAX_MEDIA_THUMBNAIL_CACHE_ITEMS - 1)
                        .associateTo(linkedMapOf()) { it.toPair() }
                    current.copy(mediaThumbnails = retained + (item.id to bitmap))
                }
                refreshCapabilityEvidence()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Leaving the item retryable lets a later viewport entry recover from camera Wi-Fi timeouts.
            } finally {
                if (generation == mediaThumbnailGeneration && mediaThumbnailJobs[item.id] === coroutineContext[Job]) {
                    mediaThumbnailJobs.remove(item.id)
                    _uiState.update { current ->
                        current.copy(mediaThumbnailLoadingIds = current.mediaThumbnailLoadingIds - item.id)
                    }
                }
            }
        }
        mediaThumbnailJobs[item.id] = job
        job.start()
    }

    fun cancelMediaThumbnail(item: CameraMediaItem) {
        // A disposed old tile does not own a newer read under a reused ID.
        if (_uiState.value.mediaItems.any { it.id == item.id && !it.hasSameThumbnailSource(item) }) return
        mediaThumbnailJobs.remove(item.id)?.cancel()
        _uiState.update { it.copy(mediaThumbnailLoadingIds = it.mediaThumbnailLoadingIds - item.id) }
    }

    fun openMediaPreview(item: CameraMediaItem) {
        if (!item.isVisualMedia) return
        val state = _uiState.value
        val isVideo = item.isVideo
        if (state.previewMode) {
            state.mediaStreamSource?.close()
            _uiState.update {
                it.copy(
                    mediaPreviewItem = item,
                    mediaPreviewBytes = null,
                    mediaPreviewLoading = false,
                    mediaStreamSource = null,
                )
            }
            return
        }
        if (
            state.isBusy(CameraOperation.MEDIA) ||
            if (isVideo) !item.streamAvailable
            else !state.supports(CameraFeature.MEDIA_PREVIEW) || !item.previewAvailable
        ) return
        if (state.transport == CameraTransport.DESKTOP_BRIDGE && state.mediaLibraryLoading) {
            cancelMediaLibraryLoad()
        }
        val generation = cameraSessionGeneration
        val connection = state.info
        val cancellableImageRead = !isVideo && state.transport in setOf(
            CameraTransport.CCAPI_NETWORK, CameraTransport.DESKTOP_BRIDGE,
        )
        // Own admission before StateFlow observers can dismiss the new viewer.
        val read = if (cancellableImageRead) HttpMediaPreviewRead() else null
        if (read != null) httpMediaPreviewRead = read
        state.mediaStreamSource?.close()
        _uiState.update {
            it.copy(
                mediaPreviewItem = item,
                mediaPreviewBytes = null,
                mediaPreviewLoading = true,
                mediaStreamSource = null,
            )
        }
        if (read != null && (read.cancelled || httpMediaPreviewRead !== read ||
                generation != cameraSessionGeneration || _uiState.value.info !== connection)) {
            if (httpMediaPreviewRead === read) httpMediaPreviewRead = null
            return
        }
        launchCameraOperation(
            operation = CameraOperation.MEDIA,
            cancelMediaReads = false,
            onRegistered = { job ->
                if (read != null) {
                    read.job = job
                    job.invokeOnCompletion {
                        if (httpMediaPreviewRead === read) {
                            httpMediaPreviewRead = null
                            // Cancellation before lazy dispatch can skip execute's finally.
                            // The generic completion handler has already removed this job;
                            // a successor must retain its own MEDIA pending state.
                            if (generation == cameraSessionGeneration && _uiState.value.info === connection &&
                                cameraOperationJobs[CameraOperation.MEDIA] == null &&
                                _uiState.value.isBusy(CameraOperation.MEDIA)) {
                                cameraStateRevision += 1
                                _uiState.update { it.copy(pendingOperations = it.pendingOperations - CameraOperation.MEDIA) }
                            }
                        }
                    }
                    // MEDIA pending publication can also trigger dismissal before registration.
                    if (read.cancelled || httpMediaPreviewRead !== read ||
                        generation != cameraSessionGeneration || _uiState.value.info !== connection) job.cancel()
                }
            },
            onError = {
                _uiState.update { current ->
                    if (generation == cameraSessionGeneration && current.info === connection &&
                        current.mediaPreviewItem?.id == item.id) current.copy(mediaPreviewLoading = false) else current
                }
            },
        ) {
            val stream = if (isVideo) repository.openMediaStream(item) else null
            val preview = if (isVideo) null else try {
                repository.mediaPreview(item).also {
                    require(it.item.id == item.id && it.item.name == item.name && it.item.isPhoto) {
                        "Camera changed the media identity or type before preview decoding."
                    }
                    if (cancellableImageRead) coroutineContext.ensureActive()
                }
            } catch (exception: Exception) {
                // A cancelled HTTP read may surface IOException while unwinding.
                // Keep this suppression local to display reads, not safety commands.
                if (cancellableImageRead) coroutineContext.ensureActive()
                throw exception
            }
            _uiState.update { current ->
                if (generation == cameraSessionGeneration && current.info === connection &&
                    current.mediaPreviewItem?.id == item.id) {
                    current.copy(
                        mediaPreviewBytes = preview?.bytes,
                        mediaPreviewLoading = false,
                        mediaStreamSource = stream,
                    )
                } else {
                    stream?.close()
                    current
                }
            }
        }
    }

    fun closeMediaPreview() {
        val previewToCancel = httpMediaPreviewRead
        _uiState.value.mediaStreamSource?.close()
        _uiState.update {
            it.copy(
                mediaPreviewItem = null,
                mediaPreviewBytes = null,
                mediaPreviewLoading = false,
                mediaStreamSource = null,
            )
        }
        previewToCancel?.cancel()
    }

    fun loadMediaInfo(item: CameraMediaItem) {
        val state = _uiState.value
        if (state.previewMode || state.isBusy(CameraOperation.MEDIA) || !state.supports(CameraFeature.MEDIA_BROWSER)) return
        runCamera(CameraOperation.MEDIA) {
            val updated = repository.mediaInfo(item)
            if (_uiState.value.mediaItems.any { it.id == updated.id && !it.hasSameThumbnailSource(updated) }) {
                // Remove ownership before cancellation: a late old finalizer cannot clear a
                // successor's loading state, even when the opaque ID is reused again.
                mediaThumbnailJobs.remove(updated.id)?.cancel()
            }
            _uiState.update { current -> current.withUpdatedMedia(updated) }
        }
    }

    fun previewAdjacentMedia(items: List<CameraMediaItem>, direction: Int) {
        if (direction == 0 || _uiState.value.isBusy(CameraOperation.MEDIA)) return
        val currentId = _uiState.value.mediaPreviewItem?.id ?: return
        val index = items.indexOfFirst { it.id == currentId }
        if (index < 0) return
        val next = items.getOrNull(index + direction) ?: return
        // Validate the target before replacing the viewer or closing its active stream.
        openMediaPreview(next)
    }

    fun setMediaProtection(item: CameraMediaItem, enabled: Boolean) = updateMediaMetadata(
        item, CameraFeature.MEDIA_PROTECT,
        MediaMetadataTarget(MediaMetadataField.PROTECTION, if (enabled) 1 else 0),
        previewUpdate = { it.copy(protected = enabled) },
    ) { repository.setMediaProtection(it, enabled) }

    fun setMediaArchived(item: CameraMediaItem, enabled: Boolean) = updateMediaMetadata(
        item, CameraFeature.MEDIA_ARCHIVE,
        MediaMetadataTarget(MediaMetadataField.ARCHIVE, if (enabled) 1 else 0),
        previewUpdate = { it.copy(archived = enabled) },
    ) { repository.setMediaArchived(it, enabled) }

    fun setMediaRating(item: CameraMediaItem, rating: Int) {
        if (rating !in 0..5) return
        updateMediaMetadata(item, CameraFeature.MEDIA_RATING,
            MediaMetadataTarget(MediaMetadataField.RATING, rating),
            previewUpdate = { it.copy(rating = rating) },
        ) { repository.setMediaRating(it, rating) }
    }

    fun setMediaRotation(item: CameraMediaItem, degrees: Int) {
        if (degrees !in setOf(0, 90, 180, 270)) return
        updateMediaMetadata(item, CameraFeature.MEDIA_ROTATE,
            MediaMetadataTarget(MediaMetadataField.ROTATION, degrees),
            previewUpdate = { it.copy(rotationDegrees = degrees) },
        ) { repository.setMediaRotation(it, degrees) }
    }

    fun setMediaProtectionBatch(items: List<CameraMediaItem>, enabled: Boolean) = updateMediaMetadataBatch(
        items, CameraFeature.MEDIA_PROTECT,
        if (enabled) MediaBatchOperation.PROTECT else MediaBatchOperation.UNPROTECT,
        MediaMetadataTarget(MediaMetadataField.PROTECTION, if (enabled) 1 else 0),
        previewUpdate = { it.copy(protected = enabled) },
    ) { repository.setMediaProtection(it, enabled) }

    fun setMediaArchivedBatch(items: List<CameraMediaItem>, enabled: Boolean) = updateMediaMetadataBatch(
        items, CameraFeature.MEDIA_ARCHIVE,
        if (enabled) MediaBatchOperation.ARCHIVE else MediaBatchOperation.UNARCHIVE,
        MediaMetadataTarget(MediaMetadataField.ARCHIVE, if (enabled) 1 else 0),
        previewUpdate = { it.copy(archived = enabled) },
    ) { repository.setMediaArchived(it, enabled) }

    fun setMediaRatingBatch(items: List<CameraMediaItem>, rating: Int) {
        if (rating !in 0..5) return
        updateMediaMetadataBatch(items, CameraFeature.MEDIA_RATING, MediaBatchOperation.RATE,
            MediaMetadataTarget(MediaMetadataField.RATING, rating),
            previewUpdate = { it.copy(rating = rating) },
        ) { repository.setMediaRating(it, rating) }
    }

    fun setMediaRotationBatch(items: List<CameraMediaItem>, degrees: Int) {
        if (degrees !in setOf(0, 90, 180, 270)) return
        updateMediaMetadataBatch(items, CameraFeature.MEDIA_ROTATE, MediaBatchOperation.ROTATE,
            MediaMetadataTarget(MediaMetadataField.ROTATION, degrees),
            previewUpdate = { it.copy(rotationDegrees = degrees) },
        ) { repository.setMediaRotation(it, degrees) }
    }

    private fun updateMediaMetadata(
        item: CameraMediaItem,
        feature: CameraFeature,
        target: MediaMetadataTarget,
        previewUpdate: (CameraMediaItem) -> CameraMediaItem,
        update: suspend (CameraMediaItem) -> CameraMediaItem,
    ) {
        fun currentItem(current: CameraUiState): CameraMediaItem = currentMediaMetadataItem(
            item, current.mediaItems, current.mediaPreviewItem, current.captureReviewItem)
        fun eligible(current: CameraUiState, selected: CameraMediaItem): Boolean =
            mediaMetadataPreflight(target.field, current.supports(feature), item.id, selected.id,
                selected.archived, selected.metadataWritable(target.field)) == null
        val state = _uiState.value
        if (state.isBusy(CameraOperation.MEDIA) || !eligible(state, currentItem(state))) return
        if (state.previewMode) {
            _uiState.update { current ->
                val selected = currentItem(current)
                if (current.info !== state.info || !eligible(current, selected)) current
                else current.withVerifiedMediaMetadata(target, previewUpdate(selected))
            }
            return
        }
        val generation = cameraSessionGeneration
        runCamera(CameraOperation.MEDIA) {
            coroutineContext.ensureActive()
            if (generation != cameraSessionGeneration || _uiState.value.info !== state.info) return@runCamera
            val current = _uiState.value
            val selected = currentItem(current)
            if (!eligible(current, selected)) return@runCamera
            val updated = update(selected)
            coroutineContext.ensureActive()
            check(target.matches(item.id, updated.metadataReadback())) { "The requested media change is unconfirmed." }
            _uiState.update { current ->
                if (generation == cameraSessionGeneration && current.info === state.info) current.withVerifiedMediaMetadata(target, updated)
                else current
            }
        }
    }

    fun cancelMediaMetadataBatch(owner: MediaMetadataOwner? = null) {
        val state = _uiState.value
        if (owner != null && !owner.matches(state.info, state.mediaSessionGeneration)) return
        if (state.mediaMetadataBatch?.running == true) cameraOperationJobs[CameraOperation.MEDIA]?.cancel()
    }

    private fun updateMediaMetadataBatch(
        items: List<CameraMediaItem>,
        feature: CameraFeature,
        operation: MediaBatchOperation,
        target: MediaMetadataTarget,
        previewUpdate: (CameraMediaItem) -> CameraMediaItem,
        update: suspend (CameraMediaItem) -> CameraMediaItem,
    ) {
        val state = _uiState.value
        if (items is MediaMetadataSelection && !items.owner.matches(state.info, state.mediaSessionGeneration)) return
        val selectedItems = items.distinctBy(CameraMediaItem::id).toList()
        if (selectedItems.isEmpty() || state.isBusy(CameraOperation.MEDIA) || !state.supports(feature)) return
        if (selectedItems.size > MAX_MEDIA_METADATA_BATCH_ITEMS) {
            _uiState.update { it.copy(error = "Select at most $MAX_MEDIA_METADATA_BATCH_ITEMS items for one metadata batch.",
                errorOperation = CameraOperation.MEDIA) }
            return
        }
        val generation = cameraSessionGeneration
        val requests = selectedItems.map { MediaMetadataRequest(it.id, it.name, target) }
        fun isCurrent(): Boolean = generation == cameraSessionGeneration && _uiState.value.info === state.info
        _uiState.update { it.copy(lastMediaBatchResult = null, mediaMetadataBatch = MediaMetadataBatchResult(requests.map { MediaMetadataRow(it) }, running = true)) }
        runCamera(CameraOperation.MEDIA, afterFinally = {
            _uiState.update { current ->
                if (isCurrent()) current.copy(mediaMetadataBatch = current.mediaMetadataBatch?.copy(running = false)) else current
            }
        }) {
            executeMediaMetadataBatch(
                requests = requests,
                isCurrent = ::isCurrent,
                preflight = { request ->
                    val current = _uiState.value
                    val loaded = current.mediaItems.firstOrNull { it.id == request.id }
                    mediaMetadataPreflight(target.field, current.supports(feature), request.id, loaded?.id,
                        loaded?.archived, loaded?.metadataWritable(target.field))
                },
                action = { request ->
                    val item = _uiState.value.mediaItems.first { it.id == request.id }
                    if (state.previewMode) previewUpdate(item) else update(item)
                },
                readback = CameraMediaItem::metadataReadback,
                onConfirmed = { updated ->
                    _uiState.update { current -> if (isCurrent()) current.withVerifiedMediaMetadata(target, updated) else current }
                },
                onChanged = { receipt ->
                    _uiState.update { current ->
                        if (!isCurrent()) current else current.copy(
                            mediaMetadataBatch = receipt,
                            mediaBatchProgress = null,
                            lastMediaBatchResult = if (receipt.running) null else MediaBatchResult(
                                operation, receipt.rows.size, receipt.confirmed,
                                receipt.rows.filter { it.outcome == MediaMetadataOutcome.FAILED_NOT_SENT }.map { it.request.name }),
                        )
                    }
                },
            )
        }
    }

    internal fun beginMediaPicker(
        kind: CameraMediaPickerKind,
        items: List<CameraMediaItem> = emptyList(),
    ): String? {
        val state = _uiState.value
        if (!canStartMediaPickerTransfer(state, kind)) return null
        if (kind != CameraMediaPickerKind.UPLOAD && items.isEmpty()) return null
        val id = mediaPickerRequests.begin(kind, cameraSessionGeneration, requireNotNull(state.info), items)
        if (id != null && kind != CameraMediaPickerKind.UPLOAD) {
            beginMediaSave(state, items, id, MediaSaveFeedback.SelectingDestination)
        }
        return id
    }

    internal fun completeMediaPicker(context: Context, kind: CameraMediaPickerKind, id: String?, uri: Uri?) {
        val continuation = continuationPicker?.takeIf { it.id == id && kind == CameraMediaPickerKind.DOWNLOAD_FOLDER }
        if (continuation != null) continuationPicker = null
        if (uri == null) {
            if (mediaPickerRequests.cancel(kind, id)) {
                mediaSaveRequest?.takeIf { it.pickerId == id }?.let { request ->
                    finishPendingMediaSave(request, MediaSaveFeedback.Cancelled)
                }
            }
            return
        }
        val state = _uiState.value
        when (val result = mediaPickerRequests.consume(
            kind, id, cameraSessionGeneration, state.info.takeIf { state.connected && !state.previewMode },
        )) {
            CameraMediaPickerRequests.Result.Ignored -> Unit
            CameraMediaPickerRequests.Result.Expired -> {
                if (continuation == null || mediaSaveRequest === continuation.request) {
                    reportMediaPickerError(context, R.string.media_picker_session_expired)
                }
            }
            is CameraMediaPickerRequests.Result.Ready -> {
                if (continuation != null) {
                    // Returning an old picker may retire its own slot, never a newer save UI.
                    if (mediaSaveRequest !== continuation.request) return
                    val draft = continuation.draft
                    if (draft == null || !ownsDownloadContinuation(draft) || !draft.canConfirm ||
                        !canStartMediaPickerTransfer(state, kind) || foregroundImportOwner != null ||
                        draft.selectedItems.any { selected -> state.mediaItems.none { it == selected } }) {
                        finishPendingMediaSave(continuation.request, MediaSaveFeedback.Cancelled)
                        reportMediaPickerError(context, R.string.media_continuation_expired)
                        return
                    }
                    downloadMediaBatchInternal(context, draft.selectedItems, uri, draft)
                    return
                }
                if (!canStartMediaPickerTransfer(state, kind)) {
                    mediaSaveRequest?.takeIf { it.pickerId == id }?.let { request ->
                        finishPendingMediaSave(request, MediaSaveFeedback.Failed(context.getString(R.string.media_picker_transfer_unavailable)))
                    }
                    reportMediaPickerError(context, R.string.media_picker_transfer_unavailable)
                    return
                }
                // Validate and consume before any source read, output open, document creation,
                // or camera command. Rejected results never enter transfer cleanup/delete paths.
                when (kind) {
                    CameraMediaPickerKind.DOWNLOAD_DOCUMENT -> downloadMedia(context, result.items.single(), uri)
                    CameraMediaPickerKind.DOWNLOAD_FOLDER -> downloadMediaBatch(context, result.items, uri)
                    CameraMediaPickerKind.UPLOAD -> uploadMedia(context, uri)
                }
            }
        }
    }

    internal fun failMediaPickerLaunch(context: Context, kind: CameraMediaPickerKind, id: String) {
        val continuation = continuationPicker?.takeIf { it.id == id }
        if (continuationPicker?.id == id) continuationPicker = null
        if (mediaPickerRequests.cancel(kind, id)) {
            mediaSaveRequest?.takeIf { it.pickerId == id }?.let { request ->
                finishPendingMediaSave(request, MediaSaveFeedback.Failed(context.getString(R.string.media_picker_open_failed)))
            }
            if (continuation == null || mediaSaveRequest === continuation.request) {
                reportMediaPickerError(context, R.string.media_picker_open_failed)
            }
        }
    }

    private fun canStartMediaPickerTransfer(state: CameraUiState, kind: CameraMediaPickerKind): Boolean =
        state.connected && !state.previewMode && !state.isBusy(CameraOperation.MEDIA) &&
            mediaDownloadJob == null && mediaUploadJob == null && state.supports(
                if (kind == CameraMediaPickerKind.UPLOAD) CameraFeature.MEDIA_UPLOAD else CameraFeature.MEDIA_DOWNLOAD,
            )

    private fun reportMediaPickerError(context: Context, @StringRes message: Int) {
        _uiState.update { it.copy(error = context.getString(message), errorOperation = CameraOperation.MEDIA) }
    }

    private fun beginMediaSave(
        state: CameraUiState,
        items: List<CameraMediaItem>,
        pickerId: String? = null,
        initial: MediaSaveFeedback = MediaSaveFeedback.Queued,
    ): MediaSaveRequest {
        cancelDownloadContinuation()
        val request = MediaSaveRequest(cameraSessionGeneration, requireNotNull(state.info), pickerId)
        mediaSaveRequest = request
        updateMediaSave(request) {
            it.copy(
                mediaSaveFeedback = items.associate { item -> item.id to initial },
                mediaWorklistUnavailable = false,
                lastDownloadedMediaName = null,
                lastDownloadLocation = null,
                lastMediaBatchResult = null,
            )
        }
        return request
    }

    private fun updateMediaSave(request: MediaSaveRequest, allowRetired: Boolean = false, update: (CameraUiState) -> CameraUiState) {
        _uiState.update { current ->
            if (request.owns(mediaSaveRequest, cameraSessionGeneration, current.info) &&
                (allowRetired || request !== retiredContinuationRequest)) update(current) else current
        }
    }

    private fun publishMediaSave(request: MediaSaveRequest, item: CameraMediaItem, feedback: MediaSaveFeedback) {
        updateMediaSave(request, allowRetired = feedback is MediaSaveFeedback.IncompleteFile) {
            it.copy(mediaSaveFeedback = it.mediaSaveFeedback + (item.id to feedback))
        }
    }

    private fun publishMediaSaveProgress(request: MediaSaveRequest, item: CameraMediaItem, progress: CameraMediaTransferProgress) {
        updateMediaSave(request) {
            it.copy(
                mediaSaveFeedback = it.mediaSaveFeedback + (item.id to MediaSaveFeedback.Saving(progress)),
                activeMediaDownloadName = item.name,
                mediaDownloadProgress = progress,
            )
        }
    }

    private fun finishPendingMediaSave(request: MediaSaveRequest, feedback: MediaSaveFeedback) {
        updateMediaSave(request, allowRetired = true) { state ->
            state.copy(mediaSaveFeedback = state.mediaSaveFeedback.mapValues { (_, previous) ->
                if (previous.isPending) feedback else previous
            })
        }
    }

    private fun trackMediaSaveJob(
        request: MediaSaveRequest,
        job: Job?,
        admittedDocumentOwner: CameraMediaItem? = null,
        admittedDocumentOutput: MediaOutputFinalization? = null,
    ) {
        mediaDownloadJob = job
        job?.invokeOnCompletion { cause ->
            // Includes cancellation while joining an earlier listing, before the transfer block runs.
            if (cause is CancellationException) {
                // Only the picker-created single output predates the transfer body. A batch
                // marks its active output in that body; queued items never owned a document.
                if (admittedDocumentOwner != null && admittedDocumentOutput?.cleanupUnconfirmed == true) {
                    publishMediaSave(request, admittedDocumentOwner, MediaSaveFeedback.IncompleteFile(true))
                }
                finishPendingMediaSave(request, MediaSaveFeedback.Cancelled)
            }
            updateMediaSave(request, allowRetired = true) {
                it.copy(mediaBatchProgress = null, activeMediaDownloadName = null, mediaDownloadProgress = null)
            }
            // Keep terminal feedback, but retire the writer before another workflow can start.
            if (mediaSaveRequest === request) mediaSaveRequest = null
            if (continuationSaveRequest === request) continuationSaveRequest = null
            if (retiredContinuationRequest === request) retiredContinuationRequest = null
            if (mediaDownloadJob === job) mediaDownloadJob = null
        }
    }

    fun enableForegroundJpegImport(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_FOREGROUND_IMPORT_CLEANUP_WARNING, false)) {
            _uiState.update { it.copy(foregroundImportCleanupUnconfirmed = true) }
            return
        }
        val state = _uiState.value
        if (handoffOwner.state.value.busy || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !state.canEnableForegroundJpegImport(true)) return
        val connection = state.info?.copy() ?: return
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        initializeDownloadHistory(appContext)
        val history = downloadHistoryStore
        val output = ForegroundJpegImportOutput { originalItem, receipt, download ->
            val item = originalItem.copy()
            withDownloadHistoryReceipt(history, history?.captureRequest(), item.name, DownloadHistoryDestination.GALLERY) { completed ->
                CameraMediaGalleryStore(resolver).save(
                    connection.model, item,
                    onFinalized = {
                        // Publication owns success before any receipt/observer can fail.
                        receipt.markPublished()
                        completed()
                    },
                    onCleanupFailure = {
                        receipt.markCleanupUnconfirmed()
                        _uiState.update { it.copy(foregroundImportCleanupUnconfirmed = true) }
                        // Only an acknowledgement bit persists; no path, URI or enablement does.
                        preferences.edit().putBoolean(KEY_FOREGROUND_IMPORT_CLEANUP_WARNING, true).apply()
                    },
                    onPublished = { evidence -> recordPublishedJpeg(connection, item, evidence) },
                    onPublicationObserverFailure = deliveredJpegStore::markRegistrationUnavailable,
                    requireAutomaticJpeg = true,
                    download = download,
                )
            }
        }
        startForegroundJpegImport(output, cameraGalleryPath(connection.model))
    }

    internal fun startForegroundJpegImport(
        output: ForegroundJpegImportOutput,
        location: String = "Synthetic Gallery",
        platformSupported: Boolean = true,
        limits: ForegroundImportLimits = ForegroundImportLimits(),
        elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
        pollMillis: Long = 5_000L,
    ): Boolean {
        val state = _uiState.value
        if (handoffOwner.state.value.busy || foregroundImportOwner != null || !appInForeground ||
            !state.canEnableForegroundJpegImport(platformSupported)) return false
        val owner = ForegroundImportOwner(cameraSessionGeneration, requireNotNull(state.info), output, location)
        val io = object : ForegroundJpegImportIo {
            override suspend fun awaitAvailable() = awaitForegroundImportIdle(owner)
            override suspend fun inventory(maximumItems: Int) = withForegroundImportOperation(owner) {
                repository.listMediaIdentities(maximumItems)
            }
            override suspend fun freshInfo(item: CameraMediaItem) = withForegroundImportOperation(owner) {
                repository.mediaInfo(item.copy(sizeBytes = null, contentType = null))
            }
            override suspend fun saveOriginal(item: CameraMediaItem, receipt: ForegroundImportReceipt) {
                withForegroundImportOperation(owner, saving = true) {
                    val request = beginMediaSave(_uiState.value, listOf(item))
                    owner.saveRequest = request
                    publishMediaSaveProgress(request, item, CameraMediaTransferProgress(0L, item.sizeBytes))
                    var failure: Throwable? = null
                    try {
                        owner.output.save(item, receipt) { destination ->
                            ensureForegroundImportOwner(owner)
                            repository.downloadMediaSingleAttempt(item, destination) { progress ->
                                publishMediaSaveProgress(request, item, progress)
                            }
                        }
                    } catch (cause: Throwable) {
                        failure = cause
                        throw cause
                    } finally {
                        if (!receipt.cleanupConfirmed) {
                            _uiState.update { it.copy(foregroundImportCleanupUnconfirmed = true) }
                        }
                        val feedback = when {
                            receipt.published -> MediaSaveFeedback.Saved(owner.location)
                            failure is CancellationException -> MediaSaveFeedback.Cancelled
                            else -> MediaSaveFeedback.Failed(CameraAppError.AUTOMATIC_JPEG_NOT_PUBLISHED.messageToken)
                        }
                        publishMediaSave(request, item, feedback)
                        if (receipt.published) updateMediaSave(request) {
                            it.copy(lastDownloadedMediaName = item.name, lastDownloadLocation = owner.location)
                        }
                    }
                }
            }
        }
        owner.runner = ForegroundJpegImportRunner(io, elapsedMillis, { status ->
            if (ownsForegroundImport(owner)) _uiState.update { it.copy(foregroundJpegImport = status) }
        }, limits, idlePollMillis = pollMillis)
        foregroundImportOwner = owner
        _uiState.update { it.copy(foregroundImportOwnerActive = true) }
        owner.job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            owner.started = true
            try {
                owner.runner.run()
            } finally {
                withContext(NonCancellable) {
                    owner.operation?.cancelAndJoin()
                    if (foregroundImportOwner === owner) {
                        foregroundImportOwner = null
                        _uiState.update { it.copy(foregroundImportOwnerActive = false) }
                        if (owner.generation == cameraSessionGeneration && _uiState.value.info === owner.connection) {
                            _uiState.update { it.copy(foregroundJpegImport = owner.runner.status) }
                        }
                    }
                }
            }
        }
        owner.job?.invokeOnCompletion {
            if (!owner.started) {
                owner.runner.finishBeforeStart()
                if (foregroundImportOwner === owner) {
                    foregroundImportOwner = null
                    _uiState.update { it.copy(foregroundImportOwnerActive = false) }
                }
            }
        }
        owner.job?.start()
        return true
    }

    fun stopForegroundJpegImport() { stopForegroundImport(ForegroundImportStopReason.USER) }

    fun acknowledgeForegroundImportCleanupWarning(context: Context) {
        if (foregroundImportOwner != null) return
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FOREGROUND_IMPORT_CLEANUP_WARNING, false).apply()
        _uiState.update { it.copy(foregroundImportCleanupUnconfirmed = false) }
    }

    private fun stopForegroundImport(reason: ForegroundImportStopReason): Job? {
        val owner = foregroundImportOwner ?: return null
        owner.runner.requestStop(reason)
        owner.operation?.cancel()
        owner.job?.cancel()
        return owner.job
    }

    private fun ownsForegroundImport(owner: ForegroundImportOwner): Boolean =
        foregroundImportOwner === owner && owner.generation == cameraSessionGeneration &&
            _uiState.value.info === owner.connection

    private suspend fun ensureForegroundImportOwner(owner: ForegroundImportOwner) {
        coroutineContext.ensureActive()
        if (!ownsForegroundImport(owner)) throw CancellationException("Foreground import connection changed.")
        if (owner.runner.status.stopReason != null) throw CancellationException("Foreground import is stopping.")
        if (!appInForeground) {
            owner.runner.requestStop(ForegroundImportStopReason.BACKGROUND)
            throw CancellationException("Foreground import left the foreground.")
        }
        val state = _uiState.value
        if (!state.connected || state.previewMode || state.transport != CameraTransport.CCAPI_NETWORK ||
            !state.supports(CameraFeature.MEDIA_BROWSER) || !state.supports(CameraFeature.MEDIA_DOWNLOAD)) {
            owner.runner.requestStop(ForegroundImportStopReason.UNSUPPORTED)
            throw CancellationException("Foreground import is no longer available.")
        }
    }

    private suspend fun awaitForegroundImportIdle(owner: ForegroundImportOwner) {
        while (true) {
            ensureForegroundImportOwner(owner)
            if (_uiState.value.foregroundImportCameraIdle() && mediaDownloadJob == null && mediaUploadJob == null &&
                mediaLibraryJob?.isActive != true && eventMediaJob?.isActive != true && captureReviewJob?.isActive != true) return
            delay(50L)
        }
    }

    /** Participates in CONNECT/disconnect joins without clearing unrelated user-facing errors. */
    private suspend fun <T> withForegroundImportOperation(
        owner: ForegroundImportOwner,
        saving: Boolean = false,
        block: suspend () -> T,
    ): T {
        awaitForegroundImportIdle(owner)
        ensureForegroundImportOwner(owner)
        cameraStateRevision += 1
        _uiState.update { it.copy(pendingOperations = it.pendingOperations + CameraOperation.MEDIA) }
        val result = CompletableDeferred<Result<T>>()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                ensureForegroundImportOwner(owner)
                val value = block()
                ensureForegroundImportOwner(owner)
                refreshCapabilityEvidence()
                result.complete(Result.success(value))
            } catch (cancelled: CancellationException) {
                result.complete(Result.failure(cancelled))
                throw cancelled
            } catch (failure: Throwable) {
                result.complete(Result.failure(failure))
            } finally {
                if (ownsForegroundImport(owner)) {
                    cameraStateRevision += 1
                    _uiState.update { it.copy(pendingOperations = it.pendingOperations - CameraOperation.MEDIA) }
                }
            }
        }
        owner.operation = job
        cameraOperationJobs[CameraOperation.MEDIA] = job
        if (saving) mediaDownloadJob = job
        job.start()
        try {
            job.join()
            coroutineContext.ensureActive()
            if (!result.isCompleted) throw CancellationException("Foreground import operation did not start.")
            return result.await().getOrThrow()
        } finally {
            withContext(NonCancellable) {
                if (!job.isCompleted) job.cancelAndJoin()
                // A cancelled LAZY operation may never enter its own finally block.
                if (ownsForegroundImport(owner) && owner.operation === job) {
                    _uiState.update { it.copy(pendingOperations = it.pendingOperations - CameraOperation.MEDIA) }
                }
                if (owner.operation === job) owner.operation = null
                if (cameraOperationJobs[CameraOperation.MEDIA] === job) cameraOperationJobs.remove(CameraOperation.MEDIA)
                if (mediaDownloadJob === job) mediaDownloadJob = null
                owner.saveRequest?.let { request ->
                    updateMediaSave(request) { it.copy(activeMediaDownloadName = null, mediaDownloadProgress = null) }
                    if (mediaSaveRequest === request) mediaSaveRequest = null
                    owner.saveRequest = null
                }
            }
        }
    }

    fun downloadMedia(context: Context, item: CameraMediaItem, destination: Uri) {
        val state = _uiState.value
        if (state.info == null || state.previewMode || state.isBusy(CameraOperation.MEDIA) || mediaDownloadJob != null) return
        val resolver = context.applicationContext.contentResolver
        initializeDownloadHistory(context)
        val history = downloadHistoryStore
        val historyRequest = history?.captureRequest()
        val request = beginMediaSave(state, listOf(item))
        val location = context.getString(R.string.media_save_selected_document)
        val output = MediaOutputFinalization(cleanupIncomplete = { deleteIncompleteMediaDocument(resolver, destination) })
        val download = AdmittedMediaDownload(output, history, historyRequest, item.name, DownloadHistoryDestination.DOCUMENT)
        launchCameraOperation(
            CameraOperation.MEDIA,
            onError = {
                finishPendingMediaSave(request, if (it.hasUnconfirmedMediaCleanup()) MediaSaveFeedback.IncompleteFile(false)
                    else MediaSaveFeedback.Failed(formatException(it)))
            },
            admittedMediaDownload = download,
            onRegistered = { trackMediaSaveJob(request, it, admittedDocumentOwner = item, admittedDocumentOutput = output) },
        ) {
            publishMediaSaveProgress(request, item, CameraMediaTransferProgress(0L, item.sizeBytes))
            withContext(Dispatchers.IO) {
                val rawOutput = resolver.openOutputStream(destination, "w")
                    ?: throw CameraAppException(CameraAppError.DOWNLOAD_DESTINATION_UNAVAILABLE)
                BufferedOutputStream(rawOutput).use { stream ->
                    repository.downloadMedia(item, stream) { progress -> publishMediaSaveProgress(request, item, progress) }
                }
                // Successful close is the SAF checkpoint; no suspension before recording it.
                download.confirm {
                    publishMediaSave(request, item, MediaSaveFeedback.Saved(location))
                    updateMediaSave(request) { it.copy(lastDownloadedMediaName = item.name) }
                }
            }
        }
    }

    fun downloadMediaBatch(context: Context, items: List<CameraMediaItem>, destinationTree: Uri? = null) {
        downloadMediaBatchInternal(context, items, destinationTree)
    }

    private fun downloadMediaBatchInternal(
        context: Context,
        items: List<CameraMediaItem>,
        destinationTree: Uri? = null,
        continuation: DownloadContinuationDraft? = null,
    ) {
        val state = _uiState.value
        val selectedItems = items.distinctBy(CameraMediaItem::id).map { it.copy() }
        if (
            selectedItems.isEmpty() || state.info == null ||
            state.previewMode ||
            state.isBusy(CameraOperation.MEDIA) ||
            !state.supports(CameraFeature.MEDIA_DOWNLOAD) ||
            (destinationTree == null && !selectedItems.all(::canSaveMediaToGallery)) ||
            mediaDownloadJob != null
        ) return
        val camera = state.info.copy()
        val resolver = context.applicationContext.contentResolver
        initializeDownloadHistory(context)
        val history = downloadHistoryStore
        // Clear invalidates this entire admitted batch, including items not yet started.
        val historyRequest = continuation?.historyRequest ?: history?.captureRequest()
        val request = beginMediaSave(state, selectedItems)
        if (continuation != null) continuationSaveRequest = request
        val location = if (destinationTree == null) cameraGalleryPath(state.info.model)
            else context.getString(R.string.media_save_selected_folder)
        val historyDestination = if (destinationTree == null) DownloadHistoryDestination.GALLERY else DownloadHistoryDestination.FOLDER
        val job = launchCameraOperation(
            CameraOperation.MEDIA,
            onError = { finishPendingMediaSave(request, if (it is MediaWorklistAdmissionException)
                MediaSaveFeedback.NotStarted else MediaSaveFeedback.Failed(formatException(it))) },
        ) {
            val planned = PlannedMediaDownloadBatch(
                history, historyRequest, selectedItems.map(CameraMediaItem::name), historyDestination,
                isCurrent = { request !== retiredContinuationRequest && request.owns(mediaSaveRequest, cameraSessionGeneration, _uiState.value.info) },
                admissionFailedMessage = context.getString(R.string.media_worklist_admission_failed),
                admissionUnknownMessage = context.getString(R.string.media_worklist_admission_unknown),
                continuation = continuation?.selections,
            )
            try {
                val recorded = planned.prepare()
                updateMediaSave(request) { it.copy(mediaWorklistUnavailable = !recorded) }
                val result = executeMediaBatch(
                    items = selectedItems,
                    operation = MediaBatchOperation.DOWNLOAD,
                    onProgress = { progress ->
                        updateMediaSave(request) { it.copy(mediaBatchProgress = progress) }
                    },
                ) { item ->
                    planned.transfer(selectedItems.indexOfFirst { it.id == item.id }) { completed ->
                        publishMediaSaveProgress(request, item, CameraMediaTransferProgress(0L, item.sizeBytes))
                        val onFinalized = {
                            completed()
                            publishMediaSave(request, item, MediaSaveFeedback.Saved(location))
                            if (destinationTree == null) updateMediaSave(request) { it.copy(lastDownloadLocation = location) }
                        }
                        try {
                            retryMediaRead {
                                if (destinationTree == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    CameraMediaGalleryStore(resolver).save(
                                        camera.model, item, onFinalized,
                                        onPublished = { evidence -> recordPublishedJpeg(camera, item, evidence) },
                                        onPublicationObserverFailure = deliveredJpegStore::markRegistrationUnavailable,
                                    ) { output ->
                                        repository.downloadMedia(item, output) { progress -> publishMediaSaveProgress(request, item, progress) }
                                    }
                                } else {
                                    var destination: Uri? = null
                                    withMediaOutputFinalization(
                                        cleanupIncomplete = {
                                            destination?.let {
                                                deleteIncompleteMediaDocument(resolver, it)
                                            }
                                        },
                                        onFinalized = onFinalized,
                                    ) { finalized ->
                                        withContext(Dispatchers.IO) {
                                            destination = createMediaDocument(resolver, requireNotNull(destinationTree), item)
                                            val rawOutput = resolver.openOutputStream(requireNotNull(destination), "w")
                                                ?: throw CameraAppException(CameraAppError.DOWNLOAD_DESTINATION_UNAVAILABLE)
                                            BufferedOutputStream(rawOutput).use { output ->
                                                repository.downloadMedia(item, output) { progress -> publishMediaSaveProgress(request, item, progress) }
                                            }
                                            finalized.confirm()
                                        }
                                    }
                                }
                            }
                        } catch (exception: CancellationException) {
                            if (exception.hasUnconfirmedMediaCleanup()) publishMediaSave(request, item, MediaSaveFeedback.IncompleteFile(true))
                            throw exception
                        } catch (exception: Exception) {
                            publishMediaSave(request, item, if (exception.hasUnconfirmedMediaCleanup()) MediaSaveFeedback.IncompleteFile(false)
                                else MediaSaveFeedback.Failed(formatException(exception)))
                            throw exception
                        }
                    }
                }
                if (result.notStartedItems > 0) finishPendingMediaSave(request, MediaSaveFeedback.NotStarted)
                updateMediaSave(request) { it.copy(lastMediaBatchResult = result) }
            } catch (failure: Exception) {
                if (continuation != null && (request === retiredContinuationRequest ||
                    !request.owns(mediaSaveRequest, cameraSessionGeneration, _uiState.value.info))) {
                    throw CancellationException("Continuation owner retired.").apply { initCause(failure) }
                }
                throw failure
            } finally {
                planned.release()
            }
        }
        trackMediaSaveJob(request, job)
    }

    private fun recordPublishedJpeg(camera: CameraInfo, item: CameraMediaItem, evidence: PublishedGalleryOriginal) {
        // A completed Gallery publication survives disconnect and observer/history failures.
        try { deliveredJpegStore.recordPublished(camera, item, evidence) }
        catch (_: Exception) { runCatching { deliveredJpegStore.markRegistrationUnavailable() } }
    }

    internal fun toggleSavedJpeg(id: DeliveredJpegId, selected: Boolean) = savedJpegController.toggle(id, selected)
    internal fun clearSavedJpegs() = savedJpegController.clear()
    internal fun cancelSavedJpegPreparation() = savedJpegController.cancel()
    internal fun retryCameraImportCleanup() { handoffOwner.retryCleanup() }

    internal fun sendSavedJpegs(context: Context, expectedSelection: Set<DeliveredJpegId>): Boolean =
        savedJpegController.send(expectedSelection, savedJpegBackend(context),
            automaticImportActive = foregroundImportOwner != null,
            sereinAvailable = SereinImportIntents.isAvailable(context.applicationContext, cameraImportTargetPackage))

    internal fun recheckSavedJpeg(context: Context, id: DeliveredJpegId): Boolean =
        savedJpegController.recheck(id, savedJpegBackend(context), automaticImportActive = foregroundImportOwner != null)

    private fun savedJpegBackend(context: Context): SavedJpegHandoffBackend<CameraImportHandoffSession> {
        val appContext = context.applicationContext
        val storage = CameraImportHandoffStorage(appContext)
        return object : SavedJpegHandoffBackend<CameraImportHandoffSession> {
            override fun reserve() = storage.reserveLocalSession()
            override suspend fun prepare(selected: List<DeliveredJpeg>, reservation: CameraImportStagingReservation,
                onItem: (Int, Int, String) -> Unit, onProgress: (CameraMediaTransferProgress) -> Unit,
            ) = storage.preparePublishedJpegs(selected, reservation, appContext.installedVersionName(), onItem, onProgress)
            override suspend fun cleanup(reservation: CameraImportStagingReservation): Boolean =
                withContext(Dispatchers.IO) { storage.cleanup(reservation) }
        }
    }

    fun openInSerein(context: Context, items: List<CameraMediaItem>) {
        val state = _uiState.value
        val selectedItems = items.distinctBy(CameraMediaItem::id).map { it.copy() }
        if (selectedItems.isEmpty() || state.previewMode || state.isBusy(CameraOperation.MEDIA) ||
            !state.supports(CameraFeature.MEDIA_DOWNLOAD) || mediaDownloadJob != null || foregroundImportOwner != null) return
        val appContext = context.applicationContext
        if (handoffOwner.state.value.busy) {
            _uiState.update { it.copy(error = appContext.getString(R.string.serein_handoff_busy), errorOperation = CameraOperation.MEDIA) }
            return
        }
        if (!SereinImportIntents.isAvailable(appContext, cameraImportTargetPackage)) {
            _uiState.update { it.copy(error = appContext.getString(R.string.serein_not_installed), errorOperation = CameraOperation.MEDIA) }
            return
        }
        val camera = state.info?.copy() ?: return
        val generation = cameraSessionGeneration
        val storage = CameraImportHandoffStorage(appContext)
        val reservation = storage.reserveLocalSession()
        val lease = handoffOwner.acquire(CameraImportHandoffOrigin.CAMERA, reservation, generation,
            cleanup = { withContext(Dispatchers.IO) { storage.cleanup(it) } }) ?: return
        fun updateCamera(transform: (CameraUiState) -> CameraUiState) {
            _uiState.update { current -> if (generation == cameraSessionGeneration) transform(current) else current }
        }
        updateCamera { it.copy(cameraImportPreparing = true, pendingCameraImportHandoff = null,
            lastCameraImportReceiptSummary = null, lastMediaBatchResult = null) }
        val job = launchCameraOperation(CameraOperation.MEDIA,
            onRegistered = { handoffOwner.attachPreparation(lease.token, it) },
        ) {
            try {
                val session = storage.prepare(
                    items = selectedItems, camera = camera, providerVersion = appContext.installedVersionName(),
                    reservation = reservation,
                    onItem = { index, total, name ->
                        if (handoffOwner.owns(lease.token, CameraImportHandoffPhase.PREPARING)) updateCamera {
                            it.copy(mediaBatchProgress = MediaBatchProgress(MediaBatchOperation.OPEN_NEGATIVE, index, total, name),
                                activeMediaDownloadName = name,
                                mediaDownloadProgress = CameraMediaTransferProgress(0L, selectedItems[index].sizeBytes))
                        }
                    },
                    onProgress = { progress ->
                        if (handoffOwner.owns(lease.token, CameraImportHandoffPhase.PREPARING)) {
                            updateCamera { it.copy(mediaDownloadProgress = progress) }
                        }
                    },
                ) { item, output, onProgress -> repository.downloadMedia(item, output, onProgress) }
                if (handoffOwner.prepared(lease.token, session)) updateCamera { it.copy(pendingCameraImportHandoff = session) }
            } catch (cancelled: CancellationException) {
                handoffOwner.finish(lease.token, CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.CANCELLED))
                throw cancelled
            } catch (_: Exception) {
                handoffOwner.finish(lease.token, CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.PREPARATION_FAILED))
                updateCamera { it.copy(error = appContext.getString(R.string.serein_prepare_failed), errorOperation = CameraOperation.MEDIA) }
            } finally {
                updateCamera { it.copy(cameraImportPreparing = false, mediaBatchProgress = null,
                    activeMediaDownloadName = null, mediaDownloadProgress = null) }
            }
        }
        if (job == null) {
            handoffOwner.finish(lease.token, CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.CANCELLED))
            updateCamera { it.copy(cameraImportPreparing = false) }
        }
        mediaDownloadJob = job
        job?.invokeOnCompletion { if (mediaDownloadJob === job) mediaDownloadJob = null }
    }

    internal fun claimSereinLaunch(token: Long): CameraImportHandoffSession? = handoffOwner.claimLaunch(token)?.session

    internal fun handleSereinResult(context: Context, token: Long, resultCode: Int, data: Intent?) {
        val lease = handoffOwner.claimResult(token) ?: return
        val session = requireNotNull(lease.session)
        val appContext = context.applicationContext
        // Receipt IO belongs to the retained transaction, even while a replacement camera is busy.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var outcome = CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.RECEIPT_INVALID)
            try {
                outcome = when {
                    resultCode != Activity.RESULT_OK -> CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.CANCELLED)
                    data?.data == null -> CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.RECEIPT_MISSING)
                    else -> withContext(Dispatchers.IO) {
                        val receiptUri = requireNotNull(data.data)
                        val resultType = data.type ?: appContext.contentResolver.getType(receiptUri)
                        require(resultType == dev.openeos.control.importing.CameraImportAndroidIntentV1.RECEIPT_MIME_TYPE)
                        CameraImportHandoffOutcome(summary = readCameraImportReceiptBatch(appContext, receiptUri, session))
                    }
                }
            } catch (cancelled: CancellationException) {
                outcome = CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.CANCELLED)
                throw cancelled
            } catch (_: Exception) {
                // Provider errors can contain private URIs or arbitrary receipt contents.
                outcome = CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.RECEIPT_INVALID)
            } finally {
                publishCameraHandoffOutcome(appContext, lease, outcome)
                handoffOwner.finish(token, outcome)
            }
        }
    }

    internal fun handleSereinLaunchFailure(context: Context, token: Long) {
        val lease = handoffOwner.state.value.active?.takeIf {
            it.token == token && it.phase == CameraImportHandoffPhase.AWAITING_RESULT
        } ?: return
        val outcome = CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.LAUNCH_FAILED)
        publishCameraHandoffOutcome(context.applicationContext, lease, outcome)
        handoffOwner.finish(token, outcome)
    }

    private fun publishCameraHandoffOutcome(context: Context, lease: CameraImportHandoffLease<CameraImportHandoffSession>,
        outcome: CameraImportHandoffOutcome,
    ) {
        if (lease.origin != CameraImportHandoffOrigin.CAMERA || lease.cameraGeneration != cameraSessionGeneration) return
        val error = when (outcome.issue) {
            CameraImportHandoffIssue.LAUNCH_FAILED -> context.getString(R.string.serein_launch_failed)
            CameraImportHandoffIssue.RECEIPT_MISSING -> context.getString(R.string.serein_receipt_missing)
            CameraImportHandoffIssue.RECEIPT_INVALID -> context.getString(R.string.serein_receipt_invalid)
            else -> null
        }
        _uiState.update {
            if (lease.cameraGeneration != cameraSessionGeneration) it else it.copy(
                pendingCameraImportHandoff = null, lastCameraImportReceiptSummary = outcome.summary,
                error = error, errorOperation = CameraOperation.MEDIA.takeIf { error != null })
        }
    }

    fun cancelMediaDownload() {
        cancelDownloadContinuation()
        handoffOwner.cancelUnlaunched(CameraImportHandoffOrigin.CAMERA)
        if (foregroundImportOwner?.operation === mediaDownloadJob && mediaDownloadJob != null) {
            stopForegroundImport(ForegroundImportStopReason.USER)
        }
        mediaDownloadJob?.cancel()
    }

    fun uploadMedia(context: Context, sourceUri: Uri) {
        val state = _uiState.value
        if (!canStartMediaUpload(state.previewMode, state.supports(CameraFeature.MEDIA_UPLOAD),
            state.isBusy(CameraOperation.MEDIA), mediaUploadJob != null, mediaDownloadJob != null)
        ) return
        val generation = cameraSessionGeneration
        val attempt = MediaUploadAttempt()
        mediaUploadAttempt = attempt
        fun owns(current: CameraUiState = _uiState.value): Boolean = ownsMediaUpload(
            attempt, mediaUploadAttempt, state.info, current.info, generation, cameraSessionGeneration,
        )
        fun publish(transform: (CameraUiState) -> CameraUiState) {
            _uiState.update { if (owns(it)) transform(it) else it }
        }
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        publish { it.copy(lastUploadedMediaName = null, mediaUploadStatus = MediaUploadStatus.PREPARING,
            mediaUploadRefreshFailed = false) }
        val job = launchCameraOperation(CameraOperation.MEDIA, onRegistered = { mediaUploadJob = it },
            afterFinally = {
                // Admission can be cancelled while older media reads are still joining.
                val outcome = attempt.finish()
                publish { it.copy(activeMediaUploadName = null, mediaUploadProgress = null,
                    mediaUploadStatus = outcome, lastUploadedMediaName = attempt.confirmedResult()?.item?.name) }
            },
        ) {
            var temporaryFile: File? = null
            var refreshed = false
            try {
                val metadata = withContext(Dispatchers.IO) {
                    coroutineContext.ensureActive()
                    if (!owns()) throw CancellationException("Upload owner changed.")
                    resolver.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        MediaUploadMetadata(
                            nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString).orEmpty(),
                            sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
                        )
                    }
                } ?: MediaUploadMetadata(sourceUri.lastPathSegment?.substringAfterLast('/').orEmpty(), null)
                val name = metadata.name.trim()
                if (name.isEmpty()) throw CameraAppException(CameraAppError.UPLOAD_FILENAME_UNKNOWN)
                publish { it.copy(activeMediaUploadName = name,
                    mediaUploadProgress = CameraMediaTransferProgress(0L, metadata.sizeBytes)) }
                withContext(Dispatchers.IO) {
                    coroutineContext.ensureActive()
                    if (!owns()) throw CancellationException("Upload owner changed.")
                    val cached = File(appContext.cacheDir, "media-upload-${UUID.randomUUID()}.tmp")
                    temporaryFile = cached
                    val rawInput = resolver.openInputStream(sourceUri)
                        ?: throw CameraAppException(CameraAppError.UPLOAD_SOURCE_UNAVAILABLE)
                    val resolvedSize = BufferedInputStream(rawInput).use { input ->
                        FileOutputStream(cached).buffered().use { output ->
                            val buffer = ByteArray(MEDIA_UPLOAD_BUFFER_BYTES)
                            var copied = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                if (!owns()) throw CancellationException("Upload owner changed.")
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                copied += count
                                if (copied > MAX_MEDIA_UPLOAD_BYTES) throw CameraAppException(CameraAppError.UPLOAD_TOO_LARGE)
                                coroutineContext.ensureActive()
                                output.write(buffer, 0, count)
                                publish { if (attempt.acceptsProgress()) it.copy(mediaUploadProgress =
                                    CameraMediaTransferProgress(copied, metadata.sizeBytes?.takeIf { size -> size > 0L })) else it }
                            }
                            copied
                        }
                    }
                    if (resolvedSize !in 1L..MAX_MEDIA_UPLOAD_BYTES) throw CameraAppException(CameraAppError.UPLOAD_SIZE_INVALID)
                    coroutineContext.ensureActive()
                    if (!owns()) throw CancellationException("Upload owner changed.")
                    BufferedInputStream(FileInputStream(cached)).use { input ->
                        repository.uploadMedia(name, resolvedSize, resolver.getType(sourceUri), input,
                            evidence = MediaUploadEvidence(
                                beforeDispatch = {
                                    if (!owns()) throw CancellationException("Upload owner changed.")
                                    attempt.beforeDispatch()
                                    publish { it.copy(mediaUploadStatus = MediaUploadStatus.SENDING,
                                        mediaUploadProgress = CameraMediaTransferProgress(0L, resolvedSize)) }
                                },
                                // Runs in the transport before withContext's prompt cancellation boundary.
                                // The receipt remains on this attempt even if its return value is cancelled.
                                onConfirmed = attempt::confirm,
                            ),
                        ) { progress ->
                            publish { if (attempt.acceptsProgress()) it.copy(mediaUploadProgress = progress) else it }
                        }
                    }
                }
                val receipt = attempt.confirmedResult()
                if (receipt == null) throw CameraAppException(CameraAppError.UPLOAD_RECEIPT_MISSING)
                publish { it.copy(lastUploadedMediaName = receipt.item.name,
                    mediaUploadStatus = MediaUploadStatus.CONFIRMED) }
                // Refresh only the current browsing scope. It cannot confirm or undo this receipt.
                coroutineContext.ensureActive()
                val refresh: suspend () -> Unit = {
                    if (owns()) {
                        publish { it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.LOADING) }
                        try {
                            val items = repository.listMedia(maximumItemsFor(state.mediaLibraryScope))
                            coroutineContext.ensureActive()
                            if (owns()) {
                                cancelMediaThumbnailLoads()
                                val batch = items.toMediaLibraryBatch(state.mediaLibraryScope)
                                publish { it.copy(mediaItems = batch.items, mediaLibraryHasMore = batch.hasMore,
                                    mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE, mediaThumbnails = emptyMap()) }
                                refreshed = true
                            }
                        } catch (exception: CancellationException) {
                            publish { it.copy(mediaUploadRefreshFailed = true, mediaLibraryLoadStatus = MediaLibraryLoadStatus.CANCELLED) }
                            throw exception
                        } catch (_: Exception) {
                            publish { it.copy(mediaUploadRefreshFailed = true, mediaLibraryLoadStatus = MediaLibraryLoadStatus.FAILED) }
                        }
                    }
                }
                if (state.transport == CameraTransport.USB_PTP) {
                    withContext(NonCancellable) { refresh() }
                } else refresh()
            } catch (exception: CancellationException) {
                throw exception
            } catch (failure: Exception) {
                // Preserve localizable app failures without changing dispatch/receipt evidence.
                // A local staging error must not vanish merely because NOT_SENT is already known.
                if (failure is CameraAppException) publish {
                    it.copy(error = failure.error.messageToken, errorOperation = CameraOperation.MEDIA)
                }
                // Neither a transport error nor a failed local read authorizes retry or a success guess.
                // The final status below is based solely on actual dispatch and receipt evidence.
            } finally {
                val outcome = attempt.finish()
                withContext(NonCancellable + Dispatchers.IO) { temporaryFile?.delete() }
                val receipt = attempt.confirmedResult()
                publish { it.copy(activeMediaUploadName = null, mediaUploadProgress = null,
                    mediaUploadStatus = outcome, lastUploadedMediaName = receipt?.item?.name,
                    mediaUploadRefreshFailed = outcome == MediaUploadStatus.CONFIRMED && !refreshed) }
                if (outcome == MediaUploadStatus.UNCERTAIN && state.transport == CameraTransport.USB_PTP && owns()) {
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { repository.disconnect() } }
                    if (owns()) {
                        closeMediaStream()
                        publish { it.withClearedSession(baseUrl = it.baseUrl,
                            error = appContext.getString(R.string.media_upload_usb_uncertain)) }
                    }
                }
            }
        }
        if (job == null && mediaUploadAttempt === attempt) mediaUploadAttempt = null
        job?.invokeOnCompletion { if (mediaUploadJob === job) mediaUploadJob = null }
    }

    fun cancelMediaUpload() {
        val job = mediaUploadJob ?: return
        mediaUploadAttempt?.cancel()
        job.cancel()
    }

    fun deleteMedia(item: CameraMediaItem) = deleteMediaBatch(listOf(item))

    fun deleteMediaBatch(items: List<CameraMediaItem>) {
        val state = _uiState.value
        if (items.isEmpty() || state.isBusy(CameraOperation.MEDIA) || mediaDeleteJob != null ||
            !state.supports(CameraFeature.MEDIA_DELETE)) return
        if (items.distinctBy(CameraMediaItem::id).size > MAX_MEDIA_DELETE_ITEMS) {
            _uiState.update { it.copy(error = "Select at most $MAX_MEDIA_DELETE_ITEMS items for one deletion.",
                errorOperation = CameraOperation.MEDIA) }
            return
        }
        val selection = items as? MediaDeleteSelection ?: MediaDeleteSelection(items,
            MediaDeleteOwner(state.info, state.mediaSessionGeneration),
            state.transport ?: CameraTransport.CCAPI_NETWORK.takeIf { state.previewMode }, bridgeEngine = state.info?.bridgeEngine, bridgeEndpoint = state.info?.bridgeEndpoint)
        if (!selection.claimConfirmation(state.info, state.mediaSessionGeneration, state.isBusy(CameraOperation.MEDIA))) return
        val generation = cameraSessionGeneration
        val control = MediaDeleteBatchControl()
        mediaDeleteControl = control
        fun isCurrent(): Boolean = generation == cameraSessionGeneration &&
            selection.owner.matches(_uiState.value.info, _uiState.value.mediaSessionGeneration)
        fun currentItem(request: MediaDeleteRequest): CameraMediaItem? {
            val current = _uiState.value
            // A retained preview/review is historical evidence, not a currently loaded deletion row.
            return current.mediaItems.firstOrNull { it.id == request.id }
        }
        var receipt = MediaDeleteReceipt(selection.attemptId, selection.owner,
            selection.requests.map { MediaDeleteRow(it) }, running = true)
        fun publish(updated: MediaDeleteReceipt) {
            receipt = updated
            _uiState.update { current -> current.copy(
                mediaDeleteReceipt = if (isCurrent()) updated else current.mediaDeleteReceipt,
                mediaDeleteHistory = retainMediaDeleteReceipt(current.mediaDeleteHistory, updated),
                mediaBatchProgress = if (isCurrent()) null else current.mediaBatchProgress,
                lastMediaBatchResult = if (isCurrent() && !updated.running) MediaBatchResult(
                    MediaBatchOperation.DELETE, updated.rows.size, updated.confirmed,
                    updated.rows.filter { it.outcome == MediaDeleteOutcome.REJECTED }.map { it.request.name })
                    else current.lastMediaBatchResult,
            ) }
        }
        _uiState.update { it.copy(lastMediaBatchResult = null, lastDeletedMediaName = null, mediaMetadataBatch = null) }
        publish(receipt)
        val job = launchCameraOperation(CameraOperation.MEDIA, onRegistered = { mediaDeleteJob = it }) {
            executeMediaDeleteBatch(selection.attemptId, selection.owner, selection.requests, control,
                isCurrent = ::isCurrent,
                preflight = { request ->
                    val current = _uiState.value
                    val item = currentItem(request)
                    mediaDeletePreflight(request, current.supports(CameraFeature.MEDIA_DELETE), item?.id,
                        item?.let { mediaDeleteSource(it.id, current.transport ?: CameraTransport.CCAPI_NETWORK.takeIf { current.previewMode }, current.info?.bridgeEngine, current.info?.bridgeEndpoint) }, item?.protected, item?.deleteWritable)
                },
                action = { request, evidence ->
                    val item = currentItem(request) ?: error("Selected media is no longer loaded.")
                    if (state.previewMode) { evidence.beforeDispatch(); evidence.onConfirmed() }
                    else repository.deleteMedia(item, evidence)
                },
                onConfirmed = { request ->
                    if (isCurrent()) {
                        val item = selection.first { it.id == request.id }
                        transitionMediaState { current -> if (isCurrent()) current.withDeletedMedia(item) else current }
                    }
                },
                onChanged = ::publish,
            )
            if (isCurrent() && receipt.confirmed > 0) {
                if (state.previewMode) refreshCaptureReview(_uiState.value.mediaItems) else refreshCaptureReview()
            }
        }
        // Also covers cancellation while launchCameraOperation is joining old media reads.
        job?.invokeOnCompletion {
            if (receipt.running) publish(receipt.copy(running = false, rows = receipt.rows.map { row ->
                if (row.outcome == MediaDeleteOutcome.NOT_STARTED) row.copy(reason =
                    if (isCurrent()) MediaDeleteReason.CANCELLED else MediaDeleteReason.OWNER_CHANGED) else row
            }))
            if (mediaDeleteJob === job) mediaDeleteJob = null
            if (mediaDeleteControl === control) mediaDeleteControl = null
        }
        if (job == null) {
            publish(receipt.copy(running = false))
            if (mediaDeleteControl === control) mediaDeleteControl = null
        }
    }

    fun cancelMediaDeletion(owner: MediaDeleteOwner) {
        val state = _uiState.value
        if (!owner.matches(state.info, state.mediaSessionGeneration)) return
        mediaDeleteControl?.cancel()
        mediaDeleteJob?.cancel()
    }

    fun inspectMediaDeletion(owner: MediaDeleteOwner) {
        val state = _uiState.value
        if (!owner.matches(state.info, state.mediaSessionGeneration) || state.isBusy(CameraOperation.MEDIA)) return
        // Only the existing user-selected scope is refreshed. Absence never edits any receipt.
        refreshMedia()
    }

    fun tapFocus(x: Double, y: Double) {
        if (_uiState.value.liveViewCoordinateRefreshPending) return
        if (_uiState.value.isBusy(CameraOperation.FOCUS) || _uiState.value.isBusy(CameraOperation.LIVE_VIEW)) return
        invalidateCameraFocusInfo()
        focusFeedbackJob?.cancel()
        _uiState.update {
            it.copy(
                focusPoint = FocusPoint(x, y),
                focusFeedback = FocusFeedback.FOCUSING,
            )
        }
        if (_uiState.value.previewMode) {
            _uiState.update {
                it.copy(
                    focusPoint = FocusPoint(x, y),
                    focusFeedback = FocusFeedback.ACCEPTED,
                )
            }
            clearFocusFeedbackAfter(FocusFeedback.ACCEPTED)
            return
        }
        val generation = cameraSessionGeneration
        val connection = _uiState.value.info
        runCamera(
            operation = CameraOperation.FOCUS,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            val result = repository.tapFocus(x, y)
            coroutineContext.ensureActive()
            if (generation != cameraSessionGeneration || _uiState.value.info !== connection) return@runCamera
            check(result.ok) { "Camera rejected the focus point." }
            _uiState.update {
                it.copy(
                    focusPoint = FocusPoint(result.x, result.y),
                    focusFeedback = FocusFeedback.ACCEPTED,
                )
            }
            clearFocusFeedbackAfter(FocusFeedback.ACCEPTED)
            if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
        }
    }

    fun setLiveViewTapAction(action: LiveViewTapAction) {
        val feature = when (action) {
            LiveViewTapAction.FOCUS -> CameraFeature.TAP_FOCUS
            LiveViewTapAction.WHITE_BALANCE -> CameraFeature.CLICK_WHITE_BALANCE
        }
        if (_uiState.value.previewMode || _uiState.value.supports(feature)) {
            _uiState.update { it.copy(liveViewTapAction = action) }
        }
    }

    fun clickWhiteBalance(x: Double, y: Double) {
        if (_uiState.value.liveViewCoordinateRefreshPending) return
        if (_uiState.value.isBusy(CameraOperation.SETTING) || _uiState.value.isBusy(CameraOperation.LIVE_VIEW)) return
        _uiState.update {
            it.copy(
                focusPoint = FocusPoint(x, y),
                focusFeedback = FocusFeedback.FOCUSING,
            )
        }
        if (_uiState.value.previewMode) {
            _uiState.update { state ->
                state.copy(
                    status = state.status?.copy(
                        exposure = state.status.exposure.copy(whiteBalance = "click"),
                    ),
                    focusFeedback = FocusFeedback.SUCCESS,
                )
            }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            return
        }
        val generation = cameraSessionGeneration
        val connection = _uiState.value.info
        runCamera(
            operation = CameraOperation.SETTING,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            val revision = cameraStateRevision
            val status = latestCameraStatus(repository.clickWhiteBalance(x, y), revision)
            coroutineContext.ensureActive()
            if (generation != cameraSessionGeneration || _uiState.value.info !== connection) return@runCamera
            _uiState.update {
                it.copy(
                    status = status,
                    focusPoint = FocusPoint(x, y),
                    focusFeedback = FocusFeedback.SUCCESS,
                )
            }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
        }
    }

    private fun updateStatus(
        operation: CameraOperation,
        block: suspend () -> dev.openeos.control.data.CameraStatus,
    ) = runCamera(operation) {
        val revision = cameraStateRevision
        val response = block()
        val status = if (_uiState.value.previewMode) response else latestCameraStatus(response, revision)
        _uiState.update { it.copy(status = status) }
        if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
    }

    /** Re-read only status when another operation crossed a command's response; never replay the command. */
    private suspend fun latestCameraStatus(initial: CameraStatus, revision: Long): CameraStatus {
        var status = initial
        var observedRevision = revision
        while (true) {
            coroutineContext.ensureActive()
            if (observedRevision == cameraStateRevision) {
                adoptShutterReleaseStatus(status)
                return status
            }
            observedRevision = cameraStateRevision
            status = repository.refreshStatus()
        }
    }

    private suspend fun readCameraStateSnapshot(): Pair<CameraStatus, CameraCapabilities> {
        while (true) {
            val revision = cameraStateRevision
            val status = repository.refreshStatus()
            coroutineContext.ensureActive()
            if (revision != cameraStateRevision) continue
            // Safety state must survive a later capability-read failure.
            adoptShutterReleaseStatus(status)
            val capabilities = repository.refreshCapabilities()
            coroutineContext.ensureActive()
            if (revision == cameraStateRevision) return status to capabilities
        }
    }

    private fun updatePreviewExposure(
        update: (dev.openeos.control.data.ExposureState) -> dev.openeos.control.data.ExposureState,
    ): Boolean {
        if (!_uiState.value.previewMode) return false
        _uiState.update { state ->
            state.copy(status = state.status?.copy(exposure = update(state.status.exposure)))
        }
        return true
    }

    private fun captureModeFrom(capabilities: CameraCapabilities): CaptureMode? {
        val setting = capabilities.captureModeSetting() ?: return null
        return setting.currentCaptureMode()?.also { mode ->
            if (mode == CaptureMode.PHOTO && setting.key.isShootingModeKey()) lastPhotoShootingMode = setting.value
        }
    }

    private fun showCaptureSuccess() {
        if (_uiState.value.shutterReleaseUnconfirmed) return
        _uiState.update { it.copy(captureFeedback = CaptureFeedback.SUCCESS) }
        viewModelScope.launch {
            delay(CAPTURE_FLASH_MILLIS)
            _uiState.update { it.copy(captureFeedback = null) }
        }
    }

    private fun runCamera(
        operation: CameraOperation,
        onError: (Exception) -> Unit = {},
        afterFinally: () -> Unit = {},
        connectionAttempt: ConnectionAttemptTarget? = null,
        block: suspend () -> Unit,
    ) {
        launchCameraOperation(operation, onError, afterFinally, connectionAttempt = connectionAttempt, block = block)
    }

    private fun launchCameraOperation(
        operation: CameraOperation,
        onError: (Exception) -> Unit = {},
        afterFinally: () -> Unit = {},
        cancelMediaReads: Boolean = true,
        connectionAttempt: ConnectionAttemptTarget? = null,
        admittedMediaDownload: AdmittedMediaDownload? = null,
        onRegistered: (Job) -> Unit = {},
        block: suspend () -> Unit,
    ): Job? {
        if (_uiState.value.isBusy(operation)) return null
        // A display read can coexist with listing, including ALL on a slow/full card. Saves
        // and every other MEDIA operation retain cancellation + join before camera/file I/O.
        val mediaReadsToJoin = if (operation == CameraOperation.MEDIA && cancelMediaReads) {
            listOfNotNull(mediaLibraryJob, eventMediaJob).also { cancelMediaLibraryLoad() }
        } else emptyList()
        // A reconnect cannot inherit work whose item IDs or commands belong to the old backend.
        val previousJobs = if (operation == CameraOperation.CONNECT) {
            handoffOwner.cancelUnlaunched(CameraImportHandoffOrigin.CAMERA)
            val foregroundImport = stopForegroundImport(ForegroundImportStopReason.SESSION_CHANGED)
            val recordingWarning = retireRecordingAttempt(_uiState.value)
            _uiState.update { it.copy(recordingDisconnectWarning = recordingWarning) }
            activeCaptureConfigurationAttempt?.cancel()
            activeShootingSettingAttempt?.cancel()
        maintenanceAttempts.values.toList().forEach(MaintenanceAttempt::retire)
            mediaDeleteControl?.cancel()
            mediaUploadAttempt?.cancel()
            cameraSessionGeneration += 1
            cancelDownloadContinuation()
            (cameraOperationJobs.values.toList() + liveViewReconciliationJobs.toList() + listOfNotNull(foregroundImport)).also { jobs ->
                cameraOperationJobs.clear()
                liveViewReconciliationJobs.clear()
                jobs.forEach(Job::cancel)
                _uiState.update { it.copy(pendingOperations = emptySet(), foregroundJpegImport = ForegroundJpegImportStatus()) }
            }
        } else emptyList()
        val generation = cameraSessionGeneration
        val connection = _uiState.value.info
        val teardown = disconnectJob
        cameraStateRevision += 1
        _uiState.update {
            it.copy(
                pendingOperations = it.pendingOperations + operation,
                mediaMetadataBatch = if (operation == CameraOperation.MEDIA && it.mediaMetadataBatch?.running != true) null else it.mediaMetadataBatch,
                connectionRecovery = null,
                error = null,
                errorOperation = null,
            )
        }
        val execute: suspend () -> Unit = operation@{
            try {
                val work: suspend () -> Unit = {
                    if (operation == CameraOperation.CONNECT) {
                        teardown?.join()
                        previousJobs.forEach { it.join() }
                        mediaUploadCleanupJob?.join()
                    }
                    mediaReadsToJoin.forEach { it.join() }
                    coroutineContext.ensureActive()
                    block()
                    coroutineContext.ensureActive()
                    if (generation == cameraSessionGeneration && operation in CAPABILITY_EVIDENCE_OPERATIONS) {
                        refreshCapabilityEvidence()
                    }
                }
                if (admittedMediaDownload == null) work() else admittedMediaDownload.run(work)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (generation != cameraSessionGeneration ||
                    (operation == CameraOperation.FOCUS && _uiState.value.info !== connection)) return@operation
                if (exception is dev.openeos.control.data.ConnectionRetirementBlockedException ||
                    exception is dev.openeos.control.data.ConnectionRetirementPendingException) {
                    publishConnectionRetirementWarning()
                    return@operation
                }
                val safetyFailure = exception is ShutterReleaseException || exception is AutofocusReleaseException ||
                    exception is dev.openeos.control.data.CcapiLiveViewReleaseException
                val recovery = if (connectionAttempt != null && !safetyFailure) {
                    connectionRecoveryFor(connectionAttempt, exception)
                } else null
                // Connection diagnostics can contain entered addresses or server bodies. The
                // recovery flow only needs typed evidence, never these values in UI or logs.
                if (recovery == null) exception.printStackTrace()
                if (exception is ShutterReleaseException) markShutterReleaseUnconfirmed()
                onError(exception)
                _uiState.update {
                    it.copy(
                        error = recovery?.let { failure -> "Connection failed: ${failure.reason.name}" }
                            ?: if (exception is MediaWorklistAdmissionException) exception.message ?: formatException(exception)
                            else formatException(exception),
                        connectionRecovery = recovery,
                        errorOperation = operation,
                        autofocusHoldState = if (operation == CameraOperation.FOCUS && exception is AutofocusReleaseException) {
                            AutofocusHoldState.RELEASE_FAILED
                        } else it.autofocusHoldState,
                    )
                }
            } finally {
                publishConnectionRetirementWarning()
                if (generation == cameraSessionGeneration &&
                    (operation != CameraOperation.FOCUS || _uiState.value.info === connection)) {
                    cameraStateRevision += 1
                    _uiState.update {
                        if (operation == CameraOperation.FOCUS && it.autofocusHoldState == AutofocusHoldState.RELEASE_FAILED) it
                        else it.copy(pendingOperations = it.pendingOperations - operation)
                    }
                    afterFinally()
                    if (operation in LIVE_VIEW_INTERLOCK_OPERATIONS && operation != CameraOperation.MAINTENANCE) queueLiveViewReconciliation()
                }
            }
        }
        val register: (Job) -> Unit = { job ->
            cameraOperationJobs[operation] = job
            job.invokeOnCompletion {
                if (cameraOperationJobs[operation] === job) cameraOperationJobs.remove(operation)
            }
            onRegistered(job)
        }
        if (admittedMediaDownload != null) {
            return viewModelScope.launchAdmittedMediaOutput(admittedMediaDownload.output, register, execute)
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) { execute() }
        register(job)
        job.start()
        return job
    }

    private fun refreshCapabilityEvidence() {
        val state = _uiState.value
        if (!state.connected || state.previewMode) return
        val observedFeatures = repository.observedFeatures()
        _uiState.update { current ->
            val capabilities = current.capabilities
            if (current.connected && !current.previewMode && capabilities != null) {
                current.copy(
                    capabilities = capabilities.copy(
                        evidence = capabilities.evidence.copy(observedFeatures = observedFeatures),
                    ),
                )
            } else {
                current
            }
        }
    }

    private fun invalidateCameraFocusInfo() {
        focusInfoGeneration += 1
        _uiState.update { it.copy(cameraFocusInfo = null, cameraFocusInfoAtMillis = null, cameraFocusInfoError = false) }
    }

    private fun stopCameraFocusInfoLoop() {
        focusInfoJob?.cancel()
        focusInfoJob = null
        invalidateCameraFocusInfo()
    }

    private fun canReadCameraFocusInfo(state: CameraUiState): Boolean =
        state.connected && !state.previewMode && !state.maintenanceSuspended && appInForeground && state.uiMode != UiMode.MEDIA &&
            state.liveViewAutoRefresh && state.liveViewTemperatureAllowed && repository.isLiveViewRunning() &&
            !state.isBusy(CameraOperation.LIVE_VIEW) && CameraOperation.CAPTURE !in state.pendingOperations &&
            state.capabilities?.liveView?.focusInfoSupported == true

    private fun startCameraFocusInfoLoop() {
        stopCameraFocusInfoLoop()
        val connection = _uiState.value.info
        if (_uiState.value.capabilities?.liveView?.focusInfoSupported != true) return
        focusInfoJob = viewModelScope.launch {
            while (isActive && _uiState.value.connected && !_uiState.value.previewMode && _uiState.value.info === connection) {
                val generation = focusInfoGeneration
                var retryDelay = 250L
                if (canReadCameraFocusInfo(_uiState.value)) {
                    try {
                        val info = focusInfoReadGate.readIfCurrent(
                            isCurrent = {
                                generation == focusInfoGeneration && canReadCameraFocusInfo(_uiState.value) &&
                                    _uiState.value.info === connection
                            },
                            read = { repository.fetchLiveViewFocusInfo() },
                        )
                        if (info == null) retryDelay = 2_000L
                        if (generation == focusInfoGeneration && canReadCameraFocusInfo(_uiState.value) && _uiState.value.info === connection) {
                            _uiState.update {
                                it.copy(cameraFocusInfo = info, cameraFocusInfoAtMillis = info?.let { System.currentTimeMillis() }, cameraFocusInfoError = false)
                            }
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        retryDelay = 1_000L
                        if (generation == focusInfoGeneration && _uiState.value.info === connection) {
                            _uiState.update { it.copy(cameraFocusInfo = null, cameraFocusInfoAtMillis = null, cameraFocusInfoError = true) }
                        }
                    }
                } else if (_uiState.value.cameraFocusInfo != null) {
                    invalidateCameraFocusInfo()
                }
                delay(retryDelay)
            }
        }
    }

    private fun clearFocusFeedbackAfter(expected: FocusFeedback) {
        focusFeedbackJob?.cancel()
        focusFeedbackJob = viewModelScope.launch {
            delay(FOCUS_FEEDBACK_MILLIS)
            _uiState.update {
                if (it.focusFeedback == expected) {
                    it.copy(focusFeedback = null, focusPoint = null)
                } else {
                    it
                }
            }
        }
    }

    private suspend fun refreshLiveViewFrameInternal(reportErrors: Boolean): Boolean {
        // Native video owns its presentation and listener generation; it has no bitmap read to stop.
        if (_uiState.value.nativeLiveViewSession != null) return false
        return refreshOwnedLiveViewFrame(reportErrors)
    }

    private suspend fun refreshOwnedLiveViewFrame(reportErrors: Boolean): Boolean = supervisorScope {
        val job = async(start = CoroutineStart.LAZY) { readLiveViewFrameInternal(reportErrors) }
        val read = LiveViewFrameRead(job)
        liveViewFrameReads += read
        job.start()
        try {
            job.await()
            coroutineContext.ensureActive()
            !read.stopped
        } catch (exception: CancellationException) {
            // Stopping preview must not cancel the control connection. A cancelled parent still
            // owns its session cleanup and must propagate cancellation instead of publishing it.
            coroutineContext.ensureActive()
            if (!read.stopped) throw exception
            false
        } finally {
            liveViewFrameReads.remove(read)
        }
    }

    private fun cancelLiveViewFrameReads() {
        liveViewFrameReads.toList().forEach { read ->
            read.stopped = true
            read.job.cancel()
        }
    }

    private suspend fun readLiveViewFrameInternal(reportErrors: Boolean) {
        if (
            !appInForeground || _uiState.value.maintenanceSuspended || !_uiState.value.liveViewAutoRefresh || !repository.isLiveViewRunning() ||
            !_uiState.value.connected || _uiState.value.shutterReleaseUnconfirmed || _uiState.value.bridgeBulbStopRequired ||
            _uiState.value.previewMode ||
            !_uiState.value.supports(CameraFeature.LIVE_VIEW)
            || !_uiState.value.liveViewTemperatureAllowed
            || _uiState.value.nativeLiveViewSession != null
        ) return

        val generation = liveViewGeneration
        val owner = repository.liveViewOwner()

        if (!repository.isRealCamera()) {
            val nextUrl = repository.nextLiveViewFrameUrl()
            _uiState.update {
                if (it.connected && !it.shutterReleaseUnconfirmed && !it.bridgeBulbStopRequired && appInForeground && it.liveViewAutoRefresh && generation == liveViewGeneration && repository.ownsLiveView(owner)) {
                    it.copy(
                        liveViewFrameUrl = nextUrl,
                        liveViewBitmap = null,
                        liveViewCoordinateRefreshPending = false,
                        error = if (it.errorOperation == CameraOperation.LIVE_VIEW) null else it.error,
                        errorOperation = it.errorOperation.takeUnless { operation -> operation == CameraOperation.LIVE_VIEW },
                        liveViewDiagnostics = recordFrame(
                            current = it.liveViewDiagnostics,
                            nowMillis = System.currentTimeMillis(),
                            sourceUrl = nextUrl,
                        ),
                    )
                } else {
                    it
                }
            }
            return
        }

        try {
            val frame = repository.fetchLiveViewFrame()
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)
            } ?: throw LiveViewFrameDecodeException(frame.bytes.size, frame.contentType)

            _uiState.update {
                if (it.connected && !it.shutterReleaseUnconfirmed && !it.bridgeBulbStopRequired && it.liveViewAutoRefresh &&
                    LiveViewLifecycleEvidence(sameOwner = repository.ownsLiveView(owner),
                        sameAttempt = generation == liveViewGeneration, foreground = appInForeground,
                        startAdmitted = repository.isLiveViewRunning(), frameEvidence = "decoded-frame")
                        .evaluate().canPublishDecoded) {
                    it.copy(
                        liveViewFrameUrl = frame.sourceUrl,
                        liveViewBitmap = bitmap,
                        liveViewCoordinateRefreshPending = false,
                        error = if (it.errorOperation == CameraOperation.LIVE_VIEW) null else it.error,
                        errorOperation = it.errorOperation.takeUnless { operation -> operation == CameraOperation.LIVE_VIEW },
                        liveViewDiagnostics = recordFrame(
                            current = it.liveViewDiagnostics,
                            nowMillis = System.currentTimeMillis(),
                            frameBytes = frame.bytes.size,
                            contentType = frame.contentType,
                            sourceUrl = frame.sourceUrl,
                        ),
                    )
                } else {
                    it
                }
            }
            if (
                CameraFeature.LIVE_VIEW_JPEG_POLLING !in
                _uiState.value.capabilities?.evidence?.observedFeatures.orEmpty()
            ) {
                refreshCapabilityEvidence()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // A terminal reader is never polled again, including errors in the quiet loop.
            retireFailedLiveView(generation, owner, formatException(exception))
        }
    }

    private fun clearLiveViewPresentation() {
        resetFrameMetrics()
        invalidateCameraFocusInfo()
        _uiState.update { it.copy(
            liveViewBitmap = null, liveViewFrameUrl = null, nativeLiveViewSession = null,
            focusPoint = null, focusFeedback = null, liveViewDiagnostics = LiveViewDiagnostics(),
            liveViewAudioStatus = NativeLiveViewAudioStatus.None,
        ) }
    }

    private fun retireFailedLiveView(generation: Long, owner: CameraLiveViewOwner, message: String) {
        if (generation != liveViewGeneration || !repository.ownsLiveView(owner)) return
        if (!repository.retireLiveView(owner)) return
        liveViewGeneration += 1
        detachNativeLiveViewListener()
        stopLiveViewLoop()
        clearLiveViewPresentation()
        _uiState.update { it.copy(
            liveViewAutoRefresh = false, liveViewStreamFailed = true, liveViewStopPending = false,
            liveViewStopUnconfirmed = repository.isLiveViewStopRequired(),
            error = message, errorOperation = CameraOperation.LIVE_VIEW,
        ) }
        // Use the existing physical-control interlocks. If a bulb/shutter operation owns the
        // camera, Stop responsibility stays visible until that operation releases or the user retries.
        queueLiveViewReconciliation()
    }

    private fun formatException(exception: Exception): String = if (exception is CameraAppException) {
        exception.error.messageToken
    } else if (exception is LiveViewFrameDecodeException) {
        exception.failure.messageToken
    } else buildString {
        append(exception.javaClass.simpleName)
        append(": ")
        append(exception.message ?: "Unknown error")
        var cause = exception.cause
        while (cause != null) {
            append("\nCaused by: ")
            append(cause.javaClass.simpleName)
            append(": ")
            append(cause.message ?: "Unknown cause")
            cause = cause.cause
        }
    }

    private fun startLiveViewLoopIfNeeded() {
        liveViewJob?.cancel()
        val state = _uiState.value
        if (
            !state.connected || state.shutterReleaseUnconfirmed || state.bridgeBulbStopRequired || state.maintenanceSuspended ||
            !appInForeground || !repository.isLiveViewRunning() ||
            state.previewMode ||
            !state.liveViewAutoRefresh ||
            !state.supports(CameraFeature.LIVE_VIEW)
            || !state.liveViewTemperatureAllowed
            || state.nativeLiveViewSession != null
        ) return

        liveViewJob = viewModelScope.launch {
            while (isActive) {
                val latest = _uiState.value
                if (!latest.connected || latest.shutterReleaseUnconfirmed || latest.bridgeBulbStopRequired || !latest.liveViewAutoRefresh || !appInForeground || !repository.isLiveViewRunning()) break
                val frameStartedAt = SystemClock.elapsedRealtime()

                if (repository.isRealCamera()) {
                    if (!refreshLiveViewFrameInternal(reportErrors = false)) break
                } else {
                    val nextUrl = repository.nextLiveViewFrameUrl()
                    _uiState.update {
                        if (it.connected && !it.shutterReleaseUnconfirmed && !it.bridgeBulbStopRequired && it.liveViewAutoRefresh) {
                            it.copy(
                                liveViewFrameUrl = nextUrl,
                                liveViewBitmap = null,
                                error = if (it.errorOperation == CameraOperation.LIVE_VIEW) null else it.error,
                                errorOperation = it.errorOperation.takeUnless { operation -> operation == CameraOperation.LIVE_VIEW },
                                liveViewDiagnostics = recordFrame(
                                    current = it.liveViewDiagnostics,
                                    nowMillis = System.currentTimeMillis(),
                                    sourceUrl = nextUrl,
                                ),
                            )
                        } else {
                            it
                        }
                    }
                }

                val frameIntervalMillis = fpsToFrameIntervalMillis(_uiState.value.liveViewFrameRateFps)
                val elapsedMillis = SystemClock.elapsedRealtime() - frameStartedAt
                delay((frameIntervalMillis - elapsedMillis).coerceAtLeast(0L))
            }
        }
    }

    private fun stopLiveViewLoop() {
        cancelLiveViewFrameReads()
        liveViewJob?.cancel()
        liveViewJob = null
    }

    private fun startEventPollingIfSupported() {
        stopEventPollingLoop()
        val state = _uiState.value
        if (!state.connected || state.previewMode || state.maintenanceSuspended || !state.supports(CameraFeature.EVENT_POLLING)) return
        val generation = eventPollingGeneration
        eventPollingJob = viewModelScope.launch {
            var consecutiveFailures = 0
            var pendingChangedKeys = emptySet<String>()
            var pendingMediaDemand = mediaEventDemandGeneration
            while (isActive && generation == eventPollingGeneration) {
                try {
                    // Polling consumes the notification. Keep its complete hint until the
                    // authoritative snapshot succeeds; an empty later poll cannot repair it.
                    if (pendingChangedKeys.isEmpty()) {
                        pendingMediaDemand = mediaEventDemandGeneration
                        pendingChangedKeys = repository.pollEvent().changedKeys.toSet()
                        if (pendingChangedKeys.isEmpty()) {
                            consecutiveFailures = 0
                            continue
                        }
                    }
                    // Publish control state before any potentially slow media listing. A command
                    // that starts or finishes during a read invalidates that snapshot and retries.
                    val capabilities = refreshEventCameraState(generation) ?: break
                    val refreshContents = "contents" in pendingChangedKeys && pendingMediaDemand == mediaEventDemandGeneration
                    pendingChangedKeys = emptySet()
                    consecutiveFailures = 0
                    if (refreshContents && capabilities.matrix.supports(CameraFeature.MEDIA_BROWSER)) {
                        refreshEventMedia(generation)
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    if (exception is ShutterReleaseException && generation == eventPollingGeneration) {
                        markShutterReleaseUnconfirmed()
                    }
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(EVENT_RETRY_DELAYS_MILLIS.size)
                    delay(EVENT_RETRY_DELAYS_MILLIS[consecutiveFailures - 1])
                }
            }
        }
    }

    private suspend fun refreshEventCameraState(generation: Long): CameraCapabilities? {
        while (generation == eventPollingGeneration && _uiState.value.connected) {
            if (_uiState.value.pendingOperations.isNotEmpty()) {
                delay(50L)
                continue
            }
            val revision = cameraStateRevision
            val status = repository.refreshStatus()
            coroutineContext.ensureActive()
            if (generation != eventPollingGeneration) return null
            if (revision != cameraStateRevision || _uiState.value.pendingOperations.isNotEmpty()) continue
            adoptShutterReleaseStatus(status)
            val capabilities = repository.refreshCapabilities()
            coroutineContext.ensureActive()
            if (generation != eventPollingGeneration) return null
            if (revision != cameraStateRevision || _uiState.value.pendingOperations.isNotEmpty()) continue
            val captureMode = captureModeFrom(capabilities)
            recordingAttempt?.takeIf { ownsRecordingAttempt(it) && !it.inFlight && !it.cancelled }
                ?.let { adoptRecordingStatus(it, status) }
            _uiState.update { current ->
                current.copy(
                    status = status,
                    captureStatusReadbackFailed = false,
                    recordingStartNotConfirmed = current.recordingStartNotConfirmed && status.recording != true,
                    capabilities = capabilities,
                    captureMode = if (current.pendingRecordingCommand != null) CaptureMode.VIDEO else captureMode ?: current.captureMode,
                    liveViewMagnification = capabilities.liveView.currentMagnification
                        ?: current.liveViewMagnification?.takeIf { it in capabilities.liveView.magnifications },
                )
            }
            return capabilities
        }
        return null
    }

    private suspend fun refreshEventMedia(generation: Long) {
        val libraryGeneration = mediaLibraryGeneration
        while (generation == eventPollingGeneration && _uiState.value.connected && _uiState.value.isBusy(CameraOperation.MEDIA)) {
            delay(50L)
        }
        if (generation != eventPollingGeneration || libraryGeneration != mediaLibraryGeneration ||
            !_uiState.value.connected || _uiState.value.mediaLibraryLoading) return
        _uiState.update { it.copy(mediaLibraryLoading = true, mediaLibraryLoadStatus = MediaLibraryLoadStatus.LOADING) }
        // The child is independently cancellable by a download, explicit Cancel, or a scope change.
        // Cancelling it must leave event polling alive; cancelling the parent cancels both.
        supervisorScope {
            val job = async(start = CoroutineStart.LAZY) { repository.listMedia(RECENT_MEDIA_REQUEST_ITEMS) }
            eventMediaJob = job
            try {
                job.start()
                val items = job.await()
                coroutineContext.ensureActive()
                if (generation != eventPollingGeneration || libraryGeneration != mediaLibraryGeneration) return@supervisorScope
                val batch = items.toMediaLibraryBatch(MediaLibraryScope.RECENT)
                cancelMediaThumbnailLoads()
                transitionMediaState { current ->
                    val merged = if (current.mediaLibraryScope == MediaLibraryScope.ALL) {
                        mergeRecentMedia(batch.items, current.mediaItems)
                    } else batch.items
                    current.withEventMediaItems(merged).copy(
                        mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE,
                        mediaLibraryHasMore = current.mediaLibraryScope == MediaLibraryScope.RECENT && batch.hasMore,
                    )
                }
                refreshCaptureReview(batch.items)
            } catch (exception: CancellationException) {
                // A cancelled child is an ordinary gallery cancellation, not an event-loop failure.
                coroutineContext.ensureActive()
            } catch (_: Exception) {
                if (generation == eventPollingGeneration && libraryGeneration == mediaLibraryGeneration) {
                    _uiState.update { it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.FAILED) }
                }
            } finally {
                if (eventMediaJob === job) eventMediaJob = null
                if (generation == eventPollingGeneration && libraryGeneration == mediaLibraryGeneration) {
                    _uiState.update { it.copy(mediaLibraryLoading = false) }
                }
            }
        }
    }

    private fun stopEventPollingLoop() {
        if (eventMediaJob != null) invalidateMediaLibraryLoad(MediaLibraryLoadStatus.CANCELLED)
        eventPollingGeneration += 1
        eventPollingJob?.cancel()
        eventPollingJob = null
    }

    private suspend fun stopEventPollingLoopAndJoin() {
        if (eventMediaJob != null) invalidateMediaLibraryLoad(MediaLibraryLoadStatus.CANCELLED)
        eventPollingGeneration += 1
        val job = eventPollingJob
        eventPollingJob = null
        job?.cancelAndJoin()
    }

    private fun pauseLiveViewForBulb() {
        stopLiveViewLoop()
        repository.setNativeLiveViewRenderingEnabled(false)
    }

    private suspend fun resumeLiveViewAfterBulb() {
        if (_uiState.value.shutterReleaseUnconfirmed || _uiState.value.bridgeBulbStopRequired) return
        reconcileLiveView()
        repository.setNativeLiveViewRenderingEnabled(appInForeground && _uiState.value.liveViewAutoRefresh)
        if (!appInForeground || !_uiState.value.liveViewAutoRefresh) return
        if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
    }

    override fun onCleared() {
        cubeLutImporter.close()
        val retireMaintenanceWithoutCommands = repository.maintenanceRetirementAccess(_uiState.value.maintenanceSuspended)
        val handoffCleanup = handoffOwner.cancelUnlaunched()
        foregroundImportPreferences?.unregisterOnSharedPreferenceChangeListener(foregroundImportWarningListener)
        val foregroundImport = stopForegroundImport(ForegroundImportStopReason.SESSION_CHANGED)
        activeCaptureConfigurationAttempt?.cancel()
        activeShootingSettingAttempt?.cancel()
        maintenanceAttempts.values.toList().forEach(MaintenanceAttempt::retire)
        mediaDeleteControl?.cancel()
        val ownedBulbJobs = retireOwnedBridgeBulbJobs()
        mediaUploadAttempt?.cancel()
        cameraSessionGeneration += 1
        val operationJobs = cameraOperationJobs.values.toList() + liveViewReconciliationJobs.toList() + listOfNotNull(foregroundImport)
        cameraOperationJobs.clear()
        liveViewReconciliationJobs.clear()
        operationJobs.forEach(Job::cancel)
        val teardown = disconnectJob
        val uploadCleanupJob = mediaUploadCleanupJob
        mediaUploadCleanupJob = null
        uploadCleanupJob?.cancel()
        stopHeldAutofocus()
        val focusJob = heldAutofocusJob
        stopCameraFocusInfoLoop()
        stopLiveViewLoop()
        stopEventPollingLoop()
        detachNativeLiveViewListener()
        resetMediaLibraryLoad()
        cancelCaptureReview()
        closeMediaStream()
        cancelMediaDownload()
        val uploadJob = mediaUploadJob
        mediaUploadJob = null
        uploadJob?.cancel()
        cancelMediaThumbnailLoads()
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            handoffCleanup?.join()
            teardown?.join()
            operationJobs.filterNot { it in ownedBulbJobs }.forEach { it.join() }
            uploadJob?.join()
            uploadCleanupJob?.join()
            focusJob?.join()
            if (retireMaintenanceWithoutCommands != null) retireMaintenanceWithoutCommands()
            else repository.disconnect()
        }
        super.onCleared()
    }

    private fun CameraUiState.withClearedSession(
        baseUrl: String,
        error: String?,
    ): CameraUiState = copy(
        autofocusHoldState = AutofocusHoldState.IDLE,
        shutterAutofocus = true,
        shutterReleaseUnconfirmed = false,
        bridgeBulbStopRequired = false,
        bridgeBulbStartPending = false,
        bridgeBulbStopPending = false,
        bridgeBulbUnconfirmed = false,
        liveViewStopUnconfirmed = false,
        liveViewStopPending = false,
        liveViewStreamFailed = false,
        pendingOperations = pendingOperations - CameraOperation.FOCUS,
        baseUrl = baseUrl,
        previewMode = false,
        transport = null,
        info = null,
        status = null,
        capabilities = null,
        mediaItems = emptyList(),
        mediaDateRange = null,
        mediaRatingFilter = MediaRatingFilter.ALL,
        mediaFolderFilter = MediaFolderFilter.All,
        mediaSessionGeneration = mediaSessionGeneration + 1,
        mediaLibraryHasMore = false,
        mediaThumbnails = emptyMap(),
        mediaThumbnailLoadingIds = emptySet(),
        mediaPreviewItem = null,
        mediaPreviewBytes = null,
        mediaPreviewLoading = false,
        mediaStreamSource = null,
        mediaLibraryLoading = false,
        mediaLibraryLoadStatus = MediaLibraryLoadStatus.NOT_LOADED,
        captureReviewItem = null,
        captureReviewThumbnail = null,
        captureReviewLoading = false,
        mediaSaveFeedback = emptyMap(),
        mediaWorklistUnavailable = false,
        foregroundJpegImport = ForegroundJpegImportStatus(),
        captureReviewStatus = CaptureReviewStatus.IDLE,
        captureReviewObservation = CaptureReviewObservation.UNREAD,
        captureReviewEvidence = CaptureReviewEvidence(),
        captureStatusReadbackFailed = false,
        recordingReceipt = null,
        pendingRecordingCommand = null,
        recordingCommandUnconfirmed = false,
        recordingStartNotConfirmed = false,
        activeMediaDownloadName = null,
        mediaDownloadProgress = null,
        lastDownloadedMediaName = null,
        lastDownloadLocation = null,
        activeMediaUploadName = null,
        mediaUploadProgress = null,
        lastUploadedMediaName = null,
        mediaUploadStatus = null,
        mediaUploadRefreshFailed = false,
        lastDeletedMediaName = null,
        mediaBatchProgress = null,
        lastMediaBatchResult = null,
        mediaMetadataBatch = null,
        previousMediaMetadataBatch = mediaMetadataBatch?.retiredMetadataReceipt() ?: previousMediaMetadataBatch,
        mediaDeleteReceipt = null,
        cameraImportPreparing = false,
        pendingCameraImportHandoff = null,
        lastCameraImportReceiptSummary = null,
        liveViewFrameUrl = null,
        liveViewBitmap = null,
        nativeLiveViewSession = null,
        liveViewMagnification = null,
        liveViewDiagnostics = LiveViewDiagnostics(),
        liveViewAudioStatus = NativeLiveViewAudioStatus.None,
        liveViewAspectRatio = 16f / 9f,
        networkDiagnostics = CameraNetworkDiagnostics.Empty,
        focusPoint = null,
        focusFeedback = null,
        lastClockSyncAtMillis = null,
        maintenanceReceipt = null,
        maintenanceSuspended = false,
        lastCreatedDirectoryName = null,
        captureConfigurationReceipt = null,
        shootingSettingReceipt = null,
        operatorConfirmedFeatures = emptySet(),
        error = error,
        errorOperation = null,
        connectionRecovery = null,
    )

    private fun fpsToFrameIntervalMillis(fps: Int): Long =
        (1_000L / fps.coerceIn(MIN_LIVE_VIEW_FPS, MAX_LIVE_VIEW_FPS)).coerceAtLeast(1L)

    private fun CameraUiState.withDeletedMedia(item: CameraMediaItem): CameraUiState {
        val deletesOpenPreview = mediaPreviewItem?.id == item.id
        val deletesCaptureReview = captureReviewItem?.id == item.id
        return copy(
            mediaItems = mediaItems.filterNot { it.id == item.id },
            mediaThumbnails = mediaThumbnails - item.id,
            mediaThumbnailLoadingIds = mediaThumbnailLoadingIds - item.id,
            mediaPreviewItem = mediaPreviewItem.takeUnless { deletesOpenPreview },
            mediaPreviewBytes = mediaPreviewBytes.takeUnless { deletesOpenPreview },
            mediaPreviewLoading = mediaPreviewLoading && !deletesOpenPreview,
            mediaStreamSource = mediaStreamSource.takeUnless { deletesOpenPreview },
            captureReviewItem = captureReviewItem.takeUnless { deletesCaptureReview },
            captureReviewThumbnail = captureReviewThumbnail.takeUnless { deletesCaptureReview },
            captureReviewLoading = captureReviewLoading && !deletesCaptureReview,
            lastDownloadedMediaName = lastDownloadedMediaName.takeUnless { it == item.name },
            lastDeletedMediaName = item.name,
        )
    }

    private fun CameraUiState.withVerifiedMediaMetadata(target: MediaMetadataTarget, readback: CameraMediaItem): CameraUiState {
        fun merge(current: CameraMediaItem): CameraMediaItem =
            if (current.id == readback.id) current.withVerifiedMetadata(target, readback) else current
        return copy(mediaItems = mediaItems.map(::merge), mediaPreviewItem = mediaPreviewItem?.let(::merge),
            captureReviewItem = captureReviewItem?.let(::merge))
    }

    // This is an observed metadata signature, not byte/content identity. Rating,
    // protection and other non-identity metadata updates do not invalidate a bitmap.
    private fun CameraMediaItem.hasSameThumbnailSource(other: CameraMediaItem): Boolean =
        id == other.id && name == other.name && isVisualMedia && other.isVisualMedia && isVideo == other.isVideo

    private fun CameraUiState.withUpdatedMedia(item: CameraMediaItem): CameraUiState {
        val retainsThumbnail = mediaItems.any { it.hasSameThumbnailSource(item) }
        return copy(
            mediaItems = mediaItems.map { current -> if (current.id == item.id) item else current },
            mediaThumbnails = if (retainsThumbnail) mediaThumbnails else mediaThumbnails - item.id,
            mediaThumbnailLoadingIds = if (retainsThumbnail) mediaThumbnailLoadingIds else mediaThumbnailLoadingIds - item.id,
            mediaPreviewItem = mediaPreviewItem?.let { current -> if (current.id == item.id) item else current },
            captureReviewItem = captureReviewItem?.let { current -> if (current.id == item.id) item else current },
        )
    }

    internal fun CameraUiState.withEventMediaItems(
        items: List<CameraMediaItem>,
        preservePreview: Boolean = false,
    ): CameraUiState {
        val itemIds = items.mapTo(hashSetOf(), CameraMediaItem::id)
        val previousItems = mediaItems.associateBy(CameraMediaItem::id)
        val retainedThumbnailIds = items.filter { item ->
            previousItems[item.id]?.hasSameThumbnailSource(item) == true
        }.mapTo(hashSetOf(), CameraMediaItem::id)
        val previewStillExists = preservePreview || mediaPreviewItem?.id in itemIds
        return copy(
            mediaItems = items,
            mediaThumbnails = mediaThumbnails.filterKeys(retainedThumbnailIds::contains),
            mediaThumbnailLoadingIds = mediaThumbnailLoadingIds.intersect(retainedThumbnailIds),
            mediaPreviewItem = mediaPreviewItem.takeIf { previewStillExists },
            mediaPreviewBytes = mediaPreviewBytes.takeIf { previewStillExists },
            mediaPreviewLoading = mediaPreviewLoading && previewStillExists,
            mediaStreamSource = mediaStreamSource.takeIf { previewStillExists },
        )
    }

    private fun applyMediaItems(items: List<CameraMediaItem>, hasMore: Boolean) = transitionMediaState { current ->
        // An intermediate page is not evidence that the separately requested preview was deleted.
        current.withEventMediaItems(items, preservePreview = true).copy(mediaLibraryHasMore = hasMore)
    }

    private fun applyDeletedMedia(item: CameraMediaItem) = transitionMediaState { current ->
        current.withDeletedMedia(item)
    }

    private inline fun transitionMediaState(transform: (CameraUiState) -> CameraUiState) {
        while (true) {
            val current = _uiState.value
            val updated = transform(current)
            if (_uiState.compareAndSet(current, updated)) {
                if (current.mediaStreamSource !== updated.mediaStreamSource) current.mediaStreamSource?.close()
                return
            }
        }
    }

    private fun cancelMediaThumbnailLoads() {
        mediaThumbnailGeneration += 1
        mediaThumbnailJobs.values.forEach(Job::cancel)
        mediaThumbnailJobs.clear()
    }

    private fun visibleMediaIds(): Set<String> {
        val state = _uiState.value
        return buildSet {
            captureReviewCandidates?.takeIf {
                it.sessionGeneration == cameraSessionGeneration && it.connection === state.info
            }?.let { addAll(it.ids) }
            state.mediaItems.forEach { add(it.id) }
            state.captureReviewItem?.let { add(it.id) }
        }
    }

    fun retryCaptureReview() {
        val state = _uiState.value
        val attempt = pendingCaptureReview ?: return
        if (!state.connected || state.previewMode || state.isBusy(CameraOperation.CAPTURE) || state.isBusy(CameraOperation.MEDIA) ||
            state.captureReviewLoading || !state.captureReviewStatus.canRetry ||
            attempt.sessionGeneration != cameraSessionGeneration) return
        refreshCaptureReview()
    }

    private fun refreshCaptureReview() {
        val state = _uiState.value
        if (!state.connected || state.previewMode || !state.supports(CameraFeature.MEDIA_BROWSER)) return
        val attempt = pendingCaptureReview
        val sessionGeneration = cameraSessionGeneration
        val connection = state.info
        val generation = beginCaptureReviewLoad()
        fun stillOwnsReview() = generation == captureReviewGeneration &&
            sessionGeneration == cameraSessionGeneration && _uiState.value.info === connection
        var terminalResultAccepted = false
        captureReviewJob = viewModelScope.launch {
            val result = awaitCaptureReviewItem(
                previousIds = attempt?.previousIds.orEmpty(),
                retryDelaysMillis = CAPTURE_REVIEW_RETRY_DELAYS_MILLIS,
                intent = attempt?.intent ?: MediaReviewIntent.RECENT,
            ) {
                // Keep the bounded read-only review queued while an existing media operation owns I/O.
                _uiState.first { !it.isBusy(CameraOperation.MEDIA) }
                coroutineContext.ensureActive()
                repository.listMedia(CAPTURE_REVIEW_REQUEST_ITEMS).also { items ->
                    coroutineContext.ensureActive()
                    if (stillOwnsReview()) {
                        // Remember only the existing bounded listing; never issue a baseline scan.
                        captureReviewCandidates = CaptureReviewCandidates(
                            items.take(CAPTURE_REVIEW_REQUEST_ITEMS).mapTo(mutableSetOf()) { it.id },
                            sessionGeneration,
                            connection,
                        )
                    }
                }
            }
            coroutineContext.ensureActive()
            if (!stillOwnsReview()) return@launch
            terminalResultAccepted = true
            when (result) {
                is CaptureReviewResult.Found -> publishCaptureReview(result.item, generation, result.evidence)
                is CaptureReviewResult.NotReady, is CaptureReviewResult.ReadFailed -> _uiState.update { it.copy(
                    captureReviewLoading = false,
                    captureReviewStatus = when {
                        attempt == null -> CaptureReviewStatus.IDLE
                        result is CaptureReviewResult.ReadFailed -> CaptureReviewStatus.READ_FAILED
                        else -> CaptureReviewStatus.NOT_READY
                    },
                    captureReviewObservation = result.observation,
                    captureReviewEvidence = result.evidence,
                ) }
            }
        }.also { job ->
            job.invokeOnCompletion {
                // Retirement never publishes partial evidence. Restore the prior terminal
                // presentation for an unchanged owner, including a zero-read/pre-launch retry.
                // A replaced owner must not alter its successor's state.
                if (job.isCancelled && stillOwnsReview()) {
                    _uiState.update { it.copy(
                        captureReviewLoading = false,
                        // Optional thumbnail cancellation cannot undo an accepted FOUND result.
                        captureReviewStatus = if (terminalResultAccepted) it.captureReviewStatus else state.captureReviewStatus,
                    ) }
                }
                if (captureReviewJob === job) captureReviewJob = null
            }
        }
    }

    private fun refreshCaptureReview(items: List<CameraMediaItem>) {
        if (captureReviewJob?.isActive == true) return
        val attempt = pendingCaptureReview
        val selected = selectCaptureReviewItem(items, attempt?.intent ?: MediaReviewIntent.RECENT, attempt?.previousIds.orEmpty())
        // Event/gallery refreshes must not turn the old image into this attempt's result.
        if (attempt != null && selected == null) {
            // A successful event/gallery listing resolves read failure, not the pending capture.
            _uiState.update { it.copy(
                captureReviewStatus = CaptureReviewStatus.NOT_READY,
                captureReviewObservation = CaptureReviewObservation.NO_CANDIDATE_OBSERVED,
                captureReviewEvidence = CaptureReviewEvidence(1, 0, CaptureReviewRead.SUCCESS),
            ) }
            return
        }
        if (selected == null) {
            cancelCaptureReview()
            _uiState.update {
                it.copy(captureReviewItem = null, captureReviewThumbnail = null, captureReviewLoading = false)
            }
            return
        }
        val generation = beginCaptureReviewLoad()
        captureReviewJob = viewModelScope.launch { publishCaptureReview(selected, generation) }.also { job ->
            job.invokeOnCompletion {
                if (captureReviewJob === job) captureReviewJob = null
            }
        }
    }

    private fun beginCaptureReviewLoad(): Long {
        captureReviewGeneration += 1
        captureReviewJob?.cancel()
        captureReviewJob = null
        _uiState.update { it.copy(
            captureReviewLoading = true,
            captureReviewStatus = if (pendingCaptureReview != null) CaptureReviewStatus.SEARCHING else CaptureReviewStatus.IDLE,
        ) }
        return captureReviewGeneration
    }

    private suspend fun publishCaptureReview(
        item: CameraMediaItem,
        generation: Long,
        evidence: CaptureReviewEvidence = CaptureReviewEvidence(1, 0, CaptureReviewRead.SUCCESS),
    ) {
        if (generation != captureReviewGeneration) return
        pendingCaptureReview = null
        val existing = _uiState.value
        val canLoadThumbnail = item.isVisualMedia && existing.supports(CameraFeature.MEDIA_THUMBNAIL)
        _uiState.update {
            it.copy(
                captureReviewItem = item,
                captureReviewStatus = CaptureReviewStatus.IDLE,
                captureReviewObservation = CaptureReviewObservation.FOUND,
                captureReviewEvidence = evidence,
                captureReviewThumbnail = it.captureReviewThumbnail.takeIf { _ ->
                    item.isVisualMedia && it.captureReviewItem?.id == item.id
                },
                captureReviewLoading = canLoadThumbnail,
            )
        }
        if (!canLoadThumbnail) return
        val thumbnail = try {
            fetchMediaThumbnailBitmap(item)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
        if (generation != captureReviewGeneration) return
        _uiState.update {
            if (it.captureReviewItem?.id == item.id) {
                it.copy(captureReviewThumbnail = thumbnail, captureReviewLoading = false)
            } else {
                it
            }
        }
    }

    private fun cancelCaptureReview() {
        captureReviewGeneration += 1
        captureReviewJob?.cancel()
        captureReviewJob = null
        pendingCaptureReview = null
        // An ordinary (including rejected) shutter attempt must not forget known session media.
        captureReviewCandidates = captureReviewCandidates?.takeIf {
            it.sessionGeneration == cameraSessionGeneration && it.connection === _uiState.value.info
        }
        _uiState.update { it.copy(
            captureReviewLoading = false,
            captureReviewStatus = CaptureReviewStatus.IDLE,
            captureReviewObservation = CaptureReviewObservation.UNREAD,
            captureReviewEvidence = CaptureReviewEvidence(),
            captureStatusReadbackFailed = false,
        ) }
    }

    private suspend fun fetchMediaThumbnailBitmap(item: CameraMediaItem): android.graphics.Bitmap {
        require(item.isVisualMedia) { "Thumbnail decoding requires an unambiguous photo or video item." }
        var lastFailure: Exception? = null
        repeat(MEDIA_THUMBNAIL_RETRY_DELAYS_MILLIS.size + 1) { attempt ->
            try {
                val thumbnail = repository.mediaThumbnail(item)
                require(thumbnail.item.hasSameThumbnailSource(item)) {
                    "Camera changed the media identity or type before thumbnail decoding."
                }
                return withContext(Dispatchers.Default) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(thumbnail.bytes, 0, thumbnail.bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                    BitmapFactory.decodeByteArray(
                        thumbnail.bytes,
                        0,
                        thumbnail.bytes.size,
                        BitmapFactory.Options().apply {
                            inSampleSize = mediaThumbnailSampleSize(bounds.outWidth, bounds.outHeight)
                        },
                    )
                } ?: error("Camera returned an undecodable thumbnail for ${item.name}.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                lastFailure = exception
                if (
                    isRetryableMediaThumbnailFailure(exception) &&
                    attempt < MEDIA_THUMBNAIL_RETRY_DELAYS_MILLIS.size
                ) {
                    delay(MEDIA_THUMBNAIL_RETRY_DELAYS_MILLIS[attempt])
                } else {
                    throw exception
                }
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun resetMediaLibraryLoad() {
        invalidateMediaLibraryLoad(MediaLibraryLoadStatus.NOT_LOADED)
    }

    private fun invalidateMediaLibraryLoad(status: MediaLibraryLoadStatus) {
        if (_uiState.value.transport == CameraTransport.DESKTOP_BRIDGE) mediaEventDemandGeneration++
        mediaLibraryGeneration += 1
        mediaLibraryJob?.cancel()
        mediaLibraryJob = null
        eventMediaJob?.cancel()
        eventMediaJob = null
        _uiState.update {
            it.copy(
                mediaLibraryLoading = false,
                mediaLibraryLoadStatus = status,
            )
        }
    }

    private fun closeMediaStream() {
        _uiState.value.mediaStreamSource?.close()
        _uiState.update { it.copy(mediaStreamSource = null) }
    }

    private fun recordFrame(
        current: LiveViewDiagnostics,
        nowMillis: Long,
        frameBytes: Int? = current.frameBytes,
        contentType: String? = current.contentType,
        sourceUrl: String? = current.sourceUrl,
    ): LiveViewDiagnostics {
        frameTimesMillis.addLast(nowMillis)
        while (frameTimesMillis.size > FPS_WINDOW_SIZE) frameTimesMillis.removeFirst()
        return LiveViewDiagnostics(
            observedFps = rollingFps(frameTimesMillis.toList()),
            frameBytes = frameBytes,
            contentType = contentType,
            sourceUrl = sourceUrl,
            lastFrameAtMillis = nowMillis,
        )
    }

    private fun configureNativeLiveViewSession(session: NativeLiveViewSession?, fps: Int) {
        session ?: return
        val generation = liveViewGeneration
        val owner = repository.liveViewOwner()
        session.setTargetFps(fps)
        session.setRenderingEnabled(appInForeground && _uiState.value.liveViewAutoRefresh)
        session.setListener { event ->
            viewModelScope.launch {
                if (generation != liveViewGeneration || !repository.ownsLiveView(owner)) return@launch
                if (_uiState.value.nativeLiveViewSession !== session && repository.nativeLiveViewSession() !== session) {
                    return@launch
                }
                // Terminal evidence has priority even while backgrounded/interlocked. Retirement
                // retains the exact Stop owner; reconciliation still obeys camera control interlocks.
                if (event is NativeLiveViewEvent.Failed) {
                    retireFailedLiveView(generation, owner, event.message)
                    return@launch
                }
                if (_uiState.value.maintenanceSuspended || !appInForeground || !_uiState.value.liveViewAutoRefresh) return@launch
                when (event) {
                    is NativeLiveViewEvent.AudioStatusChanged -> _uiState.update {
                        // A queued old-epoch callback must not re-enable or overwrite a newer manual attempt.
                        it.copy(liveViewAudioStatus = session.audioStatus)
                    }

                    is NativeLiveViewEvent.FrameRendered -> _uiState.update {
                        it.copy(
                            liveViewCoordinateRefreshPending = false,
                            liveViewAspectRatio = event.width.toFloat() / event.height.coerceAtLeast(1),
                            liveViewDiagnostics = recordFrame(
                                current = it.liveViewDiagnostics,
                                nowMillis = event.atMillis,
                                frameBytes = event.encodedBytes,
                                contentType = session.contentType,
                                sourceUrl = session.sourceUrl,
                            ),
                            error = if (it.errorOperation == CameraOperation.LIVE_VIEW) null else it.error,
                            errorOperation = it.errorOperation.takeUnless { operation -> operation == CameraOperation.LIVE_VIEW },
                        )
                    }

                    is NativeLiveViewEvent.VideoSizeChanged -> if (event.width > 0 && event.height > 0) {
                        _uiState.update { it.copy(liveViewAspectRatio = event.width.toFloat() / event.height) }
                    }

                    is NativeLiveViewEvent.Failed -> retireFailedLiveView(generation, owner, event.message)
                }
            }
        }
    }

    private fun detachNativeLiveViewListener() {
        _uiState.value.nativeLiveViewSession?.setListener(null)
    }

    private fun resetFrameMetrics() {
        frameTimesMillis.clear()
    }

    private companion object {
        const val PREFERENCES_NAME = "camera_connection"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_BRIDGE_BASE_URL = "bridge_base_url"
        const val KEY_CONNECTION_TARGET = "connection_target"
        const val KEY_FOREGROUND_IMPORT_CLEANUP_WARNING = "foreground_import_cleanup_unconfirmed"
        const val CAPTURE_FLASH_MILLIS = 120L
        const val FOCUS_FEEDBACK_MILLIS = 1_200L
        const val FPS_WINDOW_SIZE = 30
        const val MEDIA_UPLOAD_BUFFER_BYTES = 64 * 1024
        const val MAX_MEDIA_UPLOAD_BYTES = MAX_PTP_OBJECT_BYTES
        const val MAX_MEDIA_THUMBNAIL_CACHE_ITEMS = 96
        val EVENT_RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 5_000L)
        val CAPABILITY_EVIDENCE_OPERATIONS = setOf(
            CameraOperation.CONNECT,
            CameraOperation.SETTING,
            CameraOperation.CLOCK,
            CameraOperation.CAPTURE,
            CameraOperation.RECORDING,
            CameraOperation.FOCUS,
            CameraOperation.LIVE_VIEW,
            CameraOperation.MEDIA,
        )
    }
}

@Suppress("DEPRECATION")
private fun Context.installedVersionName(): String =
    packageManager.getPackageInfo(packageName, 0).versionName
        ?.takeIf(String::isNotBlank)
        ?: error("Android package version is unavailable.")
