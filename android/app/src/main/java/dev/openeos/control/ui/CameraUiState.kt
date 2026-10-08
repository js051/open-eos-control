package dev.openeos.control.ui

import android.graphics.Bitmap
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaStreamSource
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CameraSettingInputKind
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.DesktopBridgeCamera
import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.LiveViewSource
import dev.openeos.control.data.LiveViewMagnification
import dev.openeos.control.data.NativeLiveViewSession
import dev.openeos.control.data.NativeLiveViewAudioStatus
import dev.openeos.control.data.UsbPtpDiagnostics
import java.util.Locale

const val MIN_LIVE_VIEW_FPS = 1
const val MAX_LIVE_VIEW_FPS = 30
const val DEFAULT_LIVE_VIEW_FPS = 6

enum class UiMode { CONTROL, MEDIA, DEBUG }

enum class CaptureMode { PHOTO, VIDEO }

enum class LiveViewTapAction { FOCUS, WHITE_BALANCE }

enum class ConnectionTarget { CCAPI, DESKTOP_BRIDGE }

enum class SettingPicker { ISO, SHUTTER, APERTURE, WHITE_BALANCE, LIVE_VIEW, MONITOR, MORE, LANGUAGE }

enum class CameraOperation { CONNECT, STATUS, SETTING, DIRECTORY, CLOCK, MAINTENANCE, POWER, CAPTURE, RECORDING, FOCUS, LIVE_VIEW, MEDIA, USB, BRIDGE, SHUTTER_RELEASE }

enum class MediaLibraryLoadStatus { NOT_LOADED, LOADING, COMPLETE, CANCELLED, FAILED }

enum class MediaLibraryScope { RECENT, ALL }

enum class MediaBatchOperation { DOWNLOAD, OPEN_NEGATIVE, PROTECT, UNPROTECT, ARCHIVE, UNARCHIVE, RATE, ROTATE, DELETE }

data class MediaBatchProgress(
    val operation: MediaBatchOperation,
    val completedItems: Int,
    val totalItems: Int,
    val currentItemName: String,
)

data class MediaBatchResult(
    val operation: MediaBatchOperation,
    val totalItems: Int,
    val succeededItems: Int,
    val failedItemNames: List<String>,
) {
    val failedItems: Int
        get() = failedItemNames.size
}

data class LiveViewDiagnostics(
    val observedFps: Double = 0.0,
    val frameBytes: Int? = null,
    val contentType: String? = null,
    val sourceUrl: String? = null,
    val lastFrameAtMillis: Long? = null,
)

enum class CaptureReviewStatus {
    IDLE, SEARCHING, NOT_READY, READ_FAILED;

    val canRetry: Boolean get() = this == NOT_READY || this == READ_FAILED
}

enum class CaptureFeedback { SUCCESS }

enum class FocusFeedback { FOCUSING, ACCEPTED, SUCCESS, FAILURE }

enum class AutofocusHoldState { IDLE, STARTING, HOLDING, RELEASING, RELEASE_FAILED }

