package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.openeos.control.data.CameraFocusFrame
import dev.openeos.control.data.CameraFocusFrameKind
import dev.openeos.control.data.CameraFocusInfo
import dev.openeos.control.data.CameraFocusStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CameraFocusOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cameraFramesStayInsideImageAndUseSparseCornersAcrossViewports() {
        val dimensions = mutableStateOf(DpSize(360.dp, 800.dp))
        val sample = mutableStateOf(System.currentTimeMillis())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(dimensions.value)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CameraReportedFocusOverlay(info(CameraFocusStatus.FOCUSED), sample.value, 4f / 3f)
                }
            }
        }
        for ((name, viewport) in listOf("portrait" to DpSize(360.dp, 800.dp), "landscape" to DpSize(800.dp, 360.dp), "tablet" to DpSize(800.dp, 1280.dp))) {
            compose.runOnIdle { dimensions.value = viewport; sample.value = System.currentTimeMillis() }
            compose.mainClock.advanceTimeByFrame()
            val frame = compose.onNodeWithTag("camera-focus-frames").captureToImage().asAndroidBitmap()
            val content = fittedLiveViewRect(frame.width.toFloat(), frame.height.toFloat(), 4f / 3f)
            var greenPixels = 0
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                val pixel = frame.getPixel(x, y)
                if (android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel) + 50 &&
                    android.graphics.Color.green(pixel) > android.graphics.Color.blue(pixel) + 50) {
                    greenPixels++
                    assertTrue(x >= content.left && x < content.left + content.width)
                    assertTrue(y >= content.top && y < content.top + content.height)
                }
            }
            assertTrue("Visible but sparse camera frame", greenPixels in 20..(frame.width * frame.height / 50))
            val centerX = (content.left + content.width * 0.5f).toInt()
            val centerY = (content.top + content.height * 0.5f).toInt()
            assertEquals("Subject center stays unobstructed", android.graphics.Color.BLACK, frame.getPixel(centerX, centerY))
            java.io.File(compose.activity.cacheDir, "camera-af-$name.png").outputStream().use {
                assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    @Test fun statusDescriptionsFollowCameraAndOldFramesExpire() {
        val state = mutableStateOf(CameraFocusStatus.STANDBY)
        val sample = mutableStateOf(System.currentTimeMillis())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                CameraReportedFocusOverlay(info(state.value), sample.value, 4f / 3f)
            }
        }
        for (status in CameraFocusStatus.entries) {
            compose.runOnIdle { state.value = status; sample.value = System.currentTimeMillis() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithContentDescription(compose.activity.getString(cameraFocusStatusResource(status))).assertIsDisplayed()
        }
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("camera-focus-frames").assertDoesNotExist()
    }

    @Test fun staleSamplesAndMismatchedImageGeometryAreNeverDisplayed() {
        val ratio = mutableStateOf(16f / 9f)
        val sample = mutableStateOf(System.currentTimeMillis())
        compose.setContent {
            Box(Modifier.fillMaxSize()) { CameraReportedFocusOverlay(info(CameraFocusStatus.FOCUSED), sample.value, ratio.value) }
        }
        compose.onNodeWithTag("camera-focus-frames").assertDoesNotExist()
        compose.runOnIdle { sample.value = System.currentTimeMillis() - 5_000; ratio.value = 4f / 3f }
        compose.onNodeWithTag("camera-focus-frames").assertDoesNotExist()
    }

    private fun info(status: CameraFocusStatus) = CameraFocusInfo(
        listOf(CameraFocusFrame(0.25f, 0.25f, 0.75f, 0.75f, status, CameraFocusFrameKind.FACE)), 1, 4f / 3f,
    )
}
