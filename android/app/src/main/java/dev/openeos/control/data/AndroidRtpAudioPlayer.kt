package dev.openeos.control.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlinx.coroutines.channels.ReceiveChannel
import java.nio.ByteBuffer

internal class AndroidRtpAudioPlayer : RtpAudioPlayer {
    override suspend fun play(accessUnits: ReceiveChannel<AacAccessUnit>, onStats: (RtpAudioPlaybackStats) -> Unit) =
        RtpAudioPlaybackPump(AndroidRtpAudioBackend()).play(accessUnits, onStats)
}

private class AndroidRtpAudioBackend : RtpAudioBackend {
    override fun decoder(format: AacStreamFormat): RtpAudioDecoder {
        val mediaFormat = MediaFormat.createAudioFormat(MIME_AAC, format.sampleRate, format.channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(format.initializationData))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_AAC_DECODER_INPUT_BYTES)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(mediaFormat)
            ?: error("Android does not provide a raw AAC decoder for Canon RTP audio.")
        val codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(mediaFormat, null, null, 0)
            codec.start()
        } catch (error: Throwable) {
            try { codec.release() } catch (release: Throwable) { throw RtpAudioRetirementException(release) }
            throw error
        }
        return AndroidAacDecoder(codec)
    }

    override fun output(format: RtpPcmFormat): RtpPcmOutput {
        val mask = when (format.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("Unsupported PCM channel count.")
        }
        val encoding = when (format.encoding) {
            RtpPcmEncoding.PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
            RtpPcmEncoding.PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
        }
        val minimum = AudioTrack.getMinBufferSize(format.sampleRate, mask, encoding)
        require(minimum > 0) { "Android cannot allocate a Canon RTP audio output buffer." }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(format.sampleRate)
                .setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes((minimum * 2).coerceAtLeast(MIN_AUDIO_TRACK_BUFFER_BYTES))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val result = AndroidPcmOutput(track)
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Android failed to initialize Canon RTP audio output." }
            track.play()
        } catch (error: Throwable) {
            try { result.close() } catch (release: Throwable) { throw RtpAudioRetirementException(release) }
            throw error
        }
        return result
    }
}

private class AndroidAacDecoder(private val codec: MediaCodec) : RtpAudioDecoder {
    private var closed = false
    private var lastTimeUs = -1L
    private val inputTimes = linkedMapOf<Long, Long>()
    private var previousOutputSource: Pair<Long, Long>? = null
    override fun queue(accessUnit: AacAccessUnit): Boolean {
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return false
        val input = codec.getInputBuffer(index) ?: error("AAC decoder returned no input buffer.")
        input.clear()
        require(accessUnit.bytes.size <= input.remaining()) { "AAC access unit exceeds decoder input capacity." }
        input.put(accessUnit.bytes)
        check(inputTimes.size < 256) { "AAC decoder output attribution exceeded its bounded queue." }
        lastTimeUs = maxOf(accessUnit.presentationTimeUs, lastTimeUs + 1)
        inputTimes[lastTimeUs] = accessUnit.receivedAtNanos ?: System.nanoTime()
        codec.queueInputBuffer(index, 0, accessUnit.bytes.size, lastTimeUs, 0)
        return true
    }
    override fun endInput(): Boolean {
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return false
        codec.queueInputBuffer(index, 0, 0, lastTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return true
    }
    override fun poll(): RtpDecodedAudio? {
        // Bound even a broken codec that repeats format notifications forever.
        repeat(16) {
            val info = MediaCodec.BufferInfo()
            when (val index = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                else -> {
                    if (index < 0) return null
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size <= 0 || config) {
                        codec.releaseOutputBuffer(index, false)
                        if (eos) return RtpDecodedAudio.End
                    } else {
                        try {
                            val bytes = checkNotNull(codec.getOutputBuffer(index)) { "AAC decoder returned no PCM buffer." }
                            bytes.position(info.offset)
                            bytes.limit(info.offset + info.size)
                            val format = codec.outputFormat
                            val encoding = when (format.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)) {
                                AudioFormat.ENCODING_PCM_16BIT -> RtpPcmEncoding.PCM_16BIT
                                AudioFormat.ENCODING_PCM_FLOAT -> RtpPcmEncoding.PCM_FLOAT
                                else -> error("Android AAC decoder output encoding is unsupported.")
                            }
                            // Exact PTS attribution when available; unknown decoder timestamps use the
                            // oldest outstanding input conservatively rather than inventing fresh PCM.
                            val sourceTime = previousOutputSource?.takeIf { it.first == info.presentationTimeUs }?.second
                                ?: inputTimes[info.presentationTimeUs] ?: inputTimes.values.firstOrNull()
                                ?: error("AAC output has no attributable input.")
                            previousOutputSource = info.presentationTimeUs to sourceTime
                            if (inputTimes.containsKey(info.presentationTimeUs)) {
                                val completed = inputTimes.keys.takeWhile { it <= info.presentationTimeUs }
                                completed.forEach(inputTimes::remove)
                            }
                            return RtpDecodedAudio.Pcm(bytes.slice(), RtpPcmFormat(
                                format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding),
                                eos, sourceReceivedAtNanos = sourceTime, release = { codec.releaseOutputBuffer(index, false) })
                        } catch (error: Throwable) {
                            codec.releaseOutputBuffer(index, false)
                            throw error
                        }
                    }
                }
            }
        }
        return null
    }
    override fun close() {
        if (closed) return
        runCatching { codec.stop() }
        val release = runCatching { codec.release() }.exceptionOrNull()
        if (release == null) closed = true
        // A failed release cannot certify retirement even when stop succeeded.
        release?.let { throw it }
    }
}

private class AndroidPcmOutput(private val track: AudioTrack) : RtpPcmOutput {
    private var closed = false
    private var finalUnderruns = 0
    override fun write(bytes: ByteBuffer): Int = track.write(bytes, bytes.remaining(), AudioTrack.WRITE_NON_BLOCKING)
    override val playbackHeadFrames: Long get() = track.playbackHeadPosition.toLong() and 0xffff_ffffL
    override val underruns: Int get() = if (closed) finalUnderruns else track.underrunCount
    override fun close() {
        if (closed) return
        finalUnderruns = runCatching { track.underrunCount }.getOrDefault(finalUnderruns)
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        val release = runCatching { track.release() }.exceptionOrNull()
        if (release == null) closed = true
        // Successful release is the retirement boundary even if pause/flush/stop rejected the state.
        release?.let { throw it }
    }
}

private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int = runCatching { getInteger(key) }.getOrDefault(fallback)
private const val MIME_AAC = "audio/mp4a-latm"
private const val MAX_AAC_DECODER_INPUT_BYTES = 512 * 1024
private const val MIN_AUDIO_TRACK_BUFFER_BYTES = 16 * 1024
