package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDateRangeStateTest {
    private val info = CameraInfo(true, "Synthetic date filter", "TEST-DATE", "simulator")
    private val range = requireNotNull(mediaDateRangeFromInput("2026-08-14", "2026-08-14"))

    @Test fun validApplyAndClearOnlyChangeDisplayRangeDuringAnOwnedDownload() {
        val item = CameraMediaItem("shared-id", "SYNTHETIC.JPG", "image")
        val state = CameraUiState(
            info = info, mediaSessionGeneration = 7, mediaItems = listOf(item),
            mediaPreviewItem = item, captureReviewItem = item,
            mediaPreviewBytes = byteArrayOf(1, 2, 3), activeMediaDownloadName = item.name,
            mediaDownloadProgress = CameraMediaTransferProgress(10, 100),
            pendingOperations = setOf(CameraOperation.MEDIA),
        )
        val filtered = state.withMediaDateRangeForSession(range, info, 7)
        assertEquals(state.copy(mediaDateRange = range), filtered)
        assertSame(state.mediaItems, filtered.mediaItems)
        assertSame(state.mediaPreviewBytes, filtered.mediaPreviewBytes)
        assertSame(state.captureReviewItem, filtered.captureReviewItem)
        assertEquals(state, filtered.withMediaDateRangeForSession(null, info, 7))
    }

    @Test fun equalCameraDescriptionsDoNotAuthorizeAnOldForm() {
        val replacement = info.copy()
        assertEquals(info, replacement)
        val state = CameraUiState(info = replacement, mediaSessionGeneration = 7)
        assertSame(state, state.withMediaDateRangeForSession(range, info, 7))
    }

    @Test fun oldGenerationCannotApplyOrClearEvenWithTheSameInfoReference() {
        val state = CameraUiState(info = info, mediaSessionGeneration = 8, mediaDateRange = range)
        assertSame(state, state.withMediaDateRangeForSession(null, info, 7))
        assertSame(state, state.withMediaDateRangeForSession(
            mediaDateRangeFromInput("2020-01-01", "2020-01-01"), info, 7,
        ))
    }

    @Test fun freshUiSessionHasNoFilterAndDoesNotAcceptPriorSessionInput() {
        val state = CameraUiState()
        assertNull(state.mediaDateRange)
        assertSame(state, state.withMediaDateRangeForSession(range, info, 7))
    }

    @Test fun offlinePreviewCreatesNewUiIdentityAndClearsTheFilter() {
        val state = CameraUiState(info = info, mediaSessionGeneration = 7, mediaDateRange = range)
        val offline = state.withOfflinePreview()
        assertNull(offline.mediaDateRange)
        assertEquals(8L, offline.mediaSessionGeneration)
        assertTrue(offline.info !== info)
        assertSame(offline, offline.withMediaDateRangeForSession(range, info, 7))
    }
}
