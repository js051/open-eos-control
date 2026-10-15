package dev.openeos.control.data

import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Conservative automatic-only envelope. Manual downloads keep their existing format policy. */
internal object AutomaticJpegEnvelope {
    const val MAX_MIME_CHARS = 4_096
    const val MAX_BYTES = 64L * 1024 * 1024
    const val MAX_PIXELS = 64_000_000L
    const val MAX_SIDE = 16_384
    const val MAX_MARKERS = 4_096
    const val MAX_SCANS = 256
    const val DECODE_TARGET_SIDE = 1_024
    const val MAX_DECODE_SIDE = 2_048
}

internal class InvalidAutomaticJpegException(detail: String) :
    IllegalStateException("Automatic JPEG original validation failed: $detail.")

internal data class AutomaticJpegStructure(
    val width: Int,
    val height: Int,
    val byteLength: Long,
    val sha256: String,
    val scans: Int,
)

/** A declaration is never proof of bytes; missing/generic types still require both gates. */
internal fun automaticJpegResponseMimeAdmitted(value: String?): Boolean {
    if (value == null) return true
    if (value.length > AutomaticJpegEnvelope.MAX_MIME_CHARS ||
        value.any { it.code !in 32..126 } || value.isBlank()) return false
    val text = value.trim(' ')
    var offset = 0
    fun spaces() { while (offset < text.length && text[offset] == ' ') offset++ }
    fun consume(character: Char): Boolean {
        if (offset >= text.length || text[offset] != character) return false
        offset++
        return true
    }
    fun token(): String? {
        val start = offset
        while (offset < text.length && text[offset].isMimeToken()) offset++
        return if (start == offset) null else text.substring(start, offset)
    }
    val type = token() ?: return false
    if (!consume('/')) return false
    val subtype = token() ?: return false
    if ("$type/$subtype".lowercase(Locale.ROOT) !in setOf("image/jpeg", "application/octet-stream")) return false
    spaces()
    // A bounded linear cursor avoids the recursive regex stack growth of long quoted values.
    while (offset < text.length) {
        if (!consume(';')) return false
        spaces()
        token() ?: return false
        spaces()
        if (!consume('=')) return false
        spaces()
        if (consume('"')) {
            var closed = false
            while (offset < text.length) {
                val character = text[offset++]
                if (character == '"') {
                    closed = true
                    break
                }
                if (character == '\\') {
                    if (offset == text.length) return false
                    offset++ // A quoted-pair consumes exactly one already-checked ASCII byte.
                }
            }
            if (!closed) return false
        } else token() ?: return false
        spaces()
    }
    return true
}

