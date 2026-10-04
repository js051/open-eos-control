import Darwin
import Foundation
import XCTest

@testable import OpenEOSCore

/// This fixture accepts real loopback TCP connections. In particular, URLProtocol is not
/// involved: request counts include any retries performed below CameraHTTPTransport.
final class URLSessionShutterWireTests: XCTestCase {
    func testLostPressResponsePreservesReleaseWithoutReplayingTheFullPressOnTheWire() async throws {
        for endpoint in ShutterRecoveryEndpoint.variants {
            for fault in LoopbackShutterPeer.PressFault.allCases {
                for connectionMode in LoopbackShutterPeer.ConnectionMode.allCases {
                    let peer = try LoopbackShutterPeer(
                        endpoint: endpoint, pressFault: fault, failedReleases: 1, connectionMode: connectionMode
                    )
                    defer { XCTAssertTrue(peer.stop(), "Loopback peer must terminate and release its sockets") }
                    let client = try CCAPIClient(
                        baseURL: peer.baseURL, mode: .camera, transport: URLSessionCameraHTTPTransport()
                    )

                    do {
                        _ = try await client.startBulbExposure()
                        XCTFail("A dropped or truncated press response must not report success: \(fault)")
                    } catch {
                        // The peer has already consumed full_press, so delivery remains ambiguous.
                    }

                    let unknown = await client.shutterReleaseState()
                    XCTAssertTrue(unknown.releaseRequired, "\(endpoint.method) \(fault)")
                    XCTAssertTrue(unknown.releaseUnconfirmed, "\(endpoint.method) \(fault)")
                    XCTAssertNil(unknown.bulbExposureActive)
                    let beforeRetry = peer.requests()
                    XCTAssertEqual(beforeRetry.shutterActions, ["full_press", "release"],
                                   "The actual URLSession transport must not replay the state-changing press")
                    XCTAssertEqual(beforeRetry.filter { $0.action == "full_press" }.count, 1)
                    if connectionMode == .pooled,
                       let pressIndex = beforeRetry.firstIndex(where: { $0.action == "full_press" }), pressIndex > 0 {
                        let connections = peer.connectionIDs()
                        XCTAssertEqual(beforeRetry[pressIndex - 1].method, "GET")
                        XCTAssertEqual(connections[pressIndex], connections[pressIndex - 1],
                                       "This case must exercise a real URLSession reused connection")
                    }

                    try await client.retryShutterRelease()

                    let afterRetry = peer.requests()
                    let retryRequests = Array(afterRetry.dropFirst(beforeRetry.count))
                    XCTAssertEqual(retryRequests.count, 1, "Recovery must send only the retained release")
                    XCTAssertEqual(retryRequests.first?.method, endpoint.method)
                    XCTAssertEqual(retryRequests.first?.path, endpoint.manualPath)
                    XCTAssertEqual(retryRequests.first?.action, "release")
                    XCTAssertEqual(retryRequests.first?.autofocus, false)
                    XCTAssertEqual(afterRetry.shutterActions, ["full_press", "release", "release"])
                    let released = await client.shutterReleaseState()
                    XCTAssertEqual(released, .idle)

                    await client.close()
                    XCTAssertEqual(peer.requests(), afterRetry, "Closing an acknowledged recovery must not issue another press or release")
                    XCTAssertTrue(peer.errors().isEmpty, peer.errors().joined(separator: "\n"))
                }
            }
        }
    }

    func testClosePerformsRetainedReleaseAfterThePeerConsumesAnUnacknowledgedPress() async throws {
        let endpoint = ShutterRecoveryEndpoint.variants[1]
        let peer = try LoopbackShutterPeer(endpoint: endpoint, pressFault: .truncatedResponse, failedReleases: 1)
        defer { XCTAssertTrue(peer.stop()) }
        let client = try CCAPIClient(
            baseURL: peer.baseURL, mode: .camera, transport: URLSessionCameraHTTPTransport()
        )
        do {
            _ = try await client.startBulbExposure()
            XCTFail("Expected the deliberately truncated press response to fail")
        } catch {}
        let beforeClose = peer.requests()
        XCTAssertEqual(beforeClose.shutterActions, ["full_press", "release"])

        let closed = await client.closeWithShutterReleaseState()

        XCTAssertEqual(closed, .idle)
        let afterClose = peer.requests()
        let closeRequests = Array(afterClose.dropFirst(beforeClose.count))
        XCTAssertEqual(closeRequests.count, 1)
        XCTAssertEqual(closeRequests.first?.method, "POST")
        XCTAssertEqual(closeRequests.first?.path, endpoint.manualPath)
        XCTAssertEqual(closeRequests.first?.action, "release")
        XCTAssertEqual(afterClose.filter { $0.action == "full_press" }.count, 1)
        XCTAssertTrue(peer.errors().isEmpty, peer.errors().joined(separator: "\n"))
    }
}

