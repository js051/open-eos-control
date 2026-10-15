import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OpenEOSCore

/// Counterexamples use the production client and the existing explicit response gates.
/// These sources require a Swift host; source inspection is not runtime red/green evidence.
final class CCAPIShortControlRecoveryTests: XCTestCase {
    func testLiveViewStartAwaitingFirstFrameBlocksCaptureAFShutterAndModeMutations() async throws {
        for gateAtFirstFrame in [false, true] {
            let gate = ShutterRecoveryGate(gateAtFirstFrame
                ? "Live View Start acknowledged; first JPEG is still pending"
                : "Live View Start body dispatched; ACK is still pending")
            defer { gate.open() }
            let peer = LiveViewStartupMutationPeer(gate: gate, gateAtFirstFrame: gateAtFirstFrame)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
            try await client.initialize()
            let start = Task { try await client.startLiveView(.init(source: .ccapiJPEGPolling)) }
            await fulfillment(of: [gate.entered], timeout: 3)
            let before = await peer.requests()
            XCTAssertEqual(before.filter(\.isMutation).map(\.path), ["/ccapi/ver130/shooting/liveview"])
            XCTAssertEqual(before.last?.path, gateAtFirstFrame
                ? "/ccapi/ver130/shooting/liveview/flip?t=0" : "/ccapi/ver130/shooting/liveview")
            let beforeReady = await client.currentLiveViewSource()
            XCTAssertNil(beforeReady, "Start ACK alone cannot publish an input-ready source")

            await fails { try await client.captureStill() }
            await fails { try await client.captureStill(autofocus: false) }
            await fails { try await client.autofocus() }
            await fails { try await client.halfPressShutter() }
            await fails { try await client.startHeldAutofocus(owner: UUID()) }
            await fails { try await client.startBulbExposure() }
            await fails { try await client.setSetting(key: "iso", value: "800") }
            await fails { try await client.setSetting(key: "moviemode", value: "on") }
            await fails { try await client.startLiveView() }
            let blocked = await peer.requests()
            XCTAssertEqual(blocked, before, "A pending first frame owns the camera mutation boundary, including mode writes and status reads")

            gate.open()
            try await start.value
            let afterReady = await peer.requests()
            XCTAssertEqual(afterReady.filter { $0.method == "GET" && $0.path.contains("/shooting/liveview/") }.map(\.path),
                ["/ccapi/ver130/shooting/liveview/flip?t=0"])
            let frame = try await client.liveViewFrame(cacheKey: 77)
            XCTAssertEqual(frame.data, CCAPILiveViewTestFixture.jpeg)
            let afterRetainedRead = await peer.requests()
            XCTAssertEqual(afterRetainedRead, afterReady, "First UI read must consume the retained JPEG without another GET")
            let ready = await client.currentLiveViewSource()
            XCTAssertEqual(ready, .ccapiJPEGPolling)
            await client.stopLiveView()
            let stopped = await peer.requests()
            XCTAssertEqual(stopped.filter(\.isMutation).map { "\($0.method) \($0.path)" }, [
                "POST /ccapi/ver130/shooting/liveview", "DELETE /ccapi/ver130/shooting/liveview",
            ])
        }
    }

    func testHeldAFKeepsPressUntilExactReleaseAndNeverRepresses() async throws {
        let peer = ShortControlPeer(.af)
        let client = try peer.client()
        let owner = UUID()
        try await client.startHeldAutofocus(owner: owner)
        let held = await peer.requests().filter(\.isMutation)
        XCTAssertEqual(held.map(\.action), ["start"])
        let heldState = await client.shutterReleaseState()
        XCTAssertTrue(heldState.releaseRequired)
        await fails { try await client.autofocus() }
        await fails { try await client.captureStill() }
        await fails { try await client.startHeldAutofocus(owner: UUID()) }
        try await client.stopHeldAutofocus(owner: UUID())
        let ignored = await peer.requests().filter(\.isMutation)
        XCTAssertEqual(ignored, held)
        try await client.stopHeldAutofocus(owner: owner)
        await idle(client)
        let next = UUID()
        try await client.startHeldAutofocus(owner: next)
        try await client.stopHeldAutofocus(owner: owner)
        let newer = await peer.requests().filter(\.isMutation)
        XCTAssertEqual(newer.map(\.action), ["start", "stop", "start"])
        try await client.stopHeldAutofocus(owner: next)
        await idle(client)
    }

