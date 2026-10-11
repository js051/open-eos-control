package dev.openeos.control.ui

import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryReceipt
import dev.openeos.control.data.DownloadHistoryRequest
import dev.openeos.control.data.DownloadHistoryStore
import dev.openeos.control.data.DownloadWorklistAdmission
import dev.openeos.control.data.DownloadWorklistFailure
import dev.openeos.control.data.DownloadWorklistRowStart
import dev.openeos.control.data.DownloadWorklistToken
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
    return withDownloadHistoryAdmission(store, admittedOutput, {
        if (store != null && request != null) store.begin(request, filename, destination) else null
    }, transfer)
}

/** Admission is the only interchangeable part; close/cancellation receipts retain one owner. */
private suspend fun <T> withDownloadHistoryAdmission(
    store: DownloadHistoryStore?,
    admittedOutput: MediaOutputFinalization? = null,
    admit: suspend () -> DownloadHistoryReceipt?,
    transfer: suspend (onFinalized: () -> Unit) -> T,
): T {
    var receipt: DownloadHistoryReceipt? = null
    val completed = AtomicBoolean(false)
    try {
        val admittedTransfer: suspend () -> T = {
            // Ownership must survive cancellation while the process writer durably admits the receipt.
            receipt = withContext(NonCancellable) {
                admit()
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


/** A storage admission failure is not an attempted original-file transfer. */
internal class MediaWorklistAdmissionException(message: String) : Exception(message)

/** One frozen roster in one exact save owner; never used for dynamic foreground imports. */
internal class PlannedMediaDownloadBatch(
    private val store: DownloadHistoryStore?,
    private val request: DownloadHistoryRequest?,
    private val filenames: List<String>,
    private val destination: DownloadHistoryDestination,
    private val isCurrent: () -> Boolean,
    private val admissionFailedMessage: String,
    private val admissionUnknownMessage: String,
) {
    private var prepared = false
    private var token: DownloadWorklistToken? = null

    /** False means a complete plan is certainly absent; manual saving remains available. */
    suspend fun prepare(): Boolean {
        checkCurrent()
        check(!prepared) { "A worklist must be admitted only once." }
        prepared = true
        if (store == null || request == null) return false
        val admission = withContext(NonCancellable) { store.admitWorklist(request, filenames, destination) }
        checkCurrent()
        return when (admission) {
            is DownloadWorklistAdmission.Admitted -> {
                token = admission.token
                true
            }
            is DownloadWorklistAdmission.Rejected -> {
                if (!admission.planAbsent) throw MediaWorklistAdmissionException(admissionUnknownMessage)
                false
            }
        }
    }

    suspend fun <T> transfer(index: Int, transfer: suspend (onFinalized: () -> Unit) -> T): T {
        check(prepared) { "The whole worklist must be admitted before an item." }
        require(index in filenames.indices)
        checkCurrent()
        return withDownloadHistoryAdmission(store, admit = {
            val current = token
            if (current == null || store == null) null else when (val start = store.beginWorklistRow(current, index)) {
                is DownloadWorklistRowStart.Started -> start.receipt
                is DownloadWorklistRowStart.Rejected -> {
                    if (start.reason == DownloadWorklistFailure.RETIRED_REQUEST) {
                        // Clear removes the plan without cancelling actual saves. Never
                        // recreate its later rows or request a ticket in the new epoch.
                        token = null
                        null
                    } else throw MediaWorklistAdmissionException(admissionFailedMessage)
                }
            }
        }) { completed ->
            checkCurrent()
            transfer(completed)
        }
    }

    private suspend fun checkCurrent() {
        coroutineContext.ensureActive()
        if (!isCurrent()) throw CancellationException("Media worklist owner retired.")
    }
}
