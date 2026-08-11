package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

class CameraRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: CameraRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = CameraRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun connectLoadsCameraSessionAndInitialLiveViewFrame() = runTest {
        server.enqueue(jsonResponse(INFO_JSON))
        server.enqueue(jsonResponse(STATUS_JSON))
        server.enqueue(jsonResponse(CAPABILITIES_JSON))

        val session = repository.connect(server.url("/").toString())

        assertEquals(CameraTransport.CCAPI_NETWORK, session.transport)
        assertEquals(CameraTransport.CCAPI_NETWORK, session.connection.transport)
        assertEquals("Canon EOS R6 Mark III", session.info.model)
        assertEquals("800", session.status.exposure.iso)
        assertEquals(listOf("100", "800", "1600"), session.capabilities.iso)
        assertEquals(CameraModelPriority.PRIMARY, session.capabilities.profile.priority)
        assertEquals(CameraModelFamily.EOS_R, session.capabilities.profile.family)
        assertTrue(session.capabilities.matrix.supports(CameraFeature.LIVE_VIEW))
        assertTrue(session.capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(session.liveViewFrameUrl?.endsWith("/ccapi/liveview/frame?t=1") == true)
        assertEquals("/ccapi/info", server.takeRequest().path)
        assertEquals("/ccapi/status", server.takeRequest().path)
        assertEquals("/ccapi/capabilities", server.takeRequest().path)
    }

    @Test
    fun networkDiagnosticsAreResampledWithoutRecreatingTheCameraClient() = runTest {
        var diagnostics = CameraNetworkDiagnostics(targetHost = "192.168.1.2")
        val routedRepository = CameraRepository(
            CameraBackendFactory(
                httpTransportFactory = CameraHttpTransportFactory {
                    CameraHttpTransport(
                        client = OkHttpClient(),
                        diagnostics = diagnostics,
                        diagnosticsProvider = { diagnostics },
                    )
                }
            )
        )
        server.enqueue(jsonResponse(INFO_JSON))
        server.enqueue(jsonResponse(STATUS_JSON))
        server.enqueue(jsonResponse(CAPABILITIES_JSON))

        val session = routedRepository.connect(server.url("/").toString())
        diagnostics = diagnostics.copy(
            routing = CameraNetworkRouting.WIFI_BOUND,
            cameraNetworkAvailable = true,
            cellularValidated = true,
            systemDefaultTransport = SystemNetworkTransport.CELLULAR,
            systemDefaultValidated = true,
        )

        assertEquals(CameraNetworkRouting.SYSTEM_DEFAULT, session.networkDiagnostics.routing)
        assertTrue(routedRepository.refreshNetworkDiagnostics().wifiCellularCoexistence)
    }

    @Test
    fun nextLiveViewFrameUrlIncrementsCacheKeyAfterConnect() = runTest {
        server.enqueue(jsonResponse(INFO_JSON))
        server.enqueue(jsonResponse(STATUS_JSON))
        server.enqueue(jsonResponse(CAPABILITIES_JSON))

        val session = repository.connect(server.url("/").toString())
        val nextFrame = repository.nextLiveViewFrameUrl()

        assertTrue(session.liveViewFrameUrl?.endsWith("t=1") == true)
        assertTrue(nextFrame.endsWith("t=2"))
    }

    @Test
    fun restartLiveViewReturnsRequestClampedToRefreshedCapabilities() = runTest {
        server.enqueue(jsonResponse(INFO_JSON))
        server.enqueue(jsonResponse(STATUS_JSON))
        server.enqueue(jsonResponse(CAPABILITIES_JSON))
        repository.connect(server.url("/").toString())
        repository.updateLiveViewRequest(fps = 30, size = LiveViewSize.LARGE)
        server.enqueue(jsonResponse(CAPABILITIES_JSON))

        val effectiveRequest = repository.restartLiveView()

        assertEquals(2, effectiveRequest.fps)
        assertEquals(LiveViewSize.MEDIUM, effectiveRequest.size)
    }

    @Test
    fun captureStillRunsThroughRepositoryBackendBoundary() = runTest {
        server.enqueue(jsonResponse(INFO_JSON))
        server.enqueue(jsonResponse(STATUS_JSON))
        server.enqueue(jsonResponse(CAPABILITIES_JSON))
        server.enqueue(jsonResponse("""{"ok":true,"capture_count":1}"""))
        server.enqueue(jsonResponse(STATUS_JSON))
        repository.connect(server.url("/").toString())

        repository.captureStill()

        repeat(3) { server.takeRequest() }
        assertEquals("/ccapi/capture/still", server.takeRequest().path)
        assertEquals("/ccapi/status", server.takeRequest().path)
    }

    @Test
    fun cameraEventsRunThroughRepositoryBackendBoundary() = runTest {
        server.enqueue(jsonResponse(INFO_JSON))
        server.enqueue(jsonResponse(STATUS_JSON))
        server.enqueue(jsonResponse(CAPABILITIES_JSON))
        server.enqueue(jsonResponse("""{"sequence":4,"keys":["shootingsettings"]}"""))
        repository.connect(server.url("/").toString())

        val event = repository.pollEvent()

        repeat(3) { server.takeRequest() }
        assertEquals("/ccapi/events?after=0", server.takeRequest().path)
        assertEquals(setOf("shootingsettings"), event.changedKeys)
    }

