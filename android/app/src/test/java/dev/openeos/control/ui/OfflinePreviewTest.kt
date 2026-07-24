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
        assertTrue(state.capabilities?.advancedSettings.orEmpty().any { it.key == "autopoweroff" })
        assertFalse(state.pendingOperations.isNotEmpty())
    }
}
