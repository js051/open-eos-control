package dev.openeos.control

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.ui.cameraRotationQuadrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityRotationLaunchTest {
    @Test
    fun launchingTheFixedCameraLayoutPreservesAnExistingSystemRotationLock() {
        val originalUserRotation = shell("cmd window user-rotation")
        val originalAutoRotation = shell("settings get system accelerometer_rotation")
        try {
            shell("cmd window user-rotation lock 0")
            shell("settings put system accelerometer_rotation 0")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("0", shell("settings get system accelerometer_rotation"))
                    assertFalse(activity.isSystemAutoRotationCurrentlyEnabled())
                    assertFalse(activity.isOrientationListenerRunning())
                    activity.handleDeviceOrientationChanged(90)
                    assertEquals(0, cameraRotationQuadrant(activity.currentControlRotationDegrees()))
                }
            }
        } finally {
            when {
                originalUserRotation == "free" -> shell("cmd window user-rotation free")
                originalUserRotation.startsWith("lock ") -> shell("cmd window user-rotation $originalUserRotation")
            }
            if (originalAutoRotation == "null") {
                shell("settings delete system accelerometer_rotation")
            } else {
                shell("settings put system accelerometer_rotation $originalAutoRotation")
            }
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText().trim() }
    }
}
