package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Local sequential coverage only. Native ImageDecoder and real MediaStore still need device tests. */
class AutomaticJpegSequentialEntropyTest {
    @Test fun mcuCoverageRejectsMissingBlocksSurplusEntropyAndNonOnePadding() {
        checkAdmission(jpeg(entropy = bytes(0x3f)), accepted = true)
        checkAdmission(jpeg(width = 16, entropy = bytes(0x0f)), accepted = true)
        checkAdmission(jpeg(width = 16, entropy = bytes(0x3f)), accepted = false)
        checkAdmission(jpeg(entropy = bytes(0x3f, 0)), accepted = false)
        checkAdmission(jpeg(entropy = bytes(0)), accepted = false)
    }

    @Test fun acRunsAndStuffedFfStayWithinEachBlock() {
        // DC=0; three ZRLs; EOB. The final meaningful bit plus padding forms FF00.
        checkAdmission(jpeg(entropy = bytes(0, 0xff, 0), acSymbols = bytes(0xf0, 0), acLength = 2), accepted = true)
        // Four ZRLs or run=15,size=1 symbols overrun the 63 AC positions.
        checkAdmission(jpeg(entropy = bytes(0x07), acSymbols = bytes(0xf0)), accepted = false)
        checkAdmission(jpeg(entropy = bytes(0x55, 0xff, 0), acSymbols = bytes(0xf1)), accepted = false)
        val unstuffed = jpeg(entropy = bytes(0, 0xff), acSymbols = bytes(0xf0, 0), acLength = 2)
        assertThrows(InvalidAutomaticJpegException::class.java) { admit(unstuffed) }
    }

    @Test fun restartIntervalsCountMcusAndAllowShortFinalIntervals() {
        checkAdmission(jpeg(width = 16, entropy = bytes(0x3f, 0xff, 0xd0, 0x3f), restartInterval = 1), accepted = true)
        checkAdmission(jpeg(width = 24, entropy = bytes(0x0f, 0xff, 0xd0, 0x3f), restartInterval = 2), accepted = true)
        checkAdmission(jpeg(width = 16, entropy = bytes(0x3f, 0xff, 0xd0, 0x3f), restartInterval = 2), accepted = false)
        checkAdmission(jpeg(width = 16, entropy = bytes(0x0f), restartInterval = 1), accepted = false)
        // Restart numbering wraps after RST7, but not before all interval MCUs exist.
        val intervals = (0 until 10).fold(byteArrayOf()) { all, index ->
            all + bytes(0x3f) + if (index < 9) bytes(0xff, 0xd0 + index % 8) else byteArrayOf()
        }
        checkAdmission(jpeg(width = 80, entropy = intervals, restartInterval = 1), accepted = true)
    }

    @Test fun oddInterleavedDimensionsRequirePaddedEdgeMcuBlocks() {
        // 17x17 4:2:0: four 16x16 MCUs, each containing four Y + Cb + Cr blocks.
        checkAdmission(sampledJpeg(listOf(listOf(1, 2, 3) to bytes(0, 0, 0, 0, 0, 0))), accepted = true)
        // Encoding only the 17 unpadded component blocks omits seven required edge blocks.
        checkAdmission(sampledJpeg(listOf(listOf(1, 2, 3) to bytes(0, 0, 0, 0, 0x3f))), accepted = false)
    }

    @Test fun separateComponentScansUseTheirOwnSampledDimensions() {
        // 17x17 frame: Y has 3x3 blocks; each chroma plane has 2x2 blocks.
        checkAdmission(sampledJpeg(listOf(
            listOf(1) to bytes(0, 0, 0x3f), listOf(2) to bytes(0), listOf(3) to bytes(0),
        )), accepted = true)
        checkAdmission(sampledJpeg(listOf(
            listOf(1) to bytes(0, 0), listOf(2) to bytes(0), listOf(3) to bytes(0),
        )), accepted = false)
        checkAdmission(sampledJpeg(listOf(
            listOf(1) to bytes(0, 0, 0x3f), listOf(2) to bytes(0x03), listOf(3) to bytes(0),
        )), accepted = false)
    }

    @Test fun longHuffmanCodesStillRequireEveryAmplitudeBit() {
        // 16-bit DC code, eleven amplitude bits, one-bit EOB, four pad bits.
        checkAdmission(jpeg(entropy = bytes(0, 0, 0, 0x0f), dcSymbol = 11, dcLength = 16), accepted = true)
        checkAdmission(jpeg(entropy = bytes(0, 0), dcSymbol = 11, dcLength = 16), accepted = false)
        checkAdmission(jpeg(entropy = bytes(0xff, 0, 0xff, 0), dcSymbol = 11, dcLength = 16), accepted = false)
    }

