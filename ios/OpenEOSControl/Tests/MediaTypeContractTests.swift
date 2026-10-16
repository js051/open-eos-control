import CryptoKit
import Foundation
import OpenEOSCore
import XCTest
@testable import OpenEOSControl

/// Real production classifier, gallery, review and foreground-plan callers.
/// Native execution is NOT_RUN until compiled and run with Xcode on macOS.
final class MediaTypeContractTests: XCTestCase {
    private struct Item: Decodable {
        let id: String
        let name: String
        let kind: String
        let contentType: String?
        var media: CameraMediaItem {
            CameraMediaItem(id: id, name: name, kind: kind, sizeBytes: 4, contentType: contentType)
        }
    }
    private struct Expected: Decodable {
        let classification: String
        let isPhoto: Bool
        let isVideo: Bool
        let automaticJpegEligible: Bool
    }
    private struct Vector: Decodable { let id: String; let item: Item; let expected: Expected }
    private struct Fresh: Decodable {
        let id: String
        let original: Item
        let metadata: Item
        let fresh: Bool
        let expectedAdmitted: Bool
    }
    private struct Selection: Decodable {
        let id: String
        let intent: String
        let listingCases: [String]
        let knownCaseIds: [String]
        let expectedCaseId: String?
    }
    private struct Contract: Decodable {
        let schemaVersion: Int
        let cases: [Vector]
        let freshMetadataCases: [Fresh]
        let selectionCases: [Selection]
    }

    private func contractData() throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "media-type-vectors", withExtension: "json"))
        let size = try XCTUnwrap(url.resourceValues(forKeys: [.fileSizeKey]).fileSize)
        guard size == 22_867 else { throw URLError(.dataLengthExceedsMaximum) }
        let data = try Data(contentsOf: url)
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(),
                       "d24efab5bb73d5edc1d222395322f4295c4c31a3ae180d8b3d9293ce4e4b7d14")
        return data
    }

    private func contract() throws -> Contract {
        let contract = try JSONDecoder().decode(Contract.self, from: contractData())
        XCTAssertEqual(contract.schemaVersion, 1)
        XCTAssertEqual(contract.cases.count, 71)
        XCTAssertEqual(contract.freshMetadataCases.count, 15)
        XCTAssertEqual(contract.selectionCases.count, 4)
        return contract
    }

    func testFixedRawAliasVectorsUseSharedProductionResolver() throws {
        let raw = try XCTUnwrap(try JSONSerialization.jsonObject(with: contractData()) as? [String: Any])
        let vectors = try XCTUnwrap(raw["mimeAliasCases"] as? [[String: Any]])
        XCTAssertEqual(vectors.count, 19)
        for vector in vectors {
            let id = try XCTUnwrap(vector["id"] as? String)
            let fields = try XCTUnwrap(vector["fields"] as? [String: Any])
            let valid = try XCTUnwrap(vector["expectedValid"] as? Bool)
            if valid {
                let result = try CameraMediaType.declaredMIME(in: fields)
                let expected = vector["expectedValue"] as? String
                XCTAssertEqual(result.map { Data($0.utf8) }, expected.map { Data($0.utf8) }, id)
            } else {
                XCTAssertThrowsError(try CameraMediaType.declaredMIME(in: fields), id)
            }
        }
    }

    func testEveryFixedVectorUsesProductionClassificationGalleryAndStrictGate() throws {
        for vector in try contract().cases {
            let item = vector.item.media
            XCTAssertEqual(CameraMediaType.classify(item).rawValue, vector.expected.classification, vector.id)
            XCTAssertEqual(mediaIsPhoto(item), vector.expected.isPhoto, vector.id)
            XCTAssertEqual(mediaIsVideo(item), vector.expected.isVideo, vector.id)
            XCTAssertEqual(ForegroundMediaPlan.isJPEG(item), vector.expected.automaticJpegEligible, vector.id)
            XCTAssertEqual(mediaItemsForDisplay([item], filter: .photos, sort: .camera).count,
                           vector.expected.isPhoto ? 1 : 0, vector.id)
            XCTAssertEqual(mediaItemsForDisplay([item], filter: .videos, sort: .camera).count,
                           vector.expected.isVideo ? 1 : 0, vector.id)
            XCTAssertEqual(mediaItemsForDisplay([item], filter: .all, sort: .camera), [item], vector.id)
            var plan = ForegroundMediaPlan()
            try plan.observeInventory([], at: 0)
            try plan.observeInventory([], at: 1)
            try plan.observeInventory([item], at: 5)
            XCTAssertEqual(plan.pending.count, vector.expected.automaticJpegEligible ? 1 : 0, vector.id)
            XCTAssertEqual(plan.known.count, 1, "Every type is remembered: \(vector.id)")
        }
    }

    func testFreshMetadataVectorsKeepIdentityTypeAndStableSize() throws {
        for vector in try contract().freshMetadataCases {
            let original = vector.original.media
            let metadata = vector.metadata.media
            XCTAssertEqual(CameraMediaType.admitsFreshAutomaticJPEG(original: original, metadata: metadata,
                                                                    fresh: vector.fresh),
                           vector.expectedAdmitted, vector.id)
            // The plan receives only successful explicit fresh-info responses.
            guard vector.fresh else { continue }
            var plan = ForegroundMediaPlan()
            try plan.observeInventory([], at: 0)
            try plan.observeInventory([], at: 1)
            try plan.observeInventory([original], at: 5)
            if vector.expectedAdmitted {
                XCTAssertNil(try plan.observeFresh(metadata, at: 5), vector.id)
                XCTAssertEqual(try plan.observeFresh(metadata, at: 6), 4, vector.id)
            } else {
                XCTAssertThrowsError(try plan.observeFresh(metadata, at: 5), vector.id)
            }
            XCTAssertEqual(plan.preparedCount, 0, "Observation never fabricates a retained copy")
        }
    }

    func testReviewVectorsPreserveKnownIDExclusionAndCameraOrder() throws {
        let contract = try contract()
        let indexed = Dictionary(uniqueKeysWithValues: contract.cases.map { ($0.id, $0.item.media) })
        for vector in contract.selectionCases {
            let items = try vector.listingCases.map { try XCTUnwrap(indexed[$0]) }
            let known = Set(try vector.knownCaseIds.map { try XCTUnwrap(indexed[$0]).id })
            let selected = vector.intent == "recent" ? selectLatestMediaItem(items) : selectLatestMediaItem(
                afterCaptureFrom: items, previousIDs: known, kind: vector.intent == "video" ? .video : .photo)
            XCTAssertEqual(selected?.id, vector.expectedCaseId.flatMap { indexed[$0]?.id }, vector.id)
        }
    }
}
