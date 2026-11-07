import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

@MainActor
final class ConnectionRecoveryTests: XCTestCase {
    func testInvalidAddressRemainsInlineAndDoesNotReplaceUnrelatedError() async throws {
        try await withDefaults { defaults in
            let state = CameraAppState(defaults: defaults)
            state.baseURL = "not a URL"
            state.reportCubeLutRenderFailure()
            let unrelated = state.lastError
            await state.connect()
            XCTAssertEqual(state.connectionRecovery, ConnectionRecovery(mode: .ccapi, operation: .connect, reason: .invalidAddress))
            XCTAssertEqual(state.lastError, unrelated)
            XCTAssertFalse(state.connected)
            XCTAssertFalse(state.isBusy(.connect))
            XCTAssertFalse(state.connectionRecovery?.needsCameraAuthentication ?? true)
        }
    }

    func testOnlyProvenDirectCameraAuthenticationOpensCameraFields() async throws {
        let cases: [(Error, CameraConnectionFailureReason)] = [
            (CCAPIError.http(statusCode: 401, method: "GET", url: "synthetic", body: "private"), .authenticationRejected),
            (CCAPIError.http(statusCode: 403, method: "GET", url: "synthetic", body: "private"), .authenticationRejected),
            (CCAPIError.http(statusCode: 404, method: "GET", url: "synthetic", body: "HTTP 401"), .endpointNotFound),
            (URLError(.secureConnectionFailed), .tlsError),
            (URLError(.cannotFindHost), .hostNotFound),
            (URLError(.timedOut), .timeout),
            (URLError(.notConnectedToInternet), .unreachable),
            (RecoveryForgedMessage(), .unknown),
        ]
        for (failure, reason) in cases {
            try await withDefaults { defaults in
                var attempts = 0
                let state = CameraAppState(defaults: defaults, sessionFactory: {
                    attempts += 1
                    throw failure
                })
                await state.connect()
                let recovery = try XCTUnwrap(state.connectionRecovery)
                XCTAssertEqual(recovery.reason, reason)
                XCTAssertEqual(recovery.needsCameraAuthentication, reason == .authenticationRejected)
                XCTAssertNil(state.lastError, "Untrusted error text must not enter the alert/diagnostic state")
                XCTAssertEqual(attempts, 1)
                state.username = "synthetic-corrected-user"
                state.password = "synthetic-corrected-password"
                XCTAssertNil(state.connectionRecovery)
                XCTAssertEqual(attempts, 1, "Editing settings never initiates a connection")
                XCTAssertNil(defaults.string(forKey: "camera-password"))
            }
        }
    }

    func testBridgeAuthenticationNeverOpensCameraFieldsOrUsesCameraFailureText() async throws {
        for status in [401, 403, 502] {
            try await withDefaults { defaults in
                let state = CameraAppState(defaults: defaults, sessionFactory: {
                    throw DesktopBridgeError.http(statusCode: status, method: "GET", url: "synthetic",
                        code: "AUTHENTICATION_REJECTED", message: "camera HTTP 401 password rejected",
                        feature: nil, engine: "ccapi")
                })
                state.connectionMode = .desktopBridge
                state.bridgeToken = "synthetic-memory-only-token"
                await state.connect()
                let recovery = try XCTUnwrap(state.connectionRecovery)
                XCTAssertFalse(recovery.needsCameraAuthentication)
                XCTAssertEqual(recovery.messageKey, status == 502 ? "connection_http_error" : "connection_bridge_authentication")
                XCTAssertNil(state.lastError)
                XCTAssertFalse(defaults.dictionaryRepresentation().values.contains { ($0 as? String) == state.bridgeToken })
            }
        }
    }

    func testCorrectedConnectionRequiresExplicitRetryThenClearsGuidance() async throws {
        try await withDefaults { defaults in
            let transport = RecoveryConnectTransport(outcome: .success)
            var attempts = 0
            let state = CameraAppState(defaults: defaults, sessionFactory: {
                attempts += 1
                if attempts == 1 {
                    throw CCAPIError.http(statusCode: 401, method: "GET", url: "synthetic", body: "synthetic")
                }
                return .ccapi(try CCAPIClient(baseURL: "http://camera.invalid:8080", mode: .camera, transport: transport))
            })
            state.autoRefresh = false
            await state.connect()
            XCTAssertEqual(state.connectionRecovery?.reason, .authenticationRejected)
            XCTAssertEqual(attempts, 1)
            state.password = "synthetic-corrected-password"
            XCTAssertNil(state.connectionRecovery)
            XCTAssertEqual(attempts, 1)
            await state.connect()
            XCTAssertEqual(attempts, 2)
            XCTAssertTrue(state.connected)
            XCTAssertEqual(state.info?.model, "Synthetic Recovery EOS")
            XCTAssertNil(state.connectionRecovery)
            XCTAssertNil(state.lastError)
            let requests = await transport.requests()
            XCTAssertTrue(requests.allSatisfy { $0.httpMethod == "GET" && $0.url?.host == "camera.invalid" })
            await state.disconnect()
        }
    }

