import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

@MainActor
final class ShutterAutofocusStateTests: XCTestCase {
    func testBridgeChoiceSendsNothingUntilCaptureAndResetsOnReconnect() async throws {
        let transport = AppShutterAutofocusTransport()
        let state = try makeState(transport)
        await state.connect()
        XCTAssertTrue(state.canChangeShutterAutofocus)
        XCTAssertTrue(state.shutterAutofocus)
        let before = await transport.requestCount()
        state.setShutterAutofocus(false)
        XCTAssertFalse(state.shutterAutofocus)
        let after = await transport.requestCount()
        XCTAssertEqual(after, before, "Changing intent must never send AF or shutter commands")
        await state.captureStill()
        let captured = await transport.captureChoices()
        XCTAssertEqual(captured, [false])
        XCTAssertNil(state.lastError)
        await state.disconnect()
        XCTAssertTrue(state.shutterAutofocus)
        await state.connect()
        XCTAssertTrue(state.shutterAutofocus)
        await state.captureStill()
        let reconnected = await transport.captureChoices()
        XCTAssertEqual(reconnected, [false, true])
        await state.disconnect()
    }

    func testVideoBusyAndLegacySessionCannotChangeChoiceOrDuplicateCapture() async throws {
        let transport = AppShutterAutofocusTransport()
        let state = try makeState(transport)
        await state.connect()
        state.captureMode = .video
        XCTAssertFalse(state.canChangeShutterAutofocus)
        state.setShutterAutofocus(false)
        XCTAssertTrue(state.shutterAutofocus)
        state.captureMode = .photo
        state.setShutterAutofocus(false)
        await transport.suspendNextCapture()
        let capture = Task { await state.captureStill() }
        for _ in 0..<1_000 {
            if await transport.captureIsWaiting() { break }
            try await Task.sleep(for: .milliseconds(1))
        }
        let waiting = await transport.captureIsWaiting()
        XCTAssertTrue(waiting)
        XCTAssertFalse(state.canChangeShutterAutofocus)
        state.setShutterAutofocus(true)
        XCTAssertFalse(state.shutterAutofocus)
        await state.captureStill()
        await transport.resumeCapture()
        await capture.value
        let choices = await transport.captureChoices()
        XCTAssertEqual(choices, [false], "Repeated capture while pending cannot send a second shutter")
        await state.disconnect()
        await transport.setSupport(false)
        await state.connect()
        XCTAssertFalse(state.showShutterAutofocus)
        state.setShutterAutofocus(false)
        XCTAssertTrue(state.shutterAutofocus)
        await state.captureStill()
        let legacy = await transport.captureChoices()
        XCTAssertEqual(legacy, [false, true])
        await state.disconnect()
    }

    func testRejectedFalseRemainsSelectedAndCanBeRetriedOnlyByAnotherUserCapture() async throws {
        let transport = AppShutterAutofocusTransport()
        let state = try makeState(transport)
        await state.connect()
        state.setShutterAutofocus(false)
        await transport.setFailure(true)
        await state.captureStill()
        XCTAssertNotNil(state.lastError)
        XCTAssertFalse(state.shutterAutofocus)
        let failed = await transport.captureChoices()
        XCTAssertEqual(failed, [false])
        XCTAssertTrue(state.canChangeShutterAutofocus)
        await transport.setFailure(false)
        await state.captureStill()
        let retried = await transport.captureChoices()
        XCTAssertEqual(retried, [false, false])
        XCTAssertNil(state.lastError)
        await state.disconnect()
    }

    func testCapabilityWithdrawalCannotTurnAFalseChoiceIntoAnAFRequest() async throws {
        let transport = AppShutterAutofocusTransport()
        let state = try makeState(transport)
        await state.connect()
        state.setShutterAutofocus(false)
        await transport.setSupport(false)
        await state.refresh()
        XCTAssertFalse(state.shutterAutofocus)
        XCTAssertFalse(state.shutterAutofocusAllowed)
        XCTAssertTrue(state.showShutterAutofocus, "Keep the unavailable choice visible so the user can resolve it")
        let before = await transport.requestCount()
        await state.captureStill()
        let after = await transport.requestCount()
        XCTAssertEqual(after, before)
        XCTAssertNotNil(state.lastError)
        state.setShutterAutofocus(true)
        await state.captureStill()
        let choices = await transport.captureChoices()
        XCTAssertEqual(choices, [true], "Only explicit user selection may restore AF on")
        await state.disconnect()
    }

    private func makeState(_ transport: AppShutterAutofocusTransport) throws -> CameraAppState {
        let defaults = UserDefaults(suiteName: "ShutterAutofocusStateTests.\(UUID().uuidString)")!
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
        })
        state.autoRefresh = false
        return state
    }
}

private actor AppShutterAutofocusTransport: CameraHTTPTransport {
    private var requests = 0
    private var choices: [Bool] = []
    private var support = true
    private var failCapture = false
    private var suspendCapture = false
    private var captureContinuation: CheckedContinuation<Void, Never>?

    func requestCount() -> Int { requests }
    func captureChoices() -> [Bool] { choices }
    func setSupport(_ value: Bool) { support = value }
    func setFailure(_ value: Bool) { failCapture = value }
    func suspendNextCapture() { suspendCapture = true }
    func captureIsWaiting() -> Bool { captureContinuation != nil }
    func resumeCapture() { captureContinuation?.resume(); captureContinuation = nil }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        throw URLError(.unsupportedURL)
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        requests += 1
        let path = request.url!.path
        let body: String
        switch path {
        case "/health": body = #"{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}"#
        case "/v1/session": body = #"{"id":"af-ui-session","engine":"ccapi"}"#
        case "/v1/session/af-ui-session/info": body = #"{"connected":true,"model":"Synthetic AF camera","serial":"TEST-AF-UI","api":"desktop-bridge/v1"}"#
        case "/v1/session/af-ui-session/capabilities":
            body = "{\"supported\":[\"STILL_CAPTURE\"],\"shutterAutofocusSupported\":\(support)}"
        case "/v1/session/af-ui-session/capture/still":
            let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
            choices.append(payload["af"] as! Bool)
            if suspendCapture {
                suspendCapture = false
                await withCheckedContinuation { captureContinuation = $0 }
            }
            if failCapture { return CameraHTTPResponse(statusCode: 503, body: Data("{}".utf8)) }
            body = #"{"connected":true,"recording":false,"mode":"Manual"}"#
        case "/v1/session/af-ui-session/status": body = #"{"connected":true,"recording":false,"mode":"Manual"}"#
        default: body = "{}"
        }
        return CameraHTTPResponse(statusCode: 200, body: Data(body.utf8))
    }
}
