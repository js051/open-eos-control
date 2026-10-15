import CryptoKit
import Foundation
import OpenEOSCore
import XCTest
@testable import OpenEOSControl

/// Executes the production Swift logic against the immutable cross-platform file.
/// Native execution remains a separate Xcode/simulator gate, not a source-check claim.
final class MediaDateReviewContractTests: XCTestCase {
    private struct Display: Decodable {
        let dateKey: String?
        let timeKey: String?
    }
    private struct Vector: Decodable {
        let id: String
        let raw: String?
        let kind: String
        let instantMillis: Int64?
        let display: [String: Display]
    }
    private struct Item: Decodable {
        let id: String
        let name: String
        let captureTime: String?
        let kind: String
        var media: CameraMediaItem { CameraMediaItem(id: id, name: name, kind: kind, captureTime: captureTime) }
    }
    private struct SortCase: Decodable {
        let zone: String
        let sort: String
        let expectedIds: [String]
    }
    private struct ReviewCase: Decodable {
        let kind: String
        let previousIds: [String]
        let expectedId: String?
    }
    private struct Contract: Decodable {
        let schemaVersion: Int
        let instantResolution: String
        let calendar: String
        let kinds: [String]
        let displayZones: [String]
        let dateSortPrecisionOrder: [String]
        let unknownAndTies: String
        let vectors: [Vector]
        let sortItems: [Item]
        let sortCases: [SortCase]
        let reviewItems: [Item]
        let reviewCases: [ReviewCase]
    }

