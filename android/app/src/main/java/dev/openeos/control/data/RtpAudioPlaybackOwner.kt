package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/** One real native audio writer at a time, including different RTP sessions and cancelled waiters. */
internal class RtpAudioOutputRetirement {
    private val mutex = Mutex()
    private var retirementFailure: RtpAudioRetirementException? = null
    suspend fun play(block: suspend () -> Unit) {
        var locked = false
        try {
            withTimeout(5_000) { mutex.lock(); locked = true }
            retirementFailure?.let { throw it }
            try { block() }
            catch (error: RtpAudioRetirementException) { retirementFailure = error; throw error }
        } finally { if (locked) mutex.unlock() }
    }
}

internal data class RtpAudioOwnerStatus(
    val epoch: Long = 0,
    val enabled: Boolean = false,
    val state: String = "muted",
    val stats: RtpAudioPlaybackStats = RtpAudioPlaybackStats(),
    val error: String? = null,
    val droppedAccessUnits: Long = 0,
    val retirementPending: Boolean = false,
) {
    val evidenceLevel: String get() = when {
        stats.playedSampleFrames > 0 -> "output-render-progress"
        stats.submittedSampleFrames > 0 -> "submitted-pcm"
        stats.decodedAccessUnits > 0 -> "decoded-pcm"
        stats.queuedAccessUnits > 0 -> "queued-input"
        else -> "none"
    }
}

