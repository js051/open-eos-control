package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
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
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual client/repository against an independent local HTTP peer. Not physical-camera evidence. */
class DesktopBridgeConnectionCancellationTest {
    @Test fun cancelledLateCreationDeletesItsExactSessionWithOriginalAuthentication() = runBlocking {
        Peer().use { peer ->
            peer.holdCreation = true
            val repository = peer.repository()
            var published = false
            val attempt = launch(Dispatchers.IO) {
                try { repository.connectBridge(peer.url, token = "synthetic-old-token", startLiveView = false); published = true }
                catch (_: CancellationException) { }
            }
            try {
                assertTrue(peer.creationEntered.await(3, TimeUnit.SECONDS))
                attempt.cancel()
                peer.creationRelease.countDown()
                withTimeout(6_000) { attempt.join() }
                assertFalse(published)
                assertEquals(listOf("/v1/session/synthetic-owner"), peer.requests.filter { it.method == "DELETE" }.map { it.path })
                assertTrue(peer.requests.all { it.token == "Bearer synthetic-old-token" })
                assertEquals(1, peer.requests.count { it.method == "POST" })
                assertEquals(0L, repository.connectionRetirementRevision)
            } finally { peer.creationRelease.countDown(); attempt.cancelAndJoin(); repository.disconnect() }
        }
    }

    @Test fun lostCreationResponseBlocksRetryBeforeAnySecondPost() = runBlocking {
        Peer().use { peer ->
            peer.creation = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            val repository = peer.repository()
            assertTrue(runCatching { repository.connectBridge(peer.url, startLiveView = false) }.exceptionOrNull()
                is ConnectionRetirementUnconfirmedException)
            assertEquals(1L, repository.connectionRetirementRevision)
            assertTrue(runCatching { repository.connectBridge(peer.url, startLiveView = false) }.exceptionOrNull()
                is ConnectionRetirementBlockedException)
            assertEquals(1, peer.requests.count { it.method == "POST" })
            assertTrue(peer.requests.none { it.method == "DELETE" })
        }
    }

    @Test fun failedTargetedCleanupBlocksAnOtherwiseValidRetry() = runBlocking {
        Peer().use { peer ->
            peer.holdCreation = true
            peer.deletion = { MockResponse().setResponseCode(500) }
            val repository = peer.repository()
            val attempt = launch(Dispatchers.IO) {
                try { repository.connectBridge(peer.url, startLiveView = false) } catch (_: CancellationException) { }
            }
            try {
                assertTrue(peer.creationEntered.await(3, TimeUnit.SECONDS)); attempt.cancel(); peer.creationRelease.countDown()
                withTimeout(6_000) { attempt.join() }
                assertEquals(1L, repository.connectionRetirementRevision)
                assertTrue(runCatching { repository.connectBridge(peer.url, startLiveView = false) }.exceptionOrNull()
                    is ConnectionRetirementBlockedException)
                assertEquals(1, peer.requests.count { it.method == "POST" })
                assertEquals(1, peer.requests.count { it.method == "DELETE" })
            } finally { peer.creationRelease.countDown(); attempt.cancelAndJoin(); repository.disconnect() }
        }
    }

    @Test fun malformedSuccessfulIdNeverBecomesACleanupDestination() = runBlocking {
        for (value in listOf("42", "null", "\"invalid/target\"", "\"\"")) Peer().use { peer ->
            peer.creation = { peer.json("{\"id\":$value}").setResponseCode(201) }
            val client = DesktopBridgeClient(peer.url, httpClient = peer.http)
            assertTrue(runCatching { client.initialize() }.exceptionOrNull() is ConnectionRetirementUnconfirmedException)
            assertTrue(client.connectionRetirementUnconfirmed)
            withContext(NonCancellable) { client.closeConnectionAttempt() }
            assertTrue(peer.requests.none { it.method == "DELETE" })
        }
    }

    @Test fun knownAuthenticationRejectionKeepsReasonWithoutUnknownRetirement() = runBlocking {
        Peer().use { peer ->
            peer.creation = { peer.json("""{"error":{"code":"AUTHENTICATION_REQUIRED","message":"synthetic"}}""").setResponseCode(401) }
            val client = DesktopBridgeClient(peer.url, httpClient = peer.http)
            val failure = runCatching { client.initialize() }.exceptionOrNull()
            assertTrue(failure is DesktopBridgeException)
            assertEquals(ConnectionFailureReason.AUTHENTICATION_REJECTED, connectionFailureReason(requireNotNull(failure)))
            assertFalse(client.connectionRetirementUnconfirmed)
        }
    }

