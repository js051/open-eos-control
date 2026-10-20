import Foundation
import XCTest
@testable import OpenEOSCore

final class PTPUSBContainerCodecTests: XCTestCase {
    private typealias Codec = PTPUSBContainerCodec
    private typealias Failure = PTPUSBContainerCodec.CodecError

    func testOpenSessionMatchesExistingAndroidGoldenPacket() throws {
        // Exact bytes from PtpProtocolTest.openSessionCommandMatchesUsbPtpReferencePacket.
        XCTAssertEqual(try Codec.encodeCommand(operationCode: 0x1002, transactionID: 0, parameters: [1]),
                       hex("10000000010002100000000001000000"))
    }

    func testZeroParameterGetDeviceInfoGoldenPacket() throws {
        XCTAssertEqual(try Codec.encodeCommand(operationCode: 0x1001, transactionID: 0),
                       hex("0c0000000100011000000000"))
    }

    func testFiveParameterGoldenPacketPreservesUnsignedFieldsAndByteOrder() throws {
        XCTAssertEqual(try Codec.encodeCommand(operationCode: 0xFFFF, transactionID: 0xFEDCBA98,
                                               parameters: [0, 1, 0x80000000, 0x12345678, .max]),
                       hex("200000000100ffff98badcfe00000000010000000000008078563412ffffffff"))
        XCTAssertEqual(try Codec.encodeCommand(operationCode: 0, transactionID: .max),
                       hex("0c00000001000000ffffffff"))
    }

    func testCommandRejectsSixParametersWithoutTruncation() {
        XCTAssertThrowsError(try Codec.encodeCommand(operationCode: 0x1001, transactionID: 0,
                                                     parameters: Array(repeating: 0, count: 6))) {
            XCTAssertEqual($0 as? Failure, .tooManyParameters(6))
        }
    }

    func testResponseGoldenPacketsPreserveCodesAndUnsignedParameters() throws {
        let ok = try Codec.decodeResponse(container: hex("0c0000000300012000000000"), expectedTransactionID: 0)
        XCTAssertEqual(ok.code, 0x2001)
        XCTAssertEqual(ok.transactionID, 0)
        XCTAssertEqual(ok.parameters, [])
        let response = try Codec.decodeResponse(
            container: hex("20000000030001a898badcfe00000000010000000000008078563412ffffffff"),
            expectedTransactionID: 0xFEDCBA98)
        XCTAssertEqual(response.code, 0xA801)
        XCTAssertEqual(response.transactionID, 0xFEDCBA98)
        XCTAssertEqual(response.parameters, [0, 1, 0x80000000, 0x12345678, .max])
    }

    func testErrorAndUnknownResponseCodesAreNotPromotedToOK() throws {
        for code: UInt16 in [0, 0x2002, 0x2019, 0xA801, 0xFFFF] {
            let response = try Codec.decodeResponse(container: packet(type: 3, code: code, transaction: .max),
                                                     expectedTransactionID: .max)
            XCTAssertEqual(response.code, code)
            XCTAssertNotEqual(response.code, 0x2001)
        }
    }

    func testEveryTruncatedHeaderFailsBothDecoders() {
        let complete = packet(type: 3)
        for size in 0..<12 {
            let input = Data(complete.prefix(size))
            assertResponseFailure(input, .truncatedHeader(size))
            assertDatasetFailure(input, .truncatedHeader(size))
        }
    }

    func testInvalidAndMismatchedDeclaredLengthsFailClosed() {
        for length: UInt32 in Array(0...11) {
            let bytes = packet(type: 3, declaredLength: length)
            assertResponseFailure(bytes, .invalidLength(length))
            assertDatasetFailure(bytes, .invalidLength(length))
        }
        for length: UInt32 in [13, 32, 0x80000000, .max] {
            let bytes = packet(type: 3, declaredLength: length)
            assertResponseFailure(bytes, .lengthMismatch(declared: length, actual: 12))
            assertDatasetFailure(bytes, .lengthMismatch(declared: length, actual: 12))
        }
        assertResponseFailure(packet(type: 3, payload: Data([0]), declaredLength: 12),
                              .lengthMismatch(declared: 12, actual: 13))
        assertDatasetFailure(packet(type: 2, payload: Data([0]), declaredLength: 12),
                             .lengthMismatch(declared: 12, actual: 13))
    }

