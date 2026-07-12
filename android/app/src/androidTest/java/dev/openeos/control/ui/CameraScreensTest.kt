package dev.openeos.control.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.ExposureState
import org.junit.Rule
import org.junit.Test

class CameraScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun disconnectedStateShowsDedicatedConnectionScreen() {
        compose.setContent { MaterialTheme { ConnectionScreen(CameraUiState(), noOpActions()) } }
        compose.onNodeWithText("Connect your EOS").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsDisplayed()
    }

    @Test
    fun photoStateExplainsMissingStillCaptureCapability() {
        compose.setContent { MaterialTheme { CameraControlScreen(connectedState(), noOpActions()) } }
        compose.onNodeWithText("Photo").assertIsDisplayed()
        compose.onNodeWithText("Still capture is not advertised by this camera.").assertIsDisplayed()
    }

    @Test
    fun debugStateShowsDiagnosticSections() {
        compose.setContent { MaterialTheme { DebugScreen(connectedState().copy(uiMode = UiMode.DEBUG), noOpActions()) } }
        compose.onNodeWithText("Overview").assertIsDisplayed()
        compose.onNodeWithText("CCAPI").assertIsDisplayed()
        compose.onNodeWithText("USB / PTP").performScrollTo().assertIsDisplayed()
    }

    private fun connectedState() = CameraUiState(
        info = CameraInfo(true, "Canon EOS R6 Mark III", "test", "ccapi"),
        status = CameraStatus(
            connected = true,
            batteryLevel = 82,
            batteryStatus = "normal",
            recording = false,
            mode = "photo",
            mediaAvailable = true,
            remainingMinutes = 120,
            exposure = ExposureState("800", "1/50", "2.8", "auto"),
        ),
        capabilities = CameraCapabilities(emptyList(), emptyList(), emptyList(), emptyList()),
    )

    private fun noOpActions() = CameraActions(
        setBaseUrl = {}, setUsername = {}, setPassword = {},
        useHttpPreset = {}, useHttpsPreset = {}, useSimulatorPreset = {},
        connect = {}, disconnect = {}, refresh = {}, refreshUsb = {}, requestUsbPermission = {},
        setUiMode = {}, setCaptureMode = {}, openPicker = {}, closePicker = {},
        setIso = {}, setShutter = {}, setAperture = {}, setWhiteBalance = {}, setCameraSetting = { _, _ -> },
        captureStill = {}, toggleRecording = {}, tapFocus = { _, _ -> }, refreshLiveView = {}, restartLiveView = {},
        setAutoRefresh = {}, setFps = {}, setLiveViewSize = {}, clearError = {},
    )
}
