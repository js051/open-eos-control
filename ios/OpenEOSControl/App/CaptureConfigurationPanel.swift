import SwiftUI
import OpenEOSCore

/// One logical write, two separate facts, and an explicitly read-only next step.
struct CaptureConfigurationPanel: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let record = camera.captureConfigurationRecord {
                receipt(record)
                if record.running {
                    HStack {
                        ProgressView()
                        Text(LocalizedStringKey(record.reading ? "capture_config_reading" : "capture_config_working"))
                        Spacer()
                        Button("cancel") { camera.cancelCaptureConfiguration() }
                            .accessibilityIdentifier("capture-config-cancel")
                    }
                } else {
                    HStack {
                        Button("capture_config_refresh") { Task { await camera.recoverCaptureConfiguration() } }
                            .disabled(!camera.canRecoverCaptureConfiguration)
                            .accessibilityIdentifier("capture-config-refresh")
                        Button("capture_config_new_request") { camera.newCaptureConfigurationDraft() }
                            .disabled(!camera.canPrepareCaptureConfigurationDraft)
                            .accessibilityIdentifier("capture-config-new-request")
                    }
                    .buttonStyle(.bordered)
                    Text("capture_config_new_request_hint").font(.caption)
                }
            }
            if !camera.captureConfigurationHistory.isEmpty {
                DisclosureGroup("capture_config_history") {
                    ForEach(camera.captureConfigurationHistory.reversed()) { record in
                        receipt(record)
                        if record.running && record.reading {
                            HStack {
                                ProgressView()
                                Text("capture_config_reading")
                                Button("cancel") { camera.cancelCaptureConfiguration() }
                            }
                        } else {
                            Button("capture_config_refresh") {
                                Task { await camera.recoverCaptureConfiguration(record.id) }
                            }
                            .disabled(!camera.canRecoverCaptureConfiguration(record.id))
                            .accessibilityIdentifier("capture-config-history-refresh-\(record.id.uuidString)")
                        }
                        Divider()
                    }
                }
                .accessibilityIdentifier("capture-config-history")
            }
        }
        .font(.caption)
        .foregroundStyle(Color.cameraSecondaryText)
        .accessibilityIdentifier("capture-config-outcome")
    }

    private func receipt(_ record: CaptureConfigurationRecord) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(LocalizedStringKey(record.outcome.operation == .createDirectory ? "create_capture_directory" : "file_naming"))
                .fontWeight(.semibold)
            Text(language.format("capture_config_requested", record.outcome.requestedValue.isEmpty
                ? language.string("capture_config_automatic") : record.outcome.requestedValue))
            if let field = record.outcome.field { Text(verbatim: field.rawValue) }
            if !record.running || record.outcome.writeEvidence != .notSent {
                Text(LocalizedStringKey(record.statusKey)).foregroundStyle(Color.cameraText)
            }
            if !record.running, record.outcome.writeEvidence != .acknowledged {
                Text(LocalizedStringKey(record.readbackKey))
            }
            if let acknowledged = record.outcome.acknowledgedValue, record.outcome.operation == .createDirectory {
                Text(language.format("capture_config_suffix", acknowledged))
                Text("capture_config_suffix_limits")
            }
            if let selection = record.outcome.directorySelection, record.outcome.readbackEvidence == .refreshed {
                Text(language.format("capture_config_current_directory", selection.value))
            }
            if let naming = record.outcome.fileNaming, let field = record.outcome.field,
               record.outcome.readbackEvidence == .matched || record.outcome.readbackEvidence == .mismatch {
                Text(language.format("capture_config_current_value", naming.value(for: field)))
            }
            if record.cancelled { Text("capture_config_cancelled") }
            if record.retired { Text("capture_config_original_owner") }
        }
        .accessibilityElement(children: .combine)
    }
}
