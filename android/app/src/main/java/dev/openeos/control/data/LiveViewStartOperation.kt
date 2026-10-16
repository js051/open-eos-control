package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/** Retiring a preview Start must not cancel its enclosing camera-control connection. */
internal class LiveViewStartOperation {
    private class Attempt(val job: Deferred<Unit>, var retired: Boolean = false)
    private var current: Attempt? = null

    fun retire() {
        current?.let { it.retired = true; it.job.cancel() }
    }

    suspend fun run(start: suspend () -> Unit, stopRequired: () -> Boolean, stop: suspend () -> Unit): Boolean = supervisorScope {
        check(current == null) { "Live View Start must be serialized by its connection owner." }
        val attempt = Attempt(async(start = CoroutineStart.LAZY) { start() })
        current = attempt
        try {
            attempt.job.start()
            attempt.job.await()
            coroutineContext.ensureActive()
            if (attempt.retired) {
                // A completed Deferred cannot be cancelled. Retirement can still win before
                // this await continuation runs, and owes the same cleanup as cancellation.
                withContext(NonCancellable) { if (stopRequired()) stop() }
                coroutineContext.ensureActive()
                false
            } else true
        } catch (failure: CancellationException) {
            // Cancellation can win after Start returned readiness but before await resumes.
            // Stop uses the original captured backend, never whichever source is selected later.
            withContext(NonCancellable) { if (stopRequired()) stop() }
            coroutineContext.ensureActive()
            if (!attempt.retired) throw failure
            false
        } finally {
            if (current === attempt) current = null
        }
    }
}
