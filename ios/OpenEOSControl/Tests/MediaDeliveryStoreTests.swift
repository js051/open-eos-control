import Darwin
import Foundation
import XCTest

@testable import OpenEOSControl

@MainActor
final class MediaDeliveryStoreTests: XCTestCase {
    func testColdStartPreservesTerminalFactsAndProjectsInterruptedWorkUnknown() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let ticket = try await store.beginBatch()
        let pending = try await store.beginItem(ticket, filename: "PENDING.JPG")
        let ready = try await store.beginItem(ticket, filename: "../../READY.JPG")
        let failed = try await store.beginItem(ticket, filename: "FAILED.CR3")
        let cancelled = try await store.beginItem(ticket, filename: "CANCELLED.JPG")
        let source = try original(Data([1, 2, 3, 4]))
        try await store.finishPrepared(ticket, receiptID: ready, source: source, actualBytes: 4)
        try await store.finishFailure(ticket, receiptID: failed, cancelled: false)
        try await store.finishFailure(ticket, receiptID: cancelled, cancelled: true)

        let snapshot = await fixture.store().snapshot()
        XCTAssertNil(snapshot.warning)
        XCTAssertEqual(snapshot.records.map(\.id), [pending, ready, failed, cancelled])
        XCTAssertEqual(snapshot.records.map(\.status), [.unknown, .prepared, .failed, .cancelled])
        XCTAssertEqual(snapshot.records.map(\.availability), [.notRetained, .available, .notRetained, .notRetained])
        XCTAssertEqual(snapshot.records.map(\.reason), [.interrupted, nil, .downloadFailed, .cancelled])
        XCTAssertEqual(snapshot.records[1].filename, "READY.JPG")
        XCTAssertEqual(snapshot.records[1].actualBytes, 4)
        XCTAssertNotNil(snapshot.records[1].completedAt)
        XCTAssertEqual(snapshot.retainedBytes, 4)
        XCTAssertEqual(try Data(contentsOf: source.fileURL), Data([1, 2, 3, 4]))