    func testConcatenatedContainersAreNotSilentlyAccepted() {
        let response = packet(type: 3)
        assertResponseFailure(response + response, .lengthMismatch(declared: 12, actual: 24))
        let data = packet(type: 2)
        assertDatasetFailure(data + data, .lengthMismatch(declared: 12, actual: 24))
    }

    func testUnexpectedAndUnknownTypesFailClosed() {
        for type: UInt16 in [0, 1, 2, 4, .max] {
            assertResponseFailure(packet(type: type), .unexpectedType(expected: 3, actual: type))
        }
        for type: UInt16 in [0, 1, 3, 4, .max] {
            assertDatasetFailure(packet(type: type), .unexpectedType(expected: 2, actual: type))
        }
    }

    func testResponseAllowsOnlyZeroThroughFiveAlignedParameters() throws {
        for size in 0...24 {
            let bytes = packet(type: 3, payload: Data(repeating: 0, count: size))
            if size > 20 {
                assertResponseFailure(bytes, .inputTooLarge(actual: 12 + size, maximum: 32))
            } else if size % 4 != 0 {
                assertResponseFailure(bytes, .invalidParameterPayload(size))
            } else {
                let response = try Codec.decodeResponse(container: bytes, expectedTransactionID: 0)
                XCTAssertEqual(response.parameters, Array(repeating: 0, count: size / 4))
            }
        }
    }

    func testTransactionComparisonIncludesEveryUnsignedBit() {
        for transaction: UInt32 in [1, 0x00000100, 0x00010000, 0x01000000, 0x80000000, .max] {
            assertResponseFailure(packet(type: 3, transaction: transaction),
                                  .transactionMismatch(expected: 0, actual: transaction))
            assertDatasetFailure(packet(type: 2, transaction: transaction),
                                 .transactionMismatch(expected: 0, actual: transaction))
        }
    }

    func testDatasetRequiresExactOperationCodeIncludingHighByte() {
        for code: UInt16 in [0x1000, 0x0001, 0x9001, .max] {
            assertDatasetFailure(packet(type: 2, code: code), .operationMismatch(expected: 0x1001, actual: code))
        }
    }

    func testDataGoldenPacketUnwrapsOnlyPayload() throws {
        let input = hex("110000000200019098badcfe01020304ff")
        XCTAssertEqual(try Codec.decodeDataset(container: input, expectedOperationCode: 0x9001,
                                               expectedTransactionID: 0xFEDCBA98), hex("01020304ff"))
        XCTAssertEqual(try Codec.decodeDataset(container: packet(type: 2), expectedOperationCode: 0x1001,
                                               expectedTransactionID: 0), Data())
    }

    func testDatasetAndContainerAreSeparateParsingStages() throws {
        // Minimal DeviceInfo: fixed fields, empty vendor description, five empty arrays.
        let dataset = hex("64000b00000064000000000000000000000000000000000000000000000000")
        let container = packet(type: 2, payload: dataset)
        let extracted = try Codec.decodeDataset(container: container, expectedOperationCode: 0x1001,
                                                 expectedTransactionID: 0)
        XCTAssertEqual(extracted, dataset)
        let info = try PTPDeviceInfoDecoder.decode(dataset: extracted)
        XCTAssertEqual(info.standardVersion, 100)
        XCTAssertEqual(info.vendorExtensionID, 11)
        XCTAssertEqual(info.operations, [])
        // A bare dataset must not be heuristically accepted as a container.
        XCTAssertThrowsError(try Codec.decodeDataset(container: dataset, expectedOperationCode: 0x1001,
                                                      expectedTransactionID: 0))
        // Framing does not validate dataset content or imply a successful operation.
        let malformedDataset = try Codec.decodeDataset(container: packet(type: 2, payload: Data([0])),
                                                         expectedOperationCode: 0x1001, expectedTransactionID: 0)
        XCTAssertThrowsError(try PTPDeviceInfoDecoder.decode(dataset: malformedDataset))
    }

