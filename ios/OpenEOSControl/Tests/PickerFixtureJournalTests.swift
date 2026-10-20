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
