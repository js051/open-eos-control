import Foundation
import CoreFoundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum CCAPIConnectionMode: String, Codable, Sendable {
    case automatic
    case camera
    case simulator
}

private enum HTTPMethod: String, CaseIterable, Sendable {
    case get = "GET"
    case put = "PUT"
    case post = "POST"
    case delete = "DELETE"
    case patch = "PATCH"
}

private struct CCAPIOperation: Hashable, Sendable {
    let method: HTTPMethod
    let path: String
}

private struct CCAPIMultipartOperations: Sendable {
    let startLiveView: CCAPIOperation
    let stopLiveView: CCAPIOperation
    let openStream: CCAPIOperation
    let closeStream: CCAPIOperation
}

private struct CCAPIJPEGLiveViewOperations: Sendable {
    let start: CCAPIOperation
    let stop: CCAPIOperation
    let framePaths: [String]
}

private struct CCAPILiveViewGeometry: Sendable {
    let positionX: Int
    let positionY: Int
    let positionWidth: Int
    let positionHeight: Int

    func cameraPosition(normalizedX: Double, normalizedY: Double) -> (x: Int, y: Int) {
        let rawX = positionX + Int(normalizedX * Double(positionWidth))
        let rawY = positionY + Int(normalizedY * Double(positionHeight))
        return (
            min(max(rawX, positionX), positionX + positionWidth - 1),
            min(max(rawY, positionY), positionY + positionHeight - 1)
        )
    }
}

private struct CCAPIDetailedLiveView {
    let image: Data?
    let geometry: CCAPILiveViewGeometry?
}