data class CameraUiState(
    val autofocusHoldState: AutofocusHoldState = AutofocusHoldState.IDLE,
    val shutterAutofocus: Boolean = true,
    val shutterReleaseUnconfirmed: Boolean = false,
    val shutterDisconnectWarning: Boolean = false,
    val connectionTarget: ConnectionTarget = ConnectionTarget.CCAPI,
    val baseUrl: String = CameraRepository.DEFAULT_CAMERA_BASE_URL,
    val ccapiSimulatorMode: Boolean? = null,
    val username: String = "",
    val password: String = "",
    val bridgeBaseUrl: String = CameraRepository.DEFAULT_DESKTOP_BRIDGE_URL,
    val bridgeToken: String = "",
    val bridgeCameras: List<DesktopBridgeCamera> = emptyList(),
    val bridgeScanCompleted: Boolean = false,
    val connectionRecovery: ConnectionRecovery? = null,
    val selectedBridgeCameraId: String? = null,
    val previewMode: Boolean = false,
    val transport: CameraTransport? = null,
    val info: CameraInfo? = null,
    val status: CameraStatus? = null,
    val capabilities: CameraCapabilities? = null,
    val mediaItems: List<CameraMediaItem> = emptyList(),
    val mediaLibraryScope: MediaLibraryScope = MediaLibraryScope.RECENT,
    val mediaDateRange: MediaDateRange? = null,
    val mediaRatingFilter: MediaRatingFilter = MediaRatingFilter.ALL,
    val mediaFolderFilter: MediaFolderFilter = MediaFolderFilter.All,
    // Media UI identity only; transport operations retain their existing private generation.
    val mediaSessionGeneration: Long = 0,
    val mediaLibraryHasMore: Boolean = false,
    val mediaLibraryLoading: Boolean = false,
    val mediaLibraryLoadStatus: MediaLibraryLoadStatus = MediaLibraryLoadStatus.NOT_LOADED,
    val captureReviewItem: CameraMediaItem? = null,
    val captureReviewThumbnail: Bitmap? = null,
    val captureReviewLoading: Boolean = false,
    val captureReviewStatus: CaptureReviewStatus = CaptureReviewStatus.IDLE,
    val captureStatusReadbackFailed: Boolean = false,
    val mediaThumbnails: Map<String, Bitmap> = emptyMap(),
    val mediaThumbnailLoadingIds: Set<String> = emptySet(),
    val mediaPreviewItem: CameraMediaItem? = null,
    val mediaPreviewBytes: ByteArray? = null,
    val mediaPreviewLoading: Boolean = false,
    val mediaStreamSource: CameraMediaStreamSource? = null,
    val mediaSaveFeedback: Map<String, MediaSaveFeedback> = emptyMap(),
    val foregroundJpegImport: ForegroundJpegImportStatus = ForegroundJpegImportStatus(),
    // A previous connection's local-output cleanup warning does not belong to the new camera.
    val foregroundImportCleanupUnconfirmed: Boolean = false,
    // Kept across session reset until the previous local output owner has fully retired.
    val foregroundImportOwnerActive: Boolean = false,
    val activeMediaDownloadName: String? = null,
    val mediaDownloadProgress: CameraMediaTransferProgress? = null,
    val lastDownloadedMediaName: String? = null,
    val lastDownloadLocation: String? = null,
    val activeMediaUploadName: String? = null,
    val mediaUploadProgress: CameraMediaTransferProgress? = null,
    val lastUploadedMediaName: String? = null,
    val lastDeletedMediaName: String? = null,
    val mediaBatchProgress: MediaBatchProgress? = null,
    val lastMediaBatchResult: MediaBatchResult? = null,
    val cameraImportPreparing: Boolean = false,
    val pendingCameraImportHandoff: CameraImportHandoffSession? = null,
    val lastCameraImportReceiptSummary: CameraImportReceiptSummary? = null,
    val liveViewFrameUrl: String? = null,
    val liveViewBitmap: Bitmap? = null,
    val nativeLiveViewSession: NativeLiveViewSession? = null,
    val usbDiagnostics: UsbPtpDiagnostics = UsbPtpDiagnostics.Empty,
    val networkDiagnostics: CameraNetworkDiagnostics = CameraNetworkDiagnostics.Empty,
    val liveViewAutoRefresh: Boolean = true,
    val liveViewFrameRateFps: Int = DEFAULT_LIVE_VIEW_FPS,
    val liveViewSize: LiveViewSize = LiveViewSize.MEDIUM,
    val liveViewSource: LiveViewSource = LiveViewSource.AUTO,
    val liveViewAspectRatio: Float = 16f / 9f,
    val liveViewMagnification: LiveViewMagnification? = null,
    val liveViewDiagnostics: LiveViewDiagnostics = LiveViewDiagnostics(),
    val cameraFocusInfo: dev.openeos.control.data.CameraFocusInfo? = null,
    val cameraFocusInfoAtMillis: Long? = null,
    val cameraFocusInfoError: Boolean = false,
    val liveViewAudioStatus: NativeLiveViewAudioStatus = NativeLiveViewAudioStatus.None,
    val uiMode: UiMode = UiMode.CONTROL,
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val hudVisible: Boolean = true,
    val showGrid: Boolean = false,
    val monitorSettings: LiveViewMonitorSettings = LiveViewMonitorSettings(),
    val liveViewTapAction: LiveViewTapAction = LiveViewTapAction.FOCUS,
    val activeSettingPicker: SettingPicker? = null,
    val captureFeedback: CaptureFeedback? = null,
    val bulbStartedAtMillis: Long? = null,
    val focusPoint: FocusPoint? = null,
    val focusFeedback: FocusFeedback? = null,
    val lastClockSyncAtMillis: Long? = null,
    val lastCreatedDirectoryName: String? = null,
    val operatorConfirmedFeatures: Set<CameraFeature> = emptySet(),
    val error: String? = null,
    val errorOperation: CameraOperation? = null,
    val pendingOperations: Set<CameraOperation> = emptySet(),
) {
    val connected: Boolean
        get() = info != null && status?.connected == true

    fun supports(feature: CameraFeature): Boolean =
        capabilities?.matrix?.supports(feature) ?: false

    val busy: Boolean
        get() = pendingOperations.isNotEmpty() || bulbExposureActive || shutterReleaseUnconfirmed

    val bulbExposureActive: Boolean
        get() = status?.bulbExposureActive == true

    val bulbMode: Boolean
        get() = captureMode == CaptureMode.PHOTO &&
            (capabilities?.shootingModeSetting()?.value ?: status?.mode).orEmpty().isBulbModeValue()

    val liveViewTemperatureAllowed: Boolean
        get() = status?.temperature?.liveViewAllowed != false

    val stillCaptureTemperatureAllowed: Boolean
        get() = status?.temperature?.stillCaptureAllowed != false

    val movieRecordingTemperatureAllowed: Boolean
        get() = status?.temperature?.movieRecordingAllowed != false

    fun isBusy(operation: CameraOperation): Boolean =
        operation in pendingOperations ||
            (CameraOperation.MEDIA in pendingOperations && foregroundJpegImport.phase in FOREGROUND_IMPORT_ACTIVE_PHASES &&
                operation in FOREGROUND_IMPORT_CONTROL_OPERATIONS &&
                !(operation == CameraOperation.RECORDING && status?.recording == true) &&
                !(operation == CameraOperation.CAPTURE && bulbExposureActive)) ||
            ((shutterReleaseUnconfirmed || CameraOperation.SHUTTER_RELEASE in pendingOperations) &&
                operation !in setOf(CameraOperation.SHUTTER_RELEASE, CameraOperation.STATUS, CameraOperation.USB, CameraOperation.BRIDGE)) ||
            (bulbExposureActive && operation != CameraOperation.CAPTURE &&
                !(shutterReleaseUnconfirmed && operation in setOf(CameraOperation.SHUTTER_RELEASE, CameraOperation.STATUS))) ||
            (CameraOperation.FOCUS in pendingOperations && operation in HELD_AF_INTERLOCK_OPERATIONS) ||
            (operation == CameraOperation.FOCUS && HELD_AF_INTERLOCK_OPERATIONS.any { it in pendingOperations }) ||
            (autofocusHoldState != AutofocusHoldState.IDLE && operation in HELD_AF_INTERLOCK_OPERATIONS) ||
            (CameraOperation.LIVE_VIEW in pendingOperations && operation in LIVE_VIEW_INTERLOCK_OPERATIONS)
}

