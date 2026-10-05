package dev.openeos.control.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.ConnectionFailureReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real OpenEosControlApp -> production VM/repository -> native CCAPI HTTP fixture.
 * These two cases verify protocol and UI behavior, not physical camera compatibility.
 */
class CameraConnectionRecoverySessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private val repository = CameraRepository()
    private val store = ViewModelStore()
    private lateinit var viewModel: CameraViewModel
    private val requireAuthentication = AtomicBoolean(false)
    private val blockIdentityOnce = AtomicBoolean(false)
    private val identityEntered = CountDownLatch(1)
    private val releaseIdentity = CountDownLatch(1)
    private data class Request(val method: String?, val path: String, val authorization: String?)
    private val requests = CopyOnWriteArrayList<Request>()
    private val oldIdentityReturned = AtomicBoolean(false)

    @Before fun setUp() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Initialize HTTP fixtures off the UI thread." }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                requests += Request(request.method, path, request.getHeader("Authorization"))
                if (requireAuthentication.get() && request.getHeader("Authorization") != CORRECT_AUTHORIZATION) {
                    return MockResponse().setResponseCode(if (path == "/ccapi") 401 else 404)
                        .setBody("SYNTHETIC-PRIVATE-RESPONSE")
                }
                if (path.endsWith("/deviceinformation") && blockIdentityOnce.compareAndSet(true, false)) {
                    identityEntered.countDown()
                    try {
                        check(releaseIdentity.await(15, TimeUnit.SECONDS)) { "Identity response gate was not released." }
                    } finally { oldIdentityReturned.set(true) }
                }
                return when (path) {
                    "/ccapi" -> json("""{"ver110":[{"path":"/deviceinformation","get":true},{"path":"/devicestatus/battery","get":true},{"path":"/shooting/settings","get":true}]}""")
                    "/ccapi/ver110/deviceinformation" -> json("""{"productname":"Synthetic recovery camera","serialnumber":"TEST-RECOVERY-0001"}""")
                    "/ccapi/ver110/devicestatus/battery" -> json("""{"level":"87"}""")
                    "/ccapi/ver110/shooting/settings" -> json("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        // URL/hostname resolution is complete before any Compose content or UI callback.
        val baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString()
        compose.runOnIdle {
            viewModel = CameraViewModel(repository)
            store.put("connection-recovery", viewModel)
        }
        compose.setContent { OpenEosControlApp(viewModel) }
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT) { !viewModel.uiState.value.busy }
        compose.runOnIdle {
            viewModel.useDirectCameraPreset()
            viewModel.setBaseUrl(baseUrl)
            viewModel.setUsername("")
            viewModel.setPassword("")
            viewModel.setLiveViewAutoRefresh(false)
        }
    }

    @After fun tearDown() {
        releaseIdentity.countDown()
        requireAuthentication.set(false)
        try {
            if (::viewModel.isInitialized) {
                val scopeJob = requireNotNull(viewModel.viewModelScope.coroutineContext[Job])
                compose.runOnIdle { store.clear() }
                compose.waitUntil(TIMEOUT) { scopeJob.isCompleted }
                runBlocking { withTimeout(TIMEOUT) { repository.disconnect() } }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun rejectedNativeAuthenticationOpensFieldsAndRequiresManualRetryAfterEditing() {
        requireAuthentication.set(true)
        // The real app may remember a username from a previous instrumented session.
        // Start collapsed explicitly so this test proves the failure opens authentication.
        if (compose.onAllNodesWithTag("connection-username").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("connection-authentication-toggle").performScrollTo().performClick()
        }
        compose.onNodeWithTag("connection-username").assertDoesNotExist()
        compose.runOnIdle {
            viewModel.setUsername("synthetic-rejected-user")
            viewModel.setPassword("synthetic-rejected-password")
        }
        compose.onNodeWithTag("connection-connect").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) { viewModel.uiState.value.connectionRecovery != null && !viewModel.uiState.value.busy }
        assertEquals(ConnectionRecovery(ConnectionAttemptTarget.CCAPI, ConnectionFailureReason.AUTHENTICATION_REJECTED),
            viewModel.uiState.value.connectionRecovery)
        assertFalse(viewModel.uiState.value.error.orEmpty().contains("SYNTHETIC-PRIVATE"))
        compose.onNodeWithText("SYNTHETIC-PRIVATE-RESPONSE").assertDoesNotExist()
        compose.onNodeWithTag("connection-recovery-dismiss").performScrollTo().assertIsDisplayed()
        val failedRequests = requests.size
        assertTrue(requests.any { it.authorization == Credentials.basic("synthetic-rejected-user", "synthetic-rejected-password") })
        compose.onNodeWithTag("connection-username").performScrollTo().assertIsDisplayed()
            .performTextReplacement("synthetic-corrected-user")
        compose.onNodeWithTag("connection-password").performScrollTo().assertIsDisplayed()
            .performTextReplacement("synthetic-corrected-password")
        compose.onNodeWithTag("connection-recovery").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(viewModel.uiState.value.connected)
            assertTrue(viewModel.uiState.value.pendingOperations.isEmpty())
            assertEquals(failedRequests, requests.size)
        }
        compose.onNodeWithTag("connection-connect").performScrollTo().assertIsEnabled().performClick()
        assertConnected()
        assertTrue(requests.any { it.path == "/ccapi/ver110/deviceinformation" && it.authorization == CORRECT_AUTHORIZATION })
    }

    @Test fun cancelButtonAbandonsHeldIdentityAndManualReconnectStartsFresh() {
        blockIdentityOnce.set(true)
        compose.onNodeWithTag("connection-connect").performScrollTo().performClick()
        assertTrue(identityEntered.await(15, TimeUnit.SECONDS))
        val attemptJobs = requireNotNull(viewModel.viewModelScope.coroutineContext[Job]).children.toList()
        assertTrue(attemptJobs.isNotEmpty())
        val heldRequests = requests.size
        compose.onNodeWithTag("connection-cancel").assertIsDisplayed().assertIsEnabled()
            .performTouchInput { click(center) }
        compose.waitUntil(TIMEOUT) { attemptJobs.all { it.isCompleted } && viewModel.uiState.value.pendingOperations.isEmpty() }
        assertFalse("Cancel must finish before the old server response is released", oldIdentityReturned.get())
        assertEquals(1L, releaseIdentity.count)
        compose.onNodeWithTag("connection-cancel").assertDoesNotExist()
        assertFalse(viewModel.uiState.value.connected)
        assertNull(viewModel.uiState.value.info)
        assertNull(viewModel.uiState.value.connectionRecovery)
        assertNull(viewModel.uiState.value.error)
        assertEquals(heldRequests, requests.size)
        compose.onNodeWithTag("connection-connect").performScrollTo().assertIsEnabled().performClick()
        assertConnected()
        assertEquals(2, requests.count { it.path == "/ccapi/ver110/deviceinformation" })
        assertFalse("A fresh connection must not wait for the abandoned response", oldIdentityReturned.get())
        assertEquals(1L, releaseIdentity.count)
        releaseIdentity.countDown()
    }

    private fun assertConnected() {
        compose.waitUntil(TIMEOUT) { viewModel.uiState.value.connected && !viewModel.uiState.value.busy }
        compose.onNodeWithTag("camera-model-status").assertIsDisplayed()
        assertEquals("Synthetic recovery camera", viewModel.uiState.value.info?.model)
        assertEquals(87, viewModel.uiState.value.status?.batteryLevel)
        assertNull(viewModel.uiState.value.error)
        assertTrue("Connection recovery must not send camera commands", requests.all { it.method == "GET" })
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val TIMEOUT = 15_000L
        val CORRECT_AUTHORIZATION = Credentials.basic("synthetic-corrected-user", "synthetic-corrected-password")
    }
}
