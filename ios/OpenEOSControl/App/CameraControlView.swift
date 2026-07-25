import OpenEOSCore
import SwiftUI

struct CameraControlView: View {
    @EnvironmentObject private var camera: CameraAppState
    let controlRotation: Double

    var body: some View {
        GeometryReader { proxy in
            if proxy.size.width > proxy.size.height {
                landscapeLayout(proxy)
            } else {
                portraitLayout(proxy)
            }
        }
        .background(Color.black.ignoresSafeArea())
        .simultaneousGesture(
            DragGesture(minimumDistance: 40).onEnded { gesture in
                if abs(gesture.translation.height) > abs(gesture.translation.width) {
                    withAnimation(.easeOut(duration: 0.18)) { camera.hudVisible.toggle() }
                }
            }
        )
    }

    private func portraitLayout(_ proxy: GeometryProxy) -> some View {
        ZStack {
            LiveViewSurface()
            if camera.hudVisible {
                VStack(spacing: 0) {
                    CameraOverlayHeader(controlRotation: controlRotation)
                        .padding(.top, proxy.safeAreaInsets.top + 4)
                        .padding(.horizontal, 10)
                    Spacer(minLength: 0)
                    PortraitControlPanel(controlRotation: controlRotation)
                        .padding(.bottom, proxy.safeAreaInsets.bottom)
                }
            } else {
                Button {
                    withAnimation { camera.hudVisible = true }
                } label: {
                    Image(systemName: "eye")
                        .accessibilityLabel(Text("show_hud"))
                }
                .buttonStyle(CameraIconButtonStyle())
                .position(
                    x: proxy.size.width - 34,
                    y: proxy.safeAreaInsets.top + 30
                )
            }
        }
    }

    private func landscapeLayout(_ proxy: GeometryProxy) -> some View {
        HStack(spacing: 0) {
            ZStack(alignment: .top) {
                LiveViewSurface()
                if camera.hudVisible {
                    CameraOverlayHeader(controlRotation: controlRotation)
                        .padding(.top, proxy.safeAreaInsets.top + 4)
                        .padding(.horizontal, 10)
                }
            }
            .frame(width: proxy.size.width * 0.7)

            if camera.hudVisible {
                LandscapeControlPanel(controlRotation: controlRotation)
                    .frame(width: proxy.size.width * 0.3)
                    .padding(.top, proxy.safeAreaInsets.top)
                    .padding(.bottom, proxy.safeAreaInsets.bottom)
                    .background(Color.cameraBackground.opacity(0.97))
            }
        }
    }
}

