import Foundation
import OpenEOSCore
import XCTest
@testable import OpenEOSControl

final class MediaLibraryTests: XCTestCase {
    @MainActor
    func testLatestMediaRequestIsBoundedForQuickReview() {
        XCTAssertEqual(CameraAppState.latestMediaRequestItemCount, 8)
    }

    func testLatestMediaItemRetainsCameraOrderRegardlessOfCaptureDate() {
        let items = [
            media("old", "IMG_1.JPG", "2026-08-14T10:00:00Z"),
            media("new", "IMG_2.JPG", "2026-08-15T10:00:00Z"),
            media("unknown", "IMG_3.JPG", nil),
        ]

        XCTAssertEqual(selectLatestMediaItem(items)?.id, "old")
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

        XCTAssertNil(selectLatestMediaItem(afterCaptureFrom: items, previousIDs: ["old"]))
        XCTAssertEqual(
            selectLatestMediaItem(afterCaptureFrom: [
                media("old", "IMG_1.JPG", "2026-08-15T10:00:00Z"),
                media("new", "IMG_2.JPG", "2026-08-15T10:01:00Z"),
            ], previousIDs: ["old"])?.id,
            "new"
        )
    }

    func testCaptureReviewExcludesEveryKnownItemBeforeRetainingCameraOrder() {
        let a = media("A", "A.JPG", "2030-01-01T00:00:00Z")
        let b = media("B", "B.JPG", "2029-01-01T00:00:00Z")
        let new = media("N", "N.JPG", "2020-01-01T00:00:00Z")
        XCTAssertEqual(selectLatestMediaItem(afterCaptureFrom: [a, new, b], previousIDs: ["A", "B"])?.id, "N")
        XCTAssertNil(selectLatestMediaItem(afterCaptureFrom: [b], previousIDs: ["A", "B"]))
    }

    func testCaptureReviewRetainsUnknownDateAndRawPairCameraOrderWithoutClaimingCaptureTime() {
        let raw = media("raw", "PAIR.CR3", nil)
        let jpeg = media("jpeg", "PAIR.JPG", nil)
        XCTAssertEqual(selectLatestMediaItem(afterCaptureFrom: [raw, jpeg], previousIDs: [])?.id, "raw")
        XCTAssertEqual(selectLatestMediaItem(afterCaptureFrom: [jpeg, raw], previousIDs: [])?.id, "jpeg")
        let video = CameraMediaItem(id: "video", name: "OLD.MP4", kind: "video", captureTime: "2030-01-01T00:00:00Z")
        XCTAssertEqual(selectLatestMediaItem(afterCaptureFrom: [video, jpeg], previousIDs: [])?.id, "jpeg")
    }

    func testRecentMediaBatchKeepsSixtyItemsAndReportsMore() {
        let items = (1...61).map { media("\($0)", "IMG_\($0).JPG", nil) }

        let batch = mediaLibraryBatch(items, scope: .recent, recentItemCount: 60)

        XCTAssertEqual(batch.items.count, 60)
        XCTAssertEqual(batch.items.first?.id, "1")
        XCTAssertEqual(batch.items.last?.id, "60")
        XCTAssertTrue(batch.hasMore)
    }

    func testRecordingReviewExcludesKnownVideoAndEveryPhotoBeforeRetainingCameraOrder() {
        let photo = media("photo", "OLD.JPG", "2040-01-01T00:00:00Z")
        let old = CameraMediaItem(id: "old", name: "OLD.MP4", kind: "video", captureTime: "2030-01-01T00:00:00Z")
        let new = CameraMediaItem(id: "new", name: "NEW.MP4", kind: "video", captureTime: "2020-01-01T00:00:00Z")
        XCTAssertNil(selectLatestMediaItem(afterCaptureFrom: [photo, old], previousIDs: ["old"], kind: .video))
        XCTAssertEqual(
            selectLatestMediaItem(afterCaptureFrom: [photo, old, new], previousIDs: ["old"], kind: .video)?.id,
            "new"
        )
        XCTAssertEqual(selectLatestMediaItem(afterCaptureFrom: [new, photo], previousIDs: [])?.id, "photo")
    }

