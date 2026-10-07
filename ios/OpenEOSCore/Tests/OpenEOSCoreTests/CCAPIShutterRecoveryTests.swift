import Foundation
import XCTest

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

@testable import OpenEOSCore

final class CCAPIShutterRecoveryTests: XCTestCase {
    func testLostPressAndLostCompensationRetainExactReleaseForBothAdvertisedMethods() async throws {
        for endpoint in ShutterRecoveryEndpoint.variants {
            let transport = ShutterRecoveryTransport(endpoint: endpoint, press: [.lost], release: [.lost, .ack])
            let client = try makeClient(transport)

            await expectFailure { try await client.startBulbExposure() }
            await assertUnknown(client)
            let beforeRetry = await transport.requests()
            XCTAssertEqual(beforeRetry.shutterActions, ["full_press", "release"])

            try await client.retryShutterRelease()

            let afterRetry = await transport.requests()
            let retry = Array(afterRetry.dropFirst(beforeRetry.count))
            XCTAssertEqual(retry.count, 1, "Recovery must not rediscover, poll status, or repeat the press")
            XCTAssertEqual(retry.first?.method, endpoint.method)
            XCTAssertEqual(retry.first?.path, endpoint.manualPath)
            XCTAssertEqual(retry.first?.action, "release")
            XCTAssertEqual(retry.first?.autofocus, false)
            await assertIdle(client)

            try await client.retryShutterRelease()
            let afterRedundantRetry = await transport.requests()
            XCTAssertEqual(afterRedundantRetry, afterRetry, "An acknowledged release has no remaining obligation")
        }
    }

    func testUnknownReleaseBlocksNewCaptureSettingsLiveViewAndBulbCommands() async throws {
        let transport = ShutterRecoveryTransport(press: [.lost], release: [.lost])
        let client = try makeClient(transport)
        await expectFailure { try await client.startBulbExposure() }
        await assertUnknown(client)
        let before = await transport.requests()

        await expectFailure { try await client.captureStill() }
        await expectFailure { try await client.captureStill(autofocus: false) }
        await expectFailure { try await client.setSetting(key: "iso", value: "800") }
        await expectFailure { try await client.startLiveView() }
        await expectFailure { try await client.startBulbExposure() }

        let after = await transport.requests()
        XCTAssertEqual(after.filter(\.isMutation), before.filter(\.isMutation))
        await assertUnknown(client)
        let status = try await client.status()
        XCTAssertEqual(status.shutterReleaseUnconfirmed, true)
        XCTAssertNil(status.bulbExposureActive, "An unacknowledged press/release is not known to be stopped")

        try await client.retryShutterRelease()
        await assertIdle(client)
    }

    func testCloseRetriesUnknownReleaseAndKeepsFailedCleanupVisibleAfterRetirement() async throws {
        let transport = ShutterRecoveryTransport(press: [.lost], release: [.lost, .lost, .ack])
        let client = try makeClient(transport)
        await expectFailure { try await client.startBulbExposure() }

        let failedClose = await client.closeWithShutterReleaseState()
        XCTAssertFalse(failedClose.releaseRequired, "A retired session cannot dispatch additional commands")
        XCTAssertTrue(failedClose.releaseUnconfirmed)
        XCTAssertNil(failedClose.bulbExposureActive)
        let retired = await client.shutterReleaseState()
        XCTAssertEqual(retired, failedClose)
        let failedRequests = await transport.requests()
        XCTAssertEqual(failedRequests.shutterActions, ["full_press", "release", "release"])

        let repeatedClose = await client.closeWithShutterReleaseState()
        XCTAssertEqual(repeatedClose, failedClose, "Repeating close must not erase the unconfirmed-release warning")
        await expectFailure { try await client.retryShutterRelease() }
        await expectFailure { try await client.startBulbExposure() }
        let afterClose = await transport.requests()
        XCTAssertEqual(afterClose, failedRequests)
    }

    func testConcurrentStartDoesNotDispatchSecondPressAcrossActorSuspension() async throws {
        let gate = ShutterRecoveryGate("first full press reached transport")
        let transport = ShutterRecoveryTransport(pressGate: gate)
        let client = try makeClient(transport)
        let first = Task { try await client.startBulbExposure() }
        await fulfillment(of: [gate.entered], timeout: 3)
        let pending = await client.shutterReleaseState()
        XCTAssertTrue(pending.releaseRequired, "Ownership must be recorded before awaiting the press")

        let secondEntered = expectation(description: "second start entered the client actor")
        let second = Task { try await client.recoveryTestStart(onEntry: { secondEntered.fulfill() }) }
        await fulfillment(of: [secondEntered], timeout: 3)
        gate.open()
        _ = try await first.value
        _ = try? await second.value

        let requests = await transport.requests()
        XCTAssertEqual(requests.shutterActions, ["full_press"])
        try await client.retryShutterRelease()
        await assertIdle(client)
    }

