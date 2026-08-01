import CryptoKit
import Foundation
import OpenEOSCore

enum PhysicalValidationSessionStatus: Equatable {
    case ready
    case disconnected
    case offlinePreview
    case simulator
}

struct PhysicalValidationSummary: Equatable {
    let sessionStatus: PhysicalValidationSessionStatus
    let advertisedFeatures: Set<CameraFeature>
    let observedFeatures: Set<CameraFeature>
    let eligibleFeatures: Set<CameraFeature>
    let operatorConfirmedFeatures: Set<CameraFeature>

    init(
        connected: Bool,
        isPreview: Bool,
        info: CameraInfo?,
        capabilities: CameraCapabilities?,
        operatorConfirmedFeatures: Set<CameraFeature>
    ) {
        let validation = DiagnosticValidationSummary(capabilities: capabilities)
        let simulatorValues = [info?.api, info?.model, capabilities?.evidence.source]
            .compactMap { $0?.lowercased() }
        sessionStatus = if !connected {
            .disconnected
        } else if isPreview {
            .offlinePreview
        } else if simulatorValues.contains(where: { $0.contains("simulat") }) {
            .simulator
        } else {
            .ready
        }
        advertisedFeatures = validation.advertisedFeatures
        observedFeatures = validation.observedFeatures
        eligibleFeatures = sessionStatus == .ready ? validation.validatedAdvertisedFeatures : []
        self.operatorConfirmedFeatures = operatorConfirmedFeatures.intersection(eligibleFeatures)
    }
}

enum PhysicalValidationRecord {
    static func make(
        summary: PhysicalValidationSummary,
        info: CameraInfo?,
        transport: String,
        diagnosticReport: String
    ) throws -> String {
        guard summary.sessionStatus == .ready else {
            throw ValidationError.physicalCameraRequired
        }
        let normalizedDiagnostic = diagnosticReport.replacingOccurrences(of: "\r\n", with: "\n")
        let digest = SHA256.hash(data: Data(normalizedDiagnostic.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        let features = summary.advertisedFeatures.union(summary.observedFeatures)
            .sorted { $0.rawValue < $1.rawValue }
        let generatedAt = diagnosticField("generatedAt", in: diagnosticReport) ?? "unknown"
        let productVersion = diagnosticField("productVersion", in: diagnosticReport) ?? "unknown"
        var lines = [
            "# Open EOS Control physical camera validation",
            "",
            "- Record schema: 1",
            "- Generated at: \(markdownCell(generatedAt))",
            "- App version: \(markdownCell(productVersion))",
            "- Camera model: \(markdownCell(info?.model ?? "unknown"))",
            "- Transport: \(markdownCell(transport))",
            "- Diagnostic SHA-256: `\(digest)`",
            "",
            "Operator confirmation is a manual in-app attestation that the physical camera visibly performed the operation.",
            "",
            "| Feature | Advertised | Observed this session | Operator confirmed |",
            "| --- | --- | --- | --- |",
        ]
        lines.append(contentsOf: features.map { feature in
            "| \(feature.rawValue) | \(summary.advertisedFeatures.contains(feature)) | "
                + "\(summary.observedFeatures.contains(feature)) | "
                + "\(summary.operatorConfirmedFeatures.contains(feature)) |"
        })
        return lines.joined(separator: "\n")
    }

    enum ValidationError: Error {
        case physicalCameraRequired
    }

    private static func diagnosticField(_ key: String, in report: String) -> String? {
        let prefix = "\(key)="
        return report.split(whereSeparator: \.isNewline)
            .first { $0.hasPrefix(prefix) }
            .map { String($0.dropFirst(prefix.count)) }
    }

    private static func markdownCell(_ value: String) -> String {
        String(
            value
                .replacingOccurrences(of: "\r", with: " ")
                .replacingOccurrences(of: "\n", with: " ")
                .replacingOccurrences(of: "|", with: "\\|")
                .prefix(160)
        )
    }
}
