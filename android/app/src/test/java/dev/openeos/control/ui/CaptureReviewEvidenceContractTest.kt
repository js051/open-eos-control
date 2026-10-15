package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class CaptureReviewEvidenceContractTest {
    private val fixture = loadCaptureReviewEvidenceFixture()
    private val oldA = CameraMediaItem("OLD_A", "OLD_A.JPG", "jpeg", contentType = "image/jpeg")
    private val oldB = oldA.copy(id = "OLD_B", name = "OLD_B.JPG")
    private val photo = oldA.copy(id = "NEW_PHOTO", name = "NEW.JPG")
    private val video = CameraMediaItem("NEW_VIDEO", "NEW.MP4", "video", contentType = "video/mp4")

    @Test fun fixedContractMetadataAndEveryTerminalVectorMatchProductionPolicy() = runBlocking {
        assertEquals(1, fixture.schemaVersion)
        assertEquals(setOf("OLD_A", "OLD_B"), fixture.previousIds)
        assertEquals(listOf(250L, 750L, 1500L), fixture.retryDelaysMillis)
        assertEquals(4, fixture.maximumReads)
        assertEquals(106, fixture.cases.size)
        assertEquals(106, fixture.cases.map { it.id }.toSet().size)
        for (case in fixture.cases) {
            val intent = MediaReviewIntent.valueOf(case.intent.uppercase())
            assertOutcome(case.id, case.expected, invoke(case.reads, intent))
        }
    }

    @Test fun fixedRetryVectorsNeverInheritPriorInvocationEvidence() = runBlocking {
        assertEquals(2, fixture.retryCases.size)
        for (case in fixture.retryCases) {
            val first = invoke(case.first)
            val saved = first.evidence.copy()
            val second = invoke(case.second)
            assertOutcome(case.id, case.expected, second)
            assertEquals(saved, first.evidence)
            assertNotSame(first.evidence, second.evidence)
        }
    }

    @Test fun fixedRetirementVectorsRetainThePreviousOutcomeAndDiscardPartialOrLateResults() = runBlocking {
        assertEquals(4, fixture.retirementCases.size)
        for (case in fixture.retirementCases) {
            val prior = invoke(listOf("empty", "failure", "failure", "failure"))
            var published: CaptureReviewResult = prior
            var completed = CaptureReviewEvidence()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val job = launch {
                published = awaitCaptureReviewItem(fixture.previousIds, longArrayOf(60_000, 60_000, 60_000),
                    onReadCompleted = { completed = it; entered.complete(Unit) }) {
                    reads++
                    if (case.id == "cancel-in-delay-does-not-publish-partial-evidence") return@awaitCaptureReviewItem emptyList()
                    entered.complete(Unit)
                    // Emulate a transport that completes after cancellation. The production
                    // ensureActive guard must still retire its returned list/error.
                    withContext(NonCancellable) { release.await() }
                    if (case.id == "replaced-owner-ignores-late-failure") throw IOException("late old owner")
                    listOf(photo)
                }
            }
            if (case.id != "zero-completed-retry-preserves-previous") entered.await()
            job.cancel()
            release.complete(Unit)
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(case.id, case.completedReads, completed.completedReads)
            assertEquals(case.id, case.publish, published !== prior)
            assertEquals(case.id, case.preservePrevious, published === prior)
            assertEquals(case.id, if (case.id == "zero-completed-retry-preserves-previous") 0 else 1, reads)
            assertEquals(CaptureReviewEvidence(1, 3, CaptureReviewRead.FAILURE), published.evidence)
        }
    }

    private suspend fun invoke(reads: List<String>, intent: MediaReviewIntent = MediaReviewIntent.PHOTO): CaptureReviewResult {
        var index = 0
        val ids = fixture.previousIds.toMutableSet()
        val result = awaitCaptureReviewItem(ids, longArrayOf(0, 0, 0), intent) {
            ids.clear() // Production must use the immutable invocation snapshot.
            when (reads[index++]) {
                "failure" -> throw IOException("synthetic read failure")
                // Adapter seam only: actual HTTP/JSON decoding is separately covered by
                // CameraCaptureReviewRecoveryTest and is NOT exercised by the pure JVM run.
                "malformed" -> throw IllegalArgumentException("synthetic rejected listing envelope")
                "empty" -> emptyList()
                "known-only" -> listOf(oldA, oldB)
                "wrong-kind" -> listOf(if (intent == MediaReviewIntent.PHOTO) video else photo)
                "found" -> listOf(oldA, photo, video, photo.copy(id = "NEW_PHOTO_2"), video.copy(id = "NEW_VIDEO_2"))
                else -> error("Unknown fixture read")
            }
        }
        assertEquals(reads.size, index)
        assertTrue(index <= fixture.maximumReads)
        assertEquals(setOf("OLD_A", "OLD_B"), fixture.previousIds)
        return result
    }

    private fun assertOutcome(id: String, expected: CaptureReviewEvidenceExpected, actual: CaptureReviewResult) {
        val observation = when (actual.observation) {
            CaptureReviewObservation.FOUND -> "found"
            CaptureReviewObservation.NO_CANDIDATE_OBSERVED -> "no-candidate-observed"
            CaptureReviewObservation.UNREAD -> "unread"
        }
        assertEquals(id, expected.observation, observation)
        assertEquals(id, expected.successfulReads, actual.evidence.successfulReads)
        assertEquals(id, expected.failedReads, actual.evidence.failedReads)
        assertEquals(id, expected.lastCompletedRead, actual.evidence.lastCompletedRead?.name?.lowercase())
        assertEquals(id, expected.candidateId, (actual as? CaptureReviewResult.Found)?.item?.id)
    }
}
