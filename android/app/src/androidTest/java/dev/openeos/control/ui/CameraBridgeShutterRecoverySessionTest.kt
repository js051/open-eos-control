package dev.openeos.control.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.R
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
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
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Real Compose app -> ViewModel -> repository -> independent synthetic Bridge HTTP peer.
 * These fixtures are protocol-contract evidence, never physical-camera validation.
 */
class CameraBridgeShutterRecoverySessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val peer = BridgeRecoveryPeer("A")
    private var replacement: BridgeRecoveryPeer? = null
    private val store = ViewModelStore()
    // Exercise production Android routing and the unmodified default OkHttp retry policy.
    private val repository = CameraRepository()
    private val useLargeFontLayout = mutableStateOf(false)
    private lateinit var viewModel: CameraViewModel
    private val captureSuccesses = AtomicInteger()

    @Before fun setUp() {
        peer.start()
        compose.runOnIdle {
            viewModel = CameraViewModel(repository)
            store.put("bridge-shutter-recovery", viewModel)
            viewModel.initialize(compose.activity)
            viewModel.viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                viewModel.uiState.collect { state ->
                    if (state.captureFeedback == CaptureFeedback.SUCCESS) captureSuccesses.incrementAndGet()
                }
            }
            viewModel.setConnectionTarget(ConnectionTarget.DESKTOP_BRIDGE)
            viewModel.setBridgeBaseUrl(peer.baseUrl)
            viewModel.connectBridge()
        }
        compose.waitUntil(TIMEOUT) {
            val state = viewModel.uiState.value
            state.connected && state.pendingOperations.isEmpty() && state.liveViewBitmap != null
        }
        compose.setContent {
            if (useLargeFontLayout.value) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                        DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("en"))) {
                            OpenEosControlApp(viewModel)
                        }
                    }
                }
            } else OpenEosControlApp(viewModel)
        }
        compose.onNodeWithContentDescription(text(R.string.start_bulb_exposure)).assertIsEnabled()
    }

    @After fun tearDown() {
        try {
            if (::viewModel.isInitialized) {
                val job = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
                compose.runOnIdle { store.clear() }
                compose.waitUntil(TIMEOUT) { job.isCompleted }
                // onCleared also closes the repository outside the ViewModel's cancelled scope.
                runBlocking { withTimeout(TIMEOUT) { repository.disconnect() } }
            }
        } finally {
            try {
                peer.close()
            } finally {
                replacement?.close()
            }
        }
    }

    @Test fun typedStartFailureAllowsOnlyReleaseAndRepeatedStopFailuresNeverResumeLiveView() {
        // Verify these advertised controls were available before recovery blocked them.
        compose.onNodeWithTag("capture-mode-VIDEO").assertIsEnabled()
        compose.onNodeWithTag("exposure-control-ISO").assertIsEnabled()
        startAmbiguousExposure()
        assertEquals(1, peer.count("POST", "bulb/start"))
        assertEquals(0, peer.count("POST", "bulb/stop"))
        val mutationsBefore = peer.mutations()
        val framesBefore = peer.frames.get()

        compose.runOnIdle {
            viewModel.captureStill()
            viewModel.setIso("200")
            viewModel.toggleBulbExposure()
            viewModel.autofocus()
            viewModel.toggleRecording()
            viewModel.restartLiveView()
            viewModel.refreshLiveViewFrame()
            viewModel.setAppForeground(false)
            viewModel.setAppForeground(true)
            viewModel.clearError()
            // An allowed status read supplies a completion barrier for the blocked actions.
            viewModel.refresh()
        }
        awaitOperations()
        assertEquals(mutationsBefore, peer.mutations())
        assertEquals(framesBefore, peer.frames.get())
        compose.onNodeWithTag("capture-mode-VIDEO").assertIsNotEnabled()
        compose.onNodeWithTag("exposure-control-ISO").assertIsNotEnabled()
        assertRecoveryVisible()

        repeat(2) { index ->
            retryRelease()
            assertEquals(index + 1, peer.count("POST", "bulb/stop"))
            assertRecoveryVisible()
            assertEquals(framesBefore, peer.frames.get())
        }
        assertEquals(0, captureSuccesses.get())

        peer.stopReply.set(StopReply.RELEASED)
        retryNode().performClick()
        compose.waitUntil(TIMEOUT) {
            val state = viewModel.uiState.value
            !state.shutterReleaseUnconfirmed && state.pendingOperations.isEmpty() && peer.frames.get() > framesBefore
        }
        assertEquals(3, peer.count("POST", "bulb/stop"))
        assertEquals(1, peer.count("POST", "bulb/start"))
        assertFalse(viewModel.uiState.value.bulbExposureActive)
        assertEquals(false, viewModel.uiState.value.status?.shutterReleaseUnconfirmed)
        assertNull(viewModel.uiState.value.bulbStartedAtMillis)
        assertEquals(0, captureSuccesses.get())
        compose.onNodeWithContentDescription(text(R.string.start_bulb_exposure)).assertIsEnabled()
    }

    @Test fun lostStartResponseIsNotReplayedAndRecoveryFitsNarrowLargeFontLayout() {
        peer.startReply.set(StartReply.LOST_RESPONSE)
        compose.runOnIdle { useLargeFontLayout.value = true }
        startAmbiguousExposure()
        assertWarningFitsAboveRetry()
        assertEquals(1, peer.count("POST", "bulb/start"))
        val framesBefore = peer.frames.get()
        compose.runOnIdle {
            viewModel.clearError()
            viewModel.toggleBulbExposure()
            viewModel.captureStill()
            viewModel.restartLiveView()
            viewModel.refresh()
        }
        awaitOperations()
        assertRecoveryVisible()
        assertEquals(1, peer.count("POST", "bulb/start"))
        assertEquals(0, peer.count("POST", "capture/still"))
        assertEquals(framesBefore, peer.frames.get())
        assertEquals(0, captureSuccesses.get())
        retryRelease()
        assertRecoveryVisible()
        assertEquals(1, peer.count("POST", "bulb/stop"))
    }

    @Test fun statusOnlyUncertaintySurvivesCapabilitiesFailureAndManualModeWithoutBulbCapability() {
        peer.remote.set(RemoteState(mode = "Bulb", bulb = null, releaseUnconfirmed = true))
        peer.failCapabilities.set(true)
        compose.runOnIdle { viewModel.refresh() }
        awaitOperations()
        assertRecoveryVisible()
        assertEquals(CameraOperation.STATUS, viewModel.uiState.value.errorOperation)
        assertEquals(0, peer.count("POST", "bulb/start"))

        peer.remote.set(RemoteState(mode = "Manual", bulb = null, releaseUnconfirmed = true))
        peer.failCapabilities.set(false)
        peer.advertiseBulb.set(false)
        val framesBefore = peer.frames.get()
        compose.runOnIdle { viewModel.refresh() }
        awaitOperations()
        assertFalse(viewModel.uiState.value.bulbMode)
        assertFalse(viewModel.uiState.value.supports(CameraFeature.BULB_EXPOSURE))
        assertRecoveryVisible()
        retryRelease()
        assertEquals(1, peer.count("POST", "bulb/stop"))
        assertRecoveryVisible()
        assertEquals(framesBefore, peer.frames.get())
        assertEquals(0, captureSuccesses.get())
    }

    @Test fun legacyStop200WithBulbFalseButMissingReleaseFieldCannotClearAmbiguousStart() {
        peer.startReply.set(StartReply.LOST_RESPONSE)
        peer.omitReleaseField.set(true)
        peer.stopReply.set(StopReply.LEGACY_IDLE)
        startAmbiguousExposure()
        val framesBefore = peer.frames.get()
        retryRelease()
        assertRecoveryVisible()
        compose.runOnIdle {
            viewModel.refresh()
            viewModel.captureStill()
            viewModel.toggleBulbExposure()
        }
        awaitOperations()
        assertRecoveryVisible()
        assertEquals(1, peer.count("POST", "bulb/start"))
        assertEquals(1, peer.count("POST", "bulb/stop"))
        assertEquals(0, peer.count("POST", "capture/still"))
        assertEquals(framesBefore, peer.frames.get())
        assertEquals(0, captureSuccesses.get())
    }

    @Test fun reconnectKeepsPreviousSessionWarningAndNeverSendsOldStopToBridgeB() {
        startAmbiguousExposure()
        retryRelease()
        assertRecoveryVisible()
        val cameraB = BridgeRecoveryPeer("B").also {
            replacement = it
            it.remote.set(RemoteState(mode = "Manual"))
            it.start()
        }
        compose.runOnIdle {
            viewModel.disconnect()
            assertTrue(viewModel.uiState.value.shutterDisconnectWarning)
            assertFalse(viewModel.uiState.value.shutterReleaseUnconfirmed)
            viewModel.setBridgeBaseUrl(cameraB.baseUrl)
            viewModel.setLiveViewAutoRefresh(false)
            viewModel.connectBridge()
        }
        compose.waitUntil(TIMEOUT) {
            val state = viewModel.uiState.value
            state.info?.model == cameraB.model && state.connected && state.pendingOperations.isEmpty()
        }
        compose.onNodeWithText(text(R.string.shutter_disconnect_warning)).assertIsDisplayed()
        compose.runOnIdle {
            viewModel.retryShutterRelease()
            assertTrue(viewModel.uiState.value.shutterDisconnectWarning)
            assertFalse(viewModel.uiState.value.shutterReleaseUnconfirmed)
            assertFalse(viewModel.uiState.value.isBusy(CameraOperation.CAPTURE))
        }
        assertEquals(1, peer.count("POST", "bulb/stop"))
        assertEquals(0, cameraB.count("POST", "bulb/stop"))
        assertTrue(cameraB.mutations().isEmpty())
        assertEquals(0, captureSuccesses.get())

        compose.onNodeWithContentDescription(text(R.string.capture_photo)).assertIsEnabled().performClick()
        compose.waitUntil(TIMEOUT) {
            cameraB.count("POST", "capture/still") == 1 &&
                viewModel.uiState.value.pendingOperations.isEmpty() && captureSuccesses.get() > 0
        }
        assertEquals(0, cameraB.count("POST", "bulb/stop"))
        assertTrue(viewModel.uiState.value.shutterDisconnectWarning)
        compose.onNodeWithText(text(R.string.shutter_disconnect_warning)).assertIsDisplayed()
    }

    @Test fun confirmedActiveExposureKeepsStopWhenModeIsManualAndBulbCapabilityIsAbsent() {
        val cameraB = BridgeRecoveryPeer("B").also {
            replacement = it
            it.remote.set(RemoteState(mode = "Manual", bulb = true, releaseUnconfirmed = false))
            it.advertiseBulb.set(false)
            it.stopReply.set(StopReply.RELEASED)
            it.start()
        }
        compose.runOnIdle {
            viewModel.disconnect()
            viewModel.setBridgeBaseUrl(cameraB.baseUrl)
            viewModel.connectBridge()
        }
        // An active exposure intentionally keeps busy=true; only pending commands must settle.
        compose.waitUntil(TIMEOUT) {
            val state = viewModel.uiState.value
            state.info?.model == cameraB.model && state.connected && state.pendingOperations.isEmpty()
        }
        assertTrue(viewModel.uiState.value.bulbExposureActive)
        assertFalse(viewModel.uiState.value.bulbMode)
        assertFalse(viewModel.uiState.value.supports(CameraFeature.BULB_EXPOSURE))
        assertFalse(viewModel.uiState.value.shutterReleaseUnconfirmed)
        assertEquals(0, cameraB.count("POST", "bulb/start"))
        assertEquals(0, cameraB.count("POST", "liveview/start"))
        assertEquals(0, cameraB.frames.get())
        compose.onNodeWithContentDescription(text(R.string.capture_photo)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.start_recording)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.stop_bulb_exposure))
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(TIMEOUT) {
            val state = viewModel.uiState.value
            cameraB.count("POST", "bulb/stop") == 1 && !state.bulbExposureActive && state.pendingOperations.isEmpty()
        }
        assertEquals(false, viewModel.uiState.value.status?.bulbExposureActive)
        assertEquals(false, viewModel.uiState.value.status?.shutterReleaseUnconfirmed)
        assertEquals(0, cameraB.count("POST", "bulb/start"))
        assertEquals(0, cameraB.count("POST", "capture/still"))
        assertEquals(0, cameraB.count("POST", "recording/start"))
        compose.onNodeWithContentDescription(text(R.string.capture_photo)).assertIsEnabled()
    }

    private fun startAmbiguousExposure() {
        compose.onNodeWithContentDescription(text(R.string.start_bulb_exposure)).performClick()
        compose.waitUntil(TIMEOUT) {
            viewModel.uiState.value.shutterReleaseUnconfirmed && viewModel.uiState.value.pendingOperations.isEmpty()
        }
        assertRecoveryVisible()
    }

    private fun retryRelease() {
        val before = peer.count("POST", "bulb/stop")
        retryNode().performClick()
        compose.waitUntil(TIMEOUT) {
            peer.count("POST", "bulb/stop") > before && viewModel.uiState.value.pendingOperations.isEmpty()
        }
    }

    private fun awaitOperations() = compose.waitUntil(TIMEOUT) {
        viewModel.uiState.value.pendingOperations.isEmpty()
    }

    private fun assertRecoveryVisible() {
        assertTrue(viewModel.uiState.value.shutterReleaseUnconfirmed)
        assertNull(viewModel.uiState.value.bulbStartedAtMillis)
        assertNull(viewModel.uiState.value.captureFeedback)
        compose.onNodeWithText(text(R.string.bridge_shutter_release_unconfirmed)).assertIsDisplayed()
        retryNode().assertIsDisplayed().assertIsEnabled()
    }

    private fun retryNode() = compose.onNodeWithContentDescription(text(R.string.retry_shutter_release))
    private fun text(id: Int): String {
        if (!useLargeFontLayout.value) return compose.activity.getString(id)
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        return compose.activity.createConfigurationContext(configuration).getString(id)
    }

    private fun assertWarningFitsAboveRetry() {
        val warningNode = compose.onNodeWithText(text(R.string.bridge_shutter_release_unconfirmed), useUnmergedTree = true)
            .assertIsDisplayed()
        val warning = warningNode.fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        warning.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertTrue("The warning must provide a measurable text layout.", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse("Bridge recovery guidance must fit at 320dp / 2x font.", layout.hasVisualOverflow)
            assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
        }
        val retryBounds = retryNode().assertIsDisplayed().assertIsEnabled().getUnclippedBoundsInRoot()
        val warningBounds = warningNode.getUnclippedBoundsInRoot()
        val rootBounds = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("The complete warning must remain within the screen.",
            warningBounds.top >= rootBounds.top && warningBounds.bottom <= rootBounds.bottom &&
                warningBounds.left >= rootBounds.left && warningBounds.right <= rootBounds.right)
        assertTrue("The complete RETRY STOP control must remain within the screen.",
            retryBounds.top >= rootBounds.top && retryBounds.bottom <= rootBounds.bottom &&
                retryBounds.left >= rootBounds.left && retryBounds.right <= rootBounds.right)
        assertTrue("The warning must not cover RETRY STOP.", warningBounds.bottom <= retryBounds.top)
    }

    private companion object { const val TIMEOUT = 15_000L }
}

