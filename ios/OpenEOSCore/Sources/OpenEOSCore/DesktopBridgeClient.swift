import Foundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct DesktopBridgeCamera: Identifiable, Equatable, Sendable {
    public let id: String
    public let model: String
    public let port: String
    public let engine: String

    public init(id: String, model: String, port: String, engine: String) {
        self.id = id
        self.model = model
        self.port = port
        self.engine = engine
    }
}

public enum DesktopBridgeError: Error, Equatable, Sendable {
    case invalidBaseURL(String)
    case notInitialized
    case invalidResponse(String)
    case http(
        statusCode: Int,
        method: String,
        url: String,
        code: String,
        message: String,
        feature: String?,
        engine: String?
    )
    case destinationExists(String)
}

extension DesktopBridgeError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case let .invalidBaseURL(value):
            "Invalid Desktop Bridge URL: \(value)"
        case .notInitialized:
            "The Desktop Bridge session has not been initialized."
        case let .invalidResponse(message):
            message
        case let .http(statusCode, method, url, code, message, feature, engine):
            [
                "Desktop Bridge request failed: \(method) \(url) returned HTTP \(statusCode) [\(code)].",
                message,
                feature.map { "Feature: \($0)" },
                engine.map { "Engine: \($0)" },
            ]
            .compactMap { $0 }
            .joined(separator: "\n")
        case let .destinationExists(value):
            "The media destination already exists: \(value)"
        }
    }
}

