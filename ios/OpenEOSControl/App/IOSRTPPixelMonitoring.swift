import AVFoundation
import Combine
import CoreImage
import CoreVideo
import Foundation

/// Bounds acquisition/processing age. Frame freshness is separately tied to its
/// exact decoder token; rereading a retained image cannot refresh either deadline.
struct IOSRTPMonitorLease {
    struct Ticket: Equatable, Sendable {
        let generation: UUID
        let sequence: UInt64
        let acquiredAt: TimeInterval
    }

    static let interval: TimeInterval = 0.25
    static let maximumAge: TimeInterval = 1
    private(set) var generation = UUID()
    private(set) var inFlight: Ticket?
    private var sequence: UInt64 = 0
    private var lastAttempt = -TimeInterval.infinity

    mutating func invalidate() {
        generation = UUID()
        // Keep both cadence and the physical slot across settings/stop/restart.
    }

    mutating func begin(at now: TimeInterval) -> Ticket? {
        guard inFlight == nil, now - lastAttempt >= Self.interval else { return nil }
        sequence &+= 1
        let ticket = Ticket(generation: generation, sequence: sequence, acquiredAt: now)
        lastAttempt = now
        inFlight = ticket
        return ticket
    }

    mutating func complete(_ ticket: Ticket, at now: TimeInterval) -> Bool {
        guard inFlight == ticket else { return false }
        inFlight = nil
        return ticket.generation == generation && now >= ticket.acquiredAt
            && now - ticket.acquiredAt < Self.maximumAge
    }
}

enum IOSRTPMonitorStatus: String {
    case off = "native_monitor_off"
    case unsupportedOS = "native_monitor_requires_ios174"
    case waiting = "native_monitor_waiting"
    case unavailable = "native_monitor_no_pixels"
    case failed = "native_monitor_failed"
    case sampled = "native_monitor_sampled"
    case stale = "native_monitor_stale"
}

/// Both reads and expiry waits use one monotonic time domain. Tests may control
/// that domain without extending the production sample lifetime or cadence.
struct IOSRTPMonitorClock: Sendable {
    let now: @MainActor @Sendable () -> TimeInterval
    let sleepUntil: @MainActor @Sendable (TimeInterval) async throws -> Void

    static let continuous = Self(
        now: { ProcessInfo.processInfo.systemUptime },
        sleepUntil: { deadline in
            let remaining = max(0, deadline - ProcessInfo.processInfo.systemUptime)
            try await Task.sleep(for: .seconds(remaining))
        }
    )
}

/// One exact submitted output and one bounded analysis job. Histogram/waveform
/// samples may complete after a newer frame; spatial overlays additionally need
/// the exact currently confirmed presentation token. Never read an unbound image.
@MainActor
final class IOSRTPPixelMonitor: ObservableObject {
    @Published private(set) var analysis: LiveViewMonitorAnalysis?
    @Published private(set) var status: IOSRTPMonitorStatus = .off
    @Published private(set) var analysisFrame: IOSRTPFrameToken?
    @Published private(set) var presentedFrame: IOSRTPFrameToken?
    private var settings: LiveViewMonitorSettings?
    private var configurationOwner: UUID?
    private var currentOutput: IOSRTPFrameToken?
    private var analysisOutputAt: TimeInterval?
    private var lease = IOSRTPMonitorLease()
    private var expiry: Task<Void, Never>?
    private var publishedAt: TimeInterval?
    private let queue = DispatchQueue(label: "dev.openeos.control.rtp-pixel-monitor", qos: .utility)
    private let isOwnerActive: @MainActor (IOSRTPFrameToken) -> Bool
    private let analyze: @Sendable (IOSRTPPixelSample, LiveViewMonitorSettings) -> LiveViewMonitorAnalysis?
    private let clock: IOSRTPMonitorClock

    init(isOwnerActive: @escaping @MainActor (IOSRTPFrameToken) -> Bool = { _ in true },
         analyze: @escaping @Sendable (IOSRTPPixelSample, LiveViewMonitorSettings) -> LiveViewMonitorAnalysis? = analyzeRTPPixelSample,
         clock: IOSRTPMonitorClock = .continuous) {
        self.isOwnerActive = isOwnerActive
        self.analyze = analyze
        self.clock = clock
    }

    static var supported: Bool {
        if #available(iOS 17.4, *) { return true }
        return false
    }

    var isProcessing: Bool { lease.inFlight != nil }

    func configure(settings requested: LiveViewMonitorSettings?, owner: UUID) {
        var next = requested
        if next?.needsPixelAnalysis != true { next = nil }
        guard settings != next || configurationOwner != owner else { return }
        configurationOwner = owner
        settings = next
        invalidate()
    }

    func deactivate(owner: UUID) {
        guard configurationOwner == owner else { return }
        configurationOwner = nil; settings = nil
        invalidate()
    }

