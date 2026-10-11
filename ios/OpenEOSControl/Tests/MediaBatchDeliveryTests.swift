import Combine
import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

@MainActor
final class MediaBatchDeliveryTests: XCTestCase {
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

            await state.setMediaRating(composed, rating: 5)
            XCTAssertNil(state.lastError)
            XCTAssertEqual(state.mediaItems.map(mediaItemIdentity), expectedIDs)
            XCTAssertEqual(state.selectedMediaItems.map(mediaItemIdentity), expectedIDs)
            XCTAssertEqual(state.mediaItems.map(\.rating), [5, 0])
            XCTAssertEqual(state.selectedMediaItems.map(\.rating), [5, 0])
            XCTAssertEqual(state.latestMediaItem.map(mediaItemIdentity), latestIdentity)

            await state.setMediaProtection(decomposed, enabled: true)
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
                let oldPeer = BatchMediaPeer(items: [item("collision", name: "OLD.JPG")])
                let newPeer = BatchMediaPeer(items: [item("collision", name: "NEW.JPG")])
                let state = makeState([oldPeer, newPeer], bridge: bridge)
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
        let peer = BatchMediaPeer(items: [item("A")])
        let state = makeState([peer], deliveryStore: store)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() }; try? FileManager.default.removeItem(at: parent) }
        try await connectAndLoad(state)
        state.selectMediaItems(state.mediaItems)
        XCTAssertTrue(state.startMediaBatchDownload())
        await state.mediaBatchTask?.value
        XCTAssertEqual(state.mediaBatchState, .completed)
        XCTAssertEqual(state.mediaBatchResults.map(\.status), [.ready])
        XCTAssertTrue(state.mediaDeliveryHistoryWarning)
        let payload = try XCTUnwrap(state.makeMediaBatchExportPayload())
        XCTAssertEqual(try Data(contentsOf: payload.fileURLs[0]), BatchMediaPeer.original)
        let downloads = await peer.downloadIDs()
        XCTAssertEqual(downloads, [Data("A".utf8)])
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
    }
    private var items: [CameraMediaItem]
    private var downloadPlans: [Plan] = []
    private var listingGate: String?
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var recordedRequests: [String] = []
    private var downloadedIDs: [Data] = []
    private var progressHandlers: [CameraMediaProgressHandler] = []
    private var temporaryFiles: [URL] = []
    private var activeDownloads = 0
    private var maximumDownloads = 0

    init(items: [CameraMediaItem]) { self.items = items }
    func replaceItems(_ value: [CameraMediaItem]) { items = value }
    func enqueueDownload(gate: String? = nil, error: URLError.Code? = nil) {
        downloadPlans.append(Plan(gate: gate, error: error))
    }
    func holdNextListing(_ gate: String) { listingGate = gate }
    func waiting(_ name: String) -> Bool { gates[name] != nil }
    func release(_ name: String) { gates.removeValue(forKey: name)?.resume() }
    func releaseAll() {
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
        if path.hasSuffix("/events") { throw CancellationError() }
        recordedRequests.append("\(method) \(url.absoluteString)")
        let simulator = path.hasPrefix("/ccapi")
        var object: [String: Any] = [:]
        if path == "/health" {
            object = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]
        } else if path == "/v1/session" {
            object = ["id": "synthetic-batch", "engine": "ccapi"]
        } else if path.hasSuffix("/media") {
            let snapshot = items
            if let gate = listingGate {
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
                    object = simulator
                        ? ["filesize": item.sizeBytes!, "protect": item.protected == true ? "enable" : "disable",
                           "rating": item.rating == 0 ? "off" : String(item.rating ?? 0), "contenttype": "image/jpeg"]
                        : mediaJSON(item, simulator: false)
                } else {
                    // Connect's latest-media thumbnail uses only synthetic bytes.
                    return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], body: Self.original)
                }
            }
        } else if path.hasSuffix("/info") {
            object = ["connected": true, "model": "Synthetic batch", "serial": "SYNTHETIC-BATCH", "api": "fixture"]
        } else if path.hasSuffix("/capabilities") {
            object = simulator ? [:] : ["supported": ["MEDIA_BROWSER", "MEDIA_DOWNLOAD", "MEDIA_DELETE", "MEDIA_THUMBNAIL",
                "MEDIA_PROTECT", "MEDIA_RATING"]]
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
        if let gate = plan.gate { await withCheckedContinuation { gates[gate] = $0 } }
        if let error = plan.error { throw URLError(error) }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Self.original.write(to: file)
        temporaryFiles.append(file)
        return CameraHTTPDownloadResponse(statusCode: 200,
            headers: ["content-type": "image/jpeg", "content-length": String(Self.original.count)], temporaryFileURL: file)
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
private actor DeliveryReplyGate {
    private let stage: MediaDeliveryReplyStage
    private var armed = true
    private var continuation: CheckedContinuation<Void, Never>?
    private(set) var waiting = false

    init(stage: MediaDeliveryReplyStage) { self.stage = stage }

    func enter(_ current: MediaDeliveryReplyStage) async {
        guard armed, current == stage else { return }
        armed = false
        await withCheckedContinuation { continuation in
            self.continuation = continuation
            waiting = true
        }
    }

    func release() {
        waiting = false
        let pending = continuation
        continuation = nil
        pending?.resume()
    }
}
