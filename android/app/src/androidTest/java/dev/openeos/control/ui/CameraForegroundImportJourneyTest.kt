package dev.openeos.control.ui

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.filters.SdkSuppress
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaItem
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Production Compose -> ViewModel -> repository -> synthetic HTTP originals -> real MediaStore.
 * Ordinary journeys enable through the real disclosure and retain production polling/stability
 * intervals. Fault/race journeys use the public enable method with a wrapped resolver forwarding
 * to real MediaStore; only the exact row inserted into this instance's random folder is intercepted.
 * No fake output callback, physical camera, SAF provider, or compatibility claim is involved.
 */
@SdkSuppress(minSdkVersion = 29)
class CameraForegroundImportJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val camera = CameraSessionTestSimulator("foreground-import-${UUID.randomUUID()}")
    private val viewModels = ViewModelStore()
    private val displayedModel = mutableStateOf<CameraViewModel?>(null)
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var model: CameraViewModel
    private lateinit var history: DownloadHistoryStore
    private lateinit var historyDirectory: File
    private var serverStarted = false
    private var ownsCleanupWarning = false
    private var modelWorkRetired = true
    private val providers = mutableListOf<OwnedGalleryProvider>()
    private val baselineJpeg = "${camera.label}-baseline-jpeg"
    private val baselineRaw = "${camera.label}-baseline-raw"
    private val offJpeg = "${camera.label}-while-off"
    private val newJpeg = "${camera.label}-new-jpeg"
    private val newRaw = "${camera.label}-new-raw"
    private val nextJpeg = "${camera.label}-next-jpeg"
    private val baseline = listOf(baselineJpeg, baselineRaw)
    private val inventory = AtomicReference(baseline)
    private val originalMode = AtomicReference(OriginalMode.COMPLETE)
    private val metadataReads = CopyOnWriteArrayList<MetadataRead>()
    // A valid display JPEG plus distinct original payload. This exceeds the production 512 KiB
    // progress interval, so cancellation observes actual written bytes, not merely an HTTP header.
    private val originalBytes = camera.imageBytes + ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
    private val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val resolver get() = compose.activity.contentResolver
    private val status get() = model.uiState.value.foregroundJpegImport

    @Before
    fun setUp() {
        // Preserve any warning that predates this test. Only a warning produced by the owned
        // failure fixture may be acknowledged in cleanup; never clear the preference file.
        assumeFalse("A pre-existing cleanup warning must be left for its owner to acknowledge",
            compose.activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(CLEANUP_WARNING, false))
        historyDirectory = File(compose.activity.cacheDir, "foreground-import-history-${UUID.randomUUID()}")
        check(historyDirectory.mkdirs())
        history = DownloadHistoryStore(historyDirectory, historyScope)
        camera.intercept = { request ->
            val url = requireNotNull(request.requestUrl)
            when {
                request.method == "GET" && url.encodedPath == "/ccapi/media" -> camera.json(
                    JSONObject().put("items", JSONArray(inventory.get().map(::mediaJson))).toString(),
                )
                request.method == "GET" && url.encodedPath.startsWith("/ccapi/media/") &&
                    url.queryParameter("kind") == "info" -> {
                    val id = url.pathSegments.last()
                    metadataReads += MetadataRead(id, System.nanoTime())
                    // Fresh-info uses Canon's filesize field, not the listing's cached size_bytes.
                    camera.json(mediaJson(id).put("filesize", originalBytes.size).toString())
                }
                request.method == "GET" && url.encodedPath.startsWith("/ccapi/media/") &&
                    url.query == null -> when (originalMode.get()) {
                    OriginalMode.COMPLETE -> originalResponse(originalBytes)
                    OriginalMode.SLOW -> originalResponse(originalBytes)
                        .throttleBody(64 * 1024L, 300, TimeUnit.MILLISECONDS)
                    OriginalMode.WRONG_LENGTH -> originalResponse(camera.imageBytes)
                    OriginalMode.HTTP_FAILURE -> MockResponse().setResponseCode(503)
                        .setBody("Synthetic single-attempt original failure")
                    null -> error("A synthetic response mode is required")
                }
                else -> null
            }
        }
        // Cache MockWebServer's URL off the Android main thread; never weaken StrictMode.
        camera.start()
        serverStarted = true
        model = newModel()
        displayedModel.value = model
        compose.setContent { displayedModel.value?.let { OpenEosControlApp(it) } }
        connectAndOpenGallery()
        assertEquals(ForegroundImportPhase.OFF, status.phase)
        assertTrue(history.state.value.entries.isEmpty())
        assertTrue(savedRows().isEmpty())
    }

    @After
    fun tearDown() {
        camera.releaseGates()
        try {
            if (::model.isInitialized) retireModel()
            if (::history.isInitialized) awaitHistory()
        } finally {
            try {
                runBlocking {
                    historyScope.cancel()
                    withTimeout(IMPORT_TIMEOUT_MILLIS) { historyScope.coroutineContext[Job]?.join() }
                }
            } finally {
                try {
                    if (serverStarted) camera.server.shutdown()
                } finally {
                    try {
                        // Bypass a deliberately failing wrapper only for these exact test-owned
                        // rows. The random RELATIVE_PATH restriction is retained after failures.
                        check(modelWorkRetired) { "Do not delete outputs while a test-owned writer is still active" }
                        savedRows().forEach { assertEquals(1, resolver.delete(it.uri, null, null)) }
                    } finally {
                        providers.forEach(ContentProvider::shutdown)
                        if (modelWorkRetired && ownsCleanupWarning && ::model.isInitialized) {
                            // The owning jobs have ended and the owned rows were handled above.
                            // Acknowledgement itself must never delete or restart anything.
                            compose.runOnIdle { model.acknowledgeForegroundImportCleanupWarning(compose.activity) }
                        }
                        if (::historyDirectory.isInitialized) historyDirectory.deleteRecursively()
                    }
                }
            }
        }
    }

    @Test
    fun explicitUiEnableBaselinesOldFilesAndImportsOnlyTheNewJpegOriginalOnce() {
        inventory.set(baseline + offJpeg)
        camera.enqueueContentsEvent()
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) {
            val state = model.uiState.value
            state.mediaItems.any { it.id == offJpeg } && !state.mediaLibraryLoading && !state.busy
        }
        compose.onNodeWithTag("foreground-import-entry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("foreground-import-destination").performScrollTo()
            .assertTextContains(cameraGalleryPath(camera.model), substring = true)
        compose.onNodeWithTag("foreground-import-disclosure").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-dismiss").performScrollTo().performClick()
        compose.onNodeWithTag("foreground-import-dialog").assertDoesNotExist()
        assertEquals(ForegroundImportPhase.OFF, status.phase)
        assertTrue("Opening/dismissing disclosure and observing content are not opt-in", camera.originalReads.isEmpty())
        assertTrue(savedRows().isEmpty())
        assertTrue(history.state.value.entries.isEmpty())

        enableThroughUi(expectedBaseline = 3)
        assertTrue("The complete enable-time inventory must never be backfilled", camera.originalReads.isEmpty())
        assertTrue(savedRows().isEmpty())
        // Exact repeated identities appear in each snapshot. RAW+JPEG siblings share capture
        // time, but only the absent-before-enable JPEG identity is admitted for import.
        inventory.set(baseline + offJpeg + listOf(newRaw, newJpeg, newJpeg, newRaw))
        awaitCompleted(newJpeg, count = 1)
        assertPublishedOriginal(newJpeg)
        assertReceipt(newJpeg, DownloadHistoryOutcome.COMPLETED)
        assertEquals(5, status.knownCount)
        assertEquals(3, status.baselineCount)
        val freshReads = metadataReads.filter { it.id == newJpeg }
        assertTrue("A new JPEG needs two actual fresh-info responses", freshReads.size >= 2)
        assertTrue("The positive size readbacks must be at least one second apart",
            TimeUnit.NANOSECONDS.toMillis(freshReads[1].atNanos - freshReads[0].atNanos) >= 1_000L)
        assertTrue("RAW siblings must never enter original transfer", camera.originalReads.none { it.endsWith(newRaw) })

        val readsAfterSave = camera.mediaReads.get()
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) {
            camera.mediaReads.get() >= readsAfterSave + 2 && status.phase == ForegroundImportPhase.WATCHING &&
                !model.uiState.value.busy
        }
        assertEquals(listOf(originalPath(newJpeg)), camera.originalReads.toList())
        assertEquals(1, savedRows().size)
        assertEquals(1, status.completedCount)
        assertEquals(1, history.state.value.entries.size)
        // Fetch the production display representation independently. A preview/thumbnail-sized
        // file cannot satisfy either the byte-for-byte Gallery assertion or the receipt above.
        compose.runOnIdle { model.openMediaPreview(mediaItem(newJpeg)) }
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) {
            model.uiState.value.mediaPreviewItem?.id == newJpeg &&
                !model.uiState.value.mediaPreviewLoading && model.uiState.value.mediaPreviewBytes != null
        }
        assertArrayEquals(camera.imageBytes, model.uiState.value.mediaPreviewBytes)
        assertFalse(originalBytes.contentEquals(requireNotNull(model.uiState.value.mediaPreviewBytes)))
        assertTrue(camera.previewReads.contains(originalPath(newJpeg)))
        assertEquals(listOf(originalPath(newJpeg)), camera.originalReads.toList())

        // A fresh ViewModel must not restore an earlier successful opt-in or automatically
        // backfill a JPEG that appears while the app owner is absent.
        retireModel()
        inventory.set(inventory.get().distinct() + nextJpeg)
        installReplacementModel()
        connectAndOpenGallery()
        assertEquals(ForegroundImportPhase.OFF, status.phase)
        assertEquals(0, status.completedCount)
        awaitQuietEventPollWindow()
        assertEquals(ForegroundImportPhase.OFF, status.phase)
        assertEquals(listOf(originalPath(newJpeg)), camera.originalReads.toList())
        assertPublishedOriginal(newJpeg)
    }

    @Test
    fun stopDuringThrottledOriginalRemovesThePendingRowWithoutFalseSuccess() {
        enableThroughUi(expectedBaseline = baseline.size)
        originalMode.set(OriginalMode.SLOW)
        inventory.set(baseline + newJpeg)
        awaitPartialOriginal(newJpeg)
        compose.onNodeWithTag("foreground-import-stop").assertIsDisplayed().assertIsEnabled().performClick()
        awaitStopped(ForegroundImportStopReason.USER)
        assertNoPublishedSuccess(newJpeg)
        assertEquals(MediaSaveFeedback.Cancelled, model.uiState.value.mediaSaveFeedback[newJpeg])
        assertReceipt(newJpeg, DownloadHistoryOutcome.CANCELLED)
        awaitQuietEventPollWindow()
        assertEquals(listOf(originalPath(newJpeg)), camera.originalReads.toList())
        assertTrue(savedRows().isEmpty())
    }

    @Test
    fun backgroundCancelsOriginalAndForegroundWaitsForAnExplicitFreshBaseline() {
        enableThroughUi(expectedBaseline = baseline.size)
        originalMode.set(OriginalMode.SLOW)
        inventory.set(baseline + newJpeg)
        awaitPartialOriginal(newJpeg)
        // Exercise OpenEosControlApp's real ON_STOP / ON_START binding, not a copied lifecycle callback.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) { status.phase == ForegroundImportPhase.STOPPED }
        assertEquals(ForegroundImportStopReason.BACKGROUND, status.stopReason)
        assertNoPublishedSuccess(newJpeg)
        assertReceipt(newJpeg, DownloadHistoryOutcome.CANCELLED)
        inventory.set(baseline + listOf(newJpeg, offJpeg))
        originalMode.set(OriginalMode.COMPLETE)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.awaitForegroundActivityWindow(compose.activity)
        awaitQuietEventPollWindow()
        assertEquals(ForegroundImportPhase.STOPPED, status.phase)
        assertEquals(ForegroundImportStopReason.BACKGROUND, status.stopReason)
        assertEquals(listOf(originalPath(newJpeg)), camera.originalReads.toList())
        assertTrue(savedRows().isEmpty())

        enableThroughUi(expectedBaseline = 4)
        assertEquals(0, status.completedCount)
        inventory.set(inventory.get() + nextJpeg)
        awaitCompleted(nextJpeg, count = 1)
        assertPublishedOriginal(nextJpeg)
        assertEquals(listOf(originalPath(newJpeg), originalPath(nextJpeg)), camera.originalReads.toList())
        assertReceipt(nextJpeg, DownloadHistoryOutcome.COMPLETED)
        assertEquals(DownloadHistoryOutcome.CANCELLED,
            history.state.value.entries.single { it.filename == name(newJpeg) }.outcome)
        assertFalse("Fresh enable must not retry the cancelled or while-background JPEG",
            history.state.value.entries.any { it.filename == name(offJpeg) })
    }

    @Test
    fun wrongLengthOriginalStopsAfterOneGetWithoutPublicationOrAutomaticRetry() =
        assertTerminalOriginalFailure(OriginalMode.WRONG_LENGTH)

    @Test
    fun failedOriginalStopsAfterOneGetWithoutPublicationOrAutomaticRetry() =
        assertTerminalOriginalFailure(OriginalMode.HTTP_FAILURE)

    @Test
    fun publicationWinningTheStopRaceKeepsTheRealOriginalAndCompletedReceipt() {
        val publication = camera.gate()
        val provider = provider(publication = publication)
        enableWithResolver(provider)
        inventory.set(baseline + newJpeg)
        assertTrue("Real MediaStore publication must reach the return-race gate",
            publication.entered.await(IMPORT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertPublishedOriginal(newJpeg)
        assertEquals(ForegroundImportPhase.SAVING, status.phase)
        // The update has already reached Android, but its return is held before finalization's
        // receipt callback. Cancellation must neither delete this published row nor lose success.
        compose.runOnIdle { model.stopForegroundJpegImport() }
        publication.release()
        awaitStopped(ForegroundImportStopReason.USER)
        assertEquals(1, status.completedCount)
        assertEquals(MediaSaveFeedback.Saved(cameraGalleryPath(camera.model)),
            model.uiState.value.mediaSaveFeedback[newJpeg])
        assertReceipt(newJpeg, DownloadHistoryOutcome.COMPLETED)
        assertPublishedOriginal(newJpeg)
        assertEquals(1, provider.insertCalls.get())
        assertEquals(1, provider.publishCalls.get())
        assertEquals(0, provider.deleteCalls.get())
        assertEquals(listOf(originalPath(newJpeg)), camera.originalReads.toList())
    }

    @Test
    fun failedOwnedDeletionPersistsWarningAndAcknowledgementNeitherDeletesNorEnables() {
        val provider = provider(rejectDelete = true)
        ownsCleanupWarning = true
        enableWithResolver(provider)
        originalMode.set(OriginalMode.WRONG_LENGTH)
        inventory.set(baseline + newJpeg)
        awaitStopped(ForegroundImportStopReason.CLEANUP_UNCONFIRMED)
        assertTrue(model.uiState.value.foregroundImportCleanupUnconfirmed)
        assertEquals(0, status.completedCount)
        assertNull(model.uiState.value.lastDownloadLocation)
        assertFalse(model.uiState.value.mediaSaveFeedback.values.any { it is MediaSaveFeedback.Saved })
        assertReceipt(newJpeg, DownloadHistoryOutcome.FAILED)
        val stranded = savedRows().single()
        assertEquals(1, stranded.pending)
        assertArrayEquals(camera.imageBytes, readBytes(stranded.uri))
        assertEquals(1, provider.deleteCalls.get())
        assertEquals(0, provider.publishCalls.get())
        assertTrue(compose.activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(CLEANUP_WARNING, false))

        retireModel()
        installReplacementModel()
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) { model.uiState.value.foregroundImportCleanupUnconfirmed }
        assertEquals(ForegroundImportPhase.OFF, status.phase)
        // A new disconnected app owner must show the persisted global warning immediately.
        compose.onNodeWithTag("foreground-import-global-warning").assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-entry").performClick()
        compose.onNodeWithTag("foreground-import-cleanup-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("foreground-import-dismiss").performScrollTo().performClick()
        connectAndOpenGallery()
        val listingCount = camera.mediaReads.get()
        // Also test the public production guard, so a disabled button alone cannot pass.
        compose.runOnIdle { model.enableForegroundJpegImport(compose.activity) }
        assertEquals(ForegroundImportPhase.OFF, status.phase)
        assertTrue(model.uiState.value.foregroundImportCleanupUnconfirmed)
        compose.onNodeWithTag("foreground-import-entry").performClick()
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("foreground-import-cleanup-acknowledge").performScrollTo()
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("foreground-import-cleanup-warning").assertDoesNotExist()
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("foreground-import-dismiss").performScrollTo().performClick()
        assertEquals(ForegroundImportPhase.OFF, status.phase)
        assertFalse(model.uiState.value.foregroundImportCleanupUnconfirmed)
        assertFalse(compose.activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(CLEANUP_WARNING, false))
        awaitQuietEventPollWindow()
        assertEquals(listingCount, camera.mediaReads.get())
        assertEquals(listOf(originalPath(newJpeg)), camera.originalReads.toList())
        assertEquals("Acknowledgement is not a delete or a cleanup confirmation", stranded, savedRows().single())
        assertEquals(1, provider.deleteCalls.get())
        assertArrayEquals(camera.imageBytes, readBytes(stranded.uri))
        assertEquals(ForegroundImportPhase.OFF, status.phase)
    }

    private fun assertTerminalOriginalFailure(mode: OriginalMode) {
        enableThroughUi(expectedBaseline = baseline.size)
        originalMode.set(mode)
        inventory.set(baseline + newJpeg)
        awaitStopped(ForegroundImportStopReason.TRANSFER_FAILED)
        assertNoPublishedSuccess(newJpeg)
        assertTrue(model.uiState.value.mediaSaveFeedback[newJpeg] is MediaSaveFeedback.Failed)
        assertReceipt(newJpeg, DownloadHistoryOutcome.FAILED)
        compose.onNodeWithTag("foreground-import-entry").performClick()
        compose.onNodeWithTag("foreground-import-stop-reason").performScrollTo()
            .assertTextContains(compose.activity.getString(R.string.foreground_import_reason_transfer_failed))
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("foreground-import-dismiss").performScrollTo().performClick()
        val reads = camera.mediaReads.get()
        originalMode.set(OriginalMode.COMPLETE)
        awaitQuietEventPollWindow()
        assertEquals("A terminal import must not silently scan/retry after the source recovers", reads, camera.mediaReads.get())
        assertEquals(listOf(originalPath(newJpeg)), camera.originalReads.toList())
        assertNoPublishedSuccess(newJpeg)
    }

    private fun connectAndOpenGallery() {
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) { !model.downloadHistoryState.value.loading && !model.uiState.value.busy }
        compose.runOnIdle {
            model.setLiveViewAutoRefresh(false)
            model.useDevSimulatorPreset()
            model.setBaseUrl(camera.baseUrl)
            model.connect()
        }
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) {
            val state = model.uiState.value
            state.connected && state.info?.model == camera.model && !state.busy &&
                state.captureReviewItem != null && !state.captureReviewLoading
        }
        compose.runOnIdle { model.setUiMode(UiMode.MEDIA) }
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) {
            !model.uiState.value.mediaLibraryLoading && !model.uiState.value.busy &&
                model.uiState.value.mediaItems.isNotEmpty()
        }
        compose.awaitForegroundActivityWindow(compose.activity)
        assertFalse(model.uiState.value.previewMode)
    }

    private fun enableThroughUi(expectedBaseline: Int) {
        compose.awaitForegroundActivityWindow(compose.activity)
        compose.onNodeWithTag("foreground-import-entry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("foreground-import-dialog").assertDoesNotExist()
        awaitWatching(expectedBaseline)
        compose.onNodeWithTag("foreground-import-stop").assertIsDisplayed().assertIsEnabled()
    }

    private fun enableWithResolver(provider: OwnedGalleryProvider) {
        val wrapped = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(compose.activity.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = wrapped
        }
        compose.runOnIdle { model.enableForegroundJpegImport(context) }
        awaitWatching(baseline.size)
    }

    private fun awaitWatching(expectedBaseline: Int) {
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) {
            status.phase == ForegroundImportPhase.WATCHING && !model.uiState.value.busy
        }
        assertEquals(expectedBaseline, status.baselineCount)
        assertEquals(expectedBaseline, status.knownCount)
        assertEquals(0, status.completedCount)
        assertEquals(0, status.pendingCount)
    }

    private fun awaitPartialOriginal(id: String) {
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) {
            val feedback = model.uiState.value.mediaSaveFeedback[id]
            status.phase == ForegroundImportPhase.SAVING && feedback is MediaSaveFeedback.Saving &&
                feedback.progress.bytesTransferred > 0 && feedback.progress.bytesTransferred < originalBytes.size
        }
        val row = savedRows().single()
        assertEquals(name(id), row.name)
        assertEquals(1, row.pending)
        val partial = readBytes(row.uri)
        assertTrue("The cancellation must interrupt actual original bytes in Android's pending row", partial.isNotEmpty())
        assertTrue(partial.size < originalBytes.size)
        assertEquals(listOf(originalPath(id)), camera.originalReads.toList())
        assertEquals(0, status.completedCount)
        assertNull(model.uiState.value.lastDownloadLocation)
    }

    private fun awaitCompleted(id: String, count: Int) {
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) {
            status.completedCount == count && status.phase == ForegroundImportPhase.WATCHING &&
                !model.uiState.value.busy && model.uiState.value.mediaSaveFeedback[id] ==
                MediaSaveFeedback.Saved(cameraGalleryPath(camera.model))
        }
        assertEquals(cameraGalleryPath(camera.model), model.uiState.value.lastDownloadLocation)
        assertNull(model.uiState.value.activeMediaDownloadName)
        assertNull(model.uiState.value.mediaDownloadProgress)
    }

    private fun awaitStopped(reason: ForegroundImportStopReason) {
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) {
            status.phase == ForegroundImportPhase.STOPPED && !model.uiState.value.busy
        }
        assertEquals(reason, status.stopReason)
        assertEquals(0, status.pendingCount)
        assertNull(status.activeName)
        assertNull(model.uiState.value.activeMediaDownloadName)
        assertNull(model.uiState.value.mediaDownloadProgress)
    }

    private fun assertNoPublishedSuccess(id: String) {
        assertEquals(0, status.completedCount)
        assertTrue("No public or pending partial row may survive successful cleanup", savedRows().isEmpty())
        assertNull(model.uiState.value.lastDownloadLocation)
        assertFalse(model.uiState.value.mediaSaveFeedback[id] is MediaSaveFeedback.Saved)
        awaitHistory()
        assertFalse(history.state.value.entries.any { it.outcome == DownloadHistoryOutcome.COMPLETED })
    }

    private fun assertPublishedOriginal(id: String) {
        val row = savedRows().single()
        assertEquals(name(id), row.name)
        assertEquals("image/jpeg", row.mimeType)
        assertEquals(0, row.pending)
        assertArrayEquals(originalBytes, readBytes(row.uri))
    }

    private fun assertReceipt(id: String, outcome: DownloadHistoryOutcome) {
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) {
            history.state.value.entries.singleOrNull { it.filename == name(id) }?.outcome == outcome
        }
        awaitHistory()
        val receipt = history.state.value.entries.single { it.filename == name(id) }
        assertEquals(DownloadHistoryDestination.GALLERY, receipt.destination)
        assertEquals(outcome, receipt.outcome)
        assertNotNull(receipt.finishedAtMillis)
        assertNull(history.state.value.warning)
    }

    /** Observe a functioning peer for longer than the real five-second idle poll, without sleep. */
    private fun awaitQuietEventPollWindow() {
        val polls = camera.eventPolls.get()
        // Empty synthetic event responses block for 250 ms each. 24 actual polls span > 5 s,
        // with no contents event capable of legitimately admitting another media request.
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) { camera.eventPolls.get() >= polls + 24 }
    }

    private fun newModel() = CameraViewModel(downloadHistoryFactory = { history }).also {
        modelWorkRetired = false
        viewModels.put("foreground-import-journey", it)
    }

    private fun retireModel() {
        val job = requireNotNull(model.viewModelScope.coroutineContext[Job])
        val ownedJobs = compose.runOnIdle { job.children.toList() }
        compose.runOnIdle {
            displayedModel.value = null
            model.closeMediaPreview()
            viewModels.clear()
        }
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) { job.isCompleted && ownedJobs.all(Job::isCompleted) }
        modelWorkRetired = true
    }

    private fun installReplacementModel() {
        compose.runOnIdle {
            model = newModel()
            displayedModel.value = model
        }
        compose.waitUntil(IMPORT_TIMEOUT_MILLIS) { !model.downloadHistoryState.value.loading }
    }

    private fun awaitHistory() = runBlocking {
        withTimeout(IMPORT_TIMEOUT_MILLIS) { history.awaitIdle() }
    }

    private fun provider(rejectDelete: Boolean = false, publication: CameraSessionTestGate? = null): OwnedGalleryProvider =
        OwnedGalleryProvider(resolver, cameraGalleryPath(camera.model), name(newJpeg), rejectDelete, publication).also {
            it.attachInfo(compose.activity.applicationContext, ProviderInfo().apply { authority = "media"; exported = false })
            providers += it
        }

    private fun savedRows(): List<SavedRow> = requireNotNull(resolver.query(
        collection.buildUpon().appendQueryParameter("includePending", "1").build(),
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE),
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(cameraGalleryPath(camera.model)), null,
    )).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(SavedRow(ContentUris.withAppendedId(collection, cursor.getLong(0)),
                cursor.getString(1), cursor.getInt(2), cursor.getString(3)))
        }
    }

    private fun readBytes(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
    private fun originalPath(id: String) = "/ccapi/media/$id"
    private fun isRaw(id: String) = id == baselineRaw || id == newRaw
    private fun name(id: String): String = "SYNTHETIC_${id.removePrefix("${camera.label}-")}_${camera.label.takeLast(12)}.${if (isRaw(id)) "CR3" else "JPG"}"
    private fun mediaItem(id: String) = CameraMediaItem(id, name(id), if (isRaw(id)) "raw" else "image",
        sizeBytes = originalBytes.size.toLong(), previewAvailable = !isRaw(id),
        contentType = if (isRaw(id)) "application/octet-stream" else "image/jpeg")

    private fun mediaJson(id: String): JSONObject {
        check(id in listOf(baselineJpeg, baselineRaw, offJpeg, newJpeg, newRaw, nextJpeg))
        val item = mediaItem(id)
        return JSONObject().put("id", item.id).put("name", item.name).put("kind", item.kind)
            .put("size_bytes", item.sizeBytes).put("content_type", item.contentType)
            // All synthetic items deliberately share a timestamp. New identity, not date or
            // a shutter acknowledgement, is the policy's criterion for foreground import.
            .put("capture_time", "2000-01-01T00:00:01Z")
    }

    private fun originalResponse(bytes: ByteArray) = MockResponse().setHeader("Content-Type", "image/jpeg")
        .setBody(Buffer().write(bytes))

    private data class SavedRow(val uri: Uri, val name: String, val pending: Int, val mimeType: String)
    private data class MetadataRead(val id: String, val atNanos: Long)
    private enum class OriginalMode { COMPLETE, SLOW, WRONG_LENGTH, HTTP_FAILURE }

    companion object {
        // Two complete baseline reads, 5 s discovery, 1 s fresh metadata spacing and real
        // Android I/O run at production speed. This bounds conditions without shortening policy.
        private const val IMPORT_TIMEOUT_MILLIS = 30_000L
        private const val PREFERENCES = "camera_connection"
        private const val CLEANUP_WARNING = "foreground_import_cleanup_unconfirmed"
    }
}

