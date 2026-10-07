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
import dev.openeos.control.R
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.AutofocusReleaseException
import dev.openeos.control.data.ShutterReleaseException
import dev.openeos.control.data.CaptureStatusReadbackException
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraFileNamingField
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CameraSession
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryState
import dev.openeos.control.data.DownloadHistoryStore
import dev.openeos.control.data.DownloadHistoryWarning
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
import dev.openeos.control.data.ConnectionFailureReason
import dev.openeos.control.data.connectionFailureReason
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
    uniqueItems.forEachIndexed { index, item ->
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
            exception.printStackTrace()
            failures += item.name
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
        ?: error("Android could not create $filename in the selected folder.")
}

internal fun mergeRecentMedia(
    recent: List<CameraMediaItem>,
    existing: List<CameraMediaItem>,
): List<CameraMediaItem> {
    val recentIds = recent.mapTo(hashSetOf(), CameraMediaItem::id)
    return recent + existing.filterNot { it.id in recentIds }
}

internal fun isRetryableMediaThumbnailFailure(exception: Exception): Boolean = exception is IOException

private fun selectReviewCandidate(
    items: List<CameraMediaItem>,
    previousIds: Set<String>,
    videosOnly: Boolean,
): CameraMediaItem? = selectCaptureReviewItem(items.filter {
    it.id !in previousIds && (!videosOnly || it.isVideo)
})

