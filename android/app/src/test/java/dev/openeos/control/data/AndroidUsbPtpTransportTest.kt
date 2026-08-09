package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AndroidUsbPtpTransportTest {
    @Test
    fun exactPayloadUsesStableChunksAndProgress() = runTest {
        val payload = ByteArray(13) { it.toByte() }
        val output = ByteArrayOutputStream()
        val chunks = mutableListOf<Int>()
        val progress = mutableListOf<Long>()

        streamExactPtpPayload(
            source = ByteArrayInputStream(payload),
            payloadLength = payload.size.toLong(),
            bufferBytes = 5,
            onChunk = { bytes, count ->
                chunks += count
                output.write(bytes, 0, count)
            },
            onProgress = { transferred, _ -> progress += transferred },
        )

        assertArrayEquals(payload, output.toByteArray())
        assertEquals(listOf(5, 5, 3), chunks)
        assertEquals(listOf(0L, 5L, 10L, 13L), progress)
    }

    @Test
    fun exactPayloadRejectsShortAndLongSources() = runTest {
        val short = runCatching {
            streamExactPtpPayload(
                source = ByteArrayInputStream(byteArrayOf(1)),
                payloadLength = 2,
                bufferBytes = 2,
                onChunk = { _, _ -> },
                onProgress = { _, _ -> },
            )
        }.exceptionOrNull()
        val long = runCatching {
            streamExactPtpPayload(
                source = ByteArrayInputStream(byteArrayOf(1, 2)),
                payloadLength = 1,
                bufferBytes = 2,
                onChunk = { _, _ -> },
                onProgress = { _, _ -> },
            )
        }.exceptionOrNull()

        assertTrue(short is PtpProtocolException)
        assertTrue(short?.message.orEmpty().contains("ended after 1 of 2"))
        assertTrue(long is PtpProtocolException)
        assertTrue(long?.message.orEmpty().contains("exceeds its declared 1 bytes"))
    }

    @Test
    fun exactPayloadStopsAtCancellationBoundary() = runTest {
        var chunks = 0
        val worker = launch(start = CoroutineStart.LAZY) {
            streamExactPtpPayload(
                source = ByteArrayInputStream(ByteArray(16)),
                payloadLength = 16,
                bufferBytes = 4,
                onChunk = { _, _ ->
                    chunks += 1
                    if (chunks == 1) throw CancellationException("test cancellation")
                },
                onProgress = { _, _ -> },
            )
        }

        worker.start()
        worker.join()

        assertTrue(worker.isCancelled)
        assertEquals(1, chunks)
    }
}
