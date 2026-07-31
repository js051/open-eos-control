package dev.openeos.control.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer

internal data class RtpAudioPlaybackStats(
    val decodedAccessUnits: Long,
    val playedSampleFrames: Long,
    val sampleRate: Int?,
    val channels: Int?,
    val underruns: Int,
    val lastPcmAtMillis: Long?,
)

internal fun interface RtpAudioPlayer {
    suspend fun play(
        accessUnits: ReceiveChannel<AacAccessUnit>,
        onStats: (RtpAudioPlaybackStats) -> Unit,
    )
}

internal class AndroidRtpAudioPlayer : RtpAudioPlayer {
    override suspend fun play(
        accessUnits: ReceiveChannel<AacAccessUnit>,
        onStats: (RtpAudioPlaybackStats) -> Unit,
    ) {
        var decoder: MediaCodec? = null
        var decoderFormat: AacStreamFormat? = null
        var audioTrack: AudioTrack? = null
        var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
        var outputChannels: Int? = null
        var outputSampleRate: Int? = null
        var decodedAccessUnits = 0L
        var playedSampleFrames = 0L
        var lastPcmAtMillis: Long? = null
        var lastReportAtMillis = 0L
        fun reportStats() {
            onStats(
                RtpAudioPlaybackStats(
                    decodedAccessUnits = decodedAccessUnits,
                    playedSampleFrames = playedSampleFrames,
                    sampleRate = outputSampleRate,
                    channels = outputChannels,
                    underruns = audioTrack?.underrunCount ?: 0,
                    lastPcmAtMillis = lastPcmAtMillis,
                )
            )
        }

        try {
            for (accessUnit in accessUnits) {
                currentCoroutineContext().ensureActive()
                val requiresRestart = accessUnit.discontinuity ||
                    !sameAacFormat(decoderFormat, accessUnit.format)
                if (requiresRestart) {
                    releaseAudioTrack(audioTrack)
                    audioTrack = null
                    releaseCodec(decoder)
                    decoder = createDecoder(accessUnit.format)
                    decoderFormat = accessUnit.format
                    outputChannels = null
                    outputSampleRate = null
                }

                val activeDecoder = checkNotNull(decoder)
                val handleOutput: (ByteBuffer?, MediaCodec.BufferInfo?, Boolean) -> Unit = { output, info, formatChanged ->
                    if (formatChanged || audioTrack == null) {
                        val outputFormat = activeDecoder.outputFormat
                        outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outputEncoding = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        releaseAudioTrack(audioTrack)
                        audioTrack = createAudioTrack(
                            sampleRate = checkNotNull(outputSampleRate),
                            channels = checkNotNull(outputChannels),
                            encoding = outputEncoding,
                        )
                    }
                    if (output != null && info != null && info.size > 0) {
                        val track = checkNotNull(audioTrack)
                        writePcm(track, output, info)
                        playedSampleFrames += info.size / bytesPerAudioFrame(outputEncoding, checkNotNull(outputChannels))
                        lastPcmAtMillis = System.currentTimeMillis()
                    }
                }
                queueInput(activeDecoder, accessUnit, handleOutput)
                decodedAccessUnits += 1
                drainOutput(activeDecoder, handleOutput)

                val nowMillis = System.currentTimeMillis()
                if (lastReportAtMillis == 0L || nowMillis - lastReportAtMillis >= AUDIO_STATS_INTERVAL_MILLIS) {
                    lastReportAtMillis = nowMillis
                    reportStats()
                }
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } finally {
            runCatching(::reportStats)
            releaseAudioTrack(audioTrack)
            releaseCodec(decoder)
        }
    }

    private fun createDecoder(format: AacStreamFormat): MediaCodec {
        val mediaFormat = MediaFormat.createAudioFormat(MIME_AAC, format.sampleRate, format.channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(format.initializationData))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_AAC_DECODER_INPUT_BYTES)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(mediaFormat)
            ?: error("Android does not provide a raw AAC decoder for Canon RTP audio.")
        return MediaCodec.createByCodecName(codecName).also { codec ->
            try {
                codec.configure(mediaFormat, null, null, 0)
                codec.start()
            } catch (exception: Exception) {
                codec.release()
                throw exception
            }
        }
    }

