package dev.openeos.control.ui

import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraBackendFactory
import dev.openeos.control.data.CameraHttpTransport
import dev.openeos.control.data.CameraHttpTransportFactory
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Production VM/repository/CCAPI cancellation before the Android bitmap boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraFirstFrameCancellationTest {
    private val main = StandardTestDispatcher()
    private val server = MockWebServer()
    private val frameReads = AtomicInteger()
    private val successfulFrameReads = CopyOnWriteArraySet(listOf(1))
    private val viewWrites = CopyOnWriteArrayList<String>()
    private val shutterWrites = CopyOnWriteArrayList<String>()
    private val blockRelease = AtomicBoolean(false)
    private val releaseEntered = CountDownLatch(1)
    private val allowRelease = CountDownLatch(1)
    private val transportTrace = CopyOnWriteArrayList<String>()
    private val http = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) { transportTrace += "start ${call.request().method} ${call.request().url.encodedPath}" }
            override fun callEnd(call: Call) { transportTrace += "end ${call.request().method} ${call.request().url.encodedPath}" }
            override fun callFailed(call: Call, ioe: IOException) {
                transportTrace += "failed ${call.request().method} ${call.request().url.encodedPath}: ${ioe.javaClass.simpleName}: ${ioe.message}"
            }
        })
        .build()
    private val repository = CameraRepository(CameraBackendFactory(
        httpTransportFactory = CameraHttpTransportFactory {
            CameraHttpTransport(http, CameraNetworkDiagnostics.Empty)
        },
    ))
    private lateinit var viewModel: CameraViewModel

    @Before fun setUp() {
        Dispatchers.setMain(main)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                if (request.method == "POST" && path.endsWith("/shutterbutton/manual")) {
                    val action = JSONObject(request.body.readUtf8()).getString("action")
                    shutterWrites += action
                    if (action == "release" && blockRelease.get()) {
                        releaseEntered.countDown()
                        check(allowRelease.await(3, TimeUnit.SECONDS)) { "Synthetic release gate was not opened" }
                    }
                    return MockResponse().setResponseCode(204)
                }
                if (request.method == "POST" && path.endsWith("/shooting/liveview")) {
                    viewWrites += JSONObject(request.body.readUtf8()).getString("liveviewsize")
                    return json("{}")
                }
                return when {
                    path == "/ccapi" -> json(DISCOVERY)
                    path.endsWith("/deviceinformation") -> json("""{"productname":"Synthetic first-frame camera","serialnumber":"TEST-FIRST-FRAME"}""")
                    path.endsWith("/devicestatus/battery") -> json("""{"level":"90"}""")
                    path.endsWith("/shooting/settings") -> json("{}")
                    path.endsWith("/shooting/liveview/flip") -> {
                        if (frameReads.incrementAndGet() in successfulFrameReads) {
                            // The backend's startup probe scans JPEG boundaries without decoding.
                            // The second request never reaches BitmapFactory, which needs Android.
                            MockResponse().setHeader("Content-Type", "image/jpeg")
                                .setBody(Buffer().write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())))
                        } else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        viewModel = CameraViewModel(repository)
        viewModel.useDirectCameraPreset()
        // Keep the peer's concrete address: localhost may resolve to IPv6 while this fixture
        // listens on IPv4, which is unrelated to first-frame cancellation or command replay.
        viewModel.setBaseUrl(server.url("/").newBuilder().host("127.0.0.1").build().toString())
    }

    @After fun tearDown() = runBlocking {
        allowRelease.countDown()
        try {
            if (::viewModel.isInitialized) {
                println("Before teardown: connected=${viewModel.uiState.value.connected}; pending=${viewModel.uiState.value.pendingOperations}; running=${repository.isLiveViewRunning()}; stopRequired=${repository.isLiveViewStopRequired()}; viewWrites=$viewWrites")
                println(transportTrace.joinToString("\n"))
                viewModel.disconnect()
                http.dispatcher.cancelAll()
                pumpUntil { http.dispatcher.runningCallsCount() == 0 && !repository.isLiveViewRunning() }
                viewModel.viewModelScope.cancel()
                main.scheduler.runCurrent()
            }
        } finally {
            try { server.shutdown() } finally { Dispatchers.resetMain() }
        }
    }

    @Test fun disconnectCancelsPublishedSessionsFirstFrameAndClearsConnectBusy() = runBlocking {
        connectUntilFirstFrameIsBlocked()
        viewModel.disconnect()
        assertTrue(pumpUntil { activeFrameReads() == 0 && !repository.isLiveViewRunning() })
        assertFalse(viewModel.uiState.value.connected)
        assertTrue(viewModel.uiState.value.pendingOperations.isEmpty())
        // A replacement connect joins the actual old cleanup; a cleared UI alone is insufficient.
        viewModel.setLiveViewAutoRefresh(false)
        viewModel.connect()
        assertTrue(pumpUntil { viewModel.uiState.value.connected && !viewModel.uiState.value.busy })
        assertEquals(listOf("medium", "off"), viewWrites.toList())
    }

    @Test fun disablingLiveViewCancelsPublishedSessionsFirstFrameWithoutDisconnecting() = runBlocking {
        assertPreviewStopCancelsInitialFrame { viewModel.setLiveViewAutoRefresh(false) }
    }

    @Test fun backgroundingCancelsPublishedSessionsFirstFrameWithoutDisconnecting() = runBlocking {
        assertPreviewStopCancelsInitialFrame { viewModel.setAppForeground(false) }
    }

    @Test fun disablingLiveViewCancelsAReconciliationsFirstFrameAndReleasesItsMutex() = runBlocking {
        connectWithLiveViewOff()
        viewModel.setLiveViewAutoRefresh(true)
        assertTrue(pumpUntil { frameReads.get() == 2 && CameraOperation.LIVE_VIEW in viewModel.uiState.value.pendingOperations })
        viewModel.setLiveViewAutoRefresh(false)
        assertTrue("Stop must not wait behind the transition's blocked first-frame read", pumpUntil(1_000) {
            activeFrameReads() == 0 && !repository.isLiveViewRunning() && !viewModel.uiState.value.busy
        })
        assertTrue(viewModel.uiState.value.connected)
        assertEquals(listOf("medium", "off"), viewWrites.toList())
    }

    @Test fun explicitRestartCancelsTheOldFirstFrameBeforeWaitingForTheTransitionMutex() = runBlocking {
        connectWithLiveViewOff()
        viewModel.setLiveViewAutoRefresh(true)
        assertTrue(pumpUntil { frameReads.get() == 2 && activeFrameReads() == 1 })
        successfulFrameReads += 3 // The explicitly requested replacement start's validation probe.
        viewModel.restartLiveView()
        assertTrue("Restart must reach its own first-frame read without the old HTTP timeout", pumpUntil(1_000) {
            frameReads.get() == 4 && activeFrameReads() == 1
        })
        assertEquals(listOf("medium", "off", "medium"), viewWrites.toList())
        viewModel.setLiveViewAutoRefresh(false)
        assertTrue("The old read's finally must not clear the replacement read owner", pumpUntil(1_000) {
            activeFrameReads() == 0 && !repository.isLiveViewRunning() && !viewModel.uiState.value.busy
        })
        assertEquals(listOf("medium", "off", "medium", "off"), viewWrites.toList())
    }

    @Test fun rapidOffThenOnRetainsTheReplacementFrameOwnerWhileConnectFinishes() = runBlocking {
        connectUntilFirstFrameIsBlocked()
        viewModel.setLiveViewAutoRefresh(false)
        viewModel.setLiveViewAutoRefresh(true)
        assertTrue(pumpUntil {
            frameReads.get() == 3 && activeFrameReads() == 1 &&
                CameraOperation.CONNECT !in viewModel.uiState.value.pendingOperations
        })
        assertTrue(viewModel.uiState.value.connected)
        assertEquals(null, viewModel.uiState.value.liveViewBitmap)
        assertEquals(null, viewModel.uiState.value.error)
        viewModel.setLiveViewAutoRefresh(false)
        assertTrue("Stopping the replacement must still cancel its active first-frame read", pumpUntil(1_000) {
            activeFrameReads() == 0 && !repository.isLiveViewRunning() && !viewModel.uiState.value.busy
        })
        assertEquals(listOf("medium", "off"), viewWrites.toList())
    }

    @Test fun backgroundAfterCaptureReleaseCancelsOnlyItsAuxiliaryFrame() = runBlocking {
        connectWithLiveViewOff()
        // Establish a real running backend without crossing Android BitmapFactory. Queueing the
        // capture in the same turn makes the existing capture interlock defer UI reconciliation.
        repository.setLiveViewEnabled(true)
        viewModel.setLiveViewAutoRefresh(true)
        viewModel.captureStill()
        assertTrue(pumpUntil { frameReads.get() == 2 && activeFrameReads() == 1 })
        assertEquals(listOf("full_press", "release"), shutterWrites.toList())
        assertEquals(setOf(CameraOperation.CAPTURE), viewModel.uiState.value.pendingOperations)
        viewModel.setAppForeground(false)
        assertTrue("An acknowledged capture and release must not stay busy on an off-screen frame", pumpUntil(1_000) {
            activeFrameReads() == 0 && !viewModel.uiState.value.busy && !repository.isLiveViewRunning()
        })
        assertTrue(viewModel.uiState.value.connected)
        assertEquals(listOf("full_press", "release"), shutterWrites.toList())
        assertEquals(listOf("medium", "off"), viewWrites.toList())
    }

    @Test fun backgroundWhileCaptureReleaseIsUnacknowledgedDoesNotCancelTheReleaseOrStopEarly() = runBlocking {
        connectWithLiveViewOff()
        repository.setLiveViewEnabled(true)
        blockRelease.set(true)
        viewModel.setLiveViewAutoRefresh(true)
        viewModel.captureStill()
        assertTrue(pumpUntil { releaseEntered.count == 0L })
        assertEquals(listOf("full_press", "release"), shutterWrites.toList())
        viewModel.setAppForeground(false)
        main.scheduler.runCurrent()
        assertEquals(setOf(CameraOperation.CAPTURE), viewModel.uiState.value.pendingOperations)
        assertEquals(listOf("medium"), viewWrites.toList())
        assertTrue(repository.isLiveViewRunning())
        val releaseCall = http.dispatcher.runningCalls().single {
            it.request().method == "POST" && it.request().url.encodedPath.endsWith("/shutterbutton/manual")
        }
        assertFalse("Background frame cleanup must not cancel the unacknowledged release", releaseCall.isCanceled())
        allowRelease.countDown()
        assertTrue(pumpUntil { !viewModel.uiState.value.busy && !repository.isLiveViewRunning() })
        assertTrue(viewModel.uiState.value.connected)
        assertEquals(listOf("full_press", "release"), shutterWrites.toList())
        assertEquals(listOf("medium", "off"), viewWrites.toList())
    }

    private suspend fun connectWithLiveViewOff() {
        viewModel.setLiveViewAutoRefresh(false)
        viewModel.connect()
        assertTrue(pumpUntil { viewModel.uiState.value.connected && !viewModel.uiState.value.busy })
        assertFalse(repository.isLiveViewRunning())
        assertEquals(0, frameReads.get())
    }

    private suspend fun assertPreviewStopCancelsInitialFrame(stop: () -> Unit) {
        connectUntilFirstFrameIsBlocked()
        stop()
        assertTrue(pumpUntil {
            !repository.isLiveViewRunning() && CameraOperation.LIVE_VIEW !in viewModel.uiState.value.pendingOperations
        })
        assertEquals(listOf("medium", "off"), viewWrites.toList())
        assertTrue(viewModel.uiState.value.connected)
        // Pumping Main remains possible: this must not pass by waiting for the 30-second HTTP timeout.
        assertTrue("Turning Live View off must cancel its initial frame, not leave CONNECT waiting on HTTP",
            pumpUntil(timeoutMillis = 1_000) {
                activeFrameReads() == 0 && CameraOperation.CONNECT !in viewModel.uiState.value.pendingOperations
            })
        assertFalse(viewModel.uiState.value.busy)
    }

    private suspend fun connectUntilFirstFrameIsBlocked() {
        viewModel.connect()
        assertTrue(pumpUntil { frameReads.get() == 2 && viewModel.uiState.value.connected })
        assertEquals(setOf(CameraOperation.CONNECT), viewModel.uiState.value.pendingOperations)
        assertTrue(repository.isLiveViewRunning())
        assertEquals(1, activeFrameReads())
    }

    private fun activeFrameReads(): Int = http.dispatcher.runningCalls()
        .count { it.request().url.encodedPath.endsWith("/shooting/liveview/flip") }

    private suspend fun pumpUntil(timeoutMillis: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            main.scheduler.runCurrent()
            if (condition()) return true
            delay(10)
        }
        return condition()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
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
