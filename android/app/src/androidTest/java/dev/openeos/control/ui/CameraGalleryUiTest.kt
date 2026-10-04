package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTouchInput
import java.io.ByteArrayOutputStream

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraMediaTransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 29)
class CameraGalleryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val item = CameraMediaItem("fixture", "IMG_0001.JPG", "image", previewAvailable = true)
    private fun state() = CameraUiState().withOfflinePreview().copy(
        previewMode = false, uiMode = UiMode.MEDIA, mediaItems = listOf(item),
    )

    @Test fun viewerDownloadGoesDirectlyToPhoneWithoutDocumentPicker() {
        var saved = emptyList<CameraMediaItem>()
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                MediaScreen(state().copy(mediaPreviewItem = item), actions().copy(saveMediaToPhone = { saved = it }))
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.download_media, item.name)).performClick()
        compose.runOnIdle { assertEquals(listOf(item), saved) }
    }

    @Test fun thumbnailsCancelOnViewportRemovalAndRestartAfterRefreshOrTransfer() {
        val base = state()
        val capabilities = requireNotNull(base.capabilities)
        val state = mutableStateOf(base.copy(capabilities = capabilities.copy(
            matrix = capabilities.matrix.copy(supported = capabilities.matrix.supported + CameraFeature.MEDIA_THUMBNAIL),
        )))
        val loaded = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val actions = actions().copy(loadMediaThumbnail = { loaded += it.id }, cancelMediaThumbnail = { cancelled += it.id })
        compose.setContent { MaterialTheme { MediaScreen(state.value, actions) } }
        compose.runOnIdle {
            assertEquals(listOf(item.id), loaded)
            state.value = state.value.copy(mediaLibraryLoading = true)
        }
        compose.runOnIdle {
            assertEquals(2, loaded.size)
            assertEquals(1, cancelled.size)
            state.value = state.value.copy(pendingOperations = setOf(CameraOperation.MEDIA))
        }
        compose.runOnIdle {
            assertEquals(2, loaded.size)
            assertEquals(2, cancelled.size)
            state.value = state.value.copy(pendingOperations = emptySet())
        }
        compose.runOnIdle {
            assertEquals(3, loaded.size)
            state.value = state.value.copy(mediaItems = emptyList())
        }
        compose.runOnIdle { assertTrue(cancelled.size >= 3) }
    }

    @Test fun savedLocationIsVisibleInAlbum() {
        val location = cameraGalleryPath("Canon EOS R6 Mark III")
        compose.setContent { MaterialTheme { MediaScreen(state().copy(lastDownloadLocation = location), actions()) } }
        compose.onNodeWithText(compose.activity.getString(R.string.media_saved_location, location)).assertIsDisplayed()
    }

    @Test fun libraryScopeIsDisabledDuringTransferAndEnabledAfterwards() {
        val state = mutableStateOf(state().copy(
            pendingOperations = setOf(CameraOperation.MEDIA),
            mediaLibraryScope = MediaLibraryScope.RECENT,
            mediaLibraryHasMore = true,
            mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE,
        ))
        val requested = mutableListOf<MediaLibraryScope>()
        compose.setContent {
            MaterialTheme {
                MediaScreen(state.value, actions().copy(setMediaLibraryScope = { requested += it }))
            }
        }
        val recent = compose.onNodeWithText(compose.activity.getString(R.string.media_scope_recent))
        val all = compose.onNodeWithText(compose.activity.getString(R.string.media_scope_all))
        recent.assertIsSelected().assertIsNotEnabled()
        all.assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertTrue(requested.isEmpty())
            state.value = state.value.copy(pendingOperations = emptySet())
        }
        recent.assertIsEnabled()
        all.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(MediaLibraryScope.ALL), requested) }
    }

    @Test fun sameNameDoesNotShowAnotherItemsSavedResult() {
        val other = item.copy(id = "other-folder/same-name")
        compose.setContent {
            MaterialTheme {
                MediaScreen(state().copy(
                    mediaItems = listOf(item, other),
                    mediaPreviewItem = other,
                    mediaSaveFeedback = mapOf(item.id to MediaSaveFeedback.Saved("Pictures/fixture/")),
                ), actions())
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.close_media_preview)).assertIsDisplayed()
        compose.onNodeWithTag("media-save-feedback").assertDoesNotExist()
    }

    @Test fun anotherItemsActiveDownloadCanBeCancelledInsideTheViewer() {
        val other = item.copy(id = "other-folder/same-name")
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                MediaScreen(state().copy(
                    mediaItems = listOf(item, other),
                    mediaPreviewItem = other,
                    pendingOperations = setOf(CameraOperation.MEDIA),
                    mediaSaveFeedback = mapOf(item.id to MediaSaveFeedback.Saving(CameraMediaTransferProgress(20L, 100L))),
                ), actions().copy(cancelMediaDownload = { cancelled = true }))
            }
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.download_media, other.name))
            .assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel_media_download)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun largeTextLandscapeFailureKeepsCloseDownloadAndRetryReachable() {
        var retried = false
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(640.dp, 320.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    MaterialTheme {
                        MediaViewerDialog(
                            item = item, bytes = null, streamSource = null, loading = false,
                            position = 1, totalCount = 1, canMovePrevious = false, canMoveNext = false,
                            onPrevious = {}, onNext = {}, downloadEnabled = true,
                            saveFeedback = MediaSaveFeedback.Failed(
                                "The connection was interrupted while saving this original. Keep the camera awake and try again.",
                            ),
                            onDownload = { retried = true }, onDismiss = {},
                            modifier = Modifier.requiredSize(640.dp, 320.dp),
                        )
                    }
                }
            }
        }
        assertLandscapeViewerBounds()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.close_media_preview)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.download_media, item.name))
            .assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.media_save_retry)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

    @Test fun zoomAndDownloadControlsStayInsideTheActualLargeTextLandscapeViewport() {
        val bytes = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
            } finally { bitmap.recycle() }
        }
        var cancelled = false
        val media = item.copy(sizeBytes = 1_000L, captureTime = "2026-09-01T00:00:00Z")
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(640.dp, 320.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    MaterialTheme {
                        MediaViewerDialog(
                            item = media, bytes = bytes, streamSource = null, loading = false,
                            position = 1, totalCount = 2, canMovePrevious = false, canMoveNext = true,
                            onPrevious = {}, onNext = {}, downloadEnabled = true, downloadBusy = true,
                            saveFeedback = MediaSaveFeedback.Saving(CameraMediaTransferProgress(500L, 1_000L)),
                            onCancelDownload = { cancelled = true }, onDismiss = {},
                            modifier = Modifier.requiredSize(640.dp, 320.dp),
                        )
                    }
                }
            }
        }
        val viewport = assertLandscapeViewerBounds()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.media_preview_content, media.name))
            .assertIsDisplayed().performTouchInput { doubleClick() }
        val close = compose.onNodeWithContentDescription(compose.activity.getString(R.string.close_media_preview))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val download = compose.onNodeWithContentDescription(compose.activity.getString(R.string.download_media, media.name))
            .assertIsDisplayed().assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithContentDescription(compose.activity.getString(R.string.next_media))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val reset = compose.onNodeWithContentDescription(compose.activity.getString(R.string.reset_media_zoom))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText(compose.activity.getString(R.string.cancel_media_download))
            .performScrollTo().assertIsDisplayed()
        val cancelBounds = cancel.fetchSemanticsNode().boundsInRoot
        val controls = listOf(close, download, next, reset, cancelBounds)
        controls.forEach { bounds ->
            assertTrue("A visible control must stay within the measured viewer", viewport.contains(bounds.topLeft) && viewport.contains(bounds.bottomRight))
        }
        controls.forEachIndexed { index, bounds ->
            controls.drop(index + 1).forEach { other -> assertFalse("Viewer controls must not overlap", bounds.overlaps(other)) }
        }
        cancel.performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    private fun assertLandscapeViewerBounds(): androidx.compose.ui.geometry.Rect {
        val bounds = compose.onNodeWithTag("media-viewer-content").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // The dialog is a separate window: assert its measured content, not only LocalConfiguration.
        assertEquals("The viewer must really be a 640x320 viewport", 2f, bounds.width / bounds.height, 0.05f)
        return bounds
    }

    private fun actions() = CameraActions(
        setConnectionTarget = {}, setBaseUrl = {}, setUsername = {}, setPassword = {},
        setBridgeBaseUrl = {}, setBridgeToken = {}, scanDesktopBridge = {}, selectBridgeCamera = {},
        useHttpPreset = {}, useHttpsPreset = {}, useSimulatorPreset = {}, enterOfflinePreview = {},
        connect = {}, connectBridge = {}, disconnect = {}, refresh = {}, refreshUsb = {}, requestUsbPermission = {},
        connectUsb = { _, _, _ -> },
        setUiMode = {}, setCaptureMode = {}, setHudVisible = {}, setGridVisible = {}, setLiveViewTapAction = {},
        openPicker = {}, closePicker = {},
        setIso = {}, setShutter = {}, setAperture = {}, setWhiteBalance = {}, setCameraSetting = { _, _ -> },
        captureStill = {}, autofocus = {}, driveFocus = { _, _ -> }, setLiveViewMagnification = {},
        toggleRecording = {}, tapFocus = { _, _ -> }, halfPressShutter = {}, clickWhiteBalance = { _, _ -> },
        refreshMedia = {}, loadMediaThumbnail = {}, openMediaPreview = {}, closeMediaPreview = {},
        downloadMedia = {}, deleteMedia = {}, cancelMediaDownload = {},
        refreshLiveView = {}, restartLiveView = {}, setAutoRefresh = {}, setFps = {}, setLiveViewSize = {},
        setLiveViewSource = {}, setAppLanguage = {}, clearError = {},
    )
}
