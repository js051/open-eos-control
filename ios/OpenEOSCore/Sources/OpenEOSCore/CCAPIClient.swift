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
    private static let maximumErrorBodyCharacters = 2_000
    private static let maximumMediaItems = 500
    private static let maximumMediaPages = 100
    private static let maximumMediaTreeDepth = 4
    private static let maximumMediaThumbnailBytes = 8 * 1024 * 1024
    private static let maximumMediaPreviewBytes = 32 * 1024 * 1024
    private static let maximumCapabilityEvidenceItems = 256
    private static let maximumCapabilityEvidenceItemCharacters = 512
    private static let maximumRTPSessionDescriptionBytes = 64 * 1024
    private static let halfPressNanoseconds: UInt64 = 350_000_000
    private static let imageQualitySettingKey = "stillimagequality"
    private static let imageQualityFields = ["raw", "jpeg", "heif"]
    private static let wbShiftSettingKey = "wbshift"
    private static let wbShiftFields = ["ba", "mg"]
    private static let maximumStructuredSettingOptions = 256

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
    private var settingPaths: [String: String] = [:]
    private var cachedSettings: JSONDictionary?
    private var enforceAdvertisedOperations = false
    private var settingsLoaded = false
    private var discoverySource = "unknown"
    private var cachedModel = "Canon Camera"
    private var recording: Bool?
    private var bulbExposureActive = false
    private var liveViewSizeControlSupported = true
    private var activeLiveViewSize = LiveViewSize.medium
    private var activeLiveViewSource: LiveViewSource?
    private var rtpSession: (any CCAPIRTPSession)?
    private var nativeGeometryCacheKey: Int64 = 0
    private var latestLiveViewGeometry: CCAPILiveViewGeometry?

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
        resolvedMode = resolveMode(requestedMode)
        if resolvedMode == .simulator {
            discoverySource = "simulator contract"
            initialized = true
            return
        }

        var errors: [String] = []
        for path in ["/ccapi", "/ccapi/"] {
            do {
                let discovery = try await requestJSON(path: path)
                parseDiscovery(discovery, source: "GET \(path)")
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
                cachedModel = value.string("productname", default: cachedModel)
                observedFeatures.insert(.cameraIdentity)
                enforceAdvertisedOperations = true
                initialized = true
                return
            } catch {
                if error is CancellationError { throw error }
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
            observedFeatures.formUnion([.batteryStatus, .storageStatus])
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
            rawBatteryJSON: JSONString(battery),
            rawStorageJSON: JSONString(storage)
        )
    }

    public func capabilities() async throws -> CameraCapabilities {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            return try await simulatorCapabilities()
        }
        let settings = try await cachedOrLoadShootingSettings()
        let controls = cameraSettings(settings)
        var supported = observedFeatures
        if controls.contains(where: { ["iso", "shutter", "aperture"].contains($0.key) }) {
            supported.insert(.exposureControl)
        }
        if controls.contains(where: { $0.key == "whitebalance" }) {
            supported.insert(.whiteBalanceControl)
        }
        if controls.contains(where: { !Self.primarySettingKeys.contains($0.key) }) {
            supported.insert(.advancedSettings)
        }
        let supportsJPEGLiveView = supportsCompleteLiveView()
        let supportsRTPLiveView = supportsRTPLiveView()
        if supportsJPEGLiveView || supportsRTPLiveView || observedFeatures.contains(.liveView) {
            supported.insert(.liveView)
        }
        if supportsJPEGLiveView { supported.insert(.liveViewJPEGPolling) }
        if supportsRTPLiveView { supported.insert(.liveViewRTP) }
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

        let allPlanned: Set<CameraFeature> = [
            .liveViewRTP, .stillCapture, .bulbExposure, .autofocus, .shutterHalfPress, .videoRecording, .tapFocus,
            .clickWhiteBalance,
            .focusDrive, .mediaBrowser, .mediaThumbnail, .mediaPreview, .mediaDownload,
            .mediaDelete,
        ]
        let liveSizes = liveViewSizeControlSupported ? LiveViewSize.allCases : [activeLiveViewSize]
        return CameraCapabilities(
            settings: controls,
            matrix: CapabilityMatrix(
                supported: supported,
                planned: allPlanned.subtracting(supported),
                reasons: [
                    .liveViewRTP: "Canon RTP needs advertised SDP/start endpoints and a reachable camera-Wi-Fi IPv4 address.",
                    .autofocus: "The camera advertised neither CCAPI POST autofocus nor a verified manual half-press operation.",
                    .tapFocus: "The camera must advertise PUT afframeposition and detailed Live View metadata for coordinate Tap AF.",
                    .clickWhiteBalance: "The camera must advertise POST clickwb and detailed Live View metadata for Click WB.",
                    .focusDrive: "The camera did not advertise the verified CCAPI POST drivefocus operation.",
                ]
            ),
            liveView: LiveViewCapabilities(
                sources: ccapiLiveViewSources(),
                defaultSource: ccapiLiveViewSources().first ?? .auto,
                sizes: liveSizes,
                defaultSize: liveSizes.contains(.medium) ? .medium : activeLiveViewSize,
                maximumFPS: 30
            ),
            profile: CameraProfile.from(modelName: cachedModel),
            evidence: capabilityEvidence()
        )
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
            default:
                throw CCAPIError.unsupported(.advancedSettings)
            }
            observedFeatures.insert(featureForSetting(key))
            return try await status()
        }

        let settings = try await cachedOrLoadShootingSettings()
        guard let control = cameraSettings(settings).first(where: { $0.key == key }),
              control.values.contains(value) else {
            throw CCAPIError.invalidSetting(key: key, value: value)
        }
        if let structured = structuredSettingParts(key) {
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

    public func captureStill() async throws -> CameraStatus {
        try await ensureInitialized()
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
        guard resolvedMode != .simulator else {
            throw CCAPIError.unsupported(.focusDrive)
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
        latestLiveViewGeometry = nil
        if resolvedMode == .simulator {
            activeLiveViewSource = .simulatorFrame
            observedFeatures.insert(.liveView)
            return
        }
        let requestedSource = request.source
        let selectedSource: LiveViewSource
        switch requestedSource {
        case .auto:
            selectedSource = supportsRTPLiveView() ? .ccapiRTP : .ccapiJPEGPolling
        case .ccapiRTP, .ccapiJPEGPolling:
            selectedSource = requestedSource
        default:
            throw CCAPIError.invalidResponse(
                "\(requestedSource.rawValue) is not available through the CCAPI network client."
            )
        }

        if selectedSource == .ccapiRTP {
            do {
                try await startRTPLiveView(request)
                return
            } catch {
                guard requestedSource == .auto, supportsCompleteLiveView() else { throw error }
            }
        }
        try await startJPEGLiveView(request)
    }

    private func startJPEGLiveView(_ request: LiveViewRequest) async throws {
        guard supportsCompleteLiveView() else { throw CCAPIError.unsupported(.liveView) }
        let path = apiPath(.post, suffix: "/shooting/liveview")
        do {
            try await requestOK(
                path: path,
                method: .post,
                json: ["cameradisplay": "on", "liveviewsize": request.size.rawValue]
            )
            liveViewSizeControlSupported = true
        } catch let error as CCAPIError {
            guard case let .http(statusCode, _, _, _) = error, statusCode == 400 else { throw error }
            try await requestOK(path: path, method: .post, json: ["cameradisplay": "on"])
            liveViewSizeControlSupported = false
        }
        activeLiveViewSize = request.size
        activeLiveViewSource = .ccapiJPEGPolling
        observedFeatures.formUnion([.liveView, .liveViewJPEGPolling])
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
        case .ccapiJPEGPolling:
            if !enforceAdvertisedOperations || supports(.delete, suffix: "/shooting/liveview") {
                try? await requestOK(path: apiPath(.delete, suffix: "/shooting/liveview"), method: .delete)
            }
        default:
            break
        }
        activeLiveViewSource = nil
    }

    public func currentLiveViewSource() -> LiveViewSource? {
        activeLiveViewSource
    }

    public func currentNativeLiveViewSourceURL() -> URL? {
        rtpSession?.sourceURL
    }

    public func setLiveViewTargetFPS(_ fps: Int) async {
        await rtpSession?.setTargetFPS(min(max(fps, 1), 30))
    }

    public func liveViewFrame(cacheKey: Int64) async throws -> LiveViewFrame {
        try await ensureInitialized()
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
                failures.append("\(sourceURL.absoluteString): \(error.localizedDescription)")
            }
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
                    previewAvailable: ["image", "raw"].contains($0.string("kind", default: "other").lowercased())
                )
            } ?? []
            observedFeatures.insert(.mediaBrowser)
            return items
        }
        guard supports(.get, suffix: "/contents") else { throw CCAPIError.unsupported(.mediaBrowser) }

        var pending: [(path: String, depth: Int)] = [(apiPath(.get, suffix: "/contents"), 0)]
        var visited = Set<String>()
        var mediaPaths: [String] = []
        while !pending.isEmpty, mediaPaths.count < Self.maximumMediaItems {
            let next = pending.removeFirst()
            let container = try normalizeCameraResource(next.path).components(separatedBy: "?")[0]
            guard next.depth <= Self.maximumMediaTreeDepth, visited.insert(container).inserted else { continue }
            for rawPath in try await contentPaths(container: container) {
                let path = try normalizeCameraResource(rawPath).components(separatedBy: "?")[0]
                if Self.isMediaPath(path) {
                    if !mediaPaths.contains(path) { mediaPaths.append(path) }
                } else if !visited.contains(path) {
                    pending.append((path, next.depth + 1))
                }
            }
        }
        observedFeatures.insert(.mediaBrowser)
        return mediaPaths.prefix(Self.maximumMediaItems).map {
            CameraMediaItem(
                id: $0,
                name: ($0 as NSString).lastPathComponent,
                kind: Self.mediaKind($0),
                previewAvailable: ["image", "raw"].contains(Self.mediaKind($0))
            )
        }
    }

    public func downloadMedia(_ item: CameraMediaItem, to destination: URL) async throws -> CameraMediaDownload {
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
            let download = try await transport.download(request(path: path, method: .get))
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
        guard ["image", "raw"].contains(item.kind.lowercased()) else {
            throw CCAPIError.invalidResponse("Display preview is available only for camera image items.")
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

    private func directShutterOperation() -> CCAPIOperation? {
        operation(.post, suffix: "/shooting/control/shutterbutton")
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
            supports(.post, suffix: "/shooting/liveview") &&
            supports(.delete, suffix: "/shooting/liveview")
    }

    private func supportsCoordinateClickWhiteBalance() -> Bool {
        clickWhiteBalanceOperation() != nil &&
            detailedLiveViewOperation() != nil &&
            supports(.post, suffix: "/shooting/liveview") &&
            supports(.delete, suffix: "/shooting/liveview")
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
        guard latestLiveViewGeometry == nil, activeLiveViewSource == .ccapiRTP else { return }
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
        supports(.post, suffix: "/shooting/liveview") &&
            supports(.delete, suffix: "/shooting/liveview") &&
            !liveViewFramePaths().isEmpty
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

    private func supportsMediaDelete() -> Bool {
        operations.contains {
            $0.method == .delete && ($0.path.hasSuffix("/contents") || $0.path.contains("/contents/"))
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
        let writableSettings = settingPaths.keys.map {
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
            truncated: protocolVersions.count > Self.maximumCapabilityEvidenceItems ||
                commands.count > Self.maximumCapabilityEvidenceItems ||
                writableSettings.count > Self.maximumCapabilityEvidenceItems ||
                observedFeatures.count > Self.maximumCapabilityEvidenceItems
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
                if !enforceAdvertisedOperations || operations.contains(CCAPIOperation(method: .put, path: settingPath)) {
                    settingPaths[key] = settingPaths[key] ?? settingPath
                }
                if merged[key] == nil { merged[key] = setting }
            }
        }
        settingsLoaded = true
        cachedSettings = merged.isEmpty ? nil : merged
        return cachedSettings
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

    private static func boundedIntegerRangeValues(_ range: JSONDictionary?) -> [String] {
        guard let range,
              let minimum = strictInteger(range["min"]),
              let maximum = strictInteger(range["max"]),
              let step = strictInteger(range["step"]),
              step > 0,
              minimum <= maximum else { return [] }
        let (distance, overflow) = maximum.subtractingReportingOverflow(minimum)
        guard !overflow else { return [] }
        let (count, countOverflow) = (distance / step).addingReportingOverflow(1)
        guard !countOverflow, count >= 1, count <= maximumStructuredSettingOptions else { return [] }
        return (0..<count).map { String(minimum + ($0 * step)) }
    }

    private static func strictInteger(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
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
        default: .advancedSettings
        }
    }

    private func setRecording(_ enabled: Bool) async throws -> CameraStatus {
        try await ensureInitialized()
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
        let controls = [
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
        let supported: Set<CameraFeature> = [
            .cameraIdentity, .batteryStatus, .storageStatus, .liveView, .liveViewJPEGPolling,
            .stillCapture, .autofocus, .shutterHalfPress, .videoRecording, .tapFocus, .clickWhiteBalance,
            .exposureControl, .whiteBalanceControl, .mediaBrowser, .mediaThumbnail, .mediaPreview, .mediaDownload,
            .mediaDelete,
        ]
        return CameraCapabilities(
            settings: controls,
            matrix: CapabilityMatrix(supported: supported, planned: [.liveViewRTP, .focusDrive]),
            liveView: LiveViewCapabilities(
                sources: [.simulatorFrame],
                defaultSource: .simulatorFrame,
                sizes: [.medium],
                defaultSize: .medium,
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
            storageDeviceCount: media.integer("devices")
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

    private func contentPaths(container: String) async throws -> [String] {
        let pageInfo = try await firstJSON(
            paths: ["\(container)?kind=number", "\(container)?type=all,kind=number"],
            required: false
        )
        let pageCount = min(pageInfo?.integer("pagenumber") ?? 0, Self.maximumMediaPages)
        let pages = pageCount > 0 ? Array(1...pageCount) : [0]
        var result: [String] = []
        for page in pages {
            let candidates = page == 0
                ? [container]
                : ["\(container)?page=\(page)&order=desc", "\(container)?page=\(page)"]
            guard let value = try await firstJSON(paths: candidates, required: true) else { continue }
            result.append(contentsOf: value.array("path")?.strings ?? [])
        }
        return result.removingDuplicates()
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
        json: JSONDictionary? = nil
    ) async throws -> JSONDictionary {
        let request = try request(path: path, method: method, json: json)
        let response = try await transport.send(request)
        try validate(response, request: request)
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
        json: JSONDictionary? = nil
    ) async throws {
        let request = try request(path: path, method: method, json: json)
        let response = try await transport.send(request)
        try validate(response, request: request)
    }

    private func request(path: String, method: HTTPMethod, json: JSONDictionary? = nil) throws -> URLRequest {
        guard let url = URL(string: baseURLString + path) else {
            throw CCAPIError.invalidResponse("Invalid camera request path: \(path)")
        }
        var value = request(url: url, method: method)
        if let json {
            value.httpBody = try encodeJSONObject(json)
            value.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        return value
    }

    private func request(url: URL, method: HTTPMethod) -> URLRequest {
        var value = URLRequest(url: url)
        value.httpMethod = method.rawValue
        value.cachePolicy = .reloadIgnoringLocalCacheData
        value.timeoutInterval = 10
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
        var queryItems = components.queryItems ?? []
        queryItems.append(URLQueryItem(name: "t", value: String(cacheKey)))
        components.queryItems = queryItems
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

    private static func methodSupported(_ value: Any?) -> Bool {
        switch value {
        case let value as Bool: value
        case let value as NSNumber: value.intValue != 0
        case let value as String: !value.isEmpty && !["false", "no", "none", "unsupported"].contains(value.lowercased())
        case .some: true
        case .none: false
        }
    }

    private static func settingLabel(_ key: String) -> String {
        let known = [
            "afmethod": "AF method", "afoperation": "AF operation", "drivemode": "Drive mode",
            "meteringmode": "Metering", "picturestyle": "Picture style", "shootingmode": "Shooting mode",
            "stillimagequality": "Image quality", "moviequality": "Movie quality",
            "stillimagequality.raw": "RAW quality", "stillimagequality.jpeg": "JPEG quality",
            "stillimagequality.heif": "HEIF quality",
            "wbshift.ba": "WB shift B/A", "wbshift.mg": "WB shift M/G",
            "colortemperature": "Color temperature", "exposurecompensation": "Exposure compensation",
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
