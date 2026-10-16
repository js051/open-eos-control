import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OpenEOSCore

/// Authored dependency-level tests; native execution requires Swift/Xcode.
final class MaintenanceControlTests: XCTestCase {
    func testStopAndRestoreUseOnlyDedicatedBoundedControlAndFrozenConfiguration() async throws {
        for direct in [false, true] {
            let peer = MaintenanceControlPeer(direct: direct)
            let client = try await makeClient(peer, direct: direct)
            try await client.start()
            let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
            try await client.perform(attempt)
            try await client.observe(attempt); attempt.restore(.pending)
            try await client.restore(attempt)
            let controls = await peer.controls()
            XCTAssertEqual(controls.map(\.action), ["stop", "start"])
            XCTAssertTrue(controls.allSatisfy { $0.strict && $0.timeout == 15 })
            let start = try XCTUnwrap(controls.last)
            let payload = try JSONSerialization.jsonObject(with: start.body!) as! [String: Any]
            if direct { XCTAssertEqual(payload["liveviewsize"] as? String, "small") }
            else {
                XCTAssertEqual(payload["source"] as? String, "CCAPI_JPEG_POLLING")
                XCTAssertEqual(payload["size"] as? String, "SMALL")
                XCTAssertEqual(payload["fps"] as? Int, 4)
                XCTAssertTrue(start.path.contains("/maintenance/control/liveview/start"))
            }
            let state = await client.stopState()
            XCTAssertTrue(state.stopRequired); XCTAssertFalse(state.stopUnconfirmed)
            if direct {
                let reads = await peer.frameReads()
                XCTAssertEqual(reads.count, 2, "Ordinary Start and maintenance restore each validate one first JPEG")
                let initialRead = try XCTUnwrap(reads.first)
                let restoredRead = try XCTUnwrap(reads.dropFirst().first)
                XCTAssertFalse(initialRead.strict)
                XCTAssertFalse(initialRead.dedicatedFrameRead)
                XCTAssertTrue(restoredRead.strict)
                XCTAssertTrue(restoredRead.dedicatedFrameRead, "Restore JPEG must use the bounded strict frame transport")
                XCTAssertEqual(reads.map(\.query), ["t=0", "t=0"])
                XCTAssertTrue(reads.allSatisfy { $0.method == "GET" && $0.timeout == 5 })
                let beforeRetainedRead = await peer.requests()
                if case let .direct(directClient) = client {
                    let frame = try await directClient.liveViewFrame(cacheKey: 88)
                    XCTAssertEqual(frame.data, CCAPILiveViewTestFixture.jpeg)
                }
                let afterRetainedRead = await peer.requests()
                XCTAssertEqual(afterRetainedRead, beforeRetainedRead, "Restore retains the first JPEG without a duplicate ordinary GET")
            }
        }
    }

