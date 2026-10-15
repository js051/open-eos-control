import AVFoundation
import Combine
import CoreVideo
import XCTest

@testable import OpenEOSControl

final class RTPPixelMonitoringLeaseTests: XCTestCase {
    func testPhysicalSlotAndCadenceSurviveInvalidationAndLateCompletion() throws {
        var lease = IOSRTPMonitorLease()
        let old = try XCTUnwrap(lease.begin(at: 100))
        for _ in 0..<20 { lease.invalidate() }
        XCTAssertNil(lease.begin(at: 100.1), "Invalidation cannot release physical work")
        XCTAssertFalse(lease.complete(old, at: 100.1))
        XCTAssertNil(lease.begin(at: 100.2), "Settings changes cannot bypass the 4 Hz bound")
        let current = try XCTUnwrap(lease.begin(at: 100.25))
        XCTAssertFalse(lease.complete(old, at: 100.3), "Duplicate old completion cannot free current slot")
        XCTAssertNil(lease.begin(at: 100.5))
        XCTAssertTrue(lease.complete(current, at: 100.5))
        XCTAssertNotNil(lease.begin(at: 100.5))
    }

    func testContinuousNewInputDoesNotStarveAnOwnedCompletedSample() throws {
        var lease = IOSRTPMonitorLease()
        let current = try XCTUnwrap(lease.begin(at: 0))
        for milliseconds in stride(from: 10, through: 500, by: 10) {
            XCTAssertNil(lease.begin(at: Double(milliseconds) / 1000))
        }
        XCTAssertTrue(lease.complete(current, at: 0.5))
        XCTAssertNotNil(lease.begin(at: 0.5))
    }

    func testExactlyOneSecondOldWorkAndBackwardClockAreRejected() throws {
        var lease = IOSRTPMonitorLease()
        let old = try XCTUnwrap(lease.begin(at: 10))
        XCTAssertFalse(lease.complete(old, at: 11))
        let fresh = try XCTUnwrap(lease.begin(at: 11))
        XCTAssertFalse(lease.complete(fresh, at: 10.9))
    }
}

final class RTPPixelConversionTests: XCTestCase {
    func testAsymmetricRGBPatchPreservesChannelsAndTopDownOverlayPlacement() throws {
        let sample = try makeRTPMonitorSample(width: 2, height: 2)
        XCTAssertEqual(CVPixelBufferLockBaseAddress(sample.buffer, []), kCVReturnSuccess)
        let bytes = try XCTUnwrap(CVPixelBufferGetBaseAddress(sample.buffer)).assumingMemoryBound(to: UInt8.self)
        let stride = CVPixelBufferGetBytesPerRow(sample.buffer)
        // Top: red, green. Bottom: blue, white. Input bytes are BGRA.
        let colors: [[UInt8]] = [[0, 0, 255, 255], [0, 255, 0, 255], [255, 0, 0, 255], [255, 255, 255, 255]]
        for y in 0..<2 {
            for x in 0..<2 {
                for channel in 0..<4 { bytes[y * stride + x * 4 + channel] = colors[y * 2 + x][channel] }
            }
        }
        CVPixelBufferUnlockBaseAddress(sample.buffer, [])
        var settings = LiveViewMonitorSettings()
        settings.falseColorEnabled = true
        settings.waveformVisible = true
        let result = try XCTUnwrap(analyzeRTPPixelSample(sample, settings: settings))
        for bin in [13, 45, 4, 63] { XCTAssertEqual(result.histogram[bin], 1) }
        XCTAssertEqual(result.histogram.reduce(0, +), 4)
        let waveform = try XCTUnwrap(result.waveform)
        for (column, bin) in [(0, 13), (63, 45), (0, 4), (63, 63)] {
            XCTAssertEqual(waveform.density[(63 - bin) * 64 + column], 1)
        }
        XCTAssertEqual(try XCTUnwrap(result.overlayRGBA), [
            41, 182, 209, 224, 242, 209, 61, 224,
            32, 61, 160, 224, 232, 59, 53, 224,
        ], "Spatial overlay must not swap RGB/BGRA or flip the displayed rows")
    }

