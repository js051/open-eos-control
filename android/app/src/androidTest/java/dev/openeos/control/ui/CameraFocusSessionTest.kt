package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.openeos.control.R
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
    private val cameraWrites = CopyOnWriteArrayList<String>()
    private val captureAf = CopyOnWriteArrayList<Boolean>()
    private val failNextCapture = AtomicBoolean(false)
    private val failAfStop = AtomicBoolean(false)
    private val blockAfStart = AtomicBoolean(false)
    private val afStartEntered = CountDownLatch(1)
    private val releaseAfStart = CountDownLatch(1)
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
                if (request.method == "POST" && path.endsWith("/shooting/control/shutterbutton")) {
                    captureAf += JSONObject(request.body.readUtf8()).getBoolean("af")
                    return MockResponse().setResponseCode(if (failNextCapture.getAndSet(false)) 503 else 204)
                }
                if (request.method == "POST" && path.endsWith("/shooting/liveview")) {
                    val size = JSONObject(request.body.readUtf8()).getString("liveviewsize")
                    viewWrites += size
                    cameraWrites += "view:$size"
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
                    val action = JSONObject(request.body.readUtf8()).getString("action")
                    afWrites += action
                    cameraWrites += "af:$action"
                    if (action == "start" && blockAfStart.compareAndSet(true, false)) {
                        afStartEntered.countDown()
                        check(releaseAfStart.await(8, TimeUnit.SECONDS))
                    }
                    if (action == "stop" && failAfStop.get()) return MockResponse().setResponseCode(503)
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
            viewModel.initialize(compose.activity)
            viewModel.useDirectCameraPreset()
            viewModel.setBaseUrl(baseUrl)
            viewModel.connect()
        }
        awaitFrame()
    }

    @After
    fun tearDown() {
        releaseAfStart.countDown()
        failAfStop.set(false)
        releaseStart.countDown()
        if (::viewModel.isInitialized) compose.runOnIdle { viewModel.disconnect() }
        compose.waitUntil(8_000) { !repository.isLiveViewRunning() }
        server.shutdown()
    }

    @Test
    fun shutterAfSettingTravelsFromProductionUiToHttpAndResetsOnReconnect() {
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) { OpenEosControlApp(viewModel) } }
        compose.onNodeWithTag("camera-action-menu-button").performClick()
        compose.onNodeWithTag("camera-action-settings").performClick()
        compose.onNodeWithTag("shutter-autofocus-setting").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.dismiss)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.capture_without_autofocus)).performClick()
        compose.waitUntil(8_000) { captureAf.size == 1 && !viewModel.uiState.value.busy }
        assertEquals(listOf(false), captureAf.toList())
        assertNull(viewModel.uiState.value.error)
        assertTrue(afWrites.isEmpty())
        compose.runOnIdle { viewModel.disconnect() }
        awaitStopped()
        assertTrue(viewModel.uiState.value.shutterAutofocus)
        compose.runOnIdle { viewModel.connect() }
        awaitFrame()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.capture_photo)).performClick()
        compose.waitUntil(8_000) { captureAf.size == 2 && !viewModel.uiState.value.busy }
        assertEquals(listOf(false, true), captureAf.toList())
    }

    @Test
    fun shutterAfCannotChangeWhileHeldOrCapturingAndFailedCaptureIsNotSuccess() {
        compose.runOnIdle { viewModel.startHeldAutofocus() }
        compose.waitUntil(8_000) { viewModel.uiState.value.autofocusHoldState == AutofocusHoldState.HOLDING }
        compose.runOnIdle { viewModel.setShutterAutofocus(false); viewModel.captureStill() }
        assertTrue(viewModel.uiState.value.shutterAutofocus)
        assertTrue(captureAf.isEmpty())
        compose.runOnIdle { viewModel.stopHeldAutofocus() }
        awaitAfIdle()
        failNextCapture.set(true)
        compose.runOnIdle {
            viewModel.setShutterAutofocus(false)
            viewModel.captureStill()
            viewModel.setShutterAutofocus(true)
        }
        compose.waitUntil(8_000) { viewModel.uiState.value.error != null && !viewModel.uiState.value.busy }
        assertFalse(viewModel.uiState.value.shutterAutofocus)
        assertEquals(listOf(false), captureAf.toList())
        assertNull(viewModel.uiState.value.captureFeedback)
        assertEquals(CameraOperation.CAPTURE, viewModel.uiState.value.errorOperation)
        val report = buildDiagnosticReport(viewModel.uiState.value)
        assertTrue(report.contains("shutterAutofocusSelectable=true"))
        assertTrue(report.contains("shutterAutofocusRequested=false"))
    }

    @Test
    fun heldAfStopsOnReleaseAndDoesNotUseTheOldTimedPulse() {
        compose.runOnIdle { viewModel.startHeldAutofocus() }
        compose.waitUntil(8_000) { viewModel.uiState.value.autofocusHoldState == AutofocusHoldState.HOLDING }
        Thread.sleep(700)
        assertEquals(listOf("start"), afWrites.toList())
        assertNull(viewModel.uiState.value.focusFeedback)
        assertTrue(viewModel.uiState.value.isBusy(CameraOperation.CAPTURE))
        compose.runOnIdle { viewModel.stopHeldAutofocus() }
        awaitAfIdle()
        assertEquals(listOf("start", "stop"), afWrites.toList())
        assertTrue(viewModel.uiState.value.connected)
    }

    @Test
    fun heldAfReleaseDuringStartDoesNotLeaveCameraActive() {
        blockAfStart.set(true)
        compose.runOnIdle { viewModel.startHeldAutofocus() }
        assertTrue(afStartEntered.await(8, TimeUnit.SECONDS))
        compose.runOnIdle { viewModel.stopHeldAutofocus() }
        assertEquals(AutofocusHoldState.RELEASING, viewModel.uiState.value.autofocusHoldState)
        releaseAfStart.countDown()
        awaitAfIdle()
        assertEquals(listOf("start", "stop"), afWrites.toList())
    }

    @Test
    fun backgroundReleasesAfBeforeStoppingViewAndDoesNotRestartAfOnResume() {
        compose.runOnIdle { viewModel.startHeldAutofocus() }
        compose.waitUntil(8_000) { viewModel.uiState.value.autofocusHoldState == AutofocusHoldState.HOLDING }
        compose.runOnIdle { viewModel.setAppForeground(false) }
        awaitStopped()
        assertTrue(cameraWrites.indexOf("af:stop") < cameraWrites.indexOf("view:off"))
        compose.runOnIdle { viewModel.setAppForeground(true) }
        awaitFrame()
        assertEquals(listOf("start", "stop"), afWrites.toList())
    }

    @Test
    fun failedAfStopBlocksNewCommandsUntilStopOnlyRetrySucceeds() {
        failAfStop.set(true)
        compose.runOnIdle { viewModel.startHeldAutofocus() }
        compose.waitUntil(8_000) { viewModel.uiState.value.autofocusHoldState == AutofocusHoldState.HOLDING }
        compose.runOnIdle { viewModel.stopHeldAutofocus() }
        compose.waitUntil(8_000) { viewModel.uiState.value.autofocusHoldState == AutofocusHoldState.RELEASE_FAILED }
        assertEquals(CameraOperation.FOCUS, viewModel.uiState.value.errorOperation)
        compose.runOnIdle {
            viewModel.startHeldAutofocus()
            viewModel.autofocus()
            viewModel.captureStill()
            viewModel.setLiveViewAutoRefresh(false)
        }
        assertTrue(repository.isLiveViewRunning())
        assertEquals(listOf("start", "stop"), afWrites.toList())
        failAfStop.set(false)
        compose.runOnIdle { viewModel.retryHeldAutofocusStop() }
        awaitStopped()
        assertEquals(listOf("start", "stop", "stop"), afWrites.toList())
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun leavingControlOrOpeningSettingsReleasesTheHeldCommand() {
        for (leave in listOf<() -> Unit>(
            { viewModel.openSettingPicker(SettingPicker.ISO) },
            { viewModel.setHudVisible(false) },
            { viewModel.setUiMode(UiMode.DEBUG) },
        )) {
            compose.runOnIdle { viewModel.startHeldAutofocus() }
            compose.waitUntil(8_000) { viewModel.uiState.value.autofocusHoldState == AutofocusHoldState.HOLDING }
            compose.runOnIdle(leave)
            awaitAfIdle()
            compose.runOnIdle {
                viewModel.closeSettingPicker()
                viewModel.setHudVisible(true)
                viewModel.setUiMode(UiMode.CONTROL)
            }
        }
        assertEquals(listOf("start", "stop", "start", "stop", "start", "stop"), afWrites.toList())
    }

    @Test
    fun clearingViewModelReleasesHeldAfBeforeRepositoryClose() {
        val store = ViewModelStore()
        compose.runOnIdle { store.put("camera", viewModel); viewModel.startHeldAutofocus() }
        compose.waitUntil(8_000) { viewModel.uiState.value.autofocusHoldState == AutofocusHoldState.HOLDING }
        compose.runOnIdle { store.clear() }
        compose.waitUntil(8_000) { !repository.isLiveViewRunning() }
        assertEquals(listOf("start", "stop"), afWrites.toList())
        assertTrue(cameraWrites.indexOf("af:stop") < cameraWrites.indexOf("view:off"))
    }

    @Test
    fun disconnectDuringStartReleasesBeforeClosingAndCannotRestoreOldUiState() {
        blockAfStart.set(true)
        compose.runOnIdle { viewModel.startHeldAutofocus() }
        assertTrue(afStartEntered.await(8, TimeUnit.SECONDS))
        compose.runOnIdle { viewModel.disconnect() }
        releaseAfStart.countDown()
        awaitStopped()
        assertFalse(viewModel.uiState.value.connected)
        assertEquals(AutofocusHoldState.IDLE, viewModel.uiState.value.autofocusHoldState)
        assertEquals(listOf("start", "stop"), afWrites.toList())
        assertTrue(cameraWrites.indexOf("af:stop") < cameraWrites.indexOf("view:off"))
    }

    private fun awaitAfIdle() = compose.waitUntil(8_000) {
        viewModel.uiState.value.autofocusHoldState == AutofocusHoldState.IDLE && !viewModel.uiState.value.busy
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
            {"path":"/shooting/control/af","post":true},
            {"path":"/shooting/control/shutterbutton","post":true}
        ]}"""
    }
}
