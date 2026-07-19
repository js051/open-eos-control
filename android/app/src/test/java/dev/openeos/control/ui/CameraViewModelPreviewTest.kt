package dev.openeos.control.ui

import dev.openeos.control.data.LiveViewSize
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
        viewModel.toggleRecording()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.previewMode)
        assertEquals("1600", viewModel.uiState.value.status?.exposure?.iso)
        assertEquals(LiveViewSize.LARGE, viewModel.uiState.value.liveViewSize)
        assertEquals(true, viewModel.uiState.value.status?.recording)

        viewModel.disconnect()

        assertFalse(viewModel.uiState.value.connected)
        assertFalse(viewModel.uiState.value.previewMode)
    }
}
