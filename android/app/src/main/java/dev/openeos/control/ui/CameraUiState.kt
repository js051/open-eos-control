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

enum class CameraOperation { CONNECT, STATUS, SETTING, DIRECTORY, CLOCK, MAINTENANCE, POWER, CAPTURE, RECORDING, FOCUS, LIVE_VIEW, MEDIA, USB, BRIDGE }

enum class MediaLibraryLoadStatus { NOT_LOADED, LOADING, COMPLETE, CANCELLED, FAILED }

enum class MediaLibraryScope { RECENT, ALL }

data class LiveViewDiagnostics(
    val observedFps: Double = 0.0,
    val frameBytes: Int? = null,
    val contentType: String? = null,
    val sourceUrl: String? = null,
    val lastFrameAtMillis: Long? = null,
)

enum class CaptureFeedback { SUCCESS }

enum class FocusFeedback { FOCUSING, SUCCESS, FAILURE }

data class CameraUiState(
    val connectionTarget: ConnectionTarget = ConnectionTarget.CCAPI,
    val baseUrl: String = CameraRepository.DEFAULT_CAMERA_BASE_URL,
    val ccapiSimulatorMode: Boolean? = null,
    val username: String = "",
    val password: String = "",
    val bridgeBaseUrl: String = CameraRepository.DEFAULT_DESKTOP_BRIDGE_URL,
    val bridgeToken: String = "",
    val bridgeCameras: List<DesktopBridgeCamera> = emptyList(),
    val selectedBridgeCameraId: String? = null,
    val previewMode: Boolean = false,
    val transport: CameraTransport? = null,
    val info: CameraInfo? = null,
    val status: CameraStatus? = null,
    val capabilities: CameraCapabilities? = null,
    val mediaItems: List<CameraMediaItem> = emptyList(),
    val mediaLibraryScope: MediaLibraryScope = MediaLibraryScope.RECENT,
    val mediaLibraryHasMore: Boolean = false,
    val mediaLibraryLoading: Boolean = false,
    val mediaLibraryLoadStatus: MediaLibraryLoadStatus = MediaLibraryLoadStatus.NOT_LOADED,
    val mediaThumbnails: Map<String, Bitmap> = emptyMap(),
    val mediaThumbnailLoadingIds: Set<String> = emptySet(),
    val mediaPreviewItem: CameraMediaItem? = null,
    val mediaPreviewBytes: ByteArray? = null,
    val mediaPreviewLoading: Boolean = false,
    val mediaStreamSource: CameraMediaStreamSource? = null,
    val activeMediaDownloadName: String? = null,
    val mediaDownloadProgress: CameraMediaTransferProgress? = null,
    val lastDownloadedMediaName: String? = null,
    val activeMediaUploadName: String? = null,
    val mediaUploadProgress: CameraMediaTransferProgress? = null,
    val lastUploadedMediaName: String? = null,
    val lastDeletedMediaName: String? = null,
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
        get() = pendingOperations.isNotEmpty() || bulbExposureActive

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
        operation in pendingOperations || (bulbExposureActive && operation != CameraOperation.CAPTURE)
}

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
