package dev.openeos.control.ui

import androidx.compose.runtime.staticCompositionLocalOf

fun resolveCameraControlRotation(
    autoRotationEnabled: Boolean,
    sensorDegrees: Int,
    displayRotationDegrees: Int,
): Float {
    if (!autoRotationEnabled || sensorDegrees < 0) return 0f
    val deviceRotation = when (sensorDegrees) {
        in 45..134 -> 90
        in 135..224 -> 180
        in 225..314 -> 270
        else -> 0
    }
    val displayDeviceRotation = (-displayRotationDegrees).floorMod(360)
    val delta = (displayDeviceRotation - deviceRotation).floorMod(360)
    return when (delta) {
        270 -> -90f
        180 -> 180f
        else -> delta.toFloat()
    }
}

fun nearestEquivalentCameraRotation(currentDegrees: Float, targetDegrees: Float): Float {
    val rawDelta = targetDegrees - currentDegrees + 180f
    val delta = ((rawDelta % 360f) + 360f) % 360f - 180f
    return currentDegrees + if (delta == -180f) 180f else delta
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

val LocalCameraControlRotation = staticCompositionLocalOf { 0f }
