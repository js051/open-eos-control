package dev.openeos.control.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RtpAudioPlaybackTest {
    @Test fun queuedInputDoesNotProveDecodeAndTimesOutWithResourcesRetired() = runBlocking {
        val backend = Backend()
        val latest = AtomicReference(RtpAudioPlaybackStats())
        val input = Channel<AacAccessUnit>(1).apply { send(unit()) }
        val failure = runCatching { pump(backend, timeout = 60_000_000).play(input, latest::set) }.exceptionOrNull()
        assertTrue(failure is RtpAudioStalledException)
        assertEquals(1L, latest.get().queuedAccessUnits)
        assertEquals(0L, latest.get().decodedAccessUnits)
        assertEquals(0L, latest.get().playedSampleFrames)
        assertTrue(backend.decoder.closed)
        assertEquals(0, backend.outputCreates)
    }

    @Test fun delayedPcmDrainsWithoutAnotherInputAndWritesAreNotPlaybackProof() = runBlocking {
        val backend = Backend()
        val latest = AtomicReference(RtpAudioPlaybackStats())
        val input = Channel<AacAccessUnit>(1).apply { send(unit()) }
        val job = launch { pump(backend).play(input, latest::set) }
        until { backend.decoder.queued == 1 }
        backend.decoder.pcm()
        until { latest.get().submittedSampleFrames == 4L }
        assertEquals(1L, latest.get().decodedAccessUnits)
        assertEquals(4L, latest.get().decodedSampleFrames)
        assertEquals(0L, latest.get().playedSampleFrames)
        assertNull(latest.get().lastOutputAtMillis)
        backend.output.head = 4
        until { latest.get().playedSampleFrames == 4L }
        assertTrue(latest.get().pcmFresh)
        input.close()
        withTimeout(1_000) { job.join() }
        assertTrue(backend.output.closed)
        assertTrue(backend.decoder.closed)
        assertEquals(1, backend.decoder.releases)
    }

    @Test fun partialAndZeroWritesCountOnlyAcceptedCompleteFrames() = runBlocking {
        val backend = Backend().apply { output.chunks.addAll(listOf(2, 0, 6, 8)) }
        backend.decoder.pcm()
        val input = Channel<AacAccessUnit>(1).apply { send(unit()) }
        val latest = AtomicReference(RtpAudioPlaybackStats())
        val job = launch { pump(backend).play(input, latest::set) }
        until { latest.get().submittedSampleFrames == 4L }
        assertEquals(16, backend.output.accepted)
        assertEquals(0L, latest.get().playedSampleFrames)
        job.cancelAndJoin()
        assertTrue(backend.output.closed)
    }

    @Test fun zeroWriteBackpressureIsCancellableAndReleasesHeldCodecOutput() = runBlocking {
        val backend = Backend().apply { output.neverAccept = true; decoder.pcm() }
        val input = Channel<AacAccessUnit>(1).apply { send(unit()) }
        val job = launch { pump(backend).play(input) {} }
        until { backend.output.writes > 0 }
        withTimeout(500) { job.cancelAndJoin() }
        assertTrue(backend.output.closed)
        assertTrue(backend.decoder.closed)
        assertEquals(1, backend.decoder.releases)
    }

    @Test fun outputClockStallIsBoundedAndDoesNotClaimPlayback() = runBlocking {
        val backend = Backend().apply { decoder.pcm() }
        val input = Channel<AacAccessUnit>(1).apply { send(unit()); close() }
        val latest = AtomicReference(RtpAudioPlaybackStats())
        val failure = withTimeout(1_000) {
            runCatching { pump(backend, timeout = 60_000_000).play(input, latest::set) }.exceptionOrNull()
        }
        assertTrue(failure is RtpAudioStalledException)
        assertTrue(failure!!.message!!.contains("output"))
        assertEquals(4L, latest.get().submittedSampleFrames)
        assertEquals(0L, latest.get().playedSampleFrames)
        assertTrue(backend.output.closed)
    }

    @Test fun repeatedDiscontinuitiesCannotExtendFirstActualPcmDeadline() = runBlocking {
        val backend = Backend()
        val input = Channel<AacAccessUnit>(Channel.UNLIMITED)
        val producer = launch { repeat(100) { input.send(unit(discontinuity = true)); delay(5) } }
        val failure = withTimeout(1_000) {
            runCatching { pump(backend, timeout = 80_000_000).play(input) {} }.exceptionOrNull()
        }
        producer.cancelAndJoin()
        assertTrue(failure is RtpAudioStalledException)
        assertTrue(backend.decoderCreates > 2)
    }

    @Test fun closedInputWithoutEosOrPcmStillTimesOut() = runBlocking {
        val backend = Backend().apply { decoder.emitEos = false }
        val input = Channel<AacAccessUnit>(1).apply { send(unit()); close() }
        val failure = withTimeout(500) {
            runCatching { pump(backend, timeout = 50_000_000).play(input) {} }.exceptionOrNull()
        }
        assertTrue(failure is RtpAudioStalledException)
        assertTrue(backend.decoder.closed)
    }

    @Test fun freshPcmExpiresAtOneSecondWithoutInventingAnotherDecode() = runBlocking {
        val clock = AtomicLong()
        val backend = Backend().apply { decoder.pcm() }
        val input = Channel<AacAccessUnit>(1).apply { send(unit()) }
        val latest = AtomicReference(RtpAudioPlaybackStats())
        val job = launch { RtpAudioPlaybackPump(backend, clock::get, { 123L }, pollMillis = 1,
            statsIntervalNanos = 1).play(input, latest::set) }
        until { backend.output.accepted == 16 }
        clock.set(1)
        until { latest.get().pcmFresh }
        backend.output.head = 4
        until { latest.get().playedSampleFrames == 4L }
        clock.set(1_000_000_000)
        until { !latest.get().pcmFresh }
        assertEquals(1L, latest.get().decodedAccessUnits)
        assertEquals(4L, latest.get().playedSampleFrames)
        job.cancelAndJoin()
    }

    @Test fun headWrapClampsToSubmittedAndUnexpectedResetFailsClosed() {
        val wrap = RtpAudioHeadProgress(0xffff_fff0L)
        assertEquals(32L, wrap.observe(16L, 64))
        assertEquals(40L, wrap.observe(32L, 40))
        val reset = RtpAudioHeadProgress(500)
        assertThrows(IllegalStateException::class.java) { reset.observe(0, 1000) }
        val ordinary = RtpAudioHeadProgress(0)
        assertEquals(0L, ordinary.observe(0, 100))
        assertEquals(10L, ordinary.observe(10, 100))
    }

    @Test fun defaultMuteBackgroundAndManualRecoveryDoNotAutoStart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val starts = AtomicInteger()
        val latest = AtomicReference(RtpAudioOwnerStatus())
        val owner = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, _ -> starts.incrementAndGet(); awaitCancellation() } }, latest::set,
            RtpAudioOutputRetirement())
        try {
            owner.submit(listOf(unit()))
            assertEquals(0, starts.get())
            owner.enable(); until { starts.get() == 1 }
            owner.setForeground(false)
            until { !latest.get().enabled && latest.get().state == "muted" }
            owner.enable(); delay(10)
            assertEquals(1, starts.get())
            owner.setForeground(true); delay(10)
            assertEquals(1, starts.get())
            owner.enable(); until { starts.get() == 2 }
            assertEquals(2L, latest.get().epoch)
        } finally { owner.close(); scope.cancel() }
    }

    @Test fun replacementsWaitForRealRetirementAndIgnoreOldFinalStatsAcrossCancelledWaiters() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retirement = RtpAudioOutputRetirement()
        val release = CompletableDeferred<Unit>()
        val retiring = CompletableDeferred<Unit>()
        val starts = AtomicInteger()
        val latest = AtomicReference(RtpAudioOwnerStatus())
        val owner = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, stats ->
            val number = starts.incrementAndGet()
            try { awaitCancellation() } finally {
                if (number == 1) withContext(NonCancellable) {
                    retiring.complete(Unit); release.await()
                    stats(RtpAudioPlaybackStats(decodedAccessUnits = 999, playedSampleFrames = 999))
                }
            }
        } }, latest::set, retirement)
        try {
            owner.enable(); until { starts.get() == 1 }
            owner.mute(); retiring.await()
            owner.enable(); owner.mute(); owner.enable()
            delay(30)
            assertEquals(1, starts.get())
            assertEquals(3L, latest.get().epoch)
            release.complete(Unit)
            until { starts.get() == 2 }
            assertEquals(0L, latest.get().stats.playedSampleFrames)
            assertEquals(0L, latest.get().stats.decodedAccessUnits)
            assertTrue(latest.get().enabled)
        } finally { release.complete(Unit); owner.close(); scope.cancel() }
    }

    @Test fun retirementFailureBlocksAnotherOwnerWithoutOpeningAnotherOutput() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retirement = RtpAudioOutputRetirement()
        val first = AtomicReference(RtpAudioOwnerStatus())
        val next = AtomicReference(RtpAudioOwnerStatus())
        val starts = AtomicInteger()
        val a = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, _ -> throw RtpAudioRetirementException(IllegalStateException("release")) } }, first::set, retirement)
        val b = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, _ -> starts.incrementAndGet() } }, next::set, retirement)
        try {
            a.enable(); until { first.get().state == "failed" }
            b.enable(); until { next.get().state == "failed" }
            assertEquals(0, starts.get())
            assertTrue(next.get().error!!.contains("restart the app"))
        } finally { a.close(); b.close(); scope.cancel() }
    }

    @Test fun ordinaryFailureClearsRequestAndAllowsExplicitCleanRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val latest = AtomicReference(RtpAudioOwnerStatus())
        val starts = AtomicInteger()
        val owner = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, _ ->
            if (starts.incrementAndGet() == 1) throw RtpAudioStalledException("No PCM")
            awaitCancellation()
        } }, latest::set, RtpAudioOutputRetirement())
        try {
            owner.enable(); until { latest.get().state == "stalled" }
            assertFalse(latest.get().enabled)
            delay(10); assertEquals(1, starts.get())
            owner.enable(); until { starts.get() == 2 }
            assertTrue(latest.get().enabled)
            assertNull(latest.get().error)
        } finally { owner.close(); scope.cancel() }
    }

    @Test fun expiredQueuedInputIsNotSubmittedToCodec() = runBlocking {
        val backend = Backend()
        val input = Channel<AacAccessUnit>(1).apply { send(unit().copy(receivedAtNanos = System.nanoTime() - 2_000_000_000)); close() }
        pump(backend).play(input) {}
        assertEquals(0, backend.decoder.queued)
        assertEquals(0, backend.decoderCreates)
    }

    @Test fun heldPcmCannotBeWrittenAfterItsOneSecondFreshnessWindow() = runBlocking {
        val clock = AtomicLong()
        val backend = Backend().apply { decoder.pcm(); output.neverAccept = true }
        val input = Channel<AacAccessUnit>(1).apply { send(unit()) }
        val task = async { runCatching { RtpAudioPlaybackPump(backend, clock::get, pollMillis = 1).play(input) {} }.exceptionOrNull() }
        until { backend.output.writes > 0 }
        backend.output.neverAccept = false
        clock.set(1_000_000_000)
        val error = withTimeout(500) { task.await() }
        assertTrue(error is RtpAudioStalledException)
        assertEquals(0, backend.output.accepted)
        assertTrue(backend.output.closed)
    }

    @Test fun staleCodecOutputIsReleasedWithoutOpeningAudioTrack() = runBlocking {
        val backend = Backend()
        backend.decoder.pending.add(RtpDecodedAudio.Pcm(ByteBuffer.wrap(ByteArray(16)),
            RtpPcmFormat(48_000, 2, RtpPcmEncoding.PCM_16BIT), sourceReceivedAtNanos = System.nanoTime() - 2_000_000_000,
            release = { backend.decoder.releases++ }))
        val input = Channel<AacAccessUnit>(1).apply { send(unit()); close() }
        pump(backend).play(input) {}
        assertEquals(0, backend.outputCreates)
        assertEquals(1, backend.decoder.releases)
    }

    @Test fun muteReleaseFailureIsVisibleBeforeAnyRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val latest = AtomicReference(RtpAudioOwnerStatus())
        val started = CompletableDeferred<Unit>()
        val owner = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { throw RtpAudioRetirementException(IllegalStateException("release")) }
        } }, latest::set, RtpAudioOutputRetirement())
        try {
            owner.enable(); started.await(); owner.mute()
            until { latest.get().state == "failed" }
            assertFalse(latest.get().enabled)
            assertTrue(latest.get().error!!.contains("restart the app"))
            delay(10)
            assertEquals("failed", latest.get().state)
            val failedEpoch = latest.get().epoch
            owner.setForeground(false); owner.setForeground(true); owner.mute(); owner.enable()
            assertEquals("failed", latest.get().state)
            assertEquals(failedEpoch, latest.get().epoch)
        } finally { owner.close(); scope.cancel() }
    }

    @Test fun aNewSessionCannotStartBeforeThePreviousSessionsResourcesRetire() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retirement = RtpAudioOutputRetirement()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val replacement = AtomicInteger()
        val a = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
        } }, {}, retirement)
        val b = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, _ -> replacement.incrementAndGet(); awaitCancellation() } }, {}, retirement)
        try {
            a.enable(); started.await(); a.close(); b.enable(); delay(20)
            assertEquals(0, replacement.get())
            release.complete(Unit); until { replacement.get() == 1 }
        } finally { release.complete(Unit); a.close(); b.close(); scope.cancel() }
    }

    @Test fun alreadySubmittedStalePcmIsRetiredBeforeLateHeadRecoveryCanClaimProgress() = runBlocking {
        val clock = AtomicLong()
        val backend = Backend().apply { decoder.pcm() }
        val input = Channel<AacAccessUnit>(1).apply { send(unit()) }
        val latest = AtomicReference(RtpAudioPlaybackStats())
        val task = async { runCatching { RtpAudioPlaybackPump(backend, clock::get, pollMillis = 1,
            statsIntervalNanos = 1).play(input, latest::set) }.exceptionOrNull() }
        until { backend.output.accepted == 16 }
        clock.set(1_000_000_000)
        backend.output.head = 4 // Too late to establish that queued frames were fresh.
        val error = withTimeout(500) { task.await() }
        assertTrue(error is RtpAudioStalledException)
        assertEquals(0L, latest.get().playedSampleFrames)
        assertTrue(backend.output.closed)
    }

    @Test fun pendingFrameFreshnessTracksPartialPlaybackBySourceRange() {
        val pending = RtpPendingPcmFrames()
        pending.submitted(100, 0)
        pending.submitted(200, 500_000_000)
        pending.observed(99)
        assertThrows(RtpAudioStalledException::class.java) { pending.requireFresh(1_000_000_000) }
        pending.observed(100)
        pending.requireFresh(1_000_000_000)
        assertThrows(RtpAudioStalledException::class.java) { pending.requireFresh(1_500_000_000) }
        pending.observed(200)
        pending.requireFresh(Long.MAX_VALUE)
    }

    @Test fun formatChurnCannotResetTheOutputProgressDeadline() = runBlocking {
        val clock = AtomicLong()
        val writes = AtomicInteger()
        val backend = object : RtpAudioBackend {
            override fun decoder(format: AacStreamFormat) = object : RtpAudioDecoder {
                var pcm: RtpDecodedAudio? = null
                override fun queue(accessUnit: AacAccessUnit): Boolean {
                    pcm = RtpDecodedAudio.Pcm(ByteBuffer.wrap(ByteArray(16)),
                        RtpPcmFormat(48_000, 2, RtpPcmEncoding.PCM_16BIT), sourceReceivedAtNanos = clock.get())
                    return true
                }
                override fun poll(): RtpDecodedAudio? = pcm.also { pcm = null }
                override fun endInput() = true
                override fun close() {}
            }
            override fun output(format: RtpPcmFormat) = object : RtpPcmOutput {
                override fun write(bytes: ByteBuffer): Int = bytes.remaining().also { bytes.position(bytes.limit()); writes.incrementAndGet() }
                override val playbackHeadFrames = 0L
                override val underruns = 0
                override fun close() {}
            }
        }
        val input = Channel<AacAccessUnit>(1)
        val task = async { runCatching { RtpAudioPlaybackPump(backend, clock::get, pollMillis = 1).play(input) {} }.exceptionOrNull() }
        repeat(50) { index ->
            input.send(unit(discontinuity = true).copy(receivedAtNanos = clock.get()))
            until { writes.get() > index || task.isCompleted }
            if (task.isCompleted) fail("Audio pump ended before expected output timeout: ${task.await()}")
            clock.addAndGet(100_000_000)
        }
        val error = withTimeout(500) { task.await() }
        assertTrue(error is RtpAudioStalledException)
        assertTrue(error!!.message!!.contains("output"))
    }

    @Test fun firstPcmDeadlineIncludesTimeWaitingForThePreviousOutputToRetire() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retirement = RtpAudioOutputRetirement()
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val second = AtomicReference(RtpAudioOwnerStatus())
        val a = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, _ ->
            firstStarted.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
        } }, {}, retirement)
        val b = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, _ -> secondStarted.complete(Unit); awaitCancellation() } },
            second::set, retirement, startupTimeoutMillis = 150)
        try {
            a.enable(); firstStarted.await(); a.close(); b.enable()
            delay(100); release.complete(Unit); secondStarted.await()
            // Only the remaining startup budget is available; the pump does not get a new full window.
            withTimeout(100) { while (second.get().state != "stalled") delay(1) }
            assertFalse(second.get().enabled)
            assertTrue(second.get().error!!.contains("manual enable"))
        } finally { release.complete(Unit); a.close(); b.close(); scope.cancel() }
    }

    @Test fun actualFreshPcmRefreshesWatchdogWithoutLimitingTotalPlaybackDuration() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val latest = AtomicReference(RtpAudioOwnerStatus())
        val owner = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, stats ->
            repeat(5) {
                stats(RtpAudioPlaybackStats(decodedAccessUnits = it + 1L, pcmFresh = true, lastFreshPcmAtNanos = System.nanoTime()))
                delay(20)
            }
            awaitCancellation()
        } }, latest::set, RtpAudioOutputRetirement(), startupTimeoutMillis = 30)
        try {
            owner.enable(); until { latest.get().state == "active" }
            delay(60)
            assertTrue(latest.get().enabled)
        } finally { owner.close(); scope.cancel() }
    }

    @Test fun receiverRecoveryOnlyPermitsAnotherManualRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val starts = AtomicInteger()
        val owner = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, _ -> starts.incrementAndGet(); awaitCancellation() } },
            {}, RtpAudioOutputRetirement())
        try {
            owner.enable(); until { starts.get() == 1 }
            owner.setAvailable(false, "receiver failure")
            owner.enable(); delay(10); assertEquals(1, starts.get())
            owner.setAvailable(true); delay(10); assertEquals(1, starts.get())
            owner.enable(); until { starts.get() == 2 }
        } finally { owner.close(); scope.cancel() }
    }

    @Test fun watchdogRevokesBlockedNativeWorkButRetainsRetirementDutyUntilItReturns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val latest = AtomicReference(RtpAudioOwnerStatus())
        val release = CompletableDeferred<Unit>()
        val owner = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, stats ->
            stats(RtpAudioPlaybackStats(decodedAccessUnits = 1, pcmFresh = true, lastFreshPcmAtNanos = System.nanoTime()))
            withContext(NonCancellable) { release.await() } // Models a native prepare/release that has not returned.
        } }, latest::set, RtpAudioOutputRetirement(), startupTimeoutMillis = 40)
        try {
            owner.enable()
            until { latest.get().state == "stalled" }
            assertFalse(latest.get().enabled)
            assertTrue(latest.get().retirementPending)
            release.complete(Unit)
            until { !latest.get().retirementPending }
            assertEquals("stalled", latest.get().state)
        } finally { release.complete(Unit); owner.close(); scope.cancel() }
    }

    @Test fun repeatedTelemetryCannotRefreshTheActualPcmWatchdog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val latest = AtomicReference(RtpAudioOwnerStatus())
        val owner = RtpAudioPlaybackOwner(scope, { RtpAudioPlayer { _, report ->
            val stats = RtpAudioPlaybackStats(decodedAccessUnits = 1, pcmFresh = true, lastFreshPcmAtNanos = System.nanoTime())
            while (true) { report(stats); delay(5) }
        } }, latest::set, RtpAudioOutputRetirement(), startupTimeoutMillis = 40)
        try {
            owner.enable(); until { latest.get().state == "stalled" }
            assertFalse(latest.get().enabled)
        } finally { owner.close(); scope.cancel() }
    }

    private fun pump(backend: Backend, timeout: Long = 2_000_000_000) =
        RtpAudioPlaybackPump(backend, timeoutNanos = timeout, pollMillis = 1, statsIntervalNanos = 1)
    private suspend fun until(condition: () -> Boolean) = withTimeout(2_000) { while (!condition()) delay(1) }
    private fun unit(discontinuity: Boolean = false) = AacAccessUnit(byteArrayOf(1), 0,
        AacStreamFormat(48_000, 2, byteArrayOf(0x11, 0x90.toByte()), "mp4a.40.2"), discontinuity)
    private class Backend : RtpAudioBackend {
        val decoder = Decoder()
        val output = Output()
        var decoderCreates = 0
        var outputCreates = 0
        override fun decoder(format: AacStreamFormat): RtpAudioDecoder { decoderCreates++; decoder.closed = false; return decoder }
        override fun output(format: RtpPcmFormat): RtpPcmOutput { outputCreates++; return output }
    }
    private class Decoder : RtpAudioDecoder {
        val pending = ConcurrentLinkedQueue<RtpDecodedAudio>()
        @Volatile var queued = 0
        @Volatile var closed = false
        var emitEos = true
        var releases = 0
        fun pcm() { pending.add(RtpDecodedAudio.Pcm(ByteBuffer.wrap(ByteArray(16)),
            RtpPcmFormat(48_000, 2, RtpPcmEncoding.PCM_16BIT), release = { releases++ })) }
        override fun queue(accessUnit: AacAccessUnit): Boolean { queued++; return true }
        override fun endInput(): Boolean { if (emitEos) pending.add(RtpDecodedAudio.End); return true }
        override fun poll(): RtpDecodedAudio? = pending.poll()
        override fun close() { closed = true }
    }
    private class Output : RtpPcmOutput {
        val chunks = ConcurrentLinkedQueue<Int>()
        var neverAccept = false
        @Volatile var writes = 0
        @Volatile var accepted = 0
        @Volatile var head = 0L
        @Volatile var closed = false
        override fun write(bytes: ByteBuffer): Int {
            writes++
            val count = if (neverAccept) 0 else (chunks.poll() ?: bytes.remaining()).coerceAtMost(bytes.remaining())
            bytes.position(bytes.position() + count)
            accepted += count
            return count
        }
        override val playbackHeadFrames get() = head
        override val underruns get() = 0
        override fun close() { closed = true }
    }
}
