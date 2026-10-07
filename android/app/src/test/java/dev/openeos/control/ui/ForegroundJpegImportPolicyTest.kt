package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import org.junit.Assert.*
import org.junit.Test

class ForegroundJpegImportPolicyTest {
    private fun jpeg(id: String, size: Long? = 100L, name: String = "$id.JPG") =
        CameraMediaItem(id, name, "image", sizeBytes = size, contentType = "image/jpeg")

    private fun armed(vararg baseline: CameraMediaItem, limits: ForegroundImportLimits = ForegroundImportLimits()) =
        ForegroundJpegImportPolicy(limits).apply { acceptBaseline(baseline.toList(), complete = true) }

    private fun stable(policy: ForegroundJpegImportPolicy, items: List<CameraMediaItem>, hasMore: Boolean = false) {
        policy.observe(items, hasMore, 0L)
        policy.observe(items, hasMore, 1_000L)
    }

    @Test fun completeBaselineNeverBackfillsOldJpegsEvenAfterTheyDisappearAndReturn() {
        val old = jpeg("old")
        val policy = armed(old)
        stable(policy, emptyList())
        stable(policy, listOf(old.copy(sizeBytes = 200L)))
        assertEquals(ForegroundImportPhase.WATCHING, policy.status.phase)
        assertNull(policy.claimNext())
        assertEquals(0, policy.status.completedCount)
        assertEquals(1, policy.status.knownCount)
    }

    @Test fun incompleteAndOverCapacityBaselinesCannotArm() {
        val incomplete = ForegroundJpegImportPolicy()
        incomplete.acceptBaseline(listOf(jpeg("old")), complete = false)
        assertEquals(ForegroundImportStopReason.INCOMPLETE_BASELINE, incomplete.status.stopReason)
        assertNull(incomplete.claimNext())
        val capped = ForegroundJpegImportPolicy(ForegroundImportLimits(baselineItems = 2))
        capped.acceptBaseline(listOf(jpeg("a"), jpeg("b"), jpeg("c")), complete = true)
        assertEquals(ForegroundImportStopReason.BASELINE_LIMIT, capped.status.stopReason)
        assertEquals(0, capped.status.knownCount)
    }

    @Test fun completeEmptyBaselineCanImportNewJpegButNeverClaimsShutterIdentity() {
        val policy = armed()
        val item = jpeg("new")
        stable(policy, listOf(item))
        assertEquals(item, policy.claimNext())
        policy.finishTransfer(published = true)
        assertEquals(1, policy.status.completedCount)
        assertEquals(ForegroundImportPhase.WATCHING, policy.status.phase)
    }

    @Test fun duplicateSnapshotsAndRepeatedIdsCreateOnlyOneOriginalTransfer() {
        val policy = armed()
        val item = jpeg("new")
        stable(policy, listOf(item, item))
        assertEquals(item, policy.claimNext())
        policy.finishTransfer(published = true)
        policy.observe(listOf(item), hasMore = false, observedAtMillis = 2_000L)
        assertNull(policy.claimNext())
        assertEquals(1, policy.status.completedCount)
    }

    @Test fun rawJpegPairImportsOnlyJpegAndEqualNamesRemainDistinctIds() {
        val policy = armed()
        val first = jpeg("card-a", name = "SAME.JPG")
        val second = jpeg("card-b", name = "SAME.JPG")
        val raw = CameraMediaItem("raw", "SAME.CR3", "raw", sizeBytes = 500L)
        stable(policy, listOf(raw, first, second))
        assertEquals(first, policy.claimNext())
        policy.finishTransfer(published = true)
        assertEquals(second, policy.claimNext())
        policy.finishTransfer(published = true)
        assertNull(policy.claimNext())
        assertEquals(2, policy.status.completedCount)
        assertEquals(3, policy.status.knownCount)
    }

