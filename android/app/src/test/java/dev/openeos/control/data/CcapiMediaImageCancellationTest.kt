package dev.openeos.control.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class CcapiMediaImageCancellationTest {
    @Test fun cancellingThumbnailInterruptsItsActiveHttpCall() =
        assertImageGetCancellation { client, item -> client.mediaThumbnail(item) }

    @Test fun cancellingDisplayPreviewInterruptsItsActiveHttpCall() =
        assertImageGetCancellation { client, item -> client.mediaPreview(item) }

    private fun assertImageGetCancellation(read: suspend (CcapiClient, CameraMediaItem) -> Unit) = runBlocking {
        val server = MockWebServer()
        val imageReceived = CountDownLatch(1)
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ccapi" -> MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"ver110":[{"path":"/contents","get":true}]}""")
                else -> {
                    imageReceived.countDown()
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, httpClient = http)
            client.initialize()
            val item = CameraMediaItem("/ccapi/ver110/contents/card1/IMG_0001.JPG", "IMG_0001.JPG", "image")
            val outcome = CompletableDeferred<Throwable?>()
            val job = launch(Dispatchers.Default) {
                outcome.complete(runCatching { read(client, item) }.exceptionOrNull())
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { imageReceived.await(2, TimeUnit.SECONDS) })
                job.cancel()
                assertTrue("Cancel must terminate the active image HTTP read within one second",
                    withTimeoutOrNull(1_000) { job.join(); true } ?: false)
                assertTrue(outcome.await() is CancellationException)
                assertEquals(2, server.requestCount)
            } finally {
                // Ensure the pre-fix client also releases its blocked socket without masking the assertion.
                http.dispatcher.cancelAll()
                job.cancel()
                job.join()
            }
        } finally { server.shutdown() }
    }
}
