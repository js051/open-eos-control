import Foundation
import OpenEOSCore
import XCTest
import UIKit
@testable import OpenEOSControl

@MainActor
final class LiveViewStopRecoveryTests: XCTestCase {
    func testRestartStopFailureShowsRecoveryAndBothRetryAndRestartRecoverySendOnlyStop() async throws {
        for useRestart in [false, true] {
            let peer = AppLiveViewPeer()
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            XCTAssertTrue(state.connected)
            await state.startLiveView()
            XCTAssertEqual(state.activeLiveViewSource, .desktopBridgeStream)
            await peer.failNextStop()
            await state.restartLiveView()
            XCTAssertTrue(state.liveViewStopState.stopUnconfirmed)
            XCTAssertTrue(state.canRetryLiveViewStop)
            XCTAssertNil(state.activeLiveViewSource)
            let failedActions = await peer.actions()
            XCTAssertEqual(failedActions, ["start", "stop"])
            await state.startLiveView()
            await state.refresh()
            XCTAssertTrue(state.liveViewStopState.stopUnconfirmed, "Cached false status cannot discharge Stop")
            if useRestart { await state.restartLiveView() } else { await state.retryLiveViewStop() }
            XCTAssertEqual(state.liveViewStopState, .idle)
            XCTAssertNil(state.activeLiveViewSource)
            let retriedActions = await peer.actions()
            XCTAssertEqual(retriedActions, ["start", "stop", "stop"], "Recovery cannot start another view")
            await state.startLiveView()
            XCTAssertEqual(state.activeLiveViewSource, .desktopBridgeStream)
        }
    }

    func testFailedStartCompensationKeepsVisibleStopOnlyRecovery() async throws {
        let peer = AppLiveViewPeer()
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await peer.loseNextStart()
        await peer.failNextStop()
        await state.startLiveView()
        XCTAssertNil(state.activeLiveViewSource)
        XCTAssertTrue(state.liveViewStopState.stopRequired)
        XCTAssertTrue(state.liveViewStopState.stopUnconfirmed)
        XCTAssertTrue(state.canRetryLiveViewStop)
        await state.retryLiveViewStop()
        XCTAssertEqual(state.liveViewStopState, .idle)
        let actions = await peer.actions()
        XCTAssertEqual(actions, ["start", "stop", "stop"])
    }

    func testDisconnectRetainsOldWarningAndCannotStopReplacementCamera() async throws {
        let oldPeer = AppLiveViewPeer(owner: "old")
        let newPeer = AppLiveViewPeer(owner: "new")
        let state = makeState([oldPeer, newPeer])
        defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
        await state.connect()
        await state.startLiveView()
        await oldPeer.failNextStop()
        await state.disconnect()
        XCTAssertEqual(state.previousLiveViewWarnings.count, 1)
        XCTAssertEqual(state.liveViewStopState, .idle)
        await state.connect()
        await state.startLiveView()
        XCTAssertEqual(state.activeLiveViewSource, .desktopBridgeStream)
        XCTAssertFalse(state.canRetryLiveViewStop)
        state.confirmPreviousLiveViewStopped()
        await state.retryLiveViewStop()
        let actions = await newPeer.actions()
        XCTAssertEqual(actions, ["start"], "Acknowledging an old-camera warning sends no replacement command")
        XCTAssertTrue(state.previousLiveViewWarnings.isEmpty)
    }