    func testEditingOrLeavingDuringConnectionRejectsLateSuccessAndFailure() async throws {
        for change in RecoveryConfigurationChange.allCases {
            for outcome in [RecoveryConnectTransport.Outcome.success, .authentication] {
                try await withDefaults { defaults in
                    let transport = RecoveryConnectTransport(outcome: outcome, holdFirst: true)
                    let state = CameraAppState(defaults: defaults, sessionFactory: {
                        .ccapi(try CCAPIClient(baseURL: "http://camera.invalid:8080", mode: .camera, transport: transport))
                    })
                    state.autoRefresh = false
                    let connecting = Task { await state.connect() }
                    do {
                        try await waitForGate(transport)
                        change.apply(state)
                        state.reportCubeLutRenderFailure()
                        let savedError = state.lastError
                        await transport.release()
                        await connecting.value
                        XCTAssertNil(state.connectionRecovery, "Stale \(change) must not publish recovery")
                        XCTAssertEqual(state.lastError, savedError)
                        XCTAssertFalse(state.connected)
                        XCTAssertFalse(state.busyOperations.contains(.connect))
                    } catch {
                        connecting.cancel()
                        await transport.release()
                        await connecting.value
                        await state.disconnect()
                        throw error
                    }
                    await state.disconnect()
                }
            }
        }
    }

    func testCancelledConnectDoesNotPublishRecoveryOrStartFallbackRequests() async throws {
        try await withDefaults { defaults in
            let transport = RecoveryConnectTransport(outcome: .authentication, holdFirst: true)
            let state = CameraAppState(defaults: defaults, sessionFactory: {
                .ccapi(try CCAPIClient(baseURL: "http://camera.invalid:8080", mode: .camera, transport: transport))
            })
            let connecting = Task { await state.connect() }
            do {
                try await waitForGate(transport)
                connecting.cancel()
                await transport.release()
                await connecting.value
                XCTAssertNil(state.connectionRecovery)
                XCTAssertNil(state.lastError)
                XCTAssertFalse(state.connected)
                XCTAssertFalse(state.isBusy(.connect))
                let requests = await transport.requests()
                XCTAssertEqual(requests.count, 1)
            } catch {
                connecting.cancel()
                await transport.release()
                await connecting.value
                await state.disconnect()
                throw error
            }
            await state.disconnect()
        }
    }

    func testLocalizedRecoveryKeysExistInBothLanguages() throws {
        let appBundle = Bundle(for: CameraAppState.self)
        for language in ["en", "zh-Hant"] {
            let path = try XCTUnwrap(appBundle.path(forResource: language, ofType: "lproj"))
            let bundle = try XCTUnwrap(Bundle(path: path))
            for mode in AppConnectionMode.allCases {
                for reason in CameraConnectionFailureReason.allCases {
                    let recovery = ConnectionRecovery(mode: mode, operation: .connect, reason: reason)
                    for key in [recovery.messageKey, recovery.retryKey] {
                        XCTAssertNotEqual(bundle.localizedString(forKey: key, value: nil, table: nil), key, "\(language): \(key)")
                    }
                }
            }
        }
    }

    func testTransportCancellationWithoutTaskFlagProducesNoRecovery() async throws {
        for error in [CancellationError() as Error, URLError(.cancelled) as Error] {
            try await withDefaults { defaults in
                let state = CameraAppState(defaults: defaults, sessionFactory: { throw error })
                await state.connect()
                XCTAssertNil(state.connectionRecovery)
                XCTAssertNil(state.lastError)
                XCTAssertFalse(state.isBusy(.connect))
            }
        }
    }

