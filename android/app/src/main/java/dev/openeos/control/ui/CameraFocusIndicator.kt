package dev.openeos.control.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.openeos.control.R
import dev.openeos.control.data.CameraFocusInfo
import dev.openeos.control.data.CameraFocusStatus
import kotlinx.coroutines.delay

@Composable
internal fun FocusIndicator(point: FocusPoint?, feedback: FocusFeedback?, sourceAspectRatio: Float) {
    if (point == null || feedback == null) return
    val settle = remember { Animatable(1f) }
    LaunchedEffect(point) {
        settle.snapTo(1.12f)
        settle.animateTo(1f, tween(160))
    }
    val color = when (feedback) {
        FocusFeedback.SUCCESS -> AppSuccess
        FocusFeedback.FAILURE -> AppRecord
        FocusFeedback.FOCUSING, FocusFeedback.ACCEPTED -> AppAccent
    }
    val description = stringResource(
        when (feedback) {
            FocusFeedback.FOCUSING -> R.string.focus_command_pending
            FocusFeedback.ACCEPTED -> R.string.focus_command_accepted
            FocusFeedback.SUCCESS -> R.string.focus_action_completed
            FocusFeedback.FAILURE -> R.string.focus_command_failed
        },
    )
    Canvas(Modifier.fillMaxSize().testTag("focus-indicator").semantics { contentDescription = description }) {
        val bounds = focusIndicatorBounds(point, size.width, size.height, sourceAspectRatio, 48.dp.toPx() * settle.value)
        if (bounds.width <= 0f) return@Canvas
        drawFocusCorners(bounds, color)
    }
}

@Composable
internal fun CameraReportedFocusOverlay(info: CameraFocusInfo?, sampledAtMillis: Long?, sourceAspectRatio: Float) {
    if (info == null || sampledAtMillis == null || info.frames.isEmpty() || !focusInfoMatchesImage(info, sourceAspectRatio)) return
    var fresh by remember(sampledAtMillis) { mutableStateOf(System.currentTimeMillis() - sampledAtMillis in 0..999) }
    LaunchedEffect(sampledAtMillis) {
        delay((1_000 - (System.currentTimeMillis() - sampledAtMillis)).coerceIn(0, 1_000))
        fresh = false
    }
    if (!fresh) return
    val description = info.frames.map { stringResource(cameraFocusStatusResource(it.status)) }.distinct().joinToString(", ")
    Canvas(Modifier.fillMaxSize().testTag("camera-focus-frames").semantics { contentDescription = description }) {
        val content = fittedLiveViewRect(size.width, size.height, sourceAspectRatio)
        clipRect(content.left, content.top, content.left + content.width, content.top + content.height) {
            info.frames.forEach { frame ->
                val bounds = LiveViewRect(
                    content.left + frame.left * content.width,
                    content.top + frame.top * content.height,
                    (frame.right - frame.left) * content.width,
                    (frame.bottom - frame.top) * content.height,
                )
                val color = when (frame.status) {
                    CameraFocusStatus.FOCUSED -> AppSuccess
                    CameraFocusStatus.UNFOCUSED -> AppRecord
                    CameraFocusStatus.FOCUSING -> AppAccent
                    CameraFocusStatus.STANDBY -> AppText
                    CameraFocusStatus.SERVO_OFF, CameraFocusStatus.UNKNOWN -> AppMutedText
                }
                drawFocusCorners(bounds, color)
            }
        }
    }
}

internal fun focusInfoMatchesImage(info: CameraFocusInfo, sourceAspectRatio: Float): Boolean =
    sourceAspectRatio > 0f && kotlin.math.abs(info.imageAspectRatio / sourceAspectRatio - 1f) <= 0.02f

internal fun cameraFocusStatusResource(status: CameraFocusStatus): Int = when (status) {
    CameraFocusStatus.FOCUSED -> R.string.camera_focus_confirmed
    CameraFocusStatus.UNFOCUSED -> R.string.camera_focus_failed
    CameraFocusStatus.FOCUSING -> R.string.camera_focus_tracking
    CameraFocusStatus.STANDBY -> R.string.camera_focus_standby
    CameraFocusStatus.SERVO_OFF -> R.string.camera_focus_servo_off
    CameraFocusStatus.UNKNOWN -> R.string.camera_focus_unknown
}

private fun DrawScope.drawFocusCorners(bounds: LiveViewRect, color: Color) {
    if (bounds.width <= 0f || bounds.height <= 0f) return
    val arm = minOf(bounds.width, bounds.height).times(0.22f).coerceAtMost(12.dp.toPx())
    val path = Path().apply {
        for (right in listOf(false, true)) {
            for (bottom in listOf(false, true)) {
                val x = bounds.left + if (right) bounds.width else 0f
                val y = bounds.top + if (bottom) bounds.height else 0f
                moveTo(x + if (right) -arm else arm, y)
                lineTo(x, y)
                lineTo(x, y + if (bottom) -arm else arm)
            }
        }
    }
    drawPath(path, Color.Black.copy(alpha = 0.65f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
