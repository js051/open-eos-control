package dev.openeos.control.ui

import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraBackendFactory
import dev.openeos.control.data.CameraControlBackend
import dev.openeos.control.data.CameraHttpTransport
import dev.openeos.control.data.CameraHttpTransportFactory
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaPreview
import dev.openeos.control.data.CameraMediaStreamHandle
import dev.openeos.control.data.CameraMediaStreamSource
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Real HTTP connections and preview cancellation; deferred backend reads isolate cleanup races. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class CameraMediaPreviewOwnershipTest(private val transport: CameraTransport) {
    private val main = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(main) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun closingHttpPreviewCancelsItsCallAndTheSameItemCanBeOpenedAgain() = runTest(main) {
        withCamera {
            val entered = peer.stallNextPreview()
            model.openMediaPreview(image)
            entered.await()
            val call = peer.previewCalls.single()
            assertTrue(model.uiState.value.isBusy(CameraOperation.MEDIA))

            model.closeMediaPreview()
            awaitMediaIdle()

            assertTrue("Dismissal must cancel the actual HTTP preview call", call.isCanceled())
            assertClosedWithoutError()
            model.openMediaPreview(image.copy())
            awaitMediaIdle()
            assertEquals(image.id, model.uiState.value.mediaPreviewItem?.id)
            assertArrayEquals(peer.displayBytes, model.uiState.value.mediaPreviewBytes)
            assertEquals(2, peer.previewCalls.size)
        }
    }

    @Test fun leavingMediaCancelsTheRunningHttpPreviewCall() = runTest(main) {
        withCamera {
            model.setUiMode(UiMode.MEDIA)
            model.uiState.first { !it.mediaLibraryLoading }
            val entered = peer.stallNextPreview()
            model.openMediaPreview(image)
            entered.await()
            val call = peer.previewCalls.single()
            assertTrue(model.uiState.value.isBusy(CameraOperation.MEDIA))

            model.setUiMode(UiMode.CONTROL)
            awaitMediaIdle()

            assertTrue("Leaving MEDIA must cancel the actual pending preview call", call.isCanceled())
            assertEquals(UiMode.CONTROL, model.uiState.value.uiMode)
            assertClosedWithoutError()
            assertEquals(1, peer.previewCalls.size)
        }
    }

    @Test fun closingBeforeMainRunsDoesNotStrandMediaOrCancelTheNextPreview() = runTest(main) {
        withCamera {
            val read = readGate()
            replaceBackend { delegate -> object : CameraControlBackend by delegate {
                override suspend fun mediaPreview(item: CameraMediaItem) = read.read(preview(item))
            } }

            model.openMediaPreview(image)
            assertTrue(model.uiState.value.isBusy(CameraOperation.MEDIA))
            model.closeMediaPreview()
            runCurrent()

            assertFalse("A cancelled LAZY job may never enter its body's finally", model.uiState.value.isBusy(CameraOperation.MEDIA))
            assertFalse("Dismissal before Main runs must not start a camera read", read.entered.isCompleted)
            assertClosedWithoutError()

            model.openMediaPreview(image.copy())
            runCurrent()
            assertTrue(read.entered.isCompleted)
            assertTrue(read.job.isActive)
            assertTrue(model.uiState.value.isBusy(CameraOperation.MEDIA))
            read.finish.complete(Unit)
            read.job.join()
            assertArrayEquals(peer.displayBytes, model.uiState.value.mediaPreviewBytes)
            assertFalse(model.uiState.value.isBusy(CameraOperation.MEDIA))
        }
    }

    @Test fun closingOrLeavingDuringViewerPublicationPreventsReadAdmission() = runTest(main) {
        assertReentrantPreviewDismissal(whenMediaPending = false)
    }

    @Test fun closingOrLeavingDuringMediaPendingPublicationPreventsReadAdmission() = runTest(main) {
        assertReentrantPreviewDismissal(whenMediaPending = true)
    }

    @Test fun cancelledPreviewKeepsMediaBusyUntilItsCleanupCompletes() = runTest(main) {
        withCamera {
            val read = readGate(holdCleanup = true)
            replaceBackend { delegate -> object : CameraControlBackend by delegate {
                override suspend fun mediaPreview(item: CameraMediaItem) = read.read(preview(item))
            } }
            model.openMediaPreview(image)
            runCurrent()
            assertTrue(read.entered.isCompleted)

            model.closeMediaPreview()
            runCurrent()
            assertTrue(read.cancelled.isCompleted)
            assertTrue(read.job.isCancelled)
            assertFalse(read.job.isCompleted)
            assertTrue("Cancellation is not evidence that the camera read has drained", model.uiState.value.isBusy(CameraOperation.MEDIA))
            assertClosedWithoutError()

            model.openMediaPreview(image.copy())
            assertNull("A reused item ID cannot bypass the pending read", model.uiState.value.mediaPreviewItem)
            read.cleanup.complete(Unit)
            read.job.join()
            assertFalse(model.uiState.value.isBusy(CameraOperation.MEDIA))
            assertClosedWithoutError()
        }
    }

    @Test fun lateNoncooperativeFailureCannotPublishAnErrorOrAffectAReusedItem() = runTest(main) {
        withCamera {
            val old = readGate(holdCleanup = true, lateFailure = IOException("synthetic cancelled preview"))
            val successor = readGate()
            var reads = 0
            replaceBackend { delegate -> object : CameraControlBackend by delegate {
                override suspend fun mediaPreview(item: CameraMediaItem): CameraMediaPreview {
                    val read = if (reads++ == 0) old else successor
                    return read.read(preview(item))
                }
            } }
            model.openMediaPreview(image)
            runCurrent()
            model.closeMediaPreview()
            runCurrent()
            assertTrue(old.cancelled.isCompleted)
            old.cleanup.complete(Unit)
            old.job.join()
            assertClosedWithoutError()

            model.openMediaPreview(image.copy())
            runCurrent()
            assertEquals(2, reads)
            assertTrue(successor.job.isActive)
            assertTrue(model.uiState.value.mediaPreviewLoading)
            assertTrue(model.uiState.value.isBusy(CameraOperation.MEDIA))
            assertNull(model.uiState.value.error)
            successor.finish.complete(Unit)
            successor.job.join()
            assertArrayEquals(peer.displayBytes, model.uiState.value.mediaPreviewBytes)
            assertNull(model.uiState.value.error)
        }
    }

    @Test fun disconnectAndReconnectWaitForCancelledPreviewAndKeepTheirOwnOwner() = runTest(main) {
        withCamera {
            val connection = model.uiState.value.info
            val read = readGate(holdCleanup = true, lateFailure = IOException("synthetic obsolete connection"))
            replaceBackend { delegate -> object : CameraControlBackend by delegate {
                override suspend fun mediaPreview(item: CameraMediaItem) = read.read(preview(item))
            } }
            model.openMediaPreview(image)
            runCurrent()
            model.disconnect()
            beginConnect()
            runCurrent()

            assertTrue(read.cancelled.isCompleted)
            assertFalse(read.job.isCompleted)
            assertTrue(model.uiState.value.isBusy(CameraOperation.CONNECT))
            model.closeMediaPreview()
            runCurrent()
            assertTrue("Closing the obsolete viewer cannot cancel reconnect", model.uiState.value.isBusy(CameraOperation.CONNECT))
            read.cleanup.complete(Unit)
            awaitConnected()

            assertNotSame(connection, model.uiState.value.info)
            assertClosedWithoutError()
            model.openMediaPreview(image.copy())
            awaitMediaIdle()
            assertArrayEquals(peer.displayBytes, model.uiState.value.mediaPreviewBytes)
            assertEquals(image.id, model.uiState.value.mediaPreviewItem?.id)
            assertNull(model.uiState.value.error)
        }
    }

    @Test fun retiringPreviewCannotClearAMetadataSuccessorStartedByTheIdleState() = runTest(main) {
        withCamera {
            val old = readGate(holdCleanup = true)
            val metadata = readGate()
            replaceBackend { delegate -> object : CameraControlBackend by delegate {
                override suspend fun mediaPreview(item: CameraMediaItem) = old.read(preview(item))
                override suspend fun mediaInfo(item: CameraMediaItem) = metadata.read(item)
            } }
            model.openMediaPreview(image)
            runCurrent()
            model.closeMediaPreview()
            runCurrent()
            assertTrue(old.cancelled.isCompleted)
            var startSuccessor = true
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.uiState.collect { state ->
                    if (startSuccessor && !state.isBusy(CameraOperation.MEDIA)) {
                        startSuccessor = false
                        model.loadMediaInfo(image)
                    }
                }
            }
            try {
                old.cleanup.complete(Unit)
                old.job.join()
                runCurrent()

                assertFalse(startSuccessor)
                assertTrue(metadata.entered.isCompleted)
                assertTrue(metadata.job.isActive)
                assertTrue("Old completion must not retire a MEDIA job admitted by its idle update", model.uiState.value.isBusy(CameraOperation.MEDIA))
                model.closeMediaPreview()
                runCurrent()
                assertTrue(metadata.job.isActive)
                metadata.finish.complete(Unit)
                metadata.job.join()
                assertFalse(model.uiState.value.isBusy(CameraOperation.MEDIA))
                assertClosedWithoutError()
            } finally {
                observer.cancel()
            }
        }
    }

    @Test fun anActivePreviewFailureStillReportsItsMediaError() = runTest(main) {
        withCamera {
            val read = readGate()
            replaceBackend { delegate -> object : CameraControlBackend by delegate {
                override suspend fun mediaPreview(item: CameraMediaItem): CameraMediaPreview {
                    read.read(Unit)
                    throw IOException("synthetic active preview failure")
                }
            } }
            model.openMediaPreview(image)
            runCurrent()
            read.finish.complete(Unit)
            read.job.join()

            assertEquals(image.id, model.uiState.value.mediaPreviewItem?.id)
            assertFalse(model.uiState.value.mediaPreviewLoading)
            assertFalse(model.uiState.value.isBusy(CameraOperation.MEDIA))
            assertEquals(CameraOperation.MEDIA, model.uiState.value.errorOperation)
            assertTrue(model.uiState.value.error.orEmpty().contains("synthetic active preview failure"))
        }
    }

    @Test fun closingACompletedPreviewDoesNotCancelNewMetadataWork() = runTest(main) {
        withCamera {
            model.openMediaPreview(image)
            awaitMediaIdle()
            val metadata = readGate()
            replaceBackend { delegate -> object : CameraControlBackend by delegate {
                override suspend fun mediaInfo(item: CameraMediaItem) = metadata.read(item.copy(rating = 3))
            } }
            model.loadMediaInfo(image)
            runCurrent()
            assertTrue(metadata.entered.isCompleted)

            model.closeMediaPreview()
            runCurrent()

            assertTrue("The current MEDIA owner is metadata, not the completed viewer", metadata.job.isActive)
            assertFalse(metadata.cancelled.isCompleted)
            assertTrue(model.uiState.value.isBusy(CameraOperation.MEDIA))
            metadata.finish.complete(Unit)
            metadata.job.join()
            assertFalse(model.uiState.value.isBusy(CameraOperation.MEDIA))
            assertClosedWithoutError()
        }
    }

    @Test fun closingVideoDuringAllocationLetsTheSourceReturnAndClosesIt() = runTest(main) {
        withCamera {
            val allocation = readGate()
            val video = image.copy(id = "synthetic-video", name = "SYNTHETIC.MP4", kind = "video", streamAvailable = true)
            val stream = PreviewStreamProbe(video)
            replaceBackend { delegate -> object : CameraControlBackend by delegate {
                override suspend fun openMediaStream(item: CameraMediaItem) = allocation.read(stream)
            } }
            model.openMediaPreview(video)
            runCurrent()
            assertTrue(allocation.entered.isCompleted)
            model.closeMediaPreview()
            runCurrent()

            assertTrue("Video allocation has no safe ticket-cancellation contract", allocation.job.isActive)
            assertFalse(allocation.cancelled.isCompleted)
            assertTrue(model.uiState.value.isBusy(CameraOperation.MEDIA))
            allocation.finish.complete(Unit)
            allocation.job.join()
            assertEquals(1, stream.closeCalls)
            assertNull(model.uiState.value.mediaStreamSource)
            assertFalse(model.uiState.value.isBusy(CameraOperation.MEDIA))
            assertClosedWithoutError()
        }
    }

    @Test fun usbImageDismissalPreservesTheExistingNoncancellingPolicy() = runTest(main) {
        withCamera {
            val read = readGate()
            // This control tests only the ViewModel's transport policy, not a USB connection.
            // Real HTTP connection setup above remains unchanged; no PTP I/O is simulated.
            model.overrideTransportForPolicyControl(CameraTransport.USB_PTP)
            replaceBackend { delegate -> object : CameraControlBackend by delegate {
                override val transport = CameraTransport.USB_PTP
                override suspend fun mediaPreview(item: CameraMediaItem) = read.read(preview(item))
            } }
            model.openMediaPreview(image)
            runCurrent()
            assertTrue(read.entered.isCompleted)
            model.closeMediaPreview()
            runCurrent()

            assertTrue("Closing a viewer must not interrupt a PTP transaction", read.job.isActive)
            assertFalse(read.cancelled.isCompleted)
            assertTrue(model.uiState.value.isBusy(CameraOperation.MEDIA))
            read.finish.complete(Unit)
            read.job.join()
            assertFalse(model.uiState.value.isBusy(CameraOperation.MEDIA))
            assertClosedWithoutError()
        }
    }

    private suspend fun TestScope.assertReentrantPreviewDismissal(whenMediaPending: Boolean) {
        withCamera {
            for (leaveMedia in listOf(false, true)) {
                model.setUiMode(UiMode.MEDIA)
                model.uiState.first { !it.mediaLibraryLoading }
                val read = readGate()
                replaceBackend { delegate -> object : CameraControlBackend by delegate {
                    override suspend fun mediaPreview(item: CameraMediaItem) = read.read(preview(item))
                } }
                var dismissOnPublication = true
                val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    model.uiState.collect { state ->
                        if (dismissOnPublication && state.mediaPreviewItem?.id == image.id &&
                            state.mediaPreviewLoading && state.isBusy(CameraOperation.MEDIA) == whenMediaPending) {
                            dismissOnPublication = false
                            if (leaveMedia) model.setUiMode(UiMode.CONTROL) else model.closeMediaPreview()
                        }
                    }
                }
                try {
                    model.openMediaPreview(image)

                    assertFalse("The observer must dismiss inside the selected state publication", dismissOnPublication)
                    assertClosedWithoutError()
                    runCurrent()
                    assertFalse("A viewer revoked before job registration cannot start its camera read", read.entered.isCompleted)
                    assertFalse("Pre-registration dismissal must retire any admitted MEDIA owner", model.uiState.value.isBusy(CameraOperation.MEDIA))
                    assertEquals(if (leaveMedia) UiMode.CONTROL else UiMode.MEDIA, model.uiState.value.uiMode)

                    // Reuse the same item and session to prove that deferred cancellation cannot
                    // strand admission or target a job created after the revoked request.
                    model.setUiMode(UiMode.MEDIA)
                    model.uiState.first { !it.mediaLibraryLoading }
                    model.openMediaPreview(image.copy())
                    runCurrent()
                    assertTrue(read.entered.isCompleted)
                    assertTrue(read.job.isActive)
                    assertTrue(model.uiState.value.isBusy(CameraOperation.MEDIA))
                    read.finish.complete(Unit)
                    read.job.join()
                    assertArrayEquals(peer.displayBytes, model.uiState.value.mediaPreviewBytes)
                    assertFalse(model.uiState.value.isBusy(CameraOperation.MEDIA))
                    model.closeMediaPreview()
                    assertClosedWithoutError()
                } finally {
                    observer.cancel()
                }
            }
        }
    }

    private suspend fun TestScope.withCamera(block: suspend PreviewOwnershipFixture.() -> Unit) {
        val peer = PreviewOwnershipPeer()
        withContext(Dispatchers.IO) { peer.start() }
        val fixture = PreviewOwnershipFixture(peer, transport)
        try {
            fixture.model.setLiveViewAutoRefresh(false)
            fixture.beginConnect()
            fixture.awaitConnected()
            assertEquals(transport, fixture.model.uiState.value.transport)
            fixture.block()
        } finally {
            fixture.gates.forEach { it.finish.complete(Unit); it.cleanup.complete(Unit) }
            fixture.model.disconnect()
            runCurrent()
            val owner = fixture.model.viewModelScope.coroutineContext.job
            fixture.model.viewModelScope.cancel()
            owner.join()
            // disconnect owns detached cleanup; join the real repository connection mutex too.
            fixture.repository.disconnect()
            runCurrent()
            withContext(Dispatchers.IO) { peer.server.shutdown() }
            peer.http.connectionPool.evictAll()
            peer.http.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun transports(): List<Array<CameraTransport>> = listOf(
            arrayOf(CameraTransport.CCAPI_NETWORK), arrayOf(CameraTransport.DESKTOP_BRIDGE),
        )
    }
}

