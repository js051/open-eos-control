package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraShutterRecoveryStateTest {
    @Test fun dismissingPersistentReleaseWarningPreservesTheEntireState() {
        val state = CameraUiState().withOfflinePreview().copy(
            shutterReleaseUnconfirmed = true,
            shutterDisconnectWarning = true,
            error = "Synthetic current camera error",
            errorOperation = CameraOperation.SETTING,
            pendingOperations = setOf(CameraOperation.SHUTTER_RELEASE),
        )

        assertSame(state, state.dismissVisibleCameraMessage())
    }

    @Test fun dismissingCurrentErrorPreservesOldWarningUntilASecondDismissal() {
        val state = CameraUiState().withOfflinePreview().copy(
            shutterDisconnectWarning = true,
            error = "Synthetic current camera error",
            errorOperation = CameraOperation.SETTING,
            uiMode = UiMode.MEDIA,
            lastDownloadedMediaName = "SYNTHETIC.JPG",
        )

        val afterError = state.dismissVisibleCameraMessage()
        assertEquals(state.copy(error = null, errorOperation = null), afterError)

        val afterWarning = afterError.dismissVisibleCameraMessage()
        assertEquals(
            state.copy(error = null, errorOperation = null, shutterDisconnectWarning = false),
            afterWarning,
        )
    }

    @Test fun dismissingOnlyOldWarningClearsItAndItsOperationWithoutChangingOtherState() {
        val state = CameraUiState().withOfflinePreview().copy(
            shutterDisconnectWarning = true,
            error = null,
            errorOperation = CameraOperation.SHUTTER_RELEASE,
            uiMode = UiMode.MEDIA,
            lastDownloadedMediaName = "SYNTHETIC.JPG",
        )

        assertEquals(
            state.copy(shutterDisconnectWarning = false, errorOperation = null),
            state.dismissVisibleCameraMessage(),
        )
    }

    @Test fun unconfirmedReleaseBlocksNewCameraWritesButRetainsStatusAndStopOnlyRecovery() {
        val state = CameraUiState().withOfflinePreview().copy(shutterReleaseUnconfirmed = true)
        for (operation in listOf(CameraOperation.CAPTURE, CameraOperation.SETTING, CameraOperation.RECORDING,
            CameraOperation.FOCUS, CameraOperation.LIVE_VIEW, CameraOperation.MEDIA, CameraOperation.MAINTENANCE,
            CameraOperation.POWER, CameraOperation.CONNECT)) {
            assertTrue("Must block $operation until release is confirmed", state.isBusy(operation))
        }
        assertTrue(state.busy)
        assertFalse(state.isBusy(CameraOperation.STATUS))
        assertFalse(state.isBusy(CameraOperation.SHUTTER_RELEASE))
        assertFalse(state.canChangeShutterAutofocus())
        assertFalse(captureModeSwitchEnabled(state))
    }

    @Test fun stopOnlyRetryRemainsAvailableWhenCameraReportsBulbActive() {
        val state = CameraUiState().withOfflinePreview().let {
            it.copy(shutterReleaseUnconfirmed = true, status = it.status!!.copy(bulbExposureActive = true))
        }
        assertFalse(state.isBusy(CameraOperation.SHUTTER_RELEASE))
        assertFalse(state.isBusy(CameraOperation.STATUS))
        assertTrue(state.isBusy(CameraOperation.CAPTURE))
        assertTrue(state.copy(pendingOperations = setOf(CameraOperation.SHUTTER_RELEASE)).isBusy(CameraOperation.SHUTTER_RELEASE))
    }

    @Test fun confirmedReleaseStillBlocksNewCaptureUntilItsOperationFinishes() {
        val state = CameraUiState().withOfflinePreview().copy(pendingOperations = setOf(CameraOperation.SHUTTER_RELEASE))
        assertTrue(state.isBusy(CameraOperation.CAPTURE))
        assertTrue(state.isBusy(CameraOperation.RECORDING))
        assertTrue(state.isBusy(CameraOperation.LIVE_VIEW))
    }
}
