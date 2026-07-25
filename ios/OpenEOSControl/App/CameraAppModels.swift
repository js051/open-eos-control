import Foundation
import OpenEOSCore

enum AppScreen: String, Identifiable {
    case control
    case media
    case debug

    var id: String { rawValue }
}

enum AppCaptureMode: String, CaseIterable, Identifiable {
    case photo
    case video

    var id: String { rawValue }
}

enum LiveViewTapAction: String, CaseIterable, Identifiable {
    case focus
    case whiteBalance

    var id: String { rawValue }
}

enum AppConnectionMode: String, CaseIterable, Identifiable {
    case ccapi
    case desktopBridge

    var id: String { rawValue }
}

enum CameraSheet: String, Identifiable {
    case iso
    case shutter
    case aperture
    case whiteBalance
    case liveView
    case more
    case focusDrive
    case language

    var id: String { rawValue }
}

enum CameraOperation: Hashable {
    case connect
    case scan
    case refresh
    case setting
    case capture
    case recording
    case focus
    case liveView
    case media
}

struct FocusMarker: Equatable {
    let x: Double
    let y: Double
    let accepted: Bool
}

struct LiveViewRateTracker {
    private(set) var timestamps: [TimeInterval] = []
    let window: TimeInterval

    init(window: TimeInterval = 2) {
        self.window = max(0.1, window)
    }

    mutating func record(_ timestamp: TimeInterval) -> Double {
        timestamps.append(timestamp)
        let cutoff = timestamp - window
        timestamps.removeAll { $0 < cutoff }
        guard let first = timestamps.first, let last = timestamps.last, last > first else { return 0 }
        return Double(timestamps.count - 1) / (last - first)
    }

    mutating func reset() {
        timestamps.removeAll(keepingCapacity: true)
    }
}

extension CameraSnapshot {
    func replacing(status: CameraStatus) -> CameraSnapshot {
        CameraSnapshot(info: info, status: status, capabilities: capabilities)
    }
}

extension CameraStatus {
    func replacing(exposure: ExposureState? = nil, recording: Bool? = nil) -> CameraStatus {
        CameraStatus(
            connected: connected,
            batteryLevel: batteryLevel,
            batteryStatus: batteryStatus,
            recording: recording ?? self.recording,
            mode: mode,
            mediaAvailable: mediaAvailable,
            remainingMinutes: remainingMinutes,
            exposure: exposure ?? self.exposure,
            storageTotalBytes: storageTotalBytes,
            storageFreeBytes: storageFreeBytes,
            storageFreeImages: storageFreeImages,
            storageDeviceCount: storageDeviceCount,
            rawBatteryJSON: rawBatteryJSON,
            rawStorageJSON: rawStorageJSON
        )
    }
}

extension ExposureState {
    func replacing(key: String, value: String) -> ExposureState {
        ExposureState(
            iso: key == "iso" ? value : iso,
            shutter: key == "shutter" ? value : shutter,
            aperture: key == "aperture" ? value : aperture,
            whiteBalance: key == "whitebalance" ? value : whiteBalance
        )
    }
}

func advancedSettingsForMode(_ settings: [CameraSetting], mode: AppCaptureMode) -> [CameraSetting] {
    let primary = Set(["iso", "shutter", "aperture", "whitebalance"])
    let videoTokens = ["movie", "video", "frame", "codec", "record", "sound"]
    let photoTokens = ["still", "photo", "drive", "imagequality"]
    return settings.filter { setting in
        guard !primary.contains(setting.key) else { return false }
        let key = setting.key.lowercased()
        switch mode {
        case .photo: return !videoTokens.contains(where: key.contains)
        case .video: return !photoTokens.contains(where: key.contains)
        }
    }
}

