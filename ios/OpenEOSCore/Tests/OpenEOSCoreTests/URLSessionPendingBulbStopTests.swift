import Darwin
import Foundation
import OpenEOSCore
import XCTest

/// Exercises explicit Stop/Close through the production URLSession transport.
/// No request/resource timeout is shortened; the Start body barrier stays shut.
/// The separate historical idle-timeout experiment remains unresolved.
final class URLSessionPendingBulbStopTests: XCTestCase {
    func testExplicitStopCancelsPendingDirectStartForBothAdvertisedMethods() async throws {
        for endpoint in PendingBulbWireEndpoint.variants {
            try await checkDirect(endpoint, close: false, loseRelease: false)
        }
    }

    func testPendingDirectStopLosingReleaseKeepsExplicitRetryForBothAdvertisedMethods() async throws {
        for endpoint in PendingBulbWireEndpoint.variants {
            try await checkDirect(endpoint, close: false, loseRelease: true)
        }
    }

    func testConcurrentClosesCancelPendingDirectStartAndShareOriginalRelease() async throws {
        for endpoint in PendingBulbWireEndpoint.variants {
            try await checkDirect(endpoint, close: true, loseRelease: false)
        }
    }

    func testDirectCloseRetainsOneFinalReleaseAfterFailedStartCompensationForPUTAndPOST() async throws {
        for endpoint in PendingBulbWireEndpoint.variants {
            // First failure followed by final ACK confirms cleanup; losing both
            // acknowledgements must preserve the retired connection's warning.
            for failures in [1, 2] {
                try await checkDirect(endpoint, close: true, loseRelease: false, failedCloseReleases: failures)
            }
        }
    }

    private func checkDirect(
        _ endpoint: PendingBulbWireEndpoint, close: Bool, loseRelease: Bool,
        failedCloseReleases: Int = 0
    ) async throws {
        let failures = close ? failedCloseReleases : loseRelease ? 1 : 0
        let peer = try PendingBulbWirePeer(endpoint: endpoint, failedReleases: failures)
        defer { XCTAssertTrue(peer.stop()) }
        let transport = PendingBulbWireTransport()
        let client = try CCAPIClient(baseURL: peer.baseURL, mode: .camera, transport: transport)
        let start = PendingBulbWireJob("direct Start settles through cancellation") {
            try await client.startBulbExposure()
        }
        defer { start.cancel() }
        await fulfillment(of: [peer.pressReceived], timeout: 3)
        try await checkConcurrentPeer(peer, transport: transport)
        XCTAssertFalse(start.isFinished)
        XCTAssertTrue(transport.startOutcomes().isEmpty)

        let first = PendingBulbWireJob("first direct Stop or Close settles") {
            if close { return await client.closeWithShutterReleaseState() }
            try await client.retryShutterRelease()
            return await client.shutterReleaseState()
        }
        defer { first.cancel() }
        await fulfillment(of: [peer.releaseReceived], timeout: 3)
        let entered = expectation(description: "second action enters the client while cleanup is held")
        let second = PendingBulbWireJob("second direct Stop or Close settles") {
            try await client.pendingBulbWireAction(close: close) { entered.fulfill() }
        }
        defer { second.cancel() }
        await fulfillment(of: [entered], timeout: 2)
        peer.completeRelease()
        await fulfillment(of: [start.finished, first.finished, second.finished], timeout: 3)
        XCTAssertThrowsError(try start.value())
        XCTAssertEqual(transport.startOutcomes(), [.cancelled], "The real send must report cancellation")
        if close {
            let unconfirmed = failedCloseReleases == 2
            let expected = CameraShutterReleaseState(
                releaseRequired: false, releaseUnconfirmed: unconfirmed,
                bulbExposureActive: unconfirmed ? nil : false
            )
            XCTAssertEqual(try first.value(), expected)
            XCTAssertEqual(try second.value(), expected)
            let beforeRepeatedClose = peer.requests().filter(\.isMutation).count
            let repeated = await client.closeWithShutterReleaseState()
            XCTAssertEqual(repeated, expected, "Completed Close retains the same result without new requests")
            XCTAssertEqual(peer.requests().filter(\.isMutation).count, beforeRepeatedClose)
        } else if loseRelease {
            XCTAssertThrowsError(try first.value())
            XCTAssertThrowsError(try second.value())
            let state = await client.shutterReleaseState()
            XCTAssertTrue(state.releaseRequired)
            XCTAssertTrue(state.releaseUnconfirmed)
            XCTAssertEqual(peer.requests().shutterActions, ["full_press", "release"])
            let retry = PendingBulbWireJob("later explicit release retry succeeds") {
                try await client.retryShutterRelease()
            }
            defer { retry.cancel() }
            await fulfillment(of: [retry.finished], timeout: 3)
            try retry.value()
        } else {
            XCTAssertEqual(try first.value(), .idle)
            XCTAssertEqual(try second.value(), .idle)
        }
        let commands = peer.requests().filter(\.isMutation)
        let releaseCount = close ? (failedCloseReleases == 0 ? 1 : 2) : (loseRelease ? 2 : 1)
        XCTAssertEqual(commands.shutterActions, ["full_press"] + Array(repeating: "release", count: releaseCount))
        XCTAssertLessThanOrEqual(commands.shutterActions.filter { $0 == "release" }.count, 2)
        XCTAssertTrue(commands.allSatisfy { $0.method == endpoint.method && $0.path == endpoint.manualPath && $0.autofocus == false })
        XCTAssertFalse(peer.completionWasOpened, "The test must never finish the pending Start body")
        XCTAssertTrue(peer.errors().isEmpty, peer.errors().joined(separator: "\n"))
    }

