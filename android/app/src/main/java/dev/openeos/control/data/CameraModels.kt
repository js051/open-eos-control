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
            val normalized = modelName.lowercase().filter(Char::isLetterOrDigit)
            return when {
                normalized.contains("r6markiii") ||
                    normalized.contains("r6m3") ||
                    normalized.contains("r63") -> R6_MARK_III.copy(modelName = modelName.ifBlank { R6_MARK_III.modelName })

                normalized.contains("eosr") -> genericEos(modelName, CameraModelFamily.EOS_R, CameraModelPriority.SUPPORTED)
                normalized.contains("eosm") -> genericEos(modelName, CameraModelFamily.EOS_M, CameraModelPriority.SUPPORTED)
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
    CAMERA_CLOCK_SYNC("Camera clock sync"),
    BATTERY_STATUS("Battery status"),
    STORAGE_STATUS("Storage status"),
    RECORDABLE_STATUS("Recordable status"),
    LENS_STATUS("Lens status"),
    TEMPERATURE_STATUS("Temperature status"),
    EVENT_POLLING("Camera event polling"),
    LIVE_VIEW("Live view"),
    LIVE_VIEW_JPEG_POLLING("Live view JPEG polling"),
    LIVE_VIEW_RTP("Live view RTP"),
    LIVE_VIEW_MAGNIFICATION("Live view magnification"),
    STILL_CAPTURE("Still capture"),
    BULB_EXPOSURE("Bulb exposure"),
    AUTOFOCUS("Autofocus"),
    SHUTTER_HALF_PRESS("Shutter half-press"),
    MOVIE_MODE_CONTROL("Movie mode control"),
    VIDEO_RECORDING("Video recording"),
    TAP_FOCUS("Tap focus"),
    CLICK_WHITE_BALANCE("Click white balance"),
    FOCUS_DRIVE("Focus drive"),
    EXPOSURE_CONTROL("Exposure control"),
    WHITE_BALANCE_CONTROL("White balance control"),
    ZOOM_CONTROL("Zoom control"),
    CARD_SELECTION_CONTROL("Card selection control"),
    SOUND_RECORDING_LEVEL_CONTROL("Sound recording level control"),
    ADVANCED_SETTINGS("Advanced camera settings"),
    MEDIA_BROWSER("Media browser"),
    MEDIA_THUMBNAIL("Media thumbnail"),
    MEDIA_PREVIEW("Media preview"),
    MEDIA_DOWNLOAD("Media download"),
    MEDIA_DELETE("Media deletion"),
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
                CameraFeature.CLICK_WHITE_BALANCE,
                CameraFeature.EXPOSURE_CONTROL,
                CameraFeature.WHITE_BALANCE_CONTROL,
                CameraFeature.ADVANCED_SETTINGS,
            ),
        ): CapabilityMatrix = CapabilityMatrix(
            supported = supported,
            planned = setOf(
                CameraFeature.RECORDABLE_STATUS,
                CameraFeature.LENS_STATUS,
                CameraFeature.TEMPERATURE_STATUS,
                CameraFeature.EVENT_POLLING,
                CameraFeature.LIVE_VIEW_RTP,
                CameraFeature.STILL_CAPTURE,
                CameraFeature.BULB_EXPOSURE,
                CameraFeature.AUTOFOCUS,
                CameraFeature.SHUTTER_HALF_PRESS,
                CameraFeature.MOVIE_MODE_CONTROL,
                CameraFeature.VIDEO_RECORDING,
                CameraFeature.TAP_FOCUS,
                CameraFeature.CLICK_WHITE_BALANCE,
                CameraFeature.FOCUS_DRIVE,
                CameraFeature.ZOOM_CONTROL,
                CameraFeature.CARD_SELECTION_CONTROL,
                CameraFeature.SOUND_RECORDING_LEVEL_CONTROL,
                CameraFeature.MEDIA_BROWSER,
                CameraFeature.MEDIA_THUMBNAIL,
                CameraFeature.MEDIA_PREVIEW,
                CameraFeature.MEDIA_DOWNLOAD,
                CameraFeature.MEDIA_DELETE,
                CameraFeature.CAMERA_CLOCK_SYNC,
            ) - supported,
            reasons = mapOf(
                CameraFeature.RECORDABLE_STATUS to
                    "The camera must advertise GET shooting/information/recordable and return Canon's documented nullable integer payload.",
                CameraFeature.LENS_STATUS to
                    "The camera must advertise GET devicestatus/lens and return Canon's documented mount/name payload.",
                CameraFeature.TEMPERATURE_STATUS to
                    "The camera must advertise GET devicestatus/temperature and return a documented Canon status value.",
                CameraFeature.LIVE_VIEW_RTP to
                    "Requires advertised Canon RTP SDP/start endpoints plus a camera Wi-Fi route for native H.264 decoding.",
                CameraFeature.EVENT_POLLING to
                    "The camera must advertise both GET and DELETE for the Canon event polling endpoint.",
                CameraFeature.TAP_FOCUS to
                    "The camera must advertise PUT afframeposition and detailed Live View metadata for coordinate Tap AF.",
                CameraFeature.CLICK_WHITE_BALANCE to
                    "The camera must advertise POST clickwb and detailed Live View metadata for Click WB.",
                CameraFeature.AUTOFOCUS to
                    "The camera must advertise CCAPI POST autofocus or a verified manual half-press operation.",
                CameraFeature.FOCUS_DRIVE to "The camera must advertise the verified CCAPI POST drivefocus operation.",
                CameraFeature.ZOOM_CONTROL to
                    "The camera must advertise readable and writable Canon zoom control in the same API version.",
                CameraFeature.CARD_SELECTION_CONTROL to
                    "The camera must advertise matching GET and PUT Canon card-selection endpoints and valid card abilities.",
                CameraFeature.SOUND_RECORDING_LEVEL_CONTROL to
                    "The camera must advertise matching GET and PUT Canon sound-recording-level endpoints and a valid integer range.",
                CameraFeature.MOVIE_MODE_CONTROL to
                    "The camera must advertise readable and writable Canon movie mode control in the same API version.",
                CameraFeature.CAMERA_CLOCK_SYNC to
                    "The camera must advertise both GET and PUT for the Canon date-time endpoint in the same API version.",
            ),
        )

        fun androidUsbPtpRoadmap(): CapabilityMatrix = CapabilityMatrix(
            supported = emptySet(),
            planned = setOf(
                CameraFeature.USB_DIAGNOSTICS,
                CameraFeature.CAMERA_IDENTITY,
                CameraFeature.BATTERY_STATUS,
                CameraFeature.STORAGE_STATUS,
                CameraFeature.STILL_CAPTURE,
                CameraFeature.BULB_EXPOSURE,
                CameraFeature.AUTOFOCUS,
                CameraFeature.SHUTTER_HALF_PRESS,
                CameraFeature.LIVE_VIEW,
                CameraFeature.LIVE_VIEW_MAGNIFICATION,
                CameraFeature.TAP_FOCUS,
                CameraFeature.CLICK_WHITE_BALANCE,
                CameraFeature.FOCUS_DRIVE,
                CameraFeature.EXPOSURE_CONTROL,
                CameraFeature.MEDIA_BROWSER,
                CameraFeature.MEDIA_THUMBNAIL,
                CameraFeature.MEDIA_PREVIEW,
                CameraFeature.MEDIA_DOWNLOAD,
                CameraFeature.MEDIA_DELETE,
                CameraFeature.CAMERA_CLOCK_SYNC,
            ),
            reasons = mapOf(
                CameraFeature.USB_DIAGNOSTICS to "First wired milestone: enumerate Canon USB device and open a PTP session.",
                CameraFeature.LIVE_VIEW to "Requires Canon PTP vendor-extension validation on EOS R6 Mark III.",
                CameraFeature.CAMERA_CLOCK_SYNC to
                    "Requires advertised Canon EOS UTC/CameraTime property events and SetDevicePropValueEx; " +
                    "a matching post-write event is required.",
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
                CameraFeature.BULB_EXPOSURE,
                CameraFeature.AUTOFOCUS,
                CameraFeature.SHUTTER_HALF_PRESS,
                CameraFeature.VIDEO_RECORDING,
                CameraFeature.LIVE_VIEW,
                CameraFeature.LIVE_VIEW_MAGNIFICATION,
                CameraFeature.TAP_FOCUS,
                CameraFeature.CLICK_WHITE_BALANCE,
                CameraFeature.FOCUS_DRIVE,
                CameraFeature.EXPOSURE_CONTROL,
                CameraFeature.CARD_SELECTION_CONTROL,
                CameraFeature.SOUND_RECORDING_LEVEL_CONTROL,
                CameraFeature.MEDIA_BROWSER,
                CameraFeature.MEDIA_THUMBNAIL,
                CameraFeature.MEDIA_PREVIEW,
                CameraFeature.MEDIA_DOWNLOAD,
                CameraFeature.MEDIA_DELETE,
                CameraFeature.CAMERA_CLOCK_SYNC,
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
    val manufacturer: String? = null,
    val deviceVersion: String? = null,
    val engineVersion: String? = null,
)

data class ExposureState(
    val iso: String,
    val shutter: String,
    val aperture: String,
    val whiteBalance: String,
)

data class LensStatus(
    val mounted: Boolean,
    val name: String,
)

enum class CameraTemperatureStatus(
    val ccapiValue: String,
) {
    NORMAL("normal"),
    WARNING("warning"),
    FRAME_RATE_DOWN("frameratedown"),
    DISABLE_LIVE_VIEW("disableliveview"),
    DISABLE_RELEASE("disablerelease"),
    STILL_QUALITY_WARNING("stillqualitywarning"),
    RESTRICTION_MOVIE_RECORDING("restrictionmovierecording"),
    WARNING_AND_RESTRICTION_MOVIE_RECORDING("warning_and_restrictionmovierecording"),
    FRAME_RATE_DOWN_AND_RESTRICTION_MOVIE_RECORDING("frameratedown_and_restrictionmovierecording"),
    DISABLE_LIVE_VIEW_AND_RESTRICTION_MOVIE_RECORDING("disableliveview_and_restrictionmovierecording"),
    DISABLE_RELEASE_AND_RESTRICTION_MOVIE_RECORDING("disablerelease_and_restrictionmovierecording"),
    STILL_QUALITY_WARNING_AND_RESTRICTION_MOVIE_RECORDING(
        "stillqualitywarning_and_restrictionmovierecording",
    ),
    ;

    val isNormal: Boolean get() = this == NORMAL
    val liveViewAllowed: Boolean get() = "disableliveview" !in ccapiValue
    val stillCaptureAllowed: Boolean get() = "disablerelease" !in ccapiValue
    val movieRecordingAllowed: Boolean get() = "restrictionmovierecording" !in ccapiValue
    val frameRateReduced: Boolean get() = ccapiValue.startsWith("frameratedown")
    val stillQualityWarning: Boolean get() = ccapiValue.startsWith("stillqualitywarning")
    val temperatureWarning: Boolean get() = ccapiValue == "warning" || ccapiValue.startsWith("warning_and_")

    companion object {
        fun fromCcapiValue(value: String): CameraTemperatureStatus? =
            entries.firstOrNull { it.ccapiValue == value }
    }
}

data class CameraStatus(
    val connected: Boolean,
    val batteryLevel: Int?,
    val batteryStatus: String,
    val recording: Boolean?,
    val mode: String,
    val mediaAvailable: Boolean?,
    val remainingMinutes: Int?,
    val exposure: ExposureState,
    val storageTotalBytes: Long? = null,
    val storageFreeBytes: Long? = null,
    val storageFreeImages: Long? = null,
    val storageDeviceCount: Int? = null,
    val recordableShots: Long? = null,
    val remainingRecordingSeconds: Long? = null,
    val rawBatteryJson: String = "",
    val rawStorageJson: String = "",
    val rawRecordableJson: String = "",
    val rawTransportJson: String = "",
    val bulbExposureActive: Boolean? = null,
    val lens: LensStatus? = null,
    val temperature: CameraTemperatureStatus? = null,
)

data class CameraEvent(
    val changedKeys: Set<String> = emptySet(),
)

data class CameraCapabilityEvidence(
    val source: String = "unknown",
    val protocolVersions: List<String> = emptyList(),
    val advertisedCommands: List<String> = emptyList(),
    val writableSettings: List<String> = emptyList(),
    val observedFeatures: Set<CameraFeature> = emptySet(),
    val truncated: Boolean = false,
)

const val MAX_CAPABILITY_EVIDENCE_ITEMS = 256
const val MAX_CAPABILITY_EVIDENCE_ITEM_CHARS = 512

data class CameraCapabilities(
    val iso: List<String>,
    val shutter: List<String>,
    val aperture: List<String>,
    val whiteBalance: List<String>,
    val advancedSettings: List<CameraSettingControl> = emptyList(),
    val matrix: CapabilityMatrix = CapabilityMatrix(),
    val liveView: LiveViewCapabilities = LiveViewCapabilities(),
    val profile: CameraProfile = CameraProfile.genericEos(),
    val evidence: CameraCapabilityEvidence = CameraCapabilityEvidence(),
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

data class FocusDriveResult(
    val ok: Boolean,
    val direction: FocusDriveDirection,
    val step: FocusDriveStep,
)

enum class LiveViewMagnification(
    val value: Int,
) {
    X1(1),
    X5(5),
}

data class LiveViewMagnificationResult(
    val ok: Boolean,
    val magnification: LiveViewMagnification,
)

data class CameraMediaItem(
    val id: String,
    val name: String,
    val kind: String,
    val sizeBytes: Long? = null,
    val captureTime: String? = null,
    val previewAvailable: Boolean = false,
)

data class CameraMediaThumbnail(
    val item: CameraMediaItem,
    val bytes: ByteArray,
    val contentType: String?,
)

data class CameraMediaPreview(
    val item: CameraMediaItem,
    val bytes: ByteArray,
    val contentType: String?,
)

data class CameraMediaTransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long?,
)

data class CameraMediaDownloadResult(
    val item: CameraMediaItem,
    val bytesTransferred: Long,
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
