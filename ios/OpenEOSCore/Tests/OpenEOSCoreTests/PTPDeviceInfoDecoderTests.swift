import Foundation
import XCTest
@testable import OpenEOSCore

final class PTPDeviceInfoDecoderTests: XCTestCase {
    func testAndroidDeviceInfoPayloadHasIdenticalFields() throws {
        // Same values and byte construction as PtpProtocolTest.deviceInfoPayload():535–561.
        let info = try PTPDeviceInfoDecoder.decode(dataset: fixture())
        XCTAssertEqual(info.standardVersion, 100)
        XCTAssertEqual(info.vendorExtensionID, 0x0000000B)
        XCTAssertEqual(info.vendorExtensionVersion, 100)
        XCTAssertEqual(info.vendorExtensionDescription, "Canon extension")
        XCTAssertEqual(info.functionalMode, 0)
        XCTAssertEqual(info.operations, [0x1001, 0x1002, 0x1003, 0x1004, 0x1005, 0x1007, 0x1008, 0x1009, 0x100E])
        XCTAssertEqual(info.events, [])
        XCTAssertEqual(info.deviceProperties, [0x5001])
        XCTAssertEqual(info.captureFormats, [0x3801])
        XCTAssertEqual(info.imageFormats, [0x3801, 0x3811])
        XCTAssertEqual(info.manufacturer, "Canon")
        XCTAssertEqual(info.model, "Canon EOS R6 Mark III")
        XCTAssertEqual(info.deviceVersion, "1.0.0")
        XCTAssertEqual(info.serialNumber, "TEST-SERIAL-0001")
        XCTAssertTrue(info.supports(operationCode: 0x1004))
        XCTAssertTrue(info.supports(operationCode: 0x1009))
        XCTAssertFalse(info.supports(operationCode: 0x1016))
    }

    func testNonzeroStartIndexSliceMatchesStandaloneDataset() throws {
        let bytes = fixture()
        let storage = Data([0xFF, 0xEE]) + bytes + Data([0xCC])
        let slice = storage[2..<(2 + bytes.count)]
        XCTAssertEqual(slice.startIndex, 2)
        XCTAssertEqual(try PTPDeviceInfoDecoder.decode(dataset: slice), try PTPDeviceInfoDecoder.decode(dataset: bytes))
    }

    func testEveryTruncatedPrefixExceptWholeOptionalFieldsIsRejected() throws {
        var writer = mandatory()
        var allowed = Set([writer.bytes.count])
        for string in ["Canon", "Canon EOS R6 Mark III", "1.0.0", "TEST-SERIAL-0001"] {
            writer.string(string)
            allowed.insert(writer.bytes.count)
        }
        for count in 0..<writer.bytes.count {
            let input = Data(writer.bytes.prefix(count))
            if allowed.contains(count) { XCTAssertNoThrow(try PTPDeviceInfoDecoder.decode(dataset: input), "prefix \(count)") }
            else { XCTAssertThrowsError(try PTPDeviceInfoDecoder.decode(dataset: input), "prefix \(count)") }
        }
    }

    func testOmittedOptionalTailFieldsBecomeEmptyStrings() throws {
        var writer = mandatory()
        for present in 0...4 {
            let info = try PTPDeviceInfoDecoder.decode(dataset: Data(writer.bytes))
            let strings = [info.manufacturer, info.model, info.deviceVersion, info.serialNumber]
            XCTAssertEqual(strings, (0..<4).map { $0 < present ? "field\($0)" : "" })
            if present < 4 { writer.string("field\(present)") }
        }
    }

    func testTrailingBytesAreIgnoredForAndroidCompatibility() throws {
        let bytes = fixture()
        XCTAssertEqual(try PTPDeviceInfoDecoder.decode(dataset: bytes + Data([0xFF, 0xA0, 0x01])),
                       try PTPDeviceInfoDecoder.decode(dataset: bytes))
    }