private class PreviewOwnershipFixture(val peer: PreviewOwnershipPeer, private val transport: CameraTransport) {
    val repository = CameraRepository(CameraBackendFactory(httpTransportFactory = CameraHttpTransportFactory {
        CameraHttpTransport(peer.http, CameraNetworkDiagnostics.Empty)
    }))
    val model = CameraViewModel(repository)
    val image = CameraMediaItem(
        id = if (transport == CameraTransport.CCAPI_NETWORK) "/ccapi/ver100/contents/SYNTHETIC.JPG" else "synthetic-image",
        name = "SYNTHETIC.JPG", kind = "image", previewAvailable = true,
    )
    val gates = mutableListOf<PreviewReadGate>()

    fun beginConnect() {
        if (transport == CameraTransport.CCAPI_NETWORK) {
            model.useDirectCameraPreset()
            model.setBaseUrl(peer.baseUrl)
            model.connect()
        } else {
            model.setConnectionTarget(ConnectionTarget.DESKTOP_BRIDGE)
            model.setBridgeBaseUrl(peer.baseUrl)
            model.connectBridge()
        }
    }

    suspend fun awaitConnected() {
        val state = model.uiState.first { !it.busy && (it.connected && !it.captureReviewLoading || it.error != null) }
        assertNull("Synthetic connection failed", state.error)
        assertTrue(state.connected)
    }

