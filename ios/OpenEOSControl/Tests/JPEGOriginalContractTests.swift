import CryptoKit
import Foundation
import XCTest
@testable import OpenEOSCore

/// Native ImageIO assertions. Merely discovering these source tests, validating
/// fixture hashes, or running Python guards is NOT evidence that they passed.
enum JPEGOriginalTestVectors {
    struct MIME: Decodable { let id: String; let raw: String?; let valid: Bool }
    struct Header: Decodable { let id: String; let values: [String]; let valid: Bool }
    struct Bytes: Decodable {
        let id: String; let base64: String; let sha256: String; let byteCount: Int
        let structuralValid: Bool; let decodedValid: Bool; let width: Int?; let height: Int?
        func data() throws -> Data {
            let data = try XCTUnwrap(Data(base64Encoded: base64), id)
            XCTAssertEqual(data.count, byteCount, id)
            XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(), sha256, id)
            return data
        }
    }
    struct Contract: Decodable {
        let schemaVersion: Int
        let policy: [String: PolicyValue]
        let mimeVectors: [MIME]; let headerVectors: [Header]; let byteVectors: [Bytes]
    }
    enum PolicyValue: Decodable {
        case number(Int), numbers([Int])
        init(from decoder: Decoder) throws {
            let value = try decoder.singleValueContainer()
            if let integer = try? value.decode(Int.self) { self = .number(integer) }
            else { self = .numbers(try value.decode([Int].self)) }
        }
        var number: Int? { if case .number(let value) = self { return value }; return nil }
    }
    static func contract() throws -> Contract {
        let url = try XCTUnwrap(Bundle(for: JPEGOriginalContractTests.self)
            .url(forResource: "jpeg-original-vectors", withExtension: "json"))
        let size = try XCTUnwrap(url.resourceValues(forKeys: [.fileSizeKey]).fileSize)
        XCTAssertEqual(size, 310_772)
        guard size <= 512 * 1024 else { throw URLError(.dataLengthExceedsMaximum) }
        let data = try Data(contentsOf: url)
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(),
                       "639e85317c0d5fbb72f4f5f8ebccfa696593c7c41881f43900021ccd30f1f6d1")
        return try JSONDecoder().decode(Contract.self, from: data)
    }
    static func bytes(_ id: String) throws -> Data {
        try XCTUnwrap(try contract().byteVectors.first { $0.id == id }).data()
    }
}

final class JPEGOriginalContractTests: XCTestCase {
    func testFrozenEnvelopeAndCorpusIdentity() throws {
        let contract = try JPEGOriginalTestVectors.contract()
        XCTAssertEqual(contract.schemaVersion, 1)
        XCTAssertEqual(contract.byteVectors.count, 71)
        XCTAssertEqual(contract.mimeVectors.count, 34)
        XCTAssertEqual(contract.headerVectors.count, 6)
        let policy = contract.policy
        XCTAssertEqual(policy["maxEncodedBytes"]?.number, CameraOriginalJPEG.maximumBytes)
        XCTAssertEqual(policy["maxMimeBytes"]?.number, CameraOriginalJPEG.maximumMIMEBytes)
        XCTAssertEqual(policy["maxPixels"]?.number, Int(CameraOriginalJPEG.maximumPixels))
        XCTAssertEqual(policy["maxDimension"]?.number, CameraOriginalJPEG.maximumSide)
        XCTAssertEqual(policy["maxMarkers"]?.number, CameraOriginalJPEG.maximumMarkers)
        XCTAssertEqual(policy["maxScans"]?.number, CameraOriginalJPEG.maximumScans)
        XCTAssertEqual(policy["decodeTargetLongest"]?.number, CameraOriginalJPEG.decodeLongestSide)
        XCTAssertEqual(policy["maxDecodedDimension"]?.number, CameraOriginalJPEG.maximumDecodedSide)
    }

    func testEveryRawResponseMIMEAndDuplicateHeaderVectorUsesProductionValidator() throws {
        let contract = try JPEGOriginalTestVectors.contract()
        for vector in contract.mimeVectors {
            let values = vector.raw.map { [$0] } ?? []
            if vector.valid { XCTAssertNoThrow(try CameraOriginalJPEG.validateContentTypes(values), vector.id) }
            else { XCTAssertThrowsError(try CameraOriginalJPEG.validateContentTypes(values), vector.id) }
        }
        for vector in contract.headerVectors {
            if vector.valid { XCTAssertNoThrow(try CameraOriginalJPEG.validateContentTypes(vector.values), vector.id) }
            else { XCTAssertThrowsError(try CameraOriginalJPEG.validateContentTypes(vector.values), vector.id) }
        }
        let response = CameraHTTPDownloadResponse(statusCode: 200,
            headers: ["Content-Type": "image/jpeg", "content-type": "image/jpeg"],
            temporaryFileURL: URL(fileURLWithPath: "/unused-synthetic-path"))
        XCTAssertEqual(response.contentTypeValues.count, 2)
        XCTAssertThrowsError(try CameraOriginalJPEG.validateContentTypes(response.contentTypeValues))
    }

