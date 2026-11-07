import Foundation

/// External evidence is independent of the retained-original preparation fact.
/// These fixed values contain no provider URLs, camera identity, or raw errors.
enum ForegroundFilesStatus: String, Codable, Sendable { case started, copied, notCopied, unconfirmed }
enum ForegroundFilesReason: String, Codable, Sendable {
    case cancelled, sourceUnavailable, permissionDenied, coordination, collision, copyFailed, interrupted
}

struct ForegroundFilesOutcome: Equatable, Sendable {
    let status: ForegroundFilesStatus
    let reason: ForegroundFilesReason?
    static let copied = Self(status: .copied, reason: nil)
    static func notCopied(_ reason: ForegroundFilesReason) -> Self { Self(status: .notCopied, reason: reason) }
    static func unconfirmed(_ reason: ForegroundFilesReason) -> Self { Self(status: .unconfirmed, reason: reason) }
}

struct ForegroundFilesRecord: Identifiable, Codable, Sendable {
    let id: UUID
    let receiptID: UUID
    let filename: String
    let startedAt: Date
    var completedAt: Date?
    var status: ForegroundFilesStatus
    var reason: ForegroundFilesReason?
}

struct ForegroundFilesDeliveryResult: Sendable {
    let outcome: ForegroundFilesOutcome
    let historyWarning: Bool
}

protocol ForegroundFilesCoordinating: AnyObject, Sendable {
    /// Returns only after the accessor (if admitted) has returned.
    func coordinate(_ url: URL, accessor: @escaping @Sendable (URL) -> Void) -> Bool
    func cancel()
}

private final class SystemForegroundFilesCoordinator: ForegroundFilesCoordinating, @unchecked Sendable {
    private let coordinator = NSFileCoordinator(filePresenter: nil)
    func coordinate(_ url: URL, accessor: @escaping @Sendable (URL) -> Void) -> Bool {
        var error: NSError?
        coordinator.coordinate(writingItemAt: url, options: [], error: &error, byAccessor: accessor)
        return error == nil
    }
    func cancel() { coordinator.cancel() }
}

/// Dependencies are synchronous: neither coordination nor copy may return
/// while an admitted accessor still uses the source or security scope.
struct ForegroundFilesAccess: Sendable {
    var startScope: @Sendable (URL) -> Bool = { $0.startAccessingSecurityScopedResource() }
    var stopScope: @Sendable (URL) -> Void = { $0.stopAccessingSecurityScopedResource() }
    var coordinator: @Sendable () -> any ForegroundFilesCoordinating = { SystemForegroundFilesCoordinator() }
    var copy: @Sendable (URL, URL) throws -> Void = { source, destination in
        // A fresh manager has no delegate which could suppress a copy error.
        // copyItem is create-only: never delete, truncate or replace a target.
        try FileManager().copyItem(at: source, to: destination)
    }
}

