package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A REC request was attempted, but there is no reliable command acknowledgement. */
internal class RecordingCommandUnconfirmedException(
    val command: RecordingCommand,
    cause: Exception,
) : Exception("Recording command result is unknown. Check the camera or explicitly stop recording.", cause)

/** Per-call evidence survives prompt cancellation without turning cancellation into an ACK.
 * Only the actual REC HTTP boundary may mark attempted, never discovery or temperature reads.
 */
internal class RecordingRequestEvidence(val command: RecordingCommand) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RecordingRequestEvidence>
    @Volatile var attempted = false
        private set
    @Volatile var rejected = false
    private var retired = false
    val unresolved: Boolean get() = attempted && !rejected

    /** Shares the retirement lock so a passed cancellation check cannot admit an old owner. */
    @Synchronized fun beginRequest() {
        if (retired) throw CancellationException("Recording request owner was retired before admission.")
        attempted = true
    }

    /** Either sees an admitted request or prevents all later admission; never sends a command. */
    @Synchronized fun retire(): Boolean {
        retired = true
        return unresolved
    }
}
