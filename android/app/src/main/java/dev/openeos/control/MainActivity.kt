package dev.openeos.control

import android.content.pm.ActivityInfo
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
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import dev.openeos.control.ui.CameraOrientationPolicy
import dev.openeos.control.ui.CameraControlOrientationMode
import dev.openeos.control.ui.CameraControlOrientationPreferences
import dev.openeos.control.ui.OpenEosControlApp
import dev.openeos.control.ui.isSystemAutoRotationSettingEnabled
import dev.openeos.control.ui.nearestEquivalentCameraRotation
import dev.openeos.control.ui.shouldRotateCameraControls

class MainActivity : AppCompatActivity() {
    private val controlRotationDegrees = mutableFloatStateOf(0f)
    private val animateControlRotation = mutableStateOf(true)
    private val controlOrientationMode = mutableStateOf(CameraControlOrientationMode.FOLLOW_SYSTEM)
    private val systemAutoRotationEnabled = mutableStateOf(false)
    private val orientationPolicy = CameraOrientationPolicy()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var orientationListener: OrientationEventListener
    private lateinit var autoRotationObserver: ContentObserver
    private var activityStarted = false
    private var orientationListenerEnabled = false
    private val rotationPolicySyncRefresh = Runnable {
        if (activityStarted) refreshSystemAutoRotationSetting()
    }
    private val rotationSettingPoller = object : Runnable {
        override fun run() {
            if (!activityStarted) return
            refreshSystemAutoRotationSetting()
            mainHandler.postDelayed(this, ROTATION_SETTING_POLL_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = naturalCameraLayoutOrientation(
            configurationOrientation = resources.configuration.orientation,
            displayRotationDegrees = currentDisplayRotationDegrees(),
        )
        super.onCreate(savedInstanceState)
        controlOrientationMode.value = CameraControlOrientationPreferences.read(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                handleDeviceOrientationChanged(orientation)
            }
        }
        autoRotationObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onSystemRotationPolicyChanged()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onSystemRotationPolicyChanged()
            }
        }
        setContent {
            OpenEosControlApp(
                controlRotationDegrees = controlRotationDegrees.floatValue,
                animateControlRotation = animateControlRotation.value,
                controlOrientationMode = controlOrientationMode.value,
                systemAutoRotationEnabled = systemAutoRotationEnabled.value,
                onControlOrientationModeChanged = ::setControlOrientationMode,
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
        // Some vendor quick-settings implementations only notify the parent settings URI.
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            autoRotationObserver,
        )
        // Android 13+ can keep a separate lock preference per fold/device posture.
        // WindowManager mirrors the active posture back to ACCELEROMETER_ROTATION,
        // so this observer only triggers an immediate and a delayed reconciliation.
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(DEVICE_STATE_ROTATION_LOCK_SETTING),
            false,
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
        mainHandler.removeCallbacks(rotationPolicySyncRefresh)
        setOrientationListenerEnabled(false)
        contentResolver.unregisterContentObserver(autoRotationObserver)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshSystemAutoRotationSetting()
    }

    internal fun refreshSystemAutoRotationSetting() {
        val systemEnabled = isSystemAutoRotationEnabled()
        systemAutoRotationEnabled.value = systemEnabled
        val enabled = shouldRotateCameraControls(controlOrientationMode.value, systemEnabled)
        animateControlRotation.value = enabled
        orientationPolicy.setSystemAutoRotation(enabled)
        setOrientationListenerEnabled(
            orientationPolicy.shouldListen(activityStarted, orientationListener.canDetectOrientation()),
        )
        updateControlRotation()
    }

    internal fun handleDeviceOrientationChanged(orientation: Int) {
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
        // Reconcile the public system setting for every sample. Some quick-settings
        // implementations do not reliably notify observers while an immersive app is focused.
        val systemEnabled = isSystemAutoRotationEnabled()
        systemAutoRotationEnabled.value = systemEnabled
        val autoRotationEnabled = shouldRotateCameraControls(controlOrientationMode.value, systemEnabled)
        animateControlRotation.value = autoRotationEnabled
        orientationPolicy.onSensorOrientation(orientation, autoRotationEnabled)
        setOrientationListenerEnabled(
            orientationPolicy.shouldListen(activityStarted, orientationListener.canDetectOrientation()),
        )
        updateControlRotation()
    }

    internal fun isOrientationListenerRunning(): Boolean = orientationListenerEnabled

    internal fun currentControlRotationDegrees(): Float = controlRotationDegrees.floatValue

    internal fun currentControlOrientationMode(): CameraControlOrientationMode = controlOrientationMode.value

    internal fun isSystemAutoRotationCurrentlyEnabled(): Boolean = systemAutoRotationEnabled.value

    internal fun setControlOrientationMode(mode: CameraControlOrientationMode) {
        if (controlOrientationMode.value == mode) return
        controlOrientationMode.value = mode
        CameraControlOrientationPreferences.write(this, mode)
        refreshSystemAutoRotationSetting()
    }

    private fun setOrientationListenerEnabled(enabled: Boolean) {
        if (orientationListenerEnabled == enabled) return
        orientationListenerEnabled = enabled
        if (enabled) orientationListener.enable() else orientationListener.disable()
    }

    private fun onSystemRotationPolicyChanged() {
        refreshSystemAutoRotationSetting()
        // Device-state rotation lock is synchronized asynchronously by WindowManager.
        mainHandler.removeCallbacks(rotationPolicySyncRefresh)
        mainHandler.postDelayed(rotationPolicySyncRefresh, ROTATION_POLICY_SYNC_DELAY_MILLIS)
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
        private const val DEVICE_STATE_ROTATION_LOCK_SETTING = "device_state_rotation_lock"
        private const val ROTATION_POLICY_SYNC_DELAY_MILLIS = 250L
        private const val ROTATION_SETTING_POLL_INTERVAL_MILLIS = 750L
    }
}

internal fun naturalCameraLayoutOrientation(
    configurationOrientation: Int,
    displayRotationDegrees: Int,
): Int {
    val displayIsQuarterTurn = displayRotationDegrees == 90 || displayRotationDegrees == 270
    val naturalOrientationIsPortrait = if (displayIsQuarterTurn) {
        configurationOrientation == Configuration.ORIENTATION_LANDSCAPE
    } else {
        configurationOrientation == Configuration.ORIENTATION_PORTRAIT
    }
    return if (naturalOrientationIsPortrait) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}
