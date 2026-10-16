import CoreImage
import ImageIO
import Metal
import UIKit
import XCTest

@testable import OpenEOSControl

@MainActor
final class CubeLutNumericContractTests: XCTestCase {
    private struct Fixture: Decodable {
        struct Table: Decodable {
            let id: String
            let size: Int
            let domainMin: [Float]
            let domainMax: [Float]
            let values: [Float]

            var lut: CubeLut {
                CubeLut(name: "Synthetic \(id)", size: size,
                    domainMin: domainMin, domainMax: domainMax, values: values)
            }
        }
        struct Vector: Decodable {
            let id: String
            let lutId: String
            let inputRgb: [Float]
            let expectedRgb: [Float]
            let inputRgb8: [Int]?
            let expectedRgb8: [Int]?
        }
        let schemaVersion: Int
        let colorEncoding: String
        let interpolation: String
        let tableOrder: String
        let cpuAbsoluteTolerance: Float
        let luts: [Table]
        let vectors: [Vector]
    }

    private func fixture() throws -> Fixture {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "numeric-vectors", withExtension: "json"))
        let data = try Data(contentsOf: url)
        guard data.count <= 64 * 1024 else { throw invalidFixture() }
        let result = try JSONDecoder().decode(Fixture.self, from: data)
        guard result.schemaVersion == 1, result.colorEncoding == "srgb-display-values",
              result.interpolation == "trilinear", result.tableOrder == "red-fastest",
              result.cpuAbsoluteTolerance == 0.000002,
              (1...16).contains(result.luts.count), (1...32).contains(result.vectors.count),
              Set(result.luts.map(\.id)).count == result.luts.count,
              Set(result.vectors.map(\.id)).count == result.vectors.count else { throw invalidFixture() }
        for table in result.luts {
            guard (1...64).contains(table.id.utf8.count), (2...3).contains(table.size),
                  table.domainMin.count == 3, table.domainMax.count == 3,
                  zip(table.domainMin, table.domainMax).allSatisfy({ $0.isFinite && $1.isFinite && $0 < $1 }),
                  table.values.count == table.size * table.size * table.size * 3,
                  table.values.allSatisfy(\.isFinite) else { throw invalidFixture() }
        }
        for vector in result.vectors {
            guard (1...64).contains(vector.id.utf8.count),
                  result.luts.contains(where: { $0.id == vector.lutId }),
                  vector.inputRgb.count == 3, vector.expectedRgb.count == 3,
                  vector.inputRgb.allSatisfy(\.isFinite),
                  vector.expectedRgb.allSatisfy({ $0.isFinite && (0...1).contains($0) }),
                  (vector.inputRgb8 == nil) == (vector.expectedRgb8 == nil) else { throw invalidFixture() }
            if let input = vector.inputRgb8, let output = vector.expectedRgb8 {
                guard input.count == 3, output.count == 3,
                      (input + output).allSatisfy({ (0...255).contains($0) }),
                      zip(input, vector.inputRgb).allSatisfy({ abs(Float($0) / 255 - $1) <= result.cpuAbsoluteTolerance })
                else { throw invalidFixture() }
            }
        }
        guard result.vectors.filter({ $0.inputRgb8 != nil }).count >= 6 else { throw invalidFixture() }
        return result
    }

    private func invalidFixture() -> NSError { NSError(domain: "SyntheticLutContract", code: 1) }

    func testSharedNumericVectorsMatchActualCpuSampler() throws {
        let fixture = try self.fixture()
        for vector in fixture.vectors {
            let lut = try XCTUnwrap(fixture.luts.first { $0.id == vector.lutId }).lut
            let actual = lut.sample(red: vector.inputRgb[0], green: vector.inputRgb[1], blue: vector.inputRgb[2])
            for channel in 0..<3 {
                XCTAssertEqual(actual[channel], vector.expectedRgb[channel], accuracy: fixture.cpuAbsoluteTolerance,
                    "Shared numeric vector=\(vector.id), channel=\(channel)")
            }
        }
    }

    func testSharedByteVectorsMatchDecodedPreviewAndNativeGpu() throws {
        let fixture = try self.fixture()
        let device = try XCTUnwrap(MTLCreateSystemDefaultDevice(), "Native validation requires a real Metal device")
        let nativeContext = nativeRTPPreviewContext(device: device)
        for vector in fixture.vectors {
            guard let input = vector.inputRgb8, let expected = vector.expectedRgb8 else { continue }
            let lut = try XCTUnwrap(fixture.luts.first { $0.id == vector.lutId }).lut
            let source = try solidImage(input)
            let png = try XCTUnwrap(UIImage(cgImage: source).pngData())
            let preview = try XCTUnwrap(renderCubeLutPreview(data: png, lut: lut)?.cgImage)
            let nativeImage = try XCTUnwrap(nativeRTPApplyLut(CIImage(cgImage: source), lut: lut))
            let native = try XCTUnwrap(nativeContext.createCGImage(nativeImage, from: nativeImage.extent,
                format: .RGBA8, colorSpace: cubeLutPreviewOutputColorSpace))
            let decodedBytes = try rgb(preview)
            let nativeBytes = try rgb(native)
            for channel in 0..<3 {
                XCTAssertLessThanOrEqual(abs(decodedBytes[channel] - expected[channel]), 3,
                    "Decoded vector=\(vector.id), channel=\(channel), actual=\(decodedBytes), expected=\(expected)")
                XCTAssertLessThanOrEqual(abs(nativeBytes[channel] - expected[channel]), 3,
                    "Native vector=\(vector.id), channel=\(channel), actual=\(nativeBytes), expected=\(expected)")
                XCTAssertLessThanOrEqual(abs(decodedBytes[channel] - nativeBytes[channel]), 1,
                    "Decoded/native pipeline mismatch for \(vector.id)")
            }
        }
    }

    func testJpegPreviewUsesDecodedSrgbNumbersRatherThanLinearLight() throws {
        let fixture = try self.fixture()
        for vector in fixture.vectors {
            guard let input = vector.inputRgb8 else { continue }
            let lut = try XCTUnwrap(fixture.luts.first { $0.id == vector.lutId }).lut
            let source = try solidImage(input)
            let jpeg = try XCTUnwrap(UIImage(cgImage: source).jpegData(compressionQuality: 1))
            let encodedSource = try XCTUnwrap(CGImageSourceCreateWithData(jpeg as CFData, nil))
            let decodedSource = try XCTUnwrap(CGImageSourceCreateImageAtIndex(encodedSource, 0, nil))
            // JPEG quantization belongs to decoding. Apply the CPU oracle to the actual decoded bytes.
            let before = try rgb(decodedSource)
            let expected = lut.sample(red: Float(before[0]) / 255, green: Float(before[1]) / 255, blue: Float(before[2]) / 255)
            let output = try rgb(XCTUnwrap(renderCubeLutPreview(data: jpeg, lut: lut)?.cgImage))
            for channel in 0..<3 {
                XCTAssertLessThanOrEqual(abs(Float(output[channel]) - expected[channel] * 255), 3,
                    "JPEG numeric vector=\(vector.id), channel=\(channel), input=\(before), actual=\(output)")
            }
        }
    }

    private func solidImage(_ rgb: [Int]) throws -> CGImage {
        // Keep the alpha literal in the same byte type as RGB. Otherwise Swift can
        // infer the concatenated/repeated array as [Any], which Data cannot accept.
        let pixel: [UInt8] = rgb.map { UInt8($0) } + [UInt8(255)]
        let rgba: [UInt8] = Array(repeating: pixel, count: 8 * 8).flatMap { $0 }
        let provider = try XCTUnwrap(CGDataProvider(data: Data(rgba) as CFData))
        return try XCTUnwrap(CGImage(width: 8, height: 8, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: 8 * 4, space: cubeLutPreviewOutputColorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.last.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent))
    }

    private func rgb(_ image: CGImage) throws -> [Int] {
        var rgba = [UInt8](repeating: 0, count: image.width * image.height * 4)
        let rendered = rgba.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: image.width, height: image.height,
                bitsPerComponent: 8, bytesPerRow: image.width * 4, space: cubeLutPreviewOutputColorSpace,
                bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: CGFloat(image.width), height: CGFloat(image.height)))
            return true
        }
        XCTAssertTrue(rendered)
        guard rendered else { throw invalidFixture() }
        return rgba.prefix(3).map(Int.init)
    }
}