    suspend fun awaitMediaIdle() { model.uiState.first { !it.isBusy(CameraOperation.MEDIA) } }

    fun assertClosedWithoutError() {
        val state = model.uiState.value
        assertNull(state.mediaPreviewItem)
        assertNull(state.mediaPreviewBytes)
        assertFalse(state.mediaPreviewLoading)
        assertNull(state.error)
        assertNull(state.errorOperation)
    }

    fun preview(item: CameraMediaItem) = CameraMediaPreview(item, peer.displayBytes, "image/jpeg")

    fun readGate(holdCleanup: Boolean = false, lateFailure: IOException? = null) =
        PreviewReadGate(holdCleanup, lateFailure).also(gates::add)

    fun replaceBackend(wrap: (CameraControlBackend) -> CameraControlBackend) {
        // Only the single read is substituted; real initialized backend/session teardown is delegated.
        val field = CameraRepository::class.java.getDeclaredField("backend").apply { isAccessible = true }
        field.set(repository, wrap(field.get(repository) as CameraControlBackend))
    }
}

private class PreviewReadGate(private val holdCleanup: Boolean, private val lateFailure: IOException?) {
    val entered = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val cleanup = CompletableDeferred<Unit>()
    lateinit var job: Job
        private set

    suspend fun <T> read(value: T): T {
        job = currentCoroutineContext().job
        entered.complete(Unit)
        try {
            finish.await()
            return value
        } catch (failure: CancellationException) {
            cancelled.complete(Unit)
            if (holdCleanup) withContext(NonCancellable) { cleanup.await() }
            lateFailure?.let { throw it }
            throw failure
        }
    }
}

