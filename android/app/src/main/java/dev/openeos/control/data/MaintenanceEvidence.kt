package dev.openeos.control.data

import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

enum class MaintenanceAction(val wireName: String) { CLOCK_SYNC("clock-sync"), SENSOR_CLEANING("sensor-cleaning"), SLEEP("sleep") }
enum class MaintenanceCommandEvidence { NOT_SENT, UNKNOWN, RESPONSE_RECEIVED }
enum class MaintenanceStopEvidence { NOT_REQUIRED, NOT_ATTEMPTED, UNKNOWN, STOPPED }
enum class MaintenanceClockEvidence { NOT_READ, NOT_APPLICABLE, UNAVAILABLE, MISMATCH, MATCHED }
enum class MaintenanceAvailability { NOT_READ, UNAVAILABLE, REACHABLE }
enum class MaintenanceRestoreEvidence { NOT_REQUESTED, UNKNOWN, RESTORED }

/** Clock intent is captured before confirmation, including timezone and DST, never regenerated on retry. */
data class MaintenanceClockTarget(val epochMillis: Long, val offsetSeconds: Int, val daylightSaving: Boolean) {
    init { require(offsetSeconds in -64_800..64_800 && offsetSeconds % 60 == 0); Instant.ofEpochMilli(epochMillis) }
    companion object {
        fun now(zone: ZoneId = ZoneId.systemDefault()): MaintenanceClockTarget {
            val now = ZonedDateTime.now(zone)
            return MaintenanceClockTarget(now.toInstant().toEpochMilli(), now.offset.totalSeconds,
                now.zone.rules.isDaylightSavings(now.toInstant()))
        }
    }
}
data class MaintenanceRequest(val action: MaintenanceAction, val clock: MaintenanceClockTarget? = null,
    val autoPowerOff: Boolean = false) {
    init { require((action == MaintenanceAction.CLOCK_SYNC) == (clock != null)); require(!autoPowerOff || action == MaintenanceAction.SENSOR_CLEANING) }
    val needsStop: Boolean get() = action != MaintenanceAction.CLOCK_SYNC
    val allowsRestore: Boolean get() = action == MaintenanceAction.SENSOR_CLEANING && !autoPowerOff
}
data class MaintenanceClockReference(val earliestEpochSeconds: Long, val latestEpochSeconds: Long,
    val utcOffsetMinutes: Int?, val daylightSaving: Boolean?)
data class MaintenanceClockObservation(val epochSeconds: Long, val utcOffsetMinutes: Int? = null, val daylightSaving: Boolean? = null)
data class MaintenanceReadback(val clock: MaintenanceClockObservation? = null, val reachable: Boolean = true)
data class MaintenanceSnapshot(val request: MaintenanceRequest,
    val command: MaintenanceCommandEvidence = MaintenanceCommandEvidence.NOT_SENT,
    val eventStop: MaintenanceStopEvidence = if (request.needsStop) MaintenanceStopEvidence.NOT_ATTEMPTED else MaintenanceStopEvidence.NOT_REQUIRED,
    val liveViewStop: MaintenanceStopEvidence = if (request.needsStop) MaintenanceStopEvidence.NOT_ATTEMPTED else MaintenanceStopEvidence.NOT_REQUIRED,
    val clockRead: MaintenanceClockEvidence = if (request.clock == null) MaintenanceClockEvidence.NOT_APPLICABLE else MaintenanceClockEvidence.NOT_READ,
    val clockReference: MaintenanceClockReference? = null,
    val observedClock: MaintenanceClockObservation? = null,
    val recoveryClock: MaintenanceClockObservation? = null,
    val availability: MaintenanceAvailability = MaintenanceAvailability.NOT_READ,
    val restore: MaintenanceRestoreEvidence = MaintenanceRestoreEvidence.NOT_REQUESTED)

