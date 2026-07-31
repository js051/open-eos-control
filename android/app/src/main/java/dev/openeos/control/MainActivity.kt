package dev.openeos.control

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
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import dev.openeos.control.ui.CameraOrientationPolicy
import dev.openeos.control.ui.OpenEosControlApp
import dev.openeos.control.ui.isSystemAutoRotationSettingEnabled
import dev.openeos.control.ui.nearestEquivalentCameraRotation

class MainActivity : AppCompatActivity() {
    private val controlRotationDegrees = mutableFloatStateOf(0f)
    private val animateControlRotation = mutableStateOf(true)
    private val systemAutoRotationEnabled = mutableStateOf(false)
    private val orientationPolicy = CameraOrientationPolicy()
    private lateinit var orientationListener: OrientationEventListener
    private lateinit var autoRotationObserver: ContentObserver
    private var activityStarted = false
    private var orientationListenerEnabled = false
    private var firstStartCompleted = false
    private var windowHasFocus = false
    private var rotationSettingAtFocusLoss: Boolean? = null
    private var rotationSettingAtStop: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemAutoRotationSetting(isSystemAutoRotationEnabled(), updateListener = false)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                handleDeviceOrientationChanged(orientation)
            }
        }
        autoRotationObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onSystemRotationSettingObserved()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onSystemRotationSettingObserved()
            }
        }
        setContent {
            OpenEosControlApp(
                controlRotationDegrees = controlRotationDegrees.floatValue,
                animateControlRotation = animateControlRotation.value,
                systemAutoRotationEnabled = systemAutoRotationEnabled.value,
            )
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
        if (firstStartCompleted) {
            val before = rotationSettingAtStop
            val current = isSystemAutoRotationEnabled()
            if (before != null && before != current) {
                applySystemAutoRotationSetting(current)
            } else {
                applySystemAutoRotationSetting(systemAutoRotationEnabled.value)
            }
        } else {
            firstStartCompleted = true
            applySystemAutoRotationSetting(systemAutoRotationEnabled.value)
        }
        rotationSettingAtStop = null
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!::orientationListener.isInitialized) return
        windowHasFocus = hasFocus
        if (!hasFocus) {
            rotationSettingAtFocusLoss = isSystemAutoRotationEnabled()
            return
        }
        val before = rotationSettingAtFocusLoss
        rotationSettingAtFocusLoss = null
        val current = isSystemAutoRotationEnabled()
        if (before != null && before != current) {
            applySystemAutoRotationSetting(current)
        }
    }

    override fun onStop() {
        rotationSettingAtStop = isSystemAutoRotationEnabled()
        activityStarted = false
        setOrientationListenerEnabled(false)
        contentResolver.unregisterContentObserver(autoRotationObserver)
        super.onStop()
    }

    internal fun refreshSystemAutoRotationSetting() {
        applySystemAutoRotationSetting(isSystemAutoRotationEnabled())
    }

    private fun applySystemAutoRotationSetting(
        systemEnabled: Boolean,
        updateListener: Boolean = true,
    ) {
        systemAutoRotationEnabled.value = systemEnabled
        animateControlRotation.value = systemEnabled
        orientationPolicy.setSystemAutoRotation(systemEnabled)
        if (updateListener && ::orientationListener.isInitialized) {
            setOrientationListenerEnabled(
                orientationPolicy.shouldListen(activityStarted, orientationListener.canDetectOrientation()),
            )
        }
        updateControlRotation()
    }

    internal fun handleDeviceOrientationChanged(orientation: Int) {
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
        val systemEnabled = systemAutoRotationEnabled.value
        orientationPolicy.onSensorOrientation(orientation, systemEnabled)
        setOrientationListenerEnabled(
            orientationPolicy.shouldListen(activityStarted, orientationListener.canDetectOrientation()),
        )
        updateControlRotation()
    }

    internal fun isOrientationListenerRunning(): Boolean = orientationListenerEnabled

    internal fun currentControlRotationDegrees(): Float = controlRotationDegrees.floatValue

    internal fun isSystemAutoRotationCurrentlyEnabled(): Boolean = systemAutoRotationEnabled.value

    private fun setOrientationListenerEnabled(enabled: Boolean) {
        if (orientationListenerEnabled == enabled) return
        orientationListenerEnabled = enabled
        if (enabled) orientationListener.enable() else orientationListener.disable()
    }

    private fun onSystemRotationSettingObserved() {
        if (!windowHasFocus) return
        applySystemAutoRotationSetting(isSystemAutoRotationEnabled())
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

}
