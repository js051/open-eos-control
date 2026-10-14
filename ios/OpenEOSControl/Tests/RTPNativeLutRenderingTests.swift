import AVFoundation
import CoreImage
import OpenEOSCore
import UIKit
import XCTest

@testable import OpenEOSControl

@MainActor
final class RTPNativeLutRenderingTests: XCTestCase {
    @MainActor private final class ResultBox {
        var value: IOSNativeVideoResult?
    }

    private let sps = Data(base64Encoded: "Z2QQCqy4j01AgIBQAAADABAAAAMAIEA=")!
    private let pps = Data(base64Encoded: "aO4BLLIs")!
    private let idr = Data(base64Encoded: "ZYiEOv7zsH5llXDI3Pd/erFOvF6tikmf2dcpOespYUQb9VPplN9CNDI+FJld7mH+8lOcTtgPfUmtaxEtJLrGiWYEiQZZ1Tj+fmI1L6iwUgXTm/eptJRWI47Km8qCgraCtv1ItxVE4r7f7mKNN6KBlnZpSgWGTquq042kkPr5")!

    private func input() throws -> (CMSampleBuffer, CMVideoFormatDescription) {
        let format = try IOSCcapiRTPController.makeFormatDescription(sps: sps, pps: pps)
        let unit = CCAPIH264AccessUnit(nalUnits: [idr], rtpTimestamp: 90_000, keyFrame: true,
                                     sequenceParameterSet: sps, pictureParameterSet: pps)
        return (try IOSCcapiRTPController.makeSampleBuffer(accessUnit: unit, formatDescription: format), format)
    }

    private func lut(_ transform: (Float, Float, Float) -> [Float]) -> CubeLut {
        var values: [Float] = []
        for b in 0...1 { for g in 0...1 { for r in 0...1 {
            values += transform(Float(r), Float(g), Float(b))
        } } }
        return CubeLut(name: "Synthetic numeric fixture", size: 2, domainMin: [0, 0, 0], domainMax: [1, 1, 1], values: values)
    }

    private func process(_ pipeline: IOSRTPVideoProcessor, lut: CubeLut?) async throws -> IOSNativeVideoResult {
        let (sample, format) = try input()
        let box = ResultBox()
        let done = expectation(description: "Real VideoToolbox and Core Image output")
        XCTAssertTrue(pipeline.submit(sample, format: format, lut: lut, sps: sps) { result in
            box.value = result
            done.fulfill()
        })
        await fulfillment(of: [done], timeout: 8)
        return try XCTUnwrap(box.value)
    }

    private func frame(_ result: IOSNativeVideoResult) throws -> IOSNativeProcessedFrame {
        guard case let .frame(frame) = result else {
            XCTFail("Real native pipeline did not produce a frame: \(result)")
            throw NSError(domain: "SyntheticNativeLut", code: 1)
        }
        return frame
    }

    private func pixels(_ buffer: CVPixelBuffer) throws -> [[Int]] {
        let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
        let context = CIContext(options: [.workingColorSpace: space, .outputColorSpace: space])
        let input = CIImage(cvPixelBuffer: buffer)
        let image = try XCTUnwrap(context.createCGImage(input, from: input.extent, format: .RGBA8, colorSpace: space))
        let data = try XCTUnwrap(image.dataProvider?.data) as Data
        return [(16, 12), (48, 12), (16, 36), (48, 36)].map { x, y in
            let offset = y * image.bytesPerRow + x * 4
            return [Int(data[offset]), Int(data[offset + 1]), Int(data[offset + 2])]
        }
    }

    func testRealH264DecodeAndGpuLutPreserveQuadrantRowsAndSwapChannels() async throws {
        let pipeline = IOSRTPVideoProcessor()
        defer { pipeline.invalidate() }
        let identity = try frame(await process(pipeline, lut: lut { [$0, $1, $2] }))
        let swapped = try frame(await process(pipeline, lut: lut { [$2, $1, $0] }))
        let original = try pixels(identity.pixelBuffer)
        let transformed = try pixels(swapped.pixelBuffer)
        let expected = [[224, 40, 56], [48, 200, 72], [40, 80, 216], [176, 144, 104]]
        for row in 0..<4 {
            for channel in 0..<3 {
                XCTAssertLessThanOrEqual(abs(original[row][channel] - expected[row][channel]), 18,
                                         "SDR decoder color conversion tolerance, not calibrated exposure")
                XCTAssertLessThanOrEqual(abs(transformed[row][channel] - original[row][2 - channel]), 3)
            }
        }
    }

