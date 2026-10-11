package dev.openeos.control.ui

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
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
import dev.openeos.control.R
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryFileStorage
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryStore
import dev.openeos.control.data.DownloadHistoryWarning
import dev.openeos.control.data.DownloadHistoryWritePhase
import dev.openeos.control.data.DownloadWorklistAdmission
import dev.openeos.control.data.DownloadWorklistRowStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        compose.pressFocusedDialogBack(compose.activity, "download-history-dialog")
        compose.onNodeWithTag("download-history-dialog").assertDoesNotExist()
        assertEquals(0, camera.server.requestCount)
    }

    @Test fun coldOfflineWorklistKeepsFiveCompletedOneUnconfirmedAndFortyFourQueuedRows() {
        val directory = File(root, "cold-worklist")
        val filenames = (1..50).map { "SYNTHETIC-${it.toString().padStart(2, '0')}.JPG" }
        val prior = newHistory(directory)
        runBlocking {
            withTimeout(SESSION_TEST_TIMEOUT_MILLIS) {
                prior.state.first { !it.loading }
                val admission = prior.admitWorklist(prior.captureRequest(), filenames, DownloadHistoryDestination.GALLERY)
                assertTrue(admission is DownloadWorklistAdmission.Admitted)
                val token = (admission as DownloadWorklistAdmission.Admitted).token
                repeat(5) { index ->
                    val started = prior.beginWorklistRow(token, index)
                    assertTrue(started is DownloadWorklistRowStart.Started)
                    prior.recordFinished((started as DownloadWorklistRowStart.Started).receipt, DownloadHistoryOutcome.COMPLETED)
                }
                assertTrue(prior.beginWorklistRow(token, 5) is DownloadWorklistRowStart.Started)
                prior.awaitIdle()
            }
        }
        val originalRows = prior.state.value.worklists.single().rows
        assertEquals(DownloadHistoryOutcome.IN_PROGRESS, originalRows[5].receipt?.outcome)
        val durable = DownloadHistoryFileStorage(directory).readSnapshot()
        assertTrue(durable.entries.isEmpty())
        assertEquals(originalRows, durable.worklists.single().rows)
        historyScopes.first().cancel()
        runBlocking { withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { historyScopes.first().coroutineContext[Job]?.join() } }

        history = newHistory(directory)
        installApp()
        val cold = history.state.value
        val rows = cold.worklists.single().rows
        assertTrue(cold.entries.isEmpty())
        assertEquals(50, rows.size)
        assertEquals(filenames, rows.map { it.filename })
        assertEquals(originalRows.map { it.rowId }, rows.map { it.rowId })
        assertEquals(50, rows.map { it.rowId }.toSet().size)
        assertEquals(6, cold.actualReceipts().size)
        assertEquals(List(5) { DownloadHistoryOutcome.COMPLETED } + DownloadHistoryOutcome.UNCONFIRMED,
            rows.take(6).map { it.receipt?.outcome })
        assertEquals(44, rows.count { it.receipt == null })
        assertTrue(rows.drop(6).all { it.receipt == null })
        assertNull(rows[5].receipt?.finishedAtMillis)
        assertTrue(!model.uiState.value.connected)
        assertTrue(model.uiState.value.mediaSaveFeedback.isEmpty())
        assertTrue(savedUris().isEmpty())

        openHistory()
        compose.onNodeWithTag("download-history-list").performScrollToNode(hasText(filenames.first()))
        compose.onNodeWithTag("download-worklist-row-${rows.first().rowId}")
            .assert(hasAnyDescendant(hasText(text(R.string.download_history_outcome_completed))))
        compose.onNodeWithTag("download-history-list").performScrollToNode(hasText(filenames[5]))
        compose.onNodeWithTag("download-worklist-row-${rows[5].rowId}")
            .assert(hasAnyDescendant(hasText(text(R.string.download_history_outcome_unconfirmed))))
        compose.onNodeWithTag("download-history-list").performScrollToNode(hasText(filenames.last()))
        compose.onNodeWithTag("download-worklist-row-${rows.last().rowId}").assertIsDisplayed()
            .assert(hasAnyDescendant(hasText(text(R.string.download_history_outcome_queued))))
            .assert(hasAnyDescendant(hasText(text(R.string.download_history_worklist_position, 50, 50))))
        assertEquals(0, camera.server.requestCount)
        compose.onNodeWithTag("download-history-close").assertIsDisplayed().performClick()
        openHistory()
        compose.onNodeWithTag("download-history-list").performScrollToNode(hasText(filenames.last()))
        compose.onNodeWithTag("download-history-clear").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("download-history-clear-confirm-button").performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.downloadHistoryState.value.let { it.entries.isEmpty() && it.worklists.isEmpty() }
        }
        compose.onNodeWithText(text(R.string.download_history_empty)).assertIsDisplayed()
        assertNull(history.state.value.warning)
        assertTrue(history.state.value.writable)
        val clearedSnapshot = DownloadHistoryFileStorage(directory).readSnapshot()
        assertTrue(clearedSnapshot.entries.isEmpty())
        assertTrue(clearedSnapshot.worklists.isEmpty())
        val restored = newHistory(directory)
        runBlocking { withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { restored.state.first { !it.loading } } }
        assertNull(restored.state.value.warning)
        assertTrue(restored.state.value.writable)
        assertTrue(restored.state.value.entries.isEmpty())
        assertTrue(restored.state.value.worklists.isEmpty())
        assertTrue(savedUris().isEmpty())
        assertEquals(0, camera.server.requestCount)
    }

    @Test fun historyWriteFailureAfterGalleryPublicationPreservesSavedBytesAndDoesNotRetry() {
        val moves = AtomicInteger()
        val directory = File(root, "write-failure")
        history = newHistory(directory) { phase ->
            if (phase == DownloadHistoryWritePhase.BEFORE_MOVE && moves.incrementAndGet() == 3) {
                // The whole roster and the pending row must both be durable before the GET.
                // Fail only the terminal replacement, after the real Gallery publication.
                assertSavedOriginals(1)
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
        assertEquals(3, moves.get())
        assertTrue(history.state.value.entries.isEmpty())
        assertEquals(listOf(item.name), history.state.value.worklists.single().rows.map { it.filename })
        val completed = history.state.value.actualReceipts().single()
        assertEquals(DownloadHistoryOutcome.COMPLETED, completed.outcome)
        assertNull(model.uiState.value.error)
        assertEquals(listOf("/ccapi/media/" + item.id), camera.originalReads.toList())
        assertSavedOriginals(1)
        val restored = newHistory(directory)
        runBlocking { restored.state.first { !it.loading } }
        assertTrue(restored.state.value.entries.isEmpty())
        assertEquals(listOf(item.name), restored.state.value.worklists.single().rows.map { it.filename })
        val pending = restored.state.value.actualReceipts().single()
        assertEquals(completed.receiptId, pending.receiptId)
        assertEquals(DownloadHistoryOutcome.UNCONFIRMED, pending.outcome)
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
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { model.downloadHistoryState.value.actualReceipts().size == 2 }
        val rows = history.state.value.worklists.single().rows
        assertTrue(history.state.value.entries.isEmpty())
        assertEquals(items.map { it.second }, rows.map { it.filename })
        assertEquals(DownloadHistoryOutcome.COMPLETED, rows[0].receipt?.outcome)
        assertEquals(DownloadHistoryOutcome.IN_PROGRESS, rows[1].receipt?.outcome)
        assertNull(rows[2].receipt)
        openHistory() // Must remain reachable while MEDIA is busy and the selection toolbar is shown.
        compose.onNodeWithTag("download-history-clear").performClick()
        compose.onNodeWithTag("download-history-clear-confirm-button").performClick()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.downloadHistoryState.value.let { it.entries.isEmpty() && it.worklists.isEmpty() }
        }
        gate.release()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.lastMediaBatchResult?.succeededItems == 3 && !model.uiState.value.isBusy(CameraOperation.MEDIA)
        }
        runBlocking { history.awaitIdle() }
        assertTrue(history.state.value.entries.isEmpty())
        assertTrue(history.state.value.worklists.isEmpty())
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
        // Disconnect immediately clears session UI; the cancelled transfer still owns cleanup
        // and asynchronously submits its terminal receipt to the process writer. A barrier
        // queued before that submission is not evidence that cancellation has finished.
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            history.state.value.actualReceipts().singleOrNull()?.outcome == DownloadHistoryOutcome.CANCELLED
        }
        runBlocking { history.awaitIdle() }
        assertTrue(history.state.value.entries.isEmpty())
        assertEquals(listOf(firstItem.name), history.state.value.worklists.single().rows.map { it.filename })
        assertEquals(DownloadHistoryOutcome.CANCELLED, history.state.value.actualReceipts().single().outcome)
        val previousReceipt = history.state.value.actualReceipts().single().receiptId
        connectAndOpenAlbum()
        val replacement = model.uiState.value.mediaItems.first { it.id == "one" }
        compose.runOnIdle { model.downloadMediaBatch(compose.activity, listOf(replacement)) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            model.uiState.value.mediaSaveFeedback[replacement.id] is MediaSaveFeedback.Saved && !model.uiState.value.busy
        }
        runBlocking { history.awaitIdle() }
        assertTrue(history.state.value.entries.isEmpty())
        assertEquals(2, history.state.value.worklists.size)
        assertTrue(history.state.value.worklists.all { it.rows.size == 1 })
        val entries = history.state.value.actualReceipts()
        assertEquals(2, entries.size)
        assertEquals(listOf(DownloadHistoryOutcome.COMPLETED, DownloadHistoryOutcome.CANCELLED), entries.map { it.outcome })
        assertEquals(listOf("ONE.JPG", "ONE.JPG"), entries.map { it.filename })
        assertEquals(previousReceipt, entries.last().receiptId)
        assertNotEquals(previousReceipt, entries.first().receiptId)
        assertEquals(listOf("/ccapi/media/one", "/ccapi/media/one"), camera.originalReads.toList())
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
        compose.awaitForegroundActivityWindow(compose.activity)
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
