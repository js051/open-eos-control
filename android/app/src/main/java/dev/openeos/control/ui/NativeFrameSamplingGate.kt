package dev.openeos.control.ui

/** Main-thread, platform-free ownership policy. Invalidation never cancels a native operation. */
internal class NativeFrameSamplingGate {
    data class Ticket(val generation: Long, val sequence: Long, val renderedAtNanos: Long, val startedAtNanos: Long)

    private var generation = 0L
    private var inFlight: Ticket? = null
    private var lastStartedAtNanos: Long? = null
    private var lastSequence = -1L
    private var invalidatedAtNanos: Long? = null
    val busy: Boolean get() = inFlight != null

    fun invalidate(nowNanos: Long) {
        generation++
        lastSequence = -1L
        invalidatedAtNanos = nowNanos
        // Keep both the physical slot and cadence through settings/owner changes.
    }

    fun begin(sequence: Long, renderedAtNanos: Long, nowNanos: Long): Ticket? {
        if (inFlight != null || sequence <= lastSequence || !fresh(renderedAtNanos, nowNanos)) return null
        if (invalidatedAtNanos?.let { renderedAtNanos < it } == true) return null
        if (lastStartedAtNanos?.let { nowNanos - it < INTERVAL_NANOS } == true) return null
        return Ticket(generation, sequence, renderedAtNanos, nowNanos).also {
            inFlight = it
            lastSequence = sequence
            lastStartedAtNanos = nowNanos
        }
    }

    fun owns(ticket: Ticket): Boolean = ticket.generation == generation

    fun complete(ticket: Ticket, nowNanos: Long): Boolean {
        if (inFlight !== ticket) return false
        inFlight = null
        return owns(ticket) && fresh(ticket.renderedAtNanos, nowNanos) && fresh(ticket.startedAtNanos, nowNanos)
    }

    companion object {
        const val INTERVAL_NANOS = 250_000_000L
        const val MAX_AGE_NANOS = 1_000_000_000L

        fun fresh(atNanos: Long, nowNanos: Long): Boolean = nowNanos - atNanos in 0 until MAX_AGE_NANOS
    }
}
