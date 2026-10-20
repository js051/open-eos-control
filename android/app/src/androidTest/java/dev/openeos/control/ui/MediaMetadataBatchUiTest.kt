package dev.openeos.control.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.MediaMetadataField
import dev.openeos.control.data.MediaMetadataTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compose fixtures only; no real camera or network calls. */
class MediaMetadataBatchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Test fun sheetFreezesSelectionOrderAndRetainsUnloadedRowsWithoutFetchingThem() {
        val first = CameraMediaItem("exact-a", "FIRST.JPG", "image", rating = 4, archived = false)
        val second = CameraMediaItem("exact-b", "SECOND.JPG", "image", rating = 1, archived = false)
        val state = mutableStateOf(CameraUiState().withOfflinePreview().copy(uiMode = UiMode.MEDIA, mediaItems = listOf(first, second)))
        var submitted = emptyList<CameraMediaItem>(); var reads = 0
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) {
            MediaScreen(state.value, connectionRecoveryTestActions().copy(
                loadMediaInfo = { reads++ }, refreshMedia = { reads++ },
                setMediaRatingBatch = { items, _ -> submitted = items },
            ))
        } }
        compose.onNodeWithContentDescription(text(R.string.preview_media, second.name))
            .performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription(text(R.string.select_media_item, first.name)).performScrollTo().performClick()
        // Unloading BEFORE opening must retain the selected display snapshot and exact ID.
        compose.runOnIdle { state.value = state.value.copy(mediaItems = listOf(first), mediaRatingFilter = MediaRatingFilter.UNRATED) }
        compose.onNodeWithContentDescription(text(R.string.edit_selected_media, 2)).performClick()
        compose.onNodeWithTag("media-batch-metadata-sheet").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_metadata_frozen_scope, 2, 1, 1)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.set_selected_media_rating, 3)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(second.id, first.id), submitted.map { it.id }); assertEquals(0, reads) }
    }

    @Test fun receiptNamesExactIdsSeparatesUncertaintyAndOffersCancelWhileRunning() {
        val target = MediaMetadataTarget(MediaMetadataField.ROTATION, 90)
        val rows = listOf(
            MediaMetadataRow(MediaMetadataRequest("card-a/SAME.JPG", "SAME.JPG", target), MediaMetadataOutcome.CONFIRMED),
            MediaMetadataRow(MediaMetadataRequest("card-b/SAME.JPG", "SAME.JPG", target), MediaMetadataOutcome.UNCERTAIN),
            MediaMetadataRow(MediaMetadataRequest("card-c/SAME.JPG", "SAME.JPG", target)),
        )
        var cancellations = 0
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) {
            MediaBatchMetadataSheet(3, true, true, true, true, true, {}, {}, {}, {}, {},
                receipt = MediaMetadataBatchResult(rows, running = true), onCancel = { cancellations++ })
        } }
        compose.onNodeWithText(text(R.string.media_metadata_summary, 1, 1, 0, 1, 0)).assertIsDisplayed()
        compose.onNodeWithTag("media-metadata-row-card-b/SAME.JPG").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_metadata_uncertain_help)).assertIsDisplayed()
        compose.onNodeWithTag("media-metadata-cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
    }
    @Test fun delayedOldSheetCallbackCannotDispatchIntoReplacementWithSameExactIds() {
        val item = CameraMediaItem("reused-exact-id", "SAME.JPG", "image", rating = 1, archived = false)
        val state = mutableStateOf(CameraUiState().withOfflinePreview().copy(uiMode = UiMode.MEDIA, mediaItems = listOf(item)))
        var writes = 0
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) {
            MediaScreen(state.value, connectionRecoveryTestActions().copy(setMediaRatingBatch = { _, _ -> writes++ }))
        } }
        compose.onNodeWithContentDescription(text(R.string.preview_media, item.name))
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription(text(R.string.edit_selected_media, 1)).performClick()
        var delayedClick: (() -> Boolean)? = null
        compose.onNodeWithContentDescription(text(R.string.set_selected_media_rating, 3))
            .performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { delayedClick = it }
        compose.runOnIdle {
            state.value = state.value.copy(info = requireNotNull(state.value.info).copy(),
                mediaSessionGeneration = state.value.mediaSessionGeneration + 1)
        }
        compose.waitForIdle()
        compose.runOnIdle { requireNotNull(delayedClick).invoke(); assertEquals(0, writes) }
    }

    @Test fun previousOwnerReceiptRemainsLabeledAndCannotCancelTheNewConnection() {
        val target = MediaMetadataTarget(MediaMetadataField.RATING, 3)
        val history = MediaMetadataBatchResult(listOf(
            MediaMetadataRow(MediaMetadataRequest("old-camera/exact", "SAME.JPG", target), MediaMetadataOutcome.CONFIRMED),
            MediaMetadataRow(MediaMetadataRequest("old-camera/uncertain", "SAME.JPG", target), MediaMetadataOutcome.IN_FLIGHT),
        ), running = true).retiredMetadataReceipt()
        val state = CameraUiState().withOfflinePreview().copy(uiMode = UiMode.MEDIA, previousMediaMetadataBatch = history)
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) { MediaScreen(state, connectionRecoveryTestActions()) } }
        compose.onNodeWithText(text(R.string.media_metadata_previous_session)).assertIsDisplayed()
        compose.onNodeWithTag("previous-media-metadata-row-old-camera/exact").assertIsDisplayed()
        compose.onNodeWithTag("previous-media-metadata-row-old-camera/uncertain").assertIsDisplayed()
        compose.onNodeWithTag("media-metadata-cancel").assertDoesNotExist()
    }

    @Test fun delayedSingleDetailCallbacksCannotDispatchIntoReplacementOwner() {
        val item = CameraMediaItem("reused-exact-id", "SAME.JPG", "image", protected = false,
            archived = false, rating = 1, rotationDegrees = 0)
        val state = mutableStateOf(CameraUiState().withOfflinePreview().copy(uiMode = UiMode.MEDIA, mediaItems = listOf(item)))
        var writes = 0
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) {
            MediaScreen(state.value, connectionRecoveryTestActions().copy(
                setMediaProtection = { _, _ -> writes++ }, setMediaArchived = { _, _ -> writes++ },
                setMediaRating = { _, _ -> writes++ }, setMediaRotation = { _, _ -> writes++ }))
        } }
        compose.onNodeWithContentDescription(text(R.string.media_actions, item.name)).performClick()
        val delayed = mutableListOf<() -> Boolean>()
        for (description in listOf(text(R.string.protect_media, item.name), text(R.string.archive_media, item.name),
            text(R.string.set_media_rating, item.name, 3))) {
            compose.onNodeWithContentDescription(description).performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick) { delayed += it }
        }
        compose.onNodeWithText(text(R.string.rotation_degrees_short, 90)).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { delayed += it }
        compose.runOnIdle { state.value = state.value.copy(info = requireNotNull(state.value.info).copy(),
            mediaSessionGeneration = state.value.mediaSessionGeneration + 1) }
        compose.waitForIdle()
        compose.runOnIdle { delayed.forEach { it() }; assertEquals(4, delayed.size); assertEquals(0, writes) }
    }

}