/** Separate evidence channels. Read/recovery can never upgrade a command response or prove cleaning/sleep. */
class MaintenanceEvidence(val request: MaintenanceRequest, private val requireOwner: () -> Unit = {},
    private val publish: (MaintenanceSnapshot) -> Unit = {},
    eventStopRequired: Boolean = request.needsStop, liveViewStopRequired: Boolean = request.needsStop) {
    private var cancelled = false
    private var value = MaintenanceSnapshot(request,
        eventStop = if (request.needsStop && eventStopRequired) MaintenanceStopEvidence.NOT_ATTEMPTED else MaintenanceStopEvidence.NOT_REQUIRED,
        liveViewStop = if (request.needsStop && liveViewStopRequired) MaintenanceStopEvidence.NOT_ATTEMPTED else MaintenanceStopEvidence.NOT_REQUIRED)
    @Synchronized fun snapshot() = value
    @Synchronized fun cancel() { cancelled = true }
    @Synchronized private fun checkCurrent() { if (cancelled) throw CancellationException("Maintenance cancelled."); requireOwner() }
    @Synchronized fun beginEventStop() { checkCurrent(); check(value.eventStop == MaintenanceStopEvidence.NOT_ATTEMPTED); update(value.copy(eventStop = MaintenanceStopEvidence.UNKNOWN)) }
    @Synchronized fun eventStopped() { check(value.eventStop == MaintenanceStopEvidence.UNKNOWN); update(value.copy(eventStop = MaintenanceStopEvidence.STOPPED)) }
    @Synchronized fun beginLiveViewStop() { checkCurrent(); check(value.liveViewStop == MaintenanceStopEvidence.NOT_ATTEMPTED); update(value.copy(liveViewStop = MaintenanceStopEvidence.UNKNOWN)) }
    @Synchronized fun liveViewStopped() { check(value.liveViewStop == MaintenanceStopEvidence.UNKNOWN); update(value.copy(liveViewStop = MaintenanceStopEvidence.STOPPED)) }
    @Synchronized fun beforeDispatch() {
        checkCurrent(); check(value.command == MaintenanceCommandEvidence.NOT_SENT) { "Maintenance command cannot be replayed." }
        check(!request.needsStop || (maintenanceStopSatisfied(value.eventStop) && maintenanceStopSatisfied(value.liveViewStop)))
        update(value.copy(command = MaintenanceCommandEvidence.UNKNOWN))
    }
    @Synchronized fun responseReceived() { check(value.command != MaintenanceCommandEvidence.NOT_SENT); update(value.copy(command = MaintenanceCommandEvidence.RESPONSE_RECEIVED)) }
    @Synchronized fun reference(reference: MaintenanceClockReference) {
        check(value.clockReference == null); require(validMaintenanceClockReference(reference))
        update(value.copy(clockReference = reference))
    }
    @Synchronized fun initialRead(readback: MaintenanceReadback) {
        require(request.clock != null || readback.clock == null)
        check(value.clockRead in setOf(MaintenanceClockEvidence.NOT_READ, MaintenanceClockEvidence.NOT_APPLICABLE))
        val clock = readback.clock
        val reference = value.clockReference
        update(value.copy(availability = if (readback.reachable) MaintenanceAvailability.REACHABLE else MaintenanceAvailability.UNAVAILABLE,
            observedClock = clock,
            clockRead = if (request.action != MaintenanceAction.CLOCK_SYNC) MaintenanceClockEvidence.NOT_APPLICABLE
                else if (clock == null || reference == null || !validMaintenanceClockObservation(clock)) MaintenanceClockEvidence.UNAVAILABLE
                else if (maintenanceClockMatches(reference, clock)) MaintenanceClockEvidence.MATCHED else MaintenanceClockEvidence.MISMATCH))
    }
    @Synchronized fun initialUnavailable() {
        if (value.clockRead == MaintenanceClockEvidence.NOT_READ) update(value.copy(clockRead = MaintenanceClockEvidence.UNAVAILABLE))
        update(value.copy(availability = MaintenanceAvailability.UNAVAILABLE))
    }
    @Synchronized fun recovery(readback: MaintenanceReadback) {
        require(request.clock != null || readback.clock == null)
        require(readback.clock == null || validMaintenanceClockObservation(readback.clock))
        update(value.copy(availability = if (readback.reachable) MaintenanceAvailability.REACHABLE else MaintenanceAvailability.UNAVAILABLE,
            recoveryClock = readback.clock))
    }
    @Synchronized fun recoveryUnavailable() { update(value.copy(availability = MaintenanceAvailability.UNAVAILABLE, recoveryClock = null)) }
    @Synchronized fun beginRestore() {
        requireOwner(); check(request.allowsRestore)
        check(maintenanceStopSatisfied(value.eventStop) && maintenanceStopSatisfied(value.liveViewStop))
        check(value.command == MaintenanceCommandEvidence.RESPONSE_RECEIVED && value.availability == MaintenanceAvailability.REACHABLE)
        check(value.restore == MaintenanceRestoreEvidence.NOT_REQUESTED) { "Restore cannot be silently replayed." }
        update(value.copy(restore = MaintenanceRestoreEvidence.UNKNOWN))
    }
    @Synchronized fun restored() { check(value.restore == MaintenanceRestoreEvidence.UNKNOWN); update(value.copy(restore = MaintenanceRestoreEvidence.RESTORED)) }
    private fun update(next: MaintenanceSnapshot) { value = next; publish(next) }
}
internal fun validMaintenanceClockObservation(value: MaintenanceClockObservation): Boolean =
    value.epochSeconds in -62_135_596_800L..253_402_300_799L &&
        ((value.utcOffsetMinutes == null && value.daylightSaving == null) ||
            (value.utcOffsetMinutes in -1439..1439 && value.daylightSaving != null))
