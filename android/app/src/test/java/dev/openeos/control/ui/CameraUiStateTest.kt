package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CameraSettingInputKind
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

        val movieMode = CameraSettingControl("moviemode", "Movie mode", "off", listOf("off", "on"))
        assertEquals(CaptureMode.PHOTO, movieMode.currentCaptureMode())
        assertEquals("on", movieMode.valueForCaptureMode(CaptureMode.VIDEO, null))
        assertEquals("off", movieMode.copy(value = "on").valueForCaptureMode(CaptureMode.PHOTO, null))
        assertTrue("movie_mode".isMovieModeKey())
        assertTrue("moviemode".isCaptureModeKey())
        assertTrue(settingsForMode(listOf(movieMode), CaptureMode.PHOTO).isEmpty())
    }

    @Test
    fun advancedSettingsHideSingleChoiceEvidenceButKeepUsableAloControl() {
        val singleChoice = CameraSettingControl("alomode", "ALO", "x3", listOf("x3"))
        val usable = CameraSettingControl(
            "alomode",
            "ALO",
            "Standard",
            listOf("Standard", "Low", "High", "Off"),
        )

        assertTrue(settingsForMode(listOf(singleChoice), CaptureMode.PHOTO).isEmpty())
        assertEquals(listOf(usable), settingsForMode(listOf(usable), CaptureMode.PHOTO))
        assertEquals(listOf(usable), settingsForMode(listOf(usable), CaptureMode.VIDEO))
    }

    @Test
    fun advancedSettingsKeepTextControlsWithoutFakeChoiceValues() {
        val owner = CameraSettingControl(
            key = "ownername",
            label = "Owner name",
            value = "TEST OWNER",
            values = emptyList(),
            inputKind = CameraSettingInputKind.TEXT,
            maxLength = 255,
        )

        assertEquals(listOf(owner), settingsForMode(listOf(owner), CaptureMode.PHOTO))
        assertEquals(listOf(owner), settingsForMode(listOf(owner), CaptureMode.VIDEO))
    }

    @Test
    fun sourceSpecificAudioControlsStayInVideoMode() {
        val settings = listOf(
            CameraSettingControl("soundrecordingmodeintmic", "Internal mic", "auto", listOf("auto", "manual")),
            CameraSettingControl("soundrecordinglevelintmic", "Internal mic level", "32", (0..63).map(Int::toString)),
            CameraSettingControl("windfilterintmic", "Internal wind filter", "enable", listOf("enable", "disable")),
            CameraSettingControl("attenuatoracc", "Accessory attenuator", "enable", listOf("enable", "disable")),
        )

        assertTrue(settingsForMode(settings, CaptureMode.PHOTO).isEmpty())
        assertEquals(settings, settingsForMode(settings, CaptureMode.VIDEO))
    }

    @Test
    fun eventMediaRefreshPreservesCurrentItemsAndClosesRemovedPreview() {
        val retained = CameraMediaItem("media-1", "IMG_0001.JPG", "image", previewAvailable = true)
        val removed = CameraMediaItem("media-2", "IMG_0002.JPG", "image", previewAvailable = true)
        val added = CameraMediaItem("media-3", "IMG_0003.JPG", "image", previewAvailable = true)
        val state = CameraUiState(
            mediaItems = listOf(retained, removed),
            mediaThumbnailLoadingIds = setOf("media-1", "media-2"),
            mediaPreviewItem = removed,
            mediaPreviewBytes = byteArrayOf(1, 2, 3),
            mediaPreviewLoading = true,
        )

        val refreshed = with(CameraViewModel()) {
            state.withEventMediaItems(listOf(retained, added))
        }

        assertEquals(listOf(retained, added), refreshed.mediaItems)
        assertTrue(refreshed.mediaThumbnailLoadingIds.isEmpty())
        assertNull(refreshed.mediaPreviewItem)
        assertNull(refreshed.mediaPreviewBytes)
        assertFalse(refreshed.mediaPreviewLoading)

        val preservedPreview = with(CameraViewModel()) {
            state.copy(mediaPreviewItem = retained).withEventMediaItems(listOf(retained, added))
        }
        assertEquals(retained, preservedPreview.mediaPreviewItem)
        assertTrue(preservedPreview.mediaPreviewBytes?.contentEquals(byteArrayOf(1, 2, 3)) == true)
        assertTrue(preservedPreview.mediaPreviewLoading)
    }
}
