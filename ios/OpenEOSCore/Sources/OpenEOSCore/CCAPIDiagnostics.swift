import Foundation

public struct CCAPILiveViewMetrics: Equatable, Sendable {
    public let requestedFPS: Int
    public let observedFPS: Double
    public let frameBytes: Int
    public let contentType: String?
    public let source: LiveViewSource?
    public let sourceURL: URL?
    public let lastFrameAt: Date?

    public init(
        requestedFPS: Int = 0,
        observedFPS: Double = 0,
        frameBytes: Int = 0,
        contentType: String? = nil,
        source: LiveViewSource? = nil,
        sourceURL: URL? = nil,
        lastFrameAt: Date? = nil
    ) {
        self.requestedFPS = requestedFPS
        self.observedFPS = observedFPS
        self.frameBytes = frameBytes
        self.contentType = contentType
        self.source = source
        self.sourceURL = sourceURL
        self.lastFrameAt = lastFrameAt
    }
}

public enum CCAPIDiagnosticReport {
    public static func make(
        baseURL: URL,
        mode: CCAPIConnectionMode,
        versions: [String],
        snapshot: CameraSnapshot?,
        liveView: CCAPILiveViewMetrics = CCAPILiveViewMetrics(),
        lastError: String? = nil
    ) -> String {
        let supported = snapshot?.capabilities.matrix.supported.map(\.rawValue).sorted().joined(separator: ", ") ?? "none"
        let planned = snapshot?.capabilities.matrix.planned.map(\.rawValue).sorted().joined(separator: ", ") ?? "none"
        let evidence = snapshot?.capabilities.evidence
        let observed = evidence?.observedFeatures.map(\.rawValue).sorted().joined(separator: ", ")
        let observedText = observed.flatMap { $0.isEmpty ? nil : $0 } ?? "none"
        let date = ISO8601DateFormatter().string(from: liveView.lastFrameAt ?? Date(timeIntervalSince1970: 0))
        let source = sanitized(liveView.sourceURL)?.absoluteString ?? "none"
        return [
            "Open EOS Control iOS diagnostic report",
            "camera=\(snapshot?.info.model ?? "unknown")",
            "serial=\(snapshot?.info.serial ?? "unknown")",
            "transport=CCAPI_NETWORK",
            "baseUrl=\(sanitized(baseURL)?.absoluteString ?? "invalid")",
            "mode=\(mode.rawValue)",
            "apiVersions=\(versions.joined(separator: ", "))",
            "supported=\(supported)",
            "planned=\(planned)",
            "capabilitySource=\(redact(evidence?.source ?? "unknown"))",
            "protocolVersions=\(evidence?.protocolVersions.joined(separator: ", ") ?? "none")",
            "advertisedCommandCount=\(evidence?.advertisedCommands.count ?? 0)",
            "advertisedCommands=\(redact(evidence?.advertisedCommands.joined(separator: " | ") ?? "none"))",
            "writableSettings=\(evidence?.writableSettings.joined(separator: ", ") ?? "none")",
            "observedFeatures=\(observedText)",
            "capabilityEvidenceTruncated=\(evidence?.truncated ?? false)",
            "battery=\(snapshot?.status.rawBatteryJSON ?? "null")",
            "storage=\(snapshot?.status.rawStorageJSON ?? "null")",
            "storageAvailable=\(snapshot?.status.mediaAvailable.map { String($0) } ?? "unknown")",
            "storageTotalBytes=\(snapshot?.status.storageTotalBytes.map { String($0) } ?? "unknown")",
            "storageFreeBytes=\(snapshot?.status.storageFreeBytes.map { String($0) } ?? "unknown")",
            "storageFreeImages=\(snapshot?.status.storageFreeImages.map { String($0) } ?? "unknown")",
            "storageDevices=\(snapshot?.status.storageDeviceCount.map { String($0) } ?? "unknown")",
            "requestedFps=\(liveView.requestedFPS)",
            "observedFps=\(String(format: "%.1f", liveView.observedFPS))",
            "frameBytes=\(liveView.frameBytes)",
            "contentType=\(liveView.contentType ?? "none")",
            "liveViewSource=\(liveView.source?.rawValue ?? "none")",
            "source=\(source)",
            "lastFrameAt=\(liveView.lastFrameAt == nil ? "none" : date)",
            "lastError=\(redact(lastError ?? "none"))",
        ].joined(separator: "\n")
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
                of: #"(?i)authorization\s*:\s*[^\r\n]+"#,
                with: "Authorization: [redacted]",
                options: .regularExpression
            )
            .replacingOccurrences(
                of: #"(?i)(password|token)=([^\s&]+)"#,
                with: "$1=[redacted]",
                options: .regularExpression
            )
    }
}
