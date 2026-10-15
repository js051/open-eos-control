package dev.openeos.control.data

/** Identity belongs to one backend and one admitted source attempt, not a mutable source enum. */
internal class CameraLiveViewOwner(val backend: Any, val attempt: Long)

/** Monotonic, bounded first-output and subsequent-output wait. Packets/ACKs do not reset it. */
internal class LiveViewInactivityDeadline(private val timeoutNanos: Long) {
    init { require(timeoutNanos > 0) }
    private var latestEvidenceAt: Long? = null

    @Synchronized fun arm(nowNanos: Long) { latestEvidenceAt = nowNanos }
    @Synchronized fun disarm() { latestEvidenceAt = null }
    @Synchronized fun observe(nowNanos: Long) {
        if (latestEvidenceAt != null) latestEvidenceAt = nowNanos
    }
    @Synchronized fun expired(nowNanos: Long): Boolean =
        latestEvidenceAt?.let { nowNanos - it >= timeoutNanos } ?: false
}

/** Preview transitions cannot interrupt an in-flight REC command or its unresolved Stop duty. */
internal fun liveViewRecordingInterlocked(inFlight: Boolean, pendingCommand: Boolean, stopRequired: Boolean): Boolean =
    inFlight || pendingCommand || stopRequired
