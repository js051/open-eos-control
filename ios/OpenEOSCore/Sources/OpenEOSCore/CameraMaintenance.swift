import Foundation
import CoreFoundation

public enum CameraMaintenanceOperation: String, Sendable {
    case clockSync = "clock-sync", sensorCleaning = "sensor-cleaning", sleep
    public var feature: CameraFeature {
        switch self { case .clockSync: .cameraClockSync; case .sensorCleaning: .sensorCleaning; case .sleep: .cameraSleep }
    }
}
public enum CameraMaintenanceCommandEvidence: String, Sendable {
    case notSent = "not-sent", unknown, responded = "response-received"
}
public enum CameraMaintenanceObservationEvidence: String, Sendable {
    case notRead = "not-read", unavailable, matched, mismatch, observed, reachable, notApplicable = "not-applicable"
}
public enum CameraMaintenanceStopEvidence: String, Sendable {
    case notRequired = "not-required", pending, confirmed, unknown
}
public enum CameraMaintenanceRestoreEvidence: String, Sendable {
    case notRequested = "not-requested", pending, confirmed, unknown, notApplicable = "not-applicable"
}

/// An actual clock sample, with unavailable offset/DST represented explicitly.
/// A USB epoch sample must not manufacture CCAPI time-zone evidence.
public struct CameraMaintenanceClock: Equatable, Sendable {
    public let epochSeconds: Int64
    public let utcOffsetMinutes: Int?
    public let daylightSaving: Bool?
    public var date: Date { Date(timeIntervalSince1970: TimeInterval(epochSeconds)) }
    public init(epochSeconds: Int64, utcOffsetMinutes: Int?, daylightSaving: Bool?) {
        self.epochSeconds = epochSeconds; self.utcOffsetMinutes = utcOffsetMinutes; self.daylightSaving = daylightSaving
    }
    static func parseCanon(_ object: [String: Any]) -> CameraMaintenanceClock? {
        guard let datetime = object["datetime"] as? String, let daylight = strictBool(object["dst"]),
              datetime.utf8.count == 31,
              datetime.range(of: #"^[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} [+-][0-9]{4}$"#,
                             options: .regularExpression) == (datetime.startIndex..<datetime.endIndex),
              let hours = Int(datetime.suffix(4).prefix(2)), hours <= 23,
              let minutes = Int(datetime.suffix(2)), minutes < 60 else { return nil }
        let offset = (hours * 60 + minutes) * (datetime.suffix(5).first == "-" ? -1 : 1)
        guard let zone = TimeZone(secondsFromGMT: offset * 60),
              let date = formatter(timeZone: zone).date(from: datetime),
              formatter(timeZone: zone).string(from: date) == datetime else { return nil }
        return CameraMaintenanceClock(epochSeconds: Int64(date.timeIntervalSince1970), utcOffsetMinutes: offset, daylightSaving: daylight)
    }
    static func formatter(timeZone: TimeZone) -> DateFormatter {
        let value = DateFormatter(); value.locale = Locale(identifier: "en_US_POSIX")
        value.calendar = Calendar(identifier: .gregorian); value.timeZone = timeZone
        value.isLenient = false; value.dateFormat = "EEE, dd MMM yyyy HH:mm:ss Z"; return value
    }
}

public struct CameraMaintenanceClockReference: Equatable, Sendable {
    public let earliestEpochSeconds: Int64
    public let latestEpochSeconds: Int64
    public let utcOffsetMinutes: Int?
    public let daylightSaving: Bool?
    public init(earliestEpochSeconds: Int64, latestEpochSeconds: Int64, utcOffsetMinutes: Int?, daylightSaving: Bool?) {
        self.earliestEpochSeconds = earliestEpochSeconds; self.latestEpochSeconds = latestEpochSeconds
        self.utcOffsetMinutes = utcOffsetMinutes; self.daylightSaving = daylightSaving
    }
    func matches(_ sample: CameraMaintenanceClock) -> Bool {
        (earliestEpochSeconds...latestEpochSeconds).contains(sample.epochSeconds)
            && (utcOffsetMinutes == nil || sample.utcOffsetMinutes == utcOffsetMinutes)
            && (daylightSaving == nil || sample.daylightSaving == daylightSaving)
    }
}

