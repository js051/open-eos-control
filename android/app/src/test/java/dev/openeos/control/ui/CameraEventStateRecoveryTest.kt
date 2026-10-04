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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One consumed event, real production VM/repository/HTTP, and independent failed reads. */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraEventStateRecoveryTest {
    private val main = StandardTestDispatcher()
    private val camera = EventRecoveryCamera("A")
    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val repository = CameraRepository(CameraBackendFactory(
        httpTransportFactory = CameraHttpTransportFactory {
            CameraHttpTransport(http, CameraNetworkDiagnostics.Empty)
        },
    ))
    private lateinit var viewModel: CameraViewModel
    private var replacement: EventRecoveryCamera? = null

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(main)
        camera.server.start()
        viewModel = CameraViewModel(repository)
        connect(camera)
    }

    @After fun tearDown() = runBlocking {
        try {
            if (::viewModel.isInitialized) {
                viewModel.disconnect()
                http.dispatcher.cancelAll()
                assertTrue(pumpUntil { http.dispatcher.runningCallsCount() == 0 })
                viewModel.viewModelScope.cancel()
                main.scheduler.runCurrent()
            }
        } finally {
            try {
                camera.server.shutdown()
                replacement?.server?.shutdown()
            } finally {
                http.connectionPool.evictAll()
                Dispatchers.resetMain()
            }
        }
    }

    @Test fun singleEventRetriesFailedStatusWithoutAnotherEventOrCameraWrite() = runBlocking {
        assertSingleFailedReadRecovers("/ccapi/status")
    }

    @Test fun singleEventRetriesFailedCapabilitiesWithoutPublishingAPartialSnapshot() = runBlocking {
        assertSingleFailedReadRecovers("/ccapi/capabilities")
    }

    @Test fun recoveryRetainsContentsAndPublishesControlStateBeforeAnUnresponsiveListing() = runBlocking {
        camera.failedPath.set("/ccapi/status")
        camera.failuresRemaining.set(1)
        camera.blockMedia.set(true)
        val previousMediaReads = camera.mediaReads.get()
        camera.recording.set(true)
        camera.enqueueEvent("recbutton", "contents")
        assertTrue(pumpUntil(advanceTime = true) {
            viewModel.uiState.value.status?.recording == true && camera.mediaReads.get() > previousMediaReads
        })
        assertTrue(viewModel.uiState.value.mediaLibraryLoading)
        assertEquals(1, camera.deliveredEvents.get())
        assertTrue(camera.writes.isEmpty())

        // A user cancellation still owns this media child; recovery must not relist it later.
        viewModel.cancelMediaLibraryLoad()
        assertTrue(pumpUntil(advanceTime = true) {
            !viewModel.uiState.value.mediaLibraryLoading && camera.emptyEvents.get() >= 3 &&
                http.dispatcher.runningCalls().none { it.request().url.encodedPath == "/ccapi/media" }
        })
        assertEquals(previousMediaReads + 1, camera.mediaReads.get())
        assertEquals(MediaLibraryLoadStatus.CANCELLED, viewModel.uiState.value.mediaLibraryLoadStatus)
        assertEquals(true, viewModel.uiState.value.status?.recording)
        assertNull(viewModel.uiState.value.error)
    }

    @Test fun repeatedReadFailuresUseIncreasingCappedBackoffWithoutConsumingMoreEvents() = runBlocking {
        camera.failedPath.set("/ccapi/status")
        camera.failuresRemaining.set(4)
        camera.recording.set(true)
        camera.enqueueEvent("recbutton")
        assertTrue(pumpUntil { camera.failedReads.get() == 1 && http.dispatcher.runningCallsCount() == 0 })
        // Complete the failed read's Main continuation before measuring its scheduled delay.
        settleMain()
        val polls = camera.polls.get()
        val reads = camera.statusReads.get()
        for ((index, delayMillis) in listOf(1_000L, 2_000L, 5_000L, 5_000L).withIndex()) {
            main.scheduler.advanceTimeBy(delayMillis - 1)
            main.scheduler.runCurrent()
            settleMain()
            assertEquals("No early retry before backoff $delayMillis", reads + index, camera.statusReads.get())
            assertEquals("A failed snapshot still owns its already-consumed event", polls, camera.polls.get())
            main.scheduler.advanceTimeBy(1)
            main.scheduler.runCurrent()
            assertTrue(pumpUntil {
                camera.statusReads.get() == reads + index + 1 &&
                    (index == 3 || http.dispatcher.runningCallsCount() == 0)
            })
            settleMain()
        }
        assertTrue(pumpUntil { viewModel.uiState.value.status?.recording == true })
        assertEquals(4, camera.failedReads.get())
        assertEquals(1, camera.deliveredEvents.get())
        assertTrue(camera.writes.isEmpty())
    }

    @Test fun disconnectDuringRecoveryDoesNotReplayTheOldHintIntoAReplacementSession() = runBlocking {
        camera.failedPath.set("/ccapi/status")
        camera.failuresRemaining.set(Int.MAX_VALUE)
        camera.recording.set(true)
        camera.enqueueEvent("recbutton", "contents")
        assertTrue(pumpUntil { camera.failedReads.get() == 1 && http.dispatcher.runningCallsCount() == 0 })
        settleMain()
        val oldReads = camera.statusReads.get()
        viewModel.disconnect()
        main.scheduler.runCurrent()
        val next = EventRecoveryCamera("B").also { replacement = it; it.server.start() }
        connect(next)
        val nextStatusReads = next.statusReads.get()
        val nextMediaReads = next.mediaReads.get()
        val emptyEvents = next.emptyEvents.get()
        main.scheduler.advanceTimeBy(10_000)
        assertTrue(pumpUntil { next.emptyEvents.get() >= emptyEvents + 3 })
        assertEquals(oldReads, camera.statusReads.get())
        assertEquals(nextStatusReads, next.statusReads.get())
        assertEquals(nextMediaReads, next.mediaReads.get())
        assertEquals("Synthetic event recovery B", viewModel.uiState.value.info?.model)
        assertEquals(false, viewModel.uiState.value.status?.recording)
        assertTrue(viewModel.uiState.value.pendingOperations.isEmpty())
        assertNull(viewModel.uiState.value.error)
        assertTrue(camera.writes.isEmpty())
        assertTrue(next.writes.isEmpty())
    }

    private suspend fun assertSingleFailedReadRecovers(path: String) {
        camera.failedPath.set(path)
        camera.failuresRemaining.set(1)
        camera.recording.set(true)
        camera.iso.set("800")
        camera.isoOptions.set(listOf("100", "200", "800"))
        val previousEmptyEvents = camera.emptyEvents.get()
        camera.enqueueEvent("recbutton", "iso")
        assertTrue(pumpUntil { camera.failedReads.get() == 1 })
        settleMain()
        assertEquals("The failed snapshot must not be partly published", false, viewModel.uiState.value.status?.recording)
        assertEquals("100", viewModel.uiState.value.status?.exposure?.iso)
        assertEquals(listOf("100", "200"), viewModel.uiState.value.capabilities?.iso)

        assertTrue("One failed read must recover without needing a second event", pumpUntil(advanceTime = true) {
            viewModel.uiState.value.status?.recording == true && camera.emptyEvents.get() >= previousEmptyEvents + 3
        })
        assertEquals("800", viewModel.uiState.value.status?.exposure?.iso)
        assertEquals(listOf("100", "200", "800"), viewModel.uiState.value.capabilities?.iso)
        assertEquals(1, camera.failedReads.get())
        assertEquals(1, camera.deliveredEvents.get())
        assertTrue(camera.writes.isEmpty())
        assertNull(viewModel.uiState.value.error)
    }

    private suspend fun connect(peer: EventRecoveryCamera) {
        viewModel.useDevSimulatorPreset()
        viewModel.setBaseUrl(peer.server.url("/").newBuilder().host("127.0.0.1").build().toString())
        viewModel.setLiveViewAutoRefresh(false)
        viewModel.connect()
        val connected = pumpUntil(advanceTime = true) {
            val state = viewModel.uiState.value
            state.info?.model == "Synthetic event recovery ${peer.label}" && state.connected &&
                !state.busy && !state.captureReviewLoading && peer.polls.get() > 0
        }
        assertTrue("Synthetic connection failed: ${viewModel.uiState.value.error}; " +
            "pending=${viewModel.uiState.value.pendingOperations}; polls=${peer.polls.get()}", connected)
    }

    private suspend fun settleMain() {
        repeat(5) { main.scheduler.runCurrent(); delay(10) }
    }

    private suspend fun pumpUntil(
        timeoutMillis: Long = 3_000,
        advanceTime: Boolean = false,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            main.scheduler.runCurrent()
            if (condition()) return true
            if (advanceTime) main.scheduler.advanceTimeBy(100)
            delay(10)
        }
        main.scheduler.runCurrent()
        return condition()
    }
}

