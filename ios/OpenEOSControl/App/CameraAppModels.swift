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

enum CameraSheet: String, Identifiable {
    case iso
    case shutter
    case aperture
    case whiteBalance
    case liveView
    case more
    case language

    var id: String { rawValue }
}

enum CameraOperation: Hashable {
    case connect
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
