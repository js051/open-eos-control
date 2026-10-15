import AVFoundation
import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Gates record cancellation but ignore it deliberately: late completions must not own newer UI state.
@MainActor
final class CaptureMediaJourneyTests: XCTestCase {
    #if DEBUG
    func testDownloadStageTraceRequiresExactOptInAndRetainsOnlySixteenAllowlistedStages() {
        let allowed = ["request", "rejected-unsupported", "rejected-busy", "started", "history",
                       "request-original", "reply-original", "validated", "published", "cancelled", "failed"]
        XCTAssertEqual(Set(MediaDownloadStageTrace.Stage.allCases.map(\.rawValue)), Set(allowed))
        for environment in [[:], ["OEC_MEDIA_DOWNLOAD_TRACE": "0"], ["OEC_MEDIA_DOWNLOAD_TRACE": "true"]] {
            var disabled = MediaDownloadStageTrace(environment: environment)
            disabled.record(.request)
            XCTAssertFalse(disabled.enabled)
            XCTAssertTrue(disabled.entries.isEmpty)
            XCTAssertNil(disabled.accessibilityValue)
        }
        var trace = MediaDownloadStageTrace(environment: ["OEC_MEDIA_DOWNLOAD_TRACE": "1"])
        let stages = (0..<40).map { MediaDownloadStageTrace.Stage.allCases[$0 % allowed.count] }
        for stage in stages { trace.record(stage) }
        XCTAssertEqual(trace.entries.count, 16)
        XCTAssertEqual(trace.entries, Array(stages.suffix(16)))
        XCTAssertEqual(trace.accessibilityValue, stages.suffix(16).map(\.rawValue).joined(separator: "|"))
        XCTAssertTrue(trace.entries.allSatisfy { allowed.contains($0.rawValue) })
    }
    #endif

