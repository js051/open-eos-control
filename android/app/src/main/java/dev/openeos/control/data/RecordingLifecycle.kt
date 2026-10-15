package dev.openeos.control.data

/** A command receipt describes that request forever; a later observation cannot become its ACK. */
enum class RecordingCommand { START, STOP }
enum class RecordingResponseEvidence { NOT_SENT, UNKNOWN, RESPONSE_RECEIVED, REJECTED, NOT_REQUIRED }
enum class RecordingObservationSource { UNAVAILABLE, CCAPI_EVENT, SIMULATOR_STATUS, USB_EVENT, BRIDGE_STATUS }
data class RecordingObservation(val recording: Boolean?, val sequence: Long,
    val source: RecordingObservationSource = RecordingObservationSource.UNAVAILABLE) {
    val explicit: Boolean get() = recording != null && sequence > 0 && source != RecordingObservationSource.UNAVAILABLE
}
data class RecordingReceipt(val command: RecordingCommand, val response: RecordingResponseEvidence,
    val observation: RecordingObservation? = null, val retired: Boolean = false,
    val cancelled: Boolean = false, val sameOwner: Boolean = true, val priorStopRequired: Boolean = false,
    val sameAttempt: Boolean = true, val id: String = "", val requestCancelled: Boolean = false,
    val retainedStopRequired: Boolean? = null) {
    val canPublish: Boolean get() = sameOwner && sameAttempt && !cancelled && !retired
    // Cancellation/retirement forbids publishing late reads, but never erases an admitted Start's duty.
    val stopRequired: Boolean get() {
        if (!sameOwner || !sameAttempt) return priorStopRequired
        val duty = priorStopRequired || (command == RecordingCommand.START &&
            response in setOf(RecordingResponseEvidence.UNKNOWN, RecordingResponseEvidence.RESPONSE_RECEIVED))
        if (!canPublish) return duty
        if (observation?.explicit == true) return observation.recording == true
        retainedStopRequired?.let { return it }
        if (command == RecordingCommand.STOP && response == RecordingResponseEvidence.RESPONSE_RECEIVED) return false
        return duty
    }
    fun withObservation(next: RecordingObservation?): RecordingReceipt = copy(observation = next, retainedStopRequired = stopRequired)
    val mayReviewStoppedVideo: Boolean get() = canPublish && command == RecordingCommand.STOP &&
        observation?.explicit == true && observation.recording == false
}

/** Bounds include all reads; an explicit Refresh never contains a REC command. */
internal class RecordingIoContext : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RecordingIoContext>
}
internal suspend fun <T> recordingIoScope(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(RecordingIoContext()) { kotlinx.coroutines.withTimeout(15_000L) { block() } }

/** Strict additive wire schema. Missing, cached, coerced or cross-engine data is not observation. */
internal data class RecordingWireObservation(val evidence: String, val recording: Boolean?, val source: String?)
internal fun recordingWireObservation(raw: Map<String, Any?>, engine: String): RecordingWireObservation? {
    if (raw.keys != setOf("version", "engine", "observationEvidence", "recording", "source") ||
        raw["version"] !is Int || raw["version"] != 1 || raw["engine"] != engine) return null
    val evidence = raw["observationEvidence"] as? String ?: return null
    val recording = raw["recording"]
    val source = raw["source"]
    return when (evidence) {
        "observed" -> {
            val sourceEngine = when (source) {
                "ccapi-event", "ccapi-simulator" -> "ccapi"
                "usb-evf-record-status" -> "usb-ptp"
                "gphoto-config" -> "libgphoto2"
                else -> null
            }
            if (recording !is Boolean || sourceEngine == null || sourceEngine != engine) null
            else RecordingWireObservation(evidence, recording, source as String)
        }
        "unavailable", "read-failed" -> if (recording != null || source != null) null
            else RecordingWireObservation(evidence, null, null)
        else -> null
    }
}
internal data class RecordingWireOutcome(val response: RecordingResponseEvidence, val observation: RecordingWireObservation)
internal fun recordingWireOutcome(raw: Map<String, Any?>, engine: String, command: RecordingCommand): RecordingWireOutcome? {
    if (raw.keys != setOf("version", "engine", "operation", "responseEvidence", "observation") ||
        raw["version"] !is Int || raw["version"] != 1 || raw["engine"] != engine ||
        raw["operation"] != command.name.lowercase()) return null
    val response = when (raw["responseEvidence"]) {
        "response-received" -> RecordingResponseEvidence.RESPONSE_RECEIVED
        "not-sent" -> RecordingResponseEvidence.NOT_REQUIRED
        "unconfirmed" -> RecordingResponseEvidence.UNKNOWN
        "rejected" -> RecordingResponseEvidence.REJECTED
        else -> return null
    }
    @Suppress("UNCHECKED_CAST")
    val observation = recordingWireObservation(raw["observation"] as? Map<String, Any?> ?: return null, engine) ?: return null
    return RecordingWireOutcome(response, observation)
}

/** A poll admitted before a REC write cannot become post-command evidence just by arriving late. */
internal class RecordingEventFence {
    private var epoch = 0L
    private var exchanges = 0
    @Synchronized fun capture(): Long = if (exchanges > 0) -1L else epoch
    @Synchronized fun commandAdmission() { exchanges++; epoch++ }
    @Synchronized fun commandCompleted() { if (exchanges > 0) { exchanges--; epoch++ } }
    @Synchronized fun accept(readEpoch: Long, publish: () -> Unit): Boolean {
        if (exchanges > 0 || readEpoch < 0 || readEpoch != epoch) return false
        publish()
        return true
    }
}
internal fun recordingEventValue(fields: Map<String, Any?>): Boolean? {
    val values = listOf("action", "status").filter(fields::containsKey).map { fields[it] }
    if (values.isEmpty() || values.any { it !is String || it !in setOf("start", "stop") } || values.distinct().size != 1) return null
    return values.singleOrNull() == "start" || values.first() == "start"
}
