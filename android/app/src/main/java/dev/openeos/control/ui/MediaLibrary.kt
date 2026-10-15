package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaFolder
import dev.openeos.control.data.isVideoMedia
import dev.openeos.control.data.isPhotoMedia
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

enum class MediaFilter { ALL, PHOTOS, VIDEOS }

enum class MediaSort { CAMERA, NEWEST, OLDEST, NAME, RATING_HIGH, RATING_LOW }

/** A loaded-folder view never changes the camera recording directory or fetches metadata. */
sealed interface MediaFolderFilter {
    data object All : MediaFolderFilter
    data object Unknown : MediaFolderFilter
    data class Folder(val folder: CameraMediaFolder) : MediaFolderFilter
}

internal fun MediaFolderFilter.includes(item: CameraMediaItem): Boolean = when (this) {
    MediaFolderFilter.All -> true
    MediaFolderFilter.Unknown -> item.folder == null
    is MediaFolderFilter.Folder -> item.folder?.id == folder.id
}

internal fun loadedMediaFolders(items: List<CameraMediaItem>): List<CameraMediaFolder> =
    items.mapNotNull(CameraMediaItem::folder).distinctBy(CameraMediaFolder::id)
        .sortedWith(compareBy<CameraMediaFolder> { it.label.lowercase(Locale.ROOT) }.thenBy { it.id })

/** Only confirmed, loaded 0..5 ratings are known. Unknown is never the same as unrated. */
enum class MediaRatingFilter(val minimumStars: Int? = null) {
    ALL, UNRATED, AT_LEAST_ONE(1), AT_LEAST_TWO(2), AT_LEAST_THREE(3), AT_LEAST_FOUR(4), FIVE(5), UNKNOWN;

    internal fun includes(item: CameraMediaItem): Boolean = when (this) {
        ALL -> true
        UNRATED -> item.knownRating == 0
        UNKNOWN -> item.knownRating == null
        else -> item.knownRating?.let { it >= requireNotNull(minimumStars) } == true
    }
}

internal val CameraMediaItem.knownRating: Int?
    get() = rating?.takeIf { it in 0..5 }

internal val MediaSort.isRatingOrder: Boolean
    get() = this == MediaSort.RATING_HIGH || this == MediaSort.RATING_LOW

internal val MediaSort.hasGroupHeadings: Boolean
    get() = this != MediaSort.CAMERA && !isRatingOrder

/** Displayed media dates, inclusive at both ends, in the same zone as gallery details. */
data class MediaDateRange(val start: LocalDate, val end: LocalDate) {
    init { require(!end.isBefore(start)) }

    internal fun includes(captureTime: String?, displayZone: ZoneId): Boolean = captureTime.toMediaDisplayDate(displayZone)?.let {
        !it.isBefore(start) && !it.isAfter(end)
    } ?: false
}

internal fun mediaDateRangeFromInput(start: String, end: String): MediaDateRange? {
    val first = start.toStrictMediaDate() ?: return null
    val last = end.toStrictMediaDate() ?: return null
    return if (last.isBefore(first)) null else MediaDateRange(first, last)
}

private fun String.toStrictMediaDate(): LocalDate? {
    if (!ISO_DATE.matches(this)) return null
    return parseMediaDate { LocalDate.parse(this, DateTimeFormatter.ISO_LOCAL_DATE) }?.takeIf { it.year in 1..9999 }
}

/** Missing dates and malformed full timestamps stay unknown, never normalized from a prefix. */
internal fun String?.toMediaDisplayDate(displayZone: ZoneId): LocalDate? =
    parseMediaCaptureDate(this).inDisplayZone(displayZone).day

private inline fun <T> parseMediaDate(block: () -> T): T? = try {
    block()
} catch (_: DateTimeParseException) {
    null
}

data class MediaDateGroup(
    val date: String?,
    val items: List<CameraMediaItem>,
)

