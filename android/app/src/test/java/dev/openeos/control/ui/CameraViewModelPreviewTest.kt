package dev.openeos.control.ui

import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.LiveViewMagnification
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraBackendFactory
import dev.openeos.control.data.CameraHttpTransport
import dev.openeos.control.data.CameraHttpTransportFactory
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelPreviewTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun offlineGalleryOpensPlaceholdersWithoutAdvertisingRealPreviewSupport() {
        val state = CameraUiState().withOfflinePreview()
        assertFalse(state.supports(CameraFeature.MEDIA_PREVIEW))
        state.mediaItems.forEach { item ->
            assertTrue(item.name, canPreviewGalleryItem(state, item))
            assertFalse(item.name, canPreviewGalleryItem(
                state.copy(pendingOperations = setOf(CameraOperation.MEDIA)), item,
            ))
        }
    }

    @Test
    fun liveGalleryImagesStillRequirePreviewCapabilityAndItemAvailability() {
        val offline = CameraUiState().withOfflinePreview()
        val capabilities = requireNotNull(offline.capabilities)
        val state = offline.copy(previewMode = false, capabilities = capabilities.copy(
            matrix = capabilities.matrix.copy(supported = capabilities.matrix.supported + CameraFeature.MEDIA_PREVIEW),
        ))
        offline.mediaItems.filterNot { it.isVideo }.forEach { item ->
            val available = item.copy(previewAvailable = true)
            assertTrue(item.name, canPreviewGalleryItem(state, available))
            assertFalse(item.name, canPreviewGalleryItem(state, item.copy(previewAvailable = false)))
            assertFalse(item.name, canPreviewGalleryItem(offline.copy(previewMode = false), available))
            assertFalse(item.name, canPreviewGalleryItem(
                state.copy(pendingOperations = setOf(CameraOperation.MEDIA)), available,
            ))
        }
    }

    @Test
    fun liveGalleryVideosStillRequireAnAvailableStream() {
        val state = CameraUiState().withOfflinePreview().copy(previewMode = false)
        val video = state.mediaItems.single { it.isVideo }
        assertFalse(state.supports(CameraFeature.MEDIA_PREVIEW))
        assertTrue(canPreviewGalleryItem(state, video.copy(streamAvailable = true)))
        assertFalse(canPreviewGalleryItem(state, video.copy(streamAvailable = false)))
        assertFalse(canPreviewGalleryItem(
            state.copy(pendingOperations = setOf(CameraOperation.MEDIA)), video.copy(streamAvailable = true),
        ))
    }

    @Test
    fun offlineMediaViewerStaysLocalAcrossFilteredNavigationAndVideo() = runTest(dispatcher) {
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            throw IOException("Unexpected synthetic offline-preview request")
        }.build()
        val repository = CameraRepository(CameraBackendFactory(
            httpTransportFactory = CameraHttpTransportFactory {
                CameraHttpTransport(client, CameraNetworkDiagnostics.Empty)
            },
        ))
        val model = CameraViewModel(repository)
        try {
            model.enterOfflinePreview()
            model.setUiMode(UiMode.MEDIA)
            val items = model.uiState.value.mediaItems
            val folder = MediaFolderFilter.Folder(requireNotNull(items.first().folder))
            model.setMediaFolderFilter(folder)
            val filtered = mediaItemsForDisplay(items, MediaFilter.ALL, MediaSort.CAMERA, folderFilter = folder)
            assertEquals(listOf("preview-001", "preview-002"), filtered.map { it.id })
            model.openMediaPreview(filtered.first())
            model.previewAdjacentMedia(filtered, 1)
            assertEquals(filtered.last().id, model.uiState.value.mediaPreviewItem?.id)
            model.previewAdjacentMedia(filtered, 1)
            assertEquals("Navigation cannot escape the selected folder", filtered.last().id,
                model.uiState.value.mediaPreviewItem?.id)
            model.previewAdjacentMedia(filtered, -1)
            assertEquals(filtered.first().id, model.uiState.value.mediaPreviewItem?.id)
            model.previewAdjacentMedia(filtered, -1)
            assertEquals("Previous navigation cannot escape the selected folder", filtered.first().id,
                model.uiState.value.mediaPreviewItem?.id)
            model.closeMediaPreview()
            assertNull(model.uiState.value.mediaPreviewItem)

            val video = items.single { it.isVideo }
            model.setMediaFolderFilter(MediaFolderFilter.Unknown)
            model.openMediaPreview(video)
            model.loadMediaInfo(video)
            model.loadMediaThumbnail(video)
            CameraMediaPickerKind.entries.forEach { kind ->
                assertNull(model.beginMediaPicker(kind,
                    if (kind == CameraMediaPickerKind.UPLOAD) emptyList() else listOf(video)))
            }
            advanceUntilIdle()
            requireNotNull(model.viewModelScope.coroutineContext[Job]).children.toList().forEach { it.join() }
            assertEquals(video.id, model.uiState.value.mediaPreviewItem?.id)
            assertNull(model.uiState.value.mediaPreviewBytes)
            assertNull(model.uiState.value.mediaStreamSource)
            assertNull(model.uiState.value.transport)
            assertTrue(model.uiState.value.mediaSaveFeedback.isEmpty())
            assertNull(model.uiState.value.error)
            assertEquals(0, requests.get())
            model.closeMediaPreview()
            model.disconnect()
            advanceUntilIdle()
            requireNotNull(model.viewModelScope.coroutineContext[Job]).children.toList().forEach { it.join() }
            assertFalse(model.uiState.value.previewMode)
            assertEquals(0, requests.get())
        } finally {
            model.viewModelScope.cancel()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun liveViewTransitionDisablesOnlyConflictingCameraOperations() {
        val state = CameraUiState(pendingOperations = setOf(CameraOperation.LIVE_VIEW))
        assertTrue(state.isBusy(CameraOperation.FOCUS))
        assertTrue(state.isBusy(CameraOperation.CAPTURE))
        assertTrue(state.isBusy(CameraOperation.RECORDING))
        assertFalse(state.isBusy(CameraOperation.STATUS))
        assertFalse(state.isBusy(CameraOperation.MEDIA))
    }

    @Test
    fun focusCommandDoesNotPretendToConfirmOpticalFocusAndNewTapKeepsItsOwnTimer() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()
        viewModel.tapFocus(0.2, 0.3)
        assertEquals(FocusFeedback.ACCEPTED, viewModel.uiState.value.focusFeedback)
        advanceTimeBy(800)
        viewModel.tapFocus(0.7, 0.8)
        advanceTimeBy(500)
        assertEquals(FocusPoint(0.7, 0.8), viewModel.uiState.value.focusPoint)
        assertEquals(FocusFeedback.ACCEPTED, viewModel.uiState.value.focusFeedback)
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.focusPoint)
    }

    @Test
    fun previewControlsUpdateLocallyAndDisconnectWithoutBackendCalls() = runTest(dispatcher) {
        val viewModel = CameraViewModel()

        viewModel.enterOfflinePreview()
        viewModel.setIso("1600")
        viewModel.setLiveViewSize(LiveViewSize.LARGE)
        viewModel.setLiveViewMagnification(LiveViewMagnification.X5)
        viewModel.toggleRecording()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.previewMode)
        assertEquals(MediaLibraryLoadStatus.COMPLETE, viewModel.uiState.value.mediaLibraryLoadStatus)
        assertEquals("1600", viewModel.uiState.value.status?.exposure?.iso)
        assertEquals(LiveViewSize.LARGE, viewModel.uiState.value.liveViewSize)
        assertEquals(LiveViewMagnification.X5, viewModel.uiState.value.liveViewMagnification)
        assertEquals(true, viewModel.uiState.value.status?.recording)

        viewModel.disconnect()

        assertFalse(viewModel.uiState.value.connected)
        assertFalse(viewModel.uiState.value.previewMode)
        assertEquals(MediaLibraryLoadStatus.NOT_LOADED, viewModel.uiState.value.mediaLibraryLoadStatus)
        assertEquals(null, viewModel.uiState.value.liveViewMagnification)
        assertTrue(viewModel.uiState.value.operatorConfirmedFeatures.isEmpty())
    }

    @Test
    fun offlinePreviewCannotCreatePhysicalCameraConfirmation() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()

        viewModel.setOperatorConfirmation(CameraFeature.STILL_CAPTURE, true)

        assertTrue(viewModel.uiState.value.operatorConfirmedFeatures.isEmpty())
    }

    @Test
    fun offlinePreviewShowsButCannotExecuteCameraSleep() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()

        assertTrue(viewModel.uiState.value.supports(CameraFeature.CAMERA_SLEEP))
        viewModel.sleepCamera()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.connected)
        assertTrue(viewModel.uiState.value.previewMode)
        assertFalse(CameraOperation.POWER in viewModel.uiState.value.pendingOperations)
    }

    @Test
    fun offlinePreviewShowsButCannotExecuteSensorCleaning() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()

        assertTrue(viewModel.uiState.value.supports(CameraFeature.SENSOR_CLEANING))
        viewModel.cleanSensor(autoPowerOff = true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.connected)
        assertTrue(viewModel.uiState.value.previewMode)
        assertFalse(CameraOperation.MAINTENANCE in viewModel.uiState.value.pendingOperations)
    }

    @Test
    fun captureModeSwitchWritesAdvertisedPreviewModeAndRestoresPreviousPhotoMode() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()

        viewModel.setCaptureMode(CaptureMode.VIDEO)
        advanceUntilIdle()

        assertEquals(CaptureMode.VIDEO, viewModel.uiState.value.captureMode)
        assertEquals("on", viewModel.uiState.value.capabilities?.captureModeSetting()?.value)
        assertEquals("Manual", viewModel.uiState.value.capabilities?.shootingModeSetting()?.value)

        viewModel.setCaptureMode(CaptureMode.PHOTO)
        advanceUntilIdle()

        assertEquals(CaptureMode.PHOTO, viewModel.uiState.value.captureMode)
        assertEquals("off", viewModel.uiState.value.capabilities?.captureModeSetting()?.value)
        assertEquals("Manual", viewModel.uiState.value.capabilities?.shootingModeSetting()?.value)
    }

    @Test
    fun captureModeCannotChangeWhilePreviewRecordingIsActive() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()
        viewModel.setCaptureMode(CaptureMode.VIDEO)
        advanceUntilIdle()
        viewModel.toggleRecording()
        advanceUntilIdle()

        viewModel.setCaptureMode(CaptureMode.PHOTO)
        advanceUntilIdle()

        assertEquals(CaptureMode.VIDEO, viewModel.uiState.value.captureMode)
        assertEquals(true, viewModel.uiState.value.status?.recording)
    }

    @Test
    fun histogramAndWaveformRemainMutuallyExclusive() = runTest(dispatcher) {
        val viewModel = CameraViewModel()

        viewModel.setHistogramVisible(true)
        assertTrue(viewModel.uiState.value.monitorSettings.histogramVisible)
        assertFalse(viewModel.uiState.value.monitorSettings.waveformVisible)

        viewModel.setWaveformVisible(true)
        assertFalse(viewModel.uiState.value.monitorSettings.histogramVisible)
        assertTrue(viewModel.uiState.value.monitorSettings.waveformVisible)
    }

    @Test
    fun previewMediaDeleteRemovesOnlyConfirmedItemLocally() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()
        val item = viewModel.uiState.value.mediaItems.first()

        viewModel.deleteMedia(item)

        assertFalse(viewModel.uiState.value.mediaItems.any { it.id == item.id })
        assertEquals(item.name, viewModel.uiState.value.lastDeletedMediaName)
        assertEquals(2, viewModel.uiState.value.mediaItems.size)
    }

    @Test
    fun offlineCaptureReviewOpensTheLatestMediaWithoutCameraIo() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()
        val latest = viewModel.uiState.value.captureReviewItem

        viewModel.openCaptureReview()
        advanceUntilIdle()

        assertEquals(UiMode.MEDIA, viewModel.uiState.value.uiMode)
        assertEquals(latest?.id, viewModel.uiState.value.mediaPreviewItem?.id)
        assertFalse(viewModel.uiState.value.mediaPreviewLoading)
    }

    @Test
    fun previewMediaMetadataActionsUpdateOnlyTheLocalItem() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()
        val item = viewModel.uiState.value.mediaItems.first()

        viewModel.setMediaProtection(item, false)
        viewModel.setMediaArchived(item, true)
        viewModel.setMediaRating(item, 2)
        viewModel.setMediaRotation(item, 180)

        val updated = viewModel.uiState.value.mediaItems.first { it.id == item.id }
        assertEquals(false, updated.protected)
        assertEquals(true, updated.archived)
        assertEquals(2, updated.rating)
        assertEquals(180, updated.rotationDegrees)
        assertTrue(viewModel.uiState.value.previewMode)
        assertFalse(CameraOperation.MEDIA in viewModel.uiState.value.pendingOperations)
    }

    @Test
    fun mediaScopeCannotChangeDuringAnOperationAndCanChangeAfterItFinishes() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()
        val before = viewModel.uiState.value

        viewModel.setMediaProtectionBatch(before.mediaItems.take(1), false)
        assertTrue(viewModel.uiState.value.isBusy(CameraOperation.MEDIA))
        viewModel.setMediaLibraryScope(MediaLibraryScope.ALL)

        assertEquals(MediaLibraryScope.RECENT, viewModel.uiState.value.mediaLibraryScope)
        assertEquals(before.mediaItems, viewModel.uiState.value.mediaItems)
        assertEquals(before.mediaLibraryHasMore, viewModel.uiState.value.mediaLibraryHasMore)
        assertEquals(before.mediaLibraryLoadStatus, viewModel.uiState.value.mediaLibraryLoadStatus)

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isBusy(CameraOperation.MEDIA))
        viewModel.setMediaLibraryScope(MediaLibraryScope.ALL)
        assertEquals(MediaLibraryScope.ALL, viewModel.uiState.value.mediaLibraryScope)
    }

    @Test
    fun previewMediaBatchActionsUpdateAndDeleteEverySelectedItem() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()
        val selected = viewModel.uiState.value.mediaItems.take(2)

        viewModel.setMediaProtectionBatch(selected, false)
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.mediaItems
                .filter { current -> selected.any { it.id == current.id } }
                .all { it.protected == false },
        )
        assertEquals(2, viewModel.uiState.value.lastMediaBatchResult?.succeededItems)
        assertEquals(MediaBatchOperation.UNPROTECT, viewModel.uiState.value.lastMediaBatchResult?.operation)

        viewModel.deleteMediaBatch(selected)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.mediaItems.none { current -> selected.any { it.id == current.id } })
        assertEquals(2, viewModel.uiState.value.lastMediaBatchResult?.succeededItems)
        assertEquals(MediaBatchOperation.DELETE, viewModel.uiState.value.lastMediaBatchResult?.operation)
    }
}
