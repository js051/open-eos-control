import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Authored caller regressions over a synthetic peer; no socket or camera.
@MainActor
final class BridgeFocusRetirementStateTests: XCTestCase {
    func testLateSettledStartClearsOnlyItsOwnWarningWithoutRestart() async throws {
        try await assertLateSettlement(newerObservation: false)
    }

    func testLateSettledStartCannotClearNewerWarningObservation() async throws {
        try await assertLateSettlement(newerObservation: true)
    }

    func testDisconnectedOldStartCannotClearReplacementOwnerOrWarning() async throws {
        let oldPeer = BridgeFocusRetirementPeer(), freshPeer = BridgeFocusRetirementPeer()
        let oldClient = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: oldPeer)
        let freshClient = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: freshPeer)
        let suite = "BridgeFocusRetirementStateTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        var clients = [oldClient, freshClient]
        let state = CameraAppState(defaults: defaults, sessionFactory: { .desktopBridge(clients.removeFirst()) })
        state.autoRefresh = false
        defer { state.requestDisconnect(); Task { await oldPeer.releaseStart(); await freshPeer.releaseStart() } }
        await state.connect(); await state.latestMediaTask?.value
        let old = try XCTUnwrap(state.beginHeldAutofocus())
        try await wait { await oldPeer.startWaiting }
        state.releaseHeldAutofocus(owner: old)
        try await wait { state.canRetryShutterRelease && !state.busyOperations.contains(.focus) }
        await state.disconnect()
        await state.connect(); await state.latestMediaTask?.value
        let current = try XCTUnwrap(state.beginHeldAutofocus())
        try await wait { await freshPeer.startWaiting }
        await oldPeer.releaseStart()
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertTrue(state.ownsHeldAutofocus(current))
        XCTAssertTrue(state.heldAutofocusPressed)
        XCTAssertTrue(state.busyOperations.contains(.focus))
        XCTAssertTrue(state.previousShutterReleaseUnconfirmed)
        state.releaseHeldAutofocus(owner: current)
        try await wait { state.canRetryShutterRelease && !state.busyOperations.contains(.focus) }
        await freshPeer.releaseStart()
        try await wait { !state.shutterReleaseRequired }
        XCTAssertTrue(state.previousShutterReleaseUnconfirmed, "New owner settlement cannot erase previous connection evidence")
    }

    private func assertLateSettlement(newerObservation: Bool) async throws {
        let peer = BridgeFocusRetirementPeer()
        let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: peer)
        let suite = "BridgeFocusRetirementStateTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults, sessionFactory: { .desktopBridge(client) })
        state.autoRefresh = false
        defer { state.requestDisconnect(); Task { await peer.releaseStart() } }
        await state.connect()
        await state.latestMediaTask?.value
        XCTAssertTrue(state.connected)
        let owner = try XCTUnwrap(state.beginHeldAutofocus())
        try await wait { await peer.startWaiting }
        state.releaseHeldAutofocus(owner: owner)
        try await wait { state.canRetryShutterRelease && !state.busyOperations.contains(.focus) }
        XCTAssertTrue(state.shutterReleaseUnconfirmed)
        let startStillHeld = await peer.startWaiting
        XCTAssertTrue(startStillHeld, "App release must finish before ignored-cancellation Start")
        if newerObservation {
            // A fresh ordinary observation republishes a warning and changes its
            // revision. The old retirement observer has no authority over it.
            await state.refresh()
            XCTAssertTrue(state.shutterReleaseUnconfirmed)
        }
        await peer.releaseStart()
        try await wait { !(await client.shutterReleaseState()).releaseRequired }
        if newerObservation {
            // Drain already-ready main-actor observers without creating new work.
            try await Task.sleep(for: .milliseconds(100))
            XCTAssertTrue(state.shutterReleaseRequired)
            XCTAssertTrue(state.shutterReleaseUnconfirmed)
        } else {
            try await wait { !state.shutterReleaseRequired }
            XCTAssertFalse(state.shutterReleaseUnconfirmed)
            XCTAssertTrue(state.canStartHeldAutofocus)
        }
        let paths = await peer.paths
        XCTAssertEqual(paths.filter { $0.hasSuffix("/focus/auto/stop") }.count, 1)
        XCTAssertFalse(paths.contains { $0.contains("/liveview/start") }, "Safety reconciliation must not auto-resume")
    }

    private func wait(_ condition: @MainActor () async -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        repeat { if await condition() { return }; await Task.yield() } while ContinuousClock.now < deadline
        XCTFail("Held-focus caller boundary did not settle")
        throw URLError(.timedOut)
    }
}

private actor BridgeFocusRetirementPeer: CameraHTTPTransport {
    private var startGate: CheckedContinuation<Void, Never>?
    private(set) var paths: [String] = []
    var startWaiting: Bool { startGate != nil }
    func releaseStart() { startGate?.resume(); startGate = nil }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path, method = request.httpMethod ?? "GET"
        paths.append(path)
        if path == "/health" { return json(["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]) }
        if path == "/v1/session" { return json(["id": "synthetic-focus", "engine": "ccapi"]) }
        if path == "/v1/session/synthetic-focus", method == "DELETE" { return CameraHTTPResponse(statusCode: 204) }
        if path.hasSuffix("/info") { return json(["connected": true, "model": "Synthetic focus", "api": "fixture"]) }
        if path.hasSuffix("/status") { return json(["connected": true, "recording": false]) }
        if path.hasSuffix("/capabilities") {
            return json(["supported": ["AUTOFOCUS"], "settings": [], "heldAutofocusSupported": true])
        }
        if path.hasSuffix("/media") { return json(["items": []]) }
        if path.hasSuffix("/focus/auto/start") || path.hasSuffix("/focus/auto/stop") {
            let body = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
            let starting = path.hasSuffix("/start")
            if starting { await withCheckedContinuation { startGate = $0 } }
            return json(["ownerId": body["ownerId"]!, "active": starting, "stopUnconfirmed": false])
        }
        throw URLError(.unsupportedURL)
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    private func json(_ object: [String: Any]) -> CameraHTTPResponse {
        CameraHTTPResponse(statusCode: 200, headers: ["content-type": "application/json"],
                           body: try! JSONSerialization.data(withJSONObject: object))
    }
}
