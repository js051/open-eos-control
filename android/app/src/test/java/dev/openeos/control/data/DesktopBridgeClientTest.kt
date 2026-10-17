package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

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

    @Test fun maintenanceUsesOnlyNewOutcomeAndNeverFallsBackToLegacyMutation() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val request = MaintenanceRequest(MaintenanceAction.CLOCK_SYNC, MaintenanceClockTarget(1_700_000_000_000, 0, false))
        val attempt = dev.openeos.control.ui.MaintenanceAttempt(dev.openeos.control.ui.MaintenanceDraft(
            dev.openeos.control.ui.MaintenanceOwner(Any(), 1), "synthetic", request), client.maintenanceAccess(request, null), { true }, publish = {})
        attempt.run(); assertEquals(MaintenanceCommandEvidence.UNKNOWN, attempt.snapshot().evidence.command)
        assertEquals(3, server.requestCount); repeat(2) { server.takeRequest() }
        val write = server.takeRequest(); assertEquals("POST", write.method)
        assertEquals("/v1/session/session-1/maintenance/clock-sync/outcome", write.path)
        assertEquals(false, JSONObject(write.body.readUtf8()).getBoolean("autoPowerOff"))
    }

    @Test fun maintenanceClockOutcomePreservesResponseWhenClockReadIsMalformed() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        server.enqueue(jsonResponse("""{"version":1,"engine":"libgphoto2","operation":"clock-sync","autoPowerOff":false,"responseEvidence":"response-received","clockReadEvidence":"matched","clockReference":null,"clockObservation":{}}"""))
        val request = MaintenanceRequest(MaintenanceAction.CLOCK_SYNC, MaintenanceClockTarget(1_700_000_000_000, 0, false))
        val attempt = dev.openeos.control.ui.MaintenanceAttempt(dev.openeos.control.ui.MaintenanceDraft(
            dev.openeos.control.ui.MaintenanceOwner(Any(), 1), "synthetic", request), client.maintenanceAccess(request, null), { true }, publish = {})
        attempt.run(); assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, attempt.snapshot().evidence.command)
        assertEquals(MaintenanceClockEvidence.UNAVAILABLE, attempt.snapshot().evidence.clockRead)
        assertEquals(3, server.requestCount)
        server.enqueue(jsonResponse("""{"version":1,"engine":"libgphoto2","operation":"clock-sync","observationEvidence":"observed","clockObservation":{"epochSeconds":1700000000,"utcOffsetMinutes":null,"daylightSaving":null}}"""))
        attempt.recover(); assertEquals(MaintenanceClockEvidence.UNAVAILABLE, attempt.snapshot().evidence.clockRead)
        repeat(3) { server.takeRequest() }; val read = server.takeRequest()
        assertEquals("GET", read.method); assertEquals("/v1/session/session-1/maintenance/clock-sync", read.path)
        assertEquals("no-cache, no-store", read.getHeader("Cache-Control"))
    }

    @Test fun maintenanceRawDecimalExponentAndOverflowTokensNeverBecomeIntegerProof() = runTest {
        for (token in listOf("1.0", "1e0", "18446744073709551616")) {
            server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON, code = 201))
            val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
            val request = MaintenanceRequest(MaintenanceAction.CLOCK_SYNC, MaintenanceClockTarget(1_700_000_000_000, 0, false))
            val access = client.maintenanceAccess(request, null)
            server.enqueue(jsonResponse("""{"version":$token,"engine":"libgphoto2","operation":"clock-sync","autoPowerOff":false,"responseEvidence":"response-received"}"""))
            val base = MaintenanceEvidence(request)
            assertTrue(runCatching { access.dispatch(request, base) }.isFailure)
            assertEquals(MaintenanceCommandEvidence.UNKNOWN, base.snapshot().command)
            server.enqueue(jsonResponse("""{"version":1,"engine":"libgphoto2","operation":"clock-sync","autoPowerOff":false,"responseEvidence":"response-received","clockReadEvidence":"matched","clockReference":{"earliestEpochSeconds":0,"latestEpochSeconds":10,"utcOffsetMinutes":null,"daylightSaving":null},"clockObservation":{"epochSeconds":$token,"utcOffsetMinutes":null,"daylightSaving":null}}"""))
            val clock = MaintenanceEvidence(request); access.dispatch(request, clock)
            assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, clock.snapshot().command)
            assertEquals(MaintenanceClockEvidence.UNAVAILABLE, clock.snapshot().clockRead)
        }
    }

    @Test fun maintenanceControlUsesDedicatedStopStartRoutesAndStrictTypedProof() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        server.enqueue(jsonResponse("""{"active":true,"liveViewStopUnconfirmed":false}"""))
        val preview = LiveViewRequest(source = LiveViewSource.AUTO)
        client.startLiveView(preview)
        val access = client.maintenanceAccess(MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING), preview)
        val guard = MaintenanceIoContext(kotlin.coroutines.coroutineContext) {}
        server.enqueue(jsonResponse("""{"active":false,"liveViewStopUnconfirmed":false}"""))
        kotlinx.coroutines.withContext(guard) { access.stopLiveView() }
        server.enqueue(jsonResponse("""{"active":true,"liveViewStopUnconfirmed":false,"source":"LIBGPHOTO2_JPEG"}"""))
        kotlinx.coroutines.withContext(guard) { access.restore() }
        repeat(3) { server.takeRequest() }
        assertEquals("/v1/session/session-1/maintenance/control/liveview/stop", server.takeRequest().path)
        assertEquals("/v1/session/session-1/maintenance/control/liveview/start", server.takeRequest().path)
        assertTrue(client.liveViewStopRequired) // Normal active responsibility is retained.
    }

    @Test fun unknownMaintenanceRestoreRetainsStrictRetryStopAndCloseWithoutGenericCleanup() = runTest {
        var authReplays = 0
        val http = okhttp3.OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).authenticator { _, response ->
            authReplays++; response.request.newBuilder().header("Authorization", "synthetic-replay").build()
        }.build()
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        val client = DesktopBridgeClient(server.url("/").toString(), httpClient = http); client.initialize()
        server.enqueue(jsonResponse("""{"active":true,"liveViewStopUnconfirmed":false}"""))
        val preview = LiveViewRequest(); client.startLiveView(preview)
        val access = client.maintenanceAccess(MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING), preview)
        server.enqueue(jsonResponse("""{"active":false,"liveViewStopUnconfirmed":false}""")); access.stopLiveView()
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        assertTrue(runCatching { access.restore() }.isFailure); assertTrue(client.maintenanceStopOwned)
        repeat(5) { server.takeRequest() }
        val strictClient = client.javaClass.getDeclaredField("captureConfigurationHttpClient").apply { isAccessible = true }.get(client) as okhttp3.OkHttpClient
        assertEquals(15_000, strictClient.callTimeoutMillis)
        for (code in listOf(302, 401)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", server.url("/forbidden-stop-replay")).setBody("{}"))
            val before = server.requestCount
            assertTrue(runCatching { client.stopLiveView() }.isFailure)
            assertEquals(before + 1, server.requestCount); assertTrue(client.maintenanceStopOwned)
            assertEquals("/v1/session/session-1/maintenance/control/liveview/stop", server.takeRequest().path)
        }
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(204))
        val beforeClose = server.requestCount; assertTrue(client.closeMaintenanceSession())
        assertEquals(beforeClose + 2, server.requestCount)
        assertEquals("/v1/session/session-1/maintenance/control/liveview/stop", server.takeRequest().path)
        val retirement = server.takeRequest()
        assertEquals("DELETE", retirement.method)
        assertEquals("/v1/session/session-1/maintenance/control/session", retirement.path)
        assertEquals(0, authReplays)
        assertTrue(runCatching { client.closeMaintenanceSession() }.isFailure)
        assertEquals(beforeClose + 2, server.requestCount)
    }

    @Test fun restoredMaintenanceSessionCloseReleasesNewBulbBeforeStrictStopAndLocalRetire() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        server.enqueue(jsonResponse("""{"active":true,"liveViewStopUnconfirmed":false}"""))
        val preview = LiveViewRequest(); client.startLiveView(preview)
        val access = client.maintenanceAccess(MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING), preview)
        server.enqueue(jsonResponse("""{"active":false,"liveViewStopUnconfirmed":false}""")); access.stopLiveView()
        server.enqueue(jsonResponse("""{"active":true,"liveViewStopUnconfirmed":false}""")); access.restore()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/bulb/start/owned") || path.endsWith("/bulb/stop/owned") -> {
                        val ownerId = JSONObject(request.body.clone().readUtf8()).getString("ownerId")
                        val active = path.endsWith("/start/owned")
                        jsonResponse(JSONObject().put("ownerId", ownerId).put("outcome", if (active) "started" else "released")
                            .put("status", JSONObject().put("bulbExposureActive", active).put("shutterReleaseUnconfirmed", false)).toString())
                    }
                    path.endsWith("/maintenance/control/liveview/stop") -> jsonResponse("""{"active":false,"liveViewStopUnconfirmed":false}""")
                    path.endsWith("/maintenance/control/session") -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        client.startBulbExposure()
        repeat(6) { server.takeRequest() }
        assertFalse(client.closeMaintenanceSession())
        assertEquals(listOf("/v1/session/session-1/bulb/stop/owned", "/v1/session/session-1/maintenance/control/liveview/stop",
            "/v1/session/session-1/maintenance/control/session"), (0 until 3).map { server.takeRequest().path })
        assertEquals(9, server.requestCount)
    }

    @Test fun maintenanceLocalRetireRouteFailureDoesNotUseGenericDeleteOrRetry() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        client.maintenanceAccess(MaintenanceRequest(MaintenanceAction.SLEEP), null)
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val failure = runCatching { client.closeMaintenanceSession() }.exceptionOrNull()
        assertTrue(failure is MaintenanceRetirementUnconfirmedException)
        assertFalse((failure as MaintenanceRetirementUnconfirmedException).stopUnconfirmed)
        assertEquals(3, server.requestCount); repeat(2) { server.takeRequest() }
        assertEquals("/v1/session/session-1/maintenance/control/session", server.takeRequest().path)
    }

    @Test fun sameSessionWarningRevisionSupersedesStrictStopAndStartResponses() = runTest {
        lateinit var client: DesktopBridgeClient
        var bumpWarning = false
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (bumpWarning && chain.request().url.encodedPath.contains("/maintenance/control/liveview/")) {
                // Synthetic same-session warning arriving while the control response is in flight.
                client.javaClass.getDeclaredField("liveViewRevision").apply {
                    isAccessible = true; setLong(client, getLong(client) + 1)
                }
            }
            response
        }.build()
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        client = DesktopBridgeClient(server.url("/").toString(), httpClient = http); client.initialize()
        server.enqueue(jsonResponse("""{"active":true,"liveViewStopUnconfirmed":false}""")); client.startLiveView(LiveViewRequest())
        val access = client.maintenanceAccess(MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING), LiveViewRequest())
        bumpWarning = true
        server.enqueue(jsonResponse("""{"active":false,"liveViewStopUnconfirmed":false}"""))
        assertTrue(runCatching { access.stopLiveView() }.isFailure); assertTrue(client.maintenanceStopOwned)
        bumpWarning = false
        server.enqueue(jsonResponse("""{"active":false,"liveViewStopUnconfirmed":false}""")); client.stopLiveView()
        bumpWarning = true
        server.enqueue(jsonResponse("""{"active":true,"liveViewStopUnconfirmed":false}"""))
        assertTrue(runCatching { access.restore() }.isFailure); assertTrue(client.maintenanceStopOwned)
        bumpWarning = false
        server.enqueue(jsonResponse("""{"active":false,"liveViewStopUnconfirmed":false}""")); client.stopLiveView()
        assertFalse(client.liveViewStopRequired)
    }

    @Test fun maintenanceEventStopRejectsRedirectOrWrongStatusWithoutMainAction() = runTest {
        for (code in listOf(200, 201, 202, 302)) {
            server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON, code = 201))
            val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
            client.javaClass.getDeclaredField("eventPollingSupported").apply { isAccessible = true; setBoolean(client, true) }
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", server.url("/forbidden-replay")).setBody("{}"))
            val request = MaintenanceRequest(MaintenanceAction.SLEEP)
            val attempt = dev.openeos.control.ui.MaintenanceAttempt(dev.openeos.control.ui.MaintenanceDraft(
                dev.openeos.control.ui.MaintenanceOwner(Any(), 1), "stop-$code", request), client.maintenanceAccess(request, null), { true }, publish = {})
            val before = server.requestCount; attempt.run(); assertEquals(before + 1, server.requestCount)
            assertEquals(MaintenanceCommandEvidence.NOT_SENT, attempt.snapshot().evidence.command)
            assertEquals(MaintenanceStopEvidence.UNKNOWN, attempt.snapshot().evidence.eventStop)
        }
    }

    @Test fun maintenanceKeepPowerRestoreWithNoPriorPreviewNeverStartsLiveView() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        server.enqueue(jsonResponse("""{"version":1,"engine":"libgphoto2","operation":"sensor-cleaning","autoPowerOff":false,"responseEvidence":"response-received","clockReadEvidence":"not-applicable","clockReference":null,"clockObservation":null}"""))
        val reachable = """{"version":1,"engine":"libgphoto2","operation":"sensor-cleaning","observationEvidence":"reachable","clockObservation":null}"""
        server.enqueue(jsonResponse(reachable)); server.enqueue(jsonResponse(reachable))
        val request = MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING)
        val attempt = dev.openeos.control.ui.MaintenanceAttempt(dev.openeos.control.ui.MaintenanceDraft(
            dev.openeos.control.ui.MaintenanceOwner(Any(), 1), "synthetic", request), client.maintenanceAccess(request, null), { true }, publish = {})
        attempt.run(); assertTrue(attempt.restore()); assertEquals(5, server.requestCount)
        val requests = (0 until 5).map { server.takeRequest() }
        assertEquals(0, requests.count { it.path?.contains("liveview") == true })
        assertEquals(1, requests.count { it.path?.endsWith("/outcome") == true })
        assertEquals(2, requests.count { it.path?.endsWith("/maintenance/sensor-cleaning") == true && it.method == "GET" })
    }

    @Test
    fun freshMediaMetadataPreservesMimeAliasesAndRejectsTheirDisagreement() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON))
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()
        val original = CameraMediaItem("SYNTHETIC", "FRAME.JPG", "image", contentType = "image/jpeg")
        for (alias in CAMERA_MEDIA_MIME_ALIASES) {
            server.enqueue(jsonResponse("""{"id":"SYNTHETIC","name":"FRAME.JPG","kind":"image","$alias":" "}"""))
            val returned = client.mediaInfo(original)
            assertEquals(" ", returned.contentType)
            assertFalse(automaticJpegMetadataAdmitted(original, returned, fresh = true))
        }
        server.enqueue(jsonResponse("""{"id":"SYNTHETIC","name":"FRAME.JPG","kind":"image","contentType":"image/jpeg","mimeType":"IMAGE/JPEG"}"""))
        assertEquals(CameraMediaClassification.UNKNOWN, client.mediaInfo(original).mediaClassification)
        server.enqueue(jsonResponse("""{"id":"SYNTHETIC","name":"FRAME.JPG ","kind":"image","mimetype":"image/jpeg"}"""))
        val renamed = client.mediaInfo(original)
        assertEquals("FRAME.JPG ", renamed.name)
        assertFalse(automaticJpegMetadataAdmitted(original, renamed, fresh = true))
    }

    @Test
    fun missingCreatedSessionEngineIsNotInferredFromApiDisplayName() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))
        val session = JSONObject(SESSION_JSON)
        session.getJSONObject("camera").remove("engine")
        server.enqueue(jsonResponse(session.toString()))
        server.enqueue(jsonResponse(INFO_JSON))
        val client = DesktopBridgeClient(server.url("/").toString(), cameraEngine = "libgphoto2")
        client.initialize()
        assertNull(client.info().bridgeEngine)
    }

    @Test
    fun unknownAndConflictingMediaNeverIssueThumbnailPreviewOrStreamRequests() = runTest {
        val client = DesktopBridgeClient(server.url("/").toString())
        for (item in listOf(CameraMediaItem("unknown", "FILE.bin", "other"),
            CameraMediaItem("conflict", "FRAME.JPG", "image", contentType = "video/mp4"))) {
            assertTrue(runCatching { client.mediaThumbnail(item) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { client.mediaPreview(item) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { client.openMediaStream(item) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun successfulDirectoryAckReadsOneFreshSelectionOnTheSameFrozenSession() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON.replace("libgphoto2", "ccapi")))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        server.enqueue(jsonResponse("""{"name":"OTHER"}"""))
        server.enqueue(jsonResponse("""{"operation":"create-directory","directorySelection":{"value":"100OTHER","ability":["100ABCDE","100OTHER"]}}"""))
        val evidence = CaptureConfigurationEvidence(CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE"))
        assertEquals("OTHER", client.createDirectory("ABCDE", evidence))
        assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, evidence.snapshot().writeEvidence)
        assertEquals(CaptureConfigurationReadEvidence.REFRESHED, evidence.snapshot().readbackEvidence)
        assertEquals(listOf("100ABCDE", "100OTHER"), evidence.snapshot().directoryValues)
        repeat(2) { server.takeRequest() }
        val write = server.takeRequest(); val read = server.takeRequest()
        assertEquals("POST", write.method); assertEquals("/v1/session/session-1/directories", write.path)
        assertEquals("GET", read.method); assertEquals("/v1/session/session-1/capture-configuration/create-directory", read.path)
        assertEquals("no-cache, no-store", read.getHeader("Cache-Control"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun successfulDirectoryAckSurvivesItsScopedReadFailureWithoutRetry() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON.replace("libgphoto2", "ccapi")))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        server.enqueue(jsonResponse("""{"name":"OTHER"}"""))
        server.enqueue(jsonResponse("{}", code = 404))
        val evidence = CaptureConfigurationEvidence(CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE"))
        val failure = runCatching { client.createDirectory("ABCDE", evidence) }.exceptionOrNull()
        assertTrue(failure is CaptureConfigurationException)
        assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, evidence.snapshot().writeEvidence)
        assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, evidence.snapshot().readbackEvidence)
        repeat(2) { server.takeRequest() }
        assertEquals("POST", server.takeRequest().method)
        assertEquals("/v1/session/session-1/capture-configuration/create-directory", server.takeRequest().path)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun cancellationAfterDirectoryAckDoesNotStartAutomaticReadback() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON.replace("libgphoto2", "ccapi")))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        server.enqueue(jsonResponse("""{"name":"OTHER"}"""))
        lateinit var evidence: CaptureConfigurationEvidence
        evidence = CaptureConfigurationEvidence(CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE"),
            onChanged = { if (it.writeEvidence == CaptureConfigurationWriteEvidence.ACKNOWLEDGED) evidence.cancel() })
        assertTrue(runCatching { client.createDirectory("ABCDE", evidence) }.isFailure)
        assertEquals("OTHER", evidence.snapshot().acknowledgedValue)
        assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, evidence.snapshot().readbackEvidence)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun captureConfigurationBridgeReceiptRetainsAckAndRecoveryUsesFreshBoundedRoute() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON.replace("libgphoto2", "ccapi")))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        server.enqueue(jsonResponse("""{"error":{"code":"CAPTURE_CONFIG_READBACK_FAILED","message":"SYNTHETIC read failure",
            "engine":"ccapi","feature":"DIRECTORY_CONTROL","captureConfigReceipt":{"version":1,
            "operation":"create-directory","stage":"acknowledged","requestedValue":"ABCDE","acknowledgedValue":"OTHER"}}}""", code = 502))
        val evidence = CaptureConfigurationEvidence(CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE"))
        assertTrue(runCatching { client.createDirectory("ABCDE", evidence) }.isFailure)
        assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, evidence.snapshot().writeEvidence)
        assertEquals("OTHER", evidence.snapshot().acknowledgedValue)
        assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, evidence.snapshot().readbackEvidence)
        server.enqueue(jsonResponse("""{"operation":"create-directory","directorySelection":{"value":"100OTHER","ability":["100OTHER"]}}"""))
        assertEquals("100OTHER", client.readCaptureConfiguration(CaptureConfigurationOperation.CREATE_DIRECTORY).directorySelection)
        repeat(3) { server.takeRequest() }
        val recovery = server.takeRequest(); assertEquals("GET", recovery.method)
        assertEquals("/v1/session/session-1/capture-configuration/create-directory", recovery.path)
        assertEquals("no-cache, no-store", recovery.getHeader("Cache-Control"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun captureConfigurationWrongReceiptAndOversizedBodyRemainUnknown() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        for (body in listOf("""{"error":{"code":"CAPTURE_CONFIG_READBACK_FAILED","message":"SYNTHETIC",
            "engine":"ccapi","feature":"DIRECTORY_CONTROL","captureConfigReceipt":{"version":true,
            "operation":"create-directory","stage":"acknowledged","requestedValue":"ABCDE","acknowledgedValue":"ABCDE"}}}""",
            "{\"padding\":\"" + "X".repeat(65_536) + "\"}")) {
            server.enqueue(jsonResponse(body, code = 502))
            val evidence = CaptureConfigurationEvidence(CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE"))
            assertTrue(runCatching { client.createDirectory("ABCDE", evidence) }.isFailure)
            assertEquals(CaptureConfigurationWriteEvidence.UNKNOWN, evidence.snapshot().writeEvidence)
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun oldBridgeRecovery404HasNoCapabilitiesFallback() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        assertTrue(runCatching { client.readCaptureConfiguration(CaptureConfigurationOperation.SET_FILE_NAMING) }.isFailure)
        assertEquals(3, server.requestCount)
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
        // Use the actual returned engine, not the requested edsdk hint or api display string.
        assertEquals("libgphoto2", info.bridgeEngine)
        assertEquals(server.url("/").toString().trimEnd('/'), info.bridgeEndpoint)
        val initialStatus = client.status()
        val capabilities = client.capabilities()
        val exposureStatus = client.setExposure(iso = "800")
        val whiteBalanceStatus = client.setWhiteBalance("Daylight")
        client.setSetting("soundrecordinglevelintmic", "41")
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
        val media = client.listMedia(maximumItems = 61)
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
        assertEquals("image/jpeg", media.single().contentType)
        assertEquals(6000, media.single().widthPixels)
        assertEquals(4000, media.single().heightPixels)
        assertNull(media.single().folder)
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
        assertTrue(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL in observedFeatures)
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
        assertTrue(requests.any {
            it.requestUrl?.encodedPath?.endsWith("/settings/soundrecordinglevelintmic") == true
        })
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
        val bulbStart = requests.single { it.requestUrl?.encodedPath?.endsWith("/bulb/start/owned") == true }
        val bulbStop = requests.single { it.requestUrl?.encodedPath?.endsWith("/bulb/stop/owned") == true }
        val startOwner = JSONObject(bulbStart.body.clone().readUtf8())
        val stopOwner = JSONObject(bulbStop.body.clone().readUtf8())
        assertEquals(1, startOwner.length()); assertEquals(1, stopOwner.length())
        assertTrue(startOwner.opt("ownerId") is String)
        assertEquals(startOwner.getString("ownerId"), stopOwner.getString("ownerId"))
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/focus/auto") == true })
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/shutter/half-press") == true })
        assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/focus/drive") == true })
        val clickWhiteBalanceRequest = requests.first {
            it.requestUrl?.encodedPath?.endsWith("/whitebalance/click") == true
        }
        val clickWhiteBalancePayload = JSONObject(clickWhiteBalanceRequest.body.readUtf8())
        assertEquals(0.4, clickWhiteBalancePayload.getDouble("x"), 0.0001)
        assertEquals(0.6, clickWhiteBalancePayload.getDouble("y"), 0.0001)
        val mediaRequest = requests.single {
            it.method == "GET" && it.requestUrl?.encodedPath == "/v1/session/session-1/media"
        }
        assertEquals("61", mediaRequest.requestUrl?.queryParameter("limit"))
        assertTrue(requests.any { it.method == "DELETE" && it.requestUrl?.encodedPath?.contains("/media/") == true })
        assertTrue(requests.any { it.method == "DELETE" && it.requestUrl?.encodedPath == "/v1/session/session-1" })
    }

    @Test
    fun mediaListRejectsLimitsOutsideBridgeContract() = runTest {
        val client = DesktopBridgeClient(server.url("/").toString())

        listOf(0, -1, 1001).forEach { limit ->
            val failure = runCatching { client.listMedia(maximumItems = limit) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun mediaFoldersAcceptOnlyCompleteBoundedStringPairsAndKeepMalformedItemsBrowseable() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON))
        val opaqueId = "synthetic-folder:TEST%2F+TEST"
        val label = "card2/DCIM/100CANON"
        val invalidPairs = buildList<Pair<Any?, Any?>> {
            add(null to null) // Legacy response.
            add(opaqueId to null)
            add(null to label)
            add(JSONObject.NULL to label)
            add(opaqueId to JSONObject.NULL)
            listOf(7, 1.5, true, JSONObject(), org.json.JSONArray()).forEach { value ->
                add(value to label)
                add(opaqueId to value)
            }
            add("" to label)
            add(opaqueId to "")
            add("   " to label)
            add(opaqueId to "   ")
            add("x".repeat(4097) to label)
            add(opaqueId to "x".repeat(1025))
            listOf('\u0000', '\t', '\n', '\r', '\u007f', '\u0085').forEach { control ->
                add("$opaqueId$control" to label)
                add(opaqueId to "$label$control")
            }
        }
        val pairs = listOf(
            opaqueId to label,
            "x".repeat(4096) to "y".repeat(1024),
            " opaque folder " to " 相機/card2/100CANON ",
        ) + invalidPairs
        val payload = org.json.JSONArray(pairs.mapIndexed { index, (folderId, folderLabel) ->
            JSONObject()
                .put("id", "edsdk:opaque-item-$index")
                .put("name", "IMG_0001.JPG")
                .put("kind", "image")
                .put("folderId", folderId)
                .put("folderLabel", folderLabel)
        })
        server.enqueue(jsonResponse(JSONObject().put("items", payload).toString()))
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()

        val items = client.listMedia()

        assertEquals(pairs.size, items.size)
        assertEquals(pairs.indices.map { "edsdk:opaque-item-$it" }, items.map { it.id })
        assertEquals(CameraMediaFolder(opaqueId, label), items[0].folder)
        assertEquals(CameraMediaFolder("x".repeat(4096), "y".repeat(1024)), items[1].folder)
        assertEquals(CameraMediaFolder(" opaque folder ", " 相機/card2/100CANON "), items[2].folder)
        items.drop(3).forEach { assertNull("Invalid metadata for ${it.id}", it.folder) }
        assertEquals(3, server.requestCount)
        assertEquals("/health", server.takeRequest().path)
        assertEquals("/v1/session", server.takeRequest().path)
        assertEquals("/v1/session/session-1/media", server.takeRequest().path)
    }

    @Test
    fun mediaFolderMetadataRefreshAndMutationsUseMatchingAuthoritativeItemResponses() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON))
        val itemId = "gphoto2:opaque-item"
        val folderId = "gphoto2:opaque-folder%2Fvalue"
        val itemJson = JSONObject()
            .put("id", itemId)
            .put("name", "IMG_0001.JPG")
            .put("kind", "image")
            .put("folderId", folderId)
            .put("folderLabel", "store_00020001/DCIM/100CANON")
        server.enqueue(jsonResponse(JSONObject().put("items", org.json.JSONArray().put(itemJson)).toString()))
        server.enqueue(jsonResponse(itemJson.toString()))
        server.enqueue(jsonResponse(JSONObject(itemJson.toString()).put("protected", true).toString()))
        server.enqueue(jsonResponse(JSONObject(itemJson.toString()).put("archived", true).toString()))
        server.enqueue(jsonResponse(JSONObject(itemJson.toString()).put("rating", 5).toString()))
        server.enqueue(jsonResponse(JSONObject(itemJson.toString()).put("rotationDegrees", 90).toString()))
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()

        val item = client.listMedia().single()
        val refreshed = client.mediaInfo(item)
        val protected = client.setMediaProtection(refreshed, true)
        val archived = client.setMediaArchived(protected, true)
        val rated = client.setMediaRating(archived, 5)
        val rotated = client.setMediaRotation(rated, 90)

        val expectedFolder = CameraMediaFolder(folderId, "store_00020001/DCIM/100CANON")
        listOf(item, refreshed, protected, archived, rated, rotated).forEach { response ->
            assertEquals(itemId, response.id)
            assertEquals(expectedFolder, response.folder)
        }
        assertEquals(true, protected.protected)
        assertEquals(true, archived.archived)
        assertEquals(5, rated.rating)
        assertEquals(90, rotated.rotationDegrees)
        assertEquals(8, server.requestCount)
        repeat(3) { server.takeRequest() }
        listOf("info", "protection", "archive", "rating", "rotation").forEach { endpoint ->
            val request = server.takeRequest()
            assertEquals("/v1/session/session-1/media/$itemId/$endpoint", request.path)
            assertEquals(if (endpoint == "info") "GET" else "PUT", request.method)
        }

        server.enqueue(MockResponse().setBody(okio.Buffer().write(MEDIA_BYTES)))
        val destination = ByteArrayOutputStream()
        val download = client.downloadMedia(rotated, destination)
        assertEquals("/v1/session/session-1/media/$itemId", server.takeRequest().path)
        assertEquals(itemId, download.item.id)
        assertEquals(expectedFolder, download.item.folder)
        assertArrayEquals(MEDIA_BYTES, destination.toByteArray())
        assertEquals(9, server.requestCount)
    }

    @Test
    fun mediaFolderResponsesDoNotReuseStaleMetadataOrAcceptAnotherItemsIdentity() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON))
        server.enqueue(jsonResponse("""{"id":"ccapi:item","name":"IMG_0001.JPG","kind":"image"}"""))
        server.enqueue(jsonResponse("""{"id":"ccapi:item","name":"IMG_0001.JPG","kind":"image","archived":true,"folderId":"other-folder"}"""))
        val wrongItem = """{"id":"ccapi:another-item","name":"IMG_0001.JPG","kind":"image","protected":true,"folderId":"another-folder","folderLabel":"card2/DCIM/100CANON"}"""
        server.enqueue(jsonResponse(wrongItem))
        server.enqueue(jsonResponse(wrongItem))
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()
        val item = CameraMediaItem(
            "ccapi:item", "IMG_0001.JPG", "image",
            folder = CameraMediaFolder("known-folder", "card1/DCIM/100CANON"),
        )

        assertNull(client.mediaInfo(item).folder)
        assertNull(client.setMediaArchived(item, true).folder)
        val infoFailure = runCatching { client.mediaInfo(item) }.exceptionOrNull()
        val mutationFailure = runCatching { client.setMediaProtection(item, true) }.exceptionOrNull()

        listOf(infoFailure, mutationFailure).forEach { failure ->
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("does not belong to the requested item"))
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun mediaUploadCancellationBeforeDispatchSendsNoPost() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON))
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()
        val attempt = MediaUploadAttempt().also { it.cancel() }
        val failure = runCatching {
            client.uploadMedia("SAME.JPG", 1, "image/jpeg", ByteArrayInputStream(byteArrayOf(7)),
                evidence = MediaUploadEvidence(attempt::beforeDispatch, attempt::confirm))
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(MediaUploadStatus.NOT_SENT, attempt.finish())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun mediaUploadReceiptIsRecordedBeforeCancelledIoReturn() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON))
        server.enqueue(jsonResponse("""{"id":"exact/receipt","name":"SAME.JPG","kind":"image","sizeBytes":1}""", 201))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        val attempt = MediaUploadAttempt()
        val upload = launch {
            val owner = currentCoroutineContext().job
            client.uploadMedia("SAME.JPG", 1, "image/jpeg", ByteArrayInputStream(byteArrayOf(7)),
                evidence = MediaUploadEvidence(attempt::beforeDispatch, { attempt.confirm(it); owner.cancel() }))
        }
        upload.join()
        attempt.cancel()
        assertEquals(MediaUploadStatus.CONFIRMED, attempt.finish())
        assertEquals("exact/receipt", attempt.confirmedResult()?.item?.id)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun mediaUploadLostAcknowledgementIsUnknownAndDoesNotReplay() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        val attempt = MediaUploadAttempt()
        runCatching {
            client.uploadMedia("SAME.JPG", 1, "image/jpeg", ByteArrayInputStream(byteArrayOf(7)),
                evidence = MediaUploadEvidence(attempt::beforeDispatch, attempt::confirm))
        }
        assertEquals(MediaUploadStatus.UNCERTAIN, attempt.finish())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun mediaUploadActualHttpDecoderRejectsInvalidSharedReceiptsWithoutRetry() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON)); server.enqueue(jsonResponse(SESSION_JSON))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        val cases = dev.openeos.control.ui.loadMediaUploadFixture().receipts
            .filter { it.size == 1234L && it.bound && it.status != 204 }
        for (row in cases) {
            server.enqueue(jsonResponse(row.raw, row.status))
            val attempt = MediaUploadAttempt()
            runCatching {
                client.uploadMedia(row.name, row.size, "image/jpeg", ByteArrayInputStream(ByteArray(1234)),
                    evidence = MediaUploadEvidence(attempt::beforeDispatch, attempt::confirm))
            }
            assertEquals(row.id, row.expected, attempt.confirmedResult() != null)
            assertEquals(row.id, if (row.expected) MediaUploadStatus.CONFIRMED else MediaUploadStatus.UNCERTAIN,
                attempt.finish())
        }
        assertEquals(2 + cases.size, server.requestCount)
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
    fun mediaPlaybackReusesOneTicketForRangesAndRevokesAfterLastHandleCloses() = runTest {
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        server.enqueue(
            jsonResponse(
                """{"url":"/v1/media-playback/ticket_123","expiresInSeconds":900}""",
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("content-type", "video/mp4")
                .setHeader("content-range", "bytes 3-7/8")
                .setHeader("content-length", 5)
                .setBody(okio.Buffer().write(bytes, 3, 5)),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("content-type", "video/mp4")
                .setHeader("content-range", "bytes 5-7/8")
                .setHeader("content-length", 3)
                .setBody(okio.Buffer().write(bytes, 5, 3)),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        val client = DesktopBridgeClient(server.url("/").toString(), token = "bridge-secret")
        client.initialize()
        val item = CameraMediaItem(
            id = "gphoto2:video-id",
            name = "MVI_0001.MP4",
            kind = "video",
            sizeBytes = bytes.size.toLong(),
            streamAvailable = true,
        )

        val source = client.openMediaStream(item)
        val first = source.open(3)
        val firstBytes = ByteArray(5)
        assertEquals(5, first.read(firstBytes, 0, firstBytes.size))
        first.close()
        val second = source.open(5)
        val secondBytes = ByteArray(3)
        assertEquals(3, second.read(secondBytes, 0, secondBytes.size))
        source.close()
        assertEquals(5, server.requestCount)
        second.close()

        val health = server.takeRequest()
        val session = server.takeRequest()
        val ticket = server.takeRequest()
        val firstRange = server.takeRequest()
        val secondRange = server.takeRequest()
        val revoke = server.takeRequest(5, TimeUnit.SECONDS)

        assertEquals("/health", health.path)
        assertEquals("/v1/session", session.path)
        assertEquals("POST", ticket.method)
        assertEquals("/v1/session/session-1/media/gphoto2:video-id/playback", ticket.path)
        assertEquals(0L, ticket.bodySize)
        assertEquals("Bearer bridge-secret", ticket.getHeader("authorization"))
        assertEquals("GET", firstRange.method)
        assertEquals("/v1/media-playback/ticket_123", firstRange.path)
        assertEquals("bytes=3-", firstRange.getHeader("range"))
        assertEquals("GET", secondRange.method)
        assertEquals("/v1/media-playback/ticket_123", secondRange.path)
        assertEquals("bytes=5-", secondRange.getHeader("range"))
        assertEquals("DELETE", revoke?.method)
        assertEquals("/v1/media-playback/ticket_123", revoke?.path)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), firstBytes)
        assertArrayEquals(byteArrayOf(5, 6, 7), secondBytes)
    }

    @Test
    fun mediaPlaybackRejectsTicketOutsideBridgeOrigin() = runTest {
        server.enqueue(jsonResponse(HEALTH_JSON))
        server.enqueue(jsonResponse(SESSION_JSON, code = 201))
        server.enqueue(
            jsonResponse(
                """{"url":"https://example.invalid/v1/media-playback/stolen","expiresInSeconds":900}""",
            ),
        )
        val client = DesktopBridgeClient(server.url("/").toString())
        client.initialize()
        val item = CameraMediaItem(
            id = "gphoto2:video-id",
            name = "MVI_0001.MP4",
            kind = "video",
            sizeBytes = 8,
            streamAvailable = true,
        )

        val failure = runCatching { client.openMediaStream(item) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("outside its own origin"))
        assertEquals(3, server.requestCount)
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
        assertTrue(failure?.message.orEmpty().contains("byte limit"))
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
                path.endsWith("/settings/soundrecordinglevelintmic") -> json(statusJson())
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
                path.endsWith("/capture-configuration/create-directory") && request.method == "GET" ->
                    json("""{"operation":"create-directory","directorySelection":{"value":"100ABCDE","ability":["100CANON","100ABCDE"]}}""")
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
                path.endsWith("/bulb/start/owned") || path.endsWith("/bulb/stop/owned") -> {
                    val payload = JSONObject(request.body.clone().readUtf8())
                    check(payload.length() == 1 && payload.opt("ownerId") is String)
                    bulbExposureActive = path.endsWith("/start/owned")
                    json(JSONObject().put("ownerId", payload.getString("ownerId"))
                        .put("outcome", if (bulbExposureActive) "started" else "released")
                        .put("status", JSONObject(statusJson()).put("shutterReleaseUnconfirmed", false)).toString())
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
                  "widthPixels": 6000,
                  "heightPixels": 4000,
                  "previewAvailable": true
                }
              ]
            }
        """
    }
}
