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

    @Test fun partialAfStopReceiptsKeepOriginalStopUntilCompleteRetry() = runBlocking {
        for (partial in partialResponses()) Scenario().use { s ->
            s.client.initialize()
            s.stopResponse = { MockResponse().setResponseCode(503).setBody("busy") }
            assertTrue(runCatching { s.client.holdAutofocus {} }.exceptionOrNull() is AutofocusReleaseException)
            s.stopResponse = partial
            assertTrue(runCatching { s.client.retryAutofocusStop() }.exceptionOrNull() is AutofocusReleaseException)
            val before = s.events.toList()
            assertTrue(runCatching { s.client.holdAutofocus {} }.isFailure)
            assertEquals(before, s.events.toList())
            s.stopResponse = { MockResponse().setResponseCode(204) }
            s.client.retryAutofocusStop(); s.client.retryAutofocusStop()
            assertEquals(listOf("POST:af:start", "POST:af:stop", "POST:af:stop", "POST:af:stop"), s.events.toList())
            s.client.holdAutofocus {}
            assertEquals(6, s.events.size)
        }
    }

    @Test fun partialBulbReceiptsKeepOriginalPutReleaseUntilCompleteRetry() = runBlocking {
        for (partial in partialResponses()) Scenario(manualMethod = "PUT").use { s ->
            s.client.initialize(); s.client.startBulbExposure()
            s.stopResponse = { MockResponse().setResponseCode(503).setBody("busy") }
            assertTrue(runCatching { s.client.stopBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
            s.stopResponse = partial
            assertTrue(runCatching { s.client.retryShutterRelease() }.exceptionOrNull() is ShutterReleaseException)
            val before = s.events.toList()
            assertTrue(runCatching { s.client.startBulbExposure() }.isFailure)
            assertEquals(before, s.events.toList())
            s.stopResponse = { MockResponse().setResponseCode(204) }
            s.client.retryShutterRelease(); s.client.retryShutterRelease()
            assertEquals(listOf("PUT:manual:full_press", "PUT:manual:release", "PUT:manual:release", "PUT:manual:release"), s.events.toList())
            s.client.startBulbExposure(); s.client.stopBulbExposure()
            assertEquals(6, s.events.size)
        }
    }

    @Test fun partialStartsCannotPublishHeldStateButStillRelease() = runBlocking {
        for (partial in partialResponses()) Scenario().use { s ->
            s.client.initialize(); s.startResponse = partial
            var held = false
            assertTrue(runCatching { s.client.holdAutofocus { held = true } }.isFailure)
            assertFalse(held)
            assertTrue(runCatching { s.client.startBulbExposure() }.isFailure)
            s.client.retryAutofocusStop(); s.client.retryShutterRelease()
            assertEquals(listOf("POST:af:start", "POST:af:stop", "POST:manual:full_press", "POST:manual:release"), s.events.toList())
        }
    }

    @Test fun completeControlAckKeepsLegacyBodyCompatibility() = runBlocking {
        val successes: List<() -> MockResponse> = listOf(
            { MockResponse().setBody("{}") }, { MockResponse().setResponseCode(204) },
            { MockResponse().setBody("") }, { MockResponse().setHeader("Content-Type", "text/plain").setBody("Accepted") },
            { MockResponse().setResponseCode(202).setBody("Accepted") },
        )
        for (success in successes) Scenario().use { s ->
            s.client.initialize(); s.startResponse = success; s.stopResponse = success
            s.client.holdAutofocus {}; s.client.startBulbExposure(); s.client.stopBulbExposure()
            s.client.retryAutofocusStop(); s.client.retryShutterRelease()
            assertEquals(4, s.events.size)
        }
    }

    @Test fun cancelledExplicitBulbStopRemainsJoinedUntilItsResponseCloses() = runBlocking {
        val probe = ControlBodyProbe(holdFirst = false, holdSecond = true)
        withObservedScenario(http = probe.http) { s ->
            s.startResponse = { MockResponse().setBody("{}") }
            s.stopResponse = { MockResponse().setBody("{}") }
            s.client.initialize()
            assertEquals(true, s.client.startBulbExposure().bulbExposureActive)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val job = launch(Dispatchers.IO) {
                try { s.client.stopBulbExposure() } catch (error: Throwable) { failure.set(error) }
            }
            try {
                awaitGate("Explicit Bulb Stop must enter its actual response body", probe.second.bodyEntered)
                job.cancel()
                val detached = withTimeoutOrNull(80) { job.join(); true }
                assertNull("Cancellation cannot detach a gated NonCancellable Stop", detached)
                assertEquals(1L, probe.second.closeReturned.count)
                probe.second.allowBody.countDown()
                withTimeout(5_000) { job.join() }
                probe.assertHealthy()
                probe.second.assertOriginalBodyClosed()
                assertTrue("A completed release may only be followed by outer cancellation", failure.get() == null || failure.get() is CancellationException)
                assertFalse(failureGraph(failure.get()).any { it is ShutterReleaseException })
                assertEquals(false, s.client.status().bulbExposureActive)
                s.client.retryShutterRelease(); s.client.retryShutterRelease()
                assertEquals(listOf("POST:manual:full_press", "POST:manual:release"), s.events.toList())
                assertEquals(2, probe.commandCount())
            } catch (error: Throwable) {
                probe.retainObserverFailure(error)
                throw error
            } finally {
                probe.openAll()
                withContext(NonCancellable) { withTimeout(5_000) { job.cancelAndJoin() } }
            }
        }
    }

    @Test fun simulatorControlEntriesPreserveExactRequestsWithoutNativeCommands() = runBlocking {
        val server = MockWebServer()
        val writes = CopyOnWriteArrayList<Triple<String, String, String>>()
        val reads = CopyOnWriteArrayList<String>()
        val observerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = try {
                if (request.method == "GET") {
                    reads += request.path!!
                    check(request.path == "/ccapi/status")
                    MockResponse().setBody("""{"connected":true,"recording":false,"mode":"photo",
                        "battery":{"level":82,"status":"normal"},"media":{"available":true},
                        "exposure":{"iso":"800","shutter":"1/50","aperture":"2.8","white_balance":"auto"}}""")
                } else {
                    writes += Triple(request.method!!, request.path!!, request.body.readUtf8())
                    MockResponse().setBody("{}")
                }
            } catch (error: Throwable) {
                observerFailure.compareAndSet(null, error)
                MockResponse().setResponseCode(500).setBody("fixture observation failed")
            }
        }
        server.start()
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
            client.initialize()
            client.autofocus(); client.halfPressShutter()
            assertEquals(true, client.startBulbExposure().bulbExposureActive)
            client.stopBulbExposure()
            client.retryAutofocusStop(); client.retryShutterRelease()
            observerFailure.get()?.let { throw AssertionError("Simulator dispatcher failed", it) }
            assertEquals(listOf(
                "/ccapi/shutter/half-press", "/ccapi/shutter/release",
                "/ccapi/shutter/half-press", "/ccapi/shutter/release",
                "/ccapi/bulb/start", "/ccapi/bulb/stop",
            ).map { Triple("POST", it, "{}") }, writes.toList())
            assertEquals(List(4) { "/ccapi/status" }, reads.toList())
            assertFalse(writes.any { it.second.contains("/shooting/control/") })
        } catch (error: Throwable) {
            observerFailure.get()?.let { observer ->
                if (observer !== error) error.addSuppressed(observer)
            }
            throw error
        } finally { server.shutdown() }
    }

    @Test fun cancelledControlStartsCloseTheirResponseBeforeSavedReleaseCompletes() = runBlocking {
        for (bulb in listOf(false, true)) {
            val probe = ControlBodyProbe(holdFirst = true, holdSecond = true)
            withObservedScenario(http = probe.http) { s ->
                s.startResponse = { MockResponse().setBody("{}") }
                s.stopResponse = { MockResponse().setBody("{}") }
                s.client.initialize()
                var held = false
                val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                val job = launch(Dispatchers.IO) {
                    try {
                        if (bulb) s.client.startBulbExposure() else s.client.holdAutofocus { held = true }
                    } catch (error: Throwable) { failure.set(error) }
                }
                try {
                    awaitGate("Start must enter its actual response body: bulb=$bulb", probe.first.bodyEntered)
                    job.cancel()
                    awaitGate("Start cancellation must reach the actual Call: bulb=$bulb", probe.first.cancelled)
                    // EventListener runs synchronously in read(); cancel cannot release this fixture gate.
                    probe.first.allowBody.countDown()
                    awaitGate("Cancellation must dispatch the saved release: bulb=$bulb", probe.second.bodyEntered)
                    assertEquals(true, probe.second.previousBodyClosedAtCallStart.get())
                    probe.first.assertOriginalBodyClosed()
                    val detached = withTimeoutOrNull(80) { job.join(); true }
                    assertNull("The original operation must still own its gated release", detached)
                    assertEquals(1L, probe.second.closeReturned.count)
                    probe.second.allowBody.countDown()
                    withTimeout(5_000) { job.join() }
                    probe.assertHealthy()
                    probe.second.assertOriginalBodyClosed()
                    assertNotNull("Cancelled Start must not report success", failure.get())
                    assertTrue(failure.get() is CancellationException || failure.get() is java.io.IOException)
                    assertFalse(failureGraph(failure.get()).any { it is AutofocusReleaseException || it is ShutterReleaseException })
                    assertFalse(held)
                    if (bulb) {
                        assertEquals(false, s.client.status().bulbExposureActive)
                        s.client.retryShutterRelease(); s.client.retryShutterRelease()
                        assertEquals(listOf("POST:manual:full_press", "POST:manual:release"), s.events.toList())
                    } else {
                        s.client.retryAutofocusStop(); s.client.retryAutofocusStop()
                        assertEquals(listOf("POST:af:start", "POST:af:stop"), s.events.toList())
                    }
                    assertEquals(2, probe.commandCount())
                } catch (error: Throwable) {
                    probe.retainObserverFailure(error)
                    throw error
                } finally {
                    probe.openAll()
                    withContext(NonCancellable) { withTimeout(5_000) { job.cancelAndJoin() } }
                }
            }
        }
    }

    @Test fun failedControlReleaseReceiptsPreserveTypedEvidenceAndStopOnlyRecovery() = runBlocking {
        val cases: List<Triple<String, Boolean, () -> MockResponse>> = listOf(
            Triple("AF declared oversize", false) { MockResponse().setBody(" ".repeat(65_537)) },
            Triple("AF HTTP 400", false) { MockResponse().setResponseCode(400).setHeader("Content-Type", "text/plain").setBody("busy") },
            Triple("Bulb declared oversize", true) { MockResponse().setBody(" ".repeat(65_537)) },
            Triple("Bulb truncated body", true) { MockResponse().setBody("{}").setHeader("Content-Length", 12).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END) },
        )
        for ((label, bulb, response) in cases) withObservedScenario { s ->
            s.client.initialize()
            s.stopResponse = response
            var held = false
            val failure = if (bulb) {
                assertEquals(true, s.client.startBulbExposure().bulbExposureActive)
                runCatching { s.client.stopBulbExposure() }.exceptionOrNull()
            } else runCatching { s.client.holdAutofocus { held = true } }.exceptionOrNull()
            if (bulb) {
                assertTrue(label, failure is ShutterReleaseException)
                assertEquals("Unconfirmed release must remain publicly active: $label", true, s.client.status().bulbExposureActive)
            } else {
                assertTrue(label, failure is AutofocusReleaseException)
                assertTrue("A complete Start ACK reaches the held callback", held)
            }
            if (label == "AF HTTP 400") {
                assertTrue("Preserve the received HTTP status through the real cause chain",
                    failureGraph(failure).any { it is ConnectionHttpFailure && it.statusCode == 400 })
            }
            val before = s.events.toList()
            if (bulb) assertTrue(runCatching { s.client.startBulbExposure() }.isFailure)
            else assertTrue(runCatching { s.client.holdAutofocus {} }.isFailure)
            assertEquals("An uncertain release must not replay Start: $label", before, s.events.toList())
            s.stopResponse = { MockResponse().setResponseCode(204) }
            if (bulb) {
                s.client.retryShutterRelease(); s.client.retryShutterRelease()
                assertEquals("Complete release clears active state: $label", false, s.client.status().bulbExposureActive)
                assertEquals(listOf("POST:manual:full_press", "POST:manual:release", "POST:manual:release"), s.events.toList())
            } else {
                s.client.retryAutofocusStop(); s.client.retryAutofocusStop()
                assertEquals(listOf("POST:af:start", "POST:af:stop", "POST:af:stop"), s.events.toList())
            }
        }
    }

    private suspend fun withObservedScenario(http: OkHttpClient? = null, block: suspend (Scenario) -> Unit) {
        val observerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        Scenario(http = http).use { scenario ->
            val originalDispatcher = scenario.server.dispatcher
            scenario.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = try {
                    originalDispatcher.dispatch(request)
                } catch (error: Throwable) {
                    observerFailure.compareAndSet(null, error)
                    MockResponse().setResponseCode(500).setBody("fixture observation failed")
                }
            }
            try {
                block(scenario)
                observerFailure.get()?.let { throw AssertionError("Scenario dispatcher failed", it) }
            } catch (error: Throwable) {
                observerFailure.get()?.let { observer ->
                    if (observer !== error) error.addSuppressed(observer)
                }
                throw error
            }
        }
    }

    private suspend fun awaitGate(message: String, gate: CountDownLatch) {
        assertTrue(message, withContext(Dispatchers.IO) { gate.await(2, TimeUnit.SECONDS) })
    }

    private fun failureGraph(failure: Throwable?): List<Throwable> {
        val result = mutableListOf<Throwable>()
        val pending = java.util.ArrayDeque<Throwable>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        failure?.let(pending::add)
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            if (!seen.add(item)) continue
            check(result.size < 64) { "Unexpected exception graph size" }
            result += item
            item.cause?.let(pending::add)
            item.suppressed.forEach(pending::add)
        }
        return result
    }

    /** Opt-in public OkHttp observation. No alternate Call, body bytes, or production reader. */
    private class ControlBodyProbe(holdFirst: Boolean, holdSecond: Boolean) {
        class Receipt(held: Boolean) {
            val bodyEntered = CountDownLatch(1)
            val allowBody = CountDownLatch(if (held) 1 else 0)
            val cancelled = CountDownLatch(1)
            val closeReturned = CountDownLatch(1)
            val call = java.util.concurrent.atomic.AtomicReference<Call?>()
            val originalBody = java.util.concurrent.atomic.AtomicReference<okhttp3.ResponseBody?>()
            val closedBody = java.util.concurrent.atomic.AtomicReference<okhttp3.ResponseBody?>()
            val previousBodyClosedAtCallStart = java.util.concurrent.atomic.AtomicReference<Boolean?>()
            fun assertOriginalBodyClosed() {
                assertNotNull(call.get())
                assertNotNull(originalBody.get())
                assertEquals(0L, closeReturned.count)
                assertSame("The exact response body must return from close", originalBody.get(), closedBody.get())
            }
        }
        val first = Receipt(holdFirst)
        val second = Receipt(holdSecond)
        private val count = java.util.concurrent.atomic.AtomicInteger()
        private val observerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        private val receipts = java.util.concurrent.ConcurrentHashMap<Call, Receipt>()
        val http = OkHttpClient.Builder().eventListenerFactory { call ->
            if (call.request().method == "GET") EventListener.NONE else {
                val receipt = when (count.incrementAndGet()) {
                    1 -> first
                    2 -> second
                    else -> Receipt(false)
                }
                receipt.call.set(call)
                receipts[call] = receipt
                object : EventListener() {
                    override fun callStart(call: Call) {
                        if (receipt === second) receipt.previousBodyClosedAtCallStart.set(first.closeReturned.count == 0L)
                    }
                    override fun responseBodyStart(call: Call) {
                        receipt.bodyEntered.countDown()
                        observe {
                            check(receipt.allowBody.await(5, TimeUnit.SECONDS)) { "Response-body fixture gate timed out" }
                        }
                    }
                    override fun canceled(call: Call) { receipt.cancelled.countDown() }
                }
            }
        }.addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val receipt = receipts[chain.call()]
            val original = response.body
            if (receipt == null || original == null) response else {
                receipt.originalBody.set(original)
                response.newBuilder().body(object : okhttp3.ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = original.source()
                    override fun close() = observe {
                        original.close()
                        receipt.closedBody.set(original)
                        receipt.closeReturned.countDown()
                    }
                }).build()
            }
        }.build()
        private fun <T> observe(block: () -> T): T = try { block() } catch (error: Throwable) {
            observerFailure.compareAndSet(null, error)
            throw error
        }
        fun commandCount() = count.get()
        fun openAll() { first.allowBody.countDown(); second.allowBody.countDown() }
        fun assertHealthy() { observerFailure.get()?.let { throw AssertionError("OkHttp observer failed", it) } }
        fun retainObserverFailure(primary: Throwable) {
            observerFailure.get()?.let { observer -> if (observer !== primary) primary.addSuppressed(observer) }
        }
    }

    private fun partialResponses(): List<() -> MockResponse> = listOf(
        { MockResponse().setResponseCode(206).setBody("{}") },
        { MockResponse().setHeader("Content-Range", "bytes 0-1/4").setBody("{}") },
        { MockResponse().setHeader("cOnTeNt-RaNgE", "").setBody("{}") },
    )

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
