import Foundation

/// Cross-platform necessary gate. It cannot manufacture decoder or display proof.
struct IOSRTPMonitorEvidenceInput: Decodable {
    var active: Bool?
    var sameOwner: Bool?
    var sameRenderer: Bool?
    var foregroundRequired: Bool?
    var foreground: Bool?
    var terminal: Bool?
    var stopPending: Bool?
    var sampleFrame: Double?
    var currentFrame: Double?
    var sampleLut: Double?
    var currentLut: Double?
    var sampleAgeMs: Double?
    var frameAgeMs: Double?
    var maxAgeMs: Double?
    var evidence: String?
}

struct IOSRTPMonitorEvidenceResult: Decodable, Equatable {
    let canPublishDecodedAnalysis: Bool
    let canPublishSubmittedAnalysis: Bool
    let canClaimPresentation: Bool
}

func evaluateRtpMonitorEvidence(_ input: IOSRTPMonitorEvidenceInput) -> IOSRTPMonitorEvidenceResult {
    let denied = IOSRTPMonitorEvidenceResult(canPublishDecodedAnalysis: false,
        canPublishSubmittedAnalysis: false, canClaimPresentation: false)
    func safeInteger(_ value: Double?, minimum: Double) -> Bool {
        guard let value else { return false }
        return value.isFinite && value >= minimum && value <= 9_007_199_254_740_991 && value.rounded(.towardZero) == value
    }
    guard input.active == true, input.sameOwner == true, input.sameRenderer == true,
          input.terminal == false, input.stopPending == true,
          let foregroundRequired = input.foregroundRequired,
          !foregroundRequired || input.foreground == true,
          safeInteger(input.sampleFrame, minimum: 1), safeInteger(input.currentFrame, minimum: 1),
          input.sampleFrame == input.currentFrame,
          safeInteger(input.sampleLut, minimum: 0), safeInteger(input.currentLut, minimum: 0),
          input.sampleLut == input.currentLut,
          let maxAge = input.maxAgeMs, maxAge.isFinite, maxAge > 0, maxAge <= 5000,
          let sampleAge = input.sampleAgeMs, sampleAge.isFinite, sampleAge >= 0, sampleAge <= maxAge,
          let frameAge = input.frameAgeMs, frameAge.isFinite, frameAge >= 0, frameAge <= maxAge else { return denied }
    let evidence = input.evidence
    return IOSRTPMonitorEvidenceResult(
        canPublishDecodedAnalysis: ["decoded-output", "surface-submitted", "presentation-confirmed"].contains(evidence ?? ""),
        canPublishSubmittedAnalysis: ["surface-submitted", "presentation-confirmed"].contains(evidence ?? ""),
        canClaimPresentation: evidence == "presentation-confirmed")
}

// Decode each primitive strictly and fail closed on a wrong type. Synthesized
// decoding would abort the entire vector file on an intentionally malformed field.
extension IOSRTPMonitorEvidenceInput {
    private enum CodingKeys: String, CodingKey {
        case active, sameOwner, sameRenderer, foregroundRequired, foreground, terminal, stopPending
        case sampleFrame, currentFrame, sampleLut, currentLut, sampleAgeMs, frameAgeMs, maxAgeMs, evidence
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        var invalid = false
        func read<T: Decodable>(_ type: T.Type, _ key: CodingKeys) -> T? {
            do { return try values.decodeIfPresent(type, forKey: key) }
            catch { invalid = true; return nil }
        }
        active = read(Bool.self, .active)
        sameOwner = read(Bool.self, .sameOwner)
        sameRenderer = read(Bool.self, .sameRenderer)
        foregroundRequired = read(Bool.self, .foregroundRequired)
        foreground = read(Bool.self, .foreground)
        terminal = read(Bool.self, .terminal)
        stopPending = read(Bool.self, .stopPending)
        sampleFrame = read(Double.self, .sampleFrame)
        currentFrame = read(Double.self, .currentFrame)
        sampleLut = read(Double.self, .sampleLut)
        currentLut = read(Double.self, .currentLut)
        sampleAgeMs = read(Double.self, .sampleAgeMs)
        frameAgeMs = read(Double.self, .frameAgeMs)
        maxAgeMs = read(Double.self, .maxAgeMs)
        evidence = read(String.self, .evidence)
        if invalid { active = false }
    }
}
