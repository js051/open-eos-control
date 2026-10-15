package dev.openeos.control.data

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual Android JSONTokener and HTTP path; synthetic camera only. */
class MediaUploadWireInstrumentedTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }
    private fun json(body: String, status: Int = 201) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)

    @Test fun platformNumberCoercionCannotConfirmFloatExponentDuplicateOrLenientReceipt() = runBlocking {
        server.enqueue(json("""{"service":"open-eos-control-bridge"}""", 200))
        server.enqueue(json("""{"id":"synthetic"}""", 200))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        val bodies = listOf(
            "{id:'receipt',name:'SAME.JPG',kind:'image',sizeBytes:12}",
            """{"id":"receipt","name":"SAME.JPG","kind":"image","sizeBytes":12.0}""",
            """{"id":"receipt","name":"SAME.JPG","kind":"image","sizeBytes":12e0}""",
            """{"id":"receipt","name":"SAME.JPG","kind":"image","sizeBytes":12.0000000000000001}""",
            """{"id":"receipt","name":"SAME.JPG","kind":"image","sizeBytes":1,"sizeBytes":12}""",
            """{"id":"receipt","name":"SAME.JPG","kind":"image","sizeBytes":12,"extra":{"x":1,"x":2}}""",
            """{"id":"receipt","name":"SAME.JPG","kind":"image","sizeBytes":12,"sizeB\u0079tes":12}""",
        )
        for (body in bodies) {
            server.enqueue(json(body)); val attempt = MediaUploadAttempt()
            assertNotNull(runCatching {
                client.uploadMedia("SAME.JPG", 12, "image/jpeg", ByteArrayInputStream(ByteArray(12)),
                    evidence = MediaUploadEvidence(attempt::beforeDispatch, attempt::confirm))
            }.exceptionOrNull())
            assertEquals(MediaUploadStatus.UNCERTAIN, attempt.finish())
        }
        server.enqueue(MockResponse().setResponseCode(201).setBody(Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))))
        val attempt = MediaUploadAttempt()
        assertNotNull(runCatching {
            client.uploadMedia("SAME.JPG", 12, "image/jpeg", ByteArrayInputStream(ByteArray(12)),
                evidence = MediaUploadEvidence(attempt::beforeDispatch, attempt::confirm))
        }.exceptionOrNull())
        assertEquals(MediaUploadStatus.UNCERTAIN, attempt.finish())
        assertEquals(2 + bodies.size + 1, server.requestCount)
    }

    @Test fun confirmed201PreservesExactOpaqueIdAndAsciiOnlyFilenameEquivalence() = runBlocking {
        server.enqueue(json("""{"service":"open-eos-control-bridge"}""", 200))
        server.enqueue(json("""{"id":"synthetic"}""", 200))
        server.enqueue(json("""{"id":" id/%2f/é ","name":"café.jpg","kind":"image","sizeBytes":12}"""))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize()
        val attempt = MediaUploadAttempt()
        val result = client.uploadMedia("café.JPG", 12, "image/jpeg", ByteArrayInputStream(ByteArray(12)),
            evidence = MediaUploadEvidence(attempt::beforeDispatch, attempt::confirm))
        assertEquals(" id/%2f/é ", result.item.id)
        assertEquals(MediaUploadStatus.CONFIRMED, attempt.finish())
        assertEquals(3, server.requestCount)
    }
}
