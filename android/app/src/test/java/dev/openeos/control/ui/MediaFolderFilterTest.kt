package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaFolder
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.ZoneId

class MediaFolderFilterTest {
    private val first = CameraMediaFolder("native:card-a/DCIM/100EOS", "card-a/DCIM/100EOS")
    private val second = CameraMediaFolder("native:card-b/DCIM/100EOS", "card-b/DCIM/100EOS")
    private val one = CameraMediaItem("a-jpg", "SYNTHETIC.JPG", "image", captureTime = "2026-10-04", rating = 4, folder = first)
    private val raw = CameraMediaItem("a-raw", "SYNTHETIC.CR3", "raw", captureTime = "2026-10-04", rating = 4, folder = first)
    private val two = CameraMediaItem("b-jpg", "SYNTHETIC.JPG", "image", captureTime = "2026-10-05", rating = 0, folder = second)
    private val movie = CameraMediaItem("a-mp4", "SYNTHETIC.MP4", "video", captureTime = "2026-10-04", folder = first)
    private val unknown = CameraMediaItem("opaque-legacy", "LEGACY.JPG", "image", captureTime = "2026-10-04")
    private val items = listOf(two, one, raw, movie, unknown)

    @Test fun duplicateFolderBasenamesAndFilenamesOnDifferentCardsRemainDistinct() {
        assertEquals(listOf("a-jpg", "a-raw", "a-mp4"), display(MediaFolderFilter.Folder(first)))
        assertEquals(listOf("b-jpg"), display(MediaFolderFilter.Folder(second)))
        assertEquals(items.map { it.id }, display(MediaFolderFilter.All))
    }

    @Test fun unknownMetadataIsNotAnyKnownFolderAndCanBeBrowsed() {
        assertEquals(listOf("opaque-legacy"), display(MediaFolderFilter.Unknown))
        assertEquals(emptyList<String>(), mediaItemsForDisplay(listOf(unknown), MediaFilter.ALL, MediaSort.CAMERA,
            folderFilter = MediaFolderFilter.Folder(first)).map { it.id })
    }

    @Test fun folderDateRatingAndTypeAreAnIntersectionWithoutLosingRawCompanion() {
        val date = requireNotNull(mediaDateRangeFromInput("2026-10-04", "2026-10-04"))
        val result = mediaItemsForDisplay(items, MediaFilter.PHOTOS, MediaSort.NAME, date, ZoneId.of("UTC"),
            MediaRatingFilter.AT_LEAST_FOUR, MediaFolderFilter.Folder(first))
        assertEquals(listOf("a-raw", "a-jpg"), result.map { it.id })
        assertEquals(listOf("a-mp4"), mediaItemsForDisplay(items, MediaFilter.VIDEOS, MediaSort.CAMERA,
            folderFilter = MediaFolderFilter.Folder(first)).map { it.id })
        assertEquals(5, items.size)
    }

    @Test fun folderIdentityNotLabelControlsMembership() {
        val sameLabelDifferentId = CameraMediaFolder("different-camera-folder", first.label)
        assertEquals(emptyList<String>(), display(MediaFolderFilter.Folder(sameLabelDifferentId)))
        val renamedLabelSameId = CameraMediaFolder(first.id, "Updated display label")
        assertEquals(listOf("a-jpg", "a-raw", "a-mp4"), display(MediaFolderFilter.Folder(renamedLabelSameId)))
    }

    @Test fun optionsComeOnlyFromLoadedEvidenceAndDeduplicateIds() {
        assertEquals(listOf(first, second), loadedMediaFolders(items))
        assertEquals(emptyList<CameraMediaFolder>(), loadedMediaFolders(listOf(unknown)))
        assertEquals(listOf(first), loadedMediaFolders(listOf(one, raw, movie)))
    }

    @Test fun applyingAndClearingFolderLeavesTransferPreviewAndOtherFiltersUntouched() {
        val camera = CameraInfo(true, "Synthetic folder camera", "TEST-FOLDER", "fixture")
        val original = CameraUiState(info = camera, mediaSessionGeneration = 6, mediaItems = items,
            mediaRatingFilter = MediaRatingFilter.FIVE,
            mediaDateRange = mediaDateRangeFromInput("2026-10-04", "2026-10-05"),
            mediaPreviewItem = two, mediaPreviewBytes = byteArrayOf(4, 5), captureReviewItem = movie,
            mediaDownloadProgress = CameraMediaTransferProgress(10, 99), pendingOperations = setOf(CameraOperation.MEDIA))
        val changed = original.withMediaFolderFilterForSession(MediaFolderFilter.Folder(first), camera, 6)
        assertEquals(original.copy(mediaFolderFilter = MediaFolderFilter.Folder(first)), changed)
        assertSame(original.mediaItems, changed.mediaItems)
        assertSame(original.mediaPreviewBytes, changed.mediaPreviewBytes)
        assertEquals(original, changed.withMediaFolderFilterForSession(MediaFolderFilter.All, camera, 6))
    }

    @Test fun oldMenuCannotApplyOrClearReplacementSessionEvenWithEqualDescriptions() {
        val old = CameraInfo(true, "Synthetic reused camera", "TEST-FOLDER", "fixture")
        val replacement = CameraUiState(info = old.copy(), mediaSessionGeneration = 9,
            mediaFolderFilter = MediaFolderFilter.Folder(second))
        assertSame(replacement, replacement.withMediaFolderFilterForSession(MediaFolderFilter.Unknown, old, 9))
        assertSame(replacement, replacement.withMediaFolderFilterForSession(MediaFolderFilter.All, old, 9))
        val generationOnly = replacement.copy(info = old)
        assertSame(generationOnly, generationOnly.withMediaFolderFilterForSession(MediaFolderFilter.All, old, 8))
    }

    @Test fun offlineSessionResetsFolderAndRejectsPreviousCallbacks() {
        val old = CameraInfo(true, "Synthetic folder camera", "TEST-FOLDER", "fixture")
        val state = CameraUiState(info = old, mediaSessionGeneration = 10, mediaFolderFilter = MediaFolderFilter.Folder(first))
        val offline = state.withOfflinePreview()
        assertEquals(MediaFolderFilter.All, CameraUiState().mediaFolderFilter)
        assertEquals(MediaFolderFilter.All, offline.mediaFolderFilter)
        assertEquals(11L, offline.mediaSessionGeneration)
        assertSame(offline, offline.withMediaFolderFilterForSession(MediaFolderFilter.Unknown, old, 10))
    }

    private fun display(filter: MediaFolderFilter): List<String> =
        mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.CAMERA, folderFilter = filter).map { it.id }
}
