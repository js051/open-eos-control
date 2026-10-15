import SwiftUI

struct ConnectionView: View {
    @EnvironmentObject private var camera: CameraAppState
    @State private var showAuthentication = false
    @FocusState private var focusedField: Field?

    private enum Field: Hashable { case cameraURL, cameraUsername, cameraPassword, bridgeURL, bridgeToken }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
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

                connectionGuidance

                if camera.connectionMode == .ccapi {
                    ccapiFields
                } else {
                    bridgeFields
                }

                if let recovery = camera.connectionRecovery {
                    recoveryGuidance(recovery)
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
                        Text(LocalizedStringKey(camera.connectionIsWaitingForCleanup ? "connection_waiting_cleanup" :
                            camera.isBusy(.connect) ? "connecting" :
                            camera.connectionWasCancelled || camera.connectionRecovery?.operation == .connect ? "connection_retry_connect" : "connect"))
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

                if camera.canCancelConnection {
                    Button("connection_cancel", role: .cancel) { camera.cancelConnection() }
                        .buttonStyle(.bordered)
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("connection-cancel-button")
                }
                if camera.connectionWasCancelled {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("connection_cancelled_help")
                            .font(.callout)
                            .accessibilityIdentifier("connection-cancelled-message")
                        Button("connection_edit_settings") {
                            focusedField = camera.connectionMode == .ccapi ? .cameraURL : .bridgeURL
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("connection-edit-settings-button")
                    }
                    .foregroundStyle(Color.cameraSecondaryText)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

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

                Button {
                    camera.activeSheet = .deliveryHistory
                } label: {
                    Label("media_delivery_history_title", systemImage: "clock.arrow.circlepath")
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.roundedRectangle(radius: 6))
                .tint(Color.cameraText)
                .accessibilityIdentifier("connection-delivery-history-button")
            }
            .frame(maxWidth: 560)
            .padding(.horizontal, 20)
            .padding(.vertical, 18)
            .frame(maxWidth: .infinity)
        }
        .accessibilityIdentifier("connection-scroll-view")
        .scrollDismissesKeyboard(.interactively)
        .background(Color.cameraBackground)
        .onChange(of: camera.connectionRecovery) { _, recovery in
            if recovery?.needsCameraAuthentication == true { showAuthentication = true }
        }
        .onAppear {
            if camera.connectionRecovery?.needsCameraAuthentication == true { showAuthentication = true }
        }
    }

    private var connectionGuidance: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 6) {
                Text(LocalizedStringKey(camera.connectionMode == .ccapi ? "connection_camera_setup" : "connection_bridge_setup"))
                Text(LocalizedStringKey(camera.connectionMode == .ccapi ? "connection_camera_pairing_help" : "connection_ios_usb_limit"))
            }
            .padding(.top, 8)
        } label: {
            Text(LocalizedStringKey(camera.connectionMode == .ccapi ? "connection_camera_setup_title" : "connection_bridge_setup_title"))
                .fontWeight(.semibold)
        }
        .font(.footnote)
        .foregroundStyle(Color.cameraSecondaryText)
        .tint(Color.cameraSecondaryText)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("connection-setup-guidance")
    }

    private func recoveryGuidance(_ recovery: ConnectionRecovery) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("connection_recovery_title", systemImage: "exclamationmark.circle")
                .font(.subheadline.weight(.semibold))
            Text(LocalizedStringKey(recovery.messageKey))
                .font(.callout)
                .accessibilityIdentifier("connection-recovery-message")
            Text(LocalizedStringKey(recovery.nextStepKey))
                .font(.footnote)
            if recovery.canEditLocalSettings {
                Button("connection_edit_settings") {
                    if recovery.needsCameraAuthentication {
                        showAuthentication = true
                        focusedField = .cameraUsername
                    } else if recovery.mode == .desktopBridge && recovery.reason == .authenticationRejected {
                        focusedField = .bridgeToken
                    } else {
                        focusedField = recovery.mode == .ccapi ? .cameraURL : .bridgeURL
                    }
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("connection-edit-settings-button")
            }
        }
        .foregroundStyle(Color.cameraWarning)
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cameraSurface)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        // Keep the message and edit action as distinct accessible children;
        // the group's identifier must not replace their leaf identifiers.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("connection-recovery-guidance")
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
        .disabled(camera.isBusy(.connect))
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
            .focused($focusedField, equals: .cameraURL)

            DisclosureGroup(isExpanded: $showAuthentication) {
                VStack(spacing: 12) {
                    Text("connection_camera_auth_help")
                        .font(.footnote)
                        .foregroundStyle(Color.cameraSecondaryText)
                    TextField(
                        "username",
                        text: Binding(get: { camera.username }, set: camera.setUsername)
                    )
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.username)
                    .cameraFieldStyle()
                    .accessibilityIdentifier("camera-username-field")
                    .focused($focusedField, equals: .cameraUsername)

                    SecureField("password", text: $camera.password)
                        .textContentType(.password)
                        .cameraFieldStyle()
                        .accessibilityIdentifier("camera-password-field")
                        .focused($focusedField, equals: .cameraPassword)
                }
                .padding(.top, 12)
            } label: {
                Label("authentication", systemImage: "person.badge.key")
                    .foregroundStyle(Color.cameraText)
            }
            .tint(Color.cameraSecondaryText)
            .accessibilityIdentifier("camera-authentication-disclosure")
        }
        .disabled(camera.isBusy(.connect))
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
            .focused($focusedField, equals: .bridgeURL)

            SecureField("bearer_token", text: $camera.bridgeToken)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(.password)
                .cameraFieldStyle()
                .accessibilityIdentifier("bridge-token-field")
                .focused($focusedField, equals: .bridgeToken)

            Text("connection_bridge_token_help")
                .font(.footnote)
                .foregroundStyle(Color.cameraSecondaryText)
                .frame(maxWidth: .infinity, alignment: .leading)

            Button {
                Task { await camera.scanBridgeCameras() }
            } label: {
                HStack(spacing: 9) {
                    if camera.isBusy(.scan) {
                        ProgressView().tint(Color.cameraText)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                    Text(LocalizedStringKey(camera.isBusy(.scan) ? "scanning_cameras" :
                        camera.bridgeScanState == .failed ? "connection_retry_scan" : "scan_cameras"))
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.bordered)
            .buttonBorderShape(.roundedRectangle(radius: 6))
            .tint(Color.cameraText)
            .disabled(!camera.canScanBridgeCameras)
            .accessibilityIdentifier("bridge-scan-button")

            if camera.bridgeScanState != .completed || camera.bridgeCameras.isEmpty {
                Label(LocalizedStringKey(bridgeScanMessageKey), systemImage: "camera")
                    .font(.callout)
                    .foregroundStyle(Color.cameraSecondaryText)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .accessibilityIdentifier("bridge-scan-status")
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
        .disabled(camera.isBusy(.connect))
    }

    private var bridgeScanMessageKey: String {
        switch camera.bridgeScanState {
        case .notScanned: "connection_bridge_unscanned"
        case .scanning: "scanning_cameras"
        case .failed: "connection_bridge_scan_failed"
        case .completed: "connection_bridge_empty"
        }
    }

    private var presetControl: some View {
        HStack(spacing: 2) {
            presetButton(
                "preset_http",
                systemImage: "network",
                identifier: "preset-http-button",
                selected: camera.baseURL == CameraAppState.defaultCameraURL
            ) {
                camera.useHTTPPreset()
            }
            presetButton(
                "preset_https",
                systemImage: "lock",
                identifier: "preset-https-button",
                selected: camera.baseURL == CameraAppState.defaultSecureCameraURL
            ) {
                camera.useHTTPSPreset()
            }
            presetButton(
                "preset_simulator",
                systemImage: "macwindow",
                identifier: "preset-simulator-button",
                selected: camera.baseURL == CameraAppState.simulatorURL
            ) {
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
        identifier: String,
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
        .accessibilityIdentifier(identifier)
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
