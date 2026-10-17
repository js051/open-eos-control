package dev.openeos.control.data

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Registration must enqueue non-blocking work. Body parsing belongs to the external callback. */
internal suspend fun <T> awaitBridgeBulbCallback(
    timeoutMillis: Long = 15_000,
    start: ((Result<T>) -> Unit) -> (() -> Unit),
): T = withTimeout(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        val settled = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val cancelAction = AtomicReference<(() -> Unit)?>(null)
        fun cancelOnce() { cancelAction.getAndSet(null)?.invoke() }
        continuation.invokeOnCancellation {
            cancelled.set(true)
            settled.set(true)
            cancelOnce()
        }
        try {
            val cancel = start { result ->
                if (settled.compareAndSet(false, true)) continuation.resumeWith(result)
            }
            cancelAction.set(cancel)
            if (cancelled.get()) cancelOnce()
        } catch (error: Exception) {
            if (settled.compareAndSet(false, true)) continuation.resumeWithException(error)
        }
    }
}
