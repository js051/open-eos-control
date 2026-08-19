package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaLibraryTest {
    @Test
    fun captureReviewSelectsNewestKnownCaptureTime() {
        val items = listOf(
            media("old", "IMG_0001.JPG", "2026-08-13T10:00:00Z"),
            media("new", "IMG_0002.JPG", "2026-08-14T10:00:00Z"),
        )

        assertEquals("new", selectCaptureReviewItem(items)?.id)
    }

    @Test
    fun captureReviewPreservesCameraOrderWhenCaptureTimesAreUnavailable() {
        val items = listOf(
            media("camera-newest", "IMG_4915.JPG"),
            media("camera-older", "IMG_4914.JPG"),
        )

        assertEquals("camera-newest", selectCaptureReviewItem(items)?.id)
        assertEquals(null, selectCaptureReviewItem(emptyList()))
    }

    @Test
    fun captureReviewRetriesUntilTheCameraPublishesANewItem() = runTest {
        val old = media("old", "IMG_0001.JPG")
        val new = media("new", "IMG_0002.JPG")
        var calls = 0

        val selected = awaitCaptureReviewItem("old", longArrayOf(0, 0)) {
            calls += 1
            if (calls < 3) listOf(old) else listOf(new, old)
        }

        assertEquals("new", selected?.id)
        assertEquals(3, calls)
    }

    @Test
    fun captureReviewDoesNotSwallowCoroutineCancellation() {
        assertThrows(CancellationException::class.java) {
            runTest {
                awaitCaptureReviewItem(null, longArrayOf(0)) { throw CancellationException("stop") }
            }
        }
    }

    @Test
    fun mediaThumbnailDecodeSampleBoundsTheLongestEdge() {
        assertEquals(1, mediaThumbnailSampleSize(320, 240))
        assertEquals(8, mediaThumbnailSampleSize(4_000, 3_000))
        assertEquals(32, mediaThumbnailSampleSize(8_000, 12_000))
        assertEquals(1, mediaThumbnailSampleSize(0, 12_000))
    }

    @Test
    fun cameraSortPreservesTransportOrderExactly() {
        val items = listOf(
            media("ten", "IMG_10.JPG", "2026-08-13T10:00:00Z"),
            media("one", "IMG_1.JPG", "2026-08-14T10:00:00Z"),
        )

        assertEquals(items, mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.CAMERA))
    }

    @Test
    fun sortsKnownCaptureTimesBeforeUnknownAndPreservesCameraOrderWithoutDates() {
        val items = listOf(
            media("photo-9", "IMG_9.JPG"),
            media("new", "IMG_2.JPG", "2026-08-14T10:00:00Z"),
            media("photo-10", "IMG_10.JPG"),
            media("old", "IMG_1.JPG", "2026-08-13T10:00:00Z"),
        )

        assertEquals(
            listOf("new", "old", "photo-9", "photo-10"),
            mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.NEWEST).map { it.id },
        )
        assertEquals(
            listOf("old", "new", "photo-10", "photo-9"),
            mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.OLDEST).map { it.id },
        )
    }

    @Test
    fun timestampFreePhotoAndVideoItemsKeepDescendingCameraOrder() {
        val items = listOf(
            media("new-photo", "IMG_4915.JPG"),
            media("new-video", "MVI_0013.MP4", kind = "video"),
            media("old-photo", "IMG_4914.JPG"),
        )

        assertEquals(
            items,
            mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.NEWEST),
        )
        assertEquals(
            items.reversed(),
            mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.OLDEST),
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
        val item = CameraMediaItem(
            id = "photo",
            name = "IMG_0001.JPG",
            kind = "image",
            contentType = "image/jpeg; charset=binary",
            widthPixels = 6000,
            heightPixels = 4000,
        )
        assertEquals("6000 x 4000", mediaDimensionsLabel(item))
        assertEquals("image/jpeg", mediaContentTypeLabel(item.contentType))
        assertEquals(null, mediaDimensionsLabel(item.copy(heightPixels = 0)))
        assertEquals(null, mediaContentTypeLabel("application/octet-stream"))
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

    @Test
    fun recentEventItemsReplaceDuplicatesWithoutDiscardingTheLoadedLibrary() {
        val updated = media("shared", "IMG_0100.JPG")
        val existing = listOf(
            media("shared", "IMG_0100.JPG", "2026-08-13T10:00:00Z"),
            media("old", "IMG_0099.JPG"),
        )

        assertEquals(
            listOf(media("new", "IMG_0101.JPG"), updated, existing.last()),
            mergeRecentMedia(listOf(media("new", "IMG_0101.JPG"), updated), existing),
        )
    }

    @Test
    fun thumbnailRetriesOnlyTransportFailures() {
        assertEquals(true, isRetryableMediaThumbnailFailure(IOException("timeout")))
        assertEquals(false, isRetryableMediaThumbnailFailure(IllegalStateException("HTTP 404")))
    }

    private fun media(
        id: String,
        name: String,
        captureTime: String? = null,
        kind: String = "image",
    ) = CameraMediaItem(id = id, name = name, kind = kind, captureTime = captureTime)
}
