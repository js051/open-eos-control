package dev.openeos.control.ui

import dev.openeos.control.data.MediaMetadataReadback
import dev.openeos.control.data.MediaMetadataTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** This in-memory receipt is intentionally separate from download continuation/worklists. */
enum class MediaMetadataOutcome { NOT_STARTED, IN_FLIGHT, CONFIRMED, UNCERTAIN, FAILED_NOT_SENT }
enum class MediaMetadataReason { NONE, NOT_WRITABLE, UNLOADED, UNKNOWN_METADATA, UNSUPPORTED, CANCELLED, OWNER_CHANGED, READBACK_UNCONFIRMED }

data class MediaMetadataRequest(val id: String, val name: String, val target: MediaMetadataTarget)
data class MediaMetadataRow(
    val request: MediaMetadataRequest,
    val outcome: MediaMetadataOutcome = MediaMetadataOutcome.NOT_STARTED,
    val reason: MediaMetadataReason = MediaMetadataReason.NONE,
)
data class MediaMetadataBatchResult(val rows: List<MediaMetadataRow>, val running: Boolean = false) {
    val confirmed: Int get() = rows.count { it.outcome == MediaMetadataOutcome.CONFIRMED }
    val inFlight: Int get() = rows.count { it.outcome == MediaMetadataOutcome.IN_FLIGHT }
    val uncertain: Int get() = rows.count { it.outcome == MediaMetadataOutcome.UNCERTAIN }
    val failedNotSent: Int get() = rows.count { it.outcome == MediaMetadataOutcome.FAILED_NOT_SENT }
    val notStarted: Int get() = rows.count { it.outcome == MediaMetadataOutcome.NOT_STARTED }
}

internal const val MAX_MEDIA_METADATA_BATCH_ITEMS = 1_000

/** One write per frozen exact ID. Exceptions after entering action are conservatively uncertain. */
internal suspend fun <T> executeMediaMetadataBatch(
    requests: List<MediaMetadataRequest>,
    isCurrent: () -> Boolean,
    preflight: (MediaMetadataRequest) -> MediaMetadataReason?,
    action: suspend (MediaMetadataRequest) -> T,
    readback: (T) -> MediaMetadataReadback,
    onConfirmed: (T) -> Unit = {},
    onChanged: (MediaMetadataBatchResult) -> Unit = {},
): MediaMetadataBatchResult {
    val unique = requests.distinctBy { it.id }
    require(unique.isNotEmpty() && unique.size <= MAX_MEDIA_METADATA_BATCH_ITEMS) { "Select at most $MAX_MEDIA_METADATA_BATCH_ITEMS items for one metadata batch." }
    val rows = unique.map { MediaMetadataRow(it) }.toMutableList()
    fun publish(running: Boolean) = MediaMetadataBatchResult(rows.toList(), running).also(onChanged)
    publish(true)
    var activeIndex: Int? = null
    try {
        for (index in rows.indices) {
            coroutineContext.ensureActive()
            if (!isCurrent()) break
            val request = rows[index].request
            val rejection = if (request.id.isBlank()) MediaMetadataReason.UNLOADED else preflight(request)
            coroutineContext.ensureActive()
            if (!isCurrent()) break
            if (rejection != null) {
                rows[index] = rows[index].copy(outcome =
                    if (rejection == MediaMetadataReason.UNLOADED || rejection == MediaMetadataReason.UNKNOWN_METADATA)
                        MediaMetadataOutcome.NOT_STARTED else MediaMetadataOutcome.FAILED_NOT_SENT,
                    reason = rejection)
                publish(true)
                continue
            }
            activeIndex = index
            rows[index] = rows[index].copy(outcome = MediaMetadataOutcome.IN_FLIGHT,
                reason = MediaMetadataReason.READBACK_UNCONFIRMED)
            publish(true)
            var dispatched = false
            try {
                // A final synchronous owner/cancellation fence precedes the only mutation call.
                coroutineContext.ensureActive()
                if (!isCurrent()) {
                    rows[index] = rows[index].copy(outcome = MediaMetadataOutcome.NOT_STARTED,
                        reason = MediaMetadataReason.OWNER_CHANGED)
                    break
                }
                dispatched = true
                val updated = action(request)
                if (!isCurrent()) {
                    rows[index] = rows[index].copy(outcome = MediaMetadataOutcome.UNCERTAIN, reason = MediaMetadataReason.OWNER_CHANGED)
                    break
                }
                if (!request.target.matches(request.id, readback(updated))) {
                    rows[index] = rows[index].copy(outcome = MediaMetadataOutcome.UNCERTAIN)
                    break
                }
                rows[index] = rows[index].copy(outcome = MediaMetadataOutcome.CONFIRMED, reason = MediaMetadataReason.NONE)
                onConfirmed(updated)
                activeIndex = null
                publish(true)
            } catch (cancelled: CancellationException) {
                rows[index] = rows[index].copy(
                    outcome = if (dispatched) MediaMetadataOutcome.UNCERTAIN else MediaMetadataOutcome.NOT_STARTED,
                    reason = MediaMetadataReason.CANCELLED)
                throw cancelled
            } catch (_: Exception) {
                if (rows[index].outcome == MediaMetadataOutcome.IN_FLIGHT) rows[index] = rows[index].copy(outcome = MediaMetadataOutcome.UNCERTAIN)
                // No automatic retry and no subsequent mutation after an uncertain outcome.
                break
            }
        }
    } catch (cancelled: CancellationException) {
        activeIndex?.let { index ->
            if (rows[index].outcome == MediaMetadataOutcome.UNCERTAIN) {
                rows[index] = rows[index].copy(reason = MediaMetadataReason.CANCELLED)
            }
        }
        throw cancelled
    } finally {
        publish(false) // Preserves earlier confirmations, including during coroutine cancellation.
    }
    return MediaMetadataBatchResult(rows.toList())
}