internal val FOREGROUND_IMPORT_ACTIVE_PHASES = setOf(
    ForegroundImportPhase.BASELINING, ForegroundImportPhase.WATCHING, ForegroundImportPhase.WAITING,
    ForegroundImportPhase.SAVING, ForegroundImportPhase.STOPPING,
)

private val FOREGROUND_IMPORT_CONTROL_OPERATIONS = setOf(
    CameraOperation.FOCUS, CameraOperation.CAPTURE, CameraOperation.RECORDING,
    CameraOperation.SETTING, CameraOperation.DIRECTORY, CameraOperation.CLOCK,
    CameraOperation.POWER, CameraOperation.MAINTENANCE,
)

internal fun CameraUiState.foregroundImportCameraIdle(): Boolean =
    pendingOperations.isEmpty() && !bulbExposureActive && !shutterReleaseUnconfirmed &&
        autofocusHoldState == AutofocusHoldState.IDLE && status?.recording != true &&
        !mediaLibraryLoading && !captureReviewLoading && !mediaPreviewLoading && mediaPreviewItem == null &&
        activeMediaDownloadName == null && activeMediaUploadName == null && mediaBatchProgress == null &&
        mediaSaveFeedback.values.none { it.isPending } && !cameraImportPreparing

internal fun CameraUiState.canEnableForegroundJpegImport(platformSupported: Boolean): Boolean =
    platformSupported && !foregroundImportCleanupUnconfirmed && !foregroundImportOwnerActive &&
        connected && !previewMode && transport == CameraTransport.CCAPI_NETWORK &&
        supports(CameraFeature.MEDIA_BROWSER) && supports(CameraFeature.MEDIA_DOWNLOAD) &&
        foregroundJpegImport.phase in setOf(ForegroundImportPhase.OFF, ForegroundImportPhase.STOPPED) &&
        foregroundImportCameraIdle()

