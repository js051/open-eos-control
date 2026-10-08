import Foundation
import CoreFoundation
import XCTest

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

@testable import OpenEOSCore

final class ShutterAutofocusTests: XCTestCase {
    func testDirectDefaultAndFalseSendOneStrictBooleanWithNoFocusModeWrite() async throws {
        let transport = ShutterAutofocusTransport()
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
        let capabilities = try await client.capabilities()
        XCTAssertTrue(capabilities.shutterAutofocusSupported)
        XCTAssertTrue(capabilities.observing(.stillCapture).shutterAutofocusSupported)
        _ = try await client.captureStill()
        _ = try await client.captureStill(autofocus: false)
        let writes = await transport.writes()
        XCTAssertEqual(writes.map(\.method), ["POST", "POST"])
        XCTAssertEqual(writes.map(\.autofocus), [true, false])
        XCTAssertTrue(writes.allSatisfy { $0.path == "/ccapi/ver100/shooting/control/shutterbutton" })
        try assertStrictBooleans(writes)
    }

    func testManualPostAndPutUseChoiceAndAlwaysReleaseWithoutAF() async throws {
        for method in ["POST", "PUT"] {
            for af in [true, false] {
                let transport = ShutterAutofocusTransport(manualMethod: method)
                let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
                try await client.initialize()
                _ = try await client.captureStill(autofocus: af)
                let writes = await transport.writes()
                XCTAssertEqual(writes.map(\.method), [method, method])
                XCTAssertEqual(writes.shutterActions, ["full_press", "release"])
                XCTAssertEqual(writes.map(\.autofocus), [af, false])
                try assertStrictBooleans(writes)
            }
        }
    }

    func testFailedFalsePressNeverFallsBackAndManualFailureStillReleases() async throws {
        for manual in [nil, "PUT", "POST"] as [String?] {
            let transport = ShutterAutofocusTransport(manualMethod: manual, failPress: true)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
            try await client.initialize()
            do {
                _ = try await client.captureStill(autofocus: false)
                XCTFail("Rejected shutter must fail")
            } catch {}
            let writes = await transport.writes()
            XCTAssertEqual(writes.count, manual == nil ? 1 : 2)
            XCTAssertTrue(writes.allSatisfy { $0.autofocus == false })
            if manual != nil { XCTAssertEqual(writes.shutterActions, ["full_press", "release"]) }
        }
    }

