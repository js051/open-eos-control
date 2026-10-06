package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import java.util.Locale

enum class ForegroundImportPhase { OFF, BASELINING, WATCHING, WAITING, SAVING, STOPPING, STOPPED }

enum class ForegroundImportStopReason {
    USER, BACKGROUND, SESSION_CHANGED, UNSUPPORTED, INCOMPLETE_BASELINE, BASELINE_LIMIT, BASELINE_CHANGED, INCOMPLETE_SCAN,
    QUEUE_LIMIT, IDENTITY_LIMIT, INVALID_SOURCE, READ_FAILED, TRANSFER_FAILED, CLEANUP_UNCONFIRMED,
}

data class ForegroundImportLimits(
    val baselineItems: Int = 2_000,
    val knownItems: Int = 4_096,
    val queuedItems: Int = 20,
    val stableMillis: Long = 1_000L,
) {
    init {
        require(baselineItems > 0 && knownItems >= baselineItems)
        require(queuedItems > 0 && stableMillis > 0 && knownItems < Int.MAX_VALUE)
    }
}

data class ForegroundJpegImportStatus(
    val phase: ForegroundImportPhase = ForegroundImportPhase.OFF,
    val stopReason: ForegroundImportStopReason? = null,
    val baselineCount: Int = 0,
    val knownCount: Int = 0,
    val pendingCount: Int = 0,
    val completedCount: Int = 0,
    val discardedCount: Int = 0,
    val activeName: String? = null,
    val discoveryIncomplete: Boolean = false,
    val limits: ForegroundImportLimits = ForegroundImportLimits(),
)

/**
 * One foreground opt-in owns one policy instance. IDs prove only that an item was absent from
 * the complete enable-time inventory; neither timestamps nor a shutter ACK prove shot identity.
 * This class performs no I/O. Its owner must serialize calls and join output cleanup before
 * calling finishStop or admitting a replacement session.
 */
internal class ForegroundJpegImportPolicy(private val limits: ForegroundImportLimits = ForegroundImportLimits()) {
    private enum class Lifecycle { BASELINING, ARMED, STOPPING, STOPPED }
    private data class Candidate(
        var item: CameraMediaItem,
        var stableSince: Long,
        var observations: Int = 1,
        var stable: Boolean = false,
        var present: Boolean = true,
    )

    private var lifecycle = Lifecycle.BASELINING
    private var reason: ForegroundImportStopReason? = null
    private val knownIds = linkedSetOf<String>()
    private val pending = linkedMapOf<String, Candidate>()
    private var active: CameraMediaItem? = null
    private var baselineCount = 0
    private var completedCount = 0
    private var discardedCount = 0
    private var discoveryIncomplete = false

    val status: ForegroundJpegImportStatus
        get() = ForegroundJpegImportStatus(
            phase = when (lifecycle) {
                Lifecycle.BASELINING -> ForegroundImportPhase.BASELINING
                Lifecycle.STOPPING -> ForegroundImportPhase.STOPPING
                Lifecycle.STOPPED -> ForegroundImportPhase.STOPPED
                Lifecycle.ARMED -> when {
                    active != null -> ForegroundImportPhase.SAVING
                    pending.isNotEmpty() -> ForegroundImportPhase.WAITING
                    else -> ForegroundImportPhase.WATCHING
                }
            },
            stopReason = reason,
            baselineCount = baselineCount,
            knownCount = knownIds.size,
            pendingCount = pending.size,
            completedCount = completedCount,
            discardedCount = discardedCount,
            activeName = active?.name,
            discoveryIncomplete = discoveryIncomplete,
            limits = limits,
        )

    fun acceptBaseline(items: List<CameraMediaItem>, complete: Boolean) {
        if (lifecycle != Lifecycle.BASELINING) return
        if (items.size > limits.baselineItems) return requestStop(ForegroundImportStopReason.BASELINE_LIMIT)
        if (!complete) return requestStop(ForegroundImportStopReason.INCOMPLETE_BASELINE)
        val unique = validatedItems(items) ?: return requestStop(ForegroundImportStopReason.INVALID_SOURCE)
        knownIds.addAll(unique.map { it.id })
        baselineCount = knownIds.size
        lifecycle = Lifecycle.ARMED
    }

    fun observe(items: List<CameraMediaItem>, hasMore: Boolean, observedAtMillis: Long) {
        observeSnapshot(items, complete = !hasMore, observedAtMillis, metadataFresh = true)
    }

    /** ID enumeration is never evidence that a cached size is freshly stable. */
    fun observeInventory(items: List<CameraMediaItem>, complete: Boolean, observedAtMillis: Long) {
        observeSnapshot(items, complete, observedAtMillis, metadataFresh = false)
    }

