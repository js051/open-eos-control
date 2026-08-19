import OpenEOSCore
import SwiftUI
import UniformTypeIdentifiers

struct CameraSheetHost: View {
    let sheet: CameraSheet

    var body: some View {
        switch sheet {
        case .actions:
            CameraActionsView()
        case .iso, .shutter, .aperture, .whiteBalance:
            ExposureSettingsView(selectedSheet: sheet)
        case .liveView:
            LiveViewSettingsView()
        case .monitoring:
            MonitoringAssistView()
        case .more:
            MoreSettingsView()
        case .focusDrive:
            FocusDriveView()
        case .language:
            LanguageSettingsView()
        }
    }
}

private struct CameraActionsView: View {
    @EnvironmentObject private var camera: CameraAppState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                List {
                    Button {
                        dismiss()
                        camera.screen = .media
                        camera.startMediaLibraryLoad()
                    } label: {
                        Label("camera_media", systemImage: "photo.on.rectangle")
                    }
                    .disabled(!camera.supports(.mediaBrowser))
                    .accessibilityIdentifier("camera-media-menu-button")

                    Button {
                        camera.activeSheet = .focusDrive
                    } label: {
                        Label("focus_drive", systemImage: "arrow.left.and.right")
                    }
                    .disabled(!camera.supports(.focusDrive))
                    .accessibilityIdentifier("focus-drive-menu-button")

                    Button {
                        camera.activeSheet = .monitoring
                    } label: {
                        Label("monitoring_assists", systemImage: "waveform.path.ecg")
                    }
                    .accessibilityIdentifier("monitoring-menu-button")

                    Button {
                        camera.activeSheet = .more
                    } label: {
                        Label("more_settings", systemImage: "slider.horizontal.3")
                    }
                    .accessibilityIdentifier("more-settings-menu-button")

                    if camera.supports(.shutterHalfPress) {
                        Button {
                            dismiss()
                            Task { await camera.halfPressShutter() }
                        } label: {
                            Label("half_press_shutter", systemImage: "camera.aperture")
                        }
                        .disabled(camera.isBusy(.focus))
                        .accessibilityIdentifier("half-press-button")
                    }

                    Section {
                        Button {
                            dismiss()
                            camera.screen = .debug
                        } label: {
                            Label("debug", systemImage: "ladybug")
                        }
                        .accessibilityIdentifier("debug-menu-button")

                        Button {
                            camera.activeSheet = .language
                        } label: {
                            Label("language", systemImage: "globe")
                        }
                        .accessibilityIdentifier("language-menu-button")
                    }
                }
                .scrollContentBackground(.hidden)

