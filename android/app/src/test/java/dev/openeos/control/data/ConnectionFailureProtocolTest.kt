package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ConnectionFailureProtocolTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun nativeDiscoveryPreservesAuthenticationEvidenceAcrossFallback404s() = runTest {
        enqueueStatuses(401, 404, 404, 404)

        val failure = discoveryFailure(nativeClient())

        assertEquals(ConnectionFailureReason.AUTHENTICATION_REJECTED, connectionFailureReason(failure))
        assertEquals(
            setOf(ConnectionFailureReason.AUTHENTICATION_REJECTED, ConnectionFailureReason.ENDPOINT_NOT_FOUND),
            failure.reasons,
        )
        assertTrue(failure.message.orEmpty().startsWith("Failed to discover camera CCAPI."))
        assertDiscoveryRequests()
    }

    @Test
    fun nativeIdentityFallbackRetainsActualForbiddenStatus() = runTest {
        enqueueStatuses(404, 404, 403, 404)

        assertEquals(
            ConnectionFailureReason.AUTHENTICATION_REJECTED,
            connectionFailureReason(discoveryFailure(nativeClient())),
        )
        assertDiscoveryRequests()
    }

    @Test
    fun allNativeEndpointsMissingIsAnEndpointFailure() = runTest {
        enqueueStatuses(404, 404, 404, 404)

        val failure = discoveryFailure(nativeClient())

        assertEquals(setOf(ConnectionFailureReason.ENDPOINT_NOT_FOUND), failure.reasons)
        assertEquals(ConnectionFailureReason.ENDPOINT_NOT_FOUND, connectionFailureReason(failure))
        assertDiscoveryRequests()
    }

    @Test
    fun successfulIdentityFallbackStillWinsOverEarlierAuthenticationFailure() = runTest {
        enqueueStatuses(401, 404)
        server.enqueue(jsonResponse("""{"productname":"TEST CAMERA","version":"TEST VERSION"}"""))
        val client = nativeClient()

        client.initialize()

        assertTrue(client.isRealCamera)
        assertEquals("/ccapi/ver110", client.apiVersionPrefix)
        assertRequests("/ccapi", "/ccapi/", "/ccapi/ver110/deviceinformation")
    }

    @Test
    fun nativeServerBodyWordsCannotBecomeAuthenticationEvidence() = runTest {
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("401 Unauthorized: bad password"))
        }

        val failure = discoveryFailure(nativeClient())

        assertEquals(setOf(ConnectionFailureReason.HTTP_ERROR), failure.reasons)
        assertEquals(ConnectionFailureReason.HTTP_ERROR, connectionFailureReason(failure))
        assertDiscoveryRequests()
    }

    @Test
    fun nativeEmptyApiListsRetainDiscoveryFailureAlongsideMissingFallbacks() = runTest {
        repeat(4) { server.enqueue(jsonResponse("""{"ver100":[]}""")) }
        enqueueStatuses(404, 404)

        val failure = discoveryFailure(nativeClient())

        assertEquals(ConnectionFailureReason.DISCOVERY_FAILED, connectionFailureReason(failure))
        assertTrue(failure.message.orEmpty().contains("did not advertise any valid operations"))
        assertRequests(
            "/ccapi", "/ccapi/ver100/topurlfordev",
            "/ccapi/", "/ccapi/ver100/topurlfordev",
            "/ccapi/ver110/deviceinformation", "/ccapi/ver100/deviceinformation",
        )
    }

    @Test
    fun newDiscoveryAttemptDoesNotReusePreviousAuthenticationEvidence() = runTest {
        enqueueStatuses(401, 404, 404, 404, 404, 404, 404, 404)
        val client = nativeClient()

        assertEquals(ConnectionFailureReason.AUTHENTICATION_REJECTED, discoveryFailure(client).reason)
        assertEquals(ConnectionFailureReason.ENDPOINT_NOT_FOUND, discoveryFailure(client).reason)
        assertEquals(8, server.requestCount)
    }

    @Test
    fun bridgeAuthenticationUsesActualStatusEvenWhenBodyCodeDisagrees() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"error":{"code":"CAMERA_NOT_FOUND","message":"Camera not available."}}""",
            ),
        )

        val failure = bridgeFailure()

        assertEquals(401, failure.statusCode)
        assertEquals("CAMERA_NOT_FOUND", failure.code)
        assertEquals(ConnectionFailureReason.AUTHENTICATION_REJECTED, connectionFailureReason(failure))
        assertRequests("/health")
    }

    @Test
    fun bridgeServerBodyCodeAndWordsCannotInventAuthenticationRejection() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setBody(
                """{"error":{"code":"UNAUTHORIZED","message":"401 Forbidden: check your token."}}""",
            ),
        )

        val failure = bridgeFailure()

        assertEquals(503, failure.statusCode)
        assertEquals("UNAUTHORIZED", failure.code)
        assertEquals(ConnectionFailureReason.HTTP_ERROR, connectionFailureReason(failure))
        assertRequests("/health")
    }

    @Test
    fun bridgeMissingEndpointUsesActual404DespiteAuthenticationBody() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"error":{"code":"AUTHENTICATION_REQUIRED","message":"Check your password."}}""",
            ),
        )

        assertEquals(ConnectionFailureReason.ENDPOINT_NOT_FOUND, connectionFailureReason(bridgeFailure()))
        assertRequests("/health")
    }

    @Test
    fun bridgeReleaseSafetyExceptionKeepsPriorityAndItsActualStatusCause() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"error":{"code":"SHUTTER_RELEASE_UNCONFIRMED","message":"Release not confirmed."}}""",
            ),
        )
        val client = DesktopBridgeClient(server.url("/").toString())

        val failure = runCatching { client.initialize() }.exceptionOrNull()

        assertTrue(failure is ShutterReleaseException)
        // Coroutine stack recovery may copy the outer safety exception. The underlying
        // typed HTTP evidence must survive anywhere in the bounded cause chain.
        val causes = generateSequence(failure) { it.cause }.take(16).toList()
        val cause = causes.filterIsInstance<DesktopBridgeException>().firstOrNull()
        assertNotNull("Safety exception chain: ${causes.map { it.javaClass.simpleName }}", cause)
        assertEquals(401, cause?.statusCode)
        assertEquals("SHUTTER_RELEASE_UNCONFIRMED", cause?.code)
        assertRequests("/health")
    }

    @Test
    fun simulatorConnectionReadExposesActualHttpEvidence() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Endpoint missing"))
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
        client.initialize()

        val failure = runCatching { client.info() }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(ConnectionFailureReason.AUTHENTICATION_REJECTED, connectionFailureReason(requireNotNull(failure)))
        assertRequests("/ccapi/info")
    }

    @Test
    fun cancellingNativeDiscoveryDoesNotStartFallbackRequests() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = nativeClient()
        val attempt = async(Dispatchers.Default) {
            try {
                client.initialize()
                error("Discovery unexpectedly completed.")
            } catch (failure: CancellationException) {
                throw failure
            }
        }

        try {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        } finally {
            attempt.cancelAndJoin()
        }

        assertTrue(attempt.isCancelled)
        assertEquals(1, server.requestCount)
    }

    private fun nativeClient() = CcapiClient(
        server.url("/").toString(),
        httpClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        treatAsSimulator = false,
    )

    private suspend fun discoveryFailure(client: CcapiClient): CcapiDiscoveryException {
        val failure = runCatching { client.initialize() }.exceptionOrNull()
        assertTrue("Expected a terminal native discovery failure.", failure is CcapiDiscoveryException)
        return failure as CcapiDiscoveryException
    }

    private suspend fun bridgeFailure(): DesktopBridgeException {
        val client = DesktopBridgeClient(server.url("/").toString())
        val failure = runCatching { client.initialize() }.exceptionOrNull()
        assertTrue("Expected the existing Bridge exception type.", failure is DesktopBridgeException)
        return failure as DesktopBridgeException
    }

    private fun enqueueStatuses(vararg statuses: Int) {
        statuses.forEach { server.enqueue(MockResponse().setResponseCode(it)) }
    }

    private fun assertDiscoveryRequests() = assertRequests(
        "/ccapi", "/ccapi/", "/ccapi/ver110/deviceinformation", "/ccapi/ver100/deviceinformation",
    )

    private fun assertRequests(vararg paths: String) {
        assertEquals(paths.size, server.requestCount)
        assertEquals(paths.toList(), paths.map { server.takeRequest(1, TimeUnit.SECONDS)?.path })
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