public actor DesktopBridgeClient {
    private static let serviceName = "open-eos-control-bridge"
    private static let maximumLiveViewFrameBytes = 12 * 1024 * 1024
    private static let maximumErrorBodyBytes = 2_000
    private static let maximumEvidenceItems = 256
    private static let maximumEvidenceItemCharacters = 512
    private static let pathSegmentAllowed = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    )

    private let baseURL: URL
    private let bearerToken: String
    private let cameraID: String?
    private let profileHint: String?
    private let transport: any CameraHTTPTransport

    private var sessionID: String?
    private var sessionEngine: String?
    private var bridgeVersion: String?

    public init(
        baseURL: String,
        token: String = "",
        cameraID: String? = nil,
        profileHint: String? = "Canon EOS R6 Mark III",
        transport: any CameraHTTPTransport = URLSessionCameraHTTPTransport()
    ) throws {
        let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            var components = URLComponents(string: trimmed),
            let scheme = components.scheme?.lowercased(),
            ["http", "https"].contains(scheme),
            components.host?.isEmpty == false,
            components.user == nil,
            components.password == nil,
            components.query == nil,
            components.fragment == nil,
            components.path.isEmpty || components.path == "/"
        else {
            throw DesktopBridgeError.invalidBaseURL(baseURL)
        }
        components.scheme = scheme
        components.path = ""
        guard let normalizedURL = components.url else {
            throw DesktopBridgeError.invalidBaseURL(baseURL)
        }
        self.baseURL = normalizedURL
        bearerToken = token.trimmingCharacters(in: .whitespacesAndNewlines)
        self.cameraID = cameraID?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.profileHint = profileHint?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.transport = transport
    }

    public func sanitizedBaseURL() -> URL {
        baseURL
    }

    public func discoverCameras() async throws -> [DesktopBridgeCamera] {
        try await validateService()
        let body = try await getJSON(endpoint(["v1", "cameras"]))
        return body.array("cameras").compactMap { value in
            guard
                let camera = value as? BridgeJSON,
                let id = camera.nonEmptyString("id"),
                let model = camera.nonEmptyString("model")
            else { return nil }
            return DesktopBridgeCamera(
                id: id,
                model: model,
                port: camera.string("port") ?? "",
                engine: camera.string("engine") ?? "libgphoto2"
            )
        }
    }

    public func initialize() async throws {
        guard sessionID == nil else { return }
        try await validateService()
        var payload: BridgeJSON = ["engine": "auto"]
        if let cameraID { payload["cameraId"] = cameraID }
        if let profileHint { payload["profileHint"] = profileHint }
        let body = try await postJSON(endpoint(["v1", "session"]), payload: payload)
        guard let id = body.nonEmptyString("id") else {
            throw DesktopBridgeError.invalidResponse("Desktop Bridge did not return a session ID.")
        }
        sessionID = id
        sessionEngine = body.nonEmptyString("engine")
    }

    public func close() async {
        guard let sessionID else { return }
        defer {
            self.sessionID = nil
            sessionEngine = nil
        }
        try? await requestOK(endpoint(["v1", "session", sessionID]), method: "DELETE")
    }

    public func connectSnapshot() async throws -> CameraSnapshot {
        try await initialize()
        let info = try await info()
        let status = try await status()
        let capabilities = try await capabilities()
        return CameraSnapshot(info: info, status: status, capabilities: capabilities)
    }

    public func info() async throws -> CameraInfo {
        let body = try await getJSON(sessionEndpoint(["info"]))
        guard let model = body.nonEmptyString("model") else {
            throw DesktopBridgeError.invalidResponse("Desktop Bridge camera model is missing.")
        }
        return CameraInfo(
            connected: body.bool("connected") ?? true,
            model: model,
            serial: body.string("serial") ?? "unknown",
            api: body.string("api") ?? "desktop-bridge/v1"
        )
    }

    public func status() async throws -> CameraStatus {
        let body = try await getJSON(sessionEndpoint(["status"]))
        return parseStatus(body)
    }

    public func capabilities() async throws -> CameraCapabilities {
        let body = try await getJSON(sessionEndpoint(["capabilities"]))
        let settings = body.array("settings").compactMap(Self.parseSetting)
        let supported = Set(body.stringArray("supported").compactMap(CameraFeature.init(rawValue:)))
        let planned = Set(body.stringArray("planned").compactMap(CameraFeature.init(rawValue:))).subtracting(supported)
        let reasons = body.dictionary("reasons").reduce(into: [CameraFeature: String]()) { result, entry in
            guard let feature = CameraFeature(rawValue: entry.key), let reason = entry.value as? String else { return }
            result[feature] = reason
        }

        let liveViewBody = body.dictionary("liveView")
        let sources = Self.unique(liveViewBody.stringArray("sources").compactMap(Self.parseLiveViewSource))
        let sizes = Self.unique(liveViewBody.stringArray("sizes").compactMap(Self.parseLiveViewSize))
        let minimumFPS = max(1, liveViewBody.int("minFps") ?? 1)
        let maximumFPS = max(minimumFPS, liveViewBody.int("maxFps") ?? minimumFPS)
        let requestedDefaultSource = liveViewBody.string("defaultSource").flatMap(Self.parseLiveViewSource)
        let requestedDefaultSize = liveViewBody.string("defaultSize").flatMap(Self.parseLiveViewSize)

        let profileBody = body.dictionary("profile")
        let modelName = profileBody.nonEmptyString("modelName") ?? "Canon Camera"
        let inferredProfile = CameraProfile.from(modelName: modelName)
        let profile = CameraProfile(
            modelName: modelName,
            family: Self.parseFamily(profileBody.string("family")) ?? inferredProfile.family,
            priority: Self.parsePriority(profileBody.string("priority")) ?? inferredProfile.priority
        )

        let evidenceBody = body.dictionary("evidence")
        let versions = Self.boundedEvidence(evidenceBody.stringArray("protocolVersions"))
        let commands = Self.boundedEvidence(evidenceBody.stringArray("advertisedCommands"), removeQuery: true)
        let writableSettings = Self.boundedEvidence(evidenceBody.stringArray("writableSettings"))
        let source = Self.cleanEvidenceItem(evidenceBody.string("source") ?? "unknown", removeQuery: false).value
        let evidence = CameraCapabilityEvidence(
            source: source.isEmpty ? "unknown" : source,
            protocolVersions: versions.values,
            advertisedCommands: commands.values,
            writableSettings: writableSettings.values,
            truncated: (evidenceBody.bool("truncated") ?? false)
                || versions.truncated
                || commands.truncated
                || writableSettings.truncated
        )

        return CameraCapabilities(
            settings: settings,
            matrix: CapabilityMatrix(supported: supported, planned: planned, reasons: reasons),
            liveView: LiveViewCapabilities(
                sources: sources,
                defaultSource: requestedDefaultSource.flatMap { sources.contains($0) ? $0 : nil }
                    ?? sources.first
                    ?? .auto,
                sizes: sizes,
                defaultSize: requestedDefaultSize.flatMap { sizes.contains($0) ? $0 : nil }
                    ?? sizes.first
                    ?? .medium,
                minimumFPS: minimumFPS,
                maximumFPS: maximumFPS
            ),
            profile: profile,
            evidence: evidence
        )
    }

    public func setSetting(key: String, value: String) async throws -> CameraStatus {
        let body = try await postJSON(sessionEndpoint(["settings", key]), payload: ["value": value])
        return parseStatus(body)
    }

    public func captureStill() async throws -> CameraStatus {
        let body = try await postJSON(sessionEndpoint(["capture", "still"]), payload: [:])
        return parseStatus(body)
    }

    public func halfPressShutter() async throws -> CameraStatus {
        let body = try await postJSON(sessionEndpoint(["shutter", "half-press"]), payload: [:])
        return parseStatus(body)
    }

    public func startRecording() async throws -> CameraStatus {
        let body = try await postJSON(sessionEndpoint(["recording", "start"]), payload: [:])
        return parseStatus(body)
    }

    public func stopRecording() async throws -> CameraStatus {
        let body = try await postJSON(sessionEndpoint(["recording", "stop"]), payload: [:])
        return parseStatus(body)
    }

    public func tapFocus(x: Double, y: Double) async throws -> FocusResult {
        let body = try await postJSON(sessionEndpoint(["focus", "tap"]), payload: ["x": x, "y": y])
        return FocusResult(
            accepted: body.bool("accepted") ?? false,
            x: body.double("x") ?? x,
            y: body.double("y") ?? y
        )
    }

    public func driveFocus(
        direction: FocusDriveDirection,
        step: FocusDriveStep
    ) async throws -> FocusDriveResult {
        let body = try await postJSON(
            sessionEndpoint(["focus", "drive"]),
            payload: ["direction": direction.rawValue.uppercased(), "step": step.rawValue.uppercased()]
        )
        return FocusDriveResult(
            accepted: body.bool("accepted") ?? false,
            direction: body.string("direction").flatMap(Self.parseFocusDirection) ?? direction,
            step: body.string("step").flatMap(Self.parseFocusStep) ?? step
        )
    }

    public func startLiveView(_ request: LiveViewRequest = LiveViewRequest()) async throws {
        _ = try await postJSON(
            sessionEndpoint(["liveview", "start"]),
            payload: [
                "fps": request.fps,
                "size": request.size.rawValue.uppercased(),
                "source": Self.bridgeValue(request.source),
            ]
        )
    }

    public func stopLiveView() async {
        guard sessionID != nil else { return }
        try? await postJSON(sessionEndpoint(["liveview", "stop"]), payload: [:])
    }

    public func liveViewFrame(cacheKey: Int64) async throws -> LiveViewFrame {
        let url = try sessionEndpoint(["liveview", "frame"], queryItems: [URLQueryItem(name: "t", value: String(cacheKey))])
        var request = makeRequest(url: url, method: "GET", accept: "image/jpeg")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let response = try await transport.send(request)
        guard (200..<300).contains(response.statusCode) else {
            throw Self.httpError(response: response, method: "GET", url: url)
        }
        guard response.body.count <= Self.maximumLiveViewFrameBytes else {
            throw DesktopBridgeError.invalidResponse(
                "Desktop Bridge Live View frame exceeded \(Self.maximumLiveViewFrameBytes) bytes."
            )
        }
        guard Self.isCompleteJPEG(response.body) else {
            throw DesktopBridgeError.invalidResponse("Desktop Bridge did not return a complete JPEG frame.")
        }
        return LiveViewFrame(data: response.body, contentType: response.header("content-type"), sourceURL: url)
    }

    public func listMedia() async throws -> [CameraMediaItem] {
        let body = try await getJSON(sessionEndpoint(["media"]))
        return body.array("items").compactMap { value in
            guard
                let item = value as? BridgeJSON,
                let id = item.nonEmptyString("id"),
                let name = item.nonEmptyString("name")
            else { return nil }
            return CameraMediaItem(
                id: id,
                name: name,
                kind: item.string("kind") ?? "other",
                sizeBytes: item.int64("sizeBytes"),
                captureTime: item.nonEmptyString("captureTime")
            )
        }
    }

    public func downloadMedia(_ item: CameraMediaItem, to destination: URL) async throws -> CameraMediaDownload {
        guard !FileManager.default.fileExists(atPath: destination.path) else {
            throw DesktopBridgeError.destinationExists(destination.path)
        }
        let url = try sessionEndpoint(["media", item.id])
        let response = try await transport.download(makeRequest(url: url, method: "GET", accept: "application/octet-stream"))
        guard (200..<300).contains(response.statusCode) else {
            let body = Self.readPrefix(response.temporaryFileURL, limit: Self.maximumErrorBodyBytes)
            try? FileManager.default.removeItem(at: response.temporaryFileURL)
            throw Self.httpError(
                statusCode: response.statusCode,
                body: body,
                method: "GET",
                url: url
            )
        }
        do {
            try FileManager.default.moveItem(at: response.temporaryFileURL, to: destination)
        } catch {
            try? FileManager.default.removeItem(at: response.temporaryFileURL)
            throw error
        }
        let size = try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize
        return CameraMediaDownload(
            item: item,
            fileURL: destination,
            bytesTransferred: Int64(size ?? 0),
            contentType: response.header("content-type")
        )
    }

    public func deleteMedia(_ item: CameraMediaItem) async throws {
        try await requestOK(sessionEndpoint(["media", item.id]), method: "DELETE")
    }

    public func diagnosticReport(
        snapshot: CameraSnapshot?,
        liveView: CCAPILiveViewMetrics = CCAPILiveViewMetrics(),
        lastError: String? = nil
    ) -> String {
        DesktopBridgeDiagnosticReport.make(
            baseURL: baseURL,
            bridgeVersion: bridgeVersion,
            engine: sessionEngine,
            snapshot: snapshot,
            liveView: liveView,
            lastError: lastError
        )
    }

    private func validateService() async throws {
        let body = try await getJSON(endpoint(["health"]))
        guard body.string("service") == Self.serviceName, body.bool("ok") != false else {
            throw DesktopBridgeError.invalidResponse("The URL is not an Open EOS Control Desktop Bridge.")
        }
        bridgeVersion = body.nonEmptyString("version")
    }

    private func parseStatus(_ body: BridgeJSON) -> CameraStatus {
        let battery = body.dictionary("battery")
        let media = body.dictionary("media")
        let exposure = body.dictionary("exposure")
        return CameraStatus(
            connected: body.bool("connected") ?? true,
            batteryLevel: battery.int("level"),
            batteryStatus: battery.string("status") ?? "unknown",
            recording: body.bool("recording"),
            mode: body.string("mode") ?? "unknown",
            mediaAvailable: media.bool("available"),
            remainingMinutes: nil,
            exposure: ExposureState(
                iso: exposure.string("iso") ?? "-",
                shutter: exposure.string("shutter") ?? "-",
                aperture: exposure.string("aperture") ?? "-",
                whiteBalance: exposure.string("whiteBalance") ?? "-"
            ),
            rawBatteryJSON: Self.jsonString(battery),
            rawStorageJSON: Self.jsonString(media)
        )
    }

    private func getJSON(_ url: URL) async throws -> BridgeJSON {
        try await requestJSON(url: url, method: "GET", payload: nil)
    }

    private func postJSON(_ url: URL, payload: BridgeJSON) async throws -> BridgeJSON {
        try await requestJSON(url: url, method: "POST", payload: payload)
    }

    private func requestJSON(url: URL, method: String, payload: BridgeJSON?) async throws -> BridgeJSON {
        var request = makeRequest(url: url, method: method, accept: "application/json")
        if let payload {
            request.httpBody = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        let response = try await transport.send(request)
        guard (200..<300).contains(response.statusCode) else {
            throw Self.httpError(response: response, method: method, url: url)
        }
        guard
            let value = try? JSONSerialization.jsonObject(with: response.body),
            let object = value as? BridgeJSON
        else {
            throw DesktopBridgeError.invalidResponse(
                "Desktop Bridge returned invalid JSON for \(url.path)."
            )
        }
        return object
    }

    private func requestOK(_ url: URL, method: String) async throws {
        let response = try await transport.send(makeRequest(url: url, method: method, accept: "application/json"))
        guard (200..<300).contains(response.statusCode) else {
            throw Self.httpError(response: response, method: method, url: url)
        }
    }

    private func makeRequest(url: URL, method: String, accept: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue(accept, forHTTPHeaderField: "Accept")
        if !bearerToken.isEmpty {
            request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private func endpoint(_ segments: [String], queryItems: [URLQueryItem] = []) throws -> URL {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw DesktopBridgeError.invalidBaseURL(baseURL.absoluteString)
        }
        components.percentEncodedPath = "/" + try segments.map(Self.encodePathSegment).joined(separator: "/")
        components.queryItems = queryItems.isEmpty ? nil : queryItems
        guard let url = components.url else {
            throw DesktopBridgeError.invalidResponse("Desktop Bridge endpoint could not be constructed.")
        }
        return url
    }

    private func sessionEndpoint(_ segments: [String], queryItems: [URLQueryItem] = []) throws -> URL {
        guard let sessionID else { throw DesktopBridgeError.notInitialized }
        return try endpoint(["v1", "session", sessionID] + segments, queryItems: queryItems)
    }

    private static func encodePathSegment(_ value: String) throws -> String {
        guard let encoded = value.addingPercentEncoding(withAllowedCharacters: pathSegmentAllowed) else {
            throw DesktopBridgeError.invalidResponse("Desktop Bridge path contains an invalid value.")
        }
        return encoded
    }

    private static func parseSetting(_ value: Any) -> CameraSetting? {
        guard
            let body = value as? BridgeJSON,
            let key = body.nonEmptyString("key")
        else { return nil }
        let values = body.stringArray("values").filter { !$0.isEmpty }
        guard !values.isEmpty else { return nil }
        return CameraSetting(
            key: key,
            label: body.nonEmptyString("label") ?? key,
            value: body.string("value") ?? "",
            values: values
        )
    }

    private static func parseLiveViewSource(_ value: String) -> LiveViewSource? {
        switch normalizedEnumValue(value) {
        case "AUTO": .auto
        case "CCAPIJPEGPOLLING", "CCAPI_JPEG_POLLING": .ccapiJPEGPolling
        case "CCAPIRTP", "CCAPI_RTP": .ccapiRTP
        case "DESKTOPBRIDGESTREAM", "DESKTOP_BRIDGE_STREAM": .desktopBridgeStream
        case "SIMULATORFRAME", "SIMULATOR_FRAME": .simulatorFrame
        default: nil
        }
    }

    private static func parseLiveViewSize(_ value: String) -> LiveViewSize? {
        LiveViewSize(rawValue: value.lowercased())
    }

    private static func parseFamily(_ value: String?) -> CameraModelFamily? {
        guard let value else { return nil }
        switch normalizedEnumValue(value) {
        case "EOSR", "EOS_R": .eosR
        case "EOSDSLR", "EOS_DSLR": .eosDSLR
        case "EOSM", "EOS_M": .eosM
        case "POWERSHOT": .powerShot
        case "UNKNOWN": .unknown
        default: nil
        }
    }

    private static func parsePriority(_ value: String?) -> CameraModelPriority? {
        guard let value else { return nil }
        switch normalizedEnumValue(value) {
        case "PRIMARY": .primary
        case "SUPPORTED": .supported
        case "RESEARCH": .research
        default: nil
        }
    }

    private static func parseFocusDirection(_ value: String) -> FocusDriveDirection? {
        FocusDriveDirection(rawValue: value.lowercased())
    }

    private static func parseFocusStep(_ value: String) -> FocusDriveStep? {
        FocusDriveStep(rawValue: value.lowercased())
    }

    private static func bridgeValue(_ source: LiveViewSource) -> String {
        switch source {
        case .auto: "AUTO"
        case .ccapiJPEGPolling: "CCAPI_JPEG_POLLING"
        case .ccapiRTP: "CCAPI_RTP"
        case .desktopBridgeStream: "DESKTOP_BRIDGE_STREAM"
        case .simulatorFrame: "SIMULATOR_FRAME"
        }
    }

    private static func normalizedEnumValue(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().replacingOccurrences(of: "-", with: "_")
    }

    private static func boundedEvidence(
        _ input: [String],
        removeQuery: Bool = false
    ) -> (values: [String], truncated: Bool) {
        var values: [String] = []
        var seen = Set<String>()
        var truncated = false
        for rawValue in input {
            let cleaned = cleanEvidenceItem(rawValue, removeQuery: removeQuery)
            truncated = truncated || cleaned.truncated
            guard !cleaned.value.isEmpty, seen.insert(cleaned.value).inserted else { continue }
            guard values.count < maximumEvidenceItems else {
                truncated = true
                continue
            }
            values.append(cleaned.value)
        }
        return (values, truncated)
    }

    private static func cleanEvidenceItem(_ input: String, removeQuery: Bool) -> (value: String, truncated: Bool) {
        var value = input.replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: "")
        if removeQuery { value = value.components(separatedBy: "?")[0] }
        let truncated = value.count > maximumEvidenceItemCharacters
        if truncated { value = String(value.prefix(maximumEvidenceItemCharacters)) }
        return (value, truncated)
    }

    private static func unique<T: Hashable>(_ values: [T]) -> [T] {
        var seen = Set<T>()
        return values.filter { seen.insert($0).inserted }
    }

    private static func isCompleteJPEG(_ data: Data) -> Bool {
        guard data.count >= 4 else { return false }
        let start = data.startIndex
        let last = data.index(before: data.endIndex)
        let penultimate = data.index(before: last)
        return data[start] == 0xFF
            && data[data.index(after: start)] == 0xD8
            && data[penultimate] == 0xFF
            && data[last] == 0xD9
    }

    private static func jsonString(_ value: BridgeJSON) -> String {
        guard
            JSONSerialization.isValidJSONObject(value),
            let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
            let text = String(data: data, encoding: .utf8)
        else { return "{}" }
        return text
    }

    private static func readPrefix(_ url: URL, limit: Int) -> Data {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return Data() }
        defer { try? handle.close() }
        return (try? handle.read(upToCount: limit)) ?? Data()
    }

    private static func httpError(response: CameraHTTPResponse, method: String, url: URL) -> DesktopBridgeError {
        httpError(
            statusCode: response.statusCode,
            body: response.body,
            method: method,
            url: url
        )
    }

    private static func httpError(
        statusCode: Int,
        body: Data,
        method: String,
        url: URL
    ) -> DesktopBridgeError {
        let boundedBody = Data(body.prefix(maximumErrorBodyBytes))
        let object = (try? JSONSerialization.jsonObject(with: boundedBody)) as? BridgeJSON
        let detail = object?.dictionary("error") ?? [:]
        let fallback = String(data: boundedBody, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return .http(
            statusCode: statusCode,
            method: method,
            url: url.absoluteString,
            code: detail.nonEmptyString("code") ?? "HTTP_\(statusCode)",
            message: detail.nonEmptyString("message") ?? fallback?.nilIfEmpty ?? "Desktop Bridge returned HTTP \(statusCode).",
            feature: detail.nonEmptyString("feature"),
            engine: detail.nonEmptyString("engine")
        )
    }
}

