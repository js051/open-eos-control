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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val CameraSettingsMaxWidth = 680.dp
private const val CameraSettingsHeightFraction = 0.68f

@Composable
fun CameraSettingsSurface(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DarkSheetSystemBarsEffect()
        BackHandler(onBack = onDismissRequest)
        BoxWithConstraints(Modifier.fillMaxSize().testTag("camera-settings-root")) {
            StableSettingsPanel(
                availableWidth = maxWidth,
                availableHeight = maxHeight,
                edge = cameraSettingsPanelEdge(LocalCameraControlTargetRotation.current),
                onDismissRequest = onDismissRequest,
                content = content,
            )
        }
    }
}

@Composable
private fun StableSettingsPanel(
    availableWidth: Dp,
    availableHeight: Dp,
    edge: CameraSettingsPanelEdge,
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
                .then(
                    when (edge) {
                        CameraSettingsPanelEdge.BOTTOM,
                        CameraSettingsPanelEdge.TOP ->
                            Modifier.fillMaxWidth().height(availableHeight * CameraSettingsHeightFraction)

                        CameraSettingsPanelEdge.START,
                        CameraSettingsPanelEdge.END ->
                            Modifier.fillMaxHeight().width(availableWidth * CameraSettingsHeightFraction)
                    },
                )
                .align(edge.alignment)
                .clip(edge.shape)
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

internal enum class CameraSettingsPanelEdge(
    val alignment: Alignment,
    val shape: RoundedCornerShape,
) {
    BOTTOM(
        Alignment.BottomCenter,
        RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
    ),
    START(
        Alignment.CenterStart,
        RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
    ),
    TOP(
        Alignment.TopCenter,
        RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
    ),
    END(
        Alignment.CenterEnd,
        RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
    ),
}

internal fun cameraSettingsPanelEdge(rotationDegrees: Float): CameraSettingsPanelEdge =
    when (cameraRotationQuadrant(rotationDegrees)) {
        1 -> CameraSettingsPanelEdge.START
        2 -> CameraSettingsPanelEdge.TOP
        3 -> CameraSettingsPanelEdge.END
        else -> CameraSettingsPanelEdge.BOTTOM
    }