    func invalidate() {
        lease.invalidate()
        expiry?.cancel(); expiry = nil
        currentOutput = nil; analysisOutputAt = nil; analysisFrame = nil; presentedFrame = nil
        publishedAt = nil; analysis = nil
        status = settings == nil ? .off : .waiting
    }

    /// Clear before every enqueue. Historical sampled scopes are separate from
    /// spatial overlays and therefore do not cancel physical work for each frame.
    func presentationChanged(to frame: IOSRTPFrameToken?) {
        if presentedFrame != frame { presentedFrame = frame }
    }

    private func sameConfiguration(_ first: IOSRTPFrameToken, _ second: IOSRTPFrameToken) -> Bool {
        first.owner == second.owner && first.epoch == second.epoch && first.lutRevision == second.lutRevision
    }

    func currentAnalysis(settings requested: LiveViewMonitorSettings, owner: UUID) -> LiveViewMonitorAnalysis? {
        let now = clock.now()
        guard configurationOwner == owner, settings == requested,
              let analysisFrame, let currentOutput, sameConfiguration(analysisFrame, currentOutput), isOwnerActive(analysisFrame),
              let publishedAt, let analysisOutputAt,
              now >= publishedAt, now - publishedAt < IOSRTPMonitorLease.maximumAge,
              now >= analysisOutputAt, now - analysisOutputAt < IOSRTPMonitorLease.maximumAge else { return nil }
        return analysis
    }

    func currentOverlayAnalysis(settings requested: LiveViewMonitorSettings,
                                frame: IOSRTPFrameToken?, owner: UUID) -> LiveViewMonitorAnalysis? {
        guard let frame, frame == analysisFrame, frame == currentOutput, frame == presentedFrame else { return nil }
        return currentAnalysis(settings: requested, owner: owner)
    }

    func frameAvailable(token: IOSRTPFrameToken, format: CMVideoFormatDescription, buffer: CVPixelBuffer,
                        outputAt: TimeInterval) {
        guard let settings, let configurationOwner, isOwnerActive(token) else { return }
        if let currentOutput, !sameConfiguration(currentOutput, token) { invalidate() }
        // Do not allow a repeated/old token to replace the source's current identity.
        if let currentOutput, token.sequence <= currentOutput.sequence { return }
        currentOutput = token
        let now = clock.now()
        guard outputAt.isFinite, now >= outputAt, now - outputAt < IOSRTPMonitorLease.maximumAge,
              let ticket = lease.begin(at: now) else { return }
        let sample = IOSRTPPixelSample(buffer: buffer, format: format)
        let analyze = self.analyze
        // A prior published result keeps its own expiry while newer work runs.
        if analysis == nil { scheduleExpiry(ticket, outputAt: outputAt) }
        queue.async { [weak self] in
            let result = autoreleasepool { analyze(sample, settings) }
            Task { @MainActor [weak self] in
                guard let self else { return }
                let completedAt = self.clock.now()
                guard self.lease.complete(ticket, at: completedAt),
                      self.configurationOwner == configurationOwner, self.settings == settings, self.isOwnerActive(token),
                      let currentOutput = self.currentOutput, self.sameConfiguration(currentOutput, token) else { return }
                // currentFrame is the exact retained sample evidence record, not
                // the newest global output. Spatial overlay matching is stricter.
                let evidence = evaluateRtpMonitorEvidence(IOSRTPMonitorEvidenceInput(
                    active: true, sameOwner: currentOutput.owner == token.owner,
                    sameRenderer: currentOutput.epoch == token.epoch,
                    foregroundRequired: true, foreground: true, terminal: false, stopPending: true,
                    sampleFrame: Double(token.sequence), currentFrame: Double(token.sequence),
                    sampleLut: 0, currentLut: currentOutput.lutRevision == token.lutRevision ? 0 : 1,
                    sampleAgeMs: (completedAt - ticket.acquiredAt) * 1000,
                    frameAgeMs: (completedAt - outputAt) * 1000,
                    maxAgeMs: IOSRTPMonitorLease.maximumAge * 1000, evidence: "surface-submitted"))
                guard evidence.canPublishSubmittedAnalysis else { return }
                self.analysis = result
                self.analysisFrame = result == nil ? nil : token
                self.analysisOutputAt = result == nil ? nil : outputAt
                self.publishedAt = result == nil ? nil : ticket.acquiredAt
                self.status = result == nil ? .failed : .sampled
                self.scheduleExpiry(ticket, outputAt: outputAt)
            }
        }
    }

    private func scheduleExpiry(_ ticket: IOSRTPMonitorLease.Ticket, outputAt: TimeInterval) {
        expiry?.cancel()
        let clock = self.clock
        let deadline = min(outputAt, ticket.acquiredAt) + IOSRTPMonitorLease.maximumAge
        expiry = Task { @MainActor [weak self] in
            try? await clock.sleepUntil(deadline)
            guard !Task.isCancelled, let self, self.lease.generation == ticket.generation else { return }
            self.analysis = nil; self.publishedAt = nil; self.analysisFrame = nil; self.analysisOutputAt = nil
            self.status = .stale
        }
    }
}

