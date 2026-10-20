import Foundation

/// A bounded, closed-schema diagnostic for the explicitly selected synthetic
/// upload journey. No URL, filename, owner UUID, process ID or arbitrary text is
/// accepted. Disabled tickets cause no filesystem access in ordinary builds.
enum PickerFixtureJournal {
    enum Stage: String, CaseIterable, Sendable {
        case selectionCreated = "selection-created"
        case coordinatorCreated = "coordinator-created"
        case controllerCreated = "controller-created"
        case presentationRequested = "presentation-requested"
        case presentationCompleted = "presentation-completed"
        case presenterRetained = "presenter-retained"
        case presenterDisappeared = "presenter-disappeared"
        case selected, cancelled, dismantle
        case retirementObserved = "retirement-observed"
        case retirementNoModal = "retirement-no-modal"
        case retirementDismiss = "retirement-dismiss"
        case transitionRegistered = "transition-registered"
        case transitionCompleted = "transition-completed"
        case dismissCompleted = "dismiss-completed"
        case completionBegin = "completion-begin"
        case completionEnd = "completion-end"
        case delegateReturning = "delegate-returning"
        case sheetClear = "sheet-clear"
        case sheetDisappeared = "sheet-disappeared"
        case sheetDismissed = "sheet-dismissed"
        case uploadReturned = "upload-returned"
        case outcomeNotSent = "outcome-not-sent"
        case outcomeUncertain = "outcome-uncertain"
        case outcomeVerified = "outcome-verified"
        case presenterDeinit = "presenter-deinit"
        case coordinatorDeinit = "coordinator-deinit"
        case sessionRetired = "session-retired"
    }
    enum Scenario: String, Sendable { case success, cancel, lostAck = "lost-ack" }
    enum Language: String, Sendable { case english, traditionalChinese }

    /// Contains values only: keeping a ticket cannot keep a picker/owner alive.
    struct Ticket: Equatable, Sendable {
        let ordinal: Int
        let startedUptime: TimeInterval
        let scenario: Scenario
        let language: Language
        var isEnabled: Bool { (1...9_999).contains(ordinal) }
        static let disabled = Ticket(ordinal: 0, startedUptime: 0, scenario: .success, language: .english)
    }

    static func makeTicket() -> Ticket {
        #if DEBUG && OEC_REQUIRE_SIMULATOR_E2E
        let process = ProcessInfo.processInfo
        guard let value = process.environment["OEC_UPLOAD_JOURNEY_FIXTURE"],
              let scenario = Scenario(rawValue: value),
              let index = process.arguments.firstIndex(of: "-app-language"),
              process.arguments.indices.contains(index + 1),
              let language = Language(rawValue: process.arguments[index + 1]) else { return .disabled }
        return storage.makeTicket(scenario: scenario, language: language)
        #else
        return .disabled
        #endif
    }

    static func record(_ stage: Stage, ticket: Ticket, presented: Bool = false,
                       transitioning: Bool = false, dismissing: Bool = false, flag: Bool = false) {
        #if DEBUG && OEC_REQUIRE_SIMULATOR_E2E
        guard ticket.isEnabled else { return }
        storage.append(line(stage, ticket: ticket, now: ProcessInfo.processInfo.systemUptime,
            presented: presented, transitioning: transitioning, dismissing: dismissing, flag: flag), ticket: ticket)
        #endif
    }

    // Pure formatting/validation helpers are also exercised by App unit tests.
    static func line(_ stage: Stage, ticket: Ticket, now: TimeInterval,
                     presented: Bool = false, transitioning: Bool = false,
                     dismissing: Bool = false, flag: Bool = false) -> String {
        let delta = (now - ticket.startedUptime) * 1_000
        let elapsed = delta.isFinite ? Int(min(999_999, max(0, delta))) : 0
        return "v2 ticket=\(ticket.ordinal) ms=\(elapsed) language=\(ticket.language.rawValue) scenario=\(ticket.scenario.rawValue) stage=\(stage.rawValue) presented=\(presented ? 1 : 0) transitioning=\(transitioning ? 1 : 0) dismissing=\(dismissing ? 1 : 0) flag=\(flag ? 1 : 0)"
    }

