package dev.openeos.control.data

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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Independent Bridge HTTP peer: an accepted operation may lose its whole response. Not camera evidence. */
class DesktopBridgeShutterRecoveryTest {
    @Test fun lostStartIsNeverReplayedAndBlocksNewWritesUntilExplicitStop() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.start = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            val client = peer.client()
            client.initialize()
            assertTrue(runCatching { client.startBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
            assertEquals(1, peer.count("bulb/start"))
            assertTrue(runCatching { client.captureStill() }.exceptionOrNull() is ShutterReleaseException)
            assertEquals(0, peer.count("capture/still"))
            // Even an idle status is not proof that a possibly received start was released.
            client.status()
            assertTrue(runCatching { client.startBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
            assertEquals(1, peer.count("bulb/start"))
            client.stopBulbExposure()
            assertFalse(CameraFeature.BULB_EXPOSURE in client.observedFeatureSnapshot())
            client.captureStill()
            assertEquals(1, peer.count("bulb/stop"))
            assertEquals(1, peer.count("capture/still"))
        }
    }

    @Test fun newErrorCodeMapsToRecoveryForNonBulbMutation() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.capture = { peer.unconfirmed() }
            val client = peer.client()
            client.initialize()
            assertTrue(runCatching { client.captureStill() }.exceptionOrNull() is ShutterReleaseException)
            assertTrue(runCatching { client.setSetting("iso", "800") }.exceptionOrNull() is ShutterReleaseException)
            assertEquals(0, peer.count("settings/iso"))
        }
    }

