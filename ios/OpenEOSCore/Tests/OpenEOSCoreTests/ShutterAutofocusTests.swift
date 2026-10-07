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

    private func assertStrictBooleans(_ requests: [ShutterRecoveryRequest]) throws {
        for request in requests {
            let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: request.body) as? [String: Any])
            let af = try XCTUnwrap(json["af"] as? NSNumber)
            XCTAssertEqual(CFGetTypeID(af), CFBooleanGetTypeID())
        }
    }
}

private actor ShutterAutofocusTransport: CameraHTTPTransport {
    let manualMethod: String?
    let failPress: Bool
    let shutterAdvertised: Bool
    let bridgeCapability: String?
    private var recorded: [ShutterRecoveryRequest] = []

    init(manualMethod: String? = nil, failPress: Bool = false, shutterAdvertised: Bool = true,
         bridgeCapability: String? = nil) {
        self.manualMethod = manualMethod
        self.failPress = failPress
        self.shutterAdvertised = shutterAdvertised
        self.bridgeCapability = bridgeCapability
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
}
