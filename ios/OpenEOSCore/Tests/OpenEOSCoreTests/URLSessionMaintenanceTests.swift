import Foundation
import Network
import XCTest
@testable import OpenEOSCore

/// Authored native loopback coverage. Source validation on Linux does not execute
/// URLSession or establish physical-camera transport acceptance.
final class URLSessionMaintenanceTests: XCTestCase {
    func testMaintenancePOSTPUTAndDELETENeverFollowRedirectOrReplayBody() async throws {
        for method in ["POST", "PUT", "DELETE"] {
            for mode in MaintenanceWirePeer.Mode.allCases where mode != .heldPartial {
                let peer = try MaintenanceWirePeer(mode: mode)
                try await peer.start()
                defer { peer.stop() }
                let transport = URLSessionCameraHTTPTransport()
                var request = URLRequest(url: URL(string: peer.baseURL + "/exact-maintenance")!)
                request.httpMethod = method
                request.httpBody = Data(#"{"autoPowerOff":false}"#.utf8)
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
                try attempt.markDispatched()
                do {
                    let response = try await transport.sendMaintenance(request)
                    if response.statusCode == 200, let object = try? decodeForegroundJSONObject(response.body) {
                        _ = CameraMaintenanceWire.outcome(object, engine: "ccapi", attempt: attempt)
                    }
                } catch {}
                XCTAssertEqual(attempt.snapshot.commandEvidence, mode == .acknowledged ? .responded : .unknown, mode.rawValue)
                XCTAssertEqual(peer.targets(), ["/exact-maintenance"], "No redirect, retry or new body: \(method) \(mode.rawValue)")
                XCTAssertEqual(peer.bodies(), [Data(#"{"autoPowerOff":false}"#.utf8)])
            }
        }
    }
    func testCancellationDuringIncompleteNativeResponseNeverCreatesACKOrReplays() async throws {
        for method in ["GET", "POST", "PUT", "DELETE"] {
            let peer = try MaintenanceWirePeer(mode: .heldPartial)
            try await peer.start()
            defer { peer.stop() }
            let transport = URLSessionCameraHTTPTransport()
            var request = URLRequest(url: URL(string: peer.baseURL + "/cancel-original")!)
            request.httpMethod = method
            request.timeoutInterval = 5
            if method != "GET" { request.httpBody = Data(#"{"autoPowerOff":false}"#.utf8) }
            let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
            if method != "GET" { try attempt.markDispatched() }
            let task = Task { try await transport.sendMaintenance(request) }
            let deadline = ContinuousClock.now.advanced(by: .seconds(5))
            while !peer.partialResponseSent(), ContinuousClock.now < deadline {
                try await Task.sleep(for: .milliseconds(1))
            }
            XCTAssertTrue(peer.partialResponseSent(), "Peer sent JSON bytes without complete EOF")
            task.cancel()
            do { _ = try await task.value; XCTFail("An incomplete cancelled body is not a response") } catch {}
            XCTAssertEqual(attempt.snapshot.commandEvidence, method == "GET" ? .notSent : .unknown)
            XCTAssertEqual(peer.targets(), ["/cancel-original"])
            XCTAssertEqual(peer.bodies().count, 1)
        }
    }

    func testMaintenanceControlDependenciesNeverRedirectOrAuthenticateAgain() async throws {
        for (method, path) in [("DELETE", "/v1/session/original/maintenance/control/events"),
                               ("POST", "/v1/session/original/maintenance/control/liveview/stop"),
                               ("POST", "/v1/session/original/maintenance/control/liveview/start"),
                               ("DELETE", "/ccapi/ver100/shooting/liveview"),
                               ("POST", "/ccapi/ver100/shooting/liveview/rtp")] {
            for mode in [MaintenanceWirePeer.Mode.redirect307, .redirect308, .authentication] {
                let peer = try MaintenanceWirePeer(mode: mode)
                try await peer.start(); defer { peer.stop() }
                var request = URLRequest(url: URL(string: peer.baseURL + path)!)
                request.httpMethod = method; request.timeoutInterval = 15
                if method == "POST" { request.httpBody = Data("{}".utf8) }
                do { _ = try await URLSessionCameraHTTPTransport().sendMaintenance(request) } catch {}
                XCTAssertEqual(peer.targets(), [path])
                XCTAssertEqual(peer.bodies().count, 1)
            }
        }
    }

    func testMaintenanceStreamReadinessRefusesRedirectAndAuthenticationReplay() async throws {
        for mode in [MaintenanceWirePeer.Mode.redirect307, .redirect308, .authentication] {
            let peer = try MaintenanceWirePeer(mode: mode)
            try await peer.start(); defer { peer.stop() }
            let path = "/ccapi/ver100/shooting/liveview/multipart"
            var request = URLRequest(url: URL(string: peer.baseURL + path)!)
            request.httpMethod = "GET"; request.timeoutInterval = 15
            do {
                let response = try await URLSessionCameraHTTPTransport().openMaintenanceStream(request)
                response.cancel()
                XCTAssertNotEqual(response.statusCode, 200)
            } catch {}
            XCTAssertEqual(peer.targets(), [path])
            XCTAssertEqual(peer.bodies(), [Data()])
        }
    }

    func testTricklingMultipartHeadersHaveWallDeadlineBeforeStreamHandoff() async throws {
        let peer = try MaintenanceWirePeer(mode: .acknowledged, slowHeaders: true)
        try await peer.start(); defer { peer.stop() }
        var request = URLRequest(url: URL(string: peer.baseURL + "/slow-multipart-headers")!)
        request.httpMethod = "GET"; request.timeoutInterval = 0.2
        let started = ContinuousClock.now
        do {
            let response = try await URLSessionCameraHTTPTransport().openMaintenanceStream(request)
            response.cancel(); XCTFail("Incomplete headers cannot be handed to a stream")
        } catch { XCTAssertEqual((error as? URLError)?.code, .timedOut) }
        XCTAssertLessThan(started.duration(to: .now), .seconds(2))
        XCTAssertEqual(peer.targets(), ["/slow-multipart-headers"])
        XCTAssertEqual(peer.bodies(), [Data()])
    }

    func testReadOnlyTransportAlsoRefusesRedirects() async throws {
        let peer = try MaintenanceWirePeer(mode: .redirect307)
        try await peer.start()
        defer { peer.stop() }
        let transport = URLSessionCameraHTTPTransport()
        var request = URLRequest(url: URL(string: peer.baseURL + "/read-only")!)
        request.httpMethod = "GET"
        let response = try await transport.sendMaintenance(request)
        XCTAssertEqual(response.statusCode, 307)
        XCTAssertEqual(peer.targets(), ["/read-only"])
        XCTAssertEqual(peer.bodies(), [Data()])
    }
}

private final class MaintenanceWirePeer: @unchecked Sendable {
    enum Mode: String, CaseIterable { case acknowledged, redirect307, redirect308, authentication, lostACK, truncated, oversized, malformed, heldPartial }
    private let mode: Mode
    private let slowHeaders: Bool
    private let listener: NWListener
    private let queue = DispatchQueue(label: "eos.synthetic-maintenance")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var paths: [String] = []
    private var receivedBodies: [Data] = []
    private var sentPartial = false
    var baseURL: String { "http://127.0.0.1:\(listener.port!.rawValue)" }
    init(mode: Mode, slowHeaders: Bool = false) throws {
        self.mode = mode
        self.slowHeaders = slowHeaders
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
    private func trickleHeader(_ connection: NWConnection) {
        queue.asyncAfter(deadline: .now() + .milliseconds(20)) { [weak self] in
            guard let self else { return }
            connection.send(content: Data("x".utf8), completion: .contentProcessed { error in
                if error == nil { self.trickleHeader(connection) }
            })
        }
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
            guard first.count >= 2, ["GET", "POST", "PUT", "DELETE"].contains(String(first[0])), length >= 0 else { connection.cancel(); return }
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
            if self.slowHeaders {
                connection.send(content: Data("HTTP/1.1 200 OK\r\nX-Slow: ".utf8), completion: .contentProcessed { error in
                    if error == nil { self.trickleHeader(connection) }
                })
                return
            }
            if self.mode == .lostACK { connection.cancel(); return }
            let body: String
            let status: String
            let extra: String
            switch self.mode {
            case .acknowledged: status = "200 OK"; extra = ""; body = #"{"version":1,"engine":"ccapi","operation":"sensor-cleaning","autoPowerOff":false,"responseEvidence":"response-received","clockReference":null,"clockObservation":null,"clockReadEvidence":"not-applicable"}"#
            case .redirect307: status = "307 Temporary Redirect"; extra = "Location: /must-not-replay\r\n"; body = ""
            case .redirect308: status = "308 Permanent Redirect"; extra = "Location: /must-not-replay\r\n"; body = ""
            case .authentication: status = "401 Unauthorized"; extra = "WWW-Authenticate: Basic realm=fixture\r\n"; body = ""
            case .truncated: status = "200 OK"; extra = ""; body = "{"
            case .oversized: status = "200 OK"; extra = ""; body = String(repeating: "x", count: 65_537)
            case .malformed: status = "200 OK"; extra = ""; body = #"{"directoryname":"ABCDE\n"}"#
            case .heldPartial: status = "200 OK"; extra = ""; body = #"{"autoPowerOff":false}"#
            case .lostACK: return
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