data class MediaSelectionDrag(
    val anchorIndex: Int,
    val baseline: Set<String>,
    val selecting: Boolean,
)

internal fun beginMediaSelectionDrag(
    items: List<CameraMediaItem>,
    selectedIds: Set<String>,
    itemId: String,
): Pair<Set<String>, MediaSelectionDrag?> {
    val anchorIndex = items.indexOfFirst { it.id == itemId }
    if (anchorIndex < 0) return selectedIds to null
    val drag = MediaSelectionDrag(
        anchorIndex = anchorIndex,
        baseline = selectedIds,
        selecting = itemId !in selectedIds,
    )
    return applyMediaSelectionDrag(items, drag, anchorIndex) to drag
}

internal fun applyMediaSelectionDrag(
    items: List<CameraMediaItem>,
    drag: MediaSelectionDrag,
    currentIndex: Int,
): Set<String> {
    if (items.isEmpty() || currentIndex !in items.indices || drag.anchorIndex !in items.indices) {
        return drag.baseline
    }
    val range = if (drag.anchorIndex <= currentIndex) {
        drag.anchorIndex..currentIndex
    } else {
        currentIndex..drag.anchorIndex
    }
    val rangeIds = range.mapTo(hashSetOf()) { items[it].id }
    return if (drag.selecting) drag.baseline + rangeIds else drag.baseline - rangeIds
}

internal fun toggleMediaSelection(selectedIds: Set<String>, itemId: String): Set<String> =
    if (itemId in selectedIds) selectedIds - itemId else selectedIds + itemId

internal fun <Value : Any> touchMediaCacheEntry(values: Map<String, Value>, id: String): Map<String, Value> {
    val value = values[id] ?: return values
    return values.filterKeys { it != id } + (id to value)
}

internal fun mediaItemsForDisplay(
    items: List<CameraMediaItem>,
    filter: MediaFilter,
    sort: MediaSort,
    dateRange: MediaDateRange? = null,
    displayZone: ZoneId = ZoneId.systemDefault(),
    ratingFilter: MediaRatingFilter = MediaRatingFilter.ALL,
    folderFilter: MediaFolderFilter = MediaFolderFilter.All,
): List<CameraMediaItem> {
    val filtered = items.filter { item ->
        folderFilter.includes(item) && ratingFilter.includes(item) &&
            (dateRange == null || dateRange.includes(item.captureTime, displayZone)) && when (filter) {
            MediaFilter.ALL -> true
            MediaFilter.PHOTOS -> item.isPhoto
            MediaFilter.VIDEOS -> item.isVideo
        }
    }
    if (sort == MediaSort.CAMERA) return filtered
    // Cache by loaded index, preserving even duplicate rows without conflating IDs.
    val times = if (sort == MediaSort.NEWEST || sort == MediaSort.OLDEST) {
        filtered.map { parseMediaCaptureDate(it.captureTime).inDisplayZone(displayZone) }
    } else emptyList()
    return filtered.withIndex().sortedWith { left, right ->
        compareMediaItems(left.value, right.value, sort, times.getOrNull(left.index), times.getOrNull(right.index))
            .takeIf { it != 0 } ?: left.index.compareTo(right.index)
    }.map { it.value }
}

internal val CameraMediaItem.isPhoto: Boolean
    get() = isPhotoMedia

internal val CameraMediaItem.isVideo: Boolean
    get() = isVideoMedia

internal fun mediaGroupsForDisplay(
    items: List<CameraMediaItem>,
    sort: MediaSort,
    displayZone: ZoneId = ZoneId.systemDefault(),
): List<MediaDateGroup> {
    if (items.isEmpty()) return emptyList()
    if (!sort.hasGroupHeadings) return listOf(MediaDateGroup(date = null, items = items))
    val groups = mutableListOf<MediaDateGroup>()
    var groupItems = mutableListOf<CameraMediaItem>()
    items.forEach { item ->
        val heading = if (sort == MediaSort.NAME) {
            item.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        } else {
            item.captureTime.toMediaDisplayDate(displayZone)?.toString()
        }
        if (groups.isNotEmpty() && groups.last().date == heading) {
            groupItems += item
        } else {
            groupItems = mutableListOf(item)
            groups += MediaDateGroup(date = heading, items = groupItems)
        }
    }
    return groups
}

