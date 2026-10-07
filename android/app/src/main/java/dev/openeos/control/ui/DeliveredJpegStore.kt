package dev.openeos.control.ui

import android.net.Uri
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.isVideoMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Evidence captured from the original write, after close and validation, before publication. */
class PublishedGalleryOriginal internal constructor(
    internal val uriValue: String,
    val byteLength: Long,
    val sha256: String,
    val mimeType: String,
) {
    constructor(uri: Uri, byteLength: Long, sha256: String, mimeType: String) :
        this(uri.toString(), byteLength, sha256, mimeType)

    val uri: Uri get() = Uri.parse(uriValue)

    // The same synchronous publication notification cannot resurrect an entry after eviction
    // or clear. Keep its tiny delivery token with the notification, never in a growing index.
    internal val recordedId = AtomicReference<DeliveredJpegId?>(null)

    override fun toString(): String = "PublishedGalleryOriginal(byteLength=$byteLength, mimeType=$mimeType)"
}

/** Random identity of this published copy; it never derives from a URI or camera identifier. */
data class DeliveredJpegId(val value: String) {
    init {
        require(value.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid saved JPEG identifier." }
    }
}

/** Frozen local inputs for a handoff. This object is never persisted or encoded as a manifest. */
class DeliveredJpeg internal constructor(
    val id: DeliveredJpegId,
    private val uriValue: String,
    val item: CameraMediaItem,
    val camera: CameraInfo,
    val evidence: CameraImportOriginalEvidence,
    val publicationOrdinal: Long,
) {
    val uri: Uri get() = Uri.parse(uriValue)
    val row: DeliveredJpegRow get() = DeliveredJpegRow(
        id = id,
        filename = safeMediaFilename(item.name.substringAfterLast('/').substringAfterLast('\\')),
        cameraModel = safeMediaFilename(camera.model, "Canon EOS Camera"),
        captureTime = item.captureTime,
        byteLength = evidence.byteLength,
    )

    override fun toString(): String = "DeliveredJpeg(id=$id, publicationOrdinal=$publicationOrdinal)"
}

/** Display-only projection, with neither a local handle nor private source identifiers. */
data class DeliveredJpegRow(
    val id: DeliveredJpegId,
    val filename: String,
    val cameraModel: String,
    val captureTime: String?,
    val byteLength: Long,
)

data class DeliveredJpegListState internal constructor(
    val entries: List<DeliveredJpeg> = emptyList(),
    val generation: Long = 0L,
    val evictedCount: Long = 0L,
    val registrationUnavailable: Boolean = false,
) {
    val rows: List<DeliveredJpegRow> get() = Collections.unmodifiableList(entries.map { it.row })

    override fun toString(): String = "DeliveredJpegListState(count=${entries.size}, generation=$generation, " +
        "evictedCount=$evictedCount, registrationUnavailable=$registrationUnavailable)"
}

enum class DeliveredJpegSnapshotFailure { EMPTY_SELECTION, STALE_SELECTION }

class DeliveredJpegSnapshotException(val reason: DeliveredJpegSnapshotFailure) : IllegalStateException(
    when (reason) {
        DeliveredJpegSnapshotFailure.EMPTY_SELECTION -> "Select at least one saved JPEG."
        DeliveredJpegSnapshotFailure.STALE_SELECTION -> "The saved JPEG selection is no longer available."
    },
)

/**
 * A ViewModel-owned, in-memory publication list. It has no job, persistence, resolver, or camera
 * dependency. Synchronous operations serialize admission, clear, and bounded newest-first eviction.
 */
class DeliveredJpegStore(val capacity: Int = MAX_ENTRIES) {
    init {
        require(capacity in 1..MAX_ENTRIES) { "Saved JPEG capacity must be between 1 and $MAX_ENTRIES." }
    }

    private val entriesByUri = linkedMapOf<String, DeliveredJpeg>()
    private val mutableState = MutableStateFlow(DeliveredJpegListState())
    val state: StateFlow<DeliveredJpegListState> = mutableState.asStateFlow()
    private var publicationOrdinal = 0L

