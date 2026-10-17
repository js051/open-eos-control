package dev.openeos.control.data

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Current-only tests: invoke the production atomic fence, never a test replacement. */
class MaintenanceAtomicFenceTest {
    private val request = MaintenanceRequest(MaintenanceAction.CLOCK_SYNC, MaintenanceClockTarget(1_700_000_000_000L, 0, false))
    private val reference = MaintenanceClockReference(1_700_000_000L, 1_700_000_002L, 0, false)
    private val observation = MaintenanceClockObservation(1_700_000_001L, 0, false)
    private fun evidence(publish: (MaintenanceSnapshot) -> Unit = {}) = MaintenanceEvidence(request, publish = publish).also { it.beforeDispatch() }
    private fun complete(evidence: MaintenanceEvidence) = evidence.bridgeResponseReceived(reference, observation)
    private fun assertOriginalProof(evidence: MaintenanceEvidence) {
        val state = evidence.snapshot()
        assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, state.command)
        assertEquals(MaintenanceClockEvidence.MATCHED, state.clockRead)
        assertEquals(reference, state.clockReference)
        assertEquals(observation, state.observedClock)
    }
    @Test(timeout = 5000) fun completedOriginalResponseCannotBeDowngradedByUnavailableFallback() {
        val evidence = evidence()
        val responseDone = CountDownLatch(1)
        val fallbackDone = CountDownLatch(1)
        Thread {
            check(responseDone.await(2, TimeUnit.SECONDS))
            evidence.initialUnavailableIfNotRead()
            fallbackDone.countDown()
        }.apply { isDaemon = true; start() }
        complete(evidence); responseDone.countDown()
        assertTrue(fallbackDone.await(2, TimeUnit.SECONDS))
        assertOriginalProof(evidence)
        assertEquals(MaintenanceAvailability.REACHABLE, evidence.snapshot().availability)
    }
    @Test(timeout = 5000) fun completedUnavailableFallbackDoesNotPreventLateOriginalProof() {
        val evidence = evidence()
        val fallbackDone = CountDownLatch(1)
        val responseDone = CountDownLatch(1)
        Thread {
            check(fallbackDone.await(2, TimeUnit.SECONDS))
            complete(evidence)
            responseDone.countDown()
        }.apply { isDaemon = true; start() }
        evidence.initialUnavailableIfNotRead(); fallbackDone.countDown()
        assertTrue(responseDone.await(2, TimeUnit.SECONDS))
        assertOriginalProof(evidence)
        assertEquals(MaintenanceAvailability.UNAVAILABLE, evidence.snapshot().availability)
    }
    @Test(timeout = 5000) fun concurrentFallbackNeverOverwritesAlreadyPublishedReachableProof() {
        repeat(64) {
            val publications = CopyOnWriteArrayList<MaintenanceSnapshot>()
            val evidence = evidence { publications += it }
            val ready = CountDownLatch(2); val start = CountDownLatch(1); val finished = CountDownLatch(2)
            val failures = CopyOnWriteArrayList<Throwable>()
            for (action in listOf<() -> Unit>({ evidence.initialUnavailableIfNotRead() }, { complete(evidence) })) {
                Thread {
                    try { ready.countDown(); check(start.await(2, TimeUnit.SECONDS)); action() }
                    catch (failure: Throwable) { failures += failure }
                    finally { finished.countDown() }
                }.apply { isDaemon = true; start() }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS)); start.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS)); assertTrue(failures.isEmpty())
            assertOriginalProof(evidence)
            val reachable = publications.indexOfFirst { it.availability == MaintenanceAvailability.REACHABLE }
            if (reachable >= 0) assertTrue("An atomic fallback cannot follow an already-published reachable original response with unavailable",
                publications.drop(reachable).all { it.availability == MaintenanceAvailability.REACHABLE })
        }
    }
}
