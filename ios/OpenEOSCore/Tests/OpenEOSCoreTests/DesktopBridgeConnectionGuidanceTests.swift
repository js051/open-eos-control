import Foundation
import XCTest

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

@testable import OpenEOSCore

final class DesktopBridgeConnectionGuidanceTests: XCTestCase {
    private let health = #"{"ok":true,"service":"open-eos-control-bridge","version":"fixture"}"#
    private let session = #"{"id":"synthetic-guidance-session","engine":"ccapi"}"#
    private let endpoint = "http://bridge.invalid:18181"

    func testAllEightWireReasonsReachClassifierThroughSessionAndScan() async throws {
        let expected: [(String, CameraConnectionFailureReason)] = [
            ("AUTHENTICATION_REJECTED", .authenticationRejected), ("TLS_ERROR", .tlsError),
            ("HOST_NOT_FOUND", .hostNotFound), ("TIMEOUT", .timeout),
            ("UNREACHABLE", .unreachable), ("HTTP_ERROR", .httpError),
            ("DISCOVERY_FAILED", .discoveryFailed), ("ENDPOINT_NOT_FOUND", .endpointNotFound),
        ]
        XCTAssertEqual(Set(DesktopBridgeCameraConnectionReason.allCases.map(\.rawValue)), Set(expected.map(\.0)))
        for (raw, reason) in expected {
            for scan in [false, true] {
                let (error, requests) = try await failure(body: envelope(reason: raw), scan: scan)
                let cameraError = try XCTUnwrap(error as? DesktopBridgeCameraConnectionError)
                XCTAssertEqual(cameraError.reason.rawValue, raw)
                XCTAssertEqual(CameraConnectionFailureReason.classify(error), reason)
                XCTAssertEqual(cameraError.errorDescription, cameraError.httpError.errorDescription)
                guard case let .http(status, method, url, code, message, feature, engine) = cameraError.httpError else {
                    return XCTFail("The original HTTP error must remain inspectable")
                }
                XCTAssertEqual(status, 502)
                XCTAssertEqual(method, scan ? "GET" : "POST")
                XCTAssertEqual(url, endpoint + (scan ? "/v1/cameras" : "/v1/session"))
                XCTAssertEqual(code, "CCAPI_DISCOVERY_FAILED")
                XCTAssertEqual(message, "Synthetic diagnostic: HTTP 401 TLS timeout token")
                XCTAssertEqual(feature, "CAMERA_CONNECTION")
                XCTAssertEqual(engine, "ccapi")
                XCTAssertEqual(requests.map(\.path), ["/health", scan ? "/v1/cameras" : "/v1/session"])
            }
        }
    }

    func testMissingUnknownAndMalformedReasonsKeepLegacyHTTPError() async throws {
        let reasons: [Any?] = [nil, NSNull(), "", "FUTURE_REASON", "authentication_rejected",
                               " AUTHENTICATION_REJECTED ", "401", 401, true,
                               ["AUTHENTICATION_REJECTED"], ["reason": "AUTHENTICATION_REJECTED"]]
        for reason in reasons {
            let (error, requests) = try await failure(body: envelope(reason: reason))
            XCTAssertTrue(error is DesktopBridgeError)
            XCTAssertFalse(error is DesktopBridgeCameraConnectionError)
            XCTAssertEqual(CameraConnectionFailureReason.classify(error), .httpError)
            XCTAssertEqual(requests.count, 2, "No fallback or automatic retry")
        }
        let malformed = ["not JSON HTTP 401 TLS timeout", #"{"error":"AUTHENTICATION_REJECTED"}"#,
                         #"{"connectionReason":"AUTHENTICATION_REJECTED"}"#,
                         String(repeating: " ", count: 2_001) + (try envelope(reason: "AUTHENTICATION_REJECTED"))]
        for body in malformed {
            let (error, _) = try await failure(body: body)
            XCTAssertTrue(error is DesktopBridgeError)
            XCTAssertEqual(CameraConnectionFailureReason.classify(error), .httpError)
        }
    }

