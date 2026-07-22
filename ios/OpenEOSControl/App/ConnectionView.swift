import SwiftUI

struct ConnectionView: View {
    @EnvironmentObject private var camera: CameraAppState
    @State private var showAuthentication = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                HStack {
                    Image("AppMark")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 46, height: 46)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    Text("app_name")
                        .font(.headline)
                        .foregroundStyle(Color.cameraText)
                    Spacer()
                    Button {
                        camera.activeSheet = .language
                    } label: {
                        Image(systemName: "globe")
                            .accessibilityLabel(Text("language"))
                    }
                    .buttonStyle(CameraIconButtonStyle())
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("connect_title")
                        .font(.system(size: 30, weight: .bold))
                        .foregroundStyle(Color.cameraText)
                    Text(LocalizedStringKey(camera.connectionMode == .ccapi ? "direct_camera" : "usb_via_desktop_bridge"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.cameraAccent)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                connectionModePicker

                if camera.connectionMode == .ccapi {
                    ccapiFields
                } else {
                    bridgeFields
                }

                Button {
                    Task { await camera.connect() }
                } label: {
                    HStack(spacing: 10) {
                        if camera.isBusy(.connect) {
                            ProgressView().tint(Color.cameraBackground)
                        } else {
                            Image(systemName: camera.connectionMode == .ccapi ? "wifi" : "desktopcomputer")
                        }
                        Text(LocalizedStringKey(camera.isBusy(.connect) ? "connecting" : "connect"))
                            .fontWeight(.bold)
                    }
                    .frame(maxWidth: .infinity, minHeight: 54)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: 6))
                .tint(Color.cameraAccent)
                .foregroundStyle(Color.cameraBackground)
                .disabled(camera.isBusy(.connect) || camera.isBusy(.scan) || !camera.canConnect)
                .accessibilityIdentifier("connect-button")

                HStack(spacing: 12) {
                    Rectangle().fill(Color.cameraBorder).frame(height: 1)
                    Text("or")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.cameraSecondaryText)
                    Rectangle().fill(Color.cameraBorder).frame(height: 1)
                }

                Button {
                    camera.openOfflinePreview()
                } label: {
                    Label("preview_interface", systemImage: "eye")
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.roundedRectangle(radius: 6))
                .tint(Color.cameraText)
                .accessibilityIdentifier("offline-preview-button")
            }
            .frame(maxWidth: 560)
            .padding(.horizontal, 20)
            .padding(.vertical, 18)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Color.cameraBackground)
    }

    private var connectionModePicker: some View {
        Picker(
            "connection_method",
            selection: Binding(get: { camera.connectionMode }, set: camera.setConnectionMode)
        ) {
            Text("direct_camera").tag(AppConnectionMode.ccapi)
            Text("desktop_bridge").tag(AppConnectionMode.desktopBridge)
        }
        .pickerStyle(.segmented)
        .frame(minHeight: 44)
        .accessibilityIdentifier("connection-mode-picker")
    }

    private var ccapiFields: some View {
        VStack(spacing: 14) {
            presetControl

            TextField(
                "camera_url",
                text: Binding(get: { camera.baseURL }, set: camera.setBaseURL)
            )
            .textInputAutocapitalization(.never)
            .keyboardType(.URL)
            .autocorrectionDisabled()
            .textContentType(.URL)
            .cameraFieldStyle()
            .accessibilityIdentifier("camera-url-field")

            DisclosureGroup(isExpanded: $showAuthentication) {
                VStack(spacing: 12) {
                    TextField(
                        "username",
                        text: Binding(get: { camera.username }, set: camera.setUsername)
                    )
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.username)
                    .cameraFieldStyle()

                    SecureField("password", text: $camera.password)
                        .textContentType(.password)
                        .cameraFieldStyle()
                }
                .padding(.top, 12)
            } label: {
                Label("authentication", systemImage: "person.badge.key")
                    .foregroundStyle(Color.cameraText)
            }
            .tint(Color.cameraSecondaryText)
        }
    }

    private var bridgeFields: some View {
        VStack(spacing: 14) {
            TextField(
                "desktop_bridge_url",
                text: Binding(get: { camera.bridgeURL }, set: camera.setBridgeURL)
            )
            .textInputAutocapitalization(.never)
            .keyboardType(.URL)
            .autocorrectionDisabled()
            .textContentType(.URL)
            .cameraFieldStyle()
            .accessibilityIdentifier("bridge-url-field")

            SecureField("bearer_token", text: $camera.bridgeToken)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(.password)
                .cameraFieldStyle()
                .accessibilityIdentifier("bridge-token-field")

            Button {
                Task { await camera.scanBridgeCameras() }
            } label: {
                HStack(spacing: 9) {
                    if camera.isBusy(.scan) {
                        ProgressView().tint(Color.cameraText)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                    Text(LocalizedStringKey(camera.isBusy(.scan) ? "scanning_cameras" : "scan_cameras"))
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.bordered)
            .buttonBorderShape(.roundedRectangle(radius: 6))
            .tint(Color.cameraText)
            .disabled(
                camera.isBusy(.scan)
                    || camera.isBusy(.connect)
                    || camera.bridgeURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            )
            .accessibilityIdentifier("bridge-scan-button")

            if camera.bridgeCameras.isEmpty {
                Label("no_bridge_cameras", systemImage: "camera")
                    .font(.callout)
                    .foregroundStyle(Color.cameraSecondaryText)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            } else {
                Picker("bridge_camera", selection: $camera.selectedBridgeCameraID) {
                    Text("select_camera").tag(Optional<String>.none)
                    ForEach(camera.bridgeCameras) { bridgeCamera in
                        Text("\(bridgeCamera.model) | \(bridgeCamera.port)")
                            .lineLimit(1)
                            .tag(Optional(bridgeCamera.id))
                    }
                }
                .pickerStyle(.menu)
                .padding(.horizontal, 12)
                .frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
                .foregroundStyle(Color.cameraText)
                .background(Color.cameraSurface)
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.cameraBorder))
                .accessibilityIdentifier("bridge-camera-picker")
            }
        }
    }

    private var presetControl: some View {
        HStack(spacing: 2) {
            presetButton("preset_http", systemImage: "network", selected: camera.baseURL == CameraAppState.defaultCameraURL) {
                camera.useHTTPPreset()
            }
            presetButton("preset_https", systemImage: "lock", selected: camera.baseURL == CameraAppState.defaultSecureCameraURL) {
                camera.useHTTPSPreset()
            }
            presetButton("preset_simulator", systemImage: "macwindow", selected: camera.baseURL == CameraAppState.simulatorURL) {
                camera.useSimulatorPreset()
            }
        }
        .padding(2)
        .background(Color.cameraSurface)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private func presetButton(
        _ title: LocalizedStringKey,
        systemImage: String,
        selected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: systemImage)
                Text(title).font(.caption.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.75)
            }
            .frame(maxWidth: .infinity, minHeight: 50)
            .foregroundStyle(selected ? Color.cameraBackground : Color.cameraText)
            .background(selected ? Color.cameraText : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 4))
        }
        .buttonStyle(.plain)
    }
}

private extension View {
    func cameraFieldStyle() -> some View {
        self
            .padding(.horizontal, 12)
            .frame(minHeight: 50)
            .foregroundStyle(Color.cameraText)
            .background(Color.cameraSurface)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color.cameraBorder))
    }
}
