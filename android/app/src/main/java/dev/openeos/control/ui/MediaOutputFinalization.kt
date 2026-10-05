package dev.openeos.control.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** A successful close/publication protects the original from later cancellation or bookkeeping. */
internal class MediaOutputFinalization(private val onFinalized: () -> Unit) {
    private val finalized = AtomicBoolean(false)
    val isFinalized: Boolean get() = finalized.get()

    fun confirm() {
        if (finalized.compareAndSet(false, true)) {
            // Receipt/observer failures cannot undo an already closed and published original.
            try {
                onFinalized()
            } catch (_: Exception) {
                // The history store reports its own availability; it is not the transfer result.
            }
        }
    }
}

internal suspend fun <T> withMediaOutputFinalization(
    cleanupIncomplete: suspend () -> Unit,
    onFinalized: () -> Unit = {},
    transfer: suspend (MediaOutputFinalization) -> T,
): T {
    val finalization = MediaOutputFinalization(onFinalized)
    try {
        return transfer(finalization)
    } catch (failure: Throwable) {
        if (!finalization.isFinalized) {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    cleanupIncomplete()
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
        }
        throw failure
    }
}
