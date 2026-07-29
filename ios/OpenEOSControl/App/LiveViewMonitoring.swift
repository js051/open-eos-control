import CoreGraphics
import Foundation
import ImageIO
import UIKit

enum LiveViewFrameGuide: String, CaseIterable, Identifiable, Sendable {
    case off
    case ratio16x9
    case ratio2x39
    case ratio1x1
    case ratio4x3

    var id: String { rawValue }

    var aspectRatio: CGFloat? {
        switch self {
        case .off: nil
        case .ratio16x9: 16.0 / 9.0
        case .ratio2x39: 2.39
        case .ratio1x1: 1
        case .ratio4x3: 4.0 / 3.0
        }
    }
}

enum LiveViewDesqueeze: String, CaseIterable, Identifiable, Sendable {
    case off
    case x1_33
    case x1_5
    case x1_8
    case x2

    var id: String { rawValue }

    var horizontalScale: CGFloat {
        switch self {
        case .off: 1
        case .x1_33: 1.33
        case .x1_5: 1.5
        case .x1_8: 1.8
        case .x2: 2
        }
    }
}

struct LiveViewMonitorSettings: Equatable, Hashable, Sendable {
    var histogramVisible = false
    var zebraThresholdPercent: Int?
    var falseColorEnabled = false
    var focusPeakingEnabled = false
    var frameGuide = LiveViewFrameGuide.off
    var safeAreaVisible = false
    var desqueeze = LiveViewDesqueeze.off

    var needsPixelAnalysis: Bool {
        histogramVisible || zebraThresholdPercent != nil || falseColorEnabled || focusPeakingEnabled
    }
}

struct LiveViewMonitorAnalysis: Equatable, Sendable {
    let width: Int
    let height: Int
    let histogram: [Int]
    let overlayRGBA: [UInt8]?

    func overlayImage() -> UIImage? {
        guard let overlayRGBA else { return nil }
        let data = Data(overlayRGBA) as CFData
        guard let provider = CGDataProvider(data: data),
              let image = CGImage(
                width: width,
                height: height,
                bitsPerComponent: 8,
                bitsPerPixel: 32,
                bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo(
                    rawValue: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.last.rawValue
                ),
                provider: provider,
                decode: nil,
                shouldInterpolate: false,
                intent: .defaultIntent
              ) else {
            return nil
        }
        return UIImage(cgImage: image)
    }
}

func liveViewAnalysisDimensions(width: Int, height: Int) -> (width: Int, height: Int) {
    precondition(width > 0 && height > 0)
    let scale = min(
        1,
        min(Double(maximumAnalysisWidth) / Double(width), Double(maximumAnalysisHeight) / Double(height))
    )
    return (
        max(1, Int((Double(width) * scale).rounded())),
        max(1, Int((Double(height) * scale).rounded()))
    )
}

func analyzeLiveViewData(_ data: Data, settings: LiveViewMonitorSettings) -> LiveViewMonitorAnalysis? {
    guard settings.needsPixelAnalysis,
          let imageSource = CGImageSourceCreateWithData(data as CFData, nil),
          let source = CGImageSourceCreateThumbnailAtIndex(
            imageSource,
            0,
            [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maximumAnalysisDecodeSize,
            ] as CFDictionary
          ) else {
        return nil
    }
    let dimensions = liveViewAnalysisDimensions(width: source.width, height: source.height)
    var rgba = [UInt8](repeating: 0, count: dimensions.width * dimensions.height * 4)
    let rendered = rgba.withUnsafeMutableBytes { bytes -> Bool in
        guard let context = CGContext(
            data: bytes.baseAddress,
            width: dimensions.width,
            height: dimensions.height,
            bitsPerComponent: 8,
            bytesPerRow: dimensions.width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            return false
        }
        context.interpolationQuality = .medium
        context.translateBy(x: 0, y: CGFloat(dimensions.height))
        context.scaleBy(x: 1, y: -1)
        context.draw(
            source,
            in: CGRect(x: 0, y: 0, width: dimensions.width, height: dimensions.height)
        )
        return true
    }
    guard rendered else { return nil }
    return analyzeLiveViewPixels(
        rgba: rgba,
        width: dimensions.width,
        height: dimensions.height,
        zebraThresholdPercent: settings.zebraThresholdPercent,
        focusPeakingEnabled: settings.focusPeakingEnabled,
        falseColorEnabled: settings.falseColorEnabled
    )
}

