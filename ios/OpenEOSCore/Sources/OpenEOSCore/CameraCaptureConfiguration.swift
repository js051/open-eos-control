import Foundation
import CoreFoundation

public enum CameraCaptureConfigurationOperation: String, Sendable {
    case createDirectory = "create-directory"
    case setFileNaming = "set-file-naming"
}

public enum CameraCaptureConfigurationWriteEvidence: String, Sendable {
    case notSent = "not-sent", unknown, acknowledged
}

public enum CameraCaptureConfigurationReadbackEvidence: String, Sendable {
    case notRead = "not-read", unavailable, mismatch, matched, refreshed
}

/// Evidence for one frozen invocation. A directory ACK is only a five-character
/// suffix, never proof of the numbered folder or storage slot that was created.
public struct CameraCaptureConfigurationOutcome: Equatable, Sendable {
    public let operation: CameraCaptureConfigurationOperation
    public let field: CameraFileNamingField?
    public let requestedValue: String
    public fileprivate(set) var writeEvidence = CameraCaptureConfigurationWriteEvidence.notSent
    public fileprivate(set) var readbackEvidence = CameraCaptureConfigurationReadbackEvidence.notRead
    public fileprivate(set) var acknowledgedValue: String?
    public fileprivate(set) var fileNaming: CameraFileNaming?
    public fileprivate(set) var directorySelection: CameraSetting?
}

/// A single write admission plus independent, read-only observations. The lock
/// lets cancellation retire dispatch immediately, without erasing a late ACK.
public final class CameraCaptureConfigurationAttempt: @unchecked Sendable {
    private let lock = NSLock()
    private var retired = false
    private var outcome: CameraCaptureConfigurationOutcome
    public init(operation: CameraCaptureConfigurationOperation, requestedValue: String,
                field: CameraFileNamingField? = nil) {
        outcome = CameraCaptureConfigurationOutcome(operation: operation, field: field, requestedValue: requestedValue)
    }
    public var snapshot: CameraCaptureConfigurationOutcome {
        lock.lock(); defer { lock.unlock() }; return outcome
    }
    public func cancel() { lock.lock(); retired = true; lock.unlock() }
    func validate(operation: CameraCaptureConfigurationOperation, value: String,
                  field: CameraFileNamingField? = nil) throws {
        lock.lock(); defer { lock.unlock() }
        guard !retired, outcome.operation == operation, outcome.field == field,
              outcome.requestedValue == value, outcome.writeEvidence == .notSent else { throw CancellationError() }
    }
    func markDispatched() throws {
        try Task.checkCancellation()
        lock.lock(); defer { lock.unlock() }
        guard !retired, outcome.writeEvidence == .notSent else { throw CancellationError() }
        outcome.writeEvidence = .unknown
    }
    func acknowledge(_ value: String) {
        lock.lock(); defer { lock.unlock() }
        guard outcome.writeEvidence == .unknown else { return }
        outcome.writeEvidence = .acknowledged
        outcome.acknowledgedValue = value
    }
    public func readbackUnavailable() {
        lock.lock(); defer { lock.unlock() }
        outcome.readbackEvidence = .unavailable
    }
    func observe(_ naming: CameraFileNaming) {
        lock.lock(); defer { lock.unlock() }
        guard let field = outcome.field else { return }
        outcome.fileNaming = naming
        outcome.readbackEvidence = naming.value(for: field) == outcome.requestedValue ? .matched : .mismatch
    }
    func observe(_ selection: CameraSetting) {
        lock.lock(); defer { lock.unlock() }
        outcome.directorySelection = selection
        outcome.readbackEvidence = .refreshed
    }
}

extension CameraCapabilities {
    /// Merge only the validated capture configuration observation, preserving
    /// every unrelated capability and the acknowledged/readback distinction.
    public func applyingCaptureConfiguration(_ outcome: CameraCaptureConfigurationOutcome) -> CameraCapabilities {
        var updatedSettings = settings
        if let selection = outcome.directorySelection,
           outcome.readbackEvidence == .refreshed {
            updatedSettings.removeAll { $0.key == selection.key }
            updatedSettings.append(selection)
        }
        let freshNaming = [CameraCaptureConfigurationReadbackEvidence.matched, .mismatch, .refreshed].contains(outcome.readbackEvidence)
            ? outcome.fileNaming : nil
        return CameraCapabilities(settings: updatedSettings, fileNaming: freshNaming ?? fileNaming,
                                  matrix: matrix, liveView: liveView, profile: profile, evidence: evidence,
                                  shutterAutofocusSupported: shutterAutofocusSupported,
                                  heldAutofocusSupported: heldAutofocusSupported)
    }
}

