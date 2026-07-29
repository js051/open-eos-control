import Foundation

public enum CameraFeature: String, CaseIterable, Codable, Hashable, Sendable {
    case cameraIdentity = "CAMERA_IDENTITY"
    case batteryStatus = "BATTERY_STATUS"
    case storageStatus = "STORAGE_STATUS"
    case liveView = "LIVE_VIEW"
    case liveViewJPEGPolling = "LIVE_VIEW_JPEG_POLLING"
    case liveViewRTP = "LIVE_VIEW_RTP"
    case liveViewMagnification = "LIVE_VIEW_MAGNIFICATION"
    case stillCapture = "STILL_CAPTURE"
    case bulbExposure = "BULB_EXPOSURE"
    case autofocus = "AUTOFOCUS"
    case shutterHalfPress = "SHUTTER_HALF_PRESS"
    case videoRecording = "VIDEO_RECORDING"
    case tapFocus = "TAP_FOCUS"
    case clickWhiteBalance = "CLICK_WHITE_BALANCE"
    case focusDrive = "FOCUS_DRIVE"
    case exposureControl = "EXPOSURE_CONTROL"
    case whiteBalanceControl = "WHITE_BALANCE_CONTROL"
    case advancedSettings = "ADVANCED_SETTINGS"
    case mediaBrowser = "MEDIA_BROWSER"
    case mediaThumbnail = "MEDIA_THUMBNAIL"
    case mediaPreview = "MEDIA_PREVIEW"
    case mediaDownload = "MEDIA_DOWNLOAD"
    case mediaDelete = "MEDIA_DELETE"
    case desktopBridge = "DESKTOP_BRIDGE"
    case usbDiagnostics = "USB_DIAGNOSTICS"
}

public struct CapabilityMatrix: Equatable, Sendable {
    public var supported: Set<CameraFeature>
    public var planned: Set<CameraFeature>
    public var reasons: [CameraFeature: String]

    public init(
        supported: Set<CameraFeature> = [],
        planned: Set<CameraFeature> = [],
        reasons: [CameraFeature: String] = [:]
    ) {
        self.supported = supported
        self.planned = planned
        self.reasons = reasons
    }

    public func supports(_ feature: CameraFeature) -> Bool {
        supported.contains(feature)
    }
}

public enum CameraModelFamily: String, Codable, Sendable {
    case eosR
    case eosDSLR
    case eosM
    case powerShot
    case unknown
}

public enum CameraModelPriority: String, Codable, Sendable {
    case primary
    case supported
    case research
}

public struct CameraProfile: Equatable, Codable, Sendable {
    public let modelName: String
    public let family: CameraModelFamily
    public let priority: CameraModelPriority

    public init(modelName: String, family: CameraModelFamily, priority: CameraModelPriority) {
        self.modelName = modelName
        self.family = family
        self.priority = priority
    }

    public static func from(modelName: String) -> CameraProfile {
        let normalized = modelName.lowercased()
        if normalized.contains("eos r6 mark iii") || normalized.contains("r6m3") || normalized.contains("r63") {
            return CameraProfile(modelName: modelName, family: .eosR, priority: .primary)
        }
        if normalized.contains("eos r") {
            return CameraProfile(modelName: modelName, family: .eosR, priority: .supported)
        }
        if normalized.contains("eos m") {
            return CameraProfile(modelName: modelName, family: .eosM, priority: .supported)
        }
        if normalized.contains("eos") {
            return CameraProfile(modelName: modelName, family: .eosDSLR, priority: .supported)
        }
        if normalized.contains("powershot") {
            return CameraProfile(modelName: modelName, family: .powerShot, priority: .research)
        }
        return CameraProfile(
            modelName: modelName.isEmpty ? "Canon Camera" : modelName,
            family: .unknown,
            priority: .research
        )
    }
}

public struct CameraInfo: Equatable, Codable, Sendable {
    public let connected: Bool
    public let model: String
    public let serial: String
    public let api: String

    public init(connected: Bool = true, model: String, serial: String, api: String) {
        self.connected = connected
        self.model = model
        self.serial = serial
        self.api = api
    }
}

public struct ExposureState: Equatable, Codable, Sendable {
    public let iso: String
    public let shutter: String
    public let aperture: String
    public let whiteBalance: String

    public init(iso: String = "-", shutter: String = "-", aperture: String = "-", whiteBalance: String = "-") {
        self.iso = iso
        self.shutter = shutter
        self.aperture = aperture
        self.whiteBalance = whiteBalance
    }
}

public struct CameraStatus: Equatable, Sendable {
    public let connected: Bool
    public let batteryLevel: Int?
    public let batteryStatus: String
    public let recording: Bool?
    public let bulbExposureActive: Bool?
    public let mode: String
    public let mediaAvailable: Bool?
    public let remainingMinutes: Int?
    public let exposure: ExposureState
    public let storageTotalBytes: Int64?
    public let storageFreeBytes: Int64?
    public let storageFreeImages: Int64?
    public let storageDeviceCount: Int?
    public let rawBatteryJSON: String
    public let rawStorageJSON: String

