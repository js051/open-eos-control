import Foundation
import OpenEOSCore
import XCTest
@testable import OpenEOSControl

@MainActor
final class MaintenanceJourneyTests: XCTestCase {
    func testKeepPowerCleaningRequiresSeparateRestoreWithFreshReadAndRestoresOnlyOriginalView() async throws {
        for wasLive in [false, true] {
            let peer = MaintenanceAppPeer(), state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.release() } }
            await state.connect()
            if wasLive { await state.startLiveView() }
            await state.cleanSensor(autoPowerOff: false)
            let id = try XCTUnwrap(state.maintenanceRecord?.id)
            XCTAssertEqual(state.maintenanceRecord?.outcome.commandEvidence, .responded)
            XCTAssertEqual(state.maintenanceRecord?.outcome.observationEvidence, .reachable)
            XCTAssertNil(state.activeLiveViewSource)
            XCTAssertTrue(state.maintenanceControlsPaused)
            XCTAssertTrue(state.canRestoreMaintenance(id))
            let before = await peer.trace()
            XCTAssertEqual(before.filter { $0 == "POST liveview/start" }.count, wasLive ? 1 : 0)
            await state.restoreMaintenance(id)
            XCTAssertEqual(state.maintenanceRecord?.outcome.restoreEvidence, .confirmed)
            XCTAssertFalse(state.maintenanceControlsPaused)
            XCTAssertEqual(state.activeLiveViewSource != nil, wasLive)
            let after = await peer.trace()
            XCTAssertEqual(after.filter { $0 == "GET maintenance/sensor-cleaning" }.count, 2)
            XCTAssertEqual(after.filter { $0 == "POST maintenance/sensor-cleaning/outcome" }.count, 1)
            XCTAssertEqual(after.filter { $0 == "POST maintenance/control/liveview/start" }.count, wasLive ? 1 : 0)
        }
    }
    func testSleepAndPowerOffStayPausedWithoutAutomaticObservationDeleteOrRestore() async throws {
        for sleep in [false, true] {
            let peer = MaintenanceAppPeer(), state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.release() } }
            await state.connect()
            if sleep { await state.sleepCamera() } else { await state.cleanSensor(autoPowerOff: true) }
            XCTAssertTrue(state.connected)
            XCTAssertTrue(state.maintenanceControlsPaused)
            XCTAssertEqual(state.maintenanceRecord?.outcome.commandEvidence, .responded)
            XCTAssertEqual(state.maintenanceRecord?.outcome.restoreEvidence, .notApplicable)
            let trace = await peer.trace()
            XCTAssertFalse(trace.contains { $0.hasPrefix("GET maintenance/") || $0 == "DELETE session" || $0 == "POST liveview/start" })
            await state.startLiveView()
            let after = await peer.trace()
            XCTAssertEqual(after, trace, "A paused lifecycle cannot restart through an ordinary Start")
        }
    }
    func testUnknownCommandReadRecoveryNeverReplaysOrClaimsCompletion() async throws {
        let peer = MaintenanceAppPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.release() } }
        await state.connect(); await peer.failCommand()
        await state.cleanSensor(autoPowerOff: false)
        XCTAssertEqual(state.maintenanceRecord?.outcome.commandEvidence, .unknown)
        let id = try XCTUnwrap(state.maintenanceRecord?.id)
        await state.recoverMaintenance(id)
        XCTAssertEqual(state.maintenanceRecord?.outcome.commandEvidence, .unknown)
        XCTAssertEqual(state.maintenanceRecord?.outcome.observationEvidence, .reachable)
        XCTAssertFalse(state.canRestoreMaintenance(id))
        let trace = await peer.trace()
        XCTAssertEqual(trace.filter { $0 == "POST maintenance/sensor-cleaning/outcome" }.count, 1)
        XCTAssertFalse(trace.contains("POST liveview/start"))
    }
    func testClockOriginalProofSurvivesLaterReadFailureAndClockRecoveryDoesNotWrite() async throws {
        let peer = MaintenanceAppPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.release() } }
        await state.connect(); await state.syncCameraClock()
        XCTAssertNotNil(state.lastClockSyncAt)
        XCTAssertEqual(state.maintenanceRecord?.outcome.clockReadEvidence, .matched)
        await peer.failRead()
        await state.recoverMaintenance()
        XCTAssertEqual(state.maintenanceRecord?.outcome.clockReadEvidence, .matched)
        XCTAssertEqual(state.maintenanceRecord?.outcome.observationEvidence, .unavailable)
        let trace = await peer.trace()
        XCTAssertEqual(trace.filter { $0 == "POST maintenance/clock-sync/outcome" }.count, 1)
        XCTAssertEqual(trace.filter { $0 == "GET maintenance/clock-sync" }.count, 1)
    }
    func testEventStopFailurePreventsMaintenanceAndPreservesUnknownStop() async throws {
        let peer = MaintenanceAppPeer(events: true), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.release() } }
        await state.connect(); await peer.failStop()
        await state.cleanSensor(autoPowerOff: false)
        XCTAssertEqual(state.maintenanceRecord?.outcome.stopEvidence, .unknown)
        XCTAssertEqual(state.maintenanceRecord?.outcome.commandEvidence, .notSent)
        let trace = await peer.trace()
        XCTAssertFalse(trace.contains { $0.hasSuffix("/outcome") })
        XCTAssertTrue(state.maintenanceControlsPaused)
    }
    func testCancelRetainsLateResponseWithoutCurrentPublishOrNewCommand() async throws {
        let peer = MaintenanceAppPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.release() } }
        await state.connect(); await peer.holdCommand()
        let task = Task { await state.syncCameraClock() }
        try await wait { await peer.waiting }
        state.cancelMaintenance(); await peer.release(); await task.value
        XCTAssertNil(state.lastClockSyncAt)
        XCTAssertEqual(state.maintenanceRecord?.outcome.commandEvidence, .responded)
        XCTAssertTrue(state.maintenanceRecord?.cancelled == true)
        let trace = await peer.trace()
        XCTAssertEqual(trace.filter { $0.hasSuffix("/outcome") }.count, 1)
    }
    func testRetiredResponseCannotClearReplacementBusyOrPublishClockAndOldConfirmationCannotSend() async throws {
        let old = MaintenanceAppPeer(owner: "old"), fresh = MaintenanceAppPeer(owner: "new")
        let state = makeState([old, fresh])
        defer { state.requestDisconnect(); Task { await old.release(); await fresh.release() } }
        await state.connect()
        let confirmation = state.maintenanceConfirmation(for: .sleep)
        await old.holdCommand()
        let first = Task { await state.syncCameraClock() }
        try await wait { await old.waiting }
        await state.disconnect(); await state.connect()
        await state.sleepCamera(confirmation: confirmation)
        let pre = await fresh.trace()
        XCTAssertFalse(pre.contains { $0.hasSuffix("/outcome") })
        await fresh.holdCommand()
        let second = Task { await state.syncCameraClock() }
        try await wait { await fresh.waiting }
        await old.release(); await first.value
        XCTAssertTrue(state.isBusy(.clock)); XCTAssertNil(state.lastClockSyncAt)
        XCTAssertEqual(state.maintenanceHistory.first?.outcome.commandEvidence, .responded)
        XCTAssertTrue(state.maintenanceHistory.first?.retired == true)
        await fresh.release(); await second.value
        XCTAssertFalse(state.isBusy(.clock)); XCTAssertNotNil(state.lastClockSyncAt)
    }
    func testRecordingAndBulbPreconditionsRejectAllMaintenanceWithoutDispatch() async throws {
        for bulb in [false, true] {
            let peer = MaintenanceAppPeer(recording: !bulb, bulb: bulb), state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.release() } }
            await state.connect()
            await state.syncCameraClock(); await state.cleanSensor(autoPowerOff: false); await state.sleepCamera()
            XCTAssertTrue(state.maintenanceHistory.isEmpty)
            let trace = await peer.trace()
            XCTAssertFalse(trace.contains { $0.hasSuffix("/outcome") })
        }
    }
    func testRestoreFailureRetainsResponseAndRequiresAnotherExplicitFreshProbe() async throws {
        let peer = MaintenanceAppPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.release() } }
        await state.connect(); await state.startLiveView(); await state.cleanSensor(autoPowerOff: false)
        let id = try XCTUnwrap(state.maintenanceRecord?.id)
        await peer.failRead(); await state.restoreMaintenance(id)
        XCTAssertEqual(state.maintenanceRecord?.outcome.commandEvidence, .responded)
        XCTAssertEqual(state.maintenanceRecord?.outcome.restoreEvidence, .unknown)
        XCTAssertTrue(state.maintenanceControlsPaused)
        XCTAssertNil(state.activeLiveViewSource)
        let before = await peer.trace()
        XCTAssertEqual(before.filter { $0 == "POST liveview/start" }.count, 1)
        await state.recoverMaintenance(id)
        await state.restoreMaintenance(id)
        let after = await peer.trace()
        XCTAssertEqual(after.filter { $0 == "POST maintenance/sensor-cleaning/outcome" }.count, 1)
        XCTAssertEqual(after.filter { $0 == "POST maintenance/control/liveview/start" }.count, 1)
        XCTAssertEqual(state.maintenanceRecord?.outcome.restoreEvidence, .confirmed)
    }
    func testHistoryIsBoundedAndHistoricalReadCannotChangeSelectedIntent() async throws {
        let peer = MaintenanceAppPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.release() } }
        await state.connect()
        for _ in 0..<21 { await state.syncCameraClock() }
        XCTAssertEqual(state.maintenanceHistory.count, 20)
        let selected = state.maintenanceCurrentID, old = try XCTUnwrap(state.maintenanceHistory.first?.id)
        await state.recoverMaintenance(old)
        XCTAssertEqual(state.maintenanceCurrentID, selected)
        XCTAssertFalse(state.canRestoreMaintenance(old))
        let trace = await peer.trace()
        XCTAssertEqual(trace.filter { $0 == "POST maintenance/clock-sync/outcome" }.count, 21)
    }

    func testCancelDuringMaintenanceStopRetainsWarningAndNeverDispatchesCommand() async throws {
        let peer = MaintenanceAppPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.release() } }
        await state.connect(); await state.startLiveView(); await peer.holdControl("stop")
        let task = Task { await state.cleanSensor(autoPowerOff: false) }
        try await wait { await peer.waiting }
        state.cancelMaintenance(); await peer.release(); await task.value
        XCTAssertEqual(state.maintenanceRecord?.outcome.stopEvidence, .unknown)
        XCTAssertEqual(state.maintenanceRecord?.outcome.commandEvidence, .notSent)
        XCTAssertTrue(state.liveViewStopState.stopUnconfirmed)
        XCTAssertTrue(state.maintenanceControlsPaused)
        let trace = await peer.trace()
        XCTAssertFalse(trace.contains { $0.hasSuffix("/outcome") })
        XCTAssertEqual(trace.filter { $0 == "POST maintenance/control/liveview/stop" }.count, 1)
        await state.retryLiveViewStop()
        let retried = await peer.trace()
        XCTAssertEqual(retried.filter { $0 == "POST maintenance/control/liveview/stop" }.count, 2)
        XCTAssertFalse(retried.contains("POST liveview/stop"))
    }

    func testCancelDuringRestoreStartCannotPublishOrAutomaticallyStop() async throws {
        let peer = MaintenanceAppPeer(), state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.release() } }
        await state.connect(); await state.startLiveView(); await state.cleanSensor(autoPowerOff: false)
        let id = try XCTUnwrap(state.maintenanceRecord?.id)
        await peer.holdControl("start")
        let task = Task { await state.restoreMaintenance(id) }
        try await wait { await peer.waiting }
        state.cancelMaintenance(); await peer.release(); await task.value
        XCTAssertEqual(state.maintenanceRecord?.outcome.restoreEvidence, .unknown)
        XCTAssertEqual(state.maintenanceRecord?.outcome.commandEvidence, .responded)
        XCTAssertNil(state.activeLiveViewSource)
        XCTAssertTrue(state.liveViewStopState.stopUnconfirmed)
        XCTAssertTrue(state.maintenanceControlsPaused)
        let trace = await peer.trace(), strict = await peer.strictTrace()
        XCTAssertEqual(trace.filter { $0 == "POST maintenance/control/liveview/start" }.count, 1)
        XCTAssertEqual(trace.filter { $0 == "POST maintenance/control/liveview/stop" }.count, 1)
        XCTAssertFalse(trace.contains("POST liveview/stop"))
        XCTAssertTrue(strict.contains { $0.hasSuffix("/maintenance/control/liveview/start") })
        XCTAssertTrue(state.canRetryLiveViewStop, "Explicit Stop remains available while maintenance controls are held")
        await state.retryLiveViewStop()
        let retried = await peer.trace()
        XCTAssertEqual(retried.filter { $0 == "POST maintenance/control/liveview/stop" }.count, 2)
        XCTAssertEqual(retried.filter { $0 == "POST maintenance/control/liveview/start" }.count, 1)
        XCTAssertTrue(state.maintenanceControlsPaused)
        XCTAssertEqual(state.maintenanceRecord?.outcome.restoreEvidence, .unknown)
    }

    func testRetiredRestoreStartCannotPublishIntoReplacementSession() async throws {
        let old = MaintenanceAppPeer(owner: "old"), fresh = MaintenanceAppPeer(owner: "fresh")
        let state = makeState([old, fresh])
        defer { state.requestDisconnect(); Task { await old.release(); await fresh.release() } }
        await state.connect(); await state.startLiveView(); await state.cleanSensor(autoPowerOff: false)
        let id = try XCTUnwrap(state.maintenanceRecord?.id)
        await old.holdControl("start")
        let task = Task { await state.restoreMaintenance(id) }
        try await wait { await old.waiting }
        await state.disconnect(); await state.connect()
        await fresh.holdCommand()
        let newTask = Task { await state.syncCameraClock() }
        try await wait { await fresh.waiting }
        await old.release(); await task.value
        XCTAssertTrue(state.isBusy(.clock)); XCTAssertNil(state.activeLiveViewSource)
        XCTAssertTrue(state.maintenanceHistory.first?.retired == true)
        XCTAssertEqual(state.maintenanceHistory.first?.outcome.restoreEvidence, .unknown)
        await fresh.release(); await newTask.value
        XCTAssertNotNil(state.lastClockSyncAt)
    }

    func testBackgroundForegroundAndRefreshCannotResumePausedMaintenance() async throws {
        for sleep in [false, true] {
            let peer = MaintenanceAppPeer(events: true), state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.release() } }
            await state.connect(); await state.startLiveView()
            if sleep { await state.sleepCamera() } else { await state.cleanSensor(autoPowerOff: true) }
            let before = await peer.trace()
            state.setApplicationActive(false); state.setApplicationActive(true)
            await state.setAutoRefresh(true); await state.startLiveView(); await state.restartLiveView()
            for _ in 0..<10 { await Task.yield() }
            let after = await peer.trace()
            XCTAssertEqual(after, before, "No automatic GET / DELETE / Start after paused sleep or power-off")
            XCTAssertTrue(state.maintenanceControlsPaused); XCTAssertNil(state.activeLiveViewSource)
        }
    }

    func testExplicitCloseReportsLocalRetirementFailureWithoutGenericDeleteFallback() async throws {
        let peer = MaintenanceAppPeer(), state = makeState([peer])
        await state.connect(); await state.cleanSensor(autoPowerOff: false)
        await peer.failRetirement(); await state.disconnect()
        XCTAssertEqual(state.previousMaintenanceRetirementWarnings.count, 1)
        let trace = await peer.trace()
        XCTAssertEqual(trace.filter { $0 == "DELETE maintenance/control/session" }.count, 1)
        XCTAssertFalse(trace.contains("DELETE session"))
        state.dismissMaintenanceRetirementWarning()
        XCTAssertTrue(state.previousMaintenanceRetirementWarnings.isEmpty)
    }

    private func makeState(_ peers: [MaintenanceAppPeer]) -> CameraAppState {
        var remaining = peers
        let suite = "MaintenanceJourneyTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            .desktopBridge(try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: remaining.removeFirst()))
        })
        state.autoRefresh = false; return state
    }
    private func wait(_ predicate: () async -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(3))
        repeat { if await predicate() { return }; await Task.yield() } while ContinuousClock.now < deadline
        XCTFail("Controlled maintenance request did not enter its gate"); throw URLError(.timedOut)
    }
}

