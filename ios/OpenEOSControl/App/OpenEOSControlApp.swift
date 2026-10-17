import OpenEOSCore
import SwiftUI
import UIKit

@main
struct OpenEOSControlApp: App {
    @StateObject private var camera: CameraAppState
    @StateObject private var language: AppLanguageStore

    @MainActor
    init() {
        let language = AppLanguageStore()
        _language = StateObject(wrappedValue: language)
        let deliveryStore = Self.makeDeliveryStore()
        #if DEBUG
        if ProcessInfo.processInfo.environment["OEC_DELETE_JOURNEY_FIXTURE"] == "1" {
            let transport = BridgeDeleteJourneyFixture()
            _camera = StateObject(wrappedValue: CameraAppState(language: language, sessionFactory: {
                .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
            }, mediaDeliveryStore: deliveryStore))
        } else if let scenario = ProcessInfo.processInfo.environment["OEC_UPLOAD_JOURNEY_FIXTURE"],
           ["success", "cancel", "lost-ack"].contains(scenario) {
            let transport = BridgeUploadJourneyFixture(mode: scenario)
            _ = try? BridgeUploadJourneyFixture.seedPickerFile()
            _camera = StateObject(wrappedValue: CameraAppState(language: language, sessionFactory: {
                .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
            }, mediaDeliveryStore: deliveryStore))
        } else if let scenario = ProcessInfo.processInfo.environment["OEC_SHUTTER_RECOVERY_FIXTURE"],
           ["unknown", "active", "previous-and-current", "pending-start", "pending-start-lost-stop"].contains(scenario) {
            let transport = BridgeShutterRecoveryFixture(
                unknown: scenario == "unknown", failFirstClose: scenario == "previous-and-current",
                pendingStart: scenario.hasPrefix("pending-start"), failFirstStop: scenario == "pending-start-lost-stop"
            )
            _camera = StateObject(wrappedValue: CameraAppState(language: language, sessionFactory: {
                .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport))
            }, mediaDeliveryStore: deliveryStore))
        } else {
            _camera = StateObject(wrappedValue: CameraAppState(language: language, mediaDeliveryStore: deliveryStore))
        }
        #else
        _camera = StateObject(wrappedValue: CameraAppState(language: language, mediaDeliveryStore: deliveryStore))
        #endif
        #if DEBUG
        if CommandLine.arguments.contains("-disableAnimations") {
            UIView.setAnimationsEnabled(false)
        }
        #endif
    }

    private static func makeDeliveryStore() -> MediaDeliveryStore? {
        do {
            let support = try FileManager.default.url(for: .applicationSupportDirectory,
                in: .userDomainMask, appropriateFor: nil, create: true).resolvingSymlinksInPath()
            let parent = support.appendingPathComponent("OpenEOSControl", isDirectory: true)
            try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
            let values = try parent.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values.isDirectory == true, values.isSymbolicLink != true else { return nil }
            return MediaDeliveryStore(root: parent.appendingPathComponent("PreparedMedia", isDirectory: true))
        } catch {
            // History is optional; connection and immediate downloads stay usable.
            return nil
        }
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
