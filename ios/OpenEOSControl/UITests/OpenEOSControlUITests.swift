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
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        delete.tap()

        let alert = app.alerts["Delete from camera?"]
        XCTAssertTrue(alert.waitForExistence(timeout: 3))
        XCTAssertTrue(item.exists)
        alert.buttons["Delete"].tap()
        XCTAssertTrue(item.waitForNonExistence(timeout: 3))
        addScreenshot(name: "media-delete-confirmed")
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

    private func addScreenshot(name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
