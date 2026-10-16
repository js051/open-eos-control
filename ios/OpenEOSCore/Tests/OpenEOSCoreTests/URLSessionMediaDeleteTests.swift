import Foundation
import Network
import XCTest
@testable import OpenEOSCore

/// Native loopback gate. No real camera or host files. These tests are authored,
/// not executed on the Linux authoring host; source guards are not wire evidence.
final class URLSessionMediaDeleteTests: XCTestCase {
    func testRealDELETE204RefusalRedirectLostACKAndTruncatedBodyDispatchOnlyOnce() async throws {
        for mode in DeleteWirePeer.Mode.allCases {
            let peer = try DeleteWirePeer(mode: mode)
            try await peer.start()
            defer { peer.stop() }
            let transport = URLSessionCameraHTTPTransport()
            var request = URLRequest(url: URL(string: peer.baseURL + "/exact-object")!)
            request.httpMethod = "DELETE"
            let attempt = CameraMediaDeleteAttempt()
            try attempt.markDispatched()
            do { try attempt.record(try await transport.sendMediaDelete(request)) }
            catch {}
            let expected: CameraMediaDeleteOutcome = mode == .confirmed ? .confirmed : mode == .refused ? .rejected : .uncertain
            XCTAssertEqual(attempt.outcome, expected, mode.rawValue)
            XCTAssertEqual(peer.targets(), ["/exact-object"], "No redirect, retry or recovery DELETE: \(mode.rawValue)")
        }
    }
}
private final class DeleteWirePeer: @unchecked Sendable {
    enum Mode: String, CaseIterable { case confirmed, refused, accepted, redirect307, redirect308, lostACK, shortBody, serverError }
    private let mode: Mode
    private let listener: NWListener
    private let queue = DispatchQueue(label: "eos.synthetic-delete-wire")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var received: [String] = []
    var baseURL: String { "http://127.0.0.1:\(listener.port!.rawValue)" }
    init(mode: Mode) throws {
        self.mode = mode
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        listener = try NWListener(using: parameters)
    }
    func targets() -> [String] { lock.lock(); defer { lock.unlock() }; return received }
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
    private func read(_ connection: NWConnection, bytes: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] chunk, _, complete, error in
            guard let self else { connection.cancel(); return }
            let data = bytes + (chunk ?? Data())
            guard let headerEnd = data.range(of: Data("\r\n\r\n".utf8)) else {
                if complete || error != nil || data.count > 32_768 { connection.cancel() }
                else { self.read(connection, bytes: data) }
                return
            }
            let header = String(decoding: data[..<headerEnd.lowerBound], as: UTF8.self)
            let first = header.components(separatedBy: "\r\n")[0].split(separator: " ")
            guard first.count >= 2, first[0] == "DELETE" else { connection.cancel(); return }
            self.lock.lock(); self.received.append(String(first[1])); self.lock.unlock()
            if self.mode == .lostACK { connection.cancel(); return }
            let response: String
            switch self.mode {
            case .confirmed: response = "204 No Content\r\n"
            case .refused: response = "403 Forbidden\r\nContent-Length: 0\r\n"
            case .accepted: response = "202 Accepted\r\nContent-Length: 0\r\n"
            case .redirect307: response = "307 Temporary Redirect\r\nLocation: /must-not-delete\r\nContent-Length: 0\r\n"
            case .redirect308: response = "308 Permanent Redirect\r\nLocation: /must-not-delete\r\nContent-Length: 0\r\n"
            case .shortBody: response = "403 Forbidden\r\nContent-Length: 8\r\n"
            case .serverError: response = "503 Service Unavailable\r\nContent-Length: 0\r\n"
            case .lostACK: return
            }
            let payload = Data(("HTTP/1.1 " + response + "Connection: close\r\n\r\n" + (self.mode == .shortBody ? "x" : "")).utf8)
            connection.send(content: payload, completion: .contentProcessed { _ in connection.cancel() })
        }
    }
}
