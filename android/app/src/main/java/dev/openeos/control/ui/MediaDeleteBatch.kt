package dev.openeos.control.ui

import dev.openeos.control.data.MediaDeleteAttempt
import dev.openeos.control.data.MediaDeleteEvidence
import dev.openeos.control.data.MediaDeleteOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

const val MAX_MEDIA_DELETE_ITEMS = 1_000
private const val MAX_DELETE_HISTORY_BATCHES = 20
private const val MAX_DELETE_HISTORY_ROWS = 2_000

enum class MediaDeleteSource { CAMERA_CARD, ANDROID_PRIVATE, DESKTOP_HOST, UNKNOWN }
enum class MediaDeleteReason { NONE, PROTECTED, NOT_WRITABLE, UNLOADED, UNSUPPORTED, SOURCE_UNKNOWN, OWNER_CHANGED, CANCELLED, UNCONFIRMED }

/** Reference identity, never data equality or a reusable device name. */
data class MediaDeleteOwner(val connection: Any?, val generation: Long) {
    fun matches(connection: Any?, generation: Long) = this.connection === connection && this.generation == generation
}
data class MediaDeleteRequest(val id: String, val name: String, val source: MediaDeleteSource,
    val protected: Boolean? = null, val writable: Boolean? = null, val selectionToken: String? = null)
data class MediaDeleteRow(val request: MediaDeleteRequest, val outcome: MediaDeleteOutcome = MediaDeleteOutcome.NOT_STARTED,
    val reason: MediaDeleteReason = MediaDeleteReason.NONE, val inFlight: Boolean = false)
data class MediaDeleteReceipt(val attemptId: String, val owner: MediaDeleteOwner, val rows: List<MediaDeleteRow>,
    val running: Boolean = false) {
    val confirmed: Int get() = rows.count { it.outcome == MediaDeleteOutcome.CONFIRMED }
}

/** A stop closes admission; it cannot undo an already sent command or erase an ACK. */
class MediaDeleteBatchControl {
    @Volatile var cancelled: Boolean = false; private set
    @Volatile private var active: MediaDeleteAttempt? = null
    @Synchronized fun begin(): MediaDeleteAttempt = MediaDeleteAttempt().also {
        if (cancelled) it.cancel()
        active = it
    }
    @Synchronized fun cancel() { cancelled = true; active?.cancel() }
}

internal fun mediaDeletePreflight(request: MediaDeleteRequest, supported: Boolean, loadedId: String?,
    source: MediaDeleteSource?, protected: Boolean?, writable: Boolean?): MediaDeleteReason? = when {
    request.id.isEmpty() || loadedId != request.id -> MediaDeleteReason.UNLOADED
    !supported -> MediaDeleteReason.UNSUPPORTED
    request.source == MediaDeleteSource.UNKNOWN || source != request.source -> MediaDeleteReason.SOURCE_UNKNOWN
    request.protected == true || protected == true -> MediaDeleteReason.PROTECTED
    request.writable == false || writable == false -> MediaDeleteReason.NOT_WRITABLE
    else -> null
}

