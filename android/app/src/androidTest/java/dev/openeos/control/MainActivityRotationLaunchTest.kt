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
        val original = shell("cmd window user-rotation")
        try {
            shell("cmd window user-rotation lock 0")
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
                original == "free" -> shell("cmd window user-rotation free")
                original.startsWith("lock ") -> shell("cmd window user-rotation $original")
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
