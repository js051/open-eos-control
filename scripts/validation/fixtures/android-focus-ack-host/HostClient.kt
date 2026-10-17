package dev.openeos.control.data

import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

internal class MaintenanceIoContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MaintenanceIoContext>
}
internal class ShootingSettingWriteContext(val evidence: ShootingEvidence) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ShootingSettingWriteContext>
}
internal class RecordingIoContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RecordingIoContext>
}
internal class ShootingEvidence { fun beforeDispatch() {}; fun responseReceived() {} }
internal class RecordingRequestEvidence { var rejected = false; fun beginRequest() {}; fun acknowledge() {} }
internal class JSONObject(text: String = "") {
    private val values = linkedMapOf<String, Any>()
    fun length() = 0
    fun optBoolean(key: String) = false
    fun optString(key: String) = ""
    fun optDouble(key: String) = 0.0
    fun put(key: String, value: Any) = apply { values[key] = value }
    override fun toString() = values.entries.joinToString(",", "{", "}") { (key, value) ->
        "\"$key\":" + if (value is String) "\"$value\"" else "$value"
    }
}
internal class CcapiHttpException(val statusCode: Int, message: String) : IllegalStateException(message)
internal class Request(val method: String = "POST", val url: String = "", val body: String = "") {
    fun newBuilder() = Builder().url(url).also { it.method = method; it.body = body }
    class Builder {
        var method = "POST"; var url = ""; var body = ""
        fun url(value: String) = apply { url = value }
        fun post(value: String) = apply { method = "POST"; body = value }
        fun put(value: String) = apply { method = "PUT"; body = value }
        fun <T> tag(type: Class<T>, value: T?) = this
        fun build() = Request(method, url, body)
    }
}
internal fun String.toRequestBody(media: String) = this
internal data class CcapiApiOperation(val method: String, val path: String)
internal data class CameraStatus(val bulbExposureActive: Boolean = false)
internal enum class CameraFeature { FOCUS_DRIVE, TAP_FOCUS, CLICK_WHITE_BALANCE }
internal enum class FocusDriveDirection { NEAR, FAR }
internal enum class FocusDriveStep { SMALL, MEDIUM, LARGE }
internal data class FocusDriveResult(val ok: Boolean, val direction: FocusDriveDirection, val step: FocusDriveStep)
internal data class FocusResult(val ok: Boolean, val x: Double, val y: Double)
internal fun JSONObject.toCameraStatus() = CameraStatus()
internal class Geometry {
    val owner = Any()
    var valid = true
    var checks = 0
    fun requireSnapshot(snapshot: Any) { checks++; check(valid && snapshot === owner) }
}
internal const val HALF_PRESS_DURATION_MILLIS = 1L
internal class Body(val held: Boolean = false, val oversized: Boolean = false, val status: Int = 204,
    val text: String = "", val declared: Long? = null, val responseHeaders: Map<String, String> = emptyMap()) {
    val entered = CountDownLatch(1)
    val unblock = CountDownLatch(if (held) 1 else 0)
    val exited = CountDownLatch(1)
    val cancelled = AtomicBoolean(false)
    fun contentLength(): Long = declared ?: if (oversized) 65_537 else -1
    fun byteStream(): InputStream = object : InputStream() {
        val bytes = text.toByteArray().inputStream()
        override fun read(): Int {
            entered.countDown()
            check(unblock.await(4, TimeUnit.SECONDS)) { "Fixture watchdog expired" }
            if (cancelled.get()) throw IOException("Synthetic transport cancelled")
            return bytes.read()
        }
    }
    fun string(): String = byteStream().use { it.readBytes().toString(Charsets.UTF_8) }
}
internal class Response(val body: Body) : Closeable {
    val isSuccessful get() = body.status in 200..299
    val code get() = body.status
    fun headers(name: String) = body.responseHeaders.filterKeys { it.equals(name, ignoreCase = true) }.values.toList()
    override fun close() { body.exited.countDown() }
}
internal class Call(val body: Body) {
    fun execute() = Response(body)
    fun cancel() { body.cancelled.set(true); body.unblock.countDown() }
}
internal class Client(val bodies: List<Body>) {
    val bounded = mutableListOf<Boolean>()
    val requests = mutableListOf<Request>()
    var dispatched = 0
    var statusReads = 0
    val isRealCamera = true
    val baseUrl = "http://synthetic.invalid"
    val jsonMediaType = "application/json"
    val enforceAdvertisedOperations = true
    var apiVersionPrefix = "/ccapi/ver120"
    val observedFeatures = mutableSetOf<CameraFeature>()
    val liveViewGeometry = Geometry()
    fun focusDriveOperation() = CcapiApiOperation("POST", "$apiVersionPrefix/shooting/control/drivefocus")
    fun tapFocusOperation() = CcapiApiOperation("PUT", "$apiVersionPrefix/shooting/liveview/afframeposition")
    fun clickWhiteBalanceOperation() = CcapiApiOperation("POST", "$apiVersionPrefix/shooting/liveview/clickwb")
    fun supportsCoordinateTapFocus() = true
    fun supportsCoordinateClickWhiteBalance() = true
    suspend fun cameraLiveViewPosition(x: Double, y: Double, feature: CameraFeature) = Triple(2500, 2600, liveViewGeometry.owner)
    fun apiPath(method: String, suffix: String) = apiVersionPrefix + suffix
    suspend fun status(): CameraStatus { statusReads++; return CameraStatus() }
    suspend fun postJson(path: String, payload: JSONObject) = JSONObject()
    fun newCameraCall(request: Request, strict: Boolean): Call {
        bounded.add(strict); requests.add(request)
        return Call(bodies[dispatched++])
    }
    suspend fun maintenanceJson(request: Request, code: Int, empty: Boolean) {}
    suspend fun raw(method: String, path: String) = requestOk(Request(method, baseUrl + path))
    // HOST_DRIVE
    // HOST_COORDINATES
    // HOST_COMMAND
    // HOST_REQUEST_OK
}
