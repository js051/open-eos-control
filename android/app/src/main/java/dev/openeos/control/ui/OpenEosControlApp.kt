package dev.openeos.control.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.openeos.control.data.LiveViewSize

@Composable
fun OpenEosControlApp(viewModel: CameraViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.initialize(context) }

    val actions = CameraActions(
        setBaseUrl = viewModel::setBaseUrl,
        setUsername = viewModel::setUsername,
        setPassword = viewModel::setPassword,
        useHttpPreset = viewModel::useDirectCameraPreset,
        useHttpsPreset = viewModel::useDirectCameraHttpsPreset,
        useSimulatorPreset = viewModel::useDevSimulatorPreset,
        connect = {
            viewModel.rememberConnection(context)
            viewModel.connect()
        },
        disconnect = viewModel::disconnect,
        refresh = viewModel::refresh,
        refreshUsb = { viewModel.refreshUsbDiagnostics(context) },
        requestUsbPermission = { viewModel.requestUsbPermission(context, it) },
        setUiMode = viewModel::setUiMode,
        setCaptureMode = viewModel::setCaptureMode,
        openPicker = viewModel::openSettingPicker,
        closePicker = viewModel::closeSettingPicker,
        setIso = viewModel::setIso,
        setShutter = viewModel::setShutter,
        setAperture = viewModel::setAperture,
        setWhiteBalance = viewModel::setWhiteBalance,
        setCameraSetting = viewModel::setCameraSetting,
        captureStill = viewModel::captureStill,
        toggleRecording = viewModel::toggleRecording,
        tapFocus = viewModel::tapFocus,
        refreshLiveView = viewModel::refreshLiveViewFrame,
        restartLiveView = viewModel::restartLiveView,
        setAutoRefresh = viewModel::setLiveViewAutoRefresh,
        setFps = viewModel::setLiveViewFrameRate,
        setLiveViewSize = viewModel::setLiveViewSize,
        clearError = viewModel::clearError,
    )

    MaterialTheme(colorScheme = OpenEosColorScheme) {
        Box(Modifier.fillMaxSize().background(AppBackground)) {
            if (!state.connected) {
                ConnectionScreen(state, actions)
            } else if (state.uiMode == UiMode.DEBUG) {
                DebugScreen(state, actions)
            } else {
                CameraControlScreen(state, actions)
            }
            Box(Modifier.align(Alignment.BottomCenter)) {
                ErrorBanner(state.error, actions.clearError)
            }
        }
    }
}

data class CameraActions(
    val setBaseUrl: (String) -> Unit,
    val setUsername: (String) -> Unit,
    val setPassword: (String) -> Unit,
    val useHttpPreset: () -> Unit,
    val useHttpsPreset: () -> Unit,
    val useSimulatorPreset: () -> Unit,
    val connect: () -> Unit,
    val disconnect: () -> Unit,
    val refresh: () -> Unit,
    val refreshUsb: () -> Unit,
    val requestUsbPermission: (String) -> Unit,
    val setUiMode: (UiMode) -> Unit,
    val setCaptureMode: (CaptureMode) -> Unit,
    val openPicker: (SettingPicker) -> Unit,
    val closePicker: () -> Unit,
    val setIso: (String) -> Unit,
    val setShutter: (String) -> Unit,
    val setAperture: (String) -> Unit,
    val setWhiteBalance: (String) -> Unit,
    val setCameraSetting: (String, String) -> Unit,
    val captureStill: () -> Unit,
    val toggleRecording: () -> Unit,
    val tapFocus: (Double, Double) -> Unit,
    val refreshLiveView: () -> Unit,
    val restartLiveView: () -> Unit,
    val setAutoRefresh: (Boolean) -> Unit,
    val setFps: (Int) -> Unit,
    val setLiveViewSize: (LiveViewSize) -> Unit,
    val clearError: () -> Unit,
)
