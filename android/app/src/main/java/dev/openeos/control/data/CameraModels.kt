package dev.openeos.control.data

enum class CameraModelFamily(
    val label: String,
) {
    EOS_R("EOS R"),
    EOS_DSLR("EOS DSLR"),
    EOS_M("EOS M"),
    POWERSHOT("PowerShot"),
    UNKNOWN("Unknown"),
}

enum class CameraModelPriority {
    PRIMARY,
    SUPPORTED,
    RESEARCH,
}

data class CameraProfile(
    val modelName: String,
    val family: CameraModelFamily,
    val priority: CameraModelPriority,
    val aliases: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    companion object {
        val R6_MARK_III = CameraProfile(
            modelName = "Canon EOS R6 Mark III",
            family = CameraModelFamily.EOS_R,
            priority = CameraModelPriority.PRIMARY,
            aliases = listOf("R6 Mark III", "R6m3", "R63"),
            notes = listOf("Primary development and real-camera validation body."),
        )

        fun fromModelName(modelName: String): CameraProfile {
            val normalized = modelName.lowercase()
            return when {
                normalized.contains("eos r6 mark iii") ||
                    normalized.contains("r6 mark iii") ||
                    normalized.contains("r6m3") ||
                    normalized.contains("r63") -> R6_MARK_III.copy(modelName = modelName.ifBlank { R6_MARK_III.modelName })

                normalized.contains("eos r") -> genericEos(modelName, CameraModelFamily.EOS_R, CameraModelPriority.SUPPORTED)
                normalized.contains("eos m") -> genericEos(modelName, CameraModelFamily.EOS_M, CameraModelPriority.SUPPORTED)
                normalized.contains("eos") -> genericEos(modelName, CameraModelFamily.EOS_DSLR, CameraModelPriority.SUPPORTED)
                normalized.contains("powershot") -> genericEos(modelName, CameraModelFamily.POWERSHOT, CameraModelPriority.RESEARCH)
                else -> genericEos(modelName, CameraModelFamily.UNKNOWN, CameraModelPriority.RESEARCH)
            }
        }

        fun genericEos(
            modelName: String = "Canon EOS Camera",
            family: CameraModelFamily = CameraModelFamily.UNKNOWN,
            priority: CameraModelPriority = CameraModelPriority.RESEARCH,
        ): CameraProfile = CameraProfile(
            modelName = modelName.ifBlank { "Canon EOS Camera" },
            family = family,
            priority = priority,
        )
    }
}

enum class CameraFeature(
    val label: String,
) {
    CAMERA_IDENTITY("Camera identity"),
    BATTERY_STATUS("Battery status"),
    STORAGE_STATUS("Storage status"),
    LIVE_VIEW("Live view"),
    LIVE_VIEW_JPEG_POLLING("Live view JPEG polling"),
    LIVE_VIEW_RTP("Live view RTP"),
    STILL_CAPTURE("Still capture"),
    SHUTTER_HALF_PRESS("Shutter half-press"),
    VIDEO_RECORDING("Video recording"),
    TAP_FOCUS("Tap focus"),
    FOCUS_DRIVE("Focus drive"),
    EXPOSURE_CONTROL("Exposure control"),
    WHITE_BALANCE_CONTROL("White balance control"),
    ADVANCED_SETTINGS("Advanced camera settings"),
    MEDIA_BROWSER("Media browser"),
    MEDIA_DOWNLOAD("Media download"),
    USB_DIAGNOSTICS("USB/PTP diagnostics"),
    DESKTOP_BRIDGE("Desktop bridge"),
}

