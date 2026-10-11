import Foundation
import Network
import XCTest

@testable import OpenEOSCore

/// Real loopback HTTP establishes redirect and cancellation behavior that a
/// custom CameraHTTPTransport mock cannot promise for production URLSession.
final class URLSessionForegroundMediaTests: XCTestCase {
    func testStrictJSONAndOriginalDoNotFollowRedirectsButManualSendStillDoes() async throws {
        let peer = try ForegroundMediaWirePeer()
        try await peer.start()
        defer { peer.stop() }
        let transport = URLSessionCameraHTTPTransport()

        do {
            _ = try await transport.sendForeground(peer.request("/redirect"), maximumResponseBytes: 32)
            XCTFail("Strict JSON followed a redirect")
        } catch {}
        XCTAssertEqual(peer.paths(), ["/redirect"])

        do {
            let response = try await transport.downloadForeground(peer.request("/redirect"), maximumBytes: 4) { _ in }
            try? FileManager.default.removeItem(at: response.temporaryFileURL)
            XCTFail("Strict original followed a redirect")
        } catch {}
        XCTAssertEqual(peer.paths(), ["/redirect", "/redirect"])

        let manual = try await transport.send(peer.request("/redirect"))
        XCTAssertEqual(manual.statusCode, 200)
        XCTAssertEqual(manual.body, peer.original)
        XCTAssertEqual(peer.paths(), ["/redirect", "/redirect", "/redirect", "/original"])
    }

    func testStrictTransportBoundsStreamedJSONAndOriginalBytesAndKeepsOneGET() async throws {
        let peer = try ForegroundMediaWirePeer()
        try await peer.start()
        defer { peer.stop() }
        let transport = URLSessionCameraHTTPTransport()
        let json = try await transport.sendForeground(peer.request("/json"), maximumResponseBytes: 2)
        XCTAssertEqual(json.body, Data("{}".utf8))
        do {
            _ = try await transport.sendForeground(peer.request("/json"), maximumResponseBytes: 1)
            XCTFail("Oversized JSON was retained")
        } catch {}
        let original = try await transport.downloadForeground(peer.request("/original"), maximumBytes: 4) { _ in }
        defer { try? FileManager.default.removeItem(at: original.temporaryFileURL) }
        XCTAssertEqual(try Data(contentsOf: original.temporaryFileURL), peer.original)
        // Four decoded bytes fit although their chunk framing is larger than four bytes.
        let chunked = try await transport.downloadForeground(peer.request("/chunked-original"), maximumBytes: 4) { _ in }
        defer { try? FileManager.default.removeItem(at: chunked.temporaryFileURL) }
        XCTAssertEqual(try Data(contentsOf: chunked.temporaryFileURL), peer.original)
        for path in ["/oversize", "/short", "/partial"] {
            do {
                let response = try await transport.downloadForeground(peer.request(path), maximumBytes: 4) { _ in }
                try? FileManager.default.removeItem(at: response.temporaryFileURL)
                XCTFail("Invalid original response was retained: \(path)")
            } catch {}
        }
        XCTAssertEqual(peer.paths(), ["/json", "/json", "/original", "/chunked-original", "/oversize", "/short", "/partial"])
    }

