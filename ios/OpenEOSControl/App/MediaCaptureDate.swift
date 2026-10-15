import Foundation

/// Metadata precision and one immutable viewer-zone projection. Literal values
/// never become an instant, even when their local clock falls in a DST gap.
struct MediaCaptureDate: Equatable {
    enum Kind: String {
        case instant, floating, dateOnly = "date-only", unknown

        var sortBucket: Int {
            switch self {
            case .instant: 0
            case .floating: 1
            case .dateOnly: 2
            case .unknown: 3
            }
        }
    }

    let kind: Kind
    let dateKey: String?
    let timeKey: String?
    let instantMillis: Int64?
    let floatingClockMillis: Int?

    static let unknown = MediaCaptureDate(kind: .unknown, dateKey: nil, timeKey: nil,
                                         instantMillis: nil, floatingClockMillis: nil)
    static let carrierTimeZone = TimeZone(secondsFromGMT: 0)!
    private static let knownNamedZones = Set(TimeZone.knownTimeZoneIdentifiers).union(["UTC", "GMT"])
    private static let dayMillis: Int64 = 86_400_000
    private static let minimumMillis = daysBeforeYear(1) * dayMillis
    private static let maximumExclusiveMillis = daysBeforeYear(10_000) * dayMillis
    private static let longPattern = try! NSRegularExpression(pattern:
        #"\A([0-9]{4})-([0-9]{2})-([0-9]{2})(?:[T ]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.([0-9]{1,9}))?)?(Z|[+-][0-9]{2}:?[0-9]{2})?(?:\[([A-Za-z0-9_./+\-]{1,64})\])?)?\z"#)
    private static let compactPattern = try! NSRegularExpression(pattern:
        #"\A([0-9]{4})([0-9]{2})([0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})\z"#)

    static func parse(_ raw: String?, displayZone: TimeZone) -> Self {
        guard let raw else { return .unknown }
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.utf8.count <= 128 else { return .unknown }
        let fullRange = NSRange(value.startIndex..., in: value)
        let match = longPattern.firstMatch(in: value, range: fullRange)
            ?? compactPattern.firstMatch(in: value, range: fullRange)
        guard let match, match.range == fullRange else { return .unknown }
        func field(_ index: Int) -> String? {
            guard index < match.numberOfRanges, let range = Range(match.range(at: index), in: value) else { return nil }
            return String(value[range])
        }
        guard let year = field(1).flatMap(Int.init), let month = field(2).flatMap(Int.init),
              let day = field(3).flatMap(Int.init), validDay(year, month, day)
        else { return .unknown }
        let key = String(format: "%04d-%02d-%02d", year, month, day)
        guard let hourText = field(4) else {
            return Self(kind: .dateOnly, dateKey: key, timeKey: nil, instantMillis: nil, floatingClockMillis: nil)
        }
        guard let hour = Int(hourText), let minute = field(5).flatMap(Int.init),
              let second = Int(field(6) ?? "0"), (0...23).contains(hour),
              (0...59).contains(minute), (0...59).contains(second)
        else { return .unknown }
        let millis = Int(((field(7) ?? "") + "000").prefix(3))!
        let clock = ((hour * 60 + minute) * 60 + second) * 1_000 + millis
        guard let offsetText = field(8) else {
            guard field(9) == nil else { return .unknown }
            return Self(kind: .floating, dateKey: key, timeKey: timeKey(clock),
                        instantMillis: nil, floatingClockMillis: clock)
        }
        let offset: Int
        if offsetText == "Z" { offset = 0 } else {
            let digits = offsetText.dropFirst().replacingOccurrences(of: ":", with: "")
            guard let hours = Int(digits.prefix(2)), let minutes = Int(digits.suffix(2)),
                  hours <= 18, minutes <= 59, hours != 18 || minutes == 0 else { return .unknown }
            offset = (hours * 60 + minutes) * 60 * (offsetText.first == "-" ? -1 : 1)
        }
        let instant = dayNumber(year, month, day) * dayMillis + Int64(clock) - Int64(offset) * 1_000
        guard (minimumMillis..<maximumExclusiveMillis).contains(instant) else { return .unknown }
        let date = Date(timeIntervalSince1970: Double(instant) / 1_000)
        if let zoneName = field(9) {
            guard knownNamedZones.contains(zoneName), let namedZone = TimeZone(identifier: zoneName),
                  namedZone.secondsFromGMT(for: date) == offset else { return .unknown }
        }
        let local = instant + Int64(displayZone.secondsFromGMT(for: date)) * 1_000
        guard let displayed = displayFields(local) else { return .unknown }
        return Self(kind: .instant, dateKey: displayed.day, timeKey: timeKey(displayed.clock),
                    instantMillis: instant, floatingClockMillis: nil)
    }

    /// Both cards and details consume exactly the parser's zone/precision result.
    func cardText(unknownTimeZone: String) -> String? {
        guard let dateKey else { return nil }
        guard let timeKey else { return dateKey }
        let clock = String(timeKey.prefix(5))
        return kind == .floating ? "\(clock) · \(unknownTimeZone)" : clock
    }

    func detailText(unknownTimeZone: String) -> String? {
        guard let dateKey else { return nil }
        guard let timeKey else { return dateKey }
        let text = "\(dateKey) \(timeKey.prefix(5))"
        return kind == .floating ? "\(text) · \(unknownTimeZone)" : text
    }

    static func dayKey(at date: Date, in zone: TimeZone) -> String? {
        let interval = date.timeIntervalSince1970
        guard interval.isFinite, interval >= Double(minimumMillis) / 1_000,
              interval < Double(maximumExclusiveMillis) / 1_000 else { return nil }
        let local = Int64(floor(interval * 1_000)) + Int64(zone.secondsFromGMT(for: date)) * 1_000
        return displayFields(local)?.day
    }

    /// Only a DatePicker carrier. This API never returns a metadata instant.
    static func dayCarrier(_ key: String) -> Date? {
        let parsed = parse(key, displayZone: carrierTimeZone)
        guard parsed.kind == .dateOnly, parsed.dateKey == key else { return nil }
        let pieces = key.split(separator: "-").compactMap { Int($0) }
        return Date(timeIntervalSince1970: Double(dayNumber(pieces[0], pieces[1], pieces[2]) * dayMillis) / 1_000)
    }

    private static func validDay(_ year: Int, _ month: Int, _ day: Int) -> Bool {
        (1...9_999).contains(year) && (1...12).contains(month)
            && day >= 1 && day <= monthDays(year, month)
    }

    private static func monthDays(_ year: Int, _ month: Int) -> Int {
        if month == 2 { return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) ? 29 : 28 }
        return [4, 6, 9, 11].contains(month) ? 30 : 31
    }

    // Integer proleptic Gregorian arithmetic avoids Calendar/DateFormatter's
    // historical cutover, permissive normalization, and fractional rounding.
    private static func daysBeforeYear(_ year: Int) -> Int64 {
        let y = Int64(year - 1)
        return 365 * y + y / 4 - y / 100 + y / 400 - 719_162
    }

    private static func dayNumber(_ year: Int, _ month: Int, _ day: Int) -> Int64 {
        daysBeforeYear(year) + Int64((1..<month).reduce(day - 1) { $0 + monthDays(year, $1) })
    }

    private static func displayFields(_ millis: Int64) -> (day: String, clock: Int)? {
        guard (minimumMillis..<maximumExclusiveMillis).contains(millis) else { return nil }
        let day = millis / dayMillis - (millis % dayMillis < 0 ? 1 : 0)
        let clock = Int(millis - day * dayMillis)
        var low = 1
        var high = 10_000
        while low + 1 < high {
            let middle = (low + high) / 2
            if daysBeforeYear(middle) <= day { low = middle } else { high = middle }
        }
        var remainder = Int(day - daysBeforeYear(low))
        var month = 1
        while remainder >= monthDays(low, month) {
            remainder -= monthDays(low, month)
            month += 1
        }
        return (String(format: "%04d-%02d-%02d", low, month, remainder + 1), clock)
    }

    private static func timeKey(_ millis: Int) -> String {
        String(format: "%02d:%02d:%02d.%03d", millis / 3_600_000,
               millis / 60_000 % 60, millis / 1_000 % 60, millis % 1_000)
    }
}
