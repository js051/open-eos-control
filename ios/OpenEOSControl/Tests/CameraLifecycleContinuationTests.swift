import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// App caller tests with gated synthetic HTTP and an inert RTP session. These do
/// not exercise a camera, a socket listener, the decoder, or the GPU/display.
@MainActor
final class CameraLifecycleContinuationTests: XCTestCase {
    func testContentsEventYieldsDuringLiveViewRetirementAndResumesOnce() async throws {
        try await assertEventDuringRetirement(boundary: "resume")
    }

    func testLeavingGalleryConsumesWaitingEventWithoutListing() async throws {
        try await assertEventDuringRetirement(boundary: "leave-gallery")
    }

    func testDisconnectCancelsWaitingEventBeforeReplacementSession() async throws {
        try await assertEventDuringRetirement(boundary: "disconnect")
    }

    private func assertEventDuringRetirement(boundary: String) async throws {
        let old = LifecycleContinuationPeer(owner: "old"), fresh = LifecycleContinuationPeer(owner: "fresh")
        var peers = [old, fresh]
        let state = makeState { .desktopBridge(try DesktopBridgeClient(
            baseURL: "http://192.0.2.1:18181", transport: peers.removeFirst())) }
        defer { state.requestDisconnect(); Task { await old.releaseAll(); await fresh.releaseAll() } }
        await state.connect()
        await state.latestMediaTask?.value
        await state.startLiveView()
        XCTAssertEqual(state.activeLiveViewSource, .desktopBridgeStream)
        state.screen = .media
        try await wait { await old.eventWaiting }
        let originalModel = try XCTUnwrap(state.info?.model)
        let initialLists = await old.listReads
        let initialPolls = await old.eventPolls
        await old.holdNextStop()
        state.setApplicationActive(false)
        try await wait { await old.stopWaiting }
        XCTAssertTrue(state.liveViewRetiring)
        XCTAssertTrue(state.busyOperations.isEmpty, "Retirement is not a busy operation category")
        await old.emitContents()
        // A new model is published immediately before event listing admission.
        // This MainActor continuation cannot run if that admission spins forever.
        try await wait { state.info?.model != originalModel }
        XCTAssertTrue(state.liveViewRetiring)
        XCTAssertFalse(state.mediaLibraryLoading)
        let waitingLists = await old.listReads
        XCTAssertEqual(waitingLists, initialLists, "No traversal while Stop owns retirement")

        if boundary == "disconnect" {
            state.requestDisconnect()
            await old.releaseAll()
            await state.disconnect()
            state.setApplicationActive(true)
            await state.connect()
            await state.latestMediaTask?.value
            XCTAssertTrue(state.connected)
            XCTAssertTrue(state.info?.model.hasPrefix("Synthetic fresh") == true)
            let retiredLists = await old.listReads
            let replacementActions = await fresh.actions
            XCTAssertEqual(retiredLists, initialLists, "Cancelled old event cannot start a traversal")
            XCTAssertTrue(replacementActions.isEmpty, "Old retirement cannot start/stop the replacement")
        } else {
            if boundary == "leave-gallery" { state.screen = .control }
            state.setApplicationActive(true)
            await old.releaseStop()
            try await wait {
                let polls = await old.eventPolls
                return !state.liveViewRetiring && polls > initialPolls
            }
            let afterLists = await old.listReads
            XCTAssertEqual(afterLists, initialLists + (boundary == "resume" ? 1 : 0))
            XCTAssertNil(state.activeLiveViewSource, "Completing an event does not restart Live View")
            if boundary == "resume" { XCTAssertEqual(state.mediaLibraryLoadStatus, .complete) }
        }
    }

    func testMaintenanceRestoreRearmsRTPWatchOnlyWhenRenderingIsEnabled() async throws {
        for enabled in [true, false] {
            let peer = LifecycleContinuationPeer(owner: "rtp", direct: true)
            let state = makeRTPState(peer)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await state.connect()
            await state.startLiveView()
            XCTAssertEqual(state.activeLiveViewSource, .ccapiRTP)
            await state.setAutoRefresh(true)
            XCTAssertTrue(state.hasLiveViewRenderWatchTask)
            await state.cleanSensor(autoPowerOff: false)
            XCTAssertFalse(state.hasLiveViewRenderWatchTask)
            XCTAssertNil(state.activeLiveViewSource)
            let id = try XCTUnwrap(state.maintenanceRecord?.id)
            XCTAssertTrue(state.canRestoreMaintenance(id))
            await state.setAutoRefresh(enabled)
            await state.restoreMaintenance(id)
            XCTAssertEqual(state.maintenanceRecord?.outcome.restoreEvidence, .confirmed)
            XCTAssertEqual(state.activeLiveViewSource, .ccapiRTP)
            XCTAssertEqual(state.hasLiveViewRenderWatchTask, enabled)
            state.setApplicationActive(false)
            XCTAssertFalse(state.hasLiveViewRenderWatchTask, "Background retires the restored watcher")
        }
    }

