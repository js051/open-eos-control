import Combine
import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

@MainActor
final class MediaBatchDeliveryTests: XCTestCase {
    func testSinglePreparedOriginalFilesAttemptPinsFactorySourceThroughDisconnectAndClear() async throws {
        for bridge in [false, true] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let peer = BatchMediaPeer(items: [item("FILES-SINGLE")])
            let state = makeState([peer], bridge: bridge, deliveryStore: MediaDeliveryStore(root: root))
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            let original = try XCTUnwrap(state.mediaItems.first)
            state.startMediaDownload(original)
            await state.mediaDownloadTask?.value
            XCTAssertTrue(state.isMediaDownloaded(original))
            XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.status), [.prepared])
            XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.availability), [.available])
            let manifest = root.appendingPathComponent("manifest.json")
            let preparedFacts = try Data(contentsOf: manifest)
            let requests = await peer.requests()

            let exporter = MediaFilesExportController()
            XCTAssertTrue(exporter.beginUnrecordedForTesting(state.makeMediaDownloadExportPayload(for: original)))
            var coordinator: MediaFilesExportCoordinator? = MediaFilesExportCoordinator(
                attempt: try XCTUnwrap(exporter.presentation), owner: exporter)
            let source = try XCTUnwrap(coordinator?.attempt.payload.fileURLs.first)
            let identities = try XCTUnwrap(coordinator?.attempt.payload.filesExportSourceIdentities)
            XCTAssertNil(coordinator?.presentationFailure())
            coordinator?.finish(.cancelled)
            XCTAssertEqual(exporter.result?.status, .cancelled)
            XCTAssertEqual(exporter.result?.expectedCount, 1)
            XCTAssertTrue(state.isMediaDownloaded(original), "A Files cancellation cannot undo preparation")
            XCTAssertEqual(try Data(contentsOf: manifest), preparedFacts)
            let afterResult = await peer.requests()
            XCTAssertEqual(afterResult, requests, "A Files attempt cannot fetch or change camera media")

            await state.disconnect()
            XCTAssertNil(state.makeMediaDownloadExportPayload(for: original))
            let disconnectedRequests = await peer.requests()
            await state.clearMediaDeliveryHistory()
            XCTAssertTrue(state.mediaDeliverySnapshot.records.isEmpty)
            XCTAssertEqual(coordinator?.attempt.payload.fileURLs, [source])
            XCTAssertEqual(coordinator?.attempt.payload.filesExportSourceIdentities, identities)
            XCTAssertEqual(try Data(contentsOf: source), BatchMediaPeer.original)
            coordinator?.dismantle()
            XCTAssertFalse(exporter.isActive)
            XCTAssertTrue(FileManager.default.fileExists(atPath: source.path), "The coordinator still owns its source after dismantle")
            coordinator = nil
            try await waitUntil { !FileManager.default.fileExists(atPath: source.path) }
            let afterClear = await peer.requests()
            XCTAssertEqual(afterClear, disconnectedRequests)
        }
    }

    func testPartialBatchFilesAttemptExportsOnlyReadyFactorySubsetWithoutChangingWorklist() async throws {
        for bridge in [false, true] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let originals = [item("FILES-READY"), item("FILES-FAILED"), item("FILES-QUEUED")]
            let peer = BatchMediaPeer(items: originals)
            let state = makeState([peer], bridge: bridge, deliveryStore: MediaDeliveryStore(root: root))
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            state.selectMediaItems(originals)
            await peer.enqueueDownload()
            await peer.enqueueDownload(error: .networkConnectionLost)
            XCTAssertTrue(state.startMediaBatchDownload())
            await state.mediaBatchTask?.value
            XCTAssertEqual(state.mediaBatchState, .failed)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .failed, .notStarted])
            let readySource = try XCTUnwrap(state.mediaBatchResults.first?.completedFileURL)
            let manifest = root.appendingPathComponent("manifest.json")
            let preparedFacts = try Data(contentsOf: manifest)
            let requests = await peer.requests()

            let exporter = MediaFilesExportController()
            XCTAssertTrue(exporter.beginUnrecordedForTesting(state.makeMediaBatchExportPayload()))
            var coordinator: MediaFilesExportCoordinator? = MediaFilesExportCoordinator(
                attempt: try XCTUnwrap(exporter.presentation), owner: exporter)
            XCTAssertEqual(coordinator?.attempt.payload.fileURLs, [readySource])
            XCTAssertNil(coordinator?.presentationFailure())
            coordinator?.finish(.reported(count: 1))
            XCTAssertEqual(exporter.result?.status, .providerReported)
            XCTAssertEqual(exporter.result?.expectedCount, 1)
            XCTAssertEqual(state.mediaBatchState, .failed)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .failed, .notStarted])
            XCTAssertEqual(try Data(contentsOf: manifest), preparedFacts, "Files reporting cannot rewrite the prepared, failed or queued worklist")
            let afterResult = await peer.requests()
            XCTAssertEqual(afterResult, requests)
            let downloads = await peer.downloadIDs()
            XCTAssertEqual(downloads, originals.prefix(2).map(mediaItemIdentity))

            await state.disconnect()
            XCTAssertNil(state.makeMediaBatchExportPayload())
            let disconnectedRequests = await peer.requests()
            await state.clearMediaDeliveryHistory()
            XCTAssertEqual(coordinator?.attempt.payload.fileURLs, [readySource])
            XCTAssertEqual(try Data(contentsOf: readySource), BatchMediaPeer.original)
            coordinator?.dismantle()
            XCTAssertTrue(FileManager.default.fileExists(atPath: readySource.path))
            coordinator = nil
            try await waitUntil { !FileManager.default.fileExists(atPath: readySource.path) }
            let afterClear = await peer.requests()
            XCTAssertEqual(afterClear, disconnectedRequests)
        }
    }

    func testOfflineHistoryFilesAttemptPinsColdFactoryCopiesThroughClearWithoutRequests() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let peer = BatchMediaPeer(items: [item("FILES-A", name: "SAME.JPG"), item("FILES-B", name: "SAME.JPG")])
        let state = makeState([peer], deliveryStore: MediaDeliveryStore(root: root))
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        await state.mediaBatchTask?.value
        await state.disconnect()
        let requests = await peer.requests()

        let offline = makeState([], deliveryStore: MediaDeliveryStore(root: root))
        await offline.refreshMediaDeliveryHistory()
        XCTAssertFalse(offline.connected)
        XCTAssertEqual(offline.mediaDeliverySnapshot.records.map(\.status), [.prepared, .prepared])
        XCTAssertEqual(offline.mediaDeliverySnapshot.records.map(\.availability), [.available, .available])
        let manifest = root.appendingPathComponent("manifest.json")
        let preparedFacts = try Data(contentsOf: manifest)
        var payload = await offline.makeStoredMediaExportPayload(ids: offline.mediaDeliverySnapshot.records.map(\.id))
        let exporter = MediaFilesExportController()
        XCTAssertTrue(exporter.beginUnrecordedForTesting(payload))
        var coordinator: MediaFilesExportCoordinator? = MediaFilesExportCoordinator(
            attempt: try XCTUnwrap(exporter.presentation), owner: exporter)
        payload = nil
        let sources = try XCTUnwrap(coordinator?.attempt.payload.fileURLs)
        let identities = try XCTUnwrap(coordinator?.attempt.payload.filesExportSourceIdentities)
        XCTAssertEqual(sources.count, 2)
        XCTAssertEqual(Set(sources).count, 2, "Equal display names retain distinct owned copies")
        XCTAssertEqual(sources.map(\.lastPathComponent), ["SAME.JPG", "SAME.JPG"])
        XCTAssertNil(coordinator?.presentationFailure())
        coordinator?.finish(.reported(count: 1))
        XCTAssertEqual(exporter.result?.status, .unconfirmed)
        XCTAssertEqual(exporter.result?.expectedCount, 2)
        XCTAssertEqual(exporter.result?.reportedCount, 1)
        XCTAssertEqual(try Data(contentsOf: manifest), preparedFacts, "A partial provider report cannot alter any preparation receipt")

        await offline.clearMediaDeliveryHistory()
        XCTAssertTrue(offline.mediaDeliverySnapshot.records.isEmpty)
        XCTAssertEqual(coordinator?.attempt.payload.fileURLs, sources)
        XCTAssertEqual(coordinator?.attempt.payload.filesExportSourceIdentities, identities)
        for source in sources { XCTAssertEqual(try Data(contentsOf: source), BatchMediaPeer.original) }
        coordinator?.dismantle()
        XCTAssertTrue(sources.allSatisfy { FileManager.default.fileExists(atPath: $0.path) })
        coordinator = nil
        try await waitUntil { sources.allSatisfy { !FileManager.default.fileExists(atPath: $0.path) } }
        XCTAssertFalse(offline.connected)
        let after = await peer.requests()
        XCTAssertEqual(after, requests, "Cold history export and clear must remain entirely local")
    }

    func testStoredFilesFactoryPreservesAcquiredIdentityAcrossAwaitAndRejectsMutatedSource() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let gate = DeliveryReplyGate(stage: .shareAcquired)
        let peer = BatchMediaPeer(items: [item("FILES-REPLACED")])
        let state = makeState([peer], deliveryStore: MediaDeliveryStore(root: root), deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.startMediaDownload(try XCTUnwrap(state.mediaItems.first))
        await state.mediaDownloadTask?.value
        let record = try XCTUnwrap(state.mediaDeliverySnapshot.records.first)
        let source = root.appendingPathComponent(record.id.uuidString).appendingPathComponent(record.filename)
        let acquiredIdentity = try XCTUnwrap(MediaFilesExportSourceIdentity.read(source))
        let manifest = root.appendingPathComponent("manifest.json")
        let preparedFacts = try Data(contentsOf: manifest)
        await state.disconnect()
        let requests = await peer.requests()

        let share = Task { await state.makeStoredMediaExportPayload(ids: [record.id]) }
        try await waitUntil { await gate.waiting }
        // The store has validated and pinned the original. Replacing it during
        // the real factory's await must not be blessed by a new payload snapshot.
        try Data(repeating: 0x6D, count: BatchMediaPeer.original.count).write(to: source, options: .atomic)
        let replacementIdentity = try XCTUnwrap(MediaFilesExportSourceIdentity.read(source))
        XCTAssertNotEqual(replacementIdentity, acquiredIdentity)
        await gate.release()
        let acquiredPayload = await share.value
        let payload = try XCTUnwrap(acquiredPayload)
        XCTAssertEqual(payload.filesExportSourceIdentities, [Optional(acquiredIdentity)])
        XCTAssertFalse(MediaFilesExportController.sourcesAvailable(payload))
        let exporter = MediaFilesExportController()
        XCTAssertFalse(exporter.beginUnrecordedForTesting(payload))
        XCTAssertNil(exporter.presentation)
        XCTAssertFalse(exporter.isActive)
        XCTAssertEqual(exporter.result?.status, .sourceUnavailable)
        XCTAssertEqual(exporter.result?.expectedCount, 1)
        XCTAssertEqual(try Data(contentsOf: manifest), preparedFacts)
        let after = await peer.requests()
        XCTAssertEqual(after, requests, "Unavailable local bytes cannot trigger an implicit camera download")
    }

    func testSelectionUsesExactWireIDsAndDisplayedLoadedOrderWithoutRequests() async throws {
        for bridge in [false, true] {
            let composed = item("caf\u{00E9}", name: "SAME.JPG")
            let decomposed = item("cafe\u{0301}", name: "SAME.JPG")
            let raw = item("raw", name: "PAIR.CR3", kind: "raw")
            let jpeg = item("jpeg", name: "PAIR.JPG")
            let hidden = item("hidden", name: "HIDDEN.JPG")
            let unloaded = item("not-loaded", name: "UNLOADED.JPG")
            let peer = BatchMediaPeer(items: [composed, decomposed, raw, jpeg, hidden])
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            let before = await peer.requests()

            state.selectMediaItems([decomposed, raw, composed, jpeg, unloaded, raw])
            XCTAssertEqual(state.selectedMediaItems.map(mediaItemIdentity), [decomposed, raw, composed, jpeg].map(mediaItemIdentity))
            XCTAssertFalse(state.isMediaSelected(hidden))
            XCTAssertFalse(state.isMediaSelected(unloaded))
            state.toggleMediaSelection(composed)
            XCTAssertFalse(state.isMediaSelected(composed))
            XCTAssertTrue(state.isMediaSelected(decomposed), "Canonical-equivalent wire IDs must remain distinct")
            state.toggleMediaSelection(composed)
            XCTAssertEqual(state.selectedMediaItems.map(mediaItemIdentity), [decomposed, raw, jpeg, composed].map(mediaItemIdentity))
            state.clearMediaSelection()
            XCTAssertTrue(state.selectedMediaItems.isEmpty)
            let after = await peer.requests()
            XCTAssertEqual(after, before, "Selection must not fetch hidden pages or mutate the camera")
        }
    }

    func testSelectionSurvivesFiltersAndListingOmissionsAndUsesFreshMetadata() async throws {
        for bridge in [false, true] {
            let a = item("A", name: "OLD.JPG")
            let b = item("B", name: "HIDDEN.CR3", kind: "raw")
            let freshA = item("A", name: "FRESH.JPG", rating: 5)
            let peer = BatchMediaPeer(items: [a, b])
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            state.selectMediaItems([b, a])
            let owner = state.mediaLibrarySessionID
            XCTAssertTrue(state.setMediaRatingFilter(.five, owner: owner))
            XCTAssertTrue(state.setMediaFolderFilter(.unknown, owner: owner))
            await peer.replaceItems([freshA])
            state.startMediaLibraryLoad()
            try await waitUntil { state.mediaLibraryLoadStatus == .complete }
            XCTAssertEqual(state.selectedMediaItems.map(mediaItemIdentity), [b, a].map(mediaItemIdentity))
            XCTAssertEqual(state.selectedMediaItems.map(\.name), [b.name, freshA.name])
            XCTAssertEqual(state.selectedMediaItems.last?.rating, 5)
            await peer.replaceItems([])
            state.startMediaLibraryLoad()
            try await waitUntil { state.mediaLibraryLoadStatus == .complete }
            XCTAssertEqual(state.selectedMediaItems.count, 2, "A partial or empty inventory cannot imply deletion")
            await state.deleteMedia(b)
            XCTAssertEqual(state.selectedMediaItems.map(mediaItemIdentity), [b, a].map(mediaItemIdentity), "Unloaded deletion must skip without consuming selection")
            XCTAssertEqual(state.mediaDeleteRuns.last?.rows.first?.status, .skipped)
            await peer.replaceItems([b, freshA])
            state.startMediaLibraryLoad()
            try await waitUntil { state.mediaLibraryLoadStatus == .complete }
            await state.deleteMedia(b)
            XCTAssertEqual(state.selectedMediaItems.map(mediaItemIdentity), [mediaItemIdentity(a)])
            state.requestDisconnect()
            XCTAssertTrue(state.selectedMediaItems.isEmpty)
        }
    }

    func testExactUnicodeMetadataAndRAWJPEGRemainDistinctThroughDownload() async throws {
        for bridge in [false, true] {
            let composed = item("caf\u{00E9}", name: "OLD.JPG")
            let decomposed = item("cafe\u{0301}", name: "PAIR.JPG")
            let fresh = item(composed.id, name: "FRESH.JPG", rating: 5)
            let raw = item("raw", name: "PAIR.CR3", kind: "raw")
            let peer = BatchMediaPeer(items: [composed, decomposed, raw])
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            state.selectMediaItems([decomposed, raw, composed])
            await peer.replaceItems([fresh])
            state.startMediaLibraryLoad()
            try await waitUntil { state.mediaLibraryLoadStatus == .complete }
            XCTAssertEqual(state.selectedMediaItems.map(\.name), [decomposed.name, raw.name, fresh.name])
            XCTAssertTrue(state.startMediaBatchDownload())
            await state.mediaBatchTask?.value
            XCTAssertEqual(state.mediaBatchState, .completed)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .ready, .ready])
            let downloads = await peer.downloadIDs()
            XCTAssertEqual(downloads, [decomposed, raw, composed].map(mediaItemIdentity))
            XCTAssertEqual(state.makeMediaBatchExportPayload()?.fileURLs.count, 3)
        }
    }

    func testRecentBoundaryCannotSelectAnUnloadedSixtyFirstItem() async throws {
        let items = (0..<65).map { item("item-\($0)") }
        let peer = BatchMediaPeer(items: items)
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        XCTAssertEqual(state.mediaItems.count, 60)
        XCTAssertTrue(state.mediaLibraryHasMore)
        let before = await peer.requests()
        state.selectMediaItems([items[59], items[60]])
        XCTAssertEqual(state.selectedMediaItems.map(mediaItemIdentity), [mediaItemIdentity(items[59])])
        let after = await peer.requests()
        XCTAssertEqual(after, before)
        state.setMediaLibraryScope(.all)
        try await waitUntil { state.mediaLibraryLoadStatus == .complete }
        state.toggleMediaSelection(items[64])
        state.setMediaLibraryScope(.recent)
        XCTAssertEqual(state.mediaItems.count, 60)
        XCTAssertEqual(state.selectedMediaItems.map(mediaItemIdentity), [items[59], items[64]].map(mediaItemIdentity))
    }

    func testMetadataMutationAndDeletePreserveCanonicalEquivalentSiblingRowsAndSelection() async throws {
        for bridge in [false, true] {
            let composed = item("caf\u{00E9}", name: "COMPOSED.JPG")
            let decomposed = item("cafe\u{0301}", name: "DECOMPOSED.JPG")
            let expectedIDs = [composed, decomposed].map(mediaItemIdentity)
            let peer = BatchMediaPeer(items: [composed, decomposed])
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            state.selectMediaItems(state.mediaItems)
            let latestIdentity = state.latestMediaItem.map(mediaItemIdentity)

            await state.setMediaRating(composed, rating: 5, owner: state.mediaLibrarySessionID)
            XCTAssertNil(state.lastError)
            XCTAssertEqual(state.mediaItems.map(mediaItemIdentity), expectedIDs)
            XCTAssertEqual(state.selectedMediaItems.map(mediaItemIdentity), expectedIDs)
            XCTAssertEqual(state.mediaItems.map(\.rating), [5, 0])
            XCTAssertEqual(state.selectedMediaItems.map(\.rating), [5, 0])
            XCTAssertEqual(state.latestMediaItem.map(mediaItemIdentity), latestIdentity)

            await state.setMediaProtection(decomposed, enabled: true, owner: state.mediaLibrarySessionID)
            XCTAssertNil(state.lastError)
            XCTAssertEqual(state.mediaItems.map(mediaItemIdentity), expectedIDs)
            XCTAssertEqual(state.mediaItems.map { $0.protected == true }, [false, true])
            XCTAssertEqual(state.selectedMediaItems.map { $0.protected == true }, [false, true])
            XCTAssertEqual(state.latestMediaItem.map(mediaItemIdentity), latestIdentity)

            let protectedSibling = state.mediaItems[1]
            await peer.replaceItems([item(composed.id, name: composed.name, rating: 2), protectedSibling])
            await state.loadMediaInfo(state.mediaItems[0])
            XCTAssertNil(state.lastError)
            XCTAssertEqual(state.mediaItems.map(mediaItemIdentity), expectedIDs)
            XCTAssertEqual(state.selectedMediaItems.map(mediaItemIdentity), expectedIDs)
            XCTAssertEqual(state.mediaItems.map(\.rating), [2, 0])
            XCTAssertEqual(state.mediaItems[1], protectedSibling)

            state.startMediaDownload(protectedSibling)
            await state.mediaDownloadTask?.value
            let siblingFile = try XCTUnwrap(state.downloadedFile(for: protectedSibling))
            await state.deleteMedia(state.mediaItems[0])
            XCTAssertNil(state.lastError)
            XCTAssertEqual(state.mediaItems.map(mediaItemIdentity), [mediaItemIdentity(decomposed)])
            XCTAssertEqual(state.selectedMediaItems.map(mediaItemIdentity), [mediaItemIdentity(decomposed)])
            XCTAssertEqual(state.mediaItems.first, protectedSibling)
            XCTAssertTrue(state.isMediaSelected(decomposed))
            XCTAssertFalse(state.isMediaSelected(composed))
            XCTAssertEqual(state.downloadedFile(for: protectedSibling), siblingFile)
            XCTAssertTrue(FileManager.default.fileExists(atPath: siblingFile.path))
        }
    }

    func testSequentialQueueFreezesSelectionAndStopsAtFirstFailure() async throws {
        for bridge in [false, true] {
            let a = item("A", name: "A.JPG")
            let b = item("B", name: "B.CR3", kind: "raw")
            let c = item("C", name: "C.JPG")
            let peer = BatchMediaPeer(items: [a, b, c])
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            state.selectMediaItems([a, b, c])
            await peer.enqueueDownload(gate: "first")
            await peer.enqueueDownload(gate: "second", error: .networkConnectionLost)
            XCTAssertTrue(state.startMediaBatchDownload())
            let task = try XCTUnwrap(state.mediaBatchTask)
            try await waitForGate("first", peer: peer)
            XCTAssertNil(state.makeMediaBatchExportPayload(), "An in-flight file is never exportable")
            XCTAssertTrue(state.isBusy(.media))
            XCTAssertFalse(state.startMediaBatchDownload())
            state.startMediaDownload(c)
            XCTAssertNil(state.mediaDownloadTask)
            state.startMediaLibraryLoad()
            XCTAssertFalse(state.mediaLibraryLoading)
            state.clearMediaSelection()
            state.toggleMediaSelection(c)
            let firstIDs = await peer.downloadIDs()
            XCTAssertEqual(firstIDs, [Data(a.id.utf8)])
            await peer.release("first")
            try await waitForGate("second", peer: peer)
            XCTAssertEqual(state.mediaBatchResults.map(\.item.name), [a.name, b.name, c.name])
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .downloading, .notStarted])
            let ready = try XCTUnwrap(state.mediaBatchResults.first?.completedFileURL)
            XCTAssertTrue(FileManager.default.fileExists(atPath: ready.path))
            XCTAssertEqual(state.makeMediaBatchExportPayload()?.fileURLs, [ready], "Only the completed subset is exportable")
            await peer.release("second")
            await task.value
            XCTAssertEqual(state.mediaBatchState, .failed)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .failed, .notStarted])
            XCTAssertNotNil(state.mediaBatchResults[1].errorMessage)
            XCTAssertEqual(state.makeMediaBatchExportPayload()?.fileURLs, [ready])
            XCTAssertTrue(FileManager.default.fileExists(atPath: ready.path))
            XCTAssertFalse(state.isBusy(.media))
            let downloads = await peer.downloadIDs()
            let maximum = await peer.maximumConcurrentDownloads()
            XCTAssertEqual(downloads, [a, b].map(mediaItemIdentity))
            XCTAssertEqual(maximum, 1)
        }
    }

    func testCancelBeforeEntrySendsNoDownloadAndDoesNotInventStartedFiles() async throws {
        let peer = BatchMediaPeer(items: [item("A"), item("B")])
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        let task = try XCTUnwrap(state.mediaBatchTask)
        state.cancelMediaBatchDownload()
        await task.value
        XCTAssertEqual(state.mediaBatchState, .cancelled)
        XCTAssertEqual(state.mediaBatchResults.map(\.status), [.notStarted, .notStarted])
        XCTAssertNil(state.makeMediaBatchExportPayload())
        XCTAssertFalse(state.isBusy(.media))
        let downloads = await peer.downloadIDs()
        XCTAssertTrue(downloads.isEmpty)
    }

    func testCancelBetweenFilesKeepsReadyOriginalAndDoesNotStartNext() async throws {
        for bridge in [false, true] {
            let peer = BatchMediaPeer(items: [item("A"), item("B")])
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            state.selectMediaItems(state.mediaItems)
            let observation = state.$mediaBatchResults.sink { results in
                if results.first?.status == .ready { state.cancelMediaBatchDownload() }
            }
            XCTAssertTrue(state.startMediaBatchDownload())
            let task = try XCTUnwrap(state.mediaBatchTask)
            await task.value
            withExtendedLifetime(observation) {}
            XCTAssertEqual(state.mediaBatchState, .cancelled)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .notStarted])
            XCTAssertEqual(state.makeMediaBatchExportPayload()?.fileURLs.count, 1)
            let downloads = await peer.downloadIDs()
            XCTAssertEqual(downloads.count, 1)
        }
    }

    func testCancelNonCooperativeInFlightStopsQueueAndHoldsBusyUntilSettled() async throws {
        for bridge in [false, true] {
            let peer = BatchMediaPeer(items: [item("A"), item("B"), item("C")])
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            state.selectMediaItems(state.mediaItems)
            await peer.enqueueDownload()
            await peer.enqueueDownload(gate: "cancel")
            XCTAssertTrue(state.startMediaBatchDownload())
            let task = try XCTUnwrap(state.mediaBatchTask)
            try await waitForGate("cancel", peer: peer)
            state.cancelMediaBatchDownload()
            XCTAssertTrue(state.mediaBatchCancellationRequested)
            XCTAssertTrue(state.isBusy(.media))
            XCTAssertFalse(state.startMediaBatchDownload())
            await peer.release("cancel")
            await task.value
            XCTAssertEqual(state.mediaBatchState, .cancelled)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .cancelledOrUnknown, .notStarted])
            XCTAssertNil(state.mediaBatchResults[1].completedFileURL)
            XCTAssertEqual(state.makeMediaBatchExportPayload()?.fileURLs.count, 1)
            XCTAssertFalse(state.isBusy(.media))
            let downloads = await peer.downloadIDs()
            let temporaryFiles = await peer.returnedTemporaryFiles()
            XCTAssertEqual(downloads.count, 2)
            XCTAssertTrue(temporaryFiles.allSatisfy { !FileManager.default.fileExists(atPath: $0.path) })
        }
    }

    func testRetiredSuccessErrorProgressAndDeferCannotAffectSameIDReplacementBatch() async throws {
        for bridge in [false, true] {
            for failure in [false, true] {
                let root = singleDeliveryRoot()
                defer { try? FileManager.default.removeItem(at: root) }
                let store = MediaDeliveryStore(root: root)
                let oldPeer = BatchMediaPeer(items: [item("collision", name: "OLD.JPG"), item("NEVER")])
                let newPeer = BatchMediaPeer(items: [item("collision", name: "NEW.JPG")])
                let state = makeState([oldPeer, newPeer], bridge: bridge, deliveryStore: store)
                defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
                try await connectAndLoad(state)
                state.selectMediaItems(state.mediaItems)
                await oldPeer.enqueueDownload(gate: "retired", error: failure ? .networkConnectionLost : nil)
                XCTAssertTrue(state.startMediaBatchDownload())
                let oldTask = try XCTUnwrap(state.mediaBatchTask)
                try await waitForGate("retired", peer: oldPeer)
                await state.disconnect()
                try await connectAndLoad(state)
                XCTAssertTrue(state.selectedMediaItems.isEmpty)
                state.selectMediaItems(state.mediaItems)
                await newPeer.enqueueDownload(gate: "replacement")
                XCTAssertTrue(state.startMediaBatchDownload())
                let newTask = try XCTUnwrap(state.mediaBatchTask)
                try await waitForGate("replacement", peer: newPeer)
                let replacementID = state.mediaBatchResults[0].id
                await oldPeer.emitProgress(download: 0, bytes: 999)
                await oldPeer.release("retired")
                await oldTask.value
                XCTAssertEqual(state.mediaBatchResults[0].id, replacementID)
                XCTAssertEqual(state.mediaBatchResults[0].item.name, "NEW.JPG")
                XCTAssertEqual(state.mediaBatchResults[0].status, .downloading)
                XCTAssertEqual(state.mediaBatchState, .running)
                XCTAssertNotEqual(state.mediaBatchProgress?.bytesTransferred, 999)
                XCTAssertNil(state.lastError)
                XCTAssertTrue(state.isBusy(.media), "Old defer cannot release the replacement's MEDIA ownership")
                XCTAssertNotNil(state.mediaBatchTask)
                let retiredHistory = await store.snapshot()
                XCTAssertEqual(retiredHistory.plannedBatches[0].rows.map { $0.receipt?.status }, [.cancelled, nil])
                XCTAssertEqual(retiredHistory.plannedBatches[1].rows[0].receipt?.status, .started)
                let oldDownloads = await oldPeer.downloadIDs()
                XCTAssertEqual(oldDownloads, [Data("collision".utf8)])
                await newPeer.release("replacement")
                await newTask.value
                XCTAssertEqual(state.mediaBatchState, .completed)
            }
        }
    }

    func testDelayedPreviousItemProgressCannotBecomeCurrentProgress() async throws {
        let peer = BatchMediaPeer(items: [item("A"), item("B")])
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        await peer.enqueueDownload()
        await peer.enqueueDownload(gate: "second")
        XCTAssertTrue(state.startMediaBatchDownload())
        let task = try XCTUnwrap(state.mediaBatchTask)
        try await waitForGate("second", peer: peer)
        await peer.emitProgress(download: 1, bytes: 2)
        try await waitUntil { state.mediaBatchProgress?.bytesTransferred == 2 }
        await peer.emitProgress(download: 0, bytes: 999)
        // A newer same-item callback provides a MainActor drain point after A's
        // queued callback, while proving B is still the current progress owner.
        await peer.emitProgress(download: 1, bytes: 3)
        try await waitUntil { state.mediaBatchProgress?.bytesTransferred == 3 }
        XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .downloading])
        await peer.release("second")
        await task.value
        XCTAssertEqual(state.mediaBatchState, .completed)
    }

    func testExportLeaseSurvivesNewBatchAndDisconnectThenCleansOnlyOwnedDirectory() async throws {
        let peer = BatchMediaPeer(items: [item("A", name: "SAME.JPG"), item("B", name: "SAME.JPG")])
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        await state.mediaBatchTask?.value
        var payload = state.makeMediaBatchExportPayload()
        let urls = try XCTUnwrap(payload?.fileURLs)
        XCTAssertEqual(urls.count, 2)
        XCTAssertNotEqual(urls[0], urls[1], "Duplicate camera filenames cannot overwrite each other")
        XCTAssertEqual(urls.map(\.lastPathComponent), ["SAME.JPG", "SAME.JPG"])
        let sibling = urls[0].deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("unowned-\(UUID().uuidString)")
        try Data("keep".utf8).write(to: sibling)
        defer { try? FileManager.default.removeItem(at: sibling) }

        XCTAssertTrue(state.startMediaBatchDownload())
        let next = try XCTUnwrap(state.mediaBatchTask)
        state.cancelMediaBatchDownload()
        await next.value
        XCTAssertTrue(urls.allSatisfy { FileManager.default.fileExists(atPath: $0.path) })
        state.requestDisconnect()
        XCTAssertTrue(urls.allSatisfy { FileManager.default.fileExists(atPath: $0.path) })
        XCTAssertEqual(payload?.fileURLs, urls, "Export is an immutable snapshot after state reset")
        payload = nil
        XCTAssertTrue(urls.allSatisfy { !FileManager.default.fileExists(atPath: $0.deletingLastPathComponent().path) })
        XCTAssertTrue(FileManager.default.fileExists(atPath: sibling.path), "Lease cleanup cannot remove the shared parent")
    }

    func testUnsafeNamesCannotEscapeOwnedDirectoryAndExistingDestinationIsPreserved() async throws {
        let names = ["../../outside.JPG", "..\\..\\outside.CR3", ".", "..", "", "/", "bad\u{0}name.JPG", String(repeating: "相", count: 300)]
        for name in names {
            let owned = try MediaBatchOwnedFile.create(filename: name)
            XCTAssertFalse(owned.fileURL.lastPathComponent.contains("/"))
            XCTAssertFalse(owned.fileURL.lastPathComponent.contains("\\"))
            XCTAssertFalse(owned.fileURL.lastPathComponent.contains("\u{0}"))
            XCTAssertLessThanOrEqual(owned.fileURL.lastPathComponent.utf8.count, 200)
            XCTAssertNotEqual(owned.fileURL.lastPathComponent, ".")
            XCTAssertNotEqual(owned.fileURL.lastPathComponent, "..")
            XCTAssertNotNil(UUID(uuidString: owned.fileURL.deletingLastPathComponent().lastPathComponent))
        }
        let directoryID = UUID()
        let firstOwner = try MediaBatchOwnedFile.create(filename: "ORIGINAL.JPG", directoryID: directoryID)
        let originalBytes = Data("keep existing directory".utf8)
        try originalBytes.write(to: firstOwner.fileURL)
        XCTAssertThrowsError(try MediaBatchOwnedFile.create(filename: "OTHER.JPG", directoryID: directoryID)) { error in
            XCTAssertEqual((error as? POSIXError)?.code, .EEXIST)
        }
        XCTAssertEqual(try Data(contentsOf: firstOwner.fileURL), originalBytes)
        for bridge in [false, true] {
            let peer = BatchMediaPeer(items: [item("A")])
            let session: CameraSession = bridge
                ? .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
                : .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
            _ = try await session.connectSnapshot()
            let owned = try MediaBatchOwnedFile.create(filename: "EXISTING.JPG")
            let original = Data("existing bytes".utf8)
            try original.write(to: owned.fileURL)
            do {
                _ = try await session.downloadMedia(item("A"), to: owned.fileURL)
                XCTFail("An existing file must never be overwritten")
            } catch { /* Production clients reject before the transport request. */ }
            XCTAssertEqual(try Data(contentsOf: owned.fileURL), original)
            let downloads = await peer.downloadIDs()
            XCTAssertTrue(downloads.isEmpty)
            _ = await session.closeWithShutterReleaseState()
        }
    }

    func testLibraryLoadAndOfflinePreviewCannotStartBatch() async throws {
        let peer = BatchMediaPeer(items: [item("A")])
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        await peer.holdNextListing("library")
        state.startMediaLibraryLoad()
        try await waitForGate("library", peer: peer)
        XCTAssertFalse(state.canStartMediaBatchDownload)
        XCTAssertFalse(state.startMediaBatchDownload())
        await peer.release("library")
        try await waitUntil { state.mediaLibraryLoadStatus == .complete }
        let downloads = await peer.downloadIDs()
        XCTAssertTrue(downloads.isEmpty)
        state.openOfflinePreview()
        state.selectMediaItems(state.mediaItems)
        XCTAssertFalse(state.selectedMediaItems.isEmpty)
        XCTAssertFalse(state.canStartMediaBatchDownload)
        XCTAssertFalse(state.startMediaBatchDownload())
        XCTAssertNil(state.makeMediaBatchExportPayload(), "Preview cannot fabricate an exported original")
    }

    func testPreparedHistoryReopensOfflineWithoutCameraRequestsAndClearKeepsActiveExport() async throws {
        for bridge in [false, true] {
            let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("DeliveryIntegration-\(UUID().uuidString)")
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root)
            let peer = BatchMediaPeer(items: [item("A", name: "ORIGINAL.JPG")])
            let state = makeState([peer], bridge: bridge, deliveryStore: store)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            state.selectMediaItems(state.mediaItems)
            XCTAssertTrue(state.startMediaBatchDownload())
            await state.mediaBatchTask?.value
            XCTAssertEqual(state.mediaBatchState, .completed)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready])
            let originalPayload = try XCTUnwrap(state.makeMediaBatchExportPayload())
            await state.refreshMediaDeliveryHistory()
            XCTAssertFalse(state.mediaDeliveryHistoryWarning)
            XCTAssertEqual(state.mediaDeliverySnapshot.records.count, 1)
            let record = try XCTUnwrap(state.mediaDeliverySnapshot.records.first)
            XCTAssertEqual(record.status, .prepared)
            XCTAssertEqual(record.availability, .available)
            await state.disconnect()
            let requests = await peer.requests()
            XCTAssertTrue(state.mediaBatchResults.isEmpty)

            let offline = makeState([], deliveryStore: MediaDeliveryStore(root: root))
            await offline.refreshMediaDeliveryHistory()
            XCTAssertFalse(offline.connected)
            XCTAssertEqual(offline.mediaDeliverySnapshot.records.map(\.id), [record.id])
            let payload = await offline.makeStoredMediaExportPayload(ids: [record.id])
            let retained = try XCTUnwrap(payload)
            XCTAssertEqual(try Data(contentsOf: retained.fileURLs[0]), BatchMediaPeer.original)
            await offline.clearMediaDeliveryHistory()
            XCTAssertTrue(offline.mediaDeliverySnapshot.records.isEmpty)
            XCTAssertEqual(try Data(contentsOf: retained.fileURLs[0]), BatchMediaPeer.original,
                "A presented exporter retains its own source through confirmed history clearing")
            XCTAssertEqual(try Data(contentsOf: originalPayload.fileURLs[0]), BatchMediaPeer.original,
                "History owns a separate retained copy, not the current temporary original")
            let after = await peer.requests()
            XCTAssertEqual(after, requests, "Offline recovery and sharing must not issue camera requests")
        }
    }

    func testUnavailableHistoryNeverTurnsValidOriginalIntoFailedDownload() async throws {
        let parent = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("MissingDeliveryParent-\(UUID().uuidString)")
        let store = MediaDeliveryStore(root: parent.appendingPathComponent("not-created"))
        for unavailable in [nil, store] as [MediaDeliveryStore?] {
            let peer = BatchMediaPeer(items: [item("A")])
            let state = makeState([peer], deliveryStore: unavailable)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() }; try? FileManager.default.removeItem(at: parent) }
            try await connectAndLoad(state)
            state.selectMediaItems(state.mediaItems)
            XCTAssertTrue(state.startMediaBatchDownload())
            await state.mediaBatchTask?.value
            XCTAssertEqual(state.mediaBatchState, .completed)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready])
            XCTAssertTrue(state.mediaDeliveryHistoryWarning)
            XCTAssertTrue(state.mediaBatchWorklistWarning)
            let payload = try XCTUnwrap(state.makeMediaBatchExportPayload())
            XCTAssertEqual(try Data(contentsOf: payload.fileURLs[0]), BatchMediaPeer.original)
            let downloads = await peer.downloadIDs()
            XCTAssertEqual(downloads, [Data("A".utf8)])
        }
    }

    func testWholeSelectedRosterIsDurableBeforeFirstGETAndCancellationLeavesEveryRowQueued() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("WholeWorklist-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let selected = (0..<50).map { item("ROW-\($0)", name: "PHOTO-\($0).JPG") }
        let peer = BatchMediaPeer(items: selected)
        let gate = DeliveryReplyGate(stage: .batchPlanned)
        let store = MediaDeliveryStore(root: root)
        let state = makeState([peer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(selected)
        XCTAssertTrue(state.startMediaBatchDownload())
        let task = try XCTUnwrap(state.mediaBatchTask)
        try await waitUntil { await gate.waiting }
        let before = await peer.downloadIDs()
        XCTAssertTrue(before.isEmpty)
        let cold = await MediaDeliveryStore(root: root).snapshot()
        let plan = try XCTUnwrap(cold.plannedBatches.first)
        XCTAssertEqual(plan.rows.map(\.filename), selected.map(\.name))
        XCTAssertEqual(Set(plan.rows.map(\.id)).count, 50)
        XCTAssertTrue(plan.rows.allSatisfy { $0.receipt == nil })
        XCTAssertTrue(cold.records.isEmpty)
        state.cancelMediaBatchDownload()
        await gate.release()
        await task.value
        XCTAssertEqual(state.mediaBatchState, .cancelled)
        XCTAssertTrue(state.mediaBatchResults.allSatisfy { $0.status == .notStarted })
        let final = await MediaDeliveryStore(root: root).snapshot()
        XCTAssertEqual(final.plannedBatches.first?.rows.map(\.id), plan.rows.map(\.id))
        XCTAssertTrue(final.records.isEmpty)
        let after = await peer.downloadIDs()
        XCTAssertTrue(after.isEmpty)
    }

    func testReopenedWorklistDuringSixthOriginalPreservesFivePreparedOneUnknownAndFortyFourQueued() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("WorklistSixth-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let selected = (0..<50).map { item("ROW-\($0)") }
        let peer = BatchMediaPeer(items: selected)
        let store = MediaDeliveryStore(root: root)
        let state = makeState([peer], deliveryStore: store)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(selected)
        for _ in 0..<5 { await peer.enqueueDownload() }
        await peer.enqueueDownload(gate: "sixth")
        XCTAssertTrue(state.startMediaBatchDownload())
        let task = try XCTUnwrap(state.mediaBatchTask)
        try await waitForGate("sixth", peer: peer)
        let requests = await peer.requests()
        // Reconstructing a fresh store proves manifest behavior, not an OS kill.
        let offline = makeState([], deliveryStore: MediaDeliveryStore(root: root))
        await offline.refreshMediaDeliveryHistory()
        let rows = try XCTUnwrap(offline.mediaDeliverySnapshot.plannedBatches.first?.rows)
        XCTAssertEqual(rows.count, 50)
        XCTAssertEqual(rows.map(\.filename), selected.map(\.name))
        XCTAssertTrue(rows.prefix(5).allSatisfy { $0.receipt?.status == .prepared && $0.receipt?.availability == .available })
        XCTAssertEqual(rows[5].receipt?.status, .unknown)
        XCTAssertTrue(rows.dropFirst(6).allSatisfy { $0.receipt == nil })
        let downloads = await peer.downloadIDs()
        XCTAssertEqual(downloads, selected.prefix(6).map(mediaItemIdentity))
        let afterRead = await peer.requests()
        XCTAssertEqual(afterRead, requests, "Offline projection cannot query or resume the source")
        state.cancelMediaBatchDownload()
        await peer.release("sixth")
        await task.value
        let settled = await store.snapshot()
        XCTAssertEqual(settled.plannedBatches.first?.rows[5].receipt?.status, .cancelled)
        XCTAssertTrue(settled.plannedBatches.first!.rows.dropFirst(6).allSatisfy { $0.receipt == nil })
        let finalDownloads = await peer.downloadIDs()
        XCTAssertEqual(finalDownloads, downloads)
    }

    func testPlanAdmissionFailureKeepsManualFilesButCannotClaimACompleteRecoverableRoster() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("WorklistCapacity-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root, limits: .init(maximumRecords: 1, maximumRetainedBytes: 100))
        let peer = BatchMediaPeer(items: [item("A"), item("B")])
        let state = makeState([peer], deliveryStore: store)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        await state.mediaBatchTask?.value
        XCTAssertEqual(state.mediaBatchState, .completed)
        XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .ready])
        XCTAssertTrue(state.mediaBatchWorklistWarning)
        XCTAssertTrue(state.mediaDeliveryHistoryWarning)
        XCTAssertEqual(state.makeMediaBatchExportPayload()?.fileURLs.count, 2)
        let snapshot = await store.snapshot()
        XCTAssertTrue(snapshot.plannedBatches.isEmpty)
        XCTAssertTrue(snapshot.records.isEmpty, "Do not silently retain a truncated selection")
        let downloads = await peer.downloadIDs()
        XCTAssertEqual(downloads, [Data("A".utf8), Data("B".utf8)])
    }

    func testPlannedItemCommitFailureStopsBeforeGETAndLeavesTruthfulQueuedRows() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("WorklistAdmission-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let fault = WorklistCommitFault()
        let store = MediaDeliveryStore(root: root, faultInjector: { try fault.check($0) })
        let gate = DeliveryReplyGate(stage: .batchPlanned)
        let peer = BatchMediaPeer(items: [item("A"), item("B")])
        let state = makeState([peer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        let task = try XCTUnwrap(state.mediaBatchTask)
        try await waitUntil { await gate.waiting }
        fault.arm()
        await gate.release()
        await task.value
        XCTAssertEqual(state.mediaBatchState, .failed)
        XCTAssertEqual(state.mediaBatchResults.map(\.status), [.notStarted, .notStarted])
        XCTAssertTrue(state.mediaBatchWorklistWarning)
        let downloads = await peer.downloadIDs()
        XCTAssertTrue(downloads.isEmpty)
        let cold = await MediaDeliveryStore(root: root).snapshot()
        XCTAssertEqual(cold.plannedBatches.first?.rows.count, 2)
        XCTAssertTrue(cold.plannedBatches.first!.rows.allSatisfy { $0.receipt == nil })
        XCTAssertTrue(cold.records.isEmpty)
    }

    func testLateWholePlanReplyCannotDownloadOrOverwriteReplacementBatch() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("WorklistRetired-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let gate = DeliveryReplyGate(stage: .batchPlanned)
        let oldPeer = BatchMediaPeer(items: [item("OLD")])
        let newPeer = BatchMediaPeer(items: [item("NEW")])
        let state = makeState([oldPeer, newPeer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await oldPeer.releaseAll(); await newPeer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        let oldTask = try XCTUnwrap(state.mediaBatchTask)
        try await waitUntil { await gate.waiting }
        await state.disconnect()
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        await state.mediaBatchTask?.value
        XCTAssertEqual(state.mediaBatchResults.map(\.item.id), ["NEW"])
        XCTAssertEqual(state.mediaBatchState, .completed)
        await gate.release()
        await oldTask.value
        XCTAssertEqual(state.mediaBatchResults.map(\.item.id), ["NEW"])
        XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready])
        XCTAssertEqual(state.mediaBatchState, .completed)
        XCTAssertFalse(state.isBusy(.media))
        XCTAssertFalse(state.mediaBatchWorklistWarning)
        let oldDownloads = await oldPeer.downloadIDs()
        let newDownloads = await newPeer.downloadIDs()
        XCTAssertTrue(oldDownloads.isEmpty)
        XCTAssertEqual(newDownloads, [Data("NEW".utf8)])
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.plannedBatches.count, 2)
        let oldRow = try XCTUnwrap(snapshot.plannedBatches.flatMap(\.rows).first { $0.filename == "OLD.JPG" })
        let newRow = try XCTUnwrap(snapshot.plannedBatches.flatMap(\.rows).first { $0.filename == "NEW.JPG" })
        XCTAssertNil(oldRow.receipt)
        XCTAssertEqual(newRow.receipt?.status, .prepared)
    }

    func testConfirmedPlanClearKeepsManualBatchButNeverRebuildsItsWorklist() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("WorklistClear-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let peer = BatchMediaPeer(items: [item("A"), item("B")])
        let state = makeState([peer], deliveryStore: store)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        await peer.enqueueDownload(gate: "clear-first")
        XCTAssertTrue(state.startMediaBatchDownload())
        let task = try XCTUnwrap(state.mediaBatchTask)
        try await waitForGate("clear-first", peer: peer)
        await state.clearMediaDeliveryHistory()
        XCTAssertTrue(state.mediaDeliverySnapshot.plannedBatches.isEmpty)
        await peer.release("clear-first")
        await task.value
        await state.refreshMediaDeliveryHistory()
        XCTAssertEqual(state.mediaBatchState, .completed)
        XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .ready])
        XCTAssertTrue(state.mediaBatchWorklistWarning)
        XCTAssertTrue(state.mediaDeliverySnapshot.plannedBatches.isEmpty)
        XCTAssertTrue(state.mediaDeliverySnapshot.records.isEmpty)
        XCTAssertEqual(state.makeMediaBatchExportPayload()?.fileURLs.count, 2)
        let downloads = await peer.downloadIDs()
        XCTAssertEqual(downloads, [Data("A".utf8), Data("B".utf8)])
    }

    func testClearedHistoryCannotBeRecreatedByPendingBatchCompletion() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("DeliveryClear-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let peer = BatchMediaPeer(items: [item("A")])
        let state = makeState([peer], deliveryStore: store)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        await peer.enqueueDownload(gate: "history-clear")
        XCTAssertTrue(state.startMediaBatchDownload())
        let task = try XCTUnwrap(state.mediaBatchTask)
        try await waitForGate("history-clear", peer: peer)
        await state.refreshMediaDeliveryHistory()
        XCTAssertEqual(state.mediaDeliverySnapshot.records.count, 1)
        await state.clearMediaDeliveryHistory()
        XCTAssertTrue(state.mediaDeliverySnapshot.records.isEmpty)
        await peer.release("history-clear")
        await task.value
        await state.refreshMediaDeliveryHistory()
        XCTAssertTrue(state.mediaDeliverySnapshot.records.isEmpty)
        XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready])
        XCTAssertNotNil(state.makeMediaBatchExportPayload())
        let downloads = await peer.downloadIDs()
        XCTAssertEqual(downloads, [Data("A".utf8)])
    }

    func testCancelAfterStartedReceiptReplyCannotIssueOriginalGET() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("DeliveryStarted-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let gate = DeliveryReplyGate(stage: .itemStarted)
        let peer = BatchMediaPeer(items: [item("A")])
        let state = makeState([peer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        let task = try XCTUnwrap(state.mediaBatchTask)
        try await waitUntil { await gate.waiting }
        let cold = await MediaDeliveryStore(root: root).snapshot()
        XCTAssertEqual(cold.records.map(\.status), [.unknown], "A cold reader cannot infer completion from started")
        state.cancelMediaBatchDownload()
        await gate.release()
        await task.value
        let downloads = await peer.downloadIDs()
        XCTAssertTrue(downloads.isEmpty)
        XCTAssertEqual(state.mediaBatchState, .cancelled)
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.map(\.status), [.cancelled])
        XCTAssertEqual(snapshot.records.map(\.availability), [.notRetained])
    }

    func testRetainedReplyAfterDisconnectCannotOverwriteNewBatchUI() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("DeliveryRetired-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let gate = DeliveryReplyGate(stage: .originalRetained)
        let oldPeer = BatchMediaPeer(items: [item("OLD")])
        let newPeer = BatchMediaPeer(items: [item("NEW")])
        let state = makeState([oldPeer, newPeer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await oldPeer.releaseAll(); await newPeer.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        let oldTask = try XCTUnwrap(state.mediaBatchTask)
        try await waitUntil { await gate.waiting }
        await state.disconnect()
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        await state.mediaBatchTask?.value
        XCTAssertEqual(state.mediaBatchResults.map(\.item.id), ["NEW"])
        XCTAssertEqual(state.mediaBatchState, .completed)
        await gate.release()
        await oldTask.value
        XCTAssertEqual(state.mediaBatchResults.map(\.item.id), ["NEW"])
        XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready])
        XCTAssertEqual(state.mediaBatchState, .completed)
        XCTAssertFalse(state.isBusy(.media))
        let snapshot = await store.snapshot()
        XCTAssertEqual(Set(snapshot.records.map(\.filename)), Set(["OLD.JPG", "NEW.JPG"]))
        XCTAssertTrue(snapshot.records.allSatisfy { $0.status == .prepared && $0.availability == .available })
    }

    func testLateHistorySnapshotCannotRepopulateConfirmedClear() async throws {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("DeliverySnapshot-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let ticket = try await store.beginBatch()
        let receipt = try await store.beginItem(ticket, filename: "FAILED.JPG")
        try await store.finishFailure(ticket, receiptID: receipt, cancelled: false)
        let gate = DeliveryReplyGate(stage: .snapshot)
        let state = makeState([], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
        let oldRefresh = Task { await state.refreshMediaDeliveryHistory() }
        defer { Task { await gate.release(); await oldRefresh.value } }
        try await waitUntil { await gate.waiting }
        await state.refreshMediaDeliveryHistory()
        XCTAssertEqual(state.mediaDeliverySnapshot.records.count, 1)
        XCTAssertFalse(state.mediaDeliveryHistoryLoading)
        await state.clearMediaDeliveryHistory()
        XCTAssertTrue(state.mediaDeliverySnapshot.records.isEmpty)
        await gate.release()
        await oldRefresh.value
        XCTAssertTrue(state.mediaDeliverySnapshot.records.isEmpty)
        XCTAssertFalse(state.mediaDeliveryHistoryLoading)
    }

    func testSingleOriginalReopensOfflineAndBothShareLeasesSurviveClearUntilReleased() async throws {
        for bridge in [false, true] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let peer = BatchMediaPeer(items: [item("SINGLE")])
            let state = makeState([peer], bridge: bridge, deliveryStore: MediaDeliveryStore(root: root))
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            let original = try XCTUnwrap(state.mediaItems.first)
            state.startMediaDownload(original)
            await state.mediaDownloadTask?.value
            XCTAssertNil(state.lastError)
            XCTAssertFalse(state.mediaDeliveryHistoryWarning)
            XCTAssertTrue(state.mediaDeliverySnapshot.plannedBatches.isEmpty, "A single attempt is not a persisted selection queue")
            let record = try XCTUnwrap(state.mediaDeliverySnapshot.records.first)
            XCTAssertEqual(record.status, .prepared)
            XCTAssertEqual(record.actualBytes, Int64(BatchMediaPeer.original.count))
            XCTAssertEqual(record.availability, .available)
            var temporaryShare = state.makeMediaDownloadExportPayload(for: original)
            let temporary = try XCTUnwrap(temporaryShare?.fileURLs.first)
            await state.disconnect()
            XCTAssertNil(state.makeMediaDownloadExportPayload(for: original))
            let requests = await peer.requests()

            let offline = makeState([], deliveryStore: MediaDeliveryStore(root: root))
            await offline.refreshMediaDeliveryHistory()
            XCTAssertFalse(offline.connected)
            XCTAssertEqual(offline.mediaDeliverySnapshot.records.map(\.id), [record.id])
            var retainedShare = await offline.makeStoredMediaExportPayload(ids: [record.id])
            let retained = try XCTUnwrap(retainedShare?.fileURLs.first)
            await offline.clearMediaDeliveryHistory()
            XCTAssertTrue(offline.mediaDeliverySnapshot.records.isEmpty)
            XCTAssertEqual(try Data(contentsOf: temporary), BatchMediaPeer.original)
            XCTAssertEqual(try Data(contentsOf: retained), BatchMediaPeer.original)
            XCTAssertNotEqual(temporary, retained)
            temporaryShare = nil
            retainedShare = nil
            try await waitUntil {
                !FileManager.default.fileExists(atPath: temporary.path)
                    && !FileManager.default.fileExists(atPath: retained.path)
            }
            let after = await peer.requests()
            XCTAssertEqual(after, requests, "Offline recovery, clear and sharing add no camera requests")
        }
    }

    func testSingleOriginalUsesActualBytesRegardlessOfUnknownOrBogusListingSize() async throws {
        for bridge in [false, true] {
            for hint in [nil, 0, -1, 1, Int64.max] as [Int64?] {
                let root = singleDeliveryRoot()
                defer { try? FileManager.default.removeItem(at: root) }
                let original = CameraMediaItem(id: "single-wire-id", name: "SINGLE.JPG", kind: "image",
                    sizeBytes: hint, previewAvailable: true, contentType: "image/jpeg")
                let peer = BatchMediaPeer(items: [original])
                let store = MediaDeliveryStore(root: root, limits: .init(maximumRecords: 1, maximumRetainedBytes: 10))
                let state = makeState([peer], bridge: bridge, deliveryStore: store)
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                try await connectAndLoad(state)
                state.startMediaDownload(original)
                await state.mediaDownloadTask?.value
                XCTAssertNil(state.lastError)
                XCTAssertNotNil(state.makeMediaDownloadExportPayload(for: original))
                XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.actualBytes), [Int64(BatchMediaPeer.original.count)])
                XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.availability), [.available])
                XCTAssertFalse(state.mediaDeliveryHistoryWarning)
                let downloads = await peer.downloadIDs()
                XCTAssertEqual(downloads, [mediaItemIdentity(original)])
            }
        }
    }

    func testSingleOriginalBoundedRetentionAndRecordAdmissionNeverRejectValidTemporaryDownload() async throws {
        for byteLimit in [0, 8] as [Int64] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root, limits: .init(maximumRecords: 1, maximumRetainedBytes: byteLimit))
            let peer = BatchMediaPeer(items: [item("FIRST"), item("SECOND")])
            let state = makeState([peer], deliveryStore: store)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            for original in state.mediaItems {
                state.startMediaDownload(original)
                await state.mediaDownloadTask?.value
                let file = try XCTUnwrap(state.downloadedFile(for: original))
                XCTAssertEqual(try Data(contentsOf: file), BatchMediaPeer.original)
                XCTAssertNil(state.lastError)
                XCTAssertTrue(state.mediaDeliveryHistoryWarning)
            }
            let snapshot = await store.snapshot()
            XCTAssertEqual(snapshot.records.count, 1, "The second admission cannot evict or exceed the record limit")
            XCTAssertEqual(snapshot.records.first?.status, .prepared)
            XCTAssertEqual(snapshot.records.first?.reason, .retentionUnavailable)
            XCTAssertEqual(snapshot.records.first?.availability, .notRetained)
            XCTAssertEqual(snapshot.retainedBytes, 0)
            let downloads = await peer.downloadIDs()
            XCTAssertEqual(downloads.count, 2)
        }
    }

    func testSingleOriginalUnavailableStoreAndAdmissionCopyOrManifestFaultsKeepValidTemporaryShare() async throws {
        for failure in ["nil", "unavailable", "admission", "copy", "commit"] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let fault = SingleDeliveryFault()
            let store: MediaDeliveryStore? = failure == "nil" ? nil : MediaDeliveryStore(
                root: failure == "unavailable" ? root.appendingPathComponent("missing-parent") : root,
                faultInjector: { try fault.check($0) })
            if failure == "admission" { fault.arm(.beforeManifestReplace) }
            let peer = BatchMediaPeer(items: [item("SINGLE")])
            let state = makeState([peer], deliveryStore: store)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            let original = try XCTUnwrap(state.mediaItems.first)
            await peer.enqueueDownload(gate: "single-fault")
            state.startMediaDownload(original)
            let task = try XCTUnwrap(state.mediaDownloadTask)
            try await waitForGate("single-fault", peer: peer)
            if failure == "copy" { fault.arm(.copyChunk) }
            if failure == "commit" { fault.arm(.beforeManifestReplace) }
            await peer.release("single-fault")
            await task.value
            let payload = try XCTUnwrap(state.makeMediaDownloadExportPayload(for: original))
            XCTAssertEqual(try Data(contentsOf: payload.fileURLs[0]), BatchMediaPeer.original, failure)
            XCTAssertNil(state.lastError, failure)
            XCTAssertTrue(state.mediaDeliveryHistoryWarning, failure)
            if let store {
                let snapshot = await store.snapshot()
                if failure == "copy" {
                    XCTAssertEqual(snapshot.records.map(\.status), [.prepared])
                    XCTAssertEqual(snapshot.records.map(\.availability), [.notRetained])
                } else if failure == "commit" {
                    XCTAssertEqual(snapshot.records.map(\.status), [.started], "Unwritable manifest must not invent a durable completion")
                }
                XCTAssertTrue(snapshot.records.allSatisfy { $0.availability != .available })
            }
            let downloads = await peer.downloadIDs()
            XCTAssertEqual(downloads.count, 1)
        }
    }

    func testSingleStoreCancellationDuringAdmissionCannotStartOriginalEvenWhenCallerIsNotCancelled() async throws {
        for warmed in [false, true] {
            for failure in [SingleDeliveryFault.Failure.cancellation, .urlCancelled, .storeCancelled] {
                let root = singleDeliveryRoot()
                defer { try? FileManager.default.removeItem(at: root) }
                let fault = SingleDeliveryFault()
                let store = MediaDeliveryStore(root: root, faultInjector: { try fault.check($0) })
                // A cold beginBatch crosses load's error normalization; a warm
                // store reaches beginItem's commit directly. Both must stop GET.
                if warmed { _ = await store.snapshot() }
                fault.arm(.beforeManifestReplace, failure: failure)
                let peer = BatchMediaPeer(items: [item("SINGLE")])
                let state = makeState([peer], deliveryStore: store)
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                try await connectAndLoad(state)
                let original = try XCTUnwrap(state.mediaItems.first)
                state.startMediaDownload(original)
                let task = try XCTUnwrap(state.mediaDownloadTask)
                await task.value
                XCTAssertFalse(task.isCancelled)
                XCTAssertNil(state.lastError)
                XCTAssertNil(state.makeMediaDownloadExportPayload(for: original))
                let downloads = await peer.downloadIDs()
                XCTAssertTrue(downloads.isEmpty)
                let snapshot = await store.snapshot()
                XCTAssertTrue(snapshot.records.isEmpty)
                XCTAssertEqual(snapshot.warning, warmed ? nil : .cancelled)
            }
        }
    }

    func testSingleStoreCancellationDuringOptionalCopyKeepsPublishedOriginalReady() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let fault = SingleDeliveryFault()
        let store = MediaDeliveryStore(root: root, faultInjector: { try fault.check($0) })
        let peer = BatchMediaPeer(items: [item("SINGLE")])
        let state = makeState([peer], deliveryStore: store)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        let original = try XCTUnwrap(state.mediaItems.first)
        await peer.enqueueDownload(gate: "single-copy-cancel")
        state.startMediaDownload(original)
        let task = try XCTUnwrap(state.mediaDownloadTask)
        try await waitForGate("single-copy-cancel", peer: peer)
        fault.arm(.copyChunk, failure: .cancellation)
        await peer.release("single-copy-cancel")
        await task.value
        XCTAssertFalse(task.isCancelled)
        XCTAssertNil(state.lastError)
        XCTAssertTrue(state.mediaDeliveryHistoryWarning)
        let payload = try XCTUnwrap(state.makeMediaDownloadExportPayload(for: original))
        XCTAssertEqual(try Data(contentsOf: payload.fileURLs[0]), BatchMediaPeer.original)
        XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.status), [.prepared])
        XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.reason), [.retentionUnavailable])
        XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.availability), [.notRetained])
    }

    func testSingleEmptyAndCorruptFramingNeverPublishTemporaryOrRetainedSuccess() async throws {
        for bridge in [false, true] {
            for empty in [false, true] {
                let root = singleDeliveryRoot()
                defer { try? FileManager.default.removeItem(at: root) }
                let peer = BatchMediaPeer(items: [item("SINGLE")])
                let state = makeState([peer], bridge: bridge, deliveryStore: MediaDeliveryStore(root: root))
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                try await connectAndLoad(state)
                let original = try XCTUnwrap(state.mediaItems.first)
                await peer.enqueueDownload(body: empty ? Data() : BatchMediaPeer.original,
                    headers: ["content-type": "image/jpeg", "content-length": empty ? "0" : "100"])
                state.startMediaDownload(original)
                await state.mediaDownloadTask?.value
                XCTAssertNotNil(state.lastError)
                XCTAssertNil(state.downloadedFile(for: original))
                XCTAssertNil(state.makeMediaDownloadExportPayload(for: original))
                XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.status), [.failed])
                XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.availability), [.notRetained])
                let temporary = await peer.returnedTemporaryFiles()
                XCTAssertTrue(temporary.allSatisfy { !FileManager.default.fileExists(atPath: $0.path) })
            }
        }
    }

    func testSingleCancellationAfterAdmissionReplyCannotStartOriginalRequest() async throws {
        for stage in [MediaDeliveryReplyStage.batchStarted, .itemStarted] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root)
            let gate = DeliveryReplyGate(stage: stage)
            let peer = BatchMediaPeer(items: [item("SINGLE")])
            let state = makeState([peer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
            defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
            try await connectAndLoad(state)
            let original = try XCTUnwrap(state.mediaItems.first)
            state.startMediaDownload(original)
            let task = try XCTUnwrap(state.mediaDownloadTask)
            try await waitUntil { await gate.waiting }
            task.cancel() // A caller can cancel the task without invoking the UI action.
            XCTAssertTrue(state.isBusy(.media))
            await gate.release()
            await task.value
            let downloads = await peer.downloadIDs()
            XCTAssertTrue(downloads.isEmpty)
            XCTAssertNil(state.lastError)
            XCTAssertNil(state.downloadedFile(for: original))
            XCTAssertFalse(state.isBusy(.media))
            let snapshot = await store.snapshot()
            XCTAssertEqual(snapshot.records.map(\.status), stage == .itemStarted ? [.cancelled] : [])
        }
    }

    func testSingleCancellationDuringUncooperativeDownloadKeepsBusyAndCleansLateBytes() async throws {
        for bridge in [false, true] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let peer = BatchMediaPeer(items: [item("SINGLE")])
            let state = makeState([peer], bridge: bridge, deliveryStore: MediaDeliveryStore(root: root))
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            let original = try XCTUnwrap(state.mediaItems.first)
            await peer.enqueueDownload(gate: "single-cancel")
            state.startMediaDownload(original)
            let task = try XCTUnwrap(state.mediaDownloadTask)
            try await waitForGate("single-cancel", peer: peer)
            state.cancelMediaDownload()
            state.startMediaDownload(original)
            XCTAssertTrue(state.isBusy(.media))
            await peer.emitProgress(download: 0, bytes: 999)
            await peer.release("single-cancel")
            await task.value
            XCTAssertNil(state.lastError)
            XCTAssertNil(state.downloadedFile(for: original))
            XCTAssertNil(state.mediaDownloadProgress)
            XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.status), [.cancelled])
            let downloads = await peer.downloadIDs()
            XCTAssertEqual(downloads.count, 1)
            let temporary = await peer.returnedTemporaryFiles()
            XCTAssertTrue(temporary.allSatisfy { !FileManager.default.fileExists(atPath: $0.path) })
        }
    }

    func testSingleClearDuringAdmissionOrOriginalCannotRecreateHistory() async throws {
        for stage in [MediaDeliveryReplyStage.batchStarted, .itemStarted, .originalRetained] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root)
            let gate = DeliveryReplyGate(stage: stage)
            let peer = BatchMediaPeer(items: [item("SINGLE")])
            let state = makeState([peer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
            defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
            try await connectAndLoad(state)
            let original = try XCTUnwrap(state.mediaItems.first)
            state.startMediaDownload(original)
            let task = try XCTUnwrap(state.mediaDownloadTask)
            try await waitUntil { await gate.waiting }
            await state.clearMediaDeliveryHistory()
            await gate.release()
            await task.value
            let snapshot = await store.snapshot()
            XCTAssertTrue(snapshot.records.isEmpty)
            XCTAssertTrue(snapshot.plannedBatches.isEmpty)
            XCTAssertTrue(state.mediaDeliverySnapshot.records.isEmpty)
            XCTAssertFalse(state.mediaDeliveryHistoryWarning, "An obsolete completion must not undo clear's UI state")
            XCTAssertNotNil(state.makeMediaDownloadExportPayload(for: original))
            XCTAssertNil(state.lastError)
            let downloads = await peer.downloadIDs()
            XCTAssertEqual(downloads.count, 1)
        }
    }

    func testSingleClearWhileOriginalIsInFlightPreservesTemporaryOnlyAndDoesNotRepersist() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let peer = BatchMediaPeer(items: [item("SINGLE")])
        let state = makeState([peer], deliveryStore: store)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        let original = try XCTUnwrap(state.mediaItems.first)
        await peer.enqueueDownload(gate: "single-clear")
        state.startMediaDownload(original)
        let task = try XCTUnwrap(state.mediaDownloadTask)
        try await waitForGate("single-clear", peer: peer)
        await state.refreshMediaDeliveryHistory()
        XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.status), [.started])
        await state.clearMediaDeliveryHistory()
        await peer.release("single-clear")
        await task.value
        XCTAssertNotNil(state.makeMediaDownloadExportPayload(for: original))
        XCTAssertNil(state.lastError)
        XCTAssertTrue(state.mediaDeliverySnapshot.records.isEmpty)
        XCTAssertFalse(state.mediaDeliveryHistoryWarning)
        let cold = await MediaDeliveryStore(root: root).snapshot()
        XCTAssertTrue(cold.records.isEmpty)
        XCTAssertEqual(cold.retainedBytes, 0)
        let downloads = await peer.downloadIDs()
        XCTAssertEqual(downloads.count, 1)
    }

    func testSingleRetiredAdmissionCannotStartOldRequestOrClearNewBusyState() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let gate = DeliveryReplyGate(stage: .itemStarted)
        let oldPeer = BatchMediaPeer(items: [item("OLD")])
        let newPeer = BatchMediaPeer(items: [item("NEW")])
        let state = makeState([oldPeer, newPeer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await oldPeer.releaseAll(); await newPeer.releaseAll() } }
        try await connectAndLoad(state)
        state.startMediaDownload(try XCTUnwrap(state.mediaItems.first))
        let oldTask = try XCTUnwrap(state.mediaDownloadTask)
        try await waitUntil { await gate.waiting }
        await state.disconnect()
        try await connectAndLoad(state)
        await newPeer.enqueueDownload(gate: "single-admission-new")
        let replacement = try XCTUnwrap(state.mediaItems.first)
        state.startMediaDownload(replacement)
        let newTask = try XCTUnwrap(state.mediaDownloadTask)
        try await waitForGate("single-admission-new", peer: newPeer)
        await gate.release()
        await oldTask.value
        XCTAssertTrue(state.isBusy(.media))
        XCTAssertNotNil(state.mediaDownloadTask)
        XCTAssertNil(state.lastError)
        let oldDownloads = await oldPeer.downloadIDs()
        XCTAssertTrue(oldDownloads.isEmpty)
        await newPeer.release("single-admission-new")
        await newTask.value
        XCTAssertNotNil(state.makeMediaDownloadExportPayload(for: replacement))
        let snapshot = await store.snapshot()
        XCTAssertEqual(snapshot.records.map(\.status), [.cancelled, .prepared])
    }

    func testSingleRetainedReplyCancellationPreservesAlreadyPublishedTemporaryAndHistory() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let gate = DeliveryReplyGate(stage: .originalRetained)
        let peer = BatchMediaPeer(items: [item("SINGLE")])
        let state = makeState([peer], deliveryStore: MediaDeliveryStore(root: root), deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
        try await connectAndLoad(state)
        let original = try XCTUnwrap(state.mediaItems.first)
        state.startMediaDownload(original)
        let task = try XCTUnwrap(state.mediaDownloadTask)
        try await waitUntil { await gate.waiting }
        let file = try XCTUnwrap(state.downloadedFile(for: original))
        state.cancelMediaDownload()
        await gate.release()
        await task.value
        XCTAssertEqual(state.downloadedFile(for: original), file)
        XCTAssertEqual(try Data(contentsOf: file), BatchMediaPeer.original)
        XCTAssertNil(state.lastError)
        XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.status), [.prepared])
    }

    func testSingleLateRetainedReplyCannotReplaceNewSessionDownloadOrBusyOwner() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let gate = DeliveryReplyGate(stage: .originalRetained)
        let oldPeer = BatchMediaPeer(items: [item("SAME", name: "OLD.JPG")])
        let newPeer = BatchMediaPeer(items: [item("SAME", name: "NEW.JPG")])
        let state = makeState([oldPeer, newPeer], deliveryStore: MediaDeliveryStore(root: root),
            deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await oldPeer.releaseAll(); await newPeer.releaseAll() } }
        try await connectAndLoad(state)
        state.startMediaDownload(try XCTUnwrap(state.mediaItems.first))
        let oldTask = try XCTUnwrap(state.mediaDownloadTask)
        try await waitUntil { await gate.waiting }
        await state.disconnect()
        try await connectAndLoad(state)
        let replacement = try XCTUnwrap(state.mediaItems.first)
        await newPeer.enqueueDownload(gate: "single-new")
        state.startMediaDownload(replacement)
        let newTask = try XCTUnwrap(state.mediaDownloadTask)
        try await waitForGate("single-new", peer: newPeer)
        await oldPeer.emitProgress(download: 0, bytes: 999)
        await gate.release()
        await oldTask.value
        XCTAssertTrue(state.isBusy(.media))
        XCTAssertNotNil(state.mediaDownloadTask)
        XCTAssertNil(state.downloadedFile(for: replacement))
        XCTAssertNotEqual(state.mediaDownloadProgress?.bytesTransferred, 999)
        XCTAssertNil(state.lastError)
        await newPeer.release("single-new")
        await newTask.value
        XCTAssertEqual(state.downloadedFileName, "NEW.JPG")
        XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.status), [.prepared, .prepared])
    }

    func testSingleDelayedCompletionSnapshotCannotRepopulateConfirmedClear() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let gate = DeliveryReplyGate(stage: .snapshot)
        let peer = BatchMediaPeer(items: [item("SINGLE")])
        let state = makeState([peer], deliveryStore: MediaDeliveryStore(root: root), deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
        try await connectAndLoad(state)
        let original = try XCTUnwrap(state.mediaItems.first)
        state.startMediaDownload(original)
        let task = try XCTUnwrap(state.mediaDownloadTask)
        try await waitUntil { await gate.waiting }
        await state.refreshMediaDeliveryHistory()
        XCTAssertEqual(state.mediaDeliverySnapshot.records.map(\.status), [.prepared])
        await state.clearMediaDeliveryHistory()
        await gate.release()
        await task.value
        XCTAssertTrue(state.mediaDeliverySnapshot.records.isEmpty)
        XCTAssertFalse(state.mediaDeliveryHistoryLoading)
        XCTAssertFalse(state.isBusy(.media))
        XCTAssertNotNil(state.makeMediaDownloadExportPayload(for: original))
    }

    func testSingleObsoleteStoredShareReplyReleasesItsPinAfterClear() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let gate = DeliveryReplyGate(stage: .shareAcquired)
        let peer = BatchMediaPeer(items: [item("SINGLE")])
        let state = makeState([peer], deliveryStore: MediaDeliveryStore(root: root), deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.startMediaDownload(try XCTUnwrap(state.mediaItems.first))
        await state.mediaDownloadTask?.value
        let record = try XCTUnwrap(state.mediaDeliverySnapshot.records.first)
        let retained = root.appendingPathComponent(record.id.uuidString).appendingPathComponent(record.filename)
        await state.disconnect()
        let requests = await peer.requests()
        let share = Task { await state.makeStoredMediaExportPayload(ids: [record.id]) }
        try await waitUntil { await gate.waiting }
        await state.refreshMediaDeliveryHistory()
        await state.clearMediaDeliveryHistory()
        XCTAssertTrue(FileManager.default.fileExists(atPath: retained.path))
        await gate.release()
        let obsolete = await share.value
        XCTAssertNil(obsolete)
        try await waitUntil { !FileManager.default.fileExists(atPath: retained.path) }
        XCTAssertTrue(state.mediaDeliverySnapshot.records.isEmpty)
        let after = await peer.requests()
        XCTAssertEqual(after, requests)
    }

    func testSingleShareUsesExactWireIdentityAndOldLeaseSurvivesNextDownload() async throws {
        let composed = item("caf\u{00E9}", name: "SAME.JPG")
        let decomposed = item("cafe\u{0301}", name: "SAME.JPG")
        let peer = BatchMediaPeer(items: [composed, decomposed])
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.startMediaDownload(composed)
        await state.mediaDownloadTask?.value
        var payload = state.makeMediaDownloadExportPayload(for: composed)
        let previous = try XCTUnwrap(payload?.fileURLs.first)
        XCTAssertFalse(state.isMediaDownloaded(decomposed))
        XCTAssertNil(state.makeMediaDownloadExportPayload(for: decomposed))
        state.startMediaDownload(decomposed)
        await state.mediaDownloadTask?.value
        XCTAssertFalse(state.isMediaDownloaded(composed))
        XCTAssertTrue(state.isMediaDownloaded(decomposed))
        XCTAssertEqual(try Data(contentsOf: previous), BatchMediaPeer.original)
        payload = nil
        XCTAssertFalse(FileManager.default.fileExists(atPath: previous.path))
        XCTAssertNotNil(state.makeMediaDownloadExportPayload(for: decomposed))
    }

    func testColdFiftyRowWorklistContinuesOnlyExplicitlyMappedSubsetAndKeepsOriginalResults() async throws {
        for bridge in [false, true] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let seed = MediaDeliveryStore(root: root)
            let old = try await seed.beginPlannedBatch(filenames: (0..<50).map { "OLD-\($0).JPG" })
            for id in old.rowIDs.prefix(5) {
                let receipt = try await seed.beginPlannedItem(old.ticket, rowID: id)
                let file = try MediaBatchOwnedFile.create(filename: "fixture.JPG")
                try BatchMediaPeer.original.write(to: file.fileURL)
                try await seed.finishPrepared(old.ticket, receiptID: receipt, source: file,
                    actualBytes: Int64(BatchMediaPeer.original.count))
            }
            _ = try await seed.beginPlannedItem(old.ticket, rowID: old.rowIDs[5])
            let store = MediaDeliveryStore(root: root)
            let peer = BatchMediaPeer(items: [item("CURRENT-ONE", name: "UNRELATED.JPG"),
                item("CURRENT-TWO", name: "UNRELATED.JPG"), item("CURRENT-THREE")])
            let state = makeState([peer], bridge: bridge, deliveryStore: store)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            await state.refreshMediaDeliveryHistory()
            let prior = try XCTUnwrap(state.mediaDeliverySnapshot.plannedBatches.first)
            XCTAssertEqual(prior.rows.count, 50)
            XCTAssertTrue(prior.rows.prefix(5).allSatisfy { $0.receipt?.status == .prepared })
            XCTAssertEqual(prior.rows[5].receipt?.status, .unknown)
            XCTAssertTrue(prior.rows.dropFirst(6).allSatisfy { $0.receipt == nil })
            let origins = [5, 6, 49].map { MediaDeliveryOrigin(batchID: old.ticket.batchID, rowID: old.rowIDs[$0]) }
            let beforeChoices = await peer.requests()
            XCTAssertTrue(state.beginMediaWorklistContinuation(origins: origins))
            XCTAssertTrue(state.mediaWorklistContinuation!.rows.allSatisfy { $0.item == nil })
            XCTAssertFalse(state.canConfirmMediaWorklistContinuation)
            for (index, origin) in origins.enumerated() {
                XCTAssertTrue(state.bindMediaWorklistOriginal(origin: origin, identity: mediaItemIdentity(state.mediaItems[index])))
            }
            XCTAssertFalse(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: false, acknowledgedUnknown: true))
            XCTAssertFalse(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: false))
            let afterChoices = await peer.requests()
            XCTAssertEqual(beforeChoices, afterChoices, "Selection, mapping and rejected confirmation must perform no I/O")
            XCTAssertTrue(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: true))
            XCTAssertFalse(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: true),
                "One confirmation cannot be replayed")
            if let task = state.mediaBatchTask { try await awaitContinuationTask(task) }
            XCTAssertEqual(state.mediaBatchState, .completed)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .ready, .ready])
            let downloads = await peer.downloadIDs()
            XCTAssertEqual(downloads, state.mediaItems.map(mediaItemIdentity))
            let maximum = await peer.maximumConcurrentDownloads()
            XCTAssertEqual(maximum, 1)
            let after = await store.snapshot()
            let untouched = try XCTUnwrap(after.plannedBatches.first { $0.id == old.ticket.batchID })
            XCTAssertEqual(untouched.rows.map(\.id), prior.rows.map(\.id))
            XCTAssertEqual(untouched.rows.map { $0.receipt?.status }, prior.rows.map { $0.receipt?.status })
            XCTAssertEqual(untouched.rows.map { $0.receipt?.actualBytes }, prior.rows.map { $0.receipt?.actualBytes })
            let new = try XCTUnwrap(after.plannedBatches.first { $0.id != old.ticket.batchID })
            XCTAssertEqual(new.rows.compactMap(\.origin), origins)
            XCTAssertTrue(new.rows.allSatisfy { $0.receipt?.status == .prepared && $0.receipt?.availability == .available })
            XCTAssertTrue(Set(new.rows.map(\.id)).isDisjoint(with: old.rowIDs))
            let beforeShare = await peer.requests()
            let payload = await state.makeStoredMediaExportPayload(ids: new.rows.map(\.id))
            let share = try XCTUnwrap(payload)
            XCTAssertEqual(share.fileURLs.count, 3)
            for url in share.fileURLs { XCTAssertEqual(try Data(contentsOf: url), BatchMediaPeer.original) }
            let afterShare = await peer.requests()
            XCTAssertEqual(afterShare, beforeShare, "Prepared linked results use offline sharing")
            XCTAssertFalse(state.beginMediaWorklistContinuation(origins: origins), "A prepared linked copy must not default to another GET")
        }
    }

    func testContinuationMappingNeedsEachDistinctLoadedOriginalAndCancellationRetiresAllChoices() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let batch = try await store.beginPlannedBatch(filenames: ["SAME.JPG", "SAME.JPG"])
        let peer = BatchMediaPeer(items: [item("caf\u{00E9}", name: "SAME.JPG"), item("cafe\u{0301}", name: "SAME.JPG")])
        let state = makeState([peer], deliveryStore: store)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        await state.refreshMediaDeliveryHistory()
        let origins = batch.rowIDs.map { MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: $0) }
        let before = await peer.requests()
        XCTAssertTrue(state.beginMediaWorklistContinuation(origins: origins))
        XCTAssertFalse(state.bindMediaWorklistOriginal(origin: origins[0], identity: Data("unloaded".utf8)))
        XCTAssertTrue(state.bindMediaWorklistOriginal(origin: origins[0], identity: mediaItemIdentity(state.mediaItems[0])))
        XCTAssertFalse(state.bindMediaWorklistOriginal(origin: origins[1], identity: mediaItemIdentity(state.mediaItems[0])))
        XCTAssertFalse(state.canConfirmMediaWorklistContinuation)
        XCTAssertTrue(state.bindMediaWorklistOriginal(origin: origins[1], identity: mediaItemIdentity(state.mediaItems[1])))
        XCTAssertTrue(state.canConfirmMediaWorklistContinuation)
        state.cancelMediaWorklistContinuation()
        XCTAssertNil(state.mediaWorklistContinuation)
        XCTAssertFalse(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: true))
        XCTAssertTrue(state.beginMediaWorklistContinuation(origins: origins))
        XCTAssertTrue(state.mediaWorklistContinuation!.rows.allSatisfy { $0.item == nil })
        let after = await peer.requests()
        XCTAssertEqual(after, before)
        let persisted = await store.snapshot()
        XCTAssertEqual(persisted.plannedBatches.count, 1)
        XCTAssertTrue(persisted.records.isEmpty)
    }

    func testContinuationRejectsActiveAndPreparedRowsAndReusesAvailableCopyOffline() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let batch = try await store.beginPlannedBatch(filenames: ["ACTIVE.JPG", "PREPARED.JPG"])
        _ = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[0])
        let receipt = try await store.beginPlannedItem(batch.ticket, rowID: batch.rowIDs[1])
        let file = try MediaBatchOwnedFile.create(filename: "prepared.JPG")
        try BatchMediaPeer.original.write(to: file.fileURL)
        try await store.finishPrepared(batch.ticket, receiptID: receipt, source: file, actualBytes: Int64(BatchMediaPeer.original.count))
        let peer = BatchMediaPeer(items: [item("CURRENT")])
        let state = makeState([peer], deliveryStore: store)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        await state.refreshMediaDeliveryHistory()
        for rowID in batch.rowIDs {
            XCTAssertFalse(state.beginMediaWorklistContinuation(origins: [.init(batchID: batch.ticket.batchID, rowID: rowID)]))
        }
        let downloads = await peer.downloadIDs()
        XCTAssertTrue(downloads.isEmpty)
        let shared = await state.makeStoredMediaExportPayload(ids: [receipt])
        XCTAssertNotNil(shared)
    }

    func testContinuationCancellationAfterDurablePlanOrItemAdmissionNeverIssuesGET() async throws {
        for stage in [MediaDeliveryReplyStage.batchPlanned, .itemStarted] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root)
            let old = try await store.beginPlannedBatch(filenames: ["OLD.JPG"])
            let gate = DeliveryReplyGate(stage: stage)
            let peer = BatchMediaPeer(items: [item("CURRENT")])
            let state = makeState([peer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
            defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
            try await connectAndLoad(state)
            await state.refreshMediaDeliveryHistory()
            try bindContinuation(state, batch: old)
            XCTAssertTrue(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: false))
            let task = try XCTUnwrap(state.mediaBatchTask)
            try await waitUntil { await gate.waiting }
            let cold = await MediaDeliveryStore(root: root).snapshot()
            XCTAssertEqual(cold.plannedBatches.count, 2, "The full linked roster must be durable before any GET")
            state.cancelMediaBatchDownload()
            await gate.release()
            try await awaitContinuationTask(task)
            XCTAssertEqual(state.mediaBatchState, .cancelled)
            XCTAssertNil(state.mediaWorklistContinuation)
            XCTAssertFalse(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: true))
            let downloads = await peer.downloadIDs()
            XCTAssertTrue(downloads.isEmpty)
            let final = await store.snapshot()
            XCTAssertNil(final.plannedBatches.first?.rows.first?.receipt)
            XCTAssertEqual(final.plannedBatches.last?.rows.first?.receipt?.status, stage == .itemStarted ? .cancelled : nil)
        }
    }

    func testContinuationClearAfterPlanOrItemAdmissionRetiresRosterAndNeverFallsBackToUnrecordedGET() async throws {
        for stage in [MediaDeliveryReplyStage.batchPlanned, .itemStarted] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root)
            let old = try await store.beginPlannedBatch(filenames: ["OLD.JPG"])
            let gate = DeliveryReplyGate(stage: stage)
            let peer = BatchMediaPeer(items: [item("CURRENT")])
            let state = makeState([peer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
            defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
            try await connectAndLoad(state)
            await state.refreshMediaDeliveryHistory()
            try bindContinuation(state, batch: old)
            XCTAssertTrue(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: false))
            let task = try XCTUnwrap(state.mediaBatchTask)
            try await waitUntil { await gate.waiting }
            await state.clearMediaDeliveryHistory()
            await gate.release()
            try await awaitContinuationTask(task)
            let final = await store.snapshot()
            XCTAssertTrue(final.plannedBatches.isEmpty)
            XCTAssertTrue(final.records.isEmpty)
            XCTAssertEqual(state.mediaBatchState, .cancelled)
            let downloads = await peer.downloadIDs()
            XCTAssertTrue(downloads.isEmpty)
        }
    }

    func testContinuationCapacityAndAtomicCommitFailureRefuseWithoutFallbackOrEviction() async throws {
        for capacityFailure in [false, true] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let fault = WorklistCommitFault()
            let store = MediaDeliveryStore(root: root,
                limits: .init(maximumRecords: capacityFailure ? 1 : 100, maximumRetainedBytes: 100),
                faultInjector: { try fault.check($0) })
            let old = try await store.beginPlannedBatch(filenames: ["OLD.JPG"])
            let peer = BatchMediaPeer(items: [item("CURRENT")])
            let state = makeState([peer], deliveryStore: store)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            await state.refreshMediaDeliveryHistory()
            try bindContinuation(state, batch: old)
            if !capacityFailure { fault.arm() }
            XCTAssertTrue(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: false))
            if let task = state.mediaBatchTask { try await awaitContinuationTask(task) }
            XCTAssertEqual(state.mediaBatchState, .failed)
            XCTAssertTrue(state.mediaWorklistContinuationUnavailable)
            XCTAssertTrue(state.mediaBatchWorklistWarning)
            XCTAssertNil(state.makeMediaBatchExportPayload())
            let downloads = await peer.downloadIDs()
            XCTAssertTrue(downloads.isEmpty)
            let final = await store.snapshot()
            XCTAssertEqual(final.plannedBatches.map(\.id), [old.ticket.batchID])
            XCTAssertTrue(final.records.isEmpty)
        }
    }

    func testContinuationItemAdmissionFailureKeepsNewRowQueuedAndDoesNotDownload() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let fault = WorklistCommitFault()
        let store = MediaDeliveryStore(root: root, faultInjector: { try fault.check($0) })
        let old = try await store.beginPlannedBatch(filenames: ["OLD.JPG"])
        let peer = BatchMediaPeer(items: [item("CURRENT")])
        let gate = DeliveryReplyGate(stage: .batchPlanned)
        let state = makeState([peer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
        try await connectAndLoad(state)
        await state.refreshMediaDeliveryHistory()
        try bindContinuation(state, batch: old)
        XCTAssertTrue(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: false))
        let task = try XCTUnwrap(state.mediaBatchTask)
        try await waitUntil { await gate.waiting }
        fault.arm()
        await gate.release()
        try await awaitContinuationTask(task)
        XCTAssertEqual(state.mediaBatchState, .failed)
        XCTAssertTrue(state.mediaWorklistContinuationUnavailable)
        let after = await store.snapshot()
        XCTAssertEqual(after.plannedBatches.count, 2)
        XCTAssertTrue(after.records.isEmpty)
        let downloads = await peer.downloadIDs()
        XCTAssertTrue(downloads.isEmpty)
    }

    func testContinuationDraftIsRetiredByClearBackgroundReloadAndReusedSessionIdentity() async throws {
        for action in ["clear", "background", "reload", "session"] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root)
            let old = try await store.beginPlannedBatch(filenames: ["OLD.JPG"])
            let first = BatchMediaPeer(items: [item("REUSED", name: "SAME.JPG")])
            let second = BatchMediaPeer(items: [item("REUSED", name: "SAME.JPG")])
            let state = makeState([first, second], deliveryStore: store)
            defer { state.requestDisconnect(); Task { await first.releaseAll(); await second.releaseAll() } }
            try await connectAndLoad(state)
            await state.refreshMediaDeliveryHistory()
            try bindContinuation(state, batch: old)
            switch action {
            case "clear": await state.clearMediaDeliveryHistory()
            case "background": state.setApplicationActive(false); state.setApplicationActive(true)
            case "reload":
                state.startMediaLibraryLoad()
                try await waitUntil { state.mediaLibraryLoadStatus == .complete }
            default:
                await state.disconnect()
                try await connectAndLoad(state)
            }
            XCTAssertNil(state.mediaWorklistContinuation, action)
            XCTAssertFalse(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: true), action)
            let firstDownloads = await first.downloadIDs()
            let secondDownloads = await second.downloadIDs()
            XCTAssertTrue(firstDownloads.isEmpty, action)
            XCTAssertTrue(secondDownloads.isEmpty, action)
        }
    }

    func testLateContinuationPlanReplyCannotOverwriteReplacementSessionBatchWithSameWireID() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let old = try await store.beginPlannedBatch(filenames: ["OLD.JPG"])
        let first = BatchMediaPeer(items: [item("REUSED", name: "FIRST.JPG")])
        let second = BatchMediaPeer(items: [item("REUSED", name: "SECOND.JPG")])
        let gate = DeliveryReplyGate(stage: .batchPlanned)
        let state = makeState([first, second], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await first.releaseAll(); await second.releaseAll() } }
        try await connectAndLoad(state)
        await state.refreshMediaDeliveryHistory()
        try bindContinuation(state, batch: old)
        XCTAssertTrue(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: false))
        let oldTask = try XCTUnwrap(state.mediaBatchTask)
        try await waitUntil { await gate.waiting }
        await state.disconnect()
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        if let task = state.mediaBatchTask { try await awaitContinuationTask(task) }
        await gate.release()
        try await awaitContinuationTask(oldTask)
        XCTAssertEqual(state.mediaBatchState, .completed)
        XCTAssertEqual(state.mediaBatchResults.map(\.item.name), ["SECOND.JPG"])
        XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready])
        XCTAssertFalse(state.isBusy(.media))
        let firstDownloads = await first.downloadIDs()
        let secondDownloads = await second.downloadIDs()
        XCTAssertTrue(firstDownloads.isEmpty)
        XCTAssertEqual(secondDownloads, [Data("REUSED".utf8)])
    }

    func testLateContinuationOriginalAfterClearBackgroundOrSessionReplacementCannotPublishOrStartNextOriginal() async throws {
        for retirement in ["clear", "background", "session"] {
            let replaceSession = retirement == "session"
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root)
            let old = try await store.beginPlannedBatch(filenames: ["OLD-ONE.JPG", "OLD-TWO.JPG"])
            let first = BatchMediaPeer(items: [item("REUSED"), item("NEXT")])
            let second = BatchMediaPeer(items: [item("REUSED", name: "REPLACEMENT.JPG")])
            let state = makeState([first, second], deliveryStore: store)
            defer { state.requestDisconnect(); Task { await first.releaseAll(); await second.releaseAll() } }
            try await connectAndLoad(state)
            await state.refreshMediaDeliveryHistory()
            try bindContinuation(state, batch: old)
            await first.enqueueDownload(gate: "late-original")
            XCTAssertTrue(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: false))
            let oldTask = try XCTUnwrap(state.mediaBatchTask)
            try await waitForGate("late-original", peer: first)
            if replaceSession {
                await state.disconnect()
                try await connectAndLoad(state)
                state.selectMediaItems(state.mediaItems)
                XCTAssertTrue(state.startMediaBatchDownload())
                if let task = state.mediaBatchTask { try await awaitContinuationTask(task) }
            } else if retirement == "background" {
                state.setApplicationActive(false)
                state.setApplicationActive(true)
            } else { await state.clearMediaDeliveryHistory() }
            await first.release("late-original")
            try await awaitContinuationTask(oldTask)
            let firstDownloads = await first.downloadIDs()
            XCTAssertEqual(firstDownloads, [Data("REUSED".utf8)])
            if replaceSession {
                XCTAssertEqual(state.mediaBatchState, .completed)
                XCTAssertEqual(state.mediaBatchResults.map(\.item.name), ["REPLACEMENT.JPG"])
                XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready])
            } else {
                XCTAssertEqual(state.mediaBatchState, .cancelled)
                XCTAssertFalse(state.mediaBatchResults.contains { $0.status == .ready })
                let persisted = await store.snapshot()
                if retirement == "clear" {
                    XCTAssertTrue(persisted.records.isEmpty)
                    XCTAssertTrue(persisted.plannedBatches.isEmpty)
                } else {
                    XCTAssertEqual(persisted.plannedBatches.count, 2)
                    XCTAssertNil(persisted.plannedBatches.first?.rows.first?.receipt)
                    XCTAssertEqual(persisted.plannedBatches.last?.rows.first?.receipt?.status, .cancelled)
                    XCTAssertNil(persisted.plannedBatches.last?.rows.last?.receipt)
                }
            }
        }
    }

    func testContinuationCancellationAfterFinalRetentionDoesNotPublishCompletedOrLoseReadyCopy() async throws {
        for clear in [false, true] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root)
            let old = try await store.beginPlannedBatch(filenames: ["OLD.JPG"])
            let peer = BatchMediaPeer(items: [item("CURRENT")])
            let gate = DeliveryReplyGate(stage: .originalRetained)
            let state = makeState([peer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
            defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
            try await connectAndLoad(state)
            await state.refreshMediaDeliveryHistory()
            try bindContinuation(state, batch: old)
            XCTAssertTrue(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: false))
            let task = try XCTUnwrap(state.mediaBatchTask)
            try await waitUntil { await gate.waiting }
            if clear { await state.clearMediaDeliveryHistory() }
            else { state.cancelMediaBatchDownload() }
            await gate.release()
            try await awaitContinuationTask(task)
            XCTAssertEqual(state.mediaBatchState, .cancelled)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready], "A validated original remains available for explicit sharing")
            let payload = try XCTUnwrap(state.makeMediaBatchExportPayload())
            XCTAssertEqual(try Data(contentsOf: payload.fileURLs[0]), BatchMediaPeer.original)
            let history = await store.snapshot()
            if clear {
                XCTAssertTrue(history.plannedBatches.isEmpty)
                XCTAssertTrue(history.records.isEmpty)
            } else {
                XCTAssertNil(history.plannedBatches.first?.rows.first?.receipt)
                XCTAssertEqual(history.plannedBatches.last?.rows.first?.receipt?.status, .prepared)
            }
        }
    }

    func testAutomaticMediaEventRefreshRetiresContinuationBindingsWithoutStartingOriginals() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let old = try await store.beginPlannedBatch(filenames: ["OLD.JPG"])
        let peer = BatchMediaPeer(items: [item("REUSED", name: "BEFORE.JPG")], eventPolling: true)
        let state = makeState([peer], deliveryStore: store)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.screen = .media
        await state.refreshMediaDeliveryHistory()
        try bindContinuation(state, batch: old)
        await peer.replaceItems([item("REUSED", name: "AFTER.JPG")])
        await peer.emitContentEvent()
        try await waitUntil {
            state.mediaWorklistContinuation == nil && state.mediaLibraryLoadStatus == .complete
                && state.mediaItems.first?.name == "AFTER.JPG"
        }
        XCTAssertFalse(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: true))
        let origins = old.rowIDs.map { MediaDeliveryOrigin(batchID: old.ticket.batchID, rowID: $0) }
        XCTAssertTrue(state.beginMediaWorklistContinuation(origins: origins))
        XCTAssertTrue(state.mediaWorklistContinuation!.rows.allSatisfy { $0.item == nil })
        let downloads = await peer.downloadIDs()
        XCTAssertTrue(downloads.isEmpty)
        let history = await store.snapshot()
        XCTAssertEqual(history.plannedBatches.count, 1)
        XCTAssertTrue(history.records.isEmpty)
    }

    func testScopeChangeReusingCompleteInventoryRetiresContinuationBindings() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let old = try await store.beginPlannedBatch(filenames: ["OLD.JPG"])
        let peer = BatchMediaPeer(items: [item("CURRENT")])
        let state = makeState([peer], deliveryStore: store)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndLoad(state)
        state.setMediaLibraryScope(.all)
        try await waitUntil { state.mediaLibraryLoadStatus == .complete }
        await state.refreshMediaDeliveryHistory()
        try bindContinuation(state, batch: old)
        let before = await peer.requests()
        state.setMediaLibraryScope(.recent)
        XCTAssertNil(state.mediaWorklistContinuation)
        XCTAssertFalse(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: true))
        let after = await peer.requests()
        XCTAssertEqual(after, before, "Reusing a complete inventory must retire bindings without another listing")
        let downloads = await peer.downloadIDs()
        XCTAssertTrue(downloads.isEmpty)
    }

    func testRetiredContinuationItemAdmissionSettlesOldRowWithoutPoisoningReusedSession() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let old = try await store.beginPlannedBatch(filenames: ["OLD.JPG"])
        let first = BatchMediaPeer(items: [item("REUSED", name: "FIRST.JPG")])
        let second = BatchMediaPeer(items: [item("REUSED", name: "SECOND.JPG")])
        let gate = DeliveryReplyGate(stage: .itemStarted)
        let state = makeState([first, second], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await first.releaseAll(); await second.releaseAll() } }
        try await connectAndLoad(state)
        await state.refreshMediaDeliveryHistory()
        try bindContinuation(state, batch: old)
        XCTAssertTrue(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: false))
        let retiredTask = try XCTUnwrap(state.mediaBatchTask)
        try await waitUntil { await gate.waiting }
        let admitted = await store.snapshot()
        let continuation = try XCTUnwrap(admitted.plannedBatches.last)
        XCTAssertEqual(continuation.rows.first?.receipt?.status, .started)
        await state.disconnect()
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        if let replacement = state.mediaBatchTask { try await awaitContinuationTask(replacement) }
        await gate.release()
        try await awaitContinuationTask(retiredTask)
        XCTAssertEqual(state.mediaBatchState, .completed)
        XCTAssertEqual(state.mediaBatchResults.map(\.item.name), ["SECOND.JPG"])
        XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready])
        let after = await store.snapshot()
        XCTAssertNil(after.plannedBatches.first?.rows.first?.receipt)
        XCTAssertEqual(after.plannedBatches.first(where: { $0.id == continuation.id })?.rows.first?.receipt?.status, .cancelled)
        XCTAssertTrue(after.plannedBatches.first!.rows[0].canContinue,
            "Retiring an admitted no-GET row must unblock the lineage in the same live store")
        await state.refreshMediaDeliveryHistory()
        XCTAssertTrue(state.beginMediaWorklistContinuation(origins: [.init(batchID: old.ticket.batchID, rowID: old.rowIDs[0])]))
        XCTAssertTrue(state.mediaWorklistContinuation!.rows.allSatisfy { $0.item == nil })
        let firstDownloads = await first.downloadIDs()
        let secondDownloads = await second.downloadIDs()
        XCTAssertTrue(firstDownloads.isEmpty)
        XCTAssertEqual(secondDownloads, [Data("REUSED".utf8)])
    }

    // Native execution pending: these exercise production App/Store paths through
    // synthetic CCAPI and Bridge transports; they are not camera-device evidence.
    func testManualPreparedThenRetiredAdmissionContinuesOnlyExplicitlyReboundUnfinishedRows() async throws {
        for bridge in [false, true] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root)
            let first = BatchMediaPeer(items: [item("A"), item("REUSED", name: "SAME.JPG"), item("C", name: "SAME.JPG")])
            let second = BatchMediaPeer(items: [item("REUSED", name: "SAME.JPG"), item("C", name: "SAME.JPG")])
            let gate = DeliveryReplyGate(stage: .itemStarted, after: 1)
            let state = makeState([first, second], bridge: bridge, deliveryStore: store, deliveryDelay: { await gate.enter($0) })
            defer { state.requestDisconnect(); Task { await gate.release(); await first.releaseAll(); await second.releaseAll() } }
            try await connectAndLoad(state)
            state.selectMediaItems(state.mediaItems)
            XCTAssertTrue(state.startMediaBatchDownload())
            let oldTask = try XCTUnwrap(state.mediaBatchTask)
            try await waitUntil { await gate.waiting }
            let admitted = await store.snapshot()
            let old = try XCTUnwrap(admitted.plannedBatches.first)
            XCTAssertEqual(old.rows.map { $0.receipt?.status }, [.prepared, .started, nil])
            XCTAssertEqual(old.rows[0].receipt?.availability, .available)
            let coldAdmission = await MediaDeliveryStore(root: root).snapshot()
            XCTAssertEqual(coldAdmission.plannedBatches[0].rows.map { $0.receipt?.status }, [.prepared, .unknown, nil])
            let prepared = try XCTUnwrap(old.rows[0].receipt)
            let temporary = try XCTUnwrap(state.makeMediaBatchExportPayload())
            let retained = try await store.acquireShare(receiptIDs: [prepared.id])
            await state.disconnect()
            try await connectAndLoad(state)
            let beforeReply = await second.downloadIDs()
            XCTAssertTrue(beforeReply.isEmpty, "Reconnect and matching names cannot authorize a GET")
            await gate.release()
            try await awaitContinuationTask(oldTask)
            XCTAssertEqual(state.mediaBatchState, .idle)
            XCTAssertFalse(state.isBusy(.media))
            let settled = await store.snapshot()
            let rows = try XCTUnwrap(settled.plannedBatches.first?.rows)
            XCTAssertEqual(rows.map { $0.receipt?.status }, [.prepared, .cancelled, nil])
            XCTAssertEqual(rows.map(\.canContinue), [false, true, true])
            XCTAssertEqual(rows[0].receipt?.completedAt, prepared.completedAt)
            XCTAssertEqual(try Data(contentsOf: retained[0].fileURL), BatchMediaPeer.original)
            XCTAssertEqual(try Data(contentsOf: temporary.fileURLs[0]), BatchMediaPeer.original)
            XCTAssertFalse(state.beginMediaWorklistContinuation(origins: [.init(batchID: old.id, rowID: rows[0].id)]))
            let origins = rows.dropFirst().map { MediaDeliveryOrigin(batchID: old.id, rowID: $0.id) }
            XCTAssertTrue(state.beginMediaWorklistContinuation(origins: origins))
            XCTAssertTrue(state.mediaWorklistContinuation!.rows.allSatisfy { $0.item == nil }, "Even equal names and wire IDs require fresh explicit binding")
            XCTAssertFalse(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: false))
            for (index, origin) in origins.enumerated() {
                XCTAssertTrue(state.bindMediaWorklistOriginal(origin: origin, identity: mediaItemIdentity(state.mediaItems[index])))
            }
            XCTAssertFalse(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: false, acknowledgedUnknown: false))
            XCTAssertTrue(state.confirmMediaWorklistContinuation(confirmedMappingAndShare: true, acknowledgedUnknown: false))
            try await awaitContinuationTask(try XCTUnwrap(state.mediaBatchTask))
            XCTAssertEqual(state.mediaBatchState, .completed)
            let final = await MediaDeliveryStore(root: root).snapshot()
            XCTAssertEqual(final.plannedBatches.count, 2)
            XCTAssertEqual(final.plannedBatches[0].rows.map { $0.receipt?.status }, [.prepared, .cancelled, nil])
            XCTAssertEqual(final.plannedBatches[1].rows.map(\.origin), origins.map { Optional.some($0) })
            XCTAssertTrue(final.plannedBatches[1].rows.allSatisfy { $0.receipt?.status == .prepared })
            XCTAssertTrue(final.records.allSatisfy { $0.status != .started && $0.status != .unknown })
            let oldDownloads = await first.downloadIDs()
            let newDownloads = await second.downloadIDs()
            XCTAssertEqual(oldDownloads, [Data("A".utf8)])
            XCTAssertEqual(newDownloads, [Data("REUSED".utf8), Data("C".utf8)])
            XCTAssertEqual(try Data(contentsOf: retained[0].fileURL), BatchMediaPeer.original)
        }
    }

    func testManualClearBeforeTaskOrAtAdmissionNeverRecreatesHistoryAndStillFinishesSelectedOriginals() async throws {
        for stage in [nil, MediaDeliveryReplyStage.batchStarted, .batchPlanned, .itemStarted] as [MediaDeliveryReplyStage?] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root)
            let gate = DeliveryReplyGate(stage: stage ?? .batchStarted)
            let peer = BatchMediaPeer(items: [item("A"), item("B")])
            let state = makeState([peer], deliveryStore: store, deliveryDelay: { if stage != nil { await gate.enter($0) } })
            defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
            try await connectAndLoad(state)
            await state.refreshMediaDeliveryHistory()
            let previousEpoch = state.mediaDeliverySnapshot.epoch
            state.selectMediaItems(state.mediaItems)
            XCTAssertTrue(state.startMediaBatchDownload())
            let task = try XCTUnwrap(state.mediaBatchTask)
            if stage != nil { try await waitUntil { await gate.waiting } }
            await state.clearMediaDeliveryHistory()
            let clearedEpoch = state.mediaDeliverySnapshot.epoch
            XCTAssertNotEqual(clearedEpoch, previousEpoch)
            await gate.release()
            try await awaitContinuationTask(task)
            XCTAssertEqual(state.mediaBatchState, .completed)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready, .ready])
            XCTAssertTrue(state.mediaBatchWorklistWarning)
            XCTAssertFalse(state.mediaDeliveryHistoryWarning, "An intentional clear must not be overwritten by a stale history warning")
            let history = await store.snapshot()
            XCTAssertEqual(history.epoch, clearedEpoch)
            XCTAssertTrue(history.records.isEmpty)
            XCTAssertTrue(history.plannedBatches.isEmpty)
            XCTAssertEqual(state.makeMediaBatchExportPayload()?.fileURLs.count, 2)
            let downloads = await peer.downloadIDs()
            XCTAssertEqual(downloads, [Data("A".utf8), Data("B".utf8)])
        }
    }

    func testRetiredManualAdmissionAfterClearCannotMutateReplacementHistoryOrOwner() async throws {
        let root = singleDeliveryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = MediaDeliveryStore(root: root)
        let gate = DeliveryReplyGate(stage: .itemStarted)
        let first = BatchMediaPeer(items: [item("REUSED", name: "SAME.JPG")])
        let second = BatchMediaPeer(items: [item("REUSED", name: "SAME.JPG")])
        let state = makeState([first, second], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
        defer { state.requestDisconnect(); Task { await gate.release(); await first.releaseAll(); await second.releaseAll() } }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        let oldTask = try XCTUnwrap(state.mediaBatchTask)
        try await waitUntil { await gate.waiting }
        await state.clearMediaDeliveryHistory()
        await state.disconnect()
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        await second.enqueueDownload(gate: "new-owner")
        XCTAssertTrue(state.startMediaBatchDownload())
        let newTask = try XCTUnwrap(state.mediaBatchTask)
        try await waitForGate("new-owner", peer: second)
        let currentID = state.mediaBatchResults[0].id
        let before = try Data(contentsOf: root.appendingPathComponent("manifest.json"))
        await gate.release()
        try await awaitContinuationTask(oldTask)
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("manifest.json")), before)
        XCTAssertEqual(state.mediaBatchResults[0].id, currentID)
        XCTAssertEqual(state.mediaBatchResults[0].status, .downloading)
        XCTAssertTrue(state.isBusy(.media))
        XCTAssertEqual(state.mediaBatchState, .running)
        XCTAssertFalse(state.mediaDeliveryHistoryWarning)
        XCTAssertFalse(state.mediaBatchWorklistWarning)
        await second.release("new-owner")
        try await awaitContinuationTask(newTask)
        let history = await store.snapshot()
        XCTAssertEqual(history.plannedBatches.count, 1)
        XCTAssertEqual(history.records.map(\.status), [.prepared])
        let firstDownloads = await first.downloadIDs()
        let secondDownloads = await second.downloadIDs()
        XCTAssertTrue(firstDownloads.isEmpty)
        XCTAssertEqual(secondDownloads, [Data("REUSED".utf8)])
    }

    func testRetiredManualSettlementSuccessOrWriteFailureNeverReleasesNewOwner() async throws {
        for failSettlement in [false, true] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let fault = WorklistCommitFault()
            let store = MediaDeliveryStore(root: root, faultInjector: { try fault.check($0) })
            let gate = DeliveryReplyGate(stage: .itemStarted, after: 1)
            let first = BatchMediaPeer(items: [item("KEEP"), item("REUSED"), item("QUEUED")])
            let second = BatchMediaPeer(items: [item("REUSED")])
            let state = makeState([first, second], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
            defer { state.requestDisconnect(); Task { await gate.release(); await first.releaseAll(); await second.releaseAll() } }
            try await connectAndLoad(state)
            state.selectMediaItems(state.mediaItems)
            XCTAssertTrue(state.startMediaBatchDownload())
            let oldTask = try XCTUnwrap(state.mediaBatchTask)
            try await waitUntil { await gate.waiting }
            let oldSnapshot = await store.snapshot()
            let old = try XCTUnwrap(oldSnapshot.plannedBatches.first)
            let kept = try await store.acquireShare(receiptIDs: [try XCTUnwrap(old.rows[0].receipt?.id)])
            await state.disconnect()
            try await connectAndLoad(state)
            state.selectMediaItems(state.mediaItems)
            await second.enqueueDownload(gate: "replacement")
            XCTAssertTrue(state.startMediaBatchDownload())
            let newTask = try XCTUnwrap(state.mediaBatchTask)
            try await waitForGate("replacement", peer: second)
            let replacementID = state.mediaBatchResults[0].id
            let before = try Data(contentsOf: root.appendingPathComponent("manifest.json"))
            if failSettlement { fault.arm() }
            await gate.release()
            try await awaitContinuationTask(oldTask)
            let after = try Data(contentsOf: root.appendingPathComponent("manifest.json"))
            if failSettlement { XCTAssertEqual(after, before) }
            else { XCTAssertNotEqual(after, before, "The old admitted row must durably settle") }
            XCTAssertEqual(state.mediaDeliveryHistoryWarning, failSettlement)
            XCTAssertEqual(state.mediaBatchResults[0].id, replacementID)
            XCTAssertEqual(state.mediaBatchResults[0].status, .downloading)
            XCTAssertEqual(state.mediaBatchState, .running)
            XCTAssertTrue(state.isBusy(.media))
            XCTAssertNotNil(state.mediaBatchTask)
            XCTAssertFalse(state.mediaBatchWorklistWarning)
            let live = await store.snapshot()
            XCTAssertEqual(live.plannedBatches[0].rows.map { $0.receipt?.status }, [.prepared, failSettlement ? .started : .cancelled, nil])
            XCTAssertEqual(live.plannedBatches[0].rows[1].canContinue, !failSettlement,
                "A failed terminal write cannot claim durable cancellation")
            let cold = await MediaDeliveryStore(root: root).snapshot()
            XCTAssertEqual(cold.plannedBatches[0].rows.map { $0.receipt?.status }, [.prepared, failSettlement ? .unknown : .cancelled, nil])
            XCTAssertEqual(cold.plannedBatches[0].rows[1].requiresUnknownAcknowledgement, failSettlement)
            XCTAssertEqual(try Data(contentsOf: kept[0].fileURL), BatchMediaPeer.original)
            await second.release("replacement")
            try await awaitContinuationTask(newTask)
            XCTAssertEqual(state.mediaBatchState, .completed)
            XCTAssertEqual(state.mediaDeliveryHistoryWarning, failSettlement)
            let downloads = await first.downloadIDs()
            XCTAssertEqual(downloads, [Data("KEEP".utf8)])
        }
    }

    func testManualCancellationAfterFinalRetentionPreservesPreparedCopyAndCancelledBatch() async throws {
        for directTaskCancellation in [false, true] {
            let root = singleDeliveryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = MediaDeliveryStore(root: root)
            let gate = DeliveryReplyGate(stage: .originalRetained)
            let peer = BatchMediaPeer(items: [item("KEEP")])
            let state = makeState([peer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
            defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
            try await connectAndLoad(state)
            state.selectMediaItems(state.mediaItems)
            XCTAssertTrue(state.startMediaBatchDownload())
            let task = try XCTUnwrap(state.mediaBatchTask)
            try await waitUntil { await gate.waiting }
            let before = try Data(contentsOf: root.appendingPathComponent("manifest.json"))
            if directTaskCancellation { task.cancel() }
            else { state.cancelMediaBatchDownload() }
            await gate.release()
            try await awaitContinuationTask(task)
            XCTAssertEqual(state.mediaBatchState, .cancelled)
            XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready])
            XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("manifest.json")), before)
            let payload = try XCTUnwrap(state.makeMediaBatchExportPayload())
            XCTAssertEqual(try Data(contentsOf: payload.fileURLs[0]), BatchMediaPeer.original)
            let history = await store.snapshot()
            XCTAssertEqual(history.records.map(\.status), [.prepared])
            XCTAssertEqual(history.records.map(\.availability), [.available])
            XCTAssertFalse(history.plannedBatches[0].rows[0].canContinue)
            XCTAssertFalse(state.isBusy(.media))
            let downloads = await peer.downloadIDs()
            XCTAssertEqual(downloads, [Data("KEEP".utf8)])
        }
    }

    func testManualStoreCancellationDuringAdmissionNeverFallsBackToOriginalGET() async throws {
        for stage in [MediaDeliveryReplyStage.batchStarted, .batchPlanned] {
            for failure in [SingleDeliveryFault.Failure.cancellation, .urlCancelled, .storeCancelled] {
                let root = singleDeliveryRoot()
                defer { try? FileManager.default.removeItem(at: root) }
                let fault = SingleDeliveryFault()
                let store = MediaDeliveryStore(root: root, faultInjector: { try fault.check($0) })
                let gate = DeliveryReplyGate(stage: stage)
                let peer = BatchMediaPeer(items: [item("A"), item("B")])
                let state = makeState([peer], deliveryStore: store, deliveryDelay: { await gate.enter($0) })
                defer { state.requestDisconnect(); Task { await gate.release(); await peer.releaseAll() } }
                try await connectAndLoad(state)
                state.selectMediaItems(state.mediaItems)
                XCTAssertTrue(state.startMediaBatchDownload())
                let task = try XCTUnwrap(state.mediaBatchTask)
                try await waitUntil { await gate.waiting }
                fault.arm(.beforeManifestReplace, failure: failure)
                await gate.release()
                try await awaitContinuationTask(task)
                XCTAssertFalse(task.isCancelled)
                XCTAssertEqual(state.mediaBatchState, .cancelled)
                XCTAssertEqual(state.mediaBatchResults.map(\.status), [.notStarted, .notStarted])
                XCTAssertNil(state.makeMediaBatchExportPayload())
                let history = await store.snapshot()
                XCTAssertEqual(history.plannedBatches.count, stage == .batchStarted ? 0 : 1)
                XCTAssertTrue(history.records.isEmpty)
                let downloads = await peer.downloadIDs()
                XCTAssertTrue(downloads.isEmpty)
            }
        }
    }

    private func bindContinuation(_ state: CameraAppState, batch: MediaDeliveryPlannedBatch) throws {
        let origins = batch.rowIDs.map { MediaDeliveryOrigin(batchID: batch.ticket.batchID, rowID: $0) }
        XCTAssertTrue(state.beginMediaWorklistContinuation(origins: origins))
        XCTAssertEqual(state.mediaItems.count, origins.count)
        for (index, origin) in origins.enumerated() {
            XCTAssertTrue(state.bindMediaWorklistOriginal(origin: origin, identity: mediaItemIdentity(state.mediaItems[index])))
        }
        XCTAssertTrue(state.canConfirmMediaWorklistContinuation)
    }

    /// An unstructured observer bounds fixture failures without a task group
    /// that would itself wait forever for a cancellation-ignoring child. Every
    /// gate-owning test also releases its fixtures in defer before unwinding.
    private func awaitContinuationTask(_ task: Task<Void, Never>) async throws {
        let completion = ContinuationTaskCompletion()
        Task { await task.value; await completion.finish() }
        do { try await waitUntil { await completion.finished } }
        catch { task.cancel(); throw error }
    }

    private func singleDeliveryRoot() -> URL {
        FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent("SingleDelivery-\(UUID().uuidString)")
    }

    private func item(_ id: String, name: String? = nil, kind: String = "image", rating: Int = 0) -> CameraMediaItem {
        CameraMediaItem(id: id, name: name ?? "\(id).JPG", kind: kind,
            sizeBytes: Int64(BatchMediaPeer.original.count), previewAvailable: true,
            rating: rating, contentType: "image/jpeg")
    }

    private func makeState(_ peers: [BatchMediaPeer], bridge: Bool = true, deliveryStore: MediaDeliveryStore? = nil,
                           deliveryDelay: (@Sendable (MediaDeliveryReplyStage) async -> Void)? = nil) -> CameraAppState {
        var remaining = peers
        let defaults = UserDefaults(suiteName: "MediaBatchDeliveryTests.\(UUID().uuidString)")!
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            let peer = remaining.removeFirst()
            return bridge
                ? .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
                : .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
        }, mediaDeliveryStore: deliveryStore, mediaDeliveryReplyDelay: deliveryDelay)
        state.autoRefresh = false
        return state
    }

    private func connectAndLoad(_ state: CameraAppState) async throws {
        await state.connect()
        XCTAssertTrue(state.connected)
        await state.latestMediaTask?.value
        state.startMediaLibraryLoad()
        try await waitUntil { state.mediaLibraryLoadStatus == .complete }
    }

    private func waitForGate(_ name: String, peer: BatchMediaPeer) async throws {
        try await waitUntil { await peer.waiting(name) }
    }

    private func waitUntil(_ condition: @MainActor () async -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await condition() { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected production state or controlled fixture gate did not arrive")
        throw URLError(.timedOut)
    }
}

