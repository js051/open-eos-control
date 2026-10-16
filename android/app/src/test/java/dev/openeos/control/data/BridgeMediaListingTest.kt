package dev.openeos.control.data

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protocol fixtures only; this is not physical-camera or strict inventory evidence. */
class BridgeMediaListingTest {
    @Test
    fun fragmentedUtf8ChunkedSnapshotsKeepAllOpaqueItemsWithoutFullCardTruncation() {
        val ids = listOf(" 影像%2F+ ", "caf\u00e9", "cafe\u0301") + (0..1000).map { "image-$it" }
        val decoder = BridgeMediaListingDecoder("session", null)
        val published = ArrayList<List<CameraMediaItem>>()
        feed(decoder, record("start", 0).put("limit", JSONObject.NULL), published)
        var sequence = 0
        for (offset in ids.indices step 128) {
            val end = minOf(offset + 128, ids.size)
            feed(decoder, record("snapshot", ++sequence).put("revision", 1).put("offset", offset)
                .put("total", ids.size).put("items", JSONArray(ids.subList(offset, end).map(::item)))
                .put("last", end == ids.size), published)
            assertEquals(if (end == ids.size) 1 else 0, published.size)
        }
        feed(decoder, record("complete", sequence + 1).put("revision", 1).put("count", ids.size)
            .put("scopeComplete", true), published)
        assertEquals(ids, decoder.finish().map { it.id })
        assertEquals(1, published.size)
    }

    @Test
    fun malformedRecordsMissingTerminalsAndResourceLimitsNeverComplete() {
        val good = frames()
        val cases = ArrayList<List<ByteArray>>()
        cases += good.dropLast(1).map(::line)
        cases += good.map(::line) + listOf("\n".toByteArray())
        cases += good.mapIndexed { index, frame -> if (index == 2) line(frame).dropLast(1).toByteArray() else line(frame) }
        val changes = listOf(
            Triple(0, "limit", 1), Triple(0, "requestId", ""), Triple(1, "requestId", "other"),
            Triple(1, "sessionId", "other"), Triple(1, "scope", "recent"), Triple(1, "seq", true),
            Triple(1, "seq", 3), Triple(1, "seq", 9_007_199_254_740_992L), Triple(1, "revision", 2),
            Triple(1, "offset", 1), Triple(1, "total", 100_001), Triple(1, "last", false),
            Triple(1, "items", JSONArray(listOf(item("same"), item("same")))),
            Triple(2, "count", 2), Triple(2, "revision", 2), Triple(2, "scopeComplete", 1),
        )
        for ((index, key, value) in changes) {
            val broken = good.map { JSONObject(it.toString()) }; broken[index].put(key, value)
            cases += broken.map(::line)
        }
        cases += listOf(byteArrayOf(0xff.toByte(), 10))
        cases += listOf((" ".repeat(65_536) + "\n").toByteArray())
        cases += listOf("{\"type\":\"start\",\"type\":\"start\"}\n".toByteArray())
        cases += listOf("{'type':'start'}\n".toByteArray())
        cases += listOf("{\"type\":\"\\uD800\"}\n".toByteArray())
        for (bytes in cases) {
            val decoder = BridgeMediaListingDecoder("session", null)
            assertNotNull(runCatching {
                for (chunk in bytes) for (byte in chunk) decoder.consume(byte, ::parse)
                decoder.finish()
            }.exceptionOrNull())
        }
    }

    @Test
    fun errorDuringUnfinishedRevisionKeepsLastCompleteSnapshot() {
        val decoder = BridgeMediaListingDecoder("session", null)
        val published = ArrayList<List<CameraMediaItem>>()
        frames().take(2).forEach { feed(decoder, it, published) }
        feed(decoder, record("snapshot", 2).put("revision", 2).put("offset", 0).put("total", 2)
            .put("items", JSONArray(listOf(item("new")))).put("last", false), published)
        feed(decoder, record("error", 3).put("code", "RESOURCE_LIMIT").put("message", "Synthetic limit."), published)
        assertEquals(listOf(listOf("image")), published.map { it.map(CameraMediaItem::id) })
        assertNotNull(runCatching { decoder.finish() }.exceptionOrNull())
        assertNotNull(runCatching { decoder.consume(10, ::parse) }.exceptionOrNull())
    }

