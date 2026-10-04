package dev.openeos.control.data

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHostPreviewBoundedReadTest {
    @Test fun emptyStreamReturnsEmptyWithoutClosingCallerStream() = runBlocking {
        val input = TrackingInputStream(byteArrayOf())

        assertArrayEquals(byteArrayOf(), input.readBoundedHostPreview(9))
        assertEquals(1, input.bulkReads)
        assertFalse(input.closed)
    }

    @Test fun shortReadsContinueUntilEofAndPreserveZeroBytes() = runBlocking {
        val source = byteArrayOf(1, 0, 2, 0, 3)
        val input = TrackingInputStream(source, chunkSize = 2)

        assertArrayEquals(source, input.readBoundedHostPreview(9))
        assertEquals(4, input.bulkReads)
        assertEquals(source.size, input.bytesRead)
    }

    @Test fun exactPreviewLimitStopsAtEofWithoutInventingOverflowByte() = runBlocking {
        val previewLimit = 8
        val source = ByteArray(previewLimit) { it.toByte() }
        val input = TrackingInputStream(source)

        assertArrayEquals(source, input.readBoundedHostPreview(previewLimit + 1))
        assertEquals(previewLimit, input.bytesRead)
        assertEquals(2, input.bulkReads)
    }

    @Test fun growingSourceReturnsOnlyLimitPlusOneAndLeavesRemainingBytesUnread() = runBlocking {
        val previewLimit = 8
        val source = ByteArray(32) { it.toByte() }
        val input = TrackingInputStream(source, chunkSize = 2)

        val bytes = input.readBoundedHostPreview(previewLimit + 1)

        assertArrayEquals(source.copyOf(previewLimit + 1), bytes)
        assertTrue(bytes.size > previewLimit)
        assertEquals(previewLimit + 1, input.bytesRead)
        assertEquals(5, input.bulkReads)
        assertEquals(source[previewLimit + 1].toInt(), input.read())
    }

    @Test fun multipleBufferGrowthsPreserveBytesAndTheFinalCap() = runBlocking {
        val limit = 2 * 64 * 1024 + 1
        val source = ByteArray(limit + 16) { (it % 251).toByte() }
        val input = TrackingInputStream(source)

        assertArrayEquals(source.copyOf(limit), input.readBoundedHostPreview(limit))
        assertEquals(limit, input.bytesRead)
        assertEquals(3, input.bulkReads)
        assertTrue(input.largestRequest <= 64 * 1024)
    }

    @Test fun zeroByteBulkReadFallsBackToSingleByteRead() = runBlocking {
        val source = byteArrayOf(0, 1, 2)
        val input = TrackingInputStream(source).apply { zeroBulkReads = 1 }

        assertArrayEquals(source, input.readBoundedHostPreview(source.size))
        assertEquals(1, input.singleReads)
        assertEquals(source.size, input.bytesRead)
    }

    @Test fun zeroByteBulkReadFollowedByEofStops() = runBlocking {
        val input = TrackingInputStream(byteArrayOf()).apply { zeroBulkReads = 1 }

        assertArrayEquals(byteArrayOf(), input.readBoundedHostPreview(9))
        assertEquals(1, input.bulkReads)
        assertEquals(1, input.singleReads)
    }

    @Test fun zeroLimitDoesNotReadOrCloseTheStream() = runBlocking {
        val input = TrackingInputStream(byteArrayOf(1))

        assertArrayEquals(byteArrayOf(), input.readBoundedHostPreview(0))
        assertEquals(0, input.bulkReads)
        assertEquals(0, input.singleReads)
        assertFalse(input.closed)
    }

    @Test fun negativeLimitFailsBeforeReading() = runBlocking {
        val input = TrackingInputStream(byteArrayOf(1))

        assertTrue(runCatching { input.readBoundedHostPreview(-1) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, input.bulkReads)
        assertFalse(input.closed)
    }

    @Test fun ioFailureAfterPartialReadPropagatesAndCallerClosesStream() = runBlocking {
        val failure = IOException("Synthetic preview read failure")
        val input = TrackingInputStream(byteArrayOf(1, 2, 3, 4), chunkSize = 2).apply {
            beforeBulkRead = { if (bytesRead == 2) throw failure }
        }

        val result = runCatching { input.use { it.readBoundedHostPreview(9) } }

        assertSame(failure, result.exceptionOrNull())
        assertEquals(2, input.bytesRead)
        assertTrue(input.closed)
    }

    @Test fun callerClosesStreamAfterSuccessfulRead() = runBlocking {
        val input = TrackingInputStream(byteArrayOf(1, 2))

        assertArrayEquals(byteArrayOf(1, 2), input.use { it.readBoundedHostPreview(9) })
        assertTrue(input.closed)
    }

    @Test fun alreadyCancelledReadDoesNotTouchStreamAndCallerClosesIt() = runBlocking {
        val input = TrackingInputStream(byteArrayOf(1, 2))
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            currentCoroutineContext().cancel()
            outcome.complete(runCatching { input.use { it.readBoundedHostPreview(9) } }.exceptionOrNull())
        }
        job.join()

        assertTrue(outcome.await() is CancellationException)
        assertEquals(0, input.bulkReads)
        assertTrue(input.closed)
    }

    @Test fun cancellationBetweenChunksStopsBeforeAnotherRead() =
        assertCancellationDuringRead(sourceSize = 8, maxBytes = 9, chunkSize = 2)

    @Test fun cancellationOnLastAllowedReadDoesNotReturnPreview() =
        assertCancellationDuringRead(sourceSize = 9, maxBytes = 9, chunkSize = 9)

    @Test fun cancellationDuringEofReadDoesNotReturnPreview() =
        assertCancellationDuringRead(sourceSize = 0, maxBytes = 9, chunkSize = 9)

    private fun assertCancellationDuringRead(sourceSize: Int, maxBytes: Int, chunkSize: Int) = runBlocking {
        val input = TrackingInputStream(ByteArray(sourceSize), chunkSize)
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            val context = currentCoroutineContext()
            input.afterBulkRead = { context.cancel() }
            outcome.complete(runCatching { input.use { it.readBoundedHostPreview(maxBytes) } }.exceptionOrNull())
        }
        job.join()

        assertTrue(outcome.await() is CancellationException)
        assertEquals(1, input.bulkReads)
        assertEquals(minOf(sourceSize, chunkSize), input.bytesRead)
        assertTrue(input.closed)
    }

    private class TrackingInputStream(
        private val source: ByteArray,
        private val chunkSize: Int = Int.MAX_VALUE,
    ) : InputStream() {
        var bytesRead = 0
            private set
        var bulkReads = 0
            private set
        var singleReads = 0
            private set
        var largestRequest = 0
            private set
        var closed = false
            private set
        var zeroBulkReads = 0
        var beforeBulkRead: (() -> Unit)? = null
        var afterBulkRead: (() -> Unit)? = null

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(length > 0) { "The bounded reader must not request an empty read." }
            check(!closed) { "The caller already closed the stream." }
            bulkReads++
            largestRequest = maxOf(largestRequest, length)
            beforeBulkRead?.invoke()
            val count = when {
                zeroBulkReads > 0 -> { zeroBulkReads--; 0 }
                bytesRead == source.size -> -1
                else -> {
                    val count = minOf(length, chunkSize, source.size - bytesRead)
                    source.copyInto(buffer, offset, bytesRead, bytesRead + count)
                    bytesRead += count
                    count
                }
            }
            afterBulkRead?.invoke()
            return count
        }

        override fun read(): Int {
            check(!closed) { "The caller already closed the stream." }
            singleReads++
            return if (bytesRead == source.size) -1 else source[bytesRead++].toInt() and 0xFF
        }

        override fun close() {
            closed = true
        }
    }
}
