package dev.openeos.control.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.LiveViewSource
import dev.openeos.control.data.LiveViewMagnification
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.FocusDriveDirection
import dev.openeos.control.data.FocusDriveStep

@Composable
fun OpenEosControlApp(
    viewModel: CameraViewModel = viewModel(),
    controlRotationDegrees: Float = 0f,
    animateControlRotation: Boolean = true,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val animatedControlRotation by animateFloatAsState(
        targetValue = controlRotationDegrees,
        animationSpec = if (animateControlRotation) tween(durationMillis = 180) else snap(),
        label = "camera-control-rotation",
    )
    LaunchedEffect(viewModel) { viewModel.initialize(context) }

    val actions = CameraActions(
        setConnectionTarget = viewModel::setConnectionTarget,
        setBaseUrl = viewModel::setBaseUrl,
        setUsername = viewModel::setUsername,
        setPassword = viewModel::setPassword,
        setBridgeBaseUrl = viewModel::setBridgeBaseUrl,
        setBridgeToken = viewModel::setBridgeToken,
        scanDesktopBridge = viewModel::scanDesktopBridge,
        selectBridgeCamera = viewModel::selectBridgeCamera,
        useHttpPreset = viewModel::useDirectCameraPreset,
        useHttpsPreset = viewModel::useDirectCameraHttpsPreset,
        useSimulatorPreset = viewModel::useDevSimulatorPreset,
        enterOfflinePreview = viewModel::enterOfflinePreview,
        connect = {
            viewModel.rememberConnection(context)
            viewModel.connect()
        },
        connectBridge = {
            viewModel.rememberConnection(context)
            viewModel.connectBridge()
        },
        disconnect = viewModel::disconnect,
        refresh = viewModel::refresh,
        refreshUsb = { viewModel.refreshUsbDiagnostics(context) },
        requestUsbPermission = { viewModel.requestUsbPermission(context, it) },
        connectUsb = viewModel::connectUsb,
        setUiMode = viewModel::setUiMode,
        setCaptureMode = viewModel::setCaptureMode,
        setHudVisible = viewModel::setHudVisible,
        setGridVisible = viewModel::setGridVisible,
        setHistogramVisible = viewModel::setHistogramVisible,
        setWaveformVisible = viewModel::setWaveformVisible,
        setZebraThreshold = viewModel::setZebraThreshold,
        setFalseColorEnabled = viewModel::setFalseColorEnabled,
        setFocusPeakingEnabled = viewModel::setFocusPeakingEnabled,
        setFrameGuide = viewModel::setFrameGuide,
        setSafeAreaVisible = viewModel::setSafeAreaVisible,
        setDesqueeze = viewModel::setDesqueeze,
        importCubeLut = viewModel::importCubeLut,
        clearCubeLut = viewModel::clearCubeLut,
        reportCubeLutError = viewModel::reportCubeLutError,
        setLiveViewTapAction = viewModel::setLiveViewTapAction,
        openPicker = viewModel::openSettingPicker,
        closePicker = viewModel::closeSettingPicker,
        setIso = viewModel::setIso,
        setShutter = viewModel::setShutter,
        setAperture = viewModel::setAperture,
        setWhiteBalance = viewModel::setWhiteBalance,
        setCameraSetting = viewModel::setCameraSetting,
        captureStill = viewModel::captureStill,
        toggleBulbExposure = viewModel::toggleBulbExposure,
        autofocus = viewModel::autofocus,
        halfPressShutter = viewModel::halfPressShutter,
        driveFocus = viewModel::driveFocus,
        setLiveViewMagnification = viewModel::setLiveViewMagnification,
        toggleRecording = viewModel::toggleRecording,
        tapFocus = viewModel::tapFocus,
        clickWhiteBalance = viewModel::clickWhiteBalance,
        refreshMedia = viewModel::refreshMedia,
        loadMediaThumbnail = viewModel::loadMediaThumbnail,
        openMediaPreview = viewModel::openMediaPreview,
        closeMediaPreview = viewModel::closeMediaPreview,
        downloadMedia = { item, destination -> viewModel.downloadMedia(context, item, destination) },
        deleteMedia = viewModel::deleteMedia,
        cancelMediaDownload = viewModel::cancelMediaDownload,
        refreshLiveView = viewModel::refreshLiveViewFrame,
        restartLiveView = viewModel::restartLiveView,
        setAutoRefresh = viewModel::setLiveViewAutoRefresh,
        setFps = viewModel::setLiveViewFrameRate,
        setLiveViewSize = viewModel::setLiveViewSize,
        setLiveViewSource = viewModel::setLiveViewSource,
        setAppLanguage = { language ->
            viewModel.closeSettingPicker()
            AppLanguageManager.set(language)
        },
        clearError = viewModel::clearError,
    )

    CompositionLocalProvider(
        LocalCameraControlRotation provides animatedControlRotation,
        LocalCameraControlTargetRotation provides controlRotationDegrees,
    ) {
        MaterialTheme(colorScheme = OpenEosColorScheme) {
            SystemBarsEffect(immersive = state.connected && state.uiMode == UiMode.CONTROL)
            Box(Modifier.fillMaxSize().background(AppBackground)) {
                if (!state.connected) {
                    ConnectionScreen(state, actions)
                } else if (state.uiMode == UiMode.MEDIA) {
                    MediaScreen(state, actions)
                } else if (state.uiMode == UiMode.DEBUG) {
                    DebugScreen(state, actions)
                } else {
                    CameraControlScreen(state, actions)
                }
                Box(Modifier.align(Alignment.BottomCenter)) {
                    ErrorBanner(state.error, actions.clearError)
                }
            }
            LanguageSettingsSheet(state, actions)
        }
    }
}