private enum LoopbackShutterPeerError: Error {
    case systemCall(String, Int32)
    case malformedRequest
    case requestTooLarge
    case incompleteRequest
}

private final class LoopbackShutterPeer: @unchecked Sendable {
    enum PressFault: CaseIterable {
        case disconnect
        case truncatedResponse
    }

    enum ConnectionMode: CaseIterable, Equatable {
        case fresh
        case pooled
    }

    let baseURL: String
    private let listener: Int32
    private let endpoint: ShutterRecoveryEndpoint
    private let pressFault: PressFault
    private let failedReleases: Int
    private let connectionMode: ConnectionMode
    private let lock = NSLock()
    private let finished = DispatchGroup()
    private var stopped = false
    private var activeConnection: Int32?
    private var recorded: [ShutterRecoveryRequest] = []
    private var recordedConnectionIDs: [Int] = []
    private var failures: [String] = []
    // Used only on the serial socket-serving queue.
    private var releaseCount = 0

    init(
        endpoint: ShutterRecoveryEndpoint,
        pressFault: PressFault,
        failedReleases: Int,
        connectionMode: ConnectionMode = .fresh
    ) throws {
        self.endpoint = endpoint
        self.pressFault = pressFault
        self.failedReleases = failedReleases
        self.connectionMode = connectionMode
        let socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { throw LoopbackShutterPeerError.systemCall("socket", errno) }
        var initialized = false
        defer { if !initialized { Darwin.close(socket) } }

        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let bound = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else { throw LoopbackShutterPeerError.systemCall("bind", errno) }
        guard Darwin.listen(socket, 16) == 0 else { throw LoopbackShutterPeerError.systemCall("listen", errno) }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.getsockname(socket, $0, &length)
            }
        }
        guard named == 0 else { throw LoopbackShutterPeerError.systemCall("getsockname", errno) }
        baseURL = "http://127.0.0.1:\(UInt16(bigEndian: address.sin_port))"
        listener = socket
        initialized = true

        finished.enter()
        DispatchQueue(label: "OpenEOSCoreTests.shutter-loopback.\(UUID().uuidString)").async { [self] in
            serve()
            finished.leave()
        }
    }

    @discardableResult
    func stop() -> Bool {
        lock.lock()
        stopped = true
        if let activeConnection { _ = Darwin.shutdown(activeConnection, SHUT_RDWR) }
        lock.unlock()
        return finished.wait(timeout: .now() + 5) == .success
    }

    func requests() -> [ShutterRecoveryRequest] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    func errors() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return failures
    }

    func connectionIDs() -> [Int] {
        lock.lock()
        defer { lock.unlock() }
        return recordedConnectionIDs
    }

    private var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    private func serve() {
        defer { Darwin.close(listener) }
        var connectionNumber = 0
        while !isStopped {
            // Poll bounds teardown without closing/reusing a descriptor under accept().
            var ready = pollfd(fd: listener, events: Int16(POLLIN), revents: 0)
            let result = Darwin.poll(&ready, 1, 100)
            if result < 0 {
                if errno == EINTR { continue }
                recordFailure("poll failed: \(errno)")
                return
            }
            if result == 0 || isStopped { continue }
            let connection = Darwin.accept(listener, nil, nil)
            if connection < 0 {
                if errno == EINTR { continue }
                recordFailure("accept failed: \(errno)")
                return
            }
            connectionNumber += 1
            lock.lock()
            activeConnection = connection
            lock.unlock()
            var timeout = timeval(tv_sec: 3, tv_usec: 0)
            var noSignal: Int32 = 1
            _ = setsockopt(connection, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            _ = setsockopt(connection, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            _ = setsockopt(connection, SOL_SOCKET, SO_NOSIGPIPE, &noSignal, socklen_t(MemoryLayout<Int32>.size))
            do {
                while !isStopped {
                    let request = try readRequest(connection)
                    lock.lock()
                    recorded.append(request)
                    recordedConnectionIDs.append(connectionNumber)
                    lock.unlock()
                    let keepAlive = try reply(to: request, on: connection)
                    if !keepAlive { break }
                }
            } catch {
                if !isStopped { recordFailure("Loopback peer failed: \(error)") }
            }
            lock.lock()
            activeConnection = nil
            _ = Darwin.shutdown(connection, SHUT_RDWR)
            Darwin.close(connection)
            lock.unlock()
        }
    }

    private func readRequest(_ socket: Int32) throws -> ShutterRecoveryRequest {
        var bytes = Data()
        let delimiter = Data("\r\n\r\n".utf8)
        var headerEnd: Int?
        var bodyLength = 0
        var method = ""
        var path = ""
        while true {
            if let headerEnd, bytes.count >= headerEnd + bodyLength {
                return ShutterRecoveryRequest(
                    method: method, path: path,
                    body: bytes.subdata(in: headerEnd..<(headerEnd + bodyLength)),
                    taskWasCancelled: false
                )
            }
            var buffer = [UInt8](repeating: 0, count: 4096)
            let count = buffer.withUnsafeMutableBytes { raw in
                Darwin.recv(socket, raw.baseAddress!, raw.count, 0)
            }
            guard count > 0 else { throw LoopbackShutterPeerError.incompleteRequest }
            bytes.append(contentsOf: buffer.prefix(count))
            guard bytes.count <= 64 * 1024 else { throw LoopbackShutterPeerError.requestTooLarge }
            if headerEnd == nil, let range = bytes.range(of: delimiter) {
                let header = String(decoding: bytes[..<range.lowerBound], as: UTF8.self)
                let lines = header.components(separatedBy: "\r\n")
                let first = lines.first?.split(separator: " ") ?? []
                guard first.count == 3 else { throw LoopbackShutterPeerError.malformedRequest }
                method = String(first[0])
                path = String(first[1])
                for line in lines.dropFirst() {
                    guard let separator = line.firstIndex(of: ":") else { continue }
                    let key = line[..<separator].lowercased()
                    let value = line[line.index(after: separator)...].trimmingCharacters(in: .whitespaces)
                    if key == "content-length" {
                        guard let length = Int(value), (0...64 * 1024).contains(length) else {
                            throw LoopbackShutterPeerError.malformedRequest
                        }
                        bodyLength = length
                    }
                    guard key != "transfer-encoding" else {
                        throw LoopbackShutterPeerError.malformedRequest
                    }
                }
                headerEnd = range.upperBound
            }
        }
    }

    /// Returns true only when the next request should be read from the same TCP socket.
    private func reply(to request: ShutterRecoveryRequest, on socket: Int32) throws -> Bool {
        if request.method == "GET" {
            let response = endpoint.readResponse(path: request.path)
            guard response.statusCode == 200 else {
                recordFailure("Unexpected read: \(request.method) \(request.path)")
                try sendHTTP(status: 404, body: Data("{}".utf8), on: socket)
                return false
            }
            let keepAlive = connectionMode == .pooled
            try sendHTTP(status: 200, body: response.body, on: socket, keepAlive: keepAlive)
            return keepAlive
        }
        guard request.path == endpoint.manualPath, request.method == endpoint.method,
              request.autofocus == false else {
            recordFailure("Unexpected mutation: \(request.method) \(request.path)")
            try sendHTTP(status: 400, body: Data("{}".utf8), on: socket)
            return false
        }
        switch request.action {
        case "full_press":
            // readRequest has consumed the complete JSON body before either failure is injected.
            switch pressFault {
            case .disconnect:
                return false
            case .truncatedResponse:
                try sendAll(Data("HTTP/1.1 200 OK\r\nContent-Length: 64\r\nConnection: close\r\n\r\n{".utf8), on: socket)
            }
        case "release":
            releaseCount += 1
            if releaseCount <= failedReleases {
                try sendHTTP(status: 503, body: Data(#"{"message":"release acknowledgement unavailable"}"#.utf8), on: socket)
            } else {
                try sendHTTP(status: 204, body: Data(), on: socket)
            }
        default:
            recordFailure("Unexpected shutter action: \(request.action ?? "missing")")
            try sendHTTP(status: 400, body: Data("{}".utf8), on: socket)
        }
        return false
    }

    private func sendHTTP(status: Int, body: Data, on socket: Int32, keepAlive: Bool = false) throws {
        let reason = status == 204 ? "No Content" : status == 200 ? "OK" : "Failure"
        let connectionHeader = keepAlive ? "keep-alive" : "close"
        var response = Data(
            "HTTP/1.1 \(status) \(reason)\r\nContent-Type: application/json\r\nContent-Length: \(body.count)\r\nConnection: \(connectionHeader)\r\n\r\n".utf8
        )
        response.append(body)
        try sendAll(response, on: socket)
    }

    private func sendAll(_ bytes: Data, on socket: Int32) throws {
        try bytes.withUnsafeBytes { buffer in
            guard let base = buffer.baseAddress else { return }
            var offset = 0
            while offset < buffer.count {
                let count = Darwin.send(socket, base.advanced(by: offset), buffer.count - offset, 0)
                if count < 0, errno == EINTR { continue }
                guard count > 0 else { throw LoopbackShutterPeerError.systemCall("send", errno) }
                offset += count
            }
        }
    }

    private func recordFailure(_ message: String) {
        lock.lock()
        failures.append(message)
        lock.unlock()
    }
}
