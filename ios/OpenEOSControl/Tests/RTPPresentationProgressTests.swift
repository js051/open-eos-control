import CryptoKit
import Foundation
import XCTest

@testable import OpenEOSControl

final class RTPPresentationProgressTests: XCTestCase {
    private let owner = UUID(), epoch = UUID(), lut = UUID()

    private func frame(_ sequence: UInt64) -> IOSRTPFrameToken {
        IOSRTPFrameToken(owner: owner, epoch: epoch, lutRevision: lut, sequence: sequence)
    }

    private func progress() -> IOSRTPPresentationProgress {
        var value = IOSRTPPresentationProgress()
        value.begin(owner: owner, epoch: epoch, lutRevision: lut, at: 100)
        return value
    }

    func testContinuousEncodedInputCannotPreventDecodedOutputTimeout() {
        var value = progress()
        // There is deliberately no packet/input API on the output evidence reducer.
        XCTAssertNil(value.failure(at: 104.999))
        XCTAssertEqual(value.failure(at: 105), .decodedOutputStalled)
    }

    func testDelayedMainActorDeliveryCannotRejuvenateOldDecoderOutput() {
        var value = progress()
        XCTAssertTrue(value.recordDecoded(frame(1), at: 100))
        XCTAssertTrue(value.recordSubmitted(frame(1), at: 106))
        XCTAssertEqual(value.failure(at: 106), .decodedOutputStalled,
                       "Submission/receipt time is not the physical decoder completion time")
    }

    func testDecodedFramesWithoutSurfaceSubmissionHaveBoundedOutputFailure() {
        var value = progress()
        for index in 1...5 { XCTAssertTrue(value.recordDecoded(frame(UInt64(index)), at: 100 + Double(index))) }
        XCTAssertEqual(value.failure(at: 105), .outputUnavailable)
        XCTAssertNil(value.presented)
    }

    func testContinuingDecodedAndSubmittedFramesCannotKeepFrozenPresentationAlive() {
        var value = progress()
        XCTAssertTrue(value.recordDecoded(frame(1), at: 100))
        XCTAssertTrue(value.recordSubmitted(frame(1), at: 100))
        XCTAssertTrue(value.recordPresented(frame(1), at: 100))
        for index in 2...6 {
            let now = 99 + Double(index)
            XCTAssertTrue(value.recordDecoded(frame(UInt64(index)), at: now))
            XCTAssertTrue(value.recordSubmitted(frame(UInt64(index)), at: now))
            XCTAssertFalse(value.recordPresented(frame(1), at: now), "An old displayed buffer never refreshes progress")
        }
        XCTAssertEqual(value.failure(at: 105), .presentationStalled)
    }

    func testUnavailableAndCopiedReadbackRemainUnknownWithoutInventingCameraFailure() {
        for pixelsAvailable in [false, true] {
            var value = progress()
            for index in 1...20 {
                let now = 100 + Double(index)
                XCTAssertTrue(value.recordDecoded(frame(UInt64(index)), at: now))
                XCTAssertTrue(value.recordSubmitted(frame(UInt64(index)), at: now))
                value.recordUnverifiablePresentation(at: now, pixelsAvailable: pixelsAvailable)
                XCTAssertNil(value.failure(at: now))
                XCTAssertEqual(value.state, .unknown)
                XCTAssertNil(value.presented)
            }
        }
    }

    func testLosingPreviouslyConfirmedReadbackDoesNotEraseItsStallDeadline() {
        var value = progress()
        value.recordDecoded(frame(1), at: 100)
        value.recordSubmitted(frame(1), at: 100)
        value.recordPresented(frame(1), at: 100)
        value.recordDecoded(frame(2), at: 104)
        value.recordSubmitted(frame(2), at: 104)
        value.recordUnverifiablePresentation(at: 104, pixelsAvailable: false)
        XCTAssertEqual(value.state, .unknown)
        XCTAssertEqual(value.failure(at: 105), .presentationStalled)
    }

    func testStaticImageWithNewIdentitiesIsLegitimateProgress() {
        var value = progress()
        for index in 1...20 {
            let token = frame(UInt64(index)), now = 100 + Double(index)
            XCTAssertTrue(value.recordDecoded(token, at: now))
            XCTAssertTrue(value.recordSubmitted(token, at: now))
            XCTAssertTrue(value.recordPresented(token, at: now))
            XCTAssertNil(value.failure(at: now))
        }
        // Image hashes are absent: unchanged scene content is not a failure.
        XCTAssertEqual(value.state, .progressing)
    }

