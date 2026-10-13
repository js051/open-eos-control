import Darwin
import Foundation

/// Local delivery history contains no camera identity, URL, token, or raw error.
enum MediaDeliveryStatus: String, Codable, Sendable { case started, prepared, failed, cancelled, unknown }
enum MediaDeliveryAvailability: String, Sendable { case available, missing, changed, unsafe, notRetained }
enum MediaDeliveryReason: String, Codable, Sendable { case interrupted, downloadFailed, cancelled, retentionUnavailable }
enum MediaDeliveryStoreError: String, Error, Sendable {
    case storageUnavailable, invalidManifest, unsupportedSchema, unsafeFilesystem
    case recordLimit, byteLimit, invalidTicket, invalidReceipt, invalidSource, cancelled
}

struct MediaDeliveryLimits: Sendable {
    let maximumRecords: Int
    let maximumRetainedBytes: Int64
    static let production = MediaDeliveryLimits(maximumRecords: 100, maximumRetainedBytes: 512 * 1024 * 1024)
}

struct MediaDeliveryTicket: Sendable {
    let batchID: UUID
    fileprivate let epoch: UUID
}

/// The caller pairs these local row IDs with its in-memory selection only. The
/// manifest never stores a camera media ID or anything that can reconnect it.
struct MediaDeliveryPlannedBatch: Sendable {
    let ticket: MediaDeliveryTicket
    let rowIDs: [UUID]
}

/// A local history link only. It cannot identify or reconnect camera media.
struct MediaDeliveryOrigin: Hashable, Codable, Sendable {
    let batchID: UUID
    let rowID: UUID
}

struct MediaDeliveryRecord: Identifiable, Sendable {
    let id: UUID
    let batchID: UUID
    let filename: String
    let startedAt: Date
    let completedAt: Date?
    let status: MediaDeliveryStatus
    let actualBytes: Int64?
    let reason: MediaDeliveryReason?
    let availability: MediaDeliveryAvailability
}

struct MediaDeliveryPlannedRowRecord: Identifiable, Sendable {
    let id: UUID
    let filename: String
    /// Nil means this validated row has no admitted attempt: it is queued.
    /// A prepared receipt remains prepared even when its copy is unavailable.
    let receipt: MediaDeliveryRecord?
    let origin: MediaDeliveryOrigin?
    private let isContinuationBlocked: Bool
    private let hasUnknownLinkedAttempt: Bool

    var requiresUnknownAcknowledgement: Bool { receipt?.status == .unknown || hasUnknownLinkedAttempt }

    /// The actor also projects active/prepared linked work without changing
    /// this row's historical receipt, then rechecks admission and the epoch.
    var canContinue: Bool {
        guard !isContinuationBlocked else { return false }
        guard let status = receipt?.status else { return true }
        switch status {
        case .failed, .cancelled, .unknown: return true
        case .started, .prepared: return false
        }
    }

    init(id: UUID, filename: String, receipt: MediaDeliveryRecord?, origin: MediaDeliveryOrigin? = nil,
         isContinuationBlocked: Bool = false, hasUnknownLinkedAttempt: Bool = false) {
        self.id = id
        self.filename = filename
        self.receipt = receipt
        self.origin = origin
        self.isContinuationBlocked = isContinuationBlocked
        self.hasUnknownLinkedAttempt = hasUnknownLinkedAttempt
    }
}

struct MediaDeliveryPlannedBatchRecord: Identifiable, Sendable {
    let id: UUID
    let createdAt: Date
    /// Original selection order, including every still-queued row.
    let rows: [MediaDeliveryPlannedRowRecord]
}

struct MediaDeliverySnapshot: Sendable {
    let records: [MediaDeliveryRecord]
    let plannedBatches: [MediaDeliveryPlannedBatchRecord]
    let retainedBytes: Int64?
    let warning: MediaDeliveryStoreError?
    /// Nil when no validated history has been loaded. Clear changes this value.
    let epoch: UUID?

    init(records: [MediaDeliveryRecord], plannedBatches: [MediaDeliveryPlannedBatchRecord] = [],
         retainedBytes: Int64?, warning: MediaDeliveryStoreError?, epoch: UUID? = nil) {
        self.records = records
        self.plannedBatches = plannedBatches
        self.retainedBytes = retainedBytes
        self.warning = warning
        self.epoch = epoch
    }
}

/// Holding a lease pins its retained copy through clear and subsequent batches.
/// The URL cannot be changed; acquiring it always revalidates the owned file.
final class MediaDeliveryFileLease: @unchecked Sendable {
    let receiptID: UUID
    let fileURL: URL
    private let onRelease: @Sendable () -> Void

    fileprivate init(receiptID: UUID, fileURL: URL, onRelease: @escaping @Sendable () -> Void) {
        self.receiptID = receiptID
        self.fileURL = fileURL
        self.onRelease = onRelease
    }

    deinit { onRelease() }
}