public enum DesktopBridgeDiagnosticReport {
    public static func make(
        baseURL: URL,
        bridgeVersion: String? = nil,
        engine: String? = nil,
        snapshot: CameraSnapshot?,
        liveView: CCAPILiveViewMetrics = CCAPILiveViewMetrics(),
        lastError: String? = nil
    ) -> String {
        let supported = snapshot?.capabilities.matrix.supported.map(\.rawValue).sorted().joined(separator: ", ") ?? "none"
        let planned = snapshot?.capabilities.matrix.planned.map(\.rawValue).sorted().joined(separator: ", ") ?? "none"
        let evidence = snapshot?.capabilities.evidence
        let date = ISO8601DateFormatter().string(from: liveView.lastFrameAt ?? Date(timeIntervalSince1970: 0))
        let report = [
            "Open EOS Control iOS diagnostic report",
            "camera=\(snapshot?.info.model ?? "unknown")",
            "serial=\(snapshot?.info.serial ?? "unknown")",
            "transport=DESKTOP_BRIDGE",
            "bridgeUrl=\(sanitized(baseURL)?.absoluteString ?? "invalid")",
            "bridgeVersion=\(bridgeVersion ?? "unknown")",
            "engine=\(engine ?? "unknown")",
            "supported=\(supported)",
            "planned=\(planned)",
            "capabilitySource=\(evidence?.source ?? "unknown")",
            "protocolVersions=\(evidence?.protocolVersions.joined(separator: ", ") ?? "none")",
            "advertisedCommandCount=\(evidence?.advertisedCommands.count ?? 0)",
            "advertisedCommands=\(evidence?.advertisedCommands.joined(separator: " | ") ?? "none")",
            "writableSettings=\(evidence?.writableSettings.joined(separator: ", ") ?? "none")",
            "capabilityEvidenceTruncated=\(evidence?.truncated ?? false)",
            "battery=\(snapshot?.status.rawBatteryJSON ?? "null")",
            "storage=\(snapshot?.status.rawStorageJSON ?? "null")",
            "requestedFps=\(liveView.requestedFPS)",
            "observedFps=\(String(format: "%.1f", liveView.observedFPS))",
            "frameBytes=\(liveView.frameBytes)",
            "contentType=\(liveView.contentType ?? "none")",
            "source=\(sanitized(liveView.sourceURL)?.absoluteString ?? "none")",
            "lastFrameAt=\(liveView.lastFrameAt == nil ? "none" : date)",
            "lastError=\(lastError ?? "none")",
        ].joined(separator: "\n")
        return redact(report)
    }

