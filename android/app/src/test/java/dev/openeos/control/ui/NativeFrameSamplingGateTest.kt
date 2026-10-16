package dev.openeos.control.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFrameSamplingGateTest {
    @Test
    fun ownerReplacementDoesNotReleaseThePhysicalRequest() {
        val gate = NativeFrameSamplingGate()
        val old = requireNotNull(gate.begin(1, 0, 0))
        gate.invalidate(100_000_000)
        assertNull(gate.begin(2, 300_000_000, 300_000_000))
        assertFalse(gate.complete(old, 400_000_000))
        assertNotNull(gate.begin(1, 500_000_000, 500_000_000))
    }

    @Test
    fun newerFramesDoNotStarveAnInFlightResult() {
        val gate = NativeFrameSamplingGate()
        val first = requireNotNull(gate.begin(1, 0, 0))
        (2L..20L).forEach { assertNull(gate.begin(it, it * 10_000_000, it * 10_000_000)) }
        assertTrue(gate.complete(first, 220_000_000))
        assertNotNull(gate.begin(21, 250_000_000, 250_000_000))
    }

    @Test
    fun cadenceAndDuplicateSuppressionSurviveASettingsChange() {
        val gate = NativeFrameSamplingGate()
        val first = requireNotNull(gate.begin(1, 0, 0))
        assertTrue(gate.complete(first, 10_000_000))
        assertNull(gate.begin(1, 300_000_000, 300_000_000))
        val second = requireNotNull(gate.begin(2, 300_000_000, 300_000_000))
        assertTrue(gate.complete(second, 310_000_000))
        gate.invalidate(320_000_000)
        assertNull(gate.begin(3, 330_000_000, 330_000_000))
        assertNotNull(gate.begin(3, 550_000_000, 550_000_000))
    }

    @Test
    fun delayedPreAttachmentFrameCannotStartEvenWhenItIsRecent() {
        val gate = NativeFrameSamplingGate()
        gate.invalidate(300_000_000)
        assertNull(gate.begin(1, 200_000_000, 400_000_000))
        assertNotNull(gate.begin(2, 350_000_000, 400_000_000))
    }

    @Test
    fun staleAndFutureRenderTimestampsCannotStart() {
        val gate = NativeFrameSamplingGate()
        assertNull(gate.begin(1, 0, 1_000_000_000))
        assertNull(gate.begin(2, 1_000_000_001, 1_000_000_000))
        assertNotNull(gate.begin(3, 999_999_999, 1_000_000_000))
    }

    @Test
    fun slowReadbackCannotPublishAndCompletesTheSlot() {
        val gate = NativeFrameSamplingGate()
        val first = requireNotNull(gate.begin(1, 0, 0))
        assertFalse(gate.complete(first, 1_000_000_000))
        assertFalse(gate.busy)
        assertNotNull(gate.begin(2, 1_000_000_000, 1_000_000_000))
    }

    @Test
    fun duplicateCompletionCannotReleaseAnotherRequest() {
        val gate = NativeFrameSamplingGate()
        val first = requireNotNull(gate.begin(1, 0, 0))
        assertTrue(gate.complete(first, 1))
        val second = requireNotNull(gate.begin(2, 250_000_000, 250_000_000))
        assertFalse(gate.complete(first, 300_000_000))
        assertTrue(gate.busy)
        assertTrue(gate.complete(second, 300_000_000))
    }

    @Test
    fun stopAndResumeRejectsOutstandingWorkWithoutOpeningAnotherSlot() {
        val gate = NativeFrameSamplingGate()
        val first = requireNotNull(gate.begin(1, 0, 0))
        gate.invalidate(1)
        gate.invalidate(2)
        assertNull(gate.begin(2, 400_000_000, 400_000_000))
        assertFalse(gate.complete(first, 500_000_000))
        assertNotNull(gate.begin(3, 600_000_000, 600_000_000))
    }
}
