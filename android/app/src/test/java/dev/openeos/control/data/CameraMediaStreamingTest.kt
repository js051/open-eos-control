package dev.openeos.control.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CameraMediaStreamingTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun readsValidatedHttpRangeAtRequestedPosition() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/10")
                .setHeader("Content-Type", "video/mp4")
                .setBody("456789"),
        )
        val source = source { position ->
            listOf(
                Request.Builder().url(server.url("/clip.mp4"))
                    .header("Range", "bytes=$position-").build(),
            )
        }

        source.open(4).use { handle ->
            val bytes = ByteArray(6)
            assertEquals(6, handle.read(bytes, 0, bytes.size))
            assertArrayEquals("456789".toByteArray(), bytes)
            assertEquals(6L, handle.bytesRemaining)
        }
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun fallsBackToFullResponseAndSkipsToPosition() = runTest {
        server.enqueue(MockResponse().setResponseCode(416).setBody("range unsupported"))
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("0123456789"))
        val source = source { position ->
            listOf(
                Request.Builder().url(server.url("/clip.mp4")).header("Range", "bytes=$position-").build(),
                Request.Builder().url(server.url("/clip.mp4")).build(),
            )
        }

        source.open(4).use { handle ->
            val bytes = ByteArray(3)
            assertEquals(3, handle.read(bytes, 0, bytes.size))
            assertArrayEquals("456".toByteArray(), bytes)
        }
    }

    @Test
    fun rejectsTruncatedRangeWhenDeclaredBytesAreNotReceived() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/10")
                .setHeader("Content-Type", "video/mp4")
                .setChunkedBody("456", 1),
        )
        val source = source { position ->
            listOf(Request.Builder().url(server.url("/clip.mp4")).header("Range", "bytes=$position-").build())
        }

        source.open(4).use { handle ->
            val bytes = ByteArray(6)
            var received = 0
            while (received < 3) {
                received += handle.read(bytes, received, bytes.size - received)
            }
            assertEquals(3, received)
            assertEquals(-1, handle.read(bytes, received, bytes.size - received))
            assertTrue(requireNotNull(handle.bytesRemaining) > 0L)
        }
    }

    @Test
    fun rejectsRangeWhoseContentLengthDisagreesWithContentRange() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/10")
                .setHeader("Content-Length", "3")
                .setHeader("Content-Type", "video/mp4")
                .setBody("456"),
        )
        val source = source { position ->
            listOf(Request.Builder().url(server.url("/clip.mp4")).header("Range", "bytes=$position-").build())
        }

        val failure = runCatching { source.open(4) }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("invalid Content-Length"))
    }

    @Test
    fun closeWaitsForAnOpeningStreamBeforeRunningCleanup() = runTest {
        val openingStarted = CompletableDeferred<Unit>()
        val allowOpenToFinish = CompletableDeferred<Unit>()
        val handleClosed = AtomicBoolean(false)
        val cleanupCount = AtomicInteger(0)
        val item = CameraMediaItem("video", "CLIP_0001.MP4", "video", sizeBytes = 10)
        val delegate = object : CameraMediaStreamSource {
            override val item = item

            override suspend fun open(position: Long): CameraMediaStreamHandle {
                openingStarted.complete(Unit)
                allowOpenToFinish.await()
                return object : CameraMediaStreamHandle {
                    override val bytesRemaining = 10L
                    override val contentType = "video/mp4"
                    override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = -1
                    override fun close() {
                        handleClosed.set(true)
                    }
                }
            }
        }
        val source = CloseAwareCameraMediaStreamSource(delegate) { cleanupCount.incrementAndGet() }
        val opening = async { runCatching { source.open(0) } }
        openingStarted.await()

        source.close()

        assertEquals(0, cleanupCount.get())
        assertFalse(handleClosed.get())
        allowOpenToFinish.complete(Unit)
        val failure = opening.await().exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("closed while opening"))
        assertTrue(handleClosed.get())
        assertEquals(1, cleanupCount.get())
        source.close()
        assertEquals(1, cleanupCount.get())
    }

    @Test
    fun filenameClassifiesVideoWhenCameraKindIsGeneric() {
        assertTrue(CameraMediaItem("movie", "R6_0001.MP4", "file").isVideoMedia)
    }

    private fun source(requestFactory: (Long) -> List<Request>) = OkHttpCameraMediaStreamSource(
        item = CameraMediaItem("video", "CLIP_0001.MP4", "video", sizeBytes = 10),
        httpClient = OkHttpClient(),
        requestFactory = requestFactory,
    )
}
