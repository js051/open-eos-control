import Foundation
import CoreFoundation

/// A complete successful transport response is not proof of the current setting.
public enum CameraShootingSettingWriteEvidence: String, Sendable {
    case notSent = "not-sent", unknown, responded = "response-received"
}
public enum CameraShootingSettingReadbackEvidence: String, Sendable {
    case notRead = "not-read", unavailable, matched, mismatch
}
public struct CameraShootingSettingOutcome: Equatable, Sendable {
    public let key: String
    public let requestedValue: String
    public fileprivate(set) var writeEvidence = CameraShootingSettingWriteEvidence.notSent
    public fileprivate(set) var readbackEvidence = CameraShootingSettingReadbackEvidence.notRead
    public fileprivate(set) var observedSetting: CameraSetting?
}
struct CameraShootingSettingRoute: Sendable {
    let writePath: String
    let path: String
    let baseKey: String
    let field: String?
    let aggregate: Bool
    let simulator: Bool
}
public final class CameraShootingSettingAttempt: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private var outcome: CameraShootingSettingOutcome
    private var route: CameraShootingSettingRoute?
    public init(key: String, requestedValue: String) {
        outcome = CameraShootingSettingOutcome(key: key, requestedValue: requestedValue)
    }
    public var snapshot: CameraShootingSettingOutcome {
        lock.lock(); defer { lock.unlock() }; return outcome
    }
    public func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    func validate(key: String, value: String) throws {
        lock.lock(); defer { lock.unlock() }
        guard !cancelled, outcome.writeEvidence == .notSent,
              outcome.key.utf8.elementsEqual(key.utf8),
              outcome.requestedValue.utf8.elementsEqual(value.utf8) else { throw CancellationError() }
    }
    func configure(_ route: CameraShootingSettingRoute) throws {
        lock.lock(); defer { lock.unlock() }
        guard !cancelled, outcome.writeEvidence == .notSent else { throw CancellationError() }
        if let original = self.route {
            guard original.writePath == route.writePath, original.path == route.path else { throw CancellationError() }
        } else { self.route = route }
    }
    var readRoute: CameraShootingSettingRoute? {
        lock.lock(); defer { lock.unlock() }; return route
    }
    func markDispatched() throws {
        try Task.checkCancellation()
        lock.lock(); defer { lock.unlock() }
        guard !cancelled, outcome.writeEvidence == .notSent else { throw CancellationError() }
        outcome.writeEvidence = .unknown
    }
    func responded() {
        lock.lock(); defer { lock.unlock() }
        if outcome.writeEvidence == .unknown { outcome.writeEvidence = .responded }
    }
    public func readbackUnavailable() {
        lock.lock(); defer { lock.unlock() }
        outcome.readbackEvidence = .unavailable
        outcome.observedSetting = nil
    }
    func observe(_ setting: CameraSetting) {
        lock.lock(); defer { lock.unlock() }
        guard setting.key.utf8.elementsEqual(outcome.key.utf8) else { return }
        outcome.observedSetting = setting
        outcome.readbackEvidence = setting.value.utf8.elementsEqual(outcome.requestedValue.utf8) ? .matched : .mismatch
    }
}

public enum CameraShootingSettingPolicy {
    public static func canPublish(sameOwner: Bool, sameAttempt: Bool, sameObservation: Bool, retired: Bool, cancelled: Bool) -> Bool {
        sameOwner && sameAttempt && sameObservation && !retired && !cancelled
    }
    public static func canConsumeDraft(sameOwner: Bool, sameAttempt: Bool, sameDraftRevision: Bool,
                                       responded: Bool, matched: Bool, retired: Bool, cancelled: Bool) -> Bool {
        sameOwner && sameAttempt && sameDraftRevision && responded && matched && !retired && !cancelled
    }
    public static func canRecover(sameOwner: Bool, writeActive: Bool, readActive: Bool, hasRecord: Bool) -> Bool {
        sameOwner && !writeActive && !readActive && hasRecord
    }
}

