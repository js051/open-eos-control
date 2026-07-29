package dev.openeos.control

import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
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
import dev.openeos.control.ui.isSystemAutoRotationSettingEnabled
import dev.openeos.control.ui.nearestEquivalentCameraRotation

class MainActivity : AppCompatActivity() {
    private val controlRotationDegrees = mutableFloatStateOf(0f)
    private val orientationPolicy = CameraOrientationPolicy()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var orientationListener: OrientationEventListener
    private lateinit var autoRotationObserver: ContentObserver
    private var activityStarted = false
    private var orientationListenerEnabled = false
    private val rotationSettingPoller = object : Runnable {
        override fun run() {
            if (!activityStarted) return
            refreshSystemAutoRotationSetting()
            mainHandler.postDelayed(this, ROTATION_SETTING_POLL_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val autoRotationEnabled = isSystemAutoRotationEnabled()
                orientationPolicy.onSensorOrientation(orientation, autoRotationEnabled)
                updateControlRotation()
            }
        }
        autoRotationObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshSystemAutoRotationSetting()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
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
        // Some vendor quick-settings implementations only notify the parent settings URI.
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            autoRotationObserver,
        )
        refreshSystemAutoRotationSetting()
        mainHandler.removeCallbacks(rotationSettingPoller)
        mainHandler.postDelayed(rotationSettingPoller, ROTATION_SETTING_POLL_INTERVAL_MILLIS)
    }

    override fun onResume() {
        super.onResume()
        refreshSystemAutoRotationSetting()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::orientationListener.isInitialized) {
            // The quick-settings shade can change rotation lock without pausing this Activity.
            refreshSystemAutoRotationSetting()
        }
    }

    override fun onStop() {
        activityStarted = false
        mainHandler.removeCallbacks(rotationSettingPoller)
        setOrientationListenerEnabled(false)
        contentResolver.unregisterContentObserver(autoRotationObserver)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshSystemAutoRotationSetting()
    }

    private fun refreshSystemAutoRotationSetting() {
        val enabled = isSystemAutoRotationEnabled()
        orientationPolicy.setSystemAutoRotation(enabled)
        setOrientationListenerEnabled(
            orientationPolicy.shouldListen(activityStarted, orientationListener.canDetectOrientation()),
        )
        updateControlRotation()
    }

    private fun setOrientationListenerEnabled(enabled: Boolean) {
        if (orientationListenerEnabled == enabled) return
        orientationListenerEnabled = enabled
        if (enabled) orientationListener.enable() else orientationListener.disable()
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
    ).let(::isSystemAutoRotationSettingEnabled)

    @Suppress("DEPRECATION")
    private fun currentDisplayRotationDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    companion object {
        private const val ROTATION_SETTING_POLL_INTERVAL_MILLIS = 750L
    }
}
