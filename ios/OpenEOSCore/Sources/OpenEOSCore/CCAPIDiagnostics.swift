import Foundation

public struct CCAPILiveViewMetrics: Equatable, Sendable {
    public let requestedFPS: Int
    public let observedFPS: Double
    public let frameBytes: Int
    public let contentType: String?
    public let sourceURL: URL?
    public let lastFrameAt: Date?

    public init(
        requestedFPS: Int = 0,
        observedFPS: Double = 0,
        frameBytes: Int = 0,
        contentType: String? = nil,
        sourceURL: URL? = nil,
        lastFrameAt: Date? = nil
    ) {
        self.requestedFPS = requestedFPS
        self.observedFPS = observedFPS
        self.frameBytes = frameBytes
        self.contentType = contentType
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
            "capabilityEvidenceTruncated=\(evidence?.truncated ?? false)",
            "battery=\(snapshot?.status.rawBatteryJSON ?? "null")",
            "storage=\(snapshot?.status.rawStorageJSON ?? "null")",
            "requestedFps=\(liveView.requestedFPS)",
            "observedFps=\(String(format: "%.1f", liveView.observedFPS))",
            "frameBytes=\(liveView.frameBytes)",
            "contentType=\(liveView.contentType ?? "none")",
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
