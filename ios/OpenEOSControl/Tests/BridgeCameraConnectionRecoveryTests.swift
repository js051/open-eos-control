import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

@MainActor
final class BridgeCameraConnectionRecoveryTests: XCTestCase {
    private let mappings: [(String, CameraConnectionFailureReason, String)] = [
        ("AUTHENTICATION_REJECTED", .authenticationRejected, "connection_bridge_camera_authentication"),
        ("TLS_ERROR", .tlsError, "connection_bridge_camera_tls_error"),
        ("HOST_NOT_FOUND", .hostNotFound, "connection_bridge_camera_host_not_found"),
        ("TIMEOUT", .timeout, "connection_bridge_camera_timeout"),
        ("UNREACHABLE", .unreachable, "connection_bridge_camera_unreachable"),
        ("HTTP_ERROR", .httpError, "connection_bridge_camera_http_error"),
        ("DISCOVERY_FAILED", .discoveryFailed, "connection_bridge_camera_discovery_failed"),
        ("ENDPOINT_NOT_FOUND", .endpointNotFound, "connection_bridge_camera_endpoint_missing"),
    ]

    func testEveryWireReasonProducesCameraViaBridgeGuidanceForConnectAndScan() async throws {
        for (raw, reason, key) in mappings {
            for scan in [false, true] {
                try await withDefaults { defaults in
                    let transport = BridgeCameraRecoveryTransport(response: try response(reason: raw))
                    let state = try makeState(defaults: defaults, transport: transport)
                    state.reportCubeLutRenderFailure()
                    let unrelatedError = state.lastError
                    if scan { await state.scanBridgeCameras() } else { await state.connect() }
                    let recovery = try XCTUnwrap(state.connectionRecovery)
                    XCTAssertEqual(recovery.reason, reason)
                    XCTAssertEqual(recovery.source, .cameraViaBridge)
                    XCTAssertEqual(recovery.mode, .desktopBridge)
                    XCTAssertEqual(recovery.operation, scan ? .scan : .connect)
                    XCTAssertEqual(recovery.messageKey, key)
                    XCTAssertEqual(recovery.nextStepKey, "connection_bridge_camera_retry_help")
                    XCTAssertFalse(recovery.needsCameraAuthentication)
                    XCTAssertFalse(recovery.canEditLocalSettings, "Bridge camera settings are not editable in the mobile token field")
                    XCTAssertEqual(state.lastError, unrelatedError, "Do not publish wire diagnostics into operation alerts")
                    XCTAssertFalse(state.connected)
                    XCTAssertFalse(state.isBusy(.connect))
                    XCTAssertFalse(state.isBusy(.scan))
                    if scan { XCTAssertEqual(state.bridgeScanState, .failed) }
                    let requests = await transport.requests()
                    XCTAssertEqual(requests.map { $0.url?.path }, ["/health", scan ? "/v1/cameras" : "/v1/session"])
                    XCTAssertEqual(requests.map(\.httpMethod), ["GET", scan ? "GET" : "POST"])
                    await state.disconnect()
                }
            }
        }
    }

    func testBridge401And403StillPointToTokenDespiteForgedCameraReason() async throws {
        for status in [401, 403] {
            try await withDefaults { defaults in
                let transport = BridgeCameraRecoveryTransport(response: try response(status: status, reason: "AUTHENTICATION_REJECTED"))
                let state = try makeState(defaults: defaults, transport: transport)
                await state.connect()
                let recovery = try XCTUnwrap(state.connectionRecovery)
                XCTAssertEqual(recovery.source, .selectedEndpoint)
                XCTAssertEqual(recovery.reason, .authenticationRejected)
                XCTAssertEqual(recovery.messageKey, "connection_bridge_authentication")
                XCTAssertTrue(recovery.canEditLocalSettings)
                XCTAssertFalse(recovery.needsCameraAuthentication)
                XCTAssertEqual(recovery.nextStepKey, "connection_edit_then_connect")
                await state.disconnect()
            }
        }
    }

    func testLegacyAndUntrustedWireEvidenceStayGenericInApp() async throws {
        let cases = [
            try response(reason: nil), try response(reason: NSNull()),
            try response(reason: "FUTURE_REASON"), try response(reason: 401),
            try response(reason: ["AUTHENTICATION_REJECTED"]),
            try response(reason: "AUTHENTICATION_REJECTED", code: "OTHER_ERROR"),
            try response(reason: "AUTHENTICATION_REJECTED", engine: "libgphoto2"),
            try response(status: 503, reason: "AUTHENTICATION_REJECTED"),
            CameraHTTPResponse(statusCode: 502, body: Data("HTTP 401 TLS timeout".utf8)),
        ]
        for failure in cases {
            try await withDefaults { defaults in
                let transport = BridgeCameraRecoveryTransport(response: failure)
                let state = try makeState(defaults: defaults, transport: transport)
                await state.connect()
                let recovery = try XCTUnwrap(state.connectionRecovery)
                XCTAssertEqual(recovery.source, .selectedEndpoint)
                XCTAssertEqual(recovery.reason, .httpError)
                XCTAssertEqual(recovery.messageKey, "connection_http_error")
                XCTAssertFalse(recovery.needsCameraAuthentication)
                XCTAssertNil(state.lastError)
                await state.disconnect()
            }
        }
    }