    private static func sanitized(_ value: URL?) -> URL? {
        guard let value, var components = URLComponents(url: value, resolvingAgainstBaseURL: false) else { return value }
        components.user = nil
        components.password = nil
        return components.url
    }

    private static func redact(_ value: String) -> String {
        value
            .replacingOccurrences(
                of: #"(?i)(authorization\s*:\s*(?:bearer|basic)\s+)[^\s\r\n]+"#,
                with: "$1[redacted]",
                options: .regularExpression
            )
            .replacingOccurrences(
                of: #"(?i)((?:password|token)=)[^\s&]+"#,
                with: "$1[redacted]",
                options: .regularExpression
            )
            .replacingOccurrences(
                of: #"(?i)(\"(?:password|token)\"\s*:\s*\")[^\"]+"#,
                with: "$1[redacted]",
                options: .regularExpression
            )
    }
}

private typealias BridgeJSON = [String: Any]

private extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) -> String? {
        self[key] as? String
    }

    func nonEmptyString(_ key: String) -> String? {
        string(key)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }

    func bool(_ key: String) -> Bool? {
        if let value = self[key] as? Bool { return value }
        return (self[key] as? NSNumber)?.boolValue
    }

    func int(_ key: String) -> Int? {
        if let value = self[key] as? Int { return value }
        return (self[key] as? NSNumber)?.intValue
    }

    func int64(_ key: String) -> Int64? {
        if let value = self[key] as? Int64 { return value }
        return (self[key] as? NSNumber)?.int64Value
    }

    func double(_ key: String) -> Double? {
        if let value = self[key] as? Double { return value }
        return (self[key] as? NSNumber)?.doubleValue
    }

    func dictionary(_ key: String) -> BridgeJSON {
        self[key] as? BridgeJSON ?? [:]
    }

    func array(_ key: String) -> [Any] {
        self[key] as? [Any] ?? []
    }

    func stringArray(_ key: String) -> [String] {
        array(key).compactMap { $0 as? String }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
