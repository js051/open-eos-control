import Foundation

/// Identity describes one decoder output, never a packet, enqueue, or pixel hash.
struct IOSRTPFrameToken: Equatable, Hashable, Sendable {
    let owner: UUID
    let epoch: UUID
    let lutRevision: UUID
    let sequence: UInt64
}

enum IOSRTPPresentationState: String, Sendable {
    case waiting = "native_presentation_waiting"
    case progressing = "native_presentation_progressing"
    case unknown = "native_presentation_unknown"
    case stalled = "native_presentation_stalled"
}

enum IOSRTPProgressFailure: Equatable {
    case decodedOutputStalled
    case presentationStalled
    case rendererFailed
    case outputUnavailable
}

/// Pure monotonic evidence reducer. Callers must prove an exact retained output
/// identity before recordPresented. Equal image content is deliberately irrelevant.
struct IOSRTPPresentationProgress {
    static let maximumGap: TimeInterval = 5
    private(set) var owner: UUID?
    private(set) var epoch = UUID()
    private(set) var lutRevision = UUID()
    private(set) var decoded: IOSRTPFrameToken?
    private(set) var submitted: IOSRTPFrameToken?
    private(set) var presented: IOSRTPFrameToken?
    private(set) var state: IOSRTPPresentationState = .waiting
    private var startedAt: TimeInterval?
    private var lastDecodedAt: TimeInterval?
    private var lastSubmittedAt: TimeInterval?
    private(set) var lastPresentedAt: TimeInterval?
    private var presentationExpectedAt: TimeInterval?

    mutating func begin(owner: UUID, epoch: UUID, lutRevision: UUID, at now: TimeInterval) {
        if self.owner != owner {
            startedAt = now
            lastDecodedAt = nil
            lastSubmittedAt = nil
        }
        self.owner = owner; self.epoch = epoch; self.lutRevision = lutRevision
        decoded = nil; submitted = nil; presented = nil
        lastPresentedAt = nil; presentationExpectedAt = nil
        state = .waiting
    }

    func owns(_ frame: IOSRTPFrameToken) -> Bool {
        frame.owner == owner && frame.epoch == epoch && frame.lutRevision == lutRevision
    }

    @discardableResult
    mutating func recordDecoded(_ frame: IOSRTPFrameToken, at now: TimeInterval) -> Bool {
        guard owns(frame), now >= (lastDecodedAt ?? startedAt ?? now),
              decoded.map({ frame.sequence > $0.sequence }) ?? true else { return false }
        decoded = frame; lastDecodedAt = now
        return true
    }

    @discardableResult
    mutating func recordSubmitted(_ frame: IOSRTPFrameToken, at now: TimeInterval) -> Bool {
        guard owns(frame), decoded == frame,
              submitted.map({ frame.sequence > $0.sequence }) ?? true else { return false }
        submitted = frame; lastSubmittedAt = now
        return true
    }

    @discardableResult
    mutating func recordPresented(_ frame: IOSRTPFrameToken, at now: TimeInterval) -> Bool {
        guard owns(frame), let submitted, frame.sequence <= submitted.sequence,
              now >= (lastPresentedAt ?? startedAt ?? now),
              presented.map({ frame.sequence > $0.sequence }) ?? true else { return false }
        presented = frame; lastPresentedAt = now
        // A known surface owes fresh evidence once newer decoder output is submitted.
        presentationExpectedAt = now
        state = .progressing
        return true
    }

    mutating func recordUnverifiablePresentation(at now: TimeInterval, pixelsAvailable: Bool) {
        state = .unknown
        // Never advance lastPresentedAt or erase an already-established progress deadline.
    }

    mutating func failure(at now: TimeInterval, rendererFailed: Bool = false) -> IOSRTPProgressFailure? {
        if rendererFailed { state = .stalled; return .rendererFailed }
        guard let startedAt else { return nil }
        if now - (lastDecodedAt ?? startedAt) >= Self.maximumGap {
            state = .stalled; return .decodedOutputStalled
        }
        if now - (lastSubmittedAt ?? startedAt) >= Self.maximumGap {
            state = .stalled; return .outputUnavailable
        }
        if let deadline = presentationExpectedAt, let submitted,
           submitted.sequence > (presented?.sequence ?? 0), now - deadline >= Self.maximumGap {
            state = .stalled; return .presentationStalled
        }
        // OS/API unavailable or copied, unattributable buffers stay unknown.
        return nil
    }
}
