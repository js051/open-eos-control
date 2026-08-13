import Foundation
import OpenEOSCore

enum MediaFilter: String, CaseIterable, Identifiable {
    case all
    case photos
    case videos

    var id: String { rawValue }
    var localizationKey: String { "media_filter_\(rawValue)" }
}

enum MediaSort: String, CaseIterable, Identifiable {
    case newest
    case oldest
    case name

    var id: String { rawValue }
    var localizationKey: String { "media_sort_\(rawValue)" }
    var systemImage: String {
        switch self {
        case .newest: "calendar.badge.clock"
        case .oldest: "calendar"
        case .name: "textformat"
        }
    }
}

func mediaIsVideo(_ item: CameraMediaItem) -> Bool {
    if item.kind.caseInsensitiveCompare("video") == .orderedSame { return true }
    let videoExtensions = Set(["mp4", "mov", "m4v", "avi", "mkv"])
    return videoExtensions.contains((item.name as NSString).pathExtension.lowercased())
}

func mediaItemsForDisplay(
    _ items: [CameraMediaItem],
    filter: MediaFilter,
    sort: MediaSort
) -> [CameraMediaItem] {
    items
        .filter { item in
            switch filter {
            case .all: true
            case .photos: !mediaIsVideo(item)
            case .videos: mediaIsVideo(item)
            }
        }
        .sorted { left, right in
            compareMediaItems(left, right, sort: sort) == .orderedAscending
        }
}

func mediaCaptureDate(_ item: CameraMediaItem) -> Date? {
    guard let value = item.captureTime?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
        return nil
    }
    if let parsed = MediaDateParsers.withFraction.date(from: value)
        ?? MediaDateParsers.internet.date(from: value)
    {
        return parsed
    }
    return MediaDateParsers.local.firstNonNil { $0.date(from: value) }
}

private func compareMediaItems(
    _ left: CameraMediaItem,
    _ right: CameraMediaItem,
    sort: MediaSort
) -> ComparisonResult {
    if sort == .name {
        let compared = naturalMediaName(left, right)
        return compared == .orderedSame
            ? left.id.compare(right.id, options: [.caseInsensitive, .numeric])
            : compared
    }

    let leftDate = mediaCaptureDate(left)
    let rightDate = mediaCaptureDate(right)
    switch (leftDate, rightDate) {
    case let (.some(leftDate), .some(rightDate)) where leftDate != rightDate:
        let ascending = leftDate < rightDate ? ComparisonResult.orderedAscending : .orderedDescending
        return sort == .newest ? ascending.reversed : ascending
    case (.some(_), .none):
        return .orderedAscending
    case (.none, .some(_)):
        return .orderedDescending
    default:
        let compared = naturalMediaName(left, right)
        if compared != .orderedSame {
            return sort == .newest ? compared.reversed : compared
        }
        return left.id.compare(right.id, options: [.caseInsensitive, .numeric])
    }
}

private func naturalMediaName(_ left: CameraMediaItem, _ right: CameraMediaItem) -> ComparisonResult {
    left.name.compare(right.name, options: [.caseInsensitive, .numeric])
}

private enum MediaDateParsers {
    static let withFraction: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
    static let internet = ISO8601DateFormatter()
    static let local: [DateFormatter] = ["yyyy-MM-dd HH:mm:ss", "yyyyMMdd'T'HHmmss"].map { format in
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = .current
        formatter.dateFormat = format
        return formatter
    }
}

private extension ComparisonResult {
    var reversed: ComparisonResult {
        switch self {
        case .orderedAscending: .orderedDescending
        case .orderedDescending: .orderedAscending
        case .orderedSame: .orderedSame
        }
    }
}

private extension Array {
    func firstNonNil<Value>(_ transform: (Element) -> Value?) -> Value? {
        for element in self {
            if let value = transform(element) { return value }
        }
        return nil
    }
}
