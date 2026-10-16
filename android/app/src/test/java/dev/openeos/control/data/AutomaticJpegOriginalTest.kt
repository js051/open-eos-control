package dev.openeos.control.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Structure tests are deliberately separate from Android's mandatory native decode gate. */
class AutomaticJpegOriginalTest {
    @Test fun baselineExtendedAndProgressiveFramesPassTheStructuralGate() {
        for (marker in listOf(0xc0, 0xc1, 0xc2)) {
            for (components in listOf(1, 3, 4)) {
                val value = validate(jpeg(frame = marker, components = components))
                assertEquals(16, value.width)
                assertEquals(8, value.height)
                assertEquals(1, value.scans)
            }
        }
    }

    @Test fun opaqueMetadataMayContainMarkerBytesWithoutTerminatingTheOriginal() {
        val jpeg = jpeg(prefix = segment(0xe1, bytes(0xff, 0xd8, 0xff, 0xd9, 0xff, 0xda)) +
            segment(0xfe, ByteArray(65_533) { 0xff.toByte() }))
        assertEquals(jpeg.size.toLong(), validate(jpeg).byteLength)
    }

    @Test fun markerStuffingAndOrderedRestartsAreUnderstood() {
        validate(jpeg(entropy = bytes(0x11, 0xff, 0x00, 0x22)))
        validate(jpeg(prefix = segment(0xdd, bytes(0, 1)),
            entropy = bytes(0x11, 0xff, 0xd0, 0x22, 0xff, 0xd1, 0x33)))
        rejects(jpeg(entropy = bytes(0x11, 0xff, 0xd0, 0x22)))
        rejects(jpeg(prefix = segment(0xdd, bytes(0, 1)), entropy = bytes(0x11, 0xff, 0xd1, 0x22)))
        rejects(jpeg(entropy = bytes(0x11, 0xff, 0xff, 0)))
        rejects(jpeg(entropy = byteArrayOf()))
    }