    func testExplicitStopCancelsPendingBridgeStartAndLostReleaseAllowsRetry() async throws {
        for loseRelease in [false, true] {
            try await checkBridge(close: false, loseRelease: loseRelease)
        }
    }

    func testConcurrentClosesCancelPendingBridgeStartAndShareOriginalSessionDelete() async throws {
        for loseRelease in [false, true] {
            try await checkBridge(close: true, loseRelease: loseRelease)
        }
    }

    private func checkBridge(close: Bool, loseRelease: Bool) async throws {
        let peer = try PendingBulbWirePeer(failedReleases: loseRelease ? 1 : 0)
        defer { XCTAssertTrue(peer.stop()) }
        let transport = PendingBulbWireTransport()
        let client = try DesktopBridgeClient(baseURL: peer.baseURL, transport: transport)
        try await client.initialize()
        let start = PendingBulbWireJob("bridge Start settles through cancellation") {
            try await client.startBulbExposure()
        }
        defer { start.cancel() }
        await fulfillment(of: [peer.pressReceived], timeout: 3)
        try await checkConcurrentPeer(peer, transport: transport)
        XCTAssertFalse(start.isFinished)
        let first = PendingBulbWireJob("first bridge Stop or Close settles") {
            if close { return await client.closeWithShutterReleaseState() }
            try await client.retryShutterRelease()
            return await client.shutterReleaseState()
        }
        defer { first.cancel() }
        await fulfillment(of: [peer.releaseReceived], timeout: 3)
        let entered = expectation(description: "second bridge action enters while cleanup is held")
        let second = PendingBulbWireJob("second bridge Stop or Close settles") {
            try await client.pendingBulbWireAction(close: close) { entered.fulfill() }
        }
        defer { second.cancel() }
        await fulfillment(of: [entered], timeout: 2)
        peer.completeRelease()
        await fulfillment(of: [start.finished, first.finished, second.finished], timeout: 3)
        XCTAssertThrowsError(try start.value())
        XCTAssertEqual(transport.startOutcomes(), [.cancelled])
        if close {
            let expected = CameraShutterReleaseState(
                releaseRequired: false, releaseUnconfirmed: loseRelease,
                bulbExposureActive: loseRelease ? nil : false
            )
            XCTAssertEqual(try first.value(), expected)
            XCTAssertEqual(try second.value(), expected)
        } else if loseRelease {
            XCTAssertThrowsError(try first.value())
            XCTAssertThrowsError(try second.value())
            let pending = await client.shutterReleaseState()
            XCTAssertTrue(pending.releaseRequired)
            XCTAssertTrue(pending.releaseUnconfirmed)
            let retry = PendingBulbWireJob("explicit bridge retry confirms release") {
                try await client.retryShutterRelease()
            }
            defer { retry.cancel() }
            await fulfillment(of: [retry.finished], timeout: 3)
            try retry.value()
        } else {
            XCTAssertEqual(try first.value(), .idle)
            XCTAssertEqual(try second.value(), .idle)
        }
        let mutations = peer.requests().filter(\.isMutation).map { "\($0.method) \($0.path)" }
        let cleanup = close ? "DELETE /v1/session/pending-fixture" : "POST /v1/session/pending-fixture/bulb/stop"
        XCTAssertEqual(mutations, ["POST /v1/session", "POST /v1/session/pending-fixture/bulb/start", cleanup] + (!close && loseRelease ? [cleanup] : []))
        XCTAssertFalse(peer.completionWasOpened)
        XCTAssertTrue(peer.errors().isEmpty, peer.errors().joined(separator: "\n"))
    }