    func testDirectRestoreFirstFrameFailureHasOneStrictAttemptAndNoEndpointSizeOrSourceFallback() async throws {
        let failures: [(Int, Data)] = [
            (307, Data()), (308, Data()), (401, Data()),
            (503, Data(#"{"message":"Device busy"}"#.utf8)),
            (503, Data(#"{"message":"Mode not supported"}"#.utf8)),
            (200, Data("not a JPEG".utf8)),
        ]
        for (status, body) in failures {
            let peer = MaintenanceControlPeer(direct: true, multipleFrameEndpoints: true)
            let client = try await makeClient(peer, direct: true)
            try await client.start()
            let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
            try await client.perform(attempt); try await client.observe(attempt); attempt.restore(.pending)
            await peer.failFrame(status: status, body: body)
            let beforeReads = await peer.frameReads()
            do { try await client.restore(attempt); XCTFail("First-frame failure cannot publish a restored source") } catch {}
            let reads = await peer.frameReads(), controls = await peer.controls(), state = await client.stopState()
            let restoreReads = Array(reads.dropFirst(beforeReads.count))
            XCTAssertEqual(restoreReads.count, 1, "Strict maintenance must not retry busy, switch endpoint, downgrade size, or change source")
            XCTAssertTrue(restoreReads.allSatisfy { $0.strict && $0.dedicatedFrameRead && $0.method == "GET" && $0.query == "t=0" && $0.timeout == 5 })
            XCTAssertEqual(controls.map(\.action), ["stop", "start"], "Failed restore must not automatically compensate or restart")
            XCTAssertTrue(controls.allSatisfy { $0.strict && $0.timeout == 15 })
            XCTAssertTrue(state.stopRequired); XCTAssertTrue(state.stopUnconfirmed)
            XCTAssertEqual(attempt.snapshot.commandEvidence, .responded)
            if case let .direct(directClient) = client {
                let source = await directClient.currentLiveViewSource()
                XCTAssertNil(source)
            }
            try await client.retryStop()
            let recovered = await peer.controls(), afterRetryReads = await peer.frameReads()
            XCTAssertEqual(recovered.map(\.action), ["stop", "start", "stop"])
            XCTAssertTrue(recovered.allSatisfy(\.strict))
            XCTAssertEqual(afterRetryReads.count, reads.count, "Explicit Stop recovery must not refetch a frame")
        }
    }

    func testCancelDuringStopPropagatesAndLateResponseCannotDischargeObligation() async throws {
        for direct in [false, true] {
            let peer = MaintenanceControlPeer(direct: direct), client = try await makeClient(peer, direct: direct)
            try await client.start(); await peer.hold("stop")
            let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
            let task = Task { try await client.perform(attempt) }
            try await wait { await peer.waiting }
            attempt.cancel(); task.cancel(); await peer.release()
            do { try await task.value; XCTFail("Cancelled Stop cannot admit maintenance") } catch {}
            let state = await client.stopState(), controls = await peer.controls()
            XCTAssertTrue(state.stopRequired); XCTAssertTrue(state.stopUnconfirmed)
            XCTAssertEqual(attempt.snapshot.stopEvidence, .unknown)
            XCTAssertEqual(attempt.snapshot.commandEvidence, .notSent)
            XCTAssertEqual(controls.map(\.action), ["stop"])
            XCTAssertTrue(controls.last?.cancelledAfterGate == true, "No detached Stop swallowing caller cancellation")
            try await client.retryStop()
            let retried = await peer.controls()
            XCTAssertEqual(retried.map(\.action), ["stop", "stop"])
            XCTAssertTrue(retried.allSatisfy(\.strict), "Explicit recovery retains the maintenance transport")
        }
    }

    func testCancelDuringRestoreStartLeavesUnknownStopWithoutAutomaticCompensation() async throws {
        for direct in [false, true] {
            let peer = MaintenanceControlPeer(direct: direct), client = try await makeClient(peer, direct: direct)
            try await client.start()
            let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
            try await client.perform(attempt); try await client.observe(attempt); attempt.restore(.pending)
            await peer.hold("start")
            let task = Task { try await client.restore(attempt) }
            try await wait { await peer.waiting }
            attempt.cancel(); task.cancel(); await peer.release()
            do { try await task.value; XCTFail("Cancelled Start cannot publish") } catch {}
            let state = await client.stopState(), controls = await peer.controls()
            XCTAssertTrue(state.stopRequired); XCTAssertTrue(state.stopUnconfirmed)
            XCTAssertEqual(controls.map(\.action), ["stop", "start"])
            XCTAssertTrue(controls.last?.cancelledAfterGate == true)
            XCTAssertEqual(attempt.snapshot.commandEvidence, .responded)
        }
    }

    func testRedirectAuthenticationAndFailureNeverReplayStopOrStart() async throws {
        for direct in [false, true] {
            for action in ["stop", "start"] {
                for status in [307, 308, 401, 503] {
                    let peer = MaintenanceControlPeer(direct: direct), client = try await makeClient(peer, direct: direct)
                    try await client.start()
                    let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
                    if action == "start" {
                        try await client.perform(attempt); try await client.observe(attempt); attempt.restore(.pending)
                    }
                    await peer.fail(action, status: status)
                    do {
                        if action == "stop" { try await client.perform(attempt) }
                        else { try await client.restore(attempt) }
                        XCTFail("Non-success dependency is not proof")
                    } catch {}
                    let controls = await peer.controls(), state = await client.stopState()
                    XCTAssertEqual(controls.filter { $0.action == action }.count, 1)
                    XCTAssertTrue(controls.allSatisfy(\.strict))
                    XCTAssertTrue(state.stopUnconfirmed)
                    XCTAssertEqual(attempt.snapshot.commandEvidence, action == "stop" ? .notSent : .responded)
                }
            }
        }
    }

    func testLateStopAndStartCannotClearNewerSameSessionWarning() async throws {
        for action in ["stop", "start"] {
            let peer = MaintenanceControlPeer(direct: false), client = try await makeClient(peer, direct: false)
            try await client.start()
            let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
            if action == "start" {
                try await client.perform(attempt); try await client.observe(attempt); attempt.restore(.pending)
            }
            await peer.hold(action)
            let task = Task {
                if action == "stop" { try await client.perform(attempt) }
                else { try await client.restore(attempt) }
            }
            try await wait { await peer.waiting }
            await peer.reportWarning()
            if case let .bridge(bridge) = client { _ = try await bridge.status() }
            await peer.release()
            do { try await task.value; XCTFail("Newer warning must supersede late control proof") } catch {}
            let state = await client.stopState()
            XCTAssertTrue(state.stopRequired); XCTAssertTrue(state.stopUnconfirmed)
        }
    }

    func testAttemptCannotRestoreOrObserveThroughDifferentClientOwner() async throws {
        let first = try await makeClient(MaintenanceControlPeer(direct: false), direct: false)
        let otherPeer = MaintenanceControlPeer(direct: false), other = try await makeClient(otherPeer, direct: false)
        try await first.start()
        let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
        try await first.perform(attempt); try await first.observe(attempt); attempt.restore(.pending)
        do { try await other.restore(attempt); XCTFail("Restore owner is frozen") } catch {}
        do { try await other.observe(attempt); XCTFail("Read owner is frozen") } catch {}
        let controls = await otherPeer.controls()
        XCTAssertTrue(controls.isEmpty)
    }

    func testDirectSleepUsesOneFrozenStrictCapabilityReadAndNoOptionalDiscovery() async throws {
        for revoked in [false, true] {
            let peer = MaintenanceControlPeer(direct: true)
            let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: peer)
            try await client.initialize()
            _ = try await client.capabilities() // Explicit connection capability load, outside maintenance.
            if revoked { await peer.revokeSleepAbility() }
            let before = await peer.requests()
            let attempt = CameraMaintenanceAttempt(operation: .sleep)
            do { try await client.performMaintenance(attempt); XCTAssertFalse(revoked) }
            catch { XCTAssertTrue(revoked) }
            let after = await peer.requests(), suffix = Array(after.dropFirst(before.count))
            XCTAssertEqual(suffix, revoked
                ? ["strict GET /ccapi/ver100/functions/autopoweroff"]
                : ["strict GET /ccapi/ver100/functions/autopoweroff", "strict PUT /ccapi/ver100/functions/autopoweroff"])
            XCTAssertEqual(attempt.snapshot.commandEvidence, revoked ? .notSent : .responded)
        }
    }

    func testUninitializedMaintenanceDoesNotDiscoverOrSendAnything() async throws {
        let peer = MaintenanceControlPeer(direct: true)
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: peer)
        let attempt = CameraMaintenanceAttempt(operation: .sleep)
        do { try await client.performMaintenance(attempt); XCTFail("Connection must be explicit") } catch {}
        let requests = await peer.requests()
        XCTAssertTrue(requests.isEmpty); XCTAssertEqual(attempt.snapshot.commandEvidence, .notSent)
    }

    func testNewRecordingDuringStopBlocksMainDispatchWithoutAdditionalStatusRead() async throws {
        for direct in [false, true] {
            let peer = MaintenanceControlPeer(direct: direct), client = try await makeClient(peer, direct: direct)
            try await client.start(); await peer.hold("stop")
            let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
            let task = Task { try await client.perform(attempt) }
            try await wait { await peer.waiting }
            if case let .bridge(bridge) = client {
                await peer.reportRecording(); _ = try await bridge.status()
            } else { attempt.revokeMutationSafety() } // Same revocation used by App state observers.
            await peer.release()
            do { try await task.value; XCTFail("New REC evidence must block the command") } catch {}
            XCTAssertEqual(attempt.snapshot.commandEvidence, .notSent)
            let requests = await peer.requests()
            XCTAssertFalse(requests.contains { $0.hasSuffix("/outcome") || $0.hasSuffix("/functions/sensorcleaning") })
        }
    }

    func testRevokedRestoreSafetyCannotSendStart() async throws {
        for direct in [false, true] {
            let peer = MaintenanceControlPeer(direct: direct), client = try await makeClient(peer, direct: direct)
            try await client.start()
            let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
            try await client.perform(attempt); try await client.observe(attempt); attempt.restore(.pending)
            attempt.revokeMutationSafety()
            do { try await client.restore(attempt); XCTFail("REC/Bulb safety revocation cannot Start") } catch {}
            let controls = await peer.controls()
            XCTAssertEqual(controls.map(\.action), ["stop"])
        }
    }

    func testMaintenanceCloseKeepsStrictStopAndNeverFallsBackToGenericDelete() async throws {
        for direct in [false, true] {
            for stopFails in [false, true] {
                let peer = MaintenanceControlPeer(direct: direct), client = try await makeClient(peer, direct: direct)
                try await client.start()
                let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
                try await client.perform(attempt); try await client.observe(attempt); attempt.restore(.pending)
                await peer.fail("start", status: 503)
                do { try await client.restore(attempt) } catch {}
                if stopFails { await peer.fail("stop", status: 503) }
                let before = await peer.requests()
                await client.close()
                let controls = await peer.controls(), state = await client.stopState()
                XCTAssertEqual(controls.map(\.action), ["stop", "start", "stop"])
                XCTAssertTrue(controls.allSatisfy { $0.strict && $0.timeout == 15 })
                XCTAssertEqual(state.stopUnconfirmed, stopFails)
                XCTAssertFalse(state.stopRequired)
                let after = await peer.requests(), closing = Array(after.dropFirst(before.count))
                XCTAssertTrue(closing.allSatisfy { $0.hasPrefix("strict ") })
                XCTAssertFalse(closing.contains("ordinary DELETE /v1/session/synthetic"))
                if !direct { XCTAssertEqual(closing.last, "strict DELETE /v1/session/synthetic/maintenance/control/session") }
            }
        }
    }

    func testLocalRetirementFailureDoesNotBecomeStopProofOrTriggerFallback() async throws {
        let peer = MaintenanceControlPeer(direct: false)
        let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: peer)
        try await client.initialize(); try await client.startLiveView(.init(source: .ccapiJPEGPolling))
        let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
        try await client.performMaintenance(attempt)
        await peer.failRetirement(); await client.close()
        let retirement = await client.maintenanceRetirementUnconfirmed, state = await client.liveViewStopState()
        XCTAssertTrue(retirement); XCTAssertFalse(state.stopUnconfirmed)
        let trace = await peer.requests()
        XCTAssertEqual(trace.filter { $0.hasSuffix("/maintenance/control/session") }.count, 1)
        XCTAssertFalse(trace.contains("ordinary DELETE /v1/session/synthetic"))
    }

    func testCloseAfterRestoreStillReleasesFrozenBulbAndHeldAutofocusOwners() async throws {
        for direct in [false, true] {
            for autofocus in [false, true] {
                for releaseFails in [false, true] {
                    let peer = MaintenanceControlPeer(direct: direct), client = try await makeClient(peer, direct: direct)
                    try await client.start()
                    let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
                    try await client.perform(attempt); try await client.observe(attempt); attempt.restore(.pending)
                    try await client.restore(attempt)
                    try await client.acquireRelease(autofocus: autofocus)
                    if releaseFails { await peer.failRelease() }
                    let before = await peer.requests()
                    let release = await client.closeState()
                    let trace = await peer.requests(), closing = Array(trace.dropFirst(before.count))
                    let expected = direct
                        ? "strict " + (autofocus ? "POST /ccapi/ver100/shooting/control/af" : "PUT /ccapi/ver100/shooting/control/shutterbutton/manual")
                        : "strict POST /v1/session/synthetic/" + (autofocus ? "focus/auto/stop" : "bulb/stop")
                    XCTAssertEqual(closing.first, expected, "Release must precede LV Stop/local retirement")
                    XCTAssertTrue(closing.allSatisfy { $0.hasPrefix("strict ") })
                    XCTAssertEqual(closing.filter { $0 == expected }.count, 1)
                    XCTAssertEqual(release.releaseUnconfirmed, releaseFails)
                    let controls = await peer.controls(), stopState = await client.stopState()
                    XCTAssertEqual(controls.map(\.action), releaseFails ? ["stop", "start"] : ["stop", "start", "stop"])
                    XCTAssertEqual(stopState.stopUnconfirmed, releaseFails, "Failed physical release must retain LV Stop warning")
                    XCTAssertFalse(release.releaseRequired, "A retired owner keeps a warning, not a replay action")
                    if case let .bridge(bridge) = client {
                        let retirement = await bridge.maintenanceRetirementUnconfirmed
                        XCTAssertEqual(retirement, releaseFails, "Server refuses local retirement with outstanding release")
                        XCTAssertFalse(closing.contains("ordinary DELETE /v1/session/synthetic"))
                    }
                }
            }
        }
    }

    func testRestoreRequiresExplicitFalseStopWarningFromStrictBridgeRoute() async throws {
        let peer = MaintenanceControlPeer(direct: false), client = try await makeClient(peer, direct: false)
        try await client.start()
        let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
        try await client.perform(attempt); try await client.observe(attempt); attempt.restore(.pending)
        await peer.omitStartProof()
        do { try await client.restore(attempt); XCTFail("Missing Stop proof cannot publish restored controls") } catch {}
        let state = await client.stopState(), controls = await peer.controls()
        XCTAssertTrue(state.stopRequired); XCTAssertTrue(state.stopUnconfirmed)
        XCTAssertEqual(controls.map(\.action), ["stop", "start"])
    }

    private func makeClient(_ peer: MaintenanceControlPeer, direct: Bool) async throws -> MaintenanceControlClient {
        if direct {
            let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: peer)
            try await client.initialize(); return .direct(client)
        }
        let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: peer)
        try await client.initialize(); return .bridge(client)
    }
    private func wait(_ predicate: () async -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(3))
        repeat { if await predicate() { return }; await Task.yield() } while ContinuousClock.now < deadline
        XCTFail("Dependency did not reach gate"); throw URLError(.timedOut)
    }
}

