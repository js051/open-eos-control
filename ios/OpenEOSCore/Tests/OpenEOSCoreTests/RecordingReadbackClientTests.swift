import Foundation
import XCTest

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

@testable import OpenEOSCore

final class RecordingReadbackClientTests: XCTestCase {
    func testBridgeMapsOnlyAcknowledgedReadbackCodeWithTheRequestedDirection() async throws {
        for starting in [true, false] {
            let peer = RecordingBoundaryTransport(mode: .bridge, commandFault: .acknowledgedReadback)
            let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", cameraID: "synthetic", transport: peer)
            try await client.initialize()
            do {
                _ = starting ? try await client.startRecording() : try await client.stopRecording()
                XCTFail("Expected the precise Bridge ACK/readback classification")
            } catch {
                XCTAssertEqual(error as? DesktopBridgeError, .recordingStatusReadbackFailed(starting: starting))
            }
            let actions = await peer.actions()
            XCTAssertEqual(actions, [starting ? "start" : "stop"])
            let reads = await peer.statusReadCount()
            XCTAssertEqual(reads, 0, "The client must not automatically recheck or replay REC")
        }
    }

    func testBridgeCommandRejectionLostResponseAndCancellationDoNotInventAnAcknowledgement() async throws {
        for fault in [RecordingBoundaryTransport.Fault.http, .network, .malformed, .cancelled] {
            for starting in [true, false] {
                let peer = RecordingBoundaryTransport(mode: .bridge, commandFault: fault)
                let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", cameraID: "synthetic", transport: peer)
                try await client.initialize()
                do {
                    _ = starting ? try await client.startRecording() : try await client.stopRecording()
                    XCTFail("Expected an unacknowledged command failure")
                } catch {
                    if let error = error as? DesktopBridgeError, case .recordingStatusReadbackFailed = error {
                        XCTFail("A generic error cannot establish that the camera acknowledged REC")
                    }
                    if fault == .cancelled { XCTAssertTrue(error is CancellationError) }
                }
                let actions = await peer.actions()
                XCTAssertEqual(actions, [starting ? "start" : "stop"])
            }
        }
    }

    func testRequiredSimulatorReadFailurePreservesAckAndReadOnlyRecoveryNeverReplaysREC() async throws {
        for starting in [true, false] {
            for fault in [RecordingBoundaryTransport.Fault.http, .network, .malformed] {
                let peer = RecordingBoundaryTransport(mode: .simulator, readFault: fault)
                let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer)
                do {
                    _ = starting ? try await client.startRecording() : try await client.stopRecording()
                    XCTFail("The required simulator status read must fail after the command ACK")
                } catch {
                    XCTAssertEqual(error as? CCAPIError, .recordingStatusReadbackFailed(starting: starting))
                }
                await peer.clearReadFault()
                let refreshed = try await client.status()
                XCTAssertEqual(refreshed.recording, starting)
                let actions = await peer.actions()
                XCTAssertEqual(actions, [starting ? "start" : "stop"])
                let reads = await peer.statusReadCount()
                XCTAssertEqual(reads, 2)
            }
        }
    }

    func testDirectCommandFailureIsOutsideTheAcknowledgedReadbackBoundary() async throws {
        for mode in [RecordingBoundaryTransport.Mode.simulator, .canonical] {
            for fault in [RecordingBoundaryTransport.Fault.http, .network, .cancelled] {
                let peer = RecordingBoundaryTransport(mode: mode, commandFault: fault)
                let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: mode == .canonical ? .camera : .simulator, transport: peer)
                do {
                    _ = try await client.startRecording()
                    XCTFail("Expected the command itself to fail")
                } catch {
                    if let error = error as? CCAPIError, case .recordingStatusReadbackFailed = error {
                        XCTFail("A lost or rejected command response has no known ACK")
                    }
                    if fault == .cancelled { XCTAssertTrue(error is CancellationError) }
                }
                let reads = await peer.statusReadCount()
                XCTAssertEqual(reads, 0)
            }
        }
    }

    func testCanonicalOptionalReadFailuresRemainNegativeControls() async throws {
        for fault in [RecordingBoundaryTransport.Fault.http, .network, .malformed] {
            for starting in [true, false] {
                let peer = RecordingBoundaryTransport(mode: .canonical, readFault: fault)
                let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
                let status = starting ? try await client.startRecording() : try await client.stopRecording()
                // This existing canonical value is the client's acknowledged
                // direction, not an independent physical recording observation.
                XCTAssertEqual(status.recording, starting)
                let actions = await peer.actions()
                XCTAssertEqual(actions, [starting ? "start" : "stop"])
                let reads = await peer.statusReadCount()
                XCTAssertGreaterThan(reads, 0)
            }
        }
    }

    func testPostAckCancellationKeepsItsExistingCancellationSemantics() async throws {
        for mode in [RecordingBoundaryTransport.Mode.simulator, .canonical] {
            let peer = RecordingBoundaryTransport(mode: mode, readFault: .cancelled)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: mode == .canonical ? .camera : .simulator, transport: peer)
            do {
                _ = try await client.startRecording()
                XCTFail("Expected cancellation to propagate after the ACK")
            } catch {
                XCTAssertTrue(error is CancellationError)
            }
            let actions = await peer.actions()
            XCTAssertEqual(actions, ["start"])
        }
    }
}