                Divider().overlay(Color.cameraBorder)
                Button {
                    camera.requestDisconnect()
                    dismiss()
                } label: {
                    Label("disconnect", systemImage: "xmark.circle")
                        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.cameraRecording)
                .padding(.horizontal, 20)
                .padding(.vertical, 8)
                .accessibilityIdentifier("disconnect-menu-button")
            }
            .background(Color.cameraSurface)
            .navigationTitle(Text("more_actions"))
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
                    if (camera.capabilities?.liveView.sources.count ?? 0) > 1 {
                        sourceControl
                        Divider().overlay(Color.cameraBorder)
                    }
                    fpsControl
                    if camera.activeLiveViewSource == .ccapiRTP, camera.rtpAudioStatus.advertised {
                        Divider().overlay(Color.cameraBorder)
                        audioControl
                    }
                    if !camera.usesRTPLiveView {
                        Divider().overlay(Color.cameraBorder)
                        sizeControl
                    }
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
                    Button {
                        camera.activeSheet = .monitoring
                    } label: {
                        Label("monitoring_assists", systemImage: "waveform.path.ecg")
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.roundedRectangle(radius: 5))
                    .tint(Color.cameraAccent)
                    .accessibilityIdentifier("monitoring-assists-button")
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

    private var sourceControl: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("live_view_source").font(.headline)
            Picker("live_view_source", selection: Binding(
                get: { camera.selectedLiveViewSource },
                set: { value in Task { await camera.setLiveViewSource(value) } }
            )) {
                Text("source_auto").tag(LiveViewSource.auto)
                ForEach(camera.capabilities?.liveView.sources ?? [], id: \.rawValue) { source in
                    Text(localizedSource(source)).tag(source)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("live-view-source-picker")
        }
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

    private var audioControl: some View {
        let status = camera.rtpAudioStatus
        return VStack(alignment: .leading, spacing: 8) {
            Toggle(
                "camera_audio_monitoring",
                isOn: Binding(
                    get: { camera.rtpAudioRequested },
                    set: { camera.setRTPAudioEnabled($0) }
                )
            )
            .tint(Color.cameraAccent)
            .frame(minHeight: 48)
            .disabled(!status.available)
            .accessibilityIdentifier("rtp-audio-monitoring-toggle")

            Text(audioStatusText(status))
                .font(.caption)
                .foregroundStyle(status.error == nil ? Color.cameraSecondaryText : Color.cameraWarning)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("rtp-audio-monitoring-status")
        }
    }

    private func audioStatusText(_ status: IOSCcapiRTPAudioStatus) -> String {
        if status.error != nil { return language.string("camera_audio_failed") }
        if !status.available { return language.string("camera_audio_unavailable") }
        if status.enabled {
            return language.format(
                "camera_audio_active_format",
                status.rtpClockRate ?? 48_000,
                status.channels ?? 0
            )
        }
        return language.string("camera_audio_muted")
    }

    private func localizedSize(_ size: LiveViewSize) -> LocalizedStringKey {
        switch size {
        case .small: "size_small"
        case .medium: "size_medium"
        case .large: "size_large"
        }
    }

    private func localizedSource(_ source: LiveViewSource) -> LocalizedStringKey {
        switch source {
        case .ccapiRTP: "source_rtp"
        case .ccapiMultipart: "source_multipart"
        case .ccapiJPEGPolling: "source_jpeg"
        case .desktopBridgeStream: "source_bridge"
        case .simulatorFrame: "source_simulator"
        case .auto: "source_auto"
        }
    }
}