private fun Char.isMimeToken(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
    this in "!#$%&'*+-.^_`|~"

/** Original delivery is a complete HTTP response, never a range or another successful status. */
internal fun automaticJpegOriginalResponseAdmitted(statusCode: Int, contentRangePresent: Boolean): Boolean =
    statusCode == 200 && !contentRangePresent

/** Duplicate declarations fail even if equal. HTTP libraries may already strip field OWS. */
internal fun automaticJpegResponseHeadersAdmitted(values: List<String>): Boolean =
    values.size <= 1 && automaticJpegResponseMimeAdmitted(values.singleOrNull())

internal fun requireAutomaticJpegLength(length: Long) {
    jpegRequire(length in 1..AutomaticJpegEnvelope.MAX_BYTES, "encoded size is outside the automatic limit")
}

/**
 * One bounded, cancellable local read of the already closed pending original. APP/COM contents
 * are opaque, never confused with image markers. This validates framing/coding declarations;
 * it does not decode entropy or replace the mandatory native decoder gate.
 */
internal fun validateAutomaticJpegStructure(
    input: InputStream,
    expectedLength: Long,
    expectedSha256: String,
    checkActive: () -> Unit = {},
): AutomaticJpegStructure {
    requireAutomaticJpegLength(expectedLength)
    jpegRequire(expectedSha256.matches(Regex("[a-fA-F0-9]{64}")), "missing original digest")
    return AutomaticJpegParser(input, expectedLength, checkActive).parse().also {
        jpegRequire(it.byteLength == expectedLength && it.sha256.equals(expectedSha256, ignoreCase = true),
            "pending bytes differ from the original write")
    }
}

private fun jpegRequire(condition: Boolean, detail: String) {
    if (!condition) throw InvalidAutomaticJpegException(detail)
}

private class AutomaticJpegParser(input: InputStream, expected: Long, private val checkActive: () -> Unit) {
    private val reader = JpegReader(input, expected, checkActive)
    private val quantization = BooleanArray(4)
    private val huffman = Array(2) { BooleanArray(4) }
    private val sequentialAcSafe = BooleanArray(4)
    private val components = linkedMapOf<Int, Int>()
    private val samplingBlocks = mutableMapOf<Int, Int>()
    private val coefficients = mutableMapOf<Int, IntArray>()
    private val scannedComponents = mutableSetOf<Int>()
    private var frame = -1
    private var width = 0
    private var height = 0
    private var markerCount = 0
    private var scanCount = 0
    private var restartInterval = 0

    fun parse(): AutomaticJpegStructure {
        jpegRequire(reader.byte() == 0xff && reader.byte() == 0xd8, "missing SOI")
        countMarker()
        var pendingMarker: Int? = null
        while (true) {
            checkActive()
            val marker = pendingMarker ?: marker()
            pendingMarker = null
            countMarker()
            if (marker == 0xd9) {
                jpegRequire(frame != -1 && scanCount > 0 && scannedComponents.containsAll(components.keys) &&
                    (frame != 0xc2 || coefficients.values.all { it[0] >= 0 }),
                    "missing image scans")
                jpegRequire(reader.byte() == -1, "trailing bytes after EOI")
                return AutomaticJpegStructure(width, height, reader.position, reader.digest(), scanCount)
            }
            jpegRequire(marker in setOf(0xc0, 0xc1, 0xc2, 0xc4, 0xda, 0xdb, 0xdd, 0xfe) || marker in 0xe0..0xef,
                "unsupported or misplaced marker")
            val size = reader.word() - 2
            jpegRequire(size >= 0, "invalid segment length")
            val segment = reader.segment(size)
            when (marker) {
                0xc0, 0xc1, 0xc2 -> readFrame(marker, segment)
                0xdb -> readQuantization(segment)
                0xc4 -> readHuffman(segment)
                0xdd -> {
                    jpegRequire(segment.size == 2, "invalid restart interval")
                    restartInterval = segment.word(0)
                }
                0xda -> {
                    readScan(segment)
                    pendingMarker = entropy()
                }
                // APP and COM segments are length-bounded but their metadata is not interpreted.
            }
        }
    }

    private fun countMarker() {
        markerCount++
        jpegRequire(markerCount <= AutomaticJpegEnvelope.MAX_MARKERS, "too many markers")
    }

    private fun marker(): Int {
        jpegRequire(reader.byte() == 0xff, "expected marker")
        var code = reader.byte()
        while (code == 0xff) code = reader.byte()
        jpegRequire(code in 1..254, "invalid or truncated marker")
        return code
    }

    private fun readFrame(marker: Int, segment: ByteArray) {
        jpegRequire(frame == -1 && segment.size >= 6, "duplicate or truncated frame")
        jpegRequire(segment.u(0) == 8, "unsupported precision")
        height = segment.word(1)
        width = segment.word(3)
        val count = segment.u(5)
        jpegRequire(width in 1..AutomaticJpegEnvelope.MAX_SIDE && height in 1..AutomaticJpegEnvelope.MAX_SIDE &&
            width.toLong() * height <= AutomaticJpegEnvelope.MAX_PIXELS, "dimensions exceed the automatic limit")
        jpegRequire(count in setOf(1, 3, 4) && segment.size == 6 + 3 * count, "invalid frame components")
        repeat(count) { index ->
            val offset = 6 + 3 * index
            val id = segment.u(offset)
            val sampling = segment.u(offset + 1)
            val table = segment.u(offset + 2)
            jpegRequire(id !in components && sampling shr 4 in 1..4 && sampling and 15 in 1..4 && table in 0..3,
                "invalid frame component")
            components[id] = table
            samplingBlocks[id] = (sampling shr 4) * (sampling and 15)
            coefficients[id] = IntArray(64) { -1 }
        }
        frame = marker
    }

    private fun readQuantization(segment: ByteArray) {
        jpegRequire(segment.isNotEmpty(), "empty quantization segment")
        var offset = 0
        while (offset < segment.size) {
            val descriptor = segment.u(offset++)
            val precision = descriptor shr 4
            val id = descriptor and 15
            jpegRequire(precision == 0 && id in 0..3, "unsupported quantization table precision")
            val bytes = 64
            jpegRequire(segment.size - offset >= bytes, "truncated quantization table")
            repeat(64) { index ->
                val value = segment.u(offset + index)
                jpegRequire(value > 0, "zero quantization value")
            }
            quantization[id] = true
            offset += bytes
        }
    }

    private fun readHuffman(segment: ByteArray) {
        jpegRequire(segment.isNotEmpty(), "empty Huffman segment")
        var offset = 0
        while (offset < segment.size) {
            jpegRequire(segment.size - offset >= 17, "truncated Huffman table")
            val descriptor = segment.u(offset++)
            val kind = descriptor shr 4
            val id = descriptor and 15
            jpegRequire(kind in 0..1 && id in 0..3, "invalid Huffman table")
            var symbols = 0
            var available = 1
            repeat(16) {
                val count = segment.u(offset++)
                symbols += count
                available = available * 2 - count
                jpegRequire(available >= 0, "oversubscribed Huffman table")
            }
            jpegRequire(symbols in 1..256 && available > 0 && segment.size - offset >= symbols,
                "invalid or truncated Huffman symbols")
            val values = (0 until symbols).map { segment.u(offset + it) }
            jpegRequire(values.distinct().size == values.size && values.all {
                if (kind == 0) it <= 11 else (it and 15) <= 10
            }, "unsupported or duplicate Huffman symbol")
            if (kind == 1) sequentialAcSafe[id] = values.all { (it and 15) != 0 || it == 0 || it == 0xf0 }
            offset += symbols
            huffman[kind][id] = true
        }
    }

    private fun readScan(segment: ByteArray) {
        jpegRequire(frame != -1 && segment.isNotEmpty(), "scan before frame")
        val count = segment.u(0)
        jpegRequire(count in 1..components.size && segment.size == 4 + 2 * count, "invalid scan components")
        val start = segment.u(1 + 2 * count)
        val end = segment.u(2 + 2 * count)
        val approximation = segment.u(3 + 2 * count)
        val high = approximation shr 4
        val low = approximation and 15
        if (frame == 0xc2) {
            jpegRequire(start in 0..63 && end in start..63 && (start != 0 || end == 0) &&
                (start == 0 || count == 1) && high <= 13 && low <= 13 && (high == 0 || high == low + 1),
                "invalid progressive scan")
        } else {
            jpegRequire(start == 0 && end == 63 && approximation == 0, "invalid sequential scan")
        }
        val selected = mutableSetOf<Int>()
        val frameOrder = components.keys.toList()
        var previousComponent = -1
        repeat(count) { index ->
            val id = segment.u(1 + index * 2)
            val selector = segment.u(2 + index * 2)
            val dc = selector shr 4
            val ac = selector and 15
            jpegRequire(id in components && selected.add(id) && dc in 0..3 && ac in 0..3,
                "unknown or duplicate scan component")
            val componentIndex = frameOrder.indexOf(id)
            jpegRequire(componentIndex > previousComponent, "scan components do not follow frame order")
            previousComponent = componentIndex
            jpegRequire(quantization[components.getValue(id)], "missing quantization table")
            if (frame == 0xc0) jpegRequire(dc <= 1 && ac <= 1, "unsupported baseline Huffman selector")
            if (frame == 0xc2) {
                jpegRequire(if (start == 0) ac == 0 else dc == 0, "nonzero unused progressive selector")
                val prior = coefficients.getValue(id)
                if (start > 0) jpegRequire(prior[0] >= 0, "progressive AC before DC")
                for (coefficient in start..end) {
                    jpegRequire(if (high == 0) prior[coefficient] == -1 else prior[coefficient] == high,
                        "invalid progressive coefficient order")
                    prior[coefficient] = low
                }
            }
            if (start == 0 && high == 0) jpegRequire(huffman[0][dc], "missing DC Huffman table")
            if (end > 0) jpegRequire(huffman[1][ac], "missing AC Huffman table")
            if (frame != 0xc2) {
                jpegRequire(id !in scannedComponents, "repeated sequential component")
                jpegRequire(sequentialAcSafe[ac], "unsupported sequential AC symbol")
            }
        }
        if (count > 1) jpegRequire(selected.sumOf { samplingBlocks.getValue(it) } <= 10,
            "interleaved scan sampling exceeds the automatic limit")
        scannedComponents.addAll(selected)
        scanCount++
        jpegRequire(scanCount <= AutomaticJpegEnvelope.MAX_SCANS, "too many scans")
    }

    private fun entropy(): Int {
        var bytesSinceRestart = 0L
        var nextRestart = 0
        while (true) {
            val value = reader.byte()
            jpegRequire(value >= 0, "truncated entropy data")
            if (value != 0xff) {
                bytesSinceRestart++
                continue
            }
            var next = reader.byte()
            if (next == 0) {
                bytesSinceRestart++
                continue
            }
            while (next == 0xff) next = reader.byte()
            jpegRequire(next in 1..254, "invalid or truncated entropy marker")
            jpegRequire(bytesSinceRestart > 0, "empty entropy interval")
            if (next in 0xd0..0xd7) {
                jpegRequire(restartInterval > 0 && next == 0xd0 + nextRestart, "invalid restart sequence")
                countMarker()
                nextRestart = (nextRestart + 1) % 8
                bytesSinceRestart = 0
            } else return next
        }
    }
}

/** Bounded refill storage and cancellation latency, without allocating the encoded original. */
private class JpegReader(
    private val input: InputStream,
    private val expected: Long,
    private val checkActive: () -> Unit,
) {
    private val buffer = ByteArray(64 * 1024)
    private val hash = MessageDigest.getInstance("SHA-256")
    private var offset = 0
    private var available = 0
    private var read = 0L
    var position = 0L
        private set

    fun byte(): Int {
        if (offset == available) {
            checkActive()
            val wanted = minOf(buffer.size.toLong(), expected - read + 1L).toInt()
            jpegRequire(wanted > 0, "encoded stream exceeds declared size")
            available = input.read(buffer, 0, wanted)
            jpegRequire(available != 0, "local input made no progress")
            offset = 0
            if (available < 0) {
                available = 0
                jpegRequire(read == expected, "encoded stream is shorter than declared size")
                return -1
            }
            read += available
            jpegRequire(read <= expected, "encoded stream exceeds declared size")
            hash.update(buffer, 0, available)
        }
        position++
        return buffer[offset++].toInt() and 255
    }

    fun word(): Int {
        val high = byte()
        val low = byte()
        jpegRequire(high >= 0 && low >= 0, "truncated segment length")
        return high * 256 + low
    }

    fun segment(size: Int): ByteArray = ByteArray(size).also { bytes ->
        for (index in bytes.indices) {
            val value = byte()
            jpegRequire(value >= 0, "truncated segment")
            bytes[index] = value.toByte()
        }
    }

    fun digest(): String = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

private fun ByteArray.u(index: Int): Int = this[index].toInt() and 255
private fun ByteArray.word(index: Int): Int = u(index) * 256 + u(index + 1)
