import Foundation

/// Standard USB/PTP container framing only, following Android PtpCodec.
/// This is NOT the ImageCaptureCore command/completion buffer contract. In particular,
/// ImageCapturePTP.Request is not a UInt32 wire transaction ID. No native adapter calls
/// this codec; unsupportedWireContract must remain until Apple's contract is verified.
public enum PTPUSBContainerCodec {
    public static let headerBytes = 12
    public static let maximumParameters = 5
    public static let maximumDatasetBytes = PTPDeviceInfoDecoder.maximumDatasetBytes

    public enum CodecError: Error, Equatable, Sendable {
        case tooManyParameters(Int)
        case inputTooLarge(actual: Int, maximum: Int)
        case truncatedHeader(Int)
        case invalidLength(UInt32)
        case lengthMismatch(declared: UInt32, actual: Int)
        case unexpectedType(expected: UInt16, actual: UInt16)
        case transactionMismatch(expected: UInt32, actual: UInt32)
        case operationMismatch(expected: UInt16, actual: UInt16)
        case invalidParameterPayload(Int)
    }

    /// Framing validity is not operation success. Callers must interpret `code`, including
    /// non-OK/vendor responses, and establish session ownership separately.
    public struct Response: Equatable, Sendable {
        public let code: UInt16
        public let transactionID: UInt32
        public let parameters: [UInt32]
    }

    /// UInt16/UInt32 inputs prevent silent truncation of operation, transaction or params.
    /// Zero and UInt32.max are preserved; session allocation policy belongs elsewhere.
    public static func encodeCommand(operationCode: UInt16, transactionID: UInt32,
                                     parameters: [UInt32] = []) throws -> Data {
        guard parameters.count <= maximumParameters else {
            throw CodecError.tooManyParameters(parameters.count)
        }
        var bytes = [UInt8]()
        bytes.reserveCapacity(headerBytes + parameters.count * 4)
        append32(UInt32(headerBytes + parameters.count * 4), to: &bytes)
        append16(1, to: &bytes)
        append16(operationCode, to: &bytes)
        append32(transactionID, to: &bytes)
        for parameter in parameters { append32(parameter, to: &bytes) }
        return Data(bytes)
    }

    /// Requires exactly one complete response container, never a stream or dataset.
    /// All UInt16 response codes are preserved, matching Android's framing codec;
    /// an unknown code is not converted into OK or treated as a supported operation.
    public static func decodeResponse(container: Data, expectedTransactionID: UInt32) throws -> Response {
        let header = try readHeader(container, maximumBytes: headerBytes + maximumParameters * 4,
                                    expectedType: 3, expectedTransactionID: expectedTransactionID)
        let payloadBytes = container.count - headerBytes
        guard payloadBytes % 4 == 0 else { throw CodecError.invalidParameterPayload(payloadBytes) }
        let bytes = Array(container.dropFirst(headerBytes))
        let parameters = stride(from: 0, to: bytes.count, by: 4).map { read32(bytes, at: $0) }
        return Response(code: header.code, transactionID: header.transactionID, parameters: parameters)
    }

    /// Explicit container → dataset boundary, not a DeviceInfo read or success receipt.
    /// Only a complete, bounded data container matching BOTH operation and transaction
    /// is unwrapped. A transport must still establish ownership and receive/validate the
    /// separate response before accepting this dataset as an operation's successful result.
    /// No Apple completion buffer may be passed here without a verified native contract.
    public static func decodeDataset(container: Data, expectedOperationCode: UInt16,
                                     expectedTransactionID: UInt32) throws -> Data {
        let header = try readHeader(container, maximumBytes: headerBytes + maximumDatasetBytes,
                                    expectedType: 2, expectedTransactionID: expectedTransactionID)
        guard header.code == expectedOperationCode else {
            throw CodecError.operationMismatch(expected: expectedOperationCode, actual: header.code)
        }
        return Data(container.dropFirst(headerBytes))
    }

    private struct Header {
        let code: UInt16
        let transactionID: UInt32
    }

    private static func readHeader(_ data: Data, maximumBytes: Int, expectedType: UInt16,
                                   expectedTransactionID: UInt32) throws -> Header {
        // Bound before any payload copy or allocation. Normalize only the fixed header,
        // so Data slices with nonzero startIndex are safe and loads need no alignment.
        guard data.count <= maximumBytes else {
            throw CodecError.inputTooLarge(actual: data.count, maximum: maximumBytes)
        }
        guard data.count >= headerBytes else { throw CodecError.truncatedHeader(data.count) }
        let bytes = Array(data.prefix(headerBytes))
        let length = read32(bytes, at: 0)
        guard length >= UInt32(headerBytes) else { throw CodecError.invalidLength(length) }
        guard UInt64(length) == UInt64(data.count) else {
            throw CodecError.lengthMismatch(declared: length, actual: data.count)
        }
        let type = read16(bytes, at: 4)
        guard type == expectedType else { throw CodecError.unexpectedType(expected: expectedType, actual: type) }
        let transaction = read32(bytes, at: 8)
        guard transaction == expectedTransactionID else {
            throw CodecError.transactionMismatch(expected: expectedTransactionID, actual: transaction)
        }
        return Header(code: read16(bytes, at: 6), transactionID: transaction)
    }

    private static func read16(_ bytes: [UInt8], at offset: Int) -> UInt16 {
        UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)
    }
    private static func read32(_ bytes: [UInt8], at offset: Int) -> UInt32 {
        UInt32(bytes[offset]) | (UInt32(bytes[offset + 1]) << 8)
            | (UInt32(bytes[offset + 2]) << 16) | (UInt32(bytes[offset + 3]) << 24)
    }
    private static func append16(_ value: UInt16, to bytes: inout [UInt8]) {
        bytes.append(UInt8(truncatingIfNeeded: value)); bytes.append(UInt8(value >> 8))
    }
    private static func append32(_ value: UInt32, to bytes: inout [UInt8]) {
        for shift in stride(from: 0, through: 24, by: 8) { bytes.append(UInt8(truncatingIfNeeded: value >> shift)) }
    }
}