/// Production CameraSession/CCAPI/Bridge clients parse these synthetic wire
/// responses. Gates intentionally ignore task cancellation to exercise stale
/// completions and preserve the one-original-request-at-a-time contract.
private actor BatchMediaPeer: CameraHTTPTransport {
    static let original = Data([0xFF, 0xD8, 0xFF, 0xE0, 5, 6, 7, 0xFF, 0xD9])
    private struct Plan {
        let gate: String?
        let error: URLError.Code?
        var body: Data? = nil
        var headers: [String: String]? = nil
    }
    private var items: [CameraMediaItem]
    private let eventPolling: Bool
    private var pendingContentEvent = false
    private var contentEventWaiter: CheckedContinuation<Void, Error>?
    private var downloadPlans: [Plan] = []
    private var listingGate: String?
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var draining = false
    private var recordedRequests: [String] = []
    private var downloadedIDs: [Data] = []
    private var progressHandlers: [CameraMediaProgressHandler] = []
    private var temporaryFiles: [URL] = []
    private var activeDownloads = 0
    private var maximumDownloads = 0

    init(items: [CameraMediaItem], eventPolling: Bool = false) {
        self.items = items
        self.eventPolling = eventPolling
    }
    func emitContentEvent() {
        if let waiting = contentEventWaiter {
            contentEventWaiter = nil
            waiting.resume()
        } else { pendingContentEvent = true }
    }
    func replaceItems(_ value: [CameraMediaItem]) { items = value }
    func enqueueDownload(gate: String? = nil, error: URLError.Code? = nil,
                         body: Data? = nil, headers: [String: String]? = nil) {
        downloadPlans.append(Plan(gate: gate, error: error, body: body, headers: headers))
    }
    func holdNextListing(_ gate: String) { listingGate = gate }
    func waiting(_ name: String) -> Bool { gates[name] != nil }
    func release(_ name: String) { gates.removeValue(forKey: name)?.resume() }
    func releaseAll() {
        draining = true
        contentEventWaiter?.resume(throwing: CancellationError())
        contentEventWaiter = nil
        pendingContentEvent = false
        listingGate = nil
        downloadPlans = []
        let pending = gates
        gates.removeAll()
        for continuation in pending.values { continuation.resume() }
    }
    func requests() -> [String] { recordedRequests }
    func downloadIDs() -> [Data] { downloadedIDs }
    func returnedTemporaryFiles() -> [URL] { temporaryFiles }
    func maximumConcurrentDownloads() -> Int { maximumDownloads }
    func emitProgress(download index: Int, bytes: Int64) {
        progressHandlers[index](CameraMediaTransferProgress(bytesTransferred: bytes, totalBytes: Int64(Self.original.count)))
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let url = request.url!
        let path = url.path
        let method = request.httpMethod ?? "GET"
        if path.hasSuffix("/events") {
            if method == "DELETE" {
                contentEventWaiter?.resume(throwing: CancellationError())
                contentEventWaiter = nil
                pendingContentEvent = false
                return CameraHTTPResponse(statusCode: 200, body: Data("{}".utf8))
            }
            guard eventPolling, !draining else { throw CancellationError() }
            if pendingContentEvent { pendingContentEvent = false }
            else { try await withCheckedThrowingContinuation { contentEventWaiter = $0 } }
            guard !draining, !Task.isCancelled else { throw CancellationError() }
            return CameraHTTPResponse(statusCode: 200,
                body: try JSONSerialization.data(withJSONObject: ["changedKeys": ["contents"]]))
        }
        recordedRequests.append("\(method) \(url.absoluteString)")
        let simulator = path.hasPrefix("/ccapi")
        if method == "DELETE", path == "/v1/session/synthetic-batch" {
            return CameraHTTPResponse(statusCode: 204)
        }
        var object: [String: Any] = [:]
        if path == "/health" {
            object = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]
        } else if path == "/v1/session" {
            object = ["id": "synthetic-batch", "engine": "ccapi"]
        } else if path.hasSuffix("/media") {
            let snapshot = items
            if !draining, let gate = listingGate {
                listingGate = nil
                await withCheckedContinuation { gates[gate] = $0 }
            }
            object = ["items": snapshot.map { mediaJSON($0, simulator: simulator) }]
        } else if path.contains("/media/") {
            let components = url.pathComponents
            guard let marker = components.firstIndex(of: "media"), components.indices.contains(marker + 1) else {
                throw URLError(.unsupportedURL)
            }
            let identity = Data(components[marker + 1].utf8)
            if method == "DELETE" {
                items.removeAll { Data($0.id.utf8) == identity }
                return CameraHTTPResponse(statusCode: 204)
            } else if method == "PUT" {
                guard let index = items.firstIndex(where: { mediaItemIdentity($0) == identity }) else {
                    throw URLError(.resourceUnavailable)
                }
                let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
                let action = simulator ? payload["action"] as? String : url.lastPathComponent
                let previous = items[index]
                let rating = action == "rating"
                    ? ((payload["value"] as? Int) ?? Int(payload["value"] as? String ?? "") ?? 0)
                    : previous.rating
                let protection = action == "protection" || action == "protect"
                    ? ((payload["enabled"] as? Bool) ?? ((payload["value"] as? String) == "enable"))
                    : previous.protected
                let updated = CameraMediaItem(id: previous.id, name: previous.name, kind: previous.kind,
                    sizeBytes: previous.sizeBytes, previewAvailable: previous.previewAvailable,
                    protected: protection, rating: rating, contentType: previous.contentType)
                items[index] = updated
                object = simulator ? [:] : mediaJSON(updated, simulator: false)
            } else {
                let kind = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
                    .first(where: { $0.name == "kind" })?.value
                if kind == "info" || path.hasSuffix("/info") {
                    guard let item = items.first(where: { mediaItemIdentity($0) == identity }) else {
                        throw URLError(.resourceUnavailable)
                    }
                    if simulator {
                        object = ["protect": item.protected == true ? "enable" : "disable",
                                  "rating": item.rating == 0 ? "off" : String(item.rating ?? 0), "contenttype": "image/jpeg"]
                        object["filesize"] = item.sizeBytes
                    } else { object = mediaJSON(item, simulator: false) }
                } else {
                    // Connect's latest-media thumbnail uses only synthetic bytes.
                    return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], body: Self.original)
                }
            }
        } else if path.hasSuffix("/info") {
            object = ["connected": true, "model": "Synthetic batch", "serial": "SYNTHETIC-BATCH", "api": "fixture"]
        } else if path.hasSuffix("/capabilities") {
            object = simulator ? [:] : ["supported": ["MEDIA_BROWSER", "MEDIA_DOWNLOAD", "MEDIA_DELETE", "MEDIA_THUMBNAIL",
                "MEDIA_PROTECT", "MEDIA_RATING"] + (eventPolling ? ["EVENT_POLLING"] : [])]
        } else if path.hasSuffix("/status") {
            object = ["connected": true, "recording": false, "mode": "Manual",
                "battery": [String: Any](), "media": [String: Any](), "exposure": [String: Any]()]
        }
        return CameraHTTPResponse(statusCode: 200, body: try JSONSerialization.data(withJSONObject: object))
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        try await download(request, progress: { _ in })
    }

    func download(_ request: URLRequest, progress: @escaping CameraMediaProgressHandler) async throws -> CameraHTTPDownloadResponse {
        recordedRequests.append("GET \(request.url!.absoluteString)")
        downloadedIDs.append(Data(request.url!.lastPathComponent.utf8))
        progressHandlers.append(progress)
        activeDownloads += 1
        maximumDownloads = max(maximumDownloads, activeDownloads)
        defer { activeDownloads -= 1 }
        let plan = downloadPlans.isEmpty ? Plan(gate: nil, error: nil) : downloadPlans.removeFirst()
        progress(CameraMediaTransferProgress(bytesTransferred: 1, totalBytes: Int64(Self.original.count)))
        if !draining, let gate = plan.gate { await withCheckedContinuation { gates[gate] = $0 } }
        if let error = plan.error { throw URLError(error) }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let body = plan.body ?? Self.original
        try body.write(to: file)
        temporaryFiles.append(file)
        return CameraHTTPDownloadResponse(statusCode: 200,
            headers: plan.headers ?? ["content-type": "image/jpeg", "content-length": String(body.count)], temporaryFileURL: file)
    }

    private func mediaJSON(_ item: CameraMediaItem, simulator: Bool) -> [String: Any] {
        var json: [String: Any] = ["id": item.id, "name": item.name, "kind": item.kind,
            "previewAvailable": item.previewAvailable]
        json[simulator ? "size_bytes" : "sizeBytes"] = item.sizeBytes
        json["rating"] = item.rating
        json[simulator ? "protect" : "protected"] = item.protected
        json[simulator ? "content_type" : "contentType"] = item.contentType
        return json
    }
}

