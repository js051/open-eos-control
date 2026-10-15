import Foundation

/// A DELETE acknowledgement is evidence for this invocation, never a list observation.
public enum CameraMediaDeleteOutcome: String, Sendable {
    case notStarted = "not-started", notSent = "not-sent", confirmed, rejected, uncertain, skipped
}

public enum CameraMediaDeleteError: Error, LocalizedError {
    case unconfirmed, rejected(Int), ineligible
    public var errorDescription: String? {
        switch self {
        case .unconfirmed: "Deletion was dispatched but completion is unconfirmed. Inspect the original source before a new operation."
        case let .rejected(status): "Deletion was explicitly rejected (HTTP \(status))."
        case .ineligible: "This exact media item is not eligible for deletion."
        }
    }
}

public enum CameraMediaDeletePolicy {
    public static func admits<Owner: Equatable>(openingOwner: Owner, currentOwner: Owner?, confirmed: Bool,
                                                consumed: Bool, busy: Bool) -> Bool {
        confirmed && !consumed && !busy && currentOwner == openingOwner
    }
    public static func outcome(started: Bool, dispatched: Bool, confirmedReceipt: Bool,
                               rejectedReceipt: Bool, skippedReason: String?) -> CameraMediaDeleteOutcome {
        if dispatched { return confirmedReceipt ? .confirmed : rejectedReceipt ? .rejected : .uncertain }
        if skippedReason != nil { return .skipped }
        return started ? .notSent : .notStarted
    }
    public static func eligibility(source: String, loaded: Bool, capability: Bool?, protected: Bool?, writable: Bool?)
        -> (allowed: Bool, reason: String, qualificationUnknown: Bool) {
        if !loaded { return (false, "not-loaded", false) }
        if capability != true { return (false, "no-capability", false) }
        if !["camera", "desktop-host", "android-host"].contains(source) { return (false, "unknown-source", false) }
        if protected == true { return (false, "protected", false) }
        if writable == false { return (false, "not-writable", false) }
        return (true, "eligible", protected == nil || writable == nil)
    }
    public static func roster(_ selectedIDs: [String]) -> (allowed: Bool, reason: String, ids: [String]) {
        guard !selectedIDs.isEmpty else { return (false, "empty", []) }
        guard !selectedIDs.contains(where: \.isEmpty) else { return (false, "invalid-id", []) }
        var seen = Set<Data>()
        let ids = selectedIDs.filter { seen.insert(Data($0.utf8)).inserted }
        guard ids.count <= 1_000 else { return (false, "too-many", []) }
        return (true, "valid", ids)
    }
    public static func consumesSelection<Owner: Equatable, Token: Equatable>(
        operationOwner: Owner, currentOwner: Owner?, operationToken: Token?, currentToken: Token?
    ) -> Bool {
        currentOwner == operationOwner && operationToken != nil && operationToken == currentToken
    }
    public static func httpOutcome(status: Int, responseComplete: Bool, framingValid: Bool, bodyBytes: Int) -> CameraMediaDeleteOutcome {
        guard responseComplete, framingValid else { return .uncertain }
        if status == 204 && bodyBytes == 0 { return .confirmed }
        if [400, 401, 403, 404, 405, 409, 412, 413, 415, 422, 429].contains(status) { return .rejected }
        return .uncertain
    }
    /// Bridge owns an explicit 204 contract. Existing CCAPI fixtures support 204;
    /// other camera/firmware success codes remain unverified, not guessed.
    public static func responseOutcome(_ response: CameraHTTPResponse) -> CameraMediaDeleteOutcome {
        guard (try? CameraHTTPResponseIntegrity.validate(response, requestMethod: "DELETE")) != nil else { return .uncertain }
        if response.statusCode == 204 {
            guard response.body.isEmpty, response.header("content-range") == nil,
                  response.header("transfer-encoding") == nil else { return .uncertain }
            if let length = response.header("content-length"),
               length.split(separator: ",", omittingEmptySubsequences: false).contains(where: {
                   $0.trimmingCharacters(in: .whitespaces) != "0"
               }) { return .uncertain }
            return .confirmed
        }
        return httpOutcome(status: response.statusCode, responseComplete: true, framingValid: true, bodyBytes: response.body.count)
    }
}

public final class CameraMediaDeleteAttempt: @unchecked Sendable {
    public let id = UUID()
    private let lock = NSLock()
    private var sent = false
    private var cancelled = false
    private var terminal: CameraMediaDeleteOutcome?
    public init() {}
    public var dispatched: Bool { lock.lock(); defer { lock.unlock() }; return sent }
    public var outcome: CameraMediaDeleteOutcome {
        lock.lock(); defer { lock.unlock() }
        return CameraMediaDeletePolicy.outcome(started: true, dispatched: sent, confirmedReceipt: terminal == .confirmed, rejectedReceipt: terminal == .rejected, skippedReason: nil)
    }
    public func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    func markDispatched() throws {
        lock.lock(); defer { lock.unlock() }
        guard !cancelled, !sent else { throw CancellationError() }
        sent = true
    }
    func record(_ response: CameraHTTPResponse) throws {
        let result = CameraMediaDeletePolicy.responseOutcome(response)
        lock.lock()
        if sent, terminal == nil, result == .confirmed || result == .rejected { terminal = result }
        let actual = terminal ?? .uncertain
        lock.unlock()
        // A completed response can win a racing cancel, but never authorizes another send.
        if actual == .confirmed { return }
        if actual == .rejected { throw CameraMediaDeleteError.rejected(response.statusCode) }
        throw CameraMediaDeleteError.unconfirmed
    }
}
