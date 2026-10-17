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
    fun length() = 0
    fun put(key: String, value: Any): JSONObject = this
    override fun toString() = "{}"
}
internal class CcapiHttpException(val statusCode: Int, message: String) : IllegalStateException(message)
internal class CcapiLiveViewReleaseException(cause: Throwable) : IllegalStateException(cause)
internal class Request(val method: String = "POST", val url: String = "http://synthetic.invalid/liveview") {
    fun newBuilder() = Builder().url(url).also { it.method = method }
    class Builder {
        var method = "POST"; var url = ""
        fun url(value: String) = apply { url = value }
        fun post(value: String) = apply { method = "POST" }
        fun delete() = apply { method = "DELETE" }
        fun get() = apply { method = "GET" }
        fun header(key: String, value: String) = this
        fun <T> tag(type: Class<T>, value: T?) = this
        fun build() = Request(method, url)
    }
}
internal fun String.toRequestBody(media: String) = this
internal enum class LiveViewSource(val label: String) { AUTO("auto"), CCAPI_JPEG_POLLING("jpeg"), CCAPI_MULTIPART("multipart"), CCAPI_RTP("rtp") }
internal enum class LiveViewSize(val ccapiValue: String) { LARGE("large"), MEDIUM("medium"), SMALL("small") }
internal data class LiveViewRequest(val source: LiveViewSource = LiveViewSource.CCAPI_JPEG_POLLING, val size: LiveViewSize = LiveViewSize.LARGE, val fps: Int = 30)
internal enum class CcapiLiveViewStopMethod { DELETE, POST_OFF }
internal data class CcapiApiOperation(val method: String, val path: String)
internal class Operations(val stopMethod: CcapiLiveViewStopMethod) {
    val startLiveView = CcapiApiOperation("POST", "/shooting/liveview")
    val stopLiveView = CcapiApiOperation(if (stopMethod == CcapiLiveViewStopMethod.DELETE) "DELETE" else "POST", "/shooting/liveview")
}
internal class Geometry { fun reset() {}; fun retire() {} }
internal class LiveViewFrame
internal enum class CameraFeature { LIVE_VIEW, LIVE_VIEW_RTP }
internal class LiveViewLifecycleEvidence(val startAdmitted: Boolean = false, val startAcknowledged: Boolean = false,
    val terminal: Boolean = false, val sourceMode: String = "", val stopEvidence: String = "", val frameEvidence: String = "") {
    val canFallback = stopEvidence != "unconfirmed"
    val canPublishDecoded = true
    fun evaluate() = this
}
internal class Session {
    var closed = false
    fun setTargetFps(fps: Int) {}
    fun start() {}
    suspend fun awaitReady(timeout: Long) {}
    fun close() { closed = true }
}
internal class SessionFactory(val session: Session) { fun create(description: String, ip: String) = session }
internal object CcapiRtpSessionDescriptionParser { fun parse(text: String) = text }
internal const val RTP_FIRST_VIDEO_TIMEOUT_MILLIS = 15_000L
internal class Body(val held: Boolean = false, val oversized: Boolean = false, val status: Int = 204, val text: String = "", val declared: Long? = null) {
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
    fun headers(name: String) = emptyList<String>()
    override fun close() { body.exited.countDown() }
}
internal class Call(val body: Body) {
    fun execute() = Response(body)
    fun cancel() { body.cancelled.set(true); body.unblock.countDown() }
}
internal class Client(val bodies: List<Body>, val stopMethod: CcapiLiveViewStopMethod = CcapiLiveViewStopMethod.DELETE) {
    val bounded = mutableListOf<Boolean>()
    val requests = mutableListOf<Request>()
    var dispatched = 0
    var priorProducerExitedBeforeStop = false
    val session = Session()
    val rtpSessionFactory = SessionFactory(session)
    val rtpDestinationAddress = "192.0.2.2"
    var nativeLiveViewSession: Session? = null
    val baseUrl = "http://synthetic.invalid"
    val jsonMediaType = "application/json"
    val isRealCamera = true
    val enforceAdvertisedOperations = true
    val liveViewGeometry = Geometry()
    var pendingLiveViewStop: (suspend () -> Unit)? = null
    var pendingLiveViewLocalClose: (suspend () -> Unit)? = null
    var pendingLiveViewMaintenanceOwned = false
    val maintenanceStopOwned = false
    var firstLiveViewFrame: LiveViewFrame? = null
    var activeLiveViewSource: LiveViewSource? = null
    var activeJpegLiveViewOperations: Operations? = null
    var activeMultipartLiveViewOperations: Operations? = null
    var liveViewStartAttempt = 0
    var liveViewSizeControlSupported = false
    var activeLiveViewSize = LiveViewSize.LARGE
    val rejectedLiveViewSizes = mutableSetOf<LiveViewSize>()
    val observedFeatures = mutableSetOf<CameraFeature>()
    fun requireShutterReleased() {}
    suspend fun refreshTemperatureStatusForRestrictedCommand() {}
    fun requireLiveViewAllowed() {}
    fun supportsRtpLiveView() = true
    fun supportsMultipartLiveView() = false
    fun supportsCompleteLiveView() = true
    suspend fun startMultipartLiveView(request: LiveViewRequest): Unit = error("Multipart stream is outside host fixture")
    suspend fun maintenanceStopRecoveryScope(stop: suspend () -> Unit) = stop()
    fun jpegLiveViewOperations() = Operations(stopMethod)
    fun liveViewFrameUrls(key: Long, request: LiveViewRequest) = listOf("http://synthetic.invalid/frame")
    suspend fun requestLiveViewFrame(request: Request, source: String) = LiveViewFrame()
    suspend fun validateJpegLiveViewFrame(request: LiveViewRequest) { firstLiveViewFrame = LiveViewFrame() }
    suspend fun validateFirstLiveViewJpeg(frame: LiveViewFrame) {}
    fun Throwable.isRejectedLiveViewSize() = false
    fun apiPath(method: String, suffix: String) = suffix
    suspend fun getText(path: String) = "synthetic SDP"
    fun newCameraCall(request: Request, strict: Boolean): Call {
        if (dispatched > 0) priorProducerExitedBeforeStop = bodies[dispatched - 1].exited.count == 0L
        bounded.add(strict); requests.add(request)
        return Call(bodies[dispatched++])
    }
    suspend fun maintenanceJson(request: Request, code: Int, empty: Boolean) {}
    // HOST_START
    // HOST_STOP
    // HOST_STOP_OWNED
    // HOST_CLEANUP
    // HOST_JPEG
    // HOST_CCAPI_START
    // HOST_CCAPI_STOP
    // HOST_RTP
    // HOST_POST
    // HOST_DELETE
    // HOST_REQUEST_OK
}