    func testStridedBGRAProducesExistingHistogramWaveformAndEachOverlay() throws {
        let sample = try makeRTPMonitorSample(width: 12, height: 8)
        var settings = LiveViewMonitorSettings()
        settings.waveformVisible = true
        let result = try XCTUnwrap(analyzeRTPPixelSample(sample, settings: settings))
        XCTAssertEqual(result.histogram.reduce(0, +), 96)
        let waveform = try XCTUnwrap(result.waveform)
        // Black is on the left and white on the right, independent of row padding.
        XCTAssertGreaterThan(waveform.density[63 * waveform.width], 0)
        XCTAssertGreaterThan(waveform.density[waveform.width - 1], 0)
        for kind in 0..<3 {
            var overlay = LiveViewMonitorSettings()
            if kind == 0 { overlay.zebraThresholdPercent = 90 }
            if kind == 1 { overlay.falseColorEnabled = true }
            if kind == 2 { overlay.focusPeakingEnabled = true }
            let pixels = try XCTUnwrap(analyzeRTPPixelSample(sample, settings: overlay)?.overlayRGBA)
            XCTAssertEqual(pixels.count, 12 * 8 * 4)
            XCTAssertTrue(stride(from: 3, to: pixels.count, by: 4).contains { pixels[$0] > 0 })
        }
        XCTAssertNil(analyzeRTPPixelSample(sample, settings: LiveViewMonitorSettings()))
    }

    func testLandscapeAndPortraitRemainBounded() throws {
        var settings = LiveViewMonitorSettings()
        settings.histogramVisible = true
        for (width, height) in [(1920, 1080), (1080, 1920)] {
            let result = try XCTUnwrap(analyzeRTPPixelSample(try makeRTPMonitorSample(width: width, height: height), settings: settings))
            XCTAssertLessThanOrEqual(result.width, 120)
            XCTAssertLessThanOrEqual(result.height, 80)
            XCTAssertEqual(result.histogram.reduce(0, +), result.width * result.height)
        }
    }

    func testUnsupportedColorAndMismatchedGeometryDoNotProduceScopes() throws {
        var settings = LiveViewMonitorSettings()
        settings.histogramVisible = true
        let sample = try makeRTPMonitorSample(width: 12, height: 8)
        let other = try makeRTPMonitorSample(width: 8, height: 8)
        XCTAssertNil(analyzeRTPPixelSample(IOSRTPPixelSample(buffer: sample.buffer, format: other.format), settings: settings))
        var unsupported: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault, 12, 8, kCVPixelFormatType_OneComponent8,
                                          nil, &unsupported), kCVReturnSuccess)
        XCTAssertNil(analyzeRTPPixelSample(IOSRTPPixelSample(buffer: try XCTUnwrap(unsupported), format: sample.format), settings: settings))
        CVBufferSetAttachment(sample.buffer, kCVImageBufferTransferFunctionKey,
                              kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ, .shouldPropagate)
        XCTAssertNil(analyzeRTPPixelSample(sample, settings: settings))
        CVBufferSetAttachment(sample.buffer, kCVImageBufferTransferFunctionKey,
                              "unknown-transfer" as CFString, .shouldPropagate)
        XCTAssertNil(analyzeRTPPixelSample(sample, settings: settings))
    }

    func testCleanApertureAndPixelAspectMatchPresentationGeometry() throws {
        let sample = try makeRTPMonitorSample(width: 12, height: 8)
        let extensions: [String: Any] = [
            kCMFormatDescriptionExtension_CleanAperture as String: [
                kCMFormatDescriptionKey_CleanApertureWidth as String: 8,
                kCMFormatDescriptionKey_CleanApertureHeight as String: 8,
                kCMFormatDescriptionKey_CleanApertureHorizontalOffset as String: 0,
                kCMFormatDescriptionKey_CleanApertureVerticalOffset as String: 0,
            ],
            kCMFormatDescriptionExtension_PixelAspectRatio as String: [
                kCMFormatDescriptionKey_PixelAspectRatioHorizontalSpacing as String: 2,
                kCMFormatDescriptionKey_PixelAspectRatioVerticalSpacing as String: 1,
            ],
        ]
        var format: CMVideoFormatDescription?
        XCTAssertEqual(CMVideoFormatDescriptionCreate(allocator: kCFAllocatorDefault,
            codecType: kCVPixelFormatType_32BGRA, width: 12, height: 8,
            extensions: extensions as CFDictionary, formatDescriptionOut: &format), noErr)
        var settings = LiveViewMonitorSettings()
        settings.histogramVisible = true
        settings.desqueeze = .x2
        let result = try XCTUnwrap(analyzeRTPPixelSample(
            IOSRTPPixelSample(buffer: sample.buffer, format: try XCTUnwrap(format)), settings: settings))
        XCTAssertEqual(result.width, 16)
        XCTAssertEqual(result.height, 8, "Desqueeze belongs to the UI rectangle, not a second pixel transform")
    }
}

