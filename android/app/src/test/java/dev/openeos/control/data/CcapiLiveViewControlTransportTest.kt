package dev.openeos.control.data

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Real OkHttp protocol fixtures. These do not establish physical-camera or Android decoder behavior. */
class CcapiLiveViewControlTransportTest {
    @Test fun ordinaryStartCancellationJoinsTheResponseAndOriginalStop() = runBlocking {
        val startHeaders = CountDownLatch(1)
        Scenario(http = observedStartClient(startHeaders)).use { s ->
            s.startResponse = { pacedResponse() }
            s.client.initialize()
            val outcome = CompletableDeferred<Throwable?>()
            val start = launch(Dispatchers.IO) {
                outcome.complete(runCatching { s.client.startLiveView(jpegRequest) }.exceptionOrNull())
            }
            assertTrue(withContext(Dispatchers.IO) { startHeaders.await(2, TimeUnit.SECONDS) })
            val joined = withTimeoutOrNull(700) { start.cancelAndJoin(); true }
            start.cancelAndJoin() // The finite baseline response also terminates cleanly.
            assertEquals("Cancel must stop and join the response producer", true, joined)
            assertTrue(outcome.await() is CancellationException)
            assertEquals(listOf("start", "stop"), s.events.toList())
            assertFalse(s.client.liveViewStopRequired)
            assertNull(s.client.currentLiveViewSource())
        }
    }

    @Test fun cancelledStartRemainsJoinedUntilMandatoryStopBodyCompletes() = runBlocking {
        val startHeaders = CountDownLatch(1)
        Scenario(http = observedStartClient(startHeaders)).use { s ->
            s.startResponse = { pacedResponse() }
            s.stopResponse = { MockResponse().setBody("{}").setBodyDelay(300, TimeUnit.MILLISECONDS) }
            s.client.initialize()
            val start = launch(Dispatchers.IO) { runCatching { s.client.startLiveView(jpegRequest) } }
            assertTrue(withContext(Dispatchers.IO) { startHeaders.await(2, TimeUnit.SECONDS) })
            start.cancel()
            val stopReached = withContext(Dispatchers.IO) { s.stopReceived.await(700, TimeUnit.MILLISECONDS) }
            val detached = withTimeoutOrNull(80) { start.join(); true }
            start.join()
            assertTrue("The original Stop must be reached after cancellation", stopReached)
            assertNull("Stop response cannot be detached", detached)
            assertFalse(s.client.liveViewStopRequired)
            assertEquals(listOf("start", "stop"), s.events.toList())
        }
    }

    @Test(timeout = 25_000) fun defaultWholeCallDeadlineStopsContinuouslyDrippingStopBody() = runBlocking {
        // Every byte arrives well within readTimeout. Only a whole-call deadline stops this read.
        val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        assertEquals(0, http.callTimeoutMillis)
        Scenario(http = http).use { s ->
            s.client.initialize(); s.client.startLiveView(jpegRequest)
            s.stopResponse = { MockResponse().setBody("{" + " ".repeat(98) + "}")
                .throttleBody(1, 200, TimeUnit.MILLISECONDS) }
            val started = System.nanoTime()
            val failure = runCatching { s.client.stopLiveView() }.exceptionOrNull()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(failure is CcapiLiveViewReleaseException)
            assertTrue("The production 15-second cap must bound the whole response: $elapsed ms", elapsed in 12_000..19_000)
            assertTrue(s.client.liveViewStopRequired)
            val before = s.events.toList()
            assertTrue(runCatching { s.client.startLiveView() }.isFailure)
            assertEquals(before, s.events.toList())
        }
    }

    @Test fun suppliedShorterDeadlineIsPreservedAndUnknownStopCanOnlyBeRetried() = runBlocking {
        Scenario(http = OkHttpClient.Builder().callTimeout(250, TimeUnit.MILLISECONDS).build()).use { s ->
            s.client.initialize(); s.client.startLiveView(jpegRequest)
            s.stopResponse = { pacedResponse() }
            val started = System.nanoTime()
            assertTrue(runCatching { s.client.stopLiveView() }.exceptionOrNull() is CcapiLiveViewReleaseException)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
            assertTrue(s.client.liveViewStopRequired)
            val before = s.events.toList()
            assertTrue(runCatching { s.client.startLiveView() }.isFailure)
            assertEquals(before, s.events.toList())
            s.stopResponse = { MockResponse().setResponseCode(204) }
            s.client.stopLiveView()
            assertFalse(s.client.liveViewStopRequired)
            assertEquals(1, s.events.count { it == "start" })
        }
    }

