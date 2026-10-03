package dev.openeos.control.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the production single-document download entry point after a destination URI is chosen.
 * The app-owned FileProvider stands in for that URI; this does not validate SAF picker UI or grants.
 * All camera responses and original-file bytes are synthetic, not physical-camera evidence.
 */
class CameraGallerySessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private val repository = CameraRepository()
    private val viewModelStore = ViewModelStore()
    private lateinit var viewModel: CameraViewModel
    private lateinit var testDirectory: File
    private val blockNextSecondPage = AtomicBoolean(false)
    private val secondPageEntered = CountDownLatch(1)
    private val releaseSecondPage = CountDownLatch(1)
    private val secondPageRequests = AtomicInteger(0)
    private val blockNextDownload = AtomicBoolean(false)
    private val downloadEntered = CountDownLatch(1)
    private val releaseDownload = CountDownLatch(1)
    private val originalBytes = ByteArray(512) { ((it % 251) + 1).toByte() }
    private val mediaPaths = (62 downTo 1).map {
        "$CONTENTS/IMG_${it.toString().padStart(4, '0')}.CR3"
    }

    @Before
    fun setUp() {
        testDirectory = File(compose.activity.cacheDir, "camera-import/gallery-session-${UUID.randomUUID()}")
        check(testDirectory.mkdirs())
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        val thumbnail = try {
            ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                if (request.method != "GET") return MockResponse().setResponseCode(405)
                val url = requireNotNull(request.requestUrl)
                val path = url.encodedPath
                return when {
                    path == "/ccapi" -> json(DISCOVERY)
                    path == "$PREFIX/deviceinformation" ->
                        json("""{"productname":"Canon EOS Test Camera","serialnumber":"TEST-GALLERY-0001"}""")
                    path == "$PREFIX/devicestatus/battery" -> json("""{"level":"90"}""")
                    path == "$PREFIX/devicestatus/storage" -> json("{}")
                    path == "$PREFIX/shooting/settings" -> json("{}")
                    path == CONTENTS && url.queryParameter("kind") == "number" ->
                        json("""{"pagenumber":2}""")
                    path == CONTENTS && url.queryParameter("page") == "1" -> paths(mediaPaths.take(61))
                    path == CONTENTS && url.queryParameter("page") == "2" -> {
                        secondPageRequests.incrementAndGet()
                        if (blockNextSecondPage.compareAndSet(true, false)) {
                            secondPageEntered.countDown()
                            check(releaseSecondPage.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                "Timed out waiting to release the synthetic second page."
                            }
                        }
                        paths(mediaPaths.takeLast(1))
                    }
                    path in mediaPaths && url.queryParameter("kind") == "info" ->
                        json("""{"lastmodifieddate":"2026-09-01T00:00:00Z","filesize":${originalBytes.size}}""")
                    path in mediaPaths && url.queryParameter("kind") == "thumbnail" ->
                        binary(thumbnail, "image/jpeg")
                    path in mediaPaths && url.query == null -> {
                        if (blockNextDownload.compareAndSet(true, false)) {
                            downloadEntered.countDown()
                            check(releaseDownload.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                "Timed out waiting to release the synthetic original."
                            }
                        }
                        binary(originalBytes, "application/octet-stream")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val baseUrl = server.url("/").toString()
        compose.runOnIdle {
            viewModel = CameraViewModel(repository)
            viewModelStore.put("gallery-session", viewModel)
            viewModel.useDirectCameraPreset()
            viewModel.setBaseUrl(baseUrl)
            viewModel.connect()
        }
        // Let the connection's independent capture-review read finish before testing manual listing.
        try {
            compose.waitUntil(TIMEOUT_MILLIS) {
                val state = viewModel.uiState.value
                state.connected && !state.busy && state.captureReviewItem != null && !state.captureReviewLoading
            }
        } catch (failure: ComposeTimeoutException) {
            val state = viewModel.uiState.value
            throw AssertionError(
                "Synthetic gallery setup: connected=${state.connected}, pending=${state.pendingOperations}, " +
                    "browser=${state.supports(CameraFeature.MEDIA_BROWSER)}, review=${state.captureReviewItem?.id}, " +
                    "reviewLoading=${state.captureReviewLoading}, error=${state.error}, " +
                    "requests=${requests.size}, lastRequests=${requests.takeLast(12)}",
                failure,
            )
        }
        compose.runOnIdle { viewModel.setUiMode(UiMode.MEDIA) }
        awaitCompleteLibrary(RECENT_MEDIA_ITEMS)
        assertTrue(viewModel.uiState.value.mediaLibraryHasMore)
        assertEquals(0, secondPageRequests.get())
    }

    @After
    fun tearDown() {
        // Release every server gate even when an assertion fails, then clear all ViewModel jobs.
        releaseSecondPage.countDown()
        releaseDownload.countDown()
        try {
            if (::viewModel.isInitialized) {
                val scopeJob = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
                compose.runOnIdle { viewModelStore.clear() }
                compose.waitUntil(TIMEOUT_MILLIS) { scopeJob.isCompleted }
            }
        } finally {
            try {
                server.shutdown()
            } finally {
                if (::testDirectory.isInitialized) testDirectory.deleteRecursively()
            }
        }
    }

    @Test
    fun singleDocumentDownloadCancelsListingAndDiscardsItsLatePage() {
        blockNextSecondPage.set(true)
        val listingJob = compose.runOnIdle {
            val parent = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
            val previousJobs = parent.children.toSet()
            viewModel.setMediaLibraryScope(MediaLibraryScope.ALL)
            // Public coroutine lifecycle gives a deterministic completion barrier, without reflection.
            parent.children.single { it !in previousJobs }
        }
        assertTrue(secondPageEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        compose.waitUntil(TIMEOUT_MILLIS) { viewModel.uiState.value.mediaItems.size == 61 }
        val item = viewModel.uiState.value.mediaItems.first()
        val destination = documentDestination(item)
        compose.runOnIdle {
            assertTrue(viewModel.uiState.value.mediaLibraryLoading)
            viewModel.downloadMedia(compose.activity, item, destination)
            assertFalse(viewModel.uiState.value.mediaLibraryLoading)
            assertEquals(MediaLibraryLoadStatus.CANCELLED, viewModel.uiState.value.mediaLibraryLoadStatus)
        }
        awaitDownload(item)
        releaseSecondPage.countDown()
        compose.waitUntil(TIMEOUT_MILLIS) { listingJob.isCompleted }
        compose.runOnIdle {
            val state = viewModel.uiState.value
            assertFalse(state.mediaLibraryLoading)
            assertEquals(MediaLibraryLoadStatus.CANCELLED, state.mediaLibraryLoadStatus)
            assertEquals(mediaPaths.take(61), state.mediaItems.map(CameraMediaItem::id))
            assertFalse(state.mediaItems.any { it.id == mediaPaths.last() })
            assertEquals(item.name, state.lastDownloadedMediaName)
            assertNull(state.error)
        }
        assertDestinationBytes(destination)
    }

    private fun documentDestination(item: CameraMediaItem): Uri {
        val file = File(testDirectory, item.name)
        check(file.createNewFile())
        return FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.camera_import", file)
    }

    private fun awaitDownload(item: CameraMediaItem) = compose.waitUntil(TIMEOUT_MILLIS) {
        val state = viewModel.uiState.value
        !state.isBusy(CameraOperation.MEDIA) && state.lastDownloadedMediaName == item.name
    }

    private fun awaitCompleteLibrary(count: Int) = compose.waitUntil(TIMEOUT_MILLIS) {
        val state = viewModel.uiState.value
        !state.mediaLibraryLoading && state.mediaLibraryLoadStatus == MediaLibraryLoadStatus.COMPLETE &&
            state.mediaItems.size == count
    }

    private fun assertDestinationBytes(destination: Uri) {
        val saved = requireNotNull(compose.activity.contentResolver.openInputStream(destination)).use { it.readBytes() }
        assertArrayEquals(originalBytes, saved)
    }

    private fun paths(items: List<String>): MockResponse = json(JSONObject().put("path", JSONArray(items)).toString())
    private fun json(body: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun binary(bytes: ByteArray, type: String): MockResponse =
        MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(bytes))

    private companion object {
        const val PREFIX = "/ccapi/ver100"
        const val CONTENTS = "$PREFIX/contents"
        const val TIMEOUT_SECONDS = 15L
        const val TIMEOUT_MILLIS = TIMEOUT_SECONDS * 1_000
        const val DISCOVERY = """{"ver100":[
            {"path":"/deviceinformation","get":true},
            {"path":"/devicestatus/battery","get":true},
            {"path":"/devicestatus/storage","get":true},
            {"path":"/shooting/settings","get":true},
            {"path":"/contents","get":true}
        ]}"""
    }
}
