import Foundation
import Network
import XCTest
@testable import OpenEOSCore

/// Real loopback POST framing/cancellation plus production Bridge validation.
/// No camera, photo library, network host, retries or shortened production timeout.
final class URLSessionMediaUploadTests: XCTestCase {
    func testRealHTTP201VerifiesExactlyOneStagedUploadAndCancelCannotRevokeReceipt() async throws {
        let peer = try UploadWirePeer(mode: .receipt)
        try await peer.start()
        defer { peer.stop() }
        let file = try source()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let client = try DesktopBridgeClient(baseURL: peer.baseURL)
        try await client.initialize()
        let attempt = CameraMediaUploadAttempt()
        let item = try await client.uploadMedia(from: file, attempt: attempt)
        attempt.cancel()
        XCTAssertEqual(attempt.outcome, .verified)
        XCTAssertEqual(attempt.receipt?.id, "wire-result")
        XCTAssertEqual(item.id, "wire-result")
        XCTAssertEqual(peer.uploadBodies(), [Data([1, 2, 3, 4])])
    }

    func testRealHTTPCompleteBodyThenLostAckOrInvalidReceiptRemainsUncertain() async throws {
        for mode in [UploadWirePeer.Mode.lostAck, .invalidReceipt, .accepted202, .shortBody] {
            let peer = try UploadWirePeer(mode: mode)
            try await peer.start()
            defer { peer.stop() }
            let file = try source()
            defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
            let client = try DesktopBridgeClient(baseURL: peer.baseURL)
            try await client.initialize()
            let attempt = CameraMediaUploadAttempt()
            do { _ = try await client.uploadMedia(from: file, attempt: attempt); XCTFail("Unverified upload accepted") }
            catch {}
            XCTAssertEqual(attempt.outcome, .uncertain)
            XCTAssertNil(attempt.receipt)
            XCTAssertEqual(peer.uploadBodies().count, 1, "Never automatically replay a possibly accepted POST")
        }
    }

    func testRealHTTPUserCancelAfterBodyHasOnePostAndNoReceipt() async throws {
        let peer = try UploadWirePeer(mode: .holdReply)
        let received = expectation(description: "The real server received the complete POST body")
        peer.whenUploadReceived { received.fulfill() }
        try await peer.start()
        defer { peer.stop() }
        let file = try source()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let client = try DesktopBridgeClient(baseURL: peer.baseURL)
        try await client.initialize()
        let attempt = CameraMediaUploadAttempt()
        let task = Task { try await client.uploadMedia(from: file, attempt: attempt) }
        defer { task.cancel() }
        await fulfillment(of: [received], timeout: 5)
        attempt.cancel(); task.cancel()
        do { _ = try await task.value; XCTFail("No response was returned by the server") } catch {}
        XCTAssertEqual(attempt.outcome, .uncertain)
        XCTAssertNil(attempt.receipt)
        XCTAssertEqual(peer.uploadBodies().count, 1)
    }

    func testRealHTTP307And308NeverReplaySelectedFileAtRedirectDestination() async throws {
        for mode in [UploadWirePeer.Mode.redirect307, .redirect308] {
            let peer = try UploadWirePeer(mode: mode)
            try await peer.start()
            defer { peer.stop() }
            let file = try source()
            defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
            let client = try DesktopBridgeClient(baseURL: peer.baseURL)
            try await client.initialize()
            let attempt = CameraMediaUploadAttempt()
            do { _ = try await client.uploadMedia(from: file, attempt: attempt); XCTFail("Upload followed a redirect") }
            catch {}
            XCTAssertEqual(attempt.outcome, .uncertain)
            XCTAssertEqual(peer.uploadBodies().count, 1)
            XCTAssertEqual(peer.redirectPosts(), 0)
        }
    }

    func testDirectAndSimulatorCCAPINeverDispatchUploads() async throws {
        for mode in [CCAPIConnectionMode.camera, .simulator] {
            let transport = MockCameraHTTPTransport()
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: mode, transport: transport)
            let attempt = CameraMediaUploadAttempt()
            do {
                _ = try await client.uploadMedia(from: URL(fileURLWithPath: "/synthetic-not-read.JPG"), attempt: attempt)
                XCTFail("CCAPI upload must remain unsupported")
            } catch { XCTAssertEqual(error as? CCAPIError, .unsupported(.mediaUpload)) }
            XCTAssertEqual(attempt.outcome, .notSent)
            let requests = await transport.requests()
            XCTAssertTrue(requests.isEmpty)
        }
    }

    private func source() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        let file = directory.appendingPathComponent("WIRE.JPG")
        try Data([1, 2, 3, 4]).write(to: file)
        return file
    }
}