    func testHeldAFRequiresActualAFEndpointAndNeverFallsBackToHalfPress() async throws {
        let peer = ShortControlPeer(.half)
        let client = try peer.client()
        await fails { try await client.startHeldAutofocus(owner: UUID()) }
        let writes = await peer.requests().filter(\.isMutation)
        XCTAssertTrue(writes.isEmpty)
        _ = try await client.halfPressShutter()
        await idle(client)
    }

    func testHeldAFCancelledLateStartOwnsStopAndRetainsFailureForExplicitRetry() async throws {
        let gate = ShutterRecoveryGate("held autofocus late start")
        let peer = ShortControlPeer(.af, stops: [.lost, .ack], startGate: gate)
        let client = try peer.client()
        let owner = UUID()
        let start = Task { try await client.startHeldAutofocus(owner: owner) }
        await fulfillment(of: [gate.entered], timeout: 3)
        start.cancel()
        gate.open()
        await fails { try await start.value }
        await unknown(client)
        let writes = await peer.requests().filter(\.isMutation)
        XCTAssertEqual(writes.map(\.action), ["start", "stop"])
        XCTAssertFalse(try XCTUnwrap(writes.last).taskWasCancelled)
        try await client.stopHeldAutofocus(owner: owner)
        await idle(client)
        let after = await peer.requests().filter(\.isMutation)
        XCTAssertEqual(after.map(\.action), ["start", "stop", "stop"])
    }

    func testHeldAFStopWaitsForInFlightStartAndCloseReleasesExactOwner() async throws {
        let gate = ShutterRecoveryGate("held autofocus still starting")
        let peer = ShortControlPeer(.af, startGate: gate)
        let client = try peer.client()
        let owner = UUID()
        let start = Task { try await client.startHeldAutofocus(owner: owner) }
        await fulfillment(of: [gate.entered], timeout: 3)
        let stop = Task { try await client.stopHeldAutofocus(owner: owner) }
        gate.open()
        _ = await start.result
        try await stop.value
        await idle(client)
        let another = UUID()
        try await client.startHeldAutofocus(owner: another)
        let close = await client.closeWithShutterReleaseState()
        XCTAssertEqual(close, .idle)
        let writes = await peer.requests().filter(\.isMutation)
        XCTAssertEqual(writes.map(\.action), ["start", "stop", "start", "stop"])
    }

    func testEveryShortControlRetainsExactStopThroughRepeatedFailures() async throws {
        for control in ShortControl.allCases {
            for method in control.methods {
                let peer = ShortControlPeer(control, method: method, starts: [.lost], stops: [.lost, .lost, .ack])
                let client = try peer.client()
                await fails { try await control.run(client) }
                await unknown(client)
                let before = await peer.requests()
                XCTAssertEqual(before.filter(\.isMutation).map(\.action), [control.startAction, control.stopAction])
                await fails { try await client.captureStill() }
                await fails { try await client.halfPressShutter() }
                await fails { try await client.autofocus() }
                await fails { try await client.startBulbExposure() }
                await fails { try await client.setSetting(key: "iso", value: "800") }
                await fails { try await client.startLiveView() }
                let blocked = await peer.requests()
                XCTAssertEqual(blocked.filter(\.isMutation), before.filter(\.isMutation))
                XCTAssertEqual(blocked, before, "Unresolved shutter ownership must block Live View before startup or first-frame GET")
                await fails { try await client.retryShutterRelease() }
                await unknown(client)
                try await client.retryShutterRelease()
                let after = await peer.requests()
                let retries = Array(after.dropFirst(blocked.count))
                XCTAssertEqual(retries.count, 2, "Retry must send only the stored stop, with no discovery or status GET")
                XCTAssertTrue(retries.allSatisfy { $0.path == control.path && $0.method == method })
                XCTAssertTrue(retries.allSatisfy { $0.body == before.last(where: \.isMutation)?.body })
                await idle(client)
                try await client.retryShutterRelease()
                let redundant = await peer.requests()
                XCTAssertEqual(redundant, after)
            }
        }
    }

    func testSuccessfulShortControlsReleaseAndPermitTheNextCurrentSessionCommand() async throws {
        for control in ShortControl.allCases {
            let peer = ShortControlPeer(control)
            let client = try peer.client()
            _ = try await control.run(client)
            await idle(client)
            _ = try await control.run(client)
            let writes = await peer.requests().filter(\.isMutation)
            XCTAssertEqual(writes.map(\.action), [control.startAction, control.stopAction, control.startAction, control.stopAction])
            XCTAssertEqual(writes[0].autofocus, control == .af ? nil : control == .capture ? false : true)
            XCTAssertEqual(writes[1].autofocus, control == .af ? nil : false)
        }
    }