    func testChunkedDecodedOverflowRemovesTheExactOwnedProductionTemporaryFile() async throws {
        let peer = try ForegroundMediaWirePeer()
        try await peer.start()
        defer { peer.stop() }
        let transport = URLSessionCameraHTTPTransport()
        let prefixWritten = expectation(description: "unique decoded prefix reaches the production temporary file")
        let limit = Int64(peer.marker.count)
        let task = Task {
            try await transport.downloadForeground(peer.request("/chunked-overflow"), maximumBytes: limit) { progress in
                if progress.bytesTransferred == limit { prefixWritten.fulfill() }
            }
        }
        defer { task.cancel() }
        await fulfillment(of: [prefixWritten], timeout: 2)
        // The marker identifies this transfer, not every temporary file that
        // another test may concurrently create. Read only this bounded namespace.
        let ownedFiles = try foregroundTemporaryFiles(containing: peer.marker)
        XCTAssertEqual(ownedFiles.count, 1)
        let ownedFile = try XCTUnwrap(ownedFiles.first)
        XCTAssertTrue(peer.completeChunkedOverflow())
        switch await task.result {
        case .success(let response):
            try? FileManager.default.removeItem(at: response.temporaryFileURL)
            XCTFail("Oversized decoded chunk was retained")
        case .failure(let error):
            XCTAssertFalse(error is CancellationError)
            XCTAssertTrue(error.localizedDescription.contains("byte limit"), "\(error)")
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: ownedFile.path))
        XCTAssertEqual(peer.paths(), ["/chunked-overflow"])
    }

    func testCancellationBeforeInstallationSendsNoJSONOrOriginalRequest() async throws {
        let peer = try ForegroundMediaWirePeer()
        try await peer.start()
        defer { peer.stop() }
        let transport = URLSessionCameraHTTPTransport()
        let jsonTask = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await transport.sendForeground(peer.request("/json"), maximumResponseBytes: 2)
        }
        switch await jsonTask.result {
        case .success: XCTFail("Already-cancelled JSON read installed a task")
        case .failure(let error): XCTAssertTrue(error is CancellationError, "\(error)")
        }
        let originalTask = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await transport.downloadForeground(peer.request("/original"), maximumBytes: 4) { _ in }
        }
        switch await originalTask.result {
        case .success(let response):
            try? FileManager.default.removeItem(at: response.temporaryFileURL)
            XCTFail("Already-cancelled original read installed a task")
        case .failure(let error): XCTAssertTrue(error is CancellationError, "\(error)")
        }
        XCTAssertTrue(peer.paths().isEmpty)
    }

    func testCancellationAtFinalBodyProgressWinsBeforeCompletionAndCleansOwnedFile() async throws {
        let peer = try ForegroundMediaWirePeer()
        try await peer.start()
        defer { peer.stop() }
        let transport = URLSessionCameraHTTPTransport()
        let cancellation = ForegroundTestCancellation()
        let start = ForegroundTestStartGate()
        let observation = ForegroundTemporaryObservation(marker: peer.marker)
        let limit = Int64(peer.marker.count)
        let task = Task {
            await start.wait()
            return try await transport.downloadForeground(peer.request("/marked-original"), maximumBytes: limit) { progress in
                if progress.bytesTransferred == limit {
                    // This callback runs after the last file write but before the
                    // serial URLSession delegate can deliver didCompleteWithError.
                    observation.capture()
                    cancellation.cancel()
                }
            }
        }
        cancellation.install { task.cancel() }
        await start.open()
        switch await task.result {
        case .success(let response):
            try? FileManager.default.removeItem(at: response.temporaryFileURL)
            XCTFail("Completion published a file after final-byte cancellation")
        case .failure(let error): XCTAssertTrue(error is CancellationError, "\(error)")
        }
        let ownedFiles = try observation.files()
        XCTAssertEqual(ownedFiles.count, 1)
        let ownedFile = try XCTUnwrap(ownedFiles.first)
        XCTAssertFalse(FileManager.default.fileExists(atPath: ownedFile.path))
        XCTAssertEqual(peer.paths(), ["/marked-original"])
    }

    func testCancellationAfterCompletionPreservesTransferredFileOwnership() async throws {
        let peer = try ForegroundMediaWirePeer()
        try await peer.start()
        defer { peer.stop() }
        let transport = URLSessionCameraHTTPTransport()
        let task = Task {
            try await transport.downloadForeground(
                peer.request("/marked-original"), maximumBytes: Int64(peer.marker.count)
            ) { _ in }
        }
        let response = try await task.value
        defer { try? FileManager.default.removeItem(at: response.temporaryFileURL) }
        task.cancel()
        XCTAssertEqual(try Data(contentsOf: response.temporaryFileURL), peer.marker)
        XCTAssertEqual(peer.paths(), ["/marked-original"])
    }

    func testCancellationEndsPendingStrictReadWithoutAnotherRequest() async throws {
        let peer = try ForegroundMediaWirePeer()
        try await peer.start()
        defer { peer.stop() }
        let transport = URLSessionCameraHTTPTransport()
        let received = expectation(description: "strict GET reaches the stalled original")
        peer.onStall { received.fulfill() }
        let task = Task {
            try await transport.downloadForeground(peer.request("/stall"), maximumBytes: 4) { _ in }
        }
        await fulfillment(of: [received], timeout: 2)
        task.cancel()
        switch await task.result {
        case .success(let response):
            try? FileManager.default.removeItem(at: response.temporaryFileURL)
            XCTFail("Cancelled original returned a file")
        case .failure(let error): XCTAssertTrue(error is CancellationError, "\(error)")
        }
        XCTAssertEqual(peer.paths(), ["/stall"])
    }

    func testPerRequestResourceTimeoutBoundsAStalledResponse() async throws {
        let peer = try ForegroundMediaWirePeer()
        try await peer.start()
        defer { peer.stop() }
        let transport = URLSessionCameraHTTPTransport()
        var request = peer.request("/stall")
        request.timeoutInterval = 0.2
        let boundedRequest = request
        let finished = expectation(description: "strict resource timeout settles")
        let task = Task {
            defer { finished.fulfill() }
            do {
                _ = try await transport.sendForeground(boundedRequest, maximumResponseBytes: 4)
                XCTFail("Stalled strict response unexpectedly completed")
            } catch {
                XCTAssertEqual((error as? URLError)?.code, .timedOut)
            }
        }
        await fulfillment(of: [finished], timeout: 3)
        task.cancel()
        await task.value
        XCTAssertEqual(peer.paths(), ["/stall"])
    }
}

