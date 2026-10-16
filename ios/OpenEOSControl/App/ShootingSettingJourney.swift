import Foundation
import OpenEOSCore

struct ShootingSettingRecord: Identifiable {
    let id = UUID()
    let owner: UUID
    let configurationOwner: UUID
    let draftRevision: UUID
    var outcome: CameraShootingSettingOutcome
    var running = true
    var reading = false
    var cancelled = false
    var retired = false
    var superseded = false
    var statusKey: String {
        switch outcome.writeEvidence {
        case .notSent: return "shooting_setting_not_sent"
        case .unknown: return "shooting_setting_unknown"
        case .responded: return "shooting_setting_responded"
        }
    }
    var readbackKey: String {
        switch outcome.readbackEvidence {
        case .notRead: return "shooting_setting_not_read"
        case .unavailable: return "shooting_setting_unavailable"
        case .matched: return "shooting_setting_matched"
        case .mismatch: return "shooting_setting_mismatch"
        }
    }
}
