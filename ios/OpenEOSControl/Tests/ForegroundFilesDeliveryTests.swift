import Foundation
import XCTest

@testable import OpenEOSControl

/// Synthetic local folders and injected scope/coordinator behavior only.
/// Execution status: NOT_RUN until compiled and run with Xcode on macOS.
@MainActor
final class ForegroundFilesDeliveryTests: XCTestCase {
    func testCopyUsesRedirectedAccessorURLAndPersistsIndependentSuccess() async throws {
        let fixture = try ForegroundFilesFixture()
        defer { fixture.remove() }
        let prepared = try await fixture.prepare()
        let spy = ForegroundFilesSpy()
        let redirected = fixture.folder.appendingPathComponent("REDIRECTED.JPG")
        let coordinator = ForegroundFilesTestCoordinator { _, accessor in accessor(redirected); return true }
        let delivery = ForegroundFilesDelivery(access: spy.access(coordinator: coordinator))
        let result = await fixture.deliver(delivery, prepared)
        XCTAssertEqual(result.outcome, .copied)
        XCTAssertFalse(result.historyWarning)
        XCTAssertEqual(spy.scopeCounts, [1, 1])
        XCTAssertEqual(try Data(contentsOf: redirected), fixture.bytes)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.destination.path))
        let restored = await MediaDeliveryStore(root: fixture.root).snapshot()
        XCTAssertEqual(restored.records.map(\.status), [.prepared])
        XCTAssertEqual(restored.externalAttempts.map(\.status), [.copied])
        XCTAssertEqual(restored.externalAttempts.map(\.receiptID), [prepared.lease.receiptID])
        XCTAssertEqual(restored.externalAttempts.map(\.filename), [fixture.filename])
    }

    func testPermissionDenialAndCoordinationRejectionNeverCopyAndBalanceScope() async throws {
        for denied in [true, false] {
            let fixture = try ForegroundFilesFixture()
            defer { fixture.remove() }
            let prepared = try await fixture.prepare()
            let spy = ForegroundFilesSpy()
            let coordinator = ForegroundFilesTestCoordinator { _, _ in false }
            let delivery = ForegroundFilesDelivery(access: spy.access(coordinator: coordinator, allowed: !denied))
            let result = await fixture.deliver(delivery, prepared)
            XCTAssertEqual(result.outcome, .notCopied(denied ? .permissionDenied : .coordination))
            XCTAssertEqual(spy.scopeCounts, denied ? [1, 0] : [1, 1])
            XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.destination.path))
            let snapshot = await fixture.store.snapshot()
            XCTAssertEqual(snapshot.records.first?.status, .prepared)
            XCTAssertEqual(snapshot.externalAttempts.first?.status, .notCopied)
        }
    }

    func testChangedLeasedSourceIsRejectedBeforeScopeOrProviderAccess() async throws {
        let fixture = try ForegroundFilesFixture()
        defer { fixture.remove() }
        let prepared = try await fixture.prepare()
        try Data([9, 9, 9, 9]).write(to: prepared.lease.fileURL, options: .atomic)
        let spy = ForegroundFilesSpy()
        let outcome = ForegroundFilesCopyOperation().run(lease: prepared.lease, folder: fixture.folder,
            filename: fixture.filename, access: spy.access())
        XCTAssertEqual(outcome, .notCopied(.sourceUnavailable))
        XCTAssertEqual(spy.scopeCounts, [0, 0])
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.destination.path))
    }

    func testExistingDestinationIsNeverReplacedAndPartialFailureIsNotCleaned() async throws {
        for collision in [true, false] {
            let fixture = try ForegroundFilesFixture()
            defer { fixture.remove() }
            let prepared = try await fixture.prepare()
            let existing = Data([7, 8])
            let spy = ForegroundFilesSpy()
            var access = spy.access()
            if collision { try existing.write(to: fixture.destination) }
            else {
                access.copy = { _, destination in
                    try existing.write(to: destination, options: .withoutOverwriting)
                    throw CocoaError(.fileWriteOutOfSpace)
                }
            }
            let result = await fixture.deliver(ForegroundFilesDelivery(access: access), prepared)
            XCTAssertEqual(result.outcome, collision ? .notCopied(.collision) : .unconfirmed(.copyFailed))
            XCTAssertEqual(try Data(contentsOf: fixture.destination), existing)
            XCTAssertEqual(spy.scopeCounts, [1, 1])
            let snapshot = await fixture.store.snapshot()
            XCTAssertEqual(snapshot.records.first?.status, .prepared)
        }
    }

    func testCancelBeforeAccessorWaitsForActualDrainAndNeverCopies() async throws {
        let fixture = try ForegroundFilesFixture()
        defer { fixture.remove() }
        let prepared = try await fixture.prepare()
        let gate = ForegroundFilesGate()
        defer { gate.release() }
        let spy = ForegroundFilesSpy()
        let coordinator = ForegroundFilesTestCoordinator { url, accessor in
            gate.enter()
            accessor(url)
            return true
        }
        let delivery = ForegroundFilesDelivery(access: spy.access(coordinator: coordinator))
        let task = Task { await fixture.deliver(delivery, prepared) }
        try await filesWait(gate, task: task)
        task.cancel()
        XCTAssertTrue(delivery.isWriting)
        XCTAssertEqual(spy.scopeCounts, [1, 0], "Scope stays acquired while coordination has not returned")
        gate.release()
        let result = await task.value
        XCTAssertEqual(result.outcome, .notCopied(.cancelled))
        XCTAssertFalse(delivery.isWriting)
        XCTAssertEqual(spy.scopeCounts, [1, 1])
        XCTAssertTrue(coordinator.wasCancelled)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.destination.path))
    }

    func testCancelInsideCopyPreservesLateSuccessAndRejectsOverlappingDelivery() async throws {
        let fixture = try ForegroundFilesFixture()
        defer { fixture.remove() }
        let prepared = try await fixture.prepare()
        let gate = ForegroundFilesGate()
        defer { gate.release() }
        let spy = ForegroundFilesSpy()
        var access = spy.access()
        access.copy = { source, destination in
            try FileManager().copyItem(at: source, to: destination)
            gate.enter()
        }
        let delivery = ForegroundFilesDelivery(access: access)
        let task = Task { await fixture.deliver(delivery, prepared) }
        try await filesWait(gate, task: task)
        task.cancel()
        let rejected = await fixture.deliver(delivery, prepared)
        XCTAssertEqual(rejected.outcome, .notCopied(.coordination))
        XCTAssertTrue(delivery.isWriting, "Rejected admission cannot release the original writer's gate")
        XCTAssertEqual(spy.scopeCounts, [1, 0])
        gate.release()
        let result = await task.value
        XCTAssertEqual(result.outcome, .copied)
        XCTAssertFalse(result.historyWarning)
        XCTAssertFalse(delivery.isWriting)
        let restored = await MediaDeliveryStore(root: fixture.root).snapshot()
        XCTAssertEqual(restored.externalAttempts.map(\.status), [.copied])
    }

    func testClearWhileWritingPinsSourceAndRejectsLateHistoryResurrection() async throws {
        let fixture = try ForegroundFilesFixture()
        defer { fixture.remove() }
        let prepared = try await fixture.prepare()
        let gate = ForegroundFilesGate()
        defer { gate.release() }
        var access = ForegroundFilesSpy().access()
        access.copy = { source, destination in
            gate.enter()
            try FileManager().copyItem(at: source, to: destination)
        }
        let delivery = ForegroundFilesDelivery(access: access)
        let task = Task { await fixture.deliver(delivery, prepared) }
        try await filesWait(gate, task: task)
        task.cancel()
        try await fixture.store.clear()
        XCTAssertTrue(delivery.isWriting)
        XCTAssertEqual(try Data(contentsOf: prepared.lease.fileURL), fixture.bytes)
        let cleared = await fixture.store.snapshot()
        XCTAssertTrue(cleared.records.isEmpty)
        XCTAssertTrue(cleared.externalAttempts.isEmpty)
        gate.release()
        let result = await task.value
        XCTAssertEqual(result.outcome, .copied)
        XCTAssertTrue(result.historyWarning)
        let final = await fixture.store.snapshot()
        XCTAssertTrue(final.records.isEmpty)
        XCTAssertTrue(final.externalAttempts.isEmpty)
        XCTAssertEqual(try Data(contentsOf: fixture.destination), fixture.bytes)
    }

    func testSuccessfulCopyWithFailedHistoryWriteRemainsSuccessAndReopensUnconfirmed() async throws {
        let fault = ForegroundFilesFault()
        let fixture = try ForegroundFilesFixture(fault: { point in
            if point == .beforeManifestReplace, fault.enabled { throw MediaDeliveryStoreError.storageUnavailable }
        })
        defer { fixture.remove() }
        let prepared = try await fixture.prepare()
        var access = ForegroundFilesSpy().access()
        access.copy = { source, destination in
            try FileManager().copyItem(at: source, to: destination)
            fault.enable()
        }
        let result = await fixture.deliver(ForegroundFilesDelivery(access: access), prepared)
        XCTAssertEqual(result.outcome, .copied)
        XCTAssertTrue(result.historyWarning)
        XCTAssertEqual(try Data(contentsOf: fixture.destination), fixture.bytes)
        let reopened = await MediaDeliveryStore(root: fixture.root).snapshot()
        XCTAssertEqual(reopened.records.first?.status, .prepared)
        XCTAssertEqual(reopened.externalAttempts.first?.status, .unconfirmed)
        XCTAssertEqual(reopened.externalAttempts.first?.reason, .interrupted)
    }

    func testV1V2V3MigrateOnlyOnExternalAdmissionAndInterruptedAttemptProjectsUnknown() async throws {
        for version in 1...3 {
            let fixture = try ForegroundFilesFixture()
            defer { fixture.remove() }
            let prepared = try await fixture.prepare()
            var object = try fixture.manifest()
            object["version"] = version
            if version == 1 { object.removeValue(forKey: "plannedBatches") }
            let original = try JSONSerialization.data(withJSONObject: object)
            try original.write(to: fixture.manifestURL)
            let store = MediaDeliveryStore(root: fixture.root)
            let old = await store.snapshot()
            XCTAssertNil(old.warning)
            XCTAssertTrue(old.externalAttempts.isEmpty)
            XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), original)
            try await store.beginExternalAttempt(prepared.ticket, receiptID: prepared.lease.receiptID,
                attemptID: fixture.attemptID, filename: fixture.filename)
            XCTAssertEqual(try fixture.manifest()["version"] as? Int, 4)
            let reopened = await MediaDeliveryStore(root: fixture.root).snapshot()
            XCTAssertNil(reopened.warning)
            XCTAssertEqual(reopened.externalAttempts.first?.status, .unconfirmed)
            XCTAssertEqual(reopened.externalAttempts.first?.reason, .interrupted)
            XCTAssertEqual(reopened.records.first?.status, .prepared)
        }
    }

    func testStrictExternalManifestRejectsUnknownKeysDuplicateReceiptsAndFalseSuccess() async throws {
        for corruption in ["extra", "duplicate", "missing-completion", "dangling", "unbounded"] {
            let fixture = try ForegroundFilesFixture()
            defer { fixture.remove() }
            let prepared = try await fixture.prepare()
            try await fixture.store.beginExternalAttempt(prepared.ticket, receiptID: prepared.lease.receiptID,
                attemptID: fixture.attemptID, filename: fixture.filename)
            var object = try fixture.manifest()
            var attempts = try XCTUnwrap(object["externalAttempts"] as? [[String: Any]])
            switch corruption {
            case "extra": attempts[0]["providerURL"] = "SYNTHETIC-REJECTED"
            case "duplicate": attempts.append(attempts[0])
            case "missing-completion": attempts[0]["status"] = "copied"
            case "dangling": attempts[0]["receiptID"] = UUID().uuidString
            default: attempts = Array(repeating: attempts[0], count: 101)
            }
            object["externalAttempts"] = attempts
            try JSONSerialization.data(withJSONObject: object).write(to: fixture.manifestURL)
            let snapshot = await MediaDeliveryStore(root: fixture.root).snapshot()
            XCTAssertEqual(snapshot.warning, .invalidManifest, corruption)
        }
    }

    func testAttemptNamesAreBoundedDistinctAndKeepJPEGExtension() {
        let name = String(repeating: "相機", count: 150) + ".JPEG?secret=discarded"
        let first = ForegroundFilesDelivery.filename(name, attemptID: UUID())
        let second = ForegroundFilesDelivery.filename(name, attemptID: UUID())
        XCTAssertLessThanOrEqual(first.utf8.count, 180)
        XCTAssertTrue(first.hasSuffix(".jpeg"))
        XCTAssertFalse(first.contains("secret"))
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(first, MediaBatchOwnedFile.safeFilename(first))
    }
}

