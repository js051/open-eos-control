package dev.openeos.control.data

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

/** Production AF/Bulb entries and owners; synthetic HTTP response and discovery. */
class PartialControlAckTest {
    @Test fun af206RetainsOriginalStop() = runBlocking { pendingStop(false, partials()[0]) }
    @Test fun afRangeRetainsOriginalStop() = runBlocking { pendingStop(false, partials()[1]) }
    @Test fun afEmptyRangeRetainsOriginalStop() = runBlocking { pendingStop(false, partials()[2]) }
    @Test fun bulb206RetainsOriginalRelease() = runBlocking { pendingStop(true, partials()[0]) }
    @Test fun bulbRangeRetainsOriginalRelease() = runBlocking { pendingStop(true, partials()[1]) }
    @Test fun bulbEmptyRangeRetainsOriginalRelease() = runBlocking { pendingStop(true, partials()[2]) }

    private suspend fun pendingStop(bulb: Boolean, partial: Body) {
        val c = Client(listOf(Body(), Body(status = 503, text = "busy"), partial, Body(), Body(), Body()), manualMethod = "PUT")
        if (bulb) {
            c.startBulbExposure()
            assertTrue(runCatching { c.stopBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
            assertTrue(c.heldShutter.releaseUnconfirmed)
        } else assertTrue(runCatching { c.holdAutofocus {} }.exceptionOrNull() is AutofocusReleaseException)
        val originalStop = c.requests.last()
        c.apiVersionPrefix = "/ccapi/ver999"
        val retry = runCatching { if (bulb) c.retryShutterRelease() else c.retryAutofocusStop() }
        assertTrue("Partial Stop must retain the original release obligation", retry.isFailure)
        if (bulb) {
            assertTrue(c.heldShutter.hasPendingRelease)
            assertTrue(c.heldShutter.releaseUnconfirmed)
            assertTrue(c.bulbExposureActive)
        }
        val before = c.requests.size
        assertTrue(runCatching { if (bulb) c.startBulbExposure() else c.holdAutofocus {} }.isFailure)
        assertEquals(before, c.requests.size)
        if (bulb) c.retryShutterRelease() else c.retryAutofocusStop()
        assertEquals(4, c.requests.size)
        for (stop in c.requests.drop(1)) {
            assertEquals(originalStop.method, stop.method)
            assertEquals(originalStop.url, stop.url)
            assertEquals(originalStop.body, stop.body)
        }
        if (bulb) {
            assertFalse(c.heldShutter.releaseUnconfirmed)
            assertFalse(c.heldShutter.hasPendingRelease)
            assertFalse(c.bulbExposureActive)
            c.retryShutterRelease()
        } else c.retryAutofocusStop()
        assertEquals("Completed release remains cached", 4, c.requests.size)
        if (bulb) { c.startBulbExposure(); c.stopBulbExposure() } else c.holdAutofocus {}
        assertEquals(6, c.requests.size)
        assertTrue(c.requests.take(4).all { "/ver120/" in it.url })
        assertTrue(c.requests.drop(4).all { "/ver999/" in it.url })
    }

    @Test fun legacySuccessBodiesStillReleaseOriginalOwners() = runBlocking {
        for (success in listOf(Body(status = 200, text = "{}"), Body(), Body(status = 200),
            Body(status = 200, text = "Accepted"), Body(status = 202, text = "Accepted"))) {
            val af = Client(listOf(Body(), success)); af.holdAutofocus {}; af.retryAutofocusStop()
            assertEquals(2, af.requests.size)
            val bulb = Client(listOf(Body(), fresh(success))); bulb.startBulbExposure(); bulb.stopBulbExposure(); bulb.retryShutterRelease()
            assertEquals(2, bulb.requests.size)
            assertFalse(bulb.heldShutter.hasPendingRelease)
            assertFalse(bulb.heldShutter.releaseUnconfirmed)
        }
    }

    @Test fun partialStartsStillRunOriginalCleanup() = runBlocking {
        for (partial in partials()) {
            val af = Client(listOf(partial, Body())); var entered = false
            assertTrue("Partial AF Start cannot enter held state", runCatching { af.holdAutofocus { entered = true } }.isFailure)
            assertFalse(entered); assertEquals(listOf("start", "stop"), af.requests.map(::action))
            af.retryAutofocusStop(); assertEquals(2, af.requests.size)
            val bulb = Client(listOf(fresh(partial), Body()))
            assertTrue("Partial Bulb Start cannot acknowledge exposure", runCatching { bulb.startBulbExposure() }.isFailure)
            assertEquals(listOf("full_press", "release"), bulb.requests.map(::action))
            assertFalse(bulb.heldShutter.hasPendingRelease); assertFalse(bulb.bulbExposureActive)
        }
    }
    @Test fun liveViewControlRejectsPartialReceipts() = runBlocking {
        for (partial in partials()) assertTrue("Partial Live View ACK must fail", runCatching { Client(listOf(partial)).control(live = true) }.isFailure)
        Client(listOf(Body(status = 200, text = "Accepted"))).control(live = true)
    }
    @Test fun stillCaptureRejectsPartialJsonReceipts() = runBlocking {
        for (partial in partials()) assertTrue("Partial still ACK must fail even with valid JSON", runCatching { Client(listOf(partial)).control(still = true) }.isFailure)
        Client(listOf(Body(status = 200, text = "{}"))).control(still = true)
    }
    @Test fun rejectedHttpKeepsTypedErrorAndPlainTextBody() = runBlocking {
        val failure = runCatching { Client(listOf(Body(status = 400, text = "busy", responseHeaders = mapOf("Content-Range" to "")))).control(live = true) }.exceptionOrNull()
        assertTrue(failure is CcapiHttpException)
        assertEquals(400, (failure as CcapiHttpException).statusCode)
        assertTrue(failure.message!!.contains("busy"))
    }
    @Test fun unrelatedRequestDoesNotAcquireControlRangePolicy() = runBlocking {
        Client(listOf(Body(status = 206, text = "data", responseHeaders = mapOf("Content-Range" to "bytes 0-3/8")))).control()
    }
    @Test fun combinedControlFlagsKeepStrictJsonReader() = runBlocking {
        assertTrue(runCatching { Client(listOf(Body(status = 200, text = "Accepted"))).control(still = true, live = true, release = true) }.isFailure)
    }
    private fun fresh(body: Body) = Body(status = body.status, text = body.text, responseHeaders = body.responseHeaders)
    private fun partials() = listOf(Body(status = 206, text = "{}"),
        Body(status = 200, text = "{}", responseHeaders = mapOf("Content-Range" to "bytes 0-1/4")),
        Body(status = 200, text = "{}", responseHeaders = mapOf("cOnTeNt-RaNgE" to "")))
    private fun action(request: Request) = Regex("\\\"action\\\":\\\"([^\\\"]+)\\\"").find(request.body)!!.groupValues[1]
}
