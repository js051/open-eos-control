package dev.openeos.control

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val target = resolveCameraControlRotation(
                    sensorDegrees = orientation,
                    displayRotationDegrees = currentDisplayRotationDegrees(),
                )
                controlRotationDegrees.floatValue = nearestEquivalentCameraRotation(
                    currentDegrees = controlRotationDegrees.floatValue,
                    targetDegrees = target,
                )
            }
        }
        setContent {
            OpenEosControlApp(controlRotationDegrees = controlRotationDegrees.floatValue)
        }
    }

    override fun onStart() {
        super.onStart()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onStop() {
        orientationListener.disable()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotationDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
