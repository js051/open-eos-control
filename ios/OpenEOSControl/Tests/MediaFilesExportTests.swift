import Foundation
import OpenEOSCore
import UIKit
import XCTest

@testable import OpenEOSControl

/// Controlled local files and delegate calls only. No picker is presented and
/// no test performs a real external export, provider read, or cloud sync.
@MainActor
final class MediaFilesExportTests: XCTestCase {
    func testExactPositiveCountIsOnlyProviderReportedAndWaitsForTeardown() throws {
        let owner = MediaFilesExportController()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload(count: 2)))
        let attempt = try XCTUnwrap(owner.presentation)
        let coordinator = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
        XCTAssertNil(coordinator.presentationFailure())
        coordinator.finish(.reported(count: 2))
        XCTAssertEqual(owner.result, MediaFilesExportResult(attemptID: attempt.id,
            expectedCount: 2, reportedCount: 2, status: .providerReported))
        XCTAssertNil(owner.presentation)
        XCTAssertTrue(owner.isActive)
        XCTAssertFalse(owner.beginUnrecordedForTesting(try payload()))
        coordinator.dismantle()
        XCTAssertFalse(owner.isActive)
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
    }

    func testZeroPartialExcessAndNegativeCountsLeaveWholeAttemptUnconfirmed() throws {
        for count in [0, 1, 3, -1, 101, Int.max] {
            let owner = MediaFilesExportController()
            XCTAssertTrue(owner.beginUnrecordedForTesting(try payload(count: 2)))
            let attempt = try XCTUnwrap(owner.presentation)
            let coordinator = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
            coordinator.finish(.reported(count: count))
            XCTAssertEqual(owner.result?.status, .unconfirmed)
            XCTAssertEqual(owner.result?.expectedCount, 2)
            XCTAssertEqual(owner.result?.reportedCount, (0...100).contains(count) ? count : nil)
            XCTAssertFalse(owner.beginUnrecordedForTesting(try payload()))
            coordinator.dismantle()
            XCTAssertNil(owner.presentation, "An unknown result must never trigger a retry")
        }
    }

    func testDuplicateDestinationURLsDoNotProduceACompleteCount() throws {
        let owner = MediaFilesExportController()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload(count: 2)))
        let attempt = try XCTUnwrap(owner.presentation)
        let coordinator = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
        let picker = UIDocumentPickerViewController(forExporting: attempt.payload.fileURLs, asCopy: true)
        let destination = URL(fileURLWithPath: "/synthetic-provider/SAME.JPG")
        coordinator.documentPicker(picker, didPickDocumentsAt: [destination, destination])
        XCTAssertEqual(owner.result?.status, .unconfirmed)
        XCTAssertNil(owner.result?.reportedCount)
        coordinator.dismantle()
    }

    func testDelegateReportsCountWithoutReadingOrMappingDestinationURLs() throws {
        let owner = MediaFilesExportController()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload(count: 2, sameFilename: true)))
        let attempt = try XCTUnwrap(owner.presentation)
        let coordinator = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
        let picker = UIDocumentPickerViewController(forExporting: attempt.payload.fileURLs, asCopy: true)
        // These paths do not exist. Order and names do not establish a source
        // mapping, and the application must not read or store either destination.
        coordinator.documentPicker(picker, didPickDocumentsAt: [
            URL(fileURLWithPath: "/synthetic-provider/renamed-B.CR3"),
            URL(fileURLWithPath: "/synthetic-provider/renamed-A.JPG")
        ])
        XCTAssertEqual(owner.result?.status, .providerReported)
        XCTAssertEqual(owner.result?.reportedCount, 2)
        XCTAssertTrue(attempt.payload.fileURLs.allSatisfy { FileManager.default.fileExists(atPath: $0.path) })
        coordinator.dismantle()
    }

    func testCancelThenDuplicateOrLateCallbacksCannotClaimExport() throws {
        let owner = MediaFilesExportController()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
        let attempt = try XCTUnwrap(owner.presentation)
        let coordinator = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
        let picker = UIDocumentPickerViewController(forExporting: attempt.payload.fileURLs, asCopy: true)
        coordinator.documentPickerWasCancelled(picker)
        let cancelled = owner.result
        coordinator.documentPicker(picker, didPickDocumentsAt: [URL(fileURLWithPath: "/synthetic-provider/LATE.JPG")])
        coordinator.documentPickerWasCancelled(picker)
        coordinator.dismantle()
        XCTAssertEqual(owner.result, cancelled)
        XCTAssertEqual(owner.result?.status, .cancelled)
    }

    func testReportedResultCannotBeDowngradedByCancelDismissOrTeardown() throws {
        let owner = MediaFilesExportController()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
        let attempt = try XCTUnwrap(owner.presentation)
        let coordinator = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
        coordinator.finish(.reported(count: 1))
        let reported = owner.result
        coordinator.finish(.cancelled)
        owner.dismissPresentation(attempt.id)
        coordinator.dismantle()
        coordinator.dismantle()
        XCTAssertEqual(owner.result, reported)
        XCTAssertFalse(owner.isActive)
    }

    func testInteractiveDismissWithoutDocumentCallbackIsUnconfirmed() throws {
        let owner = MediaFilesExportController()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
        let attempt = try XCTUnwrap(owner.presentation)
        let coordinator = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
        let picker = UIDocumentPickerViewController(forExporting: attempt.payload.fileURLs, asCopy: true)
        coordinator.presentationControllerDidDismiss(UIPresentationController(presentedViewController: picker, presenting: nil))
        XCTAssertEqual(owner.result?.status, .unconfirmed)
        XCTAssertTrue(owner.isActive)
        coordinator.dismantle()
        XCTAssertFalse(owner.isActive)
    }

    func testDismissBeforeCoordinatorCreationDoesNotLeaveAdmissionStuck() throws {
        let owner = MediaFilesExportController()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
        let old = try XCTUnwrap(owner.presentation)
        owner.dismissPresentation(old.id)
        XCTAssertEqual(owner.result?.status, .unconfirmed)
        XCTAssertFalse(owner.isActive)
        XCTAssertNil(owner.presentation)
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
        let replacementID = owner.presentation?.id
        let lateCoordinator = MediaFilesExportCoordinator(attempt: old, owner: owner)
        XCTAssertEqual(lateCoordinator.presentationFailure(), .unconfirmed)
        lateCoordinator.dismantle()
        XCTAssertEqual(owner.presentation?.id, replacementID)
        XCTAssertNil(owner.result)
    }

    func testProgrammaticTeardownWithoutCallbackIsUnconfirmed() throws {
        let owner = MediaFilesExportController()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
        let attempt = try XCTUnwrap(owner.presentation)
        let coordinator = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
        coordinator.dismantle()
        XCTAssertEqual(owner.result?.status, .unconfirmed)
        XCTAssertNil(owner.result?.reportedCount)
        XCTAssertNil(owner.presentation)
        XCTAssertFalse(owner.isActive)
        coordinator.finish(.reported(count: 1))
        XCTAssertEqual(owner.result?.status, .unconfirmed)
    }

    func testStaleBindingCallbackAndTeardownCannotDismissReplacement() throws {
        let owner = MediaFilesExportController()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
        let old = try XCTUnwrap(owner.presentation)
        let oldCoordinator = MediaFilesExportCoordinator(attempt: old, owner: owner)
        oldCoordinator.dismantle()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload(count: 2)))
        let replacement = try XCTUnwrap(owner.presentation)
        let current = MediaFilesExportCoordinator(attempt: replacement, owner: owner)
        owner.dismissPresentation(old.id)
        owner.receive(.reported(count: 1), for: old.id)
        owner.presentationEnded(old.id)
        oldCoordinator.dismantle()
        XCTAssertEqual(owner.presentation?.id, replacement.id)
        XCTAssertNil(owner.result)
        XCTAssertNil(current.presentationFailure())
        current.finish(.reported(count: 2))
        XCTAssertEqual(owner.result?.attemptID, replacement.id)
        current.dismantle()
    }

    func testDuplicateAndRetiredRepresentablesCannotHandFilesToUIKitOrSettleCurrentOwner() throws {
        let owner = MediaFilesExportController()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
        let attempt = try XCTUnwrap(owner.presentation)
        let first = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
        let duplicate = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
        XCTAssertEqual(duplicate.presentationFailure(), .unconfirmed)
        duplicate.finish(.cancelled)
        duplicate.dismantle()
        XCTAssertNil(owner.result)
        XCTAssertNil(first.presentationFailure())
        first.dismantle()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
        let nextID = owner.presentation?.id
        let retired = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
        XCTAssertEqual(retired.presentationFailure(), .unconfirmed)
        retired.dismantle()
        XCTAssertEqual(owner.presentation?.id, nextID)
    }

    func testMissingEmptyAndUnavailablePayloadRejectBeforePresentation() throws {
        let owner = MediaFilesExportController()
        XCTAssertFalse(owner.beginUnrecordedForTesting(nil))
        for empty in [false, true] {
            let file = try MediaBatchOwnedFile.create(filename: "UNAVAILABLE.JPG")
            if empty { try Data().write(to: file.fileURL) }
            XCTAssertFalse(owner.beginUnrecordedForTesting(MediaBatchExportPayload(ownedFile: file)))
            XCTAssertEqual(owner.result?.status, .sourceUnavailable)
            XCTAssertFalse(owner.isActive)
            XCTAssertNil(owner.presentation)
        }
    }

    func testReplacementEditTruncationAndRemovalAfterCaptureAreRejected() throws {
        for change in ["replace", "edit", "truncate", "remove"] {
            let source = try payload()
            let url = try XCTUnwrap(source.fileURLs.first)
            switch change {
            case "replace": try Data([8, 8, 8]).write(to: url, options: .atomic)
            case "edit":
                let file = try FileHandle(forWritingTo: url)
                try file.write(contentsOf: Data([7, 7, 7]))
                try file.close()
                try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 1_000)], ofItemAtPath: url.path)
            case "truncate": try Data().write(to: url)
            default: try FileManager.default.removeItem(at: url)
            }
            let owner = MediaFilesExportController()
            XCTAssertFalse(owner.beginUnrecordedForTesting(source), change)
            XCTAssertEqual(owner.result?.status, .sourceUnavailable, change)
            XCTAssertNil(owner.presentation)
        }
    }

    func testSymlinkAndHardlinkSubstitutionNeverExportUnrelatedFiles() throws {
        for symbolic in [true, false] {
            let source = try payload()
            let destination = try XCTUnwrap(source.fileURLs.first)
            let other = try payload()
            let unrelated = try XCTUnwrap(other.fileURLs.first)
            try FileManager.default.removeItem(at: destination)
            if symbolic {
                try FileManager.default.createSymbolicLink(at: destination, withDestinationURL: unrelated)
            } else {
                try FileManager.default.linkItem(at: unrelated, to: destination)
            }
            let owner = MediaFilesExportController()
            XCTAssertFalse(owner.beginUnrecordedForTesting(source))
            XCTAssertEqual(owner.result?.status, .sourceUnavailable)
            XCTAssertEqual(try Data(contentsOf: unrelated), Data([1, 2, 3]))
        }
    }

    func testChangeBetweenAdmissionAndUIKitHandoffFailsClosed() throws {
        let owner = MediaFilesExportController()
        XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
        let attempt = try XCTUnwrap(owner.presentation)
        let coordinator = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
        XCTAssertNil(coordinator.presentationFailure())
        try Data([9, 9, 9]).write(to: XCTUnwrap(attempt.payload.fileURLs.first), options: .atomic)
        XCTAssertEqual(coordinator.presentationFailure(), .sourceUnavailable)
        coordinator.finish(.sourceUnavailable)
        coordinator.dismantle()
        XCTAssertEqual(owner.result?.status, .sourceUnavailable)
        XCTAssertFalse(owner.isActive)
    }

    func testUIKitControllerPinsSourceBeyondSwiftUIDismantleUntilActualRelease() throws {
        let owner = MediaFilesExportController()
        var picker: MediaFilesExportDocumentPicker?
        var sourceURL: URL?
        weak var weakCoordinator: MediaFilesExportCoordinator?
        try autoreleasepool {
            XCTAssertTrue(owner.beginUnrecordedForTesting(try payload()))
            let attempt = try XCTUnwrap(owner.presentation)
            sourceURL = try XCTUnwrap(attempt.payload.fileURLs.first)
            let coordinator = MediaFilesExportCoordinator(attempt: attempt, owner: owner)
            weakCoordinator = coordinator
            picker = MediaFilesExportDocumentPicker(forExporting: attempt.payload.fileURLs, asCopy: true)
            picker?.exportCoordinator = coordinator
            MediaFilesExportPicker.dismantleUIViewController(try XCTUnwrap(picker), coordinator: coordinator)
        }
        let url = try XCTUnwrap(sourceURL)
        XCTAssertNotNil(weakCoordinator)
        XCTAssertEqual(try Data(contentsOf: url), Data([1, 2, 3]))
        XCTAssertFalse(owner.isActive)
        picker = nil
        XCTAssertNil(weakCoordinator)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    func testReopenedControllerHasNoResultAndDoesNotAutomaticallyRetry() throws {
        let first = MediaFilesExportController()
        XCTAssertTrue(first.beginUnrecordedForTesting(try payload()))
        let attempt = try XCTUnwrap(first.presentation)
        let coordinator = MediaFilesExportCoordinator(attempt: attempt, owner: first)
        coordinator.finish(.reported(count: 1))
        coordinator.dismantle()
        let reopened = MediaFilesExportController()
        XCTAssertNil(reopened.result, "Missing screen-local evidence is not a never-exported receipt")
        XCTAssertNil(reopened.presentation)
        XCTAssertFalse(reopened.isActive)
    }

    private func payload(count: Int = 1, sameFilename: Bool = false) throws -> MediaBatchExportPayload {
        let results: [MediaBatchResult] = try (0..<count).map { index in
            let name = sameFilename ? "SAME.JPG" : "SYNTHETIC-\(index).JPG"
            let file = try MediaBatchOwnedFile.create(filename: name)
            try Data([1, 2, 3]).write(to: file.fileURL)
            var result = MediaBatchResult(item: CameraMediaItem(id: "synthetic-\(index)", name: name, kind: "image"))
            result.status = .ready
            result.ownedFile = file
            return result
        }
        return try XCTUnwrap(MediaBatchExportPayload(results: results))
    }
}