private actor MaintenanceAppPeer: CameraHTTPTransport {
    let owner: String, events: Bool, recording: Bool, bulb: Bool
    private var commandsFail = false, readsFail = false, stopsFail = false, hold = false, retirementFails = false
    private var gate: CheckedContinuation<Void, Never>?
    private var heldControl: String?
    private var requests: [String] = []
    private var strictRequests: [String] = []
    var waiting: Bool { gate != nil }
    init(owner: String = "synthetic", events: Bool = false, recording: Bool = false, bulb: Bool = false) {
        self.owner = owner; self.events = events; self.recording = recording; self.bulb = bulb
    }
    func failCommand() { commandsFail = true }
    func failRead() { readsFail = true }
    func failRetirement() { retirementFails = true }
    func failStop() { stopsFail = true }
    func holdCommand() { hold = true }
    func holdControl(_ action: String) { heldControl = "maintenance/control/liveview/" + action }
    func release() { gate?.resume(); gate = nil }
    func trace() -> [String] { requests }
    func strictTrace() -> [String] { strictRequests }
    func sendMaintenance(_ request: URLRequest) async throws -> CameraHTTPResponse {
        strictRequests.append(request.url!.path)
        XCTAssertEqual(request.timeoutInterval, 15)
        return try await send(request)
    }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path, method = request.httpMethod ?? "GET"
        let prefix = "/v1/session/\(owner)/"
        let suffix = path.hasPrefix(prefix) ? String(path.dropFirst(prefix.count)) : path == "/v1/session/\(owner)" ? "session" : path
        requests.append(method + " " + suffix)
        var body: [String: Any] = [:], status = 200
        if path == "/health" { body = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"] }
        else if path == "/v1/session" { body = ["id": owner, "engine": "ccapi"] }
        else if suffix == "info" { body = ["connected": true, "model": "Synthetic maintenance camera", "serial": "SYNTHETIC", "api": "fixture"] }
        else if suffix == "status" { body = ["connected": true, "recording": recording, "bulbExposureActive": bulb, "shutterReleaseRequired": bulb, "liveViewStopUnconfirmed": false] }
        else if suffix == "capabilities" {
            var supported: [CameraFeature] = [.cameraClockSync, .sensorCleaning, .cameraSleep, .liveView]
            if events { supported.append(.eventPolling) }
            body = ["supported": supported.map(\.rawValue), "settings": [], "liveView": ["sources": ["DESKTOP_BRIDGE_STREAM"], "sizes": ["MEDIUM"], "minFps": 1, "maxFps": 30]]
        } else if suffix == "events" {
            if method == "GET" { throw CancellationError() }
            if stopsFail { stopsFail = false; throw URLError(.networkConnectionLost) }
            status = 204
        } else if suffix == "maintenance/control/session" {
            status = retirementFails ? 503 : 204
        } else if suffix == "maintenance/control/events" {
            if stopsFail { stopsFail = false; throw URLError(.networkConnectionLost) }
            status = 204
        } else if suffix == "maintenance/control/liveview/stop" || suffix == "maintenance/control/liveview/start" {
            if heldControl == suffix { heldControl = nil; await withCheckedContinuation { gate = $0 } }
            body = ["active": suffix.hasSuffix("/start"), "liveViewStopUnconfirmed": false,
                    "source": "CCAPI_JPEG_POLLING", "size": "MEDIUM", "requestedFps": 6]
        } else if suffix.hasPrefix("maintenance/") {
            let operation = String(suffix.split(separator: "/")[1])
            let sample: [String: Any] = ["epochSeconds": 1_700_000_000, "utcOffsetMinutes": 480, "daylightSaving": false]
            if method == "POST" {
                if hold { hold = false; await withCheckedContinuation { gate = $0 } }
                if commandsFail { commandsFail = false; throw URLError(.networkConnectionLost) }
                let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
                body = ["version": 1, "engine": "ccapi", "operation": operation, "autoPowerOff": payload["autoPowerOff"]!, "responseEvidence": "response-received",
                        "clockReference": NSNull(), "clockObservation": NSNull(), "clockReadEvidence": "not-applicable"]
                if operation == "clock-sync" {
                    body["clockReference"] = ["earliestEpochSeconds": 1_699_999_990, "latestEpochSeconds": 1_700_000_010, "utcOffsetMinutes": 480, "daylightSaving": false]
                    body["clockObservation"] = sample; body["clockReadEvidence"] = "matched"
                }
            } else {
                if readsFail { readsFail = false; throw URLError(.networkConnectionLost) }
                body = ["version": 1, "engine": "ccapi", "operation": operation,
                        "observationEvidence": operation == "clock-sync" ? "observed" : "reachable",
                        "clockObservation": operation == "clock-sync" ? sample : NSNull()]
            }
        } else if suffix == "liveview/start" { body = ["active": true] }
        else if suffix == "liveview/stop" { body = ["active": false, "liveViewStopUnconfirmed": false] }
        else if suffix == "session", method == "DELETE" { status = 204 }
        else { throw URLError(.unsupportedURL) }
        return CameraHTTPResponse(statusCode: status, body: status == 204 ? Data() : try JSONSerialization.data(withJSONObject: body))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}
