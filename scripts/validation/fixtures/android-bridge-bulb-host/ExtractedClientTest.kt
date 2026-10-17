package dev.openeos.control.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/** Exact production methods execute; JSON, network, and unrelated models are explicit adapters. */
class ExtractedClientTest {
    @Test(timeout = 5000) fun savedRetirementRejectsNewWritesAndTaggedStartButAdmitsStopDeleteAndRead() = runBlocking {
        val client = ExtractedOwnedClient()
        val access = client.prepareOwnedBulb()
        access.start(); access.stop(); access.retire()
        val before = client.requests.size
        for (path in listOf("liveview/start", "capture/still", "maintenance/control/liveview/start", "maintenance/sensor-cleaning")) {
            assertTrue(runCatching { client.probe("POST", path) }.exceptionOrNull() is CancellationException)
        }
        assertTrue(runCatching { client.probe("POST", "bulb/start/owned", taggedStart = true) }.exceptionOrNull() is CancellationException)
        assertEquals(before, client.requests.size)
        client.probe("GET", "status")
        client.probe("POST", "bulb/stop/owned")
        client.probe("DELETE", "")
        client.probe("POST", "maintenance/control/liveview/stop")
        client.probe("DELETE", "maintenance/control/events")
        client.probe("DELETE", "maintenance/control/session")
        assertEquals(before + 6, client.requests.size)
    }

    @Test(timeout = 5000) fun failedOwnedStopThenRawGetNeverPollutesLegacyRecoveryOrDuplicatesStop() = runBlocking {
        val client = ExtractedOwnedClient()
        client.startBulbExposure()
        client.respond = { _, _ -> Response(404, null) }
        assertTrue(runCatching { client.stopBulbExposure() }.isFailure)
        val observed = client.status()
        assertEquals(false, observed.bulbExposureActive)
        assertEquals(false, observed.shutterReleaseUnconfirmed)
        assertTrue(client.ownedDuty)
        assertFalse(client.unownedDuty)
        assertFalse(client.legacyUnconfirmed)
        client.respond = { request, _ -> client.receiptResponse(request) }
        client.stopBulbExposure()
        client.stopBulbExposure()
        assertEquals(3, client.requests.size)
        assertTrue(client.requests.all { it.url.encodedPath.endsWith("/owned") })
        assertEquals(1, client.legacyReads)
        assertFalse(client.duty)
        assertFalse(client.legacyUnconfirmed)
    }

    @Test(timeout = 5000) fun stopFailureRemainsPrimaryWhenDeleteAlsoFails() = runBlocking {
        val client = ExtractedOwnedClient()
        client.startBulbExposure()
        client.respond = { request, _ -> Response(if (request.method == "DELETE") 503 else 404, null) }
        val failure = runCatching { client.close() }.exceptionOrNull()
        assertTrue(failure is ShutterReleaseException)
        val primary = generateSequence(failure) { it.cause }.toList()
        assertTrue(primary.any { it.message == "Synthetic HTTP 404" })
        assertTrue(primary.flatMap { it.suppressed.toList() }.any { it.message == "Synthetic HTTP 503" })
        assertFalse(client.sessionPresent)
    }

    @Test(timeout = 5000) fun retiredAccessAfterTerminalStopStillSelectsBoundedDeleteWithoutAnotherStop() = runBlocking {
        val client = ExtractedOwnedClient()
        val access = client.prepareOwnedBulb()
        access.start(); access.stop()
        access.retire()
        client.respond = { request, _ -> check(request.method == "DELETE"); Response(204, null) }
        client.close()
        assertEquals(3, client.requests.size)
        assertEquals(1, client.requests.count { it.url.encodedPath.endsWith("/stop/owned") })
        assertEquals(TimeUnit.SECONDS.toNanos(15), client.calls.last().timeout().timeoutNanos())
        assertFalse(client.sessionPresent)
    }