    @Test fun entropyAdmissionBindsOriginalDigestLengthAndBoundedReads() {
        val original = jpeg(entropy = bytes(0x3f))
        val hash = digest(original)
        assertThrows(InvalidAutomaticJpegException::class.java) {
            validateAutomaticJpegSequentialEntropy(original.inputStream(), original.size.toLong(), "0".repeat(64))
        }
        assertThrows(InvalidAutomaticJpegException::class.java) {
            validateAutomaticJpegSequentialEntropy(original.inputStream(), original.size + 1L, hash)
        }
        var totalRead = 0
        val bounded = object : ByteArrayInputStream(original) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= 64 * 1024)
                return super.read(buffer, offset, minOf(length, 7)).also { if (it > 0) totalRead += it }
            }
        }
        val admitted = validateAutomaticJpegSequentialEntropy(bounded, original.size.toLong(), hash)
        assertEquals(original.size.toLong(), admitted.byteLength)
        assertEquals(hash, admitted.sha256)
        assertEquals(original.size, totalRead)
        val failure = IOException("synthetic local input failure")
        assertSame(failure, runCatching {
            validateAutomaticJpegSequentialEntropy(object : InputStream() { override fun read(): Int = throw failure },
                original.size.toLong(), hash)
        }.exceptionOrNull())
    }

    @Test fun cancellationDuringMcuTraversalIsNotSwallowedOrDelayedUntilEoi() {
        // All encoded bytes fit into one reader refill. Refill cancellation alone is insufficient.
        val original = jpeg(width = 8192, entropy = ByteArray(256))
        assertEquals(8192, admit(original).width)
        val cancelled = CancellationException("synthetic MCU cancellation")
        assertSame(cancelled, runCatching { admit(original) { throw cancelled } }.exceptionOrNull())
        var callbacks = 0
        assertSame(cancelled, runCatching {
            admit(original) { if (++callbacks == 100) throw cancelled }
        }.exceptionOrNull())
        assertEquals(100, callbacks)
    }

    private fun checkAdmission(value: ByteArray, accepted: Boolean) {
        // These cases retain legal marker framing, even when their coefficient coverage is invalid.
        val structure = validateAutomaticJpegStructure(value.inputStream(), value.size.toLong(), digest(value))
        if (accepted) assertEquals(structure, admit(value))
        else assertThrows(InvalidAutomaticJpegException::class.java) { admit(value) }
    }

    private fun admit(value: ByteArray, active: () -> Unit = {}): AutomaticJpegStructure =
        validateAutomaticJpegSequentialEntropy(value.inputStream(), value.size.toLong(), digest(value), active)

    private fun jpeg(width: Int = 8, entropy: ByteArray, dcSymbol: Int = 0, dcLength: Int = 1,
        acSymbols: ByteArray = bytes(0), acLength: Int = 1, restartInterval: Int? = null): ByteArray =
        bytes(0xff, 0xd8) + segment(0xdb, bytes(0) + ByteArray(64) { 1 }) +
            segment(0xc0, bytes(8, 0, 8, width shr 8, width and 255, 1, 1, 0x11, 0)) +
            segment(0xc4, huffman(0, dcLength, bytes(dcSymbol)) + huffman(0x10, acLength, acSymbols)) +
            (restartInterval?.let { segment(0xdd, bytes(it shr 8, it and 255)) } ?: byteArrayOf()) +
            segment(0xda, bytes(1, 1, 0, 0, 63, 0)) + entropy + bytes(0xff, 0xd9)

    private fun sampledJpeg(scans: List<Pair<List<Int>, ByteArray>>): ByteArray {
        var value = bytes(0xff, 0xd8) + segment(0xdb, bytes(0) + ByteArray(64) { 1 }) +
            segment(0xc0, bytes(8, 0, 17, 0, 17, 3, 1, 0x22, 0, 2, 0x11, 0, 3, 0x11, 0)) +
            segment(0xc4, huffman(0, 1, bytes(0)) + huffman(0x10, 1, bytes(0)))
        for ((components, entropy) in scans) {
            val selectors = components.fold(byteArrayOf()) { all, id -> all + bytes(id, 0) }
            value += segment(0xda, bytes(components.size) + selectors + bytes(0, 63, 0)) + entropy
        }
        return value + bytes(0xff, 0xd9)
    }

    private fun huffman(key: Int, length: Int, symbols: ByteArray): ByteArray =
        bytes(key) + ByteArray(16).also { it[length - 1] = symbols.size.toByte() } + symbols
    private fun segment(marker: Int, data: ByteArray): ByteArray =
        bytes(0xff, marker, (data.size + 2) shr 8, (data.size + 2) and 255) + data
    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
    private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