    func testRecordingReviewRecognizesVideoExtensionAndRetainsUnknownDateCameraOrder() {
        let first = CameraMediaItem(id: "first", name: "FIRST.mP4", kind: "unknown")
        let second = CameraMediaItem(id: "second", name: "SECOND.MOV", kind: "video")
        XCTAssertEqual(
            selectLatestMediaItem(afterCaptureFrom: [first, second], previousIDs: [], kind: .video)?.id,
            "first"
        )
        XCTAssertEqual(
            selectLatestMediaItem(afterCaptureFrom: [first, second], previousIDs: ["first"], kind: .video)?.id,
            "second"
        )
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

    func testDateSortPreservesEqualDatedPairsAndUnknownLoadedOrder() {
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
            ["raw", "jpeg", "unknown-10", "unknown-9"]
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

    func testMediaDateRangeIncludesBothWholeDaysAndExcludesNextMidnight() throws {
        let range = try XCTUnwrap(MediaDateRange(
            startDate: instant("2026-08-14T18:00:00Z"),
            endDate: instant("2026-08-16T03:00:00Z"),
            timeZone: zone("UTC")
        ))

        XCTAssertEqual(range.startDate, try instant("2026-08-14T00:00:00Z"))
        XCTAssertEqual(range.endDate, try instant("2026-08-16T00:00:00Z"))
        XCTAssertEqual(range.endExclusive, try instant("2026-08-17T00:00:00Z"))
        XCTAssertFalse(range.contains(captureDate: range.startDate.addingTimeInterval(-0.001)))
        XCTAssertTrue(range.contains(captureDate: range.startDate))
        XCTAssertTrue(range.contains(captureDate: range.endDate))
        XCTAssertTrue(range.contains(captureDate: range.endExclusive.addingTimeInterval(-0.001)))
        XCTAssertFalse(range.contains(captureDate: range.endExclusive))
    }

    func testMediaDateRangeAllowsSameDayWithReversedTimeOfDay() throws {
        let range = try XCTUnwrap(MediaDateRange(
            startDate: instant("2028-02-29T22:00:00Z"),
            endDate: instant("2028-02-29T01:00:00Z"),
            timeZone: zone("UTC")
        ))

        XCTAssertEqual(range.startDate, range.endDate)
        XCTAssertEqual(range.endExclusive, try instant("2028-03-01T00:00:00Z"))
    }

    func testMediaDateRangeRejectsReversedDaysAndNonFiniteDates() throws {
        let first = try instant("2026-08-14T00:00:00Z")
        let last = try instant("2026-08-15T00:00:00Z")
        let timeZone = try zone("UTC")
        XCTAssertNil(MediaDateRange(startDate: last, endDate: first, timeZone: timeZone))
        for value in [Double.nan, Double.infinity, -Double.infinity] {
            let invalid = Date(timeIntervalSinceReferenceDate: value)
            XCTAssertNil(MediaDateRange(startDate: invalid, endDate: last, timeZone: timeZone))
            XCTAssertNil(MediaDateRange(startDate: first, endDate: invalid, timeZone: timeZone))
        }
    }

    func testMediaDateRangeSpringForwardUsesTwentyThreeHourDay() throws {
        let day = try instant("2026-03-08T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(
            startDate: day, endDate: day, timeZone: zone("America/Los_Angeles")
        ))

        XCTAssertEqual(range.startDate, try instant("2026-03-08T08:00:00Z"))
        XCTAssertEqual(range.endExclusive, try instant("2026-03-09T07:00:00Z"))
        XCTAssertEqual(range.endExclusive.timeIntervalSince(range.startDate), 23 * 60 * 60)
        XCTAssertTrue(range.contains(captureDate: try instant("2026-03-09T06:59:59Z")))
        XCTAssertFalse(range.contains(captureDate: try instant("2026-03-09T07:00:00Z")))
    }

    func testMediaDateRangeFallBackIncludesBothRepeatedHoursAndFullLastDay() throws {
        let day = try instant("2026-11-01T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(
            startDate: day, endDate: day, timeZone: zone("America/Los_Angeles")
        ))

        XCTAssertEqual(range.startDate, try instant("2026-11-01T07:00:00Z"))
        XCTAssertEqual(range.endExclusive, try instant("2026-11-02T08:00:00Z"))
        XCTAssertEqual(range.endExclusive.timeIntervalSince(range.startDate), 25 * 60 * 60)
        XCTAssertTrue(range.contains(captureDate: try instant("2026-11-01T08:30:00Z")))
        XCTAssertTrue(range.contains(captureDate: try instant("2026-11-01T09:30:00Z")))
        XCTAssertTrue(range.contains(captureDate: try instant("2026-11-02T07:59:59Z")))
        XCTAssertFalse(range.contains(captureDate: range.endExclusive))
    }

    func testDateFilterUsesCapturedTimeZoneForOffsetTimestamps() throws {
        let items = [
            media("east-morning", "EAST.JPG", "2026-08-14T00:30:00+08:00"),
            media("west-evening", "WEST.JPG", "2026-08-13T23:30:00-07:00"),
            media("midday", "MIDDAY.JPG", "2026-08-14T12:00:00Z"),
            media("west-late", "LATE.JPG", "2026-08-14T23:30:00-07:00"),
        ]
        let day = try instant("2026-08-14T12:00:00Z")
        let cases: [(String, [String])] = [
            ("UTC", ["west-evening", "midday"]),
            ("Asia/Taipei", ["east-morning", "west-evening", "midday"]),
            ("America/Los_Angeles", ["midday", "west-late"]),
        ]
        for (identifier, expectedIDs) in cases {
            let range = try XCTUnwrap(MediaDateRange(
                startDate: day, endDate: day, timeZone: zone(identifier)
            ))
            XCTAssertEqual(range.timeZone, try zone(identifier))
            XCTAssertEqual(
                mediaItemsForDisplay(items, filter: .all, sort: .camera, dateRange: range).map(\.id),
                expectedIDs,
                identifier
            )
        }
    }

