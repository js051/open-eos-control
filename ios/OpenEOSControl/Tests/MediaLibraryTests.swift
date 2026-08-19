import Foundation
import OpenEOSCore
import XCTest
@testable import OpenEOSControl

final class MediaLibraryTests: XCTestCase {
    func testLatestMediaRequestIsBoundedForQuickReview() {
        XCTAssertEqual(CameraAppState.latestMediaRequestItemCount, 8)
    }

    func testLatestMediaItemUsesNewestKnownCaptureDate() {
        let items = [
            media("old", "IMG_1.JPG", "2026-08-14T10:00:00Z"),
            media("new", "IMG_2.JPG", "2026-08-15T10:00:00Z"),
            media("unknown", "IMG_3.JPG", nil),
        ]

        XCTAssertEqual(selectLatestMediaItem(items)?.id, "new")
    }

    func testLatestMediaItemPreservesCameraOrderWhenDatesAreUnavailable() {
        let items = [
            media("first", "IMG_10.JPG", nil),
            media("second", "IMG_2.JPG", nil),
        ]

        XCTAssertEqual(selectLatestMediaItem(items)?.id, "first")
    }

    func testCaptureReviewSelectionRequiresAChangedID() {
        let items = [media("old", "IMG_1.JPG", "2026-08-15T10:00:00Z")]

        XCTAssertNil(selectLatestMediaItem(afterCaptureFrom: items, previousID: "old"))
        XCTAssertEqual(
            selectLatestMediaItem(afterCaptureFrom: [
                media("old", "IMG_1.JPG", "2026-08-15T10:00:00Z"),
                media("new", "IMG_2.JPG", "2026-08-15T10:01:00Z"),
            ], previousID: "old")?.id,
            "new"
        )
    }

    func testRecentMediaBatchKeepsSixtyItemsAndReportsMore() {
        let items = (1...61).map { media("\($0)", "IMG_\($0).JPG", nil) }

        let batch = mediaLibraryBatch(items, scope: .recent, recentItemCount: 60)

        XCTAssertEqual(batch.items.count, 60)
        XCTAssertEqual(batch.items.first?.id, "1")
        XCTAssertEqual(batch.items.last?.id, "60")
        XCTAssertTrue(batch.hasMore)
    }

    func testFullCardMediaBatchDoesNotTruncate() {
        let items = (1...61).map { media("\($0)", "IMG_\($0).JPG", nil) }

        let batch = mediaLibraryBatch(items, scope: .all, recentItemCount: 60)

        XCTAssertEqual(batch.items, items)
        XCTAssertFalse(batch.hasMore)
    }

    func testCameraSortPreservesTransportOrderExactly() {
        let items = [
            media("ten", "IMG_10.JPG", "2026-08-13T10:00:00Z"),
            media("one", "IMG_1.JPG", "2026-08-14T10:00:00Z"),
        ]

        XCTAssertEqual(mediaItemsForDisplay(items, filter: .all, sort: .camera), items)
    }

    func testNameSortUsesNaturalOrderAndIgnoresCaptureDate() {
        let items = [
            media("ten", "IMG_10.JPG", "2026-08-14T10:00:00Z"),
            media("two", "img_2.jpg", "2026-08-15T10:00:00Z"),
            media("one", "IMG_1.JPG", nil),
        ]

        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .name).map(\.id),
            ["one", "two", "ten"]
        )
    }

    func testDateSortUnderstandsCanonCompactTimeAndKeepsUnknownLast() {
        let items = [
            media("unknown", "IMG_99.JPG", nil),
            media("old", "IMG_1.JPG", "20260813T120000"),
            media("new", "IMG_2.JPG", "2026-08-14 12:00:00"),
        ]

        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .newest).map(\.id),
            ["new", "old", "unknown"]
        )
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .oldest).map(\.id),
            ["old", "new", "unknown"]
        )
    }

    func testDateSortPreservesEqualDatedPairsAndUsesNaturalFallbackWithoutDates() {
        let items = [
            media("unknown-10", "IMG_10.JPG", nil),
            media("unknown-9", "IMG_9.JPG", nil),
            media("raw", "IMG_2.CR3", "2026-08-14T10:00:00Z"),
            media("jpeg", "IMG_2.JPG", "2026-08-14T10:00:00Z"),
        ]

        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .newest).map(\.id),
            ["raw", "jpeg", "unknown-10", "unknown-9"]
        )
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .oldest).map(\.id),
            ["raw", "jpeg", "unknown-9", "unknown-10"]
        )
    }

    func testZoomedMediaImagePanIsBoundedByFittedEdges() {
        let viewport = CGSize(width: 1_000, height: 600)
        let image = CGSize(width: 6_000, height: 4_000)

        let bounds = mediaImagePanBounds(scale: 2, viewport: viewport, image: image)
        XCTAssertEqual(bounds.width, 400, accuracy: 0.001)
        XCTAssertEqual(bounds.height, 300, accuracy: 0.001)
        let clamped = clampMediaImageOffset(
            CGSize(width: 900, height: -800),
            scale: 2,
            viewport: viewport,
            image: image
        )
        XCTAssertEqual(clamped.width, 400, accuracy: 0.001)
        XCTAssertEqual(clamped.height, -300, accuracy: 0.001)
    }

    func testUnzoomedOrInvalidMediaImageCannotPan() {
        XCTAssertEqual(
            mediaImagePanBounds(
                scale: 1,
                viewport: CGSize(width: 1_000, height: 600),
                image: CGSize(width: 6_000, height: 4_000)
            ),
            .zero
        )
        XCTAssertEqual(
            clampMediaImageOffset(
                CGSize(width: 500, height: -500),
                scale: 4,
                viewport: .zero,
                image: CGSize(width: 10, height: 10)
            ),
            .zero
        )
    }

    func testVideoFilterUsesKindOrFilenameExtension() {
        let items = [
            media("photo", "IMG_1.JPG", nil),
            CameraMediaItem(id: "kind", name: "CLIP.bin", kind: "video"),
            media("extension", "CLIP.MP4", nil),
        ]

        XCTAssertEqual(
            Set(mediaItemsForDisplay(items, filter: .videos, sort: .name).map(\.id)),
            Set(["kind", "extension"])
        )
    }

    func testThumbnailCacheRemainsBoundedAndRefreshesExistingEntry() {
        var cache = MediaThumbnailCache(capacity: 3)
        cache.insert(Data([1]), for: "one")
        cache.insert(Data([2]), for: "two")
        cache.insert(Data([3]), for: "three")
        cache.insert(Data([4]), for: "four")

        XCTAssertEqual(cache.values.count, 3)
        XCTAssertNil(cache.values["one"])

        cache.insert(Data([9]), for: "two")
        cache.insert(Data([5]), for: "five")
        XCTAssertEqual(cache.values["two"], Data([9]))
        XCTAssertNil(cache.values["three"])

        XCTAssertTrue(cache.touch("four"))
        cache.insert(Data([6]), for: "six")
        XCTAssertNotNil(cache.values["four"])
        XCTAssertNil(cache.values["two"])
    }

    private func media(_ id: String, _ name: String, _ captureTime: String?) -> CameraMediaItem {
        CameraMediaItem(id: id, name: name, kind: "image", captureTime: captureTime)
    }
}
