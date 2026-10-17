package dev.openeos.control.data

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExtractedRepositoryTest {
    @Test(timeout = 5000) fun ownedCleanupReachesCloseWithoutLegacyAutofocusOrLiveViewWaits() = runBlocking {
        val backend = HostDisconnectBackend(bridgeBulbCleanupRequired = true)
        backend.blockLegacy = true
        val repository = ExtractedDisconnectRepository(backend)
        assertFalse(withTimeout(500) { repository.disconnect() })
        assertEquals(listOf("close"), backend.calls)
        assertFalse(repository.active)
        assertNull(repository.activeInfo)
    }

    @Test(timeout = 5000) fun unownedCleanupRetainsAutofocusThenLiveViewThenCloseOrder() = runBlocking {
        val backend = HostDisconnectBackend(bridgeBulbCleanupRequired = false)
        val repository = ExtractedDisconnectRepository(backend)
        repository.liveViewRunning = true
        assertFalse(repository.disconnect())
        assertEquals(listOf("autofocus", "liveview", "close"), backend.calls)
        assertFalse(repository.active)
        assertFalse(repository.liveViewRunning)
        assertEquals(0L, repository.connectionRetirementRevision)
    }

    @Test(timeout = 5000) fun closeShutterFailureRetainsPreviousShutterWarning() = runBlocking {
        val backend = HostDisconnectBackend(bridgeBulbCleanupRequired = true)
        backend.closeFailure = ShutterReleaseException(IOException("synthetic camera release uncertainty"))
        val repository = ExtractedDisconnectRepository(backend)
        assertFalse(repository.disconnect())
        assertEquals(setOf(PreviousConnectionSafetyWarning.SHUTTER), repository.connectionRetirementSafetyWarnings)
        assertEquals(1L, repository.connectionRetirementRevision)
        assertFalse(repository.active)
    }

    @Test(timeout = 5000) fun maintenanceCloseRemainsItsSeparateExplicitBoundary() = runBlocking {
        val backend = HostDisconnectBackend(bridgeBulbCleanupRequired = true)
        backend.maintenanceSessionOwned = true
        val repository = ExtractedDisconnectRepository(backend)
        assertFalse(repository.disconnect())
        assertEquals(listOf("maintenance-close"), backend.calls)
        assertFalse(repository.active)
    }
}

private class HostDisconnectBackend(val bridgeBulbCleanupRequired: Boolean) {
    var maintenanceSessionOwned = false
    var liveViewStopRequired = false
    var blockLegacy = false
    var closeFailure: Exception? = null
    val calls = mutableListOf<String>()
    suspend fun closeMaintenanceSession(): Boolean { calls += "maintenance-close"; return false }
    suspend fun retryAutofocusStop() { calls += "autofocus"; if (blockLegacy) awaitCancellation() }
    suspend fun stopLiveView() { calls += "liveview"; if (blockLegacy) awaitCancellation() }
    suspend fun close() { calls += "close"; closeFailure?.let { throw it } }
}
private class ExtractedDisconnectRepository(val backend: HostDisconnectBackend) {
    var active = true
    var liveViewRunning = false
    var activeInfo: Any? = Any()
    var connectionRetirementSafetyWarnings = emptySet<PreviousConnectionSafetyWarning>()
    var connectionRetirementRevision = 0L
    suspend fun disconnect() = disconnectLocked()

    // HOST_PRODUCTION_REPOSITORY_DISCONNECT
}

// HOST_PRODUCTION_PREVIOUS_WARNINGS
