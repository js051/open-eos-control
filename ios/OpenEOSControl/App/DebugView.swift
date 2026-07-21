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
                        value("transport", camera.isPreview ? language.string("offline_preview") : "CCAPI_NETWORK")
                        value("api_version", camera.info?.api ?? "unknown")
                        value("serial_number", camera.info?.serial ?? "unknown", mono: true)
                        value("last_error", camera.lastError ?? language.string("none"), warning: camera.lastError != nil)
                    }

                    debugSection("ccapi") {
                        value("base_url", camera.baseURL, mono: true)
                        value("supported_features", featureList(camera.capabilities?.matrix.supported))
                        value("planned_features", featureList(camera.capabilities?.matrix.planned))
                        value("battery_raw", camera.status?.rawBatteryJSON ?? "null", mono: true)
                        value("storage_raw", camera.status?.rawStorageJSON ?? "null", mono: true)
                    }

                    debugSection("capability_evidence") {
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
                            "evidence_truncated",
                            language.string(camera.capabilities?.evidence.truncated == true ? "yes" : "no"),
                            warning: camera.capabilities?.evidence.truncated == true
                        )
                    }

                    debugSection("live_view") {
                        value("requested_fps", "\(camera.requestedFPS)")
                        value("observed_fps", String(format: "%.1f", camera.observedFPS))
                        value("frame_bytes", "\(camera.frameBytes)")
                        value("content_type", camera.frameContentType ?? language.string("none"))
                        value("source_endpoint", camera.frameSourceURL?.absoluteString ?? language.string("none"), mono: true)
                        value("latest_frame", camera.lastFrameAt?.formatted(date: .abbreviated, time: .standard) ?? language.string("none"))
                    }

                    debugSection("platform") {
                        value("operating_system", "iOS")
                        value("local_network", language.string("local_network_permission_managed_by_ios"))
                        value("ios_usb_ptp", language.string("research_only"), warning: true)
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
}

private extension String {
    var nilIfBlank: String? { isEmpty ? nil : self }
}