    @Test(timeout = 15_000)
    fun legacyLoopbackKeepsAuthenticationAndOneMediaRequest() = runBlocking<Unit> {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            for (body in listOf("{\"service\":\"open-eos-control-bridge\"}",
                "{\"id\":\"session\",\"engine\":\"ccapi\"}",
                "{\"items\":[{\"id\":\"image\",\"name\":\"IMG.JPG\"}]}")) {
                server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            }
            val client = DesktopBridgeClient(server.url("/").toString(), token = "synthetic-token")
            client.initialize()
            val progress = ArrayList<List<CameraMediaItem>>()
            val items = client.listMedia(61) { progress += it }
            assertEquals(listOf(items), progress)
            assertEquals(3, server.requestCount)
            server.takeRequest(); server.takeRequest()
            val request = server.takeRequest()
            assertEquals("/v1/session/session/media?limit=61", request.path)
            assertEquals("Bearer synthetic-token", request.getHeader("Authorization"))
            assertEquals(BridgeMediaListingDecoder.MEDIA_TYPE, request.getHeader("Accept"))
        }
    }

    @Test(timeout = 15_000)
    fun productionBridgePublishesBeforeTerminalAndMakesOneGet() = runBlocking<Unit> {
        Peer().use { peer ->
            val client = peer.client()
            client.initialize()
            val first = CompletableDeferred<List<CameraMediaItem>>()
            val listing = async(Dispatchers.IO) { client.listMedia { first.complete(it) } }
            assertEquals(listOf("image"), withTimeout(3_000) { first.await() }.map { it.id })
            assertFalse(listing.isCompleted)
            peer.gates.single().release()
            assertEquals(listOf("image"), listing.await().map { it.id })
            assertEquals(1, peer.mediaRequests.size)
            assertEquals(BridgeMediaListingDecoder.MEDIA_TYPE, peer.mediaRequests.single().header("Accept"))
            assertNull(peer.mediaRequests.single().url.query)
        }
    }

    @Test(timeout = 20_000)
    fun cancelAndReusedSessionIdRejectLateSnapshotAndTerminal() = runBlocking<Unit> {
        for (cancel in listOf(true, false)) Peer(staleTail = true).use { peer ->
            val client = peer.client()
            client.initialize()
            val progress = CopyOnWriteArrayList<List<CameraMediaItem>>()
            val first = CompletableDeferred<Unit>()
            val listing = async(Dispatchers.IO) { client.listMedia { progress += it; first.complete(Unit) } }
            withTimeout(3_000) { first.await() }
            if (cancel) listing.cancel() else { client.close(); client.initialize() }
            val oldCall = peer.calls.single()
            peer.gates.single().release()
            assertTrue(runCatching { listing.await() }.exceptionOrNull() is CancellationException)
            assertTrue(oldCall.isCanceled())
            assertEquals(listOf(listOf("image")), progress.map { it.map(CameraMediaItem::id) })
            if (!cancel) {
                val next = async(Dispatchers.IO) { client.listMedia() }
                withTimeout(3_000) { peer.secondOpened.await() }
                peer.gates.last().release()
                assertEquals(listOf("image"), next.await().map { it.id })
            }
        }
    }

    @Test(timeout = 15_000)
    fun legacyAndTruncatedResponsesNeverRetry() = runBlocking<Unit> {
        for (legacy in listOf(true, false)) Peer(legacy = legacy, truncated = !legacy).use { peer ->
            val client = peer.client()
            client.initialize()
            val progress = CopyOnWriteArrayList<List<CameraMediaItem>>()
            val first = CompletableDeferred<Unit>()
            val listing = async(Dispatchers.IO) { runCatching { client.listMedia { progress += it; first.complete(Unit) } } }
            withTimeout(3_000) { first.await() }
            peer.gates.forEach { it.release() }
            assertEquals(legacy, listing.await().isSuccess)
            assertEquals(1, peer.mediaRequests.size)
            assertEquals(listOf(listOf("image")), progress.map { it.map(CameraMediaItem::id) })
        }
    }

    @Test(timeout = 15_000)
    fun legacyRejectsInvalidRowsDuplicateIdentitiesAndExcessLimitWithoutPublishing() = runBlocking<Unit> {
        val valid = """{"id":"image","name":"IMG.JPG","kind":"image"}"""
        val cases = listOf(
            Triple("non-object row", """{"items":[$valid,null]}""", null),
            Triple("invalid row", """{"items":[$valid,{"id":"broken","kind":"image"}]}""", null),
            Triple("duplicate identity", """{"items":[$valid,$valid]}""", null),
            Triple("escaped duplicate identity", """{"items":[$valid,{"id":"\u0069mage","name":"OTHER.JPG","kind":"image"}]}""", null),
            Triple("requested limit", """{"items":[$valid,{"id":"other","name":"OTHER.JPG","kind":"image"}]}""", 1),
        )
        for ((name, body, limit) in cases) Peer(legacyBody = body).use { peer ->
            val client = peer.client()
            client.initialize()
            val progress = ArrayList<List<CameraMediaItem>>()
            val outcome = runCatching { client.listMedia(limit, progress::add) }
            assertTrue("Accepted $name", outcome.isFailure)
            assertTrue("A failed legacy response cannot replace a previous partial: $name", progress.isEmpty())
            assertEquals("No fallback traversal: $name", 1, peer.mediaRequests.size)
        }
    }

    @Test(timeout = 30_000)
    fun legacyFullCardItemBoundaryRejectsOverflowWithoutTruncation() = runBlocking<Unit> {
        for (count in listOf(100_000, 100_001)) {
            val body = JSONObject().put("items", JSONArray((0 until count).map { item("image-$it") })).toString()
            Peer(legacyBody = body).use { peer ->
                val client = peer.client()
                client.initialize()
                val progress = ArrayList<List<CameraMediaItem>>()
                val outcome = runCatching { client.listMedia(onProgress = progress::add) }
                if (count == 100_000) {
                    assertEquals(count, outcome.getOrThrow().size)
                    assertEquals("image-99999", outcome.getOrThrow().last().id)
                } else assertTrue("Accepted legacy item overflow", outcome.isFailure)
                assertEquals(if (count == 100_000) listOf(count) else emptyList<Int>(), progress.map { it.size })
                assertEquals(1, peer.mediaRequests.size)
                assertNull(peer.mediaRequests.single().url.query)
            }
        }
    }

    @Test(timeout = 15_000)
    fun legacyEmptyAndExactLimitPreserveOpaqueUnicodeIdentities() = runBlocking<Unit> {
        val ids = listOf(" 影像%2F+ ", "caf\u00e9", "cafe\u0301")
        for (expected in listOf(emptyList(), ids)) {
            val body = JSONObject().put("items", JSONArray(expected.map(::item))).toString()
            Peer(legacyBody = body).use { peer ->
                val client = peer.client()
                client.initialize()
                val progress = ArrayList<List<CameraMediaItem>>()
                val items = client.listMedia(3, progress::add)
                assertEquals(expected, items.map { it.id })
                assertEquals(listOf(items), progress)
                assertEquals(1, peer.mediaRequests.size)
                assertEquals("3", peer.mediaRequests.single().url.queryParameter("limit"))
            }
        }
    }

    private fun feed(decoder: BridgeMediaListingDecoder, record: JSONObject, published: MutableList<List<CameraMediaItem>>) {
        for (byte in line(record)) decoder.consume(byte, ::parse)?.let(published::add)
    }
    private fun parse(value: JSONObject): CameraMediaItem? = CameraMediaItem(value.getString("id"), value.getString("name"), "image")

    private class Peer(val legacy: Boolean = false, val truncated: Boolean = false, val staleTail: Boolean = false,
                       val legacyBody: String? = null) : Closeable {
        val mediaRequests = CopyOnWriteArrayList<Request>()
        val calls = CopyOnWriteArrayList<Call>()
        val gates = CopyOnWriteArrayList<GateSource>()
        val secondOpened = CompletableDeferred<Unit>()
        private val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when {
                request.url.encodedPath == "/health" -> "{\"service\":\"open-eos-control-bridge\"}".toResponseBody(JSON)
                request.url.encodedPath.endsWith("/media") -> {
                    mediaRequests += request
                    calls += chain.call()
                    if (legacyBody != null) legacyBody.toResponseBody(JSON)
                    else if (legacy) "{\"items\":[{\"id\":\"image\",\"name\":\"IMG.JPG\"}]}".toResponseBody(JSON)
                    else {
                        val prefix = frames().take(2).flatMap { line(it).toList() }.toByteArray()
                        val tail = if (truncated) byteArrayOf() else if (staleTail && gates.isEmpty()) {
                            line(record("snapshot", 2).put("revision", 2).put("offset", 0).put("total", 1)
                                .put("items", JSONArray(listOf(item("stale")))).put("last", true)) +
                                line(record("complete", 3).put("revision", 2).put("count", 1).put("scopeComplete", true))
                        } else line(frames()[2])
                        val gate = GateSource(prefix, tail)
                        gates += gate
                        if (gates.size == 2) secondOpened.complete(Unit)
                        object : ResponseBody() {
                            private val buffered = gate.buffer()
                            override fun contentType() = BridgeMediaListingDecoder.MEDIA_TYPE.toMediaType()
                            override fun contentLength() = -1L
                            override fun source() = buffered
                        }
                    }
                }
                else -> "{\"id\":\"session\",\"engine\":\"ccapi\"}".toResponseBody(JSON)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", body.contentType().toString()).body(body).build()
        }.build()
        fun client() = DesktopBridgeClient("http://127.0.0.1:18181", httpClient = http, token = "synthetic-token")
        override fun close() { gates.forEach { it.release() }; http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }

    /** Deliberately ignores HTTP cancellation until released, to test stale-owner rejection. */
    private class GateSource(prefix: ByteArray, tail: ByteArray) : Source {
        private val first = Buffer().write(prefix)
        private val last = Buffer().write(tail)
        private val gate = CountDownLatch(1)
        fun release() = gate.countDown()
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (first.size > 0) return first.read(sink, minOf(byteCount, 1)) // Split UTF-8 and JSON.
            if (!gate.await(5, TimeUnit.SECONDS)) throw IOException("Synthetic listing gate timed out.")
            return last.read(sink, byteCount)
        }
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private fun item(id: String) = JSONObject().put("id", id).put("name", "影像📷.JPG").put("kind", "image")
        private fun record(type: String, seq: Int) = JSONObject().put("type", type).put("seq", seq)
            .put("requestId", "request").put("sessionId", "session").put("scope", "full")
        private fun frames() = listOf(record("start", 0).put("limit", JSONObject.NULL),
            record("snapshot", 1).put("revision", 1).put("offset", 0).put("total", 1)
                .put("items", JSONArray(listOf(item("image")))).put("last", true),
            record("complete", 2).put("revision", 1).put("count", 1).put("scopeComplete", true))
        private fun line(value: JSONObject) = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
    }
}
