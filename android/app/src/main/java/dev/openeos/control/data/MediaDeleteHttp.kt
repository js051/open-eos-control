package dev.openeos.control.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.atomic.AtomicBoolean

/** A dedicated one-shot DELETE path: no redirects, connection retries, or generic 2xx success. */
internal suspend fun executeMediaDeleteHttp(client: OkHttpClient, request: Request, evidence: MediaDeleteEvidence,
    checkOwner: () -> Unit = {},
): Unit = withContext(Dispatchers.IO) {
    val context = currentCoroutineContext()
    val dispatched = AtomicBoolean(false)
    val gatedClient = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .cache(null)
        .addNetworkInterceptor { chain ->
            check(chain.request().method == "DELETE" && chain.request().url == request.url)
            context.ensureActive()
            checkOwner()
            check(dispatched.compareAndSet(false, true)) { "A deletion cannot be transmitted twice." }
            evidence.beforeDispatch()
            chain.proceed(chain.request())
        }.build()
    val body = object : RequestBody() {
        override fun contentType() = null
        override fun contentLength() = 0L
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = Unit
    }
    val call = gatedClient.newCall(request.newBuilder().method("DELETE", body).build())
    val cancelCall = AtomicBoolean(true)
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { if (cancelCall.get()) call.cancel() }
    }
    try {
        call.execute().use { response ->
            // Read the complete bounded response before reporting any terminal receipt.
            var count = 0
            response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(4_096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    check(count <= 65_536) { "Deletion response exceeded its evidence limit." }
                }
            }
            val framingValid = response.code != 204 || (count == 0 &&
                response.headers.values("Content-Length").all { it == "0" } && response.header("Transfer-Encoding") == null)
            val outcome = mediaDeleteHttpOutcome(response.code, framingValid = framingValid, bodyBytes = count)
            if (outcome == MediaDeleteOutcome.CONFIRMED) {
                // Capture before withContext's prompt-cancellation return, even for a retired owner.
                evidence.onConfirmed()
            } else {
                if (outcome == MediaDeleteOutcome.REJECTED) evidence.onRejected()
                error("Deletion ${if (outcome == MediaDeleteOutcome.REJECTED) "was rejected" else "completion is unconfirmed"} (HTTP ${response.code}).")
            }
        }
    } finally { cancelCall.set(false); watcher.cancel() }
}