private enum class StartReply { UNCONFIRMED, LOST_RESPONSE }
private enum class StopReply { UNCONFIRMED, LEGACY_IDLE, RELEASED }
private data class RemoteState(
    val mode: String = "Bulb",
    val bulb: Boolean? = false,
    val releaseUnconfirmed: Boolean? = false,
)

/** Minimal Bridge-v1 peer. Unknown routes fail, and no physical camera protocol is simulated. */
private class BridgeRecoveryPeer(private val label: String) : AutoCloseable {
    private val server = MockWebServer()
    val model = "Synthetic Bridge Camera $label"
    private val sessionPath = "/v1/session/synthetic-session-$label"
    val remote = AtomicReference(RemoteState())
    val startReply = AtomicReference(StartReply.UNCONFIRMED)
    val stopReply = AtomicReference(StopReply.UNCONFIRMED)
    val omitReleaseField = AtomicBoolean(false)
    val failCapabilities = AtomicBoolean(false)
    val advertiseBulb = AtomicBoolean(true)
    val frames = AtomicInteger()
    private val requests = CopyOnWriteArrayList<String>()
    lateinit var baseUrl: String
        private set

    fun start() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Bridge fixture initialization must run on the instrumentation test thread."
        }
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
        } finally { bitmap.recycle() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                val method = request.method
                requests += "$method $path"
                return when {
                    method == "GET" && path == "/health" -> json("""{"service":"open-eos-control-bridge"}""")
                    method == "POST" && path == "/v1/session" ->
                        json("""{"id":"synthetic-session-$label"}""").setResponseCode(201)
                    method == "DELETE" && path == sessionPath -> MockResponse().setResponseCode(204)
                    method == "GET" && path == "$sessionPath/info" ->
                        json("""{"connected":true,"model":"$model","serial":"SYNTHETIC-BRIDGE-$label"}""")
                    method == "GET" && path == "$sessionPath/status" -> status()
                    method == "GET" && path == "$sessionPath/capabilities" ->
                        if (failCapabilities.get()) error("SYNTHETIC_CAPABILITIES_FAILURE") else capabilities()
                    method == "POST" && path == "$sessionPath/bulb/start" -> {
                        remote.updateAndGet { it.copy(bulb = null, releaseUnconfirmed = true) }
                        when (startReply.get()) {
                            StartReply.UNCONFIRMED -> error("SHUTTER_RELEASE_UNCONFIRMED")
                            StartReply.LOST_RESPONSE -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        }
                    }
                    method == "POST" && path == "$sessionPath/bulb/stop" -> {
                        when (stopReply.get()) {
                            StopReply.UNCONFIRMED -> error("SHUTTER_RELEASE_UNCONFIRMED")
                            StopReply.LEGACY_IDLE -> {
                                remote.updateAndGet { it.copy(bulb = false, releaseUnconfirmed = null) }
                                status()
                            }
                            StopReply.RELEASED -> {
                                remote.updateAndGet { it.copy(bulb = false, releaseUnconfirmed = false) }
                                status()
                            }
                        }
                    }
                    method == "POST" && path in setOf("$sessionPath/liveview/start", "$sessionPath/liveview/stop") -> json("{}")
                    method == "GET" && path == "$sessionPath/liveview/frame" -> {
                        frames.incrementAndGet()
                        MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg))
                    }
                    method == "POST" && path == "$sessionPath/capture/still" -> status()
                    method == "POST" && path in setOf(
                        "$sessionPath/settings/iso", "$sessionPath/focus/auto", "$sessionPath/recording/start",
                    ) -> status()
                    else -> MockResponse().setResponseCode(404).setBody("Synthetic peer has no route: $method $path")
                }
            }
        }
        server.start()
        // MockWebServer URL creation may resolve the hostname; keep it off the UI thread.
        baseUrl = server.url("/").toString()
    }

    fun count(method: String, suffix: String): Int = requests.count { it == "$method $sessionPath/$suffix" }
    fun mutations(): List<String> = requests.filter { it.startsWith("POST $sessionPath/") }

    override fun close() { server.shutdown() }

    private fun status(): MockResponse {
        val state = remote.get()
        val body = JSONObject()
            .put("connected", true)
            .put("mode", state.mode)
            .put("recording", false)
            .put("bulbExposureActive", state.bulb ?: JSONObject.NULL)
            .put("battery", JSONObject().put("level", 90).put("status", "full"))
            .put("exposure", JSONObject().put("iso", "100").put("shutter", "1/125").put("aperture", "4"))
        if (!omitReleaseField.get()) body.put("shutterReleaseUnconfirmed", state.releaseUnconfirmed ?: JSONObject.NULL)
        return json(body.toString())
    }

    private fun capabilities(): MockResponse {
        val features = mutableListOf(
            "DESKTOP_BRIDGE", "CAMERA_IDENTITY", "LIVE_VIEW", "LIVE_VIEW_JPEG_POLLING", "STILL_CAPTURE",
            "EXPOSURE_CONTROL", "AUTOFOCUS", "VIDEO_RECORDING",
        )
        if (advertiseBulb.get()) features += "BULB_EXPOSURE"
        return json(JSONObject()
            .put("supported", JSONArray(features))
            .put("settings", JSONArray().put(JSONObject()
                .put("key", "iso").put("value", "100").put("values", JSONArray(listOf("100", "200")))))
            .put("liveView", JSONObject()
                .put("sources", JSONArray(listOf("DESKTOP_BRIDGE_STREAM")))
                .put("sizes", JSONArray(listOf("MEDIUM")))
                .put("defaultSource", "DESKTOP_BRIDGE_STREAM").put("defaultSize", "MEDIUM")
                .put("minFps", 1).put("maxFps", 5))
            .toString())
    }

    private fun error(code: String): MockResponse = json(JSONObject().put("error", JSONObject()
        .put("code", code).put("message", "Synthetic Bridge test failure").put("feature", "BULB_EXPOSURE"))
        .toString()).setResponseCode(503)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
