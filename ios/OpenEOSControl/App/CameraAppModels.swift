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
    case actions
    case iso
    case shutter
    case aperture
    case whiteBalance
    case liveView
    case monitoring
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
    case directory
    case clock
    case maintenance
    case power
    case capture
    case recording
    case focus
    case liveView
    case mediaLibrary
    case media
}

enum MediaLibraryLoadStatus: String {
    case notLoaded = "NOT_LOADED"
    case loading = "LOADING"
    case complete = "COMPLETE"
    case cancelled = "CANCELLED"
    case failed = "FAILED"
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
            bulbExposureActive: bulbExposureActive,
            mode: mode,
            mediaAvailable: mediaAvailable,
            remainingMinutes: remainingMinutes,
            exposure: exposure ?? self.exposure,
            storageTotalBytes: storageTotalBytes,
            storageFreeBytes: storageFreeBytes,
            storageFreeImages: storageFreeImages,
            storageDeviceCount: storageDeviceCount,
            recordableShots: recordableShots,
            remainingRecordingSeconds: remainingRecordingSeconds,
            rawBatteryJSON: rawBatteryJSON,
            rawStorageJSON: rawStorageJSON,
            rawRecordableJSON: rawRecordableJSON,
            lens: lens,
            temperature: temperature
        )
    }
}