    func testCancelledMaintenanceRTPRestoreCannotRearmWatch() async throws {
        try await assertRetiredRTPRestore(reconnect: false)
    }

    func testRetiredMaintenanceRTPRestoreCannotArmReplacementWatch() async throws {
        try await assertRetiredRTPRestore(reconnect: true)
    }

    private func assertRetiredRTPRestore(reconnect: Bool) async throws {
        let peer = LifecycleContinuationPeer(owner: "rtp", direct: true)
        let fresh = LifecycleContinuationPeer(owner: "fresh-rtp", direct: true)
        var peers = [peer, fresh]
        let state = makeState { .ccapi(try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera,
            rtpDestinationAddress: "192.0.2.2", rtpSessionFactory: ContinuationRTPFactory(), transport: peers.removeFirst())) }
        defer { state.requestDisconnect(); Task { await peer.releaseAll(); await fresh.releaseAll() } }
        await state.connect(); await state.startLiveView(); await state.setAutoRefresh(true)
        await state.cleanSensor(autoPowerOff: false)
        let id = try XCTUnwrap(state.maintenanceRecord?.id)
        await peer.holdNextRTPStart()
        let restore = Task { await state.restoreMaintenance(id) }
        try await wait { await peer.startWaiting }
        if reconnect { state.requestDisconnect() } else { state.cancelMaintenance() }
        await peer.releaseStart()
        await restore.value
        XCTAssertFalse(state.hasLiveViewRenderWatchTask)
        XCTAssertNil(state.activeLiveViewSource)
        if reconnect {
            await state.disconnect()
            state.autoRefresh = false
            await state.connect()
            XCTAssertTrue(state.connected)
            XCTAssertEqual(state.info?.model, "Synthetic fresh-rtp")
            XCTAssertFalse(state.hasLiveViewRenderWatchTask)
            let replacementActions = await fresh.actions
            XCTAssertTrue(replacementActions.isEmpty, "Old restore cannot start replacement RTP")
        } else {
            XCTAssertTrue(state.maintenanceControlsPaused)
            XCTAssertEqual(state.maintenanceRecord?.outcome.restoreEvidence, .unknown)
        }
    }

    private func makeRTPState(_ peer: LifecycleContinuationPeer) -> CameraAppState {
        makeState { .ccapi(try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera,
            rtpDestinationAddress: "192.0.2.2", rtpSessionFactory: ContinuationRTPFactory(), transport: peer)) }
    }

    private func makeState(_ factory: @escaping @MainActor () throws -> CameraSession) -> CameraAppState {
        let suite = "CameraLifecycleContinuationTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults, sessionFactory: factory)
        state.autoRefresh = false
        return state
    }

    private func wait(_ condition: @MainActor () async -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        repeat { if await condition() { return }; await Task.yield() } while ContinuousClock.now < deadline
        XCTFail("The controlled lifecycle boundary did not settle")
        throw URLError(.timedOut)
    }
}

private actor ContinuationRTPSession: CCAPIRTPSession {
    nonisolated let sourceURL = URL(string: "rtp://192.0.2.2:12000")!
    func start() async throws { try Task.checkCancellation() }
    func waitUntilReady() async throws { try Task.checkCancellation() }
    func setTargetFPS(_ fps: Int) async {}
    func close() async {}
}

private struct ContinuationRTPFactory: CCAPIRTPSessionFactory {
    func makeSession(description: CCAPIRTPSessionDescription, destinationAddress: String) async throws -> any CCAPIRTPSession {
        ContinuationRTPSession()
    }
}

