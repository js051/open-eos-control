package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

class DesktopBridgeClientTest {
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
    fun bridgeClientMapsCompleteSessionContractAndBearerAuthentication() = runTest {
        val dispatcher = BridgeDispatcher()
        server.dispatcher = dispatcher
        val client = DesktopBridgeClient(
            baseUrl = server.url("/").toString(),
            token = "bridge-secret",
            cameraId = "camera-r6m3",
        )

        val cameras = client.discoverCameras()
        client.initialize()
        val info = client.info()
        val initialStatus = client.status()
        val capabilities = client.capabilities()
        val exposureStatus = client.setExposure(iso = "800")
        val whiteBalanceStatus = client.setWhiteBalance("Daylight")
        client.captureStill()
        client.autofocus()
        client.halfPressShutter()
        val recording = client.startRecording()
        val stopped = client.stopRecording()
        val clickWhiteBalanceStatus = client.clickWhiteBalance(0.4, 0.6)
        client.startLiveView(LiveViewRequest(fps = 5, size = LiveViewSize.MEDIUM))
        val frame = client.liveViewFrame(9)
        val focus = client.driveFocus(FocusDriveDirection.FAR, FocusDriveStep.LARGE)
        val media = client.listMedia()
        val thumbnail = client.mediaThumbnail(media.single())
        val destination = ByteArrayOutputStream()
        val progress = mutableListOf<CameraMediaTransferProgress>()
        val download = client.downloadMedia(media.single(), destination, progress::add)
        client.deleteMedia(media.single())
        client.stopLiveView()
        val observedFeatures = client.observedFeatureSnapshot()
        client.close()

        assertEquals("Canon EOS R6 Mark III", cameras.single().model)
        assertEquals("camera-r6m3", cameras.single().id)
        assertEquals("TEST-SERIAL-0001", info.serial)
        assertEquals("gphoto2 2.5.33", info.engineVersion)
        assertEquals(82, initialStatus.batteryLevel)
        assertEquals(2048L, initialStatus.storageTotalBytes)
        assertEquals(1024L, initialStatus.storageFreeBytes)
        assertEquals(123L, initialStatus.storageFreeImages)
        assertEquals(2, initialStatus.storageDeviceCount)
        assertEquals("800", exposureStatus.exposure.iso)
        assertEquals("Daylight", whiteBalanceStatus.exposure.whiteBalance)
        assertTrue(recording.recording == true)
        assertFalse(stopped.recording == true)
        assertEquals("click", clickWhiteBalanceStatus.exposure.whiteBalance)
        assertTrue(capabilities.matrix.supports(CameraFeature.DESKTOP_BRIDGE))
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_JPEG_POLLING))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_DELETE))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_THUMBNAIL))
        assertTrue(capabilities.matrix.supports(CameraFeature.CLICK_WHITE_BALANCE))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.LIVE_VIEW_RTP))
        assertFalse(capabilities.matrix.supports(CameraFeature.USB_DIAGNOSTICS))
        assertEquals(listOf("Auto", "100", "400", "800"), capabilities.iso)
        assertEquals("drivemode", capabilities.advancedSettings.single().key)
        assertEquals(5, capabilities.liveView.maxFps)
        assertEquals("gphoto2 --abilities + --list-all-config", capabilities.evidence.source)
        assertEquals(listOf("gphoto2 2.5.33"), capabilities.evidence.protocolVersions)
        assertTrue("CAPTURE_PREVIEW" in capabilities.evidence.advertisedCommands)
        assertTrue("/main/imgsettings/iso" in capabilities.evidence.writableSettings)
        assertTrue(CameraFeature.BATTERY_STATUS in capabilities.evidence.observedFeatures)
        assertTrue(
            observedFeatures.containsAll(
                setOf(
                    CameraFeature.DESKTOP_BRIDGE,
                    CameraFeature.CAMERA_IDENTITY,
                    CameraFeature.EXPOSURE_CONTROL,
                    CameraFeature.WHITE_BALANCE_CONTROL,
                    CameraFeature.STILL_CAPTURE,
                    CameraFeature.AUTOFOCUS,
                    CameraFeature.SHUTTER_HALF_PRESS,
                    CameraFeature.VIDEO_RECORDING,
                    CameraFeature.CLICK_WHITE_BALANCE,
                    CameraFeature.FOCUS_DRIVE,
                    CameraFeature.LIVE_VIEW,
                    CameraFeature.LIVE_VIEW_JPEG_POLLING,
                    CameraFeature.MEDIA_BROWSER,
                    CameraFeature.MEDIA_THUMBNAIL,
                    CameraFeature.MEDIA_DOWNLOAD,
                    CameraFeature.MEDIA_DELETE,
                ),
            ),
        )
        assertArrayEquals(JPEG, frame.bytes)
        assertTrue(frame.sourceUrl.endsWith("/liveview/frame?t=9"))
        assertTrue(focus.ok)
        assertEquals("IMG_0001.JPG", media.single().name)
        assertArrayEquals(THUMBNAIL, thumbnail.bytes)
        assertEquals("image/jpeg", thumbnail.contentType)
        assertArrayEquals(MEDIA_BYTES, destination.toByteArray())
        assertEquals(MEDIA_BYTES.size.toLong(), download.bytesTransferred)
        assertEquals(0L, progress.first().bytesTransferred)
        assertEquals(MEDIA_BYTES.size.toLong(), progress.last().bytesTransferred)

        val requests = dispatcher.requests.toList()
        assertTrue(requests.filter { it.requestUrl?.encodedPath?.startsWith("/v1/") == true }.all {
            it.getHeader("Authorization") == "Bearer bridge-secret"
        })
        val sessionRequest = requests.first { it.requestUrl?.encodedPath == "/v1/session" }
        val sessionPayload = JSONObject(sessionRequest.body.readUtf8())
        assertEquals("camera-r6m3", sessionPayload.getString("cameraId"))
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/settings/iso") == true })
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/capture/still") == true })
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/focus/auto") == true })
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/shutter/half-press") == true })
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/focus/drive") == true })
        val clickWhiteBalanceRequest = requests.first {
            it.requestUrl?.encodedPath?.endsWith("/whitebalance/click") == true
        }
        val clickWhiteBalancePayload = JSONObject(clickWhiteBalanceRequest.body.readUtf8())
        assertEquals(0.4, clickWhiteBalancePayload.getDouble("x"), 0.0001)
        assertEquals(0.6, clickWhiteBalancePayload.getDouble("y"), 0.0001)
        assertTrue(requests.any { it.method == "DELETE" && it.requestUrl?.encodedPath?.contains("/media/") == true })
        assertTrue(requests.any { it.method == "DELETE" && it.requestUrl?.encodedPath == "/v1/session/session-1" })
    }

    @Test
    fun bridgeErrorsPreserveStableCodeFeatureAndEngine() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(
            jsonResponse(
                """
                {
                  "error": {
                    "code": "CAMERA_BUSY",
                    "message": "Camera is already open.",
                    "feature": "STILL_CAPTURE",
                    "engine": "libgphoto2"
                  }
                }
                """.trimIndent(),
                code = 409,
            )
        )
        val client = DesktopBridgeClient(server.url("/").toString(), token = "top-secret")

        val failure = runCatching { client.initialize() }.exceptionOrNull() as DesktopBridgeException

        assertEquals("CAMERA_BUSY", failure.code)
        assertEquals("STILL_CAPTURE", failure.feature)
        assertEquals("libgphoto2", failure.engine)
        assertTrue(failure.message.orEmpty().contains("Camera is already open"))
        assertFalse(failure.message.orEmpty().contains("top-secret"))
    }

    @Test
    fun factoryCreatesExecutableDesktopBridgeBackend() {
        val backend = CameraBackendFactory().create(
            CameraConnection.DesktopBridge(server.url("/").toString(), token = "test")
        )

        assertTrue(backend is DesktopBridgeCameraBackend)
        assertEquals(CameraTransport.DESKTOP_BRIDGE, backend.transport)
        assertTrue(backend.prefersBitmapLiveViewFrames)
    }

    @Test
    fun bridgeUrlRejectsEmbeddedCredentialsAndQueryTokens() {
        val credentialFailure = runCatching {
            DesktopBridgeClient("http://user:password@127.0.0.1:18181")
        }.exceptionOrNull()
        val queryFailure = runCatching {
            DesktopBridgeClient("http://127.0.0.1:18181?token=secret")
        }.exceptionOrNull()

        assertTrue(credentialFailure is IllegalArgumentException)
        assertTrue(queryFailure is IllegalArgumentException)
    }

    private fun jsonResponse(body: String, code: Int = 200): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("content-type", "application/json")
        .setBody(body)

    private class BridgeDispatcher : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private var iso = "400"
        private var whiteBalance = "Auto"
        private var recording = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = request.requestUrl?.encodedPath.orEmpty()
            return when {
                path == "/health" -> json(HEALTH_JSON)
                path == "/v1/cameras" -> json(CAMERAS_JSON)
                path == "/v1/session" && request.method == "POST" -> json(SESSION_JSON, 201)
                path.endsWith("/info") -> json(INFO_JSON)
                path.endsWith("/status") -> json(statusJson())
                path.endsWith("/capabilities") -> json(CAPABILITIES_JSON)
                path.endsWith("/settings/iso") -> {
                    iso = JSONObject(request.body.readUtf8()).getString("value")
                    json(statusJson())
                }
                path.endsWith("/settings/whitebalance") -> {
                    whiteBalance = JSONObject(request.body.readUtf8()).getString("value")
                    json(statusJson())
                }
                path.endsWith("/whitebalance/click") -> {
                    whiteBalance = "click"
                    json(statusJson())
                }
                path.endsWith("/capture/still") -> json(statusJson())
                path.endsWith("/focus/auto") -> json(statusJson())
                path.endsWith("/shutter/half-press") -> json(statusJson())
                path.endsWith("/recording/start") -> {
                    recording = true
                    json(statusJson())
                }
                path.endsWith("/recording/stop") -> {
                    recording = false
                    json(statusJson())
                }
                path.endsWith("/liveview/start") -> json("""{"active":true,"requestedFps":5}""")
                path.endsWith("/liveview/stop") -> json("""{"active":false}""")
                path.endsWith("/liveview/frame") -> MockResponse()
                    .setHeader("content-type", "image/jpeg")
                    .setBody(okio.Buffer().write(JPEG))
                path.endsWith("/focus/drive") -> json("""{"accepted":true,"direction":"FAR","step":"LARGE"}""")
                path.endsWith("/media") -> json(MEDIA_JSON)
                path.endsWith("/thumbnail") -> MockResponse()
                    .setHeader("content-type", "image/jpeg")
                    .setBody(okio.Buffer().write(THUMBNAIL))
                path.contains("/media/") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                path.contains("/media/") -> MockResponse()
                    .setHeader("content-type", "image/jpeg")
                    .setHeader("content-length", MEDIA_BYTES.size)
                    .setBody(okio.Buffer().write(MEDIA_BYTES))
                path == "/v1/session/session-1" && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404).setBody("Unexpected $path")
            }
        }

        private fun statusJson(): String =
            """
            {
              "connected": true,
              "battery": {"level": 82, "status": "normal"},
              "recording": $recording,
              "mode": "Manual",
              "media": {"available": true, "totalBytes": 2048, "freeBytes": 1024, "freeImages": 123, "devices": 2},
              "exposure": {
                "iso": "$iso",
                "shutter": "1/50",
                "aperture": "2.8",
                "whiteBalance": "$whiteBalance"
              },
              "raw": {"engine": "libgphoto2", "port": "usb:001,007"}
            }
            """.trimIndent()

        private fun json(body: String, code: Int = 200): MockResponse = MockResponse()
            .setResponseCode(code)
            .setHeader("content-type", "application/json")
            .setBody(body)
    }

    private companion object {
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val THUMBNAIL = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 4, 2, 0xFF.toByte(), 0xD9.toByte())
        val MEDIA_BYTES = byteArrayOf(9, 8, 7, 6, 5)

        const val HEALTH_JSON = """
            {
              "ok": true,
              "service": "open-eos-control-bridge",
              "version": "0.1.0",
              "authRequired": true,
              "loopbackOnly": false,
              "engines": {"libgphoto2": {"available": true, "version": "gphoto2 2.5.33"}}
            }
        """

        const val CAMERAS_JSON = """
            {
              "cameras": [
                {
                  "id": "camera-r6m3",
                  "model": "Canon EOS R6 Mark III",
                  "port": "usb:001,007",
                  "engine": "libgphoto2"
                }
              ]
            }
        """

        const val SESSION_JSON = """
            {
              "id": "session-1",
              "engine": "libgphoto2",
              "camera": {
                "id": "camera-r6m3",
                "model": "Canon EOS R6 Mark III",
                "port": "usb:001,007",
                "engine": "libgphoto2"
              }
            }
        """

        const val INFO_JSON = """
            {
              "connected": true,
              "model": "Canon EOS R6 Mark III",
              "serial": "TEST-SERIAL-0001",
              "api": "desktop-bridge/v1/libgphoto2",
              "manufacturer": "Canon.Inc",
              "deviceVersion": "3-1.0.0",
              "engineVersion": "gphoto2 2.5.33"
            }
        """

        const val CAPABILITIES_JSON = """
            {
              "profile": {"modelName":"Canon EOS R6 Mark III","family":"EOS_R","priority":"PRIMARY"},
              "supported": [
                "CAMERA_IDENTITY", "DESKTOP_BRIDGE", "LIVE_VIEW", "LIVE_VIEW_JPEG_POLLING",
                "STILL_CAPTURE", "AUTOFOCUS", "SHUTTER_HALF_PRESS", "VIDEO_RECORDING", "FOCUS_DRIVE",
                "EXPOSURE_CONTROL", "WHITE_BALANCE_CONTROL", "CLICK_WHITE_BALANCE", "ADVANCED_SETTINGS",
                "MEDIA_BROWSER", "MEDIA_THUMBNAIL", "MEDIA_DOWNLOAD", "MEDIA_DELETE", "A_FUTURE_FEATURE"
              ],
              "planned": ["TAP_FOCUS", "LIVE_VIEW_RTP"],
              "reasons": {"LIVE_VIEW_RTP": "Persistent stream is not implemented."},
              "liveView": {
                "sources": ["DESKTOP_BRIDGE_STREAM"],
                "defaultSource": "DESKTOP_BRIDGE_STREAM",
                "sizes": ["MEDIUM"],
                "defaultSize": "MEDIUM",
                "minFps": 1,
                "maxFps": 5
              },
              "settings": [
                {"key":"iso","label":"ISO","value":"400","values":["Auto","100","400","800"]},
                {"key":"whitebalance","label":"White balance","value":"Auto","values":["Auto","Daylight"]},
                {"key":"drivemode","label":"Drive mode","value":"Single","values":["Single","Continuous"]}
              ],
              "evidence": {
                "source": "gphoto2 --abilities + --list-all-config",
                "protocolVersions": ["gphoto2 2.5.33"],
                "advertisedCommands": ["CAPTURE_IMAGE", "CAPTURE_PREVIEW"],
                "writableSettings": ["/main/imgsettings/iso"],
                "observedFeatures": ["BATTERY_STATUS"],
                "truncated": false
              }
            }
        """

        const val MEDIA_JSON = """
            {
              "items": [
                {
                  "id": "gphoto2:media-id",
                  "name": "IMG_0001.JPG",
                  "kind": "image",
                  "sizeBytes": 5,
                  "captureTime": "2026-07-21T02:13:21Z",
                  "contentType": "image/jpeg"
                }
              ]
            }
        """
    }
}
