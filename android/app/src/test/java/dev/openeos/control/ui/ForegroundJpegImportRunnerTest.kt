package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaInventory
import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Deterministic protocol ownership tests. Fake outputs make no claim about Android MediaStore. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundJpegImportRunnerTest {
    @Test fun twoCompleteMatchingBaselineSetsIgnoreOrderAndNeverReadOldMetadata() = runTest {
        val old = jpeg("old")
        val older = jpeg("older")
        val io = FakeImportIo(listOf(snapshot(old, older), snapshot(older, old), snapshot(old)))
        val runner = runner(io)
        val job = launch { runner.run() }
        runCurrent()
        assertEquals(ForegroundImportPhase.WATCHING, runner.status.phase)
        assertEquals(2, runner.status.baselineCount)
        assertEquals(listOf(2001, 2001, 4097), io.inventoryLimits)
        assertTrue(io.metadataRequests.isEmpty())
        assertTrue(io.saved.isEmpty())
        runner.requestStop(ForegroundImportStopReason.USER)
        job.cancelAndJoin()
        assertEquals(ForegroundImportPhase.STOPPED, runner.status.phase)
    }

    @Test fun changedBaselineIdentitySetStopsBeforeAnyMetadataOrOriginal() = runTest {
        val io = FakeImportIo(listOf(snapshot(jpeg("old")), snapshot(jpeg("replacement"))))
        val runner = runner(io)
        runner.run()
        assertStopped(runner, ForegroundImportStopReason.BASELINE_CHANGED)
        assertEquals(2, io.inventoryLimits.size)
        assertTrue(io.metadataRequests.isEmpty())
        assertTrue(io.saved.isEmpty())
    }

    @Test fun eitherIncompleteBaselineStopsWithoutArmingOrPerItemReads() = runTest {
        for (incompleteRead in 0..1) {
            val snapshots = (0..1).map { snapshot(jpeg("old")).copy(complete = it != incompleteRead) }
            val io = FakeImportIo(snapshots)
            val runner = runner(io)
            runner.run()
            assertStopped(runner, ForegroundImportStopReason.INCOMPLETE_BASELINE)
            assertEquals(incompleteRead + 1, io.inventoryLimits.size)
            assertEquals(0, runner.status.baselineCount)
            assertTrue(io.metadataRequests.isEmpty())
            assertTrue(io.saved.isEmpty())
        }
    }

    @Test fun overCapacityBaselineStopsBeforeItsSecondRead() = runTest {
        val io = FakeImportIo(listOf(snapshot(jpeg("a"), jpeg("b"))))
        val runner = runner(io, limits = ForegroundImportLimits(baselineItems = 1))
        runner.run()
        assertStopped(runner, ForegroundImportStopReason.BASELINE_LIMIT)
        assertEquals(listOf(2), io.inventoryLimits)
        assertTrue(io.metadataRequests.isEmpty())
    }

    @Test fun rawPlusJpegImportsOnlyTheFreshStableJpegExactlyOnce() = runTest {
        val old = jpeg("old")
        val fresh = jpeg("new")
        val raw = fresh.copy(id = "raw", name = "new.CR3", kind = "raw", contentType = null)
        val io = FakeImportIo(listOf(snapshot(old), snapshot(old), snapshot(old, raw, fresh)))
        val runner = runner(io)
        val job = launch { runner.run() }
        runCurrent()
        assertTrue(io.saved.isEmpty())
        assertEquals(listOf("new"), io.metadataRequests.map { it.id })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("new"), io.saved.map { it.id })
        assertEquals(1, runner.status.completedCount)
        repeat(3) { advanceTimeBy(100); runCurrent() }
        assertEquals(1, io.saved.size)
        assertEquals(listOf("new", "new"), io.metadataRequests.map { it.id })
        runner.requestStop(ForegroundImportStopReason.USER)
        job.cancelAndJoin()
    }

    @Test fun cachedInventorySizeIsClearedBeforeEveryFreshMetadataRead() = runTest {
        val item = jpeg("new", 999)
        val io = FakeImportIo(listOf(snapshot(), snapshot(), snapshot(item)))
        io.metadata = { request ->
            assertNull("Fresh reads must not inherit an inventory or prior-info size", request.sizeBytes)
            assertNull(request.contentType)
            request // The camera omits size. No cached positive value may fabricate stability.
        }
        val runner = runner(io)
        val job = launch { runner.run() }
        runCurrent()
        repeat(3) { advanceTimeBy(1_000); runCurrent() }
        assertEquals(4, io.metadataRequests.size)
        assertTrue(io.saved.isEmpty())
        assertEquals(ForegroundImportPhase.WAITING, runner.status.phase)
        runner.requestStop(ForegroundImportStopReason.USER)
        job.cancelAndJoin()
    }

    @Test fun unknownZeroAndGrowingFreshSizesRequireTwoMatchingPositiveObservations() = runTest {
        val io = FakeImportIo(listOf(snapshot(), snapshot(), snapshot(jpeg("new"))))
        val sizes = ArrayDeque(listOf<Long?>(null, 0L, 100L, 200L, 200L))
        io.metadata = { it.copy(sizeBytes = sizes.removeFirst(), contentType = "image/jpeg") }
        val runner = runner(io)
        val job = launch { runner.run() }
        runCurrent()
        repeat(3) {
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(io.saved.isEmpty())
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(200L, io.saved.single().sizeBytes)
        assertEquals(1, runner.status.completedCount)
        runner.requestStop(ForegroundImportStopReason.USER)
        job.cancelAndJoin()
    }

    @Test fun queueOverflowRejectsTheWholeSnapshotBeforeFreshInfoOrOriginal() = runTest {
        val io = FakeImportIo(listOf(snapshot(), snapshot(), snapshot(jpeg("a"), jpeg("b"))))
        val runner = runner(io, limits = ForegroundImportLimits(queuedItems = 1))
        runner.run()
        assertStopped(runner, ForegroundImportStopReason.QUEUE_LIMIT)
        assertEquals(0, runner.status.knownCount)
        assertTrue(io.metadataRequests.isEmpty())
        assertTrue(io.saved.isEmpty())
    }

    @Test fun cumulativeIdentityOverflowCannotForgetOldItemsOrAdmitPartOfABatch() = runTest {
        val io = FakeImportIo(listOf(snapshot(jpeg("old")), snapshot(jpeg("old")), snapshot(jpeg("a"), jpeg("b"))))
        val runner = runner(io, limits = ForegroundImportLimits(baselineItems = 1, knownItems = 2))
        runner.run()
        assertStopped(runner, ForegroundImportStopReason.IDENTITY_LIMIT)
        assertEquals(1, runner.status.knownCount)
        assertTrue(io.metadataRequests.isEmpty())
        assertTrue(io.saved.isEmpty())
    }

    @Test fun incompleteWatchingSnapshotStopsBeforeReadingItsNewCandidate() = runTest {
        val io = FakeImportIo(listOf(snapshot(), snapshot(), snapshot(jpeg("new")).copy(complete = false)))
        val runner = runner(io)
        runner.run()
        assertStopped(runner, ForegroundImportStopReason.INCOMPLETE_SCAN)
        assertTrue(runner.status.discoveryIncomplete)
        assertTrue(io.metadataRequests.isEmpty())
        assertTrue(io.saved.isEmpty())
    }

    @Test fun baselineTimeoutEndsOwnerWithoutStartingMetadataOrTransfer() = runTest {
        val io = FakeImportIo(listOf(snapshot()))
        io.inventoryRead = { awaitCancellation() }
        val runner = runner(io, timeoutMillis = 50)
        val job = launch { runner.run() }
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        job.join()
        assertStopped(runner, ForegroundImportStopReason.READ_FAILED)
        assertEquals(1, io.inventoryLimits.size)
        assertTrue(io.metadataRequests.isEmpty())
        assertTrue(io.saved.isEmpty())
    }

    @Test fun metadataTimeoutDoesNotStartOriginalOrRetryTheCandidate() = runTest {
        val io = FakeImportIo(listOf(snapshot(), snapshot(), snapshot(jpeg("new"))))
        io.metadata = { awaitCancellation() }
        val runner = runner(io, timeoutMillis = 50)
        val job = launch { runner.run() }
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        job.join()
        assertStopped(runner, ForegroundImportStopReason.READ_FAILED)
        assertEquals(1, io.metadataRequests.size)
        assertTrue(io.saved.isEmpty())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, io.inventoryLimits.size)
    }

    @Test fun readTimeoutCannotRetireItsOwnerBeforeNonCancellableReadCleanup() = runTest {
        val io = newCandidateIo()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        io.metadata = {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    cleanupRelease.await()
                }
            }
        }
        val runner = runner(io, timeoutMillis = 50)
        val job = launch { runner.run() }
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        assertFalse(job.isCompleted)
        assertNotEquals(ForegroundImportPhase.STOPPED, runner.status.phase)
        assertTrue(io.saved.isEmpty())
        cleanupRelease.complete(Unit)
        runCurrent()
        job.join()
        assertStopped(runner, ForegroundImportStopReason.READ_FAILED)
        assertEquals(1, io.metadataRequests.size)
        assertTrue(io.saved.isEmpty())
    }

    @Test fun stopWhileWaitingForManualWorkOwnsNoFurtherReads() = runTest {
        val io = FakeImportIo(listOf(snapshot()))
        io.available = { awaitCancellation() }
        val runner = runner(io)
        val job = launch { runner.run() }
        runCurrent()
        runner.requestStop(ForegroundImportStopReason.USER)
        job.cancelAndJoin()
        assertStopped(runner, ForegroundImportStopReason.USER)
        assertTrue(io.inventoryLimits.isEmpty())
        assertTrue(io.saved.isEmpty())
    }

    @Test fun cancellationAfterPublicationKeepsOneCompletedOriginal() = runTest {
        val io = newCandidateIo()
        val published = CompletableDeferred<Unit>()
        io.save = { _, receipt -> receipt.markPublished(); published.complete(Unit); awaitCancellation() }
        val runner = runner(io)
        val job = launch { runner.run() }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(published.isCompleted)
        runner.requestStop(ForegroundImportStopReason.USER)
        job.cancelAndJoin()
        assertStopped(runner, ForegroundImportStopReason.USER)
        assertEquals(1, runner.status.completedCount)
        assertEquals(1, io.saved.size)
    }

    @Test fun cancellationWaitsForNonCancellableCleanupAndLatchesItsFailure() = runTest {
        val io = newCandidateIo()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        io.save = { _, receipt ->
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    cleanupRelease.await()
                    receipt.markCleanupUnconfirmed()
                }
            }
        }
        val runner = runner(io)
        val job = launch { runner.run() }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, io.saved.size)
        runner.requestStop(ForegroundImportStopReason.SESSION_CHANGED)
        job.cancel()
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        assertFalse(job.isCompleted)
        assertEquals(ForegroundImportPhase.STOPPING, runner.status.phase)
        cleanupRelease.complete(Unit)
        runCurrent()
        job.join()
        assertStopped(runner, ForegroundImportStopReason.CLEANUP_UNCONFIRMED)
        assertEquals(0, runner.status.completedCount)
        assertEquals(1, io.saved.size)
    }

    @Test fun saveFailureNeverRetriesAndPublishedReceiptStillWinsAnObserverFailure() = runTest {
        for (published in listOf(false, true)) {
            val io = newCandidateIo()
            io.save = { _, receipt ->
                if (published) receipt.markPublished()
                throw IOException("Synthetic output observer failure")
            }
            val runner = runner(io)
            val job = launch { runner.run() }
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            if (published) {
                assertEquals(1, runner.status.completedCount)
                runner.requestStop(ForegroundImportStopReason.USER)
                job.cancelAndJoin()
            } else {
                job.join()
                assertStopped(runner, ForegroundImportStopReason.TRANSFER_FAILED)
                assertEquals(0, runner.status.completedCount)
            }
            assertEquals(1, io.saved.size)
        }
    }

    private fun TestScope.runner(io: FakeImportIo, limits: ForegroundImportLimits = ForegroundImportLimits(), timeoutMillis: Long = 100) =
        ForegroundJpegImportRunner(io, { testScheduler.currentTime }, {}, limits, timeoutMillis, idlePollMillis = 100)

    private fun assertStopped(runner: ForegroundJpegImportRunner, reason: ForegroundImportStopReason) {
        assertEquals(ForegroundImportPhase.STOPPED, runner.status.phase)
        assertEquals(reason, runner.status.stopReason)
        assertNull(runner.status.activeName)
        assertEquals(0, runner.status.pendingCount)
    }

    private fun newCandidateIo() = FakeImportIo(listOf(snapshot(), snapshot(), snapshot(jpeg("new"))))
    private fun jpeg(id: String, size: Long? = 100L) = CameraMediaItem(id, "$id.JPG", "image", sizeBytes = size, contentType = "image/jpeg")
    private fun snapshot(vararg items: CameraMediaItem) = CameraMediaInventory(items.toList(), complete = true)
}

