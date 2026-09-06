package dev.openeos.control.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CameraFocusInfoClientTest {
    private val server = MockWebServer()
    private lateinit var client: CcapiClient

    @Before fun setUp() {
        server.start()
        client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
    }
    @After fun tearDown() { server.shutdown() }

    private suspend fun initialize(advertise: Boolean = true) {
        val detail = if (advertise) """,{"path":"/shooting/liveview/flipdetail","get":true}""" else ""
        server.enqueue(MockResponse().setBody("""{"ver110":[
            {"path":"/shooting/liveview","post":true,"delete":true},
            {"path":"/shooting/liveview/flip","get":true}$detail]}"""))
        client.initialize()
        server.takeRequest()
    }

    @Test fun readsOnlyAdvertisedInfoPacketWhileLiveViewIsActive() = runBlocking {
        initialize()
        assertNull(client.liveViewFocusInfo())
        server.enqueue(MockResponse().setBody("{}"))
        client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_JPEG_POLLING))
        server.takeRequest()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
            .setBody(Buffer().write(focusInfoPacket(focusInfoJson()))))
        assertEquals(CameraFocusStatus.FOCUSED, client.liveViewFocusInfo()!!.frames.single().status)
        val read = server.takeRequest()
        assertEquals("GET", read.method)
        assertEquals("/ccapi/ver110/shooting/liveview/flipdetail?kind=info", read.path)
        server.enqueue(MockResponse().setBody("{}"))
        client.stopLiveView()
        server.takeRequest()
        assertNull(client.liveViewFocusInfo())
        assertEquals(4, server.requestCount)
    }

    @Test fun doesNotProbeMissingDetailedEndpoint() = runBlocking {
        initialize(advertise = false)
        server.enqueue(MockResponse().setBody("{}"))
        client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_JPEG_POLLING))
        server.takeRequest()
        assertNull(client.liveViewFocusInfo())
        assertEquals(2, server.requestCount)
    }

    @Test fun rejectedOrMalformedMetadataCannotProduceFocusSuccess() = runBlocking {
        initialize()
        server.enqueue(MockResponse().setBody("{}"))
        client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_JPEG_POLLING))
        server.takeRequest()
        for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setBody("not metadata"))) {
            server.enqueue(response)
            assertTrue(runCatching { client.liveViewFocusInfo() }.isFailure)
            server.takeRequest()
            assertEquals(LiveViewSource.CCAPI_JPEG_POLLING, client.currentLiveViewSource())
        }
    }
}
