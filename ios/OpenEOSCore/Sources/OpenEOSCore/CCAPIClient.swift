import Foundation

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

public actor CCAPIClient {
    private static let maximumErrorBodyCharacters = 2_000
    private static let maximumMediaItems = 500
    private static let maximumMediaPages = 100
    private static let maximumMediaTreeDepth = 4
    private static let halfPressNanoseconds: UInt64 = 350_000_000

    private let baseURL: URL
    private let baseURLString: String
    private let authorization: String?
    private let transport: any CameraHTTPTransport
    private let requestedMode: CCAPIConnectionMode
    private var resolvedMode: CCAPIConnectionMode
    private var initialized = false
    private var apiVersionPrefixes = ["/ccapi/ver100"]
    private var preferredVersionPrefix = "/ccapi/ver100"
    private var operations = Set<CCAPIOperation>()
    private var observedFeatures = Set<CameraFeature>()
    private var settingPaths: [String: String] = [:]
    private var cachedSettings: JSONDictionary?
    private var cachedModel = "Canon Camera"
    private var recording: Bool?
    private var liveViewSizeControlSupported = true
    private var activeLiveViewSize = LiveViewSize.medium

    public init(
        baseURL value: String,
        mode: CCAPIConnectionMode = .automatic,
        username: String = "",
        password: String = "",
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
            initialized = true
            return
        }

        var errors: [String] = []
        for path in ["/ccapi", "/ccapi/"] {
            do {
                let discovery = try await requestJSON(path: path)
                parseDiscovery(discovery)
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
                cachedModel = value.string("productname", default: cachedModel)
                observedFeatures.insert(.cameraIdentity)
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

    public func info() async throws -> CameraInfo {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            let value = try await requestJSON(path: "/ccapi/info")
            cachedModel = value.string("model", default: "Unknown camera")
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
            return try parseSimulatorStatus(await requestJSON(path: "/ccapi/status"))
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

        return CameraStatus(
            batteryLevel: batteryState.level,
            batteryStatus: batteryState.status,
            recording: recording,
            mode: settingObject(in: settings, aliases: ["shootingmode"])?.string("value", default: "unknown") ?? "unknown",
            mediaAvailable: storage.map(parseStorage),
            exposure: exposureState(settings),
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
        if supports(.post, suffix: "/shooting/liveview") || observedFeatures.contains(.liveView) {
            supported.formUnion([.liveView, .liveViewJPEGPolling])
        }
        if commandOperation(suffix: "/shooting/control/recbutton") != nil { supported.insert(.videoRecording) }
        if commandOperation(suffix: "/shooting/control/shutterbutton") != nil ||
            commandOperation(suffix: "/shooting/control/shutterbutton/manual") != nil {
            supported.insert(.stillCapture)
        }
        if commandOperation(suffix: "/shooting/control/shutterbutton/manual") != nil {
            supported.insert(.shutterHalfPress)
        }
        if commandOperation(suffix: "/shooting/control/afpoint") != nil { supported.insert(.tapFocus) }
        if supports(.get, suffix: "/contents") {
            supported.formUnion([.mediaBrowser, .mediaDownload])
        }

        let allPlanned: Set<CameraFeature> = [
            .liveViewRTP, .stillCapture, .shutterHalfPress, .videoRecording, .tapFocus,
            .focusDrive, .mediaBrowser, .mediaDownload,
        ]
        let liveSizes = liveViewSizeControlSupported ? LiveViewSize.allCases : [activeLiveViewSize]
        return CameraCapabilities(
            settings: controls,
            matrix: CapabilityMatrix(
                supported: supported,
                planned: allPlanned.subtracting(supported),
                reasons: [
                    .liveViewRTP: "RTP decoding is not implemented; this client uses bounded JPEG polling.",
                    .focusDrive: "CCAPI focus drive is not exposed without a camera-advertised operation.",
                ]
            ),
            liveView: LiveViewCapabilities(
                sources: supported.contains(.liveView) ? [.ccapiJPEGPolling] : [],
                defaultSource: .ccapiJPEGPolling,
                sizes: liveSizes,
                defaultSize: liveSizes.contains(.medium) ? .medium : activeLiveViewSize,
                maximumFPS: 30
            ),
            profile: CameraProfile.from(modelName: cachedModel)
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
            return try await status()
        }

        let settings = try await cachedOrLoadShootingSettings()
        guard let control = cameraSettings(settings).first(where: { $0.key == key }),
              control.values.contains(value) else {
            throw CCAPIError.invalidSetting(key: key, value: value)
        }
        try await putSettingValue(candidateKeys: aliases(for: key), value: value)
        return try await status()
    }

    public func captureStill() async throws -> CameraStatus {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            _ = try await requestJSON(path: "/ccapi/capture/still", method: .post, json: ["af": true])
            return try await status()
        }

        let direct = commandOperation(suffix: "/shooting/control/shutterbutton")
        let manual = commandOperation(suffix: "/shooting/control/shutterbutton/manual")
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
            return try await status()
        }
        guard let manual = commandOperation(suffix: "/shooting/control/shutterbutton/manual") else {
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

    public func startRecording() async throws -> CameraStatus {
        try await setRecording(true)
    }

    public func stopRecording() async throws -> CameraStatus {
        try await setRecording(false)
    }

    public func tapFocus(x: Double, y: Double) async throws -> FocusResult {
        try await ensureInitialized()
        guard (0...1).contains(x), (0...1).contains(y) else {
            throw CCAPIError.invalidResponse("Focus coordinates must be normalized from 0 through 1.")
        }
        if resolvedMode == .simulator {
            let value = try await requestJSON(path: "/ccapi/focus/tap", method: .post, json: ["x": x, "y": y])
            return FocusResult(
                accepted: value.bool("ok") ?? false,
                x: (value["x"] as? NSNumber)?.doubleValue ?? x,
                y: (value["y"] as? NSNumber)?.doubleValue ?? y
            )
        }
        guard let operation = commandOperation(suffix: "/shooting/control/afpoint") else {
            throw CCAPIError.unsupported(.tapFocus)
        }
        try await commandOK(operation: operation, json: ["x": x, "y": y])
        return FocusResult(accepted: true, x: x, y: y)
    }

    public func startLiveView(_ request: LiveViewRequest = LiveViewRequest()) async throws {
        try await ensureInitialized()
        if resolvedMode == .simulator { return }
        guard supports(.post, suffix: "/shooting/liveview") else {
            throw CCAPIError.unsupported(.liveView)
        }
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
        observedFeatures.formUnion([.liveView, .liveViewJPEGPolling])
    }

    public func stopLiveView() async {
        guard initialized, resolvedMode != .simulator else { return }
        try? await requestOK(path: apiPath(.delete, suffix: "/shooting/liveview"), method: .delete)
    }

    public func liveViewFrame(cacheKey: Int64) async throws -> LiveViewFrame {
        try await ensureInitialized()
        let paths: [String]
        if resolvedMode == .simulator {
            paths = ["/ccapi/liveview/frame"]
        } else {
            paths = [
                apiPath(.get, suffix: "/shooting/liveview/flip"),
                apiPath(.get, suffix: "/shooting/liveview/flipdetail") + "?kind=image",
                apiPath(.get, suffix: "/shooting/liveview"),
            ]
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
                let response = try await transport.send(request)
                try validate(response, request: request)
                let contentType = response.header("content-type")
                if Self.isTextContentType(contentType) {
                    throw CCAPIError.invalidResponse("Live View returned \(contentType ?? "text") instead of image bytes.")
                }
                let frame = try JPEGFrameParser.validatedImageData(response.body, contentType: contentType)
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
            return value.array("items")?.objects.map {
                CameraMediaItem(
                    id: $0.string("id"),
                    name: $0.string("name"),
                    kind: $0.string("kind", default: "other"),
                    sizeBytes: $0.integer64("size_bytes"),
                    captureTime: $0.string("capture_time").nilIfEmpty
                )
            } ?? []
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
        if !mediaPaths.isEmpty { observedFeatures.formUnion([.mediaBrowser, .mediaDownload]) }
        return mediaPaths.prefix(Self.maximumMediaItems).map {
            CameraMediaItem(id: $0, name: ($0 as NSString).lastPathComponent, kind: Self.mediaKind($0))
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
                    contentType: download.header("content-type")
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

    public func diagnosticReport(
        snapshot: CameraSnapshot?,
        liveView: CCAPILiveViewMetrics = CCAPILiveViewMetrics(),
        lastError: String? = nil
    ) -> String {
        CCAPIDiagnosticReport.make(
            baseURL: baseURL,
            mode: resolvedMode,
            versions: apiVersionPrefixes,
            snapshot: snapshot,
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

    private func parseDiscovery(_ value: JSONDictionary) {
        var versions = Set<String>()
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
            let rawPath = entry.string("path").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !rawPath.isEmpty else { continue }
            let path = rawPath.hasPrefix("/ccapi/")
                ? rawPath
                : "/ccapi/\(version)/\(rawPath.trimmingCharacters(in: CharacterSet(charactersIn: "/")))"
            for method in [HTTPMethod.get, .put, .post, .delete] {
                if Self.methodSupported(entry[method.rawValue.lowercased()]) {
                    operations.insert(CCAPIOperation(method: method, path: path))
                }
            }
        }
    }

    private func versionedPaths(_ suffix: String) -> [String] {
        apiVersionPrefixes.map { "\($0)\(suffix)" }
    }

    private func supports(_ method: HTTPMethod, suffix: String) -> Bool {
        operations.contains { $0.method == method && $0.path.hasSuffix(suffix) }
    }

    private func commandOperation(suffix: String) -> CCAPIOperation? {
        let matching = operations.filter { [.post, .put].contains($0.method) && $0.path.hasSuffix(suffix) }
        return matching.first { $0.path.hasPrefix(preferredVersionPrefix) }
            ?? matching.max { Self.pathVersion($0.path) < Self.pathVersion($1.path) }
    }

    private func apiPath(_ method: HTTPMethod, suffix: String) -> String {
        let matching = operations.filter { $0.method == method && $0.path.hasSuffix(suffix) }
        return matching.first { $0.path.hasPrefix(preferredVersionPrefix) }?.path
            ?? matching.max { Self.pathVersion($0.path) < Self.pathVersion($1.path) }?.path
            ?? "\(preferredVersionPrefix)\(suffix)"
    }

    private func loadShootingSettings() async throws -> JSONDictionary? {
        settingPaths.removeAll()
        var merged: JSONDictionary = [:]
        for path in versionedPaths("/shooting/settings") {
            guard let value = try await firstJSON(paths: [path], required: false) else { continue }
            let prefix = String(path.dropLast("/shooting/settings".count))
            for (key, setting) in value {
                settingPaths[key] = settingPaths[key] ?? "\(prefix)/shooting/settings/\(key)"
                if merged[key] == nil { merged[key] = setting }
            }
        }
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
            if let setting = settingObject(in: value, aliases: aliases), let control = control(key, label, setting) {
                controls.append(control)
            }
        }
        for key in value.keys.sorted() where !Self.allPrimaryAliases.contains(key) {
            guard let setting = value.object(key), let settingControl = control(key, Self.settingLabel(key), setting) else {
                continue
            }
            controls.append(settingControl)
        }
        return controls
    }

    private func control(_ key: String, _ label: String, _ value: JSONDictionary) -> CameraSetting? {
        let options = (value.array("ability")?.strings ?? []).removingDuplicates()
        let current = value.string("value")
        guard !current.isEmpty, !options.isEmpty else { return nil }
        return CameraSetting(key: key, label: label, value: current, values: options)
    }

    private func putSettingValue(candidateKeys: [String], value: String) async throws {
        if settingPaths.isEmpty { _ = try await loadShootingSettings() }
        let paths = candidateKeys.flatMap { key -> [String] in
            if let path = settingPaths[key] { return [path] }
            return versionedPaths("/shooting/settings/\(key)")
        }.removingDuplicates()
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

    private func aliases(for key: String) -> [String] {
        switch key {
        case "shutter": ["tv", "shutterspeed", "shutter"]
        case "aperture": ["av", "aperture"]
        case "whitebalance": ["wb", "whitebalance", "white_balance"]
        default: [key]
        }
    }

    private func setRecording(_ enabled: Bool) async throws -> CameraStatus {
        try await ensureInitialized()
        if resolvedMode == .simulator {
            _ = try await requestJSON(path: "/ccapi/record/\(enabled ? "start" : "stop")", method: .post, json: [:])
        } else {
            guard let operation = commandOperation(suffix: "/shooting/control/recbutton") else {
                throw CCAPIError.unsupported(.videoRecording)
            }
            try await commandOK(operation: operation, json: ["action": enabled ? "start" : "stop"])
        }
        recording = enabled
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
            .stillCapture, .shutterHalfPress, .videoRecording, .tapFocus,
            .exposureControl, .whiteBalanceControl, .mediaBrowser, .mediaDownload,
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
            profile: CameraProfile.from(modelName: cachedModel)
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
            mode: value.string("mode", default: "unknown"),
            mediaAvailable: media.bool("available"),
            remainingMinutes: media.integer("remaining_minutes"),
            exposure: ExposureState(
                iso: exposure.string("iso", default: "-"),
                shutter: exposure.string("shutter", default: "-"),
                aperture: exposure.string("aperture", default: "-"),
                whiteBalance: exposure.string("white_balance", default: "-")
            )
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

    private func parseStorage(_ value: JSONDictionary) -> Bool {
        let cards = value.array("storagelist")?.objects
            ?? value.array("storage")?.objects
            ?? [value]
        return cards.contains { card in
            let status = card.string("status").lowercased()
            let access = card.string("accesscapability", default: card.string("access")).lowercased()
            if ["ready", "access"].contains(status) || ["readwrite", "readonly"].contains(access) { return true }
            let hasSpace = ["spacesize", "maxsize", "capacity", "free"].contains { (card.integer64($0) ?? 0) > 0 }
            return hasSpace && !["not_inserted", "none"].contains(status)
        }
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
            components.scheme = nil
            components.host = nil
            components.port = nil
            components.user = nil
            components.password = nil
            let normalized = components.string ?? ""
            guard normalized.hasPrefix("/ccapi/") else { throw CCAPIError.invalidResponse("Invalid media path: \(value)") }
            return normalized
        }
        guard value.hasPrefix("/ccapi/") else { throw CCAPIError.invalidResponse("Invalid media path: \(value)") }
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
            "colortemperature": "Color temperature", "exposurecompensation": "Exposure compensation",
        ]
        if let label = known[key] { return label }
        return key.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ").capitalized
    }

    private static func isTextContentType(_ value: String?) -> Bool {
        guard let normalized = value?.lowercased() else { return false }
        return normalized.hasPrefix("text/") || normalized.contains("json") || normalized.contains("html")
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
