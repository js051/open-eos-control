package dev.openeos.control.ui

import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.isVisualMedia
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.coroutineContext

/**
 * Owns display-preview admission, resources and retirement against the ViewModel's one UI snapshot.
 * The ViewModel still owns operation registration, session generations and cancellation/join.
 * In particular, this owner never starts a coroutine outside that existing operation registry.
 */
internal class CameraMediaPreviewOwner(
    private val uiState: MutableStateFlow<CameraUiState>,
    private val repository: CameraRepository,
    private val sessionGeneration: () -> Long,
    private val cancelMediaLibraryLoad: () -> Unit,
    private val launchMedia: ((Job) -> Unit, (Exception) -> Unit, suspend () -> Unit) -> Job?,
    private val hasMediaOperation: () -> Boolean,
    private val advanceStateRevision: () -> Unit,
) {
    private class HttpMediaPreviewRead(val generation: Long, val connection: CameraInfo?) {
        var job: Job? = null
        var cancelled = false
        fun cancel() {
            cancelled = true
            job?.cancel()
        }
    }
    private var httpMediaPreviewRead: HttpMediaPreviewRead? = null

    fun open(item: CameraMediaItem) {
        if (!item.isVisualMedia) return
        val state = uiState.value
        val isVideo = item.isVideo
        if (state.previewMode) {
            state.mediaStreamSource?.close()
            uiState.update {
                it.copy(
                    mediaPreviewItem = item,
                    mediaPreviewBytes = null,
                    mediaPreviewLoading = false,
                    mediaStreamSource = null,
                )
            }
            return
        }
        if (
            state.isBusy(CameraOperation.MEDIA) ||
            if (isVideo) !item.streamAvailable
            else !state.supports(CameraFeature.MEDIA_PREVIEW) || !item.previewAvailable
        ) return
        if (state.transport == CameraTransport.DESKTOP_BRIDGE && state.mediaLibraryLoading) {
            cancelMediaLibraryLoad()
        }
        val generation = sessionGeneration()
        val connection = state.info
        val cancellableImageRead = !isVideo && state.transport in setOf(
            CameraTransport.CCAPI_NETWORK, CameraTransport.DESKTOP_BRIDGE,
        )
        // Own admission before StateFlow observers can dismiss the new viewer.
        val read = if (cancellableImageRead) HttpMediaPreviewRead(generation, connection) else null
        if (read != null) httpMediaPreviewRead = read
        state.mediaStreamSource?.close()
        uiState.update {
            it.copy(
                mediaPreviewItem = item,
                mediaPreviewBytes = null,
                mediaPreviewLoading = true,
                mediaStreamSource = null,
            )
        }
        if (read != null && (read.cancelled || httpMediaPreviewRead !== read ||
                generation != sessionGeneration() || uiState.value.info !== connection)) {
            if (httpMediaPreviewRead === read) httpMediaPreviewRead = null
            return
        }
        launchMedia(
            { job ->
                if (read != null) {
                    read.job = job
                    job.invokeOnCompletion {
                        if (httpMediaPreviewRead === read) {
                            httpMediaPreviewRead = null
                            // Cancellation before lazy dispatch can skip execute's finally.
                            // The generic completion handler has already removed this job;
                            // a successor must retain its own MEDIA pending state.
                            if (read.generation == sessionGeneration() && uiState.value.info === read.connection &&
                                !hasMediaOperation() &&
                                uiState.value.isBusy(CameraOperation.MEDIA)) {
                                advanceStateRevision()
                                uiState.update { it.copy(pendingOperations = it.pendingOperations - CameraOperation.MEDIA) }
                            }
                        }
                    }
                    // MEDIA pending publication can also trigger dismissal before registration.
                    if (read.cancelled || httpMediaPreviewRead !== read ||
                        generation != sessionGeneration() || uiState.value.info !== connection) job.cancel()
                }
            },
            {
                uiState.update { current ->
                    if (generation == sessionGeneration() && current.info === connection &&
                        current.mediaPreviewItem?.id == item.id) current.copy(mediaPreviewLoading = false) else current
                }
            },
        ) {
            val stream = if (isVideo) repository.openMediaStream(item) else null
            val preview = if (isVideo) null else try {
                repository.mediaPreview(item).also {
                    require(it.item.id == item.id && it.item.name == item.name && it.item.isPhoto) {
                        "Camera changed the media identity or type before preview decoding."
                    }
                    if (cancellableImageRead) coroutineContext.ensureActive()
                }
            } catch (exception: Exception) {
                // A cancelled HTTP read may surface IOException while unwinding.
                // Keep this suppression local to display reads, not safety commands.
                if (cancellableImageRead) coroutineContext.ensureActive()
                throw exception
            }
            uiState.update { current ->
                if (generation == sessionGeneration() && current.info === connection &&
                    current.mediaPreviewItem?.id == item.id) {
                    current.copy(
                        mediaPreviewBytes = preview?.bytes,
                        mediaPreviewLoading = false,
                        mediaStreamSource = stream,
                    )
                } else {
                    stream?.close()
                    current
                }
            }
        }
    }

    /** Capture the old request before any mode/focus publication can admit a successor. */
    fun close(
        beforeClear: () -> Unit = {},
        updateState: (CameraUiState) -> CameraUiState = { it },
    ) {
        val previewToCancel = httpMediaPreviewRead
        beforeClear()
        clearViewerState(updateState)
        previewToCancel?.cancel()
    }

    /** Gallery admission already excludes MEDIA work; preserve its original no-cancel policy. */
    fun clearForLibraryRefresh(updateState: (CameraUiState) -> CameraUiState) {
        clearViewerState(updateState)
    }

    private fun clearViewerState(updateState: (CameraUiState) -> CameraUiState) {
        uiState.value.mediaStreamSource?.close()
        uiState.update {
            updateState(it.copy(
                mediaPreviewItem = null,
                mediaPreviewBytes = null,
                mediaPreviewLoading = false,
                mediaStreamSource = null,
            ))
        }
    }

    /** The caller has already committed the state CAS; retire only its captured old source. */
    fun retireReplacedStream(previous: CameraUiState, published: CameraUiState) {
        if (previous.mediaStreamSource !== published.mediaStreamSource) previous.mediaStreamSource?.close()
    }

    /** Session teardown keeps its original stream-only facade and registry-owned HTTP joins. */
    fun closeStream() {
        uiState.value.mediaStreamSource?.close()
        uiState.update { it.copy(mediaStreamSource = null) }
    }
}
