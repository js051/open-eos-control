package dev.openeos.control.ui

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.MockResponse
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Production App → actions → ViewModel → repository → synthetic HTTP; no camera evidence. */
@SdkSuppress(minSdkVersion = 29)
class CameraMediaRatingFilterJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val camera = CameraSessionTestSimulator("rating-${UUID.randomUUID()}")
    private val store = ViewModelStore()
    private val models = mutableListOf<CameraViewModel>()
    private lateinit var model: CameraViewModel
    private lateinit var restoration: StateRestorationTester
    private lateinit var directory: File
    private lateinit var originalZone: TimeZone
    private val infoReads = CopyOnWriteArrayList<String>()
    private val ratingWrites = CopyOnWriteArrayList<Pair<String, String>>()
    private val ratings = ConcurrentHashMap(mapOf("raw" to 4, "jpeg" to 4, "video" to 5, "older" to 0))
    private val rejectReadback = AtomicBoolean(false)
    private val records = listOf(
        CameraMediaItem("latest", "LATEST.JPG", "image", captureTime = "2026-08-16T12:00:00Z"),
        CameraMediaItem("raw", "PAIR.CR3", "raw", captureTime = "2026-08-14T12:00:00Z"),
        CameraMediaItem("jpeg", "PAIR.JPG", "image", captureTime = "2026-08-14T12:00:00Z"),
        CameraMediaItem("video", "CLIP.MP4", "video", captureTime = "2026-08-14T12:00:00Z"),
        CameraMediaItem("older", "OLDER.JPG", "image", captureTime = "2026-08-13T12:00:00Z"),
        CameraMediaItem("unknown", "UNKNOWN.JPG", "image", captureTime = "2026-02-30T12:00:00Z"),
    )

    @Before fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        directory = File(compose.activity.cacheDir, "camera-import/rating-filter-${UUID.randomUUID()}")
        check(directory.mkdirs())
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            val path = url.encodedPath
            val id = path.substringAfterLast('/')
            when {
                request.method == "GET" && path == "/ccapi/media" -> camera.json(
                    JSONObject().put("items", JSONArray(records.map { item ->
                        JSONObject().put("id", item.id).put("name", item.name).put("kind", item.kind)
                            .put("size_bytes", camera.imageBytes.size).put("capture_time", item.captureTime)
                            .apply { ratings[item.id]?.let { put("rating", it) } }
                    })).toString(),
                )
                request.method == "PUT" && path.startsWith("/ccapi/media/") -> {
                    val body = JSONObject(request.body.readUtf8())
                    check(body.getString("action") == "rating")
                    val value = body.getString("value")
                    ratings[id] = if (value == "off") 0 else value.toInt()
                    ratingWrites += id to value
                    camera.json("{}")
                }
                request.method == "GET" && url.queryParameter("kind") == "info" -> {
                    infoReads += id
                    if (rejectReadback.get()) MockResponse().setResponseCode(503) else camera.json(
                        JSONObject().put("rating", ratings[id]?.let { if (it == 0) "off" else "$it" } ?: JSONObject.NULL)
                            .put("filesize", camera.imageBytes.size).toString(),
                    )
                }
                else -> null
            }
        }
        // Resolves MockWebServer hostname on the test thread; StrictMode remains enabled.
        camera.start()
        model = CameraViewModel().also(models::add)
        store.put("rating-filter", model)
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
                // Only this test's random synthetic camera folder is queried and removed.
                savedUris().forEach { compose.activity.contentResolver.delete(it, null, null) }
                if (::directory.isInitialized) directory.deleteRecursively()
                if (::originalZone.isInitialized) TimeZone.setDefault(originalZone)
            }
        }
    }

    @Test fun rateThenFindUsesConfirmedReadbackAndPreviewKeepsExactRawJpegIdentity() {
        chooseRating(MediaRatingFilter.AT_LEAST_FOUR)
        compose.onNodeWithContentDescription(text(R.string.media_actions, "PAIR.JPG")).performScrollTo().performClick()
        awaitMediaIdle()
        compose.onNodeWithContentDescription(text(R.string.set_media_rating, "PAIR.JPG", 5)).performScrollTo().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            !model.uiState.value.isBusy(CameraOperation.MEDIA) && model.uiState.value.mediaItems.single { it.id == "jpeg" }.rating == 5
        }
        pressBack()
        chooseRating(MediaRatingFilter.FIVE)
        chooseSort(MediaSort.RATING_HIGH)
        assertExcludedFromGallery("raw")
        compose.onNodeWithContentDescription(text(R.string.preview_media, "PAIR.JPG")).performScrollTo().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.uiState.value.mediaPreviewLoading }
        assertEquals("jpeg", model.uiState.value.mediaPreviewItem?.id)
        assertEquals("/ccapi/media/jpeg", camera.previewReads.last())
        assertEquals(listOf("jpeg" to "5"), ratingWrites.toList())
        assertEquals(listOf("jpeg", "jpeg"), infoReads.toList())
        assertEquals(4, model.uiState.value.mediaItems.single { it.id == "raw" }.rating)
    }

    @Test fun failedRatingReadbackKeepsPreviouslyConfirmedFilterMembership() {
        chooseRating(MediaRatingFilter.AT_LEAST_FOUR)
        compose.onNodeWithContentDescription(text(R.string.media_actions, "PAIR.JPG")).performScrollTo().performClick()
        awaitMediaIdle()
        rejectReadback.set(true)
        compose.onNodeWithContentDescription(text(R.string.set_media_rating, "PAIR.JPG", 5)).performScrollTo().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { model.uiState.value.error != null && !model.uiState.value.isBusy(CameraOperation.MEDIA) }
        assertEquals(4, model.uiState.value.mediaItems.single { it.id == "jpeg" }.rating)
        assertEquals(MediaRatingFilter.AT_LEAST_FOUR, model.uiState.value.mediaRatingFilter)
        pressBack()
        compose.runOnIdle { model.clearError() }
        chooseRating(MediaRatingFilter.FIVE)
        assertExcludedFromGallery("jpeg")
        assertEquals(listOf("jpeg" to "5"), ratingWrites.toList())
        assertEquals(listOf("jpeg", "jpeg"), infoReads.toList())
    }

    @Test fun partialUnknownAndUnratedFiltersDoNotRequestMetadataOrChangeLoadedItems() {
        val original = model.uiState.value.mediaItems
        val reads = camera.mediaReads.get()
        chooseRating(MediaRatingFilter.UNKNOWN)
        assertGalleryItemVisible("latest")
        assertGalleryItemVisible("unknown")
        assertExcludedFromGallery("older")
        compose.onNodeWithText(text(R.string.media_rating_loaded_results, 2, 6, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_date_partial)).performScrollTo().assertIsDisplayed()
        chooseRating(MediaRatingFilter.UNRATED)
        assertGalleryItemVisible("older")
        assertExcludedFromGallery("unknown")
        assertEquals(original, model.uiState.value.mediaItems)
        assertEquals(reads, camera.mediaReads.get())
        assertTrue(infoReads.isEmpty())
        assertTrue(camera.mutations.isEmpty())
    }

    @Test fun combinedDateTypeAndRatingCanBeClearedIndependently() {
        chooseRating(MediaRatingFilter.AT_LEAST_FOUR)
        applyRange("2026-08-14", "2026-08-14")
        chooseType(MediaFilter.PHOTOS, 2)
        assertGalleryItemVisible("raw")
        assertGalleryItemVisible("jpeg")
        assertExcludedFromGallery("video")
        chooseSort(MediaSort.RATING_LOW)
        compose.onNodeWithContentDescription(text(R.string.media_date_clear)).performClick()
        assertEquals(MediaRatingFilter.AT_LEAST_FOUR, model.uiState.value.mediaRatingFilter)
        assertExcludedFromGallery("older")
        applyRange("2026-08-14", "2026-08-14")
        chooseRating(MediaRatingFilter.ALL)
        assertEquals(mediaDateRangeFromInput("2026-08-14", "2026-08-14"), model.uiState.value.mediaDateRange)
        assertExcludedFromGallery("older")
        assertExcludedFromGallery("video")
        compose.onNodeWithContentDescription(text(R.string.media_sort_current, text(R.string.media_rating_low_first))).assertIsDisplayed()
        // Rating order has no date headings, even though the date filter is still active.
        compose.onNodeWithText("2026-08-14").assertDoesNotExist()
        assertTrue(infoReads.isEmpty())
        assertTrue(camera.mutations.isEmpty())
    }

    @Test fun hiddenAndVisibleSelectionsExportTheirExactIdsAndOriginalBytes() {
        compose.onNodeWithContentDescription(text(R.string.preview_media, "OLDER.JPG"))
            .performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        chooseRating(MediaRatingFilter.AT_LEAST_FOUR)
        compose.onNodeWithText(text(R.string.media_hidden_selected_summary, 1)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.select_media_item, "PAIR.JPG")).performScrollTo().performClick()
        chooseRating(MediaRatingFilter.UNKNOWN)
        compose.onNodeWithText(text(R.string.media_hidden_selected_summary, 2)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.download_selected_media, 2)).performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.lastMediaBatchResult?.succeededItems == 2 && !model.uiState.value.isBusy(CameraOperation.MEDIA)
        }
        assertEquals(listOf("/ccapi/media/jpeg", "/ccapi/media/older"), camera.originalReads.toList())
        val saved = savedUris()
        assertEquals(2, saved.size)
        saved.forEach { uri ->
            assertArrayEquals(camera.imageBytes, compose.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
        compose.onNodeWithText(text(R.string.media_hidden_selected_summary, 2)).assertIsDisplayed()
    }

    @Test fun batchRatingReadbackHidesSelectedRawAndJpegWithoutLosingIdsOrSelectingUnknowns() {
        chooseRating(MediaRatingFilter.AT_LEAST_FOUR)
        chooseType(MediaFilter.PHOTOS, 2)
        compose.onNodeWithContentDescription(text(R.string.preview_media, "PAIR.CR3"))
            .performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription(text(R.string.select_all_media)).performClick()
        compose.onNodeWithContentDescription(text(R.string.edit_selected_media, 2)).performClick()
        compose.onNodeWithContentDescription(text(R.string.set_selected_media_rating, 1)).performScrollTo().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.lastMediaBatchResult?.succeededItems == 2 && !model.uiState.value.isBusy(CameraOperation.MEDIA)
        }
        pressBack()
        compose.onNodeWithText(text(R.string.media_hidden_selected_summary, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.no_filtered_media)).assertIsDisplayed()
        assertEquals(listOf("raw" to "1", "jpeg" to "1"), ratingWrites.toList())
        assertEquals(listOf("raw", "jpeg"), infoReads.toList())
        chooseRating(MediaRatingFilter.UNRATED)
        compose.onNodeWithContentDescription(text(R.string.select_all_media)).performClick()
        compose.onNodeWithContentDescription(text(R.string.download_selected_media, 3)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.clear_media_selection)).performClick()
        compose.onNodeWithContentDescription(text(R.string.download_selected_media, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.media_hidden_selected_summary, 2)).assertIsDisplayed()
        assertNull(model.uiState.value.mediaItems.single { it.id == "unknown" }.rating)
    }

    @Test fun hiddenDownloadKeepsOwnerProgressAndCancelUntilExactBytesFinish() {
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
        chooseRating(MediaRatingFilter.FIVE)
        assertEquals(item.name, model.uiState.value.activeMediaDownloadName)
        compose.onNodeWithContentDescription(text(R.string.cancel_media_download)).assertIsDisplayed()
        assertExcludedFromGallery("older")
        gate.release()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            !model.uiState.value.isBusy(CameraOperation.MEDIA) && model.uiState.value.lastDownloadedMediaName == item.name
        }
        assertArrayEquals(camera.imageBytes, file.readBytes())
        assertEquals(listOf("/ccapi/media/older"), camera.originalReads.toList())
    }

    @Test fun sameViewModelRestorationKeepsFilterAndReconnectRejectsStaleMenu() {
        chooseRating(MediaRatingFilter.FIVE)
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(MediaRatingFilter.FIVE, model.uiState.value.mediaRatingFilter)
        compose.runOnIdle { model.setUiMode(UiMode.CONTROL) }
        openAlbum()
        assertEquals(MediaRatingFilter.FIVE, model.uiState.value.mediaRatingFilter)
        val previous = model.uiState.value
        compose.runOnIdle { model.disconnect() }
        connect()
        openAlbum()
        compose.runOnIdle { model.setMediaRatingFilter(MediaRatingFilter.FIVE, previous.info, previous.mediaSessionGeneration) }
        assertEquals(MediaRatingFilter.ALL, model.uiState.value.mediaRatingFilter)
        val fresh = CameraViewModel().also(models::add)
        store.put("fresh-rating-filter", fresh)
        compose.runOnIdle { fresh.setMediaRatingFilter(MediaRatingFilter.FIVE, previous.info, previous.mediaSessionGeneration) }
        assertEquals(MediaRatingFilter.ALL, fresh.uiState.value.mediaRatingFilter)
        assertGalleryItemVisible("older")
    }

    @Test fun captureReviewOutsideRatingFilterOpensItsExactItemAsOneOfOne() {
        val review = requireNotNull(model.uiState.value.captureReviewItem)
        assertEquals("latest", review.id)
        chooseRating(MediaRatingFilter.FIVE)
        compose.runOnIdle { model.openCaptureReview() }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.uiState.value.mediaPreviewLoading }
        assertEquals(review.id, model.uiState.value.mediaPreviewItem?.id)
        compose.onNodeWithText(text(R.string.media_viewer_position, 1, 1)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.previous_media)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.next_media)).assertDoesNotExist()
        assertEquals(review, model.uiState.value.captureReviewItem)
        assertEquals("/ccapi/media/latest", camera.previewReads.last())
    }

    private fun assertGalleryItemVisible(itemId: String) {
        val name = records.single { it.id == itemId }.name
        compose.onNodeWithTag("media-gallery-grid").performScrollToKey(itemId)
        compose.onNodeWithContentDescription(text(R.string.preview_media, name)).assertIsDisplayed()
    }

    private fun assertExcludedFromGallery(vararg itemIds: String) {
        val grid = compose.onNodeWithTag("media-gallery-grid").fetchSemanticsNode()
        compose.runOnIdle {
            itemIds.forEach { itemId ->
                // IndexForKey checks all items, including tiles outside the composed viewport.
                assertEquals("Filtered item $itemId must be absent from the gallery", -1, grid.config[SemanticsProperties.IndexForKey](itemId))
            }
        }
    }

    private fun pressBack() {
        // Send Back to the active Android window, including the separate metadata-sheet dialog.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun chooseRating(filter: MediaRatingFilter) {
        compose.onNodeWithTag("media-rating-filter").performScrollTo().performClick()
        compose.onNodeWithTag("media-rating-${filter.name}").performScrollTo().performClick()
    }

    private fun chooseSort(sort: MediaSort) {
        compose.onNodeWithContentDescription(text(R.string.media_sort_current, text(currentSort.labelResource))).performClick()
        compose.onNodeWithText(text(sort.labelResource)).performScrollTo().performClick()
        currentSort = sort
    }
    private var currentSort = MediaSort.NEWEST

    private fun chooseType(filter: MediaFilter, count: Int) {
        val label = when (filter) { MediaFilter.ALL -> R.string.media_all; MediaFilter.PHOTOS -> R.string.media_photos; MediaFilter.VIDEOS -> R.string.media_videos }
        compose.onNodeWithText(text(R.string.media_filter_count, text(label), count)).performScrollTo().performClick()
    }

    private fun applyRange(start: String, end: String) {
        compose.onNodeWithContentDescription(text(if (model.uiState.value.mediaDateRange == null) R.string.media_date_filter else R.string.media_date_edit))
            .performScrollTo().performClick()
        compose.onNodeWithTag("media-date-start").performTextReplacement(start)
        compose.onNodeWithTag("media-date-end").performTextReplacement(end)
        compose.onNodeWithTag("media-date-apply").performScrollTo().performClick()
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
    private fun awaitMediaIdle() = compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.uiState.value.isBusy(CameraOperation.MEDIA) }
    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
    private fun savedUris(): List<Uri> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return compose.activity.contentResolver.query(collection.buildUpon().appendQueryParameter("includePending", "1").build(),
            arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(cameraGalleryPath(camera.model)), null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }
    }
}