    @Test(timeout = 25000) fun productionDefaultDeadlineBoundsNoCallbackStopAndIgnoredCancelStartBody() = runBlocking {
        val noCallback = ExtractedOwnedClient()
        noCallback.startBulbExposure()
        noCallback.respond = { _, _ -> throw HostNoCallback() }
        val noDeleteCallback = ExtractedOwnedClient()
        noDeleteCallback.startBulbExposure()
        noDeleteCallback.respond = { request, _ ->
            if (request.method == "DELETE") throw HostNoCallback()
            noDeleteCallback.receiptResponse(request)
        }
        val blockedBody = ExtractedOwnedClient()
        val bodyEntered = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        blockedBody.respond = { request, _ ->
            val bytes = blockedBody.receiptJson(request).toString().toByteArray()
            Response(200, ResponseBody(object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    bodyEntered.countDown(); check(releaseBody.await(22, TimeUnit.SECONDS))
                    return super.read(buffer, offset, length)
                }
            }))
        }
        val began = System.nanoTime()
        val stopping = async(Dispatchers.Default) { runCatching { noCallback.stopBulbExposure() } }
        val starting = async(Dispatchers.Default) { runCatching { blockedBody.startBulbExposure() } }
        val closing = async(Dispatchers.Default) { runCatching { noDeleteCallback.close() } }
        try {
            assertTrue(bodyEntered.await(1, TimeUnit.SECONDS))
            val outcomes = withTimeout(20_000) { listOf(stopping.await(), starting.await(), closing.await()) }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
            assertTrue("The exact production caller deadline must fire without an outer short timeout: $elapsed ms", elapsed in 14_000..19_500)
            assertTrue(outcomes.all { result -> generateSequence(result.exceptionOrNull()) { it.cause }.any { it is TimeoutCancellationException } })
            assertEquals(1L, releaseBody.count)
            assertEquals(1, noCallback.calls.last().cancelCount.get())
            assertEquals(1, blockedBody.calls.single().cancelCount.get())
            assertEquals(1, noDeleteCallback.calls.last().cancelCount.get())
            assertTrue(noCallback.ownedDuty)
            assertTrue(blockedBody.ownedDuty)
            assertFalse(noDeleteCallback.sessionPresent)
        } finally { releaseBody.countDown() }
        assertFalse(blockedBody.confirmed)
    }

    @Test(timeout = 5000) fun closeStopsExactOwnerAndDeletesBeforeLateStartResponse() = runBlocking {
        val client = ExtractedOwnedClient()
        val entered = CountDownLatch(1)
        val respond = CountDownLatch(1)
        client.respond = { request, _ ->
            if (request.url.encodedPath.endsWith("/start/owned")) {
                entered.countDown(); check(respond.await(2, TimeUnit.SECONDS))
            }
            if (request.method == "DELETE") Response(204, null) else client.receiptResponse(request)
        }
        val starting = async(Dispatchers.Default) { runCatching { client.startBulbExposure() } }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            withTimeout(1000) { client.close() }
            assertEquals(1L, respond.count)
            assertFalse(client.sessionPresent)
            assertEquals(listOf("POST /v1/session/synthetic-session/bulb/start/owned",
                "POST /v1/session/synthetic-session/bulb/stop/owned", "DELETE /v1/session/synthetic-session"),
                client.requests.map { "${it.method} ${it.url.encodedPath}" })
            assertEquals(0, client.legacyReads)
        } finally { respond.countDown(); starting.await() }
        assertFalse(client.sessionPresent)
    }

    @Test(timeout = 5000) fun ownedStopFailureStillAttemptsDeleteAndRetainsFailure() = runBlocking {
        val client = ExtractedOwnedClient()
        client.startBulbExposure()
        client.respond = { request, _ -> if (request.method == "DELETE") Response(204, null) else Response(404, null) }
        val outcome = runCatching { client.close() }
        assertTrue(outcome.isFailure)
        assertFalse(client.sessionPresent)
        assertEquals(1, client.requests.count { it.method == "DELETE" })
        assertEquals(0, client.requests.count { it.url.encodedPath.endsWith("/bulb/stop") })
        assertEquals(0, client.legacyReads)
    }

    @Test(timeout = 5000) fun owned404NeverFallsBackAndRetainsExactOwnerForExplicitStop() = runBlocking {
        val client = ExtractedOwnedClient()
        client.respond = { _, _ -> Response(404, ResponseBody(ByteArrayInputStream(ByteArray(0)))) }
        assertTrue(runCatching { client.startBulbExposure() }.isFailure)
        assertTrue(client.duty)
        assertTrue(runCatching { client.stopBulbExposure() }.isFailure)
        assertEquals(listOf("/v1/session/synthetic-session/bulb/start/owned", "/v1/session/synthetic-session/bulb/stop/owned"),
            client.requests.map { it.url.encodedPath })
        val bodies = client.requests.map { JSONObject(it.body!!) }
        assertTrue(bodies.all { it.keys() == setOf("ownerId") && it.opt("ownerId") is String })
        assertEquals(1, bodies.map { it.opt("ownerId") }.distinct().size)
        assertEquals(0, client.legacyReads)
        assertTrue(client.duty)
    }

    @Test(timeout = 5000) fun stopBeforeReservedStartDispatchUsesSameOwnerAndNeverPresses() = runBlocking {
        val client = ExtractedOwnedClient()
        val access = client.prepareOwnedBulb()
        access.stop()
        assertTrue(runCatching { access.start() }.isFailure)
        assertEquals(1, client.requests.size)
        assertTrue(client.requests.single().url.encodedPath.endsWith("/bulb/stop/owned"))
        assertFalse(access.releaseRequired())
    }

    @Test(timeout = 5000) fun matchingTerminalMakesRepeatedPublicStopWireFree() = runBlocking {
        val client = ExtractedOwnedClient()
        client.startBulbExposure()
        client.stopBulbExposure()
        client.stopBulbExposure()
        client.retryShutterRelease()
        assertEquals(2, client.requests.size)
        assertEquals(0, client.legacyReads)
        assertFalse(client.duty)
    }

    @Test(timeout = 5000) fun pendingStartAllowsStopBeforeLateCallbackBody() = runBlocking {
        val client = ExtractedOwnedClient()
        val startEntered = CountDownLatch(1)
        val finishStart = CountDownLatch(1)
        client.respond = { request, _ ->
            if (request.url.encodedPath.endsWith("/start/owned")) {
                startEntered.countDown(); check(finishStart.await(2, TimeUnit.SECONDS))
            }
            client.receiptResponse(request)
        }
        val access = client.prepareOwnedBulb()
        val start = async(Dispatchers.Default) { runCatching { access.start() } }
        try {
            assertTrue(startEntered.await(1, TimeUnit.SECONDS))
            withTimeout(1000) { access.stop() }
            assertEquals(1L, finishStart.count)
            assertFalse(access.releaseRequired())
        } finally { finishStart.countDown(); start.await() }
        assertFalse(client.duty)
    }

    @Test(timeout = 5000) fun sameTextSessionReplacementRejectsSavedAccessBeforeAnyDispatch() = runBlocking {
        val client = ExtractedOwnedClient()
        val old = client.prepareOwnedBulb()
        client.replaceSession()
        assertTrue(runCatching { old.start() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { old.stop() }.exceptionOrNull() is CancellationException)
        assertTrue(client.requests.isEmpty())
        assertFalse(client.duty)
        val fresh = client.prepareOwnedBulb()
        fresh.start(); fresh.stop()
        assertEquals(2, client.requests.size)
    }

    @Test(timeout = 5000) fun lateOldSessionResponseCannotMutateReplacementWithSameTextId() = runBlocking {
        val client = ExtractedOwnedClient()
        val entered = CountDownLatch(1)
        val response = CountDownLatch(1)
        client.respond = { request, _ ->
            entered.countDown(); check(response.await(2, TimeUnit.SECONDS)); client.receiptResponse(request)
        }
        val start = async(Dispatchers.Default) { runCatching { client.startBulbExposure() } }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            client.replaceSession()
        } finally { response.countDown() }
        assertTrue(start.await().isFailure)
        assertFalse(client.duty)
        assertFalse(client.confirmed)
    }

    @Test(timeout = 5000) fun ownedStartRejectsDifferentReceiptOwnerWithoutReadingStatus() = runBlocking {
        val client = ExtractedOwnedClient()
        client.respond = { _, _ -> client.jsonResponse(JSONObject().put("ownerId", "different-owner")
            .put("outcome", "started").put("status", statusJson(true))) }
        assertTrue(runCatching { client.startBulbExposure() }.isFailure)
        assertTrue(client.duty)
        assertEquals(1, client.requests.size)
        assertEquals(0, client.legacyReads)
    }

    @Test(timeout = 5000) fun ownedStopNeverPublishesRecordingDisplayAndPreservesNewWarning() = runBlocking {
        val client = ExtractedOwnedClient()
        client.startBulbExposure()
        client.observeWarning()
        client.respond = { request, _ ->
            val owner = JSONObject(request.body!!).opt("ownerId")
            client.jsonResponse(JSONObject().put("ownerId", owner).put("outcome", "released"))
        }
        val status = client.stopBulbExposure()
        assertEquals(true, status.shutterReleaseUnconfirmed)
        assertFalse(client.ownedDuty)
        assertTrue(client.unownedDuty)
        assertEquals(0, client.recordingDisplayPublishes)
    }

    @Test(timeout = 5000) fun blockedBodyIgnoringCancelCannotHoldCallerOrPublishLateStart() = runBlocking {
        val client = ExtractedOwnedClient()
        val unblock = CountDownLatch(1)
        val bodyEntered = CountDownLatch(1)
        client.respond = { request, _ ->
            val bytes = client.receiptJson(request).toString().toByteArray()
            Response(200, ResponseBody(object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    bodyEntered.countDown(); check(unblock.await(2, TimeUnit.SECONDS))
                    return super.read(buffer, offset, length)
                }
            }))
        }
        val access = client.prepareOwnedBulb()
        val start = async(Dispatchers.Default) { runCatching { withTimeout(120) { access.start() } } }
        try {
            assertTrue(bodyEntered.await(1, TimeUnit.SECONDS))
            assertTrue(withTimeout(1000) { start.await() }.exceptionOrNull() is TimeoutCancellationException)
            assertEquals(1L, unblock.count)
            assertTrue(access.releaseRequired())
            assertEquals(1, client.calls.single().cancelCount.get())
            assertEquals(TimeUnit.SECONDS.toNanos(15), client.calls.single().timeout().timeoutNanos())
        } finally { unblock.countDown() }
        assertFalse(client.confirmed)
    }

    @Test(timeout = 5000) fun oversizedOwnedResponseFailsBeforeReceiptOrStatePublication() = runBlocking {
        val client = ExtractedOwnedClient()
        client.respond = { _, _ -> Response(200, ResponseBody(ByteArrayInputStream(ByteArray(65_537)))) }
        assertTrue(runCatching { client.startBulbExposure() }.isFailure)
        assertTrue(client.duty)
        assertFalse(client.confirmed)
        assertEquals(1, client.requests.size)
    }
}