/// Owns one synchronous provider operation off the main actor. Cancellation
/// asks coordination to stop; it never releases resources or invents a result.
final class ForegroundFilesCopyOperation: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private var coordinator: (any ForegroundFilesCoordinating)?
    private var observed: ForegroundFilesOutcome?

    func cancel() {
        lock.lock()
        cancelled = true
        let active = coordinator
        lock.unlock()
        active?.cancel()
    }

    private var isCancelled: Bool {
        lock.lock(); defer { lock.unlock() }
        return cancelled
    }

    private func record(_ result: ForegroundFilesOutcome) {
        lock.lock(); defer { lock.unlock() }
        observed = result
    }

    func run(lease: MediaDeliveryFileLease, folder: URL, filename: String,
             access: ForegroundFilesAccess) -> ForegroundFilesOutcome {
        guard !isCancelled else { return .notCopied(.cancelled) }
        guard MediaFilesExportSourceIdentity.read(lease.fileURL) == lease.filesExportSourceIdentity else {
            return .notCopied(.sourceUnavailable)
        }
        guard folder.isFileURL, access.startScope(folder) else { return .notCopied(.permissionDenied) }
        defer { access.stopScope(folder) }
        guard !isCancelled else { return .notCopied(.cancelled) }
        let active = access.coordinator()
        lock.lock()
        coordinator = active
        let alreadyCancelled = cancelled
        lock.unlock()
        if alreadyCancelled { active.cancel() }
        defer {
            lock.lock()
            coordinator = nil
            lock.unlock()
        }
        let coordinated = active.coordinate(folder.appendingPathComponent(filename)) { [self] destination in
            guard !isCancelled else { record(.notCopied(.cancelled)); return }
            guard MediaFilesExportSourceIdentity.read(lease.fileURL) == lease.filesExportSourceIdentity else {
                record(.notCopied(.sourceUnavailable)); return
            }
            do {
                // Use the coordinator's actual URL, including provider redirects.
                try access.copy(lease.fileURL, destination)
                // Metadata checks are ownership evidence, not a content hash.
                // Source edits during the copy leave the external bytes uncertain.
                guard MediaFilesExportSourceIdentity.read(lease.fileURL) == lease.filesExportSourceIdentity else {
                    record(.unconfirmed(.sourceUnavailable)); return
                }
                // Capture success before consulting cancellation or returning
                // to another actor. A late Stop cannot relabel a completed copy.
                record(.copied)
            } catch {
                if (error as NSError).domain == NSCocoaErrorDomain,
                   (error as NSError).code == NSFileWriteFileExistsError {
                    record(.notCopied(.collision))
                } else {
                    // Never remove an uncertain external file or inspect other
                    // user files to infer whether a partial copy survived.
                    record(.unconfirmed(.copyFailed))
                }
            }
        }
        lock.lock()
        let result = observed
        lock.unlock()
        if let result { return result }
        if isCancelled { return .notCopied(.cancelled) }
        // Even a nominal coordination return with no accessor is not a copy.
        _ = coordinated
        return .notCopied(.coordination)
    }
}

/// App-owned, separate from the camera generation and sheet. A retired camera
/// may release camera controls, but cannot overlap this still-draining writer.
@MainActor
final class ForegroundFilesDelivery: ObservableObject {
    @Published private(set) var isWriting = false
    private let access: ForegroundFilesAccess

    init(access: ForegroundFilesAccess = ForegroundFilesAccess()) { self.access = access }

    func deliver(lease: MediaDeliveryFileLease, ticket: MediaDeliveryTicket,
                 folder: URL, attemptID: UUID, filename: String,
                 store: MediaDeliveryStore) async -> ForegroundFilesDeliveryResult {
        guard !isWriting else {
            return ForegroundFilesDeliveryResult(outcome: .notCopied(.coordination), historyWarning: true)
        }
        isWriting = true
        defer { isWriting = false }
        let operation = ForegroundFilesCopyOperation()
        let access = access
        // This non-throwing owned task records its actual result even when the
        // awaiting camera task is cancelled or retired. It retains the exact
        // lease, folder URL and operation through completion and bookkeeping.
        return await withTaskCancellationHandler {
            let worker = Task.detached {
                do {
                    try await store.beginExternalAttempt(ticket, receiptID: lease.receiptID,
                                                         attemptID: attemptID, filename: filename)
                } catch {
                    return ForegroundFilesDeliveryResult(outcome: .notCopied(.coordination), historyWarning: true)
                }
                let outcome = operation.run(lease: lease, folder: folder, filename: filename, access: access)
                do {
                    try await store.finishExternalAttempt(ticket, attemptID: attemptID, outcome: outcome)
                    return ForegroundFilesDeliveryResult(outcome: outcome, historyWarning: false)
                } catch {
                    // Clear's epoch rejects this old result; a disk failure
                    // leaves started/unknown on disk. Neither undoes a copy.
                    return ForegroundFilesDeliveryResult(outcome: outcome, historyWarning: true)
                }
            }
            return await worker.value
        } onCancel: {
            operation.cancel()
        }
    }

    static func filename(_ original: String, attemptID: UUID) -> String {
        let label = String(original.prefix { $0 != "?" && $0 != "#" })
        let extensionPart = (label as NSString).pathExtension.lowercased() == "jpeg" ? ".jpeg" : ".jpg"
        let suffix = "-\(attemptID.uuidString)\(extensionPart)"
        var stem = MediaBatchOwnedFile.safeFilename((label as NSString).deletingPathExtension)
        while stem.utf8.count + suffix.utf8.count > 180 { stem.removeLast() }
        if stem.isEmpty { stem = "original" }
        return stem + suffix
    }
}