internal suspend fun awaitCaptureReviewItem(
    previousIds: Set<String>,
    retryDelaysMillis: LongArray,
    videosOnly: Boolean = false,
    loadRecentMedia: suspend () -> List<CameraMediaItem>,
): CameraMediaItem? {
    for (attempt in 0..retryDelaysMillis.size) {
        val candidate = try {
            selectReviewCandidate(loadRecentMedia(), previousIds, videosOnly)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
        if (candidate != null) {
            return candidate
        }
        if (attempt < retryDelaysMillis.size) delay(retryDelaysMillis[attempt])
    }
    return null
}

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
    private val _downloadHistoryState = MutableStateFlow(DownloadHistoryState())
    val downloadHistoryState: StateFlow<DownloadHistoryState> = _downloadHistoryState.asStateFlow()
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
    private var cameraSessionGeneration = 0L
    private val mediaPickerRequests = CameraMediaPickerRequests()
    private var cameraStateRevision = 0L
    private val cameraOperationJobs = mutableMapOf<CameraOperation, Job>()
    private var disconnectJob: Job? = null
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
    private var mediaLibraryJob: Job? = null
    private var mediaLibraryGeneration = 0L
    private var captureReviewJob: Job? = null
    private var captureReviewGeneration = 0L
    private data class CaptureReviewAttempt(
        val previousIds: Set<String>,
        val sessionGeneration: Long,
        val videosOnly: Boolean = false,
    )
    private var pendingCaptureReview: CaptureReviewAttempt? = null
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
        val store = downloadHistoryStore ?: return
        viewModelScope.launch { store.clear() }
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

    fun importCubeLut(name: String, text: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { parseCubeLut(text, name) } }
            result.fold(
                onSuccess = { lut ->
                    _uiState.update {
                        it.copy(
                            monitorSettings = it.monitorSettings.copy(cubeLut = lut),
                            error = null,
                            errorOperation = null,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            error = "3D LUT: ${error.message ?: error::class.java.simpleName}",
                            errorOperation = CameraOperation.LIVE_VIEW,
                        )
                    }
                },
            )
        }
    }

    fun clearCubeLut() = updateMonitorSettings { copy(cubeLut = null) }

    fun reportCubeLutError(message: String) {
        _uiState.update {
            it.copy(error = "3D LUT: $message", errorOperation = CameraOperation.LIVE_VIEW)
        }
    }

    private fun updateMonitorSettings(update: LiveViewMonitorSettings.() -> LiveViewMonitorSettings) {
        _uiState.update { it.copy(monitorSettings = it.monitorSettings.update()) }
    }

    fun openSettingPicker(picker: SettingPicker) {
        stopHeldAutofocus()
        _uiState.update { it.copy(activeSettingPicker = picker) }
    }

    fun closeSettingPicker() = _uiState.update { it.copy(activeSettingPicker = null) }

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
                status = session.status,
                shutterReleaseUnconfirmed = session.status.shutterReleaseUnconfirmed == true,
                capabilities = session.capabilities,
                networkDiagnostics = session.networkDiagnostics,
                liveViewFrameUrl = session.liveViewFrameUrl,
                liveViewBitmap = null,
                nativeLiveViewSession = session.nativeLiveViewSession,
                liveViewFrameRateFps = supportedFps,
                liveViewSize = session.liveViewRequest.size,
                liveViewSource = activeSource,
                liveViewMagnification = session.capabilities.liveView.currentMagnification,
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

    fun disconnect() {
        handoffOwner.cancelUnlaunched(CameraImportHandoffOrigin.CAMERA)
        val foregroundImport = stopForegroundImport(ForegroundImportStopReason.SESSION_CHANGED)
        val previousState = _uiState.value
        val shutterWarning = previousState.shutterDisconnectWarning || previousState.shutterReleaseUnconfirmed ||
            previousState.bulbExposureActive || CameraOperation.CAPTURE in previousState.pendingOperations ||
            CameraOperation.SHUTTER_RELEASE in previousState.pendingOperations
        cameraSessionGeneration += 1
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
                )
            }
            return
        }
        val job = viewModelScope.launch(NonCancellable, start = CoroutineStart.LAZY) {
            previousDisconnect?.join()
            operationJobs.forEach { it.join() }
            uploadJob?.join()
            uploadCleanupJob?.join()
            focusJob?.join()
            repository.disconnect()
        }
        disconnectJob = job
        job.invokeOnCompletion { if (disconnectJob === job) disconnectJob = null }
        job.start()
        _uiState.update {
            it.withClearedSession(baseUrl = it.baseUrl, error = null).copy(
                pendingOperations = emptySet(), shutterDisconnectWarning = shutterWarning,
            )
        }
    }

    fun refresh() = runCamera(CameraOperation.STATUS) {
        if (_uiState.value.previewMode) return@runCamera
        val (status, capabilities) = readCameraStateSnapshot()
        val networkDiagnostics = repository.refreshNetworkDiagnostics()
        val captureMode = captureModeFrom(capabilities)
        _uiState.update {
            it.copy(
                status = status,
                captureStatusReadbackFailed = false,
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
        if (!foreground) stopForegroundImport(ForegroundImportStopReason.BACKGROUND)
        if (!foreground) stopHeldAutofocus()
        if (appInForeground == foreground) return
        appInForeground = foreground
        if (!foreground) setRtpAudioEnabled(false)
        queueLiveViewReconciliation()
    }

    private fun queueLiveViewReconciliation(restart: Boolean = false) {
        invalidateCameraFocusInfo()
        if (!appInForeground || !_uiState.value.liveViewAutoRefresh) {
            liveViewGeneration += 1
            stopLiveViewLoop()
            repository.setNativeLiveViewRenderingEnabled(false)
        } else if (restart && _uiState.value.nativeLiveViewSession == null && liveViewFrameReads.any { !it.stopped }) {
            // Release an in-flight frame before waiting for the transition mutex. Do not pause
            // native rendering or bypass a camera-command interlock before reconciliation.
            liveViewGeneration += 1
            cancelLiveViewFrameReads()
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
            _uiState.update { it.copy(liveViewAudioStatus = it.liveViewAudioStatus.copy(enabled = false)) }
            return
        }
        if (state.liveViewSource != LiveViewSource.CCAPI_RTP || !state.liveViewAudioStatus.available) return
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

    fun restartLiveView() = queueLiveViewReconciliation(restart = true)

    private suspend fun reconcileLiveView(
        restart: Boolean = false,
        generation: Long = cameraSessionGeneration,
    ) = liveViewTransitionMutex.withLock {
        coroutineContext.ensureActive()
        if (generation != cameraSessionGeneration) return@withLock
        val state = _uiState.value
        if (
            !state.connected || state.previewMode || !state.supports(CameraFeature.LIVE_VIEW)
        ) return@withLock
        // Do not change the camera's remote-view session in the middle of a bulb exposure.
        if (state.bulbExposureActive || state.shutterReleaseUnconfirmed) return@withLock
        if (state.pendingOperations.any { it in LIVE_VIEW_INTERLOCK_OPERATIONS }) return@withLock
        val enabled = appInForeground && state.liveViewAutoRefresh && state.liveViewTemperatureAllowed
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
            val effectiveRequest = repository.setLiveViewEnabled(enabled, restart)
            coroutineContext.ensureActive()
            if (generation != cameraSessionGeneration || _uiState.value.info !== state.info) return@withLock
            _uiState.update {
                it.copy(
                    error = if (it.errorOperation == CameraOperation.LIVE_VIEW) null else it.error,
                    errorOperation = it.errorOperation.takeUnless { operation -> operation == CameraOperation.LIVE_VIEW },
                )
            }
            if (!enabled || !appInForeground || !_uiState.value.liveViewAutoRefresh || !_uiState.value.connected) return@withLock
            val capabilities = repository.refreshCapabilities()
            coroutineContext.ensureActive()
            if (generation != cameraSessionGeneration || _uiState.value.info !== state.info) return@withLock
            val nativeSession = repository.nativeLiveViewSession()
            configureNativeLiveViewSession(nativeSession, _uiState.value.liveViewFrameRateFps)
            _uiState.update {
                it.copy(
                    capabilities = capabilities,
                    nativeLiveViewSession = nativeSession,
                    liveViewSource = nativeSession?.source ?: it.liveViewSource,
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
            if (generation == cameraSessionGeneration && _uiState.value.info === state.info) throw exception
        } finally {
            if (generation == cameraSessionGeneration && _uiState.value.info === state.info) {
                cameraStateRevision += 1
                _uiState.update { it.copy(pendingOperations = it.pendingOperations - CameraOperation.LIVE_VIEW) }
            }
        }
    }

    fun setIso(value: String) {
        if (updatePreviewExposure { it.copy(iso = value) }) return
        updateStatus(CameraOperation.SETTING) { repository.setIso(value) }
    }

    fun setShutter(value: String) {
        if (updatePreviewExposure { it.copy(shutter = value) }) return
        updateStatus(CameraOperation.SETTING) { repository.setShutter(value) }
    }

    fun setAperture(value: String) {
        if (updatePreviewExposure { it.copy(aperture = value) }) return
        updateStatus(CameraOperation.SETTING) { repository.setAperture(value) }
    }

    fun setWhiteBalance(value: String) {
        if (updatePreviewExposure { it.copy(whiteBalance = value) }) return
        updateStatus(CameraOperation.SETTING) { repository.setWhiteBalance(value) }
    }

    fun setCameraSetting(key: String, value: String) {
        if (_uiState.value.isBusy(CameraOperation.SETTING)) return
        val selectedCaptureMode = when {
            key.isMovieModeKey() && value.equals("on", ignoreCase = true) -> CaptureMode.VIDEO
            key.isMovieModeKey() && value.equals("off", ignoreCase = true) -> CaptureMode.PHOTO
            key.isShootingModeKey() -> captureModeForShootingValue(value)
            else -> null
        }
        runCamera(CameraOperation.SETTING) {
            if (_uiState.value.previewMode) {
                if (key.isShootingModeKey() && selectedCaptureMode == CaptureMode.PHOTO) {
                    lastPhotoShootingMode = value
                }
                _uiState.update { state ->
                    state.copy(
                        captureMode = selectedCaptureMode ?: state.captureMode,
                        capabilities = state.capabilities?.copy(
                            advancedSettings = state.capabilities.advancedSettings.map { setting ->
                                if (setting.key == key) setting.copy(value = value) else setting
                            },
                        ),
                    )
                }
                return@runCamera
            }
            val revision = cameraStateRevision
            val response = repository.setCameraSetting(key, value)
            val capabilities = repository.refreshCapabilities()
            val status = latestCameraStatus(response, revision)
            val captureMode = if (key.isCaptureModeKey()) {
                captureModeFrom(capabilities)
            } else {
                selectedCaptureMode ?: captureModeFrom(capabilities)
            }
            if (key.isShootingModeKey() && selectedCaptureMode == CaptureMode.PHOTO) {
                lastPhotoShootingMode = value
            }
            _uiState.update {
                it.copy(
                    status = status,
                    capabilities = capabilities,
                    captureMode = captureMode ?: it.captureMode,
                )
            }
            if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
        }
    }

    fun syncCameraClock() = runCamera(CameraOperation.CLOCK) {
        val state = _uiState.value
        if (state.previewMode || !state.supports(CameraFeature.CAMERA_CLOCK_SYNC)) return@runCamera
        val revision = cameraStateRevision
        val status = latestCameraStatus(repository.syncCameraClock(), revision)
        _uiState.update {
            it.copy(
                status = status,
                lastClockSyncAtMillis = System.currentTimeMillis(),
            )
        }
    }

    fun createDirectory(name: String) = runCamera(CameraOperation.DIRECTORY) {
        val state = _uiState.value
        if (state.previewMode || !state.supports(CameraFeature.DIRECTORY_CONTROL)) return@runCamera
        val created = repository.createDirectory(name)
        val capabilities = repository.refreshCapabilities()
        _uiState.update {
            it.copy(
                capabilities = capabilities,
                lastCreatedDirectoryName = created,
            )
        }
    }

    fun setFileNaming(field: CameraFileNamingField, value: String) = runCamera(CameraOperation.SETTING) {
        val state = _uiState.value
        if (
            state.previewMode ||
            !state.supports(CameraFeature.FILE_NAMING_CONTROL) ||
            state.capabilities?.fileNaming?.accepts(field, value) != true
        ) return@runCamera
        val updated = repository.setFileNaming(field, value)
        _uiState.update { current ->
            current.copy(
                capabilities = current.capabilities?.copy(fileNaming = updated),
            )
        }
    }

    fun sleepCamera() {
        val state = _uiState.value
        if (
            !state.connected ||
            state.previewMode ||
            !state.supports(CameraFeature.CAMERA_SLEEP) ||
            state.status?.recording == true ||
            state.bulbExposureActive ||
            state.busy
        ) return

        stopLiveViewLoop()
        stopEventPollingLoop()
        detachNativeLiveViewListener()
        cancelMediaDownload()
        val uploadJob = mediaUploadJob
        mediaUploadJob = null
        uploadJob?.cancel()
        cancelMediaThumbnailLoads()
        runCamera(
            operation = CameraOperation.POWER,
            onError = {
                if (_uiState.value.connected) {
                    startEventPollingIfSupported()
                    restartLiveView()
                }
            },
        ) {
            uploadJob?.join()
            repository.sleepCamera()
            runCatching { repository.disconnect() }
            closeMediaStream()
            resetFrameMetrics()
            lastPhotoShootingMode = null
            _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        }
    }

    fun cleanSensor(autoPowerOff: Boolean) {
        val state = _uiState.value
        if (
            !state.connected ||
            state.previewMode ||
            !state.supports(CameraFeature.SENSOR_CLEANING) ||
            state.status?.recording == true ||
            state.bulbExposureActive ||
            state.busy
        ) return

        val restoreLiveView = state.supports(CameraFeature.LIVE_VIEW)
        var restoreSessionWork = false
        stopLiveViewLoop()
        stopEventPollingLoop()
        detachNativeLiveViewListener()
        runCamera(
            operation = CameraOperation.MAINTENANCE,
            onError = { restoreSessionWork = true },
            afterFinally = {
                if (restoreSessionWork && _uiState.value.connected) {
                    startEventPollingIfSupported()
                    if (restoreLiveView) restartLiveView()
                }
            },
        ) {
            repository.cleanSensor(autoPowerOff)
            if (autoPowerOff) {
                runCatching { repository.disconnect() }
                closeMediaStream()
                resetFrameMetrics()
                lastPhotoShootingMode = null
                _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
            } else {
                val (status, capabilities) = readCameraStateSnapshot()
                _uiState.update { it.copy(status = status, capabilities = capabilities) }
                restoreSessionWork = true
            }
        }
    }

    fun toggleRecording() {
        // Keep the user's command direction even if an event changes displayed state while it runs.
        val wasRecording = _uiState.value.status?.recording == true
        if (wasRecording && foregroundImportOwner?.operation?.isActive == true) {
            stopForegroundImport(ForegroundImportStopReason.USER)
        }
        runCamera(CameraOperation.RECORDING) {
            val generation = cameraSessionGeneration
            val connection = _uiState.value.info
            val previousReviewIds = visibleMediaIds()
            val revision = cameraStateRevision
            val response = if (_uiState.value.previewMode) {
                _uiState.value.status!!.copy(recording = !wasRecording)
            } else {
                repository.toggleRecording(wasRecording)
            }
            coroutineContext.ensureActive()
            if (generation != cameraSessionGeneration || _uiState.value.info !== connection) return@runCamera
            val status = if (_uiState.value.previewMode) response else latestCameraStatus(response, revision)
            coroutineContext.ensureActive()
            if (generation != cameraSessionGeneration || _uiState.value.info !== connection) return@runCamera
            _uiState.update { it.copy(status = status) }
            if (!_uiState.value.previewMode && wasRecording && response.recording == false && status.recording == false) {
                pendingCaptureReview = CaptureReviewAttempt(previousReviewIds, generation, videosOnly = true)
                refreshCaptureReview()
            }
            if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
        }
    }

    fun setShutterAutofocus(enabled: Boolean) {
        val state = _uiState.value
        if (state.canChangeShutterAutofocus() && (enabled || state.capabilities?.shutterAutofocusSupported == true)) {
            _uiState.update { it.copy(shutterAutofocus = enabled) }
        }
    }

    fun captureStill() = runCamera(CameraOperation.CAPTURE) {
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
        pendingCaptureReview = CaptureReviewAttempt(previousReviewIds, generation)
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

    fun retryShutterRelease() {
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
        if (status.shutterReleaseUnconfirmed == true) markShutterReleaseUnconfirmed()
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
        runCamera(
            operation = CameraOperation.FOCUS,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            repository.driveFocus(direction, step)
            _uiState.update { it.copy(focusFeedback = FocusFeedback.ACCEPTED) }
            clearFocusFeedbackAfter(FocusFeedback.ACCEPTED)
            if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
        }
    }

    fun setLiveViewMagnification(magnification: LiveViewMagnification) {
        val state = _uiState.value
        if (state.autofocusHoldState != AutofocusHoldState.IDLE) return
        if (
            state.captureMode != CaptureMode.PHOTO ||
            !state.supports(CameraFeature.LIVE_VIEW_MAGNIFICATION) ||
            magnification !in state.capabilities?.liveView?.magnifications.orEmpty()
        ) return
        if (state.previewMode) {
            _uiState.update { it.copy(liveViewMagnification = magnification) }
            return
        }
        runCamera(CameraOperation.LIVE_VIEW) {
            val result = repository.setLiveViewMagnification(magnification)
            if (result.ok) {
                _uiState.update { it.copy(liveViewMagnification = result.magnification) }
                if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
            }
        }
    }

    fun refreshMedia() {
        val initialState = _uiState.value
        if (!initialState.connected || initialState.previewMode || initialState.mediaLibraryLoading ||
            initialState.isBusy(CameraOperation.MEDIA)
        ) return
        val scope = initialState.mediaLibraryScope
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
                if (generation != mediaLibraryGeneration) return@launch
                val batch = items.toMediaLibraryBatch(scope)
                val capabilities = runCatching { repository.refreshCapabilities() }.getOrNull()
                if (generation != mediaLibraryGeneration) return@launch
                _uiState.update {
                    it.copy(
                        mediaItems = batch.items,
                        mediaLibraryHasMore = batch.hasMore,
                        mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE,
                        capabilities = capabilities ?: it.capabilities,
                        lastDownloadedMediaName = null,
                        lastDownloadLocation = null,
                        lastUploadedMediaName = null,
                        lastDeletedMediaName = null,
                        lastMediaBatchResult = null,
                    )
                }
                refreshCapabilityEvidence()
            } catch (exception: CancellationException) {
                if (generation == mediaLibraryGeneration) {
                    _uiState.update {
                        it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.CANCELLED)
                    }
                }
                throw exception
            } catch (exception: Exception) {
                exception.printStackTrace()
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
        if (item.previewAvailable || item.isVideo) openMediaPreview(item)
    }

    fun cancelMediaLibraryLoad() {
        if (!_uiState.value.mediaLibraryLoading) return
        invalidateMediaLibraryLoad(MediaLibraryLoadStatus.CANCELLED)
    }

    fun loadMediaThumbnail(item: CameraMediaItem) {
        val state = _uiState.value
        state.mediaThumbnails[item.id]?.let {
            _uiState.update { current ->
                if (item.id !in current.mediaThumbnails) return@update current
                current.copy(mediaThumbnails = touchMediaCacheEntry(current.mediaThumbnails, item.id))
            }
            return
        }
        if (
            state.previewMode ||
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
                    _uiState.value.mediaItems.none { current -> current.id == item.id }
                ) return@launch
                _uiState.update { current ->
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
        mediaThumbnailJobs.remove(item.id)?.cancel()
        _uiState.update { it.copy(mediaThumbnailLoadingIds = it.mediaThumbnailLoadingIds - item.id) }
    }

    fun openMediaPreview(item: CameraMediaItem) {
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
        state.mediaStreamSource?.close()
        _uiState.update {
            it.copy(
                mediaPreviewItem = item,
                mediaPreviewBytes = null,
                mediaPreviewLoading = true,
                mediaStreamSource = null,
            )
        }
        val generation = cameraSessionGeneration
        val connection = state.info
        launchCameraOperation(
            operation = CameraOperation.MEDIA,
            cancelMediaReads = false,
            onError = {
                _uiState.update { current ->
                    if (generation == cameraSessionGeneration && current.info === connection &&
                        current.mediaPreviewItem?.id == item.id) current.copy(mediaPreviewLoading = false) else current
                }
            },
        ) {
            val stream = if (isVideo) repository.openMediaStream(item) else null
            val preview = if (isVideo) null else repository.mediaPreview(item)
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
        _uiState.value.mediaStreamSource?.close()
        _uiState.update {
            it.copy(
                mediaPreviewItem = null,
                mediaPreviewBytes = null,
                mediaPreviewLoading = false,
                mediaStreamSource = null,
            )
        }
    }

    fun loadMediaInfo(item: CameraMediaItem) {
        val state = _uiState.value
        if (state.previewMode || state.isBusy(CameraOperation.MEDIA) || !state.supports(CameraFeature.MEDIA_BROWSER)) return
        runCamera(CameraOperation.MEDIA) {
            val updated = repository.mediaInfo(item)
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
        item,
        CameraFeature.MEDIA_PROTECT,
        previewUpdate = { it.copy(protected = enabled) },
    ) { repository.setMediaProtection(item, enabled) }

    fun setMediaArchived(item: CameraMediaItem, enabled: Boolean) = updateMediaMetadata(
        item,
        CameraFeature.MEDIA_ARCHIVE,
        previewUpdate = { it.copy(archived = enabled) },
    ) { repository.setMediaArchived(item, enabled) }

    fun setMediaRating(item: CameraMediaItem, rating: Int) {
        if (rating !in 0..5) return
        updateMediaMetadata(
            item,
            CameraFeature.MEDIA_RATING,
            previewUpdate = { it.copy(rating = rating) },
        ) { repository.setMediaRating(item, rating) }
    }

    fun setMediaRotation(item: CameraMediaItem, degrees: Int) {
        if (degrees !in setOf(0, 90, 180, 270)) return
        updateMediaMetadata(
            item,
            CameraFeature.MEDIA_ROTATE,
            previewUpdate = { it.copy(rotationDegrees = degrees) },
        ) { repository.setMediaRotation(item, degrees) }
    }

    fun setMediaProtectionBatch(items: List<CameraMediaItem>, enabled: Boolean) = updateMediaMetadataBatch(
        items = items,
        feature = CameraFeature.MEDIA_PROTECT,
        operation = if (enabled) MediaBatchOperation.PROTECT else MediaBatchOperation.UNPROTECT,
        previewUpdate = { it.copy(protected = enabled) },
    ) { item -> repository.setMediaProtection(item, enabled) }

    fun setMediaArchivedBatch(items: List<CameraMediaItem>, enabled: Boolean) = updateMediaMetadataBatch(
        items = items,
        feature = CameraFeature.MEDIA_ARCHIVE,
        operation = if (enabled) MediaBatchOperation.ARCHIVE else MediaBatchOperation.UNARCHIVE,
        previewUpdate = { it.copy(archived = enabled) },
    ) { item -> repository.setMediaArchived(item, enabled) }

    fun setMediaRatingBatch(items: List<CameraMediaItem>, rating: Int) {
        if (rating !in 0..5) return
        updateMediaMetadataBatch(
            items = items,
            feature = CameraFeature.MEDIA_RATING,
            operation = MediaBatchOperation.RATE,
            previewUpdate = {
                check(it.ratingWritable != false) { "${it.name} does not allow rating changes." }
                it.copy(rating = rating)
            },
        ) { item ->
            check(item.ratingWritable != false) { "${item.name} does not allow rating changes." }
            repository.setMediaRating(item, rating)
        }
    }

    fun setMediaRotationBatch(items: List<CameraMediaItem>, degrees: Int) {
        if (degrees !in setOf(0, 90, 180, 270)) return
        updateMediaMetadataBatch(
            items = items,
            feature = CameraFeature.MEDIA_ROTATE,
            operation = MediaBatchOperation.ROTATE,
            previewUpdate = { it.copy(rotationDegrees = degrees) },
        ) { item -> repository.setMediaRotation(item, degrees) }
    }

    private fun updateMediaMetadata(
        item: CameraMediaItem,
        feature: CameraFeature,
        previewUpdate: (CameraMediaItem) -> CameraMediaItem,
        update: suspend () -> CameraMediaItem,
    ) {
        val state = _uiState.value
        if (state.isBusy(CameraOperation.MEDIA) || !state.supports(feature)) return
        if (state.previewMode) {
            _uiState.update { current ->
                val currentItem = current.mediaItems.firstOrNull { it.id == item.id } ?: item
                current.withUpdatedMedia(previewUpdate(currentItem))
            }
            return
        }
        runCamera(CameraOperation.MEDIA) {
            val updated = update()
            _uiState.update { current -> current.withUpdatedMedia(updated) }
        }
    }

    private fun updateMediaMetadataBatch(
        items: List<CameraMediaItem>,
        feature: CameraFeature,
        operation: MediaBatchOperation,
        previewUpdate: (CameraMediaItem) -> CameraMediaItem,
        update: suspend (CameraMediaItem) -> CameraMediaItem,
    ) {
        val state = _uiState.value
        val selectedItems = items.distinctBy(CameraMediaItem::id)
        if (selectedItems.isEmpty() || state.isBusy(CameraOperation.MEDIA) || !state.supports(feature)) return
        _uiState.update { it.copy(lastMediaBatchResult = null) }
        runCamera(CameraOperation.MEDIA) {
            val result = executeMediaBatch(
                items = selectedItems,
                operation = operation,
                onProgress = { progress ->
                    _uiState.update { current -> current.copy(mediaBatchProgress = progress) }
                },
            ) { item ->
                val updated = if (state.previewMode) previewUpdate(item) else update(item)
                _uiState.update { current -> current.withUpdatedMedia(updated) }
            }
            _uiState.update {
                it.copy(
                    mediaBatchProgress = null,
                    lastMediaBatchResult = result,
                )
            }
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
            CameraMediaPickerRequests.Result.Expired -> reportMediaPickerError(context, R.string.media_picker_session_expired)
            is CameraMediaPickerRequests.Result.Ready -> {
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
        if (mediaPickerRequests.cancel(kind, id)) {
            mediaSaveRequest?.takeIf { it.pickerId == id }?.let { request ->
                finishPendingMediaSave(request, MediaSaveFeedback.Failed(context.getString(R.string.media_picker_open_failed)))
            }
            reportMediaPickerError(context, R.string.media_picker_open_failed)
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
        val request = MediaSaveRequest(cameraSessionGeneration, requireNotNull(state.info), pickerId)
        mediaSaveRequest = request
        updateMediaSave(request) {
            it.copy(
                mediaSaveFeedback = items.associate { item -> item.id to initial },
                lastDownloadedMediaName = null,
                lastDownloadLocation = null,
                lastMediaBatchResult = null,
            )
        }
        return request
    }

    private fun updateMediaSave(request: MediaSaveRequest, update: (CameraUiState) -> CameraUiState) {
        _uiState.update { current ->
            if (request.owns(mediaSaveRequest, cameraSessionGeneration, current.info)) update(current) else current
        }
    }

    private fun publishMediaSave(request: MediaSaveRequest, item: CameraMediaItem, feedback: MediaSaveFeedback) {
        updateMediaSave(request) { it.copy(mediaSaveFeedback = it.mediaSaveFeedback + (item.id to feedback)) }
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
        updateMediaSave(request) { state ->
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
            updateMediaSave(request) {
                it.copy(mediaBatchProgress = null, activeMediaDownloadName = null, mediaDownloadProgress = null)
            }
            // Keep terminal feedback, but retire the writer before another workflow can start.
            if (mediaSaveRequest === request) mediaSaveRequest = null
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
                            else -> MediaSaveFeedback.Failed("Automatic JPEG import did not publish this original.")
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
                    ?: error("Android could not open the selected download destination.")
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
        val historyRequest = history?.captureRequest()
        val request = beginMediaSave(state, selectedItems)
        val location = if (destinationTree == null) cameraGalleryPath(state.info.model)
            else context.getString(R.string.media_save_selected_folder)
        val historyDestination = if (destinationTree == null) DownloadHistoryDestination.GALLERY else DownloadHistoryDestination.FOLDER
        val job = launchCameraOperation(
            CameraOperation.MEDIA,
            onError = { finishPendingMediaSave(request, MediaSaveFeedback.Failed(formatException(it))) },
        ) {
            val result = executeMediaBatch(
                items = selectedItems,
                operation = MediaBatchOperation.DOWNLOAD,
                onProgress = { progress ->
                    updateMediaSave(request) { it.copy(mediaBatchProgress = progress) }
                },
            ) { item ->
                withDownloadHistoryReceipt(history, historyRequest, item.name, historyDestination) { completed ->
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
                                            ?: error("Android could not open the selected download destination.")
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
            updateMediaSave(request) { it.copy(lastMediaBatchResult = result) }
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
        handoffOwner.cancelUnlaunched(CameraImportHandoffOrigin.CAMERA)
        if (foregroundImportOwner?.operation === mediaDownloadJob && mediaDownloadJob != null) {
            stopForegroundImport(ForegroundImportStopReason.USER)
        }
        mediaDownloadJob?.cancel()
    }

    fun uploadMedia(context: Context, sourceUri: Uri) {
        val state = _uiState.value
        if (
            state.previewMode ||
            state.isBusy(CameraOperation.MEDIA) ||
            !state.supports(CameraFeature.MEDIA_UPLOAD) ||
            mediaUploadJob != null ||
            mediaDownloadJob != null
        ) return
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        _uiState.update { it.copy(lastUploadedMediaName = null) }
        val job = launchCameraOperation(CameraOperation.MEDIA) {
            var temporaryFile: File? = null
            try {
                val metadata = withContext(Dispatchers.IO) {
                    resolver.query(
                        sourceUri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        MediaUploadMetadata(
                            name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                                ?.let(cursor::getString)
                                .orEmpty(),
                            sizeBytes = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                                ?.let(cursor::getLong),
                        )
                    }
                } ?: MediaUploadMetadata(
                    name = sourceUri.lastPathSegment?.substringAfterLast('/').orEmpty(),
                    sizeBytes = null,
                )
                val name = metadata.name.trim()
                check(name.isNotEmpty()) { "Android could not determine the selected media filename." }
                _uiState.update {
                    it.copy(
                        activeMediaUploadName = name,
                        mediaUploadProgress = CameraMediaTransferProgress(0L, metadata.sizeBytes),
                        lastUploadedMediaName = null,
                    )
                }
                val result = withContext(Dispatchers.IO) {
                    val cached = File(appContext.cacheDir, "media-upload-${UUID.randomUUID()}.tmp")
                    temporaryFile = cached
                    val rawInput = resolver.openInputStream(sourceUri)
                        ?: error("Android could not open the selected upload source.")
                    val resolvedSize = BufferedInputStream(rawInput).use { input ->
                        FileOutputStream(cached).buffered().use { output ->
                            val buffer = ByteArray(MEDIA_UPLOAD_BUFFER_BYTES)
                            var copied = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                copied += count
                                check(copied <= MAX_MEDIA_UPLOAD_BYTES) {
                                    "Selected media exceeds the $MAX_MEDIA_UPLOAD_BYTES-byte upload limit."
                                }
                                output.write(buffer, 0, count)
                                _uiState.update { current ->
                                    current.copy(
                                        mediaUploadProgress = CameraMediaTransferProgress(
                                            copied,
                                            metadata.sizeBytes?.takeIf { it > 0L },
                                        )
                                    )
                                }
                            }
                            copied
                        }
                    }
                    check(resolvedSize in 1L..MAX_MEDIA_UPLOAD_BYTES) {
                        "Selected media size must be from 1 through $MAX_MEDIA_UPLOAD_BYTES bytes."
                    }
                    BufferedInputStream(FileInputStream(cached)).use { input ->
                        repository.uploadMedia(
                            name = name,
                            sizeBytes = resolvedSize,
                            contentType = resolver.getType(sourceUri),
                            source = input,
                        ) { progress ->
                            _uiState.update { current -> current.copy(mediaUploadProgress = progress) }
                        }
                    }
                }
                val finishUpload: suspend () -> Unit = finish@{
                    if (_uiState.value.info !== state.info) return@finish
                    _uiState.update {
                        it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.LOADING)
                    }
                    val items = try {
                        repository.listMedia()
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        _uiState.update {
                            if (it.info === state.info) it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.FAILED) else it
                        }
                        throw exception
                    }
                    if (_uiState.value.info !== state.info) return@finish
                    check(
                        items.any {
                            it.id == result.item.id ||
                                (it.name == result.item.name && it.sizeBytes == result.item.sizeBytes)
                        }
                    ) {
                        "The uploaded media was not present in the camera's refreshed media list."
                    }
                    val capabilities = runCatching { repository.refreshCapabilities() }.getOrNull()
                    if (_uiState.value.info !== state.info) return@finish
                    cancelMediaThumbnailLoads()
                    _uiState.update {
                        it.copy(
                            mediaItems = items,
                            mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE,
                            mediaThumbnails = emptyMap(),
                            capabilities = capabilities ?: it.capabilities,
                            lastUploadedMediaName = result.item.name,
                        )
                    }
                }
                if (state.transport == CameraTransport.USB_PTP) {
                    withContext(NonCancellable) { finishUpload() }
                } else {
                    finishUpload()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { temporaryFile?.delete() }
                _uiState.update { it.copy(activeMediaUploadName = null, mediaUploadProgress = null) }
            }
        }
        mediaUploadJob = job
        job?.invokeOnCompletion {
            if (mediaUploadJob === job) mediaUploadJob = null
        }
    }

    fun cancelMediaUpload() {
        val job = mediaUploadJob ?: return
        val state = _uiState.value
        if (state.transport !in setOf(CameraTransport.USB_PTP, CameraTransport.DESKTOP_BRIDGE)) {
            job.cancel()
            return
        }
        val name = state.activeMediaUploadName
        val sizeBytes = state.mediaUploadProgress?.totalBytes
        mediaUploadJob = null
        val generation = cameraSessionGeneration
        val cleanupJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            job.cancelAndJoin()
            if (generation != cameraSessionGeneration || _uiState.value.info !== state.info) return@launch
            if (_uiState.value.lastUploadedMediaName != null) return@launch
            if (state.transport == CameraTransport.DESKTOP_BRIDGE && name != null) {
                reconcileCancelledBridgeUpload(name, sizeBytes, generation)
                return@launch
            }
            withContext(NonCancellable + Dispatchers.IO) { runCatching { repository.disconnect() } }
            closeMediaStream()
            _uiState.update { current ->
                if (generation == cameraSessionGeneration && current.info === state.info) {
                    current.withClearedSession(
                        baseUrl = current.baseUrl,
                        error = "USB upload was interrupted before commit confirmation. Reconnect and refresh media before retrying.",
                    )
                } else {
                    current
                }
            }
        }
        mediaUploadCleanupJob = cleanupJob
        cleanupJob.invokeOnCompletion { if (mediaUploadCleanupJob === cleanupJob) mediaUploadCleanupJob = null }
        cleanupJob.start()
    }

    private suspend fun reconcileCancelledBridgeUpload(name: String, sizeBytes: Long?, generation: Long) {
        _uiState.update {
            it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.LOADING)
        }
        val items = withContext(NonCancellable + Dispatchers.IO) {
            runCatching { repository.listMedia() }.getOrNull()
        } ?: run {
            if (generation == cameraSessionGeneration) _uiState.update {
                it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.FAILED)
            }
            return
        }
        if (generation != cameraSessionGeneration) return
        val uploaded = items.firstOrNull { item ->
            item.name.equals(name, ignoreCase = true) &&
                (sizeBytes == null || item.sizeBytes == sizeBytes)
        }
        cancelMediaThumbnailLoads()
        _uiState.update { current ->
            current.copy(
                mediaItems = items,
                mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE,
                mediaThumbnails = emptyMap(),
                lastUploadedMediaName = uploaded?.name,
            )
        }
    }

    fun deleteMedia(item: CameraMediaItem) {
        val state = _uiState.value
        if (state.isBusy(CameraOperation.MEDIA) || !state.supports(CameraFeature.MEDIA_DELETE)) return
        _uiState.update { it.copy(lastMediaBatchResult = null) }
        if (state.previewMode) {
            applyDeletedMedia(item)
            refreshCaptureReview(_uiState.value.mediaItems)
            return
        }
        runCamera(CameraOperation.MEDIA) {
            repository.deleteMedia(item)
            applyDeletedMedia(item)
            refreshCaptureReview()
        }
    }

    fun deleteMediaBatch(items: List<CameraMediaItem>) {
        val state = _uiState.value
        val selectedItems = items.distinctBy(CameraMediaItem::id)
        if (
            selectedItems.isEmpty() ||
            state.isBusy(CameraOperation.MEDIA) ||
            !state.supports(CameraFeature.MEDIA_DELETE)
        ) return
        _uiState.update { it.copy(lastMediaBatchResult = null) }
        runCamera(CameraOperation.MEDIA) {
            val result = executeMediaBatch(
                items = selectedItems,
                operation = MediaBatchOperation.DELETE,
                onProgress = { progress ->
                    _uiState.update { current -> current.copy(mediaBatchProgress = progress) }
                },
            ) { item ->
                if (!state.previewMode) repository.deleteMedia(item)
                applyDeletedMedia(item)
            }
            if (result.succeededItems > 0) {
                if (state.previewMode) refreshCaptureReview(_uiState.value.mediaItems) else refreshCaptureReview()
            }
            _uiState.update {
                it.copy(
                    mediaBatchProgress = null,
                    lastMediaBatchResult = result,
                    lastDeletedMediaName = null,
                )
            }
        }
    }

    fun tapFocus(x: Double, y: Double) {
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
        runCamera(
            operation = CameraOperation.FOCUS,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            val result = repository.tapFocus(x, y)
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
        runCamera(
            operation = CameraOperation.SETTING,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            val revision = cameraStateRevision
            val status = latestCameraStatus(repository.clickWhiteBalance(x, y), revision)
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
            cameraSessionGeneration += 1
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
                val safetyFailure = exception is ShutterReleaseException || exception is AutofocusReleaseException ||
                    exception is dev.openeos.control.data.CcapiLiveViewReleaseException
                val recovery = if (connectionAttempt != null && !safetyFailure) {
                    ConnectionRecovery(connectionAttempt, if (exception is InvalidConnectionAddressException) {
                        ConnectionFailureReason.INVALID_ADDRESS
                    } else connectionFailureReason(exception))
                } else null
                // Connection diagnostics can contain entered addresses or server bodies. The
                // recovery flow only needs typed evidence, never these values in UI or logs.
                if (recovery == null) exception.printStackTrace()
                if (exception is ShutterReleaseException) markShutterReleaseUnconfirmed()
                onError(exception)
                _uiState.update {
                    it.copy(
                        error = recovery?.let { failure -> "Connection failed: ${failure.reason.name}" } ?: formatException(exception),
                        connectionRecovery = recovery,
                        errorOperation = operation,
                        autofocusHoldState = if (operation == CameraOperation.FOCUS && exception is AutofocusReleaseException) {
                            AutofocusHoldState.RELEASE_FAILED
                        } else it.autofocusHoldState,
                    )
                }
            } finally {
                if (generation == cameraSessionGeneration &&
                    (operation != CameraOperation.FOCUS || _uiState.value.info === connection)) {
                    cameraStateRevision += 1
                    _uiState.update {
                        if (operation == CameraOperation.FOCUS && it.autofocusHoldState == AutofocusHoldState.RELEASE_FAILED) it
                        else it.copy(pendingOperations = it.pendingOperations - operation)
                    }
                    afterFinally()
                    if (operation in LIVE_VIEW_INTERLOCK_OPERATIONS) queueLiveViewReconciliation()
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
        state.connected && !state.previewMode && appInForeground && state.uiMode != UiMode.MEDIA &&
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
                        val info = repository.fetchLiveViewFocusInfo()
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
            !appInForeground || !_uiState.value.liveViewAutoRefresh || !repository.isLiveViewRunning() ||
            !_uiState.value.connected || _uiState.value.shutterReleaseUnconfirmed ||
            _uiState.value.previewMode ||
            !_uiState.value.supports(CameraFeature.LIVE_VIEW)
            || !_uiState.value.liveViewTemperatureAllowed
            || _uiState.value.nativeLiveViewSession != null
        ) return

        val generation = liveViewGeneration

        if (!repository.isRealCamera()) {
            val nextUrl = repository.nextLiveViewFrameUrl()
            _uiState.update {
                if (it.connected && !it.shutterReleaseUnconfirmed && appInForeground && it.liveViewAutoRefresh && generation == liveViewGeneration) {
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
            return
        }

        try {
            val frame = repository.fetchLiveViewFrame()
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)
            } ?: error(
                "Live view frame was received but Android could not decode it " +
                    "(${frame.bytes.size} bytes, ${frame.contentType ?: "unknown content type"})."
            )

            _uiState.update {
                if (it.connected && !it.shutterReleaseUnconfirmed && appInForeground && it.liveViewAutoRefresh && generation == liveViewGeneration) {
                    it.copy(
                        liveViewFrameUrl = frame.sourceUrl,
                        liveViewBitmap = bitmap,
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
            exception.printStackTrace()
            if (reportErrors && generation == liveViewGeneration) {
                _uiState.update {
                    it.copy(
                        error = formatException(exception),
                        errorOperation = CameraOperation.LIVE_VIEW,
                    )
                }
            }
        }
    }

    private fun formatException(exception: Exception): String = buildString {
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
            !state.connected || state.shutterReleaseUnconfirmed ||
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
                if (!latest.connected || !latest.liveViewAutoRefresh || !appInForeground || !repository.isLiveViewRunning()) break
                val frameStartedAt = SystemClock.elapsedRealtime()

                if (repository.isRealCamera()) {
                    if (!refreshLiveViewFrameInternal(reportErrors = false)) break
                } else {
                    val nextUrl = repository.nextLiveViewFrameUrl()
                    _uiState.update {
                        if (it.connected && it.liveViewAutoRefresh) {
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
        if (!state.connected || state.previewMode || !state.supports(CameraFeature.EVENT_POLLING)) return
        val generation = eventPollingGeneration
        eventPollingJob = viewModelScope.launch {
            var consecutiveFailures = 0
            var pendingChangedKeys = emptySet<String>()
            while (isActive && generation == eventPollingGeneration) {
                try {
                    // Polling consumes the notification. Keep its complete hint until the
                    // authoritative snapshot succeeds; an empty later poll cannot repair it.
                    if (pendingChangedKeys.isEmpty()) {
                        pendingChangedKeys = repository.pollEvent().changedKeys.toSet()
                        if (pendingChangedKeys.isEmpty()) {
                            consecutiveFailures = 0
                            continue
                        }
                    }
                    // Publish control state before any potentially slow media listing. A command
                    // that starts or finishes during a read invalidates that snapshot and retries.
                    val capabilities = refreshEventCameraState(generation) ?: break
                    val refreshContents = "contents" in pendingChangedKeys
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
            _uiState.update { current ->
                current.copy(
                    status = status,
                    captureStatusReadbackFailed = false,
                    capabilities = capabilities,
                    captureMode = captureMode ?: current.captureMode,
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
        if (_uiState.value.shutterReleaseUnconfirmed) return
        reconcileLiveView()
        repository.setNativeLiveViewRenderingEnabled(appInForeground && _uiState.value.liveViewAutoRefresh)
        if (!appInForeground || !_uiState.value.liveViewAutoRefresh) return
        if (refreshLiveViewFrameInternal(reportErrors = false)) startLiveViewLoopIfNeeded()
    }

    override fun onCleared() {
        val handoffCleanup = handoffOwner.cancelUnlaunched()
        foregroundImportPreferences?.unregisterOnSharedPreferenceChangeListener(foregroundImportWarningListener)
        val foregroundImport = stopForegroundImport(ForegroundImportStopReason.SESSION_CHANGED)
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
            operationJobs.forEach { it.join() }
            uploadJob?.join()
            uploadCleanupJob?.join()
            focusJob?.join()
            repository.disconnect()
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
        foregroundJpegImport = ForegroundJpegImportStatus(),
        captureReviewStatus = CaptureReviewStatus.IDLE,
        captureStatusReadbackFailed = false,
        activeMediaDownloadName = null,
        mediaDownloadProgress = null,
        lastDownloadedMediaName = null,
        lastDownloadLocation = null,
        activeMediaUploadName = null,
        mediaUploadProgress = null,
        lastUploadedMediaName = null,
        lastDeletedMediaName = null,
        mediaBatchProgress = null,
        lastMediaBatchResult = null,
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
        lastCreatedDirectoryName = null,
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

    private fun CameraUiState.withUpdatedMedia(item: CameraMediaItem): CameraUiState = copy(
        mediaItems = mediaItems.map { current -> if (current.id == item.id) item else current },
        mediaPreviewItem = mediaPreviewItem?.let { current -> if (current.id == item.id) item else current },
        captureReviewItem = captureReviewItem?.let { current -> if (current.id == item.id) item else current },
    )

    internal fun CameraUiState.withEventMediaItems(
        items: List<CameraMediaItem>,
        preservePreview: Boolean = false,
    ): CameraUiState {
        val itemIds = items.mapTo(hashSetOf(), CameraMediaItem::id)
        val previewStillExists = preservePreview || mediaPreviewItem?.id in itemIds
        return copy(
            mediaItems = items,
            mediaThumbnails = mediaThumbnails.filterKeys(itemIds::contains),
            mediaThumbnailLoadingIds = mediaThumbnailLoadingIds.intersect(itemIds),
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
            state.captureReviewLoading || state.captureReviewStatus != CaptureReviewStatus.NOT_READY ||
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
        captureReviewJob = viewModelScope.launch {
            val selected = awaitCaptureReviewItem(
                previousIds = attempt?.previousIds.orEmpty(),
                retryDelaysMillis = CAPTURE_REVIEW_RETRY_DELAYS_MILLIS,
                videosOnly = attempt?.videosOnly == true,
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
            if (!stillOwnsReview()) return@launch
            if (selected == null) {
                _uiState.update { it.copy(
                    captureReviewLoading = false,
                    captureReviewStatus = if (attempt != null) CaptureReviewStatus.NOT_READY else CaptureReviewStatus.IDLE,
                ) }
                return@launch
            }
            publishCaptureReview(selected, generation)
        }.also { job ->
            job.invokeOnCompletion {
                if (captureReviewJob === job) captureReviewJob = null
            }
        }
    }

    private fun refreshCaptureReview(items: List<CameraMediaItem>) {
        if (captureReviewJob?.isActive == true) return
        val attempt = pendingCaptureReview
        val selected = selectReviewCandidate(items, attempt?.previousIds.orEmpty(), attempt?.videosOnly == true)
        // Event/gallery refreshes must not turn the old image into this attempt's result.
        if (attempt != null && selected == null) return
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

    private suspend fun publishCaptureReview(item: CameraMediaItem, generation: Long) {
        if (generation != captureReviewGeneration) return
        pendingCaptureReview = null
        val existing = _uiState.value
        val canLoadThumbnail = existing.supports(CameraFeature.MEDIA_THUMBNAIL)
        _uiState.update {
            it.copy(
                captureReviewItem = item,
                captureReviewStatus = CaptureReviewStatus.IDLE,
                captureReviewThumbnail = it.captureReviewThumbnail.takeIf { _ -> it.captureReviewItem?.id == item.id },
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
            captureStatusReadbackFailed = false,
        ) }
    }

    private suspend fun fetchMediaThumbnailBitmap(item: CameraMediaItem): android.graphics.Bitmap {
        var lastFailure: Exception? = null
        repeat(MEDIA_THUMBNAIL_RETRY_DELAYS_MILLIS.size + 1) { attempt ->
            try {
                val thumbnail = repository.mediaThumbnail(item)
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
        session.setTargetFps(fps)
        session.setRenderingEnabled(appInForeground && _uiState.value.liveViewAutoRefresh)
        session.setListener { event ->
            viewModelScope.launch {
                if (generation != liveViewGeneration || !appInForeground || !_uiState.value.liveViewAutoRefresh) return@launch
                if (_uiState.value.nativeLiveViewSession !== session && repository.nativeLiveViewSession() !== session) {
                    return@launch
                }
                when (event) {
                    is NativeLiveViewEvent.AudioStatusChanged -> _uiState.update {
                        it.copy(liveViewAudioStatus = event.status)
                    }

                    is NativeLiveViewEvent.FrameRendered -> _uiState.update {
                        it.copy(
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

                    is NativeLiveViewEvent.Failed -> _uiState.update {
                        it.copy(error = event.message, errorOperation = CameraOperation.LIVE_VIEW)
                    }
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
