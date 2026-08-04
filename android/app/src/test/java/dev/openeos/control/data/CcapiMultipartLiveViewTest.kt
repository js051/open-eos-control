package dev.openeos.control.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class CcapiMultipartLiveViewTest {
    @Test
    fun parsesQuotedBoundaryAndConsecutiveJpegParts() {
        val first = jpeg(1)
        val second = jpeg(2, 3)
        val stream = multipart("canon-boundary", listOf(first, second), "\r\n")
        val boundary = parseCcapiMultipartBoundary(
            "multipart/x-mixed-replace; charset=binary; boundary=\"canon-boundary\"",
        )
        val reader = CcapiMultipartFrameReader(ByteArrayInputStream(stream), boundary)

        assertArrayEquals(first, reader.nextFrame())
        assertArrayEquals(second, reader.nextFrame())
        assertNull(reader.nextFrame())
    }

    @Test
    fun acceptsCanonDocumentedLfOnlyParts() {
        val frame = jpeg(9)
        val reader = CcapiMultipartFrameReader(
            ByteArrayInputStream(multipart("boundary", listOf(frame), "\n")),
            parseCcapiMultipartBoundary("multipart/x-mixed-replace;boundary=boundary"),
        )

        assertArrayEquals(frame, reader.nextFrame())
    }

    @Test
    fun rejectsNonMultipartResponseAndMissingBoundary() {
        val wrongType = runCatching { parseCcapiMultipartBoundary("image/jpeg") }.exceptionOrNull()
        val missing = runCatching { parseCcapiMultipartBoundary("multipart/x-mixed-replace") }.exceptionOrNull()

        assertTrue(wrongType?.message.orEmpty().contains("multipart/x-mixed-replace"))
        assertTrue(missing?.message.orEmpty().contains("boundary"))
    }

    @Test
    fun rejectsInvalidLengthAndTruncatedFrame() {
        val invalidLength = (
            "--b\nContent-Type: image/jpeg\nContent-Length: -1\n\n" +
                "x\n--b--\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)
        val truncated = (
            "--b\nContent-Type: image/jpeg\nContent-Length: 8\n\n"
            ).toByteArray(StandardCharsets.ISO_8859_1) + jpeg(1)

        val invalidError = runCatching {
            CcapiMultipartFrameReader(ByteArrayInputStream(invalidLength), "b").nextFrame()
        }.exceptionOrNull()
        val truncatedError = runCatching {
            CcapiMultipartFrameReader(ByteArrayInputStream(truncated), "b").nextFrame()
        }.exceptionOrNull()

        assertTrue(invalidError?.message.orEmpty().contains("Content-Length"))
        assertTrue(truncatedError?.message.orEmpty().contains("complete"))
    }

    @Test
    fun rejectsOversizedFrameBeforeAllocatingIt() {
        val payload = (
            "--b\nContent-Type: image/jpeg\nContent-Length: ${CCAPI_MULTIPART_MAX_FRAME_BYTES + 1}\n\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)

        val error = runCatching {
            CcapiMultipartFrameReader(ByteArrayInputStream(payload), "b").nextFrame()
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("safety limit"))
    }

    @Test
    fun extractsBoundaryWithoutCanonDelimiterPrefix() {
        assertEquals("canon", parseCcapiMultipartBoundary("multipart/x-mixed-replace; boundary=--canon"))
    }

    private fun jpeg(vararg body: Int): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        body.map(Int::toByte).toByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun multipart(boundary: String, frames: List<ByteArray>, lineEnding: String): ByteArray {
        val output = ByteArrayOutputStream()
        frames.forEach { frame ->
            output.write("--$boundary$lineEnding".toByteArray(StandardCharsets.ISO_8859_1))
            output.write("Content-Type: image/jpeg$lineEnding".toByteArray(StandardCharsets.ISO_8859_1))
            output.write("Content-Length: ${frame.size}$lineEnding$lineEnding".toByteArray(StandardCharsets.ISO_8859_1))
            output.write(frame)
            output.write(lineEnding.toByteArray(StandardCharsets.ISO_8859_1))
        }
        output.write("--$boundary--$lineEnding".toByteArray(StandardCharsets.ISO_8859_1))
        return output.toByteArray()
    }
}
