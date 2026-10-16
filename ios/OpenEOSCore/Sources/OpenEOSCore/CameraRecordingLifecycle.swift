import Foundation
import CoreFoundation

public enum CameraRecordingResponseEvidence: String, Equatable, Sendable {
    case notSent = "not-sent", unconfirmed, rejected, responseReceived = "response-received"
}

public enum CameraRecordingObservationEvidence: String, Equatable, Sendable {
    case observed, unavailable, readFailed = "read-failed"
}

/// Only `observed` with an exact Boolean is current recording-state evidence.
/// A command ACK, ordinary status cache, movie mode, or filename is not observation.
public struct CameraRecordingObservation: Equatable, Sendable {
    public let evidence: CameraRecordingObservationEvidence
    public let recording: Bool?
    public let source: String?
    public static let unavailable = Self(evidence: .unavailable, recording: nil, source: nil)
    public static let readFailed = Self(evidence: .readFailed, recording: nil, source: nil)
    static func observed(_ recording: Bool, source: String) -> Self {
        Self(evidence: .observed, recording: recording, source: source)
    }
    public var observedRecording: Bool? { evidence == .observed ? recording : nil }
}

/// Value snapshot of the original invocation. Later recovery reads never rewrite it.
public struct CameraRecordingOutcome: Equatable, Sendable {
    public let id: UUID
    public let starting: Bool
    public let responseEvidence: CameraRecordingResponseEvidence
    public let observation: CameraRecordingObservation
}

public final class CameraRecordingAttempt: @unchecked Sendable {
    private let lock = NSLock()
    private var value: CameraRecordingOutcome
    private var owner: UUID?
    private var dispatched = false
    private var responseFinal = false
    private var completed = false
    public init(starting: Bool, id: UUID = UUID()) {
        value = CameraRecordingOutcome(id: id, starting: starting, responseEvidence: .notSent, observation: .unavailable)
    }
    public var snapshot: CameraRecordingOutcome { lock.lock(); defer { lock.unlock() }; return value }
    func admit(owner: UUID, starting: Bool) throws {
        try Task.checkCancellation()
        lock.lock(); defer { lock.unlock() }
        guard self.owner == nil, !dispatched, !completed, value.starting == starting else { throw CancellationError() }
        self.owner = owner
    }
    func markDispatched() throws {
        try Task.checkCancellation()
        lock.lock(); defer { lock.unlock() }
        guard owner != nil, !dispatched, !completed else { throw CancellationError() }
        dispatched = true
        value = CameraRecordingOutcome(id: value.id, starting: value.starting, responseEvidence: .unconfirmed, observation: .unavailable)
    }
    func recordResponse(_ evidence: CameraRecordingResponseEvidence) {
        lock.lock(); defer { lock.unlock() }
        guard dispatched, !responseFinal, !completed else { return }
        responseFinal = true
        value = CameraRecordingOutcome(id: value.id, starting: value.starting, responseEvidence: evidence, observation: value.observation)
    }
    func complete(_ observation: CameraRecordingObservation = .unavailable) {
        lock.lock(); defer { lock.unlock() }
        guard !completed else { return }
        completed = true
        value = CameraRecordingOutcome(id: value.id, starting: value.starting, responseEvidence: value.responseEvidence, observation: observation)
    }
}

public enum CameraRecordingPolicy {
    public static func canPublish(sameOwner: Bool, sameAttempt: Bool, cancelled: Bool, retired: Bool) -> Bool {
        sameOwner && sameAttempt && !cancelled && !retired
    }
    public static func stopRequired(previous: Bool, starting: Bool, response: CameraRecordingResponseEvidence,
                                    observation: CameraRecordingObservation, sameOwner: Bool = true,
                                    sameAttempt: Bool = true, cancelled: Bool = false, retired: Bool = false) -> Bool {
        guard sameOwner, sameAttempt else { return previous }
        if cancelled || retired {
            return previous || (starting && (response == .responseReceived || response == .unconfirmed))
        }
        if let recording = observation.observedRecording { return recording }
        if !starting, response == .responseReceived { return false }
        if starting, response == .unconfirmed || response == .responseReceived { return true }
        return previous
    }
    public static func canReviewStoppedVideo(sameOwner: Bool, sameAttempt: Bool, cancelled: Bool, retired: Bool,
                                             starting: Bool, observation: CameraRecordingObservation) -> Bool {
        canPublish(sameOwner: sameOwner, sameAttempt: sameAttempt, cancelled: cancelled, retired: retired)
            && !starting && observation.observedRecording == false
    }
}

/// v1 evidence is exact, bounded, unique-key UTF-8 JSON. Invalid envelopes never
/// downgrade an attempted command to not-sent or promote cached state to fresh.
enum CameraRecordingWire {
    static func decode(_ data: Data) throws -> [String: Any] {
        guard data.count <= 32 * 1024, String(data: data, encoding: .utf8) != nil else {
            throw CCAPIError.invalidResponse("Recording evidence must be bounded UTF-8 JSON.")
        }
        return try decodeForegroundJSONObject(data)
    }
    static func validDirectAcknowledgement(_ response: CameraHTTPResponse, simulator: Bool) -> Bool {
        guard response.body.count <= 32 * 1024, response.header("content-range") == nil,
              (try? CameraHTTPResponseIntegrity.validate(response, requestMethod: "POST")) != nil else { return false }
        if simulator { return response.statusCode == 200 && (try? decode(response.body)) != nil }
        if response.statusCode == 204 { return response.body.isEmpty }
        guard response.statusCode == 200 else { return false }
        return response.body.isEmpty || (try? decode(response.body))?.isEmpty == true
    }
    static func validVersion(_ body: [String: Any], engine: String?) -> Bool {
        guard let engine, !engine.isEmpty, body["engine"] as? String == engine,
              let version = body["version"] as? NSNumber, CFGetTypeID(version) != CFBooleanGetTypeID(),
              ["c", "s", "i", "l", "q", "C", "S", "I", "L", "Q"].contains(String(cString: version.objCType)),
              version.intValue == 1 else { return false }
        return true
    }
    static func observation(_ body: [String: Any], engine: String?) -> CameraRecordingObservation? {
        guard Set(body.keys) == Set(["version", "engine", "observationEvidence", "recording", "source"]),
              validVersion(body, engine: engine),
              let raw = body["observationEvidence"] as? String,
              let evidence = CameraRecordingObservationEvidence(rawValue: raw) else { return nil }
        if evidence == .observed {
            guard let number = body["recording"] as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID(),
                  let source = body["source"] as? String,
                  ["ccapi-event": "ccapi", "ccapi-simulator": "ccapi", "usb-evf-record-status": "usb-ptp",
                   "gphoto-config": "libgphoto2"][source] == engine else { return nil }
            return .observed(number.boolValue, source: source)
        }
        guard body["recording"] is NSNull, body["source"] is NSNull else { return nil }
        return evidence == .unavailable ? .unavailable : .readFailed
    }
    static func outcome(_ body: [String: Any], engine: String?, starting: Bool) -> (CameraRecordingResponseEvidence, CameraRecordingObservation)? {
        guard Set(body.keys) == Set(["version", "engine", "operation", "responseEvidence", "observation"]),
              validVersion(body, engine: engine), body["operation"] as? String == (starting ? "start" : "stop"),
              let raw = body["responseEvidence"] as? String,
              let response = CameraRecordingResponseEvidence(rawValue: raw),
              let observed = body["observation"] as? [String: Any],
              let observation = Self.observation(observed, engine: engine) else { return nil }
        return (response, observation)
    }
}
