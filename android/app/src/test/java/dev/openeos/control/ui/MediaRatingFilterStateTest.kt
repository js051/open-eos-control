package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MediaRatingFilterStateTest {
    private val info = CameraInfo(true, "Synthetic rating filter", "TEST-RATING", "simulator")

    @Test fun filterAndClearLeaveDateDownloadPreviewAndCaptureReviewUnchanged() {
        val item = CameraMediaItem("shared-id", "SYNTHETIC.JPG", "image", rating = 4)
        val state = CameraUiState(info = info, mediaSessionGeneration = 7, mediaItems = listOf(item),
            mediaDateRange = mediaDateRangeFromInput("2026-08-14", "2026-08-14"),
            mediaPreviewItem = item, captureReviewItem = item, mediaPreviewBytes = byteArrayOf(1, 2, 3),
            activeMediaDownloadName = item.name, mediaDownloadProgress = CameraMediaTransferProgress(10, 100),
            pendingOperations = setOf(CameraOperation.MEDIA))
        val filtered = state.withMediaRatingFilterForSession(MediaRatingFilter.FIVE, info, 7)
        assertEquals(state.copy(mediaRatingFilter = MediaRatingFilter.FIVE), filtered)
        assertSame(state.mediaItems, filtered.mediaItems)
        assertSame(state.mediaPreviewBytes, filtered.mediaPreviewBytes)
        assertEquals(state, filtered.withMediaRatingFilterForSession(MediaRatingFilter.ALL, info, 7))
    }

    @Test fun clearingDateLeavesRatingFilterIntact() {
        val state = CameraUiState(info = info, mediaSessionGeneration = 7,
            mediaDateRange = mediaDateRangeFromInput("2026-08-14", "2026-08-14"), mediaRatingFilter = MediaRatingFilter.UNKNOWN)
        assertEquals(state.copy(mediaDateRange = null), state.withMediaDateRangeForSession(null, info, 7))
    }

    @Test fun equalCameraDescriptionCannotAuthorizePriorMenu() {
        val state = CameraUiState(info = info.copy(), mediaSessionGeneration = 7)
        assertSame(state, state.withMediaRatingFilterForSession(MediaRatingFilter.FIVE, info, 7))
    }

    @Test fun staleGenerationCannotApplyOrClearEvenWithSameInfo() {
        val state = CameraUiState(info = info, mediaSessionGeneration = 8, mediaRatingFilter = MediaRatingFilter.FIVE)
        assertSame(state, state.withMediaRatingFilterForSession(MediaRatingFilter.ALL, info, 7))
        assertSame(state, state.withMediaRatingFilterForSession(MediaRatingFilter.UNKNOWN, info, 7))
    }

    @Test fun freshAndOfflineSessionsResetRatingAndRejectPreviousMenu() {
        val fresh = CameraUiState()
        assertEquals(MediaRatingFilter.ALL, fresh.mediaRatingFilter)
        assertSame(fresh, fresh.withMediaRatingFilterForSession(MediaRatingFilter.FIVE, info, 7))
        val offline = CameraUiState(info = info, mediaSessionGeneration = 7, mediaRatingFilter = MediaRatingFilter.FIVE).withOfflinePreview()
        assertEquals(MediaRatingFilter.ALL, offline.mediaRatingFilter)
        assertEquals(8L, offline.mediaSessionGeneration)
        assertSame(offline, offline.withMediaRatingFilterForSession(MediaRatingFilter.FIVE, info, 7))
    }
}
