package dev.openeos.control

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.ui.cameraRotationQuadrant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityOrientationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun cameraControlsFollowTheRealSystemAutoRotationSetting() {
        val original = shell("settings get system accelerometer_rotation")
        try {
            setSystemAutoRotation(true)
            compose.runOnIdle {
                compose.activity.refreshSystemAutoRotationSetting()
                compose.activity.handleDeviceOrientationChanged(90)
                assertEquals(3, cameraRotationQuadrant(compose.activity.currentControlRotationDegrees()))
            }

            setSystemAutoRotation(false)
            compose.runOnIdle {
                assertEquals(false, compose.activity.isOrientationListenerRunning())
                assertEquals(0, cameraRotationQuadrant(compose.activity.currentControlRotationDegrees()))
                compose.activity.handleDeviceOrientationChanged(270)
                assertEquals(0, cameraRotationQuadrant(compose.activity.currentControlRotationDegrees()))
            }

            setSystemAutoRotation(true)
            compose.runOnIdle {
                assertEquals(0, cameraRotationQuadrant(compose.activity.currentControlRotationDegrees()))
                compose.activity.handleDeviceOrientationChanged(270)
                assertEquals(1, cameraRotationQuadrant(compose.activity.currentControlRotationDegrees()))
            }
        } finally {
            if (original == "0" || original == "1") {
                shell("settings put system accelerometer_rotation $original")
            } else {
                shell("settings delete system accelerometer_rotation")
            }
            compose.runOnIdle { compose.activity.refreshSystemAutoRotationSetting() }
        }
    }

    @Test
    fun legacyCameraControlPreferenceCannotOverrideTheSystemSetting() {
        val originalSetting = shell("settings get system accelerometer_rotation")
        try {
            compose.activity.getSharedPreferences("camera_control_orientation", 0)
                .edit()
                .putString("mode", "ALWAYS_ROTATE")
                .commit()
            setSystemAutoRotation(false)
            compose.runOnIdle {
                compose.activity.refreshSystemAutoRotationSetting()
                compose.activity.handleDeviceOrientationChanged(90)
                assertEquals(0, cameraRotationQuadrant(compose.activity.currentControlRotationDegrees()))
                assertEquals(false, compose.activity.isSystemAutoRotationCurrentlyEnabled())
                assertEquals(false, compose.activity.isOrientationListenerRunning())
            }
        } finally {
            if (originalSetting == "0" || originalSetting == "1") {
                shell("settings put system accelerometer_rotation $originalSetting")
            } else {
                shell("settings delete system accelerometer_rotation")
            }
            compose.activity.getSharedPreferences("camera_control_orientation", 0)
                .edit()
                .clear()
                .commit()
            compose.runOnIdle {
                compose.activity.refreshSystemAutoRotationSetting()
            }
        }
    }

    private fun setSystemAutoRotation(enabled: Boolean) {
        val expected = if (enabled) "1" else "0"
        shell("settings put system accelerometer_rotation $expected")
        val deadline = SystemClock.uptimeMillis() + SYSTEM_SETTING_TIMEOUT_MILLIS
        var actual: String
        do {
            actual = shell("settings get system accelerometer_rotation")
            if (actual == expected) return
            Thread.sleep(SYSTEM_SETTING_POLL_MILLIS)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("System auto-rotation setting did not settle.", expected, actual)
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText().trim() }
    }

    private companion object {
        const val SYSTEM_SETTING_TIMEOUT_MILLIS = 5_000L
        const val SYSTEM_SETTING_POLL_MILLIS = 50L
    }
}
