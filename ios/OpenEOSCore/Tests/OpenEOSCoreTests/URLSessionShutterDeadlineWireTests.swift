import Darwin
import Foundation
import OpenEOSCore
import XCTest

/// Characterization, not a desired exposure budget. Ownership/cancellation evidence
/// must not be described as an idle-timeout reset unless the silent controls pass.
/// The production 120s resource limit is unchanged.
final class URLSessionShutterDeadlineWireTests: XCTestCase {
    func testNoDripResponseActuallyTimesOutWithTheShortRequestIdleInterval() async throws {
        let peer = try DeadlineWirePeer()
        defer { XCTAssertTrue(peer.stop(), "All fixture sockets and workers must terminate") }
        let transport = ShortIdleWireTransport()
        let jobs = DeadlineWireIdlePhase.allCases.map { phase in
            let request = URLRequest(url: URL(string: peer.baseURL + phase.rawValue)!)
            return (phase, DeadlineWireJob(phase.metricLabel + " finishes") { try await transport.send(request) })
        }
        defer { jobs.forEach { $0.1.cancel() } }

        // Same unchanged 3s bound for both phases, running concurrently. Do not turn
        // test cleanup cancellation into evidence of a network timeout.
        await fulfillment(of: [peer.idleBeforeHeadersEntered, peer.idleBodyEntered] + jobs.map { $0.1.finished }, timeout: 3)
        for (phase, job) in jobs {
            do {
                _ = try job.value()
                XCTFail("\(phase.metricLabel): a silent peer must hit the request idle timeout")
            } catch let error as URLError {
                XCTAssertEqual(error.code, .timedOut, phase.metricLabel)
            } catch {
                XCTFail("\(phase.metricLabel): no completed URLError.timedOut result")
            }
        }
        XCTAssertFalse(peer.completionWasOpened)
        XCTAssertEqual(peer.requests().map(\.path).sorted(), DeadlineWireIdlePhase.allCases.map(\.rawValue).sorted())
        XCTAssertTrue(peer.errors().isEmpty, peer.errors().joined(separator: "\n"))
    }

    func testDirectSlowDripRetainsCloseOwnershipUntilBodyCompletesForPUTAndPOST() async throws {
        for endpoint in DeadlineWireEndpoint.variants {
            try await checkDirect(endpoint: endpoint, cancelStart: false)
        }
    }

    func testDirectStartCancellationReleasesWithoutCompletingTheDrippingBodyForPUTAndPOST() async throws {
        for endpoint in DeadlineWireEndpoint.variants {
            try await checkDirect(endpoint: endpoint, cancelStart: true)
        }
    }

    func testBridgeSlowDripRetainsCloseOwnershipUntilBodyCompletes() async throws {
        try await checkBridge(cancelStart: false)
    }

    func testBridgeStartCancellationAllowsDeleteWithoutCompletingTheDrippingBody() async throws {
        try await checkBridge(cancelStart: true)
    }