    func testUnicodeAndSurrogatePairDecodeWithoutNormalization() throws {
        var writer = mandatory(description: "擴充\u{1F4F7}e\u{0301}")
        writer.string("相機\u{1F600}")
        let info = try PTPDeviceInfoDecoder.decode(dataset: Data(writer.bytes))
        XCTAssertEqual(Array(info.vendorExtensionDescription.unicodeScalars).map(\.value),
                       Array("擴充\u{1F4F7}e\u{0301}".unicodeScalars).map(\.value))
        XCTAssertEqual(info.manufacturer, "相機\u{1F600}")
    }

    func testMalformedUTF16UsesJavaReplacementConsumption() throws {
        // Checked against the existing JVM UTF_16LE decoder; high+nonlow consumes both.
        let cases: [([UInt16], [UInt32])] = [
            ([0xD800], [0xFFFD]), ([0xDC00], [0xFFFD]),
            ([0xD800, 0x0041], [0xFFFD]), ([0xD800, 0xD800], [0xFFFD]),
            ([0xD800, 0xD800, 0xDC00], [0xFFFD, 0xFFFD]),
            ([0xDC00, 0x0041], [0xFFFD, 0x0041]),
            ([0xD800, 0xDC00], [0x10000]), ([0x0041, 0xD800, 0x0042], [0x0041, 0xFFFD])
        ]
        for (units, expected) in cases {
            var writer = mandatory()
            writer.counted(units, last: 0)
            let info = try PTPDeviceInfoDecoder.decode(dataset: Data(writer.bytes))
            XCTAssertEqual(info.manufacturer.unicodeScalars.map(\.value), expected, "\(units)")
        }
    }

    func testCountedTerminatorIsDiscardedAndTrailingNULsTrimmed() throws {
        var writer = mandatory()
        writer.counted([0x41, 0, 0x42, 0, 0], last: 0x1234)
        writer.counted([], last: 0x1234)
        let info = try PTPDeviceInfoDecoder.decode(dataset: Data(writer.bytes))
        XCTAssertEqual(info.manufacturer.unicodeScalars.map(\.value), [0x41, 0, 0x42])
        XCTAssertEqual(info.model, "")
    }

    func testUnsignedFieldsAndDuplicateUnknownCodesArePreservedAsSets() throws {
        var writer = Writer()
        writer.u16(0xFFFF); writer.u32(0xFFFFFFFF); writer.u16(0xFFFF); writer.string(""); writer.u16(0xFFFF)
        writer.array([0xFFFF, 0xFFFF, 0x9001]); writer.array([0xFFFF]); writer.array([]); writer.array([]); writer.array([])
        let info = try PTPDeviceInfoDecoder.decode(dataset: Data(writer.bytes))
        XCTAssertEqual(info.standardVersion, 0xFFFF)
        XCTAssertEqual(info.vendorExtensionID, 0xFFFFFFFF)
        XCTAssertEqual(info.vendorExtensionVersion, 0xFFFF)
        XCTAssertEqual(info.functionalMode, 0xFFFF)
        XCTAssertEqual(info.operations, [0xFFFF, 0x9001])
        XCTAssertEqual(info.events, [0xFFFF])
    }

    func testHugeArrayCountsFailBeforeAllocation() {
        for count: UInt32 in [0xFFFFFFFF, 0x80000000, 0x7FFFFFFF, 1] {
            var writer = Writer()
            writer.u16(100); writer.u32(11); writer.u16(100); writer.string(""); writer.u16(0)
            let offset = writer.bytes.count
            writer.u32(count)
            XCTAssertThrowsError(try PTPDeviceInfoDecoder.decode(dataset: Data(writer.bytes))) {
                XCTAssertEqual($0 as? PTPDeviceInfoDecoder.DecodeError, .arrayCountOutOfBounds(offset: offset, count: count))
            }
        }
    }

