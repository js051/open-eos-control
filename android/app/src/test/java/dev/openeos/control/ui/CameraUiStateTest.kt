package dev.openeos.control.ui

import dev.openeos.control.data.CameraSettingControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiStateTest {
    @Test
    fun operationBusyStateOnlyBlocksMatchingControl() {
        val state = CameraUiState(pendingOperations = setOf(CameraOperation.SETTING))

        assertTrue(state.busy)
        assertTrue(state.isBusy(CameraOperation.SETTING))
        assertFalse(state.isBusy(CameraOperation.CAPTURE))
        assertFalse(state.isBusy(CameraOperation.RECORDING))
    }

    @Test
    fun advertisedShootingModeResolvesCaptureContextAndRestoresOnlyKnownPhotoMode() {
        val photoSetting = CameraSettingControl(
            key = "shootingmode",
            label = "Shooting mode",
            value = "Manual",
            values = listOf("P", "TV", "AV", "Manual", "Movie"),
        )
        val movieSetting = photoSetting.copy(value = "Movie")

        assertEquals(CaptureMode.PHOTO, photoSetting.currentCaptureMode())
        assertEquals("Movie", photoSetting.valueForCaptureMode(CaptureMode.VIDEO, null))
        assertEquals(CaptureMode.VIDEO, movieSetting.currentCaptureMode())
        assertEquals("Manual", movieSetting.valueForCaptureMode(CaptureMode.PHOTO, "Manual"))
        assertNull(movieSetting.valueForCaptureMode(CaptureMode.PHOTO, null))
        assertTrue("auto_exposure_mode".isShootingModeKey())
        assertEquals(CaptureMode.VIDEO, captureModeForShootingValue("movie manual exposure"))
    }
}
