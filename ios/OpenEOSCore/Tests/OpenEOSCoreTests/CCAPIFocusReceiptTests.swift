import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OpenEOSCore

/// Production-client tests with an injected transport and real Canon geometry parsing.
/// Swift compilation/execution and physical-camera validation are separate evidence.
final class CCAPIFocusReceiptTests: XCTestCase {
    private let partialReplies = [
        CameraHTTPResponse(statusCode: 206),
        CameraHTTPResponse(statusCode: 206, headers: ["Content-Length": "2"], body: Data("{}".utf8)),
        CameraHTTPResponse(statusCode: 200, headers: ["Content-Range": "bytes 0-1/4", "Content-Length": "2"], body: Data("{}".utf8)),
        CameraHTTPResponse(statusCode: 200, headers: ["cOnTeNt-RaNgE": ""]),
        CameraHTTPResponse(statusCode: 204, headers: ["content-range": ""]),
    ]
    private let fullReplies = [
        CameraHTTPResponse(statusCode: 200),
        CameraHTTPResponse(statusCode: 200, body: Data("Accepted".utf8)),
        CameraHTTPResponse(statusCode: 200, body: Data("{\"ok\":true}".utf8)),
        CameraHTTPResponse(statusCode: 204),
        CameraHTTPResponse(statusCode: 201, body: Data("Accepted".utf8)),
        CameraHTTPResponse(statusCode: 202, body: Data("{}".utf8)),
        CameraHTTPResponse(statusCode: 205),
        CameraHTTPResponse(statusCode: 299, body: Data("Accepted".utf8)),
    ]

    func testFocusReceiptPolicyIsExactlyThreeMethodPathPairs() {
        for command in FocusReceiptCommand.allCases {
            for version in ["ver110", "ver130"] {
                let path = "/ccapi/\(version)" + command.suffix
                XCTAssertTrue(CCAPIClient.requiresCompleteFocusReceipt(method: command.method, path: path))
                for method in ["GET", "POST", "PUT", "DELETE", "PATCH"] where method != command.method {
                    XCTAssertFalse(CCAPIClient.requiresCompleteFocusReceipt(method: method, path: path))
                }
                XCTAssertFalse(CCAPIClient.requiresCompleteFocusReceipt(method: command.method, path: path + "/other"))
                XCTAssertFalse(CCAPIClient.requiresCompleteControlReceipt(method: command.method, path: path))
            }
        }
        for (method, path) in [("POST", "/shooting/control/af"), ("POST", "/shooting/control/shutterbutton"),
                               ("POST", "/shooting/liveview"), ("POST", "/shooting/control/recbutton"),
                               ("GET", "/shooting/liveview/flipdetail"), ("PUT", "/shooting/settings/iso"),
                               ("GET", "/contents/card1/SYNTHETIC.JPG"), ("POST", "/ccapi/focus/drive"),
                               ("POST", "/ccapi/focus/tap"), ("POST", "/ccapi/whitebalance/click")] {
            XCTAssertFalse(CCAPIClient.requiresCompleteFocusReceipt(method: method, path: path))
        }
    }

    func testPartialReceiptsRejectAllThreeCommandsWithoutAcceptanceObservationOrReplay() async throws {
        for command in FocusReceiptCommand.allCases {
            for partial in partialReplies {
                let (client, peer) = try await prepared(command, reply: partial)
                do {
                    try await run(command, client: client)
                    XCTFail("Partial \(command) acknowledgement was accepted")
                } catch {
                    XCTAssertEqual(error as? CCAPIError, .invalidResponse("Camera control acknowledgement was incomplete."))
                }
                try await assertSingleCommand(command, peer: peer)
                let capabilities = try await client.capabilities()
                XCTAssertFalse(capabilities.evidence.observedFeatures.contains(command.feature))
                // Reads after failure must not replay the mutation or turn it into observed success.
                _ = try await client.status()
                try await assertSingleCommand(command, peer: peer)
                let refreshed = try await client.capabilities()
                XCTAssertFalse(refreshed.evidence.observedFeatures.contains(command.feature))
                if command != .drive { try await client.retryLiveViewStop() }
            }
        }
    }

    func testFullEmptyPlainJSONAndLegacy2xxReceiptsRemainAccepted() async throws {
        for command in FocusReceiptCommand.allCases {
            for full in fullReplies {
                let (client, peer) = try await prepared(command, reply: full)
                try await run(command, client: client)
                try await assertSingleCommand(command, peer: peer)
                let capabilities = try await client.capabilities()
                XCTAssertTrue(capabilities.evidence.observedFeatures.contains(command.feature))
                if command != .drive { try await client.retryLiveViewStop() }
            }
        }
    }

    func testNon2xxFocusReceiptsPreserveHTTPStatusMethodURLAndBody() async throws {
        for command in FocusReceiptCommand.allCases {
            let response = CameraHTTPResponse(statusCode: 503, headers: ["Content-Range": ""], body: Data("busy".utf8))
            let (client, peer) = try await prepared(command, reply: response)
            do {
                try await run(command, client: client)
                XCTFail("Expected existing HTTP rejection")
            } catch let CCAPIError.http(status, method, url, body) {
                XCTAssertEqual(status, 503)
                XCTAssertEqual(method, command.method)
                XCTAssertEqual(url, "http://192.0.2.1:8080/ccapi/ver130" + command.suffix)
                XCTAssertEqual(body, "busy")
            } catch { XCTFail("Existing HTTP rejection changed to \(error)") }
            try await assertSingleCommand(command, peer: peer)
            let capabilities = try await client.capabilities()
            XCTAssertFalse(capabilities.evidence.observedFeatures.contains(command.feature))
            if command != .drive { try await client.retryLiveViewStop() }
        }
    }