internal fun validMaintenanceClockReference(value: MaintenanceClockReference): Boolean =
    validMaintenanceClockObservation(MaintenanceClockObservation(value.earliestEpochSeconds, value.utcOffsetMinutes, value.daylightSaving)) &&
        validMaintenanceClockObservation(MaintenanceClockObservation(value.latestEpochSeconds, value.utcOffsetMinutes, value.daylightSaving)) &&
        value.latestEpochSeconds >= value.earliestEpochSeconds && value.latestEpochSeconds - value.earliestEpochSeconds <= 120
internal fun maintenanceClockMatches(reference: MaintenanceClockReference, observed: MaintenanceClockObservation): Boolean =
    validMaintenanceClockReference(reference) && validMaintenanceClockObservation(observed) &&
        observed.epochSeconds in reference.earliestEpochSeconds..reference.latestEpochSeconds &&
        (reference.utcOffsetMinutes == null || (reference.utcOffsetMinutes == observed.utcOffsetMinutes && reference.daylightSaving == observed.daylightSaving))

/** All closures and routes must belong to the captured backend/session; read is strictly non-mutating. */
class MaintenanceAccess(val origin: String, val restoreAvailable: Boolean = true,
    val eventStopRequired: Boolean = true, val liveViewStopRequired: Boolean = true,
    val stopEvents: suspend () -> Unit,
    val stopLiveView: suspend () -> Unit,
    val dispatch: suspend (MaintenanceRequest, MaintenanceEvidence) -> Unit,
    val read: suspend (MaintenanceRequest) -> MaintenanceReadback,
    val restore: suspend () -> Unit)

