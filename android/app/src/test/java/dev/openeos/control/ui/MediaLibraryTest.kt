package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryTest {
    @Test
    fun sortsKnownCaptureTimesBeforeUnknownAndUsesNaturalFilenameFallback() {
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
            mediaDateGroups(items),
        )
    }

    private fun media(
        id: String,
        name: String,
        captureTime: String? = null,
        kind: String = "image",
    ) = CameraMediaItem(id = id, name = name, kind = kind, captureTime = captureTime)
}
