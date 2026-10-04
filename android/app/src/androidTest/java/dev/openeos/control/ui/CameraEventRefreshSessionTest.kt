package dev.openeos.control.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraBackendFactory
import dev.openeos.control.data.CameraHttpTransport
import dev.openeos.control.data.CameraHttpTransportFactory
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Single-event races through the production ViewModel and real HTTP repository. */
class CameraEventRefreshSessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val camera = CameraSessionTestSimulator("EVENT")
    // The cancellation assertion is much shorter than these HTTP timeouts, so a natural socket
    // timeout cannot masquerade as a successful cancellation of an unresponsive listing.
    private val http = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val repository = CameraRepository(CameraBackendFactory(
        httpTransportFactory = CameraHttpTransportFactory {
            CameraHttpTransport(client = http, diagnostics = CameraNetworkDiagnostics.Empty)
        },
    ))
    private val viewModelStore = ViewModelStore()
    private lateinit var viewModel: CameraViewModel
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        testDirectory = File(compose.activity.cacheDir, "camera-import/event-session-${UUID.randomUUID()}")
        check(testDirectory.mkdirs())
        camera.start()
        compose.runOnIdle {
            viewModel = CameraViewModel(repository)
            viewModelStore.put("event-refresh-session", viewModel)
            viewModel.useDevSimulatorPreset()
            viewModel.setBaseUrl(camera.baseUrl)
            viewModel.setLiveViewAutoRefresh(false)
            viewModel.connect()
        }
        // The connection has an independent recent-capture read. Arm gates only after it finishes.
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            state.connected && !state.busy && state.captureReviewItem != null && !state.captureReviewLoading
        }
        assertEquals(false, viewModel.uiState.value.status?.recording)
    }

    @After
    fun tearDown() {
        camera.releaseGates()
        try {
            if (::viewModel.isInitialized) {
                val scopeJob = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
                compose.runOnIdle { viewModelStore.clear() }
                compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { scopeJob.isCompleted }
            }
            // Also await the repository close, which onCleared performs outside viewModelScope.
            runBlocking {
                withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { repository.disconnect() }
            }
        } finally {
            // Also close a NO_RESPONSE socket if an assertion failed before the app cancelled it.
            http.dispatcher.cancelAll()
            try {
                camera.server.shutdown()
            } finally {
                http.connectionPool.evictAll()
                if (::testDirectory.isInitialized) testDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun explicitCancelAbortsAnUnresponsiveEventListingWithoutStoppingEventPolling() {
        val blocked = startUnresponsiveEventListing()
        compose.runOnIdle {
            viewModel.cancelMediaLibraryLoad()
            assertFalse(viewModel.uiState.value.mediaLibraryLoading)
            assertEquals(MediaLibraryLoadStatus.CANCELLED, viewModel.uiState.value.mediaLibraryLoadStatus)
        }
        awaitCancelledListingAndContinuedPolling(blocked)
    }

    @Test
    fun singleDocumentDownloadCancelsEventListingAndPreservesPollingWithoutRelisting() {
        val item = requireNotNull(viewModel.uiState.value.captureReviewItem)
        val output = File(testDirectory, item.name)
        check(output.createNewFile())
        val destination = FileProvider.getUriForFile(
            compose.activity, "${compose.activity.packageName}.camera_import", output,
        )
        val blocked = startUnresponsiveEventListing()
        compose.runOnIdle {
            viewModel.downloadMedia(compose.activity, item, destination)
            assertFalse(viewModel.uiState.value.mediaLibraryLoading)
            assertEquals(MediaLibraryLoadStatus.CANCELLED, viewModel.uiState.value.mediaLibraryLoadStatus)
        }
        awaitCancelledListingAndContinuedPolling(blocked)
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            !state.isBusy(CameraOperation.MEDIA) && state.lastDownloadedMediaName == item.name
        }
        val saved = requireNotNull(compose.activity.contentResolver.openInputStream(destination)).use { it.readBytes() }
        assertArrayEquals(camera.imageBytes, saved)
        assertEquals(listOf("/ccapi/media/${item.id}"), camera.originalReads.toList())
        assertEquals(blocked.mediaReads, camera.mediaReads.get())
        compose.runOnIdle {
            assertEquals(item.name, viewModel.uiState.value.lastDownloadedMediaName)
            assertEquals(MediaLibraryLoadStatus.CANCELLED, viewModel.uiState.value.mediaLibraryLoadStatus)
            assertNull(viewModel.uiState.value.error)
        }
    }

    @Test
    fun delayedContentsListingCannotRestorePreRecordingStatusOrTurnStopIntoAnotherStart() {
        val gate = camera.gate()
        val blockNextListing = AtomicBoolean(true)
        val statusReadsBeforeEvent = camera.statusReads.get()
        camera.intercept = { request ->
            if (request.method == "GET" && request.requestUrl?.encodedPath == "/ccapi/media" &&
                blockNextListing.compareAndSet(true, false)) {
                gate.blockResponse()
                camera.json(camera.mediaJson())
            } else null
        }
        camera.enqueueContentsEvent()
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(camera.statusReads.get() > statusReadsBeforeEvent)
        assertEquals(false, viewModel.uiState.value.status?.recording)
        val pollsWhileListingBlocked = camera.eventPolls.get()

        toggleAndAwaitRecording(true)
        assertEquals(listOf("/ccapi/record/start"), camera.recordingWrites.toList())
        gate.release()
        awaitNextPoll(pollsWhileListingBlocked)

        compose.runOnIdle {
            assertEquals(true, viewModel.uiState.value.status?.recording)
            assertNull(viewModel.uiState.value.error)
        }
        toggleAndAwaitRecording(false)
        assertEquals(listOf("/ccapi/record/start", "/ccapi/record/stop"), camera.recordingWrites.toList())
        assertEquals(1, camera.deliveredEvents.get())
    }

    @Test
    fun eventStatusSnapshotCrossingACompletedRecordingCommandIsDiscardedAndReread() {
        val gate = camera.gate()
        val blockNextStatus = AtomicBoolean(true)
        camera.intercept = { request ->
            if (request.method == "GET" && request.requestUrl?.encodedPath == "/ccapi/status" &&
                blockNextStatus.compareAndSet(true, false)) {
                // Snapshot before the command, rather than reading the mutable camera after release.
                val staleStatus = camera.statusJson()
                gate.blockResponse()
                camera.json(staleStatus)
            } else null
        }
        camera.enqueueContentsEvent()
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val pollsWhileStatusBlocked = camera.eventPolls.get()
        toggleAndAwaitRecording(true)
        val statusReadsAfterCommand = camera.statusReads.get()
        gate.release()
        awaitNextPoll(pollsWhileStatusBlocked)

        assertTrue("The stale event status must be reread without needing another event.",
            camera.statusReads.get() > statusReadsAfterCommand)
        compose.runOnIdle {
            assertEquals(true, viewModel.uiState.value.status?.recording)
            assertNull(viewModel.uiState.value.error)
        }
        toggleAndAwaitRecording(false)
        assertEquals(listOf("/ccapi/record/start", "/ccapi/record/stop"), camera.recordingWrites.toList())
        assertEquals(1, camera.deliveredEvents.get())
    }

    @Test
    fun manualRefreshSnapshotCrossingARecordingCommandIsRereadBeforePublication() {
        val gate = camera.gate()
        val blockNextStatus = AtomicBoolean(true)
        camera.intercept = { request ->
            if (request.method == "GET" && request.requestUrl?.encodedPath == "/ccapi/status" &&
                blockNextStatus.compareAndSet(true, false)) {
                val staleStatus = camera.statusJson()
                gate.blockResponse()
                camera.json(staleStatus)
            } else null
        }
        compose.runOnIdle { viewModel.refresh() }
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(viewModel.uiState.value.isBusy(CameraOperation.STATUS))
        toggleAndAwaitRecording(true)
        val statusReadsAfterRecording = camera.statusReads.get()
        gate.release()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            !viewModel.uiState.value.isBusy(CameraOperation.STATUS)
        }

        assertTrue("Manual refresh must reread its outdated status snapshot.",
            camera.statusReads.get() > statusReadsAfterRecording)
        compose.runOnIdle {
            assertEquals(true, viewModel.uiState.value.status?.recording)
            assertNull(viewModel.uiState.value.error)
        }
        toggleAndAwaitRecording(false)
        assertEquals(listOf("/ccapi/record/start", "/ccapi/record/stop"), camera.recordingWrites.toList())
        assertEquals(0, camera.deliveredEvents.get())
    }

    @Test
    fun delayedSettingStatusCannotOverwriteACompletedRecordingCommand() {
        val gate = camera.gate()
        val blockNextExposure = AtomicBoolean(true)
        camera.intercept = { request ->
            if (request.method == "PATCH" && request.requestUrl?.encodedPath == "/ccapi/exposure" &&
                blockNextExposure.compareAndSet(true, false)) {
                // The setting has taken effect, but its response still describes the earlier recording state.
                camera.iso.set(JSONObject(request.body.readUtf8()).getString("iso"))
                val staleStatus = camera.statusJson()
                gate.blockResponse()
                camera.json(staleStatus)
            } else null
        }
        compose.runOnIdle { viewModel.setIso("25600") }
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(viewModel.uiState.value.isBusy(CameraOperation.SETTING))
        toggleAndAwaitRecording(true)
        val statusReadsAfterRecording = camera.statusReads.get()
        gate.release()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            !viewModel.uiState.value.isBusy(CameraOperation.SETTING)
        }

        assertTrue("A command response crossing another operation must refresh its stale status.",
            camera.statusReads.get() > statusReadsAfterRecording)
        compose.runOnIdle {
            assertEquals(true, viewModel.uiState.value.status?.recording)
            assertEquals("25600", viewModel.uiState.value.status?.exposure?.iso)
            assertNull(viewModel.uiState.value.error)
        }
        toggleAndAwaitRecording(false)
        assertEquals(listOf("/ccapi/record/start", "/ccapi/record/stop"), camera.recordingWrites.toList())
        assertEquals(0, camera.deliveredEvents.get())
    }

    @Test
    fun singleBodyEventSurvivesOneFailedStatusReadWithoutAnotherNotification() {
        assertSingleBodyEventRecovers("/ccapi/status")
    }

    @Test
    fun singleBodyEventSurvivesOneFailedCapabilityReadWithoutAnotherNotification() {
        assertSingleBodyEventRecovers("/ccapi/capabilities")
    }

    private fun assertSingleBodyEventRecovers(failedPath: String) {
        val failNextRead = AtomicBoolean(true)
        camera.intercept = { request ->
            if (request.method == "GET" && request.requestUrl?.encodedPath == failedPath &&
                failNextRead.compareAndSet(true, false)) {
                MockResponse().setResponseCode(503).setBody("Synthetic transient event refresh failure")
            } else null
        }
        val mediaReads = camera.mediaReads.get()
        val polls = camera.eventPolls.get()
        // Only the camera-side state changes. No app command and no second event repairs it.
        camera.recording.set(true)
        camera.iso.set("25600")
        camera.enqueueContentsEvent()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            state.status?.recording == true && state.status?.exposure?.iso == "25600" &&
                camera.mediaReads.get() > mediaReads && camera.eventPolls.get() >= polls + 3
        }
        compose.runOnIdle {
            assertFalse(failNextRead.get())
            assertEquals(true, viewModel.uiState.value.status?.recording)
            assertNull(viewModel.uiState.value.error)
            assertEquals(MediaLibraryLoadStatus.COMPLETE, viewModel.uiState.value.mediaLibraryLoadStatus)
        }
        assertEquals(1, camera.deliveredEvents.get())
        assertEquals(mediaReads + 1, camera.mediaReads.get())
        assertTrue(camera.recordingWrites.isEmpty())
        assertTrue(camera.mutations.isEmpty())
    }

    private fun toggleAndAwaitRecording(expected: Boolean) {
        compose.runOnIdle { viewModel.toggleRecording() }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            !state.isBusy(CameraOperation.RECORDING) && state.status?.recording == expected
        }
    }

    private fun startUnresponsiveEventListing(): BlockedEventListing {
        val entered = CountDownLatch(1)
        val blockNextListing = AtomicBoolean(true)
        val mediaReadsBeforeEvent = camera.mediaReads.get()
        camera.intercept = { request ->
            if (request.method == "GET" && request.requestUrl?.encodedPath == "/ccapi/media" &&
                blockNextListing.compareAndSet(true, false)) {
                entered.countDown()
                // No server-side response gate is released: only cancelling the real OkHttp call
                // can make the client finish before its 60-second network timeout.
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            } else null
        }
        camera.enqueueContentsEvent()
        assertTrue(entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        compose.runOnIdle {
            assertTrue(viewModel.uiState.value.mediaLibraryLoading)
            assertEquals(MediaLibraryLoadStatus.LOADING, viewModel.uiState.value.mediaLibraryLoadStatus)
        }
        assertTrue(http.dispatcher.runningCalls().any { it.request().url.encodedPath == "/ccapi/media" })
        assertEquals(mediaReadsBeforeEvent + 1, camera.mediaReads.get())
        return BlockedEventListing(mediaReads = camera.mediaReads.get(), eventPolls = camera.eventPolls.get())
    }

    private fun awaitCancelledListingAndContinuedPolling(blocked: BlockedEventListing) {
        compose.waitUntil(3_000) {
            http.dispatcher.runningCalls().none { it.request().url.encodedPath == "/ccapi/media" } &&
                camera.eventPolls.get() > blocked.eventPolls
        }
        // Observe subsequent empty event responses too: cancellation must not restart the card read.
        compose.waitUntil(3_000) { camera.eventPolls.get() >= blocked.eventPolls + 3 }
        assertEquals(blocked.mediaReads, camera.mediaReads.get())
        assertEquals(1, camera.deliveredEvents.get())
        compose.runOnIdle {
            assertFalse(viewModel.uiState.value.mediaLibraryLoading)
            assertEquals(MediaLibraryLoadStatus.CANCELLED, viewModel.uiState.value.mediaLibraryLoadStatus)
            assertNull(viewModel.uiState.value.error)
        }
    }

    private fun awaitNextPoll(previousCount: Int) {
        // The next HTTP poll proves the one event's entire refresh pipeline has completed.
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { camera.eventPolls.get() > previousCount }
    }

    private data class BlockedEventListing(val mediaReads: Int, val eventPolls: Int)
}