/** Real MediaStore forwarding, not a file-backed approximation or a registered provider. */
private class OwnedGalleryProvider(
    private val real: ContentResolver,
    private val expectedPath: String,
    private val expectedName: String,
    private val rejectDelete: Boolean,
    private val publication: CameraSessionTestGate?,
) : ContentProvider() {
    val insertCalls = AtomicInteger()
    val publishCalls = AtomicInteger()
    val deleteCalls = AtomicInteger()
    private val owned = AtomicReference<Uri?>()

    override fun onCreate() = true
    override fun getType(uri: Uri): String? { requireOwned(uri); return real.getType(uri) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        requireOwned(uri)
        return real.query(uri, projection, selection, selectionArgs, sortOrder)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        check(owned.get() == null) { "This fixture owns exactly one insertion" }
        check(uri == MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        check(values?.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) == expectedPath)
        check(values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) == expectedName)
        check(values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1)
        insertCalls.incrementAndGet()
        return requireNotNull(real.insert(uri, values)).also(owned::set)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireOwned(uri)
        return requireNotNull(real.openFileDescriptor(uri, mode))
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        requireOwned(uri)
        check(values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0)
        publishCalls.incrementAndGet()
        val result = real.update(uri, values, selection, selectionArgs)
        check(result == 1) { "The real owned row must publish before the cancellation race" }
        publication?.blockResponse()
        return result
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        requireOwned(uri)
        deleteCalls.incrementAndGet()
        return if (rejectDelete) 0 else real.delete(uri, selection, selectionArgs)
    }

    private fun requireOwned(uri: Uri) { check(uri == owned.get()) { "Refusing to address any unrelated MediaStore row" } }
}