        let json = try fixture.manifestObject()
        XCTAssertEqual(Set(json.keys), Set(["version", "epoch", "receipts", "tombstones"]))
        let records = try XCTUnwrap(json["receipts"] as? [[String: Any]])
        let allowed: Set<String> = ["id", "batchID", "filename", "startedAt", "completedAt", "status", "actualBytes", "reason", "identity"]
        XCTAssertTrue(records.allSatisfy { Set($0.keys).isSubset(of: allowed) })
        XCTAssertFalse(String(decoding: try Data(contentsOf: fixture.manifestURL), as: UTF8.self).contains(source.fileURL.path))
    }

    func testColdStartMissingAndChangedCopiesNeverErasePreparedFactOrBecomeShareable() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let ticket = try await store.beginBatch()
        let missing = try await prepare(store, ticket, name: "MISSING.JPG", bytes: Data([1, 2, 3]))
        let changed = try await prepare(store, ticket, name: "CHANGED.JPG", bytes: Data([4, 5, 6]))
        try FileManager.default.removeItem(at: fixture.file(missing, "MISSING.JPG"))
        try Data([9]).write(to: fixture.file(changed, "CHANGED.JPG"))
        let restarted = fixture.store()
        let snapshot = await restarted.snapshot()
        XCTAssertEqual(snapshot.records.map(\.status), [.prepared, .prepared])
        XCTAssertEqual(snapshot.records.map(\.availability), [.missing, .changed])
        XCTAssertEqual(snapshot.retainedBytes, 1)
        await expect(.invalidSource) { _ = try await restarted.acquireShare(receiptIDs: [missing]) }
        await expect(.invalidSource) { _ = try await restarted.acquireShare(receiptIDs: [changed]) }
    }

    func testMalformedOversizedUnknownAndUnexpectedSchemaPreserveOriginalManifest() async throws {
        let cases: [(Data, MediaDeliveryStoreError)] = [
            (Data("{broken".utf8), .invalidManifest),
            (Data(repeating: 32, count: 256 * 1024 + 1), .invalidManifest),
            (Data("{\"version\":99}".utf8), .unsupportedSchema),
            (Data("{\"version\":1,\"epoch\":\"00000000-0000-0000-0000-000000000001\",\"receipts\":[],\"tombstones\":[],\"rawError\":\"do not accept\"}".utf8), .invalidManifest)
        ]
        for (data, expected) in cases {
            let fixture = try DeliveryFixture()
            defer { fixture.remove() }
            try FileManager.default.createDirectory(at: fixture.root, withIntermediateDirectories: false)
            try data.write(to: fixture.manifestURL)
            let store = fixture.store()
            let snapshot = await store.snapshot()
            XCTAssertEqual(snapshot.warning, expected)
            XCTAssertTrue(snapshot.records.isEmpty)
            await expect(expected) { try await store.clear() }
            XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), data)
        }
    }

    func testForeignManifestFieldsAndUnsafeFilenamesFailClosed() async throws {
        for field in ["filename", "id", "status", "rawError", "startedAt"] {
            let fixture = try DeliveryFixture()
            defer { fixture.remove() }
            let store = fixture.store()
            let ticket = try await store.beginBatch()
            _ = try await store.beginItem(ticket, filename: "SAFE.JPG")
            var json = try fixture.manifestObject()
            var records = try XCTUnwrap(json["receipts"] as? [[String: Any]])
            if field == "startedAt" {
                records[0][field] = 1e300 // Finite, but unsafe to pass to date formatting.
            } else {
                records[0][field] = field == "filename" ? "../OUTSIDE.JPG" : "unsupported"
            }
            json["receipts"] = records
            let malformed = try JSONSerialization.data(withJSONObject: json)
            try malformed.write(to: fixture.manifestURL)
            let restarted = fixture.store()
            let snapshot = await restarted.snapshot()
            XCTAssertEqual(snapshot.warning, .invalidManifest)
            await expect(.invalidManifest) { try await restarted.clear() }
            XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), malformed)
        }
    }

    func testManifestChangedWhileOpenCannotBeOverwrittenByExistingActor() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let ticket = try await store.beginBatch()
        let receipt = try await store.beginItem(ticket, filename: "PENDING.JPG")
        let evidence = Data("{unexpected change".utf8)
        try evidence.write(to: fixture.manifestURL)
        await expect(.invalidManifest) { try await store.finishFailure(ticket, receiptID: receipt, cancelled: false) }
        await expect(.invalidManifest) { try await store.clear() }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), evidence)
    }

    func testRecordCapRefusesNewReceiptWithoutEvictingExistingCopy() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store(records: 2, bytes: 20)
        let ticket = try await store.beginBatch()
        let retained = try await prepare(store, ticket, name: "KEEP.JPG", bytes: Data([1, 2]))
        _ = try await store.beginItem(ticket, filename: "PENDING.CR3")
        await expect(.recordLimit) { _ = try await store.beginItem(ticket, filename: "EXCESS.JPG") }
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.count, 2)
        XCTAssertEqual(snapshot.records.first?.id, retained)
        XCTAssertEqual(snapshot.records.first?.availability, .available)
        XCTAssertEqual(try Data(contentsOf: fixture.file(retained, "KEEP.JPG")), Data([1, 2]))
    }

    func testByteCapRecordsPreparedButUnretainedAndPreservesCurrentOriginal() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store(bytes: 6)
        let ticket = try await store.beginBatch()
        let retained = try await prepare(store, ticket, name: "KEEP.JPG", bytes: Data([1, 2, 3, 4]))
        let unretained = try await store.beginItem(ticket, filename: "NEW.CR3")
        let source = try original(Data([5, 6, 7, 8]))
        await expect(.byteLimit) {
            try await store.finishPrepared(ticket, receiptID: unretained, source: source, actualBytes: 4)
        }
        let snapshot = await fixture.store(bytes: 6).snapshot()
        XCTAssertEqual(snapshot.records.map(\.status), [.prepared, .prepared])
        XCTAssertEqual(snapshot.records.map(\.availability), [.available, .notRetained])
        XCTAssertEqual(snapshot.records.last?.reason, .retentionUnavailable)
        XCTAssertEqual(snapshot.records.last?.actualBytes, 4)
        XCTAssertEqual(snapshot.retainedBytes, 4)
        XCTAssertEqual(try Data(contentsOf: source.fileURL), Data([5, 6, 7, 8]))
        XCTAssertEqual(try Data(contentsOf: fixture.file(retained, "KEEP.JPG")), Data([1, 2, 3, 4]))
    }

    func testOriginalLargerThanProductionCopyCapStillKeepsPreparedHistory() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        // A sparse synthetic source exercises the boundary without allocating
        // or copying hundreds of MiB. The existing transport temp is excluded.
        let store = fixture.store(bytes: Int64.max)
        let ticket = try await store.beginBatch()
        let receipt = try await store.beginItem(ticket, filename: "LARGE.CR3")
        let source = try original(Data([1]))
        let bytes: Int64 = 512 * 1024 * 1024 + 1
        let handle = try FileHandle(forWritingTo: source.fileURL)
        try handle.truncate(atOffset: UInt64(bytes))
        try handle.close()
        await expect(.byteLimit) { try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: bytes) }
        let snapshot = await fixture.store().snapshot()
        XCTAssertEqual(snapshot.records.first?.status, .prepared)
        XCTAssertEqual(snapshot.records.first?.actualBytes, bytes)
        XCTAssertEqual(snapshot.records.first?.availability, .notRetained)
        XCTAssertEqual(snapshot.retainedBytes, 0)
        let attributes = try FileManager.default.attributesOfItem(atPath: source.fileURL.path)
        XCTAssertEqual((attributes[.size] as? NSNumber)?.int64Value, bytes)
    }

    func testPreManifestOrphanCountsTowardLimitButIsNeverAdoptedOrErasedByClear() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store(bytes: 6)
        let ticket = try await store.beginBatch()
        let orphan = UUID()
        try FileManager.default.createDirectory(at: fixture.root.appendingPathComponent(orphan.uuidString), withIntermediateDirectories: false)
        let orphanURL = fixture.file(orphan, "ORPHAN.JPG")
        try Data([1, 2, 3, 4, 5]).write(to: orphanURL)
        let before = await store.snapshot()
        XCTAssertTrue(before.records.isEmpty)
        XCTAssertEqual(before.retainedBytes, 5)
        await expect(.invalidSource) { _ = try await store.acquireShare(receiptIDs: [orphan]) }
        let receipt = try await store.beginItem(ticket, filename: "NEW.JPG")
        let source = try original(Data([6, 7]))
        await expect(.byteLimit) { try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: 2) }
        try await store.clear()
        let after = await store.snapshot()
        XCTAssertTrue(after.records.isEmpty)
        XCTAssertEqual(after.retainedBytes, 5)
        XCTAssertEqual(try Data(contentsOf: orphanURL), Data([1, 2, 3, 4, 5]))
    }

    func testChunkFailureRemovesOnlyNewPartialCopyAndRecordsRetentionWarning() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let faults = DeliveryFaults()
        let store = fixture.store(faults: faults)
        let ticket = try await store.beginBatch()
        let receipt = try await store.beginItem(ticket, filename: "PARTIAL.CR3")
        let data = Data(repeating: 47, count: 150_000)
        let source = try original(data)
        faults.arm(.copyChunk, after: 1)
        await expect(.storageUnavailable) { try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: Int64(data.count)) }
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.first?.status, .prepared)
        XCTAssertEqual(snapshot.records.first?.reason, .retentionUnavailable)
        XCTAssertEqual(snapshot.records.first?.availability, .notRetained)
        XCTAssertEqual(snapshot.retainedBytes, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.root.appendingPathComponent(receipt.uuidString).path))
        XCTAssertEqual(try Data(contentsOf: source.fileURL), data)
    }

    func testSourceLengthChangeDuringCopyRejectsRetainedCopy() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let faults = DeliveryFaults()
        let store = fixture.store(faults: faults)
        let ticket = try await store.beginBatch()
        let receipt = try await store.beginItem(ticket, filename: "CHANGING.JPG")
        let data = Data(repeating: 7, count: 150_000)
        let source = try original(data)
        faults.arm(.copyChunk, after: 1, action: {
            let handle = try FileHandle(forWritingTo: source.fileURL)
            try handle.truncate(atOffset: 1)
            try handle.close()
        })
        await expect(.invalidSource) { try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: Int64(data.count)) }
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.first?.availability, .notRetained)
        XCTAssertEqual(snapshot.retainedBytes, 0)
        XCTAssertEqual(try Data(contentsOf: source.fileURL).count, 1)
    }

    func testCancelledRetentionPreservesOriginalAndPersistsPreparedUnavailable() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let ticket = try await store.beginBatch()
        let receipt = try await store.beginItem(ticket, filename: "CANCELLED-COPY.JPG")
        let source = try original(Data([1, 2, 3]))
        let task = Task { () -> MediaDeliveryStoreError? in
            withUnsafeCurrentTask { $0?.cancel() }
            do {
                try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: 3)
                return nil
            } catch { return error as? MediaDeliveryStoreError }
        }
        let error = await task.value
        XCTAssertEqual(error, .cancelled)
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.first?.status, .prepared)
        XCTAssertEqual(snapshot.records.first?.availability, .notRetained)
        XCTAssertEqual(try Data(contentsOf: source.fileURL), Data([1, 2, 3]))
    }

    func testFailedAtomicReplacementPreservesPreviousManifestAndOriginal() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let faults = DeliveryFaults()
        let store = fixture.store(faults: faults)
        let ticket = try await store.beginBatch()
        let receipt = try await store.beginItem(ticket, filename: "ATOMIC.JPG")
        let previous = try Data(contentsOf: fixture.manifestURL)
        let source = try original(Data([1, 2, 3]))
        faults.arm(.beforeManifestReplace, repeatFailure: true)
        await expect(.storageUnavailable) { try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: 3) }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), previous)
        XCTAssertEqual(try Data(contentsOf: source.fileURL), Data([1, 2, 3]))
        let restarted = await fixture.store().snapshot()
        XCTAssertEqual(restarted.records.first?.status, .unknown)
        XCTAssertEqual(restarted.retainedBytes, 0)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: fixture.root.path), ["manifest.json"])
    }

    func testFailedClearCommitNeitherInvalidatesTicketNorDeletesExistingCopy() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let faults = DeliveryFaults()
        let store = fixture.store(faults: faults)
        let ticket = try await store.beginBatch()
        let retained = try await prepare(store, ticket, name: "KEEP.JPG", bytes: Data([1, 2]))
        let pending = try await store.beginItem(ticket, filename: "PENDING.JPG")
        let previous = try Data(contentsOf: fixture.manifestURL)
        faults.arm(.beforeManifestReplace)
        await expect(.storageUnavailable) { try await store.clear() }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), previous)
        XCTAssertEqual(try Data(contentsOf: fixture.file(retained, "KEEP.JPG")), Data([1, 2]))
        try await store.finishFailure(ticket, receiptID: pending, cancelled: true)
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.last?.status, .cancelled)
    }

    func testClearInvalidatesOldTicketAndPinsSharedCopyUntilEveryLeaseEnds() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let ticket = try await store.beginBatch()
        let source = try original(Data([1, 2, 3]))
        let receipt = try await store.beginItem(ticket, filename: "SHARED.JPG")
        try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: 3)
        var first: [MediaDeliveryFileLease]? = try await store.acquireShare(receiptIDs: [receipt])
        var second: [MediaDeliveryFileLease]? = try await store.acquireShare(receiptIDs: [receipt])
        let retainedURL = try XCTUnwrap(first?.first?.fileURL)
        XCTAssertEqual(second?.first?.fileURL, retainedURL)
        XCTAssertNotEqual(retainedURL, source.fileURL)
        try await store.clear()
        await expect(.invalidTicket) { _ = try await store.beginItem(ticket, filename: "STALE.JPG") }
        await expect(.invalidTicket) { try await store.finishFailure(ticket, receiptID: receipt, cancelled: false) }
        var snapshot = await store.snapshot()
        XCTAssertTrue(snapshot.records.isEmpty)
        XCTAssertEqual(snapshot.retainedBytes, 3)
        XCTAssertEqual(try Data(contentsOf: retainedURL), Data([1, 2, 3]))
        first = nil
        // A second immutable lease must independently keep the copy alive.
        XCTAssertEqual(second?.first?.fileURL, retainedURL)
        XCTAssertEqual(try Data(contentsOf: retainedURL), Data([1, 2, 3]))
        second = nil
        try await waitUntil { !FileManager.default.fileExists(atPath: retainedURL.path) }
        snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.retainedBytes, 0)
        XCTAssertEqual(try Data(contentsOf: source.fileURL), Data([1, 2, 3]), "Clearing retained history cannot destroy the current batch's original")
        let next = try await store.beginBatch()
        _ = try await store.beginItem(next, filename: "NEXT.JPG")
    }

    func testDeleteFailureRetainsTombstoneAndCountsItsBytesUntilExplicitRetry() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let faults = DeliveryFaults()
        let store = fixture.store(bytes: 4, faults: faults)
        let ticket = try await store.beginBatch()
        let receipt = try await prepare(store, ticket, name: "TOMBSTONE.JPG", bytes: Data([1, 2, 3, 4]))
        faults.arm(.beforeOwnedDelete, repeatFailure: true)
        await expect(.storageUnavailable) { try await store.clear() }
        let snapshot = await fixture.store(bytes: 4).snapshot()
        XCTAssertTrue(snapshot.records.isEmpty)
        XCTAssertEqual(snapshot.retainedBytes, 4)
        XCTAssertTrue(FileManager.default.fileExists(atPath: fixture.file(receipt, "TOMBSTONE.JPG").path))
        let next = try await store.beginBatch()
        let nextID = try await store.beginItem(next, filename: "FULL.JPG")
        let source = try original(Data([9]))
        await expect(.byteLimit) { try await store.finishPrepared(next, receiptID: nextID, source: source, actualBytes: 1) }
        faults.disarm()
        try await store.clear()
        let cleaned = await store.snapshot()
        XCTAssertEqual(cleaned.retainedBytes, 0)
    }

    func testClearRetriesOwnedEmptyDirectoryAfterFileWasDeleted() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let faults = DeliveryFaults()
        let store = fixture.store(faults: faults)
        let ticket = try await store.beginBatch()
        let receipt = try await prepare(store, ticket, name: "RETRY.JPG", bytes: Data([1, 2, 3]))
        faults.arm(.beforeOwnedDirectoryDelete)
        await expect(.storageUnavailable) { try await store.clear() }
        let directory = fixture.root.appendingPathComponent(receipt.uuidString)
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.path))
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: directory.path).isEmpty)
        try await store.clear()
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
        let json = try fixture.manifestObject()
        XCTAssertTrue(try XCTUnwrap(json["tombstones"] as? [[String: Any]]).isEmpty)
    }

    func testColdStartSameLengthReplacementAndInPlaceEditAreUnavailableAndPreservedByClear() async throws {
        for replace in [false, true] {
            let fixture = try DeliveryFixture()
            defer { fixture.remove() }
            let store = fixture.store()
            let ticket = try await store.beginBatch()
            let receipt = try await prepare(store, ticket, name: "OWNED.JPG", bytes: Data([1, 2, 3]))
            let retained = fixture.file(receipt, "OWNED.JPG")
            let foreign = Data([8, 8, 8])
            if replace {
                let replacement = fixture.parent.appendingPathComponent("replacement.JPG")
                try foreign.write(to: replacement)
                try FileManager.default.removeItem(at: retained)
                try FileManager.default.moveItem(at: replacement, to: retained)
            } else {
                let handle = try FileHandle(forWritingTo: retained)
                try handle.write(contentsOf: foreign)
                try handle.close()
                // Force an unmistakably changed timestamp even on a filesystem
                // whose write timestamp resolution is coarser than this test.
                try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 1_000_000)], atPath: retained.path)
            }
            let restarted = fixture.store()
            let snapshot = await restarted.snapshot()
            XCTAssertEqual(snapshot.records.first?.status, .prepared)
            XCTAssertEqual(snapshot.records.first?.availability, .changed)
            await expect(.invalidSource) { _ = try await restarted.acquireShare(receiptIDs: [receipt]) }
            await expect(.unsafeFilesystem) { try await restarted.clear() }
            XCTAssertEqual(try Data(contentsOf: retained), foreign)
            let cleared = await restarted.snapshot()
            XCTAssertTrue(cleared.records.isEmpty)
            XCTAssertEqual(cleared.retainedBytes, 3, "Unowned replacements stay on disk and count toward the cap")
        }
    }

    func testPartialCopyCleanupPreservesReplacedOutputAndOriginal() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let faults = DeliveryFaults()
        let store = fixture.store(faults: faults)
        let ticket = try await store.beginBatch()
        let receipt = try await store.beginItem(ticket, filename: "PARTIAL.JPG")
        let sourceBytes = Data(repeating: 1, count: 150_000)
        let source = try original(sourceBytes)
        let output = fixture.file(receipt, "PARTIAL.JPG")
        let moved = fixture.parent.appendingPathComponent("moved-partial.JPG")
        let replacement = Data(repeating: 9, count: 64 * 1024)
        faults.arm(.copyChunk, after: 1, action: {
            try FileManager.default.moveItem(at: output, to: moved)
            try replacement.write(to: output)
            throw MediaDeliveryStoreError.storageUnavailable
        })
        await expect(.storageUnavailable) {
            try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: Int64(sourceBytes.count))
        }
        XCTAssertEqual(try Data(contentsOf: output), replacement)
        XCTAssertEqual(try Data(contentsOf: moved), sourceBytes.prefix(64 * 1024))
        XCTAssertEqual(try Data(contentsOf: source.fileURL), sourceBytes)
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.first?.availability, .notRetained)
        XCTAssertEqual(snapshot.retainedBytes, Int64(replacement.count))
    }

    func testManifestFailureCleanupPreservesSameLengthReplacement() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let faults = DeliveryFaults()
        let store = fixture.store(faults: faults)
        let ticket = try await store.beginBatch()
        let receipt = try await store.beginItem(ticket, filename: "REPLACED.JPG")
        let source = try original(Data([1, 2, 3]))
        let output = fixture.file(receipt, "REPLACED.JPG")
        let moved = fixture.parent.appendingPathComponent("moved-complete.JPG")
        faults.arm(.beforeManifestReplace, action: {
            try FileManager.default.moveItem(at: output, to: moved)
            try Data([9, 9, 9]).write(to: output)
            throw MediaDeliveryStoreError.storageUnavailable
        })
        await expect(.storageUnavailable) { try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: 3) }
        XCTAssertEqual(try Data(contentsOf: output), Data([9, 9, 9]))
        XCTAssertEqual(try Data(contentsOf: moved), Data([1, 2, 3]))
        XCTAssertEqual(try Data(contentsOf: source.fileURL), Data([1, 2, 3]))
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.first?.availability, .notRetained)
        XCTAssertEqual(snapshot.retainedBytes, 3)
    }

    func testLastLeaseReleasePreservesCopiesWhenManifestWasReplaced() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        var store: MediaDeliveryStore? = fixture.store()
        weak var witness = store
        let ticket = try await store!.beginBatch()
        let receipt = try await prepare(store!, ticket, name: "PINNED.JPG", bytes: Data([1, 2, 3]))
        var leases: [MediaDeliveryFileLease]? = try await store!.acquireShare(receiptIDs: [receipt])
        let retained = try XCTUnwrap(leases?.first?.fileURL)
        try await store!.clear()
        let replacement = Data("{newer or malformed evidence".utf8)
        try replacement.write(to: fixture.manifestURL)
        store = nil
        leases = nil
        // The lease's task is the remaining owner. Waiting for actor destruction
        // proves its deferred cleanup finished, without an arbitrary sleep.
        try await waitUntil { witness == nil }
        XCTAssertEqual(try Data(contentsOf: retained), Data([1, 2, 3]))
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), replacement)
    }

    func testFIFOManifestAndSourceAreRejectedWithoutBlocking() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        try FileManager.default.createDirectory(at: fixture.root, withIntermediateDirectories: false)
        XCTAssertEqual(Darwin.mkfifo(fixture.manifestURL.path, mode_t(S_IRUSR | S_IWUSR)), 0)
        let blocked = fixture.store()
        let manifestResult = await blocked.snapshot()
        XCTAssertEqual(manifestResult.warning, .invalidSource)
        try FileManager.default.removeItem(at: fixture.manifestURL)
        let store = fixture.store()
        let ticket = try await store.beginBatch()
        let receipt = try await store.beginItem(ticket, filename: "PIPE.JPG")
        let source = try MediaBatchOwnedFile.create(filename: "PIPE.JPG")
        XCTAssertEqual(Darwin.mkfifo(source.fileURL.path, mode_t(S_IRUSR | S_IWUSR)), 0)
        await expect(.invalidSource) { try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: 1) }
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.first?.status, .started)
        XCTAssertEqual(snapshot.retainedBytes, 0)
    }

    func testSymlinkRetainedFileAndDirectoryArePreservedAndNeverSharedOrDeleted() async throws {
        for linkDirectory in [false, true] {
            let fixture = try DeliveryFixture()
            defer { fixture.remove() }
            let store = fixture.store()
            let ticket = try await store.beginBatch()
            let receipt = try await prepare(store, ticket, name: "SAFE.JPG", bytes: Data([1, 2, 3]))
            let outside = fixture.parent.appendingPathComponent("unrelated", isDirectory: true)
            try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: false)
            let sentinel = outside.appendingPathComponent("SAFE.JPG")
            try Data([8, 8, 8]).write(to: sentinel)
            let destination = linkDirectory ? fixture.root.appendingPathComponent(receipt.uuidString) : fixture.file(receipt, "SAFE.JPG")
            try FileManager.default.removeItem(at: destination)
            try FileManager.default.createSymbolicLink(at: destination, withDestinationURL: linkDirectory ? outside : sentinel)
            let manifest = try Data(contentsOf: fixture.manifestURL)
            let restarted = fixture.store()
            let snapshot = await restarted.snapshot()
            XCTAssertEqual(snapshot.warning, .unsafeFilesystem)
            XCTAssertEqual(snapshot.records.first?.status, .prepared)
            XCTAssertEqual(snapshot.records.first?.availability, .unsafe)
            await expect(.unsafeFilesystem) { _ = try await restarted.acquireShare(receiptIDs: [receipt]) }
            await expect(.unsafeFilesystem) { try await restarted.clear() }
            XCTAssertEqual(try Data(contentsOf: sentinel), Data([8, 8, 8]))
            XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), manifest)
            XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath: destination.path), (linkDirectory ? outside : sentinel).path)
        }
    }

    func testSymlinkRootAndManifestFailClosedWithoutReadingOrOverwritingTarget() async throws {
        for linkRoot in [false, true] {
            let fixture = try DeliveryFixture()
            defer { fixture.remove() }
            let target = fixture.parent.appendingPathComponent("unrelated-target")
            let data = Data("unrelated evidence".utf8)
            if linkRoot {
                try FileManager.default.createDirectory(at: target, withIntermediateDirectories: false)
                try data.write(to: target.appendingPathComponent("manifest.json"))
                try FileManager.default.createSymbolicLink(at: fixture.root, withDestinationURL: target)
            } else {
                try FileManager.default.createDirectory(at: fixture.root, withIntermediateDirectories: false)
                try data.write(to: target)
                try FileManager.default.createSymbolicLink(at: fixture.manifestURL, withDestinationURL: target)
            }
            let store = fixture.store()
            let snapshot = await store.snapshot()
            XCTAssertEqual(snapshot.warning, .unsafeFilesystem)
            await expect(.unsafeFilesystem) { try await store.clear() }
            XCTAssertEqual(try Data(contentsOf: linkRoot ? target.appendingPathComponent("manifest.json") : target), data)
        }
    }

    func testUnrelatedRootFileBlocksMutationAndIsPreservedWithManifest() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let ticket = try await store.beginBatch()
        _ = try await store.beginItem(ticket, filename: "PENDING.JPG")
        let manifest = try Data(contentsOf: fixture.manifestURL)
        let foreign = fixture.root.appendingPathComponent("unrelated.txt")
        try Data("must keep".utf8).write(to: foreign)
        await expect(.unsafeFilesystem) { try await store.clear() }
        await expect(.unsafeFilesystem) { _ = try await store.beginItem(ticket, filename: "BLOCKED.JPG") }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), manifest)
        XCTAssertEqual(try Data(contentsOf: foreign), Data("must keep".utf8))
    }

    func testSourceSymlinkAndIncorrectLengthAreRejectedWithoutTouchingTarget() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let ticket = try await store.beginBatch()
        let source = try original(Data([1, 2, 3]))
        let receipt = try await store.beginItem(ticket, filename: "BAD.JPG")
        await expect(.invalidSource) { try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: 2) }
        let target = fixture.parent.appendingPathComponent("unrelated.bin")
        try Data([8, 8, 8]).write(to: target)
        try FileManager.default.removeItem(at: source.fileURL)
        try FileManager.default.createSymbolicLink(at: source.fileURL, withDestinationURL: target)
        await expect(.invalidSource) { try await store.finishPrepared(ticket, receiptID: receipt, source: source, actualBytes: 3) }
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.first?.status, .started)
        XCTAssertEqual(snapshot.retainedBytes, 0)
        XCTAssertEqual(try Data(contentsOf: target), Data([8, 8, 8]))
    }

    func testShareRequiresCompleteValidUniqueReceiptSetWithoutPartiallyPinning() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let ticket = try await store.beginBatch()
        let ready = try await prepare(store, ticket, name: "READY.JPG", bytes: Data([1, 2]))
        let pending = try await store.beginItem(ticket, filename: "PENDING.JPG")
        await expect(.invalidSource) { _ = try await store.acquireShare(receiptIDs: [ready, pending]) }
        await expect(.invalidReceipt) { _ = try await store.acquireShare(receiptIDs: [ready, ready]) }
        await expect(.invalidReceipt) { _ = try await store.acquireShare(receiptIDs: []) }
        try await store.clear()
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.file(ready, "READY.JPG").path))
    }

    private func original(_ bytes: Data) throws -> MediaBatchOwnedFile {
        let file = try MediaBatchOwnedFile.create(filename: "SYNTHETIC-ORIGINAL.JPG")
        try bytes.write(to: file.fileURL)
        return file
    }

    private func prepare(_ store: MediaDeliveryStore, _ ticket: MediaDeliveryTicket,
                         name: String, bytes: Data) async throws -> UUID {
        let id = try await store.beginItem(ticket, filename: name)
        let source = try original(bytes)
        try await store.finishPrepared(ticket, receiptID: id, source: source, actualBytes: Int64(bytes.count))
        return id
    }

    private func expect(_ expected: MediaDeliveryStoreError,
                        operation: () async throws -> Void, file: StaticString = #filePath, line: UInt = #line) async {
        do {
            try await operation()
            XCTFail("Expected a bounded store failure", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? MediaDeliveryStoreError, expected, file: file, line: line)
        }
    }

    private func waitUntil(_ condition: () -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(3))
        while !condition() {
            guard clock.now < deadline else { XCTFail("Expected owned lease cleanup"); throw URLError(.timedOut) }
            await Task.yield()
        }
    }
}