    static func isValidLine(_ line: String) -> Bool {
        let fields = line.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        let legacyStages = ["coordinator-created", "controller-created", "selected", "cancelled",
                            "dismantle", "completion-begin", "completion-end"]
        if fields.count == 5 {
            return Scenario(rawValue: fields[0]) != nil && legacyStages.contains(fields[1])
                && isBool(fields[2], key: "presented") && isBool(fields[3], key: "transitioning")
                && isBool(fields[4], key: "dismissing")
        }
        guard fields.count == 10, fields[0] == "v2",
              boundedInteger(fields[1], key: "ticket", range: 1...9_999) != nil,
              boundedInteger(fields[2], key: "ms", range: 0...999_999) != nil,
              let language = value(fields[3], key: "language"), Language(rawValue: language) != nil,
              let scenario = value(fields[4], key: "scenario"), Scenario(rawValue: scenario) != nil,
              let stage = value(fields[5], key: "stage"), Stage(rawValue: stage) != nil else { return false }
        return isBool(fields[6], key: "presented") && isBool(fields[7], key: "transitioning")
            && isBool(fields[8], key: "dismissing") && isBool(fields[9], key: "flag")
    }

    static func boundedJournal(_ lines: [String]) -> Data? {
        guard lines.allSatisfy(isValidLine) else { return nil }
        var retained = Array(lines.suffix(128))
        while !retained.isEmpty {
            let bytes = Data((retained.joined(separator: "\n") + "\n").utf8)
            if bytes.count <= 16_384 { return bytes }
            retained.removeFirst()
        }
        return Data()
    }

    /// These first sixteen openings have independent, nonrotating identities.
    /// Each keeps the same bounded window as the aggregate, but later openings
    /// can never evict it. No user-provided filename or path enters this mapping.
    static let openingOrdinals = 1...16

    static func openingJournalName(for ordinal: Int) -> String? {
        guard openingOrdinals.contains(ordinal) else { return nil }
        return "eos-picker-lifecycle-opening-\(ordinal).txt"
    }

    static func boundedOpeningJournal(_ lines: [String], ordinal: Int) -> Data? {
        guard openingOrdinals.contains(ordinal), lines.allSatisfy({ line in
            let fields = line.split(separator: " ", omittingEmptySubsequences: false)
            return isValidLine(line) && fields.count == 10
                && fields[0] == "v2" && fields[1] == "ticket=\(ordinal)"
        }) else { return nil }
        return boundedJournal(lines)
    }

    static func nextTicketOrdinal(in lines: [String], lastOrdinal: Int,
                                  highestOccupiedOpening: Int) -> Int? {
        guard (0...9_999).contains(lastOrdinal), (0...16).contains(highestOccupiedOpening),
              lines.allSatisfy(isValidLine) else { return nil }
        let aggregateOrdinal = lines.compactMap { line -> Int? in
            let fields = line.split(separator: " ")
            guard fields.first == "v2", fields.count == 10 else { return nil }
            return boundedInteger(String(fields[1]), key: "ticket", range: 1...9_999)
        }.max() ?? 0
        let prior = max(lastOrdinal, max(aggregateOrdinal, highestOccupiedOpening))
        return prior < 9_999 ? prior + 1 : nil
    }

    private static func value(_ field: String, key: String) -> String? {
        guard field.hasPrefix(key + "=") else { return nil }
        return String(field.dropFirst(key.count + 1))
    }
    private static func isBool(_ field: String, key: String) -> Bool {
        field == key + "=0" || field == key + "=1"
    }
    private static func boundedInteger(_ field: String, key: String, range: ClosedRange<Int>) -> Int? {
        guard let raw = value(field, key: key), let number = Int(raw),
              String(number) == raw, range.contains(number) else { return nil }
        return number
    }

