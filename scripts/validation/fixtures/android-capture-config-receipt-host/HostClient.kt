package dev.openeos.control.data

import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
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
/** Typed host adapter; production StrictMediaJson still checks the real serialized bytes first. */
internal class JSONObject {
    private val values: MutableMap<String, Any?>
    constructor() { values = linkedMapOf() }
    constructor(text: String) { values = requireNotNull(registry[text]) { "Unregistered synthetic JSON" }.toMutableMap() }
    fun put(key: String, value: Any?) = apply { values[key] = value }
    fun opt(key: String) = values[key]
    fun getString(key: String) = values[key] as String
    override fun toString(): String = values.entries.joinToString(",", "{", "}") { (key, value) ->
        "\"$key\":" + if (value is String) "\"$value\"" else "$value"
    }.also { registry[it] = values.toMap() }
    companion object { private val registry = ConcurrentHashMap<String, Map<String, Any?>>() }
}
internal class CcapiHttpException(val statusCode: Int, message: String) : IllegalStateException(message)
internal data class HttpUrl(val value: String) { val encodedPath get() = java.net.URI(value).path }
internal class Request(val method: String = "POST", val url: HttpUrl = HttpUrl("http://synthetic.invalid"), val body: String = "") {
    fun newBuilder() = Builder().url(url.value).also { it.method = method; it.body = body }
    class Builder {
        var method = "POST"; var url = "http://synthetic.invalid"; var body = ""
        fun url(value: String) = apply { url = value }
        fun post(value: String) = apply { method = "POST"; body = value }
        fun put(value: String) = apply { method = "PUT"; body = value }
        fun <T> tag(type: Class<T>, value: T?) = this
        fun build() = Request(method, HttpUrl(url), body)
    }
}
internal fun String.toRequestBody(media: String) = this
internal data class CcapiApiOperation(val method: String, val path: String)
internal enum class CameraFeature(val label: String) { DIRECTORY_CONTROL("directory"), FILE_NAMING_CONTROL("naming") }
internal class Body(val text: String, val declared: Long = text.toByteArray().size.toLong()) {
    var closed = false
    fun contentLength() = declared
    fun byteStream(): InputStream = object : java.io.ByteArrayInputStream(text.toByteArray()) {
        override fun close() { closed = true; super.close() }
    }
    fun string() = text
}
internal class Response(val code: Int, val body: Body?, val headerMap: Map<String, String> = emptyMap()) : Closeable {
    val isSuccessful get() = code in 200..299
    var closed = false
    fun headers(name: String) = headerMap.filterKeys { it.equals(name, ignoreCase = true) }.values.toList()
    override fun close() { closed = true }
}
internal class Call(val response: Response, val dispatched: () -> Unit) { fun execute() = response.also { dispatched() }; fun cancel() {} }
internal fun naming(index: String = "A_") = CameraFileNaming("preset_code", listOf("preset_code"), "IMG_", "IMG",
    index, 1, CameraIntegerRange(1, 9999, 1), 1, CameraIntegerRange(1, 999, 1), "ABCDE")
internal class Client(val response: Response, val refreshedNaming: CameraFileNaming = naming("B2"), val isRealCamera: Boolean = true) {
    val baseUrl = "http://synthetic.invalid"
    val jsonMediaType = "application/json"
    val observedFeatures = mutableSetOf<CameraFeature>()
    var fileNamingLoaded = true
    var fileNamingState: CameraFileNaming? = naming()
    val requests = mutableListOf<Request>()
    val bounded = mutableListOf<Boolean>()
    var executions = 0
    var reads = 0
    var readFails = false
    private fun directoryOperations(): DirectoryOperations? = DirectoryOperations(
        CcapiApiOperation("GET", "/ccapi/ver100/functions/directory/directoryselection"),
        CcapiApiOperation("PUT", "/ccapi/ver100/functions/directory/directoryselection"),
        CcapiApiOperation("POST", "/ccapi/ver100/functions/directory/createdirectory"))
    fun fileNamingOperations(): Map<CameraFileNamingField, Pair<CcapiApiOperation, CcapiApiOperation>>? =
        FILE_NAMING_ENDPOINTS.mapValues { (_, endpoint) ->
            CcapiApiOperation("GET", "/ccapi/ver100" + endpoint.pathSuffix) to
                CcapiApiOperation("PUT", "/ccapi/ver100" + endpoint.pathSuffix)
        }
    suspend fun loadFileNaming(force: Boolean = false, fresh: Boolean = false): CameraFileNaming? {
        if (!force) return fileNamingState
        reads++
        check(!readFails) { "Synthetic naming read failure" }
        return refreshedNaming
    }
    suspend fun loadShootingSettings(directoryReadback: (CaptureConfigurationReadback?) -> Unit) {
        reads++
        directoryReadback(if (readFails) null else CaptureConfigurationReadback(
            directorySelection = "100EOSXX", directoryValues = listOf("100EOSXX", "101EOSXX")))
    }
    fun JSONObject.toValidatedFileNaming(): CameraFileNaming? = error("Simulator adapter is not exercised")
    fun newCameraCall(request: Request, strict: Boolean): Call {
        requests += request; bounded += strict
        return Call(response) { executions++ }
    }
    suspend fun maintenanceJson(request: Request): JSONObject? = error("Not a maintenance test")
    suspend fun unrelatedPartialGet() = requestJson(Request("GET"))
    // HOST_CREATE
    // HOST_NAMING
    // HOST_POST
    // HOST_PUT
    // HOST_REQUEST_JSON
}
// HOST_CONSTANTS
// HOST_VALUE
