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

enum MediaRatingFilter: String, CaseIterable, Identifiable {
    case all
    case unrated
    case atLeast1 = "at_least_1"
    case atLeast2 = "at_least_2"
    case atLeast3 = "at_least_3"
    case atLeast4 = "at_least_4"
    case five
    case unknown

    var id: String { rawValue }
    var localizationKey: String { "media_rating_filter_\(rawValue)" }
}

enum MediaFolderFilter: Equatable {
    case all
    case unknown
    case folder(CameraMediaFolder)

    static func == (left: Self, right: Self) -> Bool {
        switch (left, right) {
        case (.all, .all), (.unknown, .unknown): true
        case let (.folder(left), .folder(right)): mediaFolderIdentity(left) == mediaFolderIdentity(right)
        default: false
        }
    }
}

func mediaFolderIdentity(_ folder: CameraMediaFolder) -> Data {
    // Swift String equality folds canonically equivalent Unicode spellings.
    // Folder IDs are opaque wire identities, so preserve their exact UTF-8 bytes.
    Data(folder.id.utf8)
}

struct MediaFolderOption: Equatable, Identifiable {
    let folder: CameraMediaFolder
    let itemCount: Int

    var id: Data { mediaFolderIdentity(folder) }
}

func mediaFolderOptions(_ items: [CameraMediaItem]) -> [MediaFolderOption] {
    var folders: [Data: MediaFolderOption] = [:]
    for item in items {
        guard let folder = item.folder else { continue }
        let identity = mediaFolderIdentity(folder)
        let existing = folders[identity]
        // Labels can change between loaded pages. Pick one deterministically,
        // without merging different opaque IDs that happen to share a label.
        let preferred = existing.map { option in
            if option.folder.label == folder.label {
                return option.folder.label.utf8.lexicographicallyPrecedes(folder.label.utf8) ? option.folder : folder
            }
            return option.folder.label < folder.label ? option.folder : folder
        } ?? folder
        folders[identity] = MediaFolderOption(folder: preferred, itemCount: (existing?.itemCount ?? 0) + 1)
    }
    return folders.values.sorted { left, right in
        if left.folder.label != right.folder.label { return left.folder.label < right.folder.label }
        return left.id.lexicographicallyPrecedes(right.id)
    }
}

func mediaNormalizedRating(_ item: CameraMediaItem) -> Int? {
    // Zero is an explicit camera value; absent or invalid metadata is unknown.
    guard let rating = item.rating, (0...5).contains(rating) else { return nil }
    return rating
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
    // The visible type/date/rating/folder intersection; sorting does not change this count.
    let matchedCount: Int
    // Missing/unparseable dates in the selected type/rating/folder, even without a range.
    let unknownDateCount: Int
}