private struct CameraOverlayHeader: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let controlRotation: Double

    var body: some View {
        HStack(spacing: 7) {
            RotatingControl(degrees: controlRotation) {
                HStack(spacing: 7) {
                    Circle()
                        .fill(camera.isPreview ? Color.cameraWarning : Color.cameraStatus)
                        .frame(width: 8, height: 8)
                    Text(compactCameraName(camera.info?.model ?? "unknown"))
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 4)
            RotatingControl(degrees: controlRotation) {
                HStack(spacing: 4) {
                    Image(systemName: "battery.75percent")
                    Text(camera.status?.batteryLevel.map { "\($0)%" } ?? "--")
                }
                .font(.caption.weight(.semibold))
            }
            if let storageText {
                RotatingControl(degrees: controlRotation) {
                    HStack(spacing: 4) {
                        Image(systemName: "sdcard")
                        Text(storageText)
                    }
                    .font(.caption.weight(.semibold))
                }
            }
            if camera.supports(.autofocus) {
                Button {
                    Task { await camera.autofocus() }
                } label: {
                    RotatingControl(degrees: controlRotation) {
                        Image(systemName: "viewfinder")
                            .accessibilityLabel(Text("focus_with_shutter"))
                    }
                }
                .buttonStyle(CameraIconButtonStyle())
                .disabled(camera.isBusy(.focus))
            }
            Menu {
                Button {
                    camera.screen = .media
                    Task { await camera.loadMedia() }
                } label: {
                    Label("camera_media", systemImage: "photo.on.rectangle")
                }
                .disabled(!camera.supports(.mediaBrowser))
                Button {
                    camera.activeSheet = .focusDrive
                } label: {
                    Label("focus_drive", systemImage: "arrow.left.and.right")
                }
                .disabled(!camera.supports(.focusDrive))
                if camera.supports(.shutterHalfPress) {
                    Button {
                        Task { await camera.halfPressShutter() }
                    } label: {
                        Label("half_press_shutter", systemImage: "camera.aperture")
                    }
                    .disabled(camera.isBusy(.focus))
                }
                Button {
                    camera.screen = .debug
                } label: {
                    Label("debug", systemImage: "ladybug")
                }
                Button {
                    camera.activeSheet = .language
                } label: {
                    Label("language", systemImage: "globe")
                }
                Divider()
                Button(role: .destructive) {
                    Task { await camera.disconnect() }
                } label: {
                    Label("disconnect", systemImage: "xmark.circle")
                }
            } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "ellipsis")
                        .accessibilityLabel(Text("more_actions"))
                }
            }
            .buttonStyle(CameraIconButtonStyle())
            .accessibilityIdentifier("more-actions-button")
        }
        .padding(.leading, 11)
        .padding(.trailing, 4)
        .frame(height: 54)
        .foregroundStyle(Color.cameraText)
        .background(Color.black.opacity(0.66))
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private var storageText: String? {
        if let shots = camera.status?.storageFreeImages {
            let formatter = NumberFormatter()
            formatter.numberStyle = .decimal
            formatter.locale = language.locale
            let value = formatter.string(from: NSNumber(value: shots)) ?? String(shots)
            return language.format("storage_shots_format", value)
        }
        if let bytes = camera.status?.storageFreeBytes {
            return language.format(
                "storage_free_format",
                ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
            )
        }
        return camera.status?.mediaAvailable == true ? language.string("storage_ready") : nil
    }

    private func compactCameraName(_ value: String) -> String {
        value.hasPrefix("Canon EOS ") ? String(value.dropFirst("Canon EOS ".count)) : value
    }
}

private struct PortraitControlPanel: View {
    @EnvironmentObject private var camera: CameraAppState
    let controlRotation: Double

    var body: some View {
        VStack(spacing: 0) {
            CaptureModePicker()
                .padding(.horizontal, 14)
                .padding(.top, 8)
            ExposureStrip(controlRotation: controlRotation)
            CaptureBar(controlRotation: controlRotation)
        }
        .background(Color.cameraBackground.opacity(0.94))
    }
}

private struct LandscapeControlPanel: View {
    @EnvironmentObject private var camera: CameraAppState
    let controlRotation: Double

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                CaptureModePicker()
                ExposureStrip(controlRotation: controlRotation, vertical: true)
                CaptureBar(controlRotation: controlRotation, compact: true)
            }
            .padding(8)
        }
    }
}

private struct CaptureModePicker: View {
    @EnvironmentObject private var camera: CameraAppState

    var body: some View {
        Picker("capture_mode", selection: $camera.captureMode) {
            Text("photo").tag(AppCaptureMode.photo)
            Text("video").tag(AppCaptureMode.video)
        }
        .pickerStyle(.segmented)
        .frame(height: 38)
        .accessibilityIdentifier("capture-mode-picker")
    }
}

private struct ExposureStrip: View {
    @EnvironmentObject private var camera: CameraAppState
    let controlRotation: Double
    var vertical = false

    var body: some View {
        Group {
            if vertical {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 4) {
                    exposureButtons
                }
            } else {
                HStack(spacing: 2) { exposureButtons }
            }
        }
        .padding(.horizontal, vertical ? 0 : 8)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private var exposureButtons: some View {
        ExposureButton(label: "iso", value: camera.status?.exposure.iso ?? "--", sheet: .iso, rotation: controlRotation)
        ExposureButton(label: "shutter", value: camera.status?.exposure.shutter ?? "--", sheet: .shutter, rotation: controlRotation)
        ExposureButton(label: "aperture", value: camera.status?.exposure.aperture ?? "--", sheet: .aperture, rotation: controlRotation)
        ExposureButton(label: "white_balance", value: camera.status?.exposure.whiteBalance ?? "--", sheet: .whiteBalance, rotation: controlRotation)
    }
}

private struct ExposureButton: View {
    @EnvironmentObject private var camera: CameraAppState
    let label: LocalizedStringKey
    let value: String
    let sheet: CameraSheet
    let rotation: Double

