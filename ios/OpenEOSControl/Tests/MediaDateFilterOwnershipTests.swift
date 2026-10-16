import Foundation
import OpenEOSCore
import XCTest
@testable import OpenEOSControl

@MainActor
final class MediaDateFilterOwnershipTests: XCTestCase {
    func testDisconnectRetiresAppliedRangeAndRejectsOldDraft() throws {
        try withState { state in
            state.openOfflinePreview()
            let owner = state.mediaLibrarySessionID
            let range = try makeRange()
            XCTAssertTrue(state.setMediaDateRange(range, owner: owner))
            state.requestDisconnect()
            XCTAssertNotEqual(state.mediaLibrarySessionID, owner)
            XCTAssertNil(state.mediaDateRange)
            XCTAssertFalse(state.setMediaDateRange(range, owner: owner))
            XCTAssertNil(state.mediaDateRange)
        }
    }

    func testOldDraftOrClearCannotChangeReplacementRange() throws {
        try withState { state in
            state.openOfflinePreview()
            let oldOwner = state.mediaLibrarySessionID
            let oldRange = try makeRange()
            state.requestDisconnect()
            state.openOfflinePreview()
            let newOwner = state.mediaLibrarySessionID
            let newRange = try makeRange(offset: 172800)
            XCTAssertTrue(state.setMediaDateRange(newRange, owner: newOwner))
            XCTAssertFalse(state.setMediaDateRange(oldRange, owner: oldOwner))
            XCTAssertFalse(state.setMediaDateRange(nil, owner: oldOwner))
            XCTAssertEqual(state.mediaDateRange, newRange)
            XCTAssertEqual(state.mediaLibrarySessionID, newOwner)
        }
    }

    func testRatingFilterRetiresOnDisconnectAndRejectsOldApplyAndClear() throws {
        try withState { state in
            state.openOfflinePreview()
            let oldOwner = state.mediaLibrarySessionID
            XCTAssertTrue(state.setMediaRatingFilter(.five, owner: oldOwner))
            state.requestDisconnect()
            XCTAssertEqual(state.mediaRatingFilter, .all)
            XCTAssertFalse(state.setMediaRatingFilter(.unrated, owner: oldOwner))
            state.openOfflinePreview()
            let newOwner = state.mediaLibrarySessionID
            XCTAssertTrue(state.setMediaRatingFilter(.unknown, owner: newOwner))
            XCTAssertFalse(state.setMediaRatingFilter(.five, owner: oldOwner))
            XCTAssertFalse(state.setMediaRatingFilter(.all, owner: oldOwner))
            XCTAssertEqual(state.mediaRatingFilter, .unknown)
        }
    }

    func testCurrentRatingFilterPreservesDateAndLoadedMedia() throws {
        try withState { state in
            state.openOfflinePreview()
            let owner = state.mediaLibrarySessionID
            let range = try makeRange()
            XCTAssertTrue(state.setMediaDateRange(range, owner: owner))
            let items = state.mediaItems
            XCTAssertFalse(items.isEmpty)
            for filter in MediaRatingFilter.allCases {
                XCTAssertTrue(state.setMediaRatingFilter(filter, owner: owner))
                XCTAssertEqual(state.mediaRatingFilter, filter)
                XCTAssertEqual(state.mediaDateRange, range)
                XCTAssertEqual(state.mediaItems, items)
            }
        }
    }

    func testFolderFilterRetiresOnDisconnectAndRejectsOldApplyAndClear() throws {
        try withState { state in
            state.openOfflinePreview()
            let folder = try XCTUnwrap(state.mediaItems.first?.folder)
            let oldOwner = state.mediaLibrarySessionID
            XCTAssertTrue(state.setMediaFolderFilter(.folder(folder), owner: oldOwner))
            state.requestDisconnect()
            XCTAssertNotEqual(state.mediaLibrarySessionID, oldOwner)
            XCTAssertEqual(state.mediaFolderFilter, .all)
            XCTAssertFalse(state.setMediaFolderFilter(.folder(folder), owner: oldOwner))
            XCTAssertFalse(state.setMediaFolderFilter(.all, owner: oldOwner))
            state.openOfflinePreview()
            let newOwner = state.mediaLibrarySessionID
            XCTAssertTrue(state.setMediaFolderFilter(.unknown, owner: newOwner))
            XCTAssertFalse(state.setMediaFolderFilter(.folder(folder), owner: oldOwner))
            XCTAssertFalse(state.setMediaFolderFilter(.all, owner: oldOwner))
            XCTAssertEqual(state.mediaFolderFilter, .unknown)
            XCTAssertEqual(state.mediaLibrarySessionID, newOwner)
        }
    }