    @Test
    fun mediaDownloadStreamsThroughRepositoryBackendBoundary() = runTest {
        server.enqueue(jsonResponse(INFO_JSON))
        server.enqueue(jsonResponse(STATUS_JSON))
        server.enqueue(jsonResponse(CAPABILITIES_JSON))
        repository.connect(server.url("/").toString())
        val bytes = byteArrayOf(4, 3, 2, 1)
        server.enqueue(
            MockResponse()
                .setHeader("content-type", "image/jpeg")
                .setBody(okio.Buffer().write(bytes)),
        )
        val output = ByteArrayOutputStream()
        val item = CameraMediaItem("IMG_0001.JPG", "IMG_0001.JPG", "image")

        val result = repository.downloadMedia(item, output)

        repeat(3) { server.takeRequest() }
        assertEquals("/ccapi/media/IMG_0001.JPG", server.takeRequest().path)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(bytes.size.toLong(), result.bytesTransferred)
    }

    @Test
    fun plannedUsbBackendExposesRoadmapCapabilities() = runTest {
        val backend = CameraBackendFactory().create(CameraConnection.AndroidUsbPtp(deviceName = "r6m3"))
        val capabilities = backend.capabilities()

        assertEquals(CameraTransport.USB_PTP, backend.transport)
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.USB_DIAGNOSTICS))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.STILL_CAPTURE))
    }

    @Test
    fun desktopBridgeConnectionClampsLiveViewRequestToAdvertisedCapabilities() = runTest {
        server.enqueue(jsonResponse(BRIDGE_HEALTH_JSON))
        server.enqueue(jsonResponse(BRIDGE_SESSION_JSON).setResponseCode(201))
        server.enqueue(jsonResponse(BRIDGE_INFO_JSON))
        server.enqueue(jsonResponse(BRIDGE_STATUS_JSON))
        server.enqueue(jsonResponse(BRIDGE_CAPABILITIES_JSON))
        server.enqueue(jsonResponse("""{"active":true,"requestedFps":5}"""))

        val session = repository.connectBridge(
            baseUrl = server.url("/").toString(),
            token = "bridge-token",
            request = LiveViewRequest(fps = 30, size = LiveViewSize.LARGE),
        )

        repeat(5) { server.takeRequest() }
        val startRequest = server.takeRequest()
        val startPayload = JSONObject(startRequest.body.readUtf8())
        assertEquals(CameraTransport.DESKTOP_BRIDGE, session.transport)
        assertEquals(5, session.liveViewRequest.fps)
        assertEquals(LiveViewSize.MEDIUM, session.liveViewRequest.size)
        assertEquals("/v1/session/session-1/liveview/start", startRequest.path)
        assertEquals(5, startPayload.getInt("fps"))
        assertEquals("MEDIUM", startPayload.getString("size"))
        assertNull(session.liveViewFrameUrl)

        server.enqueue(jsonResponse("""{"active":false}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        repository.disconnect()
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

        const val BRIDGE_HEALTH_JSON = """
            {"service":"open-eos-control-bridge","version":"0.1.0","ok":true,"engines":{}}
        """

        const val BRIDGE_SESSION_JSON = """
            {
              "id":"session-1",
              "engine":"libgphoto2",
              "camera":{"id":"camera-1","model":"Canon EOS R6 Mark III","port":"usb:1","engine":"libgphoto2"}
            }
        """

        const val BRIDGE_INFO_JSON = """
            {"connected":true,"model":"Canon EOS R6 Mark III","serial":"bridge-r6m3","api":"desktop-bridge/v1"}
        """

        const val BRIDGE_STATUS_JSON = """
            {
              "connected":true,
              "battery":{"level":82,"status":"normal"},
              "recording":false,
              "mode":"Manual",
              "media":{"available":true,"devices":2},
              "exposure":{"iso":"400","shutter":"1/50","aperture":"2.8","whiteBalance":"Auto"},
              "raw":{"engine":"libgphoto2"}
            }
        """

        const val BRIDGE_CAPABILITIES_JSON = """
            {
              "profile":{"modelName":"Canon EOS R6 Mark III","family":"EOS_R","priority":"PRIMARY"},
              "supported":["DESKTOP_BRIDGE","LIVE_VIEW","LIVE_VIEW_JPEG_POLLING"],
              "planned":["LIVE_VIEW_RTP"],
              "reasons":{},
              "liveView":{
                "sources":["DESKTOP_BRIDGE_STREAM"],
                "defaultSource":"DESKTOP_BRIDGE_STREAM",
                "sizes":["MEDIUM"],
                "defaultSize":"MEDIUM",
                "minFps":1,
                "maxFps":5
              },
              "settings":[]
            }
        """
    }
}