private fun compareMediaItems(
    left: CameraMediaItem,
    right: CameraMediaItem,
    sort: MediaSort,
    leftTime: MediaCaptureDateDisplay?,
    rightTime: MediaCaptureDateDisplay?,
): Int {
    if (sort.isRatingOrder) {
        val leftRating = left.knownRating
        val rightRating = right.knownRating
        if (leftRating == null) return if (rightRating == null) 0 else 1
        if (rightRating == null) return -1
        return if (sort == MediaSort.RATING_HIGH) rightRating.compareTo(leftRating) else leftRating.compareTo(rightRating)
    }
    if (sort == MediaSort.NAME) {
        return naturalCompare(left.name, right.name).takeIf { it != 0 }
            ?: left.id.compareTo(right.id)
    }
    return compareMediaCaptureDates(requireNotNull(leftTime), requireNotNull(rightTime), newest = sort == MediaSort.NEWEST)
}

internal fun mediaCaptureTimeLabel(
    value: String?,
    displayZone: ZoneId = ZoneId.systemDefault(),
    unknownTimeZoneLabel: String = "Time zone unknown",
): String? = parseMediaCaptureDate(value).inDisplayZone(displayZone).label(unknownTimeZoneLabel)

internal fun mediaByteSizeLabel(value: Long?): String? {
    val bytes = value?.takeIf { it >= 0 } ?: return null
    val (amount, unit) = when {
        bytes < KIBIBYTE -> bytes.toDouble() to "B"
        bytes < MEBIBYTE -> bytes.toDouble() / KIBIBYTE to "KB"
        bytes < GIBIBYTE -> bytes.toDouble() / MEBIBYTE to "MB"
        else -> bytes.toDouble() / GIBIBYTE to "GB"
    }
    return if (unit == "B") {
        "${bytes.toString()} $unit"
    } else {
        String.format(Locale.ROOT, "%.1f %s", amount, unit)
    }
}

internal fun mediaDimensionsLabel(item: CameraMediaItem): String? {
    val width = item.widthPixels?.takeIf { it > 0 } ?: return null
    val height = item.heightPixels?.takeIf { it > 0 } ?: return null
    return "$width x $height"
}

internal fun mediaContentTypeLabel(value: String?): String? = value
    ?.substringBefore(';')
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }

/** Only explicit-offset metadata can represent a real instant. */
internal fun String?.toMediaInstant(): Instant? = (parseMediaCaptureDate(this) as? MediaCaptureDate.OffsetInstant)?.value

private fun naturalCompare(left: String, right: String): Int {
    val leftParts = NATURAL_PART.findAll(left.lowercase()).map { it.value }.toList()
    val rightParts = NATURAL_PART.findAll(right.lowercase()).map { it.value }.toList()
    for (index in 0 until minOf(leftParts.size, rightParts.size)) {
        val leftPart = leftParts[index]
        val rightPart = rightParts[index]
        val compared = if (leftPart.firstOrNull()?.isDigit() == true && rightPart.firstOrNull()?.isDigit() == true) {
            BigInteger(leftPart).compareTo(BigInteger(rightPart))
        } else {
            leftPart.compareTo(rightPart)
        }
        if (compared != 0) return compared
    }
    return leftParts.size.compareTo(rightParts.size).takeIf { it != 0 } ?: left.compareTo(right, ignoreCase = true)
}

private val NATURAL_PART = Regex("\\d+|\\D+")
private val ISO_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private const val KIBIBYTE = 1024L
private const val MEBIBYTE = KIBIBYTE * 1024L
private const val GIBIBYTE = MEBIBYTE * 1024L
