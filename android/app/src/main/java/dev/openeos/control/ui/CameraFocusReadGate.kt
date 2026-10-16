package dev.openeos.control.ui

import dev.openeos.control.data.CameraFocusInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The slot belongs to the view model, so cancelled loops cannot overlap a replacement read. */
internal class CameraFocusReadGate {
    private val mutex = Mutex()

    suspend fun readIfCurrent(isCurrent: () -> Boolean, read: suspend () -> CameraFocusInfo?): CameraFocusInfo? =
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (isCurrent()) read() else null
        }
}
