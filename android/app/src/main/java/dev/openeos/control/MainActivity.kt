package dev.openeos.control

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.OrientationEventListener
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.core.view.WindowCompat
import dev.openeos.control.ui.CameraDisplayOrientation
import dev.openeos.control.ui.OpenEosControlApp
import dev.openeos.control.ui.resolveCameraOrientation

class MainActivity : AppCompatActivity() {
    private val controlRotationDegrees = mutableFloatStateOf(0f)
    private lateinit var orientationListener: OrientationEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val decision = resolveCameraOrientation(orientation)
                controlRotationDegrees.floatValue = decision.controlRotationDegrees
                val requested = when (decision.displayOrientation) {
                    CameraDisplayOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    CameraDisplayOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    CameraDisplayOrientation.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                }
                if (requestedOrientation != requested) requestedOrientation = requested
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
}
