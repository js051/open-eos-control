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
enum class DownloadHistoryWarning { READ_FAILED, WRITE_FAILED, CLEAR_FAILED, STOPPED }

data class DownloadHistoryEntry(
    val receiptId: String,
    val filename: String,
    val destination: DownloadHistoryDestination,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
    val outcome: DownloadHistoryOutcome,
    val cleanupUnconfirmed: Boolean = false,
)

data class DownloadHistoryState(
    /** Most recently admitted first; wall-clock adjustments never reorder receipts. */
    val entries: List<DownloadHistoryEntry> = emptyList(),
    val loading: Boolean = true,
    val writable: Boolean = false,
    val warning: DownloadHistoryWarning? = null,
)

/** Capture once when a save request is admitted, including every item of a batch. */
class DownloadHistoryRequest internal constructor(internal val owner: Any, internal val epoch: Any)

/** A receipt refers to one attempted item, even when its filename matches another attempt. */
class DownloadHistoryReceipt internal constructor(
    val receiptId: String,
    internal val request: DownloadHistoryRequest,
)

/**
 * A process-owned, best-effort history, separate from camera/session UI state.
 *
 * A single actor loads the snapshot before handling ordered commands. Admission waits for a
 * durable pending receipt or an explicit history warning, before the media operation starts.
 * Completion does not wait for filesystem I/O. The scope must outlive view models and individual
 * transfers. Pending records loaded from disk are always UNCONFIRMED.
 * Completion records only what the operation reported then, never whether a file still exists.
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
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(DownloadHistoryState())
    val state: StateFlow<DownloadHistoryState> = mutableState.asStateFlow()

    init {
        val worker = scope.launch(ioDispatcher) {
            initialize()
            for (command in commands) {
                when (command) {
                    is Command.Start -> start(command)
                    is Command.Finish -> finish(command)
                    is Command.Clear -> clearSnapshot(command.result)
                    is Command.Barrier -> command.result.complete(Unit)
                }
            }
        }
        worker.invokeOnCompletion {
            synchronized(admissionLock) {
                commands.close()
                epoch = Any()
                mutableState.value = mutableState.value.copy(
                    entries = mutableState.value.entries.map(DownloadHistoryEntry::unconfirmPending),
                    loading = false,
                    writable = false,
                    warning = DownloadHistoryWarning.STOPPED,
                )
                while (true) {
                    when (val pending = commands.tryReceive().getOrNull() ?: break) {
                        is Command.Clear -> pending.result.complete(false)
                        is Command.Start -> pending.result.complete(null)
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

    /**
     * Call immediately before the original-file save attempt, never at picker launch. The caller
     * must take ownership of the result in a NonCancellable section, then check cancellation and
     * report that terminal outcome. Caller cancellation must not lose an admitted receipt.
     */
    suspend fun begin(
        request: DownloadHistoryRequest,
        filename: String,
        destination: DownloadHistoryDestination,
    ): DownloadHistoryReceipt? {
        val result = CompletableDeferred<DownloadHistoryReceipt?>()
        synchronized(admissionLock) {
            if (!owns(request)) {
                result.complete(null)
            } else {
                val receipt = DownloadHistoryReceipt(UUID.randomUUID().toString(), request)
                val entry = DownloadHistoryEntry(
                    receiptId = receipt.receiptId,
                    filename = sanitizeDownloadHistoryFilename(filename),
                    destination = destination,
                    startedAtMillis = nowMillis().coerceAtLeast(0L),
                    finishedAtMillis = null,
                    outcome = DownloadHistoryOutcome.IN_PROGRESS,
                )
                if (commands.trySend(Command.Start(receipt, entry, result)).isFailure) result.complete(null)
            }
        }
        return result.await()
    }

    /** First terminal result wins; an evicted/cleared/missing receipt is never inserted again. */
    fun recordFinished(receipt: DownloadHistoryReceipt?, outcome: DownloadHistoryOutcome, cleanupUnconfirmed: Boolean = false) {
        if (receipt == null || !outcome.isTerminal()) return
        synchronized(admissionLock) {
            if (!owns(receipt.request)) return
            commands.trySend(Command.Finish(receipt, outcome, nowMillis().coerceAtLeast(0L),
                cleanupUnconfirmed && (outcome == DownloadHistoryOutcome.FAILED || outcome == DownloadHistoryOutcome.CANCELLED)))
        }
    }

    /**
     * Success atomically replaces the snapshot, then changes the admission epoch. Old batch
     * tokens cannot admit later items or finish old receipts. Failure preserves the epoch and
     * records. Cancellation of the caller only stops waiting; the process-owned reset still runs.
     * True means an empty replacement was synced, closed and atomically installed. No guarantee
     * of recovery from hardware or filesystem power loss is implied.
     */
    suspend fun clear(): Boolean {
        val result = CompletableDeferred<Boolean>()
        synchronized(admissionLock) {
            if (commands.trySend(Command.Clear(result)).isFailure) result.complete(false)
        }
        return result.await()
    }

    /** Test barrier, also useful for orderly process-owned shutdown; transfers never await this. */
    internal suspend fun awaitIdle() {
        val result = CompletableDeferred<Unit>()
        synchronized(admissionLock) {
            if (commands.trySend(Command.Barrier(result)).isFailure) result.complete(Unit)
        }
        result.await()
    }

    private fun owns(request: DownloadHistoryRequest): Boolean =
        request.owner === owner && request.epoch === epoch

    private fun initialize() {
        mutableState.value = try {
            DownloadHistoryState(
                entries = storage.read().map(DownloadHistoryEntry::unconfirmPending),
                loading = false,
                writable = true,
            )
        } catch (_: Exception) {
            // Preserve unreadable input exactly. Only explicit clear may replace it.
            DownloadHistoryState(loading = false, warning = DownloadHistoryWarning.READ_FAILED)
        }
    }

    private fun start(command: Command.Start) {
        if (!mutableState.value.writable || !synchronized(admissionLock) { owns(command.receipt.request) }) {
            command.result.complete(null)
            return
        }
        replace((listOf(command.entry) + mutableState.value.entries).take(MAX_DOWNLOAD_HISTORY_ENTRIES))
        command.result.complete(command.receipt)
    }

    private fun finish(command: Command.Finish) {
        val current = mutableState.value
        if (!current.writable || !synchronized(admissionLock) { owns(command.receipt.request) }) return
        val index = current.entries.indexOfFirst { it.receiptId == command.receipt.receiptId }
        if (index < 0 || current.entries[index].outcome != DownloadHistoryOutcome.IN_PROGRESS) return
        val updated = current.entries.toMutableList()
        updated[index] = updated[index].copy(
            outcome = command.outcome,
            finishedAtMillis = command.finishedAtMillis,
            cleanupUnconfirmed = command.cleanupUnconfirmed,
        )
        replace(updated)
    }

    private fun replace(entries: List<DownloadHistoryEntry>) {
        val warning = try {
            storage.write(entries)
            null
        } catch (_: Exception) {
            DownloadHistoryWarning.WRITE_FAILED
        }
        // Retain the actual in-process outcome on I/O failure, with an explicit persistence warning.
        mutableState.value = DownloadHistoryState(entries, loading = false, writable = true, warning)
    }

    private fun clearSnapshot(result: CompletableDeferred<Boolean>) {
        try {
            storage.write(emptyList())
            synchronized(admissionLock) {
                epoch = Any()
                mutableState.value = DownloadHistoryState(loading = false, writable = true)
            }
            result.complete(true)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                warning = DownloadHistoryWarning.CLEAR_FAILED,
            )
            result.complete(false)
        }
    }

    private sealed interface Command {
        data class Start(
            val receipt: DownloadHistoryReceipt,
            val entry: DownloadHistoryEntry,
            val result: CompletableDeferred<DownloadHistoryReceipt?>,
        ) : Command
        data class Finish(
            val receipt: DownloadHistoryReceipt,
            val outcome: DownloadHistoryOutcome,
            val finishedAtMillis: Long,
            val cleanupUnconfirmed: Boolean,
        ) : Command
        data class Clear(val result: CompletableDeferred<Boolean>) : Command
        data class Barrier(val result: CompletableDeferred<Unit>) : Command
    }
}

internal const val MAX_DOWNLOAD_HISTORY_ENTRIES = 100
internal const val MAX_DOWNLOAD_HISTORY_FILENAME_LENGTH = 120

internal fun DownloadHistoryOutcome.isTerminal(): Boolean =
    this == DownloadHistoryOutcome.COMPLETED || this == DownloadHistoryOutcome.FAILED ||
        this == DownloadHistoryOutcome.CANCELLED

private fun DownloadHistoryEntry.unconfirmPending(): DownloadHistoryEntry =
    if (outcome == DownloadHistoryOutcome.IN_PROGRESS) copy(outcome = DownloadHistoryOutcome.UNCONFIRMED)
    else this

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
