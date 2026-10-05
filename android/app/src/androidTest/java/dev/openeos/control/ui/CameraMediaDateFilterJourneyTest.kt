package dev.openeos.control.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real app → actions → ViewModel → repository with a synthetic HTTP peer. No camera evidence. */
class CameraMediaDateFilterJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val camera = CameraSessionTestSimulator("date-filter")
    private val store = ViewModelStore()
    private val models = mutableListOf<CameraViewModel>()
    private lateinit var model: CameraViewModel
    private lateinit var restoration: StateRestorationTester
    private lateinit var directory: File
    private lateinit var originalZone: TimeZone
    private val records = listOf(
        Triple("latest", "LATEST.JPG", "2026-08-16T12:00:00Z"),
        Triple("raw", "PAIR.CR3", "2026-08-14T12:00:00Z"),
        Triple("jpeg", "PAIR.JPG", "2026-08-14T12:00:00Z"),
        Triple("older", "OLDER.JPG", "2026-08-13T12:00:00Z"),
        Triple("unknown", "UNKNOWN.JPG", "2026-02-30T12:00:00Z"),
    )

    @Before fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        directory = File(compose.activity.cacheDir, "camera-import/date-filter-${UUID.randomUUID()}")
        check(directory.mkdirs())
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            if (request.method == "GET" && url.encodedPath == "/ccapi/media") {
                camera.json(JSONObject().put("items", JSONArray(records.map { (id, name, date) ->
                    JSONObject().put("id", id).put("name", name).put("kind", "image")
                        .put("size_bytes", camera.imageBytes.size).put("capture_time", date)
                })).toString())
            } else null
        }
        // Resolves MockWebServer URL/hostname only on the test thread, with StrictMode unchanged.
        camera.start()
        model = CameraViewModel().also(models::add)
        store.put("date-filter", model)
        restoration = StateRestorationTester(compose)
        restoration.setContent { OpenEosControlApp(model) }
        connect()
        openAlbum()
    }

    @After fun tearDown() {
        camera.releaseGates()
        try {
            if (::model.isInitialized) {
                val jobs = models.map { requireNotNull(it.viewModelScope.coroutineContext[Job]) }
                compose.runOnIdle { store.clear() }
                compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { jobs.all(Job::isCompleted) }
            }
        } finally {
            try { camera.server.shutdown() } finally {
                if (::directory.isInitialized) directory.deleteRecursively()
                if (::originalZone.isInitialized) TimeZone.setDefault(originalZone)
            }
        }
    }

    @Test fun rangeAppliesToLoadedRawAndJpegAndCancelDoesNotChangeIt() {
        val before = model.uiState.value.mediaItems
        val reads = camera.mediaReads.get()
        applyRange("2026-08-14", "2026-08-14")
        compose.onNodeWithText("PAIR.CR3").assertIsDisplayed()
        compose.onNodeWithText("PAIR.JPG").assertIsDisplayed()
        compose.onNodeWithText("OLDER.JPG").assertDoesNotExist()
        compose.onNodeWithText("UNKNOWN.JPG").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.media_date_loaded_results, 2, 5, 1)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_date_partial)).performScrollTo().assertIsDisplayed()
        val applied = model.uiState.value.mediaDateRange
        openDateDialog()
        compose.onNodeWithTag("media-date-start").performTextReplacement("2026-02-30")
        compose.onNodeWithTag("media-date-apply").assertIsNotEnabled()
        compose.onNodeWithTag("media-date-cancel").performScrollTo().performClick()
        assertEquals(applied, model.uiState.value.mediaDateRange)
        assertEquals(before, model.uiState.value.mediaItems)
        assertEquals(reads, camera.mediaReads.get())
        assertTrue(camera.mutations.isEmpty())
        compose.onNodeWithContentDescription(text(R.string.media_date_clear)).performClick()
        compose.onNodeWithText("OLDER.JPG").assertIsDisplayed()
        assertNull(model.uiState.value.mediaDateRange)
    }

    @Test fun hiddenSelectionsAreCountedAndBatchDeleteKeepsExactIdsEvenWithNoResults() {
        compose.onNodeWithContentDescription(text(R.string.preview_media, "OLDER.JPG"))
            .performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        applyRange("2026-08-14", "2026-08-14")
        compose.onNodeWithText(text(R.string.media_hidden_selected_summary, 1)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.select_all_media)).performClick()
        applyRange("2020-01-01", "2020-01-01")
        compose.onNodeWithText(text(R.string.no_filtered_media)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_hidden_selected_summary, 3)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.delete_selected_media, 3)).performClick()
        compose.onNode(hasText(text(R.string.media_hidden_selected, 3)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.delete)).performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { model.uiState.value.lastMediaBatchResult != null }
        assertEquals(setOf("/ccapi/media/raw", "/ccapi/media/jpeg", "/ccapi/media/older"), camera.deletes.toSet())
        assertEquals(3, camera.deletes.size)
    }

    @Test fun sameViewModelRecreationAndAlbumReentryKeepRangeButNewSessionClearsIt() {
        applyRange("2026-08-14", "2026-08-14")
        val applied = model.uiState.value.mediaDateRange
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(applied, model.uiState.value.mediaDateRange)
        compose.onNodeWithTag("media-date-range").assertIsDisplayed()
        compose.runOnIdle { model.setUiMode(UiMode.CONTROL) }
        openAlbum()
        assertEquals(applied, model.uiState.value.mediaDateRange)
        val previous = model.uiState.value
        compose.runOnIdle { model.disconnect() }
        connect()
        openAlbum()
        compose.runOnIdle { model.setMediaDateRange(applied, previous.info, previous.mediaSessionGeneration) }
        assertNull(model.uiState.value.mediaDateRange)
        val fresh = CameraViewModel().also(models::add)
        store.put("fresh-date-filter", fresh)
        compose.runOnIdle { fresh.setMediaDateRange(applied, previous.info, previous.mediaSessionGeneration) }
        assertNull(fresh.uiState.value.mediaDateRange)
        compose.onNodeWithText("OLDER.JPG").assertIsDisplayed()
    }

    @Test fun captureReviewOutsideRangeStillOpensTheExactLatestItemAsOneOfOne() {
        val review = requireNotNull(model.uiState.value.captureReviewItem)
        assertEquals("latest", review.id)
        applyRange("2026-08-14", "2026-08-14")
        compose.runOnIdle { model.openCaptureReview() }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.uiState.value.mediaPreviewLoading }
        assertEquals(review.id, model.uiState.value.mediaPreviewItem?.id)
        compose.onNodeWithText(text(R.string.media_viewer_position, 1, 1)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.next_media)).assertIsNotEnabled()
        assertEquals(review, model.uiState.value.captureReviewItem)
        assertEquals("/ccapi/media/latest", camera.previewReads.last())
    }

    @Test fun filteringAnActiveDownloadDoesNotChangeItsOwnerOrOriginalBytes() {
        val item = model.uiState.value.mediaItems.single { it.id == "older" }
        val file = File(directory, item.name).apply { check(createNewFile()) }
        val uri = FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.camera_import", file)
        val gate = camera.gate()
        val original = camera.intercept
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            if (url.encodedPath == "/ccapi/media/older" && url.query == null) {
                gate.blockResponse()
                camera.imageResponse()
            } else original(request)
        }
        compose.runOnIdle { model.downloadMedia(compose.activity, item, uri) }
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        applyRange("2026-08-14", "2026-08-14")
        assertEquals(item.name, model.uiState.value.activeMediaDownloadName)
        compose.onNodeWithContentDescription(text(R.string.cancel_media_download)).assertIsDisplayed()
        assertEquals(listOf("/ccapi/media/older"), camera.originalReads.toList())
        gate.release()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            !model.uiState.value.isBusy(CameraOperation.MEDIA) && model.uiState.value.lastDownloadedMediaName == item.name
        }
        assertArrayEquals(camera.imageBytes, file.readBytes())
        assertEquals(listOf("/ccapi/media/older"), camera.originalReads.toList())
    }

    private fun connect() {
        compose.waitForIdle()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.uiState.value.busy }
        compose.runOnIdle {
            model.setLiveViewAutoRefresh(false)
            model.useDevSimulatorPreset()
            model.setBaseUrl(camera.baseUrl)
            model.connect()
        }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = model.uiState.value
            state.connected && !state.busy && !state.captureReviewLoading
        }
    }

    private fun openAlbum() {
        compose.runOnIdle { model.setUiMode(UiMode.MEDIA) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.mediaItems.size == records.size && !model.uiState.value.mediaLibraryLoading
        }
    }

    private fun openDateDialog() = compose.onNodeWithContentDescription(text(
        if (model.uiState.value.mediaDateRange == null) R.string.media_date_filter else R.string.media_date_edit,
    )).performClick()

    private fun applyRange(start: String, end: String) {
        openDateDialog()
        compose.onNodeWithTag("media-date-start").performScrollTo().performTextReplacement(start)
        compose.onNodeWithTag("media-date-end").performScrollTo().performTextReplacement(end)
        compose.onNodeWithTag("media-date-apply").performScrollTo().performClick()
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
}
