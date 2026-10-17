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
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual OkHttp protocol fixtures; no physical-camera or Android device claims. */
class CcapiDirectReleaseTransportTest {
    @Test fun cancellingHeldAfJoinsStartAndItsMandatoryStopResponse() = runBlocking {
        val headers = CountDownLatch(1)
        Scenario(http = observedCommandClient(headers)).use { s ->
            s.startResponse = { pacedResponse() }
            s.stopResponse = { MockResponse().setBody("{}").setBodyDelay(300, TimeUnit.MILLISECONDS) }
            s.client.initialize()
            var held = false
            val job = launch(Dispatchers.IO) { runCatching { s.client.holdAutofocus { held = true } } }
            assertTrue(withContext(Dispatchers.IO) { headers.await(2, TimeUnit.SECONDS) })
            job.cancel()
            val reached = withContext(Dispatchers.IO) { s.stopReceived.await(700, TimeUnit.MILLISECONDS) }
            val detached = withTimeoutOrNull(80) { job.join(); true }
            job.join()
            assertTrue("Cancelled AF Start must reach its original Stop", reached)
            assertNull("Mandatory AF Stop cannot be detached", detached)
            assertFalse(held)
            assertEquals(listOf("POST:af:start", "POST:af:stop"), s.events.toList())
        }
    }

    @Test fun cancellingBulbStartJoinsItsOriginalRelease() = runBlocking {
        val headers = CountDownLatch(1)
        Scenario(http = observedCommandClient(headers)).use { s ->
            s.startResponse = { pacedResponse() }
            s.client.initialize()
            val job = launch(Dispatchers.IO) { runCatching { s.client.startBulbExposure() } }
            assertTrue(withContext(Dispatchers.IO) { headers.await(2, TimeUnit.SECONDS) })
            val joined = withTimeoutOrNull(700) { job.cancelAndJoin(); true }
            job.cancelAndJoin()
            assertEquals("Cancel must stop and join the Bulb response producer", true, joined)
            assertEquals(listOf("POST:manual:full_press", "POST:manual:release"), s.events.toList())
            s.client.retryShutterRelease()
            assertEquals(2, s.events.size)
        }
    }

    @Test(timeout = 25_000) fun defaultWholeCallDeadlineBoundsNonCancellableAfStop() = runBlocking {
        val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        assertEquals(0, http.callTimeoutMillis)
        Scenario(http = http).use { s ->
            s.client.initialize()
            s.stopResponse = { MockResponse().setBody("{" + " ".repeat(98) + "}")
                .throttleBody(1, 200, TimeUnit.MILLISECONDS) }
            val start = System.nanoTime()
            val failure = runCatching { s.client.holdAutofocus {} }.exceptionOrNull()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(failure is AutofocusReleaseException)
            assertTrue("The 15-second production cap must stop a continuously dripping body: $elapsed ms", elapsed in 12_000..19_000)
            assertTrue(runCatching { s.client.holdAutofocus {} }.isFailure)
            s.stopResponse = { MockResponse().setResponseCode(204) }
            s.client.retryAutofocusStop()
            assertEquals(listOf("POST:af:start", "POST:af:stop", "POST:af:stop"), s.events.toList())
        }
    }

