package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaReviewSelectionTest {
    private val old = CameraMediaItem("old", "OLD.JPG", "image", captureTime = "2099-01-01T00:00Z")
    private val photo = CameraMediaItem("photo", "NEW.JPG", "image")
    private val video = CameraMediaItem("video", "NEW.MP4", "video", captureTime = "2030-01-01T00:00Z")

    @Test fun photoRetryNeverSelectsFutureDatedVideoOrOldId() = runBlocking {
        var reads = 0
        val result = awaitCaptureReviewItem(setOf(old.id), longArrayOf(0, 0), MediaReviewIntent.PHOTO) {
            reads++
            if (reads < 3) listOf(video, old) else listOf(video, photo, old)
        }
        assertEquals(CaptureReviewResult.Found(photo, CaptureReviewEvidence(3, 0, CaptureReviewRead.SUCCESS)), result)
        assertEquals(3, reads)
    }

    @Test fun videoRetryIgnoresPhotosAndKeepsCameraOrder() = runBlocking {
        var reads = 0
        val result = awaitCaptureReviewItem(setOf(old.id), longArrayOf(0, 0), MediaReviewIntent.VIDEO) {
            when (reads++) {
                0 -> throw IOException("synthetic failure")
                1 -> listOf(photo, old)
                else -> listOf(photo, video, old)
            }
        }
        assertEquals(CaptureReviewResult.Found(video, CaptureReviewEvidence(2, 1, CaptureReviewRead.SUCCESS)), result)
        assertEquals(3, reads)
    }

    @Test fun recentWithoutSnapshotCanSelectVideo() = runBlocking {
        assertEquals(CaptureReviewResult.Found(video, CaptureReviewEvidence(1, 0, CaptureReviewRead.SUCCESS)), awaitCaptureReviewItem(emptySet(), longArrayOf(), MediaReviewIntent.RECENT) {
            listOf(video, photo)
        })
    }

    @Test fun previousIdSnapshotCannotBeMutatedByLaterReads() = runBlocking {
        val ids = mutableSetOf(old.id)
        var reads = 0
        val result = awaitCaptureReviewItem(ids, longArrayOf(0, 0), MediaReviewIntent.PHOTO) {
            ids.clear()
            reads++
            listOf(old)
        }
        assertEquals(CaptureReviewResult.NotReady(CaptureReviewEvidence(3, 0, CaptureReviewRead.SUCCESS)), result)
        assertEquals(3, reads)
    }

    @Test fun everyRetryRemainsBoundedAndRemembersAnySuccessfulListing() = runBlocking {
        for (successfulRound in -1..3) {
            var reads = 0
            val result = awaitCaptureReviewItem(setOf(old.id), longArrayOf(0, 0, 0), MediaReviewIntent.PHOTO) {
                if (reads++ != successfulRound) throw IOException("synthetic failure")
                listOf(video, old)
            }
            val evidence = CaptureReviewEvidence(
                if (successfulRound < 0) 0 else 1, if (successfulRound < 0) 4 else 3,
                if (successfulRound == 3) CaptureReviewRead.SUCCESS else CaptureReviewRead.FAILURE,
            )
            assertEquals(if (successfulRound < 0) CaptureReviewResult.ReadFailed(evidence)
                else CaptureReviewResult.NotReady(evidence), result)
            assertEquals(4, reads)
        }
    }

    @Test fun cancellationStopsBeforeAnyFurtherReadOrPublication() = runBlocking {
        var reads = 0
        val job = launch {
            awaitCaptureReviewItem(emptySet(), longArrayOf(0, 0), MediaReviewIntent.PHOTO) {
                reads++
                cancel()
                listOf(photo)
            }
            fail("Cancelled review cannot publish")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, reads)
        try {
            awaitCaptureReviewItem(emptySet(), longArrayOf(0, 0)) { throw CancellationException("stop") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
    }
}
