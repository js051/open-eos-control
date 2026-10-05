package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.ZoneId

class MediaRatingFilterTest {
    private fun item(id: String, rating: Int?, kind: String = "image", date: String? = null) =
        CameraMediaItem(id, "$id.JPG", kind, captureTime = date, rating = rating)

    @Test fun allKeepsUnknownInvalidUnratedAndExactItemIdentities() {
        val items = listOf(item("missing", null), item("negative", -1), item("large", 6), item("zero", 0), item("five", 5))
        val result = mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.CAMERA)
        assertEquals(items, result)
        result.zip(items).forEach { (actual, original) -> assertSame(original, actual) }
    }

    @Test fun unknownIncludesMissingAndOutOfRangeButNeverZero() {
        val items = listOf(item("missing", null), item("negative", Int.MIN_VALUE), item("large", Int.MAX_VALUE), item("zero", 0))
        assertEquals(listOf("missing", "negative", "large"), filter(items, MediaRatingFilter.UNKNOWN))
        assertEquals(listOf("zero"), filter(items, MediaRatingFilter.UNRATED))
    }

    @Test fun eachStarThresholdIncludesItsBoundaryWithoutIncludingUnknownOrZero() {
        val items = listOf(item("missing", null), item("invalid", 7)) + (0..5).map { item("$it", it) }
        MediaRatingFilter.entries.filter { it.minimumStars != null }.forEach { choice ->
            assertEquals((requireNotNull(choice.minimumStars)..5).map(Int::toString), filter(items, choice))
        }
    }

    @Test fun readOnlyKnownRatingsCanBeFilteredWithoutWriteCapability() {
        val items = listOf(item("read-only", 5).copy(ratingWritable = false), item("writable-unknown", null).copy(ratingWritable = true))
        assertEquals(listOf("read-only"), filter(items, MediaRatingFilter.FIVE))
        assertEquals(listOf("writable-unknown"), filter(items, MediaRatingFilter.UNKNOWN))
    }

    @Test fun bothSortDirectionsPutUnknownLastWithStableRawJpegAndUnknownTies() {
        val items = listOf(
            item("unknown-first", null), item("pair-raw", 4).copy(name = "PAIR.CR3", kind = "raw"),
            item("zero", 0), item("pair-jpeg", 4).copy(name = "PAIR.JPG"),
            item("five", 5), item("invalid-last", 8),
        )
        assertEquals(listOf("five", "pair-raw", "pair-jpeg", "zero", "unknown-first", "invalid-last"), sorted(items, MediaSort.RATING_HIGH))
        assertEquals(listOf("zero", "pair-raw", "pair-jpeg", "five", "unknown-first", "invalid-last"), sorted(items, MediaSort.RATING_LOW))
    }

    @Test fun equalRatingsPreserveCameraOrderInsteadOfUsingNameDateOrId() {
        val items = listOf(item("z", 3, date = "invalid"), item("a", 3, date = "2026-08-14"), item("m", 3))
        listOf(MediaSort.RATING_HIGH, MediaSort.RATING_LOW).forEach { assertEquals(items.map { it.id }, sorted(items, it)) }
    }

    @Test fun typeDateAndRatingIntersectWithoutCollapsingRawJpegIds() {
        val items = listOf(
            item("pair-raw", 4, "raw", "2026-08-14T12:00:00Z"), item("pair-jpeg", 4, date = "2026-08-14T12:00:00Z"),
            item("movie", 5, "video", "2026-08-14T12:00:00Z"), item("wrong-day", 5, date = "2026-08-13T12:00:00Z"),
            item("unknown-rating", null, date = "2026-08-14T12:00:00Z"), item("unknown-date", 5),
        )
        val result = mediaItemsForDisplay(items, MediaFilter.PHOTOS, MediaSort.RATING_HIGH,
            mediaDateRangeFromInput("2026-08-14", "2026-08-14"), ZoneId.of("UTC"), MediaRatingFilter.AT_LEAST_FOUR)
        assertEquals(listOf("pair-raw", "pair-jpeg"), result.map { it.id })
        assertSame(items[0], result[0])
        assertSame(items[1], result[1])
    }

    @Test fun ratingOrderNeverGeneratesDateHeadingsOrReordersTheGrid() {
        val items = listOf(item("first", 5, date = "2026-08-13"), item("second", 1, date = "2026-08-14"))
        listOf(MediaSort.RATING_HIGH, MediaSort.RATING_LOW).forEach { sort ->
            assertEquals(listOf(MediaDateGroup(null, items)), mediaGroupsForDisplay(items, sort))
            assertEquals(emptyList<MediaDateGroup>(), mediaGroupsForDisplay(emptyList(), sort))
        }
    }

    @Test fun selectedHiddenIdsSurviveRatingFilteringAndVisibleDragSelection() {
        val items = listOf(item("hidden", 0), item("raw", 5), item("jpeg", 5))
        val visible = mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.RATING_HIGH, ratingFilter = MediaRatingFilter.FIVE)
        val (_, drag) = beginMediaSelectionDrag(visible, setOf("hidden"), "raw")
        val selected = applyMediaSelectionDrag(visible, requireNotNull(drag), 1)
        assertEquals(setOf("hidden", "raw", "jpeg"), selected)
        assertEquals(setOf("hidden"), selected - visible.map { it.id }.toSet())
        assertEquals(listOf("hidden", "raw", "jpeg"), items.filter { it.id in selected }.map { it.id })
    }

    @Test fun metadataChangesReevaluateMembershipAndOrderByExactId() {
        val original = listOf(item("raw", 4).copy(name = "PAIR.CR3"), item("jpeg", 4).copy(name = "PAIR.JPG"))
        val confirmed = original.map { if (it.id == "jpeg") it.copy(rating = 5) else it }
        assertEquals(listOf("jpeg"), filter(confirmed, MediaRatingFilter.FIVE))
        assertEquals(listOf("jpeg", "raw"), sorted(confirmed, MediaSort.RATING_HIGH))
        assertEquals(4, confirmed.single { it.id == "raw" }.rating)
    }

    @Test fun captureReviewSelectionStillUsesRecencyIndependentlyOfRating() {
        val items = listOf(item("old-five", 5, date = "2026-08-13T12:00:00Z"), item("latest-unknown", null, date = "2026-08-14T12:00:00Z"))
        assertEquals("latest-unknown", selectCaptureReviewItem(items)?.id)
    }

    private fun filter(items: List<CameraMediaItem>, filter: MediaRatingFilter) =
        mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.CAMERA, ratingFilter = filter).map { it.id }
    private fun sorted(items: List<CameraMediaItem>, sort: MediaSort) =
        mediaItemsForDisplay(items, MediaFilter.ALL, sort).map { it.id }
}