    func testAcknowledgedDirectAndBridgeStartStillRequireAnExplicitStop() async throws {
        let endpoints: [PendingBulbWireEndpoint?] = PendingBulbWireEndpoint.variants.map { $0 } + [nil]
        for endpoint in endpoints {
            let peer = try PendingBulbWirePeer(endpoint: endpoint)
            defer { XCTAssertTrue(peer.stop()) }
            let transport = PendingBulbWireTransport()
            let direct = try endpoint.map { _ in
                try CCAPIClient(baseURL: peer.baseURL, mode: .camera, transport: transport)
            }
            let bridge = try DesktopBridgeClient(baseURL: peer.baseURL, transport: transport)
            if direct == nil { try await bridge.initialize() }
            let start = PendingBulbWireJob("acknowledged normal Start succeeds") {
                if let direct { return try await direct.startBulbExposure() }
                return try await bridge.startBulbExposure()
            }
            defer { start.cancel() }
            await fulfillment(of: [peer.pressReceived], timeout: 3)
            peer.completeBody()
            await fulfillment(of: [start.finished], timeout: 3)
            XCTAssertEqual(try start.value().bulbExposureActive, true)
            XCTAssertEqual(transport.startOutcomes(), [.response])
            XCTAssertFalse(peer.requests().contains { $0.action == "release" || $0.path.hasSuffix("/bulb/stop") })
            let stop = PendingBulbWireJob("explicit Stop after acknowledged Start succeeds") {
                if let direct { try await direct.retryShutterRelease() }
                else { try await bridge.retryShutterRelease() }
            }
            defer { stop.cancel() }
            await fulfillment(of: [peer.releaseReceived], timeout: 3)
            peer.completeRelease()
            await fulfillment(of: [stop.finished], timeout: 3)
            try stop.value()
            XCTAssertTrue(peer.errors().isEmpty, peer.errors().joined(separator: "\n"))
        }
    }

    private func checkConcurrentPeer(_ peer: PendingBulbWirePeer, transport: PendingBulbWireTransport) async throws {
        let probe = PendingBulbWireJob("peer accepts another connection while Start body remains held") {
            try await transport.send(URLRequest(url: URL(string: peer.baseURL + "/fixture-probe")!))
        }
        defer { probe.cancel() }
        await fulfillment(of: [probe.finished], timeout: 2)
        XCTAssertEqual(try probe.value().statusCode, 200)
        XCTAssertFalse(peer.completionWasOpened)
    }
}

private extension CCAPIClient {
    func pendingBulbWireAction(close: Bool, onEntry: @Sendable () -> Void) async throws -> CameraShutterReleaseState {
        onEntry()
        if close { return await closeWithShutterReleaseState() }
        try await retryShutterRelease()
        return shutterReleaseState()
    }
}

private extension DesktopBridgeClient {
    func pendingBulbWireAction(close: Bool, onEntry: @Sendable () -> Void) async throws -> CameraShutterReleaseState {
        onEntry()
        if close { return await closeWithShutterReleaseState() }
        try await retryShutterRelease()
        return shutterReleaseState()
    }
}

