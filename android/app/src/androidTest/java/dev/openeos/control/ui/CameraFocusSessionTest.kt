package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraFocusStatus
import dev.openeos.control.data.LiveViewSize
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
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CameraFocusSessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val repository = CameraRepository()
    private lateinit var viewModel: CameraViewModel
    private val viewWrites = CopyOnWriteArrayList<String>()
    private val afWrites = CopyOnWriteArrayList<String>()
    private val failNextStop = AtomicBoolean(false)
    private val blockNextStart = AtomicBoolean(false)
    private val startEntered = CountDownLatch(1)
    private val releaseStart = CountDownLatch(1)
    private val focusStatus = AtomicInteger(0x20)
    private val focusReads = AtomicInteger(0)
    private val failFocusRead = AtomicBoolean(false)
    private val delayFocusRead = AtomicBoolean(false)

    @Before
    fun setUp() {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GRAY)
        val jpeg = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
        bitmap.recycle()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (request.method == "POST" && path.endsWith("/shooting/liveview")) {
                    val size = JSONObject(request.body.readUtf8()).getString("liveviewsize")
                    viewWrites += size
                    if (size == "off" && failNextStop.compareAndSet(true, false)) {
                        return MockResponse().setResponseCode(503).setBody("camera busy")
                    }
                    if (size != "off" && blockNextStart.compareAndSet(true, false)) {
                        startEntered.countDown()
                        check(releaseStart.await(8, TimeUnit.SECONDS))
                    }
                    return json("{}")
                }
                if (request.method == "POST" && path.endsWith("/shooting/control/af")) {
                    afWrites += JSONObject(request.body.readUtf8()).getString("action")
                    return json("{}")
                }
                if (request.method != "GET") return MockResponse().setResponseCode(405)
                return when {
                    path == "/ccapi" -> json(DISCOVERY)
                    path.endsWith("/deviceinformation") -> json("""{"productname":"Canon EOS R6 Mark III","serialnumber":"TEST-SERIAL-0001"}""")
                    path.endsWith("/devicestatus/battery") -> json("""{"level":"90"}""")
                    path.endsWith("/shooting/settings") -> json("{}")
                    path.endsWith("/shooting/liveview/flipdetail") -> {
                        assertEquals("info", request.requestUrl!!.queryParameter("kind"))
                        focusReads.incrementAndGet()
                        if (failFocusRead.get()) return MockResponse().setResponseCode(503)
                        val payload = """{"liveviewdata":{"image":{"positionx":0,"positiony":0,"positionwidth":1200,"positionheight":900},
                            "zoom":{"magnification":1},"afframe":[{"select":1,"status":${focusStatus.get()},"x":300,"y":225,"width":300,"height":225}]}}""".toByteArray()
                        val packet = ByteBuffer.allocate(payload.size + 9).putShort(0xFF00.toShort()).put(1)
                            .putInt(payload.size).put(payload).putShort(0xFFFF.toShort()).array()
                        MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(packet))
                            .apply { if (delayFocusRead.get()) setBodyDelay(800, TimeUnit.MILLISECONDS) }
                    }
                    path.endsWith("/shooting/liveview/flip") -> MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val baseUrl = server.url("/").toString()
        compose.runOnIdle {
            viewModel = CameraViewModel(repository)
            viewModel.useDirectCameraPreset()
            viewModel.setBaseUrl(baseUrl)
            viewModel.connect()
        }
        awaitFrame()
    }

    @After
    fun tearDown() {
        releaseStart.countDown()
        if (::viewModel.isInitialized) compose.runOnIdle { viewModel.disconnect() }
        compose.waitUntil(8_000) { !repository.isLiveViewRunning() }
        server.shutdown()
    }

    @Test
    fun backgroundStopsRemoteViewAndForegroundResumesWithoutReconnecting() {
        compose.runOnIdle { viewModel.setAppForeground(false) }
        awaitStopped()
        assertTrue(viewModel.uiState.value.connected)
        assertTrue(viewModel.uiState.value.liveViewAutoRefresh)
        compose.runOnIdle { viewModel.setAppForeground(true) }
        awaitFrame()
        assertEquals(listOf("medium", "off", "medium"), viewWrites.toList())
        assertTrue(afWrites.isEmpty())
    }

    @Test
    fun explicitOffSurvivesRefreshResizeAndForegroundChanges() {
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(false) }
        awaitStopped()
        compose.runOnIdle {
            viewModel.setLiveViewSize(LiveViewSize.SMALL)
            viewModel.refresh()
            viewModel.restartLiveView()
            viewModel.setAppForeground(false)
            viewModel.setAppForeground(true)
        }
        compose.waitUntil(8_000) { !viewModel.uiState.value.busy }
        assertFalse(repository.isLiveViewRunning())
        assertEquals(listOf("medium", "off"), viewWrites.toList())
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(true) }
        awaitFrame()
        assertEquals("small", viewWrites.last())
    }

    @Test
    fun stopFailureIsVisibleAndRetryKeepsTheConnection() {
        failNextStop.set(true)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(false) }
        compose.waitUntil(8_000) { viewModel.uiState.value.error != null }
        assertTrue(repository.isLiveViewRunning())
        assertEquals(CameraOperation.LIVE_VIEW, viewModel.uiState.value.errorOperation)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(false) }
        awaitStopped()
        assertTrue(viewModel.uiState.value.connected)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun disablingWhileStartIsInFlightDoesNotLeaveAStreamOrStaleFrame() {
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(false) }
        awaitStopped()
        blockNextStart.set(true)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(true) }
        assertTrue(startEntered.await(8, TimeUnit.SECONDS))
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(false) }
        releaseStart.countDown()
        awaitStopped()
        assertEquals(listOf("medium", "off", "medium", "off"), viewWrites.toList())
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(true) }
        awaitFrame()
    }

    @Test
    fun acceptedAfCommandIsNotAnOpticalFocusConfirmation() {
        compose.runOnIdle { viewModel.autofocus() }
        compose.waitUntil(8_000) { viewModel.uiState.value.focusFeedback == FocusFeedback.ACCEPTED }
        assertEquals(listOf("start", "stop"), afWrites.toList())
        assertEquals(FocusFeedback.ACCEPTED, viewModel.uiState.value.focusFeedback)
    }

    @Test
    fun reportsCameraStatesWithoutSendingAnAfCommand() {
        for ((raw, expected) in listOf(0x21 to CameraFocusStatus.FOCUSED, 0x32 to CameraFocusStatus.UNFOCUSED, 0x34 to CameraFocusStatus.FOCUSING)) {
            focusStatus.set(raw)
            compose.waitUntil(8_000) { viewModel.uiState.value.cameraFocusInfo?.frames?.singleOrNull()?.status == expected }
        }
        assertTrue(afWrites.isEmpty())
        assertNull(viewModel.uiState.value.focusFeedback)
    }

    @Test
    fun metadataFailureClearsFramesWithoutFailingLiveViewAndRecovers() {
        compose.waitUntil(8_000) { viewModel.uiState.value.cameraFocusInfo != null }
        failFocusRead.set(true)
        compose.waitUntil(8_000) { viewModel.uiState.value.cameraFocusInfoError }
        assertNull(viewModel.uiState.value.cameraFocusInfo)
        assertNull(viewModel.uiState.value.error)
        assertTrue(repository.isLiveViewRunning())
        assertTrue(viewModel.uiState.value.liveViewBitmap != null)
        failFocusRead.set(false)
        compose.waitUntil(8_000) { viewModel.uiState.value.cameraFocusInfo != null && !viewModel.uiState.value.cameraFocusInfoError }
    }

    @Test
    fun stopRejectsAnInflightFocusResponseAndStopsFurtherPolling() {
        delayFocusRead.set(true)
        val previous = focusReads.get()
        compose.waitUntil(8_000) { focusReads.get() > previous }
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(false) }
        awaitStopped()
        val stoppedReads = focusReads.get()
        Thread.sleep(1_200)
        assertNull(viewModel.uiState.value.cameraFocusInfo)
        assertNull(viewModel.uiState.value.cameraFocusInfoAtMillis)
        assertEquals(stoppedReads, focusReads.get())
        delayFocusRead.set(false)
        compose.runOnIdle { viewModel.setLiveViewAutoRefresh(true) }
        compose.waitUntil(8_000) { viewModel.uiState.value.cameraFocusInfo != null }
    }

    @Test
    fun mediaAndBackgroundPauseMetadataThenResume() {
        compose.waitUntil(8_000) { viewModel.uiState.value.cameraFocusInfo != null }
        compose.runOnIdle { viewModel.setUiMode(UiMode.MEDIA) }
        Thread.sleep(400)
        val mediaReads = focusReads.get()
        Thread.sleep(600)
        assertEquals(mediaReads, focusReads.get())
        assertNull(viewModel.uiState.value.cameraFocusInfo)
        compose.runOnIdle { viewModel.setUiMode(UiMode.CONTROL) }
        compose.waitUntil(8_000) { viewModel.uiState.value.cameraFocusInfo != null }
        compose.runOnIdle { viewModel.setAppForeground(false) }
        awaitStopped()
        assertNull(viewModel.uiState.value.cameraFocusInfo)
        compose.runOnIdle { viewModel.setAppForeground(true) }
        compose.waitUntil(8_000) { viewModel.uiState.value.cameraFocusInfo != null }
    }

    private fun awaitFrame() = compose.waitUntil(8_000) {
        viewModel.uiState.value.liveViewBitmap != null && !viewModel.uiState.value.busy
    }

    private fun awaitStopped() = compose.waitUntil(8_000) {
        !repository.isLiveViewRunning() && viewModel.uiState.value.liveViewBitmap == null && !viewModel.uiState.value.busy
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val DISCOVERY = """{"ver100":[
            {"path":"/deviceinformation","get":true},
            {"path":"/devicestatus/battery","get":true},
            {"path":"/shooting/settings","get":true},
            {"path":"/shooting/liveview","post":true},
            {"path":"/shooting/liveview/flip","get":true},
            {"path":"/shooting/liveview/flipdetail","get":true},
            {"path":"/shooting/control/af","post":true}
        ]}"""
    }
}
