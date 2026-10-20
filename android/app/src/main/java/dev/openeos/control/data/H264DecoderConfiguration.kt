package dev.openeos.control.data

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.ParsableNalUnitBitArray
import java.io.ByteArrayOutputStream

/** Raw ISO VUI values. Missing/unspecified values must not become guessed color hints. */
internal data class H264VuiColor(
    val fullRange: Boolean? = null,
    val primaries: Int? = null,
    val transfer: Int? = null,
    val matrix: Int? = null,
)

/** Owns the exact escaped parameter bytes used by a picture, not mutable "latest" state. */
internal class H264DecoderConfiguration internal constructor(
    sps: ByteArray,
    pps: ByteArray,
    val width: Int,
    val height: Int,
    val profileIdc: Int,
    val chromaFormatIdc: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val color: H264VuiColor,
) {
    private val sequence = sps.copyOf()
    private val picture = pps.copyOf()
    val csd0: ByteArray get() = sequence.copyOf()
    val csd1: ByteArray get() = picture.copyOf()
    internal fun matches(sps: ByteArray, pps: ByteArray): Boolean =
        sequence.contentEquals(sps) && picture.contentEquals(pps)

    override fun equals(other: Any?): Boolean = other is H264DecoderConfiguration &&
        sequence.contentEquals(other.sequence) && picture.contentEquals(other.picture)

    override fun hashCode(): Int = 31 * sequence.contentHashCode() + picture.contentHashCode()
}

/** Exactly one admitted RTP picture, with configuration-only NALs removed from media input. */
internal class H264PreparedAccessUnit internal constructor(
    val configuration: H264DecoderConfiguration,
    media: ByteArray,
    val rtpTimestamp: Long,
    val keyFrame: Boolean,
) {
    private val payload = media.copyOf()
    val mediaBytes: ByteArray get() = payload.copyOf()
}

/**
 * Ordered receiver-side configuration resolver. The caller must reject stale receive generations
 * before calling prepare, and keep the returned picture with its original input identity.
 *
 * Parameter changes apply in NAL order. A newer announcement cannot mutate an already admitted
 * picture. Scan complete Annex B data, not optional depacketizer fields: FU-A may carry SPS/PPS.
 * Limits match the existing SDP/AU budgets; no native code, frame decoding, or readiness lives here.
 */
internal class H264DecoderConfigurationStore(initialParameterSets: List<ByteArray> = emptyList()) {
    private var sequences = linkedMapOf<Int, Sequence>()
    private var pictures = linkedMapOf<Int, Picture>()
    private var seenSequence = false
    private var seenPicture = false
    private var pendingMedia = byteArrayOf()
    private var pendingNalCount = 0
    private var needsRandomAccess = false

    init {
        if (initialParameterSets.size <= MAX_PARAMETER_SETS) {
            try {
                val initial = initialParameterSets.map { bytes ->
                    val units = annexBNals(bytes)
                    require(units.size == 1)
                    units.single()
                }
                observeTypes(initial)
                for (nal in initial) {
                    when (nal.type) {
                        7 -> parseSequence(nal).also { sequences[it.id] = it }
                        8 -> parsePicture(nal).also { pictures[it.id] = it }
                        else -> error("Not an AVC parameter set")
                    }
                }
                require(sequences.size + pictures.size <= MAX_PARAMETER_SETS)
            } catch (_: IllegalArgumentException) {
                sequences.clear()
                pictures.clear()
            } catch (_: IllegalStateException) {
                sequences.clear()
                pictures.clear()
            }
        }
    }

    // Observed NAL presence is diagnostic only; truncated sets are not valid configuration.
    val hasSequenceParameterSet: Boolean @Synchronized get() = seenSequence
    val hasPictureParameterSet: Boolean @Synchronized get() = seenPicture

    private fun observeTypes(nals: List<Nal>) {
        seenSequence = seenSequence || nals.any { it.type == 7 }
        seenPicture = seenPicture || nals.any { it.type == 8 }
    }

    /** Rendering retirement drops unowned ancillary media, not the stream's parameter inventory. */
    @Synchronized
    fun discardPendingMedia() {
        pendingMedia = byteArrayOf()
        pendingNalCount = 0
    }

