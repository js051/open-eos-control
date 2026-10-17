package dev.openeos.control.ui

import dev.openeos.control.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

class MaintenanceOwner(val connection: Any?, val generation: Long) {
    fun matches(connection: Any?, generation: Long) = this.connection === connection && this.generation == generation
}
data class MaintenanceDraft(val owner: MaintenanceOwner, val token: String, val request: MaintenanceRequest)
data class MaintenanceReceipt(val id: String, val draft: MaintenanceDraft, val origin: String,
    val evidence: MaintenanceSnapshot, val running: Boolean = true, val recovering: Boolean = false,
    val restoring: Boolean = false, val cancelled: Boolean = false, val restoreAvailable: Boolean = false, val retired: Boolean = false)

class MaintenanceAttempt(val draft: MaintenanceDraft, private val access: MaintenanceAccess, private val owns: () -> Boolean,
    private val canRestore: () -> Boolean = { true }, private val timeoutMillis: Long = 15_000L, private val publish: (MaintenanceReceipt) -> Unit) {
    val id = java.util.UUID.randomUUID().toString()
    private var receipt = MaintenanceReceipt(id, draft, access.origin, MaintenanceSnapshot(draft.request), restoreAvailable = access.restoreAvailable)
    private val evidence = MaintenanceEvidence(draft.request, ::requireOwner,
        publish = { state -> update { it.copy(evidence = state) } },
        eventStopRequired = access.eventStopRequired, liveViewStopRequired = access.liveViewStopRequired)
    init { receipt = receipt.copy(evidence = evidence.snapshot()) }
    private var started = false
    private var occupied = false
    private var invocation = 0L
    @Synchronized fun snapshot() = receipt
    @Synchronized private fun update(transform: (MaintenanceReceipt) -> MaintenanceReceipt) { receipt = transform(receipt); publish(receipt) }
    private fun requireOwner() { if (!owns()) throw CancellationException("Maintenance owner changed.") }
    fun cancel() { evidence.cancel(); synchronized(this) { invocation++ }; update { it.copy(cancelled = true) } }
    fun retire() { evidence.cancel(); synchronized(this) { invocation++ }; update { it.copy(retired = true) } }
    fun finishBeforeStart() { synchronized(this) { if (started) return; started = true }; update { it.copy(running = false, cancelled = true) } }
    suspend fun run() {
        synchronized(this) { check(!started); started = true; occupied = true }
        try {
            coroutineContext.ensureActive(); requireOwner()
            if (draft.request.needsStop) {
                if (access.eventStopRequired) { evidence.beginEventStop(); maintenancePhase { access.stopEvents(); evidence.eventStopped() } }
                coroutineContext.ensureActive(); requireOwner()
                if (access.liveViewStopRequired) { evidence.beginLiveViewStop(); maintenancePhase { access.stopLiveView(); evidence.liveViewStopped() } }
            }
            coroutineContext.ensureActive(); requireOwner()
            withTimeout(timeoutMillis) { access.dispatch(draft.request, evidence) }
            coroutineContext.ensureActive(); requireOwner()
            if (snapshot().cancelled) throw CancellationException("Maintenance cancelled.")
            // A valid command response is retained if this separate read fails.
            if ((draft.request.action == MaintenanceAction.CLOCK_SYNC || draft.request.allowsRestore) &&
                evidence.snapshot().availability == MaintenanceAvailability.NOT_READ) readOnce(initial = true)
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) cancel()
            if (evidence.snapshot().command != MaintenanceCommandEvidence.NOT_SENT) evidence.initialUnavailableIfNotRead()
        } finally { synchronized(this) { occupied = false }; update { it.copy(running = false) } }
    }
    private suspend fun maintenancePhase(block: suspend () -> Unit) {
        val caller = coroutineContext
        val guard = MaintenanceIoContext(caller) {
            caller.ensureActive(); requireOwner()
            check(!snapshot().cancelled && !snapshot().retired) { "Maintenance attempt is no longer active." }
        }
        // An in-flight bounded mutation may retain its response. Every subsequent wire step rechecks this gate.
        withContext(NonCancellable + guard) { withTimeout(timeoutMillis) { guard.requireActive(); block() } }
    }
    private suspend fun readOnce(initial: Boolean = false): MaintenanceReadback {
        val revision = synchronized(this) { invocation }
        val value = withTimeout(timeoutMillis) { access.read(draft.request) }
        coroutineContext.ensureActive(); requireOwner()
        if (revision != synchronized(this) { invocation }) throw CancellationException("Maintenance read cancelled.")
        if (initial) evidence.initialRead(value) else evidence.recovery(value)
        return value
    }
    suspend fun recover(): MaintenanceReadback? {
        synchronized(this) { if (occupied || receipt.running || receipt.retired || !owns()) return null; occupied = true }
        update { it.copy(recovering = true) }
        return try { coroutineContext.ensureActive(); requireOwner(); readOnce() }
        catch (_: Exception) { evidence.recoveryUnavailable(); null }
        finally { synchronized(this) { occupied = false }; update { it.copy(recovering = false) } }
    }
    /** A separate explicit product confirmation permits only restoration, never action replay. */
    suspend fun restore(): Boolean {
        synchronized(this) { if (occupied || !canRestoreMaintenance(receipt, owns())) return false; occupied = true }
        update { it.copy(restoring = true) }
        return try {
            coroutineContext.ensureActive(); requireOwner()
            // Every restore needs its own fresh required read. Earlier reachable evidence is insufficient.
            evidence.recoveryUnavailable()
            readOnce()
            coroutineContext.ensureActive(); requireOwner(); check(canRestore()) { "Maintenance safety state changed." }; evidence.beginRestore()
            maintenancePhase { access.restore(); evidence.restored() }
            coroutineContext.ensureActive(); requireOwner()
            true
        } catch (_: MaintenanceRestoreUnavailableException) { evidence.bridgeRestoreUnavailable(); false }
        catch (_: Exception) { false }
        finally { synchronized(this) { occupied = false }; update { it.copy(restoring = false) } }
    }
}
internal fun maintenanceHistory(history: List<MaintenanceReceipt>, receipt: MaintenanceReceipt): List<MaintenanceReceipt> =
    if (history.any { it.id == receipt.id }) history.map { if (it.id == receipt.id) receipt else it }
    else (listOf(receipt) + history).take(20)
internal class MaintenanceDraftLedger {
    private val consumed = linkedSetOf<String>()
    @Synchronized fun claim(draft: MaintenanceDraft): Boolean {
        if (!consumed.add(draft.token)) return false
        // No eviction: an old confirmation token never regains dispatch authority in this ViewModel.
        return true
    }
}
internal fun canRestoreMaintenance(receipt: MaintenanceReceipt, sameOwner: Boolean): Boolean = sameOwner && receipt.restoreAvailable &&
    !receipt.running && !receipt.recovering && !receipt.restoring && !receipt.cancelled && !receipt.retired && receipt.draft.request.allowsRestore &&
    maintenanceStopSatisfied(receipt.evidence.eventStop) && maintenanceStopSatisfied(receipt.evidence.liveViewStop) &&
    receipt.evidence.command == MaintenanceCommandEvidence.RESPONSE_RECEIVED && receipt.evidence.restore == MaintenanceRestoreEvidence.NOT_REQUESTED
