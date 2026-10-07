package dev.openeos.control.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class CameraImportHandoffOrigin { CAMERA, SAVED_JPEG }
internal enum class CameraImportHandoffPhase { PREPARING, READY, AWAITING_RESULT, READING_RECEIPT, CLEANING }
internal enum class CameraImportHandoffIssue {
    EMPTY_SELECTION, STALE_SELECTION, AUTOMATIC_IMPORT_ACTIVE, HANDOFF_BUSY, SEREIN_UNAVAILABLE,
    PREPARATION_FAILED, SOURCE_UNAVAILABLE, INSUFFICIENT_SPACE, CANCELLED, LAUNCH_FAILED,
    RECEIPT_MISSING, RECEIPT_INVALID,
}
internal data class CameraImportHandoffOutcome(
    val summary: CameraImportReceiptSummary? = null,
    val issue: CameraImportHandoffIssue? = null,
)
internal data class CameraImportHandoffLease<S>(
    val token: Long,
    val origin: CameraImportHandoffOrigin,
    val reservation: CameraImportStagingReservation,
    val cameraGeneration: Long?,
    val phase: CameraImportHandoffPhase = CameraImportHandoffPhase.PREPARING,
    val session: S? = null,
    val outcome: CameraImportHandoffOutcome? = null,
    val cleanupUnconfirmed: Boolean = false,
)
internal data class CameraImportHandoffResult(
    val token: Long,
    val origin: CameraImportHandoffOrigin,
    val outcome: CameraImportHandoffOutcome?,
    val cleanupUnconfirmed: Boolean,
)
internal data class CameraImportHandoffState<S>(
    val active: CameraImportHandoffLease<S>? = null,
    val lastResult: CameraImportHandoffResult? = null,
) {
    val busy: Boolean get() = active != null
}

/** One retained owner for both sources. No camera reset or UI field can release this lease. */
internal class CameraImportHandoffOwner<S>(private val scope: CoroutineScope) {
    private class Work<S>(
        var lease: CameraImportHandoffLease<S>,
        val cleanup: suspend (CameraImportStagingReservation) -> Boolean,
        var preparation: Job? = null,
        var cleanupRunning: Boolean = false,
        var cleanupJob: Job? = null,
    )
    private var nextToken = 0L
    private var work: Work<S>? = null
    private val mutableState = MutableStateFlow(CameraImportHandoffState<S>())
    val state: StateFlow<CameraImportHandoffState<S>> = mutableState.asStateFlow()

    @Synchronized
    fun acquire(
        origin: CameraImportHandoffOrigin,
        reservation: CameraImportStagingReservation,
        cameraGeneration: Long? = null,
        cleanup: suspend (CameraImportStagingReservation) -> Boolean,
    ): CameraImportHandoffLease<S>? {
        if (work != null) return null
        check(nextToken < Long.MAX_VALUE) { "Handoff request limit reached." }
        val lease = CameraImportHandoffLease<S>(++nextToken, origin, reservation, cameraGeneration)
        work = Work(lease, cleanup)
        publish(lease)
        return lease
    }

