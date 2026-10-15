package dev.openeos.control.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadHistoryContinuationStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun fiftyRowColdRosterExplicitSubsetCreatesIndependentDurableRowsAndPreservesOldOutcomes() = runTest {
        val directory = temporaryFolder.newFolder()
        val original = newStore(directory)
        val token = original.admitted(List(50) { "SELECTED_$it.JPG" })
        repeat(5) { original.recordFinished(original.started(token, it), DownloadHistoryOutcome.COMPLETED) }
        original.started(token, 5)
        original.awaitIdle()
        val cold = newStore(directory)
        cold.awaitIdle()
        val old = cold.state.value.worklists.single()
        assertEquals(5, old.rows.count { it.receipt?.outcome == DownloadHistoryOutcome.COMPLETED })
        assertEquals(1, old.rows.count { it.receipt?.outcome == DownloadHistoryOutcome.UNCONFIRMED })
        assertEquals(44, old.rows.count { it.receipt == null })
        assertTrue(cold.state.value.activeWorklistIds.isEmpty())
        val selections = listOf(5, 6, 49).map { old.selection(it, "NEW_MAPPING_$it.CR3").copy(uncertaintyAcknowledged = it == 5) }

        val continued = cold.continued(selections, DownloadHistoryDestination.GALLERY)
        val plans = cold.state.value.worklists
        assertEquals(old, plans.last())
        assertEquals(listOf("NEW_MAPPING_5.CR3", "NEW_MAPPING_6.CR3", "NEW_MAPPING_49.CR3"), plans.first().rows.map { it.filename })
        assertEquals(selections.map { it.origin }, plans.first().rows.map { it.origin })
        assertTrue(plans.first().rows.all { it.receipt == null })
        assertEquals(DownloadHistoryDestination.GALLERY, plans.first().destination)
        assertNotEquals(old.worklistId, continued.worklistId)
        assertTrue(plans.first().rows.none { row -> old.rows.any { it.rowId == row.rowId } })
        assertEquals(plans, DownloadHistoryFileStorage(directory).readSnapshot().worklists)
        assertEquals(setOf(continued.worklistId), cold.state.value.activeWorklistIds)
        assertEquals(setOf(continued.worklistId, old.worklistId), cold.state.value.continuationBlockedWorklistIds)

        val receipt = cold.started(continued, 0)
        cold.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
        cold.releaseWorklist(continued)
        cold.awaitIdle()
        assertEquals(old, cold.state.value.worklists.last())
        assertEquals(DownloadHistoryOutcome.UNCONFIRMED, cold.state.value.worklists.last().rows[5].receipt!!.outcome)
        val reopened = newStore(directory)
        reopened.awaitIdle()
        assertEquals(cold.state.value.worklists, reopened.state.value.worklists)
        assertTrue(reopened.state.value.activeWorklistIds.isEmpty())
        assertTrue(reopened.state.value.continuationBlockedWorklistIds.isEmpty())
    }

    @Test fun continuationReturnsOnlyAfterNewRosterIsInstalledAndNeverStartsAnAttempt() = runTest {
        val directory = temporaryFolder.newFolder()
        var check: (() -> Unit)? = null
        val store = newStore(directory, beforeWrite = { if (it == DownloadHistoryWritePhase.BEFORE_MOVE) check?.invoke() })
        val original = store.admitted(listOf("OLD.JPG"))
        store.releaseWorklist(original)
        store.awaitIdle()
        val old = store.state.value.worklists.single()
        var reached = false
        check = {
            reached = true
            assertEquals(listOf(old), DownloadHistoryFileStorage(directory).readSnapshot().worklists)
            assertEquals(listOf(old), store.state.value.worklists)
        }
        val newToken = store.continued(listOf(old.selection(0, "EXPLICIT_NEW.JPG")))
        assertTrue(reached)
        val persisted = DownloadHistoryFileStorage(directory).readSnapshot().worklists
        assertEquals(newToken.worklistId, persisted.first().worklistId)
        assertNull(persisted.first().rows.single().receipt)
        assertEquals(old, persisted.last())
    }

    @Test fun activeOwnershipExcludesQueuedAndFailedRowsUntilExplicitRelease() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val token = store.admitted(listOf("QUEUED.JPG", "FAILED.JPG"))
        store.recordFinished(store.started(token, 1), DownloadHistoryOutcome.FAILED)
        store.awaitIdle()
        val old = store.state.value.worklists.single()
        val before = snapshot(directory).readBytes()
        for (index in 0..1) assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE,
            store.admitContinuation(store.captureRequest(), listOf(old.selection(index)), DownloadHistoryDestination.FOLDER))
        assertArrayEquals(before, snapshot(directory).readBytes())
        store.releaseWorklist(token)
        store.releaseWorklist(token)
        store.awaitIdle()
        assertTrue(store.state.value.activeWorklistIds.isEmpty())
        assertArrayEquals(before, snapshot(directory).readBytes())
        val continued = store.continued(listOf(old.selection(1)))
        assertEquals(setOf(continued.worklistId, old.worklistId), store.state.value.continuationBlockedWorklistIds)
        assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE,
            store.admitContinuation(store.captureRequest(), listOf(old.selection(0)), DownloadHistoryDestination.FOLDER))
        store.releaseWorklist(continued)
        store.awaitIdle()
        assertTrue(store.state.value.continuationBlockedWorklistIds.isEmpty())
        assertNotNull(store.continued(listOf(old.selection(0))))
    }

    @Test fun releasedTokenCannotStartButItsLateReceiptCanFinishAndPendingOriginRemainsIneligible() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val token = store.admitted(listOf("ACTIVE.JPG", "QUEUED.JPG"))
        val receipt = store.started(token, 0)
        store.releaseWorklist(token)
        store.awaitIdle()
        val old = store.state.value.worklists.single()
        assertEquals(DownloadWorklistFailure.RELEASED_WORKLIST,
            (store.beginWorklistRow(token, 1) as DownloadWorklistRowStart.Rejected).reason)
        assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE,
            store.admitContinuation(store.captureRequest(), listOf(old.selection(0)), DownloadHistoryDestination.FOLDER))
        store.recordFinished(receipt, DownloadHistoryOutcome.CANCELLED, cleanupUnconfirmed = true)
        store.awaitIdle()
        assertEquals(DownloadHistoryOutcome.CANCELLED, store.state.value.worklists.single().rows[0].receipt!!.outcome)
        assertTrue(store.state.value.worklists.single().rows[0].receipt!!.cleanupUnconfirmed)
        store.continued(listOf(old.selection(0).copy(uncertaintyAcknowledged = true)))
        assertEquals(DownloadHistoryOutcome.CANCELLED, DownloadHistoryFileStorage(directory).readSnapshot().worklists.last().rows[0].receipt!!.outcome)
    }

    @Test fun continuationOfAContinuationBlocksItsAncestorsWithoutResolvingAnyOfThem() = runTest {
        val store = newStore(temporaryFolder.newFolder())
        val firstToken = store.admitted(listOf("FIRST.JPG"))
        store.releaseWorklist(firstToken)
        store.awaitIdle()
        val first = store.state.value.worklists.single()
        val secondToken = store.continued(listOf(first.selection(0, "SECOND.JPG")))
        store.releaseWorklist(secondToken)
        store.awaitIdle()
        val second = store.state.value.worklists.first()
        val thirdToken = store.continued(listOf(second.selection(0, "THIRD.JPG")))
        assertEquals(setOf(firstToken.worklistId, secondToken.worklistId, thirdToken.worklistId),
            store.state.value.continuationBlockedWorklistIds)
        assertEquals(first, store.state.value.worklists.last())
        assertEquals(second, store.state.value.worklists[1])
        assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE,
            store.admitContinuation(store.captureRequest(), listOf(first.selection(0)), DownloadHistoryDestination.FOLDER))
    }

    @Test fun completedDescendantMakesQueuedAncestorsUnavailableAfterReleaseAndRestart() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val firstToken = store.admitted(listOf("ORIGINAL.JPG"))
        store.releaseWorklist(firstToken)
        store.awaitIdle()
        val first = store.state.value.worklists.single()
        val secondToken = store.continued(listOf(first.selection(0, "SECOND.JPG")))
        store.releaseWorklist(secondToken)
        store.awaitIdle()
        val second = store.state.value.worklists.first()
        val thirdToken = store.continued(listOf(second.selection(0, "THIRD.JPG")))
        store.recordFinished(store.started(thirdToken, 0), DownloadHistoryOutcome.COMPLETED)
        store.releaseWorklist(thirdToken)
        store.awaitIdle()
        assertEquals(first, store.state.value.worklists.last())
        assertEquals(second, store.state.value.worklists[1])
        val before = snapshot(directory).readBytes()
        val reopened = newStore(directory)
        reopened.awaitIdle()
        for (candidate in listOf(first, second, reopened.state.value.worklists.first())) {
            assertEquals(DownloadContinuationStatus.UNAVAILABLE, reopened.state.value.continuationStatus(candidate.selection(0).origin))
            assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE, reopened.admitContinuation(reopened.captureRequest(),
                listOf(candidate.selection(0).copy(uncertaintyAcknowledged = true)), DownloadHistoryDestination.FOLDER))
        }
        assertArrayEquals(before, snapshot(directory).readBytes())
    }

    @Test fun activeAndReleasedInProgressDescendantsBlockQueuedAncestor() = runTest {
        val store = newStore(temporaryFolder.newFolder())
        val firstToken = store.admitted(listOf("ORIGINAL.JPG"))
        store.releaseWorklist(firstToken)
        store.awaitIdle()
        val first = store.state.value.worklists.single()
        val secondToken = store.continued(listOf(first.selection(0)))
        assertEquals(DownloadContinuationStatus.UNAVAILABLE, store.state.value.continuationStatus(first.selection(0).origin))
        val receipt = store.started(secondToken, 0)
        store.releaseWorklist(secondToken)
        store.awaitIdle()
        assertTrue(store.state.value.activeWorklistIds.isEmpty())
        assertEquals(DownloadContinuationStatus.UNAVAILABLE, store.state.value.continuationStatus(first.selection(0).origin))
        assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE, store.admitContinuation(store.captureRequest(),
            listOf(first.selection(0).copy(uncertaintyAcknowledged = true)), DownloadHistoryDestination.FOLDER))
        store.recordFinished(receipt, DownloadHistoryOutcome.FAILED)
        store.awaitIdle()
        assertEquals(DownloadContinuationStatus.AVAILABLE, store.state.value.continuationStatus(first.selection(0).origin))
        assertNotNull(store.continued(listOf(first.selection(0))))
    }

    @Test fun coldUnknownTransitiveDescendantRequiresFreshAcknowledgementEvenFromQueuedAncestor() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val firstToken = store.admitted(listOf("ORIGINAL.JPG"))
        store.releaseWorklist(firstToken)
        store.awaitIdle()
        val first = store.state.value.worklists.single()
        val secondToken = store.continued(listOf(first.selection(0, "SECOND.JPG")))
        store.releaseWorklist(secondToken)
        store.awaitIdle()
        val second = store.state.value.worklists.first()
        val thirdToken = store.continued(listOf(second.selection(0, "THIRD.JPG")))
        store.started(thirdToken, 0)
        val cold = newStore(directory)
        cold.awaitIdle()
        val before = snapshot(directory).readBytes()
        for (candidate in listOf(first, second, cold.state.value.worklists.first())) {
            assertEquals(DownloadContinuationStatus.UNCERTAIN, cold.state.value.continuationStatus(candidate.selection(0).origin))
            assertRejected(DownloadWorklistFailure.UNCERTAINTY_NOT_ACKNOWLEDGED,
                cold.admitContinuation(cold.captureRequest(), listOf(candidate.selection(0)), DownloadHistoryDestination.FOLDER))
        }
        assertArrayEquals(before, snapshot(directory).readBytes())
        val acknowledged = cold.continued(listOf(first.selection(0).copy(uncertaintyAcknowledged = true)))
        cold.releaseWorklist(acknowledged)
        cold.awaitIdle()
        assertEquals(first, cold.state.value.worklists.last())
        assertEquals(DownloadContinuationStatus.UNCERTAIN, cold.state.value.continuationStatus(first.selection(0).origin))
        assertRejected(DownloadWorklistFailure.UNCERTAINTY_NOT_ACKNOWLEDGED,
            cold.admitContinuation(cold.captureRequest(), listOf(first.selection(0)), DownloadHistoryDestination.FOLDER))
        assertFalse(snapshot(directory).readText().contains("uncertaintyAcknowledged"))
        val reopened = newStore(directory)
        reopened.awaitIdle()
        assertEquals(DownloadContinuationStatus.UNCERTAIN, reopened.state.value.continuationStatus(first.selection(0).origin))
    }

    @Test fun cleanupRiskDescendantNeedsAcknowledgementForItsOwnSelectedOrigin() = runTest {
        for (outcome in listOf(DownloadHistoryOutcome.CANCELLED, DownloadHistoryOutcome.FAILED)) {
            val directory = temporaryFolder.newFolder()
            val store = newStore(directory)
            val firstToken = store.admitted(listOf("SAME.JPG", "SAME.JPG"))
            store.releaseWorklist(firstToken)
            store.awaitIdle()
            val first = store.state.value.worklists.single()
            val secondToken = store.continued(listOf(first.selection(0)))
            store.recordFinished(store.started(secondToken, 0), outcome, cleanupUnconfirmed = true)
            store.releaseWorklist(secondToken)
            store.awaitIdle()
            assertEquals(DownloadContinuationStatus.UNCERTAIN, store.state.value.continuationStatus(first.selection(0).origin))
            assertEquals(DownloadContinuationStatus.AVAILABLE, store.state.value.continuationStatus(first.selection(1).origin))
            val before = snapshot(directory).readBytes()
            assertRejected(DownloadWorklistFailure.UNCERTAINTY_NOT_ACKNOWLEDGED,
                store.admitContinuation(store.captureRequest(), listOf(first.selection(0),
                    first.selection(1).copy(uncertaintyAcknowledged = true)), DownloadHistoryDestination.FOLDER))
            assertArrayEquals(before, snapshot(directory).readBytes())
            assertNotNull(store.continued(listOf(first.selection(0).copy(uncertaintyAcknowledged = true), first.selection(1))))
        }
    }

    @Test fun statusUsesOnlyLocalLinksAndNeverConflatesIdenticalFilenames() = runTest {
        val store = newStore(temporaryFolder.newFolder())
        val token = store.admitted(listOf("SAME.JPG", "SAME.JPG"))
        store.recordFinished(store.started(token, 1), DownloadHistoryOutcome.COMPLETED)
        store.releaseWorklist(token)
        store.awaitIdle()
        val first = store.state.value.worklists.single()
        assertEquals(DownloadContinuationStatus.AVAILABLE, store.state.value.continuationStatus(first.selection(0).origin))
        assertEquals(DownloadContinuationStatus.UNAVAILABLE, store.state.value.continuationStatus(first.selection(1).origin))
        assertEquals(DownloadContinuationStatus.UNAVAILABLE,
            store.state.value.continuationStatus(DownloadWorklistOrigin(first.worklistId, UUID.randomUUID().toString())))
        assertNotNull(store.continued(listOf(first.selection(0))))
    }

    @Test fun lateCleanupRiskIsRevalidatedBeforeActorAdmitsAncestorMapping() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val firstToken = store.admitted(listOf("ORIGINAL.JPG"))
        store.releaseWorklist(firstToken)
        store.awaitIdle()
        val first = store.state.value.worklists.single()
        val secondToken = store.continued(listOf(first.selection(0)))
        val receipt = store.started(secondToken, 0)
        store.releaseWorklist(secondToken)
        store.awaitIdle()
        store.recordFinished(receipt, DownloadHistoryOutcome.CANCELLED, cleanupUnconfirmed = true)
        assertRejected(DownloadWorklistFailure.UNCERTAINTY_NOT_ACKNOWLEDGED,
            store.admitContinuation(store.captureRequest(), listOf(first.selection(0)), DownloadHistoryDestination.FOLDER))
        assertEquals(2, store.state.value.worklists.size)
        assertEquals(DownloadContinuationStatus.UNCERTAIN, store.state.value.continuationStatus(first.selection(0).origin))
    }

    @Test fun releasedQueuedSiblingCannotBypassAnotherSiblingsActiveOrPendingAttempt() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val rootToken = store.admitted(listOf("ROOT.JPG"))
        store.releaseWorklist(rootToken)
        store.awaitIdle()
        val root = store.state.value.worklists.single()
        val firstToken = store.continued(listOf(root.selection(0, "FIRST.JPG")))
        store.releaseWorklist(firstToken)
        store.awaitIdle()
        val firstSibling = store.state.value.worklists.first()
        val secondToken = store.continued(listOf(root.selection(0, "SECOND.JPG")))
        assertEquals(DownloadContinuationStatus.UNAVAILABLE, store.state.value.continuationStatus(firstSibling.selection(0).origin))
        assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE, store.admitContinuation(store.captureRequest(),
            listOf(firstSibling.selection(0).copy(uncertaintyAcknowledged = true)), DownloadHistoryDestination.FOLDER))
        val receipt = store.started(secondToken, 0)
        store.releaseWorklist(secondToken)
        store.awaitIdle()
        assertTrue(store.state.value.activeWorklistIds.isEmpty())
        assertEquals(DownloadContinuationStatus.UNAVAILABLE, store.state.value.continuationStatus(firstSibling.selection(0).origin))
        assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE, store.admitContinuation(store.captureRequest(),
            listOf(firstSibling.selection(0)), DownloadHistoryDestination.FOLDER))
        store.recordFinished(receipt, DownloadHistoryOutcome.FAILED)
        store.awaitIdle()
        assertEquals(DownloadContinuationStatus.AVAILABLE, store.state.value.continuationStatus(firstSibling.selection(0).origin))
        assertNotNull(store.continued(listOf(firstSibling.selection(0))))
    }

    @Test fun siblingCompletionAndOutputUncertaintyRemainFamilyWideAfterRestart() = runTest {
        for (outcome in listOf(DownloadHistoryOutcome.COMPLETED, DownloadHistoryOutcome.UNCONFIRMED,
                DownloadHistoryOutcome.FAILED, DownloadHistoryOutcome.CANCELLED)) {
            val directory = temporaryFolder.newFolder()
            val store = newStore(directory)
            val rootToken = store.admitted(listOf("ROOT.JPG"))
            store.releaseWorklist(rootToken)
            store.awaitIdle()
            val root = store.state.value.worklists.single()
            val firstToken = store.continued(listOf(root.selection(0, "FIRST.JPG")))
            store.releaseWorklist(firstToken)
            store.awaitIdle()
            val firstSibling = store.state.value.worklists.first()
            val secondToken = store.continued(listOf(root.selection(0, "SECOND.JPG")))
            val receipt = store.started(secondToken, 0)
            if (outcome != DownloadHistoryOutcome.UNCONFIRMED) {
                store.recordFinished(receipt, outcome, cleanupUnconfirmed = outcome != DownloadHistoryOutcome.COMPLETED)
            }
            store.releaseWorklist(secondToken)
            store.awaitIdle()
            val cold = newStore(directory)
            cold.awaitIdle()
            val expectedStatus = if (outcome == DownloadHistoryOutcome.COMPLETED) DownloadContinuationStatus.UNAVAILABLE
                else DownloadContinuationStatus.UNCERTAIN
            val expectedFailure = if (outcome == DownloadHistoryOutcome.COMPLETED) DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE
                else DownloadWorklistFailure.UNCERTAINTY_NOT_ACKNOWLEDGED
            assertEquals(expectedStatus, cold.state.value.continuationStatus(firstSibling.selection(0).origin))
            val before = snapshot(directory).readBytes()
            assertRejected(expectedFailure,
                cold.admitContinuation(cold.captureRequest(), listOf(firstSibling.selection(0)), DownloadHistoryDestination.FOLDER))
            assertArrayEquals(before, snapshot(directory).readBytes())
            val acknowledged = listOf(firstSibling.selection(0).copy(uncertaintyAcknowledged = true))
            if (outcome == DownloadHistoryOutcome.COMPLETED) {
                assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE,
                    cold.admitContinuation(cold.captureRequest(), acknowledged, DownloadHistoryDestination.FOLDER))
            } else assertNotNull(cold.continued(acknowledged))
            assertEquals(firstSibling, cold.state.value.worklists.find { it.worklistId == firstSibling.worklistId })
        }
    }

    @Test fun oneAdmissionCannotSelectMultipleOriginsFromTheSameAssociationFamily() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val rootToken = store.admitted(listOf("ROOT.JPG"))
        store.releaseWorklist(rootToken)
        store.awaitIdle()
        val root = store.state.value.worklists.single()
        val firstToken = store.continued(listOf(root.selection(0, "FIRST.JPG")))
        store.releaseWorklist(firstToken)
        store.awaitIdle()
        val first = store.state.value.worklists.first()
        val secondToken = store.continued(listOf(root.selection(0, "SECOND.JPG")))
        store.releaseWorklist(secondToken)
        store.awaitIdle()
        val second = store.state.value.worklists.first()
        val before = snapshot(directory).readBytes()
        for (selections in listOf(listOf(root.selection(0), first.selection(0)), listOf(first.selection(0), second.selection(0)))) {
            assertRejected(DownloadWorklistFailure.DUPLICATE_ORIGIN,
                store.admitContinuation(store.captureRequest(), selections, DownloadHistoryDestination.FOLDER))
        }
        assertArrayEquals(before, snapshot(directory).readBytes())
        assertNotNull(store.continued(listOf(first.selection(0))))
    }

    @Test fun fullHundredRowFamilyResolvesWithoutRecursionAndMalformedReferencesFailClosed() {
        val planIds = List(100) { UUID.randomUUID().toString() }
        val rowIds = List(100) { UUID.randomUUID().toString() }
        val oldestFirst = List(100) { index ->
            DownloadWorklist(planIds[index], DownloadHistoryDestination.FOLDER, 1000L,
                listOf(DownloadWorklistRow(rowIds[index], "SYNTHETIC.JPG", origin = if (index == 0) null else
                    DownloadWorklistOrigin(planIds[index - 1], rowIds[index - 1]))))
        }
        val state = DownloadHistoryState(loading = false, writable = true, worklists = oldestFirst.reversed())
        val newest = DownloadWorklistOrigin(planIds.last(), rowIds.last())
        assertEquals(DownloadContinuationStatus.AVAILABLE, state.continuationStatus(newest))
        val activeAncestor = state.copy(activeWorklistIds = setOf(planIds[50]))
        assertEquals(DownloadContinuationStatus.UNAVAILABLE, activeAncestor.continuationStatus(newest))
        val dangling = state.copy(worklists = state.worklists.dropLast(1))
        assertEquals(DownloadContinuationStatus.UNAVAILABLE, dangling.continuationStatus(newest))
        val oldest = oldestFirst.first()
        val cycle = state.copy(worklists = state.worklists.dropLast(1) + oldest.copy(rows = listOf(oldest.rows.single().copy(origin = newest))))
        assertEquals(DownloadContinuationStatus.UNAVAILABLE, cycle.continuationStatus(newest))
        val overflow = state.copy(worklists = state.worklists + DownloadWorklist(UUID.randomUUID().toString(),
            DownloadHistoryDestination.FOLDER, 1000L, listOf(DownloadWorklistRow(UUID.randomUUID().toString(), "EXTRA.JPG"))))
        assertEquals(DownloadContinuationStatus.UNAVAILABLE, overflow.continuationStatus(newest))
    }

    @Test fun missingCompletedDuplicateAndEmptyOriginsAreRejectedWithoutDiskMutation() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val token = store.admitted(listOf("DONE.JPG", "QUEUED.JPG"))
        store.recordFinished(store.started(token, 0), DownloadHistoryOutcome.COMPLETED)
        store.releaseWorklist(token)
        store.awaitIdle()
        val old = store.state.value.worklists.single()
        val before = snapshot(directory).readBytes()
        val invalid = listOf(
            old.selection(0),
            old.selection(1).copy(origin = DownloadWorklistOrigin(UUID.randomUUID().toString(), old.rows[1].rowId)),
            old.selection(1).copy(origin = DownloadWorklistOrigin(old.worklistId, UUID.randomUUID().toString())),
            old.selection(1).copy(origin = DownloadWorklistOrigin("content://synthetic-private", old.rows[1].rowId)),
        )
        for (selection in invalid) assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE,
            store.admitContinuation(store.captureRequest(), listOf(selection), DownloadHistoryDestination.GALLERY))
        assertRejected(DownloadWorklistFailure.DUPLICATE_ORIGIN, store.admitContinuation(store.captureRequest(),
            listOf(old.selection(1, "FIRST.JPG"), old.selection(1, "SECOND.JPG")), DownloadHistoryDestination.FOLDER))
        assertRejected(DownloadWorklistFailure.INVALID_SELECTION,
            store.admitContinuation(store.captureRequest(), emptyList(), DownloadHistoryDestination.GALLERY))
        assertRejected(DownloadWorklistFailure.CAPACITY, store.admitContinuation(store.captureRequest(),
            List(101) { old.selection(1) }, DownloadHistoryDestination.FOLDER))
        assertArrayEquals(before, snapshot(directory).readBytes())
        assertEquals(listOf(old), store.state.value.worklists)
    }

    @Test fun requestsFromAnotherStoreAndClearedEpochCannotAdmitContinuation() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val token = store.admitted(listOf("OLD.JPG"))
        store.releaseWorklist(token)
        store.awaitIdle()
        val selection = store.state.value.worklists.single().selection(0)
        val other = newStore(temporaryFolder.newFolder())
        assertRejected(DownloadWorklistFailure.INVALID_REQUEST,
            store.admitContinuation(other.captureRequest(), listOf(selection), DownloadHistoryDestination.FOLDER))
        val request = store.captureRequest()
        assertTrue(store.clear())
        assertRejected(DownloadWorklistFailure.RETIRED_REQUEST,
            store.admitContinuation(request, listOf(selection), DownloadHistoryDestination.FOLDER))
        assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE,
            store.admitContinuation(store.captureRequest(), listOf(selection), DownloadHistoryDestination.FOLDER))
        store.releaseWorklist(token)
        store.awaitIdle()
        assertTrue(store.state.value.worklists.isEmpty())
        assertTrue(store.state.value.activeWorklistIds.isEmpty())
        assertEquals(DownloadHistorySnapshot(), DownloadHistoryFileStorage(directory).readSnapshot())
    }

    @Test fun continuationQueuedDuringClearIsRevalidatedAfterEpochRetirement() = runTest {
        val directory = temporaryFolder.newFolder()
        var onMove: (() -> Unit)? = null
        val store = newStore(directory, beforeWrite = { if (it == DownloadHistoryWritePhase.BEFORE_MOVE) onMove?.invoke() })
        val token = store.admitted(listOf("OLD.JPG"))
        store.releaseWorklist(token)
        store.awaitIdle()
        val selection = store.state.value.worklists.single().selection(0)
        var queued: kotlinx.coroutines.Deferred<DownloadWorklistAdmission>? = null
        onMove = {
            onMove = null
            queued = async(start = CoroutineStart.UNDISPATCHED) {
                store.admitContinuation(store.captureRequest(), listOf(selection), DownloadHistoryDestination.FOLDER)
            }
        }
        assertTrue(store.clear())
        assertRejected(DownloadWorklistFailure.RETIRED_REQUEST, queued!!.await())
        assertTrue(store.state.value.worklists.isEmpty())
    }

    @Test fun originCompletionQueuedBeforeAdmissionIsRevalidatedByActor() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val token = store.admitted(listOf("OLD.JPG"))
        val receipt = store.started(token, 0)
        store.releaseWorklist(token)
        store.awaitIdle()
        val selection = store.state.value.worklists.single().selection(0)
        store.recordFinished(receipt, DownloadHistoryOutcome.COMPLETED)
        assertRejected(DownloadWorklistFailure.ORIGIN_NOT_ELIGIBLE,
            store.admitContinuation(store.captureRequest(), listOf(selection), DownloadHistoryDestination.FOLDER))
        assertEquals(1, store.state.value.worklists.size)
        assertEquals(DownloadHistoryOutcome.COMPLETED, store.state.value.worklists.single().rows.single().receipt!!.outcome)
    }

    @Test fun hundredRowCapAdmitsCompleteSubsetAndRejectsNextWithoutEvictionOrTruncation() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val token = store.admitted(List(50) { "OLD_$it.JPG" })
        store.releaseWorklist(token)
        store.awaitIdle()
        val old = store.state.value.worklists.single()
        val continued = store.continued(old.rows.indices.map { old.selection(it, "NEW_$it.JPG") })
        store.releaseWorklist(continued)
        store.awaitIdle()
        val before = snapshot(directory).readBytes()
        assertEquals(100, store.state.value.worklists.sumOf { it.rows.size })
        assertRejected(DownloadWorklistFailure.CAPACITY,
            store.admitContinuation(store.captureRequest(), listOf(old.selection(0)), DownloadHistoryDestination.GALLERY))
        assertArrayEquals(before, snapshot(directory).readBytes())
        assertEquals(old, store.state.value.worklists.last())
    }

    @Test fun everyContinuationWriteFaultPreservesOriginsAndNeverLeaksAnActiveCapability() = runTest {
        for (phase in DownloadHistoryWritePhase.entries) {
            val directory = temporaryFolder.newFolder()
            var fail = false
            val store = newStore(directory, beforeWrite = { if (fail && it == phase) throw IOException("synthetic-private") })
            val token = store.admitted(listOf("FIRST.JPG", "SECOND.JPG"))
            store.releaseWorklist(token)
            store.awaitIdle()
            val old = store.state.value.worklists.single()
            val before = snapshot(directory).readBytes()
            fail = true
            val result = store.admitContinuation(store.captureRequest(), listOf(old.selection(0)), DownloadHistoryDestination.GALLERY)
                as DownloadWorklistAdmission.Rejected
            assertEquals(DownloadWorklistFailure.WRITE_FAILED, result.reason)
            assertEquals(phase != DownloadHistoryWritePhase.AFTER_MOVE, result.planAbsent)
            assertEquals(old, store.state.value.worklists.last())
            assertTrue(store.state.value.activeWorklistIds.isEmpty())
            assertTrue(store.state.value.continuationBlockedWorklistIds.isEmpty())
            if (phase == DownloadHistoryWritePhase.AFTER_MOVE) {
                assertEquals(2, store.state.value.worklists.size)
                assertNull(store.state.value.worklists.first().rows.single().receipt)
                assertEquals(old.selection(0).origin, store.state.value.worklists.first().rows.single().origin)
            } else assertArrayEquals(before, snapshot(directory).readBytes())
            assertEquals(listOf(DOWNLOAD_HISTORY_SNAPSHOT_NAME), directory.list()!!.toList())
        }
    }

    @Test fun ambiguousMoveCannotClaimContinuationAbsentEvenWhenReadbackIsOld() = runTest {
        val directory = temporaryFolder.newFolder()
        val initial = newStore(directory)
        initial.admitted(listOf("OLD.JPG"))
        val storage = DownloadHistoryFileStorage.withAtomicMoveForTest(directory) { _, _ -> throw IOException("unknown-move") }
        val store = DownloadHistoryStore(storage, backgroundScope, StandardTestDispatcher(testScheduler), { 1000L })
        store.awaitIdle()
        val selection = store.state.value.worklists.single().selection(0)
        val result = store.admitContinuation(store.captureRequest(), listOf(selection), DownloadHistoryDestination.FOLDER)
            as DownloadWorklistAdmission.Rejected
        assertEquals(DownloadWorklistFailure.WRITE_FAILED, result.reason)
        assertFalse(result.planAbsent)
        assertEquals(1, store.state.value.worklists.size)
        assertTrue(store.state.value.activeWorklistIds.isEmpty())
    }

    @Test fun unreadableInstalledContinuationFailsClosedWithoutChangingOldVisibleRows() = runTest {
        val directory = temporaryFolder.newFolder()
        var fail = false
        val store = newStore(directory, beforeWrite = {
            if (fail && it == DownloadHistoryWritePhase.AFTER_MOVE) {
                snapshot(directory).writeText("{synthetic-unknown")
                throw IOException("synthetic-installation-unknown")
            }
        })
        val token = store.admitted(listOf("OLD.JPG"))
        store.releaseWorklist(token)
        store.awaitIdle()
        val old = store.state.value.worklists.single()
        fail = true
        val result = store.admitContinuation(store.captureRequest(), listOf(old.selection(0)), DownloadHistoryDestination.FOLDER)
            as DownloadWorklistAdmission.Rejected
        assertFalse(result.planAbsent)
        assertFalse(store.state.value.writable)
        assertEquals(listOf(old), store.state.value.worklists)
        assertRejected(DownloadWorklistFailure.STORAGE_UNAVAILABLE,
            store.admitContinuation(store.captureRequest(), listOf(old.selection(0)), DownloadHistoryDestination.FOLDER))
        assertEquals("{synthetic-unknown", snapshot(directory).readText())
    }

    @Test fun stoppedStoreReleasesQueuedContinuationAndClearsProcessOwnership() = runTest {
        val directory = temporaryFolder.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val store = DownloadHistoryStore(directory, owner, dispatcher)
        val token = store.admitted(listOf("OLD.JPG"))
        val selection = store.state.value.worklists.single().selection(0)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            store.admitContinuation(store.captureRequest(), listOf(selection), DownloadHistoryDestination.FOLDER)
        }
        try {
            owner.cancel()
            runCurrent()
            assertTrue("Cancelled receiver must settle its already-delivered command", pending.isCompleted)
            assertRejected(DownloadWorklistFailure.STOPPED, pending.await())
            assertTrue(store.state.value.activeWorklistIds.isEmpty())
            assertTrue(store.state.value.continuationBlockedWorklistIds.isEmpty())
            store.releaseWorklist(token)
        } finally {
            owner.cancel()
            pending.cancel()
            runCurrent()
        }
    }

    @Test fun schemaThreeLoadsWithoutRewritingAndMigratesOnExplicitContinuation() = runTest {
        val directory = temporaryFolder.newFolder()
        newStore(directory).admitted(listOf("OLD.JPG"))
        val fixture = JSONObject(snapshot(directory).readText()).put("schema", 3).toString()
        snapshot(directory).writeText(fixture)
        val store = newStore(directory)
        store.awaitIdle()
        assertEquals(fixture, snapshot(directory).readText())
        val old = store.state.value.worklists.single()
        store.continued(listOf(old.selection(0, "NEW.JPG")))
        assertEquals(4, JSONObject(snapshot(directory).readText()).getInt("schema"))
        assertEquals(old, DownloadHistoryFileStorage(directory).readSnapshot().worklists.last())
    }

    @Test fun schemaFourRejectsMalformedIdentityExpandedDanglingCyclicAndDuplicateOriginsWithoutRewrite() = runTest {
        val seed = temporaryFolder.newFolder()
        val store = newStore(seed)
        val token = store.admitted(listOf("OLD.JPG", "OTHER.JPG"))
        store.releaseWorklist(token)
        store.awaitIdle()
        val old = store.state.value.worklists.single()
        store.continued(listOf(old.selection(0), old.selection(1)))
        val valid = snapshot(seed).readText()
        val variants = listOf<(JSONObject) -> Unit>(
            { it.put("schema", 5) },
            { it.put("schema", 3) },
            { it.row().put("origin", JSONObject.NULL) },
            { it.row().put("origin", "content://synthetic-private") },
            { it.row().put("uncertaintyAcknowledged", true) },
            { it.row().getJSONObject("origin").put("mediaId", "synthetic-id") },
            { it.row().getJSONObject("origin").put("cameraId", "synthetic-camera") },
            { it.row().getJSONObject("origin").put("destinationUri", "content://synthetic-private") },
            { it.row().getJSONObject("origin").remove("rowId") },
            { it.row().getJSONObject("origin").put("rowId", 1) },
            { it.row().getJSONObject("origin").put("rowId", "1-1-1-1-1") },
            { it.row().getJSONObject("origin").put("rowId", UUID.randomUUID().toString()) },
            { it.row().getJSONObject("origin").put("worklistId", UUID.randomUUID().toString()) },
            { it.row().getJSONObject("origin").put("worklistId", "F2B2DFB2-26F0-42D0-A6A4-F97D3110C70A") },
            { it.row().getJSONObject("origin").put("worklistId", it.plan().getString("worklistId"))
                .put("rowId", it.row().getString("rowId")) },
            { it.plan().getJSONArray("rows").getJSONObject(1).put("origin", JSONObject(it.row().getJSONObject("origin").toString())) },
            { it.getJSONArray("worklists").getJSONObject(1).getJSONArray("rows").getJSONObject(0).put("origin",
                JSONObject().put("worklistId", it.plan().getString("worklistId")).put("rowId", it.row().getString("rowId"))) },
        )
        for (mutate in variants) {
            val directory = temporaryFolder.newFolder()
            val bytes = JSONObject(valid).apply(mutate).toString().toByteArray()
            snapshot(directory).writeBytes(bytes)
            val invalid = newStore(directory)
            invalid.awaitIdle()
            assertEquals(String(bytes), DownloadHistoryWarning.READ_FAILED, invalid.state.value.warning)
            assertFalse(invalid.state.value.writable)
            assertArrayEquals(bytes, snapshot(directory).readBytes())
        }
    }

    @Test fun schemaFourKeepsStrictDuplicateKeyGrammarAndByteBound() = runTest {
        val seed = temporaryFolder.newFolder()
        val store = newStore(seed)
        val token = store.admitted(listOf("OLD.JPG"))
        store.releaseWorklist(token)
        store.awaitIdle()
        store.continued(listOf(store.state.value.worklists.single().selection(0)))
        val valid = snapshot(seed).readText()
        val origin = JSONObject(valid).row().getJSONObject("origin")
        val duplicate = origin.toString().dropLast(1) + ",\"rowId\":\"${origin.getString("rowId")}\"}"
        val inputs = listOf(valid.replace(origin.toString(), duplicate).toByteArray(),
            valid.toByteArray() + ByteArray(MAX_DOWNLOAD_HISTORY_SNAPSHOT_BYTES) { ' '.code.toByte() })
        for (bytes in inputs) {
            val directory = temporaryFolder.newFolder()
            snapshot(directory).writeBytes(bytes)
            val invalid = newStore(directory)
            invalid.awaitIdle()
            assertEquals(DownloadHistoryWarning.READ_FAILED, invalid.state.value.warning)
            assertArrayEquals(bytes, snapshot(directory).readBytes())
        }
    }

    @Test fun continuationPersistsOnlyLocalAssociationAndSanitizedNewLabels() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = newStore(directory)
        val token = store.admitted(listOf("ORIGINAL.JPG", "ORIGINAL.JPG", "ORIGINAL.JPG"))
        store.releaseWorklist(token)
        store.awaitIdle()
        val old = store.state.value.worklists.single()
        store.continued(listOf(
            old.selection(2, "https://SYNTHETIC-CAMERA/file?token=SYNTHETIC-SECRET"),
            old.selection(0, "/private/SYNTHETIC-USER/NEW.CR3"),
            old.selection(1, "NEW.CR3"),
        ), DownloadHistoryDestination.DOCUMENT)
        val plans = store.state.value.worklists
        assertEquals(listOf("download", "NEW.CR3", "NEW.CR3"), plans.first().rows.map { it.filename })
        assertEquals(listOf(2, 0, 1).map { old.rows[it].rowId }, plans.first().rows.map { it.origin!!.rowId })
        val root = JSONObject(snapshot(directory).readText())
        assertEquals(setOf("schema", "records", "worklists"), root.keys().asSequence().toSet())
        assertEquals(setOf("rowId", "filename", "receipt", "origin"), root.row().keys().asSequence().toSet())
        assertEquals(setOf("worklistId", "rowId"), root.row().getJSONObject("origin").keys().asSequence().toSet())
        val json = root.toString()
        for (privateValue in listOf("SYNTHETIC", "https://", "content://", "token=", "mediaId", "sessionId", "cameraId", "activeWorklist", "continuationBlocked")) {
            assertFalse(privateValue, json.contains(privateValue))
        }
        for (plan in plans) {
            assertEquals(4, UUID.fromString(plan.worklistId).version())
            plan.rows.forEach { assertEquals(4, UUID.fromString(it.rowId).version()) }
        }
        assertEquals(old, plans.last())
    }

    private fun assertRejected(reason: DownloadWorklistFailure, value: DownloadWorklistAdmission) {
        val rejected = value as DownloadWorklistAdmission.Rejected
        assertEquals(reason, rejected.reason)
        assertTrue(rejected.planAbsent)
    }

    private fun DownloadWorklist.selection(index: Int, filename: String = "EXPLICIT_NEW.JPG") =
        DownloadWorklistContinuationSelection(DownloadWorklistOrigin(worklistId, rows[index].rowId), filename)

    private suspend fun DownloadHistoryStore.admitted(filenames: List<String>) =
        (admitWorklist(captureRequest(), filenames, DownloadHistoryDestination.FOLDER) as DownloadWorklistAdmission.Admitted).token

    private suspend fun DownloadHistoryStore.continued(selections: List<DownloadWorklistContinuationSelection>,
        destination: DownloadHistoryDestination = DownloadHistoryDestination.FOLDER) =
        (admitContinuation(captureRequest(), selections, destination) as DownloadWorklistAdmission.Admitted).token

    private suspend fun DownloadHistoryStore.started(token: DownloadWorklistToken, index: Int) =
        (beginWorklistRow(token, index) as DownloadWorklistRowStart.Started).receipt

    private fun TestScope.newStore(directory: File, beforeWrite: (DownloadHistoryWritePhase) -> Unit = {}) =
        DownloadHistoryStore(DownloadHistoryFileStorage(directory, beforeWrite), backgroundScope,
            StandardTestDispatcher(testScheduler), { 1000L })

    private fun snapshot(directory: File) = File(directory, DOWNLOAD_HISTORY_SNAPSHOT_NAME)
    private fun JSONObject.plan() = getJSONArray("worklists").getJSONObject(0)
    private fun JSONObject.row() = plan().getJSONArray("rows").getJSONObject(0)
}
