import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// These tests install the production App actions, CameraSession and direct
/// CCAPI client. Only HTTP replies, polling time and store reply timing are
/// controlled; no test replaces the foreground admission/prepare callbacks.
@MainActor
final class ForegroundMediaIntegrationTests: XCTestCase {
    func testNewJPEGIsRetainedOnlyAfterBaselineAndTwoFreshReadsAndIsNotBackfilledOnRestart() async throws {
        try await withFixture(initialNames: ["EXISTING.JPG"]) { fixture in
            let task = try await fixture.startAndEstablishBaseline()
            XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 0)
            let baselineDownloads = await fixture.peer.originalRequests()
            XCTAssertTrue(baselineDownloads.isEmpty)

            try await fixture.discover("NEW.JPG")
            let firstReads = await fixture.peer.freshRequests()
            XCTAssertEqual(firstReads.count, 1)
            let beforeStable = await fixture.peer.originalRequests()
            XCTAssertTrue(beforeStable.isEmpty)
            try await fixture.tickAndWait(seconds: 1)

            XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 1)
            XCTAssertEqual(fixture.state.foregroundMedia.pendingCount, 0)
            XCTAssertEqual(fixture.state.foregroundTemporaryCount, 0)
            XCTAssertNil(fixture.state.makeForegroundMediaExportPayload())
            let originals = await fixture.peer.originalRequests()
            XCTAssertEqual(originals.map(\.path), [ForegroundIntegrationPeer.path("NEW.JPG")])
            XCTAssertTrue(originals.allSatisfy { $0.foreground && $0.method == "GET" })
            let fresh = await fixture.peer.freshRequests()
            XCTAssertEqual(fresh.count, 2)
            let history = await fixture.store.snapshot()
            XCTAssertEqual(history.records.map(\.filename), ["NEW.JPG"])
            XCTAssertEqual(history.records.map(\.status), [.prepared])
            XCTAssertEqual(history.records.map(\.availability), [.available])
            XCTAssertEqual(history.records.first?.actualBytes, Int64(ForegroundIntegrationPeer.original.count))
            XCTAssertEqual(fixture.state.mediaDeliverySnapshot.records.map(\.id), history.records.map(\.id))
            let storedPayload = await fixture.state.makeStoredMediaExportPayload(ids: history.records.map(\.id))
            let payload = try XCTUnwrap(storedPayload)
            XCTAssertEqual(try Data(contentsOf: XCTUnwrap(payload.fileURLs.first)), ForegroundIntegrationPeer.original)
            try await fixture.stopAndDrain(task)