/// Holds one reply without cooperating with cancellation; tests explicitly drain it.
private final class WorklistCommitFault: @unchecked Sendable {
    private let lock = NSLock()
    private var armed = false

    func arm() { lock.lock(); armed = true; lock.unlock() }

    func check(_ point: MediaDeliveryStore.FaultPoint) throws {
        lock.lock()
        let fail = armed && point == .beforeManifestReplace
        if fail { armed = false }
        lock.unlock()
        if fail { throw CocoaError(.fileWriteUnknown) }
    }
}

private actor DeliveryReplyGate {
    private let stage: MediaDeliveryReplyStage
    private var armed = true
    private var remaining: Int
    private var continuation: CheckedContinuation<Void, Never>?
    private(set) var waiting = false

    init(stage: MediaDeliveryReplyStage, after: Int = 0) { self.stage = stage; self.remaining = after }

    func enter(_ current: MediaDeliveryReplyStage) async {
        guard armed, current == stage else { return }
        if remaining > 0 { remaining -= 1; return }
        armed = false
        await withCheckedContinuation { continuation in
            self.continuation = continuation
            waiting = true
        }
    }

    func release() {
        armed = false
        waiting = false
        let pending = continuation
        continuation = nil
        pending?.resume()
    }
}

/// Persistent faults model a store that stays unwritable until the test ends.
private final class SingleDeliveryFault: @unchecked Sendable {
    enum Failure { case write, cancellation, urlCancelled, storeCancelled }
    private let lock = NSLock()
    private var point: MediaDeliveryStore.FaultPoint?
    private var failure = Failure.write

    func arm(_ point: MediaDeliveryStore.FaultPoint, failure: Failure = .write) {
        lock.lock(); self.point = point; self.failure = failure; lock.unlock()
    }

    func check(_ point: MediaDeliveryStore.FaultPoint) throws {
        lock.lock()
        let fail = self.point == point
        let failure = self.failure
        lock.unlock()
        guard fail else { return }
        switch failure {
        case .write: throw CocoaError(.fileWriteUnknown)
        case .cancellation: throw CancellationError()
        case .urlCancelled: throw URLError(.cancelled)
        case .storeCancelled: throw MediaDeliveryStoreError.cancelled
        }
    }
}

private actor ContinuationTaskCompletion {
    private(set) var finished = false
    func finish() { finished = true }
}