func analyzeLiveViewPixels(
    rgba: [UInt8],
    width: Int,
    height: Int,
    zebraThresholdPercent: Int?,
    focusPeakingEnabled: Bool,
    falseColorEnabled: Bool = false
) -> LiveViewMonitorAnalysis {
    precondition(width > 0 && height > 0)
    precondition(rgba.count == width * height * 4)
    precondition(zebraThresholdPercent == nil || (50...100).contains(zebraThresholdPercent!))

    let pixelCount = width * height
    var luminance = [Int](repeating: 0, count: pixelCount)
    var histogram = [Int](repeating: 0, count: histogramBucketCount)
    for index in 0..<pixelCount {
        let offset = index * 4
        let value = (
            54 * Int(rgba[offset])
                + 183 * Int(rgba[offset + 1])
                + 19 * Int(rgba[offset + 2])
        ) >> 8
        luminance[index] = value
        histogram[min(histogramBucketCount - 1, value * histogramBucketCount / 256)] += 1
    }

    let needsOverlay = zebraThresholdPercent != nil || falseColorEnabled || focusPeakingEnabled
    var overlay: [UInt8]?
    let zebraLuminance = zebraThresholdPercent.map { $0 * 255 / 100 }

    if needsOverlay {
        var overlayPixels = [UInt8](repeating: 0, count: pixelCount * 4)
        for y in 0..<height {
            for x in 0..<width {
                let index = y * width + x
                if falseColorEnabled {
                    writeOverlayColor(falseColor(luminance[index]), at: index, into: &overlayPixels)
                }
                if let zebraLuminance, luminance[index] >= zebraLuminance {
                    let color = ((x + y) / 2) % 4 < 2 ? zebraLight : zebraDark
                    writeOverlayColor(color, at: index, into: &overlayPixels)
                }
            }
        }
        if focusPeakingEnabled, width >= 3, height >= 3 {
            for y in 1..<(height - 1) {
                for x in 1..<(width - 1) {
                    let index = y * width + x
                    let gradient = abs(luminance[index + 1] - luminance[index - 1])
                        + abs(luminance[index + width] - luminance[index - width])
                    if gradient >= focusPeakingGradient {
                        writeOverlayColor(focusPeakingColor, at: index, into: &overlayPixels)
                    }
                }
            }
        }
        overlay = overlayPixels
    }

    return LiveViewMonitorAnalysis(
        width: width,
        height: height,
        histogram: histogram,
        overlayRGBA: overlay
    )
}

private func writeOverlayColor(_ color: (UInt8, UInt8, UInt8, UInt8), at index: Int, into rgba: inout [UInt8]) {
    let offset = index * 4
    rgba[offset] = color.0
    rgba[offset + 1] = color.1
    rgba[offset + 2] = color.2
    rgba[offset + 3] = color.3
}

private func falseColor(_ luminance: Int) -> (UInt8, UInt8, UInt8, UInt8) {
    switch luminance {
    case ..<16: (80, 22, 122, 224)
    case ..<40: (32, 61, 160, 224)
    case ..<75: (41, 182, 209, 224)
    case ..<140: (76, 175, 80, 224)
    case ..<190: (242, 209, 61, 224)
    case ..<235: (242, 140, 40, 224)
    default: (232, 59, 53, 224)
    }
}

private let maximumAnalysisWidth = 120
private let maximumAnalysisHeight = 80
private let maximumAnalysisDecodeSize = max(maximumAnalysisWidth, maximumAnalysisHeight)
private let histogramBucketCount = 64
private let focusPeakingGradient = 72
private let zebraLight: (UInt8, UInt8, UInt8, UInt8) = (255, 255, 255, 176)
private let zebraDark: (UInt8, UInt8, UInt8, UInt8) = (0, 0, 0, 120)
private let focusPeakingColor: (UInt8, UInt8, UInt8, UInt8) = (40, 197, 217, 255)
