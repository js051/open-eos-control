package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.FocusDriveDirection
import dev.openeos.control.data.FocusDriveStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Production ViewModel/repository recovery after an ambiguous synthetic CCAPI Live View start. */
class CameraLiveViewRecoverySessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val repository = CameraRepository()
    private val store = ViewModelStore()
    private lateinit var viewModel: CameraViewModel
    private val failStart = AtomicBoolean(true)
    private val failStop = AtomicBoolean(true)
    private val failFrames = AtomicBoolean(false)
    private val undecodableFrames = AtomicBoolean(false)
    private val writes = CopyOnWriteArrayList<String>()
    private val blockNextStart = AtomicBoolean(false)
    private val startEntered = CountDownLatch(1)
    private val releaseStart = CountDownLatch(1)
    private val blockNextStop = AtomicBoolean(false)
    private val stopEntered = CountDownLatch(1)
    private val releaseStop = CountDownLatch(1)
    private val blockDrive = AtomicBoolean(false)
    private val failDrive = AtomicBoolean(false)
    private val driveEntered = CountDownLatch(1)
    private val releaseDrive = CountDownLatch(1)
    private var replacementCamera: CameraSessionTestSimulator? = null

    @Before fun setUp() {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
        } finally { bitmap.recycle() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                if (request.method == "POST" && path.endsWith("/shooting/control/drivefocus")) {
                    driveEntered.countDown()
                    if (blockDrive.get()) check(releaseDrive.await(15, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(if (failDrive.get()) 503 else 204)
                }
                if (request.method == "POST" && path.endsWith("/shooting/liveview")) {
                    val stop = JSONObject(request.body.readUtf8()).getString("liveviewsize") == "off"
                    writes += if (stop) "stop" else "start"
                    if (!stop && blockNextStart.compareAndSet(true, false)) {
                        startEntered.countDown()
                        check(releaseStart.await(15, TimeUnit.SECONDS))
                    }
                    if (stop && blockNextStop.compareAndSet(true, false)) {
                        stopEntered.countDown()
                        check(releaseStop.await(15, TimeUnit.SECONDS))
                    }
                    return if ((if (stop) failStop else failStart).get()) MockResponse().setResponseCode(503)
                    else json("{}")
                }
                if (request.method != "GET") return MockResponse().setResponseCode(405)
                return when {
                    path == "/ccapi" -> json(DISCOVERY)
                    path.endsWith("/deviceinformation") -> json("""{"productname":"Canon EOS Test Camera","serialnumber":"TEST-LIVE-RECOVERY-0001"}""")
                    path.endsWith("/devicestatus/battery") -> json("""{"level":"90"}""")
                    path.endsWith("/shooting/settings") -> json("{}")
                    path.endsWith("/shooting/liveview/flip") ->
                        MockResponse().setHeader("Content-Type", "image/jpeg")
                            .setBody(Buffer().write(when {
                                failFrames.get() -> byteArrayOf(1, 2, 3)
                                undecodableFrames.get() -> byteArrayOf(-1, -40, -1, -39) // SOI/EOI only.
                                else -> jpeg
                            }))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val baseUrl = server.url("/").toString()
        compose.runOnIdle {
            viewModel = CameraViewModel(repository)
            store.put("live-view-recovery", viewModel)
            viewModel.useDirectCameraPreset()
            viewModel.setBaseUrl(baseUrl)
            viewModel.connect()
        }
        compose.waitUntil(TIMEOUT) { viewModel.uiState.value.connected && !viewModel.uiState.value.busy }
    }

    @After fun tearDown() {
        releaseStart.countDown()
        releaseDrive.countDown()
        releaseStop.countDown()
        failStart.set(false)
        failStop.set(false)
        try {
            if (::viewModel.isInitialized) {
                val job = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
                compose.runOnIdle { store.clear() }
                compose.waitUntil(TIMEOUT) { job.isCompleted }
                runBlocking { withTimeout(TIMEOUT) { repository.disconnect() } }
            }
        } finally {
            try { server.shutdown() } finally { replacementCamera?.server?.shutdown() }
        }
    }

    @Test fun turningOffRetriesUnconfirmedStopEvenThoughLiveViewWasNeverConfirmedRunning() {
        assertEquals(listOf("start", "stop"), writes.toList())
        assertFalse(repository.isLiveViewRunning())
        assertTrue(repository.isLiveViewStopRequired())
        assertNull(viewModel.uiState.value.liveViewBitmap)
        assertTrue(viewModel.uiState.value.error.orEmpty().contains("CcapiLiveViewReleaseException"))

        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(false) }
        compose.waitUntil(TIMEOUT) { writes.size == 3 && !viewModel.uiState.value.busy }
        assertEquals(listOf("start", "stop", "stop"), writes.toList())
        assertFalse(repository.isLiveViewRunning())
        assertTrue(repository.isLiveViewStopRequired())
        failStop.set(false)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(false) }
        compose.waitUntil(TIMEOUT) { writes.size == 4 && !viewModel.uiState.value.busy }
        assertEquals(listOf("start", "stop", "stop", "stop"), writes.toList())
        assertFalse(repository.isLiveViewRunning())
        assertFalse(repository.isLiveViewStopRequired())
        assertNull(viewModel.uiState.value.error)

        failStart.set(false)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(true) }
        compose.waitUntil(TIMEOUT) {
            repository.isLiveViewRunning() && !viewModel.uiState.value.busy && viewModel.uiState.value.liveViewBitmap != null
        }
        assertEquals(listOf("start", "stop", "stop", "stop", "start"), writes.toList())
        assertTrue(repository.isLiveViewStopRequired())
        assertNull(viewModel.uiState.value.error)
    }

    @Test fun disconnectCancelsInFlightAndQueuedLiveViewRestartsBeforeConnectingAnotherCamera() {
        failStop.set(false)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(false) }
        compose.waitUntil(TIMEOUT) { !viewModel.uiState.value.busy && !repository.isLiveViewStopRequired() }
        failStart.set(false)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(true) }
        compose.waitUntil(TIMEOUT) { !viewModel.uiState.value.busy && viewModel.uiState.value.liveViewBitmap != null }
        val cameraB = CameraSessionTestSimulator("LIVE-B").also {
            replacementCamera = it
            it.start()
        }
        blockNextStart.set(true)
        failStart.set(true)
        val restartJobs = compose.runOnIdle {
            val parent = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
            val before = parent.children.toSet()
            viewModel.restartLiveView()
            viewModel.restartLiveView()
            parent.children.filter { it !in before }.toList()
        }
        assertEquals(2, restartJobs.size)
        assertTrue(startEntered.await(15, TimeUnit.SECONDS))
        compose.runOnIdle {
            viewModel.disconnect()
            assertTrue(restartJobs.all { it.isCancelled })
            viewModel.useDevSimulatorPreset()
            viewModel.setBaseUrl(cameraB.baseUrl)
            viewModel.setLiveViewAutoRefresh(false)
            viewModel.connect()
        }
        releaseStart.countDown()
        compose.waitUntil(TIMEOUT) {
            val state = viewModel.uiState.value
            restartJobs.all { it.isCompleted } && state.info?.model == cameraB.model && state.connected && !state.busy
        }
        compose.runOnIdle {
            assertTrue(cameraB.mutations.isEmpty())
            assertNull(viewModel.uiState.value.error)
            assertNull(viewModel.uiState.value.errorOperation)
            assertFalse(repository.isLiveViewRunning())
            assertFalse(repository.isLiveViewStopRequired())
        }
        // Only the initial startup, the successful recovery, and the already in-flight A restart ran.
        assertEquals(3, writes.count { it == "start" })
    }

    @Test fun terminalFrameFailureClearsPresentationStopsOnceAndRequiresDeliberateStart() {
        recoverHealthyPreview()
        val starts = writes.count { it == "start" }
        val stops = writes.count { it == "stop" }
        failFrames.set(true)
        compose.waitUntil(TIMEOUT) { viewModel.uiState.value.liveViewStreamFailed && !viewModel.uiState.value.busy && !repository.isLiveViewStopRequired() }
        compose.runOnIdle {
            assertNull(viewModel.uiState.value.liveViewBitmap)
            assertNull(viewModel.uiState.value.liveViewFrameUrl)
            assertNull(viewModel.uiState.value.liveViewDiagnostics.lastFrameAtMillis)
            assertFalse(repository.isLiveViewRunning())
            assertFalse(viewModel.uiState.value.liveViewAutoRefresh)
            assertEquals(CameraOperation.LIVE_VIEW, viewModel.uiState.value.errorOperation)
        }
        assertEquals(starts, writes.count { it == "start" })
        assertEquals(stops + 1, writes.count { it == "stop" })
        failFrames.set(false)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(true) }
        compose.waitUntil(TIMEOUT) { viewModel.uiState.value.liveViewBitmap != null && !viewModel.uiState.value.busy }
        assertEquals(starts + 1, writes.count { it == "start" })
        assertFalse(viewModel.uiState.value.liveViewStreamFailed)
    }

    @Test fun failedStreamStopRemainsStopOnlyAcrossForegroundAndCannotResurrectOldFrame() {
        recoverHealthyPreview()
        val starts = writes.count { it == "start" }
        failStop.set(true); failFrames.set(true)
        compose.waitUntil(TIMEOUT) { viewModel.uiState.value.liveViewStreamFailed && !viewModel.uiState.value.busy }
        assertFalse(repository.isLiveViewRunning()); assertTrue(repository.isLiveViewStopRequired())
        compose.runOnIdle { viewModel.setAppForeground(false) }
        compose.waitUntil(TIMEOUT) { !viewModel.uiState.value.busy }
        compose.runOnIdle { viewModel.setAppForeground(true) }
        compose.waitUntil(TIMEOUT) { !viewModel.uiState.value.busy }
        assertEquals(starts, writes.count { it == "start" })
        assertNull(viewModel.uiState.value.liveViewBitmap)
        failStop.set(false)
        compose.runOnIdle { viewModel.restartLiveView() }
        compose.waitUntil(TIMEOUT) { !repository.isLiveViewStopRequired() && !viewModel.uiState.value.busy }
        assertEquals(starts, writes.count { it == "start" })
        assertFalse(viewModel.uiState.value.liveViewAutoRefresh)
    }

    @Test fun androidDecodeFailureRetainsTypedDetailsAndRequiresExplicitRestart() {
        recoverHealthyPreview()
        val starts = writes.count { it == "start" }
        val stops = writes.count { it == "stop" }
        // Pass the wire reader's JPEG marker framing, then fail the actual Android bitmap decoder.
        undecodableFrames.set(true)
        compose.waitUntil(TIMEOUT) {
            viewModel.uiState.value.liveViewStreamFailed && !viewModel.uiState.value.busy &&
                !repository.isLiveViewStopRequired()
        }
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertEquals(LiveViewDecodeFailure(4, "image/jpeg"), liveViewDecodeFailure(state.error.orEmpty()))
            assertEquals(CameraOperation.LIVE_VIEW, state.errorOperation)
            assertFalse(state.liveViewAutoRefresh)
            assertNull(state.liveViewBitmap)
            assertNull(state.liveViewDiagnostics.lastFrameAtMillis)
        }
        assertEquals(starts, writes.count { it == "start" })
        assertEquals(stops + 1, writes.count { it == "stop" })
        undecodableFrames.set(false)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(true) }
        compose.waitUntil(TIMEOUT) { viewModel.uiState.value.liveViewBitmap != null && !viewModel.uiState.value.busy }
        assertEquals(starts + 1, writes.count { it == "start" })
        assertFalse(viewModel.uiState.value.liveViewStreamFailed)
        assertNull(viewModel.uiState.value.error)
    }

    @Test fun backgroundClearsPixelsImmediatelyAndForegroundCreatesAFreshSourceAttempt() {
        recoverHealthyPreview()
        val starts = writes.count { it == "start" }
        val oldOwner = repository.liveViewOwner()
        compose.runOnIdle {
            viewModel.setAppForeground(false)
            assertNull(viewModel.uiState.value.liveViewBitmap)
            assertNull(viewModel.uiState.value.liveViewDiagnostics.lastFrameAtMillis)
            assertTrue(viewModel.uiState.value.liveViewStopPending)
            assertFalse(viewModel.uiState.value.liveViewStopUnconfirmed)
        }
        compose.waitUntil(TIMEOUT) { !repository.isLiveViewRunning() && !repository.isLiveViewStopRequired() && !viewModel.uiState.value.busy }
        assertFalse(viewModel.uiState.value.liveViewStopPending)
        compose.runOnIdle { viewModel.setAppForeground(true) }
        compose.waitUntil(TIMEOUT) { viewModel.uiState.value.liveViewBitmap != null && !viewModel.uiState.value.busy }
        assertEquals(starts + 1, writes.count { it == "start" })
        assertFalse(repository.retireLiveView(oldOwner))
        assertTrue(repository.isLiveViewRunning())
    }

    @Test fun lateAcceptedDriveCannotPublishAfterTerminalFrameFailure() {
        assertRetiredDrivePreservesPresentation(fails = false, background = false)
    }

    @Test fun lateFailedDriveCannotReplaceTerminalFrameFailure() {
        assertRetiredDrivePreservesPresentation(fails = true, background = false)
    }

    @Test fun backgroundRejectsLateAcceptedDriveUntilItsPhysicalCallSettles() {
        assertRetiredDrivePreservesPresentation(fails = false, background = true)
    }

    @Test fun backgroundRejectsLateFailedDriveUntilItsPhysicalCallSettles() {
        assertRetiredDrivePreservesPresentation(fails = true, background = true)
    }

    private fun assertRetiredDrivePreservesPresentation(fails: Boolean, background: Boolean) {
        recoverHealthyPreview()
        val starts = writes.count { it == "start" }
        val stops = writes.count { it == "stop" }
        blockDrive.set(true); failDrive.set(fails); blockNextStop.set(true)
        compose.runOnIdle { viewModel.driveFocus(FocusDriveDirection.NEAR, FocusDriveStep.SMALL) }
        assertTrue(driveEntered.await(TIMEOUT, TimeUnit.MILLISECONDS))
        if (background) compose.runOnIdle { viewModel.setAppForeground(false) }
        else failFrames.set(true)
        compose.waitUntil(TIMEOUT) {
            !repository.isLiveViewRunning() && (background || viewModel.uiState.value.liveViewStreamFailed)
        }
        val error = viewModel.uiState.value.error
        val errorOperation = viewModel.uiState.value.errorOperation
        assertEquals("FOCUS must defer physical Stop until the drive settles", stops, writes.count { it == "stop" })
        assertTrue(viewModel.uiState.value.isBusy(CameraOperation.FOCUS))
        assertTrue(repository.isLiveViewStopRequired())
        assertNull(viewModel.uiState.value.focusFeedback)
        val retiredFeedback = CopyOnWriteArrayList<FocusFeedback?>()
        // This also observes intermediate StateFlow emissions; host extraction
        // separately checks every state write without claiming Compose rendering.
        val collector = compose.runOnIdle {
            viewModel.viewModelScope.launch(Dispatchers.Unconfined) {
                viewModel.uiState.collect { retiredFeedback += it.focusFeedback }
            }
        }
        try {
            releaseDrive.countDown()
            assertTrue(stopEntered.await(TIMEOUT, TimeUnit.MILLISECONDS))
            compose.runOnIdle {
                val state = viewModel.uiState.value
                assertFalse(CameraOperation.FOCUS in state.pendingOperations)
                assertTrue(state.isBusy(CameraOperation.LIVE_VIEW))
                assertTrue(repository.isLiveViewStopRequired())
                assertEquals(error, state.error)
                assertEquals(errorOperation, state.errorOperation)
                assertNull(state.focusFeedback)
                assertNull(state.liveViewBitmap)
                assertNull(state.liveViewFrameUrl)
                assertTrue(retiredFeedback.all { it == null })
            }
            releaseStop.countDown()
            compose.waitUntil(TIMEOUT) { !viewModel.uiState.value.busy && !repository.isLiveViewStopRequired() }
            assertEquals(error, viewModel.uiState.value.error)
            assertEquals(starts, writes.count { it == "start" })
            assertEquals(stops + 1, writes.count { it == "stop" })
        } finally {
            collector.cancel()
            releaseDrive.countDown(); releaseStop.countDown()
        }
    }

    private fun recoverHealthyPreview() {
        failStop.set(false)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(false) }
        compose.waitUntil(TIMEOUT) { !repository.isLiveViewStopRequired() && !viewModel.uiState.value.busy }
        failStart.set(false)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(true) }
        compose.waitUntil(TIMEOUT) { viewModel.uiState.value.liveViewBitmap != null && !viewModel.uiState.value.busy }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val TIMEOUT = 15_000L
        const val DISCOVERY = """{"ver100":[
          {"path":"/deviceinformation","get":true},
          {"path":"/devicestatus/battery","get":true},
          {"path":"/shooting/settings","get":true},
          {"path":"/shooting/control/drivefocus","post":true},
          {"path":"/shooting/liveview","post":true},
          {"path":"/shooting/liveview/flip","get":true}
        ]}"""
    }
}
