package dev.openeos.control

import android.os.ParcelFileDescriptor
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
            Thread.sleep(1_000L)
            compose.runOnIdle {
                assertEquals(true, compose.activity.isOrientationListenerRunning())
                assertEquals(0, cameraRotationQuadrant(compose.activity.currentControlRotationDegrees()))
                compose.activity.handleDeviceOrientationChanged(270)
                assertEquals(0, cameraRotationQuadrant(compose.activity.currentControlRotationDegrees()))
            }

            setSystemAutoRotation(true)
            Thread.sleep(1_000L)
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

    private fun setSystemAutoRotation(enabled: Boolean) {
        shell("settings put system accelerometer_rotation ${if (enabled) 1 else 0}")
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