private struct MonitoringAssistView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    @Environment(\.dismiss) private var dismiss
    @State private var showingLutImporter = false

    private let zebraValues = [0, 70, 75, 80, 85, 90, 95, 100]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if !pixelAnalysisAvailable {
                        Label("monitoring_assists_rtp_unavailable", systemImage: "exclamationmark.triangle")
                            .font(.caption)
                            .foregroundStyle(Color.cameraWarning)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("monitor-pixel-analysis-unavailable")
                    }
                    monitorToggle(
                        "histogram",
                        value: Binding(
                            get: { camera.monitorSettings.histogramVisible },
                            set: { value in
                                camera.monitorSettings.setHistogramVisible(value)
                            }
                        ),
                        enabled: pixelAnalysisAvailable,
                        identifier: "monitor-histogram"
                    )
                    monitorToggle(
                        "luma_waveform",
                        value: Binding(
                            get: { camera.monitorSettings.waveformVisible },
                            set: { value in
                                camera.monitorSettings.setWaveformVisible(value)
                            }
                        ),
                        enabled: pixelAnalysisAvailable,
                        identifier: "monitor-waveform"
                    )
                    VStack(alignment: .leading, spacing: 8) {
                        Text("lut_preview")
                            .foregroundStyle(pixelAnalysisAvailable ? Color.cameraText : Color.cameraSecondaryText)
                        Text(
                            camera.monitorSettings.cubeLut.map {
                                language.format("cube_lut_summary", $0.name, $0.size)
                            } ?? language.string("no_cube_lut")
                        )
                        .font(.caption)
                        .foregroundStyle(Color.cameraSecondaryText)
                        .lineLimit(2)
                        HStack(spacing: 8) {
                            Button {
                                showingLutImporter = true
                            } label: {
                                Label("load_cube_lut", systemImage: "square.and.arrow.down")
                                    .frame(minHeight: 44)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(Color.cameraAccent)
                            .disabled(!pixelAnalysisAvailable)
                            .accessibilityIdentifier("monitor-lut-import")
                            if camera.monitorSettings.cubeLut != nil {
                                Button(role: .destructive) {
                                    camera.clearCubeLut()
                                } label: {
                                    Image(systemName: "trash")
                                        .frame(width: 44, height: 44)
                                }
                                .buttonStyle(.bordered)
                                .disabled(!pixelAnalysisAvailable)
                                .accessibilityLabel(Text("remove_cube_lut"))
                                .accessibilityIdentifier("monitor-lut-remove")
                            }
                        }
                    }
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("monitor-lut-options")
                    monitorPickerRow("zebra") {
                        Picker("zebra", selection: Binding(
                            get: { camera.monitorSettings.zebraThresholdPercent ?? 0 },
                            set: { camera.monitorSettings.zebraThresholdPercent = $0 == 0 ? nil : $0 }
                        )) {
                            ForEach(zebraValues, id: \.self) { value in
                                Text(value == 0 ? language.string("off") : language.format("zebra_threshold", value))
                                    .tag(value)
                            }
                        }
                        .disabled(!pixelAnalysisAvailable)
                        .accessibilityIdentifier("monitor-zebra")
                    }
                    monitorToggle(
                        "false_color",
                        value: Binding(
                            get: { camera.monitorSettings.falseColorEnabled },
                            set: { camera.monitorSettings.falseColorEnabled = $0 }
                        ),
                        enabled: pixelAnalysisAvailable,
                        identifier: "monitor-false-color"
                    )
                    monitorToggle(
                        "focus_peaking",
                        value: Binding(
                            get: { camera.monitorSettings.focusPeakingEnabled },
                            set: { camera.monitorSettings.focusPeakingEnabled = $0 }
                        ),
                        enabled: pixelAnalysisAvailable,
                        identifier: "monitor-focus-peaking"
                    )
                    Divider().overlay(Color.cameraBorder)
                    monitorPickerRow("frame_guide") {
                        Picker("frame_guide", selection: $camera.monitorSettings.frameGuide) {
                            ForEach(LiveViewFrameGuide.allCases) { guide in
                                Text(frameGuideLabel(guide)).tag(guide)
                            }
                        }
                        .accessibilityIdentifier("monitor-frame-guide")
                    }
                    monitorToggle(
                        "safe_area",
                        value: $camera.monitorSettings.safeAreaVisible,
                        enabled: true,
                        identifier: "monitor-safe-area"
                    )
                    monitorPickerRow("anamorphic_desqueeze") {
                        Picker("anamorphic_desqueeze", selection: $camera.monitorSettings.desqueeze) {
                            ForEach(LiveViewDesqueeze.allCases) { desqueeze in
                                Text(desqueezeLabel(desqueeze)).tag(desqueeze)
                            }
                        }
                        .accessibilityIdentifier("monitor-desqueeze")
                    }
                }
                .padding(20)
            }
            .background(Color.cameraSurface)
            .navigationTitle(Text("monitoring_assists"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .fileImporter(
            isPresented: $showingLutImporter,
            allowedContentTypes: [UTType(filenameExtension: "cube") ?? .plainText, .plainText]
        ) { result in
            switch result {
            case let .success(url):
                Task { await camera.importCubeLut(from: url) }
            case let .failure(error):
                camera.reportCubeLutImportError(error)
            }
        }
    }

    private var pixelAnalysisAvailable: Bool {
        !camera.isPreview
            && camera.activeLiveViewSource != nil
            && camera.activeLiveViewSource != .ccapiRTP
    }

    private func monitorToggle(
        _ title: LocalizedStringKey,
        value: Binding<Bool>,
        enabled: Bool,
        identifier: String
    ) -> some View {
        Toggle(title, isOn: value)
            .tint(Color.cameraAccent)
            .frame(minHeight: 48)
            .disabled(!enabled)
            .accessibilityIdentifier(identifier)
    }

    private func monitorPickerRow<Content: View>(
        _ title: LocalizedStringKey,
        @ViewBuilder content: () -> Content
    ) -> some View {
        HStack(spacing: 12) {
            Text(title)
                .foregroundStyle(Color.cameraText)
            Spacer(minLength: 8)
            content().pickerStyle(.menu)
        }
        .frame(minHeight: 48)
    }

    private func frameGuideLabel(_ guide: LiveViewFrameGuide) -> String {
        switch guide {
        case .off: language.string("off")
        case .ratio16x9: "16:9"
        case .ratio2x39: "2.39:1"
        case .ratio1x1: "1:1"
        case .ratio4x3: "4:3"
        }
    }

    private func desqueezeLabel(_ desqueeze: LiveViewDesqueeze) -> String {
        desqueeze == .off
            ? language.string("off")
            : language.format("desqueeze_value", Double(desqueeze.horizontalScale))
    }
}

