import Foundation
import OpenEOSCore
import XCTest
@testable import OpenEOSControl

final class MediaLibraryTests: XCTestCase {
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
