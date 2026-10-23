package dev.openeos.control.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.DownloadHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Real preferences and recreated VMs, followed by the production Android HTTP route.
 * This is synthetic protocol-fixture evidence, not physical-camera validation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class CameraConnectionPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val main = StandardTestDispatcher()
    private val historyJob = SupervisorJob()
    private val peer = ConnectionPersistencePeer()
    private val owners = mutableListOf<Owner>()
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var history: DownloadHistoryStore

    private class Owner(val repository: CameraRepository, val model: CameraViewModel) {
        val store = ViewModelStore().apply { put("synthetic-connection-persistence", model) }
    }

    @Before fun setUp() = runBlocking {
        assertEquals("This fixture requires the existing SDK 35 runtime", 35, android.os.Build.VERSION.SDK_INT)
        Dispatchers.setMain(main)
        context = RuntimeEnvironment.getApplication()
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        // Keep initialize's real history implementation, but own its worker in this fixture.
        history = DownloadHistoryStore(temporary.newFolder("history"), CoroutineScope(historyJob + Dispatchers.IO))
        withContext(Dispatchers.IO) { peer.start() }
    }

    @After fun tearDown() = runBlocking {
        var failure: Throwable? = null
        for (owner in owners.toList().asReversed()) {
            runCatching { dispose(owner) }.exceptionOrNull()?.let { error ->
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        try {
            historyJob.cancel()
            assertTrue("The real history worker must finish", pumpUntil { historyJob.isCompleted })
        } finally {
            try {
                withContext(Dispatchers.IO) { peer.server.shutdown() }
            } finally {
                try { preferences.edit().clear().commit() } finally { Dispatchers.resetMain() }
            }
        }
        failure?.let { throw it }
        Unit // JUnit @After must compile to void; preserve the original failure rethrow.
    }

    @Test fun savedSimulatorModeSurvivesRecreationAndUsesSimulatorProtocol() = runBlocking {
        val original = newOwner()
        original.model.useDevSimulatorPreset()
        original.model.setBaseUrl(peer.baseUrl)
        original.model.rememberConnection(context)
        dispose(original)

        val restored = newOwner()
        assertEquals("B3_TRUE_MODE_RESTORE: explicit simulator mode was lost", true,
            restored.model.uiState.value.ccapiSimulatorMode)
        assertEquals(peer.baseUrl, restored.model.uiState.value.baseUrl)
        assertEquals(true, preferences.all[KEY_MODE])
        assertNotAutoConnected(restored)

        connectExplicitly(restored)
        assertEquals("Synthetic persisted simulator", restored.model.uiState.value.info?.model)
        assertEquals(73, restored.model.uiState.value.status?.batteryLevel)
        assertFalse(restored.repository.isRealCamera())
        assertTrue(peer.paths.containsAll(listOf("/ccapi/info", "/ccapi/status", "/ccapi/capabilities")))
        assertFalse("Simulator mode must not perform native discovery", "/ccapi" in peer.paths)
        assertFalse(peer.paths.any { it.startsWith("/ccapi/ver") })
        assertReadOnlyRequests()
    }

    @Test fun savedNativeModeSurvivesRecreationAndOverridesLoopbackHeuristic() = runBlocking {
        val original = newOwner()
        original.model.useDirectCameraPreset()
        original.model.setBaseUrl(peer.baseUrl)
        original.model.rememberConnection(context)
        dispose(original)

        val restored = newOwner()
        assertEquals("B3_FALSE_MODE_RESTORE: explicit native mode was lost", false,
            restored.model.uiState.value.ccapiSimulatorMode)
        assertEquals(peer.baseUrl, restored.model.uiState.value.baseUrl)
        assertEquals(false, preferences.all[KEY_MODE])
        assertNotAutoConnected(restored)

        connectExplicitly(restored)
        assertNativeProtocol(restored)
        assertReadOnlyRequests()
    }

    @Test fun savingSameUrlAgainRestoresLatestProtocolMode() = runBlocking {
        val original = newOwner()
        original.model.useDevSimulatorPreset()
        original.model.setBaseUrl(peer.baseUrl)
        original.model.rememberConnection(context)
        val firstSavedUrl = preferences.getString(KEY_URL, null)
        original.model.useDirectCameraPreset()
        original.model.setBaseUrl(peer.baseUrl)
        original.model.rememberConnection(context)
        dispose(original)

        val restored = newOwner()
        assertEquals("B3_LATEST_MODE_RESTORE: same-URL save lost the latest mode", false,
            restored.model.uiState.value.ccapiSimulatorMode)
        assertEquals(firstSavedUrl, restored.model.uiState.value.baseUrl)
        assertEquals(peer.baseUrl, firstSavedUrl)
        assertEquals(firstSavedUrl, preferences.all[KEY_URL])
        assertEquals(false, preferences.all[KEY_MODE])
        assertNotAutoConnected(restored)
        connectExplicitly(restored)
        assertNativeProtocol(restored)
        dispose(restored)

        // A nullable/default VM must remove a stale explicit choice, not persist false or
        // leave an earlier true behind. Use real preferences, not a fake editor or setter.
        assertTrue(preferences.edit().remove(KEY_MODE).commit())
        val automatic = newOwner()
        assertNull(automatic.model.uiState.value.ccapiSimulatorMode)
        assertTrue(preferences.edit().putBoolean(KEY_MODE, true).commit())
        automatic.model.rememberConnection(context)
        assertFalse("Saving null must remove a stale mode key", preferences.contains(KEY_MODE))
        assertEquals(peer.baseUrl, preferences.getString(KEY_URL, null))
        dispose(automatic)
        val requestCount = peer.requests.size
        val defaultRestored = newOwner()
        assertNull(defaultRestored.model.uiState.value.ccapiSimulatorMode)
        assertEquals(peer.baseUrl, defaultRestored.model.uiState.value.baseUrl)
        assertNotAutoConnected(defaultRestored, requestCount)
        assertReadOnlyRequests()
    }

    @Test fun legacySettingsWithoutModePreserveDefaultsAndOtherFields() = runBlocking {
        seedLegacySettings()
        assertFalse(preferences.contains(KEY_MODE))
        val restored = newOwner()
        assertNull("An absent legacy key must preserve automatic mode", restored.model.uiState.value.ccapiSimulatorMode)
        assertLegacyFields(restored)
        assertNotAutoConnected(restored)
        restored.model.rememberConnection(context)
        assertFalse("Saving the default must keep the mode key absent", preferences.contains(KEY_MODE))
        dispose(restored)

        val preset = newOwner { it.useDevSimulatorPreset() }
        assertEquals("An absent key must preserve an already selected default", true,
            preset.model.uiState.value.ccapiSimulatorMode)
        assertLegacyFields(preset)
        assertNotAutoConnected(preset)
    }

    @Test fun repeatedInitializationPreservesUnsavedEditsAndNeverConnects() = runBlocking {
        seedLegacySettings()
        val owner = newOwner()
        assertNotAutoConnected(owner)
        owner.model.useDevSimulatorPreset()
        owner.model.setBaseUrl(peer.baseUrl + "unsaved-camera/")
        owner.model.setUsername("synthetic-unsaved-user")
        owner.model.setBridgeBaseUrl(peer.baseUrl + "unsaved-bridge/")
        owner.model.setConnectionTarget(ConnectionTarget.CCAPI)
        val edited = owner.model.uiState.value
        // A second initialize must not reread even a subsequently changed preference file.
        assertTrue(preferences.edit().putBoolean(KEY_MODE, false)
            .putString(KEY_URL, peer.baseUrl + "newly-stored-camera/").commit())
        owner.model.initialize(context)
        owner.model.initialize(context)

        val actual = owner.model.uiState.value
        assertEquals(edited.baseUrl, actual.baseUrl)
        assertEquals(true, actual.ccapiSimulatorMode)
        assertEquals(edited.username, actual.username)
        assertEquals(edited.bridgeBaseUrl, actual.bridgeBaseUrl)
        assertEquals(ConnectionTarget.CCAPI, actual.connectionTarget)
        assertEquals(false, preferences.all[KEY_MODE])
        assertEquals(peer.baseUrl + "newly-stored-camera/", preferences.getString(KEY_URL, null))
        assertNotAutoConnected(owner)
    }

    @Test fun invalidStoredModeTypeFallsBackSafelyWithoutChangingOtherFields() = runBlocking {
        seedLegacySettings()
        assertTrue(preferences.edit().putString(KEY_MODE, "true").commit())
        val restored = newOwner()
        assertNull("A stored string is not a Boolean choice", restored.model.uiState.value.ccapiSimulatorMode)
        assertLegacyFields(restored)
        assertEquals("Initialization must not rewrite malformed storage", "true", preferences.all[KEY_MODE])
        assertNotAutoConnected(restored)
        dispose(restored)

        val preset = newOwner { it.useDirectCameraPreset() }
        assertEquals("A malformed key must preserve the existing default", false,
            preset.model.uiState.value.ccapiSimulatorMode)
        assertLegacyFields(preset)
        assertEquals("true", preferences.all[KEY_MODE])
        assertNotAutoConnected(preset)
    }

    private fun newOwner(beforeInitialize: (CameraViewModel) -> Unit = {}): Owner {
        // initialize intentionally installs AndroidCameraHttpTransportFactory. Do not inject
        // a factory that it replaces or stub routing: the peer uses the real loopback route.
        val repository = CameraRepository()
        val model = CameraViewModel(repository, downloadHistoryFactory = { history })
        val owner = Owner(repository, model).also { owners += it }
        model.setLiveViewAutoRefresh(false)
        beforeInitialize(model)
        model.initialize(context)
        return owner
    }

    private suspend fun assertNotAutoConnected(owner: Owner, expectedRequests: Int = 0) {
        assertTrue("Initialization did not settle", pumpUntil {
            owner.model.uiState.value.pendingOperations.isEmpty() && !history.state.value.loading
        })
        assertFalse(owner.model.uiState.value.connected)
        assertNull(owner.model.uiState.value.info)
        assertNull(owner.model.uiState.value.error)
        assertEquals("Initialize/save/edit must never start HTTP", expectedRequests, peer.requests.size)
    }

    private suspend fun connectExplicitly(owner: Owner) {
        owner.model.connect()
        assertTrue("An explicit connect did not settle", pumpUntil {
            owner.model.uiState.value.pendingOperations.isEmpty()
        })
        assertTrue("Explicit connect failed: ${owner.model.uiState.value.error}", owner.model.uiState.value.connected)
        assertNull(owner.model.uiState.value.error)
    }

    private fun assertNativeProtocol(owner: Owner) {
        assertEquals("Synthetic persisted native", owner.model.uiState.value.info?.model)
        assertEquals(41, owner.model.uiState.value.status?.batteryLevel)
        assertTrue(owner.repository.isRealCamera())
        assertEquals("/ccapi", peer.paths.first())
        assertTrue(peer.paths.containsAll(listOf("/ccapi/ver110/deviceinformation", "/ccapi/ver110/devicestatus/battery")))
        assertFalse("Explicit native mode must override the loopback simulator heuristic", "/ccapi/info" in peer.paths)
        assertFalse("/ccapi/status" in peer.paths)
        assertFalse("/ccapi/capabilities" in peer.paths)
    }

    private fun seedLegacySettings() {
        assertTrue(preferences.edit().putString(KEY_URL, peer.baseUrl)
            .putString(KEY_USERNAME, "synthetic-legacy-user")
            .putString(KEY_BRIDGE_URL, peer.baseUrl + "legacy-bridge/")
            .putString(KEY_TARGET, ConnectionTarget.DESKTOP_BRIDGE.name).commit())
    }

    private fun assertLegacyFields(owner: Owner) {
        val state = owner.model.uiState.value
        assertEquals(peer.baseUrl, state.baseUrl)
        assertEquals("synthetic-legacy-user", state.username)
        assertEquals(peer.baseUrl + "legacy-bridge/", state.bridgeBaseUrl)
        assertEquals(ConnectionTarget.DESKTOP_BRIDGE, state.connectionTarget)
        assertEquals("", state.password)
        assertEquals("", state.bridgeToken)
    }

    private fun assertReadOnlyRequests() {
        assertTrue("Persistence fixtures must not send camera commands", peer.requests.all { it.method == "GET" })
    }

    private suspend fun dispose(owner: Owner) {
        if (!owners.remove(owner)) return
        try {
            owner.model.disconnect()
            assertTrue("Production disconnect must finish", pumpUntil {
                !owner.model.uiState.value.connectionTeardownPending
            })
        } finally {
            try {
                owner.model.cancelAndAwaitTestScope(main)
            } finally {
                // Exercise real onCleared, including preference-listener removal.
                owner.store.clear()
                coroutineScope {
                    val cleanup = async(start = CoroutineStart.UNDISPATCHED) { owner.repository.disconnect() }
                    try {
                        assertTrue("Repository cleanup must finish", pumpUntil { cleanup.isCompleted })
                        cleanup.await()
                    } finally { cleanup.cancel() }
                }
                main.scheduler.runCurrent()
            }
        }
        assertFalse(owner.repository.isLiveViewRunning())
        assertFalse(owner.repository.isLiveViewStopRequired())
    }

    private suspend fun pumpUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            main.scheduler.runCurrent()
            if (condition()) return true
            // Yield to real HTTP/history continuations; no sleep, virtual-time jump or retry.
            yield()
        }
        main.scheduler.runCurrent()
        return condition()
    }

    private companion object {
        const val PREFERENCES_NAME = "camera_connection"
        const val KEY_MODE = "ccapi_simulator_mode"
        const val KEY_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_BRIDGE_URL = "bridge_base_url"
        const val KEY_TARGET = "connection_target"
    }
}

