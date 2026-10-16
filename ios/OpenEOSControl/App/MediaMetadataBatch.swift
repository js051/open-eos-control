import Foundation
import OpenEOSCore

enum MediaMetadataBatchStatus: String {
    case notStarted, applying, confirmed, failedNotSent, unconfirmed
}
enum MediaMetadataBatchReason: String {
    case unloaded, unsupported, unknownMetadata, notWritable, invalid, cancelled, ownerChanged, blocked, notSent, uncertain, limit
}
struct MediaMetadataBatchRow: Identifiable {
    let id = UUID()
    let item: CameraMediaItem
    let loadedAtConfirmation: Bool
    var status = MediaMetadataBatchStatus.notStarted
    var reason: MediaMetadataBatchReason?
}
struct MediaMetadataBatchDraft: Identifiable {
    let id = UUID()
    let target: CameraMediaMutation
    let sessionID: UUID
    let generation: UUID
    let historyEpoch: UUID
    let rows: [MediaMetadataBatchRow]
    let hiddenCount: Int
    var unloadedCount: Int { rows.filter { !$0.loadedAtConfirmation }.count }

    static func rows(selection: [CameraMediaItem], loaded: [CameraMediaItem]) -> [MediaMetadataBatchRow] {
        var available: [Data: CameraMediaItem] = [:]
        for item in loaded where available[mediaItemIdentity(item)] == nil { available[mediaItemIdentity(item)] = item }
        var seen = Set<Data>()
        return selection.filter { seen.insert(mediaItemIdentity($0)).inserted }.map {
            let current = available[mediaItemIdentity($0)]
            return MediaMetadataBatchRow(item: current ?? $0, loadedAtConfirmation: current != nil)
        }
    }
}
/// Admission bounds apply to all retained owners, including rejected terminal runs.
enum MediaMetadataBatchHistory {
    static let maximumBatches = 20
    static let maximumRows = 2_000
}
struct MediaMetadataBatchRejection {
    enum Reason: String { case capacity, limit, staleReview }
    let selectedCount: Int
    let reason: Reason
}

enum MediaMetadataBatchEligibility {
    static func reason(snapshot: CameraMediaItem, current: CameraMediaItem?, target: CameraMediaMutation,
                       supported: Bool) -> MediaMetadataBatchReason? {
        guard let current else { return .unloaded }
        guard snapshot.id.utf8.elementsEqual(current.id.utf8),
              !snapshot.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, target.valid else { return .invalid }
        guard supported else { return .unsupported }
        guard target.isWritable(snapshot), target.isWritable(current) else { return .notWritable }
        if case .archive = target, snapshot.archived == nil || current.archived == nil { return .unknownMetadata }
        return nil
    }
}

struct MediaMetadataBatchRun: Identifiable {
    let id: UUID
    let target: CameraMediaMutation
    let hiddenCount: Int
    let unloadedCount: Int
    var rows: [MediaMetadataBatchRow]
    var running = true
    var cancellationRequested = false
    var ownerRetired = false
}

extension CameraMediaMutation {
    /// A mutation response only establishes its requested field. Preserve the
    /// original identity, unrelated metadata and writable vetoes even if omitted
    /// or contradicted by a partial response.
    func mergingConfirmedField(into item: CameraMediaItem) -> CameraMediaItem {
        var rating = item.rating
        var protection = item.protected
        var rotation = item.rotationDegrees
        var archive = item.archived
        switch self {
        case let .rating(value): rating = value
        case let .protection(value): protection = value
        case let .rotation(value): rotation = value
        case let .archive(value): archive = value
        }
        return CameraMediaItem(id: item.id, name: item.name, kind: item.kind, sizeBytes: item.sizeBytes,
            captureTime: item.captureTime, previewAvailable: item.previewAvailable, protected: protection,
            rating: rating, rotationDegrees: rotation, archived: archive, contentType: item.contentType,
            widthPixels: item.widthPixels, heightPixels: item.heightPixels, folder: item.folder,
            writable: item.writable, ratingWritable: item.ratingWritable, protectionWritable: item.protectionWritable,
            rotationWritable: item.rotationWritable, archiveWritable: item.archiveWritable)
    }


    var actionID: String {
        switch self {
        case let .rating(value): "rating-\(value)"
        case let .protection(value): "protection-\(value)"
        case let .rotation(value): "rotation-\(value)"
        case let .archive(value): "archive-\(value)"
        }
    }
    var labelKey: String {
        switch self {
        case .rating: "media_mutation_rating"
        case let .protection(value): value ? "media_mutation_protect" : "media_mutation_unprotect"
        case .rotation: "media_mutation_rotate"
        case let .archive(value): value ? "media_mutation_archive" : "media_mutation_unarchive"
        }
    }
    @MainActor func label(_ language: AppLanguageStore) -> String {
        switch self {
        case let .rating(value), let .rotation(value): language.format(labelKey, value)
        default: language.string(labelKey)
        }
    }
}