/// Every root and file is synthetic and isolated; production Application Support
/// is never opened by these tests. Swift/Xcode execution is required on macOS.
private struct DeliveryFixture {
    let parent: URL
    let root: URL
    var manifestURL: URL { root.appendingPathComponent("manifest.json") }

    init() throws {
        parent = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent("MediaDeliveryStoreTests-\(UUID().uuidString)", isDirectory: true)
        root = parent.appendingPathComponent("Store", isDirectory: true)
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: false)
    }

    func store(records: Int = 100, bytes: Int64 = 512 * 1024 * 1024, faults: DeliveryFaults? = nil) -> MediaDeliveryStore {
        MediaDeliveryStore(root: root, limits: MediaDeliveryLimits(maximumRecords: records, maximumRetainedBytes: bytes),
            faultInjector: { try faults?.hit($0) })
    }

    func file(_ id: UUID, _ name: String) -> URL { root.appendingPathComponent(id.uuidString).appendingPathComponent(name) }
    func manifestObject() throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: manifestURL)) as? [String: Any])
    }
    func remove() { try? FileManager.default.removeItem(at: parent) }
}

/// A narrow deterministic fault seam for chunk writes, atomic manifest commit,
/// and owned deletion. It cannot redirect a production filesystem operation.
private final class DeliveryFaults: @unchecked Sendable {
    private let lock = NSLock()
    private var point: MediaDeliveryStore.FaultPoint?
    private var remaining = 0
    private var repeats = false
    private var action: (@Sendable () throws -> Void)?

    func arm(_ point: MediaDeliveryStore.FaultPoint, after: Int = 0, repeatFailure: Bool = false,
             action: (@Sendable () throws -> Void)? = nil) {
        lock.lock()
        defer { lock.unlock() }
        self.point = point
        remaining = after
        repeats = repeatFailure
        self.action = action
    }

    func disarm() {
        lock.lock()
        defer { lock.unlock() }
        point = nil
        action = nil
    }

    func hit(_ current: MediaDeliveryStore.FaultPoint) throws {
        lock.lock()
        guard point == current else { lock.unlock(); return }
        if remaining > 0 { remaining -= 1; lock.unlock(); return }
        let run = action
        if !repeats { point = nil; action = nil }
        lock.unlock()
        if let run { try run() } else { throw MediaDeliveryStoreError.storageUnavailable }
    }
}