    func testCancelledLateStartIsNeverPublishedAndFailedCleanupRemainsRecoverable() async throws {
        let peer = AppLiveViewPeer()
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await peer.holdNextStart()
        await peer.failNextStop()
        let start = Task { await state.startLiveView() }
        defer { start.cancel() }
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(3))
        while !(await peer.waiting), clock.now < deadline { await Task.yield() }
        let waiting = await peer.waiting
        XCTAssertTrue(waiting)
        start.cancel()
        await peer.releaseAll()
        await start.value
        XCTAssertNil(state.activeLiveViewSource)
        XCTAssertTrue(state.liveViewStopState.stopUnconfirmed)
        XCTAssertTrue(state.canRetryLiveViewStop)
        await state.retryLiveViewStop()
        let actions = await peer.actions()
        XCTAssertEqual(actions, ["start", "stop", "stop"])
    }

    func testMaintenanceStopFailureBlocksCommandAndHealthyCleanNeedsExplicitRestore() async throws {
        for cleaning in [false, true] {
            let peer = AppLiveViewPeer(), state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect(); await state.startLiveView(); await peer.failNextStop()
            if cleaning { await state.cleanSensor(autoPowerOff: false) } else { await state.sleepCamera() }
            XCTAssertTrue(state.liveViewStopState.stopUnconfirmed)
            XCTAssertEqual(state.maintenanceRecord?.outcome.commandEvidence, .notSent)
            XCTAssertEqual(state.maintenanceRecord?.outcome.stopEvidence, .unknown)
            let failed = await peer.actions()
            XCTAssertEqual(failed, ["start", "stop"])
            await state.cleanSensor(autoPowerOff: false)
            let blocked = await peer.actions(); XCTAssertEqual(blocked, failed, "Unknown Stop cannot dispatch cleaning")
            await state.retryLiveViewStop()
            await state.cleanSensor(autoPowerOff: false)
            XCTAssertNil(state.activeLiveViewSource)
            let id = try XCTUnwrap(state.maintenanceRecord?.id)
            await state.restoreMaintenance(id)
            XCTAssertFalse(state.maintenanceControlsPaused)
            XCTAssertNil(state.activeLiveViewSource, "No view was active for the new confirmed cleaning")
        }
        let peer = AppLiveViewPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect(); await state.startLiveView(); await state.cleanSensor(autoPowerOff: false)
        XCTAssertNil(state.activeLiveViewSource)
        let id = try XCTUnwrap(state.maintenanceRecord?.id)
        await state.recoverMaintenance(id)
        let readOnly = await peer.actions(); XCTAssertEqual(readOnly, ["start", "stop", "clean"])
        await state.restoreMaintenance(id)
        XCTAssertEqual(state.activeLiveViewSource, .desktopBridgeStream)
        let restored = await peer.actions(); XCTAssertEqual(restored, ["start", "stop", "clean", "start"])
    }

    func testRestartWaitsForHeldStartAppCompletionBeforeAdmittingReplacement() async throws {
        let peer = AppLiveViewPeer()
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await peer.holdNextStart()
        let start = Task { await state.startLiveView() }
        defer { start.cancel() }
        try await wait { await peer.waiting }
        let restart = Task { await state.restartLiveView() }
        try await wait { await peer.startCancelled }
        await peer.releaseAll()
        await restart.value
        await start.value
        XCTAssertEqual(state.activeLiveViewSource, .desktopBridgeStream)
        XCTAssertFalse(state.isBusy(.liveView))
        let actions = await peer.actions()
        XCTAssertEqual(actions, ["start", "stop", "start"], "Restart must wait for App busy ownership, not only Core settlement")
    }

    func testBackgroundRetiresSourceAndForegroundRequiresNewExplicitStart() async throws {
        let peer = AppLiveViewPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect(); await state.startLiveView()
        state.setApplicationActive(false)
        try await wait { !state.liveViewRetiring }
        XCTAssertNil(state.activeLiveViewSource); XCTAssertNil(state.liveViewData)
        XCTAssertNil(state.lastFrameAt); XCTAssertTrue(state.liveViewNeedsRestart)
        state.setApplicationActive(true)
        let retired = await peer.actions(); XCTAssertEqual(retired, ["start", "stop"])
        XCTAssertTrue(state.canResumeRetiredLiveView)
        await state.startLiveView()
        let restarted = await peer.actions(); XCTAssertEqual(restarted, ["start", "stop", "start"])
    }

    func testBackgroundDuringObservedRecordingRetiresLocallyAndDefersCameraStop() async throws {
        let peer = AppLiveViewPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect(); await state.startLiveView()
        await peer.setRecording(true); await state.refresh()
        XCTAssertTrue(state.recordingStopRequired)
        state.setApplicationActive(false)
        try await wait { !state.liveViewRetiring }
        XCTAssertNil(state.activeLiveViewSource); XCTAssertNil(state.liveViewData)
        XCTAssertTrue(state.liveViewStopState.stopRequired)
        XCTAssertFalse(state.canRetryLiveViewStop)
        let deferred = await peer.actions(); XCTAssertEqual(deferred, ["start"])
        state.setApplicationActive(true)
        await state.startLiveView()
        let noRestart = await peer.actions(); XCTAssertEqual(noRestart, deferred)
        await peer.setRecording(false); await state.refresh()
        XCTAssertFalse(state.recordingStopRequired)
        XCTAssertTrue(state.canRetryLiveViewStop)
        await state.retryLiveViewStop()
        let stopped = await peer.actions(); XCTAssertEqual(stopped, ["start", "stop"])
        XCTAssertNil(state.activeLiveViewSource)
    }

    func testBackgroundDuringPendingStartCannotPublishLateReadyOrRepeatFailedStop() async throws {
        let peer = AppLiveViewPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect(); await peer.holdNextStart(); await peer.failNextStop()
        let start = Task { await state.startLiveView() }
        try await wait { await peer.waiting }
        state.setApplicationActive(false); state.setApplicationActive(true)
        await peer.releaseAll(); await start.value
        try await wait { !state.liveViewRetiring }
        XCTAssertNil(state.activeLiveViewSource); XCTAssertTrue(state.liveViewStopState.stopUnconfirmed)
        let actions = await peer.actions(); XCTAssertEqual(actions, ["start", "stop"])
        await state.retryLiveViewStop()
        let retry = await peer.actions(); XCTAssertEqual(retry, ["start", "stop", "stop"])
        XCTAssertNil(state.activeLiveViewSource)
    }

    func testBackgroundDuringPendingStartWithConfirmedCleanupStillNeedsExplicitNewStart() async throws {
        let peer = AppLiveViewPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect(); await peer.holdNextStart()
        let start = Task { await state.startLiveView() }
        defer { start.cancel() }
        try await wait { await peer.waiting }
        state.setApplicationActive(false); state.setApplicationActive(true)
        await peer.releaseAll(); await start.value
        try await wait { !state.liveViewRetiring }
        XCTAssertNil(state.activeLiveViewSource)
        XCTAssertEqual(state.liveViewStopState, .idle)
        XCTAssertTrue(state.canResumeRetiredLiveView)
        let retired = await peer.actions()
        XCTAssertEqual(retired, ["start", "stop"])
        state.setApplicationActive(false); state.setApplicationActive(true)
        let foregrounded = await peer.actions()
        XCTAssertEqual(foregrounded, retired, "Foreground cannot replay Stop or restore the retired view")
        await state.startLiveView()
        XCTAssertEqual(state.activeLiveViewSource, .desktopBridgeStream)
        let restarted = await peer.actions()
        XCTAssertEqual(restarted, ["start", "stop", "start"])
    }

    func testSettledFailedStartCompensationCannotBeRetriedByDelayedRetirementOrRestart() async throws {
        for restarting in [false, true] {
            let peer = AppLiveViewPeer(), admission = LiveViewStopAdmissionGate()
            let state = makeState([peer], stopAdmissionDelay: { await admission.wait() })
            defer {
                state.requestDisconnect()
                Task { await admission.release(); await peer.releaseAll() }
            }
            await state.connect(); await peer.holdNextStart(); await peer.failNextStop()
            let start = Task { await state.startLiveView() }
            defer { start.cancel() }
            try await wait { await peer.waiting }
            let cleanup = Task {
                if restarting { await state.restartLiveView() }
                else { state.setApplicationActive(false); state.setApplicationActive(true) }
            }
            defer { cleanup.cancel() }
            try await wait { await admission.waiting }
            // Let Core compensation and the App Start finish before the cleanup
            // caller can admit Stop. This fixes the formerly timing-dependent gap.
            await peer.releaseAll(); await start.value
            await state.refresh()
            XCTAssertTrue(state.liveViewStopState.stopRequired)
            XCTAssertTrue(state.liveViewStopState.stopUnconfirmed)
            let compensated = await peer.actions()
            XCTAssertEqual(compensated, ["start", "stop"])
            await admission.release(); await cleanup.value
            try await wait { !state.liveViewRetiring && !state.isBusy(.liveView) }
            XCTAssertNil(state.activeLiveViewSource)
            XCTAssertTrue(state.liveViewStopState.stopRequired)
            XCTAssertTrue(state.liveViewStopState.stopUnconfirmed)
            XCTAssertTrue(state.canRetryLiveViewStop)
            let retired = await peer.actions()
            XCTAssertEqual(retired, compensated, "Cleanup cannot retry the failed Stop from its cancelled Start")
            await state.retryLiveViewStop()
            XCTAssertEqual(state.liveViewStopState, .idle)
            XCTAssertNil(state.activeLiveViewSource)
            let retried = await peer.actions()
            XCTAssertEqual(retried, ["start", "stop", "stop"], "Only the new explicit recovery may retry Stop")
        }
    }

    func testRuntimeLossClearsLastImageStopsOnceAndDoesNotLoopTerminalReader() async throws {
        let peer = AppLiveViewPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        let image = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2)).image { context in
            UIColor.red.setFill(); context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
        }
        await peer.setFrame(try XCTUnwrap(image.jpegData(compressionQuality: 1)))
        await state.connect(); await state.startLiveView(); await state.setAutoRefresh(true)
        try await wait { state.lastFrameAt != nil }
        await peer.failFrames()
        try await wait { state.liveViewNeedsRestart && !state.liveViewRetiring }
        XCTAssertNil(state.activeLiveViewSource); XCTAssertNil(state.liveViewData)
        XCTAssertNil(state.lastFrameAt); XCTAssertEqual(state.frameBytes, 0)
        let actions = await peer.actions(); XCTAssertEqual(actions, ["start", "stop"])
        let reads = await peer.frameReads
        for _ in 0..<20 { await Task.yield() }
        let after = await peer.frameReads; XCTAssertEqual(after, reads)
    }

    func testLateFrameFromRetiredReaderCannotOverwriteReplacementInSameCameraSession() async throws {
        let peer = AppLiveViewPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        func jpeg(_ color: UIColor) throws -> Data {
            let image = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2)).image { context in
                color.setFill(); context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
            }
            return try XCTUnwrap(image.jpegData(compressionQuality: 1))
        }
        let old = try jpeg(.red), replacement = try jpeg(.blue)
        await peer.setFrame(old); await peer.holdNextFrame()
        await state.connect(); await state.startLiveView(); await state.setAutoRefresh(true)
        try await wait { await peer.waiting }
        state.setApplicationActive(false)
        try await wait { !state.liveViewRetiring }
        await peer.setFrame(replacement)
        state.setApplicationActive(true); await state.startLiveView()
        try await wait { state.liveViewData == replacement }
        await peer.releaseAll()
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(state.liveViewData, replacement)
        XCTAssertEqual(state.activeLiveViewSource, .desktopBridgeStream)
        let actions = await peer.actions(); XCTAssertEqual(actions, ["start", "stop", "start"])
    }

    func testCompleteJPEGEnvelopeWithoutDecodedImageIsRetiredWithVisibleStopRecovery() async throws {
        let peer = AppLiveViewPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await peer.setFrame(Data([0xff, 0xd8, 0xff, 0xd9])); await peer.failNextStop()
        await state.connect(); await state.startLiveView(); await state.setAutoRefresh(true)
        try await wait { state.liveViewStopState.stopUnconfirmed }
        XCTAssertNil(state.liveViewData); XCTAssertNil(state.lastFrameAt)
        XCTAssertTrue(state.canRetryLiveViewStop)
        let actions = await peer.actions(); XCTAssertEqual(actions, ["start", "stop"])
    }

    private func wait(_ predicate: () async -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(3))
        repeat { if await predicate() { return }; await Task.yield() } while clock.now < deadline
        XCTFail("Expected controlled request gate")
        throw URLError(.timedOut)
    }

    private func makeState(_ peers: [AppLiveViewPeer],
                           stopAdmissionDelay: (@Sendable () async -> Void)? = nil) -> CameraAppState {
        var remaining = peers
        let suite = "LiveViewStopRecoveryTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            .desktopBridge(try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: remaining.removeFirst()))
        }, liveViewStopAdmissionDelay: stopAdmissionDelay)
        state.autoRefresh = false
        return state
    }
}