private enum MaintenanceControlClient: Sendable {
    case direct(CCAPIClient), bridge(DesktopBridgeClient)
    func start() async throws {
        let request = LiveViewRequest(fps: 4, size: .small, source: .ccapiJPEGPolling)
        switch self { case let .direct(c): try await c.startLiveView(request); case let .bridge(c): try await c.startLiveView(request) }
    }
    func perform(_ a: CameraMaintenanceAttempt) async throws {
        switch self { case let .direct(c): try await c.performMaintenance(a); case let .bridge(c): try await c.performMaintenance(a) }
    }
    func observe(_ a: CameraMaintenanceAttempt) async throws {
        switch self { case let .direct(c): try await c.observeMaintenance(a); case let .bridge(c): try await c.observeMaintenance(a) }
    }
    func restore(_ a: CameraMaintenanceAttempt) async throws {
        switch self { case let .direct(c): try await c.restoreMaintenanceLiveView(a); case let .bridge(c): try await c.restoreMaintenanceLiveView(a) }
    }
    func retryStop() async throws {
        switch self { case let .direct(c): try await c.retryLiveViewStop(); case let .bridge(c): try await c.retryLiveViewStop() }
    }
    func acquireRelease(autofocus: Bool) async throws {
        switch self {
        case let .direct(c):
            if autofocus { try await c.startHeldAutofocus(owner: UUID()) }
            else { _ = try await c.startBulbExposure() }
        case let .bridge(c):
            _ = try await c.capabilities()
            if autofocus { try await c.startHeldAutofocus(owner: UUID()) }
            else { _ = try await c.startBulbExposure() }
        }
    }
    func closeState() async -> CameraShutterReleaseState {
        switch self { case let .direct(c): await c.closeWithShutterReleaseState(); case let .bridge(c): await c.closeWithShutterReleaseState() }
    }
    func close() async {
        switch self { case let .direct(c): await c.close(); case let .bridge(c): await c.close() }
    }
    func stopState() async -> CameraLiveViewStopState {
        switch self { case let .direct(c): await c.liveViewStopState(); case let .bridge(c): await c.liveViewStopState() }
    }
}

