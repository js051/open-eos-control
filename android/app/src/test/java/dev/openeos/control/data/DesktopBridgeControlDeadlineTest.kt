package dev.openeos.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Independent HTTP fault evidence, not camera timing evidence. In particular, the peer never
 * releases its slow response merely because a coroutine was cancelled. The observation window
 * diagnoses cancellation responsiveness; it does not propose a production camera deadline.
 */
class DesktopBridgeControlDeadlineTest {
    @Test fun defaultTransportHasTenSecondIdleReadButNoWholeCallDeadline() {
        val defaults = OkHttpClient()
        assertEquals("Current production idle-read policy", 10_000, defaults.readTimeoutMillis)
        assertEquals("The inherited production transport has no total call deadline", 0, defaults.callTimeoutMillis)
    }

    @Test fun frozenBaselineFailedStopTransportCompletesWithoutEnteringStatusReadback() = runBlocking<Unit> {
        Peer().use { peer ->
            val baseline = launch(Dispatchers.IO) {
                // Frozen error path from 91abc93 DesktopBridgeClient.kt:444-446, 1005-1012.
                // parseStatus is never reached on this complete non-2xx response. This is a
                // baseline transport control, not a second camera or Bridge implementation.
                runCatching {
                    withContext(Dispatchers.IO) {
                        peer.http.newCall(Request.Builder()
                            .url(peer.server.url("/v1/session/synthetic-deadline/bulb/stop"))
                            .post("{}".toRequestBody())
                            .build()).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            check(response.isSuccessful) { "Baseline stop failed: $body" }
                        }
                    }
                }
            }
            try {
                assertTrue("The completed 502 must terminate the baseline stop error path",
                    baseline.finishesWithinObservation())
                assertEquals(1, peer.count("POST", "bulb/stop"))
                assertEquals(0, peer.count("GET", "status"))
            } finally {
                baseline.cancel()
                peer.abortCalls()
                baseline.join()
            }
        }
    }

    @Test fun failedStopReadbackMustRespectRecoveryBudgetAndAllowSessionDelete() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client()
            client.initialize()
            client.startBulbExposure()
            val stop = launch(Dispatchers.IO) { runCatching { client.stopBulbExposure() } }
            var closing: Job? = null
            try {
                assertTrue("The failed stop must reach the slow status response",
                    peer.awaitStatusBody())
                closing = launch(Dispatchers.IO) { runCatching { client.close() } }
                val stopped = stop.finishesWithinObservation(RECOVERY_BUDGET_MILLIS + OBSERVATION_MILLIS)
                assertEquals("No automatic stop replay", 1, peer.count("POST", "bulb/stop"))
                assertTrue(
                    "Recovery exceeded its app budget behind a completed stop 502 and paced status body: " +
                        "stopFinished=$stopped, callTimeoutMs=${peer.http.callTimeoutMillis}",
                    stopped,
                )
                val deleted = withContext(Dispatchers.IO) {
                    peer.sessionDeleted.await(OBSERVATION_MILLIS, TimeUnit.MILLISECONDS)
                }
                assertTrue("The expired readback must release the mutex so DELETE can be sent", deleted)
            } finally {
                // Explicitly cancel the actual calls even after execute() returned response headers.
                // This is test teardown only, never the mechanism that makes the assertion pass.
                closing?.cancel()
                stop.cancel()
                peer.abortCalls()
                stop.join()
                closing?.join()
            }
        }
    }

    @Test fun cancellingOrdinaryStatusMustAbortHttpWithoutPeerFinishingItsBody() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client()
            client.initialize()
            val reading = launch(Dispatchers.IO) { runCatching { client.status() } }
            try {
                assertTrue("The production client must start consuming the paced response",
                    peer.awaitStatusBody())
                reading.cancel()
                assertTrue(
                    "Status cancellation waited for peer body completion; no test callTimeout masks it",
                    reading.finishesWithinObservation(),
                )
                assertEquals("Normal status keeps its original budget", 0L, peer.statusTimeoutMillis())
            } finally {
                reading.cancel()
                peer.abortCalls()
                reading.join()
            }
        }
    }

    @Test fun successfulFreshRecoveryProofClearsRiskWithoutCancellingCompletedCall() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.paceStatus = false
            val client = peer.client()
            client.initialize()
            client.startBulbExposure()
            val status = client.stopBulbExposure()
            assertEquals(false, status.bulbExposureActive)
            assertEquals(false, status.shutterReleaseUnconfirmed)
            assertEquals(RECOVERY_BUDGET_MILLIS, peer.statusTimeoutMillis())
            client.captureStill()
            assertEquals(1, peer.count("POST", "bulb/start"))
            assertEquals(1, peer.count("POST", "bulb/stop"))
            assertEquals(1, peer.count("POST", "capture/still"))
            assertFalse("Finishing the cancellation watcher must not cancel completed calls", peer.anyCallCancelled())
        }
    }

    @Test fun recoveryDeadlineRetainsRiskUntilAnotherExplicitStopProvesRelease() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client()
            client.initialize()
            client.startBulbExposure()
            val failure = AtomicReference<Throwable?>()
            val stop = launch(Dispatchers.IO) {
                failure.set(runCatching { client.stopBulbExposure() }.exceptionOrNull())
            }
            try {
                assertTrue(peer.awaitStatusBody())
                assertTrue("Recovery GET must expire despite paced body progress",
                    stop.finishesWithinObservation(RECOVERY_BUDGET_MILLIS + OBSERVATION_MILLIS))
                assertTrue("Expiry is unresolved release, never success", failure.get() is ShutterReleaseException)
                assertTrue(runCatching { client.startBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
                assertTrue(runCatching { client.captureStill() }.exceptionOrNull() is ShutterReleaseException)
                assertEquals(1, peer.count("POST", "bulb/start"))
                assertEquals(0, peer.count("POST", "capture/still"))
                assertEquals(1, peer.count("POST", "bulb/stop"))
                peer.failStop = false
                val released = client.stopBulbExposure()
                assertEquals(false, released.shutterReleaseUnconfirmed)
                client.captureStill()
                assertEquals(2, peer.count("POST", "bulb/stop"))
                assertEquals(1, peer.count("GET", "status"))
                assertEquals(1, peer.count("POST", "bulb/start"))
                assertEquals(1, peer.count("POST", "capture/still"))
            } finally {
                stop.cancel()
                peer.abortCalls()
                stop.join()
            }
        }
    }

    @Test fun recoveryReadRetainsAnExistingShorterWholeCallBudget() = runBlocking<Unit> {
        Peer(callTimeoutMillis = 300).use { peer ->
            val client = peer.client()
            client.initialize()
            client.startBulbExposure()
            val failure = AtomicReference<Throwable?>()
            val stop = launch(Dispatchers.IO) {
                failure.set(runCatching { client.stopBulbExposure() }.exceptionOrNull())
            }
            try {
                assertTrue(peer.awaitStatusBody())
                assertTrue("A preconfigured shorter deadline must not become five seconds", stop.finishesWithinObservation())
                assertTrue(failure.get() is ShutterReleaseException)
                assertEquals(300L, peer.statusTimeoutMillis())
            } finally {
                stop.cancel()
                peer.abortCalls()
                stop.join()
            }
        }
    }

    @Test fun cancellingCloseAbortsRequestOkWhileItsBodyIsStillArriving() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.paceDelete = true
            val client = peer.client()
            client.initialize()
            val closing = launch(Dispatchers.IO) { runCatching { client.close() } }
            try {
                assertTrue(peer.awaitDeleteBody())
                closing.cancel()
                assertTrue("requestOk cancellation must cover body consumption", closing.finishesWithinObservation())
            } finally {
                closing.cancel()
                peer.abortCalls()
                closing.join()
            }
        }
    }

    @Test fun callerCancellationDoesNotAbortTheProtectedStopMutation() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.failStop = false
            peer.paceStop = true
            val client = peer.client()
            client.initialize()
            client.startBulbExposure()
            val stop = launch(Dispatchers.IO) { runCatching { client.stopBulbExposure() } }
            try {
                assertTrue(peer.awaitStopBody())
                stop.cancel()
                assertTrue(stop.finishesWithinObservation())
                assertFalse("The release mutation remains NonCancellable", peer.stopCallCancelled())
                assertEquals("The release mutation keeps its original call budget", 0L, peer.stopTimeoutMillis())
                assertEquals(1, peer.count("POST", "bulb/stop"))
                assertEquals(0, peer.count("GET", "status"))
            } finally {
                stop.cancel()
                peer.abortCalls()
                stop.join()
            }
        }
    }

    private suspend fun Job.finishesWithinObservation(windowMillis: Long = OBSERVATION_MILLIS): Boolean =
        withTimeoutOrNull(windowMillis) { join(); true } ?: false

    private class Peer(callTimeoutMillis: Long = 0) : AutoCloseable {
        val server = MockWebServer()
        private val requests = CopyOnWriteArrayList<String>()
        private val calls = CopyOnWriteArrayList<Call>()
        private val statusBodyStarted = CountDownLatch(1)
        private val stopBodyStarted = CountDownLatch(1)
        private val deleteBodyStarted = CountDownLatch(1)
        val sessionDeleted = CountDownLatch(1)
        @Volatile var paceStatus = true
        @Volatile var failStop = true
        @Volatile var paceStop = false
        @Volatile var paceDelete = false
        val http = OkHttpClient.Builder()
            // Preserve production's lack of a total call deadline. Paced bytes arrive well
            // inside the shorter idle timeout, demonstrating why readTimeout is insufficient.
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls += call }
                override fun responseBodyStart(call: Call) {
                    if (call.request().url.encodedPath.endsWith("/status")) statusBodyStarted.countDown()
                    if (call.request().url.encodedPath.endsWith("/bulb/stop")) stopBodyStarted.countDown()
                    if (call.request().method == "DELETE") deleteBodyStarted.countDown()
                }
            })
            .build()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests += "${request.method} $path"
                    return when {
                        path == "/health" -> json("""{"service":"open-eos-control-bridge"}""")
                        path == "/v1/session" -> json("""{"id":"synthetic-deadline"}""").setResponseCode(201)
                        path.endsWith("/bulb/start") -> json("""{"connected":true,"bulbExposureActive":true,"shutterReleaseUnconfirmed":false}""")
                        path.endsWith("/bulb/stop") -> if (failStop) {
                            json("""{"error":{"code":"CCAPI_UNREACHABLE","message":"Synthetic completed stop failure"}}""")
                                .setResponseCode(502)
                        } else {
                            json(RELEASED_STATUS).also { if (paceStop) it.throttleBody(8, 25, TimeUnit.MILLISECONDS) }
                        }
                        path.endsWith("/status") -> if (paceStatus) pacedStatus() else json(RELEASED_STATUS)
                        path.endsWith("/capture/still") -> json(RELEASED_STATUS)
                        request.method == "DELETE" -> {
                            sessionDeleted.countDown()
                            if (paceDelete) pacedStatus() else MockResponse().setResponseCode(204)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }

        fun client() = DesktopBridgeClient(server.url("/").toString(), httpClient = http)
        fun count(method: String, suffix: String) = requests.count {
            it == "$method /v1/session/synthetic-deadline/$suffix"
        }
        suspend fun awaitStatusBody() = withContext(Dispatchers.IO) {
            statusBodyStarted.await(3, TimeUnit.SECONDS)
        }
        suspend fun awaitStopBody() = withContext(Dispatchers.IO) {
            stopBodyStarted.await(3, TimeUnit.SECONDS)
        }
        suspend fun awaitDeleteBody() = withContext(Dispatchers.IO) {
            deleteBodyStarted.await(3, TimeUnit.SECONDS)
        }
        fun statusTimeoutMillis() = calls.single { it.request().url.encodedPath.endsWith("/status") }
            .timeout().timeoutNanos().let(TimeUnit.NANOSECONDS::toMillis)
        fun anyCallCancelled() = calls.any(Call::isCanceled)
        fun stopCallCancelled() = calls.single { it.request().url.encodedPath.endsWith("/bulb/stop") }.isCanceled()
        fun stopTimeoutMillis() = calls.single { it.request().url.encodedPath.endsWith("/bulb/stop") }
            .timeout().timeoutNanos().let(TimeUnit.NANOSECONDS::toMillis)
        fun abortCalls() = calls.forEach(Call::cancel)
        override fun close() {
            abortCalls()
            server.shutdown()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
        private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        private fun pacedStatus() = json("{" + " ".repeat(512) + RELEASED_STATUS.drop(1))
            .throttleBody(1, 25, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val OBSERVATION_MILLIS = 1_500L
        // An app recovery policy, not a Canon camera timing promise.
        private const val RECOVERY_BUDGET_MILLIS = 5_000L
        private const val RELEASED_STATUS = "{\"connected\":true,\"bulbExposureActive\":false,\"shutterReleaseUnconfirmed\":false}"
    }
}
