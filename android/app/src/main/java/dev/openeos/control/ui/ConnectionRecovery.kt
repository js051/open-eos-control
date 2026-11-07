package dev.openeos.control.ui

import dev.openeos.control.data.ConnectionFailureReason
import dev.openeos.control.data.connectionFailure
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ConnectionAttemptTarget { CCAPI, DESKTOP_BRIDGE, USB }

/** Safe connection-only evidence, with no address, credentials, device identity or server body. */
data class ConnectionRecovery(
    val target: ConnectionAttemptTarget,
    val reason: ConnectionFailureReason,
    val cameraViaBridge: Boolean = false,
)

internal class InvalidConnectionAddressException : IllegalArgumentException("Enter a valid HTTP or HTTPS connection address.")

internal fun connectionRecoveryFor(target: ConnectionAttemptTarget, error: Throwable): ConnectionRecovery {
    // Preserve cancellation even if it is wrapped in a connection setup exception.
    val failure = connectionFailure(error)
    if (error is InvalidConnectionAddressException) {
        return ConnectionRecovery(target, ConnectionFailureReason.INVALID_ADDRESS)
    }
    return ConnectionRecovery(
        target, failure.reason,
        cameraViaBridge = target == ConnectionAttemptTarget.DESKTOP_BRIDGE && failure.cameraViaBridge,
    )
}

/** Retains supported base paths; never rewrites protocols or weakens transport validation. */
internal fun validateConnectionAddress(address: String, target: ConnectionAttemptTarget) {
    val url = address.toHttpUrlOrNull() ?: throw InvalidConnectionAddressException()
    // These are already the Bridge client's input restrictions. Validate without echoing input.
    if (target == ConnectionAttemptTarget.DESKTOP_BRIDGE &&
        (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)) {
        throw InvalidConnectionAddressException()
    }
}
