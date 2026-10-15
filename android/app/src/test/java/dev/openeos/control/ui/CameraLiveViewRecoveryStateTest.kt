package dev.openeos.control.ui

import org.junit.Assert.*
import org.junit.Test

class CameraLiveViewRecoveryStateTest {
    @Test fun stopRecoverySurvivesMissingCapabilitiesAndDoesNotDismiss() {
        val state = CameraUiState().withOfflinePreview().copy(
            capabilities = null, liveViewAutoRefresh = false, liveViewStopUnconfirmed = true,
            error = "Synthetic stop failure", errorOperation = CameraOperation.LIVE_VIEW,
        )
        assertTrue(state.liveViewRecoveryActionEnabled())
        assertSame(state, state.dismissVisibleCameraMessage())
        assertFalse(state.copy(pendingOperations = setOf(CameraOperation.LIVE_VIEW)).liveViewRecoveryActionEnabled())
    }

    @Test fun wireWarningAlsoPermitsOnlyRecoveryWithoutAdvertisedCapability() {
        val baseline = CameraUiState().withOfflinePreview()
        val state = baseline.copy(capabilities = null, liveViewAutoRefresh = false,
            status = baseline.status!!.copy(liveViewStopUnconfirmed = true))
        assertTrue(state.needsLiveViewStopRecovery)
        assertTrue(state.liveViewRecoveryActionEnabled())
        val warned = state.adoptLiveViewStopWarning(state.status!!)
        val idle = warned.status!!.copy(liveViewStopUnconfirmed = false)
        val refreshed = warned.copy(status = idle).adoptLiveViewStopWarning(idle)
        assertTrue(refreshed.needsLiveViewStopRecovery)
        assertTrue(refreshed.liveViewRecoveryActionEnabled())
        assertFalse(refreshed.copy(liveViewStopUnconfirmed = false).liveViewRecoveryActionEnabled())
    }

    @Test fun previousCameraWarningIsDistinctAndDismissalDoesNotMutateCurrentSession() {
        val state = CameraUiState().withOfflinePreview().copy(liveViewDisconnectWarning = true,
            error = "Synthetic current error", errorOperation = CameraOperation.STATUS)
        val afterError = state.dismissVisibleCameraMessage()
        assertEquals(state.copy(error = null, errorOperation = null), afterError)
        assertEquals(afterError.copy(liveViewDisconnectWarning = false), afterError.dismissVisibleCameraMessage())
    }
}
