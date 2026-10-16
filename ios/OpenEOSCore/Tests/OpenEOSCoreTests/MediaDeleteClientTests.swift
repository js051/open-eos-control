import Foundation
import XCTest
@testable import OpenEOSCore

final class MediaDeleteClientTests: XCTestCase {
    func testBothClientsRequireComplete204AndUseExplicitRefusalAllowlist() async throws {
        let cases: [(CameraHTTPResponse, CameraMediaDeleteOutcome)] = [
            (.init(statusCode: 204), .confirmed), (.init(statusCode: 200), .uncertain), (.init(statusCode: 202), .uncertain),
            (.init(statusCode: 204, body: Data([1])), .uncertain), (.init(statusCode: 204, headers: ["Content-Length": "8"]), .uncertain),
            (.init(statusCode: 204, headers: ["Transfer-Encoding": "chunked"]), .uncertain),
            (.init(statusCode: 403, body: Data("denied".utf8)), .rejected), (.init(statusCode: 404), .rejected),
            (.init(statusCode: 403, headers: ["Content-Length": "80"], body: Data([1])), .uncertain),
            (.init(statusCode: 408), .uncertain), (.init(statusCode: 499), .uncertain), (.init(statusCode: 503), .uncertain),
            (.init(statusCode: 307), .uncertain), (.init(statusCode: 308), .uncertain)
        ]
        for bridge in [true, false] {
            for (response, expected) in cases {
                let peer = DeleteHTTPPeer(response: response)
                let attempt = CameraMediaDeleteAttempt()
                let item = CameraMediaItem(id: "gphoto2:exact-object", name: "SYNTHETIC.JPG", kind: "image")
                if bridge {
                    let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
                    try await client.initialize()
                    do { try await client.deleteMedia(item, attempt: attempt); XCTAssertEqual(expected, .confirmed) }
                    catch { XCTAssertNotEqual(expected, .confirmed) }
                    do { try await client.deleteMedia(item, attempt: attempt); XCTFail("Attempt replay") } catch {}
                } else {
                    let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer)
                    do { try await client.deleteMedia(item, attempt: attempt); XCTAssertEqual(expected, .confirmed) }
                    catch { XCTAssertNotEqual(expected, .confirmed) }
                    do { try await client.deleteMedia(item, attempt: attempt); XCTFail("Attempt replay") } catch {}
                }
                XCTAssertEqual(attempt.outcome, expected)
                let sent = await peer.deletes(); XCTAssertEqual(sent, [item.id])
            }
        }
    }
    func testCancelledAndProtectedAttemptsNeverEnterTransport() async throws {
        let peer = DeleteHTTPPeer(response: .init(statusCode: 204))
        let bridge = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
        try await bridge.initialize()
        for protected in [true, false] {
            let attempt = CameraMediaDeleteAttempt()
            if !protected { attempt.cancel() }
            do { try await bridge.deleteMedia(CameraMediaItem(id: "gphoto2:one", name: "SYNTHETIC.JPG", kind: "image", protected: protected), attempt: attempt); XCTFail("Preflight must fail") } catch {}
            XCTAssertEqual(attempt.outcome, .notSent)
        }
        let writes = await peer.deletes(); XCTAssertTrue(writes.isEmpty)
    }
    func testCancelDoesNotDowngradeConfirmedAndRecordBeforeDispatchCannotForgeReceipt() throws {
        let before = CameraMediaDeleteAttempt()
        XCTAssertThrowsError(try before.record(.init(statusCode: 204)))
        XCTAssertEqual(before.outcome, .notSent)
        let attempt = CameraMediaDeleteAttempt()
        try attempt.markDispatched(); attempt.cancel()
        try attempt.record(.init(statusCode: 204))
        XCTAssertEqual(attempt.outcome, .confirmed)
        XCTAssertThrowsError(try attempt.markDispatched())
    }
}
private actor DeleteHTTPPeer: CameraHTTPTransport {
    let response: CameraHTTPResponse
    var sent: [String] = []
    init(response: CameraHTTPResponse) { self.response = response }
    func deletes() -> [String] { sent }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        if request.httpMethod == "DELETE" {
            sent.append(request.url!.lastPathComponent)
            return response
        }
        let path = request.url!.path
        if path == "/health" { return .init(statusCode: 200, body: Data(#"{"ok":true,"service":"open-eos-control-bridge"}"#.utf8)) }
        if path == "/v1/session" { return .init(statusCode: 201, body: Data(#"{"id":"synthetic-delete","engine":"libgphoto2"}"#.utf8)) }
        return .init(statusCode: 200, body: Data("{}".utf8))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}