data class CapabilityMatrix(
    val supported: Set<CameraFeature> = emptySet(),
    val planned: Set<CameraFeature> = emptySet(),
    val reasons: Map<CameraFeature, String> = emptyMap(),
) {
    fun supports(feature: CameraFeature): Boolean = feature in supported

    fun isPlanned(feature: CameraFeature): Boolean = feature in planned

    companion object {
        fun ccapiNetwork(
            supported: Set<CameraFeature> = setOf(
                CameraFeature.CAMERA_IDENTITY,
                CameraFeature.BATTERY_STATUS,
                CameraFeature.STORAGE_STATUS,
                CameraFeature.LIVE_VIEW,
                CameraFeature.LIVE_VIEW_JPEG_POLLING,
                CameraFeature.VIDEO_RECORDING,
                CameraFeature.TAP_FOCUS,
                CameraFeature.EXPOSURE_CONTROL,
                CameraFeature.WHITE_BALANCE_CONTROL,
                CameraFeature.ADVANCED_SETTINGS,
            ),
        ): CapabilityMatrix = CapabilityMatrix(
            supported = supported,
            planned = setOf(
                CameraFeature.LIVE_VIEW_RTP,
                CameraFeature.STILL_CAPTURE,
                CameraFeature.SHUTTER_HALF_PRESS,
                CameraFeature.FOCUS_DRIVE,
                CameraFeature.MEDIA_BROWSER,
                CameraFeature.MEDIA_DOWNLOAD,
            ) - supported,
        )

        fun androidUsbPtpRoadmap(): CapabilityMatrix = CapabilityMatrix(
            supported = emptySet(),
            planned = setOf(
                CameraFeature.USB_DIAGNOSTICS,
                CameraFeature.CAMERA_IDENTITY,
                CameraFeature.BATTERY_STATUS,
                CameraFeature.STORAGE_STATUS,
                CameraFeature.STILL_CAPTURE,
                CameraFeature.SHUTTER_HALF_PRESS,
                CameraFeature.LIVE_VIEW,
                CameraFeature.FOCUS_DRIVE,
                CameraFeature.EXPOSURE_CONTROL,
                CameraFeature.MEDIA_BROWSER,
                CameraFeature.MEDIA_DOWNLOAD,
            ),
            reasons = mapOf(
                CameraFeature.USB_DIAGNOSTICS to "First wired milestone: enumerate Canon USB device and open a PTP session.",
                CameraFeature.LIVE_VIEW to "Requires Canon PTP vendor-extension validation on EOS R6 Mark III.",
            ),
        )

        fun desktopBridgeRoadmap(): CapabilityMatrix = CapabilityMatrix(
            supported = emptySet(),
            planned = setOf(
                CameraFeature.DESKTOP_BRIDGE,
                CameraFeature.CAMERA_IDENTITY,
                CameraFeature.BATTERY_STATUS,
                CameraFeature.STORAGE_STATUS,
                CameraFeature.STILL_CAPTURE,
                CameraFeature.SHUTTER_HALF_PRESS,
                CameraFeature.VIDEO_RECORDING,
                CameraFeature.LIVE_VIEW,
                CameraFeature.FOCUS_DRIVE,
                CameraFeature.EXPOSURE_CONTROL,
                CameraFeature.MEDIA_BROWSER,
                CameraFeature.MEDIA_DOWNLOAD,
            ),
            reasons = mapOf(
                CameraFeature.DESKTOP_BRIDGE to "Bridge protocol is open; engines are libgphoto2 and optional user-installed Canon EDSDK.",
            ),
        )
    }
}

enum class LiveViewSource(
    val label: String,
) {
    AUTO("Auto"),
    CCAPI_JPEG_POLLING("CCAPI JPEG polling"),
    CCAPI_RTP("CCAPI RTP"),
    USB_PTP_PREVIEW("USB/PTP preview"),
    DESKTOP_BRIDGE_STREAM("Desktop bridge stream"),
    SIMULATOR_FRAME("Simulator frame"),
}

enum class LiveViewSize(
    val ccapiValue: String,
    val label: String,
) {
    SMALL("small", "Small"),
    MEDIUM("medium", "Medium"),
    LARGE("large", "Large"),
}

