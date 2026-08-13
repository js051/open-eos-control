package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun filenameClassifiesVideoWhenCameraKindIsGeneric() {
        assertTrue(CameraMediaItem("movie", "R6_0001.MP4", "file").isVideoMedia)
    }

    private fun source(requestFactory: (Long) -> List<Request>) = OkHttpCameraMediaStreamSource(
        item = CameraMediaItem("video", "CLIP_0001.MP4", "video", sizeBytes = 10),
        httpClient = OkHttpClient(),
        requestFactory = requestFactory,
    )
}