internal fun CameraUiState.dismissVisibleCameraMessage(): CameraUiState = when {
    shutterReleaseUnconfirmed -> this
    error != null || connectionRecovery != null -> copy(error = null, errorOperation = null, connectionRecovery = null)
    else -> copy(errorOperation = null, shutterDisconnectWarning = false)
}

internal val HELD_AF_INTERLOCK_OPERATIONS = setOf(
    CameraOperation.CONNECT,
    CameraOperation.FOCUS, CameraOperation.CAPTURE, CameraOperation.RECORDING, CameraOperation.SETTING,
    CameraOperation.MAINTENANCE, CameraOperation.POWER,
)

internal fun CameraUiState.showShutterAutofocus(): Boolean = connected && captureMode == CaptureMode.PHOTO &&
    !bulbMode && supports(CameraFeature.STILL_CAPTURE) &&
    (capabilities?.shutterAutofocusSupported == true || !shutterAutofocus)

internal fun CameraUiState.canChangeShutterAutofocus(): Boolean = showShutterAutofocus() &&
    !busy && !isBusy(CameraOperation.CAPTURE) && status?.recording != true

internal fun CameraUiState.canStartHeldAutofocus(): Boolean = connected && !previewMode &&
    capabilities?.heldAutofocusSupported == true && uiMode == UiMode.CONTROL && hudVisible &&
    activeSettingPicker == null && autofocusHoldState == AutofocusHoldState.IDLE &&
    !isBusy(CameraOperation.LIVE_VIEW) && HELD_AF_INTERLOCK_OPERATIONS.none { isBusy(it) }

internal val LIVE_VIEW_INTERLOCK_OPERATIONS = setOf(
    CameraOperation.FOCUS, CameraOperation.CAPTURE, CameraOperation.RECORDING, CameraOperation.MAINTENANCE,
    CameraOperation.SHUTTER_RELEASE,
)

data class FocusPoint(
    val x: Double,
    val y: Double,
)

internal fun CameraUiState.nextLiveViewMagnification(): LiveViewMagnification? {
    val abilities = capabilities?.liveView?.magnifications.orEmpty()
    if (abilities.size < 2 || captureMode != CaptureMode.PHOTO) return null
    val current = liveViewMagnification
        ?.takeIf { it in abilities }
        ?: capabilities?.liveView?.currentMagnification
        ?.takeIf { it in abilities }
        ?: abilities.first()
    return abilities[(abilities.indexOf(current) + 1) % abilities.size]
}

internal fun captureModeSwitchEnabled(state: CameraUiState): Boolean =
    !state.shutterReleaseUnconfirmed && state.autofocusHoldState == AutofocusHoldState.IDLE &&
    CameraOperation.FOCUS !in state.pendingOperations &&
    state.status?.recording != true &&
        !state.bulbExposureActive &&
        CameraOperation.SETTING !in state.pendingOperations &&
        CameraOperation.CAPTURE !in state.pendingOperations &&
        CameraOperation.RECORDING !in state.pendingOperations

internal fun CameraCapabilities.shootingModeSetting(): CameraSettingControl? =
    advancedSettings.firstOrNull { it.key.isShootingModeKey() }

internal fun CameraCapabilities.captureModeSetting(): CameraSettingControl? =
    advancedSettings.firstOrNull { it.key.isMovieModeKey() } ?: shootingModeSetting()

