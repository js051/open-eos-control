package dev.openeos.control.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraSession
import dev.openeos.control.data.FocusDriveDirection
import dev.openeos.control.data.FocusDriveStep
import dev.openeos.control.data.LiveViewRequest
import dev.openeos.control.data.LiveViewMagnification
import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.LiveViewSource
import dev.openeos.control.data.NativeLiveViewEvent
import dev.openeos.control.data.NativeLiveViewSession
import dev.openeos.control.data.UsbPtpDiagnosticScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream

class CameraViewModel(
    private val repository: CameraRepository = CameraRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
    private var liveViewJob: Job? = null
    private var mediaDownloadJob: Job? = null
    private val mediaThumbnailJobs = mutableMapOf<String, Job>()
    private val unavailableMediaThumbnailIds = mutableSetOf<String>()
    private var mediaThumbnailGeneration = 0
    private val frameTimesMillis = ArrayDeque<Long>()
    private var preferencesLoaded = false
    private var networkRoutingConfigured = false
    private var lastPhotoShootingMode: String? = null

    fun initialize(context: Context) {
        if (!networkRoutingConfigured) {
            repository.configureAndroidNetworkRouting(context.applicationContext)
            networkRoutingConfigured = true
        }
        if (preferencesLoaded) return
        preferencesLoaded = true
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        _uiState.update {
            it.copy(
                connectionTarget = preferences.getString(KEY_CONNECTION_TARGET, null)
                    ?.let { value -> runCatching { ConnectionTarget.valueOf(value) }.getOrNull() }
                    ?: it.connectionTarget,
                baseUrl = preferences.getString(KEY_BASE_URL, it.baseUrl) ?: it.baseUrl,
                username = preferences.getString(KEY_USERNAME, it.username) ?: it.username,
                bridgeBaseUrl = preferences.getString(KEY_BRIDGE_BASE_URL, it.bridgeBaseUrl) ?: it.bridgeBaseUrl,
            )
        }
        if (_uiState.value.usbDiagnostics.scannedAtMillis == 0L) {
            refreshUsbDiagnostics(context.applicationContext)
        }
    }

    fun setUiMode(mode: UiMode) {
        _uiState.update {
            it.copy(
                uiMode = mode,
                activeSettingPicker = null,
                mediaPreviewItem = if (mode == UiMode.MEDIA) it.mediaPreviewItem else null,
                mediaPreviewBytes = if (mode == UiMode.MEDIA) it.mediaPreviewBytes else null,
                mediaPreviewLoading = mode == UiMode.MEDIA && it.mediaPreviewLoading,
            )
        }
        if (mode == UiMode.MEDIA && _uiState.value.mediaItems.isEmpty()) refreshMedia()
    }

    fun setCaptureMode(mode: CaptureMode) {
        val setting = _uiState.value.capabilities?.shootingModeSetting()
        if (setting?.currentCaptureMode() == CaptureMode.PHOTO) {
            lastPhotoShootingMode = setting.value
        }
        _uiState.update { it.copy(captureMode = mode, activeSettingPicker = null) }
        val target = setting?.valueForCaptureMode(mode, lastPhotoShootingMode)
        if (target != null && target != setting.value) setCameraSetting(setting.key, target)
    }

    fun setHudVisible(visible: Boolean) = _uiState.update { it.copy(hudVisible = visible) }

    fun setGridVisible(visible: Boolean) = _uiState.update { it.copy(showGrid = visible) }

    fun setHistogramVisible(visible: Boolean) = updateMonitorSettings { copy(histogramVisible = visible) }

    fun setZebraThreshold(thresholdPercent: Int?) = updateMonitorSettings {
        copy(zebraThresholdPercent = thresholdPercent?.coerceIn(50, 100))
    }

    fun setFalseColorEnabled(enabled: Boolean) = updateMonitorSettings { copy(falseColorEnabled = enabled) }

    fun setFocusPeakingEnabled(enabled: Boolean) = updateMonitorSettings { copy(focusPeakingEnabled = enabled) }

    fun setFrameGuide(guide: LiveViewFrameGuide) = updateMonitorSettings { copy(frameGuide = guide) }

    fun setSafeAreaVisible(visible: Boolean) = updateMonitorSettings { copy(safeAreaVisible = visible) }

    fun setDesqueeze(desqueeze: LiveViewDesqueeze) = updateMonitorSettings { copy(desqueeze = desqueeze) }

    private fun updateMonitorSettings(update: LiveViewMonitorSettings.() -> LiveViewMonitorSettings) {
        _uiState.update { it.copy(monitorSettings = it.monitorSettings.update()) }
    }

    fun openSettingPicker(picker: SettingPicker) = _uiState.update { it.copy(activeSettingPicker = picker) }

    fun closeSettingPicker() = _uiState.update { it.copy(activeSettingPicker = null) }

    fun setConnectionTarget(target: ConnectionTarget) {
        if (_uiState.value.connected) return
        _uiState.update { it.copy(connectionTarget = target, error = null, errorOperation = null) }
    }

    fun setBaseUrl(value: String) {
        if (_uiState.value.connected) return
        stopLiveViewLoop()
        _uiState.update { it.withClearedSession(baseUrl = value, error = null) }
    }

    fun setUsername(value: String) {
        if (_uiState.value.connected) return
        _uiState.update { it.copy(username = value, error = null) }
    }

    fun setPassword(value: String) {
        if (_uiState.value.connected) return
        _uiState.update { it.copy(password = value, error = null) }
    }

    fun setBridgeBaseUrl(value: String) {
        if (_uiState.value.connected) return
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = it.baseUrl, error = null).copy(
                bridgeBaseUrl = value,
                bridgeCameras = emptyList(),
                selectedBridgeCameraId = null,
            )
        }
    }

    fun setBridgeToken(value: String) {
        if (_uiState.value.connected) return
        _uiState.update {
            it.copy(
                bridgeToken = value,
                bridgeCameras = emptyList(),
                selectedBridgeCameraId = null,
                error = null,
                errorOperation = null,
            )
        }
    }

    fun selectBridgeCamera(cameraId: String) {
        if (_uiState.value.connected) return
        _uiState.update { state ->
            state.copy(
                selectedBridgeCameraId = cameraId.takeIf { id -> state.bridgeCameras.any { it.id == id } },
                error = null,
                errorOperation = null,
            )
        }
    }

    fun useDirectCameraPreset() {
        if (_uiState.value.connected) return
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = CameraRepository.DEFAULT_CAMERA_BASE_URL, error = null)
        }
    }

    fun useDirectCameraHttpsPreset() {
        if (_uiState.value.connected) return
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = CameraRepository.DEFAULT_CAMERA_HTTPS_URL, error = null)
        }
    }

    fun useDevSimulatorPreset() {
        if (_uiState.value.connected) return
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = CameraRepository.DEV_EMULATOR_SIMULATOR_URL, error = null)
        }
    }

    fun enterOfflinePreview() {
        stopLiveViewLoop()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        _uiState.update { it.withOfflinePreview() }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, errorOperation = null) }
    }

    fun refreshUsbDiagnostics(context: Context) = runCamera(CameraOperation.USB) {
        val diagnostics = UsbPtpDiagnosticScanner().scan(context.applicationContext)
        _uiState.update { it.copy(usbDiagnostics = diagnostics) }
    }

    fun requestUsbPermission(context: Context, deviceName: String) = runCamera(CameraOperation.USB) {
        val scanner = UsbPtpDiagnosticScanner()
        scanner.requestPermission(context.applicationContext, deviceName)
        val diagnostics = scanner.scan(context.applicationContext)
        _uiState.update { it.copy(usbDiagnostics = diagnostics) }
    }

    fun connect() = runCamera(CameraOperation.CONNECT) {
        stopLiveViewLoop()
        detachNativeLiveViewListener()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        val session = repository.connect(
            baseUrl = _uiState.value.baseUrl,
            username = _uiState.value.username,
            password = _uiState.value.password,
            request = LiveViewRequest(
                fps = _uiState.value.liveViewFrameRateFps,
                size = _uiState.value.liveViewSize,
                source = _uiState.value.liveViewSource,
            ),
        )
        applyConnectedSession(session)
    }

    fun connectUsb(deviceName: String, vendorId: Int, productId: Int) = runCamera(CameraOperation.CONNECT) {
        stopLiveViewLoop()
        detachNativeLiveViewListener()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        val session = repository.connectUsb(
            deviceName = deviceName,
            vendorId = vendorId,
            productId = productId,
            request = LiveViewRequest(
                fps = _uiState.value.liveViewFrameRateFps,
                size = _uiState.value.liveViewSize,
                source = _uiState.value.liveViewSource,
            ),
        )
        applyConnectedSession(session)
    }

    fun scanDesktopBridge() = runCamera(CameraOperation.BRIDGE) {
        val state = _uiState.value
        val cameras = repository.discoverBridgeCameras(
            baseUrl = state.bridgeBaseUrl,
            token = state.bridgeToken,
        )
        _uiState.update { current ->
            val selected = current.selectedBridgeCameraId
                ?.takeIf { id -> cameras.any { it.id == id } }
                ?: cameras.singleOrNull()?.id
            current.copy(
                bridgeCameras = cameras,
                selectedBridgeCameraId = selected,
            )
        }
    }

    fun connectBridge() = runCamera(CameraOperation.CONNECT) {
        stopLiveViewLoop()
        detachNativeLiveViewListener()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        val state = _uiState.value
        val session = repository.connectBridge(
            baseUrl = state.bridgeBaseUrl,
            token = state.bridgeToken,
            cameraId = state.selectedBridgeCameraId,
            request = LiveViewRequest(
                fps = state.liveViewFrameRateFps,
                size = state.liveViewSize,
                source = state.liveViewSource,
            ),
        )
        applyConnectedSession(session)
    }

    private suspend fun applyConnectedSession(session: CameraSession) {
        val supportedFps = _uiState.value.liveViewFrameRateFps.coerceIn(
            session.capabilities.liveView.minFps,
            session.capabilities.liveView.maxFps,
        )
        repository.updateLiveViewRequest(fps = supportedFps)
        configureNativeLiveViewSession(session.nativeLiveViewSession, supportedFps)
        val activeSource = session.nativeLiveViewSession?.source ?: when {
            session.liveViewRequest.source != LiveViewSource.AUTO -> session.liveViewRequest.source
            LiveViewSource.CCAPI_JPEG_POLLING in session.capabilities.liveView.sources -> LiveViewSource.CCAPI_JPEG_POLLING
            else -> session.capabilities.liveView.defaultSource
        }
        val captureMode = captureModeFrom(session.capabilities)
        _uiState.update {
            it.copy(
                transport = session.transport,
                info = session.info,
                status = session.status,
                capabilities = session.capabilities,
                networkDiagnostics = session.networkDiagnostics,
                liveViewFrameUrl = session.liveViewFrameUrl,
                liveViewBitmap = null,
                nativeLiveViewSession = session.nativeLiveViewSession,
                liveViewFrameRateFps = supportedFps,
                liveViewSize = session.liveViewRequest.size,
                liveViewSource = activeSource,
                liveViewDiagnostics = session.nativeLiveViewSession?.let { native ->
                    LiveViewDiagnostics(contentType = native.contentType, sourceUrl = native.sourceUrl)
                } ?: it.liveViewDiagnostics,
                captureMode = captureMode ?: it.captureMode,
                error = session.liveViewStartError,
                errorOperation = session.liveViewStartError?.let { CameraOperation.LIVE_VIEW },
            )
        }
        if (session.capabilities.matrix.supports(CameraFeature.LIVE_VIEW)) {
            if (session.nativeLiveViewSession == null) {
                refreshLiveViewFrameInternal(reportErrors = true)
                startLiveViewLoopIfNeeded()
            }
        }
    }

    fun rememberConnection(context: Context) {
        val state = _uiState.value
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, state.baseUrl)
            .putString(KEY_USERNAME, state.username)
            .putString(KEY_BRIDGE_BASE_URL, state.bridgeBaseUrl)
            .putString(KEY_CONNECTION_TARGET, state.connectionTarget.name)
            .apply()
    }

    fun disconnect() {
        stopLiveViewLoop()
        detachNativeLiveViewListener()
        cancelMediaDownload()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        if (_uiState.value.previewMode) {
            _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
            return
        }
        viewModelScope.launch {
            try {
                repository.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
    }

    fun refresh() = runCamera(CameraOperation.STATUS) {
        if (_uiState.value.previewMode) return@runCamera
        val status = repository.refreshStatus()
        val capabilities = repository.refreshCapabilities()
        val networkDiagnostics = repository.refreshNetworkDiagnostics()
        val captureMode = captureModeFrom(capabilities)
        _uiState.update {
            it.copy(
                status = status,
                capabilities = capabilities,
                networkDiagnostics = networkDiagnostics,
                captureMode = captureMode ?: it.captureMode,
            )
        }
        refreshLiveViewFrameInternal(reportErrors = true)
    }

    fun refreshLiveViewFrame() {
        if (!_uiState.value.connected || _uiState.value.previewMode) return
        viewModelScope.launch {
            refreshLiveViewFrameInternal(reportErrors = true)
        }
    }

    fun setLiveViewAutoRefresh(enabled: Boolean) {
        _uiState.update { it.copy(liveViewAutoRefresh = enabled) }
        if (_uiState.value.previewMode) return
        repository.setNativeLiveViewRenderingEnabled(enabled)
        if (_uiState.value.nativeLiveViewSession != null) return
        if (enabled) {
            refreshLiveViewFrame()
            startLiveViewLoopIfNeeded()
        } else {
            stopLiveViewLoop()
        }
    }

    fun setLiveViewFrameRate(fps: Int) {
        val liveView = _uiState.value.capabilities?.liveView
        val clampedFps = fps.coerceIn(
            liveView?.minFps ?: MIN_LIVE_VIEW_FPS,
            liveView?.maxFps ?: MAX_LIVE_VIEW_FPS,
        )
        val changed = _uiState.value.liveViewFrameRateFps != clampedFps
        if (!_uiState.value.previewMode) repository.updateLiveViewRequest(fps = clampedFps)
        _uiState.update { it.copy(liveViewFrameRateFps = clampedFps) }
        if (changed && _uiState.value.connected && !_uiState.value.previewMode && _uiState.value.liveViewAutoRefresh) {
            startLiveViewLoopIfNeeded()
        }
    }

    fun setLiveViewSource(source: LiveViewSource) {
        val state = _uiState.value
        if (source == state.liveViewSource || source !in state.capabilities?.liveView?.sources.orEmpty()) return
        if (state.previewMode) {
            _uiState.update { it.copy(liveViewSource = source) }
            return
        }
        repository.updateLiveViewRequest(source = source)
        _uiState.update { it.copy(liveViewSource = source) }
        restartLiveView()
    }

    fun setLiveViewSize(size: LiveViewSize) {
        if (_uiState.value.liveViewSize == size) return
        if (_uiState.value.liveViewSource == LiveViewSource.CCAPI_RTP) return
        if (_uiState.value.previewMode) {
            _uiState.update { it.copy(liveViewSize = size) }
            return
        }
        repository.updateLiveViewRequest(size = size)
        _uiState.update { it.copy(liveViewSize = size) }
        restartLiveView()
    }

    fun restartLiveView() = runCamera(CameraOperation.LIVE_VIEW) {
        if (_uiState.value.previewMode || !_uiState.value.supports(CameraFeature.LIVE_VIEW)) return@runCamera
        repository.restartLiveView()
        val capabilities = repository.refreshCapabilities()
        val nativeSession = repository.nativeLiveViewSession()
        configureNativeLiveViewSession(nativeSession, _uiState.value.liveViewFrameRateFps)
        _uiState.update {
            it.copy(
                capabilities = capabilities,
                nativeLiveViewSession = nativeSession,
                liveViewSource = nativeSession?.source ?: it.liveViewSource,
                liveViewBitmap = null,
                liveViewFrameUrl = null,
                liveViewMagnification = null,
                liveViewDiagnostics = LiveViewDiagnostics(),
            )
        }
        resetFrameMetrics()
        if (nativeSession == null) {
            refreshLiveViewFrameInternal(reportErrors = true)
            startLiveViewLoopIfNeeded()
        }
    }

    fun setIso(value: String) {
        if (updatePreviewExposure { it.copy(iso = value) }) return
        updateStatus(CameraOperation.SETTING) { repository.setIso(value) }
    }

    fun setShutter(value: String) {
        if (updatePreviewExposure { it.copy(shutter = value) }) return
        updateStatus(CameraOperation.SETTING) { repository.setShutter(value) }
    }

    fun setAperture(value: String) {
        if (updatePreviewExposure { it.copy(aperture = value) }) return
        updateStatus(CameraOperation.SETTING) { repository.setAperture(value) }
    }

    fun setWhiteBalance(value: String) {
        if (updatePreviewExposure { it.copy(whiteBalance = value) }) return
        updateStatus(CameraOperation.SETTING) { repository.setWhiteBalance(value) }
    }

    fun setCameraSetting(key: String, value: String) {
        if (_uiState.value.isBusy(CameraOperation.SETTING)) return
        val selectedCaptureMode = if (key.isShootingModeKey()) captureModeForShootingValue(value) else null
        runCamera(CameraOperation.SETTING) {
            if (_uiState.value.previewMode) {
                if (selectedCaptureMode == CaptureMode.PHOTO) lastPhotoShootingMode = value
                _uiState.update { state ->
                    state.copy(
                        captureMode = selectedCaptureMode ?: state.captureMode,
                        capabilities = state.capabilities?.copy(
                            advancedSettings = state.capabilities.advancedSettings.map { setting ->
                                if (setting.key == key) setting.copy(value = value) else setting
                            },
                        ),
                    )
                }
                return@runCamera
            }
            val status = repository.setCameraSetting(key, value)
            val capabilities = repository.refreshCapabilities()
            val captureMode = selectedCaptureMode ?: captureModeFrom(capabilities)
            if (selectedCaptureMode == CaptureMode.PHOTO) lastPhotoShootingMode = value
            _uiState.update {
                it.copy(
                    status = status,
                    capabilities = capabilities,
                    captureMode = captureMode ?: it.captureMode,
                )
            }
            refreshLiveViewFrameInternal(reportErrors = false)
            startLiveViewLoopIfNeeded()
        }
    }

    fun toggleRecording() = updateStatus(CameraOperation.RECORDING) {
        if (_uiState.value.previewMode) {
            return@updateStatus _uiState.value.status!!.copy(
                recording = _uiState.value.status?.recording != true,
            )
        }
        repository.toggleRecording(_uiState.value.status?.recording)
    }

    fun captureStill() = runCamera(CameraOperation.CAPTURE) {
        if (_uiState.value.previewMode) {
            showCaptureSuccess()
            return@runCamera
        }
        val status = repository.captureStill()
        _uiState.update { it.copy(status = status) }
        showCaptureSuccess()
        refreshLiveViewFrameInternal(reportErrors = false)
        startLiveViewLoopIfNeeded()
    }

    fun toggleBulbExposure() = runCamera(CameraOperation.CAPTURE) {
        if (!_uiState.value.bulbMode || !_uiState.value.supports(CameraFeature.BULB_EXPOSURE)) {
            return@runCamera
        }
        val active = _uiState.value.bulbExposureActive
        if (_uiState.value.previewMode) {
            val status = requireNotNull(_uiState.value.status).copy(bulbExposureActive = !active)
            _uiState.update {
                it.copy(
                    status = status,
                    bulbStartedAtMillis = if (active) null else SystemClock.elapsedRealtime(),
                )
            }
            if (active) showCaptureSuccess()
            return@runCamera
        }
        pauseLiveViewForBulb()
        try {
            val status = if (active) repository.stopBulbExposure() else repository.startBulbExposure()
            _uiState.update {
                it.copy(
                    status = status,
                    bulbStartedAtMillis = if (status.bulbExposureActive == true) {
                        it.bulbStartedAtMillis ?: SystemClock.elapsedRealtime()
                    } else {
                        null
                    },
                )
            }
            if (active && status.bulbExposureActive != true) {
                showCaptureSuccess()
                resumeLiveViewAfterBulb()
            }
        } catch (exception: Exception) {
            if (!active) resumeLiveViewAfterBulb()
            throw exception
        }
    }

    fun autofocus() {
        _uiState.update { it.copy(focusFeedback = FocusFeedback.FOCUSING) }
        if (_uiState.value.previewMode) {
            _uiState.update { it.copy(focusFeedback = FocusFeedback.SUCCESS) }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            return
        }
        runCamera(
            operation = CameraOperation.FOCUS,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            val status = repository.autofocus()
            _uiState.update { it.copy(status = status, focusFeedback = FocusFeedback.SUCCESS) }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            refreshLiveViewFrameInternal(reportErrors = false)
            startLiveViewLoopIfNeeded()
        }
    }

    fun halfPressShutter() {
        _uiState.update { it.copy(focusFeedback = FocusFeedback.FOCUSING) }
        if (_uiState.value.previewMode) {
            _uiState.update { it.copy(focusFeedback = FocusFeedback.SUCCESS) }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            return
        }
        runCamera(
            operation = CameraOperation.FOCUS,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            val status = repository.halfPressShutter()
            _uiState.update { it.copy(status = status, focusFeedback = FocusFeedback.SUCCESS) }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            refreshLiveViewFrameInternal(reportErrors = false)
            startLiveViewLoopIfNeeded()
        }
    }

    fun driveFocus(direction: FocusDriveDirection, step: FocusDriveStep) {
        _uiState.update { it.copy(focusFeedback = FocusFeedback.FOCUSING) }
        if (_uiState.value.previewMode) {
            _uiState.update { it.copy(focusFeedback = FocusFeedback.SUCCESS) }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            return
        }
        runCamera(
            operation = CameraOperation.FOCUS,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            repository.driveFocus(direction, step)
            _uiState.update { it.copy(focusFeedback = FocusFeedback.SUCCESS) }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            refreshLiveViewFrameInternal(reportErrors = false)
            startLiveViewLoopIfNeeded()
        }
    }

    fun setLiveViewMagnification(magnification: LiveViewMagnification) {
        if (_uiState.value.previewMode) {
            _uiState.update { it.copy(liveViewMagnification = magnification) }
            return
        }
        runCamera(CameraOperation.LIVE_VIEW) {
            val result = repository.setLiveViewMagnification(magnification)
            if (result.ok) {
                _uiState.update { it.copy(liveViewMagnification = result.magnification) }
                refreshLiveViewFrameInternal(reportErrors = false)
                startLiveViewLoopIfNeeded()
            }
        }
    }

    fun refreshMedia() {
        if (!_uiState.value.connected || _uiState.value.previewMode) return
        cancelMediaThumbnailLoads()
        _uiState.update {
            it.copy(
                mediaThumbnails = emptyMap(),
                mediaThumbnailLoadingIds = emptySet(),
                mediaPreviewItem = null,
                mediaPreviewBytes = null,
                mediaPreviewLoading = false,
            )
        }
        runCamera(CameraOperation.MEDIA) {
            val items = repository.listMedia()
            _uiState.update {
                it.copy(
                    mediaItems = items,
                    lastDownloadedMediaName = null,
                    lastDeletedMediaName = null,
                )
            }
        }
    }

    fun loadMediaThumbnail(item: CameraMediaItem) {
        val state = _uiState.value
        if (
            state.previewMode ||
            !state.supports(CameraFeature.MEDIA_THUMBNAIL) ||
            item.id in state.mediaThumbnails ||
            item.id in state.mediaThumbnailLoadingIds ||
            item.id in unavailableMediaThumbnailIds
        ) return

        val generation = mediaThumbnailGeneration
        _uiState.update { it.copy(mediaThumbnailLoadingIds = it.mediaThumbnailLoadingIds + item.id) }
        mediaThumbnailJobs[item.id] = viewModelScope.launch {
            try {
                val thumbnail = repository.mediaThumbnail(item)
                val bitmap = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(thumbnail.bytes, 0, thumbnail.bytes.size)
                } ?: error("Camera returned an undecodable thumbnail for ${item.name}.")
                if (
                    generation != mediaThumbnailGeneration ||
                    _uiState.value.mediaItems.none { current -> current.id == item.id }
                ) return@launch
                _uiState.update { current ->
                    current.copy(mediaThumbnails = current.mediaThumbnails + (item.id to bitmap))
                }
                refreshCapabilityEvidence()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (generation == mediaThumbnailGeneration) unavailableMediaThumbnailIds += item.id
            } finally {
                if (generation == mediaThumbnailGeneration) {
                    mediaThumbnailJobs.remove(item.id)
                    _uiState.update { current ->
                        current.copy(mediaThumbnailLoadingIds = current.mediaThumbnailLoadingIds - item.id)
                    }
                }
            }
        }
    }

    fun openMediaPreview(item: CameraMediaItem) {
        val state = _uiState.value
        if (
            state.previewMode ||
            state.isBusy(CameraOperation.MEDIA) ||
            !state.supports(CameraFeature.MEDIA_PREVIEW) ||
            !item.previewAvailable
        ) return
        _uiState.update {
            it.copy(mediaPreviewItem = item, mediaPreviewBytes = null, mediaPreviewLoading = true)
        }
        runCamera(
            operation = CameraOperation.MEDIA,
            onError = {
                _uiState.update { current ->
                    if (current.mediaPreviewItem?.id == item.id) current.copy(mediaPreviewLoading = false) else current
                }
            },
        ) {
            val preview = repository.mediaPreview(item)
            _uiState.update { current ->
                if (current.mediaPreviewItem?.id == item.id) {
                    current.copy(mediaPreviewBytes = preview.bytes, mediaPreviewLoading = false)
                } else {
                    current
                }
            }
        }
    }

    fun closeMediaPreview() {
        _uiState.update {
            it.copy(mediaPreviewItem = null, mediaPreviewBytes = null, mediaPreviewLoading = false)
        }
    }

    fun downloadMedia(context: Context, item: CameraMediaItem, destination: Uri) {
        if (
            _uiState.value.previewMode ||
            _uiState.value.isBusy(CameraOperation.MEDIA) ||
            mediaDownloadJob != null
        ) return
        val resolver = context.applicationContext.contentResolver
        _uiState.update {
            it.copy(
                activeMediaDownloadName = item.name,
                mediaDownloadProgress = CameraMediaTransferProgress(0L, item.sizeBytes),
                lastDownloadedMediaName = null,
            )
        }
        val job = launchCameraOperation(CameraOperation.MEDIA) {
            try {
                val result = withContext(Dispatchers.IO) {
                    val rawOutput = resolver.openOutputStream(destination, "w")
                        ?: error("Android could not open the selected download destination.")
                    BufferedOutputStream(rawOutput).use { output ->
                        repository.downloadMedia(item, output) { progress ->
                            _uiState.update { state -> state.copy(mediaDownloadProgress = progress) }
                        }
                    }
                }
                _uiState.update { it.copy(lastDownloadedMediaName = result.item.name) }
            } catch (exception: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { resolver.delete(destination, null, null) }
                }
                throw exception
            } finally {
                _uiState.update {
                    it.copy(activeMediaDownloadName = null, mediaDownloadProgress = null)
                }
            }
        }
        mediaDownloadJob = job
        job?.invokeOnCompletion {
            if (mediaDownloadJob === job) mediaDownloadJob = null
        }
    }

    fun cancelMediaDownload() {
        mediaDownloadJob?.cancel()
    }

    fun deleteMedia(item: CameraMediaItem) {
        val state = _uiState.value
        if (state.isBusy(CameraOperation.MEDIA) || !state.supports(CameraFeature.MEDIA_DELETE)) return
        if (state.previewMode) {
            _uiState.update { current -> current.withDeletedMedia(item) }
            return
        }
        runCamera(CameraOperation.MEDIA) {
            repository.deleteMedia(item)
            _uiState.update { current -> current.withDeletedMedia(item) }
        }
    }

    fun tapFocus(x: Double, y: Double) {
        _uiState.update {
            it.copy(
                focusPoint = FocusPoint(x, y),
                focusFeedback = FocusFeedback.FOCUSING,
            )
        }
        if (_uiState.value.previewMode) {
            _uiState.update {
                it.copy(
                    focusPoint = FocusPoint(x, y),
                    focusFeedback = FocusFeedback.SUCCESS,
                )
            }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            return
        }
        runCamera(
            operation = CameraOperation.FOCUS,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            val result = repository.tapFocus(x, y)
            _uiState.update {
                it.copy(
                    focusPoint = FocusPoint(result.x, result.y),
                    focusFeedback = FocusFeedback.SUCCESS,
                )
            }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            refreshLiveViewFrameInternal(reportErrors = false)
            startLiveViewLoopIfNeeded()
        }
    }

    fun setLiveViewTapAction(action: LiveViewTapAction) {
        val feature = when (action) {
            LiveViewTapAction.FOCUS -> CameraFeature.TAP_FOCUS
            LiveViewTapAction.WHITE_BALANCE -> CameraFeature.CLICK_WHITE_BALANCE
        }
        if (_uiState.value.previewMode || _uiState.value.supports(feature)) {
            _uiState.update { it.copy(liveViewTapAction = action) }
        }
    }

    fun clickWhiteBalance(x: Double, y: Double) {
        _uiState.update {
            it.copy(
                focusPoint = FocusPoint(x, y),
                focusFeedback = FocusFeedback.FOCUSING,
            )
        }
        if (_uiState.value.previewMode) {
            _uiState.update { state ->
                state.copy(
                    status = state.status?.copy(
                        exposure = state.status.exposure.copy(whiteBalance = "click"),
                    ),
                    focusFeedback = FocusFeedback.SUCCESS,
                )
            }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            return
        }
        runCamera(
            operation = CameraOperation.SETTING,
            onError = {
                _uiState.update { state -> state.copy(focusFeedback = FocusFeedback.FAILURE) }
                clearFocusFeedbackAfter(FocusFeedback.FAILURE)
            },
        ) {
            val status = repository.clickWhiteBalance(x, y)
            _uiState.update {
                it.copy(
                    status = status,
                    focusPoint = FocusPoint(x, y),
                    focusFeedback = FocusFeedback.SUCCESS,
                )
            }
            clearFocusFeedbackAfter(FocusFeedback.SUCCESS)
            refreshLiveViewFrameInternal(reportErrors = false)
            startLiveViewLoopIfNeeded()
        }
    }

    private fun updateStatus(
        operation: CameraOperation,
        block: suspend () -> dev.openeos.control.data.CameraStatus,
    ) = runCamera(operation) {
        _uiState.update {
            it.copy(
                status = block(),
            )
        }
        refreshLiveViewFrameInternal(reportErrors = false)
        startLiveViewLoopIfNeeded()
    }

    private fun updatePreviewExposure(
        update: (dev.openeos.control.data.ExposureState) -> dev.openeos.control.data.ExposureState,
    ): Boolean {
        if (!_uiState.value.previewMode) return false
        _uiState.update { state ->
            state.copy(status = state.status?.copy(exposure = update(state.status.exposure)))
        }
        return true
    }

    private fun captureModeFrom(capabilities: CameraCapabilities): CaptureMode? {
        val setting = capabilities.shootingModeSetting() ?: return null
        return setting.currentCaptureMode()?.also { mode ->
            if (mode == CaptureMode.PHOTO) lastPhotoShootingMode = setting.value
        }
    }

    private fun showCaptureSuccess() {
        _uiState.update { it.copy(captureFeedback = CaptureFeedback.SUCCESS) }
        viewModelScope.launch {
            delay(CAPTURE_FLASH_MILLIS)
            _uiState.update { it.copy(captureFeedback = null) }
        }
    }

    private fun runCamera(
        operation: CameraOperation,
        onError: (Exception) -> Unit = {},
        block: suspend () -> Unit,
    ) {
        launchCameraOperation(operation, onError, block)
    }

    private fun launchCameraOperation(
        operation: CameraOperation,
        onError: (Exception) -> Unit = {},
        block: suspend () -> Unit,
    ): Job? {
        if (_uiState.value.isBusy(operation)) return null
        _uiState.update {
            it.copy(
                pendingOperations = it.pendingOperations + operation,
                error = null,
                errorOperation = null,
            )
        }
        return viewModelScope.launch {
            try {
                block()
                if (operation in CAPABILITY_EVIDENCE_OPERATIONS) {
                    refreshCapabilityEvidence()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                exception.printStackTrace()
                onError(exception)
                _uiState.update {
                    it.copy(
                        error = formatException(exception),
                        errorOperation = operation,
                    )
                }
            } finally {
                _uiState.update {
                    it.copy(pendingOperations = it.pendingOperations - operation)
                }
            }
        }
    }

    private fun refreshCapabilityEvidence() {
        val state = _uiState.value
        if (!state.connected || state.previewMode) return
        val observedFeatures = repository.observedFeatures()
        _uiState.update { current ->
            val capabilities = current.capabilities
            if (current.connected && !current.previewMode && capabilities != null) {
                current.copy(
                    capabilities = capabilities.copy(
                        evidence = capabilities.evidence.copy(observedFeatures = observedFeatures),
                    ),
                )
            } else {
                current
            }
        }
    }

    private fun clearFocusFeedbackAfter(expected: FocusFeedback) {
        viewModelScope.launch {
            delay(FOCUS_FEEDBACK_MILLIS)
            _uiState.update {
                if (it.focusFeedback == expected) {
                    it.copy(focusFeedback = null, focusPoint = null)
                } else {
                    it
                }
            }
        }
    }

    private suspend fun refreshLiveViewFrameInternal(reportErrors: Boolean) {
        if (
            !_uiState.value.connected ||
            _uiState.value.previewMode ||
            !_uiState.value.supports(CameraFeature.LIVE_VIEW)
            || _uiState.value.nativeLiveViewSession != null
        ) return

        if (!repository.isRealCamera()) {
            val nextUrl = repository.nextLiveViewFrameUrl()
            _uiState.update {
                if (it.connected) {
                    it.copy(
                        liveViewFrameUrl = nextUrl,
                        liveViewBitmap = null,
                        error = if (it.errorOperation == CameraOperation.LIVE_VIEW) null else it.error,
                        errorOperation = it.errorOperation.takeUnless { operation -> operation == CameraOperation.LIVE_VIEW },
                        liveViewDiagnostics = recordFrame(
                            current = it.liveViewDiagnostics,
                            nowMillis = System.currentTimeMillis(),
                            sourceUrl = nextUrl,
                        ),
                    )
                } else {
                    it
                }
            }
            return
        }

        try {
            val frame = repository.fetchLiveViewFrame()
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)
            } ?: error(
                "Live view frame was received but Android could not decode it " +
                    "(${frame.bytes.size} bytes, ${frame.contentType ?: "unknown content type"})."
            )

            _uiState.update {
                if (it.connected) {
                    it.copy(
                        liveViewFrameUrl = frame.sourceUrl,
                        liveViewBitmap = bitmap,
                        error = if (it.errorOperation == CameraOperation.LIVE_VIEW) null else it.error,
                        errorOperation = it.errorOperation.takeUnless { operation -> operation == CameraOperation.LIVE_VIEW },
                        liveViewDiagnostics = recordFrame(
                            current = it.liveViewDiagnostics,
                            nowMillis = System.currentTimeMillis(),
                            frameBytes = frame.bytes.size,
                            contentType = frame.contentType,
                            sourceUrl = frame.sourceUrl,
                        ),
                    )
                } else {
                    it
                }
            }
            if (
                CameraFeature.LIVE_VIEW_JPEG_POLLING !in
                _uiState.value.capabilities?.evidence?.observedFeatures.orEmpty()
            ) {
                refreshCapabilityEvidence()
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            if (reportErrors) {
                _uiState.update {
                    it.copy(
                        error = formatException(exception),
                        errorOperation = CameraOperation.LIVE_VIEW,
                    )
                }
            }
        }
    }

    private fun formatException(exception: Exception): String = buildString {
        append(exception.javaClass.simpleName)
        append(": ")
        append(exception.message ?: "Unknown error")
        var cause = exception.cause
        while (cause != null) {
            append("\nCaused by: ")
            append(cause.javaClass.simpleName)
            append(": ")
            append(cause.message ?: "Unknown cause")
            cause = cause.cause
        }
    }

    private fun startLiveViewLoopIfNeeded() {
        liveViewJob?.cancel()
        val state = _uiState.value
        if (
            !state.connected ||
            state.previewMode ||
            !state.liveViewAutoRefresh ||
            !state.supports(CameraFeature.LIVE_VIEW)
            || state.nativeLiveViewSession != null
        ) return

        liveViewJob = viewModelScope.launch {
            while (isActive) {
                val latest = _uiState.value
                if (!latest.connected || !latest.liveViewAutoRefresh) break
                val frameStartedAt = SystemClock.elapsedRealtime()

                if (repository.isRealCamera()) {
                    refreshLiveViewFrameInternal(reportErrors = false)
                } else {
                    val nextUrl = repository.nextLiveViewFrameUrl()
                    _uiState.update {
                        if (it.connected && it.liveViewAutoRefresh) {
                            it.copy(
                                liveViewFrameUrl = nextUrl,
                                liveViewBitmap = null,
                                error = if (it.errorOperation == CameraOperation.LIVE_VIEW) null else it.error,
                                errorOperation = it.errorOperation.takeUnless { operation -> operation == CameraOperation.LIVE_VIEW },
                                liveViewDiagnostics = recordFrame(
                                    current = it.liveViewDiagnostics,
                                    nowMillis = System.currentTimeMillis(),
                                    sourceUrl = nextUrl,
                                ),
                            )
                        } else {
                            it
                        }
                    }
                }

                val frameIntervalMillis = fpsToFrameIntervalMillis(_uiState.value.liveViewFrameRateFps)
                val elapsedMillis = SystemClock.elapsedRealtime() - frameStartedAt
                delay((frameIntervalMillis - elapsedMillis).coerceAtLeast(0L))
            }
        }
    }

    private fun stopLiveViewLoop() {
        liveViewJob?.cancel()
        liveViewJob = null
    }

    private fun pauseLiveViewForBulb() {
        stopLiveViewLoop()
        repository.setNativeLiveViewRenderingEnabled(false)
    }

    private suspend fun resumeLiveViewAfterBulb() {
        repository.setNativeLiveViewRenderingEnabled(_uiState.value.liveViewAutoRefresh)
        if (!_uiState.value.liveViewAutoRefresh) return
        refreshLiveViewFrameInternal(reportErrors = false)
        startLiveViewLoopIfNeeded()
    }

    override fun onCleared() {
        stopLiveViewLoop()
        detachNativeLiveViewListener()
        cancelMediaDownload()
        cancelMediaThumbnailLoads()
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            repository.disconnect()
        }
        super.onCleared()
    }

    private fun CameraUiState.withClearedSession(
        baseUrl: String,
        error: String?,
    ): CameraUiState = copy(
        baseUrl = baseUrl,
        previewMode = false,
        transport = null,
        info = null,
        status = null,
        capabilities = null,
        mediaItems = emptyList(),
        mediaThumbnails = emptyMap(),
        mediaThumbnailLoadingIds = emptySet(),
        mediaPreviewItem = null,
        mediaPreviewBytes = null,
        mediaPreviewLoading = false,
        activeMediaDownloadName = null,
        mediaDownloadProgress = null,
        lastDownloadedMediaName = null,
        lastDeletedMediaName = null,
        liveViewFrameUrl = null,
        liveViewBitmap = null,
        nativeLiveViewSession = null,
        liveViewMagnification = null,
        liveViewDiagnostics = LiveViewDiagnostics(),
        liveViewAspectRatio = 16f / 9f,
        networkDiagnostics = CameraNetworkDiagnostics.Empty,
        focusPoint = null,
        focusFeedback = null,
        error = error,
        errorOperation = null,
    )

    private fun fpsToFrameIntervalMillis(fps: Int): Long =
        (1_000L / fps.coerceIn(MIN_LIVE_VIEW_FPS, MAX_LIVE_VIEW_FPS)).coerceAtLeast(1L)

    private fun CameraUiState.withDeletedMedia(item: CameraMediaItem): CameraUiState {
        val deletesOpenPreview = mediaPreviewItem?.id == item.id
        return copy(
            mediaItems = mediaItems.filterNot { it.id == item.id },
            mediaThumbnails = mediaThumbnails - item.id,
            mediaThumbnailLoadingIds = mediaThumbnailLoadingIds - item.id,
            mediaPreviewItem = mediaPreviewItem.takeUnless { deletesOpenPreview },
            mediaPreviewBytes = mediaPreviewBytes.takeUnless { deletesOpenPreview },
            mediaPreviewLoading = mediaPreviewLoading && !deletesOpenPreview,
            lastDownloadedMediaName = lastDownloadedMediaName.takeUnless { it == item.name },
            lastDeletedMediaName = item.name,
        )
    }

    private fun cancelMediaThumbnailLoads() {
        mediaThumbnailGeneration += 1
        mediaThumbnailJobs.values.forEach(Job::cancel)
        mediaThumbnailJobs.clear()
        unavailableMediaThumbnailIds.clear()
    }

    private fun recordFrame(
        current: LiveViewDiagnostics,
        nowMillis: Long,
        frameBytes: Int? = current.frameBytes,
        contentType: String? = current.contentType,
        sourceUrl: String? = current.sourceUrl,
    ): LiveViewDiagnostics {
        frameTimesMillis.addLast(nowMillis)
        while (frameTimesMillis.size > FPS_WINDOW_SIZE) frameTimesMillis.removeFirst()
        return LiveViewDiagnostics(
            observedFps = rollingFps(frameTimesMillis.toList()),
            frameBytes = frameBytes,
            contentType = contentType,
            sourceUrl = sourceUrl,
            lastFrameAtMillis = nowMillis,
        )
    }

    private fun configureNativeLiveViewSession(session: NativeLiveViewSession?, fps: Int) {
        session ?: return
        session.setTargetFps(fps)
        session.setRenderingEnabled(_uiState.value.liveViewAutoRefresh)
        session.setListener { event ->
            viewModelScope.launch {
                if (_uiState.value.nativeLiveViewSession !== session && repository.nativeLiveViewSession() !== session) {
                    return@launch
                }
                when (event) {
                    is NativeLiveViewEvent.FrameRendered -> _uiState.update {
                        it.copy(
                            liveViewAspectRatio = event.width.toFloat() / event.height.coerceAtLeast(1),
                            liveViewDiagnostics = recordFrame(
                                current = it.liveViewDiagnostics,
                                nowMillis = event.atMillis,
                                frameBytes = event.encodedBytes,
                                contentType = session.contentType,
                                sourceUrl = session.sourceUrl,
                            ),
                            error = if (it.errorOperation == CameraOperation.LIVE_VIEW) null else it.error,
                            errorOperation = it.errorOperation.takeUnless { operation -> operation == CameraOperation.LIVE_VIEW },
                        )
                    }

                    is NativeLiveViewEvent.VideoSizeChanged -> if (event.width > 0 && event.height > 0) {
                        _uiState.update { it.copy(liveViewAspectRatio = event.width.toFloat() / event.height) }
                    }

                    is NativeLiveViewEvent.Failed -> _uiState.update {
                        it.copy(error = event.message, errorOperation = CameraOperation.LIVE_VIEW)
                    }
                }
            }
        }
    }

    private fun detachNativeLiveViewListener() {
        _uiState.value.nativeLiveViewSession?.setListener(null)
    }

    private fun resetFrameMetrics() {
        frameTimesMillis.clear()
    }

    private companion object {
        const val PREFERENCES_NAME = "camera_connection"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_BRIDGE_BASE_URL = "bridge_base_url"
        const val KEY_CONNECTION_TARGET = "connection_target"
        const val CAPTURE_FLASH_MILLIS = 120L
        const val FOCUS_FEEDBACK_MILLIS = 1_200L
        const val FPS_WINDOW_SIZE = 30
        val CAPABILITY_EVIDENCE_OPERATIONS = setOf(
            CameraOperation.SETTING,
            CameraOperation.CAPTURE,
            CameraOperation.RECORDING,
            CameraOperation.FOCUS,
            CameraOperation.LIVE_VIEW,
            CameraOperation.MEDIA,
        )
    }
}
