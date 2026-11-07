package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaFolder
import dev.openeos.control.data.CameraMediaItem
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Test

class MediaDateReviewContractTest {
    private val fixture = loadMediaDateReviewFixture()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    @Test fun fixtureMetadataAndFixedVectorsAreComplete() {
        assertEquals(1, fixture.schemaVersion)
        assertEquals("milliseconds-truncated", fixture.instantResolution)
        assertEquals("proleptic-gregorian-ad-1-9999", fixture.calendar)
        assertEquals(listOf("UTC", "Asia/Taipei", "America/Los_Angeles"), fixture.zones)
        assertEquals(listOf("instant", "floating", "date-only", "unknown"), fixture.kinds)
        assertEquals(listOf("instant", "floating", "date-only"), fixture.precisionOrder)
        assertEquals("loaded-order", fixture.unknownAndTies)
        assertEquals(48, fixture.vectors.size)
        assertEquals(9, fixture.sortCases.size)
        assertEquals(4, fixture.reviewCases.size)
    }

    @Test fun strictParserMatchesFixedCrossPlatformDatesAndPrecision() {
        fixture.vectors.forEach { vector ->
            val parsed = parseMediaCaptureDate(vector.raw)
            val actualKind = when (parsed) {
                is MediaCaptureDate.OffsetInstant -> "instant"
                is MediaCaptureDate.Floating -> "floating"
                is MediaCaptureDate.DateOnly -> "date-only"
                MediaCaptureDate.Unknown -> "unknown"
            }
            assertEquals(vector.id, vector.kind, actualKind)
            assertEquals(vector.id, vector.instantMillis, (parsed as? MediaCaptureDate.OffsetInstant)?.value?.toEpochMilli())
            fixture.zones.forEach { zoneName ->
                val zone = ZoneId.of(zoneName)
                val display = parsed.inDisplayZone(zone)
                val expected = vector.display.getValue(zoneName)
                assertEquals("${vector.id}/$zoneName/day", expected.dateKey, display.day?.toString())
                assertEquals("${vector.id}/$zoneName/time", expected.timeKey, display.dateTime?.format(timeFormat))
                assertEquals("${vector.id}/$zoneName/filter", expected.dateKey, vector.raw.toMediaDisplayDate(zone)?.toString())
                val item = CameraMediaItem(vector.id, "UNRELATED-2030-01-01.JPG", "image", captureTime = vector.raw)
                assertEquals(expected.dateKey, mediaGroupsForDisplay(listOf(item), MediaSort.NEWEST, zone).single().date)
                val label = mediaCaptureTimeLabel(vector.raw, zone)
                assertEquals(expected.dateKey, label?.take(10))
                if (vector.kind == "floating") assertTrue(requireNotNull(label).endsWith("(Time zone unknown)"))
                if (vector.kind == "date-only") assertEquals(expected.dateKey, label)
                expected.dateKey?.let { day ->
                    val range = requireNotNull(mediaDateRangeFromInput(day, day))
                    assertSame(item, mediaItemsForDisplay(listOf(item), MediaFilter.ALL, MediaSort.NEWEST, range, zone).single())
                }
                assertEquals(vector.raw, item.captureTime)
            }
        }
    }

    @Test fun dateSortUsesDisplayDayThenFixedPrecisionBucketsAndStableLoadedTies() {
        fixture.sortCases.forEach { case ->
            val sort = MediaSort.valueOf(case.sort.uppercase())
            val result = mediaItemsForDisplay(fixture.sortItems, MediaFilter.ALL, sort, displayZone = ZoneId.of(case.zone))
            assertEquals("${case.zone}/${case.sort}", case.expectedIds, result.map { it.id })
            result.forEach { assertSame(fixture.sortItems.single { source -> source.id == it.id }, it) }
        }
    }

    @Test fun comparatorIsAntisymmetricAndTransitiveAcrossAllKindsAndZones() {
        for (zoneName in fixture.zones) {
            val zone = ZoneId.of(zoneName)
            val values = fixture.vectors.map { parseMediaCaptureDate(it.raw).inDisplayZone(zone) }
            for (newest in listOf(false, true)) {
                for (a in values) for (b in values) {
                    val ab = compareMediaCaptureDates(a, b, newest)
                    val ba = compareMediaCaptureDates(b, a, newest)
                    assertEquals(-ab.sign(), ba.sign())
                    for (c in values) if (ab <= 0 && compareMediaCaptureDates(b, c, newest) <= 0) {
                        assertTrue(compareMediaCaptureDates(a, c, newest) <= 0)
                    }
                }
            }
        }
    }

    @Test fun explicitReviewIntentExcludesOldIdsWithoutDateSorting() {
        fixture.reviewCases.forEach { case ->
            val selected = selectCaptureReviewItem(fixture.reviewItems, MediaReviewIntent.valueOf(case.kind.uppercase()), case.previousIds)
            assertEquals(case.expectedId, selected?.id)
        }
        // General recent has no implicit photo constraint when the camera lists a video first.
        val videoFirst = fixture.reviewItems.sortedBy { if (it.kind == "video") 0 else 1 }
        assertEquals("NEW_VIDEO_FUTURE", selectCaptureReviewItem(videoFirst)?.id)
    }

