package dev.openeos.control.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CcapiFocusReleaseRecoveryTest {
    private val server = MockWebServer()
    private val writes = CopyOnWriteArrayList<String>()
    private var nativeAf = true
    private var manualMethod = "POST"
    private var rejectStart = false
    private var rejectRelease = true
    private lateinit var client: CcapiClient

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/ccapi") {
                    val af = if (nativeAf) """,{"path":"/shooting/control/af","post":true}""" else ""
                    return MockResponse().setBody("""{"ver120":[
                        {"path":"/shooting/control/shutterbutton/manual","${manualMethod.lowercase()}":true}$af]}""")
                }
                if (request.method != "GET") {
                    val manual = request.path!!.endsWith("/shutterbutton/manual")
                    assertEquals(if (manual) manualMethod else "POST", request.method)
                    assertEquals("/ccapi/ver120/shooting/control/${if (manual) "shutterbutton/manual" else "af"}", request.path)
                    val body = JSONObject(request.body.readUtf8())
                    val action = body.getString("action")
                    writes += "${request.method}:${if (manual) "manual" else "af"}:$action"
                    if (manual) assertEquals(action != "release", body.getBoolean("af"))
                    val releasing = action == "stop" || action == "release"
                    return MockResponse().setResponseCode(if (if (releasing) rejectRelease else rejectStart) 503 else 204)
                }
                return MockResponse().setBody("{}")
            }
        }
        server.start()
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun boundedNativeAfRetainsFailedStopForStopOnlyRetry() = runBlocking {
        client.initialize()
        assertTrue(runCatching { client.autofocus() }.exceptionOrNull() is AutofocusReleaseException)
        assertFalse(CameraFeature.AUTOFOCUS in client.observedFeatureSnapshot())
        assertTrue(runCatching { client.autofocus() }.isFailure)
        assertEquals(listOf("POST:af:start", "POST:af:stop"), writes.toList())
        rejectRelease = false
        client.retryAutofocusStop()
        client.retryAutofocusStop()
        assertEquals(listOf("POST:af:start", "POST:af:stop", "POST:af:stop"), writes.toList())
        client.autofocus()
        assertTrue(CameraFeature.AUTOFOCUS in client.observedFeatureSnapshot())
    }

    @Test fun manualPostHalfPressRetainsReleaseWithoutAdvertisingHeldAf() = runBlocking {
        nativeAf = false
        client.initialize()
        assertFalse(client.capabilities().heldAutofocusSupported)
        assertTrue(runCatching { client.halfPressShutter() }.exceptionOrNull() is AutofocusReleaseException)
        assertFalse(CameraFeature.SHUTTER_HALF_PRESS in client.observedFeatureSnapshot())
        rejectRelease = false
        client.retryAutofocusStop()
        assertEquals(listOf("POST:manual:half_press", "POST:manual:release", "POST:manual:release"), writes.toList())
    }

    @Test fun manualPutAutofocusFallbackRetriesTheOriginalRelease() = runBlocking {
        nativeAf = false
        manualMethod = "PUT"
        client.initialize()
        assertTrue(runCatching { client.autofocus() }.exceptionOrNull() is AutofocusReleaseException)
        rejectRelease = false
        client.retryAutofocusStop()
        assertEquals(listOf("PUT:manual:half_press", "PUT:manual:release", "PUT:manual:release"), writes.toList())
    }

    @Test fun failedStartAndReleasePreserveBothFailuresAndCloseRetriesOnlyRelease() = runBlocking {
        client.initialize()
        rejectStart = true
        val error = runCatching { client.autofocus() }.exceptionOrNull()
        assertTrue(error is AutofocusReleaseException)
        assertEquals(1, error!!.suppressed.size)
        rejectRelease = false
        client.close()
        assertEquals(listOf("POST:af:start", "POST:af:stop", "POST:af:stop"), writes.toList())
        assertFalse(CameraFeature.AUTOFOCUS in client.observedFeatureSnapshot())
    }
}