/// Actual clients consume HTTP responses here. Only the Bridge's exact new code
/// asserts its server observed an ACK; a network failure never asserts that fact.
private actor RecordingBoundaryTransport: CameraHTTPTransport {
    enum Mode: Equatable, Sendable { case bridge, simulator, canonical }
    enum Fault: Equatable, Sendable { case http, network, malformed, cancelled, acknowledgedReadback }
    let mode: Mode
    let commandFault: Fault?
    private var readFault: Fault?
    private var recording = false
    private var commands: [String] = []
    private var reads = 0

    init(mode: Mode, commandFault: Fault? = nil, readFault: Fault? = nil) {
        self.mode = mode
        self.commandFault = commandFault
        self.readFault = readFault
    }
    func actions() -> [String] { commands }
    func statusReadCount() -> Int { reads }
    func clearReadFault() { readFault = nil }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        if path == "/health" {
            return try json(["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"])
        }
        if path == "/v1/session", method == "POST" { return try json(["id": "synthetic", "engine": "ccapi"]) }
        if path == "/ccapi" {
            return try json(["ver100": [["path": "/shooting/control/recbutton", "post": true]]])
        }
        if method == "POST" {
            let direction: String
            if mode == .canonical {
                let body = try XCTUnwrap(request.httpBody)
                let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
                direction = try XCTUnwrap(object["action"])
                XCTAssertEqual(path, "/ccapi/ver100/shooting/control/recbutton")
            } else {
                direction = request.url!.lastPathComponent
                XCTAssertEqual(path, mode == .bridge ? "/v1/session/synthetic/recording/\(direction)" : "/ccapi/record/\(direction)")
            }
            commands.append(direction)
            if let commandFault { return try fail(commandFault) }
            recording = direction == "start"
            return mode == .canonical ? CameraHTTPResponse(statusCode: 204) : try json(status())
        }
        guard method == "GET" else { throw URLError(.unsupportedURL) }
        reads += 1
        if let readFault { return try fail(readFault) }
        return try json(status())
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }

    private func fail(_ fault: Fault) throws -> CameraHTTPResponse {
        switch fault {
        case .network: throw URLError(.networkConnectionLost)
        case .cancelled: throw CancellationError()
        case .malformed: return CameraHTTPResponse(statusCode: 200, body: Data("not-json".utf8))
        case .http, .acknowledgedReadback:
            return try json(["error": ["code": fault == .acknowledgedReadback ? "RECORDING_STATUS_READBACK_FAILED" : "COMMAND_REJECTED", "message": "Synthetic failure"]], statusCode: 502)
        }
    }
    private func status() -> [String: Any] {
        ["connected": true, "recording": recording, "mode": "Manual", "battery": ["level": 82, "status": "good"],
         "media": ["available": true], "exposure": ["iso": "100", "shutter": "1/100", "aperture": "4.0", "white_balance": "auto", "whiteBalance": "auto"]]
    }
    private func json(_ object: [String: Any], statusCode: Int = 200) throws -> CameraHTTPResponse {
        CameraHTTPResponse(statusCode: statusCode, body: try JSONSerialization.data(withJSONObject: object))
    }
}
