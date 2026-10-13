import AVFoundation
import Combine
import CoreImage
import CoreVideo
import Foundation

/// Bounds acquisition/processing age, not the decoder's presentation timestamp.
/// AVSampleBufferVideoRenderer does not return a frame timestamp with readback.
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

/// One retained, read-only renderer buffer and one bounded analysis job. The
/// native decoder/audio remain the presentation path; this never renders a LUT.
@MainActor
final class IOSRTPPixelMonitor: ObservableObject {
    @Published private(set) var analysis: LiveViewMonitorAnalysis?
    @Published private(set) var status: IOSRTPMonitorStatus = .off
    private var settings: LiveViewMonitorSettings?
    private var configurationOwner: UUID?
    private weak var layer: AVSampleBufferDisplayLayer?
    private var owner: UUID?
    private var format: CMVideoFormatDescription?
    private var lease = IOSRTPMonitorLease()
    private var expiry: Task<Void, Never>?
    private var lastSubmission: TimeInterval?
    private var publishedAt: TimeInterval?
    private let queue = DispatchQueue(label: "dev.openeos.control.rtp-pixel-monitor", qos: .utility)
    private let readPixels: (@MainActor (AVSampleBufferDisplayLayer) -> CVPixelBuffer?)?
    private let analyze: @Sendable (IOSRTPPixelSample, LiveViewMonitorSettings) -> LiveViewMonitorAnalysis?

    init(readPixels: (@MainActor (AVSampleBufferDisplayLayer) -> CVPixelBuffer?)? = nil,
         analyze: @escaping @Sendable (IOSRTPPixelSample, LiveViewMonitorSettings) -> LiveViewMonitorAnalysis? = analyzeRTPPixelSample) {
        self.readPixels = readPixels
        self.analyze = analyze
    }

    static var supported: Bool {
        if #available(iOS 17.4, *) { return true }
        return false
    }

    var isProcessing: Bool { lease.inFlight != nil }

    func configure(settings requested: LiveViewMonitorSettings?, owner: UUID) {
        // A retained LUT selection is inactive on native RTP. Analyze exactly
        // the unmodified native image and disclose that separately in the UI.
        var next = requested
        next?.cubeLut = nil
        if next?.needsPixelAnalysis != true { next = nil }
        guard settings != next || configurationOwner != owner else { return }
        configurationOwner = owner
        settings = next
        invalidate()
    }

    func deactivate(owner: UUID) {
        guard configurationOwner == owner else { return }
        configurationOwner = nil
        settings = nil
        invalidate()
    }

    func invalidate() {
        lease.invalidate()
        expiry?.cancel()
        expiry = nil
        layer = nil
        owner = nil
        format = nil
        lastSubmission = nil
        publishedAt = nil
        analysis = nil
        status = settings == nil ? .off : (Self.supported ? .waiting : .unsupportedOS)
    }

    func currentAnalysis(settings requested: LiveViewMonitorSettings) -> LiveViewMonitorAnalysis? {
        var requested = requested
        requested.cubeLut = nil
        guard settings == requested, let publishedAt,
              ProcessInfo.processInfo.systemUptime - publishedAt < IOSRTPMonitorLease.maximumAge else { return nil }
        return analysis
    }

    func frameSubmitted(layer nextLayer: AVSampleBufferDisplayLayer, owner nextOwner: UUID,
                        format nextFormat: CMVideoFormatDescription) {
        guard let settings else { return }
        guard #available(iOS 17.4, *) else {
            status = .unsupportedOS
            return
        }
        if layer !== nextLayer || owner != nextOwner || format.map({ !CMFormatDescriptionEqual($0, otherFormatDescription: nextFormat) }) == true {
            invalidate()
        }
        layer = nextLayer
        owner = nextOwner
        format = nextFormat
        let now = ProcessInfo.processInfo.systemUptime
        lastSubmission = now
        guard let ticket = lease.begin(at: now) else { return }
        let renderer = nextLayer.sampleBufferRenderer
        guard renderer.status != .failed, !renderer.requiresFlushToResumeDecoding else {
            finishFailure(ticket, status: .failed)
            return
        }
        // Swift spelling differs from Objective-C copyDisplayedPixelBuffer.
        // nil is a supported outcome (not ready/protected/unavailable).
        let buffer: CVPixelBuffer?
        if let readPixels { buffer = readPixels(nextLayer) }
        else { buffer = renderer.displayedPixelBuffer() }
        guard let buffer else {
            finishFailure(ticket, status: .unavailable)
            return
        }
        let sample = IOSRTPPixelSample(buffer: buffer, format: nextFormat)
        let analyze = self.analyze
        let attachmentID = ObjectIdentifier(nextLayer)
        // Expire even if analysis stalls. Invalidating never frees the busy slot.
        if analysis == nil { scheduleExpiry(ticket) }
        queue.async { [weak self] in
            let result = autoreleasepool { analyze(sample, settings) }
            Task { @MainActor [weak self] in
                guard let self else { return }
                let completedAt = ProcessInfo.processInfo.systemUptime
                guard self.lease.complete(ticket, at: completedAt) else { return }
                guard let layer = self.layer, ObjectIdentifier(layer) == attachmentID, self.owner == nextOwner,
                      self.lastSubmission.map({ completedAt - $0 < IOSRTPMonitorLease.maximumAge }) == true,
                      layer.sampleBufferRenderer.status != .failed,
                      !layer.sampleBufferRenderer.requiresFlushToResumeDecoding else {
                    self.analysis = nil
                    self.status = .stale
                    return
                }
                self.analysis = result
                self.publishedAt = result == nil ? nil : ticket.acquiredAt
                self.status = result == nil ? .failed : .sampled
                self.scheduleExpiry(ticket)
            }
        }
    }

    private func finishFailure(_ ticket: IOSRTPMonitorLease.Ticket, status: IOSRTPMonitorStatus) {
        guard lease.complete(ticket, at: ProcessInfo.processInfo.systemUptime) else { return }
        expiry?.cancel()
        analysis = nil
        publishedAt = nil
        self.status = status
    }

    private func scheduleExpiry(_ ticket: IOSRTPMonitorLease.Ticket) {
        expiry?.cancel()
        expiry = Task { @MainActor [weak self] in
            let remaining = max(0, ticket.acquiredAt + IOSRTPMonitorLease.maximumAge - ProcessInfo.processInfo.systemUptime)
            try? await Task.sleep(for: .seconds(remaining))
            guard !Task.isCancelled, let self, self.lease.generation == ticket.generation else { return }
            self.analysis = nil
            self.publishedAt = nil
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
