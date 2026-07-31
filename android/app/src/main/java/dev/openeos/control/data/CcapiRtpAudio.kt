package dev.openeos.control.data

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.LatmReader
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.ArrayDeque

internal data class LatmAccessUnit(
    val audioMuxElement: ByteArray,
    val rtpTimestamp: Long,
    val discontinuity: Boolean = false,
)

internal class LatmRtpDepacketizer(
    private val payloadType: Int,
) {
    private var timestamp: Long? = null
    private var expectedSequence: Int? = null
    private var valid = true
    private var pendingDiscontinuity = false
    private val payload = ByteArrayOutputStream()

    fun accept(datagram: ByteArray, length: Int = datagram.size): LatmAccessUnit? {
        val packet = RtpPacketParser.parse(datagram, length) ?: return null
        if (packet.payloadType != payloadType) return null

        if (timestamp != packet.timestamp) {
            if (timestamp != null && payload.size() > 0) pendingDiscontinuity = true
            reset(packet.timestamp)
        } else if (expectedSequence != null && packet.sequenceNumber != expectedSequence) {
            valid = false
            pendingDiscontinuity = true
        }
        expectedSequence = (packet.sequenceNumber + 1) and 0xFFFF
        payload.write(packet.payload)
        if (payload.size() > MAX_LATM_AUDIO_MUX_BYTES) {
            valid = false
            pendingDiscontinuity = true
        }
        if (!packet.marker) return null

        val completed = if (valid && payload.size() > 0 && timestamp != null) {
            LatmAccessUnit(
                audioMuxElement = payload.toByteArray(),
                rtpTimestamp = checkNotNull(timestamp),
                discontinuity = pendingDiscontinuity,
            ).also { pendingDiscontinuity = false }
        } else {
            null
        }
        reset(null)
        return completed
    }

    fun resetAfterDiscontinuity() {
        pendingDiscontinuity = true
        reset(null)
    }

    private fun reset(nextTimestamp: Long?) {
        timestamp = nextTimestamp
        expectedSequence = null
        valid = true
        payload.reset()
    }
}

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
)

internal interface LatmSampleExtractor {
    fun consume(accessUnit: LatmAccessUnit, presentationTimeUs: Long): List<AacAccessUnit>
    fun reset()
}

/** Uses AndroidX Media3's AOSP-derived ISO/IEC 14496-3 LATM parser. */
@OptIn(UnstableApi::class)
internal class Media3LatmSampleExtractor : LatmSampleExtractor {
    private val emittedSamples = ArrayDeque<AacAccessUnit>()
    private val sampleBytes = ByteArrayOutputStream()
    private var currentFormat: AacStreamFormat? = null
    private var currentDiscontinuity = false

    private val trackOutput = object : TrackOutput {
        override fun format(format: Format) {
            val initializationData = format.initializationData.singleOrNull()
                ?: throw IllegalArgumentException("Canon LATM AAC does not contain one AudioSpecificConfig.")
            require(format.sampleRate > 0) { "Canon LATM AAC sample rate is invalid." }
            require(format.channelCount in 1..2) {
                "Canon LATM AAC channel count ${format.channelCount} is unsupported."
            }
            currentFormat = AacStreamFormat(
                sampleRate = format.sampleRate,
                channels = format.channelCount,
                initializationData = initializationData.copyOf(),
                codec = format.codecs,
            )
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            require(sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
                "Canon LATM AAC contains unsupported supplemental sample data."
            }
            require(length in 1..MAX_RAW_AAC_ACCESS_UNIT_BYTES) {
                "Canon LATM AAC access unit size $length is invalid."
            }
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            sampleBytes.write(bytes)
            require(sampleBytes.size() <= MAX_RAW_AAC_ACCESS_UNIT_BYTES) {
                "Canon LATM AAC access unit exceeds $MAX_RAW_AAC_ACCESS_UNIT_BYTES bytes."
            }
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput && offset == 0) return C.RESULT_END_OF_INPUT
                    throw EOFException("Canon LATM AAC sample ended unexpectedly.")
                }
                offset += read
            }
            sampleData(ParsableByteArray(bytes), bytes.size, sampleDataPart)
            return bytes.size
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            require(cryptoData == null) { "Encrypted Canon LATM AAC is unsupported." }
            require(offset == 0) { "Canon LATM AAC emitted overlapping samples." }
            val bytes = sampleBytes.toByteArray()
            sampleBytes.reset()
            require(size == bytes.size) { "Canon LATM AAC sample metadata size does not match its payload." }
            emittedSamples.addLast(
                AacAccessUnit(
                    bytes = bytes,
                    presentationTimeUs = timeUs,
                    format = checkNotNull(currentFormat) { "Canon LATM AAC emitted a sample before its format." },
                    discontinuity = currentDiscontinuity,
                )
            )
            currentDiscontinuity = false
        }
    }
    private val reader = LatmReader(null, 0, RTP_CONTAINER_MIME_TYPE).apply {
        createTracks(
            object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    require(type == C.TRACK_TYPE_AUDIO) { "Canon LATM parser emitted a non-audio track." }
                    return trackOutput
                }

                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            },
            TsPayloadReader.TrackIdGenerator(1, 1),
        )
    }

    override fun consume(accessUnit: LatmAccessUnit, presentationTimeUs: Long): List<AacAccessUnit> {
        require(accessUnit.audioMuxElement.size in 1..MAX_LATM_AUDIO_MUX_BYTES) {
            "Canon RTP LATM audioMuxElement size ${accessUnit.audioMuxElement.size} is invalid."
        }
        currentDiscontinuity = currentDiscontinuity || accessUnit.discontinuity
        reader.packetStarted(presentationTimeUs, 0)
        reader.consume(ParsableByteArray(loasFrame(accessUnit.audioMuxElement)))
        reader.packetFinished(false)
        return buildList {
            while (emittedSamples.isNotEmpty()) add(emittedSamples.removeFirst())
        }
    }

    override fun reset() {
        reader.seek()
        emittedSamples.clear()
        sampleBytes.reset()
        currentFormat = null
        currentDiscontinuity = true
    }
}

internal fun loasFrame(audioMuxElement: ByteArray): ByteArray {
    val size = audioMuxElement.size
    require(size in 1..MAX_LATM_AUDIO_MUX_BYTES) {
        "Canon RTP LATM audioMuxElement size $size is invalid."
    }
    return ByteArray(size + LOAS_HEADER_BYTES).also { frame ->
        frame[0] = LOAS_SYNC_BYTE
        frame[1] = (LOAS_SYNC_SECOND or (size ushr 8)).toByte()
        frame[2] = size.toByte()
        audioMuxElement.copyInto(frame, destinationOffset = LOAS_HEADER_BYTES)
    }
}

internal const val MAX_LATM_AUDIO_MUX_BYTES = 0x1FFF
private const val MAX_RAW_AAC_ACCESS_UNIT_BYTES = 512 * 1024
private const val LOAS_HEADER_BYTES = 3
private const val LOAS_SYNC_BYTE: Byte = 0x56
private const val LOAS_SYNC_SECOND = 0xE0
private const val RTP_CONTAINER_MIME_TYPE = "application/rtp"