    /** Null means configuration-only, missing configuration, or rejected input; never readiness. */
    @Synchronized
    fun prepare(accessUnit: H264AccessUnit): H264PreparedAccessUnit? {
        if (accessUnit.discontinuity) {
            // Lost transport data invalidates reference continuity, not known parameter IDs.
            // While the sticky Boolean is set, discard standalone ancillary conservatively:
            // config-only input cannot recover, and a second loss must not leak its prefix.
            // Bytes co-located with the eventual complete IDR are still retained exactly.
            discardPendingMedia()
            needsRandomAccess = true
        }
        return try {
            prepareChecked(accessUnit)
        } catch (_: IllegalArgumentException) {
            // A malformed replacement may reuse an old ID. Do not silently decode future pictures
            // against that now-ambiguous cached ID; valid parameter sets must establish it again.
            sequences.clear()
            pictures.clear()
            discardPendingMedia()
            needsRandomAccess = true
            null
        } catch (_: IllegalStateException) {
            sequences.clear()
            pictures.clear()
            discardPendingMedia()
            needsRandomAccess = true
            null
        }
    }

    private fun prepareChecked(accessUnit: H264AccessUnit): H264PreparedAccessUnit? {
        val nals = annexBNals(accessUnit.bytes)
        observeTypes(nals)
        val nextSequences = LinkedHashMap(sequences)
        val nextPictures = LinkedHashMap(pictures)
        var configuration: H264DecoderConfiguration? = null
        var referencedPps: Int? = null
        var keyFrame: Boolean? = null
        var unresolved = false
        val media = ByteArrayOutputStream()
        media.write(pendingMedia)
        var mediaNalCount = pendingNalCount
        fun appendMedia(nal: Nal) {
            require(nal.end - nal.start <= MAX_ACCESS_UNIT_BYTES - media.size())
            require(mediaNalCount < MAX_NALS)
            media.write(nal.bytes, nal.start, nal.end - nal.start)
            mediaNalCount++
        }
        for (nal in nals) {
            when (nal.type) {
                7 -> parseSequence(nal).also { nextSequences[it.id] = it }
                8 -> parsePicture(nal).also { nextPictures[it.id] = it }
                1, 5 -> {
                    val bits = Bits(nal.bytes, nal.payloadStart + 1, nal.end)
                    bits.ue() // first_mb_in_slice
                    bits.ue(9) // slice_type, including the "all slices" values
                    val ppsId = bits.ue(255)
                    require(referencedPps == null || referencedPps == ppsId)
                    referencedPps = ppsId
                    require(keyFrame == null || keyFrame == (nal.type == 5))
                    keyFrame = nal.type == 5
                    val pps = nextPictures[ppsId]
                    val sps = pps?.let { nextSequences[it.sequenceId] }
                    if (sps == null || pps == null) {
                        unresolved = true
                    } else {
                        if (configuration == null) configuration = sps.configuration(pps)
                        else require(configuration.matches(sps.bytes, pps.bytes))
                    }
                    appendMedia(nal)
                }
                2, 3, 4, 19, 20, 21 -> {
                    // Data partitions/extensions need a different slice-header contract.
                    // Never misclassify them as harmless ancillary data.
                    unresolved = true
                }
                6, 9, 12 -> appendMedia(nal) // SEI, AUD, filler retain their exact order and bytes.
                // End-of-sequence/end-of-stream cannot be shifted onto a later live picture.
                // Other extension/reserved syntax needs an explicit contract, not opaque replay.
                else -> error("Unsupported AVC sequence boundary or ancillary NAL")
            }
            require(nextSequences.size + nextPictures.size <= MAX_PARAMETER_SETS)
        }
        sequences = nextSequences
        pictures = nextPictures
        if (unresolved || (keyFrame != null && configuration == null)) {
            discardPendingMedia()
            needsRandomAccess = true
            return null
        }
        if (keyFrame == null) {
            // A Boolean cannot distinguish fresh ancillary from another lost interval while
            // recovering. Retain standalone metadata only after a resolved IDR owns recovery.
            if (!needsRandomAccess) {
                pendingMedia = media.toByteArray()
                pendingNalCount = mediaNalCount
            }
            return null
        }
        discardPendingMedia()
        if (needsRandomAccess && !keyFrame) return null
        if (keyFrame) needsRandomAccess = false
        val resolved = requireNotNull(configuration)
        return H264PreparedAccessUnit(resolved, media.toByteArray(), accessUnit.rtpTimestamp, keyFrame)
    }
}

private data class Nal(val bytes: ByteArray, val start: Int, val payloadStart: Int, val end: Int) {
    val type: Int get() = bytes[payloadStart].toInt() and 31
    fun parameterBytes(): ByteArray {
        require(end - payloadStart <= MAX_PARAMETER_BYTES)
        return START_CODE + bytes.copyOfRange(payloadStart, end)
    }
}

