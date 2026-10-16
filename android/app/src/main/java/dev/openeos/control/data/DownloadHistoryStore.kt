package dev.openeos.control.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class DownloadHistoryDestination { GALLERY, DOCUMENT, FOLDER }

enum class DownloadHistoryOutcome { IN_PROGRESS, COMPLETED, FAILED, CANCELLED, UNCONFIRMED }

/** No exception text, camera identity, media identifier or destination address belongs here. */
enum class DownloadHistoryWarning { READ_FAILED, WRITE_FAILED, CLEAR_FAILED, CAPACITY_REACHED, STOPPED }

data class DownloadHistoryEntry(
    val receiptId: String,
    val filename: String,
    val destination: DownloadHistoryDestination,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
    val outcome: DownloadHistoryOutcome,
    val cleanupUnconfirmed: Boolean = false,
)

/** A null receipt means selected but never admitted for an original-file attempt. */
data class DownloadWorklistRow(
    val rowId: String,
    val filename: String,
    val receipt: DownloadHistoryEntry? = null,
    /** Association to an earlier local row; never a source identity or retry capability. */
    val origin: DownloadWorklistOrigin? = null,
)

data class DownloadWorklistOrigin(val worklistId: String, val rowId: String)

/** The caller explicitly maps an old local row to a newly selected item's display label. */
data class DownloadWorklistContinuationSelection(
    val origin: DownloadWorklistOrigin,
    val filename: String,
    /** Fresh user acknowledgement for this mapping only; never serialized into a roster. */
    val uncertaintyAcknowledged: Boolean = false,
)

enum class DownloadContinuationStatus { AVAILABLE, UNCERTAIN, UNAVAILABLE }

/** Display-only local roster. It deliberately cannot locate a camera, source or destination. */
data class DownloadWorklist(
    val worklistId: String,
    val destination: DownloadHistoryDestination,
    val createdAtMillis: Long,
    val rows: List<DownloadWorklistRow>,
)

data class DownloadHistoryState(
    /** Most recently admitted first; wall-clock adjustments never reorder receipts. */
    val entries: List<DownloadHistoryEntry> = emptyList(),
    val loading: Boolean = true,
    val writable: Boolean = false,
    val warning: DownloadHistoryWarning? = null,
    /** Whole rosters, newest first; rows keep the user's selected order. */
    val worklists: List<DownloadWorklist> = emptyList(),
    /** Process ownership only. Neither these IDs nor live tokens are restored from disk. */
    val activeWorklistIds: Set<String> = emptySet(),
    /** Active rosters and their origin rosters, unavailable for another concurrent mapping. */
    val continuationBlockedWorklistIds: Set<String> = emptySet(),
)

/**
 * A local association cannot establish media equality. It can, however, prevent reusing an old
 * queued row to evade a later linked attempt's completion, active ownership, or uncertain output.
 * Follow the selected row to its oldest origin, then inspect that entire local association family,
 * including siblings. These user associations prevent duplicate attempts without proving media
 * equality. Filenames play no part in this decision.
 */
fun DownloadHistoryState.continuationStatus(origin: DownloadWorklistOrigin): DownloadContinuationStatus {
    val rows = continuationRows()
    if (rows.size > MAX_DOWNLOAD_HISTORY_ENTRIES) return DownloadContinuationStatus.UNAVAILABLE
    val root = rows.continuationRoot(origin) ?: return DownloadContinuationStatus.UNAVAILABLE
    val children = rows.entries.filter { it.value.origin != null }.groupBy { it.value.origin }
    val pending = ArrayDeque<DownloadWorklistOrigin>()
    val visited = mutableSetOf<DownloadWorklistOrigin>()
    pending.addLast(root)
    var uncertain = false
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current) || visited.size > MAX_DOWNLOAD_HISTORY_ENTRIES) return DownloadContinuationStatus.UNAVAILABLE
        val row = rows[current] ?: return DownloadContinuationStatus.UNAVAILABLE
        if (current.worklistId in activeWorklistIds || current.worklistId in continuationBlockedWorklistIds ||
            row.receipt?.outcome == DownloadHistoryOutcome.COMPLETED ||
            row.receipt?.outcome == DownloadHistoryOutcome.IN_PROGRESS) return DownloadContinuationStatus.UNAVAILABLE
        if (row.receipt?.outcome == DownloadHistoryOutcome.UNCONFIRMED || row.receipt?.cleanupUnconfirmed == true) uncertain = true
        children[current]?.forEach { pending.addLast(it.key) }
    }
    return if (uncertain) DownloadContinuationStatus.UNCERTAIN else DownloadContinuationStatus.AVAILABLE
}

