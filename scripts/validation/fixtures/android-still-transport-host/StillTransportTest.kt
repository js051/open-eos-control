package dev.openeos.control.data

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class StillTransportTest {
    @Test fun cancellationMustJoinHeldProducer() = runBlocking {
        val body = Body(held = true)
        val client = Client(listOf(body))
        val job = launch(Dispatchers.IO) { runCatching { client.captureCommand() } }
        assertTrue(body.entered.await(2, TimeUnit.SECONDS))
        val joined = withTimeoutOrNull(300) { job.cancelAndJoin(); true }
        body.unblock.countDown(); job.cancelAndJoin()
        assertEquals("Still cancellation must join the stopped producer", true, joined)
        assertEquals(0L, body.exited.count)
        assertEquals(listOf(true), client.bounded)
    }
    @Test fun mandatoryReleaseMustRemainJoinedAfterCancelledPress() = runBlocking {
        val press = Body(held = true); val release = Body(held = true)
        val client = Client(listOf(press, release)); val session = ShutterReleaseSession()
        val job = launch(Dispatchers.IO) {
            runCatching { session.pressAndRelease({ client.captureCommand() }, { client.captureCommand() }) }
        }
        assertTrue(press.entered.await(2, TimeUnit.SECONDS)); job.cancel()
        val releaseStarted = withContext(Dispatchers.IO) { release.entered.await(600, TimeUnit.MILLISECONDS) }
        val detached = withTimeoutOrNull(80) { job.join(); true }
        press.unblock.countDown(); release.unblock.countDown(); job.join()
        assertTrue("Cancelled press must reach its matching release", releaseStarted)
        assertNull("Release producer must remain joined", detached)
        assertTrue(client.priorProducerExitedBeforeRelease)
        assertEquals(listOf(true, true), client.bounded)
        assertFalse(session.hasPendingRelease)
    }
    @Test fun oversizedBodyMustNotBeAccepted() = runBlocking {
        val client = Client(listOf(Body(oversized = true)))
        assertTrue("Still response must enforce its byte limit", runCatching { client.captureCommand() }.isFailure)
        assertEquals(listOf(true), client.bounded)
    }
}
