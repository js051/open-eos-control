package dev.openeos.control.data

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.nio.ByteBuffer

internal data class AacStreamFormat(
    val sampleRate: Int,
    val channels: Int,
    val initializationData: ByteArray,
    val codec: String?,
)

internal data class AacAccessUnit(
    val bytes: ByteArray,
    val presentationTimeUs: Long,
    val format: AacStreamFormat,
    val discontinuity: Boolean,
    val receivedAtNanos: Long? = null,
)

internal data class RtpAudioPlaybackStats(
    // Compatibility name: counts actual non-config PCM output buffers, never queued AAC input.
    val decodedAccessUnits: Long = 0,
    // AudioTrack playback-head progress. This is not proof of physical audibility.
    val playedSampleFrames: Long = 0,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val underruns: Int = 0,
    val lastPcmAtMillis: Long? = null,
    val queuedAccessUnits: Long = 0,
    val decodedSampleFrames: Long = 0,
    val submittedSampleFrames: Long = 0,
    val lastOutputAtMillis: Long? = null,
    val pcmFresh: Boolean = false,
    val lastFreshPcmAtNanos: Long? = null,
)

internal fun interface RtpAudioPlayer {
    suspend fun play(accessUnits: ReceiveChannel<AacAccessUnit>, onStats: (RtpAudioPlaybackStats) -> Unit)
}

internal enum class RtpPcmEncoding(val bytesPerSample: Int) { PCM_16BIT(2), PCM_FLOAT(4) }
internal data class RtpPcmFormat(val sampleRate: Int, val channels: Int, val encoding: RtpPcmEncoding) {
    val bytesPerFrame: Int get() = channels * encoding.bytesPerSample
    init { require(sampleRate > 0 && channels in 1..2) }
}

internal sealed interface RtpDecodedAudio {
    data class Pcm(val bytes: ByteBuffer, val format: RtpPcmFormat, val endOfStream: Boolean = false,
                   val sourceReceivedAtNanos: Long? = null, val release: () -> Unit = {}) : RtpDecodedAudio
    data object End : RtpDecodedAudio
}

internal interface RtpAudioDecoder : AutoCloseable {
    /** Bounded, non-blocking attempts. Input acceptance is not decode evidence. */
    fun queue(accessUnit: AacAccessUnit): Boolean
    fun endInput(): Boolean
    fun poll(): RtpDecodedAudio?
}

internal interface RtpPcmOutput : AutoCloseable {
    /** Non-blocking write; zero means backpressure, negative means failure. */
    fun write(bytes: ByteBuffer): Int
    val playbackHeadFrames: Long
    val underruns: Int
}

internal interface RtpAudioBackend {
    fun decoder(format: AacStreamFormat): RtpAudioDecoder
    fun output(format: RtpPcmFormat): RtpPcmOutput
}

internal class RtpAudioStalledException(message: String) : IllegalStateException(message)

internal class RtpAudioRetirementException(cause: Throwable) :
    IllegalStateException("Audio resource retirement failed; restart the app before enabling audio again.", cause)

/** Unsigned 32-bit playback head, reset per actual AudioTrack. Writes alone never advance it. */
internal class RtpAudioHeadProgress(initialHead: Long) {
    private var previous = initialHead and 0xffff_ffffL
    var frames = 0L
        private set
    fun observe(rawHead: Long, submitted: Long): Long {
        val next = rawHead and 0xffff_ffffL
        check(next >= previous || (previous >= 0xf000_0000L && next <= 0x0fff_ffffL)) {
            "Audio output playback head reset unexpectedly."
        }
        val delta = (next - previous) and 0xffff_ffffL
        previous = next
        frames = (frames + delta).coerceAtMost(submitted)
        return frames
    }
}

/** Tracks only frames accepted by this output and not yet observed past its playback head. */
internal class RtpPendingPcmFrames {
    private data class Pending(var endFrame: Long, val sourceTime: Long)
    private val pending = java.util.ArrayDeque<Pending>()
    fun submitted(endFrame: Long, sourceTime: Long) {
        val last = pending.peekLast()
        if (last?.sourceTime == sourceTime) last.endFrame = endFrame
        else {
            check(pending.size < 4096) { "Audio output pending-frame attribution exceeded its bounded queue." }
            pending.addLast(Pending(endFrame, sourceTime))
        }
    }
    fun observed(headFrame: Long) {
        while (pending.peekFirst()?.let { it.endFrame <= headFrame } == true) pending.removeFirst()
    }
    fun requireFresh(nowNanos: Long) {
        // Check before accepting a newly observed head advance. A late head cannot retrospectively
        // certify that queued PCM stayed fresh while output progress was unobserved.
        if (pending.peekFirst()?.let { nowNanos - it.sourceTime >= 1_000_000_000L } == true) {
            throw RtpAudioStalledException("Submitted PCM expired without output progress; enable audio manually to retry.")
        }
    }
}

