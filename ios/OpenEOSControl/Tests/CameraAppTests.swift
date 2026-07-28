import CoreGraphics
import Foundation
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

    func testCameraRTPAddressRequiresTheSameIPv4Subnet() {
        XCTAssertTrue(
            CameraRTPNetworkAddress.sameIPv4Subnet(
                cameraAddress: "192.168.1.2",
                localAddress: "192.168.1.37",
                netmask: "255.255.255.0"
            )
        )
        XCTAssertFalse(
            CameraRTPNetworkAddress.sameIPv4Subnet(
                cameraAddress: "192.168.1.2",
                localAddress: "192.168.2.37",
                netmask: "255.255.255.0"
            )
        )
        XCTAssertFalse(
            CameraRTPNetworkAddress.sameIPv4Subnet(
                cameraAddress: "camera.local",
                localAddress: "192.168.1.37",
                netmask: "255.255.255.0"
            )
        )
    }

    func testAdvancedSettingsAreFilteredByCaptureMode() {
        let settings = [
            CameraSetting(key: "iso", label: "ISO", value: "100", values: ["100"]),
            CameraSetting(key: "drivemode", label: "Drive", value: "single", values: ["single"]),
            CameraSetting(key: "moviequality", label: "Movie", value: "4K", values: ["4K"]),
            CameraSetting(key: "meteringmode", label: "Metering", value: "eval", values: ["eval"]),
            CameraSetting(
                key: "capturetarget",
                label: "Capture target",
                value: "Internal RAM",
                values: ["Internal RAM", "Memory card"]
            ),
        ]

        XCTAssertEqual(
            advancedSettingsForMode(settings, mode: .photo).map(\.key),
            ["drivemode", "meteringmode", "capturetarget"]
        )
        XCTAssertEqual(advancedSettingsForMode(settings, mode: .video).map(\.key), ["moviequality", "meteringmode"])
    }

    func testR6AdvancedSettingLocalizationKeepsProtocolValuesSeparate() {
        let expectedLabels = [
            "whitebalanceadjusta": "setting_white_balance_shift_a",
            "whitebalanceadjustb": "setting_white_balance_shift_b",
            "wbshift.ba": "setting_white_balance_shift_ba",
            "wbshift.mg": "setting_white_balance_shift_mg",
            "aspectratio": "setting_aspect_ratio",
            "zoomspeed": "setting_power_zoom_speed",
            "autopoweroff": "setting_auto_power_off",
            "capturetarget": "setting_capture_target",
            "stillimagequality.raw": "setting_image_quality_raw",
            "stillimagequality.jpeg": "setting_image_quality_jpeg",
            "stillimagequalitysd": "setting_image_quality_sd",
            "stillimagequalitycf": "setting_image_quality_cf",
        ]
        for (key, localizationKey) in expectedLabels {
            XCTAssertEqual(settingLabelLocalizationKey(key), localizationKey)
        }

        XCTAssertEqual(
            settingValueLocalizationKey(key: "autopoweroff", value: "1800"),
            "camera_value_30_minutes"
        )
        XCTAssertNil(settingValueLocalizationKey(key: "autopoweroff", value: "4294967295"))
        XCTAssertEqual(
            settingValueLocalizationKey(key: "capturetarget", value: "Internal RAM"),
            "camera_value_internal_ram"
        )
        XCTAssertEqual(
            settingValueLocalizationKey(key: "capturetarget", value: "Memory card"),
            "camera_value_memory_card"
        )
        XCTAssertEqual(
            settingValueLocalizationKey(key: "stillimagequalitycf", value: "cRAW + Large Fine JPEG"),
            "camera_value_craw_large_fine_jpeg"
        )
        XCTAssertEqual(
            settingValueLocalizationKey(key: "stillimagequality.jpeg", value: "large_fine"),
            "camera_value_large_fine"
        )
        XCTAssertEqual(settingValueLocalizationKey(key: "continuousaf", value: "Off"), "camera_value_off")
    }

    func testEnglishAndTraditionalChineseResourcesHaveMatchingKeys() throws {
        let projectRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let english = try String(
            contentsOf: projectRoot.appendingPathComponent("App/Resources/en.lproj/Localizable.strings"),
            encoding: .utf8
        )
        let traditionalChinese = try String(
            contentsOf: projectRoot.appendingPathComponent("App/Resources/zh-Hant.lproj/Localizable.strings"),
            encoding: .utf8
        )

        func resourceKeys(_ source: String) -> Set<String> {
            Set(source.split(separator: "\n").compactMap { line in
                guard line.first == "\"" else { return nil }
                let remainder = line.dropFirst()
                guard let closingQuote = remainder.firstIndex(of: "\"") else { return nil }
                return String(remainder[..<closingQuote])
            })
        }

        XCTAssertEqual(resourceKeys(english), resourceKeys(traditionalChinese))
    }

    func testOfflinePreviewHasRealisticCapabilityGating() {
        let snapshot = CameraAppState.makeOfflinePreviewSnapshot()

        XCTAssertEqual(snapshot.info.model, "Canon EOS R6 Mark III")
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.liveView))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.stillCapture))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaDownload))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaDelete))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.clickWhiteBalance))
        XCTAssertFalse(snapshot.capabilities.matrix.supports(.focusDrive))
        XCTAssertEqual(snapshot.capabilities.liveView.maximumFPS, 30)
    }

    func testOfflinePreviewClickWhiteBalanceUpdatesTheVisibleValue() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()
        state.liveViewTapAction = .whiteBalance

        await state.clickWhiteBalance(x: 0.4, y: 0.6)

        XCTAssertEqual(state.snapshot?.status.exposure.whiteBalance, "click")
        XCTAssertEqual(state.focusMarker, FocusMarker(x: 0.4, y: 0.6, accepted: true))
        XCTAssertNil(state.lastError)
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

    func testBridgePreferencesPersistWithoutPersistingBearerToken() {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)

        state.setConnectionMode(.desktopBridge)
        state.setBridgeURL("http://192.168.1.20:18181")
        state.bridgeToken = "memory-only-secret"

        let restored = CameraAppState(defaults: defaults)
        XCTAssertEqual(restored.connectionMode, .desktopBridge)
        XCTAssertEqual(restored.bridgeURL, "http://192.168.1.20:18181")
        XCTAssertTrue(restored.bridgeToken.isEmpty)
        XCTAssertFalse(restored.canConnect)
    }
}
