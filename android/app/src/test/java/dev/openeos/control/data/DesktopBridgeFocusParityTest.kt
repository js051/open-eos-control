package dev.openeos.control.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic independent Bridge peer only; these fixtures do not establish camera support. */
class DesktopBridgeFocusParityTest {
    @Test fun explicitCapabilitiesReachBackendAndExistingFocusModels() = runBlocking {
        Peer().use { peer ->
            val backend = DesktopBridgeCameraBackend(CameraConnection.DesktopBridge(peer.url), CameraHttpTransportFactory {
                CameraHttpTransport(client = peer.http(), diagnostics = CameraNetworkDiagnostics())
            })
            backend.initialize()
            val caps = backend.capabilities()
            assertTrue(caps.heldAutofocusSupported)
            assertTrue(caps.liveView.focusInfoSupported)
            backend.holdAutofocus {}
            backend.retryAutofocusStop()
            assertEquals(1, peer.count("focus/auto/start"))
            assertEquals(1, peer.count("focus/auto/stop"))
            assertNull(backend.liveViewFocusInfo())
            backend.startLiveView()
            assertEquals(CameraFocusStatus.FOCUSED, backend.liveViewFocusInfo()!!.frames.single().status)
            backend.close()
        }
    }

    @Test fun oldMissingNullFalseAndCoercedCapabilitiesNeverCreateRequests() = runBlocking {
        for (value in listOf<String?>(null, "null", "false", "\"true\"", "1", "{}")) Peer().use { peer ->
            peer.capabilityFields = value?.let { ",\"heldAutofocusSupported\":$it,\"focusInfoSupported\":$it" }.orEmpty()
            val client = peer.ready()
            val caps = client.capabilities()
            assertFalse(caps.heldAutofocusSupported)
            assertFalse(caps.liveView.focusInfoSupported)
            assertTrue(runCatching { client.holdAutofocus {} }.isFailure)
            client.retryAutofocusStop()
            client.startLiveView(LiveViewRequest())
            assertNull(client.liveViewFocusInfo())
            assertEquals(0, peer.count("focus/auto/start"))
            assertEquals(0, peer.count("focus/auto/stop"))
            assertEquals(0, peer.count("liveview/focus-info"))
        }
    }

    @Test fun parentFeatureAndLiveViewSourceMustAlsoBeAdvertised() = runBlocking {
        Peer().use { peer ->
            peer.supported = "[]"
            val client = peer.ready()
            assertFalse(client.capabilities().heldAutofocusSupported)
            assertFalse(client.capabilities().liveView.focusInfoSupported)
        }
        Peer().use { peer ->
            peer.sources = "[]"
            assertFalse(peer.ready().capabilities().liveView.focusInfoSupported)
        }
    }

    @Test fun heldStartStopDoNotReadStatusOrReplayBoundedAutofocus() = runBlocking {
        Peer().use { peer ->
            val client = peer.ready()
            repeat(2) { client.holdAutofocus {} }
            val starts = peer.focusCommands.filter { it.second }
            val stops = peer.focusCommands.filterNot { it.second }
            assertEquals(2, starts.size)
            assertEquals(starts.map { it.first }, stops.map { it.first })
            assertEquals(2, starts.map { it.first }.toSet().size)
            assertTrue(starts.all { it.first.matches(Regex("[A-Za-z0-9._:-]{1,128}")) })
            assertEquals(0, peer.count("status"))
            assertEquals(0, peer.count("focus/auto"))
        }
    }

