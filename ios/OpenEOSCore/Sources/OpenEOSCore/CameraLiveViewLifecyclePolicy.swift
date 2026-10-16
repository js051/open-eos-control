import Foundation
import CoreFoundation

/// Evidence policy only. Receivers/decoders supply the facts; this cannot create them.
public enum CameraLiveViewLifecyclePolicy {
    public static func evaluate(_ input: [String: Any]) -> [String: Bool] {
        func flag(_ key: String) -> Bool {
            guard let value = input[key] as? NSNumber,
                  CFGetTypeID(value) == CFBooleanGetTypeID() else { return false }
            return value.boolValue
        }
        let alive = flag("sameOwner") && flag("sameAttempt") && !flag("cancelled") && !flag("retired")
            && (!flag("foregroundRequired") || flag("foreground"))
        let admitted = flag("startAdmitted"), terminal = flag("terminal")
        let stop = input["stopEvidence"] as? String
        let frame = ["encoded-frame": 1, "decoded-frame": 2, "presented-frame": 3][input["frameEvidence"] as? String ?? ""] ?? 0
        let ready = alive && admitted && flag("startAcknowledged") && !terminal && stop == "pending"
        return ["canPublishInputReady": ready && frame >= 1,
                "canPublishDecoded": ready && frame >= 2,
                "canPublishPresented": ready && frame >= 3,
                "canFallback": alive && terminal && input["sourceMode"] as? String == "AUTO"
                    && (stop == "confirmed" || (!admitted && stop == "not-needed")),
                "stopRequired": admitted && stop != "confirmed"]
    }
}
