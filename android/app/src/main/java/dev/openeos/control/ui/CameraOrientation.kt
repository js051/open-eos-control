package dev.openeos.control.ui

import androidx.compose.runtime.staticCompositionLocalOf

private const val CAMERA_ORIENTATION_HYSTERESIS_DEGREES = 5
private const val ORIENTATION_UNKNOWN = -1
internal const val SYSTEM_AUTO_ROTATION_ENABLE_STABILITY_MILLIS = 250L

internal fun isSystemAutoRotationSettingEnabled(value: Int): Boolean = value == 1

internal class StableSystemAutoRotationGate(
    private val enableStabilityMillis: Long = SYSTEM_AUTO_ROTATION_ENABLE_STABILITY_MILLIS,
) {
    var enabled: Boolean = false
        private set
    private var enableCandidateSinceMillis: Long? = null

    fun update(rawEnabled: Boolean, elapsedRealtimeMillis: Long): Boolean {
        if (!rawEnabled) {
            enabled = false
            enableCandidateSinceMillis = null
            return false
        }
        if (enabled) return true

        val candidateSince = enableCandidateSinceMillis
        if (candidateSince == null) {
            enableCandidateSinceMillis = elapsedRealtimeMillis
        } else if (elapsedRealtimeMillis - candidateSince >= enableStabilityMillis) {
            enabled = true
            enableCandidateSinceMillis = null
        }
        return enabled
    }

    fun remainingEnableDelayMillis(elapsedRealtimeMillis: Long): Long? =
        enableCandidateSinceMillis?.let { candidateSince ->
            (enableStabilityMillis - (elapsedRealtimeMillis - candidateSince)).coerceAtLeast(0L)
        }

    fun reset() {
        enabled = false
        enableCandidateSinceMillis = null
    }
}

internal class CameraOrientationPolicy {
    private var systemAutoRotationEnabled = false
    private var latestSensorDegrees = ORIENTATION_UNKNOWN
    private var snappedSensorDegrees = 0

    fun setSystemAutoRotation(enabled: Boolean) {
        if (enabled != systemAutoRotationEnabled) {
            // Never reuse a posture captured before the system rotation policy changed.
            latestSensorDegrees = ORIENTATION_UNKNOWN
            snappedSensorDegrees = 0
        }
        systemAutoRotationEnabled = enabled
    }

    fun onSensorOrientation(sensorDegrees: Int) {
        if (sensorDegrees < 0) return
        latestSensorDegrees = sensorDegrees
        snappedSensorDegrees = snapCameraDeviceRotation(snappedSensorDegrees, sensorDegrees)
    }

    fun onSensorOrientation(sensorDegrees: Int, systemAutoRotationEnabled: Boolean) {
        setSystemAutoRotation(systemAutoRotationEnabled)
        if (!systemAutoRotationEnabled) return
        onSensorOrientation(sensorDegrees)
    }

    fun shouldListen(activityStarted: Boolean, canDetectOrientation: Boolean): Boolean =
        activityStarted && canDetectOrientation

    fun resolveControlRotation(displayRotationDegrees: Int): Float = resolveCameraControlRotation(
        autoRotationEnabled = systemAutoRotationEnabled,
        sensorDegrees = if (latestSensorDegrees == ORIENTATION_UNKNOWN) {
            latestSensorDegrees
        } else {
            snappedSensorDegrees
        },
        displayRotationDegrees = displayRotationDegrees,
    )
}

fun snapCameraDeviceRotation(previousDegrees: Int, sensorDegrees: Int): Int {
    if (sensorDegrees < 0) return previousDegrees.floorMod(360)
    val previous = previousDegrees.floorMod(360)
    val sensor = sensorDegrees.floorMod(360)
    val directDistance = kotlin.math.abs(previous - sensor)
    val circularDistance = minOf(directDistance, 360 - directDistance)
    if (circularDistance < 45 + CAMERA_ORIENTATION_HYSTERESIS_DEGREES) return previous
    return when (sensor) {
        in 45..134 -> 90
        in 135..224 -> 180
        in 225..314 -> 270
        else -> 0
    }
}

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
val LocalCameraControlTargetRotation = staticCompositionLocalOf { 0f }
val LocalCameraContentRotationHandled = staticCompositionLocalOf { false }