    func testKnownRejectedStartRemainsAnHTTPRejectionAfterSuccessfulCompensation() async throws {
        for control in ShortControl.allCases {
            let peer = ShortControlPeer(control, starts: [.rejected])
            let client = try peer.client()
            do { _ = try await control.run(client); XCTFail("Rejected start must fail") }
            catch let CCAPIError.http(code, _, _, _) { XCTAssertEqual(code, 409) }
            catch { XCTFail("Known rejection was changed to \(error)") }
            await idle(client)
            let writes = await peer.requests().filter(\.isMutation)
            XCTAssertEqual(writes.map(\.action), [control.startAction, control.stopAction])
        }
    }

    func testCancellationBeforeDispatchCreatesNoStopResponsibility() async throws {
        let gate = ShutterRecoveryGate("cancel before entering short control")
        let peer = ShortControlPeer(.half)
        let client = try peer.client()
        try await client.initialize()
        let command = Task { await gate.wait(); return try await client.halfPressShutter() }
        await fulfillment(of: [gate.entered], timeout: 3)
        command.cancel()
        gate.open()
        await fails { try await command.value }
        await idle(client)
        let writes = await peer.requests().filter(\.isMutation)
        XCTAssertTrue(writes.isEmpty)
    }

    func testCancellationAfterDispatchAndLateACKRetainFailedCleanup() async throws {
        for response in [ShortReply.ack, .cancelled] {
            let gate = ShutterRecoveryGate("short press dispatched before caller cancellation")
            let peer = ShortControlPeer(.half, starts: [response], stops: [.lost, .ack], startGate: gate)
            let client = try peer.client()
            let command = Task { try await client.halfPressShutter() }
            await fulfillment(of: [gate.entered], timeout: 3)
            command.cancel()
            gate.open()
            await fails { try await command.value }
            await unknown(client)
            let writes = await peer.requests().filter(\.isMutation)
            XCTAssertEqual(writes.map(\.action), ["half_press", "release"])
            XCTAssertFalse(try XCTUnwrap(writes.last).taskWasCancelled)
            try await client.retryShutterRelease()
            await idle(client)
        }
    }

    func testCloseWaitsForLateStartThenRetiresOnlyItsOriginalCameraResponsibility() async throws {
        for failClose in [false, true] {
            let gate = ShutterRecoveryGate("old camera short press awaits late ACK")
            let peer = ShortControlPeer(.af, stops: failClose ? [.lost, .lost] : [.lost, .ack], startGate: gate)
            let client = try peer.client()
            let command = Task { try await client.autofocus() }
            await fulfillment(of: [gate.entered], timeout: 3)
            let entered = expectation(description: "close enters original client")
            let close = Task { await client.shortTestClose { entered.fulfill() } }
            await fulfillment(of: [entered], timeout: 3)
            let held = await peer.requests().filter(\.isMutation)
            XCTAssertEqual(held.map(\.action), ["start"], "Close must not send stop ahead of the pending start")
            let replacementPeer = ShortControlPeer(.half)
            let replacement = try replacementPeer.client(port: 18081)
            _ = try await replacement.halfPressShutter()
            let replacementBefore = await replacementPeer.requests()
            gate.open()
            await fails { try await command.value }
            let closed = await close.value
            XCTAssertFalse(closed.releaseRequired)
            XCTAssertEqual(closed.releaseUnconfirmed, failClose)
            let oldWrites = await peer.requests().filter(\.isMutation)
            XCTAssertEqual(oldWrites.map(\.action), ["start", "stop", "stop"])
            await fails { try await client.retryShutterRelease() }
            let repeated = await client.closeWithShutterReleaseState()
            XCTAssertEqual(repeated, closed)
            let replacementAfter = await replacementPeer.requests()
            XCTAssertEqual(replacementAfter, replacementBefore, "Old cleanup cannot reach a replacement camera")
            await idle(replacement)
        }
    }

