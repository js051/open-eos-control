package dev.openeos.control.ui

import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraBackendFactory
import dev.openeos.control.data.CameraHttpTransport
import dev.openeos.control.data.CameraHttpTransportFactory
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real VM/repository/HTTP; virtual Main and a gated list make cancellation order deterministic. */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraCapturePreviewListingTest {
    private val main = StandardTestDispatcher()
    private val peer = PreviewListingPeer()
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private val repository = CameraRepository(CameraBackendFactory(
        httpTransportFactory = CameraHttpTransportFactory { CameraHttpTransport(http, CameraNetworkDiagnostics.Empty) },
    ))
    private lateinit var viewModel: CameraViewModel

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(main)
        withContext(Dispatchers.IO) { peer.start() }
        viewModel = CameraViewModel(repository)
        connect()
    }

    @After fun tearDown() = runBlocking {
        peer.releaseGates()
        try {
            viewModel.disconnect()
            http.dispatcher.cancelAll()
            val scope = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
            viewModel.viewModelScope.cancel()
            assertTrue("ViewModel children must finish before resetting Main", pumpUntil { scope.isCompleted })
        } finally {
            withContext(Dispatchers.IO) { peer.server.shutdown() }
            http.connectionPool.evictAll()
            Dispatchers.resetMain()
        }
    }

    @Test fun firstCapturePreviewKeepsItsListingAndCanNavigateToTheNextItem() = runBlocking {
        capture()
        assertTrue(viewModel.uiState.value.mediaItems.isEmpty())
        openCaptureWhileListIsGated()
        assertEquals(listOf("new", "sibling", "old"), viewModel.uiState.value.mediaItems.map { it.id })
        assertEquals("new", viewModel.uiState.value.mediaPreviewItem?.id)
        assertArrayEquals(peer.displayBytes, viewModel.uiState.value.mediaPreviewBytes)

        viewModel.previewAdjacentMedia(viewModel.uiState.value.mediaItems, 1)
        assertTrue(pumpUntil { viewModel.uiState.value.mediaPreviewItem?.id == "sibling" && !viewModel.uiState.value.busy })
        assertArrayEquals(peer.displayBytes, viewModel.uiState.value.mediaPreviewBytes)
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun populatedAllAlbumRefreshesWithoutDelayingTheKnownCapturePreview() = runBlocking {
        viewModel.setMediaLibraryScope(MediaLibraryScope.ALL)
        viewModel.setUiMode(UiMode.MEDIA)
        assertTrue(pumpUntil { viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE })
        assertEquals(listOf("old"), viewModel.uiState.value.mediaItems.map { it.id })
        viewModel.setUiMode(UiMode.CONTROL)
        capture()
        assertEquals(listOf("old"), viewModel.uiState.value.mediaItems.map { it.id })

        openCaptureWhileListIsGated()
        assertEquals(MediaLibraryScope.ALL, viewModel.uiState.value.mediaLibraryScope)
        assertTrue(viewModel.uiState.value.mediaItems.any { it.id == "new" })
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun nonPreviewMediaOperationStillCancelsTheRunningListing() = runBlocking {
        val item = requireNotNull(viewModel.uiState.value.captureReviewItem)
        val gate = peer.gateNextListing()
        viewModel.setUiMode(UiMode.MEDIA)
        assertTrue(pumpUntil { gate.entered.count == 0L })
        viewModel.loadMediaInfo(item)
        assertFalse(viewModel.uiState.value.mediaLibraryLoading)
        assertEquals(MediaLibraryLoadStatus.CANCELLED, viewModel.uiState.value.mediaLibraryLoadStatus)
        assertTrue(pumpUntil { peer.infoReads.get() == 1 && !viewModel.uiState.value.busy })
        gate.release.countDown()
        assertEquals(0, peer.previewReads.size)
        assertEquals(0, peer.captureWrites.get())
    }

    @Test fun disconnectDuringListingDoesNotReopenTheCompletedPreviewInANewSession() = runBlocking {
        capture()
        val gate = peer.gateNextListing()
        viewModel.openCaptureReview()
        assertTrue(pumpUntil { gate.entered.count == 0L && viewModel.uiState.value.mediaPreviewBytes != null && !viewModel.uiState.value.busy })
        assertEquals(1, peer.previewReads.size)
        viewModel.disconnect()
        gate.release.countDown()
        assertTrue(pumpUntil { !viewModel.uiState.value.connected && !viewModel.uiState.value.busy })
        assertNull(viewModel.uiState.value.mediaPreviewItem)
        connect()
        assertNull(viewModel.uiState.value.mediaPreviewItem)
        assertEquals(1, peer.previewReads.size)
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun closeDuringListingDoesNotReopenThePreviewWhenTheAlbumCompletes() = runBlocking {
        capture()
        val gate = peer.gateNextListing()
        viewModel.openCaptureReview()
        assertTrue(pumpUntil { gate.entered.count == 0L && viewModel.uiState.value.mediaPreviewBytes != null && !viewModel.uiState.value.busy })
        viewModel.closeMediaPreview()
        assertNull(viewModel.uiState.value.mediaPreviewItem)
        gate.release.countDown()
        assertTrue(pumpUntil { viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE })
        assertNull(viewModel.uiState.value.mediaPreviewItem)
        assertNull(viewModel.uiState.value.mediaPreviewBytes)
        assertEquals(1, peer.previewReads.size)
    }

    @Test fun explicitListingCancelKeepsTheAlreadyLoadedPreviewWithoutAutomaticRelisting() = runBlocking {
        capture()
        val gate = peer.gateNextListing()
        viewModel.openCaptureReview()
        assertTrue(pumpUntil { gate.entered.count == 0L && viewModel.uiState.value.mediaPreviewBytes != null && !viewModel.uiState.value.busy })
        val reads = peer.listReads.get()
        viewModel.cancelMediaLibraryLoad()
        gate.release.countDown()
        assertTrue(pumpUntil { http.dispatcher.runningCallsCount() == 0 })
        assertEquals(MediaLibraryLoadStatus.CANCELLED, viewModel.uiState.value.mediaLibraryLoadStatus)
        assertFalse(viewModel.uiState.value.mediaLibraryLoading)
        assertEquals("new", viewModel.uiState.value.mediaPreviewItem?.id)
        assertArrayEquals(peer.displayBytes, viewModel.uiState.value.mediaPreviewBytes)
        assertEquals(reads, peer.listReads.get())
    }

    @Test fun recentSnapshotMissingAKnownItemIsNotEvidenceToCloseItsPreview() = runBlocking {
        capture()
        peer.omitCaptureFromList.set(true)
        val gate = peer.gateNextListing()
        viewModel.openCaptureReview()
        assertTrue(pumpUntil { gate.entered.count == 0L && viewModel.uiState.value.mediaPreviewBytes != null && !viewModel.uiState.value.busy })
        gate.release.countDown()
        assertTrue(pumpUntil { viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE })
        val state = viewModel.uiState.value
        assertEquals(MediaLibraryScope.RECENT, state.mediaLibraryScope)
        assertEquals(listOf("old"), state.mediaItems.map { it.id })
        assertEquals("The user explicitly opened a known item; a bounded recent result must not dismiss it", "new", state.mediaPreviewItem?.id)
        assertArrayEquals(peer.displayBytes, state.mediaPreviewBytes)
        assertFalse(state.mediaPreviewLoading)
        assertEquals(1, peer.captureWrites.get())
    }

    @Test fun nativeAllPartialPageCannotDismissTheSeparatelyLoadedCapturePreview() = runBlocking {
        viewModel.disconnect()
        assertTrue(pumpUntil { !viewModel.uiState.value.connected && !viewModel.uiState.value.busy })
        connect(native = true)
        viewModel.setMediaLibraryScope(MediaLibraryScope.ALL)
        assertTrue(pumpUntil { viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE })
        val capturedId = "${PreviewListingPeer.CONTENTS}/new.JPG"
        capture(capturedId)
        val gate = peer.gateSecondNativePage()
        viewModel.openCaptureReview()
        assertTrue("The display must complete while the later card page remains blocked", pumpUntil {
            gate.entered.count == 0L && viewModel.uiState.value.mediaPreviewBytes != null && !viewModel.uiState.value.busy
        })
        val partial = viewModel.uiState.value
        assertTrue(partial.mediaLibraryLoading)
        assertEquals(listOf("${PreviewListingPeer.CONTENTS}/old.JPG"), partial.mediaItems.map { it.id })
        assertEquals(capturedId, partial.mediaPreviewItem?.id)
        assertArrayEquals(peer.displayBytes, partial.mediaPreviewBytes)
        gate.release.countDown()
        assertTrue(pumpUntil { viewModel.uiState.value.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE })
        assertTrue(viewModel.uiState.value.mediaItems.any { it.id == capturedId })
        assertEquals(capturedId, viewModel.uiState.value.mediaPreviewItem?.id)
        assertEquals(1, peer.captureWrites.get())
    }

    private suspend fun openCaptureWhileListIsGated() {
        val gate = peer.gateNextListing()
        viewModel.openCaptureReview()
        val immediate = viewModel.uiState.value
        assertEquals("Opening a preview must not synchronously cancel its album", MediaLibraryLoadStatus.LOADING, immediate.mediaLibraryLoadStatus)
        assertTrue(immediate.mediaLibraryLoading)
        assertEquals("new", immediate.mediaPreviewItem?.id)
        assertTrue(immediate.mediaPreviewLoading)
        assertTrue("A known capture preview must finish even while the album response is gated", pumpUntil {
            gate.entered.count == 0L && viewModel.uiState.value.mediaPreviewBytes != null && !viewModel.uiState.value.busy
        })
        assertTrue(viewModel.uiState.value.mediaLibraryLoading)
        assertEquals(MediaLibraryLoadStatus.LOADING, viewModel.uiState.value.mediaLibraryLoadStatus)
        assertEquals(1, peer.previewReads.size)
        assertArrayEquals(peer.displayBytes, viewModel.uiState.value.mediaPreviewBytes)
        gate.release.countDown()
        assertTrue("Album and preview should both complete: ${viewModel.uiState.value.error}", pumpUntil {
            val state = viewModel.uiState.value
            state.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE && !state.mediaLibraryLoading &&
                !state.busy && !state.mediaPreviewLoading && state.mediaPreviewBytes != null
        })
    }

    private suspend fun capture(expectedId: String = "new") {
        viewModel.captureStill()
        assertTrue(pumpUntil {
            val state = viewModel.uiState.value
            state.captureReviewItem?.id == expectedId && !state.captureReviewLoading && !state.busy
        })
        assertEquals(1, peer.captureWrites.get())
    }

    private suspend fun connect(native: Boolean = false) {
        if (native) viewModel.useDirectCameraPreset() else viewModel.useDevSimulatorPreset()
        viewModel.setBaseUrl(peer.baseUrl)
        viewModel.setLiveViewAutoRefresh(false)
        viewModel.connect()
        assertTrue(pumpUntil {
            val state = viewModel.uiState.value
            state.connected && !state.busy && state.captureReviewItem != null && !state.captureReviewLoading
        })
    }

    private suspend fun pumpUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            main.scheduler.runCurrent()
            if (condition()) return true
            main.scheduler.advanceTimeBy(100)
            delay(10)
        }
        main.scheduler.runCurrent()
        return condition()
    }
}

