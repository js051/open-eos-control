package dev.openeos.control.ui

import android.graphics.Bitmap
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraRepository
import dev.openeos.control.data.CameraStatus

data class CameraUiState(
    val baseUrl: String = CameraRepository.DEFAULT_CAMERA_BASE_URL,
    val info: CameraInfo? = null,
    val status: CameraStatus? = null,
    val capabilities: CameraCapabilities? = null,
    val liveViewFrameUrl: String? = null,
    val liveViewBitmap: Bitmap? = null,
    val liveViewAutoRefresh: Boolean = true,
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
