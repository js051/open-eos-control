import XCTest

@testable import OpenEOSControl

final class LiveViewMonitoringTests: XCTestCase {
    func testAnalysisDimensionsStayWithinTheBoundedWorkSurface() {
        let dimensions = liveViewAnalysisDimensions(width: 4_000, height: 3_000)

        XCTAssertEqual(dimensions.width, 107)
        XCTAssertEqual(dimensions.height, 80)
        XCTAssertEqual(liveViewAnalysisDimensions(width: 80, height: 40).width, 80)
        XCTAssertEqual(liveViewAnalysisDimensions(width: 80, height: 40).height, 40)
        XCTAssertEqual(liveViewAnalysisDimensions(width: 3_000, height: 4_000).width, 60)
        XCTAssertEqual(liveViewAnalysisDimensions(width: 3_000, height: 4_000).height, 80)
    }

    func testHistogramCountsEveryPixelWithoutCreatingAnOverlay() {
        let rgba: [UInt8] = [
            0, 0, 0, 255,
            128, 128, 128, 255,
            255, 255, 255, 255,
            255, 0, 0, 255,
        ]

        let analysis = analyzeLiveViewPixels(
            rgba: rgba,
            width: 4,
            height: 1,
            zebraThresholdPercent: nil,
            focusPeakingEnabled: false
        )

        XCTAssertEqual(analysis.histogram.reduce(0, +), 4)
        XCTAssertEqual(analysis.histogram.count, 64)
        XCTAssertNil(analysis.overlayRGBA)
        XCTAssertNil(analysis.waveform)
    }

    func testWaveformPreservesHorizontalPositionAndLuminance() throws {
        let analysis = analyzeLiveViewPixels(
            rgba: [
                0, 0, 0, 255,
                255, 255, 255, 255,
            ],
            width: 2,
            height: 1,
            zebraThresholdPercent: nil,
            focusPeakingEnabled: false,
            waveformVisible: true
        )

        let waveform = try XCTUnwrap(analysis.waveform)
        XCTAssertEqual(waveform.width, 64)
        XCTAssertEqual(waveform.height, 64)
        XCTAssertEqual(waveform.density.reduce(0, +), 2)
        XCTAssertEqual(waveform.density[63 * waveform.width], 1)
        XCTAssertEqual(waveform.density[waveform.width - 1], 1)
    }

    func testHistogramAndWaveformRemainMutuallyExclusive() {
        var settings = LiveViewMonitorSettings()

        settings.setHistogramVisible(true)
        XCTAssertTrue(settings.histogramVisible)
        XCTAssertFalse(settings.waveformVisible)

        settings.setWaveformVisible(true)
        XCTAssertFalse(settings.histogramVisible)
        XCTAssertTrue(settings.waveformVisible)
    }

    func testFalseColorMapsDarkPixelsToTheExposurePalette() throws {
        let analysis = analyzeLiveViewPixels(
            rgba: [0, 0, 0, 255],
            width: 1,
            height: 1,
            zebraThresholdPercent: nil,
            focusPeakingEnabled: false,
            falseColorEnabled: true
        )

        XCTAssertEqual(try XCTUnwrap(analysis.overlayRGBA), [80, 22, 122, 224])
        XCTAssertNotNil(analysis.overlayImage())
    }

    func testZebraUsesAlternatingBandsAboveTheSelectedThreshold() throws {
        let rgba = Array(repeating: [UInt8](repeating: 255, count: 4), count: 8).flatMap { $0 }
        let analysis = analyzeLiveViewPixels(
            rgba: rgba,
            width: 8,
            height: 1,
            zebraThresholdPercent: 90,
            focusPeakingEnabled: false
        )
        let overlay = try XCTUnwrap(analysis.overlayRGBA)

        XCTAssertEqual(Array(overlay[0..<4]), [255, 255, 255, 176])
        XCTAssertEqual(Array(overlay[16..<20]), [0, 0, 0, 120])
    }

    func testFocusPeakingMarksStrongInteriorLuminanceEdges() throws {
        var rgba = [UInt8](repeating: 0, count: 3 * 3 * 4)
        for y in 0..<3 {
            for x in 0..<3 {
                let offset = (y * 3 + x) * 4
                let value: UInt8 = x == 2 ? 255 : 0
                rgba[offset] = value
                rgba[offset + 1] = value
                rgba[offset + 2] = value
                rgba[offset + 3] = 255
            }
        }

        let analysis = analyzeLiveViewPixels(
            rgba: rgba,
            width: 3,
            height: 3,
            zebraThresholdPercent: nil,
            focusPeakingEnabled: true
        )
        let overlay = try XCTUnwrap(analysis.overlayRGBA)
        let center = (1 * 3 + 1) * 4

        XCTAssertEqual(Array(overlay[center..<(center + 4)]), [40, 197, 217, 255])
    }

    func testCubeLutUsesRedFastRowsAndTrilinearInterpolation() throws {
        let lut = try parseCubeLut(Self.invertLut, fallbackName: "fallback.cube")

        XCTAssertEqual(lut.name, "Invert")
        XCTAssertEqual(lut.size, 2)
        let result = lut.sample(red: 0.25, green: 0.5, blue: 0.75)
        XCTAssertEqual(result[0], 0.75, accuracy: 0.0001)
        XCTAssertEqual(result[1], 0.5, accuracy: 0.0001)
        XCTAssertEqual(result[2], 0.25, accuracy: 0.0001)
    }

    func testCubeLutRejectsIncompleteAndOneDimensionalFiles() {
        XCTAssertThrowsError(try parseCubeLut("LUT_3D_SIZE 2\n0 0 0", fallbackName: "bad.cube"))
        XCTAssertThrowsError(try parseCubeLut("LUT_1D_SIZE 2\n0 0 0\n1 1 1", fallbackName: "bad.cube"))
    }

    func testCubeLutRendersThroughCoreImage() throws {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8))
        let source = renderer.image { context in
            UIColor(red: 0.25, green: 0.5, blue: 0.75, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
        let data = try XCTUnwrap(source.pngData())
        let lut = try parseCubeLut(Self.invertLut, fallbackName: "invert.cube")

        let result = try XCTUnwrap(renderCubeLutPreview(data: data, lut: lut))
        let sourcePixels = try XCTUnwrap(source.cgImage)
        let resultPixels = try XCTUnwrap(result.cgImage)

        XCTAssertEqual(resultPixels.width, sourcePixels.width)
        XCTAssertEqual(resultPixels.height, sourcePixels.height)
    }

    private static let invertLut = """
        TITLE "Invert"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0 0 0
        DOMAIN_MAX 1 1 1
        1 1 1
        0 1 1
        1 0 1
        0 0 1
        1 1 0
        0 1 0
        1 0 0
        0 0 0
        """
}
