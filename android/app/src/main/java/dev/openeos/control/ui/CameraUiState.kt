package dev.openeos.control.ui

import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraStatus

data class CameraUiState(
    val baseUrl: String = CameraRepository.DEFAULT_BASE_URL,
    val info: CameraInfo? = null,
    val status: CameraStatus? = null,
    val capabilities: CameraCapabilities? = null,
    val focusPoint: FocusPoint? = null,
    val error: String? = null,
    val busy: Boolean = false,
) {
    val connected: Boolean
        get() = info != null && status?.connected == true
}

data class FocusPoint(
    val x: Double,
    val y: Double,
)
