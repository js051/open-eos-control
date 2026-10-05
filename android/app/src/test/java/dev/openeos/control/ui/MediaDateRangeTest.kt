package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaDateRangeTest {
    private val utc = ZoneId.of("UTC")
    private val taipei = ZoneId.of("Asia/Taipei")

    @Test fun rejectsInvalidFullTimestampsRatherThanAcceptingTheirDatePrefix() {
        listOf(null, "", "unknown", "2026-02-29T12:00:00Z", "2026-04-31 12:00:00",
            "20260229T120000", "2026-08-14T25:00:00", "2026-08-14junk", "20260814junk",
            "2026-08-14T12:00:00+25:00", "2026-08-14T23:59:60Z", "2026-08-14 24:00:00")
            .forEach { value ->
                assertNull("Filter date for $value", value.toMediaDisplayDate(utc))
                assertNull("Detail date for $value", mediaCaptureTimeLabel(value, utc))
            }
    }

    @Test fun strictlySupportsLeapDaysAndAllExistingTimestampShapes() {
        listOf("2024-02-29T12:34:56Z", "2024-02-29T12:34:56+00:00", "2024-02-29T12:34:56",
            "2024-02-29 12:34:56", "20240229T123456", "2024-02-29T12:34:56.123456Z",
            "2024-02-29T12:34:56Z[UTC]", " 2024-02-29T12:34:56Z ")
            .forEach { value ->
                assertEquals(value, LocalDate.parse("2024-02-29"), value.toMediaDisplayDate(utc))
                assertEquals(value, "2024-02-29 12:34", mediaCaptureTimeLabel(value, utc))
            }
    }

    @Test fun dateOnlyNeverInventsAMidnightOrZone() {
        for (zone in listOf(utc, taipei, ZoneId.of("America/Los_Angeles"))) {
            assertEquals(LocalDate.parse("2026-08-14"), "2026-08-14".toMediaDisplayDate(zone))
            assertEquals("2026-08-14", mediaCaptureTimeLabel("2026-08-14", zone))
        }
    }

    @Test fun offsetAndUtcFileTimestampsUseTheSameDayInFilterGroupsAndDetails() {
        listOf(
            Triple("2026-08-14T00:30:00+08:00", utc, "2026-08-13"),
            Triple("2026-08-14T23:30:00-07:00", utc, "2026-08-15"),
            Triple("2026-08-13T16:30:00Z", taipei, "2026-08-14"),
            Triple("2026-09-01T00:30:00Z", ZoneId.of("America/Los_Angeles"), "2026-08-31"),
        ).forEach { (timestamp, zone, date) ->
            val item = media("one", timestamp)
            val range = requireNotNull(mediaDateRangeFromInput(date, date))
            assertEquals(listOf(item), mediaItemsForDisplay(listOf(item), MediaFilter.ALL, MediaSort.CAMERA, range, zone))
            assertEquals(date, mediaGroupsForDisplay(listOf(item), MediaSort.NEWEST, zone).single().date)
            assertEquals(date, mediaCaptureTimeLabel(timestamp, zone)?.take(10))
        }
    }

    @Test fun noOffsetUsesExistingDeviceLocalSemanticsIncludingDstGap() {
        val zone = ZoneId.of("Pacific/Apia")
        // The device-local interpretation normalizes a skipped local day exactly as existing details did.
        val timestamp = "2011-12-30T12:00:00"
        assertEquals("2011-12-31", timestamp.toMediaDisplayDate(zone)?.toString())
        assertEquals("2011-12-31 12:00", mediaCaptureTimeLabel(timestamp, zone))
        assertEquals("2026-08-14", "20260814T003000".toMediaDisplayDate(taipei)?.toString())
    }

    @Test fun validatesBothClosedEndpointsWithoutTurningInvalidInputIntoAll() {
        listOf("" to "2026-08-14", "2026-08-14" to "", "2026-08-15" to "2026-08-14",
            "2026-02-29" to "2026-03-01", "2026-8-01" to "2026-08-14",
            "2026-08-14T00:00:00Z" to "2026-08-14", " 2026-08-14" to "2026-08-14")
            .forEach { (start, end) -> assertNull(mediaDateRangeFromInput(start, end)) }
        assertEquals(MediaDateRange(LocalDate.parse("2024-02-29"), LocalDate.parse("2024-02-29")),
            mediaDateRangeFromInput("2024-02-29", "2024-02-29"))
        assertThrows(IllegalArgumentException::class.java) {
            MediaDateRange(LocalDate.parse("2026-08-15"), LocalDate.parse("2026-08-14"))
        }
    }

    @Test fun includesBothWholeDaysAndExcludesUnknownWithoutChangingItemIdentity() {
        val items = listOf(
            media("before", "2026-08-13T23:59:59.999Z"),
            media("first", "2026-08-14T00:00:00Z"),
            media("last", "2026-08-15T23:59:59.999Z"),
            media("after", "2026-08-16T00:00:00Z"),
            media("unknown", null),
            media("invalid", "2026-08-14junk"),
        )
        val range = requireNotNull(mediaDateRangeFromInput("2026-08-14", "2026-08-15"))
        val result = mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.CAMERA, range, utc)
        assertEquals(listOf("first", "last"), result.map { it.id })
        assertSame(items[1], result[0])
        assertSame(items[2], result[1])
        assertEquals(items, mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.CAMERA, null, utc))
    }

    @Test fun sameDayPreservesRawJpegIdsTypeFilterAndExistingSortOrder() {
        val items = listOf(
            media("raw", "2026-08-14T10:00:00Z", "PAIR.CR3"),
            media("jpeg", "2026-08-14T10:00:00Z", "PAIR.JPG"),
            media("video", "2026-08-14T11:00:00Z", "VIDEO.MP4"),
            media("outside", "2026-08-15T10:00:00Z"),
        )
        val range = requireNotNull(mediaDateRangeFromInput("2026-08-14", "2026-08-14"))
        for (sort in MediaSort.entries) {
            val full = mediaItemsForDisplay(items, MediaFilter.ALL, sort, displayZone = utc)
            val filtered = mediaItemsForDisplay(items, MediaFilter.ALL, sort, range, utc)
            assertEquals(full.filter { it.id != "outside" }, filtered)
            assertEquals(listOf("raw", "jpeg"), mediaItemsForDisplay(items, MediaFilter.PHOTOS, sort, range, utc).map { it.id })
            assertEquals(listOf("video"), mediaItemsForDisplay(items, MediaFilter.VIDEOS, sort, range, utc).map { it.id })
        }
    }

    @Test fun unknownGroupsUseStrictValidationWhileCaptureReviewRemainsIndependent() {
        val invalid = media("invalid", "2026-08-14garbage")
        assertNull(mediaGroupsForDisplay(listOf(invalid), MediaSort.NEWEST, utc).single().date)
        val latest = media("latest", "2026-08-15T12:00:00Z")
        val older = media("older", "2026-08-14T12:00:00Z")
        val items = listOf(latest, older)
        assertEquals(listOf(older), mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.NEWEST,
            mediaDateRangeFromInput("2026-08-14", "2026-08-14"), utc))
        assertSame(latest, selectCaptureReviewItem(items))
    }

    private fun media(id: String, time: String?, name: String = "$id.JPG") =
        CameraMediaItem(id, name, "image", captureTime = time)
}