private fun statusJson(active: Boolean) = JSONObject().put("bulbExposureActive", active).put("shutterReleaseUnconfirmed", false)
internal enum class CameraFeature { BULB_EXPOSURE }
internal class HostFocusSession {
    val hasReleaseResponsibility = false; val revision = 0L; val stopUnconfirmed = false
    suspend fun retryStop() {}; suspend fun close() {}
}

/** Typed registry, deliberately not a replacement claim for the org.json parser. */
internal class JSONObject {
    private val fields: MutableMap<String, Any?>
    constructor() { fields = linkedMapOf() }
    constructor(serialized: String) { fields = requireNotNull(values[serialized]) { "Synthetic invalid JSON" }.toMutableMap() }
    fun put(key: String, value: Any?): JSONObject = apply { fields[key] = value }
    fun opt(key: String): Any? = fields[key]
    fun has(key: String) = fields.containsKey(key)
    fun optJSONObject(key: String) = fields[key] as? JSONObject
    fun keys(): Set<String> = fields.keys.toSet()
    override fun toString(): String = "host-json-${sequence.incrementAndGet()}".also { values[it] = fields.toMap() }
    companion object {
        private val sequence = AtomicInteger()
        private val values = ConcurrentHashMap<String, Map<String, Any?>>()
    }
}
internal data class HttpUrl(val encodedPath: String)
internal data class Request(val url: HttpUrl, val body: String? = null, val method: String = "POST", private val tags: Map<Class<*>, Any> = emptyMap()) {
    @Suppress("UNCHECKED_CAST") fun <T> tag(type: Class<T>): T? = tags[type] as? T
    class Builder {
        private var url = HttpUrl(""); private var body: String? = null
        private var method = "POST"
        private val tags = mutableMapOf<Class<*>, Any>()
        fun url(value: HttpUrl) = apply { url = value }
        fun <T : Any> tag(type: Class<T>, value: T?) = apply { if (value != null) tags[type] = value }
        fun post(value: String) = apply { body = value }
        fun delete() = apply { method = "DELETE" }
        fun get() = apply { method = "GET" }
        fun build() = Request(url, body, method, tags.toMap())
    }
}
internal fun String.toRequestBody(mediaType: String) = this
internal class HostTimeout {
    private var nanos = 0L
    fun timeoutNanos() = nanos
    fun timeout(value: Long, unit: TimeUnit) = apply { nanos = unit.toNanos(value) }
}
internal interface Call { fun timeout(): HostTimeout; fun enqueue(callback: Callback); fun cancel() }
internal interface Callback { fun onFailure(call: Call, error: IOException); fun onResponse(call: Call, response: Response) }
internal class HostNoCallback : RuntimeException()
internal class ResponseBody(private val input: InputStream) { fun byteStream() = input }
internal class Response(val code: Int, val body: ResponseBody?) : Closeable { override fun close() { body?.byteStream()?.close() } }
internal class HostCall(private val request: Request, private val respond: (Request, HostCall) -> Response) : Call {
    private val timer = HostTimeout()
    val cancelCount = AtomicInteger()
    override fun timeout() = timer
    override fun cancel() { cancelCount.incrementAndGet() } // Deliberately ignores socket/body cancellation.
    override fun enqueue(callback: Callback) {
        Thread {
            try { callback.onResponse(this, respond(request, this)) }
            catch (_: HostNoCallback) { /* Explicit no-callback network fixture. */ }
            catch (failure: IOException) { callback.onFailure(this, failure) }
        }.apply { isDaemon = true; start() }
    }
}