    func testOnlyExactStatusCodeAndEnginePermitCameraEvidence() async throws {
        for status in [400, 401, 403, 404, 500, 503] {
            let (error, _) = try await failure(status: status, body: envelope(reason: "AUTHENTICATION_REJECTED"))
            XCTAssertTrue(error is DesktopBridgeError)
            XCTAssertEqual(CameraConnectionFailureReason.classify(error), CameraConnectionFailureReason.httpStatus(status))
        }
        let values: [Any?] = [nil, NSNull(), 502, true, [], "", "AUTHENTICATION_REJECTED", "ccapi ", "CCAPI",
                              " CCAPI_DISCOVERY_FAILED", "CCAPI_DISCOVERY_FAILED ", "ccapi_discovery_failed"]
        for value in values {
            let (wrongCode, _) = try await failure(body: envelope(reason: "AUTHENTICATION_REJECTED", code: value))
            let (wrongEngine, _) = try await failure(body: envelope(reason: "AUTHENTICATION_REJECTED", engine: value))
            for error in [wrongCode, wrongEngine] {
                XCTAssertTrue(error is DesktopBridgeError)
                XCTAssertEqual(CameraConnectionFailureReason.classify(error), .httpError)
            }
        }
        let (wrongEngine, _) = try await failure(body: envelope(reason: "AUTHENTICATION_REJECTED", engine: "libgphoto2"))
        XCTAssertEqual(CameraConnectionFailureReason.classify(wrongEngine), .httpError)
    }