    @Test fun jpegSuffixIsCaseInsensitiveButMimeAndVideoContradictionsAreNotImported() {
        val policy = armed()
        val jpeg = jpeg("valid", name = "PHOTO.JpEg")
        stable(policy, listOf(jpeg, jpeg("video").copy(kind = "video"), jpeg("mime").copy(contentType = "image/png")))
        assertEquals(jpeg, policy.claimNext())
        policy.finishTransfer(published = true)
        assertNull(policy.claimNext())
    }

    @Test fun unknownZeroAndGrowingSizesWaitForTwoStablePositiveObservations() {
        val policy = armed()
        policy.observe(listOf(jpeg("new", null)), false, 0L)
        policy.observe(listOf(jpeg("new", 0L)), false, 1_000L)
        policy.observe(listOf(jpeg("new", 100L)), false, 2_000L)
        assertNull(policy.claimNext())
        policy.observe(listOf(jpeg("new", 200L)), false, 3_000L)
        policy.observe(listOf(jpeg("new", 200L)), false, 3_999L)
        assertNull(policy.claimNext())
        policy.observe(listOf(jpeg("new", 200L)), false, 4_000L)
        assertEquals(200L, policy.claimNext()?.sizeBytes)
    }

    @Test fun oneObservationCannotBecomeReadyJustBecauseTimePassed() {
        val policy = armed()
        policy.observe(listOf(jpeg("new")), false, 0L)
        assertNull(policy.claimNext())
        assertEquals(1, policy.status.pendingCount)
    }

    @Test fun incompleteDiscoveryStopsBeforeAdmittingNewCandidates() {
        val policy = armed(jpeg("old"))
        val candidate = jpeg("new")
        policy.observe(listOf(candidate), true, 0L)
        assertTrue(policy.status.discoveryIncomplete)
        assertEquals(ForegroundImportStopReason.INCOMPLETE_SCAN, policy.status.stopReason)
        assertEquals(0, policy.status.pendingCount)
        assertEquals(1, policy.status.knownCount)
        assertNull(policy.claimNext())
    }

    @Test fun inventoryCacheCannotEstablishFreshStableSize() {
        val policy = armed()
        val item = jpeg("new")
        policy.observeInventory(listOf(item), true, 0L)
        policy.observeInventory(listOf(item), true, 2_000L)
        assertNull(policy.claimNext())
        val requested = policy.pendingMetadataItems().single()
        assertNull(requested.sizeBytes)
        policy.acceptMetadata(requested, item, 2_000L)
        assertNull(policy.claimNext())
        policy.observeInventory(listOf(item.copy(sizeBytes = 1L)), true, 3_000L)
        policy.acceptMetadata(requested, item, 3_000L)
        assertEquals(100L, policy.claimNext()?.sizeBytes)
    }

    @Test fun unknownFreshMetadataClearsPreviousPositiveStability() {
        val policy = armed()
        val item = jpeg("new")
        policy.observeInventory(listOf(item), true, 0L)
        policy.acceptMetadata(item, item, 0L)
        policy.acceptMetadata(item, item.copy(sizeBytes = null), 1_000L)
        assertNull(policy.claimNext())
        policy.acceptMetadata(item, item, 2_000L)
        assertNull(policy.claimNext())
        policy.acceptMetadata(item, item, 3_000L)
        assertEquals(item, policy.claimNext())
    }

    @Test fun metadataForWrongReturnedIdStopsWithoutAcceptingBytes() {
        val policy = armed()
        val item = jpeg("new")
        policy.observeInventory(listOf(item), true, 0L)
        policy.acceptMetadata(item, item.copy(id = "other"), 1_000L)
        assertEquals(ForegroundImportStopReason.INVALID_SOURCE, policy.status.stopReason)
        assertNull(policy.claimNext())
    }

    @Test fun queueOverflowStopsBeforeAdmittingAPartialNewBatch() {
        val policy = armed(limits = ForegroundImportLimits(queuedItems = 2))
        policy.observe(listOf(jpeg("a"), jpeg("b"), jpeg("c")), false, 0L)
        assertEquals(ForegroundImportStopReason.QUEUE_LIMIT, policy.status.stopReason)
        assertEquals(0, policy.status.pendingCount)
        assertEquals(0, policy.status.knownCount)
        assertNull(policy.claimNext())
    }

