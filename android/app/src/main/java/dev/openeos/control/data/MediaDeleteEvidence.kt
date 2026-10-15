package dev.openeos.control.data

import kotlinx.coroutines.CancellationException

/** Per-invocation transport evidence. Neither a refresh nor list absence is a receipt. */
data class MediaDeleteEvidence(
    val beforeDispatch: () -> Unit = {},
    val onConfirmed: () -> Unit = {},
    val onRejected: () -> Unit = {},
)

enum class MediaDeleteOutcome { NOT_STARTED, NOT_SENT, CONFIRMED, REJECTED, UNCERTAIN, SKIPPED }

/** The transport invokes the gate at the last local boundary, exactly once per row. */
class MediaDeleteAttempt {
    private var dispatched = false
    private var stopped = false
    private var finished = false
    private var receipt: MediaDeleteOutcome? = null
    @Synchronized fun beforeDispatch() {
        if (stopped || finished) throw CancellationException("Deletion stopped before dispatch.")
        check(!dispatched) { "A deletion attempt must not be dispatched twice." }
        dispatched = true
    }
    @Synchronized fun confirm() {
        check(dispatched) { "Deletion receipt without dispatch." }
        if (!finished) receipt = MediaDeleteOutcome.CONFIRMED
    }
    @Synchronized fun reject() {
        check(dispatched) { "Deletion rejection without dispatch." }
        if (!finished && receipt == null) receipt = MediaDeleteOutcome.REJECTED
    }
    @Synchronized fun cancel() { stopped = true }
    @Synchronized fun finish(): MediaDeleteOutcome {
        finished = true
        return reduceMediaDeleteOutcome(started = true, dispatched = dispatched,
            confirmedReceipt = receipt == MediaDeleteOutcome.CONFIRMED, rejectedReceipt = receipt == MediaDeleteOutcome.REJECTED)
    }
}

/** Bridge's synchronous route and repository CCAPI fixtures prove 204 only.
 * Other 2xx are unconfirmed; this is not an assertion about every Canon firmware. */
internal fun reduceMediaDeleteOutcome(started: Boolean, dispatched: Boolean, confirmedReceipt: Boolean,
    rejectedReceipt: Boolean, skipped: Boolean = false): MediaDeleteOutcome = when {
    dispatched && confirmedReceipt -> MediaDeleteOutcome.CONFIRMED
    dispatched && rejectedReceipt -> MediaDeleteOutcome.REJECTED
    dispatched -> MediaDeleteOutcome.UNCERTAIN
    skipped -> MediaDeleteOutcome.SKIPPED
    started -> MediaDeleteOutcome.NOT_SENT
    else -> MediaDeleteOutcome.NOT_STARTED
}

internal fun mediaDeleteHttpOutcome(status: Int, responseComplete: Boolean = true,
    framingValid: Boolean = true, bodyBytes: Int = 0): MediaDeleteOutcome = when {
    !responseComplete || !framingValid -> MediaDeleteOutcome.UNCERTAIN
    status == 204 && bodyBytes == 0 -> MediaDeleteOutcome.CONFIRMED
    status in setOf(400, 401, 403, 404, 405, 409, 412, 413, 415, 422, 429) -> MediaDeleteOutcome.REJECTED
    else -> MediaDeleteOutcome.UNCERTAIN
}

/** A concrete exact-ID handle only; never wildcard, reserved, truncated or prefix-stripped input. */
internal fun mediaDeletePtpHandle(id: String): Long {
    require(Regex("ptp:[0-9a-fA-F]{1,8}").matches(id)) { "Deletion requires an exact PTP media ID." }
    return id.substring(4).toLong(16).also {
        require(it in 1L..0xFFFF_FFFEL) { "Deletion requires a concrete PTP object handle." }
    }
}

/** Session creation has both operation engine and camera descriptor engine; disagreement is unknown. */
internal fun mediaDeleteBridgeEngine(sessionEngine: Any?, cameraEngine: Any?): String? =
    (sessionEngine as? String)?.takeIf { it in setOf("libgphoto2", "ccapi") && it == cameraEngine }

/** Display only the verified endpoint origin; never credentials, path, query, or fragment. */
internal fun mediaDeleteEndpoint(value: String?): String? = runCatching {
    val parsed = java.net.URI(value ?: return null)
    val scheme = parsed.scheme?.lowercase() ?: return null
    if (scheme !in setOf("http", "https") || parsed.host.isNullOrBlank() || parsed.port !in -1..65535) return null
    java.net.URI(scheme, null, parsed.host, parsed.port, null, null, null).toASCIIString()
}.getOrNull()
