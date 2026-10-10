import Foundation
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