/** Production AAC/PCM pump. Device calls stay behind the small native backend. */
internal class RtpAudioPlaybackPump(
    private val backend: RtpAudioBackend,
    private val nowNanos: () -> Long = System::nanoTime,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val timeoutNanos: Long = 5_000_000_000L,
    private val pollMillis: Long = 10L,
    private val statsIntervalNanos: Long = 500_000_000L,
) : RtpAudioPlayer {
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun play(accessUnits: ReceiveChannel<AacAccessUnit>, onStats: (RtpAudioPlaybackStats) -> Unit) {
        var decoder: RtpAudioDecoder? = null
        var decoderFormat: AacStreamFormat? = null
        var output: RtpPcmOutput? = null
        var outputFormat: RtpPcmFormat? = null
        var head: RtpAudioHeadProgress? = null
        var pendingPcm = RtpPendingPcmFrames()
        var trackSubmitted = 0L
        var retiredProgress = 0L
        var retiredUnderruns = 0
        var stats = RtpAudioPlaybackStats()
        var lastInput = nowNanos()
        var lastDecoded: Long? = nowNanos()
        var lastActualPcm: Long? = null
        var lastProgress: Long? = null
        var lastReport: Long? = null
        var inputClosed = false
        var eosQueued = false
        var eosSeen = false

        fun report(force: Boolean = false) {
            val now = nowNanos()
            if (force || lastReport == null || now - requireNotNull(lastReport) >= statsIntervalNanos) {
                stats = stats.copy(pcmFresh = lastActualPcm?.let { now - it < 1_000_000_000L } == true)
                onStats(stats)
                lastReport = now
            }
        }
        fun observeProgress() {
            val track = output ?: return
            pendingPcm.requireFresh(nowNanos())
            val progress = requireNotNull(head).observe(track.playbackHeadFrames, trackSubmitted)
            pendingPcm.observed(progress)
            val total = retiredProgress + progress
            if (total > stats.playedSampleFrames) {
                lastProgress = nowNanos()
                stats = stats.copy(playedSampleFrames = total, lastOutputAtMillis = nowMillis())
                report(force = true)
            }
            stats = stats.copy(underruns = retiredUnderruns + track.underruns)
        }
        suspend fun checkpoint() {
            currentCoroutineContext().ensureActive()
            observeProgress()
            val now = nowNanos()
            if (!inputClosed && now - lastInput >= timeoutNanos) throw RtpAudioStalledException("No AAC input arrived within the audio timeout; enable audio manually to retry.")
            if (lastDecoded?.let { now - it >= timeoutNanos } == true) throw RtpAudioStalledException("AAC decoder produced no PCM within the audio timeout; enable audio manually to retry.")
            if (lastProgress?.let { now - it >= timeoutNanos } == true) throw RtpAudioStalledException("Audio output progress is unverified/stalled; enable audio manually to retry.")
            report()
            currentCoroutineContext().ensureActive()
        }
        fun retireOutput() {
            val track = output ?: return
            // Do not clear the reference until close returns. Failure blocks future writers.
            val observationError = runCatching { observeProgress() }.exceptionOrNull()
            val underruns = runCatching { track.underruns }.getOrDefault(0)
            try { track.close() } catch (error: Throwable) { throw RtpAudioRetirementException(error) }
            retiredProgress += head?.frames ?: 0L
            retiredUnderruns += underruns
            output = null
            outputFormat = null
            head = null
            pendingPcm = RtpPendingPcmFrames()
            trackSubmitted = 0
            observationError?.let { throw it }
        }
        suspend fun drain() {
            val codec = decoder ?: return
            while (true) {
                checkpoint()
                when (val decoded = codec.poll() ?: break) {
                    RtpDecodedAudio.End -> { eosSeen = true; lastDecoded = null }
                    is RtpDecodedAudio.Pcm -> try {
                        if (decoded.bytes.hasRemaining()) {
                            check(decoded.bytes.remaining() % decoded.format.bytesPerFrame == 0) { "Decoder emitted a partial PCM frame." }
                            val sourceTime = decoded.sourceReceivedAtNanos ?: nowNanos()
                            stats = stats.copy(decodedAccessUnits = stats.decodedAccessUnits + 1,
                                decodedSampleFrames = stats.decodedSampleFrames + decoded.bytes.remaining() / decoded.format.bytesPerFrame,
                                sampleRate = decoded.format.sampleRate, channels = decoded.format.channels,
                                lastPcmAtMillis = nowMillis())
                            if (nowNanos() - sourceTime >= 1_000_000_000L) {
                                if (decoded.endOfStream) { eosSeen = true; lastDecoded = null }
                                continue
                            }
                            lastDecoded = nowNanos()
                            lastActualPcm = sourceTime
                            stats = stats.copy(lastFreshPcmAtNanos = lastDecoded)
                            // First/last PCM evidence must reach the owner before any native output
                            // allocation or write can block; periodic telemetry is not the watchdog.
                            report(force = true)
                            currentCoroutineContext().ensureActive()
                            if (outputFormat != decoded.format) {
                                retireOutput()
                                val created = backend.output(decoded.format)
                                output = created
                                outputFormat = decoded.format
                                head = RtpAudioHeadProgress(created.playbackHeadFrames)
                            }
                            var submittedBytes = trackSubmitted * decoded.format.bytesPerFrame
                            var lastWrite = nowNanos()
                            while (decoded.bytes.hasRemaining()) {
                                if (nowNanos() - sourceTime >= 1_000_000_000L) throw RtpAudioStalledException("PCM expired while waiting for audio output; enable audio manually to retry.")
                                if (nowNanos() - lastWrite >= timeoutNanos) throw RtpAudioStalledException("Audio output write is stalled; enable audio manually to retry.")
                                checkpoint()
                                val remaining = decoded.bytes.remaining()
                                val written = requireNotNull(output).write(decoded.bytes)
                                check(written in 0..remaining) { "Android audio output write failed with code $written." }
                                if (written == 0) delay(pollMillis) else {
                                    lastWrite = nowNanos()
                                    submittedBytes += written
                                    val frames = submittedBytes / decoded.format.bytesPerFrame
                                    stats = stats.copy(submittedSampleFrames = stats.submittedSampleFrames + frames - trackSubmitted)
                                    if (frames > trackSubmitted) pendingPcm.submitted(frames, sourceTime)
                                    trackSubmitted = frames
                                }
                                if (lastProgress == null && (trackSubmitted > (head?.frames ?: 0L) || written == 0)) lastProgress = nowNanos()
                            }
                        }
                        if (decoded.endOfStream) { eosSeen = true; lastDecoded = null }
                    } finally { decoded.release() }
                }
            }
        }

        try {
            report(force = true)
            while (true) {
                checkpoint()
                if (!inputClosed) {
                    // Select atomically between input and the drain tick. A timeout wrapped around
                    // receive can win after receiving a value and silently discard that AU.
                    val received = select {
                        accessUnits.onReceiveCatching { it }
                        onTimeout(pollMillis) { null }
                    }
                    if (received != null) {
                        val unit = received.getOrNull()
                        if (unit == null) {
                            received.exceptionOrNull()?.let { throw it }
                            inputClosed = true
                        } else {
                            if (unit.receivedAtNanos?.let { nowNanos() - it >= 1_000_000_000L } == true) { drain(); continue }
                            lastInput = nowNanos()
                            if (unit.discontinuity || !sameAacFormat(decoderFormat, unit.format)) {
                                retireOutput()
                                try { decoder?.close() }
                                catch (error: Throwable) { throw RtpAudioRetirementException(error) }
                                decoder = null
                                decoderFormat = null
                                decoder = backend.decoder(unit.format)
                                decoderFormat = unit.format
                            }
                            val freshUnit = unit.copy(receivedAtNanos = unit.receivedAtNanos ?: nowNanos())
                            while (true) {
                                if (nowNanos() - requireNotNull(freshUnit.receivedAtNanos) >= 1_000_000_000L) {
                                    throw RtpAudioStalledException("AAC input expired while waiting for decoder capacity; enable audio manually to retry.")
                                }
                                checkpoint()
                                if (requireNotNull(decoder).queue(freshUnit)) break
                                drain(); delay(pollMillis)
                            }
                            stats = stats.copy(queuedAccessUnits = stats.queuedAccessUnits + 1)
                        }
                    }
                }
                // Keep polling on silent RTP, including delayed output after the last received AU.
                if (inputClosed && decoder == null) break
                if (inputClosed && !eosQueued) eosQueued = requireNotNull(decoder).endInput()
                drain()
                if (inputClosed && eosSeen && (head?.frames ?: 0L) >= trackSubmitted) break
                if (inputClosed) delay(pollMillis)
            }
        } finally {
            // All resources retire before the owner/gate can permit a replacement.
            var retirementError: Throwable? = null
            runCatching { observeProgress() }
            runCatching { output?.close() }.exceptionOrNull()?.let { retirementError = it }
            runCatching { decoder?.close() }.exceptionOrNull()?.let { if (retirementError == null) retirementError = it }
            runCatching { report(force = true) }
            retirementError?.let { throw RtpAudioRetirementException(it) }
        }
    }
}

private fun sameAacFormat(left: AacStreamFormat?, right: AacStreamFormat): Boolean =
    left?.sampleRate == right.sampleRate && left.channels == right.channels &&
        left.initializationData.contentEquals(right.initializationData)
