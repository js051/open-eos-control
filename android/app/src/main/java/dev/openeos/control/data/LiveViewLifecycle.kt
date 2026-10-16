package dev.openeos.control.data

/** Evidence levels never imply one another in reverse: an ACK or encoded input is not pixels. */
internal data class LiveViewLifecycleEvidence(
    val sameOwner: Boolean = true,
    val sameAttempt: Boolean = true,
    val cancelled: Boolean = false,
    val retired: Boolean = false,
    val foregroundRequired: Boolean = true,
    val foreground: Boolean = true,
    val startAdmitted: Boolean = true,
    val startAcknowledged: Boolean = true,
    val stopEvidence: String? = "pending",
    val sourceMode: String? = "explicit",
    val terminal: Boolean = false,
    val frameEvidence: String? = "none",
) {
    fun evaluate(): LiveViewLifecycleDecision {
        val alive = sameOwner && sameAttempt && !cancelled && !retired && (!foregroundRequired || foreground)
        val level = when (frameEvidence) {
            "encoded-frame" -> 1
            "decoded-frame" -> 2
            "presented-frame" -> 3
            else -> 0
        }
        val ready = alive && startAdmitted && startAcknowledged && !terminal && stopEvidence == "pending"
        return LiveViewLifecycleDecision(
            canPublishInputReady = ready && level >= 1,
            canPublishDecoded = ready && level >= 2,
            canPublishPresented = ready && level >= 3,
            canFallback = alive && terminal && sourceMode == "AUTO" &&
                (stopEvidence == "confirmed" || (!startAdmitted && stopEvidence == "not-needed")),
            stopRequired = startAdmitted && stopEvidence != "confirmed",
        )
    }
}

internal data class LiveViewLifecycleDecision(
    val canPublishInputReady: Boolean,
    val canPublishDecoded: Boolean,
    val canPublishPresented: Boolean,
    val canFallback: Boolean,
    val stopRequired: Boolean,
)