    @Test fun oversizedChunkedStopPreventsAutoFallbackAndKeepsOriginalOwner() = runBlocking {
        Scenario(multipart = true).use { s ->
            s.multipartResponse = { MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=canon").setBody("") }
            s.stopResponse = { MockResponse().setChunkedBody(" ".repeat(65_537), 1024) }
            s.client.initialize()
            assertTrue(runCatching { s.client.startLiveView() }.exceptionOrNull() is CcapiLiveViewReleaseException)
            assertEquals(listOf("start", "open", "close-stream", "stop"), s.events.toList())
            assertTrue(s.client.liveViewStopRequired)
            assertNull(s.client.currentLiveViewSource())
            val before = s.events.toList()
            assertTrue(runCatching { s.client.startLiveView(jpegRequest) }.isFailure)
            assertEquals(before, s.events.toList())
        }
    }

    @Test fun multipartCloseStreamAndGeneralStopActuallySelectBoundedCalls() = runBlocking {
        val controls = CopyOnWriteArrayList<Pair<String, Long>>()
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                if (call.request().method != "GET") controls += call.request().url.encodedPath to
                    TimeUnit.NANOSECONDS.toMillis(call.timeout().timeoutNanos())
            }
        }).build()
        Scenario(http = http, multipart = true).use { s ->
            s.client.initialize(); s.client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_MULTIPART))
            s.client.stopLiveView()
            assertEquals(listOf("start", "open", "close-stream", "stop"), s.events.toList())
            assertEquals(listOf(15_000L, 15_000L, 15_000L), controls.map { it.second })
            assertTrue(controls[1].first.endsWith("/multipart"))
            assertFalse(s.client.liveViewStopRequired)
        }
    }

    @Test fun plainText400RetainsExistingSizeFallbackSemantics() = runBlocking {
        Scenario().use { s ->
            var starts = 0
            s.startResponse = { if (++starts < 3) MockResponse().setResponseCode(400).setBody("Invalid size")
                else MockResponse().setResponseCode(204) }
            s.client.initialize(); s.client.startLiveView(jpegRequest)
            assertEquals(listOf("start", "start", "start", "jpeg"), s.events.toList())
            assertEquals(LiveViewSource.CCAPI_JPEG_POLLING, s.client.currentLiveViewSource())
            s.client.stopLiveView()
            assertFalse(s.client.liveViewStopRequired)
        }
    }

    @Test fun controlRedirectCannotReplayStartBeforeOwnedCleanup() = runBlocking {
        Scenario().use { s ->
            s.startResponse = { MockResponse().setResponseCode(302).setHeader("Location", s.server.url("/forbidden")).setBody("") }
            s.client.initialize()
            assertTrue(runCatching { s.client.startLiveView(jpegRequest) }.isFailure)
            assertEquals(listOf("start", "stop"), s.events.toList())
            assertFalse(s.client.liveViewStopRequired)
        }
    }

    private fun observedStartClient(headers: CountDownLatch) = OkHttpClient.Builder()
        .eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) {
                if (call.request().method == "POST" && call.request().url.encodedPath.endsWith("/shooting/liveview")) headers.countDown()
            }
        }).build()

    private class Scenario(http: OkHttpClient? = null, multipart: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val events = CopyOnWriteArrayList<String>()
        val stopReceived = CountDownLatch(1)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())
        var startResponse: () -> MockResponse = { MockResponse().setResponseCode(204) }
        var stopResponse: () -> MockResponse = { MockResponse().setResponseCode(204) }
        var multipartResponse: () -> MockResponse = {
            MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=canon")
                .setBody(Buffer().writeUtf8("--canon\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n")
                    .write(jpeg).writeUtf8("\r\n")).setHeader("Content-Length", 1_000_000)
        }
        val client: CcapiClient
        init {
            val multipartEntry = if (multipart) ",{\"path\":\"/shooting/liveview/multipart\",\"get\":true,\"delete\":true}" else ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/ccapi" -> MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"ver110":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/flip","get":true}$multipartEntry]}""")
                    request.path?.endsWith("/multipart") == true && request.method == "GET" -> { events += "open"; multipartResponse() }
                    request.path?.endsWith("/multipart") == true -> { events += "close-stream"; MockResponse().setResponseCode(204) }
                    request.path?.contains("/flip?") == true -> { events += "jpeg"
                        MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)) }
                    request.path?.endsWith("/shooting/liveview") == true && request.method == "POST" -> { events += "start"; startResponse() }
                    request.path?.endsWith("/shooting/liveview") == true && request.method == "DELETE" -> {
                        events += "stop"; stopReceived.countDown(); stopResponse()
                    }
                    else -> { events += "unexpected"; MockResponse().setResponseCode(404) }
                }
            }
            server.start()
            client = CcapiClient(server.url("/").toString(), httpClient = http, treatAsSimulator = false)
        }
        override fun close() {
            stopResponse = { MockResponse().setResponseCode(204) }
            try { runBlocking { client.stopLiveView() } } finally { server.shutdown() }
        }
    }

    companion object {
        private val jpegRequest = LiveViewRequest(source = LiveViewSource.CCAPI_JPEG_POLLING)
        private fun pacedResponse() = MockResponse().setBody("{           }").throttleBody(1, 200, TimeUnit.MILLISECONDS)
    }
}
