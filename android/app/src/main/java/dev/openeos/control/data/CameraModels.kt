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
    DIRECTORY_CONTROL("Capture directory control"),
    FILE_NAMING_CONTROL("File naming control"),
    SENSOR_CLEANING("Sensor cleaning"),
    CAMERA_SLEEP("Camera sleep"),
    BATTERY_STATUS("Battery status"),
    STORAGE_STATUS("Storage status"),
    RECORDABLE_STATUS("Recordable status"),
    LENS_STATUS("Lens status"),
    TEMPERATURE_STATUS("Temperature status"),
    EVENT_POLLING("Camera event polling"),
    LIVE_VIEW("Live view"),
    LIVE_VIEW_JPEG_POLLING("Live view JPEG polling"),
    LIVE_VIEW_MULTIPART("Live view multipart stream"),
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
    SOUND_RECORDING_CONTROL("Sound recording control"),
    SOUND_RECORDING_LEVEL_CONTROL("Sound recording level control"),
    FOCUS_BRACKETING_CONTROL("Focus bracketing control"),
    MOVIE_SETTINGS_CONTROL("Movie recording settings control"),
    ADVANCED_SETTINGS("Advanced camera settings"),
    MEDIA_BROWSER("Media browser"),
    MEDIA_THUMBNAIL("Media thumbnail"),
    MEDIA_PREVIEW("Media preview"),
    MEDIA_DOWNLOAD("Media download"),
    MEDIA_UPLOAD("Media upload"),
    MEDIA_PROTECT("Media protection"),
    MEDIA_ARCHIVE("Media archive"),
    MEDIA_RATING("Media rating"),
    MEDIA_ROTATE("Media rotation"),
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
        private val directCcapiFeatures = CameraFeature.entries.toSet() - setOf(
            CameraFeature.USB_DIAGNOSTICS,
            CameraFeature.DESKTOP_BRIDGE,
        )

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
            planned = directCcapiFeatures - supported,
            reasons = mapOf(
                CameraFeature.RECORDABLE_STATUS to
                    "The camera must advertise GET shooting/information/recordable and return Canon's documented nullable integer payload.",
                CameraFeature.LENS_STATUS to
                    "The camera must advertise GET devicestatus/lens and return Canon's documented mount/name payload.",
                CameraFeature.TEMPERATURE_STATUS to
                    "The camera must advertise GET devicestatus/temperature and return a documented Canon status value.",
                CameraFeature.LIVE_VIEW_RTP to
                    "Requires advertised Canon RTP SDP/start endpoints plus a camera Wi-Fi route for native H.264 decoding.",
                CameraFeature.LIVE_VIEW_MULTIPART to
                    "Requires matching GET and DELETE Canon multipart Live View endpoints in one API version.",
                CameraFeature.EVENT_POLLING to
                    "The camera must advertise both GET and DELETE for the Canon event polling endpoint.",
                CameraFeature.TAP_FOCUS to
                    "The camera must advertise PUT afframeposition and detailed Live View metadata for coordinate Tap AF.",
                CameraFeature.CLICK_WHITE_BALANCE to
                    "The camera must advertise POST clickwb and detailed Live View metadata for Click WB.",
                CameraFeature.AUTOFOCUS to
                    "The camera must advertise CCAPI POST autofocus or a verified manual half-press operation.",
                CameraFeature.FOCUS_DRIVE to "The camera must advertise the verified CCAPI POST drivefocus operation.",
                CameraFeature.LIVE_VIEW_MAGNIFICATION to
                    "The camera must advertise same-version GET and PUT Canon lvzoom endpoints and return a valid 1x/5x/10x string ability list.",
                CameraFeature.ZOOM_CONTROL to
                    "The camera must advertise readable and writable Canon zoom control in the same API version.",
                CameraFeature.CARD_SELECTION_CONTROL to
                    "The camera must advertise matching GET and PUT Canon card-selection endpoints and valid card abilities.",
                CameraFeature.SOUND_RECORDING_CONTROL to
                    "The camera must advertise matching GET and PUT Canon sound-recording-setting endpoints and valid documented abilities.",
                CameraFeature.SOUND_RECORDING_LEVEL_CONTROL to
                    "The camera must advertise matching GET and PUT Canon sound-recording-level endpoints and a valid integer range.",
                CameraFeature.FOCUS_BRACKETING_CONTROL to
                    "The camera must advertise matching GET and PUT Canon focus-bracketing endpoints and valid documented abilities.",
                CameraFeature.MOVIE_SETTINGS_CONTROL to
                    "The camera must advertise matching GET and PUT Canon movie-setting endpoints and valid documented abilities.",
                CameraFeature.MOVIE_MODE_CONTROL to
                    "The camera must advertise readable and writable Canon movie mode control in the same API version.",
                CameraFeature.CAMERA_CLOCK_SYNC to
                    "The camera must advertise both GET and PUT for the Canon date-time endpoint in the same API version.",
                CameraFeature.DIRECTORY_CONTROL to
                    "The camera must advertise Canon directory creation and a same-version GET/PUT directory-selection pair with a valid ability list.",
                CameraFeature.FILE_NAMING_CONTROL to
                    "The camera must advertise the complete same-version Canon still and movie file-naming endpoint group and return valid values and ranges.",
                CameraFeature.SENSOR_CLEANING to
                    "The camera must advertise the Canon POST sensor-cleaning endpoint.",
                CameraFeature.CAMERA_SLEEP to
                    "The camera must advertise matching GET and PUT Auto Power Off endpoints and include immediately in its current ability.",
                CameraFeature.MEDIA_PROTECT to
                    "The camera must advertise PUT for Canon contents before file protection can be changed.",
                CameraFeature.MEDIA_ARCHIVE to
                    "The camera must advertise PUT for Canon contents before file archive state can be changed.",
                CameraFeature.MEDIA_RATING to
                    "The camera must advertise PUT for Canon contents before file ratings can be changed.",
                CameraFeature.MEDIA_ROTATE to
                    "The camera must advertise PUT for Canon contents before display rotation can be changed.",
                CameraFeature.MEDIA_PREVIEW to
                    "Canon kind=display requires an advertised GET contents operation and is eligible only for JPEG or CR3 items; the camera can still reject an individual file.",
                CameraFeature.MEDIA_UPLOAD to
                    "Direct CCAPI upload remains unavailable because no verified Canon upload operation is advertised or implemented.",
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
                CameraFeature.MEDIA_UPLOAD,
                CameraFeature.MEDIA_PROTECT,
                CameraFeature.MEDIA_ARCHIVE,
                CameraFeature.MEDIA_RATING,
                CameraFeature.MEDIA_ROTATE,
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
                CameraFeature.SOUND_RECORDING_CONTROL,
                CameraFeature.SOUND_RECORDING_LEVEL_CONTROL,
                CameraFeature.FOCUS_BRACKETING_CONTROL,
                CameraFeature.MOVIE_SETTINGS_CONTROL,
                CameraFeature.MEDIA_BROWSER,
                CameraFeature.MEDIA_THUMBNAIL,
                CameraFeature.MEDIA_PREVIEW,
                CameraFeature.MEDIA_DOWNLOAD,
                CameraFeature.MEDIA_UPLOAD,
                CameraFeature.MEDIA_PROTECT,
                CameraFeature.MEDIA_ARCHIVE,
                CameraFeature.MEDIA_RATING,
                CameraFeature.MEDIA_ROTATE,
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
    CCAPI_MULTIPART("CCAPI multipart"),
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
    val magnifications: List<LiveViewMagnification> = emptyList(),
    val currentMagnification: LiveViewMagnification? = null,
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
            magnifications = LiveViewMagnification.entries,
            currentMagnification = LiveViewMagnification.X1,
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

data class CameraDiscoveryAttempt(
    val endpoint: String,
    val outcome: String,
    val httpStatus: Int? = null,
    val responseKeys: List<String> = emptyList(),
    val protocolVersions: List<String> = emptyList(),
    val advertisedOperationCount: Int = 0,
    val truncated: Boolean = false,
)

data class CameraCapabilityEvidence(
    val source: String = "unknown",
    val protocolVersions: List<String> = emptyList(),
    val advertisedCommands: List<String> = emptyList(),
    val writableSettings: List<String> = emptyList(),
    val observedFeatures: Set<CameraFeature> = emptySet(),
    val discoveryTrace: List<CameraDiscoveryAttempt> = emptyList(),
    val truncated: Boolean = false,
)

const val MAX_CAPABILITY_EVIDENCE_ITEMS = 256
const val MAX_CAPABILITY_EVIDENCE_ITEM_CHARS = 512
const val MAX_DISCOVERY_TRACE_ATTEMPTS = 16
const val MAX_DISCOVERY_TRACE_KEYS = 32
const val MAX_DISCOVERY_TRACE_KEY_CHARS = 64

data class CameraCapabilities(
    val iso: List<String>,
    val shutter: List<String>,
    val aperture: List<String>,
    val whiteBalance: List<String>,
    val advancedSettings: List<CameraSettingControl> = emptyList(),
    val fileNaming: CameraFileNaming? = null,
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
    val inputKind: CameraSettingInputKind = CameraSettingInputKind.CHOICE,
    val maxLength: Int? = null,
)

enum class CameraSettingInputKind {
    CHOICE,
    TEXT,
}

enum class CameraFileNamingField(
    val wireName: String,
) {
    STILL_FILENAME_MODE("still-filename-mode"),
    STILL_USER_SETTING_1("still-user-setting-1"),
    STILL_USER_SETTING_2("still-user-setting-2"),
    MOVIE_INDEX("movie-index"),
    MOVIE_REEL_NUMBER("movie-reel-number"),
    MOVIE_CLIP_NUMBER("movie-clip-number"),
    MOVIE_USER_DEFINED("movie-user-defined"),
    ;

    companion object {
        fun fromWireName(value: String): CameraFileNamingField? = entries.firstOrNull { it.wireName == value }
    }
}

data class CameraIntegerRange(
    val minimum: Int,
    val maximum: Int,
    val step: Int,
) {
    fun accepts(value: String): Boolean {
        if (minimum > maximum || step <= 0) return false
        val integer = value.toIntOrNull()?.takeIf { it.toString() == value } ?: return false
        return integer in minimum..maximum && (integer - minimum) % step == 0
    }
}

data class CameraFileNaming(
    val stillFilenameMode: String,
    val stillFilenameModeOptions: List<String>,
    val stillUserSetting1: String,
    val stillUserSetting2: String,
    val movieIndex: String,
    val movieReelNumber: Int,
    val movieReelRange: CameraIntegerRange,
    val movieClipNumber: Int,
    val movieClipRange: CameraIntegerRange,
    val movieUserDefined: String,
) {
    fun accepts(field: CameraFileNamingField, value: String): Boolean = when (field) {
        CameraFileNamingField.STILL_FILENAME_MODE -> value in stillFilenameModeOptions
        CameraFileNamingField.STILL_USER_SETTING_1 -> Regex("^[A-Z0-9][A-Z0-9_]{3}$").matches(value)
        CameraFileNamingField.STILL_USER_SETTING_2 -> Regex("^[A-Z0-9][A-Z0-9_]{2}$").matches(value)
        CameraFileNamingField.MOVIE_INDEX -> Regex("^[A-Z0-9][A-Z0-9_]$").matches(value)
        CameraFileNamingField.MOVIE_REEL_NUMBER -> movieReelRange.accepts(value)
        CameraFileNamingField.MOVIE_CLIP_NUMBER -> movieClipRange.accepts(value)
        CameraFileNamingField.MOVIE_USER_DEFINED -> Regex("^[A-Z0-9]{5}$").matches(value)
    }
}

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
    X10(10),
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
    val protected: Boolean? = null,
    val rating: Int? = null,
    val rotationDegrees: Int? = null,
    val ratingWritable: Boolean? = null,
    val archived: Boolean? = null,
    val streamAvailable: Boolean = false,
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

data class CameraMediaUploadResult(
    val item: CameraMediaItem,
    val bytesTransferred: Long,
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
