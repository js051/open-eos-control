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
    func testSuccessfulExactFrameSampleExpiresWithoutFurtherSourceActivity() async throws {
        let source = try makeRTPMonitorSample(width: 12, height: 8)
        let monitor = IOSRTPPixelMonitor()
        let sampled = expectation(description: "Exact sample published")
        let stale = expectation(description: "Same frame expires without another presentation")
        let subscription = monitor.$status.sink {
            if $0 == .sampled { sampled.fulfill() }
            if $0 == .stale { stale.fulfill() }
        }
        defer { subscription.cancel() }
        var settings = LiveViewMonitorSettings(); settings.histogramVisible = true
        let owner = UUID(), token = makeMonitorToken()
        monitor.configure(settings: settings, owner: owner)
        let presentedAt = ProcessInfo.processInfo.systemUptime
        monitor.frameAvailable(token: token, format: source.format, buffer: source.buffer, outputAt: presentedAt)
        await fulfillment(of: [sampled], timeout: 2)
        XCTAssertNotNil(monitor.currentAnalysis(settings: settings, owner: owner))
        XCTAssertNil(monitor.currentOverlayAnalysis(settings: settings, frame: makeMonitorToken(), owner: owner))
        XCTAssertNil(monitor.currentAnalysis(settings: settings, owner: UUID()))
        await fulfillment(of: [stale], timeout: 2)
        // Re-reading the same image cannot acquire a fresh timestamp or publish it again.
        monitor.presentationChanged(to: nil)
        monitor.frameAvailable(token: token, format: source.format, buffer: source.buffer, outputAt: presentedAt)
        XCTAssertFalse(monitor.isProcessing)
        XCTAssertNil(monitor.currentAnalysis(settings: settings, owner: owner))
        monitor.deactivate(owner: owner)
    }

    func testDisabledAndLUTOnlySettingsNeverAnalyzePixels() throws {
        let monitor = IOSRTPPixelMonitor(analyze: { _, _ in XCTFail("Inactive tools must not analyze"); return nil })
        let source = try makeRTPMonitorSample(width: 12, height: 8)
        let owner = UUID(), token = makeMonitorToken()
        monitor.configure(settings: LiveViewMonitorSettings(), owner: owner)
        monitor.frameAvailable(token: token, format: source.format, buffer: source.buffer,
                               outputAt: ProcessInfo.processInfo.systemUptime)
        var lutOnly = LiveViewMonitorSettings()
        lutOnly.cubeLut = CubeLut(name: "Synthetic", size: 2, domainMin: [0, 0, 0], domainMax: [1, 1, 1],
                               values: Array(repeating: 0, count: 24))
        monitor.configure(settings: lutOnly, owner: owner)
        monitor.frameAvailable(token: token, format: source.format, buffer: source.buffer,
                               outputAt: ProcessInfo.processInfo.systemUptime)
        XCTAssertFalse(monitor.isProcessing)
        XCTAssertNil(monitor.analysis)
        monitor.deactivate(owner: owner)
        XCTAssertEqual(monitor.status, .off)
    }

    func testOldViewCannotDeactivateReplacementAndInvalidationKeepsPhysicalSlot() async throws {
        let source = try makeRTPMonitorSample(width: 12, height: 8)
        let gate = RTPAnalysisGate()
        defer { gate.release.signal() }
        let monitor = IOSRTPPixelMonitor(analyze: { _, _ in
            gate.started.fulfill()
            _ = gate.release.wait(timeout: .now() + 5)
            return LiveViewMonitorAnalysis(width: 1, height: 1, histogram: [1], waveform: nil, overlayRGBA: nil)
        })
        var settings = LiveViewMonitorSettings(); settings.histogramVisible = true
        let firstView = UUID(), secondView = UUID(), token = makeMonitorToken()
        monitor.configure(settings: settings, owner: firstView)
        monitor.frameAvailable(token: token, format: source.format, buffer: source.buffer,
                               outputAt: ProcessInfo.processInfo.systemUptime)
        await fulfillment(of: [gate.started], timeout: 2)
        monitor.configure(settings: settings, owner: secondView)
        monitor.deactivate(owner: firstView)
        XCTAssertEqual(monitor.status, .waiting)
        for _ in 0..<10 {
            monitor.frameAvailable(token: token, format: source.format, buffer: source.buffer,
                                   outputAt: ProcessInfo.processInfo.systemUptime)
        }
        XCTAssertTrue(monitor.isProcessing, "A new view cannot release overlapping physical work")
        monitor.deactivate(owner: secondView)
        gate.release.signal()
        let deadline = ProcessInfo.processInfo.systemUptime + 2
        while monitor.isProcessing, ProcessInfo.processInfo.systemUptime < deadline { await Task.yield() }
        XCTAssertFalse(monitor.isProcessing)
        XCTAssertNil(monitor.analysis)
        XCTAssertEqual(monitor.status, .off)
    }

    func testNewSubmissionAndLutChangeRejectHeldAnalysisEvenWithIdenticalPixels() async throws {
        let source = try makeRTPMonitorSample(width: 12, height: 8)
        let gate = RTPAnalysisGate()
        defer { gate.release.signal() }
        let monitor = IOSRTPPixelMonitor(analyze: { _, _ in
            gate.started.fulfill()
            _ = gate.release.wait(timeout: .now() + 5)
            return LiveViewMonitorAnalysis(width: 1, height: 1, histogram: [1], waveform: nil, overlayRGBA: [0, 0, 0, 255])
        })
        var settings = LiveViewMonitorSettings(); settings.histogramVisible = true
        let view = UUID(), token = makeMonitorToken()
        monitor.configure(settings: settings, owner: view)
        monitor.frameAvailable(token: token, format: source.format, buffer: source.buffer,
                               outputAt: ProcessInfo.processInfo.systemUptime)
        await fulfillment(of: [gate.started], timeout: 2)
        // This invalidation precedes renderer enqueue, so a late analysis cannot
        // paint a prior frame's overlay on an asynchronously presented replacement.
        monitor.presentationChanged(to: nil)
        settings.cubeLut = CubeLut(name: "Replacement", size: 2, domainMin: [0, 0, 0], domainMax: [1, 1, 1],
                                  values: Array(repeating: 0, count: 24))
        monitor.configure(settings: settings, owner: view)
        gate.release.signal()
        let deadline = ProcessInfo.processInfo.systemUptime + 2
        while monitor.isProcessing, ProcessInfo.processInfo.systemUptime < deadline { await Task.yield() }
        XCTAssertNil(monitor.analysis)
        XCTAssertNil(monitor.currentAnalysis(settings: settings, owner: view))
        monitor.deactivate(owner: view)
    }

    func testNewerFrameDoesNotStarveSampledScopesButClearsSpatialOverlay() async throws {
        let source = try makeRTPMonitorSample(width: 12, height: 8)
        let gate = RTPAnalysisGate()
        let clock = RTPManualMonitorClock(now: 100)
        defer { gate.release.signal() }
        let monitor = IOSRTPPixelMonitor(analyze: { _, _ in
            gate.started.fulfill()
            _ = gate.release.wait(timeout: .now() + 5)
            return LiveViewMonitorAnalysis(width: 1, height: 1, histogram: [1], waveform: nil, overlayRGBA: [0, 0, 0, 255])
        }, clock: clock.clock)
        let sampled = expectation(description: "Retained sample published before its logical deadline")
        let stale = expectation(description: "Retained sample expires at its original deadline")
        let subscription = monitor.$status.sink {
            // @Published emits before assignment. Observe the event here;
            // inspect monitor state only after the awaited continuation resumes.
            if $0 == .sampled { sampled.fulfill() }
            if $0 == .stale { stale.fulfill() }
        }
        var settings = LiveViewMonitorSettings(); settings.histogramVisible = true
        let view = UUID(), first = makeMonitorToken()
        let second = IOSRTPFrameToken(owner: first.owner, epoch: first.epoch,
                                      lutRevision: first.lutRevision, sequence: 2)
        defer {
            subscription.cancel()
            monitor.deactivate(owner: view)
            clock.cancelAll()
        }
        monitor.configure(settings: settings, owner: view)
        monitor.frameAvailable(token: first, format: source.format, buffer: source.buffer,
                               outputAt: clock.now)
        await fulfillment(of: [gate.started], timeout: 2)
        clock.advance(to: 100.25)
        monitor.presentationChanged(to: nil)
        monitor.frameAvailable(token: second, format: source.format, buffer: source.buffer,
                               outputAt: clock.now)
        monitor.presentationChanged(to: second)
        clock.advance(to: 100.5)
        gate.release.signal()
        await fulfillment(of: [sampled], timeout: 2)
        XCTAssertFalse(monitor.isProcessing)
        XCTAssertNotNil(monitor.currentAnalysis(settings: settings, owner: view), "A bounded retained sample may finish at live frame rates")
        XCTAssertEqual(monitor.analysisFrame, first)
        XCTAssertNil(monitor.currentOverlayAnalysis(settings: settings, frame: second, owner: view))
        XCTAssertNil(monitor.currentOverlayAnalysis(settings: settings, frame: first, owner: view))
        clock.advance(to: 100.999)
        XCTAssertNotNil(monitor.currentAnalysis(settings: settings, owner: view))
        clock.advance(to: 101)
        // Neither publication at 100.5 nor the newer source at 100.25 grants a
        // fresh second. Readback rejects immediately, then expiry clears storage.
        XCTAssertNil(monitor.currentAnalysis(settings: settings, owner: view))
        await fulfillment(of: [stale], timeout: 2)
        XCTAssertNil(monitor.analysis)
        XCTAssertNil(monitor.analysisFrame)
        // Re-reading the original token cannot refresh output identity or age.
        monitor.frameAvailable(token: first, format: source.format, buffer: source.buffer, outputAt: clock.now)
        XCTAssertFalse(monitor.isProcessing)
        XCTAssertNil(monitor.currentAnalysis(settings: settings, owner: view))
    }

    func testExactDeadlineAndFutureOutputNeverStartWithControlledClock() throws {
        let source = try makeRTPMonitorSample(width: 12, height: 8)
        let clock = RTPManualMonitorClock(now: 100)
        let monitor = IOSRTPPixelMonitor(
            analyze: { _, _ in XCTFail("Untrusted output age"); return nil }, clock: clock.clock
        )
        var settings = LiveViewMonitorSettings(); settings.histogramVisible = true
        let view = UUID(), first = makeMonitorToken()
        defer { monitor.deactivate(owner: view); clock.cancelAll() }
        monitor.configure(settings: settings, owner: view)
        monitor.frameAvailable(token: first, format: source.format, buffer: source.buffer, outputAt: 99)
        let second = IOSRTPFrameToken(owner: first.owner, epoch: first.epoch,
                                      lutRevision: first.lutRevision, sequence: 2)
        monitor.frameAvailable(token: second, format: source.format, buffer: source.buffer, outputAt: 100.001)
        XCTAssertFalse(monitor.isProcessing)
        XCTAssertNil(monitor.currentAnalysis(settings: settings, owner: view))
        XCTAssertEqual(clock.pendingWaitCount, 0)
    }

    func testStaleOrFutureOutputAgeNeverStartsAnalysis() throws {
        let source = try makeRTPMonitorSample(width: 12, height: 8)
        let monitor = IOSRTPPixelMonitor(analyze: { _, _ in XCTFail("Untrusted output age"); return nil })
        var settings = LiveViewMonitorSettings(); settings.histogramVisible = true
        let view = UUID(), first = makeMonitorToken()
        monitor.configure(settings: settings, owner: view)
        monitor.frameAvailable(token: first, format: source.format, buffer: source.buffer,
                               outputAt: ProcessInfo.processInfo.systemUptime - 2)
        let second = IOSRTPFrameToken(owner: first.owner, epoch: first.epoch,
                                      lutRevision: first.lutRevision, sequence: 2)
        monitor.frameAvailable(token: second, format: source.format, buffer: source.buffer,
                               outputAt: ProcessInfo.processInfo.systemUptime + 2)
        XCTAssertFalse(monitor.isProcessing)
        XCTAssertNil(monitor.analysis)
        monitor.deactivate(owner: view)
    }

    func testTerminalProducerLatchRejectsHeldAnalysisBeforeMainActorRetirement() async throws {
        let source = try makeRTPMonitorSample(width: 12, height: 8)
        let gate = RTPAnalysisGate()
        defer { gate.release.signal() }
        var active = true
        let monitor = IOSRTPPixelMonitor(isOwnerActive: { _ in active }, analyze: { _, _ in
            gate.started.fulfill()
            _ = gate.release.wait(timeout: .now() + 5)
            return LiveViewMonitorAnalysis(width: 1, height: 1, histogram: [1], waveform: nil, overlayRGBA: nil)
        })
        var settings = LiveViewMonitorSettings(); settings.histogramVisible = true
        let view = UUID(), token = makeMonitorToken()
        monitor.configure(settings: settings, owner: view)
        monitor.frameAvailable(token: token, format: source.format, buffer: source.buffer,
                               outputAt: ProcessInfo.processInfo.systemUptime)
        await fulfillment(of: [gate.started], timeout: 2)
        active = false // Producer terminal latch has fired; no invalidate() callback yet.
        gate.release.signal()
        let deadline = ProcessInfo.processInfo.systemUptime + 2
        while monitor.isProcessing, ProcessInfo.processInfo.systemUptime < deadline { await Task.yield() }
        XCTAssertNil(monitor.analysis)
        XCTAssertNil(monitor.currentAnalysis(settings: settings, owner: view))
        monitor.deactivate(owner: view)
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

/// All mutation and continuation ownership are main-actor confined. Advancing
/// logical time, not host scheduling, controls freshness in ownership tests.
@MainActor
private final class RTPManualMonitorClock {
    private struct Waiter {
        let deadline: TimeInterval
        let continuation: CheckedContinuation<Void, Error>
    }

    private(set) var now: TimeInterval
    private var waiters: [UUID: Waiter] = [:]
    var didRegisterWait: (() -> Void)?
    var pendingWaitCount: Int { waiters.count }
    var clock: IOSRTPMonitorClock {
        IOSRTPMonitorClock(now: { self.now }, sleepUntil: { try await self.sleep(until: $0) })
    }

    init(now: TimeInterval) { self.now = now }

    func advance(to next: TimeInterval) {
        precondition(next.isFinite && next >= now)
        now = next
        for id in waiters.keys.filter({ waiters[$0]!.deadline <= now }) {
            waiters.removeValue(forKey: id)?.continuation.resume()
        }
    }

    func cancelAll() {
        let pending = Array(waiters.values)
        waiters.removeAll()
        for waiter in pending { waiter.continuation.resume(throwing: CancellationError()) }
    }

    private func sleep(until deadline: TimeInterval) async throws {
        try Task.checkCancellation()
        guard deadline > now else { return }
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                // Cancellation before registration cannot leave an orphan waiter.
                if Task.isCancelled {
                    continuation.resume(throwing: CancellationError())
                } else if deadline <= now {
                    continuation.resume()
                } else {
                    waiters[id] = Waiter(deadline: deadline, continuation: continuation)
                    didRegisterWait?()
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.waiters.removeValue(forKey: id)?.continuation.resume(throwing: CancellationError())
            }
        }
    }
}

