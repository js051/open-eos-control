package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaTransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SavedJpegProgress(
    val completedItems: Int,
    val totalItems: Int,
    val filename: String,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long? = null,
)
internal data class SavedJpegUiState(
    val rows: List<DeliveredJpegRow> = emptyList(),
    val selectedIds: Set<DeliveredJpegId> = emptySet(),
    val evictedCount: Long = 0L,
    val registrationUnavailable: Boolean = false,
    val progress: SavedJpegProgress? = null,
    val outcome: CameraImportHandoffOutcome? = null,
    val cleanupUnconfirmed: Boolean = false,
    val unavailableIds: Set<DeliveredJpegId> = emptySet(),
    val recheckingId: DeliveredJpegId? = null,
)

/** Local-only IO contract: there is deliberately no camera repository or fallback operation. */
internal interface SavedJpegHandoffBackend<S> {
    fun reserve(): CameraImportStagingReservation
    suspend fun prepare(
        selected: List<DeliveredJpeg>,
        reservation: CameraImportStagingReservation,
        onItem: (index: Int, total: Int, name: String) -> Unit,
        onProgress: (CameraMediaTransferProgress) -> Unit,
    ): S
    suspend fun cleanup(reservation: CameraImportStagingReservation): Boolean
}

/** Retained local selection and admission, independent of connection state and camera jobs. */
internal class SavedJpegHandoffController<S>(
    private val store: DeliveredJpegStore,
    private val owner: CameraImportHandoffOwner<S>,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SavedJpegUiState())
    val state: StateFlow<SavedJpegUiState> = mutableState.asStateFlow()
    private var activeToken: Long? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.state.collect { list ->
                val valid = list.entries.mapTo(hashSetOf()) { it.id }
                mutableState.update { current -> current.copy(
                    rows = list.rows, selectedIds = current.selectedIds.intersect(valid),
                    unavailableIds = current.unavailableIds.intersect(valid),
                    evictedCount = list.evictedCount, registrationUnavailable = list.registrationUnavailable,
                ) }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            owner.state.collect { shared ->
                val lease = shared.active?.takeIf { it.origin == CameraImportHandoffOrigin.SAVED_JPEG && it.token == activeToken }
                val result = shared.lastResult?.takeIf { it.origin == CameraImportHandoffOrigin.SAVED_JPEG && it.token == activeToken }
                if (lease != null) mutableState.update { current -> current.copy(
                    outcome = lease.outcome ?: current.outcome,
                    cleanupUnconfirmed = lease.cleanupUnconfirmed,
                    progress = current.progress.takeIf { lease.phase == CameraImportHandoffPhase.PREPARING },
                ) }
                else if (result != null) mutableState.update { current -> current.copy(
                    outcome = result.outcome, cleanupUnconfirmed = result.cleanupUnconfirmed,
                    progress = null, recheckingId = null,
                ) }
            }
        }
    }

    fun toggle(id: DeliveredJpegId, selected: Boolean) = synchronized(store) {
        val present = store.state.value.entries.any { it.id == id }
        mutableState.update { current ->
            if (selected && present && id !in current.unavailableIds) current.copy(selectedIds = current.selectedIds + id)
            else current.copy(selectedIds = current.selectedIds - id)
        }
    }

    fun clear() = synchronized(store) {
        store.clear()
        mutableState.update { it.copy(rows = emptyList(), selectedIds = emptySet(), unavailableIds = emptySet()) }
    }

    fun send(
        expectedSelection: Set<DeliveredJpegId>,
        backend: SavedJpegHandoffBackend<S>,
        automaticImportActive: Boolean,
        sereinAvailable: Boolean,
    ): Boolean = admit(expectedSelection, backend, automaticImportActive, sereinAvailable, recheckingId = null)

    fun recheck(id: DeliveredJpegId, backend: SavedJpegHandoffBackend<S>, automaticImportActive: Boolean): Boolean =
        admit(setOf(id), backend, automaticImportActive, sereinAvailable = true, recheckingId = id)

    fun cancel() { owner.cancelUnlaunched(CameraImportHandoffOrigin.SAVED_JPEG) }

    private fun admit(
        ids: Set<DeliveredJpegId>,
        backend: SavedJpegHandoffBackend<S>,
        automaticImportActive: Boolean,
        sereinAvailable: Boolean,
        recheckingId: DeliveredJpegId?,
    ): Boolean {
        if (automaticImportActive) return reject(CameraImportHandoffIssue.AUTOMATIC_IMPORT_ACTIVE)
        if (owner.state.value.busy) return reject(CameraImportHandoffIssue.HANDOFF_BUSY)
        if (!sereinAvailable) return reject(CameraImportHandoffIssue.SEREIN_UNAVAILABLE)
        if (recheckingId == null && ids.any { it in mutableState.value.unavailableIds }) {
            return reject(CameraImportHandoffIssue.SOURCE_UNAVAILABLE)
        }
        val snapshot = try { store.snapshot(ids.toSet()) } catch (failure: DeliveredJpegSnapshotException) {
            return reject(if (failure.reason == DeliveredJpegSnapshotFailure.EMPTY_SELECTION) {
                CameraImportHandoffIssue.EMPTY_SELECTION
            } else CameraImportHandoffIssue.STALE_SELECTION)
        }
        val reservation = try { backend.reserve() } catch (_: Exception) {
            return reject(CameraImportHandoffIssue.PREPARATION_FAILED)
        }
        val lease = owner.acquire(CameraImportHandoffOrigin.SAVED_JPEG, reservation, cleanup = backend::cleanup)
            ?: return reject(CameraImportHandoffIssue.HANDOFF_BUSY)
        activeToken = lease.token
        mutableState.update { it.copy(outcome = null, cleanupUnconfirmed = false, progress = null, recheckingId = recheckingId) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var currentId: DeliveredJpegId? = null
            try {
                val prepared = backend.prepare(snapshot, reservation,
                    onItem = { index, total, _ ->
                        if (owner.owns(lease.token, CameraImportHandoffPhase.PREPARING)) {
                            val row = snapshot.getOrNull(index)?.row
                            currentId = row?.id
                            mutableState.update { it.copy(progress = SavedJpegProgress(index, total, row?.filename.orEmpty(),
                                totalBytes = row?.byteLength)) }
                        }
                    },
                    onProgress = { progress ->
                        if (owner.owns(lease.token, CameraImportHandoffPhase.PREPARING)) mutableState.update { current ->
                            current.copy(progress = current.progress?.copy(bytesTransferred = progress.bytesTransferred,
                                totalBytes = progress.totalBytes))
                        }
                    },
                )
                if (recheckingId != null) {
                    if (owner.owns(lease.token, CameraImportHandoffPhase.PREPARING)) {
                        mutableState.update { it.copy(unavailableIds = it.unavailableIds - recheckingId) }
                        owner.finish(lease.token, CameraImportHandoffOutcome())
                    }
                } else owner.prepared(lease.token, prepared)
            } catch (cancelled: CancellationException) {
                owner.finish(lease.token, CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.CANCELLED))
                throw cancelled
            } catch (failure: Exception) {
                val issue = when (failure) {
                    is SavedJpegStagingSpaceException -> CameraImportHandoffIssue.INSUFFICIENT_SPACE
                    else -> if (currentId != null) CameraImportHandoffIssue.SOURCE_UNAVAILABLE else CameraImportHandoffIssue.PREPARATION_FAILED
                }
                if (owner.owns(lease.token, CameraImportHandoffPhase.PREPARING)) {
                    currentId?.takeIf { issue == CameraImportHandoffIssue.SOURCE_UNAVAILABLE }?.let { unavailable ->
                        synchronized(store) {
                            if (store.state.value.entries.any { it.id == unavailable }) {
                                mutableState.update { it.copy(unavailableIds = it.unavailableIds + unavailable,
                                    selectedIds = it.selectedIds - unavailable) }
                            }
                        }
                    }
                    owner.finish(lease.token, CameraImportHandoffOutcome(issue = issue))
                }
            }
        }
        owner.attachPreparation(lease.token, job)
        job.start()
        return true
    }

    private fun reject(issue: CameraImportHandoffIssue): Boolean {
        // A second tap must not replace feedback owned by a running transaction.
        if (activeToken == null || !owner.owns(requireNotNull(activeToken))) {
            mutableState.update { it.copy(outcome = CameraImportHandoffOutcome(issue = issue)) }
        }
        return false
    }
}
