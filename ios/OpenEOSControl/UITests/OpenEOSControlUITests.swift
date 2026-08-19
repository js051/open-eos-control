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
        let halfPress = app.buttons["half-press-button"]
        XCTAssertTrue(tapCameraAction(halfPress, in: app))
        XCTAssertTrue(halfPress.waitForNonExistence(timeout: 3))
        moreActions.tap()
        let debug = app.buttons["debug-menu-button"]
        XCTAssertTrue(tapCameraAction(debug, in: app))
        XCTAssertTrue(app.buttons["copy-diagnostic-button"].waitForExistence(timeout: 5))
        let physicalCopy = app.buttons["copy-physical-validation-button"]
        XCTAssertTrue(physicalCopy.waitForExistence(timeout: 5))
        XCTAssertFalse(physicalCopy.isEnabled)
        XCTAssertTrue(app.staticTexts["Offline UI preview cannot produce physical-camera evidence."].exists)
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

    func testOfflineDisconnectReturnsToConnectionScreen() throws {
        let app = launch(appLanguage: "english", appleLanguage: "en", locale: "en_US")
        XCTAssertTrue(app.buttons["offline-preview-button"].waitForExistence(timeout: 8))
        app.buttons["offline-preview-button"].tap()

        openMoreActions(in: app)
        XCTAssertTrue(tapCameraAction(app.buttons["disconnect-menu-button"], in: app))

        guard waitForConnectionScreen(in: app, timeout: 8) else { return }
    }

    func testOfflineMediaDeletionRequiresConfirmation() throws {
        let app = launch(appLanguage: "english", appleLanguage: "en", locale: "en_US")
        let preview = app.buttons["offline-preview-button"]
        XCTAssertTrue(preview.waitForExistence(timeout: 8))
        preview.tap()

        let moreActions = app.buttons["more-actions-button"]
        XCTAssertTrue(moreActions.waitForExistence(timeout: 5))
        moreActions.tap()
        let media = app.buttons["camera-media-menu-button"]
        XCTAssertTrue(tapCameraAction(media, in: app))

        let item = app.staticTexts["R6M3_0001.JPG"]
        let actions = app.buttons["media-actions-preview-002"]
        let delete = app.buttons["delete-media-preview-002"]
        XCTAssertTrue(item.waitForExistence(timeout: 5))
        XCTAssertTrue(waitForInteraction(actions, timeout: 5))
        actions.tap()
        XCTAssertTrue(scrollToInteraction(delete, in: app, timeout: 8))
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
        XCTAssertTrue(tapCameraAction(app.buttons["camera-media-menu-button"], in: app))

        XCTAssertFalse(app.buttons["upload-media-button"].waitForExistence(timeout: 2))
        let download = app.buttons["download-media-preview-001"]
        XCTAssertTrue(download.waitForExistence(timeout: 5))
        download.tap()

        XCTAssertTrue(app.images["download-complete-preview-001"].waitForExistence(timeout: 3))
        addScreenshot(name: "media-download-complete")
    }

    func testOfflineMediaGridFiltersPhotosAndVideos() throws {
        let app = launch(appLanguage: "english", appleLanguage: "en", locale: "en_US")
        XCTAssertTrue(app.buttons["offline-preview-button"].waitForExistence(timeout: 8))
        app.buttons["offline-preview-button"].tap()
        XCTAssertTrue(app.buttons["more-actions-button"].waitForExistence(timeout: 5))
        app.buttons["more-actions-button"].tap()
        XCTAssertTrue(tapCameraAction(app.buttons["camera-media-menu-button"], in: app))

        let filter = app.segmentedControls["media-filter"]
        XCTAssertTrue(filter.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["R6M3_0001.JPG"].exists)
        filter.buttons["Videos"].tap()
        XCTAssertTrue(app.staticTexts["R6M3_0002.MP4"].waitForExistence(timeout: 3))
        XCTAssertFalse(app.staticTexts["R6M3_0001.JPG"].exists)
        filter.buttons["All"].tap()
        XCTAssertTrue(app.staticTexts["R6M3_0001.JPG"].waitForExistence(timeout: 3))
        addScreenshot(name: "media-grid-filtered")
    }

    func testOfflineMonitoringAssistsKeepGeometryControlsAvailable() throws {
        let app = launch(appLanguage: "english", appleLanguage: "en", locale: "en_US")
        XCTAssertTrue(app.buttons["offline-preview-button"].waitForExistence(timeout: 8))
        app.buttons["offline-preview-button"].tap()
        XCTAssertTrue(app.buttons["more-actions-button"].waitForExistence(timeout: 5))
        app.buttons["more-actions-button"].tap()

        let monitoring = app.buttons["monitoring-menu-button"]
        XCTAssertTrue(tapCameraAction(monitoring, in: app))

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

    @MainActor
    func testDirectCCAPIControlsReachTheRunningCameraSimulator() async throws {
        let available = await waitForSimulatorHealth()
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
        let liveView = app.images["live-view-decoded-frame"]
        XCTAssertTrue(liveView.waitForExistence(timeout: 30))
        let liveViewInteraction = app.descendants(matching: .any)["live-view-interaction-surface"]
        XCTAssertTrue(liveViewInteraction.waitForExistence(timeout: 8))

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

        let autofocus = app.buttons["autofocus-button"]
        XCTAssertTrue(waitForInteraction(autofocus, timeout: 8))
        autofocus.tap()
        try await waitForSimulatorState { state in
            (state["half_press_count"] as? NSNumber)?.intValue == 1 &&
                (state["shutter_release_count"] as? NSNumber)?.intValue == 1
        }

        openMoreActions(in: app)
        let halfPress = app.buttons["half-press-button"]
        XCTAssertTrue(halfPress.waitForExistence(timeout: 8))
        halfPress.tap()
        try await waitForSimulatorState { state in
            (state["half_press_count"] as? NSNumber)?.intValue == 2 &&
                (state["shutter_release_count"] as? NSNumber)?.intValue == 2 &&
                state["half_pressed"] as? Bool == false
        }

        XCTAssertTrue(waitForInteraction(liveViewInteraction, timeout: 8))
        liveViewInteraction.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.35)).tap()
        try await waitForSimulatorState { state in
            (state["focus"] as? [String: Any])?["count"] as? Int == 1
        }

        openMoreActions(in: app)
        let moreSettings = app.buttons["more-settings-menu-button"]
        XCTAssertTrue(waitForInteraction(moreSettings, timeout: 8))
        moreSettings.tap()
        let tapAction = app.segmentedControls["live-view-tap-action-picker"]
        XCTAssertTrue(tapAction.waitForExistence(timeout: 5))
        let clickWhiteBalance = tapAction.buttons["Click white balance"]
        clickWhiteBalance.tap()
        XCTAssertTrue(waitForValue(tapAction, equalTo: "whiteBalance", timeout: 3))
        app.buttons["Done"].tap()
        XCTAssertTrue(waitForInteraction(liveViewInteraction, timeout: 8))
        liveViewInteraction.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.65)).tap()
        try await waitForSimulatorState { state in
            (state["click_white_balance"] as? [String: Any])?["count"] as? Int == 1 &&
                (state["exposure"] as? [String: Any])?["white_balance"] as? String == "click"
        }

        openMoreActions(in: app)
        let focusDrive = app.buttons["focus-drive-menu-button"]
        guard tapCameraAction(focusDrive, in: app) else { return }
        let driveNearLarge = app.buttons["focus-drive-near-large"]
        guard waitForInteraction(driveNearLarge, timeout: 5) else {
            XCTFail("The focus-drive sheet did not become interactive")
            return
        }
        driveNearLarge.tap()
        try await waitForSimulatorState { state in
            guard let focus = state["focus_drive"] as? [String: Any] else { return false }
            return (focus["count"] as? NSNumber)?.intValue == 1 &&
                focus["direction"] as? String == "near" &&
                focus["step"] as? String == "large"
        }
        app.buttons["Done"].tap()

        _ = try await simulatorRequest(
            path: "/ccapi/test/mode",
            method: "POST",
            queryItems: [URLQueryItem(name: "mode", value: "Bulb")]
        )
        try await waitForSimulatorState { state in state["mode"] as? String == "Bulb" }
        XCTAssertTrue(waitForLabel(app.buttons["shutter-button"], containing: "Start Bulb exposure", timeout: 15))
        app.buttons["shutter-button"].tap()
        try await waitForSimulatorState { state in
            state["bulb_exposure_active"] as? Bool == true &&
                (state["bulb_start_count"] as? NSNumber)?.intValue == 1
        }
        XCTAssertTrue(waitForLabel(app.buttons["shutter-button"], containing: "Stop Bulb exposure", timeout: 8))
        app.buttons["shutter-button"].tap()
        try await waitForSimulatorState { state in
            state["bulb_exposure_active"] as? Bool == false &&
                (state["bulb_stop_count"] as? NSNumber)?.intValue == 1
        }

        let captureMode = app.segmentedControls["capture-mode-picker"]
        XCTAssertTrue(captureMode.waitForExistence(timeout: 8))
        captureMode.buttons["Video"].tap()
        try await waitForSimulatorState { state in
            state["movie_mode"] as? String == "on" &&
                (state["movie_mode_update_count"] as? NSNumber)?.intValue == 1
        }
        let record = app.buttons["record-button"]
        XCTAssertTrue(waitForInteraction(record, timeout: 8))
        record.tap()
        try await waitForSimulatorState { state in state["recording"] as? Bool == true }
        XCTAssertTrue(waitForLabel(record, containing: "Stop recording", timeout: 15))
        record.tap()
        try await waitForSimulatorState { state in state["recording"] as? Bool == false }
        captureMode.buttons["Photo"].tap()
        try await waitForSimulatorState { state in
            state["movie_mode"] as? String == "off" &&
                (state["movie_mode_update_count"] as? NSNumber)?.intValue == 2
        }

        openMoreActions(in: app)
        guard tapCameraAction(app.buttons["camera-media-menu-button"], in: app) else { return }
        XCTAssertTrue(app.staticTexts["SIM_0003.PNG"].waitForExistence(timeout: 20))

        let previewMedia = app.buttons["preview-media-SIM_0003.PNG"]
        XCTAssertTrue(waitForInteraction(previewMedia, timeout: 8))
        previewMedia.tap()
        XCTAssertTrue(app.buttons["close-media-preview"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["media-preview-position"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["media-preview-download-SIM_0003.PNG"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["media-preview-actions-SIM_0003.PNG"].waitForExistence(timeout: 3))
        app.buttons["close-media-preview"].tap()

        let mediaActions = app.buttons["media-actions-SIM_0003.PNG"]
        XCTAssertTrue(waitForInteraction(mediaActions, timeout: 8))
        mediaActions.tap()
        let deleteMedia = app.buttons["delete-media-SIM_0003.PNG"]
        XCTAssertTrue(scrollToInteraction(deleteMedia, in: app, timeout: 8))
        deleteMedia.tap()
        let deleteAlert = app.alerts["Delete from camera?"]
        XCTAssertTrue(deleteAlert.waitForExistence(timeout: 5))
        deleteAlert.buttons["Delete"].tap()
        try await waitForSimulatorState { state in
            !(state["media_ids"] as? [String] ?? []).contains("SIM_0003.PNG")
        }
        XCTAssertTrue(app.staticTexts["SIM_0003.PNG"].waitForNonExistence(timeout: 8))

        app.buttons["media-back-button"].tap()
        openMoreActions(in: app)
        guard tapCameraAction(app.buttons["disconnect-menu-button"], in: app) else { return }
        guard waitForConnectionScreen(in: app, timeout: 15) else { return }
    }

    @MainActor
    func testCanonicalCCAPIEventsRefreshTheProductionUI() async throws {
        let available = await waitForSimulatorHealth()
        guard available else {
            #if OEC_REQUIRE_SIMULATOR_E2E
            XCTFail("The required fake camera is not reachable at \(simulatorURL.absoluteString)")
            return
            #else
            throw XCTSkip("Start the fake camera at \(simulatorURL.absoluteString) to run the network end-to-end test")
            #endif
        }
        _ = try await simulatorRequest(path: "/ccapi/test/reset", method: "POST")
        _ = try await simulatorRequest(
            path: "/ccapi/ver100/shooting/settings/shootingmode",
            method: "PUT",
            jsonBody: ["value": "Manual"]
        )

        let app = launch(
            appLanguage: "english",
            appleLanguage: "en",
            locale: "en_US",
            environment: ["OEC_HTTP_PRESET_URL": simulatorURL.absoluteString]
        )
        let httpPreset = app.buttons["preset-http-button"]
        XCTAssertTrue(httpPreset.waitForExistence(timeout: 8))
        httpPreset.tap()

        let urlField = app.textFields["camera-url-field"]
        XCTAssertTrue(urlField.waitForExistence(timeout: 3))
        XCTAssertEqual(urlField.value as? String, simulatorURL.absoluteString)
        let connect = app.buttons["connect-button"]
        XCTAssertTrue(waitForInteraction(connect, timeout: 8))
        connect.tap()

        XCTAssertTrue(app.descendants(matching: .any)["camera-model-status"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.images["live-view-decoded-frame"].waitForExistence(timeout: 30))
        try await waitForSimulatorState { state in
            guard let canonical = state["canonical"] as? [String: Any] else { return false }
            return ((canonical["event_poll_count"] as? NSNumber)?.intValue ?? 0) >= 1 &&
                (canonical["event_active_requests"] as? NSNumber)?.intValue == 1 &&
                ((canonical["live_view_start_count"] as? NSNumber)?.intValue ?? 0) >= 1
        }

        _ = try await simulatorRequest(
            path: "/ccapi/exposure",
            method: "PATCH",
            jsonBody: ["iso": "3200"]
        )
        XCTAssertTrue(waitForLabel(app.buttons["exposure-iso"], containing: "3200", timeout: 20))

        openMoreActions(in: app)
        guard tapCameraAction(app.buttons["camera-media-menu-button"], in: app) else { return }
        XCTAssertTrue(app.staticTexts["SIM_0002.PNG"].waitForExistence(timeout: 20))

        _ = try await simulatorRequest(
            path: "/ccapi/ver100/shooting/control/shutterbutton",
            method: "POST",
            jsonBody: ["af": true]
        )
        XCTAssertTrue(app.staticTexts["SIM_0003.JPG"].waitForExistence(timeout: 20))
        try await waitForSimulatorState { state in
            guard let canonical = state["canonical"] as? [String: Any] else { return false }
            return ((canonical["event_cursor"] as? NSNumber)?.intValue ?? 0) >= 3
        }

        app.buttons["media-back-button"].tap()
        openMoreActions(in: app)
        guard tapCameraAction(app.buttons["disconnect-menu-button"], in: app) else { return }
        guard waitForConnectionScreen(in: app, timeout: 15) else { return }
        try await waitForSimulatorState { state in
            guard let canonical = state["canonical"] as? [String: Any] else { return false }
            return ((canonical["event_delete_count"] as? NSNumber)?.intValue ?? 0) >= 1 &&
                (canonical["event_active_requests"] as? NSNumber)?.intValue == 0 &&
                ((canonical["live_view_stop_count"] as? NSNumber)?.intValue ?? 0) >= 1
        }
    }

    @MainActor
    func testCanonicalCCAPIMediaPagesAppearProgressivelyAndCanBeCancelled() async throws {
        let available = await waitForSimulatorHealth()
        guard available else {
            #if OEC_REQUIRE_SIMULATOR_E2E
            XCTFail("The required fake camera is not reachable at \(simulatorURL.absoluteString)")
            return
            #else
            throw XCTSkip("Start the fake camera at \(simulatorURL.absoluteString) to run the network end-to-end test")
            #endif
        }
        _ = try await simulatorRequest(path: "/ccapi/test/reset", method: "POST")
        _ = try await simulatorRequest(
            path: "/ccapi/test/media-pagination",
            method: "POST",
            jsonBody: ["page_size": 1, "page_delay_ms": 15_000]
        )

        let app = launch(
            appLanguage: "english",
            appleLanguage: "en",
            locale: "en_US",
            environment: ["OEC_HTTP_PRESET_URL": simulatorURL.absoluteString]
        )
        let httpPreset = app.buttons["preset-http-button"]
        XCTAssertTrue(httpPreset.waitForExistence(timeout: 8))
        httpPreset.tap()
        let connect = app.buttons["connect-button"]
        XCTAssertTrue(waitForInteraction(connect, timeout: 8))
        connect.tap()
        XCTAssertTrue(app.descendants(matching: .any)["camera-model-status"].waitForExistence(timeout: 30))

        openMoreActions(in: app)
        guard tapCameraAction(app.buttons["camera-media-menu-button"], in: app) else { return }
        XCTAssertTrue(app.staticTexts["SIM_0002.PNG"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.descendants(matching: .any)["media-library-loading-progressive"].waitForExistence(timeout: 3))
        let loadingSummary = app.staticTexts["media-library-summary-loading"]
        XCTAssertTrue(waitForLabel(loadingSummary, containing: "Loading", timeout: 3))
        let cancel = app.buttons["cancel-media-library-load"]
        XCTAssertTrue(waitForInteraction(cancel, timeout: 3))
        cancel.tap()

        let cancelledSummary = app.staticTexts["media-library-summary-cancelled"]
        XCTAssertTrue(waitForLabel(cancelledSummary, containing: "incomplete", timeout: 8))
        XCTAssertTrue(app.staticTexts["SIM_0002.PNG"].exists)
        XCTAssertTrue(app.staticTexts["SIM_0001.PNG"].waitForNonExistence(timeout: 6))
        XCTAssertTrue(app.buttons["refresh-media"].waitForExistence(timeout: 3))

        app.buttons["media-back-button"].tap()
        openMoreActions(in: app)
        guard tapCameraAction(app.buttons["disconnect-menu-button"], in: app) else { return }
        guard waitForConnectionScreen(in: app, timeout: 15) else { return }
    }

    private func launch(
        appLanguage: String,
        appleLanguage: String,
        locale: String,
        environment: [String: String] = [:]
    ) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = [
            "-resetState",
            "-disableAnimations",
            "-app-language", appLanguage,
            "-AppleLanguages", "(\(appleLanguage))",
            "-AppleLocale", locale,
        ]
        app.launchEnvironment.merge(environment) { _, newValue in newValue }
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

    private func waitForValue(_ element: XCUIElement, equalTo expectedValue: String, timeout: TimeInterval) -> Bool {
        let predicate = NSPredicate { candidate, _ in
            (candidate as? XCUIElement)?.value as? String == expectedValue
        }
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    private func scrollToInteraction(
        _ element: XCUIElement,
        in app: XCUIApplication,
        timeout: TimeInterval
    ) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        guard element.waitForExistence(timeout: min(timeout, 2)) else { return false }
        while Date() < deadline {
            if element.isEnabled, element.isHittable { return true }
            let scrollSurface = app.scrollViews.allElementsBoundByIndex.last {
                $0.exists && $0.isHittable
            }
            if let scrollSurface {
                scrollSurface.swipeUp()
            } else {
                app.swipeUp()
            }
        }
        return element.isEnabled && element.isHittable
    }

    private func openMoreActions(in app: XCUIApplication) {
        let moreActions = app.buttons["more-actions-button"]
        XCTAssertTrue(waitForInteraction(moreActions, timeout: 8))
        moreActions.tap()
    }

    private func waitForConnectionScreen(in app: XCUIApplication, timeout: TimeInterval) -> Bool {
        guard app.buttons["connect-button"].waitForExistence(timeout: timeout) else {
            XCTFail("The connection screen did not become visible.\n\(app.debugDescription)")
            return false
        }
        return true
    }

    @discardableResult
    private func tapCameraAction(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        guard scrollToInteraction(element, in: app, timeout: 8) else {
            XCTFail("The requested camera action did not become interactive")
            return false
        }
        element.tap()
        return true
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

    private func waitForSimulatorHealth(timeout: TimeInterval = 10) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if let health = try? await simulatorRequest(path: "/health", timeoutInterval: 1),
               health["ok"] as? Bool == true {
                return true
            }
            try? await Task.sleep(nanoseconds: 250_000_000)
        } while Date() < deadline
        return false
    }

    private func simulatorRequest(
        path: String,
        method: String = "GET",
        queryItems: [URLQueryItem] = [],
        jsonBody: [String: Any]? = nil,
        timeoutInterval: TimeInterval = 5
    ) async throws -> [String: Any] {
        let normalizedPath = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let pathURL = simulatorURL.appendingPathComponent(normalizedPath)
        var components = try XCTUnwrap(URLComponents(url: pathURL, resolvingAgainstBaseURL: false))
        if !queryItems.isEmpty { components.queryItems = queryItems }
        var request = URLRequest(url: try XCTUnwrap(components.url))
        request.httpMethod = method
        if let jsonBody {
            request.httpBody = try JSONSerialization.data(withJSONObject: jsonBody)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        } else if method == "POST" {
            request.httpBody = Data()
        }
        request.timeoutInterval = timeoutInterval
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, 200..<300 ~= http.statusCode else {
            throw SimulatorTestError.invalidResponse
        }
        if data.isEmpty { return [:] }
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