    func testEveryFixedByteVectorUsesBoundedProductionStructureParser() throws {
        for vector in try JPEGOriginalTestVectors.contract().byteVectors {
            let data = try vector.data()
            if vector.structuralValid {
                let result = try CameraOriginalJPEG.validateStructure(data)
                XCTAssertEqual(result.width, vector.width, vector.id)
                XCTAssertEqual(result.height, vector.height, vector.id)
            } else { XCTAssertThrowsError(try CameraOriginalJPEG.validateStructure(data), vector.id) }
        }
    }

    func testRealImageIOAcceptanceAndRejectionPreservesOriginalBytes() throws {
        for vector in try JPEGOriginalTestVectors.contract().byteVectors {
            let data = try vector.data()
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("JPEGValidation-\(UUID().uuidString)")
            try data.write(to: url)
            defer { try? FileManager.default.removeItem(at: url) }
            if vector.decodedValid {
                XCTAssertNoThrow(try CameraOriginalJPEG.validate(fileURL: url, expectedBytes: Int64(data.count),
                    contentTypeValues: ["application/octet-stream"]), vector.id)
            } else {
                XCTAssertThrowsError(try CameraOriginalJPEG.validate(fileURL: url, expectedBytes: Int64(data.count),
                    contentTypeValues: ["image/jpeg"]), vector.id)
            }
            XCTAssertEqual(try Data(contentsOf: url), data, "Validator never rewrites or deletes its input: \(vector.id)")
        }
    }

    func testPrematureEntropyEOIMustFailActualImageIODecodeDespiteValidStructure() throws {
        let vectors = try JPEGOriginalTestVectors.contract().byteVectors.filter { $0.id.hasPrefix("premature-entropy-eoi-") }
        XCTAssertEqual(vectors.count, 4)
        for vector in vectors {
            let data = try vector.data()
            let dimensions = try CameraOriginalJPEG.validateStructure(data)
            // ImageIO may tolerate corruption. If this fails on Apple hardware,
            // it is a real unresolved acceptance failure, never skipped or inverted.
            XCTAssertThrowsError(try CameraOriginalJPEG.decode(data, dimensions: dimensions), vector.id)
        }
    }

    func testDecodeEntropyGatePreservesFrozenStructureAndPositiveProgressivePath() throws {
        let vectors = try JPEGOriginalTestVectors.contract().byteVectors.filter(\.structuralValid)
        XCTAssertEqual(vectors.count, 15)
        for vector in vectors {
            let data = try vector.data()
            let dimensions = try CameraOriginalJPEG.validateStructure(data)
            if vector.decodedValid {
                XCTAssertNoThrow(try CameraOriginalJPEG.validateSequentialEntropy(data, dimensions: dimensions), vector.id)
            } else {
                XCTAssertThrowsError(try CameraOriginalJPEG.validateSequentialEntropy(data, dimensions: dimensions), vector.id)
            }
        }
    }

    func testSequentialMCUCoverageRejectsTruncationExtraEntropyAndBadPadding() throws {
        for (width, entropy, valid) in [
            (8, [UInt8(0x3F)], true), // DC category 0 + EOB, then six 1 padding bits.
            (16, [UInt8(0x0F)], true), // Two complete blocks.
            (16, [UInt8(0x3F)], false), // A legal EOI after only one of two MCUs.
            (8, [UInt8(0x3F), 0x00], false), // Surplus entropy after the last MCU.
            (8, [UInt8(0x00)], false), // Padding must be 1 bits, not replacement zero coefficients.
        ] {
            try assertSequential(syntheticSequential(width: width, entropy: entropy), accepted: valid)
        }
    }

    func testSequentialACRunsAndStuffedFFAreCheckedWithinEachBlock() throws {
        // DC=0; three ZRLs; EOB. The last meaningful bit plus padding forms FF00.
        try assertSequential(syntheticSequential(entropy: [0x00, 0xFF, 0x00],
            acSymbols: [0xF0, 0], acLength: 2), accepted: true)
        try assertSequential(syntheticSequential(entropy: [0x00, 0xFF],
            acSymbols: [0xF0, 0], acLength: 2), accepted: false)
        // Four ZRLs overflow the 63 AC positions. Four run=15,size=1 symbols
        // likewise attempt a nonzero coefficient beyond the end of the block.
        try assertSequential(syntheticSequential(entropy: [0x07], acSymbols: [0xF0]), accepted: false)
        try assertSequential(syntheticSequential(entropy: [0x55, 0xFF, 0x00], acSymbols: [0xF1]), accepted: false)
    }

