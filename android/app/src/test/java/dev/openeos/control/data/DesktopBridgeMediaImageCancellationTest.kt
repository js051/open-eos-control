package dev.openeos.control.data

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/** Loopback transport fixtures only; these tests do not provide physical-camera evidence. */
class DesktopBridgeMediaImageCancellationTest {
    @Test(timeout = 15_000)
    fun cancellingThumbnailBeforeHeadersInterruptsItsHttpCall() =
        assertImageGetCancellation(ImageRead.THUMBNAIL, stalledBody = false)

    @Test(timeout = 15_000)
    fun cancellingPreviewBeforeHeadersInterruptsItsHttpCall() =
        assertImageGetCancellation(ImageRead.PREVIEW, stalledBody = false)

    @Test(timeout = 15_000)
    fun cancellingThumbnailDuringBodyReadInterruptsItsHttpCall() =
        assertImageGetCancellation(ImageRead.THUMBNAIL, stalledBody = true)

    @Test(timeout = 15_000)
    fun cancellingPreviewDuringBodyReadInterruptsItsHttpCall() =
        assertImageGetCancellation(ImageRead.PREVIEW, stalledBody = true)

    @Test(timeout = 15_000)
    fun cancellingPreviewDuringErrorBodyReadStillReportsCancellation() =
        assertImageGetCancellation(ImageRead.PREVIEW, stalledBody = true, responseCode = 409)

    @Test(timeout = 15_000)
    fun activeHttpFailuresPreserveBridgeErrorsWithoutRetryOrFeatureObservation() = runBlocking<Unit> {
        for (read in ImageRead.entries) Peer().use { peer ->
            peer.imageResponse = {
                peer.json(
                    """{"error":{"code":"CAMERA_BUSY","message":"Synthetic camera busy.","feature":"${read.feature}","engine":"libgphoto2"}}""",
                ).setResponseCode(409)
            }
            val client = peer.client()
            client.initialize()

            val failure = runCatching { read.read(client) }.exceptionOrNull()

            assertTrue("An active HTTP failure must remain a Bridge failure", failure is DesktopBridgeException)
            failure as DesktopBridgeException
            assertEquals("CAMERA_BUSY", failure.code)
            assertEquals(409, failure.statusCode)
            assertEquals(read.feature.name, failure.feature)
            assertEquals("libgphoto2", failure.engine)
            assertTrue(failure.message.orEmpty().contains("Synthetic camera busy."))
            assertFalse(read.feature in client.observedFeatureSnapshot())
            peer.assertOneImageRequest(read)
            assertFalse("Finishing the watcher must not cancel a completed call", peer.imageCalls.single().isCanceled())
        }
    }

    @Test(timeout = 15_000)
    fun healthyImagesPreserveBytesTypeAuthenticationAndObservedFeature() = runBlocking<Unit> {
        for (read in ImageRead.entries) Peer().use { peer ->
            val client = peer.client()
            client.initialize()

            val (bytes, contentType) = read.read(client)

            assertArrayEquals(JPEG, bytes)
            assertEquals("image/jpeg", contentType)
            assertTrue(read.feature in client.observedFeatureSnapshot())
            peer.assertOneImageRequest(read)
            assertFalse("A successful read must disarm its cancellation watcher", peer.imageCalls.single().isCanceled())
        }
    }

    @Test(timeout = 15_000)
    fun declaredThumbnailAndPreviewLimitsRejectOversizeBeforeReadingBody() = runBlocking<Unit> {
        for (read in ImageRead.entries) Peer().use { peer ->
            peer.imageResponse = {
                peer.image().setHeader("Content-Length", read.maximumBytes + 1L)
            }
            val client = peer.client()
            client.initialize()

            val failure = runCatching { read.read(client) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("exceeded ${read.maximumBytes} bytes"))
            assertEquals("The declared size must be checked before reading image bytes", 0, peer.bodyReads.get())
            assertFalse(read.feature in client.observedFeatureSnapshot())
            peer.assertOneImageRequest(read)
        }
    }

