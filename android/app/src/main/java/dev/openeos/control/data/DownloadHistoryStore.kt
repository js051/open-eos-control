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
)

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
)

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
    private val commands = Channel<Command>(Channel.UNLIMITED)
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
                mutableState.value = mutableState.value.copy(
                    entries = mutableState.value.entries.map(DownloadHistoryEntry::unconfirmPending),
                    worklists = mutableState.value.worklists.map(DownloadWorklist::unconfirmPending),
                    loading = false,
                    writable = false,
                    warning = DownloadHistoryWarning.STOPPED,
                )
                while (true) {
                    when (val pending = commands.tryReceive().getOrNull() ?: break) {
                        is Command.Clear -> pending.result.complete(false)
                        is Command.Start -> pending.result.complete(null)
                        is Command.AdmitWorklist -> pending.result.complete(
                            DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.STOPPED, true))
                        is Command.StartWorklistRow -> pending.result.complete(
                            DownloadWorklistRowStart.Rejected(DownloadWorklistFailure.STOPPED))
                        is Command.Barrier -> pending.result.complete(Unit)
                        else -> Unit
                    }
                }
            }
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
                        filenames.map { DownloadWorklistRow(UUID.randomUUID().toString(), sanitizeDownloadHistoryFilename(it)) },
                    )
                    val token = DownloadWorklistToken(worklist.worklistId, request)
                    if (commands.trySend(Command.AdmitWorklist(token, worklist, result)).isFailure) {
                        result.complete(DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.STOPPED, true))
                    }
                }
            }
        }
        return result.await()
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
        if (current.rowCount() + command.worklist.rows.size > MAX_DOWNLOAD_HISTORY_ENTRIES) {
            command.result.complete(DownloadWorklistAdmission.Rejected(DownloadWorklistFailure.CAPACITY, true))
            return
        }
        val candidate = DownloadHistorySnapshot(current.entries, listOf(command.worklist) + current.worklists)
        if (write(candidate)) {
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
            warning = warning, worklists = snapshot.worklists)
    }

    private fun clearSnapshot(result: CompletableDeferred<Boolean>) {
        val empty = DownloadHistorySnapshot()
        if (write(empty)) {
            synchronized(admissionLock) {
                epoch = Any()
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
            val result: CompletableDeferred<DownloadWorklistAdmission>) : Command
        data class StartWorklistRow(val token: DownloadWorklistToken, val index: Int,
            val result: CompletableDeferred<DownloadWorklistRowStart>) : Command
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
