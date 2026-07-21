import Foundation

enum AppLanguage: String, CaseIterable, Identifiable {
    case system
    case english
    case traditionalChinese

    var id: String { rawValue }

    var locale: Locale {
        switch self {
        case .system: .autoupdatingCurrent
        case .english: Locale(identifier: "en")
        case .traditionalChinese: Locale(identifier: "zh-Hant-TW")
        }
    }
}

@MainActor
final class AppLanguageStore: ObservableObject {
    static let defaultsKey = "app-language"

    @Published private(set) var selection: AppLanguage
    private let defaults: UserDefaults

    var locale: Locale { selection.locale }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if CommandLine.arguments.contains("-resetState") {
            defaults.removeObject(forKey: Self.defaultsKey)
        }
        selection = defaults.string(forKey: Self.defaultsKey).flatMap(AppLanguage.init(rawValue:)) ?? .system
    }

    func select(_ language: AppLanguage) {
        selection = language
        defaults.set(language.rawValue, forKey: Self.defaultsKey)
    }

    func string(_ key: String) -> String {
        localizedBundle.localizedString(forKey: key, value: key, table: nil)
    }

    func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: string(key), locale: locale, arguments: arguments)
    }

    private var localizedBundle: Bundle {
        let languageCode: String?
        switch selection {
        case .system: languageCode = nil
        case .english: languageCode = "en"
        case .traditionalChinese: languageCode = "zh-Hant"
        }
        guard let languageCode,
              let path = Bundle.main.path(forResource: languageCode, ofType: "lproj"),
              let bundle = Bundle(path: path) else {
            return .main
        }
        return bundle
    }
}
