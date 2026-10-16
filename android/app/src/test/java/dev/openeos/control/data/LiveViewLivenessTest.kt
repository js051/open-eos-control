package dev.openeos.control.data

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Test

class LiveViewLivenessTest {
    @Test fun allRecordingMutationAndRetainedDutyStatesBlockPreviewTransitions() {
        for (inFlight in listOf(false, true)) for (pending in listOf(false, true)) for (stop in listOf(false, true)) {
            assertEquals(inFlight || pending || stop, liveViewRecordingInterlocked(inFlight, pending, stop))
        }
    }

    @Test fun onlyCompleteVclPicturesRefreshInputProgressNotParameterSetsOrSei() {
        val reader = H264RtpDepacketizer(103)
        fun packet(sequence: Int, nal: Int) = byteArrayOf(0x80.toByte(), (0x80 or 103).toByte(), 0, sequence.toByte(),
            0, 0, 0, sequence.toByte(), 0, 0, 0, 1, nal.toByte(), 1)
        for ((sequence, nal) in listOf(0x67, 0x68, 0x06).withIndex()) {
            assertFalse(checkNotNull(reader.accept(packet(sequence, nal))).hasPicture)
        }
        assertTrue(checkNotNull(reader.accept(packet(4, 0x65))).hasPicture)
        assertTrue(checkNotNull(reader.accept(packet(5, 0x61))).hasPicture)
        assertFalse(checkNotNull(reader.accept(packet(6, 0x67))).hasPicture)
    }

    @Test fun firstOutputAndEveryFollowingOutputHaveABoundedDeadline() {
        val deadline = LiveViewInactivityDeadline(100)
        assertFalse(deadline.expired(1_000))
        deadline.arm(0)
        assertFalse(deadline.expired(99))
        assertTrue(deadline.expired(100))
        deadline.observe(100)
        assertFalse(deadline.expired(199))
        assertTrue(deadline.expired(200))
    }

    @Test fun HiddenOrDetachedPresentationCannotTimeOutUntilARealResume() {
        val deadline = LiveViewInactivityDeadline(100)
        deadline.arm(0)
        deadline.disarm()
        deadline.observe(1_000) // A stale callback does not resurrect a detached deadline.
        assertFalse(deadline.expired(2_000))
        deadline.arm(3_000)
        assertFalse(deadline.expired(3_099))
        assertTrue(deadline.expired(3_100))
    }

    @Test fun retiredSourceStillOwesStopAndCannotPublishOrFallback() {
        val result = LiveViewLifecycleEvidence(retired = true, terminal = true,
            frameEvidence = "presented-frame", sourceMode = "AUTO", stopEvidence = "unconfirmed").evaluate()
        assertTrue(result.stopRequired)
        assertFalse(result.canPublishInputReady)
        assertFalse(result.canPublishDecoded)
        assertFalse(result.canPublishPresented)
        assertFalse(result.canFallback)
    }

    @Test fun encodedKeyframeCannotBecomeDecodedOrPresentedEvidence() {
        val result = LiveViewLifecycleEvidence(frameEvidence = "encoded-frame").evaluate()
        assertTrue(result.canPublishInputReady)
        assertFalse(result.canPublishDecoded)
        assertFalse(result.canPublishPresented)
    }

    @Test fun backgroundRetiresOnlyStartAndKeepsTheControlCallerAlive() = runBlocking {
        val owner = LiveViewStartOperation()
        val entered = CompletableDeferred<Unit>()
        var stopRequired = false
        var stops = 0
        val start = async {
            owner.run(start = { stopRequired = true; entered.complete(Unit); awaitCancellation() },
                stopRequired = { stopRequired }, stop = { stops++; stopRequired = false })
        }
        entered.await()
        owner.retire()
        assertFalse(start.await())
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(1, stops)
        assertFalse(stopRequired)
        assertTrue(owner.run(start = {}, stopRequired = { false }, stop = { error("No Stop was admitted") }))
    }

    @Test fun cancellationAfterReadyStillSettlesOriginalStopBeforeReturning() = runBlocking {
        val owner = LiveViewStartOperation()
        var stopRequired = false
        var stops = 0
        val result = owner.run(start = {
            stopRequired = true
            owner.retire() // A lifecycle retirement races with the completed Start result.
        }, stopRequired = { stopRequired }, stop = { stops++; stopRequired = false })
        assertFalse(result)
        assertEquals(1, stops)
        assertFalse(stopRequired)
    }

    @Test fun retirementAfterDeferredCompletionBeforeAwaitResumesStillStops() = runBlocking {
        val tasks = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val owner = LiveViewStartOperation()
        var required = false
        var stops = 0
        val task = scope.async { owner.run(start = { required = true }, stopRequired = { required },
            stop = { stops++; required = false }) }
        try {
            tasks.removeFirst().run() // Parent suspends waiting for the child.
            tasks.removeFirst().run() // Child returns successfully; only await continuation remains.
            assertFalse(task.isCompleted)
            owner.retire()
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
            assertTrue(task.isCompleted)
            assertFalse(task.await())
            assertEquals(1, stops)
            assertFalse(required)
        } finally { scope.cancel(); while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun failedRetirementCleanupIsNeverConvertedIntoCancelledSuccess() = runBlocking {
        val owner = LiveViewStartOperation()
        var stopRequired = false
        val failure = runCatching { owner.run(start = { stopRequired = true; owner.retire() },
            stopRequired = { stopRequired }, stop = { error("Synthetic original Stop remains unconfirmed") }) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("original Stop"))
        assertTrue(stopRequired)
    }

    @Test fun cancellingEnclosingCallerStillPropagatesAfterStop() = runBlocking {
        val owner = LiveViewStartOperation()
        val entered = CompletableDeferred<Unit>()
        var stops = 0
        val task = launch { owner.run(start = { entered.complete(Unit); awaitCancellation() },
            stopRequired = { true }, stop = { stops++ }) }
        entered.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, stops)
    }
}
