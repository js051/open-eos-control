package dev.openeos.control

import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.core.view.WindowCompat
import dev.openeos.control.ui.CameraOrientationPolicy
import dev.openeos.control.ui.OpenEosControlApp
import dev.openeos.control.ui.nearestEquivalentCameraRotation

class MainActivity : AppCompatActivity() {
    private val controlRotationDegrees = mutableFloatStateOf(0f)
    private val orientationPolicy = CameraOrientationPolicy()
    private lateinit var orientationListener: OrientationEventListener
    private lateinit var autoRotationObserver: ContentObserver
    private var activityStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                orientationPolicy.onSensorOrientation(orientation)
                updateControlRotation()
            }
        }
        autoRotationObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshSystemAutoRotationSetting()
            }
        }
        setContent {
            OpenEosControlApp(controlRotationDegrees = controlRotationDegrees.floatValue)
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            false,
            autoRotationObserver,
        )
        refreshSystemAutoRotationSetting()
    }

    override fun onStop() {
        activityStarted = false
        orientationListener.disable()
        contentResolver.unregisterContentObserver(autoRotationObserver)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateControlRotation()
    }

    private fun refreshSystemAutoRotationSetting() {
        val enabled = isSystemAutoRotationEnabled()
        orientationPolicy.setSystemAutoRotation(enabled)
        if (orientationPolicy.shouldListen(activityStarted, orientationListener.canDetectOrientation())) {
            orientationListener.enable()
        } else {
            orientationListener.disable()
        }
        updateControlRotation()
    }

    private fun updateControlRotation() {
        val target = orientationPolicy.resolveControlRotation(currentDisplayRotationDegrees())
        controlRotationDegrees.floatValue = nearestEquivalentCameraRotation(
            currentDegrees = controlRotationDegrees.floatValue,
            targetDegrees = target,
        )
    }

    private fun isSystemAutoRotationEnabled(): Boolean = Settings.System.getInt(
        contentResolver,
        Settings.System.ACCELEROMETER_ROTATION,
        0,
    ) == 1

    @Suppress("DEPRECATION")
    private fun currentDisplayRotationDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
