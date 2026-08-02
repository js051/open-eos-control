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
        assertTrue(state.supports(CameraFeature.SOUND_RECORDING_CONTROL))
        assertTrue(state.supports(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL))
        assertTrue(state.supports(CameraFeature.FOCUS_BRACKETING_CONTROL))
        assertEquals(
            listOf("auto", "manual", "disable"),
            state.capabilities?.advancedSettings.orEmpty().single { it.key == "soundrecording" }.values,
        )
        assertTrue(state.capabilities?.advancedSettings.orEmpty().any { it.key == "autopoweroff" })
        assertEquals(
            (0..63).map(Int::toString),
            state.capabilities?.advancedSettings.orEmpty().single { it.key == "soundrecordinglevel" }.values,
        )
        assertEquals(
            (2..999).map(Int::toString),
            state.capabilities?.advancedSettings.orEmpty().single { it.key == "focusbracketingnumberofshots" }.values,
        )
        assertEquals(
            (1..10).map(Int::toString),
            state.capabilities?.advancedSettings.orEmpty().single { it.key == "focusbracketingfocusincrement" }.values,
        )
        assertTrue(settingsForMode(state.capabilities?.advancedSettings.orEmpty(), CaptureMode.PHOTO).none {
            it.key in setOf("soundrecording", "soundrecordinglevel", "windfilter", "attenuator")
        })
        assertTrue(settingsForMode(state.capabilities?.advancedSettings.orEmpty(), CaptureMode.VIDEO).none {
            it.key.startsWith("focusbracketing")
        })
        assertFalse(state.pendingOperations.isNotEmpty())
    }
}