private fun DownloadHistoryState.continuationRows(): Map<DownloadWorklistOrigin, DownloadWorklistRow> =
    worklists.flatMap { plan ->
        plan.rows.map { row -> DownloadWorklistOrigin(plan.worklistId, row.rowId) to row }
    }.toMap()

private fun Map<DownloadWorklistOrigin, DownloadWorklistRow>.continuationRoot(origin: DownloadWorklistOrigin): DownloadWorklistOrigin? {
    val visited = mutableSetOf<DownloadWorklistOrigin>()
    var current = origin
    while (visited.add(current) && visited.size <= MAX_DOWNLOAD_HISTORY_ENTRIES) {
        val row = this[current] ?: return null
        current = row.origin ?: return current
    }
    return null
}

/** Capture once when a save request is admitted, including every item of a batch. */
class DownloadHistoryRequest internal constructor(internal val owner: Any, internal val epoch: Any)

/** A receipt refers to one attempted item, even when its filename matches another attempt. */
class DownloadHistoryReceipt internal constructor(
    val receiptId: String,
    internal val request: DownloadHistoryRequest,
)

/** A live process capability, never reconstructed from a persisted display-only roster. */
class DownloadWorklistToken internal constructor(
    val worklistId: String,
    internal val request: DownloadHistoryRequest,
)

enum class DownloadWorklistFailure {
    RETIRED_REQUEST, INVALID_REQUEST, STORAGE_UNAVAILABLE, WRITE_FAILED, CAPACITY,
    INVALID_SELECTION, ROW_NOT_QUEUED, STOPPED,
    ORIGIN_NOT_ELIGIBLE, DUPLICATE_ORIGIN, RELEASED_WORKLIST, UNCERTAINTY_NOT_ACKNOWLEDGED,
}

sealed interface DownloadWorklistAdmission {
    data class Admitted(val token: DownloadWorklistToken) : DownloadWorklistAdmission
    data class Rejected(
        val reason: DownloadWorklistFailure,
        /** False when installation happened or could not be excluded. Never retry unrecorded then. */
        val planAbsent: Boolean,
    ) : DownloadWorklistAdmission
}

sealed interface DownloadWorklistRowStart {
    data class Started(val receipt: DownloadHistoryReceipt) : DownloadWorklistRowStart
    data class Rejected(val reason: DownloadWorklistFailure) : DownloadWorklistRowStart
}

/**
 * A process-owned history, separate from camera/session UI state.
 *
 * Legacy begin is best effort and records actual starts only. Selected batches first admit their
 * complete roster, then await each pending row write before any original/destination operation.
 * Callers own returned capabilities in NonCancellable sections, check cancellation afterward,
 * and report terminal results. Completion never waits for I/O. A cold pending receipt is always
 * UNCONFIRMED; a cold queued row remains queued. No stored row authorizes resumption.
 */
