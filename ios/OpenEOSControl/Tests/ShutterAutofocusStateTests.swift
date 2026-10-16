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

    func testLutSuccessAndFailureCannotOverwriteCameraErrorRaisedDuringImport() async throws {
        for fails in [false, true] {
            let transport = AppShutterAutofocusTransport()
            let gate = LutCameraErrorGate()
            let lut = try parseCubeLut("LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n", fallbackName: "candidate")
            let state = try makeState(transport, cubeLutLoader: { _ in
                await gate.wait()
                if fails { throw CocoaError(.fileReadNoPermission) }
                return lut
            })
            await state.connect()
            let pending = Task { await state.importCubeLut(from: URL(fileURLWithPath: "/synthetic/candidate.cube")) }
            let deadline = ContinuousClock.now.advanced(by: .seconds(5))
            while !(await gate.waiting()), ContinuousClock.now < deadline { await Task.yield() }
            let waiting = await gate.waiting()
            XCTAssertTrue(waiting)
            await transport.setFailure(true)
            await state.captureStill() // Injected transport, no socket or physical camera.
            let cameraError = state.lastError
            XCTAssertNotNil(cameraError)
            await gate.release()
            await pending.value
            XCTAssertEqual(state.lastError, cameraError)
            XCTAssertEqual(state.cubeLutImportError == nil, !fails)
            XCTAssertEqual(state.monitorSettings.cubeLut, fails ? nil : lut)
            XCTAssertFalse(state.isImportingCubeLut)
            state.cancelCubeLutImport()
            XCTAssertEqual(state.lastError, cameraError)
            state.clearCubeLut()
            XCTAssertEqual(state.lastError, cameraError)
            await state.disconnect()
        }
    }

    func testIdenticalCameraErrorTextHasANewOwnerAndSurvivesLutMutations() async throws {
        for renderWarning in [false, true] {
            let transport = AppShutterAutofocusTransport()
            let lut = try parseCubeLut("LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n", fallbackName: "candidate")
            let state = try makeState(transport, cubeLutLoader: { _ in lut })
            await state.connect()
            if renderWarning { state.reportCubeLutRenderFailure() }
            else { state.reportCubeLutImportError(CocoaError(.fileReadNoPermission)) }
            let identicalText = try XCTUnwrap(state.lastError)
            await transport.setFailureMessage(identicalText)
            await state.captureStill()
            XCTAssertEqual(state.lastError, identicalText)
            state.cancelCubeLutImport()
            XCTAssertEqual(state.lastError, identicalText, "A same-text camera write is not the old LUT error owner")
            state.clearCubeLut()
            XCTAssertEqual(state.lastError, identicalText)
            state.reportCubeLutImportError(CocoaError(.fileReadCorruptFile))
            XCTAssertEqual(state.lastError, identicalText, "A new LUT failure cannot overwrite the same-text camera error")
            await state.importCubeLut(from: URL(fileURLWithPath: "/synthetic/candidate.cube"))
            XCTAssertEqual(state.lastError, identicalText, "Success cannot clear a later same-text camera error")
            XCTAssertEqual(state.monitorSettings.cubeLut, lut)
            await state.disconnect()
        }
    }

    func testConnectedDiagnosticReportOmitsDetailedLutFailureCanaries() async throws {
        let transport = AppShutterAutofocusTransport()
        let state = try makeState(transport)
        await state.connect()
        let canaries = ["SYNTHETIC_LUT_FILENAME_ONLY.cube", "SYNTHETIC_LUT_TITLE_ONLY", "/synthetic/lut-private/location"]
        let error = NSError(domain: "SyntheticLutProvider", code: 1,
                            userInfo: [NSLocalizedDescriptionKey: canaries.joined(separator: " ")])
        state.reportCubeLutImportError(error)
        let report = try await state.diagnosticReport()
        for canary in canaries {
            XCTAssertTrue(state.cubeLutImportError?.contains(canary) == true)
            XCTAssertFalse(report.text.contains(canary))
        }
        XCTAssertTrue(report.text.contains("LUT_IMPORT_FAILED"))
        await state.disconnect()
    }

    private func makeState(_ transport: AppShutterAutofocusTransport,
                           cubeLutLoader: (@Sendable (URL) async throws -> CubeLut)? = nil) throws -> CameraAppState {
        let defaults = UserDefaults(suiteName: "ShutterAutofocusStateTests.\(UUID().uuidString)")!
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
        }, cubeLutLoader: cubeLutLoader)
        state.autoRefresh = false
        return state
    }
}

private actor AppShutterAutofocusTransport: CameraHTTPTransport {
    private var requests = 0
    private var choices: [Bool] = []
    private var support = true
    private var failCapture = false
    private var failureMessage: String?
    private var suspendCapture = false
    private var captureContinuation: CheckedContinuation<Void, Never>?

    func requestCount() -> Int { requests }
    func captureChoices() -> [Bool] { choices }
    func setSupport(_ value: Bool) { support = value }
    func setFailure(_ value: Bool) { failCapture = value }
    func setFailureMessage(_ value: String?) { failureMessage = value }
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
            if let failureMessage {
                throw NSError(domain: "SyntheticCamera", code: 1, userInfo: [NSLocalizedDescriptionKey: failureMessage])
            }
            if failCapture { return CameraHTTPResponse(statusCode: 503, body: Data("{}".utf8)) }
            body = #"{"connected":true,"recording":false,"mode":"Manual"}"#
        case "/v1/session/af-ui-session/status": body = #"{"connected":true,"recording":false,"mode":"Manual"}"#
        default: body = "{}"
        }
        return CameraHTTPResponse(statusCode: 200, body: Data(body.utf8))
    }
}

private actor LutCameraErrorGate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var released = false
    func wait() async {
        if released { return }
        await withCheckedContinuation { continuation = $0 }
    }
    func waiting() -> Bool { continuation != nil }
    func release() {
        released = true
        continuation?.resume()
        continuation = nil
    }
}
