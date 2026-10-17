package dev.openeos.control.data

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BridgeBulbObservationRaceTest {
    @Test(timeout = 5000) fun observationCheckedBeforeOwnerTransitionCannotOverwriteNewerWarning() = runBlocking {
        val session = BridgeShutterReleaseSession("synthetic-observation-race")
        val active = status(true, false)
        session.bulb.start(press = { BridgeBulbReceipt(it, "started", active) })
        val savedRevision = session.revision
        val staleIdle = status(false, false)
        val newerWarning = status(false, true)
        val threadFailure = AtomicReference<Throwable?>(null)
        val observer = Thread {
            try { session.observe(staleIdle, savedRevision) }
            catch (failure: Throwable) { threadFailure.set(failure) }
        }.apply { isDaemon = true }
        synchronized(session.bulb) {
            observer.start()
            val deadline = System.nanoTime() + 1_000_000_000
            while (observer.state != Thread.State.BLOCKED && observer.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertTrue("The observer must either finish under one shared monitor or block at the old split monitor",
                observer.state == Thread.State.BLOCKED || !observer.isAlive)
            session.bulb.observe(newerWarning)
            session.bulb.retire()
        }
        observer.join(1000)
        assertFalse(observer.isAlive)
        assertNull(threadFailure.get())
        assertSame("A stale observation that passed an earlier revision check cannot overwrite a later owner warning",
            newerWarning, session.bulb.displayStatus)
    }

    private fun status(active: Boolean, warning: Boolean) = CameraStatus(
        connected = true, batteryLevel = null, batteryStatus = "synthetic", recording = false,
        mode = "photo", mediaAvailable = true, remainingMinutes = null,
        exposure = ExposureState("100", "bulb", "4.0", "auto"),
        bulbExposureActive = active, shutterReleaseUnconfirmed = warning,
    )
}