    func testConcurrentShortStartAndRetryCannotOvertakeTheOwningPress() async throws {
        let gate = ShutterRecoveryGate("first manual press has no ACK yet")
        let peer = ShortControlPeer(.capture, startGate: gate)
        let client = try peer.client()
        try await client.initialize()
        let first = Task { try await client.captureStill(autofocus: false) }
        await fulfillment(of: [gate.entered], timeout: 3)
        await unknown(client)
        await fails { try await client.captureStill(autofocus: false) }
        let entered = expectation(description: "retry enters client before press returns")
        let retry = Task { try await client.shortTestRetry { entered.fulfill() } }
        await fulfillment(of: [entered], timeout: 3)
        let held = await peer.requests().filter(\.isMutation)
        XCTAssertEqual(held.map(\.action), ["full_press"])
        gate.open()
        await fails { try await first.value }
        try await retry.value
        await idle(client)
        _ = try await client.captureStill(autofocus: false)
        let writes = await peer.requests().filter(\.isMutation)
        XCTAssertEqual(writes.map(\.action), ["full_press", "release", "full_press", "release"])
    }

    func testConcurrentRetriesShareOneOriginalStopAndCannotEraseTheNextOwner() async throws {
        let peer = ShortControlPeer(.capture, stops: [.lost, .ack, .lost, .ack])
        let client = try peer.client()
        await fails { try await ShortControl.capture.run(client) }
        let gate = ShutterRecoveryGate("explicit retry is awaiting its stop ACK")
        await peer.holdNextStop(gate)
        let first = Task { try await client.retryShutterRelease() }
        await fulfillment(of: [gate.entered], timeout: 3)
        let entered = expectation(description: "second retry enters original stop")
        let second = Task { try await client.shortTestRetry { entered.fulfill() } }
        await fulfillment(of: [entered], timeout: 3)
        gate.open()
        try await first.value
        try await second.value
        await idle(client)
        await fails { try await ShortControl.capture.run(client) }
        await unknown(client)
        let before = await peer.requests().filter(\.isMutation)
        XCTAssertEqual(before.map(\.action), ["full_press", "release", "release", "full_press", "release"])
        try await client.retryShutterRelease()
        await idle(client)
        let after = await peer.requests().filter(\.isMutation)
        XCTAssertEqual(after.map(\.action), before.map(\.action) + ["release"])
    }

    func testReleaseACKStaysClearedWhenLaterStatusReadIsCancelled() async throws {
        let peer = ShortControlPeer(.capture, cancelStatusAfterStop: true)
        let client = try peer.client()
        try await client.initialize()
        await fails { try await client.captureStill(autofocus: false) }
        await idle(client)
        let before = await peer.requests()
        try await client.retryShutterRelease()
        let after = await peer.requests()
        XCTAssertEqual(after, before)
    }

    func testSimulatorShortControlDoesNotSuppressLaterObservedBulbOwnership() async throws {
        let peer = SimulatorShortControlPeer()
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer)
        _ = try await client.halfPressShutter()
        await idle(client)
        await peer.setObservedBulb(true)
        let status = try await client.status()
        XCTAssertEqual(status.bulbExposureActive, true)
        let owned = await client.shutterReleaseState()
        XCTAssertTrue(owned.releaseRequired)
        XCTAssertFalse(owned.releaseUnconfirmed)
        try await client.retryShutterRelease()
        await idle(client)
        let writes = await peer.writes()
        XCTAssertEqual(writes, ["/ccapi/shutter/half-press", "/ccapi/shutter/release", "/ccapi/bulb/stop"])
    }

    func testSimulatorStatusCannotReplaceAnUnresolvedShortStopWithBulbStop() async throws {
        let peer = SimulatorShortControlPeer(failFirstRelease: true)
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer)
        await fails { try await client.autofocus() }
        await unknown(client)
        await peer.setObservedBulb(true)
        let status = try await client.status()
        XCTAssertNil(status.bulbExposureActive)
        XCTAssertEqual(status.shutterReleaseUnconfirmed, true)
        try await client.retryShutterRelease()
        await idle(client)
        let writes = await peer.writes()
        XCTAssertEqual(writes, ["/ccapi/shutter/half-press", "/ccapi/shutter/release", "/ccapi/shutter/release"])
    }

    func testLateSimulatorActiveStatusCannotRecreateOwnershipAfterClose() async throws {
        for failClose in [false, true] {
            let peer = SimulatorShortControlPeer(failedReleases: failClose ? 2 : 0)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer)
            if failClose { await fails { try await client.autofocus() } }
            await peer.setObservedBulb(true)
            let gate = ShutterRecoveryGate("active status body arrives after retirement")
            await peer.holdNextStatus(gate)
            let read = Task { try await client.status() }
            await fulfillment(of: [gate.entered], timeout: 3)
            let closed = await client.closeWithShutterReleaseState()
            XCTAssertFalse(closed.releaseRequired)
            XCTAssertEqual(closed.releaseUnconfirmed, failClose)
            gate.open()
            let late = try await read.value
            XCTAssertEqual(late.shutterReleaseUnconfirmed, failClose)
            XCTAssertEqual(late.bulbExposureActive, failClose ? nil : false)
            let retired = await client.shutterReleaseState()
            XCTAssertEqual(retired, closed)
            let before = await peer.writes()
            await fails { try await client.retryShutterRelease() }
            let after = await peer.writes()
            XCTAssertEqual(after, before)
        }
    }

    private func fails<T>(_ action: () async throws -> T, file: StaticString = #filePath, line: UInt = #line) async {
        do { _ = try await action(); XCTFail("Expected failure", file: file, line: line) } catch {}
    }
    private func unknown(_ client: CCAPIClient, file: StaticString = #filePath, line: UInt = #line) async {
        let state = await client.shutterReleaseState()
        XCTAssertTrue(state.releaseRequired, file: file, line: line)
        XCTAssertTrue(state.releaseUnconfirmed, file: file, line: line)
        XCTAssertNil(state.bulbExposureActive, file: file, line: line)
    }
    private func idle(_ client: CCAPIClient, file: StaticString = #filePath, line: UInt = #line) async {
        let state = await client.shutterReleaseState()
        XCTAssertEqual(state, .idle, file: file, line: line)
    }
}

