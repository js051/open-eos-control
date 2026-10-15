import AVFoundation
import Combine
import CoreImage
import CoreVideo
import Foundation
import Metal
import VideoToolbox

enum IOSNativeVideoStatus: String {
    case off = "native_lut_off"
    case waiting = "native_lut_waiting"
    case applied = "native_lut_applied"
    case unsupportedColor = "native_lut_unsupported_color"
    case unavailable = "native_lut_unavailable"
    case failed = "native_lut_failed"
}

@MainActor
final class IOSNativeVideoPresentation: ObservableObject {
    @Published var status: IOSNativeVideoStatus = .off
}

struct IOSNativeProcessedFrame: @unchecked Sendable {
    let sampleBuffer: CMSampleBuffer
    let pixelBuffer: CVPixelBuffer
    let format: CMVideoFormatDescription
}

enum IOSNativeVideoResult: @unchecked Sendable {
    case decoded // Decode-only readiness, never renderer/presentation evidence.
    case frame(IOSNativeProcessedFrame)
    case backpressure
    case unsupportedColor
    case unavailable
    case failed
}

/// Access under the session lock. Presentation throttling must never drop reference pictures.
struct IOSRTPVideoDeliveryGate {
    struct Ticket: Sendable { let sequence: UInt64 }
    private(set) var pending = 0
    private var sequence: UInt64 = 0
    private var needsKeyFrame = false

    mutating func admit(keyFrame: Bool) -> Ticket? {
        sequence &+= 1
        guard pending < 4 else { needsKeyFrame = true; return nil }
        guard !needsKeyFrame || keyFrame else { return nil }
        if keyFrame { needsKeyFrame = false }
        pending += 1
        return Ticket(sequence: sequence)
    }

    mutating func complete() { if pending > 0 { pending -= 1 } }
}

/// Main-actor task delivery is not assumed to preserve packet arrival order.
struct IOSRTPVideoDecodeContinuity {
    private var previous: UInt64?
    private var needsKeyFrame = true

    mutating func admit(sequence: UInt64, keyFrame: Bool) -> Bool {
        if let previous {
            guard sequence > previous else { return false }
            if sequence != previous &+ 1 { needsKeyFrame = true }
        }
        previous = sequence
        guard !needsKeyFrame || keyFrame else { return false }
        needsKeyFrame = false
        return true
    }
}

/// An off-main, one-physical-work-slot H.264/CI pipeline. Retiring an owner never frees a busy slot.
/// VideoToolbox uses synchronous decode flags; the output closure completes before DecodeFrame returns.
final class IOSRTPVideoProcessor: @unchecked Sendable {
    private struct Ticket: Equatable { let generation: UUID; let id: UUID }
    private let lock = NSLock()
    private var generation = UUID()
    private var inFlight: Ticket?
    private let queue = DispatchQueue(label: "dev.openeos.control.rtp-video-processing", qos: .userInitiated)
    // The following resources belong exclusively to queue.
    private var decoder: VTDecompressionSession?
    private var decoderFormat: CMVideoFormatDescription?
    private var resourceGeneration: UUID?
    private var pool: CVPixelBufferPool?
    private var poolSize: CGSize = .zero
    private var context: CIContext?

    var busy: Bool { lock.lock(); defer { lock.unlock() }; return inFlight != nil }

    func invalidate() {
        lock.lock()
        generation = UUID()
        let ticket = inFlight == nil ? Ticket(generation: generation, id: UUID()) : nil
        if let ticket { inFlight = ticket }
        lock.unlock()
        if let ticket {
            queue.async { [self] in resetResources(); finish(ticket) }
        }
    }

    @MainActor
    func submit(_ sampleBuffer: CMSampleBuffer, format: CMVideoFormatDescription, lut: CubeLut?,
                sps: Data, present: Bool = true, decodeOnly: Bool = false, completion: @escaping @MainActor @Sendable (IOSNativeVideoResult) -> Void) -> Bool {
        lock.lock()
        guard inFlight == nil else { lock.unlock(); return false }
        let ticket = Ticket(generation: generation, id: UUID())
        inFlight = ticket
        lock.unlock()
        let request = Request(sample: sampleBuffer, format: format, lut: lut, sps: sps, present: present, decodeOnly: decodeOnly)
        queue.async { [self] in
            if resourceGeneration != ticket.generation {
                resetResources()
                resourceGeneration = ticket.generation
            }
            let result = autoreleasepool { process(request) }
            Task { @MainActor [self] in
                if owns(ticket) { completion(result) }
                finish(ticket)
            }
        }
        return true
    }

