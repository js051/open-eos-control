import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Authored native tests over production app state and protocol clients.
/// In-memory gates ignore cancellation; there are no sockets or physical cameras.
@MainActor
final class ConnectionCancellationTests: XCTestCase {
    func testCancelPreservesEditableSettingsAndExplicitRetryWaitsForExactOldOwner() async throws {
        for bridge in [false, true] {
            for fails in [false, true] {
                let old = ConnectingPeer(owner: "A", phase: bridge ? .creation : .metadata,
                                         outcome: fails ? .rejected : .success, holdDeletion: bridge && !fails)
                let new = ConnectingPeer(owner: "B", phase: .metadata)
                try await withFixture([old, new], bridge: bridge) { fixture in
                    let state = fixture.state
                    let first = fixture.connect()
                    try await waitForGate("opening", peer: old)
                    XCTAssertTrue(state.canCancelConnection)
                    let oldSelection = state.selectedBridgeCameraID
                    state.cancelConnection()
                    state.cancelConnection() // Repeated cancel cannot retire a newer owner.
                    XCTAssertFalse(state.isBusy(.connect))
                    XCTAssertFalse(state.canCancelConnection)
                    XCTAssertFalse(state.connected)
                    XCTAssertTrue(state.connectionWasCancelled)
                    XCTAssertEqual(state.password, "synthetic-old-password")
                    XCTAssertEqual(state.bridgeToken, "synthetic-old-token")
                    XCTAssertEqual(state.selectedBridgeCameraID, oldSelection)
                    XCTAssertFalse(state.pendingConnectionCleanup.isEmpty)
                    state.setBaseURL("http://new-camera.invalid:18080")
                    state.setUsername("synthetic-new-user")
                    state.password = "synthetic-new-password"
                    if bridge { await state.scanBridgeCameras() }
                    XCTAssertEqual(fixture.creations, 1, "Cancel, edit and scan never reconnect")

                    let second = fixture.connect()
                    try await waitUntil { state.connectionIsWaitingForCleanup }
                    await state.connect() // Double click must not admit another attempt.
                    XCTAssertEqual(fixture.creations, 1)
                    XCTAssertTrue(state.isBusy(.connect))
                    XCTAssertTrue(state.canCancelConnection)
                    await old.release("opening")
                    if bridge && !fails {
                        try await waitForGate("deletion", peer: old)
                        XCTAssertEqual(fixture.creations, 1, "New session cannot overlap old DELETE")
                        await old.release("deletion")
                    }
                    try await waitForGate("opening", peer: new)
                    state.reportCubeLutRenderFailure()
                    let newerError = state.lastError
                    await first.value
                    XCTAssertTrue(state.isBusy(.connect), "Old finally cannot unlock B")
                    XCTAssertTrue(state.canCancelConnection)
                    XCTAssertNil(state.connectionRecovery, "Old error cannot publish guidance for B")
                    XCTAssertEqual(state.lastError, newerError)
                    XCTAssertFalse(state.connected)
                    XCTAssertEqual(fixture.creations, 2)
                    await new.release("opening")
                    await second.value
                    XCTAssertTrue(state.connected)
                    XCTAssertEqual(state.info?.model, "Synthetic B")
                    XCTAssertFalse(state.isBusy(.connect))
                    XCTAssertFalse(state.canCancelConnection)
                    XCTAssertNil(state.connectionRecovery)
                    XCTAssertTrue(state.pendingConnectionCleanup.isEmpty)
                    XCTAssertTrue(state.previousConnectionCleanupWarnings.isEmpty)
                    let oldRequests = await old.requests()
                    let newRequests = await new.requests()
                    XCTAssertTrue(oldRequests.allSatisfy { $0.url?.host == (bridge ? "old-bridge.invalid" : "old-camera.invalid") })
                    XCTAssertTrue(newRequests.allSatisfy { $0.url?.host == (bridge ? "old-bridge.invalid" : "new-camera.invalid") })
                    XCTAssertFalse(newRequests.contains { $0.httpMethod == "DELETE" }, "Old cleanup cannot close B")
                    if bridge {
                        let deletes = oldRequests.filter { $0.httpMethod == "DELETE" }
                        XCTAssertEqual(deletes.map { $0.url!.path }, fails ? [] : ["/v1/session/A"])
                        XCTAssertTrue(deletes.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer synthetic-old-token" })
                        let cancelledDeletes = await old.cancelledDeletionCount()
                        XCTAssertEqual(cancelledDeletes, 0, "Cleanup must not inherit the cancelled opening task")
                    }
                }
            }
        }
    }

    func testCancellingQueuedRetryBeforeSessionCreationDoesNotStartItLater() async throws {
        let old = ConnectingPeer(owner: "A", phase: .creation)
        let new = ConnectingPeer(owner: "B", phase: .none)
        try await withFixture([old, new], bridge: true) { fixture in
            let state = fixture.state
            let first = fixture.connect()
            try await waitForGate("opening", peer: old)
            state.cancelConnection()
            let queued = fixture.connect()
            try await waitUntil { state.connectionIsWaitingForCleanup }
            state.cancelConnection()
            XCTAssertFalse(state.isBusy(.connect))
            XCTAssertTrue(state.connectionWasCancelled)
            state.password = "synthetic-edited-while-waiting"
            await old.release("opening")
            await first.value
            await queued.value
            XCTAssertEqual(fixture.creations, 1, "A cancelled explicit retry cannot wake up and allocate B")
            XCTAssertFalse(state.connected)
            XCTAssertNil(state.connectionRecovery)
            await fixture.connect().value
            XCTAssertEqual(fixture.creations, 2)
            XCTAssertEqual(state.info?.model, "Synthetic B")
        }
    }

    func testLateOpeningOrDeletionUncertaintyBlocksQueuedAndSubsequentRetries() async throws {
        for lostPOST in [false, true] {
            let old = ConnectingPeer(owner: "A", phase: .creation,
                                     outcome: lostPOST ? .lost : .success, failDeletion: !lostPOST)
            let unused = ConnectingPeer(owner: "B", phase: .none)
            try await withFixture([old, unused], bridge: true) { fixture in
                let state = fixture.state
                let first = fixture.connect()
                try await waitForGate("opening", peer: old)
                state.cancelConnection()
                let retry = fixture.connect()
                try await waitUntil { state.connectionIsWaitingForCleanup }
                state.reportCubeLutRenderFailure()
                let unrelatedError = state.lastError
                await old.release("opening")
                await first.value
                await retry.value
                XCTAssertFalse(state.connected)
                XCTAssertFalse(state.isBusy(.connect))
                XCTAssertFalse(state.canConnect)
                XCTAssertNil(state.connectionRecovery)
                XCTAssertEqual(state.lastError, unrelatedError)
                XCTAssertEqual(state.previousConnectionCleanupWarnings.count, 1)
                XCTAssertTrue(state.pendingConnectionCleanup.isEmpty)
                state.cancelConnection()
                state.setBridgeURL("http://new-bridge.invalid:18181")
                state.bridgeToken = "synthetic-new-token"
                await state.connect()
                XCTAssertEqual(fixture.creations, 1, "Editing or cancelling cannot dismiss an unknown resource owner")
                XCTAssertEqual(state.previousConnectionCleanupWarnings.count, 1)
                let requests = await old.requests()
                XCTAssertEqual(requests.filter { $0.httpMethod == "DELETE" }.count, lostPOST ? 0 : 1)
            }
        }
    }

    func testLateCreationAndDeletionSafetyWarningsRemainSpecificToRetiredClient() async throws {
        for code in ["SHUTTER_RELEASE_UNCONFIRMED", "LIVE_VIEW_STOP_UNCONFIRMED", "RECORDING_COMMAND_UNCONFIRMED"] {
            for lateDelete in [false, true] {
                let old = ConnectingPeer(owner: "A", phase: .creation,
                                         creationSafetyCode: lateDelete ? nil : code,
                                         deletionSafetyCode: lateDelete ? code : nil)
                let unused = ConnectingPeer(owner: "B", phase: .none)
                try await withFixture([old, unused], bridge: true) { fixture in
                    let state = fixture.state
                    let opening = fixture.connect()
                    try await waitForGate("opening", peer: old)
                    state.cancelConnection()
                    await old.release("opening")
                    await opening.value
                    XCTAssertEqual(state.previousShutterReleaseWarnings.count, code == "SHUTTER_RELEASE_UNCONFIRMED" ? 1 : 0)
                    XCTAssertEqual(state.previousLiveViewWarnings.count, code == "LIVE_VIEW_STOP_UNCONFIRMED" ? 1 : 0)
                    XCTAssertEqual(state.previousRecordingWarnings.count, code == "RECORDING_COMMAND_UNCONFIRMED" ? 1 : 0)
                    XCTAssertEqual(state.previousConnectionCleanupWarnings.count, 1)
                    XCTAssertFalse(state.shutterReleaseRequired)
                    XCTAssertFalse(state.recordingStopRequired)
                    XCTAssertFalse(state.canScanBridgeCameras)
                    let scans = await fixture.discovery.count()
                    await state.scanBridgeCameras()
                    let after = await fixture.discovery.count()
                    XCTAssertEqual(after, scans, "Unknown cleanup must not reach Bridge discovery")
                    state.confirmPreviousShutterReleased()
                    state.confirmPreviousLiveViewStopped()
                    state.confirmPreviousRecordingStopped()
                    XCTAssertFalse(state.canConnect, "Acknowledging a warning is not session cleanup evidence")
                    XCTAssertFalse(state.canScanBridgeCameras)
                    await state.connect()
                    XCTAssertEqual(fixture.creations, 1)
                    let requests = await old.requests()
                    XCTAssertEqual(requests.map { $0.url!.path }, lateDelete
                        ? ["/health", "/v1/session", "/v1/session/A"] : ["/health", "/v1/session"])
                }
            }
        }
    }

    func testScanWaitsForRetirementWhileSettingsRemainEditableThenRequiresExplicitAction() async throws {
        let old = ConnectingPeer(owner: "A", phase: .creation, holdDeletion: true)
        let new = ConnectingPeer(owner: "B", phase: .none)
        try await withFixture([old, new], bridge: true) { fixture in
            let state = fixture.state
            let opening = fixture.connect()
            try await waitForGate("opening", peer: old)
            state.cancelConnection()
            state.setBridgeURL("http://new-bridge.invalid:18181")
            state.bridgeToken = "synthetic-new-token"
            XCTAssertEqual(state.bridgeURL, "http://new-bridge.invalid:18181")
            XCTAssertEqual(state.bridgeToken, "synthetic-new-token")
            XCTAssertFalse(state.canScanBridgeCameras)
            let initialScans = await fixture.discovery.count()
            await state.scanBridgeCameras()
            var scans = await fixture.discovery.count()
            XCTAssertEqual(scans, initialScans, "Pending POST must not overlap discovery")
            await old.release("opening")
            try await waitForGate("deletion", peer: old)
            await state.scanBridgeCameras()
            scans = await fixture.discovery.count()
            XCTAssertEqual(scans, initialScans, "Pending DELETE must not overlap discovery")
            await old.release("deletion")
            await opening.value
            XCTAssertTrue(state.canScanBridgeCameras)
            scans = await fixture.discovery.count()
            XCTAssertEqual(scans, initialScans, "Cleanup completion cannot automatically scan")
            XCTAssertEqual(fixture.creations, 1, "Editing and cleanup completion cannot reconnect")
            await state.scanBridgeCameras()
            scans = await fixture.discovery.count()
            XCTAssertEqual(scans, initialScans + 1)
            XCTAssertTrue(state.canConnect)
            await fixture.connect().value
            XCTAssertEqual(state.info?.model, "Synthetic B")
            let oldRequests = await old.requests()
            let newRequests = await new.requests()
            XCTAssertTrue(oldRequests.allSatisfy { $0.url?.host == "old-bridge.invalid" })
            XCTAssertTrue(newRequests.allSatisfy { $0.url?.host == "new-bridge.invalid" })
            XCTAssertEqual(oldRequests.last?.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-old-token")
            XCTAssertTrue(newRequests.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer synthetic-new-token" })
            XCTAssertFalse(newRequests.contains { $0.httpMethod == "DELETE" })
        }
    }

    func testCancelBeforeBridgePOSTSendsNoAllocationOrCleanupAndNoReconnect() async throws {
        for outcome in [ConnectingPeer.Outcome.success, .lost] {
            let old = ConnectingPeer(owner: "A", phase: .service, outcome: outcome)
            try await withFixture([old], bridge: true) { fixture in
                let pending = fixture.connect()
                try await waitForGate("opening", peer: old)
                fixture.state.cancelConnection()
                await old.release("opening")
                await pending.value
                XCTAssertFalse(fixture.state.connected)
                XCTAssertFalse(fixture.state.isBusy(.connect))
                XCTAssertNil(fixture.state.connectionRecovery)
                XCTAssertTrue(fixture.state.previousConnectionCleanupWarnings.isEmpty)
                XCTAssertEqual(fixture.creations, 1)
                let requests = await old.requests()
                XCTAssertEqual(requests.map { $0.url!.path }, ["/health"])
            }
        }
    }

    func testCancelDuringMetadataClosesAllocatedBridgeSessionBeforeExplicitRetry() async throws {
        for outcome in [ConnectingPeer.Outcome.success, .lost] {
            let old = ConnectingPeer(owner: "A", phase: .metadata, outcome: outcome, holdDeletion: true)
            let new = ConnectingPeer(owner: "B", phase: .none)
            try await withFixture([old, new], bridge: true) { fixture in
                let first = fixture.connect()
                try await waitForGate("opening", peer: old)
                fixture.state.cancelConnection()
                let retry = fixture.connect()
                try await waitForGate("deletion", peer: old)
                await old.release("deletion")
                XCTAssertEqual(fixture.creations, 1, "Completed close alone must not forget pending metadata")
                await old.release("opening")
                await first.value
                await retry.value
                XCTAssertEqual(fixture.state.info?.model, "Synthetic B")
                XCTAssertNil(fixture.state.connectionRecovery)
                let oldRequests = await old.requests()
                XCTAssertEqual(oldRequests.filter { $0.httpMethod == "DELETE" }.map { $0.url!.path }, ["/v1/session/A"])
                XCTAssertFalse(oldRequests.contains { $0.url?.path == "/v1/session/A/status" })
                XCTAssertFalse(oldRequests.contains { $0.url?.path == "/v1/session/A/capabilities" })
            }
        }
    }

    func testOfflinePreviewAndBackgroundNeverPublishLateConnectionOrAutomaticallyResume() async throws {
        for bridge in [false, true] {
            for preview in [false, true] {
                let old = ConnectingPeer(owner: "A", phase: bridge ? .creation : .metadata)
                try await withFixture([old], bridge: bridge) { fixture in
                    let pending = fixture.connect()
                    try await waitForGate("opening", peer: old)
                    if preview { fixture.state.openOfflinePreview() }
                    else { fixture.state.setApplicationActive(false) }
                    let snapshot = fixture.state.snapshot
                    await old.release("opening")
                    await pending.value
                    fixture.state.setApplicationActive(true)
                    XCTAssertEqual(fixture.state.snapshot, snapshot)
                    XCTAssertEqual(fixture.state.isPreview, preview)
                    XCTAssertEqual(fixture.creations, 1)
                    XCTAssertFalse(fixture.state.isBusy(.connect))
                    XCTAssertNil(fixture.state.connectionRecovery)
                }
            }
        }
    }

    func testCurrentFailureAfterOldRetirementKeepsItsOwnRecovery() async throws {
        let old = ConnectingPeer(owner: "A", phase: .creation, outcome: .rejected)
        let new = ConnectingPeer(owner: "B", phase: .metadata, outcome: .rejected)
        try await withFixture([old, new], bridge: true) { fixture in
            let first = fixture.connect()
            try await waitForGate("opening", peer: old)
            fixture.state.cancelConnection()
            let retry = fixture.connect()
            try await waitUntil { fixture.state.connectionIsWaitingForCleanup }
            await old.release("opening")
            try await waitForGate("opening", peer: new)
            await new.release("opening")
            await retry.value
            await first.value
            XCTAssertEqual(fixture.state.connectionRecovery?.reason, .authenticationRejected)
            XCTAssertFalse(fixture.state.connected)
            XCTAssertFalse(fixture.state.isBusy(.connect))
            XCTAssertTrue(fixture.state.previousConnectionCleanupWarnings.isEmpty)
            XCTAssertEqual(fixture.creations, 2)
        }
    }

    func testCancellationKeysAreLocalizedInBothLanguages() throws {
        for language in ["en", "zh-Hant"] {
            let path = try XCTUnwrap(Bundle(for: CameraAppState.self).path(forResource: language, ofType: "lproj"))
            let bundle = try XCTUnwrap(Bundle(path: path))
            for key in ["connection_cancel", "connection_waiting_cleanup", "connection_cancelled_help",
                        "connection_cleanup_pending", "connection_cleanup_unconfirmed"] {
                XCTAssertNotEqual(bundle.localizedString(forKey: key, value: nil, table: nil), key)
            }
        }
    }

    private func waitForGate(_ gate: String, peer: ConnectingPeer) async throws {
        try await waitUntil { await peer.waiting(gate) }
    }

    private func waitUntil(_ condition: () async -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        repeat {
            if await condition() { return }
            await Task.yield()
        } while ContinuousClock.now < deadline
        XCTFail("Controlled connection did not reach its expected gate")
        throw URLError(.timedOut)
    }

    private func withFixture(_ peers: [ConnectingPeer], bridge: Bool,
                             body: (ConnectingFixture) async throws -> Void) async throws {
        let fixture = ConnectingFixture(peers: peers, bridge: bridge)
        if bridge { await fixture.state.scanBridgeCameras() }
        do { try await body(fixture) }
        catch { await fixture.cleanup(); throw error }
        await fixture.cleanup()
    }
}

@MainActor
private final class ConnectingFixture {
    private(set) var state: CameraAppState!
    private(set) var creations = 0
    let discovery = ConnectingDiscoveryCounter()
    private let peers: [ConnectingPeer]
    private var connections: [Task<Void, Never>] = []
    private let suite = "ConnectionCancellationTests.\(UUID().uuidString)"
    private let defaults: UserDefaults

    init(peers: [ConnectingPeer], bridge: Bool) {
        self.peers = peers
        defaults = UserDefaults(suiteName: suite)!
        state = CameraAppState(defaults: defaults, sessionFactory: { [unowned self] in
            guard creations < peers.count else { throw URLError(.badServerResponse) }
            let peer = peers[creations]
            creations += 1
            if bridge {
                return .desktopBridge(try DesktopBridgeClient(baseURL: state.bridgeURL, token: state.bridgeToken,
                                                              cameraID: state.selectedBridgeCameraID, transport: peer))
            }
            return .ccapi(try CCAPIClient(baseURL: state.baseURL, mode: .simulator,
                                         username: state.username, password: state.password, transport: peer))
        }, bridgeCameraDiscovery: { [discovery] _, _ in
            await discovery.record()
            return [DesktopBridgeCamera(id: "synthetic-camera", model: "Synthetic EOS", port: "synthetic:usb", engine: "fixture")]
        })
        state.connectionMode = bridge ? .desktopBridge : .ccapi
        state.baseURL = "http://old-camera.invalid:18080"
        state.bridgeURL = "http://old-bridge.invalid:18181"
        state.username = "synthetic-old-user"
        state.password = "synthetic-old-password"
        state.bridgeToken = "synthetic-old-token"
        state.autoRefresh = false
    }

    func connect() -> Task<Void, Never> {
        let task = Task { await state.connect() }
        connections.append(task)
        return task
    }

    func cleanup() async {
        connections.forEach { $0.cancel() }
        state.cancelConnection()
        for peer in peers { await peer.releaseAll() }
        for connection in connections { await connection.value }
        for cleanup in Array(state.connectionCleanupTasks.values) { await cleanup.value }
        await state.disconnect()
        defaults.removePersistentDomain(forName: suite)
    }
}

private actor ConnectingDiscoveryCounter {
    private var calls = 0
    func record() { calls += 1 }
    func count() -> Int { calls }
}

private actor ConnectingPeer: CameraHTTPTransport {
    enum Phase { case none, service, creation, metadata }
    enum Outcome { case success, rejected, lost }
    private let owner: String
    private let phase: Phase
    private let outcome: Outcome
    private let holdDeletion: Bool
    private let failDeletion: Bool
    private let creationSafetyCode: String?
    private let deletionSafetyCode: String?
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var recorded: [URLRequest] = []
    private var cancelledDeletes = 0
    private var drained = false

    init(owner: String, phase: Phase, outcome: Outcome = .success,
         holdDeletion: Bool = false, failDeletion: Bool = false,
         creationSafetyCode: String? = nil, deletionSafetyCode: String? = nil) {
        self.owner = owner; self.phase = phase; self.outcome = outcome
        self.holdDeletion = holdDeletion; self.failDeletion = failDeletion
        self.creationSafetyCode = creationSafetyCode; self.deletionSafetyCode = deletionSafetyCode
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        recorded.append(request)
        let method = request.httpMethod ?? "GET"
        let path = request.url!.path
        let direct = path.hasPrefix("/ccapi/")
        let prefix = direct ? "/ccapi" : "/v1/session/\(owner)"
        let isOpening = (phase == .service && path == "/health")
            || (phase == .creation && path == "/v1/session" && method == "POST")
            || (phase == .metadata && path == prefix + "/info")
        if isOpening {
            if !drained { await withCheckedContinuation { gates["opening"] = $0 } }
            if outcome == .lost { throw URLError(.networkConnectionLost) }
            if outcome == .rejected {
                return CameraHTTPResponse(statusCode: 401, body: Data(#"{"error":{"code":"AUTHENTICATION_REQUIRED","message":"synthetic rejection"}}"#.utf8))
            }
        }
        if method == "POST", path == "/v1/session" {
            try Task.checkCancellation()
            if let code = creationSafetyCode { return try safetyReply(code) }
        }
        if method == "DELETE", path == prefix {
            if Task.isCancelled { cancelledDeletes += 1 }
            try Task.checkCancellation() // A cancelled cleanup cannot reach the fixture's delete ACK.
            if holdDeletion && !drained { await withCheckedContinuation { gates["deletion"] = $0 } }
            if let code = deletionSafetyCode { return try safetyReply(code) }
            return CameraHTTPResponse(statusCode: failDeletion ? 503 : 204)
        }
        let object: [String: Any]
        switch path {
        case "/health": object = ["ok": true, "service": "open-eos-control-bridge"]
        case "/v1/session": object = ["id": owner, "engine": "ccapi"]
        case prefix + "/info": object = ["connected": true, "model": "Synthetic \(owner)", "serial": "SYNTHETIC", "api": "fixture"]
        case prefix + "/status": object = ["connected": true, "recording": false,
            "battery": ["level": 70], "media": ["available": true],
            "exposure": ["iso": "100", "shutter": "1/100", "aperture": "4.0"]]
        case prefix + "/capabilities": object = direct ? [:] : ["supported": []]
        case prefix + "/media": object = ["items": []]
        case prefix + "/events": throw CancellationError()
        case prefix + "/liveview/stop": object = [:]
        default: throw URLError(.unsupportedURL)
        }
        return CameraHTTPResponse(statusCode: 200, body: try JSONSerialization.data(withJSONObject: object))
    }

    private func safetyReply(_ code: String) throws -> CameraHTTPResponse {
        CameraHTTPResponse(statusCode: 409, body: try JSONSerialization.data(withJSONObject: ["error": ["code": code]]))
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    func waiting(_ gate: String) -> Bool { gates[gate] != nil }
    func release(_ gate: String) { gates.removeValue(forKey: gate)?.resume() }
    func releaseAll() {
        drained = true
        let waiting = gates; gates.removeAll()
        waiting.values.forEach { $0.resume() }
    }
    func requests() -> [URLRequest] { recorded }
    func cancelledDeletionCount() -> Int { cancelledDeletes }
}