class DownloadHistoryStore internal constructor(
    private val storage: DownloadHistoryFileStorage,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    private val nowMillis: () -> Long,
) {
    constructor(
        directory: File,
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(DownloadHistoryFileStorage(directory), scope, ioDispatcher, System::currentTimeMillis)

    private val owner = Any()
    private val admissionLock = Any()
    private var epoch = Any()
    private var stopped = false
    private var durableSnapshot: DownloadHistorySnapshot? = null
    private var lastWriteInstallation = DownloadHistoryInstallation.NOT_INSTALLED
    private val activeWorklists = mutableMapOf<String, DownloadWorklistToken>()
    // A cancelled suspended receiver can lose an already-delivered command before its body
    // resumes. Settle that command as well as commands still buffered at worker shutdown.
    private val commands = Channel<Command>(Channel.UNLIMITED, onUndeliveredElement = ::rejectStoppedCommand)
    private val mutableState = MutableStateFlow(DownloadHistoryState())
    val state: StateFlow<DownloadHistoryState> = mutableState.asStateFlow()

    init {
        val worker = scope.launch(ioDispatcher) {
            initialize()
            for (command in commands) {
                when (command) {
                    is Command.Start -> start(command)
                    is Command.AdmitWorklist -> admit(command)
                    is Command.StartWorklistRow -> startRow(command)
                    is Command.ReleaseWorklist -> release(command.token)
                    is Command.Finish -> finish(command)
                    is Command.Clear -> clearSnapshot(command.result)
                    is Command.Barrier -> command.result.complete(Unit)
                }
            }
        }
        worker.invokeOnCompletion {
            synchronized(admissionLock) {
                stopped = true
                commands.close()
                activeWorklists.clear()
                mutableState.value = mutableState.value.copy(
                    entries = mutableState.value.entries.map(DownloadHistoryEntry::unconfirmPending),
                    worklists = mutableState.value.worklists.map(DownloadWorklist::unconfirmPending),
                    loading = false,
                    writable = false,
                    warning = DownloadHistoryWarning.STOPPED,
                    activeWorklistIds = emptySet(),
                    continuationBlockedWorklistIds = emptySet(),
                )
                while (true) {
                    rejectStoppedCommand(commands.tryReceive().getOrNull() ?: break)
                }
            }
        }
    }

    /** No I/O or state mutation: this may be invoked synchronously by channel cancellation. */
    private fun rejectStoppedCommand(command: Command) {
        when (command) {
            is Command.Clear -> command.result.complete(false)
            is Command.Start -> command.result.complete(null)
            is Command.AdmitWorklist -> command.result.complete(
                DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.STOPPED, true))
            is Command.StartWorklistRow -> command.result.complete(
                DownloadWorklistRowStart.Rejected(DownloadWorklistFailure.STOPPED))
            is Command.Barrier -> command.result.complete(Unit)
            is Command.ReleaseWorklist, is Command.Finish -> Unit
        }
    }

    fun captureRequest(): DownloadHistoryRequest = synchronized(admissionLock) {
        // Disk initialization does not change this epoch, including missing/corrupt snapshots.
        DownloadHistoryRequest(owner, epoch)
    }

    /** Legacy actual-start-only admission, including open-ended foreground imports. */
    suspend fun begin(
        request: DownloadHistoryRequest,
        filename: String,
        destination: DownloadHistoryDestination,
    ): DownloadHistoryReceipt? {
        val result = CompletableDeferred<DownloadHistoryReceipt?>()
        synchronized(admissionLock) {
            if (requestFailure(request) != null) {
                result.complete(null)
            } else {
                val receipt = DownloadHistoryReceipt(UUID.randomUUID().toString(), request)
                val entry = pendingEntry(receipt, filename, destination)
                if (commands.trySend(Command.Start(receipt, entry, result)).isFailure) result.complete(null)
            }
        }
        return result.await()
    }

    /**
     * Atomic all-or-none admission, before opening any source or destination. This never evicts
     * another receipt/roster and never truncates the selection. A rejection's planAbsent flag is
     * authoritative; a write exception alone cannot establish that the plan was not installed.
     */
    suspend fun admitWorklist(
        request: DownloadHistoryRequest,
        filenames: List<String>,
        destination: DownloadHistoryDestination,
    ): DownloadWorklistAdmission = admitSelection(request, filenames, destination, origins = null)

    /**
     * Remapping creates a new durable roster. The old rows keep their own receipts and outcomes;
     * these local links do not establish media equality, completed output, or permission to retry.
     * Eligibility is checked by the same actor that installs the complete selection before I/O.
     */
    suspend fun admitContinuation(
        request: DownloadHistoryRequest,
        selections: List<DownloadWorklistContinuationSelection>,
        destination: DownloadHistoryDestination,
    ): DownloadWorklistAdmission {
        val selected = selections.toList()
        return admitSelection(request, selected.map { it.filename }, destination, selected.map { it.origin },
            selected.filter { it.uncertaintyAcknowledged }.map { it.origin }.toSet())
    }

    private suspend fun admitSelection(
        request: DownloadHistoryRequest,
        filenames: List<String>,
        destination: DownloadHistoryDestination,
        origins: List<DownloadWorklistOrigin>?,
        uncertaintyAcknowledged: Set<DownloadWorklistOrigin> = emptySet(),
    ): DownloadWorklistAdmission {
        val result = CompletableDeferred<DownloadWorklistAdmission>()
        synchronized(admissionLock) {
            val failure = requestFailure(request)
            when {
                failure != null -> result.complete(DownloadWorklistAdmission.Rejected(failure, true))
                filenames.isEmpty() -> result.complete(DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.INVALID_SELECTION, true))
                filenames.size > MAX_DOWNLOAD_HISTORY_ENTRIES -> result.complete(DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.CAPACITY, true))
                else -> {
                    val worklist = DownloadWorklist(
                        UUID.randomUUID().toString(), destination, nowMillis().coerceAtLeast(0L),
                        filenames.mapIndexed { index, filename -> DownloadWorklistRow(
                            UUID.randomUUID().toString(), sanitizeDownloadHistoryFilename(filename),
                            origin = origins?.get(index)) },
                    )
                    val token = DownloadWorklistToken(worklist.worklistId, request)
                    if (commands.trySend(Command.AdmitWorklist(token, worklist, result, uncertaintyAcknowledged)).isFailure) {
                        result.complete(DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.STOPPED, true))
                    }
                }
            }
        }
        return result.await()
    }

    /** Retire batch ownership without retiring already-started receipts or mutating the roster. */
    fun releaseWorklist(token: DownloadWorklistToken) {
        synchronized(admissionLock) {
            if (requestFailure(token.request) == null) commands.trySend(Command.ReleaseWorklist(token))
        }
    }

    /**
     * Only Started authorizes a tracked attempt. Take ownership in a NonCancellable section,
     * then check cancellation and finish the receipt. Repeated starts never retry a row.
     */
    suspend fun beginWorklistRow(token: DownloadWorklistToken, index: Int): DownloadWorklistRowStart {
        val result = CompletableDeferred<DownloadWorklistRowStart>()
        synchronized(admissionLock) {
            val failure = requestFailure(token.request)
            if (failure != null) result.complete(DownloadWorklistRowStart.Rejected(failure))
            else if (commands.trySend(Command.StartWorklistRow(token, index, result)).isFailure) {
                result.complete(DownloadWorklistRowStart.Rejected(DownloadWorklistFailure.STOPPED))
            }
        }
        return result.await()
    }

    /** First terminal result wins; an evicted/cleared/missing receipt is never inserted again. */
    fun recordFinished(receipt: DownloadHistoryReceipt?, outcome: DownloadHistoryOutcome, cleanupUnconfirmed: Boolean = false) {
        if (receipt == null || !outcome.isTerminal()) return
        synchronized(admissionLock) {
            if (requestFailure(receipt.request) != null) return
            commands.trySend(Command.Finish(receipt, outcome, nowMillis().coerceAtLeast(0L),
                cleanupUnconfirmed && (outcome == DownloadHistoryOutcome.FAILED || outcome == DownloadHistoryOutcome.CANCELLED)))
        }
    }

    /**
     * True means an empty replacement was synced, closed and atomically installed. Failure is
     * always visible, even if readback finds the empty replacement installed after an exception;
     * in that case old requests are retired so they cannot reconstruct cleared data. A proved
     * unchanged snapshot preserves the epoch. An unreadable/ambiguous result fails closed.
     * Caller cancellation stops waiting, not this process-owned reset. No power-loss guarantee.
     */
    suspend fun clear(): Boolean {
        val result = CompletableDeferred<Boolean>()
        synchronized(admissionLock) {
            if (commands.trySend(Command.Clear(result)).isFailure) result.complete(false)
        }
        return result.await()
    }

    internal suspend fun awaitIdle() {
        val result = CompletableDeferred<Unit>()
        synchronized(admissionLock) {
            if (commands.trySend(Command.Barrier(result)).isFailure) result.complete(Unit)
        }
        result.await()
    }

    /** Caller must hold admissionLock. STOPPED never masquerades as a user clear. */
    private fun requestFailure(request: DownloadHistoryRequest): DownloadWorklistFailure? = when {
        stopped -> DownloadWorklistFailure.STOPPED
        request.owner !== owner -> DownloadWorklistFailure.INVALID_REQUEST
        request.epoch !== epoch -> DownloadWorklistFailure.RETIRED_REQUEST
        else -> null
    }

    private fun initialize() {
        mutableState.value = try {
            val loaded = storage.readSnapshot().also { durableSnapshot = it }
            DownloadHistoryState(
                entries = loaded.entries.map(DownloadHistoryEntry::unconfirmPending),
                worklists = loaded.worklists.map(DownloadWorklist::unconfirmPending),
                loading = false,
                writable = true,
            )
        } catch (_: Exception) {
            // Preserve unreadable input exactly. Only explicit clear may replace it.
            DownloadHistoryState(loading = false, warning = DownloadHistoryWarning.READ_FAILED)
        }
    }

    private fun start(command: Command.Start) {
        val current = mutableState.value
        if (!current.writable || synchronized(admissionLock) { requestFailure(command.receipt.request) } != null) {
            command.result.complete(null)
            return
        }
        val retained = current.entries.toMutableList()
        if (current.rowCount() >= MAX_DOWNLOAD_HISTORY_ENTRIES) {
            val evict = retained.indexOfLast { it.outcome.isTerminal() }
            if (evict < 0) {
                mutableState.value = current.copy(warning = DownloadHistoryWarning.CAPACITY_REACHED)
                command.result.complete(null)
                return
            }
            retained.removeAt(evict)
        }
        replaceLegacy(DownloadHistorySnapshot(listOf(command.entry) + retained, current.worklists))
        command.result.complete(command.receipt)
    }

    private fun admit(command: Command.AdmitWorklist) {
        val current = mutableState.value
        val failure = synchronized(admissionLock) { requestFailure(command.token.request) }
            ?: if (!current.writable) DownloadWorklistFailure.STORAGE_UNAVAILABLE else null
        if (failure != null) {
            command.result.complete(DownloadWorklistAdmission.Rejected(failure, true))
            return
        }
        val origins = command.worklist.rows.mapNotNull { it.origin }
        val rows = current.continuationRows()
        val roots = origins.mapNotNull { rows.continuationRoot(it) }
        if (origins.distinct().size != origins.size || roots.distinct().size != roots.size) {
            command.result.complete(DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.DUPLICATE_ORIGIN, true))
            return
        }
        val statuses = origins.associateWith { current.continuationStatus(it) }
        if (statuses.values.any { it == DownloadContinuationStatus.UNAVAILABLE }) {
            command.result.complete(DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE, true))
            return
        }
        if (statuses.any { (origin, status) ->
                status == DownloadContinuationStatus.UNCERTAIN && origin !in command.uncertaintyAcknowledged
            }) {
            command.result.complete(DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.UNCERTAINTY_NOT_ACKNOWLEDGED, true))
            return
        }
        if (current.rowCount() + command.worklist.rows.size > MAX_DOWNLOAD_HISTORY_ENTRIES) {
            mutableState.value = current.copy(warning = DownloadHistoryWarning.CAPACITY_REACHED)
            command.result.complete(DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.CAPACITY, true))
            return
        }
        val candidate = DownloadHistorySnapshot(current.entries, listOf(command.worklist) + current.worklists)
        if (write(candidate)) {
            activeWorklists[command.token.worklistId] = command.token
            publish(candidate)
            command.result.complete(DownloadWorklistAdmission.Admitted(command.token))
        } else {
            val observed = readAfterFailure()
            val absent = lastWriteInstallation == DownloadHistoryInstallation.NOT_INSTALLED &&
                observed != null && observed.worklists.none { it.worklistId == command.worklist.worklistId }
            reconcileFailedWrite(candidate, observed)
            command.result.complete(DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.WRITE_FAILED, absent))
        }
    }

    private fun startRow(command: Command.StartWorklistRow) {
        val current = mutableState.value
        val failure = synchronized(admissionLock) { requestFailure(command.token.request) }
            ?: if (!current.writable) DownloadWorklistFailure.STORAGE_UNAVAILABLE else null
        if (failure != null) {
            command.result.complete(DownloadWorklistRowStart.Rejected(failure))
            return
        }
        if (activeWorklists[command.token.worklistId] !== command.token) {
            command.result.complete(DownloadWorklistRowStart.Rejected(DownloadWorklistFailure.RELEASED_WORKLIST))
            return
        }
        val planIndex = current.worklists.indexOfFirst { it.worklistId == command.token.worklistId }
        val worklist = current.worklists.getOrNull(planIndex)
        if (worklist == null || command.index !in worklist.rows.indices) {
            command.result.complete(DownloadWorklistRowStart.Rejected(DownloadWorklistFailure.INVALID_SELECTION))
            return
        }
        val row = worklist.rows[command.index]
        if (row.receipt != null) {
            command.result.complete(DownloadWorklistRowStart.Rejected(DownloadWorklistFailure.ROW_NOT_QUEUED))
            return
        }
        val receipt = DownloadHistoryReceipt(UUID.randomUUID().toString(), command.token.request)
        val rows = worklist.rows.toMutableList()
        rows[command.index] = row.copy(receipt = pendingEntry(receipt, row.filename, worklist.destination))
        val plans = current.worklists.toMutableList()
        plans[planIndex] = worklist.copy(rows = rows)
        val candidate = DownloadHistorySnapshot(current.entries, plans)
        if (write(candidate)) {
            publish(candidate)
            command.result.complete(DownloadWorklistRowStart.Started(receipt))
        } else {
            // A pending row installed before the throw is conservatively unknown. No receipt
            // capability escapes and the caller must not open the source or destination.
            val observed = readAfterFailure()
            reconcileFailedWrite(candidate, observed, receipt.receiptId)
            command.result.complete(DownloadWorklistRowStart.Rejected(DownloadWorklistFailure.WRITE_FAILED))
        }
    }

    private fun pendingEntry(receipt: DownloadHistoryReceipt, filename: String, destination: DownloadHistoryDestination) =
        DownloadHistoryEntry(receipt.receiptId, sanitizeDownloadHistoryFilename(filename), destination,
            nowMillis().coerceAtLeast(0L), null, DownloadHistoryOutcome.IN_PROGRESS)

    private fun finish(command: Command.Finish) {
        val current = mutableState.value
        if (synchronized(admissionLock) { requestFailure(command.receipt.request) } != null) return
        fun publishFinished(snapshot: DownloadHistorySnapshot) {
            if (current.writable) replaceLegacy(snapshot)
            else publish(snapshot, current.warning, writable = false)
        }
        fun updated(entry: DownloadHistoryEntry) = entry.copy(outcome = command.outcome,
            finishedAtMillis = command.finishedAtMillis, cleanupUnconfirmed = command.cleanupUnconfirmed)
        val index = current.entries.indexOfFirst { it.receiptId == command.receipt.receiptId }
        if (index >= 0) {
            if (current.entries[index].outcome != DownloadHistoryOutcome.IN_PROGRESS) return
            val entries = current.entries.toMutableList()
            entries[index] = updated(entries[index])
            publishFinished(DownloadHistorySnapshot(entries, current.worklists))
            return
        }
        for ((planIndex, plan) in current.worklists.withIndex()) {
            val rowIndex = plan.rows.indexOfFirst { it.receipt?.receiptId == command.receipt.receiptId }
            if (rowIndex < 0) continue
            val row = plan.rows[rowIndex]
            if (row.receipt?.outcome != DownloadHistoryOutcome.IN_PROGRESS) return
            val rows = plan.rows.toMutableList()
            rows[rowIndex] = row.copy(receipt = updated(requireNotNull(row.receipt)))
            val plans = current.worklists.toMutableList()
            plans[planIndex] = plan.copy(rows = rows)
            publishFinished(DownloadHistorySnapshot(current.entries, plans))
            return
        }
    }

    private fun write(snapshot: DownloadHistorySnapshot): Boolean = try {
        storage.writeSnapshot(snapshot)
        durableSnapshot = snapshot
        lastWriteInstallation = DownloadHistoryInstallation.INSTALLED
        true
    } catch (failure: Exception) {
        lastWriteInstallation = (failure as? DownloadHistoryWriteException)?.installation ?: DownloadHistoryInstallation.UNKNOWN
        false
    }

    private fun readAfterFailure(): DownloadHistorySnapshot? = try { storage.readSnapshot() } catch (_: Exception) { null }

    /** Never rebuild an old in-memory image on top of an unknown commit. */
    private fun reconcileFailedWrite(
        candidate: DownloadHistorySnapshot,
        observed: DownloadHistorySnapshot?,
        unconfirmedReceiptId: String? = null,
    ) {
        val baseline = durableSnapshot
        when {
            observed == candidate -> {
                durableSnapshot = observed
                val visible = candidate.copy(worklists = candidate.worklists.map { plan ->
                    plan.copy(rows = plan.rows.map { row ->
                        if (row.receipt?.receiptId == unconfirmedReceiptId) row.copy(receipt = row.receipt?.unconfirmPending()) else row
                    })
                })
                publish(visible, DownloadHistoryWarning.WRITE_FAILED)
            }
            observed != null && observed == baseline -> {
                mutableState.value = mutableState.value.copy(warning = DownloadHistoryWarning.WRITE_FAILED)
            }
            else -> mutableState.value = mutableState.value.copy(writable = false, warning = DownloadHistoryWarning.WRITE_FAILED)
        }
    }

    private fun replaceLegacy(snapshot: DownloadHistorySnapshot) {
        if (write(snapshot)) publish(snapshot)
        else {
            val observed = readAfterFailure()
            val known = observed != null && (observed == snapshot || observed == durableSnapshot)
            if (known) durableSnapshot = observed
            // Retain actual live results after failure, but block subsequent writes if disk state
            // cannot be reconciled. This never invents a completed result after a cold restart.
            publish(snapshot, DownloadHistoryWarning.WRITE_FAILED, writable = known)
        }
    }

    private fun publish(snapshot: DownloadHistorySnapshot, warning: DownloadHistoryWarning? = null, writable: Boolean = true) {
        mutableState.value = DownloadHistoryState(snapshot.entries, loading = false, writable = writable,
            warning = warning, worklists = snapshot.worklists, activeWorklistIds = activeWorklists.keys.toSet(),
            continuationBlockedWorklistIds = blockedWorklists(snapshot.worklists))
    }

    private fun blockedWorklists(worklists: List<DownloadWorklist>): Set<String> {
        val blocked = activeWorklists.keys.toMutableSet()
        // Following earlier local associations also excludes ancestors of active continuations.
        val pending = ArrayDeque(blocked)
        val plans = worklists.associateBy { it.worklistId }
        while (pending.isNotEmpty()) {
            plans[pending.removeFirst()]?.rows?.forEach { row ->
                row.origin?.worklistId?.let { if (blocked.add(it)) pending.addLast(it) }
            }
        }
        return blocked
    }

    private fun release(token: DownloadWorklistToken) {
        if (activeWorklists[token.worklistId] !== token) return
        activeWorklists.remove(token.worklistId)
        val current = mutableState.value
        mutableState.value = current.copy(activeWorklistIds = activeWorklists.keys.toSet(),
            continuationBlockedWorklistIds = blockedWorklists(current.worklists))
    }

    private fun clearSnapshot(result: CompletableDeferred<Boolean>) {
        val empty = DownloadHistorySnapshot()
        if (write(empty)) {
            synchronized(admissionLock) {
                epoch = Any()
                activeWorklists.clear()
                publish(empty)
            }
            result.complete(true)
            return
        }
        val observed = readAfterFailure()
        synchronized(admissionLock) {
            when {
                observed != null && observed == durableSnapshot &&
                    lastWriteInstallation != DownloadHistoryInstallation.INSTALLED &&
                    (observed != empty || lastWriteInstallation == DownloadHistoryInstallation.NOT_INSTALLED) -> {
                    mutableState.value = mutableState.value.copy(warning = DownloadHistoryWarning.CLEAR_FAILED)
                }
                observed == empty && (observed != durableSnapshot || lastWriteInstallation == DownloadHistoryInstallation.INSTALLED) -> {
                    // Empty replacement was observed, but write completion was not confirmed.
                    // Retiring the epoch prevents any old batch from rebuilding cleared rows.
                    durableSnapshot = empty
                    epoch = Any()
                    activeWorklists.clear()
                    publish(empty, DownloadHistoryWarning.CLEAR_FAILED)
                }
                else -> mutableState.value = mutableState.value.copy(writable = false, warning = DownloadHistoryWarning.CLEAR_FAILED)
            }
        }
        result.complete(false)
    }

    private sealed interface Command {
        data class Start(val receipt: DownloadHistoryReceipt, val entry: DownloadHistoryEntry,
            val result: CompletableDeferred<DownloadHistoryReceipt?>) : Command
        data class AdmitWorklist(val token: DownloadWorklistToken, val worklist: DownloadWorklist,
            val result: CompletableDeferred<DownloadWorklistAdmission>,
            val uncertaintyAcknowledged: Set<DownloadWorklistOrigin>) : Command
        data class StartWorklistRow(val token: DownloadWorklistToken, val index: Int,
            val result: CompletableDeferred<DownloadWorklistRowStart>) : Command
        data class ReleaseWorklist(val token: DownloadWorklistToken) : Command
        data class Finish(val receipt: DownloadHistoryReceipt, val outcome: DownloadHistoryOutcome,
            val finishedAtMillis: Long, val cleanupUnconfirmed: Boolean) : Command
        data class Clear(val result: CompletableDeferred<Boolean>) : Command
        data class Barrier(val result: CompletableDeferred<Unit>) : Command
    }
}