    func testConnectIsRejectedDuringHeldRescanWithPriorSelectedCamera() async throws {
        for cancelRescan in [false, true] {
            try await withDefaults { defaults in
                let discovery = RecoveryOverlapDiscovery(holdRescan: true)
                var sessionCreations = 0
                let state = CameraAppState(defaults: defaults, sessionFactory: {
                    sessionCreations += 1
                    throw DesktopBridgeError.http(statusCode: 401, method: "GET", url: "synthetic",
                        code: "UNAUTHORIZED", message: "Synthetic authentication failure", feature: nil, engine: nil)
                }, bridgeCameraDiscovery: { _, _ in await discovery.scan() })
                state.connectionMode = .desktopBridge
                await state.scanBridgeCameras()
                XCTAssertEqual(state.selectedBridgeCameraID, "synthetic-overlap-camera")
                XCTAssertTrue(state.canConnect)
                await state.connect()
                let previousRecovery = try XCTUnwrap(state.connectionRecovery)
                XCTAssertEqual(previousRecovery.reason, .authenticationRejected)
                XCTAssertEqual(sessionCreations, 1)

                let scan = Task { await state.scanBridgeCameras() }
                do {
                    try await waitForGate(discovery)
                    XCTAssertEqual(state.bridgeScanState, .scanning)
                    XCTAssertNil(state.connectionRecovery)
                    await state.connect()
                    XCTAssertEqual(sessionCreations, 1, "A held rescan must reject connection before session creation")
                    XCTAssertTrue(state.busyOperations.contains(.scan))
                    XCTAssertFalse(state.busyOperations.contains(.connect))
                    XCTAssertNil(state.connectionRecovery, "The rejected connection must not publish newer guidance")
                    if cancelRescan { scan.cancel() }
                    await discovery.release()
                    await scan.value
                    XCTAssertEqual(state.bridgeScanState, .completed)
                    XCTAssertEqual(state.selectedBridgeCameraID, "synthetic-overlap-camera")
                    XCTAssertEqual(state.connectionRecovery, cancelRescan ? previousRecovery : nil)
                    let calls = await discovery.callCount()
                    XCTAssertEqual(calls, 2)

                    await state.connect()
                    XCTAssertEqual(sessionCreations, 2, "Connect is admitted after the exact rescan settles")
                    XCTAssertEqual(state.connectionRecovery, previousRecovery)
                    XCTAssertFalse(state.isBusy(.scan))
                } catch {
                    scan.cancel()
                    await discovery.release()
                    await scan.value
                    await state.disconnect()
                    throw error
                }
                await state.disconnect()
            }
        }
    }

    func testScanIsRejectedDuringHeldConnectionWithoutOverwritingItsGuidance() async throws {
        for cancelConnection in [false, true] {
            try await withDefaults { defaults in
                let discovery = RecoveryOverlapDiscovery(holdRescan: false)
                let transport = RecoveryConnectTransport(outcome: .authentication, holdFirst: true)
                var sessionCreations = 0
                let state = CameraAppState(defaults: defaults, sessionFactory: {
                    sessionCreations += 1
                    return .desktopBridge(try DesktopBridgeClient(
                        baseURL: "http://bridge.invalid:18181", cameraID: "synthetic-overlap-camera", transport: transport
                    ))
                }, bridgeCameraDiscovery: { _, _ in await discovery.scan() })
                state.connectionMode = .desktopBridge
                await state.scanBridgeCameras()
                let cameras = state.bridgeCameras
                let selection = state.selectedBridgeCameraID
                let connecting = Task { await state.connect() }
                do {
                    try await waitForGate(transport)
                    await state.scanBridgeCameras()
                    let calls = await discovery.callCount()
                    XCTAssertEqual(calls, 1, "A held connection must reject scan before discovery is called")
                    XCTAssertEqual(sessionCreations, 1)
                    XCTAssertTrue(state.busyOperations.contains(.connect))
                    XCTAssertFalse(state.busyOperations.contains(.scan))
                    XCTAssertEqual(state.bridgeScanState, .completed)
                    XCTAssertEqual(state.bridgeCameras, cameras)
                    XCTAssertEqual(state.selectedBridgeCameraID, selection)
                    if cancelConnection { connecting.cancel() }
                    await transport.release()
                    await connecting.value
                    XCTAssertEqual(state.connectionRecovery?.reason, cancelConnection ? nil : .authenticationRejected)
                    XCTAssertFalse(state.busyOperations.contains(.scan))
                    XCTAssertFalse(state.busyOperations.contains(.connect))
                    let settledCalls = await discovery.callCount()
                    XCTAssertEqual(settledCalls, 1, "No old scan may finish or clear the connection's guidance")

                    await state.scanBridgeCameras()
                    let retriedCalls = await discovery.callCount()
                    XCTAssertEqual(retriedCalls, 2, "Scan is admitted after the exact connection settles")
                    XCTAssertEqual(state.bridgeScanState, .completed)
                    XCTAssertNil(state.connectionRecovery)
                } catch {
                    connecting.cancel()
                    await transport.release()
                    await connecting.value
                    await state.disconnect()
                    throw error
                }
                await state.disconnect()
            }
        }
    }