    func testSubmissionAloneAndOutOfOrderTokensCannotManufactureEvidence() {
        var value = progress()
        XCTAssertFalse(value.recordSubmitted(frame(1), at: 100))
        XCTAssertFalse(value.recordPresented(frame(1), at: 100))
        XCTAssertTrue(value.recordDecoded(frame(2), at: 100))
        XCTAssertFalse(value.recordDecoded(frame(1), at: 101))
        XCTAssertFalse(value.recordSubmitted(frame(1), at: 101))
        XCTAssertTrue(value.recordSubmitted(frame(2), at: 101))
        XCTAssertFalse(value.recordPresented(frame(3), at: 101))
        XCTAssertTrue(value.recordPresented(frame(2), at: 101))
        XCTAssertFalse(value.recordPresented(frame(1), at: 102))
        XCTAssertFalse(value.recordDecoded(frame(3), at: 99))
    }

    func testOwnerRendererAndLutRetirementRejectLateOutput() {
        for identity in 0..<3 {
            var value = progress()
            let old = frame(1)
            value.recordDecoded(old, at: 100)
            value.recordSubmitted(old, at: 100)
            value.begin(owner: identity == 0 ? UUID() : owner,
                        epoch: identity == 1 ? UUID() : epoch,
                        lutRevision: identity == 2 ? UUID() : lut, at: 101)
            XCTAssertFalse(value.recordDecoded(old, at: 101))
            XCTAssertFalse(value.recordSubmitted(old, at: 101))
            XCTAssertFalse(value.recordPresented(old, at: 101))
            XCTAssertNil(value.presented)
        }
    }

    func testRepeatedRendererResetCannotExtendDecoderFailureDeadline() {
        var value = progress()
        for index in 1...5 {
            value.begin(owner: owner, epoch: UUID(), lutRevision: lut, at: 100 + Double(index))
        }
        XCTAssertEqual(value.failure(at: 105), .decodedOutputStalled)
    }

    func testLutResetPreservesLastSubmissionClockInsteadOfInstantFailureOrUnlimitedGrace() {
        var value = progress()
        value.recordDecoded(frame(1), at: 200)
        value.recordSubmitted(frame(1), at: 200)
        value.begin(owner: owner, epoch: UUID(), lutRevision: UUID(), at: 201)
        XCTAssertNil(value.failure(at: 201), "A long-running source gets its existing recent-output grace")
        value.begin(owner: owner, epoch: UUID(), lutRevision: UUID(), at: 204)
        XCTAssertEqual(value.failure(at: 205), .decodedOutputStalled, "Repeated reset cannot renew either output clock")
    }

    func testReplacementOwnerGetsFreshDeadlineAndExplicitRendererFailureIsImmediate() {
        var value = progress()
        value.begin(owner: UUID(), epoch: UUID(), lutRevision: UUID(), at: 200)
        XCTAssertNil(value.failure(at: 201))
        XCTAssertEqual(value.failure(at: 201, rendererFailed: true), .rendererFailed)
    }
}

final class RTPMonitorEvidenceContractTests: XCTestCase {
    private struct Contract: Decodable {
        struct Case: Decodable {
            let name: String
            let input: IOSRTPMonitorEvidenceInput
            let expected: IOSRTPMonitorEvidenceResult
        }
        let schemaVersion: Int
        let cases: [Case]
    }

    func testAll112SharedEvidenceVectorsFailClosedWithStrictPrimitiveTypes() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "monitor-vectors", withExtension: "json"))
        let data = try Data(contentsOf: url)
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(),
                       "6809376250b9ffb91a74a4348d69b7fd03d397f03aecc47e95d5da3a0cc03e45")
        let contract = try JSONDecoder().decode(Contract.self, from: data)
        XCTAssertEqual(contract.schemaVersion, 1)
        XCTAssertEqual(contract.cases.count, 112)
        XCTAssertEqual(Set(contract.cases.map(\.name)).count, 112)
        for vector in contract.cases {
            XCTAssertEqual(evaluateRtpMonitorEvidence(vector.input), vector.expected, vector.name)
        }
    }

    func testNonfiniteAgesAndTokensNeverPublish() {
        let valid = IOSRTPMonitorEvidenceInput(active: true, sameOwner: true, sameRenderer: true,
            foregroundRequired: true, foreground: true, terminal: false, stopPending: true,
            sampleFrame: 1, currentFrame: 1, sampleLut: 0, currentLut: 0,
            sampleAgeMs: 0, frameAgeMs: 0, maxAgeMs: 1000, evidence: "presentation-confirmed")
        for value in [Double.nan, .infinity, -.infinity] {
            for field in 0..<5 {
                var input = valid
                switch field {
                case 0: input.sampleAgeMs = value
                case 1: input.frameAgeMs = value
                case 2: input.maxAgeMs = value
                case 3: input.sampleFrame = value
                default: input.sampleLut = value
                }
                XCTAssertFalse(evaluateRtpMonitorEvidence(input).canPublishDecodedAnalysis)
            }
        }
    }
}
