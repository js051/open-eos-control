package dev.openeos.control.ui

import android.graphics.Bitmap
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.DesktopBridgeCamera
import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.LiveViewSource
import dev.openeos.control.data.NativeLiveViewSession
import dev.openeos.control.data.UsbPtpDiagnostics
import java.util.Locale

const val MIN_LIVE_VIEW_FPS = 1
const val MAX_LIVE_VIEW_FPS = 30
const val DEFAULT_LIVE_VIEW_FPS = 6

enum class UiMode { CONTROL, MEDIA, DEBUG }

enum class CaptureMode { PHOTO, VIDEO }

enum class LiveViewTapAction { FOCUS, WHITE_BALANCE }

enum class ConnectionTarget { CCAPI, DESKTOP_BRIDGE }

enum class SettingPicker { ISO, SHUTTER, APERTURE, WHITE_BALANCE, LIVE_VIEW, MORE, LANGUAGE }

enum class CameraOperation { CONNECT, STATUS, SETTING, CAPTURE, RECORDING, FOCUS, LIVE_VIEW, MEDIA, USB, BRIDGE }

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
    val mediaThumbnails: Map<String, Bitmap> = emptyMap(),
    val mediaThumbnailLoadingIds: Set<String> = emptySet(),
    val activeMediaDownloadName: String? = null,
    val mediaDownloadProgress: CameraMediaTransferProgress? = null,
    val lastDownloadedMediaName: String? = null,
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
    val liveViewDiagnostics: LiveViewDiagnostics = LiveViewDiagnostics(),
    val uiMode: UiMode = UiMode.CONTROL,
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val hudVisible: Boolean = true,
    val showGrid: Boolean = false,
    val liveViewTapAction: LiveViewTapAction = LiveViewTapAction.FOCUS,
    val activeSettingPicker: SettingPicker? = null,
    val captureFeedback: CaptureFeedback? = null,
    val focusPoint: FocusPoint? = null,
    val focusFeedback: FocusFeedback? = null,
    val error: String? = null,
    val errorOperation: CameraOperation? = null,
    val pendingOperations: Set<CameraOperation> = emptySet(),
) {
    val connected: Boolean
        get() = info != null && status?.connected == true

    fun supports(feature: CameraFeature): Boolean =
        capabilities?.matrix?.supports(feature) ?: false

    val busy: Boolean
        get() = pendingOperations.isNotEmpty()

    fun isBusy(operation: CameraOperation): Boolean = operation in pendingOperations
}

data class FocusPoint(
    val x: Double,
    val y: Double,
)

internal fun CameraCapabilities.shootingModeSetting(): CameraSettingControl? =
    advancedSettings.firstOrNull { it.key.isShootingModeKey() }

internal fun CameraSettingControl.currentCaptureMode(): CaptureMode? {
    val current = values.firstOrNull { it.cameraModeToken() == value.cameraModeToken() } ?: return null
    return captureModeForShootingValue(current)
}

internal fun CameraSettingControl.valueForCaptureMode(
    mode: CaptureMode,
    preferredPhotoValue: String?,
): String? = when (mode) {
    CaptureMode.VIDEO -> values.firstOrNull { captureModeForShootingValue(it) == CaptureMode.VIDEO }
    CaptureMode.PHOTO -> sequenceOf(preferredPhotoValue, value)
        .filterNotNull()
        .mapNotNull { candidate ->
            values.firstOrNull { it.cameraModeToken() == candidate.cameraModeToken() }
        }
        .firstOrNull { captureModeForShootingValue(it) == CaptureMode.PHOTO }
}

internal fun captureModeForShootingValue(value: String): CaptureMode? {
    val token = value.cameraModeToken()
    if (token.isBlank() || token.startsWith("unknown") || token.startsWith("0x")) return null
    return if ("movie" in token || "video" in token) CaptureMode.VIDEO else CaptureMode.PHOTO
}

internal fun String.isShootingModeKey(): Boolean =
    cameraModeToken() in setOf("shootingmode", "autoexposuremode", "ae")

private fun String.cameraModeToken(): String =
    lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

fun settingsForMode(settings: List<CameraSettingControl>, mode: CaptureMode): List<CameraSettingControl> {
    val videoTokens = listOf("movie", "video", "frame", "codec", "record", "sound")
    val photoTokens = listOf("still", "photo", "drive", "imagequality", "colorspace", "highisonr", "aeb", "aspect")
    return settings.filter { it.values.distinct().size > 1 }.filter { setting ->
        val key = setting.key.lowercase()
        val isVideo = videoTokens.any(key::contains)
        val isPhoto = photoTokens.any(key::contains)
        when (mode) {
            CaptureMode.PHOTO -> !isVideo
            CaptureMode.VIDEO -> !isPhoto
        }
    }
}
