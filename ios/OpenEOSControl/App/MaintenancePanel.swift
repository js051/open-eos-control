import SwiftUI
import OpenEOSCore

struct MaintenancePanel: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let current = camera.maintenanceRecord { receipt(current); actions(current) }
            if camera.maintenanceControlsPaused { Text("maintenance_monitoring_paused").foregroundStyle(Color.cameraText) }
            DisclosureGroup("maintenance_history") {
                ForEach(camera.maintenanceHistory.reversed()) { record in
                    receipt(record)
                    if record.id != camera.maintenanceCurrentID { actions(record) }
                    Divider()
                }
            }
        }
        .font(.caption)
        .foregroundStyle(Color.cameraSecondaryText)
        .accessibilityIdentifier("maintenance-outcome")
    }
    @ViewBuilder private func actions(_ record: MaintenanceRecord) -> some View {
        if record.running && !record.retired {
            HStack {
                ProgressView()
                Text(LocalizedStringKey(record.restoring ? "maintenance_restoring" : record.reading ? "maintenance_reading" : "maintenance_working"))
                Button("cancel") { camera.cancelMaintenance() }.accessibilityIdentifier("maintenance-cancel")
            }
        } else {
            HStack {
                Button("maintenance_observe") { Task { await camera.recoverMaintenance(record.id) } }
                    .disabled(!camera.canRecoverMaintenance(record.id))
                    .accessibilityIdentifier("maintenance-observe-\(record.id.uuidString)")
                if record.outcome.operation == .sensorCleaning && !record.outcome.autoPowerOff {
                    Button("maintenance_restore") { Task { await camera.restoreMaintenance(record.id) } }
                        .disabled(!camera.canRestoreMaintenance(record.id))
                        .accessibilityIdentifier("maintenance-restore-\(record.id.uuidString)")
                }
            }
            .buttonStyle(.bordered)
        }
    }
    private func receipt(_ record: MaintenanceRecord) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(LocalizedStringKey(record.titleKey)).fontWeight(.semibold)
            Text(LocalizedStringKey(record.commandKey)).foregroundStyle(Color.cameraText)
            Text(language.format("maintenance_stop_phase", language.string("maintenance_stop_" + record.outcome.stopEvidence.rawValue)))
            if record.outcome.operation == .clockSync {
                Text(language.format("maintenance_clock_proof", language.string("maintenance_observation_" + record.outcome.clockReadEvidence.rawValue)))
                if let clock = record.outcome.verifiedClock {
                    Text(language.format("maintenance_clock_sample", clock.date.formatted(), zone(clock)))
                }
            }
            Text(language.format("maintenance_latest_read", language.string("maintenance_observation_" + record.outcome.observationEvidence.rawValue)))
            if let sample = record.outcome.observedClock { Text(language.format("maintenance_clock_sample", sample.date.formatted(), zone(sample))) }
            if record.outcome.operation != .clockSync { Text("maintenance_not_physical_completion") }
            Text(language.format("maintenance_restore_phase", language.string("maintenance_restore_" + record.outcome.restoreEvidence.rawValue)))
            if record.cancelled { Text("maintenance_cancelled") }
            if record.retired { Text("maintenance_retired") }
        }
        .accessibilityElement(children: .combine)
    }
    private func zone(_ clock: CameraMaintenanceClock) -> String {
        guard let offset = clock.utcOffsetMinutes, let dst = clock.daylightSaving else { return language.string("maintenance_zone_unavailable") }
        return "UTC \(offset >= 0 ? "+" : "−")\(abs(offset) / 60):\(String(format: "%02d", abs(offset) % 60)); DST \(dst ? "on" : "off")"
    }
}
