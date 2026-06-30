package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: CameraRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = CameraRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun connectLoadsCameraSessionAndInitialLiveViewFrame() = runTest {
        server.enqueue(jsonResponse(INFO_JSON))
        server.enqueue(jsonResponse(STATUS_JSON))
        server.enqueue(jsonResponse(CAPABILITIES_JSON))

        val session = repository.connect(server.url("/").toString())

        assertEquals("Canon EOS R6 Mark III", session.info.model)
        assertEquals("800", session.status.exposure.iso)
        assertEquals(listOf("100", "800", "1600"), session.capabilities.iso)
        assertTrue(session.liveViewFrameUrl.endsWith("/ccapi/liveview/frame?t=1"))
        assertEquals("/ccapi/info", server.takeRequest().path)
        assertEquals("/ccapi/status", server.takeRequest().path)
        assertEquals("/ccapi/capabilities", server.takeRequest().path)
    }

    @Test
    fun nextLiveViewFrameUrlIncrementsCacheKeyAfterConnect() = runTest {
        server.enqueue(jsonResponse(INFO_JSON))
        server.enqueue(jsonResponse(STATUS_JSON))
        server.enqueue(jsonResponse(CAPABILITIES_JSON))

        val session = repository.connect(server.url("/").toString())
        val nextFrame = repository.nextLiveViewFrameUrl()

        assertTrue(session.liveViewFrameUrl.endsWith("t=1"))
        assertTrue(nextFrame.endsWith("t=2"))
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setHeader("content-type", "application/json")
            .setBody(body)

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
    }
}