@MainActor
final class RTPManualMonitorClockTests: XCTestCase {
    func testCancellationBeforeRegistrationLeavesNoWaiter() async {
        let clock = RTPManualMonitorClock(now: 100)
        let finished = expectation(description: "Cancelled before clock registration")
        let task = Task { @MainActor in
            do {
                try await clock.clock.sleepUntil(101)
                XCTFail("A cancelled wait cannot succeed")
            } catch {
                XCTAssertTrue(error is CancellationError)
            }
            finished.fulfill()
        }
        // This synchronous main-actor turn cancels before the child can start.
        task.cancel()
        defer { task.cancel(); clock.cancelAll() }
        await fulfillment(of: [finished], timeout: 2)
        XCTAssertEqual(clock.pendingWaitCount, 0)
    }

    func testRegisteredCancellationAndExactDeadlineResumeOnlyTheirWaiters() async {
        let clock = RTPManualMonitorClock(now: 100)
        let registered = expectation(description: "Both waits registered")
        registered.expectedFulfillmentCount = 2
        clock.didRegisterWait = { registered.fulfill() }
        let cancelled = expectation(description: "First waiter cancelled")
        let expired = expectation(description: "Second waiter reaches its exact deadline")
        var didExpire = false
        let first = Task { @MainActor in
            do {
                try await clock.clock.sleepUntil(101)
                XCTFail("Cancellation must not become expiry success")
            } catch {
                XCTAssertTrue(error is CancellationError)
            }
            cancelled.fulfill()
        }
        let second = Task { @MainActor in
            do {
                try await clock.clock.sleepUntil(101)
                didExpire = true
            } catch {
                XCTFail("The uncancelled waiter must reach its deadline")
            }
            expired.fulfill()
        }
        defer { first.cancel(); second.cancel(); clock.cancelAll() }
        await fulfillment(of: [registered], timeout: 2)
        XCTAssertEqual(clock.pendingWaitCount, 2)
        first.cancel()
        await fulfillment(of: [cancelled], timeout: 2)
        XCTAssertEqual(clock.pendingWaitCount, 1)
        clock.advance(to: 100.999)
        XCTAssertEqual(clock.pendingWaitCount, 1)
        XCTAssertFalse(didExpire)
        clock.advance(to: 101)
        await fulfillment(of: [expired], timeout: 2)
        XCTAssertTrue(didExpire)
        XCTAssertEqual(clock.pendingWaitCount, 0)
        // Repeated cancellation/advancement cannot resume a continuation twice.
        first.cancel(); second.cancel(); clock.advance(to: 102)
        XCTAssertEqual(clock.pendingWaitCount, 0)
    }
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

private func makeMonitorToken() -> IOSRTPFrameToken {
    IOSRTPFrameToken(owner: UUID(), epoch: UUID(), lutRevision: UUID(), sequence: 1)
}