private actor LiveViewStopAdmissionGate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var released = false
    var waiting: Bool { continuation != nil }
    func wait() async {
        guard !released else { return }
        await withCheckedContinuation { continuation = $0 }
    }
    func release() {
        released = true
        continuation?.resume(); continuation = nil
    }
}

private actor AppLiveViewPeer: CameraHTTPTransport {
    private let owner: String
    private var stopFailures = 0
    private var loseStart = false
    private var holdStart = false
    private(set) var startCancelled = false
    private func noteStartCancellation() { startCancelled = true }
    private var gate: CheckedContinuation<Void, Never>?
    private var recorded: [String] = []
    private var frameData: Data?
    private var frameFailure = false
    private var observedRecording = false
    func setRecording(_ value: Bool) { observedRecording = value }
    private var holdFrame = false
    func holdNextFrame() { holdFrame = true }
    private(set) var frameReads = 0
    func setFrame(_ value: Data) { frameData = value; frameFailure = false }
    func failFrames() { frameFailure = true }
    var waiting: Bool { gate != nil }
    init(owner: String = "synthetic") { self.owner = owner }
    func failNextStop() { stopFailures += 1 }
    func loseNextStart() { loseStart = true }
    func holdNextStart() { holdStart = true }
    func releaseAll() { gate?.resume(); gate = nil }
    func actions() -> [String] { recorded }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        if path.hasSuffix("/liveview/frame") {
            frameReads += 1
            guard !frameFailure, let frameData else { throw URLError(.networkConnectionLost) }
            if holdFrame { holdFrame = false; await withCheckedContinuation { gate = $0 } }
            return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], body: frameData)
        }
        var body: [String: Any]
        var status = 200
        if path == "/health" { body = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"] }
        else if path == "/v1/session" { body = ["id": owner, "engine": "ccapi"] }
        else if path == "/v1/session/\(owner)", method == "DELETE" { return CameraHTTPResponse(statusCode: 204) }
        else if path.hasSuffix("/info") { body = ["connected": true, "model": "Synthetic Live View camera", "serial": "SYNTHETIC", "api": "fixture"] }
        else if path.hasSuffix("/status") { body = ["connected": true, "recording": observedRecording, "liveViewStopUnconfirmed": false] }
        else if path.hasSuffix("/capabilities") {
            body = ["supported": [CameraFeature.liveView.rawValue, CameraFeature.cameraSleep.rawValue, CameraFeature.sensorCleaning.rawValue], "settings": [],
                    "liveView": ["sources": ["DESKTOP_BRIDGE_STREAM"], "sizes": ["MEDIUM"], "minFps": 1, "maxFps": 30]]
        } else if path == "/v1/session/\(owner)/liveview/start" || path == "/v1/session/\(owner)/maintenance/control/liveview/start" {
            recorded.append("start")
            if holdStart {
                holdStart = false
                await withTaskCancellationHandler {
                    await withCheckedContinuation { gate = $0 }
                } onCancel: { Task { await self.noteStartCancellation() } }
            }
            if loseStart { loseStart = false; throw URLError(.networkConnectionLost) }
            body = ["active": true, "source": "CCAPI_JPEG_POLLING", "size": "MEDIUM", "requestedFps": 6]
        } else if path == "/v1/session/\(owner)/liveview/stop" || path == "/v1/session/\(owner)/maintenance/control/liveview/stop" {
            recorded.append("stop")
            if stopFailures > 0 {
                stopFailures -= 1; status = 502
                body = ["code": "LIVE_VIEW_STOP_UNCONFIRMED", "message": "Synthetic stop response lost"]
            } else { body = ["active": false, "liveViewStopUnconfirmed": false] }
        } else if path == "/v1/session/\(owner)/maintenance/sensor-cleaning", method == "GET" {
            body = ["version": 1, "engine": "ccapi", "operation": "sensor-cleaning", "observationEvidence": "reachable", "clockObservation": NSNull()]
        } else if path.hasSuffix("/maintenance/sensor-cleaning/outcome") || path.hasSuffix("/maintenance/sleep/outcome") {
            let cleaning = path.contains("sensor-cleaning")
            recorded.append(cleaning ? "clean" : "sleep")
            let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
            body = ["version": 1, "engine": "ccapi", "operation": cleaning ? "sensor-cleaning" : "sleep",
                "autoPowerOff": payload["autoPowerOff"]!, "responseEvidence": "response-received", "clockReadEvidence": "not-applicable",
                "clockReference": NSNull(), "clockObservation": NSNull()]
        } else { throw URLError(.unsupportedURL) }
        if path.contains("/maintenance/control/liveview/"), status == 200 {
            let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
            if payload["prepareRestore"] as? Bool == true { body["restoreToken"] = "synthetic_restore_permit_0001" }
            if let token = payload["restoreToken"] as? String {
                body["restoreToken"] = token; body["restoreOutcome"] = path.hasSuffix("/start") ? "started" : "stopped"
                body["liveViewStopUnconfirmed"] = false
            }
        }
        return CameraHTTPResponse(statusCode: status, headers: ["content-type": "application/json"],
            body: try JSONSerialization.data(withJSONObject: body))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}
