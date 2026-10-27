import Foundation
import XCTest
@testable import OpenEOSControl

final class PickerFixtureJournalTests: XCTestCase {
    private let ticket = PickerFixtureJournal.Ticket(
        ordinal: 1, startedUptime: 10, scenario: .success, language: .english)

    func testEveryStageUsesOnlyClosedSchemaValues() {
        for stage in PickerFixtureJournal.Stage.allCases {
            let line = PickerFixtureJournal.line(stage, ticket: ticket, now: 10.5,
                presented: true, transitioning: false, dismissing: true, flag: true)
            XCTAssertEqual(line, "v2 ticket=1 ms=500 language=english scenario=success stage=\(stage.rawValue) presented=1 transitioning=0 dismissing=1 flag=1")
            XCTAssertTrue(PickerFixtureJournal.isValidLine(line))
        }
    }

    func testRelativeTimeIsBoundedAndHasNoWallClockOrNonfiniteValues() {
        for (now, expected) in [(9.0, "0"), (10.0, "0"), (11.0, "1000"),
                                (10_000.0, "999999"), (Double.infinity, "0"), (Double.nan, "0")] {
            let line = PickerFixtureJournal.line(.selected, ticket: ticket, now: now)
            XCTAssertTrue(line.contains(" ms=\(expected) "))
            XCTAssertTrue(PickerFixtureJournal.isValidLine(line))
        }
    }

    func testSchemaRejectsPrivateFieldsAndNoncanonicalNumbers() {
        let good = PickerFixtureJournal.line(.selected, ticket: ticket, now: 10)
        for bad in [
            good + " filename=private", good + " owner=private", good + " url=https://example.invalid",
            good.replacingOccurrences(of: "ticket=1", with: "ticket=0"),
            good.replacingOccurrences(of: "ticket=1", with: "ticket=10000"),
            good.replacingOccurrences(of: "ticket=1", with: "ticket=01"),
            good.replacingOccurrences(of: "ticket=1", with: "ticket=+1"),
            good.replacingOccurrences(of: "ms=0", with: "ms=-1"),
            good.replacingOccurrences(of: "ms=0", with: "ms=1000000"),
            good.replacingOccurrences(of: "ms=0", with: "ms=00"),
            good.replacingOccurrences(of: "ms=0", with: "ms=1.1"),
            good.replacingOccurrences(of: "language=english", with: "language=private"),
            good.replacingOccurrences(of: "scenario=success", with: "scenario=private"),
            good.replacingOccurrences(of: "stage=selected", with: "stage=private"),
            good.replacingOccurrences(of: "flag=0", with: "flag=2"),
            good + "\n", " " + good,
        ] {
            XCTAssertFalse(PickerFixtureJournal.isValidLine(bad))
            XCTAssertNil(PickerFixtureJournal.boundedJournal([good, bad]))
        }
        XCTAssertTrue(PickerFixtureJournal.isValidLine(
            "success selected presented=1 transitioning=0 dismissing=0"))
    }

