import Foundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

@testable import OpenEOSCore

enum MockTransportError: Error, Equatable {
    case missingResponse(String)
    case unexpectedRequest(expected: String, actual: String)
}

struct RecordedRequest: Sendable {
    let method: String
    let path: String
    let headers: [String: String]
    let body: Data?
}

actor MockCameraHTTPTransport: CameraHTTPTransport {
    struct Stub: Sendable {
        let method: String
        let path: String
        let response: CameraHTTPResponse
    }

    struct DownloadStub: Sendable {
        let method: String
        let path: String
        let statusCode: Int
        let headers: [String: String]
        let body: Data
    }

    private var stubs: [Stub] = []
    private var downloadStubs: [DownloadStub] = []
    private var recorded: [RecordedRequest] = []

    func enqueue(
        method: String = "GET",
        path: String,
        status: Int = 200,
        headers: [String: String] = ["content-type": "application/json"],
        body: Data = Data("{}".utf8)
    ) {
        stubs.append(Stub(method: method, path: path, response: CameraHTTPResponse(statusCode: status, headers: headers, body: body)))
    }

    func enqueueJSON(method: String = "GET", path: String, status: Int = 200, body: String) {
        enqueue(method: method, path: path, status: status, body: Data(body.utf8))
    }

    func enqueueDownload(
        method: String = "GET",
        path: String,
        status: Int = 200,
        headers: [String: String] = [:],
        body: Data
    ) {
        downloadStubs.append(DownloadStub(method: method, path: path, statusCode: status, headers: headers, body: body))
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let actual = Self.record(request)
        recorded.append(actual)
        guard !stubs.isEmpty else { throw MockTransportError.missingResponse("\(actual.method) \(actual.path)") }
        let stub = stubs.removeFirst()
        guard stub.method == actual.method, stub.path == actual.path else {
            throw MockTransportError.unexpectedRequest(
                expected: "\(stub.method) \(stub.path)",
                actual: "\(actual.method) \(actual.path)"
            )
        }
        return stub.response
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        let actual = Self.record(request)
        recorded.append(actual)
        guard !downloadStubs.isEmpty else { throw MockTransportError.missingResponse("download \(actual.path)") }
        let stub = downloadStubs.removeFirst()
        guard stub.method == actual.method, stub.path == actual.path else {
            throw MockTransportError.unexpectedRequest(
                expected: "\(stub.method) \(stub.path)",
                actual: "\(actual.method) \(actual.path)"
            )
        }
        let temporaryURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try stub.body.write(to: temporaryURL, options: .atomic)
        return CameraHTTPDownloadResponse(
            statusCode: stub.statusCode,
            headers: stub.headers,
            temporaryFileURL: temporaryURL
        )
    }

    func requests() -> [RecordedRequest] {
        recorded
    }

    func remainingResponses() -> Int {
        stubs.count
    }

    private static func record(_ request: URLRequest) -> RecordedRequest {
        let url = request.url
        let path = (url?.path ?? "") + (url?.query.map { "?\($0)" } ?? "")
        return RecordedRequest(
            method: request.httpMethod ?? "GET",
            path: path,
            headers: request.allHTTPHeaderFields ?? [:],
            body: request.httpBody
        )
    }
}
