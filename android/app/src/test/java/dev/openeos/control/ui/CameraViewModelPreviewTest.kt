package dev.openeos.control.ui

import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.LiveViewMagnification
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
        assertEquals("1600", viewModel.uiState.value.status?.exposure?.iso)
        assertEquals(LiveViewSize.LARGE, viewModel.uiState.value.liveViewSize)
        assertEquals(LiveViewMagnification.X5, viewModel.uiState.value.liveViewMagnification)
        assertEquals(true, viewModel.uiState.value.status?.recording)

        viewModel.disconnect()

        assertFalse(viewModel.uiState.value.connected)
        assertFalse(viewModel.uiState.value.previewMode)
        assertEquals(null, viewModel.uiState.value.liveViewMagnification)
    }

    @Test
    fun captureModeSwitchWritesAdvertisedPreviewModeAndRestoresPreviousPhotoMode() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()

        viewModel.setCaptureMode(CaptureMode.VIDEO)
        advanceUntilIdle()

        assertEquals(CaptureMode.VIDEO, viewModel.uiState.value.captureMode)
        assertEquals(
            "Movie",
            viewModel.uiState.value.capabilities?.shootingModeSetting()?.value,
        )

        viewModel.setCaptureMode(CaptureMode.PHOTO)
        advanceUntilIdle()

        assertEquals(CaptureMode.PHOTO, viewModel.uiState.value.captureMode)
        assertEquals(
            "Manual",
            viewModel.uiState.value.capabilities?.shootingModeSetting()?.value,
        )
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
    fun previewMediaDeleteRemovesOnlyConfirmedItemLocally() = runTest(dispatcher) {
        val viewModel = CameraViewModel()
        viewModel.enterOfflinePreview()
        val item = viewModel.uiState.value.mediaItems.first()

        viewModel.deleteMedia(item)

        assertFalse(viewModel.uiState.value.mediaItems.any { it.id == item.id })
        assertEquals(item.name, viewModel.uiState.value.lastDeletedMediaName)
        assertEquals(2, viewModel.uiState.value.mediaItems.size)
    }
}
