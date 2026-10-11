import XCTest
import OpenEOSCore
@testable import OpenEOSControl

final class ForegroundMediaPlanTests: XCTestCase {
    func testTwoMatchingCompleteBaselinesDoNotBackfillAnyMediaType() throws {
        var plan = ForegroundMediaPlan()
        let old = [item("old"), item("raw", name: "A.CR3", kind: "raw"), item("movie", name: "B.MP4", kind: "video")]
        try plan.observeInventory(old, at: 0)
        try plan.observeInventory(Array(old.reversed()), at: 0.5)
        XCTAssertFalse(plan.baselineEstablished)
        try plan.observeInventory(Array(old.reversed()), at: 1)
        XCTAssertTrue(plan.baselineEstablished)
        XCTAssertTrue(plan.pending.isEmpty)
        XCTAssertEqual(plan.known.count, 3)
    }

    func testChangedBaselineRestartsObservationAndEventuallyFailsClosed() throws {
        var plan = ForegroundMediaPlan()
        try plan.observeInventory([], at: 0)
        try plan.observeInventory([item("old")], at: 1)
        try plan.observeInventory([item("old")], at: 1.5)
        XCTAssertFalse(plan.baselineEstablished)
        try plan.observeInventory([item("old")], at: 2)
        XCTAssertTrue(plan.baselineEstablished)
        XCTAssertTrue(plan.pending.isEmpty)
        var unstable = ForegroundMediaPlan()
        try unstable.observeInventory([], at: 0)
        XCTAssertThrowsError(try unstable.observeInventory([item("new")], at: 31)) {
            XCTAssertEqual($0 as? ForegroundMediaPlan.Failure, .unstableBaseline)
        }
    }

    func testNewJPEGOnlyAndReappearingOrReclassifiedIDsNeverRepeat() throws {
        var plan = try baseline([])
        let jpeg = item("jpeg")
        let raw = item("raw", name: "A.CR3", kind: "raw")
        try plan.observeInventory([jpeg, raw], at: 5)
        XCTAssertEqual(plan.pending.map { Data($0.item.id.utf8) }, [Data("jpeg".utf8)])
        XCTAssertNil(try plan.observeFresh(jpeg, at: 5))
        XCTAssertEqual(try plan.observeFresh(jpeg, at: 6), 4)
        try plan.recordPrepared(jpeg)
        try plan.observeInventory([], at: 10)
        try plan.observeInventory([jpeg, item("raw")], at: 15)
        XCTAssertTrue(plan.pending.isEmpty)
        XCTAssertEqual(plan.preparedCount, 1)
    }

    func testOpaqueUnicodeIDsRemainDistinctAndDuplicateWireIDsRejectInventory() throws {
        let a = item("caf\u{00e9}")
        let b = item("cafe\u{0301}")
        XCTAssertEqual(a.id, b.id)
        var plan = try baseline([])
        try plan.observeInventory([a, b], at: 5)
        XCTAssertEqual(plan.pending.count, 2)
        XCTAssertEqual(plan.known.count, 2)
        XCTAssertThrowsError(try plan.observeInventory([a, a], at: 10)) {
            XCTAssertEqual($0 as? ForegroundMediaPlan.Failure, .invalidInventory)
        }
    }

    func testFreshSizeMustBePositiveSameIdentityAndStableForAFullInterval() throws {
        var plan = try baseline([])
        try plan.observeInventory([item("new", size: 999)], at: 5)
        XCTAssertNil(try plan.observeFresh(item("new", size: 4), at: 5))
        XCTAssertNil(try plan.observeFresh(item("new", size: 4), at: 5.5))
        XCTAssertNil(try plan.observeFresh(item("new", size: 5), at: 6))
        XCTAssertNil(try plan.observeFresh(item("new", size: 5), at: 6.5))
        XCTAssertEqual(try plan.observeFresh(item("new", size: 5), at: 7), 5)
        for invalid in [item("other"), item("new", size: nil), item("new", size: 0), item("new", size: -1)] {
            XCTAssertThrowsError(try plan.observeFresh(invalid, at: 8))
        }
    }

    func testLimitsAndExpiryStopWithoutGrowingTheQueue() throws {
        var tooMany = ForegroundMediaPlan()
        XCTAssertThrowsError(try tooMany.observeInventory((0...1_000).map { item("\($0)") }, at: 0))
        var queue = try baseline([])
        XCTAssertThrowsError(try queue.observeInventory((0...20).map { item("\($0)") }, at: 5))
        XCTAssertTrue(queue.pending.isEmpty)
        XCTAssertTrue(queue.known.isEmpty)
        try queue.observeInventory([item("new")], at: 5)
        XCTAssertNoThrow(try queue.checkPendingAge(at: 35))
        XCTAssertThrowsError(try queue.checkPendingAge(at: 35.1)) {
            XCTAssertEqual($0 as? ForegroundMediaPlan.Failure, .expiredItem)
        }
    }

    func testNonJPEGOrConflictingTypeNeverBecomesAutomaticOriginal() {
        XCTAssertTrue(ForegroundMediaPlan.isJPEG(item("j", name: "A.JPEG")))
        for value in [item("r", name: "A.CR3"), item("v", name: "A.JPG", kind: "video"),
                      item("t", name: "A.JPG", contentType: "text/html"), item("n", name: "no-extension")] {
            XCTAssertFalse(ForegroundMediaPlan.isJPEG(value))
        }
    }

    func testKnownIdentityAndSuccessfulResultBoundsAreIndependentOfCurrentCardSize() throws {
        var known = try baseline([])
        for page in 0..<4 {
            try known.observeInventory((0..<1_000).map { item("\(page)-\($0)", name: "A.CR3", kind: "raw") }, at: Double(page + 5))
        }
        XCTAssertThrowsError(try known.observeInventory((0..<97).map { item("overflow-\($0)", name: "A.CR3", kind: "raw") }, at: 10))
        XCTAssertEqual(known.known.count, 4_000)
        var results = try baseline([])
        for index in 0..<100 {
            let value = item("\(index)")
            let time = Double(index * 3 + 5)
            try results.observeInventory([value], at: time)
            XCTAssertNil(try results.observeFresh(value, at: time))
            XCTAssertEqual(try results.observeFresh(value, at: time + 1), 4)
            try results.recordPrepared(value)
        }
        let next = item("101")
        try results.observeInventory([next], at: 305)
        XCTAssertNil(try results.observeFresh(next, at: 305))
        XCTAssertThrowsError(try results.observeFresh(next, at: 306)) {
            XCTAssertEqual($0 as? ForegroundMediaPlan.Failure, .resultLimit)
        }
        XCTAssertEqual(results.preparedCount, 100)
    }

    private func baseline(_ items: [CameraMediaItem]) throws -> ForegroundMediaPlan {
        var plan = ForegroundMediaPlan()
        try plan.observeInventory(items, at: 0)
        try plan.observeInventory(items, at: 1)
        return plan
    }

    private func item(_ id: String, name: String = "A.JPG", kind: String = "image",
                      size: Int64? = 4, contentType: String? = "image/jpeg") -> CameraMediaItem {
        CameraMediaItem(id: id, name: name, kind: kind, sizeBytes: size, contentType: contentType)
    }
}