            let restart = try await fixture.startAndEstablishBaseline()
            try await fixture.tickAndWait(seconds: 5)
            XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 0)
            XCTAssertEqual(fixture.state.foregroundMedia.pendingCount, 0)
            let afterRestart = await fixture.peer.originalRequests()
            XCTAssertEqual(afterRestart, originals, "Re-enabling establishes a new baseline and never replays an old original")
            try await fixture.stopAndDrain(restart)
        }
    }

    func testStopWhileBeginItemReplyIsSuspendedRecordsCancellationWithoutOriginalGET() async throws {
        try await withFixture(replyStage: .itemStarted) { fixture in
            let task = try await fixture.startAndEstablishBaseline()
            try await fixture.discover("PENDING.JPG")
            try await fixture.tick(seconds: 1)
            try await integrationWait { await fixture.replyGate.waiting }
            let inMemory = await fixture.store.snapshot()
            XCTAssertEqual(inMemory.records.map(\.status), [.started])
            let cold = await MediaDeliveryStore(root: fixture.root).snapshot()
            XCTAssertEqual(cold.records.map(\.status), [.unknown])
            XCTAssertEqual(cold.records.map(\.availability), [.notRetained])

            fixture.state.stopForegroundMedia()
            XCTAssertEqual(fixture.state.foregroundMedia.state, .stopping)
            XCTAssertTrue(fixture.state.isBusy(.media))
            XCTAssertFalse(fixture.state.startForegroundMedia())
            let beforeReply = await fixture.peer.originalRequests()
            XCTAssertTrue(beforeReply.isEmpty)
            await fixture.replyGate.release()
            try await fixture.drain(task)

            let afterReply = await fixture.peer.originalRequests()
            XCTAssertTrue(afterReply.isEmpty, "A cancelled beginItem await must never admit an original GET")
            let history = await fixture.store.snapshot()
            XCTAssertEqual(history.records.map(\.status), [.cancelled])
            XCTAssertEqual(history.records.map(\.reason), [.cancelled])
            XCTAssertEqual(history.records.map(\.availability), [.notRetained])
            XCTAssertEqual(fixture.state.foregroundMedia.state, .stopped)
            XCTAssertEqual(fixture.state.foregroundMedia.reason, .user)
            XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 0)
            XCTAssertNil(fixture.state.makeForegroundMediaExportPayload())
            XCTAssertFalse(fixture.state.isBusy(.media))
        }
    }

    func testStorageByteAndRecordCapacityRejectBeforeAnyOriginalGET() async throws {
        for recordLimit in [false, true] {
            let limits = MediaDeliveryLimits(maximumRecords: recordLimit ? 1 : 100,
                maximumRetainedBytes: recordLimit ? 1_024 : Int64(ForegroundIntegrationPeer.original.count - 1))
            try await withFixture(limits: limits) { fixture in
                if recordLimit {
                    let ticket = try await fixture.store.beginBatch()
                    let receipt = try await fixture.store.beginItem(ticket, filename: "PRIOR.JPG")
                    try await fixture.store.finishFailure(ticket, receiptID: receipt, cancelled: false)
                }
                let before = await fixture.store.snapshot()
                let task = try await fixture.startAndEstablishBaseline()
                try await fixture.discover("NO_CAPACITY.JPG")
                try await fixture.tick(seconds: 1)
                try await fixture.drain(task)

                XCTAssertEqual(fixture.state.foregroundMedia.state, .failed)
                XCTAssertEqual(fixture.state.foregroundMedia.reason, .storage)
                XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 0)
                let requests = await fixture.peer.originalRequests()
                XCTAssertTrue(requests.isEmpty)
                let after = await fixture.store.snapshot()
                XCTAssertEqual(after.records.map(\.id), before.records.map(\.id), "Rejected preflight must not invent a started receipt")
                XCTAssertNil(fixture.state.makeForegroundMediaExportPayload())
                XCTAssertFalse(fixture.state.isBusy(.media))
            }
        }
    }

    func testStopAndBackgroundKeepOwnershipUntilNoncooperativeOriginalSettles() async throws {
        for background in [false, true] {
            try await withFixture(initialNames: ["MANUAL.JPG"]) { fixture in
                let task = try await fixture.startAndEstablishBaseline()
                try await fixture.discover("INTERRUPTED.JPG")
                await fixture.peer.holdNextOriginal("original")
                try await fixture.tick(seconds: 1)
                try await integrationWait { await fixture.peer.waiting("original") }
                let before = await fixture.peer.requests()
                if background { fixture.state.setApplicationActive(false) }
                else { fixture.state.stopForegroundMedia() }

                XCTAssertTrue(fixture.state.foregroundMedia.isRunning)
                XCTAssertEqual(fixture.state.foregroundMedia.state, .stopping)
                XCTAssertFalse(fixture.state.startForegroundMedia())
                try await assertManualControlsBlocked(fixture)
                let duringStop = await fixture.peer.requests()
                XCTAssertEqual(duringStop, before, "Stop must retain exclusion until the exact original task settles")
                await fixture.peer.release("original")
                try await fixture.drain(task)

                XCTAssertFalse(fixture.state.isBusy(.media))
                XCTAssertEqual(fixture.state.foregroundMedia.reason, background ? .background : .user)
                XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 0)
                XCTAssertEqual(fixture.state.foregroundTemporaryCount, 0)
                let history = await fixture.store.snapshot()
                XCTAssertEqual(history.records.map(\.status), [.cancelled])
                XCTAssertEqual(history.records.map(\.availability), [.notRetained])
                for file in await fixture.peer.returnedTemporaryFiles() {
                    XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
                }
                fixture.state.setApplicationActive(true)
                XCTAssertEqual(fixture.state.foregroundMedia.state, .stopped)
                XCTAssertFalse(fixture.state.foregroundMedia.isRunning, "Returning to foreground does not silently opt in again")
                XCTAssertTrue(fixture.state.canStartForegroundMedia)
                let manual = try XCTUnwrap(fixture.state.mediaItems.first)
                await fixture.state.loadMediaThumbnail(manual)
                XCTAssertEqual(fixture.state.mediaThumbnails[manual.id], ForegroundIntegrationPeer.original,
                    "Manual thumbnail loading is admitted again after foreground ownership settles")
            }
        }
    }

    func testDisconnectDuringOriginalCannotPublishOrUnlockReplacementForegroundRun() async throws {
        try await assertRetiredRunCannotAffectReplacement(retireAfterRetention: false)
    }

    func testDisconnectAfterRetentionCannotPublishOrUnlockReplacementForegroundRun() async throws {
        try await assertRetiredRunCannotAffectReplacement(retireAfterRetention: true)
    }

    func testRetentionFailurePreservesValidTemporaryExportAndPreparedHistoricalFact() async throws {
        try await withFixture(faultInjector: { point in
            if point == .copyChunk { throw MediaDeliveryStoreError.storageUnavailable }
        }) { fixture in
            let task = try await fixture.startAndEstablishBaseline()
            try await fixture.discover("VALID.JPG")
            try await fixture.tick(seconds: 1)
            try await fixture.drain(task)

            XCTAssertEqual(fixture.state.foregroundMedia.state, .failed)
            XCTAssertEqual(fixture.state.foregroundMedia.reason, .storage)
            XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 0, "The counter reports retained App copies")
            XCTAssertEqual(fixture.state.foregroundTemporaryCount, 1)
            XCTAssertTrue(fixture.state.mediaDeliveryHistoryWarning)
            let payload = try XCTUnwrap(fixture.state.makeForegroundMediaExportPayload())
            let file = try XCTUnwrap(payload.fileURLs.first)
            XCTAssertEqual(try Data(contentsOf: file), ForegroundIntegrationPeer.original)
            let history = await fixture.store.snapshot()
            XCTAssertEqual(history.records.map(\.status), [.prepared], "Retention failure cannot relabel a verified original downloadFailed")
            XCTAssertEqual(history.records.map(\.reason), [.retentionUnavailable])
            XCTAssertEqual(history.records.map(\.availability), [.notRetained])
            XCTAssertEqual(history.records.first?.actualBytes, Int64(ForegroundIntegrationPeer.original.count))
            XCTAssertEqual(history.retainedBytes, 0)
            XCTAssertFalse(fixture.state.isBusy(.media))

            await fixture.state.disconnect()
            XCTAssertEqual(fixture.state.foregroundTemporaryCount, 0)
            XCTAssertEqual(try Data(contentsOf: file), ForegroundIntegrationPeer.original,
                "An explicit export payload keeps its valid temporary source alive across disconnect")
            withExtendedLifetime(payload) {}
        }
    }

    func testForegroundTemporaryFilesAttemptUsesFactoryLeaseAfterRetentionFailureAndClear() async throws {
        try await withFixture(faultInjector: { point in
            if point == .copyChunk { throw MediaDeliveryStoreError.storageUnavailable }
        }) { fixture in
            let task = try await fixture.startAndEstablishBaseline()
            try await fixture.discover("FILES-FALLBACK.JPG")
            try await fixture.tick(seconds: 1)
            try await fixture.drain(task)
            XCTAssertEqual(fixture.state.foregroundMedia.state, .failed)
            XCTAssertEqual(fixture.state.foregroundMedia.reason, .storage)
            XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 0)
            XCTAssertEqual(fixture.state.foregroundTemporaryCount, 1)
            let manifest = fixture.root.appendingPathComponent("manifest.json")
            let preparedFacts = try Data(contentsOf: manifest)
            let requests = await fixture.peer.requests()

            let exporter = MediaFilesExportController()
            XCTAssertTrue(exporter.begin(fixture.state.makeForegroundMediaExportPayload()))
            var coordinator: MediaFilesExportCoordinator? = MediaFilesExportCoordinator(
                attempt: try XCTUnwrap(exporter.presentation), owner: exporter)
            let source = try XCTUnwrap(coordinator?.attempt.payload.fileURLs.first)
            let identities = try XCTUnwrap(coordinator?.attempt.payload.filesExportSourceIdentities)
            XCTAssertNil(coordinator?.presentationFailure())
            coordinator?.finish(.unconfirmed)
            XCTAssertEqual(exporter.result?.status, .unconfirmed)
            XCTAssertEqual(exporter.result?.expectedCount, 1)
            XCTAssertEqual(fixture.state.foregroundMedia.state, .failed)
            XCTAssertEqual(fixture.state.foregroundMedia.reason, .storage)
            XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 0)
            XCTAssertEqual(fixture.state.foregroundTemporaryCount, 1)
            XCTAssertEqual(try Data(contentsOf: manifest), preparedFacts)
            let history = await fixture.store.snapshot()
            XCTAssertEqual(history.records.map(\.status), [.prepared])
            XCTAssertEqual(history.records.map(\.reason), [.retentionUnavailable])
            XCTAssertEqual(history.records.map(\.availability), [.notRetained])
            let afterResult = await fixture.peer.requests()
            XCTAssertEqual(afterResult, requests, "Files export cannot restart foreground polling or download another original")

            await fixture.state.disconnect()
            XCTAssertNil(fixture.state.makeForegroundMediaExportPayload())
            XCTAssertEqual(fixture.state.foregroundTemporaryCount, 0)
            let disconnectedRequests = await fixture.peer.requests()
            await fixture.state.clearMediaDeliveryHistory()
            XCTAssertTrue(fixture.state.mediaDeliverySnapshot.records.isEmpty)
            XCTAssertEqual(coordinator?.attempt.payload.fileURLs, [source])
            XCTAssertEqual(coordinator?.attempt.payload.filesExportSourceIdentities, identities)
            XCTAssertEqual(try Data(contentsOf: source), ForegroundIntegrationPeer.original)
            coordinator?.dismantle()
            XCTAssertFalse(exporter.isActive)
            XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
            coordinator = nil
            try await integrationWait { !FileManager.default.fileExists(atPath: source.path) }
            let afterClear = await fixture.peer.requests()
            XCTAssertEqual(afterClear, disconnectedRequests)
        }
    }

    func testManualDownloadIsAdmittedBetweenPollsAndForegroundWaitsForIt() async throws {
        try await withFixture(initialNames: ["MANUAL.JPG"]) { fixture in
            let task = try await fixture.startAndEstablishBaseline()
            XCTAssertFalse(fixture.state.isBusy(.media))
            let manual = try XCTUnwrap(fixture.state.mediaItems.first)
            await fixture.peer.holdNextOriginal("manual")
            fixture.state.startMediaDownload(manual)
            let manualTask = try XCTUnwrap(fixture.state.mediaDownloadTask)
            fixture.track(manualTask)
            try await integrationWait { await fixture.peer.waiting("manual") }
            let before = await fixture.peer.requests()
            try await fixture.tickAndWait(seconds: 5)

            XCTAssertEqual(fixture.state.foregroundMedia.state, .waiting)
            let waiting = await fixture.peer.requests()
            XCTAssertEqual(waiting, before, "A busy manual transfer delays inventory, metadata and originals")
            await fixture.peer.release("manual")
            try await fixture.drain(manualTask)
            XCTAssertNotNil(fixture.state.downloadedFile(for: manual))
            try await fixture.tickAndWait(seconds: 1)
            XCTAssertEqual(fixture.state.foregroundMedia.state, .watching)
            XCTAssertFalse(fixture.state.isBusy(.media))
            try await fixture.stopAndDrain(task)
            let originals = await fixture.peer.originalRequests()
            XCTAssertEqual(originals.count, 1)
            XCTAssertFalse(try XCTUnwrap(originals.first).foreground)
            XCTAssertNil(fixture.state.lastError)
        }
    }

    func testLiveViewRestartSourceAndSizeWaitForForegroundOriginalOwnership() async throws {
        try await withFixture(liveView: true) { fixture in
            XCTAssertTrue(fixture.state.supports(.liveView))
            await fixture.state.setLiveViewSource(.ccapiJPEGPolling)
            XCTAssertEqual(fixture.state.activeLiveViewSource, .ccapiJPEGPolling)
            XCTAssertEqual(fixture.state.selectedLiveViewSource, .ccapiJPEGPolling)
            XCTAssertEqual(fixture.state.liveViewSize, .medium)
            XCTAssertTrue(fixture.state.capabilities?.liveView.sizes.contains(.small) == true)
            let task = try await fixture.startAndEstablishBaseline()
            try await fixture.discover("LIVE_VIEW.JPG")
            await fixture.peer.holdNextOriginal("live-view-original")
            try await fixture.tick(seconds: 1)
            try await integrationWait { await fixture.peer.waiting("live-view-original") }
            let before = await fixture.peer.requests()

            await fixture.state.restartLiveView()
            await fixture.state.setLiveViewSource(.auto)
            await fixture.state.setLiveViewSize(.small)
            XCTAssertEqual(fixture.state.activeLiveViewSource, .ccapiJPEGPolling)
            XCTAssertEqual(fixture.state.selectedLiveViewSource, .ccapiJPEGPolling,
                "Rejected source changes must not mutate the preference before checking admission")
            XCTAssertEqual(fixture.state.liveViewSize, .medium,
                "A supported size still waits for foreground ownership before changing App state")
            XCTAssertTrue(fixture.state.isBusy(.liveView))
            let blocked = await fixture.peer.requests()
            XCTAssertEqual(blocked, before, "Restart must not stop the active stream while an original is owned")

            await fixture.peer.release("live-view-original")
            try await fixture.waitForSleep()
            XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 1)
            try await fixture.stopAndDrain(task)
            let settled = await fixture.peer.requests()
            await fixture.state.setLiveViewSize(.small)
            XCTAssertEqual(fixture.state.liveViewSize, .small)
            let afterSize = await fixture.peer.requests()
            XCTAssertEqual(afterSize, settled, "With automatic frames disabled, changing size alone sends no command")
            await fixture.state.restartLiveView()
            XCTAssertEqual(fixture.state.activeLiveViewSource, .ccapiJPEGPolling)
            XCTAssertEqual(fixture.state.liveViewSize, .small)
            let restarted = await fixture.peer.requests()
            XCTAssertEqual(Array(restarted.dropFirst(settled.count)).map(\.method), ["DELETE", "POST"])
            await fixture.state.setLiveViewSource(.auto)
            XCTAssertEqual(fixture.state.selectedLiveViewSource, .auto)
            XCTAssertEqual(fixture.state.activeLiveViewSource, .ccapiJPEGPolling)
            let changedSource = await fixture.peer.requests()
            XCTAssertEqual(Array(changedSource.dropFirst(restarted.count)).map(\.method), ["DELETE", "POST"])
            XCTAssertNil(fixture.state.lastError)
        }
    }

    func testConnectLatestListingDelaysForegroundInventoryUntilItsExactTaskSettles() async throws {
        try await withFixture(holdLatestOnConnect: true) { fixture in
            let latest = try XCTUnwrap(fixture.state.latestMediaTask)
            XCTAssertFalse(fixture.state.latestMediaThumbnailLoading)
            XCTAssertFalse(fixture.state.mediaLibraryLoading)
            XCTAssertEqual(fixture.state.captureReviewState, .idle)
            XCTAssertTrue(fixture.state.busyOperations.isEmpty,
                "This admission check must depend on the pending latest task, not another busy flag")
            XCTAssertTrue(fixture.state.startForegroundMedia())
            let task = try XCTUnwrap(fixture.state.foregroundMedia.task)
            fixture.track(task)
            try await fixture.waitForSleep()
            XCTAssertEqual(fixture.state.foregroundMedia.state, .waiting)
            let before = await fixture.peer.requests()
            XCTAssertTrue(before.filter(\.foreground).isEmpty)
            try await fixture.tickAndWait(seconds: 5)
            XCTAssertEqual(fixture.state.foregroundMedia.state, .waiting)
            let held = await fixture.peer.requests()
            XCTAssertEqual(held, before, "A pending connect listing cannot overlap the foreground baseline")

            await fixture.peer.release("connect-latest")
            try await fixture.drain(latest)
            XCTAssertNil(fixture.state.latestMediaTask)
            try await fixture.tickAndWait(seconds: 1)
            XCTAssertEqual(fixture.state.foregroundMedia.state, .baseline)
            try await fixture.tickAndWait(seconds: 1)
            XCTAssertEqual(fixture.state.foregroundMedia.state, .watching)
            let admitted = await fixture.peer.requests()
            XCTAssertEqual(admitted.filter(\.foreground).map(\.path),
                Array(repeating: "\(ForegroundIntegrationPeer.root)?kind=number", count: 2))
            XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 0)
            try await fixture.stopAndDrain(task)
        }
    }

    func testForegroundInventoryWaitsForAlreadyAdmittedLiveViewRestartToSettle() async throws {
        try await withFixture(liveView: true) { fixture in
            await fixture.state.setLiveViewSource(.ccapiJPEGPolling)
            XCTAssertEqual(fixture.state.activeLiveViewSource, .ccapiJPEGPolling)
            let task = try await fixture.startAndEstablishBaseline()
            await fixture.peer.holdNextLiveViewStop("restart-stop")
            let restart = Task { await fixture.state.restartLiveView() }
            fixture.track(restart)
            try await integrationWait { await fixture.peer.waiting("restart-stop") }
            XCTAssertTrue(fixture.state.busyOperations.isEmpty,
                "The pending stop must reserve admission before startLiveView owns its busy operation")
            let before = await fixture.peer.requests()
            try await fixture.tickAndWait(seconds: 5)
            XCTAssertEqual(fixture.state.foregroundMedia.state, .waiting)
            await fixture.state.restartLiveView()
            await fixture.state.setLiveViewSource(.auto)
            await fixture.state.setLiveViewSize(.small)
            XCTAssertEqual(fixture.state.selectedLiveViewSource, .ccapiJPEGPolling)
            XCTAssertEqual(fixture.state.liveViewSize, .medium)
            let held = await fixture.peer.requests()
            XCTAssertEqual(held, before,
                "Foreground inventory and reentrant Live View controls must wait through stop/start")

            await fixture.peer.release("restart-stop")
            try await fixture.drain(restart)
            XCTAssertEqual(fixture.state.activeLiveViewSource, .ccapiJPEGPolling)
            XCTAssertNil(fixture.state.lastError)
            let restarted = await fixture.peer.requests()
            XCTAssertEqual(Array(restarted.dropFirst(before.count)).map(\.method), ["POST"])
            try await fixture.tickAndWait(seconds: 1)
            XCTAssertEqual(fixture.state.foregroundMedia.state, .watching)
            let resumed = await fixture.peer.requests()
            let next = Array(resumed.dropFirst(restarted.count))
            XCTAssertEqual(next.map(\.path), ["\(ForegroundIntegrationPeer.root)?kind=number"])
            XCTAssertTrue(next.allSatisfy { $0.foreground && $0.method == "GET" })
            try await fixture.stopAndDrain(task)
        }
    }

    private func assertManualControlsBlocked(_ fixture: ForegroundIntegrationFixture) async throws {
        let item = try XCTUnwrap(fixture.state.mediaItems.first)
        XCTAssertTrue(fixture.state.supports(.stillCapture))
        XCTAssertTrue(fixture.state.supports(.mediaRating))
        XCTAssertTrue(fixture.state.isBusy(.capture))
        XCTAssertTrue(fixture.state.isBusy(.refresh))
        fixture.state.selectMediaItems([item])
        XCTAssertFalse(fixture.state.startMediaBatchDownload())
        fixture.state.startMediaDownload(item)
        XCTAssertNil(fixture.state.mediaDownloadTask)
        fixture.state.startMediaLibraryLoad()
        XCTAssertFalse(fixture.state.mediaLibraryLoading)
        await fixture.state.loadMediaThumbnail(item)
        await fixture.state.loadMediaInfo(item)
        await fixture.state.setMediaRating(item, rating: 5)
        await fixture.state.refresh()
        await fixture.state.captureStill()
    }

    private func assertRetiredRunCannotAffectReplacement(retireAfterRetention: Bool) async throws {
        try await withFixture(replyStage: retireAfterRetention ? .originalRetained : nil, peerCount: 2) { fixture in
            let oldPeer = fixture.peer
            let oldTask = try await fixture.startAndEstablishBaseline()
            try await fixture.discover("OLD.JPG")
            if !retireAfterRetention { await oldPeer.holdNextOriginal("old") }
            try await fixture.tick(seconds: 1)
            if retireAfterRetention { try await integrationWait { await fixture.replyGate.waiting } }
            else { try await integrationWait { await oldPeer.waiting("old") } }

            await fixture.state.disconnect()
            XCTAssertEqual(fixture.state.foregroundMedia.reason, .session)
            XCTAssertNil(fixture.state.makeForegroundMediaExportPayload())
            try await fixture.connectNextPeer()
            let newPeer = fixture.peer
            let newTask = try await fixture.startAndEstablishBaseline()
            try await fixture.discover("NEW.JPG")
            await newPeer.holdNextOriginal("new")
            try await fixture.tick(seconds: 1)
            try await integrationWait { await newPeer.waiting("new") }
            let replacementOwner = fixture.state.mediaLibrarySessionID
            let newRequests = await newPeer.requests()

            if retireAfterRetention { await fixture.replyGate.release() }
            else { await oldPeer.release("old") }
            try await fixture.drain(oldTask)
            XCTAssertEqual(fixture.state.mediaLibrarySessionID, replacementOwner)
            XCTAssertTrue(fixture.state.foregroundMedia.isRunning)
            XCTAssertEqual(fixture.state.foregroundMedia.state, .preparing)
            XCTAssertEqual(fixture.state.foregroundMedia.currentName, "NEW.JPG")
            XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 0)
            XCTAssertTrue(fixture.state.isBusy(.media), "The old defer must not unlock the replacement run")
            XCTAssertNil(fixture.state.makeForegroundMediaExportPayload())
            let afterOldReturn = await newPeer.requests()
            XCTAssertEqual(afterOldReturn, newRequests)

            await newPeer.release("new")
            try await fixture.waitForSleep()
            XCTAssertEqual(fixture.state.foregroundMedia.preparedCount, 1)
            XCTAssertEqual(fixture.state.foregroundMedia.pendingCount, 0)
            XCTAssertEqual(fixture.state.foregroundTemporaryCount, 0)
            let history = await fixture.store.snapshot()
            let old = try XCTUnwrap(history.records.first { $0.filename == "OLD.JPG" })
            let new = try XCTUnwrap(history.records.first { $0.filename == "NEW.JPG" })
            XCTAssertEqual(old.status, retireAfterRetention ? .prepared : .cancelled)
            XCTAssertEqual(old.availability, retireAfterRetention ? .available : .notRetained)
            XCTAssertEqual(new.status, .prepared)
            XCTAssertEqual(new.availability, .available)
            XCTAssertFalse(fixture.state.mediaDeliveryHistoryWarning)
            try await fixture.stopAndDrain(newTask)
        }
    }

    private func withFixture(
        initialNames: [String] = [], limits: MediaDeliveryLimits = .production,
        replyStage: MediaDeliveryReplyStage? = nil, peerCount: Int = 1,
        liveView: Bool = false, holdLatestOnConnect: Bool = false,
        faultInjector: @escaping MediaDeliveryStore.FaultInjector = { _ in },
        _ body: @MainActor (ForegroundIntegrationFixture) async throws -> Void
    ) async throws {
        let fixture = ForegroundIntegrationFixture(initialNames: initialNames, limits: limits,
            replyStage: replyStage, peerCount: peerCount, liveView: liveView, faultInjector: faultInjector)
        do {
            try await fixture.connectNextPeer(holdLatest: holdLatestOnConnect)
            try await body(fixture)
        } catch {
            try await fixture.close()
            throw error
        }
        try await fixture.close()
    }
}

