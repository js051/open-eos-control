import Foundation
import XCTest

final class OpenEOSControlUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testOfflineCameraWorkflowInPortraitAndLandscape() throws {
        let app = launch(appLanguage: "english", appleLanguage: "en", locale: "en_US")
        let preview = app.buttons["offline-preview-button"]
        XCTAssertTrue(preview.waitForExistence(timeout: 8))
        preview.tap()

        XCTAssertTrue(app.staticTexts["Offline UI preview"].waitForExistence(timeout: 5))
        addScreenshot(name: "control-portrait")

        let shutter = app.buttons["shutter-button"]
        XCTAssertTrue(shutter.waitForExistence(timeout: 5))
        XCTAssertTrue(shutter.isHittable)
        XCTAssertLessThanOrEqual(shutter.frame.maxY, app.windows.firstMatch.frame.maxY - 8)

        XCUIDevice.shared.orientation = .landscapeLeft
        let moreActions = app.buttons["more-actions-button"]
        XCTAssertTrue(moreActions.waitForExistence(timeout: 5))
        addScreenshot(name: "control-landscape")

        moreActions.tap()
        let halfPress = app.buttons["Half-press shutter"]
        XCTAssertTrue(halfPress.waitForExistence(timeout: 3))
        halfPress.tap()
        XCTAssertTrue(halfPress.waitForNonExistence(timeout: 3))
        moreActions.tap()
        let debug = app.buttons["Debug"]
        XCTAssertTrue(debug.waitForExistence(timeout: 3))
        debug.tap()
        XCTAssertTrue(app.buttons["copy-diagnostic-button"].waitForExistence(timeout: 5))
        addScreenshot(name: "debug-landscape")
    }

    func testTraditionalChineseConnectionScreen() throws {
        let app = launch(
            appLanguage: "traditionalChinese",
            appleLanguage: "zh-Hant",
            locale: "zh_TW"
        )

        XCTAssertTrue(app.staticTexts["連接你的 EOS"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["offline-preview-button"].exists)
        addScreenshot(name: "connection-zh-Hant")
    }

    func testDesktopBridgeConnectionFormRequiresScannedCamera() throws {
        let app = launch(appLanguage: "english", appleLanguage: "en", locale: "en_US")
        let modePicker = app.segmentedControls["connection-mode-picker"]
        XCTAssertTrue(modePicker.waitForExistence(timeout: 8))

        modePicker.buttons["Desktop Bridge"].tap()

        XCTAssertTrue(app.textFields["bridge-url-field"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.secureTextFields["bridge-token-field"].exists)
        XCTAssertTrue(app.buttons["bridge-scan-button"].exists)
        XCTAssertFalse(app.buttons["connect-button"].isEnabled)
        addScreenshot(name: "connection-desktop-bridge")
    }

    func testOfflineMediaDeletionRequiresConfirmation() throws {
        let app = launch(appLanguage: "english", appleLanguage: "en", locale: "en_US")
        let preview = app.buttons["offline-preview-button"]
        XCTAssertTrue(preview.waitForExistence(timeout: 8))
        preview.tap()

        let moreActions = app.buttons["more-actions-button"]
        XCTAssertTrue(moreActions.waitForExistence(timeout: 5))
        moreActions.tap()
        let media = app.buttons["Camera media"]
        XCTAssertTrue(media.waitForExistence(timeout: 3))
        media.tap()

        let item = app.staticTexts["R6M3_0001.JPG"]
        let delete = app.buttons["delete-media-preview-002"]
        XCTAssertTrue(item.waitForExistence(timeout: 5))
        XCTAssertTrue(waitForInteraction(delete, timeout: 5))
        delete.tap()

        let alert = app.alerts["Delete from camera?"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        XCTAssertTrue(item.exists)
        alert.buttons["Delete"].tap()
        XCTAssertTrue(item.waitForNonExistence(timeout: 3))
        addScreenshot(name: "media-delete-confirmed")
    }

    func testOfflineMediaDownloadCompletesFromTheRowAction() throws {
        let app = launch(appLanguage: "english", appleLanguage: "en", locale: "en_US")
        XCTAssertTrue(app.buttons["offline-preview-button"].waitForExistence(timeout: 8))
        app.buttons["offline-preview-button"].tap()
        XCTAssertTrue(app.buttons["more-actions-button"].waitForExistence(timeout: 5))
        app.buttons["more-actions-button"].tap()
        XCTAssertTrue(app.buttons["Camera media"].waitForExistence(timeout: 3))
        app.buttons["Camera media"].tap()

        let download = app.buttons["download-media-preview-001"]
        XCTAssertTrue(download.waitForExistence(timeout: 5))
        download.tap()

        XCTAssertTrue(app.images["download-complete-preview-001"].waitForExistence(timeout: 3))
        addScreenshot(name: "media-download-complete")
    }

    func testOfflineMonitoringAssistsKeepGeometryControlsAvailable() throws {
        let app = launch(appLanguage: "english", appleLanguage: "en", locale: "en_US")
        XCTAssertTrue(app.buttons["offline-preview-button"].waitForExistence(timeout: 8))
        app.buttons["offline-preview-button"].tap()
        XCTAssertTrue(app.buttons["more-actions-button"].waitForExistence(timeout: 5))
        app.buttons["more-actions-button"].tap()

        let monitoring = app.buttons["Monitoring assists"]
        XCTAssertTrue(monitoring.waitForExistence(timeout: 3))
        monitoring.tap()

        let unavailable = app.descendants(matching: .any)["monitor-pixel-analysis-unavailable"]
        XCTAssertTrue(unavailable.waitForExistence(timeout: 3))
        XCTAssertTrue(unavailable.label.localizedCaseInsensitiveContains("luma waveform"))
        XCTAssertTrue(unavailable.label.localizedCaseInsensitiveContains("LUT preview"))
        XCTAssertFalse(app.switches["monitor-histogram"].isEnabled)
        XCTAssertFalse(app.switches["monitor-waveform"].isEnabled)
        let lutImport = app.buttons["monitor-lut-import"]
        XCTAssertTrue(lutImport.waitForExistence(timeout: 3))
        XCTAssertFalse(lutImport.isEnabled)
        let safeArea = app.switches["monitor-safe-area"]
        XCTAssertTrue(safeArea.isEnabled)
        safeArea.tap()
        XCTAssertEqual(safeArea.value as? String, "1")
        addScreenshot(name: "monitoring-assists-offline")
    }

    func testDirectCCAPIControlsReachTheRunningCameraSimulator() async throws {
        let health = try? await simulatorRequest(path: "/health")
        let available = health?["ok"] as? Bool == true
        guard available else {
            #if OEC_REQUIRE_SIMULATOR_E2E
            XCTFail("The required fake camera is not reachable at \(simulatorURL.absoluteString)")
            return
            #else
            throw XCTSkip("Start the fake camera at \(simulatorURL.absoluteString) to run the network end-to-end test")
            #endif
        }
        _ = try await simulatorRequest(path: "/ccapi/test/reset", method: "POST")

        let app = launch(appLanguage: "english", appleLanguage: "en", locale: "en_US")
        let simulatorPreset = app.buttons["preset-simulator-button"]
        XCTAssertTrue(simulatorPreset.waitForExistence(timeout: 8))
        simulatorPreset.tap()
        app.buttons["connect-button"].tap()

        XCTAssertTrue(app.descendants(matching: .any)["camera-model-status"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.images["live-view-decoded-frame"].waitForExistence(timeout: 30))

        app.buttons["exposure-iso"].tap()
        let iso1600 = app.buttons["setting-value-1600"]
        XCTAssertTrue(iso1600.waitForExistence(timeout: 8))
        iso1600.tap()
        try await waitForSimulatorState { state in
            (state["exposure"] as? [String: Any])?["iso"] as? String == "1600"
        }
        app.buttons["Done"].tap()

        app.buttons["shutter-button"].tap()
        try await waitForSimulatorState { state in
            (state["capture_count"] as? NSNumber)?.intValue == 1
        }

        let captureMode = app.segmentedControls["capture-mode-picker"]
        XCTAssertTrue(captureMode.waitForExistence(timeout: 8))
        captureMode.buttons["Video"].tap()
        let record = app.buttons["record-button"]
        XCTAssertTrue(waitForInteraction(record, timeout: 8))
        record.tap()
        try await waitForSimulatorState { state in state["recording"] as? Bool == true }
        XCTAssertTrue(waitForLabel(record, containing: "Stop recording", timeout: 15))
        record.tap()
        try await waitForSimulatorState { state in state["recording"] as? Bool == false }

        app.buttons["more-actions-button"].tap()
        app.buttons["Camera media"].tap()
        XCTAssertTrue(app.staticTexts["SIM_0003.PNG"].waitForExistence(timeout: 20))
        app.buttons["media-back-button"].tap()
        app.buttons["more-actions-button"].tap()
        app.buttons["Disconnect"].tap()
        XCTAssertTrue(app.buttons["connect-button"].waitForExistence(timeout: 15))
    }

    private func launch(appLanguage: String, appleLanguage: String, locale: String) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = [
            "-resetState",
            "-app-language", appLanguage,
            "-AppleLanguages", "(\(appleLanguage))",
            "-AppleLocale", locale,
        ]
        app.launch()
        return app
    }

    private func waitForInteraction(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let predicate = NSPredicate { value, _ in
            guard let element = value as? XCUIElement else { return false }
            return element.exists && element.isEnabled && element.isHittable
        }
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    private func waitForLabel(_ element: XCUIElement, containing value: String, timeout: TimeInterval) -> Bool {
        let predicate = NSPredicate { candidate, _ in
            guard let element = candidate as? XCUIElement else { return false }
            return element.exists && element.label.localizedCaseInsensitiveContains(value)
        }
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    private func waitForSimulatorState(
        timeout: TimeInterval = 20,
        predicate: ([String: Any]) -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if let state = try? await simulatorRequest(path: "/ccapi/test/state"), predicate(state) {
                return
            }
            try await Task.sleep(nanoseconds: 250_000_000)
        } while Date() < deadline
        throw SimulatorTestError.timeout
    }

    private func simulatorRequest(path: String, method: String = "GET") async throws -> [String: Any] {
        let normalizedPath = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var request = URLRequest(url: simulatorURL.appendingPathComponent(normalizedPath))
        request.httpMethod = method
        if method == "POST" { request.httpBody = Data() }
        request.timeoutInterval = 5
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, 200..<300 ~= http.statusCode else {
            throw SimulatorTestError.invalidResponse
        }
        guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw SimulatorTestError.invalidResponse
        }
        return value
    }

    private func addScreenshot(name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private var simulatorURL: URL { URL(string: "http://127.0.0.1:18080")! }

    private enum SimulatorTestError: Error {
        case invalidResponse
        case timeout
    }
}