@Composable
private fun SystemBarsEffect(immersive: Boolean) {
    val view = LocalView.current
    val activity = view.context as? Activity ?: return
    fun applySystemBars() {
        WindowInsetsControllerCompat(activity.window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            if (immersive) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(view, immersive) {
        applySystemBars()
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) applySystemBars()
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
        }
    }
}

data class CameraActions(
    val setConnectionTarget: (ConnectionTarget) -> Unit,
    val setBaseUrl: (String) -> Unit,
    val setUsername: (String) -> Unit,
    val setPassword: (String) -> Unit,
    val setBridgeBaseUrl: (String) -> Unit,
    val setBridgeToken: (String) -> Unit,
    val scanDesktopBridge: () -> Unit,
    val selectBridgeCamera: (String) -> Unit,
    val useHttpPreset: () -> Unit,
    val useHttpsPreset: () -> Unit,
    val useSimulatorPreset: () -> Unit,
    val enterOfflinePreview: () -> Unit,
    val connect: () -> Unit,
    val connectBridge: () -> Unit,
    val disconnect: () -> Unit,
    val refresh: () -> Unit,
    val refreshUsb: () -> Unit,
    val requestUsbPermission: (String) -> Unit,
    val connectUsb: (String, Int, Int) -> Unit,
    val setUiMode: (UiMode) -> Unit,
    val setCaptureMode: (CaptureMode) -> Unit,
    val setHudVisible: (Boolean) -> Unit,
    val setGridVisible: (Boolean) -> Unit,
    val setHistogramVisible: (Boolean) -> Unit = {},
    val setWaveformVisible: (Boolean) -> Unit = {},
    val setZebraThreshold: (Int?) -> Unit = {},
    val setFalseColorEnabled: (Boolean) -> Unit = {},
    val setFocusPeakingEnabled: (Boolean) -> Unit = {},
    val setFrameGuide: (LiveViewFrameGuide) -> Unit = {},
    val setSafeAreaVisible: (Boolean) -> Unit = {},
    val setDesqueeze: (LiveViewDesqueeze) -> Unit = {},
    val importCubeLut: (String, String) -> Unit = { _, _ -> },
    val clearCubeLut: () -> Unit = {},
    val reportCubeLutError: (String) -> Unit = {},
    val setLiveViewTapAction: (LiveViewTapAction) -> Unit,
    val openPicker: (SettingPicker) -> Unit,
    val closePicker: () -> Unit,
    val setIso: (String) -> Unit,
    val setShutter: (String) -> Unit,
    val setAperture: (String) -> Unit,
    val setWhiteBalance: (String) -> Unit,
    val setCameraSetting: (String, String) -> Unit,
    val captureStill: () -> Unit,
    val toggleBulbExposure: () -> Unit = {},
    val autofocus: () -> Unit,
    val halfPressShutter: () -> Unit,
    val driveFocus: (FocusDriveDirection, FocusDriveStep) -> Unit,
    val setLiveViewMagnification: (LiveViewMagnification) -> Unit,
    val toggleRecording: () -> Unit,
    val tapFocus: (Double, Double) -> Unit,
    val clickWhiteBalance: (Double, Double) -> Unit,
    val refreshMedia: () -> Unit,
    val loadMediaThumbnail: (CameraMediaItem) -> Unit,
    val openMediaPreview: (CameraMediaItem) -> Unit,
    val closeMediaPreview: () -> Unit,
    val downloadMedia: (CameraMediaItem, Uri) -> Unit,
    val deleteMedia: (CameraMediaItem) -> Unit,
    val cancelMediaDownload: () -> Unit,
    val refreshLiveView: () -> Unit,
    val restartLiveView: () -> Unit,
    val setAutoRefresh: (Boolean) -> Unit,
    val setFps: (Int) -> Unit,
    val setLiveViewSize: (LiveViewSize) -> Unit,
    val setLiveViewSource: (LiveViewSource) -> Unit,
    val setAppLanguage: (AppLanguage) -> Unit,
    val clearError: () -> Unit,
)
