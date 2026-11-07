package dev.openeos.control.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** One Bridge connection owns these tokens. A delayed gesture can release only its own hold. */
internal class BridgeHeldAutofocusSession(
    private val command: suspend (ownerId: String, start: Boolean) -> Unit,
    private val newOwnerId: () -> String = { UUID.randomUUID().toString() },
    private val onTransition: () -> Unit = {},
) {
    private class Owner(val id: String) {
        @Volatile var confirmed = false
    }

    private val mutex = Mutex()
    @Volatile private var owner: Owner? = null
    private var closing = false
    @Volatile var revision = 0L
        private set
    val hasReleaseResponsibility: Boolean get() = owner != null
    val active: Boolean? get() {
        val current = owner ?: return false
        return if (current.confirmed) true else null
    }
    val stopUnconfirmed: Boolean get() = owner?.confirmed == false

    suspend fun hold(whileHeld: suspend () -> Unit) {
        var held: Owner? = null
        var failure: Throwable? = null
        try {
            mutex.withLock {
                check(!closing) { "Desktop Bridge focus session is closing." }
                check(owner == null) { "Autofocus must be stopped before starting again." }
                val next = Owner(newOwnerId())
                require(next.id.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Invalid focus owner." }
                held = next
                owner = next // A lost or cancelled start still owns this exact stop.
                changed()
                command(next.id, true)
                next.confirmed = true
                changed()
            }
            whileHeld()
        } catch (exception: Throwable) {
            failure = exception
            throw exception
        } finally {
            held?.let { token ->
                try {
                    withContext(NonCancellable) { mutex.withLock { releaseLocked(token) } }
                } catch (exception: AutofocusReleaseException) {
                    failure?.let(exception::addSuppressed)
                    throw exception
                }
            }
        }
    }

    suspend fun retryStop() = withContext(NonCancellable) {
        mutex.withLock { owner?.let { releaseLocked(it) } }
    }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            closing = true
            owner?.let { releaseLocked(it) }
        }
    }

    private suspend fun releaseLocked(token: Owner) {
        if (owner !== token) return // A newer owner or close already settled this gesture.
        token.confirmed = false
        changed()
        try {
            command(token.id, false)
            owner = null
            changed()
        } catch (exception: Exception) {
            throw AutofocusReleaseException(exception)
        }
    }

    private fun changed() {
        revision += 1
        onTransition()
    }
}
