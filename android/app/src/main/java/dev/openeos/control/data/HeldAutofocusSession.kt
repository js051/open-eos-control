package dev.openeos.control.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AutofocusReleaseException(cause: Throwable) :
    IllegalStateException("Autofocus stop was not confirmed. Retry stopping autofocus.", cause)

internal class HeldAutofocusSession {
    private val mutex = Mutex()
    private var pendingRelease: (suspend () -> Unit)? = null

    suspend fun hold(start: suspend () -> Unit, stop: suspend () -> Unit, whileHeld: suspend () -> Unit) =
        mutex.withLock {
            check(pendingRelease == null) { "Autofocus must be stopped before starting again." }
            // Even a failed start can have reached the camera. Retain its stop until acknowledged.
            pendingRelease = stop
            var failure: Throwable? = null
            try {
                start()
                whileHeld()
            } catch (exception: Throwable) {
                failure = exception
                throw exception
            } finally {
                try {
                    withContext(NonCancellable) { releaseLocked() }
                } catch (exception: AutofocusReleaseException) {
                    failure?.let(exception::addSuppressed)
                    throw exception
                }
            }
        }

    suspend fun retryStop() = mutex.withLock { withContext(NonCancellable) { releaseLocked() } }

    private suspend fun releaseLocked() {
        val release = pendingRelease ?: return
        try {
            release()
            pendingRelease = null
        } catch (exception: Exception) {
            throw AutofocusReleaseException(exception)
        }
    }
}