    func testDateFilterExcludesUnknownAndMalformedDatesOnlyWhileActive() throws {
        let items = [
            media("missing", "20260814.JPG", nil),
            media("empty", "EMPTY.JPG", ""),
            media("spaces", "SPACES.JPG", "  \n "),
            media("malformed", "BROKEN.JPG", "not-a-date"),
            media("raw", "PAIR.CR3", "2026-08-14T10:00:00Z"),
            media("jpeg", "PAIR.JPG", "2026-08-14T10:00:00Z"),
            media("outside", "OLD.JPG", "2026-08-13T10:00:00Z"),
        ]
        let day = try instant("2026-08-14T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: zone("UTC")))

        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .camera, dateRange: range),
            [items[4], items[5]]
        )
        XCTAssertEqual(mediaItemsForDisplay(items, filter: .all, sort: .camera, dateRange: nil), items)
        XCTAssertEqual(
            mediaDateFilterSummary(items, filter: .all, dateRange: range),
            MediaDateFilterSummary(loadedCount: 7, matchedCount: 2, unknownDateCount: 4)
        )
        XCTAssertEqual(
            mediaDateFilterSummary(items, filter: .all, dateRange: nil),
            MediaDateFilterSummary(loadedCount: 7, matchedCount: 7, unknownDateCount: 4)
        )
    }

    func testDateFilterRetainsExistingTimezoneFreeCaptureDateFormats() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let day = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 8, day: 14, hour: 12)))
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: calendar.timeZone))
        let items = [
            media("compact", "PAIR.CR3", "20260814T120000"),
            media("spaced", "PAIR.JPG", "2026-08-14 12:00:00"),
            media("previous", "OLD.JPG", "20260813T120000"),
        ]

        for item in items.prefix(2) {
            let date = mediaCaptureDate(item, displayZone: calendar.timeZone)
            XCTAssertEqual(date.kind, .floating)
            XCTAssertEqual(date.dateKey, "2026-08-14")
            XCTAssertEqual(date.timeKey, "12:00:00.000")
            XCTAssertNil(date.instantMillis)
        }
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .camera, dateRange: range),
            [items[0], items[1]]
        )
    }

    func testDateFilterIntersectsMediaTypeAndPreservesAllSortOrders() throws {
        let items = [
            media("outside", "IMG_0.JPG", "2026-08-13T10:00:00Z"),
            media("raw", "IMG_2.CR3", "2026-08-14T10:00:00Z"),
            media("video", "IMG_1.MP4", "2026-08-14T12:00:00Z"),
            media("jpeg", "IMG_2.JPG", "2026-08-14T10:00:00Z"),
            media("early", "IMG_10.JPG", "2026-08-14T08:00:00Z"),
            media("unknown", "IMG_3.JPG", nil),
        ]
        let day = try instant("2026-08-14T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: zone("UTC")))
        let cases: [(MediaSort, [String], [String])] = [
            (.camera, ["raw", "video", "jpeg", "early"], ["raw", "jpeg", "early"]),
            (.newest, ["video", "raw", "jpeg", "early"], ["raw", "jpeg", "early"]),
            (.oldest, ["early", "raw", "jpeg", "video"], ["early", "raw", "jpeg"]),
            (.name, ["video", "raw", "jpeg", "early"], ["raw", "jpeg", "early"]),
        ]
        for (sort, allIDs, photoIDs) in cases {
            XCTAssertEqual(
                mediaItemsForDisplay(items, filter: .all, sort: sort, dateRange: range).map(\.id), allIDs
            )
            XCTAssertEqual(
                mediaItemsForDisplay(items, filter: .photos, sort: sort, dateRange: range).map(\.id), photoIDs
            )
            XCTAssertEqual(
                mediaItemsForDisplay(items, filter: .videos, sort: sort, dateRange: range).map(\.id), ["video"]
            )
        }
    }

    func testDateFilterSummaryCountsAllLoadedItemsAndTypeScopedUnknownDates() throws {
        let items = [
            media("photo", "PHOTO.JPG", "2026-08-14T10:00:00Z"),
            media("unknown-photo", "UNKNOWN.CR3", nil),
            CameraMediaItem(id: "video", name: "CLIP.bin", kind: "video", captureTime: "2026-08-14T11:00:00Z"),
            media("unknown-video", "UNKNOWN.MOV", "not-a-date"),
            media("outside-video", "OLD.MP4", "2026-08-13T10:00:00Z"),
        ]
        let day = try instant("2026-08-14T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: zone("UTC")))

        XCTAssertEqual(
            mediaDateFilterSummary(items, filter: .all, dateRange: range),
            MediaDateFilterSummary(loadedCount: 5, matchedCount: 2, unknownDateCount: 2)
        )
        XCTAssertEqual(
            mediaDateFilterSummary(items, filter: .photos, dateRange: range),
            MediaDateFilterSummary(loadedCount: 5, matchedCount: 1, unknownDateCount: 1)
        )
        XCTAssertEqual(
            mediaDateFilterSummary(items, filter: .videos, dateRange: range),
            MediaDateFilterSummary(loadedCount: 5, matchedCount: 1, unknownDateCount: 1)
        )
        XCTAssertEqual(
            mediaDateFilterSummary(items, filter: .videos, dateRange: nil),
            MediaDateFilterSummary(loadedCount: 5, matchedCount: 3, unknownDateCount: 1)
        )
        for filter in MediaFilter.allCases {
            for sort in MediaSort.allCases {
                XCTAssertEqual(
                    mediaDateFilterSummary(items, filter: filter, dateRange: range).matchedCount,
                    mediaItemsForDisplay(items, filter: filter, sort: sort, dateRange: range).count
                )
            }
        }
    }

    func testDateFilterHandlesEmptyAndZeroMatchLoadedLists() throws {
        let day = try instant("2026-08-14T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: zone("UTC")))
        XCTAssertEqual(
            mediaDateFilterSummary([], filter: .all, dateRange: range),
            MediaDateFilterSummary(loadedCount: 0, matchedCount: 0, unknownDateCount: 0)
        )
        let items = [media("outside", "OLD.JPG", "2026-08-13T10:00:00Z")]
        XCTAssertTrue(mediaItemsForDisplay(items, filter: .all, sort: .camera, dateRange: range).isEmpty)
        XCTAssertEqual(
            mediaDateFilterSummary(items, filter: .all, dateRange: range),
            MediaDateFilterSummary(loadedCount: 1, matchedCount: 0, unknownDateCount: 0)
        )
    }

    func testRatingNormalizationKeepsOnlyNumericZeroThroughFive() {
        let cases: [(Int?, Int?)] = [
            (nil, nil), (Int.min, nil), (-1, nil),
            (0, 0), (1, 1), (2, 2), (3, 3), (4, 4), (5, 5),
            (6, nil), (Int.max, nil),
        ]
        for (value, expected) in cases {
            let item = media("rating", "FIVE_STARS.JPG", nil, rating: value)
            XCTAssertEqual(mediaNormalizedRating(item), expected, "Raw rating: \(String(describing: value))")
            XCTAssertEqual(item.rating, value, "Normalization must not rewrite camera metadata")
        }
    }

    func testRatingFiltersDistinguishKnownUnratedThresholdsAndUnknownValues() {
        let items = [
            media("missing", "FIVE_STARS.JPG", nil),
            media("negative", "NEGATIVE.JPG", nil, rating: -1),
            media("zero", "ZERO.JPG", nil, rating: 0),
            media("one", "ONE.JPG", nil, rating: 1),
            media("two", "TWO.JPG", nil, rating: 2),
            media("three", "THREE.JPG", nil, rating: 3),
            media("four", "FOUR.JPG", nil, rating: 4),
            media("five", "FIVE.JPG", nil, rating: 5),
            media("too-high", "HIGH.JPG", nil, rating: 6),
        ]
        let cases: [(MediaRatingFilter, [String])] = [
            (.all, items.map(\.id)),
            (.unrated, ["zero"]),
            (.atLeast1, ["one", "two", "three", "four", "five"]),
            (.atLeast2, ["two", "three", "four", "five"]),
            (.atLeast3, ["three", "four", "five"]),
            (.atLeast4, ["four", "five"]),
            (.five, ["five"]),
            (.unknown, ["missing", "negative", "too-high"]),
        ]
        for (ratingFilter, expectedIDs) in cases {
            XCTAssertEqual(
                mediaItemsForDisplay(items, filter: .all, sort: .camera, ratingFilter: ratingFilter).map(\.id),
                expectedIDs,
                ratingFilter.rawValue
            )
        }
        XCTAssertEqual(mediaItemsForDisplay(items, filter: .all, sort: .camera), items)
    }

    func testRatingSortsKeepUnknownLastAndRetainEqualRatingCameraOrder() {
        let items = [
            media("missing", "IMG_1.JPG", "2030-01-01T00:00:00Z"),
            media("three-z", "Z.JPG", "2020-01-01T00:00:00Z", rating: 3),
            media("too-high", "IMG_2.JPG", nil, rating: Int.max),
            media("five", "FIVE.JPG", nil, rating: 5),
            media("zero", "ZERO.JPG", nil, rating: 0),
            media("three-a", "A.JPG", "2030-01-01T00:00:00Z", rating: 3),
            media("negative", "IMG_0.JPG", nil, rating: Int.min),
            media("one", "ONE.JPG", nil, rating: 1),
        ]

        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .ratingHigh).map(\.id),
            ["five", "three-z", "three-a", "one", "zero", "missing", "too-high", "negative"]
        )
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .ratingLow).map(\.id),
            ["zero", "one", "three-z", "three-a", "five", "missing", "too-high", "negative"]
        )
    }

    func testRatingSortPreservesExactItemsAndSeparateRawJpegIdentities() {
        let raw = CameraMediaItem(
            id: "PAIR", name: "PAIR.CR3", kind: "image", sizeBytes: 1_234,
            captureTime: "2026-08-14T10:00:00Z", previewAvailable: true,
            protected: true, rating: 4, rotationDegrees: 90, archived: false,
            contentType: "image/x-canon-cr3", widthPixels: 6_000, heightPixels: 4_000
        )
        let jpeg = CameraMediaItem(
            id: "pair", name: "PAIR.JPG", kind: "image", sizeBytes: 567,
            captureTime: "2026-08-14T10:00:00Z", previewAvailable: true,
            protected: false, rating: 4, rotationDegrees: 180, archived: true,
            contentType: "image/jpeg", widthPixels: 3_000, heightPixels: 2_000
        )
        let invalid = media("invalid", "PAIR.JPG", nil, rating: 6)
        let items = [invalid, raw, jpeg]

        for sort in [MediaSort.ratingHigh, .ratingLow] {
            XCTAssertEqual(mediaItemsForDisplay(items, filter: .all, sort: sort), [raw, jpeg, invalid])
            XCTAssertEqual(
                mediaItemsForDisplay(items, filter: .all, sort: sort, ratingFilter: .atLeast4),
                [raw, jpeg]
            )
            XCTAssertEqual(
                mediaItemsForDisplay([jpeg, raw], filter: .all, sort: sort),
                [jpeg, raw]
            )
        }
        XCTAssertEqual(items, [invalid, raw, jpeg])
    }

    func testRatingSortKeepsAllUnknownOrAllUnratedListsInLoadedOrder() {
        let unknown = [
            media("z", "Z.JPG", nil, rating: 6),
            media("a", "A.JPG", nil),
            media("b", "B.JPG", nil, rating: -1),
        ]
        let unrated = [
            media("z", "Z.JPG", nil, rating: 0),
            media("a", "A.JPG", nil, rating: 0),
        ]
        for sort in [MediaSort.ratingHigh, .ratingLow] {
            XCTAssertEqual(mediaItemsForDisplay(unknown, filter: .all, sort: sort), unknown)
            XCTAssertEqual(mediaItemsForDisplay(unrated, filter: .all, sort: sort), unrated)
            XCTAssertTrue(mediaItemsForDisplay(unknown, filter: .all, sort: sort, ratingFilter: .unrated).isEmpty)
            XCTAssertTrue(mediaItemsForDisplay(unrated, filter: .all, sort: sort, ratingFilter: .unknown).isEmpty)
        }
    }

    func testRatingFilterIntersectsLoadedMediaTypeAndWholeDateRange() throws {
        let day = try instant("2026-08-14T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: zone("UTC")))
        let items = [
            media("outside", "OUTSIDE.JPG", "2026-08-13T23:59:59Z", rating: 5),
            media("unknown-date", "UNKNOWN.JPG", nil, rating: 5),
            media("raw", "PAIR.CR3", "2026-08-14T00:00:00Z", rating: 4),
            media("video", "CLIP.MP4", "2026-08-14T12:00:00Z", rating: 5),
            media("low", "LOW.JPG", "2026-08-14T12:00:00Z", rating: 3),
            media("jpeg", "PAIR.JPG", "2026-08-14T23:59:59Z", rating: 4),
            media("unknown-rating", "UNKNOWN.JPG", "2026-08-14T12:00:00Z"),
            media("next-day", "NEXT.JPG", "2026-08-15T00:00:00Z", rating: 5),
        ]

        for sort in [MediaSort.camera, .ratingHigh, .ratingLow] {
            XCTAssertEqual(
                mediaItemsForDisplay(items, filter: .photos, sort: sort, dateRange: range, ratingFilter: .atLeast4),
                [items[2], items[5]]
            )
            XCTAssertEqual(
                mediaItemsForDisplay(items, filter: .videos, sort: sort, dateRange: range, ratingFilter: .five),
                [items[3]]
            )
        }
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .ratingHigh, dateRange: range, ratingFilter: .atLeast4),
            [items[3], items[2], items[5]]
        )
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .ratingLow, dateRange: range, ratingFilter: .atLeast4),
            [items[2], items[5], items[3]]
        )
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .camera, dateRange: range, ratingFilter: .unknown),
            [items[6]]
        )
    }

    func testRatingSummaryCountsUnknownBeforeRatingFilterWithinTypeAndDate() throws {
        let day = try instant("2026-08-14T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: zone("UTC")))
        let items = [
            media("rated", "RATED.JPG", "2026-08-14T12:00:00Z", rating: 5),
            media("zero", "ZERO.JPG", "2026-08-14T12:00:00Z", rating: 0),
            media("missing", "MISSING.JPG", "2026-08-14T12:00:00Z"),
            media("invalid", "INVALID.JPG", "2026-08-14T12:00:00Z", rating: 6),
            media("unknown-date", "UNKNOWN.JPG", nil),
            media("outside", "OUTSIDE.JPG", "2026-08-13T12:00:00Z"),
            media("video", "CLIP.MOV", "2026-08-14T12:00:00Z"),
        ]

        XCTAssertEqual(
            mediaRatingFilterSummary(items, filter: .photos, dateRange: range, ratingFilter: .five),
            MediaRatingFilterSummary(loadedCount: 7, matchedCount: 1, unknownRatingCount: 2)
        )
        XCTAssertEqual(
            mediaRatingFilterSummary(items, filter: .photos, dateRange: range, ratingFilter: .unknown),
            MediaRatingFilterSummary(loadedCount: 7, matchedCount: 2, unknownRatingCount: 2)
        )
        XCTAssertEqual(
            mediaRatingFilterSummary(items, filter: .photos, dateRange: range, ratingFilter: .unrated),
            MediaRatingFilterSummary(loadedCount: 7, matchedCount: 1, unknownRatingCount: 2)
        )
        XCTAssertEqual(
            mediaRatingFilterSummary(items, filter: .photos),
            MediaRatingFilterSummary(loadedCount: 7, matchedCount: 6, unknownRatingCount: 4)
        )
        XCTAssertEqual(
            mediaRatingFilterSummary(items, filter: .videos, dateRange: range),
            MediaRatingFilterSummary(loadedCount: 7, matchedCount: 1, unknownRatingCount: 1)
        )
    }

    func testDateSummaryIncludesRatingConditionAndScopesUnknownDatesToTypeAndRating() throws {
        let day = try instant("2026-08-14T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: zone("UTC")))
        let items = [
            media("in-range", "IN.JPG", "2026-08-14T12:00:00Z", rating: 5),
            media("outside", "OUT.JPG", "2026-08-13T12:00:00Z", rating: 5),
            media("unknown-five", "UNKNOWN.JPG", nil, rating: 5),
            media("unknown-zero", "ZERO.JPG", "not-a-date", rating: 0),
            media("unknown-both", "MISSING.JPG", nil),
            media("video", "CLIP.MP4", nil, rating: 5),
        ]

        XCTAssertEqual(
            mediaDateFilterSummary(items, filter: .photos, dateRange: range, ratingFilter: .five),
            MediaDateFilterSummary(loadedCount: 6, matchedCount: 1, unknownDateCount: 1)
        )
        XCTAssertEqual(
            mediaDateFilterSummary(items, filter: .photos, dateRange: nil, ratingFilter: .five),
            MediaDateFilterSummary(loadedCount: 6, matchedCount: 3, unknownDateCount: 1)
        )
        XCTAssertEqual(
            mediaDateFilterSummary(items, filter: .photos, dateRange: range, ratingFilter: .unknown),
            MediaDateFilterSummary(loadedCount: 6, matchedCount: 0, unknownDateCount: 1)
        )
    }

    func testRatingAndDateSummariesMatchEveryDisplayedFilterAndSortIntersection() throws {
        let day = try instant("2026-08-14T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: zone("UTC")))
        let items = [
            media("five", "FIVE.JPG", "2026-08-14T12:00:00Z", rating: 5),
            media("zero", "ZERO.JPG", "2026-08-14T12:00:00Z", rating: 0),
            media("missing", "MISSING.JPG", "2026-08-14T12:00:00Z"),
            media("invalid", "INVALID.JPG", "2026-08-14T12:00:00Z", rating: -1),
            media("outside", "OUTSIDE.JPG", "2026-08-13T12:00:00Z", rating: 3),
            media("unknown-date", "UNKNOWN.JPG", nil, rating: 4),
            media("video", "CLIP.MP4", "2026-08-14T12:00:00Z", rating: 2),
            media("unknown-video", "UNKNOWN.MOV", nil),
        ]

        for dateRange in [nil, range] as [MediaDateRange?] {
            for filter in MediaFilter.allCases {
                for ratingFilter in MediaRatingFilter.allCases {
                    let dateSummary = mediaDateFilterSummary(
                        items, filter: filter, dateRange: dateRange, ratingFilter: ratingFilter
                    )
                    let ratingSummary = mediaRatingFilterSummary(
                        items, filter: filter, dateRange: dateRange, ratingFilter: ratingFilter
                    )
                    XCTAssertEqual(dateSummary.loadedCount, items.count)
                    XCTAssertEqual(ratingSummary.loadedCount, items.count)
                    for sort in MediaSort.allCases {
                        let displayed = mediaItemsForDisplay(
                            items, filter: filter, sort: sort, dateRange: dateRange, ratingFilter: ratingFilter
                        )
                        XCTAssertEqual(dateSummary.matchedCount, displayed.count)
                        XCTAssertEqual(ratingSummary.matchedCount, displayed.count)
                    }
                }
            }
        }
    }

    func testRatingFilterHandlesEmptyAndNoMatchLoadedLists() {
        let items = [media("five", "FIVE.JPG", nil, rating: 5)]
        for ratingFilter in MediaRatingFilter.allCases {
            XCTAssertEqual(
                mediaRatingFilterSummary([], filter: .all, ratingFilter: ratingFilter),
                MediaRatingFilterSummary(loadedCount: 0, matchedCount: 0, unknownRatingCount: 0)
            )
            XCTAssertTrue(mediaItemsForDisplay([], filter: .all, sort: .ratingHigh, ratingFilter: ratingFilter).isEmpty)
        }
        XCTAssertTrue(mediaItemsForDisplay(items, filter: .all, sort: .ratingLow, ratingFilter: .unknown).isEmpty)
        XCTAssertEqual(
            mediaRatingFilterSummary(items, filter: .all, ratingFilter: .unknown),
            MediaRatingFilterSummary(loadedCount: 1, matchedCount: 0, unknownRatingCount: 0)
        )
    }

    func testFolderOptionsGroupExactIDsAndResolveLabelConflictsDeterministically() throws {
        let first = try folder("card1/100CANON", "100CANON")
        let renamed = try folder("card1/100CANON", "Renamed")
        let second = try folder("card2/100CANON", "100CANON")
        let differentCase = try folder("CARD1/100CANON", "100CANON")
        let items = [
            media("raw", "PAIR.CR3", nil, folder: renamed),
            media("jpeg", "PAIR.JPG", nil, folder: first),
            media("second", "PAIR.JPG", nil, folder: second),
            media("case", "PAIR.JPG", nil, folder: differentCase),
            media("unknown", "card1/100CANON/IMAGE.JPG", nil),
        ]
        let expected = [
            MediaFolderOption(folder: differentCase, itemCount: 1),
            MediaFolderOption(folder: first, itemCount: 2),
            MediaFolderOption(folder: second, itemCount: 1),
        ]
        XCTAssertEqual(mediaFolderOptions(items), expected)
        XCTAssertEqual(mediaFolderOptions(Array(items.reversed())), expected)
        XCTAssertEqual(MediaFolderFilter.folder(first), .folder(renamed))
        XCTAssertNotEqual(MediaFolderFilter.folder(first), .folder(second))
        XCTAssertNotEqual(MediaFolderFilter.folder(first), .folder(differentCase))
        XCTAssertNotEqual(MediaFolderFilter.all, .unknown)
        XCTAssertNotEqual(MediaFolderFilter.folder(first), .unknown)
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .camera, folderFilter: .folder(first)),
            [items[0], items[1]],
            "RAW and JPEG keep separate file identities in their exact folder"
        )
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .camera, folderFilter: .folder(second)),
            [items[2]],
            "Identical display labels cannot merge different folders"
        )
    }

    func testFolderUnknownDoesNotGuessFromFilenameIDOrKindAndAllKeepsLoadedOrder() throws {
        let known = try folder("opaque-known", "Known")
        let items = [
            media("card1/DCIM/100CANON/IMAGE.JPG", "100CANON/IMAGE.JPG", nil),
            media("ptp:storage/parent/file", "DIRECTORY.CR3", nil),
            media("known", "CLIP.MP4", nil, folder: known),
        ]
        XCTAssertEqual(mediaItemsForDisplay(items, filter: .all, sort: .camera), items)
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .camera, folderFilter: .all), items
        )
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .camera, folderFilter: .unknown),
            Array(items.prefix(2))
        )
        XCTAssertEqual(mediaFolderOptions(items), [MediaFolderOption(folder: known, itemCount: 1)])
        XCTAssertEqual(
            mediaFolderFilterSummary(items, filter: .all, folderFilter: .folder(known)),
            MediaFolderFilterSummary(loadedCount: 3, matchedCount: 1, unknownFolderCount: 2)
        )
    }

    func testCanonicallyEquivalentFolderIDsKeepDistinctWireIdentities() throws {
        let composed = try folder("caf\u{00E9}", "Shared label")
        let decomposed = try folder("cafe\u{0301}", "Shared label")
        XCTAssertEqual(composed.id, decomposed.id, "The fixture must exercise Swift canonical String equality")
        XCTAssertNotEqual(mediaFolderIdentity(composed), mediaFolderIdentity(decomposed))
        XCTAssertNotEqual(MediaFolderFilter.folder(composed), .folder(decomposed))
        let items = [
            media("composed-raw", "PAIR.CR3", nil, folder: composed),
            media("decomposed", "PAIR.JPG", nil, folder: decomposed),
            media("composed-jpeg", "PAIR.JPG", nil, folder: composed),
        ]
        let expected = [
            MediaFolderOption(folder: decomposed, itemCount: 1),
            MediaFolderOption(folder: composed, itemCount: 2),
        ]
        XCTAssertEqual(mediaFolderOptions(items), expected)
        XCTAssertEqual(mediaFolderOptions(Array(items.reversed())), expected)
        XCTAssertEqual(Set(mediaFolderOptions(items).map(\.id)).count, 2)
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .camera, folderFilter: .folder(composed)),
            [items[0], items[2]]
        )
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .all, sort: .camera, folderFilter: .folder(decomposed)),
            [items[1]]
        )
    }

    func testCanonicalLabelConflictsChooseStableBytesWithinOneFolderIdentity() throws {
        let composedLabel = try folder("same-id", "caf\u{00E9}")
        let decomposedLabel = try folder("same-id", "cafe\u{0301}")
        let items = [
            media("first", "FIRST.JPG", nil, folder: composedLabel),
            media("second", "SECOND.JPG", nil, folder: decomposedLabel),
        ]
        for loaded in [items, Array(items.reversed())] {
            let option = try XCTUnwrap(mediaFolderOptions(loaded).first)
            XCTAssertEqual(option.itemCount, 2)
            XCTAssertEqual(Data(option.folder.label.utf8), Data(decomposedLabel.label.utf8))
        }
    }

    func testMissingSelectedFolderStaysZeroInsteadOfFallingBackToAll() throws {
        let loaded = try folder("loaded", "Same label")
        let missing = try folder("not-loaded", "Same label")
        let items = [media("loaded", "FILE.JPG", nil, folder: loaded), media("unknown", "UNKNOWN.JPG", nil)]
        for sort in MediaSort.allCases {
            XCTAssertTrue(mediaItemsForDisplay(items, filter: .all, sort: sort, folderFilter: .folder(missing)).isEmpty)
        }
        XCTAssertEqual(
            mediaFolderFilterSummary(items, filter: .all, folderFilter: .folder(missing)),
            MediaFolderFilterSummary(loadedCount: 2, matchedCount: 0, unknownFolderCount: 1)
        )
        for folderFilter in [MediaFolderFilter.all, .unknown, .folder(missing)] {
            XCTAssertEqual(
                mediaFolderFilterSummary([], filter: .all, folderFilter: folderFilter),
                MediaFolderFilterSummary(loadedCount: 0, matchedCount: 0, unknownFolderCount: 0)
            )
            XCTAssertTrue(mediaItemsForDisplay([], filter: .all, sort: .camera, folderFilter: folderFilter).isEmpty)
        }
        XCTAssertTrue(mediaFolderOptions([]).isEmpty)
    }

    func testFolderSummaryUnknownCountUsesTypeDateAndRatingBeforeFolderFilter() throws {
        let known = try folder("known", "Known")
        let day = try instant("2026-08-14T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: zone("UTC")))
        let items = [
            media("known", "KNOWN.JPG", "2026-08-14T12:00:00Z", rating: 5, folder: known),
            media("unknown-folder", "UNKNOWN.JPG", "2026-08-14T12:00:00Z", rating: 5),
            media("unknown-folder-low", "LOW.JPG", "2026-08-14T12:00:00Z", rating: 3),
            media("unknown-folder-date", "DATE.JPG", nil, rating: 5),
            media("unknown-folder-old", "OLD.JPG", "2026-08-13T12:00:00Z", rating: 5),
            media("unknown-folder-video", "CLIP.MP4", "2026-08-14T12:00:00Z", rating: 5),
            media("known-unknown-date", "DATE.JPG", nil, rating: 5, folder: known),
            media("known-unknown-rating", "RATING.JPG", "2026-08-14T12:00:00Z", folder: known),
            media("known-invalid-rating", "RATING.JPG", "2026-08-14T12:00:00Z", rating: 9, folder: known),
        ]
        for (folderFilter, matched) in [(MediaFolderFilter.all, 2), (.folder(known), 1), (.unknown, 1)] {
            XCTAssertEqual(
                mediaFolderFilterSummary(items, filter: .photos, dateRange: range,
                    ratingFilter: .five, folderFilter: folderFilter),
                MediaFolderFilterSummary(loadedCount: 9, matchedCount: matched, unknownFolderCount: 1)
            )
        }
        XCTAssertEqual(
            mediaDateFilterSummary(items, filter: .photos, dateRange: range,
                ratingFilter: .five, folderFilter: .folder(known)),
            MediaDateFilterSummary(loadedCount: 9, matchedCount: 1, unknownDateCount: 1)
        )
        XCTAssertEqual(
            mediaRatingFilterSummary(items, filter: .photos, dateRange: range,
                ratingFilter: .five, folderFilter: .folder(known)),
            MediaRatingFilterSummary(loadedCount: 9, matchedCount: 1, unknownRatingCount: 2)
        )
        XCTAssertEqual(
            mediaItemsForDisplay(items, filter: .photos, sort: .camera, dateRange: range,
                ratingFilter: .five, folderFilter: .folder(known)),
            [items[0]]
        )
    }

    func testFolderIntersectionsAgreeAcrossEveryFilterSortAndLoadedSummary() throws {
        let first = try folder("card1", "Same")
        let second = try folder("card2", "Same")
        let missing = try folder("missing", "Same")
        let day = try instant("2026-08-14T12:00:00Z")
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: zone("UTC")))
        let items = [
            media("raw", "PAIR.CR3", "2026-08-14T12:00:00Z", rating: 4, folder: first),
            media("jpeg", "PAIR.JPG", "2026-08-14T12:00:00Z", rating: 4, folder: first),
            media("rated", "FIVE.JPG", "2026-08-14T13:00:00Z", rating: 5, folder: second),
            media("unrated", "ZERO.JPG", "2026-08-14T12:00:00Z", rating: 0),
            media("missing-rating", "RATING.JPG", "2026-08-14T12:00:00Z", folder: first),
            media("outside", "OLD.JPG", "2026-08-13T12:00:00Z", rating: 3, folder: second),
            media("unknown-date", "DATE.JPG", nil, rating: 4, folder: first),
            media("video", "CLIP.MP4", "2026-08-14T12:00:00Z", rating: 2, folder: first),
            media("unknown-video", "UNKNOWN.MOV", nil),
        ]
        for dateRange in [nil, range] as [MediaDateRange?] {
            for filter in MediaFilter.allCases {
                for ratingFilter in MediaRatingFilter.allCases {
                    for folderFilter in [MediaFolderFilter.all, .unknown, .folder(first), .folder(second), .folder(missing)] {
                        let dateSummary = mediaDateFilterSummary(items, filter: filter, dateRange: dateRange,
                            ratingFilter: ratingFilter, folderFilter: folderFilter)
                        let ratingSummary = mediaRatingFilterSummary(items, filter: filter, dateRange: dateRange,
                            ratingFilter: ratingFilter, folderFilter: folderFilter)
                        let folderSummary = mediaFolderFilterSummary(items, filter: filter, dateRange: dateRange,
                            ratingFilter: ratingFilter, folderFilter: folderFilter)
                        XCTAssertEqual(dateSummary.loadedCount, items.count)
                        XCTAssertEqual(ratingSummary.loadedCount, items.count)
                        XCTAssertEqual(folderSummary.loadedCount, items.count)
                        for sort in MediaSort.allCases {
                            let displayed = mediaItemsForDisplay(items, filter: filter, sort: sort, dateRange: dateRange,
                                ratingFilter: ratingFilter, folderFilter: folderFilter)
                            XCTAssertEqual(dateSummary.matchedCount, displayed.count)
                            XCTAssertEqual(ratingSummary.matchedCount, displayed.count)
                            XCTAssertEqual(folderSummary.matchedCount, displayed.count)
                        }
                    }
                }
            }
        }
        // Equal-rating pairs preserve the camera's order after all four filters.
        for sort in [MediaSort.camera, .ratingHigh, .ratingLow, .newest, .oldest] {
            XCTAssertEqual(
                mediaItemsForDisplay(items, filter: .photos, sort: sort, dateRange: range,
                    ratingFilter: .atLeast4, folderFilter: .folder(first)),
                [items[0], items[1]]
            )
        }
    }

    func testFortyLongUnicodeFolderLabelsRemainKnownAndKeepDistinctIDs() throws {
        let sharedLabel = "card/DCIM/" + String(repeating: "資料夾📷e\u{301}", count: 32)
        let folders = try (0..<40).map { try folder("synthetic-long-folder-\($0)", sharedLabel) }
        let items = folders.enumerated().map { index, folder in
            media("image-\(index)", "IMAGE.JPG", nil, folder: folder)
        }
        let options = mediaFolderOptions(items)
        XCTAssertEqual(options.count, 40)
        XCTAssertEqual(Set(options.map(\.id)), Set(folders.map { mediaFolderIdentity($0) }))
        XCTAssertTrue(options.allSatisfy { $0.folder.label == sharedLabel && $0.itemCount == 1 })
        XCTAssertEqual(mediaFolderOptions(Array(items.reversed())), options)
        XCTAssertEqual(
            mediaFolderFilterSummary(items, filter: .all),
            MediaFolderFilterSummary(loadedCount: 40, matchedCount: 40, unknownFolderCount: 0)
        )
        for (index, folder) in folders.enumerated() {
            XCTAssertEqual(
                mediaItemsForDisplay(items, filter: .all, sort: .camera, folderFilter: .folder(folder)),
                [items[index]]
            )
        }
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

    private func media(
        _ id: String, _ name: String, _ captureTime: String?, rating: Int? = nil, folder: CameraMediaFolder? = nil
    ) -> CameraMediaItem {
        CameraMediaItem(id: id, name: name, kind: "image", captureTime: captureTime, rating: rating, folder: folder)
    }

    private func folder(_ id: String, _ label: String) throws -> CameraMediaFolder {
        try XCTUnwrap(CameraMediaFolder(id: id, label: label))
    }

    private func instant(_ value: String) throws -> Date {
        try XCTUnwrap(ISO8601DateFormatter().date(from: value))
    }

    private func zone(_ identifier: String) throws -> TimeZone {
        try XCTUnwrap(TimeZone(identifier: identifier))
    }
}