internal fun CameraSettingControl.currentCaptureMode(): CaptureMode? {
    if (key.isMovieModeKey()) {
        return when (value.cameraModeToken()) {
            "on" -> CaptureMode.VIDEO
            "off" -> CaptureMode.PHOTO
            else -> null
        }
    }
    val current = values.firstOrNull { it.cameraModeToken() == value.cameraModeToken() } ?: return null
    return captureModeForShootingValue(current)
}

internal fun CameraSettingControl.valueForCaptureMode(
    mode: CaptureMode,
    preferredPhotoValue: String?,
): String? {
    if (key.isMovieModeKey()) {
        val expected = if (mode == CaptureMode.VIDEO) "on" else "off"
        return values.firstOrNull { it.cameraModeToken() == expected }
    }
    return when (mode) {
        CaptureMode.VIDEO -> values.firstOrNull { captureModeForShootingValue(it) == CaptureMode.VIDEO }
        CaptureMode.PHOTO -> sequenceOf(preferredPhotoValue, value)
            .filterNotNull()
            .mapNotNull { candidate ->
                values.firstOrNull { it.cameraModeToken() == candidate.cameraModeToken() }
            }
            .firstOrNull { captureModeForShootingValue(it) == CaptureMode.PHOTO }
    }
}

internal fun captureModeForShootingValue(value: String): CaptureMode? {
    val token = value.cameraModeToken()
    if (token.isBlank() || token.startsWith("unknown") || token.startsWith("0x")) return null
    return if ("movie" in token || "video" in token) CaptureMode.VIDEO else CaptureMode.PHOTO
}

internal fun String.isShootingModeKey(): Boolean =
    cameraModeToken() in setOf("shootingmode", "autoexposuremode", "ae")

internal fun String.isMovieModeKey(): Boolean = cameraModeToken() == "moviemode"

internal fun String.isCaptureModeKey(): Boolean = isMovieModeKey() || isShootingModeKey()

private fun String.cameraModeToken(): String =
    lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

internal fun String.isBulbModeValue(): Boolean = cameraModeToken() == "bulb"

fun settingsForMode(settings: List<CameraSettingControl>, mode: CaptureMode): List<CameraSettingControl> {
    val videoTokens = listOf("movie", "video", "frame", "codec", "record", "sound")
    val videoOnlyPrefixes = listOf("windfilter", "attenuator")
    val photoTokens = listOf(
        "still", "photo", "drive", "imagequality", "colorspace", "highisonr", "aeb", "aspect", "capturetarget",
        "capturestorage", "directory",
    )
    return settings.filter {
        it.inputKind == CameraSettingInputKind.TEXT || it.values.distinct().size > 1
    }.filter { setting ->
        val key = setting.key.lowercase()
        if (setting.key.isMovieModeKey()) return@filter false
        val isVideo = videoOnlyPrefixes.any(key::startsWith) || videoTokens.any(key::contains)
        val isPhoto = key.startsWith("focusbracketing") || photoTokens.any(key::contains)
        when (mode) {
            CaptureMode.PHOTO -> !isVideo
            CaptureMode.VIDEO -> !isPhoto
        }
    }
}

/** Display-only state reducer. Old forms cannot apply or clear a replacement session's range. */
internal fun CameraUiState.withMediaDateRangeForSession(
    range: MediaDateRange?,
    connection: CameraInfo?,
    generation: Long,
): CameraUiState = if (info === connection && mediaSessionGeneration == generation) {
    copy(mediaDateRange = range)
} else this

/** A queued rating-menu callback cannot change a replacement camera's display. */
internal fun CameraUiState.withMediaRatingFilterForSession(
    filter: MediaRatingFilter,
    connection: CameraInfo?,
    generation: Long,
): CameraUiState = if (info === connection && mediaSessionGeneration == generation) {
    copy(mediaRatingFilter = filter)
} else this

/** A folder menu belongs to the exact connection and media session that opened it. */
internal fun CameraUiState.withMediaFolderFilterForSession(
    filter: MediaFolderFilter,
    connection: CameraInfo?,
    generation: Long,
): CameraUiState = if (info === connection && mediaSessionGeneration == generation) {
    copy(mediaFolderFilter = filter)
} else this