private actor MaintenanceControlPeer: CameraHTTPTransport {
    struct Control: Sendable {
        let action: String, path: String
        let strict: Bool, timeout: TimeInterval, body: Data?
        var cancelledAfterGate = false
    }
    struct FrameRead: Sendable {
        let method: String, path: String, query: String?
        let strict: Bool, dedicatedFrameRead: Bool, timeout: TimeInterval
    }
    let direct: Bool
    private let multipleFrameEndpoints: Bool
    private var frameLog: [FrameRead] = []
    private var frameFailure: (Int, Data)?
    private var held: String?, gate: CheckedContinuation<Void, Never>?
    private var failure: (String, Int)?
    private var warning = false
    private var recording = false
    private var retirementFails = false
    private var releaseFails = false
    private var releaseOutstanding = false
    private var trace: [String] = []
    private var sleepAbility = true
    private var omitStartStopProof = false
    private var log: [Control] = []
    var waiting: Bool { gate != nil }
    init(direct: Bool, multipleFrameEndpoints: Bool = false) {
        self.direct = direct; self.multipleFrameEndpoints = multipleFrameEndpoints
    }
    func frameReads() -> [FrameRead] { frameLog }
    func failFrame(status: Int, body: Data) { frameFailure = (status, body) }
    func hold(_ action: String) { held = action }
    func fail(_ action: String, status: Int) { failure = (action, status) }
    func reportWarning() { warning = true }
    func reportRecording() { recording = true }
    func failRetirement() { retirementFails = true }
    func failRelease() { releaseFails = true }
    func omitStartProof() { omitStartStopProof = true }
    func revokeSleepAbility() { sleepAbility = false }
    func requests() -> [String] { trace }
    func release() { gate?.resume(); gate = nil }
    func controls() -> [Control] { log }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse { try await route(request, strict: false) }
    func sendMaintenance(_ request: URLRequest) async throws -> CameraHTTPResponse { try await route(request, strict: true) }
    func sendMaintenanceLiveViewFrame(_ request: URLRequest) async throws -> CameraHTTPResponse {
        try await route(request, strict: true, dedicatedFrameRead: true)
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    private func route(_ request: URLRequest, strict: Bool, dedicatedFrameRead: Bool = false) async throws -> CameraHTTPResponse {
        let path = request.url!.path, method = request.httpMethod ?? "GET"
        trace.append((strict ? "strict " : "ordinary ") + method + " " + path)
        if method == "GET", path.hasSuffix("/shooting/liveview") || path.hasSuffix("/shooting/liveview/flip") {
            frameLog.append(FrameRead(method: method, path: path, query: request.url?.query,
                strict: strict, dedicatedFrameRead: dedicatedFrameRead, timeout: request.timeoutInterval))
            if let failure = frameFailure {
                frameFailure = nil
                return CameraHTTPResponse(statusCode: failure.0,
                    headers: ["location": "/never-follow", "www-authenticate": "Basic realm=fixture"], body: failure.1)
            }
            return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], body: CCAPILiveViewTestFixture.jpeg)
        }
        let action: String? = path.hasSuffix("/liveview/stop") || path.hasSuffix("/shooting/liveview") && method == "DELETE" ? "stop"
            : path.hasSuffix("/liveview/start") || path.hasSuffix("/shooting/liveview") && method == "POST" ? "start" : nil
        if let action, strict {
            let index = log.count
            log.append(Control(action: action, path: path, strict: strict, timeout: request.timeoutInterval, body: request.httpBody))
            if held == action {
                held = nil; await withCheckedContinuation { gate = $0 }
                log[index].cancelledAfterGate = Task.isCancelled
            }
            if let failure, failure.0 == action {
                self.failure = nil
                return CameraHTTPResponse(statusCode: failure.1, headers: ["location": "/never-follow", "www-authenticate": "Basic realm=fixture"], body: Data())
            }
        } else if let action, path.contains("/maintenance/control/") {
            log.append(Control(action: action, path: path, strict: false, timeout: request.timeoutInterval, body: request.httpBody))
            XCTFail("Maintenance control escaped dedicated transport")
        }
        var object: [String: Any] = [:], status = 200
        if path == "/ccapi" {
            var operations: [[String: Any]] = [
                ["path": "/shooting/liveview", "get": true, "post": true, "delete": true],
                ["path": "/functions/sensorcleaning", "post": true],
                ["path": "/shooting/control/shutterbutton/manual", "put": true],
                ["path": "/shooting/control/af", "post": true],
                ["path": "/functions/autopoweroff", "get": true, "put": true],
                ["path": "/deviceinformation", "get": true]]
            if multipleFrameEndpoints { operations.append(["path": "/shooting/liveview/flip", "get": true]) }
            object = ["ver100": operations]
        } else if path.hasSuffix("/deviceinformation") { object = ["productname": "Synthetic maintenance control camera"] }
        else if path == "/health" { object = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"] }
        else if path == "/v1/session" { object = ["id": "synthetic", "engine": "ccapi"] }
        else if path.hasSuffix("/capabilities") {
            object = ["supported": [CameraFeature.autofocus.rawValue], "settings": [], "heldAutofocusSupported": true]
        } else if path.hasSuffix("/focus/auto/start") || path.hasSuffix("/focus/auto/stop") {
            let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
            let start = path.hasSuffix("/start")
            if start { releaseOutstanding = true }
            else if !releaseFails { releaseOutstanding = false }
            object = ["ownerId": payload["ownerId"]!, "active": releaseOutstanding, "stopUnconfirmed": !start && releaseFails]
            if !start && releaseFails { status = 503 }
        } else if path.hasSuffix("/bulb/start") || path.hasSuffix("/bulb/stop") {
            let start = path.hasSuffix("/start")
            if start { releaseOutstanding = true }
            else if !releaseFails { releaseOutstanding = false }
            object = ["bulbExposureActive": releaseOutstanding, "shutterReleaseUnconfirmed": !start && releaseFails]
            if !start && releaseFails { status = 503 }
        } else if path.hasSuffix("/shooting/control/shutterbutton/manual") || path.hasSuffix("/shooting/control/af") {
            let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
            let releasing = ["release", "stop"].contains(payload["action"] as? String ?? "")
            if !releasing { releaseOutstanding = true }
            else if !releaseFails { releaseOutstanding = false }
            if releasing && releaseFails { status = 503 }
        }
        else if path.hasSuffix("/status") { object = ["connected": true, "recording": recording, "liveViewStopUnconfirmed": warning] }
        else if path.hasSuffix("/maintenance/control/session") {
            status = retirementFails ? 503 : releaseOutstanding ? 409 : 204
        } else if path.hasSuffix("/functions/autopoweroff") {
            if method == "PUT" { status = 202 }
            else { object = ["value": "30", "ability": sleepAbility ? ["30", "60", "immediately"] : ["30", "60"]] }
        }
        else if let action {
            if direct { status = action == "stop" ? 204 : 200 }
            else { object = ["active": action == "start", "liveViewStopUnconfirmed": false,
                             "source": "CCAPI_JPEG_POLLING", "size": "SMALL", "requestedFps": 4] }
        } else if path.hasSuffix("/maintenance/sensor-cleaning/outcome") {
            object = ["version": 1, "engine": "ccapi", "operation": "sensor-cleaning", "autoPowerOff": false,
                "responseEvidence": "response-received", "clockReference": NSNull(), "clockObservation": NSNull(), "clockReadEvidence": "not-applicable"]
        } else if path.hasSuffix("/maintenance/sensor-cleaning") {
            object = ["version": 1, "engine": "ccapi", "operation": "sensor-cleaning", "observationEvidence": "reachable", "clockObservation": NSNull()]
        } else if path.hasSuffix("/functions/sensorcleaning") { object = [:] }
        else { throw URLError(.unsupportedURL) }
        if strict, action == "start", omitStartStopProof { object.removeValue(forKey: "liveViewStopUnconfirmed") }
        return CameraHTTPResponse(statusCode: status, body: status == 204 ? Data() : try JSONSerialization.data(withJSONObject: object))
    }
}
