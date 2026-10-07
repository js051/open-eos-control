package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class DesktopBridgeShutterAutofocusTest {
    private val server = MockWebServer()
    private var capability: Any? = true
    private var failCapture = false
    private val captures = CopyOnWriteArrayList<RecordedRequest>()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/health" -> json("""{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}""")
                    path == "/v1/session" -> json("""{"id":"af-session","engine":"ccapi"}""")
                    path.endsWith("/capabilities") -> json(JSONObject()
                        .put("supported", org.json.JSONArray().put("STILL_CAPTURE"))
                        .apply { capability?.let { put("shutterAutofocusSupported", it) } }.toString())
                    path.endsWith("/capture/still") -> {
                        captures += request
                        if (failCapture) json("""{"error":{"code":"CAPTURE_FAILED","message":"Rejected"}}""")
                            .setResponseCode(503) else json("{}")
                    }
                    else -> json("{}")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    @Test fun advertisedBridgeBackendSendsDefaultAndFalseExactlyOnce() = runTest {
        val backend = DesktopBridgeCameraBackend(CameraConnection.DesktopBridge(server.url("/").toString()))
        backend.initialize()
        assertTrue(backend.capabilities().shutterAutofocusSupported)
        backend.captureStill()
        backend.captureStill(autofocus = false)
        assertEquals(listOf(true, false), captures.map { JSONObject(it.body.readUtf8()).get("af") })
        assertTrue(captures.all { it.method == "POST" && it.path == "/v1/session/af-session/capture/still" })
    }

    @Test fun absentFalseAndInvalidCapabilitiesRejectFalseBeforeAnyRequestButKeepDefault() = runTest {
        for (value in listOf(null, false, "true", "false", 0, 1, JSONObject.NULL)) {
            capability = value
            val client = DesktopBridgeClient(server.url("/").toString())
            client.initialize()
            assertFalse(client.capabilities().shutterAutofocusSupported)
            val before = server.requestCount
            assertTrue(runCatching { client.captureStill(autofocus = false) }.exceptionOrNull() is IllegalStateException)
            assertEquals(before, server.requestCount)
            client.captureStill()
            assertEquals(true, JSONObject(captures.last().body.readUtf8()).get("af"))
            client.close()
        }
    }

    @Test fun supportDoesNotCarryAcrossCloseReinitializeOrCapabilityWithdrawal() = runTest {
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()
        assertTrue(client.capabilities().shutterAutofocusSupported)
        client.close()
        client.initialize()
        var before = server.requestCount
        assertTrue(runCatching { client.captureStill(false) }.isFailure)
        assertEquals(before, server.requestCount)
        client.capabilities()
        capability = null
        assertFalse(client.capabilities().shutterAutofocusSupported)
        before = server.requestCount
        assertTrue(runCatching { client.captureStill(false) }.isFailure)
        assertEquals(before, server.requestCount)
    }

    @Test fun failedFalseRequestIsNeverRetriedOrChangedToTrue() = runTest {
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()
        client.capabilities()
        failCapture = true
        assertTrue(runCatching { client.captureStill(false) }.isFailure)
        assertEquals(1, captures.size)
        assertEquals(false, JSONObject(captures.single().body.readUtf8()).get("af"))
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
