package dev.openeos.control.ui

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.R
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryFileStorage
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryStore
import dev.openeos.control.data.DownloadHistoryWarning
import dev.openeos.control.data.DownloadHistoryWritePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real App/HTTP/MediaStore flow. Synthetic originals and private test directories only. */
@SdkSuppress(minSdkVersion = 29)
class CameraDownloadHistoryJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val camera = CameraSessionTestSimulator("history-${UUID.randomUUID()}")
    private val viewModels = ViewModelStore()
    private val historyScopes = mutableListOf<CoroutineScope>()
    private val histories = mutableListOf<DownloadHistoryStore>()
    private lateinit var root: File
    private lateinit var history: DownloadHistoryStore
    private lateinit var model: CameraViewModel
    private val items = listOf("one" to "ONE.JPG", "two" to "TWO.JPG", "three" to "THREE.JPG")

    @Before fun setUp() {
        root = File(compose.activity.cacheDir, "download-history-fixture-${UUID.randomUUID()}")
        check(root.mkdirs())
        camera.intercept = { request ->
            if (request.method == "GET" && request.requestUrl?.encodedPath == "/ccapi/media") {
                camera.json(JSONObject().put("items", JSONArray(items.map { (id, name) ->
                    JSONObject().put("id", id).put("name", name).put("kind", "image")
                        .put("size_bytes", camera.imageBytes.size).put("capture_time", "2026-10-05T10:00:00Z")
                })).toString())
            } else null
        }
        // Resolve the peer URL on the instrumentation thread, before any Compose/main-thread work.
        camera.start()
    }

    @After fun tearDown() {
        camera.releaseGates()
        try {
            if (::model.isInitialized) {
                val job = requireNotNull(model.viewModelScope.coroutineContext[Job])
                compose.runOnIdle { viewModels.clear() }
                compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { job.isCompleted }
            }
            runBlocking {
                histories.forEach { it.awaitIdle() }
                historyScopes.forEach { scope -> scope.cancel(); scope.coroutineContext[Job]?.join() }
            }
        } finally {
            try { camera.server.shutdown() } finally {
                // Query/delete only the current test's random synthetic camera folder.
                savedUris().forEach { compose.activity.contentResolver.delete(it, null, null) }
                if (::root.isInitialized) root.deleteRecursively()
            }
        }
    }

    @Test fun coldOfflineHistoryShowsConfirmedAndUnconfirmedWithoutCameraCommands() {
        val directory = File(root, "cold")
        val prior = newHistory(directory)
        runBlocking {
            prior.state.first { !it.loading }
            val request = prior.captureRequest()
            val saved = prior.begin(request, "CONFIRMED.JPG", DownloadHistoryDestination.GALLERY)
            prior.recordFinished(saved, DownloadHistoryOutcome.COMPLETED)
            prior.begin(request, "UNFINISHED.JPG", DownloadHistoryDestination.DOCUMENT)
            prior.awaitIdle()
        }
        historyScopes.first().cancel()
        runBlocking { historyScopes.first().coroutineContext[Job]?.join() }
        history = newHistory(directory)
        installApp()
        openHistory()
        compose.onNodeWithTag("download-history-list").performScrollToNode(hasText("CONFIRMED.JPG"))
        compose.onNodeWithText(text(R.string.download_history_outcome_completed)).assertIsDisplayed()
        compose.onNodeWithTag("download-history-list").performScrollToNode(hasText("UNFINISHED.JPG"))
        compose.onNodeWithText(text(R.string.download_history_outcome_unconfirmed)).assertIsDisplayed()
        assertTrue(!model.uiState.value.connected)
        assertEquals(0, camera.server.requestCount)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("download-history-dialog").assertDoesNotExist()
        assertEquals(0, camera.server.requestCount)
    }

    @Test fun historyWriteFailureAfterGalleryPublicationPreservesSavedBytesAndDoesNotRetry() {
        val moves = AtomicInteger()
        val directory = File(root, "write-failure")
        history = newHistory(directory) { phase ->
            if (phase == DownloadHistoryWritePhase.BEFORE_MOVE && moves.incrementAndGet() > 1) {
                throw IOException("Synthetic receipt write failure")
            }
        }
        installApp()
        connectAndOpenAlbum()
        val item = model.uiState.value.mediaItems.first()
        compose.onNodeWithContentDescription(text(R.string.preview_media, item.name)).performScrollTo().performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.uiState.value.mediaPreviewLoading }
        compose.onNodeWithContentDescription(text(R.string.download_media, item.name)).performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.mediaSaveFeedback[item.id] is MediaSaveFeedback.Saved &&
                !model.uiState.value.isBusy(CameraOperation.MEDIA)
        }
        runBlocking { history.awaitIdle() }
        assertEquals(DownloadHistoryWarning.WRITE_FAILED, history.state.value.warning)
        assertEquals(DownloadHistoryOutcome.COMPLETED, history.state.value.entries.single().outcome)
        assertNull(model.uiState.value.error)
        assertEquals(listOf("/ccapi/media/" + item.id), camera.originalReads.toList())
        assertSavedOriginals(1)
        val restored = newHistory(directory)
        runBlocking { restored.state.first { !it.loading } }
        assertEquals(DownloadHistoryOutcome.UNCONFIRMED, restored.state.value.entries.single().outcome)
        assertTrue(model.uiState.value.mediaSaveFeedback[item.id] is MediaSaveFeedback.Saved)
    }

    @Test fun clearingWhileBatchIsActiveDoesNotRecreateRecordsForLaterItems() {
        history = newHistory(File(root, "active-clear"))
        val gate = camera.gate()
        val ordinary = camera.intercept
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            if (url.encodedPath == "/ccapi/media/two" && url.query == null) {
                gate.blockResponse()
                camera.imageResponse()
            } else ordinary(request)
        }
        installApp()
        connectAndOpenAlbum()
        compose.onNodeWithContentDescription(text(R.string.preview_media, "ONE.JPG"))
            .performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithContentDescription(text(R.string.select_all_media)).performClick()
        compose.onNodeWithContentDescription(text(R.string.download_selected_media, 3)).performClick()
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { model.downloadHistoryState.value.entries.size == 2 }
        openHistory() // Must remain reachable while MEDIA is busy and the selection toolbar is shown.
        compose.onNodeWithTag("download-history-clear").performClick()
        compose.onNodeWithTag("download-history-clear-confirm-button").performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { model.downloadHistoryState.value.entries.isEmpty() }
        gate.release()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.lastMediaBatchResult?.succeededItems == 3 && !model.uiState.value.isBusy(CameraOperation.MEDIA)
        }
        runBlocking { history.awaitIdle() }
        assertTrue(history.state.value.entries.isEmpty())
        assertEquals(listOf("/ccapi/media/one", "/ccapi/media/two", "/ccapi/media/three"), camera.originalReads.toList())
        assertSavedOriginals(3)
        compose.onNodeWithTag("download-history-close").performClick()
        compose.onNodeWithTag("download-history-dialog").assertDoesNotExist()
    }

    @Test fun corruptHistoryAndFailedClearDoNotBlockCameraConnectionOrEraseInput() {
        val directory = File(root, "corrupt").apply { check(mkdirs()) }
        val snapshot = File(directory, "history.json").apply { writeText("SYNTHETIC_INVALID_SNAPSHOT") }
        val original = snapshot.readBytes()
        history = newHistory(directory) { throw IOException("Synthetic history reset failure") }
        installApp()
        openHistory()
        compose.onNodeWithText(text(R.string.download_history_warning_read)).assertIsDisplayed()
        compose.onNodeWithTag("download-history-clear").performClick()
        compose.onNodeWithTag("download-history-clear-confirm-button").performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.downloadHistoryState.value.warning == DownloadHistoryWarning.CLEAR_FAILED
        }
        compose.onNodeWithText(text(R.string.download_history_warning_clear)).assertIsDisplayed()
        assertArrayEquals(original, snapshot.readBytes())
        assertEquals(0, camera.server.requestCount)
        compose.onNodeWithTag("download-history-close").performClick()
        connectAndOpenAlbum()
        assertTrue(model.uiState.value.connected)
        assertArrayEquals(original, snapshot.readBytes())
    }

    @Test fun cancelledSessionAndReplacementSaveWithSameItemIdKeepSeparateReceipts() {
        history = newHistory(File(root, "reconnect"))
        val firstOriginal = AtomicBoolean(true)
        val gate = camera.gate()
        val ordinary = camera.intercept
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            if (url.encodedPath == "/ccapi/media/one" && url.query == null && firstOriginal.compareAndSet(true, false)) {
                gate.blockResponse()
                camera.imageResponse()
            } else ordinary(request)
        }
        installApp()
        connectAndOpenAlbum()
        val firstItem = model.uiState.value.mediaItems.first { it.id == "one" }
        compose.runOnIdle { model.downloadMediaBatch(compose.activity, listOf(firstItem)) }
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        compose.runOnIdle { model.disconnect() }
        gate.release()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.uiState.value.connected && !model.uiState.value.busy }
        runBlocking { history.awaitIdle() }
        assertEquals(DownloadHistoryOutcome.CANCELLED, history.state.value.entries.single().outcome)
        val previousReceipt = history.state.value.entries.single().receiptId
        connectAndOpenAlbum()
        val replacement = model.uiState.value.mediaItems.first { it.id == "one" }
        compose.runOnIdle { model.downloadMediaBatch(compose.activity, listOf(replacement)) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.mediaSaveFeedback[replacement.id] is MediaSaveFeedback.Saved && !model.uiState.value.busy
        }
        runBlocking { history.awaitIdle() }
        val entries = history.state.value.entries
        assertEquals(2, entries.size)
        assertEquals(listOf(DownloadHistoryOutcome.COMPLETED, DownloadHistoryOutcome.CANCELLED), entries.map { it.outcome })
        assertEquals(listOf("ONE.JPG", "ONE.JPG"), entries.map { it.filename })
        assertEquals(previousReceipt, entries.last().receiptId)
        assertNotEquals(previousReceipt, entries.first().receiptId)
        assertSavedOriginals(1)
    }

    private fun newHistory(
        directory: File,
        beforeWrite: (DownloadHistoryWritePhase) -> Unit = {},
    ): DownloadHistoryStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(historyScopes::add)
        return DownloadHistoryStore(DownloadHistoryFileStorage(directory, beforeWrite), scope, Dispatchers.IO, System::currentTimeMillis)
            .also(histories::add)
    }

    private fun installApp() {
        model = CameraViewModel(downloadHistoryFactory = { history })
        viewModels.put("history-journey", model)
        compose.setContent { OpenEosControlApp(model) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.downloadHistoryState.value.loading }
    }

    private fun openHistory() {
        compose.onNodeWithTag("download-history-open").performScrollTo().performClick()
        compose.onNodeWithTag("download-history-dialog").assertIsDisplayed()
    }

    private fun connectAndOpenAlbum() {
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { !model.uiState.value.busy }
        compose.runOnIdle {
            model.setLiveViewAutoRefresh(false)
            model.useDevSimulatorPreset()
            model.setBaseUrl(camera.baseUrl)
            model.connect()
        }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.connected && !model.uiState.value.busy && !model.uiState.value.captureReviewLoading
        }
        compose.runOnIdle { model.setUiMode(UiMode.MEDIA) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.mediaItems.size == items.size && !model.uiState.value.mediaLibraryLoading
        }
    }

    private fun assertSavedOriginals(count: Int) {
        val resolver = compose.activity.contentResolver
        val uris = savedUris()
        assertEquals(count, uris.size)
        uris.forEach { uri ->
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            assertArrayEquals(camera.imageBytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    private fun savedUris(): List<Uri> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return compose.activity.contentResolver.query(collection.buildUpon().appendQueryParameter("includePending", "1").build(),
            arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(cameraGalleryPath(camera.model)), null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
}
