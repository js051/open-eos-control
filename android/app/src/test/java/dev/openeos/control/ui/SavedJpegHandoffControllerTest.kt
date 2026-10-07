package dev.openeos.control.ui

import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SavedJpegHandoffControllerTest {
    @Test fun admittedSnapshotSurvivesClearDisconnectAndEvictionWithoutAnyCameraDependency() = runTest {
        val store = DeliveredJpegStore(capacity = 2)
        val first = publish(store, 1)
        val second = publish(store, 2)
        val owner = CameraImportHandoffOwner<String>(backgroundScope)
        val controller = SavedJpegHandoffController(store, owner, backgroundScope)
        val backend = LocalOriginals().apply { gate = CompletableDeferred() }
        controller.toggle(first, true)
        controller.toggle(second, true)
        assertTrue(controller.send(setOf(first, second), backend, false, true))
        runCurrent()
        assertEquals(listOf(second, first), backend.selections.single().map { it.id })
        owner.cancelUnlaunched(CameraImportHandoffOrigin.CAMERA) // disconnect/reconnect reset
        controller.clear()
        val newer = publish(store, 3)
        publish(store, 4)
        publish(store, 5)
        runCurrent()
        assertTrue(controller.state.value.selectedIds.isEmpty())
        assertFalse(controller.state.value.rows.any { it.id == first || it.id == second || it.id == newer })
        backend.gate!!.complete(Unit)
        runCurrent()
        val lease = requireNotNull(owner.state.value.active)
        assertEquals(CameraImportHandoffPhase.READY, lease.phase)
        assertEquals(listOf(second, first), backend.selections.single().map { it.id })
        owner.claimLaunch(lease.token)
        owner.claimResult(lease.token)
        val outcome = CameraImportHandoffOutcome(summary = CameraImportReceiptSummary(2, 0, 0, 0))
        owner.finish(lease.token, outcome)
        runCurrent()
        assertEquals(outcome, controller.state.value.outcome)
        assertEquals(2, controller.state.value.rows.size)
        assertFalse(controller.state.value.rows.any { it.id == first || it.id == second })
        assertEquals(1, backend.prepareCalls)
        assertEquals(listOf(lease.reservation.sessionId), backend.cleaned)
    }

    @Test fun staleUiCallbackCannotRestoreEvictedRowsOrSilentlySendASmallerBatch() = runTest {
        val store = DeliveredJpegStore(capacity = 2)
        val first = publish(store, 1)
        val second = publish(store, 2)
        val owner = CameraImportHandoffOwner<String>(backgroundScope)
        val controller = SavedJpegHandoffController(store, owner, backgroundScope)
        val backend = LocalOriginals()
        controller.toggle(first, true)
        controller.toggle(second, true)
        val displayedSelection = controller.state.value.selectedIds
        publish(store, 3)
        runCurrent()
        assertEquals(setOf(second), controller.state.value.selectedIds)
        controller.toggle(first, true)
        assertEquals(setOf(second), controller.state.value.selectedIds)
        assertFalse(controller.send(displayedSelection, backend, false, true))
        assertEquals(CameraImportHandoffIssue.STALE_SELECTION, controller.state.value.outcome?.issue)
        assertEquals(0, backend.reservations)
        assertEquals(0, backend.prepareCalls)
        assertFalse(owner.state.value.busy)
    }

    @Test fun automaticImportMustFullyRetireAndDuplicateSendCannotAllocateAnotherSession() = runTest {
        val store = DeliveredJpegStore()
        val id = publish(store, 1)
        val owner = CameraImportHandoffOwner<String>(backgroundScope)
        val controller = SavedJpegHandoffController(store, owner, backgroundScope)
        val backend = LocalOriginals().apply { gate = CompletableDeferred() }
        assertFalse(controller.send(setOf(id), backend, true, true))
        assertEquals(CameraImportHandoffIssue.AUTOMATIC_IMPORT_ACTIVE, controller.state.value.outcome?.issue)
        assertEquals(0, backend.reservations)
        assertFalse(controller.send(setOf(id), backend, false, false))
        assertEquals(CameraImportHandoffIssue.SEREIN_UNAVAILABLE, controller.state.value.outcome?.issue)
        assertTrue(controller.send(setOf(id), backend, false, true))
        assertFalse(controller.send(setOf(id), backend, false, true))
        assertEquals(1, backend.reservations)
        assertNull(controller.state.value.outcome)
        runCurrent()
        controller.cancel()
        runCurrent()
        assertFalse(owner.state.value.busy)
        assertEquals(1, backend.prepareCalls)
        assertEquals(1, backend.cleaned.size)
        assertEquals(CameraImportHandoffIssue.CANCELLED, controller.state.value.outcome?.issue)
    }

    @Test fun controllerCancelWaitsForLocalWriterAndDropsLateProgressBeforeNextAdmission() = runTest {
        val store = DeliveredJpegStore()
        val id = publish(store, 1)
        val owner = CameraImportHandoffOwner<String>(backgroundScope)
        val controller = SavedJpegHandoffController(store, owner, backgroundScope)
        val releaseWriter = CompletableDeferred<Unit>()
        val backend = LocalOriginals().apply { gate = CompletableDeferred(); cancellationDrain = releaseWriter }
        controller.send(setOf(id), backend, false, true)
        runCurrent()
        val firstToken = requireNotNull(owner.state.value.active).token
        val staleProgress = requireNotNull(backend.progress)
        controller.cancel()
        runCurrent()
        assertTrue(owner.state.value.busy)
        assertTrue(backend.cleaned.isEmpty())
        assertFalse(controller.send(setOf(id), LocalOriginals(), false, true))
        releaseWriter.complete(Unit)
        runCurrent()
        assertFalse(owner.state.value.busy)
        val next = LocalOriginals().apply { gate = CompletableDeferred() }
        assertTrue(controller.send(setOf(id), next, false, true))
        runCurrent()
        assertNotEquals(firstToken, owner.state.value.active?.token)
        val before = controller.state.value.progress
        staleProgress(CameraMediaTransferProgress(9999, 9999))
        assertEquals(before, controller.state.value.progress)
        controller.cancel()
        runCurrent()
    }

    @Test fun unavailableOriginalNeedsExplicitSuccessfulRecheckAndNeverBecomesCameraRetry() = runTest {
        val store = DeliveredJpegStore()
        val id = publish(store, 1)
        val owner = CameraImportHandoffOwner<String>(backgroundScope)
        val controller = SavedJpegHandoffController(store, owner, backgroundScope)
        val unavailable = LocalOriginals().apply { failure = IOException("content://private/forbidden") }
        controller.toggle(id, true)
        controller.send(setOf(id), unavailable, false, true)
        runCurrent()
        assertEquals(CameraImportHandoffIssue.SOURCE_UNAVAILABLE, controller.state.value.outcome?.issue)
        assertEquals(setOf(id), controller.state.value.unavailableIds)
        assertTrue(controller.state.value.selectedIds.isEmpty())
        controller.toggle(id, true)
        assertTrue(controller.state.value.selectedIds.isEmpty())
        val retry = LocalOriginals()
        assertFalse(controller.send(setOf(id), retry, false, true))
        assertEquals(0, retry.prepareCalls)
        assertTrue(controller.recheck(id, retry, false))
        runCurrent()
        assertTrue(controller.state.value.unavailableIds.isEmpty())
        assertFalse(owner.state.value.busy) // recheck validates and cleans; it never launches
        assertEquals(1, retry.prepareCalls)
        assertEquals(1, retry.cleaned.size)
        controller.toggle(id, true)
        assertEquals(setOf(id), controller.state.value.selectedIds)
        assertEquals(1, store.state.value.entries.size)
    }

    @Test fun verifiedReceiptAndCleanupFailureAreIndependentAndClearNeverErasesResult() = runTest {
        val store = DeliveredJpegStore()
        val id = publish(store, 1)
        val owner = CameraImportHandoffOwner<String>(backgroundScope)
        val controller = SavedJpegHandoffController(store, owner, backgroundScope)
        val backend = LocalOriginals().apply { cleanupSucceeds = false }
        controller.send(setOf(id), backend, false, true)
        runCurrent()
        val token = requireNotNull(owner.state.value.active).token
        owner.claimLaunch(token)
        owner.claimResult(token)
        val receipt = CameraImportHandoffOutcome(summary = CameraImportReceiptSummary(0, 1, 0, 0))
        owner.finish(token, receipt)
        runCurrent()
        assertEquals(receipt, controller.state.value.outcome)
        assertTrue(controller.state.value.cleanupUnconfirmed)
        controller.clear()
        runCurrent()
        assertEquals(receipt, controller.state.value.outcome)
        assertTrue(controller.state.value.cleanupUnconfirmed)
        assertEquals(1, backend.selections.single().size)
        backend.cleanupSucceeds = true
        owner.retryCleanup()
        runCurrent()
        assertEquals(receipt, controller.state.value.outcome)
        assertFalse(controller.state.value.cleanupUnconfirmed)
        assertTrue(controller.state.value.rows.isEmpty())
    }

    /** This spy only opens the admitted local-original seam; no transport can be supplied. */
    private class LocalOriginals : SavedJpegHandoffBackend<String> {
        var reservations = 0
        var prepareCalls = 0
        val selections = mutableListOf<List<DeliveredJpeg>>()
        val cleaned = mutableListOf<String>()
        var cleanupSucceeds = true
        var gate: CompletableDeferred<Unit>? = null
        var cancellationDrain: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        var progress: ((CameraMediaTransferProgress) -> Unit)? = null
        override fun reserve() = CameraImportStagingReservation("local-${++reservations}")
        override suspend fun prepare(selected: List<DeliveredJpeg>, reservation: CameraImportStagingReservation,
            onItem: (Int, Int, String) -> Unit, onProgress: (CameraMediaTransferProgress) -> Unit,
        ): String {
            prepareCalls++
            selections += selected
            progress = onProgress
            onItem(0, selected.size, selected.first().row.filename)
            onProgress(CameraMediaTransferProgress(0L, selected.first().evidence.byteLength))
            try { gate?.await(); failure?.let { throw it } }
            finally { cancellationDrain?.let { withContext(NonCancellable) { it.await() } } }
            return reservation.sessionId
        }
        override suspend fun cleanup(reservation: CameraImportStagingReservation): Boolean {
            cleaned += reservation.sessionId
            return cleanupSucceeds
        }
    }

    private fun publish(store: DeliveredJpegStore, number: Int): DeliveredJpegId = requireNotNull(store.recordPublished(
        CameraInfo(true, "Synthetic EOS", "TEST-SERIAL", "ccapi"),
        CameraMediaItem("source-$number", "IMG_$number.JPG", "image", sizeBytes = 123),
        PublishedGalleryOriginal("content://media/external/images/media/$number", 123, "a".repeat(64), "image/jpeg"),
    ))
}