    func testRealGpuNonlinearDomainInterpolationMatchesNumericOracle() async throws {
        let pipeline = IOSRTPVideoProcessor()
        defer { pipeline.invalidate() }
        let identity = try frame(await process(pipeline, lut: lut { [$0, $1, $2] }))
        let values = lut { [$0 * $1 * 1.6 - 0.2, $1 * $2 * 1.2, $2 * $0 * 1.4] }.values
        let curved = CubeLut(name: "Nonlinear domain fixture", size: 2,
            domainMin: [-0.1, 0.05, -0.2], domainMax: [0.9, 0.85, 1.1], values: values)
        let output = try frame(await process(pipeline, lut: curved))
        let original = try pixels(identity.pixelBuffer)
        let transformed = try pixels(output.pixelBuffer)
        for row in 0..<4 {
            let expected = curved.sample(red: Float(original[row][0]) / 255,
                green: Float(original[row][1]) / 255, blue: Float(original[row][2]) / 255)
            for channel in 0..<3 {
                XCTAssertLessThanOrEqual(abs(Float(transformed[row][channel]) - expected[channel] * 255), 6,
                    "Native color-space/domain/trilinear behavior must agree with the CPU preview contract")
            }
        }
    }

    func testDecodeOnlyFrameDoesNotConsumeAnOutputPoolBuffer() async throws {
        let pipeline = IOSRTPVideoProcessor()
        defer { pipeline.invalidate() }
        let (sample, format) = try input()
        let done = expectation(description: "Decode without presentation")
        XCTAssertTrue(pipeline.submit(sample, format: format, lut: nil, sps: sps, present: false) { result in
            guard case .backpressure = result else { return XCTFail("Hidden frame must decode without publishing pixels") }
            done.fulfill()
        })
        await fulfillment(of: [done], timeout: 8)
        _ = try frame(await process(pipeline, lut: nil))
    }

    func testPostLutSampleUsesTheProcessedBufferWithoutApplyingLutTwice() async throws {
        let pipeline = IOSRTPVideoProcessor()
        defer { pipeline.invalidate() }
        let transform = lut { [$2, $1, $0] }
        let output = try frame(await process(pipeline, lut: transform))
        var settings = LiveViewMonitorSettings()
        let monitor = IOSRTPPixelMonitor(readPixels: { _ in XCTFail("Supplied post-LUT frame must not read old display pixels"); return nil })
        let owner = UUID()
        let layer = AVSampleBufferDisplayLayer()
        for tool in 0..<5 {
            settings = LiveViewMonitorSettings()
            settings.cubeLut = transform
            switch tool {
            case 0: settings.histogramVisible = true
            case 1: settings.waveformVisible = true
            case 2: settings.zebraThresholdPercent = 70
            case 3: settings.falseColorEnabled = true
            default: settings.focusPeakingEnabled = true
            }
            let expected = try XCTUnwrap(analyzeRTPPixelSample(
                IOSRTPPixelSample(buffer: output.pixelBuffer, format: output.format), settings: settings))
            try await Task.sleep(for: .milliseconds(270)) // Respect the production acquisition cadence.
            monitor.configure(settings: settings, owner: owner)
            monitor.frameSubmitted(layer: layer, owner: owner, format: output.format, suppliedBuffer: output.pixelBuffer)
            let deadline = ProcessInfo.processInfo.systemUptime + 3
            while monitor.isProcessing && ProcessInfo.processInfo.systemUptime < deadline {
                try await Task.sleep(for: .milliseconds(10))
            }
            XCTAssertEqual(monitor.analysis, expected, "All five tools analyze the already-filtered native pixels once")
        }
        var changed = settings; changed.cubeLut = lut { [$0, $1, $2] }
        XCTAssertNil(monitor.currentAnalysis(settings: changed), "Old LUT identity cannot publish for new settings")
        monitor.deactivate(owner: owner)
    }

    func testOutputPoolNeverAllocatesBeyondThreeRetainedFrames() async throws {
        let pipeline = IOSRTPVideoProcessor()
        defer { pipeline.invalidate() }
        var held: [IOSNativeProcessedFrame] = []
        for _ in 0..<3 { held.append(try frame(await process(pipeline, lut: nil))) }
        let fourth = try await process(pipeline, lut: nil)
        guard case .backpressure = fourth else { return XCTFail("Retained frame pool must refuse a fourth allocation") }
        XCTAssertEqual(held.count, 3)
        held.removeAll()
        _ = try frame(await process(pipeline, lut: nil))
    }

