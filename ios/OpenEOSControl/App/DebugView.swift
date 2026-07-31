import OpenEOSCore
import SwiftUI
import UIKit

struct DebugView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let controlRotation: Double
    @State private var copied = false

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().overlay(Color.cameraBorder)
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    debugSection("overview") {
                        value("camera_profile", camera.capabilities?.profile.modelName ?? "unknown")
                        value("transport", camera.transportIdentifier)
                        value("api_version", camera.info?.api ?? "unknown")
                        value(
                            "serial_number",
                            displayedSerial(camera.info?.serial),
                            mono: true
                        )
                        value("last_error", camera.lastError ?? language.string("none"), warning: camera.lastError != nil)
                    }

                    debugSection("connection_details") {
                        value("base_url", camera.connectionEndpoint, mono: true)
                        value("supported_features", featureList(camera.capabilities?.matrix.supported))
                        value("planned_features", featureList(camera.capabilities?.matrix.planned))
                        value("storage_available", camera.status?.mediaAvailable.map { language.string($0 ? "yes" : "no") } ?? language.string("unknown"))
                        value("storage_total_bytes", camera.status?.storageTotalBytes.map { String($0) } ?? language.string("none"), mono: true)
                        value("storage_free_bytes", camera.status?.storageFreeBytes.map { String($0) } ?? language.string("none"), mono: true)
                        value("storage_free_images", camera.status?.storageFreeImages.map { String($0) } ?? language.string("none"), mono: true)
                        value("storage_devices", camera.status?.storageDeviceCount.map { String($0) } ?? language.string("none"), mono: true)
                        value("battery_raw", camera.status?.rawBatteryJSON ?? "null", mono: true)
                        value("storage_raw", camera.status?.rawStorageJSON ?? "null", mono: true)
                    }

                    debugSection("capability_evidence") {
                        let validation = DiagnosticValidationSummary(capabilities: camera.capabilities)
                        value("capability_source", camera.capabilities?.evidence.source ?? "unknown", mono: true)
                        value(
                            "protocol_versions",
                            camera.capabilities?.evidence.protocolVersions.joined(separator: ", ").nilIfBlank
                                ?? language.string("none"),
                            mono: true
                        )
                        value(
                            "advertised_commands",
                            camera.capabilities?.evidence.advertisedCommands.joined(separator: "\n").nilIfBlank
                                ?? language.string("none"),
                            mono: true
                        )
                        value(
                            "writable_settings",
                            camera.capabilities?.evidence.writableSettings.joined(separator: ", ").nilIfBlank
                                ?? language.string("none"),
                            mono: true
                        )
                        value(
                            "observed_features",
                            featureList(camera.capabilities?.evidence.observedFeatures),
                            mono: true
                        )
                        value(
                            "validation_coverage",
                            "\(validation.validatedAdvertisedFeatures.count) / \(validation.advertisedFeatures.count)"
                        )
                        value(
                            "unverified_advertised_features",
                            featureList(validation.unverifiedAdvertisedFeatures),
                            mono: true,
                            warning: !validation.unverifiedAdvertisedFeatures.isEmpty
                        )
                        value(
                            "observed_without_advertisement",
                            featureList(validation.observedWithoutAdvertisement),
                            mono: true,
                            warning: !validation.observedWithoutAdvertisement.isEmpty
                        )
                        value(
                            "evidence_truncated",
                            language.string(camera.capabilities?.evidence.truncated == true ? "yes" : "no"),
                            warning: camera.capabilities?.evidence.truncated == true
                        )
                    }

                    debugSection("live_view") {
                        value("live_view_source", camera.activeLiveViewSource?.rawValue ?? language.string("none"))
                        value("requested_fps", "\(camera.requestedFPS)")
                        value("observed_fps", String(format: "%.1f", camera.observedFPS))
                        value("frame_bytes", "\(camera.frameBytes)")
                        value("content_type", camera.frameContentType ?? language.string("none"))
                        value("source_endpoint", camera.frameSourceURL?.absoluteString ?? language.string("none"), mono: true)
                        value("latest_frame", camera.lastFrameAt?.formatted(date: .abbreviated, time: .standard) ?? language.string("none"))
                        value("rtp_audio_advertised", language.string(camera.rtpAudioStatus.advertised ? "yes" : "no"))
                        value("rtp_audio_available", language.string(camera.rtpAudioStatus.available ? "yes" : "no"))
                        value("rtp_audio_requested", language.string(camera.rtpAudioRequested ? "yes" : "no"))
                        value("rtp_audio_enabled", language.string(camera.rtpAudioStatus.enabled ? "yes" : "no"))
                        value("rtp_audio_codec", camera.rtpAudioStatus.codec ?? language.string("none"), mono: true)
                        value("rtp_audio_port", camera.rtpAudioStatus.rtpPort.map { String($0) } ?? language.string("none"), mono: true)
                        value("rtp_audio_clock_rate", camera.rtpAudioStatus.rtpClockRate.map { String($0) } ?? language.string("none"), mono: true)
                        value("rtp_audio_channels", camera.rtpAudioStatus.channels.map { String($0) } ?? language.string("none"), mono: true)
                        value("rtp_audio_packets", "\(camera.rtpAudioStatus.packetsReceived)", mono: true)
                        value("rtp_audio_access_units", "\(camera.rtpAudioStatus.accessUnitsReceived)", mono: true)
                        value("rtp_audio_decoded", "\(camera.rtpAudioStatus.decodedAccessUnits)", mono: true)
                        value("rtp_audio_played_frames", "\(camera.rtpAudioStatus.playedSampleFrames)", mono: true)
                        value("rtp_audio_dropped", "\(camera.rtpAudioStatus.droppedAccessUnits)", mono: true)
                        value(
                            "rtp_audio_last_packet",
                            camera.rtpAudioStatus.lastPacketAt?.formatted(date: .omitted, time: .standard) ?? language.string("none"),
                            mono: true
                        )
                        value(
                            "rtp_audio_last_pcm",
                            camera.rtpAudioStatus.lastPCMAt?.formatted(date: .omitted, time: .standard) ?? language.string("none"),
                            mono: true
                        )
                        value("rtp_audio_reason", camera.rtpAudioStatus.reason ?? language.string("none"), mono: true)
                        value(
                            "rtp_audio_error",
                            camera.rtpAudioStatus.error ?? language.string("none"),
                            mono: true,
                            warning: camera.rtpAudioStatus.error != nil
                        )
                    }

                    debugSection("platform") {
                        value("operating_system", "iOS")
                        value("local_network", language.string("local_network_permission_managed_by_ios"))
                        value(
                            "ios_usb_ptp",
                            language.string(camera.connectionMode == .desktopBridge ? "usb_via_desktop_bridge" : "research_only"),
                            warning: camera.connectionMode != .desktopBridge
                        )
                    }

                    Button {
                        Task {
                            UIPasteboard.general.string = await camera.diagnosticReport()
                            copied = true
                            try? await Task.sleep(nanoseconds: 1_500_000_000)
                            copied = false
                        }
                    } label: {
                        Label(
                            LocalizedStringKey(copied ? "diagnostic_copied" : "copy_diagnostic"),
                            systemImage: copied ? "checkmark" : "doc.on.doc"
                        )
                            .frame(maxWidth: .infinity, minHeight: 50)
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.roundedRectangle(radius: 6))
                    .tint(copied ? Color.cameraStatus : Color.cameraText)
                    .accessibilityIdentifier("copy-diagnostic-button")
                }
                .padding(16)
                .padding(.bottom, 20)
            }
        }
        .safeAreaPadding(.top, 2)
        .background(Color.cameraBackground)
    }

    private var header: some View {
        HStack(spacing: 6) {
            Button {
                camera.screen = .control
            } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "chevron.left").accessibilityLabel(Text("back_to_camera"))
                }
            }
            .buttonStyle(CameraIconButtonStyle())
            RotatingControl(degrees: controlRotation) {
                Text("debug").font(.headline)
            }
            Spacer()
            Button {
                camera.activeSheet = .language
            } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "globe").accessibilityLabel(Text("language"))
                }
            }
            .buttonStyle(CameraIconButtonStyle())
            Button {
                Task { await camera.refresh() }
            } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "arrow.clockwise").accessibilityLabel(Text("refresh"))
                }
            }
            .buttonStyle(CameraIconButtonStyle())
            .disabled(camera.isPreview || camera.isBusy(.refresh))
            Button {
                Task { await camera.restartLiveView() }
            } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "play.rectangle.on.rectangle").accessibilityLabel(Text("restart_live_view"))
                }
            }
            .buttonStyle(CameraIconButtonStyle())
            .disabled(camera.isPreview || !camera.supports(.liveView) || camera.isBusy(.liveView))
        }
        .foregroundStyle(Color.cameraText)
        .padding(.horizontal, 8)
        .frame(minHeight: 56)
    }

    private func debugSection<Content: View>(
        _ title: LocalizedStringKey,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(title)
                .font(.headline)
                .foregroundStyle(Color.cameraAccent)
            Divider().overlay(Color.cameraBorder)
            content()
        }
    }

    private func value(_ label: LocalizedStringKey, _ value: String, mono: Bool = false, warning: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundStyle(Color.cameraSecondaryText)
            Text(value)
                .font(mono ? .system(.callout, design: .monospaced) : .callout)
                .foregroundStyle(warning ? Color.cameraWarning : Color.cameraText)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func featureList(_ features: Set<CameraFeature>?) -> String {
        features?.map(\.rawValue).sorted().joined(separator: ", ").nilIfBlank ?? language.string("none")
    }

    private func displayedSerial(_ serial: String?) -> String {
        let normalized = serial?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return normalized == nil || normalized == "" || normalized == "unknown" || normalized == "none"
            ? language.string("unknown")
            : language.string("redacted")
    }
}

private extension String {
    var nilIfBlank: String? { isEmpty ? nil : self }
}
