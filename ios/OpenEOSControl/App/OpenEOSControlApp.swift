import SwiftUI

@main
struct OpenEOSControlApp: App {
    @StateObject private var camera = CameraAppState()
    @StateObject private var language = AppLanguageStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(camera)
                .environmentObject(language)
                .environment(\.locale, language.locale)
                .preferredColorScheme(.dark)
        }
    }
}