internal fun maintenanceInteger(value: Any?): Long? = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }
internal fun maintenanceClockObservation(raw: Map<String, Any?>?): MaintenanceClockObservation? {
    if (raw == null || !raw.keys.containsAll(listOf("epochSeconds", "utcOffsetMinutes", "daylightSaving"))) return null
    val epoch = maintenanceInteger(raw["epochSeconds"]) ?: return null
    val offset = raw["utcOffsetMinutes"]
    val daylight = raw["daylightSaving"]
    if (offset == null && daylight != null || offset != null && daylight !is Boolean) return null
    val minutes = if (offset == null) null else maintenanceInteger(offset)?.takeIf { it in -1439..1439 }?.toInt() ?: return null
    return MaintenanceClockObservation(epoch, minutes, daylight as? Boolean).takeIf(::validMaintenanceClockObservation)
}
internal fun maintenanceClockReference(raw: Map<String, Any?>?): MaintenanceClockReference? {
    if (raw == null || !raw.keys.containsAll(listOf("earliestEpochSeconds", "latestEpochSeconds", "utcOffsetMinutes", "daylightSaving"))) return null
    val first = maintenanceInteger(raw["earliestEpochSeconds"]) ?: return null
    val last = maintenanceInteger(raw["latestEpochSeconds"]) ?: return null
    val clock = maintenanceClockObservation(mapOf("epochSeconds" to first, "utcOffsetMinutes" to raw["utcOffsetMinutes"], "daylightSaving" to raw["daylightSaving"])) ?: return null
    return MaintenanceClockReference(first, last, clock.utcOffsetMinutes, clock.daylightSaving).takeIf(::validMaintenanceClockReference)
}
internal fun applyMaintenanceBridgeOutcome(evidence: MaintenanceEvidence, engine: String?, status: Int,
    body: Map<String, Any?>): Boolean {
    val request = evidence.request
    if (status != 200 || body["version"] !is Int || body["version"] != 1 || engine == null || body["engine"] != engine ||
        body["operation"] != request.action.wireName || body["autoPowerOff"] != request.autoPowerOff ||
        body["responseEvidence"] != "response-received") return false
    evidence.responseReceived()
    if (request.action != MaintenanceAction.CLOCK_SYNC) return true
    @Suppress("UNCHECKED_CAST")
    val reference = maintenanceClockReference(body["clockReference"] as? Map<String, Any?>)
    @Suppress("UNCHECKED_CAST")
    val observation = maintenanceClockObservation(body["clockObservation"] as? Map<String, Any?>)
    if (reference != null) evidence.reference(reference)
    val expected = if (reference != null && observation != null && maintenanceClockMatches(reference, observation)) "matched" else "mismatch"
    if (reference != null && observation != null && body["clockReadEvidence"] == expected) {
        evidence.initialRead(MaintenanceReadback(observation))
    } else evidence.initialUnavailable()
    return true
}
internal fun maintenanceBridgeReadback(engine: String?, request: MaintenanceRequest, body: Map<String, Any?>): MaintenanceReadback? {
    if (engine == null || body["version"] !is Int || body["version"] != 1 || body["engine"] != engine ||
        body["operation"] != request.action.wireName) return null
    return if (request.action == MaintenanceAction.CLOCK_SYNC) {
        if (body["observationEvidence"] != "observed") null else {
            @Suppress("UNCHECKED_CAST")
            maintenanceClockObservation(body["clockObservation"] as? Map<String, Any?>)?.let { MaintenanceReadback(it) }
        }
    } else if (body["observationEvidence"] == "reachable" && body.containsKey("clockObservation") && body["clockObservation"] == null) MaintenanceReadback() else null
}
internal fun maintenanceMayPublish(sameOwner: Boolean, sameAttempt: Boolean, cancelled: Boolean, retired: Boolean): Boolean =
    sameOwner && sameAttempt && !cancelled && !retired
internal fun maintenanceMayRestore(sameOwner: Boolean, sameAttempt: Boolean, cancelled: Boolean, retired: Boolean,
    responseReceived: Boolean, stopConfirmed: Boolean, operation: String, autoPowerOff: Boolean,
    wasLive: Boolean, freshReachable: Boolean, safetyClear: Boolean): Boolean =
    maintenanceMayPublish(sameOwner, sameAttempt, cancelled, retired) && responseReceived && stopConfirmed &&
        operation == "sensor-cleaning" && !autoPowerOff && freshReachable && safetyClear

/** A successful arbitrary JSON object is not required battery-read evidence. */
internal fun validMaintenanceBatteryLevels(levels: List<Any?>): Boolean = levels.size in 1..32 && levels.all { value ->
    maintenanceInteger(value)?.let { it in 0..100 } == true || value in setOf("full", "middle", "low", "empty")
}

internal fun maintenanceStopSatisfied(value: MaintenanceStopEvidence): Boolean = value == MaintenanceStopEvidence.STOPPED || value == MaintenanceStopEvidence.NOT_REQUIRED

/** Carries the original attempt cancellation/owner gate across bounded late-ACK phases. */
internal class MaintenanceIoContext(val callerContext: kotlin.coroutines.CoroutineContext, val requireActive: () -> Unit) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<MaintenanceIoContext>
}

