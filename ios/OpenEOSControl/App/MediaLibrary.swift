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
    // Recent review is transport order, independent of gallery date/name sorting.
    // A visible item never proves which controller caused its capture.
    items.first
}

enum CaptureReviewState: String {
    case idle, capturing, searching, notReady, readFailed, available

    var localizationKey: String { "capture_review_\(rawValue)" }
}

/// Observation and read reliability describe different facts. Neither proves
/// capture provenance, absence from the card, nor failure of the shutter/STOP.
enum CaptureReviewObservation: String {
    case found
    case noCandidateObserved = "no-candidate-observed"
    case unread
}

struct CaptureReviewReadEvidence: Equatable {
    enum CompletedRead: String { case success, failure }
    private(set) var successfulReads = 0
    private(set) var failedReads = 0
    private(set) var lastCompletedRead: CompletedRead?
    var completedReads: Int { successfulReads + failedReads }

    mutating func record(_ read: CompletedRead) {
        switch read {
        case .success: successfulReads += 1
        case .failure: failedReads += 1
        }
        lastCompletedRead = read
    }
}

struct CaptureReviewOutcome: Equatable {
    let observation: CaptureReviewObservation
    let readEvidence: CaptureReviewReadEvidence

    init(readEvidence: CaptureReviewReadEvidence = .init(), found: Bool = false) {
        self.readEvidence = readEvidence
        observation = found ? .found : readEvidence.successfulReads > 0 ? .noCandidateObserved : .unread
    }

    var terminalState: CaptureReviewState {
        switch observation {
        case .found: .available
        case .noCandidateObserved: .notReady
        case .unread: readEvidence.completedReads == 0 ? .notReady : .readFailed
        }
    }

    var showsLastReadFailureWarning: Bool {
        observation == .noCandidateObserved && readEvidence.lastCompletedRead == .failure
    }
}

enum CaptureReviewKind: Equatable {
    case photo, video

    var localizationPrefix: String { self == .photo ? "capture_review" : "recording_review" }

    func localizationKey(for state: CaptureReviewState) -> String {
        localizationKey(suffix: state.rawValue)
    }

    func localizationKey(for state: CaptureReviewState, outcome: CaptureReviewOutcome) -> String {
        if state == .notReady, outcome.observation == .unread, outcome.readEvidence.completedReads == 0 {
            return localizationKey(suffix: "not_checked")
        }
        return localizationKey(for: state)
    }

    func localizationKey(suffix: String) -> String {
        "\(localizationPrefix)_\(suffix)"
    }
}

struct CaptureReviewAttempt {
    let previousIDs: Set<String>
    let kind: CaptureReviewKind
}