    @Test(timeout = 15_000)
    fun chunkedThumbnailCannotBypassItsStreamingSizeLimit() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.imageResponse = {
                MockResponse().setHeader("Content-Type", "image/jpeg")
                    .setChunkedBody(Buffer().write(ByteArray(ImageRead.THUMBNAIL.maximumBytes.toInt() + 1)), 64 * 1024)
            }
            val client = peer.client()
            client.initialize()

            val failure = runCatching { client.mediaThumbnail(ITEM) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("exceeded ${ImageRead.THUMBNAIL.maximumBytes} bytes"))
            assertTrue(peer.bodyReads.get() > 0)
            assertFalse(CameraFeature.MEDIA_THUMBNAIL in client.observedFeatureSnapshot())
            peer.assertOneImageRequest(ImageRead.THUMBNAIL)
        }
    }

    @Test(timeout = 15_000)
    fun nonImageOrEmptyResponsesRemainFailuresWithoutObservingPreviewSupport() = runBlocking<Unit> {
        for (invalidResponse in listOf(
            MockResponse().setHeader("Content-Type", "text/plain").setBody("not an image"),
            MockResponse().setHeader("Content-Type", "image/jpeg").setBody(""),
        )) Peer().use { peer ->
            peer.imageResponse = { invalidResponse }
            val client = peer.client()
            client.initialize()

            val failure = runCatching { client.mediaPreview(ITEM) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("did not return an image display preview"))
            assertFalse(CameraFeature.MEDIA_PREVIEW in client.observedFeatureSnapshot())
            peer.assertOneImageRequest(ImageRead.PREVIEW)
        }
    }

    @Test(timeout = 15_000)
    fun responseFromClosedSessionCannotObservePreviewSupportInReinitializedSession() = runBlocking<Unit> {
        Peer().use { peer ->
            val respond = CountDownLatch(1)
            peer.imageResponse = {
                check(respond.await(5, TimeUnit.SECONDS)) { "Test did not release the image response" }
                peer.image()
            }
            val client = peer.client()
            client.initialize()
            val outcome = CompletableDeferred<Throwable?>()
            val reading = launch(Dispatchers.Default) {
                outcome.complete(runCatching { client.mediaPreview(ITEM) }.exceptionOrNull())
            }
            try {
                assertReached(peer.imageReceived, "The old-session image request must reach the peer")
                client.close()
                // Reuse the wire ID deliberately: session identity must still distinguish the two owners.
                client.initialize()
                respond.countDown()
                withTimeout(2_000) { reading.join() }

                assertTrue(withTimeout(2_000) { outcome.await() } is CancellationException)
                assertFalse(CameraFeature.MEDIA_PREVIEW in client.observedFeatureSnapshot())
                peer.assertOneImageRequest(ImageRead.PREVIEW, expectedTotalRequests = 6)
                assertEquals(1, peer.requests.count { it.method == "DELETE" && it.path == SESSION_PATH })
            } finally {
                respond.countDown()
                peer.http.dispatcher.cancelAll()
                reading.cancel()
                withTimeout(2_000) { reading.join() }
            }
        }
    }

    private fun assertImageGetCancellation(
        read: ImageRead,
        stalledBody: Boolean,
        responseCode: Int = 200,
    ) = runBlocking<Unit> {
        Peer().use { peer ->
            peer.imageResponse = {
                if (stalledBody) {
                    // Send a real prefix and advertise more bytes. KEEP_OPEN leaves the next socket
                    // read blocked, without a timed server sleep or a synthetic source exception.
                    peer.image().setResponseCode(responseCode).setHeader("Content-Length", JPEG.size + 128)
                } else {
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
            val client = peer.client()
            client.initialize()
            val outcome = CompletableDeferred<Throwable?>()
            val reading = launch(Dispatchers.Default) {
                outcome.complete(runCatching { read.read(client) }.exceptionOrNull())
            }
            try {
                assertReached(peer.imageReceived, "The image request must reach the peer")
                if (stalledBody) {
                    assertReached(peer.readAfterPrefix, "The client must read the prefix and enter the next body read")
                }
                reading.cancel()

                assertTrue("Cancellation must release the blocked HTTP read within one second",
                    withTimeoutOrNull(1_000) { reading.join(); true } ?: false)
                assertTrue("A cancelled request must report cancellation, not a transport or Bridge failure",
                    withTimeout(2_000) { outcome.await() } is CancellationException)
                assertTrue("The owning coroutine must cancel its exact OkHttp call", peer.imageCalls.single().isCanceled())
                assertFalse(read.feature in client.observedFeatureSnapshot())
                peer.assertOneImageRequest(read)
            } finally {
                // Release even the pre-fix blocked socket after the bounded regression assertion.
                peer.http.dispatcher.cancelAll()
                reading.cancel()
                withTimeout(2_000) { reading.join() }
            }
        }
    }

    private suspend fun assertReached(latch: CountDownLatch, message: String) {
        assertTrue(message, withContext(Dispatchers.IO) { latch.await(2, TimeUnit.SECONDS) })
    }

    private enum class ImageRead(val endpoint: String, val feature: CameraFeature, val maximumBytes: Long) {
        THUMBNAIL("thumbnail", CameraFeature.MEDIA_THUMBNAIL, 8 * 1024 * 1024L),
        PREVIEW("preview", CameraFeature.MEDIA_PREVIEW, 32 * 1024 * 1024L);

        suspend fun read(client: DesktopBridgeClient): Pair<ByteArray, String?> = when (this) {
            THUMBNAIL -> client.mediaThumbnail(ITEM).let { it.bytes to it.contentType }
            PREVIEW -> client.mediaPreview(ITEM).let { it.bytes to it.contentType }
        }
    }

    private class Peer : AutoCloseable {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val imageCalls = CopyOnWriteArrayList<Call>()
        val imageReceived = CountDownLatch(1)
        val readAfterPrefix = CountDownLatch(1)
        val bodyReads = AtomicInteger()
        var imageResponse: () -> MockResponse = { image() }
        val http = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    if (call.request().url.encodedPath.startsWith("$SESSION_PATH/media/")) imageCalls += call
                }
            })
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                if (!chain.request().url.encodedPath.startsWith("$SESSION_PATH/media/") || body == null) {
                    response
                } else {
                    val source = object : ForwardingSource(body.source()) {
                        private var receivedBytes = 0L

                        override fun read(sink: Buffer, byteCount: Long): Long {
                            bodyReads.incrementAndGet()
                            if (receivedBytes > 0L) readAfterPrefix.countDown()
                            return super.read(sink, byteCount).also { if (it > 0L) receivedBytes += it }
                        }
                    }.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source() = source
                    }).build()
                }
            }
            .build()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when {
                        request.method == "GET" && request.path == "/health" ->
                            json("""{"service":"open-eos-control-bridge"}""")
                        request.method == "POST" && request.path == "/v1/session" ->
                            json("""{"id":"synthetic-image-session"}""").setResponseCode(201)
                        request.method == "DELETE" && request.path == SESSION_PATH ->
                            MockResponse().setResponseCode(204)
                        request.method == "GET" && request.path in ImageRead.entries.map { "$SESSION_PATH/media/${ITEM.id}/${it.endpoint}" } -> {
                            imageReceived.countDown()
                            imageResponse()
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }

        fun client() = DesktopBridgeClient(server.url("/").toString(), httpClient = http, token = "synthetic-image-token")

        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

        fun image() = MockResponse().setHeader("Content-Type", "image/jpeg; charset=binary").setBody(Buffer().write(JPEG))

        fun assertOneImageRequest(read: ImageRead, expectedTotalRequests: Int = 3) {
            val image = requests.filter { it.path?.contains("/media/") == true }.single()
            assertEquals("GET", image.method)
            assertEquals("$SESSION_PATH/media/${ITEM.id}/${read.endpoint}", image.path)
            assertEquals("image/*", image.getHeader("Accept"))
            assertEquals("Bearer synthetic-image-token", image.getHeader("Authorization"))
            assertEquals("No hidden retry, fallback, or extra request", expectedTotalRequests, server.requestCount)
            assertEquals(1, imageCalls.size)
        }

        override fun close() {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }

    private companion object {
        const val SESSION_PATH = "/v1/session/synthetic-image-session"
        val ITEM = CameraMediaItem("synthetic-image-1", "IMG_TEST.JPG", "image")
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 3, 7, 0xFF.toByte(), 0xD9.toByte())
    }
}