    func testSequentialRestartIntervalCountsMCUsAndAllowsShortFinalInterval() throws {
        try assertSequential(syntheticSequential(width: 16, entropy: [0x3F, 0xFF, 0xD0, 0x3F],
            restartInterval: 1), accepted: true)
        try assertSequential(syntheticSequential(width: 24, entropy: [0x0F, 0xFF, 0xD0, 0x3F],
            restartInterval: 2), accepted: true)
        // Marker order alone is valid, but it cannot replace the second MCU.
        try assertSequential(syntheticSequential(width: 16, entropy: [0x3F, 0xFF, 0xD0, 0x3F],
            restartInterval: 2), accepted: false)
        try assertSequential(syntheticSequential(width: 16, entropy: [0x0F],
            restartInterval: 1), accepted: false)
    }

    func testInterleavedOddDimensionsRequireAllPaddedMCUBlocks() throws {
        // 17x17 4:2:0 uses four 16x16 MCUs, each with four Y + Cb + Cr blocks.
        // All 24 blocks are required, including the padded right/bottom edges.
        try assertSequential(syntheticSampledSequential(scans: [([1, 2, 3], [0, 0, 0, 0, 0, 0])]), accepted: true)
        // The 17 unpadded component blocks are insufficient in an interleaved scan.
        try assertSequential(syntheticSampledSequential(scans: [([1, 2, 3], [0, 0, 0, 0, 0x3F])]), accepted: false)
    }

    func testSeparateSequentialComponentScansUseTheirOwnSampledDimensions() throws {
        // The same 17x17 frame, encoded separately: Y has 3x3 blocks, each chroma
        // component has ceil(17/2)=9 samples per side and therefore 2x2 blocks.
        let complete: [([UInt8], [UInt8])] = [([1], [0, 0, 0x3F]), ([2], [0]), ([3], [0])]
        try assertSequential(syntheticSampledSequential(scans: complete), accepted: true)
        try assertSequential(syntheticSampledSequential(scans: [([1], [0, 0]), ([2], [0]), ([3], [0])]), accepted: false)
        try assertSequential(syntheticSampledSequential(scans: [([1], [0, 0, 0x3F]), ([2], [0x03]), ([3], [0])]), accepted: false)
    }

    func testSequentialLongHuffmanCodesStillRequireEveryAmplitudeBit() throws {
        // A 16-bit DC code, 11 amplitude bits and a 1-bit EOB, then four pad bits.
        try assertSequential(syntheticSequential(entropy: [0, 0, 0, 0x0F],
            dcSymbol: 11, dcLength: 16), accepted: true)
        try assertSequential(syntheticSequential(entropy: [0, 0],
            dcSymbol: 11, dcLength: 16), accepted: false)
        try assertSequential(syntheticSequential(entropy: [0xFF, 0, 0xFF, 0],
            dcSymbol: 11, dcLength: 16), accepted: false)
    }