    #if DEBUG && OEC_REQUIRE_SIMULATOR_E2E
    private static let storage = Storage()
    /// A lock protects atomic records from nonisolated deinit callbacks. It owns
    /// no UI objects and dispatches no work, so it cannot extend their lifetimes.
    private final class Storage: @unchecked Sendable {
        private let lock = NSLock()
        private var lastOrdinal = 0
        func makeTicket(scenario: Scenario, language: Language) -> Ticket {
            lock.lock(); defer { lock.unlock() }
            do {
                guard let url = journalURL(), let lines = try read(url) else { return .disabled }
                // Late callbacks can rotate a newer ticket out of the aggregate.
                // A high-water mark protects this process; occupied fixed slots
                // protect earlier openings across fixture app relaunches as well.
                let occupiedOrdinal = try highestOccupiedOpening(in: url.deletingLastPathComponent())
                guard let ordinal = PickerFixtureJournal.nextTicketOrdinal(in: lines,
                    lastOrdinal: lastOrdinal, highestOccupiedOpening: occupiedOrdinal) else { return .disabled }
                let ticket = Ticket(ordinal: ordinal, startedUptime: ProcessInfo.processInfo.systemUptime,
                    scenario: scenario, language: language)
                let firstLine = PickerFixtureJournal.line(.selectionCreated, ticket: ticket, now: ticket.startedUptime)
                try write(lines + [firstLine], to: url)
                lastOrdinal = ticket.ordinal
                appendOpening(firstLine, ticket: ticket, aggregateURL: url)
                return ticket
            } catch { return .disabled }
        }
        func append(_ line: String, ticket: Ticket) {
            lock.lock(); defer { lock.unlock() }
            guard let url = journalURL() else { return }
            do {
                if let lines = try read(url) { try write(lines + [line], to: url) }
            } catch { /* Diagnostics cannot alter admission or outcomes. */ }
            appendOpening(line, ticket: ticket, aggregateURL: url)
        }
        private func highestOccupiedOpening(in cache: URL) throws -> Int {
            for ordinal in PickerFixtureJournal.openingOrdinals.reversed() {
                guard let name = PickerFixtureJournal.openingJournalName(for: ordinal) else { continue }
                do {
                    // Even an invalid, empty or symlink slot is consumed, never
                    // reassigned. Its contents are read only through read below.
                    _ = try FileManager.default.attributesOfItem(atPath: cache.appendingPathComponent(name).path)
                    return ordinal
                } catch let error as CocoaError where error.code == .fileReadNoSuchFile { continue }
            }
            return 0
        }
        private func appendOpening(_ line: String, ticket: Ticket, aggregateURL: URL) {
            guard let name = PickerFixtureJournal.openingJournalName(for: ticket.ordinal) else { return }
            let url = aggregateURL.deletingLastPathComponent().appendingPathComponent(name)
            do {
                guard let lines = try read(url) else { return }
                try write(lines + [line], to: url, openingOrdinal: ticket.ordinal)
            } catch { /* A damaged optional slot cannot suppress the aggregate. */ }
        }
        private func journalURL() -> URL? {
            guard let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
            return cache.appendingPathComponent("eos-picker-lifecycle.txt")
        }
        private func read(_ url: URL) throws -> [String]? {
            for parent in [url.deletingLastPathComponent(), url.deletingLastPathComponent().deletingLastPathComponent()] {
                let values = try parent.resourceValues(forKeys: [.isSymbolicLinkKey, .isDirectoryKey])
                guard values.isSymbolicLink != true, values.isDirectory == true else { return nil }
            }
            // attributesOfItem also rejects a dangling symlink rather than treating it as missing.
            let attributes: [FileAttributeKey: Any]
            do { attributes = try FileManager.default.attributesOfItem(atPath: url.path) }
            catch let error as CocoaError where error.code == .fileReadNoSuchFile { return [] }
            guard attributes[.type] as? FileAttributeType == .typeRegular,
                  let size = attributes[.size] as? NSNumber, size.intValue <= 16_384 else { return nil }
            let bytes = try Data(contentsOf: url)
            guard bytes.count <= 16_384, let text = String(data: bytes, encoding: .ascii) else { return nil }
            if bytes.isEmpty { return [] }
            let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
            guard lines.last == "" else { return nil }
            let records = Array(lines.dropLast())
            guard records.count <= 128, records.allSatisfy(PickerFixtureJournal.isValidLine) else { return nil }
            return records
        }
        private func write(_ lines: [String], to url: URL, openingOrdinal: Int? = nil) throws {
            let data: Data?
            if let openingOrdinal {
                data = PickerFixtureJournal.boundedOpeningJournal(lines, ordinal: openingOrdinal)
            } else {
                data = PickerFixtureJournal.boundedJournal(lines)
            }
            guard let bytes = data, bytes.count <= 16_384 else { return }
            try bytes.write(to: url, options: .atomic)
        }
    }
    #endif
}
