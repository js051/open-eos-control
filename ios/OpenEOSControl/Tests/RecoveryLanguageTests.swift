import Foundation
import XCTest

@testable import OpenEOSControl

/// App state/resource tests only. These do not run a camera or a native alert.
@MainActor
final class RecoveryLanguageTests: XCTestCase {
    func testNewRecoveryErrorsFollowChangesOnTheSameSharedLanguageStore() throws {
        let defaults = makeDefaults()
        let language = AppLanguageStore(defaults: defaults)
        let state = CameraAppState(defaults: defaults, language: language)
        state.openOfflinePreview()
        state.screen = .debug
        let model = state.info?.model
        for choice in [AppLanguage.english, .traditionalChinese, .english] {
            let precedingError = state.lastError
            language.select(choice)
            // A language preference cannot retire the connection, clear history,
            // or synthesize a new error owner. New emissions use its current value.
            XCTAssertEqual(state.lastError, precedingError)
            XCTAssertTrue(state.isPreview)
            XCTAssertEqual(state.info?.model, model)
            XCTAssertEqual(state.screen, .debug)
            state.reportCubeLutRenderFailure()
            XCTAssertEqual(state.lastError, try localized("lut_render_failed", choice: choice))
            XCTAssertEqual(state.lastError, language.string("lut_render_failed"))
        }
        XCTAssertNotEqual(try localized("lut_render_failed", choice: .english),
                          try localized("lut_render_failed", choice: .traditionalChinese))
    }

    func testLocalizedImportTemplatePreservesRawErrorTextAndCancellationDoesNotReplaceIt() throws {
        let defaults = makeDefaults(), raw = "SYNTHETIC raw device/server text: recording_command_rejected"
        let language = AppLanguageStore(defaults: defaults)
        let state = CameraAppState(defaults: defaults, language: language)
        for choice in [AppLanguage.traditionalChinese, .english] {
            language.select(choice)
            state.reportCubeLutImportError(SyntheticRecoveryError(raw: raw))
            let expected = String(format: try localized("lut_import_failed", choice: choice),
                                  locale: choice.locale, arguments: [raw])
            XCTAssertEqual(state.cubeLutImportError, expected)
            XCTAssertEqual(state.lastError, expected)
            XCTAssertTrue(state.lastError?.contains(raw) == true, "Raw diagnostics must not be translated as keys")
            state.reportCubeLutImportError(CancellationError())
            XCTAssertEqual(state.lastError, expected)
        }
    }

    func testDefaultStateUsesSavedAppLanguageWithoutChangingGlobalLocale() throws {
        let defaults = makeDefaults()
        defaults.set(AppLanguage.traditionalChinese.rawValue, forKey: AppLanguageStore.defaultsKey)
        let state = CameraAppState(defaults: defaults)
        state.reportCubeLutRenderFailure()
        XCTAssertEqual(state.lastError, try localized("lut_render_failed", choice: .traditionalChinese))
    }

    func testSystemSelectionRetainsExistingBundleFallback() {
        let defaults = makeDefaults()
        let language = AppLanguageStore(defaults: defaults)
        let state = CameraAppState(defaults: defaults, language: language)
        language.select(.system)
        state.reportCubeLutRenderFailure()
        XCTAssertEqual(state.lastError, Bundle.main.localizedString(forKey: "lut_render_failed", value: "lut_render_failed", table: nil))
    }

    func testRecordingRecoveryTemplatesAndNestedEvidenceUseTheSameSelectedLanguage() throws {
        let language = AppLanguageStore(defaults: makeDefaults())
        for choice in [AppLanguage.english, .traditionalChinese] {
            language.select(choice)
            let response = language.format("recording_original_response", language.string("start_recording"),
                                           language.string("recording_response_unconfirmed"))
            let expected = String(format: try localized("recording_original_response", choice: choice),
                locale: choice.locale, arguments: [try localized("start_recording", choice: choice),
                                                   try localized("recording_response_unconfirmed", choice: choice)])
            XCTAssertEqual(response, expected)
            let observation = language.format("recording_current_observation", language.string("recording_observed_unavailable"))
            XCTAssertEqual(observation, String(format: try localized("recording_current_observation", choice: choice),
                locale: choice.locale, arguments: [try localized("recording_observed_unavailable", choice: choice)]))
        }
    }

    private func makeDefaults() -> UserDefaults {
        let suite = "RecoveryLanguageTests.\(UUID().uuidString)"
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
}

private struct SyntheticRecoveryError: LocalizedError {
    let raw: String
    var errorDescription: String? { raw }
}