data class LiveViewRequest(
    val fps: Int = DEFAULT_LIVE_VIEW_REQUEST_FPS,
    val size: LiveViewSize = LiveViewSize.MEDIUM,
    val source: LiveViewSource = LiveViewSource.AUTO,
) {
    fun clampTo(capabilities: LiveViewCapabilities): LiveViewRequest =
        copy(
            fps = fps.coerceIn(capabilities.minFps, capabilities.maxFps),
            size = if (size in capabilities.sizes) size else capabilities.defaultSize,
            source = if (source == LiveViewSource.AUTO || source in capabilities.sources) source else capabilities.defaultSource,
        )
}

data class LiveViewCapabilities(
    val sources: List<LiveViewSource> = emptyList(),
    val defaultSource: LiveViewSource = sources.firstOrNull() ?: LiveViewSource.AUTO,
    val sizes: List<LiveViewSize> = emptyList(),
    val defaultSize: LiveViewSize = sizes.firstOrNull() ?: LiveViewSize.MEDIUM,
    val minFps: Int = 1,
    val maxFps: Int = 30,
) {
    companion object {
        fun ccapiNetwork(): LiveViewCapabilities = LiveViewCapabilities(
            sources = listOf(LiveViewSource.CCAPI_JPEG_POLLING),
            defaultSource = LiveViewSource.CCAPI_JPEG_POLLING,
            sizes = listOf(LiveViewSize.SMALL, LiveViewSize.MEDIUM, LiveViewSize.LARGE),
            defaultSize = LiveViewSize.MEDIUM,
        )

        fun simulator(): LiveViewCapabilities = LiveViewCapabilities(
            sources = listOf(LiveViewSource.SIMULATOR_FRAME),
            defaultSource = LiveViewSource.SIMULATOR_FRAME,
            sizes = listOf(LiveViewSize.MEDIUM),
            defaultSize = LiveViewSize.MEDIUM,
            maxFps = 2,
        )
    }
}

data class CameraInfo(
    val connected: Boolean,
    val model: String,
    val serial: String,
    val api: String,
)

data class ExposureState(
    val iso: String,
    val shutter: String,
    val aperture: String,
    val whiteBalance: String,
)

data class CameraStatus(
    val connected: Boolean,
    val batteryLevel: Int?,
    val batteryStatus: String,
    val recording: Boolean?,
    val mode: String,
    val mediaAvailable: Boolean?,
    val remainingMinutes: Int?,
    val exposure: ExposureState,
    val rawBatteryJson: String = "",
    val rawStorageJson: String = "",
)

data class CameraCapabilities(
    val iso: List<String>,
    val shutter: List<String>,
    val aperture: List<String>,
    val whiteBalance: List<String>,
    val advancedSettings: List<CameraSettingControl> = emptyList(),
    val matrix: CapabilityMatrix = CapabilityMatrix(),
    val liveView: LiveViewCapabilities = LiveViewCapabilities(),
    val profile: CameraProfile = CameraProfile.genericEos(),
)

data class CameraSettingControl(
    val key: String,
    val label: String,
    val value: String,
    val values: List<String>,
)

data class LiveViewFrame(
    val bytes: ByteArray,
    val contentType: String?,
    val sourceUrl: String,
)

data class FocusResult(
    val ok: Boolean,
    val x: Double,
    val y: Double,
)

data class CameraMediaItem(
    val id: String,
    val name: String,
    val kind: String,
    val sizeBytes: Long? = null,
    val captureTime: String? = null,
)

data class CameraMediaFile(
    val item: CameraMediaItem,
    val bytes: ByteArray,
    val contentType: String?,
)

sealed interface CameraCommand {
    data object RefreshStatus : CameraCommand
    data class SetExposure(val iso: String? = null, val shutter: String? = null, val aperture: String? = null) : CameraCommand
    data class SetSetting(val key: String, val value: String) : CameraCommand
    data class StartLiveView(val request: LiveViewRequest) : CameraCommand
    data object StopLiveView : CameraCommand
    data object CaptureStill : CameraCommand
    data object StartRecording : CameraCommand
    data object StopRecording : CameraCommand
    data class TapFocus(val x: Double, val y: Double) : CameraCommand
}

const val DEFAULT_LIVE_VIEW_REQUEST_FPS = 6
