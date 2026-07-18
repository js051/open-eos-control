package dev.openeos.control.ui

import org.junit.Assert.assertFalse
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
}
