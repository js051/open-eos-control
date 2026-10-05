package dev.openeos.control.ui

import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.ConnectionFailureReason
import dev.openeos.control.data.CameraBackendFactory
import dev.openeos.control.data.CameraHttpTransport
import dev.openeos.control.data.CameraHttpTransportFactory
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Production VM -> repository -> HTTP, with responses gated at an independent local peer.
 *
 * Proposed edit policy: an accepted configuration edit abandons the old attempt; a new
 * connection or discovery still needs an explicit tap. This is protocol-fixture evidence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraConnectionConfigurationRecoveryTest {
    private val main = StandardTestDispatcher()
    private val oldPeer = ConnectionConfigurationPeer("A", 31)
    private val newPeer = ConnectionConfigurationPeer("B", 87)
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()
    private val repository = CameraRepository(CameraBackendFactory(
        httpTransportFactory = CameraHttpTransportFactory {
            CameraHttpTransport(http, CameraNetworkDiagnostics.Empty)
        },
    ))
    private lateinit var viewModel: CameraViewModel

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(main)
        // Resolve/cache server URLs off Main, matching instrumented reuse of this fixture.
        withContext(Dispatchers.IO) {
            oldPeer.start()
            newPeer.start()
        }
        viewModel = CameraViewModel(repository)
        viewModel.setLiveViewAutoRefresh(false)
        main.scheduler.runCurrent()
    }

    @After fun tearDown() = runBlocking {
        // A failed assertion must not leave a MockWebServer dispatcher waiting on our gate.
        oldPeer.releaseAllGates()
        newPeer.releaseAllGates()
        try {
            if (::viewModel.isInitialized) {
                viewModel.disconnect()
                http.dispatcher.cancelAll()
                try {
                    assertTrue("HTTP calls did not finish teardown", pumpUntil {
                        http.dispatcher.runningCallsCount() == 0
                    })
                } finally {
                    // HTTP idle alone does not prove the cancelled IO continuation finished.
                    viewModel.cancelAndAwaitTestScope(main)
                    // disconnect() deliberately owns detached NonCancellable cleanup.
                    // Pump Main while joining the repository lock; directly awaiting this
                    // lock can strand an earlier cleanup continuation on virtual Main.
                    val repositoryCleanup = async(start = CoroutineStart.UNDISPATCHED) { repository.disconnect() }
                    try {
                        assertTrue("Repository cleanup did not finish", pumpUntil { repositoryCleanup.isCompleted })
                        repositoryCleanup.await()
                    } finally {
                        repositoryCleanup.cancel()
                    }
                    main.scheduler.runCurrent()
                }
            }
        } finally {
            try {
                withContext(Dispatchers.IO) {
                    try { oldPeer.server.shutdown() } finally { newPeer.server.shutdown() }
                }
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
                Dispatchers.resetMain()
            }
        }
    }

    @Test fun editedBridgeConfigurationCannotReceiveTheOldDiscoveryOrSelection() = runBlocking {
        viewModel.setConnectionTarget(ConnectionTarget.DESKTOP_BRIDGE)
        viewModel.setBridgeBaseUrl(oldPeer.baseUrl)
        viewModel.setBridgeToken("synthetic-old-token")
        val gate = oldPeer.gateNext("/v1/cameras")
        viewModel.scanDesktopBridge()
        assertTrue("The old discovery request never reached the peer", pumpUntil { gate.entered.count == 0L })
        val oldAttempt = currentAttemptJobs()
        assertEquals(listOf("/health", "/v1/cameras"), oldPeer.requests.map { it.path })
        assertTrue(oldPeer.requests.all { it.authorization == "Bearer synthetic-old-token" })

        viewModel.setBridgeBaseUrl(newPeer.baseUrl)
        viewModel.setBridgeToken("synthetic-new-token")
        assertEquals(newPeer.baseUrl, viewModel.uiState.value.bridgeBaseUrl)
        gate.release.countDown()
        awaitAttempt(oldAttempt)

        val edited = viewModel.uiState.value
        assertTrue("A completed scan for endpoint A must not populate endpoint B", edited.bridgeCameras.isEmpty())
        assertNull("The old camera must not be auto-selected under edited settings", edited.selectedBridgeCameraId)
        assertNull(edited.error)
        assertTrue("Editing must not silently discover the replacement endpoint", newPeer.requests.isEmpty())

        viewModel.scanDesktopBridge()
        assertTrue("An explicit new scan must recover", pumpUntil {
            CameraOperation.BRIDGE !in viewModel.uiState.value.pendingOperations &&
                viewModel.uiState.value.selectedBridgeCameraId == newPeer.cameraId
        })
        assertEquals(listOf(newPeer.cameraId), viewModel.uiState.value.bridgeCameras.map { it.id })
        assertEquals(listOf("/health", "/v1/cameras"), newPeer.requests.map { it.path })
        assertTrue(newPeer.requests.all { it.authorization == "Bearer synthetic-new-token" })
        assertReadOnlyRequests()
    }

    @Test fun editedDirectUrlCannotPublishOldCameraAndExplicitRetryUsesNewEndpoint() = runBlocking {
        val oldAttempt = holdDirectConnect()
        viewModel.setBaseUrl(newPeer.baseUrl)
        assertEquals(newPeer.baseUrl, viewModel.uiState.value.baseUrl)
        oldPeer.releaseAllGates()
        awaitAttempt(oldAttempt)

        val edited = viewModel.uiState.value
        assertFalse("Endpoint B must not appear connected using the session from endpoint A", edited.connected)
        assertNull("An edited URL must not inherit the old camera identity", edited.info)
        assertNull(edited.error)
        assertTrue("Editing must not silently connect the replacement endpoint", newPeer.requests.isEmpty())
        connectAndVerifyReplacement()
    }

    @Test fun explicitDisconnectAlreadyCancelsTheHeldAttemptAndAllowsReplacement() = runBlocking {
        val oldAttempt = holdDirectConnect()
        viewModel.disconnect()
        viewModel.setBaseUrl(newPeer.baseUrl)
        oldPeer.releaseAllGates()
        awaitAttempt(oldAttempt)

        assertFalse(viewModel.uiState.value.connected)
        assertNull(viewModel.uiState.value.info)
        assertNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.pendingOperations.isEmpty())
        assertTrue(newPeer.requests.isEmpty())
        connectAndVerifyReplacement()
    }

    @Test fun nativeAuthenticationFailureIsSafeAndEditingAllowsManualRecovery() = runBlocking {
        oldPeer.intercept = { request ->
            MockResponse().setResponseCode(if (request.requestUrl?.encodedPath == "/ccapi") 401 else 404)
                .setBody("SYNTHETIC-PRIVATE-RESPONSE https://synthetic.example/?private=fixture")
        }
        viewModel.useDirectCameraPreset()
        viewModel.setBaseUrl(oldPeer.baseUrl)
        viewModel.setUsername("synthetic-user")
        viewModel.setPassword("synthetic-password")
        viewModel.connect()
        assertTrue(pumpUntil { viewModel.uiState.value.connectionRecovery != null && !viewModel.uiState.value.busy })
        assertEquals(ConnectionRecovery(ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.AUTHENTICATION_REJECTED),
            viewModel.uiState.value.connectionRecovery)
        assertFalse(viewModel.uiState.value.error.orEmpty().contains("synthetic", ignoreCase = true))
        assertFalse(viewModel.uiState.value.error.orEmpty().contains(oldPeer.baseUrl))
        viewModel.setUsername("synthetic-replacement")
        assertNull(viewModel.uiState.value.connectionRecovery)
        assertNull(viewModel.uiState.value.error)
        viewModel.setBaseUrl(newPeer.baseUrl)
        assertTrue(newPeer.requests.isEmpty())
        connectAndVerifyReplacement()
    }

    @Test fun bridgeAuthenticationFailureUsesActualStatusAndClearsWithNewToken() = runBlocking {
        oldPeer.intercept = {
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"NOT_AUTHENTICATION","message":"SYNTHETIC-PRIVATE-RESPONSE"}}""")
        }
        viewModel.setConnectionTarget(ConnectionTarget.DESKTOP_BRIDGE)
        viewModel.setBridgeBaseUrl(oldPeer.baseUrl)
        viewModel.scanDesktopBridge()
        assertTrue(pumpUntil { viewModel.uiState.value.connectionRecovery != null && !viewModel.uiState.value.busy })
        assertEquals(ConnectionRecovery(ConnectionAttemptTarget.DESKTOP_BRIDGE, ConnectionFailureReason.AUTHENTICATION_REJECTED),
            viewModel.uiState.value.connectionRecovery)
        assertFalse(viewModel.uiState.value.bridgeScanCompleted)
        assertFalse(viewModel.uiState.value.error.orEmpty().contains("SYNTHETIC-PRIVATE"))
        viewModel.setBridgeToken("synthetic-corrected-token")
        assertNull(viewModel.uiState.value.connectionRecovery)
        viewModel.setBridgeBaseUrl(newPeer.baseUrl)
        assertTrue(newPeer.requests.isEmpty())
        viewModel.scanDesktopBridge()
        assertTrue(pumpUntil { viewModel.uiState.value.bridgeScanCompleted && !viewModel.uiState.value.busy })
        assertEquals(newPeer.cameraId, viewModel.uiState.value.selectedBridgeCameraId)
        assertTrue(newPeer.requests.all { it.authorization == "Bearer synthetic-corrected-token" })
        assertNull(viewModel.uiState.value.error)
    }

    @Test fun invalidAddressesFailBeforeHttpWithoutEchoingEnteredData() = runBlocking {
        viewModel.setBaseUrl("synthetic private address")
        viewModel.connect()
        assertTrue(pumpUntil { viewModel.uiState.value.connectionRecovery != null && !viewModel.uiState.value.busy })
        assertEquals(ConnectionFailureReason.INVALID_ADDRESS, viewModel.uiState.value.connectionRecovery?.reason)
        assertFalse(viewModel.uiState.value.error.orEmpty().contains("synthetic"))
        viewModel.setConnectionTarget(ConnectionTarget.DESKTOP_BRIDGE)
        viewModel.setBridgeBaseUrl("https://synthetic-user:synthetic-password@invalid.example/?synthetic=1")
        viewModel.scanDesktopBridge()
        assertTrue(pumpUntil { viewModel.uiState.value.connectionRecovery != null && !viewModel.uiState.value.busy })
        assertEquals(ConnectionAttemptTarget.DESKTOP_BRIDGE, viewModel.uiState.value.connectionRecovery?.target)
        assertEquals(ConnectionFailureReason.INVALID_ADDRESS, viewModel.uiState.value.connectionRecovery?.reason)
        assertFalse(viewModel.uiState.value.error.orEmpty().contains("synthetic"))
        assertTrue(oldPeer.requests.isEmpty() && newPeer.requests.isEmpty())
    }

    @Test fun completedEmptyScanIsDistinctFromInitialAndEditedConfiguration() = runBlocking {
        oldPeer.intercept = { request ->
            if (request.requestUrl?.encodedPath == "/v1/cameras") MockResponse()
                .setHeader("Content-Type", "application/json").setBody("""{"cameras":[]}""") else null
        }
        viewModel.setConnectionTarget(ConnectionTarget.DESKTOP_BRIDGE)
        viewModel.setBridgeBaseUrl(oldPeer.baseUrl)
        assertFalse(viewModel.uiState.value.bridgeScanCompleted)
        viewModel.scanDesktopBridge()
        assertTrue(pumpUntil { viewModel.uiState.value.bridgeScanCompleted && !viewModel.uiState.value.busy })
        assertTrue(viewModel.uiState.value.bridgeCameras.isEmpty())
        assertNull(viewModel.uiState.value.connectionRecovery)
        assertNull(viewModel.uiState.value.error)
        viewModel.setBridgeBaseUrl(newPeer.baseUrl)
        assertFalse(viewModel.uiState.value.bridgeScanCompleted)
        assertTrue(newPeer.requests.isEmpty())
        viewModel.scanDesktopBridge()
        assertTrue(pumpUntil { viewModel.uiState.value.bridgeScanCompleted && !viewModel.uiState.value.busy })
        assertEquals(newPeer.cameraId, viewModel.uiState.value.selectedBridgeCameraId)
    }

    @Test fun unchangedFieldsDoNotCancelAnAcceptedScan() = runBlocking {
        viewModel.setConnectionTarget(ConnectionTarget.DESKTOP_BRIDGE)
        viewModel.setBridgeBaseUrl(oldPeer.baseUrl)
        viewModel.setBridgeToken("synthetic-token")
        val gate = oldPeer.gateNext("/v1/cameras")
        viewModel.scanDesktopBridge()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        val attempt = currentAttemptJobs()
        viewModel.setBridgeBaseUrl(oldPeer.baseUrl)
        viewModel.setBridgeToken("synthetic-token")
        viewModel.setConnectionTarget(ConnectionTarget.DESKTOP_BRIDGE)
        assertTrue(attempt.any { it.isActive })
        assertTrue(CameraOperation.BRIDGE in viewModel.uiState.value.pendingOperations)
        gate.release.countDown()
        awaitAttempt(attempt)
        assertEquals(oldPeer.cameraId, viewModel.uiState.value.selectedBridgeCameraId)
        assertTrue(viewModel.uiState.value.bridgeScanCompleted)
    }

    @Test fun abandonedScanFailureCannotReplaceTheEditedConfigurationRecovery() = runBlocking {
        oldPeer.intercept = { request -> if (request.requestUrl?.encodedPath == "/v1/cameras")
            MockResponse().setResponseCode(401).setBody("synthetic obsolete response") else null }
        viewModel.setConnectionTarget(ConnectionTarget.DESKTOP_BRIDGE)
        viewModel.setBridgeBaseUrl(oldPeer.baseUrl)
        val gate = oldPeer.gateNext("/v1/cameras")
        viewModel.scanDesktopBridge()
        assertTrue(pumpUntil { gate.entered.count == 0L })
        val attempt = currentAttemptJobs()
        viewModel.setBridgeBaseUrl(newPeer.baseUrl)
        gate.release.countDown()
        awaitAttempt(attempt)
        assertNull(viewModel.uiState.value.connectionRecovery)
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.bridgeScanCompleted)
        assertTrue(viewModel.uiState.value.bridgeCameras.isEmpty())
        assertTrue(newPeer.requests.isEmpty())
    }

    private suspend fun holdDirectConnect(): List<Job> {
        viewModel.useDirectCameraPreset()
        viewModel.setBaseUrl(oldPeer.baseUrl)
        val gate = oldPeer.gateNext("/ccapi/ver110/deviceinformation")
        viewModel.connect()
        assertTrue("The old connection never reached camera identity read", pumpUntil { gate.entered.count == 0L })
        assertFalse(viewModel.uiState.value.connected)
        assertTrue(CameraOperation.CONNECT in viewModel.uiState.value.pendingOperations)
        assertTrue(oldPeer.requests.any { it.path == "/ccapi" })
        assertTrue(oldPeer.requests.any { it.path == "/ccapi/ver110/deviceinformation" })
        return currentAttemptJobs()
    }

    private suspend fun connectAndVerifyReplacement() {
        viewModel.connect()
        assertTrue("Explicit retry failed: ${viewModel.uiState.value.error}", pumpUntil {
            viewModel.uiState.value.connected && !viewModel.uiState.value.busy
        })
        assertEquals(newPeer.baseUrl, viewModel.uiState.value.baseUrl)
        assertEquals(newPeer.model, viewModel.uiState.value.info?.model)
        assertEquals(newPeer.serial, viewModel.uiState.value.info?.serial)
        assertEquals(87, viewModel.uiState.value.status?.batteryLevel)
        assertTrue(newPeer.requests.any { it.path == "/ccapi/ver110/deviceinformation" })
        val oldReads = oldPeer.requests.size
        val newBatteryReads = newPeer.requests.count { it.path.endsWith("/devicestatus/battery") }
        viewModel.refresh()
        assertTrue("Replacement status read did not finish", pumpUntil {
            CameraOperation.STATUS !in viewModel.uiState.value.pendingOperations
        })
        assertNull(viewModel.uiState.value.error)
        assertEquals(87, viewModel.uiState.value.status?.batteryLevel)
        assertEquals(oldReads, oldPeer.requests.size)
        assertTrue(newPeer.requests.count { it.path.endsWith("/devicestatus/battery") } > newBatteryReads)
        assertReadOnlyRequests()
    }

    private fun currentAttemptJobs(): List<Job> =
        requireNotNull(viewModel.viewModelScope.coroutineContext[Job]).children.toList().also {
            assertTrue("The gated attempt must still own an attached coroutine", it.isNotEmpty())
        }

    private suspend fun awaitAttempt(jobs: List<Job>) {
        assertTrue("The captured attempt never finished after releasing its response", pumpUntil {
            jobs.all { it.isCompleted }
        })
        main.scheduler.runCurrent()
        assertTrue("Completed attempts must release pending controls", viewModel.uiState.value.pendingOperations.isEmpty())
    }

    private fun assertReadOnlyRequests() {
        assertTrue("Configuration/retry fixtures must not send camera commands",
            (oldPeer.requests + newPeer.requests).all { it.method == "GET" })
    }

    private suspend fun pumpUntil(timeoutMillis: Long = 4_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            main.scheduler.runCurrent()
            if (condition()) return true
            delay(10)
        }
        main.scheduler.runCurrent()
        return condition()
    }
}

private class ConnectionConfigurationPeer(label: String, private val batteryLevel: Int) {
    val server = MockWebServer()
    lateinit var baseUrl: String
    val cameraId = "synthetic-camera-$label"
    val model = "Synthetic connection $label"
    val serial = "TEST-CONNECTION-$label"
    data class Request(val method: String?, val path: String, val authorization: String?)
    val requests = CopyOnWriteArrayList<Request>()
    class Gate(val path: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
    @Volatile var intercept: ((RecordedRequest) -> MockResponse?)? = null
    private val nextGate = AtomicReference<Gate?>()
    private val gates = CopyOnWriteArrayList<Gate>()

    fun gateNext(path: String): Gate = Gate(path).also {
        check(nextGate.compareAndSet(null, it)) { "A response gate is already configured" }
        gates += it
    }

    fun releaseAllGates() = gates.forEach { it.release.countDown() }

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                requests += Request(request.method, path, request.getHeader("Authorization"))
                // Snapshot the response before the edit, then release this exact old result.
                val response = intercept?.invoke(request) ?: when {
                    path == "/health" -> json("""{"service":"open-eos-control-bridge"}""")
                    path == "/v1/cameras" -> json("""{"cameras":[{"id":"$cameraId","model":"$model","port":"synthetic:001","engine":"libgphoto2"}]}""")
                    path == "/ccapi" -> json("""{"ver110":[{"path":"/deviceinformation","get":true},{"path":"/devicestatus/battery","get":true},{"path":"/shooting/settings","get":true}]}""")
                    path.endsWith("/deviceinformation") -> json(JSONObject()
                        .put("productname", model).put("serialnumber", serial).toString())
                    path.endsWith("/devicestatus/battery") -> json("""{"level":"$batteryLevel"}""")
                    path.endsWith("/shooting/settings") -> json("{}")
                    else -> MockResponse().setResponseCode(404)
                }
                val gate = nextGate.get()?.takeIf { it.path == path }
                if (gate != null && nextGate.compareAndSet(gate, null)) {
                    gate.entered.countDown()
                    check(gate.release.await(8, TimeUnit.SECONDS)) { "Synthetic response gate was not released" }
                }
                return response
            }
        }
        server.start()
        baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
