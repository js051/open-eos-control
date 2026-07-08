package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
        assertEquals(emptyList<CameraSettingControl>(), capabilities.advancedSettings)
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

    @Test
    fun realLiveViewFrameUrlUsesFlipEndpoint() {
        client.forceRealCamera()

        val url = client.liveViewFrameUrl(cacheKey = 42)

        assertEquals("${server.url("/").toString().trimEnd('/')}/ccapi/ver100/shooting/liveview/flip?t=42", url)
    }

    @Test
    fun startLiveViewPostsCanonDisplayAndSize() = runTest {
        client.forceRealCamera()
        server.enqueue(MockResponse().setResponseCode(204))

        client.startLiveView()
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertEquals("/ccapi/ver100/shooting/liveview", request.path)
        assertEquals("POST", request.method)
        assertEquals("on", body.getString("cameradisplay"))
        assertEquals("medium", body.getString("liveviewsize"))
    }

    @Test
    fun realCapabilitiesExposeAdvancedSettingsFromShootingSettings() = runTest {
        client.forceRealCamera(prefixes = listOf("/ccapi/ver110", "/ccapi/ver100"))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        server.enqueue(jsonResponse("""{}"""))

        val capabilities = client.capabilities()
        val metering = capabilities.advancedSettings.first { it.key == "meteringmode" }

        assertEquals("/ccapi/ver110/shooting/settings", server.takeRequest().path)
        assertEquals("/ccapi/ver100/shooting/settings", server.takeRequest().path)
        assertEquals("Metering", metering.label)
        assertEquals("evaluative", metering.value)
        assertEquals(listOf("evaluative", "spot"), metering.values)
    }

    @Test
    fun realStatusUsesBatteryListStorageListAndMergedSettings() = runTest {
        client.forceRealCamera(prefixes = listOf("/ccapi/ver110", "/ccapi/ver100"))
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89,"quality":"good"}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","maxsize":64000000000,"spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        server.enqueue(jsonResponse("""{}"""))

        val status = client.status()

        assertEquals("/ccapi/ver110/devicestatus/batterylist", server.takeRequest().path)
        assertEquals("/ccapi/ver110/devicestatus/storage", server.takeRequest().path)
        assertEquals("/ccapi/ver110/shooting/settings", server.takeRequest().path)
        assertEquals("/ccapi/ver100/shooting/settings", server.takeRequest().path)
        assertEquals(89, status.batteryLevel)
        assertEquals("89%", status.batteryStatus)
        assertTrue(status.mediaAvailable)
        assertEquals("800", status.exposure.iso)
        assertEquals("1/50", status.exposure.shutter)
        assertEquals("2.8", status.exposure.aperture)
        assertEquals("auto", status.exposure.whiteBalance)
    }

    @Test
    fun realSetExposureUsesVersionedSettingPathAndAcceptsEmptySuccess() = runTest {
        client.forceRealCamera(prefixes = listOf("/ccapi/ver110", "/ccapi/ver100"))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        server.enqueue(jsonResponse("""{}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89,"quality":"good"}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","maxsize":64000000000,"spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON.replace("\"1/50\"", "\"1/100\"")))
        server.enqueue(jsonResponse("""{}"""))

        val status = client.setExposure(shutter = "1/100")

        assertEquals("/ccapi/ver110/shooting/settings", server.takeRequest().path)
        assertEquals("/ccapi/ver100/shooting/settings", server.takeRequest().path)
        val putRequest = server.takeRequest()
        assertEquals("/ccapi/ver110/shooting/settings/tv", putRequest.path)
        assertEquals("PUT", putRequest.method)
        assertEquals("1/100", JSONObject(putRequest.body.readUtf8()).getString("value"))
        repeat(4) { server.takeRequest() }
        assertEquals("1/100", status.exposure.shutter)
    }

    @Test
    fun realSetCameraSettingUsesDiscoveredAdvancedSettingPath() = runTest {
        client.forceRealCamera(prefixes = listOf("/ccapi/ver110", "/ccapi/ver100"))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        server.enqueue(jsonResponse("""{}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89,"quality":"good"}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","maxsize":64000000000,"spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON.replace("\"evaluative\"", "\"spot\"")))
        server.enqueue(jsonResponse("""{}"""))

        val status = client.setSetting("meteringmode", "spot")

        assertEquals("/ccapi/ver110/shooting/settings", server.takeRequest().path)
        assertEquals("/ccapi/ver100/shooting/settings", server.takeRequest().path)
        val putRequest = server.takeRequest()
        assertEquals("/ccapi/ver110/shooting/settings/meteringmode", putRequest.path)
        assertEquals("PUT", putRequest.method)
        assertEquals("spot", JSONObject(putRequest.body.readUtf8()).getString("value"))
        repeat(4) { server.takeRequest() }
        assertEquals("800", status.exposure.iso)
    }

    @Test
    fun realRecordingAcceptsEmptyCommandSuccess() = runTest {
        client.forceRealCamera(prefixes = listOf("/ccapi/ver110", "/ccapi/ver100"))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89,"quality":"good"}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","maxsize":64000000000,"spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        server.enqueue(jsonResponse("""{}"""))

        val status = client.startRecording()
        val recordRequest = server.takeRequest()

        assertEquals("/ccapi/ver100/shooting/control/recbutton", recordRequest.path)
        assertEquals("POST", recordRequest.method)
        assertTrue(status.recording)
    }

    @Test
    fun liveViewFrameExtractsSingleJpegFrame() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())
        server.enqueue(binaryResponse(jpeg, "image/jpeg"))

        val frame = client.liveViewFrame(cacheKey = 7)
        val request = server.takeRequest()

        assertEquals("/ccapi/liveview/frame?t=7", request.path)
        assertEquals("GET", request.method)
        assertEquals("image/jpeg", frame.contentType)
        assertEquals("${server.url("/").toString().trimEnd('/')}/ccapi/liveview/frame?t=7", frame.sourceUrl)
        assertArrayEquals(jpeg, frame.bytes)
    }

    @Test
    fun liveViewFrameExtractsFirstJpegFromMultipartStream() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x03, 0x04, 0xFF.toByte(), 0xD9.toByte())
        val multipart = "--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() +
            jpeg +
            "\r\n--frame\r\n".toByteArray()
        server.enqueue(binaryResponse(multipart, "multipart/x-mixed-replace; boundary=frame"))

        val frame = client.liveViewFrame(cacheKey = 8)

        assertEquals("/ccapi/liveview/frame?t=8", server.takeRequest().path)
        assertEquals("multipart/x-mixed-replace; boundary=frame", frame.contentType)
        assertArrayEquals(jpeg, frame.bytes)
    }

    @Test
    fun liveViewFrameFallsBackFromFlipToFlipDetail() = runTest {
        client.forceRealCamera()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x05, 0x06, 0xFF.toByte(), 0xD9.toByte())
        server.enqueue(MockResponse().setResponseCode(404).setHeader("content-type", "text/plain").setBody("not found"))
        server.enqueue(binaryResponse(jpeg, "image/jpeg"))

        val frame = client.liveViewFrame(cacheKey = 9)
        val flipRequest = server.takeRequest()
        val flipDetailRequest = server.takeRequest()

        assertEquals("/ccapi/ver100/shooting/liveview/flip?t=9", flipRequest.path)
        assertEquals("/ccapi/ver100/shooting/liveview/flipdetail?kind=image&t=9", flipDetailRequest.path)
        assertArrayEquals(jpeg, frame.bytes)
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setHeader("content-type", "application/json")
            .setBody(body)

    private fun binaryResponse(body: ByteArray, contentType: String): MockResponse =
        MockResponse()
            .setHeader("content-type", contentType)
            .setBody(Buffer().write(body))

    private fun CcapiClient.forceRealCamera(
        prefix: String = "/ccapi/ver100",
        prefixes: List<String> = listOf(prefix),
    ) {
        javaClass.getDeclaredField("isRealCamera").apply {
            isAccessible = true
            setBoolean(this@forceRealCamera, true)
        }
        javaClass.getDeclaredField("apiVersionPrefix").apply {
            isAccessible = true
            set(this@forceRealCamera, prefix)
        }
        javaClass.getDeclaredField("apiVersionPrefixes").apply {
            isAccessible = true
            set(this@forceRealCamera, prefixes)
        }
    }

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

        const val REAL_SETTINGS_JSON = """
            {
              "iso": {"value": "800", "ability": ["100", "800", "1600"]},
              "tv": {"value": "1/50", "ability": ["1/50", "1/100"]},
              "av": {"value": "2.8", "ability": ["2.8", "4.0"]},
              "wb": {"value": "auto", "ability": ["auto", "daylight"]},
              "meteringmode": {"value": "evaluative", "ability": ["evaluative", "spot"]},
              "afmethod": {"value": "face+tracking", "ability": ["face+tracking", "1-point"]},
              "shootingmode": {"value": "movie", "ability": ["movie"]}
            }
        """
    }
}