    private func checkDirect(endpoint: DeadlineWireEndpoint, cancelStart: Bool) async throws {
        let context = "\(endpoint.method), cancel=\(cancelStart)"
        let peer = try DeadlineWirePeer(endpoint: endpoint)
        defer { XCTAssertTrue(peer.stop(), context) }
        let transport = ShortIdleWireTransport(startPath: endpoint.manualPath)
        let client = try CCAPIClient(baseURL: peer.baseURL, mode: .camera, transport: transport)
        let start = DeadlineWireJob("\(context): start settles") { try await client.startBulbExposure() }
        defer { start.cancel() }
        await fulfillment(of: [peer.pressReceived], timeout: 3)
        XCTAssertEqual(peer.requests().shutterActions, ["full_press"], context)
        try await checkConcurrentPeer(peer, transport: transport)

        let entered = [expectation(description: "first close enters"), expectation(description: "second close enters")]
        let first = DeadlineWireJob("first direct close finishes") {
            await client.deadlineWireClose { entered[0].fulfill() }
        }
        let second = DeadlineWireJob("second direct close finishes") {
            await client.deadlineWireClose { entered[1].fulfill() }
        }
        defer { first.cancel(); second.cancel() }
        await fulfillment(of: entered, timeout: 2)
        await fulfillment(of: [peer.dripCheckpoint], timeout: ShortIdleWireTransport.dripCheckpointWait)
        XCTAssertGreaterThanOrEqual(peer.dripElapsed, ShortIdleWireTransport.idleInterval * 3, context)
        XCTAssertFalse(start.isFinished, context)
        XCTAssertFalse(first.isFinished, context)
        XCTAssertFalse(second.isFinished, context)
        XCTAssertTrue(transport.startOutcomes().isEmpty, context)
        XCTAssertEqual(peer.requests().shutterActions, ["full_press"], context)
        print("deadline-wire direct-\(endpoint.method) category=\(cancelStart ? "cancel-pending" : "complete-pending") " +
              "configuredIdleSeconds=\(ShortIdleWireTransport.idleInterval) checkpointSeconds=\(peer.dripElapsed)")
        if cancelStart {
            // The peer never opens the body-completion barrier in this case.
            start.cancel()
        } else {
            peer.completeBody()
        }

        await fulfillment(of: [start.finished, first.finished, second.finished], timeout: 3)
        checkStartTransportOutcome(transport, peer: peer, cancelled: cancelStart)
        XCTAssertThrowsError(try start.value(), "Closing/cancellation must not revive the exposure") { error in
            if !cancelStart {
                XCTAssertTrue(error is CancellationError, "The client closing guard must reject the successful transport result")
            }
        }
        XCTAssertEqual(try first.value(), .idle, context)
        XCTAssertEqual(try second.value(), .idle, context)
        let commands = peer.requests().filter(\.isMutation)
        XCTAssertEqual(commands.shutterActions, ["full_press", "release"], context)
        XCTAssertEqual(commands.map(\.method), [endpoint.method, endpoint.method], context)
        XCTAssertEqual(commands.map(\.path), [endpoint.manualPath, endpoint.manualPath], context)
        XCTAssertTrue(commands.allSatisfy { $0.autofocus == false }, context)
        XCTAssertEqual(peer.completionWasOpened, !cancelStart, context)
        XCTAssertTrue(peer.errors().isEmpty, "\(context): \(peer.errors().joined(separator: "\n"))")
    }

    private func checkConcurrentPeer(_ peer: DeadlineWirePeer, transport: ShortIdleWireTransport) async throws {
        let request = URLRequest(url: URL(string: peer.baseURL + "/fixture-probe")!)
        let probe = DeadlineWireJob("the same URLSession serves another connection while the press body is held") {
            try await transport.send(request)
        }
        defer { probe.cancel() }
        await fulfillment(of: [probe.finished], timeout: 2)
        XCTAssertEqual(try probe.value().statusCode, 200)
        XCTAssertFalse(peer.completionWasOpened)
    }

    private func checkStartTransportOutcome(
        _ transport: ShortIdleWireTransport, peer: DeadlineWirePeer, cancelled: Bool
    ) {
        let outcomes = transport.startOutcomes()
        if cancelled {
            XCTAssertTrue(outcomes == [.cancellationError] || outcomes == [.urlCancelled],
                          "The production send must actually report cancellation, got \(outcomes)")
        } else {
            XCTAssertEqual(outcomes, [.response(statusCode: 200, bodyBytes: peer.expectedStartBodyBytes)],
                           "The production send must finish the complete padded body before the client rejects Start")
        }
    }

    private func checkBridge(cancelStart: Bool) async throws {
        let peer = try DeadlineWirePeer()
        defer { XCTAssertTrue(peer.stop()) }
        let transport = ShortIdleWireTransport(startPath: "/v1/session/deadline-fixture/bulb/start")
        let client = try DesktopBridgeClient(baseURL: peer.baseURL, transport: transport)
        try await client.initialize()
        // No event capability is advertised: isolate the in-flight start from event DELETE.
        let start = DeadlineWireJob("bridge start settles") { try await client.startBulbExposure() }
        defer { start.cancel() }
        await fulfillment(of: [peer.pressReceived], timeout: 3)
        try await checkConcurrentPeer(peer, transport: transport)
        let entered = [
            expectation(description: "first bridge close enters"),
            expectation(description: "second bridge close enters"),
        ]
        let first = DeadlineWireJob("first bridge close finishes") {
            await client.deadlineWireClose { entered[0].fulfill() }
        }
        let second = DeadlineWireJob("second bridge close finishes") {
            await client.deadlineWireClose { entered[1].fulfill() }
        }
        defer { first.cancel(); second.cancel() }
        await fulfillment(of: entered, timeout: 2)
        await fulfillment(of: [peer.dripCheckpoint], timeout: ShortIdleWireTransport.dripCheckpointWait)
        XCTAssertGreaterThanOrEqual(peer.dripElapsed, ShortIdleWireTransport.idleInterval * 3)
        XCTAssertFalse(start.isFinished)
        XCTAssertFalse(first.isFinished)
        XCTAssertFalse(second.isFinished)
        XCTAssertTrue(transport.startOutcomes().isEmpty)
        XCTAssertFalse(peer.requests().contains { $0.method == "DELETE" })
        print("deadline-wire bridge category=\(cancelStart ? "cancel-pending" : "complete-pending") " +
              "configuredIdleSeconds=\(ShortIdleWireTransport.idleInterval) checkpointSeconds=\(peer.dripElapsed)")
        if cancelStart {
            start.cancel()
        } else {
            peer.completeBody()
        }

        await fulfillment(of: [start.finished, first.finished, second.finished], timeout: 3)
        checkStartTransportOutcome(transport, peer: peer, cancelled: cancelStart)
        XCTAssertThrowsError(try start.value()) { error in
            if !cancelStart {
                XCTAssertEqual(error as? DesktopBridgeError, .sessionChanged,
                               "The client closing guard must reject the successful transport result")
            }
        }
        XCTAssertEqual(try first.value(), .idle)
        XCTAssertEqual(try second.value(), .idle)
        XCTAssertEqual(peer.requests().map { "\($0.method) \($0.path)" }, [
            "GET /health", "POST /v1/session", "POST /v1/session/deadline-fixture/bulb/start",
            "GET /fixture-probe", "DELETE /v1/session/deadline-fixture",
        ])
        XCTAssertEqual(peer.completionWasOpened, !cancelStart)
        XCTAssertTrue(peer.errors().isEmpty, peer.errors().joined(separator: "\n"))
    }
}

