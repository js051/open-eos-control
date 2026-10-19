import Foundation
import Network
import XCTest
@testable import OpenEOSCore

/// Authored native URLSession tests, NOT_RUN without macOS/Swift. A source check
/// cannot establish socket replay, cancellation, or physical-camera acceptance.
final class URLSessionRecordingLifecycleTests: XCTestCase {
    func testReadOnlyBodyIsRejectedBeforeDispatch() async throws {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:1/must-not-send")!)
        request.httpMethod = "GET"
        request.httpBody = Data("{}".utf8)
        do {
            _ = try await URLSessionCameraHTTPTransport().sendRecordingCommand(request)
            XCTFail("A read-only request must not silently discard its body")
        } catch CCAPIError.invalidResponse {
            // Pre-dispatch validation, not a connection failure or retry.
        } catch {
            XCTFail("Expected pre-dispatch rejection, got \(error)")
        }
    }

    func testRecordingCommandsNeverRedirectAuthenticateOrReplay() async throws {
        for method in ["POST", "PUT"] {
            for mode in RecordingLifecycleWirePeer.Mode.allCases where mode != .heldPartial {
                let peer = try RecordingLifecycleWirePeer(mode: mode)
                try await peer.start()
                defer { peer.stop() }
                var request = URLRequest(url: URL(string: peer.baseURL + "/exact-recording")!)
                request.httpMethod = method
                request.httpBody = Data(#"{"action":"start"}"#.utf8)
                do {
                    let response = try await URLSessionCameraHTTPTransport().sendRecordingCommand(request)
                    if mode == .responded { XCTAssertTrue(response.bodyComplete) }
                    if mode == .oversized || mode == .truncated { XCTAssertFalse(response.bodyComplete) }
                    if mode == .redirect307 { XCTAssertEqual(response.response.statusCode, 307) }
                    if mode == .redirect308 { XCTAssertEqual(response.response.statusCode, 308) }
                    if mode == .malformed { XCTAssertThrowsError(try CameraRecordingWire.decode(response.response.body)) }
                } catch { XCTAssertTrue(mode == .lostResponse || mode == .authentication) }
                XCTAssertEqual(peer.targets(), ["/exact-recording"], "No redirect, retry, or extra body: \(method) \(mode.rawValue)")
                XCTAssertEqual(peer.bodies(), [Data(#"{"action":"start"}"#.utf8)])
            }
        }
    }
    func testRecordingRecoveryIsReadOnlyAndCannotFollowRedirect() async throws {
        let peer = try RecordingLifecycleWirePeer(mode: .redirect307)
        try await peer.start()
        defer { peer.stop() }
        var request = URLRequest(url: URL(string: peer.baseURL + "/recording/observation")!)
        request.httpMethod = "GET"
        let response = try await URLSessionCameraHTTPTransport().sendRecordingStatus(request)
        XCTAssertEqual(response.statusCode, 307)
        XCTAssertEqual(peer.targets(), ["/recording/observation"])
        XCTAssertEqual(peer.bodies(), [Data()])
    }
    func testRecordingTotalDeadlineBoundsHeldBodyWithoutReplay() async throws {
        for method in ["GET", "POST"] {
            let peer = try RecordingLifecycleWirePeer(mode: .heldPartial)
            try await peer.start()
            defer { peer.stop() }
            var request = URLRequest(url: URL(string: peer.baseURL + "/bounded-recording")!)
            request.httpMethod = method
            request.timeoutInterval = 0.25
            if method == "POST" { request.httpBody = Data("{}".utf8) }
            let start = ContinuousClock.now
            do {
                let exchange = try await URLSessionCameraHTTPTransport().sendRecordingCommand(request)
                XCTAssertFalse(exchange.bodyComplete)
            } catch { XCTAssertTrue((error as? URLError)?.code == .timedOut) }
            XCTAssertLessThan(start.duration(to: .now), .seconds(2))
            XCTAssertEqual(peer.targets(), ["/bounded-recording"])
            XCTAssertEqual(peer.bodies().count, 1)
        }
    }
}

private final class RecordingLifecycleWirePeer: @unchecked Sendable {
    enum Mode: String, CaseIterable { case responded, redirect307, redirect308, authentication, lostResponse, truncated, oversized, malformed, heldPartial }
    private let mode: Mode
    private let listener: NWListener
    private let queue = DispatchQueue(label: "eos.synthetic-recording-lifecycle")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var paths: [String] = []
    private var receivedBodies: [Data] = []
    private var sentPartial = false
    var baseURL: String { "http://127.0.0.1:\(listener.port!.rawValue)" }
    init(mode: Mode) throws {
        self.mode = mode
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        listener = try NWListener(using: parameters)
    }
    func targets() -> [String] { lock.lock(); defer { lock.unlock() }; return paths }
    func partialResponseSent() -> Bool { lock.lock(); defer { lock.unlock() }; return sentPartial }
    func bodies() -> [Data] { lock.lock(); defer { lock.unlock() }; return receivedBodies }
    func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            listener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready: self?.listener.stateUpdateHandler = nil; continuation.resume()
                case let .failed(error): self?.listener.stateUpdateHandler = nil; continuation.resume(throwing: error)
                default: break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                guard let self else { connection.cancel(); return }
                self.lock.lock(); self.connections.append(connection); self.lock.unlock()
                connection.start(queue: self.queue)
                self.read(connection, bytes: Data())
            }
            listener.start(queue: queue)
        }
    }
    func stop() {
        listener.cancel()
        lock.lock(); let active = connections; connections = []; lock.unlock()
        active.forEach { $0.cancel() }
    }
    private func read(_ connection: NWConnection, bytes: Data, headerRecorded: Bool = false) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] chunk, _, complete, error in
            guard let self else { connection.cancel(); return }
            let data = bytes + (chunk ?? Data())
            guard let headerEnd = data.range(of: Data("\r\n\r\n".utf8)) else {
                if complete || error != nil || data.count > 32_768 { connection.cancel() }
                else { self.read(connection, bytes: data, headerRecorded: headerRecorded) }
                return
            }
            let header = String(decoding: data[..<headerEnd.lowerBound], as: UTF8.self)
            let lines = header.components(separatedBy: "\r\n")
            let first = lines[0].split(separator: " ")
            let length = lines.first { $0.lowercased().hasPrefix("content-length:") }
                .flatMap { Int($0.split(separator: ":", maxSplits: 1)[1].trimmingCharacters(in: .whitespaces)) } ?? 0
            guard first.count >= 2, ["GET", "POST", "PUT", "PATCH"].contains(String(first[0])), length >= 0 else { connection.cancel(); return }
            if !headerRecorded {
                self.lock.lock(); self.paths.append(String(first[1])); self.lock.unlock()
            }
            guard data.count >= headerEnd.upperBound + length else {
                if complete || error != nil { connection.cancel() }
                else { self.read(connection, bytes: data, headerRecorded: true) }
                return
            }
            self.lock.lock()
            self.receivedBodies.append(Data(data[headerEnd.upperBound..<(headerEnd.upperBound + length)]))
            self.lock.unlock()
            if self.mode == .lostResponse { connection.cancel(); return }
            let body: String
            let status: String
            let extra: String
            switch self.mode {
            case .responded: status = "200 OK"; extra = ""; body = "{}"
            case .redirect307: status = "307 Temporary Redirect"; extra = "Location: /must-not-replay\r\n"; body = ""
            case .redirect308: status = "308 Permanent Redirect"; extra = "Location: /must-not-replay\r\n"; body = ""
            case .authentication: status = "401 Unauthorized"; extra = "WWW-Authenticate: Basic realm=fixture\r\n"; body = ""
            case .truncated: status = "200 OK"; extra = ""; body = "{"
            case .oversized: status = "200 OK"; extra = ""; body = String(repeating: "x", count: 32_769)
            case .malformed: status = "200 OK"; extra = ""; body = #"{"version":1,"version":1}"#
            case .heldPartial: status = "200 OK"; extra = ""; body = #"{"value":"800"}"#
            case .lostResponse: return
            }
            let lengthOut = [Mode.truncated, .heldPartial].contains(self.mode) ? 99 : body.utf8.count
            let payload = Data("HTTP/1.1 \(status)\r\n\(extra)Content-Type: application/json\r\nContent-Length: \(lengthOut)\r\nConnection: close\r\n\r\n\(body)".utf8)
            connection.send(content: payload, completion: .contentProcessed { _ in
                if self.mode == .heldPartial {
                    self.lock.lock(); self.sentPartial = true; self.lock.unlock()
                } else { connection.cancel() }
            })
        }
    }
}