    func testSimulatorAndUnadvertisedFalseFailBeforeCameraRequest() async throws {
        for mode in [CCAPIConnectionMode.simulator, .camera] {
            let transport = ShutterAutofocusTransport(shutterAdvertised: false)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: mode, transport: transport)
            if mode == .camera { try await client.initialize() }
            let before = await transport.requests()
            do {
                _ = try await client.captureStill(autofocus: false)
                XCTFail("Unsupported AF choice must fail locally")
            } catch {}
            let after = await transport.requests()
            XCTAssertEqual(after, before)
        }
    }

    func testBridgeRequiresStrictCapabilityAndRetiresItWithSession() async throws {
        for capability in ["true", "false", "1", "\"true\"", "null"] {
            let transport = ShutterAutofocusTransport(bridgeCapability: capability)
            let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport)
            try await client.initialize()
            let capabilities = try await client.capabilities()
            XCTAssertEqual(capabilities.shutterAutofocusSupported, capability == "true")
            let before = await transport.requests()
            do {
                _ = try await client.captureStill(autofocus: false)
                XCTAssertEqual(capability, "true")
            } catch { XCTAssertNotEqual(capability, "true") }
            let after = await transport.requests()
            XCTAssertEqual(after.count - before.count, capability == "true" ? 1 : 0)
            _ = try await client.captureStill()
            let captures = await transport.writes().filter { $0.path.hasSuffix("/capture/still") }
            XCTAssertEqual(captures.map(\.autofocus), capability == "true" ? [false, true] : [true])
            await client.close()
            try await client.initialize()
            let reopened = await transport.requests()
            do {
                _ = try await client.captureStill(autofocus: false)
                XCTFail("New session must rediscover support")
            } catch {}
            let final = await transport.requests()
            XCTAssertEqual(final, reopened)
        }
    }

    func testLegacyBridgeDefaultsOnAndCannotSilentlyIgnoreFalse() async throws {
        let transport = ShutterAutofocusTransport()
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport)
        try await client.initialize()
        let capabilities = try await client.capabilities()
        XCTAssertFalse(capabilities.shutterAutofocusSupported)
        let before = await transport.requests()
        do { _ = try await client.captureStill(autofocus: false); XCTFail("Legacy server ignores shoot bodies") } catch {}
        let after = await transport.requests()
        XCTAssertEqual(after, before)
        _ = try await client.captureStill()
    }

    func testBridgeMapsOnlyAcknowledgedCaptureStatusReadbackFailure() async throws {
        let codes: [String?] = [
            "CAPTURE_STATUS_READBACK_FAILED",
            "CCAPI_UNREACHABLE",
            "CAPTURE_FAILED",
            nil,
            "SHUTTER_RELEASE_UNCONFIRMED",
            "capture_status_readback_failed",
            "CAPTURE_STATUS_READBACK_FAILED_OTHER",
        ]
        for code in codes {
            let transport = ShutterAutofocusTransport(
                bridgeCapability: "true", bridgeCaptureFailure: .http(code: code)
            )
            let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport)
            try await client.initialize()
            _ = try await client.capabilities()
            let before = await transport.requests()
            do {
                _ = try await client.captureStill(autofocus: false)
                XCTFail("The capture response must fail for \(code ?? "HTTP_502")")
            } catch let error as DesktopBridgeError {
                if code == "CAPTURE_STATUS_READBACK_FAILED" {
                    XCTAssertEqual(error, .captureStatusReadbackFailed)
                } else {
                    XCTAssertEqual(error, .http(
                        statusCode: 502, method: "POST",
                        url: "http://127.0.0.1:18181/v1/session/af-session/capture/still",
                        code: code ?? "HTTP_502", message: "Synthetic bridge failure.",
                        feature: nil, engine: nil
                    ))
                }
            }
            let after = await transport.requests()
            let captureRequests = Array(after.dropFirst(before.count))
            XCTAssertEqual(captureRequests.count, 1, "Capture must not add a retry, start, or stop")
            try assertSingleBridgeCapture(captureRequests)
            let releaseState = await client.shutterReleaseState()
            if code == "SHUTTER_RELEASE_UNCONFIRMED" {
                XCTAssertEqual(releaseState, CameraShutterReleaseState(
                    releaseRequired: true, releaseUnconfirmed: true, bulbExposureActive: nil
                ))
            } else {
                XCTAssertEqual(releaseState, .idle, "Readback failure must not create a stop obligation")
            }
            await client.close()
            // Closing may clean up an owned unconfirmed release. Count capture
            // separately so legitimate session cleanup is never called a replay.
            let closed = await transport.requests()
            try assertSingleBridgeCapture(closed)
        }
    }

    func testBridgeCaptureNetworkFailureIsNotAcknowledgedOrReplayed() async throws {
        let transport = ShutterAutofocusTransport(
            bridgeCapability: "true", bridgeCaptureFailure: .network
        )
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport)
        try await client.initialize()
        _ = try await client.capabilities()
        let before = await transport.requests()
        do {
            _ = try await client.captureStill(autofocus: false)
            XCTFail("A lost capture response must fail")
        } catch let error as URLError {
            XCTAssertEqual(error.code, .networkConnectionLost)
        }
        let after = await transport.requests()
        let captureRequests = Array(after.dropFirst(before.count))
        XCTAssertEqual(captureRequests.count, 1)
        try assertSingleBridgeCapture(captureRequests)
        let releaseState = await client.shutterReleaseState()
        XCTAssertEqual(releaseState, .idle)
        await client.close()
        let closed = await transport.requests()
        try assertSingleBridgeCapture(closed)
    }

    func testBridgeDoesNotMapCaptureReadbackCodeFromOtherOperations() async throws {
        for operation in ["status", "settings/iso"] {
            let path = "/v1/session/af-session/\(operation)"
            let code = "CAPTURE_STATUS_READBACK_FAILED"
            let transport = ShutterAutofocusTransport(bridgeOperationErrorCodes: [path: code])
            let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport)
            try await client.initialize()
            let before = await transport.requests()
            do {
                if operation == "status" {
                    _ = try await client.status()
                } else {
                    _ = try await client.setSetting(key: "iso", value: "100")
                }
                XCTFail("The operation's HTTP error must be preserved")
            } catch let error as DesktopBridgeError {
                XCTAssertEqual(error, .http(
                    statusCode: 502, method: operation == "status" ? "GET" : "POST",
                    url: "http://127.0.0.1:18181\(path)", code: code,
                    message: "Synthetic bridge failure.", feature: nil, engine: nil
                ))
            }
            let after = await transport.requests()
            XCTAssertEqual(Array(after.dropFirst(before.count)).map(\.path), [path])
            let releaseState = await client.shutterReleaseState()
            XCTAssertEqual(releaseState, .idle)
            await client.close()
            let closed = await transport.requests()
            XCTAssertFalse(closed.contains { $0.path.hasSuffix("/capture/still") })
        }
    }

    func testBridgeCaptureReadbackAndSharedStopDescriptionsAreDistinct() {
        XCTAssertEqual(
            DesktopBridgeError.captureStatusReadbackFailed.errorDescription,
            "The shutter command was acknowledged, but camera status could not be read. Check recent media without taking another photo."
        )
        XCTAssertEqual(
            DesktopBridgeError.shutterReleaseUnconfirmed.errorDescription,
            "Camera shutter or autofocus stop is not confirmed. Retry Stop before starting another camera operation."
        )
    }

    private func assertSingleBridgeCapture(_ requests: [ShutterRecoveryRequest]) throws {
        let captures = requests.filter { $0.path == "/v1/session/af-session/capture/still" }
        XCTAssertEqual(captures.map(\.method), ["POST"])
        XCTAssertEqual(captures.map(\.autofocus), [false])
        XCTAssertEqual(captures.first?.body, Data(#"{"af":false}"#.utf8))
        try assertStrictBooleans(captures)
    }

    private func assertStrictBooleans(_ requests: [ShutterRecoveryRequest]) throws {
        for request in requests {
            let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: request.body) as? [String: Any])
            let af = try XCTUnwrap(json["af"] as? NSNumber)
            XCTAssertEqual(CFGetTypeID(af), CFBooleanGetTypeID())
        }
    }
}

