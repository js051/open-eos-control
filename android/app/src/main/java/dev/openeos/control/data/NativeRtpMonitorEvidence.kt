package dev.openeos.control.data

/** Necessary evidence gate. The authoritative frame record is the captured frame, not a newer frame. */
internal data class NativeRtpMonitorEvidence(
    val active: Boolean? = null,
    val sameOwner: Boolean? = null,
    val sameRenderer: Boolean? = null,
    val foregroundRequired: Boolean? = null,
    val foreground: Boolean? = null,
    val terminal: Boolean? = null,
    val stopPending: Boolean? = null,
    val sampleFrame: Long? = null,
    val currentFrame: Long? = null,
    val sampleLut: Long? = null,
    val currentLut: Long? = null,
    val sampleAgeMs: Double? = null,
    val frameAgeMs: Double? = null,
    val maxAgeMs: Double? = null,
    val evidence: String? = null,
) {
    fun evaluate(): NativeRtpMonitorDecision {
        fun identity(value: Long?, minimum: Long) = value != null &&
            value >= minimum && value <= 9_007_199_254_740_991L
        val limit = maxAgeMs
        fun fresh(age: Double?) = age != null && age.isFinite() && limit != null && limit.isFinite() &&
            limit > 0 && limit <= 5_000 && age >= 0 && age <= limit
        val ready = active == true && sameOwner == true && sameRenderer == true &&
            (foregroundRequired == false || (foregroundRequired == true && foreground == true)) &&
            terminal == false && stopPending == true &&
            identity(sampleFrame, 1) && identity(currentFrame, 1) && sampleFrame == currentFrame &&
            identity(sampleLut, 0) && identity(currentLut, 0) && sampleLut == currentLut &&
            fresh(sampleAgeMs) && fresh(frameAgeMs)
        val level = when (evidence) {
            "decoded-output" -> 1
            "surface-submitted" -> 2
            "presentation-confirmed" -> 3
            else -> 0
        }
        return NativeRtpMonitorDecision(ready && level >= 1, ready && level >= 2, ready && level >= 3)
    }
}

internal data class NativeRtpMonitorDecision(
    val canPublishDecodedAnalysis: Boolean,
    val canPublishSubmittedAnalysis: Boolean,
    val canClaimPresentation: Boolean,
)