/// Retains Core Video's read-only buffer through the sole background job. No
/// attachments or source bytes are modified. No JPEG round trip/full-size copy.
struct IOSRTPPixelSample: @unchecked Sendable {
    let buffer: CVPixelBuffer
    let format: CMVideoFormatDescription
}

private let rtpMonitorColorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
private let rtpMonitorContext = CIContext(options: [
    .workingColorSpace: rtpMonitorColorSpace,
    .outputColorSpace: rtpMonitorColorSpace,
    .cacheIntermediates: false,
])

func analyzeRTPPixelSample(_ sample: IOSRTPPixelSample,
                          settings: LiveViewMonitorSettings) -> LiveViewMonitorAnalysis? {
    guard settings.needsPixelAnalysis else { return nil }
    switch CVPixelBufferGetPixelFormatType(sample.buffer) {
    case kCVPixelFormatType_32BGRA, kCVPixelFormatType_32ARGB:
        break
    case kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
         kCVPixelFormatType_420YpCbCr8BiPlanarFullRange:
        // CIImage reads buffer attachments, not the compressed format's
        // metadata. Never guess a YUV matrix/range conversion when absent.
        guard let matrix = CVBufferCopyAttachment(sample.buffer, kCVImageBufferYCbCrMatrixKey, nil),
              let primaries = CVBufferCopyAttachment(sample.buffer, kCVImageBufferColorPrimariesKey, nil),
              CVBufferCopyAttachment(sample.buffer, kCVImageBufferTransferFunctionKey, nil) != nil else { return nil }
        guard CFEqual(matrix, kCVImageBufferYCbCrMatrix_ITU_R_709_2)
            || CFEqual(matrix, kCVImageBufferYCbCrMatrix_ITU_R_601_4) else { return nil }
        guard CFEqual(primaries, kCVImageBufferColorPrimaries_ITU_R_709_2)
            || CFEqual(primaries, kCVImageBufferColorPrimaries_SMPTE_C)
            || CFEqual(primaries, kCVImageBufferColorPrimaries_EBU_3213) else { return nil }
    default:
        return nil
    }
    // This path offers SDR display-preview scopes only. Unknown transfer
    // functions, PQ/HLG and high-bit-depth buffers must not masquerade as SDR.
    let transfer = CVBufferCopyAttachment(sample.buffer, kCVImageBufferTransferFunctionKey, nil)
        ?? CMFormatDescriptionGetExtension(sample.format, extensionKey: kCMFormatDescriptionExtension_TransferFunction)
    if let transfer {
        guard CFEqual(transfer, kCVImageBufferTransferFunction_ITU_R_709_2)
            || CFEqual(transfer, kCVImageBufferTransferFunction_sRGB) else { return nil }
    }
    let encoded = CMVideoFormatDescriptionGetDimensions(sample.format)
    guard CVPixelBufferGetWidth(sample.buffer) == Int(encoded.width),
          CVPixelBufferGetHeight(sample.buffer) == Int(encoded.height) else { return nil }
    let source = CIImage(cvPixelBuffer: sample.buffer)
    let aperture = CMVideoFormatDescriptionGetCleanAperture(sample.format, originIsAtTopLeft: false)
    let presentation = CMVideoFormatDescriptionGetPresentationDimensions(
        sample.format, usePixelAspectRatio: true, useCleanAperture: true
    )
    guard !aperture.isEmpty, source.extent.contains(aperture),
          presentation.width.isFinite, presentation.height.isFinite,
          presentation.width > 0, presentation.height > 0,
          presentation.width <= 32_768, presentation.height <= 32_768 else { return nil }
    let dimensions = liveViewAnalysisDimensions(width: Int(presentation.width), height: Int(presentation.height))
    let image = source.cropped(to: aperture)
        .transformed(by: CGAffineTransform(translationX: -aperture.minX, y: -aperture.minY))
        .transformed(by: CGAffineTransform(scaleX: CGFloat(dimensions.width) / aperture.width,
                                          y: CGFloat(dimensions.height) / aperture.height))
    // Core Image consumes YCbCr matrix/range/transfer/color attachments. The
    // existing RGB scopes see explicit SDR sRGB, never untreated Y-plane values.
    // H.264 RTP has no app rotation transform; sample and native view both use
    // identity orientation. Desqueeze is applied once to their shared UI rect.
    guard let image = rtpMonitorContext.createCGImage(
        image, from: CGRect(x: 0, y: 0, width: dimensions.width, height: dimensions.height),
        format: .RGBA8, colorSpace: rtpMonitorColorSpace
    ) else { return nil }
    return analyzeLiveViewCGImage(image, settings: settings)
}
