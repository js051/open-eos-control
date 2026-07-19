package dev.openeos.control.ui

import android.graphics.Bitmap
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.UsbPtpDiagnostics

const val MIN_LIVE_VIEW_FPS = 1
const val MAX_LIVE_VIEW_FPS = 30
const val DEFAULT_LIVE_VIEW_FPS = 6

enum class UiMode { CONTROL, DEBUG }

enum class CaptureMode { PHOTO, VIDEO }

enum class SettingPicker { ISO, SHUTTER, APERTURE, WHITE_BALANCE, LIVE_VIEW, MORE, LANGUAGE }

enum class CameraOperation { CONNECT, STATUS, SETTING, CAPTURE, RECORDING, FOCUS, LIVE_VIEW, USB }

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
    val baseUrl: String = CameraRepository.DEFAULT_CAMERA_BASE_URL,
    val username: String = "",
    val password: String = "",
    val transport: CameraTransport? = null,
    val info: CameraInfo? = null,
    val status: CameraStatus? = null,
    val capabilities: CameraCapabilities? = null,
    val liveViewFrameUrl: String? = null,
    val liveViewBitmap: Bitmap? = null,
    val usbDiagnostics: UsbPtpDiagnostics = UsbPtpDiagnostics.Empty,
    val networkDiagnostics: CameraNetworkDiagnostics = CameraNetworkDiagnostics.Empty,
    val liveViewAutoRefresh: Boolean = true,
    val liveViewFrameRateFps: Int = DEFAULT_LIVE_VIEW_FPS,
    val liveViewSize: LiveViewSize = LiveViewSize.MEDIUM,
    val liveViewDiagnostics: LiveViewDiagnostics = LiveViewDiagnostics(),
    val uiMode: UiMode = UiMode.CONTROL,
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val hudVisible: Boolean = true,
    val showGrid: Boolean = false,
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

fun settingsForMode(settings: List<CameraSettingControl>, mode: CaptureMode): List<CameraSettingControl> {
    val videoTokens = listOf("movie", "video", "frame", "codec", "record", "sound")
    val photoTokens = listOf("still", "photo", "drive", "imagequality")
    return settings.filter { setting ->
        val key = setting.key.lowercase()
        val isVideo = videoTokens.any(key::contains)
        val isPhoto = photoTokens.any(key::contains)
        when (mode) {
            CaptureMode.PHOTO -> !isVideo
            CaptureMode.VIDEO -> !isPhoto
        }
    }
}