private class FakeImportIo(snapshots: List<CameraMediaInventory>) : ForegroundJpegImportIo {
    private val snapshots = ArrayDeque(snapshots)
    val inventoryLimits = mutableListOf<Int>()
    val metadataRequests = mutableListOf<CameraMediaItem>()
    val saved = mutableListOf<CameraMediaItem>()
    var available: suspend () -> Unit = {}
    var inventoryRead: (suspend () -> CameraMediaInventory)? = null
    var metadata: suspend (CameraMediaItem) -> CameraMediaItem = { it.copy(sizeBytes = 100L, contentType = "image/jpeg") }
    var save: suspend (CameraMediaItem, ForegroundImportReceipt) -> Unit = { _, receipt -> receipt.markPublished() }
    override suspend fun awaitAvailable() = available()
    override suspend fun inventory(maximumItems: Int): CameraMediaInventory {
        inventoryLimits += maximumItems
        return inventoryRead?.invoke() ?: if (snapshots.size > 1) snapshots.removeFirst() else snapshots.first()
    }
    override suspend fun freshInfo(item: CameraMediaItem): CameraMediaItem {
        metadataRequests += item
        return metadata(item)
    }
    override suspend fun saveOriginal(item: CameraMediaItem, receipt: ForegroundImportReceipt) {
        saved += item
        save(item, receipt)
    }
}