private enum PendingBulbStartOutcome: Equatable, Sendable {
    case response, cancelled, otherFailure
}

/// Records outcomes without changing requests, timeouts, bytes, errors, or cancellation.
private struct PendingBulbWireTransport: CameraHTTPTransport {
    private let underlying = URLSessionCameraHTTPTransport()
    private let recorder = PendingBulbOutcomeRecorder()

    func startOutcomes() -> [PendingBulbStartOutcome] { recorder.snapshot() }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let body = request.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]
        let isStart = request.url?.path.hasSuffix("/bulb/start") == true || body?["action"] as? String == "full_press"
        do {
            let response = try await underlying.send(request)
            if isStart { recorder.append(.response) }
            return response
        } catch {
            if isStart {
                recorder.append(error is CancellationError || (error as? URLError)?.code == .cancelled ? .cancelled : .otherFailure)
            }
            throw error
        }
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        try await underlying.download(request)
    }
}

private final class PendingBulbOutcomeRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var outcomes: [PendingBulbStartOutcome] = []
    func append(_ outcome: PendingBulbStartOutcome) {
        lock.lock()
        outcomes.append(outcome)
        lock.unlock()
    }
    func snapshot() -> [PendingBulbStartOutcome] {
        lock.lock()
        defer { lock.unlock() }
        return outcomes
    }
}

/// Bounded XCTest waits must not then await a still-running Task.value indefinitely.
private final class PendingBulbWireJob<Value: Sendable>: @unchecked Sendable {
    let finished: XCTestExpectation
    private let result: PendingBulbWireResult<Value>
    private let task: Task<Void, Never>

    init(_ description: String, operation: @escaping @Sendable () async throws -> Value) {
        let result = PendingBulbWireResult<Value>()
        let finished = XCTestExpectation(description: description)
        self.result = result
        self.finished = finished
        task = Task {
            do { result.set(.success(try await operation())) }
            catch { result.set(.failure(error)) }
            finished.fulfill()
        }
    }

    var isFinished: Bool { result.get() != nil }
    func cancel() { task.cancel() }
    func value() throws -> Value {
        guard let value = result.get() else { throw PendingBulbWireError.operationDidNotFinish }
        return try value.get()
    }
}

private final class PendingBulbWireResult<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var result: Result<Value, Error>?
    func set(_ value: Result<Value, Error>) {
        lock.lock()
        result = value
        lock.unlock()
    }
    func get() -> Result<Value, Error>? {
        lock.lock()
        defer { lock.unlock() }
        return result
    }
}

private enum PendingBulbWireError: Error {
    case systemCall(String, Int32)
    case malformedRequest
    case operationDidNotFinish
}

private struct PendingBulbWireEndpoint: Sendable {
    let version: String
    let method: String
    static let variants = [
        PendingBulbWireEndpoint(version: "ver110", method: "PUT"),
        PendingBulbWireEndpoint(version: "ver130", method: "POST"),
    ]
    var manualPath: String { "/ccapi/\(version)/shooting/control/shutterbutton/manual" }

    func readResponse(path: String) -> CameraHTTPResponse {
        let body: String
        switch path {
        case "/ccapi", "/ccapi/":
            body = """
            {"\(version)":[
              {"path":"/shooting/control/shutterbutton/manual","\(method.lowercased())":true},
              {"path":"/devicestatus/batterylist","get":true},
              {"path":"/devicestatus/storage","get":true},
              {"path":"/shooting/settings","get":true}
            ]}
            """
        case "/ccapi/\(version)/devicestatus/batterylist":
            body = #"{"batterylist":[{"level":90,"status":"normal"}]}"#
        case "/ccapi/\(version)/devicestatus/storage":
            body = #"{"storagelist":[{"name":"card1","status":"ready","freespace":1000000}]}"#
        case "/ccapi/\(version)/shooting/settings":
            body = #"{"iso":{"value":"100","ability":["100"]},"tv":{"value":"bulb","ability":["bulb"]}}"#
        default:
            return CameraHTTPResponse(statusCode: 404, body: Data("{}".utf8))
        }
        return CameraHTTPResponse(statusCode: 200, body: Data(body.utf8))
    }
}