    func testMaximumCountedStringAndZeroCountStrings() throws {
        var writer = mandatory(description: "")
        writer.string(String(repeating: "界", count: 254)); writer.string("")
        let info = try PTPDeviceInfoDecoder.decode(dataset: Data(writer.bytes))
        XCTAssertEqual(info.vendorExtensionDescription, "")
        XCTAssertEqual(info.manufacturer, String(repeating: "界", count: 254))
        XCTAssertEqual(info.model, "")
    }

    func testInputBudgetAllowsLimitAndRejectsOneByteOver() throws {
        var bytes = fixture()
        bytes.append(Data(repeating: 0, count: PTPDeviceInfoDecoder.maximumDatasetBytes - bytes.count))
        XCTAssertNoThrow(try PTPDeviceInfoDecoder.decode(dataset: bytes))
        bytes.append(0)
        XCTAssertThrowsError(try PTPDeviceInfoDecoder.decode(dataset: bytes)) {
            XCTAssertEqual($0 as? PTPDeviceInfoDecoder.DecodeError, .inputTooLarge(16 * 1024 * 1024 + 1))
        }
    }

    func testExactBytesMatchAllFieldsFromAndroidProductionOracle() throws {
        // Frozen 13-case oracle from complete Android PtpDatasets production classes.
        // PtpProtocol.kt SHA256: 6dfe645f02a58ff4e0e3e6d3be27eec3180f537bcccdb2c0e2908b183f9b028c
        // Strings compare UTF-16 hex, not Swift canonical-equivalent String equality.
        let cases: [(String, String, [String]?)] = [
            ("baseline", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e1000000000010000000150010000000138020000000138113806430061006e006f006e00000016430061006e006f006e00200045004f00530020005200360020004d00610072006b00200049004900490000000631002e0030002e00300000001154004500530054002d00530045005200490041004c002d0030003000300031000000", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "00430061006e006f006e", "00430061006e006f006e00200045004f00530020005200360020004d00610072006b0020004900490049", "0031002e0030002e0030", "0054004500530054002d00530045005200490041004c002d0030003000300031"]),
            ("omitted_tail", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e10000000000100000001500100000001380200000001381138", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "", "", "", ""]),
            ("trailing_bytes", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e1000000000010000000150010000000138020000000138113806430061006e006f006e00000016430061006e006f006e00200045004f00530020005200360020004d00610072006b00200049004900490000000631002e0030002e00300000001154004500530054002d00530045005200490041004c002d0030003000300031000000aabb", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "00430061006e006f006e", "00430061006e006f006e00200045004f00530020005200360020004d00610072006b0020004900490049", "0031002e0030002e0030", "0054004500530054002d00530045005200490041004c002d0030003000300031"]),
            ("partial_tail", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e100000000001000000015001000000013802000000013811380241", nil),
            ("nonzero_terminator", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e100000000001000000015001000000013802000000013811380241004200", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "0041", "", "", ""]),
            ("empty_tail_fields", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e1000000000010000000150010000000138020000000138113800000000", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "", "", "", ""]),
            ("high_nonlow", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e100000000001000000015001000000013802000000013811380300d841000000", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "fffd", "", "", ""]),
            ("high_high_low", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e100000000001000000015001000000013802000000013811380400d800d800dc0000", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "fffdfffd", "", "", ""]),
            ("lone_high", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e100000000001000000015001000000013802000000013811380200d80000", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "fffd", "", "", ""]),
            ("lone_low", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e100000000001000000015001000000013802000000013811380200dc0000", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "fffd", "", "", ""]),
            ("pair", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e10000000000100000001500100000001380200000001381138033dd800de0000", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "d83dde00", "", "", ""]),
            ("nul_trim", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e10000000000100000001500100000001380200000001381138044100000000000000", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "0041", "", "", ""]),
            ("embedded_nul", "64000b000000640010430061006e006f006e00200065007800740065006e00730069006f006e000000000009000000011002100310041005100710081009100e10000000000100000001500100000001380200000001381138044100000042000000", ["100", "11", "100", "00430061006e006f006e00200065007800740065006e00730069006f006e", "0", "[4097, 4098, 4099, 4100, 4101, 4103, 4104, 4105, 4110]", "[]", "[20481]", "[14337]", "[14337, 14353]", "004100000042", "", "", ""]),
        ]
        XCTAssertEqual(cases.count, 13)
        for (name, hex, expected) in cases {
            let input = try data(hex: hex)
            if name == "baseline" { XCTAssertEqual(input, fixture()) }
            guard let expected else {
                XCTAssertThrowsError(try PTPDeviceInfoDecoder.decode(dataset: input), name)
                continue
            }
            XCTAssertEqual(expected.count, 14)
            let info = try PTPDeviceInfoDecoder.decode(dataset: input)
            func utf16(_ value: String) -> String { value.utf16.map { String(format: "%04x", Int($0)) }.joined() }
            func codes(_ values: Set<UInt16>) -> String { "[" + values.sorted().map(String.init).joined(separator: ", ") + "]" }
            let actual = [String(info.standardVersion), String(info.vendorExtensionID), String(info.vendorExtensionVersion),
                          utf16(info.vendorExtensionDescription), String(info.functionalMode), codes(info.operations),
                          codes(info.events), codes(info.deviceProperties), codes(info.captureFormats), codes(info.imageFormats),
                          utf16(info.manufacturer), utf16(info.model), utf16(info.deviceVersion), utf16(info.serialNumber)]
            XCTAssertEqual(actual, expected, name)
        }
    }

    func testBOMAndDecomposedCombiningSequenceRemainExactUTF16() throws {
        let units: [UInt16] = [0xFEFF, 0x0065, 0x0301, 0xD83D, 0xDE00]
        var writer = mandatory()
        writer.counted(units, last: 0)
        let info = try PTPDeviceInfoDecoder.decode(dataset: Data(writer.bytes))
        XCTAssertEqual(Array(info.manufacturer.utf16), units)
        XCTAssertEqual(info.manufacturer.unicodeScalars.map(\.value), [0xFEFF, 0x0065, 0x0301, 0x1F600])
    }

    private func data(hex: String) throws -> Data {
        XCTAssertEqual(hex.count % 2, 0)
        let chars = Array(hex)
        return try Data(stride(from: 0, to: chars.count, by: 2).map { index in
            try XCTUnwrap(UInt8(String(chars[index...index + 1]), radix: 16))
        })
    }

    private func fixture() -> Data {
        var writer = mandatory()
        for string in ["Canon", "Canon EOS R6 Mark III", "1.0.0", "TEST-SERIAL-0001"] { writer.string(string) }
        return Data(writer.bytes)
    }
    private func mandatory(description: String = "Canon extension") -> Writer {
        var w = Writer()
        w.u16(100); w.u32(11); w.u16(100); w.string(description); w.u16(0)
        w.array([0x1001, 0x1002, 0x1003, 0x1004, 0x1005, 0x1007, 0x1008, 0x1009, 0x100E])
        w.array([]); w.array([0x5001]); w.array([0x3801]); w.array([0x3801, 0x3811])
        return w
    }
    // Dataset fixture writer only. No command/container encoder or native calls.
    private struct Writer {
        var bytes: [UInt8] = []
        mutating func u16(_ value: UInt16) { bytes += [UInt8(truncatingIfNeeded: value), UInt8(value >> 8)] }
        mutating func u32(_ value: UInt32) { for shift in stride(from: 0, through: 24, by: 8) { bytes.append(UInt8(truncatingIfNeeded: value >> shift)) } }
        mutating func array(_ values: [UInt16]) { u32(UInt32(values.count)); for value in values { u16(value) } }
        mutating func string(_ value: String) {
            if value.isEmpty { bytes.append(0) } else { counted(Array(value.utf16), last: 0) }
        }
        mutating func counted(_ units: [UInt16], last: UInt16) {
            precondition(units.count < 255)
            bytes.append(UInt8(units.count + 1))
            for unit in units { u16(unit) }; u16(last)
        }
    }
}
