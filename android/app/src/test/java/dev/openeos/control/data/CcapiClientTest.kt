package dev.openeos.control.data

import android.view.Surface
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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
        assertEquals(128_000_000_000L, status.storageTotalBytes)
        assertEquals(84_000_000_000L, status.storageFreeBytes)
        assertEquals(2_418L, status.storageFreeImages)
        assertEquals(2, status.storageDeviceCount)
        assertEquals(2_418L, status.recordableShots)
        assertEquals(7_200L, status.remainingRecordingSeconds)
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
        assertTrue(capabilities.matrix.supports(CameraFeature.CAMERA_CLOCK_SYNC))
        assertTrue(capabilities.matrix.supports(CameraFeature.FOCUS_DRIVE))
        assertTrue(capabilities.matrix.supports(CameraFeature.RECORDABLE_STATUS))
        assertEquals(listOf(LiveViewSource.SIMULATOR_FRAME), capabilities.liveView.sources)
        assertEquals(2, capabilities.liveView.maxFps)
        assertEquals(LiveViewMagnification.entries, capabilities.liveView.magnifications)
        assertEquals(LiveViewMagnification.X1, capabilities.liveView.currentMagnification)
    }

    @Test
    fun simulatorSourceAudioControlsUseBackedEndpoints() = runTest {
        server.enqueue(
            jsonResponse(
                """{
                    "iso":["800"],"shutter":["1/50"],"aperture":["2.8"],"white_balance":["auto"],
                    "soundrecordingmodeintmic":{"value":"manual","ability":["auto","manual"]},
                    "soundrecordinglevelintmic":{"value":32,"ability":{"min":0,"max":63,"step":1}},
                    "windfilterintmic":{"value":"enable","ability":["enable","disable"]}
                }""".trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse(STATUS_JSON))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse(STATUS_JSON))

        val capabilities = client.capabilities()
        client.setSetting("soundrecordingmodeintmic", "auto")
        client.setSetting("soundrecordinglevelintmic", "41")

        assertEquals(listOf("auto", "manual"), capabilities.advancedSettings.single {
            it.key == "soundrecordingmodeintmic"
        }.values)
        assertEquals((0..63).map(Int::toString), capabilities.advancedSettings.single {
            it.key == "soundrecordinglevelintmic"
        }.values)
        assertEquals(listOf("enable", "disable"), capabilities.advancedSettings.single {
            it.key == "windfilterintmic"
        }.values)
        assertEquals("/ccapi/capabilities", server.takeRequest().path)
        assertEquals("/ccapi/sound-recording-mode/internal-mic", server.takeRequest().path)
        assertEquals("/ccapi/status", server.takeRequest().path)
        val levelWrite = server.takeRequest()
        assertEquals("/ccapi/sound-recording-level/internal-mic", levelWrite.path)
        assertEquals(41, JSONObject(levelWrite.body.readUtf8()).getInt("value"))
    }

    @Test
    fun simulatorLiveViewMagnificationUsesBackedIntegerEndpoint() = runTest {
        server.enqueue(jsonResponse(CAPABILITIES_JSON))
        server.enqueue(jsonResponse("""{"accepted":true,"value":10}"""))

        val capabilities = client.capabilities()
        val result = client.setLiveViewMagnification(LiveViewMagnification.X10)

        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_MAGNIFICATION))
        assertEquals(LiveViewMagnification.X10, result.magnification)
        assertEquals("/ccapi/capabilities", server.takeRequest().path)
        val write = server.takeRequest()
        assertEquals("POST", write.method)
        assertEquals("/ccapi/liveview/magnification", write.path)
        assertEquals(10, JSONObject(write.body.readUtf8()).getInt("value"))
    }

    @Test
    fun simulatorCardSelectionCapabilityWritesRealSimulatorEndpoint() = runTest {
        server.enqueue(
            jsonResponse(
                """{
                    "iso":["100","800","1600"],
                    "shutter":["1/50","1/100"],
                    "aperture":["2.8","4.0"],
                    "white_balance":["auto","daylight"],
                    "cardselectionstillimage":{"value":"card1","ability":["none","card1","card2"]},
                    "cardselectionmovie":{"value":"card2","ability":["card1","card2"]}
                }""".trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse(STATUS_JSON))

        val capabilities = client.capabilities()
        assertEquals("card1", capabilities.advancedSettings.single {
            it.key == "cardselectionstillimage"
        }.value)
        assertTrue(capabilities.matrix.supports(CameraFeature.CARD_SELECTION_CONTROL))

        client.setSetting("cardselectionmovie", "card1")

        server.takeRequest()
        val write = server.takeRequest()
        assertEquals("PUT", write.method)
        assertEquals("/ccapi/card-selection/movie", write.path)
        assertEquals("card1", JSONObject(write.body.readUtf8()).getString("value"))
        assertEquals("/ccapi/status", server.takeRequest().path)
    }

    @Test
    fun simulatorFileNamingCapabilityWritesBackedEndpoint() = runTest {
        server.enqueue(jsonResponse(SIMULATOR_FILE_NAMING_CAPABILITIES_JSON))
        server.enqueue(jsonResponse(SIMULATOR_FILE_NAMING_CAPABILITIES_JSON))
        server.enqueue(
            jsonResponse(
                JSONObject(SIMULATOR_FILE_NAMING_CAPABILITIES_JSON)
                    .getJSONObject("fileNaming")
                    .put("stillUserSetting1", "EOS_")
                    .toString(),
            ),
        )

        val capabilities = client.capabilities()
        assertTrue(capabilities.matrix.supports(CameraFeature.FILE_NAMING_CONTROL))
        assertEquals("IMG_", capabilities.fileNaming?.stillUserSetting1)

        val updated = client.setFileNaming(CameraFileNamingField.STILL_USER_SETTING_1, "EOS_")

        assertEquals("EOS_", updated.stillUserSetting1)
        server.takeRequest()
        server.takeRequest()
        val write = server.takeRequest()
        assertEquals("PUT", write.method)
        assertEquals("/ccapi/file-naming/still-user-setting-1", write.path)
        assertEquals("EOS_", JSONObject(write.body.readUtf8()).getString("value"))
    }

    @Test
    fun simulatorDeviceFunctionSettingsUseBackedEndpoints() = runTest {
        server.enqueue(
            jsonResponse(
                """{
                    "iso":["100","800"],
                    "shutter":["1/50","1/100"],
                    "aperture":["2.8","4.0"],
                    "white_balance":["auto","daylight"],
                    "beep":{"value":"enable","ability":["enable","disable","disabletouch"]},
                    "displayoff":{"value":"60","ability":["10","20","30","60","120","180"]}
                }""".trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse(STATUS_JSON))

        val capabilities = client.capabilities()
        assertEquals("enable", capabilities.advancedSettings.single { it.key == "beep" }.value)
        assertEquals("60", capabilities.advancedSettings.single { it.key == "displayoff" }.value)

        client.setSetting("beep", "disabletouch")

        server.takeRequest()
        val write = server.takeRequest()
        assertEquals("PUT", write.method)
        assertEquals("/ccapi/device-settings/beep", write.path)
        assertEquals("disabletouch", JSONObject(write.body.readUtf8()).getString("value"))
        assertEquals("/ccapi/status", server.takeRequest().path)
    }

    @Test
    fun simulatorAutoPowerOffSeparatesTimedSettingAndSleepAction() = runTest {
        server.enqueue(
            jsonResponse(
                """{
                    "iso":["100","800"],
                    "shutter":["1/50","1/100"],
                    "aperture":["2.8","4.0"],
                    "white_balance":["auto","daylight"],
                    "autopoweroff":{
                        "value":"180",
                        "ability":["30","60","120","180","300","600","disable","immediately"]
                    }
                }""".trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))

        val capabilities = client.capabilities()
        client.sleepCamera()

        val setting = capabilities.advancedSettings.single { it.key == "autopoweroff" }
        assertEquals(listOf("30", "60", "120", "180", "300", "600", "disable"), setting.values)
        assertFalse("immediately" in setting.values)
        assertTrue(capabilities.matrix.supports(CameraFeature.CAMERA_SLEEP))
        server.takeRequest()
        val sleep = server.takeRequest()
        assertEquals("POST", sleep.method)
        assertEquals("/ccapi/camera-sleep", sleep.path)
        assertEquals(0, JSONObject(sleep.body.readUtf8()).length())
        assertTrue(CameraFeature.CAMERA_SLEEP in client.observedFeatureSnapshot())
    }

    @Test
    fun realAutoPowerOffUsesFreshAbilityAndSeparateImmediateAction() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/functions/autopoweroff","get":true,"put":true}
                ]}""",
            ),
        )
        repeat(2) {
            server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
            server.enqueue(
                jsonResponse(
                    """{"value":"180","ability":["30","60","120","180","300","600","disable","immediately"]}""",
                ),
            )
        }
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))

        client.initialize()
        val capabilities = client.capabilities()
        client.sleepCamera()

        val setting = capabilities.advancedSettings.single { it.key == "autopoweroff" }
        assertEquals(listOf("30", "60", "120", "180", "300", "600", "disable"), setting.values)
        assertTrue(capabilities.matrix.supports(CameraFeature.CAMERA_SLEEP))
        repeat(5) { server.takeRequest() }
        val sleep = server.takeRequest()
        assertEquals("PUT", sleep.method)
        assertEquals("/ccapi/ver100/functions/autopoweroff", sleep.path)
        assertEquals("immediately", JSONObject(sleep.body.readUtf8()).getString("value"))
        assertTrue(CameraFeature.CAMERA_SLEEP in client.observedFeatureSnapshot())
    }

    @Test
    fun realCameraSleepRequiresCanonAcceptedStatus() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/functions/autopoweroff","get":true,"put":true}
                ]}""",
            ),
        )
        repeat(2) {
            server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
            server.enqueue(
                jsonResponse(
                    """{"value":"180","ability":["30","60","120","180","300","600","disable","immediately"]}""",
                ),
            )
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.initialize()
        client.capabilities()
        val failure = runCatching { client.sleepCamera() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("expected HTTP 202") == true)
        assertFalse(CameraFeature.CAMERA_SLEEP in client.observedFeatureSnapshot())
    }

    @Test
    fun realAutoPowerOffWithoutImmediateAbilityKeepsTimedSettingButHidesSleep() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/functions/autopoweroff","get":true,"put":true}
                ]}""",
            ),
        )
        repeat(2) {
            server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
            server.enqueue(
                jsonResponse("""{"value":"180","ability":["30","60","180","disable"]}"""),
            )
        }

        client.initialize()
        val capabilities = client.capabilities()

        assertTrue(capabilities.advancedSettings.any { it.key == "autopoweroff" })
        assertFalse(capabilities.matrix.supports(CameraFeature.CAMERA_SLEEP))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.CAMERA_SLEEP))
        val failure = runCatching { client.sleepCamera() }.exceptionOrNull()
        assertTrue(failure is UnsupportedOperationException)
        assertFalse(CameraFeature.CAMERA_SLEEP in client.observedFeatureSnapshot())
        assertEquals(5, server.requestCount)
    }

    @Test
    fun simulatorSensorCleaningUsesBackedEndpointAndBooleanPayload() = runTest {
        server.enqueue(
            jsonResponse(
                """{"iso":[],"shutter":[],"aperture":[],"white_balance":[]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))

        val capabilities = client.capabilities()
        client.cleanSensor(autoPowerOff = true)

        assertTrue(capabilities.matrix.supports(CameraFeature.SENSOR_CLEANING))
        assertEquals("/ccapi/capabilities", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/ccapi/sensor-cleaning", request.path)
        assertTrue(JSONObject(request.body.readUtf8()).getBoolean("autopoweroff"))
        assertTrue(CameraFeature.SENSOR_CLEANING in client.observedFeatureSnapshot())
    }

    @Test
    fun realSensorCleaningRequiresAdvertisedPostAndSendsCanonPayload() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver110":[{"path":"/functions/sensorcleaning","post":true}]}""",
            ),
        )
        server.enqueue(jsonResponse("{}"))

        client.initialize()
        val capabilities = client.capabilities()
        client.cleanSensor(autoPowerOff = false)

        assertTrue(capabilities.matrix.supports(CameraFeature.SENSOR_CLEANING))
        assertEquals("/ccapi", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/ccapi/ver110/functions/sensorcleaning", request.path)
        assertFalse(JSONObject(request.body.readUtf8()).getBoolean("autopoweroff"))
        assertTrue(CameraFeature.SENSOR_CLEANING in client.observedFeatureSnapshot())
    }

    @Test
    fun realSensorCleaningRejectsNonCanonSuccessStatus() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver100":[{"path":"/functions/sensorcleaning","post":true}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))

        client.initialize()
        val failure = runCatching { client.cleanSensor(autoPowerOff = false) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("expected HTTP 200") == true)
        assertFalse(CameraFeature.SENSOR_CLEANING in client.observedFeatureSnapshot())
    }

    @Test
    fun realSensorCleaningDoesNotSendUnadvertisedCommand() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse("""{"ver100":[{"path":"/functions/datetime","get":true}]}"""))

        client.initialize()
        val capabilities = client.capabilities()
        val failure = runCatching { client.cleanSensor(autoPowerOff = false) }.exceptionOrNull()

        assertFalse(capabilities.matrix.supports(CameraFeature.SENSOR_CLEANING))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.SENSOR_CLEANING))
        assertTrue(failure is UnsupportedOperationException)
        assertEquals(1, server.requestCount)
        assertFalse(CameraFeature.SENSOR_CLEANING in client.observedFeatureSnapshot())
    }

    @Test
    fun realDeviceFunctionSettingsRequirePairsRefreshAndWriteAdvertisedValue() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/functions/beep","get":true,"put":true},
                    {"path":"/functions/displayoff","get":true,"put":true}
                ]}""",
            ),
        )
        repeat(2) {
            server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
            server.enqueue(jsonResponse("""{"value":"enable","ability":["enable","disable","disabletouch"]}"""))
            server.enqueue(jsonResponse("""{"value":"60","ability":["10","20","30","60","120","180"]}"""))
        }
        server.enqueue(jsonResponse("""{"value":"disabletouch"}"""))
        enqueueRealStatus()

        client.initialize()
        val capabilities = client.capabilities()
        client.setSetting("beep", "disabletouch")

        assertEquals("enable", capabilities.advancedSettings.single { it.key == "beep" }.value)
        assertEquals("60", capabilities.advancedSettings.single { it.key == "displayoff" }.value)
        repeat(7) { server.takeRequest() }
        val write = server.takeRequest()
        assertEquals("PUT", write.method)
        assertEquals("/ccapi/ver100/functions/beep", write.path)
        assertEquals("disabletouch", JSONObject(write.body.readUtf8()).getString("value"))
        assertTrue(CameraFeature.ADVANCED_SETTINGS in client.observedFeatureSnapshot())
    }

    @Test
    fun realDeviceFunctionSettingsRejectMalformedOrCrossVersionContracts() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{
                    "ver100":[
                        {"path":"/shooting/settings","get":true},
                        {"path":"/functions/beep","get":true},
                        {"path":"/functions/displayoff","get":true,"put":true}
                    ],
                    "ver110":[{"path":"/functions/beep","put":true}]
                }""",
            ),
        )
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        server.enqueue(jsonResponse("""{"value":"60","ability":["60","future"]}"""))

        client.initialize()
        val capabilities = client.capabilities()

        assertFalse(capabilities.advancedSettings.any { it.key == "beep" })
        assertFalse(capabilities.advancedSettings.any { it.key == "displayoff" })
        assertEquals(3, server.requestCount)
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
    fun simulatorClockSyncUsesBackedEndpoint() = runTest {
        server.enqueue(jsonResponse(STATUS_JSON))

        val status = client.syncCameraClock()
        val request = server.takeRequest()

        assertEquals("/ccapi/clock/sync", request.path)
        assertEquals("POST", request.method)
        assertEquals(0, JSONObject(request.body.readUtf8()).length())
        assertTrue(status.connected)
        assertTrue(CameraFeature.CAMERA_CLOCK_SYNC in client.observedFeatureSnapshot())
    }

    @Test
    fun realClockSyncRequiresAdvertisedReadWriteAndVerifiesReadback() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        val formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss xx", Locale.US)
        val requested = ZonedDateTime.now()
        val cameraClock = formatter.format(requested)
        val daylight = requested.zone.rules.isDaylightSavings(requested.toInstant())
        server.enqueue(
            jsonResponse(
                """{"ver110":[
                    {"path":"/functions/datetime","get":true,"put":true},
                    {"path":"/shooting/settings","get":true}
                ]}""",
            ),
        )
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        server.enqueue(jsonResponse("""{"datetime":"$cameraClock","dst":$daylight}"""))
        server.enqueue(jsonResponse("""{"datetime":"$cameraClock","dst":$daylight}"""))
        enqueueRealStatus()

        client.initialize()
        val capabilities = client.capabilities()
        val status = client.syncCameraClock()
        server.takeRequest()
        server.takeRequest()
        val write = server.takeRequest()
        val read = server.takeRequest()
        val payload = JSONObject(write.body.readUtf8())

        assertTrue(capabilities.matrix.supports(CameraFeature.CAMERA_CLOCK_SYNC))
        assertEquals("PUT", write.method)
        assertEquals("/ccapi/ver110/functions/datetime", write.path)
        assertTrue(payload.getString("datetime").matches(Regex("^[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4}$")))
        assertTrue(payload.has("dst"))
        assertEquals("GET", read.method)
        assertEquals("/ccapi/ver110/functions/datetime", read.path)
        assertTrue(status.connected)
        assertTrue(CameraFeature.CAMERA_CLOCK_SYNC in client.observedFeatureSnapshot())
    }

    @Test
    fun realClockSyncDoesNotCombineReadAndWriteAcrossApiVersions() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{
                    "ver100":[
                        {"path":"/shooting/settings","get":true},
                        {"path":"/functions/datetime","get":true}
                    ],
                    "ver110":[{"path":"/functions/datetime","put":true}]
                }""",
            ),
        )
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))

        client.initialize()
        val capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.CAMERA_CLOCK_SYNC))
        assertTrue(CameraFeature.CAMERA_CLOCK_SYNC in capabilities.matrix.planned)
        try {
            client.syncCameraClock()
            throw AssertionError("Expected unsupported camera clock synchronization")
        } catch (_: UnsupportedOperationException) {
            // Expected: GET and PUT are not advertised by the same API version.
        }
        assertEquals(2, server.requestCount)
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
    fun simulatorClickWhiteBalanceSendsNormalizedCoordinatesAndUpdatesStatus() = runTest {
        server.enqueue(jsonResponse(STATUS_JSON.replace("\"white_balance\": \"auto\"", "\"white_balance\": \"click\"")))

        val status = client.clickWhiteBalance(0.4, 0.6)
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertEquals("/ccapi/whitebalance/click", request.path)
        assertEquals("POST", request.method)
        assertEquals(0.4, body.getDouble("x"), 0.0001)
        assertEquals(0.6, body.getDouble("y"), 0.0001)
        assertEquals("click", status.exposure.whiteBalance)
    }

    @Test
    fun realClickWhiteBalanceUsesAdvertisedCanonCoordinates() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver110":[
                    {"path":"/shooting/liveview","post":true,"delete":true},
                    {"path":"/shooting/liveview/clickwb","post":true},
                    {"path":"/shooting/liveview/flipdetail","get":true},
                    {"path":"/shooting/settings","get":true}
                ]}""",
            ),
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x05, 0x06, 0xFF.toByte(), 0xD9.toByte())
        server.enqueue(
            binaryResponse(
                detailedLiveView(
                    jpeg,
                    """{"liveview":{"image":{"positionx":100,"positiony":200,"positionwidth":6000,"positionheight":4000}}}""",
                ),
                "application/octet-stream",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON.replace("\"auto\"", "\"click\"")))

        client.initialize()
        client.liveViewFrame(cacheKey = 8)
        val status = client.clickWhiteBalance(0.4, 0.6)

        server.takeRequest()
        val frame = server.takeRequest()
        val click = server.takeRequest()
        val body = JSONObject(click.body.readUtf8())
        assertEquals("/ccapi/ver110/shooting/liveview/flipdetail?kind=both", frame.path)
        assertEquals("POST", click.method)
        assertEquals("/ccapi/ver110/shooting/liveview/clickwb", click.path)
        assertEquals(2500, body.getInt("positionx"))
        assertEquals(2600, body.getInt("positiony"))
        assertEquals(setOf("positionx", "positiony"), body.keys().asSequence().toSet())
        val statusRequests = List(3) { server.takeRequest().path }
        assertEquals(
            listOf(
                "/ccapi/ver110/devicestatus/batterylist",
                "/ccapi/ver110/devicestatus/storage",
                "/ccapi/ver110/shooting/settings",
            ),
            statusRequests,
        )
        assertEquals("click", status.exposure.whiteBalance)
    }

    @Test
    fun realTapFocusUsesAdvertisedCanonCoordinates() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver110":[
                    {"path":"/shooting/liveview","post":true,"delete":true},
                    {"path":"/shooting/liveview/afframeposition","put":true},
                    {"path":"/shooting/liveview/clickwb","post":true},
                    {"path":"/shooting/liveview/flipdetail","get":true}
                ]}""",
            ),
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x05, 0x06, 0xFF.toByte(), 0xD9.toByte())
        server.enqueue(
            binaryResponse(
                detailedLiveView(
                    jpeg,
                    """{"liveview":{"image":{"positionx":100,"positiony":200,"positionwidth":6000,"positionheight":4000}}}""",
                ),
                "application/octet-stream",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))

        client.initialize()
        client.liveViewFrame(cacheKey = 7)
        client.tapFocus(0.25, 0.75)

        server.takeRequest()
        val frame = server.takeRequest()
        val focus = server.takeRequest()
        val body = JSONObject(focus.body.readUtf8())
        assertEquals("/ccapi/ver110/shooting/liveview/flipdetail?kind=both", frame.path)
        assertEquals("PUT", focus.method)
        assertEquals("/ccapi/ver110/shooting/liveview/afframeposition", focus.path)
        assertEquals(1600, body.getInt("positionx"))
        assertEquals(3200, body.getInt("positiony"))
        assertEquals(setOf("positionx", "positiony"), body.keys().asSequence().toSet())
    }

    @Test
    fun realTapFocusWithoutDetailedFrameDoesNotSendGuessedCoordinates() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/liveview","post":true,"delete":true},
                    {"path":"/shooting/liveview/afframeposition","put":true},
                    {"path":"/shooting/liveview/clickwb","post":true},
                    {"path":"/shooting/liveview/flipdetail","get":true}
                ]}""",
            ),
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte())
        server.enqueue(binaryResponse(detailedPacket(0x00, jpeg), "application/octet-stream"))
        server.enqueue(binaryResponse(detailedPacket(0x00, jpeg), "application/octet-stream"))

        client.initialize()
        val failure = runCatching { client.tapFocus(0.25, 0.75) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("position metadata"))
        assertEquals(2, server.requestCount)

        val clickFailure = runCatching { client.clickWhiteBalance(0.25, 0.75) }.exceptionOrNull()
        assertTrue(clickFailure is IllegalStateException)
        assertTrue(clickFailure?.message.orEmpty().contains("position metadata"))
        assertEquals(3, server.requestCount)
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
    fun startLiveViewFallsBackFromInvalidLargeToMediumAndPrunesLarge() = runTest {
        client.forceRealCamera()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Invalid parameter"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Invalid parameter"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))

        client.startLiveView(LiveViewRequest(size = LiveViewSize.LARGE))
        val capabilities = client.capabilities()
        val starts = List(3) { JSONObject(server.takeRequest().body.readUtf8()) }

        assertEquals("large", starts[0].getString("liveviewsize"))
        assertFalse(starts[1].has("liveviewsize"))
        assertEquals("medium", starts[2].getString("liveviewsize"))
        assertEquals(listOf(LiveViewSize.SMALL, LiveViewSize.MEDIUM), capabilities.liveView.sizes)
        assertEquals(LiveViewSize.MEDIUM, capabilities.liveView.defaultSize)
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
        val capabilities = client.capabilities()
        val failure = runCatching { client.startLiveView() }.exceptionOrNull()

        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/", server.takeRequest().path)
        assertEquals("/ccapi/ver110/deviceinformation", server.takeRequest().path)
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("complete Live View"))
        assertEquals(
            listOf("HTTP_ERROR", "HTTP_ERROR", "IDENTITY"),
            capabilities.evidence.discoveryTrace.map(CameraDiscoveryAttempt::outcome),
        )
        assertEquals(200, capabilities.evidence.discoveryTrace.last().httpStatus)
        assertTrue("productname" in capabilities.evidence.discoveryTrace.last().responseKeys)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun discoveryLoadsCanonDeveloperApiListWhenRootOmitsOperations() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        server.enqueue(jsonResponse("""{"value":"No list of APIs"}"""))
        server.enqueue(jsonResponse(DISCOVERY_JSON))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))

        client.initialize()
        val capabilities = client.capabilities()

        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/ver100/topurlfordev", server.takeRequest().path)
        assertEquals("/ccapi/ver110/shooting/settings", server.takeRequest().path)
        assertTrue(capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(capabilities.matrix.supports(CameraFeature.VIDEO_RECORDING))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_BROWSER))
        assertEquals(
            "GET /ccapi/ver100/topurlfordev (Canon developer API fallback)",
            capabilities.evidence.source,
        )
        assertTrue(
            "POST /ccapi/ver110/shooting/control/shutterbutton" in
                capabilities.evidence.advertisedCommands,
        )
        assertEquals(
            listOf("NO_API_LIST", "OPERATIONS"),
            capabilities.evidence.discoveryTrace.map(CameraDiscoveryAttempt::outcome),
        )
        assertEquals(
            listOf("GET /ccapi", "GET /ccapi/ver100/topurlfordev"),
            capabilities.evidence.discoveryTrace.map(CameraDiscoveryAttempt::endpoint),
        )
        assertTrue(capabilities.evidence.discoveryTrace.last().advertisedOperationCount > 0)
        assertTrue(capabilities.evidence.discoveryTrace.flatMap { it.responseKeys }.none { "secret" in it.lowercase() })
    }

    @Test
    fun discoveryLoadsDeveloperApiListWhenFirmwareReturnsVersionWithoutCommands() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        server.enqueue(
            jsonResponse(
                """{"api":["/ccapi/ver100"],"version":"ver100","ver100":[]}""",
            ),
        )
        server.enqueue(jsonResponse(DISCOVERY_JSON))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))

        client.initialize()
        val capabilities = client.capabilities()

        assertEquals(
            listOf(
                "/ccapi",
                "/ccapi/ver100/topurlfordev",
                "/ccapi/ver110/shooting/settings",
            ),
            List(server.requestCount) { server.takeRequest().path },
        )
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW))
        assertTrue(capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(capabilities.matrix.supports(CameraFeature.VIDEO_RECORDING))
        assertTrue(capabilities.evidence.advertisedCommands.isNotEmpty())
        assertEquals(
            "GET /ccapi/ver100/topurlfordev (Canon developer API fallback)",
            capabilities.evidence.source,
        )
        assertEquals(
            listOf("ZERO_OPERATIONS", "OPERATIONS"),
            capabilities.evidence.discoveryTrace.map(CameraDiscoveryAttempt::outcome),
        )
        assertEquals(listOf("ver100"), capabilities.evidence.discoveryTrace.first().protocolVersions)
        assertEquals(0, capabilities.evidence.discoveryTrace.first().advertisedOperationCount)
    }

    @Test
    fun discoveryRejectsEmptyDeveloperApiListWithoutInventingCapabilities() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        server.enqueue(jsonResponse("""{"ver100":[]}"""))
        server.enqueue(jsonResponse("""{"ver100":[]}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))

        val failure = runCatching { client.initialize() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("did not advertise any valid operations"))
        assertEquals(
            listOf(
                "/ccapi",
                "/ccapi/ver100/topurlfordev",
                "/ccapi/",
                "/ccapi/ver110/deviceinformation",
                "/ccapi/ver100/deviceinformation",
            ),
            List(server.requestCount) { server.takeRequest().path },
        )
    }

    @Test
    fun discoveryReportsDeveloperApiFailureWithoutInventingCapabilities() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        server.enqueue(jsonResponse("""{"value":"No list of APIs"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("camera busy"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))

        val failure = runCatching { client.initialize() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("/ccapi/ver100/topurlfordev"))
        assertEquals(
            listOf(
                "/ccapi",
                "/ccapi/ver100/topurlfordev",
                "/ccapi/",
                "/ccapi/ver110/deviceinformation",
                "/ccapi/ver100/deviceinformation",
            ),
            List(server.requestCount) { server.takeRequest().path },
        )
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
        assertTrue(capabilities.matrix.supports(CameraFeature.BULB_EXPOSURE))
        assertTrue(!capabilities.matrix.isPlanned(CameraFeature.STILL_CAPTURE))
        assertTrue(capabilities.matrix.supports(CameraFeature.SHUTTER_HALF_PRESS))
        assertTrue(capabilities.matrix.supports(CameraFeature.FOCUS_DRIVE))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_BROWSER))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_THUMBNAIL))
        assertTrue(!capabilities.matrix.isPlanned(CameraFeature.MEDIA_THUMBNAIL))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_PREVIEW))
        assertTrue(!capabilities.matrix.isPlanned(CameraFeature.MEDIA_PREVIEW))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_DOWNLOAD))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_DELETE))
        assertTrue(capabilities.matrix.supports(CameraFeature.EXPOSURE_CONTROL))
        assertTrue(!capabilities.matrix.supports(CameraFeature.TAP_FOCUS))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.TAP_FOCUS))
        assertTrue(!capabilities.matrix.supports(CameraFeature.BATTERY_STATUS))
        assertEquals("GET /ccapi", capabilities.evidence.source)
        assertEquals(listOf("ver110"), capabilities.evidence.protocolVersions)
        assertTrue("POST /ccapi/ver110/shooting/control/shutterbutton" in capabilities.evidence.advertisedCommands)
        assertTrue("iso" in capabilities.evidence.writableSettings)
        assertTrue(!capabilities.evidence.truncated)
    }

    @Test
    fun realEventPollingRequiresAdvertisedGetAndDeleteLifecycle() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        server.enqueue(
            jsonResponse(
                """{"ver110":[{"path":"/event/polling","get":true,"delete":true}]}""",
            ),
        )
        server.enqueue(jsonResponse("""{"shootingsettings":{"iso":{"value":"1600"}}}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        client.initialize()
        val capabilities = client.capabilities()
        val event = client.pollEvent()
        client.stopEventPolling()

        assertTrue(capabilities.matrix.supports(CameraFeature.EVENT_POLLING))
        assertEquals(setOf("shootingsettings"), event.changedKeys)
        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/ver110/event/polling?timeout=long", server.takeRequest().path)
        val stop = server.takeRequest()
        assertEquals("DELETE", stop.method)
        assertEquals("/ccapi/ver110/event/polling", stop.path)
    }

    @Test
    fun realEventPollingIsNotAdvertisedForIncompleteLifecycle() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        server.enqueue(
            jsonResponse(
                """{"ver110":[{"path":"/event/polling","get":true}]}""",
            ),
        )

        client.initialize()
        val capabilities = client.capabilities()
        val failure = runCatching { client.pollEvent() }.exceptionOrNull()

        assertFalse(capabilities.matrix.supports(CameraFeature.EVENT_POLLING))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.EVENT_POLLING))
        assertTrue(failure is UnsupportedOperationException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun canonMultipartLiveViewUsesAdvertisedPersistentLifecycle() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x21, 0xFF.toByte(), 0xD9.toByte())
        val multipart = Buffer()
            .writeUtf8("--canon\nContent-Type: image/jpeg\nContent-Length: ${jpeg.size}\n\n")
            .write(jpeg)
            .writeUtf8("\n--canon--\n")
        server.enqueue(jsonResponse(DISCOVERY_MULTIPART_JSON))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "multipart/x-mixed-replace;boundary=canon")
                .setChunkedBody(multipart, 3),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.initialize()
        val capabilities = client.capabilities()
        client.startLiveView(LiveViewRequest(source = LiveViewSource.AUTO))

        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_MULTIPART))
        assertEquals(
            listOf(LiveViewSource.CCAPI_MULTIPART, LiveViewSource.CCAPI_JPEG_POLLING),
            capabilities.liveView.sources,
        )
        assertFalse(CameraFeature.LIVE_VIEW_MULTIPART in client.observedFeatureSnapshot())
        val frame = client.liveViewFrame(cacheKey = 1)
        assertArrayEquals(jpeg, frame.bytes)
        assertEquals("image/jpeg", frame.contentType)
        assertEquals(
            "${server.url("/").toString().trimEnd('/')}/ccapi/ver110/shooting/liveview/multipart",
            frame.sourceUrl,
        )
        assertTrue(CameraFeature.LIVE_VIEW_MULTIPART in client.observedFeatureSnapshot())

        client.stopLiveView()

        val requests = List(5) { server.takeRequest() }
        assertEquals(
            listOf(
                "/ccapi",
                "/ccapi/ver110/shooting/liveview",
                "/ccapi/ver110/shooting/liveview/multipart",
                "/ccapi/ver110/shooting/liveview/multipart",
                "/ccapi/ver110/shooting/liveview",
            ),
            requests.map { it.path },
        )
        assertEquals(listOf("GET", "POST", "GET", "DELETE", "DELETE"), requests.map { it.method })
    }

    @Test
    fun canonMultipartLiveViewRetriesTransientNotStartedResponses() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x31, 0xFF.toByte(), 0xD9.toByte())
        val multipart = Buffer()
            .writeUtf8("--canon\nContent-Type: image/jpeg\nContent-Length: ${jpeg.size}\n\n")
            .write(jpeg)
            .writeUtf8("\n--canon--\n")
        server.enqueue(jsonResponse(DISCOVERY_MULTIPART_JSON))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setBody("""{"message":"Live view not started"}"""),
            )
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "multipart/x-mixed-replace;boundary=canon")
                .setChunkedBody(multipart, 3),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.initialize()
        client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_MULTIPART))
        val frame = client.liveViewFrame(cacheKey = 1)
        client.stopLiveView()

        assertArrayEquals(jpeg, frame.bytes)
        val requests = List(7) { server.takeRequest() }
        assertEquals(3, requests.count { it.path == "/ccapi/ver110/shooting/liveview/multipart" && it.method == "GET" })
        assertEquals("DELETE", requests[5].method)
        assertEquals("DELETE", requests[6].method)
    }

    @Test
    fun multipartCapabilityRequiresAllOperationsInTheSameApiVersion() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        server.enqueue(
            jsonResponse(
                """{
                  "ver100":[{"path":"/shooting/liveview","post":true,"delete":true}],
                  "ver110":[{"path":"/shooting/liveview/multipart","get":true,"delete":true}]
                }""",
            ),
        )

        client.initialize()
        val capabilities = client.capabilities()
        val failure = runCatching {
            client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_MULTIPART))
        }.exceptionOrNull()

        assertFalse(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_MULTIPART))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.LIVE_VIEW_MULTIPART))
        assertTrue(failure?.message.orEmpty().contains("complete Canon multipart"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun version100EventPollingUsesContinueMode() = runTest {
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
        )
        server.enqueue(
            jsonResponse(
                """{"ver100":[{"path":"/event/polling","get":true,"delete":true}]}""",
            ),
        )
        server.enqueue(jsonResponse("{}"))

        client.initialize()
        val event = client.pollEvent()

        assertTrue(event.changedKeys.isEmpty())
        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/ver100/event/polling?continue=on", server.takeRequest().path)
    }

    @Test
    fun simulatorEventPollingTracksSequence() = runTest {
        server.enqueue(jsonResponse("""{"sequence":3,"keys":["shootingsettings","contents"]}"""))
        server.enqueue(jsonResponse("""{"sequence":3,"keys":[]}"""))

        val first = client.pollEvent()
        val second = client.pollEvent()

        assertEquals(setOf("shootingsettings", "contents"), first.changedKeys)
        assertTrue(second.changedKeys.isEmpty())
        assertEquals("/ccapi/events?after=0", server.takeRequest().path)
        assertEquals("/ccapi/events?after=3", server.takeRequest().path)
    }

    @Test
    fun canonRtpLiveViewUsesAdvertisedSdpStartAndStopContract() = runTest {
        lateinit var nativeSession: FakeNativeLiveViewSession
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
            rtpDestinationAddress = "192.168.11.5",
            rtpSessionFactory = CcapiRtpSessionFactory { description, destinationAddress ->
                FakeNativeLiveViewSession(description, destinationAddress).also { nativeSession = it }
            },
        )
        server.enqueue(jsonResponse(DISCOVERY_RTP_JSON))
        server.enqueue(MockResponse().setHeader("content-type", "text/plain").setBody(CANON_RTP_SDP))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("{}"))

        client.initialize()
        val capabilities = client.capabilities()
        client.startLiveView(LiveViewRequest(fps = 15, source = LiveViewSource.CCAPI_RTP))

        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW))
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_RTP))
        assertEquals(listOf(LiveViewSource.CCAPI_RTP), capabilities.liveView.sources)
        assertTrue(nativeSession.started)
        assertTrue(nativeSession.readyAwaited)
        assertEquals(15, nativeSession.selectedFps)
        assertEquals("rtp://192.168.11.5:12000", nativeSession.sourceUrl)
        assertEquals(nativeSession, client.nativeLiveViewSession)
        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/ver110/shooting/liveview/rtpsessiondesc", server.takeRequest().path)
        val start = server.takeRequest()
        val startBody = JSONObject(start.body.readUtf8())
        assertEquals("/ccapi/ver110/shooting/liveview/rtp", start.path)
        assertEquals("POST", start.method)
        assertEquals("start", startBody.getString("action"))
        assertEquals("192.168.11.5", startBody.getString("ipaddress"))

        client.stopLiveView()

        val stop = server.takeRequest()
        val stopBody = JSONObject(stop.body.readUtf8())
        assertEquals("/ccapi/ver110/shooting/liveview/rtp", stop.path)
        assertEquals("stop", stopBody.getString("action"))
        assertEquals("", stopBody.getString("ipaddress"))
        assertTrue(nativeSession.closed)
        assertNull(client.nativeLiveViewSession)
    }

    @Test
    fun automaticLiveViewCleansUpRejectedRtpAndFallsBackToJpeg() = runTest {
        lateinit var nativeSession: FakeNativeLiveViewSession
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
            rtpDestinationAddress = "192.168.11.5",
            rtpSessionFactory = CcapiRtpSessionFactory { description, destinationAddress ->
                FakeNativeLiveViewSession(description, destinationAddress).also { nativeSession = it }
            },
        )
        server.enqueue(jsonResponse(DISCOVERY_RTP_AND_JPEG_JSON))
        server.enqueue(MockResponse().setHeader("content-type", "text/plain").setBody(CANON_RTP_SDP))
        server.enqueue(MockResponse().setResponseCode(400).setBody("RTP unavailable"))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(MockResponse().setResponseCode(204))

        client.initialize()
        client.startLiveView(LiveViewRequest(source = LiveViewSource.AUTO))

        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/ver110/shooting/liveview/rtpsessiondesc", server.takeRequest().path)
        assertEquals("start", JSONObject(server.takeRequest().body.readUtf8()).getString("action"))
        assertEquals("stop", JSONObject(server.takeRequest().body.readUtf8()).getString("action"))
        val jpegStart = server.takeRequest()
        assertEquals("/ccapi/ver110/shooting/liveview", jpegStart.path)
        assertEquals("on", JSONObject(jpegStart.body.readUtf8()).getString("cameradisplay"))
        assertTrue(nativeSession.closed)
        assertNull(client.nativeLiveViewSession)
        assertTrue(client.liveViewFrameUrl(1, LiveViewRequest(source = LiveViewSource.AUTO)).contains("/flip"))
    }

    @Test
    fun automaticLiveViewCleansUpRtpWithoutVideoAndFallsBackToJpeg() = runTest {
        lateinit var nativeSession: FakeNativeLiveViewSession
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
            rtpDestinationAddress = "192.168.11.5",
            rtpSessionFactory = CcapiRtpSessionFactory { description, destinationAddress ->
                FakeNativeLiveViewSession(
                    description,
                    destinationAddress,
                    readyError = IllegalStateException("Canon RTP video received no packets."),
                ).also { nativeSession = it }
            },
        )
        server.enqueue(jsonResponse(DISCOVERY_RTP_AND_JPEG_JSON))
        server.enqueue(MockResponse().setHeader("content-type", "text/plain").setBody(CANON_RTP_SDP))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(MockResponse().setResponseCode(204))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x31, 0xFF.toByte(), 0xD9.toByte())
        server.enqueue(binaryResponse(jpeg, "image/jpeg"))

        client.initialize()
        client.startLiveView(LiveViewRequest(source = LiveViewSource.AUTO))
        val frame = client.liveViewFrame(cacheKey = 1, request = LiveViewRequest(source = LiveViewSource.AUTO))

        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/ver110/shooting/liveview/rtpsessiondesc", server.takeRequest().path)
        assertEquals("start", JSONObject(server.takeRequest().body.readUtf8()).getString("action"))
        assertEquals("stop", JSONObject(server.takeRequest().body.readUtf8()).getString("action"))
        assertEquals("/ccapi/ver110/shooting/liveview", server.takeRequest().path)
        assertTrue(server.takeRequest().path.orEmpty().startsWith("/ccapi/ver110/shooting/liveview/flip?"))
        assertTrue(nativeSession.readyAwaited)
        assertTrue(nativeSession.closed)
        assertNull(client.nativeLiveViewSession)
        assertArrayEquals(jpeg, frame.bytes)
        assertEquals(LiveViewSource.CCAPI_JPEG_POLLING, client.currentLiveViewSource())
        assertFalse(CameraFeature.LIVE_VIEW_RTP in client.observedFeatureSnapshot())
        assertTrue(CameraFeature.LIVE_VIEW_JPEG_POLLING in client.observedFeatureSnapshot())
    }

    @Test
    fun latestFirmwareAutoFallsBackToPostOnlySmallJpegAndStopsWithPostOff() = runTest {
        lateinit var nativeSession: FakeNativeLiveViewSession
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
            rtpDestinationAddress = "192.168.11.5",
            rtpSessionFactory = CcapiRtpSessionFactory { description, destinationAddress ->
                FakeNativeLiveViewSession(description, destinationAddress).also { nativeSession = it }
            },
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x41, 0x42, 0xFF.toByte(), 0xD9.toByte())
        server.enqueue(jsonResponse(DISCOVERY_POST_ONLY_RTP_AND_JPEG_JSON))
        server.enqueue(MockResponse().setHeader("content-type", "text/plain").setBody(CANON_RTP_SDP))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"Mode not supported"}"""))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"Mode not supported"}"""))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(binaryResponse(jpeg, "image/jpeg"))
        server.enqueue(jsonResponse("{}"))

        client.initialize()
        val initialCapabilities = client.capabilities()
        client.startLiveView(
            LiveViewRequest(
                fps = 15,
                size = LiveViewSize.MEDIUM,
                source = LiveViewSource.AUTO,
            )
        )
        val activeCapabilities = client.capabilities()

        assertEquals(
            listOf(LiveViewSource.CCAPI_RTP, LiveViewSource.CCAPI_JPEG_POLLING),
            initialCapabilities.liveView.sources,
        )
        assertEquals(LiveViewSource.CCAPI_JPEG_POLLING, client.currentLiveViewSource())
        assertEquals(listOf(LiveViewSize.SMALL, LiveViewSize.LARGE), activeCapabilities.liveView.sizes)
        assertEquals(LiveViewSize.SMALL, activeCapabilities.liveView.defaultSize)
        assertFalse(CameraFeature.LIVE_VIEW_RTP in client.observedFeatureSnapshot())
        assertTrue(CameraFeature.LIVE_VIEW_JPEG_POLLING in client.observedFeatureSnapshot())
        assertTrue(nativeSession.closed)

        client.stopLiveView()

        val requests = List(10) { server.takeRequest() }
        assertEquals("/ccapi", requests[0].path)
        assertEquals("/ccapi/ver100/shooting/liveview/rtpsessiondesc", requests[1].path)
        assertEquals("start", JSONObject(requests[2].body.readUtf8()).getString("action"))
        assertEquals("stop", JSONObject(requests[3].body.readUtf8()).getString("action"))
        assertEquals("medium", JSONObject(requests[4].body.readUtf8()).getString("liveviewsize"))
        assertTrue(requests[5].path.orEmpty().startsWith("/ccapi/ver100/shooting/liveview/flip?"))
        assertEquals("off", JSONObject(requests[6].body.readUtf8()).getString("liveviewsize"))
        assertEquals("small", JSONObject(requests[7].body.readUtf8()).getString("liveviewsize"))
        assertTrue(requests[8].path.orEmpty().startsWith("/ccapi/ver100/shooting/liveview/flip?"))
        assertEquals("off", JSONObject(requests[9].body.readUtf8()).getString("liveviewsize"))
        assertEquals(List(6) { "POST" }, listOf(
            requests[2].method,
            requests[3].method,
            requests[4].method,
            requests[6].method,
            requests[7].method,
            requests[9].method,
        ))
    }

    @Test
    fun latestFirmwareAutoFallsBackFromRejectedRtpToPostOnlyMultipart() = runTest {
        lateinit var nativeSession: FakeNativeLiveViewSession
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
            rtpDestinationAddress = "192.168.11.5",
            rtpSessionFactory = CcapiRtpSessionFactory { description, destinationAddress ->
                FakeNativeLiveViewSession(description, destinationAddress).also { nativeSession = it }
            },
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x51, 0x52, 0xFF.toByte(), 0xD9.toByte())
        val multipart = Buffer()
            .writeUtf8("--boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n")
            .write(jpeg)
            .writeUtf8("\r\n--boundary--\r\n")
        server.enqueue(jsonResponse(DISCOVERY_LATEST_FIRMWARE_LIVE_VIEW_JSON))
        server.enqueue(MockResponse().setHeader("content-type", "text/plain").setBody(CANON_RTP_SDP))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"Mode not supported"}"""))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "multipart/x-mixed-replace;boundary=boundary")
                .setChunkedBody(multipart, 3),
        )
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"Mode not supported"}"""))
        server.enqueue(jsonResponse("{}"))

        client.initialize()
        val capabilities = client.capabilities()
        client.startLiveView(
            LiveViewRequest(
                fps = 15,
                size = LiveViewSize.MEDIUM,
                source = LiveViewSource.AUTO,
            )
        )
        val frame = client.liveViewFrame(cacheKey = 1)

        assertEquals(
            listOf(
                LiveViewSource.CCAPI_RTP,
                LiveViewSource.CCAPI_MULTIPART,
                LiveViewSource.CCAPI_JPEG_POLLING,
            ),
            capabilities.liveView.sources,
        )
        assertEquals(LiveViewSource.CCAPI_MULTIPART, client.currentLiveViewSource())
        assertArrayEquals(jpeg, frame.bytes)
        assertFalse(CameraFeature.LIVE_VIEW_RTP in client.observedFeatureSnapshot())
        assertTrue(CameraFeature.LIVE_VIEW_MULTIPART in client.observedFeatureSnapshot())
        assertTrue(nativeSession.closed)

        client.stopLiveView()

        val requests = List(8) { server.takeRequest() }
        assertEquals("/ccapi", requests[0].path)
        assertEquals("/ccapi/ver100/shooting/liveview/rtpsessiondesc", requests[1].path)
        assertEquals("start", JSONObject(requests[2].body.readUtf8()).getString("action"))
        assertEquals("stop", JSONObject(requests[3].body.readUtf8()).getString("action"))
        assertEquals("medium", JSONObject(requests[4].body.readUtf8()).getString("liveviewsize"))
        assertEquals("GET", requests[5].method)
        assertEquals("/ccapi/ver100/shooting/liveview/multipart", requests[5].path)
        assertEquals("DELETE", requests[6].method)
        assertEquals("off", JSONObject(requests[7].body.readUtf8()).getString("liveviewsize"))
    }

    @Test
    fun explicitRtpWithoutVideoStopsTheCameraAndReportsTheReadinessFailure() = runTest {
        lateinit var nativeSession: FakeNativeLiveViewSession
        client = CcapiClient(
            baseUrl = server.url("/").toString(),
            treatAsSimulator = false,
            rtpDestinationAddress = "192.168.11.5",
            rtpSessionFactory = CcapiRtpSessionFactory { description, destinationAddress ->
                FakeNativeLiveViewSession(
                    description,
                    destinationAddress,
                    readyError = IllegalStateException("Canon RTP video received no decodable key frame."),
                ).also { nativeSession = it }
            },
        )
        server.enqueue(jsonResponse(DISCOVERY_RTP_JSON))
        server.enqueue(MockResponse().setHeader("content-type", "text/plain").setBody(CANON_RTP_SDP))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("{}"))

        client.initialize()
        val failure = runCatching {
            client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_RTP))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("no decodable key frame"))
        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/ver110/shooting/liveview/rtpsessiondesc", server.takeRequest().path)
        assertEquals("start", JSONObject(server.takeRequest().body.readUtf8()).getString("action"))
        assertEquals("stop", JSONObject(server.takeRequest().body.readUtf8()).getString("action"))
        assertTrue(nativeSession.readyAwaited)
        assertTrue(nativeSession.closed)
        assertNull(client.nativeLiveViewSession)
        assertNull(client.currentLiveViewSource())
        assertFalse(CameraFeature.LIVE_VIEW in client.observedFeatureSnapshot())
        assertFalse(CameraFeature.LIVE_VIEW_RTP in client.observedFeatureSnapshot())
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
    fun simulatorFocusDriveUsesBackedEndpointAndVerifiesResponse() = runTest {
        server.enqueue(jsonResponse("""{"ok":true,"direction":"far","step":"large"}"""))

        val result = client.driveFocus(FocusDriveDirection.FAR, FocusDriveStep.LARGE)
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())

        assertEquals("POST", request.method)
        assertEquals("/ccapi/focus/drive", request.path)
        assertEquals("far", body.getString("direction"))
        assertEquals("large", body.getString("step"))
        assertTrue(result.ok)
        assertEquals(FocusDriveDirection.FAR, result.direction)
        assertEquals(FocusDriveStep.LARGE, result.step)
        assertTrue(CameraFeature.FOCUS_DRIVE in client.observedFeatureSnapshot())
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
                    {"path":"/shooting/liveview","delete":true},
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
        assertTrue(CameraFeature.STILL_CAPTURE !in client.observedFeatureSnapshot())
        assertTrue(CameraFeature.EXPOSURE_CONTROL !in client.observedFeatureSnapshot())
        assertTrue(CameraFeature.LIVE_VIEW !in client.observedFeatureSnapshot())
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
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","maxsize":64000000000,"spacesize":32000000000,"freeimages":-1},{"name":"card2","capacity":128000000000,"freebytes":64000000000,"remainingimages":2400}]}"""))
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
        assertEquals(192_000_000_000L, status.storageTotalBytes)
        assertEquals(96_000_000_000L, status.storageFreeBytes)
        assertEquals(2_400L, status.storageFreeImages)
        assertEquals(2, status.storageDeviceCount)
        assertEquals("800", status.exposure.iso)
        assertEquals("1/50", status.exposure.shutter)
        assertEquals("2.8", status.exposure.aperture)
        assertEquals("auto", status.exposure.whiteBalance)
    }

    @Test
    fun realStatusUsesOnlyAdvertisedStrictCanonLensAndTemperaturePayloads() = runTest {
        server.enqueue(jsonResponse(DEVICE_STATUS_DISCOVERY_JSON))
        server.enqueue(jsonResponse("""{"batterylist":[{"level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse("""{"recordableshots":2418,"remainingtime":null}"""))
        server.enqueue(jsonResponse("""{"mount":true,"name":"RF24-105mm F4 L IS USM"}"""))
        server.enqueue(jsonResponse("""{"status":"frameratedown_and_restrictionmovierecording"}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val status = client.status()
        val capabilities = client.capabilities()

        assertEquals(LensStatus(true, "RF24-105mm F4 L IS USM"), status.lens)
        assertEquals(CameraTemperatureStatus.FRAME_RATE_DOWN_AND_RESTRICTION_MOVIE_RECORDING, status.temperature)
        assertEquals(2_418L, status.recordableShots)
        assertNull(status.remainingRecordingSeconds)
        assertTrue(status.temperature?.frameRateReduced == true)
        assertFalse(status.temperature?.movieRecordingAllowed ?: true)
        assertTrue(capabilities.matrix.supports(CameraFeature.LENS_STATUS))
        assertTrue(capabilities.matrix.supports(CameraFeature.TEMPERATURE_STATUS))
        assertTrue(capabilities.matrix.supports(CameraFeature.RECORDABLE_STATUS))
        assertEquals(
            listOf(
                "/ccapi",
                "/ccapi/ver100/devicestatus/batterylist",
                "/ccapi/ver100/devicestatus/storage",
                "/ccapi/ver100/shooting/information/recordable",
                "/ccapi/ver100/devicestatus/lens",
                "/ccapi/ver100/devicestatus/temperature",
                "/ccapi/ver100/shooting/settings",
            ),
            List(7) { server.takeRequest().path },
        )
    }

    @Test
    fun malformedAdvertisedLensAndTemperatureRemainPlanned() = runTest {
        server.enqueue(jsonResponse(DEVICE_STATUS_DISCOVERY_JSON))
        server.enqueue(jsonResponse("""{"batterylist":[{"level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse("""{"recordableshots":true,"remainingtime":-1}"""))
        server.enqueue(jsonResponse("""{"mount":"true","name":"RF24-105mm"}"""))
        server.enqueue(jsonResponse("""{"status":"hot"}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val status = client.status()
        val capabilities = client.capabilities()

        assertNull(status.lens)
        assertNull(status.temperature)
        assertNull(status.recordableShots)
        assertNull(status.remainingRecordingSeconds)
        assertFalse(capabilities.matrix.supports(CameraFeature.LENS_STATUS))
        assertFalse(capabilities.matrix.supports(CameraFeature.TEMPERATURE_STATUS))
        assertFalse(capabilities.matrix.supports(CameraFeature.RECORDABLE_STATUS))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.LENS_STATUS))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.TEMPERATURE_STATUS))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.RECORDABLE_STATUS))
    }

    @Test
    fun oversizedAdvertisedLensNameRemainsPlanned() = runTest {
        val oversizedName = "R".repeat(513)
        server.enqueue(jsonResponse(DEVICE_STATUS_DISCOVERY_JSON))
        server.enqueue(jsonResponse("""{"batterylist":[{"level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse("""{"recordableshots":2418,"remainingtime":null}"""))
        server.enqueue(jsonResponse("""{"mount":true,"name":"$oversizedName"}"""))
        server.enqueue(jsonResponse("""{"status":"normal"}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val status = client.status()
        val capabilities = client.capabilities()

        assertNull(status.lens)
        assertFalse(capabilities.matrix.supports(CameraFeature.LENS_STATUS))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.LENS_STATUS))
    }

    @Test
    fun temperatureRestrictionIsRefreshedBeforeStillCaptureWithoutSendingShutterCommand() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/devicestatus/temperature","get":true},
                    {"path":"/shooting/control/shutterbutton","post":true}
                ]}""".trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("""{"status":"disablerelease"}"""))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val failure = runCatching { client.captureStill() }.exceptionOrNull()
        server.enqueue(jsonResponse("""{"status":"hot"}"""))
        val staleFailure = runCatching { client.captureStill() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("temperature restriction"))
        assertTrue(staleFailure is IllegalStateException)
        assertEquals(listOf("GET", "GET", "GET"), List(3) { server.takeRequest().method })
        assertEquals(3, server.requestCount)
    }

    @Test
    fun canonTemperatureStatusesExposeDocumentedRestrictions() {
        assertEquals(12, CameraTemperatureStatus.entries.size)
        assertFalse(CameraTemperatureStatus.DISABLE_LIVE_VIEW.liveViewAllowed)
        assertFalse(CameraTemperatureStatus.DISABLE_RELEASE.stillCaptureAllowed)
        assertFalse(CameraTemperatureStatus.RESTRICTION_MOVIE_RECORDING.movieRecordingAllowed)
        assertTrue(CameraTemperatureStatus.FRAME_RATE_DOWN.frameRateReduced)
        assertTrue(CameraTemperatureStatus.STILL_QUALITY_WARNING.stillQualityWarning)
        assertTrue(CameraTemperatureStatus.WARNING.temperatureWarning)
        assertTrue(CameraTemperatureStatus.NORMAL.isNormal)
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
    fun canonZoomRequiresMatchingGetPostAndWritesIntegerValue() = runTest {
        val discovery = """{"ver100":[
            {"path":"/devicestatus/batterylist","get":true},
            {"path":"/devicestatus/storage","get":true},
            {"path":"/shooting/settings","get":true},
            {"path":"/shooting/control/zoom","get":true,"post":true}
        ]}""".trimIndent()
        server.enqueue(jsonResponse(discovery))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"value":50,"ability":{"min":0,"max":100,"step":25}}"""))
        server.enqueue(jsonResponse("""{"value":75}"""))
        server.enqueue(jsonResponse("""{"batterylist":[{"level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"value":75,"ability":{"min":0,"max":100,"step":25}}"""))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()
        val zoom = capabilities.advancedSettings.single { it.key == "zoom" }
        assertEquals(listOf("0", "25", "50", "75", "100"), zoom.values)
        assertEquals("50", zoom.value)
        assertTrue(capabilities.matrix.supports(CameraFeature.ZOOM_CONTROL))

        client.setSetting("zoom", "75")

        repeat(3) { server.takeRequest() }
        val write = server.takeRequest()
        assertEquals("POST", write.method)
        assertEquals("/ccapi/ver100/shooting/control/zoom", write.path)
        assertEquals(75, JSONObject(write.body.readUtf8()).getInt("value"))
    }

    @Test
    fun canonZoomIsHiddenWithoutMatchingPost() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/shooting/control/zoom","get":true}
                ]}""".trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("{}"))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.ZOOM_CONTROL))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.ZOOM_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key == "zoom" })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun canonSoundRecordingLevelRequiresMatchingGetPutAndWritesIntegerValue() = runTest {
        val discovery = """{"ver100":[
            {"path":"/devicestatus/batterylist","get":true},
            {"path":"/devicestatus/storage","get":true},
            {"path":"/shooting/settings","get":true},
            {"path":"/shooting/settings/soundrecording/level","get":true,"put":true}
        ]}""".trimIndent()
        server.enqueue(jsonResponse(discovery))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"value":32,"ability":{"min":0,"max":63,"step":1}}"""))
        repeat(2) {
            server.enqueue(jsonResponse("{}"))
            server.enqueue(jsonResponse("""{"value":32,"ability":{"min":0,"max":63,"step":1}}"""))
        }
        server.enqueue(jsonResponse("""{"value":48}"""))
        server.enqueue(jsonResponse("""{"batterylist":[{"level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"value":48,"ability":{"min":0,"max":63,"step":1}}"""))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()
        val soundLevel = capabilities.advancedSettings.single { it.key == "soundrecordinglevel" }

        assertEquals("32", soundLevel.value)
        assertEquals((0..63).map(Int::toString), soundLevel.values)
        assertTrue(capabilities.matrix.supports(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL))
        assertTrue("soundrecordinglevel" in capabilities.evidence.writableSettings)
        val requestCount = server.requestCount
        val invalid = runCatching { client.setSetting("soundrecordinglevel", "64") }.exceptionOrNull()
        assertTrue(invalid is IllegalStateException)
        assertTrue(invalid?.message.orEmpty().contains("not advertised"))
        assertEquals(requestCount + 2, server.requestCount)

        client.setSetting("soundrecordinglevel", "48")

        repeat(7) { server.takeRequest() }
        val write = server.takeRequest()
        val body = JSONObject(write.body.readUtf8())
        assertEquals("PUT", write.method)
        assertEquals("/ccapi/ver100/shooting/settings/soundrecording/level", write.path)
        assertEquals(48, body.getInt("value"))
        assertTrue(body.get("value") is Int)
    }

    @Test
    fun canonSoundRecordingLevelRejectsMalformedRanges() = runTest {
        val discovery = """{"ver100":[
            {"path":"/shooting/settings","get":true},
            {"path":"/shooting/settings/soundrecording/level","get":true,"put":true}
        ]}""".trimIndent()
        val malformedResponses = listOf(
            """{"value":false,"ability":{"min":0,"max":63,"step":1}}""",
            """{"value":32.0,"ability":{"min":0,"max":63,"step":1}}""",
            """{"value":32,"ability":{"min":0,"max":1000,"step":1}}""",
            """{"value":32,"ability":{"min":0,"max":63,"step":0}}""",
            """{"value":33,"ability":{"min":0,"max":63,"step":2}}""",
            """{"value":32,"ability":{"min":32,"max":32,"step":1}}""",
        )

        malformedResponses.forEachIndexed { index, response ->
            if (index > 0) {
                server.shutdown()
                server = MockWebServer().also { it.start() }
            }
            server.enqueue(jsonResponse(discovery))
            server.enqueue(jsonResponse("{}"))
            server.enqueue(jsonResponse(response))
            client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

            client.initialize()
            val capabilities = client.capabilities()

            assertFalse(capabilities.matrix.supports(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL))
            assertTrue(capabilities.matrix.isPlanned(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL))
            assertTrue(capabilities.advancedSettings.none { it.key == "soundrecordinglevel" })
        }
    }

    @Test
    fun canonSoundRecordingLevelDoesNotCombineGetPutAcrossVersions() = runTest {
        server.enqueue(
            jsonResponse(
                """{
                    "ver100":[
                        {"path":"/shooting/settings","get":true},
                        {"path":"/shooting/settings/soundrecording/level","get":true}
                    ],
                    "ver110":[{"path":"/shooting/settings/soundrecording/level","put":true}]
                }""".trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("{}"))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key == "soundrecordinglevel" })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun canonSoundRecordingControlsRequireMatchingPairAndRefreshBeforeStringWrite() = runTest {
        val discovery = """{"ver100":[
            {"path":"/devicestatus/batterylist","get":true},
            {"path":"/devicestatus/storage","get":true},
            {"path":"/shooting/settings","get":true},
            {"path":"/shooting/settings/soundrecording/windfilter","get":true,"put":true}
        ]}""".trimIndent()
        val advertised = """{"value":"auto","ability":["auto","enable","disable"]}"""
        server.enqueue(jsonResponse(discovery))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse(advertised))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"value":"auto","ability":["auto","disable"]}"""))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse(advertised))
        server.enqueue(jsonResponse("""{"value":"enable"}"""))
        server.enqueue(jsonResponse("""{"batterylist":[{"level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()
        val windFilter = capabilities.advancedSettings.single { it.key == "windfilter" }

        assertEquals("auto", windFilter.value)
        assertEquals(listOf("auto", "enable", "disable"), windFilter.values)
        assertTrue(capabilities.matrix.supports(CameraFeature.SOUND_RECORDING_CONTROL))
        assertTrue("windfilter" in capabilities.evidence.writableSettings)

        val invalid = runCatching { client.setSetting("windfilter", "enable") }.exceptionOrNull()
        assertTrue(invalid is IllegalStateException)
        assertTrue(invalid?.message.orEmpty().contains("not advertised"))

        client.setSetting("windfilter", "enable")

        val requests = generateSequence { server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .toList()
        val write = requests.single { it.method == "PUT" }
        assertEquals("/ccapi/ver100/shooting/settings/soundrecording/windfilter", write.path)
        assertEquals("enable", JSONObject(write.body.readUtf8()).getString("value"))
    }

    @Test
    fun canonCurrentSourceAudioControlsExposeR6MarkIIIAbilities() = runTest {
        val discovery = """{
            "ver100":[
                {"path":"/devicestatus/batterylist","get":true},
                {"path":"/devicestatus/storage","get":true},
                {"path":"/shooting/settings","get":true},
                {"path":"/shooting/settings/soundrecording/mode/intmic","get":true,"put":true},
                {"path":"/shooting/settings/soundrecording/level/intmic","get":true,"put":true},
                {"path":"/shooting/settings/soundrecording/windfilter/intmic","get":true,"put":true}
            ],
            "ver110":[
                {"path":"/shooting/settings/soundrecording","get":true,"put":true}
            ]
        }""".trimIndent()
        val soundRecording = """{"value":"enable","ability":["enable","disable"]}"""
        val mode = """{"value":"auto","ability":["auto","manual"]}"""
        val windFilter = """{"value":"enable","ability":["enable","disable"]}"""
        val level = """{"value":32,"ability":{"min":0,"max":63,"step":1}}"""
        fun enqueueSettings(modeResponse: String = mode) {
            server.enqueue(jsonResponse("{}"))
            listOf(soundRecording, modeResponse, windFilter, level).forEach { server.enqueue(jsonResponse(it)) }
        }
        server.enqueue(jsonResponse(discovery))
        enqueueSettings()
        enqueueSettings()
        server.enqueue(jsonResponse("""{"value":"manual"}"""))
        server.enqueue(jsonResponse("""{"batterylist":[{"level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        assertEquals(listOf("enable", "disable"), capabilities.advancedSettings.single { it.key == "soundrecording" }.values)
        assertEquals(listOf("auto", "manual"), capabilities.advancedSettings.single { it.key == "soundrecordingmodeintmic" }.values)
        assertEquals(listOf("enable", "disable"), capabilities.advancedSettings.single { it.key == "windfilterintmic" }.values)
        assertEquals((0..63).map(Int::toString), capabilities.advancedSettings.single { it.key == "soundrecordinglevelintmic" }.values)
        assertTrue(capabilities.matrix.supports(CameraFeature.SOUND_RECORDING_CONTROL))
        assertTrue(capabilities.matrix.supports(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL))

        client.setSetting("soundrecordingmodeintmic", "manual")

        val requests = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
        val write = requests.single { it.method == "PUT" }
        assertEquals("/ccapi/ver100/shooting/settings/soundrecording/mode/intmic", write.path)
        assertEquals("manual", JSONObject(write.body.readUtf8()).getString("value"))
    }

    @Test
    fun canonSoundRecordingControlsRejectMalformedStringAbilities() = runTest {
        val discovery = """{"ver100":[
            {"path":"/shooting/settings","get":true},
            {"path":"/shooting/settings/soundrecording/attenuator","get":true,"put":true}
        ]}""".trimIndent()
        val malformedResponses = listOf(
            """{"value":"on","ability":["enable","disable"]}""",
            """{"value":"auto","ability":["auto","auto"]}""",
            """{"value":"auto","ability":["auto"]}""",
            """{"value":"auto","ability":["auto",1]}""",
            """{"value":1,"ability":["auto","disable"]}""",
        )

        malformedResponses.forEachIndexed { index, response ->
            if (index > 0) {
                server.shutdown()
                server = MockWebServer().also { it.start() }
            }
            server.enqueue(jsonResponse(discovery))
            server.enqueue(jsonResponse("{}"))
            server.enqueue(jsonResponse(response))
            client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

            client.initialize()
            val capabilities = client.capabilities()

            assertFalse(capabilities.matrix.supports(CameraFeature.SOUND_RECORDING_CONTROL))
            assertTrue(capabilities.matrix.isPlanned(CameraFeature.SOUND_RECORDING_CONTROL))
            assertTrue(capabilities.advancedSettings.none { it.key == "attenuator" })
        }
    }

    @Test
    fun canonSoundRecordingControlsDoNotCombineGetPutAcrossVersions() = runTest {
        server.enqueue(
            jsonResponse(
                """{
                    "ver100":[
                        {"path":"/shooting/settings","get":true},
                        {"path":"/shooting/settings/soundrecording","get":true}
                    ],
                    "ver110":[{"path":"/shooting/settings/soundrecording","put":true}]
                }""".trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("{}"))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.SOUND_RECORDING_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key == "soundrecording" })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun canonSoundRecordingControlsDoNotUseAggregateSettingsAsEndpointGet() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/shooting/settings/soundrecording/windfilter","put":true}
                ]}""".trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse("""{"windfilter":{"value":"auto","ability":["auto","enable","disable"]}}"""),
        )
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.SOUND_RECORDING_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key == "windfilter" })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun canonFocusBracketingRequiresExactPairsAndWritesIntegerAfterRefresh() = runTest {
        val discovery = """{"ver100":[
            {"path":"/devicestatus/batterylist","get":true},
            {"path":"/devicestatus/storage","get":true},
            {"path":"/shooting/settings","get":true},
            {"path":"/shooting/settings/focusbracketing","get":true,"put":true},
            {"path":"/shooting/settings/focusbracketing/numberofshots","get":true,"put":true},
            {"path":"/shooting/settings/focusbracketing/focusincrement","get":true,"put":true},
            {"path":"/shooting/settings/focusbracketing/exposuresmoothing","get":true,"put":true}
        ]}""".trimIndent()
        val root = """{"value":"disable","ability":["enable","disable"]}"""
        val smoothing = """{"value":"disable","ability":["enable","disable"]}"""
        val shots = """{"value":100,"ability":{"min":2,"max":999,"step":1}}"""
        val increment = """{"value":4,"ability":{"min":1,"max":10,"step":1}}"""
        server.enqueue(jsonResponse(discovery))
        server.enqueue(jsonResponse("{}"))
        listOf(root, smoothing, shots, increment).forEach { server.enqueue(jsonResponse(it)) }
        server.enqueue(jsonResponse("{}"))
        listOf(root, smoothing, shots, increment).forEach { server.enqueue(jsonResponse(it)) }
        server.enqueue(jsonResponse("""{"value":250}"""))
        server.enqueue(jsonResponse("""{"batterylist":[{"level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse("{}"))
        listOf(root, smoothing, shots.replace("100", "250"), increment).forEach { server.enqueue(jsonResponse(it)) }
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        assertTrue(capabilities.matrix.supports(CameraFeature.FOCUS_BRACKETING_CONTROL))
        assertEquals("disable", capabilities.advancedSettings.single { it.key == "focusbracketing" }.value)
        assertEquals(
            (2..999).map(Int::toString),
            capabilities.advancedSettings.single { it.key == "focusbracketingnumberofshots" }.values,
        )
        assertEquals(
            (1..10).map(Int::toString),
            capabilities.advancedSettings.single { it.key == "focusbracketingfocusincrement" }.values,
        )
        assertTrue("focusbracketing" in capabilities.evidence.writableSettings)

        client.setSetting("focusbracketingnumberofshots", "250")

        val requests = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
        val write = requests.single { it.method == "PUT" }
        assertEquals("/ccapi/ver100/shooting/settings/focusbracketing/numberofshots", write.path)
        val payload = JSONObject(write.body.readUtf8())
        assertEquals(250, payload.getInt("value"))
        assertTrue(payload.get("value") is Int)
        assertTrue(requests.count { it.path == "/ccapi/ver100/shooting/settings/focusbracketing/numberofshots" } >= 3)
    }

    @Test
    fun canonFocusBracketingRejectsMalformedRootAndDoesNotProbeChildren() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/shooting/settings/focusbracketing","get":true,"put":true},
                    {"path":"/shooting/settings/focusbracketing/numberofshots","get":true,"put":true},
                    {"path":"/shooting/settings/focusbracketing/focusincrement","get":true,"put":true},
                    {"path":"/shooting/settings/focusbracketing/exposuresmoothing","get":true,"put":true}
                ]}""".trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse(
                """{"focusbracketing":{"value":"disable","ability":["enable","disable"]}}""",
            ),
        )
        server.enqueue(jsonResponse("""{"value":"disable","ability":["disable","disable"]}"""))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.FOCUS_BRACKETING_CONTROL))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.FOCUS_BRACKETING_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key.startsWith("focusbracketing") })
        assertEquals(3, server.requestCount)
    }

    @Test
    fun canonFocusBracketingDoesNotCombineGetPutAcrossVersions() = runTest {
        server.enqueue(
            jsonResponse(
                """{
                    "ver100":[
                        {"path":"/shooting/settings","get":true},
                        {"path":"/shooting/settings/focusbracketing","get":true}
                    ],
                    "ver110":[{"path":"/shooting/settings/focusbracketing","put":true}]
                }""".trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse(
                """{"focusbracketing":{"value":"disable","ability":["enable","disable"]}}""",
            ),
        )
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.FOCUS_BRACKETING_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key.startsWith("focusbracketing") })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun canonMovieSettingsRequireExactPairsAndWriteStringAfterRefresh() = runTest {
        val discovery = """{
            "ver100":[
                {"path":"/devicestatus/batterylist","get":true},
                {"path":"/devicestatus/storage","get":true},
                {"path":"/shooting/settings","get":true},
                {"path":"/shooting/settings/moviequality","get":true,"put":true}
            ],
            "ver110":[
                {"path":"/shooting/settings/highframerate","get":true,"put":true},
                {"path":"/shooting/settings/moviecropping","get":true,"put":true},
                {"path":"/shooting/settings/movieformat","get":true,"put":true}
            ]
        }""".trimIndent()
        val quality = """{"value":"3840x2160_5994_ipb_standard","ability":["3840x2160_5994_ipb_standard","1920x1080_2997_ipb_standard"]}"""
        val toggle = """{"value":"disable","ability":["enable","disable"]}"""
        val format = """{"value":"xfavcs-ycc420-8bit","ability":["raw","xfhevcs-ycc422-10bit","xfhevcs-ycc420-10bit","xfavcs-ycc422-10bit","xfavcs-ycc420-8bit"]}"""
        fun enqueueSettings(movieFormat: String = format) {
            server.enqueue(jsonResponse("{}"))
            listOf(quality, toggle, toggle, movieFormat).forEach { server.enqueue(jsonResponse(it)) }
        }
        server.enqueue(jsonResponse(discovery))
        enqueueSettings()
        enqueueSettings()
        server.enqueue(jsonResponse("""{"value":"xfhevcs-ycc422-10bit"}"""))
        server.enqueue(jsonResponse("""{"batterylist":[{"level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        enqueueSettings(format.replace("\"value\":\"xfavcs-ycc420-8bit\"", "\"value\":\"xfhevcs-ycc422-10bit\""))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        assertTrue(capabilities.matrix.supports(CameraFeature.MOVIE_SETTINGS_CONTROL))
        assertEquals(
            listOf("3840x2160_5994_ipb_standard", "1920x1080_2997_ipb_standard"),
            capabilities.advancedSettings.single { it.key == "moviequality" }.values,
        )
        assertEquals(listOf("enable", "disable"), capabilities.advancedSettings.single { it.key == "highframerate" }.values)
        assertEquals("disable", capabilities.advancedSettings.single { it.key == "moviecropping" }.value)
        assertEquals(
            listOf("raw", "xfhevcs-ycc422-10bit", "xfhevcs-ycc420-10bit", "xfavcs-ycc422-10bit", "xfavcs-ycc420-8bit"),
            capabilities.advancedSettings.single { it.key == "movieformat" }.values,
        )
        assertTrue(setOf("moviequality", "highframerate", "moviecropping", "movieformat").all {
            it in capabilities.evidence.writableSettings
        })

        client.setSetting("movieformat", "xfhevcs-ycc422-10bit")

        val requests = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
        val write = requests.single { it.method == "PUT" }
        assertEquals("/ccapi/ver110/shooting/settings/movieformat", write.path)
        assertEquals("xfhevcs-ycc422-10bit", JSONObject(write.body.readUtf8()).getString("value"))
        assertTrue(requests.count { it.path == "/ccapi/ver110/shooting/settings/movieformat" } >= 3)
    }

    @Test
    fun canonMovieSettingsDoNotCombineVersionsOrTrustAggregateOnly() = runTest {
        server.enqueue(
            jsonResponse(
                """{
                    "ver100":[
                        {"path":"/shooting/settings","get":true},
                        {"path":"/shooting/settings/moviequality","get":true}
                    ],
                    "ver110":[{"path":"/shooting/settings/moviequality","put":true}]
                }""".trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse(
                """{"moviequality":{"value":"3840x2160_5994_ipb_standard","ability":["3840x2160_5994_ipb_standard","1920x1080_2997_ipb_standard"]}}""",
            ),
        )
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.MOVIE_SETTINGS_CONTROL))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.MOVIE_SETTINGS_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key == "moviequality" })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun canonMovieModeRequiresMatchingGetPostAndWritesAction() = runTest {
        val discovery = """{"ver100":[
            {"path":"/devicestatus/batterylist","get":true},
            {"path":"/devicestatus/storage","get":true},
            {"path":"/shooting/settings","get":true},
            {"path":"/shooting/control/moviemode","get":true,"post":true}
        ]}""".trimIndent()
        server.enqueue(jsonResponse(discovery))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"status":"off"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse("""{"batterylist":[{"level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"status":"on"}"""))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()
        val movieMode = capabilities.advancedSettings.single { it.key == "moviemode" }
        assertEquals(listOf("off", "on"), movieMode.values)
        assertEquals("off", movieMode.value)
        assertTrue(capabilities.matrix.supports(CameraFeature.MOVIE_MODE_CONTROL))

        client.setSetting("moviemode", "on")

        repeat(3) { server.takeRequest() }
        val write = server.takeRequest()
        assertEquals("POST", write.method)
        assertEquals("/ccapi/ver100/shooting/control/moviemode", write.path)
        assertEquals("on", JSONObject(write.body.readUtf8()).getString("action"))
    }

    @Test
    fun canonMovieModeIsHiddenWithoutMatchingPostOrValidStatus() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/shooting/control/moviemode","get":true}
                ]}""".trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("{}"))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        var capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.MOVIE_MODE_CONTROL))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.MOVIE_MODE_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key == "moviemode" })
        assertEquals(2, server.requestCount)

        server.shutdown()
        server = MockWebServer().also { it.start() }
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/shooting/control/moviemode","get":true,"post":true}
                ]}""".trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"status":"recording"}"""))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.MOVIE_MODE_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key == "moviemode" })
    }

    @Test
    fun canonCardSelectionRequiresMatchingGetPutAndWritesOnlyAdvertisedValue() = runTest {
        val discovery = """{"ver100":[
            {"path":"/shooting/settings","get":true},
            {"path":"/functions/cardselection/stillimage","get":true,"put":true},
            {"path":"/functions/cardselection/movie","get":true,"put":true}
        ]}""".trimIndent()
        server.enqueue(jsonResponse(discovery))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"value":"card1","ability":["none","card1","card2"]}"""))
        server.enqueue(jsonResponse("""{"value":"card2","ability":["card1","card2"]}"""))
        repeat(2) {
            server.enqueue(jsonResponse("{}"))
            server.enqueue(jsonResponse("""{"value":"card1","ability":["none","card1","card2"]}"""))
            server.enqueue(jsonResponse("""{"value":"card2","ability":["card1","card2"]}"""))
        }
        server.enqueue(jsonResponse("""{"value":"card2"}"""))
        server.enqueue(jsonResponse("""{"batterylist":[{"level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"value":"card2","ability":["none","card1","card2"]}"""))
        server.enqueue(jsonResponse("""{"value":"card2","ability":["card1","card2"]}"""))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        val still = capabilities.advancedSettings.single { it.key == "cardselectionstillimage" }
        val movie = capabilities.advancedSettings.single { it.key == "cardselectionmovie" }
        assertEquals("card1", still.value)
        assertEquals(listOf("none", "card1", "card2"), still.values)
        assertEquals("card2", movie.value)
        assertTrue(capabilities.matrix.supports(CameraFeature.CARD_SELECTION_CONTROL))
        assertTrue("cardselectionstillimage" in capabilities.evidence.writableSettings)
        val requestCount = server.requestCount
        val invalid = runCatching {
            client.setSetting("cardselectionstillimage", "card3")
        }.exceptionOrNull()
        assertTrue(invalid is IllegalStateException)
        assertEquals(requestCount + 3, server.requestCount)

        client.setSetting("cardselectionstillimage", "card2")

        repeat(10) { server.takeRequest() }
        val write = server.takeRequest()
        assertEquals("PUT", write.method)
        assertEquals("/ccapi/ver100/functions/cardselection/stillimage", write.path)
        assertEquals("card2", JSONObject(write.body.readUtf8()).getString("value"))
    }

    @Test
    fun canonCardSelectionRejectsMalformedAbilityAndCrossVersionPairing() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/functions/cardselection/stillimage","get":true,"put":true}
                ]}""".trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"value":"card1","ability":["card1","card1"]}"""))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        var capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.CARD_SELECTION_CONTROL))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.CARD_SELECTION_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key.startsWith("cardselection") })

        server.shutdown()
        server = MockWebServer().also { it.start() }
        server.enqueue(
            jsonResponse(
                """{
                    "ver100":[
                        {"path":"/shooting/settings","get":true},
                        {"path":"/functions/cardselection/stillimage","get":true}
                    ],
                    "ver110":[{"path":"/functions/cardselection/stillimage","put":true}]
                }""".trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("{}"))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.CARD_SELECTION_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key.startsWith("cardselection") })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun canonDirectoryControlRequiresCompleteGroupAndCreatesAdvertisedDirectory() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/functions/directory/createdirectory","post":true},
                    {"path":"/functions/directory/directoryselection","get":true,"put":true}
                ]}""".trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("{}"))
        server.enqueue(
            jsonResponse(
                """{"value":"100EOSXX","ability":["100EOSXX"]}""",
            ),
        )
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        val selection = capabilities.advancedSettings.single { it.key == "directoryselection" }
        assertEquals("100EOSXX", selection.value)
        assertEquals(listOf("100EOSXX"), selection.values)
        assertTrue(capabilities.matrix.supports(CameraFeature.DIRECTORY_CONTROL))
        assertTrue("directoryselection" in capabilities.evidence.writableSettings)

        val requestCount = server.requestCount
        val invalid = runCatching { client.createDirectory("bad") }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
        assertEquals(requestCount, server.requestCount)

        server.enqueue(jsonResponse("""{"directoryname":"ABCDE"}"""))
        server.enqueue(jsonResponse("{}"))
        server.enqueue(
            jsonResponse(
                """{"value":"101ABCDE","ability":["100EOSXX","101ABCDE"]}""",
            ),
        )

        assertEquals("ABCDE", client.createDirectory("ABCDE"))

        repeat(3) { server.takeRequest() }
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/ccapi/ver100/functions/directory/createdirectory", create.path)
        assertEquals("ABCDE", JSONObject(create.body.readUtf8()).getString("directoryname"))
    }

    @Test
    fun canonDirectoryControlRejectsMalformedAndCrossVersionContracts() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ver100":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/functions/directory/createdirectory","post":true},
                    {"path":"/functions/directory/directoryselection","get":true,"put":true}
                ]}""".trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("{}"))
        server.enqueue(
            jsonResponse(
                """{"value":"100EOSXX","ability":["100EOSXX","100EOSXX"]}""",
            ),
        )
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        var capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.DIRECTORY_CONTROL))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.DIRECTORY_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key == "directoryselection" })

        server.shutdown()
        server = MockWebServer().also { it.start() }
        server.enqueue(
            jsonResponse(
                """{
                    "ver100":[
                        {"path":"/shooting/settings","get":true},
                        {"path":"/functions/directory/createdirectory","post":true},
                        {"path":"/functions/directory/directoryselection","get":true}
                    ],
                    "ver110":[{"path":"/functions/directory/directoryselection","put":true}]
                }""".trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("{}"))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.DIRECTORY_CONTROL))
        assertTrue(capabilities.advancedSettings.none { it.key == "directoryselection" })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun canonFileNamingRequiresCompleteGroupAndVerifiesStringAndIntegerUpdates() = runTest {
        server.enqueue(jsonResponse(CANON_FILE_NAMING_DISCOVERY_JSON))
        server.enqueue(jsonResponse("{}"))
        enqueueCanonFileNaming()
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        val capabilities = client.capabilities()

        val fileNaming = requireNotNull(capabilities.fileNaming)
        assertTrue(capabilities.matrix.supports(CameraFeature.FILE_NAMING_CONTROL))
        assertEquals(listOf("preset_code", "usersetting1", "usersetting2"), fileNaming.stillFilenameModeOptions)
        assertEquals(1, fileNaming.movieReelRange.minimum)
        assertEquals(9999, fileNaming.movieReelRange.maximum)
        assertTrue("movie-reel-number" in capabilities.evidence.writableSettings)

        val requestCount = server.requestCount
        val invalid = runCatching {
            client.setFileNaming(CameraFileNamingField.STILL_USER_SETTING_1, "_BAD")
        }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
        assertEquals(requestCount, server.requestCount)

        server.enqueue(jsonResponse("""{"usersetting1":"EOS_"}"""))
        enqueueCanonFileNaming(stillUserSetting1 = "EOS_")
        val updatedString = client.setFileNaming(CameraFileNamingField.STILL_USER_SETTING_1, "EOS_")
        assertEquals("EOS_", updatedString.stillUserSetting1)

        repeat(9) { server.takeRequest() }
        val stringWrite = server.takeRequest()
        assertEquals("PUT", stringWrite.method)
        assertEquals("/ccapi/ver100/functions/filename/stills/usersetting1", stringWrite.path)
        assertEquals("EOS_", JSONObject(stringWrite.body.readUtf8()).getString("usersetting1"))

        server.enqueue(jsonResponse("""{"value":42}"""))
        enqueueCanonFileNaming(stillUserSetting1 = "EOS_", movieReelNumber = 42)
        val updatedInteger = client.setFileNaming(CameraFileNamingField.MOVIE_REEL_NUMBER, "42")
        assertEquals(42, updatedInteger.movieReelNumber)

        repeat(7) { server.takeRequest() }
        val integerWrite = server.takeRequest()
        assertEquals("PUT", integerWrite.method)
        assertEquals("/ccapi/ver100/functions/filename/movies/reelnum", integerWrite.path)
        assertEquals(42, JSONObject(integerWrite.body.readUtf8()).getInt("value"))
    }

    @Test
    fun canonFileNamingRejectsIncompleteMalformedAndCrossVersionContracts() = runTest {
        val incompleteDiscovery = CANON_FILE_NAMING_DISCOVERY_JSON.replace(
            "{\"path\":\"/functions/filename/movies/userdefined\",\"get\":true,\"put\":true}",
            "{\"path\":\"/functions/filename/movies/userdefined\",\"get\":true}",
        )
        server.enqueue(jsonResponse(incompleteDiscovery))
        server.enqueue(jsonResponse("{}"))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        var capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.FILE_NAMING_CONTROL))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.FILE_NAMING_CONTROL))
        assertNull(capabilities.fileNaming)
        assertEquals(2, server.requestCount)

        server.shutdown()
        server = MockWebServer().also { it.start() }
        server.enqueue(jsonResponse(CANON_FILE_NAMING_DISCOVERY_JSON))
        server.enqueue(jsonResponse("{}"))
        enqueueCanonFileNaming(stillUserSetting1 = "_BAD")
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.FILE_NAMING_CONTROL))
        assertNull(capabilities.fileNaming)

        server.shutdown()
        server = MockWebServer().also { it.start() }
        server.enqueue(
            jsonResponse(
                CANON_FILE_NAMING_DISCOVERY_JSON.replaceFirst(
                    "{\"path\":\"/functions/filename/stills/filename\",\"get\":true,\"put\":true}",
                    "{\"path\":\"/functions/filename/stills/filename\",\"get\":true}",
                ).replace(
                    "]}",
                    "],\"ver110\":[{\"path\":\"/functions/filename/stills/filename\",\"put\":true}]}",
                ),
            ),
        )
        server.enqueue(jsonResponse("{}"))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)

        client.initialize()
        capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.FILE_NAMING_CONTROL))
        assertNull(capabilities.fileNaming)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun realStillImageQualityUsesAdvertisedObjectFieldsAndPreservesCompanionFormat() = runTest {
        client.forceRealCamera(prefixes = listOf("/ccapi/ver110", "/ccapi/ver100"))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        server.enqueue(jsonResponse("""{}"""))

        val capabilities = client.capabilities()

        assertEquals(
            listOf("none", "raw", "craw"),
            capabilities.advancedSettings.first { it.key == "stillimagequality.raw" }.values,
        )
        assertEquals(
            "large_fine",
            capabilities.advancedSettings.first { it.key == "stillimagequality.jpeg" }.value,
        )
        val requestCount = server.requestCount
        val invalid = runCatching { client.setSetting("stillimagequality.jpeg", "none") }.exceptionOrNull()
        assertTrue(invalid is IllegalStateException)
        assertTrue(invalid?.message.orEmpty().contains("at least one", ignoreCase = true))
        assertEquals(requestCount, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89,"quality":"good"}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON.replace("\"raw\": \"none\"", "\"raw\": \"raw\"")))
        server.enqueue(jsonResponse("""{}"""))

        client.setSetting("stillimagequality.raw", "raw")

        assertEquals("/ccapi/ver110/shooting/settings", server.takeRequest().path)
        assertEquals("/ccapi/ver100/shooting/settings", server.takeRequest().path)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/ccapi/ver110/shooting/settings/stillimagequality", put.path)
        val value = JSONObject(put.body.readUtf8()).getJSONObject("value")
        assertEquals("raw", value.getString("raw"))
        assertEquals("large_fine", value.getString("jpeg"))
    }

    @Test
    fun realWhiteBalanceShiftUsesAdvertisedRangeAndWritesIntegerObject() = runTest {
        client.forceRealCamera(prefixes = listOf("/ccapi/ver110", "/ccapi/ver100"))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
        server.enqueue(jsonResponse("""{}"""))

        val capabilities = client.capabilities()
        val ba = capabilities.advancedSettings.first { it.key == "wbshift.ba" }
        assertEquals((-9..9).map(Int::toString), ba.values)
        assertEquals("0", ba.value)
        val requestCount = server.requestCount
        val invalid = runCatching { client.setSetting("wbshift.ba", "10") }.exceptionOrNull()
        assertTrue(invalid is IllegalStateException)
        assertTrue(invalid?.message.orEmpty().contains("not advertised"))
        assertEquals(requestCount, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89,"quality":"good"}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON.replace("\"ba\": 0", "\"ba\": 9")))
        server.enqueue(jsonResponse("""{}"""))

        client.setSetting("wbshift.ba", "9")

        assertEquals("/ccapi/ver110/shooting/settings", server.takeRequest().path)
        assertEquals("/ccapi/ver100/shooting/settings", server.takeRequest().path)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/ccapi/ver110/shooting/settings/wbshift", put.path)
        val value = JSONObject(put.body.readUtf8()).getJSONObject("value")
        assertEquals(9, value.getInt("ba"))
        assertEquals(0, value.getInt("mg"))
    }

    @Test
    fun realWhiteBalanceShiftHidesMalformedOrUnboundedRanges() = runTest {
        client.forceRealCamera()
        server.enqueue(
            jsonResponse(
                """
                {
                  "wbshift": {
                    "value": {"ba": 0, "mg": 0},
                    "ability": {
                      "ba": {"min": -1000, "max": 1000, "step": 1},
                      "mg": {"min": -9, "max": 9, "step": 0}
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val capabilities = client.capabilities()

        assertTrue(capabilities.advancedSettings.none { it.key.startsWith("wbshift.") })
    }

    @Test
    fun realWhiteBalanceShiftRequiresCompleteIntegerCurrentValue() = runTest {
        client.forceRealCamera()
        server.enqueue(
            jsonResponse(
                """
                {
                  "wbshift": {
                    "value": {"ba": 0.5},
                    "ability": {
                      "ba": {"min": -9, "max": 9, "step": 1},
                      "mg": {"min": -9, "max": 9, "step": 1}
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val capabilities = client.capabilities()

        assertTrue(capabilities.advancedSettings.none { it.key.startsWith("wbshift.") })
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

        assertTrue(CameraFeature.STILL_CAPTURE !in client.observedFeatureSnapshot())
        client.captureStill()
        val request = server.takeRequest()

        assertEquals("/ccapi/ver110/shooting/control/shutterbutton", request.path)
        assertEquals("POST", request.method)
        assertTrue(JSONObject(request.body.readUtf8()).getBoolean("af"))
        assertTrue(CameraFeature.STILL_CAPTURE in client.observedFeatureSnapshot())
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
    fun bulbExposureUsesAdvertisedManualPressAndReleaseWithoutStatusPollingWhilePressed() = runTest {
        fun takeRequest() = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver110":[{"path":"/shooting/control/shutterbutton/manual","put":true}]}""",
            ),
        )
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))

        client.initialize()
        val started = client.startBulbExposure()

        assertTrue(started.bulbExposureActive == true)
        assertTrue(CameraFeature.BULB_EXPOSURE !in client.observedFeatureSnapshot())
        assertEquals("/ccapi", takeRequest().path)
        repeat(2) { takeRequest() }
        val press = takeRequest()
        val pressBody = JSONObject(press.body.readUtf8())
        assertEquals("PUT", press.method)
        assertEquals("full_press", pressBody.getString("action"))
        assertFalse(pressBody.getBoolean("af"))
        assertEquals(4, server.requestCount)

        val stopped = client.stopBulbExposure()
        val release = takeRequest()
        val releaseBody = JSONObject(release.body.readUtf8())
        assertEquals("release", releaseBody.getString("action"))
        assertFalse(releaseBody.getBoolean("af"))
        repeat(2) { takeRequest() }
        assertTrue(stopped.bulbExposureActive == false)
        assertTrue(CameraFeature.BULB_EXPOSURE in client.observedFeatureSnapshot())
    }

    @Test
    fun failedBulbPressStillAttemptsShutterRelease() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver110":[{"path":"/shooting/control/shutterbutton/manual","put":true}]}""",
            ),
        )
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("press response lost"))
        server.enqueue(MockResponse().setResponseCode(204))

        client.initialize()
        val failure = runCatching { client.startBulbExposure() }.exceptionOrNull()

        server.takeRequest()
        repeat(2) { server.takeRequest() }
        val press = server.takeRequest()
        val release = server.takeRequest()
        assertTrue(failure is IllegalStateException)
        assertEquals("full_press", JSONObject(press.body.readUtf8()).getString("action"))
        assertEquals("release", JSONObject(release.body.readUtf8()).getString("action"))
        assertTrue(CameraFeature.BULB_EXPOSURE !in client.observedFeatureSnapshot())
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
    fun autofocusUsesAdvertisedCanonStartAndStopActions() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver110":[{"path":"/shooting/control/af","post":true}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        enqueueRealStatus()

        client.initialize()
        client.autofocus()

        server.takeRequest()
        val start = server.takeRequest()
        val stop = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("/ccapi/ver110/shooting/control/af", start.path)
        assertEquals("start", JSONObject(start.body.readUtf8()).getString("action"))
        assertEquals("POST", stop.method)
        assertEquals("stop", JSONObject(stop.body.readUtf8()).getString("action"))
    }

    @Test
    fun failedAutofocusStartStillSendsStop() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse("""{"ver110":[{"path":"/shooting/control/af","post":true}]}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("focus failed"))
        server.enqueue(MockResponse().setResponseCode(204))

        client.initialize()
        val failure = runCatching { client.autofocus() }.exceptionOrNull()

        server.takeRequest()
        val start = server.takeRequest()
        val stop = server.takeRequest()
        assertTrue(failure is IllegalStateException)
        assertEquals("start", JSONObject(start.body.readUtf8()).getString("action"))
        assertEquals("stop", JSONObject(stop.body.readUtf8()).getString("action"))
    }

    @Test
    fun unadvertisedAutofocusFailsWithoutSendingACommand() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse("""{"ver110":[{"path":"/deviceinformation","get":true}]}"""))

        client.initialize()
        val failure = runCatching { client.autofocus() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, server.requestCount)
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
        server.enqueue(
            jsonResponse(
                """{"path":["/ccapi/ver110/contents/card1/100CANON/IMG_0002.JPG","/ccapi/ver110/contents/card1/100CANON/IMG_0003.PNG"]}""",
            ),
        )
        server.enqueue(
            jsonResponse(
                """{"path":["/ccapi/ver110/contents/card1/100CANON/IMG_0001.CR3","/ccapi/ver110/contents/card1/100CANON/IMG_0004.CR2"]}""",
            ),
        )

        val items = client.listMedia()

        assertEquals(
            listOf("IMG_0002.JPG", "IMG_0003.PNG", "IMG_0001.CR3", "IMG_0004.CR2"),
            items.map { it.name },
        )
        assertEquals(listOf("image", "image", "raw", "raw"), items.map { it.kind })
        assertEquals(listOf(true, false, true, false), items.map { it.previewAvailable })
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
    fun realMediaListStopsRetryingUnsupportedDescendingOrder() = runTest {
        client.forceRealCamera(prefix = "/ccapi/ver140")
        server.enqueue(jsonResponse("""{"pagenumber":2}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"message":"Illegal query parameter"}"""),
        )
        server.enqueue(
            jsonResponse(
                """{"path":["/ccapi/ver140/contents/card2/DCIM/100EOSR6/IMG_0001.JPG","/ccapi/ver140/contents/card2/DCIM/100EOSR6/IMG_0002.JPG"]}""",
            ),
        )
        server.enqueue(
            jsonResponse(
                """{"path":["/ccapi/ver140/contents/card2/DCIM/100EOSR6/IMG_0003.JPG","/ccapi/ver140/contents/card2/DCIM/100EOSR6/IMG_0004.JPG"]}""",
            ),
        )

        val items = client.listMedia()

        assertEquals(
            listOf("IMG_0004.JPG", "IMG_0003.JPG", "IMG_0002.JPG", "IMG_0001.JPG"),
            items.map { it.name },
        )
        assertEquals("/ccapi/ver140/contents?kind=number", server.takeRequest().path)
        assertEquals("/ccapi/ver140/contents?page=1&order=desc", server.takeRequest().path)
        assertEquals("/ccapi/ver140/contents?page=1", server.takeRequest().path)
        assertEquals("/ccapi/ver140/contents?page=2", server.takeRequest().path)
    }

    @Test
    fun realMediaListStopsPagingAtResultLimit() = runTest {
        client.forceRealCamera(prefix = "/ccapi/ver140")
        server.enqueue(jsonResponse("""{"pagenumber":47}"""))
        repeat(5) { pageIndex ->
            val paths = (1..100).joinToString(separator = ",") { itemIndex ->
                val number = pageIndex * 100 + itemIndex
                "\"/ccapi/ver140/contents/card2/DCIM/100EOSR6/IMG_${number.toString().padStart(4, '0')}.JPG\""
            }
            server.enqueue(jsonResponse("""{"path":[$paths]}"""))
        }

        val items = client.listMedia()

        assertEquals(500, items.size)
        assertEquals("IMG_0001.JPG", items.first().name)
        assertEquals("IMG_0500.JPG", items.last().name)
        assertEquals(6, server.requestCount)
        assertEquals("/ccapi/ver140/contents?kind=number", server.takeRequest().path)
        assertEquals("/ccapi/ver140/contents?page=1&order=desc", server.takeRequest().path)
        repeat(4) { pageIndex ->
            assertEquals(
                "/ccapi/ver140/contents?page=${pageIndex + 2}&order=desc",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun realMediaListFairlyMergesSiblingPhotoAndVideoContainers() = runTest {
        client.forceRealCamera(prefix = "/ccapi/ver140")
        val containers =
            """{"path":["/ccapi/ver140/contents/card2/DCIM/100EOSR6","/ccapi/ver140/contents/card2/XFVC/REEL_0001"]}"""
        server.enqueue(jsonResponse(containers))
        server.enqueue(jsonResponse(containers))
        server.enqueue(jsonResponse("""{"pagenumber":1}"""))
        server.enqueue(
            jsonResponse(
                """{"path":["/ccapi/ver140/contents/card2/DCIM/100EOSR6/IMG_0002.JPG","/ccapi/ver140/contents/card2/DCIM/100EOSR6/IMG_0001.JPG"]}""",
            ),
        )
        server.enqueue(jsonResponse("""{"pagenumber":1}"""))
        server.enqueue(
            jsonResponse(
                """{"path":["/ccapi/ver140/contents/card2/XFVC/REEL_0001/VIDEO_0002.MP4","/ccapi/ver140/contents/card2/XFVC/REEL_0001/VIDEO_0001.MP4"]}""",
            ),
        )

        val items = client.listMedia()

        assertEquals(
            listOf("IMG_0002.JPG", "VIDEO_0002.MP4", "IMG_0001.JPG", "VIDEO_0001.MP4"),
            items.map { it.name },
        )
        assertEquals(listOf("image", "video", "image", "video"), items.map { it.kind })
        assertEquals(6, server.requestCount)
    }

    @Test
    fun realLiveViewMagnificationUsesAdvertisedStringValueAndReadback() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver140":[
                    {"path":"/shooting/liveview","post":true,"delete":true},
                    {"path":"/shooting/liveview/flip","get":true},
                    {"path":"/shooting/settings","get":true},
                    {"path":"/shooting/settings/lvzoom","get":true,"put":true}
                ]}""",
            ),
        )
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"value":"5","ability":["1","5","10"]}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(jsonResponse("""{"value":"10","ability":["1","5","10"]}"""))

        client.initialize()
        val capabilities = client.capabilities()
        client.startLiveView()
        val result = client.setLiveViewMagnification(LiveViewMagnification.X10)

        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_MAGNIFICATION))
        assertEquals(
            listOf(LiveViewMagnification.X1, LiveViewMagnification.X5, LiveViewMagnification.X10),
            capabilities.liveView.magnifications,
        )
        assertEquals(LiveViewMagnification.X5, capabilities.liveView.currentMagnification)
        assertEquals(LiveViewMagnification.X10, result.magnification)
        assertEquals("/ccapi", server.takeRequest().path)
        assertEquals("/ccapi/ver140/shooting/settings", server.takeRequest().path)
        assertEquals("/ccapi/ver140/shooting/settings/lvzoom", server.takeRequest().path)
        assertEquals("/ccapi/ver140/shooting/liveview", server.takeRequest().path)
        val write = server.takeRequest()
        assertEquals("PUT", write.method)
        assertEquals("/ccapi/ver140/shooting/settings/lvzoom", write.path)
        val requestedValue = JSONObject(write.body.readUtf8()).get("value")
        assertTrue(requestedValue is String)
        assertEquals("10", requestedValue)
        assertEquals("/ccapi/ver140/shooting/settings/lvzoom", server.takeRequest().path)
    }

    @Test
    fun realLiveViewMagnificationRejectsInvalidNumericAbilityPayload() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{"ver140":[
                    {"path":"/shooting/settings","get":true},
                    {"path":"/shooting/settings/lvzoom","get":true,"put":true}
                ]}""",
            ),
        )
        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"value":1,"ability":[1,5,10]}"""))

        client.initialize()
        val capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_MAGNIFICATION))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.LIVE_VIEW_MAGNIFICATION))
        assertTrue(capabilities.liveView.magnifications.isEmpty())
        assertNull(capabilities.liveView.currentMagnification)
    }

    @Test
    fun realLiveViewMagnificationRequiresSameVersionGetAndPut() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(
            jsonResponse(
                """{
                    "ver140":[
                        {"path":"/shooting/settings","get":true},
                        {"path":"/shooting/settings/lvzoom","get":true}
                    ],
                    "ver130":[{"path":"/shooting/settings/lvzoom","put":true}]
                }""",
            ),
        )
        server.enqueue(jsonResponse("{}"))

        client.initialize()
        val capabilities = client.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_MAGNIFICATION))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun simulatorMediaCanBeListedAndDownloaded() = runTest {
        server.enqueue(
            jsonResponse(
                """{"items":[{"id":"SIM_0001.PNG","name":"SIM_0001.PNG","kind":"image","size_bytes":6,"archive":true}]}""",
            ),
        )
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        server.enqueue(binaryResponse(bytes, "image/png"))
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<CameraMediaTransferProgress>()

        val item = client.listMedia().single()
        assertTrue(item.previewAvailable)
        assertEquals(true, item.archived)
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
    fun simulatorMediaUploadStreamsExactBytesAndRealCcapiRefusesUnverifiedUpload() = runTest {
        val bytes = ByteArray(96 * 1024 + 7) { (it % 239).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("content-type", "application/json")
                .setBody(
                    """{"id":"PHONE 0001.JPG","name":"PHONE 0001.JPG","kind":"image","size_bytes":${bytes.size}}"""
                )
        )
        val progress = mutableListOf<CameraMediaTransferProgress>()

        val result = client.uploadMedia(
            name = "PHONE 0001.JPG",
            sizeBytes = bytes.size.toLong(),
            contentType = "image/jpeg",
            source = ByteArrayInputStream(bytes),
            onProgress = progress::add,
        )
        val upload = server.takeRequest()
        client.forceRealCamera(prefix = "/ccapi/ver110")
        val realFailure = runCatching {
            client.uploadMedia(
                name = "PHONE_0002.JPG",
                sizeBytes = bytes.size.toLong(),
                contentType = "image/jpeg",
                source = ByteArrayInputStream(bytes),
            )
        }.exceptionOrNull()

        assertEquals("POST", upload.method)
        assertEquals("/ccapi/media?filename=PHONE%200001.JPG", upload.path)
        assertArrayEquals(bytes, upload.body.readByteArray())
        assertEquals(bytes.size.toLong(), result.bytesTransferred)
        assertEquals(bytes.size.toLong(), progress.last().bytesTransferred)
        assertTrue(realFailure is UnsupportedOperationException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun mediaThumbnailUsesCanonKindQueryAndReturnsBoundedImage() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse("""{"ver110":[{"path":"/contents","get":true}]}"""))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte())
        server.enqueue(binaryResponse(jpeg, "application/octet-stream"))
        client.initialize()
        server.takeRequest()
        val item = CameraMediaItem(
            "/ccapi/ver110/contents/card1/100CANON/IMG_0001.JPG?kind=main",
            "IMG_0001.JPG",
            "image",
        )

        val thumbnail = client.mediaThumbnail(item)

        assertEquals("/ccapi/ver110/contents/card1/100CANON/IMG_0001.JPG?kind=thumbnail", server.takeRequest().path)
        assertArrayEquals(jpeg, thumbnail.bytes)
        assertEquals("image/jpeg", thumbnail.contentType)
        assertTrue(CameraFeature.MEDIA_THUMBNAIL in client.observedFeatureSnapshot())
    }

    @Test
    fun mediaThumbnailRejectsOversizedOrNonImageResponses() = runTest {
        val item = CameraMediaItem("SIM_0001.PNG", "SIM_0001.PNG", "image")
        server.enqueue(binaryResponse(ByteArray(8 * 1024 * 1024 + 1), "image/png"))

        val oversized = runCatching { client.mediaThumbnail(item) }.exceptionOrNull()

        assertTrue(oversized is IllegalStateException)
        assertTrue(oversized?.message.orEmpty().contains("exceeded"))

        server.enqueue(jsonResponse("""{"message":"not a thumbnail"}"""))
        val text = runCatching { client.mediaThumbnail(item) }.exceptionOrNull()

        assertTrue(text is IllegalStateException)
        assertTrue(text?.message.orEmpty().contains("text instead of an image"))
    }

    @Test
    fun mediaThumbnailRejectsCrossOriginAndTraversalPathsBeforeFetching() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse("""{"ver110":[{"path":"/contents","get":true}]}"""))
        client.initialize()
        server.takeRequest()
        val outside = CameraMediaItem(
            "http://attacker.invalid/ccapi/ver110/contents/IMG_0001.JPG",
            "IMG_0001.JPG",
            "image",
        )
        val traversal = CameraMediaItem(
            "/ccapi/ver110/contents/%2e%2e/deviceinformation",
            "deviceinformation",
            "other",
        )

        val outsideFailure = runCatching { client.mediaThumbnail(outside) }.exceptionOrNull()
        val traversalFailure = runCatching { client.mediaThumbnail(traversal) }.exceptionOrNull()

        assertTrue(outsideFailure is IllegalArgumentException)
        assertTrue(traversalFailure is IllegalArgumentException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun mediaPreviewUsesCanonDisplayQueryAndRejectsVideoBeforeFetching() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse("""{"ver110":[{"path":"/contents","get":true}]}"""))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x02, 0xFF.toByte(), 0xD9.toByte())
        server.enqueue(binaryResponse(jpeg, "application/octet-stream"))
        client.initialize()
        server.takeRequest()
        val path = "/ccapi/ver110/contents/card1/100CANON/IMG_0001.JPG"

        val preview = client.mediaPreview(CameraMediaItem("$path?kind=main", "IMG_0001.JPG", "raw"))
        val videoFailure = runCatching {
            client.mediaPreview(CameraMediaItem("$path/MVI_0001.MP4", "MVI_0001.MP4", "video"))
        }.exceptionOrNull()
        val pngFailure = runCatching {
            client.mediaPreview(CameraMediaItem("${path.substringBeforeLast('/')}/IMG_0002.PNG", "IMG_0002.PNG", "image"))
        }.exceptionOrNull()

        assertEquals("$path?kind=display", server.takeRequest().path)
        assertArrayEquals(jpeg, preview.bytes)
        assertEquals("image/jpeg", preview.contentType)
        assertTrue(CameraFeature.MEDIA_PREVIEW in client.observedFeatureSnapshot())
        assertTrue(videoFailure is IllegalArgumentException)
        assertTrue(pngFailure is IllegalArgumentException)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun mediaPreviewRejectsTextAndOversizedResponses() = runTest {
        val item = CameraMediaItem("SIM_0001.PNG", "SIM_0001.PNG", "image")
        server.enqueue(jsonResponse("""{"message":"not a preview"}"""))
        val textFailure = runCatching { client.mediaPreview(item) }.exceptionOrNull()

        server.enqueue(binaryResponse(ByteArray(32 * 1024 * 1024 + 1), "image/png"))
        val oversizedFailure = runCatching { client.mediaPreview(item) }.exceptionOrNull()

        assertTrue(textFailure is IllegalStateException)
        assertTrue(textFailure?.message.orEmpty().contains("text instead of an image"))
        assertTrue(oversizedFailure is IllegalStateException)
        assertTrue(oversizedFailure?.message.orEmpty().contains("exceeded"))
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
    fun mediaMetadataUsesAdvertisedCanonPutAndVerifiesReadback() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse("""{"ver110":[{"path":"/contents","get":true,"put":true}]}"""))
        client.initialize()
        server.takeRequest()
        val path = "/ccapi/ver110/contents/card1/100CANON/IMG_0001.JPG"
        val item = CameraMediaItem(path, "IMG_0001.JPG", "image")
        server.enqueue(
            jsonResponse(
                """{"filesize":1234,"protect":"disable","archive":"disable","rating":"off","rotate":"0","lastmodifieddate":"2026-08-05T10:00:00+08:00"}""",
            ),
        )

        val info = client.mediaInfo(item)

        assertEquals(1234L, info.sizeBytes)
        assertEquals(false, info.protected)
        assertEquals(false, info.archived)
        assertEquals(0, info.rating)
        assertEquals(0, info.rotationDegrees)
        assertEquals("$path?kind=info", server.takeRequest().path)

        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"protect":"enable","archive":"disable","rating":"off","rotate":"0"}"""))
        val protected = client.setMediaProtection(info, true)
        val protectRequest = server.takeRequest()
        val protectBody = JSONObject(protectRequest.body.readUtf8())
        assertEquals("PUT", protectRequest.method)
        assertEquals(path, protectRequest.path)
        assertEquals("protect", protectBody.getString("action"))
        assertEquals("enable", protectBody.getString("value"))
        assertEquals("$path?kind=info", server.takeRequest().path)
        assertEquals(true, protected.protected)

        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"protect":"enable","archive":"enable","rating":"off","rotate":"0"}"""))
        val archived = client.setMediaArchived(protected, true)
        val archiveRequest = server.takeRequest()
        val archiveBody = JSONObject(archiveRequest.body.readUtf8())
        assertEquals("archive", archiveBody.getString("action"))
        assertEquals("enable", archiveBody.getString("value"))
        server.takeRequest()
        assertEquals(true, archived.archived)

        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"protect":"enable","archive":"disable","rating":"off","rotate":"0"}"""))
        val unarchived = client.setMediaArchived(archived, false)
        val unarchiveRequest = server.takeRequest()
        val unarchiveBody = JSONObject(unarchiveRequest.body.readUtf8())
        assertEquals("archive", unarchiveBody.getString("action"))
        assertEquals("disable", unarchiveBody.getString("value"))
        server.takeRequest()
        assertEquals(false, unarchived.archived)

        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"protect":"enable","archive":"disable","rating":"5","rotate":"0"}"""))
        assertEquals(5, client.setMediaRating(unarchived, 5).rating)
        val ratingRequest = server.takeRequest()
        val ratingBody = JSONObject(ratingRequest.body.readUtf8())
        assertEquals("rating", ratingBody.getString("action"))
        assertEquals("5", ratingBody.getString("value"))
        server.takeRequest()

        server.enqueue(jsonResponse("{}"))
        server.enqueue(jsonResponse("""{"protect":"enable","archive":"disable","rating":"5","rotate":"270"}"""))
        assertEquals(270, client.setMediaRotation(unarchived, 270).rotationDegrees)
        val rotateRequest = server.takeRequest()
        val rotateBody = JSONObject(rotateRequest.body.readUtf8())
        assertEquals("rotate", rotateBody.getString("action"))
        assertEquals("270", rotateBody.getString("value"))
        server.takeRequest()

        assertTrue(CameraFeature.MEDIA_PROTECT in client.observedFeatureSnapshot())
        assertTrue(CameraFeature.MEDIA_ARCHIVE in client.observedFeatureSnapshot())
        assertTrue(CameraFeature.MEDIA_RATING in client.observedFeatureSnapshot())
        assertTrue(CameraFeature.MEDIA_ROTATE in client.observedFeatureSnapshot())
    }

    @Test
    fun mediaMetadataDoesNotWriteWithoutAdvertisedContentsPut() = runTest {
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
        server.enqueue(jsonResponse("""{"ver110":[{"path":"/contents","get":true}]}"""))
        client.initialize()
        server.takeRequest()
        val item = CameraMediaItem(
            "/ccapi/ver110/contents/card1/100CANON/IMG_0001.JPG",
            "IMG_0001.JPG",
            "image",
        )

        val failure = runCatching { client.setMediaArchived(item, true) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("did not advertise media archive"))
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
                .throttleBody(1024, 100, TimeUnit.MILLISECONDS),
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
        assertEquals("/ccapi/ver100/shooting/liveview/flipdetail?kind=image", flipDetailRequest.path)
        assertArrayEquals(jpeg, frame.bytes)
    }

    @Test
    fun liveViewFrameRetriesTransientCanonDeviceBusy() = runTest {
        client.forceRealCamera()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x07, 0x08, 0xFF.toByte(), 0xD9.toByte())
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("content-type", "application/json")
                .setBody("""{"message":"Device busy"}"""),
        )
        server.enqueue(binaryResponse(jpeg, "image/jpeg"))

        val frame = client.liveViewFrame(cacheKey = 10)
        val requests = List(2) { server.takeRequest() }

        assertArrayEquals(jpeg, frame.bytes)
        assertEquals(
            listOf(
                "/ccapi/ver100/shooting/liveview/flip?t=10",
                "/ccapi/ver100/shooting/liveview/flip?t=10",
            ),
            requests.map { it.path },
        )
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setHeader("content-type", "application/json")
            .setBody(body)

    private fun binaryResponse(body: ByteArray, contentType: String): MockResponse =
        MockResponse()
            .setHeader("content-type", contentType)
            .setBody(Buffer().write(body))

    private fun detailedLiveView(jpeg: ByteArray, info: String): ByteArray =
        detailedPacket(0x00, jpeg) + detailedPacket(0x01, info.toByteArray())

    private fun detailedPacket(type: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        output.write(byteArrayOf(0xFF.toByte(), 0x00, type.toByte()))
        output.write(
            byteArrayOf(
                (payload.size ushr 24).toByte(),
                (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
            ),
        )
        output.write(payload)
        output.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        output.toByteArray()
    }

    private fun enqueueRealStatus() {
        server.enqueue(jsonResponse("""{"batterylist":[{"kind":"battery","level":89}]}"""))
        server.enqueue(jsonResponse("""{"storagelist":[{"name":"card1","spacesize":32000000000}]}"""))
        server.enqueue(jsonResponse(REAL_SETTINGS_JSON))
    }

    private fun enqueueCanonFileNaming(
        stillUserSetting1: String = "IMG_",
        movieReelNumber: Int = 1,
    ) {
        server.enqueue(
            jsonResponse(
                """{"value":"preset_code","ability":["preset_code","usersetting1","usersetting2"]}""",
            ),
        )
        server.enqueue(jsonResponse("""{"usersetting1":"$stillUserSetting1"}"""))
        server.enqueue(jsonResponse("""{"usersetting2":"EOS"}"""))
        server.enqueue(jsonResponse("""{"index":"A_"}"""))
        server.enqueue(
            jsonResponse(
                """{"value":$movieReelNumber,"ability":{"min":1,"max":9999,"step":1}}""",
            ),
        )
        server.enqueue(jsonResponse("""{"value":1,"ability":{"min":1,"max":999,"step":1}}"""))
        server.enqueue(jsonResponse("""{"userdefined":"EOS01"}"""))
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

    private class FakeNativeLiveViewSession(
        description: CcapiRtpSessionDescription,
        destinationAddress: String,
        private val readyError: Exception? = null,
    ) : NativeLiveViewSession {
        override val source: LiveViewSource = LiveViewSource.CCAPI_RTP
        override val sourceUrl: String = "rtp://$destinationAddress:${description.video.port}"
        override val contentType: String = "video/H264"
        var started = false
        var closed = false
        var selectedFps = 0
        var readyAwaited = false

        override fun start() {
            started = true
        }

        override suspend fun awaitReady(timeoutMillis: Long) {
            readyAwaited = true
            readyError?.let { throw it }
        }

        override fun attachSurface(surface: Surface) = Unit

        override fun detachSurface(surface: Surface) = Unit

        override fun setTargetFps(fps: Int) {
            selectedFps = fps
        }

        override fun setRenderingEnabled(enabled: Boolean) = Unit

        override fun setListener(listener: ((NativeLiveViewEvent) -> Unit)?) = Unit

        override fun close() {
            closed = true
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
              "recordable_shots": 2418,
              "remaining_recording_seconds": 7200,
              "media": {"available": true, "remaining_minutes": 120, "total_bytes": 128000000000, "free_bytes": 84000000000, "free_images": 2418, "devices": 2},
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
              "white_balance": ["auto", "daylight"],
              "liveView": {
                "magnifications": [1, 5, 10],
                "currentMagnification": 1
              }
            }
        """

        const val SIMULATOR_FILE_NAMING_CAPABILITIES_JSON = """
            {
              "iso": ["100"],
              "shutter": ["1/50"],
              "aperture": ["2.8"],
              "white_balance": ["auto"],
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
              }
            }
        """

        const val CANON_FILE_NAMING_DISCOVERY_JSON = """
            {"ver100":[
              {"path":"/shooting/settings","get":true},
              {"path":"/functions/filename/stills/filename","get":true,"put":true},
              {"path":"/functions/filename/stills/usersetting1","get":true,"put":true},
              {"path":"/functions/filename/stills/usersetting2","get":true,"put":true},
              {"path":"/functions/filename/movies/index","get":true,"put":true},
              {"path":"/functions/filename/movies/reelnum","get":true,"put":true},
              {"path":"/functions/filename/movies/clipnum","get":true,"put":true},
              {"path":"/functions/filename/movies/userdefined","get":true,"put":true}
            ]}
        """

        const val REAL_SETTINGS_JSON = """
            {
              "iso": {"value": "800", "ability": ["100", "800", "1600"]},
              "tv": {"value": "1/50", "ability": ["1/50", "1/100"]},
              "av": {"value": "2.8", "ability": ["2.8", "4.0"]},
              "wb": {"value": "auto", "ability": ["auto", "daylight"]},
              "meteringmode": {"value": "evaluative", "ability": ["evaluative", "spot"]},
              "afmethod": {"value": "face+tracking", "ability": ["face+tracking", "1-point"]},
              "stillimagequality": {
                "value": {"raw": "none", "jpeg": "large_fine"},
                "ability": {"raw": ["none", "raw", "craw"], "jpeg": ["none", "large_fine", "large_normal"]}
              },
              "wbshift": {
                "value": {"ba": 0, "mg": 0},
                "ability": {
                  "ba": {"min": -9, "max": 9, "step": 1},
                  "mg": {"min": -9, "max": 9, "step": 1}
                }
              },
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
                {"path":"/shooting/settings/stillimagequality","put":true},
                {"path":"/shooting/settings/wbshift","put":true},
                {"path":"/shooting/settings/shootingmode","put":true}
              ]
            }
        """

        const val DEVICE_STATUS_DISCOVERY_JSON = """
            {
              "ver100": [
                {"path":"/devicestatus/batterylist","get":true},
                {"path":"/devicestatus/storage","get":true},
                {"path":"/shooting/information/recordable","get":true},
                {"path":"/devicestatus/lens","get":true},
                {"path":"/devicestatus/temperature","get":true},
                {"path":"/shooting/settings","get":true}
              ]
            }
        """

        const val DISCOVERY_RTP_JSON = """
            {
              "ver110": [
                {"path":"/shooting/liveview/rtpsessiondesc","get":true},
                {"path":"/shooting/liveview/rtp","post":true}
              ]
            }
        """

        const val DISCOVERY_RTP_AND_JPEG_JSON = """
            {
              "ver110": [
                {"path":"/shooting/liveview","post":true,"delete":true},
                {"path":"/shooting/liveview/flip","get":true},
                {"path":"/shooting/liveview/rtpsessiondesc","get":true},
                {"path":"/shooting/liveview/rtp","post":true}
              ]
            }
        """

        const val DISCOVERY_POST_ONLY_RTP_AND_JPEG_JSON = """
            {
              "ver100": [
                {"path":"/shooting/liveview","post":true},
                {"path":"/shooting/liveview/flip","get":true},
                {"path":"/shooting/liveview/rtpsessiondesc","get":true},
                {"path":"/shooting/liveview/rtp","get":true,"post":true}
              ]
            }
        """

        const val DISCOVERY_LATEST_FIRMWARE_LIVE_VIEW_JSON = """
            {
              "ver100": [
                {"path":"/shooting/liveview","post":true},
                {"path":"/shooting/liveview/flip","get":true},
                {"path":"/shooting/liveview/multipart","get":true,"delete":true},
                {"path":"/shooting/liveview/rtpsessiondesc","get":true},
                {"path":"/shooting/liveview/rtp","get":true,"post":true}
              ]
            }
        """

        const val DISCOVERY_MULTIPART_JSON = """
            {
              "ver110": [
                {"path":"/shooting/liveview","post":true,"delete":true},
                {"path":"/shooting/liveview/flip","get":true},
                {"path":"/shooting/liveview/multipart","get":true,"delete":true}
              ]
            }
        """

        const val CANON_RTP_SDP = """
            v=0
            o=- 0 0 IN IP4 192.168.11.4
            s=RTP Session
            c=IN IP4 0.0.0.0
            t=0 0
            a=control *
            m=video 12000 RTP/AVP 103
            a=rtpmap:103 H264/90000
            m=audio 12010 RTP/AVP 106
            a=rtpmap:106 MP4A-LATM/48000
        """
    }
}
