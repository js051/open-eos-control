import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OpenEOSCore

final class CCAPICaptureReadbackTests: XCTestCase {
    func testSimulatorACKThenRequiredStatusFailureIsReadbackOnly() async throws {
        let peer = CaptureReadbackPeer(mode: .simulator, status: .lost)
        let client = try peer.client()
        do { _ = try await client.captureStill(); XCTFail("Required status read must fail") }
        catch let error as CCAPIError { XCTAssertEqual(error, .captureStatusReadbackFailed) }
        let release = await client.shutterReleaseState()
        XCTAssertEqual(release, .idle)
        _ = try await client.status()
        try await client.retryShutterRelease()
        let requests = await peer.requests()
        XCTAssertEqual(requests, ["POST /ccapi/capture/still", "GET /ccapi/status", "GET /ccapi/status"])
    }

    func testSimulatorCommandLossAndRejectionNeverBecomeCaptureAcknowledgements() async throws {
        for failure in [CaptureReadbackReply.lost, .rejected] {
            let peer = CaptureReadbackPeer(mode: .simulator, capture: failure)
            let client = try peer.client()
            do { _ = try await client.captureStill(); XCTFail("Command must fail") }
            catch { XCTAssertNotEqual(error as? CCAPIError, .captureStatusReadbackFailed) }
            let requests = await peer.requests()
            XCTAssertEqual(requests, ["POST /ccapi/capture/still"])
        }
    }

    func testReadbackCancellationKeepsExistingCancellationContractInBothModes() async throws {
        for mode in [CCAPIConnectionMode.simulator, .camera] {
            for failure in [CaptureReadbackReply.cancelled, .urlCancelled] {
                let peer = CaptureReadbackPeer(mode: mode, status: failure)
                let client = try peer.client()
                do {
                    _ = try await client.captureStill()
                    // Canon's optional firstJSON reads already absorb URLError.cancelled.
                    XCTAssertTrue(mode == .camera && failure == .urlCancelled)
                } catch {
                    XCTAssertTrue(CameraConnectionFailureReason.isCancellation(error))
                    XCTAssertNotEqual(error as? CCAPIError, .captureStatusReadbackFailed)
                }
                let writes = await peer.requests().filter { $0.hasPrefix("POST ") }
                XCTAssertEqual(writes.count, 1)
            }
        }
    }

    func testCallerCancellationWithLateOrdinaryReadErrorDoesNotBecomeReadbackRecovery() async throws {
        for failure in [CaptureReadbackReply.lost, .rejected] {
            let gate = ShutterRecoveryGate("required status read ignores caller cancellation")
            let peer = CaptureReadbackPeer(mode: .simulator, status: failure, statusGate: gate)
            let client = try peer.client()
            let capture = Task { try await client.captureStill() }
            await fulfillment(of: [gate.entered], timeout: 3)
            capture.cancel()
            gate.open()
            do { _ = try await capture.value; XCTFail("Cancelled capture must fail") }
            catch { XCTAssertTrue(error is CancellationError) }
            let requests = await peer.requests()
            XCTAssertEqual(requests, ["POST /ccapi/capture/still", "GET /ccapi/status"])
        }
    }

    func testCanonicalOptionalHTTPAndNetworkReadFailuresRemainSuccessfulStatusReads() async throws {
        for failure in [CaptureReadbackReply.lost, .rejected] {
            let peer = CaptureReadbackPeer(mode: .camera, status: failure)
            let client = try peer.client()
            _ = try await client.captureStill()
            let writes = await peer.requests().filter { $0.hasPrefix("POST ") }
            XCTAssertEqual(writes, ["POST /ccapi/ver100/shooting/control/shutterbutton"])
            let state = await client.shutterReleaseState()
            XCTAssertEqual(state, .idle)
        }
    }
}

private enum CaptureReadbackReply: Sendable {
    case ack, lost, rejected, cancelled, urlCancelled
    func response() throws -> CameraHTTPResponse {
        switch self {
        case .ack: return CameraHTTPResponse(statusCode: 200, body: Data("{}".utf8))
        case .rejected: return CameraHTTPResponse(statusCode: 503, body: Data("{}".utf8))
        case .lost: throw URLError(.networkConnectionLost)
        case .cancelled: throw CancellationError()
        case .urlCancelled: throw URLError(.cancelled)
        }
    }
}

private actor CaptureReadbackPeer: CameraHTTPTransport {
    nonisolated let mode: CCAPIConnectionMode
    private let capture: CaptureReadbackReply
    private var status: CaptureReadbackReply
    private var acknowledged = false
    private var statusGate: ShutterRecoveryGate?
    private var recorded: [String] = []
    init(mode: CCAPIConnectionMode, capture: CaptureReadbackReply = .ack, status: CaptureReadbackReply = .ack,
         statusGate: ShutterRecoveryGate? = nil) {
        self.mode = mode; self.capture = capture; self.status = status; self.statusGate = statusGate
    }
    nonisolated func client() throws -> CCAPIClient {
        try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: mode, transport: self)
    }
    func requests() -> [String] { recorded }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        recorded.append("\(request.httpMethod ?? "GET") \(path)")
        if path == "/ccapi" {
            return CameraHTTPResponse(statusCode: 200, body: Data(#"{"ver100":[{"path":"/shooting/control/shutterbutton","post":true},{"path":"/devicestatus/batterylist","get":true}]}"#.utf8))
        }
        if request.httpMethod == "POST" {
            let response = try capture.response()
            acknowledged = response.statusCode == 200
            return response
        }
        if acknowledged {
            let reply = status
            if mode == .simulator { status = .ack }
            let gate = statusGate
            statusGate = nil
            await gate?.wait()
            if path == "/ccapi/status", reply == .ack {
                return CameraHTTPResponse(statusCode: 200, body: Data(
                    #"{"recording":false,"battery":{},"media":{},"exposure":{}}"#.utf8
                ))
            }
            return try reply.response()
        }
        return try CaptureReadbackReply.ack.response()
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}