private struct MoreSettingsView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    @Environment(\.dismiss) private var dismiss
    @State private var pendingRangeIndices: [String: Double] = [:]
    @State private var showSleepConfirmation = false
    @State private var showSensorCleaningConfirmation = false
    @State private var directoryName = ""
    @State private var textDrafts: [String: String] = [:]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 0) {
                    if camera.supports(.clickWhiteBalance) {
                        liveViewTapActionPicker
                        Divider().overlay(Color.cameraBorder)
                    }
                    if camera.supports(.cameraClockSync) {
                        cameraClockRow
                        Divider().overlay(Color.cameraBorder)
                    }
                    if camera.supports(.directoryControl) {
                        directoryControlRow
                        Divider().overlay(Color.cameraBorder)
                    }
                    if camera.supports(.fileNamingControl), camera.capabilities?.fileNaming != nil {
                        fileNamingRow
                        Divider().overlay(Color.cameraBorder)
                    }
                    if camera.supports(.sensorCleaning) {
                        sensorCleaningRow
                        Divider().overlay(Color.cameraBorder)
                    }
                    if camera.supports(.cameraSleep) {
                        cameraSleepRow
                        Divider().overlay(Color.cameraBorder)
                    }
                    if settings.isEmpty &&
                        !camera.supports(.clickWhiteBalance) &&
                        !camera.supports(.cameraClockSync) &&
                        !camera.supports(.directoryControl) &&
                        !camera.supports(.fileNamingControl) &&
                        !camera.supports(.sensorCleaning) &&
                        !camera.supports(.cameraSleep) {
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
        .alert("camera_sleep_confirm_title", isPresented: $showSleepConfirmation) {
            Button("cancel", role: .cancel) {}
            Button("sleep_now", role: .destructive) {
                Task { await camera.sleepCamera() }
            }
        } message: {
            Text("camera_sleep_confirm_message")
        }
        .confirmationDialog(
            "sensor_cleaning_confirm_title",
            isPresented: $showSensorCleaningConfirmation,
            titleVisibility: .visible
        ) {
            Button("sensor_cleaning_keep_power") {
                Task { await camera.cleanSensor(autoPowerOff: false) }
            }
            Button("sensor_cleaning_power_off", role: .destructive) {
                Task { await camera.cleanSensor(autoPowerOff: true) }
            }
            Button("cancel", role: .cancel) {}
        } message: {
            Text("sensor_cleaning_confirm_message")
        }
    }

    private var settings: [CameraSetting] {
        advancedSettingsForMode(camera.capabilities?.settings ?? [], mode: camera.captureMode)
    }

    private var liveViewTapActionPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("live_view_tap_action")
                .font(.callout.weight(.semibold))
                .foregroundStyle(Color.cameraText)
            Picker("live_view_tap_action", selection: liveViewTapActionBinding) {
                if camera.supports(.tapFocus) {
                    Label("tap_action_focus", systemImage: "viewfinder").tag(LiveViewTapAction.focus)
                }
                Label("tap_action_white_balance", systemImage: "eyedropper").tag(LiveViewTapAction.whiteBalance)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("live-view-tap-action-picker")
            .accessibilityValue(Text(camera.effectiveLiveViewTapAction?.rawValue ?? "none"))
        }
        .padding(.vertical, 14)
    }

    private var liveViewTapActionBinding: Binding<LiveViewTapAction> {
        Binding(
            get: { camera.effectiveLiveViewTapAction ?? .focus },
            set: { camera.liveViewTapAction = $0 }
        )
    }

    private var cameraClockRow: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("sync_camera_clock")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Color.cameraText)
                if let syncedAt = camera.lastClockSyncAt {
                    Text(language.format("camera_clock_synced_at", localizedClockTime(syncedAt)))
                    .font(.caption)
                    .foregroundStyle(Color.cameraStatus)
                } else {
                    Text("sync_camera_clock_hint")
                        .font(.caption)
                        .foregroundStyle(Color.cameraSecondaryText)
                }
            }
            Spacer(minLength: 8)
            Button {
                Task { await camera.syncCameraClock() }
            } label: {
                Label("sync_now", systemImage: "clock")
                    .frame(minHeight: 44)
            }
            .buttonStyle(.bordered)
            .tint(Color.cameraAccent)
            .disabled(camera.isBusy(.clock))
            .accessibilityIdentifier("sync-camera-clock")
        }
        .frame(minHeight: 72)
    }

    private var directoryControlRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("create_capture_directory")
                .font(.callout.weight(.semibold))
                .foregroundStyle(Color.cameraText)
            Text(camera.lastCreatedDirectoryName.map { language.format("directory_created", $0) }
                 ?? language.string("create_capture_directory_hint"))
                .font(.caption)
                .foregroundStyle(camera.lastCreatedDirectoryName == nil ? Color.cameraSecondaryText : Color.cameraStatus)
            HStack(spacing: 8) {
                TextField("directory_name_placeholder", text: $directoryName)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .onChange(of: directoryName) { _, value in
                        directoryName = String(
                            value.uppercased()
                                .filter { "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_".contains($0) }
                                .prefix(5)
                        )
                    }
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("directory-name")
                Button("create") {
                    let name = directoryName
                    directoryName = ""
                    Task { await camera.createDirectory(name: name) }
                }
                .buttonStyle(.bordered)
                .tint(Color.cameraAccent)
                .disabled(
                    camera.isPreview ||
                        (!directoryName.isEmpty && directoryName.count != 5) ||
                        camera.isBusy(.directory)
                )
                .accessibilityIdentifier("create-directory")
            }
        }
        .padding(.vertical, 14)
    }

    private var fileNamingRow: some View {
        NavigationLink {
            FileNamingEditorView()
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("file_naming")
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(Color.cameraText)
                    if let naming = camera.capabilities?.fileNaming {
                        Text(
                            camera.captureMode == .photo
                                ? language.format("file_naming_photo_summary", naming.stillFilenameMode)
                                : language.format(
                                    "file_naming_video_summary",
                                    naming.movieIndex,
                                    naming.movieReelNumber,
                                    naming.movieClipNumber
                                )
                        )
                        .font(.caption)
                        .foregroundStyle(Color.cameraSecondaryText)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .foregroundStyle(Color.cameraSecondaryText)
            }
            .frame(minHeight: 72)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("file-naming")
    }

    private func localizedClockTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = language.locale
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    private var cameraSleepRow: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("camera_sleep")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Color.cameraText)
                Text("camera_sleep_hint")
                    .font(.caption)
                    .foregroundStyle(Color.cameraSecondaryText)
            }
            Spacer(minLength: 8)
            Button(role: .destructive) {
                showSleepConfirmation = true
            } label: {
                Label("sleep_now", systemImage: "power")
                    .frame(minHeight: 44)
            }
            .buttonStyle(.bordered)
            .disabled(
                camera.isPreview ||
                camera.recording ||
                camera.bulbExposureActive ||
                !camera.busyOperations.isEmpty
            )
            .accessibilityIdentifier("camera-sleep")
        }
        .frame(minHeight: 72)
    }

    private var sensorCleaningRow: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("sensor_cleaning")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Color.cameraText)
                Text("sensor_cleaning_hint")
                    .font(.caption)
                    .foregroundStyle(Color.cameraSecondaryText)
            }
            Spacer(minLength: 8)
            Button {
                showSensorCleaningConfirmation = true
            } label: {
                Label("clean_now", systemImage: "arrow.triangle.2.circlepath")
                    .frame(minHeight: 44)
            }
            .buttonStyle(.bordered)
            .tint(Color.cameraAccent)
            .disabled(
                camera.isPreview ||
                camera.recording ||
                camera.bulbExposureActive ||
                !camera.busyOperations.isEmpty
            )
            .accessibilityIdentifier("sensor-cleaning")
        }
        .frame(minHeight: 72)
    }

    @ViewBuilder
    private func settingRow(_ setting: CameraSetting) -> some View {
        if setting.inputKind == .text {
            textSettingRow(setting)
        } else if [
            "zoom",
            "soundrecordinglevel",
            "soundrecordinglevelintmic",
            "soundrecordinglevelextmic",
            "soundrecordinglevelacc",
            "focusbracketingnumberofshots",
            "focusbracketingfocusincrement",
        ].contains(setting.key.lowercased()) {
            rangeSettingRow(setting)
        } else {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(localizedSettingLabel(setting))
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(Color.cameraText)
                    Text(localizedSettingValue(setting, value: currentValue(for: setting)))
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
                                Label(localizedSettingValue(setting, value: value), systemImage: "checkmark")
                            } else {
                                Text(localizedSettingValue(setting, value: value))
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
    }

    private func textSettingRow(_ setting: CameraSetting) -> some View {
        let current = currentValue(for: setting)
        let draft = textDrafts[setting.key] ?? current
        let binding = Binding<String>(
            get: { textDrafts[setting.key] ?? currentValue(for: setting) },
            set: { textDrafts[setting.key] = $0 }
        )
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(localizedSettingLabel(setting))
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Color.cameraText)
                Spacer()
                Text("text_metadata_rule")
                    .font(.caption)
                    .foregroundStyle(Color.cameraSecondaryText)
            }
            HStack(spacing: 10) {
                TextField(localizedSettingLabel(setting), text: binding)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.asciiCapable)
                    .autocorrectionDisabled()
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("\(setting.key)-input")
                Button("apply") {
                    let value = binding.wrappedValue
                    Task {
                        await camera.setSetting(key: setting.key, value: value)
                        if camera.lastError == nil {
                            textDrafts[setting.key] = nil
                        }
                    }
                }
                .buttonStyle(.bordered)
                .tint(Color.cameraAccent)
                .disabled(
                    camera.isPreview ||
                    camera.isBusy(.setting) ||
                    !setting.accepts(draft) ||
                    draft == current
                )
                .accessibilityIdentifier("\(setting.key)-apply")
            }
        }
        .frame(minHeight: 96)
    }

    private func rangeSettingRow(_ setting: CameraSetting) -> some View {
        let currentIndex = setting.values.firstIndex(of: currentValue(for: setting)) ?? 0
        let pendingIndex = min(
            max(Int((pendingRangeIndices[setting.key] ?? Double(currentIndex)).rounded()), 0),
            setting.values.count - 1
        )
        let selection = Binding<Double>(
            get: { pendingRangeIndices[setting.key] ?? Double(currentIndex) },
            set: { pendingRangeIndices[setting.key] = $0 }
        )
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(localizedSettingLabel(setting))
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Color.cameraText)
                Spacer()
                Text(verbatim: rangeSettingValue(setting, value: setting.values[pendingIndex]))
                    .font(.callout.weight(.bold))
                    .foregroundStyle(Color.cameraAccent)
            }
            Slider(
                value: selection,
                in: 0...Double(setting.values.count - 1),
                step: 1
            ) { editing in
                guard !editing else { return }
                let selected = setting.values[pendingIndex]
                pendingRangeIndices[setting.key] = nil
                guard selected != currentValue(for: setting) else { return }
                Task { await camera.setSetting(key: setting.key, value: selected) }
            }
            .tint(Color.cameraAccent)
            .disabled(camera.isBusy(.setting))
            .accessibilityLabel(Text(localizedSettingLabel(setting)))
            .accessibilityValue(Text(verbatim: rangeSettingValue(setting, value: setting.values[pendingIndex])))
        }
        .frame(minHeight: 72)
    }

    private func rangeSettingValue(_ setting: CameraSetting, value: String) -> String {
        setting.key.lowercased() == "zoom" ? "\(value)%" : value
    }

    private func currentValue(for setting: CameraSetting) -> String {
        camera.capabilities?.setting(setting.key)?.value ?? setting.value
    }

    private func localizedSettingLabel(_ setting: CameraSetting) -> LocalizedStringKey {
        LocalizedStringKey(settingLabelLocalizationKey(setting.key) ?? setting.label)
    }

    private func localizedSettingValue(_ setting: CameraSetting, value: String) -> LocalizedStringKey {
        if setting.key.lowercased() == "moviequality",
           let display = movieQualityDisplayValue(
               value,
               lightLabel: language.string("camera_value_light"),
               cropLabel: language.string("camera_value_crop"),
               fineLabel: language.string("camera_value_fine")
           ) {
            return LocalizedStringKey(display)
        }
        if setting.key.lowercased() == "movieformat",
           let display = movieFormatDisplayValue(value) {
            return LocalizedStringKey(display)
        }
        return LocalizedStringKey(settingValueLocalizationKey(key: setting.key, value: value) ?? value)
    }
}

