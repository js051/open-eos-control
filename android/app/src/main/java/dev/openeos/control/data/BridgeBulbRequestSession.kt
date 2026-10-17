package dev.openeos.control.data

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class BridgeBulbReceipt(val ownerId: String, val outcome: String, val status: CameraStatus? = null)

/** A saved command identity is release responsibility before the HTTP Start is dispatched. */
internal class BridgeBulbRequestSession(
    private val newOwnerId: () -> String = { UUID.randomUUID().toString() },
    private val onTransition: () -> Unit = {},
    private val monitor: Any = Any(),
) {
    internal class Owner internal constructor(val id: String) {
        internal var stopRequested = false
        internal var released = false
        internal var confirmed = false
        internal var uncertain = false
        internal var dispatched = false
        internal var stopAttempt: CompletableDeferred<CameraStatus?>? = null
        internal var startStatus: CameraStatus? = null
        internal var releaseStatus: CameraStatus? = null
    }
    private var current: Owner? = null
    private var observation: CameraStatus? = null
    private var observationRevision = 0L
    private var retired = false
    val isRetired: Boolean get() = synchronized(monitor) { retired || closed }
    private var retirementRequested = false
    val requiresBoundedRetirement: Boolean get() = synchronized(monitor) { retirementRequested || current != null }
    @Volatile var closed = false
        private set
    val ownerId: String? get() = synchronized(monitor) { current?.id }
    val hasReleaseResponsibility: Boolean get() = synchronized(monitor) { current != null && !closed }
    val hasConfirmedStart: Boolean get() = synchronized(monitor) { current?.confirmed == true && !closed }
    val unconfirmed: Boolean get() = synchronized(monitor) { current?.uncertain == true && !closed }
    val displayStatus: CameraStatus? get() = synchronized(monitor) { observation }

    fun observe(status: CameraStatus) = synchronized(monitor) {
        if (!closed) { observation = status; observationRevision++ }
    }

    fun reserve(admit: () -> Unit = {}): Owner = synchronized(monitor) {
        check(!closed && !retired) { "Bridge session is closed" }
        check(current == null) { "Stop the previous Bulb request before starting another" }
        admit()
        Owner(newOwnerId()).also { current = it; onTransition() }
    }

    suspend fun start(press: suspend (String) -> BridgeBulbReceipt, admit: () -> Unit = {}): CameraStatus =
        start(reserve(admit), press)

    suspend fun start(owner: Owner, press: suspend (String) -> BridgeBulbReceipt): CameraStatus {
        val revision = synchronized(monitor) {
            check(current === owner && !closed && !retired && !owner.stopRequested && !owner.dispatched) {
                "This Bulb request has been stopped or retired"
            }
            owner.dispatched = true
            observationRevision
        }
        try {
            val receipt = press(owner.id)
            val status = receipt.status
            check(receipt.ownerId == owner.id && receipt.outcome == "started" &&
                status?.bulbExposureActive == true && status.shutterReleaseUnconfirmed != true) {
                "Bridge did not confirm the owned Bulb Start"
            }
            return synchronized(monitor) {
                if (current !== owner || closed || retired || owner.stopRequested) {
                    throw CancellationException("The Bulb Start result belongs to a stopped request")
                }
                owner.confirmed = true
                owner.uncertain = false
                owner.startStatus = status
                if (revision == observationRevision) { observation = status; observationRevision++ }
                onTransition()
                observation ?: status
            }
        } catch (error: Exception) {
            synchronized(monitor) {
                if (current === owner && !closed && !owner.stopRequested) {
                    owner.uncertain = true
                    onTransition()
                }
            }
            if (error is CancellationException) throw error
            throw ShutterReleaseException(error)
        }
    }

    suspend fun stop(release: suspend (String) -> BridgeBulbReceipt): CameraStatus? {
        val owner = synchronized(monitor) { current } ?: return synchronized(monitor) { observation }
        return stop(owner, release)
    }

    suspend fun stop(owner: Owner, release: suspend (String) -> BridgeBulbReceipt): CameraStatus? = withContext(NonCancellable) {
        var leader = false
        var revision = 0L
        val attempt = synchronized(monitor) {
            // Old handles never issue an unowned Stop or mutate a replacement owner.
            if (owner.released) { owner.releaseStatus = null; return@withContext observation }
            check(!closed && current === owner) { "This Bulb request belongs to a retired session" }
            owner.stopAttempt ?: CompletableDeferred<CameraStatus?>().also {
                owner.stopAttempt = it
                owner.stopRequested = true
                revision = observationRevision
                leader = true
                onTransition()
            }
        }
        if (!leader) return@withContext attempt.await()
        try {
            val receipt = release(owner.id)
            check(receipt.ownerId == owner.id && receipt.outcome in setOf("released", "cancelled-before-start")) {
                "Bridge did not acknowledge this Bulb owner"
            }
            if (receipt.outcome == "released" && receipt.status != null) {
                check(receipt.status.bulbExposureActive == false && receipt.status.shutterReleaseUnconfirmed == false) {
                    "Bridge did not confirm shutter release"
                }
            }
            val display = synchronized(monitor) {
                check(!closed && current === owner) { "This Bulb receipt belongs to a retired session" }
                // A cached receipt has no camera observation. Only remove our own stale Start flag.
                if (receipt.outcome == "released" && receipt.status != null && revision == observationRevision) {
                    observation = receipt.status
                    owner.releaseStatus = receipt.status
                    observationRevision++
                } else if (observation === owner.startStatus) {
                    observation = observation?.copy(bulbExposureActive = null)
                    observationRevision++
                }
                owner.released = true
                owner.confirmed = false
                owner.uncertain = false
                current = null
                onTransition()
                observation
            }
            attempt.complete(display)
        } catch (error: Exception) {
            synchronized(monitor) {
                if (current === owner && !closed) { owner.uncertain = true; onTransition() }
            }
            attempt.completeExceptionally(ShutterReleaseException(error))
        } finally {
            synchronized(monitor) { if (owner.stopAttempt === attempt) owner.stopAttempt = null }
        }
        attempt.await()
    }

    fun hasFreshReleaseObservation(owner: Owner): Boolean = synchronized(monitor) {
        !closed && current == null && owner.released && owner.releaseStatus != null && observation === owner.releaseStatus
    }

    /** A saved UI cleanup may outlive its Stop response, but may never retire another owner. */
    fun requestRetirement(owner: Owner) = synchronized(monitor) {
        if (!closed && (current === owner || (current == null && owner.released))) {
            retired = true
            retirementRequested = true
            onTransition()
        }
    }

    fun retire(): Boolean = synchronized(monitor) {
        retired = true
        onTransition()
        retirementRequested || current != null
    }
    fun close() = synchronized(monitor) {
        closed = true
        retired = true
        current = null
        onTransition()
    }
}
