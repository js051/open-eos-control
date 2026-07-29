package dev.openeos.control.ui

import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.ExposureState
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
    fun captureModeSwitchIsBlockedDuringRecordingBulbAndCaptureWrites() {
        val status = CameraStatus(
            connected = true,
            batteryLevel = 80,
            batteryStatus = "normal",
            recording = false,
            mode = "Manual",
            mediaAvailable = true,
            remainingMinutes = null,
            exposure = ExposureState("400", "1/50", "2.8", "Auto"),
        )
        val ready = CameraUiState(status = status)

        assertTrue(captureModeSwitchEnabled(ready))
        assertFalse(captureModeSwitchEnabled(ready.copy(status = status.copy(recording = true))))
        assertFalse(
            captureModeSwitchEnabled(
                ready.copy(status = status.copy(bulbExposureActive = true)),
            ),
        )
        assertFalse(
            captureModeSwitchEnabled(
                ready.copy(pendingOperations = setOf(CameraOperation.CAPTURE)),
            ),
        )
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
