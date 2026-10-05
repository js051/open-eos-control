package dev.openeos.control.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaFolder
import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaFolderFilterUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val first = CameraMediaFolder("card-a-folder", "card-a/DCIM/100EOS")
    private val second = CameraMediaFolder("card-b-folder", "card-b/DCIM/100EOS")

    @Test fun actualOfflineAppCanFindRawJpegPairThenUnknownFolderAndClearWithoutConnecting() {
        val model = CameraViewModel()
        val store = ViewModelStore().apply { put("folder-preview", model) }
        try {
            compose.setContent { OpenEosControlApp(model) }
            compose.onNodeWithTag("connection-offline-preview").performScrollTo().performClick()
            compose.runOnIdle { model.setUiMode(UiMode.MEDIA) }
            chooseFolder("media-folder-option-preview-folder")
            assertGalleryIdsPresent("preview-001", "preview-002")
            assertGalleryIdsAbsent("preview-003")
            compose.runOnIdle {
                assertEquals("preview-folder", (model.uiState.value.mediaFolderFilter as MediaFolderFilter.Folder).folder.id)
                assertTrue(model.uiState.value.previewMode)
                assertNull(model.uiState.value.transport)
            }
            chooseFolder("media-folder-unknown")
            assertGalleryIdsPresent("preview-003")
            assertGalleryIdsAbsent("preview-001", "preview-002")
            chooseFolder("media-folder-all")
            assertGalleryIdsPresent("preview-001", "preview-002", "preview-003")
            compose.runOnIdle {
                assertEquals(MediaFolderFilter.All, model.uiState.value.mediaFolderFilter)
                assertNull(model.uiState.value.error)
            }
        } finally {
            val scope = requireNotNull(model.viewModelScope.coroutineContext[Job])
            compose.runOnIdle { store.clear() }
            compose.waitUntil(15_000) { scope.isCompleted }
        }
    }

    @Test fun folderFilteringKeepsHiddenSelectionAndBatchUsesExactLoadedIds() {
        val jpg = CameraMediaItem("a-jpg", "SAME.JPG", "image", folder = first)
        val raw = CameraMediaItem("a-raw", "SAME.CR3", "raw", folder = first)
        val other = CameraMediaItem("b-jpg", "SAME.JPG", "image", folder = second)
        val unknown = CameraMediaItem("unknown", "UNKNOWN.JPG", "image")
        val state = mutableStateOf(CameraUiState().withOfflinePreview().copy(
            previewMode = false, uiMode = UiMode.MEDIA, mediaItems = listOf(jpg, raw, other, unknown),
            mediaLibraryScope = MediaLibraryScope.ALL, mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE,
        ))
        var batch = emptyList<CameraMediaItem>()
        var listingRequests = 0
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                MediaScreen(state.value, connectionRecoveryTestActions().copy(
                    setMediaFolderFilter = { state.value = state.value.copy(mediaFolderFilter = it) },
                    refreshMedia = { listingRequests++ },
                    downloadMediaBatch = { batch = it },
                ))
            }
        }
        chooseFolder("media-folder-option-${second.id}")
        compose.onNodeWithTag("media-gallery-grid").performScrollToKey(other.id)
        compose.onNode(
            hasContentDescription(text(R.string.select_media_item, other.name)) or
                hasContentDescription(text(R.string.preview_media, other.name)),
        ).performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        chooseFolder("media-folder-option-${first.id}")
        compose.onNodeWithText(text(R.string.media_hidden_selected_summary, 1)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.select_all_media)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.download_selected_media, 3)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(jpg.id, raw.id, other.id), batch.map { it.id })
            assertEquals(0, listingRequests)
            assertEquals(4, state.value.mediaItems.size)
        }
        assertGalleryIdsAbsent(other.id, unknown.id)
    }

    @Test fun manyFoldersAndLongLabelsRemainTouchableAtTwoTimesTextSize() {
        val folders = (0 until 40).map { index ->
            CameraMediaFolder("folder-$index", "card-a/DCIM/${index.toString().padStart(3, '0')}-Synthetic long folder label + 素材")
        }
        val selected = mutableStateOf<MediaFolderFilter>(MediaFolderFilter.All)
        var calls = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    Box(Modifier.size(320.dp, 480.dp).clipToBounds().background(AppBackground)) {
                        MediaFolderFilterButton(selected.value, folders, 3,
                            { selected.value = it; calls++ },
                            menuModifier = Modifier.width(320.dp).heightIn(max = 320.dp))
                    }
                }
            }
        }
        compose.onNodeWithTag("media-folder-filter").assertIsDisplayed().performTouchInput { click(center) }
        val last = compose.onNodeWithTag("media-folder-option-folder-39").performScrollTo().assertIsDisplayed()
        val lastNode = last.fetchSemanticsNode()
        compose.runOnIdle { assertFullyVisibleDialogAction(lastNode) }
        last.performTouchInput { click(center) }
        compose.onNodeWithTag("media-folder-menu").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls); assertEquals(MediaFolderFilter.Folder(folders.last()), selected.value) }
        chooseFolder("media-folder-unknown", scrollTrigger = false)
        compose.runOnIdle { assertEquals(2, calls); assertEquals(MediaFolderFilter.Unknown, selected.value) }
        chooseFolder("media-folder-all", scrollTrigger = false)
        compose.runOnIdle { assertEquals(3, calls); assertEquals(MediaFolderFilter.All, selected.value) }
    }

    @Test fun replacementSessionDismissesOldFolderMenuAndKeepsNewFilter() {
        val state = mutableStateOf(CameraUiState().withOfflinePreview().copy(
            uiMode = UiMode.MEDIA, mediaItems = listOf(CameraMediaItem("same-id", "SAME.JPG", "image", folder = first)),
        ))
        var calls = 0
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) { MediaScreen(state.value,
            connectionRecoveryTestActions().copy(setMediaFolderFilter = { calls++ })) } }
        compose.onNodeWithTag("media-folder-filter").performScrollTo().performClick()
        compose.onNodeWithTag("media-folder-option-${first.id}").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(info = state.value.info?.copy(), mediaSessionGeneration = state.value.mediaSessionGeneration + 1,
                mediaItems = listOf(CameraMediaItem("same-id", "SAME.JPG", "image", folder = second)),
                mediaFolderFilter = MediaFolderFilter.Unknown)
        }
        compose.onNodeWithTag("media-folder-menu").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls); assertEquals(MediaFolderFilter.Unknown, state.value.mediaFolderFilter) }
    }

    @Test fun combinedFolderDateAndRatingFiltersKeepPartialLibraryWarningReachableAtLargeText() {
        val target = CameraMediaItem("matching-raw", "SYNTHETIC.CR3", "raw", captureTime = "2026-10-04", rating = 4, folder = first)
        val items = listOf(
            target,
            target.copy(id = "matching-jpg", name = "SYNTHETIC.JPG", kind = "image"),
            target.copy(id = "lower-rated", rating = 1),
            target.copy(id = "outside-date", captureTime = "2026-10-03"),
            target.copy(id = "other-folder", folder = second),
            target.copy(id = "unknown-folder", folder = null),
        )
        val state = CameraUiState().withOfflinePreview().copy(
            uiMode = UiMode.MEDIA, mediaItems = items, mediaFolderFilter = MediaFolderFilter.Folder(first),
            mediaDateRange = mediaDateRangeFromInput("2026-10-04", "2026-10-04"),
            mediaRatingFilter = MediaRatingFilter.AT_LEAST_FOUR,
            // ALL listings use hasMore=false even when interrupted. The warning must be
            // justified by CANCELLED alone, not accidentally pass through another condition.
            mediaLibraryScope = MediaLibraryScope.ALL, mediaLibraryHasMore = false,
            mediaLibraryLoadStatus = MediaLibraryLoadStatus.CANCELLED,
        )
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    Box(Modifier.size(320.dp, 640.dp).clipToBounds().background(AppBackground)) {
                        MediaScreen(state, connectionRecoveryTestActions())
                    }
                }
            }
        }
        assertGalleryIdsPresent("matching-raw", "matching-jpg")
        assertGalleryIdsAbsent("lower-rated", "outside-date", "other-folder", "unknown-folder")
        compose.onNodeWithTag("media-folder-summary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_folder_loaded_results, 2, 6, 1)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_folder_loaded_only)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_date_partial)).performScrollTo().assertIsDisplayed()
    }

    private fun chooseFolder(tag: String, scrollTrigger: Boolean = true) {
        val trigger = compose.onNodeWithTag("media-folder-filter")
        if (scrollTrigger) trigger.performScrollTo()
        trigger.assertIsDisplayed().performClick()
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("media-folder-menu").assertDoesNotExist()
    }

    private fun assertGalleryIdsPresent(vararg ids: String) {
        val index = compose.onNodeWithTag("media-gallery-grid").fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        ids.forEach { assertTrue("Expected gallery key $it", index(it) >= 0) }
    }

    private fun assertGalleryIdsAbsent(vararg ids: String) {
        val index = compose.onNodeWithTag("media-gallery-grid").fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        ids.forEach { assertEquals("Excluded key must not merely be offscreen: $it", -1, index(it)) }
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
}
