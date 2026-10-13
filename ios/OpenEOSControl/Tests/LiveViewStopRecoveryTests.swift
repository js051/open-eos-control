import Foundation
import OpenEOSCore
import XCTest
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

    func testMaintenanceStopFailureBlocksCommandAndHealthyCleanRestartsView() async throws {
        for cleaning in [false, true] {
            let peer = AppLiveViewPeer()
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            await state.startLiveView()
            await peer.failNextStop()
            if cleaning { await state.cleanSensor(autoPowerOff: false) }
            else { await state.sleepCamera() }
            XCTAssertTrue(state.liveViewStopState.stopUnconfirmed)
            XCTAssertTrue(state.canRetryLiveViewStop)
            XCTAssertTrue(state.connected)
            let failed = await peer.actions()
            XCTAssertEqual(failed, ["start", "stop"], "No maintenance or automatic Start after failed Stop")
            await state.retryLiveViewStop()
            await state.startLiveView()
            await state.cleanSensor(autoPowerOff: false)
            XCTAssertEqual(state.activeLiveViewSource, .desktopBridgeStream)
            XCTAssertFalse(state.liveViewStopState.stopUnconfirmed)
            let completed = await peer.actions()
            XCTAssertEqual(Array(completed.suffix(3)), ["stop", "clean", "start"])
        }
    }

    func testDirectCleaningRetryClearsDischargedStopRecoveryWithoutSeparateStopAction() async throws {
        let peer = AppLiveViewPeer()
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        await state.connect()
        await state.startLiveView()
        await peer.failNextStop()
        await state.cleanSensor(autoPowerOff: false)
        XCTAssertTrue(state.liveViewStopState.stopUnconfirmed)
        XCTAssertNil(state.activeLiveViewSource)

        await state.cleanSensor(autoPowerOff: false)

        XCTAssertEqual(state.liveViewStopState, .idle)
        XCTAssertFalse(state.canRetryLiveViewStop)
        XCTAssertNil(state.lastError)
        let actions = await peer.actions()
        XCTAssertEqual(actions, ["start", "stop", "stop", "clean"])
        await state.startLiveView()
        XCTAssertEqual(state.activeLiveViewSource, .desktopBridgeStream)
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

    private func wait(_ predicate: () async -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(3))
        repeat { if await predicate() { return }; await Task.yield() } while clock.now < deadline
        XCTFail("Expected controlled request gate")
        throw URLError(.timedOut)
    }

    private func makeState(_ peers: [AppLiveViewPeer]) -> CameraAppState {
        var remaining = peers
        let suite = "LiveViewStopRecoveryTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            .desktopBridge(try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: remaining.removeFirst()))
        })
        state.autoRefresh = false
        return state
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
        let body: [String: Any]
        var status = 200
        if path == "/health" { body = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"] }
        else if path == "/v1/session" { body = ["id": owner, "engine": "ccapi"] }
        else if path == "/v1/session/\(owner)", method == "DELETE" { body = [:] }
        else if path.hasSuffix("/info") { body = ["connected": true, "model": "Synthetic Live View camera", "serial": "SYNTHETIC", "api": "fixture"] }
        else if path.hasSuffix("/status") { body = ["connected": true, "recording": false, "liveViewStopUnconfirmed": false] }
        else if path.hasSuffix("/capabilities") {
            body = ["supported": [CameraFeature.liveView.rawValue, CameraFeature.cameraSleep.rawValue, CameraFeature.sensorCleaning.rawValue], "settings": [],
                    "liveView": ["sources": ["DESKTOP_BRIDGE_STREAM"], "sizes": ["MEDIUM"], "minFps": 1, "maxFps": 30]]
        } else if path == "/v1/session/\(owner)/liveview/start" {
            recorded.append("start")
            if holdStart {
                holdStart = false
                await withTaskCancellationHandler {
                    await withCheckedContinuation { gate = $0 }
                } onCancel: { Task { await self.noteStartCancellation() } }
            }
            if loseStart { loseStart = false; throw URLError(.networkConnectionLost) }
            body = ["active": true]
        } else if path == "/v1/session/\(owner)/liveview/stop" {
            recorded.append("stop")
            if stopFailures > 0 {
                stopFailures -= 1; status = 502
                body = ["code": "LIVE_VIEW_STOP_UNCONFIRMED", "message": "Synthetic stop response lost"]
            } else { body = ["active": false, "liveViewStopUnconfirmed": false] }
        } else if path == "/v1/session/\(owner)/maintenance/sensor-cleaning" {
            recorded.append("clean"); body = [:]
        } else if path == "/v1/session/\(owner)/power/sleep" {
            recorded.append("sleep"); body = [:]
        } else { throw URLError(.unsupportedURL) }
        return CameraHTTPResponse(statusCode: status, headers: ["content-type": "application/json"],
            body: try JSONSerialization.data(withJSONObject: body))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}