extension CameraCapabilities {
    func replacingSetting(key: String, value: String) -> CameraCapabilities {
        CameraCapabilities(
            settings: settings.map { setting in
                guard setting.key == key else { return setting }
                return CameraSetting(
                    key: setting.key,
                    label: setting.label,
                    value: value,
                    values: setting.values,
                    inputKind: setting.inputKind,
                    maxLength: setting.maxLength
                )
            },
            matrix: matrix,
            liveView: liveView,
            profile: profile,
            evidence: evidence
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
    let videoOnlyPrefixes = ["windfilter", "attenuator"]
    let photoTokens = ["still", "photo", "drive", "imagequality", "capturetarget", "capturestorage", "directory"]
    return settings.filter { setting in
        guard setting.inputKind == .text || Set(setting.values).count > 1 else { return false }
        guard !primary.contains(setting.key) else { return false }
        guard setting.key.lowercased() != "moviemode" else { return false }
        let key = setting.key.lowercased()
        switch mode {
        case .photo:
            return !videoOnlyPrefixes.contains(where: key.hasPrefix) &&
                !videoTokens.contains(where: key.contains)
        case .video: return !key.hasPrefix("focusbracketing") && !photoTokens.contains(where: key.contains)
        }
    }
}

func captureModeSetting(_ settings: [CameraSetting]) -> CameraSetting? {
    settings.first { $0.key.lowercased() == "moviemode" }
        ?? settings.first { ["shootingmode", "autoexposuremode", "ae"].contains($0.key.lowercased()) }
}

func appCaptureMode(for setting: CameraSetting) -> AppCaptureMode? {
    let key = setting.key.lowercased()
    let token = cameraModeToken(setting.value)
    if key == "moviemode" {
        if token == "on" { return .video }
        if token == "off" { return .photo }
        return nil
    }
    guard !token.isEmpty, !token.hasPrefix("unknown"), !token.hasPrefix("0x") else { return nil }
    return token.contains("movie") || token.contains("video") ? .video : .photo
}

func captureModeValue(
    for mode: AppCaptureMode,
    setting: CameraSetting,
    preferredPhotoValue: String?
) -> String? {
    if setting.key.lowercased() == "moviemode" {
        let expected = mode == .video ? "on" : "off"
        return setting.values.first { cameraModeToken($0) == expected }
    }
    switch mode {
    case .video:
        return setting.values.first { value in
            let token = cameraModeToken(value)
            return token.contains("movie") || token.contains("video")
        }
    case .photo:
        return [preferredPhotoValue, setting.value]
            .compactMap { $0 }
            .compactMap { candidate in
                setting.values.first { cameraModeToken($0) == cameraModeToken(candidate) }
            }
            .first { value in
                let token = cameraModeToken(value)
                return !token.contains("movie") && !token.contains("video")
            }
    }
}

private func cameraModeToken(_ value: String) -> String {
    value.lowercased().filter { $0.isLetter || $0.isNumber }
}

func settingLabelLocalizationKey(_ key: String) -> String? {
    switch key.lowercased() {
    case "afmethod": "setting_af_method"
    case "afoperation": "setting_af_operation"
    case "drivemode": "setting_drive_mode"
    case "meteringmode": "setting_metering_mode"
    case "flashmode": "setting_flash_mode"
    case "picturestyle": "setting_picture_style"
    case "moviemode": "setting_movie_mode"
    case "shootingmode": "setting_shooting_mode"
    case "stillimagequality": "setting_image_quality"
    case "stillimagequality.raw": "setting_image_quality_raw"
    case "stillimagequality.jpeg": "setting_image_quality_jpeg"
    case "stillimagequality.heif": "setting_image_quality_heif"
    case "stillimagequalitysd": "setting_image_quality_sd"
    case "stillimagequalitycf": "setting_image_quality_cf"
    case "moviequality": "setting_movie_quality"
    case "highframerate": "setting_high_frame_rate"
    case "moviecropping": "setting_movie_cropping"
    case "movieformat": "setting_movie_format"
    case "framerate": "setting_frame_rate"
    case "exposurecompensation": "setting_exposure_compensation"
    case "colortemperature": "setting_color_temperature"
    case "whitebalanceadjusta": "setting_white_balance_shift_a"
    case "whitebalanceadjustb": "setting_white_balance_shift_b"
    case "wbshift.ba": "setting_white_balance_shift_ba"
    case "wbshift.mg": "setting_white_balance_shift_mg"
    case "colorspace": "setting_color_space"
    case "aspectratio": "setting_aspect_ratio"
    case "zoom": "setting_zoom"
    case "zoomspeed": "setting_power_zoom_speed"
    case "autopoweroff": "setting_auto_power_off"
    case "beep": "setting_beep"
    case "displayoff": "setting_display_off"
    case "capturetarget": "setting_capture_target"
    case "capturestorage": "setting_capture_storage"
    case "cardselectionstillimage": "setting_still_image_card"
    case "cardselectionmovie": "setting_movie_card"
    case "directoryselection": "setting_capture_directory"
    case "soundrecordinglevel": "setting_sound_recording_level"
    case "soundrecordinglevelintmic": "setting_internal_microphone_level"
    case "soundrecordinglevelextmic": "setting_external_microphone_level"
    case "soundrecordinglevelacc": "setting_accessory_microphone_level"
    case "soundrecording": "setting_sound_recording"
    case "soundrecordingmodeintmic": "setting_internal_microphone_mode"
    case "soundrecordingmodeextmic": "setting_external_microphone_mode"
    case "soundrecordingmodeacc": "setting_accessory_microphone_mode"
    case "windfilter": "setting_wind_filter"
    case "windfilterintmic": "setting_internal_microphone_wind_filter"
    case "windfilterextmic": "setting_external_microphone_wind_filter"
    case "windfilteracc": "setting_accessory_microphone_wind_filter"
    case "attenuator": "setting_attenuator"
    case "attenuatorintmic": "setting_internal_microphone_attenuator"
    case "attenuatorextmic": "setting_external_microphone_attenuator"
    case "attenuatoracc": "setting_accessory_microphone_attenuator"
    case "focusbracketing": "setting_focus_bracketing"
    case "focusbracketingnumberofshots": "setting_focus_bracketing_shots"
    case "focusbracketingfocusincrement": "setting_focus_bracketing_increment"
    case "focusbracketingexposuresmoothing": "setting_focus_bracketing_exposure_smoothing"
    case "highisonr": "setting_high_iso_noise_reduction"
    case "alomode": "setting_auto_lighting_optimizer"
    case "continuousaf": "setting_continuous_af"
    case "movieservoaf": "setting_movie_servo_af"
    case "aeb": "setting_aeb"
    case "ownername": "setting_owner_name"
    case "artist": "setting_artist"
    case "copyright": "setting_copyright"
    case "nickname": "setting_nickname"
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
        "standard": "camera_value_standard",
        "manual": "camera_value_manual",
        "enable": "camera_value_enable",
        "disable": "camera_value_disable",
    ]
    if normalizedKey == "alomode" {
        return [
            "standard (disabled in manual exposure)": "camera_value_standard_disabled_manual",
            "low (disabled in manual exposure)": "camera_value_low_disabled_manual",
            "high (disabled in manual exposure)": "camera_value_high_disabled_manual",
            "off (disabled in manual exposure)": "camera_value_off_disabled_manual",
        ][value.lowercased()] ?? common[value.lowercased()]
    }
    if normalizedKey == "autopoweroff" {
        return [
            "0": "camera_value_disable",
            "15": "camera_value_15_seconds",
            "30": "camera_value_30_seconds",
            "60": "camera_value_1_minute",
            "120": "camera_value_2_minutes",
            "180": "camera_value_3_minutes",
            "300": "camera_value_5_minutes",
            "600": "camera_value_10_minutes",
            "1800": "camera_value_30_minutes",
        ][value]
    }
    if normalizedKey == "beep", value.lowercased() == "disabletouch" {
        return "camera_value_disable_touch"
    }
    if normalizedKey == "displayoff" {
        return [
            "10": "camera_value_10_seconds",
            "20": "camera_value_20_seconds",
            "30": "camera_value_30_seconds",
            "60": "camera_value_1_minute",
            "120": "camera_value_2_minutes",
            "180": "camera_value_3_minutes",
        ][value]
    }
    if normalizedKey == "capturetarget" {
        return [
            "internal ram": "camera_value_internal_ram",
            "sdram": "camera_value_internal_ram",
            "memory card": "camera_value_memory_card",
            "card": "camera_value_memory_card",
        ][value.lowercased()]
    }
    if normalizedKey == "capturestorage" {
        return [
            "card 1": "camera_value_card_1",
            "card 2": "camera_value_card_2",
        ][value.lowercased()]
    }
    if ["cardselectionstillimage", "cardselectionmovie"].contains(normalizedKey) {
        return [
            "none": "camera_value_none",
            "card1": "camera_value_card_1",
            "card2": "camera_value_card_2",
        ][value.lowercased()]
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

func movieQualityDisplayValue(
    _ rawValue: String,
    lightLabel: String = "Light",
    cropLabel: String = "Crop",
    fineLabel: String = "Fine"
) -> String? {
    let parts = rawValue.split(separator: "_", omittingEmptySubsequences: false).map(String.init)
    guard (4...5).contains(parts.count), parts.allSatisfy({ !$0.isEmpty }) else { return nil }
    let size: String
    switch parts[0].lowercased() {
    case "4k": size = "4K"
    case "fhd": size = "FHD"
    case "hd": size = "HD"
    default: size = parts[0]
    }
    guard
        (4...5).contains(parts[1].count),
        parts[1].allSatisfy(\.isNumber),
        let frameRateHundredths = Int(parts[1])
    else { return nil }
    let frameRate = String(format: "%d.%02dp", frameRateHundredths / 100, frameRateHundredths % 100)
    let compression: String
    switch parts[2].lowercased() {
    case "raw": compression = "RAW"
    case "alli": compression = "ALL-I"
    case "ipb": compression = "IPB"
    case "longgop": compression = "Long GOP"
    default: return nil
    }
    var labels = [size, frameRate, compression]
    switch parts[3].lowercased() {
    case "standard": break
    case "light": labels.append(lightLabel)
    default: return nil
    }
    if parts.count == 5 {
        switch parts[4].lowercased() {
        case "crop": labels.append(cropLabel)
        case "fine": labels.append(fineLabel)
        default: return nil
        }
    }
    return labels.joined(separator: " / ")
}

func movieFormatDisplayValue(_ rawValue: String) -> String? {
    let normalized = rawValue.lowercased().filter { $0.isLetter || $0.isNumber }
    if normalized == "raw" { return "RAW" }
    if normalized == "mp4" { return "MP4" }
    return [
        "xfhevcsycc42210bit": "XF-HEVC S / 4:2:2 / 10-bit",
        "xfhevcsycc42010bit": "XF-HEVC S / 4:2:0 / 10-bit",
        "xfavcsycc42210bit": "XF-AVC S / 4:2:2 / 10-bit",
        "xfavcsycc4208bit": "XF-AVC S / 4:2:0 / 8-bit",
    ][normalized]
}
