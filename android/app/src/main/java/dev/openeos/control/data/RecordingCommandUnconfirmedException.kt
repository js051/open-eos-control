package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A REC request was attempted, but there is no reliable command acknowledgement. */
internal class RecordingCommandUnconfirmedException(
    val command: RecordingCommand,
    cause: Exception,
) : Exception("Recording command result is unknown. Check the camera or explicitly stop recording.", cause)

/** One admitted request only. Evidence is retained even if prompt cancellation discards its return. */
internal class RecordingRequestEvidence(val command: RecordingCommand) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RecordingRequestEvidence>
    @Volatile var attempted = false
        private set
    @Volatile private var response = RecordingResponseEvidence.NOT_SENT
    var rejected: Boolean
        get() = response == RecordingResponseEvidence.REJECTED
        set(value) { if (value) reject() }
    private var retired = false
    var onAdmission: (() -> Long)? = null
    @Volatile var observationFloor: Long = 0
    @Volatile var observation: RecordingObservation? = null
        private set
    @Synchronized fun beginObservationRead() {
        observationFloor = maxOf(observationFloor, observation?.sequence ?: 0L)
        observation = null
    }
    @Synchronized fun observe(value: RecordingObservation?) {
        if (value?.explicit == true && value.sequence > observationFloor &&
            value.sequence > (observation?.sequence ?: 0)) observation = value
    }
    val unresolved: Boolean get() = response in setOf(RecordingResponseEvidence.UNKNOWN, RecordingResponseEvidence.RESPONSE_RECEIVED)
    @Synchronized fun responseEvidence(): RecordingResponseEvidence = response
    @Synchronized fun beginRequest() {
        if (retired) throw CancellationException("Recording request owner was retired before admission.")
        check(response == RecordingResponseEvidence.NOT_SENT) { "A recording command cannot be replayed." }
        onAdmission?.let { observationFloor = it() }
        attempted = true
        response = RecordingResponseEvidence.UNKNOWN
    }
    @Synchronized fun acknowledge() {
        check(attempted)
        if (response == RecordingResponseEvidence.UNKNOWN) response = RecordingResponseEvidence.RESPONSE_RECEIVED
    }
    @Synchronized fun remoteNoOp() {
        check(attempted && response == RecordingResponseEvidence.UNKNOWN)
        response = RecordingResponseEvidence.NOT_REQUIRED
    }
    @Synchronized fun noOp() {
        check(!attempted && response == RecordingResponseEvidence.NOT_SENT)
        response = RecordingResponseEvidence.NOT_REQUIRED
    }
    @Synchronized private fun reject() {
        if (response == RecordingResponseEvidence.UNKNOWN) response = RecordingResponseEvidence.REJECTED
    }
    @Synchronized fun retire(): Boolean { retired = true; return unresolved }
}