    /** Non-JPEG publications are deliberately ignored. Invalid JPEG evidence reports availability. */
    @Synchronized
    fun recordPublished(camera: CameraInfo, item: CameraMediaItem, evidence: PublishedGalleryOriginal): DeliveredJpegId? {
        if (item.isVideoMedia || galleryMimeType(item) != "image/jpeg" ||
            !evidence.mimeType.equals("image/jpeg", ignoreCase = true)
        ) return null
        evidence.recordedId.get()?.let { return it }
        if (!validEvidence(evidence)) {
            markRegistrationUnavailable()
            return null
        }
        entriesByUri[evidence.uriValue]?.let { existing ->
            evidence.recordedId.compareAndSet(null, existing.id)
            return existing.id
        }

        val id = DeliveredJpegId(UUID.randomUUID().toString())
        // One publication belongs to one retained owner. A stale callback carrying this exact
        // notification cannot enter a fresh store or restore an already cleared record.
        if (!evidence.recordedId.compareAndSet(null, id)) return evidence.recordedId.get()
        publicationOrdinal += 1
        val saved = DeliveredJpeg(
            id = id,
            uriValue = evidence.uriValue,
            item = CameraMediaItem(
                id = item.id,
                name = item.name,
                kind = item.kind,
                sizeBytes = item.sizeBytes,
                captureTime = item.captureTime,
                rotationDegrees = item.rotationDegrees,
                contentType = "image/jpeg",
                widthPixels = item.widthPixels,
                heightPixels = item.heightPixels,
            ),
            camera = CameraInfo(
                connected = camera.connected,
                model = camera.model,
                serial = camera.serial,
                api = camera.api,
                manufacturer = camera.manufacturer,
            ),
            evidence = CameraImportOriginalEvidence(evidence.byteLength, evidence.sha256.lowercase(Locale.ROOT)),
            publicationOrdinal = publicationOrdinal,
        )
        entriesByUri[evidence.uriValue] = saved
        val previous = mutableState.value
        val evicted = entriesByUri.size > capacity
        if (evicted) entriesByUri.remove(entriesByUri.keys.first())
        mutableState.value = previous.copy(
            entries = immutableEntries(),
            generation = next(previous.generation),
            evictedCount = if (evicted) next(previous.evictedCount) else previous.evictedCount,
        )
        return id
    }

    /** Clear references only. Admitted snapshots and Gallery originals remain owned by their callers. */
    @Synchronized
    fun clear() {
        entriesByUri.clear()
        mutableState.value = DeliveredJpegListState(generation = next(mutableState.value.generation))
    }

    /** Admit all IDs atomically or fail without admitting any; stable publication order is newest first. */
    @Synchronized
    fun snapshot(ids: Set<DeliveredJpegId>): List<DeliveredJpeg> {
        if (ids.isEmpty()) throw DeliveredJpegSnapshotException(DeliveredJpegSnapshotFailure.EMPTY_SELECTION)
        val selected = mutableState.value.entries.filter { it.id in ids }
        if (selected.size != ids.size) throw DeliveredJpegSnapshotException(DeliveredJpegSnapshotFailure.STALE_SELECTION)
        return Collections.unmodifiableList(selected)
    }

    /** Safe bounded notice for observer failures; exception text and source handles are never retained. */
    @Synchronized
    fun markRegistrationUnavailable() {
        val previous = mutableState.value
        if (!previous.registrationUnavailable) {
            mutableState.value = previous.copy(generation = next(previous.generation), registrationUnavailable = true)
        }
    }

    private fun immutableEntries(): List<DeliveredJpeg> = Collections.unmodifiableList(entriesByUri.values.reversed())

    private fun validEvidence(evidence: PublishedGalleryOriginal): Boolean =
        evidence.byteLength > 0 && evidence.sha256.matches(Regex("[a-fA-F0-9]{64}")) && runCatching {
            val uri = URI(evidence.uriValue)
            uri.scheme == "content" && !uri.authority.isNullOrBlank() && !uri.path.isNullOrBlank()
        }.getOrDefault(false)

    private fun next(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

    companion object {
        const val MAX_ENTRIES = 100
    }
}