    func testRollingJournalPreservesNewestRecordsWithinBothCaps() throws {
        let lines = (1...200).map { ordinal in
            PickerFixtureJournal.line(.presentationCompleted,
                ticket: .init(ordinal: ordinal, startedUptime: 0, scenario: .lostAck, language: .traditionalChinese),
                now: 999, presented: true, transitioning: true, dismissing: true, flag: true)
        }
        let data = try XCTUnwrap(PickerFixtureJournal.boundedJournal(lines))
        let retained = String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init)
        XCTAssertLessThanOrEqual(data.count, 16_384)
        XCTAssertLessThanOrEqual(retained.count, 128)
        XCTAssertGreaterThan(retained.count, 1)
        XCTAssertEqual(retained, Array(lines.suffix(retained.count)))
        XCTAssertEqual(retained.last, lines.last)
    }

    func testOpeningSlotNamesAreFixedAndNeverWrap() {
        XCTAssertEqual(Array(PickerFixtureJournal.openingOrdinals), Array(1...16))
        for ordinal in 1...16 {
            XCTAssertEqual(PickerFixtureJournal.openingJournalName(for: ordinal),
                "eos-picker-lifecycle-opening-\(ordinal).txt")
        }
        for ordinal in [-1, 0, 17, 32, 9_999, 10_000] {
            XCTAssertNil(PickerFixtureJournal.openingJournalName(for: ordinal))
            XCTAssertNil(PickerFixtureJournal.boundedOpeningJournal([], ordinal: ordinal))
        }
    }

    func testOpeningJournalRejectsDifferentTicketsLegacyAndPrivateFields() {
        let first = PickerFixtureJournal.line(.presenterRetained, ticket: ticket, now: 10, flag: true)
        XCTAssertNotNil(PickerFixtureJournal.boundedOpeningJournal([first], ordinal: 1))
        for invalid in [
            first.replacingOccurrences(of: "ticket=1", with: "ticket=2"),
            first.replacingOccurrences(of: "ticket=1", with: "ticket=17"),
            "success selected presented=1 transitioning=0 dismissing=0",
            first + " filename=private", first + " owner=private", first + " url=https://example.invalid",
        ] {
            XCTAssertNil(PickerFixtureJournal.boundedOpeningJournal([first, invalid], ordinal: 1))
        }
    }

    func testOpeningJournalKeepsBothCapsWithoutMixingOpenings() throws {
        let opening = PickerFixtureJournal.Ticket(ordinal: 16, startedUptime: 0,
            scenario: .lostAck, language: .traditionalChinese)
        let lines = (0..<200).map { index in
            PickerFixtureJournal.line(.presentationCompleted, ticket: opening, now: Double(index),
                presented: true, transitioning: true, dismissing: true, flag: true)
        }
        let bytes = try XCTUnwrap(PickerFixtureJournal.boundedOpeningJournal(lines, ordinal: 16))
        let retained = String(decoding: bytes, as: UTF8.self).split(separator: "\n").map(String.init)
        XCTAssertLessThanOrEqual(bytes.count, 16_384)
        XCTAssertLessThanOrEqual(retained.count, 128)
        XCTAssertGreaterThan(retained.count, 1)
        XCTAssertEqual(retained, Array(lines.suffix(retained.count)))
        XCTAssertTrue(retained.allSatisfy { $0.hasPrefix("v2 ticket=16 ") })
    }

    func testLaterOpeningsCannotReuseSlotsAfterAggregateRotationOrRelaunch() throws {
        var slots: [Int: Data] = [:]
        var firstSixteenSlots: [Int: Data] = [:]
        var aggregate: [String] = []
        var lastOrdinal = 0
        for expected in 1...20 {
            let ordinal = try XCTUnwrap(PickerFixtureJournal.nextTicketOrdinal(in: aggregate,
                lastOrdinal: lastOrdinal, highestOccupiedOpening: slots.keys.max() ?? 0))
            XCTAssertEqual(ordinal, expected)
            let current = PickerFixtureJournal.Ticket(ordinal: ordinal, startedUptime: 0,
                scenario: .success, language: .english)
            let first = PickerFixtureJournal.line(.selectionCreated, ticket: current, now: 0)
            if PickerFixtureJournal.openingJournalName(for: ordinal) != nil {
                XCTAssertNil(slots[ordinal])
                slots[ordinal] = try XCTUnwrap(PickerFixtureJournal.boundedOpeningJournal([first], ordinal: ordinal))
            }
            if ordinal == 16 { firstSixteenSlots = slots }
            aggregate.append(first)
            lastOrdinal = ordinal
        }
        let oldLine = PickerFixtureJournal.line(.presenterDisappeared, ticket: ticket, now: 11)
        let rotatedBytes = try XCTUnwrap(PickerFixtureJournal.boundedJournal(aggregate + Array(repeating: oldLine, count: 200)))
        let rotated = String(decoding: rotatedBytes, as: UTF8.self).split(separator: "\n").map(String.init)
        XCTAssertTrue(rotated.allSatisfy { $0.hasPrefix("v2 ticket=1 ") })
        XCTAssertEqual(PickerFixtureJournal.nextTicketOrdinal(in: rotated,
            lastOrdinal: lastOrdinal, highestOccupiedOpening: 16), 21)
        // A relaunch loses the in-memory high-water mark, not the consumed slots.
        XCTAssertEqual(PickerFixtureJournal.nextTicketOrdinal(in: rotated,
            lastOrdinal: 0, highestOccupiedOpening: 16), 17)
        XCTAssertEqual(slots, firstSixteenSlots)
        XCTAssertEqual(slots.count, 16)
        XCTAssertEqual(slots[1], Data((aggregate[0] + "\n").utf8))
    }

    func testTicketAllocationIsBoundedAndTreatsOccupiedSlotsAsConsumed() {
        XCTAssertEqual(PickerFixtureJournal.nextTicketOrdinal(in: [], lastOrdinal: 0, highestOccupiedOpening: 0), 1)
        XCTAssertEqual(PickerFixtureJournal.nextTicketOrdinal(in: [], lastOrdinal: 0, highestOccupiedOpening: 8), 9)
        XCTAssertNil(PickerFixtureJournal.nextTicketOrdinal(in: [], lastOrdinal: 9_999, highestOccupiedOpening: 16))
        XCTAssertNil(PickerFixtureJournal.nextTicketOrdinal(in: [], lastOrdinal: -1, highestOccupiedOpening: 0))
        XCTAssertNil(PickerFixtureJournal.nextTicketOrdinal(in: [], lastOrdinal: 0, highestOccupiedOpening: 17))
        XCTAssertNil(PickerFixtureJournal.nextTicketOrdinal(in: ["private"], lastOrdinal: 0, highestOccupiedOpening: 0))
    }

    func testIdentityFlagsHaveFixedIndependentBooleanMeanings() {
        let stages: [PickerFixtureJournal.Stage] = [
            .identityPickerMatches, .identityRequestedHostAlive, .identityRequestedHostAttached,
            .identityOriginalPresenterKnown, .identityOriginalPresenterAlive,
            .identityCurrentIsRequestedHost, .identityCurrentIsHostAncestor,
            .identityAncestryComplete, .identityCurrentIsOriginalPresenter,
        ]
        for enabledIndex in -1..<9 {
            let flags = (0..<9).map { $0 == enabledIndex }
            let snapshot = PickerFixtureJournal.IdentitySnapshot(
                pickerMatches: flags[0], requestedHostAlive: flags[1], requestedHostAttached: flags[2],
                originalPresenterKnown: flags[3], originalPresenterAlive: flags[4],
                currentIsRequestedHost: flags[5], currentIsHostAncestor: flags[6],
                ancestryComplete: flags[7], currentIsOriginalPresenter: flags[8])
            XCTAssertEqual(snapshot.records.map { $0.0 }, stages)
            XCTAssertEqual(snapshot.records.map { $0.1 }, flags)
            for (stage, flag) in snapshot.records {
                let line = PickerFixtureJournal.line(stage, ticket: ticket, now: 10.5, flag: flag)
                XCTAssertTrue(PickerFixtureJournal.isValidLine(line))
                XCTAssertTrue(line.hasSuffix(" flag=\(flag ? 1 : 0)"))
            }
        }
    }

    func testThreeIdentitySnapshotsFitUnchangedOpeningCapsAndRejectPrivateFields() throws {
        let snapshot = PickerFixtureJournal.IdentitySnapshot(
            pickerMatches: true, requestedHostAlive: true, requestedHostAttached: true,
            originalPresenterKnown: true, originalPresenterAlive: true,
            currentIsRequestedHost: false, currentIsHostAncestor: true,
            ancestryComplete: true, currentIsOriginalPresenter: true)
        var lines: [String] = []
        for phase in [PickerFixtureJournal.Stage.presentationCompleted, .selected, .completionEnd] {
            lines.append(PickerFixtureJournal.line(phase, ticket: ticket, now: 10.5))
            lines += snapshot.records.map { stage, flag in
                PickerFixtureJournal.line(stage, ticket: ticket, now: 10.5, flag: flag)
            }
        }
        let bytes = try XCTUnwrap(PickerFixtureJournal.boundedOpeningJournal(lines, ordinal: 1))
        XCTAssertLessThanOrEqual(bytes.count, 16_384)
        XCTAssertLessThanOrEqual(lines.count, 128)
        XCTAssertEqual(String(decoding: bytes, as: UTF8.self), lines.joined(separator: "\n") + "\n")
        let identityLine = try XCTUnwrap(lines.last)
        for field in ["address=private", "class=private", "device=private", "filename=private"] {
            XCTAssertFalse(PickerFixtureJournal.isValidLine(identityLine + " " + field))
            XCTAssertNil(PickerFixtureJournal.boundedOpeningJournal([identityLine + " " + field], ordinal: 1))
        }
    }

    func testDisabledTicketContainsNoOwnerAndCannotProduceAnAcceptedRecord() {
        XCTAssertFalse(PickerFixtureJournal.Ticket.disabled.isEnabled)
        XCTAssertTrue(ticket.isEnabled)
        XCTAssertFalse(PickerFixtureJournal.isValidLine(
            PickerFixtureJournal.line(.selected, ticket: .disabled, now: 0)))
        #if !OEC_REQUIRE_SIMULATOR_E2E
        XCTAssertEqual(PickerFixtureJournal.makeTicket(), .disabled)
        #endif
    }
}