    func testCloseDuringPressWaitsForLateACKAndDoesNotReviveBulbState() async throws {
        let gate = ShutterRecoveryGate("press sent but ACK withheld")
        let transport = ShutterRecoveryTransport(pressGate: gate)
        let client = try makeClient(transport)
        let start = Task { try await client.startBulbExposure() }
        await fulfillment(of: [gate.entered], timeout: 3)

        let closeEntered = expectation(description: "close entered the client actor")
        let close = Task { await client.recoveryTestClose(onEntry: { closeEntered.fulfill() }) }
        await fulfillment(of: [closeEntered], timeout: 3)
        let whileWaiting = await transport.requests()
        XCTAssertEqual(whileWaiting.shutterActions, ["full_press"], "Do not release ahead of an in-flight press")
        gate.open()

        _ = try? await start.value
        let closeState = await close.value
        XCTAssertEqual(closeState, .idle)
        await assertIdle(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.shutterActions, ["full_press", "release"])

        await expectFailure { try await client.startBulbExposure() }
        let afterLateStart = await transport.requests()
        XCTAssertEqual(afterLateStart.shutterActions, requests.shutterActions, "A closed session cannot restart the shutter")
    }

    func testConcurrentStartCannotEnterWhileFirstPreflightIsSuspended() async throws {
        let gate = ShutterRecoveryGate("first start is waiting for its baseline status")
        let transport = ShutterRecoveryTransport(statusGate: gate)
        let client = try makeClient(transport)
        let first = Task { try await client.startBulbExposure() }
        await fulfillment(of: [gate.entered], timeout: 3)
        let secondEntered = expectation(description: "second start overlaps the first preflight")
        let second = Task { try await client.recoveryTestStart(onEntry: { secondEntered.fulfill() }) }
        await fulfillment(of: [secondEntered], timeout: 3)
        gate.open()

        _ = try await first.value
        await expectFailure { try await second.value }
        let requests = await transport.requests()
        XCTAssertEqual(requests.shutterActions, ["full_press"])
        try await client.retryShutterRelease()
        await assertIdle(client)
    }

    func testCloseDuringPreflightPreventsPressAfterTheStatusResponseArrives() async throws {
        let gate = ShutterRecoveryGate("baseline response will arrive after close begins")
        let transport = ShutterRecoveryTransport(statusGate: gate)
        let client = try makeClient(transport)
        let start = Task { try await client.startBulbExposure() }
        await fulfillment(of: [gate.entered], timeout: 3)
        let closeEntered = expectation(description: "close overlaps start preflight")
        let close = Task { await client.recoveryTestClose(onEntry: { closeEntered.fulfill() }) }
        await fulfillment(of: [closeEntered], timeout: 3)
        gate.open()

        await expectFailure { try await start.value }
        let closed = await close.value
        XCTAssertEqual(closed, .idle)
        let requests = await transport.requests()
        XCTAssertTrue(requests.filter(\.isMutation).isEmpty)
        await assertIdle(client)
    }

    func testStopDuringStartCannotReleaseBeforeThePressAcknowledgement() async throws {
        let gate = ShutterRecoveryGate("start press remains in transport")
        let transport = ShutterRecoveryTransport(pressGate: gate)
        let client = try makeClient(transport)
        let start = Task { try await client.startBulbExposure() }
        await fulfillment(of: [gate.entered], timeout: 3)
        let stopEntered = expectation(description: "stop overlaps the pending press")
        let stop = Task { try await client.recoveryTestStop(onEntry: { stopEntered.fulfill() }) }
        await fulfillment(of: [stopEntered], timeout: 3)
        let pendingRequests = await transport.requests()
        XCTAssertEqual(pendingRequests.shutterActions, ["full_press"])
        gate.open()

        await expectFailure { try await start.value }
        _ = try await stop.value
        let requests = await transport.requests()
        XCTAssertEqual(requests.shutterActions, ["full_press", "release"])
        await assertIdle(client)
    }

