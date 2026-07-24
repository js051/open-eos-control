package dev.openeos.control.data

import java.io.ByteArrayOutputStream

data class RtpMediaDescription(
    val kind: String,
    val port: Int,
    val payloadType: Int,
    val codec: String,
    val clockRate: Int,
    val channels: Int? = null,
)

data class CcapiRtpSessionDescription(
    val rawSdp: String,
    val video: RtpMediaDescription,
    val audio: RtpMediaDescription? = null,
)

object CcapiRtpSessionDescriptionParser {
    private val rtpMapPattern = Regex("""^a=rtpmap:(\d+)\s+([^/\s]+)/([0-9]+)(?:/([0-9]+))?$""", RegexOption.IGNORE_CASE)

    fun parse(sdp: String): CcapiRtpSessionDescription {
        require(sdp.isNotBlank()) { "Canon RTP session description is empty." }

        val lines = sdp.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val mappings = lines.mapNotNull { line ->
            val match = rtpMapPattern.matchEntire(line) ?: return@mapNotNull null
            val payloadType = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            payloadType to RtpMapping(
                codec = match.groupValues[2],
                clockRate = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null,
                channels = match.groupValues[4].toIntOrNull(),
            )
        }.toMap()

        val media = lines.filter { it.startsWith("m=", ignoreCase = true) }.mapNotNull { line ->
            val fields = line.substring(2).split(Regex("""\s+"""))
            if (fields.size < 4 || !fields[2].equals("RTP/AVP", ignoreCase = true)) return@mapNotNull null
            val port = fields[1].substringBefore('/').toIntOrNull() ?: return@mapNotNull null
            val advertisedPayloads = fields.drop(3).mapNotNull(String::toIntOrNull)
            val payloadType = if (fields[0].equals("video", ignoreCase = true)) {
                advertisedPayloads.firstOrNull { mappings[it]?.codec.equals("H264", ignoreCase = true) }
            } else {
                advertisedPayloads.firstOrNull { it in mappings }
            } ?: return@mapNotNull null
            val mapping = mappings.getValue(payloadType)
            RtpMediaDescription(
                kind = fields[0].lowercase(),
                port = port,
                payloadType = payloadType,
                codec = mapping.codec,
                clockRate = mapping.clockRate,
                channels = mapping.channels,
            )
        }

        val video = media.firstOrNull {
            it.kind == "video" && it.codec.equals("H264", ignoreCase = true)
        } ?: throw IllegalArgumentException("Canon RTP SDP does not advertise an H.264 video stream.")
        require(video.port in 1..65535) { "Canon RTP video port ${video.port} is invalid." }
        require(video.payloadType in 0..127) { "Canon RTP video payload type ${video.payloadType} is invalid." }
        require(video.clockRate == H264_RTP_CLOCK_RATE) {
            "Canon RTP H.264 clock rate ${video.clockRate} is unsupported; expected $H264_RTP_CLOCK_RATE."
        }

        val audio = media.firstOrNull { it.kind == "audio" }?.also {
            require(it.port in 1..65535) { "Canon RTP audio port ${it.port} is invalid." }
        }
        return CcapiRtpSessionDescription(rawSdp = sdp, video = video, audio = audio)
    }

    private data class RtpMapping(
        val codec: String,
        val clockRate: Int,
        val channels: Int?,
    )
}

internal data class RtpPacket(
    val marker: Boolean,
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val payload: ByteArray,
)

