package dev.openeos.control.data

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class OriginalMediaDownloadIntegrityTest(private val bridge: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "bridge={0}")
        fun backends() = listOf(arrayOf(false), arrayOf(true))
    }

    private fun download(size: Long?, bytes: ByteArray, chunked: Boolean = false): Pair<Result<CameraMediaDownloadResult>, ByteArray> = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val item = CameraMediaItem("synthetic-media-id", "IMG_0001.JPG", "image", sizeBytes = size)
            val output = ByteArrayOutputStream()
            val response = MockResponse().setHeader("Content-Type", "image/jpeg").apply {
                if (chunked) setChunkedBody(Buffer().write(bytes), 2) else setBody(Buffer().write(bytes))
            }
            val result = if (bridge) {
                server.enqueue(MockResponse().setBody("""{"ok":true,"service":"open-eos-control-bridge","version":"0.1.0"}"""))
                server.enqueue(MockResponse().setBody("""{"id":"synthetic-session"}"""))
                server.enqueue(response)
                val client = DesktopBridgeClient(server.url("/").toString())
                client.initialize()
                runCatching { client.downloadMedia(item, output) }.also {
                    if (it.isFailure) assertFalse(CameraFeature.MEDIA_DOWNLOAD in client.observedFeatureSnapshot())
                }
            } else {
                server.enqueue(response)
                val client = CcapiClient(server.url("/").toString())
                runCatching { client.downloadMedia(item, output) }.also {
                    if (it.isFailure) assertFalse(CameraFeature.MEDIA_DOWNLOAD in client.observedFeatureSnapshot())
                }
            }
            assertEquals(if (bridge) 3 else 1, server.requestCount)
            result to output.toByteArray()
        } finally { server.shutdown() }
    }

    @Test fun selfConsistentHttpBodyShorterThanOriginalFails() {
        assertTrue(download(8, byteArrayOf(1, 2, 3, 4)).first.isFailure)
    }
    @Test fun emptyOriginalWithoutListedSizeFails() {
        assertTrue(download(null, byteArrayOf()).first.isFailure)
    }
    @Test fun explicitlyZeroListedSizeIsNotReinterpretedAsUnknown() {
        assertTrue(download(0, byteArrayOf(1, 2, 3, 4)).first.isFailure)
    }
    @Test fun chunkedBodyStillHasToMatchListedOriginal() {
        assertTrue(download(8, byteArrayOf(1, 2, 3, 4), chunked = true).first.isFailure)
    }
    @Test fun nonemptyUnknownSizeIsMeasuredWithoutInventingIntegrityEvidence() {
        val result = download(null, byteArrayOf(1, 2, 3, 4), chunked = true).first.getOrThrow()
        assertEquals(4L, result.bytesTransferred)
        assertEquals(4L, result.item.sizeBytes)
    }
    @Test fun exactOriginalPreservesBytesAndMetadata() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val (result, saved) = download(4, bytes)
        assertArrayEquals(bytes, saved)
        assertEquals(4L, result.getOrThrow().bytesTransferred)
    }
}
