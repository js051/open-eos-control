import SwiftUI
import OpenEOSCore

struct ShootingSettingPanel: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let record = camera.shootingSettingRecord {
                receipt(record)
                if record.running {
                    HStack {
                        ProgressView()
                        Text(LocalizedStringKey(record.reading ? "shooting_setting_reading" : "shooting_setting_working"))
                        Button("cancel") { camera.cancelShootingSetting() }
                            .accessibilityIdentifier("shooting-setting-cancel")
                    }
                } else {
                    HStack {
                        Button("shooting_setting_refresh") { Task { await camera.recoverShootingSetting() } }
                            .disabled(!camera.canRecoverShootingSetting(record.id))
                            .accessibilityIdentifier("shooting-setting-refresh")
                        Button("shooting_setting_new") { camera.newShootingSettingDraft() }
                            .disabled(!camera.canPrepareShootingSettingDraft)
                            .accessibilityIdentifier("shooting-setting-new")
                    }
                    .buttonStyle(.bordered)
                    Text("shooting_setting_no_replay")
                }
            }
            if !camera.shootingSettingHistory.isEmpty {
                DisclosureGroup("shooting_setting_history") {
                    ForEach(camera.shootingSettingHistory.reversed()) { record in
                        receipt(record)
                        if record.running && record.reading {
                            Button("cancel") { camera.cancelShootingSetting() }
                        } else {
                            Button("shooting_setting_refresh") { Task { await camera.recoverShootingSetting(record.id) } }
                                .disabled(!camera.canRecoverShootingSetting(record.id))
                        }
                        Divider()
                    }
                }
            }
        }
        .font(.caption)
        .foregroundStyle(Color.cameraSecondaryText)
        .accessibilityIdentifier("shooting-setting-outcome")
    }
    private func receipt(_ record: ShootingSettingRecord) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: record.outcome.key).fontWeight(.semibold)
            Text(language.format("shooting_setting_requested", record.outcome.requestedValue))
            if !record.running || record.reading {
                Text(LocalizedStringKey(record.statusKey)).foregroundStyle(Color.cameraText)
                Text(LocalizedStringKey(record.readbackKey))
            }
            if let observed = record.outcome.observedSetting {
                Text(language.format("shooting_setting_observed", observed.value))
            }
            if record.cancelled { Text("shooting_setting_cancelled") }
            if record.retired { Text("shooting_setting_retired") }
            if record.superseded { Text("shooting_setting_superseded") }
        }
        .accessibilityElement(children: .combine)
    }
}
