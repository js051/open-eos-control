import CoreImage
import Foundation
import Metal
import UIKit

struct CubeLut: Identifiable, Hashable, Sendable {
    let id = UUID()
    let name: String
    let size: Int
    let domainMin: [Float]
    let domainMax: [Float]
    let values: [Float]
    let rgbaData: Data

    init(name: String, size: Int, domainMin: [Float], domainMax: [Float], values: [Float]) {
        self.name = name
        self.size = size
        self.domainMin = domainMin
        self.domainMax = domainMax
        self.values = values
        var rgba = [Float]()
        rgba.reserveCapacity(size * size * size * 4)
        for index in stride(from: 0, to: values.count, by: 3) {
            rgba.append(values[index])
            rgba.append(values[index + 1])
            rgba.append(values[index + 2])
            rgba.append(1)
        }
        rgbaData = rgba.withUnsafeBytes { Data($0) }
    }

    static func == (lhs: CubeLut, rhs: CubeLut) -> Bool { lhs.id == rhs.id }

    func hash(into hasher: inout Hasher) { hasher.combine(id) }

    func sample(red: Float, green: Float, blue: Float) -> [Float] {
        let rc = coordinate(red, channel: 0)
        let gc = coordinate(green, channel: 1)
        let bc = coordinate(blue, channel: 2)
        let r0 = Int(floor(rc))
        let g0 = Int(floor(gc))
        let b0 = Int(floor(bc))
        let r1 = min(size - 1, r0 + 1)
        let g1 = min(size - 1, g0 + 1)
        let b1 = min(size - 1, b0 + 1)
        let rt = rc - Float(r0)
        let gt = gc - Float(g0)
        let bt = bc - Float(b0)
        return (0..<3).map { channel in
            let c00 = lerp(value(r0, g0, b0, channel), value(r1, g0, b0, channel), rt)
            let c10 = lerp(value(r0, g1, b0, channel), value(r1, g1, b0, channel), rt)
            let c01 = lerp(value(r0, g0, b1, channel), value(r1, g0, b1, channel), rt)
            let c11 = lerp(value(r0, g1, b1, channel), value(r1, g1, b1, channel), rt)
            return min(1, max(0, lerp(lerp(c00, c10, gt), lerp(c01, c11, gt), bt)))
        }
    }

    private func coordinate(_ value: Float, channel: Int) -> Float {
        min(1, max(0, (value - domainMin[channel]) / (domainMax[channel] - domainMin[channel])))
            * Float(size - 1)
    }

    private func value(_ red: Int, _ green: Int, _ blue: Int, _ channel: Int) -> Float {
        values[((blue * size * size + green * size + red) * 3) + channel]
    }
}

enum CubeLutError: LocalizedError {
    case invalid(String)

    var errorDescription: String? {
        switch self {
        case let .invalid(message): message
        }
    }
}

