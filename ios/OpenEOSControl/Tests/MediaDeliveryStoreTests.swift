import Darwin
import Foundation
import XCTest

@testable import OpenEOSControl

@MainActor
final class MediaDeliveryStoreTests: XCTestCase {
    // Continuation execution status: NOT_RUN; Swift/Xcode on macOS is required.
    // Fixture reconstruction is not physical-camera evidence.
    func testColdReopenContinuesChosenSubsetOfFiftyRowsWithoutChangingOriginalFacts() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let names = (0..<50).map { "ORIGINAL-\($0).JPG" }
        let originalBatch = try await store.beginPlannedBatch(filenames: names)
        for id in originalBatch.rowIDs.prefix(5) {
            _ = try await store.beginPlannedItem(originalBatch.ticket, rowID: id)
            let source = try original(Data([1, 2]))
            try await store.finishPrepared(originalBatch.ticket, receiptID: id, source: source, actualBytes: 2)
        }
        for offset in [5, 6, 7] {
            let id = originalBatch.rowIDs[offset]
            _ = try await store.beginPlannedItem(originalBatch.ticket, rowID: id)
            if offset != 5 { try await store.finishFailure(originalBatch.ticket, receiptID: id, cancelled: offset == 7) }
        }
        let saved = try fixture.manifestObject()
        let bytesBeforeReopen = try Data(contentsOf: fixture.manifestURL)
        let reopened = fixture.store()
        let before = await reopened.snapshot()
        let epoch = try XCTUnwrap(before.epoch)
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), bytesBeforeReopen)
        let originalRows = try XCTUnwrap(before.plannedBatches.first?.rows)
        XCTAssertEqual(originalRows.map(\.canContinue), Array(repeating: false, count: 5) + Array(repeating: true, count: 45))
        XCTAssertEqual(originalRows[5].receipt?.status, .unknown)
        let offsets = [49, 5, 7, 6, 23]
        let origins = offsets.map { MediaDeliveryOrigin(batchID: originalBatch.ticket.batchID, rowID: originalBatch.rowIDs[$0]) }
        let continuation = try await reopened.beginContinuationBatch(origins: origins,
            filenames: offsets.map { "CURRENT-\($0).JPG" }, expectedEpoch: epoch, acknowledgedUnknown: true)
        XCTAssertNotEqual(continuation.ticket.batchID, originalBatch.ticket.batchID)
        XCTAssertTrue(Set(continuation.rowIDs).isDisjoint(with: originalBatch.rowIDs))
        let first = continuation.rowIDs[0]
        _ = try await reopened.beginPlannedItem(continuation.ticket, rowID: first)
        try await reopened.validatePlannedDownload(continuation.ticket, rowID: first)
        let source = try original(Data([3, 4, 5]))
        try await reopened.finishPrepared(continuation.ticket, receiptID: first, source: source, actualBytes: 3)
        let restored = await fixture.store().snapshot()
        XCTAssertNil(restored.warning)
        XCTAssertEqual(restored.epoch, epoch)
        XCTAssertEqual(restored.plannedBatches.map(\.id), [originalBatch.ticket.batchID, continuation.ticket.batchID])
        let kept = restored.plannedBatches[0].rows
        XCTAssertEqual(kept.count, 50)
        XCTAssertEqual(kept.map(\.id), originalRows.map(\.id))
        XCTAssertEqual(kept.map(\.filename), names)
        XCTAssertEqual(kept.map { $0.receipt?.status }, originalRows.map { $0.receipt?.status })
        XCTAssertEqual(kept.map { $0.receipt?.startedAt }, originalRows.map { $0.receipt?.startedAt })
        XCTAssertEqual(kept.map { $0.receipt?.completedAt }, originalRows.map { $0.receipt?.completedAt })
        XCTAssertTrue(kept.allSatisfy { $0.origin == nil })
        let linked = restored.plannedBatches[1].rows
        XCTAssertEqual(linked.map(\.id), continuation.rowIDs)
        XCTAssertEqual(linked.compactMap(\.origin), origins)
        XCTAssertEqual(linked.map(\.filename), offsets.map { "CURRENT-\($0).JPG" })
        XCTAssertEqual(linked.first?.receipt?.status, .prepared)
        XCTAssertTrue(linked.dropFirst().allSatisfy { $0.receipt == nil })
        XCTAssertEqual(restored.retainedBytes, 13)
        let after = try fixture.manifestObject()
        XCTAssertEqual(after["version"] as? Int, 3)
        let originalSavedBatch = try XCTUnwrap((saved["plannedBatches"] as? [[String: Any]])?.first)
        let originalAfterBatch = try XCTUnwrap((after["plannedBatches"] as? [[String: Any]])?.first)
        XCTAssertEqual(originalAfterBatch as NSDictionary, originalSavedBatch as NSDictionary)
        let savedLinked = try XCTUnwrap((after["plannedBatches"] as? [[String: Any]])?.last?["rows"] as? [[String: Any]])
        XCTAssertTrue(savedLinked.allSatisfy { Set($0.keys) == Set(["id", "filename", "origin"]) })
        XCTAssertTrue(savedLinked.allSatisfy { ($0["origin"] as? [String: Any]).map { Set($0.keys) == Set(["batchID", "rowID"]) } == true })
    }

    func testContinuationRequiresUnknownAcknowledgementAndPreservesFailedAdmissionBytes() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: ["UNKNOWN.JPG"])
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
        let reopened = fixture.store()
        let snapshot = await reopened.snapshot()
        let epoch = try XCTUnwrap(snapshot.epoch)
        let origin = MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: batch.rowIDs[0])
        let previous = try Data(contentsOf: fixture.manifestURL)
        await expect(.invalidReceipt) {
            _ = try await reopened.beginContinuationBatch(origins: [origin], filenames: ["CHOSEN.JPG"], expectedEpoch: epoch, acknowledgedUnknown: false)
        }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), previous)
        let retry = try await reopened.beginContinuationBatch(origins: [origin], filenames: ["CHOSEN.JPG"], expectedEpoch: epoch, acknowledgedUnknown: true)
        let after = await fixture.store().snapshot()
        XCTAssertEqual(after.plannedBatches[0].rows[0].receipt?.status, .unknown)
        XCTAssertEqual(after.plannedBatches[1].rows[0].origin, origin)
        XCTAssertEqual(after.plannedBatches[1].rows[0].id, retry.rowIDs[0])
        XCTAssertNil(after.plannedBatches[1].rows[0].receipt)
    }

    func testContinuationRejectsInvalidDuplicateStartedAndPreparedOriginsIncludingMissingCopies() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: ["QUEUED.JPG", "STARTED.JPG", "PREPARED.JPG"])
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[1])
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[2])
        let source = try original(Data([1]))
        try await store.finishPrepared(batch.ticket, receiptID: batch.rowIDs[2], source: source, actualBytes: 1)
        try FileManager.default.removeItem(at: fixture.file(batch.rowIDs[2], "PREPARED.JPG"))
        let snapshot = await store.snapshot()
        let epoch = try XCTUnwrap(snapshot.epoch)
        XCTAssertEqual(snapshot.plannedBatches[0].rows.map(\.canContinue), [true, false, false])
        XCTAssertEqual(snapshot.plannedBatches[0].rows[2].receipt?.availability, .missing)
        let queued = MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: batch.rowIDs[0])
        let invalid = [
            MediaDeliveryOrigin(batchID: UUID(), rowID: queued.rowID),
            MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: UUID()),
            MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: batch.rowIDs[1]),
            MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: batch.rowIDs[2])
        ]
        let previous = try Data(contentsOf: fixture.manifestURL)
        for origin in invalid {
            await expect(.invalidReceipt) {
                _ = try await store.beginContinuationBatch(origins: [origin], filenames: ["CURRENT.JPG"], expectedEpoch: epoch, acknowledgedUnknown: true)
            }
        }
        for (origins, filenames) in [([queued, queued], ["A.JPG", "B.JPG"]), ([], []), ([queued], [])] {
            await expect(.invalidReceipt) {
                _ = try await store.beginContinuationBatch(origins: origins, filenames: filenames, expectedEpoch: epoch, acknowledgedUnknown: true)
            }
        }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), previous)
    }

    func testUnknownLinkedAttemptRequiresAcknowledgementWhenSelectingFailedAncestorOrQueuedSibling() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: ["FAILED.JPG"])
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
        try await store.finishFailure(batch.ticket, receiptID: batch.rowIDs[0], cancelled: false)
        let initial = await store.snapshot()
        let epoch = try XCTUnwrap(initial.epoch)
        let origin = MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: batch.rowIDs[0])
        let interrupted = try await store.beginContinuationBatch(origins: [origin], filenames: ["INTERRUPTED.JPG"],
            expectedEpoch: epoch, acknowledgedUnknown: false)
        let sibling = try await store.beginContinuationBatch(origins: [origin], filenames: ["QUEUED.JPG"],
            expectedEpoch: epoch, acknowledgedUnknown: false)
        _ = try await store.beginPlannedItem(interrupted.ticket, rowID: interrupted.rowIDs[0])
        let reopened = fixture.store()
        let projected = await reopened.snapshot()
        XCTAssertEqual(projected.plannedBatches.map { $0.rows[0].receipt?.status }, [.failed, .unknown, nil])
        XCTAssertTrue(projected.plannedBatches.flatMap(\.rows).allSatisfy(\.canContinue))
        XCTAssertTrue(projected.plannedBatches.flatMap(\.rows).allSatisfy(\.requiresUnknownAcknowledgement))
        let previous = try Data(contentsOf: fixture.manifestURL)
        let siblingOrigin = MediaDeliveryOrigin(batchID: sibling.ticket.batchID, rowID: sibling.rowIDs[0])
        for selected in [origin, siblingOrigin] {
            await expect(.invalidReceipt) {
                _ = try await reopened.beginContinuationBatch(origins: [selected], filenames: ["BLOCKED.JPG"],
                    expectedEpoch: epoch, acknowledgedUnknown: false)
            }
        }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), previous)
        _ = try await reopened.beginContinuationBatch(origins: [origin], filenames: ["CONFIRMED.JPG"],
            expectedEpoch: epoch, acknowledgedUnknown: true)
        let confirmed = await reopened.snapshot()
        XCTAssertEqual(confirmed.plannedBatches[0].rows[0].receipt?.status, .failed)
        XCTAssertEqual(confirmed.plannedBatches[1].rows[0].receipt?.status, .unknown)
        XCTAssertTrue(confirmed.plannedBatches[3].rows[0].requiresUnknownAcknowledgement)
    }

    func testActiveContinuationBlocksAncestorSiblingAndDescendantAdmissionUntilTerminal() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let originalBatch = try await store.beginPlannedBatch(filenames: ["ORIGINAL.JPG"])
        let initial = await store.snapshot()
        let epoch = try XCTUnwrap(initial.epoch)
        let origin = MediaDeliveryOrigin(batchID: originalBatch.ticket.batchID, rowID: originalBatch.rowIDs[0])
        let first = try await store.beginContinuationBatch(origins: [origin], filenames: ["FIRST.JPG"], expectedEpoch: epoch, acknowledgedUnknown: false)
        let sibling = try await store.beginContinuationBatch(origins: [origin], filenames: ["SIBLING.JPG"], expectedEpoch: epoch, acknowledgedUnknown: false)
        let firstOrigin = MediaDeliveryOrigin(batchID: first.ticket.batchID, rowID: first.rowIDs[0])
        let child = try await store.beginContinuationBatch(origins: [firstOrigin], filenames: ["CHILD.JPG"], expectedEpoch: epoch, acknowledgedUnknown: false)
        _ = try await store.beginPlannedItem(first.ticket, rowID: first.rowIDs[0])
        let before = try Data(contentsOf: fixture.manifestURL)
        for reference in [origin, firstOrigin, MediaDeliveryOrigin(batchID: sibling.ticket.batchID, rowID: sibling.rowIDs[0])] {
            await expect(.invalidReceipt) {
                _ = try await store.beginContinuationBatch(origins: [reference], filenames: ["BLOCKED.JPG"], expectedEpoch: epoch, acknowledgedUnknown: true)
            }
        }
        for candidate in [originalBatch, sibling, child] {
            await expect(.invalidReceipt) { _ = try await store.beginPlannedItem(candidate.ticket, rowID: candidate.rowIDs[0]) }
        }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), before)
        try await store.finishFailure(first.ticket, receiptID: first.rowIDs[0], cancelled: true)
        _ = try await store.beginPlannedItem(child.ticket, rowID: child.rowIDs[0])
        await expect(.invalidReceipt) { _ = try await store.beginPlannedItem(sibling.ticket, rowID: sibling.rowIDs[0]) }
        try await store.finishFailure(child.ticket, receiptID: child.rowIDs[0], cancelled: false)
        _ = try await store.beginPlannedItem(sibling.ticket, rowID: sibling.rowIDs[0])
    }

    func testOneContinuationCannotAdmitAncestorAndDescendantOrSiblingOriginsTogether() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let original = try await store.beginPlannedBatch(filenames: ["ORIGINAL.JPG"])
        let snapshot = await store.snapshot()
        let epoch = try XCTUnwrap(snapshot.epoch)
        let origin = MediaDeliveryOrigin(batchID: original.ticket.batchID, rowID: original.rowIDs[0])
        let child = try await store.beginContinuationBatch(origins: [origin], filenames: ["CHILD.JPG"], expectedEpoch: epoch, acknowledgedUnknown: false)
        let sibling = try await store.beginContinuationBatch(origins: [origin], filenames: ["SIBLING.JPG"], expectedEpoch: epoch, acknowledgedUnknown: false)
        let childOrigin = MediaDeliveryOrigin(batchID: child.ticket.batchID, rowID: child.rowIDs[0])
        let siblingOrigin = MediaDeliveryOrigin(batchID: sibling.ticket.batchID, rowID: sibling.rowIDs[0])
        let before = try Data(contentsOf: fixture.manifestURL)
        for selected in [[origin, childOrigin], [childOrigin, siblingOrigin]] {
            await expect(.invalidReceipt) {
                _ = try await store.beginContinuationBatch(origins: selected, filenames: ["A.JPG", "B.JPG"],
                    expectedEpoch: epoch, acknowledgedUnknown: false)
            }
        }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), before)
        let after = await store.snapshot()
        XCTAssertEqual(after.plannedBatches.count, 3)
        XCTAssertTrue(after.records.isEmpty)
    }

    func testContinuationCapacityCountsOriginalAndEveryNewRowWithoutEviction() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: (0..<50).map { "ORIGINAL-\($0).JPG" })
        let snapshot = await store.snapshot()
        let epoch = try XCTUnwrap(snapshot.epoch)
        let origins = batch.rowIDs.map { MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: $0) }
        let continuation = try await store.beginContinuationBatch(origins: origins,
            filenames: Array(repeating: "CHOSEN.JPG", count: 50), expectedEpoch: epoch, acknowledgedUnknown: false)
        let full = try Data(contentsOf: fixture.manifestURL)
        await expect(.recordLimit) {
            _ = try await store.beginContinuationBatch(origins: [origins[0]], filenames: ["EXCESS.JPG"], expectedEpoch: epoch, acknowledgedUnknown: false)
        }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), full)
        _ = try await store.beginPlannedItem(continuation.ticket, rowID: continuation.rowIDs[0])
        let reopened = await fixture.store().snapshot()
        XCTAssertEqual(reopened.plannedBatches.flatMap(\.rows).count, 100)
        XCTAssertEqual(reopened.plannedBatches[0].rows.map(\.id), batch.rowIDs)
        XCTAssertEqual(reopened.plannedBatches[1].rows.map(\.id), continuation.rowIDs)
    }

    func testPreparedLinkedAttemptBlocksHistoricalAndQueuedFamilyWithoutChangingTheirStatuses() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: ["ORIGINAL.JPG"])
        let before = await store.snapshot()
        let epoch = try XCTUnwrap(before.epoch)
        let origin = MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: batch.rowIDs[0])
        let completed = try await store.beginContinuationBatch(origins: [origin], filenames: ["COMPLETED.JPG"],
            expectedEpoch: epoch, acknowledgedUnknown: false)
        let sibling = try await store.beginContinuationBatch(origins: [origin], filenames: ["SIBLING.JPG"],
            expectedEpoch: epoch, acknowledgedUnknown: false)
        _ = try await store.beginPlannedItem(completed.ticket, rowID: completed.rowIDs[0])
        let active = await store.snapshot()
        XCTAssertTrue(active.plannedBatches.flatMap(\.rows).allSatisfy { !$0.canContinue })
        let source = try original(Data([1, 2]))
        try await store.finishPrepared(completed.ticket, receiptID: completed.rowIDs[0], source: source, actualBytes: 2)
        let prepared = await store.snapshot()
        XCTAssertTrue(prepared.plannedBatches.flatMap(\.rows).allSatisfy { !$0.canContinue })
        XCTAssertNil(prepared.plannedBatches[0].rows[0].receipt)
        XCTAssertNil(prepared.plannedBatches[2].rows[0].receipt)
        let lease = try await store.acquireShare(receiptIDs: [completed.rowIDs[0]])
        XCTAssertEqual(try Data(contentsOf: lease[0].fileURL), Data([1, 2]))
        await expect(.invalidReceipt) { _ = try await store.beginPlannedItem(sibling.ticket, rowID: sibling.rowIDs[0]) }
        await expect(.invalidReceipt) {
            _ = try await store.beginContinuationBatch(origins: [origin], filenames: ["DUPLICATE.JPG"], expectedEpoch: epoch, acknowledgedUnknown: true)
        }
        try FileManager.default.removeItem(at: fixture.file(completed.rowIDs[0], "COMPLETED.JPG"))
        let reopened = fixture.store()
        let missing = await reopened.snapshot()
        XCTAssertEqual(missing.plannedBatches[1].rows[0].receipt?.status, .prepared)
        XCTAssertEqual(missing.plannedBatches[1].rows[0].receipt?.availability, .missing)
        XCTAssertTrue(missing.plannedBatches.flatMap(\.rows).allSatisfy { !$0.canContinue })
        await expect(.invalidReceipt) {
            _ = try await reopened.beginContinuationBatch(origins: [origin], filenames: ["DUPLICATE.JPG"], expectedEpoch: epoch, acknowledgedUnknown: true)
        }
        withExtendedLifetime(lease) {}
    }

    func testContinuationRejectsExhaustedByteBudgetBeforeRosterAndAgainBeforeGET() async throws {
        for maximumBytes in [Int64(0), 2] {
            let fixture = try DeliveryFixture()
            defer { fixture.remove() }
            let store = fixture.store(bytes: maximumBytes)
            let batch = try await store.beginPlannedBatch(filenames: ["ORIGINAL.JPG"])
            let snapshot = await store.snapshot()
            let epoch = try XCTUnwrap(snapshot.epoch)
            let origin = MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: batch.rowIDs[0])
            var continuation: MediaDeliveryPlannedBatch?
            if maximumBytes > 0 {
                continuation = try await store.beginContinuationBatch(origins: [origin], filenames: ["CHOSEN.JPG"],
                    expectedEpoch: epoch, acknowledgedUnknown: false)
                let other = try await store.beginBatch()
                _ = try await prepare(store, other, name: "RETAINED.JPG", bytes: Data([1, 2]))
            }
            let full = try Data(contentsOf: fixture.manifestURL)
            await expect(.byteLimit) {
                _ = try await store.beginContinuationBatch(origins: [origin], filenames: ["EXCESS.JPG"],
                    expectedEpoch: epoch, acknowledgedUnknown: false)
            }
            XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), full)
            if let continuation {
                _ = try await store.beginPlannedItem(continuation.ticket, rowID: continuation.rowIDs[0])
                let started = try Data(contentsOf: fixture.manifestURL)
                await expect(.byteLimit) {
                    try await store.validatePlannedDownload(continuation.ticket, rowID: continuation.rowIDs[0])
                }
                XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), started)
            }
            let after = await store.snapshot()
            XCTAssertEqual(after.plannedBatches.count, maximumBytes > 0 ? 2 : 1)
            XCTAssertEqual(after.retainedBytes, maximumBytes)
        }
    }

    func testContinuationFaultAndCancellationPreserveOriginalManifestWithoutInertPartialBatch() async throws {
        for cancelAtCommit in [false, true] {
            let fixture = try DeliveryFixture()
            defer { fixture.remove() }
            let faults = DeliveryFaults()
            let store = fixture.store(faults: faults)
            let batch = try await store.beginPlannedBatch(filenames: ["ORIGINAL.JPG"])
            let snapshot = await store.snapshot()
            let epoch = try XCTUnwrap(snapshot.epoch)
            let origin = MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: batch.rowIDs[0])
            let before = try Data(contentsOf: fixture.manifestURL)
            if cancelAtCommit {
                faults.arm(.beforeManifestReplace, action: { withUnsafeCurrentTask { $0?.cancel() } })
            } else { faults.arm(.beforeManifestReplace) }
            let admission = Task {
                try await store.beginContinuationBatch(origins: [origin], filenames: ["NEW.JPG"], expectedEpoch: epoch, acknowledgedUnknown: false)
            }
            do {
                _ = try await admission.value
                XCTFail("Expected admission to fail before the atomic replacement")
            } catch {
                if cancelAtCommit { XCTAssertTrue(error is CancellationError) }
                else { XCTAssertEqual(error as? MediaDeliveryStoreError, .storageUnavailable) }
            }
            XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), before)
            let reopened = await fixture.store().snapshot()
            XCTAssertEqual(reopened.plannedBatches.map(\.id), [batch.ticket.batchID])
            XCTAssertTrue(reopened.records.isEmpty)
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: fixture.root.path), ["manifest.json"])
        }
    }

    func testAlreadyCancelledContinuationDoesNotAdmitAnyNewRows() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: ["ORIGINAL.JPG"])
        let snapshot = await store.snapshot()
        let epoch = try XCTUnwrap(snapshot.epoch)
        let before = try Data(contentsOf: fixture.manifestURL)
        let origin = MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: batch.rowIDs[0])
        let admission = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await store.beginContinuationBatch(origins: [origin], filenames: ["NEW.JPG"], expectedEpoch: epoch, acknowledgedUnknown: false)
        }
        do { _ = try await admission.value; XCTFail("Cancelled admission must fail") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), before)
    }

    func testClearInvalidatesContinuationSelectionAndLateDownloadCallbacks() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: ["ORIGINAL.JPG"])
        let before = await store.snapshot()
        let epoch = try XCTUnwrap(before.epoch)
        let origin = MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: batch.rowIDs[0])
        let next = try await store.beginContinuationBatch(origins: [origin], filenames: ["NEW.JPG"], expectedEpoch: epoch, acknowledgedUnknown: false)
        await expect(.invalidReceipt) { try await store.validatePlannedDownload(next.ticket, rowID: next.rowIDs[0]) }
        _ = try await store.beginPlannedItem(next.ticket, rowID: next.rowIDs[0])
        try await store.validatePlannedDownload(next.ticket, rowID: next.rowIDs[0])
        try await store.clear()
        let cleared = await store.snapshot()
        XCTAssertNotEqual(cleared.epoch, epoch)
        await expect(.invalidTicket) {
            _ = try await store.beginContinuationBatch(origins: [origin], filenames: ["STALE.JPG"], expectedEpoch: epoch, acknowledgedUnknown: false)
        }
        await expect(.invalidTicket) { try await store.validatePlannedDownload(next.ticket, rowID: next.rowIDs[0]) }
        await expect(.invalidTicket) { try await store.finishFailure(next.ticket, receiptID: next.rowIDs[0], cancelled: true) }
        let source = try original(Data([1]))
        await expect(.invalidTicket) { try await store.finishPrepared(next.ticket, receiptID: next.rowIDs[0], source: source, actualBytes: 1) }
        XCTAssertTrue(cleared.plannedBatches.isEmpty)
        XCTAssertTrue(cleared.records.isEmpty)
    }

    func testDownloadRevalidationRequiresExactStartedPlannedRowAndDoesNotMutate() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let dynamic = try await store.beginBatch()
        let dynamicReceipt = try await store.beginItem(dynamic, filename: "DYNAMIC.JPG")
        let batch = try await store.beginPlannedBatch(filenames: ["CURRENT.JPG"])
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
        let before = try Data(contentsOf: fixture.manifestURL)
        try await store.validatePlannedDownload(batch.ticket, rowID: batch.rowIDs[0])
        await expect(.invalidReceipt) { try await store.validatePlannedDownload(dynamic, rowID: dynamicReceipt) }
        await expect(.invalidReceipt) { try await store.validatePlannedDownload(dynamic, rowID: batch.rowIDs[0]) }
        await expect(.invalidReceipt) { try await store.validatePlannedDownload(batch.ticket, rowID: UUID()) }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), before)
        try await store.finishFailure(batch.ticket, receiptID: batch.rowIDs[0], cancelled: false)
        await expect(.invalidReceipt) { try await store.validatePlannedDownload(batch.ticket, rowID: batch.rowIDs[0]) }
    }

    func testV2ReadIsNonmutatingAndContinuationMigratesToV3WithOriginalRosterIntact() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: ["QUEUED.JPG", "FAILED.JPG"])
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[1])
        try await store.finishFailure(batch.ticket, receiptID: batch.rowIDs[1], cancelled: false)
        var legacy = try fixture.manifestObject()
        legacy["version"] = 2
        let bytes = try JSONSerialization.data(withJSONObject: legacy)
        try bytes.write(to: fixture.manifestURL)
        let reopened = fixture.store()
        let snapshot = await reopened.snapshot()
        XCTAssertNil(snapshot.warning)
        XCTAssertEqual(snapshot.plannedBatches[0].rows.map(\.id), batch.rowIDs)
        XCTAssertTrue(snapshot.plannedBatches[0].rows.allSatisfy { $0.origin == nil && $0.canContinue })
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), bytes)
        let origins = batch.rowIDs.map { MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: $0) }
        _ = try await reopened.beginContinuationBatch(origins: origins, filenames: ["CURRENT-A.JPG", "CURRENT-B.JPG"],
            expectedEpoch: try XCTUnwrap(snapshot.epoch), acknowledgedUnknown: false)
        let migrated = try fixture.manifestObject()
        XCTAssertEqual(migrated["version"] as? Int, 3, "V1/V2 clients must reject this manifest rather than drop origin links")
        XCTAssertEqual(migrated["receipts"] as? NSArray, legacy["receipts"] as? NSArray)
        XCTAssertEqual(migrated["tombstones"] as? NSArray, legacy["tombstones"] as? NSArray)
        let beforeBatch = try XCTUnwrap((legacy["plannedBatches"] as? [[String: Any]])?.first)
        let afterBatch = try XCTUnwrap((migrated["plannedBatches"] as? [[String: Any]])?.first)
        XCTAssertEqual(afterBatch as NSDictionary, beforeBatch as NSDictionary)
        let after = await fixture.store().snapshot()
        XCTAssertNil(after.warning)
        XCTAssertEqual(after.plannedBatches[1].rows.compactMap(\.origin), origins)
    }

    func testPersistedOriginRemainsValidWhenOriginalLaterCompletes() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: ["ORIGINAL.JPG"])
        let snapshot = await store.snapshot()
        let origin = MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: batch.rowIDs[0])
        let continuation = try await store.beginContinuationBatch(origins: [origin], filenames: ["CHOSEN.JPG"],
            expectedEpoch: try XCTUnwrap(snapshot.epoch), acknowledgedUnknown: false)
        // A valid historical link is not retroactively corrupt when a previous
        // attempt records a completed fact after the linked roster was created.
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
        let source = try original(Data([1]))
        try await store.finishPrepared(batch.ticket, receiptID: batch.rowIDs[0], source: source, actualBytes: 1)
        let after = await fixture.store().snapshot()
        XCTAssertNil(after.warning)
        XCTAssertEqual(after.plannedBatches[0].rows[0].receipt?.status, .prepared)
        XCTAssertFalse(after.plannedBatches[0].rows[0].canContinue)
        XCTAssertEqual(after.plannedBatches[1].rows[0].id, continuation.rowIDs[0])
        XCTAssertEqual(after.plannedBatches[1].rows[0].origin, origin)
        XCTAssertFalse(after.plannedBatches[1].rows[0].canContinue)
    }

    func testHostileOriginMetadataDanglingForwardCyclicAndDuplicateLinksFailClosed() async throws {
        let corruptions = ["null", "missing-key", "extra-private-key", "invalid-uuid", "missing-batch", "missing-row",
            "crossed-pair", "self", "same-batch", "forward-cycle", "duplicate", "v2-origin"]
        for corruption in corruptions {
            let fixture = try DeliveryFixture()
            defer { fixture.remove() }
            let store = fixture.store()
            let batch = try await store.beginPlannedBatch(filenames: ["A.JPG", "B.JPG"])
            let snapshot = await store.snapshot()
            let origins = batch.rowIDs.map { MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: $0) }
            let continuation = try await store.beginContinuationBatch(origins: origins, filenames: ["C.JPG", "D.JPG"],
                expectedEpoch: try XCTUnwrap(snapshot.epoch), acknowledgedUnknown: false)
            var json = try fixture.manifestObject()
            var batches = try XCTUnwrap(json["plannedBatches"] as? [[String: Any]])
            var rows = try XCTUnwrap(batches[1]["rows"] as? [[String: Any]])
            var reference = try XCTUnwrap(rows[0]["origin"] as? [String: Any])
            switch corruption {
            case "null": rows[0]["origin"] = NSNull()
            case "missing-key": reference.removeValue(forKey: "rowID")
            case "extra-private-key": reference["cameraID"] = "synthetic-private-metadata"
            case "invalid-uuid": reference["rowID"] = "synthetic-camera-media-id"
            case "missing-batch": reference["batchID"] = UUID().uuidString
            case "missing-row": reference["rowID"] = UUID().uuidString
            case "crossed-pair": reference["rowID"] = continuation.rowIDs[0].uuidString
            case "self":
                reference["batchID"] = continuation.ticket.batchID.uuidString
                reference["rowID"] = continuation.rowIDs[0].uuidString
            case "same-batch":
                reference["batchID"] = continuation.ticket.batchID.uuidString
                reference["rowID"] = continuation.rowIDs[1].uuidString
            case "forward-cycle":
                var originals = try XCTUnwrap(batches[0]["rows"] as? [[String: Any]])
                originals[0]["origin"] = ["batchID": continuation.ticket.batchID.uuidString, "rowID": continuation.rowIDs[0].uuidString]
                batches[0]["rows"] = originals
            case "duplicate": rows[1]["origin"] = rows[0]["origin"]
            case "v2-origin": json["version"] = 2
            default: XCTFail("Unhandled corruption")
            }
            if corruption != "null" { rows[0]["origin"] = reference }
            batches[1]["rows"] = rows
            json["plannedBatches"] = batches
            let bytes = try JSONSerialization.data(withJSONObject: json)
            try bytes.write(to: fixture.manifestURL)
            let reopened = fixture.store()
            let after = await reopened.snapshot()
            XCTAssertEqual(after.warning, .invalidManifest, corruption)
            XCTAssertNil(after.epoch, corruption)
            XCTAssertTrue(after.plannedBatches.isEmpty, corruption)
            await expect(.invalidManifest) { try await reopened.clear() }
            XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), bytes, corruption)
        }
    }

    func testFreshStoreReconstructsAllFiftyPlannedRowsIncludingFortyFourQueued() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let names = (0..<50).map { "SELECTED-\($0).JPG" }
        let batch = try await store.beginPlannedBatch(filenames: names)
        let admitted = await store.snapshot()
        XCTAssertTrue(admitted.records.isEmpty)
        XCTAssertEqual(admitted.plannedBatches.first?.rows.count, 50)
        XCTAssertEqual(Set(batch.rowIDs).count, 50)
        for id in batch.rowIDs.prefix(5) {
            let receipt = try await store.beginPlannedItem(batch.ticket, rowID: id)
            XCTAssertEqual(receipt, id)
            let source = try original(Data([1, 2, 3]))
            try await store.finishPrepared(batch.ticket, receiptID: receipt, source: source, actualBytes: 3)
        }
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[5])
        let bytes = try Data(contentsOf: fixture.manifestURL)

        // Fresh actor reconstruction exercises persisted boundaries. It is not
        // evidence of an actual OS process kill or power-loss durability.
        let snapshot = await fixture.store().snapshot()
        XCTAssertNil(snapshot.warning)
        let restored = try XCTUnwrap(snapshot.plannedBatches.first)
        XCTAssertEqual(restored.id, batch.ticket.batchID)
        XCTAssertEqual(restored.rows.map(\.id), batch.rowIDs)
        XCTAssertEqual(restored.rows.map(\.filename), names)
        XCTAssertEqual(restored.rows.prefix(5).map { $0.receipt?.status }, Array(repeating: .prepared, count: 5))
        XCTAssertEqual(restored.rows[5].receipt?.status, .unknown)
        XCTAssertEqual(restored.rows[5].receipt?.reason, .interrupted)
        XCTAssertTrue(restored.rows.suffix(44).allSatisfy { $0.receipt == nil })
        XCTAssertEqual(snapshot.records.count, 6)
        XCTAssertEqual(snapshot.retainedBytes, 15)
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), bytes, "Projection must not rewrite started receipts")

        try FileManager.default.removeItem(at: fixture.file(batch.rowIDs[0], names[0]))
        let missing = await fixture.store().snapshot()
        XCTAssertEqual(missing.plannedBatches.first?.rows.first?.receipt?.status, .prepared)
        XCTAssertEqual(missing.plannedBatches.first?.rows.first?.receipt?.availability, .missing)
        XCTAssertEqual(missing.plannedBatches.first?.rows.count, 50)
    }

    func testFailedWholePlanAdmissionPreservesPreviousBytesAndEveryExistingRow() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let faults = DeliveryFaults()
        let store = fixture.store(faults: faults)
        let ticket = try await store.beginBatch()
        let kept = try await prepare(store, ticket, name: "KEEP.JPG", bytes: Data([1, 2]))
        let prior = try await store.beginPlannedBatch(filenames: ["ALREADY-QUEUED.JPG"])
        let previous = try Data(contentsOf: fixture.manifestURL)
        faults.arm(.beforeManifestReplace)
        await expect(.storageUnavailable) {
            _ = try await store.beginPlannedBatch(filenames: (0..<50).map { "REJECTED-\($0).JPG" })
        }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), previous)
        let snapshot = await fixture.store().snapshot()
        XCTAssertEqual(snapshot.plannedBatches.map(\.id), [prior.ticket.batchID])
        XCTAssertEqual(snapshot.plannedBatches.first?.rows.map(\.id), prior.rowIDs)
        XCTAssertEqual(snapshot.records.map(\.id), [kept])
        XCTAssertEqual(snapshot.records.first?.availability, .available)
        XCTAssertEqual(try Data(contentsOf: fixture.file(kept, "KEEP.JPG")), Data([1, 2]))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: fixture.root.path).count, 2)
    }

    func testInterleavedAttemptsAcrossBatchesRestoreOriginalRowOrderByLocalIdentity() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let first = try await store.beginPlannedBatch(filenames: Array(repeating: "REPEATED.JPG", count: 3))
        let second = try await store.beginPlannedBatch(filenames: Array(repeating: "REPEATED.JPG", count: 2))
        let secondLast = try await store.beginPlannedItem(second.ticket, rowID: second.rowIDs[1])
        let firstLast = try await store.beginPlannedItem(first.ticket, rowID: first.rowIDs[2])
        let secondFirst = try await store.beginPlannedItem(second.ticket, rowID: second.rowIDs[0])
        try await store.finishFailure(second.ticket, receiptID: secondLast, cancelled: false)
        try await store.finishFailure(first.ticket, receiptID: firstLast, cancelled: true)
        let snapshot = await fixture.store().snapshot()
        XCTAssertEqual(snapshot.plannedBatches.map(\.id), [first.ticket.batchID, second.ticket.batchID])
        XCTAssertEqual(snapshot.plannedBatches[0].rows.map(\.id), first.rowIDs)
        XCTAssertEqual(snapshot.plannedBatches[1].rows.map(\.id), second.rowIDs)
        XCTAssertEqual(snapshot.plannedBatches[0].rows.map { $0.receipt?.status }, [nil, nil, .cancelled])
        XCTAssertEqual(snapshot.plannedBatches[1].rows.map { $0.receipt?.status }, [.unknown, .failed])
        XCTAssertEqual(snapshot.records.map(\.id), [secondLast, firstLast, secondFirst])
        XCTAssertEqual(snapshot.plannedBatches[0].rows[2].receipt?.batchID, first.ticket.batchID)
        XCTAssertEqual(snapshot.plannedBatches[1].rows[0].receipt?.batchID, second.ticket.batchID)
    }

    func testFailedPlannedItemAdmissionKeepsItsReservedRowQueued() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let faults = DeliveryFaults()
        let store = fixture.store(faults: faults)
        let batch = try await store.beginPlannedBatch(filenames: ["QUEUED.JPG"])
        let previous = try Data(contentsOf: fixture.manifestURL)
        faults.arm(.beforeManifestReplace)
        await expect(.storageUnavailable) {
            _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
        }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), previous)
        let snapshot = await fixture.store().snapshot()
        XCTAssertTrue(snapshot.records.isEmpty)
        XCTAssertEqual(snapshot.plannedBatches.first?.rows.map(\.id), batch.rowIDs)
        XCTAssertNil(snapshot.plannedBatches.first?.rows.first?.receipt)
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
        await expect(.invalidReceipt) { _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0]) }
    }

    func testCancelledAndInterruptedPlannedAttemptsLeaveOtherRowsQueued() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: ["CANCELLED.JPG", "INTERRUPTED.JPG", "QUEUED.JPG"])
        let cancelled = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
        try await store.finishFailure(batch.ticket, receiptID: cancelled, cancelled: true)
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[1])
        let other = try await store.beginBatch()
        await expect(.invalidReceipt) { _ = try await store.beginPlannedItem(other, rowID: batch.rowIDs[2]) }
        await expect(.invalidReceipt) { _ = try await store.beginPlannedItem(batch.ticket, rowID: UUID()) }
        await expect(.invalidTicket) { _ = try await store.beginItem(batch.ticket, filename: "OUTSIDE-ROSTER.JPG") }
        let snapshot = await fixture.store().snapshot()
        let rows = try XCTUnwrap(snapshot.plannedBatches.first?.rows)
        XCTAssertEqual(rows.map { $0.receipt?.status }, [.cancelled, .unknown, nil])
        XCTAssertEqual(rows.map { $0.receipt?.reason }, [.cancelled, .interrupted, nil])
        XCTAssertEqual(rows.map(\.id), batch.rowIDs)
    }

    func testPlannedReservationsShareCapacityWithDynamicReceiptsWithoutTruncation() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store(records: 4)
        let ticket = try await store.beginBatch()
        let retained = try await prepare(store, ticket, name: "KEEP.JPG", bytes: Data([1]))
        let batch = try await store.beginPlannedBatch(filenames: ["FIRST.JPG", "SECOND.JPG", "THIRD.JPG"])
        let full = try Data(contentsOf: fixture.manifestURL)
        await expect(.recordLimit) { _ = try await store.beginItem(ticket, filename: "EXCESS.JPG") }
        await expect(.recordLimit) { _ = try await store.beginPlannedBatch(filenames: ["EXCESS.JPG"]) }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), full)
        // Converting reserved rows to receipts consumes no additional slots.
        for id in batch.rowIDs {
            _ = try await store.beginPlannedItem(batch.ticket, rowID: id)
            try await store.finishFailure(batch.ticket, receiptID: id, cancelled: false)
        }
        await expect(.recordLimit) { _ = try await store.beginItem(ticket, filename: "STILL-FULL.JPG") }
        let snapshot = await fixture.store(records: 4).snapshot()
        XCTAssertEqual(snapshot.records.count, 4)
        XCTAssertEqual(snapshot.records.first?.id, retained)
        XCTAssertEqual(snapshot.records.first?.availability, .available)
        XCTAssertEqual(snapshot.plannedBatches.first?.rows.map(\.id), batch.rowIDs)
        XCTAssertTrue(snapshot.plannedBatches.first!.rows.allSatisfy { $0.receipt?.status == .failed })
    }

    func testPlanAdmissionRejectsEmptyAndOverProductionCapacityWithoutTruncation() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store(records: 1_000)
        _ = try await store.beginBatch()
        let empty = try Data(contentsOf: fixture.manifestURL)
        await expect(.invalidReceipt) { _ = try await store.beginPlannedBatch(filenames: []) }
        await expect(.recordLimit) { _ = try await store.beginPlannedBatch(filenames: Array(repeating: "EXCESS.JPG", count: 101)) }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), empty)
        let full = try await store.beginPlannedBatch(filenames: Array(repeating: "SAME.JPG", count: 100))
        let snapshot = await fixture.store().snapshot()
        XCTAssertEqual(snapshot.plannedBatches.first?.rows.count, 100)
        XCTAssertEqual(Set(full.rowIDs).count, 100)
        let previous = try Data(contentsOf: fixture.manifestURL)
        await expect(.recordLimit) { _ = try await store.beginPlannedBatch(filenames: ["NO-ROOM.JPG"]) }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), previous)
    }

    func testPlannedUnknownSizeAndSanitizedDuplicateNamesRemainIndependent() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store(bytes: 3)
        let batch = try await store.beginPlannedBatch(filenames: ["../../SAME.JPG", "SAME.JPG", "../", "\u{0000}name.JPG"])
        let before = try Data(contentsOf: fixture.manifestURL)
        for size in [Int64(-1), 0, 4] {
            await expect(.byteLimit) { _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0], expectedBytes: size) }
        }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), before)
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0], expectedBytes: 3)
        let source = try original(Data([1, 2, 3]))
        try await store.finishPrepared(batch.ticket, receiptID: batch.rowIDs[0], source: source, actualBytes: 3)
        // Unknown-size manual work retains its best-effort admission behavior.
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[1])
        await expect(.byteLimit) {
            try await store.finishPrepared(batch.ticket, receiptID: batch.rowIDs[1], source: source, actualBytes: 3)
        }
        let snapshot = await fixture.store(bytes: 3).snapshot()
        let rows = try XCTUnwrap(snapshot.plannedBatches.first?.rows)
        XCTAssertEqual(rows.map(\.filename), ["SAME.JPG", "SAME.JPG", "original-media", "_name.JPG"])
        XCTAssertEqual(rows.map(\.id), batch.rowIDs)
        XCTAssertNotEqual(rows[0].id, rows[1].id)
        XCTAssertEqual(rows[0].receipt?.availability, .available)
        XCTAssertEqual(rows[1].receipt?.status, .prepared)
        XCTAssertEqual(rows[1].receipt?.availability, .notRetained)
        XCTAssertFalse(rows[0].canContinue)
        XCTAssertFalse(rows[1].canContinue, "Prepared originals stay terminal even when retention failed")
        XCTAssertNil(rows[2].receipt)
        XCTAssertNil(rows[3].receipt)
        XCTAssertEqual(snapshot.retainedBytes, 3)
        let object = try fixture.manifestObject()
        let plans = try XCTUnwrap(object["plannedBatches"] as? [[String: Any]])
        XCTAssertEqual(Set(plans[0].keys), Set(["id", "createdAt", "rows"]))
        let savedRows = try XCTUnwrap(plans[0]["rows"] as? [[String: Any]])
        XCTAssertTrue(savedRows.allSatisfy { Set($0.keys) == Set(["id", "filename"]) })
    }

    func testValidV1MigratesOnMutationWithoutInventingRosterOrLosingIdentityAndTombstones() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let first = try await store.beginBatch()
        let pinned = try await prepare(store, first, name: "PINNED.JPG", bytes: Data([1, 2]))
        let lease = try await store.acquireShare(receiptIDs: [pinned])
        try await store.clear()
        let second = try await store.beginBatch()
        let kept = try await prepare(store, second, name: "LEGACY.JPG", bytes: Data([3, 4, 5]))
        let interrupted = try await store.beginItem(second, filename: "INTERRUPTED-LEGACY.JPG")
        var legacy = try fixture.manifestObject()
        legacy["version"] = 1
        legacy.removeValue(forKey: "plannedBatches")
        let bytes = try JSONSerialization.data(withJSONObject: legacy)
        try bytes.write(to: fixture.manifestURL)
        let restarted = fixture.store()
        let before = await restarted.snapshot()
        XCTAssertNil(before.warning)
        XCTAssertTrue(before.plannedBatches.isEmpty)
        XCTAssertEqual(before.records.map(\.id), [kept, interrupted])
        XCTAssertEqual(before.records.map(\.status), [.prepared, .unknown])
        XCTAssertEqual(before.records.first?.availability, .available)
        XCTAssertEqual(before.retainedBytes, 5)
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), bytes)
        let batch = try await restarted.beginPlannedBatch(filenames: ["NEW-QUEUED.JPG"])
        let migrated = try fixture.manifestObject()
        XCTAssertEqual(migrated["version"] as? Int, 3)
        var expectedReceipts = try XCTUnwrap(legacy["receipts"] as? [[String: Any]])
        expectedReceipts[1]["status"] = "unknown"
        expectedReceipts[1]["reason"] = "interrupted"
        XCTAssertEqual(migrated["receipts"] as? NSArray, expectedReceipts as NSArray)
        XCTAssertEqual(migrated["tombstones"] as? NSArray, legacy["tombstones"] as? NSArray)
        let after = await restarted.snapshot()
        XCTAssertEqual(after.plannedBatches.map(\.id), [batch.ticket.batchID])
        XCTAssertEqual(after.records.map(\.id), [kept, interrupted])
        XCTAssertEqual(after.records.last?.status, .unknown)
        XCTAssertEqual(try Data(contentsOf: lease[0].fileURL), Data([1, 2]))
        withExtendedLifetime(lease) {}
    }

    func testPlannedDisplayNamesDiscardURLAuthorityQueryAndFragmentWithoutUsingNamesAsIdentity() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: [
            "https://synthetic-authority.invalid/SAME.JPG?token=synthetic-query#synthetic-fragment",
            "../SAME.JPG#synthetic-fragment",
            "https://synthetic-authority.invalid?access=synthetic-query"
        ])
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[1])
        let snapshot = await fixture.store().snapshot()
        let rows = try XCTUnwrap(snapshot.plannedBatches.first?.rows)
        XCTAssertEqual(rows.map(\.filename), ["SAME.JPG", "SAME.JPG", "original-media"])
        XCTAssertNotEqual(rows[0].id, rows[1].id)
        XCTAssertEqual(rows[0].receipt?.filename, "SAME.JPG")
        XCTAssertEqual(rows[1].receipt?.filename, "SAME.JPG")
        let persisted = String(decoding: try Data(contentsOf: fixture.manifestURL), as: UTF8.self)
        for forbidden in ["synthetic-authority", "synthetic-query", "synthetic-fragment", "token=", "access=", "https:", "?", "#"] {
            XCTAssertFalse(persisted.contains(forbidden), forbidden)
        }
    }

    func testDynamicSingleDisplayNamesUseTheSameSanitizerAsPlannedRows() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let ticket = try await store.beginBatch()
        for filename in ["https://synthetic-authority.invalid/SAME.JPG?token=synthetic-query#synthetic-fragment",
                         "../SAME.JPG#synthetic-fragment", "https://synthetic-authority.invalid?token=synthetic-query"] {
            _ = try await store.beginItem(ticket, filename: filename, expectedBytes: nil)
        }
        let snapshot = await fixture.store().snapshot()
        XCTAssertEqual(snapshot.records.map(\.filename), ["SAME.JPG", "SAME.JPG", "original-media"])
        XCTAssertEqual(Set(snapshot.records.map(\.id)).count, 3)
        XCTAssertTrue(snapshot.plannedBatches.isEmpty)
        let persisted = String(decoding: try Data(contentsOf: fixture.manifestURL), as: UTF8.self)
        for forbidden in ["synthetic-authority", "synthetic-query", "synthetic-fragment", "token=", "https:", "?", "#"] {
            XCTAssertFalse(persisted.contains(forbidden), forbidden)
        }
    }

    func testClearRemovesPlanAndReceiptsTogetherRejectsLateCallbacksAndKeepsExportLease() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let batch = try await store.beginPlannedBatch(filenames: ["PREPARED.JPG", "STARTED.JPG", "QUEUED.JPG"])
        let prepared = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
        let source = try original(Data([1, 2, 3]))
        try await store.finishPrepared(batch.ticket, receiptID: prepared, source: source, actualBytes: 3)
        let started = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[1])
        var leases: [MediaDeliveryFileLease]? = try await store.acquireShare(receiptIDs: [prepared])
        let file = try XCTUnwrap(leases?.first?.fileURL)
        try await store.clear()
        await expect(.invalidTicket) { _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[2]) }
        await expect(.invalidTicket) { try await store.finishFailure(batch.ticket, receiptID: started, cancelled: true) }
        await expect(.invalidTicket) { try await store.finishPrepared(batch.ticket, receiptID: started, source: source, actualBytes: 3) }
        let cleared = await fixture.store().snapshot()
        XCTAssertTrue(cleared.plannedBatches.isEmpty)
        XCTAssertTrue(cleared.records.isEmpty)
        XCTAssertEqual(cleared.retainedBytes, 3)
        XCTAssertEqual(try Data(contentsOf: file), Data([1, 2, 3]))
        let next = try await store.beginPlannedBatch(filenames: ["NEW.JPG"])
        XCTAssertNotEqual(next.rowIDs[0], batch.rowIDs[0])
        leases = nil
        try await waitUntil { !FileManager.default.fileExists(atPath: file.path) }
        let after = await store.snapshot()
        XCTAssertEqual(after.plannedBatches.map(\.id), [next.ticket.batchID])
        XCTAssertEqual(after.retainedBytes, 0)
        XCTAssertTrue(after.records.isEmpty)
    }

    func testFailedClearLeavesWholePlanAndTicketUsable() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let faults = DeliveryFaults()
        let store = fixture.store(faults: faults)
        let batch = try await store.beginPlannedBatch(filenames: ["FIRST.JPG", "SECOND.JPG"])
        let first = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
        let before = try Data(contentsOf: fixture.manifestURL)
        faults.arm(.beforeManifestReplace)
        await expect(.storageUnavailable) { try await store.clear() }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), before)
        try await store.finishFailure(batch.ticket, receiptID: first, cancelled: true)
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[1])
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.plannedBatches.first?.rows.map(\.id), batch.rowIDs)
        XCTAssertEqual(snapshot.plannedBatches.first?.rows.map { $0.receipt?.status }, [.cancelled, .started])
    }

    func testHostilePlanFieldsDuplicateIdentitiesAndBrokenReceiptLinksFailClosed() async throws {
        let forbidden = ["rawMediaID", "url", "session", "serial", "destinationPath", "handle", "token", "expectedBytes"]
        let corruptions = forbidden.map { "batch-\($0)" } + forbidden.map { "row-\($0)" }
            + ["duplicate-batch", "duplicate-row", "cross-batch-row", "empty-rows", "unsafe-name", "long-name", "query-name",
               "invalid-date", "foreign-receipt-batch", "foreign-receipt-name", "unlisted-receipt", "batch-is-row"]
        for corruption in corruptions {
            let fixture = try DeliveryFixture()
            defer { fixture.remove() }
            let store = fixture.store()
            let batch = try await store.beginPlannedBatch(filenames: ["FIRST.JPG", "SECOND.JPG"])
            _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
            var json = try fixture.manifestObject()
            var batches = try XCTUnwrap(json["plannedBatches"] as? [[String: Any]])
            var rows = try XCTUnwrap(batches[0]["rows"] as? [[String: Any]])
            var receipts = try XCTUnwrap(json["receipts"] as? [[String: Any]])
            switch corruption {
            case "duplicate-batch": batches.append(batches[0])
            case "duplicate-row": rows[1]["id"] = rows[0]["id"]
            case "cross-batch-row":
                var other = batches[0]
                other["id"] = UUID().uuidString
                batches.append(other)
            case "empty-rows": rows = []
            case "unsafe-name": rows[1]["filename"] = "../OUTSIDE.JPG"
            case "long-name": rows[1]["filename"] = String(repeating: "x", count: 201)
            case "query-name": rows[1]["filename"] = "SECOND.JPG?token=synthetic-query"
            case "invalid-date": batches[0]["createdAt"] = 1e300
            case "foreign-receipt-batch": receipts[0]["batchID"] = UUID().uuidString
            case "foreign-receipt-name": receipts[0]["filename"] = "OTHER.JPG"
            case "unlisted-receipt": rows[0]["id"] = UUID().uuidString
            case "batch-is-row": batches[0]["id"] = rows[1]["id"]
            default:
                if corruption.hasPrefix("batch-") { batches[0][String(corruption.dropFirst(6))] = "untrusted" }
                else { rows[0][String(corruption.dropFirst(4))] = "untrusted" }
            }
            batches[0]["rows"] = rows
            json["plannedBatches"] = batches
            json["receipts"] = receipts
            let malformed = try JSONSerialization.data(withJSONObject: json)
            try malformed.write(to: fixture.manifestURL)
            let restarted = fixture.store()
            let snapshot = await restarted.snapshot()
            XCTAssertEqual(snapshot.warning, .invalidManifest, corruption)
            XCTAssertTrue(snapshot.records.isEmpty, corruption)
            XCTAssertTrue(snapshot.plannedBatches.isEmpty, corruption)
            await expect(.invalidManifest) { _ = try await restarted.beginPlannedBatch(filenames: ["BLOCKED.JPG"]) }
            await expect(.invalidManifest) { try await restarted.clear() }
            XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), malformed, corruption)
        }
    }

    func testManifestByteLimitRejectsWholePlanWithoutReplacingExistingEvidence() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store()
        let ticket = try await store.beginBatch()
        _ = try await prepare(store, ticket, name: "SEED.JPG", bytes: Data([1]))
        var json = try fixture.manifestObject()
        let receipt = try XCTUnwrap((json["receipts"] as? [[String: Any]])?.first)
        let identity = try XCTUnwrap(receipt["identity"])
        json["receipts"] = [[String: Any]]()
        // Legitimate cleanup evidence can remain after an earlier file delete.
        // Fill only schema-allowed tombstones; no arbitrary padding field.
        var tombstones: [[String: Any]] = []
        var previous = Data()
        for _ in 0..<512 {
            tombstones.append(["id": UUID().uuidString, "filename": String(repeating: "x", count: 200),
                "actualBytes": 1, "identity": identity])
            json["tombstones"] = tombstones
            let candidate = try JSONSerialization.data(withJSONObject: json)
            if candidate.count > 256 * 1024 { break }
            previous = candidate
        }
        XCTAssertGreaterThan(previous.count, 250 * 1024)
        XCTAssertLessThanOrEqual(previous.count, 256 * 1024)
        try previous.write(to: fixture.manifestURL)
        let restarted = fixture.store()
        let before = await restarted.snapshot()
        XCTAssertNil(before.warning)
        await expect(.invalidManifest) {
            _ = try await restarted.beginPlannedBatch(filenames: Array(repeating: String(repeating: "y", count: 200), count: 100))
        }
        XCTAssertEqual(try Data(contentsOf: fixture.manifestURL), previous)
        let after = await fixture.store().snapshot()
        XCTAssertTrue(after.plannedBatches.isEmpty)
        XCTAssertEqual(after.retainedBytes, 1, "The unclaimed seed file is still counted and preserved")
    }

    func testExpectedSizePreflightRejectsWithoutCreatingReceiptAndKeepsManualCompatibility() async throws {
        let fixture = try DeliveryFixture()
        defer { fixture.remove() }
        let store = fixture.store(bytes: 4)
        let ticket = try await store.beginBatch()
        for size in [Int64(-1), 0, 5] {
            await expect(.byteLimit) {
                _ = try await store.beginItem(ticket, filename: "REJECT.JPG", expectedBytes: size)
            }
        }
        let rejected = await store.snapshot()
        XCTAssertTrue(rejected.records.isEmpty)
        let exact = try await store.beginItem(ticket, filename: "EXACT.JPG", expectedBytes: 4)
        let source = try original(Data([1, 2, 3, 4]))
        try await store.finishPrepared(ticket, receiptID: exact, source: source, actualBytes: 4)
        await expect(.byteLimit) {
            _ = try await store.beginItem(ticket, filename: "FULL.JPG", expectedBytes: 1)
        }
        // Legacy manual preparation has no expected-size admission promise.
        // Its existing finishPrepared check still enforces actual retained bytes.
        let manual = try await store.beginItem(ticket, filename: "MANUAL.JPG")
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.map(\.id), [exact, manual])
        XCTAssertEqual(snapshot.retainedBytes, 4)
    }

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
        XCTAssertEqual(Set(json.keys), Set(["version", "epoch", "receipts", "tombstones", "plannedBatches"]))
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
            (Data("{\"version\":1,\"epoch\":\"00000000-0000-0000-0000-000000000001\",\"receipts\":[],\"tombstones\":[],\"rawError\":\"do not accept\"}".utf8), .invalidManifest),
            (Data("{\"version\":2,\"epoch\":\"00000000-0000-0000-0000-000000000001\",\"receipts\":[],\"tombstones\":[]}".utf8), .invalidManifest),
            (Data("{\"version\":2,\"epoch\":\"00000000-0000-0000-0000-000000000001\",\"receipts\":[],\"tombstones\":[],\"plannedBatches\":null}".utf8), .invalidManifest)
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
            XCTAssertTrue(snapshot.plannedBatches.isEmpty)
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
                try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 1_000_000)], ofItemAtPath: retained.path)
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