    private suspend fun queueInput(
        codec: MediaCodec,
        accessUnit: AacAccessUnit,
        onOutput: (ByteBuffer?, MediaCodec.BufferInfo?, Boolean) -> Unit,
    ) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val inputIndex = codec.dequeueInputBuffer(AAC_DECODER_INPUT_TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = codec.getInputBuffer(inputIndex) ?: error("AAC decoder returned no input buffer.")
                require(accessUnit.bytes.size <= input.remaining()) {
                    "Canon AAC access unit (${accessUnit.bytes.size} bytes) exceeds decoder input capacity (${input.remaining()} bytes)."
                }
                input.put(accessUnit.bytes)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    accessUnit.bytes.size,
                    accessUnit.presentationTimeUs,
                    0,
                )
                return
            }
            drainOutput(codec, onOutput)
        }
    }

    private fun drainOutput(
        codec: MediaCodec,
        onOutput: (ByteBuffer?, MediaCodec.BufferInfo?, Boolean) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onOutput(null, null, true)
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> if (outputIndex >= 0) {
                    val output = codec.getOutputBuffer(outputIndex)
                    try {
                        onOutput(output, info, false)
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                } else {
                    return
                }
            }
        }
    }

    private fun createAudioTrack(sampleRate: Int, channels: Int, encoding: Int): AudioTrack {
        val channelMask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("Canon RTP audio channel count $channels is unsupported.")
        }
        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            "Android AAC decoder output encoding $encoding is unsupported."
        }
        val minimumBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        require(minimumBuffer > 0) { "Android cannot allocate a Canon RTP audio output buffer." }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes((minimumBuffer * 2).coerceAtLeast(MIN_AUDIO_TRACK_BUFFER_BYTES))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "Android failed to initialize Canon RTP audio output." }
        track.play()
        return track
    }

    private fun writePcm(track: AudioTrack, output: ByteBuffer, info: MediaCodec.BufferInfo) {
        output.position(info.offset)
        output.limit(info.offset + info.size)
        var remaining = info.size
        while (remaining > 0) {
            val written = track.write(output, remaining, AudioTrack.WRITE_BLOCKING)
            check(written > 0) { "Android audio output write failed with code $written." }
            remaining -= written
        }
    }
}

private fun sameAacFormat(left: AacStreamFormat?, right: AacStreamFormat): Boolean =
    left?.sampleRate == right.sampleRate &&
        left.channels == right.channels &&
        left.initializationData.contentEquals(right.initializationData)

private fun bytesPerAudioFrame(encoding: Int, channels: Int): Int {
    val bytesPerSample = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> error("Unsupported PCM encoding $encoding.")
    }
    return bytesPerSample * channels
}

private fun releaseCodec(codec: MediaCodec?) {
    runCatching { codec?.stop() }
    runCatching { codec?.release() }
}

private fun releaseAudioTrack(track: AudioTrack?) {
    runCatching { track?.pause() }
    runCatching { track?.flush() }
    runCatching { track?.stop() }
    runCatching { track?.release() }
}

private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
    runCatching { getInteger(key) }.getOrDefault(fallback)

private const val MIME_AAC = "audio/mp4a-latm"
private const val MAX_AAC_DECODER_INPUT_BYTES = 512 * 1024
private const val MIN_AUDIO_TRACK_BUFFER_BYTES = 16 * 1024
private const val AAC_DECODER_INPUT_TIMEOUT_US = 20_000L
private const val AUDIO_STATS_INTERVAL_MILLIS = 500L
