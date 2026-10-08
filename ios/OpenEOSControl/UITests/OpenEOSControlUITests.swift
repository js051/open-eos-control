import Foundation
import XCTest
import UIKit

final class OpenEOSControlUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testConnectionHitGeometryRejectsOffscreenOrClippedButtons() {
        let window = CGRect(x: 0, y: 0, width: 874, height: 402)
        let visible = CGRect(x: 157, y: 200, width: 560, height: 77.33333333333337)
        let cases: [(String, CGRect, CGRect, CGRect, Bool)] = [
            ("fully visible", visible, window, window, true),
            ("observed offscreen but AX-hittable Connect",
             CGRect(x: 157, y: 596.6666666666666, width: 560, height: 77.33333333333337), window, window, false),
            ("center visible but bottom clipped", CGRect(x: 157, y: 376, width: 560, height: 44), window, window, false),
            ("top clipped", CGRect(x: 157, y: -10, width: 560, height: 77), window, window, false),
            ("left clipped", CGRect(x: -10, y: 200, width: 560, height: 77), window, window, false),
            ("inside window but below scroll viewport", visible,
             CGRect(x: 0, y: 0, width: 874, height: 250), window, false),
            ("inside scroll content but below window", CGRect(x: 157, y: 500, width: 560, height: 77),
             CGRect(x: 0, y: 0, width: 874, height: 900), window, false),
            ("44pt target", CGRect(x: 157, y: 200, width: 44, height: 44), window, window, true),
            ("too short", CGRect(x: 157, y: 200, width: 560, height: 43), window, window, false),
            ("too narrow", CGRect(x: 157, y: 200, width: 43, height: 77), window, window, false),
            ("missing button", .null, window, window, false),
            ("empty viewport", visible, .zero, window, false),
            ("infinite viewport", visible, .infinite, window, false),
            ("nonfinite button", CGRect(x: CGFloat.nan, y: 200, width: 560, height: 77), window, window, false),
        ]
        for (name, button, scroll, window, expected) in cases {
            XCTAssertEqual(Self.connectionButtonHasVisibleHitFrame(button, scroll: scroll, window: window), expected, name)
        }
    }

    func testShutterRecoveryStopRemainsReachableAcrossLanguagesFontsAndRotation() throws {
        let languages = [
            ("english", "en", "en_US", "Stop · Release shutter", "Shutter release is unconfirmed"),
            ("traditionalChinese", "zh-Hant", "zh_TW", "停止・釋放快門", "尚未確認快門已釋放"),
        ]
        for (language, appleLanguage, locale, stopLabel, warningLabel) in languages {
            for font in ["UICTContentSizeCategoryXS", "UICTContentSizeCategoryAccessibilityXXXL"] {
                for scenario in ["active", "unknown"] {
                    let app = launch(
                        appLanguage: language, appleLanguage: appleLanguage, locale: locale,
                        environment: ["OEC_SHUTTER_RECOVERY_FIXTURE": scenario],
                        contentSizeCategory: font
                    )
                    let connect = app.buttons["connect-button"]
                    XCTAssertTrue(scrollConnectionButtonIntoView(in: app, timeout: 8))
                    assertConnectionButtonHitFrame(in: app)
                    connect.tap()
                    let stop = app.buttons["release-shutter-button"]
                    XCTAssertTrue(waitForInteraction(stop, timeout: 8))
                    XCTAssertTrue(stop.label.contains(stopLabel))
                    if scenario == "unknown" {
                        XCTAssertEqual(app.staticTexts["shutter-release-warning"].label, warningLabel)
                    }
                    for orientation in [UIDeviceOrientation.portrait, .landscapeLeft, .landscapeRight] {
                        XCUIDevice.shared.orientation = orientation
                        XCTAssertTrue(waitForInteraction(stop, timeout: 5))
                        XCTAssertTrue(stop.label.contains(stopLabel))
                        XCTAssertGreaterThanOrEqual(stop.frame.minX, app.windows.firstMatch.frame.minX)
                        XCTAssertLessThanOrEqual(stop.frame.maxX, app.windows.firstMatch.frame.maxX)
                        XCTAssertGreaterThanOrEqual(stop.frame.minY, app.windows.firstMatch.frame.minY)
                        XCTAssertLessThanOrEqual(stop.frame.maxY, app.windows.firstMatch.frame.maxY)
                        addScreenshot(name: "shutter-recovery-\(language)-\(font)-\(scenario)-\(orientation.rawValue)")
                    }
                    stop.tap()
                    XCTAssertTrue(stop.waitForNonExistence(timeout: 5))
                    XCTAssertFalse(app.staticTexts["shutter-release-warning"].exists)
                    app.terminate()
                }
            }
        }
    }

    func testPendingBulbStartLeavesStopEnabledAndLostReleaseCanBeRetried() throws {
        for scenario in ["pending-start", "pending-start-lost-stop"] {
            let app = launch(
                appLanguage: "english", appleLanguage: "en", locale: "en_US",
                environment: ["OEC_SHUTTER_RECOVERY_FIXTURE": scenario]
            )
            XCTAssertTrue(scrollConnectionButtonIntoView(in: app, timeout: 8))
            app.buttons["connect-button"].tap()
            let shutter = app.buttons["shutter-button"]
            XCTAssertTrue(waitForInteraction(shutter, timeout: 8))
            XCTAssertTrue(shutter.label.contains("Start Bulb exposure"))
            shutter.tap()
            let stop = app.buttons["release-shutter-button"]
            XCTAssertTrue(waitForInteraction(stop, timeout: 5), "A dispatched Start cannot disable its Stop")
            stop.tap()
            if scenario == "pending-start-lost-stop" {
                XCTAssertTrue(waitForInteraction(stop, timeout: 5))
                XCTAssertTrue(app.staticTexts["shutter-release-warning"].exists)
                stop.tap()
            }
            XCTAssertTrue(stop.waitForNonExistence(timeout: 5))
            XCTAssertTrue(waitForInteraction(shutter, timeout: 5))
            XCTAssertTrue(shutter.label.contains("Start Bulb exposure"))
            XCTAssertFalse(app.alerts.firstMatch.exists, "Cancelled Start must not replace successful Stop with an error")
            app.terminate()
        }
    }

    func testPendingBulbStartDisconnectCanReconnectWithoutFinishingStartReply() throws {
        let app = launch(
            appLanguage: "english", appleLanguage: "en", locale: "en_US",
            environment: ["OEC_SHUTTER_RECOVERY_FIXTURE": "pending-start"]
        )
        XCTAssertTrue(scrollConnectionButtonIntoView(in: app, timeout: 8))
        app.buttons["connect-button"].tap()
        XCTAssertTrue(waitForInteraction(app.buttons["shutter-button"], timeout: 8))
        app.buttons["shutter-button"].tap()
        XCTAssertTrue(waitForInteraction(app.buttons["release-shutter-button"], timeout: 5))
        openMoreActions(in: app)
        let disconnect = app.buttons["disconnect-menu-button"]
        XCTAssertTrue(scrollToInteraction(disconnect, in: app, timeout: 8))
        disconnect.tap()
        XCTAssertTrue(waitForConnectionScreen(in: app, timeout: 8))
        XCTAssertTrue(scrollConnectionButtonIntoView(in: app, timeout: 8))
        app.buttons["connect-button"].tap()
        XCTAssertTrue(waitForLabel(app.staticTexts["camera-model-status"], containing: "shutter-fixture-2", timeout: 8))
        XCTAssertFalse(app.buttons["release-shutter-button"].exists)
        XCTAssertFalse(app.staticTexts["previous-shutter-release-warning"].exists)
        XCTAssertTrue(waitForInteraction(app.buttons["shutter-button"], timeout: 5))
    }

    func testPreviousConnectionWarningStaysSeparateFromNewConnectionStop() throws {
        let app = launch(
            appLanguage: "english", appleLanguage: "en", locale: "en_US",
            environment: ["OEC_SHUTTER_RECOVERY_FIXTURE": "previous-and-current"]
        )
        let connect = app.buttons["connect-button"]
        XCTAssertTrue(scrollConnectionButtonIntoView(in: app, timeout: 8))
        assertConnectionButtonHitFrame(in: app)
        connect.tap()
        XCTAssertTrue(waitForInteraction(app.buttons["release-shutter-button"], timeout: 8))
        openMoreActions(in: app)
        let disconnect = app.buttons["disconnect-menu-button"]
        XCTAssertTrue(scrollToInteraction(disconnect, in: app, timeout: 8))
        // Keep the same single element tap. The accessibility-hittable footer
        // previously overlapped Stop; capture both targets and reject overlap
        // rather than hiding a wrong action with a second tap.
        recordShutterRecoveryDisconnectGeometry(in: app, phase: "before-tap")
        guard assertSeparateRecoverySheetControls(in: app) != nil else { return }
        disconnect.tap()
        recordShutterRecoveryDisconnectGeometry(in: app, phase: "after-tap")
        guard waitForConnectionScreen(in: app, timeout: 8) else { return }
        let previous = app.staticTexts["previous-shutter-release-warning"]
        XCTAssertTrue(previous.waitForExistence(timeout: 8))
        XCTAssertTrue(scrollConnectionButtonIntoView(in: app, timeout: 8))
        XCTAssertTrue(waitForInteraction(connect, timeout: 5))
        assertConnectionButtonHitFrame(in: app)
        connect.tap()
        let stop = app.buttons["release-shutter-button"]
        XCTAssertTrue(waitForInteraction(stop, timeout: 8))
        let confirm = app.buttons["confirm-previous-shutter-release-button"]
        XCTAssertTrue(scrollToInteraction(confirm, in: app, timeout: 8))
        XCTAssertTrue(previous.exists)
        XCTAssertTrue(stop.isHittable)
        addScreenshot(name: "previous-warning-and-current-stop")
        confirm.tap()
        app.buttons["Confirm shutter is released"].tap()
        XCTAssertTrue(previous.waitForNonExistence(timeout: 5))
        XCTAssertTrue(waitForInteraction(stop, timeout: 5), "Acknowledging the old camera must leave the new Stop available")
        stop.tap()
        XCTAssertTrue(stop.waitForNonExistence(timeout: 5))
    }

    func testRecoverySheetStopAndDisconnectHaveIndependentHitTargets() throws {
        // The root recovery matrix already covers both languages/font extremes
        // in every orientation. Exercise both sheet actions in these additional
        // portrait/landscape and active/unknown combinations.
        let cases: [(String, String, String, String, UIDeviceOrientation, String)] = [
            ("english", "en", "en_US", "UICTContentSizeCategoryXS", .portrait, "active"),
            ("english", "en", "en_US", "UICTContentSizeCategoryXS", .landscapeLeft, "unknown"),
            ("traditionalChinese", "zh-Hant", "zh_TW", "UICTContentSizeCategoryAccessibilityXXXL", .portrait, "unknown"),
            ("traditionalChinese", "zh-Hant", "zh_TW", "UICTContentSizeCategoryAccessibilityXXXL", .landscapeRight, "active"),
        ]
        for (language, appleLanguage, locale, font, orientation, scenario) in cases {
            let app = launch(
                appLanguage: language, appleLanguage: appleLanguage, locale: locale,
                environment: ["OEC_SHUTTER_RECOVERY_FIXTURE": scenario],
                contentSizeCategory: font
            )
            let connect = app.buttons["connect-button"]
            XCTAssertTrue(scrollConnectionButtonIntoView(in: app, timeout: 8))
            assertConnectionButtonHitFrame(in: app)
            connect.tap()
            XCTAssertTrue(waitForInteraction(app.buttons["release-shutter-button"], timeout: 8))
            XCUIDevice.shared.orientation = orientation
            openMoreActions(in: app)
            let name = "\(language)-\(font)-\(orientation.rawValue)-\(scenario)"
            recordShutterRecoveryDisconnectGeometry(in: app, phase: "\(name)-before-disconnect")
            guard assertSeparateRecoverySheetControls(in: app) != nil else { return }
            // A single Disconnect must retire the connection while release is
            // still required; tapping Stop by mistake must fail this assertion.
            app.buttons["disconnect-menu-button"].tap()
            guard waitForConnectionScreen(in: app, timeout: 8) else { return }
            XCTAssertFalse(app.buttons["disconnect-menu-button"].exists)
            XCTAssertFalse(app.staticTexts["camera-model-status"].exists)
            XCTAssertFalse(app.buttons["release-shutter-button"].exists)

            XCTAssertTrue(scrollConnectionButtonIntoView(in: app, timeout: 8))
            recordShutterRecoveryConnectGeometry(in: app, phase: "\(name)-before-reconnect")
            assertConnectionButtonHitFrame(in: app)
            connect.tap()
            let reconnectDeadline = ProcessInfo.processInfo.systemUptime + 8
            recordShutterRecoveryConnectGeometry(in: app, phase: "\(name)-after-reconnect")
            let stop = app.buttons["release-shutter-button"]
            let remaining = max(0, reconnectDeadline - ProcessInfo.processInfo.systemUptime)
            let reconnected: Bool
            if remaining > 0 {
                reconnected = waitForInteraction(stop, timeout: remaining)
            } else {
                // Diagnostics consumed the budget: take one current sample,
                // never start another wait or grant a fresh eight seconds.
                reconnected = stop.exists && stop.isEnabled && stop.isHittable
            }
            // Record the result before a failure aborts the test. Keep the same
            // single tap and total eight-second budget after it returns. The
            // test helper now rejects the proven offscreen AX false-positive;
            // these observations alone do not prove a production repair.
            if !reconnected {
                recordShutterRecoveryConnectGeometry(in: app, phase: "\(name)-failed-reconnect")
            }
            XCTAssertTrue(reconnected)
            openMoreActions(in: app)
            guard let sheetStop = assertSeparateRecoverySheetControls(in: app) else { return }
            // Conversely, Stop must release without invoking Disconnect or
            // dismissing the actions sheet. Do not retry either action.
            sheetStop.tap()
            XCTAssertTrue(app.buttons["release-shutter-button"].waitForNonExistence(timeout: 5))
            XCTAssertFalse(app.staticTexts["shutter-release-warning"].exists)
            XCTAssertTrue(waitForInteraction(app.buttons["disconnect-menu-button"], timeout: 5))
            XCTAssertTrue(app.staticTexts["camera-model-status"].exists)
            XCTAssertFalse(connect.exists)
            addScreenshot(name: "shutter-recovery-sheet-stop-\(name)")
            app.terminate()
        }
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
        // Swift errors unwind this async test before XCTest begins the next case.
        continueAfterFailure = true
        let available = try await waitForSimulatorHealth()
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
        defer { app.terminate() }
        let simulatorPreset = app.buttons["preset-simulator-button"]
        _ = try XCTUnwrap((simulatorPreset.waitForExistence(timeout: 8)) ? true : nil)
        try tapForAsyncTest(simulatorPreset)
        try tapForAsyncTest(app.buttons["connect-button"])

        _ = try XCTUnwrap((app.descendants(matching: .any)["camera-model-status"].waitForExistence(timeout: 30)) ? true : nil)
        let liveView = app.images["live-view-decoded-frame"]
        _ = try XCTUnwrap((liveView.waitForExistence(timeout: 30)) ? true : nil)
        let liveViewInteraction = app.descendants(matching: .any)["live-view-interaction-surface"]
        _ = try XCTUnwrap((liveViewInteraction.waitForExistence(timeout: 8)) ? true : nil)

        try tapForAsyncTest(app.buttons["exposure-iso"])
        let iso1600 = app.buttons["setting-value-1600"]
        _ = try XCTUnwrap((iso1600.waitForExistence(timeout: 8)) ? true : nil)
        try tapForAsyncTest(iso1600)
        try await waitForSimulatorState { state in
            (state["exposure"] as? [String: Any])?["iso"] as? String == "1600"
        }
        try tapForAsyncTest(app.buttons["Done"])

        try tapForAsyncTest(app.buttons["shutter-button"])
        try await waitForSimulatorState { state in
            (state["capture_count"] as? NSNumber)?.intValue == 1
        }

        let autofocus = app.buttons["autofocus-button"]
        _ = try XCTUnwrap((waitForInteraction(autofocus, timeout: 8)) ? true : nil)
        try tapForAsyncTest(autofocus)
        try await waitForSimulatorState { state in
            (state["half_press_count"] as? NSNumber)?.intValue == 1 &&
                (state["shutter_release_count"] as? NSNumber)?.intValue == 1
        }

        try openMoreActionsForAsyncTest(in: app)
        let halfPress = app.buttons["half-press-button"]
        _ = try XCTUnwrap((halfPress.waitForExistence(timeout: 8)) ? true : nil)
        try tapForAsyncTest(halfPress)
        try await waitForSimulatorState { state in
            (state["half_press_count"] as? NSNumber)?.intValue == 2 &&
                (state["shutter_release_count"] as? NSNumber)?.intValue == 2 &&
                state["half_pressed"] as? Bool == false
        }

        _ = try XCTUnwrap((waitForInteraction(liveViewInteraction, timeout: 8)) ? true : nil)
        try tapForAsyncTest(liveViewInteraction.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.35)))
        try await waitForSimulatorState { state in
            (state["focus"] as? [String: Any])?["count"] as? Int == 1
        }

        try openMoreActionsForAsyncTest(in: app)
        let moreSettings = app.buttons["more-settings-menu-button"]
        _ = try XCTUnwrap((waitForInteraction(moreSettings, timeout: 8)) ? true : nil)
        try tapForAsyncTest(moreSettings)
        let tapAction = app.otherElements["live-view-tap-action-control"]
        _ = try XCTUnwrap((tapAction.waitForExistence(timeout: 5)) ? true : nil)
        guard try selectLiveViewTapAction(.whiteBalance, in: app, phase: "direct-ccapi") else { return }
        try tapForAsyncTest(app.buttons["Done"])
        _ = try XCTUnwrap((waitForInteraction(liveViewInteraction, timeout: 8)) ? true : nil)
        try tapForAsyncTest(liveViewInteraction.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.65)))
        try await waitForSimulatorState { state in
            (state["click_white_balance"] as? [String: Any])?["count"] as? Int == 1 &&
                (state["exposure"] as? [String: Any])?["white_balance"] as? String == "click"
        }

        try openMoreActionsForAsyncTest(in: app)
        let focusDrive = app.buttons["focus-drive-menu-button"]
        guard try tapCameraActionForAsyncTest(focusDrive, in: app) else { return }
        let driveNearLarge = app.buttons["focus-drive-near-large"]
        guard waitForInteraction(driveNearLarge, timeout: 5) else {
            XCTFail("The focus-drive sheet did not become interactive")
            return
        }
        try tapForAsyncTest(driveNearLarge)
        try await waitForSimulatorState { state in
            guard let focus = state["focus_drive"] as? [String: Any] else { return false }
            return (focus["count"] as? NSNumber)?.intValue == 1 &&
                focus["direction"] as? String == "near" &&
                focus["step"] as? String == "large"
        }
        try tapForAsyncTest(app.buttons["Done"])

        _ = try await simulatorRequest(
            path: "/ccapi/test/mode",
            method: "POST",
            queryItems: [URLQueryItem(name: "mode", value: "Bulb")]
        )
        try await waitForSimulatorState { state in state["mode"] as? String == "Bulb" }
        _ = try XCTUnwrap((waitForLabel(app.buttons["shutter-button"], containing: "Start Bulb exposure", timeout: 15)) ? true : nil)
        try tapForAsyncTest(app.buttons["shutter-button"])
        try await waitForSimulatorState { state in
            state["bulb_exposure_active"] as? Bool == true &&
                (state["bulb_start_count"] as? NSNumber)?.intValue == 1
        }
        _ = try XCTUnwrap((waitForLabel(app.buttons["shutter-button"], containing: "Stop Bulb exposure", timeout: 8)) ? true : nil)
        try tapForAsyncTest(app.buttons["shutter-button"])
        try await waitForSimulatorState { state in
            state["bulb_exposure_active"] as? Bool == false &&
                (state["bulb_stop_count"] as? NSNumber)?.intValue == 1
        }

        let captureMode = app.segmentedControls["capture-mode-picker"]
        _ = try XCTUnwrap((captureMode.waitForExistence(timeout: 8)) ? true : nil)
        try tapForAsyncTest(captureMode.buttons["Video"])
        try await waitForSimulatorState { state in
            state["movie_mode"] as? String == "on" &&
                (state["movie_mode_update_count"] as? NSNumber)?.intValue == 1
        }
        let record = app.buttons["record-button"]
        _ = try XCTUnwrap((waitForInteraction(record, timeout: 8)) ? true : nil)
        try tapForAsyncTest(record)
        try await waitForSimulatorState { state in state["recording"] as? Bool == true }
        _ = try XCTUnwrap((waitForLabel(record, containing: "Stop recording", timeout: 15)) ? true : nil)
        try tapForAsyncTest(record)
        try await waitForSimulatorState { state in state["recording"] as? Bool == false }
        try tapForAsyncTest(captureMode.buttons["Photo"])
        try await waitForSimulatorState { state in
            state["movie_mode"] as? String == "off" &&
                (state["movie_mode_update_count"] as? NSNumber)?.intValue == 2
        }

        try openMoreActionsForAsyncTest(in: app)
        guard try tapCameraActionForAsyncTest(app.buttons["camera-media-menu-button"], in: app) else { return }
        _ = try XCTUnwrap((app.staticTexts["SIM_0003.PNG"].waitForExistence(timeout: 20)) ? true : nil)

        let previewMedia = app.buttons["preview-media-SIM_0003.PNG"]
        _ = try XCTUnwrap((waitForInteraction(previewMedia, timeout: 8)) ? true : nil)
        try tapForAsyncTest(previewMedia)
        _ = try XCTUnwrap((app.buttons["close-media-preview"].waitForExistence(timeout: 8)) ? true : nil)
        _ = try XCTUnwrap((app.staticTexts["media-preview-position"].waitForExistence(timeout: 3)) ? true : nil)
        _ = try XCTUnwrap((app.buttons["media-preview-download-SIM_0003.PNG"].waitForExistence(timeout: 3)) ? true : nil)
        _ = try XCTUnwrap((app.buttons["media-preview-actions-SIM_0003.PNG"].waitForExistence(timeout: 3)) ? true : nil)
        try tapForAsyncTest(app.buttons["close-media-preview"])

        let mediaActions = app.buttons["media-actions-SIM_0003.PNG"]
        _ = try XCTUnwrap((waitForInteraction(mediaActions, timeout: 8)) ? true : nil)
        try tapForAsyncTest(mediaActions)
        let deleteMedia = app.buttons["delete-media-SIM_0003.PNG"]
        _ = try XCTUnwrap((scrollToInteraction(deleteMedia, in: app, timeout: 8)) ? true : nil)
        try tapForAsyncTest(deleteMedia)
        let deleteAlert = app.alerts["Delete from camera?"]
        _ = try XCTUnwrap((deleteAlert.waitForExistence(timeout: 5)) ? true : nil)
        try tapForAsyncTest(deleteAlert.buttons["Delete"])
        try await waitForSimulatorState { state in
            !(state["media_ids"] as? [String] ?? []).contains("SIM_0003.PNG")
        }
        _ = try XCTUnwrap((app.staticTexts["SIM_0003.PNG"].waitForNonExistence(timeout: 8)) ? true : nil)

        try tapForAsyncTest(app.buttons["media-back-button"])
        try openMoreActionsForAsyncTest(in: app)
        guard try tapCameraActionForAsyncTest(app.buttons["disconnect-menu-button"], in: app) else { return }
        guard waitForConnectionScreen(in: app, timeout: 15) else { return }
    }

    @MainActor
    func testCanonicalShutterAFChoiceReachesCameraAndNewJpegPreviewInBothLanguages() async throws {
        // Swift errors unwind this async test before XCTest begins the next case.
        continueAfterFailure = true
        guard try await waitForSimulatorHealth() else {
            #if OEC_REQUIRE_SIMULATOR_E2E
            XCTFail("The required synthetic camera is not reachable")
            return
            #else
            throw XCTSkip("Start the synthetic camera for the shutter AF UI journey")
            #endif
        }
        for (language, apple, locale, onValue, offValue, explanation) in [
            ("english", "en", "en_US", "On", "Off", "does not ask for autofocus"),
            ("traditionalChinese", "zh-Hant", "zh_TW", "開啟", "關閉", "不要求自動對焦"),
        ] {
            _ = try await simulatorRequest(path: "/ccapi/test/reset", method: "POST")
            _ = try await simulatorRequest(path: "/ccapi/test/capture-delivery", method: "POST",
                                           jsonBody: ["enabled": true, "hidden_listing_reads": 1, "thumbnail_failures": 1])
            _ = try await simulatorRequest(path: "/ccapi/ver100/shooting/settings/shootingmode", method: "PUT",
                                           jsonBody: ["value": "Manual"])
            let app = launch(appLanguage: language, appleLanguage: apple, locale: locale,
                             environment: ["OEC_HTTP_PRESET_URL": simulatorURL.absoluteString])
            defer { app.terminate() }
            try tapForAsyncTest(app.buttons["preset-http-button"])
            _ = try XCTUnwrap((scrollConnectionButtonIntoView(in: app, timeout: 8)) ? true : nil)
            try tapForAsyncTest(app.buttons["connect-button"])
            let toggle = app.buttons["shutter-autofocus-toggle"]
            _ = try XCTUnwrap((waitForInteraction(toggle, timeout: 30)) ? true : nil)
            let coordinateRoundingTolerance = 0.000_001
            _ = try XCTUnwrap(((toggle.frame.width + coordinateRoundingTolerance) >= (44)) ? true : nil)
            _ = try XCTUnwrap(((toggle.frame.height + coordinateRoundingTolerance) >= (44)) ? true : nil)
            _ = try XCTUnwrap((toggle.isSelected) ? true : nil)
            _ = try XCTUnwrap(((toggle.value as? String) == (onValue)) ? true : nil)
            try tapForAsyncTest(toggle)
            _ = try XCTUnwrap((!(toggle.isSelected)) ? true : nil)
            _ = try XCTUnwrap(((toggle.value as? String) == (offValue)) ? true : nil)
            _ = try XCTUnwrap((app.staticTexts["shutter-autofocus-description"].label.contains(explanation)) ? true : nil)
            let unchanged = try await simulatorRequest(path: "/ccapi/test/state")
            _ = try XCTUnwrap((((unchanged["capture_count"] as? NSNumber)?.intValue) == (0)) ? true : nil)
            _ = try XCTUnwrap((((unchanged["canonical"] as? [String: Any])?["shutter_af_requests"] as? [Bool]) == ([])) ? true : nil)
            _ = try XCTUnwrap(waitForLabel(app.buttons["latest-media-button"], containing: "SIM_0002.PNG", timeout: 10) ? true : nil)
            let shutter = app.buttons["shutter-button"]
            _ = try XCTUnwrap((waitForInteraction(shutter, timeout: 8)) ? true : nil)
            try tapForAsyncTest(shutter)
            try await waitForSimulatorState { state in
                (state["canonical"] as? [String: Any])?["shutter_af_requests"] as? [Bool] == [false]
            }
            let latest = app.buttons["latest-media-button"]
            _ = try XCTUnwrap((waitForLabel(latest, containing: "SIM_0003.JPG", timeout: 20)) ? true : nil)
            _ = try XCTUnwrap((waitForInteraction(latest, timeout: 8)) ? true : nil)
            try tapForAsyncTest(latest)
            _ = try XCTUnwrap((app.buttons["close-media-preview"].waitForExistence(timeout: 15)) ? true : nil)
            _ = try XCTUnwrap((app.images["media-preview-image"].waitForExistence(timeout: 15)) ? true : nil)
            let mediaID = "/ccapi/ver100/contents/card1/100CANON/SIM_0003.JPG"
            let download = app.buttons["media-preview-download-\(mediaID)"]
            _ = try XCTUnwrap(waitForInteraction(download, timeout: 5) ? true : nil)
            try tapForAsyncTest(download)
            let share = app.buttons["media-preview-share-\(mediaID)"]
            _ = try XCTUnwrap(waitForInteraction(share, timeout: 10) ? true : nil)
            let delivered = try await simulatorRequest(path: "/ccapi/test/state")
            _ = try XCTUnwrap((delivered["capture_count"] as? NSNumber)?.intValue == 1 ? true : nil)
            let fixture = try XCTUnwrap(delivered["capture_delivery"] as? [String: Any])
            let gets = try XCTUnwrap(fixture["representation_get_counts"] as? [String: NSNumber])
            _ = try XCTUnwrap(gets["original"]?.intValue == 1 ? true : nil)
            _ = try XCTUnwrap(gets["display"]?.intValue == 1 ? true : nil)
            let failures = try XCTUnwrap(fixture["representation_failure_counts"] as? [String: NSNumber])
            _ = try XCTUnwrap(failures["thumbnail"]?.intValue == 1 ? true : nil)
            // This proves the production app can hand off its original, not external Files/Photos saving.
            addScreenshot(name: "shutter-af-new-jpeg-original-share-\(language)")
            try tapForAsyncTest(app.buttons["close-media-preview"])
            try tapForAsyncTest(app.buttons["media-back-button"])
            try openMoreActionsForAsyncTest(in: app)
            guard try tapCameraActionForAsyncTest(app.buttons["disconnect-menu-button"], in: app) else { return }
            _ = try XCTUnwrap((waitForConnectionScreen(in: app, timeout: 15)) ? true : nil)
            _ = try XCTUnwrap((scrollConnectionButtonIntoView(in: app, timeout: 8)) ? true : nil)
            try tapForAsyncTest(app.buttons["connect-button"])
            _ = try XCTUnwrap((waitForInteraction(toggle, timeout: 30)) ? true : nil)
            _ = try XCTUnwrap((toggle.isSelected) ? true : nil)
            _ = try XCTUnwrap(((toggle.value as? String) == (onValue)) ? true : nil)
            try openMoreActionsForAsyncTest(in: app)
            guard try tapCameraActionForAsyncTest(app.buttons["disconnect-menu-button"], in: app) else { return }
            _ = try XCTUnwrap((waitForConnectionScreen(in: app, timeout: 15)) ? true : nil)
        }
    }

    @MainActor
    func testCanonicalCaptureReviewRetriesReadsAndFailedPreviewOriginalWithoutReshooting() async throws {
        continueAfterFailure = true
        guard try await waitForSimulatorHealth() else {
            #if OEC_REQUIRE_SIMULATOR_E2E
            XCTFail("The required synthetic camera is not reachable")
            return
            #else
            throw XCTSkip("Start the synthetic camera for the JPEG delivery recovery journey")
            #endif
        }
        for (language, apple, locale, notReady, readFailed, previous, outsideList) in [
            ("english", "en", "en_US", "No newly visible photo", "photo list could not be read", "previous photo", "Not in the current list"),
            ("traditionalChinese", "zh-Hant", "zh_TW", "尚未找到新出現", "無法讀取照片清單", "先前照片", "目前清單未列出"),
        ] {
            _ = try await simulatorRequest(path: "/ccapi/test/reset", method: "POST")
            _ = try await simulatorRequest(path: "/ccapi/test/capture-delivery", method: "POST", jsonBody: [
                "enabled": true, "listing_failures": 1, "hidden_listing_reads": 20,
                "thumbnail_failures": 1, "display_failures": 1, "original_failures": 3,
            ])
            _ = try await simulatorRequest(path: "/ccapi/ver100/shooting/settings/shootingmode", method: "PUT",
                                           jsonBody: ["value": "Manual"])
            let app = launch(appLanguage: language, appleLanguage: apple, locale: locale,
                             environment: ["OEC_HTTP_PRESET_URL": simulatorURL.absoluteString])
            defer { app.terminate() }
            try tapForAsyncTest(app.buttons["preset-http-button"])
            _ = try XCTUnwrap(scrollConnectionButtonIntoView(in: app, timeout: 8) ? true : nil)
            try tapForAsyncTest(app.buttons["connect-button"])
            let toggle = app.buttons["shutter-autofocus-toggle"]
            _ = try XCTUnwrap(waitForInteraction(toggle, timeout: 30) ? true : nil)
            try tapForAsyncTest(toggle)
            let latest = app.buttons["latest-media-button"]
            _ = try XCTUnwrap(waitForLabel(latest, containing: "SIM_0002.PNG", timeout: 10) ? true : nil)
            try tapForAsyncTest(app.buttons["shutter-button"])
            let status = app.staticTexts["capture-review-status"]
            _ = try XCTUnwrap(waitForLabel(status, containing: notReady, timeout: 15) ? true : nil)
            _ = try XCTUnwrap(latest.label.contains(previous) ? true : nil)
            let retry = app.buttons["capture-review-retry"]
            _ = try XCTUnwrap(waitForInteraction(retry, timeout: 5) ? true : nil)
            _ = try XCTUnwrap(retry.frame.height + 0.000_001 >= 44 && retry.frame.width + 0.000_001 >= 44 ? true : nil)
            addScreenshot(name: "capture-review-not-ready-\(language)")
            // Cover every GET in the four bounded rounds, including Canon query fallbacks.
            // Wait for the terminal status rather than assuming a transient UI state.
            let forcedFailures = try await simulatorRequest(path: "/ccapi/test/capture-delivery", method: "POST",
                                                           jsonBody: ["listing_failures": 20])
            let failuresBefore = try XCTUnwrap((forcedFailures["listing_failure_count"] as? NSNumber)?.intValue)
            try tapForAsyncTest(retry)
            _ = try XCTUnwrap(waitForLabel(status, containing: readFailed, timeout: 15) ? true : nil)
            _ = try XCTUnwrap(waitForInteraction(retry, timeout: 5) ? true : nil)
            _ = try XCTUnwrap(retry.frame.height + 0.000_001 >= 44 && retry.frame.width + 0.000_001 >= 44 ? true : nil)
            _ = try XCTUnwrap(latest.label.contains(previous) ? true : nil)
            let failedReview = try await simulatorRequest(path: "/ccapi/test/state")
            _ = try XCTUnwrap((failedReview["capture_count"] as? NSNumber)?.intValue == 1 ? true : nil)
            _ = try XCTUnwrap((failedReview["canonical"] as? [String: Any])?["shutter_af_requests"] as? [Bool] == [false] ? true : nil)
            let failedFixture = try XCTUnwrap(failedReview["capture_delivery"] as? [String: Any])
            let failuresAfter = try XCTUnwrap((failedFixture["listing_failure_count"] as? NSNumber)?.intValue)
            _ = try XCTUnwrap(failuresAfter - failuresBefore >= 4 ? true : nil)
            addScreenshot(name: "capture-review-read-failed-\(language)")
            _ = try await simulatorRequest(path: "/ccapi/test/capture-delivery", method: "POST",
                                           jsonBody: ["hidden_listing_reads": 0, "listing_failures": 0])
            try tapForAsyncTest(retry)
            _ = try XCTUnwrap(waitForLabel(latest, containing: "SIM_0003.JPG", timeout: 10) ? true : nil)
            _ = try XCTUnwrap(waitForInteraction(latest, timeout: 5) ? true : nil)
            try tapForAsyncTest(latest)
            _ = try XCTUnwrap(app.alerts.firstMatch.waitForExistence(timeout: 10) ? true : nil)
            try tapForAsyncTest(app.alerts.buttons.firstMatch)
            let previewRetry = app.buttons["retry-media-preview"]
            _ = try XCTUnwrap(waitForInteraction(previewRetry, timeout: 5) ? true : nil)
            try tapForAsyncTest(previewRetry)
            _ = try XCTUnwrap(app.images["media-preview-image"].waitForExistence(timeout: 10) ? true : nil)
            let mediaID = "/ccapi/ver100/contents/card1/100CANON/SIM_0003.JPG"
            let download = app.buttons["media-preview-download-\(mediaID)"]
            let share = app.buttons["media-preview-share-\(mediaID)"]
            let cancel = app.buttons["media-preview-cancel-download-\(mediaID)"]
            _ = try XCTUnwrap(waitForInteraction(download, timeout: 5) ? true : nil)
            try tapForAsyncTest(download)
            _ = try XCTUnwrap(app.alerts.firstMatch.waitForExistence(timeout: 10) ? true : nil)
            try tapForAsyncTest(app.alerts.buttons.firstMatch)
            _ = try XCTUnwrap(!share.exists ? true : nil)
            _ = try await simulatorRequest(path: "/ccapi/test/capture-delivery", method: "POST",
                                           jsonBody: ["original_failures": 0, "original_delay_ms": 60_000])
            _ = try XCTUnwrap(waitForInteraction(download, timeout: 5) ? true : nil)
            try tapForAsyncTest(download)
            try await waitForSimulatorState { observed in
                let fixture = observed["capture_delivery"] as? [String: Any]
                return (fixture?["representation_get_counts"] as? [String: NSNumber])?["original"]?.intValue == 4
            }
            _ = try XCTUnwrap(waitForInteraction(cancel, timeout: 5) ? true : nil)
            try tapForAsyncTest(cancel)
            _ = try XCTUnwrap(waitForInteraction(download, timeout: 5) ? true : nil)
            _ = try XCTUnwrap(!share.exists ? true : nil)
            _ = try await simulatorRequest(path: "/ccapi/test/capture-delivery", method: "POST",
                                           jsonBody: ["original_delay_ms": 0])
            try tapForAsyncTest(download)
            _ = try XCTUnwrap(waitForInteraction(share, timeout: 10) ? true : nil)
            let delivered = try await simulatorRequest(path: "/ccapi/test/state")
            _ = try XCTUnwrap((delivered["capture_count"] as? NSNumber)?.intValue == 1 ? true : nil)
            _ = try XCTUnwrap((delivered["canonical"] as? [String: Any])?["shutter_af_requests"] as? [Bool] == [false] ? true : nil)
            let fixture = try XCTUnwrap(delivered["capture_delivery"] as? [String: Any])
            let gets = try XCTUnwrap(fixture["representation_get_counts"] as? [String: NSNumber])
            _ = try XCTUnwrap(gets["display"]?.intValue == 2 && gets["original"]?.intValue == 5 ? true : nil)
            // Hide the selected JPEG from a later partial listing without deleting it.
            // Deleting a different synthetic old item emits a real contents notification.
            _ = try await simulatorRequest(path: "/ccapi/test/capture-delivery", method: "POST",
                                           jsonBody: ["hidden_listing_reads": 20])
            _ = try await simulatorRequest(path: "/ccapi/ver100/contents/card1/100CANON/SIM_0001.PNG", method: "DELETE")
            _ = try XCTUnwrap(waitForLabel(app.staticTexts["media-preview-position"], containing: outsideList, timeout: 15) ? true : nil)
            _ = try XCTUnwrap(app.images["media-preview-image"].exists ? true : nil)
            _ = try XCTUnwrap(waitForInteraction(share, timeout: 5) ? true : nil)
            // isHittable describes a computed hit point, not the disabled interaction contract.
            // SwiftUI may omit a transparent disabled control from the accessibility tree.
            let navigationUnavailable = XCTNSPredicateExpectation(
                predicate: NSPredicate { _, _ in
                    let previous = app.buttons["media-preview-previous"]
                    let next = app.buttons["media-preview-next"]
                    return (!previous.exists || !previous.isEnabled) && (!next.exists || !next.isEnabled)
                },
                object: nil
            )
            _ = try XCTUnwrap(
                XCTWaiter.wait(for: [navigationUnavailable], timeout: 5) == .completed ? true : nil,
                "Navigation must be unavailable while the selected JPEG is outside the current listing"
            )
            addScreenshot(name: "capture-review-recovered-original-share-\(language)")
        }
    }

    @MainActor
    func testCanonicalCCAPIEventsRefreshTheProductionUI() async throws {
        // Swift errors unwind this async test before XCTest begins the next case.
        continueAfterFailure = true
        let available = try await waitForSimulatorHealth()
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
        defer { app.terminate() }
        let httpPreset = app.buttons["preset-http-button"]
        _ = try XCTUnwrap((httpPreset.waitForExistence(timeout: 8)) ? true : nil)
        try tapForAsyncTest(httpPreset)

        let urlField = app.textFields["camera-url-field"]
        _ = try XCTUnwrap((urlField.waitForExistence(timeout: 3)) ? true : nil)
        _ = try XCTUnwrap(((urlField.value as? String) == (simulatorURL.absoluteString)) ? true : nil)
        let connect = app.buttons["connect-button"]
        _ = try XCTUnwrap((waitForInteraction(connect, timeout: 8)) ? true : nil)
        try tapForAsyncTest(connect)

        _ = try XCTUnwrap((app.descendants(matching: .any)["camera-model-status"].waitForExistence(timeout: 30)) ? true : nil)
        _ = try XCTUnwrap((app.images["live-view-decoded-frame"].waitForExistence(timeout: 30)) ? true : nil)
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
        _ = try XCTUnwrap((waitForLabel(app.buttons["exposure-iso"], containing: "3200", timeout: 20)) ? true : nil)

        try openMoreActionsForAsyncTest(in: app)
        guard try tapCameraActionForAsyncTest(app.buttons["camera-media-menu-button"], in: app) else { return }
        _ = try XCTUnwrap((app.staticTexts["SIM_0002.PNG"].waitForExistence(timeout: 20)) ? true : nil)

        _ = try await simulatorRequest(
            path: "/ccapi/ver100/shooting/control/shutterbutton",
            method: "POST",
            jsonBody: ["af": true]
        )
        _ = try XCTUnwrap((app.staticTexts["SIM_0003.JPG"].waitForExistence(timeout: 20)) ? true : nil)
        try await waitForSimulatorState { state in
            guard let canonical = state["canonical"] as? [String: Any] else { return false }
            return ((canonical["event_cursor"] as? NSNumber)?.intValue ?? 0) >= 3
        }

        try tapForAsyncTest(app.buttons["media-back-button"])
        try openMoreActionsForAsyncTest(in: app)
        guard try tapCameraActionForAsyncTest(app.buttons["disconnect-menu-button"], in: app) else { return }
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
        // Swift errors unwind this async test before XCTest begins the next case.
        continueAfterFailure = true
        let available = try await waitForSimulatorHealth()
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
        defer { app.terminate() }
        let httpPreset = app.buttons["preset-http-button"]
        _ = try XCTUnwrap((httpPreset.waitForExistence(timeout: 8)) ? true : nil)
        try tapForAsyncTest(httpPreset)
        let connect = app.buttons["connect-button"]
        _ = try XCTUnwrap((waitForInteraction(connect, timeout: 8)) ? true : nil)
        try tapForAsyncTest(connect)
        _ = try XCTUnwrap((app.descendants(matching: .any)["camera-model-status"].waitForExistence(timeout: 30)) ? true : nil)

        try openMoreActionsForAsyncTest(in: app)
        guard try tapCameraActionForAsyncTest(app.buttons["camera-media-menu-button"], in: app) else { return }
        _ = try XCTUnwrap((app.staticTexts["SIM_0002.PNG"].waitForExistence(timeout: 8)) ? true : nil)
        _ = try XCTUnwrap((app.descendants(matching: .any)["media-library-loading-progressive"].waitForExistence(timeout: 3)) ? true : nil)
        let loadingSummary = app.staticTexts["media-library-summary-loading"]
        _ = try XCTUnwrap((waitForLabel(loadingSummary, containing: "Loading", timeout: 3)) ? true : nil)
        let cancel = app.buttons["cancel-media-library-load"]
        _ = try XCTUnwrap((waitForInteraction(cancel, timeout: 3)) ? true : nil)
        try tapForAsyncTest(cancel)

        let cancelledSummary = app.staticTexts["media-library-summary-cancelled"]
        _ = try XCTUnwrap((waitForLabel(cancelledSummary, containing: "incomplete", timeout: 8)) ? true : nil)
        _ = try XCTUnwrap((app.staticTexts["SIM_0002.PNG"].exists) ? true : nil)
        _ = try XCTUnwrap((app.staticTexts["SIM_0001.PNG"].waitForNonExistence(timeout: 6)) ? true : nil)
        _ = try XCTUnwrap((app.buttons["refresh-media"].waitForExistence(timeout: 3)) ? true : nil)

        try tapForAsyncTest(app.buttons["media-back-button"])
        try openMoreActionsForAsyncTest(in: app)
        guard try tapCameraActionForAsyncTest(app.buttons["disconnect-menu-button"], in: app) else { return }
        guard waitForConnectionScreen(in: app, timeout: 15) else { return }
    }

    @MainActor
    func testLiveViewTapActionSelectionRetainsStateWithoutSendingLiveViewCommands() async throws {
        // Swift errors unwind this async test before XCTest begins the next case.
        continueAfterFailure = true
        let available = try await waitForSimulatorHealth()
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
        defer { app.terminate() }
        let simulatorPreset = app.buttons["preset-simulator-button"]
        _ = try XCTUnwrap((simulatorPreset.waitForExistence(timeout: 8)) ? true : nil)
        try tapForAsyncTest(simulatorPreset)
        try tapForAsyncTest(app.buttons["connect-button"])
        _ = try XCTUnwrap((app.descendants(matching: .any)["camera-model-status"].waitForExistence(timeout: 30)) ? true : nil)
        _ = try XCTUnwrap((app.images["live-view-decoded-frame"].waitForExistence(timeout: 30)) ? true : nil)

        try openMoreSettingsForTapSelection(in: app)
        let control = app.otherElements["live-view-tap-action-control"]
        _ = try XCTUnwrap((control.waitForExistence(timeout: 5)) ? true : nil)
        _ = try XCTUnwrap((control.buttons[LiveViewTapSelection.focus.buttonIdentifier].isSelected) ? true : nil)
        _ = try XCTUnwrap((!(control.buttons[LiveViewTapSelection.whiteBalance.buttonIdentifier].isSelected)) ? true : nil)
        _ = try XCTUnwrap(((control.value as? String) == (LiveViewTapSelection.focus.rawValue)) ? true : nil)
        try await assertNoSimulatorLiveViewCommands()

        // Each change is a single real UI tap. Reopening must retain the
        // production choice, and choosing a mode must not operate the camera.
        for choice in [LiveViewTapSelection.whiteBalance, .focus] {
            guard try selectLiveViewTapAction(choice, in: app, phase: "selection-only-\(choice.rawValue)") else { return }
            try await assertNoSimulatorLiveViewCommands()
            try tapForAsyncTest(app.buttons["Done"])
            _ = try XCTUnwrap((control.waitForNonExistence(timeout: 5)) ? true : nil)
            try openMoreSettingsForTapSelection(in: app)
            _ = try XCTUnwrap((control.waitForExistence(timeout: 5)) ? true : nil)
            for candidate in LiveViewTapSelection.allCases {
                _ = try XCTUnwrap(((control.buttons[candidate.buttonIdentifier].isSelected) == (candidate == choice)) ? true : nil)
            }
            _ = try XCTUnwrap(((control.value as? String) == (choice.rawValue)) ? true : nil)
            try await assertNoSimulatorLiveViewCommands()
        }
    }

    private func launch(
        appLanguage: String,
        appleLanguage: String,
        locale: String,
        environment: [String: String] = [:],
        contentSizeCategory: String? = nil
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
        if let contentSizeCategory {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", contentSizeCategory]
        }
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

    private enum LiveViewTapSelection: String, CaseIterable {
        case focus
        case whiteBalance

        var buttonIdentifier: String { "live-view-tap-action-\(rawValue)" }
    }

    private func openMoreSettingsForTapSelection(in app: XCUIApplication) throws {
        try openMoreActionsForAsyncTest(in: app)
        let moreSettings = app.buttons["more-settings-menu-button"]
        _ = try XCTUnwrap((waitForInteraction(moreSettings, timeout: 8)) ? true : nil)
        try tapForAsyncTest(moreSettings)
    }

    @MainActor
    private func selectLiveViewTapAction(
        _ choice: LiveViewTapSelection, in app: XCUIApplication, phase: String,
        file: StaticString = #filePath, line: UInt = #line
    ) throws -> Bool {
        let control = app.otherElements["live-view-tap-action-control"]
        let button = control.buttons[choice.buttonIdentifier]
        guard waitForInteraction(button, timeout: 5) else {
            XCTFail("The \(choice.rawValue) button did not become interactive", file: file, line: line)
            return false
        }
        // Use one real button tap, with no warmup, retry, or intervening AX reads.
        try requireNoRecordedUIFailure(file: file, line: line)
        button.tap()
        let deadline = ProcessInfo.processInfo.systemUptime + 5
        try requireNoRecordedUIFailure(file: file, line: line)
        let selected = XCTNSPredicateExpectation(
            predicate: NSPredicate { candidate, _ in
                (candidate as? XCUIElement)?.isSelected == true
            },
            object: button
        )
        let remaining = max(0, deadline - ProcessInfo.processInfo.systemUptime)
        guard remaining > 0, XCTWaiter().wait(for: [selected], timeout: remaining) == .completed else {
            addScreenshot(name: phase == "direct-ccapi" ? "click-white-balance-selection-failed" : "\(phase)-tap-selection-failed")
            XCTFail("The \(choice.rawValue) button did not select within five seconds", file: file, line: line)
            return false
        }
        // Selection and the production value must both agree before the test
        // dismisses the sheet or performs a real Live View operation.
        let controls = app.otherElements.matching(identifier: "live-view-tap-action-control")
        guard controls.count == 1, control.value as? String == choice.rawValue else {
            XCTFail("The production value or control cardinality did not match the selection", file: file, line: line)
            return false
        }
        for candidate in LiveViewTapSelection.allCases {
            let buttons = control.buttons.matching(identifier: candidate.buttonIdentifier)
            guard buttons.count == 1, buttons.firstMatch.isSelected == (candidate == choice) else {
                XCTFail("The \(candidate.rawValue) button selection or cardinality did not match", file: file, line: line)
                return false
            }
            // AX CGRect subtraction can report 43.99999999999994 for a 44pt frame.
            // A millionth of a point covers representation error, not layout slack.
            let coordinateRoundingTolerance = 0.000_001
            _ = try XCTUnwrap(((buttons.firstMatch.frame.width + coordinateRoundingTolerance) >= (44)) ? true : nil, file: file, line: line)
            _ = try XCTUnwrap(((buttons.firstMatch.frame.height + coordinateRoundingTolerance) >= (44)) ? true : nil, file: file, line: line)
        }
        return true
    }

    private func assertNoSimulatorLiveViewCommands(
        file: StaticString = #filePath, line: UInt = #line
    ) async throws {
        let state = try await simulatorRequest(path: "/ccapi/test/state")
        _ = try XCTUnwrap((((state["focus"] as? [String: Any])?["count"] as? Int) == (0)) ? true : nil, file: file, line: line)
        _ = try XCTUnwrap((((state["click_white_balance"] as? [String: Any])?["count"] as? Int) == (0)) ? true : nil, file: file, line: line)
    }

    private func scrollToInteraction(
        _ element: XCUIElement,
        in app: XCUIApplication,
        timeout: TimeInterval
    ) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.exists, element.isEnabled, element.isHittable { return true }
            let scrollSurface = app.scrollViews.allElementsBoundByIndex.last {
                $0.exists && $0.isHittable
            }
            if let scrollSurface {
                scrollSurface.swipeUp()
            } else {
                app.swipeUp()
            }
        }
        return element.exists && element.isEnabled && element.isHittable
    }

    private static func connectionButtonHasVisibleHitFrame(
        _ button: CGRect, scroll: CGRect, window: CGRect
    ) -> Bool {
        guard [button, scroll, window].allSatisfy({ frame in
            !frame.isNull && !frame.isInfinite && !frame.isEmpty
                && [frame.minX, frame.minY, frame.maxX, frame.maxY].allSatisfy { $0.isFinite }
        }), button.width >= 44, button.height >= 44 else { return false }
        let viewport = scroll.intersection(window)
        let center = CGPoint(x: button.midX, y: button.midY)
        return !viewport.isEmpty && viewport.contains(button) && viewport.contains(center)
    }

    private func scrollConnectionButtonIntoView(in app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let connect = app.buttons["connect-button"]
        let scroll = app.scrollViews["connection-scroll-view"]
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        var swipes = 0
        while ProcessInfo.processInfo.systemUptime < deadline, swipes < 8 {
            guard connect.exists, scroll.exists else {
                Thread.sleep(forTimeInterval: min(0.05, max(0, deadline - ProcessInfo.processInfo.systemUptime)))
                continue
            }
            let frame = connect.frame
            let window = app.windows.firstMatch.frame
            let viewport = scroll.frame.intersection(window)
            if Self.connectionButtonHasVisibleHitFrame(frame, scroll: scroll.frame, window: window) {
                if connect.isEnabled && connect.isHittable { return true }
                // A visible but disabled button needs state to settle, not a swipe.
                Thread.sleep(forTimeInterval: min(0.05, max(0, deadline - ProcessInfo.processInfo.systemUptime)))
                continue
            }
            guard !viewport.isNull, !viewport.isEmpty else { return false }
            guard ProcessInfo.processInfo.systemUptime < deadline else { break }
            // Only this known scroll container may move. Do not trust AX's
            // isHittable for offscreen content or tap until the frame is visible.
            if frame.maxY > viewport.maxY {
                scroll.swipeUp()
            } else if frame.minY < viewport.minY {
                scroll.swipeDown()
            } else {
                return false // Vertical scrolling cannot fix width or target size.
            }
            swipes += 1
        }
        return connect.exists && scroll.exists && connect.isEnabled && connect.isHittable
            && Self.connectionButtonHasVisibleHitFrame(
                connect.frame, scroll: scroll.frame, window: app.windows.firstMatch.frame
            )
    }

    private func assertConnectionButtonHitFrame(
        in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line
    ) {
        let frame = app.buttons["connect-button"].frame
        let scroll = app.scrollViews["connection-scroll-view"].frame
        let window = app.windows.firstMatch.frame
        XCTAssertTrue(
            Self.connectionButtonHasVisibleHitFrame(frame, scroll: scroll, window: window),
            "Connect \(frame) must be a full 44pt target inside scroll/window viewport \(scroll.intersection(window))",
            file: file, line: line
        )
    }

    private func requireNoRecordedUIFailure(
        file: StaticString = #filePath, line: UInt = #line
    ) throws {
        let run = try XCTUnwrap(testRun, "The UI test must have an active run", file: file, line: line)
        _ = try XCTUnwrap(
            run.totalFailureCount == 0 ? true : nil,
            "Stop this async journey after the already-recorded UI failure", file: file, line: line
        )
    }

    private func tapForAsyncTest(
        _ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line
    ) throws {
        try requireNoRecordedUIFailure(file: file, line: line)
        element.tap()
        try requireNoRecordedUIFailure(file: file, line: line)
    }

    private func tapForAsyncTest(
        _ coordinate: XCUICoordinate, file: StaticString = #filePath, line: UInt = #line
    ) throws {
        try requireNoRecordedUIFailure(file: file, line: line)
        coordinate.tap()
        try requireNoRecordedUIFailure(file: file, line: line)
    }

    private func openMoreActionsForAsyncTest(in app: XCUIApplication) throws {
        let moreActions = app.buttons["more-actions-button"]
        _ = try XCTUnwrap(waitForInteraction(moreActions, timeout: 8) ? true : nil)
        try tapForAsyncTest(moreActions)
    }

    private func tapCameraActionForAsyncTest(_ element: XCUIElement, in app: XCUIApplication) throws -> Bool {
        guard scrollToInteraction(element, in: app, timeout: 8) else {
            XCTFail("The requested camera action did not become interactive")
            return false
        }
        try tapForAsyncTest(element)
        return true
    }

    private func openMoreActions(in app: XCUIApplication) {
        let moreActions = app.buttons["more-actions-button"]
        XCTAssertTrue(waitForInteraction(moreActions, timeout: 8))
        moreActions.tap()
    }

    private func recordShutterRecoveryDisconnectGeometry(in app: XCUIApplication, phase: String) {
        let disconnect = app.buttons["disconnect-menu-button"]
        let disconnectFrame = disconnect.exists ? disconnect.frame : CGRect.null
        let disconnectCenter = CGPoint(x: disconnectFrame.midX, y: disconnectFrame.midY)
        let actionsSheetExists = disconnect.exists && app.navigationBars.firstMatch.exists
        var lines = ["[OEC_SHUTTER_DISCONNECT_GEOMETRY] \(phase)"]
        lines.append("window=\(app.windows.firstMatch.frame)")
        lines.append("more-actions-sheet-exists=\(actionsSheetExists)")
        for identifier in [
            "disconnect-menu-button", "release-shutter-button", "shutter-button",
            "more-actions-button", "connect-button",
        ] {
            let matches = app.buttons.matching(identifier: identifier)
            lines.append("\(identifier) count=\(matches.count)")
            // The root and sheet can both expose a Stop. Record each rather than
            // assuming the first accessibility match is the visible one.
            for (index, element) in matches.allElementsBoundByIndex.prefix(4).enumerated() {
                let frame = element.frame
                lines.append("\(identifier)[\(index)] enabled=\(element.isEnabled) hittable=\(element.isHittable) frame=\(frame)")
                if identifier == "release-shutter-button" || identifier == "shutter-button" {
                    let overlap = disconnectFrame.intersection(frame)
                    let containsCenter = !disconnectFrame.isNull && frame.contains(disconnectCenter)
                    lines.append("\(identifier)[\(index)] disconnect-overlap=\(overlap) contains-disconnect-center=\(containsCenter)")
                }
            }
        }
        for identifier in ["shutter-release-warning", "previous-shutter-release-warning", "camera-model-status"] {
            lines.append("\(identifier)-exists=\(app.staticTexts[identifier].exists)")
        }
        // Only fixed control IDs, geometry and booleans from the synthetic
        // recovery test are emitted, never arbitrary labels or camera details.
        let diagnostic = lines.joined(separator: "\n")
        print(diagnostic)
        let attachment = XCTAttachment(string: diagnostic)
        attachment.name = "shutter-recovery-disconnect-\(phase)-geometry"
        attachment.lifetime = .keepAlways
        add(attachment)
        addScreenshot(name: "shutter-recovery-disconnect-\(phase)")
    }

    private func assertSeparateRecoverySheetControls(
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> XCUIElement? {
        let disconnect = app.buttons["disconnect-menu-button"]
        XCTAssertTrue(waitForInteraction(disconnect, timeout: 5), file: file, line: line)
        let visibleStops = app.buttons.matching(identifier: "release-shutter-button")
            .allElementsBoundByIndex.filter { $0.exists && $0.isEnabled && $0.isHittable }
        XCTAssertEqual(visibleStops.count, 1, "Exactly one sheet Stop must be interactive", file: file, line: line)
        guard let stop = visibleStops.first else { return nil }
        let disconnectFrame = disconnect.frame
        let stopFrame = stop.frame
        XCTAssertTrue(
            disconnectFrame.intersection(stopFrame).isEmpty,
            "Disconnect \(disconnectFrame) must not overlap Stop \(stopFrame)",
            file: file, line: line
        )
        XCTAssertLessThanOrEqual(disconnectFrame.maxY, stopFrame.minY, file: file, line: line)
        let window = app.windows.firstMatch.frame
        for frame in [disconnectFrame, stopFrame] {
            XCTAssertTrue(window.contains(frame), "Sheet action must remain on screen: \(frame)", file: file, line: line)
            XCTAssertGreaterThanOrEqual(frame.height, 44, file: file, line: line)
        }
        return stop
    }

    private func recordShutterRecoveryConnectGeometry(in app: XCUIApplication, phase: String) {
        let connect = app.buttons["connect-button"]
        let connectFrame = connect.exists ? connect.frame : CGRect.null
        let center = CGPoint(x: connectFrame.midX, y: connectFrame.midY)
        let window = app.windows.firstMatch.frame
        let scrollView = app.scrollViews["connection-scroll-view"]
        let viewport = scrollView.exists ? scrollView.frame.intersection(window) : CGRect.null
        var lines = ["[OEC_SHUTTER_CONNECT_GEOMETRY] \(phase)"]
        lines.append("window=\(window)")
        lines.append("connection-scroll-view-exists=\(scrollView.exists)")
        lines.append("connection-scroll-view-frame=\(viewport)")
        lines.append("connect-visible-frame=\(connectFrame.intersection(viewport))")
        lines.append("connect-center-in-viewport=\(!connectFrame.isNull && viewport.contains(center))")
        lines.append("connect-fully-in-viewport=\(!connectFrame.isNull && viewport.contains(connectFrame))")
        lines.append("keyboard-count=\(app.keyboards.count)")
        lines.append("alert-count=\(app.alerts.count)")
        for identifier in [
            "connect-button", "offline-preview-button", "release-shutter-button",
            "disconnect-menu-button", "more-actions-button", "preset-http-button",
            "preset-https-button", "preset-simulator-button",
        ] {
            let matches = app.buttons.matching(identifier: identifier)
            lines.append("\(identifier) count=\(matches.count)")
            for (index, element) in matches.allElementsBoundByIndex.prefix(4).enumerated() {
                let frame = element.frame
                lines.append("\(identifier)[\(index)] enabled=\(element.isEnabled) hittable=\(element.isHittable) frame=\(frame)")
                if identifier != "connect-button" {
                    let overlap = connectFrame.intersection(frame)
                    let containsCenter = !connectFrame.isNull && frame.contains(center)
                    lines.append("\(identifier)[\(index)] connect-overlap=\(overlap) contains-connect-center=\(containsCenter)")
                }
            }
        }
        for identifier in ["shutter-release-warning", "previous-shutter-release-warning", "camera-model-status"] {
            lines.append("\(identifier)-exists=\(app.staticTexts[identifier].exists)")
        }
        let model = app.staticTexts["camera-model-status"]
        // Compare only known synthetic fixture names; never log the label itself,
        // connection field values, alert text, request data or authentication data.
        let modelLabel = model.exists ? model.label : ""
        lines.append("model-fixture-session-1=\(modelLabel == "Shutter recovery fixture shutter-fixture-1")")
        lines.append("model-fixture-session-2=\(modelLabel == "Shutter recovery fixture shutter-fixture-2")")
        let offlinePreview = app.staticTexts["Offline UI preview"].exists || app.staticTexts["離線 UI 預覽"].exists
        lines.append("offline-preview-visible=\(offlinePreview)")
        let diagnostic = lines.joined(separator: "\n")
        print(diagnostic)
        let attachment = XCTAttachment(string: diagnostic)
        attachment.name = "shutter-recovery-connect-\(phase)-geometry"
        attachment.lifetime = .keepAlways
        add(attachment)
        addScreenshot(name: "shutter-recovery-connect-\(phase)")
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
        file: StaticString = #filePath,
        line: UInt = #line,
        predicate: ([String: Any]) -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        var lastState: [String: Any]?
        repeat {
            try Task.checkCancellation()
            do {
                let state = try await simulatorRequest(path: "/ccapi/test/state")
                lastState = state
                if predicate(state) { return }
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as URLError {
                if error.code == .cancelled { throw error }
                try Task.checkCancellation()
                // A transient fixture request failure still uses the original deadline.
            } catch SimulatorTestError.invalidResponse {
                try Task.checkCancellation()
            }
            try await Task.sleep(nanoseconds: 250_000_000)
        } while Date() < deadline
        // This endpoint belongs only to the synthetic loopback test fixture.
        XCTFail("Simulator state did not match: \(String(describing: lastState))", file: file, line: line)
        throw SimulatorTestError.timeout
    }

    private func waitForSimulatorHealth(timeout: TimeInterval = 10) async throws -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            try Task.checkCancellation()
            do {
                let health = try await simulatorRequest(path: "/health", timeoutInterval: 1)
                if health["ok"] as? Bool == true { return true }
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as URLError {
                if error.code == .cancelled { throw error }
                try Task.checkCancellation()
                // Startup/readiness failures may retry only until the original deadline.
            } catch SimulatorTestError.invalidResponse {
                try Task.checkCancellation()
            }
            try await Task.sleep(nanoseconds: 250_000_000)
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
        try Task.checkCancellation()
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
        // UI steps can outlive Uvicorn's keep-alive, so avoid reusing a stale harness socket.
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
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
