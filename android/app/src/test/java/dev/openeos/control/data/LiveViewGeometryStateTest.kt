package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test

class LiveViewGeometryStateTest {
    @Test fun mutationRetiresBeforeDispatchAndAcknowledgementIsNotGeometry() {
        val state = LiveViewGeometryState<String>()
        val read = state.beginRead(state.owner()); assertTrue(state.publish(read, "old"))
        val old = state.snapshot()!!; val change = state.beginMutation(state.owner())
        assertNull(state.snapshot()); assertTrue(runCatching { state.owner() }.isFailure)
        assertTrue(runCatching { state.requireSnapshot(old) }.isFailure)
        assertFalse(state.publish(read, "late old")); state.finishMutation(change)
        assertNull(state.snapshot()); assertTrue(state.publish(state.beginRead(state.owner()), "new"))
        assertEquals("new", state.snapshot()!!.value)
    }
    @Test fun allTerminalOutcomesRequireFreshGeometry() {
        for (outcome in listOf("success", "error", "cancelled", "unknown ack", "invalid readback")) {
            val state = LiveViewGeometryState<String>(); state.publish(state.beginRead(state.owner()), "old")
            val change = state.beginMutation(state.owner()); state.finishMutation(change)
            assertNull(outcome, state.snapshot()); assertFalse(state.publish(LiveViewGeometryState.Read(change, 1), "during mutation"))
            assertTrue(state.publish(state.beginRead(state.owner()), "fresh $outcome"))
        }
    }
    @Test fun lateOldReadCannotClearOrOverwriteNewGeometry() {
        val state = LiveViewGeometryState<String>(); val old = state.beginRead(state.owner())
        val change = state.beginMutation(state.owner()); state.finishMutation(change)
        state.publish(state.beginRead(state.owner()), "new")
        assertFalse(state.publish(old, "old")); assertFalse(state.publish(old, null)); assertEquals("new", state.snapshot()!!.value)
    }
    @Test fun latestReadWinsAndSupersededCoordinateIsRevoked() {
        val state = LiveViewGeometryState<String>(); val one = state.beginRead(state.owner()); state.publish(one, "one")
        val coordinate = state.snapshot()!!; val two = state.beginRead(state.owner())
        assertTrue(runCatching { state.requireSnapshot(coordinate) }.isFailure)
        state.publish(two, "two"); assertFalse(state.publish(one, null)); assertEquals("two", state.snapshot()!!.value)
    }
    @Test fun replacementSessionIgnoresOldReadAndOldMutationFinally() {
        val state = LiveViewGeometryState<String>(); val old = state.beginRead(state.owner()); val change = state.beginMutation(state.owner())
        state.retire(); state.reset(); state.publish(state.beginRead(state.owner()), "replacement"); state.finishMutation(change)
        assertFalse(state.publish(old, "old session")); assertEquals("replacement", state.snapshot()!!.value)
        assertTrue(runCatching { state.requireRevision(change) }.isFailure)
    }
    @Test fun stoppedSessionRejectsReadsAndCoordinates() {
        val state = LiveViewGeometryState<String>(); state.publish(state.beginRead(state.owner()), "old")
        val snapshot = state.snapshot()!!; state.retire()
        assertTrue(runCatching { state.owner() }.isFailure); assertTrue(runCatching { state.requireSnapshot(snapshot) }.isFailure)
        state.finishMutation(snapshot.read.revision); assertTrue(runCatching { state.owner() }.isFailure)
    }
    @Test fun localRetirementRevokesAlreadyAdmittedReadAndCoordinate() {
        val state = LiveViewGeometryState<String>(); val read = state.beginRead(state.owner())
        assertTrue(state.publish(read, "old")); val coordinate = state.snapshot()!!
        state.retire() // Must precede any suspending local-session close.
        assertFalse(state.publish(read, "late detail")); assertNull(state.snapshot())
        assertTrue(runCatching { state.requireSnapshot(coordinate) }.isFailure)
        assertTrue(runCatching { state.beginRead(read.revision) }.isFailure)
    }
    @Test fun concurrentChangeIsRejectedRatherThanQueued() {
        val state = LiveViewGeometryState<String>(); val owner = state.owner(); val change = state.beginMutation(owner)
        assertTrue(runCatching { state.beginMutation(owner) }.isFailure); assertTrue(runCatching { state.beginRead(owner) }.isFailure)
        state.finishMutation(change)
    }
    @Test fun missingFreshGeometryRemainsUnavailable() {
        val state = LiveViewGeometryState<String>(); state.publish(state.beginRead(state.owner()), "old")
        val change = state.beginMutation(state.owner()); state.finishMutation(change)
        assertFalse(state.publish(state.beginRead(state.owner()), null)); assertNull(state.snapshot())
    }
    @Test fun queuedNativeInputCannotBeRebrandedAfterPauseAndResume() {
        val ingress = NativeRtpInputIdentity(frameId = 41, renderingGeneration = 3)
        val queuedInput = ingress
        val decodedMetadata = 128 to queuedInput
        assertFalse(decodedMetadata.second.ownsRendering(4)) // paused
        assertFalse(decodedMetadata.second.ownsRendering(5)) // resumed
        assertEquals(3L, decodedMetadata.second.renderingGeneration)
        val fresh = NativeRtpInputIdentity(frameId = 42, renderingGeneration = 5)
        assertTrue(fresh.ownsRendering(5))
        assertFalse(fresh.ownsRendering(7)) // a second zoom retires this input too
    }
}