    @Test fun identityOverflowStopsWithoutForgettingOldIds() {
        val policy = armed(jpeg("old"), limits = ForegroundImportLimits(baselineItems = 2, knownItems = 2))
        policy.observe(listOf(jpeg("a"), jpeg("b")), false, 0L)
        assertEquals(ForegroundImportStopReason.IDENTITY_LIMIT, policy.status.stopReason)
        assertEquals(1, policy.status.knownCount)
        assertNull(policy.claimNext())
    }

    @Test fun conflictingDuplicateIdentityStopsInsteadOfSelectingArbitraryBytes() {
        val policy = armed()
        policy.observe(listOf(jpeg("same", 100L), jpeg("same", 200L)), false, 0L)
        assertEquals(ForegroundImportStopReason.INVALID_SOURCE, policy.status.stopReason)
        assertNull(policy.claimNext())
    }

    @Test fun queuedIdentityCannotChangeItsNameToAnotherFile() {
        val policy = armed()
        policy.observe(listOf(jpeg("id", name = "FIRST.JPG")), false, 0L)
        policy.observe(listOf(jpeg("id", name = "SECOND.JPG")), false, 1_000L)
        assertEquals(ForegroundImportStopReason.INVALID_SOURCE, policy.status.stopReason)
        assertNull(policy.claimNext())
    }

    @Test fun stopDiscardsOnlyNotStartedWorkAndWaitsForActiveOwnership() {
        val policy = armed()
        stable(policy, listOf(jpeg("a"), jpeg("b")))
        assertEquals("a", policy.claimNext()?.id)
        policy.requestStop(ForegroundImportStopReason.USER)
        assertEquals(ForegroundImportPhase.STOPPING, policy.status.phase)
        assertEquals(1, policy.status.discardedCount)
        assertFalse(policy.finishStop())
        assertNull(policy.claimNext())
        policy.finishTransfer(published = false)
        assertTrue(policy.finishStop())
        assertEquals(ForegroundImportPhase.STOPPED, policy.status.phase)
        assertEquals(0, policy.status.completedCount)
    }

    @Test fun publicationBeforeStopKeepsItsSuccessfulReceipt() {
        val policy = armed()
        stable(policy, listOf(jpeg("a")))
        policy.claimNext()
        policy.requestStop(ForegroundImportStopReason.BACKGROUND)
        policy.finishTransfer(published = true)
        assertTrue(policy.finishStop())
        assertEquals(1, policy.status.completedCount)
        assertEquals(ForegroundImportStopReason.BACKGROUND, policy.status.stopReason)
    }

    @Test fun failedTransferOrUnconfirmedCleanupCannotAutomaticallyRetry() {
        for (cleanupConfirmed in listOf(true, false)) {
            val policy = armed()
            val item = jpeg("a")
            stable(policy, listOf(item))
            policy.claimNext()
            policy.finishTransfer(published = false, failed = true, cleanupConfirmed = cleanupConfirmed)
            assertEquals(if (cleanupConfirmed) ForegroundImportStopReason.TRANSFER_FAILED
                else ForegroundImportStopReason.CLEANUP_UNCONFIRMED, policy.status.stopReason)
            policy.finishStop()
            policy.observe(listOf(item), false, 2_000L)
            assertNull(policy.claimNext())
            assertEquals(0, policy.status.completedCount)
        }
    }

    @Test fun lateSnapshotsAfterSessionStopCannotCreateAnotherQueue() {
        val policy = armed()
        policy.requestStop(ForegroundImportStopReason.SESSION_CHANGED)
        policy.finishStop()
        stable(policy, listOf(jpeg("late")))
        assertNull(policy.claimNext())
        assertEquals(0, policy.status.knownCount)
        assertEquals(ForegroundImportStopReason.SESSION_CHANGED, policy.status.stopReason)
    }
}