/// One actor owns an explicit app-private root. The manifest is a bounded atomic
/// rename, not a claim of power-loss durability. Copies are streamed in chunks;
/// this bound excludes the existing camera transport's temporary download.
actor MediaDeliveryStore {
    enum FaultPoint: Sendable, Equatable { case copyChunk, beforeManifestReplace, beforeOwnedDelete, beforeOwnedDirectoryDelete }
    typealias FaultInjector = @Sendable (FaultPoint) throws -> Void

    private struct Receipt: Codable, Sendable {
        let id: UUID
        let batchID: UUID
        let filename: String
        let startedAt: Date
        var completedAt: Date?
        var status: MediaDeliveryStatus
        var actualBytes: Int64?
        var reason: MediaDeliveryReason?
        var identity: RetainedIdentity?

        var retained: Bool { status == .prepared && reason != .retentionUnavailable }
        var file: RetainedFile? {
            guard retained, let bytes = actualBytes, let identity else { return nil }
            return RetainedFile(id: id, filename: filename, actualBytes: bytes, identity: identity)
        }
    }

    /// Metadata establishes ownership of this exact local copy, not a camera
    /// content hash. Replacement, in-place edits, and backup inode changes are
    /// honestly unavailable rather than silently adopted as our file.
    private struct FileIdentity: Codable, Sendable, Equatable {
        let device: Int64
        let inode: UInt64
        let modifiedSeconds: Int64
        let modifiedNanoseconds: Int64
        let changedSeconds: Int64
        let changedNanoseconds: Int64

        init(_ info: stat) {
            device = Int64(info.st_dev)
            inode = UInt64(info.st_ino)
            modifiedSeconds = Int64(info.st_mtimespec.tv_sec)
            modifiedNanoseconds = Int64(info.st_mtimespec.tv_nsec)
            changedSeconds = Int64(info.st_ctimespec.tv_sec)
            changedNanoseconds = Int64(info.st_ctimespec.tv_nsec)
        }
    }

    private struct RetainedIdentity: Codable, Sendable {
        let directory: FileIdentity
        let file: FileIdentity
    }

    private struct RetainedFile: Codable, Sendable {
        let id: UUID
        let filename: String
        let actualBytes: Int64
        let identity: RetainedIdentity
    }

    private struct PlannedRow: Codable, Sendable {
        let id: UUID
        let filename: String
        let origin: MediaDeliveryOrigin?
    }

    private struct PlannedBatch: Codable, Sendable {
        let id: UUID
        let createdAt: Date
        let rows: [PlannedRow]
    }

    private struct Manifest: Codable, Sendable {
        var version = 3
        var epoch = UUID()
        var receipts: [Receipt] = []
        var tombstones: [RetainedFile] = []
        var plannedBatches: [PlannedBatch] = []

        private enum CodingKeys: String, CodingKey { case version, epoch, receipts, tombstones, plannedBatches }

        init() {}

        init(from decoder: Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            version = try values.decode(Int.self, forKey: .version)
            epoch = try values.decode(UUID.self, forKey: .epoch)
            receipts = try values.decode([Receipt].self, forKey: .receipts)
            tombstones = try values.decode([RetainedFile].self, forKey: .tombstones)
            // Only validated v1 data may omit the complete selection roster.
            if version == 1 { plannedBatches = [] }
            else { plannedBatches = try values.decode([PlannedBatch].self, forKey: .plannedBatches) }
        }
    }

    private final class Descriptor {
        let value: Int32
        init(_ value: Int32) { self.value = value }
        deinit { Darwin.close(value) }
    }

    private let root: URL
    private let limits: MediaDeliveryLimits
    private let injectFault: FaultInjector
    private var rootDescriptor: Descriptor?
    private var manifest: Manifest?
    private var committedManifestData: Data?
    private var blocked: MediaDeliveryStoreError?
    private var pins: [UUID: Int] = [:]
    private static let manifestName = "manifest.json"
    private static let maximumManifestBytes = 256 * 1024
    private static let maximumDirectoryEntries = 512
    private static let chunkBytes = 64 * 1024

    /// The parent exists already; only this explicit root may be created here.
    /// Resolve legitimate ancestor aliases in the app's factory before injection.
    init(root: URL, limits: MediaDeliveryLimits = .production,
         faultInjector: @escaping FaultInjector = { _ in }) {
        self.root = root.standardizedFileURL
        self.limits = MediaDeliveryLimits(maximumRecords: max(1, min(100, limits.maximumRecords)),
            maximumRetainedBytes: max(0, min(512 * 1024 * 1024, limits.maximumRetainedBytes)))
        self.injectFault = faultInjector
    }

    func beginBatch() throws -> MediaDeliveryTicket {
        try load()
        _ = try diskUsage()
        return MediaDeliveryTicket(batchID: UUID(), epoch: manifest!.epoch)
    }

    /// Atomically admit the entire inert roster before the caller issues its
    /// first original GET. Capacity reserves queued rows, never truncates or
    /// evicts history, and does not promise retained space for unknown sizes.
    func beginPlannedBatch(filenames: [String]) throws -> MediaDeliveryPlannedBatch {
        try load()
        guard !filenames.isEmpty else { throw MediaDeliveryStoreError.invalidReceipt }
        guard filenames.count <= limits.maximumRecords - reservedRecordCount(manifest!) else {
            throw MediaDeliveryStoreError.recordLimit
        }
        _ = try diskUsage()
        let ticket = MediaDeliveryTicket(batchID: UUID(), epoch: manifest!.epoch)
        let rows = filenames.map { PlannedRow(id: UUID(), filename: deliveryFilename($0), origin: nil) }
        var next = manifest!
        next.plannedBatches.append(PlannedBatch(id: ticket.batchID, createdAt: Date(), rows: rows))
        try commit(next)
        return MediaDeliveryPlannedBatch(ticket: ticket, rowIDs: rows.map(\.id))
    }

    /// Admit a new inert roster after the user maps each historical row to a
    /// current selection. Neither display names nor links authorize camera I/O.
    /// Original rows and receipts remain untouched, including prepared facts.
    func beginContinuationBatch(origins: [MediaDeliveryOrigin], filenames: [String],
                                expectedEpoch: UUID, acknowledgedUnknown: Bool) throws -> MediaDeliveryPlannedBatch {
        try Task.checkCancellation()
        try load()
        guard manifest!.epoch == expectedEpoch else { throw MediaDeliveryStoreError.invalidTicket }
        guard !origins.isEmpty, origins.count == filenames.count,
              Set(origins).count == origins.count else { throw MediaDeliveryStoreError.invalidReceipt }
        guard origins.count <= limits.maximumRecords - reservedRecordCount(manifest!) else {
            throw MediaDeliveryStoreError.recordLimit
        }
        let current = manifest!
        let roots = lineageRoots(in: current)
        guard Set(origins.map { roots[$0] ?? $0 }).count == origins.count else {
            throw MediaDeliveryStoreError.invalidReceipt
        }
        for origin in origins {
            guard let batch = current.plannedBatches.first(where: { $0.id == origin.batchID }),
                  batch.rows.contains(where: { $0.id == origin.rowID }),
                  !hasBlockingLinkedAttempt(origin, in: current) else { throw MediaDeliveryStoreError.invalidReceipt }
            if let receipt = current.receipts.first(where: { $0.id == origin.rowID }) {
                guard receipt.status != .started, receipt.status != .prepared else { throw MediaDeliveryStoreError.invalidReceipt }
            }
            guard acknowledgedUnknown || !hasLinkedAttempt(origin, in: current, matching: { $0 == .unknown }) else {
                throw MediaDeliveryStoreError.invalidReceipt
            }
        }
        let used = try diskUsage()
        guard used < limits.maximumRetainedBytes else { throw MediaDeliveryStoreError.byteLimit }
        let ticket = MediaDeliveryTicket(batchID: UUID(), epoch: current.epoch)
        let rows = zip(origins, filenames).map { origin, filename in
            PlannedRow(id: UUID(), filename: deliveryFilename(filename), origin: origin)
        }
        var next = current
        next.plannedBatches.append(PlannedBatch(id: ticket.batchID, createdAt: Date(), rows: rows))
        guard manifest!.epoch == expectedEpoch else { throw MediaDeliveryStoreError.invalidTicket }
        try commit(next, requireUncancelled: true)
        return MediaDeliveryPlannedBatch(ticket: ticket, rowIDs: rows.map(\.id))
    }

    /// The reserved row ID becomes the receipt ID. Admission is single-use;
    /// neither filenames nor a restored roster grant permission to retry I/O.
    func beginPlannedItem(_ ticket: MediaDeliveryTicket, rowID: UUID,
                          expectedBytes: Int64? = nil) throws -> UUID {
        try validate(ticket)
        guard let batch = manifest!.plannedBatches.first(where: { $0.id == ticket.batchID }),
              let row = batch.rows.first(where: { $0.id == rowID }),
              !manifest!.receipts.contains(where: { $0.id == rowID }) else {
            throw MediaDeliveryStoreError.invalidReceipt
        }
        guard !hasBlockingLinkedAttempt(MediaDeliveryOrigin(batchID: batch.id, rowID: row.id), in: manifest!) else {
            throw MediaDeliveryStoreError.invalidReceipt
        }
        try validateExpectedBytes(expectedBytes)
        var next = manifest!
        next.receipts.append(Receipt(id: row.id, batchID: batch.id,
            filename: row.filename, startedAt: Date(), status: .started))
        try commit(next)
        return row.id
    }

    /// Revalidate immediately before the caller issues an original GET. Clear
    /// invalidates the ticket even when an attempt was already admitted.
    func validatePlannedDownload(_ ticket: MediaDeliveryTicket, rowID: UUID) throws {
        _ = try receiptIndex(ticket, rowID)
        guard manifest!.plannedBatches.contains(where: { batch in
            batch.id == ticket.batchID && batch.rows.contains(where: { $0.id == rowID })
        }) else { throw MediaDeliveryStoreError.invalidReceipt }
        guard try diskUsage() < limits.maximumRetainedBytes else { throw MediaDeliveryStoreError.byteLimit }
    }

    func beginItem(_ ticket: MediaDeliveryTicket, filename: String, expectedBytes: Int64? = nil) throws -> UUID {
        try validate(ticket)
        guard !manifest!.plannedBatches.contains(where: { $0.id == ticket.batchID }) else {
            throw MediaDeliveryStoreError.invalidTicket
        }
        guard reservedRecordCount(manifest!) < limits.maximumRecords else { throw MediaDeliveryStoreError.recordLimit }
        try validateExpectedBytes(expectedBytes)
        let receipt = Receipt(id: UUID(), batchID: ticket.batchID,
            filename: deliveryFilename(filename), startedAt: Date(), status: .started)
        var next = manifest!
        next.receipts.append(receipt)
        try commit(next)
        return receipt.id
    }

    private func validateExpectedBytes(_ expectedBytes: Int64?) throws {
        let used = try diskUsage()
        if let expectedBytes {
            guard expectedBytes > 0, used <= limits.maximumRetainedBytes,
                  expectedBytes <= limits.maximumRetainedBytes - used else { throw MediaDeliveryStoreError.byteLimit }
        }
    }

    /// The temporary source is never moved, unlinked, or otherwise adopted.
    /// If retention fails after validating that original, retain its historical
    /// prepared fact when the manifest remains writable, and return a warning.
    func finishPrepared(_ ticket: MediaDeliveryTicket, receiptID: UUID,
                        source: MediaBatchOwnedFile, actualBytes: Int64) throws {
        let index = try receiptIndex(ticket, receiptID)
        guard actualBytes > 0 else { throw MediaDeliveryStoreError.invalidSource }
        let sourceFD = try openSource(source.fileURL, expectedBytes: actualBytes)
        let initial = try regularStat(sourceFD.value)
        do {
            let used = try diskUsage()
            guard used <= limits.maximumRetainedBytes,
                  actualBytes <= limits.maximumRetainedBytes - used else { throw MediaDeliveryStoreError.byteLimit }
            try Task.checkCancellation()
            let identity = try retainCopy(sourceFD: sourceFD.value, original: initial,
                receipt: manifest!.receipts[index], actualBytes: actualBytes, existingBytes: used)
            var next = manifest!
            next.receipts[index].status = .prepared
            next.receipts[index].completedAt = Date()
            next.receipts[index].actualBytes = actualBytes
            next.receipts[index].reason = nil
            next.receipts[index].identity = identity
            do { try commit(next) }
            catch {
                // A failed manifest replacement cannot promote this copy. Only
                // our exact newly created directory is eligible for cleanup.
                try? removeOwnedFile(RetainedFile(id: receiptID,
                    filename: next.receipts[index].filename, actualBytes: actualBytes, identity: identity))
                throw error
            }
        } catch {
            let failure = fixedError(error)
            var next = manifest!
            next.receipts[index].status = .prepared
            next.receipts[index].completedAt = Date()
            next.receipts[index].actualBytes = actualBytes
            next.receipts[index].reason = .retentionUnavailable
            next.receipts[index].identity = nil
            // If the disk itself is unwritable, leave the last atomic manifest
            // intact. A restart will honestly project its started item unknown.
            try? commit(next)
            throw failure
        }
    }

    func finishFailure(_ ticket: MediaDeliveryTicket, receiptID: UUID, cancelled: Bool) throws {
        let index = try receiptIndex(ticket, receiptID)
        var next = manifest!
        next.receipts[index].status = cancelled ? .cancelled : .failed
        next.receipts[index].completedAt = Date()
        next.receipts[index].reason = cancelled ? .cancelled : .downloadFailed
        try commit(next)
    }

    func snapshot() -> MediaDeliverySnapshot {
        do {
            try load()
            let used = try diskUsage()
            return projectedSnapshot(retainedBytes: used, warning: used > limits.maximumRetainedBytes ? .byteLimit : nil)
        } catch {
            return projectedSnapshot(retainedBytes: nil, warning: fixedError(error))
        }
    }

    private func projectedSnapshot(retainedBytes: Int64?, warning: MediaDeliveryStoreError?) -> MediaDeliverySnapshot {
        let records = projectedRecords()
        let receipts = Dictionary(uniqueKeysWithValues: records.map { ($0.id, $0) })
        let batches = (manifest?.plannedBatches ?? []).map { batch in
            MediaDeliveryPlannedBatchRecord(id: batch.id, createdAt: batch.createdAt, rows: batch.rows.map { row in
                MediaDeliveryPlannedRowRecord(id: row.id, filename: row.filename, receipt: receipts[row.id], origin: row.origin,
                    isContinuationBlocked: manifest.map {
                        hasBlockingLinkedAttempt(MediaDeliveryOrigin(batchID: batch.id, rowID: row.id), in: $0)
                    } ?? false,
                    hasUnknownLinkedAttempt: manifest.map {
                        hasLinkedAttempt(MediaDeliveryOrigin(batchID: batch.id, rowID: row.id), in: $0, matching: { $0 == .unknown })
                    } ?? false)
            })
        }
        return MediaDeliverySnapshot(records: records, plannedBatches: batches, retainedBytes: retainedBytes,
            warning: warning, epoch: manifest?.epoch)
    }

    private func projectedRecords() -> [MediaDeliveryRecord] {
        (manifest?.receipts ?? []).map { receipt in
            MediaDeliveryRecord(id: receipt.id, batchID: receipt.batchID, filename: receipt.filename,
                startedAt: receipt.startedAt, completedAt: receipt.completedAt,
                status: receipt.status, actualBytes: receipt.actualBytes, reason: receipt.reason,
                availability: receipt.file.map(availability) ?? .notRetained)
        }
    }

    func acquireShare(receiptIDs: [UUID]) throws -> [MediaDeliveryFileLease] {
        try load()
        _ = try diskUsage()
        guard !receiptIDs.isEmpty, Set(receiptIDs).count == receiptIDs.count,
              receiptIDs.count <= limits.maximumRecords else { throw MediaDeliveryStoreError.invalidReceipt }
        var files: [RetainedFile] = []
        for id in receiptIDs {
            guard let file = manifest!.receipts.first(where: { $0.id == id })?.file,
                  availability(file) == .available else { throw MediaDeliveryStoreError.invalidSource }
            files.append(file)
        }
        // Validate the whole request before pinning any part of it.
        return files.map { file in
            pins[file.id, default: 0] += 1
            return MediaDeliveryFileLease(receiptID: file.id, fileURL: fileURL(file)) { [self] in
                Task { await self.release(file.id) }
            }
        }
    }

    /// Commit the epoch change before touching any file. Pinned copies remain
    /// explicit tombstones, so bytes still count and active exporters keep them.
    func clear() throws {
        try load()
        _ = try diskUsage()
        var next = manifest!
        next.epoch = UUID()
        next.tombstones += next.receipts.compactMap(\.file)
        next.receipts = []
        next.plannedBatches = []
        try commit(next)
        try cleanTombstones()
    }

    private func release(_ id: UUID) {
        guard let count = pins[id] else { return }
        if count > 1 { pins[id] = count - 1 } else { pins.removeValue(forKey: id) }
        try? cleanTombstones()
    }

    private func cleanTombstones() throws {
        guard let current = manifest else { return }
        try verifyRoot()
        try verifyManifestIsUnchanged()
        _ = try diskUsage()
        var next = current
        var failure: MediaDeliveryStoreError?
        for file in current.tombstones where pins[file.id] == nil {
            do {
                try removeOwnedFile(file)
                next.tombstones.removeAll { $0.id == file.id }
            } catch { failure = fixedError(error) }
        }
        if next.tombstones.count != current.tombstones.count { try commit(next) }
        if let failure { throw failure }
    }

    private func validate(_ ticket: MediaDeliveryTicket) throws {
        try load()
        guard ticket.epoch == manifest!.epoch else { throw MediaDeliveryStoreError.invalidTicket }
    }

    private func receiptIndex(_ ticket: MediaDeliveryTicket, _ id: UUID) throws -> Int {
        try validate(ticket)
        guard let index = manifest!.receipts.firstIndex(where: {
            $0.id == id && $0.batchID == ticket.batchID && $0.status == .started
        }) else { throw MediaDeliveryStoreError.invalidReceipt }
        return index
    }

    private func load() throws {
        if let blocked { throw blocked }
        if manifest != nil {
            try verifyRoot()
            try verifyManifestIsUnchanged()
            return
        }
        do {
            guard root.isFileURL, root.path != "/",
                  root.resolvingSymlinksInPath().path == root.path else { throw MediaDeliveryStoreError.unsafeFilesystem }
            var info = stat()
            if Darwin.lstat(root.path, &info) != 0 {
                guard errno == ENOENT, Darwin.mkdir(root.path, mode_t(S_IRWXU)) == 0 else {
                    throw MediaDeliveryStoreError.storageUnavailable
                }
            }
            let fd = Darwin.open(root.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
            guard fd >= 0 else { throw MediaDeliveryStoreError.unsafeFilesystem }
            rootDescriptor = Descriptor(fd)
            try verifyRoot()
            let manifestFD = Darwin.openat(fd, Self.manifestName, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
            if manifestFD < 0 {
                guard errno == ENOENT else { throw MediaDeliveryStoreError.unsafeFilesystem }
                guard try entries(fd).isEmpty else { throw MediaDeliveryStoreError.invalidManifest }
                let fresh = Manifest()
                try commit(fresh)
            } else {
                let descriptor = Descriptor(manifestFD)
                let info = try regularStat(descriptor.value)
                guard info.st_size >= 0, info.st_size <= Self.maximumManifestBytes else {
                    throw MediaDeliveryStoreError.invalidManifest
                }
                let data = try readBounded(descriptor.value, limit: Self.maximumManifestBytes)
                guard data.count == info.st_size else { throw MediaDeliveryStoreError.invalidManifest }
                try validateKeys(data)
                var saved: Manifest
                do { saved = try JSONDecoder().decode(Manifest.self, from: data) }
                catch { throw MediaDeliveryStoreError.invalidManifest }
                try validateManifest(saved)
                // Migration is in memory only. Reading legacy receipts must not
                // invent a full roster or rewrite their ownership evidence.
                saved.version = 3
                // A previous process cannot establish how far started work got.
                for index in saved.receipts.indices where saved.receipts[index].status == .started {
                    saved.receipts[index].status = .unknown
                    saved.receipts[index].reason = .interrupted
                }
                manifest = saved
                committedManifestData = data
            }
            _ = try diskUsage()
        } catch {
            blocked = fixedError(error)
            throw blocked!
        }
    }

    private func validateManifest(_ value: Manifest) throws {
        guard (1...3).contains(value.version) else { throw MediaDeliveryStoreError.unsupportedSchema }
        let ids = value.receipts.map(\.id) + value.tombstones.map(\.id)
        let batches = value.plannedBatches
        let rows = batches.flatMap(\.rows)
        let rowIDs = rows.map(\.id)
        let batchIDs = batches.map(\.id)
        guard (value.version != 1 || batches.isEmpty),
              reservedRecordCount(value) <= limits.maximumRecords,
              value.tombstones.count <= Self.maximumDirectoryEntries,
              Set(ids).count == ids.count,
              Set(batchIDs).count == batchIDs.count,
              Set(rowIDs).count == rowIDs.count,
              Set(rowIDs).isDisjoint(with: value.tombstones.map(\.id)),
              Set(batchIDs).isDisjoint(with: ids + rowIDs) else { throw MediaDeliveryStoreError.invalidManifest }
        var priorRoots: [MediaDeliveryOrigin: MediaDeliveryOrigin] = [:]
        for batch in batches {
            guard validDate(batch.createdAt), !batch.rows.isEmpty else { throw MediaDeliveryStoreError.invalidManifest }
            let origins = batch.rows.compactMap(\.origin)
            guard Set(origins).count == origins.count,
                  Set(origins.compactMap { priorRoots[$0] }).count == origins.count else {
                throw MediaDeliveryStoreError.invalidManifest
            }
            for row in batch.rows {
                guard safeName(row.filename), row.filename == deliveryFilename(row.filename) else {
                    throw MediaDeliveryStoreError.invalidManifest
                }
                // References must point to an earlier batch's exact local row.
                // This rejects dangling, self, forward, and cyclic links. Do not
                // revalidate historical eligibility: facts may later complete.
                if let origin = row.origin {
                    guard value.version == 3, priorRoots[origin] != nil else {
                        throw MediaDeliveryStoreError.invalidManifest
                    }
                }
                if let receipt = value.receipts.first(where: { $0.id == row.id }) {
                    guard receipt.batchID == batch.id, receipt.filename == row.filename else {
                        throw MediaDeliveryStoreError.invalidManifest
                    }
                }
            }
            for row in batch.rows {
                let reference = MediaDeliveryOrigin(batchID: batch.id, rowID: row.id)
                priorRoots[reference] = row.origin.flatMap { priorRoots[$0] } ?? reference
            }
        }
        for receipt in value.receipts {
            guard safeName(receipt.filename), validDate(receipt.startedAt),
                  receipt.completedAt.map(validDate) != false,
                  receipt.actualBytes.map({ $0 >= 0 }) != false else {
                throw MediaDeliveryStoreError.invalidManifest
            }
            // A receipt claiming a planned batch must belong to its roster.
            // Legacy/dynamic receipts make no complete-roster claim.
            if let batch = batches.first(where: { $0.id == receipt.batchID }),
               !batch.rows.contains(where: { $0.id == receipt.id }) {
                throw MediaDeliveryStoreError.invalidManifest
            }
            switch receipt.status {
            case .started:
                guard receipt.completedAt == nil, receipt.actualBytes == nil, receipt.reason == nil, receipt.identity == nil else {
                    throw MediaDeliveryStoreError.invalidManifest
                }
            case .prepared:
                guard receipt.completedAt != nil, receipt.actualBytes != nil,
                      receipt.reason == nil || receipt.reason == .retentionUnavailable,
                      receipt.retained == (receipt.identity != nil),
                      !receipt.retained || receipt.actualBytes! <= 512 * 1024 * 1024 else {
                    throw MediaDeliveryStoreError.invalidManifest
                }
            case .failed, .cancelled:
                guard receipt.completedAt != nil, receipt.actualBytes == nil, receipt.identity == nil,
                      receipt.reason == (receipt.status == .failed ? .downloadFailed : .cancelled) else {
                    throw MediaDeliveryStoreError.invalidManifest
                }
            case .unknown:
                guard receipt.actualBytes == nil, receipt.identity == nil, receipt.reason == .interrupted else {
                    throw MediaDeliveryStoreError.invalidManifest
                }
            }
        }
        for file in value.tombstones {
            guard safeName(file.filename), file.actualBytes >= 0,
                  file.actualBytes <= 512 * 1024 * 1024 else { throw MediaDeliveryStoreError.invalidManifest }
        }
    }

    private func reservedRecordCount(_ value: Manifest) -> Int {
        let receiptIDs = Set(value.receipts.map(\.id))
        return value.receipts.count + value.plannedBatches.reduce(0) { count, batch in
            count + batch.rows.filter { !receiptIDs.contains($0.id) }.count
        }
    }

    /// Queued links alone do not prove a live owner after a restart. Active
    /// receipts do. A linked prepared fact also excludes continued work even
    /// when its local copy is unavailable; the original gallery flow remains
    /// separate. Siblings and ancestors share the same local lineage root.
    /// Rechecking at item admission also serializes separately admitted rosters.
    private func hasBlockingLinkedAttempt(_ origin: MediaDeliveryOrigin, in value: Manifest) -> Bool {
        hasLinkedAttempt(origin, in: value, matching: { $0 == .started || $0 == .prepared })
    }

    private func hasLinkedAttempt(_ origin: MediaDeliveryOrigin, in value: Manifest,
                                  matching: (MediaDeliveryStatus) -> Bool) -> Bool {
        let roots = lineageRoots(in: value)
        let requestedRoot = roots[origin] ?? origin
        return value.receipts.contains { receipt in
            let reference = MediaDeliveryOrigin(batchID: receipt.batchID, rowID: receipt.id)
            return matching(receipt.status) && (roots[reference] ?? reference) == requestedRoot
        }
    }

    private func lineageRoots(in value: Manifest) -> [MediaDeliveryOrigin: MediaDeliveryOrigin] {
        var roots: [MediaDeliveryOrigin: MediaDeliveryOrigin] = [:]
        // Valid manifests only reference earlier batches, so roots are known
        // before each child and traversal never follows a cycle.
        for batch in value.plannedBatches {
            for row in batch.rows {
                let reference = MediaDeliveryOrigin(batchID: batch.id, rowID: row.id)
                roots[reference] = row.origin.flatMap { roots[$0] } ?? reference
            }
        }
        return roots
    }

    private func validateKeys(_ data: Data) throws {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = object["version"] as? Int else { throw MediaDeliveryStoreError.invalidManifest }
        guard (1...3).contains(version) else { throw MediaDeliveryStoreError.unsupportedSchema }
        let manifestKeys: Set<String> = version == 1
            ? ["version", "epoch", "receipts", "tombstones"]
            : ["version", "epoch", "receipts", "tombstones", "plannedBatches"]
        guard Set(object.keys) == manifestKeys,
              let receipts = object["receipts"] as? [[String: Any]],
              let tombstones = object["tombstones"] as? [[String: Any]] else { throw MediaDeliveryStoreError.invalidManifest }
        if version >= 2 {
            guard let batches = object["plannedBatches"] as? [[String: Any]] else {
                throw MediaDeliveryStoreError.invalidManifest
            }
            for batch in batches {
                guard Set(batch.keys) == Set(["id", "createdAt", "rows"]),
                      let rows = batch["rows"] as? [[String: Any]] else {
                    throw MediaDeliveryStoreError.invalidManifest
                }
                for row in rows {
                    let keys = Set(row.keys)
                    guard keys == Set(["id", "filename"]) ||
                          (version == 3 && keys == Set(["id", "filename", "origin"])) else {
                        throw MediaDeliveryStoreError.invalidManifest
                    }
                    if let origin = row["origin"] {
                        guard let reference = origin as? [String: Any],
                              Set(reference.keys) == Set(["batchID", "rowID"]) else {
                            throw MediaDeliveryStoreError.invalidManifest
                        }
                    }
                }
            }
        }
        let receiptKeys: Set<String> = ["id", "batchID", "filename", "startedAt", "completedAt", "status", "actualBytes", "reason", "identity"]
        for receipt in receipts where !Set(receipt.keys).isSubset(of: receiptKeys) {
            throw MediaDeliveryStoreError.invalidManifest
        }
        for file in tombstones where Set(file.keys) != Set(["id", "filename", "actualBytes", "identity"]) {
            throw MediaDeliveryStoreError.invalidManifest
        }
        let identityKeys: Set<String> = ["device", "inode", "modifiedSeconds", "modifiedNanoseconds", "changedSeconds", "changedNanoseconds"]
        for entry in receipts + tombstones {
            guard let value = entry["identity"] else { continue }
            guard let identity = value as? [String: Any], Set(identity.keys) == Set(["directory", "file"]) else {
                throw MediaDeliveryStoreError.invalidManifest
            }
            for value in identity.values {
                guard let fields = value as? [String: Any], Set(fields.keys) == identityKeys else {
                    throw MediaDeliveryStoreError.invalidManifest
                }
            }
        }
    }

    private func commit(_ value: Manifest, requireUncancelled: Bool = false) throws {
        if requireUncancelled { try Task.checkCancellation() }
        try verifyRoot()
        _ = try diskUsage()
        try verifyManifestIsUnchanged()
        try validateManifest(value)
        let data = try JSONEncoder().encode(value)
        guard data.count <= Self.maximumManifestBytes else { throw MediaDeliveryStoreError.invalidManifest }
        let rootFD = rootDescriptor!.value
        let name = ".manifest-\(UUID().uuidString).tmp"
        let fd = Darwin.openat(rootFD, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, mode_t(S_IRUSR | S_IWUSR))
        guard fd >= 0 else { throw MediaDeliveryStoreError.storageUnavailable }
        var open = true
        var temporaryIdentity = FileIdentity(try regularStat(fd))
        defer {
            if open { Darwin.close(fd) }
            if let current = try? childStat(rootFD, name),
               isRegular(current), current.st_nlink == 1, FileIdentity(current) == temporaryIdentity {
                Darwin.unlinkat(rootFD, name, 0)
            }
        }
        try writeAll(fd, data)
        temporaryIdentity = FileIdentity(try regularStat(fd))
        guard Darwin.close(fd) == 0 else { open = false; throw MediaDeliveryStoreError.storageUnavailable }
        open = false
        try injectFault(.beforeManifestReplace)
        try verifyRoot()
        try verifyManifestIsUnchanged()
        guard FileIdentity(try childStat(rootFD, name)) == temporaryIdentity else {
            throw MediaDeliveryStoreError.unsafeFilesystem
        }
        var target = stat()
        if Darwin.fstatat(rootFD, Self.manifestName, &target, AT_SYMLINK_NOFOLLOW) == 0 {
            guard isRegular(target), target.st_nlink == 1 else { throw MediaDeliveryStoreError.unsafeFilesystem }
        } else if errno != ENOENT { throw MediaDeliveryStoreError.storageUnavailable }
        // Continuation admission is cancellable until its atomic replacement.
        // Other callers retain their existing terminal-receipt semantics.
        if requireUncancelled { try Task.checkCancellation() }
        guard Darwin.renameat(rootFD, name, rootFD, Self.manifestName) == 0 else {
            throw MediaDeliveryStoreError.storageUnavailable
        }
        manifest = value
        committedManifestData = data
    }

    private func verifyManifestIsUnchanged() throws {
        let fd = Darwin.openat(rootDescriptor!.value, Self.manifestName, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
        if fd < 0 {
            guard errno == ENOENT, committedManifestData == nil else { throw MediaDeliveryStoreError.unsafeFilesystem }
            return
        }
        let descriptor = Descriptor(fd)
        let info = try regularStat(descriptor.value)
        guard let expected = committedManifestData, info.st_size == expected.count,
              try readBounded(descriptor.value, limit: Self.maximumManifestBytes) == expected else {
            throw MediaDeliveryStoreError.invalidManifest
        }
    }

    /// Enumerate only bounded direct children. Unknown entries and links are
    /// preserved, never followed or recursively cleaned. Orphan UUID directories
    /// count toward the cap but are never interpreted as prepared receipts.
    private func diskUsage() throws -> Int64 {
        try verifyRoot()
        let rootFD = rootDescriptor!.value
        var total: Int64 = 0
        for name in try entries(rootFD) {
            let info = try childStat(rootFD, name)
            if name == Self.manifestName || isManifestTemporary(name) {
                guard isRegular(info), info.st_nlink == 1,
                      info.st_size <= Self.maximumManifestBytes else { throw MediaDeliveryStoreError.unsafeFilesystem }
                continue
            }
            guard canonicalUUID(name), isDirectory(info) else { throw MediaDeliveryStoreError.unsafeFilesystem }
            let directoryFD = Darwin.openat(rootFD, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
            guard directoryFD >= 0 else { throw MediaDeliveryStoreError.unsafeFilesystem }
            let directory = Descriptor(directoryFD)
            let children = try entries(directory.value)
            guard children.count <= 1 else { throw MediaDeliveryStoreError.unsafeFilesystem }
            for child in children {
                let childInfo = try childStat(directory.value, child)
                guard safeName(child), isRegular(childInfo), childInfo.st_nlink == 1,
                      childInfo.st_size >= 0, total <= Int64.max - childInfo.st_size else {
                    throw MediaDeliveryStoreError.unsafeFilesystem
                }
                total += childInfo.st_size
            }
        }
        try verifyRoot()
        return total
    }

    private func retainCopy(sourceFD: Int32, original: stat, receipt: Receipt,
                            actualBytes: Int64, existingBytes: Int64) throws -> RetainedIdentity {
        let rootFD = rootDescriptor!.value
        let name = receipt.id.uuidString
        guard Darwin.mkdirat(rootFD, name, mode_t(S_IRWXU)) == 0 else { throw MediaDeliveryStoreError.unsafeFilesystem }
        let directoryFD = Darwin.openat(rootFD, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        guard directoryFD >= 0 else { throw MediaDeliveryStoreError.unsafeFilesystem }
        defer { Darwin.close(directoryFD) }
        let originalDirectory = try descriptorStat(directoryFD)
        let outputFD = Darwin.openat(directoryFD, receipt.filename,
            O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, mode_t(S_IRUSR | S_IWUSR))
        guard outputFD >= 0 else {
            // Only an unchanged empty directory that we created can be removed.
            if let current = try? childStat(rootFD, name),
               FileIdentity(current) == FileIdentity(originalDirectory),
               (try? entries(directoryFD).isEmpty) == true {
                Darwin.unlinkat(rootFD, name, AT_REMOVEDIR)
            }
            throw MediaDeliveryStoreError.storageUnavailable
        }
        var outputOpen = true
        var completed = false
        let directoryIdentity = FileIdentity(try descriptorStat(directoryFD))
        var outputIdentity = FileIdentity(try regularStat(outputFD))
        defer {
            if outputOpen { Darwin.close(outputFD) }
            if !completed {
                // A fault may replace the path while our descriptor is open.
                // Never unlink that replacement, even if its length matches.
                cleanPartial(rootFD: rootFD, directoryFD: directoryFD, name: name,
                    filename: receipt.filename, directoryIdentity: directoryIdentity, fileIdentity: outputIdentity)
            }
        }
        var copied: Int64 = 0
        var buffer = [UInt8](repeating: 0, count: Self.chunkBytes)
        while true {
            try Task.checkCancellation()
            try injectFault(.copyChunk)
            let read = Darwin.read(sourceFD, &buffer, buffer.count)
            if read < 0 { if errno == EINTR { continue }; throw MediaDeliveryStoreError.storageUnavailable }
            if read == 0 { break }
            guard Int64(read) <= actualBytes - copied,
                  Int64(read) <= limits.maximumRetainedBytes - existingBytes - copied else {
                throw MediaDeliveryStoreError.byteLimit
            }
            try writeAll(outputFD, Data(buffer.prefix(read)))
            outputIdentity = FileIdentity(try regularStat(outputFD))
            copied += Int64(read)
        }
        let after = try regularStat(sourceFD)
        guard copied == actualBytes, sameFile(original, after), after.st_size == actualBytes,
              FileIdentity(original) == FileIdentity(after) else { throw MediaDeliveryStoreError.invalidSource }
        let finalOutput = try regularStat(outputFD)
        guard finalOutput.st_size == actualBytes,
              FileIdentity(finalOutput) == outputIdentity else { throw MediaDeliveryStoreError.storageUnavailable }
        guard Darwin.close(outputFD) == 0 else { outputOpen = false; throw MediaDeliveryStoreError.storageUnavailable }
        outputOpen = false
        try Task.checkCancellation()
        try verifyRoot()
        let identity = RetainedIdentity(directory: directoryIdentity, file: outputIdentity)
        guard availability(RetainedFile(id: receipt.id, filename: receipt.filename,
                actualBytes: actualBytes, identity: identity)) == .available else {
            throw MediaDeliveryStoreError.invalidSource
        }
        completed = true
        return identity
    }

    private func cleanPartial(rootFD: Int32, directoryFD: Int32, name: String, filename: String,
                              directoryIdentity: FileIdentity, fileIdentity: FileIdentity) {
        do { try verifyRoot(); try verifyManifestIsUnchanged() }
        catch { return }
        guard let opened = try? descriptorStat(directoryFD),
              let directoryPath = try? childStat(rootFD, name),
              FileIdentity(opened) == directoryIdentity,
              FileIdentity(directoryPath) == directoryIdentity,
              (try? entries(directoryFD)) == [filename],
              let path = try? childStat(directoryFD, filename), isRegular(path), path.st_nlink == 1,
              FileIdentity(path) == fileIdentity else { return }
        guard Darwin.unlinkat(directoryFD, filename, 0) == 0 else { return }
        // Unlink changes directory timestamps; recheck its inode and emptiness.
        guard let remaining = try? childStat(rootFD, name), sameFile(opened, remaining),
              (try? entries(directoryFD).isEmpty) == true else { return }
        Darwin.unlinkat(rootFD, name, AT_REMOVEDIR)
    }

    private func openSource(_ url: URL, expectedBytes: Int64) throws -> Descriptor {
        // Only the system temporary ancestor may have a legitimate alias (for
        // example /var -> /private/var). Owned components themselves must never
        // be symlinks. MediaBatchOwnedFile has no arbitrary-URL constructor.
        let owned = url.deletingLastPathComponent()
        guard url.isFileURL, safeName(url.lastPathComponent), canonicalUUID(owned.lastPathComponent),
              owned.deletingLastPathComponent().lastPathComponent == "BatchMedia",
              owned.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent == "OpenEOSControl" else {
            throw MediaDeliveryStoreError.invalidSource
        }
        let temporary = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
        let expected = temporary.appendingPathComponent("OpenEOSControl", isDirectory: true)
            .appendingPathComponent("BatchMedia", isDirectory: true)
            .appendingPathComponent(owned.lastPathComponent, isDirectory: true)
            .appendingPathComponent(url.lastPathComponent)
        guard url.resolvingSymlinksInPath().path == expected.path else { throw MediaDeliveryStoreError.invalidSource }
        let temporaryFD = Darwin.open(temporary.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        guard temporaryFD >= 0 else { throw MediaDeliveryStoreError.invalidSource }
        var directory = Descriptor(temporaryFD)
        for name in ["OpenEOSControl", "BatchMedia", owned.lastPathComponent] {
            let next = Darwin.openat(directory.value, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
            guard next >= 0 else { throw MediaDeliveryStoreError.invalidSource }
            directory = Descriptor(next)
        }
        let fd = Darwin.openat(directory.value, url.lastPathComponent, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
        guard fd >= 0 else { throw MediaDeliveryStoreError.invalidSource }
        let descriptor = Descriptor(fd)
        let opened = try regularStat(fd)
        guard opened.st_size == expectedBytes, sameFile(opened, try childStat(directory.value, url.lastPathComponent)) else {
            throw MediaDeliveryStoreError.invalidSource
        }
        return descriptor
    }

    private func availability(_ file: RetainedFile) -> MediaDeliveryAvailability {
        do {
            try verifyRoot()
            let fd = Darwin.openat(rootDescriptor!.value, file.id.uuidString,
                O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
            if fd < 0 { return errno == ENOENT ? .missing : .unsafe }
            let directory = Descriptor(fd)
            var info = stat()
            if Darwin.fstatat(directory.value, file.filename, &info, AT_SYMLINK_NOFOLLOW) != 0 {
                return errno == ENOENT ? .missing : .unsafe
            }
            guard isRegular(info), info.st_nlink == 1 else { return .unsafe }
            var opened = stat()
            guard Darwin.fstat(directory.value, &opened) == 0,
                  sameFile(opened, try childStat(rootDescriptor!.value, file.id.uuidString)) else { return .unsafe }
            try verifyRoot()
            guard info.st_size == file.actualBytes,
                  FileIdentity(opened) == file.identity.directory,
                  FileIdentity(info) == file.identity.file else { return .changed }
            return .available
        } catch { return .unsafe }
    }

    private func removeOwnedFile(_ file: RetainedFile) throws {
        try verifyRoot()
        try injectFault(.beforeOwnedDelete)
        try verifyRoot()
        try verifyManifestIsUnchanged()
        let rootFD = rootDescriptor!.value
        let name = file.id.uuidString
        let directoryFD = Darwin.openat(rootFD, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        if directoryFD < 0 {
            if errno == ENOENT { return }
            throw MediaDeliveryStoreError.unsafeFilesystem
        }
        defer { Darwin.close(directoryFD) }
        let openedDirectory = try descriptorStat(directoryFD)
        let pathDirectory = try childStat(rootFD, name)
        guard sameNode(openedDirectory, file.identity.directory), sameFile(openedDirectory, pathDirectory) else {
            throw MediaDeliveryStoreError.unsafeFilesystem
        }
        let names = try entries(directoryFD)
        guard names.isEmpty || names == [file.filename] else { throw MediaDeliveryStoreError.unsafeFilesystem }
        if !names.isEmpty {
            let info = try childStat(directoryFD, file.filename)
            guard FileIdentity(openedDirectory) == file.identity.directory,
                  FileIdentity(pathDirectory) == file.identity.directory,
                  isRegular(info), info.st_nlink == 1, info.st_size == file.actualBytes,
                  FileIdentity(info) == file.identity.file else { throw MediaDeliveryStoreError.unsafeFilesystem }
            guard Darwin.unlinkat(directoryFD, file.filename, 0) == 0 else { throw MediaDeliveryStoreError.storageUnavailable }
        }
        try injectFault(.beforeOwnedDirectoryDelete)
        try verifyRoot()
        try verifyManifestIsUnchanged()
        // An earlier own unlink may have succeeded while rmdir/commit failed.
        // Empty directories need their original inode, not stale timestamps.
        guard sameFile(openedDirectory, try childStat(rootFD, name)),
              try entries(directoryFD).isEmpty else { throw MediaDeliveryStoreError.unsafeFilesystem }
        guard Darwin.unlinkat(rootFD, name, AT_REMOVEDIR) == 0 else { throw MediaDeliveryStoreError.storageUnavailable }
    }

    private func verifyRoot() throws {
        guard let fd = rootDescriptor?.value else { throw MediaDeliveryStoreError.storageUnavailable }
        var opened = stat()
        guard Darwin.fstat(fd, &opened) == 0, isDirectory(opened) else { throw MediaDeliveryStoreError.unsafeFilesystem }
        let path = try pathStat(root)
        guard isDirectory(path), sameFile(opened, path),
              root.resolvingSymlinksInPath().path == root.path else { throw MediaDeliveryStoreError.unsafeFilesystem }
    }

    private func entries(_ fd: Int32) throws -> [String] {
        let copied = Darwin.dup(fd)
        guard copied >= 0 else { throw MediaDeliveryStoreError.storageUnavailable }
        guard let directory = Darwin.fdopendir(copied) else {
            Darwin.close(copied)
            throw MediaDeliveryStoreError.storageUnavailable
        }
        defer { Darwin.closedir(directory) }
        // dup shares a directory offset, so start every bounded scan at zero.
        Darwin.rewinddir(directory)
        var names: [String] = []
        errno = 0
        while let entry = Darwin.readdir(directory) {
            let capacity = MemoryLayout.size(ofValue: entry.pointee.d_name)
            let name = withUnsafePointer(to: &entry.pointee.d_name) {
                $0.withMemoryRebound(to: CChar.self, capacity: capacity) { String(cString: $0) }
            }
            if name == "." || name == ".." { continue }
            guard names.count < Self.maximumDirectoryEntries else { throw MediaDeliveryStoreError.unsafeFilesystem }
            names.append(name)
        }
        guard errno == 0 else { throw MediaDeliveryStoreError.storageUnavailable }
        return names
    }

    private func childStat(_ fd: Int32, _ name: String) throws -> stat {
        var info = stat()
        guard Darwin.fstatat(fd, name, &info, AT_SYMLINK_NOFOLLOW) == 0 else {
            throw MediaDeliveryStoreError.storageUnavailable
        }
        return info
    }

    private func pathStat(_ url: URL) throws -> stat {
        var info = stat()
        guard Darwin.lstat(url.path, &info) == 0 else { throw MediaDeliveryStoreError.storageUnavailable }
        return info
    }

    private func descriptorStat(_ fd: Int32) throws -> stat {
        var info = stat()
        guard Darwin.fstat(fd, &info) == 0 else { throw MediaDeliveryStoreError.storageUnavailable }
        return info
    }

    private func regularStat(_ fd: Int32) throws -> stat {
        var info = stat()
        guard Darwin.fstat(fd, &info) == 0, isRegular(info), info.st_nlink == 1 else {
            throw MediaDeliveryStoreError.invalidSource
        }
        return info
    }

    private func isRegular(_ info: stat) -> Bool { info.st_mode & S_IFMT == S_IFREG }
    private func isDirectory(_ info: stat) -> Bool { info.st_mode & S_IFMT == S_IFDIR }
    private func sameFile(_ lhs: stat, _ rhs: stat) -> Bool { lhs.st_dev == rhs.st_dev && lhs.st_ino == rhs.st_ino }
    private func sameNode(_ info: stat, _ identity: FileIdentity) -> Bool {
        Int64(info.st_dev) == identity.device && UInt64(info.st_ino) == identity.inode
    }
    private func validDate(_ date: Date) -> Bool {
        date.timeIntervalSince1970.isFinite && date >= .distantPast && date <= .distantFuture
    }
    private func safeName(_ name: String) -> Bool { !name.isEmpty && name == MediaBatchOwnedFile.safeFilename(name) }
    private func deliveryFilename(_ value: String) -> String {
        // A hostile display label must not persist URL query/fragment secrets.
        // Drop the authority too if a caller accidentally supplies a full URL.
        let label = String(value.prefix { $0 != "?" && $0 != "#" })
        if let scheme = label.range(of: "://") {
            let authorityAndPath = label[scheme.upperBound...]
            guard let path = authorityAndPath.firstIndex(of: "/") else { return "original-media" }
            return MediaBatchOwnedFile.safeFilename(String(authorityAndPath[path...]))
        }
        return MediaBatchOwnedFile.safeFilename(label)
    }
    private func canonicalUUID(_ name: String) -> Bool { UUID(uuidString: name)?.uuidString == name }
    private func isManifestTemporary(_ name: String) -> Bool {
        guard name.hasPrefix(".manifest-"), name.hasSuffix(".tmp") else { return false }
        return canonicalUUID(String(name.dropFirst(10).dropLast(4)))
    }
    private func fileURL(_ file: RetainedFile) -> URL {
        root.appendingPathComponent(file.id.uuidString, isDirectory: true).appendingPathComponent(file.filename)
    }
    private func fixedError(_ error: Error) -> MediaDeliveryStoreError {
        if error is CancellationError || (error as? URLError)?.code == .cancelled { return .cancelled }
        return error as? MediaDeliveryStoreError ?? .storageUnavailable
    }

    private func readBounded(_ fd: Int32, limit: Int) throws -> Data {
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: Self.chunkBytes)
        while true {
            let count = Darwin.read(fd, &buffer, min(buffer.count, limit - data.count + 1))
            if count < 0 { if errno == EINTR { continue }; throw MediaDeliveryStoreError.storageUnavailable }
            if count == 0 { return data }
            guard count <= limit - data.count else { throw MediaDeliveryStoreError.invalidManifest }
            data.append(contentsOf: buffer.prefix(count))
        }
    }

    private func writeAll(_ fd: Int32, _ data: Data) throws {
        try data.withUnsafeBytes { bytes in
            var offset = 0
            while offset < bytes.count {
                let count = Darwin.write(fd, bytes.baseAddress!.advanced(by: offset), bytes.count - offset)
                if count < 0 { if errno == EINTR { continue }; throw MediaDeliveryStoreError.storageUnavailable }
                guard count > 0 else { throw MediaDeliveryStoreError.storageUnavailable }
                offset += count
            }
        }
    }
}
