package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** Production suspend policy, actual coroutines; no camera, command or Android API. */
class CaptureReviewEvidenceTest {
    private val old = CameraMediaItem("OLD", "OLD.JPG", "image")
    private val photo = CameraMediaItem("PHOTO", "NEW.JPG", "image")
    private val video = CameraMediaItem("VIDEO", "NEW.MP4", "video")

    @Test fun allSixteenPatternsRetainCountsAndFinalReadAcrossEmptyKnownOnlyAndWrongKind() = runBlocking {
        for (intent in MediaReviewIntent.entries) {
            val listings = mutableListOf(emptyList(), listOf(old))
            if (intent != MediaReviewIntent.RECENT) {
                listings += listOf(if (intent == MediaReviewIntent.PHOTO) video else photo)
            }
            for (items in listings) for (mask in 0..15) {
                var reads = 0
                val snapshots = mutableListOf<CaptureReviewEvidence>()
                val result = awaitCaptureReviewItem(
                    setOf(old.id), longArrayOf(0, 0, 0), intent, snapshots::add,
                ) {
                    if (mask and (1 shl reads++) == 0) throw IOException("synthetic detail")
                    items
                }
                val success = Integer.bitCount(mask)
                val last = if (mask and 8 != 0) CaptureReviewRead.SUCCESS else CaptureReviewRead.FAILURE
                assertEquals(CaptureReviewEvidence(success, 4 - success, last), result.evidence)
                assertEquals(if (success == 0) CaptureReviewObservation.UNREAD
                    else CaptureReviewObservation.NO_CANDIDATE_OBSERVED, result.observation)
                assertEquals(success > 0 && last == CaptureReviewRead.FAILURE,
                    result.evidence.noCandidateWithFinalReadFailure)
                assertEquals(4, reads)
                assertEquals(listOf(1, 2, 3, 4), snapshots.map { it.completedReads })
                assertEquals(1, snapshots.first().completedReads) // Later reads cannot mutate earlier values.
            }
        }
    }

    @Test fun candidateAtEachRoundReturnsCurrentInvocationEvidenceAndCameraOrder() = runBlocking {
        for (intent in MediaReviewIntent.entries) for (arrival in 0..3) {
            for (earlierFailures in listOf(false, true)) {
                var reads = 0
                val expected = if (intent == MediaReviewIntent.VIDEO) video else photo
                val later = expected.copy(id = "LATER", captureTime = "2099-01-01T00:00Z")
                val result = awaitCaptureReviewItem(setOf(old.id), longArrayOf(0, 0, 0), intent) {
                    if (reads++ < arrival) {
                        if (earlierFailures) throw IOException("synthetic failure")
                        listOf(old)
                    } else listOf(old, expected, later)
                }
                assertSame(expected, (result as CaptureReviewResult.Found).item)
                assertEquals(CaptureReviewObservation.FOUND, result.observation)
                assertEquals(CaptureReviewEvidence(
                    if (earlierFailures) 1 else arrival + 1,
                    if (earlierFailures) arrival else 0, CaptureReviewRead.SUCCESS,
                ), result.evidence)
                assertEquals(arrival + 1, reads)
            }
        }
    }

    @Test fun priorSuccessfulInvocationCannotMaskAnAllFailedRetry() = runBlocking {
        val before = awaitCaptureReviewItem(setOf(old.id), longArrayOf(0, 0, 0)) { listOf(old) }
        val retry = awaitCaptureReviewItem(setOf(old.id), longArrayOf(0, 0, 0)) { throw IOException("failure") }
        assertEquals(CaptureReviewEvidence(4, 0, CaptureReviewRead.SUCCESS), before.evidence)
        assertEquals(CaptureReviewObservation.UNREAD, retry.observation)
        assertEquals(CaptureReviewEvidence(0, 4, CaptureReviewRead.FAILURE), retry.evidence)
    }

    @Test fun cancellingInflightReadRetainsPriorTerminalOutcomeAndNeverPublishesLateCandidate() = runBlocking {
        val prior = awaitCaptureReviewItem(setOf(old.id), longArrayOf()) { listOf(old) }
        var published: CaptureReviewResult = prior
        var completed = CaptureReviewEvidence()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            published = awaitCaptureReviewItem(setOf(old.id), longArrayOf(0, 0, 0),
                onReadCompleted = { completed = it }) {
                started.complete(Unit)
                release.await()
                listOf(photo)
            }
        }
        started.await()
        job.cancelAndJoin()
        release.complete(Unit)
        assertSame(prior, published)
        assertEquals(CaptureReviewEvidence(), completed)
    }

    @Test fun cancellationDuringDelayDoesNotPublishPartialEvidenceOrIssueAnotherRead() = runBlocking {
        var reads = 0
        var published = false
        val completed = CompletableDeferred<CaptureReviewEvidence>()
        val job = launch {
            awaitCaptureReviewItem(setOf(old.id), longArrayOf(60_000, 60_000, 60_000),
                onReadCompleted = { completed.complete(it) }) {
                reads++
                listOf(old)
            }
            published = true
        }
        assertEquals(CaptureReviewEvidence(1, 0, CaptureReviewRead.SUCCESS), completed.await())
        yield()
        job.cancelAndJoin()
        assertFalse(published)
        assertEquals(1, reads)
    }
}