    func testSequentialEntropyBindsDimensionsAndHonorsCancellation() async throws {
        let data = syntheticSequential(entropy: [0x3F])
        let dimensions = try CameraOriginalJPEG.validateStructure(data)
        XCTAssertThrowsError(try CameraOriginalJPEG.validateSequentialEntropy(data,
            dimensions: .init(width: dimensions.width + 1, height: dimensions.height)))
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            try CameraOriginalJPEG.validateSequentialEntropy(data, dimensions: dimensions)
        }
        switch await task.result {
        case .success: XCTFail("Cancelled entropy validation must not admit bytes")
        case .failure(let error): XCTAssertTrue(error is CancellationError)
        }
    }

    private func assertSequential(_ data: Data, accepted: Bool, file: StaticString = #filePath, line: UInt = #line) throws {
        // These adversarial cases deliberately retain valid marker/scan structure.
        let dimensions = try CameraOriginalJPEG.validateStructure(data)
        if accepted {
            XCTAssertNoThrow(try CameraOriginalJPEG.validateSequentialEntropy(data, dimensions: dimensions), file: file, line: line)
        } else {
            XCTAssertThrowsError(try CameraOriginalJPEG.validateSequentialEntropy(data, dimensions: dimensions), file: file, line: line)
        }
    }

    private func syntheticSequential(width: Int = 8, entropy: [UInt8], dcSymbol: UInt8 = 0, dcLength: Int = 1,
                                     acSymbols: [UInt8] = [0], acLength: Int = 1, restartInterval: Int? = nil) -> Data {
        func segment(_ marker: UInt8, _ payload: [UInt8]) -> [UInt8] {
            let length = payload.count + 2
            return [0xFF, marker, UInt8(length >> 8), UInt8(length & 255)] + payload
        }
        func huffman(_ key: UInt8, _ length: Int, _ symbols: [UInt8]) -> [UInt8] {
            var counts = [UInt8](repeating: 0, count: 16)
            counts[length - 1] = UInt8(symbols.count)
            return [key] + counts + symbols
        }
        var data: [UInt8] = [0xFF, 0xD8]
        data += segment(0xDB, [0] + [UInt8](repeating: 1, count: 64))
        data += segment(0xC0, [8, 0, 8, UInt8(width >> 8), UInt8(width & 255), 1, 1, 0x11, 0])
        data += segment(0xC4, huffman(0, dcLength, [dcSymbol]) + huffman(0x10, acLength, acSymbols))
        if let interval = restartInterval {
            data += segment(0xDD, [UInt8(interval >> 8), UInt8(interval & 255)])
        }
        data += segment(0xDA, [1, 1, 0, 0, 63, 0])
        data += entropy
        data += [0xFF, 0xD9]
        return Data(data)
    }

    private func syntheticSampledSequential(scans: [([UInt8], [UInt8])]) -> Data {
        func segment(_ marker: UInt8, _ payload: [UInt8]) -> [UInt8] {
            let length = payload.count + 2
            return [0xFF, marker, UInt8(length >> 8), UInt8(length & 255)] + payload
        }
        var data: [UInt8] = [0xFF, 0xD8]
        data += segment(0xDB, [0] + [UInt8](repeating: 1, count: 64))
        data += segment(0xC0, [8, 0, 17, 0, 17, 3, 1, 0x22, 0, 2, 0x11, 0, 3, 0x11, 0])
        let dc: [UInt8] = [0, 1] + [UInt8](repeating: 0, count: 15) + [0]
        let ac: [UInt8] = [0x10, 1] + [UInt8](repeating: 0, count: 15) + [0]
        data += segment(0xC4, dc + ac)
        for (components, entropy) in scans {
            let selectors: [UInt8] = components.flatMap { [$0, UInt8(0)] }
            data += segment(0xDA, [UInt8(components.count)] + selectors + [0, 63, 0])
            data += entropy
        }
        return Data(data + [0xFF, 0xD9])
    }

    func testClosedFileSymlinkLengthAndPreCancelledInputsFailWithoutModification() async throws {
        let data = try JPEGOriginalTestVectors.bytes("one-pixel")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("JPEGValidation-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("original.jpg")
        let link = root.appendingPathComponent("alias.jpg")
        try data.write(to: file)
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: file)
        XCTAssertThrowsError(try CameraOriginalJPEG.validate(fileURL: link, expectedBytes: Int64(data.count), contentTypeValues: []))
        XCTAssertThrowsError(try CameraOriginalJPEG.validate(fileURL: file, expectedBytes: Int64(data.count + 1), contentTypeValues: []))
        XCTAssertThrowsError(try CameraOriginalJPEG.validate(fileURL: file,
            expectedBytes: Int64(CameraOriginalJPEG.maximumBytes) + 1, contentTypeValues: []))
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            try CameraOriginalJPEG.validate(fileURL: file, expectedBytes: Int64(data.count), contentTypeValues: [])
        }
        switch await task.result {
        case .success: XCTFail("Pre-cancelled validation accepted bytes")
        case .failure(let error): XCTAssertTrue(error is CancellationError)
        }
        XCTAssertEqual(try Data(contentsOf: file), data)
    }

    func testDimensionBoundaryIsCheckedBeforeAnyNativeDecode() throws {
        let source = try JPEGOriginalTestVectors.bytes("rgb-baseline")
        let sof = try XCTUnwrap(source.range(of: Data([0xFF, 0xC0])))
        for (width, height, accepted) in [(8_000, 8_000, true), (8_001, 8_000, false),
                                          (16_384, 1, true), (16_385, 1, false)] {
            var data = source
            let offset = sof.lowerBound
            data[offset + 5] = UInt8(height >> 8); data[offset + 6] = UInt8(height & 255)
            data[offset + 7] = UInt8(width >> 8); data[offset + 8] = UInt8(width & 255)
            if accepted { XCTAssertNoThrow(try CameraOriginalJPEG.validateStructure(data)) }
            else { XCTAssertThrowsError(try CameraOriginalJPEG.validateStructure(data)) }
        }
    }
}