    private fun observeSnapshot(items: List<CameraMediaItem>, complete: Boolean, observedAtMillis: Long, metadataFresh: Boolean) {
        if (lifecycle != Lifecycle.ARMED) return
        require(observedAtMillis >= 0L)
        discoveryIncomplete = !complete
        if (items.size > limits.knownItems) return requestStop(ForegroundImportStopReason.IDENTITY_LIMIT)
        if (!complete) return requestStop(ForegroundImportStopReason.INCOMPLETE_SCAN)
        val unique = validatedItems(items) ?: return requestStop(ForegroundImportStopReason.INVALID_SOURCE)
        val newItems = unique.filter { it.id !in knownIds }
        if (knownIds.size + newItems.size > limits.knownItems) {
            return requestStop(ForegroundImportStopReason.IDENTITY_LIMIT)
        }
        val newJpegs = newItems.filter(::isJpegImportCandidate)
        if (pending.size + (if (active == null) 0 else 1) + newJpegs.size > limits.queuedItems) {
            return requestStop(ForegroundImportStopReason.QUEUE_LIMIT)
        }
        // Validate the entire snapshot before admitting any part of it.
        if (unique.any { item -> pending[item.id]?.let {
                it.item.name != item.name || !isJpegImportCandidate(item)
            } == true }) return requestStop(ForegroundImportStopReason.INVALID_SOURCE)
        knownIds.addAll(newItems.map { it.id })
        pending.values.forEach { it.present = false }
        for (item in unique) {
            val candidate = pending[item.id]
            if (candidate != null) {
                candidate.present = true
                if (metadataFresh) updateMetadata(candidate, item, observedAtMillis)
            } else if (item in newJpegs) {
                pending[item.id] = Candidate(
                    if (metadataFresh) item else item.copy(sizeBytes = null, contentType = null),
                    observedAtMillis,
                    observations = if (metadataFresh) 1 else 0,
                )
            }
        }
    }

    fun pendingMetadataItems(): List<CameraMediaItem> = if (lifecycle == Lifecycle.ARMED) {
        pending.values.filter { it.present }.map { it.item }
    } else emptyList()

    fun acceptMetadata(requested: CameraMediaItem, returned: CameraMediaItem, observedAtMillis: Long) {
        if (lifecycle != Lifecycle.ARMED) return
        require(observedAtMillis >= 0L)
        val candidate = pending[requested.id] ?: return
        if (requested.id != returned.id || candidate.item.name != returned.name || !isJpegImportCandidate(returned)) {
            return requestStop(ForegroundImportStopReason.INVALID_SOURCE)
        }
        updateMetadata(candidate, returned, observedAtMillis)
    }

    private fun updateMetadata(candidate: Candidate, item: CameraMediaItem, observedAtMillis: Long) {
        if (candidate.observations == 0 || candidate.item.sizeBytes != item.sizeBytes || candidate.item.contentType != item.contentType) {
            candidate.stableSince = observedAtMillis
            candidate.observations = 1
            candidate.stable = false
        } else {
            candidate.observations += 1
            candidate.stable = item.sizeBytes?.let { it > 0L } == true &&
                candidate.observations >= 2 && observedAtMillis - candidate.stableSince >= limits.stableMillis
        }
        candidate.item = item
    }

    fun claimNext(): CameraMediaItem? {
        if (lifecycle != Lifecycle.ARMED || active != null) return null
        val candidate = pending.values.firstOrNull { it.present && it.stable } ?: return null
        pending.remove(candidate.item.id)
        active = candidate.item
        return active
    }

    fun finishTransfer(published: Boolean, failed: Boolean = false, cleanupConfirmed: Boolean = true) {
        check(active != null) { "No foreground-import output is owned." }
        active = null
        if (published) {
            // A finalized Gallery original remains successful even if Stop wins the return race.
            completedCount += 1
        } else if (!cleanupConfirmed) {
            requestStop(ForegroundImportStopReason.CLEANUP_UNCONFIRMED)
        } else if (failed || lifecycle != Lifecycle.STOPPING) {
            requestStop(ForegroundImportStopReason.TRANSFER_FAILED)
        }
    }

    fun requestStop(stopReason: ForegroundImportStopReason) {
        if (lifecycle == Lifecycle.STOPPED) return
        if (reason == null || stopReason == ForegroundImportStopReason.CLEANUP_UNCONFIRMED) reason = stopReason
        discardedCount += pending.size
        pending.clear()
        lifecycle = Lifecycle.STOPPING
    }

    /** Only the operation owner may call this after all pending I/O and cleanup have ended. */
    fun finishStop(): Boolean {
        if (lifecycle != Lifecycle.STOPPING || active != null) return false
        lifecycle = Lifecycle.STOPPED
        return true
    }

    private fun validatedItems(items: List<CameraMediaItem>): List<CameraMediaItem>? {
        if (items.any { it.id.isBlank() || it.name.isBlank() }) return null
        val groups = items.groupBy { it.id }
        if (groups.values.any { versions -> versions.distinct().size != 1 }) return null
        return groups.values.map { it.first() }
    }
}

internal fun isJpegImportCandidate(item: CameraMediaItem): Boolean =
    !item.isVideo && item.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("jpg", "jpeg") &&
        (item.contentType.isNullOrBlank() || item.contentType.substringBefore(';').trim().equals("image/jpeg", ignoreCase = true))
