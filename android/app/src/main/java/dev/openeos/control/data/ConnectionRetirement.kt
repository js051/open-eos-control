package dev.openeos.control.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Retain an acquired owner before a dispatcher can discard its result on cancellation.
 * Network acquisition has a bounded I/O policy. A noncooperative OS open can keep
 * retirement pending; logical cancellation still never publishes a connection.
 */
internal suspend fun <T> acquireConnectionResource(acquire: suspend () -> T, retain: (T) -> Unit) {
    currentCoroutineContext().ensureActive()
    withContext(NonCancellable) { retain(acquire()) }
    currentCoroutineContext().ensureActive()
}

/** A previous resource may still exist; no endpoint, authentication or server text enters UI. */
internal class ConnectionRetirementUnconfirmedException(cause: Throwable) :
    IllegalStateException("Previous connection cleanup is unconfirmed.", cause)

/** Only abandoned setup cleanup gets the bounded retirement budget; ordinary close is unchanged. */
internal object ConnectionAttemptCleanup : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<ConnectionAttemptCleanup>
}

internal class ConnectionRetirementBlockedException :
    IllegalStateException("Check and close the previous connection before restarting the app.")

/** Only explicit Bridge rejection contracts establish that no session was created. */
internal fun bridgeSessionCreationRejected(status: Int?, code: String?, engine: String?): Boolean =
    code !in setOf("SHUTTER_RELEASE_UNCONFIRMED", "LIVE_VIEW_STOP_UNCONFIRMED", "RECORDING_COMMAND_UNCONFIRMED") &&
        (status in setOf(400, 401, 403, 404, 405, 409, 422, 429) ||
            (status in setOf(501, 503) && code == "ENGINE_UNAVAILABLE") ||
            (status == 502 && code == "CCAPI_DISCOVERY_FAILED" && engine == "ccapi"))

internal fun bridgeCreatedSessionId(value: Any?): String? = (value as? String)?.takeIf {
    it.length in 1..128 && it.all { character ->
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '_' || character == '-'
    }
}

internal fun bridgeSessionRetirementAcknowledged(status: Int, body: String): Boolean =
    status == 204 && body.isEmpty()

internal class ConnectionRetirementPendingException : IllegalStateException("Previous connection cleanup is still pending.")

internal enum class PreviousConnectionSafetyWarning { SHUTTER, LIVE_VIEW, RECORDING }

internal fun previousConnectionSafetyWarnings(error: Throwable): Set<PreviousConnectionSafetyWarning> =
    generateSequence(error) { it.cause }.take(16).mapNotNull { cause ->
        when (cause) {
            is ShutterReleaseException, is AutofocusReleaseException -> PreviousConnectionSafetyWarning.SHUTTER
            is DesktopBridgeException -> when (cause.code) {
                "SHUTTER_RELEASE_UNCONFIRMED" -> PreviousConnectionSafetyWarning.SHUTTER
                "LIVE_VIEW_STOP_UNCONFIRMED" -> PreviousConnectionSafetyWarning.LIVE_VIEW
                "RECORDING_COMMAND_UNCONFIRMED" -> PreviousConnectionSafetyWarning.RECORDING
                else -> null
            }
            else -> null
        }
    }.toSet()
