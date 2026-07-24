package dev.openeos.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun authenticatedRequestsSendBasicAuthorizationHeader() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            username = "camera-user",
            password = "camera-password",
        )
        server.enqueue(jsonResponse(INFO_JSON))

        client.info()

        assertEquals(
            Credentials.basic("camera-user", "camera-password"),
            server.takeRequest().getHeader("Authorization"),
        )
    }

    @Test
    fun authenticatedRequestsKeepAuthorizationWithInjectedHttpClient() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            httpClient = OkHttpClient(),
            username = "camera-user",
            password = "camera-password",
        )
        server.enqueue(jsonResponse(INFO_JSON))

        client.info()

        assertEquals(
            Credentials.basic("camera-user", "camera-password"),
            server.takeRequest().getHeader("Authorization"),
        )
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
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW))
        assertEquals(listOf(LiveViewSource.SIMULATOR_FRAME), capabilities.liveView.sources)
        assertEquals(2, capabilities.liveView.maxFps)
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
        assertTrue(status.recording == true)
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
    fun realTapFocusDoesNotReportPlainAutofocusAsPositionSuccess() = runTest {
        client.forceRealCamera()
        server.enqueue(MockResponse().setResponseCode(404).setBody("unsupported"))

        val failure = runCatching { client.tapFocus(0.25, 0.75) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, server.requestCount)
        assertEquals("/ccapi/ver100/shooting/control/afpoint", server.takeRequest().path)
    }

    @Test
    fun realTapFocusUsesAdvertisedPutMethod() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver110":[{"path":"/shooting/control/afpoint","put":true}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))

        client.initialize()
        client.tapFocus(0.25, 0.75)

        server.takeRequest()
        val focus = server.takeRequest()
        assertEquals("PUT", focus.method)
        assertEquals("/ccapi/ver110/shooting/control/afpoint", focus.path)
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

        client.startLiveView(LiveViewRequest(size = LiveViewSize.LARGE))
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertEquals("/ccapi/ver100/shooting/liveview", request.path)
        assertEquals("POST", request.method)
        assertEquals("on", body.getString("cameradisplay"))
        assertEquals("large", body.getString("liveviewsize"))
    }

    @Test
    fun startLiveViewRetriesWithoutSizeWhenCameraRejectsRequestedParameters() = runTest {
        client.forceRealCamera()
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"message":"Invalid parameter"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))

        client.startLiveView(LiveViewRequest(size = LiveViewSize.MEDIUM))
        val capabilities = client.capabilities()
        val rejectedRequest = server.takeRequest()
        val fallbackRequest = server.takeRequest()
        val rejectedBody = JSONObject(rejectedRequest.body.readUtf8())
        val fallbackBody = JSONObject(fallbackRequest.body.readUtf8())

        assertEquals("medium", rejectedBody.getString("liveviewsize"))
        assertEquals("on", fallbackBody.getString("cameradisplay"))
        assertTrue(!fallbackBody.has("liveviewsize"))
        assertEquals(listOf(LiveViewSize.MEDIUM), capabilities.liveView.sizes)
    }

    @Test
    fun startLiveViewDoesNotHideServerFailuresBehindParameterFallback() = runTest {
        client.forceRealCamera()
        server.enqueue(MockResponse().setResponseCode(503).setBody("camera busy"))

        val failure = runCatching { client.startLiveView() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("HTTP 503"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun discoveryFallbackUsesTheVersionThatActuallyResponded() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(jsonResponse("""{"productname":"Canon EOS R6 Mark III"}"""))

        client.initialize()
        val failure = runCatching { client.startLiveView() }.exceptionOrNull()

        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/", server.takeRequest().path)
        assertEquals("/ccapi/ver110/deviceinformation", server.takeRequest().path)
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("complete Live View"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun realCapabilitiesFollowAdvertisedApiOperations() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        server.enqueue(jsonResponse(DISCOVERY_JSON))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))

        client.initialize()
        val capabilities = client.capabilities()

        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/ver110/shooting/settings", server.takeRequest().path)
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW))
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_JPEG_POLLING))
        assertTrue(capabilities.matrix.supports(CameraFeature.VIDEO_RECORDING))
        assertTrue(capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(!capabilities.matrix.isPlanned(CameraFeature.STILL_CAPTURE))
        assertTrue(capabilities.matrix.supports(CameraFeature.SHUTTER_HALF_PRESS))
        assertTrue(capabilities.matrix.supports(CameraFeature.FOCUS_DRIVE))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_BROWSER))
        assertTrue(!capabilities.matrix.supports(CameraFeature.MEDIA_THUMBNAIL))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.MEDIA_THUMBNAIL))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_DOWNLOAD))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_DELETE))
        assertTrue(capabilities.matrix.supports(CameraFeature.EXPOSURE_CONTROL))
        assertTrue(!capabilities.matrix.supports(CameraFeature.TAP_FOCUS))
        assertTrue(!capabilities.matrix.supports(CameraFeature.BATTERY_STATUS))
        assertEquals("GET /ccapi", capabilities.evidence.source)
        assertEquals(listOf("ver110"), capabilities.evidence.protocolVersions)
        assertTrue("POST /ccapi/ver110/shooting/control/shutterbutton" in capabilities.evidence.advertisedCommands)
        assertTrue("iso" in capabilities.evidence.writableSettings)
        assertTrue(!capabilities.evidence.truncated)
    }

    @Test
    fun discoveryAcceptsSameOriginUrlEntriesAndRejectsUnsafeOperations() = runTest {
        val cameraOrigin = server.url("/").toString().trimEnd('/')
        client = CcapiClient(cameraOrigin, treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """
                {
                  "ver100": [
                    {"url":"$cameraOrigin/ccapi/ver100/devicestatus/storage?token=secret","get":true},
                    {"url":"$cameraOrigin/ccapi/ver100/shooting/settings","get":true},
                    {"url":"$cameraOrigin/ccapi/ver100/shooting/settings/iso","put":true},
                    {"url":"$cameraOrigin/ccapi/ver100/shooting/control/shutterbutton","post":true},
                    {"url":"http://attacker.invalid/ccapi/ver100/shooting/control/recbutton","post":true},
                    {"url":"$cameraOrigin/ccapi/ver100/ignored/../shooting/control/recbutton","post":true}
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))

        client.initialize()
        val capabilities = client.capabilities()

        assertTrue(capabilities.matrix.supports(CameraFeature.EXPOSURE_CONTROL))
        assertTrue(capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(!capabilities.matrix.supports(CameraFeature.VIDEO_RECORDING))
        assertTrue("GET /ccapi/ver100/devicestatus/storage" in capabilities.evidence.advertisedCommands)
        assertTrue("POST /ccapi/ver100/shooting/control/shutterbutton" in capabilities.evidence.advertisedCommands)
        assertTrue(capabilities.evidence.advertisedCommands.none { "secret" in it || "attacker" in it })
        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/ver100/shooting/settings", server.takeRequest().path)
    }

    @Test
    fun realFocusDriveUsesAdvertisedPostAndCanonValue() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver110":[{"path":"/shooting/control/drivefocus","post":true}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))

        client.initialize()
        val result = client.driveFocus(FocusDriveDirection.NEAR, FocusDriveStep.MEDIUM)

        assertEquals("/ccapi", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/ccapi/ver110/shooting/control/drivefocus", request.path)
        assertEquals("near2", JSONObject(request.body.readUtf8()).getString("value"))
        assertTrue(result.ok)
        assertEquals(FocusDriveDirection.NEAR, result.direction)
        assertEquals(FocusDriveStep.MEDIUM, result.step)
    }

    @Test
    fun realFocusDriveRejectsUnadvertisedCommandWithoutRequest() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse("""{"ver110":[{"path":"/deviceinformation","get":true}]}"""))

        client.initialize()
        val failure = runCatching {
            client.driveFocus(FocusDriveDirection.FAR, FocusDriveStep.LARGE)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("did not advertise manual focus drive"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun realCapabilityEvidenceIsBoundedAndRemovesQueries() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        val longSegment = "x".repeat(600)
        val entries = (0 until 300).joinToString(",") { index ->
            """{"path":"/diagnostics/item$index/$longSegment?token=secret","get":true}"""
        }
        server.enqueue(jsonResponse("""{"ver100":[$entries]}"""))

        client.initialize()
        val evidence = client.capabilities().evidence

        assertEquals(MAX_CAPABILITY_EVIDENCE_ITEMS, evidence.advertisedCommands.size)
        assertTrue(evidence.truncated)
        assertTrue(evidence.advertisedCommands.none { "?" in it || "secret" in it })
        assertTrue(evidence.advertisedCommands.all { it.length <= MAX_CAPABILITY_EVIDENCE_ITEM_CHARS })
    }

    @Test
    fun realCapabilitiesRejectReadOnlySettingsWrongShutterMethodAndIncompleteLiveView() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """
                {
                  "ver100": [
                    {"path":"/shooting/settings","get":true},
                    {"path":"/shooting/control/shutterbutton","put":true},
                    {"path":"/shooting/liveview","post":true},
                    {"path":"/shooting/liveview/flip","get":true}
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))

        client.initialize()
        val capabilities = client.capabilities()
        val requestCount = server.requestCount
        val captureFailure = runCatching { client.captureStill() }.exceptionOrNull()
        val settingFailure = runCatching { client.setExposure(iso = "1600") }.exceptionOrNull()
        val liveViewFailure = runCatching { client.startLiveView() }.exceptionOrNull()

        assertTrue(!capabilities.matrix.supports(CameraFeature.EXPOSURE_CONTROL))
        assertTrue(!capabilities.matrix.supports(CameraFeature.ADVANCED_SETTINGS))
        assertTrue(!capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(!capabilities.matrix.supports(CameraFeature.LIVE_VIEW))
        assertEquals(emptyList<CameraSettingControl>(), capabilities.advancedSettings)
        assertTrue(captureFailure is IllegalStateException)
        assertTrue(settingFailure is IllegalStateException)
        assertTrue(liveViewFailure is IllegalStateException)
        assertEquals(requestCount, server.requestCount)
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
        assertEquals(listOf(LiveViewSize.SMALL, LiveViewSize.MEDIUM, LiveViewSize.LARGE), capabilities.liveView.sizes)
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
        assertTrue(status.mediaAvailable == true)
        assertEquals("800", status.exposure.iso)
        assertEquals("1/50", status.exposure.shutter)
        assertEquals("2.8", status.exposure.aperture)
        assertEquals("auto", status.exposure.whiteBalance)
    }

    @Test
    fun unavailableRealStatusValuesRemainUnknown() = runTest {
        client.forceRealCamera()
        repeat(6) {
            server.enqueue(MockResponse().setResponseCode(404))
        }

        val status = client.status()

        assertNull(status.batteryLevel)
        assertEquals("unknown", status.batteryStatus)
        assertNull(status.recording)
        assertEquals("unknown", status.mode)
        assertNull(status.mediaAvailable)
        assertNull(status.remainingMinutes)
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
    fun realSetExposureRejectsValueOutsideCameraAbilityWithoutPut() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse(DISCOVERY_JSON))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))

        client.initialize()
        client.capabilities()
        val requestCount = server.requestCount
        val failure = runCatching { client.setExposure(iso = "51200") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("not advertised"))
        assertEquals(requestCount, server.requestCount)
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
        assertTrue(status.recording == true)
    }

    @Test
    fun realStillCaptureUsesAdvertisedShutterEndpointWithAutofocus() = runTest {
        client.forceRealCamera(
            prefix = "/ccapi/ver110",
            prefixes = listOf("/ccapi/ver110", "/ccapi/ver100"),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89,"quality":"good"}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        server.enqueue(jsonResponse("""{}"""))

        client.captureStill()
        val request = server.takeRequest()

        assertEquals("/ccapi/ver110/shooting/control/shutterbutton", request.path)
        assertEquals("POST", request.method)
        assertTrue(JSONObject(request.body.readUtf8()).getBoolean("af"))
    }

    @Test
    fun realStillCaptureUsesAdvertisedManualPutAndAlwaysReleasesShutter() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver110":[{"path":"/shooting/control/shutterbutton/manual","put":true}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        enqueueRealStatus()

        client.initialize()
        client.captureStill()

        assertEquals("/ccapi", server.takeRequest().path)
        val press = server.takeRequest()
        val release = server.takeRequest()
        assertEquals("PUT", press.method)
        assertEquals("full_press", JSONObject(press.body.readUtf8()).getString("action"))
        assertEquals("PUT", release.method)
        assertEquals("release", JSONObject(release.body.readUtf8()).getString("action"))
    }

    @Test
    fun halfPressUsesManualCommandThenReleasesShutter() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver110":[{"path":"/shooting/control/shutterbutton/manual","post":true}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        enqueueRealStatus()

        client.initialize()
        client.halfPressShutter()

        server.takeRequest()
        val press = server.takeRequest()
        val release = server.takeRequest()
        val pressBody = JSONObject(press.body.readUtf8())
        assertEquals("half_press", pressBody.getString("action"))
        assertTrue(pressBody.getBoolean("af"))
        assertEquals("release", JSONObject(release.body.readUtf8()).getString("action"))
    }

    @Test
    fun cancellingHalfPressStillReleasesShutter() = runBlocking {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver110":[{"path":"/shooting/control/shutterbutton/manual","put":true}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        client.initialize()
        server.takeRequest()
        val job = launch(Dispatchers.Default) { client.halfPressShutter() }
        val press = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        val release = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        assertEquals("half_press", JSONObject(press.body.readUtf8()).getString("action"))
        assertEquals("release", JSONObject(release.body.readUtf8()).getString("action"))
    }

    @Test
    fun realMediaListTraversesStorageDirectoriesAndAllPages() = runTest {
        client.forceRealCamera(prefix = "/ccapi/ver110")
        server.enqueue(jsonResponse("""{"pagenumber":1}"""))
        server.enqueue(jsonResponse("""{"path":["/ccapi/ver110/contents/card1"]}"""))
        server.enqueue(jsonResponse("""{"pagenumber":1}"""))
        server.enqueue(jsonResponse("""{"path":["/ccapi/ver110/contents/card1/100CANON"]}"""))
        server.enqueue(jsonResponse("""{"pagenumber":2}"""))
        server.enqueue(jsonResponse("""{"path":["/ccapi/ver110/contents/card1/100CANON/IMG_0002.JPG"]}"""))
        server.enqueue(jsonResponse("""{"path":["/ccapi/ver110/contents/card1/100CANON/IMG_0001.CR3"]}"""))

        val items = client.listMedia()

        assertEquals(listOf("IMG_0002.JPG", "IMG_0001.CR3"), items.map { it.name })
        assertEquals(listOf("image", "raw"), items.map { it.kind })
        assertEquals("/ccapi/ver110/contents?kind=number", server.takeRequest().path)
        assertEquals("/ccapi/ver110/contents?page=1&order=desc", server.takeRequest().path)
    }

    @Test
    fun realMediaListReportsCameraErrorsInsteadOfReturningAnEmptyLibrary() = runTest {
        client.forceRealCamera(prefix = "/ccapi/ver110")
        server.enqueue(MockResponse().setResponseCode(404).setBody("unsupported query"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("unsupported query"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("storage busy"))

        val failure = runCatching { client.listMedia() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("Reading camera media page failed"))
        assertTrue(failure?.message.orEmpty().contains("HTTP 503"))
    }

    @Test
    fun simulatorMediaCanBeListedAndDownloaded() = runTest {
        server.enqueue(
            jsonResponse(
                """{"items":[{"id":"SIM_0001.PNG","name":"SIM_0001.PNG","kind":"image","size_bytes":6}]}""",
            ),
        )
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        server.enqueue(binaryResponse(bytes, "image/png"))
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<CameraMediaTransferProgress>()

        val item = client.listMedia().single()
        val result = client.downloadMedia(item, output, progress::add)

        assertEquals("/ccapi/media", server.takeRequest().path)
        assertEquals("/ccapi/media/SIM_0001.PNG", server.takeRequest().path)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(bytes.size.toLong(), result.bytesTransferred)
        assertEquals("image/png", result.contentType)
        assertEquals(0L, progress.first().bytesTransferred)
        assertEquals(bytes.size.toLong(), progress.last().bytesTransferred)
        assertEquals(bytes.size.toLong(), progress.last().totalBytes)
    }

    @Test
    fun mediaDeletionUsesEncodedSimulatorIdAndAdvertisedRealCameraPath() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        client.deleteMedia(CameraMediaItem("SIM FILE.PNG", "SIM FILE.PNG", "image"))

        val simulatorRequest = server.takeRequest()
        assertEquals("DELETE", simulatorRequest.method)
        assertEquals("/ccapi/media/SIM%20FILE.PNG", simulatorRequest.path)

        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse("""{"ver110":[{"path":"/contents","get":true,"delete":true}]}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        client.initialize()
        server.takeRequest()
        val path = "/ccapi/ver110/contents/card1/100CANON/IMG_0001.JPG"

        client.deleteMedia(CameraMediaItem(path, "IMG_0001.JPG", "image"))

        val cameraRequest = server.takeRequest()
        assertEquals("DELETE", cameraRequest.method)
        assertEquals(path, cameraRequest.path)
    }

    @Test
    fun realMediaDeletionDoesNotRunWithoutAdvertisedDeleteMethod() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse("""{"ver110":[{"path":"/contents","get":true}]}"""))
        client.initialize()
        server.takeRequest()
        val item = CameraMediaItem(
            "/ccapi/ver110/contents/card1/100CANON/IMG_0001.JPG",
            "IMG_0001.JPG",
            "image",
        )

        val failure = runCatching { client.deleteMedia(item) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("did not advertise media deletion"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun mediaDownloadStreamsInBoundedChunksAndReportsProgress() = runTest {
        val bytes = ByteArray(2 * 1024 * 1024 + 123) { (it % 251).toByte() }
        server.enqueue(binaryResponse(bytes, "video/mp4"))
        val output = CountingOutputStream()
        val progress = mutableListOf<CameraMediaTransferProgress>()
        val item = CameraMediaItem("BIG.MP4", "BIG.MP4", "video")

        val result = client.downloadMedia(item, output, progress::add)

        assertEquals(bytes.size.toLong(), result.bytesTransferred)
        assertEquals(bytes.size.toLong(), output.bytesWritten)
        assertTrue(output.writeCalls > 1)
        assertTrue(output.largestWrite <= 64 * 1024)
        assertEquals(0L, progress.first().bytesTransferred)
        assertEquals(bytes.size.toLong(), progress.last().bytesTransferred)
        assertEquals(bytes.size.toLong(), progress.last().totalBytes)
    }

    @Test
    fun realMediaDownloadRetriesHttpVariantsBeforeWriting() = runTest {
        client.forceRealCamera(prefix = "/ccapi/ver110")
        val item = CameraMediaItem(
            id = "/ccapi/ver110/contents/card1/100CANON/IMG_0001.CR3",
            name = "IMG_0001.CR3",
            kind = "raw",
        )
        val bytes = byteArrayOf(9, 8, 7, 6)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"kind":"metadata"}"""),
        )
        server.enqueue(binaryResponse(bytes, "image/x-canon-cr3"))
        val output = ByteArrayOutputStream()

        client.downloadMedia(item, output)

        assertEquals(item.id, server.takeRequest().path)
        assertEquals("${item.id}?kind=main", server.takeRequest().path)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun mediaDownloadDoesNotRetryAfterDestinationWriteFails() = runTest {
        client.forceRealCamera(prefix = "/ccapi/ver110")
        val item = CameraMediaItem(
            id = "/ccapi/ver110/contents/card1/100CANON/IMG_0001.JPG",
            name = "IMG_0001.JPG",
            kind = "image",
        )
        server.enqueue(binaryResponse(ByteArray(128 * 1024) { 1 }, "image/jpeg"))
        val destination = object : OutputStream() {
            override fun write(value: Int) = throw IOException("destination full")
        }

        val failure = runCatching { client.downloadMedia(item, destination) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun mediaDownloadPropagatesCancellationWithoutTryingAnotherVariant() = runTest {
        client.forceRealCamera(prefix = "/ccapi/ver110")
        val item = CameraMediaItem(
            id = "/ccapi/ver110/contents/card1/100CANON/CLIP_0001.MP4",
            name = "CLIP_0001.MP4",
            kind = "video",
        )
        server.enqueue(binaryResponse(ByteArray(1024 * 1024) { 2 }, "video/mp4"))

        val failure = runCatching {
            client.downloadMedia(item, CountingOutputStream()) { progress ->
                if (progress.bytesTransferred > 0L) throw CancellationException("user cancelled")
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancellingMediaDownloadInterruptsAnActiveHttpRead() = runBlocking {
        client.forceRealCamera(prefix = "/ccapi/ver110")
        val item = CameraMediaItem(
            id = "/ccapi/ver110/contents/card1/100CANON/CLIP_0002.MP4",
            name = "CLIP_0002.MP4",
            kind = "video",
        )
        server.enqueue(
            binaryResponse(ByteArray(1024 * 1024) { 3 }, "video/mp4")
                .throttleBody(1024, 5, TimeUnit.SECONDS),
        )
        val firstWrite = CountDownLatch(1)
        val destination = object : OutputStream() {
            override fun write(value: Int) {
                firstWrite.countDown()
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                firstWrite.countDown()
            }
        }
        val job = launch(Dispatchers.Default) { client.downloadMedia(item, destination) }

        requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertTrue(firstWrite.await(2, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(2_000) { job.join() }

        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
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

    private fun enqueueRealStatus() {
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
    }

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

    private class CountingOutputStream : OutputStream() {
        var bytesWritten = 0L
            private set
        var writeCalls = 0
            private set
        var largestWrite = 0
            private set

        override fun write(value: Int) {
            bytesWritten += 1
            writeCalls += 1
            largestWrite = maxOf(largestWrite, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytesWritten += length
            writeCalls += 1
            largestWrite = maxOf(largestWrite, length)
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

        const val DISCOVERY_JSON = """
            {
              "ver110": [
                {"path":"/shooting/liveview","post":true,"delete":true},
                {"path":"/shooting/liveview/flip","get":true},
                {"path":"/shooting/control/recbutton","post":true},
                {"path":"/shooting/control/shutterbutton","post":true},
                {"path":"/shooting/control/shutterbutton/manual","put":true},
                {"path":"/shooting/control/drivefocus","post":true},
                {"path":"/contents","get":true,"delete":true},
                {"path":"/shooting/settings","get":true},
                {"path":"/shooting/settings/iso","put":true},
                {"path":"/shooting/settings/tv","put":true},
                {"path":"/shooting/settings/av","put":true},
                {"path":"/shooting/settings/wb","put":true},
                {"path":"/shooting/settings/meteringmode","put":true},
                {"path":"/shooting/settings/afmethod","put":true},
                {"path":"/shooting/settings/shootingmode","put":true}
              ]
            }
        """
    }
}
