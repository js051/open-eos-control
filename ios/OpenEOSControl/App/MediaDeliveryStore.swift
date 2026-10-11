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

struct MediaDeliverySnapshot: Sendable {
    let records: [MediaDeliveryRecord]
    let retainedBytes: Int64?
    let warning: MediaDeliveryStoreError?
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

    private struct Manifest: Codable, Sendable {
        var version = 1
        var epoch = UUID()
        var receipts: [Receipt] = []
        var tombstones: [RetainedFile] = []
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

    func beginItem(_ ticket: MediaDeliveryTicket, filename: String, expectedBytes: Int64? = nil) throws -> UUID {
        try validate(ticket)
        guard manifest!.receipts.count < limits.maximumRecords else { throw MediaDeliveryStoreError.recordLimit }
        let used = try diskUsage()
        if let expectedBytes {
            guard expectedBytes > 0, used <= limits.maximumRetainedBytes,
                  expectedBytes <= limits.maximumRetainedBytes - used else { throw MediaDeliveryStoreError.byteLimit }
        }
        let receipt = Receipt(id: UUID(), batchID: ticket.batchID,
            filename: MediaBatchOwnedFile.safeFilename(filename), startedAt: Date(), status: .started)
        var next = manifest!
        next.receipts.append(receipt)
        try commit(next)
        return receipt.id
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
            return MediaDeliverySnapshot(records: projectedRecords(), retainedBytes: used,
                warning: used > limits.maximumRetainedBytes ? .byteLimit : nil)
        } catch {
            return MediaDeliverySnapshot(records: projectedRecords(), retainedBytes: nil, warning: fixedError(error))
        }
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
        guard value.version == 1 else { throw MediaDeliveryStoreError.unsupportedSchema }
        let ids = value.receipts.map(\.id) + value.tombstones.map(\.id)
        guard value.receipts.count <= limits.maximumRecords,
              value.tombstones.count <= Self.maximumDirectoryEntries,
              Set(ids).count == ids.count else { throw MediaDeliveryStoreError.invalidManifest }
        for receipt in value.receipts {
            guard safeName(receipt.filename), validDate(receipt.startedAt),
                  receipt.completedAt.map(validDate) != false,
                  receipt.actualBytes.map({ $0 >= 0 }) != false else {
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

    private func validateKeys(_ data: Data) throws {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = object["version"] as? Int else { throw MediaDeliveryStoreError.invalidManifest }
        guard version == 1 else { throw MediaDeliveryStoreError.unsupportedSchema }
        guard Set(object.keys) == Set(["version", "epoch", "receipts", "tombstones"]),
              let receipts = object["receipts"] as? [[String: Any]],
              let tombstones = object["tombstones"] as? [[String: Any]] else { throw MediaDeliveryStoreError.invalidManifest }
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

    private func commit(_ value: Manifest) throws {
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
    private func canonicalUUID(_ name: String) -> Bool { UUID(uuidString: name)?.uuidString == name }
    private func isManifestTemporary(_ name: String) -> Bool {
        guard name.hasPrefix(".manifest-"), name.hasSuffix(".tmp") else { return false }
        return canonicalUUID(String(name.dropFirst(10).dropLast(4)))
    }
    private func fileURL(_ file: RetainedFile) -> URL {
        root.appendingPathComponent(file.id.uuidString, isDirectory: true).appendingPathComponent(file.filename)
    }
    private func fixedError(_ error: Error) -> MediaDeliveryStoreError {
        if error is CancellationError { return .cancelled }
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
