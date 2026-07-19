package dev.openeos.control.ui

import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraProfile
import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CapabilityMatrix
import dev.openeos.control.data.ExposureState
import dev.openeos.control.data.LiveViewCapabilities

internal fun CameraUiState.withOfflinePreview(): CameraUiState = copy(
    previewMode = true,
    transport = null,
    info = CameraInfo(
        connected = true,
        model = CameraProfile.R6_MARK_III.modelName,
        serial = "offline-preview",
        api = "offline-preview",
    ),
    status = CameraStatus(
        connected = true,
        batteryLevel = 82,
        batteryStatus = "preview",
        recording = false,
        mode = "photo",
        mediaAvailable = true,
        remainingMinutes = 118,
        exposure = ExposureState(
            iso = "800",
            shutter = "1/125",
            aperture = "2.8",
            whiteBalance = "auto",
        ),
    ),
    capabilities = CameraCapabilities(
        iso = listOf("Auto", "100", "200", "400", "800", "1600", "3200", "6400", "12800"),
        shutter = listOf("1/30", "1/50", "1/60", "1/100", "1/125", "1/250", "1/500", "1/1000"),
        aperture = listOf("1.8", "2.0", "2.8", "4.0", "5.6", "8.0", "11"),
        whiteBalance = listOf("auto", "daylight", "shade", "cloudy", "tungsten", "fluorescent", "flash"),
        advancedSettings = listOf(
            CameraSettingControl("afmethod", "AF method", "face+tracking", listOf("face+tracking", "1-point", "zone")),
            CameraSettingControl("afoperation", "AF operation", "servo", listOf("one-shot", "servo")),
            CameraSettingControl("drivemode", "Drive mode", "single", listOf("single", "high-speed", "timer")),
            CameraSettingControl("meteringmode", "Metering", "evaluative", listOf("evaluative", "partial", "spot")),
            CameraSettingControl("picturestyle", "Picture style", "standard", listOf("standard", "portrait", "landscape", "neutral")),
            CameraSettingControl("stillimagequality", "Image quality", "RAW+L", listOf("RAW+L", "RAW", "C-RAW", "L")),
            CameraSettingControl("moviequality", "Movie quality", "4K", listOf("4K", "FHD")),
        ),
        matrix = CapabilityMatrix.ccapiNetwork(
            supported = setOf(
                CameraFeature.CAMERA_IDENTITY,
                CameraFeature.BATTERY_STATUS,
                CameraFeature.STORAGE_STATUS,
                CameraFeature.LIVE_VIEW,
                CameraFeature.LIVE_VIEW_JPEG_POLLING,
                CameraFeature.STILL_CAPTURE,
                CameraFeature.VIDEO_RECORDING,
                CameraFeature.TAP_FOCUS,
                CameraFeature.EXPOSURE_CONTROL,
                CameraFeature.WHITE_BALANCE_CONTROL,
                CameraFeature.ADVANCED_SETTINGS,
            ),
        ),
        liveView = LiveViewCapabilities.ccapiNetwork(),
        profile = CameraProfile.R6_MARK_III,
    ),
    liveViewFrameUrl = null,
    liveViewBitmap = null,
    liveViewDiagnostics = LiveViewDiagnostics(),
    uiMode = UiMode.CONTROL,
    activeSettingPicker = null,
    error = null,
    errorOperation = null,
)