    func testCoordinateCommandsStillRequireCurrentGeometryBeforeSending() async throws {
        for command in [FocusReceiptCommand.tap, .whiteBalance] {
            let peer = FocusReceiptPeer(reply: .init(statusCode: 204))
            let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: peer)
            try await client.initialize()
            do {
                try await run(command, client: client)
                XCTFail("Missing geometry must fail before dispatch")
            } catch {
                XCTAssertTrue(error.localizedDescription.contains("detailed Live View frame"))
            }
            let writes = await peer.focusWrites()
            XCTAssertTrue(writes.isEmpty)
        }
    }

    private func prepared(_ command: FocusReceiptCommand, reply: CameraHTTPResponse) async throws -> (CCAPIClient, FocusReceiptPeer) {
        let peer = FocusReceiptPeer(reply: reply)
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: peer)
        try await client.initialize()
        if command != .drive {
            try await client.startLiveView(.init(source: .ccapiJPEGPolling))
            _ = try await client.liveViewFrame(cacheKey: 1)
        }
        return (client, peer)
    }

    private func run(_ command: FocusReceiptCommand, client: CCAPIClient) async throws {
        switch command {
        case .drive:
            let result = try await client.driveFocus(direction: .far, step: .large)
            XCTAssertEqual(result, FocusDriveResult(accepted: true, direction: .far, step: .large))
        case .tap:
            let result = try await client.tapFocus(x: 0.25, y: 0.75)
            XCTAssertEqual(result, FocusResult(accepted: true, x: 0.25, y: 0.75))
        case .whiteBalance:
            let status = try await client.clickWhiteBalance(x: 0.25, y: 0.75)
            XCTAssertEqual(status.exposure.whiteBalance, "auto")
        }
    }

    private func assertSingleCommand(_ command: FocusReceiptCommand, peer: FocusReceiptPeer,
                                     file: StaticString = #filePath, line: UInt = #line) async throws {
        let writes = await peer.focusWrites()
        XCTAssertEqual(writes.count, 1, "No automatic replay", file: file, line: line)
        let request = try XCTUnwrap(writes.first, file: file, line: line)
        XCTAssertEqual(request.method, command.method, file: file, line: line)
        XCTAssertEqual(request.path, "/ccapi/ver130" + command.suffix, file: file, line: line)
        let value = try JSONSerialization.jsonObject(with: request.body)
        if command == .drive {
            XCTAssertEqual(value as? [String: String], ["value": "far3"], file: file, line: line)
        } else {
            XCTAssertEqual(value as? [String: Int], ["positionx": 1600, "positiony": 3200], file: file, line: line)
        }
    }
}

private enum FocusReceiptCommand: CaseIterable, Sendable {
    case drive, tap, whiteBalance
    var method: String { self == .tap ? "PUT" : "POST" }
    var suffix: String {
        switch self {
        case .drive: return "/shooting/control/drivefocus"
        case .tap: return "/shooting/liveview/afframeposition"
        case .whiteBalance: return "/shooting/liveview/clickwb"
        }
    }
    var feature: CameraFeature {
        switch self {
        case .drive: return .focusDrive
        case .tap: return .tapFocus
        case .whiteBalance: return .clickWhiteBalance
        }
    }
}

private actor FocusReceiptPeer: CameraHTTPTransport {
    private let reply: CameraHTTPResponse
    private var records: [ShutterRecoveryRequest] = []
    init(reply: CameraHTTPResponse) { self.reply = reply }
    func focusWrites() -> [ShutterRecoveryRequest] {
        records.filter { request in
            FocusReceiptCommand.allCases.contains { request.method == $0.method && request.path.hasSuffix($0.suffix) }
        }
    }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let method = request.httpMethod ?? "GET", path = request.url?.path ?? ""
        records.append(.init(method: method, path: path, body: request.httpBody ?? Data(), taskWasCancelled: Task.isCancelled))
        if FocusReceiptCommand.allCases.contains(where: { method == $0.method && path == "/ccapi/ver130" + $0.suffix }) {
            return reply
        }
        if method == "GET", path == "/ccapi" {
            return .init(statusCode: 200, body: Data(Self.discovery.utf8))
        }
        if method == "GET", path == "/ccapi/ver130/shooting/settings" {
            return .init(statusCode: 200, body: Data(#"{"whitebalance":{"value":"auto","ability":["auto"]}}"#.utf8))
        }
        if method == "GET", path == "/ccapi/ver130/shooting/liveview/flipdetail" {
            let geometry = Data(#"{"liveview":{"image":{"positionx":100,"positiony":200,"positionwidth":6000,"positionheight":4000}}}"#.utf8)
            return .init(statusCode: 200, headers: ["Content-Type": "application/octet-stream"],
                         body: packet(0, CCAPILiveViewTestFixture.jpeg) + packet(1, geometry))
        }
        if (method == "POST" || method == "DELETE"), path == "/ccapi/ver130/shooting/liveview" {
            return .init(statusCode: 204)
        }
        throw URLError(.unsupportedURL)
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    private func packet(_ type: UInt8, _ payload: Data) -> Data {
        let length = UInt32(payload.count)
        return Data([0xff, 0, type, UInt8((length >> 24) & 255), UInt8((length >> 16) & 255),
                     UInt8((length >> 8) & 255), UInt8(length & 255)]) + payload + Data([0xff, 0xff])
    }
    private static let discovery = #"{"ver130":[{"path":"/shooting/control/drivefocus","post":true},{"path":"/shooting/liveview/afframeposition","put":true},{"path":"/shooting/liveview/clickwb","post":true},{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/flipdetail","get":true},{"path":"/shooting/settings","get":true}]}"#
}