public struct CameraMaintenanceOutcome: Equatable, Sendable {
    public let operation: CameraMaintenanceOperation
    public let autoPowerOff: Bool
    public fileprivate(set) var commandEvidence = CameraMaintenanceCommandEvidence.notSent
    public fileprivate(set) var clockReference: CameraMaintenanceClockReference?
    // Command-time proof is retained independently of subsequent recovery reads.
    public fileprivate(set) var clockReadEvidence = CameraMaintenanceObservationEvidence.notRead
    public fileprivate(set) var verifiedClock: CameraMaintenanceClock?
    public fileprivate(set) var observationEvidence = CameraMaintenanceObservationEvidence.notRead
    public fileprivate(set) var stopEvidence = CameraMaintenanceStopEvidence.notRequired
    public fileprivate(set) var restoreEvidence = CameraMaintenanceRestoreEvidence.notRequested
    public fileprivate(set) var observedClock: CameraMaintenanceClock?
    public fileprivate(set) var observedAt: Date?
    public fileprivate(set) var commandResponseAt: Date?
}

/// One send admission, independent command proof, verification and observations.
/// Cancellation retires dispatch, never rewrites an intact original response.
public final class CameraMaintenanceAttempt: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private var outcome: CameraMaintenanceOutcome
    private var readPath: String?
    private var owner: UUID?
    private var restoreRequest: LiveViewRequest?
    private var restoreIncludesSize = true
    private var mutationSafetyRevoked = false
    public init(operation: CameraMaintenanceOperation, autoPowerOff: Bool = false) {
        outcome = CameraMaintenanceOutcome(operation: operation, autoPowerOff: autoPowerOff)
        if operation != .sensorCleaning || autoPowerOff { outcome.restoreEvidence = .notApplicable }
        if operation != .clockSync { outcome.clockReadEvidence = .notApplicable }
    }
    public var snapshot: CameraMaintenanceOutcome { lock.lock(); defer { lock.unlock() }; return outcome }
    public func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    var liveViewRestoreRequest: LiveViewRequest? { lock.lock(); defer { lock.unlock() }; return restoreRequest }
    var liveViewRestoreIncludesSize: Bool { lock.lock(); defer { lock.unlock() }; return restoreIncludesSize }
    func configureLiveViewRestoreRequest(_ request: LiveViewRequest?, includesSize: Bool = true) {
        lock.lock(); defer { lock.unlock() }
        if outcome.commandEvidence == .notSent { restoreRequest = request; restoreIncludesSize = includesSize }
    }
    func bindOwner(_ value: UUID) throws {
        lock.lock(); defer { lock.unlock() }
        guard owner == nil || owner == value else { throw CancellationError() }
        owner = value
    }
    func checkActive() throws {
        try Task.checkCancellation(); lock.lock(); defer { lock.unlock() }
        guard !cancelled else { throw CancellationError() }
    }
    public func revokeMutationSafety() { lock.lock(); mutationSafetyRevoked = true; lock.unlock() }
    func checkMutationSafety() throws {
        try checkActive(); lock.lock(); defer { lock.unlock() }
        guard !mutationSafetyRevoked else { throw CancellationError() }
    }
    func validateRestore() throws {
        try checkMutationSafety()
        let value = snapshot
        guard value.operation == .sensorCleaning, !value.autoPowerOff,
              value.commandEvidence == .responded,
              [.notRequired, .confirmed].contains(value.stopEvidence),
              value.observationEvidence == .reachable, value.restoreEvidence == .pending else {
            throw CancellationError()
        }
    }
    func validate() throws {
        try checkMutationSafety(); lock.lock(); defer { lock.unlock() }
        guard !cancelled, outcome.commandEvidence == .notSent,
              outcome.operation == .sensorCleaning || !outcome.autoPowerOff else { throw CancellationError() }
    }
    func configureReadPath(_ path: String) throws {
        lock.lock(); defer { lock.unlock() }
        guard !cancelled, outcome.commandEvidence == .notSent, readPath == nil || readPath == path else { throw CancellationError() }
        readPath = path
    }
    var observationPath: String? { lock.lock(); defer { lock.unlock() }; return readPath }
    func markDispatched() throws {
        try validate(); lock.lock(); defer { lock.unlock() }
        guard !cancelled, outcome.commandEvidence == .notSent else { throw CancellationError() }
        outcome.commandEvidence = .unknown
    }
    func responded(at date: Date = Date()) {
        lock.lock(); defer { lock.unlock() }
        guard outcome.commandEvidence == .unknown else { return }
        outcome.commandEvidence = .responded; outcome.commandResponseAt = date
    }
    func reference(_ reference: CameraMaintenanceClockReference?) {
        lock.lock(); defer { lock.unlock() }; outcome.clockReference = reference
    }
    public func stop(_ evidence: CameraMaintenanceStopEvidence) { lock.lock(); outcome.stopEvidence = evidence; lock.unlock() }
    public func restore(_ evidence: CameraMaintenanceRestoreEvidence) { lock.lock(); outcome.restoreEvidence = evidence; lock.unlock() }
    public func observationUnavailable() { lock.lock(); outcome.observationEvidence = .unavailable; lock.unlock() }
    func verificationUnavailable() { lock.lock(); outcome.clockReadEvidence = .unavailable; lock.unlock() }
    func observe(_ clock: CameraMaintenanceClock, verifying: Bool = false, at date: Date = Date()) {
        lock.lock(); defer { lock.unlock() }
        outcome.observedClock = clock; outcome.observedAt = date
        outcome.observationEvidence = .observed
        if verifying {
            outcome.verifiedClock = clock
            outcome.clockReadEvidence = outcome.clockReference.map { $0.matches(clock) ? .matched : .mismatch } ?? .observed
        }
    }
    func reachable(at date: Date = Date()) {
        lock.lock(); defer { lock.unlock() }; outcome.observationEvidence = .reachable; outcome.observedAt = date
    }
}