    func testSuccessIgnoresErrorShapedFieldsAndInstallsSession() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        let failureObject = try XCTUnwrap(JSONSerialization.jsonObject(
            with: Data(envelope(reason: "AUTHENTICATION_REJECTED").utf8)
        ) as? [String: Any])
        var body = failureObject
        body["id"] = "synthetic-guidance-session"
        try await transport.enqueue(method: "POST", path: "/v1/session", body: JSONSerialization.data(withJSONObject: body))
        let client = try DesktopBridgeClient(baseURL: endpoint, transport: transport)
        try await client.initialize()
        try await client.initialize()
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 2, "A successful session must not be recreated")
    }

    func testHealthAndEstablishedCommandsKeepExistingHTTPErrorType() async throws {
        let body = try envelope(reason: "AUTHENTICATION_REJECTED")
        let healthTransport = MockCameraHTTPTransport()
        await healthTransport.enqueueJSON(path: "/health", status: 502, body: body)
        let healthClient = try DesktopBridgeClient(baseURL: endpoint, transport: healthTransport)
        do {
            try await healthClient.initialize()
            XCTFail("Expected health error")
        } catch {
            XCTAssertTrue(error is DesktopBridgeError)
            XCTAssertEqual(CameraConnectionFailureReason.classify(error), .httpError)
        }
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(method: "POST", path: "/v1/session", body: session)
        await transport.enqueueJSON(path: "/v1/session/synthetic-guidance-session/status", status: 502, body: body)
        await transport.enqueueJSON(method: "POST", path: "/v1/session/synthetic-guidance-session/recording/stop", status: 502, body: body)
        let client = try DesktopBridgeClient(baseURL: endpoint, transport: transport)
        try await client.initialize()
        do {
            _ = try await client.status()
            XCTFail("Expected status error")
        } catch {
            XCTAssertTrue(error is DesktopBridgeError)
            XCTAssertEqual(CameraConnectionFailureReason.classify(error), .httpError)
        }
        do {
            _ = try await client.stopRecording()
            XCTFail("Expected stop error")
        } catch {
            XCTAssertTrue(error is DesktopBridgeError)
            XCTAssertEqual(CameraConnectionFailureReason.classify(error), .httpError)
        }
        let release = await client.shutterReleaseState()
        XCTAssertEqual(release, .idle)
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 4)
    }

    func testExplicitSessionRetryCanSucceedWithoutRetainingOldCameraEvidence() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        try await transport.enqueueJSON(method: "POST", path: "/v1/session", status: 502,
                                        body: envelope(reason: "AUTHENTICATION_REJECTED"))
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(method: "POST", path: "/v1/session", body: session)
        let client = try DesktopBridgeClient(baseURL: endpoint, transport: transport)
        do {
            try await client.initialize()
            XCTFail("Expected first connection failure")
        } catch {
            XCTAssertEqual(CameraConnectionFailureReason.classify(error), .authenticationRejected)
        }
        let beforeRetry = await transport.requests()
        XCTAssertEqual(beforeRetry.count, 2)
        try await client.initialize()
        try await client.initialize()
        let afterRetry = await transport.requests()
        XCTAssertEqual(afterRetry.map(\.path), ["/health", "/v1/session", "/health", "/v1/session"])
    }

    func testCaptureAndReleaseSafetyCodesRemainTypedWithSupplementalReason() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(method: "POST", path: "/v1/session", body: session)
        try await transport.enqueueJSON(method: "POST", path: "/v1/session/synthetic-guidance-session/capture/still",
                                    status: 502, body: envelope(reason: "AUTHENTICATION_REJECTED", code: "CAPTURE_STATUS_READBACK_FAILED"))
        try await transport.enqueueJSON(path: "/v1/session/synthetic-guidance-session/status", status: 502,
                                    body: envelope(reason: "AUTHENTICATION_REJECTED", code: "SHUTTER_RELEASE_UNCONFIRMED"))
        let client = try DesktopBridgeClient(baseURL: endpoint, transport: transport)
        try await client.initialize()
        do {
            _ = try await client.captureStill()
            XCTFail("Expected acknowledged capture readback failure")
        } catch {
            XCTAssertEqual(error as? DesktopBridgeError, .captureStatusReadbackFailed)
        }
        do {
            _ = try await client.status()
            XCTFail("Expected release warning")
        } catch {
            XCTAssertTrue(error is DesktopBridgeError)
        }
        let release = await client.shutterReleaseState()
        XCTAssertTrue(release.releaseRequired)
        XCTAssertTrue(release.releaseUnconfirmed)
    }

    func testTransportCancellationNeverBecomesCameraGuidance() async throws {
        for urlCancellation in [false, true] {
            for scan in [false, true] {
                let transport = BridgeGuidanceCancellationTransport(urlCancellation: urlCancellation)
                let client = try DesktopBridgeClient(baseURL: endpoint, transport: transport)
                do {
                    if scan { _ = try await client.discoverCameras() } else { try await client.initialize() }
                    XCTFail("Expected cancellation")
                } catch {
                    XCTAssertTrue(CameraConnectionFailureReason.isCancellation(error))
                    XCTAssertNil(CameraConnectionFailureReason.classify(error))
                }
                let count = await transport.requestCount()
                XCTAssertEqual(count, 2)
            }
        }
    }

    private func failure(status: Int = 502, body: String, scan: Bool = false) async throws -> (Error, [RecordedRequest]) {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(method: scan ? "GET" : "POST", path: scan ? "/v1/cameras" : "/v1/session",
                                    status: status, body: body)
        let client = try DesktopBridgeClient(baseURL: endpoint, transport: transport)
        do {
            if scan { _ = try await client.discoverCameras() } else { try await client.initialize() }
        } catch {
            return (error, await transport.requests())
        }
        XCTFail("Expected connection failure")
        throw MockTransportError.missingResponse("Expected connection failure")
    }

    private func envelope(reason: Any?, code: Any? = "CCAPI_DISCOVERY_FAILED", engine: Any? = "ccapi") throws -> String {
        var detail: [String: Any] = ["message": "Synthetic diagnostic: HTTP 401 TLS timeout token", "feature": "CAMERA_CONNECTION"]
        detail["code"] = code
        detail["engine"] = engine
        detail["connectionReason"] = reason
        return String(decoding: try JSONSerialization.data(withJSONObject: ["error": detail], options: [.sortedKeys]), as: UTF8.self)
    }
}

private actor BridgeGuidanceCancellationTransport: CameraHTTPTransport {
    let urlCancellation: Bool
    private var count = 0
    init(urlCancellation: Bool) { self.urlCancellation = urlCancellation }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        count += 1
        if request.url?.path == "/health" {
            return CameraHTTPResponse(statusCode: 200, body: Data(#"{"ok":true,"service":"open-eos-control-bridge"}"#.utf8))
        }
        if urlCancellation { throw URLError(.cancelled) }
        throw CancellationError()
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw CancellationError() }
    func requestCount() -> Int { count }
}
