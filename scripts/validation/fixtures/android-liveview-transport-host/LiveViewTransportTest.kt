package dev.openeos.control.data

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class LiveViewTransportTest {
    @Test fun cancellingJpegStartMustJoinProducerAndReachOriginalStop() = runBlocking {
        val start = Body(held = true); val stop = Body()
        val client = Client(listOf(start, stop))
        val job = launch(Dispatchers.IO) { runCatching { client.startLiveView() } }
        assertTrue(start.entered.await(2, TimeUnit.SECONDS))
        val joined = withTimeoutOrNull(600) { job.cancelAndJoin(); true }
        start.unblock.countDown(); job.cancelAndJoin()
        assertEquals("Live View cancellation must retire Start and reach Stop", true, joined)
        assertEquals(0L, start.exited.count)
        assertTrue(client.priorProducerExitedBeforeStop)
        assertEquals(listOf(true, true), client.bounded)
        assertNull(client.pendingLiveViewStop)
        assertNull(client.activeLiveViewSource)
    }
    @Test fun cancelledJpegStartMustRemainJoinedThroughHeldStop() = runBlocking {
        val start = Body(held = true); val stop = Body(held = true)
        val client = Client(listOf(start, stop))
        val job = launch(Dispatchers.IO) { runCatching { client.startLiveView() } }
        assertTrue(start.entered.await(2, TimeUnit.SECONDS)); job.cancel()
        val reached = withContext(Dispatchers.IO) { stop.entered.await(600, TimeUnit.MILLISECONDS) }
        val detached = withTimeoutOrNull(80) { job.join(); true }
        start.unblock.countDown(); stop.unblock.countDown(); job.join()
        assertTrue("Cancelled Start must dispatch its original Stop", reached)
        assertNull("Physical Stop must remain joined", detached)
        assertTrue(client.priorProducerExitedBeforeStop)
        assertEquals(listOf(true, true), client.bounded)
    }
    @Test fun oversizedJpegStartMustFailAndStopBeforePublishing() = runBlocking {
        val client = Client(listOf(Body(oversized = true), Body()))
        assertTrue("Live View Start response must be bounded", runCatching { client.startLiveView() }.isFailure)
        assertEquals(listOf(true, true), client.bounded)
        assertNull(client.activeLiveViewSource)
        assertNull(client.pendingLiveViewStop)
    }
    @Test fun ordinaryJpegStopMustRetainOwnerOnOversizedResponse() = runBlocking {
        val client = Client(listOf(Body(), Body(oversized = true), Body()))
        client.startLiveView()
        assertTrue("Live View Stop response must be bounded", runCatching { client.stopLiveView() }.exceptionOrNull() is CcapiLiveViewReleaseException)
        assertNotNull(client.pendingLiveViewStop)
        assertTrue(runCatching { client.startLiveView() }.isFailure)
        client.stopLiveView()
        assertNull(client.pendingLiveViewStop)
        assertEquals(listOf(true, true, true), client.bounded)
    }
    @Test fun rtpCancellationMustJoinStopBeforePhysicalClose() = runBlocking {
        val start = Body(held = true); val stop = Body(held = true)
        val client = Client(listOf(start, stop))
        val job = launch(Dispatchers.IO) { runCatching { client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_RTP)) } }
        assertTrue(start.entered.await(2, TimeUnit.SECONDS)); job.cancel()
        val reached = withContext(Dispatchers.IO) { stop.entered.await(600, TimeUnit.MILLISECONDS) }
        val detached = withTimeoutOrNull(80) { job.join(); true }
        val closedBeforeStop = client.session.closed
        start.unblock.countDown(); stop.unblock.countDown(); job.join()
        assertTrue("RTP cancellation must reach the matching Stop", reached)
        assertNull(detached); assertFalse(closedBeforeStop); assertTrue(client.session.closed)
        assertEquals(listOf(true, true), client.bounded)
    }
    @Test fun rtpStopFailureMustClosePhysicalSessionAndKeepRetryOwner() = runBlocking {
        val client = Client(listOf(Body(), Body(oversized = true), Body()))
        client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_RTP))
        assertTrue("RTP Stop response must be bounded", runCatching { client.stopLiveView() }.exceptionOrNull() is CcapiLiveViewReleaseException)
        assertTrue(client.session.closed); assertNotNull(client.pendingLiveViewStop)
        client.stopLiveView()
        assertNull(client.pendingLiveViewStop)
        assertEquals(listOf(true, true, true), client.bounded)
    }
    @Test fun postOffStopMustUseBoundedControlTransport() = runBlocking {
        val client = Client(listOf(Body(), Body()), CcapiLiveViewStopMethod.POST_OFF)
        client.startLiveView(); client.stopLiveView()
        assertEquals("POST-off Start and Stop must be bounded", listOf(true, true), client.bounded)
        assertEquals(listOf("POST", "POST"), client.requests.map { it.method })
    }
    @Test fun explicitSizeFallbackCommandsMustAllRemainBounded() = runBlocking {
        val client = Client(listOf(Body(status = 400, text = "Invalid size"), Body(status = 400, text = "Size required"), Body(), Body()))
        client.startLiveView(); client.stopLiveView()
        assertEquals("Every size fallback and Stop must be bounded", listOf(true, true, true, true), client.bounded)
        assertEquals(LiveViewSize.MEDIUM, client.activeLiveViewSize)
    }
    @Test fun unknownAutoStopCannotFallbackOrAdmitANewStart() = runBlocking {
        val client = Client(listOf(Body(oversized = true), Body(oversized = true), Body()))
        assertTrue("AUTO cannot accept oversized Start or Stop", runCatching {
            client.startLiveView(LiveViewRequest(source = LiveViewSource.AUTO))
        }.exceptionOrNull() is CcapiLiveViewReleaseException)
        assertEquals(2, client.dispatched)
        assertTrue(client.session.closed); assertNotNull(client.pendingLiveViewStop)
        assertNull(client.activeLiveViewSource)
        assertTrue(runCatching { client.startLiveView() }.isFailure)
        assertEquals(2, client.dispatched)
        client.stopLiveView()
        assertNull(client.pendingLiveViewStop)
        assertEquals(listOf(true, true, true), client.bounded)
    }
    @Test fun cancellingStopCannotDetachProducerOrClearUnknownOwner() = runBlocking {
        val stop = Body(held = true)
        val client = Client(listOf(Body(), stop, Body()))
        client.startLiveView()
        val job = launch(Dispatchers.IO) { runCatching { client.stopLiveView() } }
        assertTrue(stop.entered.await(2, TimeUnit.SECONDS)); job.cancel()
        assertNull("Cancellation must not detach the mandatory Stop", withTimeoutOrNull(80) { job.join(); true })
        assertNotNull(client.pendingLiveViewStop)
        stop.cancelled.set(true); stop.unblock.countDown(); job.join()
        assertNotNull("Transport failure must retain the original Stop", client.pendingLiveViewStop)
        assertTrue(runCatching { client.startLiveView() }.isFailure)
        client.stopLiveView()
        assertNull(client.pendingLiveViewStop)
        assertEquals("Stop transport must remain bounded even in NonCancellable", listOf(true, true, true), client.bounded)
    }

    @Test fun unknownLengthStartMustEnforceStreamingByteLimit() = runBlocking {
        val client = Client(listOf(Body(text = " ".repeat(65_537)), Body()))
        assertTrue("Unknown-length Start must enforce the streaming byte limit", runCatching { client.startLiveView() }.isFailure)
        assertNull(client.activeLiveViewSource); assertNull(client.pendingLiveViewStop)
        assertEquals(listOf(true, true), client.bounded)
    }
    @Test fun truncatedDeclaredStopMustKeepItsOriginalOwner() = runBlocking {
        val client = Client(listOf(Body(), Body(text = "{}", declared = 3), Body()))
        client.startLiveView()
        assertTrue("Truncated Stop cannot be acknowledged", runCatching { client.stopLiveView() }.exceptionOrNull() is CcapiLiveViewReleaseException)
        assertNotNull(client.pendingLiveViewStop)
        client.stopLiveView()
        assertNull(client.pendingLiveViewStop)
        assertEquals(listOf(true, true, true), client.bounded)
    }

}
