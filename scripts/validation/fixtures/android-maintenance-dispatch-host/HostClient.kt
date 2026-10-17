package dev.openeos.control.data

import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/** Adapters for host-only APIs. Every method below the marker is copied unchanged. */
internal class ExtractedMaintenanceClient(val initialLiveView: Boolean = false) {
    @Volatile private var shutterSession: BridgeShutterReleaseSession? = BridgeShutterReleaseSession("synthetic-session")
    private var heldAutofocusSession: HostFocusSession? = null
    private var sessionId: String? = "synthetic-session"
    private var sessionEngine: String? = "synthetic-engine"
    private var maintenanceSessionOwned = true
    private var eventPollingSupported = false
    private val activeEventCall = AtomicReference<Call?>(null)
    private class MediaListingOwner(val call: Call)
    private val activeMediaListing = AtomicReference<MediaListingOwner?>(null)
    private class MaintenanceBridgeOwnerContext(val owner: BridgeShutterReleaseSession) :
        kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<MaintenanceBridgeOwnerContext>
    }
    private class LiveViewStopOwner(val session: BridgeShutterReleaseSession, val stopUrl: HttpUrl,
        var confirmedStart: Boolean = false, val maintenanceOwned: Boolean = false)
    @Volatile private var liveViewStopOwner: LiveViewStopOwner? = if (initialLiveView)
        LiveViewStopOwner(requireNotNull(shutterSession), endpoint("v1", "session", "synthetic-session", "maintenance", "control", "liveview", "stop"), true, true) else null
    @Volatile private var liveViewRevision = 0L
    private val liveViewStateLock = Any()
    val liveViewStopRequired: Boolean get() = liveViewStopOwner?.let { it.session === shutterSession && !it.session.closed } == true
    val maintenanceStopOwned: Boolean get() = liveViewStopRequired && liveViewStopOwner?.maintenanceOwned == true
    private val jsonMediaType = "application/json"
    private val observedFeatures = mutableSetOf<CameraFeature>()
    private object BridgeShutterStart
    private val RECOVERY_STATUS_CALL_TIMEOUT_SECONDS = 15L
    var callbackCopies = 1
    val requests = CopyOnWriteArrayList<Request>()
    val calls = CopyOnWriteArrayList<HostCall>()
    var respond: (Request, HostCall) -> Response = { request, _ -> validResponse(request) }
    var physicalFailure: Exception? = null
    val sessionPresent get() = shutterSession != null
    val owner get() = shutterSession
    val liveViewOwnerIdentity: Any? get() = liveViewStopOwner
    val sessionIdentity: Any? get() = shutterSession
    var accepts = AtomicInteger()
    fun replaceSession(): BridgeShutterReleaseSession {
        val fresh = BridgeShutterReleaseSession("synthetic-session")
        shutterSession = fresh; sessionId = fresh.id
        liveViewStopOwner = LiveViewStopOwner(fresh, endpoint("v1", "session", fresh.id, "maintenance", "control", "liveview", "stop"), true, true)
        liveViewRevision += 1
        return fresh
    }
    fun requirePhysicalRelease(failure: Exception) { requireNotNull(shutterSession).markUnconfirmed(); physicalFailure = failure }
    fun setFocusFailure(failure: Exception) { heldAutofocusSession = HostFocusSession(failure) }
    fun setActiveEvent(call: Call) { activeEventCall.set(call) }
    fun hasActiveEvent(call: Call) = activeEventCall.get() === call
    suspend fun directCleanup(tail: String = "session", captured: BridgeShutterReleaseSession = requireShutterSession(), onAccept: Boolean = false) {
        val request = Request.Builder().url(endpoint("v1", "session", captured.id, "maintenance", "control", tail)).delete().build()
        maintenanceJson(request, captured, expected = if(onAccept) 200 else 204, empty = !onAccept,
            accept = if(onAccept) ({ accepts.incrementAndGet(); Unit }) else null)
    }
    suspend fun routeProbe(method: String, path: String) {
        val builder = Request.Builder().url(HttpUrl(path))
        when (method) { "GET" -> builder.get(); "DELETE" -> builder.delete(); else -> builder.post(JSONObject().toString()) }
        maintenanceJson(builder.build(), requireShutterSession()) { accepts.incrementAndGet() }
    }
    fun validResponse(request: Request): Response = if (request.method == "DELETE") Response(204, null)
        else jsonResponse(JSONObject().put("active", false).put("liveViewStopUnconfirmed", false))
    fun jsonResponse(body: JSONObject) = Response(200, ResponseBody(ByteArrayInputStream(body.toString().toByteArray())))
    private fun requireShutterSession() = requireNotNull(shutterSession)
    private fun endpoint(vararg parts: String) = HttpUrl("/" + parts.joinToString("/"))
    private fun sessionEndpoint(vararg parts: String) = endpoint("v1", "session", requireNotNull(sessionId), *parts)
    private fun newBridgeCall(request: Request, captureConfiguration: Boolean = false): Call {
        requests += request
        return HostCall(request, respond, callbackCopies).also { calls += it }
    }
    private suspend fun stopBulbExposure(owner: BridgeShutterReleaseSession) { physicalFailure?.let { throw it } }
    private suspend fun requestOk(request: Request) { error("Legacy event transport is outside strict maintenance tests") }
    private fun parseStatus(body: JSONObject) = CameraStatus()
    private fun observeBridgeStatus(session: BridgeShutterReleaseSession, status: CameraStatus, revision: Long) = session.observe(status, revision)
    private fun bridgeError(code: Int, body: String, path: String, session: BridgeShutterReleaseSession?, revision: Long? = null, cameraDiscovery: Boolean = false): Exception = DesktopBridgeException(code)
    // HOST_PRODUCTION_METHODS
}

