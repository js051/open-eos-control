import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Gates ignore cancellation deliberately: late network completions must not own newer UI state.
@MainActor
final class CaptureMediaJourneyTests: XCTestCase {
    func testFourOldOrFailedReadsBecomeNotReadyAndRetryOnlyReadsWithoutAutofocus() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem?.id == "A" && !state.latestMediaThumbnailLoading }
        state.setShutterAutofocus(false)
        await peer.queueListings([.failure, .items([.b]), .failure, .items([.a, .b])])
        let before = await peer.listCount()
        await state.captureStill()
        try await waitUntil { state.captureReviewState == .notReady }
        let exhausted = await peer.listCount()
        XCTAssertEqual(exhausted - before, 4)
        XCTAssertEqual(state.latestMediaItem?.id, "A", "An older photo stays available under an explicit old-photo label")
        XCTAssertTrue(state.latestMediaIsPrevious)
        XCTAssertNil(state.lastError, "Media delay must not turn a successful shutter into a shutter failure")
        let choices = await peer.captureChoices()
        XCTAssertEqual(choices, [false])
        await peer.queueListings([.items([.a, .b, .new])])
        state.retryCaptureMediaReview()
        try await waitUntil { state.captureReviewState == .available && !state.latestMediaThumbnailLoading }
        XCTAssertEqual(state.latestMediaItem?.id, "N", "Known future-dated A cannot hide newly visible older-dated N")
        XCTAssertFalse(state.latestMediaIsPrevious)
        let retriedChoices = await peer.captureChoices()
        XCTAssertEqual(retriedChoices, [false])
        let requests = await peer.requestsSinceCapture()
        XCTAssertTrue(requests.allSatisfy { $0.hasPrefix("GET ") }, "Review retry never sends a shutter, AF, or other mutation")
    }

    func testFourFailedListingReadsReportReadFailureWithoutClaimingNoNewPhoto() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        state.setShutterAutofocus(false)
        await peer.queueListings([.failure, .failure, .failure, .failure])
        let before = await peer.listCount()
        await state.captureStill()
        let review = try XCTUnwrap(state.latestMediaTask)
        await review.value
        let after = await peer.listCount()
        XCTAssertEqual(after - before, 4)
        XCTAssertEqual(state.captureReviewState, .readFailed, "No successful listing cannot be described as no newly visible photo")
        XCTAssertTrue(state.canRetryCaptureMediaReview)
        XCTAssertTrue(state.latestMediaIsPrevious)
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        XCTAssertNil(state.lastError, "Review failures have their own status, not a global shutter error")
        let choices = await peer.captureChoices()
        XCTAssertEqual(choices, [false])
    }

    func testSuccessfulEmptyReadAfterFailureClearsReviewFailureWithoutAnotherShutter() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        state.setShutterAutofocus(false)
        await peer.queueListings([.failure, .failure, .failure, .failure])
        await state.captureStill()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.captureReviewState, .readFailed)
        let item = try XCTUnwrap(state.latestMediaItem)
        await peer.holdNext("original")
        let beforeBusy = await peer.listCount()
        state.retryCaptureMediaReview()
        let interruptedRetry = try XCTUnwrap(state.latestMediaTask)
        // MEDIA can become busy after retry is accepted but before its task reads.
        state.startMediaDownload(item)
        await interruptedRetry.value
        XCTAssertEqual(state.captureReviewState, .readFailed, "No completed read must not clear the prior read failure")
        let afterBusy = await peer.listCount()
        XCTAssertEqual(afterBusy, beforeBusy)
        try await waitForGate(peer, "original")
        let download = try XCTUnwrap(state.mediaDownloadTask)
        await peer.release("original")
        await download.value
        await peer.queueListings([.failure, .items([]), .items([]), .items([])])
        let before = await peer.listCount()
        state.retryCaptureMediaReview()
        let retry = try XCTUnwrap(state.latestMediaTask)
        await retry.value
        let after = await peer.listCount()
        XCTAssertEqual(after - before, 4, "Failure recovery retains the same bounded read budget")
        XCTAssertEqual(state.captureReviewState, .notReady, "A successful empty listing clears the prior read failure")
        XCTAssertTrue(state.canRetryCaptureMediaReview)
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        XCTAssertNil(state.lastError)
        let choices = await peer.captureChoices()
        XCTAssertEqual(choices, [false])
        let requests = await peer.requestsSinceCapture()
        XCTAssertTrue(requests.allSatisfy { $0.hasPrefix("GET ") })
    }

    func testLoadedLibraryIDsAlsoExcludeOldItemsWithoutAPreCaptureScan() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        await peer.queueListings([.items([.a, .b, .libraryOnly])])
        state.startMediaLibraryLoad()
        try await waitUntil { state.mediaLibraryLoadStatus == .complete }
        let before = await peer.listCount()
        await peer.queueListings([.items([.libraryOnly, .new])])
        await state.captureStill()
        try await waitUntil { state.captureReviewState == .available }
        XCTAssertEqual(state.latestMediaItem?.id, "N")
        let after = await peer.listCount()
        XCTAssertEqual(after - before, 1, "Capture must not add a full-card baseline scan")
    }

    func testOldListingIsRetiredBeforeNewShutterAcknowledgement() async throws {
        let peer = CaptureMediaPeer()
        await peer.holdNext("listing")
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitForGate(peer, "listing")
        let retiredReview = try XCTUnwrap(state.latestMediaTask)
        await peer.holdNext("capture")
        let shutter = Task { await state.captureStill() }
        try await waitForGate(peer, "capture")
        await peer.release("listing")
        await retiredReview.value
        XCTAssertNil(state.latestMediaItem)
        XCTAssertEqual(state.captureReviewState, .capturing)
        await peer.queueListings([.items([.new])])
        await peer.release("capture")
        await shutter.value
        try await waitUntil { state.captureReviewState == .available }
        XCTAssertEqual(state.latestMediaItem?.id, "N")
    }

    func testOldThumbnailIsRetiredBeforeNewShutterAcknowledgement() async throws {
        let peer = CaptureMediaPeer()
        await peer.holdNext("thumbnail")
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitForGate(peer, "thumbnail")
        let retiredReview = try XCTUnwrap(state.latestMediaTask)
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        await peer.holdNext("capture")
        let shutter = Task { await state.captureStill() }
        try await waitForGate(peer, "capture")
        await peer.release("thumbnail")
        await retiredReview.value
        XCTAssertNil(state.latestMediaThumbnail)
        XCTAssertEqual(state.captureReviewState, .capturing)
        await peer.queueListings([.items([.a, .b, .new])])
        await peer.release("capture")
        await shutter.value
        try await waitUntil { state.captureReviewState == .available }
        XCTAssertEqual(state.latestMediaItem?.id, "N")
    }

    func testReadOnlyRetryWhileMediaBusySendsZeroRequests() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        await state.captureStill()
        try await waitUntil { state.captureReviewState == .notReady }
        await peer.holdNext("preview")
        let preview = Task { await state.openLatestMedia() }
        try await waitForGate(peer, "preview")
        let before = await peer.requestCount()
        XCTAssertFalse(state.canRetryCaptureMediaReview)
        state.retryCaptureMediaReview()
        await state.latestMediaTask?.value
        let after = await peer.requestCount()
        XCTAssertEqual(after, before)
        XCTAssertEqual(state.captureReviewState, .notReady)
        await peer.release("preview")
        await preview.value
    }

    func testThumbnailAndPreviewFailuresStillAllowOriginalAndRealPreviewRetry() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        await peer.failNext("thumbnail")
        await peer.queueListings([.items([.new])])
        await state.captureStill()
        try await waitUntil { state.captureReviewState == .available && !state.latestMediaThumbnailLoading }
        XCTAssertNil(state.latestMediaThumbnail)
        XCTAssertTrue(state.canOpenLatestMedia)
        await peer.failNext("preview")
        await state.openLatestMedia()
        XCTAssertNil(state.mediaPreviewData)
        XCTAssertFalse(state.mediaPreviewLoading)
        XCTAssertTrue(state.canRetryMediaPreview)
        let before = await peer.count("preview")
        await state.retryMediaPreview()
        let after = await peer.count("preview")
        XCTAssertEqual(after, before + 1)
        XCTAssertEqual(state.mediaPreviewData, CaptureMediaPeer.displayBytes)
        let item = try XCTUnwrap(state.mediaPreviewItem)
        state.startMediaDownload(item)
        try await waitUntil { !state.isBusy(.media) }
        let url = try XCTUnwrap(state.downloadedFile(for: item))
        XCTAssertEqual(try Data(contentsOf: url), CaptureMediaPeer.originalBytes)
        XCTAssertNotEqual(try Data(contentsOf: url), state.mediaPreviewData)
        XCTAssertNotEqual(try Data(contentsOf: url), CaptureMediaPeer.thumbnailBytes)
        let choices = await peer.captureChoices()
        XCTAssertEqual(choices, [true])
    }

    func testCloseReopenSameItemRetiresOldPreviewSuccessFailureAndBusyOwner() async throws {
        for failOld in [false, true] {
            let peer = CaptureMediaPeer()
            let state = makeState(peer)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
            let item = try XCTUnwrap(state.latestMediaItem)
            if failOld { await peer.failNext("preview") }
            await peer.holdNext("preview", gate: "old-preview")
            let old = Task { await state.openMediaPreview(item) }
            try await waitForGate(peer, "old-preview")
            state.closeMediaPreview()
            await peer.holdNext("preview", gate: "new-preview")
            let new = Task { await state.openMediaPreview(item) }
            try await waitForGate(peer, "new-preview")
            await peer.release("old-preview")
            await old.value
            XCTAssertTrue(state.isBusy(.media))
            XCTAssertTrue(state.mediaPreviewLoading)
            XCTAssertNil(state.mediaPreviewData)
            XCTAssertNil(state.lastError)
            await peer.release("new-preview")
            await new.value
            XCTAssertFalse(state.isBusy(.media))
            XCTAssertEqual(state.mediaPreviewData, CaptureMediaPeer.displayBytes)
        }
    }

    func testSameIDInNewSessionRejectsOldPreviewSuccessAndFailure() async throws {
        for failOld in [false, true] {
            let peer = CaptureMediaPeer()
            let state = makeState(peer)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
            let item = try XCTUnwrap(state.latestMediaItem)
            if failOld { await peer.failNext("preview") }
            await peer.holdNext("preview", gate: "old-preview")
            let old = Task { await state.openMediaPreview(item) }
            try await waitForGate(peer, "old-preview")
            await state.disconnect()
            await state.connect()
            try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
            await peer.holdNext("preview", gate: "new-preview")
            let new = Task { await state.openMediaPreview(item) }
            try await waitForGate(peer, "new-preview")
            await peer.release("old-preview")
            await old.value
            XCTAssertTrue(state.isBusy(.media))
            XCTAssertTrue(state.mediaPreviewLoading)
            XCTAssertNil(state.mediaPreviewData)
            XCTAssertNil(state.lastError)
            await peer.release("new-preview")
            await new.value
        }
    }

    func testSameFilenameDifferentIDCannotShareAnotherItemsOriginal() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        let item = try XCTUnwrap(state.latestMediaItem)
        state.startMediaDownload(item)
        try await waitUntil { !state.isBusy(.media) }
        let completed = try XCTUnwrap(state.downloadedFile(for: item))
        let sameName = CameraMediaItem(id: "different-folder", name: item.name, kind: "photo", previewAvailable: true)
        await state.openMediaPreview(sameName)
        XCTAssertNil(state.downloadedFile(for: sameName))
        XCTAssertFalse(state.isMediaDownloaded(sameName))
        XCTAssertTrue(FileManager.default.fileExists(atPath: completed.path), "Changing the viewer does not revoke completed files")
        XCTAssertEqual(state.downloadedFile(for: item), completed)
        await state.disconnect()
        await state.connect()
        XCTAssertNil(state.downloadedFile(for: item), "A matching ID in a new camera session cannot inherit the old share")
    }

    func testFailedAndCancelledOriginalNeverExposeShareAndRetryNeverReshoots() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        let item = try XCTUnwrap(state.latestMediaItem)
        await peer.failNext("original")
        state.startMediaDownload(item)
        try await waitUntil { !state.isBusy(.media) }
        XCTAssertNil(state.downloadedFile(for: item))
        await peer.holdNext("original")
        state.startMediaDownload(item)
        try await waitForGate(peer, "original")
        state.cancelMediaDownload()
        await peer.release("original")
        try await waitUntil { !state.isBusy(.media) }
        XCTAssertNil(state.downloadedFile(for: item))
        XCTAssertNil(state.downloadedFileURL)
        state.startMediaDownload(item)
        try await waitUntil { !state.isBusy(.media) }
        let url = try XCTUnwrap(state.downloadedFile(for: item))
        XCTAssertEqual(try Data(contentsOf: url), CaptureMediaPeer.originalBytes)
        let choices = await peer.captureChoices()
        XCTAssertEqual(choices, [])
    }

    func testPendingReviewSurvivesContentEventUntilExplicitReadOnlyRetry() async throws {
        let peer = CaptureMediaPeer(eventPolling: true)
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        try await waitForGate(peer, "event")
        await state.captureStill()
        try await waitUntil { state.captureReviewState == .notReady }
        let before = await peer.listCount()
        await peer.queueListings([.items([.new])])
        await peer.release("event")
        for _ in 0..<500 {
            if await peer.count("event") == 2 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        let eventPolls = await peer.count("event")
        XCTAssertEqual(eventPolls, 2, "The real App event loop must process the content notification")
        XCTAssertEqual(state.captureReviewState, .notReady)
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        let after = await peer.listCount()
        XCTAssertEqual(after, before, "An event cannot silently replace an unresolved capture review")
        state.retryCaptureMediaReview()
        try await waitUntil { state.captureReviewState == .available }
        XCTAssertEqual(state.latestMediaItem?.id, "N")
    }

    func testContentEventPreservesUnopenedCandidateAndTheOpenOriginalDelivery() async throws {
        let peer = CaptureMediaPeer(eventPolling: true)
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        try await waitForGate(peer, "event")
        await peer.queueListings([.items([.a, .b, .new])])
        await state.captureStill()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.captureReviewState, .available)
        XCTAssertEqual(state.latestMediaItem?.id, "N")
        let before = await peer.listCount()
        await peer.release("event")
        try await waitForEventPoll(peer, count: 2)
        let after = await peer.listCount()
        XCTAssertEqual(after, before)
        XCTAssertEqual(state.latestMediaItem?.id, "N", "A late event must not replace the unopened capture candidate with future-dated old A")

        await state.openLatestMedia()
        let item = try XCTUnwrap(state.mediaPreviewItem)
        XCTAssertEqual(item.id, "N")
        await peer.holdNext("original")
        state.startMediaDownload(item)
        try await waitForGate(peer, "original")
        await peer.queueListings([.items([.a, .b]), .items([.a, .b])])
        await peer.release("event")
        let download = try XCTUnwrap(state.mediaDownloadTask)
        await peer.release("original")
        await download.value
        try await waitForEventPoll(peer, count: 3)
        XCTAssertFalse(state.mediaItems.contains { $0.id == "N" }, "The new partial list deliberately omits the selected photo")
        XCTAssertEqual(state.mediaPreviewItem?.id, "N", "An item absent from a partial list is not a deleted item")
        XCTAssertEqual(state.mediaPreviewData, CaptureMediaPeer.displayBytes)
        let url = try XCTUnwrap(state.downloadedFile(for: item))
        XCTAssertEqual(try Data(contentsOf: url), CaptureMediaPeer.originalBytes)
        XCTAssertFalse(state.isBusy(.media))

        await peer.failNext("preview")
        await state.openMediaPreview(item)
        let viewerError = try XCTUnwrap(state.lastError)
        await peer.release("event")
        try await waitForEventPoll(peer, count: 4)
        XCTAssertEqual(state.mediaPreviewItem?.id, "N")
        XCTAssertEqual(state.lastError, viewerError, "A background list must not dismiss the viewer's failed-read alert")
        XCTAssertEqual(state.downloadedFile(for: item), url)
        state.clearError()
        await state.retryMediaPreview()
        XCTAssertEqual(state.mediaPreviewData, CaptureMediaPeer.displayBytes)
    }

    func testRetiredDownloadSuccessOrFailureCannotOwnNewSessionSameIDShareBusyOrError() async throws {
        for failOld in [false, true] {
            let peer = CaptureMediaPeer()
            let state = makeState(peer)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
            let item = try XCTUnwrap(state.latestMediaItem)
            if failOld { await peer.failNext("original") }
            await peer.holdNext("original", gate: "old-original")
            state.startMediaDownload(item)
            try await waitForGate(peer, "old-original")
            let retiredDownload = try XCTUnwrap(state.mediaDownloadTask)
            await state.disconnect()
            await state.connect()
            try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
            await peer.holdNext("original", gate: "new-original")
            state.startMediaDownload(item)
            try await waitForGate(peer, "new-original")
            await peer.release("old-original")
            await retiredDownload.value
            XCTAssertTrue(state.isBusy(.media))
            XCTAssertEqual(state.activeMediaDownloadID, item.id)
            XCTAssertNil(state.downloadedFile(for: item))
            XCTAssertNil(state.lastError)
            await peer.release("new-original")
            try await waitUntil { !state.isBusy(.media) }
            let url = try XCTUnwrap(state.downloadedFile(for: item))
            XCTAssertEqual(try Data(contentsOf: url), CaptureMediaPeer.originalBytes)
        }
    }

    func testExplicitDeleteStillClosesTheSelectedViewer() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        await state.openLatestMedia()
        let item = try XCTUnwrap(state.mediaPreviewItem)
        await state.deleteMedia(item)
        XCTAssertNil(state.mediaPreviewItem)
        XCTAssertNil(state.mediaPreviewData)
        XCTAssertNil(state.latestMediaItem)
        XCTAssertFalse(state.mediaItems.contains { $0.id == item.id })
    }

    func testDownloadQueuedBeforeSessionReplacementCannotPublishIntoOfflinePreview() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        let item = try XCTUnwrap(state.latestMediaItem)
        state.startMediaDownload(item)
        let queuedDownload = try XCTUnwrap(state.mediaDownloadTask)
        // No suspension: retire the queued MainActor work and install replacement
        // state before it can enter. Without the entry guard, the preview branch
        // would falsely mark this old camera item as downloaded in the new context.
        state.openOfflinePreview()
        await queuedDownload.value
        XCTAssertTrue(state.isPreview)
        XCTAssertFalse(state.isMediaDownloaded(item))
        XCTAssertNil(state.downloadedFileName)
        XCTAssertNil(state.downloadedFile(for: item))
        let originalGets = await peer.count("original")
        XCTAssertEqual(originalGets, 0)
    }

    private func makeState(_ peer: CaptureMediaPeer) -> CameraAppState {
        let defaults = UserDefaults(suiteName: "CaptureMediaJourneyTests.\(UUID().uuidString)")!
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
        })
        state.autoRefresh = false
        return state
    }

    private func waitUntil(_ condition: @MainActor () -> Bool) async throws {
        for _ in 0..<500 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Timed out waiting for the expected production state")
        throw URLError(.timedOut)
    }

    private func waitForEventPoll(_ peer: CaptureMediaPeer, count: Int) async throws {
        for _ in 0..<500 {
            if await peer.count("event") == count { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Expected event processing to settle before its next poll")
        throw URLError(.timedOut)
    }

    private func waitForGate(_ peer: CaptureMediaPeer, _ gate: String) async throws {
        for _ in 0..<500 {
            if await peer.waiting(gate) { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Expected request gate was never reached: \(gate)")
        throw URLError(.timedOut)
    }
}

private struct CaptureFixtureItem: Sendable {
    let id: String
    let date: String
    static let a = Self(id: "A", date: "2030-01-01T00:00:00Z")
    static let b = Self(id: "B", date: "2029-01-01T00:00:00Z")
    static let new = Self(id: "N", date: "2020-01-01T00:00:00Z")
    static let libraryOnly = Self(id: "LIBRARY", date: "2040-01-01T00:00:00Z")
    var json: [String: Any] {
        ["id": id, "name": "\(id).JPG", "kind": "photo", "captureTime": date, "previewAvailable": true]
    }
}

private enum CaptureFixtureListing: Sendable {
    case items([CaptureFixtureItem])
    case failure
}

private actor CaptureMediaPeer: CameraHTTPTransport {
    // Distinct representation payloads; decoding is separately covered by the valid-JPEG simulator UI fixture.
    static let thumbnailBytes = Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 0xFF, 0xD9])
    static let displayBytes = Data([0xFF, 0xD8, 0xFF, 0xE0, 2, 0xFF, 0xD9])
    static let originalBytes = Data([0xFF, 0xD8, 0xFF, 0xE0, 3, 4, 5, 0xFF, 0xD9])
    private var requests: [String] = []
    private var choices: [Bool] = []
    private var counts: [String: Int] = [:]
    private var listings: [CaptureFixtureListing] = []
    private var holds: [String: String] = [:]
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var failures = Set<String>()
    private var captureRequestEnd = 0
    private let eventPolling: Bool
    init(eventPolling: Bool = false) { self.eventPolling = eventPolling }
    func listCount() -> Int { counts["listing", default: 0] }
    func requestCount() -> Int { requests.count }
    func count(_ operation: String) -> Int { counts[operation, default: 0] }
    func captureChoices() -> [Bool] { choices }
    func requestsSinceCapture() -> [String] { Array(requests.dropFirst(captureRequestEnd)) }
    func queueListings(_ values: [CaptureFixtureListing]) { listings = values }
    func failNext(_ operation: String) { failures.insert(operation) }
    func holdNext(_ operation: String, gate: String? = nil) { holds[operation] = gate ?? operation }
    func waiting(_ gate: String) -> Bool { gates[gate] != nil }
    func release(_ gate: String) { gates.removeValue(forKey: gate)?.resume() }
    func releaseAll() { let pending = gates; gates.removeAll(); for continuation in pending.values { continuation.resume() } }

    private func waitIfHeld(_ operation: String) async {
        if let gate = holds.removeValue(forKey: operation) {
            await withCheckedContinuation { gates[gate] = $0 }
        }
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        requests.append("\(request.httpMethod ?? "GET") \(request.url!.path)")
        counts["original", default: 0] += 1
        let failure = failures.remove("original") != nil
        await waitIfHeld("original")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Self.originalBytes.write(to: url)
        return CameraHTTPDownloadResponse(statusCode: failure ? 503 : 200,
            headers: ["content-type": "image/jpeg", "content-length": "\(Self.originalBytes.count)"], temporaryFileURL: url)
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        requests.append("\(request.httpMethod ?? "GET") \(path)")
        let object: [String: Any]
        if path == "/health" {
            object = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]
        } else if path == "/v1/session" {
            object = ["id": "capture-review", "engine": "ccapi"]
        } else if path.hasSuffix("/info") {
            object = ["connected": true, "model": "Synthetic capture review", "serial": "TEST-CAPTURE-REVIEW", "api": "desktop-bridge/v1"]
        } else if path.hasSuffix("/capabilities") {
            let supported = ["STILL_CAPTURE", "MEDIA_BROWSER", "MEDIA_THUMBNAIL", "MEDIA_PREVIEW", "MEDIA_DOWNLOAD", "MEDIA_DELETE"]
                + (eventPolling ? ["EVENT_POLLING"] : [])
            object = ["supported": supported, "shutterAutofocusSupported": true]
        } else if path.hasSuffix("/events") {
            if request.httpMethod == "DELETE" {
                release("event")
                object = [:]
            } else {
                counts["event", default: 0] += 1
                await withCheckedContinuation { gates["event"] = $0 }
                object = ["changedKeys": ["contents"]]
            }
        } else if path.hasSuffix("/capture/still") {
            let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
            choices.append(payload["af"] as! Bool)
            captureRequestEnd = requests.count
            await waitIfHeld("capture")
            object = ["connected": true, "recording": false, "mode": "Manual"]
        } else if path.hasSuffix("/media") {
            counts["listing", default: 0] += 1
            let response = listings.isEmpty ? .items([.a, .b]) : listings.removeFirst()
            await waitIfHeld("listing")
            switch response {
            case let .items(items): object = ["items": items.map(\.json)]
            case .failure: return CameraHTTPResponse(statusCode: 503, body: Data("{}".utf8))
            }
        } else if path.hasSuffix("/thumbnail") || path.hasSuffix("/preview") {
            let operation = path.hasSuffix("/thumbnail") ? "thumbnail" : "preview"
            counts[operation, default: 0] += 1
            let failure = failures.remove(operation) != nil
            await waitIfHeld(operation)
            return CameraHTTPResponse(statusCode: failure ? 503 : 200, headers: ["content-type": "image/jpeg"],
                body: operation == "thumbnail" ? Self.thumbnailBytes : Self.displayBytes)
        } else {
            object = ["connected": true, "recording": false, "mode": "Manual"]
        }
        return CameraHTTPResponse(statusCode: 200, body: try JSONSerialization.data(withJSONObject: object))
    }
}