    @Test fun galleryDateRatingAndFolderFiltersNeverModifyReviewOrHiddenSelectionIds() {
        val folder = CameraMediaFolder("synthetic:folder", "Synthetic folder")
        val items = fixture.reviewItems.map { it.copy(rating = 0, folder = folder) }
        val before = items.toList()
        val selectedIds = items.mapTo(linkedSetOf()) { it.id }
        val range = requireNotNull(mediaDateRangeFromInput("2000-01-01", "2000-01-02"))
        for (sort in MediaSort.entries) {
            assertTrue(mediaItemsForDisplay(items, MediaFilter.PHOTOS, sort, range, ZoneId.of("UTC"),
                MediaRatingFilter.FIVE, MediaFolderFilter.Unknown).isEmpty())
            assertEquals("NEW_PHOTO_NO_DATE", selectCaptureReviewItem(items, MediaReviewIntent.PHOTO, setOf("OLD"))?.id)
        }
        assertEquals(before, items)
        assertEquals(before.map { it.id }.toSet(), selectedIds)
    }

    @Test fun unknownDatesAndEqualSubmillisecondRawJpegTimestampsKeepLoadedOrder() {
        val items = listOf(
            CameraMediaItem("raw", "PAIR.CR3", "image", captureTime = "2026-08-14T12:00:00.123999999Z"),
            CameraMediaItem("jpeg", "PAIR.JPG", "image", captureTime = "2026-08-14T12:00:00.123000001Z"),
            CameraMediaItem("unknown-first", "B.JPG", "image"), CameraMediaItem("unknown-last", "A.JPG", "image"),
        )
        for (sort in listOf(MediaSort.NEWEST, MediaSort.OLDEST)) assertEquals(items,
            mediaItemsForDisplay(items, MediaFilter.ALL, sort, displayZone = ZoneId.of("UTC")))
    }

    @Test fun literalDstGapAndSkippedDayNeverNormalizeAndFloatingLabelsAreLocalized() {
        for (zone in fixture.zones.map(ZoneId::of) + ZoneId.of("Pacific/Apia")) {
            assertEquals("2011-12-30 12:00 (時區未知)", mediaCaptureTimeLabel("2011-12-30T12:00:00", zone, "時區未知"))
            assertEquals("2026-03-08 02:30 (Time zone unknown)", mediaCaptureTimeLabel("2026-03-08T02:30:00", zone))
        }
    }

    @Test fun yearBoundariesAndMalformedNamedZonesFailClosed() {
        for (raw in listOf("0001-01-01T00:00+18:00", "9999-12-31T23:59-18:00", "10000-01-01", "0000-01-01",
            "2026-08-14T12:34.5Z", "2026-08-14T12:34:56Z[UTC]junk", "2026-08-14T12:34:56Z[+00:00]")) {
            assertEquals(raw, MediaCaptureDate.Unknown, parseMediaCaptureDate(raw))
        }
        val low = parseMediaCaptureDate("0001-01-01T00:00Z")
        val high = parseMediaCaptureDate("9999-12-31T23:59Z")
        assertEquals(MediaCaptureDateKind.UNKNOWN, low.inDisplayZone(ZoneId.of("America/Los_Angeles")).kind)
        assertEquals(MediaCaptureDateKind.UNKNOWN, high.inDisplayZone(ZoneId.of("Asia/Taipei")).kind)
        assertEquals("0001-01-01", low.inDisplayZone(ZoneId.of("UTC")).day.toString())
        assertEquals("9999-12-31", high.inDisplayZone(ZoneId.of("UTC")).day.toString())
    }

    @Test fun aZoneSnapshotChangesEveryInstantProjectionButNeverFloatingOrDateOnly() {
        val items = listOf(
            CameraMediaItem("instant", "I.JPG", "image", captureTime = "2026-08-13T17:30Z"),
            CameraMediaItem("floating", "F.JPG", "image", captureTime = "2026-08-14T00:30"),
            CameraMediaItem("date", "D.JPG", "image", captureTime = "2026-08-14"),
        )
        val range = requireNotNull(mediaDateRangeFromInput("2026-08-14", "2026-08-14"))
        assertEquals(listOf("floating", "date"), mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.NEWEST, range, ZoneId.of("UTC")).map { it.id })
        assertEquals(listOf("instant", "floating", "date"), mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.NEWEST, range, ZoneId.of("Asia/Taipei")).map { it.id })
        assertEquals("2026-08-14 01:30", mediaCaptureTimeLabel(items[0].captureTime, ZoneId.of("Asia/Taipei")))
    }

    private fun Int.sign() = compareTo(0)
}