public enum CameraMaintenancePolicy {
    public static func canPublish(sameOwner: Bool, sameAttempt: Bool, retired: Bool, cancelled: Bool) -> Bool {
        sameOwner && sameAttempt && !retired && !cancelled
    }
    public static func canRecover(sameOwner: Bool, active: Bool, hasRecord: Bool) -> Bool {
        sameOwner && !active && hasRecord
    }
    public static func canRestore(sameOwner: Bool, sameAttempt: Bool, cancelled: Bool, retired: Bool,
        responseReceived: Bool, stopConfirmed: Bool, operation: CameraMaintenanceOperation, autoPowerOff: Bool,
        wasLive: Bool, freshReachable: Bool, safetyClear: Bool) -> Bool {
        canPublish(sameOwner: sameOwner, sameAttempt: sameAttempt, retired: retired, cancelled: cancelled)
            && responseReceived && stopConfirmed && operation == .sensorCleaning && !autoPowerOff
            && freshReachable && safetyClear
    }
    public static func canRestore(_ outcome: CameraMaintenanceOutcome, sameOwner: Bool, active: Bool) -> Bool {
        sameOwner && !active && outcome.operation == .sensorCleaning && !outcome.autoPowerOff
            && outcome.commandEvidence == .responded && [.notRequired, .confirmed].contains(outcome.stopEvidence)
            && outcome.observationEvidence == .reachable && outcome.restoreEvidence != .confirmed
    }
}

private func strictInteger(_ raw: Any?) -> Int64? {
    guard let value = raw as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(),
          ["c", "s", "i", "l", "q", "C", "S", "I", "L", "Q"].contains(String(cString: value.objCType)) else { return nil }
    let kind = String(cString: value.objCType)
    if ["C", "S", "I", "L", "Q"].contains(kind), value.uint64Value > UInt64(Int64.max) { return nil }
    return value.int64Value
}
private func strictBool(_ raw: Any?) -> Bool? {
    guard let value = raw as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else { return nil }
    return value.boolValue
}