    func testCloseDuringLostPressRetainsTheWarningAndCannotBeRevivedByRetry() async throws {
        let gate = ShutterRecoveryGate("lost press is still in flight")
        let transport = ShutterRecoveryTransport(
            press: [.lost], release: [.lost, .lost, .ack], pressGate: gate
        )
        let client = try makeClient(transport)
        let start = Task { try await client.startBulbExposure() }
        await fulfillment(of: [gate.entered], timeout: 3)
        let closeEntered = expectation(description: "close owns lifecycle before lost response")
        let close = Task { await client.recoveryTestClose(onEntry: { closeEntered.fulfill() }) }
        await fulfillment(of: [closeEntered], timeout: 3)
        gate.open()
        await expectFailure { try await start.value }
        let closed = await close.value
        XCTAssertFalse(closed.releaseRequired)
        XCTAssertTrue(closed.releaseUnconfirmed)
        XCTAssertNil(closed.bulbExposureActive)

        let beforeRetry = await transport.requests()
        await expectFailure { try await client.retryShutterRelease() }
        let afterRetry = await transport.requests()
        XCTAssertEqual(afterRetry, beforeRetry)
        XCTAssertEqual(afterRetry.shutterActions, ["full_press", "release", "release"])
        XCTAssertEqual(afterRetry.filter { $0.action == "full_press" }.count, 1)
        let retired = await client.shutterReleaseState()
        XCTAssertEqual(retired, closed)
    }

    func testAlreadyCancelledStartNeverDispatchesAPress() async throws {
        let beforeCall = ShutterRecoveryGate("task created before cancellation")
        let transport = ShutterRecoveryTransport()
        let client = try makeClient(transport)
        try await client.initialize()
        let start = Task {
            await beforeCall.wait()
            return try await client.startBulbExposure()
        }
        await fulfillment(of: [beforeCall.entered], timeout: 3)
        start.cancel()
        beforeCall.open()

        await expectFailure { try await start.value }
        let requests = await transport.requests()
        XCTAssertTrue(requests.filter(\.isMutation).isEmpty)
        await assertIdle(client)
    }

    func testCancellationDuringPreflightDoesNotAcquireShutterOwnership() async throws {
        let gate = ShutterRecoveryGate("baseline status response withheld")
        let transport = ShutterRecoveryTransport(statusGate: gate)
        let client = try makeClient(transport)
        let start = Task { try await client.startBulbExposure() }
        await fulfillment(of: [gate.entered], timeout: 3)
        start.cancel()
        gate.open()

        await expectFailure { try await start.value }
        let requests = await transport.requests()
        XCTAssertTrue(requests.filter(\.isMutation).isEmpty)
        await assertIdle(client)
    }

    func testCancellationAfterDispatchKeepsFailedReleaseAvailableForRetry() async throws {
        let gate = ShutterRecoveryGate("press dispatched before cancellation")
        let transport = ShutterRecoveryTransport(
            press: [.cancelled], release: [.lost, .ack], pressGate: gate
        )
        let client = try makeClient(transport)
        let start = Task { try await client.startBulbExposure() }
        await fulfillment(of: [gate.entered], timeout: 3)
        start.cancel()
        gate.open()

        await expectFailure { try await start.value }
        await assertUnknown(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.shutterActions, ["full_press", "release"])
        XCTAssertEqual(requests.last(where: { $0.action == "release" })?.taskWasCancelled, false,
                       "Compensation must survive cancellation of the originating command")
        try await client.retryShutterRelease()
        await assertIdle(client)
    }

    func testLateSuccessfulPressACKAfterCancellationIsCompensated() async throws {
        let gate = ShutterRecoveryGate("transport will deliver a late successful ACK")
        let transport = ShutterRecoveryTransport(pressGate: gate)
        let client = try makeClient(transport)
        let start = Task { try await client.startBulbExposure() }
        await fulfillment(of: [gate.entered], timeout: 3)
        start.cancel()
        gate.open()

        await expectFailure { try await start.value }
        let requests = await transport.requests()
        XCTAssertEqual(requests.shutterActions, ["full_press", "release"])
        await assertIdle(client)
    }

    func testReleaseACKIsNotUndoneBySubsequentStatusFailure() async throws {
        let transport = ShutterRecoveryTransport()
        let client = try makeClient(transport)
        _ = try await client.startBulbExposure()
        await transport.failNextStatusRead()

        await expectFailure { try await client.stopBulbExposure() }
        await assertIdle(client)
        let beforeRetry = await transport.requests()
        XCTAssertEqual(beforeRetry.shutterActions, ["full_press", "release"])

        try await client.retryShutterRelease()
        let afterRetry = await transport.requests()
        XCTAssertEqual(afterRetry, beforeRetry, "A polling failure cannot recreate an acknowledged release obligation")
    }