private extension CCAPIClient {
    func deadlineWireClose(onEntry: @Sendable () -> Void) async -> CameraShutterReleaseState {
        onEntry()
        return await closeWithShutterReleaseState()
    }
}

private extension DesktopBridgeClient {
    func deadlineWireClose(onEntry: @Sendable () -> Void) async -> CameraShutterReleaseState {
        onEntry()
        return await closeWithShutterReleaseState()
    }
}

/// Changes only URLRequest's idle interval. All networking, response integrity and
/// cancellation still run through the unmodified production URLSession transport.
private struct ShortIdleWireTransport: CameraHTTPTransport {
    static let idleInterval: TimeInterval = 1.0
    static let dripCheckpointInterval: TimeInterval = idleInterval * 3.25
    // 3.25s target plus 1.25s scheduling margin; the silent control remains 3s.
    static let dripCheckpointWait: TimeInterval = dripCheckpointInterval + 1.25
    private let underlying = URLSessionCameraHTTPTransport()
    private let startPath: String?
    private let recorder = DeadlineWireTransportRecorder()

    init(startPath: String? = nil) { self.startPath = startPath }

    func startOutcomes() -> [DeadlineWireTransportOutcome] { recorder.snapshot() }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        var request = request
        request.timeoutInterval = Self.idleInterval
        let observe = isStart(request)
        let began = DispatchTime.now().uptimeNanoseconds
        do {
            let response = try await underlying.send(request)
            let outcome = DeadlineWireTransportOutcome.response(statusCode: response.statusCode, bodyBytes: response.body.count)
            if observe { recorder.append(outcome) }
            reportMetric(outcome, request: request, observesStart: observe, began: began)
            return response
        } catch {
            let outcome: DeadlineWireTransportOutcome
            if error is CancellationError { outcome = .cancellationError }
            else if (error as? URLError)?.code == .cancelled { outcome = .urlCancelled }
            else if (error as? URLError)?.code == .timedOut { outcome = .timedOut }
            else { outcome = .otherFailure }
            if observe { recorder.append(outcome) }
            reportMetric(outcome, request: request, observesStart: observe, began: began)
            throw error
        }
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        try await underlying.download(request)
    }

    private func isStart(_ request: URLRequest) -> Bool {
        guard let startPath, request.url?.path == startPath else { return false }
        if startPath.hasSuffix("/bulb/start") { return true }
        // Direct CCAPI uses the same path for press and release. Inspect only the
        // synthetic action discriminator; never retain or log request bodies.
        guard let data = request.httpBody,
              let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return false }
        return body["action"] as? String == "full_press"
    }

    private func reportMetric(
        _ outcome: DeadlineWireTransportOutcome, request: URLRequest, observesStart: Bool, began: UInt64
    ) {
        let phase = request.url.flatMap { DeadlineWireIdlePhase(rawValue: $0.path) }
        guard observesStart || phase != nil else { return }
        let elapsed = Double(DispatchTime.now().uptimeNanoseconds - began) / 1_000_000_000
        let label = observesStart ? "start" : phase!.metricLabel
        print("deadline-wire \(label) configuredIdleSeconds=\(Self.idleInterval) elapsedSeconds=\(elapsed) \(outcome.metricFields)")
    }
}