    public init(
        connected: Bool = true,
        batteryLevel: Int? = nil,
        batteryStatus: String = "unknown",
        recording: Bool? = nil,
        bulbExposureActive: Bool? = nil,
        mode: String = "unknown",
        mediaAvailable: Bool? = nil,
        remainingMinutes: Int? = nil,
        exposure: ExposureState = ExposureState(),
        storageTotalBytes: Int64? = nil,
        storageFreeBytes: Int64? = nil,
        storageFreeImages: Int64? = nil,
        storageDeviceCount: Int? = nil,
        rawBatteryJSON: String = "null",
        rawStorageJSON: String = "null"
    ) {
        self.connected = connected
        self.batteryLevel = batteryLevel
        self.batteryStatus = batteryStatus
        self.recording = recording
        self.bulbExposureActive = bulbExposureActive
        self.mode = mode
        self.mediaAvailable = mediaAvailable
        self.remainingMinutes = remainingMinutes
        self.exposure = exposure
        self.storageTotalBytes = storageTotalBytes
        self.storageFreeBytes = storageFreeBytes
        self.storageFreeImages = storageFreeImages
        self.storageDeviceCount = storageDeviceCount
        self.rawBatteryJSON = rawBatteryJSON
        self.rawStorageJSON = rawStorageJSON
    }

    public func withBulbExposureActive(_ active: Bool?) -> CameraStatus {
        CameraStatus(
            connected: connected,
            batteryLevel: batteryLevel,
            batteryStatus: batteryStatus,
            recording: recording,
            bulbExposureActive: active,
            mode: mode,
            mediaAvailable: mediaAvailable,
            remainingMinutes: remainingMinutes,
            exposure: exposure,
            storageTotalBytes: storageTotalBytes,
            storageFreeBytes: storageFreeBytes,
            storageFreeImages: storageFreeImages,
            storageDeviceCount: storageDeviceCount,
            rawBatteryJSON: rawBatteryJSON,
            rawStorageJSON: rawStorageJSON
        )
    }
}

public struct CameraSetting: Identifiable, Equatable, Sendable {
    public let key: String
    public let label: String
    public var value: String
    public let values: [String]

    public var id: String { key }

    public init(key: String, label: String, value: String, values: [String]) {
        self.key = key
        self.label = label
        self.value = value
        self.values = values
    }
}

public enum LiveViewSource: String, Codable, Sendable {
    case auto
    case ccapiJPEGPolling
    case ccapiRTP
    case desktopBridgeStream
    case simulatorFrame
}

public enum LiveViewSize: String, CaseIterable, Codable, Sendable {
    case small
    case medium
    case large
}

public struct LiveViewRequest: Equatable, Codable, Sendable {
    public var fps: Int
    public var size: LiveViewSize
    public var source: LiveViewSource

    public init(fps: Int = 6, size: LiveViewSize = .medium, source: LiveViewSource = .auto) {
        self.fps = fps
        self.size = size
        self.source = source
    }

    public func clamped(to capabilities: LiveViewCapabilities) -> LiveViewRequest {
        LiveViewRequest(
            fps: min(max(fps, capabilities.minimumFPS), capabilities.maximumFPS),
            size: capabilities.sizes.contains(size) ? size : capabilities.defaultSize,
            source: source == .auto || capabilities.sources.contains(source) ? source : capabilities.defaultSource
        )
    }
}

public struct LiveViewCapabilities: Equatable, Sendable {
    public let sources: [LiveViewSource]
    public let defaultSource: LiveViewSource
    public let sizes: [LiveViewSize]
    public let defaultSize: LiveViewSize
    public let minimumFPS: Int
    public let maximumFPS: Int

    public init(
        sources: [LiveViewSource] = [],
        defaultSource: LiveViewSource = .auto,
        sizes: [LiveViewSize] = [],
        defaultSize: LiveViewSize = .medium,
        minimumFPS: Int = 1,
        maximumFPS: Int = 30
    ) {
        self.sources = sources
        self.defaultSource = defaultSource
        self.sizes = sizes
        self.defaultSize = defaultSize
        self.minimumFPS = minimumFPS
        self.maximumFPS = maximumFPS
    }
}

public struct CameraCapabilityEvidence: Equatable, Sendable {
    public let source: String
    public let protocolVersions: [String]
    public let advertisedCommands: [String]
    public let writableSettings: [String]
    public let observedFeatures: Set<CameraFeature>
    public let truncated: Bool

    public init(
        source: String = "unknown",
        protocolVersions: [String] = [],
        advertisedCommands: [String] = [],
        writableSettings: [String] = [],
        observedFeatures: Set<CameraFeature> = [],
        truncated: Bool = false
    ) {
        self.source = source
        self.protocolVersions = protocolVersions
        self.advertisedCommands = advertisedCommands
        self.writableSettings = writableSettings
        self.observedFeatures = observedFeatures
        self.truncated = truncated
    }
}

