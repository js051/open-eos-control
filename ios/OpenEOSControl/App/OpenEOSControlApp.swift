import OpenEOSCore
import SwiftUI
import UIKit

@main
struct OpenEOSControlApp: App {
    @StateObject private var camera: CameraAppState
    @StateObject private var language = AppLanguageStore()

    @MainActor
    init() {
        #if DEBUG
        if let scenario = ProcessInfo.processInfo.environment["OEC_SHUTTER_RECOVERY_FIXTURE"],
           ["unknown", "active", "previous-and-current", "pending-start", "pending-start-lost-stop"].contains(scenario) {
            let transport = BridgeShutterRecoveryFixture(
                unknown: scenario == "unknown", failFirstClose: scenario == "previous-and-current",
                pendingStart: scenario.hasPrefix("pending-start"), failFirstStop: scenario == "pending-start-lost-stop"
            )
            _camera = StateObject(wrappedValue: CameraAppState(sessionFactory: {
                .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
            }))
        } else {
            _camera = StateObject(wrappedValue: CameraAppState())
        }
        #else
        _camera = StateObject(wrappedValue: CameraAppState())
        #endif
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
