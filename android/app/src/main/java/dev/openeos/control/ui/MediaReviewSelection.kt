package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Review uses camera order and explicit intent; gallery preferences cannot influence it. */
internal enum class MediaReviewIntent { RECENT, PHOTO, VIDEO }

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
    data class Found(val item: CameraMediaItem) : CaptureReviewResult
    data object NotReady : CaptureReviewResult
    data object ReadFailed : CaptureReviewResult
}

internal suspend fun awaitCaptureReviewItem(
    previousIds: Set<String>,
    retryDelaysMillis: LongArray,
    intent: MediaReviewIntent = MediaReviewIntent.RECENT,
    loadRecentMedia: suspend () -> List<CameraMediaItem>,
): CaptureReviewResult {
    val observedBeforeReview = previousIds.toSet()
    var readSucceeded = false
    for (attempt in 0..retryDelaysMillis.size) {
        coroutineContext.ensureActive()
        val candidate = try {
            val items = loadRecentMedia()
            coroutineContext.ensureActive()
            readSucceeded = true
            selectCaptureReviewItem(items, intent, observedBeforeReview)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
        if (candidate != null) {
            return CaptureReviewResult.Found(candidate)
        }
        if (attempt < retryDelaysMillis.size) delay(retryDelaysMillis[attempt])
    }
    // A failed final round does not erase a successful listing earlier in this check.
    // Keep exceptions out of UI state; no successful read means availability is unknown.
    return if (readSucceeded) CaptureReviewResult.NotReady else CaptureReviewResult.ReadFailed
}
