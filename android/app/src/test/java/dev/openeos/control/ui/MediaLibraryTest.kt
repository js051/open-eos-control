package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryTest {
    @Test
    fun cameraSortPreservesTransportOrderExactly() {
        val items = listOf(
            media("ten", "IMG_10.JPG", "2026-08-13T10:00:00Z"),
            media("one", "IMG_1.JPG", "2026-08-14T10:00:00Z"),
        )

        assertEquals(items, mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.CAMERA))
    }

    @Test
    fun sortsKnownCaptureTimesBeforeUnknownAndUsesNaturalFallbackWithoutDates() {
        val items = listOf(
            media("photo-9", "IMG_9.JPG"),
            media("new", "IMG_2.JPG", "2026-08-14T10:00:00Z"),
            media("photo-10", "IMG_10.JPG"),
            media("old", "IMG_1.JPG", "2026-08-13T10:00:00Z"),
        )

        assertEquals(
            listOf("new", "old", "photo-10", "photo-9"),
            mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.NEWEST).map { it.id },
        )
        assertEquals(
            listOf("old", "new", "photo-9", "photo-10"),
            mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.OLDEST).map { it.id },
        )
    }

    @Test
    fun dateSortPreservesCameraOrderForEqualTimestamps() {
        val items = listOf(
            media("raw", "IMG_0002.CR3", "2026-08-14T10:00:00Z"),
            media("jpeg", "IMG_0002.JPG", "2026-08-14T10:00:00Z"),
        )

        assertEquals(
            listOf("raw", "jpeg"),
            mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.NEWEST).map { it.id },
        )
        assertEquals(
            listOf("raw", "jpeg"),
            mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.OLDEST).map { it.id },
        )
    }

    @Test
    fun filtersVideoByKindOrExtension() {
        val items = listOf(
            media("jpeg", "IMG_0001.JPG"),
            media("movie-kind", "CLIP_0001.bin", kind = "video"),
            media("movie-name", "CLIP_0002.MP4", kind = "other"),
        )

        assertEquals(
            setOf("movie-kind", "movie-name"),
            mediaItemsForDisplay(items, MediaFilter.VIDEOS, MediaSort.NEWEST).map { it.id }.toSet(),
        )
        assertEquals(
            listOf("jpeg"),
            mediaItemsForDisplay(items, MediaFilter.PHOTOS, MediaSort.NEWEST).map { it.id },
        )
    }

    @Test
    fun filenameSortUsesNaturalAscendingOrderRegardlessOfCaptureDate() {
        val items = listOf(
            media("ten", "IMG_10.JPG", "2026-08-14T10:00:00Z"),
            media("two", "img_2.jpg", "2026-08-15T10:00:00Z"),
            media("one", "IMG_1.JPG"),
        )

        assertEquals(
            listOf("one", "two", "ten"),
            mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.NAME).map { it.id },
        )
    }

    @Test
    fun groupsSortedItemsByCameraDateWithoutChangingOrder() {
        val items = listOf(
            media("new-a", "IMG_0003.JPG", "2026-08-14T10:00:00Z"),
            media("new-b", "IMG_0002.CR3", "2026-08-14T09:00:00+08:00"),
            media("old", "IMG_0001.JPG", "20260813T120000"),
            media("unknown", "RECOVERED.JPG"),
        )

        assertEquals(
            listOf(
                MediaDateGroup("2026-08-14", items.take(2)),
                MediaDateGroup("2026-08-13", listOf(items[2])),
                MediaDateGroup(null, listOf(items[3])),
            ),
            mediaGroupsForDisplay(items, MediaSort.NEWEST),
        )
    }

    @Test
    fun filenameSortGroupsOnlyContiguousNaturalNameInitials() {
        val items = listOf(
            media("a2", "A2.JPG"),
            media("a10", "A10.JPG"),
            media("b1", "B1.JPG"),
        )

        assertEquals(
            listOf(
                MediaDateGroup("A", items.take(2)),
                MediaDateGroup("B", listOf(items.last())),
            ),
            mediaGroupsForDisplay(items, MediaSort.NAME),
        )
    }

    @Test
    fun groupsUnknownDateItemsWhenTheyAppearFirst() {
        val items = listOf(
            media("unknown-a", "RECOVERED_1.JPG"),
            media("unknown-b", "RECOVERED_2.JPG"),
            media("dated", "IMG_1.JPG", "2026-08-14T10:00:00Z"),
        )

        assertEquals(
            listOf(
                MediaDateGroup(null, items.take(2)),
                MediaDateGroup("2026-08-14", listOf(items.last())),
            ),
            mediaGroupsForDisplay(items, MediaSort.NEWEST),
        )
    }

    @Test
    fun cameraOrderUsesOneUnlabelledGroupWithoutReordering() {
        val items = listOf(
            media("new", "IMG_2.JPG", "2026-08-14T10:00:00Z"),
            media("old", "IMG_1.JPG", "2026-08-13T10:00:00Z"),
        )

        assertEquals(
            listOf(MediaDateGroup(date = null, items = items)),
            mediaGroupsForDisplay(items, MediaSort.CAMERA),
        )
        assertEquals(emptyList<MediaDateGroup>(), mediaGroupsForDisplay(emptyList(), MediaSort.CAMERA))
    }

    @Test
    fun viewerLabelsNormalizeLocalCaptureTimeAndBinarySize() {
        assertEquals("2026-08-14 10:05", mediaCaptureTimeLabel("2026-08-14T10:05:59"))
        assertEquals(null, mediaCaptureTimeLabel("not-a-date"))
        assertEquals("0 B", mediaByteSizeLabel(0))
        assertEquals("1.5 KB", mediaByteSizeLabel(1536))
        assertEquals("2.0 MB", mediaByteSizeLabel(2 * 1024L * 1024L))
        assertEquals(null, mediaByteSizeLabel(-1))
    }

    @Test
    fun touchingCachedThumbnailMovesItToTheMostRecentPosition() {
        val cache = linkedMapOf("one" to 1, "two" to 2, "three" to 3)

        assertEquals(
            listOf("one", "three", "two"),
            touchMediaCacheEntry(cache, "two").keys.toList(),
        )
        assertEquals(cache, touchMediaCacheEntry(cache, "missing"))
    }

    private fun media(
        id: String,
        name: String,
        captureTime: String? = null,
        kind: String = "image",
    ) = CameraMediaItem(id = id, name = name, kind = kind, captureTime = captureTime)
}