private final class ForegroundMediaWirePeer: @unchecked Sendable {
    let original = Data([0xFF, 0xD8, 0xFF, 0xD9])
    let marker = Data("foreground-fixture-\(UUID().uuidString)".utf8)
    private let listener: NWListener
    private let queue = DispatchQueue(label: "open-eos.foreground-wire-fixture")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var recorded: [String] = []
    private var stallAction: (@Sendable () -> Void)?
    private var overflowConnection: NWConnection?

    init() throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        listener = try NWListener(using: parameters)
    }

    func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            listener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    self?.listener.stateUpdateHandler = nil
                    continuation.resume()
                case .failed(let error):
                    self?.listener.stateUpdateHandler = nil
                    continuation.resume(throwing: error)
                default: break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                guard let self else { connection.cancel(); return }
                self.lock.lock()
                self.connections.append(connection)
                self.lock.unlock()
                connection.start(queue: self.queue)
                self.read(connection, bytes: Data())
            }
            listener.start(queue: queue)
        }
    }

    func stop() {
        listener.cancel()
        lock.lock()
        let connections = connections
        self.connections.removeAll()
        lock.unlock()
        connections.forEach { $0.cancel() }
    }

    func request(_ path: String) -> URLRequest {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(listener.port!.rawValue)\(path)")!)
        request.httpMethod = "GET"
        request.timeoutInterval = 2
        return request
    }

    func paths() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    func onStall(_ action: @escaping @Sendable () -> Void) {
        lock.lock()
        stallAction = action
        lock.unlock()
    }

    func completeChunkedOverflow() -> Bool {
        lock.lock()
        let connection = overflowConnection
        overflowConnection = nil
        lock.unlock()
        guard let connection else { return false }
        // One extra decoded byte crosses the limit, followed by a valid terminator.
        connection.send(content: Data("1\r\nX\r\n0\r\n\r\n".utf8), completion: .contentProcessed { _ in
            connection.cancel()
        })
        return true
    }

    private func read(_ connection: NWConnection, bytes: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4_096) { [weak self] data, _, complete, error in
            guard let self else { connection.cancel(); return }
            let accumulated = bytes + (data ?? Data())
            guard accumulated.count <= 32 * 1024 else { connection.cancel(); return }
            if accumulated.range(of: Data("\r\n\r\n".utf8)) != nil {
                let fields = String(decoding: accumulated, as: UTF8.self).components(separatedBy: "\r\n")[0]
                    .split(separator: " ")
                guard fields.count == 3, fields[0] == "GET" else { connection.cancel(); return }
                self.respond(to: String(fields[1]), connection: connection)
            } else if complete || error != nil {
                connection.cancel()
            } else {
                self.read(connection, bytes: accumulated)
            }
        }
    }

    private func respond(to path: String, connection: NWConnection) {
        lock.lock()
        recorded.append(path)
        if path == "/chunked-overflow" { overflowConnection = connection }
        let stallAction = stallAction
        lock.unlock()
        let response: Data
        switch path {
        case "/redirect":
            response = Data("HTTP/1.1 302 Found\r\nLocation: /original\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8)
        case "/json": response = success(Data("{}".utf8))
        case "/original": response = success(original)
        case "/marked-original": response = success(marker)
        case "/chunked-original":
            response = Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n2\r\n".utf8) +
                original.prefix(2) + Data("\r\n2\r\n".utf8) + original.suffix(2) + Data("\r\n0\r\n\r\n".utf8)
        case "/chunked-overflow":
            response = Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n\(String(marker.count, radix: 16))\r\n".utf8) +
                marker + Data("\r\n".utf8)
        case "/oversize":
            response = Data("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".utf8) + original + Data([0x00])
        case "/short":
            response = Data("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\n".utf8) + original.prefix(3)
        case "/partial":
            response = Data("HTTP/1.1 206 Partial Content\r\nContent-Length: 4\r\nConnection: close\r\n\r\n".utf8) + original
        case "/stall":
            response = Data("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\n".utf8) + original.prefix(1)
        default:
            response = Data("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8)
        }
        connection.send(content: response, completion: .contentProcessed { error in
            if path == "/stall", error == nil { stallAction?() }
            else if path == "/chunked-overflow", error == nil { /* The test owns the completion barrier. */ }
            else { connection.cancel() }
        })
    }

    private func success(_ data: Data) -> Data {
        Data("HTTP/1.1 200 OK\r\nContent-Length: \(data.count)\r\nConnection: close\r\n\r\n".utf8) + data
    }
}

private enum ForegroundFixtureError: Error {
    case tooManyTemporaryFiles
    case missingObservation
}

/// Locate only this test's unique bytes, with a strict read bound per candidate.
/// This avoids changing production APIs or claiming ownership from a directory delta.
private func foregroundTemporaryFiles(containing marker: Data) throws -> [URL] {
    let candidates = try FileManager.default.contentsOfDirectory(
        at: FileManager.default.temporaryDirectory, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey]
    ).filter { $0.lastPathComponent.hasPrefix("open-eos-foreground-") }
    guard candidates.count <= 512 else { throw ForegroundFixtureError.tooManyTemporaryFiles }
    return candidates.filter { url in
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true, values.fileSize == marker.count,
              let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        return (try? handle.read(upToCount: marker.count)) == marker
    }
}

