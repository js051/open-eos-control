import CoreGraphics
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

@MainActor
final class CameraAppTests: XCTestCase {
    func testRollingFrameRateUsesRecentWindow() {
        var tracker = LiveViewRateTracker(window: 1)

        XCTAssertEqual(tracker.record(0), 0)
        XCTAssertEqual(tracker.record(0.1), 10, accuracy: 0.001)
        XCTAssertEqual(tracker.record(0.2), 10, accuracy: 0.001)
        XCTAssertEqual(tracker.record(1.2), 1, accuracy: 0.001)
    }

    func testAspectFitRectPreservesImageAndLetterboxes() {
        let rect = aspectFitRect(
            contentSize: CGSize(width: 3_000, height: 2_000),
            containerSize: CGSize(width: 400, height: 400)
        )

        XCTAssertEqual(rect.minX, 0, accuracy: 0.001)
        XCTAssertEqual(rect.width, 400, accuracy: 0.001)
        XCTAssertEqual(rect.height, 266.666, accuracy: 0.01)
        XCTAssertEqual(rect.midY, 200, accuracy: 0.001)
    }

    func testAdvancedSettingsAreFilteredByCaptureMode() {
        let settings = [
            CameraSetting(key: "iso", label: "ISO", value: "100", values: ["100"]),
            CameraSetting(key: "drivemode", label: "Drive", value: "single", values: ["single"]),
            CameraSetting(key: "moviequality", label: "Movie", value: "4K", values: ["4K"]),
            CameraSetting(key: "meteringmode", label: "Metering", value: "eval", values: ["eval"]),
        ]

        XCTAssertEqual(advancedSettingsForMode(settings, mode: .photo).map(\.key), ["drivemode", "meteringmode"])
        XCTAssertEqual(advancedSettingsForMode(settings, mode: .video).map(\.key), ["moviequality", "meteringmode"])
    }

    func testOfflinePreviewHasRealisticCapabilityGating() {
        let snapshot = CameraAppState.makeOfflinePreviewSnapshot()

        XCTAssertEqual(snapshot.info.model, "Canon EOS R6 Mark III")
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.liveView))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.stillCapture))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaDownload))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaDelete))
        XCTAssertFalse(snapshot.capabilities.matrix.supports(.focusDrive))
        XCTAssertEqual(snapshot.capabilities.liveView.maximumFPS, 30)
    }

    func testOfflinePreviewDeletionRemovesOnlyConfirmedItem() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()
        let item = state.mediaItems[1]

        await state.deleteMedia(item)

        XCTAssertEqual(state.mediaItems.count, 2)
        XCTAssertFalse(state.mediaItems.contains { $0.id == item.id })
        XCTAssertEqual(state.deletedMediaName, item.name)
        XCTAssertNil(state.lastError)
    }

    func testLanguageSelectionPersistsWithoutChangingPasswordState() {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AppLanguageStore(defaults: defaults)

        store.select(.traditionalChinese)

        XCTAssertEqual(store.selection, .traditionalChinese)
        XCTAssertEqual(defaults.string(forKey: AppLanguageStore.defaultsKey), AppLanguage.traditionalChinese.rawValue)
        XCTAssertTrue(store.locale.identifier.lowercased().contains("zh"))
    }
}