private enum DeadlineWireTransportOutcome: Equatable, Sendable {
    case response(statusCode: Int, bodyBytes: Int)
    case cancellationError
    case urlCancelled
    case timedOut
    case otherFailure

    var metricFields: String {
        switch self {
        case .response(let status, let count): return "category=response status=\(status) count=\(count)"
        case .cancellationError: return "category=CancellationError"
        case .urlCancelled: return "category=URLError.cancelled"
        case .timedOut: return "category=URLError.timedOut"
        case .otherFailure: return "category=otherFailure"
        }
    }
}

/// Observation only: do not transform the production transport's response or error.
/// The recorder retains only outcome categories, HTTP status and body byte counts.
private final class DeadlineWireTransportRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var outcomes: [DeadlineWireTransportOutcome] = []
    func append(_ outcome: DeadlineWireTransportOutcome) {
        lock.lock()
        outcomes.append(outcome)
        lock.unlock()
    }
    func snapshot() -> [DeadlineWireTransportOutcome] {
        lock.lock()
        defer { lock.unlock() }
        return outcomes
    }
}

/// Bounded XCTest waits must not then await a still-running Task.value indefinitely.
private final class DeadlineWireJob<Value: Sendable>: @unchecked Sendable {
    let finished: XCTestExpectation
    private let result: DeadlineWireResult<Value>
    private let task: Task<Void, Never>

    init(_ description: String, operation: @escaping @Sendable () async throws -> Value) {
        let result = DeadlineWireResult<Value>()
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
        guard let value = result.get() else { throw DeadlineWireError.operationDidNotFinish }
        return try value.get()
    }
}

private final class DeadlineWireResult<Value: Sendable>: @unchecked Sendable {
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

private enum DeadlineWireError: Error {
    case systemCall(String, Int32)
    case malformedRequest
    case operationDidNotFinish
}

private enum DeadlineWireIdlePhase: String, CaseIterable, Sendable {
    case beforeHeaders = "/idle-before-headers"
    case body = "/idle-body"

