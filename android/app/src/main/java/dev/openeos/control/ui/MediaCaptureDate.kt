package dev.openeos.control.ui

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Precision is metadata, never a guessed device zone or an invented midnight instant. */
internal enum class MediaCaptureDateKind { INSTANT, FLOATING, DATE_ONLY, UNKNOWN }

internal sealed interface MediaCaptureDate {
    data class OffsetInstant(val value: Instant) : MediaCaptureDate
    data class Floating(val value: LocalDateTime) : MediaCaptureDate
    data class DateOnly(val value: LocalDate) : MediaCaptureDate
    data object Unknown : MediaCaptureDate
}

/** One projection is shared by sort, range, headings and details for a supplied zone snapshot. */
internal data class MediaCaptureDateDisplay(
    val kind: MediaCaptureDateKind,
    val day: LocalDate? = null,
    val localTimeMillis: Long? = null,
    val instantMillis: Long? = null,
    val dateTime: LocalDateTime? = null,
) {
    fun label(unknownTimeZoneLabel: String): String? = when (kind) {
        MediaCaptureDateKind.INSTANT -> dateTime?.format(MEDIA_DISPLAY_DATE_TIME)
        MediaCaptureDateKind.FLOATING -> dateTime?.format(MEDIA_DISPLAY_DATE_TIME)?.let { "$it ($unknownTimeZoneLabel)" }
        MediaCaptureDateKind.DATE_ONLY -> day?.toString()
        MediaCaptureDateKind.UNKNOWN -> null
    }
}

/** Strict Gregorian AD 1..9999, full string only. Fraction precision is intentionally milliseconds. */
internal fun parseMediaCaptureDate(raw: String?): MediaCaptureDate {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.length > 128) return MediaCaptureDate.Unknown
    return try {
        MEDIA_DATE_ONLY.matchEntire(value)?.let { match ->
            return MediaCaptureDate.DateOnly(strictDate(match.groupValues[1], match.groupValues[2], match.groupValues[3]))
        }
        val match = MEDIA_TIMESTAMP.matchEntire(value) ?: MEDIA_PTP_TIMESTAMP.matchEntire(value)
            ?: return MediaCaptureDate.Unknown
        val parts = match.groupValues
        val date = strictDate(parts[1], parts[2], parts[3])
        val milliseconds = parts.getOrNull(7).orEmpty().take(3).padEnd(3, '0').toInt()
        val time = LocalTime.of(parts[4].toInt(), parts[5].toInt(), parts[6].ifEmpty { "0" }.toInt(), milliseconds * 1_000_000)
        val local = LocalDateTime.of(date, time)
        val offsetText = parts.getOrNull(8).orEmpty()
        if (offsetText.isEmpty()) return MediaCaptureDate.Floating(local)
        val offset = ZoneOffset.of(offsetText)
        val instant = local.toInstant(offset)
        // Converting the supplied offset must not create year 0/10000 at UTC either.
        if (instant.atOffset(ZoneOffset.UTC).year !in 1..9999) return MediaCaptureDate.Unknown
        val region = parts.getOrNull(9).orEmpty()
        if (region.isNotEmpty()) {
            if (region !in ZoneId.getAvailableZoneIds()) return MediaCaptureDate.Unknown
            if (ZoneId.of(region).rules.getOffset(instant) != offset) return MediaCaptureDate.Unknown
        }
        MediaCaptureDate.OffsetInstant(instant)
    } catch (_: DateTimeException) {
        MediaCaptureDate.Unknown
    } catch (_: IllegalArgumentException) {
        MediaCaptureDate.Unknown
    }
}

internal fun MediaCaptureDate.inDisplayZone(zone: ZoneId): MediaCaptureDateDisplay = when (this) {
    is MediaCaptureDate.OffsetInstant -> {
        val local = value.atZone(zone).toLocalDateTime()
        if (local.year !in 1..9999) UNKNOWN_MEDIA_DATE_DISPLAY else MediaCaptureDateDisplay(
            kind = MediaCaptureDateKind.INSTANT, day = local.toLocalDate(),
            instantMillis = value.toEpochMilli(), dateTime = local,
        )
    }
    is MediaCaptureDate.Floating -> MediaCaptureDateDisplay(
        kind = MediaCaptureDateKind.FLOATING, day = value.toLocalDate(),
        localTimeMillis = value.toLocalTime().toNanoOfDay() / 1_000_000, dateTime = value,
    )
    is MediaCaptureDate.DateOnly -> MediaCaptureDateDisplay(MediaCaptureDateKind.DATE_ONLY, day = value)
    MediaCaptureDate.Unknown -> UNKNOWN_MEDIA_DATE_DISPLAY
}

/** A lexicographic total preorder; only the known day and within-kind key reverse. */
internal fun compareMediaCaptureDates(left: MediaCaptureDateDisplay, right: MediaCaptureDateDisplay, newest: Boolean): Int {
    if (left.kind == MediaCaptureDateKind.UNKNOWN) return if (right.kind == MediaCaptureDateKind.UNKNOWN) 0 else 1
    if (right.kind == MediaCaptureDateKind.UNKNOWN) return -1
    val direction = if (newest) -1 else 1
    val dayOrder = requireNotNull(left.day).compareTo(requireNotNull(right.day))
    if (dayOrder != 0) return direction * dayOrder
    val precisionOrder = left.kind.ordinal.compareTo(right.kind.ordinal)
    if (precisionOrder != 0) return precisionOrder
    return direction * when (left.kind) {
        MediaCaptureDateKind.INSTANT -> requireNotNull(left.instantMillis).compareTo(requireNotNull(right.instantMillis))
        MediaCaptureDateKind.FLOATING -> requireNotNull(left.localTimeMillis).compareTo(requireNotNull(right.localTimeMillis))
        MediaCaptureDateKind.DATE_ONLY, MediaCaptureDateKind.UNKNOWN -> 0
    }
}

private fun strictDate(year: String, month: String, day: String): LocalDate {
    val adYear = year.toInt()
    require(adYear in 1..9999)
    return LocalDate.of(adYear, month.toInt(), day.toInt())
}

private val UNKNOWN_MEDIA_DATE_DISPLAY = MediaCaptureDateDisplay(MediaCaptureDateKind.UNKNOWN)
private val MEDIA_DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
private val MEDIA_DATE_ONLY = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")
private val MEDIA_TIMESTAMP = Regex(
    "([0-9]{4})-([0-9]{2})-([0-9]{2})[T ]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\\.([0-9]{1,9}))?)?" +
        "(?:(Z|[+-][0-9]{2}:?[0-9]{2})(?:\\[([\\x21-\\x7E]{1,64})\\])?)?",
)
private val MEDIA_PTP_TIMESTAMP = Regex("([0-9]{4})([0-9]{2})([0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})")