    @Test fun unknownStartAndFailedStopRetainOriginalOwnerForStopOnlyRetry() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.start = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            peer.stop = { peer.error("SHUTTER_RELEASE_UNCONFIRMED") }
            val client = peer.ready()
            assertTrue(runCatching { client.holdAutofocus {} }.exceptionOrNull() is AutofocusReleaseException)
            assertTrue(runCatching { client.holdAutofocus {} }.isFailure)
            assertEquals(1, peer.count("focus/auto/start"))
            assertEquals(true, client.status().shutterReleaseUnconfirmed)
            assertNull(client.status().heldAutofocusActive)
            assertTrue(runCatching { client.captureStill() }.exceptionOrNull() is AutofocusReleaseException)
            assertEquals(0, peer.count("capture/still"))
            peer.stop = { owner -> peer.ack(owner, false) }
            client.retryShutterRelease()
            client.retryAutofocusStop()
            assertEquals(2, peer.count("focus/auto/stop"))
            assertEquals(1, peer.focusCommands.map { it.first }.toSet().size)
            assertEquals(0, peer.count("bulb/stop"))
            client.captureStill()
        }
    }

    @Test fun wrongOwnerOrMalformedAcknowledgementNeverConfirmsRelease() = runBlocking {
        for (body in listOf(
            "{\"ownerId\":\"other-owner\",\"active\":false,\"stopUnconfirmed\":false}",
            "{\"active\":false,\"stopUnconfirmed\":false}",
            "{\"active\":\"false\",\"stopUnconfirmed\":false}",
            "null", "{}", "not JSON",
        )) Peer().use { peer ->
            peer.stop = { peer.json(body) }
            val client = peer.ready()
            assertTrue(runCatching { client.holdAutofocus {} }.exceptionOrNull() is AutofocusReleaseException)
            peer.stop = { owner -> peer.ack(owner, false) }
            client.retryAutofocusStop()
            assertEquals(1, peer.count("focus/auto/start"))
            assertEquals(2, peer.count("focus/auto/stop"))
        }
    }

    @Test fun unknownOrMalformedStartNeverEntersHeldCallback() = runBlocking {
        for (response in listOf(
            "{}", "null", "not JSON",
            "{\"ownerId\":\"other-owner\",\"active\":true,\"stopUnconfirmed\":false}",
        )) Peer().use { peer ->
            peer.start = { peer.json(response) }
            val client = peer.ready()
            assertTrue(runCatching { client.holdAutofocus { fail("Unconfirmed start") } }.isFailure)
            assertEquals(1, peer.count("focus/auto/start"))
            assertEquals(1, peer.count("focus/auto/stop"))
        }
    }

    @Test fun matchingOwnerStillRequiresExactPositiveStartAndNegativeStopEvidence() = runBlocking {
        for ((field, value) in listOf("active" to JSONObject.NULL, "active" to "true", "active" to false,
            "stopUnconfirmed" to true, "stopUnconfirmed" to JSONObject.NULL, "stopUnconfirmed" to "false")) Peer().use { peer ->
            peer.start = { owner -> peer.json(JSONObject().put("ownerId", owner).put("active", true)
                .put("stopUnconfirmed", false).put(field, value).toString()) }
            val client = peer.ready()
            assertTrue(runCatching { client.holdAutofocus { fail("Malformed positive acknowledgement") } }.isFailure)
            assertEquals(1, peer.count("focus/auto/stop"))
        }
        for ((field, value) in listOf("active" to JSONObject.NULL, "active" to "false", "active" to true,
            "stopUnconfirmed" to true, "stopUnconfirmed" to JSONObject.NULL, "stopUnconfirmed" to "false")) Peer().use { peer ->
            peer.stop = { owner -> peer.json(JSONObject().put("ownerId", owner).put("active", false)
                .put("stopUnconfirmed", false).put(field, value).toString()) }
            val client = peer.ready()
            assertTrue(runCatching { client.holdAutofocus {} }.exceptionOrNull() is AutofocusReleaseException)
            peer.stop = { owner -> peer.ack(owner, false) }
            client.retryAutofocusStop()
            assertEquals(2, peer.count("focus/auto/stop"))
        }
    }

    @Test fun failedStopHttp503IsRetriedOnlyWhenRequested() = runBlocking {
        Peer().use { peer ->
            peer.stop = { peer.error("SHUTTER_RELEASE_UNCONFIRMED", 503).setHeader("Retry-After", "0") }
            val client = peer.ready()
            assertTrue(runCatching { client.holdAutofocus {} }.exceptionOrNull() is AutofocusReleaseException)
            assertEquals(1, peer.count("focus/auto/stop"))
            assertTrue(runCatching { client.retryAutofocusStop() }.exceptionOrNull() is AutofocusReleaseException)
            assertEquals(2, peer.count("focus/auto/stop"))
            assertEquals(1, peer.count("focus/auto/start"))
        }
    }

    @Test fun mutationHttpRetriesAndRedirectsCannotReplayStartOrStop() = runBlocking {
        for (code in listOf(302, 307, 308, 408, 503)) Peer().use { peer ->
            peer.start = { peer.error("SYNTHETIC", code).setHeader("Retry-After", "0").setHeader("Location", "/replayed") }
            val client = peer.ready()
            assertTrue(runCatching { client.holdAutofocus {} }.isFailure)
            assertEquals(1, peer.count("focus/auto/start"))
            assertEquals(1, peer.count("focus/auto/stop"))
            assertEquals(0, peer.count("replayed"))
        }
    }

    @Test fun cancelledStartReleasesSameOwnerAndSameSession() = runBlocking {
        Peer().use { peer ->
            val received = CountDownLatch(1)
            peer.start = { owner -> received.countDown(); peer.ack(owner, true).setBodyDelay(5, TimeUnit.SECONDS) }
            val client = peer.ready()
            val job = launch(Dispatchers.IO) { client.holdAutofocus { awaitCancellation() } }
            assertTrue(withContext(Dispatchers.IO) { received.await(2, TimeUnit.SECONDS) })
            withTimeout(2_000) { job.cancelAndJoin() }
            assertEquals(1, peer.count("focus/auto/start"))
            assertEquals(1, peer.count("focus/auto/stop"))
            assertEquals(1, peer.focusCommands.map { it.first }.toSet().size)
        }
    }

    @Test fun closeAttemptsDeleteEvenWhenFocusReleaseFails() = runBlocking {
        Peer().use { peer ->
            peer.stop = { peer.error("SHUTTER_RELEASE_UNCONFIRMED") }
            val client = peer.ready()
            assertTrue(runCatching { client.holdAutofocus {} }.isFailure)
            assertTrue(runCatching { client.close() }.exceptionOrNull() is AutofocusReleaseException)
            assertEquals(2, peer.count("focus/auto/stop"))
            assertTrue(peer.requests.contains("DELETE /v1/session/synthetic-session"))
            peer.stop = { owner -> peer.ack(owner, false) }
            client.initialize() // Intentionally reuse the server ID; client identity still changes.
            client.capabilities()
            client.retryAutofocusStop()
            assertEquals(2, peer.count("focus/auto/stop"))
            client.holdAutofocus {}
            assertEquals(2, peer.focusCommands.filter { it.second }.map { it.first }.toSet().size)
        }
    }

    @Test fun cancellingCloseFinishesOwnedFocusStopAndRetiresSession() = runBlocking<Unit> {
        Peer().use { peer ->
            val stopReceived = CountDownLatch(1)
            val respondToStop = CountDownLatch(1)
            peer.stop = { owner ->
                stopReceived.countDown()
                check(respondToStop.await(3, TimeUnit.SECONDS))
                peer.ack(owner, false)
            }
            val client = peer.ready()
            val held = CompletableDeferred<Unit>()
            val releaseHold = CompletableDeferred<Unit>()
            val holding = launch { client.holdAutofocus { held.complete(Unit); releaseHold.await() } }
            held.await()
            val closing = launch(Dispatchers.IO) { client.close() }
            try {
                assertTrue(withContext(Dispatchers.IO) { stopReceived.await(2, TimeUnit.SECONDS) })
                closing.cancel()
                respondToStop.countDown()
                withTimeout(2_000) { closing.join() }
                val stopCall = peer.calls.single { it.request().url.encodedPath.endsWith("/focus/auto/stop") }
                assertFalse("Caller cancellation must not abort its owned focus release", stopCall.isCanceled())
                assertTrue("Protected focus release must finish before local retirement", stopCall in peer.finishedCalls)
                assertFalse("A cancelled caller must not begin the session DELETE", peer.requests.any { it.startsWith("DELETE ") })
                client.initialize() // Reusing the wire ID must not retain the retired session.
                client.capabilities()
                releaseHold.complete(Unit)
                holding.join()
                assertEquals(1, peer.count("focus/auto/stop"))
                client.holdAutofocus {}
                assertEquals(2, peer.count("focus/auto/start"))
                assertEquals(2, peer.count("focus/auto/stop"))
            } finally {
                respondToStop.countDown()
                closing.cancelAndJoin()
                releaseHold.complete(Unit)
                holding.cancelAndJoin()
            }
        }
    }

    @Test fun cancelledCloseWithFailedFocusStopStillAttemptsBoundedSessionDelete() = runBlocking<Unit> {
        for ((callTimeoutMillis, paceDelete) in listOf(8_000L to false, 300L to false, 300L to true)) Peer(callTimeoutMillis).use { peer ->
            peer.stop = { peer.error("SHUTTER_RELEASE_UNCONFIRMED") }
            if (paceDelete) peer.delete = {
                MockResponse().setBody(" ".repeat(512)).throttleBody(1, 25, TimeUnit.MILLISECONDS)
            }
            val client = peer.ready()
            assertTrue(runCatching { client.holdAutofocus {} }.exceptionOrNull() is AutofocusReleaseException)
            val stopReceived = CountDownLatch(1)
            val respondToStop = CountDownLatch(1)
            peer.stop = {
                stopReceived.countDown()
                check(respondToStop.await(3, TimeUnit.SECONDS))
                peer.error("SHUTTER_RELEASE_UNCONFIRMED")
            }
            val failure = CompletableDeferred<Throwable?>()
            val closing = launch(Dispatchers.IO) {
                failure.complete(runCatching { client.close() }.exceptionOrNull())
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { stopReceived.await(2, TimeUnit.SECONDS) })
                closing.cancel()
                respondToStop.countDown()
                withTimeout(2_000) { closing.join() }
                val closeFailure = failure.await()
                val deleteCall = peer.calls.single { it.request().method == "DELETE" }
                assertEquals("Remote cleanup must be dispatched exactly once", 1,
                    peer.requests.count { it == "DELETE /v1/session/synthetic-session" })
                if (paceDelete) {
                    assertTrue("Paced cleanup must expire within its existing short deadline", closeFailure is IOException)
                    assertTrue("Cleanup expiry must retain the original release failure",
                        requireNotNull(closeFailure).suppressed.any { it is AutofocusReleaseException })
                    assertTrue("The deadline must abort the paced DELETE", deleteCall.isCanceled())
                } else {
                    assertTrue("The original release failure must stay visible", closeFailure is AutofocusReleaseException)
                    assertTrue("Remote cleanup must finish before local retirement", deleteCall in peer.finishedCalls)
                    assertFalse("Failed release cleanup remains protected", deleteCall.isCanceled())
                }
                assertEquals(minOf(callTimeoutMillis, 5_000L),
                    TimeUnit.NANOSECONDS.toMillis(deleteCall.timeout().timeoutNanos()))
                assertEquals(1, peer.focusCommands.map { it.first }.toSet().size)
                assertEquals(1, peer.count("focus/auto/start"))
                assertEquals(2, peer.count("focus/auto/stop"))
                client.initialize()
                client.capabilities()
                client.retryAutofocusStop()
                assertEquals(2, peer.count("focus/auto/stop"))
            } finally {
                respondToStop.countDown()
                closing.cancelAndJoin()
            }
        }
    }

    @Test fun oldHeldCallbackCannotReleaseReinitializedSessionWithSameWireId() = runBlocking {
        Peer().use { peer ->
            val client = peer.ready()
            val firstHeld = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val first = launch { client.holdAutofocus { firstHeld.complete(Unit); releaseFirst.await() } }
            firstHeld.await()
            client.close()
            client.initialize()
            client.capabilities()
            val secondHeld = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()
            val second = launch { client.holdAutofocus { secondHeld.complete(Unit); releaseSecond.await() } }
            secondHeld.await()
            releaseFirst.complete(Unit)
            first.join()
            assertEquals(1, peer.count("focus/auto/stop"))
            releaseSecond.complete(Unit)
            second.join()
            assertEquals(2, peer.count("focus/auto/stop"))
            assertNotEquals(peer.focusCommands[0].first, peer.focusCommands[2].first)
        }
    }

    @Test fun focusInfoNeedsActiveAdvertisedPreviewAndNeverFansOutToStatus() = runBlocking {
        Peer().use { peer ->
            val client = peer.ready()
            assertNull(client.liveViewFocusInfo())
            assertEquals(0, peer.count("liveview/focus-info"))
            client.startLiveView(LiveViewRequest())
            val snapshot = peer.requests.toList()
            assertEquals(1, client.liveViewFocusInfo()!!.frames.size)
            assertEquals(listOf("GET /v1/session/synthetic-session/liveview/focus-info"), peer.requests.drop(snapshot.size))
            assertEquals(listOf("no-cache, no-store"), peer.focusCacheHeaders.toList())
            peer.focusInfo = { peer.json("null") }
            assertNull(client.liveViewFocusInfo())
            client.stopLiveView()
            assertNull(client.liveViewFocusInfo())
            assertEquals(2, peer.count("liveview/focus-info"))
        }
    }

    @Test fun malformedAndOversizedTelemetryIsNotRendered() = runBlocking {
        for (body in listOf("{}", "null", bridgeFocusInfoJson().toString().replace("0.4", "-0.4"))) Peer().use { peer ->
            val client = peer.ready()
            client.startLiveView(LiveViewRequest())
            peer.focusInfo = { peer.json(body) }
            assertNull(client.liveViewFocusInfo())
        }
        for (chunked in listOf(false, true)) Peer().use { peer ->
            val client = peer.ready()
            client.startLiveView(LiveViewRequest())
            val body = " ".repeat(MAX_FOCUS_INFO_BYTES + 1)
            peer.focusInfo = { if (chunked) MockResponse().setChunkedBody(body, 1024) else peer.json(body) }
            assertTrue(runCatching { client.liveViewFocusInfo() }.isFailure)
        }
    }

    @Test fun malformedUtf8TelemetryIsRejectedBeforeJsonParsing() = runBlocking {
        Peer().use { peer ->
            val client = peer.ready()
            client.startLiveView(LiveViewRequest())
            val prefix = "{\"extra\":\"".toByteArray()
            val suffix = "\",\"frames\":[],\"magnification\":1,\"imageAspectRatio\":1.5}".toByteArray()
            for (bytes in listOf(
                prefix + byteArrayOf(0xC3.toByte(), 0x28) + suffix,
                bridgeFocusInfoJson().toString().toByteArray(Charsets.UTF_16LE),
                bridgeFocusInfoJson().toString().toByteArray() + byteArrayOf(0),
                (bridgeFocusInfoJson().toString() + "trailing").toByteArray(),
            )) {
                peer.focusInfo = { MockResponse().setBody(Buffer().write(bytes)) }
                assertTrue(runCatching { client.liveViewFocusInfo() }.isFailure)
            }
        }
    }

    @Test fun telemetryCancellationCancelsSocketAndStalePreviewReplyIsDiscarded() = runBlocking {
        Peer().use { peer ->
            val received = CountDownLatch(1)
            peer.focusInfo = { received.countDown(); peer.json(bridgeFocusInfoJson().toString()).setBodyDelay(5, TimeUnit.SECONDS) }
            val client = peer.ready()
            client.startLiveView(LiveViewRequest())
            val job = launch(Dispatchers.IO) { client.liveViewFocusInfo() }
            assertTrue(withContext(Dispatchers.IO) { received.await(2, TimeUnit.SECONDS) })
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(0, peer.count("status"))
        }
        Peer().use { peer ->
            val received = CountDownLatch(1)
            val respond = CountDownLatch(1)
            peer.focusInfo = {
                received.countDown()
                check(respond.await(2, TimeUnit.SECONDS))
                peer.json(bridgeFocusInfoJson().toString())
            }
            val client = peer.ready()
            client.startLiveView(LiveViewRequest())
            val result = CompletableDeferred<CameraFocusInfo?>()
            val job = launch(Dispatchers.IO) { result.complete(client.liveViewFocusInfo()) }
            try {
                assertTrue(withContext(Dispatchers.IO) { received.await(2, TimeUnit.SECONDS) })
                client.stopLiveView()
                client.startLiveView(LiveViewRequest())
                respond.countDown()
                assertNull(withTimeout(2_000) { result.await() })
            } finally { respond.countDown(); job.cancelAndJoin() }
        }
    }

    @Test fun statusFieldIsNullableAndStrictOnLegacyAndMalformedReplies() = runBlocking {
        for ((field, expected) in listOf("" to null, ",\"heldAutofocusActive\":null" to null,
            ",\"heldAutofocusActive\":\"false\"" to null, ",\"heldAutofocusActive\":true" to true,
            ",\"heldAutofocusActive\":false" to false)) Peer().use { peer ->
            peer.status = { peer.json("{\"shutterReleaseUnconfirmed\":false$field}") }
            assertEquals(expected, peer.ready().status().heldAutofocusActive)
        }
    }

    private class Peer(private val callTimeoutMillis: Long = 8_000L) : AutoCloseable {
        private val server = MockWebServer()
        val requests = CopyOnWriteArrayList<String>()
        val calls = CopyOnWriteArrayList<Call>()
        val finishedCalls = CopyOnWriteArrayList<Call>()
        val focusCommands = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val focusCacheHeaders = CopyOnWriteArrayList<String>()
        var capabilityFields = ",\"heldAutofocusSupported\":true,\"focusInfoSupported\":true"
        var supported = "[\"AUTOFOCUS\",\"LIVE_VIEW\",\"STILL_CAPTURE\"]"
        var sources = "[\"CCAPI_JPEG_POLLING\"]"
        var delete: () -> MockResponse = { MockResponse().setResponseCode(204) }
        var start: (String) -> MockResponse = { ack(it, true) }
        var stop: (String) -> MockResponse = { ack(it, false) }
        var focusInfo: () -> MockResponse = { json(bridgeFocusInfoJson().toString()) }
        var status: () -> MockResponse = { json("""{"heldAutofocusActive":false,"shutterReleaseUnconfirmed":false,"bulbExposureActive":false}""") }
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests += "${request.method} $path"
                    return when {
                        path == "/health" -> json("""{"service":"open-eos-control-bridge"}""")
                        path == "/v1/session" -> json("""{"id":"synthetic-session"}""").setResponseCode(201)
                        path.endsWith("/capabilities") -> json("""{"supported":$supported,"settings":[],"liveView":{"sources":$sources}$capabilityFields}""")
                        path.endsWith("/focus/auto/start") || path.endsWith("/focus/auto/stop") -> {
                            val owner = JSONObject(request.body.readUtf8()).getString("ownerId")
                            val starting = path.endsWith("/start")
                            focusCommands += owner to starting
                            if (starting) start(owner) else stop(owner)
                        }
                        path.endsWith("/liveview/focus-info") -> {
                            focusCacheHeaders += request.getHeader("Cache-Control").orEmpty()
                            focusInfo()
                        }
                        path.endsWith("/liveview/start") -> json("""{"active":true,"liveViewStopUnconfirmed":false}""")
                        path.endsWith("/liveview/stop") -> json("""{"active":false,"liveViewStopUnconfirmed":false}""")
                        path.endsWith("/status") || path.endsWith("/capture/still") -> status()
                        request.method == "DELETE" -> delete()
                        else -> error("UNEXPECTED_ENDPOINT", 404)
                    }
                }
            }
            server.start()
        }
        val url get() = server.url("/").toString()
        fun http() = OkHttpClient.Builder().readTimeout(6, TimeUnit.SECONDS).callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls += call }
                override fun callEnd(call: Call) { finishedCalls += call }
            }).build()
        suspend fun ready() = DesktopBridgeClient(url, httpClient = http()).also { it.initialize(); it.capabilities() }
        fun count(suffix: String) = requests.count { it.endsWith("/$suffix") }
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        fun ack(owner: String, active: Boolean) = json(JSONObject().put("ownerId", owner).put("active", active).put("stopUnconfirmed", false).toString())
        fun error(code: String, status: Int = 502) = json("""{"error":{"code":"$code","feature":"AUTOFOCUS","message":"Synthetic failure"}}""").setResponseCode(status)
        override fun close() = server.shutdown()
    }
}
