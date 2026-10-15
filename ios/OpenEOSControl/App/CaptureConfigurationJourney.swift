import Foundation
import OpenEOSCore

struct CaptureConfigurationRecord: Identifiable {
    let id = UUID()
    let owner: UUID
    let configurationOwner: UUID
    let draftRevision: UUID
    let targetSelection: String?
    var outcome: CameraCaptureConfigurationOutcome
    var running = true
    var reading = false
    var cancelled = false
    var retired = false

    var readbackKey: String {
        switch outcome.readbackEvidence {
        case .notRead: "capture_config_readback_not_read"
        case .unavailable: "capture_config_readback_unavailable"
        case .matched: "capture_config_readback_matched"
        case .mismatch: "capture_config_readback_mismatch"
        case .refreshed: "capture_config_readback_refreshed"
        }
    }

    var statusKey: String {
        switch outcome.writeEvidence {
        case .notSent: return "capture_config_not_sent"
        case .unknown: return "capture_config_unknown"
        case .acknowledged:
            switch outcome.readbackEvidence {
            case .matched: return "capture_config_ack_matched"
            case .refreshed: return "capture_config_ack_refreshed"
            case .mismatch: return "capture_config_ack_mismatch"
            case .notRead, .unavailable: return "capture_config_ack_unavailable"
            }
        }
    }
}
