package dev.openeos.control.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Production ViewModel/repository with two isolated, synthetic HTTP camera sessions. */
class CameraSessionIsolationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val cameraA = CameraSessionTestSimulator("A")
    private val cameraB = CameraSessionTestSimulator("B", initialIso = "200")
    private val repository = CameraRepository()
    private val viewModelStore = ViewModelStore()
    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        cameraA.start()
        cameraB.start()
        compose.runOnIdle {
            viewModel = CameraViewModel(repository)
            viewModelStore.put("session-isolation", viewModel)
            viewModel.useDevSimulatorPreset()
            viewModel.setBaseUrl(cameraA.baseUrl)
            viewModel.setLiveViewAutoRefresh(false)
            viewModel.connect()
        }
        awaitConnected(cameraA)
    }

    @After
    fun tearDown() {
        cameraA.releaseGates()
        cameraB.releaseGates()
        try {
            if (::viewModel.isInitialized) {
                val scopeJob = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
                compose.runOnIdle { viewModelStore.clear() }
                compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { scopeJob.isCompleted }
            }
            // onCleared closes the repository in a detached NonCancellable job; join actual cleanup
            // from the instrumentation thread before closing either HTTP fixture.
            runBlocking {
                withTimeout(SESSION_TEST_TIMEOUT_MILLIS) { repository.disconnect() }
            }
        } finally {
            try {
                cameraA.server.shutdown()
            } finally {
                cameraB.server.shutdown()
            }
        }
    }

    @Test
    fun disconnectCancelsBatchBeforeItsSecondDeleteAndReconnectCannotMutateTheNewCamera() {
        loadMedia()
        val items = viewModel.uiState.value.mediaItems
        assertEquals(2, items.size)
        val gate = cameraA.gate()
        val blockFirstDelete = AtomicBoolean(true)
        cameraA.intercept = { request ->
            if (request.method == "DELETE" && blockFirstDelete.compareAndSet(true, false)) {
                gate.blockResponse()
                MockResponse().setResponseCode(204)
            } else null
        }
        val oldOperation = launchAndCaptureJob { viewModel.deleteMediaBatch(items) }
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        reconnectWhileOldResponseIsBlocked(oldOperation)
        gate.release()
        awaitReplacementSession(oldOperation)

        assertEquals(listOf("/ccapi/media/${items.first().id}"), cameraA.deletes.toList())
        assertTrue(cameraB.mutations.isEmpty())
        compose.runOnIdle {
            assertNull(viewModel.uiState.value.mediaBatchProgress)
            assertNull(viewModel.uiState.value.lastMediaBatchResult)
            assertNull(viewModel.uiState.value.lastDeletedMediaName)
            assertReplacementState()
        }
    }

    @Test
    fun delayedSettingSuccessCannotPublishTheOldStatusIntoAReplacementSession() {
        assertDelayedSettingCannotAffectReplacement(responseCode = 200)
    }

    @Test
    fun delayedSettingFailureCannotPublishAnOldErrorIntoAReplacementSession() {
        assertDelayedSettingCannotAffectReplacement(responseCode = 503)
    }

    @Test
    fun enteringOfflinePreviewCancelsAConnectingCameraAndDiscardsItsLateIdentity() {
        val gate = cameraB.gate()
        val blockNextInfo = AtomicBoolean(true)
        cameraB.intercept = { request ->
            if (request.method == "GET" && request.requestUrl?.encodedPath == "/ccapi/info" &&
                blockNextInfo.compareAndSet(true, false)) {
                gate.blockResponse()
            }
            null // After release, return the normal synthetic B identity through the real client.
        }
        compose.runOnIdle {
            viewModel.disconnect()
            viewModel.setBaseUrl(cameraB.baseUrl)
        }
        val connectingJob = launchAndCaptureJob { viewModel.connect() }
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val offlineState = compose.runOnIdle {
            assertTrue(viewModel.uiState.value.isBusy(CameraOperation.CONNECT))
            viewModel.enterOfflinePreview()
            assertTrue(connectingJob.isCancelled)
            // A cancellable GET may finish locally before the synthetic server releases its body.
            viewModel.uiState.value.also { state ->
                assertTrue(state.previewMode)
                assertEquals("offline-preview", state.info?.serial)
                assertEquals("offline-preview", state.info?.api)
                assertTrue(state.pendingOperations.isEmpty())
            }
        }
        gate.release()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { connectingJob.isCompleted }
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertTrue(state.previewMode)
            assertEquals(offlineState.info, state.info)
            assertEquals(offlineState.status, state.status)
            assertTrue(state.pendingOperations.isEmpty())
            assertNull(state.error)
            assertNull(state.errorOperation)
        }
        assertEquals(0, cameraB.statusReads.get())
        assertEquals(0, cameraB.eventPolls.get())
        assertTrue(cameraB.mutations.isEmpty())
    }

    @Test
    fun adjacentPreviewRequestWhileLoadingKeepsTheCurrentViewerAndOriginalResponse() {
        loadMedia()
        val items = viewModel.uiState.value.mediaItems
        assertEquals(2, items.size)
        val first = items.first()
        val gate = cameraA.gate()
        val blockFirstPreview = AtomicBoolean(true)
        cameraA.intercept = { request ->
            if (request.method == "GET" && request.requestUrl?.queryParameter("kind") == "display" &&
                blockFirstPreview.compareAndSet(true, false)) {
                gate.blockResponse()
                cameraA.imageResponse()
            } else null
        }
        compose.runOnIdle { viewModel.openMediaPreview(first) }
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        compose.runOnIdle {
            assertTrue(viewModel.uiState.value.isBusy(CameraOperation.MEDIA))
            viewModel.previewAdjacentMedia(items, +1)
            val state = viewModel.uiState.value
            assertEquals(first.id, state.mediaPreviewItem?.id)
            assertTrue(state.mediaPreviewLoading)
            assertNull(state.mediaPreviewBytes)
        }
        assertEquals(listOf("/ccapi/media/${first.id}"), cameraA.previewReads.toList())
        gate.release()
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            !state.isBusy(CameraOperation.MEDIA) && !state.mediaPreviewLoading
        }
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertEquals(first.id, state.mediaPreviewItem?.id)
            assertArrayEquals(cameraA.imageBytes, state.mediaPreviewBytes)
            assertNull(state.error)
        }
        assertEquals(listOf("/ccapi/media/${first.id}"), cameraA.previewReads.toList())
    }

    private fun assertDelayedSettingCannotAffectReplacement(responseCode: Int) {
        val gate = cameraA.gate()
        cameraA.intercept = { request ->
            if (request.method == "PATCH" && request.requestUrl?.encodedPath == "/ccapi/exposure") {
                gate.blockResponse()
                if (responseCode == 200) {
                    cameraA.json(cameraA.statusJson(recordingValue = true, isoValue = "25600"))
                } else MockResponse().setResponseCode(responseCode).setBody("synthetic old-session failure")
            } else null
        }
        val oldOperation = launchAndCaptureJob { viewModel.setIso("25600") }
        assertTrue(gate.entered.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        reconnectWhileOldResponseIsBlocked(oldOperation)
        gate.release()
        awaitReplacementSession(oldOperation)

        assertEquals(listOf("PATCH /ccapi/exposure"), cameraA.mutations.toList())
        assertTrue(cameraB.mutations.isEmpty())
        compose.runOnIdle { assertReplacementState() }
    }

    private fun launchAndCaptureJob(action: () -> Unit): Job = compose.runOnIdle {
        val parent = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
        val previousJobs = parent.children.toSet()
        action()
        parent.children.single { it !in previousJobs }
    }

    private fun reconnectWhileOldResponseIsBlocked(oldOperation: Job) {
        compose.runOnIdle {
            viewModel.disconnect()
            // Cancellation must be synchronous; no old batch step can choose B's repository backend.
            assertTrue(oldOperation.isCancelled)
            viewModel.setBaseUrl(cameraB.baseUrl)
            viewModel.connect()
        }
        compose.runOnIdle {
            assertFalse(oldOperation.isCompleted)
            assertEquals(0, cameraB.server.requestCount)
            assertFalse(viewModel.uiState.value.connected)
        }
    }

    private fun awaitReplacementSession(oldOperation: Job) {
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) { oldOperation.isCompleted }
        awaitConnected(cameraB)
    }

    private fun assertReplacementState() {
        val state = viewModel.uiState.value
        assertTrue(state.connected)
        assertEquals(cameraB.model, state.info?.model)
        assertEquals(cameraB.serial, state.info?.serial)
        assertEquals("200", state.status?.exposure?.iso)
        assertEquals(false, state.status?.recording)
        assertEquals(87, state.status?.batteryLevel)
        assertTrue(state.pendingOperations.isEmpty())
        assertNull(state.error)
        assertNull(state.errorOperation)
    }

    private fun awaitConnected(camera: CameraSessionTestSimulator) = compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
        val state = viewModel.uiState.value
        state.connected && state.info?.model == camera.model && !state.busy &&
            state.captureReviewItem?.id == camera.itemIds.first() && !state.captureReviewLoading
    }

    private fun loadMedia() {
        compose.runOnIdle { viewModel.setUiMode(UiMode.MEDIA) }
        compose.waitUntil(SESSION_TEST_TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            !state.mediaLibraryLoading && state.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE &&
                state.mediaItems.size == 2
        }
    }
}
