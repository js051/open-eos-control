package okhttp3

import java.io.Closeable
import java.io.InputStream
import okio.BufferedSink

/** Deliberately in-memory HTTP doubles; this is not an OkHttp/socket implementation. */
class Headers(private val values: Map<String, String>) {
    fun values(name: String) = values.filterKeys { it.equals(name, ignoreCase = true) }.values.toList()
}
class Response(val code: Int, val fields: Map<String, String> = emptyMap(), val body: Body? = Body()) : Closeable {
    val headers = Headers(fields)
    fun header(name: String) = headers.values(name).lastOrNull()
    override fun close() = Unit
}
class Body(val bytes: ByteArray = byteArrayOf()) {
    fun byteStream(): InputStream = bytes.inputStream()
}
abstract class RequestBody {
    abstract fun contentType(): Any?
    abstract fun contentLength(): Long
    open fun isOneShot() = false
    abstract fun writeTo(sink: BufferedSink)
}
class Request(val method: String, val url: String, val body: RequestBody? = null) {
    fun newBuilder() = Builder().url(url).method(method, body)
    class Builder {
        private var method = "GET"
        private var url = ""
        private var body: RequestBody? = null
        fun url(value: String) = apply { url = value }
        fun delete() = method("DELETE", null)
        fun method(value: String, valueBody: RequestBody?) = apply { method = value; body = valueBody }
        fun build() = Request(method, url, body)
    }
}
class Authenticator { companion object { val NONE = Authenticator() } }
class Chain(private val request: Request, private val proceed: (Request) -> Response) {
    fun request() = request
    fun proceed(request: Request) = proceed.invoke(request)
}
class Call(private val executeCall: () -> Response) {
    fun execute() = executeCall()
    fun cancel() = Unit
}
class OkHttpClient(val response: Response) {
    var dispatches = 0
    var lastRequest: Request? = null
    val options = mutableMapOf<String, Boolean>()
    private val interceptors = mutableListOf<(Chain) -> Response>()
    fun newBuilder() = Builder(this)
    class Builder(private val client: OkHttpClient) {
        fun retryOnConnectionFailure(value: Boolean) = apply { client.options["retry"] = value }
        fun followRedirects(value: Boolean) = apply { client.options["redirect"] = value }
        fun followSslRedirects(value: Boolean) = apply { client.options["sslRedirect"] = value }
        fun authenticator(value: Authenticator) = apply { check(value === Authenticator.NONE) }
        fun proxyAuthenticator(value: Authenticator) = apply { check(value === Authenticator.NONE) }
        fun cache(value: Any?) = apply { check(value == null) }
        fun addNetworkInterceptor(value: (Chain) -> Response) = apply { client.interceptors.add(value) }
        fun build() = client
    }
    fun newCall(request: Request) = Call {
        fun next(index: Int, current: Request): Response =
            if (index == interceptors.size) {
                dispatches++; lastRequest = current; response
            } else interceptors[index](Chain(current) { next(index + 1, it) })
        next(0, request)
    }
}
