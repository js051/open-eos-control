package dev.openeos.control.ui

import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryRequest
import dev.openeos.control.data.DownloadHistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** One receipt per started original, outside transport retries and independent of session UI. */
internal suspend fun <T> withDownloadHistoryReceipt(
    store: DownloadHistoryStore?,
    request: DownloadHistoryRequest?,
    filename: String,
    destination: DownloadHistoryDestination,
    transfer: suspend (onFinalized: () -> Unit) -> T,
): T {
    // Ownership must survive cancellation while the process writer durably admits the receipt.
    val receipt = withContext(NonCancellable) {
        if (store != null && request != null) store.begin(request, filename, destination) else null
    }
    val completed = AtomicBoolean(false)
    try {
        coroutineContext.ensureActive()
        return transfer {
            if (completed.compareAndSet(false, true)) {
                store?.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
            }
        }
    } catch (failure: CancellationException) {
        if (!completed.get()) store?.recordFinished(receipt, DownloadHistoryOutcome.CANCELLED)
        throw failure
    } catch (failure: Exception) {
        if (!completed.get()) store?.recordFinished(receipt, DownloadHistoryOutcome.FAILED)
        throw failure
    }
}