    private func waitForGate(_ discovery: RecoveryOverlapDiscovery) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await discovery.isWaiting() { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Rescan did not reach the controlled discovery gate")
        throw URLError(.timedOut)
    }

    private func waitForGate(_ transport: RecoveryConnectTransport) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await transport.isWaiting() { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Connection did not reach the controlled transport gate")
        throw URLError(.timedOut)
    }

    private func withDefaults(_ body: (UserDefaults) async throws -> Void) async throws {
        let suite = "ConnectionRecoveryTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        try await body(defaults)
    }
}

private enum RecoveryConfigurationChange: CaseIterable {
    case url, username, password, mode, token, bridgeURL, selection, urlRoundTrip, disconnect

    @MainActor
    func apply(_ state: CameraAppState) {
        switch self {
        case .url: state.baseURL = "http://replacement.invalid:8080"
        case .username: state.username = "synthetic-replacement"
        case .password: state.password = "synthetic-replacement"
        case .mode: state.connectionMode = .desktopBridge
        case .token: state.bridgeToken = "synthetic-replacement"
        case .bridgeURL: state.bridgeURL = "http://bridge.invalid:18182"
        case .selection: state.selectedBridgeCameraID = "synthetic-replacement"
        case .urlRoundTrip:
            let original = state.baseURL
            state.baseURL = "http://replacement.invalid:8080"
            state.baseURL = original
        case .disconnect: state.requestDisconnect()
        }
    }
}

private struct RecoveryForgedMessage: LocalizedError {
    var errorDescription: String? { "HTTP 401 password token TLS timeout synthetic-private-value" }
}

/// The initial scan seeds a real selection; only the explicit rescan can be held.
private actor RecoveryOverlapDiscovery {
    private let holdRescan: Bool
    private var calls = 0
    private var continuation: CheckedContinuation<Void, Never>?

    init(holdRescan: Bool) { self.holdRescan = holdRescan }

    func scan() async -> [DesktopBridgeCamera] {
        calls += 1
        if holdRescan, calls == 2 {
            await withCheckedContinuation { continuation = $0 }
        }
        return [DesktopBridgeCamera(id: "synthetic-overlap-camera", model: "Synthetic Overlap EOS",
                                    port: "synthetic:usb", engine: "fixture")]
    }

    func isWaiting() -> Bool { continuation != nil }
    func release() { continuation?.resume(); continuation = nil }
    func callCount() -> Int { calls }
}

/// No network. Cancellation deliberately does not resume the held discovery request.
private actor RecoveryConnectTransport: CameraHTTPTransport {
    enum Outcome: Sendable { case success, authentication }
    private let outcome: Outcome
    private var holdFirst: Bool
    private var continuation: CheckedContinuation<Void, Never>?
    private var recorded: [URLRequest] = []

    init(outcome: Outcome, holdFirst: Bool = false) {
        self.outcome = outcome
        self.holdFirst = holdFirst
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        recorded.append(request)
        if holdFirst {
            holdFirst = false
            await withCheckedContinuation { continuation = $0 }
        }
        if outcome == .authentication {
            return CameraHTTPResponse(statusCode: recorded.count == 1 ? 401 : 404)
        }
        switch request.url?.path {
        case "/ccapi":
            return CameraHTTPResponse(statusCode: 200, body: Data(#"{"ver100":[{"path":"/deviceinformation","get":true}]}"#.utf8))
        case "/ccapi/ver100/deviceinformation":
            return CameraHTTPResponse(statusCode: 200, body: Data(#"{"productname":"Synthetic Recovery EOS"}"#.utf8))
        default: return CameraHTTPResponse(statusCode: 404)
        }
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw RecoveryForgedMessage() }
    func isWaiting() -> Bool { continuation != nil }
    func release() { continuation?.resume(); continuation = nil }
    func requests() -> [URLRequest] { recorded }
}
