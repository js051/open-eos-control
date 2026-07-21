import SwiftUI
import UIKit

struct RootView: View {
    @EnvironmentObject private var camera: CameraAppState
    @State private var controlRotation = 0.0

    var body: some View {
        Group {
            if !camera.connected {
                ConnectionView()
            } else {
                switch camera.screen {
                case .control:
                    CameraControlView(controlRotation: controlRotation)
                case .media:
                    MediaView(controlRotation: controlRotation)
                case .debug:
                    DebugView(controlRotation: controlRotation)
                }
            }
        }
        .background(Color.cameraBackground.ignoresSafeArea())
        .sheet(item: $camera.activeSheet) { sheet in
            CameraSheetHost(sheet: sheet)
                .presentationBackground(Color.cameraSurface)
        }
        .alert(
            Text("operation_failed"),
            isPresented: Binding(
                get: { camera.lastError != nil },
                set: { if !$0 { camera.clearError() } }
            )
        ) {
            Button("dismiss", role: .cancel) { camera.clearError() }
        } message: {
            Text(camera.lastError ?? "")
        }
        .onAppear {
            UIDevice.current.beginGeneratingDeviceOrientationNotifications()
            updateControlRotation()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
            updateControlRotation()
        }
        .onDisappear {
            UIDevice.current.endGeneratingDeviceOrientationNotifications()
        }
    }

    private func updateControlRotation() {
        switch UIDevice.current.orientation {
        case .portraitUpsideDown:
            controlRotation = 180
        case .portrait, .landscapeLeft, .landscapeRight:
            controlRotation = 0
        default:
            break
        }
    }
}
