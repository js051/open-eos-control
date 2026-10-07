package dev.openeos.control.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class SavedJpegStagingTest {
    private val bytes = ByteArray(131_079) { (it % 251).toByte() }

    @Test fun copiesExactlyPublishedBytesAndReportsFinalProgress() = runBlocking {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val copied = copyPublishedJpegOriginal(ByteArrayInputStream(bytes), output, evidence(bytes)) {
            assertEquals(bytes.size.toLong(), it.totalBytes)
            progress += it.bytesTransferred
        }
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(bytes.size.toLong(), copied)
        assertEquals(copied, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun sameLengthReplacementCannotPassPublicationChecksum() {
        val changed = bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertThrows(SavedJpegSourceChangedException::class.java) {
            runBlocking { copyPublishedJpegOriginal(ByteArrayInputStream(changed), ByteArrayOutputStream(), evidence(bytes)) {} }
        }
    }

    @Test fun truncatedOriginalCannotPassRecordedLength() {
        assertThrows(SavedJpegSourceChangedException::class.java) {
            runBlocking { copyPublishedJpegOriginal(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)),
                ByteArrayOutputStream(), evidence(bytes)) {} }
        }
    }

    @Test fun oversizedSourceReadsAtMostOneExcessByteAndDoesNotWriteThatChunk() {
        var readBytes = 0
        val source = object : ByteArrayInputStream(bytes + ByteArray(1000)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
                if (it > 0) readBytes += it
            }
        }
        val output = ByteArrayOutputStream()
        assertThrows(SavedJpegSourceChangedException::class.java) {
            runBlocking { copyPublishedJpegOriginal(source, output, evidence(bytes)) {} }
        }
        assertEquals(bytes.size + 1, readBytes)
        assertTrue(output.size() <= bytes.size)
    }

    @Test fun noProgressInputFailsWithoutSpinning() {
        var reads = 0
        val source = object : InputStream() {
            override fun read(): Int = error("bulk reads only")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; return 0 }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { copyPublishedJpegOriginal(source, ByteArrayOutputStream(), evidence(bytes)) {} }
        }
        assertEquals(1, reads)
    }

    @Test fun readFailureIsNotRetried() {
        var reads = 0
        val source = object : InputStream() {
            override fun read(): Int = error("bulk reads only")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; throw IOException("synthetic read failure") }
        }
        assertThrows(IOException::class.java) {
            runBlocking { copyPublishedJpegOriginal(source, ByteArrayOutputStream(), evidence(bytes)) {} }
        }
        assertEquals(1, reads)
    }

    @Test fun cancellationAfterProgressStopsBeforeAnotherReadOrWrite() {
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val job = requireNotNull(currentCoroutineContext()[Job])
                copyPublishedJpegOriginal(ByteArrayInputStream(bytes), output, evidence(bytes)) { job.cancel() }
            }
        }
        assertEquals(64 * 1024, output.size())
    }

    @Test fun batchSpaceRequiresReserveAndSaturatesOverflow() {
        val size = bytes.size.toLong()
        requireSavedJpegStagingSpace(size, size + SAVED_JPEG_STAGING_RESERVE_BYTES, true)
        assertThrows(SavedJpegStagingSpaceException::class.java) {
            requireSavedJpegStagingSpace(size, size + SAVED_JPEG_STAGING_RESERVE_BYTES - 1, true)
        }
        assertThrows(SavedJpegStagingSpaceException::class.java) { requireSavedJpegStagingSpace(size, Long.MAX_VALUE, false) }
        val huge = savedJpegStagingBytes(listOf(evidence(bytes).copy(byteLength = Long.MAX_VALUE), evidence(bytes)))
        assertEquals(Long.MAX_VALUE, huge)
        assertThrows(SavedJpegStagingSpaceException::class.java) { requireSavedJpegStagingSpace(huge, Long.MAX_VALUE, true) }
    }

    @Test fun admissionRejectsEmptyOversizedOrInvalidPublicationEvidence() {
        assertEquals(bytes.size.toLong() * 2, savedJpegStagingBytes(listOf(evidence(bytes), evidence(bytes))))
        for (items in listOf(emptyList(), List(101) { evidence(bytes) },
            listOf(evidence(bytes).copy(byteLength = 0)), listOf(evidence(bytes).copy(sha256 = "invalid")))) {
            assertThrows(IllegalArgumentException::class.java) { savedJpegStagingBytes(items) }
        }
    }

    private fun evidence(value: ByteArray) = CameraImportOriginalEvidence(value.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
}
