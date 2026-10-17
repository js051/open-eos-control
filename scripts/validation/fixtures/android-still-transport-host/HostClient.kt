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
internal class JSONObject(text: String) { fun length() = 0 }
internal class CcapiHttpException(val statusCode: Int, message: String) : IllegalStateException(message)
internal class Request {
    val method = "POST"; val url = "http://synthetic.invalid/shutter"
    fun newBuilder() = this
    fun <T> tag(type: Class<T>, value: T?) = this
    fun build() = this
}
internal class Body(val held: Boolean = false, val oversized: Boolean = false) {
    val entered = CountDownLatch(1)
    val unblock = CountDownLatch(if (held) 1 else 0)
    val exited = CountDownLatch(1)
    val cancelled = AtomicBoolean(false)
    fun contentLength(): Long = if (oversized) 65_537 else -1
    fun byteStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            entered.countDown()
            try {
                check(unblock.await(4, TimeUnit.SECONDS)) { "Fixture watchdog expired" }
                if (cancelled.get()) throw IOException("Synthetic transport cancelled")
                return -1
            } finally { exited.countDown() }
        }
    }
    fun string(): String = byteStream().use { it.readBytes().toString(Charsets.UTF_8) }
}
internal class Response(val body: Body) : Closeable {
    val isSuccessful = true; val code = 204
    fun headers(name: String) = emptyList<String>()
    override fun close() {}
}
internal class Call(val body: Body) {
    fun execute() = Response(body)
    fun cancel() { body.cancelled.set(true); body.unblock.countDown() }
}
internal class Client(val bodies: List<Body>) {
    val bounded = mutableListOf<Boolean>()
    var dispatched = 0
    var priorProducerExitedBeforeRelease = false
    fun newCameraCall(request: Request, strict: Boolean): Call {
        if (dispatched > 0) priorProducerExitedBeforeRelease = bodies[dispatched - 1].exited.count == 0L
        bounded.add(strict)
        return Call(bodies[dispatched++])
    }
    suspend fun maintenanceJson(request: Request, code: Int, empty: Boolean) {}
    suspend fun captureCommand() { /* HOST_COMMAND_CALL */ }
    // HOST_REQUEST_OK
}