public actor CCAPIClient {
    private static let maxDeviceStatusTextCharacters = 512
    private static let maximumErrorBodyCharacters = 2_000
    private static let maximumMediaItems = 500
    private static let noAPIListValue = "No list of APIs"
    private static let developerAPIPath = "/ccapi/ver100/topurlfordev"
    private static let maximumMediaPages = 100
    private static let maximumMediaTreeDepth = 4
    private static let maximumMediaThumbnailBytes = 8 * 1024 * 1024
    private static let maximumMediaPreviewBytes = 32 * 1024 * 1024
    private static let mediaRotations = Set([0, 90, 180, 270])
    private static let maximumCapabilityEvidenceItems = 256
    private static let maximumCapabilityEvidenceItemCharacters = 512
    private static let maximumDiscoveryTraceAttempts = 16
    private static let maximumDiscoveryTraceKeys = 32
    private static let maximumDiscoveryTraceKeyCharacters = 64
    private static let maximumEventBytes = 256 * 1024
    private static let maximumEventKeys = 64
    private static let maximumEventKeyCharacters = 128
    private static let maximumRTPSessionDescriptionBytes = 64 * 1024
    private static let halfPressNanoseconds: UInt64 = 350_000_000
    private static let multipartStartRetryNanoseconds: [UInt64] = [
        100_000_000,
        200_000_000,
        400_000_000,
        800_000_000,
    ]
    private static let jpegFrameBusyRetryNanoseconds: [UInt64] = [50_000_000, 100_000_000]
    private static let imageQualitySettingKey = "stillimagequality"
    private static let imageQualityFields = ["raw", "jpeg", "heif"]
    private static let wbShiftSettingKey = "wbshift"
    private static let wbShiftFields = ["ba", "mg"]
    private static let zoomSettingKey = "zoom"
    private static let zoomPathSuffix = "/shooting/control/zoom"
    private static let liveViewMagnificationPathSuffix = "/shooting/settings/lvzoom"
    private static let liveViewMagnificationSettingKey = "lvzoom"
    private static let liveViewMagnificationValues = Set(["1", "5", "10"])
    private static let movieModeSettingKey = "moviemode"
    private static let movieModePathSuffix = "/shooting/control/moviemode"
    private static let movieModeValues = ["off", "on"]
    private static let stillCardSelectionSettingKey = "cardselectionstillimage"
    private static let movieCardSelectionSettingKey = "cardselectionmovie"
    private static let cardSelectionValues = ["none", "card1", "card2"]
    private static let cardSelectionEndpoints = [
        (key: stillCardSelectionSettingKey, suffix: "/functions/cardselection/stillimage"),
        (key: movieCardSelectionSettingKey, suffix: "/functions/cardselection/movie"),
    ]
    private static let cardSelectionSettingKeys = Set(
        cardSelectionEndpoints.map { $0.key }
    )
    private static let beepSettingKey = "beep"
    private static let displayOffSettingKey = "displayoff"
    private static let autoPowerOffSettingKey = "autopoweroff"
    private static let autoPowerOffImmediately = "immediately"
    private static let autoPowerOffSettingValues = Set(["30", "60", "120", "180", "300", "600", "disable"])
    private static let deviceFunctionSettingEndpoints = [
        (
            key: beepSettingKey,
            suffix: "/functions/beep",
            simulatorPath: "/ccapi/device-settings/beep",
            values: Set(["enable", "disable", "disabletouch"])
        ),
        (
            key: displayOffSettingKey,
            suffix: "/functions/displayoff",
            simulatorPath: "/ccapi/device-settings/display-off",
            values: Set(["10", "20", "30", "60", "120", "180"])
        ),
        (
            key: autoPowerOffSettingKey,
            suffix: "/functions/autopoweroff",
            simulatorPath: "/ccapi/device-settings/auto-power-off",
            values: autoPowerOffSettingValues.union([autoPowerOffImmediately])
        ),
    ]
    private static let deviceFunctionSettingKeys = Set(deviceFunctionSettingEndpoints.map { $0.key })
    private static let soundRecordingLevelSettingKey = "soundrecordinglevel"
    private static let soundRecordingLevelIntMicSettingKey = "soundrecordinglevelintmic"
    private static let soundRecordingLevelExtMicSettingKey = "soundrecordinglevelextmic"
    private static let soundRecordingLevelAccessorySettingKey = "soundrecordinglevelacc"
    private static let soundRecordingLevelEndpoints = [
        (
            key: soundRecordingLevelSettingKey,
            suffix: "/shooting/settings/soundrecording/level",
            simulatorPath: "/ccapi/sound-recording-level"
        ),
        (
            key: soundRecordingLevelIntMicSettingKey,
            suffix: "/shooting/settings/soundrecording/level/intmic",
            simulatorPath: "/ccapi/sound-recording-level/internal-mic"
        ),
        (
            key: soundRecordingLevelExtMicSettingKey,
            suffix: "/shooting/settings/soundrecording/level/extmic",
            simulatorPath: "/ccapi/sound-recording-level/external-mic"
        ),
        (
            key: soundRecordingLevelAccessorySettingKey,
            suffix: "/shooting/settings/soundrecording/level/acc",
            simulatorPath: "/ccapi/sound-recording-level/accessory"
        ),
    ]
    private static let soundRecordingLevelSettingKeys = Set(soundRecordingLevelEndpoints.map(\.key))
    private static let simulatorSoundRecordingLevelValues = Set((0...63).map(String.init))
    private static let soundRecordingSettingKey = "soundrecording"
    private static let soundRecordingModeIntMicSettingKey = "soundrecordingmodeintmic"
    private static let soundRecordingModeExtMicSettingKey = "soundrecordingmodeextmic"
    private static let soundRecordingModeAccessorySettingKey = "soundrecordingmodeacc"
    private static let windFilterSettingKey = "windfilter"
    private static let windFilterIntMicSettingKey = "windfilterintmic"
    private static let windFilterExtMicSettingKey = "windfilterextmic"
    private static let windFilterAccessorySettingKey = "windfilteracc"
    private static let attenuatorSettingKey = "attenuator"
    private static let attenuatorIntMicSettingKey = "attenuatorintmic"
    private static let attenuatorExtMicSettingKey = "attenuatorextmic"
    private static let attenuatorAccessorySettingKey = "attenuatoracc"
    private static let soundRecordingEndpoints = [
        (
            key: soundRecordingSettingKey,
            suffix: "/shooting/settings/soundrecording",
            simulatorPath: "/ccapi/sound-recording",
            values: Set(["auto", "manual", "enable", "disable"])
        ),
        (
            key: soundRecordingModeIntMicSettingKey,
            suffix: "/shooting/settings/soundrecording/mode/intmic",
            simulatorPath: "/ccapi/sound-recording-mode/internal-mic",
            values: Set(["auto", "manual", "disable"])
        ),
        (
            key: soundRecordingModeExtMicSettingKey,
            suffix: "/shooting/settings/soundrecording/mode/extmic",
            simulatorPath: "/ccapi/sound-recording-mode/external-mic",
            values: Set(["auto", "manual", "disable"])
        ),
        (
            key: soundRecordingModeAccessorySettingKey,
            suffix: "/shooting/settings/soundrecording/mode/acc",
            simulatorPath: "/ccapi/sound-recording-mode/accessory",
            values: Set(["auto", "manual", "disable"])
        ),
        (
            key: windFilterSettingKey,
            suffix: "/shooting/settings/soundrecording/windfilter",
            simulatorPath: "/ccapi/wind-filter",
            values: Set(["auto", "enable", "disable"])
        ),
        (
            key: windFilterIntMicSettingKey,
            suffix: "/shooting/settings/soundrecording/windfilter/intmic",
            simulatorPath: "/ccapi/wind-filter/internal-mic",
            values: Set(["auto", "enable", "disable"])
        ),
        (
            key: windFilterExtMicSettingKey,
            suffix: "/shooting/settings/soundrecording/windfilter/extmic",
            simulatorPath: "/ccapi/wind-filter/external-mic",
            values: Set(["auto", "enable", "disable"])
        ),
        (
            key: windFilterAccessorySettingKey,
            suffix: "/shooting/settings/soundrecording/windfilter/acc",
            simulatorPath: "/ccapi/wind-filter/accessory",
            values: Set(["auto", "enable", "disable"])
        ),
        (
            key: attenuatorSettingKey,
            suffix: "/shooting/settings/soundrecording/attenuator",
            simulatorPath: "/ccapi/attenuator",
            values: Set(["enable", "disable", "auto", "manual"])
        ),
        (
            key: attenuatorIntMicSettingKey,
            suffix: "/shooting/settings/soundrecording/attenuator/intmic",
            simulatorPath: "/ccapi/attenuator/internal-mic",
            values: Set(["enable", "disable", "auto", "manual"])
        ),
        (
            key: attenuatorExtMicSettingKey,
            suffix: "/shooting/settings/soundrecording/attenuator/extmic",
            simulatorPath: "/ccapi/attenuator/external-mic",
            values: Set(["enable", "disable", "auto", "manual"])
        ),
        (
            key: attenuatorAccessorySettingKey,
            suffix: "/shooting/settings/soundrecording/attenuator/acc",
            simulatorPath: "/ccapi/attenuator/accessory",
            values: Set(["enable", "disable", "auto", "manual"])
        ),
    ]
    private static let soundRecordingSettingKeys = Set(soundRecordingEndpoints.map { $0.key })
    private static let maximumStructuredSettingOptions = 256
    private static let maximumFocusBracketingOptions = 1024
    private static let focusBracketingSettingKey = "focusbracketing"
    private static let focusBracketingNumberSettingKey = "focusbracketingnumberofshots"
    private static let focusBracketingIncrementSettingKey = "focusbracketingfocusincrement"
    private static let focusBracketingSmoothingSettingKey = "focusbracketingexposuresmoothing"
    private static let focusBracketingStringEndpoints = [
        (
            key: focusBracketingSettingKey,
            suffix: "/shooting/settings/focusbracketing",
            simulatorPath: "/ccapi/focus-bracketing",
            values: Set(["enable", "disable"])
        ),
        (
            key: focusBracketingSmoothingSettingKey,
            suffix: "/shooting/settings/focusbracketing/exposuresmoothing",
            simulatorPath: "/ccapi/focus-bracketing/exposure-smoothing",
            values: Set(["enable", "disable"])
        ),
    ]
    private static let focusBracketingIntegerEndpoints = [
        (
            key: focusBracketingNumberSettingKey,
            suffix: "/shooting/settings/focusbracketing/numberofshots",
            simulatorPath: "/ccapi/focus-bracketing/number-of-shots"
        ),
        (
            key: focusBracketingIncrementSettingKey,
            suffix: "/shooting/settings/focusbracketing/focusincrement",
            simulatorPath: "/ccapi/focus-bracketing/focus-increment"
        ),
    ]
    private static let focusBracketingStringSettingKeys = Set(focusBracketingStringEndpoints.map { $0.key })
    private static let focusBracketingIntegerSettingKeys = Set(focusBracketingIntegerEndpoints.map { $0.key })
    private static let focusBracketingSettingKeys =
        focusBracketingStringSettingKeys.union(focusBracketingIntegerSettingKeys)
    private static let simulatorFocusBracketingValues = [
        focusBracketingNumberSettingKey: Set((2...999).map(String.init)),
        focusBracketingIncrementSettingKey: Set((1...10).map(String.init)),
    ]
    private static let maximumStringSettingOptions = 256
    private static let maximumStringSettingValueLength = 128
    private static let movieQualitySettingKey = "moviequality"
    private static let highFrameRateSettingKey = "highframerate"
    private static let movieCroppingSettingKey = "moviecropping"
    private static let movieFormatSettingKey = "movieformat"
    private static let movieSettingEndpoints: [(
        key: String,
        suffix: String,
        simulatorPath: String,
        values: Set<String>?
    )] = [
        (movieQualitySettingKey, "/shooting/settings/moviequality", "/ccapi/movie-settings/quality", nil),
        (
            highFrameRateSettingKey,
            "/shooting/settings/highframerate",
            "/ccapi/movie-settings/high-frame-rate",
            Set(["enable", "disable"])
        ),
        (
            movieCroppingSettingKey,
            "/shooting/settings/moviecropping",
            "/ccapi/movie-settings/cropping",
            Set(["enable", "disable"])
        ),
        (
            movieFormatSettingKey,
            "/shooting/settings/movieformat",
            "/ccapi/movie-settings/format",
            nil
        ),
    ]
    private static let movieSettingKeys = Set(movieSettingEndpoints.map(\.key))
    private static let simulatorMovieSettingValues = [
        movieQualitySettingKey: Set([
            "3840x2160_5994_ipb_standard",
            "1920x1080_2997_ipb_standard",
        ]),
        highFrameRateSettingKey: Set(["enable", "disable"]),
        movieCroppingSettingKey: Set(["enable", "disable"]),
        movieFormatSettingKey: Set(["raw", "mp4"]),
    ]
    private static let directorySelectionSettingKey = "directoryselection"
    private static let directorySelectionPathSuffix = "/functions/directory/directoryselection"
    private static let directoryCreatePathSuffix = "/functions/directory/createdirectory"
    private static let stillFilenameModes = Set(["preset_code", "usersetting1", "usersetting2"])
    private static let fileNamingEndpoints: [(
        field: CameraFileNamingField,
        suffix: String,
        responseKey: String
    )] = [
        (.stillFilenameMode, "/functions/filename/stills/filename", "value"),
        (.stillUserSetting1, "/functions/filename/stills/usersetting1", "usersetting1"),
        (.stillUserSetting2, "/functions/filename/stills/usersetting2", "usersetting2"),
        (.movieIndex, "/functions/filename/movies/index", "index"),
        (.movieReelNumber, "/functions/filename/movies/reelnum", "value"),
        (.movieClipNumber, "/functions/filename/movies/clipnum", "value"),
        (.movieUserDefined, "/functions/filename/movies/userdefined", "userdefined"),
    ]

    private let baseURL: URL
    private let baseURLString: String
    private let authorization: String?
    private let transport: any CameraHTTPTransport
    private let requestedMode: CCAPIConnectionMode
    private let rtpDestinationAddress: String?
    private let rtpSessionFactory: (any CCAPIRTPSessionFactory)?
    private var resolvedMode: CCAPIConnectionMode
    private var initialized = false
    private var apiVersionPrefixes = ["/ccapi/ver100"]
    private var preferredVersionPrefix = "/ccapi/ver100"
    private var operations = Set<CCAPIOperation>()
    private var observedFeatures = Set<CameraFeature>()
    private var discoveryTrace: [CameraDiscoveryAttempt] = []
    private var discoveryTraceTruncated = false
    private var settingPaths: [String: String] = [:]
    private var cameraSleepPath: String?
    private var cachedSettings: JSONDictionary?
    private var cachedFileNaming: CameraFileNaming?
    private var fileNamingLoaded = false
    private var enforceAdvertisedOperations = false
    private var settingsLoaded = false
    private var discoverySource = "unknown"
    private var cachedModel = "Canon Camera"
    private var recording: Bool?
    private var bulbExposureActive = false
    private var latestTemperatureStatus: CameraTemperatureStatus?
    private var liveViewSizeControlSupported = true
    private var rejectedLiveViewSizes = Set<LiveViewSize>()
    private var activeLiveViewSize = LiveViewSize.medium
    private var activeLiveViewSource: LiveViewSource?
    private var requestedLiveViewRequest: LiveViewRequest?
    private var liveViewSizeFallbackAttempted = false
    private var rtpSession: (any CCAPIRTPSession)?
    private var multipartSession: CCAPIMultipartLiveViewSession?
    private var pendingMultipartFrame: Data?
    private var nativeGeometryCacheKey: Int64 = 0
    private var latestLiveViewGeometry: CCAPILiveViewGeometry?
    private var simulatorEventSequence: Int64 = 0
    private var liveViewMagnifications: [LiveViewMagnification] = []
    private var currentLiveViewMagnification: LiveViewMagnification?
    private var mediaDescendingOrderSupported: Bool?

    public init(
        baseURL value: String,
        mode: CCAPIConnectionMode = .automatic,
        username: String = "",
        password: String = "",
        rtpDestinationAddress: String? = nil,
        rtpSessionFactory: (any CCAPIRTPSessionFactory)? = nil,
        transport: (any CameraHTTPTransport)? = nil
    ) throws {
        guard var components = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = components.scheme?.lowercased(),
              ["http", "https"].contains(scheme),
              components.host != nil,
              components.query == nil,
              components.fragment == nil,
              components.path.isEmpty || components.path == "/" else {
            throw CCAPIError.invalidBaseURL(value)
        }
        guard components.user == nil, components.password == nil else {
            throw CCAPIError.invalidBaseURL("Credentials must be entered separately.")
        }
        components.scheme = scheme
        components.path = ""
        guard let normalizedURL = components.url else {
            throw CCAPIError.invalidBaseURL(value)
        }

        baseURL = normalizedURL
        baseURLString = normalizedURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        requestedMode = mode
        resolvedMode = mode
        self.rtpDestinationAddress = rtpDestinationAddress
        self.rtpSessionFactory = rtpSessionFactory
        self.transport = transport ?? URLSessionCameraHTTPTransport()
        if !username.isEmpty {
            let token = Data("\(username):\(password)".utf8).base64EncodedString()
            authorization = "Basic \(token)"
        } else {
            authorization = nil
        }
    }

    public func sanitizedBaseURL() -> URL {
        baseURL
    }

    public func initialize() async throws {
        guard !initialized else { return }
        discoveryTrace.removeAll()
        discoveryTraceTruncated = false
        resolvedMode = resolveMode(requestedMode)
        if resolvedMode == .simulator {
            discoverySource = "simulator contract"
            initialized = true
            return
        }

        var errors: [String] = []
        for path in ["/ccapi", "/ccapi/"] {
            do {
                let rootDiscovery: JSONDictionary
                do {
                    rootDiscovery = try await requestJSON(path: path)
                } catch {
                    if error is CancellationError { throw error }
                    recordDiscoveryFailure(endpoint: "GET \(path)", error: error)
                    throw error
                }
                if rootDiscovery.string("value") != Self.noAPIListValue {
                    parseDiscovery(rootDiscovery, source: "GET \(path)")
                    recordDiscoveryResponse(
                        endpoint: "GET \(path)",
                        outcome: operations.isEmpty ? "ZERO_OPERATIONS" : "OPERATIONS",
                        response: rootDiscovery
                    )
                    if !operations.isEmpty {
                        initialized = true
                        return
                    }
                } else {
                    recordDiscoveryResponse(
                        endpoint: "GET \(path)",
                        outcome: "NO_API_LIST",
                        response: rootDiscovery
                    )
                }

                let developerEndpoint = "GET \(Self.developerAPIPath)"
                let discovery: JSONDictionary
                do {
                    discovery = try await requestJSON(path: Self.developerAPIPath)
                } catch {
                    if error is CancellationError { throw error }
                    recordDiscoveryFailure(endpoint: developerEndpoint, error: error)
                    throw error
                }
                parseDiscovery(
                    discovery,
                    source: "GET \(Self.developerAPIPath) (Canon developer API fallback)"
                )
                recordDiscoveryResponse(
                    endpoint: developerEndpoint,
                    outcome: operations.isEmpty ? "ZERO_OPERATIONS" : "OPERATIONS",
                    response: discovery
                )
                guard !operations.isEmpty else {
                    throw CCAPIError.invalidResponse(
                        "Camera developer API \(Self.developerAPIPath) did not advertise any valid operations."
                    )
                }
                initialized = true
                return
            } catch {
                if error is CancellationError { throw error }
                errors.append("GET \(path): \(error.localizedDescription)")
            }
        }

        for prefix in ["/ccapi/ver110", "/ccapi/ver100"] {
            do {
                let value = try await requestJSON(path: "\(prefix)/deviceinformation")
                apiVersionPrefixes = [prefix]
                preferredVersionPrefix = prefix
                discoverySource = "GET \(prefix)/deviceinformation (identity fallback)"
                recordDiscoveryResponse(
                    endpoint: "GET \(prefix)/deviceinformation",
                    outcome: "IDENTITY",
                    response: value,
                    operationCount: 0
                )
                cachedModel = value.string("productname", default: cachedModel)
                observedFeatures.insert(.cameraIdentity)
                enforceAdvertisedOperations = true
                initialized = true
                return
            } catch {
                if error is CancellationError { throw error }
                recordDiscoveryFailure(endpoint: "GET \(prefix)/deviceinformation", error: error)
                errors.append("GET \(prefix)/deviceinformation: \(error.localizedDescription)")
            }
        }
        throw CCAPIError.discoveryFailed(errors)
    }

    public func connectSnapshot() async throws -> CameraSnapshot {
        try await initialize()
        let cameraInfo = try await info()
        let cameraStatus = try await status()
        let cameraCapabilities = try await capabilities()
        return CameraSnapshot(info: cameraInfo, status: cameraStatus, capabilities: cameraCapabilities)
    }

    public func close() async {
        await stopEventPolling()
        if bulbExposureActive {
            _ = try? await stopBulbExposure()
        }
        await stopLiveView()
    }

    public func info() async throws -> CameraInfo {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            let value = try await requestJSON(path: "/ccapi/info")
            cachedModel = value.string("model", default: "Unknown camera")
            observedFeatures.insert(.cameraIdentity)
            return CameraInfo(
                connected: value.bool("connected") ?? true,
                model: cachedModel,
                serial: value.string("serial", default: "unknown"),
                api: value.string("api", default: "simulated-ccapi")
            )
        }

        let value = try await firstJSON(paths: versionedPaths("/deviceinformation"), required: true)
        let info = CameraInfo(
            model: value?.string("productname", default: "Canon Camera") ?? "Canon Camera",
            serial: value?.string("serialnumber", default: "unknown") ?? "unknown",
            api: value?.string("version", default: "ccapi") ?? "ccapi"
        )
        cachedModel = info.model
        observedFeatures.insert(.cameraIdentity)
        return info
    }

    public func status() async throws -> CameraStatus {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            let status = try parseSimulatorStatus(await requestJSON(path: "/ccapi/status"))
            latestTemperatureStatus = status.temperature
            observedFeatures.formUnion([.batteryStatus, .storageStatus])
            if status.recordableShots != nil || status.remainingRecordingSeconds != nil {
                observedFeatures.insert(.recordableStatus)
            } else {
                observedFeatures.remove(.recordableStatus)
            }
            if status.lens == nil { observedFeatures.remove(.lensStatus) }
            else { observedFeatures.insert(.lensStatus) }
            if status.temperature == nil { observedFeatures.remove(.temperatureStatus) }
            else { observedFeatures.insert(.temperatureStatus) }
            return status
        }

        let battery = try await firstJSON(
            paths: versionedPaths("/devicestatus/batterylist") + versionedPaths("/devicestatus/battery"),
            required: false
        )
        if battery != nil { observedFeatures.insert(.batteryStatus) }
        let storage = try await firstJSON(
            paths: versionedPaths("/devicestatus/storage") +
                versionedPaths("/devicestatus/currentstorage") +
                versionedPaths("/contents"),
            required: false
        )
        if storage != nil { observedFeatures.insert(.storageStatus) }
        let recordableValue: JSONDictionary?
        if let operation = operation(.get, suffix: "/shooting/information/recordable") {
            recordableValue = try await firstJSON(paths: [operation.path], required: false)
        } else {
            recordableValue = nil
        }
        let recordable = parseRecordable(recordableValue)
        if recordable == nil {
            observedFeatures.remove(.recordableStatus)
        } else {
            observedFeatures.insert(.recordableStatus)
        }
        let lensValue: JSONDictionary?
        if let operation = operation(.get, suffix: "/devicestatus/lens") {
            lensValue = try await firstJSON(paths: [operation.path], required: false)
        } else {
            lensValue = nil
        }
        let lens = parseLens(lensValue)
        if lens == nil {
            observedFeatures.remove(.lensStatus)
        } else {
            observedFeatures.insert(.lensStatus)
        }
        let temperatureValue: JSONDictionary?
        if let operation = operation(.get, suffix: "/devicestatus/temperature") {
            temperatureValue = try await firstJSON(paths: [operation.path], required: false)
        } else {
            temperatureValue = nil
        }
        if let refreshedTemperature = parseTemperature(temperatureValue) {
            latestTemperatureStatus = refreshedTemperature
            observedFeatures.insert(.temperatureStatus)
        }
        let settings = try await loadShootingSettings()
        let batteryState = parseBattery(battery)
        let storageState = storage.map(parseStorage)

        return CameraStatus(
            batteryLevel: batteryState.level,
            batteryStatus: batteryState.status,
            recording: recording,
            bulbExposureActive: bulbExposureActive,
            mode: settingObject(in: settings, aliases: ["shootingmode"])?.string("value", default: "unknown") ?? "unknown",
            mediaAvailable: storageState?.available,
            exposure: exposureState(settings),
            storageTotalBytes: storageState?.totalBytes,
            storageFreeBytes: storageState?.freeBytes,
            storageFreeImages: storageState?.freeImages,
            storageDeviceCount: storageState?.devices,
            recordableShots: recordable?.shots,
            remainingRecordingSeconds: recordable?.remainingSeconds,
            rawBatteryJSON: JSONString(battery),
            rawStorageJSON: JSONString(storage),
            rawRecordableJSON: JSONString(recordableValue),
            lens: lens,
            temperature: latestTemperatureStatus
        )
    }

    public func capabilities() async throws -> CameraCapabilities {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            return try await simulatorCapabilities()
        }
        let settings = try await cachedOrLoadShootingSettings()
        let fileNaming = try await loadFileNaming()
        let controls = cameraSettings(settings)
        var supported = observedFeatures
        if controls.contains(where: { ["iso", "shutter", "aperture"].contains($0.key) }) {
            supported.insert(.exposureControl)
        }
        if controls.contains(where: { $0.key == "whitebalance" }) {
            supported.insert(.whiteBalanceControl)
        }
        if controls.contains(where: { $0.key == Self.zoomSettingKey }) {
            supported.insert(.zoomControl)
        }
        if controls.contains(where: { $0.key == Self.movieModeSettingKey }) {
            supported.insert(.movieModeControl)
        }
        if controls.contains(where: { Self.cardSelectionSettingKeys.contains($0.key) }) {
            supported.insert(.cardSelectionControl)
        }
        if controls.contains(where: { Self.soundRecordingSettingKeys.contains($0.key) }) {
            supported.insert(.soundRecordingControl)
        }
        if controls.contains(where: { Self.soundRecordingLevelSettingKeys.contains($0.key) }) {
            supported.insert(.soundRecordingLevelControl)
        }
        if controls.contains(where: { $0.key == Self.focusBracketingSettingKey }) {
            supported.insert(.focusBracketingControl)
        }
        if controls.contains(where: { Self.movieSettingKeys.contains($0.key) }) {
            supported.insert(.movieSettingsControl)
        }
        if controls.contains(where: { $0.key == Self.directorySelectionSettingKey }), directoryOperations() != nil {
            supported.insert(.directoryControl)
        }
        if fileNaming != nil { supported.insert(.fileNamingControl) }
        if controls.contains(where: { !Self.primarySettingKeys.contains($0.key) }) {
            supported.insert(.advancedSettings)
        }
        let supportsJPEGLiveView = supportsCompleteLiveView()
        let supportsMultipartLiveView = supportsMultipartLiveView()
        let supportsRTPLiveView = supportsRTPLiveView()
        if supportsJPEGLiveView || supportsMultipartLiveView || supportsRTPLiveView || observedFeatures.contains(.liveView) {
            supported.insert(.liveView)
        }
        if supportsJPEGLiveView { supported.insert(.liveViewJPEGPolling) }
        if supportsMultipartLiveView { supported.insert(.liveViewMultipart) }
        if supportsRTPLiveView { supported.insert(.liveViewRTP) }
        if liveViewMagnifications.count >= 2 { supported.insert(.liveViewMagnification) }
        if recordingOperation() != nil { supported.insert(.videoRecording) }
        if directShutterOperation() != nil || manualShutterOperation() != nil {
            supported.insert(.stillCapture)
        }
        if manualShutterOperation() != nil {
            supported.insert(.shutterHalfPress)
            supported.insert(.bulbExposure)
        }
        if autofocusOperation() != nil || manualShutterOperation() != nil {
            supported.insert(.autofocus)
        }
        if supportsCoordinateTapFocus() { supported.insert(.tapFocus) }
        if supportsCoordinateClickWhiteBalance() { supported.insert(.clickWhiteBalance) }
        if focusDriveOperation() != nil { supported.insert(.focusDrive) }
        if supports(.get, suffix: "/contents") {
            supported.formUnion([.mediaBrowser, .mediaThumbnail, .mediaPreview, .mediaDownload])
        }
        if supportsMediaDelete() { supported.insert(.mediaDelete) }
        if supportsMediaModify() {
            supported.formUnion([.mediaProtect, .mediaRating, .mediaRotate, .mediaArchive])
        }
        if eventPollingOperations() != nil { supported.insert(.eventPolling) }
        if cameraClockOperations() != nil { supported.insert(.cameraClockSync) }
        if operation(.post, suffix: "/functions/sensorcleaning") != nil { supported.insert(.sensorCleaning) }
        if cameraSleepPath != nil { supported.insert(.cameraSleep) }

        let directCCAPIFeatures = Set(CameraFeature.allCases).subtracting([.desktopBridge, .usbDiagnostics])
        let liveSizes = liveViewSizeControlSupported
            ? LiveViewSize.allCases.filter { !rejectedLiveViewSizes.contains($0) }
            : [activeLiveViewSize]
        return CameraCapabilities(
            settings: controls,
            fileNaming: fileNaming,
            matrix: CapabilityMatrix(
                supported: supported,
                planned: directCCAPIFeatures.subtracting(supported),
                reasons: [
                    .recordableStatus: "The camera must advertise GET shooting/information/recordable and return Canon's documented nullable integer payload.",
                    .lensStatus: "The camera must advertise GET devicestatus/lens and return Canon's documented mount/name payload.",
                    .temperatureStatus: "The camera must advertise GET devicestatus/temperature and return a documented Canon status value.",
                    .eventPolling: "The camera must advertise both GET and DELETE for the Canon event polling endpoint.",
                    .liveViewMultipart: "The camera must advertise matching GET and DELETE Canon multipart Live View endpoints with the regular lifecycle in one API version.",
                    .liveViewRTP: "Canon RTP needs advertised SDP/start endpoints and a reachable camera-Wi-Fi IPv4 address.",
                    .autofocus: "The camera advertised neither CCAPI POST autofocus nor a verified manual half-press operation.",
                    .tapFocus: "The camera must advertise PUT afframeposition and detailed Live View metadata for coordinate Tap AF.",
                    .clickWhiteBalance: "The camera must advertise POST clickwb and detailed Live View metadata for Click WB.",
                    .focusDrive: "The camera did not advertise the verified CCAPI POST drivefocus operation.",
                    .zoomControl: "The camera must advertise readable and writable Canon zoom control in the same API version.",
                    .cardSelectionControl: "The camera must advertise matching GET and PUT Canon card-selection endpoints and valid card abilities.",
                    .soundRecordingControl: "The camera must advertise matching GET and PUT Canon sound-recording-setting endpoints and valid documented abilities.",
                    .soundRecordingLevelControl: "The camera must advertise matching GET and PUT Canon sound-recording-level endpoints and a valid integer range.",
                    .focusBracketingControl: "The camera must advertise matching GET and PUT Canon focus-bracketing endpoints and valid documented abilities.",
                    .movieSettingsControl: "The camera must advertise matching GET and PUT Canon movie-setting endpoints and valid documented abilities.",
                    .liveViewMagnification: "The camera must advertise matching GET and PUT Canon lvzoom endpoints in one CCAPI version and return a strict string ability containing 1 and at least one additional documented magnification.",
                    .movieModeControl: "The camera must advertise readable and writable Canon movie mode control in the same API version.",
                    .cameraClockSync: "The camera must advertise both GET and PUT for the Canon date-time endpoint in the same API version.",
                    .sensorCleaning: "The camera must advertise the Canon POST sensor-cleaning endpoint.",
                    .cameraSleep: "The camera must advertise matching GET and PUT Auto Power Off endpoints and include immediately in its current ability.",
                    .directoryControl: "The camera must advertise directory creation plus matching readable and writable directory selection in the same CCAPI version, and return a valid ability list.",
                    .fileNamingControl: "The camera must advertise the complete same-version Canon still and movie file-naming endpoint group and return valid values and ranges.",
                    .mediaProtect: "The camera must advertise PUT for Canon contents before file protection can be changed.",
                    .mediaRating: "The camera must advertise PUT for Canon contents before file ratings can be changed.",
                    .mediaRotate: "The camera must advertise PUT for Canon contents before display rotation can be changed.",
                    .mediaArchive: "The camera must advertise PUT for Canon contents before media archiving can be changed.",
                    .mediaPreview: "Canon kind=display requires an advertised GET contents operation and is eligible only for JPEG or CR3 items; the camera can still reject an individual file.",
                    .mediaUpload: "Direct CCAPI upload remains unavailable because no verified Canon upload operation is advertised or implemented.",
                ]
            ),
            liveView: LiveViewCapabilities(
                sources: ccapiLiveViewSources(),
                defaultSource: ccapiLiveViewSources().first ?? .auto,
                sizes: liveSizes,
                defaultSize: liveSizes.contains(.medium) ? .medium : (liveSizes.first ?? activeLiveViewSize),
                currentSize: activeLiveViewSource == nil ? nil : activeLiveViewSize,
                magnifications: liveViewMagnifications,
                currentMagnification: currentLiveViewMagnification,
                maximumFPS: 30
            ),
            profile: CameraProfile.from(modelName: cachedModel),
            evidence: capabilityEvidence()
        )
    }

    public func pollEvent() async throws -> CameraEvent {
        try await ensureInitialized()
        let changedKeys: [String]
        if resolvedMode == .simulator {
            let value = try await requestJSON(
                path: "/ccapi/events?after=\(simulatorEventSequence)",
                timeoutInterval: 40,
                maximumBytes: Self.maximumEventBytes
            )
            simulatorEventSequence = max(simulatorEventSequence, value.integer64("sequence") ?? simulatorEventSequence)
            changedKeys = Self.safeEventKeys(value.array("keys")?.strings ?? [])
        } else {
            guard let operations = eventPollingOperations() else {
                throw CCAPIError.unsupported(.eventPolling)
            }
            let parameter = Self.pathVersion(operations.poll.path) >= 110 ? "timeout=long" : "continue=on"
            let separator = operations.poll.path.contains("?") ? "&" : "?"
            let value = try await requestJSON(
                path: "\(operations.poll.path)\(separator)\(parameter)",
                timeoutInterval: 40,
                maximumBytes: Self.maximumEventBytes
            )
            changedKeys = Self.safeEventKeys(Array(value.keys))
        }
        observedFeatures.insert(.eventPolling)
        return CameraEvent(changedKeys: changedKeys)
    }

    public func stopEventPolling() async {
        guard resolvedMode != .simulator, let operations = eventPollingOperations() else { return }
        try? await requestOK(path: operations.stop.path, method: .delete, timeoutInterval: 5)
    }

    public func setSetting(key: String, value: String) async throws -> CameraStatus {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            switch key {
            case "iso", "shutter", "aperture":
                _ = try await requestJSON(path: "/ccapi/exposure", method: .patch, json: [key: value])
            case "whitebalance":
                _ = try await requestJSON(
                    path: "/ccapi/white-balance",
                    method: .patch,
                    json: ["white_balance": value]
                )
            case Self.zoomSettingKey:
                guard let zoom = Int(value), String(zoom) == value, (0...100).contains(zoom) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                _ = try await requestJSON(path: "/ccapi/zoom", method: .post, json: ["value": zoom])
            case Self.movieModeSettingKey:
                guard Self.movieModeValues.contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: "/ccapi/movie-mode", method: .post, json: ["action": value])
            case Self.stillCardSelectionSettingKey:
                guard Self.cardSelectionValues.contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(
                    path: "/ccapi/card-selection/stillimage",
                    method: .put,
                    json: ["value": value]
                )
            case Self.movieCardSelectionSettingKey:
                guard Self.cardSelectionValues.contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(
                    path: "/ccapi/card-selection/movie",
                    method: .put,
                    json: ["value": value]
                )
            case let deviceKey where Self.deviceFunctionSettingKeys.contains(deviceKey):
                guard let endpoint = Self.deviceFunctionSettingEndpoints.first(where: { $0.key == deviceKey }),
                      endpoint.values.subtracting(Set([Self.autoPowerOffImmediately])).contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: endpoint.simulatorPath, method: .put, json: ["value": value])
            case let soundLevelKey where Self.soundRecordingLevelSettingKeys.contains(soundLevelKey):
                guard let endpoint = Self.soundRecordingLevelEndpoints.first(where: { $0.key == soundLevelKey }),
                      let level = Int(value),
                      String(level) == value,
                      Self.simulatorSoundRecordingLevelValues.contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(
                    path: endpoint.simulatorPath,
                    method: .put,
                    json: ["value": level]
                )
            case let soundKey where Self.soundRecordingSettingKeys.contains(soundKey):
                guard let endpoint = Self.soundRecordingEndpoints.first(where: { $0.key == soundKey }),
                      endpoint.values.contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: endpoint.simulatorPath, method: .put, json: ["value": value])
            case let focusKey where Self.focusBracketingStringSettingKeys.contains(focusKey):
                guard let endpoint = Self.focusBracketingStringEndpoints.first(where: { $0.key == focusKey }),
                      endpoint.values.contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: endpoint.simulatorPath, method: .put, json: ["value": value])
            case let focusKey where Self.focusBracketingIntegerSettingKeys.contains(focusKey):
                guard let endpoint = Self.focusBracketingIntegerEndpoints.first(where: { $0.key == focusKey }),
                      let integer = Int(value),
                      String(integer) == value,
                      Self.simulatorFocusBracketingValues[focusKey]?.contains(value) == true else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: endpoint.simulatorPath, method: .put, json: ["value": integer])
            case let movieKey where Self.movieSettingKeys.contains(movieKey):
                guard let endpoint = Self.movieSettingEndpoints.first(where: { $0.key == movieKey }),
                      Self.simulatorMovieSettingValues[movieKey]?.contains(value) == true else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: endpoint.simulatorPath, method: .put, json: ["value": value])
            case Self.directorySelectionSettingKey:
                guard Self.isValidDirectorySelection(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: "/ccapi/directory-selection", method: .put, json: ["value": value])
            default:
                throw CCAPIError.unsupported(.advancedSettings)
            }
            observedFeatures.insert(featureForSetting(key))
            return try await status()
        }

        let settings: JSONDictionary?
        if Self.cardSelectionSettingKeys.contains(key.lowercased()) ||
            Self.deviceFunctionSettingKeys.contains(key.lowercased()) ||
            Self.soundRecordingSettingKeys.contains(key.lowercased()) ||
            Self.soundRecordingLevelSettingKeys.contains(key.lowercased()) ||
            Self.focusBracketingSettingKeys.contains(key.lowercased()) ||
            Self.movieSettingKeys.contains(key.lowercased()) ||
            key.lowercased() == Self.directorySelectionSettingKey {
            settings = try await loadShootingSettings()
        } else {
            settings = try await cachedOrLoadShootingSettings()
        }
        guard let control = cameraSettings(settings).first(where: { $0.key == key }),
              control.values.contains(value) else {
            throw CCAPIError.invalidSetting(key: key, value: value)
        }
        if key == Self.movieModeSettingKey {
            guard let path = settingPaths[Self.movieModeSettingKey] else {
                throw CCAPIError.invalidSetting(key: key, value: value)
            }
            try await requestOK(path: path, method: .post, json: ["action": value])
            cachedSettings = nil
        } else if key == Self.zoomSettingKey {
            guard let path = settingPaths[Self.zoomSettingKey],
                  let zoom = Int(value), String(zoom) == value else {
                throw CCAPIError.invalidSetting(key: key, value: value)
            }
            _ = try await requestJSON(path: path, method: .post, json: ["value": zoom])
            cachedSettings = nil
        } else if Self.soundRecordingLevelSettingKeys.contains(key) {
            guard let path = settingPaths[key],
                  let level = Int(value), String(level) == value else {
                throw CCAPIError.invalidSetting(key: key, value: value)
            }
            _ = try await requestJSON(path: path, method: .put, json: ["value": level])
            cachedSettings = nil
        } else if Self.focusBracketingIntegerSettingKeys.contains(key) {
            guard let path = settingPaths[key],
                  let integer = Int(value), String(integer) == value else {
                throw CCAPIError.invalidSetting(key: key, value: value)
            }
            _ = try await requestJSON(path: path, method: .put, json: ["value": integer])
            cachedSettings = nil
        } else if let structured = structuredSettingParts(key) {
            try await putStructuredSettingValue(
                settings: settings,
                baseKey: structured.baseKey,
                field: structured.field,
                value: value
            )
        } else {
            try await putSettingValue(candidateKeys: aliases(for: key), value: value)
        }
        observedFeatures.insert(featureForSetting(key))
        return try await status()
    }

    public func createDirectory(name: String) async throws -> String {
        try await ensureInitialized()
        guard Self.isValidDirectoryCreateName(name) else {
            throw CCAPIError.invalidSetting(key: "directoryname", value: name)
        }
        let response: JSONDictionary
        if resolvedMode == .simulator {
            response = try await requestJSON(
                path: "/ccapi/directory",
                method: .post,
                json: ["directoryname": name]
            )
        } else {
            guard let operations = directoryOperations() else {
                throw CCAPIError.unsupported(.directoryControl)
            }
            response = try await requestJSON(
                path: operations.create.path,
                method: operations.create.method,
                json: ["directoryname": name]
            )
        }
        let created = response.string("directoryname")
        guard Self.isValidCreatedDirectoryName(created) else {
            throw CCAPIError.invalidResponse("Canon directory creation returned an invalid directory name.")
        }
        observedFeatures.insert(.directoryControl)
        cachedSettings = nil
        if resolvedMode != .simulator { _ = try await loadShootingSettings() }
        return created
    }

    public func setFileNaming(
        field: CameraFileNamingField,
        value: String
    ) async throws -> CameraFileNaming {
        try await ensureInitialized()
        let current: CameraFileNaming
        if resolvedMode == .simulator {
            guard let naming = try await simulatorCapabilities().fileNaming else {
                throw CCAPIError.unsupported(.fileNamingControl)
            }
            current = naming
        } else {
            guard let naming = try await loadFileNaming() else {
                throw CCAPIError.unsupported(.fileNamingControl)
            }
            current = naming
        }
        guard current.accepts(field, value: value) else {
            throw CCAPIError.invalidSetting(key: field.rawValue, value: value)
        }

        let updated: CameraFileNaming
        if resolvedMode == .simulator {
            let response = try await requestJSON(
                path: "/ccapi/file-naming/\(field.rawValue)",
                method: .put,
                json: ["value": value]
            )
            guard let naming = Self.validatedBridgeFileNaming(response) else {
                throw CCAPIError.invalidResponse("Simulator returned an invalid file-naming state.")
            }
            updated = naming
        } else {
            guard let operations = fileNamingOperations(),
                  let operation = operations[field],
                  let endpoint = Self.fileNamingEndpoints.first(where: { $0.field == field }) else {
                throw CCAPIError.unsupported(.fileNamingControl)
            }
            let requestValue: Any
            switch field {
            case .movieReelNumber, .movieClipNumber:
                guard let integer = Int(value) else {
                    throw CCAPIError.invalidSetting(key: field.rawValue, value: value)
                }
                requestValue = integer
            default:
                requestValue = value
            }
            let response = try await requestJSON(
                path: operation.write.path,
                method: operation.write.method,
                json: [endpoint.responseKey: requestValue]
            )
            let responseMatches: Bool
            if let integer = requestValue as? Int {
                responseMatches = Self.strictInteger(response[endpoint.responseKey]) == integer
            } else {
                responseMatches = response[endpoint.responseKey] as? String == value
            }
            guard responseMatches else {
                throw CCAPIError.invalidResponse("Canon file-naming control returned an invalid update response.")
            }
            fileNamingLoaded = false
            guard let naming = try await loadFileNaming(force: true) else {
                throw CCAPIError.invalidResponse("Canon file-naming control returned an invalid state after update.")
            }
            updated = naming
        }
        guard updated.value(for: field) == value else {
            throw CCAPIError.invalidResponse("Canon file-naming control did not return the requested value on refresh.")
        }
        cachedFileNaming = updated
        fileNamingLoaded = true
        observedFeatures.insert(.fileNamingControl)
        return updated
    }

    public func sleepCamera() async throws {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            try await requestOK(path: "/ccapi/camera-sleep", method: .post, json: [:])
            observedFeatures.insert(.cameraSleep)
            return
        }
        await stopEventPolling()
        await stopLiveView()
        cachedSettings = nil
        _ = try await loadShootingSettings()
        guard let cameraSleepPath else { throw CCAPIError.unsupported(.cameraSleep) }
        try await requestOK(
            path: cameraSleepPath,
            method: .put,
            json: ["value": Self.autoPowerOffImmediately],
            expectedStatusCode: 202
        )
        cachedSettings = nil
        observedFeatures.insert(.cameraSleep)
    }

    public func cleanSensor(autoPowerOff: Bool) async throws {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            try await requestOK(
                path: "/ccapi/sensor-cleaning",
                method: .post,
                json: ["autopoweroff": autoPowerOff]
            )
            observedFeatures.insert(.sensorCleaning)
            return
        }
        guard let operation = operation(.post, suffix: "/functions/sensorcleaning") else {
            throw CCAPIError.unsupported(.sensorCleaning)
        }
        await stopEventPolling()
        await stopLiveView()
        try await requestOK(
            path: operation.path,
            method: .post,
            json: ["autopoweroff": autoPowerOff],
            expectedStatusCode: 200
        )
        observedFeatures.insert(.sensorCleaning)
    }

    public func captureStill() async throws -> CameraStatus {
        try await ensureInitialized()
        try await refreshTemperatureStatusForRestrictedCommand()
        try requireTemperatureAllowsStillCapture()
        if resolvedMode == .simulator {
            _ = try await requestJSON(path: "/ccapi/capture/still", method: .post, json: ["af": true])
            observedFeatures.insert(.stillCapture)
            return try await status()
        }

        let direct = directShutterOperation()
        let manual = manualShutterOperation()
        guard direct != nil || manual != nil else { throw CCAPIError.unsupported(.stillCapture) }
        if let direct {
            try await commandOK(operation: direct, json: ["af": true])
        } else if let manual {
            try await performGuaranteedRelease(
                press: { try await self.commandOK(operation: manual, json: ["af": true, "action": "full_press"]) },
                release: { try await self.commandOK(operation: manual, json: ["af": false, "action": "release"]) }
            )
        }
        observedFeatures.insert(.stillCapture)
        return try await status()
    }

    public func syncCameraClock() async throws -> CameraStatus {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            _ = try await requestJSON(path: "/ccapi/clock/sync", method: .post, json: [:])
            observedFeatures.insert(.cameraClockSync)
            return try await status()
        }

        guard let operations = cameraClockOperations() else {
            throw CCAPIError.unsupported(.cameraClockSync)
        }
        let requested = Date()
        let timeZone = TimeZone.current
        let formatter = Self.canonDateTimeFormatter(timeZone: timeZone)
        let daylight = timeZone.isDaylightSavingTime(for: requested)
        let payload: JSONDictionary = [
            "datetime": formatter.string(from: requested),
            "dst": daylight,
        ]
        _ = try Self.parseCameraClock(
            try await requestJSON(path: operations.write.path, method: .put, json: payload)
        )
        let reported = try Self.parseCameraClock(
            try await requestJSON(path: operations.read.path)
        )
        guard abs(reported.date.timeIntervalSince(requested)) <= 10,
              reported.daylight == daylight else {
            throw CCAPIError.invalidResponse(
                "The camera did not report the requested date, time, and daylight-saving state."
            )
        }
        observedFeatures.insert(.cameraClockSync)
        return try await status()
    }

    public func halfPressShutter() async throws -> CameraStatus {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            try await performGuaranteedRelease(
                press: { _ = try await self.requestJSON(path: "/ccapi/shutter/half-press", method: .post, json: [:]) },
                release: { _ = try await self.requestJSON(path: "/ccapi/shutter/release", method: .post, json: [:]) },
                holdNanoseconds: Self.halfPressNanoseconds
            )
            observedFeatures.insert(.shutterHalfPress)
            return try await status()
        }
        guard let manual = manualShutterOperation() else {
            throw CCAPIError.unsupported(.shutterHalfPress)
        }
        try await performGuaranteedRelease(
            press: { try await self.commandOK(operation: manual, json: ["af": true, "action": "half_press"]) },
            release: { try await self.commandOK(operation: manual, json: ["af": false, "action": "release"]) },
            holdNanoseconds: Self.halfPressNanoseconds
        )
        observedFeatures.insert(.shutterHalfPress)
        return try await status()
    }

    public func startBulbExposure() async throws -> CameraStatus {
        try await ensureInitialized()
        if bulbExposureActive { return try await status() }
        let baseline = try await status()
        try requireTemperatureAllowsStillCapture()
        if resolvedMode == .simulator {
            do {
                _ = try await requestJSON(path: "/ccapi/bulb/start", method: .post, json: [:])
            } catch {
                _ = try? await requestJSON(path: "/ccapi/bulb/stop", method: .post, json: [:])
                throw error
            }
        } else {
            guard let manual = manualShutterOperation() else {
                throw CCAPIError.unsupported(.bulbExposure)
            }
            do {
                try await commandOK(operation: manual, json: ["af": false, "action": "full_press"])
            } catch {
                try? await commandOK(operation: manual, json: ["af": false, "action": "release"])
                throw error
            }
        }
        bulbExposureActive = true
        return baseline.withBulbExposureActive(true)
    }

    public func stopBulbExposure() async throws -> CameraStatus {
        try await ensureInitialized()
        if !bulbExposureActive { return try await status() }
        if resolvedMode == .simulator {
            _ = try await requestJSON(path: "/ccapi/bulb/stop", method: .post, json: [:])
        } else {
            guard let manual = manualShutterOperation() else {
                throw CCAPIError.unsupported(.bulbExposure)
            }
            try await commandOK(operation: manual, json: ["af": false, "action": "release"])
        }
        bulbExposureActive = false
        observedFeatures.insert(.bulbExposure)
        return try await status()
    }

    public func autofocus() async throws -> CameraStatus {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            try await performGuaranteedRelease(
                press: { _ = try await self.requestJSON(path: "/ccapi/shutter/half-press", method: .post, json: [:]) },
                release: { _ = try await self.requestJSON(path: "/ccapi/shutter/release", method: .post, json: [:]) },
                holdNanoseconds: Self.halfPressNanoseconds
            )
            observedFeatures.insert(.autofocus)
            return try await status()
        }
        if let operation = autofocusOperation() {
            try await performGuaranteedRelease(
                press: { try await self.commandOK(operation: operation, json: ["action": "start"]) },
                release: { try await self.commandOK(operation: operation, json: ["action": "stop"]) },
                holdNanoseconds: Self.halfPressNanoseconds
            )
        } else if let manual = manualShutterOperation() {
            try await performGuaranteedRelease(
                press: { try await self.commandOK(operation: manual, json: ["af": true, "action": "half_press"]) },
                release: { try await self.commandOK(operation: manual, json: ["af": false, "action": "release"]) },
                holdNanoseconds: Self.halfPressNanoseconds
            )
        } else {
            throw CCAPIError.unsupported(.autofocus)
        }
        observedFeatures.insert(.autofocus)
        return try await status()
    }

    public func startRecording() async throws -> CameraStatus {
        try await setRecording(true)
    }

    public func stopRecording() async throws -> CameraStatus {
        try await setRecording(false)
    }

    public func tapFocus(x: Double, y: Double) async throws -> FocusResult {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            let value = try await requestJSON(path: "/ccapi/focus/tap", method: .post, json: ["x": x, "y": y])
            let result = FocusResult(
                accepted: value.bool("ok") ?? false,
                x: (value["x"] as? NSNumber)?.doubleValue ?? x,
                y: (value["y"] as? NSNumber)?.doubleValue ?? y
            )
            if result.accepted { observedFeatures.insert(.tapFocus) }
            return result
        }
        guard let operation = tapFocusOperation() else {
            throw CCAPIError.unsupported(.tapFocus)
        }
        guard supportsCoordinateTapFocus() else {
            throw CCAPIError.unsupported(.tapFocus)
        }
        try await ensureLiveViewGeometryForNativeStream()
        let position = try cameraLiveViewPosition(x: x, y: y, feature: .tapFocus)
        try await commandOK(
            operation: operation,
            json: ["positionx": position.x, "positiony": position.y]
        )
        observedFeatures.insert(.tapFocus)
        return FocusResult(accepted: true, x: x, y: y)
    }

    public func clickWhiteBalance(x: Double, y: Double) async throws -> CameraStatus {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            let status = try parseSimulatorStatus(await requestJSON(
                path: "/ccapi/whitebalance/click",
                method: .post,
                json: ["x": x, "y": y]
            ))
            observedFeatures.insert(.clickWhiteBalance)
            return status
        }
        guard let operation = clickWhiteBalanceOperation(), supportsCoordinateClickWhiteBalance() else {
            throw CCAPIError.unsupported(.clickWhiteBalance)
        }
        try await ensureLiveViewGeometryForNativeStream()
        let position = try cameraLiveViewPosition(x: x, y: y, feature: .clickWhiteBalance)
        try await commandOK(
            operation: operation,
            json: ["positionx": position.x, "positiony": position.y]
        )
        observedFeatures.insert(.clickWhiteBalance)
        return try await status()
    }

    public func driveFocus(
        direction: FocusDriveDirection,
        step: FocusDriveStep
    ) async throws -> FocusDriveResult {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            let value = try await requestJSON(
                path: "/ccapi/focus/drive",
                method: .post,
                json: ["direction": direction.rawValue, "step": step.rawValue]
            )
            let result = FocusDriveResult(
                accepted: value.bool("ok") ?? false,
                direction: FocusDriveDirection(rawValue: value.string("direction")) ?? direction,
                step: FocusDriveStep(rawValue: value.string("step")) ?? step
            )
            if result.accepted { observedFeatures.insert(.focusDrive) }
            return result
        }
        guard let operation = focusDriveOperation() else {
            throw CCAPIError.unsupported(.focusDrive)
        }
        let stepNumber: Int
        switch step {
        case .small: stepNumber = 1
        case .medium: stepNumber = 2
        case .large: stepNumber = 3
        }
        try await commandOK(
            operation: operation,
            json: ["value": "\(direction.rawValue)\(stepNumber)"]
        )
        observedFeatures.insert(.focusDrive)
        return FocusDriveResult(accepted: true, direction: direction, step: step)
    }

    public func startLiveView(_ request: LiveViewRequest = LiveViewRequest()) async throws {
        try await ensureInitialized()
        try await refreshTemperatureStatusForRestrictedCommand()
        try requireTemperatureAllowsLiveView()
        latestLiveViewGeometry = nil
        requestedLiveViewRequest = request
        liveViewSizeFallbackAttempted = false
        if resolvedMode == .simulator {
            activeLiveViewSource = .simulatorFrame
            observedFeatures.insert(.liveView)
            return
        }
        let sources: [LiveViewSource]
        switch request.source {
        case .auto:
            sources = [
                supportsRTPLiveView() ? .ccapiRTP : nil,
                supportsMultipartLiveView() ? .ccapiMultipart : nil,
                supportsCompleteLiveView() ? .ccapiJPEGPolling : nil,
            ].compactMap { $0 }
        case .ccapiRTP, .ccapiMultipart, .ccapiJPEGPolling:
            sources = [request.source]
        default:
            throw CCAPIError.invalidResponse(
                "\(request.source.rawValue) is not available through the CCAPI network client."
            )
        }
        guard !sources.isEmpty else { throw CCAPIError.unsupported(.liveView) }
        var lastError: Error?
        for source in sources {
            do {
                switch source {
                case .ccapiRTP:
                    try await startRTPLiveView(request)
                case .ccapiMultipart:
                    try await startMultipartLiveView(request)
                case .ccapiJPEGPolling:
                    try await startJPEGLiveView(request)
                default:
                    throw CCAPIError.invalidResponse("Unsupported CCAPI Live View source.")
                }
                return
            } catch {
                lastError = error
                guard request.source == .auto else { throw error }
            }
        }
        throw lastError ?? CCAPIError.unsupported(.liveView)
    }

    private func startJPEGLiveView(_ request: LiveViewRequest) async throws {
        guard let operations = jpegLiveViewOperations() else { throw CCAPIError.unsupported(.liveView) }
        try await startCCAPILiveView(request, path: operations.start.path)
        pendingMultipartFrame = nil
        activeLiveViewSource = .ccapiJPEGPolling
    }

    private func startCCAPILiveView(_ request: LiveViewRequest, path: String) async throws {
        var effectiveSize = request.size
        do {
            try await requestOK(
                path: path,
                method: .post,
                json: ["cameradisplay": "on", "liveviewsize": request.size.rawValue]
            )
            liveViewSizeControlSupported = true
        } catch let error as CCAPIError {
            guard case let .http(statusCode, _, _, _) = error, statusCode == 400 else { throw error }
            do {
                try await requestOK(path: path, method: .post, json: ["cameradisplay": "on"])
                liveViewSizeControlSupported = false
            } catch let parameterError as CCAPIError {
                guard case let .http(statusCode, _, _, _) = parameterError, statusCode == 400 else {
                    throw parameterError
                }
                let fallbackSizes: [LiveViewSize]
                switch request.size {
                case .large: fallbackSizes = [.medium, .small]
                case .medium: fallbackSizes = [.small]
                case .small: fallbackSizes = []
                }
                var fallbackSucceeded = false
                for fallbackSize in fallbackSizes {
                    do {
                        try await requestOK(
                            path: path,
                            method: .post,
                            json: ["cameradisplay": "on", "liveviewsize": fallbackSize.rawValue]
                        )
                        rejectedLiveViewSizes.insert(request.size)
                        liveViewSizeControlSupported = true
                        effectiveSize = fallbackSize
                        fallbackSucceeded = true
                        break
                    } catch let fallbackError as CCAPIError {
                        guard case let .http(statusCode, _, _, _) = fallbackError, statusCode == 400 else {
                            throw fallbackError
                        }
                        rejectedLiveViewSizes.insert(fallbackSize)
                    }
                }
                guard fallbackSucceeded else { throw error }
            }
        }
        activeLiveViewSize = effectiveSize
    }

    private func startMultipartLiveView(_ request: LiveViewRequest) async throws {
        guard let operations = multipartLiveViewOperations() else {
            throw CCAPIError.unsupported(.liveViewMultipart)
        }
        try await startCCAPILiveView(request, path: operations.startLiveView.path)
        var stream: CameraHTTPStreamResponse?
        var candidateSession: CCAPIMultipartLiveViewSession?
        do {
            var value = try self.request(
                path: operations.openStream.path,
                method: .get,
                timeoutInterval: 60
            )
            value.setValue("multipart/x-mixed-replace", forHTTPHeaderField: "Accept")
            value.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
            let response = try await openMultipartStream(value)
            stream = response
            let boundary = try CCAPIMultipartLiveView.boundary(from: response.header("content-type"))
            let session = CCAPIMultipartLiveViewSession(response: response, boundary: boundary)
            candidateSession = session
            stream = nil
            let firstFrame = try await session.validatedFirstFrame()
            multipartSession?.close()
            multipartSession = session
            candidateSession = nil
            pendingMultipartFrame = firstFrame
            activeLiveViewSource = .ccapiMultipart
            observedFeatures.formUnion([.liveView, .liveViewMultipart])
        } catch {
            candidateSession?.close()
            stream?.cancel()
            try? await requestOK(
                path: operations.closeStream.path,
                method: .delete,
                expectedStatusCode: 200
            )
            await stopMultipartGeneralLiveView(operations)
            throw error
        }
    }

    private func openMultipartStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse {
        for attempt in 0...Self.multipartStartRetryNanoseconds.count {
            let response = try await transport.openStream(request)
            if response.statusCode == 200 { return response }
            let body = await Self.multipartErrorBody(response)
            response.cancel()
            let retryable = response.statusCode == 503 &&
                body.range(of: "live view not started", options: .caseInsensitive) != nil &&
                attempt < Self.multipartStartRetryNanoseconds.count
            guard retryable else {
                throw CCAPIError.http(
                    statusCode: response.statusCode,
                    method: request.httpMethod ?? "GET",
                    url: request.url?.absoluteString ?? "unknown",
                    body: body
                )
            }
            try await Task.sleep(nanoseconds: Self.multipartStartRetryNanoseconds[attempt])
        }
        throw CCAPIError.invalidResponse("Canon multipart Live View did not become ready.")
    }

    private static func multipartErrorBody(_ response: CameraHTTPStreamResponse) async -> String {
        await withTaskGroup(of: String.self) { group in
            group.addTask {
                var body = Data()
                do {
                    for try await chunk in response.chunks {
                        let remaining = Self.maximumErrorBodyCharacters - body.count
                        guard remaining > 0 else { break }
                        body.append(chunk.prefix(remaining))
                        if body.count == Self.maximumErrorBodyCharacters { break }
                    }
                } catch {
                    // The HTTP status remains authoritative when the optional error body is truncated.
                }
                return String(decoding: body, as: UTF8.self)
            }
            group.addTask {
                do {
                    try await Task.sleep(nanoseconds: 1_000_000_000)
                } catch {
                    return ""
                }
                response.cancel()
                return ""
            }
            let body = await group.next() ?? ""
            group.cancelAll()
            return body
        }
    }

    public func stopLiveView() async {
        latestLiveViewGeometry = nil
        guard initialized else { return }
        if resolvedMode == .simulator {
            activeLiveViewSource = nil
            return
        }
        switch activeLiveViewSource {
        case .ccapiRTP:
            await stopRTPLiveView()
        case .ccapiMultipart:
            await stopMultipartLiveView()
        case .ccapiJPEGPolling:
            await stopJPEGLiveView()
        default:
            break
        }
        activeLiveViewSource = nil
    }

    public func currentLiveViewSource() -> LiveViewSource? {
        activeLiveViewSource
    }

    public func currentLiveViewSize() -> LiveViewSize? {
        activeLiveViewSource == nil ? nil : activeLiveViewSize
    }

    public func currentNativeLiveViewSourceURL() -> URL? {
        rtpSession?.sourceURL
    }

    public func setLiveViewTargetFPS(_ fps: Int) async {
        await rtpSession?.setTargetFPS(min(max(fps, 1), 30))
    }

    public func setLiveViewMagnification(
        _ magnification: LiveViewMagnification
    ) async throws -> LiveViewMagnificationResult {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            guard activeLiveViewSource != nil,
                  liveViewMagnifications.contains(magnification) else {
                throw CCAPIError.unsupported(.liveViewMagnification)
            }
            let response = try await requestJSON(
                path: "/ccapi/liveview/magnification",
                method: .post,
                json: ["value": magnification.rawValue]
            )
            guard response["accepted"] as? Bool == true,
                  let returnedValue = Self.strictInteger(response["value"]),
                  let returned = LiveViewMagnification(rawValue: returnedValue),
                  returned == magnification,
                  liveViewMagnifications.contains(returned) else {
                throw CCAPIError.invalidResponse(
                    "Simulator did not confirm the requested Live View magnification."
                )
            }
            currentLiveViewMagnification = returned
            observedFeatures.insert(.liveViewMagnification)
            return LiveViewMagnificationResult(accepted: true, magnification: returned)
        }
        if liveViewMagnifications.isEmpty {
            _ = try await loadShootingSettings()
        }
        guard let operations = liveViewMagnificationOperations(),
              liveViewMagnifications.contains(magnification) else {
            throw CCAPIError.unsupported(.liveViewMagnification)
        }
        guard activeLiveViewSource != nil else {
            throw CCAPIError.invalidResponse("Canon Live View must be active before changing magnification.")
        }
        guard recording != true,
              cachedSettings?.object(Self.movieModeSettingKey)?.string("value") != "on" else {
            throw CCAPIError.invalidResponse("Canon Live View magnification is unavailable in Movie mode or while recording.")
        }
        try await requestOK(
            path: operations.write.path,
            method: .put,
            json: ["value": String(magnification.rawValue)]
        )
        guard let readback = try await firstJSON(paths: [operations.read.path], required: true),
              let setting = Self.validatedLiveViewMagnificationSetting(readback),
              setting.current == magnification else {
            throw CCAPIError.invalidResponse(
                "Canon Live View magnification PUT succeeded but GET readback did not confirm the requested value."
            )
        }
        liveViewMagnifications = setting.magnifications
        currentLiveViewMagnification = setting.current
        observedFeatures.insert(.liveViewMagnification)
        return LiveViewMagnificationResult(accepted: true, magnification: setting.current)
    }

    public func liveViewFrame(cacheKey: Int64) async throws -> LiveViewFrame {
        try await ensureInitialized()
        if activeLiveViewSource == .ccapiMultipart {
            guard let multipartSession else {
                throw CCAPIError.invalidResponse("Canon multipart Live View session is missing.")
            }
            let frame: Data
            if let pendingMultipartFrame {
                frame = pendingMultipartFrame
                self.pendingMultipartFrame = nil
            } else {
                frame = try await multipartSession.nextFrame()
            }
            observedFeatures.formUnion([.liveView, .liveViewMultipart])
            guard let sourceURL = URL(
                string: baseURLString + (multipartLiveViewOperations()?.openStream.path ?? "")
            ) else {
                throw CCAPIError.invalidResponse("Canon multipart Live View source URL is invalid.")
            }
            return LiveViewFrame(
                data: frame,
                contentType: "image/jpeg",
                sourceURL: sourceURL
            )
        }
        if activeLiveViewSource == .ccapiRTP {
            throw CCAPIError.invalidResponse(
                "CCAPI RTP Live View renders through the native H.264 surface, not the JPEG frame reader."
            )
        }
        let paths: [String]
        if resolvedMode == .simulator {
            paths = ["/ccapi/liveview/frame"]
        } else {
            paths = liveViewFramePaths()
            guard !paths.isEmpty else { throw CCAPIError.unsupported(.liveView) }
        }

        var failures: [String] = []
        var liveViewSizeRejected = false
        for attempt in 0...Self.jpegFrameBusyRetryNanoseconds.count {
            var transientBusy = false
            for path in paths {
                try Task.checkCancellation()
                let sourceURL = try URLForPath(path, cacheKey: cacheKey)
                var request = request(url: sourceURL, method: .get)
                request.setValue("multipart/x-mixed-replace,image/jpeg,image/*,*/*", forHTTPHeaderField: "Accept")
                request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
                request.setValue("no-cache", forHTTPHeaderField: "Pragma")
                request.setValue("close", forHTTPHeaderField: "Connection")
                do {
                    let isDetailedFrame = path.contains("/shooting/liveview/flipdetail") && path.contains("kind=both")
                    if isDetailedFrame {
                        latestLiveViewGeometry = nil
                    }
                    let response = try await transport.send(request)
                    try validate(response, request: request)
                    let contentType = response.header("content-type")
                    if Self.isTextContentType(contentType) {
                        throw CCAPIError.invalidResponse("Live View returned \(contentType ?? "text") instead of image bytes.")
                    }
                    let frame: Data
                    if isDetailedFrame {
                        let detailed = try Self.parseDetailedLiveView(response.body)
                        if let geometry = detailed.geometry {
                            latestLiveViewGeometry = geometry
                        }
                        guard let image = detailed.image else {
                            throw CCAPIError.invalidResponse(
                                "Detailed Live View response did not contain an image packet."
                            )
                        }
                        frame = image
                    } else {
                        frame = try JPEGFrameParser.validatedImageData(response.body, contentType: contentType)
                    }
                    observedFeatures.formUnion([.liveView, .liveViewJPEGPolling])
                    return LiveViewFrame(data: frame, contentType: contentType, sourceURL: sourceURL)
                } catch {
                    if error is CancellationError { throw error }
                    liveViewSizeRejected = liveViewSizeRejected || shouldDowngradeLiveViewSize(for: error)
                    failures.append("\(sourceURL.absoluteString): \(error.localizedDescription)")
                    if shouldRetryBusyLiveViewFrame(error) {
                        transientBusy = true
                        break
                    }
                }
            }
            if transientBusy, attempt < Self.jpegFrameBusyRetryNanoseconds.count {
                try await Task.sleep(nanoseconds: Self.jpegFrameBusyRetryNanoseconds[attempt])
                continue
            }
            break
        }
        if liveViewSizeRejected {
            return try await retryJPEGLiveViewAtSmallSize(cacheKey: cacheKey)
        }
        throw CCAPIError.invalidResponse(
            "Live View failed on every advertised JPEG endpoint.\n" + failures.map { "- \($0)" }.joined(separator: "\n")
        )
    }

    public func listMedia() async throws -> [CameraMediaItem] {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            let value = try await requestJSON(path: "/ccapi/media")
            let items = value.array("items")?.objects.map {
                CameraMediaItem(
                    id: $0.string("id"),
                    name: $0.string("name"),
                    kind: $0.string("kind", default: "other"),
                    sizeBytes: $0.integer64("size_bytes"),
                    captureTime: $0.string("capture_time").nilIfEmpty,
                    previewAvailable: ["image", "raw"].contains($0.string("kind", default: "other").lowercased()),
                    protected: $0.bool("protect"),
                    rating: $0.integer("rating").flatMap { (0...5).contains($0) ? $0 : nil },
                    rotationDegrees: $0.integer("rotate").flatMap { Self.mediaRotations.contains($0) ? $0 : nil },
                    archived: $0.bool("archive")
                )
            } ?? []
            observedFeatures.insert(.mediaBrowser)
            return items
        }
        guard supports(.get, suffix: "/contents") else { throw CCAPIError.unsupported(.mediaBrowser) }

        var pending: [(path: String, depth: Int)] = [(apiPath(.get, suffix: "/contents"), 0)]
        var visited = Set<String>()
        var mediaPathGroups: [[String]] = []
        while !pending.isEmpty {
            let next = pending.removeFirst()
            let container = try normalizeCameraResource(next.path).components(separatedBy: "?")[0]
            guard next.depth <= Self.maximumMediaTreeDepth, visited.insert(container).inserted else { continue }
            var mediaPaths: [String] = []
            for rawPath in try await contentPaths(container: container, maxPaths: Self.maximumMediaItems) {
                let path = try normalizeCameraResource(rawPath).components(separatedBy: "?")[0]
                if Self.isMediaPath(path) {
                    if !mediaPaths.contains(path) { mediaPaths.append(path) }
                } else if !visited.contains(path) {
                    pending.append((path, next.depth + 1))
                }
            }
            if !mediaPaths.isEmpty { mediaPathGroups.append(mediaPaths) }
        }
        let mediaPaths = mergeMediaPathGroups(mediaPathGroups, maxItems: Self.maximumMediaItems)
        observedFeatures.insert(.mediaBrowser)
        return mediaPaths.map {
            CameraMediaItem(
                id: $0,
                name: ($0 as NSString).lastPathComponent,
                kind: Self.mediaKind($0),
                previewAvailable: Self.isCCAPIDisplayPreviewPath($0)
            )
        }
    }

    public func downloadMedia(
        _ item: CameraMediaItem,
        to destination: URL,
        progress: @escaping CameraMediaProgressHandler = { _ in }
    ) async throws -> CameraMediaDownload {
        try await ensureInitialized()
        guard !FileManager.default.fileExists(atPath: destination.path) else {
            throw CCAPIError.destinationExists(destination.path)
        }
        let paths: [String]
        if resolvedMode == .simulator {
            paths = ["/ccapi/media/\(Self.encodePathComponent(item.id))"]
        } else {
            guard supports(.get, suffix: "/contents") || observedFeatures.contains(.mediaDownload) else {
                throw CCAPIError.unsupported(.mediaDownload)
            }
            let path = try normalizeCameraResource(item.id).components(separatedBy: "?")[0]
            paths = [path, "\(path)?kind=main", "\(path)?type=main"]
        }

        var failures: [String] = []
        for path in paths {
            try Task.checkCancellation()
            let download = try await transport.download(
                request(path: path, method: .get),
                progress: { value in
                    progress(
                        CameraMediaTransferProgress(
                            bytesTransferred: value.bytesTransferred,
                            totalBytes: value.totalBytes ?? item.sizeBytes
                        )
                    )
                }
            )
            if (200..<300).contains(download.statusCode) {
                let contentType = download.header("content-type")
                let prefix = (try? Data(contentsOf: download.temporaryFileURL).prefix(2_000)).map { Data($0) } ?? Data()
                if Self.isTextContentType(contentType) || Self.looksLikeTextPayload(prefix) {
                    let preview = String(data: prefix, encoding: .utf8) ?? ""
                    try? FileManager.default.removeItem(at: download.temporaryFileURL)
                    failures.append("\(path): HTTP \(download.statusCode): \(preview)")
                    continue
                }
                do {
                    try FileManager.default.moveItem(at: download.temporaryFileURL, to: destination)
                } catch {
                    try? FileManager.default.removeItem(at: download.temporaryFileURL)
                    throw error
                }
                let fileSize = try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize
                let size = Int64(fileSize ?? 0)
                progress(
                    CameraMediaTransferProgress(
                        bytesTransferred: size,
                        totalBytes: size
                    )
                )
                observedFeatures.insert(.mediaDownload)
                return CameraMediaDownload(
                    item: item,
                    fileURL: destination,
                    bytesTransferred: size,
                    contentType: contentType
                )
            }
            let preview = (try? Data(contentsOf: download.temporaryFileURL).prefix(2_000))
                .flatMap { String(data: Data($0), encoding: .utf8) } ?? ""
            try? FileManager.default.removeItem(at: download.temporaryFileURL)
            failures.append("\(path): HTTP \(download.statusCode): \(preview)")
        }
        throw CCAPIError.invalidResponse(
            "Media download failed for '\(item.name)'.\n" + failures.map { "- \($0)" }.joined(separator: "\n")
        )
    }

    public func uploadMedia(
        from fileURL: URL,
        contentType: String? = nil,
        progress: @escaping CameraMediaProgressHandler = { _ in }
    ) async throws -> CameraMediaItem {
        throw CCAPIError.unsupported(.mediaUpload)
    }

    public func mediaThumbnail(_ item: CameraMediaItem) async throws -> CameraMediaThumbnail {
        let response = try await mediaImageRepresentation(
            item,
            kind: "thumbnail",
            maximumBytes: Self.maximumMediaThumbnailBytes,
            label: "thumbnail",
            feature: .mediaThumbnail
        )
        observedFeatures.insert(.mediaThumbnail)
        return CameraMediaThumbnail(item: item, data: response.data, contentType: response.contentType)
    }

    public func mediaPreview(_ item: CameraMediaItem) async throws -> CameraMediaPreview {
        let imageKind = ["image", "raw"].contains(item.kind.lowercased())
        let previewEligible = resolvedMode == .simulator
            ? imageKind
            : imageKind && Self.isCCAPIDisplayPreviewPath(item.id)
        guard previewEligible else {
            throw CCAPIError.invalidResponse("CCAPI display preview is available only for JPEG or CR3 items.")
        }
        let response = try await mediaImageRepresentation(
            item,
            kind: "display",
            maximumBytes: Self.maximumMediaPreviewBytes,
            label: "display preview",
            feature: .mediaPreview
        )
        observedFeatures.insert(.mediaPreview)
        return CameraMediaPreview(item: item, data: response.data, contentType: response.contentType)
    }

    public func mediaInfo(_ item: CameraMediaItem) async throws -> CameraMediaItem {
        try await ensureInitialized()
        if resolvedMode != .simulator, !supports(.get, suffix: "/contents") {
            throw CCAPIError.unsupported(.mediaBrowser)
        }
        let path = try mediaPath(item)
        let body = try await requestJSON(path: "\(path)?kind=info")
        return parseMediaInfo(item, body: body)
    }

    public func setMediaProtection(_ item: CameraMediaItem, enabled: Bool) async throws -> CameraMediaItem {
        try await modifyMedia(
            item,
            action: "protect",
            value: enabled ? "enable" : "disable",
            feature: .mediaProtect,
            matches: { $0.protected == enabled }
        )
    }

    public func setMediaRating(_ item: CameraMediaItem, rating: Int) async throws -> CameraMediaItem {
        guard (0...5).contains(rating) else {
            throw CCAPIError.invalidResponse("Media rating must be from 0 through 5.")
        }
        return try await modifyMedia(
            item,
            action: "rating",
            value: rating == 0 ? "off" : String(rating),
            feature: .mediaRating,
            matches: { $0.rating == rating }
        )
    }

    public func setMediaRotation(_ item: CameraMediaItem, degrees: Int) async throws -> CameraMediaItem {
        guard Self.mediaRotations.contains(degrees) else {
            throw CCAPIError.invalidResponse("Media rotation must be 0, 90, 180, or 270 degrees.")
        }
        return try await modifyMedia(
            item,
            action: "rotate",
            value: String(degrees),
            feature: .mediaRotate,
            matches: { $0.rotationDegrees == degrees }
        )
    }

    public func setMediaArchive(_ item: CameraMediaItem, enabled: Bool) async throws -> CameraMediaItem {
        try await modifyMedia(
            item,
            action: "archive",
            value: enabled ? "enable" : "disable",
            feature: .mediaArchive,
            matches: { $0.archived == enabled }
        )
    }

    private func modifyMedia(
        _ item: CameraMediaItem,
        action: String,
        value: String,
        feature: CameraFeature,
        matches: (CameraMediaItem) -> Bool
    ) async throws -> CameraMediaItem {
        try await ensureInitialized()
        if resolvedMode != .simulator, !supportsMediaModify() {
            throw CCAPIError.unsupported(feature)
        }
        try await requestOK(
            path: try mediaPath(item),
            method: .put,
            json: ["action": action, "value": value],
            expectedStatusCode: 200
        )
        var latest = item
        for attempt in 0..<3 {
            latest = try await mediaInfo(item)
            if matches(latest) {
                observedFeatures.insert(feature)
                return latest
            }
            if attempt < 2 { try await Task.sleep(nanoseconds: 100_000_000) }
        }
        throw CCAPIError.invalidResponse(
            "Camera accepted media \(action) but did not report the requested value."
        )
    }

    private func mediaPath(_ item: CameraMediaItem) throws -> String {
        if resolvedMode == .simulator {
            return "/ccapi/media/\(Self.encodePathComponent(item.id))"
        }
        return try normalizeCameraResource(item.id).components(separatedBy: "?")[0]
    }

    private func parseMediaInfo(_ item: CameraMediaItem, body: JSONDictionary) -> CameraMediaItem {
        let protected: Bool? = switch body.string("protect") {
        case "enable": true
        case "disable": false
        default: nil
        }
        let ratingValue = body.string("rating")
        let parsedRating = Int(ratingValue)
        let rating = ratingValue == "off"
            ? 0
            : parsedRating.flatMap { (1...5).contains($0) ? $0 : nil }
        let rotation = body.integer("rotate").flatMap { Self.mediaRotations.contains($0) ? $0 : nil }
        let archived = Self.parseMediaArchive(body.string("archive"))
        return CameraMediaItem(
            id: item.id,
            name: item.name,
            kind: item.kind,
            sizeBytes: body.integer64("filesize").flatMap { $0 > 0 ? $0 : nil } ?? item.sizeBytes,
            captureTime: body.string("lastmodifieddate").nilIfEmpty ?? item.captureTime,
            previewAvailable: item.previewAvailable,
            protected: protected,
            rating: rating,
            rotationDegrees: rotation,
            archived: archived
        )
    }

    private static func parseMediaArchive(_ value: String) -> Bool? {
        switch value {
        case "enable": true
        case "disable": false
        default: nil
        }
    }

    private func mediaImageRepresentation(
        _ item: CameraMediaItem,
        kind: String,
        maximumBytes: Int,
        label: String,
        feature: CameraFeature
    ) async throws -> (data: Data, contentType: String?) {
        try await ensureInitialized()
        let basePath: String
        if resolvedMode == .simulator {
            basePath = "/ccapi/media/\(Self.encodePathComponent(item.id))"
        } else {
            guard supports(.get, suffix: "/contents") else {
                throw CCAPIError.unsupported(feature)
            }
            basePath = try normalizeCameraResource(item.id).components(separatedBy: "?")[0]
        }
        guard var components = URLComponents(string: basePath) else {
            throw CCAPIError.invalidResponse("Invalid camera media path: \(basePath)")
        }
        components.queryItems = [URLQueryItem(name: "kind", value: kind)]
        guard let representationPath = components.string else {
            throw CCAPIError.invalidResponse("Could not build the camera \(label) path.")
        }
        var representationRequest = try request(path: representationPath, method: .get)
        representationRequest.setValue("image/*,application/octet-stream;q=0.5", forHTTPHeaderField: "Accept")
        representationRequest.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let response = try await transport.send(representationRequest)
        try validate(response, request: representationRequest)
        guard !response.body.isEmpty else {
            throw CCAPIError.invalidResponse("Camera returned an empty media \(label).")
        }
        guard response.body.count <= maximumBytes else {
            throw CCAPIError.invalidResponse(
                "Camera \(label) exceeded \(maximumBytes) bytes."
            )
        }
        let responseContentType = response.header("content-type")?
            .components(separatedBy: ";")[0]
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        let detectedContentType = Self.imageContentType(response.body)
        guard !Self.isTextContentType(responseContentType),
              !Self.looksLikeTextPayload(response.body),
              responseContentType?.hasPrefix("image/") == true || detectedContentType != nil else {
            throw CCAPIError.invalidResponse("Camera did not return a recognized image \(label).")
        }
        let contentType = responseContentType?.hasPrefix("image/") == true
            ? responseContentType
            : detectedContentType
        return (response.body, contentType)
    }

    public func deleteMedia(_ item: CameraMediaItem) async throws {
        try await ensureInitialized()
        let path: String
        if resolvedMode == .simulator {
            path = "/ccapi/media/\(Self.encodePathComponent(item.id))"
        } else {
            guard supportsMediaDelete() else { throw CCAPIError.unsupported(.mediaDelete) }
            path = try normalizeCameraResource(item.id).components(separatedBy: "?")[0]
        }
        try await requestOK(path: path, method: .delete)
        observedFeatures.insert(.mediaDelete)
    }

    public func diagnosticReport(
        snapshot: CameraSnapshot?,
        liveView: CCAPILiveViewMetrics = CCAPILiveViewMetrics(),
        lastError: String? = nil
    ) async -> String {
        let currentSnapshot: CameraSnapshot?
        if let snapshot, let capabilities = try? await capabilities() {
            currentSnapshot = CameraSnapshot(info: snapshot.info, status: snapshot.status, capabilities: capabilities)
        } else {
            currentSnapshot = snapshot
        }
        return CCAPIDiagnosticReport.make(
            baseURL: baseURL,
            mode: resolvedMode,
            versions: apiVersionPrefixes,
            snapshot: currentSnapshot,
            liveView: liveView,
            lastError: lastError
        )
    }

    private func ensureInitialized() async throws {
        if !initialized { try await initialize() }
    }

    private func resolveMode(_ mode: CCAPIConnectionMode) -> CCAPIConnectionMode {
        guard mode == .automatic else { return mode }
        let host = baseURL.host?.lowercased() ?? ""
        if ["localhost", "127.0.0.1", "::1", "10.0.2.2"].contains(host) || baseURL.port == 18080 {
            return .simulator
        }
        return .camera
    }

    private func parseDiscovery(_ value: JSONDictionary, source: String) {
        var versions = Set<String>()
        enforceAdvertisedOperations = true
        operations.removeAll()
        discoverySource = source
        value.array("api")?.strings.forEach {
            if let version = Self.extractVersion(from: $0) { versions.insert(version) }
        }
        for (key, entries) in value {
            guard Self.versionNumber(key) != nil else { continue }
            versions.insert(key)
            guard let entries = entries as? [Any] else { continue }
            recordOperations(version: key, entries: entries.objects)
        }
        let version = value.string("version")
        if Self.versionNumber(version) != nil { versions.insert(version) }
        if versions.isEmpty { versions.insert("ver100") }
        apiVersionPrefixes = versions.sorted { (Self.versionNumber($0) ?? 0) > (Self.versionNumber($1) ?? 0) }
            .map { "/ccapi/\($0)" }
        preferredVersionPrefix = apiVersionPrefixes.contains("/ccapi/ver100") ? "/ccapi/ver100" : apiVersionPrefixes[0]
    }

    private func recordDiscoveryResponse(
        endpoint: String,
        outcome: String,
        response: JSONDictionary,
        httpStatus: Int? = 200,
        operationCount: Int? = nil
    ) {
        let keys = response.keys.compactMap(Self.safeDiscoveryKey).removingDuplicates().sorted()
        var versions = Set<String>()
        response.array("api")?.strings.forEach {
            if let version = Self.extractVersion(from: $0) { versions.insert(version) }
        }
        keys.filter { Self.versionNumber($0) != nil }.forEach { versions.insert($0) }
        let version = response.string("version")
        if Self.versionNumber(version) != nil { versions.insert(version) }
        let orderedVersions = versions.sorted {
            (Self.versionNumber($0) ?? 0) > (Self.versionNumber($1) ?? 0)
        }
        recordDiscoveryAttempt(
            CameraDiscoveryAttempt(
                endpoint: String(endpoint.prefix(128)),
                outcome: String(outcome.prefix(64)),
                httpStatus: httpStatus,
                responseKeys: Array(keys.prefix(Self.maximumDiscoveryTraceKeys)),
                protocolVersions: Array(orderedVersions.prefix(Self.maximumDiscoveryTraceKeys)),
                advertisedOperationCount: max(0, operationCount ?? operations.count),
                truncated: keys.count > Self.maximumDiscoveryTraceKeys ||
                    orderedVersions.count > Self.maximumDiscoveryTraceKeys
            )
        )
    }

    private func recordDiscoveryFailure(endpoint: String, error: Error) {
        let statusCode: Int?
        if case let CCAPIError.http(value, _, _, _) = error {
            statusCode = value
        } else {
            statusCode = nil
        }
        recordDiscoveryAttempt(
            CameraDiscoveryAttempt(
                endpoint: String(endpoint.prefix(128)),
                outcome: statusCode == nil ? "REQUEST_ERROR" : "HTTP_ERROR",
                httpStatus: statusCode
            )
        )
    }

    private func recordDiscoveryAttempt(_ attempt: CameraDiscoveryAttempt) {
        if discoveryTrace.count < Self.maximumDiscoveryTraceAttempts {
            discoveryTrace.append(attempt)
        } else {
            discoveryTraceTruncated = true
        }
    }

    private static func safeDiscoveryKey(_ value: String) -> String? {
        let scalars = Array(value.unicodeScalars)
        guard !scalars.isEmpty, scalars.count <= maximumDiscoveryTraceKeyCharacters else { return nil }
        func isLetter(_ scalar: UnicodeScalar) -> Bool {
            (65...90).contains(scalar.value) || (97...122).contains(scalar.value)
        }
        func isAllowed(_ scalar: UnicodeScalar) -> Bool {
            isLetter(scalar) || (48...57).contains(scalar.value) || scalar.value == 95 || scalar.value == 45
        }
        guard isLetter(scalars[0]), scalars.allSatisfy(isAllowed) else { return nil }
        return value
    }

    private func recordOperations(version: String, entries: [JSONDictionary]) {
        for entry in entries {
            guard let path = advertisedOperationPath(version: version, entry: entry) else { continue }
            for method in [HTTPMethod.get, .put, .post, .delete] {
                if Self.methodSupported(entry[method.rawValue.lowercased()]) {
                    operations.insert(CCAPIOperation(method: method, path: path))
                }
            }
        }
    }

    private func advertisedOperationPath(version: String, entry: JSONDictionary) -> String? {
        for value in [entry.string("path"), entry.string("url")] {
            let raw = value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !raw.isEmpty,
                  let components = URLComponents(string: raw),
                  components.fragment == nil,
                  components.user == nil,
                  components.password == nil else { continue }

            let path: String
            if components.scheme != nil || components.host != nil {
                guard components.scheme != nil,
                      let absolute = components.url,
                      Self.sameOrigin(absolute, baseURL) else { continue }
                path = components.percentEncodedPath
            } else {
                path = components.percentEncodedPath
            }
            guard !path.isEmpty,
                  !path.split(separator: "/", omittingEmptySubsequences: false).contains(where: {
                      $0 == "." || $0 == ".."
                  }) else { continue }
            let normalized = path.hasPrefix("/ccapi/")
                ? path
                : "/ccapi/\(version)/\(path.trimmingCharacters(in: CharacterSet(charactersIn: "/")))"
            guard normalized.hasPrefix("/ccapi/"),
                  !normalized.contains("\r"),
                  !normalized.contains("\n") else { continue }
            return normalized
        }
        return nil
    }

    private func versionedPaths(_ suffix: String) -> [String] {
        apiVersionPrefixes.map { "\($0)\(suffix)" }
    }

    private func supports(_ method: HTTPMethod, suffix: String) -> Bool {
        operations.contains { $0.method == method && $0.path.hasSuffix(suffix) }
    }

    private func operation(_ method: HTTPMethod, suffix: String) -> CCAPIOperation? {
        let matching = operations.filter { $0.method == method && $0.path.hasSuffix(suffix) }
        return matching.first { $0.path.hasPrefix(preferredVersionPrefix) }
            ?? matching.max { Self.pathVersion($0.path) < Self.pathVersion($1.path) }
    }

    private func zoomOperations() -> (read: CCAPIOperation, write: CCAPIOperation)? {
        let reads = operations
            .filter { $0.method == .get && $0.path.hasSuffix(Self.zoomPathSuffix) }
            .sorted { Self.pathVersion($0.path) > Self.pathVersion($1.path) }
        for read in reads {
            if let write = operations.first(where: { $0.method == .post && $0.path == read.path }) {
                return (read, write)
            }
        }
        return nil
    }

    private func liveViewMagnificationOperations() -> (read: CCAPIOperation, write: CCAPIOperation)? {
        readWriteSettingOperations(suffix: Self.liveViewMagnificationPathSuffix)
    }

    private func movieModeOperations() -> (read: CCAPIOperation, write: CCAPIOperation)? {
        let reads = operations
            .filter { $0.method == .get && $0.path.hasSuffix(Self.movieModePathSuffix) }
            .sorted { Self.pathVersion($0.path) > Self.pathVersion($1.path) }
        for read in reads {
            if let write = operations.first(where: { $0.method == .post && $0.path == read.path }) {
                return (read, write)
            }
        }
        return nil
    }

    private func cardSelectionOperations(
        suffix: String
    ) -> (read: CCAPIOperation, write: CCAPIOperation)? {
        let reads = operations
            .filter { $0.method == .get && $0.path.hasSuffix(suffix) }
            .sorted { Self.pathVersion($0.path) > Self.pathVersion($1.path) }
        for read in reads {
            if let write = operations.first(where: { $0.method == .put && $0.path == read.path }) {
                return (read, write)
            }
        }
        return nil
    }

    private func soundRecordingLevelOperations(
        suffix: String
    ) -> (read: CCAPIOperation, write: CCAPIOperation)? {
        let reads = operations
            .filter { $0.method == .get && $0.path.hasSuffix(suffix) }
            .sorted { Self.pathVersion($0.path) > Self.pathVersion($1.path) }
        for read in reads {
            if let write = operations.first(where: { $0.method == .put && $0.path == read.path }) {
                return (read, write)
            }
        }
        return nil
    }

    private func soundRecordingOperations(
        suffix: String
    ) -> (read: CCAPIOperation, write: CCAPIOperation)? {
        let reads = operations
            .filter { $0.method == .get && $0.path.hasSuffix(suffix) }
            .sorted { Self.pathVersion($0.path) > Self.pathVersion($1.path) }
        for read in reads {
            if let write = operations.first(where: { $0.method == .put && $0.path == read.path }) {
                return (read, write)
            }
        }
        return nil
    }

    private func readWriteSettingOperations(
        suffix: String
    ) -> (read: CCAPIOperation, write: CCAPIOperation)? {
        let reads = operations
            .filter { $0.method == .get && $0.path.hasSuffix(suffix) }
            .sorted { Self.pathVersion($0.path) > Self.pathVersion($1.path) }
        for read in reads {
            if let write = operations.first(where: { $0.method == .put && $0.path == read.path }) {
                return (read, write)
            }
        }
        return nil
    }

    private func directoryOperations() -> (read: CCAPIOperation, write: CCAPIOperation, create: CCAPIOperation)? {
        let reads = operations
            .filter { $0.method == .get && $0.path.hasSuffix(Self.directorySelectionPathSuffix) }
            .sorted { Self.pathVersion($0.path) > Self.pathVersion($1.path) }
        for read in reads {
            let prefix = String(read.path.dropLast(Self.directorySelectionPathSuffix.count))
            let write = CCAPIOperation(method: .put, path: read.path)
            let create = CCAPIOperation(method: .post, path: "\(prefix)\(Self.directoryCreatePathSuffix)")
            if operations.contains(write), operations.contains(create) {
                return (read, write, create)
            }
        }
        return nil
    }

    private func fileNamingOperations() -> [
        CameraFileNamingField: (read: CCAPIOperation, write: CCAPIOperation)
    ]? {
        let prefixes = apiVersionPrefixes.sorted { Self.pathVersion($0) > Self.pathVersion($1) }
        for prefix in prefixes {
            var result: [CameraFileNamingField: (read: CCAPIOperation, write: CCAPIOperation)] = [:]
            for endpoint in Self.fileNamingEndpoints {
                let path = "\(prefix)\(endpoint.suffix)"
                let read = CCAPIOperation(method: .get, path: path)
                let write = CCAPIOperation(method: .put, path: path)
                guard operations.contains(read), operations.contains(write) else {
                    result.removeAll()
                    break
                }
                result[endpoint.field] = (read, write)
            }
            if result.count == Self.fileNamingEndpoints.count { return result }
        }
        return nil
    }

    private func directShutterOperation() -> CCAPIOperation? {
        operation(.post, suffix: "/shooting/control/shutterbutton")
    }

    private func eventPollingOperations() -> (poll: CCAPIOperation, stop: CCAPIOperation)? {
        let gets = operations
            .filter { $0.method == .get && $0.path.hasSuffix("/event/polling") }
            .sorted { Self.pathVersion($0.path) > Self.pathVersion($1.path) }
        for get in gets {
            let prefix = String(get.path.dropLast("/event/polling".count))
            let stop = CCAPIOperation(method: .delete, path: "\(prefix)/event/polling")
            if operations.contains(stop) { return (get, stop) }
        }
        return nil
    }

    private func cameraClockOperations() -> (read: CCAPIOperation, write: CCAPIOperation)? {
        let reads = operations
            .filter { $0.method == .get && $0.path.hasSuffix("/functions/datetime") }
            .sorted { Self.pathVersion($0.path) > Self.pathVersion($1.path) }
        for read in reads {
            let prefix = String(read.path.dropLast("/functions/datetime".count))
            let write = CCAPIOperation(method: .put, path: "\(prefix)/functions/datetime")
            if operations.contains(write) { return (read, write) }
        }
        return nil
    }

    private func manualShutterOperation() -> CCAPIOperation? {
        operation(.put, suffix: "/shooting/control/shutterbutton/manual")
            ?? operation(.post, suffix: "/shooting/control/shutterbutton/manual")
    }

    private func recordingOperation() -> CCAPIOperation? {
        operation(.post, suffix: "/shooting/control/recbutton")
            ?? operation(.put, suffix: "/shooting/control/recbutton")
    }

    private func tapFocusOperation() -> CCAPIOperation? {
        operation(.put, suffix: "/shooting/liveview/afframeposition")
    }

    private func detailedLiveViewOperation() -> CCAPIOperation? {
        operation(.get, suffix: "/shooting/liveview/flipdetail")
    }

    private func clickWhiteBalanceOperation() -> CCAPIOperation? {
        operation(.post, suffix: "/shooting/liveview/clickwb")
    }

    private func supportsCoordinateTapFocus() -> Bool {
        tapFocusOperation() != nil &&
            detailedLiveViewOperation() != nil &&
            supportsCompleteLiveView()
    }

    private func supportsCoordinateClickWhiteBalance() -> Bool {
        clickWhiteBalanceOperation() != nil &&
            detailedLiveViewOperation() != nil &&
            supportsCompleteLiveView()
    }

    private func needsLiveViewGeometry() -> Bool {
        supportsCoordinateTapFocus() || supportsCoordinateClickWhiteBalance()
    }

    private func autofocusOperation() -> CCAPIOperation? {
        operation(.post, suffix: "/shooting/control/af")
    }

    private func focusDriveOperation() -> CCAPIOperation? {
        operation(.post, suffix: "/shooting/control/drivefocus")
    }

    private func liveViewFramePaths() -> [String] {
        guard enforceAdvertisedOperations else {
            return [
                apiPath(.get, suffix: "/shooting/liveview/flip"),
                apiPath(.get, suffix: "/shooting/liveview/flipdetail") + "?kind=image",
                apiPath(.get, suffix: "/shooting/liveview"),
            ]
        }
        if let operations = jpegLiveViewOperations() {
            var paths: [String] = []
            if needsLiveViewGeometry(),
               let detail = operations.framePaths.first(where: { $0.hasSuffix("/shooting/liveview/flipdetail") }) {
                paths.append("\(detail)?kind=both")
            }
            paths.append(contentsOf: operations.framePaths.map { path in
                path.hasSuffix("/shooting/liveview/flipdetail") ? "\(path)?kind=image" : path
            })
            return paths
        }
        var paths: [String] = []
        if needsLiveViewGeometry(), let detail = detailedLiveViewOperation() {
            paths.append("\(detail.path)?kind=both")
        }
        paths.append(contentsOf: [
            operation(.get, suffix: "/shooting/liveview/flip")?.path,
            operation(.get, suffix: "/shooting/liveview/flipdetail").map { "\($0.path)?kind=image" },
            operation(.get, suffix: "/shooting/liveview")?.path,
        ].compactMap { $0 })
        return paths
    }

    private static func parseDetailedLiveView(_ data: Data) throws -> CCAPIDetailedLiveView {
        let bytes = [UInt8](data)
        var offset = 0
        var image: Data?
        var geometry: CCAPILiveViewGeometry?
        var foundPacket = false

        while offset + 9 <= bytes.count {
            guard bytes[offset] == 0xFF, bytes[offset + 1] == 0x00 else { break }
            let type = bytes[offset + 2]
            let size = (Int(bytes[offset + 3]) << 24) |
                (Int(bytes[offset + 4]) << 16) |
                (Int(bytes[offset + 5]) << 8) |
                Int(bytes[offset + 6])
            let dataStart = offset + 7
            let dataEnd = dataStart + size
            guard size <= JPEGFrameParser.maximumScanBytes,
                  dataEnd + 2 <= bytes.count,
                  bytes[dataEnd] == 0xFF,
                  bytes[dataEnd + 1] == 0xFF else { break }
            foundPacket = true
            let payload = Data(bytes[dataStart..<dataEnd])
            if type == 0x00 {
                image = try JPEGFrameParser.firstJPEG(in: payload)
            } else if type == 0x01 {
                geometry = parseLiveViewGeometry(payload)
            }
            offset = dataEnd + 2
        }

        guard foundPacket else {
            throw CCAPIError.invalidResponse(
                "Detailed Live View response did not contain a valid Canon packet."
            )
        }
        return CCAPIDetailedLiveView(image: image, geometry: geometry)
    }

    private static func parseLiveViewGeometry(_ data: Data) -> CCAPILiveViewGeometry? {
        guard data.count <= 2 * 1024 * 1024,
              let root = try? JSONSerialization.jsonObject(with: data) else { return nil }

        func find(_ node: Any) -> CCAPILiveViewGeometry? {
            guard let object = node as? [String: Any] else { return nil }
            if let image = object["image"] as? [String: Any],
               let x = image["positionx"] as? NSNumber,
               let y = image["positiony"] as? NSNumber,
               let width = image["positionwidth"] as? NSNumber,
               let height = image["positionheight"] as? NSNumber,
               width.intValue > 0,
               height.intValue > 0 {
                return CCAPILiveViewGeometry(
                    positionX: x.intValue,
                    positionY: y.intValue,
                    positionWidth: width.intValue,
                    positionHeight: height.intValue
                )
            }
            for child in object.values {
                if let geometry = find(child) { return geometry }
            }
            return nil
        }

        return find(root)
    }

    private func cameraLiveViewPosition(
        x: Double,
        y: Double,
        feature: CameraFeature
    ) throws -> (x: Int, y: Int) {
        guard (0...1).contains(x), (0...1).contains(y) else {
            throw CCAPIError.invalidResponse("\(feature.rawValue) coordinates must be normalized from 0 through 1.")
        }
        guard let geometry = latestLiveViewGeometry else {
            throw CCAPIError.invalidResponse(
                "\(feature.rawValue) needs a detailed Live View frame with Canon image position metadata."
            )
        }
        return geometry.cameraPosition(normalizedX: x, normalizedY: y)
    }

    private func ensureLiveViewGeometryForNativeStream() async throws {
        guard latestLiveViewGeometry == nil,
              activeLiveViewSource == .ccapiRTP || activeLiveViewSource == .ccapiMultipart else { return }
        guard let detail = detailedLiveViewOperation() else {
            throw CCAPIError.invalidResponse(
                "Coordinate control needs the camera's detailed Live View endpoint."
            )
        }
        let cacheKey = nativeGeometryCacheKey
        nativeGeometryCacheKey &+= 1
        let sourceURL = try URLForPath("\(detail.path)?kind=both", cacheKey: cacheKey)
        var request = request(url: sourceURL, method: .get)
        request.setValue("application/octet-stream,*/*", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let response = try await transport.send(request)
        try validate(response, request: request)
        let detailed = try Self.parseDetailedLiveView(response.body)
        guard let geometry = detailed.geometry else {
            throw CCAPIError.invalidResponse(
                "Detailed Live View response did not contain Canon image position metadata."
            )
        }
        latestLiveViewGeometry = geometry
    }

    private func supportsCompleteLiveView() -> Bool {
        jpegLiveViewOperations() != nil
    }

    private func jpegLiveViewOperations() -> CCAPIJPEGLiveViewOperations? {
        let liveViewSuffix = "/shooting/liveview"
        let starts = operations
            .filter { $0.method == .post && $0.path.hasSuffix(liveViewSuffix) }
            .sorted { left, right in
                let leftPreferred = left.path.hasPrefix(preferredVersionPrefix)
                let rightPreferred = right.path.hasPrefix(preferredVersionPrefix)
                if leftPreferred != rightPreferred { return leftPreferred }
                return Self.pathVersion(left.path) > Self.pathVersion(right.path)
            }

        for start in starts {
            let prefix = String(start.path.dropLast(liveViewSuffix.count))
            let candidates = [
                "\(prefix)\(liveViewSuffix)/flip",
                "\(prefix)\(liveViewSuffix)/flipdetail",
                "\(prefix)\(liveViewSuffix)",
            ]
            let framePaths = candidates.filter {
                operations.contains(CCAPIOperation(method: .get, path: $0))
            }
            guard !framePaths.isEmpty else { continue }
            let delete = CCAPIOperation(method: .delete, path: start.path)
            return CCAPIJPEGLiveViewOperations(
                start: start,
                stop: operations.contains(delete) ? delete : start,
                framePaths: framePaths
            )
        }
        return nil
    }

    private func multipartLiveViewOperations() -> CCAPIMultipartOperations? {
        let reads = operations
            .filter { $0.method == .get && $0.path.hasSuffix("/shooting/liveview/multipart") }
            .sorted { Self.pathVersion($0.path) > Self.pathVersion($1.path) }
        for read in reads {
            let prefix = String(read.path.dropLast("/shooting/liveview/multipart".count))
            let start = CCAPIOperation(method: .post, path: "\(prefix)/shooting/liveview")
            guard operations.contains(start) else { continue }
            let delete = CCAPIOperation(method: .delete, path: start.path)
            let result = CCAPIMultipartOperations(
                startLiveView: start,
                stopLiveView: operations.contains(delete) ? delete : start,
                openStream: read,
                closeStream: CCAPIOperation(method: .delete, path: read.path)
            )
            if operations.contains(result.startLiveView),
               operations.contains(result.closeStream) {
                return result
            }
        }
        return nil
    }

    private func supportsMultipartLiveView() -> Bool {
        multipartLiveViewOperations() != nil
    }

    private func supportsRTPLiveView() -> Bool {
        supports(.get, suffix: "/shooting/liveview/rtpsessiondesc") &&
            supports(.post, suffix: "/shooting/liveview/rtp") &&
            !(rtpDestinationAddress?.isEmpty ?? true) &&
            rtpSessionFactory != nil
    }

    private func ccapiLiveViewSources() -> [LiveViewSource] {
        var sources: [LiveViewSource] = []
        if supportsRTPLiveView() { sources.append(.ccapiRTP) }
        if supportsMultipartLiveView() { sources.append(.ccapiMultipart) }
        if supportsCompleteLiveView() { sources.append(.ccapiJPEGPolling) }
        return sources
    }

    private func startRTPLiveView(_ request: LiveViewRequest) async throws {
        guard supportsRTPLiveView(),
              let rtpDestinationAddress,
              let rtpSessionFactory else {
            throw CCAPIError.unsupported(.liveViewRTP)
        }
        let descriptionPath = apiPath(.get, suffix: "/shooting/liveview/rtpsessiondesc")
        let controlPath = apiPath(.post, suffix: "/shooting/liveview/rtp")
        let text = try await requestText(path: descriptionPath, maximumBytes: Self.maximumRTPSessionDescriptionBytes)
        let description = try CCAPIRTPSessionDescriptionParser.parse(text)
        let session = try await rtpSessionFactory.makeSession(
            description: description,
            destinationAddress: rtpDestinationAddress
        )
        await session.setTargetFPS(request.fps)
        do {
            try await session.start()
            try await requestOK(
                path: controlPath,
                method: .post,
                json: ["action": "start", "ipaddress": rtpDestinationAddress]
            )
        } catch {
            await session.close()
            try? await requestOK(
                path: controlPath,
                method: .post,
                json: ["action": "stop", "ipaddress": ""]
            )
            throw error
        }
        if let existing = rtpSession { await existing.close() }
        rtpSession = session
        activeLiveViewSource = .ccapiRTP
        observedFeatures.formUnion([.liveView, .liveViewRTP])
    }

    private func stopRTPLiveView() async {
        if !enforceAdvertisedOperations || supports(.post, suffix: "/shooting/liveview/rtp") {
            try? await requestOK(
                path: apiPath(.post, suffix: "/shooting/liveview/rtp"),
                method: .post,
                json: ["action": "stop", "ipaddress": ""]
            )
        }
        if let session = rtpSession { await session.close() }
        rtpSession = nil
    }

    private func stopJPEGLiveView() async {
        guard let operations = jpegLiveViewOperations() else { return }
        if operations.stop.method == .delete {
            try? await requestOK(path: operations.stop.path, method: .delete)
        } else {
            try? await requestOK(
                path: operations.stop.path,
                method: .post,
                json: ["liveviewsize": "off", "cameradisplay": "on"]
            )
        }
    }

    private func shouldDowngradeLiveViewSize(for error: Error) -> Bool {
        guard activeLiveViewSource == .ccapiJPEGPolling,
              !liveViewSizeFallbackAttempted,
              activeLiveViewSize != .small,
              requestedLiveViewRequest?.source == .auto || requestedLiveViewRequest?.source == .ccapiJPEGPolling,
              case let CCAPIError.http(statusCode, _, _, body) = error,
              statusCode == 503 else { return false }
        return body.range(of: "mode not supported", options: .caseInsensitive) != nil
    }

    private func shouldRetryBusyLiveViewFrame(_ error: Error) -> Bool {
        guard case let CCAPIError.http(statusCode, _, _, body) = error,
              statusCode == 503 else { return false }
        return body.range(of: "device busy", options: .caseInsensitive) != nil
    }

    private func retryJPEGLiveViewAtSmallSize(cacheKey: Int64) async throws -> LiveViewFrame {
        liveViewSizeFallbackAttempted = true
        let requestedFPS = requestedLiveViewRequest?.fps ?? 1
        rejectedLiveViewSizes.insert(activeLiveViewSize)
        await stopLiveView()
        try await startJPEGLiveView(
            LiveViewRequest(fps: requestedFPS, size: .small, source: .ccapiJPEGPolling)
        )
        return try await liveViewFrame(cacheKey: cacheKey)
    }

    private func stopMultipartLiveView() async {
        multipartSession?.close()
        multipartSession = nil
        pendingMultipartFrame = nil
        guard let operations = multipartLiveViewOperations() else { return }
        // Closing the reader is best-effort: Canon firmware may return 503 after
        // the multipart stream has already ended. The general Live View stop is
        // the authoritative cleanup and must still be sent.
        try? await requestOK(
            path: operations.closeStream.path,
            method: .delete,
            expectedStatusCode: 200
        )
        await stopMultipartGeneralLiveView(operations)
    }

    private func stopMultipartGeneralLiveView(_ operations: CCAPIMultipartOperations) async {
        if operations.stopLiveView.method == .delete {
            try? await requestOK(path: operations.stopLiveView.path, method: .delete)
        } else {
            try? await requestOK(
                path: operations.stopLiveView.path,
                method: .post,
                json: ["liveviewsize": "off", "cameradisplay": "on"]
            )
        }
    }

    private func supportsMediaDelete() -> Bool {
        operations.contains {
            $0.method == .delete && ($0.path.hasSuffix("/contents") || $0.path.contains("/contents/"))
        }
    }

    private func supportsMediaModify() -> Bool {
        operations.contains {
            $0.method == .put && ($0.path.hasSuffix("/contents") || $0.path.contains("/contents/"))
        }
    }

    private func advertisedPaths(_ method: HTTPMethod, suffix: String) -> [String] {
        apiVersionPrefixes.compactMap { prefix in
            operations.first {
                $0.method == method && $0.path.hasPrefix(prefix) && $0.path.hasSuffix(suffix)
            }?.path
        }.removingDuplicates()
    }

    private func capabilityEvidence() -> CameraCapabilityEvidence {
        let protocolVersions = apiVersionPrefixes.map {
            ($0 as NSString).lastPathComponent
                .replacingOccurrences(of: "\r", with: "")
                .replacingOccurrences(of: "\n", with: "")
        }.removingDuplicates()
        let commands = operations.map { operation in
            let path = operation.path
                .components(separatedBy: "?")[0]
                .replacingOccurrences(of: "\r", with: "")
                .replacingOccurrences(of: "\n", with: "")
            return String(
                "\(operation.method.rawValue) \(path)".prefix(Self.maximumCapabilityEvidenceItemCharacters)
            )
        }.removingDuplicates().sorted()
        let writableSettings = Set(settingPaths.keys)
            .union(cameraSleepPath == nil ? Set<String>() : Set([Self.autoPowerOffSettingKey]))
            .union(
                cachedFileNaming == nil
                    ? Set<String>()
                    : Set(Self.fileNamingEndpoints.map { $0.field.rawValue })
            )
            .map {
            String(
                $0.replacingOccurrences(of: "\r", with: "")
                    .replacingOccurrences(of: "\n", with: "")
                    .prefix(Self.maximumCapabilityEvidenceItemCharacters)
            )
        }.removingDuplicates().sorted()
        return CameraCapabilityEvidence(
            source: String(
                discoverySource
                    .replacingOccurrences(of: "\r", with: "")
                    .replacingOccurrences(of: "\n", with: "")
                    .prefix(Self.maximumCapabilityEvidenceItemCharacters)
            ),
            protocolVersions: Array(protocolVersions.prefix(Self.maximumCapabilityEvidenceItems)),
            advertisedCommands: Array(commands.prefix(Self.maximumCapabilityEvidenceItems)),
            writableSettings: Array(writableSettings.prefix(Self.maximumCapabilityEvidenceItems)),
            observedFeatures: observedFeatures,
            discoveryTrace: discoveryTrace,
            truncated: protocolVersions.count > Self.maximumCapabilityEvidenceItems ||
                commands.count > Self.maximumCapabilityEvidenceItems ||
                writableSettings.count > Self.maximumCapabilityEvidenceItems ||
                observedFeatures.count > Self.maximumCapabilityEvidenceItems ||
                discoveryTraceTruncated || discoveryTrace.contains { $0.truncated }
        )
    }

    private func apiPath(_ method: HTTPMethod, suffix: String) -> String {
        let matching = operations.filter { $0.method == method && $0.path.hasSuffix(suffix) }
        return matching.first { $0.path.hasPrefix(preferredVersionPrefix) }?.path
            ?? matching.max { Self.pathVersion($0.path) < Self.pathVersion($1.path) }?.path
            ?? "\(preferredVersionPrefix)\(suffix)"
    }

    private func loadShootingSettings() async throws -> JSONDictionary? {
        settingPaths.removeAll()
        liveViewMagnifications.removeAll()
        currentLiveViewMagnification = nil
        observedFeatures.remove(.liveViewMagnification)
        cameraSleepPath = nil
        observedFeatures.remove(.cardSelectionControl)
        observedFeatures.remove(.soundRecordingControl)
        observedFeatures.remove(.soundRecordingLevelControl)
        observedFeatures.remove(.focusBracketingControl)
        observedFeatures.remove(.movieSettingsControl)
        observedFeatures.remove(.directoryControl)
        settingsLoaded = false
        var merged: JSONDictionary = [:]
        let paths = enforceAdvertisedOperations
            ? advertisedPaths(.get, suffix: "/shooting/settings")
            : versionedPaths("/shooting/settings")
        for path in paths {
            guard let value = try await firstJSON(paths: [path], required: false) else { continue }
            let prefix = String(path.dropLast("/shooting/settings".count))
            for (key, setting) in value {
                let settingPath = "\(prefix)/shooting/settings/\(key)"
                if !Self.soundRecordingSettingKeys.contains(key),
                   !Self.soundRecordingLevelSettingKeys.contains(key),
                   key != Self.liveViewMagnificationSettingKey,
                   !Self.deviceFunctionSettingKeys.contains(key),
                   !Self.focusBracketingSettingKeys.contains(key),
                   !Self.movieSettingKeys.contains(key),
                   !enforceAdvertisedOperations || operations.contains(CCAPIOperation(method: .put, path: settingPath)) {
                    settingPaths[key] = settingPaths[key] ?? settingPath
                }
                if merged[key] == nil { merged[key] = setting }
            }
        }
        if let operations = liveViewMagnificationOperations(),
           let raw = try await firstJSON(paths: [operations.read.path], required: false),
           let setting = Self.validatedLiveViewMagnificationSetting(raw) {
            settingPaths[Self.liveViewMagnificationSettingKey] = operations.write.path
            liveViewMagnifications = setting.magnifications
            currentLiveViewMagnification = setting.current
            observedFeatures.insert(.liveViewMagnification)
        }
        if let operations = zoomOperations(),
           let raw = try await firstJSON(paths: [operations.read.path], required: false),
           let zoom = Self.validatedZoomSetting(raw) {
            settingPaths[Self.zoomSettingKey] = operations.write.path
            merged[Self.zoomSettingKey] = zoom
        }
        if let operations = movieModeOperations(),
           let raw = try await firstJSON(paths: [operations.read.path], required: false),
           let movieMode = Self.validatedMovieModeSetting(raw) {
            settingPaths[Self.movieModeSettingKey] = operations.write.path
            merged[Self.movieModeSettingKey] = movieMode
        }
        for (key, suffix) in Self.cardSelectionEndpoints {
            guard let operations = cardSelectionOperations(suffix: suffix),
                  let raw = try await firstJSON(paths: [operations.read.path], required: false),
                  let cardSelection = Self.validatedCardSelectionSetting(raw) else { continue }
            settingPaths[key] = operations.write.path
            merged[key] = cardSelection
            observedFeatures.insert(.cardSelectionControl)
        }
        for endpoint in Self.deviceFunctionSettingEndpoints {
            guard let operations = readWriteSettingOperations(suffix: endpoint.suffix),
                  let raw = try await firstJSON(paths: [operations.read.path], required: false),
                  let setting = Self.validatedStringAbilitySetting(raw, allowedValues: endpoint.values) else {
                continue
            }
            let ability = setting.array("ability")?.strings ?? []
            if endpoint.key == Self.autoPowerOffSettingKey,
               ability.contains(Self.autoPowerOffImmediately) {
                cameraSleepPath = operations.write.path
            }
            let settingValues = ability.filter {
                endpoint.values.subtracting(Set([Self.autoPowerOffImmediately])).contains($0)
            }
            let current = setting.string("value")
            if settingValues.count >= 2, settingValues.contains(current) {
                settingPaths[endpoint.key] = operations.write.path
                merged[endpoint.key] = ["value": current, "ability": settingValues]
            }
        }
        for endpoint in Self.soundRecordingEndpoints {
            guard let operations = soundRecordingOperations(suffix: endpoint.suffix),
                  let raw = try await firstJSON(paths: [operations.read.path], required: false),
                  let setting = Self.validatedStringAbilitySetting(raw, allowedValues: endpoint.values) else {
                continue
            }
            settingPaths[endpoint.key] = operations.write.path
            merged[endpoint.key] = setting
            observedFeatures.insert(.soundRecordingControl)
        }
        for endpoint in Self.soundRecordingLevelEndpoints {
            if let operations = soundRecordingLevelOperations(suffix: endpoint.suffix),
               let raw = try await firstJSON(paths: [operations.read.path], required: false),
               let soundRecordingLevel = Self.validatedIntegerRangeSetting(raw) {
                settingPaths[endpoint.key] = operations.write.path
                merged[endpoint.key] = soundRecordingLevel
                observedFeatures.insert(.soundRecordingLevelControl)
            }
        }
        var focusBracketingAvailable = false
        for endpoint in Self.focusBracketingStringEndpoints {
            if endpoint.key != Self.focusBracketingSettingKey, !focusBracketingAvailable { continue }
            guard let operations = readWriteSettingOperations(suffix: endpoint.suffix),
                  let raw = try await firstJSON(paths: [operations.read.path], required: false),
                  let setting = Self.validatedStringAbilitySetting(raw, allowedValues: endpoint.values) else {
                continue
            }
            settingPaths[endpoint.key] = operations.write.path
            merged[endpoint.key] = setting
            if endpoint.key == Self.focusBracketingSettingKey {
                focusBracketingAvailable = true
                observedFeatures.insert(.focusBracketingControl)
            }
        }
        if focusBracketingAvailable {
            for endpoint in Self.focusBracketingIntegerEndpoints {
                guard let operations = readWriteSettingOperations(suffix: endpoint.suffix),
                      let raw = try await firstJSON(paths: [operations.read.path], required: false),
                      let setting = Self.validatedIntegerRangeSetting(
                          raw,
                          maximumOptions: Self.maximumFocusBracketingOptions
                      ) else { continue }
                settingPaths[endpoint.key] = operations.write.path
                merged[endpoint.key] = setting
            }
        }
        for endpoint in Self.movieSettingEndpoints {
            guard let operations = readWriteSettingOperations(suffix: endpoint.suffix),
                  let raw = try await firstJSON(paths: [operations.read.path], required: false),
                  let setting = Self.validatedStringAbilitySetting(raw, allowedValues: endpoint.values) else {
                continue
            }
            settingPaths[endpoint.key] = operations.write.path
            merged[endpoint.key] = setting
            observedFeatures.insert(.movieSettingsControl)
        }
        if let operations = directoryOperations(),
           let raw = try await firstJSON(paths: [operations.read.path], required: false),
           let selection = Self.validatedDirectorySelectionSetting(raw) {
            settingPaths[Self.directorySelectionSettingKey] = operations.write.path
            merged[Self.directorySelectionSettingKey] = selection
            observedFeatures.insert(.directoryControl)
        }
        settingsLoaded = true
        cachedSettings = merged.isEmpty ? nil : merged
        return cachedSettings
    }

    private func loadFileNaming(force: Bool = false) async throws -> CameraFileNaming? {
        if fileNamingLoaded, !force { return cachedFileNaming }
        observedFeatures.remove(.fileNamingControl)
        guard let operations = fileNamingOperations() else {
            cachedFileNaming = nil
            fileNamingLoaded = true
            return nil
        }
        var responses: [CameraFileNamingField: JSONDictionary] = [:]
        for endpoint in Self.fileNamingEndpoints {
            guard let operation = operations[endpoint.field],
                  let response = try await firstJSON(paths: [operation.read.path], required: false) else {
                cachedFileNaming = nil
                fileNamingLoaded = true
                return nil
            }
            responses[endpoint.field] = response
        }
        let state = Self.validatedCanonicalFileNaming(responses)
        cachedFileNaming = state
        fileNamingLoaded = true
        if state != nil { observedFeatures.insert(.fileNamingControl) }
        return state
    }

    private func cachedOrLoadShootingSettings() async throws -> JSONDictionary? {
        if let cachedSettings { return cachedSettings }
        return try await loadShootingSettings()
    }

    private func cameraSettings(_ value: JSONDictionary?) -> [CameraSetting] {
        guard let value else { return [] }
        var controls: [CameraSetting] = []
        let canonical: [(String, [String], String)] = [
            ("iso", ["iso"], "ISO"),
            ("shutter", ["shutter", "shutterspeed", "tv"], "Shutter speed"),
            ("aperture", ["aperture", "av"], "Aperture"),
            ("whitebalance", ["whitebalance", "white_balance", "wb"], "White balance"),
        ]
        for (key, aliases, label) in canonical {
            guard let writableAlias = aliases.first(where: { settingPaths[$0] != nil }) else { continue }
            if let setting = value.object(writableAlias), let control = control(key, label, setting) {
                controls.append(control)
            }
        }
        for key in value.keys.sorted() where !Self.allPrimaryAliases.contains(key) && settingPaths[key] != nil {
            guard let setting = value.object(key) else {
                continue
            }
            if key == Self.imageQualitySettingKey {
                controls.append(contentsOf: structuredImageQualityControls(setting))
            } else if key == Self.wbShiftSettingKey {
                controls.append(contentsOf: structuredWBShiftControls(setting))
            } else if key == Self.zoomSettingKey {
                if let control = zoomControl(setting) { controls.append(control) }
            } else if let settingControl = control(key, Self.settingLabel(key), setting) {
                controls.append(settingControl)
            }
        }
        return controls
    }

    private func structuredImageQualityControls(_ setting: JSONDictionary) -> [CameraSetting] {
        guard let current = setting.object("value"), let ability = setting.object("ability") else { return [] }
        return Self.imageQualityFields.compactMap { field in
            let values = (ability.array(field)?.strings ?? []).removingDuplicates()
            let value = current.string(field)
            guard !value.isEmpty, values.count >= 2 else { return nil }
            let key = "\(Self.imageQualitySettingKey).\(field)"
            return CameraSetting(key: key, label: Self.settingLabel(key), value: value, values: values)
        }
    }

    private func structuredWBShiftControls(_ setting: JSONDictionary) -> [CameraSetting] {
        guard let current = setting.object("value"), let ability = setting.object("ability") else { return [] }
        let currentValues = Dictionary(uniqueKeysWithValues: Self.wbShiftFields.compactMap { field in
            Self.strictInteger(current[field]).map { (field, $0) }
        })
        guard currentValues.count == Self.wbShiftFields.count else { return [] }
        return Self.wbShiftFields.compactMap { field in
            let values = Self.boundedIntegerRangeValues(ability.object(field))
            guard let currentValue = currentValues[field] else { return nil }
            let value = String(currentValue)
            guard values.count >= 2, values.contains(value) else { return nil }
            let key = "\(Self.wbShiftSettingKey).\(field)"
            return CameraSetting(key: key, label: Self.settingLabel(key), value: value, values: values)
        }
    }

    private static func boundedIntegerRangeValues(
        _ range: JSONDictionary?,
        maximumOptions: Int = maximumStructuredSettingOptions
    ) -> [String] {
        guard let range,
              let minimum = strictInteger(range["min"]),
              let maximum = strictInteger(range["max"]),
              let step = strictInteger(range["step"]),
              step > 0,
              minimum <= maximum else { return [] }
        let (distance, overflow) = maximum.subtractingReportingOverflow(minimum)
        guard !overflow else { return [] }
        let (count, countOverflow) = (distance / step).addingReportingOverflow(1)
        guard !countOverflow, count >= 1, count <= maximumOptions else { return [] }
        return (0..<count).map { String(minimum + ($0 * step)) }
    }

    private static func validatedZoomSetting(_ value: JSONDictionary) -> JSONDictionary? {
        validatedIntegerRangeSetting(value)
    }

    private static func validatedLiveViewMagnificationSetting(
        _ value: JSONDictionary
    ) -> (magnifications: [LiveViewMagnification], current: LiveViewMagnification)? {
        guard let currentValue = value["value"] as? String,
              let rawAbility = value["ability"] as? [Any] else { return nil }
        let ability = rawAbility.compactMap { $0 as? String }
        guard ability.count == rawAbility.count,
              ability.count >= 2,
              Set(ability).count == ability.count,
              ability.allSatisfy(liveViewMagnificationValues.contains),
              ability.contains("1"),
              ability.contains(currentValue),
              let current = LiveViewMagnification(rawValue: Int(currentValue) ?? 0) else {
            return nil
        }
        let magnifications = ability.compactMap { LiveViewMagnification(rawValue: Int($0) ?? 0) }
        guard magnifications.count == ability.count else { return nil }
        return (magnifications, current)
    }

    private static func validatedIntegerRangeSetting(
        _ value: JSONDictionary,
        maximumOptions: Int = maximumStructuredSettingOptions
    ) -> JSONDictionary? {
        guard let current = strictInteger(value["value"]),
              let ability = value.object("ability") else { return nil }
        let values = boundedIntegerRangeValues(ability, maximumOptions: maximumOptions)
        let currentValue = String(current)
        guard values.count >= 2, values.contains(currentValue) else { return nil }
        return ["value": currentValue, "ability": values]
    }

    private static func validatedMovieModeSetting(_ value: JSONDictionary) -> JSONDictionary? {
        let status = value.string("status", default: value.string("value"))
        guard movieModeValues.contains(status) else { return nil }
        return ["value": status, "ability": movieModeValues]
    }

    private static func validatedCardSelectionSetting(_ value: JSONDictionary) -> JSONDictionary? {
        guard let current = value["value"] as? String,
              let rawAbility = value["ability"] as? [Any] else { return nil }
        let values = rawAbility.compactMap { $0 as? String }
        guard values.count == rawAbility.count,
              values.count >= 2,
              Set(values).count == values.count,
              values.allSatisfy({ cardSelectionValues.contains($0) }),
              cardSelectionValues.contains(current),
              values.contains(current) else { return nil }
        return ["value": current, "ability": values]
    }

    private static func validatedDirectorySelectionSetting(_ value: JSONDictionary) -> JSONDictionary? {
        guard let current = value["value"] as? String,
              let rawAbility = value["ability"] as? [Any] else { return nil }
        let values = rawAbility.compactMap { $0 as? String }
        guard values.count == rawAbility.count,
              !values.isEmpty,
              values.count <= maximumStringSettingOptions,
              Set(values).count == values.count,
              values.allSatisfy(isValidDirectorySelection),
              values.contains(current) else { return nil }
        return ["value": current, "ability": values]
    }

    private static func validatedFileRange(
        _ value: JSONDictionary,
        maximumAllowed: Int
    ) -> (value: Int, range: CameraIntegerRange)? {
        guard let current = strictInteger(value["value"]),
              let ability = value.object("ability"),
              let minimum = strictInteger(ability["min"]),
              let maximum = strictInteger(ability["max"]),
              let step = strictInteger(ability["step"]),
              minimum >= 1,
              maximum <= maximumAllowed,
              minimum <= maximum,
              step > 0 else { return nil }
        let range = CameraIntegerRange(minimum: minimum, maximum: maximum, step: step)
        guard range.accepts(String(current)) else { return nil }
        return (current, range)
    }

    private static func validatedBridgeRange(
        _ value: JSONDictionary,
        maximumAllowed: Int
    ) -> CameraIntegerRange? {
        guard let minimum = strictInteger(value["minimum"]),
              let maximum = strictInteger(value["maximum"]),
              let step = strictInteger(value["step"]),
              minimum >= 1,
              maximum <= maximumAllowed,
              minimum <= maximum,
              step > 0 else { return nil }
        return CameraIntegerRange(minimum: minimum, maximum: maximum, step: step)
    }

    private static func validatedBridgeFileNaming(_ value: JSONDictionary) -> CameraFileNaming? {
        guard let mode = value["stillFilenameMode"] as? String,
              let rawOptions = value["stillFilenameModeOptions"] as? [Any],
              let stillUserSetting1 = value["stillUserSetting1"] as? String,
              let stillUserSetting2 = value["stillUserSetting2"] as? String,
              let movieIndex = value["movieIndex"] as? String,
              let movieReelNumber = strictInteger(value["movieReelNumber"]),
              let movieReelRangeValue = value.object("movieReelRange"),
              let movieReelRange = validatedBridgeRange(movieReelRangeValue, maximumAllowed: 9999),
              let movieClipNumber = strictInteger(value["movieClipNumber"]),
              let movieClipRangeValue = value.object("movieClipRange"),
              let movieClipRange = validatedBridgeRange(movieClipRangeValue, maximumAllowed: 999),
              let movieUserDefined = value["movieUserDefined"] as? String else { return nil }
        let options = rawOptions.compactMap { $0 as? String }
        guard options.count == rawOptions.count,
              !options.isEmpty,
              options.count <= stillFilenameModes.count,
              Set(options).count == options.count,
              options.allSatisfy(stillFilenameModes.contains),
              options.contains(mode) else { return nil }
        let result = CameraFileNaming(
            stillFilenameMode: mode,
            stillFilenameModeOptions: options,
            stillUserSetting1: stillUserSetting1,
            stillUserSetting2: stillUserSetting2,
            movieIndex: movieIndex,
            movieReelNumber: movieReelNumber,
            movieReelRange: movieReelRange,
            movieClipNumber: movieClipNumber,
            movieClipRange: movieClipRange,
            movieUserDefined: movieUserDefined
        )
        return CameraFileNamingField.allCases.allSatisfy {
            result.accepts($0, value: result.value(for: $0))
        } ? result : nil
    }

    private static func validatedCanonicalFileNaming(
        _ values: [CameraFileNamingField: JSONDictionary]
    ) -> CameraFileNaming? {
        guard values.count == fileNamingEndpoints.count,
              let modeValue = values[.stillFilenameMode],
              let mode = modeValue["value"] as? String,
              let rawOptions = modeValue["ability"] as? [Any],
              let stillUserSetting1 = values[.stillUserSetting1]?["usersetting1"] as? String,
              let stillUserSetting2 = values[.stillUserSetting2]?["usersetting2"] as? String,
              let movieIndex = values[.movieIndex]?["index"] as? String,
              let reelValue = values[.movieReelNumber],
              let reel = validatedFileRange(reelValue, maximumAllowed: 9999),
              let clipValue = values[.movieClipNumber],
              let clip = validatedFileRange(clipValue, maximumAllowed: 999),
              let movieUserDefined = values[.movieUserDefined]?["userdefined"] as? String else { return nil }
        let options = rawOptions.compactMap { $0 as? String }
        guard options.count == rawOptions.count,
              !options.isEmpty,
              options.count <= stillFilenameModes.count,
              Set(options).count == options.count,
              options.allSatisfy(stillFilenameModes.contains),
              options.contains(mode) else { return nil }
        let result = CameraFileNaming(
            stillFilenameMode: mode,
            stillFilenameModeOptions: options,
            stillUserSetting1: stillUserSetting1,
            stillUserSetting2: stillUserSetting2,
            movieIndex: movieIndex,
            movieReelNumber: reel.value,
            movieReelRange: reel.range,
            movieClipNumber: clip.value,
            movieClipRange: clip.range,
            movieUserDefined: movieUserDefined
        )
        return CameraFileNamingField.allCases.allSatisfy {
            result.accepts($0, value: result.value(for: $0))
        } ? result : nil
    }

    private static func isValidDirectoryCreateName(_ value: String) -> Bool {
        value.range(of: #"^(?:[A-Z0-9_]{5})?$"#, options: .regularExpression) != nil
    }

    private static func isValidCreatedDirectoryName(_ value: String) -> Bool {
        value.range(of: #"^[A-Z0-9_]{5}$"#, options: .regularExpression) != nil
    }

    private static func isValidDirectorySelection(_ value: String) -> Bool {
        value.range(of: #"^[0-9]{3}[A-Z0-9_]{5}$"#, options: .regularExpression) != nil
    }

    private static func validatedStringAbilitySetting(
        _ value: JSONDictionary,
        allowedValues: Set<String>?
    ) -> JSONDictionary? {
        guard let current = value["value"] as? String,
              let rawAbility = value["ability"] as? [Any] else { return nil }
        let values = rawAbility.compactMap { $0 as? String }
        guard values.count == rawAbility.count,
              values.count >= 2,
              values.count <= maximumStringSettingOptions,
              Set(values).count == values.count,
              values.allSatisfy({ item in
                  !item.isEmpty &&
                      item.count <= maximumStringSettingValueLength &&
                      (allowedValues?.contains(item) ?? true)
              }),
              values.contains(current) else { return nil }
        return ["value": current, "ability": values]
    }

    private func zoomControl(_ value: JSONDictionary) -> CameraSetting? {
        let values = value.array("ability")?.strings ?? []
        let current = value.string("value")
        guard values.count >= 2, values.contains(current) else { return nil }
        return CameraSetting(key: Self.zoomSettingKey, label: "Zoom", value: current, values: values)
    }

    private static func strictInteger(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              ["c", "s", "i", "l", "q", "C", "S", "I", "L", "Q"].contains(
                  String(cString: number.objCType)
              ) else { return nil }
        return Int(number.stringValue)
    }

    private func control(_ key: String, _ label: String, _ value: JSONDictionary) -> CameraSetting? {
        let options = (value.array("ability")?.strings ?? []).removingDuplicates()
        let current = value.string("value")
        guard !current.isEmpty, !options.isEmpty else { return nil }
        return CameraSetting(key: key, label: label, value: current, values: options)
    }

    private func putSettingValue(candidateKeys: [String], value: String) async throws {
        if !settingsLoaded { _ = try await loadShootingSettings() }
        let paths = candidateKeys.compactMap { settingPaths[$0] }.removingDuplicates()
        guard !paths.isEmpty else {
            throw CCAPIError.invalidResponse(
                "Camera did not advertise a writable setting for \(candidateKeys.joined(separator: ", "))."
            )
        }
        var failures: [String] = []
        for path in paths {
            do {
                try await requestOK(path: path, method: .put, json: ["value": value])
                return
            } catch {
                if error is CancellationError { throw error }
                failures.append("\(path): \(error.localizedDescription)")
            }
        }
        throw CCAPIError.invalidResponse(
            "Setting write failed for '\(value)'.\n" + failures.map { "- \($0)" }.joined(separator: "\n")
        )
    }

    private func putStructuredSettingValue(
        settings: JSONDictionary?,
        baseKey: String,
        field: String,
        value: String
    ) async throws {
        guard let path = settingPaths[baseKey],
              let setting = settings?.object(baseKey),
              let current = setting.object("value") else {
            throw CCAPIError.invalidSetting(key: "\(baseKey).\(field)", value: value)
        }
        var updated = current
        if baseKey == Self.wbShiftSettingKey {
            guard Self.wbShiftFields.allSatisfy({ Self.strictInteger(current[$0]) != nil }),
                  let integerValue = Int(value) else {
                throw CCAPIError.invalidSetting(key: "\(baseKey).\(field)", value: value)
            }
            updated[field] = integerValue
        } else {
            updated[field] = value
        }
        if baseKey == Self.imageQualitySettingKey {
            let activeFormats = Self.imageQualityFields.compactMap { field -> String? in
                guard let value = updated[field] as? String, !value.isEmpty else { return nil }
                return value
            }
            guard activeFormats.isEmpty || !activeFormats.allSatisfy({
                $0.caseInsensitiveCompare("none") == .orderedSame
            }) else {
                throw CCAPIError.invalidSetting(key: "\(baseKey).\(field)", value: value)
            }
        }
        try await requestOK(path: path, method: .put, json: ["value": updated])
    }

    private func structuredSettingParts(_ key: String) -> (baseKey: String, field: String)? {
        for (baseKey, fields) in [
            (Self.imageQualitySettingKey, Self.imageQualityFields),
            (Self.wbShiftSettingKey, Self.wbShiftFields),
        ] {
            let prefix = "\(baseKey)."
            if key.hasPrefix(prefix) {
                let field = String(key.dropFirst(prefix.count))
                return fields.contains(field) ? (baseKey, field) : nil
            }
        }
        return nil
    }

    private func aliases(for key: String) -> [String] {
        switch key {
        case "shutter": ["tv", "shutterspeed", "shutter"]
        case "aperture": ["av", "aperture"]
        case "whitebalance": ["wb", "whitebalance", "white_balance"]
        default: [key]
        }
    }

    private func featureForSetting(_ key: String) -> CameraFeature {
        switch key.lowercased() {
        case "iso", "shutter", "aperture": .exposureControl
        case "whitebalance": .whiteBalanceControl
        case Self.movieModeSettingKey: .movieModeControl
        case Self.zoomSettingKey: .zoomControl
        case Self.stillCardSelectionSettingKey, Self.movieCardSelectionSettingKey: .cardSelectionControl
        case let soundKey where Self.soundRecordingSettingKeys.contains(soundKey): .soundRecordingControl
        case let soundLevelKey where Self.soundRecordingLevelSettingKeys.contains(soundLevelKey):
            .soundRecordingLevelControl
        case let focusKey where Self.focusBracketingSettingKeys.contains(focusKey): .focusBracketingControl
        case let movieKey where Self.movieSettingKeys.contains(movieKey): .movieSettingsControl
        case Self.directorySelectionSettingKey: .directoryControl
        default: .advancedSettings
        }
    }

    private func requireTemperatureAllowsLiveView() throws {
        if latestTemperatureStatus?.liveViewAllowed == false {
            throw CCAPIError.temperatureRestriction(.liveView)
        }
    }

    private func refreshTemperatureStatusForRestrictedCommand() async throws {
        guard resolvedMode != .simulator,
              let operation = operation(.get, suffix: "/devicestatus/temperature") else { return }
        if let refreshedTemperature = parseTemperature(
            try await firstJSON(paths: [operation.path], required: false)
        ) {
            latestTemperatureStatus = refreshedTemperature
            observedFeatures.insert(.temperatureStatus)
        }
    }

    private func requireTemperatureAllowsStillCapture() throws {
        if latestTemperatureStatus?.stillCaptureAllowed == false {
            throw CCAPIError.temperatureRestriction(.stillCapture)
        }
    }

    private func requireTemperatureAllowsMovieRecording() throws {
        if latestTemperatureStatus?.movieRecordingAllowed == false {
            throw CCAPIError.temperatureRestriction(.videoRecording)
        }
    }

    private func setRecording(_ enabled: Bool) async throws -> CameraStatus {
        try await ensureInitialized()
        if enabled {
            try await refreshTemperatureStatusForRestrictedCommand()
            try requireTemperatureAllowsMovieRecording()
        }
        if resolvedMode == .simulator {
            _ = try await requestJSON(path: "/ccapi/record/\(enabled ? "start" : "stop")", method: .post, json: [:])
        } else {
            guard let operation = recordingOperation() else {
                throw CCAPIError.unsupported(.videoRecording)
            }
            try await commandOK(operation: operation, json: ["action": enabled ? "start" : "stop"])
        }
        recording = enabled
        observedFeatures.insert(.videoRecording)
        return try await status()
    }

    private func commandOK(operation: CCAPIOperation, json: JSONDictionary) async throws {
        try await requestOK(path: operation.path, method: operation.method, json: json)
    }

    private func performGuaranteedRelease(
        press: @escaping @Sendable () async throws -> Void,
        release: @escaping @Sendable () async throws -> Void,
        holdNanoseconds: UInt64? = nil
    ) async throws {
        var primaryFailure: (any Error)?
        do {
            try await press()
            if let holdNanoseconds { try await Task.sleep(nanoseconds: holdNanoseconds) }
        } catch {
            primaryFailure = error
        }

        var releaseFailure: (any Error)?
        do {
            try await Task.detached(priority: .userInitiated) { try await release() }.value
        } catch {
            releaseFailure = error
        }
        if let primaryFailure, let releaseFailure {
            throw CCAPIError.operationAndReleaseFailed(
                operation: primaryFailure.localizedDescription,
                release: releaseFailure.localizedDescription
            )
        }
        if let primaryFailure { throw primaryFailure }
        if let releaseFailure { throw releaseFailure }
    }

    private func simulatorCapabilities() async throws -> CameraCapabilities {
        let value = try await requestJSON(path: "/ccapi/capabilities")
        let fileNaming = value.object("fileNaming").flatMap(Self.validatedBridgeFileNaming)
        let simulatorMagnifications: [LiveViewMagnification] = {
            guard let liveView = value.object("liveView"),
                  let rawValues = liveView.array("magnifications") else { return [] }
            let values = rawValues.compactMap(Self.strictInteger)
            guard values.count == rawValues.count,
                  values.count >= 2,
                  Set(values).count == values.count,
                  values.contains(1),
                  values.allSatisfy({ LiveViewMagnification(rawValue: $0) != nil }) else { return [] }
            return values.compactMap(LiveViewMagnification.init(rawValue:))
        }()
        let simulatorCurrentMagnification = value.object("liveView")
            .flatMap { Self.strictInteger($0["currentMagnification"]) }
            .flatMap(LiveViewMagnification.init(rawValue:))
            .flatMap { simulatorMagnifications.contains($0) ? $0 : nil }
        liveViewMagnifications = simulatorMagnifications
        currentLiveViewMagnification = simulatorCurrentMagnification
        var controls = [
            CameraSetting(key: "iso", label: "ISO", value: "-", values: value.array("iso")?.strings ?? []),
            CameraSetting(key: "shutter", label: "Shutter speed", value: "-", values: value.array("shutter")?.strings ?? []),
            CameraSetting(key: "aperture", label: "Aperture", value: "-", values: value.array("aperture")?.strings ?? []),
            CameraSetting(
                key: "whitebalance",
                label: "White balance",
                value: "-",
                values: value.array("white_balance")?.strings ?? []
            ),
        ]
        if let raw = value.object(Self.zoomSettingKey),
           let normalized = Self.validatedZoomSetting(raw),
           let control = zoomControl(normalized) {
            controls.append(control)
        }
        if let raw = value.object(Self.movieModeSettingKey),
           let normalized = Self.validatedMovieModeSetting(raw),
           let control = control(Self.movieModeSettingKey, "Movie mode", normalized) {
            controls.append(control)
        }
        for key in [Self.stillCardSelectionSettingKey, Self.movieCardSelectionSettingKey] {
            guard let raw = value.object(key),
                  let normalized = Self.validatedCardSelectionSetting(raw),
                  let control = control(key, Self.settingLabel(key), normalized) else { continue }
            controls.append(control)
        }
        var simulatorCameraSleepSupported = false
        for endpoint in Self.deviceFunctionSettingEndpoints {
            guard let raw = value.object(endpoint.key),
                  let normalized = Self.validatedStringAbilitySetting(raw, allowedValues: endpoint.values) else {
                continue
            }
            let ability = normalized.array("ability")?.strings ?? []
            if endpoint.key == Self.autoPowerOffSettingKey,
               ability.contains(Self.autoPowerOffImmediately) {
                simulatorCameraSleepSupported = true
            }
            let settingValues = ability.filter {
                endpoint.values.subtracting(Set([Self.autoPowerOffImmediately])).contains($0)
            }
            let current = normalized.string("value")
            guard settingValues.count >= 2,
                  settingValues.contains(current),
                  let deviceControl = control(
                      endpoint.key,
                      Self.settingLabel(endpoint.key),
                      ["value": current, "ability": settingValues]
                  ) else { continue }
            controls.append(deviceControl)
        }
        for endpoint in Self.soundRecordingEndpoints {
            guard let raw = value.object(endpoint.key),
                  let normalized = Self.validatedStringAbilitySetting(raw, allowedValues: endpoint.values),
                  let control = control(endpoint.key, Self.settingLabel(endpoint.key), normalized) else { continue }
            controls.append(control)
        }
        for endpoint in Self.soundRecordingLevelEndpoints {
            guard let raw = value.object(endpoint.key),
                  let normalized = Self.validatedIntegerRangeSetting(raw),
                  let control = control(endpoint.key, Self.settingLabel(endpoint.key), normalized) else { continue }
            controls.append(control)
        }
        var focusBracketingAvailable = false
        for endpoint in Self.focusBracketingStringEndpoints {
            if endpoint.key != Self.focusBracketingSettingKey, !focusBracketingAvailable { continue }
            guard let raw = value.object(endpoint.key),
                  let normalized = Self.validatedStringAbilitySetting(raw, allowedValues: endpoint.values),
                  let control = control(endpoint.key, Self.settingLabel(endpoint.key), normalized) else { continue }
            controls.append(control)
            if endpoint.key == Self.focusBracketingSettingKey { focusBracketingAvailable = true }
        }
        if focusBracketingAvailable {
            for endpoint in Self.focusBracketingIntegerEndpoints {
                guard let raw = value.object(endpoint.key),
                      let normalized = Self.validatedIntegerRangeSetting(
                          raw,
                          maximumOptions: Self.maximumFocusBracketingOptions
                      ),
                      let control = control(endpoint.key, Self.settingLabel(endpoint.key), normalized) else { continue }
                controls.append(control)
            }
        }
        for endpoint in Self.movieSettingEndpoints {
            guard let raw = value.object(endpoint.key),
                  let normalized = Self.validatedStringAbilitySetting(raw, allowedValues: endpoint.values),
                  let control = control(endpoint.key, Self.settingLabel(endpoint.key), normalized) else { continue }
            controls.append(control)
        }
        if let raw = value.object(Self.directorySelectionSettingKey),
           let normalized = Self.validatedDirectorySelectionSetting(raw),
           let control = control(
               Self.directorySelectionSettingKey,
               Self.settingLabel(Self.directorySelectionSettingKey),
               normalized
           ) {
            controls.append(control)
        }
        var supported: Set<CameraFeature> = [
            .cameraIdentity, .batteryStatus, .storageStatus, .eventPolling, .liveView, .liveViewJPEGPolling,
            .stillCapture, .bulbExposure, .autofocus, .shutterHalfPress, .videoRecording, .tapFocus,
            .clickWhiteBalance, .focusDrive,
            .exposureControl, .whiteBalanceControl, .mediaBrowser, .mediaThumbnail, .mediaPreview, .mediaDownload,
            .mediaProtect, .mediaRating, .mediaRotate, .mediaArchive, .mediaDelete, .cameraClockSync,
            .recordableStatus, .lensStatus, .temperatureStatus,
        ]
        if simulatorCameraSleepSupported { supported.insert(.cameraSleep) }
        supported.insert(.sensorCleaning)
        if controls.contains(where: { $0.key == Self.zoomSettingKey }) {
            supported.insert(.zoomControl)
        }
        if controls.contains(where: { $0.key == Self.movieModeSettingKey }) {
            supported.insert(.movieModeControl)
        }
        if controls.contains(where: { Self.cardSelectionSettingKeys.contains($0.key) }) {
            supported.insert(.cardSelectionControl)
        }
        if controls.contains(where: { Self.soundRecordingSettingKeys.contains($0.key) }) {
            supported.insert(.soundRecordingControl)
        }
        if controls.contains(where: { $0.key == Self.soundRecordingLevelSettingKey }) {
            supported.insert(.soundRecordingLevelControl)
        }
        if controls.contains(where: { $0.key == Self.focusBracketingSettingKey }) {
            supported.insert(.focusBracketingControl)
        }
        if controls.contains(where: { Self.movieSettingKeys.contains($0.key) }) {
            supported.insert(.movieSettingsControl)
        }
        if controls.contains(where: { $0.key == Self.directorySelectionSettingKey }) {
            supported.insert(.directoryControl)
        }
        if fileNaming != nil { supported.insert(.fileNamingControl) }
        if controls.contains(where: { !Self.primarySettingKeys.contains($0.key) }) {
            supported.insert(.advancedSettings)
        }
        if simulatorMagnifications.count >= 2, simulatorCurrentMagnification != nil {
            supported.insert(.liveViewMagnification)
        }
        return CameraCapabilities(
            settings: controls,
            fileNaming: fileNaming,
            matrix: CapabilityMatrix(supported: supported, planned: [.liveViewRTP]),
            liveView: LiveViewCapabilities(
                sources: [.simulatorFrame],
                defaultSource: .simulatorFrame,
                sizes: [.medium],
                defaultSize: .medium,
                magnifications: simulatorMagnifications,
                currentMagnification: simulatorCurrentMagnification,
                maximumFPS: 2
            ),
            profile: CameraProfile.from(modelName: cachedModel),
            evidence: CameraCapabilityEvidence(
                source: discoverySource,
                observedFeatures: observedFeatures
            )
        )
    }

    private func parseSimulatorStatus(_ value: JSONDictionary) throws -> CameraStatus {
        guard let battery = value.object("battery"),
              let media = value.object("media"),
              let exposure = value.object("exposure") else {
            throw CCAPIError.invalidResponse("Simulator status omitted required objects.")
        }
        return CameraStatus(
            connected: value.bool("connected") ?? true,
            batteryLevel: battery.integer("level"),
            batteryStatus: battery.string("status", default: "unknown"),
            recording: value.bool("recording"),
            bulbExposureActive: value.bool("bulb_exposure_active") ?? value.bool("bulbExposureActive"),
            mode: value.string("mode", default: "unknown"),
            mediaAvailable: media.bool("available"),
            remainingMinutes: media.integer("remaining_minutes"),
            exposure: ExposureState(
                iso: exposure.string("iso", default: "-"),
                shutter: exposure.string("shutter", default: "-"),
                aperture: exposure.string("aperture", default: "-"),
                whiteBalance: exposure.string("white_balance", default: "-")
            ),
            storageTotalBytes: media.integer64("total_bytes") ?? media.integer64("totalBytes"),
            storageFreeBytes: media.integer64("free_bytes") ?? media.integer64("freeBytes"),
            storageFreeImages: media.integer64("free_images") ?? media.integer64("freeImages"),
            storageDeviceCount: media.integer("devices"),
            recordableShots: value.integer64("recordable_shots") ?? value.integer64("recordableShots"),
            remainingRecordingSeconds: value.integer64("remaining_recording_seconds")
                ?? value.integer64("remainingRecordingSeconds"),
            lens: value.object("lens").flatMap(parseBridgeLens),
            temperature: CameraTemperatureStatus(rawValue: value.string("temperature", default: ""))
        )
    }

    private func exposureState(_ settings: JSONDictionary?) -> ExposureState {
        ExposureState(
            iso: settingObject(in: settings, aliases: ["iso"])?.string("value", default: "-") ?? "-",
            shutter: settingObject(in: settings, aliases: ["shutter", "shutterspeed", "tv"])?
                .string("value", default: "-") ?? "-",
            aperture: settingObject(in: settings, aliases: ["aperture", "av"])?
                .string("value", default: "-") ?? "-",
            whiteBalance: settingObject(in: settings, aliases: ["whitebalance", "white_balance", "wb"])?
                .string("value", default: "-") ?? "-"
        )
    }

    private func settingObject(in settings: JSONDictionary?, aliases: [String]) -> JSONDictionary? {
        guard let settings else { return nil }
        return aliases.lazy.compactMap { settings.object($0) }.first
    }

    private func parseLens(_ value: JSONDictionary?) -> LensStatus? {
        guard let value,
              let mounted = value["mount"] as? Bool,
              let name = value["name"] as? String,
              validLensName(name, mounted: mounted) else {
            return nil
        }
        return LensStatus(mounted: mounted, name: mounted ? name : "")
    }

    private func parseRecordable(
        _ value: JSONDictionary?
    ) -> (shots: Int64?, remainingSeconds: Int64?)? {
        guard let value,
              value.keys.contains("recordableshots"),
              value.keys.contains("remainingtime") else {
            return nil
        }

        func nullableNonNegativeInteger(_ key: String) -> (valid: Bool, value: Int64?) {
            guard let raw = value[key] else { return (false, nil) }
            if raw is NSNull { return (true, nil) }
            guard let number = raw as? NSNumber,
                  CFGetTypeID(number) != CFBooleanGetTypeID() else {
                return (false, nil)
            }
            let integer = number.int64Value
            guard integer >= 0, number.doubleValue == Double(integer) else { return (false, nil) }
            return (true, integer)
        }

        let shots = nullableNonNegativeInteger("recordableshots")
        let remaining = nullableNonNegativeInteger("remainingtime")
        guard shots.valid, remaining.valid else { return nil }
        return (shots.value, remaining.value)
    }

    private func parseBridgeLens(_ value: JSONDictionary) -> LensStatus? {
        guard let mounted = value["mounted"] as? Bool,
              let name = value["name"] as? String,
              validLensName(name, mounted: mounted) else {
            return nil
        }
        return LensStatus(mounted: mounted, name: mounted ? name : "")
    }

    private func validLensName(_ name: String, mounted: Bool) -> Bool {
        name.count <= Self.maxDeviceStatusTextCharacters &&
            !name.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) } &&
            (!mounted || !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    private func parseTemperature(_ value: JSONDictionary?) -> CameraTemperatureStatus? {
        guard let rawValue = value?["status"] as? String else { return nil }
        return CameraTemperatureStatus(rawValue: rawValue)
    }

    private func parseBattery(_ value: JSONDictionary?) -> (level: Int?, status: String) {
        guard let value else { return (nil, "unknown") }
        let battery = value.array("batterylist")?.objects.first
            ?? value.array("battery")?.objects.first
            ?? value
        if let numeric = battery.integer("level") { return (numeric, "\(numeric)%") }
        let text = battery.string("level", default: battery.string("state", default: "unknown"))
        let level: Int?
        switch text.lowercased() {
        case "full": level = 100
        case "middle": level = 50
        case "low": level = 20
        case "empty": level = 5
        default: level = Int(text)
        }
        return (level, battery.string("state", default: text))
    }

    private struct StorageSummary {
        let available: Bool
        let totalBytes: Int64?
        let freeBytes: Int64?
        let freeImages: Int64?
        let devices: Int
    }

    private func parseStorage(_ value: JSONDictionary) -> StorageSummary {
        if let paths = value.array("path"), !paths.isEmpty {
            return StorageSummary(available: true, totalBytes: nil, freeBytes: nil, freeImages: nil, devices: 1)
        }
        let cards = value.array("storagelist")?.objects
            ?? value.array("storage")?.objects
            ?? [value]
        let usable = cards.filter { card in
            let status = card.string("status").lowercased()
            let access = card.string("accesscapability", default: card.string("access")).lowercased()
            if ["ready", "access"].contains(status) || ["readwrite", "readonly"].contains(access) { return true }
            let hasSpace = ["spacesize", "maxsize", "capacity", "free"].contains { (card.integer64($0) ?? 0) > 0 }
            return hasSpace && !["not_inserted", "none"].contains(status)
        }
        func sum(_ keys: [String]) -> Int64? {
            let values = usable.compactMap { card in
                keys.compactMap { card.integer64($0) }.first(where: { $0 > 0 })
            }
            guard !values.isEmpty else { return nil }
            return values.reduce(0) { total, value in
                let (sum, overflow) = total.addingReportingOverflow(value)
                return overflow ? Int64.max : sum
            }
        }
        return StorageSummary(
            available: !usable.isEmpty,
            totalBytes: sum(["maxsize", "capacity", "totalbytes", "totalsize"]),
            freeBytes: sum(["spacesize", "free", "freebytes", "freespace"]),
            freeImages: sum(["freeimages", "remainingimages", "numberofimages"]),
            devices: usable.count
        )
    }

    private func contentPaths(container: String, maxPaths: Int) async throws -> [String] {
        guard maxPaths > 0 else { return [] }
        let pageInfo = try await firstJSON(
            paths: ["\(container)?kind=number", "\(container)?type=all,kind=number"],
            required: false
        )
        let pageCount = min(pageInfo?.integer("pagenumber") ?? 0, Self.maximumMediaPages)
        var result: [String] = []
        if pageCount <= 0 {
            if let value = try await firstJSON(paths: [container], required: true) {
                result.append(contentsOf: value.array("path")?.strings ?? [])
            }
        } else if mediaDescendingOrderSupported == false {
            for page in stride(from: pageCount, through: 1, by: -1) {
                guard let value = try await contentPage(container: container, page: page) else { continue }
                let paths = value.array("path")?.strings ?? []
                result.append(contentsOf: paths.reversed())
                if result.count >= maxPaths { break }
            }
        } else {
            guard let firstPage = try await contentPage(container: container, page: 1) else { return [] }
            if mediaDescendingOrderSupported == false {
                for page in stride(from: pageCount, through: 1, by: -1) {
                    let value: JSONDictionary
                    if page == 1 {
                        value = firstPage
                    } else {
                        guard let response = try await contentPage(container: container, page: page) else {
                            continue
                        }
                        value = response
                    }
                    let paths = value.array("path")?.strings ?? []
                    result.append(contentsOf: paths.reversed())
                    if result.count >= maxPaths { break }
                }
            } else {
                result.append(contentsOf: firstPage.array("path")?.strings ?? [])
                if pageCount >= 2 {
                    for page in 2...pageCount {
                        if result.count >= maxPaths { break }
                        if let value = try await contentPage(container: container, page: page) {
                            result.append(contentsOf: value.array("path")?.strings ?? [])
                        }
                    }
                }
            }
        }
        return result.removingDuplicates().prefix(maxPaths).map { $0 }
    }

    private func contentPage(container: String, page: Int) async throws -> JSONDictionary? {
        let plainPath = "\(container)?page=\(page)"
        if mediaDescendingOrderSupported == false {
            return try await firstJSON(paths: [plainPath], required: true)
        }

        do {
            let orderedPath = "\(plainPath)&order=desc"
            let value = try await requestJSON(path: orderedPath)
            mediaDescendingOrderSupported = true
            return value
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as CCAPIError {
            if case let .http(statusCode, _, _, _) = error, statusCode == 400 {
                mediaDescendingOrderSupported = false
            }
            return try await requestJSON(path: plainPath)
        } catch {
            return try await requestJSON(path: plainPath)
        }
    }

    private func mergeMediaPathGroups(_ groups: [[String]], maxItems: Int) -> [String] {
        guard maxItems > 0 else { return [] }
        var positions = Array(repeating: 0, count: groups.count)
        var merged: [String] = []
        var seen = Set<String>()
        while merged.count < maxItems {
            var advanced = false
            for index in groups.indices {
                guard merged.count < maxItems, positions[index] < groups[index].count else { continue }
                let path = groups[index][positions[index]]
                positions[index] += 1
                advanced = true
                if seen.insert(path).inserted { merged.append(path) }
            }
            if !advanced { break }
        }
        return merged
    }

    private func normalizeCameraResource(_ value: String) throws -> String {
        if let absolute = URL(string: value), absolute.scheme != nil {
            guard Self.sameOrigin(absolute, baseURL) else { throw CCAPIError.outsideCameraOrigin(value) }
            guard var components = URLComponents(url: absolute, resolvingAgainstBaseURL: false) else {
                throw CCAPIError.invalidResponse("Camera returned an invalid media URL: \(value)")
            }
            guard components.fragment == nil, !Self.hasTraversalSegment(components.path) else {
                throw CCAPIError.invalidResponse("Invalid media path: \(value)")
            }
            components.scheme = nil
            components.host = nil
            components.port = nil
            components.user = nil
            components.password = nil
            let normalized = components.string ?? ""
            guard normalized.hasPrefix("/ccapi/") else { throw CCAPIError.invalidResponse("Invalid media path: \(value)") }
            return normalized
        }
        guard let components = URLComponents(string: value),
              components.fragment == nil,
              !Self.hasTraversalSegment(components.path),
              value.hasPrefix("/ccapi/") else {
            throw CCAPIError.invalidResponse("Invalid media path: \(value)")
        }
        return value
    }

    private func firstJSON(paths: [String], required: Bool) async throws -> JSONDictionary? {
        var failures: [String] = []
        for path in paths {
            do {
                return try await requestJSON(path: path)
            } catch {
                if error is CancellationError { throw error }
                failures.append("\(path): \(error.localizedDescription)")
            }
        }
        if required {
            throw CCAPIError.invalidResponse("Camera JSON request failed.\n" + failures.map { "- \($0)" }.joined(separator: "\n"))
        }
        return nil
    }

    private func requestJSON(
        path: String,
        method: HTTPMethod = .get,
        json: JSONDictionary? = nil,
        timeoutInterval: TimeInterval = 10,
        maximumBytes: Int? = nil
    ) async throws -> JSONDictionary {
        let request = try request(path: path, method: method, json: json, timeoutInterval: timeoutInterval)
        let response = try await transport.send(request)
        try validate(response, request: request)
        if let maximumBytes, response.body.count > maximumBytes {
            throw CCAPIError.invalidResponse("Camera response at \(path) exceeded the \(maximumBytes)-byte limit.")
        }
        do {
            return try decodeJSONObject(response.body)
        } catch let error as CCAPIError {
            throw error
        } catch {
            throw CCAPIError.invalidResponse("Camera returned invalid JSON: \(error.localizedDescription)")
        }
    }

    private func requestText(path: String, maximumBytes: Int) async throws -> String {
        let request = try request(path: path, method: .get)
        let response = try await transport.send(request)
        try validate(response, request: request)
        guard response.body.count <= maximumBytes else {
            throw CCAPIError.invalidResponse(
                "Camera response at \(path) exceeded the \(maximumBytes)-byte limit."
            )
        }
        guard let text = String(data: response.body, encoding: .utf8) else {
            throw CCAPIError.invalidResponse("Camera response at \(path) was not UTF-8 text.")
        }
        return text
    }

    private func requestOK(
        path: String,
        method: HTTPMethod,
        json: JSONDictionary? = nil,
        timeoutInterval: TimeInterval = 10,
        expectedStatusCode: Int? = nil
    ) async throws {
        let request = try request(path: path, method: method, json: json, timeoutInterval: timeoutInterval)
        let response = try await transport.send(request)
        try validate(response, request: request)
        if let expectedStatusCode, response.statusCode != expectedStatusCode {
            throw CCAPIError.invalidResponse(
                "Camera request \(method.rawValue) \(path) returned HTTP \(response.statusCode); " +
                    "expected HTTP \(expectedStatusCode)."
            )
        }
    }

    private func request(
        path: String,
        method: HTTPMethod,
        json: JSONDictionary? = nil,
        timeoutInterval: TimeInterval = 10
    ) throws -> URLRequest {
        guard let url = URL(string: baseURLString + path) else {
            throw CCAPIError.invalidResponse("Invalid camera request path: \(path)")
        }
        var value = request(url: url, method: method, timeoutInterval: timeoutInterval)
        if let json {
            value.httpBody = try encodeJSONObject(json)
            value.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        return value
    }

    private func request(url: URL, method: HTTPMethod, timeoutInterval: TimeInterval = 10) -> URLRequest {
        var value = URLRequest(url: url)
        value.httpMethod = method.rawValue
        value.cachePolicy = .reloadIgnoringLocalCacheData
        value.timeoutInterval = timeoutInterval
        value.setValue("application/json", forHTTPHeaderField: "Accept")
        if let authorization { value.setValue(authorization, forHTTPHeaderField: "Authorization") }
        return value
    }

    private func validate(_ response: CameraHTTPResponse, request: URLRequest) throws {
        guard (200..<300).contains(response.statusCode) else {
            let preview = String(data: response.body, encoding: .utf8)?.prefix(Self.maximumErrorBodyCharacters) ?? ""
            throw CCAPIError.http(
                statusCode: response.statusCode,
                method: request.httpMethod ?? "GET",
                url: request.url?.absoluteString ?? "unknown",
                body: String(preview)
            )
        }
    }

    private func URLForPath(_ path: String, cacheKey: Int64) throws -> URL {
        guard let url = URL(string: baseURLString + path),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw CCAPIError.invalidResponse("Invalid Live View path: \(path)")
        }
        if !path.contains("/shooting/liveview/flipdetail") {
            var queryItems = components.queryItems ?? []
            queryItems.append(URLQueryItem(name: "t", value: String(cacheKey)))
            components.queryItems = queryItems
        }
        guard let result = components.url else { throw CCAPIError.invalidResponse("Invalid Live View URL.") }
        return result
    }

    private static func extractVersion(from path: String) -> String? {
        guard let range = path.range(of: #"/ccapi/ver\d+"#, options: .regularExpression) else { return nil }
        return String(path[range]).split(separator: "/").last.map(String.init)
    }

    private static func versionNumber(_ value: String) -> Int? {
        guard value.hasPrefix("ver") else { return nil }
        return Int(value.dropFirst(3))
    }

    private static func pathVersion(_ path: String) -> Int {
        extractVersion(from: path).flatMap(versionNumber) ?? 0
    }

    private static func canonDateTimeFormatter(timeZone: TimeZone) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss Z"
        return formatter
    }

    private static func parseCameraClock(_ value: JSONDictionary) throws -> (date: Date, daylight: Bool) {
        let rawDateTime = value.string("datetime")
        guard !rawDateTime.isEmpty,
              let daylight = value.bool("dst"),
              let date = canonDateTimeFormatter(timeZone: TimeZone(secondsFromGMT: 0)!).date(from: rawDateTime) else {
            throw CCAPIError.invalidResponse(
                "Canon date-time response must contain an RFC 1123 datetime with UTC offset and a boolean dst field."
            )
        }
        return (date, daylight)
    }

    private static func methodSupported(_ value: Any?) -> Bool {
        switch value {
        case let value as Bool: value
        case let value as NSNumber: value.intValue != 0
        case let value as String: !value.isEmpty && !["false", "no", "none", "unsupported"].contains(value.lowercased())
        case .some: true
        case .none: false
        }
    }

    private static func safeEventKeys(_ keys: [String]) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for key in keys {
            let safe = String(
                key.replacingOccurrences(of: "\r", with: "")
                    .replacingOccurrences(of: "\n", with: "")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .prefix(maximumEventKeyCharacters)
            )
            guard !safe.isEmpty, seen.insert(safe).inserted else { continue }
            result.append(safe)
            if result.count >= maximumEventKeys { break }
        }
        return result
    }

    private static func settingLabel(_ key: String) -> String {
        let known = [
            "afmethod": "AF method", "afoperation": "AF operation", "drivemode": "Drive mode",
            "meteringmode": "Metering", "picturestyle": "Picture style", "moviemode": "Movie mode",
            "shootingmode": "Shooting mode",
            "stillimagequality": "Image quality",
            "stillimagequality.raw": "RAW quality", "stillimagequality.jpeg": "JPEG quality",
            "stillimagequality.heif": "HEIF quality",
            "wbshift.ba": "WB shift B/A", "wbshift.mg": "WB shift M/G",
            "colortemperature": "Color temperature", "exposurecompensation": "Exposure compensation",
            "alomode": "Auto Lighting Optimizer",
            "zoom": "Zoom",
            Self.stillCardSelectionSettingKey: "Still-image card",
            Self.movieCardSelectionSettingKey: "Movie card",
            Self.beepSettingKey: "Beep",
            Self.displayOffSettingKey: "Auto display off",
            Self.autoPowerOffSettingKey: "Auto power off",
            Self.soundRecordingSettingKey: "Sound recording",
            Self.soundRecordingModeIntMicSettingKey: "Internal microphone mode",
            Self.soundRecordingModeExtMicSettingKey: "External microphone mode",
            Self.soundRecordingModeAccessorySettingKey: "Accessory microphone mode",
            Self.windFilterSettingKey: "Wind filter",
            Self.windFilterIntMicSettingKey: "Internal microphone wind filter",
            Self.windFilterExtMicSettingKey: "External microphone wind filter",
            Self.windFilterAccessorySettingKey: "Accessory microphone wind filter",
            Self.attenuatorSettingKey: "Attenuator",
            Self.attenuatorIntMicSettingKey: "Internal microphone attenuator",
            Self.attenuatorExtMicSettingKey: "External microphone attenuator",
            Self.attenuatorAccessorySettingKey: "Accessory microphone attenuator",
            Self.soundRecordingLevelSettingKey: "Sound recording level",
            Self.soundRecordingLevelIntMicSettingKey: "Internal microphone level",
            Self.soundRecordingLevelExtMicSettingKey: "External microphone level",
            Self.soundRecordingLevelAccessorySettingKey: "Accessory microphone level",
            Self.focusBracketingSettingKey: "Focus bracketing",
            Self.focusBracketingNumberSettingKey: "Focus bracketing shots",
            Self.focusBracketingIncrementSettingKey: "Focus increment",
            Self.focusBracketingSmoothingSettingKey: "Exposure smoothing",
            Self.movieQualitySettingKey: "Movie quality",
            Self.highFrameRateSettingKey: "High frame rate",
            Self.movieCroppingSettingKey: "Movie cropping",
            Self.movieFormatSettingKey: "Movie recording format",
            Self.directorySelectionSettingKey: "Capture directory",
        ]
        if let label = known[key] { return label }
        return key.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ").capitalized
    }

    private static func isTextContentType(_ value: String?) -> Bool {
        guard let normalized = value?.lowercased() else { return false }
        return normalized.hasPrefix("text/") || normalized.contains("json") || normalized.contains("html")
    }

    private static func looksLikeTextPayload(_ data: Data) -> Bool {
        guard let text = String(data: data, encoding: .utf8) else { return false }
        guard let first = text.first(where: { !$0.isWhitespace }) else { return false }
        return first == "{" || first == "[" || first == "<"
    }

    private static func imageContentType(_ data: Data) -> String? {
        let bytes = [UInt8](data.prefix(12))
        if bytes.count >= 3, bytes[0] == 0xFF, bytes[1] == 0xD8, bytes[2] == 0xFF { return "image/jpeg" }
        if bytes.count >= 8,
           bytes[0] == 0x89, bytes[1] == 0x50, bytes[2] == 0x4E, bytes[3] == 0x47,
           bytes[4] == 0x0D, bytes[5] == 0x0A, bytes[6] == 0x1A, bytes[7] == 0x0A {
            return "image/png"
        }
        if bytes.count >= 6, [UInt8](bytes[0..<6]) == Array("GIF87a".utf8) || [UInt8](bytes[0..<6]) == Array("GIF89a".utf8) {
            return "image/gif"
        }
        if bytes.count >= 12,
           [UInt8](bytes[0..<4]) == Array("RIFF".utf8),
           [UInt8](bytes[8..<12]) == Array("WEBP".utf8) {
            return "image/webp"
        }
        return nil
    }

    private static func hasTraversalSegment(_ path: String) -> Bool {
        path.split(separator: "/", omittingEmptySubsequences: false).contains { $0 == "." || $0 == ".." }
    }

    private static func isMediaPath(_ value: String) -> Bool {
        let name = (value as NSString).lastPathComponent
        return name.contains(".") && !name.hasSuffix(".")
    }

    private static func mediaKind(_ value: String) -> String {
        switch (value as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg", "hif", "heif", "png": "image"
        case "cr2", "cr3", "raw": "raw"
        case "mp4", "mov": "video"
        default: "other"
        }
    }

    private static func isCCAPIDisplayPreviewPath(_ value: String) -> Bool {
        let path = value.components(separatedBy: "?")[0]
        return ["jpg", "jpeg", "cr3"].contains((path as NSString).pathExtension.lowercased())
    }

    private static func encodePathComponent(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private static func sameOrigin(_ left: URL, _ right: URL) -> Bool {
        left.scheme?.lowercased() == right.scheme?.lowercased() &&
            left.host?.lowercased() == right.host?.lowercased() &&
            effectivePort(left) == effectivePort(right)
    }

    private static func effectivePort(_ url: URL) -> Int {
        url.port ?? (url.scheme?.lowercased() == "https" ? 443 : 80)
    }

    private static let primarySettingKeys = Set(["iso", "shutter", "aperture", "whitebalance"])
    private static let allPrimaryAliases = Set([
        "iso", "shutter", "shutterspeed", "tv", "aperture", "av", "whitebalance", "white_balance", "wb",
    ])
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

private extension Array where Element: Hashable {
    func removingDuplicates() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
