import Foundation
import OpenEOSCore

/// One explicitly enabled foreground run. IDs are opaque UTF-8 wire values;
/// neither filenames nor a shutter acknowledgement prove a new capture.
struct ForegroundMediaPlan {
    static let inventoryLimit = 1_000
    static let knownLimit = 4_096
    static let pendingLimit = 20
    static let resultLimit = 100
    static let observationInterval: TimeInterval = 1
    static let inventoryInterval: TimeInterval = 5
    static let pendingLifetime: TimeInterval = 30

    enum Failure: Error, Equatable {
        case invalidInventory, inventoryLimit, knownLimit, pendingLimit, resultLimit
        case unstableBaseline, expiredItem, invalidMetadata
    }

    struct Pending {
        let item: CameraMediaItem
        let discoveredAt: TimeInterval
        var firstSize: Int64?
        var firstObservedAt: TimeInterval?
    }

    private(set) var baselineEstablished = false
    private(set) var known = Set<Data>()
    private(set) var pending: [Pending] = []
    private(set) var preparedCount = 0
    private var baseline: Set<Data>?
    private var baselineAt: TimeInterval?
    private var baselineStartedAt: TimeInterval?

    mutating func observeInventory(_ items: [CameraMediaItem], at now: TimeInterval) throws {
        guard now.isFinite else { throw Failure.invalidInventory }
        guard items.count <= Self.inventoryLimit else { throw Failure.inventoryLimit }
        let identities = items.map(mediaItemIdentity)
        let current = Set(identities)
        guard identities.allSatisfy({ !$0.isEmpty && $0.count <= 8_192 }),
              current.count == items.count else { throw Failure.invalidInventory }
        if !baselineEstablished {
            if baselineStartedAt == nil { baselineStartedAt = now }
            guard now - (baselineStartedAt ?? now) <= Self.pendingLifetime else {
                throw Failure.unstableBaseline
            }
            if baseline == current, let baselineAt,
               now - baselineAt >= Self.observationInterval {
                known = current
                baselineEstablished = true
            } else if baseline != current {
                baseline = current
                baselineAt = now
            }
            return
        }
        let added = current.subtracting(known)
        guard known.count + added.count <= Self.knownLimit else { throw Failure.knownLimit }
        let newJPEGs = items.filter { added.contains(mediaItemIdentity($0)) && Self.isJPEG($0) }
        guard pending.count + newJPEGs.count <= Self.pendingLimit else { throw Failure.pendingLimit }
        // Remember every type. A RAW/video identity cannot later become a new
        // JPEG merely because a later listing changes its metadata.
        known.formUnion(current)
        pending.append(contentsOf: newJPEGs.map { Pending(item: $0, discoveredAt: now) })
        try checkPendingAge(at: now)
    }

    func checkPendingAge(at now: TimeInterval) throws {
        guard now.isFinite else { throw Failure.invalidMetadata }
        if pending.contains(where: { now - $0.discoveredAt > Self.pendingLifetime }) {
            throw Failure.expiredItem
        }
    }

    /// Only the current first item may advance. A changed fresh size restarts
    /// its observation interval; absent/invalid size is never treated as zero.
    mutating func observeFresh(_ item: CameraMediaItem, at now: TimeInterval) throws -> Int64? {
        try checkPendingAge(at: now)
        guard !pending.isEmpty,
              mediaItemIdentity(item) == mediaItemIdentity(pending[0].item),
              Self.isJPEG(item), let size = item.sizeBytes, size > 0 else {
            throw Failure.invalidMetadata
        }
        if pending[0].firstSize == size, let firstAt = pending[0].firstObservedAt,
           now - firstAt >= Self.observationInterval {
            guard preparedCount < Self.resultLimit else { throw Failure.resultLimit }
            return size
        }
        if pending[0].firstSize != size {
            pending[0].firstSize = size
            pending[0].firstObservedAt = now
        }
        return nil
    }

    mutating func recordPrepared(_ item: CameraMediaItem) throws {
        guard let first = pending.first, mediaItemIdentity(first.item) == mediaItemIdentity(item) else {
            throw Failure.invalidMetadata
        }
        guard preparedCount < Self.resultLimit else { throw Failure.resultLimit }
        pending.removeFirst()
        preparedCount += 1
    }

    static func isJPEG(_ item: CameraMediaItem) -> Bool {
        let suffix = (item.name as NSString).pathExtension.lowercased()
        guard suffix == "jpg" || suffix == "jpeg" else { return false }
        let kind = item.kind.lowercased()
        guard !["video", "movie", "raw", "audio"].contains(kind) else { return false }
        if let contentType = item.contentType?.split(separator: ";", maxSplits: 1).first?
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
           contentType != "image/jpeg" && contentType != "image/jpg" { return false }
        return true
    }
}
