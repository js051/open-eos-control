package dev.openeos.control.ui

import dev.openeos.control.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** Reference identity plus epoch. Same model/address after reconnect is a different owner. */
class CaptureConfigurationOwner(val connection: Any?, val generation: Long) {
    fun matches(connection: Any?, generation: Long): Boolean = this.connection === connection && this.generation == generation
}

data class CaptureConfigurationDraft(
    val owner: CaptureConfigurationOwner,
    val token: String,
    val revision: Long,
    val request: CaptureConfigurationRequest,
)

data class CaptureConfigurationReceipt(
    val id: String,
    val draft: CaptureConfigurationDraft,
    val origin: String,
    val evidence: CaptureConfigurationSnapshot,
    val running: Boolean = true,
    val recovering: Boolean = false,
    val cancelled: Boolean = false,
)

/** Both UI and synthetic tests execute this same one-write / explicit-read state machine. */
class CaptureConfigurationAttempt(
    val draft: CaptureConfigurationDraft,
    private val access: CaptureConfigurationAccess,
    private val owns: () -> Boolean,
    private val publish: (CaptureConfigurationReceipt) -> Unit,
) {
    val id: String = java.util.UUID.randomUUID().toString()
    private var receipt = CaptureConfigurationReceipt(id, draft, access.origin, CaptureConfigurationSnapshot(draft.request))
    private val evidence = CaptureConfigurationEvidence(draft.request, checkOwner = {
        if (!owns()) throw CancellationException("Capture configuration owner changed.")
    }, onChanged = { snapshot -> update { it.copy(evidence = snapshot) } })
    private var started = false
    private var reading = false
    @Synchronized fun snapshot(): CaptureConfigurationReceipt = receipt
    @Synchronized private fun update(change: (CaptureConfigurationReceipt) -> CaptureConfigurationReceipt) {
        receipt = change(receipt)
        publish(receipt)
    }
    fun cancel() {
        evidence.cancel()
        update { it.copy(cancelled = true) }
    }
    fun finishBeforeStart() {
        synchronized(this) { if (started) return; started = true }
        update { it.copy(running = false, cancelled = true) }
    }
    suspend fun run(): CameraFileNaming? {
        synchronized(this) { check(!started); started = true }
        return try {
            coroutineContext.ensureActive()
            if (!owns()) throw CancellationException("Capture configuration owner changed.")
            val result = access.write(draft.request, evidence)
            coroutineContext.ensureActive()
            result
        } catch (failure: Exception) {
            if (evidence.snapshot().writeEvidence == CaptureConfigurationWriteEvidence.ACKNOWLEDGED &&
                evidence.snapshot().readbackEvidence == CaptureConfigurationReadEvidence.NOT_READ) evidence.unavailable()
            if (failure is CancellationException) cancel()
            // Evidence owns the UI outcome; generic errors must not suggest resending the write.
            null
        } finally { update { it.copy(running = false) } }
    }
    /** Explicit button only. Never calls write; no retries, cached reads or whole-card enumeration. */
    suspend fun recover(): CaptureConfigurationReadback? {
        synchronized(this) {
            if (!canRecoverCaptureConfiguration(owns(), receipt.running, reading, true)) return null
            reading = true
        }
        update { it.copy(recovering = true) }
        return try {
            coroutineContext.ensureActive()
            if (!owns()) throw CancellationException("Capture configuration owner changed.")
            val readback = withTimeout(15_000L) { access.read(draft.request.operation) }
            // A cancelled read invocation cannot publish settings, even if a non-cancellable
            // transport returns late. A later explicit read has its own live invocation.
            coroutineContext.ensureActive()
            evidence.observe(readback)
            readback
        } catch (failure: Exception) {
            evidence.unavailable()
            null
        } finally {
            synchronized(this) { reading = false }
            update { it.copy(recovering = false) }
        }
    }
}

internal fun captureConfigurationHistory(history: List<CaptureConfigurationReceipt>, receipt: CaptureConfigurationReceipt): List<CaptureConfigurationReceipt> =
    if (history.any { it.id == receipt.id }) history.map { if (it.id == receipt.id) receipt else it }
    else (listOf(receipt) + history).take(20)

internal fun canPublishCaptureConfiguration(receipt: CaptureConfigurationReceipt, activeId: String?, connection: Any?, generation: Long, retired: Boolean = false): Boolean =
    !retired && receipt.id == activeId && receipt.draft.owner.matches(connection, generation)

internal fun canConsumeCaptureConfigurationDraft(receipt: CaptureConfigurationReceipt, token: String, revision: Long,
    connection: Any?, generation: Long): Boolean = !receipt.running && !receipt.cancelled &&
    receipt.draft.owner.matches(connection, generation) && receipt.draft.token == token && receipt.draft.revision == revision &&
    receipt.evidence.writeEvidence == CaptureConfigurationWriteEvidence.ACKNOWLEDGED &&
    receipt.evidence.readbackEvidence == CaptureConfigurationReadEvidence.MATCHED

/** ACK history survives cancellation, but a cancelled write cannot replace the current summary. */
internal fun captureConfigurationDirectorySummary(receipt: CaptureConfigurationReceipt, previous: String?): String? =
    if (!receipt.cancelled && receipt.draft.request.operation == CaptureConfigurationOperation.CREATE_DIRECTORY &&
        receipt.evidence.writeEvidence == CaptureConfigurationWriteEvidence.ACKNOWLEDGED)
        receipt.evidence.acknowledgedValue else previous

internal fun canRecoverCaptureConfiguration(sameOwner: Boolean, writeActive: Boolean, readActive: Boolean, hasRecord: Boolean): Boolean =
    sameOwner && !writeActive && !readActive && hasRecord

/** One submitted revision per editor; only a user edit or explicit new editor token admits another write. */
internal class CaptureConfigurationDraftLedger {
    private val consumed = linkedSetOf<Pair<String, Long>>()
    @Synchronized fun claim(draft: CaptureConfigurationDraft): Boolean {
        if (!consumed.add(draft.token to draft.revision)) return false
        while (consumed.size > 32) consumed.remove(consumed.first())
        return true
    }
}

/** Only a fresh validated same-owner selection replaces directory choices. Other controls stay unchanged. */
internal fun mergeCaptureConfigurationDirectorySettings(settings: List<CameraSettingControl>,
    readback: CaptureConfigurationReadback, sameOwner: Boolean): List<CameraSettingControl> {
    if (!sameOwner) return settings
    val selected = validCaptureDirectorySelection(readback.directorySelection, readback.directoryValues) ?: return settings
    val existing = settings.firstOrNull { it.key == "directoryselection" }
    val replacement = existing?.copy(value = selected, values = readback.directoryValues.toList())
        ?: CameraSettingControl("directoryselection", "Capture directory", selected, readback.directoryValues.toList())
    return if (existing == null) settings + replacement else settings.map { if (it.key == "directoryselection") replacement else it }
}
