import Foundation
import OpenEOSCore

struct MaintenanceConfirmation {
    let owner: UUID
    let configurationOwner: UUID
    let operation: CameraMaintenanceOperation
}
struct MaintenanceRecord: Identifiable {
    let id = UUID()
    let owner: UUID
    let configurationOwner: UUID
    let backend: String
    let hadLiveView: Bool
    var outcome: CameraMaintenanceOutcome
    var running = true
    var reading = false
    var restoring = false
    var cancelled = false
    var retired = false
    var titleKey: String {
        switch outcome.operation {
        case .clockSync: "sync_camera_clock"
        case .sensorCleaning: outcome.autoPowerOff ? "sensor_cleaning_power_off" : "sensor_cleaning_keep_power"
        case .sleep: "camera_sleep"
        }
    }
    var commandKey: String {
        switch outcome.commandEvidence {
        case .notSent: "maintenance_not_sent"
        case .unknown: "maintenance_unknown"
        case .responded: "maintenance_response_received"
        }
    }
}
