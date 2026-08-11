import SwiftUI
import UIKit

@main
struct OpenEOSControlApp: App {
    @StateObject private var camera = CameraAppState()
    @StateObject private var language = AppLanguageStore()

    @MainActor
    init() {
        #if DEBUG
        if CommandLine.arguments.contains("-disableAnimations") {
            UIView.setAnimationsEnabled(false)
        }
        #endif
    }

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