func parseCubeLut(_ text: String, fallbackName: String) throws -> CubeLut {
    guard text.utf8.count <= maximumCubeLutBytes else {
        throw CubeLutError.invalid("3D LUT exceeds the 16 MiB limit.")
    }
    var title: String?
    var size: Int?
    var domainMin: [Float] = [0, 0, 0]
    var domainMax: [Float] = [1, 1, 1]
    var hasDomainMin = false
    var hasDomainMax = false
    var values: [Float] = []

    for (zeroBasedLine, sourceLine) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
        let line = String(sourceLine).split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0]
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if line.isEmpty { continue }
        let tokens = line.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        let lineNumber = zeroBasedLine + 1
        switch tokens[0].uppercased() {
        case "TITLE":
            guard title == nil else { throw CubeLutError.invalid("Duplicate TITLE at line \(lineNumber).") }
            let raw = String(line.dropFirst(tokens[0].count)).trimmingCharacters(in: .whitespaces)
            title = raw.trimmingCharacters(in: CharacterSet(charactersIn: "\"")).nilIfBlank
        case "LUT_3D_SIZE":
            guard size == nil, values.isEmpty, tokens.count == 2,
                  let parsed = Int(tokens[1]), (minimumCubeLutSize...maximumCubeLutSize).contains(parsed) else {
                throw CubeLutError.invalid("3D LUT size must be between 2 and 64 (line \(lineNumber)).")
            }
            size = parsed
            values.reserveCapacity(parsed * parsed * parsed * 3)
        case "DOMAIN_MIN":
            guard !hasDomainMin, values.isEmpty else {
                throw CubeLutError.invalid("Duplicate or late DOMAIN_MIN at line \(lineNumber).")
            }
            domainMin = try parseCubeVector(tokens, lineNumber: lineNumber)
            hasDomainMin = true
        case "DOMAIN_MAX":
            guard !hasDomainMax, values.isEmpty else {
                throw CubeLutError.invalid("Duplicate or late DOMAIN_MAX at line \(lineNumber).")
            }
            domainMax = try parseCubeVector(tokens, lineNumber: lineNumber)
            hasDomainMax = true
        case "LUT_3D_INPUT_RANGE":
            guard !hasDomainMin, !hasDomainMax, values.isEmpty, tokens.count == 3 else {
                throw CubeLutError.invalid("Duplicate or conflicting input range at line \(lineNumber).")
            }
            let minimum = try finiteFloat(tokens[1], lineNumber: lineNumber)
            let maximum = try finiteFloat(tokens[2], lineNumber: lineNumber)
            domainMin = [minimum, minimum, minimum]
            domainMax = [maximum, maximum, maximum]
            hasDomainMin = true
            hasDomainMax = true
        case "LUT_1D_SIZE":
            throw CubeLutError.invalid("1D and shaper LUTs are not supported.")
        default:
            guard let size else {
                throw CubeLutError.invalid("LUT_3D_SIZE must appear before table data (line \(lineNumber)).")
            }
            guard tokens.count == 3 else { throw CubeLutError.invalid("Invalid 3D LUT row at line \(lineNumber).") }
            values.append(contentsOf: try tokens.map { try finiteFloat($0, lineNumber: lineNumber) })
            guard values.count <= size * size * size * 3 else {
                throw CubeLutError.invalid("3D LUT contains more rows than LUT_3D_SIZE declares.")
            }
        }
    }

    guard let size else { throw CubeLutError.invalid("LUT_3D_SIZE is required.") }
    guard zip(domainMin, domainMax).allSatisfy({ $0.isFinite && $1.isFinite && $0 < $1 }) else {
        throw CubeLutError.invalid("Every LUT domain minimum must be lower than its maximum.")
    }
    let expectedRows = size * size * size
    guard values.count == expectedRows * 3 else {
        throw CubeLutError.invalid("3D LUT requires \(expectedRows) RGB rows; found \(values.count / 3).")
    }
    let fallback = URL(fileURLWithPath: fallbackName).deletingPathExtension().lastPathComponent
    let name = String((title ?? fallback.nilIfBlank ?? "Imported LUT").prefix(maximumCubeLutNameLength))
    return CubeLut(name: name, size: size, domainMin: domainMin, domainMax: domainMax, values: values)
}

// Display previews use encoded sRGB numbers on every platform, not scene-linear light.
// Extended range preserves negative/over-range table entries until after interpolation.
let cubeLutPreviewOutputColorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
private let cubeLutWorkingColorSpace = CGColorSpace(name: CGColorSpace.extendedSRGB)!

func cubeLutPreviewContext(device: MTLDevice? = nil) -> CIContext {
    let options: [CIContextOption: Any] = [
        .workingColorSpace: cubeLutWorkingColorSpace,
        .outputColorSpace: cubeLutPreviewOutputColorSpace,
        .cacheIntermediates: false,
    ]
    if let device { return CIContext(mtlDevice: device, options: options) }
    return CIContext(options: options)
}