    func testActualNativeLayerReadbackContainsTheFilteredFrame() async throws {
        guard #available(iOS 17.4, *) else { throw XCTSkip("Actual displayed-buffer API starts at iOS 17.4") }
        let pipeline = IOSRTPVideoProcessor()
        defer { pipeline.invalidate() }
        let output = try frame(await process(pipeline, lut: lut { [1 - $0, 1 - $1, 1 - $2] }))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 128, height: 96)
        let controller = UIViewController()
        window.rootViewController = controller
        let layer = AVSampleBufferDisplayLayer()
        layer.frame = CGRect(x: 0, y: 0, width: 128, height: 96)
        layer.videoGravity = .resize
        controller.view.layer.addSublayer(layer)
        window.isHidden = false
        defer { layer.flushAndRemoveImage(); layer.removeFromSuperlayer(); window.isHidden = true }
        layer.sampleBufferRenderer.enqueue(output.sampleBuffer)
        let deadline = ProcessInfo.processInfo.systemUptime + 4
        var displayed: CVPixelBuffer?
        while displayed == nil && ProcessInfo.processInfo.systemUptime < deadline {
            try await Task.sleep(for: .milliseconds(20))
            displayed = layer.sampleBufferRenderer.displayedPixelBuffer()
        }
        let actual = try pixels(XCTUnwrap(displayed, "Native renderer must display the actual filtered buffer"))
        let expected = try pixels(output.pixelBuffer)
        for row in 0..<4 { for channel in 0..<3 {
            XCTAssertLessThanOrEqual(abs(actual[row][channel] - expected[row][channel]), 4)
        } }
    }

    func testRetirementKeepsPhysicalSlotAndSuppressesLateNativeCompletion() async throws {
        let pipeline = IOSRTPVideoProcessor()
        defer { pipeline.invalidate() }
        let (sample, format) = try input()
        let box = ResultBox()
        XCTAssertTrue(pipeline.submit(sample, format: format, lut: nil, sps: sps) { box.value = $0 })
        pipeline.invalidate()
        XCTAssertTrue(pipeline.busy, "Retirement must not release the physical decode/render slot")
        XCTAssertFalse(pipeline.submit(sample, format: format, lut: nil, sps: sps) { _ in
            XCTFail("A retired physical job still owns the slot")
        })
        let deadline = ProcessInfo.processInfo.systemUptime + 8
        while pipeline.busy && ProcessInfo.processInfo.systemUptime < deadline {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertFalse(pipeline.busy)
        XCTAssertNil(box.value, "Old native completion must not publish after invalidation")
        _ = try frame(await process(pipeline, lut: nil))
    }

    func testHighDepthProfilesNeverEnterTheSdrProcessingPath() throws {
        let (_, format) = try input()
        XCTAssertTrue(nativeRTPPreviewColorSupported(format: format, sps: sps))
        for profile in [UInt8(44), 110, 122, 244, 255] {
            var unsupported = sps; unsupported[unsupported.startIndex + 1] = profile
            XCTAssertFalse(nativeRTPPreviewColorSupported(format: format, sps: unsupported))
        }
        XCTAssertFalse(nativeRTPPreviewColorSupported(format: format, sps: Data()))
    }
}

final class RTPVideoDeliveryBoundTests: XCTestCase {
    func testPendingMainActorDeliveryIsBoundedAndOverflowWaitsForIdr() throws {
        var gate = IOSRTPVideoDeliveryGate()
        for _ in 0..<4 { XCTAssertNotNil(gate.admit(keyFrame: false)) }
        XCTAssertEqual(gate.pending, 4)
        XCTAssertNil(gate.admit(keyFrame: false))
        gate.complete()
        XCTAssertNil(gate.admit(keyFrame: false), "A missing reference requires IDR")
        let recovery = try XCTUnwrap(gate.admit(keyFrame: true))
        XCTAssertEqual(recovery.sequence, 7)
        XCTAssertEqual(gate.pending, 4)
        for _ in 0..<5 { gate.complete() }
        XCTAssertEqual(gate.pending, 0)
    }

    func testReorderedOrMissingCompressedDeliveryCannotContinuePrediction() {
        var gate = IOSRTPVideoDecodeContinuity()
        XCTAssertFalse(gate.admit(sequence: 1, keyFrame: false))
        XCTAssertTrue(gate.admit(sequence: 2, keyFrame: true))
        XCTAssertTrue(gate.admit(sequence: 3, keyFrame: false))
        XCTAssertFalse(gate.admit(sequence: 2, keyFrame: true), "Stale IDR cannot roll the stream backward")
        XCTAssertFalse(gate.admit(sequence: 5, keyFrame: false))
        XCTAssertFalse(gate.admit(sequence: 6, keyFrame: false))
        XCTAssertTrue(gate.admit(sequence: 7, keyFrame: true))
        XCTAssertTrue(gate.admit(sequence: 8, keyFrame: false))
    }
}
