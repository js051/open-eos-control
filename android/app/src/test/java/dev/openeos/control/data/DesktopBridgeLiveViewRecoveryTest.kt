package dev.openeos.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic Bridge HTTP only; no camera or Android lifecycle claim. */
class DesktopBridgeLiveViewRecoveryTest {
    @Test fun lostStartIsNotReplayedAndRequiresExplicitStopProof() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.start = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            val client = peer.client(); client.initialize()
            assertTrue(runCatching { client.startLiveView(LiveViewRequest()) }.isFailure)
            assertTrue(client.liveViewStopRequired)
            assertTrue(runCatching { client.startLiveView(LiveViewRequest()) }.isFailure)
            assertEquals(1, peer.count("liveview/start"))
            peer.stop = { peer.json("""{"active":false}""") }
            assertTrue(runCatching { client.stopLiveView() }.isFailure)
            assertTrue(client.liveViewStopRequired)
            peer.stop = { peer.json("""{"active":false,"liveViewStopUnconfirmed":false}""") }
            client.stopLiveView()
            assertFalse(client.liveViewStopRequired)
            assertEquals(2, peer.count("liveview/stop"))
            assertEquals(1, peer.count("liveview/start"))
        }
    }

    @Test fun knownSuccessfulLegacyStartStopRemainsCompatible() = runBlocking<Unit> {
        for (startBody in listOf("""{"active":true}""", """{"active":true,"liveViewStopUnconfirmed":null}""")) {
            Peer().use { peer ->
                peer.start = { peer.json(startBody) }
                val client = peer.client(); client.initialize()
                client.startLiveView(LiveViewRequest())
                assertTrue(client.liveViewStopRequired)
                peer.stop = { peer.json("""{"active":false}""") }
                client.stopLiveView(); assertFalse(client.liveViewStopRequired)
            }
        }
    }

    @Test fun startStopWarningCannotGrantLegacyStopProof() = runBlocking<Unit> {
        Peer().use { peer ->
            peer.start = { peer.json("""{"active":true,"liveViewStopUnconfirmed":true}""") }
            val client = peer.client(); client.initialize()
            assertTrue(runCatching { client.startLiveView(LiveViewRequest()) }.isFailure)
            assertTrue(client.liveViewStopRequired)
            assertTrue(runCatching { client.startLiveView(LiveViewRequest()) }.isFailure)
            peer.stop = { peer.json("""{"active":false}""") }
            assertTrue(runCatching { client.stopLiveView() }.isFailure)
            assertTrue(client.liveViewStopRequired)
            peer.stop = { peer.json("""{"active":false,"liveViewStopUnconfirmed":false}""") }
            client.stopLiveView()
            assertFalse(client.liveViewStopRequired)
            assertEquals(1, peer.count("liveview/start"))
            assertEquals(2, peer.count("liveview/stop"))
        }
    }

    @Test fun failedStopKeepsOwnerAndCannotUseLegacyFalseOnRetry() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client(); client.initialize(); client.startLiveView(LiveViewRequest())
            peer.stop = { peer.error() }
            assertTrue(runCatching { client.stopLiveView() }.isFailure)
            assertTrue(client.liveViewStopRequired)
            peer.stop = { peer.json("""{"active":false}""") }
            assertTrue(runCatching { client.stopLiveView() }.isFailure)
            assertTrue(client.liveViewStopRequired)
            assertEquals(1, peer.count("liveview/start"))
        }
    }

    @Test fun stopRejectsPositiveUnknownAndMalformedEvidence() = runBlocking<Unit> {
        for (body in listOf("""{"active":true,"liveViewStopUnconfirmed":false}""",
            """{"active":false,"liveViewStopUnconfirmed":true}""",
            """{"active":false,"liveViewStopUnconfirmed":"false"}""", "{}")) {
            Peer().use { peer ->
                val client = peer.client(); client.initialize(); client.startLiveView(LiveViewRequest())
                peer.stop = { peer.json(body) }
                assertTrue(body, runCatching { client.stopLiveView() }.isFailure)
                assertTrue(body, client.liveViewStopRequired)
            }
        }
    }

    @Test fun freshUnconfirmedStatusCreatesStopOnlyOwnerButFalseNeverDischargesIt() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client(); client.initialize()
            peer.status = { peer.status(true) }; client.status()
            assertTrue(client.liveViewStopRequired)
            peer.status = { peer.status(false) }; client.status()
            assertTrue(client.liveViewStopRequired)
            assertTrue(runCatching { client.startLiveView(LiveViewRequest()) }.isFailure)
            assertEquals(0, peer.count("liveview/start"))
            client.stopLiveView(); assertFalse(client.liveViewStopRequired)
        }
    }

    @Test fun cancelledInFlightStartRetainsStopWithoutAnotherStart() = runBlocking<Unit> {
        Peer().use { peer ->
            val arrived = CountDownLatch(1)
            peer.start = { arrived.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
            val client = peer.client(); client.initialize()
            val start = launch(Dispatchers.IO) { runCatching { client.startLiveView(LiveViewRequest()) } }
            try {
                assertTrue(withContext(Dispatchers.IO) { arrived.await(2, TimeUnit.SECONDS) })
                start.cancel(); start.join()
                assertTrue(client.liveViewStopRequired)
                client.stopLiveView(); assertFalse(client.liveViewStopRequired)
                assertEquals(1, peer.count("liveview/start"))
                assertEquals(1, peer.count("liveview/stop"))
            } finally { start.cancel(); start.join() }
        }
    }

    @Test fun closingRetiresResponsibilityAndReusedWireIdDoesNotInheritIt() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client(); client.initialize(); client.startLiveView(LiveViewRequest())
            peer.stop = { peer.error() }; runCatching { client.stopLiveView() }
            assertTrue(client.liveViewStopRequired)
            client.close(); assertFalse(client.liveViewStopRequired)
            client.initialize(); assertFalse(client.liveViewStopRequired)
            client.startLiveView(LiveViewRequest())
            assertEquals(2, peer.count("liveview/start"))
            assertEquals(1, peer.requests.count { it == "DELETE /v1/session/synthetic" })
        }
    }

    @Test fun delayedUnconfirmedSnapshotCannotRestoreAnAcknowledgedStop() = runBlocking<Unit> {
        Peer().use { peer ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val client = peer.client(); client.initialize(); client.startLiveView(LiveViewRequest())
            var first = true
            peer.status = {
                if (first) {
                    first = false; arrived.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    peer.status(true)
                } else peer.status(false)
            }
            val read = launch(Dispatchers.IO) { client.status() }
            try {
                assertTrue(withContext(Dispatchers.IO) { arrived.await(2, TimeUnit.SECONDS) })
                client.stopLiveView()
                release.countDown(); read.join()
                assertFalse(client.liveViewStopRequired)
                assertEquals(2, peer.count("status"))
            } finally { release.countDown(); read.cancel(); read.join() }
        }
    }

    @Test fun freshWarningRevokesKnownStartLegacyProof() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client(); client.initialize(); client.startLiveView(LiveViewRequest())
            peer.status = { peer.status(true) }; client.status()
            peer.stop = { peer.json("""{"active":false}""") }
            assertTrue(runCatching { client.stopLiveView() }.isFailure)
            assertTrue(client.liveViewStopRequired)
        }
    }

    @Test fun repeatedConfirmedBulbStartIsRejectedWithoutReplayAndStillStops() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client(); client.initialize()
            withTimeout(3_000) {
                client.startBulbExposure()
                assertTrue(runCatching { client.startBulbExposure() }.isFailure)
                client.stopBulbExposure()
            }
            assertEquals(1, peer.count("bulb/start/owned"))
            assertEquals(1, peer.count("bulb/stop/owned"))
            assertEquals(0, peer.count("status"))
        }
    }

    @Test fun repositoryRetainsStopOnlyAfterFailedStartAndReportsDisconnectUncertainty() = runBlocking<Unit> {
        Peer().use { peer ->
            val repository = peer.repository()
            repository.connectBridge(peer.url, startLiveView = false)
            peer.start = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            assertTrue(runCatching { repository.setLiveViewEnabled(true) }.isFailure)
            assertFalse(repository.isLiveViewRunning())
            assertTrue(repository.isLiveViewStopRequired())
            peer.stop = { peer.error() }
            assertTrue(repository.disconnect())
            assertEquals(1, peer.count("liveview/start"))
            assertEquals(1, peer.count("liveview/stop"))
            assertEquals(1, peer.requests.count { it == "DELETE /v1/session/synthetic" })
        }
    }

    @Test fun repositoryStopOnlyRecoveryDoesNotReplayStart() = runBlocking<Unit> {
        Peer().use { peer ->
            val repository = peer.repository()
            repository.connectBridge(peer.url, startLiveView = false)
            peer.start = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            runCatching { repository.setLiveViewEnabled(true) }
            repository.setLiveViewEnabled(false)
            assertFalse(repository.isLiveViewStopRequired())
            assertFalse(repository.isLiveViewRunning())
            assertEquals(1, peer.count("liveview/start"))
            assertEquals(1, peer.count("liveview/stop"))
        }
    }

    @Test fun warningDuringStopRevokesCapturedLegacyProofButNotLaterExplicitAcknowledgement() = runBlocking<Unit> {
        for (explicit in listOf(false, null)) {
            Peer().use { peer ->
                val arrived = CountDownLatch(1)
                val release = CountDownLatch(1)
                val client = peer.client(); client.initialize(); client.startLiveView(LiveViewRequest())
                peer.stop = {
                    arrived.countDown(); check(release.await(2, TimeUnit.SECONDS))
                    peer.json(if (explicit == false) """{"active":false,"liveViewStopUnconfirmed":false}"""
                        else """{"active":false}""")
                }
                val stopping = async(Dispatchers.IO) { runCatching { client.stopLiveView() } }
                try {
                    assertTrue(withContext(Dispatchers.IO) { arrived.await(2, TimeUnit.SECONDS) })
                    peer.status = { peer.status(true) }; client.status()
                    release.countDown()
                    val result = stopping.await()
                    assertEquals(explicit == false, result.isSuccess)
                    assertEquals(explicit != false, client.liveViewStopRequired)
                    assertEquals(1, peer.count("liveview/start"))
                } finally { release.countDown(); stopping.cancel(); stopping.join() }
            }
        }
    }

    @Test fun lateStartAcknowledgementCannotRestoreRevokedLegacyStopProof() = runBlocking<Unit> {
        Peer().use { peer ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            peer.start = {
                arrived.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                peer.json("""{"active":true}""")
            }
            val client = peer.client(); client.initialize()
            val starting = async(Dispatchers.IO) { client.startLiveView(LiveViewRequest()) }
            try {
                assertTrue(withContext(Dispatchers.IO) { arrived.await(2, TimeUnit.SECONDS) })
                peer.status = { peer.status(true) }
                client.status()
                release.countDown()
                starting.await()
                assertTrue(client.liveViewStopRequired)
                peer.stop = { peer.json("""{"active":false}""") }
                assertTrue(runCatching { client.stopLiveView() }.isFailure)
                assertTrue(client.liveViewStopRequired)
                peer.stop = { peer.json("""{"active":false,"liveViewStopUnconfirmed":false}""") }
                client.stopLiveView()
                assertFalse(client.liveViewStopRequired)
                assertEquals(1, peer.count("liveview/start"))
                assertEquals(2, peer.count("liveview/stop"))
            } finally { release.countDown(); starting.cancel(); starting.join() }
        }
    }

    private class Peer : AutoCloseable {
        private val server = MockWebServer()
        val requests = CopyOnWriteArrayList<String>()
        var start: () -> MockResponse = { json("""{"active":true}""") }
        var stop: () -> MockResponse = { json("""{"active":false,"liveViewStopUnconfirmed":false}""") }
        private var bulbActive = false
        var status: () -> MockResponse = { status(false, bulbActive) }
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests += "${request.method} $path"
                    return when {
                        path == "/health" -> json("""{"service":"open-eos-control-bridge"}""")
                        path == "/v1/session" -> json("""{"id":"synthetic"}""").setResponseCode(201)
                        path.endsWith("/info") -> json("""{"connected":true,"model":"Synthetic Canon Camera","serial":"SYNTHETIC-ONLY"}""")
                        path.endsWith("/capabilities") -> json("""{"supported":["LIVE_VIEW"],"settings":[],"liveView":{"minFps":1,"maxFps":5,"sources":["DESKTOP_BRIDGE_STREAM"]}}""")
                        path.endsWith("/liveview/start") -> start()
                        path.endsWith("/liveview/stop") -> stop()
                        path.endsWith("/status") -> status()
                        path.endsWith("/bulb/start/owned") || path.endsWith("/bulb/stop/owned") -> {
                            val payload = JSONObject(request.body.clone().readUtf8())
                            check(payload.length() == 1 && payload.opt("ownerId") is String)
                            bulbActive = path.endsWith("/start/owned")
                            json(JSONObject().put("ownerId", payload.getString("ownerId"))
                                .put("outcome", if (bulbActive) "started" else "released")
                                .put("status", JSONObject().put("connected", true).put("bulbExposureActive", bulbActive)
                                    .put("shutterReleaseUnconfirmed", false)).toString())
                        }
                        request.method == "DELETE" -> MockResponse().setResponseCode(204)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }
        val url get() = server.url("/").toString()
        private fun http() = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).callTimeout(2, TimeUnit.SECONDS).build()
        fun client() = DesktopBridgeClient(url, httpClient = http())
        fun repository() = CameraRepository(CameraBackendFactory(httpTransportFactory = CameraHttpTransportFactory {
            CameraHttpTransport(http(), CameraNetworkDiagnostics.Empty)
        }))
        fun count(suffix: String) = requests.count { it.endsWith("/$suffix") }
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        fun error() = json("""{"code":"LIVE_VIEW_STOP_UNCONFIRMED","message":"Synthetic stop failure"}""").setResponseCode(409)
        fun status(unconfirmed: Boolean, bulb: Boolean = false) = json("""{"connected":true,"liveViewStopUnconfirmed":$unconfirmed,"bulbExposureActive":$bulb,"shutterReleaseUnconfirmed":false,"battery":{"level":90},"media":{"available":true},"exposure":{}}""")
        override fun close() { server.shutdown() }
    }
}
