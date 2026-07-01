package dev.openeos.control.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CameraViewModel(
    private val repository: CameraRepository = CameraRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
    private var liveViewJob: Job? = null

    fun setBaseUrl(value: String) {
        stopLiveViewLoop()
        _uiState.update { it.withClearedSession(baseUrl = value, error = null) }
    }

    fun useDirectCameraPreset() {
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = CameraRepository.DEFAULT_CAMERA_BASE_URL, error = null)
        }
    }

    fun useDirectCameraHttpsPreset() {
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = CameraRepository.DEFAULT_CAMERA_HTTPS_URL, error = null)
        }
    }

    fun useDevSimulatorPreset() {
        stopLiveViewLoop()
        _uiState.update {
            it.withClearedSession(baseUrl = CameraRepository.DEV_EMULATOR_SIMULATOR_URL, error = null)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun connect() = runCamera {
        stopLiveViewLoop()
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
        val session = repository.connect(_uiState.value.baseUrl)
        _uiState.update {
            it.copy(
                info = session.info,
                status = session.status,
                capabilities = session.capabilities,
                liveViewFrameUrl = session.liveViewFrameUrl,
            )
        }
        startLiveViewLoopIfNeeded()
    }

    fun disconnect() {
        stopLiveViewLoop()
        viewModelScope.launch {
            try {
                repository.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
        _uiState.update { it.withClearedSession(baseUrl = it.baseUrl, error = null) }
    }

    fun refresh() = runCamera {
        _uiState.update {
            it.copy(
                status = repository.refreshStatus(),
                liveViewFrameUrl = repository.nextLiveViewFrameUrl(),
            )
        }
    }

    fun refreshLiveViewFrame() {
        if (!_uiState.value.connected) return
        _uiState.update { it.copy(liveViewFrameUrl = repository.nextLiveViewFrameUrl()) }
    }

    fun setLiveViewAutoRefresh(enabled: Boolean) {
        _uiState.update { it.copy(liveViewAutoRefresh = enabled) }
        if (enabled) {
            refreshLiveViewFrame()
            startLiveViewLoopIfNeeded()
        } else {
            stopLiveViewLoop()
        }
    }

    fun setIso(value: String) = updateStatus { repository.setIso(value) }

    fun setShutter(value: String) = updateStatus { repository.setShutter(value) }

    fun setAperture(value: String) = updateStatus { repository.setAperture(value) }

    fun setWhiteBalance(value: String) = updateStatus { repository.setWhiteBalance(value) }

    fun toggleRecording() = updateStatus {
        repository.toggleRecording(_uiState.value.status?.recording == true)
    }

    fun tapFocus(x: Double, y: Double) = runCamera {
        val result = repository.tapFocus(x, y)
        _uiState.update {
            it.copy(
                focusPoint = FocusPoint(result.x, result.y),
                liveViewFrameUrl = repository.nextLiveViewFrameUrl(),
            )
        }
        startLiveViewLoopIfNeeded()
    }

    private fun updateStatus(block: suspend () -> dev.openeos.control.data.CameraStatus) = runCamera {
        _uiState.update {
            it.copy(
                status = block(),
                liveViewFrameUrl = repository.nextLiveViewFrameUrl(),
            )
        }
        startLiveViewLoopIfNeeded()
    }

    private fun runCamera(block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (exception: Exception) {
                exception.printStackTrace()
                val detailedMessage = buildString {
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
                _uiState.update {
                    it.copy(error = detailedMessage)
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun startLiveViewLoopIfNeeded() {
        liveViewJob?.cancel()
        val state = _uiState.value
        if (!state.connected || !state.liveViewAutoRefresh) return

        val delayMillis = if (repository.isRealCamera()) 150L else LIVE_VIEW_REFRESH_MILLIS

        liveViewJob = viewModelScope.launch {
            while (isActive) {
                delay(delayMillis)
                val latest = _uiState.value
                if (!latest.connected || !latest.liveViewAutoRefresh) break
                _uiState.update {
                    if (it.connected && it.liveViewAutoRefresh) {
                        it.copy(liveViewFrameUrl = repository.nextLiveViewFrameUrl())
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun stopLiveViewLoop() {
        liveViewJob?.cancel()
        liveViewJob = null
    }

    override fun onCleared() {
        stopLiveViewLoop()
        super.onCleared()
    }

    private fun CameraUiState.withClearedSession(
        baseUrl: String,
        error: String?,
    ): CameraUiState = copy(
        baseUrl = baseUrl,
        info = null,
        status = null,
        capabilities = null,
        liveViewFrameUrl = null,
        focusPoint = null,
        error = error,
    )

    private companion object {
        const val LIVE_VIEW_REFRESH_MILLIS = 1_000L
    }
}