private class PreviewListingPeer {
    val server = MockWebServer()
    lateinit var baseUrl: String
    val displayBytes = byteArrayOf(1, 2, 3, 4) // Preview state reads bytes; image decoding is instrumented separately.
    val captureWrites = AtomicInteger()
    val omitCaptureFromList = AtomicBoolean(false)
    val infoReads = AtomicInteger()
    val listReads = AtomicInteger()
    val previewReads = CopyOnWriteArrayList<String>()
    class Gate(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))
    private val gates = CopyOnWriteArrayList<Gate>()
    private val nextListing = AtomicReference<Gate?>()
    private val secondNativePage = AtomicReference<Gate?>()
    @Volatile private var nativePartialAlbum = false
    fun gateSecondNativePage() = Gate().also { gates += it; secondNativePage.set(it); nativePartialAlbum = true }
    fun gateNextListing() = Gate().also { gates += it; nextListing.set(it) }
    fun releaseGates() = gates.forEach { it.release.countDown() }

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = requireNotNull(request.requestUrl)
                val path = url.encodedPath
                return when {
                    path == "/ccapi" -> json("""{"ver100":[{"path":"/deviceinformation","get":true},{"path":"/devicestatus/battery","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/control/shutterbutton","post":true},{"path":"/contents","get":true}]}""")
                    path.endsWith("/deviceinformation") -> json("""{"productname":"Synthetic preview listing","serialnumber":"TEST-PREVIEW-LISTING"}""")
                    path.endsWith("/devicestatus/battery") -> json("""{"level":"90"}""")
                    path.endsWith("/shooting/settings") -> json("{}")
                    path == CONTENTS && url.queryParameter("kind") == "number" ->
                        json("""{"pagenumber":${if (nativePartialAlbum) 2 else 1}}""")
                    path == CONTENTS && url.queryParameter("page") != null -> {
                        val ids = if (nativePartialAlbum) {
                            if (url.queryParameter("page") == "1") listOf("old") else {
                                secondNativePage.getAndSet(null)?.let { gate ->
                                    gate.entered.countDown()
                                    check(gate.release.await(5, TimeUnit.SECONDS)) { "Second native page was not released" }
                                }
                                listOf("new", "sibling")
                            }
                        } else if (captureWrites.get() > 0) listOf("new", "sibling", "old") else listOf("old")
                        json(JSONObject().put("path", JSONArray(ids.map { "$CONTENTS/$it.JPG" })).toString())
                    }
                    path == "/ccapi/info" -> json("""{"connected":true,"model":"Synthetic preview listing","serial":"TEST-PREVIEW-LISTING","api":"simulator"}""")
                    path == "/ccapi/status" -> json("""{"connected":true,"recording":false,"mode":"photo","battery":{},"media":{},"exposure":{}}""")
                    path == "/ccapi/capabilities" -> json("""{"iso":["100"],"shutter":["1/125"],"aperture":["4.0"],"white_balance":["auto"]}""")
                    path == "/ccapi/events" -> json("""{"sequence":0,"keys":[]}""")
                    (path == "/ccapi/capture/still" || path.endsWith("/shutterbutton")) && request.method == "POST" -> {
                        captureWrites.incrementAndGet()
                        json("{}")
                    }
                    path == "/ccapi/media" -> {
                        listReads.incrementAndGet()
                        val ids = if (captureWrites.get() > 0 && !omitCaptureFromList.get()) listOf("new", "sibling", "old") else listOf("old")
                        nextListing.getAndSet(null)?.let { gate ->
                            gate.entered.countDown()
                            check(gate.release.await(5, TimeUnit.SECONDS)) { "Synthetic listing gate was not released" }
                        }
                        json(JSONObject().put("items", JSONArray(ids.map(::item))).toString())
                    }
                    (path.startsWith("/ccapi/media/") || path.startsWith("$CONTENTS/")) && url.queryParameter("kind") == "info" -> {
                        infoReads.incrementAndGet()
                        val id = url.pathSegments.last().removeSuffix(".JPG")
                        json(item(id).put("lastmodifieddate", if (id == "new") "2026-09-01T00:00:03Z" else "2026-09-01T00:00:01Z").toString())
                    }
                    (path.startsWith("/ccapi/media/") || path.startsWith("$CONTENTS/")) && url.queryParameter("kind") == "display" -> {
                        previewReads += path
                        MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(displayBytes))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    }

    private fun item(id: String) = JSONObject().put("id", id).put("name", "$id.JPG").put("kind", "image")
        .put("capture_time", if (id == "new") "2026-09-01T00:00:03Z" else "2026-09-01T00:00:01Z")
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    companion object { const val CONTENTS = "/ccapi/ver100/contents" }
}