    var metricLabel: String {
        switch self {
        case .beforeHeaders: return "no-drip-before-headers"
        case .body: return "no-drip-body"
        }
    }
}

private struct DeadlineWireEndpoint: Sendable {
    let version: String
    let method: String
    static let variants = [
        DeadlineWireEndpoint(version: "ver110", method: "PUT"),
        DeadlineWireEndpoint(version: "ver130", method: "POST"),
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

private struct DeadlineWireRequest: Sendable {
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

private extension Array where Element == DeadlineWireRequest {
    var shutterActions: [String] { compactMap(\.action) }
}

/// Every accepted socket gets its own worker. The held press response cannot prevent
/// this peer from accepting and recording a release/DELETE on a different connection.
private final class DeadlineWirePeer: @unchecked Sendable {
    let baseURL: String
    let pressReceived = XCTestExpectation(description: "peer consumed full press and began body")
    let idleBeforeHeadersEntered = XCTestExpectation(description: "peer withholds all response headers")
    let idleBodyEntered = XCTestExpectation(description: "peer sent headers and one body byte, then went silent")
    let dripCheckpoint = XCTestExpectation(description: "body kept arriving beyond three configured idle intervals")
    private let listener: Int32
    private let endpoint: DeadlineWireEndpoint?
    private let condition = NSCondition()
    private let workers = DispatchGroup()
    private var stopped = false
    private var completionOpen = false
    private var sockets = Set<Int32>()
    private var recorded: [DeadlineWireRequest] = []
    private var failures: [String] = []
    private var checkpointElapsed: TimeInterval = 0
    private static let maximumHold: TimeInterval = 8
    private static let paddingBytes = 256
    private static let directStartBody = Data("{}".utf8)
    private static let bridgeStartBody = Data(#"{"bulbExposureActive":true,"shutterReleaseUnconfirmed":false}"#.utf8)

    var expectedStartBodyBytes: Int {
        (endpoint == nil ? Self.bridgeStartBody.count : Self.directStartBody.count) + Self.paddingBytes
    }

    init(endpoint: DeadlineWireEndpoint? = nil) throws {
        self.endpoint = endpoint
        let socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { throw DeadlineWireError.systemCall("socket", errno) }
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
            throw DeadlineWireError.systemCall("bind/listen", errno)
        }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.getsockname(socket, $0, &length) }
        }
        guard named == 0 else { throw DeadlineWireError.systemCall("getsockname", errno) }
        baseURL = "http://127.0.0.1:\(UInt16(bigEndian: address.sin_port))"
        listener = socket
        initialized = true
        workers.enter()
        DispatchQueue(label: "OpenEOSCoreTests.deadline-accept.\(UUID().uuidString)").async { [self] in
            acceptConnections()
            workers.leave()
        }
    }

    var completionWasOpened: Bool { locked { completionOpen } }
    var dripElapsed: TimeInterval { locked { checkpointElapsed } }
    func requests() -> [DeadlineWireRequest] { locked { recorded } }
    func errors() -> [String] { locked { failures } }
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
            if request.method == "GET", let phase = DeadlineWireIdlePhase(rawValue: request.path) {
                switch phase {
                case .beforeHeaders:
                    idleBeforeHeadersEntered.fulfill()
                case .body:
                    try sendAll(Data("HTTP/1.1 200 OK\r\nContent-Length: 64\r\nConnection: close\r\n\r\n{".utf8), socket)
                    idleBodyEntered.fulfill()
                }
                condition.lock()
                let deadline = Date().addingTimeInterval(Self.maximumHold)
                while !stopped, Date() < deadline { _ = condition.wait(until: deadline) }
                condition.unlock()
            } else if request.method == "GET", request.path == "/fixture-probe" {
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
                    try sendHTTP(204, Data(), socket)
                } else {
                    recordFailure("Unexpected direct mutation: \(request.method) \(request.path)")
                    try sendHTTP(400, Data("{}".utf8), socket)
                }
            } else {
                switch (request.method, request.path) {
                case ("GET", "/health"):
                    try sendHTTP(200, Data(#"{"ok":true,"service":"open-eos-control-bridge"}"#.utf8), socket)
                case ("POST", "/v1/session"):
                    try sendHTTP(201, Data(#"{"id":"deadline-fixture","engine":"ccapi"}"#.utf8), socket)
                case ("POST", "/v1/session/deadline-fixture/bulb/start"):
                    try dripBody(Self.bridgeStartBody, socket)
                case ("DELETE", "/v1/session/deadline-fixture"):
                    try sendHTTP(204, Data(), socket)
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
        var checkpointSent = false
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
            } catch let DeadlineWireError.systemCall(_, code) where code == EPIPE || code == ECONNRESET {
                // Cancellation intentionally closes this socket with the barrier shut.
                return
            }
            if !checkpointSent, elapsed >= ShortIdleWireTransport.dripCheckpointInterval {
                checkpointSent = true
                locked { checkpointElapsed = elapsed }
                dripCheckpoint.fulfill()
            }
        }
    }

    private func readRequest(_ socket: Int32) throws -> DeadlineWireRequest {
        var bytes = Data()
        var headerEnd: Int?
        var bodyLength = 0
        var method = ""
        var path = ""
        while true {
            if let headerEnd, bytes.count >= headerEnd + bodyLength {
                return DeadlineWireRequest(method: method, path: path,
                    body: bytes.subdata(in: headerEnd..<(headerEnd + bodyLength)))
            }
            var buffer = [UInt8](repeating: 0, count: 4096)
            let count = buffer.withUnsafeMutableBytes { Darwin.recv(socket, $0.baseAddress!, $0.count, 0) }
            if count < 0, errno == EINTR { continue }
            guard count > 0 else { throw DeadlineWireError.malformedRequest }
            bytes.append(contentsOf: buffer.prefix(count))
            guard bytes.count <= 64 * 1024 else { throw DeadlineWireError.malformedRequest }
            if headerEnd == nil, let range = bytes.range(of: Data("\r\n\r\n".utf8)) {
                let lines = String(decoding: bytes[..<range.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
                let first = lines.first?.split(separator: " ") ?? []
                guard first.count == 3 else { throw DeadlineWireError.malformedRequest }
                method = String(first[0])
                path = String(first[1])
                for line in lines.dropFirst() {
                    guard let colon = line.firstIndex(of: ":") else { continue }
                    let key = line[..<colon].lowercased()
                    let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                    guard key != "transfer-encoding" else { throw DeadlineWireError.malformedRequest }
                    if key == "content-length" {
                        guard let length = Int(value), (0...64 * 1024).contains(length) else {
                            throw DeadlineWireError.malformedRequest
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
                guard count > 0 else { throw DeadlineWireError.systemCall("send", errno) }
                offset += count
            }
        }
    }

    private func recordFailure(_ message: String) { locked { failures.append(message) } }
}