internal const val MAX_DOWNLOAD_HISTORY_ENTRIES = 100
internal const val MAX_DOWNLOAD_HISTORY_FILENAME_LENGTH = 120

internal fun DownloadHistoryOutcome.isTerminal(): Boolean =
    this == DownloadHistoryOutcome.COMPLETED || this == DownloadHistoryOutcome.FAILED ||
        this == DownloadHistoryOutcome.CANCELLED

private fun DownloadHistoryState.rowCount(): Int = entries.size + worklists.sumOf { it.rows.size }

private fun DownloadHistoryEntry.unconfirmPending(): DownloadHistoryEntry =
    if (outcome == DownloadHistoryOutcome.IN_PROGRESS) copy(outcome = DownloadHistoryOutcome.UNCONFIRMED)
    else this

private fun DownloadWorklist.unconfirmPending(): DownloadWorklist =
    copy(rows = rows.map { it.copy(receipt = it.receipt?.unconfirmPending()) })

/** A display-only basename. URI inputs are rejected rather than preserving authority or tokens. */
internal fun sanitizeDownloadHistoryFilename(value: String): String {
    val cleaned = value.filterNot {
        it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || it.isSurrogate()
    }.trim()
    val windowsPath = Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(cleaned)
    if (!windowsPath && Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(cleaned)) return "download"
    return cleaned.substringBefore('?').substringBefore('#')
        .replace('\\', '/').substringAfterLast('/').trim()
        .take(MAX_DOWNLOAD_HISTORY_FILENAME_LENGTH)
        .takeUnless { it.isBlank() || it == "." || it == ".." || ':' in it }
        ?: "download"
}
