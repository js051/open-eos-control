package dev.openeos.control.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraImportHandoffOwnerTest {
    @Test fun cameraResetCannotReleaseLocalOrAlreadyLaunchedCameraLease() = runTest {
        val owner = CameraImportHandoffOwner<String>(backgroundScope)
        val cleaned = mutableListOf<String>()
        val local = requireNotNull(owner.acquire(CameraImportHandoffOrigin.SAVED_JPEG, reservation("local")) {
            cleaned += it.sessionId; true
        })
        owner.cancelUnlaunched(CameraImportHandoffOrigin.CAMERA)
        assertTrue(owner.owns(local.token))
        assertNull(owner.acquire(CameraImportHandoffOrigin.CAMERA, reservation("blocked")) { true })
        assertTrue(owner.prepared(local.token, "local manifest"))
        assertNotNull(owner.claimLaunch(local.token))
        owner.cancelUnlaunched(CameraImportHandoffOrigin.CAMERA)
        assertEquals(CameraImportHandoffPhase.AWAITING_RESULT, owner.state.value.active?.phase)
        assertNotNull(owner.claimResult(local.token))
        owner.finish(local.token, CameraImportHandoffOutcome(summary = CameraImportReceiptSummary(1, 0, 0, 0)))
        runCurrent()
        assertEquals(listOf("local"), cleaned)

        val camera = requireNotNull(owner.acquire(CameraImportHandoffOrigin.CAMERA, reservation("camera"), 7L) { true })
        owner.prepared(camera.token, "camera manifest")
        owner.claimLaunch(camera.token)
        owner.cancelUnlaunched(CameraImportHandoffOrigin.CAMERA)
        assertTrue(owner.owns(camera.token, CameraImportHandoffPhase.AWAITING_RESULT))
        assertNull(owner.acquire(CameraImportHandoffOrigin.SAVED_JPEG, reservation("still-blocked")) { true })
    }

    @Test fun launchAndResultAreClaimedOnceAndOldTokensCannotConsumeANewerOwner() = runTest {
        val owner = CameraImportHandoffOwner<String>(backgroundScope)
        val first = requireNotNull(owner.acquire(CameraImportHandoffOrigin.CAMERA, reservation("first")) { true })
        assertNull(owner.claimLaunch(first.token))
        owner.prepared(first.token, "first manifest")
        assertNotNull(owner.claimLaunch(first.token))
        assertNull(owner.claimLaunch(first.token))
        assertNotNull(owner.claimResult(first.token))
        assertNull(owner.claimResult(first.token))
        owner.finish(first.token, CameraImportHandoffOutcome())
        runCurrent()
        val second = requireNotNull(owner.acquire(CameraImportHandoffOrigin.SAVED_JPEG, reservation("second")) { true })
        assertFalse(owner.prepared(first.token, "stale"))
        assertFalse(owner.finish(first.token, CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.LAUNCH_FAILED)))
        assertNull(owner.claimLaunch(first.token))
        assertNull(owner.claimResult(first.token))
        assertTrue(owner.owns(second.token, CameraImportHandoffPhase.PREPARING))
    }

    @Test fun cancellationJoinsTheWriterBeforeCleanupOrNewAdmission() = runTest {
        val owner = CameraImportHandoffOwner<String>(backgroundScope)
        val drained = CompletableDeferred<Unit>()
        var writerFinished = false
        var cleaned = false
        val lease = requireNotNull(owner.acquire(CameraImportHandoffOrigin.SAVED_JPEG, reservation("cancel")) {
            assertTrue("No cleanup may race a still-running writer", writerFinished)
            cleaned = true
            true
        })
        val writer = backgroundScope.launch(start = CoroutineStart.LAZY) {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { drained.await(); writerFinished = true }
            }
        }
        owner.attachPreparation(lease.token, writer)
        writer.start()
        runCurrent()
        owner.cancelUnlaunched(CameraImportHandoffOrigin.SAVED_JPEG)
        runCurrent()
        assertFalse(writer.isCompleted)
        assertFalse(cleaned)
        assertFalse(owner.prepared(lease.token, "late manifest"))
        assertNull(owner.acquire(CameraImportHandoffOrigin.CAMERA, reservation("blocked")) { true })
        drained.complete(Unit)
        runCurrent()
        assertTrue(writer.isCompleted)
        assertTrue(cleaned)
        assertFalse(owner.state.value.busy)
        assertEquals(CameraImportHandoffIssue.CANCELLED, owner.state.value.lastResult?.outcome?.issue)
    }

    @Test fun cleanupFailurePreservesVerifiedReceiptAndBlocksUnboundedStagingUntilExplicitRetry() = runTest {
        val owner = CameraImportHandoffOwner<String>(backgroundScope)
        var attempts = 0
        val cleaned = mutableListOf<String>()
        val lease = requireNotNull(owner.acquire(CameraImportHandoffOrigin.SAVED_JPEG, reservation("exact")) {
            cleaned += it.sessionId
            ++attempts > 1
        })
        val receipt = CameraImportHandoffOutcome(summary = CameraImportReceiptSummary(2, 1, 1, 0))
        owner.prepared(lease.token, "manifest")
        owner.claimLaunch(lease.token)
        owner.claimResult(lease.token)
        owner.finish(lease.token, receipt)
        runCurrent()
        assertEquals(receipt, owner.state.value.active?.outcome)
        assertEquals(CameraImportHandoffPhase.CLEANING, owner.state.value.active?.phase)
        assertTrue(owner.state.value.active?.cleanupUnconfirmed == true)
        assertNull(owner.acquire(CameraImportHandoffOrigin.SAVED_JPEG, reservation("new")) { true })
        assertFalse(owner.finish(lease.token, CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.RECEIPT_INVALID)))
        assertEquals(1, attempts)
        owner.retryCleanup()
        owner.retryCleanup()
        runCurrent()
        assertEquals(listOf("exact", "exact"), cleaned)
        assertFalse(owner.state.value.busy)
        assertEquals(receipt, owner.state.value.lastResult?.outcome)
        assertFalse(requireNotNull(owner.state.value.lastResult).cleanupUnconfirmed)
    }

    @Test fun cancellationBeforeCoroutineStartStillCleansReservedSessionExactlyOnce() = runTest {
        val owner = CameraImportHandoffOwner<String>(backgroundScope)
        var calls = 0
        val lease = requireNotNull(owner.acquire(CameraImportHandoffOrigin.CAMERA, reservation("not-started")) { calls++; true })
        val writer = backgroundScope.launch(start = CoroutineStart.LAZY) { fail("Cancelled work must not run") }
        owner.attachPreparation(lease.token, writer)
        owner.cancelUnlaunched(CameraImportHandoffOrigin.CAMERA)
        runCurrent()
        assertTrue(writer.isCompleted)
        assertEquals(1, calls)
        assertFalse(owner.state.value.busy)
    }

    @Test fun immediateCancellationReturnsTheCleanupJobAlreadyStartedByCompletion() = runTest {
        val immediateScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val owner = CameraImportHandoffOwner<String>(immediateScope)
        val allowCleanup = CompletableDeferred<Unit>()
        var cleanupCalls = 0
        val lease = requireNotNull(owner.acquire(CameraImportHandoffOrigin.SAVED_JPEG, reservation("immediate-wait")) {
            cleanupCalls++
            allowCleanup.await()
            true
        })
        val writer = immediateScope.launch(start = CoroutineStart.LAZY) { fail("Cancelled work must not start") }
        owner.attachPreparation(lease.token, writer)

        // Cancelling a lazy writer synchronously invokes its completion callback. With an
        // immediate dispatcher that callback starts cleanup before cancelUnlaunched returns.
        val cleanupJob = owner.cancelUnlaunched()
        try {
            assertNotNull("Disposal must receive the cleanup already started by writer completion", cleanupJob)
            assertTrue(writer.isCompleted)
            assertEquals(1, cleanupCalls)
            assertFalse(requireNotNull(cleanupJob).isCompleted)
            assertSame(cleanupJob, owner.cancelUnlaunched())
            assertTrue(owner.owns(lease.token, CameraImportHandoffPhase.CLEANING))
            assertNull(owner.acquire(CameraImportHandoffOrigin.CAMERA, reservation("waits-for-cleanup")) { true })
        } finally {
            allowCleanup.complete(Unit)
        }
        requireNotNull(cleanupJob).join()
        assertTrue(cleanupJob.isCompleted)
        assertFalse(owner.state.value.busy)
        assertEquals(1, cleanupCalls)
        assertEquals(CameraImportHandoffIssue.CANCELLED, owner.state.value.lastResult?.outcome?.issue)
    }

    @Test fun immediateCompletionCannotScheduleCleanupAgainForAnAlreadyRetiredWork() = runTest {
        val immediateScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val owner = CameraImportHandoffOwner<String>(immediateScope)
        val cleaned = mutableListOf<String>()
        val lease = requireNotNull(owner.acquire(CameraImportHandoffOrigin.CAMERA, reservation("immediate-done")) {
            cleaned += it.sessionId
            true
        })
        val writer = immediateScope.launch(start = CoroutineStart.LAZY) { fail("Cancelled work must not start") }
        owner.attachPreparation(lease.token, writer)

        val cleanupJob = requireNotNull(owner.cancelUnlaunched())
        assertTrue(cleanupJob.isCompleted)
        assertEquals(listOf("immediate-done"), cleaned)
        assertFalse(owner.state.value.busy)
        assertEquals(CameraImportHandoffIssue.CANCELLED, owner.state.value.lastResult?.outcome?.issue)
        val next = requireNotNull(owner.acquire(CameraImportHandoffOrigin.SAVED_JPEG, reservation("next")) { true })
        assertTrue(owner.owns(next.token, CameraImportHandoffPhase.PREPARING))
        assertEquals(listOf("immediate-done"), cleaned)
    }

    @Test fun immediateCleanupFailureRemainsBlockedUntilAnExplicitRetry() = runTest {
        val immediateScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val owner = CameraImportHandoffOwner<String>(immediateScope)
        var cleanupCalls = 0
        val lease = requireNotNull(owner.acquire(CameraImportHandoffOrigin.CAMERA, reservation("immediate-failure")) {
            ++cleanupCalls > 1
        })
        val writer = immediateScope.launch(start = CoroutineStart.LAZY) { fail("Cancelled work must not start") }
        owner.attachPreparation(lease.token, writer)

        val cleanupJob = requireNotNull(owner.cancelUnlaunched())
        assertTrue(cleanupJob.isCompleted)
        assertEquals(1, cleanupCalls)
        assertSame(cleanupJob, owner.cancelUnlaunched())
        assertTrue(owner.owns(lease.token, CameraImportHandoffPhase.CLEANING))
        assertTrue(owner.state.value.active?.cleanupUnconfirmed == true)
        assertEquals(CameraImportHandoffIssue.CANCELLED, owner.state.value.active?.outcome?.issue)
        assertNull(owner.acquire(CameraImportHandoffOrigin.SAVED_JPEG, reservation("blocked")) { true })

        requireNotNull(owner.retryCleanup()).join()
        assertEquals(2, cleanupCalls)
        assertFalse(owner.state.value.busy)
        assertEquals(CameraImportHandoffIssue.CANCELLED, owner.state.value.lastResult?.outcome?.issue)
    }

    private fun reservation(id: String) = CameraImportStagingReservation(id)
}
