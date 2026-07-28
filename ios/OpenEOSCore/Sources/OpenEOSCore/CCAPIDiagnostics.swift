import Foundation

let diagnosticReportSchema = 1

public struct DiagnosticReportMetadata: Equatable, Sendable {
    public let productVersion: String
    public let generatedAt: String

    public init(productVersion: String, generatedAt: String) {
        self.productVersion = productVersion
        self.generatedAt = generatedAt
    }

    public static func current() -> DiagnosticReportMetadata {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        return DiagnosticReportMetadata(
            productVersion: version?.nilIfDiagnosticBlank ?? "unknown",
            generatedAt: ISO8601DateFormatter().string(from: Date())
        )
    }
}

public struct DiagnosticValidationSummary: Equatable, Sendable {
    public let advertisedFeatures: Set<CameraFeature>
    public let observedFeatures: Set<CameraFeature>

    public var validatedAdvertisedFeatures: Set<CameraFeature> {
        advertisedFeatures.intersection(observedFeatures)
    }

    public var unverifiedAdvertisedFeatures: Set<CameraFeature> {
        advertisedFeatures.subtracting(observedFeatures)
    }

    public var observedWithoutAdvertisement: Set<CameraFeature> {
        observedFeatures.subtracting(advertisedFeatures)
    }

    public init(capabilities: CameraCapabilities?) {
        advertisedFeatures = capabilities?.matrix.supported ?? []
        observedFeatures = capabilities?.evidence.observedFeatures ?? []
    }
}

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
        lastError: String? = nil,
        metadata: DiagnosticReportMetadata = .current()
    ) -> String {
        let supported = snapshot?.capabilities.matrix.supported.map(\.rawValue).sorted().joined(separator: ", ") ?? "none"
        let planned = snapshot?.capabilities.matrix.planned.map(\.rawValue).sorted().joined(separator: ", ") ?? "none"
        let evidence = snapshot?.capabilities.evidence
        let observed = evidence?.observedFeatures.map(\.rawValue).sorted().joined(separator: ", ")
        let observedText = observed.flatMap { $0.isEmpty ? nil : $0 } ?? "none"
        let date = ISO8601DateFormatter().string(from: liveView.lastFrameAt ?? Date(timeIntervalSince1970: 0))
        let source = sanitized(liveView.sourceURL)?.absoluteString ?? "none"
        let validation = DiagnosticValidationSummary(capabilities: snapshot?.capabilities)
        let report = [
            "Open EOS Control iOS diagnostic report",
            "reportSchema=\(diagnosticReportSchema)",
            "generatedAt=\(metadata.generatedAt)",
            "productVersion=\(metadata.productVersion)",
            "camera=\(snapshot?.info.model ?? "unknown")",
            "serial=\(diagnosticSerial(snapshot?.info.serial))",
            "transport=CCAPI_NETWORK",
            "baseUrl=\(sanitized(baseURL)?.absoluteString ?? "invalid")",
            "mode=\(mode.rawValue)",
            "apiVersions=\(versions.joined(separator: ", "))",
            "supported=\(supported)",
            "planned=\(planned)",
            "capabilitySource=\(evidence?.source ?? "unknown")",
            "protocolVersions=\(evidence?.protocolVersions.joined(separator: ", ") ?? "none")",
            "advertisedCommandCount=\(evidence?.advertisedCommands.count ?? 0)",
            "advertisedCommands=\(evidence?.advertisedCommands.joined(separator: " | ") ?? "none")",
            "writableSettings=\(evidence?.writableSettings.joined(separator: ", ") ?? "none")",
            "observedFeatures=\(observedText)",
            "advertisedFeatureCount=\(validation.advertisedFeatures.count)",
            "observedFeatureCount=\(validation.observedFeatures.count)",
            "validatedAdvertisedFeatureCount=\(validation.validatedAdvertisedFeatures.count)",
            "unverifiedAdvertisedFeatures=\(diagnosticFeatureList(validation.unverifiedAdvertisedFeatures))",
            "observedWithoutAdvertisement=\(diagnosticFeatureList(validation.observedWithoutAdvertisement))",
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
            "lastError=\(lastError ?? "none")",
        ].joined(separator: "\n")
        return redactDiagnosticText(report, sensitiveValues: [snapshot?.info.serial])
    }

    private static func sanitized(_ value: URL?) -> URL? {
        guard let value, var components = URLComponents(url: value, resolvingAgainstBaseURL: false) else { return value }
        components.user = nil
        components.password = nil
        return components.url
    }

}

func diagnosticSerial(_ value: String?) -> String {
    value.isDiagnosticUnknown ? "unknown" : "[redacted]"
}

func diagnosticFeatureList(_ features: Set<CameraFeature>) -> String {
    let value = features.map(\.rawValue).sorted().joined(separator: ", ")
    return value.isEmpty ? "none" : value
}

func redactDiagnosticText(_ value: String, sensitiveValues: [String?] = []) -> String {
    var redacted = value
        .replacingOccurrences(
            of: #"(?i)(authorization\s*:\s*(?:bearer|basic)?\s*)[^\s\r\n]+"#,
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
    for sensitiveValue in sensitiveValues.compactMap({ $0 }).filter({ !$0.isDiagnosticUnknown }) {
        redacted = redacted.replacingOccurrences(of: sensitiveValue, with: "[redacted]")
    }
    return redacted
}

private extension Optional where Wrapped == String {
    var isDiagnosticUnknown: Bool {
        guard let value = self else { return true }
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return normalized.isEmpty || normalized == "unknown" || normalized == "none"
    }
}

private extension String {
    var isDiagnosticUnknown: Bool { Optional(self).isDiagnosticUnknown }
    var nilIfDiagnosticBlank: String? { isEmpty ? nil : self }
}