public struct CameraCapabilities: Equatable, Sendable {
    public let settings: [CameraSetting]
    public let matrix: CapabilityMatrix
    public let liveView: LiveViewCapabilities
    public let profile: CameraProfile
    public let evidence: CameraCapabilityEvidence

    public init(
        settings: [CameraSetting],
        matrix: CapabilityMatrix,
        liveView: LiveViewCapabilities,
        profile: CameraProfile,
        evidence: CameraCapabilityEvidence = CameraCapabilityEvidence()
    ) {
        self.settings = settings
        self.matrix = matrix
        self.liveView = liveView
        self.profile = profile
        self.evidence = evidence
    }

    public func setting(_ key: String) -> CameraSetting? {
        settings.first { $0.key == key }
    }
}

public struct LiveViewFrame: Equatable, Sendable {
    public let data: Data
    public let contentType: String?
    public let sourceURL: URL

    public init(data: Data, contentType: String?, sourceURL: URL) {
        self.data = data
        self.contentType = contentType
        self.sourceURL = sourceURL
    }
}

public struct FocusResult: Equatable, Sendable {
    public let accepted: Bool
    public let x: Double
    public let y: Double

    public init(accepted: Bool, x: Double, y: Double) {
        self.accepted = accepted
        self.x = x
        self.y = y
    }
}

public enum FocusDriveDirection: String, CaseIterable, Codable, Sendable {
    case near
    case far
}

public enum FocusDriveStep: String, CaseIterable, Codable, Sendable {
    case small
    case medium
    case large
}

public struct FocusDriveResult: Equatable, Sendable {
    public let accepted: Bool
    public let direction: FocusDriveDirection
    public let step: FocusDriveStep

    public init(accepted: Bool, direction: FocusDriveDirection, step: FocusDriveStep) {
        self.accepted = accepted
        self.direction = direction
        self.step = step
    }
}

public enum LiveViewMagnification: Int, CaseIterable, Codable, Sendable {
    case x1 = 1
    case x5 = 5
}

public struct LiveViewMagnificationResult: Equatable, Sendable {
    public let accepted: Bool
    public let magnification: LiveViewMagnification

    public init(accepted: Bool, magnification: LiveViewMagnification) {
        self.accepted = accepted
        self.magnification = magnification
    }
}

public struct CameraMediaItem: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let kind: String
    public let sizeBytes: Int64?
    public let captureTime: String?
    public let previewAvailable: Bool

    public init(
        id: String,
        name: String,
        kind: String,
        sizeBytes: Int64? = nil,
        captureTime: String? = nil,
        previewAvailable: Bool = false
    ) {
        self.id = id
        self.name = name
        self.kind = kind
        self.sizeBytes = sizeBytes
        self.captureTime = captureTime
        self.previewAvailable = previewAvailable
    }
}

public struct CameraMediaDownload: Equatable, Sendable {
    public let item: CameraMediaItem
    public let fileURL: URL
    public let bytesTransferred: Int64
    public let contentType: String?

    public init(item: CameraMediaItem, fileURL: URL, bytesTransferred: Int64, contentType: String?) {
        self.item = item
        self.fileURL = fileURL
        self.bytesTransferred = bytesTransferred
        self.contentType = contentType
    }
}

public struct CameraMediaTransferProgress: Equatable, Sendable {
    public let bytesTransferred: Int64
    public let totalBytes: Int64?

    public init(bytesTransferred: Int64, totalBytes: Int64?) {
        self.bytesTransferred = max(0, bytesTransferred)
        self.totalBytes = totalBytes.flatMap { $0 > 0 ? $0 : nil }
    }

    public var fractionCompleted: Double? {
        guard let totalBytes else { return nil }
        return min(1, Double(bytesTransferred) / Double(totalBytes))
    }
}

public typealias CameraMediaProgressHandler = @Sendable (CameraMediaTransferProgress) -> Void

public struct CameraMediaThumbnail: Equatable, Sendable {
    public let item: CameraMediaItem
    public let data: Data
    public let contentType: String?

    public init(item: CameraMediaItem, data: Data, contentType: String?) {
        self.item = item
        self.data = data
        self.contentType = contentType
    }
}

public struct CameraMediaPreview: Equatable, Sendable {
    public let item: CameraMediaItem
    public let data: Data
    public let contentType: String?

    public init(item: CameraMediaItem, data: Data, contentType: String?) {
        self.item = item
        self.data = data
        self.contentType = contentType
    }
}

public struct CameraSnapshot: Equatable, Sendable {
    public let info: CameraInfo
    public let status: CameraStatus
    public let capabilities: CameraCapabilities

    public init(info: CameraInfo, status: CameraStatus, capabilities: CameraCapabilities) {
        self.info = info
        self.status = status
        self.capabilities = capabilities
    }
}
