package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Review uses camera order and explicit intent; gallery preferences cannot influence it. */
internal enum class MediaReviewIntent { RECENT, PHOTO, VIDEO }

enum class CaptureReviewObservation { FOUND, NO_CANDIDATE_OBSERVED, UNREAD }
enum class CaptureReviewRead { SUCCESS, FAILURE }

/** Completed, parsed listing reads from one invocation only; contains no transport error details. */
data class CaptureReviewEvidence(
    val successfulReads: Int = 0,
    val failedReads: Int = 0,
    val lastCompletedRead: CaptureReviewRead? = null,
) {
    val completedReads: Int get() = successfulReads + failedReads
    val noCandidateWithFinalReadFailure: Boolean get() =
        successfulReads > 0 && lastCompletedRead == CaptureReviewRead.FAILURE

    init {
        require(successfulReads >= 0 && failedReads >= 0)
        require((completedReads == 0) == (lastCompletedRead == null))
        require(lastCompletedRead != CaptureReviewRead.SUCCESS || successfulReads > 0)
        require(lastCompletedRead != CaptureReviewRead.FAILURE || failedReads > 0)
    }

    internal fun completed(read: CaptureReviewRead): CaptureReviewEvidence = when (read) {
        CaptureReviewRead.SUCCESS -> copy(successfulReads = successfulReads + 1, lastCompletedRead = read)
        CaptureReviewRead.FAILURE -> copy(failedReads = failedReads + 1, lastCompletedRead = read)
    }
}

internal fun selectCaptureReviewItem(
    items: List<CameraMediaItem>,
    intent: MediaReviewIntent = MediaReviewIntent.RECENT,
    previousIds: Set<String> = emptySet(),
): CameraMediaItem? = items.firstOrNull { item ->
    item.id !in previousIds && when (intent) {
        MediaReviewIntent.RECENT -> true
        MediaReviewIntent.PHOTO -> !item.isVideo
        MediaReviewIntent.VIDEO -> item.isVideo
    }
}

internal sealed interface CaptureReviewResult {
    val evidence: CaptureReviewEvidence
    val observation: CaptureReviewObservation

    data class Found(val item: CameraMediaItem, override val evidence: CaptureReviewEvidence) : CaptureReviewResult {
        override val observation = CaptureReviewObservation.FOUND
    }
    data class NotReady(override val evidence: CaptureReviewEvidence) : CaptureReviewResult {
        override val observation = CaptureReviewObservation.NO_CANDIDATE_OBSERVED
    }
    data class ReadFailed(override val evidence: CaptureReviewEvidence) : CaptureReviewResult {
        override val observation = CaptureReviewObservation.UNREAD
    }
}

internal suspend fun awaitCaptureReviewItem(
    previousIds: Set<String>,
    retryDelaysMillis: LongArray,
    intent: MediaReviewIntent = MediaReviewIntent.RECENT,
    onReadCompleted: (CaptureReviewEvidence) -> Unit = {},
    loadRecentMedia: suspend () -> List<CameraMediaItem>,
): CaptureReviewResult {
    val observedBeforeReview = previousIds.toSet()
    var evidence = CaptureReviewEvidence()
    for (attempt in 0..retryDelaysMillis.size) {
        coroutineContext.ensureActive()
        val candidate = try {
            val items = loadRecentMedia()
            coroutineContext.ensureActive()
            evidence = evidence.completed(CaptureReviewRead.SUCCESS)
            selectCaptureReviewItem(items, intent, observedBeforeReview)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            evidence = evidence.completed(CaptureReviewRead.FAILURE)
            null
        }
        onReadCompleted(evidence)
        coroutineContext.ensureActive()
        if (candidate != null) return CaptureReviewResult.Found(candidate, evidence)
        if (attempt < retryDelaysMillis.size) delay(retryDelaysMillis[attempt])
    }
    // A successful parsed listing and a known final failure are independent facts.
    return if (evidence.successfulReads > 0) CaptureReviewResult.NotReady(evidence)
    else CaptureReviewResult.ReadFailed(evidence)
}
