package dev.openeos.control.ui

import androidx.compose.runtime.staticCompositionLocalOf

enum class CameraDisplayOrientation {
    PORTRAIT,
    LANDSCAPE,
    REVERSE_LANDSCAPE,
}

data class CameraOrientationDecision(
    val displayOrientation: CameraDisplayOrientation,
    val controlRotationDegrees: Float,
)

fun resolveCameraOrientation(sensorDegrees: Int): CameraOrientationDecision = when (sensorDegrees) {
    in 45..134 -> CameraOrientationDecision(CameraDisplayOrientation.REVERSE_LANDSCAPE, 0f)
    in 135..224 -> CameraOrientationDecision(CameraDisplayOrientation.PORTRAIT, 180f)
    in 225..314 -> CameraOrientationDecision(CameraDisplayOrientation.LANDSCAPE, 0f)
    else -> CameraOrientationDecision(CameraDisplayOrientation.PORTRAIT, 0f)
}

val LocalCameraControlRotation = staticCompositionLocalOf { 0f }