internal object RtpPacketParser {
    fun parse(datagram: ByteArray, length: Int = datagram.size): RtpPacket? {
        if (length !in RTP_MIN_HEADER_BYTES..datagram.size) return null
        val first = datagram[0].toInt() and 0xFF
        if (first ushr 6 != RTP_VERSION) return null

        val csrcCount = first and 0x0F
        val hasExtension = first and 0x10 != 0
        val hasPadding = first and 0x20 != 0
        var payloadStart = RTP_MIN_HEADER_BYTES + csrcCount * 4
        if (payloadStart > length) return null

        if (hasExtension) {
            if (payloadStart + 4 > length) return null
            val extensionWords = readUnsignedShort(datagram, payloadStart + 2)
            payloadStart += 4 + extensionWords * 4
            if (payloadStart > length) return null
        }

        val paddingBytes = if (hasPadding) datagram[length - 1].toInt() and 0xFF else 0
        if (hasPadding && paddingBytes == 0) return null
        if (paddingBytes > length - payloadStart) return null
        val payloadEnd = length - paddingBytes
        if (payloadStart >= payloadEnd) return null

        return RtpPacket(
            marker = datagram[1].toInt() and 0x80 != 0,
            payloadType = datagram[1].toInt() and 0x7F,
            sequenceNumber = readUnsignedShort(datagram, 2),
            timestamp = readUnsignedInt(datagram, 4),
            payload = datagram.copyOfRange(payloadStart, payloadEnd),
        )
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}

internal data class H264AccessUnit(
    val bytes: ByteArray,
    val rtpTimestamp: Long,
    val keyFrame: Boolean,
    val sequenceParameterSet: ByteArray?,
    val pictureParameterSet: ByteArray?,
)

internal class H264RtpDepacketizer(
    private val payloadType: Int,
) {
    private var timestamp: Long? = null
    private var expectedSequence: Int? = null
    private var validAccessUnit = true
    private var fragmentedNal = false
    private var keyFrame = false
    private var sequenceParameterSet: ByteArray? = null
    private var pictureParameterSet: ByteArray? = null
    private var output = ByteArrayOutputStream()

    fun accept(datagram: ByteArray, length: Int = datagram.size): H264AccessUnit? {
        val packet = RtpPacketParser.parse(datagram, length) ?: return null
        if (packet.payloadType != payloadType) return null

        val previousTimestamp = timestamp
        if (previousTimestamp != packet.timestamp) {
            resetAccessUnit(packet.timestamp)
        } else if (expectedSequence != null && packet.sequenceNumber != expectedSequence) {
            validAccessUnit = false
            fragmentedNal = false
        }
        expectedSequence = (packet.sequenceNumber + 1) and 0xFFFF

        val payload = packet.payload
        val nalType = payload[0].toInt() and H264_NAL_TYPE_MASK
        when (nalType) {
            in H264_SINGLE_NAL_MIN..H264_SINGLE_NAL_MAX -> appendNal(payload)
            H264_STAP_A -> appendStapA(payload)
            H264_FU_A -> appendFuA(payload)
            else -> validAccessUnit = false
        }

        if (!packet.marker) return null
        val completedTimestamp = timestamp ?: return null
        val completed = if (validAccessUnit && !fragmentedNal && output.size() > 0) {
            H264AccessUnit(
                bytes = output.toByteArray(),
                rtpTimestamp = completedTimestamp,
                keyFrame = keyFrame,
                sequenceParameterSet = sequenceParameterSet,
                pictureParameterSet = pictureParameterSet,
            )
        } else {
            null
        }
        resetAccessUnit(null)
        return completed
    }

    private fun appendStapA(payload: ByteArray) {
        var offset = 1
        while (offset + 2 <= payload.size) {
            val size = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
            offset += 2
            if (size <= 0 || offset + size > payload.size) {
                validAccessUnit = false
                return
            }
            appendNal(payload.copyOfRange(offset, offset + size))
            offset += size
        }
        if (offset != payload.size) validAccessUnit = false
    }

    private fun appendFuA(payload: ByteArray) {
        if (payload.size < 3) {
            validAccessUnit = false
            return
        }
        val fuIndicator = payload[0].toInt() and 0xFF
        val fuHeader = payload[1].toInt() and 0xFF
        val start = fuHeader and H264_FU_START != 0
        val end = fuHeader and H264_FU_END != 0
        val nalType = fuHeader and H264_NAL_TYPE_MASK
        if ((start && end) || fuHeader and H264_FU_RESERVED != 0) {
            validAccessUnit = false
            fragmentedNal = false
            return
        }

        if (start) {
            if (fragmentedNal) {
                validAccessUnit = false
                return
            }
            fragmentedNal = true
            val reconstructedHeader = ((fuIndicator and H264_NAL_PREFIX_MASK) or nalType).toByte()
            appendStartCode()
            output.write(reconstructedHeader.toInt())
            output.write(payload, 2, payload.size - 2)
            observeNalType(nalType, null)
        } else {
            if (!fragmentedNal) {
                validAccessUnit = false
                return
            }
            output.write(payload, 2, payload.size - 2)
        }
        if (end) fragmentedNal = false
        enforceSizeLimit()
    }

    private fun appendNal(nal: ByteArray) {
        if (nal.isEmpty()) {
            validAccessUnit = false
            return
        }
        appendStartCode()
        output.write(nal)
        observeNalType(nal[0].toInt() and H264_NAL_TYPE_MASK, nal)
        enforceSizeLimit()
    }

    private fun observeNalType(type: Int, nal: ByteArray?) {
        when (type) {
            H264_IDR -> keyFrame = true
            H264_SPS -> sequenceParameterSet = nal?.withStartCode()
            H264_PPS -> pictureParameterSet = nal?.withStartCode()
        }
    }

    private fun appendStartCode() = output.write(H264_START_CODE)

    private fun enforceSizeLimit() {
        if (output.size() > MAX_H264_ACCESS_UNIT_BYTES) validAccessUnit = false
    }

    private fun resetAccessUnit(nextTimestamp: Long?) {
        timestamp = nextTimestamp
        validAccessUnit = true
        fragmentedNal = false
        keyFrame = false
        sequenceParameterSet = null
        pictureParameterSet = null
        output = ByteArrayOutputStream()
    }
}

private fun ByteArray.withStartCode(): ByteArray = H264_START_CODE + this

const val H264_RTP_CLOCK_RATE = 90_000
private const val RTP_VERSION = 2
private const val RTP_MIN_HEADER_BYTES = 12
private const val H264_NAL_TYPE_MASK = 0x1F
private const val H264_NAL_PREFIX_MASK = 0xE0
private const val H264_SINGLE_NAL_MIN = 1
private const val H264_SINGLE_NAL_MAX = 23
private const val H264_IDR = 5
private const val H264_SPS = 7
private const val H264_PPS = 8
private const val H264_STAP_A = 24
private const val H264_FU_A = 28
private const val H264_FU_START = 0x80
private const val H264_FU_END = 0x40
private const val H264_FU_RESERVED = 0x20
private const val MAX_H264_ACCESS_UNIT_BYTES = 8 * 1024 * 1024
private val H264_START_CODE = byteArrayOf(0, 0, 0, 1)