/** Cancel actual I/O on either the phase deadline or original caller cancellation. */
internal suspend fun maintenanceCancellationWatchers(cancel: () -> Unit): List<kotlinx.coroutines.Job> {
    val current = kotlin.coroutines.coroutineContext
    val contexts = listOfNotNull(current, current[MaintenanceIoContext]?.callerContext)
    return contexts.map { context ->
        kotlinx.coroutines.CoroutineScope(context).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { kotlinx.coroutines.awaitCancellation() } finally { cancel() }
        }
    }
}

internal fun requireMaintenanceEmptyResponse(status: Int, lengths: List<String>, encodings: List<String>, text: String) {
    check(status == 204 && lengths.size <= 1 && lengths.all { it == "0" } && encodings.isEmpty() && text.isEmpty()) {
        "Maintenance Stop requires a complete empty HTTP 204 response."
    }
}

internal fun maintenanceRestoreStopSafe(warning: Boolean, stopRequired: Boolean, running: Boolean, afterRestore: Boolean): Boolean =
    !warning && (!stopRequired || (afterRestore && running))

internal suspend fun <T> maintenanceIoScope(block: suspend () -> T): T {
    val caller = kotlin.coroutines.coroutineContext
    if (caller[MaintenanceIoContext] != null) return block()
    return kotlinx.coroutines.withContext(MaintenanceIoContext(caller) { caller.ensureActive() }) { block() }
}

/** A retained strict Stop obligation stores policy, never a cancelled operation context. */
internal suspend fun <T> maintenanceStopRecoveryScope(timeoutMillis: Long = 15_000L, block: suspend () -> T): T =
    maintenanceIoScope { kotlinx.coroutines.withTimeout(timeoutMillis) { block() } }

internal fun maintenanceMayRetryStop(holding: Boolean, warning: Boolean, hasPendingOperation: Boolean, safetyClear: Boolean): Boolean =
    holding && warning && !hasPendingOperation && safetyClear

internal class MaintenanceRetirementUnconfirmedException(
    val stopUnconfirmed: Boolean,
    cause: Throwable,
    val physicalReleaseUnconfirmed: Boolean = false,
    val retirementUnconfirmed: Boolean = true,
) : IllegalStateException(if (retirementUnconfirmed)
    "Local connection retired, but Bridge session retirement is unconfirmed. No retry or legacy cleanup was sent."
    else "Physical shutter/autofocus release remains unconfirmed after local retirement.", cause)

/** Keep both original release responsibilities even when one release fails. */
internal suspend fun maintenancePhysicalReleaseOnce(autofocus: suspend () -> Unit, shutter: suspend () -> Unit) {
    val focusFailure = try { autofocus(); null } catch (failure: Exception) { failure }
    val shutterFailure = try { shutter(); null } catch (failure: Exception) { failure }
    if (shutterFailure != null) { focusFailure?.let(shutterFailure::addSuppressed); throw shutterFailure }
    if (focusFailure != null) throw focusFailure
}

/** Explicit close preserves physical release, strict Stop and local retirement as separate evidence. */
internal suspend fun maintenanceCloseOnce(stopRequired: Boolean, stop: suspend () -> Unit,
    releasePhysical: suspend () -> Unit = {}, retireRemote: suspend () -> Unit = {}, retireLocal: suspend () -> Unit): Boolean {
    var stopUnconfirmed = false
    var physicalFailure: Exception? = null
    try {
        try { releasePhysical() } catch (failure: Exception) { physicalFailure = failure }
        if (stopRequired) {
            if (physicalFailure != null) stopUnconfirmed = true // Never cross an unresolved Bulb/shutter safety gate.
            else try {
                kotlin.coroutines.coroutineContext.ensureActive()
                stop()
            } catch (_: Exception) { stopUnconfirmed = true }
        }
        try {
            kotlin.coroutines.coroutineContext.ensureActive()
            retireRemote()
        } catch (failure: Exception) {
            physicalFailure?.let(failure::addSuppressed)
            throw MaintenanceRetirementUnconfirmedException(stopUnconfirmed, failure,
                physicalReleaseUnconfirmed = physicalFailure != null)
        }
        physicalFailure?.let { throw MaintenanceRetirementUnconfirmedException(stopUnconfirmed, it,
            physicalReleaseUnconfirmed = true, retirementUnconfirmed = false) }
        return stopUnconfirmed
    } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { retireLocal() } }
}
