package dev.openeos.control.ui

import dev.openeos.control.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

class ShootingSettingOwner(val connection: Any?, val generation: Long) {
    fun matches(connection: Any?, generation: Long) = this.connection === connection && this.generation == generation
}
data class ShootingSettingDraft(val owner: ShootingSettingOwner, val token: String, val revision: Long,
    val request: ShootingSettingRequest)
data class ShootingSettingReceipt(val id: String, val draft: ShootingSettingDraft, val origin: String,
    val evidence: ShootingSettingSnapshot, val running: Boolean = true, val recovering: Boolean = false,
    val cancelled: Boolean = false)

/** One explicit intent admits one write and at most one automatic, targeted read. */
class ShootingSettingAttempt(
    val draft: ShootingSettingDraft,
    private val access: ShootingSettingAccess,
    private val owns: () -> Boolean,
    private val readTimeoutMillis: Long = 15_000L,
    private val publish: (ShootingSettingReceipt) -> Unit,
) {
    val id = java.util.UUID.randomUUID().toString()
    private var receipt = ShootingSettingReceipt(id, draft, access.origin, ShootingSettingSnapshot(draft.request))
    private val evidence = ShootingSettingEvidence(draft.request, { requireOwner() }) { snapshot ->
        update { it.copy(evidence = snapshot) }
    }
    private var started = false
    private var reading = false
    @Synchronized fun snapshot(): ShootingSettingReceipt = receipt
    @Synchronized private fun update(change: (ShootingSettingReceipt) -> ShootingSettingReceipt) {
        receipt = change(receipt)
        publish(receipt)
    }
    private fun requireOwner() { if (!owns()) throw CancellationException("Setting owner changed.") }
    fun cancel() { evidence.cancel(); update { it.copy(cancelled = true) } }
    fun finishBeforeStart() {
        synchronized(this) { if (started) return; started = true }
        update { it.copy(running = false, cancelled = true) }
    }
    suspend fun run(): ShootingSettingObservation? {
        synchronized(this) { check(!started); started = true }
        return try {
            coroutineContext.ensureActive(); requireOwner()
            access.write(draft.request, evidence)
            coroutineContext.ensureActive(); requireOwner()
            if (snapshot().cancelled) throw CancellationException("Setting attempt cancelled.")
            // Bridge's single outcome response already includes its one fresh read attempt.
            val snapshot = evidence.snapshot()
            if (snapshot.readbackEvidence != ShootingSettingReadEvidence.NOT_READ) {
                snapshot.observedValue?.let { ShootingSettingObservation(draft.request.key, it) }
            } else {
                // A no-op may legitimately leave NOT_SENT; do not fabricate a command response.
                readOnce()
            }
        } catch (failure: Exception) {
            if (evidence.snapshot().writeEvidence != ShootingSettingWriteEvidence.NOT_SENT &&
                evidence.snapshot().readbackEvidence == ShootingSettingReadEvidence.NOT_READ) evidence.unavailable()
            if (failure is CancellationException) cancel()
            null
        } finally { update { it.copy(running = false) } }
    }
    private suspend fun readOnce(): ShootingSettingObservation {
        val value = withTimeout(readTimeoutMillis) { access.read(draft.request) }
        coroutineContext.ensureActive(); requireOwner()
        evidence.observe(value)
        return value
    }
    /** Explicit invocation, one fresh scoped read, no fallback write, no automatic retry. */
    suspend fun recover(): ShootingSettingObservation? {
        synchronized(this) {
            if (!canRecoverShootingSetting(owns(), receipt.running, reading, true)) return null
            reading = true
        }
        update { it.copy(recovering = true) }
        return try {
            coroutineContext.ensureActive(); requireOwner()
            readOnce()
        } catch (_: Exception) { evidence.unavailable(); null }
        finally { synchronized(this) { reading = false }; update { it.copy(recovering = false) } }
    }
}

internal fun canRecoverShootingSetting(sameOwner: Boolean, writeActive: Boolean, readActive: Boolean,
    hasRecord: Boolean): Boolean = sameOwner && !writeActive && !readActive && hasRecord
internal fun canPublishShootingSetting(receipt: ShootingSettingReceipt, activeId: String?, connection: Any?,
    generation: Long): Boolean = !receipt.cancelled && receipt.id == activeId && receipt.draft.owner.matches(connection, generation)
internal fun canConsumeShootingSettingDraft(receipt: ShootingSettingReceipt, token: String, revision: Long,
    connection: Any?, generation: Long): Boolean = !receipt.running && !receipt.cancelled &&
    receipt.draft.owner.matches(connection, generation) && receipt.draft.token == token && receipt.draft.revision == revision &&
    receipt.evidence.writeEvidence == ShootingSettingWriteEvidence.RESPONSE_RECEIVED &&
    receipt.evidence.readbackEvidence == ShootingSettingReadEvidence.MATCHED
internal fun shootingSettingHistory(history: List<ShootingSettingReceipt>, receipt: ShootingSettingReceipt): List<ShootingSettingReceipt> =
    if (history.any { it.id == receipt.id }) history.map { if (it.id == receipt.id) receipt else it }
    else (listOf(receipt) + history).take(20)
internal class ShootingSettingDraftLedger {
    private val consumed = linkedSetOf<Pair<String, Long>>()
    @Synchronized fun claim(draft: ShootingSettingDraft): Boolean {
        if (!consumed.add(draft.token to draft.revision)) return false
        while (consumed.size > 64) consumed.remove(consumed.first())
        return true
    }
}