/** Splits without rewriting media start codes, escaped bytes, AUD, or SEI. */
private fun annexBNals(bytes: ByteArray): List<Nal> {
    require(bytes.isNotEmpty() && bytes.size <= MAX_ACCESS_UNIT_BYTES)
    val starts = mutableListOf<Pair<Int, Int>>()
    var offset = 0
    while (offset + 2 < bytes.size) {
        val length = when {
            bytes[offset] != 0.toByte() || bytes[offset + 1] != 0.toByte() -> 0
            bytes[offset + 2] == 1.toByte() -> 3
            offset + 3 < bytes.size && bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 1.toByte() -> 4
            else -> 0
        }
        if (length == 0) offset++ else {
            starts += offset to offset + length
            require(starts.size <= MAX_NALS)
            offset += length
        }
    }
    require(starts.isNotEmpty())
    require((0 until starts.first().first).all { bytes[it] == 0.toByte() })
    return starts.mapIndexed { index, (start, payload) ->
        val end = starts.getOrNull(index + 1)?.first ?: bytes.size
        require(payload < end)
        require(bytes[payload].toInt() and 0x80 == 0)
        require(bytes[payload].toInt() and 31 in 1..23)
        Nal(bytes, if (index == 0) 0 else start, payload, end)
    }
}

private data class Picture(val id: Int, val sequenceId: Int, val bytes: ByteArray)

private data class Sequence(
    val id: Int,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val profile: Int,
    val chroma: Int,
    val lumaDepth: Int,
    val chromaDepth: Int,
    val color: H264VuiColor,
) {
    fun configuration(picture: Picture) = H264DecoderConfiguration(
        bytes, picture.bytes, width, height, profile, chroma, lumaDepth, chromaDepth, color,
    )
}

private fun parameterBits(nal: Nal): Bits {
    require(nal.end - nal.payloadStart <= MAX_PARAMETER_BYTES)
    require(nal.bytes[nal.payloadStart].toInt() and 0x60 != 0)
    // Annex B may carry trailing_zero_8bits outside the RBSP. Preserve them in CSD, but
    // validate/read only through the last nonzero byte rather than mistaking them for syntax.
    var end = nal.end
    while (end > nal.payloadStart + 1 && nal.bytes[end - 1] == 0.toByte()) end--
    var zeros = 0
    for (index in nal.payloadStart + 1 until end) {
        val value = nal.bytes[index].toInt() and 255
        if (zeros == 2) {
            if (value == 3) {
                require(index + 1 < end && nal.bytes[index + 1].toInt() and 255 <= 3)
                zeros = 0
                continue
            }
            require(value > 2)
        }
        zeros = if (value == 0) zeros + 1 else 0
    }
    return Bits(nal.bytes, nal.payloadStart + 1, end)
}

private fun parsePicture(nal: Nal): Picture {
    val bits = parameterBits(nal)
    val id = bits.ue(255)
    val sequence = bits.ue(31)
    // Require the fixed flags following the IDs; full PPS syntax remains the decoder's job.
    bits.skip(2)
    return Picture(id, sequence, nal.parameterBytes())
}

private fun parseSequence(nal: Nal): Sequence {
    val bits = parameterBits(nal)
    val profile = bits.read(8)
    val constraints = bits.read(8)
    require(constraints and 3 == 0)
    bits.read(8) // level_idc: the selected decoder still decides level support.
    val id = bits.ue(31)
    var chroma = 1
    var separatePlane = false
    var lumaDepth = 8
    var chromaDepth = 8
    if (profile in EXTENDED_PROFILES) {
        chroma = bits.ue(3)
        if (chroma == 3) separatePlane = bits.bit()
        lumaDepth += bits.ue(6)
        chromaDepth += bits.ue(6)
        bits.skip(1)
        if (bits.bit()) {
            repeat(if (chroma == 3) 12 else 8) { index ->
                if (bits.bit()) {
                    var last = 8
                    var next = 8
                    repeat(if (index < 6) 16 else 64) {
                        if (next != 0) next = (last + bits.se(-128, 127) + 256) % 256
                        if (next != 0) last = next
                    }
                }
            }
        }
    } else {
        require(profile in setOf(66, 77, 88))
    }
    bits.ue(12) // log2_max_frame_num_minus4
    when (bits.ue(2)) {
        0 -> bits.ue(12)
        1 -> {
            bits.skip(1)
            bits.se()
            bits.se()
            repeat(bits.ue(255)) { bits.se() }
        }
    }
    bits.ue(16) // max_num_ref_frames
    bits.skip(1)
    val widthMbs = bits.ue().toLong() + 1
    val heightMapUnits = bits.ue().toLong() + 1
    val frameOnly = bits.bit()
    if (!frameOnly) bits.skip(1)
    bits.skip(1)
    var width = widthMbs * 16
    var height = heightMapUnits * 16 * if (frameOnly) 1 else 2
    // Bound the actual coded allocation, not only a potentially tiny visible crop.
    // These are the existing native video output resource budgets, not codec capability claims.
    require(width in 1..MAX_DIMENSION.toLong() && height in 1..MAX_DIMENSION.toLong())
    require(width * height <= MAX_PIXELS)
    if (bits.bit()) {
        val left = bits.ue().toLong()
        val right = bits.ue().toLong()
        val top = bits.ue().toLong()
        val bottom = bits.ue().toLong()
        val chromaArrayType = if (separatePlane) 0 else chroma
        val cropX = if (chromaArrayType == 0 || chromaArrayType == 3) 1 else 2
        val cropY = (if (chromaArrayType == 1) 2 else 1) * if (frameOnly) 1 else 2
        width -= (left + right) * cropX
        height -= (top + bottom) * cropY
    }
    require(width in 1..Int.MAX_VALUE.toLong() && height in 1..Int.MAX_VALUE.toLong())
    val color = if (bits.bit()) parseVui(bits) else H264VuiColor()
    bits.trailingBits()
    return Sequence(id, nal.parameterBytes(), width.toInt(), height.toInt(), profile, chroma, lumaDepth, chromaDepth, color)
}