internal class ExtractedOwnedClient {
    private var shutterSession: BridgeShutterReleaseSession? = BridgeShutterReleaseSession("synthetic-session")
    private var heldAutofocusSession: HostFocusSession? = null
    private var maintenanceSessionOwned = false
    private var closingMediaSession: BridgeShutterReleaseSession? = null
    private class HostListing(val call: Call)
    private class LiveViewStopOwner(val session: BridgeShutterReleaseSession, val url: HttpUrl, var confirmedStart: Boolean = false)
    private var liveViewStopOwner: LiveViewStopOwner? = null
    private var liveViewRevision = 0L
    private val liveViewStateLock = Any()
    private val activeMediaListing = AtomicReference<HostListing?>(null)
    private val activeEventCall = AtomicReference<Call?>(null)
    private var sessionId: String? = "synthetic-session"
    private var sessionCameraModel: String? = null
    private var sessionEngine: String? = null
    private var eventPollingSupported = false
    private var shutterAutofocusSupported = false
    private var heldAutofocusSupported = false
    private var focusInfoSupported = false
    private var liveViewMagnifications = emptyList<String>()
    private val observedFeatures = mutableSetOf<CameraFeature>()
    private val jsonMediaType = "application/json"
    private object BridgeShutterStart
    private object BridgeFocusStart
    val requests = CopyOnWriteArrayList<Request>()
    val calls = CopyOnWriteArrayList<HostCall>()
    var legacyReads = 0
    var recordingDisplayPublishes = 0
    var respond: (Request, HostCall) -> Response = { request, _ -> receiptResponse(request) }
    val sessionPresent get() = shutterSession != null
    val duty get() = shutterSession?.hasReleaseResponsibility == true
    val ownedDuty get() = shutterSession?.bulb?.hasReleaseResponsibility == true
    val unownedDuty get() = shutterSession?.hasUnownedReleaseResponsibility == true
    val legacyUnconfirmed get() = shutterSession?.unconfirmed == true
    val confirmed get() = shutterSession?.bulb?.hasConfirmedStart == true
    fun replaceSession() { shutterSession?.close(); shutterSession = BridgeShutterReleaseSession("synthetic-session") }
    fun observeWarning() { requireShutterSession().let { it.observe(CameraStatus(bulbExposureActive = false, shutterReleaseUnconfirmed = true), it.revision) } }
    fun receiptJson(request: Request): JSONObject {
        val owner = JSONObject(requireNotNull(request.body)).opt("ownerId")
        val start = request.url.encodedPath.endsWith("/start/owned")
        return JSONObject().put("ownerId", owner).put("outcome", if (start) "started" else "released").put("status", statusJson(start))
    }
    fun receiptResponse(request: Request) = jsonResponse(receiptJson(request))
    fun jsonResponse(body: JSONObject) = Response(200, ResponseBody(ByteArrayInputStream(body.toString().toByteArray())))
    private fun requireShutterSession() = requireNotNull(shutterSession)
    private suspend fun closeMaintenanceSession() = false
    private suspend fun stopEventPolling() {}
    private suspend fun requestOk(request: Request, recoveryCleanup: Boolean = false) { error("Unowned close adapter is outside these owned tests") }
    private fun endpoint(vararg parts: String) = HttpUrl("/" + parts.joinToString("/"))
    private fun newBridgeCall(request: Request): Call {
        requireBridgeRequestAllowed(request)
        requests += request
        return HostCall(request, respond).also { calls += it }
    }
    fun probe(method: String, tail: String, taggedStart: Boolean = false) {
        val request = Request.Builder().url(HttpUrl("/v1/session/synthetic-session" + if (tail.isEmpty()) "" else "/$tail"))
            .tag(BridgeShutterStart::class.java, if (taggedStart) BridgeShutterStart else null)
        when (method) { "GET" -> request.get(); "DELETE" -> request.delete(); else -> request.post(JSONObject().toString()) }
        newBridgeCall(request.build())
    }
    private fun bridgeError(code: Int, body: String, path: String, session: BridgeShutterReleaseSession?): IllegalStateException =
        IllegalStateException("Synthetic HTTP $code")
    private suspend fun postJson(path: HttpUrl, payload: JSONObject): JSONObject {
        requests += Request(path, payload.toString())
        return statusJson(false)
    }
    private suspend fun readBridgeStatus(session: BridgeShutterReleaseSession, recoveryRead: Boolean = false): CameraStatus {
        legacyReads++; return CameraStatus(bulbExposureActive = false, shutterReleaseUnconfirmed = false)
    }
    private fun parseStatus(body: JSONObject, publishRecordingDisplay: Boolean = true): CameraStatus {
        if (publishRecordingDisplay) recordingDisplayPublishes++
        return CameraStatus(bulbExposureActive = body.opt("bulbExposureActive") as? Boolean,
            shutterReleaseUnconfirmed = body.opt("shutterReleaseUnconfirmed") as? Boolean)
    }

    // HOST_PRODUCTION_OWNED_METHODS
}

// HOST_PRODUCTION_RETIREMENT_ACK