private struct PendingBulbWireRequest: Sendable {
    let method: String
    let path: String
    let body: Data
    var isMutation: Bool { method != "GET" }
    var action: String? { payload?["action"] as? String }
    var autofocus: Bool? { payload?["af"] as? Bool }
    private var payload: [String: Any]? {
        guard !body.isEmpty else { return nil }
        return (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
    }
}

private extension Array where Element == PendingBulbWireRequest {
    var shutterActions: [String] { compactMap(\.action) }
}

/// Every accepted socket gets its own worker. The held press response cannot prevent
/// this peer from accepting and recording a release/DELETE on a different connection.
private final class PendingBulbWirePeer: @unchecked Sendable {
    let baseURL: String
    let pressReceived = XCTestExpectation(description: "peer consumed full press and began body")
    let releaseReceived = XCTestExpectation(description: "peer received release or session DELETE")
    private let listener: Int32
    private let endpoint: PendingBulbWireEndpoint?
    private let condition = NSCondition()
    private let workers = DispatchGroup()
    private var stopped = false
    private var completionOpen = false
    private var sockets = Set<Int32>()
    private var recorded: [PendingBulbWireRequest] = []
    private var failures: [String] = []
    private var releaseOpen = false
    private var releaseCount = 0
    private let failedReleases: Int
    private static let maximumHold: TimeInterval = 8
    private static let paddingBytes = 256
    private static let directStartBody = Data("{}".utf8)
    private static let bridgeStartBody = Data(#"{"bulbExposureActive":true,"shutterReleaseUnconfirmed":false}"#.utf8)

    init(endpoint: PendingBulbWireEndpoint? = nil, failedReleases: Int = 0) throws {
        self.failedReleases = failedReleases
        self.endpoint = endpoint
        let socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { throw PendingBulbWireError.systemCall("socket", errno) }
        var initialized = false
        defer { if !initialized { Darwin.close(socket) } }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, Darwin.listen(socket, 16) == 0 else {
            throw PendingBulbWireError.systemCall("bind/listen", errno)
        }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.getsockname(socket, $0, &length) }
        }
        guard named == 0 else { throw PendingBulbWireError.systemCall("getsockname", errno) }
        baseURL = "http://127.0.0.1:\(UInt16(bigEndian: address.sin_port))"
        listener = socket
        initialized = true
        workers.enter()
        DispatchQueue(label: "OpenEOSCoreTests.pending-bulb-accept.\(UUID().uuidString)").async { [self] in
            acceptConnections()
            workers.leave()
        }
    }

    var completionWasOpened: Bool { locked { completionOpen } }
    func requests() -> [PendingBulbWireRequest] { locked { recorded } }
    func errors() -> [String] { locked { failures } }
    func completeRelease() {
        condition.lock()
        releaseOpen = true
        condition.broadcast()
        condition.unlock()
    }

    func completeBody() {
        condition.lock()
        completionOpen = true
        condition.broadcast()
        condition.unlock()
    }

    func stop() -> Bool {
        condition.lock()
        stopped = true
        for socket in sockets { _ = Darwin.shutdown(socket, SHUT_RDWR) }
        condition.broadcast()
        condition.unlock()
        return workers.wait(timeout: .now() + 3) == .success
    }

    private func locked<Value>(_ body: () -> Value) -> Value {
        condition.lock()
        defer { condition.unlock() }
        return body()
    }