private class PreviewStreamProbe(override val item: CameraMediaItem) : CameraMediaStreamSource {
    var closeCalls = 0
        private set
    override suspend fun open(position: Long): CameraMediaStreamHandle = error("Playback is outside this allocation test")
    override fun close() { closeCalls += 1 }
}

private fun CameraViewModel.overrideTransportForPolicyControl(transport: CameraTransport) {
    val field = CameraViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val state = field.get(this) as MutableStateFlow<CameraUiState>
    state.value = state.value.copy(transport = transport)
}

private class PreviewOwnershipPeer {
    val server = MockWebServer()
    lateinit var baseUrl: String
    val displayBytes = byteArrayOf(1, 2, 3, 4)
    val previewCalls = CopyOnWriteArrayList<Call>()
    private val stalledPreview = AtomicReference<CompletableDeferred<Unit>?>()
    val http = OkHttpClient.Builder().retryOnConnectionFailure(false).eventListener(object : EventListener() {
        override fun callStart(call: Call) {
            val url = call.request().url
            if (url.queryParameter("kind") == "display" || url.encodedPath.endsWith("/preview")) previewCalls += call
        }
    }).build()

    fun stallNextPreview() = CompletableDeferred<Unit>().also { stalledPreview.set(it) }

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = requireNotNull(request.requestUrl)
                val path = url.encodedPath
                if (url.queryParameter("kind") == "display" || path.endsWith("/preview")) {
                    stalledPreview.getAndSet(null)?.let { entered ->
                        entered.complete(Unit)
                        return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(displayBytes))
                }
                return when {
                    path == "/ccapi" -> json("""{"ver100":[{"path":"/deviceinformation","get":true},{"path":"/devicestatus/battery","get":true},{"path":"/shooting/settings","get":true},{"path":"/contents","get":true}]}""")
                    path.endsWith("/deviceinformation") -> json("""{"productname":"Synthetic preview owner","serialnumber":"TEST-PREVIEW-OWNER"}""")
                    path.endsWith("/devicestatus/battery") -> json("""{"level":"90"}""")
                    path.endsWith("/shooting/settings") -> json("{}")
                    path == "/ccapi/ver100/contents" && url.queryParameter("kind") == "number" -> json("""{"pagenumber":1}""")
                    path == "/ccapi/ver100/contents" -> json("""{"path":[]}""")
                    path == "/health" -> json("""{"service":"open-eos-control-bridge"}""")
                    path == "/v1/session" && request.method == "POST" -> json("""{"id":"synthetic-session"}""")
                    path.endsWith("/info") -> json("""{"connected":true,"model":"Synthetic preview owner","serial":"TEST-PREVIEW-OWNER","api":"desktop-bridge/v1"}""")
                    path.endsWith("/status") -> json("""{"connected":true,"recording":false,"mode":"photo","battery":{},"media":{},"exposure":{}}""")
                    path.endsWith("/capabilities") -> json("""{"supported":["CAMERA_IDENTITY","DESKTOP_BRIDGE","MEDIA_BROWSER","MEDIA_PREVIEW"],"settings":[]}""")
                    path.endsWith("/media") -> json("""{"items":[]}""")
                    path == "/v1/session/synthetic-session" && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path.endsWith("/liveview/stop") -> json("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
