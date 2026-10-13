package dev.openeos.control.data

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
    @Volatile var rejected = false
    val unresolved: Boolean get() = attempted && !rejected
}
