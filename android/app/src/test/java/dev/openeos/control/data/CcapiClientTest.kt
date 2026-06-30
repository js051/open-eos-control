package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CcapiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: CcapiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CcapiClient(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun infoMapsCameraIdentity() = runTest {
        server.enqueue(jsonResponse(INFO_JSON))

        val info = client.info()
        val request = server.takeRequest()

        assertEquals("/ccapi/info", request.path)
        assertEquals("GET", request.method)
        assertTrue(info.connected)
        assertEquals("Canon EOS R6 Mark III", info.model)
        assertEquals("sim-r6m3", info.serial)
        assertEquals("simulated-ccapi", info.api)
    }

    @Test
    fun statusMapsCameraState() = runTest {
        server.enqueue(jsonResponse(STATUS_JSON))

        val status = client.status()
        val request = server.takeRequest()

        assertEquals("/ccapi/status", request.path)
        assertEquals("GET", request.method)
        assertEquals(82, status.batteryLevel)
        assertEquals("800", status.exposure.iso)
        assertEquals("1/50", status.exposure.shutter)
        assertEquals("2.8", status.exposure.aperture)
        assertEquals("auto", status.exposure.whiteBalance)
    }

    @Test
    fun capabilitiesMapSupportedValues() = runTest {
        server.enqueue(jsonResponse(CAPABILITIES_JSON))

        val capabilities = client.capabilities()

        assertEquals("/ccapi/capabilities", server.takeRequest().path)
        assertEquals(listOf("100", "800", "1600"), capabilities.iso)
        assertEquals(listOf("1/50", "1/100"), capabilities.shutter)
        assertEquals(listOf("2.8", "4.0"), capabilities.aperture)
        assertEquals(listOf("auto", "daylight"), capabilities.whiteBalance)
    }

    @Test
    fun setExposureSendsPatchBody() = runTest {
        server.enqueue(jsonResponse(STATUS_JSON.replace("\"800\"", "\"1600\"")))

        val status = client.setExposure(iso = "1600")
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertEquals("/ccapi/exposure", request.path)
        assertEquals("PATCH", request.method)
        assertEquals("1600", body.getString("iso"))
        assertEquals("1600", status.exposure.iso)
    }

    @Test
    fun startRecordingPostsThenRefreshesStatus() = runTest {
        server.enqueue(jsonResponse("""{"ok":true,"recording":true}"""))
        server.enqueue(jsonResponse(STATUS_JSON.replace("\"recording\": false", "\"recording\": true")))

        val status = client.startRecording()
        val startRequest = server.takeRequest()
        val statusRequest = server.takeRequest()

        assertEquals("/ccapi/record/start", startRequest.path)
        assertEquals("POST", startRequest.method)
        assertEquals("/ccapi/status", statusRequest.path)
        assertTrue(status.recording)
    }

    @Test
    fun tapFocusSendsNormalizedCoordinates() = runTest {
        server.enqueue(jsonResponse("""{"ok":true,"x":0.25,"y":0.75}"""))

        val result = client.tapFocus(0.25, 0.75)
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertEquals("/ccapi/focus/tap", request.path)
        assertEquals("POST", request.method)
        assertEquals(0.25, body.getDouble("x"), 0.0001)
        assertEquals(0.75, body.getDouble("y"), 0.0001)
        assertTrue(result.ok)
        assertEquals(0.25, result.x, 0.0001)
        assertEquals(0.75, result.y, 0.0001)
    }

    @Test
    fun liveViewFrameUrlBuildsCacheBustedFrameUrl() {
        val url = client.liveViewFrameUrl(cacheKey = 42)

        assertEquals("${server.url("/").toString().trimEnd('/')}/ccapi/liveview/frame?t=42", url)
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setHeader("content-type", "application/json")
            .setBody(body)

    private companion object {
        const val INFO_JSON = """
            {
              "connected": true,
              "model": "Canon EOS R6 Mark III",
              "serial": "sim-r6m3",
              "api": "simulated-ccapi"
            }
        """

        const val STATUS_JSON = """
            {
              "connected": true,
              "battery": {"level": 82, "status": "normal"},
              "recording": false,
              "mode": "movie",
              "media": {"available": true, "remaining_minutes": 120},
              "exposure": {
                "iso": "800",
                "shutter": "1/50",
                "aperture": "2.8",
                "white_balance": "auto"
              }
            }
        """

        const val CAPABILITIES_JSON = """
            {
              "iso": ["100", "800", "1600"],
              "shutter": ["1/50", "1/100"],
              "aperture": ["2.8", "4.0"],
              "white_balance": ["auto", "daylight"]
            }
        """
    }
}
