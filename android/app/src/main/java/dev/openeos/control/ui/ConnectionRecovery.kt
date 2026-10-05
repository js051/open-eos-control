package dev.openeos.control.ui

import dev.openeos.control.data.ConnectionFailureReason
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ConnectionAttemptTarget { CCAPI, DESKTOP_BRIDGE, USB }

/** Safe connection-only evidence, with no address, credentials, device identity or server body. */
data class ConnectionRecovery(
    val target: ConnectionAttemptTarget,
    val reason: ConnectionFailureReason,
)

internal class InvalidConnectionAddressException : IllegalArgumentException("Enter a valid HTTP or HTTPS connection address.")

/** Retains supported base paths; never rewrites protocols or weakens transport validation. */
internal fun validateConnectionAddress(address: String, target: ConnectionAttemptTarget) {
    val url = address.toHttpUrlOrNull() ?: throw InvalidConnectionAddressException()
    // These are already the Bridge client's input restrictions. Validate without echoing input.
    if (target == ConnectionAttemptTarget.DESKTOP_BRIDGE &&
        (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)) {
        throw InvalidConnectionAddressException()
    }
}