func settingLabelLocalizationKey(_ key: String) -> String? {
    switch key.lowercased() {
    case "afmethod": "setting_af_method"
    case "afoperation": "setting_af_operation"
    case "drivemode": "setting_drive_mode"
    case "meteringmode": "setting_metering_mode"
    case "flashmode": "setting_flash_mode"
    case "picturestyle": "setting_picture_style"
    case "shootingmode": "setting_shooting_mode"
    case "stillimagequality": "setting_image_quality"
    case "stillimagequality.raw": "setting_image_quality_raw"
    case "stillimagequality.jpeg": "setting_image_quality_jpeg"
    case "stillimagequality.heif": "setting_image_quality_heif"
    case "stillimagequalitysd": "setting_image_quality_sd"
    case "stillimagequalitycf": "setting_image_quality_cf"
    case "moviequality": "setting_movie_quality"
    case "framerate": "setting_frame_rate"
    case "exposurecompensation": "setting_exposure_compensation"
    case "colortemperature": "setting_color_temperature"
    case "whitebalanceadjusta": "setting_white_balance_shift_a"
    case "whitebalanceadjustb": "setting_white_balance_shift_b"
    case "colorspace": "setting_color_space"
    case "aspectratio": "setting_aspect_ratio"
    case "zoomspeed": "setting_power_zoom_speed"
    case "autopoweroff": "setting_auto_power_off"
    case "highisonr": "setting_high_iso_noise_reduction"
    case "continuousaf": "setting_continuous_af"
    case "movieservoaf": "setting_movie_servo_af"
    case "aeb": "setting_aeb"
    default: nil
    }
}

func settingValueLocalizationKey(key: String, value: String) -> String? {
    let normalizedKey = key.lowercased()
    let common = [
        "auto": "camera_value_auto",
        "on": "camera_value_on",
        "off": "camera_value_off",
        "low": "camera_value_low",
        "normal": "camera_value_normal",
        "high": "camera_value_high",
    ]
    if normalizedKey == "autopoweroff" {
        return [
            "0": "camera_value_disable",
            "15": "camera_value_15_seconds",
            "30": "camera_value_30_seconds",
            "60": "camera_value_1_minute",
            "180": "camera_value_3_minutes",
            "300": "camera_value_5_minutes",
            "600": "camera_value_10_minutes",
            "1800": "camera_value_30_minutes",
        ][value]
    }
    if [
        "stillimagequality", "stillimagequality.raw", "stillimagequality.jpeg", "stillimagequality.heif",
        "stillimagequalitysd", "stillimagequalitycf",
    ].contains(normalizedKey) {
        return [
            "none": "camera_value_none",
            "raw": "camera_value_raw",
            "craw": "camera_value_craw",
            "large": "camera_value_large",
            "medium1": "camera_value_medium_1",
            "medium2": "camera_value_medium_2",
            "small": "camera_value_small",
            "large_fine": "camera_value_large_fine",
            "large_normal": "camera_value_large_normal",
            "medium_fine": "camera_value_medium_fine",
            "medium_normal": "camera_value_medium_normal",
            "small1_fine": "camera_value_small_1_fine",
            "small1_normal": "camera_value_small_1_normal",
            "small2": "camera_value_small_2",
            "Large Fine JPEG": "camera_value_large_fine_jpeg",
            "Large Normal JPEG": "camera_value_large_normal_jpeg",
            "Smaller JPEG": "camera_value_smaller_jpeg",
            "cRAW + Large Fine JPEG": "camera_value_craw_large_fine_jpeg",
            "cRAW + Large Normal JPEG": "camera_value_craw_large_normal_jpeg",
            "RAW + Large Fine JPEG": "camera_value_raw_large_fine_jpeg",
            "RAW + Large Normal JPEG": "camera_value_raw_large_normal_jpeg",
            "cRAW + Smaller JPEG": "camera_value_craw_smaller_jpeg",
            "RAW + Smaller JPEG": "camera_value_raw_smaller_jpeg",
            "RAW": "camera_value_raw",
            "cRAW": "camera_value_craw",
        ][value]
    }
    return common[value.lowercased()]
}
