package dev.openeos.control.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class CameraFocusReadGateTest {
    @Test fun cancelledReadOwnsSlotUntilTransportActuallySettles() = runBlocking {
        val gate = CameraFocusReadGate()
        val settle = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        var reads = 0
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.readIfCurrent({ true }) {
                reads++
                active++
                maximum = maxOf(maximum, active)
                try { withContext(NonCancellable) { settle.await() }; null } finally { active-- }
            }
        }
        first.cancel()
        val next = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.readIfCurrent({ true }) {
                reads++
                active++
                maximum = maxOf(maximum, active)
                active--
                null
            }
        }
        assertEquals(1, reads)
        settle.complete(Unit)
        first.join()
        next.join()
        assertEquals(2, reads)
        assertEquals(1, maximum)
    }

    @Test fun queuedRetiredGenerationAndCancelledReplacementNeverReadCamera() = runBlocking {
        val gate = CameraFocusReadGate()
        val settle = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { gate.readIfCurrent({ true }) { settle.await(); null } }
        var current = true
        var reads = 0
        val retired = launch(start = CoroutineStart.UNDISPATCHED) { gate.readIfCurrent({ current }) { reads++; null } }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { gate.readIfCurrent({ true }) { reads++; null } }
        current = false
        cancelled.cancelAndJoin()
        settle.complete(Unit)
        first.join()
        retired.join()
        assertEquals(0, reads)
    }
}
