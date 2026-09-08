package dev.openeos.control.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraFeature
import org.junit.Assert.assertEquals
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
        downloadMedia = { _, _ -> }, deleteMedia = {}, cancelMediaDownload = {},
        refreshLiveView = {}, restartLiveView = {}, setAutoRefresh = {}, setFps = {}, setLiveViewSize = {},
        setLiveViewSource = {}, setAppLanguage = {}, clearError = {},
    )
}
