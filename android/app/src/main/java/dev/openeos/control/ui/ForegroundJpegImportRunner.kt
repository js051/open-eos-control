package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaInventory
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaDownloadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.io.OutputStream

/** Publication and cleanup callbacks can run on the output's IO dispatcher. */
internal class ForegroundImportReceipt {
    private val publication = AtomicBoolean(false)
    private val cleanup = AtomicBoolean(true)
    val published: Boolean get() = publication.get()
    val cleanupConfirmed: Boolean get() = cleanup.get()
    fun markPublished() { publication.set(true) }
    fun markCleanupUnconfirmed() { cleanup.set(false) }
}

internal interface ForegroundJpegImportIo {
    /** Must reject an obsolete connection owner and yield to interactive camera/media work. */
    suspend fun awaitAvailable()
    suspend fun inventory(maximumItems: Int): CameraMediaInventory
    suspend fun freshInfo(item: CameraMediaItem): CameraMediaItem
    /** One original request only. Return/throw only after output finalization or cleanup ends. */
    suspend fun saveOriginal(item: CameraMediaItem, receipt: ForegroundImportReceipt)
}

internal fun interface ForegroundJpegImportOutput {
    suspend fun save(
        item: CameraMediaItem,
        receipt: ForegroundImportReceipt,
        download: suspend (OutputStream) -> CameraMediaDownloadResult,
    )
}

internal class ForegroundJpegImportRunner(
    private val io: ForegroundJpegImportIo,
    private val elapsedMillis: () -> Long,
    private val onStatus: (ForegroundJpegImportStatus) -> Unit,
    private val limits: ForegroundImportLimits = ForegroundImportLimits(),
    private val readTimeoutMillis: Long = 30_000L,
    private val idlePollMillis: Long = 5_000L,
) {
    private val policy = ForegroundJpegImportPolicy(limits)
    val status: ForegroundJpegImportStatus get() = policy.status

    fun requestStop(reason: ForegroundImportStopReason) {
        policy.requestStop(reason)
        onStatus(policy.status)
    }

    /** A LAZY owner cancelled before its body ran has no IO/finally block to retire it. */
    fun finishBeforeStart() {
        if (policy.status.stopReason == null) policy.requestStop(ForegroundImportStopReason.USER)
        check(policy.finishStop())
        onStatus(policy.status)
    }

    suspend fun run() {
        onStatus(policy.status)
        try {
            val first = readInventory(limits.baselineItems + 1)
            if (!validBaseline(first)) return
            val second = readInventory(limits.baselineItems + 1)
            if (!validBaseline(second)) return
            if (first.items.map { it.id }.toSet() != second.items.map { it.id }.toSet()) {
                policy.requestStop(ForegroundImportStopReason.BASELINE_CHANGED)
                return
            }
            policy.acceptBaseline(second.items, complete = true)
            onStatus(policy.status)
            while (currentCoroutineContext().isActive && policy.status.stopReason == null) {
                val inventory = readInventory(limits.knownItems + 1)
                policy.observeInventory(inventory.items, inventory.complete, elapsedMillis())
                onStatus(policy.status)
                if (policy.status.stopReason != null) break
                // Admission checks happen first: an overflowing snapshot makes no per-item reads.
                for (candidate in policy.pendingMetadataItems()) {
                    io.awaitAvailable()
                    currentCoroutineContext().ensureActive()
                    // Existing mediaInfo retains fallback fields when the camera omits them.
                    // Clear old size before each fresh read so it cannot fabricate stability.
                    val requested = candidate.copy(sizeBytes = null, contentType = null)
                    val metadata = withTimeout(readTimeoutMillis) { io.freshInfo(requested) }
                    currentCoroutineContext().ensureActive()
                    policy.acceptMetadata(candidate, metadata, elapsedMillis())
                    onStatus(policy.status)
                    if (policy.status.stopReason != null) break
                }
                if (policy.status.stopReason != null) break
                while (true) {
                    val next = policy.claimNext() ?: break
                    onStatus(policy.status)
                    transfer(next)
                    if (policy.status.stopReason != null) break
                }
                if (policy.status.stopReason != null) break
                delay(if (policy.status.pendingCount > 0) limits.stableMillis else idlePollMillis)
            }
        } catch (failure: TimeoutCancellationException) {
            policy.requestStop(ForegroundImportStopReason.READ_FAILED)
        } catch (cancelled: CancellationException) {
            if (policy.status.stopReason == null) policy.requestStop(ForegroundImportStopReason.SESSION_CHANGED)
            throw cancelled
        } catch (_: Exception) {
            policy.requestStop(ForegroundImportStopReason.READ_FAILED)
        } finally {
            withContext(NonCancellable) {
                if (policy.status.stopReason == null) policy.requestStop(ForegroundImportStopReason.USER)
                check(policy.finishStop()) { "Foreground import still owns an active output." }
                onStatus(policy.status)
            }
        }
    }

    private fun validBaseline(inventory: CameraMediaInventory): Boolean {
        if (inventory.items.size > limits.baselineItems) {
            policy.requestStop(ForegroundImportStopReason.BASELINE_LIMIT)
            return false
        }
        if (!inventory.complete) {
            policy.requestStop(ForegroundImportStopReason.INCOMPLETE_BASELINE)
            return false
        }
        return true
    }

    private suspend fun readInventory(limit: Int): CameraMediaInventory {
        io.awaitAvailable()
        currentCoroutineContext().ensureActive()
        val snapshot = withTimeout(readTimeoutMillis) { io.inventory(limit) }
        currentCoroutineContext().ensureActive()
        return snapshot
    }

    private suspend fun transfer(item: CameraMediaItem) {
        val receipt = ForegroundImportReceipt()
        var failed = false
        try {
            io.awaitAvailable()
            currentCoroutineContext().ensureActive()
            io.saveOriginal(item, receipt)
            currentCoroutineContext().ensureActive()
        } catch (timeout: TimeoutCancellationException) {
            failed = true
            policy.requestStop(ForegroundImportStopReason.TRANSFER_FAILED)
            throw timeout
        } catch (cancelled: CancellationException) {
            if (policy.status.stopReason == null) policy.requestStop(ForegroundImportStopReason.USER)
            throw cancelled
        } catch (failure: Exception) {
            failed = true
            if (!receipt.published) throw failure
        } finally {
            policy.finishTransfer(receipt.published, failed, receipt.cleanupConfirmed)
            onStatus(policy.status)
        }
    }
}
