package dev.openeos.control.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual Android JSONTokener/HTTP path negatives, not substituted JVM JSON or physical-camera evidence. */
class MediaMetadataWireInstrumentedTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }
    private fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)

    @Test fun bridgeWriteCannotConfirmLenientSyntaxDuplicateKeysOrFloatingTokens() = runBlocking {
        server.enqueue(json("""{"service":"open-eos-control-bridge"}""")); server.enqueue(json("""{"id":"synthetic"}"""))
        val client = DesktopBridgeClient(server.url("/").toString()); client.initialize(); repeat(2) { server.takeRequest() }
        val item = CameraMediaItem("same-id", "SAME.JPG", "image")
        val bodies = listOf(
            "{id:'same-id',name:'SAME.JPG',rating:3}",
            """{"id":"same-id","name":"SAME.JPG","rating":03}""",
            """{"id":"same-id","name":"SAME.JPG","rating":0x3}""",
            """{"id":"same-id","name":"SAME.JPG","rating":+3}""",
            """{"id":"same-id","name":"SAME.JPG","rating":/*comment*/3}""",
            """{"id":"same-id","name":"SAME.JPG","rating":0,"rating":3}""",
            """{"id":"same-id","name":"SAME.JPG","rating":0,"rati\u006eg":3}""",
            """{"id":"same-id","name":"SAME.JPG","rating":3.0}""",
            """{"id":"same-id","name":"SAME.JPG","rating":3e0}""",
            """{"id":"same-id","name":"SAME.JPG","rating":3.0000000000000000001}""",
        )
        for (body in bodies) {
            val before = server.requestCount; server.enqueue(json(body))
            assertNotNull(body, runCatching { client.setMediaRating(item, 3) }.exceptionOrNull())
            assertEquals(before + 1, server.requestCount); assertEquals("PUT", server.takeRequest().method)
        }
        assertFalse(CameraFeature.MEDIA_RATING in client.observedFeatureSnapshot())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))))
        assertNotNull(runCatching { client.setMediaRating(item, 3) }.exceptionOrNull())
    }

    @Test fun canonicalInfoRejectsLenientSyntaxAndDuplicateEvidenceWithoutAnotherWrite() = runBlocking {
        server.enqueue(json("""{"ver110":[{"path":"/contents","get":true,"put":true}]}"""))
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false); client.initialize(); server.takeRequest()
        val item = CameraMediaItem("/ccapi/ver110/contents/card1/SAME.JPG", "SAME.JPG", "image")
        for (body in listOf("{rating:'3'}", "{\"rating\":/*comment*/\"3\"}",
            "{\"rating\":\"off\",\"rating\":\"3\"}", "{\"rating\":\"off\",\"rati\\u006eg\":\"3\"}")) {
            server.enqueue(json("{}")); server.enqueue(json(body)); val before = server.requestCount
            assertNotNull(body, runCatching { client.setMediaRating(item, 3) }.exceptionOrNull())
            assertEquals(before + 2, server.requestCount)
            assertEquals("PUT", server.takeRequest().method); assertEquals("GET", server.takeRequest().method)
        }
    }
}
