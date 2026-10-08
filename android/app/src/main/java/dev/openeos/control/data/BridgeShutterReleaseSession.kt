package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bridge owns the camera release; HTTP stop includes a status tail, not a separate release ACK. */
internal class BridgeShutterReleaseSession(val id: String) {
    val mutex = Mutex()
    @Volatile var unconfirmed = false
        private set
    @Volatile var closed = false
        private set
    @Volatile var revision = 0L
        private set
    private var confirmedStart = false
    @Volatile private var releaseRequired = false
    val hasReleaseResponsibility: Boolean get() = releaseRequired
    val hasConfirmedStart: Boolean get() = synchronized(this) { confirmedStart }

    @Synchronized fun observe(status: CameraStatus, expectedRevision: Long): Boolean {
        if (closed || revision != expectedRevision) return false
        if (status.shutterReleaseUnconfirmed == true) markUnconfirmed()
        else if (status.bulbExposureActive == true && !releaseRequired) {
            releaseRequired = true
            confirmedStart = true
            revision += 1
        }
        return true
    }

    @Synchronized fun markUnconfirmed() {
        if (closed) return
        val changed = !unconfirmed || !releaseRequired
        unconfirmed = true
        releaseRequired = true
        if (changed) revision += 1
    }

    fun decorate(status: CameraStatus): CameraStatus = if (unconfirmed) {
        status.copy(bulbExposureActive = null, shutterReleaseUnconfirmed = true)
    } else status

    suspend fun start(read: suspend () -> CameraStatus, press: suspend () -> CameraStatus): CameraStatus = mutex.withLock {
        check(!closed) { "Desktop Bridge session is closed." }
        if (unconfirmed) throw ShutterReleaseException(IllegalStateException("Retry stopping the camera control in this Bridge session."))
        if (confirmedStart) return@withLock read()
        // Record responsibility before bytes can reach Bridge. No failed start is replayable.
        markUnconfirmed()
        try {
            val status = press()
            check(status.bulbExposureActive == true && status.shutterReleaseUnconfirmed != true) {
                "Desktop Bridge did not confirm Bulb start."
            }
            synchronized(this) {
                confirmedStart = true
                unconfirmed = false
                revision += 1
            }
            status
        } catch (exception: CancellationException) {
            throw exception // The same session still owns cleanup; cancellation is not a negative ACK.
        } catch (exception: Exception) {
            throw exception.asReleaseFailure()
        }
    }

    suspend fun stop(
        release: suspend () -> CameraStatus,
        read: suspend () -> CameraStatus,
    ): CameraStatus = withContext(NonCancellable) {
        mutex.withLock {
            check(!closed) { "Desktop Bridge session is closed." }
            val legacyAcknowledgementAllowed = confirmedStart && !unconfirmed
            markUnconfirmed()
            try {
                val status = release()
                if (status.provesRelease() || (legacyAcknowledgementAllowed &&
                        status.bulbExposureActive == false && status.shutterReleaseUnconfirmed == null)) {
                    return@withLock confirmRelease(status)
                }
                error("Bridge did not confirm release. Check the camera; older Bridge versions must be updated for recovery.")
            } catch (exception: Exception) {
                // A 502 may mean release succeeded but the server's status tail failed. Only a
                // fresh read after this stop, from this exact session, can establish that fact.
                try {
                    val fresh = read()
                    if (fresh.provesRelease()) return@withLock confirmRelease(fresh)
                } catch (readFailure: Exception) {
                    exception.addSuppressed(readFailure)
                }
                throw exception.asReleaseFailure()
            }
        }
    }

    @Synchronized private fun confirmRelease(status: CameraStatus): CameraStatus {
        releaseRequired = false
        confirmedStart = false
        unconfirmed = false
        revision += 1
        return status.copy(shutterReleaseUnconfirmed = false)
    }

    @Synchronized fun close() {
        closed = true
        releaseRequired = false
        confirmedStart = false
        unconfirmed = false
        revision += 1
    }

    private fun CameraStatus.provesRelease() =
        bulbExposureActive == false && shutterReleaseUnconfirmed == false

    private fun Exception.asReleaseFailure() = if (this is ShutterReleaseException) this else ShutterReleaseException(this)
}
