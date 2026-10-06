package dev.openeos.control.ui

import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryReceipt
import dev.openeos.control.data.DownloadHistoryRequest
import dev.openeos.control.data.DownloadHistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** A single picker-created output is already an admitted save, before previous reads can join. */
internal class AdmittedMediaDownload(
    val output: MediaOutputFinalization,
    private val store: DownloadHistoryStore?,
    private val request: DownloadHistoryRequest?,
    private val filename: String,
    private val destination: DownloadHistoryDestination,
) {
    private var receiptCompleted: () -> Unit = {}

    suspend fun run(transfer: suspend () -> Unit) {
        withDownloadHistoryReceipt(store, request, filename, destination, output) { completed ->
            receiptCompleted = completed
            transfer()
        }
    }

    fun confirm(onFinalized: () -> Unit) {
        output.confirm {
            receiptCompleted()
            onFinalized()
        }
    }
}

/** One receipt per started original, outside transport retries and independent of session UI. */
internal suspend fun <T> withDownloadHistoryReceipt(
    store: DownloadHistoryStore?,
    request: DownloadHistoryRequest?,
    filename: String,
    destination: DownloadHistoryDestination,
    admittedOutput: MediaOutputFinalization? = null,
    transfer: suspend (onFinalized: () -> Unit) -> T,
): T {
    var receipt: DownloadHistoryReceipt? = null
    val completed = AtomicBoolean(false)
    try {
        val admittedTransfer: suspend () -> T = {
            // Ownership must survive cancellation while the process writer durably admits the receipt.
            receipt = withContext(NonCancellable) {
                if (store != null && request != null) store.begin(request, filename, destination) else null
            }
            coroutineContext.ensureActive()
            transfer {
                if (completed.compareAndSet(false, true)) {
                    store?.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
                }
            }
        }
        // A picker output predates receipt admission. Its cleanup must finish before the first
        // terminal write, including cancellation while begin() is awaiting the durable journal.
        return if (admittedOutput == null) admittedTransfer() else admittedOutput.protect { admittedTransfer() }
    } catch (failure: CancellationException) {
        if (!completed.get()) store?.recordFinished(receipt, DownloadHistoryOutcome.CANCELLED, failure.hasUnconfirmedMediaCleanup())
        throw failure
    } catch (failure: Exception) {
        if (!completed.get()) store?.recordFinished(receipt, DownloadHistoryOutcome.FAILED, failure.hasUnconfirmedMediaCleanup())
        throw failure
    }
}
