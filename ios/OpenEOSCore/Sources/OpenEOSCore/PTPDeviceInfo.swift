import Foundation

/// Decoded dataset fields, not a transport receipt or proof of a successful camera read.
public struct PTPDeviceInfo: Equatable, Sendable {
    public let standardVersion: UInt16
    public let vendorExtensionID: UInt32
    public let vendorExtensionVersion: UInt16
    public let vendorExtensionDescription: String
    public let functionalMode: UInt16
    public let operations: Set<UInt16>
    public let events: Set<UInt16>
    public let deviceProperties: Set<UInt16>
    public let captureFormats: Set<UInt16>
    public let imageFormats: Set<UInt16>
    public let manufacturer: String
    public let model: String
    public let deviceVersion: String
    public let serialNumber: String

    public func supports(operationCode: UInt16) -> Bool { operations.contains(operationCode) }
}

/// Compatibility decoder for the existing Android PtpDatasets.deviceInfo contract.
/// Input must already be a separated dataset. Never pass an unvalidated Apple completion
/// buffer here: framing, response status and transaction ownership are outside this API.
/// This intentionally accepts omitted trailing string fields and ignores trailing bytes;
/// a counted string discards its last code unit without validating NUL, then trims NULs.
/// These compatibility choices are not strict PTP specification validation.
public enum PTPDeviceInfoDecoder {
    /// Matches the Android transport metadata budget. Android's dataset function itself
    /// does not impose this limit; this decoder adds it before copying/allocating input.
    public static let maximumDatasetBytes = 16 * 1024 * 1024

    public enum DecodeError: Error, Equatable, Sendable {
        case inputTooLarge(Int)
        case truncated(offset: Int, required: Int, remaining: Int)
        case arrayCountOutOfBounds(offset: Int, count: UInt32)
    }

    public static func decode(dataset: Data) throws -> PTPDeviceInfo {
        guard dataset.count <= maximumDatasetBytes else { throw DecodeError.inputTooLarge(dataset.count) }
        var reader = Reader(bytes: Array(dataset)) // Normalizes Data slices with nonzero startIndex.
        return try PTPDeviceInfo(
            standardVersion: reader.u16(), vendorExtensionID: reader.u32(),
            vendorExtensionVersion: reader.u16(), vendorExtensionDescription: reader.string(),
            functionalMode: reader.u16(), operations: reader.codes(), events: reader.codes(),
            deviceProperties: reader.codes(), captureFormats: reader.codes(), imageFormats: reader.codes(),
            manufacturer: reader.optionalString(), model: reader.optionalString(),
            deviceVersion: reader.optionalString(), serialNumber: reader.optionalString())
    }

    private struct Reader {
        let bytes: [UInt8]
        var offset = 0
        var remaining: Int { bytes.count - offset }
        func require(_ count: Int) throws {
            guard count <= remaining else {
                throw DecodeError.truncated(offset: offset, required: count, remaining: remaining)
            }
        }
        mutating func u16() throws -> UInt16 {
            try require(2)
            defer { offset += 2 }
            return UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)
        }
        mutating func u32() throws -> UInt32 {
            try require(4)
            defer { offset += 4 }
            return UInt32(bytes[offset]) | (UInt32(bytes[offset + 1]) << 8)
                | (UInt32(bytes[offset + 2]) << 16) | (UInt32(bytes[offset + 3]) << 24)
        }
        mutating func codes() throws -> Set<UInt16> {
            let countOffset = offset
            let count = try u32()
            // Validate before multiplication, conversion or allocation. Keep Android's
            // signed-Int count limit even though Swift's Int may be wider.
            guard count <= UInt32(Int32.max), UInt64(count) <= UInt64(remaining / 2) else {
                throw DecodeError.arrayCountOutOfBounds(offset: countOffset, count: count)
            }
            var values = Set<UInt16>()
            values.reserveCapacity(min(Int(count), 65536))
            for _ in 0..<Int(count) { values.insert(try u16()) }
            return values
        }
        mutating func optionalString() throws -> String { remaining == 0 ? "" : try string() }
        mutating func string() throws -> String {
            try require(1)
            let count = Int(bytes[offset]); offset += 1
            if count == 0 { return "" }
            try require(count * 2)
            var units: [UInt16] = []
            units.reserveCapacity(count - 1)
            for _ in 0..<(count - 1) { units.append(try u16()) }
            offset += 2 // Android discards the final counted code unit, even when nonzero.
            var scalars = String.UnicodeScalarView()
            var index = 0
            while index < units.count {
                let unit = units[index]; index += 1
                if (0xD800...0xDBFF).contains(unit) {
                    // Java UTF_16LE replacement semantics consume both code units when
                    // a high surrogate is followed by a non-low surrogate. Swift's
                    // default repairing decoder differs, so implement that rule explicitly.
                    guard index < units.count else { scalars.append("\u{FFFD}"); continue }
                    let low = units[index]; index += 1
                    if (0xDC00...0xDFFF).contains(low) {
                        let value = 0x10000 + ((UInt32(unit) - 0xD800) << 10) + UInt32(low) - 0xDC00
                        scalars.append(Unicode.Scalar(value)!)
                    } else { scalars.append("\u{FFFD}") }
                } else if (0xDC00...0xDFFF).contains(unit) {
                    scalars.append("\u{FFFD}")
                } else { scalars.append(Unicode.Scalar(UInt32(unit))!) }
            }
            while scalars.last == "\u{0000}" { scalars.removeLast() }
            return String(scalars)
        }
    }
}