/// Strict exact-key read envelope. No trimming, coercion, or future input kinds.
enum CameraShootingSettingValue {
    static func validString(_ value: String) -> Bool {
        value.unicodeScalars.count <= 512 && value.unicodeScalars.allSatisfy {
            $0.properties.generalCategory != .control && !(0xD800...0xDFFF).contains($0.value)
        }
    }
    static func validKey(_ key: String) -> Bool {
        let bytes = Array(key.utf8)
        return (1...64).contains(bytes.count) && (97...122).contains(bytes[0])
            && bytes.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || [46, 95, 45].contains($0) }
    }
    static func parse(_ raw: Any?, key: String) -> CameraSetting? {
        guard validKey(key), let body = raw as? [String: Any], let actualKey = body["key"] as? String,
              actualKey.utf8.elementsEqual(key.utf8),
              let label = body["label"] as? String, validString(label),
              let value = body["value"] as? String, validString(value),
              let values = body["values"] as? [String], values.count <= 1024,
              values.allSatisfy({ !$0.isEmpty && validString($0) }),
              Set(values.map { Data($0.utf8) }).count == values.count,
              let rawKind = body["inputKind"] as? String,
              let kind = CameraSettingInputKind(rawValue: rawKind) else { return nil }
        let maxLength: Int?
        if kind == .text {
            guard values.isEmpty, let number = body["maxLength"] as? NSNumber,
                  CFGetTypeID(number) != CFBooleanGetTypeID(),
                  ["c", "s", "i", "l", "q", "C", "S", "I", "L", "Q"].contains(String(cString: number.objCType)),
                  (1...255).contains(number.intValue) else { return nil }
            maxLength = number.intValue
        } else {
            guard body["maxLength"] == nil || body["maxLength"] is NSNull,
                  !values.isEmpty, values.contains(where: { $0.utf8.elementsEqual(value.utf8) }) else { return nil }
            maxLength = nil
        }
        let setting = CameraSetting(key: key, label: label, value: value, values: values, inputKind: kind, maxLength: maxLength)
        return setting.accepts(value) ? setting : nil
    }
}

extension CameraCapabilities {
    public func applyingShootingSetting(_ setting: CameraSetting) -> CameraCapabilities {
        var updated = settings
        guard let index = updated.firstIndex(where: { $0.key == setting.key }) else { return self }
        updated[index] = setting
        return CameraCapabilities(settings: updated, fileNaming: fileNaming, matrix: matrix, liveView: liveView,
            profile: profile, evidence: evidence, shutterAutofocusSupported: shutterAutofocusSupported,
            heldAutofocusSupported: heldAutofocusSupported)
    }
}

/// Bridge response fields bind evidence to the frozen invocation and actual backend.
enum CameraShootingSettingWire {
    static func validEnvelope(_ body: [String: Any], engine: String?) -> Bool {
        guard let engine, !engine.isEmpty, body["engine"] as? String == engine,
              let version = body["version"] as? NSNumber,
              CFGetTypeID(version) != CFBooleanGetTypeID(),
              ["c", "s", "i", "l", "q", "C", "S", "I", "L", "Q"].contains(String(cString: version.objCType)),
              version.intValue == 1 else { return false }
        return true
    }
    static func observe(_ body: [String: Any], engine: String?, attempt: CameraShootingSettingAttempt) -> Bool {
        let outcome = attempt.snapshot
        attempt.readbackUnavailable()
        guard CameraShootingSettingValue.validKey(outcome.key), CameraShootingSettingValue.validString(outcome.requestedValue),
              validEnvelope(body, engine: engine),
              let key = body["key"] as? String, key.utf8.elementsEqual(outcome.key.utf8),
              let requested = body["requestedValue"] as? String, requested.utf8.elementsEqual(outcome.requestedValue.utf8),
              body["writeEvidence"] as? String == "response-received" else { return false }
        attempt.responded()
        attempt.readbackUnavailable()
        guard let read = body["readbackEvidence"] as? String, ["matched", "mismatch"].contains(read) else { return true }
        guard let observed = CameraShootingSettingValue.parse(body["setting"], key: outcome.key),
              (observed.value.utf8.elementsEqual(outcome.requestedValue.utf8) ? "matched" : "mismatch") == read else { return true }
        attempt.observe(observed)
        return true
    }
}

func decodeShootingSettingJSONObject(_ data: Data) throws -> [String: Any] {
    guard data.count <= 64 * 1024, String(data: data, encoding: .utf8) != nil else {
        throw CCAPIError.invalidResponse("Setting evidence must be bounded UTF-8 JSON.")
    }
    return try decodeForegroundJSONObject(data)
}