/** Each manual enable owns its own queue, player and callback epoch. Muting revokes all three. */
internal class RtpAudioPlaybackOwner(
    private val scope: CoroutineScope,
    private val factory: () -> RtpAudioPlayer,
    private val onStatus: (RtpAudioOwnerStatus) -> Unit,
    private val retirement: RtpAudioOutputRetirement = processAudioRetirement,
    private val startupTimeoutMillis: Long = 5_000,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private data class Attempt(val epoch: Long, val input: Channel<AacAccessUnit>, val startedAtNanos: Long,
        var job: Job? = null, var startupWatchdog: Job? = null, var freshPcm: Boolean = false,
        var lastPcmAtNanos: Long = startedAtNanos, var lastPcmEventAtNanos: Long? = null,
        var discontinuity: Boolean = true, var retiring: Boolean = false)
    private val guard = Any()
    private var current: Attempt? = null
    private var status = RtpAudioOwnerStatus()
    private var closed = false
    private var foreground = true
    private var available = true
    private var retiring = 0
    private var retirementError: String? = null

    fun enable() = synchronized(guard) {
        if (closed || !foreground || !available || current != null || retirementError != null) return@synchronized
        val epoch = status.epoch + 1
        val input = Channel<AacAccessUnit>(24, BufferOverflow.DROP_OLDEST) {
            synchronized(guard) {
                if (current?.epoch == epoch) {
                    current?.discontinuity = true
                    status = status.copy(droppedAccessUnits = status.droppedAccessUnits + 1)
                }
            }
        }
        val attempt = Attempt(epoch, input, nowNanos())
        current = attempt
        status = RtpAudioOwnerStatus(epoch = epoch, enabled = true, state = "starting",
            droppedAccessUnits = status.droppedAccessUnits, retirementPending = retiring > 0)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                retirement.play {
                    // Cancellation while waiting cannot acquire/create a new native output.
                    synchronized(guard) { check(current === attempt && !closed && foreground) }
                    factory().play(input) { stats ->
                        synchronized(guard) {
                            if (current === attempt && !closed && foreground) {
                                if (nowNanos() - attempt.lastPcmAtNanos >= startupTimeoutMillis * 1_000_000L) {
                                    mute(error = pcmTimeoutMessage(attempt), state = "stalled")
                                } else {
                                    val pcmEvent = stats.lastFreshPcmAtNanos
                                    if (pcmEvent != null && pcmEvent != attempt.lastPcmEventAtNanos && stats.pcmFresh) {
                                        attempt.freshPcm = true
                                        attempt.lastPcmEventAtNanos = pcmEvent
                                        attempt.lastPcmAtNanos = pcmEvent.coerceAtMost(nowNanos())
                                    }
                                    status = status.copy(stats = stats, state = if (stats.decodedAccessUnits > 0) "active" else "starting")
                                    onStatus(status)
                                }
                            }
                        }
                    }
                }
                finish(attempt, "stalled", "Audio input ended; enable audio manually to retry.")
            } catch (error: CancellationException) {
                // A retirement-wait timeout belongs only to this attempt; plain mute cancellation is quiet.
                if (error is kotlinx.coroutines.TimeoutCancellationException) {
                    finish(attempt, "failed", "Previous audio output has not retired; enable audio manually to retry.")
                }
                throw error
            } catch (error: Exception) {
                if (error is RtpAudioRetirementException) synchronized(guard) {
                    // A retired epoch may not publish PCM, but a real cleanup failure still owns a duty.
                    current?.input?.cancel()
                    if (current !== attempt) current?.job?.cancel()
                    current = null
                    retirementError = error.message
                    status = status.copy(enabled = false, state = "failed",
                        stats = status.stats.copy(pcmFresh = false), error = error.message)
                    onStatus(status)
                }
                finish(attempt, if (error is RtpAudioStalledException) "stalled" else "failed",
                    "Canon RTP audio playback failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
        attempt.job = job
        attempt.startupWatchdog = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                val remaining = synchronized(guard) {
                    if (current !== attempt) return@launch
                    startupTimeoutMillis * 1_000_000L - (nowNanos() - attempt.lastPcmAtNanos)
                }
                if (remaining <= 0) {
                    synchronized(guard) {
                        if (current === attempt && nowNanos() - attempt.lastPcmAtNanos >= startupTimeoutMillis * 1_000_000L) {
                            mute(error = pcmTimeoutMessage(attempt), state = "stalled")
                            return@launch
                        }
                    }
                } else delay((remaining + 999_999L) / 1_000_000L)
            }
        }
        job.invokeOnCompletion {
            attempt.startupWatchdog?.cancel()
            synchronized(guard) {
                if (attempt.retiring) retiring -= 1
                val updated = status.copy(retirementPending = retiring > 0,
                    state = if (current == null && retiring == 0 && status.state == "retiring") "muted" else status.state)
                if (updated != status) { status = updated; onStatus(status) }
            }
        }
        onStatus(status)
        attempt.startupWatchdog?.start()
        job.start()
    }

    fun submit(samples: List<AacAccessUnit>) = synchronized(guard) {
        val attempt = current ?: return@synchronized
        for (sample in samples) {
            val next = sample.copy(discontinuity = sample.discontinuity || attempt.discontinuity,
                receivedAtNanos = System.nanoTime())
            attempt.discontinuity = false
            attempt.input.trySend(next)
        }
    }

    fun mute(error: String? = null, state: String = "muted") = synchronized(guard) {
        val attempt = current
        current = null // Fence callbacks before cancelling native work or publishing mute.
        if (attempt?.job?.isCompleted == false) { attempt.retiring = true; retiring += 1 }
        status = status.copy(enabled = false, stats = status.stats.copy(pcmFresh = false),
            retirementPending = retiring > 0,
            state = if (retirementError != null) "failed" else if (state == "muted" && retiring > 0) "retiring" else state,
            error = retirementError ?: error)
        attempt?.startupWatchdog?.cancel()
        attempt?.input?.cancel()
        attempt?.job?.cancel()
        onStatus(status)
    }

    fun setAvailable(value: Boolean, error: String? = null) = synchronized(guard) {
        available = value
        if (!value) mute(error = error, state = "failed")
        // Parser/receiver recovery only permits a new manual request, never revives the old one.
    }

    fun setForeground(value: Boolean) = synchronized(guard) {
        foreground = value
        if (!value) mute()
        // Returning to the foreground never restores a previous audio request.
    }

    fun close() = synchronized(guard) {
        closed = true
        mute(state = "unavailable")
    }

    private fun pcmTimeoutMessage(attempt: Attempt): String = if (attempt.freshPcm)
        "Audio decoder produced no fresh PCM within 5 seconds; enable audio manually to retry."
    else "No fresh decoded PCM within 5 seconds of manual enable; enable audio manually to retry."

    private fun finish(attempt: Attempt, state: String, error: String) = synchronized(guard) {
        if (current !== attempt) return@synchronized
        current = null
        attempt.startupWatchdog?.cancel()
        attempt.input.cancel()
        status = status.copy(enabled = false, state = state, stats = status.stats.copy(pcmFresh = false), error = error)
        onStatus(status)
    }
}

private val processAudioRetirement = RtpAudioOutputRetirement()