    @Test fun shutterSafetyResponseKeepsItsTypeAndUnknownRetirementGate() = runBlocking {
        Peer().use { peer ->
            peer.creation = { peer.json("""{"error":{"code":"SHUTTER_RELEASE_UNCONFIRMED","message":"synthetic"}}""").setResponseCode(409) }
            val repository = peer.repository()
            assertTrue(runCatching { repository.connectBridge(peer.url, startLiveView = false) }.exceptionOrNull() is ShutterReleaseException)
            assertEquals(1L, repository.connectionRetirementRevision)
            assertTrue(runCatching { repository.connectBridge(peer.url, startLiveView = false) }.exceptionOrNull() is ConnectionRetirementBlockedException)
        }
    }

    @Test fun otherSuccessStatusesAreNotSessionRetirementAcknowledgements() = runBlocking {
        for (status in listOf(200, 202, 206)) Peer().use { peer ->
            val client = DesktopBridgeClient(peer.url, httpClient = peer.http)
            client.initialize()
            peer.deletion = { MockResponse().setResponseCode(status).setBody("") }
            assertTrue(runCatching { client.closeConnectionAttempt() }.isFailure)
            assertEquals(1, peer.requests.count { it.method == "DELETE" })
        }
    }

    @Test fun deleteSafetyResponseKeepsItsOriginalTypeAndPreviousOwnerWarning() = runBlocking {
        for ((code, warning) in listOf(
            "SHUTTER_RELEASE_UNCONFIRMED" to PreviousConnectionSafetyWarning.SHUTTER,
            "LIVE_VIEW_STOP_UNCONFIRMED" to PreviousConnectionSafetyWarning.LIVE_VIEW,
            "RECORDING_COMMAND_UNCONFIRMED" to PreviousConnectionSafetyWarning.RECORDING)) Peer().use { peer ->
            val client = DesktopBridgeClient(peer.url, httpClient = peer.http)
            client.initialize()
            peer.deletion = { peer.json("{\"error\":{\"code\":\"$code\",\"message\":\"synthetic\"}}").setResponseCode(502) }
            val failure = requireNotNull(runCatching { client.closeConnectionAttempt() }.exceptionOrNull())
            if (code == "SHUTTER_RELEASE_UNCONFIRMED") assertTrue(failure is ShutterReleaseException)
            else assertTrue(failure is DesktopBridgeException)
            assertEquals(setOf(warning), previousConnectionSafetyWarnings(failure))
        }
    }

    private class Peer : AutoCloseable {
        data class Observation(val method: String?, val path: String, val token: String?)
        val requests = CopyOnWriteArrayList<Observation>()
        val server = MockWebServer()
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
        val creationEntered = CountDownLatch(1)
        val creationRelease = CountDownLatch(1)
        @Volatile var holdCreation = false
        var creation: () -> MockResponse = { json("""{"id":"synthetic-owner"}""").setResponseCode(201) }
        var deletion: () -> MockResponse = { MockResponse().setResponseCode(204) }
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = requireNotNull(request.requestUrl).encodedPath
                    requests += Observation(request.method, path, request.getHeader("Authorization"))
                    return when {
                        path == "/health" -> json("""{"service":"open-eos-control-bridge"}""")
                        request.method == "POST" && path == "/v1/session" -> {
                            val response = creation(); creationEntered.countDown()
                            if (holdCreation) check(creationRelease.await(8, TimeUnit.SECONDS))
                            response
                        }
                        request.method == "DELETE" -> deletion()
                        path.endsWith("/info") -> json("""{"connected":true,"model":"Synthetic Camera","serial":"SYNTHETIC-ONLY"}""")
                        path.endsWith("/status") -> json("""{"connected":true,"shutterReleaseUnconfirmed":false}""")
                        path.endsWith("/capabilities") -> json("""{"supported":[],"settings":[]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }
        val url get() = server.url("/").toString()
        fun repository() = CameraRepository(CameraBackendFactory(httpTransportFactory = CameraHttpTransportFactory {
            CameraHttpTransport(http, CameraNetworkDiagnostics.Empty)
        }))
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        override fun close() { creationRelease.countDown(); http.dispatcher.cancelAll(); server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
}
