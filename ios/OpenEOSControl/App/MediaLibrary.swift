import CoreGraphics
import Foundation
import OpenEOSCore

enum MediaLibraryScope: String, CaseIterable, Identifiable {
    case recent
    case all

    var id: String { rawValue }
    var localizationKey: String { "media_scope_\(rawValue)" }
}

struct MediaLibraryBatch: Equatable {
    let items: [CameraMediaItem]
    let hasMore: Bool
}

func selectLatestMediaItem(_ items: [CameraMediaItem]) -> CameraMediaItem? {
    guard !items.isEmpty else { return nil }
    return items.max { left, right in
        switch (mediaCaptureDate(left), mediaCaptureDate(right)) {
        case let (.some(leftDate), .some(rightDate)):
            return leftDate < rightDate
        case (.some, .none):
            return false
        case (.none, .some):
            return true
        case (.none, .none):
            return false
        }
    }
}

enum CaptureReviewState: String {
    case idle, capturing, searching, notReady, readFailed, available

    var localizationKey: String { "capture_review_\(rawValue)" }
}

func selectLatestMediaItem(afterCaptureFrom items: [CameraMediaItem], previousIDs: Set<String>) -> CameraMediaItem? {
    // Visibility is not exposure provenance. Keep the existing date/camera order,
    // but exclude everything already observed before choosing a still candidate.
    selectLatestMediaItem(items.filter { !previousIDs.contains($0.id) && !mediaIsVideo($0) })
}

enum MediaFilter: String, CaseIterable, Identifiable {
    case all
    case photos
    case videos

    var id: String { rawValue }
    var localizationKey: String { "media_filter_\(rawValue)" }
}

struct MediaDateRange: Equatable {
    let startDate: Date
    let endDate: Date
    let endExclusive: Date
    let timeZone: TimeZone

    init?(startDate: Date, endDate: Date, timeZone: TimeZone) {
        guard startDate.timeIntervalSinceReferenceDate.isFinite,
              endDate.timeIntervalSinceReferenceDate.isFinite,
              let capturedTimeZone = TimeZone(identifier: timeZone.identifier)
        else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = capturedTimeZone
        guard let firstDay = calendar.dateInterval(of: .day, for: startDate),
              let lastDay = calendar.dateInterval(of: .day, for: endDate),
              firstDay.start <= lastDay.start,
              firstDay.start.timeIntervalSinceReferenceDate.isFinite,
              lastDay.start.timeIntervalSinceReferenceDate.isFinite,
              lastDay.end.timeIntervalSinceReferenceDate.isFinite,
              lastDay.end > lastDay.start
        else { return nil }
        self.startDate = firstDay.start
        self.endDate = lastDay.start
        // Calendar day intervals include the whole last day even across DST.
        endExclusive = lastDay.end
        self.timeZone = capturedTimeZone
    }

    func contains(captureDate: Date) -> Bool {
        captureDate >= startDate && captureDate < endExclusive
    }
}

struct MediaDateFilterSummary: Equatable {
    // The denominator is every loaded item, including other media types.
    let loadedCount: Int
    // The visible type/date intersection; sorting does not change this count.
    let matchedCount: Int
    // Missing/unparseable dates in the selected type, even without a range.
    let unknownDateCount: Int
}

func mediaDateFilterSummary(
    _ items: [CameraMediaItem],
    filter: MediaFilter,
    dateRange: MediaDateRange?
) -> MediaDateFilterSummary {
    var matchedCount = 0
    var unknownDateCount = 0
    for item in items where mediaMatchesFilter(item, filter: filter) {
        let captureDate = mediaCaptureDate(item)
        if captureDate == nil { unknownDateCount += 1 }
        if let dateRange {
            if let captureDate, dateRange.contains(captureDate: captureDate) { matchedCount += 1 }
        } else {
            matchedCount += 1
        }
    }
    return MediaDateFilterSummary(
        loadedCount: items.count,
        matchedCount: matchedCount,
        unknownDateCount: unknownDateCount
    )
}

func mediaLibraryBatch(
    _ items: [CameraMediaItem],
    scope: MediaLibraryScope,
    recentItemCount: Int
) -> MediaLibraryBatch {
    precondition(recentItemCount > 0)
    guard scope == .recent else { return MediaLibraryBatch(items: items, hasMore: false) }
    return MediaLibraryBatch(
        items: Array(items.prefix(recentItemCount)),
        hasMore: items.count > recentItemCount
    )
}

enum MediaSort: String, CaseIterable, Identifiable {
    case camera
    case newest
    case oldest
    case name

    var id: String { rawValue }
    var localizationKey: String { "media_sort_\(rawValue)" }
    var systemImage: String {
        switch self {
        case .camera: "camera"
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
    sort: MediaSort,
    dateRange: MediaDateRange? = nil
) -> [CameraMediaItem] {
    let filtered = items
        .filter { item in
            guard mediaMatchesFilter(item, filter: filter) else { return false }
            guard let dateRange else { return true }
            guard let captureDate = mediaCaptureDate(item) else { return false }
            return dateRange.contains(captureDate: captureDate)
        }
    guard sort != .camera else { return filtered }
    return filtered.enumerated()
        .sorted { left, right in
            let compared = compareMediaItems(left.element, right.element, sort: sort)
            return compared == .orderedSame ? left.offset < right.offset : compared == .orderedAscending
        }
        .map { $0.element }
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

private func mediaMatchesFilter(_ item: CameraMediaItem, filter: MediaFilter) -> Bool {
    switch filter {
    case .all: true
    case .photos: !mediaIsVideo(item)
    case .videos: mediaIsVideo(item)
    }
}

private func compareMediaItems(
    _ left: CameraMediaItem,
    _ right: CameraMediaItem,
    sort: MediaSort
) -> ComparisonResult {
    if sort == .camera { return .orderedSame }
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
    case (.none, .none):
        let compared = naturalMediaName(left, right)
        let stable = compared == .orderedSame
            ? left.id.compare(right.id, options: [.caseInsensitive, .numeric])
            : compared
        return sort == .newest ? stable.reversed : stable
    case (.some(_), .some(_)):
        return .orderedSame
    }
}

func mediaImagePanBounds(scale: CGFloat, viewport: CGSize, image: CGSize) -> CGSize {
    guard scale > 1,
          viewport.width > 0,
          viewport.height > 0,
          image.width > 0,
          image.height > 0
    else { return .zero }
    let fit = min(viewport.width / image.width, viewport.height / image.height)
    return CGSize(
        width: max(0, (image.width * fit * scale - viewport.width) / 2),
        height: max(0, (image.height * fit * scale - viewport.height) / 2)
    )
}

func clampMediaImageOffset(
    _ proposed: CGSize,
    scale: CGFloat,
    viewport: CGSize,
    image: CGSize
) -> CGSize {
    let bounds = mediaImagePanBounds(scale: scale, viewport: viewport, image: image)
    return CGSize(
        width: min(max(proposed.width, -bounds.width), bounds.width),
        height: min(max(proposed.height, -bounds.height), bounds.height)
    )
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
