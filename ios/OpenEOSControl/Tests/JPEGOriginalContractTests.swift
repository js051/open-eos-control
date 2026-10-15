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