/** Synthetic protocol peer; no Android bitmap work, external service or physical camera. */
private class EventRecoveryCamera(val label: String) {
    val server = MockWebServer()
    val recording = AtomicBoolean(false)
    val iso = AtomicReference("100")
    val isoOptions = AtomicReference(listOf("100", "200"))
    val failedPath = AtomicReference("")
    val failuresRemaining = AtomicInteger()
    val failedReads = AtomicInteger()
    val statusReads = AtomicInteger()
    val mediaReads = AtomicInteger()
    val polls = AtomicInteger()
    val deliveredEvents = AtomicInteger()
    val emptyEvents = AtomicInteger()
    val blockMedia = AtomicBoolean(false)
    val writes = CopyOnWriteArrayList<String>()
    private val events = LinkedBlockingQueue<List<String>>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                if (request.method != "GET") writes += "${request.method} $path"
                if (path == "/ccapi/status") statusReads.incrementAndGet()
                if (path == "/ccapi/media") mediaReads.incrementAndGet()
                if (path == failedPath.get() && failuresRemaining.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
                    failedReads.incrementAndGet()
                    return MockResponse().setResponseCode(503).setBody("Synthetic transient read failure")
                }
                return when (path) {
                    "/ccapi/info" -> json(JSONObject().put("connected", true)
                        .put("model", "Synthetic event recovery $label").put("serial", "TEST-EVENT-$label").put("api", "simulator"))
                    "/ccapi/status" -> json(JSONObject().put("connected", true).put("recording", recording.get())
                        .put("battery", JSONObject().put("level", 87).put("status", "normal"))
                        .put("media", JSONObject().put("available", true).put("remaining_minutes", 42))
                        .put("mode", "movie").put("exposure", JSONObject().put("iso", iso.get())
                            .put("shutter", "1/125").put("aperture", "4.0").put("white_balance", "auto")))
                    "/ccapi/capabilities" -> json(JSONObject().put("iso", JSONArray(isoOptions.get()))
                        .put("shutter", JSONArray(listOf("1/125"))).put("aperture", JSONArray(listOf("4.0")))
                        .put("white_balance", JSONArray(listOf("auto"))))
                    "/ccapi/events" -> {
                        polls.incrementAndGet()
                        val keys = events.poll(100, TimeUnit.MILLISECONDS).orEmpty()
                        if (keys.isEmpty()) emptyEvents.incrementAndGet() else deliveredEvents.incrementAndGet()
                        json(JSONObject().put("sequence", deliveredEvents.get()).put("keys", JSONArray(keys)))
                    }
                    "/ccapi/media" -> if (blockMedia.get()) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        else json(JSONObject().put("items", JSONArray()))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    fun enqueueEvent(vararg keys: String) { events.put(keys.toList()) }
    private fun json(body: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
}
