package dev.openeos.control.ui

import android.content.Context

enum class CameraControlOrientationMode {
    FOLLOW_SYSTEM,
    ALWAYS_ROTATE,
    KEEP_FIXED,
}

internal fun shouldRotateCameraControls(
    mode: CameraControlOrientationMode,
    systemAutoRotationEnabled: Boolean,
): Boolean = when (mode) {
    CameraControlOrientationMode.FOLLOW_SYSTEM -> systemAutoRotationEnabled
    CameraControlOrientationMode.ALWAYS_ROTATE -> true
    CameraControlOrientationMode.KEEP_FIXED -> false
}

internal object CameraControlOrientationPreferences {
    private const val PREFERENCES = "camera_control_orientation"
    private const val MODE = "mode"

    fun read(context: Context): CameraControlOrientationMode {
        val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(MODE, null)
        return CameraControlOrientationMode.entries.firstOrNull { it.name == value }
            ?: CameraControlOrientationMode.FOLLOW_SYSTEM
    }

    fun write(context: Context, mode: CameraControlOrientationMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(MODE, mode.name)
            .apply()
    }
}
