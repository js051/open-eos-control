package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Retiring a preview Start must not cancel its enclosing camera-control connection. */
internal class LiveViewStartOperation {
    private class Attempt(val job: Deferred<Unit>, var retired: Boolean = false)
    private var current: Attempt? = null

    fun retire() {
        current?.let { it.retired = true; it.job.cancel() }
    }

    suspend fun run(start: suspend () -> Unit, stopRequired: () -> Boolean, stop: suspend () -> Unit): Boolean = supervisorScope {
        check(current == null) { "Live View Start must be serialized by its connection owner." }
        // Preserve the child's own terminal outcome even when parent cancellation wins await.
        val childFailure = AtomicReference<Throwable?>()
        val attempt = Attempt(async(start = CoroutineStart.LAZY) {
            try { start() }
            catch (failure: Throwable) { childFailure.set(failure); throw failure }
        })
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
            // Parent cancellation can win await while the backend still owns startup cleanup.
            // Join that producer before considering an additional Stop on the original owner.
            withContext(NonCancellable) {
                attempt.job.join()
                // A substantive child failure must retain its original meaning and Stop debt,
                // just as when await observes it directly. Debt is not permission to retry it.
                childFailure.get()?.takeUnless { it is CancellationException }?.let { throw it }
                if (stopRequired()) stop()
            }
            coroutineContext.ensureActive()
            if (!attempt.retired) throw failure
            false
        } finally {
            if (current === attempt) current = null
        }
    }
}
