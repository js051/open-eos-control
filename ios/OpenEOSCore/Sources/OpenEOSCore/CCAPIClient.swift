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

public actor CCAPIClient: CameraForegroundMediaReading {
    private static let maxDeviceStatusTextCharacters = 512
    private static let maximumErrorBodyCharacters = 2_000
    private static let noAPIListValue = "No list of APIs"
    private static let developerAPIPath = "/ccapi/ver100/topurlfordev"
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
    private let decodedJPEGValidator: (@Sendable (Data) -> Bool)?
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
    private var shootingSettingsGeneration = UUID()
    private var shootingSettingsReadRevision: UInt64 = 0
    private var committedShootingSettingsRevision: UInt64 = 0
    private var cachedFileNaming: CameraFileNaming?
    private var fileNamingLoaded = false
    private var enforceAdvertisedOperations = false
    private var settingsLoaded = false
    private var discoverySource = "unknown"
    private var cachedModel = "Canon Camera"
    public private(set) var recordingCommandEvidence: CameraRecordingCommandEvidence?
    private var recording: Bool?
    private let recordingOwner = UUID()
    private var recordingAttemptID: UUID?
    private var recordingRevision = UUID()
    // Same-session Stop authorization is independent of current recording duty.
    // A cancelled publication can retain app-side duty after ACK/fresh false.
    // Never use this route to admit Start; retire it only with this client.
    private var retainedRecordingStop: CCAPIOperation?
    private var bulbExposureActive = false
    private struct ShutterRelease: Sendable {
        let id: UUID
        let operation: CCAPIOperation
        let simulator: Bool
        let payload: Data
    }
    private var shortControlTask: Task<Void, Error>?
    private var shortControlID: UUID?
    private var pendingShutterRelease: ShutterRelease?
    private var bulbStartAcknowledged = false
    private var shutterReleaseUnconfirmed = false
    private var bulbStateAuthoritative = false
    private var bulbStartTask: Task<CameraStatus, Error>?
    private var bulbStartID: UUID?
    private var shutterReleaseTask: Task<Void, Error>?
    private var closing = false
    private var closeTask: Task<CameraShutterReleaseState, Never>?
    private var cameraMutationCount = 0
    private var latestTemperatureStatus: CameraTemperatureStatus?
    private var liveViewSizeControlSupported = true
    private var rejectedLiveViewSizes = Set<LiveViewSize>()
    private var activeLiveViewSize = LiveViewSize.medium
    private var activeLiveViewSource: LiveViewSource?
    private var requestedLiveViewRequest: LiveViewRequest?
    private var liveViewSizeFallbackAttempted = false
    private struct LiveViewStopObligation: Sendable {
        let id: UUID
        let source: LiveViewSource
        let stop: URLRequest
        let closeStream: URLRequest?
        var maintenanceOwned = false
    }
    private var pendingLiveViewStop: LiveViewStopObligation?
    private var liveViewStopUnconfirmed = false
    private var retiredLiveViewSource: LiveViewSource?
    private var liveViewMaintenanceInFlight = false
    private let maintenanceSessionOwner = UUID()
    private var maintenanceLifecycleOwned = false
    private var maintenanceEventStopRequired = false
    private var activeMaintenanceAttempt: CameraMaintenanceAttempt?
    private var maintenanceLiveViewControlID: UUID?
    private var liveViewStopRevision = UUID()
    private var liveViewStartTask: Task<Void, Error>?
    private var liveViewStartID: UUID?
    private var liveViewStopTask: Task<Void, Error>?
    private var liveViewReleaseTask: Task<Void, Error>?
    private var rtpSession: (any CCAPIRTPSession)?
    private var multipartSession: CCAPIMultipartLiveViewSession?
    private var pendingJPEGFrame: LiveViewFrame?
    private var pendingMultipartFrame: Data?
    private var nativeGeometryCacheKey: Int64 = 0
    private var latestLiveViewGeometry: CCAPILiveViewGeometry?
    private struct LiveViewGeometryOwner {
        let session: UUID?
        let source: LiveViewSource?
        let revision: UUID
    }
    private var liveViewGeometryRevision = UUID()
    private var liveViewMagnificationAttempt: UUID?
    private var liveViewMagnificationOwner: LiveViewGeometryOwner?
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
        decodedJPEGValidator: (@Sendable (Data) -> Bool)? = nil,
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
        self.decodedJPEGValidator = decodedJPEGValidator
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
        try Task.checkCancellation()
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
        var failureReasons = Set<CameraConnectionFailureReason>()
        for path in ["/ccapi", "/ccapi/"] {
            do {
                let rootDiscovery: JSONDictionary
                do {
                    rootDiscovery = try await requestJSON(path: path)
                    try Task.checkCancellation()
                } catch {
                    if CameraConnectionFailureReason.isCancellation(error) { throw error }
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
                    try Task.checkCancellation()
                } catch {
                    if CameraConnectionFailureReason.isCancellation(error) { throw error }
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
                if CameraConnectionFailureReason.isCancellation(error) { throw error }
                try Task.checkCancellation()
                failureReasons.insert(CameraConnectionFailureReason.classify(error) ?? .discoveryFailed)
                errors.append("GET \(path): \(error.localizedDescription)")
            }
        }

        for prefix in ["/ccapi/ver110", "/ccapi/ver100"] {
            do {
                let value = try await requestJSON(path: "\(prefix)/deviceinformation")
                try Task.checkCancellation()
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
                if CameraConnectionFailureReason.isCancellation(error) { throw error }
                try Task.checkCancellation()
                failureReasons.insert(CameraConnectionFailureReason.classify(error) ?? .discoveryFailed)
                recordDiscoveryFailure(endpoint: "GET \(prefix)/deviceinformation", error: error)
                errors.append("GET \(prefix)/deviceinformation: \(error.localizedDescription)")
            }
        }
        throw CCAPIError.discoveryFailedWithReasons(errors, failureReasons)
    }

    public func connectSnapshot() async throws -> CameraSnapshot {
        try await initialize()
        try Task.checkCancellation()
        guard !closing else { throw CancellationError() }
        let cameraInfo = try await readInfo(preservingConnectionEvidence: true)
        try Task.checkCancellation()
        guard !closing else { throw CancellationError() }
        let cameraStatus = try await status()
        try Task.checkCancellation()
        guard !closing else { throw CancellationError() }
        let cameraCapabilities = try await capabilities()
        try Task.checkCancellation()
        guard !closing else { throw CancellationError() }
        return CameraSnapshot(info: cameraInfo, status: cameraStatus, capabilities: cameraCapabilities)
    }

    public func close() async {
        _ = await closeWithShutterReleaseState()
    }

    /// Retains the existing close API while exposing a failed final release to callers.
    public func closeWithShutterReleaseState() async -> CameraShutterReleaseState {
        if let closeTask { return await closeTask.value }
        closing = true
        retainedRecordingStop = nil
        let task = Task.detached(priority: .userInitiated) { await self.finishClose() }
        closeTask = task
        return await task.value
    }

    private func finishClose() async -> CameraShutterReleaseState {
        if maintenanceLifecycleOwned { return await finishMaintenanceClose() }
        // Cancel the owned pending request, then settle it before final release.
        // A transport that ignores cancellation must still finish first.
        if let bulbStartTask {
            bulbStartTask.cancel()
            _ = await bulbStartTask.result
        }
        if let shortControlTask {
            shortControlTask.cancel()
            _ = await shortControlTask.result
        }
        // Preserve Close's bounded final cleanup: if Start's compensation failed,
        // Close makes one last original-endpoint release attempt before retiring.
        // Concurrent/repeated Close calls share closeTask; this is separate from
        // one Stop action, which surfaces its failed compensation without retrying.
        try? await releasePendingShutter()
        if let start = liveViewStartTask {
            start.cancel()
            _ = await start.result
        }
        await stopEventPolling()
        await stopLiveView()
        retiredLiveViewSource = pendingLiveViewStop?.source
        pendingLiveViewStop = nil
        let unconfirmed = pendingShutterRelease != nil || shutterReleaseUnconfirmed
        // A retired connection keeps a warning, never a command to replay in its replacement.
        pendingShutterRelease = nil
        bulbExposureActive = false
        shutterReleaseUnconfirmed = unconfirmed
        return CameraShutterReleaseState(
            releaseRequired: false,
            releaseUnconfirmed: unconfirmed,
            bulbExposureActive: unconfirmed ? nil : false
        )
    }

    public func shutterReleaseState() -> CameraShutterReleaseState {
        CameraShutterReleaseState(
            releaseRequired: pendingShutterRelease != nil,
            releaseUnconfirmed: shutterReleaseUnconfirmed,
            bulbExposureActive: shutterReleaseUnconfirmed ? nil : bulbExposureActive
        )
    }

    public func info() async throws -> CameraInfo {
        try await readInfo(preservingConnectionEvidence: false)
    }

    private func readInfo(preservingConnectionEvidence: Bool) async throws -> CameraInfo {
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

        let value = try await firstJSON(paths: versionedPaths("/deviceinformation"), required: true,
                                        preservingConnectionEvidence: preservingConnectionEvidence)
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
            if let observed = status.recording { recording = observed }
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
            if !closing, !bulbStateAuthoritative, pendingShutterRelease == nil, status.bulbExposureActive == true {
                // The Simulator contract explicitly reports an existing exposure.
                // Adopt its known stop route without claiming a locally observed start.
                pendingShutterRelease = ShutterRelease(
                    id: UUID(), operation: CCAPIOperation(method: .post, path: "/ccapi/bulb/stop"),
                    simulator: true, payload: try encodeJSONObject([:])
                )
                bulbStateAuthoritative = true
                bulbExposureActive = true
                bulbStartAcknowledged = false
                shutterReleaseUnconfirmed = false
            }
            return closing || bulbStateAuthoritative || pendingShutterRelease != nil || shutterReleaseUnconfirmed
                ? status.withShutterReleaseState(shutterReleaseState()) : status
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
            bulbExposureActive: shutterReleaseUnconfirmed ? nil : bulbExposureActive,
            shutterReleaseUnconfirmed: shutterReleaseUnconfirmed,
            liveViewStopUnconfirmed: liveViewStopUnconfirmed,
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
        _ = try await cachedOrLoadShootingSettings()
        let fileNaming = try await loadFileNaming()
        // File-naming reads can suspend; pair values with the committed routes
        // available now, not a settings dictionary captured before that await.
        let controls = cameraSettings(cachedSettings)
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
                maximumFPS: 30,
                focusInfoSupported: !ccapiLiveViewSources().isEmpty && detailedLiveViewOperation() != nil
            ),
            profile: CameraProfile.from(modelName: cachedModel),
            evidence: capabilityEvidence(),
            shutterAutofocusSupported: directShutterOperation() != nil || manualShutterOperation() != nil,
            heldAutofocusSupported: autofocusOperation() != nil
        )
    }

    public func pollEvent() async throws -> CameraEvent {
        try await ensureInitialized()
        if maintenanceLifecycleOwned { maintenanceEventStopRequired = true }
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
        try await writeShootingSetting(key: key, value: value)
        return try await status()
    }

    public func applyShootingSetting(key: String, value: String, attempt: CameraShootingSettingAttempt) async throws {
        attempt.readbackUnavailable()
        try await writeShootingSetting(key: key, value: value, attempt: attempt)
        try Task.checkCancellation()
        try await refreshShootingSetting(attempt)
    }

    private func writeShootingSetting(key: String, value: String, attempt: CameraShootingSettingAttempt? = nil) async throws {
        try attempt?.validate(key: key, value: value)
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        // A refresh that predates this command cannot later publish stale
        // setting values or routes over the command's readback owner.
        retireShootingSettingsReads()
        try await ensureInitialized()
        if resolvedMode == .simulator {
            if attempt != nil {
                let advertised = try await requestJSON(path: "/ccapi/capabilities", timeoutInterval: 15,
                    maximumBytes: 64 * 1024, shootingSettingRead: true)
                let values: [String]
                if Self.primarySettingKeys.contains(key) {
                    values = advertised[key == "whitebalance" ? "white_balance" : key] as? [String] ?? []
                } else if let object = advertised[key] as? JSONDictionary,
                          let observed = shootingSettingObservation(object, key: key, field: nil) {
                    values = observed.values
                } else { values = [] }
                guard values.contains(where: { $0.utf8.elementsEqual(value.utf8) }) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
            }
            switch key {
            case "iso", "shutter", "aperture":
                _ = try await requestJSON(path: "/ccapi/exposure", method: .patch, json: [key: value], shootingSettingAttempt: attempt)
            case "whitebalance":
                _ = try await requestJSON(
                    path: "/ccapi/white-balance",
                    method: .patch,
                    json: ["white_balance": value],
                    shootingSettingAttempt: attempt
                )
            case Self.zoomSettingKey:
                guard let zoom = Int(value), String(zoom) == value, (0...100).contains(zoom) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                _ = try await requestJSON(path: "/ccapi/zoom", method: .post, json: ["value": zoom], shootingSettingAttempt: attempt)
            case Self.movieModeSettingKey:
                guard Self.movieModeValues.contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: "/ccapi/movie-mode", method: .post, json: ["action": value], shootingSettingAttempt: attempt)
            case Self.stillCardSelectionSettingKey:
                guard Self.cardSelectionValues.contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(
                    path: "/ccapi/card-selection/stillimage",
                    method: .put,
                    json: ["value": value],
                    shootingSettingAttempt: attempt
                )
            case Self.movieCardSelectionSettingKey:
                guard Self.cardSelectionValues.contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(
                    path: "/ccapi/card-selection/movie",
                    method: .put,
                    json: ["value": value],
                    shootingSettingAttempt: attempt
                )
            case let deviceKey where Self.deviceFunctionSettingKeys.contains(deviceKey):
                guard let endpoint = Self.deviceFunctionSettingEndpoints.first(where: { $0.key == deviceKey }),
                      endpoint.values.subtracting(Set([Self.autoPowerOffImmediately])).contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: endpoint.simulatorPath, method: .put, json: ["value": value], shootingSettingAttempt: attempt)
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
                    json: ["value": level],
                    shootingSettingAttempt: attempt
                )
            case let soundKey where Self.soundRecordingSettingKeys.contains(soundKey):
                guard let endpoint = Self.soundRecordingEndpoints.first(where: { $0.key == soundKey }),
                      endpoint.values.contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: endpoint.simulatorPath, method: .put, json: ["value": value], shootingSettingAttempt: attempt)
            case let focusKey where Self.focusBracketingStringSettingKeys.contains(focusKey):
                guard let endpoint = Self.focusBracketingStringEndpoints.first(where: { $0.key == focusKey }),
                      endpoint.values.contains(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: endpoint.simulatorPath, method: .put, json: ["value": value], shootingSettingAttempt: attempt)
            case let focusKey where Self.focusBracketingIntegerSettingKeys.contains(focusKey):
                guard let endpoint = Self.focusBracketingIntegerEndpoints.first(where: { $0.key == focusKey }),
                      let integer = Int(value),
                      String(integer) == value,
                      Self.simulatorFocusBracketingValues[focusKey]?.contains(value) == true else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: endpoint.simulatorPath, method: .put, json: ["value": integer], shootingSettingAttempt: attempt)
            case let movieKey where Self.movieSettingKeys.contains(movieKey):
                guard let endpoint = Self.movieSettingEndpoints.first(where: { $0.key == movieKey }),
                      Self.simulatorMovieSettingValues[movieKey]?.contains(value) == true else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: endpoint.simulatorPath, method: .put, json: ["value": value], shootingSettingAttempt: attempt)
            case Self.directorySelectionSettingKey:
                guard Self.isValidDirectorySelection(value) else {
                    throw CCAPIError.invalidSetting(key: key, value: value)
                }
                try await requestOK(path: "/ccapi/directory-selection", method: .put, json: ["value": value], shootingSettingAttempt: attempt)
            default:
                throw CCAPIError.unsupported(.advancedSettings)
            }
            observedFeatures.insert(featureForSetting(key))
            return
        }

        var settings = cachedSettings
        if let attempt {
            let structured = structuredSettingParts(key)
            let candidates = structured.map { [$0.baseKey] } ?? shootingSettingAliases(for: key)
            guard let baseKey = candidates.first(where: { settingPaths[$0] != nil }), let path = settingPaths[baseKey] else {
                throw CCAPIError.invalidSetting(key: key, value: value)
            }
            try prepareShootingSettingRoute(path: path, attempt: attempt)
            let raw = try await readShootingSettingObject(path: path, baseKey: baseKey, field: structured?.field)
            if let structured, !validShootingSettingSiblings(raw, baseKey: structured.baseKey) {
                throw CCAPIError.invalidSetting(key: key, value: value)
            }
            guard let fresh = shootingSettingObservation(raw, key: key, field: structured?.field),
                  fresh.values.contains(where: { $0.utf8.elementsEqual(value.utf8) }),
                  !(key == "autopoweroff" && value == Self.autoPowerOffImmediately) else {
                throw CCAPIError.invalidSetting(key: key, value: value)
            }
            // Pass only this fresh selected object to the existing payload builders.
            var selected = cachedSettings ?? [:]
            selected[baseKey] = structured == nil ? ["value": fresh.value, "ability": fresh.values] : raw
            settings = selected
        } else if Self.cardSelectionSettingKeys.contains(key.lowercased()) ||
            Self.deviceFunctionSettingKeys.contains(key.lowercased()) ||
            Self.soundRecordingSettingKeys.contains(key.lowercased()) ||
            Self.soundRecordingLevelSettingKeys.contains(key.lowercased()) ||
            Self.focusBracketingSettingKeys.contains(key.lowercased()) ||
            Self.movieSettingKeys.contains(key.lowercased()) ||
            key.lowercased() == Self.directorySelectionSettingKey {
            _ = try await loadShootingSettings()
        } else {
            _ = try await cachedOrLoadShootingSettings()
        }
        // Use one committed value/route view after all required reads. A
        // concurrent invalidation remains a closed gate, never a stale fallback.
        if attempt == nil { settings = cachedSettings }
        guard let control = cameraSettings(settings).first(where: { $0.key == key }),
              control.values.contains(value) else {
            throw CCAPIError.invalidSetting(key: key, value: value)
        }
        if key == Self.movieModeSettingKey {
            guard let path = attempt?.readRoute?.writePath ?? settingPaths[Self.movieModeSettingKey] else {
                throw CCAPIError.invalidSetting(key: key, value: value)
            }
            try await requestOK(path: path, method: .post, json: ["action": value], shootingSettingAttempt: attempt)
            invalidateShootingSettingsCache()
        } else if key == Self.zoomSettingKey {
            guard let path = attempt?.readRoute?.writePath ?? settingPaths[Self.zoomSettingKey],
                  let zoom = Int(value), String(zoom) == value else {
                throw CCAPIError.invalidSetting(key: key, value: value)
            }
            _ = try await requestJSON(path: path, method: .post, json: ["value": zoom], shootingSettingAttempt: attempt)
            invalidateShootingSettingsCache()
        } else if Self.soundRecordingLevelSettingKeys.contains(key) {
            guard let path = attempt?.readRoute?.writePath ?? settingPaths[key],
                  let level = Int(value), String(level) == value else {
                throw CCAPIError.invalidSetting(key: key, value: value)
            }
            _ = try await requestJSON(path: path, method: .put, json: ["value": level], shootingSettingAttempt: attempt)
            invalidateShootingSettingsCache()
        } else if Self.focusBracketingIntegerSettingKeys.contains(key) {
            guard let path = attempt?.readRoute?.writePath ?? settingPaths[key],
                  let integer = Int(value), String(integer) == value else {
                throw CCAPIError.invalidSetting(key: key, value: value)
            }
            _ = try await requestJSON(path: path, method: .put, json: ["value": integer], shootingSettingAttempt: attempt)
            invalidateShootingSettingsCache()
        } else if let structured = structuredSettingParts(key) {
            try await putStructuredSettingValue(
                settings: settings,
                baseKey: structured.baseKey,
                field: structured.field,
                value: value,
                shootingSettingAttempt: attempt
            )
        } else {
            try await putSettingValue(candidateKeys: shootingSettingAliases(for: key), value: value, shootingSettingAttempt: attempt)
        }
        observedFeatures.insert(featureForSetting(key))
        return
    }

    private func shootingSettingAliases(for key: String) -> [String] {
        switch key {
        case "shutter": return ["shutter", "shutterspeed", "tv"]
        case "aperture": return ["aperture", "av"]
        case "whitebalance": return ["whitebalance", "white_balance", "wb"]
        default: return [key]
        }
    }

    private func shootingSettingRoute(path: String, baseKey: String, field: String?) -> CameraShootingSettingRoute? {
        if operations.contains(CCAPIOperation(method: .get, path: path)) {
            return CameraShootingSettingRoute(writePath: path, path: path, baseKey: baseKey, field: field, aggregate: false, simulator: false)
        }
        // An aggregate endpoint is usable only when advertised for this exact version.
        let suffix = "/shooting/settings/" + baseKey
        if path.hasSuffix(suffix) {
            let aggregate = String(path.dropLast(baseKey.count + 1))
            if operations.contains(CCAPIOperation(method: .get, path: aggregate)) {
                return CameraShootingSettingRoute(writePath: path, path: aggregate, baseKey: baseKey, field: field, aggregate: true, simulator: false)
            }
        }
        return nil
    }

    private func prepareShootingSettingRoute(path: String, attempt: CameraShootingSettingAttempt) throws {
        let key = attempt.snapshot.key
        if resolvedMode == .simulator {
            try attempt.configure(CameraShootingSettingRoute(writePath: path, path: "/ccapi/capabilities", baseKey: key,
                field: nil, aggregate: true, simulator: true))
        } else {
            let structured = structuredSettingParts(key)
            let baseKey = structured?.baseKey ?? String(path.split(separator: "/").last ?? Substring(key))
            if let route = shootingSettingRoute(path: path, baseKey: baseKey, field: structured?.field) {
                try attempt.configure(route)
            }
        }
    }

    private func readShootingSettingObject(path: String, baseKey: String, field: String?) async throws -> JSONDictionary {
        guard let route = shootingSettingRoute(path: path, baseKey: baseKey, field: field) else {
            throw CCAPIError.invalidResponse("Camera did not advertise a read for this exact setting route.")
        }
        let raw = try await requestJSON(path: route.path, timeoutInterval: 15, maximumBytes: 64 * 1024,
                                        shootingSettingRead: true)
        guard let value = route.aggregate ? raw[baseKey] as? JSONDictionary : raw else {
            throw CCAPIError.invalidResponse("Camera omitted the requested setting object.")
        }
        return value
    }

    public func refreshShootingSetting(_ attempt: CameraShootingSettingAttempt) async throws {
        attempt.readbackUnavailable()
        try Task.checkCancellation()
        guard !closing, let route = attempt.readRoute else {
            throw CCAPIError.invalidResponse("No original setting read route is available.")
        }
        let deadline = ContinuousClock.now.advanced(by: .seconds(15))
        let raw = try await requestJSON(path: route.path, timeoutInterval: 15, maximumBytes: 64 * 1024,
                                        shootingSettingRead: true)
        let key = attempt.snapshot.key
        let object: JSONDictionary
        if route.simulator, Self.primarySettingKeys.contains(key) {
            let abilityKey = key == "whitebalance" ? "white_balance" : key
            guard let ability = raw[abilityKey] as? [String] else {
                throw CCAPIError.invalidResponse("Simulator omitted this setting's abilities.")
            }
            let remaining = ContinuousClock.now.duration(to: deadline)
            let seconds = Double(remaining.components.seconds) + Double(remaining.components.attoseconds) / 1e18
            guard seconds > 0 else { throw CCAPIError.invalidResponse("Setting readback exceeded its deadline.") }
            let status = try await requestJSON(path: "/ccapi/status", timeoutInterval: seconds,
                maximumBytes: 64 * 1024, shootingSettingRead: true)
            guard let exposure = status["exposure"] as? JSONDictionary,
                  let value = exposure[abilityKey] as? String else {
                throw CCAPIError.invalidResponse("Simulator omitted the current setting value.")
            }
            object = ["value": value, "ability": ability]
        } else if route.aggregate {
            guard let value = raw[route.baseKey] as? JSONDictionary else {
                throw CCAPIError.invalidResponse("Camera omitted the requested setting.")
            }
            object = value
        } else { object = raw }
        guard ContinuousClock.now < deadline else { throw CCAPIError.invalidResponse("Setting readback exceeded its deadline.") }
        try Task.checkCancellation()
        guard !closing, let observed = shootingSettingObservation(object, key: key, field: route.field) else {
            throw CCAPIError.invalidResponse("Camera returned an invalid current setting value or ability.")
        }
        attempt.observe(observed)
    }

    private func validShootingSettingSiblings(_ object: JSONDictionary, baseKey: String) -> Bool {
        guard let current = object["value"] as? JSONDictionary,
              let ability = object["ability"] as? JSONDictionary,
              Set(current.keys) == Set(ability.keys), !current.isEmpty else { return false }
        if baseKey == Self.wbShiftSettingKey {
            guard Set(current.keys) == Set(Self.wbShiftFields) else { return false }
            return Self.wbShiftFields.allSatisfy { field in
                guard let value = Self.strictInteger(current[field]), let range = ability[field] as? JSONDictionary,
                      Set(range.keys) == Set(["min", "max", "step"]) else { return false }
                return Self.boundedIntegerRangeValues(range).contains(String(value))
            }
        }
        guard baseKey == Self.imageQualitySettingKey,
              Set(current.keys).isSubset(of: Set(Self.imageQualityFields)) else { return false }
        return current.keys.allSatisfy { field in
            guard let value = current[field] as? String, CameraShootingSettingValue.validString(value),
                  let values = ability[field] as? [String], (1...1024).contains(values.count),
                  values.allSatisfy({ !$0.isEmpty && CameraShootingSettingValue.validString($0) }),
                  Set(values.map { Data($0.utf8) }).count == values.count else { return false }
            return values.contains(where: { $0.utf8.elementsEqual(value.utf8) })
        }
    }

    private func shootingSettingObservation(_ object: JSONDictionary, key: String, field: String?) -> CameraSetting? {
        var normalized = object
        if let field {
            let baseKey = key.hasPrefix(Self.wbShiftSettingKey + ".") ? Self.wbShiftSettingKey : Self.imageQualitySettingKey
            guard validShootingSettingSiblings(object, baseKey: baseKey), let current = object["value"] as? JSONDictionary,
                  let ability = object["ability"] as? JSONDictionary else { return nil }
            if key.hasPrefix(Self.wbShiftSettingKey + ".") {
                guard let value = Self.strictInteger(current[field]), let range = ability[field] as? JSONDictionary else { return nil }
                normalized = ["value": String(value), "ability": Self.boundedIntegerRangeValues(range)]
            } else {
                guard let value = current[field] as? String, let values = ability[field] as? [String] else { return nil }
                normalized = ["value": value, "ability": values]
            }
        } else if key == Self.movieModeSettingKey {
            guard let value = object["status"] as? String ?? object["value"] as? String,
                  Self.movieModeValues.contains(value) else { return nil }
            normalized = ["value": value, "ability": Array(Self.movieModeValues).sorted()]
        } else if object["value"] is NSNumber {
            guard let range = Self.validatedIntegerRangeSetting(object) else { return nil }
            normalized = range
        }
        guard let value = normalized["value"] as? String,
              let values = normalized["ability"] as? [String] else { return nil }
        return CameraShootingSettingValue.parse(["key": key, "label": Self.settingLabel(key), "value": value, "values": values, "inputKind": "choice"], key: key)
    }

    public func createDirectory(name: String, attempt: CameraCaptureConfigurationAttempt? = nil) async throws -> String {
        let attempt = attempt ?? CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: name)
        try attempt.validate(operation: .createDirectory, value: name)
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        try await ensureInitialized()
        guard Self.isValidDirectoryCreateName(name) else {
            throw CCAPIError.invalidSetting(key: "directoryname", value: name)
        }
        let response: JSONDictionary
        if resolvedMode == .simulator {
            response = try await requestJSON(
                path: "/ccapi/directory",
                method: .post,
                json: ["directoryname": name],
                captureConfigurationAttempt: attempt
            )
        } else {
            guard let operations = directoryOperations() else {
                throw CCAPIError.unsupported(.directoryControl)
            }
            response = try await requestJSON(
                path: operations.create.path,
                method: operations.create.method,
                json: ["directoryname": name],
                captureConfigurationAttempt: attempt
            )
        }
        guard let created = response["directoryname"] as? String,
              Self.isValidCreatedDirectoryName(created) else {
            throw CCAPIError.invalidResponse("Canon directory creation returned an invalid directory name.")
        }
        attempt.acknowledge(created)
        observedFeatures.insert(.directoryControl)
        // Retire in-flight aggregate reads, but retain unrelated cached fields.
        // Only the canonical directoryselection GET can supply readback proof.
        retireShootingSettingsReads()
        if resolvedMode != .simulator {
            attempt.readbackUnavailable()
            try Task.checkCancellation()
            try await refreshCaptureConfiguration(attempt)
        }
        return created
    }

    public func setFileNaming(
        field: CameraFileNamingField,
        value: String,
        attempt: CameraCaptureConfigurationAttempt? = nil
    ) async throws -> CameraFileNaming {
        let attempt = attempt ?? CameraCaptureConfigurationAttempt(operation: .setFileNaming, requestedValue: value, field: field)
        try attempt.validate(operation: .setFileNaming, value: value, field: field)
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
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
                json: ["value": value],
                captureConfigurationAttempt: attempt
            )
            guard let naming = Self.validatedBridgeFileNaming(response) else {
                throw CCAPIError.invalidResponse("Simulator returned an invalid file-naming state.")
            }
            guard naming.value(for: field) == value else {
                throw CCAPIError.invalidResponse("Simulator returned a mismatched file-naming update.")
            }
            attempt.acknowledge(value)
            attempt.observe(naming)
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
                json: [endpoint.responseKey: requestValue],
                captureConfigurationAttempt: attempt
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
            attempt.acknowledge(value)
            attempt.readbackUnavailable()
            try Task.checkCancellation()
            fileNamingLoaded = false
            guard let naming = try await loadFileNaming(force: true, captureConfigurationRead: true) else {
                throw CCAPIError.invalidResponse("Canon file-naming control returned an invalid state after update.")
            }
            attempt.observe(naming)
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

    /// Explicit recovery only reads the original client's advertised resources.
    /// It cannot dispatch a write or turn observation into missing ACK evidence.
    public func refreshCaptureConfiguration(_ attempt: CameraCaptureConfigurationAttempt) async throws {
        try Task.checkCancellation()
        try await ensureInitialized()
        let outcome = attempt.snapshot
        attempt.readbackUnavailable()
        if outcome.operation == .setFileNaming {
            let naming: CameraFileNaming?
            if resolvedMode == .simulator {
                naming = try await simulatorCapabilities(captureConfigurationRead: true).fileNaming
            } else {
                naming = try await readCaptureFileNaming()
            }
            guard let naming else { throw CCAPIError.invalidResponse("File-naming readback is unavailable.") }
            try Task.checkCancellation()
            guard !closing else { throw CancellationError() }
            cachedFileNaming = naming
            fileNamingLoaded = true
            observedFeatures.insert(.fileNamingControl)
            attempt.observe(naming)
        } else if resolvedMode == .simulator {
            guard let selection = try await simulatorCapabilities(captureConfigurationRead: true).setting(Self.directorySelectionSettingKey) else {
                throw CCAPIError.invalidResponse("Capture directory readback is unavailable.")
            }
            try Task.checkCancellation()
            attempt.observe(selection)
        } else {
            guard let operations = directoryOperations() else { throw CCAPIError.unsupported(.directoryControl) }
            let raw = try await requestJSON(path: operations.read.path, timeoutInterval: 15, maximumBytes: 64 * 1024,
                                            captureConfigurationRead: true)
            guard let normalized = Self.validatedDirectorySelectionSetting(raw),
                  let selection = control(Self.directorySelectionSettingKey,
                      Self.settingLabel(Self.directorySelectionSettingKey), normalized) else {
                throw CCAPIError.invalidResponse("Capture directory readback is invalid.")
            }
            try Task.checkCancellation()
            guard !closing else { throw CancellationError() }
            cachedSettings = (cachedSettings ?? [:]).merging([Self.directorySelectionSettingKey: normalized]) { _, new in new }
            settingPaths[Self.directorySelectionSettingKey] = operations.write.path
            observedFeatures.insert(.directoryControl)
            attempt.observe(selection)
        }
    }

    private func readCaptureFileNaming() async throws -> CameraFileNaming? {
        guard let operations = fileNamingOperations() else { return nil }
        let deadline = ContinuousClock.now.advanced(by: .seconds(15))
        var responses: [CameraFileNamingField: JSONDictionary] = [:]
        for endpoint in Self.fileNamingEndpoints {
            try Task.checkCancellation()
            let duration = ContinuousClock.now.duration(to: deadline).components
            let remaining = Double(duration.seconds) + Double(duration.attoseconds) / 1e18
            guard let operation = operations[endpoint.field], remaining > 0 else {
                throw CCAPIError.invalidResponse("File-naming readback exceeded its bounded deadline.")
            }
            responses[endpoint.field] = try await requestJSON(path: operation.read.path,
                timeoutInterval: min(10, remaining), maximumBytes: 64 * 1024, captureConfigurationRead: true)
            guard ContinuousClock.now < deadline else {
                throw CCAPIError.invalidResponse("File-naming readback exceeded its bounded deadline.")
            }
        }
        guard ContinuousClock.now < deadline else {
            throw CCAPIError.invalidResponse("File-naming readback exceeded its bounded deadline.")
        }
        return Self.validatedCanonicalFileNaming(responses)
    }

    public func sleepCamera() async throws {
        try beginCameraMutation()
        guard !liveViewMaintenanceInFlight else {
            cameraMutationCount -= 1
            throw CCAPIError.invalidResponse("Camera maintenance is already in progress.")
        }
        liveViewMaintenanceInFlight = true
        defer { cameraMutationCount -= 1; liveViewMaintenanceInFlight = false }
        try await ensureInitialized()
        if resolvedMode == .simulator {
            try await requestOK(path: "/ccapi/camera-sleep", method: .post, json: [:])
            observedFeatures.insert(.cameraSleep)
            return
        }
        await stopEventPolling()
        try await retryLiveViewStop()
        try Task.checkCancellation()
        invalidateShootingSettingsCache()
        _ = try await loadShootingSettings()
        guard let cameraSleepPath else { throw CCAPIError.unsupported(.cameraSleep) }
        try await requestOK(
            path: cameraSleepPath,
            method: .put,
            json: ["value": Self.autoPowerOffImmediately],
            expectedStatusCode: 202
        )
        invalidateShootingSettingsCache()
        observedFeatures.insert(.cameraSleep)
    }

    public func cleanSensor(autoPowerOff: Bool) async throws {
        try beginCameraMutation()
        guard !liveViewMaintenanceInFlight else {
            cameraMutationCount -= 1
            throw CCAPIError.invalidResponse("Camera maintenance is already in progress.")
        }
        liveViewMaintenanceInFlight = true
        defer { cameraMutationCount -= 1; liveViewMaintenanceInFlight = false }
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
        try await retryLiveViewStop()
        try Task.checkCancellation()
        try await requestOK(
            path: operation.path,
            method: .post,
            json: ["autopoweroff": autoPowerOff],
            expectedStatusCode: 200
        )
        observedFeatures.insert(.sensorCleaning)
    }

    public func captureStill(autofocus: Bool = true) async throws -> CameraStatus {
        guard autofocus || (resolvedMode == .camera && (directShutterOperation() != nil || manualShutterOperation() != nil)) else {
            throw CCAPIError.invalidResponse("This camera session does not support a shutter request without autofocus.")
        }
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        try await ensureInitialized()
        try await refreshTemperatureStatusForRestrictedCommand()
        try requireTemperatureAllowsStillCapture()
        if resolvedMode == .simulator {
            _ = try await requestJSON(path: "/ccapi/capture/still", method: .post, json: ["af": autofocus])
            observedFeatures.insert(.stillCapture)
            do { return try await status() }
            catch {
                // This required Simulator read follows an acknowledged capture. Canon
                // camera status uses optional reads; retain that contract and cancellation.
                try Task.checkCancellation()
                if CameraConnectionFailureReason.isCancellation(error) { throw error }
                throw CCAPIError.captureStatusReadbackFailed
            }
        }

        let direct = directShutterOperation()
        let manual = manualShutterOperation()
        guard direct != nil || manual != nil else { throw CCAPIError.unsupported(.stillCapture) }
        if let direct {
            try await commandOK(operation: direct, json: ["af": autofocus])
        } else if let manual {
            try await performGuaranteedRelease(
                operation: manual, press: ["af": autofocus, "action": "full_press"],
                release: ["af": false, "action": "release"]
            )
        }
        observedFeatures.insert(.stillCapture)
        return try await status()
    }

    public func syncCameraClock() async throws -> CameraStatus {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
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

    /// Evidence-aware maintenance never bundles status, reconnect or restoration.
    public func performMaintenance(_ attempt: CameraMaintenanceAttempt) async throws {
        try attempt.validate()
        try attempt.bindOwner(maintenanceSessionOwner)
        try beginCameraMutation()
        guard !liveViewMaintenanceInFlight else {
            cameraMutationCount -= 1
            throw CCAPIError.invalidResponse("Camera maintenance is already in progress.")
        }
        liveViewMaintenanceInFlight = true
        defer { cameraMutationCount -= 1; liveViewMaintenanceInFlight = false }
        // The explicit connection flow owns discovery. Maintenance cannot hide
        // initialization or optional-status probes in its preparation phase.
        guard initialized, !closing else { throw CancellationError() }
        let intent = attempt.snapshot
        activeMaintenanceAttempt = attempt
        defer { if activeMaintenanceAttempt === attempt { activeMaintenanceAttempt = nil } }
        if intent.operation != .clockSync { maintenanceLifecycleOwned = true }
        if intent.operation == .clockSync {
            let path: String
            if resolvedMode == .simulator { path = "/ccapi/ver100/functions/datetime" }
            else {
                guard let operations = cameraClockOperations() else { throw CCAPIError.unsupported(.cameraClockSync) }
                path = operations.write.path
            }
            try attempt.configureReadPath(path)
            let requested = Date(), zone = TimeZone.current
            let epoch = Int64(requested.timeIntervalSince1970)
            attempt.reference(CameraMaintenanceClockReference(earliestEpochSeconds: epoch - 10,
                latestEpochSeconds: epoch + 10, utcOffsetMinutes: zone.secondsFromGMT(for: requested) / 60,
                daylightSaving: zone.isDaylightSavingTime(for: requested)))
            let response = try await maintenanceRequest(path: path, method: .put,
                json: ["datetime": CameraMaintenanceClock.formatter(timeZone: zone).string(from: requested),
                       "dst": zone.isDaylightSavingTime(for: requested)], expectedStatus: 200, attempt: attempt)
            guard CameraMaintenanceClock.parseCanon(try decodeMaintenanceJSONObject(response.body)) != nil else {
                throw CCAPIError.invalidResponse("Camera clock command response was invalid.")
            }
            attempt.responded()
            // Preserve response before cancellation or the independent read.
            try Task.checkCancellation()
            guard !closing else { throw CancellationError() }
            attempt.verificationUnavailable()
            try await observeMaintenance(attempt, verifyingClock: true)
        } else {
            let readPath = resolvedMode == .simulator ? "/ccapi/info"
                : operation(.get, suffix: "/deviceinformation")?.path ?? versionedPaths("/deviceinformation").first
            guard let readPath else { throw CCAPIError.invalidResponse("No required maintenance observation route.") }
            try attempt.configureReadPath(readPath)
            let path: String, method: HTTPMethod, payload: JSONDictionary, expected: Int
            if intent.operation == .sensorCleaning {
                if resolvedMode == .simulator { path = "/ccapi/sensor-cleaning"; expected = 204 }
                else {
                    guard let operation = operation(.post, suffix: "/functions/sensorcleaning") else { throw CCAPIError.unsupported(.sensorCleaning) }
                    path = operation.path; expected = 200
                }
                method = .post; payload = ["autopoweroff": intent.autoPowerOff]
            } else {
                if resolvedMode == .simulator { path = "/ccapi/camera-sleep"; method = .post; payload = [:]; expected = 204 }
                else {
                    // Freeze the previously advertised capability/path; one
                    // required strict read revalidates "immediately" before any Stop.
                    guard let frozenSleepPath = cameraSleepPath,
                          operations.contains(CCAPIOperation(method: .get, path: frozenSleepPath)),
                          operations.contains(CCAPIOperation(method: .put, path: frozenSleepPath)) else {
                        throw CCAPIError.unsupported(.cameraSleep)
                    }
                    let sleepSetting = try await maintenanceRequest(path: frozenSleepPath, method: .get,
                        json: nil, expectedStatus: 200)
                    try attempt.checkActive()
                    guard let setting = Self.validatedStringAbilitySetting(try decodeMaintenanceJSONObject(sleepSetting.body),
                            allowedValues: Self.autoPowerOffSettingValues.union([Self.autoPowerOffImmediately])),
                          setting.array("ability")?.strings.contains(Self.autoPowerOffImmediately) == true else {
                        throw CCAPIError.unsupported(.cameraSleep)
                    }
                    path = frozenSleepPath; method = .put; payload = ["value": Self.autoPowerOffImmediately]; expected = 202
                }
            }
            attempt.configureLiveViewRestoreRequest(activeLiveViewSource.map {
                LiveViewRequest(fps: requestedLiveViewRequest?.fps ?? 6, size: activeLiveViewSize, source: $0)
            }, includesSize: liveViewSizeControlSupported)
            let events = resolvedMode == .simulator ? nil : eventPollingOperations()
            maintenanceEventStopRequired = events != nil
            let needsStop = liveViewStopState().stopRequired || activeLiveViewSource != nil || events != nil
            attempt.stop(needsStop ? .pending : .notRequired)
            do {
                if let events {
                    _ = try await maintenanceRequest(path: events.stop.path, method: .delete, json: nil, expectedStatus: 204)
                    maintenanceEventStopRequired = false
                }
                try await stopMaintenanceLiveView(attempt)
                try attempt.checkActive()
                attempt.stop(needsStop ? .confirmed : .notRequired)
            } catch { attempt.stop(.unknown); throw error }
            try Task.checkCancellation()
            guard !closing else { throw CancellationError() }
            _ = try await maintenanceRequest(path: path, method: method, json: payload, expectedStatus: expected, attempt: attempt)
            attempt.responded()
            try Task.checkCancellation()
            guard !closing else { throw CancellationError() }
        }
        observedFeatures.insert(intent.operation.feature)
    }

    /// Exactly one required GET. No discovery, optional-status synthesis,
    /// command dispatch, event restart, or Live View start is allowed here.
    public func observeMaintenance(_ attempt: CameraMaintenanceAttempt, verifyingClock: Bool = false) async throws {
        try Task.checkCancellation()
        try attempt.bindOwner(maintenanceSessionOwner)
        guard initialized, !closing, let path = attempt.observationPath else { throw CancellationError() }
        attempt.observationUnavailable()
        let response = try await maintenanceRequest(path: path, method: .get, json: nil, expectedStatus: 200)
        let body = try decodeMaintenanceJSONObject(response.body)
        if attempt.snapshot.operation == .clockSync {
            guard let clock = CameraMaintenanceClock.parseCanon(body) else {
                throw CCAPIError.invalidResponse("Camera clock observation was invalid.")
            }
            attempt.observe(clock, verifying: verifyingClock)
        } else {
            let name = body[resolvedMode == .simulator ? "model" : "productname"] as? String
            let connected = body["connected"] as? NSNumber
            let simulatorReachable = resolvedMode != .simulator
                || (connected.map { CFGetTypeID($0) == CFBooleanGetTypeID() && $0.boolValue } ?? false)
            guard let name, !name.isEmpty, simulatorReachable else {
                throw CCAPIError.invalidResponse("Camera identity observation was invalid.")
            }
            attempt.reachable()
        }
        try Task.checkCancellation()
        guard !closing else { throw CancellationError() }
    }

    private func maintenanceRequest(path: String, method: HTTPMethod, json: JSONDictionary?,
        expectedStatus: Int, attempt: CameraMaintenanceAttempt? = nil) async throws -> CameraHTTPResponse {
        let request = try request(path: path, method: method, json: json, timeoutInterval: 15)
        try Task.checkCancellation()
        guard !closing else { throw CancellationError() }
        if let attempt { try requireMaintenanceMutationSafety(attempt); try attempt.markDispatched() }
        let response = try await transport.sendMaintenance(request)
        try CameraHTTPResponseIntegrity.validate(response, requestMethod: method.rawValue)
        guard response.body.count <= 64 * 1024, response.statusCode == expectedStatus,
              response.header("content-range") == nil else {
            throw CCAPIError.invalidResponse("Maintenance requires a complete expected response within 64 KiB.")
        }
        return response
    }

    private func finishMaintenanceClose() async -> CameraShutterReleaseState {
        activeMaintenanceAttempt?.cancel()
        // Restored controls may have acquired Bulb/held-AF responsibilities. Keep
        // their original owner cleanup before retiring any local resources.
        if let start = bulbStartTask { start.cancel(); _ = await start.result }
        if let control = shortControlTask { control.cancel(); _ = await control.result }
        try? await releasePendingShutter()
        if let start = liveViewStartTask { start.cancel(); _ = await start.result }
        if let stop = liveViewStopTask { _ = await stop.result }
        // A failed physical release must not be bypassed by Live View/event Stop.
        let releaseSafe = pendingShutterRelease == nil && !shutterReleaseUnconfirmed
        let cleanup = CameraMaintenanceAttempt(operation: .sensorCleaning)
        if releaseSafe, maintenanceEventStopRequired, resolvedMode != .simulator, let events = eventPollingOperations() {
            do {
                let request = try request(path: events.stop.path, method: .delete, timeoutInterval: 15)
                _ = try await maintenanceLiveViewTransfer(request, expectedStatus: 204, attempt: cleanup, allowClosing: true)
                maintenanceEventStopRequired = false
            } catch { liveViewStopUnconfirmed = true }
        }
        if releaseSafe, pendingLiveViewStop != nil {
            do { try await stopMaintenanceLiveView(cleanup, allowClosing: true) }
            catch { liveViewStopUnconfirmed = true }
        }
        // Local resources retire even if another control is still settling or its
        // Stop failed. No ordinary Stop/event helper is a fallback on this path.
        let native = rtpSession; rtpSession = nil
        multipartSession?.close(); multipartSession = nil; pendingMultipartFrame = nil; pendingJPEGFrame = nil
        activeLiveViewSource = nil; latestLiveViewGeometry = nil
        if let native { await native.close() }
        retiredLiveViewSource = pendingLiveViewStop?.source
        if pendingLiveViewStop != nil || maintenanceEventStopRequired { liveViewStopUnconfirmed = true }
        pendingLiveViewStop = nil; liveViewStopRevision = UUID()
        let unconfirmed = pendingShutterRelease != nil || shutterReleaseUnconfirmed
        pendingShutterRelease = nil; bulbExposureActive = false; shutterReleaseUnconfirmed = unconfirmed
        return CameraShutterReleaseState(releaseRequired: false, releaseUnconfirmed: unconfirmed,
                                        bulbExposureActive: unconfirmed ? nil : false)
    }

    private func maintenanceLiveViewTransfer(_ captured: URLRequest, expectedStatus: Int,
                                              attempt: CameraMaintenanceAttempt, allowClosing: Bool = false) async throws -> CameraHTTPResponse {
        var request = captured
        request.timeoutInterval = 15
        try attempt.checkActive()
        guard !closing || allowClosing else { throw CancellationError() }
        let response = try await transport.sendMaintenance(request)
        try CameraHTTPResponseIntegrity.validate(response, requestMethod: request.httpMethod ?? "POST")
        guard response.statusCode == expectedStatus, response.body.count <= 64 * 1024,
              response.header("content-range") == nil else {
            throw CCAPIError.invalidResponse("Maintenance Live View control was not confirmed.")
        }
        try attempt.checkActive()
        guard !closing || allowClosing else { throw CancellationError() }
        return response
    }

    private func stopMaintenanceLiveView(_ attempt: CameraMaintenanceAttempt, allowClosing: Bool = false) async throws {
        try attempt.checkActive()
        guard (!closing || allowClosing), maintenanceLiveViewControlID == nil, liveViewStartTask == nil,
              liveViewStopTask == nil, liveViewReleaseTask == nil else { throw CCAPIError.liveViewStopUnconfirmed }
        guard var stop = pendingLiveViewStop else {
            if resolvedMode == .simulator { activeLiveViewSource = nil }
            return
        }
        stop.maintenanceOwned = true; pendingLiveViewStop = stop
        let id = UUID(), revision = liveViewStopRevision
        maintenanceLiveViewControlID = id
        defer { if maintenanceLiveViewControlID == id { maintenanceLiveViewControlID = nil } }
        let native = rtpSession
        rtpSession = nil; multipartSession?.close(); multipartSession = nil
        pendingMultipartFrame = nil; pendingJPEGFrame = nil; activeLiveViewSource = nil; latestLiveViewGeometry = nil
        if let native { await native.close() }
        do {
            if let closeStream = stop.closeStream {
                _ = try await maintenanceLiveViewTransfer(closeStream, expectedStatus: 204, attempt: attempt, allowClosing: allowClosing)
            }
            _ = try await maintenanceLiveViewTransfer(stop.stop,
                expectedStatus: stop.stop.httpMethod == "DELETE" ? 204 : 200, attempt: attempt, allowClosing: allowClosing)
            guard (!closing || allowClosing), maintenanceLiveViewControlID == id, pendingLiveViewStop?.id == stop.id,
                  liveViewStopRevision == revision else { throw CCAPIError.liveViewStopUnconfirmed }
            pendingLiveViewStop = nil; liveViewStopUnconfirmed = false; liveViewStopRevision = UUID()
        } catch {
            if pendingLiveViewStop?.id == stop.id {
                liveViewStopUnconfirmed = true; liveViewStopRevision = UUID()
            }
            throw error
        }
    }

    /// Restores only the exact source and effective size captured before Stop.
    /// No discovery, AUTO/size fallback, detached cleanup, or command retry.
    public func restoreMaintenanceLiveView(_ attempt: CameraMaintenanceAttempt) async throws {
        try attempt.bindOwner(maintenanceSessionOwner)
        try attempt.validateRestore()
        activeMaintenanceAttempt = attempt
        defer { if activeMaintenanceAttempt === attempt { activeMaintenanceAttempt = nil } }
        guard initialized, !closing, !liveViewMaintenanceInFlight, maintenanceLiveViewControlID == nil,
              liveViewStartTask == nil, liveViewStopTask == nil, liveViewReleaseTask == nil,
              pendingLiveViewStop == nil, !liveViewStopUnconfirmed,
              let frozen = attempt.liveViewRestoreRequest, frozen.source != .auto else {
            throw CCAPIError.liveViewStopUnconfirmed
        }
        try beginCameraMutation()
        let id = UUID()
        maintenanceLiveViewControlID = id; liveViewMaintenanceInFlight = true
        defer {
            cameraMutationCount -= 1
            if maintenanceLiveViewControlID == id { maintenanceLiveViewControlID = nil; liveViewMaintenanceInFlight = false }
        }
        try requireTemperatureAllowsLiveView()
        try checkMaintenanceLiveViewOwner(id, attempt: attempt)
        if resolvedMode == .simulator {
            try requireMaintenanceMutationSafety(attempt)
            guard frozen.source == .simulatorFrame else { throw CCAPIError.unsupported(.liveView) }
            activeLiveViewSource = .simulatorFrame; requestedLiveViewRequest = frozen; return
        }
        var stop: LiveViewStopObligation
        let start: URLRequest
        var multipartRead: URLRequest?
        var candidateNative: (any CCAPIRTPSession)?
        var candidateMultipart: CCAPIMultipartLiveViewSession?
        var openedStream: CameraHTTPStreamResponse?
        let liveViewPayload: JSONDictionary = attempt.liveViewRestoreIncludesSize
            ? ["cameradisplay": "on", "liveviewsize": frozen.size.rawValue] : ["cameradisplay": "on"]
        switch frozen.source {
        case .ccapiJPEGPolling:
            guard let operations = jpegLiveViewOperations() else { throw CCAPIError.unsupported(.liveView) }
            stop = try makeLiveViewStop(source: frozen.source, operation: operations.stop)
            start = try request(path: operations.start.path, method: .post,
                json: liveViewPayload, timeoutInterval: 15)
        case .ccapiMultipart:
            guard let operations = multipartLiveViewOperations() else { throw CCAPIError.unsupported(.liveViewMultipart) }
            stop = try makeLiveViewStop(source: frozen.source, operation: operations.stopLiveView, closeStream: operations.closeStream)
            start = try request(path: operations.startLiveView.path, method: .post,
                json: liveViewPayload, timeoutInterval: 15)
            var read = try request(path: operations.openStream.path, method: .get, timeoutInterval: 15)
            read.setValue("multipart/x-mixed-replace", forHTTPHeaderField: "Accept")
            multipartRead = read
        case .ccapiRTP:
            guard supportsRTPLiveView(), let rtpDestinationAddress, let rtpSessionFactory else {
                throw CCAPIError.unsupported(.liveViewRTP)
            }
            let descriptionRequest = try request(path: apiPath(.get, suffix: "/shooting/liveview/rtpsessiondesc"), method: .get, timeoutInterval: 15)
            let response = try await maintenanceLiveViewTransfer(descriptionRequest, expectedStatus: 200, attempt: attempt)
            guard let text = String(data: response.body, encoding: .utf8), response.body.count <= Self.maximumRTPSessionDescriptionBytes else {
                throw CCAPIError.invalidResponse("Invalid RTP description.")
            }
            let description = try CCAPIRTPSessionDescriptionParser.parse(text)
            let path = apiPath(.post, suffix: "/shooting/liveview/rtp")
            stop = try makeLiveViewStop(source: frozen.source, operation: CCAPIOperation(method: .post, path: path))
            start = try request(path: path, method: .post,
                json: ["action": "start", "ipaddress": rtpDestinationAddress], timeoutInterval: 15)
            candidateNative = try await rtpSessionFactory.makeSession(description: description, destinationAddress: rtpDestinationAddress)
        default: throw CCAPIError.unsupported(.liveView)
        }
        do {
            try checkMaintenanceLiveViewOwner(id, attempt: attempt)
            if let native = candidateNative {
                await native.setTargetFPS(frozen.fps)
                try checkMaintenanceLiveViewOwner(id, attempt: attempt)
                try await native.start()
                try checkMaintenanceLiveViewOwner(id, attempt: attempt)
            }
            try requireMaintenanceMutationSafety(attempt)
            // Retain cleanup responsibility before dispatch, including lost/cancelled Start.
            stop.maintenanceOwned = true
            pendingLiveViewStop = stop; liveViewStopRevision = UUID()
            let revision = liveViewStopRevision
            _ = try await maintenanceLiveViewTransfer(start, expectedStatus: 200, attempt: attempt)
            if let native = candidateNative {
                try await native.waitUntilReady()
                try checkMaintenanceLiveViewOwner(id, attempt: attempt)
            }
            if frozen.source == .ccapiJPEGPolling {
                pendingJPEGFrame = try await firstJPEGFrame(owner: stop.id, maintenance: true)
                try checkMaintenanceLiveViewOwner(id, attempt: attempt)
            }
            if let read = multipartRead {
                let readinessDeadline = ContinuousClock.now.advanced(by: .seconds(15))
                let stream = try await transport.openMaintenanceStream(read)
                openedStream = stream
                try checkMaintenanceLiveViewOwner(id, attempt: attempt)
                guard stream.statusCode == 200 else { throw CCAPIError.liveViewStopUnconfirmed }
                let boundary = try CCAPIMultipartLiveView.boundary(from: stream.header("content-type"))
                let session = CCAPIMultipartLiveViewSession(response: stream, boundary: boundary)
                candidateMultipart = session; openedStream = nil
                let frame = try await Self.maintenanceFirstFrame(session, deadline: readinessDeadline)
                try validateLiveViewJPEG(frame)
                try session.checkUsable()
                try checkMaintenanceLiveViewOwner(id, attempt: attempt)
                pendingMultipartFrame = frame
            }
            try checkMaintenanceLiveViewOwner(id, attempt: attempt)
            try requireMaintenanceMutationSafety(attempt)
            guard pendingLiveViewStop?.id == stop.id, liveViewStopRevision == revision, !liveViewStopUnconfirmed else {
                throw CCAPIError.liveViewStopUnconfirmed
            }
            rtpSession = candidateNative; multipartSession = candidateMultipart
            candidateNative = nil; candidateMultipart = nil
            activeLiveViewSource = frozen.source; activeLiveViewSize = frozen.size
            requestedLiveViewRequest = frozen; liveViewSizeFallbackAttempted = true
            latestLiveViewGeometry = nil
        } catch {
            openedStream?.cancel(); candidateMultipart?.close()
            if let native = candidateNative { await native.close() }
            if pendingLiveViewStop?.id == stop.id {
                liveViewStopUnconfirmed = true; liveViewStopRevision = UUID(); activeLiveViewSource = nil
            }
            // Cancellation/lost Start never sends hidden compensating mutations.
            throw error
        }
    }

    private func requireMaintenanceMutationSafety(_ attempt: CameraMaintenanceAttempt) throws {
        try attempt.checkMutationSafety()
        guard recording != true, recordingCommandEvidence == nil,
              pendingShutterRelease == nil, !bulbExposureActive, !shutterReleaseUnconfirmed,
              bulbStartTask == nil, shortControlTask == nil, shutterReleaseTask == nil else {
            throw CCAPIError.invalidResponse("Recording or shutter recovery blocks camera maintenance.")
        }
    }

    private func checkMaintenanceLiveViewOwner(_ id: UUID, attempt: CameraMaintenanceAttempt) throws {
        try attempt.checkActive()
        guard !closing, maintenanceLiveViewControlID == id, liveViewMaintenanceInFlight else { throw CancellationError() }
    }

    private static func maintenanceFirstFrame(_ session: CCAPIMultipartLiveViewSession, deadline: ContinuousClock.Instant) async throws -> Data {
        try await withTaskCancellationHandler {
            try await withThrowingTaskGroup(of: Data.self) { group in
                group.addTask { try await session.validatedFirstFrame() }
                group.addTask {
                    try await ContinuousClock().sleep(until: deadline)
                    session.close()
                    throw URLError(.timedOut)
                }
                defer { group.cancelAll() }
                guard let frame = try await group.next() else { throw CancellationError() }
                return frame
            }
        } onCancel: { session.close() }
    }

    public func halfPressShutter() async throws -> CameraStatus {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        try await ensureInitialized()
        if resolvedMode == .simulator {
            try await performGuaranteedRelease(
                operation: CCAPIOperation(method: .post, path: "/ccapi/shutter/half-press"),
                press: [:], release: [:], releasePath: "/ccapi/shutter/release",
                holdNanoseconds: Self.halfPressNanoseconds
            )
            observedFeatures.insert(.shutterHalfPress)
            return try await status()
        }
        guard let manual = manualShutterOperation() else {
            throw CCAPIError.unsupported(.shutterHalfPress)
        }
        try await performGuaranteedRelease(
            operation: manual, press: ["af": true, "action": "half_press"],
            release: ["af": false, "action": "release"],
            holdNanoseconds: Self.halfPressNanoseconds
        )
        observedFeatures.insert(.shutterHalfPress)
        return try await status()
    }

    public func startBulbExposure() async throws -> CameraStatus {
        try Task.checkCancellation()
        guard !closing else { throw CCAPIError.invalidResponse("This camera connection is closing.") }
        guard bulbStartTask == nil, shortControlTask == nil, shutterReleaseTask == nil, pendingShutterRelease == nil,
              cameraMutationCount == 0 else {
            throw CCAPIError.invalidResponse("Release the shutter before starting another camera operation.")
        }
        // Reserving before the first await also excludes two concurrent start preflights.
        let id = UUID()
        bulbStartID = id
        let task = Task { try await self.performBulbStart(id: id) }
        bulbStartTask = task
        return try await withTaskCancellationHandler {
            try await task.value
        } onCancel: {
            task.cancel()
        }
    }

    private func performBulbStart(id: UUID) async throws -> CameraStatus {
        defer {
            if bulbStartID == id {
                bulbStartTask = nil
                bulbStartID = nil
            }
        }
        try await ensureInitialized()
        let baseline = try await status()
        guard pendingShutterRelease == nil else {
            throw CCAPIError.invalidResponse("Release the existing shutter exposure before starting again.")
        }
        try requireTemperatureAllowsStillCapture()
        try Task.checkCancellation()
        guard !closing, bulbStartID == id else { throw CancellationError() }
        let simulator = resolvedMode == .simulator
        let operation: CCAPIOperation
        if simulator {
            operation = CCAPIOperation(method: .post, path: "/ccapi/bulb/stop")
        } else if let manual = manualShutterOperation() {
            operation = manual
        } else {
            throw CCAPIError.unsupported(.bulbExposure)
        }
        let release = ShutterRelease(
            id: id, operation: operation, simulator: simulator,
            payload: try encodeJSONObject(simulator ? [:] : ["af": false, "action": "release"])
        )
        pendingShutterRelease = release
        bulbStateAuthoritative = true
        bulbStartAcknowledged = false
        shutterReleaseUnconfirmed = true
        do {
            try await sendOwnedShutterCommand(release, start: true)
            bulbStartAcknowledged = true
            // A cancelled/closing caller still owes release after a late successful ACK.
            try Task.checkCancellation()
            guard !closing, pendingShutterRelease?.id == id else { throw CancellationError() }
            bulbExposureActive = true
            shutterReleaseUnconfirmed = false
            return baseline.withShutterReleaseState(shutterReleaseState())
        } catch {
            let primary = error
            do {
                try await releasePendingShutter()
            } catch {
                throw CCAPIError.operationAndReleaseFailed(
                    operation: primary.localizedDescription,
                    release: error.localizedDescription
                )
            }
            throw primary
        }
    }

    public func stopBulbExposure() async throws -> CameraStatus {
        try await retryShutterRelease()
        return try await status()
    }

    /// Stop-only; its successful return confirms release independently of status refresh.
    public func retryShutterRelease() async throws {
        guard !closing else { throw CCAPIError.invalidResponse("This camera connection is closing.") }
        // Bind before awaiting: an old Stop must never cancel or release a newer owner.
        guard let owner = pendingShutterRelease?.id ?? shortControlID ?? bulbStartID else { return }
        let startingBulb = bulbStartTask
        let startingShortControl = shortControlTask
        if let startingBulb {
            startingBulb.cancel()
            let result = await startingBulb.result
            guard !closing else { throw CCAPIError.invalidResponse("This camera connection is closing.") }
            // A cancelled dispatched Start owns its one compensation attempt.
            // Do not silently repeat a failed release in this same Stop action.
            if case let .failure(error) = result, pendingShutterRelease?.id == owner { throw error }
        }
        if let startingShortControl {
            startingShortControl.cancel()
            let result = await startingShortControl.result
            guard !closing else { throw CCAPIError.invalidResponse("This camera connection is closing.") }
            if case let .failure(error) = result, pendingShutterRelease?.id == owner { throw error }
        }
        guard !closing else { throw CCAPIError.invalidResponse("This camera connection is closing.") }
        guard pendingShutterRelease?.id == owner else { return }
        try await releasePendingShutter()
    }

    private func releasePendingShutter() async throws {
        if let shutterReleaseTask {
            try await shutterReleaseTask.value
            return
        }
        guard let release = pendingShutterRelease else { return }
        // This task is owned by the connection, not the cancelled UI caller.
        let task = Task.detached(priority: .userInitiated) { try await self.performShutterRelease(release) }
        shutterReleaseTask = task
        defer { shutterReleaseTask = nil }
        try await task.value
    }

    private func performShutterRelease(_ release: ShutterRelease) async throws {
        guard pendingShutterRelease?.id == release.id else { return }
        do {
            try await sendOwnedShutterCommand(release, start: false)
            guard pendingShutterRelease?.id == release.id else { return }
            pendingShutterRelease = nil
            bulbExposureActive = false
            shutterReleaseUnconfirmed = false
            if bulbStartAcknowledged { observedFeatures.insert(.bulbExposure) }
            bulbStartAcknowledged = false
        } catch {
            if pendingShutterRelease?.id == release.id {
                bulbExposureActive = false
                shutterReleaseUnconfirmed = true
            }
            throw error
        }
    }

    private func sendOwnedShutterCommand(_ release: ShutterRelease, start: Bool) async throws {
        // The owned command deliberately bypasses the ordinary mutation interlock.
        // No alternate method, endpoint, or press retry is attempted here.
        let path = start && release.simulator ? "/ccapi/bulb/start" : release.operation.path
        guard let url = URL(string: baseURLString + path) else {
            throw CCAPIError.invalidResponse("Invalid saved shutter endpoint.")
        }
        var request = request(url: url, method: release.operation.method)
        if start {
            request.httpBody = try encodeJSONObject(release.simulator ? [:] : ["af": false, "action": "full_press"])
        } else {
            request.httpBody = release.payload
        }
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        let response: CameraHTTPResponse
        if maintenanceLifecycleOwned && closing {
            request.timeoutInterval = 15
            response = try await transport.sendMaintenance(request)
        } else {
            response = try await transport.send(request)
        }
        try validate(response, request: request)
        if release.simulator { _ = try decodeJSONObject(response.body) }
    }

    /// The caller creates one owner for one physical press, then releases that exact owner.
    /// Unlike bounded autofocus, this requires Canon's advertised AF start/stop operation.
    public func startHeldAutofocus(owner: UUID) async throws {
        try Task.checkCancellation()
        guard !closing, shortControlTask == nil, bulbStartTask == nil,
              shutterReleaseTask == nil, pendingShutterRelease == nil, cameraMutationCount == 0 else {
            throw CCAPIError.invalidResponse("Release the shutter before another camera operation.")
        }
        shortControlID = owner
        let task = Task { try await self.performHeldAutofocusStart(owner: owner) }
        shortControlTask = task
        try await withTaskCancellationHandler {
            try await task.value
        } onCancel: { task.cancel() }
    }

    private func performHeldAutofocusStart(owner: UUID) async throws {
        defer {
            if shortControlID == owner { shortControlID = nil; shortControlTask = nil }
        }
        try await ensureInitialized()
        try Task.checkCancellation()
        guard !closing, shortControlID == owner else { throw CancellationError() }
        guard resolvedMode == .camera, let operation = autofocusOperation() else {
            throw CCAPIError.unsupported(.autofocus)
        }
        guard let url = URL(string: baseURLString + operation.path) else {
            throw CCAPIError.invalidResponse("Invalid advertised autofocus endpoint.")
        }
        var press = request(url: url, method: operation.method)
        press.httpBody = try encodeJSONObject(["action": "start"])
        press.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        pendingShutterRelease = ShutterRelease(id: owner, operation: operation, simulator: false,
            payload: try encodeJSONObject(["action": "stop"]))
        shutterReleaseUnconfirmed = true
        do {
            let response = try await transport.send(press)
            try validate(response, request: press)
            try Task.checkCancellation()
            guard !closing, pendingShutterRelease?.id == owner else { throw CancellationError() }
            shutterReleaseUnconfirmed = false
            observedFeatures.insert(.autofocus)
        } catch {
            let primary = error
            do { try await releasePendingShutter() }
            catch { throw CCAPIError.operationAndReleaseFailed(operation: primary.localizedDescription, release: error.localizedDescription) }
            throw primary
        }
    }

    public func stopHeldAutofocus(owner: UUID) async throws {
        // Bind before every suspension: a late release event cannot affect a newer press.
        guard pendingShutterRelease?.id == owner || shortControlID == owner else { return }
        if let start = shortControlTask, shortControlID == owner {
            start.cancel()
            let result = await start.result
            if case let .failure(error) = result, pendingShutterRelease?.id == owner { throw error }
        }
        guard pendingShutterRelease?.id == owner else { return }
        try await releasePendingShutter()
    }

    public func liveViewFocusInfo() async throws -> CameraFocusInfo? {
        try Task.checkCancellation()
        guard !closing, resolvedMode == .camera, activeLiveViewSource != nil,
              let operation = detailedLiveViewOperation() else { return nil }
        let liveViewOwner = pendingLiveViewStop?.id
        let geometryOwner = liveViewGeometryOwner
        guard liveViewGeometryIsCurrent(geometryOwner) else { return nil }
        var read = try request(path: operation.path + "?kind=info", method: .get, timeoutInterval: 2)
        read.setValue("application/octet-stream", forHTTPHeaderField: "Accept")
        read.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let data = try await CameraFocusMetadata.boundedRead(read, transport: transport)
        guard !closing, activeLiveViewSource != nil, pendingLiveViewStop?.id == liveViewOwner else { return nil }
        guard liveViewGeometryIsCurrent(geometryOwner) else { return nil }
        return try CameraFocusMetadata.canonPacket(data)
    }

    public func autofocus() async throws -> CameraStatus {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        try await ensureInitialized()
        if resolvedMode == .simulator {
            try await performGuaranteedRelease(
                operation: CCAPIOperation(method: .post, path: "/ccapi/shutter/half-press"),
                press: [:], release: [:], releasePath: "/ccapi/shutter/release",
                holdNanoseconds: Self.halfPressNanoseconds
            )
            observedFeatures.insert(.autofocus)
            return try await status()
        }
        if let operation = autofocusOperation() {
            try await performGuaranteedRelease(
                operation: operation, press: ["action": "start"], release: ["action": "stop"],
                holdNanoseconds: Self.halfPressNanoseconds
            )
        } else if let manual = manualShutterOperation() {
            try await performGuaranteedRelease(
                operation: manual, press: ["af": true, "action": "half_press"],
                release: ["af": false, "action": "release"],
                holdNanoseconds: Self.halfPressNanoseconds
            )
        } else {
            throw CCAPIError.unsupported(.autofocus)
        }
        observedFeatures.insert(.autofocus)
        return try await status()
    }

    public func startRecording() async throws -> CameraStatus {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        return try await setRecording(true)
    }

    public func stopRecording() async throws -> CameraStatus {
        try await setRecording(false)
    }

    public func tapFocus(x: Double, y: Double) async throws -> FocusResult {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        let geometryOwner = liveViewGeometryOwner
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
        try await ensureLiveViewGeometryForNativeStream(owner: geometryOwner)
        let position = try cameraLiveViewPosition(x: x, y: y, feature: .tapFocus)
        try await sendCoordinateCommand(
            operation: operation, position: position, owner: geometryOwner
        )
        observedFeatures.insert(.tapFocus)
        return FocusResult(accepted: true, x: x, y: y)
    }

    public func clickWhiteBalance(x: Double, y: Double) async throws -> CameraStatus {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        let geometryOwner = liveViewGeometryOwner
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
        try await ensureLiveViewGeometryForNativeStream(owner: geometryOwner)
        let position = try cameraLiveViewPosition(x: x, y: y, feature: .clickWhiteBalance)
        try await sendCoordinateCommand(
            operation: operation, position: position, owner: geometryOwner
        )
        observedFeatures.insert(.clickWhiteBalance)
        let status = try await status()
        try requireLiveViewGeometryOwner(geometryOwner)
        return status
    }

    public func driveFocus(
        direction: FocusDriveDirection,
        step: FocusDriveStep
    ) async throws -> FocusDriveResult {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
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
        try beginCameraMutation()
        guard !liveViewMaintenanceInFlight, liveViewStartTask == nil, liveViewStopTask == nil, pendingLiveViewStop == nil else {
            cameraMutationCount -= 1
            throw CCAPIError.liveViewStopUnconfirmed
        }
        let id = UUID()
        liveViewStartID = id
        let task = Task { try await self.performLiveViewStart(request, id: id) }
        liveViewStartTask = task
        try await withTaskCancellationHandler {
            try await task.value
        } onCancel: {
            task.cancel()
        }
    }

    private func performLiveViewStart(_ request: LiveViewRequest, id: UUID) async throws {
        defer {
            if liveViewStartID == id {
                liveViewStartID = nil
                liveViewStartTask = nil
                cameraMutationCount -= 1
            }
        }
        try await ensureInitialized()
        try await refreshTemperatureStatusForRestrictedCommand()
        try checkLiveViewStart()
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
                let primary = error
                let admitted = pendingLiveViewStop != nil
                do {
                    guard !liveViewStopUnconfirmed else { throw CCAPIError.liveViewStopUnconfirmed }
                    try await releasePendingLiveView()
                } catch {
                    if CameraConnectionFailureReason.isCancellation(primary) { throw primary }
                    try Task.checkCancellation()
                    throw CCAPIError.liveViewStopUnconfirmed
                }
                if CameraConnectionFailureReason.isCancellation(primary) { throw primary }
                try checkLiveViewStart()
                lastError = primary
                let fallback = CameraLiveViewLifecyclePolicy.evaluate([
                    "sameOwner": !closing, "sameAttempt": liveViewStartID == id,
                    "cancelled": Task.isCancelled, "retired": closing,
                    "foregroundRequired": false, "foreground": true,
                    "startAdmitted": admitted, "startAcknowledged": false,
                    "stopEvidence": admitted ? "confirmed" : "not-needed",
                    "sourceMode": request.source == .auto ? "AUTO" : "explicit",
                    "terminal": true, "frameEvidence": "none"])
                guard fallback["canFallback"] == true else { throw primary }
            }
        }
        throw lastError ?? CCAPIError.unsupported(.liveView)
    }

    private enum FirstJPEGFrameFailure: LocalizedError {
        case sizeUnsupported
        var errorDescription: String? { "Canon JPEG Live View did not support the requested image size." }
    }

    private func startJPEGLiveView(_ request: LiveViewRequest) async throws {
        guard let operations = jpegLiveViewOperations() else { throw CCAPIError.unsupported(.liveView) }
        var candidate = request
        while true {
            let stop = try makeLiveViewStop(source: .ccapiJPEGPolling, operation: operations.stop)
            try await startCCAPILiveView(candidate, path: operations.start.path, stop: stop)
            pendingMultipartFrame = nil; pendingJPEGFrame = nil
            do {
                // Never call public Start/Stop from the current Start task.
                let first = try await firstJPEGFrame(owner: stop.id)
                try checkLiveViewStart()
                try requireLiveViewInputReady(owner: stop.id, decoded: decodedJPEGValidator != nil)
                pendingJPEGFrame = first
                activeLiveViewSource = .ccapiJPEGPolling
                return
            } catch FirstJPEGFrameFailure.sizeUnsupported {
                guard !liveViewSizeFallbackAttempted, activeLiveViewSize != .small else { throw FirstJPEGFrameFailure.sizeUnsupported }
                let rejectedSize = activeLiveViewSize
                // This is a new size attempt, only after the captured original Stop ACK.
                // Unknown cleanup escapes to the outer handler and cannot replay Start.
                try await releasePendingLiveView()
                try checkLiveViewStart()
                liveViewSizeFallbackAttempted = true
                rejectedLiveViewSizes.insert(rejectedSize)
                candidate = LiveViewRequest(fps: request.fps, size: .small, source: request.source)
            }
        }
    }

    private func startCCAPILiveView(_ request: LiveViewRequest, path: String, stop: LiveViewStopObligation) async throws {
        var effectiveSize = request.size
        do {
            try await sendLiveViewStart(stop: stop,
                path: path,
                method: .post,
                json: ["cameradisplay": "on", "liveviewsize": request.size.rawValue]
            )
            liveViewSizeControlSupported = true
        } catch let error as CCAPIError {
            guard case let .http(statusCode, _, _, _) = error, statusCode == 400 else { throw error }
            do {
                try await sendLiveViewStart(stop: stop, path: path, method: .post, json: ["cameradisplay": "on"])
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
                        try await sendLiveViewStart(stop: stop,
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
        let stop = try makeLiveViewStop(source: .ccapiMultipart, operation: operations.stopLiveView,
                                       closeStream: operations.closeStream)
        try await startCCAPILiveView(request, path: operations.startLiveView.path, stop: stop)
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
            try checkLiveViewStart()
            let boundary = try CCAPIMultipartLiveView.boundary(from: response.header("content-type"))
            let session = CCAPIMultipartLiveViewSession(response: response, boundary: boundary)
            candidateSession = session
            stream = nil
            let firstFrame = try await session.validatedFirstFrame()
            try validateLiveViewJPEG(firstFrame)
            try session.checkUsable()
            try checkLiveViewStart()
            try requireLiveViewInputReady(owner: stop.id, decoded: decodedJPEGValidator != nil)
            multipartSession?.close()
            multipartSession = session
            candidateSession = nil
            pendingMultipartFrame = firstFrame
            activeLiveViewSource = .ccapiMultipart
            observedFeatures.formUnion([.liveView, .liveViewMultipart])
        } catch {
            candidateSession?.close()
            stream?.cancel()
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
        try? await retryLiveViewStop()
    }

    public func liveViewStopState() -> CameraLiveViewStopState {
        CameraLiveViewStopState(stopRequired: pendingLiveViewStop != nil,
            stopUnconfirmed: liveViewStopUnconfirmed,
            source: pendingLiveViewStop?.source ?? retiredLiveViewSource)
    }

    /// Stop only. A failed acknowledgement retains the original request for a later retry.
    public func retryLiveViewStop() async throws {
        guard maintenanceLiveViewControlID == nil else { throw CCAPIError.liveViewStopUnconfirmed }
        if pendingLiveViewStop?.maintenanceOwned == true {
            return try await stopMaintenanceLiveView(CameraMaintenanceAttempt(operation: .sensorCleaning))
        }
        if let task = liveViewStopTask { return try await task.value }
        let task = Task.detached { try await self.performLiveViewStop() }
        liveViewStopTask = task
        defer { liveViewStopTask = nil }
        try await task.value
    }

    private func performLiveViewStop() async throws {
        if let start = liveViewStartTask {
            start.cancel()
            _ = await start.result
            // The interrupted Start already attempted compensation. One Stop action
            // must expose its failure, not silently send a second retry.
            if liveViewStopUnconfirmed { throw CCAPIError.liveViewStopUnconfirmed }
        }
        latestLiveViewGeometry = nil
        if resolvedMode == .simulator { activeLiveViewSource = nil }
        try await releasePendingLiveView()
    }

    private func checkLiveViewStart() throws {
        try Task.checkCancellation()
        guard !closing, liveViewStartID != nil, liveViewStopTask == nil else { throw CancellationError() }
    }

    private func makeLiveViewStop(source: LiveViewSource, operation: CCAPIOperation,
                                  closeStream: CCAPIOperation? = nil) throws -> LiveViewStopObligation {
        let payload: JSONDictionary? = operation.method == .delete ? nil :
            (source == .ccapiRTP ? ["action": "stop", "ipaddress": ""] :
                ["liveviewsize": "off", "cameradisplay": "on"])
        return LiveViewStopObligation(id: UUID(), source: source,
            stop: try request(path: operation.path, method: operation.method, json: payload),
            closeStream: try closeStream.map { try request(path: $0.path, method: $0.method) })
    }

    private func sendLiveViewStart(stop: LiveViewStopObligation, path: String,
                                   method: HTTPMethod, json: JSONDictionary) async throws {
        // Construction/interlocks precede responsibility; no await separates ownership
        // from dispatch. All retry commands use this exact session's captured stop.
        let start = try request(path: path, method: method, json: json)
        try checkLiveViewStart()
        guard pendingLiveViewStop == nil || pendingLiveViewStop?.id == stop.id else {
            throw CCAPIError.liveViewStopUnconfirmed
        }
        pendingLiveViewStop = stop
        liveViewStopRevision = UUID()
        let response = try await transport.send(start)
        try validate(response, request: start)
        try checkLiveViewStart()
    }

    private func releasePendingLiveView() async throws {
        guard maintenanceLiveViewControlID == nil else { throw CCAPIError.liveViewStopUnconfirmed }
        if pendingLiveViewStop?.maintenanceOwned == true {
            return try await stopMaintenanceLiveView(CameraMaintenanceAttempt(operation: .sensorCleaning))
        }
        if let task = liveViewReleaseTask { return try await task.value }
        guard let stop = pendingLiveViewStop else { return }
        let task = Task.detached { try await self.sendLiveViewStop(stop) }
        liveViewReleaseTask = task
        defer { liveViewReleaseTask = nil }
        try await task.value
    }

    /// Local-only retirement is safe even when another physical control defers
    /// camera Stop. No acknowledgement is invented and the frozen duty survives.
    public func retireLiveViewReader() async {
        liveViewStartTask?.cancel()
        await closeLiveViewResources()
    }

    private func closeLiveViewResources() async {
        let native = rtpSession
        rtpSession = nil
        multipartSession?.close()
        multipartSession = nil
        pendingMultipartFrame = nil; pendingJPEGFrame = nil
        activeLiveViewSource = nil
        latestLiveViewGeometry = nil
        if let native { await native.close() }
    }

    private func sendLiveViewStop(_ stop: LiveViewStopObligation) async throws {
        // Close local resources even if the wire acknowledgement is lost.
        await closeLiveViewResources()
        // Reader DELETE is best effort; only the general Live View stop discharges
        // multipart responsibility, including firmware which returns reader 503.
        if let closeStream = stop.closeStream { _ = try? await transport.send(closeStream) }
        do {
            let response = try await transport.send(stop.stop)
            try validate(response, request: stop.stop)
            guard pendingLiveViewStop?.id == stop.id else { return }
            pendingLiveViewStop = nil
            liveViewStopUnconfirmed = false
        } catch {
            if pendingLiveViewStop?.id == stop.id { liveViewStopUnconfirmed = true; liveViewStopRevision = UUID() }
            throw CCAPIError.liveViewStopUnconfirmed
        }
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
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
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
        guard !liveViewMagnificationIsCurrent else {
            throw CCAPIError.invalidResponse("Canon Live View magnification is already changing.")
        }
        // Admission revokes old crop coordinates before PUT, including uncertain
        // outcomes. Acknowledgement/readback is never a replacement geometry sample.
        invalidateLiveViewGeometry()
        let owner = liveViewGeometryOwner, attempt = UUID()
        liveViewMagnificationAttempt = attempt
        liveViewMagnificationOwner = owner
        defer {
            if liveViewMagnificationAttempt == attempt {
                // Also discard reads/frames admitted while PUT/readback was in flight.
                // A retired attempt cannot clear a replacement session's geometry.
                if liveViewGeometryIsCurrent(owner, allowMagnification: true) {
                    invalidateLiveViewGeometry()
                }
                liveViewMagnificationAttempt = nil
                liveViewMagnificationOwner = nil
            }
        }
        currentLiveViewMagnification = nil
        try requireLiveViewGeometryOwner(owner, allowMagnification: true)
        try await requestOK(
            path: operations.write.path,
            method: .put,
            json: ["value": String(magnification.rawValue)]
        )
        try requireLiveViewGeometryOwner(owner, allowMagnification: true)
        let readback = try await firstJSON(paths: [operations.read.path], required: true)
        try requireLiveViewGeometryOwner(owner, allowMagnification: true)
        guard let readback,
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
        let geometryOwner = liveViewGeometryOwner
        try await ensureInitialized()
        try requireLiveViewGeometryOwner(geometryOwner)
        let owner = pendingLiveViewStop?.id
        if activeLiveViewSource == .ccapiJPEGPolling, let first = pendingJPEGFrame {
            pendingJPEGFrame = nil
            return first
        }
        if activeLiveViewSource == .ccapiMultipart {
            guard let multipartSession else {
                throw CCAPIError.invalidResponse("Canon multipart Live View session is missing.")
            }
            let frame: Data
            if let pendingMultipartFrame {
                frame = pendingMultipartFrame
                self.pendingMultipartFrame = nil; pendingJPEGFrame = nil
            } else {
                frame = try await multipartSession.nextFrame()
            }
            try requireLiveViewGeometryOwner(geometryOwner)
            guard pendingLiveViewStop?.id == owner, activeLiveViewSource == .ccapiMultipart else { throw CancellationError() }
            try multipartSession.checkUsable()
            try validateLiveViewJPEG(frame)
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
        return try await readJPEGLiveViewFrame(cacheKey: cacheKey, owner: owner, allowSizeFallback: true)
    }

    private func requireLiveViewInputReady(owner: UUID, decoded: Bool) throws {
        let policy = CameraLiveViewLifecyclePolicy.evaluate([
            "sameOwner": pendingLiveViewStop?.id == owner, "sameAttempt": liveViewStartID != nil,
            "cancelled": Task.isCancelled, "retired": closing,
            "foregroundRequired": false, "foreground": true,
            "startAdmitted": true, "startAcknowledged": true, "stopEvidence": "pending",
            "sourceMode": "explicit", "terminal": false,
            "frameEvidence": decoded ? "decoded-frame" : "encoded-frame"])
        guard policy["canPublishInputReady"] == true else { throw CancellationError() }
    }

    private func validateLiveViewJPEG(_ frame: Data) throws {
        if let decodedJPEGValidator, !decodedJPEGValidator(frame) {
            throw CCAPIError.invalidResponse("Live View JPEG could not be decoded.")
        }
    }

    private func firstJPEGFrame(owner: UUID, maintenance: Bool = false) async throws -> LiveViewFrame {
        try await withThrowingTaskGroup(of: LiveViewFrame.self) { group in
            group.addTask { try await self.readJPEGLiveViewFrame(cacheKey: 0, owner: owner, allowSizeFallback: false, maintenance: maintenance) }
            group.addTask {
                try await Task.sleep(nanoseconds: 5_000_000_000)
                throw CCAPIError.invalidResponse("Canon JPEG Live View did not return a valid first frame within 5 seconds.")
            }
            defer { group.cancelAll() }
            guard let frame = try await group.next() else { throw CancellationError() }
            return frame
        }
    }

    private func readJPEGLiveViewFrame(cacheKey: Int64, owner: UUID?, allowSizeFallback: Bool, maintenance: Bool = false) async throws -> LiveViewFrame {
        let geometryOwner = liveViewGeometryOwner
        let paths: [String]
        if resolvedMode == .simulator {
            paths = ["/ccapi/liveview/frame"]
        } else {
            paths = liveViewFramePaths()
            guard !paths.isEmpty else { throw CCAPIError.unsupported(.liveView) }
        }

        var failures: [String] = []
        var liveViewSizeRejected = false
        let attempts = maintenance ? 0 : Self.jpegFrameBusyRetryNanoseconds.count
        let framePaths = maintenance ? Array(paths.prefix(1)) : paths
        for attempt in 0...attempts {
            var transientBusy = false
            for path in framePaths {
                try requireLiveViewGeometryOwner(geometryOwner)
                let sourceURL = try URLForPath(path, cacheKey: cacheKey)
                var request = request(url: sourceURL, method: .get, timeoutInterval: 5)
                request.setValue("multipart/x-mixed-replace,image/jpeg,image/*,*/*", forHTTPHeaderField: "Accept")
                request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
                request.setValue("no-cache", forHTTPHeaderField: "Pragma")
                request.setValue("close", forHTTPHeaderField: "Connection")
                do {
                    let isDetailedFrame = path.contains("/shooting/liveview/flipdetail") && path.contains("kind=both")
                    if isDetailedFrame {
                        latestLiveViewGeometry = nil
                    }
                    let response: CameraHTTPResponse
                    if maintenance { response = try await transport.sendMaintenanceLiveViewFrame(request) }
                    else { response = try await transport.sendLiveViewFrame(request) }
                    guard response.body.count <= CCAPIMultipartLiveView.maximumFrameBytes else {
                        throw CCAPIError.invalidResponse("Live View JPEG exceeded the frame safety limit.")
                    }
                    if maintenance {
                        try CameraHTTPResponseIntegrity.validate(response, requestMethod: "GET")
                        guard response.statusCode == 200, response.header("content-range") == nil else {
                            throw CCAPIError.invalidResponse("Maintenance Live View requires one complete HTTP 200 image response.")
                        }
                    }
                    try requireLiveViewGeometryOwner(geometryOwner)
                    guard pendingLiveViewStop?.id == owner else { throw CancellationError() }
                    try validate(response, request: request)
                    guard response.statusCode == 200, response.header("content-range") == nil else {
                        throw CCAPIError.invalidResponse("Live View requires a complete HTTP 200 image response.")
                    }
                    let contentType = response.header("content-type")
                    if Self.isTextContentType(contentType) {
                        throw CCAPIError.invalidResponse("Live View returned \(contentType ?? "text") instead of image bytes.")
                    }
                    let frame: Data
                    var geometry: CCAPILiveViewGeometry?
                    if isDetailedFrame {
                        let detailed = try Self.parseDetailedLiveView(response.body)
                        geometry = detailed.geometry
                        guard let image = detailed.image else {
                            throw CCAPIError.invalidResponse(
                                "Detailed Live View response did not contain an image packet."
                            )
                        }
                        frame = image
                    } else {
                        frame = try JPEGFrameParser.validatedImageData(response.body, contentType: contentType)
                    }
                    try validateLiveViewJPEG(frame)
                    if let geometry { latestLiveViewGeometry = geometry }
                    observedFeatures.formUnion([.liveView, .liveViewJPEGPolling])
                    return LiveViewFrame(data: frame, contentType: contentType, sourceURL: sourceURL)
                } catch {
                    if error is CancellationError || maintenance { throw error }
                    liveViewSizeRejected = liveViewSizeRejected || isLiveViewSizeRejection(error)
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
            if !allowSizeFallback, !maintenance { throw FirstJPEGFrameFailure.sizeUnsupported }
            if allowSizeFallback, activeLiveViewSource == .ccapiJPEGPolling,
               !liveViewSizeFallbackAttempted, activeLiveViewSize != .small,
               requestedLiveViewRequest?.source == .auto || requestedLiveViewRequest?.source == .ccapiJPEGPolling {
                return try await retryJPEGLiveViewAtSmallSize(cacheKey: cacheKey)
            }
        }
        throw CCAPIError.invalidResponse(
            "Live View failed on every advertised JPEG endpoint.\n" + failures.map { "- \($0)" }.joined(separator: "\n")
        )
    }

    public func foregroundMediaReadsAvailable() async -> Bool {
        initialized && !closing && resolvedMode == .camera && supports(.get, suffix: "/contents") &&
            transport is any CameraForegroundHTTPTransport
    }

    public func foregroundMediaInventory() async throws -> [CameraMediaItem] {
        try await foregroundMediaInventory(timeout: .seconds(30))
    }

    // A shorter budget is available only to deterministic deadline tests.
    func foregroundMediaInventory(timeout: Duration) async throws -> [CameraMediaItem] {
        let strictTransport = try foregroundTransport()
        var budget = CameraForegroundReadBudget(timeout: timeout)
        let root = apiPath(.get, suffix: "/contents")
        _ = try foregroundResourcePath(root, root: root)
        var containers = [root]
        var seenContainers: Set<Data> = [Data(root.utf8)]
        var index = 0
        var seenItems = Set<Data>()
        var items: [CameraMediaItem] = []

        func append(_ body: JSONDictionary) throws {
            guard let paths = body["path"] as? [String] else {
                throw CameraForegroundMediaError.invalidInventory
            }
            for rawID in paths {
                _ = try budget.remaining()
                let path = try foregroundResourcePath(rawID, root: root)
                if Self.isMediaPath(path) {
                    // Swift String equality folds Unicode canonical equivalents;
                    // camera identities are exact UTF-8 byte sequences instead.
                    if seenItems.insert(Data(rawID.utf8)).inserted {
                        guard items.count < CameraForegroundReadBudget.maximumItems else {
                            throw CameraForegroundMediaError.inventoryLimitExceeded
                        }
                        items.append(CameraMediaItem(
                            id: rawID, name: (path as NSString).lastPathComponent,
                            kind: Self.mediaKind(path),
                            previewAvailable: Self.isCCAPIDisplayPreviewPath(path),
                            folder: Self.listedMediaFolder(path)
                        ))
                    }
                } else if seenContainers.insert(Data(path.utf8)).inserted {
                    guard containers.count < CameraForegroundReadBudget.maximumContainers else {
                        throw CameraForegroundMediaError.inventoryLimitExceeded
                    }
                    containers.append(path)
                }
            }
        }

        while index < containers.count {
            _ = try budget.remaining()
            let container = containers[index]
            index += 1
            let count = try await foregroundJSON(
                path: "\(container)?kind=number", transport: strictTransport, budget: &budget
            )
            if count["pagenumber"] == nil {
                // An explicit, well-typed unpaged path array is positive evidence;
                // a missing/malformed count alone is never an empty baseline.
                try append(count)
                continue
            }
            guard let pageCount = Self.foregroundInteger(count["pagenumber"]), pageCount >= 0 else {
                throw CameraForegroundMediaError.invalidInventory
            }
            guard pageCount <= Int64(CameraForegroundReadBudget.maximumRequests - budget.requests) else {
                throw CameraForegroundMediaError.inventoryLimitExceeded
            }
            if pageCount == 0 {
                let body = try await foregroundJSON(path: container, transport: strictTransport, budget: &budget)
                try append(body)
            } else {
                // Plain ascending pages avoid the manual browser's ordering probe
                // and compatibility retries. Every advertised page must succeed.
                for page in 1...Int(pageCount) {
                    let body = try await foregroundJSON(
                        path: "\(container)?page=\(page)", transport: strictTransport, budget: &budget
                    )
                    try append(body)
                }
            }
        }
        _ = try budget.remaining()
        return items
    }

    public func foregroundMediaInfo(_ item: CameraMediaItem) async throws -> CameraMediaItem {
        let strictTransport = try foregroundTransport()
        var budget = CameraForegroundReadBudget()
        let path = try foregroundResourcePath(item.id, root: apiPath(.get, suffix: "/contents"))
        guard Self.isMediaPath(path) else { throw CameraForegroundMediaError.invalidInventory }
        let body = try await foregroundJSON(path: "\(path)?kind=info", transport: strictTransport, budget: &budget)
        let canonicalName = (path as NSString).lastPathComponent
        let freshID = try Self.mediaInfoString(body, keys: ["id"], fallback: item.id)
        let freshName = try Self.mediaInfoString(body, keys: ["name", "filename"], fallback: canonicalName)
        guard freshID?.utf8.elementsEqual(item.id.utf8) == true,
              freshName?.utf8.elementsEqual(item.name.utf8) == true else {
            throw CameraMediaMetadataError.invalidMetadata
        }
        guard let size = Self.foregroundInteger(body["filesize"]), size > 0 else {
            throw CameraForegroundMediaError.freshSizeUnavailable
        }
        // Missing type/name declarations bind to the requested camera resource,
        // never to cached kind/MIME. Fresh absent MIME remains genuinely absent.
        let canonical = CameraMediaItem(id: item.id, name: canonicalName, kind: Self.mediaKind(path),
                                        previewAvailable: item.previewAvailable, folder: item.folder)
        let metadata = try parseMediaInfo(canonical, body: body)
        guard CameraMediaType.admitsFreshAutomaticJPEG(original: item, metadata: metadata, fresh: true) else {
            throw CameraMediaMetadataError.invalidMetadata
        }
        return metadata
    }

    public func downloadForegroundOriginal(
        _ item: CameraMediaItem,
        expectedBytes: Int64,
        to destination: URL,
        progress: @escaping CameraMediaProgressHandler = { _ in }
    ) async throws -> CameraMediaDownload {
        let strictTransport = try foregroundTransport()
        try Task.checkCancellation()
        guard CameraMediaType.isAutomaticJPEG(item) else { throw CameraMediaMetadataError.invalidMetadata }
        guard expectedBytes > 0, item.sizeBytes == expectedBytes else {
            throw CameraForegroundMediaError.freshSizeUnavailable
        }
        guard expectedBytes <= Int64(CameraOriginalJPEG.maximumBytes) else {
            throw CameraOriginalJPEG.Failure.resourceLimit
        }
        guard !FileManager.default.fileExists(atPath: destination.path) else {
            throw CCAPIError.destinationExists(destination.path)
        }
        let path = try foregroundResourcePath(item.id, root: apiPath(.get, suffix: "/contents"))
        guard Self.isMediaPath(path) else { throw CameraForegroundMediaError.invalidInventory }
        let budget = CameraForegroundReadBudget()
        var request = try request(path: path, method: .get, timeoutInterval: try budget.remaining())
        request.setValue("application/octet-stream", forHTTPHeaderField: "Accept")
        request.setValue("no-cache, no-store", forHTTPHeaderField: "Cache-Control")
        let response = try await strictTransport.downloadForeground(
            request, maximumBytes: expectedBytes, progress: progress
        )
        defer { try? FileManager.default.removeItem(at: response.temporaryFileURL) }
        _ = try budget.remaining()
        let size = try CameraHTTPResponseIntegrity.validateOriginalMedia(response)
        guard size == expectedBytes else { throw CameraForegroundMediaError.originalSizeMismatch }
        let contentType = response.header("content-type")
        try CameraOriginalJPEG.validate(fileURL: response.temporaryFileURL, expectedBytes: expectedBytes,
                                        contentTypeValues: response.contentTypeValues)
        _ = try budget.remaining()
        try FileManager.default.moveItem(at: response.temporaryFileURL, to: destination)
        return CameraMediaDownload(item: item, fileURL: destination, bytesTransferred: size, contentType: contentType)
    }

    private func foregroundTransport() throws -> any CameraForegroundHTTPTransport {
        try Task.checkCancellation()
        // Initialization belongs to the explicit connection flow. Never discover,
        // open a Bridge session, or mutate a camera to enable an automatic read.
        guard initialized, !closing, resolvedMode == .camera, supports(.get, suffix: "/contents"),
              let transport = transport as? any CameraForegroundHTTPTransport else {
            throw CameraForegroundMediaError.unsupported
        }
        return transport
    }

    private func foregroundJSON(
        path: String,
        transport: any CameraForegroundHTTPTransport,
        budget: inout CameraForegroundReadBudget
    ) async throws -> JSONDictionary {
        var request = try request(path: path, method: .get, timeoutInterval: budget.nextRequestTimeout())
        request.setValue("no-cache, no-store", forHTTPHeaderField: "Cache-Control")
        let response = try await transport.sendForeground(
            request, maximumResponseBytes: CameraForegroundReadBudget.maximumJSONBytes
        )
        _ = try budget.remaining()
        guard response.statusCode == 200, response.header("content-range") == nil,
              !response.body.isEmpty, response.body.count <= CameraForegroundReadBudget.maximumJSONBytes else {
            throw CameraForegroundMediaError.invalidInventory
        }
        try CameraHTTPResponseIntegrity.validate(response, requestMethod: "GET")
        return try decodeForegroundJSONObject(response.body)
    }

    private func foregroundResourcePath(_ rawID: String, root: String) throws -> String {
        guard !rawID.isEmpty, rawID.utf8.count <= 4_096,
              let components = URLComponents(string: rawID),
              components.query == nil, components.fragment == nil,
              components.user == nil, components.password == nil,
              let decoded = rawID.removingPercentEncoding,
              !decoded.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw CameraForegroundMediaError.invalidInventory
        }
        let path = try normalizeCameraResource(rawID)
        guard root.range(of: #"^/ccapi/ver[0-9]+/contents$"#, options: .regularExpression) != nil,
              path == root || path.hasPrefix(root + "/"),
              !path.hasSuffix("/"), !path.contains("//") else {
            throw CameraForegroundMediaError.invalidInventory
        }
        return path
    }

    private static func foregroundInteger(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              ["c", "s", "i", "l", "q", "C", "S", "I", "L", "Q"].contains(String(cString: number.objCType)) else {
            return nil
        }
        return Int64(number.stringValue)
    }

    public func listMedia(
        maximumItems: Int? = nil,
        onProgress: CameraMediaListProgressHandler = { _ in }
    ) async throws -> [CameraMediaItem] {
        guard maximumItems.map({ $0 > 0 }) ?? true else {
            throw CCAPIError.invalidResponse("maximumItems must be greater than zero.")
        }
        try await ensureInitialized()
        if resolvedMode == .simulator {
            let value = try await requestJSON(path: "/ccapi/media", mediaMetadata: true)
            guard let rawItems = value.array("items") else {
                throw CCAPIError.invalidResponse("Simulator media response must contain an items array.")
            }
            let allItems = try rawItems.map { raw -> CameraMediaItem in
                guard let item = raw as? JSONDictionary,
                      let id = item["id"] as? String, !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      let name = item["name"] as? String, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    throw CCAPIError.invalidResponse("Simulator media response contains an invalid item.")
                }
                return CameraMediaItem(
                    id: id,
                    name: name,
                    kind: item.string("kind", default: "other"),
                    sizeBytes: item.integer64("size_bytes"),
                    captureTime: item.string("capture_time").nilIfEmpty,
                    previewAvailable: ["image", "raw"].contains(item.string("kind", default: "other").lowercased()),
                    protected: CameraMediaMetadataValue.boolean(item["protect"]),
                    rating: CameraMediaMetadataValue.integer(item["rating"], allowed: Array(0...5)),
                    rotationDegrees: CameraMediaMetadataValue.integer(item["rotate"], allowed: [0, 90, 180, 270]),
                    archived: CameraMediaMetadataValue.boolean(item["archive"]),
                    contentType: try CameraMediaType.declaredMIME(in: item),
                    widthPixels: item.integer("width_pixels").flatMap { $0 > 0 ? $0 : nil },
                    heightPixels: item.integer("height_pixels").flatMap { $0 > 0 ? $0 : nil },
                    writable: CameraMediaMetadataValue.boolean(item["writable"]),
                    ratingWritable: CameraMediaMetadataValue.boolean(item["ratingWritable"]),
                    protectionWritable: CameraMediaMetadataValue.boolean(item["protectionWritable"]),
                    rotationWritable: CameraMediaMetadataValue.boolean(item["rotationWritable"]),
                    archiveWritable: CameraMediaMetadataValue.boolean(item["archiveWritable"])
                )
            }
            let items = maximumItems.map { Array(allItems.prefix($0)) } ?? allItems
            await onProgress(items)
            observedFeatures.insert(.mediaBrowser)
            return items
        }
        guard supports(.get, suffix: "/contents") else { throw CCAPIError.unsupported(.mediaBrowser) }

        var pending = [apiPath(.get, suffix: "/contents")]
        var pendingIndex = 0
        var visited = Set<String>()
        var mediaPathGroups: [[String]] = []
        while pendingIndex < pending.count {
            try Task.checkCancellation()
            let container = try normalizeCameraResource(pending[pendingIndex]).components(separatedBy: "?")[0]
            pendingIndex += 1
            guard visited.insert(container).inserted else { continue }
            mediaPathGroups.append([])
            let groupIndex = mediaPathGroups.index(before: mediaPathGroups.endIndex)
            var discoveredContainers: [String] = []
            let rawPaths = try await contentPaths(container: container, maximumItems: maximumItems) { partialPaths in
                try Task.checkCancellation()
                var mediaPaths: [String] = []
                var seenMediaPaths = Set<String>()
                for rawPath in partialPaths {
                    let path = try self.normalizeCameraResource(rawPath).components(separatedBy: "?")[0]
                    if Self.isMediaPath(path), seenMediaPaths.insert(path).inserted {
                        mediaPaths.append(path)
                    }
                }
                mediaPathGroups[groupIndex] = mediaPaths
                if !mediaPaths.isEmpty {
                    let merged = self.mergeMediaPathGroups(mediaPathGroups)
                    let bounded = maximumItems.map { Array(merged.prefix($0)) } ?? merged
                    await onProgress(self.mediaItems(from: bounded))
                }
            }
            var finalMediaPaths: [String] = []
            var seenFinalMediaPaths = Set<String>()
            var seenDiscoveredContainers = Set<String>()
            for rawPath in rawPaths {
                let path = try normalizeCameraResource(rawPath).components(separatedBy: "?")[0]
                if Self.isMediaPath(path) {
                    if seenFinalMediaPaths.insert(path).inserted { finalMediaPaths.append(path) }
                } else if !visited.contains(path), seenDiscoveredContainers.insert(path).inserted {
                    discoveredContainers.append(path)
                }
            }
            mediaPathGroups[groupIndex] = finalMediaPaths
            pending.append(contentsOf: discoveredContainers)
            if mediaPathGroups[groupIndex].isEmpty { mediaPathGroups.remove(at: groupIndex) }
        }
        let mergedMediaPaths = mergeMediaPathGroups(mediaPathGroups)
        let mediaPaths = maximumItems.map { Array(mergedMediaPaths.prefix($0)) } ?? mergedMediaPaths
        observedFeatures.insert(.mediaBrowser)
        return mediaItems(from: mediaPaths)
    }

    private func mediaItems(from mediaPaths: [String]) -> [CameraMediaItem] {
        mediaPaths.map {
            CameraMediaItem(
                id: $0,
                name: ($0 as NSString).lastPathComponent,
                kind: Self.mediaKind($0),
                previewAvailable: Self.isCCAPIDisplayPreviewPath($0),
                folder: Self.listedMediaFolder($0)
            )
        }
    }

    /// Called only for normalized paths from a successful camera listing, never caller-supplied item IDs.
    private static func listedMediaFolder(_ path: String) -> CameraMediaFolder? {
        guard let separator = path.lastIndex(of: "/") else { return nil }
        let parent = String(path[..<separator])
        // Qualify the encoded path before decoding: other/contents and encoded endpoint names
        // remain browsable media but do not establish folder provenance.
        guard parent.range(of: #"^/ccapi/ver[0-9]+/contents(?:/.+)?$"#, options: .regularExpression) != nil,
              let prefix = parent.range(of: #"^/ccapi/ver[0-9]+/contents"#, options: .regularExpression),
              let label = String(parent[prefix.upperBound...]).removingPercentEncoding else { return nil }
        return CameraMediaFolder(id: parent, label: label.isEmpty ? "/" : label)
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
            defer { try? FileManager.default.removeItem(at: download.temporaryFileURL) }
            // A custom transport can return a file despite task cancellation.
            try Task.checkCancellation()
            if (200..<300).contains(download.statusCode) {
                let size = try CameraHTTPResponseIntegrity.validateOriginalMedia(download)
                let contentType = download.header("content-type")
                let prefix = try download.readPrefix(limit: Self.maximumErrorBodyCharacters)
                if Self.isTextContentType(contentType) || Self.looksLikeTextPayload(prefix) {
                    let preview = String(data: prefix, encoding: .utf8) ?? ""
                    failures.append("\(path): HTTP \(download.statusCode): \(preview)")
                    continue
                }
                try Task.checkCancellation()
                try FileManager.default.moveItem(at: download.temporaryFileURL, to: destination)
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
            let preview = (try? download.readPrefix(limit: Self.maximumErrorBodyCharacters))
                .flatMap { String(data: $0, encoding: .utf8) } ?? ""
            failures.append("\(path): HTTP \(download.statusCode): \(preview)")
        }
        throw CCAPIError.invalidResponse(
            "Media download failed for '\(item.name)'.\n" + failures.map { "- \($0)" }.joined(separator: "\n")
        )
    }

    public func openMediaStream(
        _ item: CameraMediaItem,
        offset: Int64,
        length: Int64? = nil
    ) async throws -> CameraMediaStreamResponse {
        guard CameraMediaType.classify(item) == .video else { throw CameraMediaMetadataError.invalidMetadata }
        try await ensureInitialized()
        guard offset >= 0, length.map({ $0 > 0 }) ?? true else {
            throw CCAPIError.invalidResponse("Media byte range must be positive.")
        }
        if resolvedMode != .simulator,
           !supports(.get, suffix: "/contents"),
           !observedFeatures.contains(.mediaDownload) {
            throw CCAPIError.unsupported(.mediaDownload)
        }

        let paths: [String]
        if resolvedMode == .simulator {
            paths = ["/ccapi/media/\(Self.encodePathComponent(item.id))"]
        } else {
            let path = try normalizeCameraResource(item.id).components(separatedBy: "?")[0]
            paths = [path, "\(path)?kind=main", "\(path)?type=main"]
        }

        var failures: [String] = []
        for path in paths {
            try Task.checkCancellation()
            var streamRequest = try request(path: path, method: .get, timeoutInterval: 120)
            streamRequest.setValue("video/*, application/octet-stream", forHTTPHeaderField: "Accept")
            streamRequest.setValue(Self.mediaRangeHeader(offset: offset, length: length), forHTTPHeaderField: "Range")
            let response = try await transport.openStream(streamRequest)
            guard response.statusCode == 200 || response.statusCode == 206 else {
                response.cancel()
                failures.append("\(path): HTTP \(response.statusCode)")
                continue
            }
            let contentType = response.header("content-type")
            guard !Self.isTextContentType(contentType) else {
                response.cancel()
                failures.append("\(path): camera returned \(contentType ?? "text")")
                continue
            }
            do {
                let stream = try CameraMediaStreamResponse(
                    item: item,
                    response: response,
                    fallbackTotalBytes: item.sizeBytes
                )
                observedFeatures.insert(.mediaDownload)
                return stream
            } catch {
                response.cancel()
                failures.append("\(path): invalid byte-range response")
            }
        }
        throw CCAPIError.invalidResponse(
            "Media streaming failed for '\(item.name)'.\n" + failures.map { "- \($0)" }.joined(separator: "\n")
        )
    }

    private static func mediaRangeHeader(offset: Int64, length: Int64?) -> String {
        guard let length else { return "bytes=\(offset)-" }
        let (end, overflow) = offset.addingReportingOverflow(length - 1)
        return overflow ? "bytes=\(offset)-" : "bytes=\(offset)-\(end)"
    }

    public func uploadMedia(
        from fileURL: URL,
        contentType: String? = nil,
        attempt: CameraMediaUploadAttempt = CameraMediaUploadAttempt(),
        progress: @escaping CameraMediaProgressHandler = { _ in }
    ) async throws -> CameraMediaItem {
        throw CCAPIError.unsupported(.mediaUpload)
    }

    public func mediaThumbnail(_ item: CameraMediaItem) async throws -> CameraMediaThumbnail {
        guard [.photo, .video].contains(CameraMediaType.classify(item)) else {
            throw CameraMediaMetadataError.invalidMetadata
        }
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
        guard CameraMediaType.classify(item) == .photo, previewEligible else {
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
        let body = try await requestJSON(path: "\(path)?kind=info", maximumBytes: 1_048_576, mediaMetadata: true)
        return try parseMediaInfo(item, body: body)
    }

    public func setMediaProtection(_ item: CameraMediaItem, enabled: Bool, attempt: CameraMediaMutationAttempt? = nil) async throws -> CameraMediaItem {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        return try await modifyMedia(
            item,
            action: "protect",
            value: enabled ? "enable" : "disable",
            target: .protection(enabled),
            attempt: attempt
        )
    }

    public func setMediaRating(_ item: CameraMediaItem, rating: Int, attempt: CameraMediaMutationAttempt? = nil) async throws -> CameraMediaItem {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        guard (0...5).contains(rating) else {
            throw CCAPIError.invalidResponse("Media rating must be from 0 through 5.")
        }
        return try await modifyMedia(
            item,
            action: "rating",
            value: rating == 0 ? "off" : String(rating),
            target: .rating(rating),
            attempt: attempt
        )
    }

    public func setMediaRotation(_ item: CameraMediaItem, degrees: Int, attempt: CameraMediaMutationAttempt? = nil) async throws -> CameraMediaItem {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        guard Self.mediaRotations.contains(degrees) else {
            throw CCAPIError.invalidResponse("Media rotation must be 0, 90, 180, or 270 degrees.")
        }
        return try await modifyMedia(
            item,
            action: "rotate",
            value: String(degrees),
            target: .rotation(degrees),
            attempt: attempt
        )
    }

    public func setMediaArchive(_ item: CameraMediaItem, enabled: Bool, attempt: CameraMediaMutationAttempt? = nil) async throws -> CameraMediaItem {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        return try await modifyMedia(
            item,
            action: "archive",
            value: enabled ? "enable" : "disable",
            target: .archive(enabled),
            attempt: attempt
        )
    }

    private func modifyMedia(
        _ item: CameraMediaItem,
        action: String,
        value: String,
        target: CameraMediaMutation,
        attempt: CameraMediaMutationAttempt?
    ) async throws -> CameraMediaItem {
        let feature = target.feature
        guard target.isWritable(item) else { throw CCAPIError.invalidResponse("This media item is not writable.") }
        try await ensureInitialized()
        if resolvedMode != .simulator, !supportsMediaModify() {
            throw CCAPIError.unsupported(feature)
        }
        let request = try request(path: mediaPath(item), method: .put, json: ["action": action, "value": value])
        try Task.checkCancellation()
        guard !closing else { throw CancellationError() }
        try attempt?.markDispatched()
        let response = try await transport.send(request)
        try validate(response, request: request)
        guard response.statusCode == 200 else { throw CCAPIError.invalidResponse("Media mutation did not return HTTP 200.") }
        var latest = item
        for readAttempt in 0..<3 {
            try Task.checkCancellation()
            guard !closing else { throw CancellationError() }
            latest = try await mediaInfo(item)
            try Task.checkCancellation()
            guard !closing else { throw CancellationError() }
            if target.confirms(latest, original: item) {
                observedFeatures.insert(feature)
                return latest
            }
            if readAttempt < 2 { try await Task.sleep(nanoseconds: 100_000_000) }
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

    private func parseMediaInfo(_ item: CameraMediaItem, body: JSONDictionary) throws -> CameraMediaItem {
        let id = try Self.mediaInfoString(body, keys: ["id"], fallback: item.id)
        guard id?.utf8.elementsEqual(item.id.utf8) == true else { throw CameraMediaMetadataError.invalidMetadata }
        let protected = CameraMediaMetadataValue.canonFlag(body["protect"])
        let rating = CameraMediaMetadataValue.canonRating(body["rating"])
        let rotation = CameraMediaMetadataValue.canonRotation(body["rotate"])
        let archived = CameraMediaMetadataValue.canonFlag(body["archive"])
        return CameraMediaItem(
            id: item.id,
            name: try Self.mediaInfoString(body, keys: ["name", "filename"], fallback: item.name) ?? "",
            kind: try Self.mediaInfoString(body, keys: ["kind"], fallback: item.kind) ?? "",
            sizeBytes: body.integer64("filesize").flatMap { $0 > 0 ? $0 : nil } ?? item.sizeBytes,
            captureTime: body.string("lastmodifieddate").nilIfEmpty ?? item.captureTime,
            previewAvailable: item.previewAvailable,
            protected: protected,
            rating: rating,
            rotationDegrees: rotation,
            archived: archived,
            contentType: try CameraMediaType.declaredMIME(in: body),
            widthPixels: item.widthPixels,
            heightPixels: item.heightPixels,
            folder: item.folder,
            writable: CameraMediaMetadataValue.boolean(body["writable"]) ?? item.writable,
            ratingWritable: CameraMediaMetadataValue.boolean(body["ratingWritable"]) ?? item.ratingWritable,
            protectionWritable: CameraMediaMetadataValue.boolean(body["protectionWritable"]) ?? item.protectionWritable,
            rotationWritable: CameraMediaMetadataValue.boolean(body["rotationWritable"]) ?? item.rotationWritable,
            archiveWritable: CameraMediaMetadataValue.boolean(body["archiveWritable"]) ?? item.archiveWritable
        )
    }

    /// Do not erase an explicit blank, null, malformed field or conflicting
    /// alias by substituting listing metadata in an explicitly fresh response.
    private static func mediaInfoString(_ body: JSONDictionary, keys: [String], fallback: String?) throws -> String? {
        var present = false
        var result: String?
        for key in keys {
            guard let raw = body[key] else { continue }
            let value: String?
            if raw is NSNull { value = nil }
            else if let string = raw as? String { value = string }
            else { throw CameraMediaMetadataError.invalidMetadata }
            if present, result.map({ Data($0.utf8) }) != value.map({ Data($0.utf8) }) {
                throw CameraMediaMetadataError.invalidMetadata
            }
            present = true
            result = value
        }
        return present ? result : fallback
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

    public func deleteMedia(_ item: CameraMediaItem, attempt: CameraMediaDeleteAttempt = CameraMediaDeleteAttempt()) async throws {
        try beginCameraMutation()
        defer { cameraMutationCount -= 1 }
        try await ensureInitialized()
        let path: String
        if resolvedMode == .simulator {
            path = "/ccapi/media/\(Self.encodePathComponent(item.id))"
        } else {
            guard supportsMediaDelete() else { throw CCAPIError.unsupported(.mediaDelete) }
            path = try normalizeCameraResource(item.id).components(separatedBy: "?")[0]
        }
        guard item.protected != true, item.writable != false, !item.id.isEmpty else { throw CameraMediaDeleteError.ineligible }
        let request = try request(path: path, method: .delete)
        try Task.checkCancellation()
        guard !closing else { throw CancellationError() }
        try attempt.markDispatched()
        let response = try await transport.sendMediaDelete(request)
        // Preserve the receipt belonging to this invocation even if its session retired
        // while awaiting it. Only current-session side effects require ownership.
        try attempt.record(response)
        guard !closing else { throw CancellationError() }
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

        guard foundPacket, offset == bytes.count else {
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
               let x = strictInteger(image["positionx"]), x >= 0,
               let y = strictInteger(image["positiony"]), y >= 0,
               let width = strictInteger(image["positionwidth"]), width > 0,
               let height = strictInteger(image["positionheight"]), height > 0,
               width <= Int(Int32.max), height <= Int(Int32.max),
               x <= Int(Int32.max) - width, y <= Int(Int32.max) - height {
                return CCAPILiveViewGeometry(
                    positionX: x,
                    positionY: y,
                    positionWidth: width,
                    positionHeight: height
                )
            }
            for child in object.values {
                if let geometry = find(child) { return geometry }
            }
            return nil
        }

        return find(root)
    }

    private var liveViewGeometryOwner: LiveViewGeometryOwner {
        LiveViewGeometryOwner(session: pendingLiveViewStop?.id, source: activeLiveViewSource,
                              revision: liveViewGeometryRevision)
    }

    private var liveViewMagnificationIsCurrent: Bool {
        guard liveViewMagnificationAttempt != nil, let owner = liveViewMagnificationOwner else { return false }
        return owner.session == pendingLiveViewStop?.id && owner.source == activeLiveViewSource
            && owner.revision == liveViewGeometryRevision
    }

    private func liveViewGeometryIsCurrent(_ owner: LiveViewGeometryOwner, allowMagnification: Bool = false) -> Bool {
        !closing && owner.session == pendingLiveViewStop?.id && owner.source == activeLiveViewSource
            && owner.revision == liveViewGeometryRevision
            && (allowMagnification || !liveViewMagnificationIsCurrent)
    }

    private func invalidateLiveViewGeometry() {
        liveViewGeometryRevision = UUID()
        latestLiveViewGeometry = nil
        pendingJPEGFrame = nil
        pendingMultipartFrame = nil
        multipartSession?.discardPendingFrame()
    }

    private func requireLiveViewGeometryOwner(_ owner: LiveViewGeometryOwner, allowMagnification: Bool = false) throws {
        try Task.checkCancellation()
        guard liveViewGeometryIsCurrent(owner, allowMagnification: allowMagnification) else {
            throw CancellationError()
        }
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

    private func sendCoordinateCommand(
        operation: CCAPIOperation, position: (x: Int, y: Int), owner: LiveViewGeometryOwner
    ) async throws {
        let command = try request(path: operation.path, method: operation.method,
                                  json: ["positionx": position.x, "positiony": position.y])
        // No asynchronous preflight may separate the final owner check from send.
        try requireLiveViewGeometryOwner(owner)
        let response = try await transport.send(command)
        try requireLiveViewGeometryOwner(owner)
        try validate(response, request: command)
    }

    private func ensureLiveViewGeometryForNativeStream(owner: LiveViewGeometryOwner) async throws {
        try requireLiveViewGeometryOwner(owner)
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
        var request = request(url: sourceURL, method: .get, timeoutInterval: 5)
        request.setValue("application/octet-stream,*/*", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let response = try await transport.sendLiveViewFrame(request)
        try requireLiveViewGeometryOwner(owner)
        try CameraHTTPResponseIntegrity.validate(response, requestMethod: "GET")
        try validate(response, request: request)
        guard response.statusCode == 200, response.header("content-range") == nil,
              response.body.count <= CCAPIMultipartLiveView.maximumFrameBytes else {
            throw CCAPIError.invalidResponse("Coordinate geometry requires a complete bounded Live View response.")
        }
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
        guard supportsRTPLiveView(), let rtpDestinationAddress, let rtpSessionFactory else {
            throw CCAPIError.unsupported(.liveViewRTP)
        }
        let descriptionPath = apiPath(.get, suffix: "/shooting/liveview/rtpsessiondesc")
        let controlPath = apiPath(.post, suffix: "/shooting/liveview/rtp")
        let text = try await requestText(path: descriptionPath, maximumBytes: Self.maximumRTPSessionDescriptionBytes)
        try checkLiveViewStart()
        let description = try CCAPIRTPSessionDescriptionParser.parse(text)
        let session = try await rtpSessionFactory.makeSession(description: description, destinationAddress: rtpDestinationAddress)
        do {
            // Late allocation still belongs to this Start and must be closed on cancellation.
            try checkLiveViewStart()
            await session.setTargetFPS(request.fps)
            try checkLiveViewStart()
            try await session.start()
            let stop = try makeLiveViewStop(source: .ccapiRTP,
                operation: CCAPIOperation(method: .post, path: controlPath))
            try await sendLiveViewStart(stop: stop, path: controlPath, method: .post,
                json: ["action": "start", "ipaddress": rtpDestinationAddress])
            try await session.waitUntilReady()
            try checkLiveViewStart()
            try requireLiveViewInputReady(owner: stop.id, decoded: true)
            rtpSession = session
            activeLiveViewSource = .ccapiRTP
            observedFeatures.formUnion([.liveView, .liveViewRTP])
        } catch {
            await session.close()
            throw error
        }
    }

    private func isLiveViewSizeRejection(_ error: Error) -> Bool {
        guard case let CCAPIError.http(statusCode, _, _, body) = error, statusCode == 503 else { return false }
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
        try await retryLiveViewStop()
        try await startLiveView(LiveViewRequest(fps: requestedFPS, size: .small, source: .ccapiJPEGPolling))
        liveViewSizeFallbackAttempted = true
        return try await liveViewFrame(cacheKey: cacheKey)
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
        let generation = shootingSettingsGeneration
        shootingSettingsReadRevision &+= 1
        let revision = shootingSettingsReadRevision
        // Actor methods can reenter at every request. Keep the last complete
        // values/routes/capabilities together until this entire refresh is ready.
        var settingPaths: [String: String] = [:]
        var liveViewMagnifications: [LiveViewMagnification] = []
        var currentLiveViewMagnification: LiveViewMagnification?
        var cameraSleepPath: String?
        var refreshedFeatures = Set<CameraFeature>()
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
            refreshedFeatures.insert(.liveViewMagnification)
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
            refreshedFeatures.insert(.cardSelectionControl)
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
            refreshedFeatures.insert(.soundRecordingControl)
        }
        for endpoint in Self.soundRecordingLevelEndpoints {
            if let operations = soundRecordingLevelOperations(suffix: endpoint.suffix),
               let raw = try await firstJSON(paths: [operations.read.path], required: false),
               let soundRecordingLevel = Self.validatedIntegerRangeSetting(raw) {
                settingPaths[endpoint.key] = operations.write.path
                merged[endpoint.key] = soundRecordingLevel
                refreshedFeatures.insert(.soundRecordingLevelControl)
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
                refreshedFeatures.insert(.focusBracketingControl)
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
            refreshedFeatures.insert(.movieSettingsControl)
        }
        if let operations = directoryOperations(),
           let raw = try await firstJSON(paths: [operations.read.path], required: false),
           let selection = Self.validatedDirectorySelectionSetting(raw) {
            settingPaths[Self.directorySelectionSettingKey] = operations.write.path
            merged[Self.directorySelectionSettingKey] = selection
            refreshedFeatures.insert(.directoryControl)
        }
        try Task.checkCancellation()
        // A newer completed read is fresh enough for this caller; a merely
        // pending read is not. Never turn forced validation into an old-cache
        // fallback or let an older read overwrite a newer committed snapshot.
        if committedShootingSettingsRevision > revision { return cachedSettings }
        guard shootingSettingsGeneration == generation, !closing else {
            throw CCAPIError.invalidResponse("Camera settings changed while refreshing. Refresh settings before retrying.")
        }
        self.settingPaths = settingPaths
        self.liveViewMagnifications = liveViewMagnifications
        self.currentLiveViewMagnification = currentLiveViewMagnification
        self.cameraSleepPath = cameraSleepPath
        observedFeatures.subtract([
            .liveViewMagnification, .cardSelectionControl, .soundRecordingControl,
            .soundRecordingLevelControl, .focusBracketingControl, .movieSettingsControl, .directoryControl,
        ])
        observedFeatures.formUnion(refreshedFeatures)
        settingsLoaded = true
        cachedSettings = merged.isEmpty ? nil : merged
        committedShootingSettingsRevision = revision
        return cachedSettings
    }

    private func loadFileNaming(force: Bool = false, captureConfigurationRead: Bool = false) async throws -> CameraFileNaming? {
        if fileNamingLoaded, !force { return cachedFileNaming }
        observedFeatures.remove(.fileNamingControl)
        guard let operations = fileNamingOperations() else {
            cachedFileNaming = nil
            fileNamingLoaded = true
            return nil
        }
        var responses: [CameraFileNamingField: JSONDictionary] = [:]
        for endpoint in Self.fileNamingEndpoints {
            try Task.checkCancellation()
            guard let operation = operations[endpoint.field],
                  let response = try await firstJSON(paths: [operation.read.path], required: false,
                                                     captureConfigurationRead: captureConfigurationRead) else {
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

    private func retireShootingSettingsReads() {
        shootingSettingsGeneration = UUID()
        committedShootingSettingsRevision = 0
    }

    private func invalidateShootingSettingsCache() {
        retireShootingSettingsReads()
        cachedSettings = nil
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
        CameraCaptureConfigurationValue.directorySuffix(value, allowEmpty: true)
    }

    private static func isValidCreatedDirectoryName(_ value: String) -> Bool {
        CameraCaptureConfigurationValue.directorySuffix(value)
    }

    private static func isValidDirectorySelection(_ value: String) -> Bool {
        CameraCaptureConfigurationValue.directorySelection(value)
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

    private func putSettingValue(candidateKeys: [String], value: String,
                                 shootingSettingAttempt: CameraShootingSettingAttempt? = nil) async throws {
        if !settingsLoaded { _ = try await loadShootingSettings() }
        guard let path = shootingSettingAttempt?.readRoute?.writePath ?? candidateKeys.compactMap({ settingPaths[$0] }).first else {
            throw CCAPIError.invalidResponse("Camera did not advertise this writable setting.")
        }
        // A failed response never authorizes an alias retry of the same logical write.
        try await requestOK(path: path, method: .put, json: ["value": value], shootingSettingAttempt: shootingSettingAttempt)
    }

    private func putStructuredSettingValue(
        settings: JSONDictionary?,
        baseKey: String,
        field: String,
        value: String,
        shootingSettingAttempt: CameraShootingSettingAttempt? = nil
    ) async throws {
        guard let path = shootingSettingAttempt?.readRoute?.writePath ?? settingPaths[baseKey] else {
            throw CCAPIError.invalidSetting(key: "\(baseKey).\(field)", value: value)
        }
        // The outcome path supplies the fresh preflight object, including all
        // siblings. Legacy API callers retain their existing object semantics.
        guard let fresh = settings?.object(baseKey), let control = (baseKey == Self.wbShiftSettingKey ? structuredWBShiftControls(fresh) : structuredImageQualityControls(fresh))
                .first(where: { $0.key == "\(baseKey).\(field)" }), control.accepts(value),
              let current = fresh.object("value") else {
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
        try await requestOK(path: path, method: .put, json: ["value": updated], shootingSettingAttempt: shootingSettingAttempt)
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

    /// One explicit command plus at most one bounded independent observation.
    /// The original attempt is never rewritten by a later recovery read.
    public func recordingOutcome(starting: Bool, attempt: CameraRecordingAttempt) async throws -> CameraRecordingOutcome {
        try Task.checkCancellation()
        guard recordingAttemptID == nil else { throw CCAPIError.invalidResponse("A recording command is already active.") }
        try attempt.admit(owner: recordingOwner, starting: starting)
        let id = attempt.snapshot.id
        recordingAttemptID = id
        defer { if recordingAttemptID == id { recordingAttemptID = nil }; attempt.complete() }
        if starting {
            guard recordingCommandEvidence == nil, recording != true else {
                throw CCAPIError.invalidResponse("Stop the current recording before another Start.")
            }
            try beginCameraMutation()
        }
        defer { if starting { cameraMutationCount -= 1 } }
        try await ensureInitialized()
        if starting {
            try await refreshTemperatureStatusForRestrictedCommand()
            try requireTemperatureAllowsMovieRecording()
        }
        let operation: CCAPIOperation
        if resolvedMode == .simulator {
            operation = CCAPIOperation(method: .post, path: "/ccapi/record/\(starting ? "start" : "stop")")
        } else if !starting, let retainedRecordingStop {
            operation = retainedRecordingStop
        } else {
            guard let advertised = recordingOperation() else { throw CCAPIError.unsupported(.videoRecording) }
            operation = advertised
        }
        let command = try request(path: operation.path, method: operation.method,
            json: resolvedMode == .simulator ? [:] : ["action": starting ? "start" : "stop"])
        guard !closing else { throw CCAPIError.notInitialized }
        let previous = recordingCommandEvidence
        try attempt.markDispatched()
        recordingRevision = UUID()
        recordingCommandEvidence = .unconfirmed(starting: starting)
        retainedRecordingStop = resolvedMode == .simulator
            ? CCAPIOperation(method: .post, path: "/ccapi/record/stop") : operation
        let exchange: CameraHTTPRecordingResponse
        do { exchange = try await transport.sendRecordingCommand(command) }
        catch {
            attempt.complete()
            if error is CancellationError || (error as? URLError)?.code == .cancelled { throw error }
            throw CCAPIError.recordingCommandUnconfirmed(starting: starting)
        }
        let response = exchange.response
        if !(200..<300).contains(response.statusCode) {
            attempt.recordResponse(.rejected)
            recordingCommandEvidence = previous
        } else if exchange.bodyComplete,
                  CameraRecordingWire.validDirectAcknowledgement(response, simulator: resolvedMode == .simulator) {
            attempt.recordResponse(.responseReceived)
            if !closing, !exchange.cancelled, !Task.isCancelled {
                recordingCommandEvidence = starting ? .acknowledged(starting: true) : nil
                if !starting { recording = nil }
                observedFeatures.insert(.videoRecording)
            }
        }
        if exchange.cancelled { attempt.complete(); throw CancellationError() }
        try Task.checkCancellation()
        guard !closing else { throw CCAPIError.notInitialized }
        let responseEvidence = attempt.snapshot.responseEvidence
        guard responseEvidence == .responseReceived else {
            attempt.complete()
            if responseEvidence == .unconfirmed { throw CCAPIError.recordingCommandUnconfirmed(starting: starting) }
            return attempt.snapshot
        }
        do {
            let observation = try await recordingObservation()
            guard !closing, recordingAttemptID == id else { throw CCAPIError.notInitialized }
            attempt.complete(observation)
        } catch {
            attempt.complete(.readFailed)
            if error is CancellationError || (error as? URLError)?.code == .cancelled { throw error }
        }
        return attempt.snapshot
    }

    /// Direct Canon currently has no proven REC GET. Its ACK cache is never
    /// relabelled as observation, and recovery never rediscovers or sends REC.
    public func recordingObservation() async throws -> CameraRecordingObservation {
        try Task.checkCancellation()
        guard !closing else { throw CCAPIError.notInitialized }
        try await ensureInitialized()
        guard resolvedMode == .simulator else { return .unavailable }
        var read = try request(path: "/ccapi/status", method: .get)
        read.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let revision = recordingRevision
        let response = try await transport.sendRecordingStatus(read)
        try Task.checkCancellation()
        guard !closing, revision == recordingRevision, response.statusCode == 200, response.header("content-range") == nil,
              response.body.count <= 32 * 1024 else { throw CCAPIError.invalidResponse("Recording observation was incomplete.") }
        try CameraHTTPResponseIntegrity.validate(response, requestMethod: "GET")
        let status = try parseSimulatorStatus(CameraRecordingWire.decode(response.body))
        guard let observed = status.recording else { return .unavailable }
        recording = observed
        if !observed { recordingCommandEvidence = nil }
        return .observed(observed, source: "ccapi-simulator")
    }

    private func setRecording(_ enabled: Bool) async throws -> CameraStatus {
        try Task.checkCancellation()
        try await ensureInitialized()
        if enabled {
            try await refreshTemperatureStatusForRestrictedCommand()
            try requireTemperatureAllowsMovieRecording()
        }
        try Task.checkCancellation()
        let command: URLRequest
        if resolvedMode == .simulator {
            command = try request(path: "/ccapi/record/\(enabled ? "start" : "stop")", method: .post, json: [:])
        } else {
            guard let operation = recordingOperation() else {
                throw CCAPIError.unsupported(.videoRecording)
            }
            command = try request(path: operation.path, method: operation.method, json: ["action": enabled ? "start" : "stop"])
        }
        guard !closing else { throw CCAPIError.notInitialized }
        try Task.checkCancellation()
        recordingCommandEvidence = .unconfirmed(starting: enabled)
        let exchange: CameraHTTPRecordingResponse
        do {
            exchange = try await transport.sendRecordingCommand(command)
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled { throw error }
            throw CCAPIError.recordingCommandUnconfirmed(starting: enabled)
        }
        let response = exchange.response
        let rejected = !(200..<300).contains(response.statusCode)
        // A received rejection remains known even if its diagnostic body is lost.
        if rejected { recordingCommandEvidence = nil }
        if exchange.cancelled { throw CancellationError() }
        if !exchange.bodyComplete, !rejected {
            throw CCAPIError.recordingCommandUnconfirmed(starting: enabled)
        }
        try validate(response, request: command)
        try Task.checkCancellation()
        guard !closing else { throw CCAPIError.notInitialized }
        if resolvedMode == .simulator {
            do { _ = try decodeJSONObject(response.body) }
            catch { throw CCAPIError.recordingCommandUnconfirmed(starting: enabled) }
        }
        recordingCommandEvidence = .acknowledged(starting: enabled)
        observedFeatures.insert(.videoRecording)
        do {
            let status = try await status()
            if resolvedMode == .simulator, status.recording == false { recordingCommandEvidence = nil }
            return status
        } catch {
            // Cancellation retains its existing propagation contract. In camera
            // mode ordinary optional status-read failures are absorbed by status().
            if error is CancellationError || (error as? URLError)?.code == .cancelled { throw error }
            // Only this read follows a received command ACK. Command result
            // uncertainty has its own evidence and never enters this branch.
            throw CCAPIError.recordingStatusReadbackFailed(starting: enabled)
        }
    }

    private func commandOK(operation: CCAPIOperation, json: JSONDictionary) async throws {
        try await requestOK(path: operation.path, method: operation.method, json: json)
    }

    private func performGuaranteedRelease(
        operation: CCAPIOperation,
        press: JSONDictionary,
        release: JSONDictionary,
        releasePath: String? = nil,
        holdNanoseconds: UInt64? = nil
    ) async throws {
        try Task.checkCancellation()
        guard !closing, shortControlTask == nil, bulbStartTask == nil,
              shutterReleaseTask == nil, pendingShutterRelease == nil else {
            throw CCAPIError.invalidResponse("Release the shutter before another camera operation.")
        }
        // Prepare and freeze the original session's exact commands before any send.
        let pressRequest = try request(path: operation.path, method: operation.method, json: press)
        let ownedRelease = ShutterRelease(
            id: UUID(), operation: CCAPIOperation(method: operation.method, path: releasePath ?? operation.path),
            simulator: resolvedMode == .simulator, payload: try encodeJSONObject(release)
        )
        shortControlID = ownedRelease.id
        let task = Task {
            try await self.performShortControl(pressRequest, release: ownedRelease, holdNanoseconds: holdNanoseconds)
        }
        shortControlTask = task
        try await withTaskCancellationHandler {
            try await task.value
        } onCancel: {
            task.cancel()
        }
    }

    private func performShortControl(
        _ press: URLRequest, release: ShutterRelease, holdNanoseconds: UInt64?
    ) async throws {
        defer {
            if shortControlID == release.id {
                shortControlTask = nil
                shortControlID = nil
            }
        }
        // Cancellation/Close before dispatch has no camera-side cleanup obligation.
        try Task.checkCancellation()
        guard !closing, shortControlID == release.id else { throw CancellationError() }
        guard pendingShutterRelease == nil else {
            throw CCAPIError.invalidResponse("Release the existing shutter exposure before starting again.")
        }
        pendingShutterRelease = release
        shutterReleaseUnconfirmed = true
        var primaryFailure: (any Error)?
        do {
            let response = try await transport.send(press)
            try validate(response, request: press)
            if release.simulator { _ = try decodeJSONObject(response.body) }
            try Task.checkCancellation()
            guard !closing, pendingShutterRelease?.id == release.id else { throw CancellationError() }
            if let holdNanoseconds { try await Task.sleep(nanoseconds: holdNanoseconds) }
        } catch {
            primaryFailure = error
        }
        // The shared, detached stop survives caller cancellation and is retained
        // on failure. Close and explicit Retry settle this task before releasing.
        do { try await releasePendingShutter() }
        catch {
            if let primaryFailure {
                throw CCAPIError.operationAndReleaseFailed(
                    operation: primaryFailure.localizedDescription, release: error.localizedDescription
                )
            }
            throw error
        }
        if let primaryFailure { throw primaryFailure }
    }

    private func simulatorCapabilities(captureConfigurationRead: Bool = false) async throws -> CameraCapabilities {
        let value = try await requestJSON(path: "/ccapi/capabilities", maximumBytes: captureConfigurationRead ? 64 * 1024 : nil,
                                          captureConfigurationRead: captureConfigurationRead)
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

    private func simulatorRecordingObservation(_ value: Any?) throws -> Bool? {
        guard let value, !(value is NSNull) else { return nil }
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else {
            throw CCAPIError.invalidResponse("Simulator recording status must be Boolean, null, or absent.")
        }
        return number.boolValue
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
            recording: try simulatorRecordingObservation(value["recording"]),
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

    private func contentPaths(
        container: String,
        maximumItems: Int? = nil,
        onPage: ([String]) async throws -> Void = { _ in }
    ) async throws -> [String] {
        let pageInfo = try await firstJSON(
            paths: ["\(container)?kind=number", "\(container)?type=all,kind=number"],
            required: false
        )
        let pageCount: Int
        if let pageInfo {
            if pageInfo["pagenumber"] == nil {
                // Some cameras ignore kind=number and return a valid unpaged list.
                // Missing count alone is never evidence of an empty card.
                _ = try Self.validatedContentPaths(pageInfo)
                pageCount = 0
            } else {
                guard let count = Self.strictInteger(pageInfo["pagenumber"]) else {
                    throw CCAPIError.invalidResponse("Camera returned an invalid media page count.")
                }
                guard count >= 0 else {
                    throw CCAPIError.invalidResponse("Camera returned a negative media page count.")
                }
                pageCount = count
            }
        } else {
            // The optional count endpoint may be unsupported; the required
            // fallback listing still has to contain a valid path array.
            pageCount = 0
        }
        var result: [String] = []
        var seenPaths = Set<String>()
        var sawNonMediaPath = false
        func appendUnique(_ paths: [String], reversed: Bool = false) {
            let values = reversed ? Array(paths.reversed()) : paths
            for path in values where seenPaths.insert(path).inserted {
                if !Self.isMediaPath(path) { sawNonMediaPath = true }
                result.append(path)
            }
        }
        func reachedMediaLimit() -> Bool {
            guard let maximumItems, !sawNonMediaPath else { return false }
            return result.reduce(into: 0) { count, path in
                if Self.isMediaPath(path) { count += 1 }
            } >= maximumItems
        }
        func boundedResult() -> [String] {
            guard let maximumItems, !sawNonMediaPath else { return result }
            return result.filter(Self.isMediaPath).prefix(maximumItems).map { $0 }
        }
        if pageCount <= 0 {
            try Task.checkCancellation()
            if let value = try await firstJSON(paths: [container], required: true) {
                appendUnique(try Self.validatedContentPaths(value))
            }
            try await onPage(boundedResult())
        } else if mediaDescendingOrderSupported == false {
            for page in stride(from: pageCount, through: 1, by: -1) {
                try Task.checkCancellation()
                guard let value = try await contentPage(container: container, page: page) else { continue }
                let paths = try Self.validatedContentPaths(value)
                appendUnique(paths, reversed: true)
                try await onPage(boundedResult())
                if reachedMediaLimit() { return boundedResult() }
            }
        } else {
            guard let firstPage = try await contentPage(container: container, page: 1) else { return [] }
            if mediaDescendingOrderSupported == false {
                for page in stride(from: pageCount, through: 1, by: -1) {
                    try Task.checkCancellation()
                    let value: JSONDictionary
                    if page == 1 {
                        value = firstPage
                    } else {
                        guard let response = try await contentPage(container: container, page: page) else {
                            continue
                        }
                        value = response
                    }
                    let paths = try Self.validatedContentPaths(value)
                    appendUnique(paths, reversed: true)
                    try await onPage(boundedResult())
                    if reachedMediaLimit() { return boundedResult() }
                }
            } else {
                appendUnique(try Self.validatedContentPaths(firstPage))
                try await onPage(boundedResult())
                if reachedMediaLimit() { return boundedResult() }
                if pageCount >= 2 {
                    for page in 2...pageCount {
                        try Task.checkCancellation()
                        if let value = try await contentPage(container: container, page: page) {
                            appendUnique(try Self.validatedContentPaths(value))
                            try await onPage(boundedResult())
                            if reachedMediaLimit() { return boundedResult() }
                        }
                    }
                }
            }
        }
        return boundedResult()
    }

    private static func validatedContentPaths(_ value: JSONDictionary) throws -> [String] {
        guard let paths = value["path"] as? [String],
              paths.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else {
            throw CCAPIError.invalidResponse("Camera media response must contain a valid path array.")
        }
        return paths
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

    private func mergeMediaPathGroups(_ groups: [[String]]) -> [String] {
        var positions = Array(repeating: 0, count: groups.count)
        var merged: [String] = []
        var seen = Set<String>()
        while true {
            var advanced = false
            for index in groups.indices {
                guard positions[index] < groups[index].count else { continue }
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

    private func firstJSON(paths: [String], required: Bool,
                           preservingConnectionEvidence: Bool = false,
                           captureConfigurationRead: Bool = false) async throws -> JSONDictionary? {
        var failures: [String] = []
        var reasons = Set<CameraConnectionFailureReason>()
        for path in paths {
            do {
                let result = try await requestJSON(path: path, maximumBytes: captureConfigurationRead ? 64 * 1024 : nil,
                                                   captureConfigurationRead: captureConfigurationRead)
                if preservingConnectionEvidence { try Task.checkCancellation() }
                return result
            } catch {
                if error is CancellationError { throw error }
                if preservingConnectionEvidence {
                    if CameraConnectionFailureReason.isCancellation(error) { throw error }
                    try Task.checkCancellation()
                    reasons.insert(CameraConnectionFailureReason.classify(error) ?? .unknown)
                }
                failures.append("\(path): \(error.localizedDescription)")
            }
        }
        if required {
            if preservingConnectionEvidence { throw CCAPIError.connectionReadFailed(failures, reasons) }
            throw CCAPIError.invalidResponse("Camera JSON request failed.\n" + failures.map { "- \($0)" }.joined(separator: "\n"))
        }
        return nil
    }

    private func requestJSON(
        path: String,
        method: HTTPMethod = .get,
        json: JSONDictionary? = nil,
        timeoutInterval: TimeInterval = 10,
        maximumBytes: Int? = nil,
        mediaMetadata: Bool = false,
        captureConfigurationAttempt: CameraCaptureConfigurationAttempt? = nil,
        captureConfigurationRead: Bool = false,
        shootingSettingAttempt: CameraShootingSettingAttempt? = nil,
        shootingSettingRead: Bool = false
    ) async throws -> JSONDictionary {
        let request = try request(path: path, method: method, json: json, timeoutInterval: timeoutInterval)
        let readDeadline = (captureConfigurationRead || shootingSettingRead) ? ContinuousClock.now.advanced(by: .seconds(timeoutInterval)) : nil
        try captureConfigurationAttempt?.markDispatched()
        if let shootingSettingAttempt {
            try prepareShootingSettingRoute(path: path, attempt: shootingSettingAttempt)
            try shootingSettingAttempt.markDispatched()
        }
        let response: CameraHTTPResponse
        if shootingSettingAttempt != nil || shootingSettingRead {
            response = try await transport.sendShootingSetting(request)
        } else if captureConfigurationAttempt != nil || captureConfigurationRead {
            response = try await transport.sendCaptureConfiguration(request)
        } else {
            response = try await transport.send(request)
        }
        if let readDeadline, ContinuousClock.now >= readDeadline {
            throw CCAPIError.invalidResponse("Capture configuration readback exceeded its bounded deadline.")
        }
        if captureConfigurationAttempt != nil || captureConfigurationRead || shootingSettingAttempt != nil || shootingSettingRead {
            try CameraHTTPResponseIntegrity.validate(response, requestMethod: method.rawValue)
            guard response.statusCode != 206, response.header("content-range") == nil else {
                throw CCAPIError.invalidResponse("Capture configuration requires a complete response.")
            }
        }
        if shootingSettingRead, response.statusCode != 200 { throw CCAPIError.invalidResponse("Setting readback requires HTTP 200.") }
        if shootingSettingAttempt != nil || shootingSettingRead {
            guard response.body.count <= 64 * 1024 else { throw CCAPIError.invalidResponse("Setting response exceeded 64 KiB.") }
        }
        try validate(response, request: request)
        shootingSettingAttempt?.responded()
        if captureConfigurationAttempt != nil, response.body.count > 64 * 1024 {
            throw CCAPIError.invalidResponse("Capture configuration acknowledgment exceeded its size limit.")
        }
        if let maximumBytes, response.body.count > maximumBytes {
            throw CCAPIError.invalidResponse("Camera response at \(path) exceeded the \(maximumBytes)-byte limit.")
        }
        do {
            if shootingSettingAttempt != nil || shootingSettingRead { return try decodeShootingSettingJSONObject(response.body) }
            if captureConfigurationAttempt != nil || captureConfigurationRead { return try decodeForegroundJSONObject(response.body) }
            return try mediaMetadata ? CameraMediaMetadataJSON.decode(response.body) : decodeJSONObject(response.body)
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
        expectedStatusCode: Int? = nil,
        shootingSettingAttempt: CameraShootingSettingAttempt? = nil
    ) async throws {
        let request = try request(path: path, method: method, json: json, timeoutInterval: timeoutInterval)
        if let shootingSettingAttempt {
            try prepareShootingSettingRoute(path: path, attempt: shootingSettingAttempt)
            try shootingSettingAttempt.markDispatched()
        }
        let response: CameraHTTPResponse
        if shootingSettingAttempt != nil { response = try await transport.sendShootingSetting(request) }
        else { response = try await transport.send(request) }
        if shootingSettingAttempt != nil {
            try CameraHTTPResponseIntegrity.validate(response, requestMethod: method.rawValue)
            guard response.statusCode != 206, response.header("content-range") == nil,
                  response.body.count <= 64 * 1024 else { throw CCAPIError.invalidResponse("Setting response was incomplete or oversized.") }
        }
        try validate(response, request: request)
        shootingSettingAttempt?.responded()
        if let expectedStatusCode, response.statusCode != expectedStatusCode {
            throw CCAPIError.invalidResponse(
                "Camera request \(method.rawValue) \(path) returned HTTP \(response.statusCode); " +
                    "expected HTTP \(expectedStatusCode)."
            )
        }
    }

    private func beginCameraMutation() throws {
        try Task.checkCancellation()
        guard !closing, bulbStartTask == nil, shortControlTask == nil,
              liveViewStartTask == nil, liveViewStopTask == nil, liveViewReleaseTask == nil,
              maintenanceLiveViewControlID == nil,
              shutterReleaseTask == nil, pendingShutterRelease == nil else {
            throw CCAPIError.invalidResponse("Release the shutter before another camera operation.")
        }
        cameraMutationCount += 1
    }

    private func validateShutterMutation(path: String, method: HTTPMethod, json: JSONDictionary?) throws {
        guard method != .get, closing || bulbStartTask != nil || shortControlTask != nil || shutterReleaseTask != nil || pendingShutterRelease != nil else { return }
        let action = json?["action"] as? String
        // Only existing, explicit cleanup contracts may pass the interlock.
        let safeStop =
            (method == .delete && (path.hasSuffix("/event/polling") || path.hasSuffix("/shooting/liveview") || path.hasSuffix("/shooting/liveview/multipart"))) ||
            (path.hasSuffix("/shooting/liveview") && method == .post && (json?["liveviewsize"] as? String) == "off") ||
            (path.hasSuffix("/shooting/liveview/rtp") && method == .post && action == "stop") ||
            (path.hasSuffix("/shooting/control/recbutton") && (method == .post || method == .put) && action == "stop") ||
            (path == "/ccapi/record/stop" && method == .post) ||
            (path.hasSuffix("/shooting/control/af") && (method == .post || method == .put) && action == "stop") ||
            (path.hasSuffix("/shooting/control/shutterbutton/manual") && (method == .post || method == .put) && action == "release") ||
            (path == "/ccapi/shutter/release" && method == .post)
        guard safeStop else {
            throw CCAPIError.invalidResponse("Release the shutter before another camera operation.")
        }
    }

    private func request(
        path: String,
        method: HTTPMethod,
        json: JSONDictionary? = nil,
        timeoutInterval: TimeInterval = 10
    ) throws -> URLRequest {
        try validateShutterMutation(path: path, method: method, json: json)
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
        // These physical-control replies acknowledge an entire command. A partial
        // representation cannot discharge the existing captured release/Stop owner.
        // Keep media reads and unrelated requests on their existing response contract.
        if Self.requiresCompleteControlReceipt(method: request.httpMethod ?? "GET", path: request.url?.path ?? ""),
           response.statusCode == 206 || response.header("content-range") != nil {
            throw CCAPIError.invalidResponse("Camera control acknowledgement was incomplete.")
        }
    }

    static func requiresCompleteControlReceipt(method: String, path: String) -> Bool {
        switch method {
        case "POST":
            return ["/shooting/control/shutterbutton", "/shooting/control/shutterbutton/manual",
                    "/shooting/control/af", "/shooting/liveview", "/shooting/liveview/rtp"]
                .contains { path.hasSuffix($0) }
        case "PUT":
            return path.hasSuffix("/shooting/control/shutterbutton/manual")
        case "DELETE":
            return path.hasSuffix("/shooting/liveview") || path.hasSuffix("/shooting/liveview/multipart")
        default:
            return false
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