    @Test fun shorterDeadlinePreservesUnknownBulbReleaseForStopOnlyRetry() = runBlocking {
        Scenario(http = OkHttpClient.Builder().callTimeout(250, TimeUnit.MILLISECONDS).build()).use { s ->
            s.client.initialize(); s.client.startBulbExposure()
            s.stopResponse = { pacedResponse() }
            val start = System.nanoTime()
            assertTrue(runCatching { s.client.stopBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
            val previous = s.events.toList()
            assertTrue(runCatching { s.client.startBulbExposure() }.isFailure)
            assertEquals(previous, s.events.toList())
            s.stopResponse = { MockResponse().setResponseCode(204) }
            s.client.retryShutterRelease(); s.client.retryShutterRelease()
            assertEquals(listOf("POST:manual:full_press", "POST:manual:release", "POST:manual:release"), s.events.toList())
        }
    }

    @Test fun pulseManualFallbackHalfPressAndBulbSelectActualCappedCalls() = runBlocking {
        val timeouts = CopyOnWriteArrayList<Long>()
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                if (call.request().method != "GET") timeouts += TimeUnit.NANOSECONDS.toMillis(call.timeout().timeoutNanos())
            }
        }).build()
        Scenario(http = http).use { s ->
            s.client.initialize(); s.client.autofocus()
            s.client.startBulbExposure(); s.client.stopBulbExposure()
            assertEquals(listOf("POST:af:start", "POST:af:stop", "POST:manual:full_press", "POST:manual:release"), s.events.toList())
        }
        Scenario(http = http, nativeAf = false, manualMethod = "PUT").use { s ->
            s.client.initialize(); s.client.autofocus(); s.client.halfPressShutter()
            assertEquals(listOf("PUT:manual:half_press", "PUT:manual:release", "PUT:manual:half_press", "PUT:manual:release"), s.events.toList())
        }
        assertEquals(List(8) { 15_000L }, timeouts.toList())
    }

    @Test fun oversizedAfStartStillReleasesWithoutEnteringHeldCallback() = runBlocking {
        Scenario().use { s ->
            s.startResponse = { MockResponse().setBody(" ".repeat(65_537)) }
            s.client.initialize()
            var held = false
            assertTrue(runCatching { s.client.holdAutofocus { held = true } }.isFailure)
            assertFalse(held)
            s.client.retryAutofocusStop()
            assertEquals(listOf("POST:af:start", "POST:af:stop"), s.events.toList())
        }
    }

    @Test fun oversizedChunkedAfStopKeepsOriginalRetryObligation() = runBlocking {
        Scenario().use { s ->
            s.stopResponse = { MockResponse().setChunkedBody(" ".repeat(65_537), 1024) }
            s.client.initialize()
            assertTrue(runCatching { s.client.holdAutofocus {} }.exceptionOrNull() is AutofocusReleaseException)
            assertTrue(runCatching { s.client.holdAutofocus {} }.isFailure)
            s.stopResponse = { MockResponse().setResponseCode(204) }
            s.client.retryAutofocusStop()
            assertEquals(listOf("POST:af:start", "POST:af:stop", "POST:af:stop"), s.events.toList())
        }
    }

    @Test fun truncatedBulbReleaseCannotBecomeAcknowledged() = runBlocking {
        Scenario().use { s ->
            s.client.initialize(); s.client.startBulbExposure()
            s.stopResponse = { MockResponse().setBody("{}").setHeader("Content-Length", 12)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END) }
            assertTrue(runCatching { s.client.stopBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
            assertTrue(runCatching { s.client.startBulbExposure() }.isFailure)
            s.stopResponse = { MockResponse().setResponseCode(204) }
            s.client.retryShutterRelease()
            assertEquals(listOf("POST:manual:full_press", "POST:manual:release", "POST:manual:release"), s.events.toList())
        }
    }

    @Test fun redirectsCannotReplayAfStartBeforeMandatoryStop() = runBlocking {
        Scenario().use { s ->
            s.startResponse = { MockResponse().setResponseCode(307).setHeader("Location", s.server.url("/forbidden")).setBody("") }
            s.client.initialize()
            assertTrue(runCatching { s.client.holdAutofocus {} }.isFailure)
            assertEquals(listOf("POST:af:start", "POST:af:stop"), s.events.toList())
        }
    }

    private class Scenario(http: OkHttpClient? = null, nativeAf: Boolean = true, manualMethod: String = "POST") : AutoCloseable {
        val server = MockWebServer()
        val events = CopyOnWriteArrayList<String>()
        val stopReceived = CountDownLatch(1)
        var startResponse: () -> MockResponse = { MockResponse().setResponseCode(204) }
        var stopResponse: () -> MockResponse = { MockResponse().setResponseCode(204) }
        val client: CcapiClient
        init {
            val af = if (nativeAf) ", {\"path\":\"/shooting/control/af\",\"post\":true}" else ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/ccapi" -> MockResponse().setBody("""{"ver120":[
                        {"path":"/shooting/control/shutterbutton/manual","${manualMethod.lowercase()}":true}$af]}""")
                    request.path == "/forbidden" -> { events += "forbidden"; MockResponse().setResponseCode(204) }
                    request.method != "GET" -> {
                        val action = JSONObject(request.body.readUtf8()).getString("action")
                        val manual = request.path!!.endsWith("/shutterbutton/manual")
                        assertEquals("/ccapi/ver120/shooting/control/${if (manual) "shutterbutton/manual" else "af"}", request.path)
                        events += "${request.method}:${if (manual) "manual" else "af"}:$action"
                        if (action == "stop" || action == "release") {
                            stopReceived.countDown(); stopResponse()
                        } else startResponse()
                    }
                    else -> MockResponse().setBody("{}")
                }
            }
            server.start()
            client = CcapiClient(server.url("/").toString(), httpClient = http, treatAsSimulator = false)
        }
        override fun close() {
            stopResponse = { MockResponse().setResponseCode(204) }
            try { runBlocking { client.retryAutofocusStop(); client.retryShutterRelease() } } finally { server.shutdown() }
        }
    }

    companion object {
        private fun pacedResponse() = MockResponse().setBody("{           }").throttleBody(1, 200, TimeUnit.MILLISECONDS)
        private fun observedCommandClient(headers: CountDownLatch) = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) {
                    if (call.request().method != "GET") headers.countDown()
                }
            }).build()
    }
}
