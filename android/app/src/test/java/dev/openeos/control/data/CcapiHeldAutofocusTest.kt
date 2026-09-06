package dev.openeos.control.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

class CcapiHeldAutofocusTest {
    private val server = MockWebServer()
    private val actions = CopyOnWriteArrayList<String>()
    private var advertiseAf = true
    private var rejectStart = false
    private var rejectStop = false
    private lateinit var client: CcapiClient

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/ccapi") {
                    val af = if (advertiseAf) """,{"path":"/shooting/control/af","post":true}""" else ""
                    return MockResponse().setBody("""{"ver120":[{"path":"/shooting/control/shutterbutton/manual","post":true}$af]}""")
                }
                if (request.method == "POST") {
                    assertEquals("/ccapi/ver120/shooting/control/af", request.path)
                    val action = JSONObject(request.body.readUtf8()).getString("action")
                    actions += action
                    if ((action == "start" && rejectStart) || (action == "stop" && rejectStop)) {
                        return MockResponse().setResponseCode(503)
                    }
                    return MockResponse().setResponseCode(204)
                }
                return MockResponse().setBody("{}")
            }
        }
        server.start()
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun holdsAdvertisedVersionUntilReleasedWithoutFixedPulseOrStatusRead() = runBlocking {
        client.initialize()
        assertTrue(client.capabilities().heldAutofocusSupported)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) { client.holdAutofocus { started.complete(Unit); release.await() } }
        started.await()
        assertEquals(listOf("start"), actions.toList())
        release.complete(Unit)
        job.join()
        assertEquals(listOf("start", "stop"), actions.toList())
    }

    @Test fun manualHalfPressAloneDoesNotAdvertiseOrExecuteHeldAf() = runBlocking {
        advertiseAf = false
        client.initialize()
        assertFalse(client.capabilities().heldAutofocusSupported)
        assertTrue(runCatching { client.holdAutofocus {} }.isFailure)
        assertTrue(actions.isEmpty())
    }

    @Test fun rejectedStartStillStopsAndFailedStopCanBeRetriedWithoutAnotherStart() = runBlocking {
        client.initialize()
        rejectStart = true
        rejectStop = true
        assertTrue(runCatching { client.holdAutofocus {} }.exceptionOrNull() is AutofocusReleaseException)
        rejectStop = false
        client.retryAutofocusStop()
        client.retryAutofocusStop()
        assertEquals(listOf("start", "stop", "stop"), actions.toList())
    }
}
