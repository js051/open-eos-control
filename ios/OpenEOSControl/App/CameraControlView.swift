import OpenEOSCore
import SwiftUI
import UIKit

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
    }

    // Camera controls own their touches. In particular, dragging out of AF-ON
    // releases that press without also hiding its entire control hierarchy.
    private var hudVisibilityGesture: some Gesture {
        DragGesture(minimumDistance: 40).onEnded { gesture in
            if abs(gesture.translation.height) > abs(gesture.translation.width) {
                withAnimation(.easeOut(duration: 0.18)) { camera.hudVisible.toggle() }
            }
        }
    }

    private func portraitLayout(_ proxy: GeometryProxy) -> some View {
        ZStack {
            LiveViewSurface()
                .simultaneousGesture(hudVisibilityGesture)
            if camera.hudVisible {
                VStack(spacing: 0) {
                    CameraOverlayHeader(controlRotation: controlRotation)
                        .padding(.top, proxy.safeAreaInsets.top + 4)
                        .padding(.horizontal, 10)
                    TemperatureWarningOverlay(controlRotation: controlRotation)
                        .padding(.top, 6)
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
                    .simultaneousGesture(hudVisibilityGesture)
                if camera.hudVisible {
                    VStack(spacing: 6) {
                        CameraOverlayHeader(controlRotation: controlRotation)
                        TemperatureWarningOverlay(controlRotation: controlRotation)
                    }
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

private struct TemperatureWarningOverlay: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let controlRotation: Double

    var body: some View {
        if let temperature = camera.status?.temperature, !temperature.isNormal {
            RotatingControl(degrees: controlRotation) {
                HStack(spacing: 7) {
                    Image(systemName: "exclamationmark.triangle.fill")
                    Text(messages(for: temperature).joined(separator: " · "))
                        .font(.caption.weight(.semibold))
                        .lineLimit(2)
                        .minimumScaleFactor(0.75)
                }
                .foregroundStyle(Color.cameraWarning)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .frame(maxWidth: 360, minHeight: 38)
                .background(Color.black.opacity(0.82))
                .clipShape(RoundedRectangle(cornerRadius: 6))
            }
            .accessibilityIdentifier("temperature-status-banner")
        }
    }

    private func messages(for temperature: CameraTemperatureStatus) -> [String] {
        var values: [String] = []
        if temperature.temperatureWarning { values.append(language.string("camera_temperature_warning")) }
        if temperature.frameRateReduced { values.append(language.string("temperature_frame_rate_reduced")) }
        if !temperature.liveViewAllowed { values.append(language.string("temperature_live_view_unavailable")) }
        if !temperature.stillCaptureAllowed { values.append(language.string("temperature_shutter_unavailable")) }
        if !temperature.movieRecordingAllowed { values.append(language.string("temperature_movie_recording_restricted")) }
        if temperature.stillQualityWarning { values.append(language.string("temperature_still_quality_warning")) }
        return values.isEmpty ? [language.string("camera_temperature_warning")] : values
    }
}

private struct CameraOverlayHeader: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let controlRotation: Double

    var body: some View {
        ViewThatFits(in: .horizontal) {
            header(showDetails: true).fixedSize(horizontal: true, vertical: false)
            header(showDetails: false)
        }
        .padding(.leading, 11)
        .padding(.trailing, 4)
        .frame(height: 54)
        .foregroundStyle(Color.cameraText)
        .background(Color.black.opacity(0.66))
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private func header(showDetails: Bool) -> some View {
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
            .accessibilityIdentifier("camera-model-status")
            Spacer(minLength: 4)
            if showDetails {
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
            }
            if camera.capabilities?.heldAutofocusSupported == true {
                HeldAutofocusButton(camera: camera)
                    .frame(width: 50, height: 44)
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
                .accessibilityIdentifier("autofocus-button")
            }
            Button {
                camera.activeSheet = .actions
            } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "ellipsis")
                        .accessibilityLabel(Text("more_actions"))
                }
            }
            .buttonStyle(CameraIconButtonStyle())
            .accessibilityIdentifier("more-actions-button")
        }

    }

    private var storageText: String? {
        if camera.captureMode == .video, let seconds = camera.status?.remainingRecordingSeconds {
            return language.format("recording_time_remaining_format", formatRecordingDuration(seconds))
        }
        if let shots = camera.status?.recordableShots ?? camera.status?.storageFreeImages {
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

    private func formatRecordingDuration(_ value: Int64) -> String {
        let seconds = max(0, value)
        let hours = seconds / 3_600
        let minutes = (seconds % 3_600) / 60
        let remainder = seconds % 60
        if hours > 0 {
            return String(format: "%lld:%02lld:%02lld", hours, minutes, remainder)
        }
        return String(format: "%02lld:%02lld", minutes, remainder)
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
    @State private var draft: AppCaptureMode?
    @State private var revision = UUID()
    @State private var editorOwner = UUID()

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 2) {
                modeButton(.photo, title: "photo")
                modeButton(.video, title: "video")
            }
            .padding(2)
            .background(.quaternary, in: RoundedRectangle(cornerRadius: 9))
            .disabled(!canSelectMode)
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Text("capture_mode"))
            .accessibilityIdentifier("capture-mode-picker")
            #if DEBUG && OEC_REQUIRE_SIMULATOR_E2E
            .background {
                if CaptureModeTouchProbe.enabled {
                    CaptureModeTouchProbe(camera: camera).allowsHitTesting(false)
                }
            }
            #endif
            // Reserve this slot before a selection begins. In the bottom-aligned
            // controls, inserting Apply moved the mode selector by more than
            // its own height. Keeping geometry stable avoids that mid-selection reflow.
            Text("apply")
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(minHeight: 44)
                .hidden()
                .overlay {
                    if let selected = draft {
                        Button("apply") {
                            let submittedRevision = revision
                            let owner = editorOwner
                            let sessionOwner = camera.mediaLibrarySessionID
                            Task {
                                let consumed = await camera.setCaptureMode(selected, draftRevision: submittedRevision)
                                if consumed, revision == submittedRevision, owner == editorOwner,
                                   sessionOwner == camera.mediaLibrarySessionID { draft = nil }
                            }
                        }
                        .buttonStyle(.bordered)
                        .disabled(!camera.canStartShootingSetting || selected == camera.captureMode
                            || camera.recordingStopRequired || camera.bulbExposureActive)
                        .accessibilityIdentifier("capture-mode-apply")
                    }
                }
            if let record = camera.shootingSettingRecord {
                Button { camera.activeSheet = .more } label: {
                    Text(LocalizedStringKey(record.running ? "shooting_setting_working" : record.statusKey))
                        .font(.caption).lineLimit(2)
                }
                .accessibilityIdentifier("shooting-setting-open-result")
            }
        }
        .onChange(of: camera.mediaLibrarySessionID) { _, _ in draft = nil; revision = UUID(); editorOwner = UUID() }
        .onDisappear { editorOwner = UUID(); camera.cancelShootingSetting() }
    }

    private var canSelectMode: Bool {
        !camera.recordingStopRequired && !camera.bulbExposureActive && camera.canStartShootingSetting
            && !camera.isBusy(.capture) && !camera.isBusy(.recording)
    }

    private func modeButton(_ mode: AppCaptureMode, title: LocalizedStringKey) -> some View {
        let selected = (draft ?? camera.captureMode) == mode
        return Button {
            guard canSelectMode else { return }
            // Each action owns its exact mode; no implicit Picker selection binding
            // can substitute the previously selected segment during a native touch.
            camera.traceCaptureMode(.pickerSelection, requested: mode)
            draft = mode
            revision = UUID()
        } label: {
            Text(title)
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(selected ? Color.accentColor.opacity(0.18) : Color.clear,
                    in: RoundedRectangle(cornerRadius: 7))
        .accessibilityAddTraits(selected ? .isSelected : [])
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
    @EnvironmentObject private var language: AppLanguageStore
    let controlRotation: Double
    var compact = false

    var body: some View {
        VStack(spacing: 3) {
            HStack(spacing: 12) {
                latestMediaButton

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

            if camera.showShutterAutofocus {
                VStack(alignment: .leading, spacing: 4) {
                    Button {
                        camera.setShutterAutofocus(!camera.shutterAutofocus)
                    } label: {
                        HStack(spacing: 8) {
                            Text("shutter_autofocus")
                            Spacer(minLength: 0)
                            Text(LocalizedStringKey(camera.shutterAutofocus ? "shutter_af_on" : "shutter_af_off"))
                            Image(systemName: camera.shutterAutofocus ? "checkmark.circle.fill" : "circle")
                        }
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 8)
                        .frame(minWidth: 44, maxWidth: .infinity, minHeight: 44)
                        .foregroundStyle(camera.shutterAutofocus ? Color.cameraBackground : Color.cameraText)
                        .background(camera.shutterAutofocus ? Color.cameraText : Color.cameraSurfaceRaised)
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!camera.canChangeShutterAutofocus)
                    .accessibilityLabel(Text("shutter_autofocus"))
                    .accessibilityValue(Text(LocalizedStringKey(camera.shutterAutofocus ? "shutter_af_on" : "shutter_af_off")))
                    .accessibilityAddTraits(camera.shutterAutofocus ? .isSelected : [])
                    .accessibilityIdentifier("shutter-autofocus-toggle")
                    Text(LocalizedStringKey(!camera.shutterAutofocusAllowed ? "shutter_af_unavailable" :
                        camera.shutterAutofocus ? "shutter_af_on_description" : "shutter_af_off_description"))
                        .font(.caption2)
                        .foregroundStyle(Color.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("shutter-autofocus-description")
                }
                .padding(.horizontal, compact ? 8 : 18)
                .padding(.bottom, 6)
            }

            if let outcome = camera.recordingOutcomes.last {
                VStack(alignment: .leading, spacing: 2) {
                    Text(language.format("recording_original_response",
                        language.string(outcome.starting ? "start_recording" : "stop_recording"),
                        language.string("recording_response_" + outcome.responseEvidence.rawValue)))
                        .accessibilityIdentifier("recording-original-response")
                    Text(language.format("recording_current_observation",
                        language.string(camera.recordingObservation.observedRecording.map {
                            $0 ? "recording_observed_active" : "recording_observed_stopped"
                        } ?? "recording_observed_unavailable")))
                        .accessibilityIdentifier("recording-current-observation")
                }
                .font(.caption2)
                .foregroundStyle(Color.secondary)
                .padding(.horizontal, compact ? 8 : 18)
                .padding(.bottom, 6)
            }

            if let recovery = camera.recordingRecovery {
                VStack(alignment: .leading, spacing: 4) {
                    Text(LocalizedStringKey(camera.recordingCommandRecovery != nil
                        ? "recording_command_unconfirmed"
                        : recovery.starting ? "recording_start_status_unknown" : "recording_stop_status_unknown"))
                        .font(.caption)
                        .foregroundStyle(Color.cameraWarning)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("recording-readback-status")
                    Button {
                        Task { await camera.refreshRecordingObservation() }
                    } label: {
                        Label("refresh", systemImage: "arrow.clockwise")
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .disabled(camera.isBusy(.refresh) || camera.busyOperations.contains(.recording))
                    .accessibilityIdentifier("recording-readback-refresh")
                }
                .padding(.horizontal, compact ? 8 : 18)
                .padding(.bottom, 6)
            }

            if camera.captureReviewState != .idle {
                VStack(alignment: .leading, spacing: 4) {
                    Text(LocalizedStringKey(camera.captureReviewKind.localizationKey(for: camera.captureReviewState, outcome: camera.captureReviewOutcome)))
                        .font(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("capture-review-status")
                    if camera.captureReviewState == .notReady,
                       camera.captureReviewOutcome.showsLastReadFailureWarning {
                        Text(LocalizedStringKey(camera.captureReviewKind.localizationKey(suffix: "last_read_failed")))
                            .font(.caption)
                            .foregroundStyle(Color.cameraWarning)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("capture-review-last-read-failed")
                    }
                    if camera.captureReviewState != .capturing {
                        Text(LocalizedStringKey(camera.captureReviewKind.localizationKey(suffix: "limited_list")))
                            .accessibilityIdentifier("capture-review-limit")
                            .font(.caption2)
                            .foregroundStyle(Color.cameraSecondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if camera.captureReviewState == .notReady || camera.captureReviewState == .readFailed {
                        Button {
                            camera.retryCaptureMediaReview()
                        } label: {
                            Label(LocalizedStringKey(camera.captureReviewKind.localizationKey(suffix: "retry")), systemImage: "arrow.clockwise")
                                .font(.caption.weight(.semibold))
                                .frame(minWidth: 44, maxWidth: .infinity, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.cameraAccent)
                        .disabled(!camera.canRetryCaptureMediaReview)
                        .accessibilityIdentifier("capture-review-retry")
                    }
                }
                .padding(.horizontal, compact ? 8 : 18)
                .padding(.bottom, 6)
            }

            if !captureSupported {
                Text(LocalizedStringKey(camera.bulbMode ? "bulb_not_supported" : camera.captureMode == .photo ? "capture_not_supported" : "recording_not_supported"))
                    .font(.caption)
                    .foregroundStyle(Color.cameraWarning)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 8)
                    .padding(.bottom, 6)
            }
        }
    }

    @ViewBuilder
    private var latestMediaButton: some View {
        if camera.supports(.mediaBrowser) {
            Button {
                Task { await camera.openLatestMedia() }
            } label: {
                RotatingControl(degrees: controlRotation) {
                    ZStack {
                        if let data = camera.latestMediaThumbnail,
                           let image = UIImage(data: data) {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFill()
                        } else {
                            Image(systemName: "photo.on.rectangle")
                                .font(.title3.weight(.semibold))
                                .foregroundStyle(Color.cameraSecondaryText)
                        }
                        if camera.latestMediaThumbnailLoading {
                            ProgressView()
                                .tint(Color.cameraText)
                                .background(.black.opacity(0.35), in: Circle())
                        }
                    }
                    .frame(width: 48, height: 48)
                    .background(Color.cameraSurfaceRaised)
                    .clipShape(RoundedRectangle(cornerRadius: 5))
                    .overlay {
                        RoundedRectangle(cornerRadius: 5)
                            .stroke(Color.cameraText.opacity(0.25), lineWidth: 1)
                    }
                    .overlay(alignment: .bottom) {
                        if camera.latestMediaIsPrevious {
                            Text(LocalizedStringKey(camera.captureReviewKind.localizationKey(suffix: "previous_badge")))
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                                .background(.black.opacity(0.8))
                                .accessibilityHidden(true)
                        }
                    }
                    .accessibilityLabel(Text(latestMediaAccessibilityLabel))
                }
            }
            .buttonStyle(.plain)
            .disabled(!camera.canOpenLatestMedia || camera.isBusy(.media))
            .accessibilityIdentifier("latest-media-button")
        } else {
            Color.clear
                .frame(width: 48, height: 48)
                .accessibilityHidden(true)
        }
    }

    private var latestMediaAccessibilityLabel: String {
        guard let item = camera.latestMediaItem else {
            return language.string("open_latest_media")
        }
        let key: String
        if camera.latestMediaIsPrevious {
            key = mediaIsVideo(item) ? "open_previous_video_named" : "open_previous_media_named"
        } else if camera.captureReviewState == .available {
            key = mediaIsVideo(item) ? "open_visible_video_named" : "open_visible_media_named"
        } else {
            key = "open_latest_media_named"
        }
        return language.format(key, item.name)
    }

    private var captureSupported: Bool {
        if camera.shutterReleaseRequired { return true }
        if camera.recordingStopRequired { return true }
        if camera.captureMode == .photo && !camera.bulbMode && !camera.shutterAutofocusAllowed { return false }
        return camera.supports(
            camera.bulbMode ? .bulbExposure : camera.captureMode == .photo ? .stillCapture : .videoRecording
        )
    }

    private var captureTemperatureAllowed: Bool {
        if camera.shutterReleaseRequired { return true }
        if camera.recordingStopRequired { return true }
        return camera.captureMode == .photo
            ? camera.stillCaptureTemperatureAllowed
            : camera.movieRecordingTemperatureAllowed
    }

    @ViewBuilder
    private var captureButton: some View {
        Button {
            Task { await camera.performCaptureButtonAction() }
        } label: {
            RotatingControl(degrees: controlRotation) {
                ZStack {
                    Circle()
                        .stroke(Color.cameraText, lineWidth: 4)
                        .frame(width: compact ? 62 : 72, height: compact ? 62 : 72)
                    if camera.shutterReleaseRequired {
                        RoundedRectangle(cornerRadius: 5)
                            .fill(Color.cameraWarning)
                            .frame(width: 40, height: 40)
                    } else if camera.recordingStopRequired || camera.captureMode == .video {
                        RoundedRectangle(cornerRadius: camera.recordingStopRequired ? 5 : 25)
                            .fill(camera.recordingRecovery != nil ? Color.cameraWarning : Color.cameraRecording)
                            .frame(width: camera.recordingStopRequired ? 28 : 54, height: camera.recordingStopRequired ? 28 : 54)
                    } else {
                        Group {
                            if camera.bulbMode && camera.bulbExposureActive {
                                RoundedRectangle(cornerRadius: 5).fill(Color.cameraWarning)
                            } else {
                                Circle().fill(camera.bulbMode ? Color.cameraWarning : Color.cameraText)
                            }
                        }
                        .frame(width: compact ? 49 : 58, height: compact ? 49 : 58)
                    }
                    if camera.busyOperations.contains(camera.shutterReleaseRequired || (!camera.recordingStopRequired && camera.captureMode == .photo) ? .capture : .recording) {
                        ProgressView().tint(camera.captureMode == .photo ? Color.cameraBackground : Color.cameraText)
                    }
                }
                .accessibilityLabel(
                    Text(
                        LocalizedStringKey(
                            camera.shutterReleaseRequired
                                ? camera.shutterReleaseUnconfirmed ? "release_shutter_now" : "stop_bulb_exposure"
                                : camera.recordingStopRequired ? "stop_recording"
                                : camera.bulbMode
                                ? "start_bulb_exposure"
                                : camera.captureMode == .photo
                                    ? camera.shutterAutofocus ? "capture_photo" : "capture_without_autofocus"
                                    : camera.recordingStopRequired ? "stop_recording" : "start_recording"
                        )
                    )
                )
            }
        }
        .buttonStyle(.plain)
        .disabled(
            !captureSupported || !captureTemperatureAllowed ||
                (camera.shutterReleaseRequired
                    ? !camera.canRetryShutterRelease
                    : camera.isBusy(!camera.recordingStopRequired && camera.captureMode == .photo ? .capture : .recording))
        )
        .opacity(captureSupported && captureTemperatureAllowed ? 1 : 0.38)
        .accessibilityIdentifier(!camera.recordingStopRequired && camera.captureMode == .photo ? "shutter-button" : "record-button")
    }
}
