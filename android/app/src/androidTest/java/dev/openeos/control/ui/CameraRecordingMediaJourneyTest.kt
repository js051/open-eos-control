package dev.openeos.control.ui

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.R
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real Compose -> production ViewModel/repository -> synthetic HTTP peer -> Media3/MediaStore.
 * The peer exposes a delayed MP4 only after Stop; the UI must read back a non-recording status.
 * Empty event polls deliberately supply no contents hint. The valid H.264 asset is both the
 * decoded preview and the byte-for-byte saved original; the JPEG is only an older item/thumbnail.
 * This is simulator-contract evidence, not physical-camera or external-picker validation.
 */
@UnstableApi
@SdkSuppress(minSdkVersion = 29)
class CameraRecordingMediaJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val camera = CameraSessionTestSimulator("recording-journey-${UUID.randomUUID()}")
    private val viewModels = ViewModelStore()
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var model: CameraViewModel
    private lateinit var history: DownloadHistoryStore
    private lateinit var historyDirectory: File
    private lateinit var movieBytes: ByteArray
    private val stopped = AtomicBoolean(false)
    private val postStopListings = AtomicInteger()
    private val visibleOnListing = AtomicInteger(2)
    private val previousId = "${camera.label}-synthetic-previous"
    private val movieId = "${camera.label}-synthetic-movie"
    private val movieName = "SYNTHETIC_RECORDED_H264.MP4"
    private val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val resolver get() = compose.activity.contentResolver

    @Before
    fun setUp() {
        // Asset I/O and MockWebServer URL/hostname resolution stay on the instrumentation
        // thread. Do not relax StrictMode or perform either inside Compose/main callbacks.
        movieBytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("valid-h264.mp4").use { it.readBytes() }
        historyDirectory = File(compose.activity.cacheDir, "recording-history-${UUID.randomUUID()}")
        check(historyDirectory.mkdirs())
        history = DownloadHistoryStore(historyDirectory, historyScope)
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            when {
                request.method == "POST" && url.encodedPath == "/ccapi/record/stop" -> {
                    camera.recording.set(false)
                    stopped.set(true)
                    camera.json("{}")
                }
                request.method == "GET" && url.encodedPath == "/ccapi/media" -> {
                    val showMovie = stopped.get() && postStopListings.incrementAndGet() >= visibleOnListing.get()
                    val ids = if (showMovie) listOf(movieId, previousId) else listOf(previousId)
                    camera.json(JSONObject().put("items", JSONArray(ids.map(::mediaItemJson))).toString())
                }
                request.method == "GET" && url.encodedPath.startsWith("/ccapi/media/") &&
                    url.queryParameter("kind") == "info" ->
                    camera.json(mediaItemJson(url.pathSegments.last()).toString())
                request.method == "GET" && url.encodedPath == "/ccapi/media/$movieId" && url.query == null ->
                    movieResponse(request.getHeader("Range"))
                else -> null
            }
        }
        camera.start()
        model = CameraViewModel(downloadHistoryFactory = { history })
        viewModels.put("recording-media-journey", model)
        compose.setContent { OpenEosControlApp(model) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.downloadHistoryState.value.loading }
        compose.runOnIdle {
            model.setLiveViewAutoRefresh(false)
            model.useDevSimulatorPreset()
            model.setBaseUrl(camera.baseUrl)
            model.connect()
        }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = model.uiState.value
            state.connected && state.info?.model == camera.model && !state.busy &&
                !state.captureReviewLoading && state.captureReviewItem?.id == previousId
        }
        assertTrue("The real connection must read peer status", camera.statusReads.get() > 0)
        assertFalse(model.uiState.value.previewMode)
    }

    @After
    fun tearDown() {
        camera.releaseGates()
        try {
            if (::model.isInitialized) {
                val job = requireNotNull(model.viewModelScope.coroutineContext[Job])
                compose.runOnIdle {
                    model.closeMediaPreview()
                    viewModels.clear()
                }
                compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { job.isCompleted }
            }
            if (::history.isInitialized) runBlocking {
                withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { history.awaitIdle() }
            }
        } finally {
            try {
                runBlocking {
                    historyScope.cancel()
                    withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { historyScope.coroutineContext[Job]?.join() }
                }
            } finally {
                try {
                    camera.server.shutdown()
                } finally {
                    // Restrict cleanup to this instance's random synthetic camera folder and
                    // private history directory. Never query/delete unrelated user media.
                    try {
                        savedRows().forEach { resolver.delete(it.uri, null, null) }
                    } finally {
                        if (::historyDirectory.isInitialized) historyDirectory.deleteRecursively()
                    }
                }
            }
        }
    }

    @Test
    fun stopFindsDelayedMovieWithoutContentsEventThenPreviewsAndSavesOriginal() {
        recordAndStopOnce()
        awaitReviewedMovie()
        assertEquals("The first post-Stop listing must not already contain the new movie", 2, postStopListings.get())
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        openReviewedMovieAndSave()
        assertOnlyOneRecordingPair()
    }

    @Test
    fun exhaustedReviewCanReadOnlyRetryThenPreviewAndSaveWithoutRecordingAgain() {
        visibleOnListing.set(Int.MAX_VALUE)
        recordAndStopOnce()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = model.uiState.value
            !state.busy && !state.captureReviewLoading && state.captureReviewStatus == CaptureReviewStatus.NOT_READY
        }
        assertEquals("An older photo must not be relabeled as the new movie", previousId, model.uiState.value.captureReviewItem?.id)
        assertTrue("The review must have retried before becoming NOT_READY", postStopListings.get() > 1)
        val writesBeforeRetry = camera.mutations.toList()
        val readsBeforeRetry = postStopListings.get()

        compose.onNodeWithTag("capture-review-button").assertIsDisplayed().performClick()
        compose.onNodeWithTag("capture-review-status-dialog").assertIsDisplayed()
        compose.onNodeWithTag("capture-review-open-existing").performScrollTo().assertIsDisplayed()
        visibleOnListing.set(readsBeforeRetry + 1)
        compose.onNodeWithTag("capture-review-retry").performScrollTo().assertIsEnabled().performClick()
        awaitReviewedMovie()
        assertEquals(readsBeforeRetry + 1, postStopListings.get())
        assertEquals("Retry may only read the camera", writesBeforeRetry, camera.mutations.toList())
        compose.onNodeWithTag("capture-review-status-dialog").assertDoesNotExist()

        openReviewedMovieAndSave()
        assertEquals("Preview, save and history must not start or stop another recording", writesBeforeRetry, camera.mutations.toList())
        assertOnlyOneRecordingPair()
    }

    private fun recordAndStopOnce() {
        assertEquals(previousId, model.uiState.value.captureReviewItem?.id)
        compose.onNodeWithTag("capture-mode-VIDEO").assertIsDisplayed().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.captureMode == CaptureMode.VIDEO && !model.uiState.value.busy
        }
        val readsBeforeStart = camera.mediaReads.get()
        compose.onNodeWithContentDescription(text(R.string.start_recording)).assertIsEnabled().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.status?.recording == true && !model.uiState.value.busy
        }
        assertTrue(camera.recording.get())
        assertEquals("Start alone must not look for a completed movie", readsBeforeStart, camera.mediaReads.get())
        assertEquals(0, postStopListings.get())
        assertEquals(CaptureReviewStatus.IDLE, model.uiState.value.captureReviewStatus)
        assertEquals(previousId, model.uiState.value.captureReviewItem?.id)

        compose.onNodeWithContentDescription(text(R.string.stop_recording)).assertIsEnabled().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.status?.recording == false && !model.uiState.value.isBusy(CameraOperation.RECORDING)
        }
        assertFalse(camera.recording.get())
        assertOnlyOneRecordingPair()
    }

    private fun awaitReviewedMovie() = compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
        val state = model.uiState.value
        !state.busy && !state.captureReviewLoading && state.captureReviewItem?.id == movieId &&
            state.captureReviewStatus == CaptureReviewStatus.IDLE
    }

    private fun openReviewedMovieAndSave() {
        assertEquals(false, model.uiState.value.status?.recording)
        assertEquals(movieName, model.uiState.value.captureReviewItem?.name)
        assertNotEquals(previousId, model.uiState.value.captureReviewItem?.id)
        assertTrue("Polling must be active, rather than disabled to fake missing hints", camera.eventPolls.get() > 0)
        assertEquals("No contents or other event may drive this review", 0, camera.deliveredEvents.get())
        assertTrue(history.state.value.entries.isEmpty())
        assertTrue(savedRows().isEmpty())

        compose.onNodeWithTag("capture-review-button").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = model.uiState.value
            state.mediaPreviewItem?.id == movieId && !state.mediaPreviewLoading &&
                state.mediaStreamSource != null && !state.isBusy(CameraOperation.MEDIA) && !state.mediaLibraryLoading
        }
        assertTrue(requireNotNull(model.uiState.value.mediaPreviewItem).isVideo)
        assertNull("The video viewer must use the original stream, not JPEG preview bytes", model.uiState.value.mediaPreviewBytes)
        compose.onNodeWithTag("media-viewer-content").assertIsDisplayed()
        assertDecodedMovieAdvances()
        val originalReadsBeforeSave = camera.originalReads.size
        compose.onNode(hasContentDescription(text(R.string.download_media, movieName)) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = model.uiState.value
            !state.isBusy(CameraOperation.MEDIA) &&
                state.mediaSaveFeedback[movieId] == MediaSaveFeedback.Saved(cameraGalleryPath(camera.model))
        }
        assertTrue("Save must fetch an original after preview playback", camera.originalReads.size > originalReadsBeforeSave)
        assertEquals(movieId, model.uiState.value.mediaPreviewItem?.id)
        compose.onNode(hasText(text(R.string.media_saved_location, cameraGalleryPath(camera.model))) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
        assertPublishedOriginal()
        runBlocking { withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { history.awaitIdle() } }
        val receipt = history.state.value.entries.single()
        assertEquals(movieName, receipt.filename)
        assertEquals(DownloadHistoryDestination.GALLERY, receipt.destination)
        assertEquals(DownloadHistoryOutcome.COMPLETED, receipt.outcome)
        assertNotNull(receipt.finishedAtMillis)
        assertNull(history.state.value.warning)

        compose.onNodeWithContentDescription(text(R.string.close_media_preview)).performClick()
        compose.onNodeWithTag("download-history-open").performScrollTo().performClick()
        compose.onNodeWithTag("download-history-dialog").assertIsDisplayed()
        compose.onNodeWithTag("download-history-list").performScrollToNode(hasText(movieName))
        compose.onNode(hasText(movieName) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.download_history_outcome_completed)) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
        compose.onNodeWithTag("download-history-close").performClick()
    }

    private fun assertDecodedMovieAdvances() {
        var snapshot: PlaybackSnapshot? = null
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            snapshot = compose.runOnIdle {
                WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findPlayerView)?.player?.let { player ->
                    PlaybackSnapshot(player.playbackState, player.currentPosition,
                        player.videoSize.width, player.videoSize.height, player.playerError?.message)
                }
            }
            snapshot?.let {
                it.error == null && it.width > 0 && it.height > 0 && it.positionMillis > 0 &&
                    it.state in setOf(Player.STATE_READY, Player.STATE_ENDED)
            } == true
        }
        val decoded = requireNotNull(snapshot)
        assertNull("The actual viewer player must decode the valid H.264 stream", decoded.error)
        assertTrue(decoded.width > 0 && decoded.height > 0 && decoded.positionMillis > 0)
        assertTrue(camera.originalReads.contains("/ccapi/media/$movieId"))
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView && view.isShown) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findPlayerView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun assertOnlyOneRecordingPair() {
        assertEquals(listOf("/ccapi/record/start", "/ccapi/record/stop"), camera.recordingWrites.toList())
        assertEquals(listOf("POST /ccapi/record/start", "POST /ccapi/record/stop"), camera.mutations.toList())
        assertEquals(0, camera.deliveredEvents.get())
    }

    private fun assertPublishedOriginal() {
        val row = savedRows().single()
        assertEquals(movieName, row.name)
        assertEquals("video/mp4", row.mimeType)
        assertEquals(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, row.mediaType)
        assertEquals(0, row.pending)
        assertArrayEquals(movieBytes, requireNotNull(resolver.openInputStream(row.uri)).use { it.readBytes() })
    }

    private fun savedRows(): List<SavedRow> = requireNotNull(resolver.query(
        collection.buildUpon().appendQueryParameter("includePending", "1").build(),
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE),
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(cameraGalleryPath(camera.model)), null,
    )).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(SavedRow(ContentUris.withAppendedId(collection, cursor.getLong(0)),
                cursor.getString(1), cursor.getInt(2), cursor.getString(3), cursor.getInt(4)))
        }
    }

    private fun mediaItemJson(id: String): JSONObject {
        check(id == movieId || id == previousId) { "Unexpected synthetic media ID" }
        val movie = id == movieId
        return JSONObject().put("id", id).put("name", if (movie) movieName else "SYNTHETIC_PREVIOUS.JPG")
            .put("kind", if (movie) "video" else "image")
            .put("size_bytes", if (movie) movieBytes.size else camera.imageBytes.size)
            .put("content_type", if (movie) "video/mp4" else "image/jpeg")
            // Deliberately fixed fixture dates, unrelated to any camera or user's recordings.
            .put("capture_time", if (movie) "2000-01-01T00:00:02Z" else "2000-01-01T00:00:01Z")
    }

    private fun movieResponse(range: String?): MockResponse {
        val offset = range?.let { Regex("bytes=(\\d+)-").matchEntire(it)?.groupValues?.get(1)?.toLong() }
        if (range != null && (offset == null || offset !in 0L until movieBytes.size.toLong())) {
            return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${movieBytes.size}")
        }
        val start = offset?.toInt() ?: 0
        return MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
            .setBody(Buffer().write(movieBytes, start, movieBytes.size - start)).apply {
                if (offset != null) setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-${movieBytes.lastIndex}/${movieBytes.size}")
            }
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
    private data class SavedRow(val uri: Uri, val name: String, val pending: Int, val mimeType: String, val mediaType: Int)
    private data class PlaybackSnapshot(val state: Int, val positionMillis: Long, val width: Int, val height: Int, val error: String?)
}
