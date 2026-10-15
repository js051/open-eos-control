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
            VStack(spacing: 0) {
                ForegroundMediaBanner(controller: camera.foregroundMedia, delivery: camera.foregroundFiles)
                ShutterReleaseRecoveryView()
                if !camera.mediaDeleteRuns.isEmpty && (!camera.connected || camera.screen != .media) {
                    Button("media_delete_view_results") { camera.activeSheet = .deleteHistory }
                        .accessibilityIdentifier("media-delete-history-banner")
                }
            }
        }
        .background(Color.cameraBackground.ignoresSafeArea())
        .sheet(item: $camera.activeSheet) { sheet in
            // A NavigationStack footer can ignore an outer safe-area inset.
            // Reserve actual sibling space so Disconnect cannot sit under Stop.
            VStack(spacing: 0) {
                CameraSheetHost(sheet: sheet)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                if sheet != .foregroundImport {
                    ForegroundMediaBanner(controller: camera.foregroundMedia, delivery: camera.foregroundFiles)
                }
                if camera.shutterReleaseRequired {
                    ShutterReleaseRecoveryView()
                        .layoutPriority(1)
                }
            }
            .presentationBackground(Color.cameraSurface)
        }
        .alert(
            Text("operation_failed"),
            isPresented: Binding(
                get: { camera.lastError != nil && camera.mediaPreviewItem == nil && !camera.shutterReleaseRequired },
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
        .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange)) { _ in
            camera.refreshMediaDateDisplayZone()
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

// The safety action lives outside the camera HUD, capture mode and capability panels.
// A bounded scrolling explanation leaves the Stop button reachable at accessibility sizes.
private struct ShutterReleaseRecoveryView: View {
    @EnvironmentObject private var camera: CameraAppState
    @State private var confirmingPreviousRelease = false
    @State private var confirmingPreviousRecording = false
    @State private var confirmingPreviousLiveView = false
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    var body: some View {
        if camera.shutterReleaseRequired || camera.previousShutterReleaseUnconfirmed || !camera.previousRecordingWarnings.isEmpty || camera.liveViewStopState.stopUnconfirmed || !camera.previousLiveViewWarnings.isEmpty {
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
                        if camera.liveViewStopState.stopUnconfirmed {
                            Text("live_view_stop_unconfirmed")
                                .font(.headline)
                                .accessibilityIdentifier("live-view-stop-warning")
                        }
                        if !camera.previousLiveViewWarnings.isEmpty {
                            Text("previous_live_view_stop_warning")
                                .font(.subheadline)
                                .accessibilityIdentifier("previous-live-view-stop-warning")
                            Button("confirm_previous_live_view_stopped") { confirmingPreviousLiveView = true }
                                .buttonStyle(.bordered)
                        }
                        if !camera.previousRecordingWarnings.isEmpty {
                            Text("previous_recording_warning")
                                .font(.subheadline)
                                .accessibilityIdentifier("previous-recording-warning")
                            Button("confirm_previous_recording_stopped") {
                                confirmingPreviousRecording = true
                            }
                            .buttonStyle(.bordered)
                            .accessibilityIdentifier("confirm-previous-recording-button")
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
                if camera.liveViewStopState.stopUnconfirmed {
                    Button("retry_live_view_stop") { Task { await camera.retryLiveViewStop() } }
                        .buttonStyle(.borderedProminent)
                        .disabled(!camera.canRetryLiveViewStop)
                        .accessibilityIdentifier("retry-live-view-stop-button")
                }
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
            .confirmationDialog("confirm_previous_live_view_stopped", isPresented: $confirmingPreviousLiveView) {
                Button("previous_live_view_physically_verified") { camera.confirmPreviousLiveViewStopped() }
            } message: {
                Text("previous_live_view_confirmation_help")
            }
            .confirmationDialog("confirm_previous_recording_stopped", isPresented: $confirmingPreviousRecording) {
                Button("previous_recording_physically_verified") { camera.confirmPreviousRecordingStopped() }
            } message: {
                Text("previous_recording_confirmation_help")
            }
            .confirmationDialog("confirm_previous_shutter_released", isPresented: $confirmingPreviousRelease) {
                Button("previous_shutter_physically_verified") { camera.confirmPreviousShutterReleased() }
            } message: {
                Text("previous_shutter_confirmation_help")
            }
        }
    }
}