private actor LifecycleContinuationPeer: CameraHTTPTransport {
    private let owner: String
    private let direct: Bool
    private var infoReads = 0
    private(set) var listReads = 0
    private(set) var eventPolls = 0
    private(set) var actions: [String] = []
    private var holdStop = false, holdStart = false
    private var stopGate: CheckedContinuation<Void, Never>?
    private var startGate: CheckedContinuation<Void, Never>?
    private var eventGate: CheckedContinuation<CameraHTTPResponse, Never>?
    var stopWaiting: Bool { stopGate != nil }
    var startWaiting: Bool { startGate != nil }
    var eventWaiting: Bool { eventGate != nil }
    init(owner: String, direct: Bool = false) { self.owner = owner; self.direct = direct }
    func holdNextStop() { holdStop = true }
    func holdNextRTPStart() { holdStart = true }
    func releaseStop() { stopGate?.resume(); stopGate = nil }
    func releaseStart() { startGate?.resume(); startGate = nil }
    func emitContents() {
        let pending = eventGate; eventGate = nil
        pending?.resume(returning: json(["changedKeys": ["contents"]]))
    }
    func releaseAll() {
        releaseStop(); releaseStart()
        let pending = eventGate; eventGate = nil
        pending?.resume(returning: json(["changedKeys": []]))
    }
    func sendMaintenance(_ request: URLRequest) async throws -> CameraHTTPResponse { try await send(request) }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path, method = request.httpMethod ?? "GET"
        if path == "/ccapi" {
            return json(["ver130": [
                ["path": "/deviceinformation", "get": true],
                ["path": "/functions/sensorcleaning", "post": true],
                ["path": "/shooting/liveview", "post": true, "delete": true],
                ["path": "/shooting/liveview/flip", "get": true],
                ["path": "/shooting/liveview/rtpsessiondesc", "get": true],
                ["path": "/shooting/liveview/rtp", "post": true]]])
        }
        if path.hasSuffix("/rtpsessiondesc") {
            return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "application/sdp"],
                body: Data("v=0\r\nm=video 12000 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n".utf8))
        }
        if path.hasSuffix("/rtp") {
            let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: String]
            let action = payload["action"]!
            actions.append(action)
            if action == "start", holdStart { holdStart = false; await withCheckedContinuation { startGate = $0 } }
            return json([:])
        }
        if path.hasSuffix("/sensorcleaning") { actions.append("clean"); return json([:]) }
        if path.hasSuffix("/info") || path.hasSuffix("/deviceinformation") {
            infoReads += 1
            return json(["connected": true, "model": "Synthetic \(owner) \(infoReads)",
                "productname": "Synthetic \(owner)", "serial": "SYNTHETIC", "api": "fixture"])
        }
        if direct { return CameraHTTPResponse(statusCode: 404) } // Unadvertised optional reads stay unavailable.
        if path == "/health" { return json(["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]) }
        if path == "/v1/session" { return json(["id": owner, "engine": "ccapi"]) }
        if path == "/v1/session/\(owner)", method == "DELETE" { return CameraHTTPResponse(statusCode: 204) }
        if path.hasSuffix("/status") { return json(["connected": true, "recording": false, "liveViewStopUnconfirmed": false]) }
        if path.hasSuffix("/capabilities") {
            return json(["supported": ["LIVE_VIEW", "MEDIA_BROWSER", "EVENT_POLLING"], "settings": [],
                "liveView": ["sources": ["DESKTOP_BRIDGE_STREAM"], "sizes": ["MEDIUM"], "minFps": 1, "maxFps": 30]])
        }
        if path.hasSuffix("/events") {
            if method == "DELETE" { releaseAll(); return CameraHTTPResponse(statusCode: 204) }
            eventPolls += 1
            return await withCheckedContinuation { eventGate = $0 }
        }
        if path.hasSuffix("/media") { listReads += 1; return json(["items": []]) }
        if path.hasSuffix("/liveview/start") { actions.append("start"); return json(["active": true]) }
        if path.hasSuffix("/liveview/stop") {
            actions.append("stop")
            if holdStop { holdStop = false; await withCheckedContinuation { stopGate = $0 } }
            return json(["active": false, "liveViewStopUnconfirmed": false])
        }
        throw URLError(.unsupportedURL)
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    private func json(_ object: [String: Any]) -> CameraHTTPResponse {
        CameraHTTPResponse(statusCode: 200, headers: ["content-type": "application/json"],
            body: try! JSONSerialization.data(withJSONObject: object))
    }
}
