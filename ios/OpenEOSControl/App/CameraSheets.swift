import OpenEOSCore
import SwiftUI

struct CameraSheetHost: View {
    let sheet: CameraSheet

    var body: some View {
        switch sheet {
        case .iso, .shutter, .aperture, .whiteBalance:
            ExposureSettingsView(selectedSheet: sheet)
        case .liveView:
            LiveViewSettingsView()
        case .more:
            MoreSettingsView()
        case .focusDrive:
            FocusDriveView()
        case .language:
            LanguageSettingsView()
        }
    }
}

private struct ExposureSettingsView: View {
    @EnvironmentObject private var camera: CameraAppState
    @Environment(\.dismiss) private var dismiss
    let selectedSheet: CameraSheet

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                exposureTabs
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                Divider().overlay(Color.cameraBorder)
                valueRail
            }
            .background(Color.cameraSurface)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("done") { dismiss() }
                }
            }
        }
        .presentationDetents([.height(270), .medium])
        .presentationDragIndicator(.visible)
    }

    private var exposureTabs: some View {
        HStack(spacing: 3) {
            tab("iso", .iso, key: "iso")
            tab("shutter", .shutter, key: "shutter")
            tab("aperture", .aperture, key: "aperture")
            tab("white_balance", .whiteBalance, key: "whitebalance")
        }
    }

    private func tab(_ label: LocalizedStringKey, _ sheet: CameraSheet, key: String) -> some View {
        Button {
            camera.activeSheet = sheet
        } label: {
            Text(label)
                .font(.caption.weight(.bold))
                .frame(maxWidth: .infinity, minHeight: 44)
                .foregroundStyle(selectedSheet == sheet ? Color.cameraBackground : Color.cameraText)
                .background(selectedSheet == sheet ? Color.cameraText : Color.cameraSurfaceRaised)
                .clipShape(RoundedRectangle(cornerRadius: 4))
        }
        .buttonStyle(.plain)
        .disabled(camera.capabilities?.setting(key)?.values.isEmpty != false)
        .opacity(camera.capabilities?.setting(key)?.values.isEmpty == false ? 1 : 0.42)
    }

    private var valueRail: some View {
        ScrollViewReader { reader in
            ScrollView(.horizontal) {
                LazyHStack(spacing: 8) {
                    ForEach(setting?.values ?? [], id: \.self) { value in
                        let selected = value == currentValue
                        Button {
                            Task { await camera.setSetting(key: key, value: value) }
                        } label: {
                            VStack(spacing: 7) {
                                Text(value)
                                    .font(.headline)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.68)
                                Capsule()
                                    .fill(selected ? Color.cameraAccent : Color.clear)
                                    .frame(width: 24, height: 3)
                            }
                            .frame(width: 92, height: 78)
                            .foregroundStyle(selected ? Color.cameraText : Color.cameraSecondaryText)
                            .background(selected ? Color.cameraSurfaceRaised : Color.clear)
                            .clipShape(RoundedRectangle(cornerRadius: 5))
                        }
                        .buttonStyle(.plain)
                        .disabled(camera.isBusy(.setting))
                        .id(value)
                        .accessibilityIdentifier("setting-value-\(value)")
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 20)
            }
            .overlay(alignment: .topTrailing) {
                if camera.isBusy(.setting) {
                    ProgressView().tint(Color.cameraAccent).padding(12)
                }
            }
            .onAppear {
                guard let currentValue else { return }
                DispatchQueue.main.async { reader.scrollTo(currentValue, anchor: .center) }
            }
        }
    }

    private var key: String {
        switch selectedSheet {
        case .iso: "iso"
        case .shutter: "shutter"
        case .aperture: "aperture"
        case .whiteBalance: "whitebalance"
        default: ""
        }
    }

    private var title: LocalizedStringKey {
        switch selectedSheet {
        case .iso: "iso"
        case .shutter: "shutter_speed"
        case .aperture: "aperture"
        case .whiteBalance: "white_balance"
        default: "more_settings"
        }
    }

    private var setting: CameraSetting? { camera.capabilities?.setting(key) }

    private var currentValue: String? {
        switch selectedSheet {
        case .iso: camera.status?.exposure.iso
        case .shutter: camera.status?.exposure.shutter
        case .aperture: camera.status?.exposure.aperture
        case .whiteBalance: camera.status?.exposure.whiteBalance
        default: nil
        }
    }
}