private fun parseVui(bits: Bits): H264VuiColor {
    if (bits.bit()) {
        if (bits.read(8) == 255) {
            require(bits.read(16) > 0)
            require(bits.read(16) > 0)
        }
    }
    if (bits.bit()) bits.skip(1)
    var color = H264VuiColor()
    if (bits.bit()) {
        bits.skip(3)
        val fullRange = bits.bit()
        color = if (bits.bit()) H264VuiColor(
            fullRange, bits.read(8).takeUnless { it == 2 },
            bits.read(8).takeUnless { it == 2 }, bits.read(8).takeUnless { it == 2 },
        ) else H264VuiColor(fullRange)
    }
    if (bits.bit()) {
        bits.ue(5)
        bits.ue(5)
    }
    if (bits.bit()) bits.skip(65)
    val nalHrd = bits.bit()
    if (nalHrd) skipHrd(bits)
    val vclHrd = bits.bit()
    if (vclHrd) skipHrd(bits)
    if (nalHrd || vclHrd) bits.skip(1)
    bits.skip(1)
    if (bits.bit()) {
        bits.skip(1)
        bits.ue(16)
        bits.ue(16)
        bits.ue(16)
        bits.ue(16)
        val reorder = bits.ue(16)
        val buffer = bits.ue(16)
        require(reorder <= buffer)
    }
    return color
}

private fun skipHrd(bits: Bits) {
    val count = bits.ue(31) + 1
    bits.skip(8)
    repeat(count) {
        bits.ue()
        bits.ue()
        bits.skip(1)
    }
    bits.skip(20)
}

/**
 * Reuse the already-installed Media3 escaped-bit reader, adding explicit EOF, Exp-Golomb
 * and syntax-loop bounds. Do not use its color summary: that discards matrix_coefficients.
 */
@OptIn(UnstableApi::class)
private class Bits(bytes: ByteArray, start: Int, end: Int) {
    private val reader = ParsableNalUnitBitArray(bytes, start, end)

    fun read(count: Int): Int {
        require(count in 0..31 && reader.canReadBits(count))
        // Media3 readBits expects a nonzero count. UE(0) has no suffix bits:
        // delegating readBits(0) would return a prefix from the current byte.
        return if (count == 0) 0 else reader.readBits(count)
    }
    fun bit(): Boolean = read(1) != 0
    fun skip(count: Int) {
        require(count >= 0 && reader.canReadBits(count))
        reader.skipBits(count)
    }
    fun ue(maximum: Int = Int.MAX_VALUE): Int {
        var zeros = 0
        while (!bit()) {
            zeros++
            require(zeros <= 31)
        }
        val value = (1L shl zeros) - 1 + read(zeros)
        require(value <= maximum)
        return value.toInt()
    }
    fun se(minimum: Int = -Int.MAX_VALUE, maximum: Int = Int.MAX_VALUE): Int {
        val code = ue()
        val value = if (code and 1 == 0) -(code / 2) else code / 2 + 1
        require(value in minimum..maximum)
        return value
    }
    fun trailingBits() {
        require(bit())
        while (reader.canReadBits(1)) require(!bit())
    }
}

private const val MAX_ACCESS_UNIT_BYTES = 8 * 1024 * 1024
private const val MAX_PARAMETER_BYTES = 64 * 1024
private const val MAX_PARAMETER_SETS = 8
private const val MAX_NALS = 1024
private const val MAX_DIMENSION = 4096
private const val MAX_PIXELS = 8_388_608L
private val START_CODE = byteArrayOf(0, 0, 0, 1)
private val EXTENDED_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
