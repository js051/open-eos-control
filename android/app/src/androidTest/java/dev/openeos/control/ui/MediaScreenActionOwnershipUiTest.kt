package dev.openeos.control.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compose callback ownership only; no physical camera, storage provider or Serein execution. */
class MediaScreenActionOwnershipUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val item = CameraMediaItem("reused-exact-id", "SYNTHETIC.JPG", "image", previewAvailable = true)
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Test fun retiredSingleDetailCannotSaveLaunchDestinationPickerOrHandoff() {
        val current = mutableStateOf(CameraUiState().withOfflinePreview().copy(
            previewMode = false, uiMode = UiMode.MEDIA, mediaItems = listOf(item)))
        var effects = 0
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) {
            MediaScreen(current.value, connectionRecoveryTestActions().copy(
                downloadMedia = { effects++ }, saveMediaToPhone = { effects++ }, openInSerein = { effects++ }))
        } }
        compose.onNodeWithContentDescription(text(R.string.media_actions, item.name)).performClick()
        val delayed = mutableListOf<() -> Boolean>()
        compose.onNodeWithContentDescription(text(R.string.download_media, item.name)).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { delayed += it }
        compose.onNodeWithText(text(R.string.media_save_to_folder)).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { delayed += it }
        compose.onNodeWithContentDescription(text(R.string.open_media_in_serein, item.name)).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { delayed += it }
        compose.runOnIdle { current.value = current.value.copy(info = requireNotNull(current.value.info).copy(),
            mediaSessionGeneration = current.value.mediaSessionGeneration + 1) }
        compose.waitForIdle()
        compose.runOnIdle { delayed.forEach { it() }; assertEquals(3, delayed.size); assertEquals(0, effects) }
        // The replacement screen still dispatches its own normal action.
        compose.onNodeWithContentDescription(text(R.string.media_actions, item.name)).performClick()
        compose.onNodeWithContentDescription(text(R.string.download_media, item.name)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, effects) }
    }

    @Test fun retiredGalleryClickCannotOpenAReplacementCamerasReusedItem() {
        val current = mutableStateOf(CameraUiState().withOfflinePreview().copy(
            previewMode = false, uiMode = UiMode.MEDIA, mediaItems = listOf(item)))
        var previews = 0
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) {
            MediaScreen(current.value, connectionRecoveryTestActions().copy(openMediaPreview = { previews++ }))
        } }
        var delayed: (() -> Boolean)? = null
        compose.onNodeWithContentDescription(text(R.string.preview_media, item.name))
            .performSemanticsAction(SemanticsActions.OnClick) { delayed = it }
        compose.runOnIdle { current.value = current.value.copy(info = requireNotNull(current.value.info).copy(),
            mediaSessionGeneration = current.value.mediaSessionGeneration + 1) }
        compose.waitForIdle()
        compose.runOnIdle { requireNotNull(delayed).invoke(); assertEquals(0, previews) }
        compose.onNodeWithContentDescription(text(R.string.preview_media, item.name)).performClick()
        compose.runOnIdle { assertEquals(1, previews) }
    }
}
