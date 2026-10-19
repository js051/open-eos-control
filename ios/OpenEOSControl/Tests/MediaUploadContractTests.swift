import CryptoKit
import Foundation
import XCTest
@testable import OpenEOSCore
@testable import OpenEOSControl

/// Runs the shared corpus through production decisions and strict receipt decoder.
/// App/HTTP and actual document-picker journeys live in the adjoining suites.
final class MediaUploadContractTests: XCTestCase {
    func testUploadJourneyFixtureConfirmsOnlyItsExactSessionRetirement() async throws {
        let peer = BridgeUploadJourneyFixture(mode: "success")
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
        try await client.initialize()
        await client.close()
        let firstUnconfirmed = await client.connectionRetirementUnconfirmed
        XCTAssertFalse(firstUnconfirmed)
        try await client.initialize()
        for path in ["/v1/session/unknown", "/v1/session/upload-ui-1", "/v1/session/upload-ui-2/events"] {
            var request = URLRequest(url: try XCTUnwrap(URL(string: "http://127.0.0.1:18181" + path)))
            request.httpMethod = "DELETE"
            do {
                _ = try await peer.send(request)
                XCTFail("Only the exact active synthetic session can retire")
            } catch let error as URLError {
                XCTAssertEqual(error.code, .unsupportedURL)
            }
        }
        await client.close()
        let secondUnconfirmed = await client.connectionRetirementUnconfirmed
        XCTAssertFalse(secondUnconfirmed)
    }

    private func vectors(_ group: String) throws -> [[String: Any]] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "upload-outcome-vectors", withExtension: "json"))
        let data = try Data(contentsOf: url)
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(),
            "dcb90087f58db32166fa86291486190a1aca1ea368ff7e74cf3342a601c8b1fd")
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try XCTUnwrap(object[group] as? [[String: Any]])
    }
    func testAll48OutcomesIgnoreCancellationAndOrdinaryListObservations() throws {
        let cases = try vectors("outcomeCases")
        XCTAssertEqual(cases.count, 48)
        for row in cases {
            let result = CameraMediaUploadPolicy.outcome(dispatched: row["dispatchStarted"] as! Bool,
                verifiedReceipt: row["verifiedReceiptForAttempt"] as! Bool)
            XCTAssertEqual(result.rawValue, row["expectedOutcome"] as? String, row["id"] as! String)
        }
    }
    func testAll12OpeningOwnerSingleUseAdmissionCases() throws {
        let cases = try vectors("admissionCases")
        XCTAssertEqual(cases.count, 12)
        for row in cases {
            let result = CameraMediaUploadPolicy.admits(
                owner: row["openingOwner"] as! String, currentOwner: row["currentOwner"] as? String,
                token: row["openingToken"] as! String, currentToken: row["currentToken"] as? String,
                singleUseAvailable: row["singleUseAvailable"] as! Bool, supported: row["featureSupported"] as! Bool,
                busy: row["mediaBusy"] as! Bool, sourceSelected: row["sourceSelected"] as! Bool)
            XCTAssertEqual(result, row["expectedAdmit"] as? Bool, row["id"] as! String)
        }
    }
    func testAll20ProgressReceiptErrorRefreshAndFinallyOwnershipCases() throws {
        let cases = try vectors("publicationCases")
        XCTAssertEqual(cases.count, 20)
        for row in cases {
            let result = CameraMediaUploadPolicy.owns(
                owner: row["attemptOwner"] as! String, currentOwner: row["currentOwner"] as? String,
                token: row["attemptToken"] as! String, currentToken: row["currentToken"] as? String)
            XCTAssertEqual(result, row["expectedPublish"] as? Bool, row["id"] as! String)
        }
    }
    func testAll48ReceiptsUseProductionRawJSONAndSameAttemptVerifier() throws {
        let cases = try vectors("receiptCases")
        XCTAssertEqual(cases.count, 48)
        for row in cases {
            let attempt = UUID()
            let receipt = try? CameraMediaUploadReceipt.mediaItem(
                CameraHTTPUploadResponse(statusCode: row["statusCode"] as! Int,
                    body: Data((row["rawResponseJSON"] as! String).utf8)),
                filename: row["expectedFilename"] as! String, size: (row["expectedSizeBytes"] as! NSNumber).int64Value,
                responseAttemptID: row["boundToAttempt"] as! Bool ? attempt : UUID(), expectedAttemptID: attempt)
            XCTAssertEqual(receipt != nil, row["expectedVerified"] as? Bool, row["id"] as! String)
            if row["id"] as? String == "opaque-id-preserved" {
                let original = try JSONSerialization.jsonObject(with: Data((row["rawResponseJSON"] as! String).utf8)) as! [String: Any]
                XCTAssertEqual(Array(try XCTUnwrap(receipt).id.utf8), Array((original["id"] as! String).utf8))
            }
        }
    }
    func testAll14JourneysUseProductionAdmissionAttemptAndOwnerDecisions() throws {
        let cases = try vectors("journeyCases")
        XCTAssertEqual(cases.count, 14)
        for row in cases {
            let attempt = CameraMediaUploadAttempt()
            let openingOwner = UUID(), openingToken = UUID()
            var currentOwner = openingOwner
            var available = false, admitted = false
            var dispatches = 0
            for event in row["events"] as! [String] {
                switch event {
                case "open": available = true
                case "picker-cancel": available = false
                case "picker-result", "repeated-picker-result":
                    let decision = CameraMediaUploadPolicy.admits(owner: openingOwner, currentOwner: currentOwner,
                        token: openingToken, currentToken: openingToken, singleUseAvailable: available,
                        supported: true, busy: false, sourceSelected: true)
                    available = false
                    if decision { admitted = true }
                case "dispatch":
                    if admitted { try attempt.markDispatched(); dispatches += 1 }
                case "cancel": attempt.cancel()
                case "verified-receipt":
                    attempt.recordVerified(CameraMediaItem(id: "exact-result", name: "SYNTHETIC.JPG", kind: "photo", sizeBytes: 7))
                case "reconnect": currentOwner = UUID()
                default: break // Read/list/failure observations are deliberately not receipt inputs.
                }
            }
            let owned = CameraMediaUploadPolicy.owns(owner: openingOwner, currentOwner: currentOwner,
                                                      token: attempt.id, currentToken: attempt.id)
            XCTAssertEqual(dispatches, row["expectedDispatches"] as? Int, row["id"] as! String)
            XCTAssertEqual(attempt.outcome.rawValue, row["expectedOutcome"] as? String, row["id"] as! String)
            XCTAssertEqual(owned && attempt.receipt != nil,
                           row["mayPublishConfirmationForCurrentOwner"] as? Bool, row["id"] as! String)
            XCTAssertLessThanOrEqual(dispatches, 1)
            if dispatches > 0 || (row["events"] as! [String]).contains("cancel") {
                XCTAssertThrowsError(try attempt.markDispatched(), "One-shot/cancelled attempts cannot be replayed")
            }
        }
    }
    func testStagingOwnsAnImmutableCopyAndCleansUpWithoutDispatch() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("SOURCE.JPG")
        try Data([1, 2, 3]).write(to: source)
        var staged: CameraMediaUploadStagedFile? = try CameraMediaUploadStagedFile.create(from: source)
        let owned = try XCTUnwrap(staged?.url)
        try Data([4, 5, 6]).write(to: source)
        XCTAssertEqual(try Data(contentsOf: owned), Data([1, 2, 3]))
        XCTAssertEqual(staged?.size, 3)
        staged = nil
        XCTAssertFalse(FileManager.default.fileExists(atPath: owned.path))
    }
}
