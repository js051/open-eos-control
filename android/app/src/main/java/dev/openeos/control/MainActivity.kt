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
import dev.openeos.control.ui.OpenEosControlApp
import dev.openeos.control.ui.nearestEquivalentCameraRotation
import dev.openeos.control.ui.resolveCameraControlRotation

class MainActivity : AppCompatActivity() {
    private val controlRotationDegrees = mutableFloatStateOf(0f)
    private lateinit var orientationListener: OrientationEventListener
    private lateinit var autoRotationObserver: ContentObserver
    private var latestSensorDegrees = OrientationEventListener.ORIENTATION_UNKNOWN
    private var systemAutoRotationEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                latestSensorDegrees = orientation
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
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            false,
            autoRotationObserver,
        )
        refreshSystemAutoRotationSetting()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onStop() {
        orientationListener.disable()
        contentResolver.unregisterContentObserver(autoRotationObserver)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateControlRotation()
    }

    private fun refreshSystemAutoRotationSetting() {
        systemAutoRotationEnabled = Settings.System.getInt(
            contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0,
        ) == 1
        updateControlRotation()
    }

    private fun updateControlRotation() {
        val target = resolveCameraControlRotation(
            autoRotationEnabled = systemAutoRotationEnabled,
            sensorDegrees = latestSensorDegrees,
            displayRotationDegrees = currentDisplayRotationDegrees(),
        )
        controlRotationDegrees.floatValue = nearestEquivalentCameraRotation(
            currentDegrees = controlRotationDegrees.floatValue,
            targetDegrees = target,
        )
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotationDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
