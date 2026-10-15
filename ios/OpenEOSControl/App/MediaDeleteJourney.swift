import Foundation
import OpenEOSCore

enum MediaDeleteSource: String {
    case cameraCard = "camera", desktopHost = "desktop-host", unknown
    static func resolve(_ item: CameraMediaItem, usesBridge: Bool, bridgeEngine: String?) -> Self {
        guard !item.id.isEmpty else { return .unknown }
        if item.id.hasPrefix("gphoto2-host:") { return usesBridge && bridgeEngine == "libgphoto2" ? .desktopHost : .unknown }
        if !usesBridge { return .cameraCard }
        if bridgeEngine == "ccapi" { return .cameraCard } // This backend has no host store route.
        if bridgeEngine == "libgphoto2", item.id.hasPrefix("gphoto2:") { return .cameraCard }
        return .unknown // No guessed EDSDK/provider location or host-to-card fallback.
    }
}
enum MediaDeleteReason: String {
    case unloaded, unsupported, protected, notWritable, invalid, unknownSource, sourceChanged
    case cancelled, ownerChanged, blocked, uncertain, notSent, rejected, capacity, limit, staleReview
}
struct MediaDeleteRow: Identifiable {
    let id = UUID()
    let item: CameraMediaItem
    let source: MediaDeleteSource
    let selectionToken: UUID?
    let loadedAtConfirmation: Bool
    let qualificationUnknown: Bool
    let skipReason: MediaDeleteReason?
    var status = CameraMediaDeleteOutcome.notStarted
    var reason: MediaDeleteReason?
    var applying = false
    var attemptID: UUID?
    var receiptSettled = false
}
struct MediaDeleteDraft: Identifiable {
    let id = UUID()
    let owner: UUID
    let generation: UUID
    let historyEpoch: UUID
    let ownerLabel: String
    let rows: [MediaDeleteRow]
    let hiddenCount: Int
    var unloadedCount: Int { rows.filter { !$0.loadedAtConfirmation }.count }
}
struct MediaDeleteRun: Identifiable {
    let id: UUID
    let owner: UUID
    let ownerLabel: String
    let hiddenCount: Int
    let unloadedCount: Int
    var rows: [MediaDeleteRow]
    var running = true
    var cancellationRequested = false
    var ownerRetired = false
    var inspected = false
}
enum MediaDeleteHistory {
    static let maximumBatches = 20
    static let maximumRows = 2_000
    static let maximumBatchRows = 1_000
}
enum MediaDeleteEligibility {
    static func reason(snapshot: CameraMediaItem, current: CameraMediaItem?, source: MediaDeleteSource,
                       currentSource: MediaDeleteSource, supported: Bool) -> MediaDeleteReason? {
        guard let current else { return .unloaded }
        guard !snapshot.id.isEmpty,
              snapshot.id.utf8.elementsEqual(current.id.utf8) else { return .invalid }
        guard source == currentSource else { return .sourceChanged }
        let decision = CameraMediaDeletePolicy.eligibility(source: source.rawValue, loaded: true, capability: supported,
            protected: snapshot.protected == true || current.protected == true ? true : current.protected,
            writable: snapshot.writable == false || current.writable == false ? false : current.writable)
        switch decision.reason {
        case "no-capability": return .unsupported
        case "unknown-source": return .unknownSource
        case "protected": return .protected
        case "not-writable": return .notWritable
        default: return nil
        }
    }
}