    private func acceptConnections() {
        defer { Darwin.close(listener) }
        while !locked({ stopped }) {
            var ready = pollfd(fd: listener, events: Int16(POLLIN), revents: 0)
            let count = Darwin.poll(&ready, 1, 50)
            if count < 0, errno == EINTR { continue }
            guard count >= 0 else { recordFailure("accept poll failed: \(errno)"); return }
            if count == 0 { continue }
            let socket = Darwin.accept(listener, nil, nil)
            guard socket >= 0 else { recordFailure("accept failed: \(errno)"); return }
            var timeout = timeval(tv_sec: 2, tv_usec: 0)
            var noSignal: Int32 = 1
            _ = setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            _ = setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            _ = setsockopt(socket, SOL_SOCKET, SO_NOSIGPIPE, &noSignal, socklen_t(MemoryLayout<Int32>.size))
            let shouldRun = locked {
                if stopped { return false }
                sockets.insert(socket)
                return true
            }
            guard shouldRun else { Darwin.close(socket); return }
            workers.enter()
            DispatchQueue.global(qos: .userInitiated).async { [self] in
                serve(socket)
                workers.leave()
            }
        }
    }

    private func serve(_ socket: Int32) {
        defer {
            condition.lock()
            sockets.remove(socket)
            _ = Darwin.shutdown(socket, SHUT_RDWR)
            Darwin.close(socket)
            condition.unlock()
        }
        do {
            let request = try readRequest(socket)
            locked { recorded.append(request) }
            if request.method == "GET", request.path == "/fixture-probe" {
                try sendHTTP(200, Data("{}".utf8), socket)
            } else if let endpoint {
                if request.method == "GET" {
                    let response = endpoint.readResponse(path: request.path)
                    if response.statusCode != 200 { recordFailure("Unexpected direct read: \(request.path)") }
                    try sendHTTP(response.statusCode, response.body, socket)
                } else if request.method == endpoint.method, request.path == endpoint.manualPath,
                          request.autofocus == false, request.action == "full_press" {
                    try dripBody(Self.directStartBody, socket)
                } else if request.method == endpoint.method, request.path == endpoint.manualPath,
                          request.autofocus == false, request.action == "release" {
                    try releaseResponse(socket, bridge: false)
                } else {
                    recordFailure("Unexpected direct mutation: \(request.method) \(request.path)")
                    try sendHTTP(400, Data("{}".utf8), socket)
                }
            } else {
                switch (request.method, request.path) {
                case ("GET", "/health"):
                    try sendHTTP(200, Data(#"{"ok":true,"service":"open-eos-control-bridge"}"#.utf8), socket)
                case ("POST", "/v1/session"):
                    try sendHTTP(201, Data(#"{"id":"pending-fixture","engine":"ccapi"}"#.utf8), socket)
                case ("POST", "/v1/session/pending-fixture/bulb/start"):
                    try dripBody(Self.bridgeStartBody, socket)
                case ("POST", "/v1/session/pending-fixture/bulb/stop"),
                     ("DELETE", "/v1/session/pending-fixture"):
                    try releaseResponse(socket, bridge: true)
                case ("GET", "/v1/session/pending-fixture/status"):
                    try sendHTTP(200, Data(#"{"bulbExposureActive":null,"shutterReleaseUnconfirmed":true}"#.utf8), socket)
                default:
                    recordFailure("Unexpected bridge request: \(request.method) \(request.path)")
                    try sendHTTP(400, Data("{}".utf8), socket)
                }
            }
        } catch {
            if !locked({ stopped }) { recordFailure("Wire peer failed: \(error)") }
        }
    }

    private func dripBody(_ json: Data, _ socket: Int32) throws {
        // JSON plus trailing whitespace is valid; declared framing remains incomplete
        // until the test opens the barrier. Never use 204 for a response-body test.
        let padding = Self.paddingBytes
        let headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(json.count + padding)\r\nConnection: close\r\n\r\n"
        try sendAll(Data(headers.utf8) + json, socket)
        pressReceived.fulfill()
        let began = DispatchTime.now().uptimeNanoseconds
        var sent = 0
        while sent < padding {
            condition.lock()
            if !stopped, !completionOpen { _ = condition.wait(until: Date().addingTimeInterval(0.05)) }
            let stop = stopped
            let complete = completionOpen
            condition.unlock()
            if stop { return }
            let elapsed = Double(DispatchTime.now().uptimeNanoseconds - began) / 1_000_000_000
            if elapsed >= Self.maximumHold {
                recordFailure("Body barrier was not released before the fixture deadline")
                return
            }
            do {
                let count = complete ? padding - sent : 1
                try sendAll(Data(repeating: 0x20, count: count), socket)
                sent += count
            } catch let PendingBulbWireError.systemCall(_, code) where code == EPIPE || code == ECONNRESET {
                // Cancellation intentionally closes this socket with the barrier shut.
                return
            }
        }
    }

    private func releaseResponse(_ socket: Int32, bridge: Bool) throws {
        condition.lock()
        releaseCount += 1
        let attempt = releaseCount
        condition.unlock()
        if attempt == 1 { releaseReceived.fulfill() }
        condition.lock()
        let deadline = Date().addingTimeInterval(Self.maximumHold)
        while !stopped, !releaseOpen, Date() < deadline { _ = condition.wait(until: deadline) }
        let canReply = releaseOpen && !stopped
        condition.unlock()
        guard canReply else { return }
        if attempt <= failedReleases {
            try sendHTTP(503, Data(#"{"error":{"code":"SHUTTER_RELEASE_UNCONFIRMED","message":"Fixture release was not confirmed"}}"#.utf8), socket)
        } else if bridge {
            try sendHTTP(200, Data(#"{"bulbExposureActive":false,"shutterReleaseUnconfirmed":false}"#.utf8), socket)
        } else {
            try sendHTTP(204, Data(), socket)
        }
    }

    private func readRequest(_ socket: Int32) throws -> PendingBulbWireRequest {
        var bytes = Data()
        var headerEnd: Int?
        var bodyLength = 0
        var method = ""
        var path = ""
        while true {
            if let headerEnd, bytes.count >= headerEnd + bodyLength {
                return PendingBulbWireRequest(method: method, path: path,
                    body: bytes.subdata(in: headerEnd..<(headerEnd + bodyLength)))
            }
            var buffer = [UInt8](repeating: 0, count: 4096)
            let count = buffer.withUnsafeMutableBytes { Darwin.recv(socket, $0.baseAddress!, $0.count, 0) }
            if count < 0, errno == EINTR { continue }
            guard count > 0 else { throw PendingBulbWireError.malformedRequest }
            bytes.append(contentsOf: buffer.prefix(count))
            guard bytes.count <= 64 * 1024 else { throw PendingBulbWireError.malformedRequest }
            if headerEnd == nil, let range = bytes.range(of: Data("\r\n\r\n".utf8)) {
                let lines = String(decoding: bytes[..<range.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
                let first = lines.first?.split(separator: " ") ?? []
                guard first.count == 3 else { throw PendingBulbWireError.malformedRequest }
                method = String(first[0])
                path = String(first[1])
                for line in lines.dropFirst() {
                    guard let colon = line.firstIndex(of: ":") else { continue }
                    let key = line[..<colon].lowercased()
                    let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                    guard key != "transfer-encoding" else { throw PendingBulbWireError.malformedRequest }
                    if key == "content-length" {
                        guard let length = Int(value), (0...64 * 1024).contains(length) else {
                            throw PendingBulbWireError.malformedRequest
                        }
                        bodyLength = length
                    }
                }
                headerEnd = range.upperBound
            }
        }
    }

    private func sendHTTP(_ status: Int, _ body: Data, _ socket: Int32) throws {
        let fields = status == 204 ? "" : "Content-Type: application/json\r\nContent-Length: \(body.count)\r\n"
        let reason = status == 204 ? "No Content" : status == 201 ? "Created" : status == 200 ? "OK" : "Failure"
        try sendAll(Data("HTTP/1.1 \(status) \(reason)\r\n\(fields)Connection: close\r\n\r\n".utf8) + body, socket)
    }

    private func sendAll(_ data: Data, _ socket: Int32) throws {
        try data.withUnsafeBytes { buffer in
            guard let base = buffer.baseAddress else { return }
            var offset = 0
            while offset < buffer.count {
                let count = Darwin.send(socket, base.advanced(by: offset), buffer.count - offset, 0)
                if count < 0, errno == EINTR { continue }
                guard count > 0 else { throw PendingBulbWireError.systemCall("send", errno) }
                offset += count
            }
        }
    }

    private func recordFailure(_ message: String) { locked { failures.append(message) } }
}
