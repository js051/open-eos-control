package dev.openeos.control.data

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class DirectReleaseTransportTest {
    @Test fun heldAfCancellationJoinsItsOriginalStop() = runBlocking {
        val press = Body(held = true); val stop = Body(held = true)
        val client = Client(listOf(press, stop))
        var enteredHeld = false
        val job = launch(Dispatchers.IO) { runCatching { client.holdAutofocus { enteredHeld = true } } }
        assertTrue(press.entered.await(2, TimeUnit.SECONDS)); job.cancel()
        val reached = withContext(Dispatchers.IO) { stop.entered.await(600, TimeUnit.MILLISECONDS) }
        val detached = withTimeoutOrNull(80) { job.join(); true }
        press.unblock.countDown(); stop.unblock.countDown(); job.join()
        assertTrue("Held AF cancellation must reach the original Stop", reached)
        assertNull("AF Stop remains joined", detached)
        assertFalse(enteredHeld)
        assertTrue(client.priorProducerExitedBeforeRelease)
        assertEquals(listOf(true, true), client.bounded)
        assertEquals(listOf("start", "stop"), client.requests.map(::action))
    }
    @Test fun heldAfOversizedStopRetainsOriginalReleaseForRetry() = runBlocking {
        val client = Client(listOf(Body(), Body(oversized = true), Body()))
        val failure = runCatching { client.holdAutofocus {} }.exceptionOrNull()
        assertTrue("Held AF Stop must reject oversized response", failure is AutofocusReleaseException)
        assertTrue(runCatching { client.holdAutofocus {} }.isFailure)
        client.apiVersionPrefix = "/ccapi/ver999"
        client.retryAutofocusStop(); client.retryAutofocusStop()
        assertEquals(listOf("start", "stop", "stop"), client.requests.map(::action))
        assertTrue(client.requests.all { "/ver120/" in it.url })
        assertTrue(client.bounded.all { it })
    }
    @Test fun pulseAutofocusUsesBothBoundedCommands() = runBlocking {
        val client = Client(listOf(Body(), Body()))
        client.autofocus()
        assertEquals("Pulse AF Start and Stop must be bounded", listOf(true, true), client.bounded)
        assertEquals(listOf("start", "stop"), client.requests.map(::action))
    }
    @Test fun manualPutFallbackUsesBothBoundedCommands() = runBlocking {
        val client = Client(listOf(Body(), Body()), nativeAf = false, manualMethod = "PUT")
        client.autofocus()
        assertEquals("Manual PUT autofocus fallback must be bounded", listOf(true, true), client.bounded)
        assertEquals(listOf("half_press", "release"), client.requests.map(::action))
        assertTrue(client.requests.all { it.method == "PUT" && it.url.endsWith("/shutterbutton/manual") })
    }
    @Test fun explicitHalfPressUsesBothBoundedCommands() = runBlocking {
        val client = Client(listOf(Body(), Body()), nativeAf = false)
        client.halfPressShutter()
        assertEquals("Explicit half-press and release must be bounded", listOf(true, true), client.bounded)
        assertEquals(listOf("half_press", "release"), client.requests.map(::action))
    }
    @Test fun bulbStartAndExplicitStopUseBoundedCommands() = runBlocking {
        val client = Client(listOf(Body(), Body()), manualMethod = "PUT")
        assertTrue(client.startBulbExposure().bulbExposureActive)
        assertTrue(client.heldShutter.hasPendingRelease)
        client.stopBulbExposure()
        assertEquals("Bulb Start and Stop must be bounded", listOf(true, true), client.bounded)
        assertEquals(listOf("full_press", "release"), client.requests.map(::action))
        assertFalse(client.bulbExposureActive)
        assertFalse(client.heldShutter.hasPendingRelease)
    }
    @Test fun cancelledBulbStartJoinsMandatoryRelease() = runBlocking {
        val press = Body(held = true); val release = Body(held = true)
        val client = Client(listOf(press, release))
        val job = launch(Dispatchers.IO) { runCatching { client.startBulbExposure() } }
        assertTrue(press.entered.await(2, TimeUnit.SECONDS)); job.cancel()
        val reached = withContext(Dispatchers.IO) { release.entered.await(600, TimeUnit.MILLISECONDS) }
        val detached = withTimeoutOrNull(80) { job.join(); true }
        press.unblock.countDown(); release.unblock.countDown(); job.join()
        assertTrue("Cancelled Bulb press must reach original release", reached)
        assertNull("Bulb release remains joined", detached)
        assertTrue(client.priorProducerExitedBeforeRelease)
        assertEquals(listOf(true, true), client.bounded)
        assertFalse(client.bulbExposureActive)
        assertFalse(client.heldShutter.hasPendingRelease)
    }
    @Test fun unknownBulbReleaseBlocksNewStartUntilOriginalRetry() = runBlocking {
        val client = Client(listOf(Body(), Body(oversized = true), Body()))
        client.startBulbExposure()
        val failure = runCatching { client.stopBulbExposure() }.exceptionOrNull()
        assertTrue("Unknown Bulb release must retain its obligation", failure is ShutterReleaseException)
        assertTrue(client.heldShutter.releaseUnconfirmed)
        assertTrue(client.bulbExposureActive)
        assertTrue(runCatching { client.startBulbExposure() }.isFailure)
        client.apiVersionPrefix = "/ccapi/ver999"
        client.retryShutterRelease(); client.retryShutterRelease()
        assertEquals(listOf("full_press", "release", "release"), client.requests.map(::action))
        assertTrue(client.requests.all { "/ver120/" in it.url })
        assertFalse(client.bulbExposureActive)
        assertFalse(client.heldShutter.hasPendingRelease)
    }
    @Test fun unknownLengthAfStopMustBeBounded() = runBlocking {
        val client = Client(listOf(Body(), Body(text = " ".repeat(65_537))))
        assertTrue("Unknown-length AF Stop must enforce the byte limit",
            runCatching { client.holdAutofocus {} }.exceptionOrNull() is AutofocusReleaseException)
        assertTrue(runCatching { client.holdAutofocus {} }.isFailure)
        assertEquals(2, client.requests.size)
    }
    @Test fun truncatedBulbStopRemainsPending() = runBlocking {
        val client = Client(listOf(Body(), Body(text = "{}", declared = 12)))
        client.startBulbExposure()
        assertTrue("Truncated Bulb release cannot be acknowledged",
            runCatching { client.stopBulbExposure() }.exceptionOrNull() is ShutterReleaseException)
        assertTrue(client.heldShutter.releaseUnconfirmed)
        assertTrue(client.bulbExposureActive)
    }
    @Test fun nonCancellableBulbStopKeepsProducerJoined() = runBlocking {
        val release = Body(held = true)
        val client = Client(listOf(Body(), release))
        client.startBulbExposure()
        val stop = launch(Dispatchers.IO) { runCatching { client.stopBulbExposure() } }
        assertTrue(release.entered.await(2, TimeUnit.SECONDS)); stop.cancel()
        val detached = withTimeoutOrNull(80) { stop.join(); true }
        release.unblock.countDown(); stop.join()
        assertEquals("NonCancellable Bulb release must select bounded producer", listOf(true, true), client.bounded)
        assertNull(detached)
        assertFalse(client.heldShutter.hasPendingRelease)
        assertFalse(client.bulbExposureActive)
    }
    @Test fun oversizedAfStartStillReleasesWithoutEnteringHeldCallback() = runBlocking {
        val client = Client(listOf(Body(oversized = true), Body()))
        var entered = false
        val failure = runCatching { client.holdAutofocus { entered = true } }.exceptionOrNull()
        assertNotNull("AF Start must enforce the byte limit before the held callback", failure)
        assertFalse(entered)
        assertEquals(listOf("start", "stop"), client.requests.map(::action))
        client.retryAutofocusStop()
        assertEquals(2, client.requests.size)
    }
    @Test fun plainTextHttpErrorRemainsTypedAndReleaseRetryable() = runBlocking {
        val client = Client(listOf(Body(), Body(status = 400, text = "busy"), Body()))
        val failure = runCatching { client.holdAutofocus {} }.exceptionOrNull()
        assertTrue(failure is AutofocusReleaseException)
        assertEquals(400, (failure!!.cause as CcapiHttpException).statusCode)
        client.retryAutofocusStop()
        assertEquals(listOf("start", "stop", "stop"), client.requests.map(::action))
        val combined = Client(listOf(Body(status = 200, text = "not JSON")))
        assertTrue("Combining releaseControl with stillCapture must keep strict JSON validation",
            runCatching { combined.combinedStillControl() }.isFailure)
    }
    @Test fun simulatorEntriesRetainTheirOriginalEndpointsAndPayloads() = runBlocking {
        val client = Client(emptyList(), isRealCamera = false)
        client.autofocus(); client.halfPressShutter(); client.startBulbExposure(); client.stopBulbExposure()
        assertEquals(listOf("/ccapi/shutter/half-press:{}", "/ccapi/shutter/release:{}",
            "/ccapi/shutter/half-press:{}", "/ccapi/shutter/release:{}", "/ccapi/bulb/start:{}", "/ccapi/bulb/stop:{}"),
            client.simulatorRequests)
        assertTrue(client.requests.isEmpty())
    }
    private fun action(request: Request) = Regex("\\\"action\\\":\\\"([^\\\"]+)\\\"").find(request.body)!!.groupValues[1]
}