@MainActor
final class RTPPixelMonitorOwnershipTests: XCTestCase {
    func testSuccessfulSampleExpiresWithoutFurtherSourceActivity() async throws {
        guard #available(iOS 17.4, *) else { throw XCTSkip("Displayed pixel API starts at iOS 17.4") }
        let source = try makeRTPMonitorSample(width: 12, height: 8)
        let monitor = IOSRTPPixelMonitor(readPixels: { _ in source.buffer })
        let sampled = expectation(description: "Sample published")
        let stale = expectation(description: "Sample expires without another frame")
        let subscription = monitor.$status.sink {
            if $0 == .sampled { sampled.fulfill() }
            if $0 == .stale { stale.fulfill() }
        }
        defer { subscription.cancel() }
        var settings = LiveViewMonitorSettings()
        settings.histogramVisible = true
        let owner = UUID()
        monitor.configure(settings: settings, owner: owner)
        let layer = AVSampleBufferDisplayLayer()
        monitor.frameSubmitted(layer: layer, owner: UUID(), format: source.format)
        await fulfillment(of: [sampled], timeout: 2)
        XCTAssertNotNil(monitor.currentAnalysis(settings: settings))
        await fulfillment(of: [stale], timeout: 2)
        XCTAssertNil(monitor.analysis)
        XCTAssertNil(monitor.currentAnalysis(settings: settings))
        monitor.deactivate(owner: owner)
    }

    func testDisabledAndLUTOnlySettingsNeverReadPixelsAndNilClearsState() throws {
        guard #available(iOS 17.4, *) else { throw XCTSkip("Displayed pixel API starts at iOS 17.4") }
        var reads = 0
        let monitor = IOSRTPPixelMonitor(readPixels: { _ in reads += 1; return nil })
        let source = try makeRTPMonitorSample(width: 12, height: 8)
        let layer = AVSampleBufferDisplayLayer()
        let owner = UUID()
        monitor.configure(settings: LiveViewMonitorSettings(), owner: owner)
        monitor.frameSubmitted(layer: layer, owner: UUID(), format: source.format)
        XCTAssertEqual(reads, 0)
        var lutOnly = LiveViewMonitorSettings()
        lutOnly.cubeLut = CubeLut(name: "Synthetic", size: 2, domainMin: [0, 0, 0], domainMax: [1, 1, 1],
                               values: Array(repeating: 0, count: 24))
        monitor.configure(settings: lutOnly, owner: owner)
        monitor.frameSubmitted(layer: layer, owner: UUID(), format: source.format)
        XCTAssertEqual(reads, 0, "A retained inactive LUT must not start pixel readback")
        var settings = LiveViewMonitorSettings()
        settings.histogramVisible = true
        monitor.configure(settings: settings, owner: owner)
        monitor.frameSubmitted(layer: layer, owner: UUID(), format: source.format)
        XCTAssertEqual(reads, 1)
        XCTAssertNil(monitor.analysis)
        XCTAssertEqual(monitor.status, .unavailable)
        monitor.deactivate(owner: owner)
        XCTAssertEqual(monitor.status, .off)
    }

    func testOldViewCannotDeactivateReplacementAndInvalidationKeepsPhysicalSlot() async throws {
        guard #available(iOS 17.4, *) else { throw XCTSkip("Displayed pixel API starts at iOS 17.4") }
        let source = try makeRTPMonitorSample(width: 12, height: 8)
        let gate = RTPAnalysisGate()
        defer { gate.release.signal() }
        var reads = 0
        let monitor = IOSRTPPixelMonitor(readPixels: { _ in reads += 1; return source.buffer },
            analyze: { _, _ in
                gate.started.fulfill()
                _ = gate.release.wait(timeout: .now() + 5)
                return LiveViewMonitorAnalysis(width: 1, height: 1, histogram: [1], waveform: nil, overlayRGBA: nil)
            })
        var settings = LiveViewMonitorSettings()
        settings.histogramVisible = true
        let firstView = UUID(), secondView = UUID(), sourceOwner = UUID()
        let layer = AVSampleBufferDisplayLayer()
        monitor.configure(settings: settings, owner: firstView)
        monitor.frameSubmitted(layer: layer, owner: sourceOwner, format: source.format)
        await fulfillment(of: [gate.started], timeout: 2)
        monitor.configure(settings: settings, owner: secondView)
        monitor.deactivate(owner: firstView)
        XCTAssertEqual(monitor.status, .waiting)
        for _ in 0..<10 { monitor.frameSubmitted(layer: layer, owner: sourceOwner, format: source.format) }
        XCTAssertEqual(reads, 1, "A new view cannot start overlapping physical work")
        monitor.deactivate(owner: secondView)
        gate.release.signal()
        let deadline = ProcessInfo.processInfo.systemUptime + 2
        while monitor.isProcessing, ProcessInfo.processInfo.systemUptime < deadline { await Task.yield() }
        XCTAssertFalse(monitor.isProcessing, "Wait for the real worker completion before inspecting retirement")
        XCTAssertNil(monitor.analysis)
        XCTAssertEqual(monitor.status, .off)
    }