/// Closed post-ACK error extension. Extra envelope fields carry no authority.
/// The original failure stays a failure; this only preserves its validated ACK.
enum CameraCaptureConfigurationReceipt {
    static func validate(status: Int, detail: [String: Any],
                         invocation: CameraCaptureConfigurationOutcome) -> String? {
        guard status == 502, detail["code"] as? String == "CAPTURE_CONFIG_READBACK_FAILED",
              detail["engine"] as? String == "ccapi",
              detail["feature"] as? String == (invocation.operation == .createDirectory ? "DIRECTORY_CONTROL" : "FILE_NAMING_CONTROL"),
              let receipt = detail["captureConfigReceipt"] as? [String: Any],
              let version = receipt["version"] as? NSNumber,
              CFGetTypeID(version) != CFBooleanGetTypeID(), version.doubleValue == 1,
              receipt["operation"] as? String == invocation.operation.rawValue,
              receipt["stage"] as? String == "acknowledged",
              let requested = receipt["requestedValue"] as? String,
              requested.utf8.elementsEqual(invocation.requestedValue.utf8), requested.utf8.count <= 32,
              let acknowledged = receipt["acknowledgedValue"] as? String, acknowledged.utf8.count <= 32 else { return nil }
        if invocation.operation == .createDirectory {
            guard receipt["field"] == nil,
                  CameraCaptureConfigurationValue.directorySuffix(requested, allowEmpty: true),
                  CameraCaptureConfigurationValue.directorySuffix(acknowledged) else { return nil }
        } else {
            guard let field = invocation.field, receipt["field"] as? String == field.rawValue,
                  acknowledged.utf8.elementsEqual(requested.utf8),
                  CameraCaptureConfigurationValue.naming(field, value: acknowledged) else { return nil }
        }
        return acknowledged
    }
}

public enum CameraCaptureConfigurationPolicy {
    public static func canPublish(sameOwner: Bool, sameAttempt: Bool, retired: Bool) -> Bool {
        sameOwner && sameAttempt && !retired
    }
    public static func canConsumeDraft(sameOwner: Bool, sameAttempt: Bool, retired: Bool,
                                       sameDraftRevision: Bool, acknowledged: Bool,
                                       matched: Bool, cancelled: Bool) -> Bool {
        canPublish(sameOwner: sameOwner, sameAttempt: sameAttempt, retired: retired)
            && sameDraftRevision && acknowledged && matched && !cancelled
    }
    public static func canRecover(sameOwner: Bool, writeActive: Bool, readActive: Bool, hasRecord: Bool) -> Bool {
        sameOwner && !writeActive && !readActive && hasRecord
    }
}

/// Exact ASCII checks: Foundation's `$` also matches before a terminal newline.
enum CameraCaptureConfigurationValue {
    static func directorySuffix(_ value: String, allowEmpty: Bool = false) -> Bool {
        let bytes = Array(value.utf8)
        return (allowEmpty && bytes.isEmpty) || (bytes.count == 5 && bytes.allSatisfy(asciiCharacter))
    }
    static func directorySelection(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        return bytes.count == 8 && bytes.prefix(3).allSatisfy { (48...57).contains($0) }
            && bytes.suffix(5).allSatisfy(asciiCharacter)
    }
    static func asciiCharacter(_ byte: UInt8) -> Bool {
        (65...90).contains(byte) || (48...57).contains(byte) || byte == 95
    }
    static func exactPattern(_ value: String, _ pattern: String) -> Bool {
        value.range(of: pattern, options: .regularExpression) == (value.startIndex..<value.endIndex)
    }
    static func naming(_ field: CameraFileNamingField, value: String) -> Bool {
        switch field {
        case .stillFilenameMode: return ["preset_code", "usersetting1", "usersetting2"].contains(value)
        case .stillUserSetting1: return exactPattern(value, #"^[A-Z0-9][A-Z0-9_]{3}$"#)
        case .stillUserSetting2: return exactPattern(value, #"^[A-Z0-9][A-Z0-9_]{2}$"#)
        case .movieIndex: return exactPattern(value, #"^[A-Z0-9][A-Z0-9_]$"#)
        case .movieReelNumber: return CameraIntegerRange(minimum: 1, maximum: 9999, step: 1).accepts(value)
        case .movieClipNumber: return CameraIntegerRange(minimum: 1, maximum: 999, step: 1).accepts(value)
        case .movieUserDefined: return exactPattern(value, #"^[A-Z0-9]{5}$"#)
        }
    }
}