    func testCurrentFolderFilterPreservesDateRatingAndLoadedLibraryWithoutImplicitLoadAll() throws {
        try withState { state in
            state.openOfflinePreview()
            let owner = state.mediaLibrarySessionID
            let range = try makeRange()
            let loadedFolder = try XCTUnwrap(state.mediaItems.first?.folder)
            let missingFolder = try XCTUnwrap(CameraMediaFolder(id: "synthetic:missing", label: loadedFolder.label))
            XCTAssertTrue(state.setMediaDateRange(range, owner: owner))
            XCTAssertTrue(state.setMediaRatingFilter(.atLeast3, owner: owner))
            let items = state.mediaItems
            let scope = state.mediaLibraryScope
            let loadStatus = state.mediaLibraryLoadStatus
            let hasMore = state.mediaLibraryHasMore
            XCTAssertFalse(items.isEmpty)
            for filter in [MediaFolderFilter.folder(loadedFolder), .unknown, .folder(missingFolder), .all] {
                XCTAssertTrue(state.setMediaFolderFilter(filter, owner: owner))
                XCTAssertEqual(state.mediaFolderFilter, filter)
                XCTAssertEqual(state.mediaDateRange, range)
                XCTAssertEqual(state.mediaRatingFilter, .atLeast3)
                XCTAssertEqual(state.mediaItems, items)
                XCTAssertEqual(state.mediaLibraryScope, scope)
                XCTAssertEqual(state.mediaLibraryLoadStatus, loadStatus)
                XCTAssertEqual(state.mediaLibraryHasMore, hasMore)
                XCTAssertFalse(state.mediaLibraryLoading)
            }
            XCTAssertTrue(state.setMediaFolderFilter(.folder(missingFolder), owner: owner))
            XCTAssertTrue(mediaItemsForDisplay(state.mediaItems, filter: .all, sort: .camera,
                folderFilter: state.mediaFolderFilter).isEmpty)
            XCTAssertEqual(state.mediaFolderFilter, .folder(missingFolder))
        }
    }

    func testOfflineRawJpegFolderSurvivesAllMetadataCopiesAndVideoStaysUnknown() async throws {
        let suite = "MediaFolderMetadataTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        defer { state.requestDisconnect() }
        state.openOfflinePreview()
        let raw = try XCTUnwrap(state.mediaItems.first { $0.name.hasSuffix(".CR3") })
        let jpeg = try XCTUnwrap(state.mediaItems.first { $0.name.hasSuffix(".JPG") })
        let video = try XCTUnwrap(state.mediaItems.first { mediaIsVideo($0) })
        let folder = try XCTUnwrap(raw.folder)
        XCTAssertEqual(folder.id, "synthetic:preview-folder")
        XCTAssertEqual(folder.label, "card1/DCIM/100CANON")
        XCTAssertEqual(jpeg.folder, folder)
        XCTAssertNotEqual(raw.id, jpeg.id)
        XCTAssertNil(video.folder)
        XCTAssertTrue(state.setMediaFolderFilter(.folder(folder), owner: state.mediaLibrarySessionID))
        for original in [raw, jpeg] {
            await state.setMediaProtection(original, enabled: !(original.protected ?? false), owner: state.mediaLibrarySessionID)
            var updated = try XCTUnwrap(state.mediaItems.first { $0.id == original.id })
            XCTAssertEqual(updated.folder, folder)
            await state.setMediaRating(updated, rating: 4, owner: state.mediaLibrarySessionID)
            updated = try XCTUnwrap(state.mediaItems.first { $0.id == original.id })
            XCTAssertEqual(updated.folder, folder)
            await state.setMediaRotation(updated, degrees: 180, owner: state.mediaLibrarySessionID)
            updated = try XCTUnwrap(state.mediaItems.first { $0.id == original.id })
            XCTAssertEqual(updated.folder, folder)
            await state.setMediaArchive(updated, enabled: true, owner: state.mediaLibrarySessionID)
            updated = try XCTUnwrap(state.mediaItems.first { $0.id == original.id })
            XCTAssertEqual(updated.folder, folder)
            XCTAssertEqual(updated.protected, !(original.protected ?? false))
            XCTAssertEqual(updated.rating, 4)
            XCTAssertEqual(updated.rotationDegrees, 180)
            XCTAssertEqual(updated.archived, true)
        }
        XCTAssertEqual(state.mediaFolderFilter, .folder(folder))
        XCTAssertEqual(mediaItemsForDisplay(state.mediaItems, filter: .all, sort: .camera,
            folderFilter: state.mediaFolderFilter).map(\.id), [raw.id, jpeg.id])
        XCTAssertEqual(state.mediaItems.first { $0.id == video.id }, video)
    }