private struct FocusDriveView: View {
    @EnvironmentObject private var camera: CameraAppState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 18) {
                if !camera.autoRefresh {
                    Label("focus_drive_live_view_required", systemImage: "exclamationmark.triangle")
                        .font(.callout)
                        .foregroundStyle(Color.cameraWarning)
                }
                directionRow(.near, title: "focus_near", systemImage: "arrow.left")
                Divider().overlay(Color.cameraBorder)
                directionRow(.far, title: "focus_far", systemImage: "arrow.right")
                Spacer(minLength: 0)
            }
            .padding(20)
            .background(Color.cameraSurface)
            .navigationTitle(Text("focus_drive"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("done") { dismiss() }
                }
            }
        }
        .presentationDetents([.height(310), .medium])
        .presentationDragIndicator(.visible)
    }

    private func directionRow(
        _ direction: FocusDriveDirection,
        title: LocalizedStringKey,
        systemImage: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: systemImage)
                .font(.headline)
                .foregroundStyle(Color.cameraText)
            HStack(spacing: 8) {
                focusButton(direction: direction, step: .small, title: "step_small")
                focusButton(direction: direction, step: .medium, title: "step_medium")
                focusButton(direction: direction, step: .large, title: "step_large")
            }
        }
    }

    private func focusButton(
        direction: FocusDriveDirection,
        step: FocusDriveStep,
        title: LocalizedStringKey
    ) -> some View {
        Button {
            Task { await camera.driveFocus(direction: direction, step: step) }
        } label: {
            Text(title)
                .font(.callout.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: 50)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.roundedRectangle(radius: 5))
        .tint(Color.cameraAccent)
        .disabled(camera.isBusy(.focus) || !camera.autoRefresh)
        .accessibilityIdentifier("focus-drive-\(direction.rawValue)-\(step.rawValue)")
    }
}

private struct LiveViewSettingsView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    fpsControl
                    Divider().overlay(Color.cameraBorder)
                    sizeControl
                    Divider().overlay(Color.cameraBorder)
                    Toggle(
                        "auto_refresh",
                        isOn: Binding(
                            get: { camera.autoRefresh },
                            set: { enabled in Task { await camera.setAutoRefresh(enabled) } }
                        )
                    )
                    .tint(Color.cameraAccent)
                    .frame(minHeight: 48)
                    Toggle("composition_grid", isOn: $camera.showGrid)
                        .tint(Color.cameraAccent)
                        .frame(minHeight: 48)
                }
                .padding(20)
            }
            .background(Color.cameraSurface)
            .navigationTitle(Text("live_view_settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var fpsControl: some View {
        let minimum = camera.capabilities?.liveView.minimumFPS ?? 1
        let maximum = camera.capabilities?.liveView.maximumFPS ?? 30
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("live_view_frame_rate").font(.headline)
                Spacer()
                Text("\(camera.requestedFPS) FPS")
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(Color.cameraAccent)
            }
            HStack(spacing: 12) {
                Button { camera.setRequestedFPS(camera.requestedFPS - 1) } label: {
                    Image(systemName: "minus").accessibilityLabel(Text("decrease_fps"))
                }
                .buttonStyle(CameraIconButtonStyle())
                .disabled(camera.requestedFPS <= minimum)

                Slider(
                    value: Binding(
                        get: { Double(camera.requestedFPS) },
                        set: { camera.setRequestedFPS(Int($0.rounded())) }
                    ),
                    in: Double(minimum)...Double(maximum),
                    step: 1
                )
                .tint(Color.cameraAccent)
                .accessibilityLabel(Text("live_view_frame_rate"))

                Button { camera.setRequestedFPS(camera.requestedFPS + 1) } label: {
                    Image(systemName: "plus").accessibilityLabel(Text("increase_fps"))
                }
                .buttonStyle(CameraIconButtonStyle())
                .disabled(camera.requestedFPS >= maximum)
            }
            Text(language.format("requested_observed_format", camera.requestedFPS, camera.observedFPS))
                .font(.caption.monospacedDigit())
                .foregroundStyle(Color.cameraSecondaryText)
        }
    }

    private var sizeControl: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("live_view_size").font(.headline)
            Picker("live_view_size", selection: Binding(
                get: { camera.liveViewSize },
                set: { value in Task { await camera.setLiveViewSize(value) } }
            )) {
                ForEach(camera.capabilities?.liveView.sizes ?? [], id: \.self) { size in
                    Text(localizedSize(size)).tag(size)
                }
            }
            .pickerStyle(.segmented)
            .disabled((camera.capabilities?.liveView.sizes.count ?? 0) < 2)
        }
    }

    private func localizedSize(_ size: LiveViewSize) -> LocalizedStringKey {
        switch size {
        case .small: "size_small"
        case .medium: "size_medium"
        case .large: "size_large"
        }
    }
}

