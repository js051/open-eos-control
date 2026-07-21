package dev.openeos.control.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraSession
import dev.openeos.control.data.LiveViewRequest
import dev.openeos.control.data.LiveViewSize
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
    private val frameTimesMillis = ArrayDeque<Long>()
    private var preferencesLoaded = false
    private var networkRoutingConfigured = false

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
                baseUrl = preferences.getString(KEY_BASE_URL, it.baseUrl) ?: it.baseUrl,
                username = preferences.getString(KEY_USERNAME, it.username) ?: it.username,
            )
        }
        if (_uiState.value.usbDiagnostics.scannedAtMillis == 0L) {
            refreshUsbDiagnostics(context.applicationContext)
        }
    }

    fun setUiMode(mode: UiMode) {
        _uiState.update { it.copy(uiMode = mode, activeSettingPicker = null) }
        if (mode == UiMode.MEDIA && _uiState.value.mediaItems.isEmpty()) refreshMedia()
    }

    fun setCaptureMode(mode: CaptureMode) = _uiState.update { it.copy(captureMode = mode, activeSettingPicker = null) }

    fun setHudVisible(visible: Boolean) = _uiState.update { it.copy(hudVisible = visible) }

    fun setGridVisible(visible: Boolean) = _uiState.update { it.copy(showGrid = visible) }

    fun openSettingPicker(picker: SettingPicker) = _uiState.update { it.copy(activeSettingPicker = picker) }

    fun closeSettingPicker() = _uiState.update { it.copy(activeSettingPicker = null) }

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
        resetFrameMetrics()
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
        resetFrameMetrics()
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        val session = repository.connect(
            baseUrl = _uiState.value.baseUrl,
            username = _uiState.value.username,
            password = _uiState.value.password,
            request = LiveViewRequest(
                fps = _uiState.value.liveViewFrameRateFps,
                size = _uiState.value.liveViewSize,
            ),
        )
        applyConnectedSession(session)
    }

    fun connectUsb(deviceName: String, vendorId: Int, productId: Int) = runCamera(CameraOperation.CONNECT) {
        stopLiveViewLoop()
        resetFrameMetrics()
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        val session = repository.connectUsb(
            deviceName = deviceName,
            vendorId = vendorId,
            productId = productId,
            request = LiveViewRequest(
                fps = _uiState.value.liveViewFrameRateFps,
                size = _uiState.value.liveViewSize,
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
        _uiState.update {
            it.copy(
                transport = session.transport,
                info = session.info,
                status = session.status,
                capabilities = session.capabilities,
                networkDiagnostics = session.networkDiagnostics,
                liveViewFrameUrl = session.liveViewFrameUrl,
                liveViewBitmap = null,
                liveViewFrameRateFps = supportedFps,
            )
        }
        if (session.capabilities.matrix.supports(CameraFeature.LIVE_VIEW)) {
            refreshLiveViewFrameInternal(reportErrors = true)
            startLiveViewLoopIfNeeded()
        }
    }

    fun rememberConnection(context: Context) {
        val state = _uiState.value
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, state.baseUrl)
            .putString(KEY_USERNAME, state.username)
            .apply()
    }

    fun disconnect() {
        stopLiveViewLoop()
        cancelMediaDownload()
        resetFrameMetrics()
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
        _uiState.update {
            it.copy(
                status = status,
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

    fun setLiveViewSize(size: LiveViewSize) {
        if (_uiState.value.liveViewSize == size) return
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
        _uiState.update { it.copy(capabilities = capabilities) }
        refreshLiveViewFrameInternal(reportErrors = true)
        startLiveViewLoopIfNeeded()
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

    fun setCameraSetting(key: String, value: String) = runCamera(CameraOperation.SETTING) {
        if (_uiState.value.previewMode) {
            _uiState.update { state ->
                state.copy(
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
        _uiState.update {
            it.copy(
                status = status,
                capabilities = capabilities,
            )
        }
        refreshLiveViewFrameInternal(reportErrors = false)
        startLiveViewLoopIfNeeded()
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

    fun focusWithShutter() {
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

    fun refreshMedia() {
        if (!_uiState.value.connected || _uiState.value.previewMode) return
        runCamera(CameraOperation.MEDIA) {
            val items = repository.listMedia()
            _uiState.update { it.copy(mediaItems = items, lastDownloadedMediaName = null) }
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

    override fun onCleared() {
        stopLiveViewLoop()
        cancelMediaDownload()
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
        activeMediaDownloadName = null,
        mediaDownloadProgress = null,
        lastDownloadedMediaName = null,
        liveViewFrameUrl = null,
        liveViewBitmap = null,
        liveViewDiagnostics = LiveViewDiagnostics(),
        networkDiagnostics = CameraNetworkDiagnostics.Empty,
        focusPoint = null,
        focusFeedback = null,
        error = error,
        errorOperation = null,
    )

    private fun fpsToFrameIntervalMillis(fps: Int): Long =
        (1_000L / fps.coerceIn(MIN_LIVE_VIEW_FPS, MAX_LIVE_VIEW_FPS)).coerceAtLeast(1L)

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

    private fun resetFrameMetrics() {
        frameTimesMillis.clear()
    }

    private companion object {
        const val PREFERENCES_NAME = "camera_connection"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val CAPTURE_FLASH_MILLIS = 120L
        const val FOCUS_FEEDBACK_MILLIS = 1_200L
        const val FPS_WINDOW_SIZE = 30
    }
}
