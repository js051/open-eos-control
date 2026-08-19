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

enum MediaFilter: String, CaseIterable, Identifiable {
    case all
    case photos
    case videos

    var id: String { rawValue }
    var localizationKey: String { "media_filter_\(rawValue)" }
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
    sort: MediaSort
) -> [CameraMediaItem] {
    let filtered = items
        .filter { item in
            switch filter {
            case .all: true
            case .photos: !mediaIsVideo(item)
            case .videos: mediaIsVideo(item)
            }
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
