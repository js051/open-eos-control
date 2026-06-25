package dev.openeos.control.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openeos.control.data.CameraRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CameraViewModel(
    private val repository: CameraRepository = CameraRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    fun setBaseUrl(value: String) {
        _uiState.update { it.copy(baseUrl = value) }
    }

    fun useDirectCameraPreset() {
        _uiState.update { it.copy(baseUrl = CameraRepository.DEFAULT_CAMERA_BASE_URL) }
    }

    fun useDevSimulatorPreset() {
        _uiState.update { it.copy(baseUrl = CameraRepository.DEV_EMULATOR_SIMULATOR_URL) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun connect() = runCamera {
        val session = repository.connect(_uiState.value.baseUrl)
        _uiState.update {
            it.copy(
                info = session.info,
                status = session.status,
                capabilities = session.capabilities,
            )
        }
    }

    fun refresh() = runCamera {
        _uiState.update { it.copy(status = repository.refreshStatus()) }
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
        _uiState.update { it.copy(focusPoint = FocusPoint(result.x, result.y)) }
    }

    private fun updateStatus(block: suspend () -> dev.openeos.control.data.CameraStatus) = runCamera {
        _uiState.update { it.copy(status = block()) }
    }

    private fun runCamera(block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(error = exception.message ?: "Camera request failed")
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }
}
