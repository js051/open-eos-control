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
import java.util.Locale

enum class MediaFilter { ALL, PHOTOS, VIDEOS }

enum class MediaSort { CAMERA, NEWEST, OLDEST, NAME }

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
    if (sort == MediaSort.CAMERA) return filtered
    return filtered.withIndex().sortedWith { left, right ->
        compareMediaItems(left.value, right.value, sort)
            .takeIf { it != 0 }
            ?: if (
                sort == MediaSort.OLDEST &&
                left.value.captureTime.toMediaInstant() == null &&
                right.value.captureTime.toMediaInstant() == null
            ) {
                right.index.compareTo(left.index)
            } else {
                left.index.compareTo(right.index)
            }
    }.map { it.value }
}

internal fun selectCaptureReviewItem(items: List<CameraMediaItem>): CameraMediaItem? =
    mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.NEWEST).firstOrNull()

internal val CameraMediaItem.isVideo: Boolean
    get() = isVideoMedia

internal fun mediaGroupsForDisplay(items: List<CameraMediaItem>, sort: MediaSort): List<MediaDateGroup> {
    if (items.isEmpty()) return emptyList()
    if (sort == MediaSort.CAMERA) return listOf(MediaDateGroup(date = null, items = items))
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
        return 0
    }
    // Canon's descending contents order is better evidence than comparing unrelated
    // IMG_/MVI_ prefixes when the listing does not include capture timestamps.
    return 0
}

internal fun mediaCaptureTimeLabel(value: String?): String? = value.toMediaInstant()
    ?.atZone(ZoneId.systemDefault())
    ?.format(MEDIA_DISPLAY_DATE_TIME)

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
private val MEDIA_DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val NATURAL_PART = Regex("\\d+|\\D+")
private val ISO_DATE_PREFIX = Regex("\\d{4}-\\d{2}-\\d{2}")
private val COMPACT_DATE_PREFIX = Regex("\\d{8}")
private const val KIBIBYTE = 1024L
private const val MEBIBYTE = KIBIBYTE * 1024L
private const val GIBIBYTE = MEBIBYTE * 1024L