/// Bounded observation of an explicit state/gate; never a sleep that assumes an
/// operation finished. Every started transfer/controller task is captured and
/// drained after its noncooperative gates have been released, including teardown.
@MainActor
private func integrationWait(
    file: StaticString = #filePath, line: UInt = #line,
    _ condition: @MainActor () async -> Bool
) async throws {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(5))
    repeat {
        if await condition() { return }
        await Task.yield()
    } while clock.now < deadline
    XCTFail("Expected production state, exact task completion or controlled gate did not arrive", file: file, line: line)
    throw URLError(.timedOut)
}

@MainActor
private final class ForegroundIntegrationFixture {
    let state: CameraAppState
    let root: URL
    let store: MediaDeliveryStore
    let replyGate: ForegroundIntegrationReplyGate
    let ticker: ForegroundIntegrationTicker
    private let clock: ForegroundIntegrationClock
    private let peers: [ForegroundIntegrationPeer]
    private var activePeer = -1
    private var tasks: [Task<Void, Never>] = []
    var peer: ForegroundIntegrationPeer { peers[activePeer] }

    init(initialNames: [String], limits: MediaDeliveryLimits, replyStage: MediaDeliveryReplyStage?,
         peerCount: Int, liveView: Bool, faultInjector: @escaping MediaDeliveryStore.FaultInjector) {
        root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent("ForegroundIntegration-\(UUID().uuidString)", isDirectory: true)
        store = MediaDeliveryStore(root: root, limits: limits, faultInjector: faultInjector)
        replyGate = ForegroundIntegrationReplyGate(stage: replyStage)
        peers = (0..<peerCount).map { _ in ForegroundIntegrationPeer(names: initialNames, liveView: liveView) }
        var remaining = peers
        let clock = ForegroundIntegrationClock()
        self.clock = clock
        let ticker = ForegroundIntegrationTicker()
        self.ticker = ticker
        let gate = replyGate
        let controller = ForegroundMediaController(now: { clock.now }, sleep: { _ in try await ticker.sleep() })
        let defaults = UserDefaults(suiteName: "ForegroundIntegrationTests.\(UUID().uuidString)")!
        state = CameraAppState(defaults: defaults, sessionFactory: {
            .ccapi(try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: remaining.removeFirst()))
        }, mediaDeliveryStore: store, mediaDeliveryReplyDelay: { await gate.enter($0) }, foregroundMedia: controller)
        state.autoRefresh = false
    }

    func connectNextPeer(holdLatest: Bool = false) async throws {
        activePeer += 1
        if holdLatest { await peer.holdNextOrdinaryListing("connect-latest") }
        await state.connect()
        XCTAssertTrue(state.connected)
        XCTAssertTrue(state.supports(.mediaBrowser))
        XCTAssertTrue(state.supports(.mediaDownload))
        if let latest = state.latestMediaTask { track(latest) }
        if holdLatest {
            try await integrationWait { await self.peer.waiting("connect-latest") }
            return
        }
        if let latest = state.latestMediaTask { try await drain(latest) }
        state.startMediaLibraryLoad()
        try await integrationWait { self.state.mediaLibraryLoadStatus == .complete }
        XCTAssertFalse(state.isBusy(.media))
        XCTAssertTrue(state.canStartForegroundMedia)
    }

    func track(_ task: Task<Void, Never>) { tasks.append(task) }

    func startAndEstablishBaseline() async throws -> Task<Void, Never> {
        XCTAssertTrue(state.startForegroundMedia())
        let task = try XCTUnwrap(state.foregroundMedia.task)
        track(task)
        try await waitForSleep()
        XCTAssertEqual(state.foregroundMedia.state, .baseline)
        try await tickAndWait(seconds: 1)
        XCTAssertEqual(state.foregroundMedia.state, .watching)
        XCTAssertEqual(state.foregroundMedia.pendingCount, 0)
        return task
    }

    func discover(_ name: String) async throws {
        await peer.append(name)
        try await tickAndWait(seconds: 5)
        XCTAssertEqual(state.foregroundMedia.state, .stabilizing)
        XCTAssertEqual(state.foregroundMedia.currentName, name)
        XCTAssertEqual(state.foregroundMedia.pendingCount, 1)
    }

    func waitForSleep() async throws { try await integrationWait { await self.ticker.waiting } }

    func tick(seconds: TimeInterval) async throws {
        try await waitForSleep()
        clock.now += seconds
        await ticker.release()
    }

    func tickAndWait(seconds: TimeInterval) async throws {
        let count = await ticker.sleepCount
        try await tick(seconds: seconds)
        try await integrationWait { await self.ticker.isWaiting(after: count) }
    }

    func stopAndDrain(_ task: Task<Void, Never>) async throws {
        state.stopForegroundMedia()
        await ticker.release()
        try await drain(task)
    }

    func drain(_ task: Task<Void, Never>) async throws {
        let completion = ForegroundIntegrationCompletion()
        let observer = Task { await task.value; completion.finished = true }
        try await integrationWait { completion.finished }
        await observer.value
        await task.value
    }

    func close() async throws {
        state.stopForegroundMedia()
        state.cancelMediaDownload()
        await ticker.finish()
        await replyGate.release()
        for peer in peers { await peer.releaseAll() }
        await state.disconnect()
        for task in tasks { try await drain(task) }
        for peer in peers {
            for file in await peer.returnedTemporaryFiles() {
                XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), "Transport temporary files must be consumed or cleaned")
            }
        }
        if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) }
    }
}

