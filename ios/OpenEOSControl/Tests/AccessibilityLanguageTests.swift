import Foundation
import OpenEOSCore
import SwiftUI
import UIKit
import XCTest

@testable import OpenEOSControl

/// Hosted UIKit labels and localization resources only, not a VoiceOver session.
@MainActor
final class AccessibilityLanguageTests: XCTestCase {
    func testHeldAutofocusRepresentableUpdatesTheSameButtonForCurrentLanguage() async throws {
        let language = AppLanguageStore(defaults: makeDefaults())
        let state = CameraAppState(defaults: makeDefaults(), language: language)
        let host = UIHostingController(rootView: HeldAutofocusButton(camera: state).environmentObject(language))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 320, height: 120)
        window.rootViewController = host
        window.isHidden = false
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        host.loadViewIfNeeded()
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        try await waitUntil { self.focusButton(in: host.view) != nil }
        let button = try XCTUnwrap(focusButton(in: host.view))
        let initialLabel = language.string("held_autofocus")
        try await waitUntil { button.accessibilityLabel == initialLabel && !button.isEnabled }
        let enabled = button.isEnabled

        for choice in [AppLanguage.english, .traditionalChinese, .english] {
            let label = try localized("held_autofocus", choice: choice)
            let value = try localized("held_autofocus_released", choice: choice)
            let hint = try localized("held_autofocus_hint", choice: choice)
            language.select(choice)
            try await waitUntil {
                button.accessibilityLabel == label && button.accessibilityValue == value
                    && button.accessibilityHint == hint
            }
            XCTAssertTrue(focusButton(in: host.view) === button, "A preference update must not replace the held-touch control")
            XCTAssertEqual(button.isEnabled, enabled)
            XCTAssertFalse(button.isSelected)
            XCTAssertFalse(state.heldAutofocusPressed)
            XCTAssertEqual(button.accessibilityIdentifier, "held-autofocus-button")
        }
    }

    func testHeldAFAdmissionCannotBeOverriddenByExternalEnablement() {
        let button = AccessibleFocusButton(type: .system)
        var activations = 0
        button.activation = { activations += 1 }
        button.setControlAdmission(false, environmentEnabled: true)
        button.isEnabled = true
        XCTAssertFalse(button.isEnabled)
        XCTAssertFalse(button.accessibilityActivate())
        XCTAssertEqual(activations, 0)
        button.setControlAdmission(true, environmentEnabled: true)
        XCTAssertTrue(button.isEnabled)
        XCTAssertTrue(button.accessibilityActivate())
        XCTAssertEqual(activations, 1)
        button.isEnabled = false
        XCTAssertFalse(button.isEnabled)
        button.setControlAdmission(true, environmentEnabled: false)
        button.isEnabled = true
        XCTAssertFalse(button.isEnabled)
        button.setControlAdmission(true, environmentEnabled: true)
        XCTAssertTrue(button.isEnabled)
        button.setControlAdmission(false, environmentEnabled: true)
        XCTAssertFalse(button.isEnabled)
        button.isEnabled = true
        XCTAssertFalse(button.isEnabled)
    }

    func testMagnificationAndAllCameraFocusStatusesResolveInBothSelectedBundles() throws {
        let language = AppLanguageStore(defaults: makeDefaults())
        for choice in [AppLanguage.english, .traditionalChinese] {
            language.select(choice)
            XCTAssertEqual(language.format("live_view_magnify_to", 5),
                           String(format: try localized("live_view_magnify_to", choice: choice),
                                  locale: choice.locale, arguments: [5]))
            for status in CameraFocusStatus.allCases {
                let key = "camera_focus_" + status.rawValue.lowercased()
                XCTAssertEqual(language.string(key), try localized(key, choice: choice))
            }
            XCTAssertEqual(language.string("held_autofocus_active"), try localized("held_autofocus_active", choice: choice))
        }
    }

    private func focusButton(in view: UIView) -> AccessibleFocusButton? {
        if let button = view as? AccessibleFocusButton { return button }
        for child in view.subviews {
            if let button = focusButton(in: child) { return button }
        }
        return nil
    }

    private func waitUntil(_ predicate: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(3)
        while !predicate() {
            guard Date() < deadline else {
                XCTFail("Hosted accessibility labels did not reach the selected language")
                throw LabelUpdateTimeout()
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    private func makeDefaults() -> UserDefaults {
        let suite = "AccessibilityLanguageTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        return defaults
    }

    private func localized(_ key: String, choice: AppLanguage) throws -> String {
        let code = choice == .traditionalChinese ? "zh-Hant" : "en"
        let path = try XCTUnwrap(Bundle(for: CameraAppState.self).path(forResource: code, ofType: "lproj"))
        let bundle = try XCTUnwrap(Bundle(path: path))
        let value = bundle.localizedString(forKey: key, value: key, table: nil)
        XCTAssertNotEqual(value, key)
        return value
    }

    private struct LabelUpdateTimeout: Error {}
}