    func testHeldFlushCompletionCannotReopenReplacementAttachmentReadback() async {
        var completions: [@Sendable () -> Void] = []
        let controller = IOSCcapiRTPController(flushRenderer: { _, _, complete in completions.append(complete) })
        let first = AVSampleBufferDisplayLayer(), second = AVSampleBufferDisplayLayer()
        controller.attach(first)
        XCTAssertTrue(controller.isPixelReadbackSuspended)
        let oldCompletion = completions.removeFirst()
        controller.attach(second)
        oldCompletion()
        await Task.yield()
        XCTAssertTrue(controller.isPixelReadbackSuspended, "A retired removal callback cannot reopen new readback")
        // First pending completion belongs to detaching A; last belongs to B.
        completions.removeFirst()()
        await Task.yield()
        XCTAssertTrue(controller.isPixelReadbackSuspended)
        completions.removeFirst()()
        let deadline = ProcessInfo.processInfo.systemUptime + 2
        while controller.isPixelReadbackSuspended, ProcessInfo.processInfo.systemUptime < deadline { await Task.yield() }
        XCTAssertFalse(controller.isPixelReadbackSuspended)
        controller.detach(second)
    }

    func testSameLayerUpdatesDoNotFlushAndOldDetachDoesNotRetireNewLayer() {
        var flushed: [ObjectIdentifier] = []
        let controller = IOSCcapiRTPController(flushRenderer: { layer, _, complete in flushed.append(ObjectIdentifier(layer)); complete() })
        let first = AVSampleBufferDisplayLayer(), second = AVSampleBufferDisplayLayer()
        controller.attach(first)
        for _ in 0..<50 { controller.attach(first) }
        XCTAssertEqual(flushed, [ObjectIdentifier(first)])
        XCTAssertEqual(first.videoGravity, .resize)
        controller.attach(second)
        XCTAssertEqual(flushed, [ObjectIdentifier(first), ObjectIdentifier(first), ObjectIdentifier(second)])
        controller.detach(first)
        XCTAssertEqual(flushed.count, 3)
        controller.setRenderingEnabled(false)
        controller.setRenderingEnabled(false)
        XCTAssertEqual(flushed.count, 4)
        controller.setRenderingEnabled(true)
        XCTAssertEqual(flushed.count, 5, "Resuming must reset the skipped inter-frame prediction chain")
        controller.detach(second)
        XCTAssertEqual(flushed.count, 6)
    }
}

private final class RTPAnalysisGate: @unchecked Sendable {
    let started = XCTestExpectation(description: "Analysis is physically in flight")
    let release = DispatchSemaphore(value: 0)
}

private func makeRTPMonitorSample(width: Int, height: Int) throws -> IOSRTPPixelSample {
    var buffer: CVPixelBuffer?
    let attributes = [kCVPixelBufferBytesPerRowAlignmentKey as String: 64] as CFDictionary
    XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA,
                                      attributes, &buffer), kCVReturnSuccess)
    let pixels = try XCTUnwrap(buffer)
    XCTAssertEqual(CVPixelBufferLockBaseAddress(pixels, []), kCVReturnSuccess)
    let bytes = try XCTUnwrap(CVPixelBufferGetBaseAddress(pixels)).assumingMemoryBound(to: UInt8.self)
    let stride = CVPixelBufferGetBytesPerRow(pixels)
    for y in 0..<height {
        for x in 0..<width {
            let value: UInt8 = x < width / 2 ? 0 : 255
            let offset = y * stride + x * 4
            bytes[offset] = value; bytes[offset + 1] = value; bytes[offset + 2] = value; bytes[offset + 3] = 255
        }
    }
    CVPixelBufferUnlockBaseAddress(pixels, [])
    var format: CMVideoFormatDescription?
    XCTAssertEqual(CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault,
        imageBuffer: pixels, formatDescriptionOut: &format), noErr)
    return IOSRTPPixelSample(buffer: pixels, format: try XCTUnwrap(format))
}
