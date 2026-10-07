package dev.openeos.control.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Typed ownership risk; never put provider addresses or messages into persistent history. */
internal class IncompleteMediaCleanupException(cause: Exception) :
    IllegalStateException("The incomplete media output could not be removed.", cause)

internal fun Throwable.hasUnconfirmedMediaCleanup(): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>().apply { add(this@hasUnconfirmedMediaCleanup) }
    while (pending.isNotEmpty() && seen.size < 32) {
        val failure = pending.removeFirst()
        if (!seen.add(failure)) continue
        if (failure is IncompleteMediaCleanupException) return true
        failure.cause?.let(pending::addLast)
        failure.suppressed.forEach(pending::addLast)
    }
    return false
}

/** A successful close/publication protects the original from later cancellation or bookkeeping. */
internal class MediaOutputFinalization(
    private val cleanupIncomplete: suspend () -> Unit,
    private val onFinalized: () -> Unit = {},
) {
    private val finalized = AtomicBoolean(false)
    // Only the owning coroutine cleans up. Nested protection at receipt/operation boundaries
    // shares this result so no boundary can delete twice or record a terminal receipt too soon.
    private var cleanupAttempted = false
    @Volatile private var cleanupFailure: IncompleteMediaCleanupException? = null
    val isFinalized: Boolean get() = finalized.get()
    // Job completion can report the original cancellation instead of the recovered throwable
    // that escaped protect(). Completion observers must read this owned result directly.
    val cleanupUnconfirmed: Boolean get() = cleanupFailure != null

    fun confirm(onFinalized: () -> Unit = this.onFinalized) {
        if (finalized.compareAndSet(false, true)) {
            // Receipt/observer failures cannot undo an already closed and published original.
            try {
                onFinalized()
            } catch (_: Exception) {
                // The history store reports its own availability; it is not the transfer result.
            }
        }
    }

    suspend fun <T> protect(transfer: suspend (MediaOutputFinalization) -> T): T {
        try {
            return transfer(this)
        } catch (failure: Throwable) {
            var escaping = failure
            if (!isFinalized && !cleanupAttempted) {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        cleanupAttempted = true
                        try {
                            cleanupIncomplete()
                        } catch (exception: Exception) {
                            // Hold this separately: returning to a cancelled dispatcher can replace
                            // the transfer failure with a different CancellationException.
                            cleanupFailure = IncompleteMediaCleanupException(exception)
                        }
                    }
                    coroutineContext.ensureActive()
                } catch (returnFailure: Throwable) {
                    escaping = returnFailure
                    if (escaping !== failure) escaping.addSuppressed(failure)
                }
            }
            cleanupFailure?.let { marker ->
                if (!escaping.hasUnconfirmedMediaCleanup()) escaping.addSuppressed(marker)
            }
            throw escaping
        }
    }
}

internal suspend fun <T> withMediaOutputFinalization(
    cleanupIncomplete: suspend () -> Unit,
    onFinalized: () -> Unit = {},
    transfer: suspend (MediaOutputFinalization) -> T,
): T {
    return MediaOutputFinalization(cleanupIncomplete, onFinalized).protect(transfer)
}

/** An admitted CreateDocument output already exists, even before the first dispatched turn. */
internal fun CoroutineScope.launchAdmittedMediaOutput(
    output: MediaOutputFinalization,
    register: (Job) -> Unit,
    operation: suspend () -> Unit,
): Job {
    val registered = CompletableDeferred<Unit>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
        output.protect {
            // Enter even with a cancelled parent, but let mappings/completion observers install
            // before operation or cleanup. The operation retains its own cancellation/finally path.
            withContext(NonCancellable) { registered.await() }
            operation()
        }
    }
    try {
        register(job)
    } finally {
        registered.complete(Unit)
    }
    return job
}
