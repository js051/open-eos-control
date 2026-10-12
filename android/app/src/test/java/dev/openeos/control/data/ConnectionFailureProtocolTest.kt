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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun bridgeCameraDiscoveryPreservesAllEightReasonsFromTheActualHttpResponse() = runTest {
        val reasons = listOf(
            ConnectionFailureReason.AUTHENTICATION_REJECTED,
            ConnectionFailureReason.TLS_ERROR,
            ConnectionFailureReason.HOST_NOT_FOUND,
            ConnectionFailureReason.TIMEOUT,
            ConnectionFailureReason.UNREACHABLE,
            ConnectionFailureReason.HTTP_ERROR,
            ConnectionFailureReason.DISCOVERY_FAILED,
            ConnectionFailureReason.ENDPOINT_NOT_FOUND,
        )
        for (reason in reasons) {
            enqueueBridgeDiscoveryError(502, cameraError(reason.name))

            val failure = bridgeDiscoveryFailure()

            assertEquals(502, failure.statusCode)
            assertEquals("CCAPI_DISCOVERY_FAILED", failure.code)
            assertEquals("ccapi", failure.engine)
            assertEquals("discovery", failure.feature)
            assertEquals(reason, failure.connectionReason)
            assertEquals(reason, connectionFailureReason(failure))
            assertEquals(ConnectionFailure(reason, cameraViaBridge = true), connectionFailure(failure))
        }
        assertRequests(*reasons.flatMap { listOf("/health", "/v1/cameras") }.toTypedArray())
    }

    @Test
    fun bridgeUnknownMissingAndNonStringReasonsKeepLegacyHttpRecovery() = runTest {
        val values = listOf(
            null, JSONObject.NULL, "", "UNKNOWN", "INVALID_ADDRESS", "NEW_FUTURE_REASON",
            "authentication_rejected", " AUTHENTICATION_REJECTED", "AUTHENTICATION_REJECTED ",
            401, true, JSONArray().put("AUTHENTICATION_REJECTED"),
            JSONObject().put("reason", "AUTHENTICATION_REJECTED"),
        )
        for (value in values) {
            enqueueBridgeDiscoveryError(502, cameraError(value))

            val failure = bridgeDiscoveryFailure()

            assertEquals("CCAPI_DISCOVERY_FAILED", failure.code)
            assertEquals(502, failure.statusCode)
            assertNull("Non-contract reason: $value", failure.connectionReason)
            assertEquals(ConnectionFailure(ConnectionFailureReason.HTTP_ERROR), connectionFailure(failure))
        }
        assertRequests(*values.flatMap { listOf("/health", "/v1/cameras") }.toTypedArray())
    }

    @Test
    fun bridgeCameraReasonRequiresExactStringCodeAndEngine() = runTest {
        val invalidValues = listOf(
            null, JSONObject.NULL, 502, true, JSONArray(), JSONObject(),
        )
        val errors = invalidValues.flatMap { value ->
            listOf(
                cameraError("AUTHENTICATION_REJECTED").put("code", value),
                cameraError("AUTHENTICATION_REJECTED").put("engine", value),
            )
        } + listOf(
            cameraError("AUTHENTICATION_REJECTED").put("code", "OTHER_ERROR"),
            cameraError("AUTHENTICATION_REJECTED").put("code", "ccapi_discovery_failed"),
            cameraError("AUTHENTICATION_REJECTED").put("code", "CCAPI_DISCOVERY_FAILED "),
            cameraError("AUTHENTICATION_REJECTED").put("engine", "libgphoto2"),
            cameraError("AUTHENTICATION_REJECTED").put("engine", "CCAPI"),
            cameraError("AUTHENTICATION_REJECTED").put("engine", "ccapi "),
        )
        for (error in errors) {
            enqueueBridgeDiscoveryError(502, error)

            val failure = bridgeDiscoveryFailure()

            assertNull(failure.connectionReason)
            assertEquals(ConnectionFailure(ConnectionFailureReason.HTTP_ERROR), connectionFailure(failure))
        }
        assertRequests(*errors.flatMap { listOf("/health", "/v1/cameras") }.toTypedArray())
    }

    @Test
    fun actualBridgeStatusAlwaysWinsOverCameraReasonIncluding401And403() = runTest {
        val cases = listOf(
            401 to ConnectionFailureReason.AUTHENTICATION_REJECTED,
            403 to ConnectionFailureReason.AUTHENTICATION_REJECTED,
            404 to ConnectionFailureReason.ENDPOINT_NOT_FOUND,
            400 to ConnectionFailureReason.HTTP_ERROR,
            500 to ConnectionFailureReason.HTTP_ERROR,
            503 to ConnectionFailureReason.HTTP_ERROR,
        )
        for ((status, expected) in cases) {
            enqueueBridgeDiscoveryError(status, cameraError("TLS_ERROR").put("status", 502))

            val failure = bridgeDiscoveryFailure()

            assertEquals(status, failure.statusCode)
            assertEquals("CCAPI_DISCOVERY_FAILED", failure.code)
            assertNull(failure.connectionReason)
            assertEquals(ConnectionFailure(expected), connectionFailure(failure))
        }
        assertRequests(*cases.flatMap { listOf("/health", "/v1/cameras") }.toTypedArray())
    }

    @Test
    fun malformedBridgeErrorEnvelopesDoNotInventCameraEvidence() = runTest {
        val bodies = listOf(
            "401 TLS_ERROR AUTHENTICATION_REJECTED",
            "[]", "{}", """{"error":null}""", """{"error":"AUTHENTICATION_REJECTED"}""",
            """{"error":[{"code":"CCAPI_DISCOVERY_FAILED","engine":"ccapi","connectionReason":"TLS_ERROR"}]}""",
        )
        for (body in bodies) {
            server.enqueue(MockResponse().setResponseCode(502).setBody(body))
            val failure = bridgeFailure()
            assertEquals(502, failure.statusCode)
            assertNull(failure.connectionReason)
            assertEquals(ConnectionFailure(ConnectionFailureReason.HTTP_ERROR), connectionFailure(failure))
        }
        assertRequests(*bodies.map { "/health" }.toTypedArray())
    }

    @Test
    fun sessionCreationAcceptsCameraEvidenceUnderAnExistingBridgeBasePath() = runTest {
        server.enqueue(jsonResponse("""{"service":"open-eos-control-bridge"}"""))
        server.enqueue(jsonResponse(JSONObject().put("error", cameraError("TLS_ERROR")).toString()).setResponseCode(502))
        val client = DesktopBridgeClient(server.url("/bridge/base/").toString())

        val failure = runCatching { client.initialize() }.exceptionOrNull()

        assertTrue(failure is DesktopBridgeException)
        assertEquals(ConnectionFailure(ConnectionFailureReason.TLS_ERROR, cameraViaBridge = true),
            connectionFailure(requireNotNull(failure)))
        assertRequests("/bridge/base/health", "/bridge/base/v1/session")
    }

    @Test
    fun healthCannotSupplyCameraDiscoveryEvidence() = runTest {
        server.enqueue(jsonResponse(JSONObject().put("error", cameraError("AUTHENTICATION_REJECTED")).toString())
            .setResponseCode(502))

        val failure = bridgeFailure()

        assertNull(failure.connectionReason)
        assertEquals(ConnectionFailure(ConnectionFailureReason.HTTP_ERROR), connectionFailure(failure))
        assertRequests("/health")
    }

    @Test
    fun successfulScanIgnoresBodySuppliedFailureEvidence() = runTest {
        server.enqueue(jsonResponse("""{"service":"open-eos-control-bridge"}"""))
        server.enqueue(jsonResponse(JSONObject().put("cameras", JSONArray())
            .put("error", cameraError("AUTHENTICATION_REJECTED").put("status", 502)).toString()))
        val client = DesktopBridgeClient(server.url("/").toString())

        assertEquals(emptyList<DesktopBridgeCamera>(), client.discoverCameras())
        assertRequests("/health", "/v1/cameras")
    }

    @Test
    fun establishedSessionReadsCommandsStopsAndCloseRetainLegacyHttpErrors() = runTest {
        server.enqueue(jsonResponse("""{"service":"open-eos-control-bridge"}"""))
        server.enqueue(jsonResponse("""{"id":"synthetic-session"}"""))
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()
        val operations = listOf<suspend () -> Unit>(
            { client.info() }, { client.status() }, { client.autofocus() },
            { client.stopLiveView() }, { client.stopRecording() }, { client.close() },
        )
        for (operation in operations) {
            server.enqueue(jsonResponse(JSONObject().put("error", cameraError("AUTHENTICATION_REJECTED")).toString())
                .setResponseCode(502))

            val failure = runCatching { operation() }.exceptionOrNull()

            assertTrue("Established session must keep DesktopBridgeException", failure is DesktopBridgeException)
            val bridgeFailure = failure as DesktopBridgeException
            assertEquals(502, bridgeFailure.statusCode)
            assertEquals("CCAPI_DISCOVERY_FAILED", bridgeFailure.code)
            assertNull(bridgeFailure.connectionReason)
            assertEquals(ConnectionFailure(ConnectionFailureReason.HTTP_ERROR), connectionFailure(bridgeFailure))
        }
        assertRequests(
            "/health", "/v1/session", "/v1/session/synthetic-session/info", "/v1/session/synthetic-session/status",
            "/v1/session/synthetic-session/focus/auto", "/v1/session/synthetic-session/liveview/stop",
            "/v1/session/synthetic-session/recording/stop", "/v1/session/synthetic-session",
        )
    }

    @Test
    fun cameraReasonCannotReplaceReleaseSafetyDuringSessionCreation() = runTest {
        enqueueBridgeDiscoveryError(502, cameraError("AUTHENTICATION_REJECTED")
            .put("code", "SHUTTER_RELEASE_UNCONFIRMED"))
        val client = DesktopBridgeClient(server.url("/").toString())

        val failure = runCatching { client.initialize() }.exceptionOrNull()

        assertTrue(failure is ShutterReleaseException)
        val cause = generateSequence(failure) { it.cause }.take(16)
            .filterIsInstance<DesktopBridgeException>().firstOrNull()
        assertNotNull(cause)
        assertEquals(502, cause?.statusCode)
        assertEquals("SHUTTER_RELEASE_UNCONFIRMED", cause?.code)
        assertNull(cause?.connectionReason)
        assertRequests("/health", "/v1/session")
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

    @Test
    fun cancellingBridgeDiscoveryRemainsCancellationWithoutRetrying() = runBlocking {
        server.enqueue(jsonResponse("""{"service":"open-eos-control-bridge"}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = DesktopBridgeClient(server.url("/").toString())
        val attempt = async(Dispatchers.Default) { client.discoverCameras() }

        try {
            assertEquals("/health", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            assertEquals("/v1/cameras", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        } finally {
            attempt.cancelAndJoin()
        }

        assertTrue(attempt.isCancelled)
        assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
        assertEquals(2, server.requestCount)
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

    private fun cameraError(reason: Any?): JSONObject = JSONObject()
        .put("code", "CCAPI_DISCOVERY_FAILED")
        .put("engine", "ccapi")
        .put("feature", "discovery")
        .put("message", "SYNTHETIC PRIVATE URL AND TOKEN: 401 TLS timeout")
        .put("connectionReason", reason)

    private fun enqueueBridgeDiscoveryError(status: Int, error: JSONObject) {
        server.enqueue(jsonResponse("""{"service":"open-eos-control-bridge"}"""))
        server.enqueue(jsonResponse(JSONObject().put("error", error).toString()).setResponseCode(status))
    }

    private suspend fun bridgeDiscoveryFailure(): DesktopBridgeException {
        val client = DesktopBridgeClient(server.url("/").toString())
        val failure = runCatching { client.discoverCameras() }.exceptionOrNull()
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
