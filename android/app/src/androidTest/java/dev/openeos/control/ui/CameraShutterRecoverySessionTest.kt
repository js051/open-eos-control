package dev.openeos.control.ui

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.R
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.Job
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
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real ViewModel/repository/CCAPI paths against a synthetic HTTP peer, never physical-camera evidence. */
class CameraShutterRecoverySessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val store = ViewModelStore()
    private val repository = CameraRepository()
    private lateinit var viewModel: CameraViewModel
    private val failStart = AtomicBoolean(true)
    private val failRelease = AtomicBoolean(true)
    private val mode = AtomicReference("bulb")
    private val manualWrites = CopyOnWriteArrayList<String>()
    private val liveViewWrites = CopyOnWriteArrayList<String>()
    private val frames = AtomicInteger()
    private var replacementCamera: CameraSessionTestSimulator? = null
    private val useLargeFontLayout = mutableStateOf(false)

    @Before fun setUp() {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
        } finally { bitmap.recycle() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                if (request.method == "POST" && path.endsWith("/shutterbutton/manual")) {
                    val action = JSONObject(request.body.readUtf8()).getString("action")
                    manualWrites += action
                    val fail = if (action == "release") failRelease.get() else failStart.get()
                    return MockResponse().setResponseCode(if (fail) 503 else 204)
                }
                if (request.method == "POST" && path.endsWith("/shooting/liveview")) {
                    liveViewWrites += JSONObject(request.body.readUtf8()).getString("liveviewsize")
                    return json("{}")
                }
                if (request.method != "GET") return MockResponse().setResponseCode(405)
                return when {
                    path == "/ccapi" -> json(DISCOVERY)
                    path.endsWith("/deviceinformation") -> json("""{"productname":"Canon EOS Test Camera","serialnumber":"TEST-SHUTTER-0001"}""")
                    path.endsWith("/devicestatus/battery") -> json("""{"level":"90"}""")
                    path.endsWith("/shooting/settings") -> json("""{"shootingmode":{"value":"${mode.get()}","ability":["m","bulb"]}}""")
                    path.endsWith("/shooting/liveview/flip") -> {
                        frames.incrementAndGet()
                        MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val baseUrl = server.url("/").toString()
        compose.runOnIdle {
            viewModel = CameraViewModel(repository)
            store.put("shutter-recovery", viewModel)
            viewModel.initialize(compose.activity)
            viewModel.useDirectCameraPreset()
            viewModel.setBaseUrl(baseUrl)
            viewModel.connect()
        }
        compose.waitUntil(TIMEOUT) {
            val state = viewModel.uiState.value
            state.connected && !state.busy && state.liveViewBitmap != null
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
    }

    @After fun tearDown() {
        failStart.set(false)
        failRelease.set(false)
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

    @Test fun ambiguousBulbStartOffersOnlyStopAndDoesNotRestartLiveViewBeforeRelease() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.start_bulb_exposure)).performClick()
        awaitReleaseRecovery()
        assertEquals(listOf("full_press", "release"), manualWrites.toList())
        assertNull(viewModel.uiState.value.bulbStartedAtMillis)
        assertNull(viewModel.uiState.value.captureFeedback)
        val liveWritesBefore = liveViewWrites.toList()
        compose.runOnIdle {
            viewModel.captureStill()
            viewModel.toggleBulbExposure()
            viewModel.autofocus()
            viewModel.toggleRecording()
            viewModel.restartLiveView()
            viewModel.setAppForeground(false)
            viewModel.setAppForeground(true)
            viewModel.clearError()
        }
        compose.waitForIdle()
        assertEquals(listOf("full_press", "release"), manualWrites.toList())
        assertEquals(liveWritesBefore, liveViewWrites.toList())
        compose.onNodeWithTag("capture-mode-VIDEO").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.shutter_release_unconfirmed)).assertIsDisplayed()
        val retry = compose.onNodeWithContentDescription(compose.activity.getString(R.string.retry_shutter_release))
        retry.assertIsEnabled().performClick()
        compose.waitUntil(TIMEOUT) {
            manualWrites.size == 3 && CameraOperation.SHUTTER_RELEASE !in viewModel.uiState.value.pendingOperations
        }
        assertTrue(viewModel.uiState.value.shutterReleaseUnconfirmed)
        assertEquals(listOf("full_press", "release", "release"), manualWrites.toList())
        failRelease.set(false)
        val previousFrames = frames.get()
        retry.performClick()
        compose.waitUntil(TIMEOUT) {
            val state = viewModel.uiState.value
            !state.shutterReleaseUnconfirmed && !state.busy && frames.get() > previousFrames
        }
        assertEquals(listOf("full_press", "release", "release", "release"), manualWrites.toList())
        assertFalse(viewModel.uiState.value.bulbExposureActive)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.start_bulb_exposure)).assertIsEnabled()
    }

    @Test fun manualStillReleaseFailureUsesTheSameStopOnlyRecoveryWithoutAnotherCapture() {
        failStart.set(false)
        mode.set("m")
        compose.runOnIdle { viewModel.refresh() }
        compose.waitUntil(TIMEOUT) { !viewModel.uiState.value.busy && !viewModel.uiState.value.bulbMode }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.capture_photo)).performClick()
        awaitReleaseRecovery()
        assertEquals(listOf("full_press", "release"), manualWrites.toList())
        assertNull(viewModel.uiState.value.captureFeedback)
        failRelease.set(false)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.retry_shutter_release)).performClick()
        compose.waitUntil(TIMEOUT) { !viewModel.uiState.value.shutterReleaseUnconfirmed && !viewModel.uiState.value.busy }
        assertEquals(listOf("full_press", "release", "release"), manualWrites.toList())
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.capture_photo)).assertIsEnabled()
    }

    @Test fun disconnectRetainsAPreviousCameraWarningWithoutBlockingTheReplacementSession() {
        compose.runOnIdle { viewModel.toggleBulbExposure() }
        awaitReleaseRecovery()
        val cameraB = CameraSessionTestSimulator("SHUTTER-B").also {
            replacementCamera = it
            it.start()
        }
        compose.runOnIdle {
            viewModel.disconnect()
            assertTrue(viewModel.uiState.value.shutterDisconnectWarning)
            assertFalse(viewModel.uiState.value.shutterReleaseUnconfirmed)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.shutter_disconnect_warning)).assertIsDisplayed()
        compose.runOnIdle {
            viewModel.useDevSimulatorPreset()
            viewModel.setBaseUrl(cameraB.baseUrl)
            viewModel.setLiveViewAutoRefresh(false)
            viewModel.connect()
        }
        compose.waitUntil(TIMEOUT) {
            val state = viewModel.uiState.value
            state.info?.model == cameraB.model && state.connected && !state.busy
        }
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertTrue(state.shutterDisconnectWarning)
            assertFalse(state.shutterReleaseUnconfirmed)
            assertFalse(state.isBusy(CameraOperation.CAPTURE))
            assertTrue(cameraB.mutations.isEmpty())
        }
        cameraB.intercept = { request ->
            if (request.method == "PATCH" && request.requestUrl?.encodedPath == "/ccapi/exposure") {
                MockResponse().setResponseCode(503).setBody("Synthetic replacement-camera setting failure")
            } else null
        }
        compose.runOnIdle { viewModel.setIso("200") }
        compose.waitUntil(TIMEOUT) {
            val state = viewModel.uiState.value
            !state.busy && state.error != null && state.errorOperation == CameraOperation.SETTING
        }
        assertEquals(listOf("PATCH /ccapi/exposure"), cameraB.mutations.toList())
        assertTrue(viewModel.uiState.value.shutterDisconnectWarning)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.dismiss)).performClick()
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertNull(state.error)
            assertNull(state.errorOperation)
            assertTrue(state.shutterDisconnectWarning)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.shutter_disconnect_warning)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.dismiss)).performClick()
        compose.runOnIdle {
            assertFalse(viewModel.uiState.value.shutterDisconnectWarning)
        }
    }

    @Test fun persistentWarningAndStopOnlyActionRemainReadableOnNarrowLargeFontLayout() {
        compose.runOnIdle {
            viewModel.toggleBulbExposure()
            useLargeFontLayout.value = true
        }
        awaitReleaseRecovery()
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val english = compose.activity.createConfigurationContext(configuration)
        val warning = english.getString(R.string.shutter_release_unconfirmed)
        val retryDescription = english.getString(R.string.retry_shutter_release)
        val warningNode = compose.onNodeWithText(warning, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        warningNode.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val retry = compose.onNodeWithContentDescription(retryDescription).assertIsDisplayed().assertIsEnabled()
        val retryBounds = retry.fetchSemanticsNode().boundsInRoot
        val warningBounds = warningNode.boundsInRoot
        val root = compose.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val diagnostics = buildString {
            appendLine("warningBounds=$warningBounds")
            appendLine("retryBounds=$retryBounds")
            appendLine("rootBounds=$rootBounds")
            appendLine("textLayoutCount=${layouts.size}")
            layouts.forEachIndexed { index, layout ->
                appendLine("layout[$index].text=${layout.layoutInput.text.text}")
                appendLine("layout[$index].size=${layout.size}")
                appendLine("layout[$index].constraints=${layout.layoutInput.constraints}")
                appendLine("layout[$index].fontSize=${layout.layoutInput.style.fontSize}")
                appendLine("layout[$index].lineHeight=${layout.layoutInput.style.lineHeight}")
                appendLine("layout[$index].fontScale=${layout.layoutInput.density.fontScale}")
                appendLine("layout[$index].maxLines=${layout.layoutInput.maxLines}")
                appendLine("layout[$index].softWrap=${layout.layoutInput.softWrap}")
                appendLine("layout[$index].overflowPolicy=${layout.layoutInput.overflow}")
                appendLine("layout[$index].lineCount=${layout.lineCount}")
                appendLine("layout[$index].hasVisualOverflow=${layout.hasVisualOverflow}")
                appendLine("layout[$index].didOverflowWidth=${layout.didOverflowWidth}")
                appendLine("layout[$index].didOverflowHeight=${layout.didOverflowHeight}")
                for (line in 0 until layout.lineCount) {
                    appendLine("layout[$index].line[$line].bounds=" +
                        "left=${layout.getLineLeft(line)}, top=${layout.getLineTop(line)}, " +
                        "right=${layout.getLineRight(line)}, bottom=${layout.getLineBottom(line)}")
                    appendLine("layout[$index].line[$line].ellipsis=${layout.isLineEllipsized(line)}")
                }
            }
        }
        File(compose.activity.cacheDir, "shutter-recovery-en-320dp-font2.txt").writeText(diagnostics)
        // Persist evidence before a strict overflow/overlap assertion can end the test.
        val screenshot = root.captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "shutter-recovery-en-320dp-font2.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse("The entire exposure warning and retry instruction must fit.", layout.hasVisualOverflow)
            assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
        }
        assertTrue("The warning must not cover the stop-only control.", warningBounds.bottom <= retryBounds.top)
        failRelease.set(false)
        retry.performClick()
        compose.waitUntil(TIMEOUT) { !viewModel.uiState.value.shutterReleaseUnconfirmed && !viewModel.uiState.value.busy }
        assertEquals(listOf("full_press", "release", "release"), manualWrites.toList())
    }

    private fun awaitReleaseRecovery() = compose.waitUntil(TIMEOUT) {
        val state = viewModel.uiState.value
        state.shutterReleaseUnconfirmed && CameraOperation.CAPTURE !in state.pendingOperations
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val TIMEOUT = 15_000L
        const val DISCOVERY = """{"ver100":[
          {"path":"/deviceinformation","get":true},
          {"path":"/devicestatus/battery","get":true},
          {"path":"/shooting/settings","get":true},
          {"path":"/shooting/control/shutterbutton/manual","post":true},
          {"path":"/shooting/liveview","post":true},
          {"path":"/shooting/liveview/flip","get":true}
        ]}"""
    }
}
