import XCTest

final class OpenEOSControlUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testOfflineCameraWorkflowInPortraitAndLandscape() throws {
        let app = launch(language: "en")
        let preview = app.buttons["offline-preview-button"]
        XCTAssertTrue(preview.waitForExistence(timeout: 8))
        preview.tap()

        XCTAssertTrue(app.otherElements["camera-control-view"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["shutter-button"].exists)
        addScreenshot(name: "control-portrait")

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.otherElements["live-view-surface"].waitForExistence(timeout: 3))
        addScreenshot(name: "control-landscape")

        app.buttons["more-actions-button"].tap()
        app.buttons["Debug"].tap()
        XCTAssertTrue(app.otherElements["debug-view"].waitForExistence(timeout: 3))
        addScreenshot(name: "debug-landscape")
    }

    func testTraditionalChineseConnectionScreen() throws {
        let app = launch(language: "zh-Hant")

        XCTAssertTrue(app.staticTexts["連接你的 EOS"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["offline-preview-button"].exists)
        addScreenshot(name: "connection-zh-Hant")
    }

    private func launch(language: String) -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = [
            "-resetState",
            "-AppleLanguages", "(\(language))",
            "-AppleLocale", language == "en" ? "en_US" : "zh_TW",
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
