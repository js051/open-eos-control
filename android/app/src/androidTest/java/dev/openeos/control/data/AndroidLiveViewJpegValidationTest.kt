package dev.openeos.control.data

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Real BitmapFactory decoder, synthetic loopback responses, no physical camera. */
class AndroidLiveViewJpegValidationTest {
    @Test fun envelopeOnlyMultipartIsRejectedBeforeAutoSelectsDecodedJpeg() = runBlocking {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        val jpeg = try { ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray() }
            finally { bitmap.recycle() }
        val envelopeOnly = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        assertTrue(androidDecodesLiveViewJpeg(jpeg))
        assertFalse(androidDecodesLiveViewJpeg(envelopeOnly))
        val server = MockWebServer()
        val events = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"ver110":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/multipart","get":true,"delete":true},{"path":"/shooting/liveview/flip","get":true}]}""")
                request.path?.endsWith("/multipart") == true && request.method == "GET" -> {
                    events += "open"
                    MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=canon").setBody(
                        Buffer().writeUtf8("--canon\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\n\r\n")
                            .write(envelopeOnly).writeUtf8("\r\n")).setHeader("Content-Length", 1_000_000)
                }
                request.path?.endsWith("/multipart") == true -> { events += "close-stream"; MockResponse().setResponseCode(204) }
                request.path?.contains("/flip?") == true -> { events += "jpeg"; MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)) }
                request.method == "POST" -> { events += "start"; MockResponse().setResponseCode(204) }
                request.method == "DELETE" -> { events += "stop"; MockResponse().setResponseCode(204) }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false,
            jpegFrameValidator = ::androidDecodesLiveViewJpeg)
        try {
            client.initialize(); client.startLiveView()
            assertEquals(LiveViewSource.CCAPI_JPEG_POLLING, client.currentLiveViewSource())
            assertArrayEquals(jpeg, client.liveViewFrame(1).bytes)
            assertEquals(listOf("start", "open", "close-stream", "stop", "start", "jpeg"), events.toList())
        } finally { client.stopLiveView(); server.shutdown() }
    }
}