@MainActor private final class ForegroundIntegrationClock { var now: TimeInterval = 0 }
@MainActor private final class ForegroundIntegrationCompletion { var finished = false }

/// A polling checkpoint is intentionally noncooperative until explicitly
/// released. Time advances only in the test, independent of wall-clock speed.
private actor ForegroundIntegrationTicker {
    private var continuation: CheckedContinuation<Void, Never>?
    private var finished = false
    private(set) var sleepCount = 0
    var waiting: Bool { continuation != nil }
    func isWaiting(after count: Int) -> Bool { waiting && sleepCount > count }
    func sleep() async throws {
        guard !finished else { throw CancellationError() }
        sleepCount += 1
        await withCheckedContinuation { continuation = $0 }
        try Task.checkCancellation()
    }
    func release() {
        let pending = continuation
        continuation = nil
        pending?.resume()
    }
    func finish() { finished = true; release() }
}

private actor ForegroundIntegrationReplyGate {
    private let stage: MediaDeliveryReplyStage?
    private var armed = true
    private var continuation: CheckedContinuation<Void, Never>?
    var waiting: Bool { continuation != nil }
    init(stage: MediaDeliveryReplyStage?) { self.stage = stage }
    func enter(_ stage: MediaDeliveryReplyStage) async {
        guard armed, self.stage == stage else { return }
        armed = false
        await withCheckedContinuation { continuation = $0 }
    }
    func release() {
        armed = false
        let pending = continuation
        continuation = nil
        pending?.resume()
    }
}