    @Test fun stopFailureCanRecoverOnlyFromFreshSameSessionDoubleFalseProof() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client()
            client.initialize()
            client.startBulbExposure()
            peer.stop = { peer.error("CCAPI_UNREACHABLE") } // Camera release ACK preceded a failed status tail.
            val stopped = client.stopBulbExposure()
            assertEquals(false, stopped.bulbExposureActive)
            assertEquals(1, peer.count("bulb/stop"))
            assertEquals(1, peer.count("status"))
            assertEquals(listOf("no-cache, no-store"), peer.statusCacheDirectives.toList())
            client.captureStill()
        }
    }

    @Test fun legacyFalseAfterAmbiguousStartNeverProvesReleaseEvenAfterStop200() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.start = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            peer.status = { peer.status(false, null) }
            peer.stop = { peer.status(false, null) } // Old Bridge lost ownership and does not send camera release.
            val client = peer.client()
            client.initialize()
            runCatching { client.startBulbExposure() }
            assertTrue(runCatching { client.stopBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
            assertTrue(runCatching { client.captureStill() }.exceptionOrNull() is ShutterReleaseException)
            assertEquals(0, peer.count("capture/still"))
        }
    }

    @Test fun knownSuccessfulLegacyStartStopRemainsCompatible() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.start = { peer.status(true, null) }
            peer.stop = { peer.status(false, null) }
            val client = peer.client()
            client.initialize()
            assertEquals(true, client.startBulbExposure().bulbExposureActive)
            assertEquals(false, client.stopBulbExposure().bulbExposureActive)
            assertTrue(CameraFeature.BULB_EXPOSURE in client.observedFeatureSnapshot())
            client.captureStill()
            assertEquals(1, peer.count("capture/still"))
        }
    }

    @Test fun malformedFalseAndMissingFieldsCannotClearFailedStop() = runBlocking<Unit> {
        for (fields in listOf(
            "\"bulbExposureActive\":false",
            "\"bulbExposureActive\":false,\"shutterReleaseUnconfirmed\":null",
            "\"bulbExposureActive\":false,\"shutterReleaseUnconfirmed\":\"false\"",
            "\"bulbExposureActive\":\"false\",\"shutterReleaseUnconfirmed\":false",
            "\"bulbExposureActive\":null,\"shutterReleaseUnconfirmed\":false",
            "\"bulbExposureActive\":false,\"shutterReleaseUnconfirmed\":true",
        )) Peer().use { peer ->
            val client = peer.client()
            client.initialize()
            client.startBulbExposure()
            peer.stop = { peer.error("CCAPI_UNREACHABLE") }
            peer.status = { peer.json("{$fields}") }
            assertTrue("Must retain release responsibility for $fields",
                runCatching { client.stopBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
            assertTrue(runCatching { client.captureStill() }.exceptionOrNull() is ShutterReleaseException)
            assertEquals(0, peer.count("capture/still"))
        }
    }

    @Test fun repositoryRetryUsesExistingBridgeSessionStopOnly() = runBlocking<Unit> {
        Peer().use { peer ->
            val repository = CameraRepository(CameraBackendFactory(httpTransportFactory = CameraHttpTransportFactory {
                CameraHttpTransport(client = peer.http(), diagnostics = CameraNetworkDiagnostics())
            }))
            repository.connectBridge(peer.url, startLiveView = false)
            peer.start = { peer.unconfirmed() }
            runCatching { repository.startBulbExposure() }
            repository.retryShutterRelease()
            assertEquals(1, peer.count("bulb/start"))
            assertEquals(1, peer.count("bulb/stop"))
            assertTrue(peer.requests.filter { it.endsWith("bulb/stop") }.all { "/synthetic-session-1/" in it })
            repository.disconnect()
        }
    }

    @Test fun automatic408And503RetriesCannotRepeatBulbStart() = runBlocking<Unit> {
        for (code in listOf(408, 503)) Peer().use { peer ->
            peer.start = { peer.error("HTTP_$code", code).setHeader("Retry-After", "0") }
            val client = peer.client()
            client.initialize()
            runCatching { client.startBulbExposure() }
            assertEquals("No automatic replay after HTTP $code", 1, peer.count("bulb/start"))
        }
    }

    @Test fun stopRetriesAreExplicitAndCannotAutomaticallyRepeatAfter503() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client()
            client.initialize()
            client.startBulbExposure()
            peer.stop = { peer.unconfirmed().setResponseCode(503).setHeader("Retry-After", "0") }
            peer.status = { peer.status(null, true) }
            repeat(2) { attempt ->
                assertTrue(runCatching { client.stopBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
                assertEquals(attempt + 1, peer.count("bulb/stop"))
            }
            peer.stop = { peer.status(false, false) }
            client.retryShutterRelease()
            client.retryShutterRelease()
            assertEquals(3, peer.count("bulb/stop"))
            assertEquals(1, peer.count("bulb/start"))
        }
    }

    @Test fun malformedStartResponseRetainsStopResponsibility() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.start = { peer.json("not JSON") }
            val client = peer.client()
            client.initialize()
            assertTrue(runCatching { client.startBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
            assertEquals(true, client.status().shutterReleaseUnconfirmed)
            assertNull(client.status().bulbExposureActive)
            client.retryShutterRelease()
            assertEquals(false, client.status().shutterReleaseUnconfirmed)
            assertEquals(1, peer.count("bulb/start"))
        }
    }

    @Test fun statusWarningBlocksMutationsAndRetainsStopEndpoints() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.status = { peer.status(null, true) }
            val client = peer.client()
            client.initialize()
            assertEquals(true, client.status().shutterReleaseUnconfirmed)
            assertTrue(runCatching { client.startRecording() }.exceptionOrNull() is ShutterReleaseException)
            assertTrue(runCatching { client.startLiveView(LiveViewRequest()) }.exceptionOrNull() is ShutterReleaseException)
            assertTrue(runCatching { client.setSetting("iso", "800") }.exceptionOrNull() is ShutterReleaseException)
            client.stopLiveView()
            client.stopRecording()
            assertEquals(1, peer.count("liveview/stop"))
            assertEquals(1, peer.count("recording/stop"))
            assertEquals(0, peer.count("recording/start"))
            client.retryShutterRelease()
        }
    }

    @Test fun firstStatusWarningIsPublishedWithoutRequiringAnotherSuccessfulRead() = runBlocking<Unit> {
        Peer().use { peer ->
            var reads = 0
            peer.status = { if (++reads == 1) peer.status(null, true) else peer.error("STATUS_UNAVAILABLE") }
            val client = peer.client()
            client.initialize()
            assertEquals(true, client.status().shutterReleaseUnconfirmed)
            assertEquals(1, reads)
            assertTrue(runCatching { client.captureStill() }.exceptionOrNull() is ShutterReleaseException)
            assertEquals(0, peer.count("capture/still"))
        }
    }

    @Test fun connectRetainsReleaseRecoveryWhenCapabilitiesFailWithoutStartingLiveView() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.status = { peer.status(null, true) }
            peer.capabilities = { peer.error("CAPABILITIES_UNAVAILABLE") }
            val repository = CameraRepository(CameraBackendFactory(httpTransportFactory = CameraHttpTransportFactory {
                CameraHttpTransport(client = peer.http(), diagnostics = CameraNetworkDiagnostics())
            }))
            val session = repository.connectBridge(peer.url, startLiveView = true)
            assertEquals(true, session.status.shutterReleaseUnconfirmed)
            assertFalse(repository.isLiveViewRunning())
            assertEquals(0, peer.count("liveview/start"))
            repository.retryShutterRelease()
            assertEquals(1, peer.count("bulb/stop"))
            repository.disconnect()
        }
    }

    @Test fun closeAndReinitializeCannotCarryOldReleaseIntoNewSession() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client()
            client.initialize()
            peer.start = { peer.unconfirmed() }
            runCatching { client.startBulbExposure() }
            client.close()
            peer.sessionNumber = 2
            client.initialize()
            client.retryShutterRelease()
            assertEquals(0, peer.count("bulb/stop"))
            client.captureStill()
            assertTrue(peer.requests.contains("POST /v1/session/synthetic-session-2/capture/still"))
            assertTrue(peer.requests.contains("DELETE /v1/session/synthetic-session-1"))
        }
    }

    @Test fun oldStatusCannotPoisonAReinitializedSessionEvenIfServerReusesTheId() = runBlocking<Unit> {
        Peer().use { peer ->
            val received = CountDownLatch(1)
            val respond = CountDownLatch(1)
            peer.status = {
                received.countDown()
                check(respond.await(2, TimeUnit.SECONDS))
                peer.status(null, true)
            }
            val client = peer.client()
            client.initialize()
            val reading = launch(Dispatchers.IO) { runCatching { client.status() } }
            try {
                assertTrue(withContext(Dispatchers.IO) { received.await(2, TimeUnit.SECONDS) })
                client.close()
                client.initialize()
                respond.countDown()
                reading.join()
                client.captureStill()
                client.retryShutterRelease()
                assertEquals(0, peer.count("bulb/stop"))
            } finally { respond.countDown(); reading.cancel(); reading.join() }
        }
    }

    @Test fun cancelledStartStillOwnsSameSessionCleanup() = runBlocking<Unit> {
        Peer().use { peer ->
            val received = CountDownLatch(1)
            val respond = CountDownLatch(1)
            peer.start = {
                received.countDown()
                check(respond.await(2, TimeUnit.SECONDS))
                peer.status(true, false)
            }
            val client = peer.client()
            client.initialize()
            val starting = launch(Dispatchers.IO) { runCatching { client.startBulbExposure() } }
            try {
                assertTrue(withContext(Dispatchers.IO) { received.await(2, TimeUnit.SECONDS) })
                starting.cancel()
                respond.countDown()
                assertTrue(withTimeoutOrNull(2_000) { starting.join(); true } ?: false)
                assertTrue(starting.isCancelled)
                client.retryShutterRelease()
                assertEquals(1, peer.count("bulb/start"))
                assertEquals(1, peer.count("bulb/stop"))
            } finally { respond.countDown(); starting.cancel(); starting.join() }
        }
    }

    @Test fun mutationRedirectsNeverReplayOrFollowToAnotherEndpoint() = runBlocking<Unit> {
        for (code in listOf(302, 307, 308)) Peer().use { peer ->
            peer.start = { MockResponse().setResponseCode(code).setHeader("Location", "/redirected-start") }
            val client = peer.client()
            client.initialize()
            assertTrue(runCatching { client.startBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
            assertEquals(1, peer.count("bulb/start"))
            assertEquals(0, peer.count("redirected-start"))
        }
    }

    @Test fun readOnlyConnectionFailureRecoveryIsPreserved() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client()
            client.initialize()
            var reads = 0
            peer.status = {
                if (++reads == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else peer.status(false, false)
            }
            assertEquals(false, client.status().bulbExposureActive)
            assertEquals(2, reads)
        }
    }

    @Test fun delayedOldMutationErrorCannotUndoNewerConfirmedRelease() = runBlocking<Unit> {
        Peer().use { peer ->
            val received = CountDownLatch(1)
            val respond = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            peer.setting = {
                received.countDown()
                check(respond.await(2, TimeUnit.SECONDS))
                peer.unconfirmed()
            }
            val client = peer.client()
            client.initialize()
            client.startBulbExposure()
            val setting = launch(Dispatchers.IO) { failure.set(runCatching { client.setSetting("iso", "800") }.exceptionOrNull()) }
            try {
                assertTrue(withContext(Dispatchers.IO) { received.await(2, TimeUnit.SECONDS) })
                client.stopBulbExposure()
                respond.countDown()
                setting.join()
                assertTrue(failure.get() is DesktopBridgeException)
                assertEquals(false, client.status().shutterReleaseUnconfirmed)
                client.captureStill()
                assertEquals(1, peer.count("capture/still"))
            } finally { respond.countDown(); setting.cancel(); setting.join() }
        }
    }

    private class Peer : AutoCloseable {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<String>()
        val statusCacheDirectives = CopyOnWriteArrayList<String>()
        var start: () -> MockResponse = { status(true, false) }
        var stop: () -> MockResponse = { status(false, false) }
        var status: () -> MockResponse = { status(false, false) }
        var capture: () -> MockResponse = { status(false, false) }
        var setting: () -> MockResponse = { status(false, false) }
        var capabilities: () -> MockResponse = { json("""{"supported":["BULB_EXPOSURE","STILL_CAPTURE"],"settings":[]}""") }
        var sessionNumber = 1
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests += "${request.method} $path"
                    return when {
                        path == "/health" -> json("""{"service":"open-eos-control-bridge"}""")
                        path == "/v1/session" -> json("""{"id":"synthetic-session-$sessionNumber"}""").setResponseCode(201)
                        path.endsWith("/info") -> json("""{"connected":true,"model":"Synthetic Canon Camera","serial":"SYNTHETIC-ONLY"}""")
                        path.endsWith("/capabilities") -> capabilities()
                        path.endsWith("/status") -> {
                            statusCacheDirectives += request.getHeader("Cache-Control").orEmpty()
                            status()
                        }
                        path.endsWith("/bulb/start") -> start()
                        path.endsWith("/bulb/stop") -> stop()
                        path.endsWith("/capture/still") -> capture()
                        path.endsWith("/settings/iso") -> setting()
                        request.method == "DELETE" -> MockResponse().setResponseCode(204)
                        else -> status(false, false)
                    }
                }
            }
            server.start()
        }
        val url get() = server.url("/").toString()
        // Retry remains enabled here deliberately; production mutation handling must suppress replay.
        fun http() = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).callTimeout(2, TimeUnit.SECONDS).build()
        fun client() = DesktopBridgeClient(url, httpClient = http())
        fun count(suffix: String) = requests.count { it.endsWith("/$suffix") }
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        fun status(active: Boolean?, unconfirmed: Boolean?) = json("""{"connected":true,"mode":"Bulb","bulbExposureActive":$active${unconfirmed?.let { ",\"shutterReleaseUnconfirmed\":$it" }.orEmpty()}}""")
        fun error(code: String, status: Int = 502) = json("""{"error":{"code":"$code","message":"Synthetic failure"}}""").setResponseCode(status)
        fun unconfirmed() = error("SHUTTER_RELEASE_UNCONFIRMED")
        override fun close() = server.shutdown()
    }
}