private struct ForegroundFilesPreparedFixture {
    let ticket: MediaDeliveryTicket
    let lease: MediaDeliveryFileLease
}

@MainActor
private final class ForegroundFilesFixture {
    let parent: URL
    let root: URL
    let folder: URL
    let store: MediaDeliveryStore
    let attemptID = UUID()
    let bytes = Data([0xFF, 0xD8, 0xFF, 0xD9])
    var filename: String { ForegroundFilesDelivery.filename("SYNTHETIC.JPG", attemptID: attemptID) }
    var destination: URL { folder.appendingPathComponent(filename) }
    var manifestURL: URL { root.appendingPathComponent("manifest.json") }

    init(fault: @escaping MediaDeliveryStore.FaultInjector = { _ in }) throws {
        parent = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent("ForegroundFilesTests-\(UUID().uuidString)", isDirectory: true)
        root = parent.appendingPathComponent("History", isDirectory: true)
        folder = parent.appendingPathComponent("Selected", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        store = MediaDeliveryStore(root: root, faultInjector: fault)
    }
    func prepare() async throws -> ForegroundFilesPreparedFixture {
        let ticket = try await store.beginBatch()
        let id = try await store.beginItem(ticket, filename: "SYNTHETIC.JPG", expectedBytes: Int64(bytes.count))
        let source = try MediaBatchOwnedFile.create(filename: "SYNTHETIC.JPG")
        try bytes.write(to: source.fileURL)
        try await store.finishPrepared(ticket, receiptID: id, source: source, actualBytes: Int64(bytes.count))
        let leases = try await store.acquireShare(receiptIDs: [id])
        return ForegroundFilesPreparedFixture(ticket: ticket, lease: try XCTUnwrap(leases.first))
    }
    func deliver(_ delivery: ForegroundFilesDelivery, _ prepared: ForegroundFilesPreparedFixture) async -> ForegroundFilesDeliveryResult {
        await delivery.deliver(lease: prepared.lease, ticket: prepared.ticket, folder: folder,
            attemptID: attemptID, filename: filename, store: store)
    }
    func manifest() throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: manifestURL)) as? [String: Any])
    }
    func remove() { try? FileManager.default.removeItem(at: parent) }
}