    var body: some View {
        Button {
            camera.activeSheet = sheet
        } label: {
            RotatingControl(degrees: rotation) {
                VStack(spacing: 2) {
                    Text(label)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Color.cameraSecondaryText)
                    Text(value)
                        .font(.callout.weight(.bold))
                        .foregroundStyle(Color.cameraText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                .frame(maxWidth: .infinity, minHeight: 50)
            }
        }
        .buttonStyle(.plain)
        .background(Color.cameraSurface)
        .clipShape(RoundedRectangle(cornerRadius: 4))
        .disabled(setting(for: sheet)?.values.isEmpty != false)
        .opacity(setting(for: sheet)?.values.isEmpty == false ? 1 : 0.45)
        .accessibilityIdentifier("exposure-\(sheet.rawValue)")
    }

    private func setting(for sheet: CameraSheet) -> CameraSetting? {
        let key: String
        switch sheet {
        case .iso: key = "iso"
        case .shutter: key = "shutter"
        case .aperture: key = "aperture"
        case .whiteBalance: key = "whitebalance"
        default: return nil
        }
        return camera.capabilities?.setting(key)
    }
}

private struct CaptureBar: View {
    @EnvironmentObject private var camera: CameraAppState
    let controlRotation: Double
    var compact = false

    var body: some View {
        VStack(spacing: 3) {
            HStack(spacing: 12) {
                Button {
                    camera.activeSheet = .more
                } label: {
                    RotatingControl(degrees: controlRotation) {
                        Image(systemName: "slider.horizontal.3")
                            .accessibilityLabel(Text("more_settings"))
                    }
                }
                .buttonStyle(CameraIconButtonStyle())

                Spacer(minLength: 0)

                captureButton

                Spacer(minLength: 0)

                Button {
                    camera.activeSheet = .liveView
                } label: {
                    RotatingControl(degrees: controlRotation) {
                        VStack(spacing: 0) {
                            Text("\(camera.requestedFPS)").font(.callout.bold())
                            Text("FPS").font(.system(size: 9, weight: .bold))
                        }
                        .accessibilityLabel(Text("live_view_settings"))
                    }
                }
                .buttonStyle(CameraIconButtonStyle())
            }
            .frame(minHeight: compact ? 72 : 84)
            .padding(.horizontal, compact ? 0 : 18)

            if !captureSupported {
                Text(LocalizedStringKey(camera.captureMode == .photo ? "capture_not_supported" : "recording_not_supported"))
                    .font(.caption)
                    .foregroundStyle(Color.cameraWarning)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 8)
                    .padding(.bottom, 6)
            }
        }
    }

    private var captureSupported: Bool {
        camera.supports(camera.captureMode == .photo ? .stillCapture : .videoRecording)
    }

    @ViewBuilder
    private var captureButton: some View {
        Button {
            Task {
                if camera.captureMode == .photo {
                    await camera.captureStill()
                } else {
                    await camera.toggleRecording()
                }
            }
        } label: {
            RotatingControl(degrees: controlRotation) {
                ZStack {
                    Circle()
                        .stroke(Color.cameraText, lineWidth: 4)
                        .frame(width: compact ? 62 : 72, height: compact ? 62 : 72)
                    if camera.captureMode == .video {
                        RoundedRectangle(cornerRadius: camera.recording ? 5 : 25)
                            .fill(Color.cameraRecording)
                            .frame(width: camera.recording ? 28 : 54, height: camera.recording ? 28 : 54)
                    } else {
                        Circle()
                            .fill(Color.cameraText)
                            .frame(width: compact ? 49 : 58, height: compact ? 49 : 58)
                    }
                    if camera.isBusy(camera.captureMode == .photo ? .capture : .recording) {
                        ProgressView().tint(camera.captureMode == .photo ? Color.cameraBackground : Color.cameraText)
                    }
                }
                .accessibilityLabel(
                    Text(
                        LocalizedStringKey(
                            camera.captureMode == .photo ? "capture_photo" : camera.recording ? "stop_recording" : "start_recording"
                        )
                    )
                )
            }
        }
        .buttonStyle(.plain)
        .disabled(!captureSupported || camera.isBusy(camera.captureMode == .photo ? .capture : .recording))
        .opacity(captureSupported ? 1 : 0.38)
        .accessibilityIdentifier(camera.captureMode == .photo ? "shutter-button" : "record-button")
    }
}