    func testNonzeroIndexDataSlicesAreNormalizedWithoutUnalignedLoads() throws {
        var response = Data([0xAA]); response.append(hex("1000000003000120ffffffff78563412"))
        response.removeFirst()
        XCTAssertNotEqual(response.startIndex, 0)
        let parsed = try Codec.decodeResponse(container: response, expectedTransactionID: .max)
        XCTAssertEqual(parsed.code, 0x2001)
        XCTAssertEqual(parsed.parameters, [0x12345678])
        var data = Data([0xAA]); data.append(packet(type: 2, payload: Data([1, 2, 3])))
        data.removeFirst()
        XCTAssertNotEqual(data.startIndex, 0)
        XCTAssertEqual(try Codec.decodeDataset(container: data, expectedOperationCode: 0x1001,
                                               expectedTransactionID: 0), Data([1, 2, 3]))
    }

    func testDatasetBudgetAcceptsLimitAndRejectsOneByteOverBeforeCopy() throws {
        let payload = Data(repeating: 0, count: Codec.maximumDatasetBytes)
        let limit = packet(type: 2, payload: payload)
        XCTAssertEqual(try Codec.decodeDataset(container: limit, expectedOperationCode: 0x1001,
                                               expectedTransactionID: 0).count, Codec.maximumDatasetBytes)
        var oversized = limit; oversized.append(0)
        assertDatasetFailure(oversized, .inputTooLarge(actual: Codec.maximumDatasetBytes + 13,
                                                       maximum: Codec.maximumDatasetBytes + 12))
        assertResponseFailure(oversized, .inputTooLarge(actual: Codec.maximumDatasetBytes + 13, maximum: 32))
    }

    private func assertResponseFailure(_ data: Data, _ expected: Failure,
                                       file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try Codec.decodeResponse(container: data, expectedTransactionID: 0), file: file, line: line) {
            XCTAssertEqual($0 as? Failure, expected, file: file, line: line)
        }
    }
    private func assertDatasetFailure(_ data: Data, _ expected: Failure,
                                      file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try Codec.decodeDataset(container: data, expectedOperationCode: 0x1001,
                                                      expectedTransactionID: 0), file: file, line: line) {
            XCTAssertEqual($0 as? Failure, expected, file: file, line: line)
        }
    }
    private func hex(_ text: String) -> Data {
        let characters = Array(text)
        precondition(characters.count % 2 == 0)
        return Data(stride(from: 0, to: characters.count, by: 2).map {
            UInt8(String(characters[$0...$0 + 1]), radix: 16)!
        })
    }
    // Test-only container builder; literal golden fixtures above independently fix byte order.
    private func packet(type: UInt16, code: UInt16 = 0x1001, transaction: UInt32 = 0,
                        payload: Data = Data(), declaredLength: UInt32? = nil) -> Data {
        var bytes = [UInt8]()
        func u16(_ value: UInt16) { bytes += [UInt8(truncatingIfNeeded: value), UInt8(value >> 8)] }
        func u32(_ value: UInt32) {
            for shift in stride(from: 0, through: 24, by: 8) { bytes.append(UInt8(truncatingIfNeeded: value >> shift)) }
        }
        u32(declaredLength ?? UInt32(12 + payload.count)); u16(type); u16(code); u32(transaction)
        return Data(bytes) + payload
    }
}
