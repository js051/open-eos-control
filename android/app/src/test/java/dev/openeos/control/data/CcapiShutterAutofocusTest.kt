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

class CcapiShutterAutofocusTest {
    private val server = MockWebServer()
    private var advertised = """{"path":"/shooting/control/shutterbutton","post":true}"""
    private var failPress = false
    private val writes = CopyOnWriteArrayList<RecordedRequest>()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method != "GET") {
                    writes += request
                    return MockResponse().setResponseCode(
                        if (failPress && !request.body.clone().readUtf8().contains("release")) 503 else 204,
                    )
                }
                return when (request.requestUrl!!.encodedPath) {
                    "/ccapi" -> json("""{"ver110":[{"path":"/deviceinformation","get":true},$advertised]}""")
                    "/ccapi/ver110/devicestatus/temperature" -> json("""{"status":"disablerelease"}""")
                    else -> json("{}")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    @Test fun directShutterDefaultsOnAndSendsSelectedBooleanWithoutAnAfModeWrite() = runTest {
        val client = nativeClient()
        assertTrue(client.capabilities().shutterAutofocusSupported)
        client.captureStill()
        client.captureStill(autofocus = false)
        assertEquals(listOf(true, false), writes.map { JSONObject(it.body.readUtf8()).getBoolean("af") })
        assertTrue(writes.all { it.method == "POST" && it.path == "/ccapi/ver110/shooting/control/shutterbutton" })
        assertTrue(CameraFeature.STILL_CAPTURE in client.observedFeatureSnapshot())
    }

    @Test fun manualPostAndPutHonorChoiceAndAlwaysReleaseWithoutAf() = runTest {
        for (method in listOf("post", "put")) {
            advertised = """{"path":"/shooting/control/shutterbutton/manual","$method":true}"""
            val client = nativeClient()
            assertTrue(client.capabilities().shutterAutofocusSupported)
            for (af in listOf(true, false)) {
                writes.clear()
                client.captureStill(autofocus = af)
                assertManualPair(method.uppercase(), af)
            }
        }
    }

    @Test fun rejectedManualPressStillReleasesAndDoesNotClaimCapture() = runTest {
        advertised = """{"path":"/shooting/control/shutterbutton/manual","put":true}"""
        val client = nativeClient()
        failPress = true
        assertTrue(runCatching { client.captureStill(autofocus = false) }.isFailure)
        assertManualPair("PUT", false)
        assertFalse(CameraFeature.STILL_CAPTURE in client.observedFeatureSnapshot())
    }

    @Test fun getOnlyShutterCannotAdvertiseOrSendAfSelection() = runTest {
        advertised = """{"path":"/shooting/control/shutterbutton","get":true}"""
        val client = nativeClient()
        assertFalse(client.capabilities().shutterAutofocusSupported)
        val before = server.requestCount
        assertTrue(runCatching { client.captureStill(autofocus = false) }.exceptionOrNull() is IllegalStateException)
        assertEquals(before, server.requestCount)
        assertTrue(writes.isEmpty())
    }

    @Test fun disablingAfDoesNotBypassTheCameraTemperatureRestriction() = runTest {
        advertised += """,{"path":"/devicestatus/temperature","get":true}"""
        val client = nativeClient()
        val failure = runCatching { client.captureStill(autofocus = false) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("temperature restriction"))
        assertTrue(writes.isEmpty())
    }

    @Test fun simplifiedSimulatorAndLegacyBackendRejectUnsupportedFalseBeforeAnyRequest() = runTest {
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true)
        assertTrue(runCatching { client.captureStill(autofocus = false) }.exceptionOrNull() is IllegalStateException)
        val bridge = DesktopBridgeCameraBackend(CameraConnection.DesktopBridge(server.url("/").toString()))
        assertTrue(runCatching { bridge.captureStill(autofocus = false) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, server.requestCount)
    }

    private suspend fun nativeClient() = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        .apply { initialize() }

    private fun assertManualPair(method: String, af: Boolean) {
        assertEquals(2, writes.size)
        assertTrue(writes.all { it.method == method && it.path == "/ccapi/ver110/shooting/control/shutterbutton/manual" })
        val press = JSONObject(writes[0].body.readUtf8())
        val release = JSONObject(writes[1].body.readUtf8())
        assertEquals("full_press", press.getString("action"))
        assertEquals(af, press.getBoolean("af"))
        assertEquals("release", release.getString("action"))
        assertFalse(release.getBoolean("af"))
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