    func testViewerZoneRefreshPreservesLiteralRangeSelectionAndSessionOwners() throws {
        try withState { state in
            state.openOfflinePreview()
            let owner = state.mediaLibrarySessionID
            let day = try XCTUnwrap(MediaCaptureDate.dayCarrier("2026-08-14"))
            let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day,
                timeZone: MediaCaptureDate.carrierTimeZone))
            XCTAssertTrue(state.setMediaDateRange(range, owner: owner))
            let selected = try XCTUnwrap(state.mediaItems.first)
            state.toggleMediaSelection(selected)
            let selection = state.selectedMediaItems
            let items = state.mediaItems
            let status = state.mediaLibraryLoadStatus
            let beforeTransfer = state.activeMediaDownloadID
            let sample = CameraMediaItem(id: "synthetic-zone-item", name: "ZONE.JPG", kind: "image",
                                        captureTime: "2026-08-13T16:30:00Z")
            for (identifier, visible) in [("UTC", false), ("Asia/Taipei", true), ("America/Los_Angeles", false)] {
                let zone = try XCTUnwrap(TimeZone(identifier: identifier))
                state.refreshMediaDateDisplayZone(zone)
                XCTAssertEqual(state.mediaDateDisplayZone, zone)
                XCTAssertEqual(state.mediaDateRange, range)
                XCTAssertEqual(state.mediaDateRange?.startDayKey, "2026-08-14")
                XCTAssertEqual(state.mediaDateRange?.endDayKey, "2026-08-14")
                XCTAssertEqual(state.selectedMediaItems, selection)
                XCTAssertEqual(state.mediaItems, items)
                XCTAssertEqual(state.mediaLibrarySessionID, owner)
                XCTAssertEqual(state.mediaLibraryLoadStatus, status)
                XCTAssertEqual(state.activeMediaDownloadID, beforeTransfer)
                let displayed = mediaItemsForDisplay([sample], filter: .all, sort: .newest,
                    dateRange: state.mediaDateRange, displayZone: state.mediaDateDisplayZone)
                XCTAssertEqual(!displayed.isEmpty, visible)
                let parsed = mediaCaptureDate(sample, displayZone: state.mediaDateDisplayZone)
                XCTAssertEqual(parsed.dateKey == "2026-08-14", visible)
                XCTAssertEqual(mediaDateGroups([sample], displayZone: state.mediaDateDisplayZone).first?.dayKey,
                               parsed.dateKey)
            }
            // A draft's carriers remain literal even when its sheet spans a zone change.
            let draft = try XCTUnwrap(MediaDateRange(startDate: range.startDayCarrier, endDate: range.endDayCarrier,
                timeZone: MediaCaptureDate.carrierTimeZone))
            XCTAssertEqual(draft.startDayKey, range.startDayKey)
            XCTAssertEqual(draft.endDayKey, range.endDayKey)
            state.setApplicationActive(true)
            XCTAssertEqual(state.mediaDateDisplayZone, .current)
            XCTAssertEqual(state.mediaDateRange, range)
            XCTAssertEqual(state.selectedMediaItems, selection)
        }
    }

    private func makeRange(offset: TimeInterval = 0) throws -> MediaDateRange {
        let date = Date(timeIntervalSince1970: 1_786_752_000 + offset)
        return try XCTUnwrap(MediaDateRange(startDate: date, endDate: date, timeZone: TimeZone(secondsFromGMT: 0)!))
    }

    private func withState(_ body: (CameraAppState) throws -> Void) rethrows {
        let suite = "MediaDateFilterOwnershipTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        defer { state.requestDisconnect() }
        try body(state)
    }
}