internal suspend fun executeMediaDeleteBatch(
    attemptId: String, owner: MediaDeleteOwner, requests: List<MediaDeleteRequest>, control: MediaDeleteBatchControl,
    isCurrent: () -> Boolean, preflight: (MediaDeleteRequest) -> MediaDeleteReason?,
    action: suspend (MediaDeleteRequest, MediaDeleteEvidence) -> Unit,
    onConfirmed: (MediaDeleteRequest) -> Unit = {}, onChanged: (MediaDeleteReceipt) -> Unit = {},
): MediaDeleteReceipt {
    // Exact first occurrence, then reject an oversized complete roster; never truncate.
    val ids = mediaDeleteRoster(requests.map { it.id })
    val unique = requests.distinctBy { it.id }
    check(unique.map { it.id } == ids)
    val rows = unique.map { MediaDeleteRow(it) }.toMutableList()
    fun publish(running: Boolean) = MediaDeleteReceipt(attemptId, owner, rows.toList(), running).also(onChanged)
    try {
        publish(true)
        for (index in rows.indices) {
            coroutineContext.ensureActive()
            if (control.cancelled || !isCurrent()) break
            val request = rows[index].request
            val veto = preflight(request)
            if (veto != null) {
                rows[index] = rows[index].copy(outcome = MediaDeleteOutcome.SKIPPED, reason = veto)
                publish(true)
                continue
            }
            val attempt = control.begin()
            rows[index] = rows[index].copy(inFlight = true)
            publish(true)
            try {
                val context = coroutineContext
                action(request, MediaDeleteEvidence(
                    beforeDispatch = {
                        context.ensureActive()
                        if (!isCurrent()) throw CancellationException("Deletion owner changed.")
                        // Re-check current protection/source at the transport boundary.
                        check(preflight(request) == null) { "Deletion eligibility changed before dispatch." }
                        attempt.beforeDispatch()
                    },
                    onConfirmed = attempt::confirm,
                    onRejected = attempt::reject,
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Classification comes only from transport evidence, never the exception text.
            } finally {
                val outcome = attempt.finish()
                rows[index] = rows[index].copy(outcome = outcome, inFlight = false,
                    reason = when {
                        outcome == MediaDeleteOutcome.CONFIRMED || outcome == MediaDeleteOutcome.REJECTED -> MediaDeleteReason.NONE
                        !isCurrent() -> MediaDeleteReason.OWNER_CHANGED
                        control.cancelled -> MediaDeleteReason.CANCELLED
                        else -> MediaDeleteReason.UNCONFIRMED
                    })
                if (outcome == MediaDeleteOutcome.CONFIRMED) onConfirmed(request)
                publish(true)
            }
            // A typed refusal may continue. Uncertain or pre-dispatch failure requires manual review.
            if (rows[index].outcome !in setOf(MediaDeleteOutcome.CONFIRMED, MediaDeleteOutcome.REJECTED)) break
        }
    } finally {
        rows.indices.forEach { index -> if (rows[index].outcome == MediaDeleteOutcome.NOT_STARTED) {
            rows[index] = rows[index].copy(reason = when {
                !isCurrent() -> MediaDeleteReason.OWNER_CHANGED
                control.cancelled -> MediaDeleteReason.CANCELLED
                else -> MediaDeleteReason.UNCONFIRMED
            })
        } }
        publish(false)
    }
    return MediaDeleteReceipt(attemptId, owner, rows.toList())
}

/** Bounded informational history; old owners never regain command authority. */
internal fun retainMediaDeleteReceipt(history: List<MediaDeleteReceipt>, receipt: MediaDeleteReceipt): List<MediaDeleteReceipt> {
    val candidates = listOf(receipt) + history.filterNot { it.attemptId == receipt.attemptId }
    var count = 0
    return candidates.take(MAX_DELETE_HISTORY_BATCHES).takeWhile { count += it.rows.size; count <= MAX_DELETE_HISTORY_ROWS }
}

/** Per-ID selection epochs protect a deselected/reselected row and additions during an attempt. */
internal fun consumeConfirmedDeleteSelection(tokens: Map<String, String>, receipt: MediaDeleteReceipt,
    connection: Any?, generation: Long): Map<String, String> {
    if (!receipt.owner.matches(connection, generation)) return tokens
    val consumed = receipt.rows.filter { it.outcome == MediaDeleteOutcome.CONFIRMED &&
        it.request.selectionToken != null && tokens[it.request.id] == it.request.selectionToken }.map { it.request.id }.toSet()
    return tokens.filterKeys { it !in consumed }
}

internal fun mediaDeleteRoster(ids: List<String>): List<String> {
    val unique = ids.distinct()
    require(unique.isNotEmpty() && unique.size <= MAX_MEDIA_DELETE_ITEMS && unique.none(String::isEmpty))
    return unique
}

internal fun canAdmitMediaDeletion(owner: MediaDeleteOwner, connection: Any?, generation: Long,
    confirmed: Boolean, consumed: Boolean, busy: Boolean): Boolean =
    confirmed && !consumed && !busy && owner.matches(connection, generation)