private extension CCAPIClient {
    func shortTestClose(onEntry: @Sendable () -> Void) async -> CameraShutterReleaseState {
        onEntry(); return await closeWithShutterReleaseState()
    }
    func shortTestRetry(onEntry: @Sendable () -> Void) async throws {
        onEntry(); try await retryShutterRelease()
    }
}

private enum ShortControl: CaseIterable, Sendable {
    case capture, half, af, fallbackAF
    var path: String { "/ccapi/ver130/shooting/control/" + (self == .af ? "af" : "shutterbutton/manual") }
    var methods: [String] { self == .af ? ["POST"] : ["POST", "PUT"] }
    var startAction: String { self == .af ? "start" : self == .capture ? "full_press" : "half_press" }
    var stopAction: String { self == .af ? "stop" : "release" }
    func run(_ client: CCAPIClient) async throws -> CameraStatus {
        try await client.initialize()
        switch self {
        case .capture: return try await client.captureStill(autofocus: false)
        case .half: return try await client.halfPressShutter()
        case .af, .fallbackAF: return try await client.autofocus()
        }
    }
}

private enum ShortReply: Sendable {
    case ack, lost, cancelled, rejected
    func response() throws -> CameraHTTPResponse {
        switch self {
        case .ack: return CameraHTTPResponse(statusCode: 204)
        case .rejected: return CameraHTTPResponse(statusCode: 409)
        case .lost: throw URLError(.networkConnectionLost)
        case .cancelled: throw CancellationError()
        }
    }
}

private actor ShortControlPeer: CameraHTTPTransport {
    nonisolated let control: ShortControl
    nonisolated let method: String
    private var starts: [ShortReply]
    private var stops: [ShortReply]
    private var startGate: ShutterRecoveryGate?
    private var stopGate: ShutterRecoveryGate?
    private let cancelStatusAfterStop: Bool
    private var stopped = false
    private var recorded: [ShutterRecoveryRequest] = []
    init(_ control: ShortControl, method: String? = nil, starts: [ShortReply] = [], stops: [ShortReply] = [],
         startGate: ShutterRecoveryGate? = nil, cancelStatusAfterStop: Bool = false) {
        self.control = control; self.method = method ?? control.methods[0]
        self.starts = starts; self.stops = stops; self.startGate = startGate
        self.cancelStatusAfterStop = cancelStatusAfterStop
    }
    nonisolated func client(port: Int = 18080) throws -> CCAPIClient {
        try CCAPIClient(baseURL: "http://127.0.0.1:\(port)", mode: .camera, transport: self)
    }
    func requests() -> [ShutterRecoveryRequest] { recorded }
    func holdNextStop(_ gate: ShutterRecoveryGate) { stopGate = gate }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let record = ShutterRecoveryRequest(method: request.httpMethod ?? "GET", path: request.url!.path,
            body: request.httpBody ?? Data(), taskWasCancelled: Task.isCancelled)
        recorded.append(record)
        if record.action == control.startAction {
            let reply = starts.isEmpty ? .ack : starts.removeFirst()
            let gate = startGate
            startGate = nil
            await gate?.wait()
            return try reply.response()
        }
        if record.action == control.stopAction {
            let reply = stops.isEmpty ? .ack : stops.removeFirst()
            let gate = stopGate
            stopGate = nil
            await gate?.wait()
            let response = try reply.response()
            stopped = true
            return response
        }
        if record.method == "GET", stopped, cancelStatusAfterStop { throw CancellationError() }
        let body: String
        switch record.path {
        case "/ccapi", "/ccapi/":
            body = """
            {"ver130":[{"path":"\(control.path)","\(method.lowercased())":true},
            {"path":"/shooting/settings","get":true},{"path":"/shooting/settings/iso","put":true}]}
            """
        case "/ccapi/ver130/shooting/settings": body = #"{"iso":{"value":"100","ability":["100","800"]}}"#
        default: body = "{}"
        }
        return CameraHTTPResponse(statusCode: 200, body: Data(body.utf8))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}