    private func owns(_ ticket: Ticket) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return inFlight == ticket && generation == ticket.generation
    }

    private func finish(_ ticket: Ticket) {
        lock.lock()
        guard inFlight == ticket else { lock.unlock(); return }
        let stale = generation != ticket.generation
        inFlight = nil
        lock.unlock()
        // Serialize actual cleanup. It cannot release resources used by a replacement frame.
        if stale { invalidate() }
    }

    private struct Request: @unchecked Sendable {
        let sample: CMSampleBuffer
        let format: CMVideoFormatDescription
        let lut: CubeLut?
        let sps: Data
        let present: Bool
        let decodeOnly: Bool
    }

    private func process(_ request: Request) -> IOSNativeVideoResult {
        guard request.decodeOnly || nativeRTPPreviewColorSupported(format: request.format, sps: request.sps) else { return .unsupportedColor }
        let dimensions = CMVideoFormatDescriptionGetDimensions(request.format)
        guard dimensions.width > 0, dimensions.height > 0,
              dimensions.width <= 8192, dimensions.height <= 8192,
              Int64(dimensions.width) * Int64(dimensions.height) <= 8_847_360 else { return .unavailable }
        if decoderFormat.map({ !CMFormatDescriptionEqual($0, otherFormatDescription: request.format) }) != false {
            resetResources()
            let attributes: [CFString: Any] = [
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                kCVPixelBufferMetalCompatibilityKey: true,
                kCVPixelBufferIOSurfacePropertiesKey: [:] as [String: Any],
            ]
            let status = VTDecompressionSessionCreate(allocator: kCFAllocatorDefault,
                formatDescription: request.format, decoderSpecification: nil,
                imageBufferAttributes: attributes as CFDictionary, outputCallback: nil,
                decompressionSessionOut: &decoder)
            guard status == noErr, decoder != nil else { return .unavailable }
            decoderFormat = request.format
        }
        guard let decoder else { return .unavailable }
        let decodedOutput = DecodedOutput()
        let status = VTDecompressionSessionDecodeFrame(decoder, sampleBuffer: request.sample,
            flags: [], infoFlagsOut: nil) { result, _, image, _, _ in
                decodedOutput.status = result
                decodedOutput.buffer = image
            }
        guard status == noErr, decodedOutput.status == noErr else { return .failed }
        guard request.present, let decoded = decodedOutput.buffer else { return .backpressure }
        if request.decodeOnly { return .decoded }
        guard CVPixelBufferGetPixelFormatType(decoded) == kCVPixelFormatType_32BGRA,
              CVPixelBufferGetWidth(decoded) == Int(dimensions.width),
              CVPixelBufferGetHeight(decoded) == Int(dimensions.height) else { return .unsupportedColor }
        let output: CVPixelBuffer
        do {
            guard let device = MTLCreateSystemDefaultDevice() else { return .unavailable }
            if context == nil {
                context = nativeRTPPreviewContext(device: device)
            }
            let size = CGSize(width: CGFloat(dimensions.width), height: CGFloat(dimensions.height))
            if pool == nil || poolSize != size {
                pool = nil
                let attributes: [CFString: Any] = [
                    kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                    kCVPixelBufferWidthKey: Int(dimensions.width), kCVPixelBufferHeightKey: Int(dimensions.height),
                    kCVPixelBufferMetalCompatibilityKey: true,
                    kCVPixelBufferIOSurfacePropertiesKey: [:] as [String: Any],
                ]
                guard CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &pool) == kCVReturnSuccess else {
                    return .unavailable
                }
                poolSize = size
            }
            guard let pool, let context else { return .unavailable }
            var target: CVPixelBuffer?
            let allocation = CVPixelBufferPoolCreatePixelBufferWithAuxAttributes(kCFAllocatorDefault, pool,
                [kCVPixelBufferPoolAllocationThresholdKey: 3] as CFDictionary, &target)
            if allocation == kCVReturnWouldExceedAllocationThreshold { return .backpressure }
            guard allocation == kCVReturnSuccess, let target else { return .unavailable }
            let inputImage = CIImage(cvPixelBuffer: decoded)
            let image: CIImage
            if let lut = request.lut {
                guard let filtered = nativeRTPApplyLut(inputImage, lut: lut) else { return .failed }
                image = filtered
            } else { image = inputImage }
            context.render(image, to: target, bounds: image.extent, colorSpace: cubeLutPreviewOutputColorSpace)
            output = target
        }
        // Preserve the compressed stream's display geometry; convert color explicitly to SDR sRGB.
        CVBufferSetAttachment(output, kCVImageBufferColorPrimariesKey, kCVImageBufferColorPrimaries_ITU_R_709_2, .shouldPropagate)
        CVBufferSetAttachment(output, kCVImageBufferTransferFunctionKey, kCVImageBufferTransferFunction_sRGB, .shouldPropagate)
        for (sourceKey, targetKey) in [
            (kCMFormatDescriptionExtension_CleanAperture, kCVImageBufferCleanApertureKey),
            (kCMFormatDescriptionExtension_PixelAspectRatio, kCVImageBufferPixelAspectRatioKey),
        ] {
            if let value = CMFormatDescriptionGetExtension(request.format, extensionKey: sourceKey) {
                CVBufferSetAttachment(output, targetKey, value, .shouldPropagate)
            }
        }
        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault,
            imageBuffer: output, formatDescriptionOut: &format) == noErr, let format else { return .failed }
        var timing = CMSampleTimingInfo(duration: .invalid,
            presentationTimeStamp: CMSampleBufferGetPresentationTimeStamp(request.sample), decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: output,
            formatDescription: format, sampleTiming: &timing, sampleBufferOut: &sample) == noErr, let sample else { return .failed }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) as? NSArray,
           let attachment = attachments.firstObject as? NSMutableDictionary {
            attachment[kCMSampleAttachmentKey_DisplayImmediately] = true
        }
        return .frame(IOSNativeProcessedFrame(sampleBuffer: sample, pixelBuffer: output, format: format))
    }

    private final class DecodedOutput: @unchecked Sendable {
        var status: OSStatus = noErr
        var buffer: CVPixelBuffer?
    }

    private func resetResources() {
        if let decoder { VTDecompressionSessionInvalidate(decoder) }
        decoder = nil; decoderFormat = nil; pool = nil; poolSize = .zero
        context?.clearCaches(); context = nil
    }

    deinit { if let decoder { VTDecompressionSessionInvalidate(decoder) } }
}