    func testManualRecordingOriginalPublishesMatchingIDBytesAndExportPayload() async throws {
        let peer = CaptureMediaPeer(recording: true)
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        await peer.queueListings([.items([.newVideo])])
        await state.toggleRecording()
        await state.latestMediaTask?.value
        let video = try XCTUnwrap(state.latestMediaItem)
        XCTAssertEqual(video.id, "NEW-VIDEO")
        XCTAssertTrue(mediaIsVideo(video))
        // Exercise the manual original through the real DesktopBridgeClient,
        // without an AVPlayer fallback being able to stand in for that request.
        await peer.holdNext("original")
        state.startMediaDownload(video)
        let download = try XCTUnwrap(state.mediaDownloadTask)
        try await waitForGate(peer, "original")
        XCTAssertEqual(state.activeMediaDownloadID, video.id)
        XCTAssertTrue(state.isBusy(.media))
        XCTAssertNil(state.downloadedFile(for: video))
        XCTAssertNil(state.makeMediaDownloadExportPayload(for: video))
        await peer.release("original")
        await download.value
        let original = try XCTUnwrap(state.downloadedFile(for: video))
        XCTAssertTrue(state.isMediaDownloaded(video))
        XCTAssertEqual(try Data(contentsOf: original), try CaptureMediaPeer.videoOriginalBytes())
        XCTAssertEqual(state.downloadedFileName, video.name)
        XCTAssertNil(state.activeMediaDownloadID)
        XCTAssertFalse(state.isBusy(.media))
        XCTAssertNil(state.lastError)
        let export = try XCTUnwrap(state.makeMediaDownloadExportPayload(for: video))
        XCTAssertEqual(export.fileURLs, [original])
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(export.fileURLs.first)), try CaptureMediaPeer.videoOriginalBytes())
        let originals = await peer.count("original")
        let stops = await peer.count("recording-stop")
        let starts = await peer.count("recording-start")
        XCTAssertEqual(originals, 1)
        XCTAssertEqual(stops, 1)
        XCTAssertEqual(starts, 0)
    }

    func testConfirmedRecordingStopFindsDelayedVideoWithoutSelectingOldPhotosOrKnownVideo() async throws {
        let peer = CaptureMediaPeer(recording: true)
        await peer.queueListings([.items([.a, .b, .oldVideo])])
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        XCTAssertTrue(state.recording)
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        let before = await peer.listCount()
        await peer.queueListings([.items([.a, .new, .oldVideo]), .items([.a, .new, .oldVideo, .newVideo])])
        await peer.holdNext("recording-stop")
        let stop = Task { await state.toggleRecording() }
        try await waitForGate(peer, "recording-stop")
        let beforeStopResponse = await peer.listCount()
        XCTAssertEqual(beforeStopResponse, before, "STOP must not wait for a pre-command card scan")
        XCTAssertEqual(state.captureReviewKind, .video)
        XCTAssertEqual(state.captureReviewState, .capturing)
        XCTAssertTrue(state.latestMediaIsPrevious)
        await peer.release("recording-stop")
        await stop.value
        await state.latestMediaTask?.value
        XCTAssertFalse(state.recording)
        XCTAssertFalse(state.isBusy(.recording))
        XCTAssertEqual(state.captureReviewState, .available)
        XCTAssertEqual(state.latestMediaItem?.id, "NEW-VIDEO")
        XCTAssertFalse(state.latestMediaIsPrevious)
        XCTAssertTrue(state.canOpenLatestMedia)
        XCTAssertNil(state.lastError)
        let after = await peer.listCount()
        let stops = await peer.count("recording-stop")
        let limits = await peer.listLimits()
        let requests = await peer.requestsSinceRecording()
        XCTAssertEqual(after - before, 2)
        XCTAssertEqual(stops, 1)
        XCTAssertTrue(limits.allSatisfy { $0 == "8" })
        XCTAssertTrue(requests.allSatisfy { $0.hasPrefix("GET ") })
    }

    func testRecordingStartAndUnconfirmedStopsDoNotStartVideoReview() async throws {
        for outcome in CaptureRecordingStopOutcome.allCases where outcome != .stopped {
            let peer = CaptureMediaPeer(recording: true)
            let state = makeState(peer)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            await state.latestMediaTask?.value
            await peer.setRecordingStopOutcome(outcome)
            await peer.queueListings([.items([.newVideo])])
            let before = await peer.listCount()
            await state.toggleRecording()
            await state.latestMediaTask?.value
            let after = await peer.listCount()
            let stops = await peer.count("recording-stop")
            XCTAssertEqual(after, before, "An unconfirmed \(outcome) response must not start media review")
            XCTAssertEqual(stops, 1)
            XCTAssertEqual(state.captureReviewState, .idle)
            XCTAssertFalse(state.canRetryCaptureMediaReview)
            XCTAssertEqual(state.latestMediaItem?.id, "A")
            XCTAssertFalse(state.isBusy(.recording))
            if outcome == .rejected || outcome == .responseLost { XCTAssertNotNil(state.lastError) }
            if outcome == .unknown { XCTAssertNil(state.status?.recording) }
            if outcome == .active { XCTAssertTrue(state.recording) }
        }

        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        let before = await peer.listCount()
        await state.toggleRecording()
        await state.latestMediaTask?.value
        let after = await peer.listCount()
        let starts = await peer.count("recording-start")
        let stops = await peer.count("recording-stop")
        XCTAssertTrue(state.recording)
        XCTAssertEqual(starts, 1)
        XCTAssertEqual(stops, 0)
        XCTAssertEqual(after, before)
        XCTAssertEqual(state.captureReviewState, .idle)
    }

    func testConfirmedStopWithoutMediaBrowserDoesNotInventAReview() async throws {
        let peer = CaptureMediaPeer(mediaSupported: false, recording: true)
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.toggleRecording()
        let stops = await peer.count("recording-stop")
        let lists = await peer.listCount()
        XCTAssertEqual(stops, 1)
        XCTAssertEqual(lists, 0)
        XCTAssertFalse(state.recording)
        XCTAssertEqual(state.captureReviewState, .idle)
        XCTAssertNil(state.latestMediaTask)
    }

    func testRecordingReviewExhaustsKnownVideosAndRetryNeverReplaysRecording() async throws {
        let peer = CaptureMediaPeer(recording: true)
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        await peer.queueListings([.items([.oldVideo])])
        state.startMediaLibraryLoad()
        try await waitUntil { state.mediaLibraryLoadStatus == .complete }
        await peer.queueListings(Array(repeating: .items([.oldVideo, .new]), count: 4))
        let before = await peer.listCount()
        await state.toggleRecording()
        await state.latestMediaTask?.value
        let exhausted = await peer.listCount()
        XCTAssertEqual(exhausted - before, 4)
        XCTAssertEqual(state.captureReviewKind, .video)
        XCTAssertEqual(state.captureReviewState, .notReady)
        XCTAssertTrue(state.latestMediaIsPrevious)
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        XCTAssertTrue(state.canRetryCaptureMediaReview)
        await peer.queueListings([.items([.oldVideo, .new, .newVideo])])
        state.retryCaptureMediaReview()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.latestMediaItem?.id, "NEW-VIDEO")
        XCTAssertEqual(state.captureReviewState, .available)
        XCTAssertEqual(state.captureReviewKind, .video)
        let stops = await peer.count("recording-stop")
        let starts = await peer.count("recording-start")
        let choices = await peer.captureChoices()
        let requests = await peer.requestsSinceRecording()
        XCTAssertEqual(stops, 1)
        XCTAssertEqual(starts, 0)
        XCTAssertTrue(choices.isEmpty)
        XCTAssertTrue(requests.allSatisfy { $0.hasPrefix("GET ") })
    }

    func testRecordingReviewReadFailureAndRetryRespectMediaBusyWithoutClearingOtherErrors() async throws {
        let peer = CaptureMediaPeer(recording: true)
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        await peer.queueListings([.failure, .failure, .failure, .failure])
        await state.toggleRecording()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.captureReviewState, .readFailed)
        XCTAssertNil(state.lastError, "A review read failure is not a failed STOP")
        state.reportCubeLutRenderFailure()
        let otherError = state.lastError
        let item = try XCTUnwrap(state.latestMediaItem)
        await peer.holdNext("original")
        let before = await peer.listCount()
        state.retryCaptureMediaReview()
        let interrupted = try XCTUnwrap(state.latestMediaTask)
        state.startMediaDownload(item)
        await interrupted.value
        XCTAssertEqual(state.captureReviewState, .readFailed)
        XCTAssertEqual(state.captureReviewKind, .video)
        XCTAssertFalse(state.canRetryCaptureMediaReview)
        let after = await peer.listCount()
        XCTAssertEqual(after, before)
        try await waitForGate(peer, "original")
        let beforeBusyRetry = await peer.requestCount()
        state.retryCaptureMediaReview()
        let afterBusyRetry = await peer.requestCount()
        XCTAssertEqual(afterBusyRetry, beforeBusyRetry)
        let download = try XCTUnwrap(state.mediaDownloadTask)
        await peer.release("original")
        await download.value
        // The download owns its own success/error. Set a newer unrelated alert now.
        state.reportCubeLutRenderFailure()
        XCTAssertEqual(state.lastError, otherError)
        await peer.queueListings([.items([.a, .newVideo])])
        state.retryCaptureMediaReview()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.captureReviewState, .available)
        XCTAssertEqual(state.latestMediaItem?.id, "NEW-VIDEO")
        XCTAssertEqual(state.lastError, otherError, "A background review must not clear another owner's alert")
        let stops = await peer.count("recording-stop")
        XCTAssertEqual(stops, 1)
    }

    func testRecordingStopRetiresOldListingAndThumbnailBeforeItsResponse() async throws {
        for operation in ["listing", "thumbnail"] {
            let peer = CaptureMediaPeer(recording: true)
            await peer.holdNext(operation)
            let state = makeState(peer)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            try await waitForGate(peer, operation)
            let retired = try XCTUnwrap(state.latestMediaTask)
            let previousID = state.latestMediaItem?.id
            await peer.holdNext("recording-stop")
            let stop = Task { await state.toggleRecording() }
            try await waitForGate(peer, "recording-stop")
            try await waitForCancellation(peer, operation)
            await peer.release(operation)
            await retired.value
            XCTAssertEqual(state.captureReviewState, .capturing)
            XCTAssertEqual(state.captureReviewKind, .video)
            XCTAssertEqual(state.latestMediaItem?.id, previousID)
            XCTAssertNil(state.latestMediaThumbnail)
            await peer.queueListings([.items([.newVideo])])
            await peer.release("recording-stop")
            await stop.value
            await state.latestMediaTask?.value
            XCTAssertEqual(state.captureReviewState, .available)
            XCTAssertEqual(state.latestMediaItem?.id, "NEW-VIDEO")
        }
    }

    func testRetiredStopSuccessAndFailureCannotStartReviewOrClearNewCaptureOwner() async throws {
        for outcome in [CaptureRecordingStopOutcome.stopped, .rejected] {
            let oldPeer = CaptureMediaPeer(recording: true)
            let newPeer = CaptureMediaPeer()
            let state = makeState([oldPeer, newPeer])
            defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
            await state.connect()
            await state.latestMediaTask?.value
            await oldPeer.setRecordingStopOutcome(outcome)
            await oldPeer.holdNext("recording-stop")
            let oldStop = Task { await state.toggleRecording() }
            try await waitForGate(oldPeer, "recording-stop")
            await state.disconnect()
            await state.connect()
            await state.latestMediaTask?.value
            await state.setCaptureMode(.photo)
            XCTAssertEqual(state.captureMode, .photo)
            await newPeer.holdNext("capture")
            let capture = Task { await state.captureStill() }
            try await waitForGate(newPeer, "capture")
            state.reportCubeLutRenderFailure()
            let currentError = state.lastError
            let before = await oldPeer.listCount()
            await oldPeer.release("recording-stop")
            await oldStop.value
            let after = await oldPeer.listCount()
            XCTAssertEqual(after, before)
            XCTAssertEqual(state.captureReviewKind, .photo)
            XCTAssertEqual(state.captureReviewState, .capturing)
            XCTAssertTrue(state.isBusy(.capture))
            XCTAssertEqual(state.lastError, currentError)
            XCTAssertNil(state.latestMediaTask)
            await newPeer.queueListings([.items([.newVideo, .new])])
            await newPeer.release("capture")
            await capture.value
            await state.latestMediaTask?.value
            XCTAssertEqual(state.captureReviewState, .available)
            XCTAssertEqual(state.latestMediaItem?.id, "N", "The new still owner keeps its photo selector")
        }
    }

    func testNewStillCaptureRetiresHeldVideoReviewAndRestoresPhotoSelection() async throws {
        let peer = CaptureMediaPeer(recording: true)
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        await peer.queueListings([.items([.newVideo])])
        await peer.holdNext("listing")
        await state.toggleRecording()
        try await waitForGate(peer, "listing")
        let oldReview = try XCTUnwrap(state.latestMediaTask)
        await state.setCaptureMode(.photo)
        XCTAssertEqual(state.captureMode, .photo)
        await peer.holdNext("capture")
        let capture = Task { await state.captureStill() }
        try await waitForGate(peer, "capture")
        await peer.release("listing")
        await oldReview.value
        XCTAssertEqual(state.captureReviewKind, .photo)
        XCTAssertEqual(state.captureReviewState, .capturing)
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        await peer.queueListings([.items([.newVideo, .new])])
        await peer.release("capture")
        await capture.value
        await state.latestMediaTask?.value
        XCTAssertEqual(state.latestMediaItem?.id, "N")
        XCTAssertEqual(state.captureReviewState, .available)
    }

    func testRecordingStopRetiresPendingStillReviewAndSwitchesToVideoSelection() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        await peer.queueListings([.items([.new])])
        await peer.holdNext("listing")
        await state.captureStill()
        try await waitForGate(peer, "listing")
        let oldReview = try XCTUnwrap(state.latestMediaTask)
        XCTAssertEqual(state.captureReviewKind, .photo)
        await state.toggleRecording()
        XCTAssertTrue(state.recording)
        await peer.holdNext("recording-stop")
        let stop = Task { await state.toggleRecording() }
        try await waitForGate(peer, "recording-stop")
        try await waitForCancellation(peer, "listing")
        await peer.release("listing")
        await oldReview.value
        XCTAssertEqual(state.captureReviewKind, .video)
        XCTAssertEqual(state.captureReviewState, .capturing)
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        await peer.queueListings([.items([.new, .newVideo])])
        await peer.release("recording-stop")
        await stop.value
        await state.latestMediaTask?.value
        XCTAssertEqual(state.latestMediaItem?.id, "NEW-VIDEO")
        XCTAssertEqual(state.captureReviewState, .available)
        let starts = await peer.count("recording-start")
        let stops = await peer.count("recording-stop")
        let choices = await peer.captureChoices()
        XCTAssertEqual(starts, 1)
        XCTAssertEqual(stops, 1)
        XCTAssertEqual(choices.count, 1)
    }

    func testRecordingReviewContentEventPreservesPendingVideoSelectorUntilReadOnlyRetry() async throws {
        let peer = CaptureMediaPeer(eventPolling: true, recording: true)
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        try await waitForGate(peer, "event")
        await state.toggleRecording()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.captureReviewState, .notReady)
        let before = await peer.listCount()
        await peer.queueListings([.items([.new, .newVideo])])
        await peer.release("event")
        try await waitForEventPoll(peer, count: 2)
        let after = await peer.listCount()
        XCTAssertEqual(after, before)
        XCTAssertEqual(state.captureReviewState, .notReady)
        XCTAssertEqual(state.captureReviewKind, .video)
        state.retryCaptureMediaReview()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.latestMediaItem?.id, "NEW-VIDEO")
        XCTAssertEqual(state.captureReviewState, .available)
        await peer.release("event")
        try await waitForEventPoll(peer, count: 3)
        XCTAssertEqual(state.latestMediaItem?.id, "NEW-VIDEO", "An unopened video candidate survives a content event")
    }

    func testRecordingReviewOpensExistingVideoPlaybackAndKeepsOriginalDownloadSeparate() async throws {
        let peer = CaptureMediaPeer(recording: true)
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        await peer.queueListings([.items([.newVideo])])
        await state.toggleRecording()
        await state.latestMediaTask?.value
        let video = try XCTUnwrap(state.latestMediaItem)
        await state.openLatestMedia()
        XCTAssertEqual(state.screen, .media)
        XCTAssertEqual(state.mediaPreviewItem?.id, video.id)
        XCTAssertNotNil(state.mediaVideoPlayback)
        XCTAssertNil(state.mediaPreviewData)
        XCTAssertEqual(state.captureReviewState, .idle)
        XCTAssertFalse(state.canRetryMediaPreview, "Video uses the existing playback flow, not JPEG retry")
        let playback = try XCTUnwrap(state.mediaVideoPlayback)
        try await waitUntil { playback.player.currentItem?.status == .readyToPlay }
        let allocations = await peer.count("playback")
        let streamReads = await peer.count("playback-GET")
        let photoPreviews = await peer.count("preview")
        let originalsBefore = await peer.count("original")
        XCTAssertEqual(allocations, 1)
        XCTAssertGreaterThan(streamReads, 0)
        XCTAssertEqual(photoPreviews, 0)
        XCTAssertEqual(originalsBefore, 0)
        XCTAssertNil(playback.failure)
        XCTAssertFalse(playback.isPreparingFallback)
        state.closeMediaPreview()
        state.startMediaDownload(video)
        await state.mediaDownloadTask?.value
        let original = try XCTUnwrap(state.downloadedFile(for: video))
        XCTAssertEqual(try Data(contentsOf: original), try CaptureMediaPeer.videoOriginalBytes())
        XCTAssertEqual(state.downloadedFileName, "NEW-VIDEO.MP4")
        let originalsAfter = await peer.count("original")
        let stops = await peer.count("recording-stop")
        XCTAssertEqual(originalsAfter, 1)
        XCTAssertEqual(stops, 1)
    }

    func testClosingVideoBeforeScheduledFallbackRunsDoesNotRequestOriginal() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        let playback = try await openFixtureVideo(state, peer: peer)

        // There is no suspension between scheduling and closing on MainActor, so
        // this exercises a cancelled task that has not entered prepareFallback yet.
        playback.handlePlaybackFailure(URLError(.networkConnectionLost))
        let fallback = try XCTUnwrap(playback.fallbackTask)
        XCTAssertTrue(playback.isPreparingFallback)
        state.closeMediaPreview()
        try await waitForPlaybackFallback(fallback)

        let originals = await peer.count("original")
        XCTAssertEqual(originals, 0, "A closed playback must not start a queued original download")
        XCTAssertNil(playback.player.currentItem)
        XCTAssertNil(playback.failure)
        XCTAssertFalse(playback.isPreparingFallback)
        XCTAssertNil(playback.fallbackTask)
        XCTAssertTrue(try playbackTemporaryDirectories().isEmpty)
    }

    func testCurrentVideoFallbackPublishesOnceAndCloseRemovesItsPrivateFile() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        let playback = try await openFixtureVideo(state, peer: peer)
        let video = try XCTUnwrap(state.mediaPreviewItem)
        await peer.holdNext("original")
        playback.handlePlaybackFailure(URLError(.networkConnectionLost))
        let fallback = try XCTUnwrap(playback.fallbackTask)
        try await waitForGate(peer, "original")
        XCTAssertTrue(playback.isPreparingFallback)
        playback.handlePlaybackFailure(URLError(.networkConnectionLost))
        await peer.release("original")
        try await waitForPlaybackFallback(fallback)

        let asset = try XCTUnwrap(playback.player.currentItem?.asset as? AVURLAsset)
        XCTAssertTrue(asset.url.isFileURL)
        XCTAssertEqual(try Data(contentsOf: asset.url), try CaptureMediaPeer.videoOriginalBytes())
        XCTAssertNil(playback.failure)
        XCTAssertFalse(playback.isPreparingFallback)
        XCTAssertNil(playback.fallbackTask)
        XCTAssertNil(state.downloadedFile(for: video), "Playback fallback is not a manual original download")
        let originals = await peer.count("original")
        XCTAssertEqual(originals, 1, "Repeated failure callbacks must share one fallback")

        state.closeMediaPreview()
        XCTAssertNil(playback.player.currentItem)
        XCTAssertFalse(FileManager.default.fileExists(atPath: asset.url.deletingLastPathComponent().path))
        playback.handlePlaybackFailure(URLError(.networkConnectionLost))
        XCTAssertNil(playback.fallbackTask, "A late failure callback must not reopen closed playback")
    }

    func testClosingVideoDuringFallbackDiscardsLateDownloadAndTemporaryFiles() async throws {
        for failOriginal in [false, true] {
            let peer = CaptureMediaPeer()
            let state = makeState(peer)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            let playback = try await openFixtureVideo(state, peer: peer)
            let video = try XCTUnwrap(state.mediaPreviewItem)
            await peer.holdNext("original")
            if failOriginal { await peer.failNext("original") }
            playback.handlePlaybackFailure(URLError(.networkConnectionLost))
            let fallback = try XCTUnwrap(playback.fallbackTask)
            try await waitForGate(peer, "original")
            let directories = try playbackTemporaryDirectories()
            XCTAssertEqual(directories.count, 1)

            state.closeMediaPreview()
            try await waitForCancellation(peer, "original")
            let stillHeld = await peer.waiting("original")
            XCTAssertTrue(stillHeld, "Close must cancel without waiting for an uncooperative download")
            XCTAssertNil(playback.player.currentItem)
            XCTAssertFalse(playback.isPreparingFallback)
            await peer.release("original")
            try await waitForPlaybackFallback(fallback)

            let originals = await peer.count("original")
            let temporaryFiles = await peer.originalTemporaryFiles()
            XCTAssertEqual(originals, 1)
            XCTAssertEqual(temporaryFiles.count, 1, "The cancelled transport still returns its late result")
            XCTAssertTrue((directories + temporaryFiles).allSatisfy {
                !FileManager.default.fileExists(atPath: $0.path)
            })
            XCTAssertNil(playback.player.currentItem, "A late result must not replace the closed player")
            XCTAssertNil(playback.failure, "A retired fallback must not publish a late failure")
            XCTAssertNil(playback.fallbackTask)
            XCTAssertNil(state.mediaVideoPlayback)
            XCTAssertNil(state.downloadedFile(for: video))
        }
    }

    func testRecordingReviewResourcesDescribeVideosWithoutClaimingCaptureProvenance() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        for (language, video, older) in [("en", "video", "older file"), ("zh-Hant", "影片", "舊檔")] {
            let source = try String(contentsOf: root.appendingPathComponent("App/Resources/\(language).lproj/Localizable.strings"), encoding: .utf8)
            func line(_ key: String) throws -> String {
                let matches = source.split(separator: "\n").filter { $0.hasPrefix("\"\(key)\" = ") }
                XCTAssertEqual(matches.count, 1)
                return String(try XCTUnwrap(matches.first))
            }
            for status in [CaptureReviewState.searching, .notReady, .readFailed, .available] {
                XCTAssertTrue(try line(CaptureReviewKind.video.localizationKey(for: status)).contains(video))
                XCTAssertEqual(CaptureReviewKind.photo.localizationKey(for: status), status.localizationKey)
            }
            XCTAssertTrue(try line("recording_review_limited_list").contains(older))
            for suffix in ["capturing", "limited_list", "retry", "previous_badge"] {
                let videoKey = CaptureReviewKind.video.localizationKey(suffix: suffix)
                let photoKey = CaptureReviewKind.photo.localizationKey(suffix: suffix)
                XCTAssertEqual(videoKey, "recording_review_\(suffix)")
                XCTAssertEqual(photoKey, "capture_review_\(suffix)")
                _ = try line(videoKey)
                _ = try line(photoKey)
            }
            for key in ["open_previous_video_named", "open_visible_video_named"] {
                XCTAssertTrue(try line(key).contains(video))
            }
        }
    }

    func testPhotoReviewKeepsFirstUnknownDatePhotoAheadOfFutureVideoAndLaterDatedPhoto() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        state.setShutterAutofocus(false)
        let undated = CaptureFixtureItem(id: "UNDATED", date: "")
        let futureVideo = CaptureFixtureItem(id: "FUTURE-VIDEO", date: "2099-01-01T00:00:00Z", video: true)
        await peer.queueListings([.items([.a, futureVideo, undated, .new, .b])])
        let before = await peer.listCount()
        await state.captureStill()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.latestMediaItem?.id, "UNDATED")
        XCTAssertEqual(state.captureReviewKind, .photo)
        XCTAssertEqual(state.captureReviewState, .available)
        XCTAssertFalse(state.latestMediaIsPrevious)
        let after = await peer.listCount()
        let choices = await peer.captureChoices()
        let requests = await peer.requestsSinceCapture()
        let limits = await peer.listLimits()
        XCTAssertEqual(after - before, 1)
        XCTAssertEqual(choices, [false], "Review must not replay the shutter")
        XCTAssertTrue(requests.allSatisfy { $0.hasPrefix("GET ") })
        XCTAssertTrue(limits.allSatisfy { $0 == "8" })
    }

    func testRecentReviewRetainsFirstUnknownDateRegardlessOfFutureCameraMetadata() async throws {
        let peer = CaptureMediaPeer()
        await peer.queueListings([.items([CaptureFixtureItem(id: "UNDATED", date: ""), .a, .newVideo])])
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.latestMediaItem?.id, "UNDATED")
        let choices = await peer.captureChoices()
        let starts = await peer.count("recording-start")
        let stops = await peer.count("recording-stop")
        XCTAssertTrue(choices.isEmpty)
        XCTAssertEqual(starts, 0)
        XCTAssertEqual(stops, 0)
    }

    func testOnlyAcknowledgedBridgeReadbackErrorStartsReviewWithoutInventingCaptureSuccess() async throws {
        for code in ["CAPTURE_STATUS_READBACK_FAILED", "CCAPI_UNREACHABLE", "CAPTURE_FAILED", "SHUTTER_RELEASE_UNCONFIRMED"] {
            let peer = CaptureMediaPeer()
            let state = makeState(peer)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            try await waitUntil { state.latestMediaItem?.id == "A" && !state.latestMediaThumbnailLoading }
            state.setShutterAutofocus(false)
            await peer.setCaptureError(code)
            await peer.queueListings([.items([.a, .b, .new])])
            let before = await peer.listCount()
            let previousMode = state.status?.mode
            await state.captureStill()
            await state.latestMediaTask?.value
            let after = await peer.listCount()
            let acknowledged = code == "CAPTURE_STATUS_READBACK_FAILED"
            XCTAssertEqual(after - before, acknowledged ? 1 : 0)
            XCTAssertEqual(state.captureReviewState, acknowledged ? .available : .idle)
            XCTAssertEqual(state.latestMediaItem?.id, acknowledged ? "N" : "A")
            XCTAssertEqual(state.status?.mode, previousMode)
            XCTAssertFalse(state.shutterFlash, "A readback error must not flash a successful exposure")
            XCTAssertNotNil(state.lastError)
            let choices = await peer.captureChoices()
            XCTAssertEqual(choices, [false], "An error must not replay the shutter or change its AF choice")
        }
    }

    func testAcknowledgedReadbackRecoveryKeepsAllKnownIDsAndOneReadOnlyRetryOwner() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem?.id == "A" && !state.latestMediaThumbnailLoading }
        await peer.queueListings([.items([.a, .b, .libraryOnly])])
        state.startMediaLibraryLoad()
        try await waitUntil { state.mediaLibraryLoadStatus == .complete }
        state.setShutterAutofocus(false)
        await peer.setCaptureError("CAPTURE_STATUS_READBACK_FAILED")
        await peer.queueListings([.items([.b, .a]), .items([.libraryOnly]), .items([.b]), .items([.a, .b])])
        let before = await peer.listCount()
        await state.captureStill()
        try await waitUntil { state.captureReviewState == .notReady }
        let after = await peer.listCount()
        XCTAssertEqual(after - before, 4, "Use the existing bounded lookup, without a pre-capture scan")
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        XCTAssertTrue(state.latestMediaIsPrevious)
        let warning = try XCTUnwrap(state.lastError)
        await peer.queueListings([.items([.a, .b, .libraryOnly, .new])])
        await peer.holdNext("listing", gate: "readback-retry")
        state.retryCaptureMediaReview()
        let owner = try XCTUnwrap(state.latestMediaTask)
        try await waitForGate(peer, "readback-retry")
        let heldCount = await peer.listCount()
        state.retryCaptureMediaReview()
        let repeatedCount = await peer.listCount()
        XCTAssertEqual(repeatedCount, heldCount, "A repeated retry cannot create another listing owner")
        await peer.release("readback-retry")
        await owner.value
        XCTAssertEqual(state.captureReviewState, .available)
        XCTAssertEqual(state.latestMediaItem?.id, "N", "Known future-dated IDs cannot hide the older-dated new item")
        XCTAssertEqual(state.lastError, warning)
        XCTAssertFalse(state.shutterFlash)
        let choices = await peer.captureChoices()
        let requests = await peer.requestsSinceCapture()
        XCTAssertEqual(choices, [false])
        XCTAssertTrue(requests.allSatisfy { $0.hasPrefix("GET ") })
    }

    func testReadbackRecoveryDistinguishesUnreadListsAndEmptyListsIncludingAnEmptyBaseline() async throws {
        for emptyBaseline in [false, true] {
            let peer = CaptureMediaPeer()
            if emptyBaseline { await peer.queueListings([.items([])]) }
            let state = makeState(peer)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            try await waitUntil { state.latestMediaTask == nil && !state.latestMediaThumbnailLoading }
            state.setShutterAutofocus(false)
            await peer.setCaptureError("CAPTURE_STATUS_READBACK_FAILED")
            await peer.queueListings([.failure, .failure, .failure, .failure])
            let before = await peer.listCount()
            await state.captureStill()
            await state.latestMediaTask?.value
            XCTAssertEqual(state.captureReviewState, .readFailed)
            XCTAssertTrue(state.canRetryCaptureMediaReview)
            let warning = try XCTUnwrap(state.lastError)
            await peer.queueListings([.items([]), .items([]), .items([]), .items([])])
            state.retryCaptureMediaReview()
            await state.latestMediaTask?.value
            let after = await peer.listCount()
            XCTAssertEqual(after - before, 8, "An empty known-ID set still owns two complete four-read attempts")
            XCTAssertEqual(state.captureReviewState, .notReady)
            XCTAssertTrue(state.canRetryCaptureMediaReview)
            let expectedPrevious: String? = emptyBaseline ? nil : "A"
            XCTAssertEqual(state.latestMediaItem?.id, expectedPrevious)
            XCTAssertEqual(state.lastError, warning)
            await peer.queueListings([.items([.new])])
            state.retryCaptureMediaReview()
            await state.latestMediaTask?.value
            XCTAssertEqual(state.captureReviewState, .available)
            XCTAssertEqual(state.latestMediaItem?.id, "N")
            XCTAssertEqual(state.lastError, warning)
            let choices = await peer.captureChoices()
            let requests = await peer.requestsSinceCapture()
            XCTAssertEqual(choices, [false])
            XCTAssertTrue(requests.allSatisfy { $0.hasPrefix("GET ") })
        }
    }

    func testContentEventCannotStealAcknowledgedReadbackRecovery() async throws {
        let peer = CaptureMediaPeer(eventPolling: true)
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem?.id == "A" && !state.latestMediaThumbnailLoading }
        try await waitForGate(peer, "event")
        state.setShutterAutofocus(false)
        await peer.setCaptureError("CAPTURE_STATUS_READBACK_FAILED")
        await state.captureStill()
        try await waitUntil { state.captureReviewState == .notReady }
        let warning = try XCTUnwrap(state.lastError)
        let before = await peer.listCount()
        await peer.queueListings([.items([.new])])
        await peer.release("event")
        try await waitForEventPoll(peer, count: 2)
        let after = await peer.listCount()
        XCTAssertEqual(after, before)
        XCTAssertEqual(state.captureReviewState, .notReady)
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        XCTAssertEqual(state.lastError, warning)
        state.retryCaptureMediaReview()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.latestMediaItem?.id, "N")
        XCTAssertEqual(state.lastError, warning)
        let choices = await peer.captureChoices()
        let requests = await peer.requestsSinceCapture()
        XCTAssertEqual(choices, [false])
        XCTAssertTrue(requests.allSatisfy { $0.hasPrefix("GET ") })
    }

    func testOldListingAndThumbnailCannotOwnNewCaptureReadbackRecovery() async throws {
        for operation in ["listing", "thumbnail"] {
            let peer = CaptureMediaPeer()
            await peer.holdNext(operation)
            let state = makeState(peer)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            try await waitForGate(peer, operation)
            let retiredReview = try XCTUnwrap(state.latestMediaTask)
            state.setShutterAutofocus(false)
            await peer.setCaptureError("CAPTURE_STATUS_READBACK_FAILED")
            await peer.holdNext("capture")
            let capture = Task { await state.captureStill() }
            try await waitForGate(peer, "capture")
            await peer.release(operation)
            await retiredReview.value
            XCTAssertEqual(state.captureReviewState, .capturing)
            XCTAssertNil(state.latestMediaThumbnail)
            XCTAssertTrue(state.isBusy(.capture))
            await peer.queueListings([.items([.new])])
            await peer.release("capture")
            await capture.value
            await state.latestMediaTask?.value
            XCTAssertEqual(state.captureReviewState, .available)
            XCTAssertEqual(state.latestMediaItem?.id, "N")
            XCTAssertNotNil(state.lastError)
            XCTAssertFalse(state.shutterFlash)
            let choices = await peer.captureChoices()
            XCTAssertEqual(choices, [false])
        }
    }

    func testOldCaptureReadbackFailureCannotChangeAReplacementSessionOrItsNewCapture() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem?.id == "A" && !state.latestMediaThumbnailLoading }
        state.setShutterAutofocus(false)
        await peer.setCaptureError("CAPTURE_STATUS_READBACK_FAILED")
        await peer.holdNext("capture", gate: "old-capture")
        let old = Task { await state.captureStill() }
        try await waitForGate(peer, "old-capture")
        await state.disconnect()
        await state.connect()
        try await waitUntil { state.latestMediaItem?.id == "A" && !state.latestMediaThumbnailLoading }
        state.setShutterAutofocus(false)
        await peer.setCaptureError(nil)
        await peer.holdNext("capture", gate: "new-capture")
        let new = Task { await state.captureStill() }
        try await waitForGate(peer, "new-capture")
        let replacementMode = state.status?.mode
        await peer.release("old-capture")
        await old.value
        XCTAssertEqual(state.status?.mode, replacementMode)
        XCTAssertTrue(state.isBusy(.capture))
        XCTAssertEqual(state.captureReviewState, .capturing)
        XCTAssertNil(state.lastError)
        XCTAssertFalse(state.shutterFlash)
        await peer.queueListings([.items([.a, .b, .new])])
        await peer.release("new-capture")
        await new.value
        await state.latestMediaTask?.value
        XCTAssertEqual(state.latestMediaItem?.id, "N")
        let choices = await peer.captureChoices()
        XCTAssertEqual(choices, [false, false], "Each explicitly requested capture stays bound to its own session")
    }

    func testReadbackWarningWithoutMediaCapabilityDoesNotInventAReviewOrAnotherCapture() async throws {
        let peer = CaptureMediaPeer(mediaSupported: false)
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        state.setShutterAutofocus(false)
        await peer.setCaptureError("CAPTURE_STATUS_READBACK_FAILED")
        await state.captureStill()
        XCTAssertEqual(state.captureReviewState, .idle)
        XCTAssertNil(state.latestMediaTask)
        XCTAssertFalse(state.canRetryCaptureMediaReview)
        XCTAssertFalse(state.shutterFlash)
        XCTAssertNotNil(state.lastError)
        let listings = await peer.listCount()
        let choices = await peer.captureChoices()
        XCTAssertEqual(listings, 0)
        XCTAssertEqual(choices, [false])
    }

    func testAcknowledgedReadbackRetryWhileMediaBusySendsZeroRequests() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem?.id == "A" && !state.latestMediaThumbnailLoading }
        state.setShutterAutofocus(false)
        await peer.setCaptureError("CAPTURE_STATUS_READBACK_FAILED")
        await state.captureStill()
        try await waitUntil { state.captureReviewState == .notReady }
        await peer.holdNext("preview")
        let preview = Task { await state.openLatestMedia() }
        try await waitForGate(peer, "preview")
        let before = await peer.requestCount()
        XCTAssertFalse(state.canRetryCaptureMediaReview)
        state.retryCaptureMediaReview()
        let after = await peer.requestCount()
        XCTAssertEqual(after, before)
        XCTAssertEqual(state.captureReviewState, .notReady)
        let choices = await peer.captureChoices()
        XCTAssertEqual(choices, [false])
        await peer.release("preview")
        await preview.value
    }

    func testNewReadbackCaptureRetiresAnEarlierCaptureReviewBeforeItsAcknowledgement() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem?.id == "A" && !state.latestMediaThumbnailLoading }
        state.setShutterAutofocus(false)
        await peer.setCaptureError("CAPTURE_STATUS_READBACK_FAILED")
        await peer.holdNext("listing", gate: "old-review")
        await state.captureStill()
        try await waitForGate(peer, "old-review")
        let oldReview = try XCTUnwrap(state.latestMediaTask)
        await peer.holdNext("capture", gate: "new-capture")
        let capture = Task { await state.captureStill() }
        try await waitForGate(peer, "new-capture")
        await peer.release("old-review")
        await oldReview.value
        XCTAssertEqual(state.captureReviewState, .capturing)
        XCTAssertTrue(state.isBusy(.capture))
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        await peer.queueListings([.items([.a, .b, .new])])
        await peer.release("new-capture")
        await capture.value
        await state.latestMediaTask?.value
        XCTAssertEqual(state.captureReviewState, .available)
        XCTAssertEqual(state.latestMediaItem?.id, "N")
        XCTAssertNotNil(state.lastError)
        XCTAssertFalse(state.shutterFlash)
        let choices = await peer.captureChoices()
        XCTAssertEqual(choices, [false, false])
    }

    func testSharedStopRecoveryResourcesCoverAutofocusWhileKnownBulbLabelsRemain() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        for (language, autofocus) in [("en", "autofocus"), ("zh-Hant", "自動對焦")] {
            let source = try String(contentsOf: root.appendingPathComponent("App/Resources/\(language).lproj/Localizable.strings"), encoding: .utf8)
            func line(_ key: String) throws -> String {
                let matches = source.split(separator: "\n").filter { $0.hasPrefix("\"\(key)\" = ") }
                XCTAssertEqual(matches.count, 1, "Each localization key must be unique")
                return String(try XCTUnwrap(matches.first))
            }
            for key in ["shutter_release_recovery_help", "previous_shutter_release_warning", "previous_shutter_confirmation_help"] {
                XCTAssertTrue(try line(key).contains(autofocus))
            }
            XCTAssertFalse(try line("shutter_release_recovery_help").contains("Bulb"))
            XCTAssertTrue(try line("start_bulb_exposure").contains("Bulb"))
            XCTAssertTrue(try line("stop_bulb_exposure").contains("Bulb"))
            _ = try line("capture_status_readback_failed")
        }
    }

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

    func testCloseCancelsPendingPreviewRequestAndImmediatelyReleasesMedia() async throws {
        try await assertPendingPreviewCancelled { $0.closeMediaPreview() }
    }

    func testScopeResetCancelsPendingPreviewRequestAndImmediatelyReleasesMedia() async throws {
        try await assertPendingPreviewCancelled { $0.setMediaLibraryScope(.all) }
    }

    func testDisconnectCancelsPendingPreviewRequestAndImmediatelyReleasesMedia() async throws {
        try await assertPendingPreviewCancelled { $0.requestDisconnect() }
    }

    func testOfflinePreviewCancelsPendingPreviewRequestAndImmediatelyReleasesMedia() async throws {
        try await assertPendingPreviewCancelled { $0.openOfflinePreview() }
    }

    func testCallerCancellationReachesHeldImageRequestAndSuppressesLateSuccessOrFailure() async throws {
        for failPreview in [false, true] {
            let peer = CaptureMediaPeer()
            let state = makeState(peer)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
            let item = try XCTUnwrap(state.latestMediaItem)
            if failPreview { await peer.failNext("preview") }
            await peer.holdNext("preview")
            let preview = Task { await state.openMediaPreview(item) }
            try await waitForGate(peer, "preview")
            preview.cancel()
            try await waitForCancellation(peer, "preview")
            let stillHeld = await peer.waiting("preview")
            XCTAssertTrue(stillHeld, "Cancellation is observed without making the transport cooperative")
            await peer.release("preview")
            await preview.value
            XCTAssertFalse(state.isBusy(.media))
            XCTAssertFalse(state.mediaPreviewLoading)
            XCTAssertNil(state.mediaPreviewData)
            XCTAssertNil(state.lastError)
            XCTAssertTrue(state.canRetryMediaPreview)
            await state.retryMediaPreview()
            XCTAssertEqual(state.mediaPreviewData, CaptureMediaPeer.displayBytes)
        }
    }

    func testCallerCancelledBeforePreviewStartsNeverSendsImageRequest() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        let item = try XCTUnwrap(state.latestMediaItem)
        let before = await peer.count("preview")
        let preview = Task { await state.openMediaPreview(item) }
        // No suspension: cancel the caller before its MainActor body can enter.
        // An unstructured image task must still receive this existing cancellation.
        preview.cancel()
        await preview.value
        let after = await peer.count("preview")
        XCTAssertEqual(after, before)
        XCTAssertFalse(state.isBusy(.media))
        XCTAssertFalse(state.mediaPreviewLoading)
        XCTAssertNil(state.mediaPreviewData)
        XCTAssertNil(state.lastError)
        XCTAssertTrue(state.canRetryMediaPreview)
        await state.retryMediaPreview()
        XCTAssertEqual(state.mediaPreviewData, CaptureMediaPeer.displayBytes)
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
            XCTAssertFalse(state.isBusy(.media), "Close releases MEDIA before the old transport responds")
            try await waitForCancellation(peer, "old-preview")
            await peer.holdNext("preview", gate: "new-preview")
            let new = Task { await state.openMediaPreview(item) }
            try await waitForGate(peer, "new-preview")
            await peer.release("old-preview")
            await old.value
            XCTAssertTrue(state.isBusy(.media))
            XCTAssertTrue(state.mediaPreviewLoading)
            XCTAssertNil(state.mediaPreviewData)
            XCTAssertNil(state.lastError)
            let newCancellations = await peer.cancellationCount("new-preview")
            XCTAssertEqual(newCancellations, 0, "Retiring the old request cannot cancel its replacement")
            await peer.release("new-preview")
            await new.value
            XCTAssertFalse(state.isBusy(.media))
            XCTAssertEqual(state.mediaPreviewData, CaptureMediaPeer.displayBytes)
        }
    }

    func testClosedPreviewLateSuccessOrFailureCannotCancelOrClearNewOriginalDownload() async throws {
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
            XCTAssertFalse(state.isBusy(.media))
            try await waitForCancellation(peer, "old-preview")
            await peer.holdNext("original")
            state.startMediaDownload(item)
            let download = try XCTUnwrap(state.mediaDownloadTask)
            try await waitForGate(peer, "original")

            await peer.release("old-preview")
            await old.value
            XCTAssertTrue(state.isBusy(.media))
            XCTAssertEqual(state.activeMediaDownloadID, item.id)
            XCTAssertNotNil(state.mediaDownloadTask)
            XCTAssertFalse(download.isCancelled)
            XCTAssertNil(state.mediaPreviewItem)
            XCTAssertNil(state.mediaPreviewData)
            XCTAssertNil(state.downloadedFile(for: item))
            XCTAssertNil(state.lastError)
            let cancellations = await peer.cancellationCount("original")
            XCTAssertEqual(cancellations, 0)

            await peer.release("original")
            await download.value
            XCTAssertFalse(state.isBusy(.media))
            let url = try XCTUnwrap(state.downloadedFile(for: item))
            XCTAssertEqual(try Data(contentsOf: url), CaptureMediaPeer.originalBytes)
        }
    }

    func testClosingCompletedPreviewPreservesHeldOriginalDownloadAndCompletedShare() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        let item = try XCTUnwrap(state.latestMediaItem)
        await state.openMediaPreview(item)
        XCTAssertEqual(state.mediaPreviewData, CaptureMediaPeer.displayBytes)
        await peer.holdNext("original")
        state.startMediaDownload(item)
        let download = try XCTUnwrap(state.mediaDownloadTask)
        try await waitForGate(peer, "original")

        state.closeMediaPreview()
        XCTAssertTrue(state.isBusy(.media))
        XCTAssertEqual(state.activeMediaDownloadID, item.id)
        XCTAssertNotNil(state.mediaDownloadTask)
        XCTAssertFalse(download.isCancelled)
        XCTAssertNil(state.mediaPreviewItem)
        XCTAssertNil(state.mediaPreviewData)
        let cancellations = await peer.cancellationCount("original")
        XCTAssertEqual(cancellations, 0)

        await peer.release("original")
        await download.value
        XCTAssertFalse(state.isBusy(.media))
        let url = try XCTUnwrap(state.downloadedFile(for: item))
        state.closeMediaPreview()
        XCTAssertEqual(state.downloadedFile(for: item), url)
        XCTAssertEqual(try Data(contentsOf: url), CaptureMediaPeer.originalBytes)
    }

    func testClosedPendingVideoRevokesLateTicketWithoutCancellingAllocationOrNewPreview() async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        let photo = try XCTUnwrap(state.latestMediaItem)
        let video = CameraMediaItem(id: "held-video", name: "HELD.MP4", kind: "video", sizeBytes: 4096)
        await peer.holdNext("playback")
        let old = Task { await state.openMediaPreview(video) }
        try await waitForGate(peer, "playback")
        state.closeMediaPreview()
        XCTAssertFalse(state.isBusy(.media))
        let allocationCancellations = await peer.cancellationCount("playback")
        XCTAssertEqual(allocationCancellations, 0, "A late server allocation still needs its DELETE")
        await peer.holdNext("preview", gate: "new-preview")
        let new = Task { await state.openMediaPreview(photo) }
        try await waitForGate(peer, "new-preview")

        await peer.release("playback")
        await old.value
        let allocations = await peer.count("playback")
        let revocations = await peer.count("playback-DELETE")
        let streamReads = await peer.count("playback-GET")
        XCTAssertEqual(allocations, 1)
        XCTAssertEqual(revocations, 1)
        XCTAssertEqual(streamReads, 0, "A retired ticket must not start playback")
        XCTAssertNil(state.mediaVideoPlayback)
        XCTAssertEqual(state.mediaPreviewItem?.id, photo.id)
        XCTAssertTrue(state.mediaPreviewLoading)
        XCTAssertTrue(state.isBusy(.media))
        XCTAssertNil(state.lastError)
        let newCancellations = await peer.cancellationCount("new-preview")
        XCTAssertEqual(newCancellations, 0)
        await peer.release("new-preview")
        await new.value
        XCTAssertFalse(state.isBusy(.media))
        XCTAssertEqual(state.mediaPreviewData, CaptureMediaPeer.displayBytes)
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
        makeState([peer])
    }

    private func makeState(_ peers: [CaptureMediaPeer]) -> CameraAppState {
        let defaults = UserDefaults(suiteName: "CaptureMediaJourneyTests.\(UUID().uuidString)")!
        var nextPeer = 0
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            let peer = peers[min(nextPeer, peers.count - 1)]
            nextPeer += 1
            return .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
        })
        state.autoRefresh = false
        return state
    }

    private func assertPendingPreviewCancelled(
        reset: @MainActor (CameraAppState) -> Void,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws {
        let peer = CaptureMediaPeer()
        let state = makeState(peer)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        try await waitUntil { state.latestMediaItem != nil && !state.latestMediaThumbnailLoading }
        let item = try XCTUnwrap(state.latestMediaItem)
        await peer.holdNext("preview")
        let preview = Task { await state.openMediaPreview(item) }
        try await waitForGate(peer, "preview")
        XCTAssertTrue(state.isBusy(.media), file: file, line: line)
        reset(state)
        XCTAssertFalse(state.isBusy(.media), file: file, line: line)
        XCTAssertNil(state.mediaPreviewItem, file: file, line: line)
        XCTAssertNil(state.mediaPreviewData, file: file, line: line)
        XCTAssertFalse(state.mediaPreviewLoading, file: file, line: line)
        try await waitForCancellation(peer, "preview")
        let stillHeld = await peer.waiting("preview")
        XCTAssertTrue(stillHeld, "Reset must cancel without waiting for transport completion", file: file, line: line)
        await peer.release("preview")
        await preview.value
        XCTAssertFalse(state.isBusy(.media), file: file, line: line)
        XCTAssertNil(state.mediaPreviewItem, file: file, line: line)
        XCTAssertNil(state.mediaPreviewData, file: file, line: line)
        XCTAssertNil(state.lastError, file: file, line: line)
    }

    private func waitUntil(_ condition: @MainActor () -> Bool) async throws {
        for _ in 0..<500 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Timed out waiting for the expected production state")
        throw URLError(.timedOut)
    }

    private func openFixtureVideo(_ state: CameraAppState, peer: CaptureMediaPeer) async throws -> CameraMediaPlayback {
        await peer.queueListings([.items([.newVideo])])
        await state.connect()
        await state.latestMediaTask?.value
        let video = try XCTUnwrap(state.latestMediaItem)
        XCTAssertEqual(video.id, "NEW-VIDEO")
        await state.openMediaPreview(video)
        let playback = try XCTUnwrap(state.mediaVideoPlayback)
        try await waitUntil { playback.player.currentItem?.status == .readyToPlay }
        return playback
    }

    private func waitForPlaybackFallback(_ fallback: Task<Void, Never>) async throws {
        var finished = false
        let observer = Task {
            await fallback.value
            finished = true
        }
        defer { observer.cancel() }
        // Do not await an uncooperative task directly from the test. A failure
        // remains bounded and teardown releases every fixture continuation.
        try await waitUntil { finished }
    }

    private func playbackTemporaryDirectories() throws -> [URL] {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("OpenEOSControl", isDirectory: true)
            .appendingPathComponent("VideoPlayback", isDirectory: true)
        guard FileManager.default.fileExists(atPath: root.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
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

    private func waitForCancellation(_ peer: CaptureMediaPeer, _ gate: String) async throws {
        for _ in 0..<500 {
            if await peer.cancellationCount(gate) > 0 { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Expected request never observed cancellation: \(gate)")
        throw URLError(.timedOut)
    }
}

private struct CaptureFixtureItem: Sendable {
    let id: String
    let date: String
    var video = false
    static let a = Self(id: "A", date: "2030-01-01T00:00:00Z")
    static let b = Self(id: "B", date: "2029-01-01T00:00:00Z")
    static let new = Self(id: "N", date: "2020-01-01T00:00:00Z")
    static let libraryOnly = Self(id: "LIBRARY", date: "2040-01-01T00:00:00Z")
    static let oldVideo = Self(id: "OLD-VIDEO", date: "2028-01-01T00:00:00Z", video: true)
    static let newVideo = Self(id: "NEW-VIDEO", date: "2020-01-01T00:00:00Z", video: true)
    var json: [String: Any] {
        ["id": id, "name": "\(id).\(video ? "MP4" : "JPG")", "kind": video ? "video" : "photo",
         "captureTime": date, "previewAvailable": !video]
    }
}

private enum CaptureFixtureListing: Sendable {
    case items([CaptureFixtureItem])
    case failure
}

private enum CaptureRecordingStopOutcome: Sendable, CaseIterable, Equatable {
    case stopped, active, unknown, rejected, responseLost
}

/// A cancellation handler cannot await the fixture actor; record synchronously for exact ownership assertions.
private final class CaptureRequestCancellations: @unchecked Sendable {
    private let lock = NSLock()
    private var counts: [String: Int] = [:]

    func record(_ gate: String) {
        lock.lock()
        defer { lock.unlock() }
        counts[gate, default: 0] += 1
    }

    func count(_ gate: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return counts[gate, default: 0]
    }
}

private actor CaptureMediaPeer: CameraHTTPTransport {
    // Distinct representation payloads; decoding is separately covered by the valid-JPEG simulator UI fixture.
    static let thumbnailBytes = Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 0xFF, 0xD9])
    static let displayBytes = Data([0xFF, 0xD8, 0xFF, 0xE0, 2, 0xFF, 0xD9])
    static let originalBytes = Data([0xFF, 0xD8, 0xFF, 0xE0, 3, 4, 5, 0xFF, 0xD9])
    static func videoOriginalBytes() throws -> Data {
        guard let url = Bundle(for: CaptureMediaJourneyTests.self)
            .url(forResource: "valid-h264-baseline", withExtension: "mp4") else {
            throw URLError(.fileDoesNotExist)
        }
        return try Data(contentsOf: url)
    }
    private var requests: [String] = []
    private var choices: [Bool] = []
    private var counts: [String: Int] = [:]
    private var downloadTemporaryFiles: [URL] = []
    private var listings: [CaptureFixtureListing] = []
    private var listingLimits: [String?] = []
    private var holds: [String: String] = [:]
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private let cancellations = CaptureRequestCancellations()
    private var failures = Set<String>()
    private var captureRequestEnd = 0
    private var recordingRequestEnd = 0
    private var recording: Bool?
    private var recordingStopOutcome = CaptureRecordingStopOutcome.stopped
    private let eventPolling: Bool
    private let mediaSupported: Bool
    private var captureErrorCode: String?
    init(eventPolling: Bool = false, mediaSupported: Bool = true, recording: Bool = false) {
        self.eventPolling = eventPolling
        self.mediaSupported = mediaSupported
        self.recording = recording
    }
    func setCaptureError(_ code: String?) { captureErrorCode = code }
    func setRecordingStopOutcome(_ outcome: CaptureRecordingStopOutcome) { recordingStopOutcome = outcome }
    func listCount() -> Int { counts["listing", default: 0] }
    func listLimits() -> [String?] { listingLimits }
    func requestCount() -> Int { requests.count }
    func count(_ operation: String) -> Int { counts[operation, default: 0] }
    func originalTemporaryFiles() -> [URL] { downloadTemporaryFiles }
    func cancellationCount(_ gate: String) -> Int { cancellations.count(gate) }
    func captureChoices() -> [Bool] { choices }
    func requestsSinceCapture() -> [String] { Array(requests.dropFirst(captureRequestEnd)) }
    func requestsSinceRecording() -> [String] { Array(requests.dropFirst(recordingRequestEnd)) }
    func queueListings(_ values: [CaptureFixtureListing]) { listings = values }
    func failNext(_ operation: String) { failures.insert(operation) }
    func holdNext(_ operation: String, gate: String? = nil) { holds[operation] = gate ?? operation }
    func waiting(_ gate: String) -> Bool { gates[gate] != nil }
    func release(_ gate: String) { gates.removeValue(forKey: gate)?.resume() }
    func releaseAll() {
        holds.removeAll()
        let pending = gates
        gates.removeAll()
        for continuation in pending.values { continuation.resume() }
    }

    private func waitIfHeld(_ operation: String) async {
        if let gate = holds.removeValue(forKey: operation) {
            let cancellations = cancellations
            await withTaskCancellationHandler {
                await withCheckedContinuation { gates[gate] = $0 }
            } onCancel: {
                // Do not resume or throw: existing stale-result tests still receive late responses.
                cancellations.record(gate)
            }
        }
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        requests.append("\(request.httpMethod ?? "GET") \(request.url!.path)")
        counts["original", default: 0] += 1
        let failure = failures.remove("original") != nil
        await waitIfHeld("original")
        let video = request.url!.path.contains("NEW-VIDEO")
        let bytes: Data
        if video {
            bytes = try Self.videoOriginalBytes()
        } else {
            bytes = Self.originalBytes
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try bytes.write(to: url)
        downloadTemporaryFiles.append(url)
        return CameraHTTPDownloadResponse(statusCode: failure ? 503 : 200,
            headers: ["content-type": video ? "video/mp4" : "image/jpeg", "content-length": "\(bytes.count)"], temporaryFileURL: url)
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        requests.append("\(request.httpMethod ?? "GET") \(path)")
        let object: [String: Any]
        if path == "/health" {
            object = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]
        } else if path == "/v1/session" {
            object = ["id": "capture-review", "engine": "ccapi"]
        } else if path.hasSuffix("/media/NEW-VIDEO/info") {
            var info = CaptureFixtureItem.newVideo.json
            info["sizeBytes"] = try Self.videoOriginalBytes().count
            object = info
        } else if path.hasSuffix("/info") {
            object = ["connected": true, "model": "Synthetic capture review", "serial": "TEST-CAPTURE-REVIEW", "api": "desktop-bridge/v1"]
        } else if path.hasSuffix("/capabilities") {
            let supported = ["STILL_CAPTURE", "VIDEO_RECORDING"]
                + (mediaSupported ? ["MEDIA_BROWSER", "MEDIA_THUMBNAIL", "MEDIA_PREVIEW", "MEDIA_DOWNLOAD", "MEDIA_DELETE"] : [])
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
            let errorCode = captureErrorCode
            await waitIfHeld("capture")
            if let errorCode {
                let error = ["error": ["code": errorCode, "message": "Synthetic capture response failure",
                                       "feature": "STILL_CAPTURE", "engine": "ccapi"]]
                return CameraHTTPResponse(statusCode: 502, body: try JSONSerialization.data(withJSONObject: error))
            }
            object = ["connected": true, "recording": false, "mode": "Manual"]
        } else if path.hasSuffix("/recording/start") {
            counts["recording-start", default: 0] += 1
            recordingRequestEnd = requests.count
            await waitIfHeld("recording-start")
            recording = true
            object = statusJSON()
        } else if path.hasSuffix("/recording/stop") {
            counts["recording-stop", default: 0] += 1
            recordingRequestEnd = requests.count
            let outcome = recordingStopOutcome
            await waitIfHeld("recording-stop")
            switch outcome {
            case .rejected:
                let error = ["error": ["code": "RECORDING_STOP_FAILED", "message": "Synthetic stop rejected",
                                       "feature": "VIDEO_RECORDING", "engine": "ccapi"]]
                return CameraHTTPResponse(statusCode: 503, body: try JSONSerialization.data(withJSONObject: error))
            case .responseLost: throw URLError(.networkConnectionLost)
            case .stopped: recording = false
            case .active: recording = true
            case .unknown: recording = nil
            }
            object = statusJSON()
        } else if path.hasSuffix("/media") {
            counts["listing", default: 0] += 1
            listingLimits.append(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first { $0.name == "limit" }?.value)
            let response = listings.isEmpty ? .items([.a, .b]) : listings.removeFirst()
            await waitIfHeld("listing")
            switch response {
            case let .items(items): object = ["items": items.map(\.json)]
            case .failure: return CameraHTTPResponse(statusCode: 503, body: Data("{}".utf8))
            }
        } else if path.hasSuffix("/playback"), request.httpMethod == "POST" {
            counts["playback", default: 0] += 1
            await waitIfHeld("playback")
            object = ["url": "/v1/media-playback/capture_review_ticket", "expiresInSeconds": 900]
        } else if path == "/v1/media-playback/capture_review_ticket" {
            counts["playback-\(request.httpMethod ?? "GET")", default: 0] += 1
            if request.httpMethod == "DELETE" { return CameraHTTPResponse(statusCode: 204) }
            if failures.remove("playback-GET") != nil { return CameraHTTPResponse(statusCode: 503) }
            return try playbackResponse(request)
        } else if path.hasSuffix("/thumbnail") || path.hasSuffix("/preview") {
            let operation = path.hasSuffix("/thumbnail") ? "thumbnail" : "preview"
            counts[operation, default: 0] += 1
            let failure = failures.remove(operation) != nil
            await waitIfHeld(operation)
            return CameraHTTPResponse(statusCode: failure ? 503 : 200, headers: ["content-type": "image/jpeg"],
                body: operation == "thumbnail" ? Self.thumbnailBytes : Self.displayBytes)
        } else {
            object = statusJSON()
        }
        return CameraHTTPResponse(statusCode: 200, body: try JSONSerialization.data(withJSONObject: object))
    }

    private func playbackResponse(_ request: URLRequest) throws -> CameraHTTPResponse {
        let bytes = try Self.videoOriginalBytes()
        let headers = ["content-type": "video/mp4", "accept-ranges": "bytes"]
        guard let range = request.value(forHTTPHeaderField: "Range"), range.hasPrefix("bytes=") else {
            return CameraHTTPResponse(statusCode: 200,
                headers: headers.merging(["content-length": "\(bytes.count)"]) { _, new in new }, body: bytes)
        }
        let bounds = range.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false)
        guard bounds.count == 2, let start = Int(bounds[0]), start >= 0, start < bytes.count,
              bounds[1].isEmpty || Int(bounds[1]) != nil else {
            return CameraHTTPResponse(statusCode: 416, headers: ["content-range": "bytes */\(bytes.count)"])
        }
        let end = min(Int(bounds[1]) ?? bytes.count - 1, bytes.count - 1)
        guard end >= start else { return CameraHTTPResponse(statusCode: 416) }
        let payload = Data(bytes[start...end])
        return CameraHTTPResponse(statusCode: 206,
            headers: headers.merging([
                "content-length": "\(payload.count)", "content-range": "bytes \(start)-\(end)/\(bytes.count)",
            ]) { _, new in new }, body: payload)
    }

    private func statusJSON() -> [String: Any] {
        var status: [String: Any] = ["connected": true, "mode": "Manual"]
        if let recording { status["recording"] = recording }
        return status
    }
}