private actor ShutterAutofocusTransport: CameraHTTPTransport {
    enum BridgeCaptureFailure: Sendable {
        case http(code: String?)
        case network
    }

    let manualMethod: String?
    let failPress: Bool
    let shutterAdvertised: Bool
    let bridgeCapability: String?
    let bridgeCaptureFailure: BridgeCaptureFailure?
    let bridgeOperationErrorCodes: [String: String]
    private var recorded: [ShutterRecoveryRequest] = []

    init(manualMethod: String? = nil, failPress: Bool = false, shutterAdvertised: Bool = true,
         bridgeCapability: String? = nil, bridgeCaptureFailure: BridgeCaptureFailure? = nil,
         bridgeOperationErrorCodes: [String: String] = [:]) {
        self.manualMethod = manualMethod
        self.failPress = failPress
        self.shutterAdvertised = shutterAdvertised
        self.bridgeCapability = bridgeCapability
        self.bridgeCaptureFailure = bridgeCaptureFailure
        self.bridgeOperationErrorCodes = bridgeOperationErrorCodes
    }

    func requests() -> [ShutterRecoveryRequest] { recorded }
    func writes() -> [ShutterRecoveryRequest] { recorded.filter(\.isMutation) }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        throw URLError(.unsupportedURL)
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let record = ShutterRecoveryRequest(method: request.httpMethod ?? "GET", path: request.url!.path,
            body: request.httpBody ?? Data(), taskWasCancelled: Task.isCancelled)
        recorded.append(record)
        if record.path == "/v1/session/af-session/capture/still", let failure = bridgeCaptureFailure {
            switch failure {
            case let .http(code):
                return try bridgeHTTPFailure(code: code)
            case .network:
                throw URLError(.networkConnectionLost)
            }
        }
        if let code = bridgeOperationErrorCodes[record.path] {
            return try bridgeHTTPFailure(code: code)
        }
        var body = "{}"
        switch record.path {
        case "/ccapi":
            let shutter = shutterAdvertised
                ? ",{\"path\":\"/shooting/control/shutterbutton\(manualMethod == nil ? "" : "/manual")\",\"\((manualMethod ?? "POST").lowercased())\":true}"
                : ""
            body = "{\"ver100\":[{\"path\":\"/deviceinformation\",\"get\":true}\(shutter)]}"
        case "/health": body = #"{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}"#
        case "/v1/session": body = #"{"id":"af-session","engine":"ccapi"}"#
        case "/v1/session/af-session/capabilities":
            body = "{\"supported\":[\"STILL_CAPTURE\"]\(bridgeCapability.map { ",\"shutterAutofocusSupported\":\($0)" } ?? "")}"
        default: break
        }
        if failPress && record.isMutation && record.action != "release" {
            return CameraHTTPResponse(statusCode: 503, body: Data("{}".utf8))
        }
        return CameraHTTPResponse(statusCode: 200, body: Data(body.utf8))
    }

    private func bridgeHTTPFailure(code: String?) throws -> CameraHTTPResponse {
        var detail = ["message": "Synthetic bridge failure."]
        if let code { detail["code"] = code }
        return CameraHTTPResponse(
            statusCode: 502, body: try JSONSerialization.data(withJSONObject: ["error": detail])
        )
    }
}
