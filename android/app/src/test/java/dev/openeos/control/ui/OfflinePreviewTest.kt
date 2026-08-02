package dev.openeos.control.ui

import dev.openeos.control.data.CameraFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePreviewTest {
    @Test
    fun previewProvidesInteractiveR6MarkIIIStateWithoutNetworkTransport() {
        val state = CameraUiState().withOfflinePreview()

        assertTrue(state.connected)
        assertTrue(state.previewMode)
        assertEquals("Canon EOS R6 Mark III", state.info?.model)
        assertEquals(null, state.transport)
        assertTrue(state.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(state.supports(CameraFeature.VIDEO_RECORDING))
        assertTrue(state.supports(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL))
        assertTrue(state.capabilities?.advancedSettings.orEmpty().any { it.key == "autopoweroff" })
        assertEquals(
            (0..63).map(Int::toString),
            state.capabilities?.advancedSettings.orEmpty().single { it.key == "soundrecordinglevel" }.values,
        )
        assertFalse(state.pendingOperations.isNotEmpty())
    }
}
