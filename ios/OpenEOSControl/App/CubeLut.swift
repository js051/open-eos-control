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
    let previewEncoding: CubeLutPreviewEncoding?

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
        let data = rgba.withUnsafeBytes { Data($0) }
        rgbaData = data
        previewEncoding = CubeLutPreviewEncoding.make(values: values, originalData: data)
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

/// CIColorCube can clip extended-range table texels before it interpolates them.
/// Encode each channel affinely into its supported range, then undo that affine
/// transform after interpolation. Raw LUT values remain the CPU/wire authority.
struct CubeLutPreviewEncoding: Sendable {
    let rgbaData: Data
    let scale: [Float]
    let bias: [Float]

    static func make(values: [Float], originalData: Data) -> Self? {
        guard !values.isEmpty, values.count.isMultiple(of: 3), values.allSatisfy(\.isFinite) else { return nil }
        if values.allSatisfy({ (0...1).contains($0) }) {
            return Self(rgbaData: originalData, scale: [1, 1, 1], bias: [0, 0, 0])
        }
        var scale: [Float] = [1, 1, 1]
        var bias: [Float] = [0, 0, 0]
        var spans: [Double] = [1, 1, 1]
        var constant: [Float?] = [nil, nil, nil]
        for channel in 0..<3 {
            var minimum = values[channel]
            var maximum = minimum
            for index in stride(from: channel, to: values.count, by: 3) {
                minimum = min(minimum, values[index])
                maximum = max(maximum, values[index])
            }
            // A convex interpolation entirely on one side of the final clamp
            // is constant after that clamp, including finite Float extremes.
            if maximum <= 0 { constant[channel] = 0; continue }
            if minimum >= 1 { constant[channel] = 1; continue }
            let lower = min(minimum, 0)
            let upper = max(maximum, 1)
            let span = Double(upper) - Double(lower)
            let floatSpan = Float(span)
            // Never send an infinite inverse transform to the GPU. Finite
            // coefficients do not promise byte precision for arbitrary ranges.
            guard floatSpan.isFinite, floatSpan > 0 else { return nil }
            scale[channel] = floatSpan
            bias[channel] = lower
            spans[channel] = span
        }
        var rgba: [Float] = []
        rgba.reserveCapacity(values.count / 3 * 4)
        for index in stride(from: 0, to: values.count, by: 3) {
            for channel in 0..<3 {
                let encoded = constant[channel]
                    ?? Float((Double(values[index + channel]) - Double(bias[channel])) / spans[channel])
                guard encoded.isFinite, (0...1).contains(encoded) else { return nil }
                rgba.append(encoded)
            }
            // Table alpha remains one, so its RGB is also valid premultiplied data.
            rgba.append(1)
        }
        return Self(rgbaData: rgba.withUnsafeBytes { Data($0) }, scale: scale, bias: bias)
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

    // Swift treats CRLF as one Character, so splitting on the LF Character alone
    // misses Windows line endings. Preserve the byte decoder's text contract;
    // normalize only parser line boundaries, without admitting bare CR lines.
    let lines = text.replacingOccurrences(of: "\r\n", with: "\n")
    for (zeroBasedLine, sourceLine) in lines.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
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
// Extended range allows the post-interpolation affine restore before the final clamp.
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
          zip(lut.domainMin, lut.domainMax).allSatisfy({ $0.isFinite && $1.isFinite && $0 < $1 }),
          let encoding = lut.previewEncoding else { return nil }
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
    cube.setValue(encoding.rgbaData, forKey: "inputCubeData")
    cube.setValue(cubeLutWorkingColorSpace, forKey: "inputColorSpace")
    guard let interpolated = cube.outputImage else { return nil }
    var restored = interpolated
    if encoding.scale != [1, 1, 1] || encoding.bias != [0, 0, 0] {
        guard let restore = CIFilter(name: "CIColorMatrix") else { return nil }
        restore.setValue(interpolated, forKey: kCIInputImageKey)
        restore.setValue(CIVector(x: CGFloat(encoding.scale[0]), y: 0, z: 0, w: 0), forKey: "inputRVector")
        restore.setValue(CIVector(x: 0, y: CGFloat(encoding.scale[1]), z: 0, w: 0), forKey: "inputGVector")
        restore.setValue(CIVector(x: 0, y: 0, z: CGFloat(encoding.scale[2]), w: 0), forKey: "inputBVector")
        // CI color filters operate on unpremultiplied values. Restore RGB only;
        // do not add an alpha bias or multiply alpha by the RGB range.
        restore.setValue(CIVector(x: 0, y: 0, z: 0, w: 1), forKey: "inputAVector")
        restore.setValue(CIVector(x: CGFloat(encoding.bias[0]), y: CGFloat(encoding.bias[1]),
            z: CGFloat(encoding.bias[2]), w: 0), forKey: "inputBiasVector")
        guard let output = restore.outputImage else { return nil }
        restored = output
    }
    // Clamp only after interpolation and restoration. Early clipping changes nonlinear LUTs.
    outputClamp.setValue(restored, forKey: kCIInputImageKey)
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