private class ConnectionPersistencePeer {
    val server = MockWebServer()
    lateinit var baseUrl: String
    data class Request(val method: String?, val path: String)
    val requests = CopyOnWriteArrayList<Request>()
    val paths: List<String> get() = requests.map { it.path }

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                requests += Request(request.method, path)
                return when (path) {
                    "/ccapi" -> json("""{"ver110":[{"path":"/deviceinformation","get":true},{"path":"/devicestatus/battery","get":true},{"path":"/shooting/settings","get":true}]}""")
                    "/ccapi/ver110/deviceinformation" -> json("""{"productname":"Synthetic persisted native","serialnumber":"TEST-PERSISTENCE-NATIVE"}""")
                    "/ccapi/ver110/devicestatus/battery" -> json("""{"level":"41"}""")
                    "/ccapi/ver110/shooting/settings" -> json("{}")
                    "/ccapi/info" -> json("""{"connected":true,"model":"Synthetic persisted simulator","serial":"TEST-PERSISTENCE-SIMULATOR","api":"simulator"}""")
                    "/ccapi/status" -> json("""{"connected":true,"recording":false,"mode":"photo","battery":{"level":73,"status":"normal"},"media":{"available":true},"exposure":{"iso":"100","shutter":"1/125","aperture":"4.0","white_balance":"auto"}}""")
                    "/ccapi/capabilities" -> json("""{"iso":["100"],"shutter":["1/125"],"aperture":["4.0"],"white_balance":["auto"]}""")
                    // The real event call is cancelled by disconnect, not a timed fixture sleep.
                    "/ccapi/events" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    "/ccapi/media" -> json("""{"items":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