/// Numeric LUT coordinates use SDR sRGB preview values, matching the CPU/browser oracle.
func applyCubeLutPreview(_ input: CIImage, lut: CubeLut) -> CIImage? {
    guard let normalize = CIFilter(name: "CIColorMatrix"), let clamp = CIFilter(name: "CIColorClamp"),
          let cube = CIFilter(name: "CIColorCubeWithColorSpace"),
          let outputClamp = CIFilter(name: "CIColorClamp") else { return nil }
    guard (2...64).contains(lut.size), lut.domainMin.count == 3, lut.domainMax.count == 3,
          lut.values.count == lut.size * lut.size * lut.size * 3,
          lut.values.allSatisfy({ $0.isFinite }),
          zip(lut.domainMin, lut.domainMax).allSatisfy({ $0.isFinite && $1.isFinite && $0 < $1 }) else { return nil }
    let inverse = zip(lut.domainMin, lut.domainMax).map { 1 / ($1 - $0) }
    let bias = zip(lut.domainMin, inverse).map { -$0 * $1 }
    guard inverse.allSatisfy({ $0.isFinite && $0 > 0 }), bias.allSatisfy({ $0.isFinite }) else { return nil }
    normalize.setValue(input, forKey: kCIInputImageKey)
    normalize.setValue(CIVector(x: CGFloat(inverse[0]), y: 0, z: 0, w: 0), forKey: "inputRVector")
    normalize.setValue(CIVector(x: 0, y: CGFloat(inverse[1]), z: 0, w: 0), forKey: "inputGVector")
    normalize.setValue(CIVector(x: 0, y: 0, z: CGFloat(inverse[2]), w: 0), forKey: "inputBVector")
    normalize.setValue(CIVector(x: 0, y: 0, z: 0, w: 1), forKey: "inputAVector")
    normalize.setValue(CIVector(x: CGFloat(bias[0]),
        y: CGFloat(bias[1]), z: CGFloat(bias[2]), w: 0), forKey: "inputBiasVector")
    guard let normalized = normalize.outputImage else { return nil }
    clamp.setValue(normalized, forKey: kCIInputImageKey)
    clamp.setValue(CIVector(x: 0, y: 0, z: 0, w: 0), forKey: "inputMinComponents")
    clamp.setValue(CIVector(x: 1, y: 1, z: 1, w: 1), forKey: "inputMaxComponents")
    guard let clamped = clamp.outputImage else { return nil }
    cube.setValue(clamped, forKey: kCIInputImageKey)
    cube.setValue(lut.size, forKey: "inputCubeDimension")
    cube.setValue(lut.rgbaData, forKey: "inputCubeData")
    cube.setValue(cubeLutWorkingColorSpace, forKey: "inputColorSpace")
    guard let interpolated = cube.outputImage else { return nil }
    // Clamp the interpolated result, never each table entry. Early clipping changes nonlinear LUTs.
    outputClamp.setValue(interpolated, forKey: kCIInputImageKey)
    outputClamp.setValue(CIVector(x: 0, y: 0, z: 0, w: 0), forKey: "inputMinComponents")
    outputClamp.setValue(CIVector(x: 1, y: 1, z: 1, w: 1), forKey: "inputMaxComponents")
    return outputClamp.outputImage?.cropped(to: input.extent)
}

func renderCubeLutPreview(data: Data, lut: CubeLut) -> UIImage? {
    guard let input = CIImage(data: data, options: [.applyOrientationProperty: true]),
          let output = applyCubeLutPreview(input, lut: lut),
          let cgImage = cubeLutContext.value.createCGImage(output, from: input.extent,
              format: .RGBA8, colorSpace: cubeLutPreviewOutputColorSpace) else { return nil }
    return UIImage(cgImage: cgImage)
}

let maximumCubeLutBytes = 16 * 1024 * 1024

private func parseCubeVector(_ tokens: [String], lineNumber: Int) throws -> [Float] {
    guard tokens.count == 4 else { throw CubeLutError.invalid("Invalid LUT domain at line \(lineNumber).") }
    return try tokens.dropFirst().map { try finiteFloat($0, lineNumber: lineNumber) }
}

private func finiteFloat(_ value: String, lineNumber: Int) throws -> Float {
    guard let number = Float(value), number.isFinite else {
        throw CubeLutError.invalid("Invalid finite number at line \(lineNumber).")
    }
    return number
}

private func lerp(_ start: Float, _ end: Float, _ amount: Float) -> Float { start + (end - start) * amount }

private extension String {
    var nilIfBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}

private final class SendableCIContext: @unchecked Sendable {
    let value = cubeLutPreviewContext()
}

private let cubeLutContext = SendableCIContext()
private let minimumCubeLutSize = 2
private let maximumCubeLutSize = 64
private let maximumCubeLutNameLength = 120
