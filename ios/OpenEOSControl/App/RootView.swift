import SwiftUI
import UIKit

struct RootView: View {
    @EnvironmentObject private var camera: CameraAppState
    @Environment(\.scenePhase) private var scenePhase
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
        .safeAreaInset(edge: .bottom, spacing: 0) {
            ShutterReleaseRecoveryView()
        }
        .background(Color.cameraBackground.ignoresSafeArea())
        .sheet(item: $camera.activeSheet) { sheet in
            CameraSheetHost(sheet: sheet)
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    if camera.shutterReleaseRequired { ShutterReleaseRecoveryView() }
                }
                .presentationBackground(Color.cameraSurface)
        }
        .alert(
            Text("operation_failed"),
            isPresented: Binding(
                get: { camera.lastError != nil && !camera.shutterReleaseRequired },
                set: { if !$0 { camera.clearError() } }
            )
        ) {
            Button("dismiss", role: .cancel) { camera.clearError() }
        } message: {
            Text(camera.lastError ?? "")
        }
        .onChange(of: camera.shutterReleaseRequired) { _, required in
            if required { camera.activeSheet = nil }
        }
        .onAppear {
            UIDevice.current.beginGeneratingDeviceOrientationNotifications()
            updateControlRotation()
            camera.setApplicationActive(scenePhase == .active)
        }
        .onChange(of: scenePhase) { _, phase in camera.setApplicationActive(phase == .active) }
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

// The safety action lives outside the camera HUD, capture mode and capability panels.
// A bounded scrolling explanation leaves the Stop button reachable at accessibility sizes.
private struct ShutterReleaseRecoveryView: View {
    @EnvironmentObject private var camera: CameraAppState
    @State private var confirmingPreviousRelease = false
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    var body: some View {
        if camera.shutterReleaseRequired || camera.previousShutterReleaseUnconfirmed {
            VStack(spacing: 8) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        if camera.shutterReleaseRequired {
                            Text(LocalizedStringKey(camera.shutterReleaseUnconfirmed ? "shutter_release_unconfirmed" : "bulb_exposure_running"))
                                .font(.headline)
                                .accessibilityIdentifier("shutter-release-warning")
                            if camera.shutterReleaseUnconfirmed {
                                Text("shutter_release_recovery_help")
                                    .font(.subheadline)
                            }
                        }
                        if camera.previousShutterReleaseUnconfirmed {
                            Text("previous_shutter_release_warning")
                                .font(.subheadline)
                                .accessibilityIdentifier("previous-shutter-release-warning")
                            Button("confirm_previous_shutter_released") {
                                confirmingPreviousRelease = true
                            }
                            .buttonStyle(.bordered)
                            .accessibilityIdentifier("confirm-previous-shutter-release-button")
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: verticalSizeClass == .compact ? 72 : 130)
                .accessibilityIdentifier("shutter-recovery-details")
                if camera.shutterReleaseRequired {
                    Button {
                        Task { await camera.retryShutterRelease() }
                    } label: {
                        Label("release_shutter_now", systemImage: "stop.fill")
                            .font(.headline)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.cameraWarning)
                    .foregroundStyle(Color.cameraBackground)
                    .disabled(!camera.canRetryShutterRelease)
                    .accessibilityIdentifier("release-shutter-button")
                }
            }
            .padding(12)
            .foregroundStyle(Color.cameraWarning)
            .background(Color.cameraSurface)
            .confirmationDialog("confirm_previous_shutter_released", isPresented: $confirmingPreviousRelease) {
                Button("previous_shutter_physically_verified") { camera.confirmPreviousShutterReleased() }
            } message: {
                Text("previous_shutter_confirmation_help")
            }
        }
    }
}