/// The base envelope alone can retain response proof. Invalid clock/reference
/// fields fail closed independently, without erasing that command response.
enum CameraMaintenanceWire {
    static func validBase(_ body: [String: Any], engine: String?, invocation: CameraMaintenanceOutcome) -> Bool {
        guard let engine, !engine.isEmpty, body["engine"] as? String == engine,
              strictInteger(body["version"]) == 1, body["operation"] as? String == invocation.operation.rawValue else { return false }
        return true
    }
    static func clock(_ raw: Any?) -> CameraMaintenanceClock? {
        guard let body = raw as? [String: Any], Set(body.keys) == ["epochSeconds", "utcOffsetMinutes", "daylightSaving"],
              let epoch = strictInteger(body["epochSeconds"]), (-62_135_596_800...253_402_300_799).contains(epoch),
              let pair = zone(body) else { return nil }
        return CameraMaintenanceClock(epochSeconds: epoch, utcOffsetMinutes: pair.0, daylightSaving: pair.1)
    }
    private static func zone(_ body: [String: Any]) -> (Int?, Bool?)? {
        if body["utcOffsetMinutes"] is NSNull, body["daylightSaving"] is NSNull { return (nil, nil) }
        guard let offset = strictInteger(body["utcOffsetMinutes"]), (-1439...1439).contains(offset),
              let daylight = strictBool(body["daylightSaving"]) else { return nil }
        return (Int(offset), daylight)
    }
    static func reference(_ raw: Any?) -> CameraMaintenanceClockReference? {
        guard let body = raw as? [String: Any], Set(body.keys) == ["earliestEpochSeconds", "latestEpochSeconds", "utcOffsetMinutes", "daylightSaving"],
              let earliest = strictInteger(body["earliestEpochSeconds"]), let latest = strictInteger(body["latestEpochSeconds"]),
              (-62_135_596_800...253_402_300_799).contains(earliest), (-62_135_596_800...253_402_300_799).contains(latest),
              latest >= earliest, latest - earliest <= 120, let pair = zone(body) else { return nil }
        return CameraMaintenanceClockReference(earliestEpochSeconds: earliest, latestEpochSeconds: latest,
                                               utcOffsetMinutes: pair.0, daylightSaving: pair.1)
    }
    static func outcome(_ body: [String: Any], engine: String?, attempt: CameraMaintenanceAttempt) -> Bool {
        let invocation = attempt.snapshot
        guard validBase(body, engine: engine, invocation: invocation),
              strictBool(body["autoPowerOff"]) == invocation.autoPowerOff,
              body["responseEvidence"] as? String == "response-received" else { return false }
        attempt.responded()
        if invocation.operation == .clockSync {
            attempt.verificationUnavailable()
            guard let reference = reference(body["clockReference"]) else { return true }
            attempt.reference(reference)
            guard let sample = clock(body["clockObservation"]), let evidence = body["clockReadEvidence"] as? String,
                  ["matched", "mismatch"].contains(evidence),
                  (evidence == "matched") == reference.matches(sample) else { return true }
            attempt.observe(sample, verifying: true)
        }
        return true
    }
    static func observation(_ body: [String: Any], engine: String?, attempt: CameraMaintenanceAttempt) -> Bool {
        let invocation = attempt.snapshot
        guard validBase(body, engine: engine, invocation: invocation) else { return false }
        attempt.observationUnavailable()
        if invocation.operation == .clockSync, body["observationEvidence"] as? String == "observed",
           let sample = clock(body["clockObservation"]) { attempt.observe(sample); return true }
        if invocation.operation != .clockSync, body["observationEvidence"] as? String == "reachable",
           body["clockObservation"] is NSNull { attempt.reachable(); return true }
        return body["observationEvidence"] as? String == "unavailable"
    }
}

/// Preserve the integer-vs-float wire distinction before Foundation can coerce
/// 1.0 or 1e0 to an integer NSNumber. Invalid numeric fields become null locally:
/// a malformed read field cannot erase an otherwise valid command response.
func decodeMaintenanceJSONObject(_ data: Data) throws -> [String: Any] {
    guard data.count <= 64 * 1024, String(data: data, encoding: .utf8) != nil else {
        throw CCAPIError.invalidResponse("Maintenance evidence must be bounded UTF-8 JSON.")
    }
    _ = try decodeForegroundJSONObject(data)
    var bytes = Array(data), index = 0, quoted = false, escaped = false
    var invalid: [Range<Int>] = []
    while index < bytes.count {
        let byte = bytes[index]
        if quoted {
            if escaped { escaped = false }
            else if byte == 92 { escaped = true }
            else if byte == 34 { quoted = false }
            index += 1; continue
        }
        if byte == 34 { quoted = true; index += 1; continue }
        if byte == 45 || (48...57).contains(byte) {
            let start = index
            while index < bytes.count, (48...57).contains(bytes[index]) || [45, 43, 46, 69, 101].contains(bytes[index]) { index += 1 }
            if bytes[start..<index].contains(where: { [46, 69, 101].contains($0) }) { invalid.append(start..<index) }
        } else { index += 1 }
    }
    for range in invalid.reversed() { bytes.replaceSubrange(range, with: Array("null".utf8)) }
    guard let object = try JSONSerialization.jsonObject(with: Data(bytes)) as? [String: Any] else {
        throw CCAPIError.invalidResponse("Maintenance evidence must be a JSON object.")
    }
    return object
}