private struct MoreSettingsView: View {
    @EnvironmentObject private var camera: CameraAppState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 0) {
                    if settings.isEmpty {
                        ContentUnavailableView("no_settings", systemImage: "slider.horizontal.3")
                            .padding(.top, 48)
                    } else {
                        ForEach(settings) { setting in
                            settingRow(setting)
                            Divider().overlay(Color.cameraBorder)
                        }
                    }
                }
                .padding(.horizontal, 18)
            }
            .background(Color.cameraSurface)
            .navigationTitle(Text("more_settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var settings: [CameraSetting] {
        advancedSettingsForMode(camera.capabilities?.settings ?? [], mode: camera.captureMode)
    }

    private func settingRow(_ setting: CameraSetting) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(localizedSettingLabel(setting))
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Color.cameraText)
                Text(currentValue(for: setting))
                    .font(.caption)
                    .foregroundStyle(Color.cameraSecondaryText)
                    .lineLimit(1)
            }
            Spacer()
            Menu {
                ForEach(setting.values, id: \.self) { value in
                    Button {
                        Task { await camera.setSetting(key: setting.key, value: value) }
                    } label: {
                        if value == currentValue(for: setting) {
                            Label(value, systemImage: "checkmark")
                        } else {
                            Text(value)
                        }
                    }
                }
            } label: {
                Image(systemName: "chevron.up.chevron.down")
                    .frame(width: 48, height: 48)
                    .foregroundStyle(Color.cameraAccent)
                    .accessibilityLabel(Text("choose_setting"))
            }
            .disabled(camera.isBusy(.setting))
        }
        .frame(minHeight: 64)
    }

    private func currentValue(for setting: CameraSetting) -> String {
        camera.capabilities?.setting(setting.key)?.value ?? setting.value
    }

    private func localizedSettingLabel(_ setting: CameraSetting) -> LocalizedStringKey {
        switch setting.key.lowercased() {
        case "afmethod": "setting_af_method"
        case "afoperation": "setting_af_operation"
        case "drivemode": "setting_drive_mode"
        case "meteringmode": "setting_metering_mode"
        case "picturestyle": "setting_picture_style"
        case "shootingmode": "setting_shooting_mode"
        case "stillimagequality": "setting_image_quality"
        case "moviequality": "setting_movie_quality"
        case "framerate": "setting_frame_rate"
        default: LocalizedStringKey(setting.label)
        }
    }
}