    func testOnlyExplicitRetryReadsNewEvidence() async throws {
        try await withDefaults { defaults in
            let transport = BridgeCameraRecoveryTransport(response: try response(reason: "AUTHENTICATION_REJECTED"))
            let state = try makeState(defaults: defaults, transport: transport)
            await state.connect()
            let first = try XCTUnwrap(state.connectionRecovery)
            await transport.replaceResponse(try response(reason: "FUTURE_REASON"))
            XCTAssertEqual(state.connectionRecovery, first)
            let beforeRetry = await transport.requests()
            XCTAssertEqual(beforeRetry.count, 2)
            await state.connect()
            XCTAssertEqual(state.connectionRecovery?.source, .selectedEndpoint)
            XCTAssertEqual(state.connectionRecovery?.reason, .httpError)
            let afterRetry = await transport.requests()
            XCTAssertEqual(afterRetry.map { $0.url?.path }, ["/health", "/v1/session", "/health", "/v1/session"])
            await state.disconnect()
        }
    }

    func testCancelledOrRetiredRequestCannotPublishLateCameraReason() async throws {
        for scan in [false, true] {
            for change in BridgeGuidanceRetirement.allCases {
                try await withDefaults { defaults in
                    let transport = BridgeCameraRecoveryTransport(response: try response(reason: "AUTHENTICATION_REJECTED"), hold: true)
                    let state = try makeState(defaults: defaults, transport: transport)
                    let pending = Task {
                        if scan { await state.scanBridgeCameras() } else { await state.connect() }
                    }
                    do {
                        try await waitForGate(transport)
                        switch change {
                        case .cancel: pending.cancel()
                        case .url: state.bridgeURL = "http://replacement.invalid:18182"
                        case .token: state.bridgeToken = "synthetic-replacement-token"
                        case .mode: state.connectionMode = .ccapi
                        case .disconnect: state.requestDisconnect()
                        }
                        await transport.release()
                        await pending.value
                        XCTAssertNil(state.connectionRecovery, "Late \(change) must not publish guidance")
                        XCTAssertNil(state.lastError)
                        XCTAssertFalse(state.connected)
                        XCTAssertFalse(state.isBusy(.connect))
                        XCTAssertFalse(state.isBusy(.scan))
                        if scan { XCTAssertNotEqual(state.bridgeScanState, .failed) }
                        let requests = await transport.requests()
                        XCTAssertEqual(requests.count, 2)
                    } catch {
                        pending.cancel()
                        await transport.release()
                        await pending.value
                        await state.disconnect()
                        throw error
                    }
                    await state.disconnect()
                }
            }
        }
    }

    func testCameraViaBridgeMessagesAndInstructionsExistInBothLanguages() throws {
        let appBundle = Bundle(for: CameraAppState.self)
        for language in ["en", "zh-Hant"] {
            let path = try XCTUnwrap(appBundle.path(forResource: language, ofType: "lproj"))
            let bundle = try XCTUnwrap(Bundle(path: path))
            for (_, reason, expectedKey) in mappings {
                let recovery = ConnectionRecovery(mode: .desktopBridge, operation: .connect, reason: reason, source: .cameraViaBridge)
                XCTAssertEqual(recovery.messageKey, expectedKey)
                for key in [recovery.messageKey, recovery.nextStepKey, recovery.retryKey] {
                    let text = bundle.localizedString(forKey: key, value: nil, table: nil)
                    XCTAssertNotEqual(text, key, "\(language): \(key)")
                    XCTAssertFalse(text.isEmpty)
                }
            }
        }
    }

    private func makeState(defaults: UserDefaults, transport: BridgeCameraRecoveryTransport) throws -> CameraAppState {
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            .desktopBridge(try DesktopBridgeClient(baseURL: "http://bridge.invalid:18181", transport: transport))
        }, bridgeCameraDiscovery: { _, _ in
            try await DesktopBridgeClient(baseURL: "http://bridge.invalid:18181", transport: transport).discoverCameras()
        })
        state.connectionMode = .desktopBridge
        state.autoRefresh = false
        return state
    }

    private func response(status: Int = 502, reason: Any?, code: String = "CCAPI_DISCOVERY_FAILED", engine: String = "ccapi") throws -> CameraHTTPResponse {
        var detail: [String: Any] = ["code": code, "engine": engine, "message": "Synthetic HTTP 401 TLS timeout token diagnostic"]
        detail["connectionReason"] = reason
        return CameraHTTPResponse(statusCode: status, body: try JSONSerialization.data(withJSONObject: ["error": detail]))
    }

    private func waitForGate(_ transport: BridgeCameraRecoveryTransport) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await transport.isWaiting() { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Connection did not reach the controlled Bridge response gate")
        throw URLError(.timedOut)
    }

    private func withDefaults(_ body: (UserDefaults) async throws -> Void) async throws {
        let suite = "BridgeCameraConnectionRecoveryTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        try await body(defaults)
    }
}

private enum BridgeGuidanceRetirement: CaseIterable { case cancel, url, token, mode, disconnect }

/// No network or camera commands. The held error can arrive even after cancellation.
private actor BridgeCameraRecoveryTransport: CameraHTTPTransport {
    private var response: CameraHTTPResponse
    private var hold: Bool
    private var continuation: CheckedContinuation<Void, Never>?
    private var recorded: [URLRequest] = []

    init(response: CameraHTTPResponse, hold: Bool = false) { self.response = response; self.hold = hold }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        recorded.append(request)
        if request.url?.path == "/health" {
            return CameraHTTPResponse(statusCode: 200, body: Data(#"{"ok":true,"service":"open-eos-control-bridge"}"#.utf8))
        }
        guard ["/v1/session", "/v1/cameras"].contains(request.url?.path ?? "") else { throw URLError(.unsupportedURL) }
        if hold {
            hold = false
            await withCheckedContinuation { continuation = $0 }
        }
        return response
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    func isWaiting() -> Bool { continuation != nil }
    func release() { continuation?.resume(); continuation = nil }
    func replaceResponse(_ response: CameraHTTPResponse) { self.response = response }
    func requests() -> [URLRequest] { recorded }
}