func selectLatestMediaItem(
    afterCaptureFrom items: [CameraMediaItem],
    previousIDs: Set<String>,
    kind: CaptureReviewKind = .photo
) -> CameraMediaItem? {
    // Visibility is not exposure provenance. Exclude every observed ID first,
    // enforce the requested kind, and retain bounded camera response order.
    selectLatestMediaItem(items.filter {
        !previousIDs.contains($0.id) && CameraMediaType.classify($0) == (kind == .video ? .video : .photo)
    })
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
    // Literal endpoints are the gallery filter's truth. The Date boundaries above
    // retain the original editor context for compatibility, never gallery zoning.
    let startDayKey: String
    let endDayKey: String

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
        guard let firstKey = MediaCaptureDate.dayKey(at: startDate, in: capturedTimeZone),
              let lastKey = MediaCaptureDate.dayKey(at: endDate, in: capturedTimeZone), firstKey <= lastKey
        else { return nil }
        startDayKey = firstKey
        endDayKey = lastKey
        self.startDate = firstDay.start
        self.endDate = lastDay.start
        // Calendar day intervals include the whole last day even across DST.
        endExclusive = lastDay.end
        self.timeZone = capturedTimeZone
    }

    // UTC carriers keep date-picker literal days intact even if the viewer zone
    // skips an entire day. They must not be used as capture instants or sort keys.
    var startDayCarrier: Date { MediaCaptureDate.dayCarrier(startDayKey)! }
    var endDayCarrier: Date { MediaCaptureDate.dayCarrier(endDayKey)! }

    func contains(dayKey: String?) -> Bool {
        guard let dayKey else { return false }
        return dayKey >= startDayKey && dayKey <= endDayKey
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
    folderFilter: MediaFolderFilter = .all,
    displayZone: TimeZone? = nil
) -> MediaDateFilterSummary {
    let zone = displayZone ?? dateRange?.timeZone ?? .current
    var matchedCount = 0
    var unknownDateCount = 0
    for item in items where mediaMatchesFilter(item, filter: filter)
        && mediaMatchesRatingFilter(item, filter: ratingFilter)
        && mediaMatchesFolderFilter(item, filter: folderFilter)
    {
        let captureDate = mediaCaptureDate(item, displayZone: zone)
        if captureDate.kind == .unknown { unknownDateCount += 1 }
        if let dateRange {
            if dateRange.contains(dayKey: captureDate.dateKey) { matchedCount += 1 }
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
    folderFilter: MediaFolderFilter = .all,
    displayZone: TimeZone? = nil
) -> MediaRatingFilterSummary {
    let zone = displayZone ?? dateRange?.timeZone ?? .current
    var matchedCount = 0
    var unknownRatingCount = 0
    for item in items where mediaMatchesFilter(item, filter: filter)
        && mediaMatchesDateRange(item, dateRange: dateRange, displayZone: zone)
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
    folderFilter: MediaFolderFilter = .all,
    displayZone: TimeZone? = nil
) -> MediaFolderFilterSummary {
    let zone = displayZone ?? dateRange?.timeZone ?? .current
    var matchedCount = 0
    var unknownFolderCount = 0
    for item in items where mediaMatchesFilter(item, filter: filter)
        && mediaMatchesDateRange(item, dateRange: dateRange, displayZone: zone)
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
    CameraMediaType.classify(item) == .video
}

func mediaIsPhoto(_ item: CameraMediaItem) -> Bool {
    CameraMediaType.classify(item) == .photo
}

private struct MediaDisplayItem {
    let loadedIndex: Int
    let item: CameraMediaItem
    let date: MediaCaptureDate
}

func mediaItemsForDisplay(
    _ items: [CameraMediaItem],
    filter: MediaFilter,
    sort: MediaSort,
    dateRange: MediaDateRange? = nil,
    ratingFilter: MediaRatingFilter = .all,
    folderFilter: MediaFolderFilter = .all,
    displayZone: TimeZone? = nil
) -> [CameraMediaItem] {
    let zone = displayZone ?? dateRange?.timeZone ?? .current
    let dateSort = sort == .newest || sort == .oldest
    let requiresDate = dateSort || dateRange != nil
    // Parse once per admitted loaded index, never inside the O(n log n) comparator.
    // Camera/name/rating sorts with no range do not parse capture metadata at all.
    let filtered = items.enumerated().compactMap { index, item -> MediaDisplayItem? in
        guard mediaMatchesFilter(item, filter: filter),
              mediaMatchesRatingFilter(item, filter: ratingFilter),
              mediaMatchesFolderFilter(item, filter: folderFilter) else { return nil }
        let date = requiresDate ? mediaCaptureDate(item, displayZone: zone) : .unknown
        if let dateRange, !dateRange.contains(dayKey: date.dateKey) { return nil }
        return MediaDisplayItem(loadedIndex: index, item: item, date: date)
    }
    guard sort != .camera else { return filtered.map(\.item) }
    return filtered.sorted { left, right in
        let compared = dateSort
            ? compareMediaCaptureDates(left.date, right.date, newest: sort == .newest)
            : compareNonDateMediaItems(left.item, right.item, sort: sort)
        return compared == .orderedSame ? left.loadedIndex < right.loadedIndex : compared == .orderedAscending
    }.map(\.item)
}

struct MediaDateGroup: Equatable {
    let dayKey: String?
    var items: [CameraMediaItem]
}

func mediaDateGroups(_ items: [CameraMediaItem], displayZone: TimeZone) -> [MediaDateGroup] {
    var groups: [MediaDateGroup] = []
    for item in items {
        let key = mediaCaptureDate(item, displayZone: displayZone).dateKey
        if !groups.isEmpty && groups[groups.count - 1].dayKey == key {
            groups[groups.count - 1].items.append(item)
        } else {
            groups.append(MediaDateGroup(dayKey: key, items: [item]))
        }
    }
    return groups
}

func mediaCaptureDate(_ item: CameraMediaItem, displayZone: TimeZone) -> MediaCaptureDate {
    MediaCaptureDate.parse(item.captureTime, displayZone: displayZone)
}

private func mediaMatchesFilter(_ item: CameraMediaItem, filter: MediaFilter) -> Bool {
    switch filter {
    case .all: true
    case .photos: mediaIsPhoto(item)
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

private func mediaMatchesDateRange(
    _ item: CameraMediaItem, dateRange: MediaDateRange?, displayZone: TimeZone
) -> Bool {
    guard let dateRange else { return true }
    return dateRange.contains(dayKey: mediaCaptureDate(item, displayZone: displayZone).dateKey)
}

private func mediaMatchesFolderFilter(_ item: CameraMediaItem, filter: MediaFolderFilter) -> Bool {
    switch filter {
    case .all: true
    case .unknown: item.folder == nil
    case let .folder(folder): item.folder.map { mediaFolderIdentity($0) } == mediaFolderIdentity(folder)
    }
}

private func compareNonDateMediaItems(
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

    // Date modes are handled by their already-parsed display rows above.
    return .orderedSame
}

func compareMediaCaptureDates(
    _ left: MediaCaptureDate, _ right: MediaCaptureDate, newest: Bool
) -> ComparisonResult {
    // A total tuple prevents mixed-precision comparison cycles. Bucket order is
    // presentation only, and says nothing about cross-precision capture chronology.
    guard let leftDay = left.dateKey else { return right.dateKey == nil ? .orderedSame : .orderedDescending }
    guard let rightDay = right.dateKey else { return .orderedAscending }
    if leftDay != rightDay {
        let ascending: ComparisonResult = leftDay < rightDay ? .orderedAscending : .orderedDescending
        return newest ? ascending.reversed : ascending
    }
    if left.kind != right.kind {
        return left.kind.sortBucket < right.kind.sortBucket ? .orderedAscending : .orderedDescending
    }
    let leftValue = left.instantMillis ?? Int64(left.floatingClockMillis ?? 0)
    let rightValue = right.instantMillis ?? Int64(right.floatingClockMillis ?? 0)
    guard leftValue != rightValue else { return .orderedSame }
    let ascending: ComparisonResult = leftValue < rightValue ? .orderedAscending : .orderedDescending
    return newest ? ascending.reversed : ascending
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

private extension ComparisonResult {
    var reversed: ComparisonResult {
        switch self {
        case .orderedAscending: .orderedDescending
        case .orderedDescending: .orderedAscending
        case .orderedSame: .orderedSame
        }
    }
}