private final class UploadWirePeer: @unchecked Sendable {
    enum Mode: Equatable { case receipt, lostAck, invalidReceipt, accepted202, shortBody, holdReply, redirect307, redirect308 }
    private let mode: Mode
    private let listener: NWListener
    private let queue = DispatchQueue(label: "open-eos.upload-wire-fixture")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var uploads: [Data] = []
    private var redirectedPosts = 0
    private var received: (@Sendable () -> Void)?
    var baseURL: String { "http://127.0.0.1:\(listener.port!.rawValue)" }
    init(mode: Mode) throws {
        self.mode = mode
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        listener = try NWListener(using: parameters)
    }
    func whenUploadReceived(_ action: @escaping @Sendable () -> Void) {
        lock.lock(); received = action; lock.unlock()
    }
    func redirectPosts() -> Int { lock.lock(); defer { lock.unlock() }; return redirectedPosts }
    func uploadBodies() -> [Data] { lock.lock(); defer { lock.unlock() }; return uploads }
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
                self.read(connection, accumulated: Data())
            }
            listener.start(queue: queue)
        }
    }
    func stop() {
        listener.cancel()
        lock.lock(); let pending = connections; connections = []; lock.unlock()
        pending.forEach { $0.cancel() }
    }
    private func read(_ connection: NWConnection, accumulated: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, complete, error in
            guard let self else { connection.cancel(); return }
            let bytes = accumulated + (data ?? Data())
            guard bytes.count < 64 * 1024 else { connection.cancel(); return }
            if let split = bytes.range(of: Data("\r\n\r\n".utf8)) {
                let lines = String(decoding: bytes[..<split.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
                let fields = lines[0].split(separator: " ")
                guard fields.count == 3 else { connection.cancel(); return }
                let length = lines.dropFirst().first { $0.lowercased().hasPrefix("content-length:") }
                    .flatMap { Int($0.split(separator: ":", maxSplits: 1)[1].trimmingCharacters(in: .whitespaces)) } ?? 0
                let body = Data(bytes[split.upperBound...])
                if body.count >= length {
                    self.respond(connection, method: String(fields[0]), path: String(fields[1]), body: Data(body.prefix(length)))
                    return
                }
            }
            if complete || error != nil { connection.cancel() }
            else { self.read(connection, accumulated: bytes) }
        }
    }
    private func respond(_ connection: NWConnection, method: String, path: String, body: Data) {
        if method == "GET", path == "/health" {
            send(connection, status: 200, body: #"{"ok":true,"service":"open-eos-control-bridge","version":"synthetic"}"#)
        } else if method == "POST", path == "/v1/session" {
            send(connection, status: 201, body: #"{"id":"upload-wire","engine":"libgphoto2"}"#)
        } else if method == "POST", path.hasPrefix("/v1/session/upload-wire/media?") {
            lock.lock(); uploads.append(body); let action = received; lock.unlock(); action?()
            if mode == .redirect307 || mode == .redirect308 {
                let status = mode == .redirect307 ? 307 : 308
                let response = Data("HTTP/1.1 \(status) Redirect\r\nLocation: /redirect-target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8)
                connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
                return
            }
            if mode == .holdReply { return }
            if mode == .lostAck { connection.cancel(); return }
            let name = mode == .invalidReceipt ? "WRONG.JPG" : "WIRE.JPG"
            let result = "{\"id\":\"wire-result\",\"name\":\"\(name)\",\"kind\":\"photo\",\"sizeBytes\":4}"
            send(connection, status: mode == .accepted202 ? 202 : 201, body: result, truncate: mode == .shortBody)
        } else if path == "/redirect-target" {
            lock.lock(); redirectedPosts += method == "POST" ? 1 : 0; lock.unlock()
            send(connection, status: 201, body: "{}")
        } else { send(connection, status: 404, body: "{}") }
    }
    private func send(_ connection: NWConnection, status: Int, body: String, truncate: Bool = false) {
        let data = Data(body.utf8)
        let response = Data("HTTP/1.1 \(status) Fixture\r\nContent-Type: application/json\r\nContent-Length: \(data.count)\r\nConnection: close\r\n\r\n".utf8)
            + (truncate ? Data(data.dropLast()) : data)
        connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
    }
}