    private func contract() throws -> Contract {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "date-review-vectors", withExtension: "json"))
        let size = try XCTUnwrap(url.resourceValues(forKeys: [.fileSizeKey]).fileSize)
        XCTAssertLessThanOrEqual(size, 32_768)
        guard size > 0 && size <= 32_768 else { throw URLError(.dataLengthExceedsMaximum) }
        let data = try Data(contentsOf: url)
        XCTAssertEqual(data.count, 26_467)
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(),
                       "8eb57e04446478fe523c1e8cd08c0cc57b524d9fcb7f63845fc28d6d02f57e9a")
        let value = try JSONDecoder().decode(Contract.self, from: data)
        XCTAssertEqual(value.schemaVersion, 1)
        XCTAssertEqual(value.instantResolution, "milliseconds-truncated")
        XCTAssertEqual(value.calendar, "proleptic-gregorian-ad-1-9999")
        XCTAssertEqual(value.kinds, ["instant", "floating", "date-only", "unknown"])
        XCTAssertEqual(value.displayZones, ["UTC", "Asia/Taipei", "America/Los_Angeles"])
        XCTAssertEqual(value.dateSortPrecisionOrder, ["instant", "floating", "date-only"])
        XCTAssertEqual(value.unknownAndTies, "loaded-order")
        XCTAssertEqual(value.vectors.count, 48)
        XCTAssertEqual(Set(value.vectors.map(\.id)).count, 48)
        XCTAssertTrue(value.vectors.allSatisfy { ($0.raw?.utf8.count ?? 0) <= 128 })
        XCTAssertEqual(value.sortItems.count, 8)
        XCTAssertEqual(value.sortCases.count, 9)
        XCTAssertEqual(value.reviewItems.count, 4)
        XCTAssertEqual(value.reviewCases.count, 4)
        return value
    }

    func testAllFixedParseVectorsAndPrecisionAwareCardDetailInEveryZone() throws {
        let fixture = try contract()
        var count = 0
        for zoneID in fixture.displayZones {
            let zone = try XCTUnwrap(TimeZone(identifier: zoneID))
            for vector in fixture.vectors {
                let expected = try XCTUnwrap(vector.display[zoneID])
                let item = CameraMediaItem(id: vector.id, name: "SYNTHETIC.JPG", kind: "image", captureTime: vector.raw)
                let actual = mediaCaptureDate(item, displayZone: zone)
                let context = "\(vector.id) in \(zoneID)"
                XCTAssertEqual(actual.kind.rawValue, vector.kind, context)
                XCTAssertEqual(actual.instantMillis, vector.instantMillis, context)
                XCTAssertEqual(actual.dateKey, expected.dateKey, context)
                XCTAssertEqual(actual.timeKey, expected.timeKey, context)
                XCTAssertEqual(mediaDateGroups([item], displayZone: zone).first?.dayKey, expected.dateKey, context)
                for marker in ["Time zone unknown", "時區未知"] {
                    let card = actual.cardText(unknownTimeZone: marker)
                    let detail = actual.detailText(unknownTimeZone: marker)
                    switch vector.kind {
                    case "date-only":
                        XCTAssertNil(actual.instantMillis, context)
                        XCTAssertNil(actual.timeKey, context)
                        XCTAssertEqual(card, expected.dateKey, context)
                        XCTAssertEqual(detail, expected.dateKey, context)
                    case "floating":
                        XCTAssertNil(actual.instantMillis, context)
                        XCTAssertTrue(try XCTUnwrap(card).hasSuffix(marker), context)
                        XCTAssertEqual(detail, "\(try XCTUnwrap(expected.dateKey)) \(try XCTUnwrap(expected.timeKey).prefix(5)) · \(marker)", context)
                    case "instant":
                        XCTAssertEqual(card, expected.timeKey.map { String($0.prefix(5)) }, context)
                        XCTAssertEqual(detail, "\(try XCTUnwrap(expected.dateKey)) \(try XCTUnwrap(expected.timeKey).prefix(5))", context)
                    default:
                        XCTAssertNil(card, context)
                        XCTAssertNil(detail, context)
                    }
                }
                count += 1
            }
        }
        XCTAssertEqual(count, 144)
    }

    func testFixedSortCasesRunActualGallerySortAndGroupCode() throws {
        let fixture = try contract()
        let items = fixture.sortItems.map(\.media)
        for test in fixture.sortCases {
            let zone = try XCTUnwrap(TimeZone(identifier: test.zone))
            let sort = try XCTUnwrap(MediaSort(rawValue: test.sort))
            let ordered = mediaItemsForDisplay(items, filter: .all, sort: sort, displayZone: zone)
            XCTAssertEqual(ordered.map(\.id), test.expectedIds, "\(test.zone) \(test.sort)")
            let groups = mediaDateGroups(ordered, displayZone: zone)
            XCTAssertEqual(groups.flatMap(\.items).map(\.id), test.expectedIds)
            for group in groups {
                XCTAssertTrue(group.items.allSatisfy { mediaCaptureDate($0, displayZone: zone).dateKey == group.dayKey })
            }
            if sort != .camera {
                XCTAssertEqual(groups.last?.items.map(\.id), ["U_FIRST", "U_LAST"])
                XCTAssertEqual(groups.count, Set(groups.map { $0.dayKey ?? "unknown" }).count)
            }
        }
    }

    func testDateTupleIsTransitiveFor2016DistinctOrderedTriples() throws {
        let fixture = try contract()
        var count = 0
        for zoneID in fixture.displayZones {
            let zone = try XCTUnwrap(TimeZone(identifier: zoneID))
            let dates = fixture.sortItems.map { mediaCaptureDate($0.media, displayZone: zone) }
            for newest in [true, false] {
                for a in dates.indices {
                    for b in dates.indices where b != a {
                        for c in dates.indices where c != a && c != b {
                            let ab = compareMediaCaptureDates(dates[a], dates[b], newest: newest)
                            let bc = compareMediaCaptureDates(dates[b], dates[c], newest: newest)
                            if ab != .orderedDescending && bc != .orderedDescending {
                                XCTAssertNotEqual(compareMediaCaptureDates(dates[a], dates[c], newest: newest), .orderedDescending)
                            }
                            XCTAssertEqual(ab.rawValue, -compareMediaCaptureDates(dates[b], dates[a], newest: newest).rawValue)
                            count += 1
                        }
                    }
                }
            }
        }
        XCTAssertEqual(count, 2_016)
    }

    func testLiteralRangeIntersectsFixedExpectedDaysForAllThreeZones() throws {
        let fixture = try contract()
        let day = try XCTUnwrap(MediaCaptureDate.dayCarrier("2026-08-14"))
        let range = try XCTUnwrap(MediaDateRange(startDate: day, endDate: day, timeZone: .init(secondsFromGMT: 0)!))
        let items = fixture.vectors.map { CameraMediaItem(id: $0.id, name: "SYNTHETIC.JPG", kind: "image", captureTime: $0.raw) }
        for zoneID in fixture.displayZones {
            let zone = try XCTUnwrap(TimeZone(identifier: zoneID))
            let expected = fixture.vectors.filter { $0.display[zoneID]?.dateKey == "2026-08-14" }.map(\.id)
            let unknown = fixture.vectors.filter { $0.display[zoneID]?.dateKey == nil }.count
            XCTAssertEqual(mediaItemsForDisplay(items, filter: .all, sort: .camera,
                dateRange: range, displayZone: zone).map(\.id), expected)
            let summary = mediaDateFilterSummary(items, filter: .all, dateRange: range, displayZone: zone)
            XCTAssertEqual(summary.loadedCount, 48)
            XCTAssertEqual(summary.matchedCount, expected.count)
            XCTAssertEqual(summary.unknownDateCount, unknown)
            XCTAssertEqual(mediaRatingFilterSummary(items, filter: .all, dateRange: range,
                displayZone: zone).matchedCount, expected.count)
            XCTAssertEqual(mediaFolderFilterSummary(items, filter: .all, dateRange: range,
                displayZone: zone).matchedCount, expected.count)
        }
    }

    func testFixedReviewCasesKeepCameraOrderAndExcludeEveryPreviousIDBeforeKind() throws {
        let fixture = try contract()
        let items = fixture.reviewItems.map(\.media)
        for test in fixture.reviewCases {
            let selected: CameraMediaItem?
            if test.kind == "recent" { selected = selectLatestMediaItem(items) }
            else {
                selected = selectLatestMediaItem(afterCaptureFrom: items, previousIDs: Set(test.previousIds),
                    kind: test.kind == "video" ? .video : .photo)
            }
            XCTAssertEqual(selected?.id, test.expectedId)
        }
        XCTAssertEqual(selectLatestMediaItem(Array(items.reversed()))?.id, "NEW_PHOTO_WITH_DATE")
        XCTAssertEqual(selectLatestMediaItem(afterCaptureFrom: [items[1], items[0], items[2], items[3]],
            previousIDs: ["OLD"], kind: .photo)?.id, "NEW_PHOTO_NO_DATE")
    }

    func testGregorianBoundaryAndMillisecondValuesStayStrict() throws {
        let utc = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let fixed: [(String, Int64, String)] = [
            ("0001-01-01T00:00:00Z", -62_135_596_800_000, "0001-01-01"),
            ("1582-10-10T12:00:00Z", -12_219_681_600_000, "1582-10-10"),
            ("1969-12-31T23:59:59.999999999Z", -1, "1969-12-31"),
            ("9999-12-31T23:59:59.999999999Z", 253_402_300_799_999, "9999-12-31"),
        ]
        for (raw, millis, key) in fixed {
            let date = MediaCaptureDate.parse(raw, displayZone: utc)
            XCTAssertEqual(date.kind, .instant)
            XCTAssertEqual(date.instantMillis, millis)
            XCTAssertEqual(date.dateKey, key)
        }
        XCTAssertEqual(MediaCaptureDate.parse(fixed[3].0, displayZone: utc).timeKey, "23:59:59.999")
        for raw in ["0001-01-01T00:00:00+00:01", "9999-12-31T23:59:59-00:01",
                    "10000-01-01", "0000-01-01", "1500-02-29", "2026-08-14\nJUNK",
                    "2026-08-14T12:34:56+08:00[GMT+0800]",
                    "2026-08-14T00:00:00Z[" + String(repeating: "A", count: 65) + "]",
                    String(repeating: "A", count: 129)] {
            XCTAssertEqual(MediaCaptureDate.parse(raw, displayZone: utc).kind, .unknown, raw)
        }
        XCTAssertEqual(MediaCaptureDate.parse("1600-02-29", displayZone: utc).dateKey, "1600-02-29")
        XCTAssertEqual(MediaCaptureDate.parse("0001-01-01T00:00:00Z",
            displayZone: try XCTUnwrap(TimeZone(secondsFromGMT: -60))).kind, .unknown)
        XCTAssertEqual(MediaCaptureDate.parse(fixed[3].0,
            displayZone: try XCTUnwrap(TimeZone(secondsFromGMT: 60))).kind, .unknown)
        XCTAssertEqual(MediaCaptureDate.parse(String(repeating: " ", count: 200) + "2026-08-14",
            displayZone: utc).kind, .dateOnly, "The bound is applied after trimming")
    }

    func testTwentyThousandUnknownRowsKeepLoadedOrderAndOneGroup() {
        let zone = MediaCaptureDate.carrierTimeZone
        let items = (0..<20_000).map {
            CameraMediaItem(id: "synthetic-\($0)", name: "IMG_\(20_000 - $0).JPG", kind: "image")
        }
        for sort in [MediaSort.newest, .oldest, .camera] {
            let sorted = mediaItemsForDisplay(items, filter: .all, sort: sort, displayZone: zone)
            XCTAssertEqual(sorted, items)
            let groups = mediaDateGroups(sorted, displayZone: zone)
            XCTAssertEqual(groups.count, 1)
            XCTAssertNil(groups.first?.dayKey)
            XCTAssertEqual(groups.first?.items.count, 20_000)
            XCTAssertEqual(groups.first?.items, items)
        }
    }

    func testLiteralCarrierRetainsSkippedDayAndNeverEntersCaptureInstant() throws {
        let apia = try XCTUnwrap(TimeZone(identifier: "Pacific/Apia"))
        let literal = MediaCaptureDate.parse("2011-12-30", displayZone: apia)
        let carrier = try XCTUnwrap(MediaCaptureDate.dayCarrier("2011-12-30"))
        let range = try XCTUnwrap(MediaDateRange(startDate: carrier, endDate: carrier, timeZone: MediaCaptureDate.carrierTimeZone))
        XCTAssertEqual(range.startDayKey, "2011-12-30")
        XCTAssertEqual(range.endDayKey, "2011-12-30")
        XCTAssertEqual(range.startDayCarrier, carrier)
        XCTAssertTrue(range.contains(dayKey: literal.dateKey))
        XCTAssertNil(literal.instantMillis)
        XCTAssertEqual(literal.detailText(unknownTimeZone: "Time zone unknown"), "2011-12-30")
        XCTAssertNil(MediaCaptureDate.dayCarrier(" 2011-12-30"))
    }
}