internal class JSONObject {
    private val fields: MutableMap<String, Any?>
    constructor() { fields = linkedMapOf() }
    constructor(text: String) { fields = (if (text == "{}") emptyMap() else requireNotNull(registry[text]) { "Synthetic invalid JSON" }).toMutableMap() }
    fun put(key: String, value: Any?) = apply { fields[key] = value }
    fun opt(key: String) = fields[key]
    fun has(key: String) = fields.containsKey(key)
    fun isNull(key: String) = fields[key] == null
    fun getString(key: String) = fields[key] as String
    fun optJSONObject(key: String) = fields[key] as? JSONObject
    fun keys() = fields.keys.toSet()
    override fun toString(): String = "{\"hostToken\":${sequence.incrementAndGet()}}".also { registry[it] = fields.toMap() }
    companion object {
        val NULL = Any()
        private val sequence = AtomicInteger()
        private val registry = ConcurrentHashMap<String, Map<String, Any?>>()
    }
}
internal data class HttpUrl(val encodedPath: String) {
    fun newBuilder() = Builder(encodedPath)
    class Builder(private var path: String) { fun addPathSegment(part: String) = apply { path += "/$part" }; fun build() = HttpUrl(path) }
}
internal data class Request(val url: HttpUrl, val body: String? = null, val method: String = "POST") {
    class Builder {
        private var url = HttpUrl(""); private var body: String? = null; private var method = "POST"
        fun url(value: HttpUrl) = apply { url = value }
        fun <T: Any> tag(type: Class<T>, value: T?) = this
        fun header(name: String, value: String) = this
        fun post(value: String) = apply { body = value; method = "POST" }
        fun delete() = apply { method = "DELETE" }
        fun get() = apply { method = "GET" }
        fun build() = Request(url, body, method)
    }
}
internal fun String.toRequestBody(mediaType: String) = this
internal class HostTimeout {
    private var nanos = 0L
    fun timeoutNanos() = nanos
    fun timeout(value: Long, unit: TimeUnit) = apply { nanos = unit.toNanos(value) }
}
internal interface Call { fun timeout(): HostTimeout; fun execute(): Response; fun enqueue(callback: Callback); fun cancel() }
internal interface Callback { fun onFailure(call: Call, error: IOException); fun onResponse(call: Call, response: Response) }
internal class HostNoCallback : RuntimeException()
internal class ResponseBody(private val input: InputStream, private val length: Long = -1) {
    fun byteStream() = input
    fun contentLength() = length
    fun string() = input.readBytes().toString(Charsets.UTF_8)
    fun readBoundedUtf8(limit: Int) = readStrictMediaJsonText(input, length, limit)
}
internal class Response(val code: Int, val body: ResponseBody?, private val headerMap: Map<String, List<String>> = emptyMap()) : Closeable {
    val isSuccessful get() = code in 200..299
    val closes = AtomicInteger()
    fun headers(name: String) = headerMap[name].orEmpty()
    override fun close() { closes.incrementAndGet(); body?.byteStream()?.close() }
}
internal class HostCall(val request: Request, private val respond: (Request, HostCall) -> Response, private val copies: Int = 1) : Call {
    private val timer = HostTimeout()
    val cancelCount = AtomicInteger()
    val executeCount = AtomicInteger()
    val enqueueCount = AtomicInteger()
    val callbacksCompleted = AtomicInteger()
    override fun timeout() = timer
    override fun cancel() { cancelCount.incrementAndGet() } // Ignores physical transport cancellation on purpose.
    override fun execute(): Response { executeCount.incrementAndGet(); return respond(request, this) }
    override fun enqueue(callback: Callback) {
        enqueueCount.incrementAndGet()
        Thread {
            try { repeat(copies) { callback.onResponse(this, respond(request, this)); callbacksCompleted.incrementAndGet() } }
            catch (_: HostNoCallback) { /* Deliberately no callback. */ }
            catch (failure: IOException) { callback.onFailure(this, failure) }
        }.apply { isDaemon = true; start() }
    }
}
internal class HostFocusSession(private val failure: Exception? = null) {
    val revision = 0L
    suspend fun close() { failure?.let { throw it } }
}
internal class RecordingIoContext : kotlin.coroutines.AbstractCoroutineContextElement(Key) { companion object Key : kotlin.coroutines.CoroutineContext.Key<RecordingIoContext> }
internal class RecordingRequestEvidence(val command: String = "synthetic") { var rejected = false; fun beginRequest() {} }
internal class CaptureConfigurationEvidence(val request: String = "synthetic") { fun beforeDispatch(path: String) {}; fun acknowledge(value: String) {}; fun unavailable() {} }
internal class DesktopBridgeException(val statusCode: Int, val code: String = "HTTP_$statusCode") : IllegalStateException("Synthetic HTTP $statusCode") { val isRecordingCommandUnconfirmed = false }
internal class RecordingCommandUnconfirmedException(command: String, cause: Throwable) : IllegalStateException("Synthetic recording boundary", cause)
internal fun acceptsCaptureConfigurationBridgeReceipt(vararg arguments: Any?): Boolean = false
internal fun readCaptureConfigurationJsonText(input: InputStream, length: Long) = readStrictMediaJsonText(input, length, 65536)
internal enum class CameraFeature { LIVE_VIEW, CAMERA_CLOCK_SYNC, SENSOR_CLEANING, CAMERA_SLEEP }
internal enum class LiveViewSize { SMALL }
internal enum class LiveViewSource { AUTO }
internal data class LiveViewRequest(val fps: Int = 15, val size: LiveViewSize = LiveViewSize.SMALL, val source: LiveViewSource = LiveViewSource.AUTO)
data class CameraStatus(val bulbExposureActive: Boolean? = null, val shutterReleaseUnconfirmed: Boolean? = null)

internal class ConnectionRetirementBlockedException : Exception()
internal class ConnectionRetirementPendingException : Exception()
internal class CcapiLiveViewReleaseException : Exception()
internal class AutofocusReleaseException : Exception()