    @Synchronized
    fun attachPreparation(token: Long, job: Job): Boolean {
        val current = matching(token) ?: return false.also { job.cancel() }
        if (current.lease.phase != CameraImportHandoffPhase.PREPARING || current.preparation != null) {
            job.cancel()
            return false
        }
        current.preparation = job
        // Also covers cancellation before the coroutine body starts, and the cancellable IO return gap.
        job.invokeOnCompletion {
            synchronized(this) {
                val latest = matching(token) ?: return@invokeOnCompletion
                if (latest.lease.phase == CameraImportHandoffPhase.PREPARING) {
                    latest.lease = latest.lease.copy(
                        phase = CameraImportHandoffPhase.CLEANING,
                        outcome = latest.lease.outcome ?: CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.CANCELLED),
                    )
                    publish(latest.lease)
                }
                if (latest.lease.phase == CameraImportHandoffPhase.CLEANING) scheduleCleanup(latest)
            }
        }
        return true
    }

    @Synchronized
    fun prepared(token: Long, session: S): Boolean {
        val current = matching(token) ?: return false
        if (current.lease.phase != CameraImportHandoffPhase.PREPARING) return false
        current.lease = current.lease.copy(phase = CameraImportHandoffPhase.READY, session = session)
        publish(current.lease)
        return true
    }

    /** The phase changes synchronously before any external Intent is sent. */
    @Synchronized
    fun claimLaunch(token: Long): CameraImportHandoffLease<S>? {
        val current = matching(token) ?: return null
        if (current.lease.phase != CameraImportHandoffPhase.READY || current.lease.session == null) return null
        current.lease = current.lease.copy(phase = CameraImportHandoffPhase.AWAITING_RESULT)
        publish(current.lease)
        return current.lease
    }

    @Synchronized
    fun claimResult(token: Long): CameraImportHandoffLease<S>? {
        val current = matching(token) ?: return null
        if (current.lease.phase != CameraImportHandoffPhase.AWAITING_RESULT) return null
        current.lease = current.lease.copy(phase = CameraImportHandoffPhase.READING_RECEIPT)
        publish(current.lease)
        return current.lease
    }

    /** Preparation calls this before returning; cleanup always joins that writer first. */
    @Synchronized
    fun finish(token: Long, outcome: CameraImportHandoffOutcome): Boolean {
        val current = matching(token) ?: return false
        if (current.lease.phase == CameraImportHandoffPhase.CLEANING) return false
        current.lease = current.lease.copy(phase = CameraImportHandoffPhase.CLEANING, outcome = outcome)
        publish(current.lease)
        scheduleCleanup(current)
        return true
    }

    /** Disconnect only cancels camera work which has not crossed the external launch boundary. */
    @Synchronized
    fun cancelUnlaunched(origin: CameraImportHandoffOrigin? = null): Job? {
        val current = work ?: return null
        if (origin != null && current.lease.origin != origin) return null
        if (current.lease.phase != CameraImportHandoffPhase.PREPARING &&
            current.lease.phase != CameraImportHandoffPhase.READY) return current.cleanupJob
        current.lease = current.lease.copy(phase = CameraImportHandoffPhase.CLEANING,
            outcome = CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.CANCELLED))
        publish(current.lease)
        current.preparation?.cancel()
        // Completion may have already scheduled (or finished) cleanup synchronously.
        // A failed immediate cleanup must remain failed until an explicit retry.
        return current.cleanupJob ?: scheduleCleanup(current)
    }

    @Synchronized
    fun retryCleanup(): Job? {
        val current = work ?: return null
        if (current.lease.phase != CameraImportHandoffPhase.CLEANING || !current.lease.cleanupUnconfirmed) return null
        return scheduleCleanup(current)
    }

    @Synchronized
    fun owns(token: Long, phase: CameraImportHandoffPhase? = null): Boolean =
        matching(token)?.let { phase == null || it.lease.phase == phase } == true

    private fun matching(token: Long): Work<S>? = work?.takeIf { it.lease.token == token }

    // Caller holds the monitor. A bounded failed cleanup keeps the lease and exact reservation.
    private fun scheduleCleanup(current: Work<S>): Job? {
        // Cancellation may synchronously invoke the writer's completion callback. Reuse its
        // cleanup job, even when immediate cleanup has already retired this exact Work.
        if (work !== current || current.cleanupRunning) return current.cleanupJob
        current.cleanupRunning = true
        val cleanupJob = scope.launch(NonCancellable, start = CoroutineStart.LAZY) {
            current.preparation?.join()
            val cleaned = withContext(NonCancellable) {
                try { current.cleanup(current.lease.reservation) } catch (_: Exception) { false }
            }
            synchronized(this@CameraImportHandoffOwner) {
                if (work !== current) return@synchronized
                current.cleanupRunning = false
                current.lease = current.lease.copy(cleanupUnconfirmed = !cleaned)
                val result = CameraImportHandoffResult(current.lease.token, current.lease.origin,
                    current.lease.outcome, !cleaned)
                if (cleaned) {
                    work = null
                    mutableState.value = CameraImportHandoffState(lastResult = result)
                } else {
                    mutableState.value = CameraImportHandoffState(current.lease, result)
                }
            }
        }
        // Publish the handle before execution so immediate completion and disposal can join it.
        current.cleanupJob = cleanupJob
        cleanupJob.start()
        return cleanupJob
    }

    private fun publish(lease: CameraImportHandoffLease<S>) {
        mutableState.value = mutableState.value.copy(active = lease)
    }
}