    func testFailedStopAndFailedRetryKeepAnAcknowledgedExposureUnknownUntilReleaseACK() async throws {
        let transport = ShutterRecoveryTransport(release: [.lost, .lost, .ack])
        let client = try makeClient(transport)
        let started = try await client.startBulbExposure()
        XCTAssertEqual(started.bulbExposureActive, true)

        await expectFailure { try await client.stopBulbExposure() }
        await assertUnknown(client)
        await expectFailure { try await client.startBulbExposure() }
        await expectFailure { try await client.retryShutterRelease() }
        await assertUnknown(client)
        let beforeLastRetry = await transport.requests()
        XCTAssertEqual(beforeLastRetry.shutterActions, ["full_press", "release", "release"])

        try await client.retryShutterRelease()

        let after = await transport.requests()
        XCTAssertEqual(Array(after.dropFirst(beforeLastRetry.count)).shutterActions, ["release"])
        XCTAssertEqual(after.filter { $0.action == "full_press" }.count, 1)
        await assertIdle(client)
    }

    func testStopAndRetryShareOneInFlightRelease() async throws {
        let gate = ShutterRecoveryGate("stop release dispatched but ACK withheld")
        let transport = ShutterRecoveryTransport(releaseGate: gate)
        let client = try makeClient(transport)
        _ = try await client.startBulbExposure()
        let stop = Task { try await client.stopBulbExposure() }
        await fulfillment(of: [gate.entered], timeout: 3)
        let retryEntered = expectation(description: "retry entered client actor while stop is waiting")
        let retry = Task { try await client.recoveryTestRetry(onEntry: { retryEntered.fulfill() }) }
        await fulfillment(of: [retryEntered], timeout: 3)
        gate.open()

        _ = try await stop.value
        try await retry.value
        let requests = await transport.requests()
        XCTAssertEqual(requests.shutterActions, ["full_press", "release"])
        await assertIdle(client)
    }

    private func makeClient(_ transport: ShutterRecoveryTransport) throws -> CCAPIClient {
        try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
    }

    private func expectFailure<T>(
        _ operation: () async throws -> T,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        do {
            _ = try await operation()
            XCTFail("Expected the interrupted or blocked operation to throw", file: file, line: line)
        } catch {
            // The state and exact wire effects, rather than an error string, define recovery correctness.
        }
    }

    private func assertUnknown(
        _ client: CCAPIClient,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        let state = await client.shutterReleaseState()
        XCTAssertTrue(state.releaseRequired, file: file, line: line)
        XCTAssertTrue(state.releaseUnconfirmed, file: file, line: line)
        XCTAssertNil(state.bulbExposureActive, file: file, line: line)
    }

    private func assertIdle(
        _ client: CCAPIClient,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        let state = await client.shutterReleaseState()
        XCTAssertEqual(state, .idle, file: file, line: line)
    }
}

// These actor-isolated entry points make overlap deterministic without timing sleeps or
// production test hooks: the signal is emitted while already executing on the client actor.
private extension CCAPIClient {
    func recoveryTestStart(onEntry: @Sendable () -> Void) async throws -> CameraStatus {
        onEntry()
        return try await startBulbExposure()
    }

    func recoveryTestClose(onEntry: @Sendable () -> Void) async -> CameraShutterReleaseState {
        onEntry()
        return await closeWithShutterReleaseState()
    }

    func recoveryTestRetry(onEntry: @Sendable () -> Void) async throws {
        onEntry()
        try await retryShutterRelease()
    }

    func recoveryTestStop(onEntry: @Sendable () -> Void) async throws -> CameraStatus {
        onEntry()
        return try await stopBulbExposure()
    }
}

struct ShutterRecoveryEndpoint: Sendable {
    let version: String
    let method: String

    static let variants = [
        ShutterRecoveryEndpoint(version: "ver110", method: "PUT"),
        ShutterRecoveryEndpoint(version: "ver130", method: "POST"),
    ]

    var manualPath: String { "/ccapi/\(version)/shooting/control/shutterbutton/manual" }

    var discovery: String {
        """
        {"\(version)":[
          {"path":"/shooting/control/shutterbutton/manual","\(method.lowercased())":true},
          {"path":"/shooting/control/shutterbutton","post":true},
          {"path":"/devicestatus/batterylist","get":true},
          {"path":"/devicestatus/storage","get":true},
          {"path":"/shooting/settings","get":true},
          {"path":"/shooting/settings/iso","put":true},
          {"path":"/shooting/liveview","post":true,"delete":true},
          {"path":"/shooting/liveview/flip","get":true}
        ]}
        """
    }

