package dev.openeos.control.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val CameraSettingsMaxWidth = 680.dp
private const val CameraSettingsHeightFraction = 0.68f

@Composable
fun CameraSettingsSurface(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismissRequest)
    BoxWithConstraints(Modifier.fillMaxSize().testTag("camera-settings-root")) {
        StableSettingsPanel(
            availableHeight = maxHeight,
            onDismissRequest = onDismissRequest,
            content = content,
        )
    }
}

@Composable
private fun StableSettingsPanel(
    availableHeight: Dp,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scrimInteractionSource = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .clickable(
                    interactionSource = scrimInteractionSource,
                    indication = null,
                    onClick = onDismissRequest,
                ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(availableHeight * CameraSettingsHeightFraction)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(AppSurface)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .testTag("camera-settings-panel"),
        ) {
            CameraRotatingSlot(
                Modifier
                    .fillMaxSize()
                    .testTag("settings-content-rotation"),
                contentAlignment = Alignment.TopCenter,
                animateRotation = false,
            ) {
                SettingsContentViewport(content = content)
            }
        }
    }
}

@Composable
private fun SettingsContentViewport(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .widthIn(max = CameraSettingsMaxWidth)
                .fillMaxWidth()
                .fillMaxSize()
                .testTag("settings-content-viewport"),
        ) {
            content()
        }
    }
}

internal fun cameraRotationQuadrant(rotationDegrees: Float): Int {
    val normalized = ((rotationDegrees % 360f) + 360f) % 360f
    return ((normalized + 45f) / 90f).toInt() % 4
}