internal data class MediaMetadataRoster(val ids: List<String>, val eligibleIds: List<String>, val hiddenCount: Int, val unloadedCount: Int)
internal fun mediaMetadataRoster(selectedIds: List<String>, loadedIds: Set<String>, visibleIds: Set<String>): MediaMetadataRoster {
    val ids = selectedIds.distinct()
    return MediaMetadataRoster(ids, ids.filter { it in loadedIds },
        ids.count { it in loadedIds && it !in visibleIds }, ids.count { it !in loadedIds })
}

/** Eligibility uses the current loaded exact-ID row; the selection snapshot supplies identity only. */
internal fun mediaMetadataPreflight(
    field: dev.openeos.control.data.MediaMetadataField,
    supported: Boolean,
    requestedId: String,
    loadedId: String?,
    archived: Boolean?,
    writable: Boolean?,
): MediaMetadataReason? = when {
    !supported -> MediaMetadataReason.UNSUPPORTED
    requestedId.isBlank() || loadedId != requestedId -> MediaMetadataReason.UNLOADED
    writable == false -> MediaMetadataReason.NOT_WRITABLE
    field == dev.openeos.control.data.MediaMetadataField.ARCHIVE && archived == null -> MediaMetadataReason.UNKNOWN_METADATA
    else -> null
}

/** Reference identity plus session generation; a delayed old-sheet callback cannot target a new camera. */
data class MediaMetadataOwner(val connection: Any?, val generation: Long) {
    fun matches(connection: Any?, generation: Long): Boolean =
        this.connection === connection && this.generation == generation
}

internal fun MediaMetadataBatchResult.retiredMetadataReceipt(): MediaMetadataBatchResult = copy(
    running = false,
    rows = rows.map { row ->
        if (row.outcome == MediaMetadataOutcome.IN_FLIGHT) row.copy(
            outcome = MediaMetadataOutcome.UNCERTAIN, reason = MediaMetadataReason.OWNER_CHANGED)
        else row
    },
)
