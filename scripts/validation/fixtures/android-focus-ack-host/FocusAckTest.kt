package dev.openeos.control.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/** Exact production command entries and receipt boundary; synthetic transport and geometry. */
class FocusAckTest {
    @Test fun drive206CannotSucceed() = runBlocking { rejected(0, partials()[0]) }
    @Test fun driveRangeCannotSucceed() = runBlocking { rejected(0, partials()[1]) }
    @Test fun driveEmptyRangeCannotSucceed() = runBlocking { rejected(0, partials()[2]) }
    @Test fun tap206CannotSucceed() = runBlocking { rejected(1, partials()[0]) }
    @Test fun tapRangeCannotSucceed() = runBlocking { rejected(1, partials()[1]) }
    @Test fun tapEmptyRangeCannotSucceed() = runBlocking { rejected(1, partials()[2]) }
    @Test fun whiteBalance206CannotSucceed() = runBlocking { rejected(2, partials()[0]) }
    @Test fun whiteBalanceRangeCannotSucceed() = runBlocking { rejected(2, partials()[1]) }
    @Test fun whiteBalanceEmptyRangeCannotSucceed() = runBlocking { rejected(2, partials()[2]) }

    private suspend fun rejected(route: Int, body: Body) {
        val client = Client(listOf(body))
        val failure = runCatching { invoke(client, route) }.exceptionOrNull()
        assertNotNull("Original partial ACK must not publish success", failure)
        assertFalse(failure is CcapiHttpException)
        assertTrue(failure!!.message!!.contains("complete control acknowledgement"))
        assertFalse(features[route] in client.observedFeatures)
        assertEquals(0, client.statusReads)
        assertSingleOriginal(client, route)
        client.status()
        assertEquals(1, client.requests.size) // Read-only observation never replays the command.
    }

    @Test fun completeSuccessBodiesAndStatusesRemainAccepted() = runBlocking {
        for (route in 0..2) for ((code, text) in listOf(200 to "{}", 204 to "", 200 to "Accepted", 200 to "", 202 to "Accepted")) {
            val client = Client(listOf(Body(status = code, text = text)))
            invoke(client, route)
            assertTrue(features[route] in client.observedFeatures)
            assertEquals(if (route == 2) 1 else 0, client.statusReads)
            assertSingleOriginal(client, route)
        }
    }

    @Test fun nonSuccessWithRangeKeepsTypedHttpFailureAndBody() = runBlocking {
        for (route in 0..2) {
            val client = Client(listOf(Body(status = 503, text = "busy", responseHeaders = mapOf("Content-Range" to ""))))
            val failure = runCatching { invoke(client, route) }.exceptionOrNull()
            assertTrue(failure is CcapiHttpException)
            assertEquals(503, (failure as CcapiHttpException).statusCode)
            assertTrue(failure.message!!.contains("busy"))
            assertFalse(features[route] in client.observedFeatures)
            assertSingleOriginal(client, route)
        }
    }

    @Test fun methodAndPathScopeLeaveRangesElsewhereUnchanged() = runBlocking {
        for ((method, path) in listOf("GET" to "/shooting/control/drivefocus", "PUT" to "/shooting/control/drivefocus",
            "POST" to "/shooting/liveview/afframeposition", "PUT" to "/shooting/liveview/clickwb",
            "GET" to "/contents/card1/IMAGE.JPG", "POST" to "/unrelated", "POST" to "/shooting/liveview/clickwb/other")) {
            val client = Client(listOf(partials()[0]))
            client.raw(method, "/ccapi/ver120$path")
            assertEquals(1, client.requests.size)
        }
    }

    @Test fun staleSnapshotStillPreventsCoordinateDispatch() = runBlocking {
        for (route in 1..2) {
            val client = Client(listOf(Body()))
            client.liveViewGeometry.valid = false
            assertTrue(runCatching { invoke(client, route) }.isFailure)
            assertTrue(client.requests.isEmpty())
            assertTrue(client.observedFeatures.isEmpty())
        }
    }

    private suspend fun invoke(client: Client, route: Int) {
        when (route) {
            0 -> assertTrue(client.driveFocus(FocusDriveDirection.NEAR, FocusDriveStep.MEDIUM).ok)
            1 -> assertTrue(client.tapFocus(0.4, 0.6).ok)
            2 -> client.clickWhiteBalance(0.4, 0.6)
        }
    }
    private fun assertSingleOriginal(client: Client, route: Int) {
        val request = client.requests.single()
        assertEquals(if (route == 1) "PUT" else "POST", request.method)
        assertTrue(request.url.endsWith(paths[route]))
        assertEquals(if (route == 0) "{\"value\":\"near2\"}" else "{\"positionx\":2500,\"positiony\":2600}", request.body)
        assertEquals(listOf(false), client.bounded) // No new transport/JSON/timeout flag.
        assertEquals(0L, client.bodies.single().exited.count)
        if (route != 0) assertEquals(2, client.liveViewGeometry.checks)
    }
    private fun partials() = listOf(Body(status = 206, text = "{}"),
        Body(status = 200, text = "{}", responseHeaders = mapOf("Content-Range" to "bytes 0-1/4")),
        Body(status = 200, text = "{}", responseHeaders = mapOf("cOnTeNt-RaNgE" to "")))
    private val features = listOf(CameraFeature.FOCUS_DRIVE, CameraFeature.TAP_FOCUS, CameraFeature.CLICK_WHITE_BALANCE)
    private val paths = listOf("/shooting/control/drivefocus", "/shooting/liveview/afframeposition", "/shooting/liveview/clickwb")
}
