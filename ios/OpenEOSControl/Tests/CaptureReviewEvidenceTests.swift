import CryptoKit
import Foundation
import XCTest
@testable import OpenEOSControl

/// Fixed JSON expectations are shared by every platform, never calculated from Swift output.
struct CaptureReviewEvidenceContract: Decodable {
    struct Expected: Decodable {
        let observation: String
        let successfulReads: Int
        let failedReads: Int
        let lastCompletedRead: String?
        let candidateId: String?
    }
    struct Vector: Decodable {
        let id: String
        let intent: String
        let reads: [String]
        let expected: Expected
    }
    struct Retry: Decodable {
        let id: String
        let first: [String]
        let second: [String]
        let expected: Expected
    }
    struct Retirement: Decodable {
        let id: String
        let completedReads: Int
        let publish: Bool
        let preservePrevious: Bool
    }
    let schemaVersion: Int
    let previousIds: [String]
    let retryDelaysMillis: [UInt64]
    let maximumReads: Int
    let cases: [Vector]
    let retryCases: [Retry]
    let retirementCases: [Retirement]

    static func load() throws -> Self {
        let url = try XCTUnwrap(Bundle(for: CaptureReviewEvidenceTests.self)
            .url(forResource: "review-evidence-vectors", withExtension: "json"))
        let size = try XCTUnwrap(url.resourceValues(forKeys: [.fileSizeKey]).fileSize)
        XCTAssertGreaterThan(size, 0)
        XCTAssertLessThanOrEqual(size, 32_768)
        guard size > 0 && size <= 32_768 else { throw URLError(.dataLengthExceedsMaximum) }
        let data = try Data(contentsOf: url)
        XCTAssertEqual(data.count, 25_745)
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(),
                       "aab83ace169997c2f9788ea45f2863513f0a0d99e3211143c0e60475eb7ec7b1")
        let fixture = try JSONDecoder().decode(Self.self, from: data)
        XCTAssertEqual(fixture.schemaVersion, 1)
        XCTAssertEqual(fixture.previousIds, ["OLD_A", "OLD_B"])
        XCTAssertEqual(fixture.retryDelaysMillis, [250, 750, 1500])
        XCTAssertEqual(fixture.maximumReads, 4)
        XCTAssertEqual(fixture.cases.count, 106)
        XCTAssertEqual(Set(fixture.cases.map(\.id)).count, 106)
        XCTAssertEqual(fixture.retryCases.count, 2)
        XCTAssertEqual(fixture.retirementCases.count, 4)
        XCTAssertTrue(fixture.retirementCases.allSatisfy { !$0.publish && $0.preservePrevious })
        XCTAssertEqual(fixture.retirementCases.map(\.id), [
            "zero-completed-retry-preserves-previous", "cancel-in-flight-ignores-late-success",
            "cancel-in-delay-does-not-publish-partial-evidence", "replaced-owner-ignores-late-failure",
        ])
        return fixture
    }
}

final class CaptureReviewEvidenceTests: XCTestCase {
    func testEmptyEvidenceMeansUnreadWithoutFabricatingReadFailure() {
        let outcome = CaptureReviewOutcome()
        XCTAssertEqual(outcome.observation, .unread)
        XCTAssertEqual(outcome.readEvidence.successfulReads, 0)
        XCTAssertEqual(outcome.readEvidence.failedReads, 0)
        XCTAssertEqual(outcome.readEvidence.completedReads, 0)
        XCTAssertNil(outcome.readEvidence.lastCompletedRead)
        XCTAssertEqual(outcome.terminalState, .notReady)
        XCTAssertFalse(outcome.showsLastReadFailureWarning)
        for kind in [CaptureReviewKind.photo, .video] {
            XCTAssertEqual(kind.localizationKey(for: .notReady, outcome: outcome),
                           kind.localizationKey(suffix: "not_checked"))
        }
    }

    func testBothLocalizedFactsSurviveMixedReadEvidence() throws {
        var evidence = CaptureReviewReadEvidence()
        evidence.record(.success)
        evidence.record(.failure)
        let mixed = CaptureReviewOutcome(readEvidence: evidence)
        XCTAssertEqual(mixed.observation, .noCandidateObserved)
        XCTAssertEqual(mixed.terminalState, .notReady)
        XCTAssertTrue(mixed.showsLastReadFailureWarning)
        evidence.record(.success)
        let endingSuccess = CaptureReviewOutcome(readEvidence: evidence)
        XCTAssertEqual(endingSuccess.observation, .noCandidateObserved)
        XCTAssertEqual(endingSuccess.readEvidence.failedReads, 1)
        XCTAssertFalse(endingSuccess.showsLastReadFailureWarning)
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        for language in ["en", "zh-Hant"] {
            let source = try String(contentsOf: root.appendingPathComponent("App/Resources/\(language).lproj/Localizable.strings"), encoding: .utf8)
            func localizedLine(_ key: String) throws -> String {
                let matches = source.split(separator: "\n").filter { $0.hasPrefix("\"\(key)\" = ") }
                XCTAssertEqual(matches.count, 1)
                return String(try XCTUnwrap(matches.first))
            }
            for kind in [CaptureReviewKind.photo, .video] {
                for suffix in ["notReady", "readFailed", "last_read_failed", "not_checked"] {
                    let key = kind.localizationKey(suffix: suffix)
                    let value = try localizedLine(key)
                    XCTAssertFalse(value.isEmpty)
                    XCTAssertEqual(source.components(separatedBy: "\"\(key)\" = ").count - 1, 1)
                }
                let status = try localizedLine(kind.localizationKey(for: .notReady, outcome: mixed))
                let warning = try localizedLine(kind.localizationKey(suffix: "last_read_failed"))
                XCTAssertTrue(status.contains(language == "en" ? "No newly visible" : "尚未找到新出現"))
                XCTAssertTrue(warning.contains(language == "en" ? "last" : "最後一次"))
                XCTAssertTrue(warning.contains(language == "en" ? "failed" : "失敗"))
            }
        }
    }
}