final class ForegroundFilesTestCoordinator: ForegroundFilesCoordinating, @unchecked Sendable {
    private let body: @Sendable (URL, @escaping @Sendable (URL) -> Void) -> Bool
    private let lock = NSLock()
    private var cancelled = false
    var wasCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
    init(_ body: @escaping @Sendable (URL, @escaping @Sendable (URL) -> Void) -> Bool = { url, accessor in accessor(url); return true }) {
        self.body = body
    }
    func coordinate(_ url: URL, accessor: @escaping @Sendable (URL) -> Void) -> Bool { body(url, accessor) }
    func cancel() { lock.lock(); cancelled = true; lock.unlock() }
}

final class ForegroundFilesSpy: @unchecked Sendable {
    private let lock = NSLock()
    private var starts = 0
    private var stops = 0
    var scopeCounts: [Int] { lock.lock(); defer { lock.unlock() }; return [starts, stops] }
    func access(coordinator: any ForegroundFilesCoordinating = ForegroundFilesTestCoordinator(), allowed: Bool = true) -> ForegroundFilesAccess {
        ForegroundFilesAccess(startScope: { [self] _ in
            lock.lock(); starts += 1; lock.unlock(); return allowed
        }, stopScope: { [self] _ in
            lock.lock(); stops += 1; lock.unlock()
        }, coordinator: { coordinator })
    }
}

final class ForegroundFilesGate: @unchecked Sendable {
    private let lock = NSLock()
    private let semaphore = DispatchSemaphore(value: 0)
    private var hasEntered = false
    var entered: Bool { lock.lock(); defer { lock.unlock() }; return hasEntered }
    func enter() { lock.lock(); hasEntered = true; lock.unlock(); semaphore.wait() }
    func release() { semaphore.signal() }
}

private final class ForegroundFilesFault: @unchecked Sendable {
    private let lock = NSLock()
    private var active = false
    var enabled: Bool { lock.lock(); defer { lock.unlock() }; return active }
    func enable() { lock.lock(); active = true; lock.unlock() }
}

@MainActor
private func filesWait(_ gate: ForegroundFilesGate, task: Task<ForegroundFilesDeliveryResult, Never>) async throws {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(5))
    repeat {
        if gate.entered { return }
        await Task.yield()
    } while clock.now < deadline
    XCTFail("The controlled provider operation did not enter its gate")
    gate.release()
    _ = await task.value
    throw URLError(.timedOut)
}