func nativeRTPPreviewContext(device: MTLDevice) -> CIContext {
    cubeLutPreviewContext(device: device)
}

func nativeRTPPreviewColorSupported(format: CMVideoFormatDescription, sps: Data) -> Bool {
    // Baseline/Main/Extended/High profiles are 8-bit; High10/422/444 and unknown profiles are not guessed.
    guard sps.count >= 4, sps[sps.startIndex] & 0x1f == 7,
          [66, 77, 88, 100].contains(Int(sps[sps.startIndex + 1])) else { return false }
    if let transfer = CMFormatDescriptionGetExtension(format, extensionKey: kCMFormatDescriptionExtension_TransferFunction) {
        guard CFEqual(transfer, kCVImageBufferTransferFunction_ITU_R_709_2) ||
            CFEqual(transfer, kCVImageBufferTransferFunction_sRGB) else { return false }
    }
    if let primaries = CMFormatDescriptionGetExtension(format, extensionKey: kCMFormatDescriptionExtension_ColorPrimaries) {
        guard CFEqual(primaries, kCVImageBufferColorPrimaries_ITU_R_709_2) ||
            CFEqual(primaries, kCVImageBufferColorPrimaries_SMPTE_C) ||
            CFEqual(primaries, kCVImageBufferColorPrimaries_EBU_3213) else { return false }
    }
    return true // Missing tags remain an explicitly approximate display-preview interpretation.
}

/// Native and decoded-frame previews share one numeric domain/interpolation pipeline.
func nativeRTPApplyLut(_ input: CIImage, lut: CubeLut) -> CIImage? {
    applyCubeLutPreview(input, lut: lut)
}
