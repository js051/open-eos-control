package dev.openeos.control.ui

import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.LiveViewMagnification
import dev.openeos.control.data.CameraFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