    func readResponse(path: String) -> CameraHTTPResponse {
        let body: String
        switch path {
        case "/ccapi", "/ccapi/": body = discovery
        case "/ccapi/\(version)/devicestatus/batterylist":
            body = #"{"batterylist":[{"level":90,"status":"normal"}]}"#
        case "/ccapi/\(version)/devicestatus/storage":
            body = #"{"storagelist":[{"name":"card1","status":"ready","freespace":1000000}]}"#
        case "/ccapi/\(version)/shooting/settings":
            body = #"{"iso":{"value":"100","ability":["100","800"]},"tv":{"value":"bulb","ability":["bulb"]}}"#
        default:
            return CameraHTTPResponse(statusCode: 404, body: Data("{}".utf8))
        }
        return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "application/json"], body: Data(body.utf8))
    }
}

struct ShutterRecoveryRequest: Equatable, Sendable {
    let method: String
    let path: String
    let body: Data
    let taskWasCancelled: Bool

    var isMutation: Bool { method != "GET" }
    var action: String? { payload?["action"] as? String }
    var autofocus: Bool? { payload?["af"] as? Bool }
    private var payload: [String: Any]? {
        // Reads have no JSON payload. Do not ask Foundation to parse their empty body
        // while inspecting the interleaved read/mutation wire history.
        guard !body.isEmpty else { return nil }
        return (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
    }
}

extension Array where Element == ShutterRecoveryRequest {
    var shutterActions: [String] { compactMap(\.action) }
}

private enum ShutterRecoveryReply: Sendable {
    case ack
    case lost
    case cancelled

    func response() throws -> CameraHTTPResponse {
        switch self {
        case .ack: return CameraHTTPResponse(statusCode: 204)
        case .lost: throw URLError(.networkConnectionLost)
        case .cancelled: throw CancellationError()
        }
    }
}

private actor ShutterRecoveryTransport: CameraHTTPTransport {
    let endpoint: ShutterRecoveryEndpoint
    private var pressReplies: [ShutterRecoveryReply]
    private var releaseReplies: [ShutterRecoveryReply]
    private let pressGate: ShutterRecoveryGate?
    private let releaseGate: ShutterRecoveryGate?
    private var statusGate: ShutterRecoveryGate?
    private var failStatus = false
    private var recorded: [ShutterRecoveryRequest] = []

    init(
        endpoint: ShutterRecoveryEndpoint = ShutterRecoveryEndpoint.variants[0],
        press: [ShutterRecoveryReply] = [],
        release: [ShutterRecoveryReply] = [],
        pressGate: ShutterRecoveryGate? = nil,
        releaseGate: ShutterRecoveryGate? = nil,
        statusGate: ShutterRecoveryGate? = nil
    ) {
        self.endpoint = endpoint
        pressReplies = press
        releaseReplies = release
        self.pressGate = pressGate
        self.releaseGate = releaseGate
        self.statusGate = statusGate
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let record = ShutterRecoveryRequest(
            method: request.httpMethod ?? "GET", path: request.url?.path ?? "",
            body: request.httpBody ?? Data(), taskWasCancelled: Task.isCancelled
        )
        recorded.append(record)
        if record.action == "full_press" {
            let reply = pressReplies.isEmpty ? .ack : pressReplies.removeFirst()
            await pressGate?.wait()
            return try reply.response()
        }
        if record.action == "release" {
            let reply = releaseReplies.isEmpty ? .ack : releaseReplies.removeFirst()
            await releaseGate?.wait()
            return try reply.response()
        }
        if record.method == "GET", record.path != "/ccapi", record.path != "/ccapi/" {
            let gate = statusGate
            statusGate = nil
            await gate?.wait()
            if failStatus {
                failStatus = false
                throw CancellationError()
            }
        }
        return record.method == "GET"
            ? endpoint.readResponse(path: record.path)
            : CameraHTTPResponse(statusCode: 204)
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        throw URLError(.unsupportedURL)
    }

    func requests() -> [ShutterRecoveryRequest] { recorded }
    func failNextStatusRead() { failStatus = true }
}

private final class ShutterRecoveryGate: @unchecked Sendable {
    let entered: XCTestExpectation
    private let lock = NSLock()
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(_ description: String) {
        entered = XCTestExpectation(description: description)
    }

    func wait() async {
        await withCheckedContinuation { continuation in
            lock.lock()
            let alreadyOpen = isOpen
            if !alreadyOpen { waiters.append(continuation) }
            lock.unlock()
            entered.fulfill()
            if alreadyOpen { continuation.resume() }
        }
    }

    func open() {
        lock.lock()
        isOpen = true
        let pending = waiters
        waiters.removeAll()
        lock.unlock()
        for waiter in pending { waiter.resume() }
    }
}
