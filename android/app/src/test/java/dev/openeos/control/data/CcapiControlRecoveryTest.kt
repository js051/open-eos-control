package dev.openeos.control.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Independent failure model: requests may take effect before a response is lost.
 * These tests call the production CcapiClient; only its HTTP peer is synthetic.
 * No physical-camera validation is implied by these synthetic failure fixtures.
 */
class CcapiControlRecoveryTest {
    private val manualDiscovery = """{"ver110":[{"path":"/shooting/control/shutterbutton/manual","put":true}]}"""
    private val jpegDiscovery = """{"ver110":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/flip","get":true}]}"""
    private val multipartDiscovery = """{"ver110":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/multipart","get":true,"delete":true}]}"""
    private fun json(body: String = "{}") = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun noRetryHttp() = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()
    private fun action(request: RecordedRequest) = JSONObject(request.body.clone().readUtf8()).optString("action")

    @Test fun lostBulbStartAndFailedCompensationMustKeepReleaseRetryable() = runBlocking {
        val server = MockWebServer()
        val releases = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json(manualDiscovery)
                request.path?.endsWith("/shutterbutton/manual") == true && action(request) == "full_press" ->
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                request.path?.endsWith("/shutterbutton/manual") == true && action(request) == "release" ->
                    if (releases.incrementAndGet() == 1) MockResponse().setResponseCode(503).setBody("release rejected")
                    else MockResponse().setResponseCode(204)
                else -> json()
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, httpClient = noRetryHttp())
            client.initialize()
            assertTrue(runCatching { client.startBulbExposure() }.isFailure)
            assertEquals(1, releases.get())
            client.stopBulbExposure()
            assertEquals("The explicit stop must retry release after an ambiguous start and failed cleanup", 2, releases.get())
        } finally { server.shutdown() }
    }

    @Test fun rejectedManualCaptureReleaseMustRemainOwnedUntilCloseRetriesIt() = runBlocking {
        val server = MockWebServer()
        val releases = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json(manualDiscovery)
                request.path?.endsWith("/shutterbutton/manual") == true && action(request) == "release" ->
                    if (releases.incrementAndGet() == 1) MockResponse().setResponseCode(503).setBody("release rejected")
                    else MockResponse().setResponseCode(204)
                request.path?.endsWith("/shutterbutton/manual") == true -> MockResponse().setResponseCode(204)
                else -> json()
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, httpClient = noRetryHttp())
            client.initialize()
            assertTrue(runCatching { client.captureStill() }.isFailure)
            client.close()
            assertEquals("Closing must retry the unresolved shutter release", 2, releases.get())
        } finally { server.shutdown() }
    }

    @Test fun lostJpegLiveViewStartResponseMustStillAttemptStop() = runBlocking {
        val server = MockWebServer()
        val stops = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json(jpegDiscovery)
                request.path?.endsWith("/shooting/liveview") == true && request.method == "POST" ->
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                request.path?.endsWith("/shooting/liveview") == true && request.method == "DELETE" -> {
                    stops.incrementAndGet()
                    MockResponse().setResponseCode(204)
                }
                else -> json()
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, httpClient = noRetryHttp())
            client.initialize()
            assertTrue(runCatching { client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_JPEG_POLLING)) }.isFailure)
            client.close()
            assertEquals("A received start with missing response still needs its compensating stop", 1, stops.get())
        } finally { server.shutdown() }
    }

    @Test fun cancellingMultipartStartWithoutHeadersMustCancelItsHttpCall() = runBlocking {
        val server = MockWebServer()
        val streamReceived = CountDownLatch(1)
        val http = noRetryHttp()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json(multipartDiscovery)
                request.path?.endsWith("/shooting/liveview/multipart") == true && request.method == "GET" -> {
                    streamReceived.countDown()
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                else -> json()
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, httpClient = http)
            client.initialize()
            val job = launch(Dispatchers.Default) {
                // Capture a late socket failure so broken cleanup cannot mask the cancellation assertion.
                runCatching { client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_MULTIPART)) }
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { streamReceived.await(2, TimeUnit.SECONDS) })
                job.cancel()
                val completed = withTimeoutOrNull(1_000) { job.join(); true } ?: false
                assertTrue("Cancellation must not wait for an unbounded response-header read", completed)
            } finally {
                // Derived OkHttp clients share Dispatcher: ensure even the broken implementation cannot hang the test.
                http.dispatcher.cancelAll()
                job.cancel()
                job.join()
                client.close()
            }
        } finally { server.shutdown() }
    }

    @Test fun droppedStillCaptureResponseMustNotReplayAnAlreadyReceivedCommand() = runBlocking {
        val server = MockWebServer()
        val captures = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json("""{"ver110":[{"path":"/shooting/control/shutterbutton","post":true}]}""")
                request.path?.endsWith("/shooting/control/shutterbutton") == true -> {
                    if (captures.incrementAndGet() == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else MockResponse().setResponseCode(204)
                }
                else -> json()
            }
        }
        server.start()
        try {
            // Production default retry behavior, unlike the other tests which isolate release ownership.
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
            client.initialize() // primes a healthy pooled connection before the lost command response
            assertTrue(runCatching { client.captureStill() }.isFailure)
            assertFalse(CameraFeature.STILL_CAPTURE in client.observedFeatureSnapshot())
            assertEquals("One user capture must not become two received shutter commands after response loss", 1, captures.get())
        } finally { server.shutdown() }
    }

    @Test fun unknownBulbReleaseBlocksNewWritesUntilStopOnlyRetrySucceeds() = runBlocking {
        val server = MockWebServer()
        val presses = AtomicInteger()
        val releases = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json(manualDiscovery)
                request.path?.endsWith("/shutterbutton/manual") == true && action(request) == "full_press" -> {
                    presses.incrementAndGet()
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
                request.path?.endsWith("/shutterbutton/manual") == true && action(request) == "release" ->
                    if (releases.incrementAndGet() == 1) MockResponse().setResponseCode(503).setBody("release rejected")
                    else MockResponse().setResponseCode(204)
                else -> json()
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
            client.initialize()
            val failure = runCatching { client.startBulbExposure() }.exceptionOrNull()
            assertTrue(failure is ShutterReleaseException)
            assertEquals(1, failure!!.suppressed.size)
            val requestCount = server.requestCount
            assertTrue(runCatching { client.startBulbExposure() }.isFailure)
            assertTrue(runCatching { client.captureStill() }.isFailure)
            assertTrue(runCatching { client.startLiveView() }.isFailure)
            assertEquals(requestCount, server.requestCount)
            client.retryShutterRelease()
            client.retryShutterRelease()
            assertEquals(1, presses.get())
            assertEquals(2, releases.get())
            assertFalse(client.status().bulbExposureActive == true)
        } finally { server.shutdown() }
    }

    @Test fun failedLiveViewCleanupCannotBeHiddenByAutoFallbackAndRemainsRetryable() = runBlocking {
        val server = MockWebServer()
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val discovery = """{"ver110":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/multipart","get":true,"delete":true},{"path":"/shooting/liveview/flip","get":true}]}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json(discovery)
                request.path?.endsWith("/shooting/liveview") == true && request.method == "POST" -> {
                    starts.incrementAndGet()
                    MockResponse().setResponseCode(204)
                }
                request.path?.endsWith("/shooting/liveview") == true && request.method == "DELETE" ->
                    if (stops.incrementAndGet() == 1) MockResponse().setResponseCode(503).setBody("stop rejected")
                    else MockResponse().setResponseCode(204)
                request.path?.endsWith("/multipart") == true && request.method == "GET" ->
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain").setBody("invalid stream")
                else -> json()
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
            client.initialize()
            assertTrue(runCatching { client.startLiveView() }.exceptionOrNull() is CcapiLiveViewReleaseException)
            assertTrue(client.liveViewStopRequired)
            assertNull(client.currentLiveViewSource())
            assertEquals("AUTO must not start JPEG after an unconfirmed multipart stop", 1, starts.get())
            val requests = server.requestCount
            assertTrue(runCatching { client.startLiveView() }.isFailure)
            assertEquals(requests, server.requestCount)
            client.stopLiveView()
            client.stopLiveView()
            assertEquals(2, stops.get())
            assertFalse(client.liveViewStopRequired)
            client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_JPEG_POLLING))
            assertEquals(2, starts.get())
            client.stopLiveView()
        } finally { server.shutdown() }
    }

    @Test fun retryAfterZeroCannotReplayMutatingShutterBody() = runBlocking {
        val server = MockWebServer()
        val captures = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json("""{"ver110":[{"path":"/shooting/control/shutterbutton","post":true}]}""")
                request.path?.endsWith("/shutterbutton") == true -> {
                    captures.incrementAndGet()
                    MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy")
                }
                else -> json()
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
            client.initialize()
            assertTrue(runCatching { client.captureStill() }.isFailure)
            assertEquals(1, captures.get())
        } finally { server.shutdown() }
    }

    @Test fun cancellingJpegReadInterruptsTheHttpCallWithoutTryingAnotherVariant() = runBlocking {
        val server = MockWebServer()
        val frameReceived = CountDownLatch(1)
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                frameReceived.countDown()
                return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), httpClient = http)
            val job = launch(Dispatchers.Default) { runCatching { client.liveViewFrame(1) } }
            try {
                assertTrue(withContext(Dispatchers.IO) { frameReceived.await(2, TimeUnit.SECONDS) })
                job.cancel()
                assertTrue(withTimeoutOrNull(1_000) { job.join(); true } ?: false)
                assertEquals(1, server.requestCount)
            } finally {
                http.dispatcher.cancelAll()
                job.cancel()
                job.join()
            }
        } finally { server.shutdown() }
    }

    @Test fun cancellingMultipartFrameWaitDoesNotWaitForItsFifteenSecondDeadline() = runBlocking {
        val server = MockWebServer()
        val http = noRetryHttp()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json(multipartDiscovery)
                request.path?.endsWith("/multipart") == true && request.method == "GET" ->
                    MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=canon")
                        .setBody("--canon\r\n").setBodyDelay(2, TimeUnit.SECONDS)
                else -> json()
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, httpClient = http)
            client.initialize()
            client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_MULTIPART))
            val entered = CountDownLatch(1)
            val job = launch(Dispatchers.Default) {
                entered.countDown()
                runCatching { client.liveViewFrame(1) }
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
                // Let the production consumer enter its monitor wait before cancellation.
                kotlinx.coroutines.delay(50)
                job.cancel()
                assertTrue(withTimeoutOrNull(1_000) { job.join(); true } ?: false)
            } finally {
                client.stopLiveView()
                http.dispatcher.cancelAll()
                job.cancel()
                job.join()
            }
        } finally { server.shutdown() }
    }

    @Test fun manualCaptureReleaseRecoveryRetriesOnlyTheOriginalRelease() = runBlocking {
        val server = MockWebServer()
        val presses = AtomicInteger()
        val releases = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json(manualDiscovery)
                request.path?.endsWith("/manual") == true && action(request) == "full_press" -> {
                    presses.incrementAndGet()
                    MockResponse().setResponseCode(204)
                }
                request.path?.endsWith("/manual") == true && action(request) == "release" ->
                    if (releases.incrementAndGet() == 1) MockResponse().setResponseCode(503).setBody("release rejected")
                    else MockResponse().setResponseCode(204)
                else -> json()
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
            client.initialize()
            assertTrue(runCatching { client.captureStill() }.exceptionOrNull() is ShutterReleaseException)
            assertTrue(runCatching { client.captureStill() }.isFailure)
            assertEquals(1, presses.get())
            client.retryShutterRelease()
            client.retryShutterRelease()
            assertEquals(1, presses.get())
            assertEquals(2, releases.get())
            client.captureStill()
            assertEquals(2, presses.get())
            assertEquals(3, releases.get())
        } finally { server.shutdown() }
    }

    @Test fun readOnlyRequestsKeepConnectionFailureRecovery() = runBlocking {
        val server = MockWebServer()
        val reads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json(manualDiscovery)
                request.path?.endsWith("/deviceinformation") == true -> {
                    if (reads.incrementAndGet() == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else json("""{"productname":"Synthetic Canon Camera","serialnumber":"SYNTHETIC-ONLY","version":"1.0"}""")
                }
                else -> json()
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
            client.initialize()
            assertEquals("Synthetic Canon Camera", client.info().model)
            assertEquals("Disabling mutation replay must not remove existing safe GET recovery", 2, reads.get())
        } finally { server.shutdown() }
    }

    @Test fun stalledMultipartHeadersTimeOutBeforeAutoFallsBackToJpeg() = runBlocking {
        val server = MockWebServer()
        val http = noRetryHttp()
        val streamReceived = CountDownLatch(1)
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val discovery = """{"ver110":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/multipart","get":true,"delete":true},{"path":"/shooting/liveview/flip","get":true}]}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json(discovery)
                request.path?.endsWith("/shooting/liveview") == true && request.method == "POST" -> {
                    starts.incrementAndGet()
                    MockResponse().setResponseCode(204)
                }
                request.path?.endsWith("/shooting/liveview") == true && request.method == "DELETE" -> {
                    stops.incrementAndGet()
                    MockResponse().setResponseCode(204)
                }
                request.path?.endsWith("/multipart") == true && request.method == "GET" -> {
                    streamReceived.countDown()
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                else -> json()
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, httpClient = http)
            client.initialize()
            val outcome = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
            val job = launch(Dispatchers.Default) { outcome.complete(runCatching { client.startLiveView() }) }
            try {
                assertTrue(withContext(Dispatchers.IO) { streamReceived.await(2, TimeUnit.SECONDS) })
                assertTrue("The ten-second opening deadline must complete without user cancellation",
                    withTimeoutOrNull(12_000) { job.join(); true } ?: false)
                outcome.await().getOrThrow()
                assertEquals(2, starts.get())
                assertEquals(1, stops.get())
                assertEquals(LiveViewSource.CCAPI_JPEG_POLLING, client.currentLiveViewSource())
            } finally {
                // Still bounded on the unfixed client: do not leave a blocked test-server socket behind.
                http.dispatcher.cancelAll()
                job.cancel()
                job.join()
                client.stopLiveView()
            }
        } finally { server.shutdown() }
    }

    @Test fun cancellingMediaListingInterruptsItsJsonGetWithoutEndpointFallback() = runBlocking {
        val server = MockWebServer()
        val listingReceived = CountDownLatch(1)
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ccapi" -> json("""{"ver110":[{"path":"/contents","get":true}]}""")
                else -> {
                    listingReceived.countDown()
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, httpClient = http)
            client.initialize()
            val outcome = kotlinx.coroutines.CompletableDeferred<Throwable?>()
            val job = launch(Dispatchers.Default) {
                outcome.complete(runCatching { client.listMedia(20) }.exceptionOrNull())
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { listingReceived.await(2, TimeUnit.SECONDS) })
                job.cancel()
                assertTrue(withTimeoutOrNull(1_000) { job.join(); true } ?: false)
                assertTrue(outcome.await() is kotlinx.coroutines.CancellationException)
                assertEquals("Cancellation must not fall back to another media endpoint", 2, server.requestCount)
            } finally {
                http.dispatcher.cancelAll()
                job.cancel()
                job.join()
            }
        } finally { server.shutdown() }
    }
}