private actor SimulatorShortControlPeer: CameraHTTPTransport {
    private var observedBulb = false
    private var failedReleases: Int
    private var statusGate: ShutterRecoveryGate?
    private var mutations: [String] = []
    init(failFirstRelease: Bool = false, failedReleases: Int = 0) {
        self.failedReleases = failFirstRelease ? 1 : failedReleases
    }
    func holdNextStatus(_ gate: ShutterRecoveryGate) { statusGate = gate }
    func setObservedBulb(_ active: Bool) { observedBulb = active }
    func writes() -> [String] { mutations }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        if request.httpMethod != "GET" { mutations.append(path) }
        if path == "/ccapi/shutter/release", failedReleases > 0 {
            failedReleases -= 1
            throw URLError(.networkConnectionLost)
        }
        let body = path == "/ccapi/status"
            ? "{\"bulb_exposure_active\":\(observedBulb),\"recording\":false,\"battery\":{},\"media\":{},\"exposure\":{}}"
            : "{}"
        if path == "/ccapi/status" {
            let gate = statusGate
            statusGate = nil
            await gate?.wait()
        }
        return CameraHTTPResponse(statusCode: 200, body: Data(body.utf8))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}

private actor LiveViewStartupMutationPeer: CameraHTTPTransport {
    private let gate: ShutterRecoveryGate
    private let gateAtFirstFrame: Bool
    private var recorded: [ShutterRecoveryRequest] = []
    init(gate: ShutterRecoveryGate, gateAtFirstFrame: Bool) {
        self.gate = gate; self.gateAtFirstFrame = gateAtFirstFrame
    }
    func requests() -> [ShutterRecoveryRequest] { recorded }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let url = try XCTUnwrap(request.url), method = request.httpMethod ?? "GET"
        recorded.append(ShutterRecoveryRequest(method: method,
            path: url.path + (url.query.map { "?" + $0 } ?? ""),
            body: request.httpBody ?? Data(), taskWasCancelled: Task.isCancelled))
        if method == "POST", url.path.hasSuffix("/shooting/liveview"), !gateAtFirstFrame { await gate.wait() }
        if method == "GET", url.path.hasSuffix("/shooting/liveview/flip") {
            if gateAtFirstFrame { await gate.wait() }
            return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], body: CCAPILiveViewTestFixture.jpeg)
        }
        if method != "GET" { return CameraHTTPResponse(statusCode: 204) }
        let body: String
        switch url.path {
        case "/ccapi", "/ccapi/":
            body = """
            {"ver130":[
              {"path":"/shooting/liveview","post":true,"delete":true},
              {"path":"/shooting/liveview/flip","get":true},
              {"path":"/shooting/control/shutterbutton","post":true},
              {"path":"/shooting/control/shutterbutton/manual","put":true},
              {"path":"/shooting/control/af","post":true},
              {"path":"/shooting/control/moviemode","get":true,"post":true},
              {"path":"/shooting/settings","get":true},
              {"path":"/shooting/settings/iso","put":true}
            ]}
            """
        case "/ccapi/ver130/shooting/settings":
            body = #"{"iso":{"value":"100","ability":["100","800"]},"tv":{"value":"bulb","ability":["bulb"]}}"#
        case "/ccapi/ver130/shooting/control/moviemode": body = #"{"status":"off"}"#
        default: body = "{}"
        }
        return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "application/json"], body: Data(body.utf8))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}