    @Test fun everyTruncatedPrefixAndTrailingPayloadFailsEvenWithSelfConsistentLengthAndHash() {
        val original = jpeg()
        for (end in 1 until original.size) rejects(original.copyOf(end))
        rejects(original + bytes(0))
        rejects(original + jpeg())
        rejects(bytes(0) + original)
        rejects(bytes(0xff, 0xd8, 0xff, 0xd9))
        rejects("<html>not a JPEG</html>".toByteArray())
        rejects(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
    }

    @Test fun missingTablesFramesUnsupportedPrecisionAndDuplicateComponentsFail() {
        rejects(jpeg(includeTables = false))
        rejects(jpeg(frame = 0xc3))
        rejects(jpeg(precision = 12))
        rejects(jpeg(components = 2))
        rejects(jpeg(prefix = segment(0xdb, bytes(0) + ByteArray(63) { 1 })))
        rejects(jpeg(prefix = segment(0xc4, bytes(0, 3) + ByteArray(15) + bytes(0, 1, 2))))
        rejects(jpeg(prefix = bytes(0xff, 0xfe, 0, 1)))
        rejects(jpeg(prefix = segment(0xdd, bytes(0))))
        val duplicate = jpeg(components = 3).also {
            val sof = markerOffset(it, 0xc0)
            it[sof + 13] = it[sof + 10]
        }
        rejects(duplicate)
    }

    @Test fun malformedScanDeclarationsAndUnknownComponentsFail() {
        val baseline = jpeg()
        val sos = markerOffset(baseline, 0xda)
        listOf(5 to 99, 6 to 0x22, 7 to 1, 8 to 62, 9 to 1).forEach { (offset, value) ->
            rejects(baseline.copyOf().also { it[sos + offset] = value.toByte() })
        }
        rejects(jpeg(frame = 0xc2).also { it[markerOffset(it, 0xda) + 8] = 63 })
        rejects(jpeg(frame = 0xc2).also { it[markerOffset(it, 0xda) + 9] = 0x31 })
    }

    @Test fun supportedEightBitCodingTablesAndScanSamplingAreConservative() {
        rejects(jpeg(prefix = segment(0xdb, bytes(0x10) + ByteArray(128) { 1 })))
        rejects(jpeg(prefix = segment(0xc4, bytes(0, 0, 2) + ByteArray(14) + bytes(0, 0))))
        rejects(jpeg(prefix = segment(0xc4, bytes(0, 1) + ByteArray(15) + bytes(12))))
        rejects(jpeg(prefix = segment(0xc4, bytes(0x10, 1) + ByteArray(15) + bytes(0x0b))))
        val gray = jpeg().also { it[markerOffset(it, 0xc0) + 11] = 0x44 }
        validate(gray) // A noninterleaved scan does not use the interleaved MCU-block sum.
        rejects(jpeg(components = 3).also { it[markerOffset(it, 0xc0) + 11] = 0x44 })
        rejects(jpeg(components = 3).also {
            val scan = markerOffset(it, 0xda)
            it[scan + 5] = 2
            it[scan + 7] = 1
        })
    }

    @Test fun baselineSelectorsAndProgressiveCoefficientOrderingCannotBorrowDecoderTolerance() {
        val tables = segment(0xc4, bytes(2, 1) + ByteArray(15) + bytes(0, 0x12, 1) + ByteArray(15) + bytes(0))
        val baseline = jpeg(prefix = tables).also { it[markerOffset(it, 0xda) + 6] = 0x22 }
        rejects(baseline)
        validate(jpeg(frame = 0xc1, prefix = tables).also { it[markerOffset(it, 0xda) + 6] = 0x22 })
        val dc = jpeg(frame = 0xc2)
        val sos = markerOffset(dc, 0xda)
        val prefix = dc.copyOfRange(0, sos)
        fun scan(start: Int, end: Int, approximation: Int = 0, selector: Int = 0) =
            segment(0xda, bytes(1, 1, selector, start, end, approximation)) + bytes(0x3f)
        rejects(prefix + scan(1, 63) + bytes(0xff, 0xd9)) // AC before DC.
        rejects(prefix + scan(0, 0, 0x10) + bytes(0xff, 0xd9)) // Refinement before first scan.
        rejects(prefix + scan(0, 0) + scan(0, 0) + bytes(0xff, 0xd9)) // Repeated initial DC.
        rejects(prefix + scan(0, 0, selector = 1) + bytes(0xff, 0xd9)) // Unused AC selector.
        rejects(prefix + scan(0, 0) + scan(1, 63, selector = 0x10) + bytes(0xff, 0xd9))
        validate(prefix + scan(0, 0, 1) + scan(0, 0, 0x10) + scan(1, 63) + bytes(0xff, 0xd9))
    }

    @Test fun frameAndEncodedLimitsRejectBeforeNativeDecode() {
        validate(jpeg(width = 16_384, height = 1))
        validate(jpeg(width = 8_000, height = 8_000))
        rejects(jpeg(width = 16_385, height = 1))
        rejects(jpeg(width = 8_001, height = 8_000))
        rejects(jpeg(width = 0))
        rejects(jpeg(height = 0))
        for (length in listOf(0L, AutomaticJpegEnvelope.MAX_BYTES + 1L, Long.MAX_VALUE)) {
            var reads = 0
            val input = object : InputStream() { override fun read(): Int { reads++; return -1 } }
            assertThrows(InvalidAutomaticJpegException::class.java) {
                validateAutomaticJpegStructure(input, length, "0".repeat(64))
            }
            assertEquals(0, reads)
        }
    }

    @Test fun markerLimitIsBoundedAndCancellationIsNotSwallowed() {
        rejects(jpeg(prefix = ByteArray(0).let { initial ->
            (1..AutomaticJpegEnvelope.MAX_MARKERS).fold(initial) { bytes, _ -> bytes + segment(0xfe, byteArrayOf()) }
        }))
        val cancelled = CancellationException("synthetic cancellation")
        assertSame(cancelled, runCatching { validate(jpeg()) { throw cancelled } }.exceptionOrNull())
        var callbacks = 0
        val large = jpeg(prefix = segment(0xfe, ByteArray(65_533) { 1 }) + segment(0xfe, ByteArray(65_533) { 2 }))
        val cause = runCatching { validate(large) { if (++callbacks > 4) throw cancelled } }.exceptionOrNull()
        assertSame(cancelled, cause)
    }

    @Test fun scanCountLimitIsEnforcedIndependentlyOfTheMarkerLimit() {
        val first = jpeg(frame = 0xc2)
        val sos = markerOffset(first, 0xda)
        val scans = (0..63).flatMap { coefficient ->
            (13 downTo 0).map { low ->
                val high = if (low == 13) 0 else low + 1
                segment(0xda, bytes(1, 1, 0, coefficient, coefficient, (high shl 4) or low)) + bytes(0x3f)
            }
        }
        val prefix = first.copyOfRange(0, sos)
        val allowed = scans.take(AutomaticJpegEnvelope.MAX_SCANS).fold(prefix) { value, scan -> value + scan } + bytes(0xff, 0xd9)
        assertEquals(AutomaticJpegEnvelope.MAX_SCANS, validate(allowed).scans)
        rejects(allowed.copyOf(allowed.size - 2) + scans[AutomaticJpegEnvelope.MAX_SCANS] + bytes(0xff, 0xd9))
    }

    @Test fun localReadIsBoundedDetectsReplacementAndPreservesIoFailures() {
        val original = jpeg()
        val digest = digest(original)
        val replacement = original.copyOf().also { it[it.lastIndex - 2] = 0x33 }
        assertThrows(InvalidAutomaticJpegException::class.java) {
            validateAutomaticJpegStructure(ByteArrayInputStream(replacement), original.size.toLong(), digest)
        }
        assertThrows(InvalidAutomaticJpegException::class.java) {
            validateAutomaticJpegStructure(ByteArrayInputStream(original), original.size + 1L, digest)
        }
        assertThrows(InvalidAutomaticJpegException::class.java) {
            validateAutomaticJpegStructure(ByteArrayInputStream(original + bytes(0)), original.size.toLong(), digest)
        }
        var totalRead = 0
        val bounded = object : ByteArrayInputStream(original) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(length <= 64 * 1024)
                return super.read(buffer, offset, minOf(length, 7)).also { if (it > 0) totalRead += it }
            }
        }
        assertEquals(original.size.toLong(), validateAutomaticJpegStructure(bounded, original.size.toLong(), digest).byteLength)
        assertEquals(original.size, totalRead)
        val cause = java.io.IOException("synthetic local failure")
        assertSame(cause, runCatching {
            validateAutomaticJpegStructure(object : InputStream() { override fun read(): Int = throw cause },
                original.size.toLong(), digest)
        }.exceptionOrNull())
    }

    @Test fun missingAndGenericResponseMimeStillRequireBytesButConflictingDeclarationsFail() {
        listOf(null, "image/jpeg", "IMAGE/JPEG", " image/jpeg ", "application/octet-stream",
            "image/jpeg; charset=binary", "image/jpeg; note=\"synthetic;fixture\"").forEach {
            assertTrue("Expected admitted MIME: $it", automaticJpegResponseMimeAdmitted(it))
        }
        listOf("", " ", "\t", "image/jpg", "image/png", "text/plain", "image/jpeg, image/png", "image/jpeg;",
            "image/jpeg; bad", "image/jpeg; bad=", "image/jpeg\n", "image/jpeg; a=\"unterminated",
            "image/jpeg;\tx=y", "image/jpeg; a=é").forEach {
            assertFalse("Expected rejected MIME: $it", automaticJpegResponseMimeAdmitted(it))
        }
    }

    @Test fun mimeLengthHasAnExplicitAsciiBound() {
        val prefix = "image/jpeg; x="
        assertTrue(automaticJpegResponseMimeAdmitted(prefix + "a".repeat(4096 - prefix.length)))
        assertFalse(automaticJpegResponseMimeAdmitted(prefix + "a".repeat(4097 - prefix.length)))
        val quoted = prefix + "\"" + "a".repeat(4096 - prefix.length - 2) + "\""
        assertTrue(automaticJpegResponseMimeAdmitted(quoted))
        assertFalse(automaticJpegResponseMimeAdmitted(quoted.dropLast(1)))
        assertFalse(automaticJpegResponseMimeAdmitted(quoted + "a"))
        assertTrue(automaticJpegResponseMimeAdmitted(prefix + "\"" + "\\\"".repeat(1000) + "\""))
    }

    @Test fun onlyExactHttp200WithoutAnyContentRangeFieldCanBeAnOriginal() {
        for (status in listOf(0, 100, 200, 201, 202, 204, 205, 206, 207, 226, 302, 401, 404, 503)) {
            assertEquals(status == 200, automaticJpegOriginalResponseAdmitted(status, contentRangePresent = false))
            assertFalse(automaticJpegOriginalResponseAdmitted(status, contentRangePresent = true))
        }
    }

    @Test fun duplicateOriginalContentTypeFieldsFailEvenIfTheyAgree() {
        assertTrue(automaticJpegResponseHeadersAdmitted(emptyList()))
        assertTrue(automaticJpegResponseHeadersAdmitted(listOf("image/jpeg")))
        assertFalse(automaticJpegResponseHeadersAdmitted(listOf("")))
        assertFalse(automaticJpegResponseHeadersAdmitted(listOf(" ")))
        assertFalse(automaticJpegResponseHeadersAdmitted(listOf("image/jpeg", "image/jpeg")))
        assertFalse(automaticJpegResponseHeadersAdmitted(listOf("image/jpeg", "image/png")))
    }

    private fun validate(value: ByteArray, active: () -> Unit = {}): AutomaticJpegStructure =
        validateAutomaticJpegStructure(ByteArrayInputStream(value), value.size.toLong(), digest(value), active)

    private fun rejects(value: ByteArray) {
        assertThrows(InvalidAutomaticJpegException::class.java) { validate(value) }
    }

    private fun jpeg(frame: Int = 0xc0, width: Int = 16, height: Int = 8, precision: Int = 8,
        components: Int = 1, prefix: ByteArray = byteArrayOf(), includeTables: Boolean = true,
        entropy: ByteArray = bytes(0x3f)): ByteArray {
        val tables = if (includeTables) segment(0xdb, bytes(0) + ByteArray(64) { 1 }) +
            segment(0xc4, bytes(0, 1) + ByteArray(15) + bytes(0, 0x10, 1) + ByteArray(15) + bytes(0)) else byteArrayOf()
        val frameComponents = (1..components).fold(byteArrayOf()) { values, id -> values + bytes(id, 0x11, 0) }
        val scanComponents = (1..components).fold(byteArrayOf()) { values, id -> values + bytes(id, 0) }
        return bytes(0xff, 0xd8) + prefix + tables + segment(frame,
            bytes(precision, height shr 8, height and 255, width shr 8, width and 255, components) + frameComponents) +
            segment(0xda, bytes(components) + scanComponents + bytes(0, if (frame == 0xc2) 0 else 63, 0)) +
            entropy + bytes(0xff, 0xd9)
    }

    private fun segment(marker: Int, data: ByteArray): ByteArray =
        bytes(0xff, marker, (data.size + 2) shr 8, (data.size + 2) and 255) + data
    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
    private fun markerOffset(value: ByteArray, marker: Int): Int = (0 until value.lastIndex).first {
        value[it] == 0xff.toByte() && value[it + 1] == marker.toByte()
    }
    private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