func mediaDateFilterSummary(
    _ items: [CameraMediaItem],
    filter: MediaFilter,
    dateRange: MediaDateRange?,
    ratingFilter: MediaRatingFilter = .all,
    folderFilter: MediaFolderFilter = .all
) -> MediaDateFilterSummary {
    var matchedCount = 0
    var unknownDateCount = 0
    for item in items where mediaMatchesFilter(item, filter: filter)
        && mediaMatchesRatingFilter(item, filter: ratingFilter)
        && mediaMatchesFolderFilter(item, filter: folderFilter)
    {
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

struct MediaRatingFilterSummary: Equatable {
    // Keep the same loaded denominator and visible count as the date summary.
    let loadedCount: Int
    let matchedCount: Int
    // Unknown ratings in the selected type/date/folder, before applying a rating filter.
    let unknownRatingCount: Int
}

func mediaRatingFilterSummary(
    _ items: [CameraMediaItem],
    filter: MediaFilter,
    dateRange: MediaDateRange? = nil,
    ratingFilter: MediaRatingFilter = .all,
    folderFilter: MediaFolderFilter = .all
) -> MediaRatingFilterSummary {
    var matchedCount = 0
    var unknownRatingCount = 0
    for item in items where mediaMatchesFilter(item, filter: filter)
        && mediaMatchesDateRange(item, dateRange: dateRange)
        && mediaMatchesFolderFilter(item, filter: folderFilter)
    {
        if mediaNormalizedRating(item) == nil { unknownRatingCount += 1 }
        if mediaMatchesRatingFilter(item, filter: ratingFilter) { matchedCount += 1 }
    }
    return MediaRatingFilterSummary(
        loadedCount: items.count,
        matchedCount: matchedCount,
        unknownRatingCount: unknownRatingCount
    )
}

struct MediaFolderFilterSummary: Equatable {
    let loadedCount: Int
    let matchedCount: Int
    // Unknown folders in the selected type/date/rating, before the folder filter.
    let unknownFolderCount: Int
}

func mediaFolderFilterSummary(
    _ items: [CameraMediaItem],
    filter: MediaFilter,
    dateRange: MediaDateRange? = nil,
    ratingFilter: MediaRatingFilter = .all,
    folderFilter: MediaFolderFilter = .all
) -> MediaFolderFilterSummary {
    var matchedCount = 0
    var unknownFolderCount = 0
    for item in items where mediaMatchesFilter(item, filter: filter)
        && mediaMatchesDateRange(item, dateRange: dateRange)
        && mediaMatchesRatingFilter(item, filter: ratingFilter)
    {
        if item.folder == nil { unknownFolderCount += 1 }
        if mediaMatchesFolderFilter(item, filter: folderFilter) { matchedCount += 1 }
    }
    return MediaFolderFilterSummary(
        loadedCount: items.count,
        matchedCount: matchedCount,
        unknownFolderCount: unknownFolderCount
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
    case ratingHigh = "rating_high"
    case ratingLow = "rating_low"

    var id: String { rawValue }
    var localizationKey: String { "media_sort_\(rawValue)" }
    var systemImage: String {
        switch self {
        case .camera: "camera"
        case .newest: "calendar.badge.clock"
        case .oldest: "calendar"
        case .name: "textformat"
        case .ratingHigh: "star.fill"
        case .ratingLow: "star"
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
    dateRange: MediaDateRange? = nil,
    ratingFilter: MediaRatingFilter = .all,
    folderFilter: MediaFolderFilter = .all
) -> [CameraMediaItem] {
    let filtered = items
        .filter { item in
            mediaMatchesFilter(item, filter: filter)
                && mediaMatchesRatingFilter(item, filter: ratingFilter)
                && mediaMatchesDateRange(item, dateRange: dateRange)
                && mediaMatchesFolderFilter(item, filter: folderFilter)
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

private func mediaMatchesRatingFilter(_ item: CameraMediaItem, filter: MediaRatingFilter) -> Bool {
    let rating = mediaNormalizedRating(item)
    switch filter {
    case .all: return true
    case .unrated: return rating == 0
    case .atLeast1: return rating.map { $0 >= 1 } ?? false
    case .atLeast2: return rating.map { $0 >= 2 } ?? false
    case .atLeast3: return rating.map { $0 >= 3 } ?? false
    case .atLeast4: return rating.map { $0 >= 4 } ?? false
    case .five: return rating == 5
    case .unknown: return rating == nil
    }
}

private func mediaMatchesDateRange(_ item: CameraMediaItem, dateRange: MediaDateRange?) -> Bool {
    guard let dateRange else { return true }
    guard let captureDate = mediaCaptureDate(item) else { return false }
    return dateRange.contains(captureDate: captureDate)
}

private func mediaMatchesFolderFilter(_ item: CameraMediaItem, filter: MediaFolderFilter) -> Bool {
    switch filter {
    case .all: true
    case .unknown: item.folder == nil
    case let .folder(folder): item.folder.map { mediaFolderIdentity($0) } == mediaFolderIdentity(folder)
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
    if sort == .ratingHigh || sort == .ratingLow {
        switch (mediaNormalizedRating(left), mediaNormalizedRating(right)) {
        case let (.some(leftRating), .some(rightRating)) where leftRating != rightRating:
            let ascending = leftRating < rightRating ? ComparisonResult.orderedAscending : .orderedDescending
            return sort == .ratingHigh ? ascending.reversed : ascending
        case (.some, .none):
            return .orderedAscending
        case (.none, .some):
            return .orderedDescending
        default:
            // Both equal and unknown ratings retain the loaded camera order.
            return .orderedSame
        }
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