/// Explicit strict-transport opt-in and real Canon paths are essential here:
/// simulator/Bridge compatibility APIs cannot establish the foreground contract.
/// Held replies ignore cancellation to exercise production post-await ownership.
private actor ForegroundIntegrationPeer: CameraForegroundHTTPTransport {
    static let root = "/ccapi/ver140/contents"
    static let original = Data([0xFF, 0xD8, 0xFF, 0xD9])
    static func path(_ name: String) -> String { "\(root)/card1/100CANON/\(name)" }
    struct Request: Equatable, Sendable {
        let method: String
        let path: String
        let foreground: Bool
    }
    private var names: [String]
    private let liveView: Bool
    private var recorded: [Request] = []
    private var originals: [Request] = []
    private var nextOriginalGate: String?
    private var nextListingGate: String?
    private var nextLiveViewStopGate: String?
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var temporaryFiles: [URL] = []
    init(names: [String], liveView: Bool) { self.names = names; self.liveView = liveView }
    func append(_ name: String) { names.append(name) }
    func requests() -> [Request] { recorded }
    func originalRequests() -> [Request] { originals }
    func freshRequests() -> [Request] { recorded.filter { $0.foreground && $0.path.hasSuffix("?kind=info") } }
    func returnedTemporaryFiles() -> [URL] { temporaryFiles }
    func holdNextOriginal(_ gate: String) { nextOriginalGate = gate }
    func holdNextOrdinaryListing(_ gate: String) { nextListingGate = gate }
    func holdNextLiveViewStop(_ gate: String) { nextLiveViewStopGate = gate }
    func waiting(_ gate: String) -> Bool { gates[gate] != nil }
    func release(_ gate: String) { gates.removeValue(forKey: gate)?.resume() }
    func releaseAll() {
        nextOriginalGate = nil
        nextListingGate = nil
        nextLiveViewStopGate = nil
        let pending = gates
        gates.removeAll()
        for continuation in pending.values { continuation.resume() }
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let response = try respond(request, foreground: false)
        if request.url?.path == Self.root, request.url?.query == "kind=number", let gate = nextListingGate {
            nextListingGate = nil
            await withCheckedContinuation { gates[gate] = $0 }
        }
        if request.httpMethod == "DELETE", request.url?.path.hasSuffix("/shooting/liveview") == true,
           let gate = nextLiveViewStopGate {
            nextLiveViewStopGate = nil
            await withCheckedContinuation { gates[gate] = $0 }
        }
        return response
    }

    func sendForeground(_ request: URLRequest, maximumResponseBytes: Int) async throws -> CameraHTTPResponse {
        let response = try respond(request, foreground: true)
        guard response.body.count <= maximumResponseBytes else { throw URLError(.dataLengthExceedsMaximum) }
        return response
    }

    private func respond(_ request: URLRequest, foreground: Bool) throws -> CameraHTTPResponse {
        record(request, foreground: foreground)
        let url = request.url!
        let kind = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "kind" }?.value
        var object: [String: Any]
        if url.path == "/ccapi" {
            var discovery: [[String: Any]] = [
                ["path": "/contents", "get": true, "put": true],
                ["path": "/deviceinformation", "get": true],
                ["path": "/shooting/control/shutterbutton", "post": true]
            ]
            if liveView {
                discovery.append(["path": "/shooting/liveview", "post": true, "delete": true])
                discovery.append(["path": "/shooting/liveview/flip", "get": true])
            }
            object = ["ver140": discovery]
        } else if url.path.hasSuffix("/deviceinformation") {
            object = ["productname": "Synthetic foreground fixture", "serialnumber": "SYNTHETIC-ONLY", "version": "fixture"]
        } else if url.path == Self.root {
            object = ["path": names.map(Self.path)]
        } else if url.path.hasPrefix(Self.root + "/"), kind == "info" {
            object = ["filesize": Self.original.count, "contenttype": "image/jpeg", "rating": "off"]
        } else if url.path.hasPrefix(Self.root + "/"), kind == "thumbnail" || kind == "display" {
            return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], body: Self.original)
        } else if url.path.hasSuffix("/shooting/control/shutterbutton") {
            object = [:]
        } else if liveView, url.path.hasSuffix("/shooting/liveview") {
            object = [:]
        } else {
            return CameraHTTPResponse(statusCode: 404, body: Data("{}".utf8))
        }
        return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "application/json"],
            body: try JSONSerialization.data(withJSONObject: object))
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        try await download(request, progress: { _ in })
    }

    func download(_ request: URLRequest, progress: @escaping CameraMediaProgressHandler) async throws -> CameraHTTPDownloadResponse {
        try await downloadOriginal(request, foreground: false, progress: progress)
    }

    func downloadForeground(_ request: URLRequest, maximumBytes: Int64,
                            progress: @escaping CameraMediaProgressHandler) async throws -> CameraHTTPDownloadResponse {
        guard maximumBytes == Int64(Self.original.count) else { throw URLError(.dataLengthExceedsMaximum) }
        return try await downloadOriginal(request, foreground: true, progress: progress)
    }

    private func downloadOriginal(_ request: URLRequest, foreground: Bool,
                          progress: @escaping CameraMediaProgressHandler) async throws -> CameraHTTPDownloadResponse {
        let recorded = record(request, foreground: foreground)
        originals.append(recorded)
        guard request.url?.query == nil, request.value(forHTTPHeaderField: "Range") == nil else {
            throw URLError(.unsupportedURL)
        }
        if let gate = nextOriginalGate {
            nextOriginalGate = nil
            await withCheckedContinuation { gates[gate] = $0 }
        }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("ForegroundHTTP-\(UUID().uuidString)")
        try Self.original.write(to: file)
        temporaryFiles.append(file)
        progress(CameraMediaTransferProgress(bytesTransferred: Int64(Self.original.count), totalBytes: Int64(Self.original.count)))
        return CameraHTTPDownloadResponse(statusCode: 200,
            headers: ["content-type": "image/jpeg", "content-length": String(Self.original.count)], temporaryFileURL: file)
    }

    @discardableResult
    private func record(_ request: URLRequest, foreground: Bool) -> Request {
        let url = request.url!
        let path = url.path + (url.query.map { "?" + $0 } ?? "")
        let value = Request(method: request.httpMethod ?? "GET", path: path, foreground: foreground)
        recorded.append(value)
        return value
    }
}
