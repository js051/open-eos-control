import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

@MainActor
final class ShutterReleaseRecoveryTests: XCTestCase {
    func testStopCancelsOwnedStartBeforeItCanEnterEitherClient() async throws {
        for bridge in [false, true] {
            let transport = SuspendedShutterTransport()
            let session: CameraSession = bridge
                ? .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
                : .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: transport))
            let start = Task {
                // Model the interval before the app's owned task enters Core.
                try await Task.sleep(for: .seconds(60))
                return try await session.startBulbExposure()
            }
            try await session.retryShutterRelease(cancelling: start)
            let result = await start.result
            if case .success = result { XCTFail("Stop must cancel a Start which has not been dispatched") }
            let mutations = await transport.mutations()
            XCTAssertTrue(mutations.isEmpty)
        }
    }

    func testLostStartAndFailedCleanupKeepsStopOnlyUntilReleaseAcknowledgement() async throws {
        let transport = SuspendedShutterTransport()
        let state = try makeState(transport)
        await state.connect()
        await transport.enqueue("POST /ccapi/bulb/start", error: .networkConnectionLost)
        await transport.enqueue("POST /ccapi/bulb/stop", error: .timedOut)

        await state.toggleBulbExposure()

        XCTAssertTrue(state.shutterReleaseRequired)
        XCTAssertTrue(state.shutterReleaseUnconfirmed)
        XCTAssertFalse(state.bulbExposureActive)
        XCTAssertTrue(state.canRetryShutterRelease)
        state.clearError()
        await state.refresh()
        XCTAssertTrue(state.shutterReleaseUnconfirmed, "Dismissing an error cannot release a shutter")
        let before = await transport.mutations()
        state.captureMode = .video // Mode drift must never turn recovery into a record command.
        await state.captureStill()
        await state.toggleRecording()
        await state.setSetting(key: "iso", value: "200")
        await state.autofocus()
        await state.syncCameraClock()
        await state.startLiveView()
        let after = await transport.mutations()
        XCTAssertEqual(after, before)

        await transport.enqueue("GET /ccapi/status", error: .networkConnectionLost)
        await state.toggleBulbExposure()

        XCTAssertFalse(state.shutterReleaseRequired)
        XCTAssertFalse(state.shutterReleaseUnconfirmed, "A failed status read cannot undo the release ACK")
        XCTAssertNotNil(state.lastError)
        XCTAssertEqual(state.status?.shutterReleaseUnconfirmed, false)
        XCTAssertFalse(state.shutterFlash, "Unknown recovery does not claim a completed photograph")
        let releases = await transport.count("POST /ccapi/bulb/stop")
        XCTAssertEqual(releases, 2)
        await state.retryShutterRelease()
        let repeatedReleases = await transport.count("POST /ccapi/bulb/stop")
        XCTAssertEqual(repeatedReleases, releases, "A stop-only action cannot start another exposure")
        await state.disconnect()
    }

    func testRefreshSnapshotCapturedBeforeCleanupCannotRelatchAfterReleaseAcknowledgement() async throws {
        let transport = SuspendedShutterTransport()
        let state = try makeState(transport)
        await state.connect()
        await transport.enqueue("POST /ccapi/bulb/start", error: .networkConnectionLost)
        await transport.enqueue("POST /ccapi/bulb/stop", gate: "cleanup")
        let start = Task { await state.toggleBulbExposure() }
        try await waitForGate("cleanup", on: transport)
        await transport.enqueue("GET /ccapi/capabilities", gate: "late-capabilities")
        let refresh = Task { await state.refresh() }
        try await waitForGate("late-capabilities", on: transport)
        await transport.release("cleanup")
        await start.value
        XCTAssertFalse(state.shutterReleaseRequired)
        await transport.release("late-capabilities")
        await refresh.value
        XCTAssertFalse(state.shutterReleaseRequired)
        XCTAssertFalse(state.shutterReleaseUnconfirmed)
        XCTAssertEqual(state.status?.shutterReleaseUnconfirmed, false)
        await state.disconnect()
    }

    func testPendingStartCanBeStoppedWithoutOpeningItsReplyGateAndRepeatedStopsStayBusy() async throws {
        let transport = SuspendedShutterTransport()
        let state = try makeState(transport)
        await state.connect()
        await transport.enqueue("POST /ccapi/bulb/start", gate: "start", cancellable: true)
        await transport.enqueue("POST /ccapi/bulb/stop", gate: "stop")
        let start = Task { await state.toggleBulbExposure() }
        try await waitForGate("start", on: transport)
        XCTAssertTrue(state.canRetryShutterRelease, "A pending Start must leave an explicit Stop available")
        await state.captureStill()
        let starts = await transport.count("POST /ccapi/bulb/start")
        XCTAssertEqual(starts, 1)
        let stop = Task { await state.toggleBulbExposure() }
        try await waitForGate("stop", on: transport)
        XCTAssertTrue(state.busyOperations.contains(.capture))
        XCTAssertFalse(state.canRetryShutterRelease, "Stop owns capture until release settles")
        await state.retryShutterRelease()
        let stops = await transport.count("POST /ccapi/bulb/stop")
        XCTAssertEqual(stops, 1)
        await transport.release("stop")
        await stop.value
        await start.value
        let cancellations = await transport.cancelledGateCount
        XCTAssertEqual(cancellations, 1)
        XCTAssertFalse(state.shutterReleaseRequired)
        XCTAssertNil(state.lastError, "Deliberate cancellation must not restore Start's error")
        await state.disconnect()
    }

    func testPendingStartStopFailureRemainsUnknownUntilAnotherExplicitStop() async throws {
        let transport = SuspendedShutterTransport()
        let state = try makeState(transport)
        await state.connect()
        await transport.enqueue("POST /ccapi/bulb/start", gate: "start", cancellable: true)
        await transport.enqueue("POST /ccapi/bulb/stop", error: .timedOut)
        let start = Task { await state.toggleBulbExposure() }
        try await waitForGate("start", on: transport)
        let stop = Task { await state.retryShutterRelease() }
        try await waitForRequest("POST /ccapi/bulb/stop", on: transport)
        await stop.value
        await start.value
        XCTAssertTrue(state.shutterReleaseUnconfirmed)
        XCTAssertTrue(state.canRetryShutterRelease)
        XCTAssertFalse(state.shutterFlash)
        let firstStops = await transport.count("POST /ccapi/bulb/stop")
        XCTAssertEqual(firstStops, 1, "One Stop must not hide a failed compensation with an automatic retry")
        await state.retryShutterRelease()
        XCTAssertFalse(state.shutterReleaseRequired)
        let starts = await transport.count("POST /ccapi/bulb/start")
        let stops = await transport.count("POST /ccapi/bulb/stop")
        XCTAssertEqual(starts, 1)
        XCTAssertEqual(stops, 2)
        await state.disconnect()
    }

    func testBridgePendingStartStopCancelsOnlyStartAndKeepsFailureAvailableForRetry() async throws {
        let transport = BridgeShutterRecoveryFixture(unknown: false, pendingStart: true, failFirstStop: true)
        let state = CameraAppState(sessionFactory: {
            .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
        })
        state.autoRefresh = false
        await state.connect()
        let start = Task { await state.toggleBulbExposure() }
        try await waitForBridgeMutation("POST /v1/session/shutter-fixture-1/bulb/start", on: transport)
        XCTAssertTrue(state.canRetryShutterRelease)
        let stop = Task { await state.retryShutterRelease() }
        try await waitForBridgeMutation("POST /v1/session/shutter-fixture-1/bulb/stop", on: transport)
        await stop.value
        await start.value
        XCTAssertTrue(state.shutterReleaseUnconfirmed)
        XCTAssertTrue(state.canRetryShutterRelease)
        XCTAssertFalse(state.shutterFlash)
        let cancellations = await transport.cancelledStartCount
        XCTAssertEqual(cancellations, 1)
        await state.retryShutterRelease()
        XCTAssertFalse(state.shutterReleaseRequired)
        XCTAssertNil(state.lastError)
        let commands = await transport.mutations()
        XCTAssertEqual(commands, [
            "POST /v1/session", "POST /v1/session/shutter-fixture-1/bulb/start",
            "POST /v1/session/shutter-fixture-1/bulb/stop", "POST /v1/session/shutter-fixture-1/bulb/stop",
        ])
        await state.disconnect()
    }

    func testBridgePendingStartDisconnectSettlesBeforeReplacementAndPreservesOnlyOldWarning() async throws {
        for failClose in [false, true] {
            let transport = BridgeShutterRecoveryFixture(unknown: false, failFirstClose: failClose, pendingStart: true)
            let state = CameraAppState(sessionFactory: {
                .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
            })
            state.autoRefresh = false
            await state.connect()
            let start = Task { await state.toggleBulbExposure() }
            try await waitForBridgeMutation("POST /v1/session/shutter-fixture-1/bulb/start", on: transport)
            state.requestDisconnect()
            let reconnect = Task { await state.connect() }
            try await waitForBridgeMutation("DELETE /v1/session/shutter-fixture-1", on: transport)
            await reconnect.value
            await start.value
            XCTAssertEqual(state.info?.model, "Shutter recovery fixture shutter-fixture-2")
            XCTAssertFalse(state.shutterReleaseRequired)
            XCTAssertFalse(state.busyOperations.contains(.capture))
            XCTAssertEqual(state.previousShutterReleaseUnconfirmed, failClose)
            let cancellations = await transport.cancelledStartCount
            XCTAssertEqual(cancellations, 1)
            let commands = await transport.mutations()
            XCTAssertEqual(commands, [
                "POST /v1/session", "POST /v1/session/shutter-fixture-1/bulb/start",
                "DELETE /v1/session/shutter-fixture-1", "POST /v1/session",
            ])
            await state.disconnect()
        }
    }

    func testStopWaitsForCancellationIgnoringStartAndLateAcknowledgementCannotRestoreExposure() async throws {
        let transport = SuspendedShutterTransport()
        let state = try makeState(transport)
        await state.connect()
        await transport.enqueue("POST /ccapi/bulb/start", gate: "late-start")
        await transport.enqueue("POST /ccapi/bulb/stop", gate: "release")
        let start = Task { await state.toggleBulbExposure() }
        try await waitForGate("late-start", on: transport)
        let stop = Task { await state.retryShutterRelease() }
        for _ in 0..<2_000 {
            if !state.canRetryShutterRelease { break }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTAssertFalse(state.canRetryShutterRelease)
        let before = await transport.mutations()
        XCTAssertEqual(before, ["POST /ccapi/bulb/start"], "Cleanup cannot race a request which ignored cancellation")
        await transport.release("late-start")
        try await waitForGate("release", on: transport)
        XCTAssertFalse(state.bulbExposureActive)
        XCTAssertTrue(state.busyOperations.contains(.capture))
        await transport.release("release")
        await stop.value
        await start.value
        XCTAssertFalse(state.shutterReleaseRequired)
        XCTAssertFalse(state.busyOperations.contains(.capture))
        XCTAssertNil(state.lastError)
        await state.disconnect()
    }

    func testCancelledConnectingSessionIsClosedAndCanBeReplaced() async throws {
        let old = SuspendedShutterTransport(model: "Old fixture camera")
        let next = SuspendedShutterTransport(model: "New fixture camera")
        let state = try makeState(old, next: next)
        await old.enqueue("GET /ccapi/info", gate: "opening")
        let opening = Task { await state.connect() }
        try await waitForGate("opening", on: old)
        opening.cancel()
        await old.release("opening")
        await opening.value
        XCTAssertFalse(state.connected)
        XCTAssertFalse(state.busyOperations.contains(.connect))
        await state.connect()
        XCTAssertEqual(state.info?.model, "New fixture camera")
        await state.disconnect()
    }

    func testKnownActiveStopFailureCanRetryAfterModeAndTemperatureDrift() async throws {
        let transport = SuspendedShutterTransport()
        let state = try makeState(transport)
        await state.connect()
        await state.toggleBulbExposure()
        XCTAssertTrue(state.bulbExposureActive)
        XCTAssertFalse(state.shutterReleaseUnconfirmed)
        await transport.enqueue("POST /ccapi/bulb/stop", error: .networkConnectionLost)
        await state.retryShutterRelease()
        XCTAssertTrue(state.shutterReleaseRequired)
        XCTAssertTrue(state.shutterReleaseUnconfirmed)
        XCTAssertFalse(state.shutterFlash)
        await transport.enqueue(
            "GET /ccapi/status",
            json: #"{"connected":true,"mode":"Manual","bulb_exposure_active":true,"temperature":"disablerelease","battery":{},"media":{},"exposure":{}}"#
        )
        await state.refresh()
        state.captureMode = .video
        XCTAssertFalse(state.bulbMode)
        XCTAssertFalse(state.stillCaptureTemperatureAllowed)
        XCTAssertTrue(state.canRetryShutterRelease)
        await state.retryShutterRelease()
        XCTAssertFalse(state.shutterReleaseRequired)
        let starts = await transport.count("POST /ccapi/bulb/start")
        let stops = await transport.count("POST /ccapi/bulb/stop")
        XCTAssertEqual(starts, 1)
        XCTAssertEqual(stops, 2)
        await state.disconnect()
    }

    func testCameraStatusCopyPreservesReleaseUncertainty() {
        let status = CameraStatus(bulbExposureActive: nil, shutterReleaseUnconfirmed: true)
        XCTAssertEqual(status.replacing(exposure: ExposureState(iso: "200")).shutterReleaseUnconfirmed, true)
        XCTAssertEqual(status.replacing(recording: false).shutterReleaseUnconfirmed, true)
    }

    func testInitialExplicitSimulatorActiveStateStopsWithoutSendingStart() async throws {
        let transport = SuspendedShutterTransport(initiallyActive: true)
        let state = try makeState(transport)
        await state.connect()
        XCTAssertTrue(state.canRetryShutterRelease)
        await state.retryShutterRelease()
        XCTAssertFalse(state.shutterReleaseRequired)
        let starts = await transport.count("POST /ccapi/bulb/start")
        let stops = await transport.count("POST /ccapi/bulb/stop")
        XCTAssertEqual(starts, 0)
        XCTAssertEqual(stops, 1)
        await state.disconnect()
    }

    func testLostStartWithSuccessfulCleanupDoesNotLeaveUnknownState() async throws {
        let transport = SuspendedShutterTransport()
        let state = try makeState(transport)
        await state.connect()
        await transport.enqueue("POST /ccapi/bulb/start", error: .networkConnectionLost)
        await state.toggleBulbExposure()
        XCTAssertFalse(state.shutterReleaseRequired)
        XCTAssertFalse(state.shutterReleaseUnconfirmed)
        let stopCount = await transport.count("POST /ccapi/bulb/stop")
        XCTAssertEqual(stopCount, 1)
        await state.disconnect()
    }

    func testDebugShutterFixtureCannotProducePhysicalCameraValidationEvidence() async throws {
        for unknown in [false, true] {
            let suite = "ShutterFixtureValidationTests.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            let transport = BridgeShutterRecoveryFixture(unknown: unknown)
            let state = CameraAppState(defaults: defaults, sessionFactory: {
                .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
            })
            state.autoRefresh = false
            await state.connect()

            XCTAssertTrue(state.connected)
            XCTAssertEqual(state.info?.api, "simulated-shutter-recovery")
            XCTAssertEqual(state.physicalValidation.sessionStatus, .simulator)
            XCTAssertTrue(state.physicalValidation.eligibleFeatures.isEmpty)
            state.setOperatorConfirmation(.bulbExposure, confirmed: true)
            XCTAssertTrue(state.operatorConfirmedFeatures.isEmpty)
            do {
                _ = try await state.physicalValidationRecord()
                XCTFail("Synthetic UI transport must never export physical-camera evidence")
            } catch PhysicalValidationRecord.ValidationError.physicalCameraRequired {
                // The ordinary physical-record guard must reject the fixture.
            } catch {
                XCTFail("Expected physicalCameraRequired, not an unrelated export error: \(error)")
            }
            await state.disconnect()
        }
    }

    func testBridgeReportedActiveOrUnknownCanStopWithoutBulbModeCapabilityOrTemperaturePermission() async throws {
        for unknown in [false, true] {
            let transport = BridgeShutterRecoveryFixture(unknown: unknown)
            let state = CameraAppState(sessionFactory: {
                .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
            })
            state.autoRefresh = false
            await state.connect()
            XCTAssertFalse(state.isPreview)
            XCTAssertFalse(state.bulbMode)
            XCTAssertFalse(state.supports(.bulbExposure))
            XCTAssertFalse(state.stillCaptureTemperatureAllowed)
            XCTAssertTrue(state.canRetryShutterRelease)
            await state.retryShutterRelease()
            XCTAssertFalse(state.shutterReleaseRequired)
            let stopCount = await transport.stopCount
            XCTAssertEqual(stopCount, 1)
            await state.disconnect()
        }
    }

    func testPreviousBridgeWarningAcknowledgementSendsNothingAndStopTargetsOnlyNewSession() async throws {
        let transport = BridgeShutterRecoveryFixture(unknown: false, failFirstClose: true)
        let state = CameraAppState(sessionFactory: {
            .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
        })
        state.autoRefresh = false
        await state.connect()
        await state.disconnect()
        XCTAssertTrue(state.previousShutterReleaseUnconfirmed)
        await state.connect()
        XCTAssertTrue(state.canRetryShutterRelease)
        XCTAssertTrue(state.previousShutterReleaseUnconfirmed)
        let before = await transport.mutations()
        state.confirmPreviousShutterReleased()
        let after = await transport.mutations()
        XCTAssertEqual(before, after)
        XCTAssertTrue(state.canRetryShutterRelease)
        await state.retryShutterRelease()
        let commands = await transport.mutations()
        XCTAssertEqual(commands.filter { $0.hasSuffix("/bulb/stop") }, ["POST /v1/session/shutter-fixture-2/bulb/stop"])
        XCTAssertFalse(state.shutterReleaseRequired)
        await state.disconnect()
    }

    func testUnknownReleaseCannotResumeLiveViewAndBackgroundStopDoesNotResumeIt() async throws {
        let transport = SuspendedShutterTransport()
        let state = try makeState(transport, liveView: true)
        await state.connect()
        try await waitForRequest("GET /ccapi/liveview/frame", on: transport)
        await transport.enqueue("POST /ccapi/bulb/start", error: .networkConnectionLost)
        await transport.enqueue("POST /ccapi/bulb/stop", error: .timedOut)
        await state.toggleBulbExposure()
        let frames = await transport.count("GET /ccapi/liveview/frame")
        await state.setAutoRefresh(false)
        await state.setAutoRefresh(true)
        state.setApplicationActive(false)
        state.setApplicationActive(true)
        try await Task.sleep(nanoseconds: 200_000_000)
        let blockedFrames = await transport.count("GET /ccapi/liveview/frame")
        XCTAssertEqual(blockedFrames, frames)
        XCTAssertTrue(state.shutterReleaseRequired)
        let stopsBeforeBackground = await transport.count("POST /ccapi/bulb/stop")
        state.setApplicationActive(false)
        let stopsAfterBackground = await transport.count("POST /ccapi/bulb/stop")
        XCTAssertEqual(stopsAfterBackground, stopsBeforeBackground, "Backgrounding must preserve exposure")
        await state.retryShutterRelease()
        try await Task.sleep(nanoseconds: 200_000_000)
        let backgroundFrames = await transport.count("GET /ccapi/liveview/frame")
        XCTAssertEqual(backgroundFrames, frames)
        state.setApplicationActive(true)
        try await waitForRequest("GET /ccapi/liveview/frame", count: frames + 1, on: transport)
        await state.disconnect()
    }

    func testDisconnectSerializesCleanupBeforeReconnectAndKeepsPreviousWarningSeparate() async throws {
        let old = SuspendedShutterTransport(model: "Old fixture camera")
        let next = SuspendedShutterTransport(model: "New fixture camera")
        var creations = 0
        let state = try makeState(old, next: next, onCreate: { creations += 1 })
        await state.connect()
        await state.toggleBulbExposure()
        await old.enqueue("POST /ccapi/bulb/stop", error: .timedOut, gate: "close")
        state.requestDisconnect()
        try await waitForGate("close", on: old)
        let reconnect = Task { await state.connect() }
        await Task.yield()
        XCTAssertEqual(creations, 1, "A new connection must wait for old cleanup")
        XCTAssertTrue(state.previousShutterReleaseUnconfirmed)
        await old.release("close")
        await reconnect.value
        XCTAssertEqual(state.info?.model, "New fixture camera")
        XCTAssertFalse(state.shutterReleaseRequired)
        XCTAssertTrue(state.previousShutterReleaseUnconfirmed)
        let oldCommands = await old.mutations()
        let newCommands = await next.mutations()
        state.confirmPreviousShutterReleased()
        XCTAssertFalse(state.previousShutterReleaseUnconfirmed)
        let oldAfterConfirmation = await old.mutations()
        let newAfterConfirmation = await next.mutations()
        XCTAssertEqual(oldAfterConfirmation, oldCommands)
        XCTAssertEqual(newAfterConfirmation, newCommands)
        await state.disconnect()
    }

    func testOldStopStatusAndDeferCannotOverwriteOrUnlockNewBulbStart() async throws {
        let old = SuspendedShutterTransport(model: "Old fixture camera")
        let next = SuspendedShutterTransport(model: "New fixture camera")
        let state = try makeState(old, next: next)
        await state.connect()
        await state.toggleBulbExposure()
        await old.enqueue("GET /ccapi/status", gate: "old-stop-status")
        let oldStop = Task { await state.retryShutterRelease() }
        try await waitForGate("old-stop-status", on: old)
        await state.disconnect()
        await state.connect()
        await next.enqueue("POST /ccapi/bulb/start", gate: "new-start")
        let newStart = Task { await state.toggleBulbExposure() }
        try await waitForGate("new-start", on: next)
        await old.release("old-stop-status")
        await oldStop.value
        XCTAssertEqual(state.info?.model, "New fixture camera")
        XCTAssertTrue(state.busyOperations.contains(.capture), "An old defer must not unlock a new operation")
        XCTAssertTrue(state.shutterReleaseUnconfirmed)
        XCTAssertTrue(state.canRetryShutterRelease, "The replacement's pending Start remains explicitly stoppable")
        await next.release("new-start")
        await newStart.value
        XCTAssertTrue(state.bulbExposureActive)
        await state.disconnect()
    }

    func testOldRefreshSnapshotAndDeferCannotReplaceOrUnlockNewRefresh() async throws {
        let old = SuspendedShutterTransport(model: "Old fixture camera")
        let next = SuspendedShutterTransport(model: "New fixture camera")
        let state = try makeState(old, next: next)
        await state.connect()
        await old.enqueue("GET /ccapi/status", gate: "old-refresh")
        let oldRefresh = Task { await state.refresh() }
        try await waitForGate("old-refresh", on: old)
        await state.disconnect()
        await state.connect()
        await next.enqueue("GET /ccapi/status", gate: "new-refresh")
        let newRefresh = Task { await state.refresh() }
        try await waitForGate("new-refresh", on: next)
        await old.release("old-refresh")
        await oldRefresh.value
        XCTAssertEqual(state.info?.model, "New fixture camera")
        XCTAssertTrue(state.busyOperations.contains(.refresh))
        await next.release("new-refresh")
        await newRefresh.value
        await state.disconnect()
    }

    func testOldEventSnapshotCannotReplaceReconnectedCamera() async throws {
        let old = SuspendedShutterTransport(model: "Old fixture camera")
        let next = SuspendedShutterTransport(model: "New fixture camera")
        await old.enqueue("GET /ccapi/events", json: #"{"sequence":1,"keys":["mode"]}"#, gate: "event")
        let state = try makeState(old, next: next)
        await state.connect()
        try await waitForGate("event", on: old)
        await old.enqueue("GET /ccapi/status", gate: "event-snapshot")
        await old.release("event")
        try await waitForGate("event-snapshot", on: old)
        await state.disconnect()
        await state.connect()
        await old.release("event-snapshot")
        // Drain the old continuation without relying on the transport honoring cancellation.
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(state.info?.model, "New fixture camera")
        XCTAssertFalse(state.shutterReleaseRequired)
        await state.disconnect()
    }

    func testDisconnectDuringLostStartCannotRestoreOldBulbStateAfterReconnect() async throws {
        let old = SuspendedShutterTransport(model: "Old fixture camera")
        let next = SuspendedShutterTransport(model: "New fixture camera")
        let state = try makeState(old, next: next)
        await state.connect()
        await old.enqueue("POST /ccapi/bulb/start", gate: "old-start", cancellable: true)
        await old.enqueue("POST /ccapi/bulb/stop", error: .timedOut)
        await old.enqueue("POST /ccapi/bulb/stop", error: .timedOut)
        let oldStart = Task { await state.toggleBulbExposure() }
        try await waitForGate("old-start", on: old)
        state.requestDisconnect()
        let reconnect = Task { await state.connect() }
        // A bounded observable wait fails before awaiting task values if cancellation
        // regresses. No test code opens the old Start response barrier.
        try await waitForRequest("GET /ccapi/info", on: next)
        await reconnect.value
        await oldStart.value
        let cancellations = await old.cancelledGateCount
        XCTAssertEqual(cancellations, 1)
        XCTAssertEqual(state.info?.model, "New fixture camera")
        XCTAssertFalse(state.shutterReleaseRequired)
        XCTAssertTrue(state.previousShutterReleaseUnconfirmed)
        XCTAssertFalse(state.busyOperations.contains(.capture))
        await state.disconnect()
    }

    private func makeState(
        _ first: SuspendedShutterTransport,
        next: SuspendedShutterTransport? = nil,
        liveView: Bool = false,
        onCreate: @escaping @MainActor () -> Void = {}
    ) throws -> CameraAppState {
        var creations = 0
        let defaults = UserDefaults(suiteName: "ShutterReleaseRecoveryTests.\(UUID().uuidString)")!
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            onCreate()
            let transport = creations == 0 ? first : next ?? first
            creations += 1
            return .ccapi(try CCAPIClient(
                baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: transport
            ))
        })
        state.autoRefresh = liveView
        return state
    }

    private func waitForGate(_ gate: String, on transport: SuspendedShutterTransport) async throws {
        for _ in 0..<2_000 {
            if await transport.isWaiting(gate) { return }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("The transport did not reach gate \(gate)")
        throw URLError(.timedOut)
    }

    private func waitForBridgeMutation(_ key: String, on transport: BridgeShutterRecoveryFixture) async throws {
        for _ in 0..<2_000 {
            if await transport.mutations().contains(key) { return }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("The Bridge fixture did not receive \(key)")
        throw URLError(.timedOut)
    }

    private func waitForRequest(
        _ key: String, count: Int = 1, on transport: SuspendedShutterTransport
    ) async throws {
        for _ in 0..<2_000 {
            if await transport.count(key) >= count { return }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("The transport did not receive \(key)")
        throw URLError(.timedOut)
    }
}

// Independent camera model: a lost Start response still opens the shutter. Failed
// release leaves it open. Gates deliberately ignore Task cancellation to expose races.
private actor SuspendedShutterTransport: CameraHTTPTransport {
    private struct Plan {
        let json: String?
        let error: URLError.Code?
        let gate: String?
        let cancellable: Bool
    }
    private let model: String
    private var physicalShutterOpen = false
    private var plans: [String: [Plan]] = [:]
    private var requests: [String] = []
    private var gates: [String: CheckedContinuation<Void, Error>] = [:]
    private(set) var cancelledGateCount = 0

    init(model: String = "Shutter fixture camera", initiallyActive: Bool = false) {
        self.model = model
        physicalShutterOpen = initiallyActive
    }

    func enqueue(
        _ key: String, json: String? = nil, error: URLError.Code? = nil,
        gate: String? = nil, cancellable: Bool = false
    ) {
        plans[key, default: []].append(Plan(json: json, error: error, gate: gate, cancellable: cancellable))
    }

    func count(_ key: String) -> Int { requests.filter { $0 == key }.count }
    func mutations() -> [String] { requests.filter { !$0.hasPrefix("GET ") } }
    func isWaiting(_ gate: String) -> Bool { gates[gate] != nil }
    func release(_ gate: String) { gates.removeValue(forKey: gate)?.resume() }
    private func cancel(_ gate: String) {
        guard let continuation = gates.removeValue(forKey: gate) else { return }
        cancelledGateCount += 1
        continuation.resume(throwing: CancellationError())
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        let key = "\(method) \(path)"
        requests.append(key)
        let plan = plans[key]?.isEmpty == false ? plans[key]?.removeFirst() : nil
        if path == "/ccapi/bulb/start" { physicalShutterOpen = true }
        if path == "/ccapi/bulb/stop", plan?.error == nil { physicalShutterOpen = false }
        let reply = plan?.json ?? defaultJSON(path)
        if let gate = plan?.gate {
            if plan?.cancellable == true {
                try await withTaskCancellationHandler {
                    try Task.checkCancellation()
                    try await withCheckedThrowingContinuation { gates[gate] = $0 }
                } onCancel: {
                    Task { await self.cancel(gate) }
                }
            } else {
                try await withCheckedThrowingContinuation { gates[gate] = $0 }
            }
        } else if path == "/ccapi/events", plan == nil {
            try await Task.sleep(nanoseconds: 60_000_000_000)
        }
        if let error = plan?.error { throw URLError(error) }
        return CameraHTTPResponse(statusCode: 200, headers: ["Content-Type": path.hasSuffix("/frame") ? "image/jpeg" : "application/json"], body: Data(reply.utf8))
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        throw URLError(.unsupportedURL)
    }

    private func defaultJSON(_ path: String) -> String {
        switch path {
        case "/ccapi/info":
            return #"{"connected":true,"model":"\#(model)","serial":"SYNTHETIC","api":"fixture"}"#
        case "/ccapi/status":
            return #"{"connected":true,"mode":"Bulb","bulb_exposure_active":\#(physicalShutterOpen),"recording":false,"battery":{},"media":{},"exposure":{}}"#
        case "/ccapi/capabilities": return #"{"iso":["100","200"]}"#
        case "/ccapi/media": return #"{"items":[]}"#
        case "/ccapi/events": return #"{"sequence":0,"keys":[]}"#
        default: return #"{"ok":true}"#
        }
    }
}
