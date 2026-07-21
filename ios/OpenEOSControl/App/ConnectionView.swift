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
                    Text("direct_camera")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.cameraAccent)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                presetControl

                VStack(spacing: 14) {
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

                Button {
                    Task { await camera.connect() }
                } label: {
                    HStack(spacing: 10) {
                        if camera.isBusy(.connect) {
                            ProgressView().tint(Color.cameraBackground)
                        } else {
                            Image(systemName: "wifi")
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
                .disabled(camera.isBusy(.connect) || camera.baseURL.trimmingCharacters(in: .whitespaces).isEmpty)
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
