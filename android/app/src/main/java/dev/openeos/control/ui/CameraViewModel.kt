package dev.openeos.control.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraFileNamingField
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraSession
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.FocusDriveDirection
import dev.openeos.control.data.FocusDriveStep
import dev.openeos.control.data.LiveViewRequest
import dev.openeos.control.data.LiveViewMagnification
import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.LiveViewSource
import dev.openeos.control.data.MAX_PTP_OBJECT_BYTES
import dev.openeos.control.data.NativeLiveViewEvent
import dev.openeos.control.data.NativeLiveViewAudioStatus
import dev.openeos.control.data.NativeLiveViewSession
import dev.openeos.control.data.UsbPtpDiagnosticScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

private data class MediaUploadMetadata(
    val name: String,
    val sizeBytes: Long?,
)

class CameraViewModel(
    private val repository: CameraRepository = CameraRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
    private var liveViewJob: Job? = null
    private var eventPollingJob: Job? = null
    private var eventPollingGeneration = 0L
    private var mediaDownloadJob: Job? = null
    private var mediaUploadJob: Job? = null
    private var mediaLibraryJob: Job? = null
    private var mediaLibraryGeneration = 0L
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
        if (mode != UiMode.MEDIA) _uiState.value.mediaStreamSource?.close()
        _uiState.update {
            it.copy(
                uiMode = mode,
                activeSettingPicker = null,
                mediaPreviewItem = if (mode == UiMode.MEDIA) it.mediaPreviewItem else null,
                mediaPreviewBytes = if (mode == UiMode.MEDIA) it.mediaPreviewBytes else null,
                mediaPreviewLoading = mode == UiMode.MEDIA && it.mediaPreviewLoading,
                mediaStreamSource = if (mode == UiMode.MEDIA) it.mediaStreamSource else null,
            )
        }
        if (mode == UiMode.MEDIA && _uiState.value.mediaItems.isEmpty()) refreshMedia()
    }

    fun setCaptureMode(mode: CaptureMode) {
        val state = _uiState.value
        if (state.captureMode == mode || !captureModeSwitchEnabled(state)) return
        val setting = state.capabilities?.captureModeSetting()
        if (setting?.key?.isShootingModeKey() == true && setting.currentCaptureMode() == CaptureMode.PHOTO) {
            lastPhotoShootingMode = setting.value
        }
        val target = setting?.valueForCaptureMode(mode, lastPhotoShootingMode)
        if (target != null && target != setting.value) {
            setCameraSetting(setting.key, target)
        } else {
            _uiState.update { it.copy(captureMode = mode, activeSettingPicker = null) }
        }
    }

    fun setHudVisible(visible: Boolean) = _uiState.update { it.copy(hudVisible = visible) }

    fun setGridVisible(visible: Boolean) = _uiState.update { it.copy(showGrid = visible) }

    fun setOperatorConfirmation(feature: CameraFeature, confirmed: Boolean) {
        _uiState.update { current ->
            val eligible = feature in physicalValidationSummary(current).eligibleFeatures
            current.copy(
                operatorConfirmedFeatures = when {
                    confirmed && eligible -> current.operatorConfirmedFeatures + feature
                    !confirmed -> current.operatorConfirmedFeatures - feature
                    else -> current.operatorConfirmedFeatures
                },
            )
        }
    }

    fun setHistogramVisible(visible: Boolean) = updateMonitorSettings {
        copy(histogramVisible = visible, waveformVisible = if (visible) false else waveformVisible)
    }

    fun setWaveformVisible(visible: Boolean) = updateMonitorSettings {
        copy(waveformVisible = visible, histogramVisible = if (visible) false else histogramVisible)
    }

    fun setZebraThreshold(thresholdPercent: Int?) = updateMonitorSettings {
        copy(zebraThresholdPercent = thresholdPercent?.coerceIn(50, 100))
    }

    fun setFalseColorEnabled(enabled: Boolean) = updateMonitorSettings { copy(falseColorEnabled = enabled) }

    fun setFocusPeakingEnabled(enabled: Boolean) = updateMonitorSettings { copy(focusPeakingEnabled = enabled) }

    fun setFrameGuide(guide: LiveViewFrameGuide) = updateMonitorSettings { copy(frameGuide = guide) }

    fun setSafeAreaVisible(visible: Boolean) = updateMonitorSettings { copy(safeAreaVisible = visible) }

    fun setDesqueeze(desqueeze: LiveViewDesqueeze) = updateMonitorSettings { copy(desqueeze = desqueeze) }

    fun importCubeLut(name: String, text: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { parseCubeLut(text, name) } }
            result.fold(
                onSuccess = { lut ->
                    _uiState.update {
                        it.copy(
                            monitorSettings = it.monitorSettings.copy(cubeLut = lut),
                            error = null,
                            errorOperation = null,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            error = "3D LUT: ${error.message ?: error::class.java.simpleName}",
                            errorOperation = CameraOperation.LIVE_VIEW,
                        )
                    }
                },
            )
        }
    }

    fun clearCubeLut() = updateMonitorSettings { copy(cubeLut = null) }

    fun reportCubeLutError(message: String) {
        _uiState.update {
            it.copy(error = "3D LUT: $message", errorOperation = CameraOperation.LIVE_VIEW)
        }
    }

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
                .copy(ccapiSimulatorMode = false)
        }
    }

    fun useDirectCameraHttpsPreset() {
        if (_uiState.value.connected) return
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = CameraRepository.DEFAULT_CAMERA_HTTPS_URL, error = null)
                .copy(ccapiSimulatorMode = false)
        }
    }

    fun useDevSimulatorPreset() {
        if (_uiState.value.connected) return
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = CameraRepository.DEV_EMULATOR_SIMULATOR_URL, error = null)
                .copy(ccapiSimulatorMode = true)
        }
    }

    fun enterOfflinePreview() {
        stopLiveViewLoop()
        stopEventPollingLoop()
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
        stopEventPollingLoopAndJoin()
        stopLiveViewLoop()
        detachNativeLiveViewListener()
        cancelMediaLibraryLoad()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        val session = repository.connect(
            baseUrl = _uiState.value.baseUrl,
            username = _uiState.value.username,
            password = _uiState.value.password,
            simulatorMode = _uiState.value.ccapiSimulatorMode,
            request = LiveViewRequest(
                fps = _uiState.value.liveViewFrameRateFps,
                size = _uiState.value.liveViewSize,
                source = _uiState.value.liveViewSource,
            ),
        )
        applyConnectedSession(session)
    }

    fun connectUsb(deviceName: String, vendorId: Int, productId: Int) = runCamera(CameraOperation.CONNECT) {
        stopEventPollingLoopAndJoin()
        stopLiveViewLoop()
        detachNativeLiveViewListener()
        cancelMediaLibraryLoad()
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
        stopEventPollingLoopAndJoin()
        stopLiveViewLoop()
        detachNativeLiveViewListener()
        cancelMediaLibraryLoad()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        val state = _uiState.value
        val selectedCamera = state.bridgeCameras.firstOrNull { it.id == state.selectedBridgeCameraId }
        val session = repository.connectBridge(
            baseUrl = state.bridgeBaseUrl,
            token = state.bridgeToken,
            cameraId = state.selectedBridgeCameraId,
            cameraEngine = selectedCamera?.engine,
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
        val activeSource = session.activeLiveViewSource ?: session.nativeLiveViewSession?.source ?: when {
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
                liveViewMagnification = session.capabilities.liveView.currentMagnification,
                liveViewDiagnostics = session.nativeLiveViewSession?.let { native ->
                    LiveViewDiagnostics(contentType = native.contentType, sourceUrl = native.sourceUrl)
                } ?: it.liveViewDiagnostics,
                liveViewAudioStatus = session.nativeLiveViewSession?.audioStatus
                    ?: NativeLiveViewAudioStatus.None,
                captureMode = captureMode ?: it.captureMode,
                error = session.liveViewStartError,
                errorOperation = session.liveViewStartError?.let { CameraOperation.LIVE_VIEW },
            )
        }
        if (session.capabilities.matrix.supports(CameraFeature.LIVE_VIEW) && session.status.temperature?.liveViewAllowed != false) {
            if (session.nativeLiveViewSession == null) {
                refreshLiveViewFrameInternal(reportErrors = true)
                startLiveViewLoopIfNeeded()
            }
        }
        startEventPollingIfSupported()
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
        stopEventPollingLoop()
        detachNativeLiveViewListener()
        cancelMediaLibraryLoad()
        closeMediaStream()
        cancelMediaDownload()
        val uploadJob = mediaUploadJob
        mediaUploadJob = null
        uploadJob?.cancel()
        cancelMediaThumbnailLoads()
        resetFrameMetrics()
        lastPhotoShootingMode = null
        if (_uiState.value.previewMode) {
            _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
            return
        }
        viewModelScope.launch {
            try {
                uploadJob?.join()
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
                liveViewMagnification = capabilities.liveView.currentMagnification
                    ?: it.liveViewMagnification?.takeIf { value -> value in capabilities.liveView.magnifications },
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

    fun setRtpAudioEnabled(enabled: Boolean) {
        val state = _uiState.value
        val session = state.nativeLiveViewSession ?: return
        if (!enabled) {
            session.setAudioEnabled(false)
            _uiState.update { it.copy(liveViewAudioStatus = it.liveViewAudioStatus.copy(enabled = false)) }
            return
        }
        if (state.liveViewSource != LiveViewSource.CCAPI_RTP || !state.liveViewAudioStatus.available) return
        session.setAudioEnabled(true)
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
        restartLiveViewInternal()
    }

    private suspend fun restartLiveViewInternal() {
        if (
            _uiState.value.previewMode ||
            !_uiState.value.supports(CameraFeature.LIVE_VIEW) ||
            !_uiState.value.liveViewTemperatureAllowed
        ) return
        stopLiveViewLoop()
        val effectiveRequest = repository.restartLiveView()
        val capabilities = repository.refreshCapabilities()
        val nativeSession = repository.nativeLiveViewSession()
        configureNativeLiveViewSession(nativeSession, _uiState.value.liveViewFrameRateFps)
        _uiState.update {
            it.copy(
                capabilities = capabilities,
                nativeLiveViewSession = nativeSession,
                liveViewSource = nativeSession?.source ?: it.liveViewSource,
                liveViewSize = effectiveRequest.size,
                liveViewBitmap = null,
                liveViewFrameUrl = null,
                liveViewMagnification = null,
                liveViewDiagnostics = nativeSession?.let { session ->
                    LiveViewDiagnostics(contentType = session.contentType, sourceUrl = session.sourceUrl)
                } ?: LiveViewDiagnostics(),
                liveViewAudioStatus = nativeSession?.audioStatus
                    ?: NativeLiveViewAudioStatus.None,
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
        val selectedCaptureMode = when {
            key.isMovieModeKey() && value.equals("on", ignoreCase = true) -> CaptureMode.VIDEO
            key.isMovieModeKey() && value.equals("off", ignoreCase = true) -> CaptureMode.PHOTO
            key.isShootingModeKey() -> captureModeForShootingValue(value)
            else -> null
        }
        runCamera(CameraOperation.SETTING) {
            if (_uiState.value.previewMode) {
                if (key.isShootingModeKey() && selectedCaptureMode == CaptureMode.PHOTO) {
                    lastPhotoShootingMode = value
                }
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
            val captureMode = if (key.isCaptureModeKey()) {
                captureModeFrom(capabilities)
            } else {
                selectedCaptureMode ?: captureModeFrom(capabilities)
            }
            if (key.isShootingModeKey() && selectedCaptureMode == CaptureMode.PHOTO) {
                lastPhotoShootingMode = value
            }
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

    fun syncCameraClock() = runCamera(CameraOperation.CLOCK) {
        val state = _uiState.value
        if (state.previewMode || !state.supports(CameraFeature.CAMERA_CLOCK_SYNC)) return@runCamera
        val status = repository.syncCameraClock()
        _uiState.update {
            it.copy(
                status = status,
                lastClockSyncAtMillis = System.currentTimeMillis(),
            )
        }
    }

    fun createDirectory(name: String) = runCamera(CameraOperation.DIRECTORY) {
        val state = _uiState.value
        if (state.previewMode || !state.supports(CameraFeature.DIRECTORY_CONTROL)) return@runCamera
        val created = repository.createDirectory(name)
        val capabilities = repository.refreshCapabilities()
        _uiState.update {
            it.copy(
                capabilities = capabilities,
                lastCreatedDirectoryName = created,
            )
        }
    }

    fun setFileNaming(field: CameraFileNamingField, value: String) = runCamera(CameraOperation.SETTING) {
        val state = _uiState.value
        if (
            state.previewMode ||
            !state.supports(CameraFeature.FILE_NAMING_CONTROL) ||
            state.capabilities?.fileNaming?.accepts(field, value) != true
        ) return@runCamera
        val updated = repository.setFileNaming(field, value)
        _uiState.update { current ->
            current.copy(
                capabilities = current.capabilities?.copy(fileNaming = updated),
            )
        }
    }

    fun sleepCamera() {
        val state = _uiState.value
        if (
            !state.connected ||
            state.previewMode ||
            !state.supports(CameraFeature.CAMERA_SLEEP) ||
            state.status?.recording == true ||
            state.bulbExposureActive ||
            state.busy
        ) return

        stopLiveViewLoop()
        stopEventPollingLoop()
        detachNativeLiveViewListener()
        cancelMediaDownload()
        val uploadJob = mediaUploadJob
        mediaUploadJob = null
        uploadJob?.cancel()
        cancelMediaThumbnailLoads()
        runCamera(
            operation = CameraOperation.POWER,
            onError = {
                if (_uiState.value.connected) {
                    startEventPollingIfSupported()
                    restartLiveView()
                }
            },
        ) {
            uploadJob?.join()
            repository.sleepCamera()
            runCatching { repository.disconnect() }
            closeMediaStream()
            resetFrameMetrics()
            lastPhotoShootingMode = null
            _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        }
    }

    fun cleanSensor(autoPowerOff: Boolean) {
        val state = _uiState.value
        if (
            !state.connected ||
            state.previewMode ||
            !state.supports(CameraFeature.SENSOR_CLEANING) ||
            state.status?.recording == true ||
            state.bulbExposureActive ||
            state.busy
        ) return

        val restoreLiveView = state.supports(CameraFeature.LIVE_VIEW)
        var restoreSessionWork = false
        stopLiveViewLoop()
        stopEventPollingLoop()
        detachNativeLiveViewListener()
        runCamera(
            operation = CameraOperation.MAINTENANCE,
            onError = { restoreSessionWork = true },
            afterFinally = {
                if (restoreSessionWork && _uiState.value.connected) {
                    startEventPollingIfSupported()
                    if (restoreLiveView) restartLiveView()
                }
            },
        ) {
            repository.cleanSensor(autoPowerOff)
            if (autoPowerOff) {
                runCatching { repository.disconnect() }
                closeMediaStream()
                resetFrameMetrics()
                lastPhotoShootingMode = null
                _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
            } else {
                val status = repository.refreshStatus()
                val capabilities = repository.refreshCapabilities()
                _uiState.update { it.copy(status = status, capabilities = capabilities) }
                restoreSessionWork = true
            }
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
        val active = _uiState.value.bulbExposureActive
        if (!_uiState.value.bulbMode || (!active && !_uiState.value.supports(CameraFeature.BULB_EXPOSURE))) {
            return@runCamera
        }
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
        val state = _uiState.value
        if (
            state.captureMode != CaptureMode.PHOTO ||
            !state.supports(CameraFeature.LIVE_VIEW_MAGNIFICATION) ||
            magnification !in state.capabilities?.liveView?.magnifications.orEmpty()
        ) return
        if (state.previewMode) {
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
        if (!_uiState.value.connected || _uiState.value.previewMode || _uiState.value.mediaLibraryLoading) return
        val generation = ++mediaLibraryGeneration
        cancelMediaThumbnailLoads()
        _uiState.value.mediaStreamSource?.close()
        _uiState.update {
            it.copy(
                mediaThumbnails = emptyMap(),
                mediaThumbnailLoadingIds = emptySet(),
                mediaPreviewItem = null,
                mediaPreviewBytes = null,
                mediaPreviewLoading = false,
                mediaStreamSource = null,
                mediaLibraryLoading = true,
                mediaLibraryLoadStatus = MediaLibraryLoadStatus.LOADING,
            )
        }
        val job = viewModelScope.launch {
            try {
                val items = repository.listMedia { partialItems ->
                    if (generation == mediaLibraryGeneration) {
                        applyMediaItems(partialItems)
                    }
                }
                if (generation != mediaLibraryGeneration) return@launch
                val capabilities = runCatching { repository.refreshCapabilities() }.getOrNull()
                _uiState.update {
                    it.copy(
                        mediaItems = items,
                        mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE,
                        capabilities = capabilities ?: it.capabilities,
                        lastDownloadedMediaName = null,
                        lastUploadedMediaName = null,
                        lastDeletedMediaName = null,
                    )
                }
                refreshCapabilityEvidence()
            } catch (exception: CancellationException) {
                if (generation == mediaLibraryGeneration) {
                    _uiState.update {
                        it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.CANCELLED)
                    }
                }
                throw exception
            } catch (exception: Exception) {
                exception.printStackTrace()
                _uiState.update {
                    it.copy(
                        mediaLibraryLoadStatus = MediaLibraryLoadStatus.FAILED,
                        error = formatException(exception),
                        errorOperation = CameraOperation.MEDIA,
                    )
                }
            } finally {
                if (generation == mediaLibraryGeneration) {
                    _uiState.update { it.copy(mediaLibraryLoading = false) }
                }
            }
        }
        mediaLibraryJob = job
        job.invokeOnCompletion {
            if (mediaLibraryJob === job) mediaLibraryJob = null
        }
    }

    fun loadMediaThumbnail(item: CameraMediaItem) {
        val state = _uiState.value
        state.mediaThumbnails[item.id]?.let {
            _uiState.update { current ->
                if (item.id !in current.mediaThumbnails) return@update current
                current.copy(mediaThumbnails = touchMediaCacheEntry(current.mediaThumbnails, item.id))
            }
            return
        }
        if (
            state.previewMode ||
            !state.supports(CameraFeature.MEDIA_THUMBNAIL) ||
            item.id in state.mediaThumbnailLoadingIds ||
            item.id in mediaThumbnailJobs ||
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
                    val retained = current.mediaThumbnails.entries
                        .asSequence()
                        .filterNot { it.key == item.id }
                        .toList()
                        .takeLast(MAX_MEDIA_THUMBNAIL_CACHE_ITEMS - 1)
                        .associateTo(linkedMapOf()) { it.toPair() }
                    current.copy(mediaThumbnails = retained + (item.id to bitmap))
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
        val isVideo = item.isVideo
        if (
            state.previewMode ||
            state.isBusy(CameraOperation.MEDIA) ||
            if (isVideo) !item.streamAvailable
            else !state.supports(CameraFeature.MEDIA_PREVIEW) || !item.previewAvailable
        ) return
        state.mediaStreamSource?.close()
        _uiState.update {
            it.copy(
                mediaPreviewItem = item,
                mediaPreviewBytes = null,
                mediaPreviewLoading = true,
                mediaStreamSource = null,
            )
        }
        runCamera(
            operation = CameraOperation.MEDIA,
            onError = {
                _uiState.update { current ->
                    if (current.mediaPreviewItem?.id == item.id) current.copy(mediaPreviewLoading = false) else current
                }
            },
        ) {
            val stream = if (isVideo) repository.openMediaStream(item) else null
            val preview = if (isVideo) null else repository.mediaPreview(item)
            _uiState.update { current ->
                if (current.mediaPreviewItem?.id == item.id) {
                    current.copy(
                        mediaPreviewBytes = preview?.bytes,
                        mediaPreviewLoading = false,
                        mediaStreamSource = stream,
                    )
                } else {
                    stream?.close()
                    current
                }
            }
        }
    }

    fun closeMediaPreview() {
        _uiState.value.mediaStreamSource?.close()
        _uiState.update {
            it.copy(
                mediaPreviewItem = null,
                mediaPreviewBytes = null,
                mediaPreviewLoading = false,
                mediaStreamSource = null,
            )
        }
    }

    fun loadMediaInfo(item: CameraMediaItem) {
        val state = _uiState.value
        if (state.previewMode || state.isBusy(CameraOperation.MEDIA) || !state.supports(CameraFeature.MEDIA_BROWSER)) return
        runCamera(CameraOperation.MEDIA) {
            val updated = repository.mediaInfo(item)
            _uiState.update { current -> current.withUpdatedMedia(updated) }
        }
    }

    fun previewAdjacentMedia(items: List<CameraMediaItem>, direction: Int) {
        if (direction == 0) return
        val currentId = _uiState.value.mediaPreviewItem?.id ?: return
        val index = items.indexOfFirst { it.id == currentId }
        val next = items.getOrNull(index + direction) ?: return
        _uiState.value.mediaStreamSource?.close()
        _uiState.update { it.copy(mediaPreviewItem = null, mediaPreviewBytes = null, mediaStreamSource = null) }
        openMediaPreview(next)
    }

    fun setMediaProtection(item: CameraMediaItem, enabled: Boolean) = updateMediaMetadata(
        item,
        CameraFeature.MEDIA_PROTECT,
        previewUpdate = { it.copy(protected = enabled) },
    ) { repository.setMediaProtection(item, enabled) }

    fun setMediaArchived(item: CameraMediaItem, enabled: Boolean) = updateMediaMetadata(
        item,
        CameraFeature.MEDIA_ARCHIVE,
        previewUpdate = { it.copy(archived = enabled) },
    ) { repository.setMediaArchived(item, enabled) }

    fun setMediaRating(item: CameraMediaItem, rating: Int) {
        if (rating !in 0..5) return
        updateMediaMetadata(
            item,
            CameraFeature.MEDIA_RATING,
            previewUpdate = { it.copy(rating = rating) },
        ) { repository.setMediaRating(item, rating) }
    }

    fun setMediaRotation(item: CameraMediaItem, degrees: Int) {
        if (degrees !in setOf(0, 90, 180, 270)) return
        updateMediaMetadata(
            item,
            CameraFeature.MEDIA_ROTATE,
            previewUpdate = { it.copy(rotationDegrees = degrees) },
        ) { repository.setMediaRotation(item, degrees) }
    }

    private fun updateMediaMetadata(
        item: CameraMediaItem,
        feature: CameraFeature,
        previewUpdate: (CameraMediaItem) -> CameraMediaItem,
        update: suspend () -> CameraMediaItem,
    ) {
        val state = _uiState.value
        if (state.isBusy(CameraOperation.MEDIA) || !state.supports(feature)) return
        if (state.previewMode) {
            _uiState.update { current ->
                val currentItem = current.mediaItems.firstOrNull { it.id == item.id } ?: item
                current.withUpdatedMedia(previewUpdate(currentItem))
            }
            return
        }
        runCamera(CameraOperation.MEDIA) {
            val updated = update()
            _uiState.update { current -> current.withUpdatedMedia(updated) }
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

    fun uploadMedia(context: Context, sourceUri: Uri) {
        val state = _uiState.value
        if (
            state.previewMode ||
            state.isBusy(CameraOperation.MEDIA) ||
            !state.supports(CameraFeature.MEDIA_UPLOAD) ||
            mediaUploadJob != null ||
            mediaDownloadJob != null
        ) return
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        _uiState.update { it.copy(lastUploadedMediaName = null) }
        val job = launchCameraOperation(CameraOperation.MEDIA) {
            var temporaryFile: File? = null
            try {
                val metadata = withContext(Dispatchers.IO) {
                    resolver.query(
                        sourceUri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        MediaUploadMetadata(
                            name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                                ?.let(cursor::getString)
                                .orEmpty(),
                            sizeBytes = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                                ?.let(cursor::getLong),
                        )
                    }
                } ?: MediaUploadMetadata(
                    name = sourceUri.lastPathSegment?.substringAfterLast('/').orEmpty(),
                    sizeBytes = null,
                )
                val name = metadata.name.trim()
                check(name.isNotEmpty()) { "Android could not determine the selected media filename." }
                _uiState.update {
                    it.copy(
                        activeMediaUploadName = name,
                        mediaUploadProgress = CameraMediaTransferProgress(0L, metadata.sizeBytes),
                        lastUploadedMediaName = null,
                    )
                }
                val result = withContext(Dispatchers.IO) {
                    val cached = File(appContext.cacheDir, "media-upload-${UUID.randomUUID()}.tmp")
                    temporaryFile = cached
                    val rawInput = resolver.openInputStream(sourceUri)
                        ?: error("Android could not open the selected upload source.")
                    val resolvedSize = BufferedInputStream(rawInput).use { input ->
                        FileOutputStream(cached).buffered().use { output ->
                            val buffer = ByteArray(MEDIA_UPLOAD_BUFFER_BYTES)
                            var copied = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                copied += count
                                check(copied <= MAX_MEDIA_UPLOAD_BYTES) {
                                    "Selected media exceeds the $MAX_MEDIA_UPLOAD_BYTES-byte upload limit."
                                }
                                output.write(buffer, 0, count)
                                _uiState.update { current ->
                                    current.copy(
                                        mediaUploadProgress = CameraMediaTransferProgress(
                                            copied,
                                            metadata.sizeBytes?.takeIf { it > 0L },
                                        )
                                    )
                                }
                            }
                            copied
                        }
                    }
                    check(resolvedSize in 1L..MAX_MEDIA_UPLOAD_BYTES) {
                        "Selected media size must be from 1 through $MAX_MEDIA_UPLOAD_BYTES bytes."
                    }
                    BufferedInputStream(FileInputStream(cached)).use { input ->
                        repository.uploadMedia(
                            name = name,
                            sizeBytes = resolvedSize,
                            contentType = resolver.getType(sourceUri),
                            source = input,
                        ) { progress ->
                            _uiState.update { current -> current.copy(mediaUploadProgress = progress) }
                        }
                    }
                }
                val finishUpload: suspend () -> Unit = {
                    _uiState.update {
                        it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.LOADING)
                    }
                    val items = try {
                        repository.listMedia()
                    } catch (exception: Exception) {
                        _uiState.update {
                            it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.FAILED)
                        }
                        throw exception
                    }
                    check(
                        items.any {
                            it.id == result.item.id ||
                                (it.name == result.item.name && it.sizeBytes == result.item.sizeBytes)
                        }
                    ) {
                        "The uploaded media was not present in the camera's refreshed media list."
                    }
                    val capabilities = runCatching { repository.refreshCapabilities() }.getOrNull()
                    cancelMediaThumbnailLoads()
                    _uiState.update {
                        it.copy(
                            mediaItems = items,
                            mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE,
                            mediaThumbnails = emptyMap(),
                            capabilities = capabilities ?: it.capabilities,
                            lastUploadedMediaName = result.item.name,
                        )
                    }
                }
                if (state.transport == CameraTransport.USB_PTP) {
                    withContext(NonCancellable) { finishUpload() }
                } else {
                    finishUpload()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { temporaryFile?.delete() }
                _uiState.update { it.copy(activeMediaUploadName = null, mediaUploadProgress = null) }
            }
        }
        mediaUploadJob = job
        job?.invokeOnCompletion {
            if (mediaUploadJob === job) mediaUploadJob = null
        }
    }

    fun cancelMediaUpload() {
        val job = mediaUploadJob ?: return
        val state = _uiState.value
        if (state.transport !in setOf(CameraTransport.USB_PTP, CameraTransport.DESKTOP_BRIDGE)) {
            job.cancel()
            return
        }
        val name = state.activeMediaUploadName
        val sizeBytes = state.mediaUploadProgress?.totalBytes
        mediaUploadJob = null
        viewModelScope.launch {
            job.cancelAndJoin()
            if (_uiState.value.lastUploadedMediaName != null) return@launch
            if (state.transport == CameraTransport.DESKTOP_BRIDGE && name != null) {
                reconcileCancelledBridgeUpload(name, sizeBytes)
                return@launch
            }
            withContext(NonCancellable + Dispatchers.IO) { runCatching { repository.disconnect() } }
            closeMediaStream()
            _uiState.update { current ->
                if (current.transport == CameraTransport.USB_PTP) {
                    current.withClearedSession(
                        baseUrl = current.baseUrl,
                        error = "USB upload was interrupted before commit confirmation. Reconnect and refresh media before retrying.",
                    )
                } else {
                    current
                }
            }
        }
    }

    private suspend fun reconcileCancelledBridgeUpload(name: String, sizeBytes: Long?) {
        _uiState.update {
            it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.LOADING)
        }
        val items = withContext(NonCancellable + Dispatchers.IO) {
            runCatching { repository.listMedia() }.getOrNull()
        } ?: run {
            _uiState.update {
                it.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.FAILED)
            }
            return
        }
        val uploaded = items.firstOrNull { item ->
            item.name.equals(name, ignoreCase = true) &&
                (sizeBytes == null || item.sizeBytes == sizeBytes)
        }
        cancelMediaThumbnailLoads()
        _uiState.update { current ->
            current.copy(
                mediaItems = items,
                mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE,
                mediaThumbnails = emptyMap(),
                lastUploadedMediaName = uploaded?.name,
            )
        }
    }

    fun deleteMedia(item: CameraMediaItem) {
        val state = _uiState.value
        if (state.isBusy(CameraOperation.MEDIA) || !state.supports(CameraFeature.MEDIA_DELETE)) return
        if (state.previewMode) {
            applyDeletedMedia(item)
            return
        }
        runCamera(CameraOperation.MEDIA) {
            repository.deleteMedia(item)
            applyDeletedMedia(item)
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
        val status = block()
        _uiState.update { it.copy(status = status) }
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
        val setting = capabilities.captureModeSetting() ?: return null
        return setting.currentCaptureMode()?.also { mode ->
            if (mode == CaptureMode.PHOTO && setting.key.isShootingModeKey()) lastPhotoShootingMode = setting.value
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
        afterFinally: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        launchCameraOperation(operation, onError, afterFinally, block)
    }

    private fun launchCameraOperation(
        operation: CameraOperation,
        onError: (Exception) -> Unit = {},
        afterFinally: () -> Unit = {},
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
                afterFinally()
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
            || !_uiState.value.liveViewTemperatureAllowed
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
            || !state.liveViewTemperatureAllowed
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

    private fun startEventPollingIfSupported() {
        stopEventPollingLoop()
        val state = _uiState.value
        if (
            !state.connected ||
            state.previewMode ||
            !state.supports(CameraFeature.EVENT_POLLING)
        ) return

        val generation = eventPollingGeneration
        eventPollingJob = viewModelScope.launch {
            var consecutiveFailures = 0
            while (isActive && generation == eventPollingGeneration) {
                try {
                    val event = repository.pollEvent()
                    consecutiveFailures = 0
                    if (event.changedKeys.isEmpty()) continue
                    val status = repository.refreshStatus()
                    val capabilities = repository.refreshCapabilities()
                    val captureMode = captureModeFrom(capabilities)
                    val mediaResult = if ("contents" in event.changedKeys) {
                        if (capabilities.matrix.supports(CameraFeature.MEDIA_BROWSER)) {
                            _uiState.update { current ->
                                if (
                                    generation == eventPollingGeneration &&
                                    current.connected &&
                                    !current.previewMode
                                ) {
                                    current.copy(mediaLibraryLoadStatus = MediaLibraryLoadStatus.LOADING)
                                } else {
                                    current
                                }
                            }
                            runCatching { repository.listMedia() }
                        } else {
                            Result.success(emptyList())
                        }
                    } else {
                        null
                    }
                    val mediaItems = mediaResult?.getOrNull()
                    if (mediaItems != null) cancelMediaThumbnailLoads()
                    val updateState: (CameraUiState) -> CameraUiState = { current ->
                        if (
                            generation == eventPollingGeneration &&
                            current.connected &&
                            !current.previewMode
                        ) {
                            val refreshed = current.copy(
                                status = status,
                                capabilities = capabilities,
                                captureMode = captureMode ?: current.captureMode,
                                liveViewMagnification = capabilities.liveView.currentMagnification
                                    ?: current.liveViewMagnification?.takeIf { value ->
                                        value in capabilities.liveView.magnifications
                                    },
                                mediaLibraryLoadStatus = when {
                                    mediaResult?.isSuccess == true -> MediaLibraryLoadStatus.COMPLETE
                                    mediaResult?.isFailure == true -> MediaLibraryLoadStatus.FAILED
                                    else -> current.mediaLibraryLoadStatus
                                },
                            )
                            if (mediaItems != null) refreshed.withEventMediaItems(mediaItems) else refreshed
                        } else {
                            current
                        }
                    }
                    if (mediaItems != null) transitionMediaState(updateState) else _uiState.update(updateState)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    consecutiveFailures += 1
                    delay(EVENT_RETRY_DELAYS_MILLIS[(consecutiveFailures - 1).coerceAtMost(EVENT_RETRY_DELAYS_MILLIS.lastIndex)])
                }
            }
        }
    }

    private fun stopEventPollingLoop() {
        eventPollingGeneration += 1
        eventPollingJob?.cancel()
        eventPollingJob = null
    }

    private suspend fun stopEventPollingLoopAndJoin() {
        eventPollingGeneration += 1
        val job = eventPollingJob
        eventPollingJob = null
        job?.cancelAndJoin()
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
        stopEventPollingLoop()
        detachNativeLiveViewListener()
        cancelMediaLibraryLoad()
        closeMediaStream()
        cancelMediaDownload()
        val uploadJob = mediaUploadJob
        mediaUploadJob = null
        uploadJob?.cancel()
        cancelMediaThumbnailLoads()
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            uploadJob?.join()
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
        mediaStreamSource = null,
        mediaLibraryLoading = false,
        mediaLibraryLoadStatus = MediaLibraryLoadStatus.NOT_LOADED,
        activeMediaDownloadName = null,
        mediaDownloadProgress = null,
        lastDownloadedMediaName = null,
        activeMediaUploadName = null,
        mediaUploadProgress = null,
        lastUploadedMediaName = null,
        lastDeletedMediaName = null,
        liveViewFrameUrl = null,
        liveViewBitmap = null,
        nativeLiveViewSession = null,
        liveViewMagnification = null,
        liveViewDiagnostics = LiveViewDiagnostics(),
        liveViewAudioStatus = NativeLiveViewAudioStatus.None,
        liveViewAspectRatio = 16f / 9f,
        networkDiagnostics = CameraNetworkDiagnostics.Empty,
        focusPoint = null,
        focusFeedback = null,
        lastClockSyncAtMillis = null,
        lastCreatedDirectoryName = null,
        operatorConfirmedFeatures = emptySet(),
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
            mediaStreamSource = mediaStreamSource.takeUnless { deletesOpenPreview },
            lastDownloadedMediaName = lastDownloadedMediaName.takeUnless { it == item.name },
            lastDeletedMediaName = item.name,
        )
    }

    private fun CameraUiState.withUpdatedMedia(item: CameraMediaItem): CameraUiState = copy(
        mediaItems = mediaItems.map { current -> if (current.id == item.id) item else current },
        mediaPreviewItem = mediaPreviewItem?.let { current -> if (current.id == item.id) item else current },
    )

    internal fun CameraUiState.withEventMediaItems(items: List<CameraMediaItem>): CameraUiState {
        val itemIds = items.mapTo(hashSetOf(), CameraMediaItem::id)
        val previewStillExists = mediaPreviewItem?.id in itemIds
        return copy(
            mediaItems = items,
            mediaThumbnails = mediaThumbnails.filterKeys(itemIds::contains),
            mediaThumbnailLoadingIds = mediaThumbnailLoadingIds.intersect(itemIds),
            mediaPreviewItem = mediaPreviewItem.takeIf { previewStillExists },
            mediaPreviewBytes = mediaPreviewBytes.takeIf { previewStillExists },
            mediaPreviewLoading = mediaPreviewLoading && previewStillExists,
            mediaStreamSource = mediaStreamSource.takeIf { previewStillExists },
        )
    }

    private fun applyMediaItems(items: List<CameraMediaItem>) = transitionMediaState { current ->
        current.withEventMediaItems(items)
    }

    private fun applyDeletedMedia(item: CameraMediaItem) = transitionMediaState { current ->
        current.withDeletedMedia(item)
    }

    private inline fun transitionMediaState(transform: (CameraUiState) -> CameraUiState) {
        while (true) {
            val current = _uiState.value
            val updated = transform(current)
            if (_uiState.compareAndSet(current, updated)) {
                if (current.mediaStreamSource !== updated.mediaStreamSource) current.mediaStreamSource?.close()
                return
            }
        }
    }

    private fun cancelMediaThumbnailLoads() {
        mediaThumbnailGeneration += 1
        mediaThumbnailJobs.values.forEach(Job::cancel)
        mediaThumbnailJobs.clear()
        unavailableMediaThumbnailIds.clear()
    }

    private fun cancelMediaLibraryLoad() {
        mediaLibraryGeneration += 1
        mediaLibraryJob?.cancel()
        mediaLibraryJob = null
        _uiState.update {
            it.copy(
                mediaLibraryLoading = false,
                mediaLibraryLoadStatus = MediaLibraryLoadStatus.NOT_LOADED,
            )
        }
    }

    private fun closeMediaStream() {
        _uiState.value.mediaStreamSource?.close()
        _uiState.update { it.copy(mediaStreamSource = null) }
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
                    is NativeLiveViewEvent.AudioStatusChanged -> _uiState.update {
                        it.copy(liveViewAudioStatus = event.status)
                    }

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
        const val MEDIA_UPLOAD_BUFFER_BYTES = 64 * 1024
        const val MAX_MEDIA_UPLOAD_BYTES = MAX_PTP_OBJECT_BYTES
        const val MAX_MEDIA_THUMBNAIL_CACHE_ITEMS = 96
        val EVENT_RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 5_000L)
        val CAPABILITY_EVIDENCE_OPERATIONS = setOf(
            CameraOperation.CONNECT,
            CameraOperation.SETTING,
            CameraOperation.CLOCK,
            CameraOperation.CAPTURE,
            CameraOperation.RECORDING,
            CameraOperation.FOCUS,
            CameraOperation.LIVE_VIEW,
            CameraOperation.MEDIA,
        )
    }
}
