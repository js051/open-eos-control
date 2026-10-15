import CoreGraphics
import Foundation
import ImageIO

/// Forces bounded raster output before claiming decoded-frame evidence.
/// Creating UIImage/CGImage wrappers alone may defer decompression until display.
enum IOSLiveViewJPEGDecoder {
    static let maximumDimension = 8_192
    static let maximumPixels = 8_847_360
    static let maximumEncodedBytes = 12 * 1024 * 1024

    static func validates(_ data: Data) -> Bool {
        guard !data.isEmpty, data.count <= maximumEncodedBytes else { return false }
        return autoreleasepool {
            guard let source = CGImageSourceCreateWithData(data as CFData,
                [kCGImageSourceShouldCache: false] as CFDictionary),
                CGImageSourceGetCount(source) == 1,
                CGImageSourceGetStatus(source) == .statusComplete,
                let metadata = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                let width = metadata[kCGImagePropertyPixelWidth] as? NSNumber,
                let height = metadata[kCGImagePropertyPixelHeight] as? NSNumber else { return false }
            let w = width.intValue, h = height.intValue
            guard w > 0, h > 0, w <= maximumDimension, h <= maximumDimension,
                  w * h <= maximumPixels,
                  let image = CGImageSourceCreateImageAtIndex(source, 0,
                    [kCGImageSourceShouldCache: true, kCGImageSourceShouldCacheImmediately: true] as CFDictionary),
                  image.width == w, image.height == h,
                  CGImageSourceGetStatusAtIndex(source, 0) == .statusComplete,
                  let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8,
                    bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue), context.data != nil else { return false }
            // Drawing materializes actual destination pixels. The bounded bitmap is
            // discarded here; this evidence is still distinct from UI presentation.
            context.draw(image, in: CGRect(x: 0, y: 0, width: CGFloat(w), height: CGFloat(h)))
            return context.makeImage() != nil
        }
    }
}