private final class ForegroundTemporaryObservation: @unchecked Sendable {
    private let lock = NSLock()
    private let marker: Data
    private var result: Result<[URL], Error>?
    init(marker: Data) { self.marker = marker }
    func capture() {
        let result = Result { try foregroundTemporaryFiles(containing: marker) }
        lock.lock()
        self.result = result
        lock.unlock()
    }
    func files() throws -> [URL] {
        lock.lock()
        let result = result
        lock.unlock()
        guard let result else { throw ForegroundFixtureError.missingObservation }
        return try result.get()
    }
}

/// Handles a fast callback that cancels even before the test stores its Task.
private final class ForegroundTestCancellation: @unchecked Sendable {
    private let lock = NSLock()
    private var action: (@Sendable () -> Void)?
    private var requested = false
    func install(_ action: @escaping @Sendable () -> Void) {
        lock.lock()
        self.action = action
        let requested = requested
        lock.unlock()
        if requested { action() }
    }
    func cancel() {
        lock.lock()
        requested = true
        let action = action
        lock.unlock()
        action?()
    }
}

/// Install the cancellation target before any transport callback can occur.
private actor ForegroundTestStartGate {
    private var opened = false
    private var continuation: CheckedContinuation<Void, Never>?
    func wait() async {
        if opened { return }
        await withCheckedContinuation { continuation = $0 }
    }
    func open() {
        opened = true
        continuation?.resume()
        continuation = nil
    }
}