private struct FileNamingEditorView: View {
    @EnvironmentObject private var camera: CameraAppState
    @State private var stillUserSetting1 = ""
    @State private var stillUserSetting2 = ""
    @State private var movieIndex = ""
    @State private var movieReelNumber = ""
    @State private var movieClipNumber = ""
    @State private var movieUserDefined = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let naming = camera.capabilities?.fileNaming {
                    if camera.captureMode == .photo {
                        Picker("still_filename_mode", selection: stillModeBinding(naming)) {
                            ForEach(naming.stillFilenameModeOptions, id: \.self) { option in
                                Text(modeLabel(option)).tag(option)
                            }
                        }
                        .pickerStyle(.segmented)
                        .disabled(camera.isBusy(.setting) || camera.isPreview)
                        fileNamingField(
                            "still_user_setting_1",
                            value: $stillUserSetting1,
                            field: .stillUserSetting1,
                            maximumLength: 4,
                            allowUnderscore: true,
                            naming: naming
                        )
                        fileNamingField(
                            "still_user_setting_2",
                            value: $stillUserSetting2,
                            field: .stillUserSetting2,
                            maximumLength: 3,
                            allowUnderscore: true,
                            naming: naming
                        )
                    } else {
                        fileNamingField(
                            "movie_index",
                            value: $movieIndex,
                            field: .movieIndex,
                            maximumLength: 2,
                            allowUnderscore: true,
                            naming: naming
                        )
                        fileNamingField(
                            "movie_reel_number",
                            value: $movieReelNumber,
                            field: .movieReelNumber,
                            maximumLength: 4,
                            numeric: true,
                            naming: naming
                        )
                        fileNamingField(
                            "movie_clip_number",
                            value: $movieClipNumber,
                            field: .movieClipNumber,
                            maximumLength: 3,
                            numeric: true,
                            naming: naming
                        )
                        fileNamingField(
                            "movie_user_defined",
                            value: $movieUserDefined,
                            field: .movieUserDefined,
                            maximumLength: 5,
                            allowUnderscore: false,
                            naming: naming
                        )
                    }
                    Text("file_naming_rule")
                        .font(.caption)
                        .foregroundStyle(Color.cameraSecondaryText)
                } else {
                    ContentUnavailableView("file_naming_unavailable", systemImage: "textformat")
                }
            }
            .padding(18)
        }
        .background(Color.cameraSurface)
        .navigationTitle(Text("file_naming"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: loadCurrentValues)
        .onChange(of: camera.capabilities?.fileNaming) { _, _ in loadCurrentValues() }
    }

    private func stillModeBinding(_ naming: CameraFileNaming) -> Binding<String> {
        Binding(
            get: { naming.stillFilenameMode },
            set: { value in Task { await camera.setFileNaming(field: .stillFilenameMode, value: value) } }
        )
    }

    @ViewBuilder
    private func fileNamingField(
        _ label: LocalizedStringKey,
        value: Binding<String>,
        field: CameraFileNamingField,
        maximumLength: Int,
        allowUnderscore: Bool = false,
        numeric: Bool = false,
        naming: CameraFileNaming
    ) -> some View {
        HStack(spacing: 10) {
            TextField(label, text: value)
                .textInputAutocapitalization(numeric ? .never : .characters)
                .keyboardType(numeric ? .numberPad : .asciiCapable)
                .autocorrectionDisabled()
                .onChange(of: value.wrappedValue) { _, raw in
                    let filtered = numeric
                        ? raw.filter(\.isNumber)
                        : raw.uppercased().filter {
                            $0.isASCII && ($0.isLetter || $0.isNumber || (allowUnderscore && $0 == "_"))
                        }
                    value.wrappedValue = String(filtered.prefix(maximumLength))
                }
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier(field.rawValue)
            Button("apply") {
                Task { await camera.setFileNaming(field: field, value: value.wrappedValue) }
            }
            .buttonStyle(.bordered)
            .tint(Color.cameraAccent)
            .disabled(camera.isPreview || camera.isBusy(.setting) || !naming.accepts(field, value: value.wrappedValue))
            .accessibilityIdentifier("\(field.rawValue)-apply")
        }
    }

    private func loadCurrentValues() {
        guard let naming = camera.capabilities?.fileNaming else { return }
        stillUserSetting1 = naming.stillUserSetting1
        stillUserSetting2 = naming.stillUserSetting2
        movieIndex = naming.movieIndex
        movieReelNumber = String(naming.movieReelNumber)
        movieClipNumber = String(naming.movieClipNumber)
        movieUserDefined = naming.movieUserDefined
    }

    private func modeLabel(_ value: String) -> LocalizedStringKey {
        switch value {
        case "preset_code": "file_naming_preset_code"
        case "usersetting1": "still_user_setting_1"
        case "usersetting2": "still_user_setting_2"
        default: "unknown"
        }
    }
}
