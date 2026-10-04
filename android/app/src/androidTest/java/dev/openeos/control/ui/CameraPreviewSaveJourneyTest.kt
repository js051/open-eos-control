package dev.openeos.control.ui

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.filters.SdkSuppress
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Production Compose -> ViewModel -> repository -> HTTP original stream -> Android MediaStore.
 * The synthetic camera exposes a new item only after the real shutter request. Display JPEGs
 * intentionally differ from originals, so saving a thumbnail/preview cannot satisfy the test.
 * These tests validate neither a physical camera nor an external document-picker provider.
 */
@SdkSuppress(minSdkVersion = 29)
class CameraPreviewSaveJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val camera = CameraSessionTestSimulator("preview-save-${UUID.randomUUID()}")
    private val store = ViewModelStore()
    private lateinit var viewModel: CameraViewModel
    private val captured = AtomicBoolean(false)
    private val shutterRequests = AtomicInteger()
    private val originalMode = AtomicReference(OriginalMode.COMPLETE)
    private val capturedId = "${camera.label}-captured"
    private val sameNameId = "${camera.label}-another-id"
    private val previousId = "${camera.label}-previous"
    private val capturedName = "SYNTHETIC_CAPTURE.JPG"
    // Larger than the real transfer's 512 KiB reporting interval. A throttled response leaves
    // enough time to inspect nonzero progress and cancel, rather than testing headers only.
    private val originalBytes = camera.imageBytes + ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
    private val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val resolver get() = compose.activity.contentResolver

    @Before
    fun setUp() {
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            when {
                request.method == "POST" && url.encodedPath == "/ccapi/capture/still" -> {
                    shutterRequests.incrementAndGet()
                    captured.set(true)
                    camera.json("{}")
                }
                request.method == "GET" && url.encodedPath == "/ccapi/media" -> camera.json(mediaJson())
                request.method == "GET" && url.encodedPath.startsWith("/ccapi/media/") &&
                    url.queryParameter("kind") == "info" -> {
                    val id = url.pathSegments.last()
                    camera.json(mediaItemJson(id).toString())
                }
                request.method == "GET" && url.encodedPath.startsWith("/ccapi/media/") &&
                    url.query == null -> when (originalMode.get()) {
                    OriginalMode.COMPLETE -> originalResponse(originalBytes)
                    OriginalMode.SLOW -> originalResponse(originalBytes)
                        .throttleBody(64 * 1024L, 500, TimeUnit.MILLISECONDS)
                    // A complete HTTP body whose size contradicts camera metadata is terminal
                    // corruption, not a flaky transport eligible for silent automatic retries.
                    OriginalMode.WRONG_LENGTH -> originalResponse(camera.imageBytes)
                    null -> error("A synthetic response mode is required.")
                }
                else -> null
            }
        }
        // start() caches MockWebServer.url() on this instrumentation thread. Never resolve
        // its hostname in a Compose/UI callback or relax Android's network StrictMode.
        camera.start()
        viewModel = CameraViewModel()
        store.put("preview-save-journey", viewModel)
        compose.setContent { OpenEosControlApp(viewModel) }
        compose.waitForIdle()
        connect()
    }

    @After
    fun tearDown() {
        camera.releaseGates()
        try {
            if (::viewModel.isInitialized) {
                val job = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
                compose.runOnIdle { store.clear() }
                compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { job.isCompleted }
            }
        } finally {
            try {
                camera.server.shutdown()
            } finally {
                // The random synthetic model is this instance's private MediaStore folder.
                // Never enumerate or delete unrelated user media, even after a failed test.
                savedRows().forEach { resolver.delete(it.uri, null, null) }
            }
        }
    }

    @Test
    fun captureOnceOpensTheNewRecentItemAndSavesItsOriginalFromPreview() {
        val item = captureAndOpenPreview()
        assertTrue(camera.originalReads.isEmpty())
        assertArrayEquals(camera.imageBytes, viewModel.uiState.value.mediaPreviewBytes)
        downloadButton(item).assertIsDisplayed().assertIsEnabled().performClick()

        awaitSaved(item)
        dialogText(savedMessage()).assertIsDisplayed()
        assertPublishedOriginal()
        assertEquals(listOf("/ccapi/media/$capturedId"), camera.originalReads.toList())
        assertEquals(1, shutterRequests.get())
        assertEquals(capturedId, viewModel.uiState.value.mediaPreviewItem?.id)
    }

    @Test
    fun visibleProgressCanBeCancelledAndBusyDuplicateTapCreatesNoSecondDownload() {
        val item = captureAndOpenPreview()
        originalMode.set(OriginalMode.SLOW)
        downloadButton(item).performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val feedback = viewModel.uiState.value.mediaSaveFeedback[item.id]
            feedback is MediaSaveFeedback.Saving && feedback.progress.bytesTransferred > 0L &&
                feedback.progress.bytesTransferred < originalBytes.size
        }
        val pending = savedRows().single()
        assertEquals(1, pending.pending)
        val indicator = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and
                hasAnyAncestor(isDialog()),
        ).assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertTrue("Preview must show determinate, nonzero original-byte progress", indicator.current > 0f)
        assertTrue(indicator.current < 1f)

        downloadButton(item).assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(1, camera.originalReads.size)
        assertEquals(1, savedRows().size)
        dialogAction(R.string.cancel_media_download).assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            !state.isBusy(CameraOperation.MEDIA) && state.mediaSaveFeedback[item.id] is MediaSaveFeedback.Cancelled
        }

        assertTrue("Cancelling must remove the partially written MediaStore row", savedRows().isEmpty())
        assertNull(viewModel.uiState.value.lastDownloadLocation)
        assertFalse(viewModel.uiState.value.mediaSaveFeedback.values.any { it is MediaSaveFeedback.Saved })
        dialogText(savedMessage()).assertDoesNotExist()
        dialogText(compose.activity.getString(R.string.media_save_cancelled)).assertIsDisplayed()
        assertEquals(1, camera.originalReads.size)
        assertEquals(1, shutterRequests.get())
        assertEquals(item.id, viewModel.uiState.value.mediaPreviewItem?.id)
    }

    @Test
    fun corruptOriginalShowsFailureAndExplicitRetrySavesWithoutAnotherShutter() {
        val item = captureAndOpenPreview()
        originalMode.set(OriginalMode.WRONG_LENGTH)
        downloadButton(item).performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            !state.isBusy(CameraOperation.MEDIA) && state.mediaSaveFeedback[item.id] is MediaSaveFeedback.Failed
        }
        val failure = viewModel.uiState.value.mediaSaveFeedback.getValue(item.id) as MediaSaveFeedback.Failed
        assertTrue(failure.message.isNotBlank())
        val failureText = compose.activity.getString(R.string.media_save_failed, failure.message)
        dialogText(failureText).assertIsDisplayed()
        dialogText(savedMessage()).assertDoesNotExist()
        assertTrue("A rejected incomplete original must not remain in the gallery", savedRows().isEmpty())
        assertNull(viewModel.uiState.value.lastDownloadLocation)
        assertEquals(1, camera.originalReads.size)
        assertEquals(1, shutterRequests.get())

        originalMode.set(OriginalMode.COMPLETE)
        dialogAction(R.string.media_save_retry).assertIsDisplayed().assertIsEnabled().performClick()
        awaitSaved(item)
        dialogText(savedMessage()).assertIsDisplayed()
        dialogText(failureText).assertDoesNotExist()
        assertPublishedOriginal()
        assertEquals(listOf("/ccapi/media/$capturedId", "/ccapi/media/$capturedId"), camera.originalReads.toList())
        assertEquals("Retry must redownload the existing capture, never fire another shutter", 1, shutterRequests.get())
    }

    @Test
    fun successBelongsToExactItemIdAndCannotLeakAcrossReconnectOfTheSameCamera() {
        val item = captureAndOpenPreview()
        downloadButton(item).performClick()
        awaitSaved(item)
        dialogText(savedMessage()).assertIsDisplayed()

        dialogAction(R.string.next_media).assertIsDisplayed().performClick()
        awaitPreview(sameNameId)
        val sibling = requireNotNull(viewModel.uiState.value.mediaPreviewItem)
        assertEquals(item.name, sibling.name)
        assertNotEquals(item.id, sibling.id)
        assertNull(viewModel.uiState.value.mediaSaveFeedback[sibling.id])
        dialogText(savedMessage()).assertDoesNotExist()
        downloadButton(sibling).assertIsDisplayed().assertIsEnabled()

        dialogAction(R.string.previous_media).performClick()
        awaitPreview(item.id)
        dialogText(savedMessage()).assertIsDisplayed()
        dialogAction(R.string.close_media_preview).performClick()
        compose.runOnIdle { viewModel.disconnect() }
        connect()
        compose.runOnIdle {
            assertTrue("A connection is a new save-feedback session, even for identical media IDs", viewModel.uiState.value.mediaSaveFeedback.isEmpty())
            assertNull(viewModel.uiState.value.lastDownloadLocation)
            viewModel.setUiMode(UiMode.CONTROL)
        }
        compose.onNodeWithTag("capture-review-button").assertIsDisplayed().performClick()
        awaitPreview(item.id)
        dialogText(savedMessage()).assertDoesNotExist()
        downloadButton(item).assertIsEnabled()
        assertPublishedOriginal()
        assertEquals(1, camera.originalReads.size)
        assertEquals(1, shutterRequests.get())
    }

    @Test
    fun latePeerResponseStaysIsolatedFromTheReplacementSessionsSave() {
        val item = captureAndOpenPreview()
        val oldResponse = camera.gate()
        val replacementResponse = camera.gate()
        val oldResponseReturned = CountDownLatch(1)
        val attempts = AtomicInteger()
        val ordinaryResponse = camera.intercept
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            if (request.method == "GET" && url.encodedPath == "/ccapi/media/$capturedId" && url.query == null) {
                when (attempts.incrementAndGet()) {
                    1 -> {
                        oldResponse.blockResponse()
                        oldResponseReturned.countDown()
                        MockResponse().setResponseCode(503).setBody("Synthetic stale-session download failure")
                    }
                    2 -> {
                        replacementResponse.blockResponse()
                        originalResponse(originalBytes)
                    }
                    else -> error("An explicit save must issue exactly one request in each session")
                }
            } else ordinaryResponse(request)
        }
        val jobsBeforeSave = compose.runOnIdle {
            requireNotNull(viewModel.viewModelScope.coroutineContext[Job]).children.toSet()
        }
        downloadButton(item).performClick()
        assertTrue(oldResponse.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val oldJobs = compose.runOnIdle {
            requireNotNull(viewModel.viewModelScope.coroutineContext[Job]).children
                .filter { it !in jobsBeforeSave }.toList()
        }
        assertTrue("The production download must own an active ViewModel operation", oldJobs.isNotEmpty())
        assertEquals(1, savedRows().single().pending)

        compose.runOnIdle { viewModel.disconnect() }
        connect()
        assertTrue("Reconnect must finish joining the cancelled old operation before replacement work", oldJobs.all(Job::isCompleted))
        compose.runOnIdle {
            assertTrue(viewModel.uiState.value.mediaSaveFeedback.isEmpty())
            viewModel.setUiMode(UiMode.CONTROL)
        }
        compose.onNodeWithTag("capture-review-button").performClick()
        awaitPreview(item.id)
        downloadButton(item).performClick()
        assertTrue(replacementResponse.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            viewModel.uiState.value.mediaSaveFeedback[item.id] is MediaSaveFeedback.Saving
        }

        // The old client jobs have already completed. Release only their blocked HTTP peer
        // after the replacement owns the same item ID; its late response must remain isolated.
        oldResponse.release()
        assertTrue(oldResponseReturned.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertTrue(state.isBusy(CameraOperation.MEDIA))
            assertTrue(state.mediaSaveFeedback[item.id] is MediaSaveFeedback.Saving)
            assertEquals(item.name, state.activeMediaDownloadName)
            assertNull(state.lastDownloadLocation)
            assertNull(state.error)
        }
        dialogText(savedMessage()).assertDoesNotExist()
        dialogAction(R.string.cancel_media_download).assertIsDisplayed().assertIsEnabled()
        downloadButton(item).assertIsNotEnabled()
        assertEquals("Only the replacement session may retain a pending gallery entry", 1, savedRows().size)
        assertEquals(1, savedRows().single().pending)

        replacementResponse.release()
        awaitSaved(item)
        dialogText(savedMessage()).assertIsDisplayed()
        assertPublishedOriginal()
        assertEquals(2, camera.originalReads.size)
        assertEquals(1, shutterRequests.get())
    }

    private fun connect() {
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !viewModel.uiState.value.busy }
        compose.runOnIdle {
            viewModel.setLiveViewAutoRefresh(false)
            viewModel.useDevSimulatorPreset()
            viewModel.setBaseUrl(camera.baseUrl)
            viewModel.connect()
        }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            state.connected && state.info?.model == camera.model && !state.busy &&
                state.captureReviewItem != null && !state.captureReviewLoading
        }
    }

    private fun captureAndOpenPreview(): CameraMediaItem {
        assertEquals(previousId, viewModel.uiState.value.captureReviewItem?.id)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.capture_photo))
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            !state.busy && state.captureReviewItem?.id == capturedId && !state.captureReviewLoading
        }
        assertEquals(1, shutterRequests.get())
        compose.onNodeWithTag("capture-review-button").assertIsDisplayed().performClick()
        awaitPreview(capturedId)
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !viewModel.uiState.value.mediaLibraryLoading }
        val state = viewModel.uiState.value
        assertEquals(MediaLibraryScope.RECENT, state.mediaLibraryScope)
        assertTrue(state.mediaItems.any { it.id == capturedId })
        assertTrue(camera.previewReads.contains("/ccapi/media/$capturedId"))
        return requireNotNull(state.mediaPreviewItem)
    }

    private fun awaitPreview(id: String) = compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
        val state = viewModel.uiState.value
        state.mediaPreviewItem?.id == id && !state.mediaPreviewLoading &&
            state.mediaPreviewBytes != null && !state.isBusy(CameraOperation.MEDIA)
    }

    private fun awaitSaved(item: CameraMediaItem) = compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
        val state = viewModel.uiState.value
        !state.isBusy(CameraOperation.MEDIA) &&
            state.mediaSaveFeedback[item.id] == MediaSaveFeedback.Saved(cameraGalleryPath(camera.model))
    }

    private fun downloadButton(item: CameraMediaItem): SemanticsNodeInteraction = compose.onNode(
        hasContentDescription(compose.activity.getString(R.string.download_media, item.name)) and
            hasAnyAncestor(isDialog()),
    )

    private fun dialogText(text: String): SemanticsNodeInteraction = compose.onNode(
        hasText(text) and hasAnyAncestor(isDialog()),
    )

    private fun dialogAction(resource: Int): SemanticsNodeInteraction {
        val text = compose.activity.getString(resource)
        return compose.onNode((hasContentDescription(text) or hasText(text)) and hasAnyAncestor(isDialog()))
    }

    private fun savedMessage() = compose.activity.getString(R.string.media_saved_location, cameraGalleryPath(camera.model))

    private fun assertPublishedOriginal() {
        val row = savedRows().single()
        assertEquals(capturedName, row.name)
        assertEquals(0, row.pending)
        assertArrayEquals(originalBytes, requireNotNull(resolver.openInputStream(row.uri)).use { it.readBytes() })
    }

    private fun savedRows(): List<SavedRow> = requireNotNull(resolver.query(
        collection.buildUpon().appendQueryParameter("includePending", "1").build(),
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING),
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        arrayOf(cameraGalleryPath(camera.model)),
        null,
    )).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(SavedRow(
                ContentUris.withAppendedId(collection, cursor.getLong(0)),
                cursor.getString(1),
                cursor.getInt(2),
            ))
        }
    }

    private fun mediaJson(): String {
        val ids = if (captured.get()) listOf(capturedId, sameNameId, previousId) else listOf(previousId)
        return JSONObject().put("items", JSONArray(ids.map(::mediaItemJson))).toString()
    }

    private fun mediaItemJson(id: String): JSONObject = JSONObject()
        .put("id", id)
        .put("name", if (id == previousId) "SYNTHETIC_PREVIOUS.JPG" else capturedName)
        .put("kind", "image")
        .put("size_bytes", originalBytes.size)
        .put("capture_time", when (id) {
            capturedId -> "2026-09-01T00:00:03Z"
            sameNameId -> "2026-09-01T00:00:02Z"
            else -> "2026-09-01T00:00:01Z"
        })

    private fun originalResponse(bytes: ByteArray): MockResponse = MockResponse()
        .setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))

    private data class SavedRow(val uri: Uri, val name: String, val pending: Int)
    private enum class OriginalMode { COMPLETE, SLOW, WRONG_LENGTH }
}
