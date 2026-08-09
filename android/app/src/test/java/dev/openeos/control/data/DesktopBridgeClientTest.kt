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
import java.io.ByteArrayInputStream
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
            cameraEngine = "edsdk",
        )

        val cameras = client.discoverCameras()
        client.initialize()
        val info = client.info()
        val initialStatus = client.status()
        val capabilities = client.capabilities()
        val exposureStatus = client.setExposure(iso = "800")
        val whiteBalanceStatus = client.setWhiteBalance("Daylight")
        client.syncCameraClock()
        client.cleanSensor(autoPowerOff = false)
        client.sleepCamera()
        val createdDirectory = client.createDirectory("ABCDE")
        val updatedFileNaming = client.setFileNaming(
            CameraFileNamingField.STILL_USER_SETTING_1,
            "EOS_",
        )
        client.captureStill()
        val bulbStarted = client.startBulbExposure()
        val bulbStopped = client.stopBulbExposure()
        client.autofocus()
        client.halfPressShutter()
        val recording = client.startRecording()
        val stopped = client.stopRecording()
        val clickWhiteBalanceStatus = client.clickWhiteBalance(0.4, 0.6)
        client.startLiveView(LiveViewRequest(fps = 5, size = LiveViewSize.MEDIUM))
        val frame = client.liveViewFrame(9)
        val magnification = client.setLiveViewMagnification(LiveViewMagnification.X5)
        val focus = client.driveFocus(FocusDriveDirection.FAR, FocusDriveStep.LARGE)
        val media = client.listMedia()
        val thumbnail = client.mediaThumbnail(media.single())
        val preview = client.mediaPreview(media.single())
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
        assertTrue(media.single().previewAvailable)
        assertEquals("gphoto2 2.5.33", info.engineVersion)
        assertEquals(82, initialStatus.batteryLevel)
        assertEquals(2048L, initialStatus.storageTotalBytes)
        assertEquals(1024L, initialStatus.storageFreeBytes)
        assertEquals(123L, initialStatus.storageFreeImages)
        assertEquals(2, initialStatus.storageDeviceCount)
        assertEquals(120L, initialStatus.recordableShots)
        assertEquals(3_600L, initialStatus.remainingRecordingSeconds)
        assertEquals("800", exposureStatus.exposure.iso)
        assertEquals("Daylight", whiteBalanceStatus.exposure.whiteBalance)
        assertEquals("ABCDE", createdDirectory)
        assertTrue(bulbStarted.bulbExposureActive == true)
        assertFalse(bulbStopped.bulbExposureActive == true)
        assertTrue(recording.recording == true)
        assertFalse(stopped.recording == true)
        assertEquals("click", clickWhiteBalanceStatus.exposure.whiteBalance)
        assertTrue(capabilities.matrix.supports(CameraFeature.DESKTOP_BRIDGE))
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_JPEG_POLLING))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_DELETE))
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_MAGNIFICATION))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_THUMBNAIL))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_PREVIEW))
        assertTrue(capabilities.matrix.supports(CameraFeature.CLICK_WHITE_BALANCE))
        assertTrue(capabilities.matrix.supports(CameraFeature.SENSOR_CLEANING))
        assertTrue(capabilities.matrix.supports(CameraFeature.CAMERA_SLEEP))
        assertTrue(capabilities.matrix.supports(CameraFeature.DIRECTORY_CONTROL))
        assertTrue(capabilities.matrix.supports(CameraFeature.FILE_NAMING_CONTROL))
        assertEquals("IMG_", capabilities.fileNaming?.stillUserSetting1)
        assertEquals("EOS_", updatedFileNaming.stillUserSetting1)
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.LIVE_VIEW_RTP))
        assertFalse(capabilities.matrix.supports(CameraFeature.USB_DIAGNOSTICS))
        assertEquals(listOf("Auto", "100", "400", "800"), capabilities.iso)
        assertEquals(
            setOf("directoryselection", "drivemode", "ownername"),
            capabilities.advancedSettings.map { it.key }.toSet(),
        )
        val owner = capabilities.advancedSettings.single { it.key == "ownername" }
        assertEquals(CameraSettingInputKind.TEXT, owner.inputKind)
        assertEquals("TEST OWNER", owner.value)
        assertEquals(emptyList<String>(), owner.values)
        assertEquals(255, owner.maxLength)
        assertEquals(5, capabilities.liveView.maxFps)
        assertEquals(
            listOf(LiveViewMagnification.X1, LiveViewMagnification.X5),
            capabilities.liveView.magnifications,
        )
        assertEquals(LiveViewMagnification.X1, capabilities.liveView.currentMagnification)
        assertEquals("gphoto2 --abilities + --list-all-config", capabilities.evidence.source)
        assertEquals(listOf("gphoto2 2.5.33"), capabilities.evidence.protocolVersions)
        assertTrue("CAPTURE_PREVIEW" in capabilities.evidence.advertisedCommands)
        assertTrue("/main/imgsettings/iso" in capabilities.evidence.writableSettings)
        assertTrue(CameraFeature.BATTERY_STATUS in capabilities.evidence.observedFeatures)
        assertEquals(1, capabilities.evidence.discoveryTrace.size)
        assertEquals("GET /ccapi", capabilities.evidence.discoveryTrace.single().endpoint)
        assertEquals("NO_API_LIST", capabilities.evidence.discoveryTrace.single().outcome)
        assertEquals(listOf("value"), capabilities.evidence.discoveryTrace.single().responseKeys)
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_UPLOAD))
        assertTrue(
            observedFeatures.containsAll(
                setOf(
                    CameraFeature.DESKTOP_BRIDGE,
                    CameraFeature.CAMERA_IDENTITY,
                    CameraFeature.EXPOSURE_CONTROL,
                    CameraFeature.WHITE_BALANCE_CONTROL,
                    CameraFeature.CAMERA_CLOCK_SYNC,
                    CameraFeature.SENSOR_CLEANING,
                    CameraFeature.CAMERA_SLEEP,
                    CameraFeature.FILE_NAMING_CONTROL,
                    CameraFeature.STILL_CAPTURE,
                    CameraFeature.BULB_EXPOSURE,
                    CameraFeature.AUTOFOCUS,
                    CameraFeature.SHUTTER_HALF_PRESS,
                    CameraFeature.VIDEO_RECORDING,
                    CameraFeature.CLICK_WHITE_BALANCE,
                    CameraFeature.FOCUS_DRIVE,
                    CameraFeature.LIVE_VIEW_MAGNIFICATION,
                    CameraFeature.LIVE_VIEW,
                    CameraFeature.LIVE_VIEW_JPEG_POLLING,
                    CameraFeature.MEDIA_BROWSER,
                    CameraFeature.MEDIA_THUMBNAIL,
                    CameraFeature.MEDIA_PREVIEW,
                    CameraFeature.MEDIA_DOWNLOAD,
                    CameraFeature.MEDIA_DELETE,
                ),
            ),
        )
        assertArrayEquals(JPEG, frame.bytes)
        assertTrue(frame.sourceUrl.endsWith("/liveview/frame?t=9"))
        assertTrue(focus.ok)
        assertEquals(LiveViewMagnification.X5, magnification.magnification)
        assertEquals("IMG_0001.JPG", media.single().name)
        assertArrayEquals(THUMBNAIL, thumbnail.bytes)
        assertEquals("image/jpeg", thumbnail.contentType)
        assertArrayEquals(PREVIEW, preview.bytes)
        assertEquals("image/jpeg", preview.contentType)
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
        assertEquals("edsdk", sessionPayload.getString("engine"))
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/settings/iso") == true })
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/clock/sync") == true })
        val sensorCleaningRequest = requests.first {
            it.requestUrl?.encodedPath?.endsWith("/maintenance/sensor-cleaning") == true
        }
        assertEquals("POST", sensorCleaningRequest.method)
        assertEquals(false, dispatcher.sensorCleaningAutoPowerOff)
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/power/sleep") == true })
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/directories") == true })
        assertEquals("ABCDE", dispatcher.createdDirectoryName)
        val fileNamingRequest = requests.single {
            it.requestUrl?.encodedPath?.endsWith("/file-naming/still-user-setting-1") == true
        }
        assertEquals("PUT", fileNamingRequest.method)
        assertEquals("EOS_", JSONObject(fileNamingRequest.body.readUtf8()).getString("value"))
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/capture/still") == true })
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/bulb/start") == true })
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/bulb/stop") == true })
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
    fun mediaUploadStreamsExactBytesAndReportsProgress() = runTest {
        val bytes = ByteArray(192 * 1024 + 17) { (it % 251).toByte() }
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON))
        server.enqueue(
            jsonResponse(
                """{"id":"gphoto2:upload","name":"PHONE 0001.JPG","kind":"image","sizeBytes":${bytes.size},"contentType":"image/jpeg"}""",
                code = 201,
            )
        )
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()
        val progress = mutableListOf<CameraMediaTransferProgress>()

        val result = client.uploadMedia(
            name = "PHONE 0001.JPG",
            sizeBytes = bytes.size.toLong(),
            contentType = "image/jpeg",
            source = ByteArrayInputStream(bytes),
            onProgress = progress::add,
        )
        val request = server.takeRequest()
        server.takeRequest()
        val upload = server.takeRequest()

        assertEquals("GET", request.method)
        assertEquals("POST", upload.method)
        assertEquals("/v1/session/session-1/media?filename=PHONE%200001.JPG", upload.path)
        assertEquals("image/jpeg", upload.getHeader("content-type"))
        assertEquals(bytes.size.toString(), upload.getHeader("content-length"))
        assertArrayEquals(bytes, upload.body.readByteArray())
        assertEquals(bytes.size.toLong(), result.bytesTransferred)
        assertEquals(0L, progress.first().bytesTransferred)
        assertEquals(bytes.size.toLong(), progress.last().bytesTransferred)
        assertTrue(CameraFeature.MEDIA_UPLOAD in client.observedFeatureSnapshot())
    }

    @Test
    fun mediaArchiveUsesBooleanBridgeRouteAndParsesAuthoritativeResponse() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON))
        server.enqueue(
            jsonResponse(
                """{"id":"ccapi:item","name":"IMG_0001.JPG","kind":"image","archived":true}""",
            ),
        )
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()
        server.takeRequest()
        server.takeRequest()
        val item = CameraMediaItem("ccapi:item", "IMG_0001.JPG", "image", archived = false)

        val updated = client.setMediaArchived(item, enabled = true)
        val request = server.takeRequest()

        assertEquals("PUT", request.method)
        assertEquals("/v1/session/session-1/media/ccapi:item/archive", request.path)
        assertTrue(JSONObject(request.body.readUtf8()).getBoolean("enabled"))
        assertEquals(true, updated.archived)
        assertTrue(CameraFeature.MEDIA_ARCHIVE in client.observedFeatureSnapshot())
    }

    @Test
    fun mediaUploadRejectsOversizedBridgeResponseBeforeJsonParsing() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON))
        server.enqueue(MockResponse().setResponseCode(201).setBody("x".repeat(32 * 1024 + 1)))
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()

        val failure = runCatching {
            client.uploadMedia(
                name = "PHONE_0002.JPG",
                sizeBytes = 1,
                contentType = "image/jpeg",
                source = ByteArrayInputStream(byteArrayOf(1)),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("response exceeded 32768 bytes"))
        assertFalse(CameraFeature.MEDIA_UPLOAD in client.observedFeatureSnapshot())
    }

    @Test
    fun bridgeEventPollingUsesAdvertisedLifecycle() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        server.enqueue(
            jsonResponse(
                CAPABILITIES_JSON.replace(
                    "\"supported\": [",
                    "\"supported\": [\"EVENT_POLLING\",",
                ),
            ),
        )
        server.enqueue(jsonResponse("""{"changedKeys":["shootingsettings","contents"]}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        val client = DesktopBridgeClient(server.url("/").toString())

        client.initialize()
        val capabilities = client.capabilities()
        val event = client.pollEvent()
        client.stopEventPolling()

        assertTrue(capabilities.matrix.supports(CameraFeature.EVENT_POLLING))
        assertEquals(setOf("shootingsettings", "contents"), event.changedKeys)
        assertEquals("/health", server.takeRequest().path)
        assertEquals("/v1/session", server.takeRequest().path)
        assertEquals("/v1/session/session-1/capabilities", server.takeRequest().path)
        assertEquals("/v1/session/session-1/events", server.takeRequest().path)
        val stop = server.takeRequest()
        assertEquals("DELETE", stop.method)
        assertEquals("/v1/session/session-1/events", stop.path)
    }

    @Test
    fun bridgeEventPollingRejectsOversizedResponseBeforeParsing() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        server.enqueue(
            jsonResponse(
                CAPABILITIES_JSON.replace(
                    "\"supported\": [",
                    "\"supported\": [\"EVENT_POLLING\",",
                ),
            ),
        )
        server.enqueue(jsonResponse("""{"changedKeys":["${"x".repeat(270_000)}"]}"""))
        val client = DesktopBridgeClient(server.url("/").toString())

        client.initialize()
        client.capabilities()
        val failure = runCatching { client.pollEvent() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("event response was too large"))
    }

    @Test
    fun bridgeCapabilitiesInferMissingOrInvalidProfileWithoutInventingR6Identity() = runTest {
        val cases = listOf(
            BridgeProfileCase(
                sessionModel = "Canon EOS-R6 Mark III",
                profile = null,
                expectedModel = "Canon EOS-R6 Mark III",
                expectedFamily = CameraModelFamily.EOS_R,
                expectedPriority = CameraModelPriority.PRIMARY,
            ),
            BridgeProfileCase(
                sessionModel = "Canon Camera",
                profile = JSONObject()
                    .put("modelName", "Canon EOS M50")
                    .put("family", "Canon EOS")
                    .put("priority", "compatible"),
                expectedModel = "Canon EOS M50",
                expectedFamily = CameraModelFamily.EOS_M,
                expectedPriority = CameraModelPriority.SUPPORTED,
            ),
            BridgeProfileCase(
                sessionModel = "Third-party Camera",
                profile = null,
                expectedModel = "Third-party Camera",
                expectedFamily = CameraModelFamily.UNKNOWN,
                expectedPriority = CameraModelPriority.RESEARCH,
            ),
            BridgeProfileCase(
                sessionModel = "Canon EOS R5",
                profile = JSONObject()
                    .put("modelName", JSONObject.NULL)
                    .put("family", JSONObject.NULL)
                    .put("priority", JSONObject.NULL),
                expectedModel = "Canon EOS R5",
                expectedFamily = CameraModelFamily.EOS_R,
                expectedPriority = CameraModelPriority.SUPPORTED,
            ),
        )

        cases.forEach { case ->
            val session = JSONObject(SESSION_JSON).apply {
                getJSONObject("camera").put("model", case.sessionModel)
            }
            val capabilities = JSONObject(CAPABILITIES_JSON).apply {
                if (case.profile == null) remove("profile") else put("profile", case.profile)
            }
            server.enqueue(jsonResponse(HEALTH_JSON))
            server.enqueue(jsonResponse(session.toString(), code = 201))
            server.enqueue(jsonResponse(capabilities.toString()))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = DesktopBridgeClient(server.url("/").toString())

            client.initialize()
            val parsed = client.capabilities().profile
            client.close()

            assertEquals(case.expectedModel, parsed.modelName)
            assertEquals(case.expectedFamily, parsed.family)
            assertEquals(case.expectedPriority, parsed.priority)
        }
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
    fun bridgeCapabilitiesDiscardMalformedTextSettingContracts() = runTest {
        val capabilitiesJson = JSONObject(CAPABILITIES_JSON)
        capabilitiesJson.getJSONArray("settings").apply {
            put(JSONObject().put("key", "bad-kind").put("value", "TEST").put("values", emptyList<String>())
                .put("inputKind", "textarea").put("maxLength", 255))
            put(JSONObject().put("key", "bad-choices").put("value", "TEST").put("values", listOf("TEST"))
                .put("inputKind", "text").put("maxLength", 255))
            put(JSONObject().put("key", "bad-length").put("value", "TEST").put("values", emptyList<String>())
                .put("inputKind", "text").put("maxLength", 256))
            put(JSONObject().put("key", "bad-ascii").put("value", "測試").put("values", emptyList<String>())
                .put("inputKind", "text").put("maxLength", 255))
        }
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        server.enqueue(jsonResponse(capabilitiesJson.toString()))
        server.enqueue(MockResponse().setResponseCode(204))
        val client = DesktopBridgeClient(server.url("/").toString())

        client.initialize()
        val settings = client.capabilities().advancedSettings
        client.close()

        assertTrue(settings.any { it.key == "ownername" })
        assertTrue(settings.none { it.key.startsWith("bad-") })
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

    private data class BridgeProfileCase(
        val sessionModel: String,
        val profile: JSONObject?,
        val expectedModel: String,
        val expectedFamily: CameraModelFamily,
        val expectedPriority: CameraModelPriority,
    )

    private class BridgeDispatcher : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private var iso = "400"
        private var whiteBalance = "Auto"
        private var recording = false
        private var bulbExposureActive = false
        private var stillUserSetting1 = "IMG_"
        var sensorCleaningAutoPowerOff: Boolean? = null
            private set
        var createdDirectoryName: String? = null
            private set

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
                path.endsWith("/clock/sync") -> json(statusJson())
                path.endsWith("/maintenance/sensor-cleaning") -> {
                    sensorCleaningAutoPowerOff = JSONObject(request.body.readUtf8()).getBoolean("autoPowerOff")
                    MockResponse().setResponseCode(204)
                }
                path.endsWith("/power/sleep") -> MockResponse().setResponseCode(204)
                path.endsWith("/directories") -> {
                    val name = JSONObject(request.body.readUtf8()).getString("name")
                    createdDirectoryName = name
                    json("""{"name":"$name"}""")
                }
                path.contains("/file-naming/") && request.method == "PUT" -> {
                    val field = path.substringAfterLast('/')
                    val value = JSONObject(request.body.clone().readUtf8()).getString("value")
                    if (field != CameraFileNamingField.STILL_USER_SETTING_1.wireName) {
                        return json("""{"detail":"Unexpected file-naming field"}""", 400)
                    }
                    stillUserSetting1 = value
                    json(fileNamingJson())
                }
                path.endsWith("/capture/still") -> json(statusJson())
                path.endsWith("/bulb/start") -> {
                    bulbExposureActive = true
                    json(statusJson())
                }
                path.endsWith("/bulb/stop") -> {
                    bulbExposureActive = false
                    json(statusJson())
                }
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
                path.endsWith("/liveview/magnification") -> {
                    val value = JSONObject(request.body.readUtf8()).getInt("value")
                    json("""{"accepted":true,"value":$value}""")
                }
                path.endsWith("/focus/drive") -> json("""{"accepted":true,"direction":"FAR","step":"LARGE"}""")
                path.endsWith("/media") -> json(MEDIA_JSON)
                path.endsWith("/thumbnail") -> MockResponse()
                    .setHeader("content-type", "image/jpeg")
                    .setBody(okio.Buffer().write(THUMBNAIL))
                path.endsWith("/preview") -> MockResponse()
                    .setHeader("content-type", "image/jpeg")
                    .setBody(okio.Buffer().write(PREVIEW))
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
              "bulbExposureActive": $bulbExposureActive,
              "mode": "Manual",
              "recordableShots": 120,
              "remainingRecordingSeconds": 3600,
              "media": {"available": true, "totalBytes": 2048, "freeBytes": 1024, "freeImages": 123, "devices": 2},
              "exposure": {
                "iso": "$iso",
                "shutter": "1/50",
                "aperture": "2.8",
                "whiteBalance": "$whiteBalance"
              },
              "raw": {"engine": "libgphoto2", "port": "usb:001,007", "recordable": {"recordableshots": 120, "remainingtime": 3600}}
            }
            """.trimIndent()

        private fun fileNamingJson(): String =
            """
            {
              "stillFilenameMode": "preset_code",
              "stillFilenameModeOptions": ["preset_code", "usersetting1", "usersetting2"],
              "stillUserSetting1": "$stillUserSetting1",
              "stillUserSetting2": "EOS",
              "movieIndex": "A_",
              "movieReelNumber": 1,
              "movieReelRange": {"minimum": 1, "maximum": 9999, "step": 1},
              "movieClipNumber": 1,
              "movieClipRange": {"minimum": 1, "maximum": 999, "step": 1},
              "movieUserDefined": "EOS01"
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
        val PREVIEW = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 8, 6, 0xFF.toByte(), 0xD9.toByte())
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
                "CAMERA_CLOCK_SYNC", "DIRECTORY_CONTROL", "SENSOR_CLEANING", "CAMERA_SLEEP", "STILL_CAPTURE", "BULB_EXPOSURE", "AUTOFOCUS", "SHUTTER_HALF_PRESS", "VIDEO_RECORDING", "FOCUS_DRIVE",
                "LIVE_VIEW_MAGNIFICATION", "FILE_NAMING_CONTROL",
                "EXPOSURE_CONTROL", "WHITE_BALANCE_CONTROL", "CLICK_WHITE_BALANCE", "ADVANCED_SETTINGS",
                "MEDIA_BROWSER", "MEDIA_THUMBNAIL", "MEDIA_PREVIEW", "MEDIA_DOWNLOAD", "MEDIA_UPLOAD", "MEDIA_DELETE", "A_FUTURE_FEATURE"
              ],
              "planned": ["TAP_FOCUS", "LIVE_VIEW_RTP"],
              "reasons": {"LIVE_VIEW_RTP": "Persistent stream is not implemented."},
              "liveView": {
                "sources": ["DESKTOP_BRIDGE_STREAM"],
                "defaultSource": "DESKTOP_BRIDGE_STREAM",
                "sizes": ["MEDIUM"],
                "defaultSize": "MEDIUM",
                "minFps": 1,
                "maxFps": 5,
                "magnifications": [1, 5],
                "currentMagnification": 1
              },
              "settings": [
                {"key":"iso","label":"ISO","value":"400","values":["Auto","100","400","800"]},
                {"key":"whitebalance","label":"White balance","value":"Auto","values":["Auto","Daylight"]},
                {"key":"directoryselection","label":"Capture directory","value":"100EOSXX","values":["100EOSXX","101EOSXX"]},
                {"key":"drivemode","label":"Drive mode","value":"Single","values":["Single","Continuous"]},
                {"key":"ownername","label":"Owner name","value":"TEST OWNER","values":[],"inputKind":"text","maxLength":255}
              ],
              "fileNaming": {
                "stillFilenameMode": "preset_code",
                "stillFilenameModeOptions": ["preset_code", "usersetting1", "usersetting2"],
                "stillUserSetting1": "IMG_",
                "stillUserSetting2": "EOS",
                "movieIndex": "A_",
                "movieReelNumber": 1,
                "movieReelRange": {"minimum": 1, "maximum": 9999, "step": 1},
                "movieClipNumber": 1,
                "movieClipRange": {"minimum": 1, "maximum": 999, "step": 1},
                "movieUserDefined": "EOS01"
              },
              "evidence": {
                "source": "gphoto2 --abilities + --list-all-config",
                "protocolVersions": ["gphoto2 2.5.33"],
                "advertisedCommands": ["CAPTURE_IMAGE", "CAPTURE_PREVIEW"],
                "writableSettings": ["/main/imgsettings/iso"],
                "observedFeatures": ["BATTERY_STATUS"],
                "discoveryTrace": [
                  {
                    "endpoint": "GET /ccapi",
                    "outcome": "NO_API_LIST",
                    "httpStatus": 200,
                    "responseKeys": ["value"],
                    "protocolVersions": [],
                    "advertisedOperationCount": 0,
                    "truncated": false
                  }
                ],
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
                  "contentType": "image/jpeg",
                  "previewAvailable": true
                }
              ]
            }
        """
    }
}
