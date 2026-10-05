package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Connection guidance may use these values without retaining an address, token, or response body. */
enum class ConnectionFailureReason {
    INVALID_ADDRESS,
    AUTHENTICATION_REJECTED,
    ENDPOINT_NOT_FOUND,
    HTTP_ERROR,
    TLS_ERROR,
    HOST_NOT_FOUND,
    TIMEOUT,
    UNREACHABLE,
    DISCOVERY_FAILED,
    UNKNOWN,
}

/** Network selection failed before HTTP; preserves the old non-retryable setup exception type. */
internal class CameraWifiRouteUnavailableException : IllegalStateException(
    "No Wi-Fi route can reach the camera. Connect the phone to the camera network before retrying."
)

/** The actual response status, never a server-provided error code or message. */
internal interface ConnectionHttpFailure {
    val statusCode: Int?
}

internal fun httpConnectionFailureReason(statusCode: Int): ConnectionFailureReason = when (statusCode) {
    401, 403 -> ConnectionFailureReason.AUTHENTICATION_REJECTED
    404 -> ConnectionFailureReason.ENDPOINT_NOT_FOUND
    in 200..299 -> ConnectionFailureReason.UNKNOWN
    else -> ConnectionFailureReason.HTTP_ERROR
}

/** Preserves existing diagnostic exception compatibility; only enum evidence enters recovery UI. */
internal class CcapiDiscoveryException(
    message: String,
    reasons: Set<ConnectionFailureReason>,
) : IllegalStateException(message) {
    // A set of enum values is bounded regardless of the number of attempted endpoints.
    val reasons: Set<ConnectionFailureReason> = reasons.map {
        if (it == ConnectionFailureReason.UNKNOWN) ConnectionFailureReason.DISCOVERY_FAILED else it
    }.toSet().ifEmpty { setOf(ConnectionFailureReason.DISCOVERY_FAILED) }

    val reason: ConnectionFailureReason
        get() = DISCOVERY_REASON_PRIORITY.first { it in reasons }
}

// A fallback 404 must not erase a concrete authentication, TLS, or transport failure.
private val DISCOVERY_REASON_PRIORITY = listOf(
    ConnectionFailureReason.INVALID_ADDRESS,
    ConnectionFailureReason.AUTHENTICATION_REJECTED,
    ConnectionFailureReason.TLS_ERROR,
    ConnectionFailureReason.HOST_NOT_FOUND,
    ConnectionFailureReason.TIMEOUT,
    ConnectionFailureReason.UNREACHABLE,
    ConnectionFailureReason.HTTP_ERROR,
    ConnectionFailureReason.DISCOVERY_FAILED,
    ConnectionFailureReason.ENDPOINT_NOT_FOUND,
)

/** Use only at connection boundaries; command and release safety errors keep their existing handling. */
internal fun connectionFailureReason(error: Throwable): ConnectionFailureReason {
    // Bound traversal, including unusual cyclic cause chains. Never inspect diagnostic text.
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    for (cause in causes) {
        val reason = when (cause) {
            is CcapiDiscoveryException -> cause.reason
            is CameraWifiRouteUnavailableException -> ConnectionFailureReason.UNREACHABLE
            is ConnectionHttpFailure -> cause.statusCode?.let(::httpConnectionFailureReason)
                ?: ConnectionFailureReason.UNKNOWN
            is SSLException -> ConnectionFailureReason.TLS_ERROR
            is UnknownHostException -> ConnectionFailureReason.HOST_NOT_FOUND
            // Includes SocketTimeoutException and OkHttp's call-timeout InterruptedIOException.
            is InterruptedIOException -> ConnectionFailureReason.TIMEOUT
            is ConnectException, is NoRouteToHostException, is SocketException ->
                ConnectionFailureReason.UNREACHABLE
            else -> ConnectionFailureReason.UNKNOWN
        }
        if (reason != ConnectionFailureReason.UNKNOWN) return reason
    }
    return ConnectionFailureReason.UNKNOWN
}
