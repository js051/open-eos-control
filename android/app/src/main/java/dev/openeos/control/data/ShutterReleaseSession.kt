package dev.openeos.control.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ShutterReleaseException(cause: Throwable) :
    IllegalStateException("Shutter release was not confirmed. Retry releasing the shutter before another operation.", cause)

/** Owns only the matching release, never a retry of a possibly received press. */
internal class ShutterReleaseSession {
    private val mutex = Mutex()
    @Volatile private var pendingRelease: (suspend () -> Unit)? = null
    @Volatile var releaseUnconfirmed: Boolean = false
        private set

    val hasPendingRelease: Boolean get() = pendingRelease != null

    suspend fun start(start: suspend () -> Unit, stop: suspend () -> Unit) = mutex.withLock {
        startLocked(start, stop, releaseAfterStart = false)
    }

    suspend fun pressAndRelease(start: suspend () -> Unit, stop: suspend () -> Unit) = mutex.withLock {
        startLocked(start, stop, releaseAfterStart = true)
    }

    suspend fun retryRelease() = withContext(NonCancellable) {
        mutex.withLock { releaseLocked() }
    }

    private suspend fun startLocked(
        start: suspend () -> Unit,
        stop: suspend () -> Unit,
        releaseAfterStart: Boolean,
    ) {
        check(pendingRelease == null) { "The shutter must be released before starting again." }
        pendingRelease = stop
        var failure: Throwable? = null
        try {
            start()
        } catch (exception: Throwable) {
            failure = exception
            throw exception
        } finally {
            if (releaseAfterStart || failure != null) {
                try {
                    withContext(NonCancellable) { releaseLocked() }
                } catch (releaseFailure: ShutterReleaseException) {
                    failure?.let(releaseFailure::addSuppressed)
                    throw releaseFailure
                }
            }
        }
    }

    private suspend fun releaseLocked() {
        val stop = pendingRelease ?: return
        try {
            stop()
            pendingRelease = null
            releaseUnconfirmed = false
        } catch (exception: Exception) {
            releaseUnconfirmed = true
            throw ShutterReleaseException(exception)
        }
    }
}
