package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.isVideoMedia
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

enum class MediaFilter { ALL, PHOTOS, VIDEOS }

enum class MediaSort { NEWEST, OLDEST, NAME }

data class MediaDateGroup(
    val date: String?,
    val items: List<CameraMediaItem>,
)

internal fun <Value : Any> touchMediaCacheEntry(values: Map<String, Value>, id: String): Map<String, Value> {
    val value = values[id] ?: return values
    return values.filterKeys { it != id } + (id to value)
}

internal fun mediaItemsForDisplay(
    items: List<CameraMediaItem>,
    filter: MediaFilter,
    sort: MediaSort,
): List<CameraMediaItem> {
    val filtered = items.filter { item ->
        when (filter) {
            MediaFilter.ALL -> true
            MediaFilter.PHOTOS -> !item.isVideo
            MediaFilter.VIDEOS -> item.isVideo
        }
    }
    return filtered.sortedWith { left, right ->
        compareMediaItems(left, right, sort)
    }
}

internal val CameraMediaItem.isVideo: Boolean
    get() = isVideoMedia

internal fun mediaGroupsForDisplay(items: List<CameraMediaItem>, sort: MediaSort): List<MediaDateGroup> {
    val groups = mutableListOf<MediaDateGroup>()
    items.forEach { item ->
        val heading = if (sort == MediaSort.NAME) {
            item.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        } else {
            item.mediaDate
        }
        if (groups.isNotEmpty() && groups.last().date == heading) {
            val last = groups.removeAt(groups.lastIndex)
            groups += last.copy(items = last.items + item)
        } else {
            groups += MediaDateGroup(date = heading, items = listOf(item))
        }
    }
    return groups
}

private val CameraMediaItem.mediaDate: String?
    get() = captureTime?.trim()?.let { value ->
        when {
            ISO_DATE_PREFIX.matchesAt(value, 0) -> value.take(10)
            COMPACT_DATE_PREFIX.matchesAt(value, 0) ->
                "${value.take(4)}-${value.substring(4, 6)}-${value.substring(6, 8)}"
            else -> null
        }
    }

private fun compareMediaItems(left: CameraMediaItem, right: CameraMediaItem, sort: MediaSort): Int {
    if (sort == MediaSort.NAME) {
        return naturalCompare(left.name, right.name).takeIf { it != 0 }
            ?: left.id.compareTo(right.id)
    }
    val leftTime = left.captureTime.toMediaInstant()
    val rightTime = right.captureTime.toMediaInstant()
    if (leftTime != null || rightTime != null) {
        if (leftTime == null) return 1
        if (rightTime == null) return -1
        val compared = leftTime.compareTo(rightTime)
        if (compared != 0) return if (sort == MediaSort.NEWEST) -compared else compared
    }
    val nameCompared = naturalCompare(left.name, right.name)
    if (nameCompared != 0) return if (sort == MediaSort.NEWEST) -nameCompared else nameCompared
    return left.id.compareTo(right.id)
}

private fun String?.toMediaInstant(): Instant? {
    val value = this?.trim().orEmpty()
    if (value.isEmpty()) return null
    return parseDate { Instant.parse(value) }
        ?: parseDate { OffsetDateTime.parse(value).toInstant() }
        ?: parseDate { ZonedDateTime.parse(value).toInstant() }
        ?: LOCAL_DATE_TIME_FORMATS.firstNotNullOfOrNull { formatter ->
            parseDate { LocalDateTime.parse(value, formatter).atZone(ZoneId.systemDefault()).toInstant() }
        }
}

private inline fun parseDate(block: () -> Instant): Instant? = try {
    block()
} catch (_: DateTimeParseException) {
    null
}

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

private val LOCAL_DATE_TIME_FORMATS = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"),
)
private val NATURAL_PART = Regex("\\d+|\\D+")
private val ISO_DATE_PREFIX = Regex("\\d{4}-\\d{2}-\\d{2}")
private val COMPACT_DATE_PREFIX = Regex("\\d{8}")
