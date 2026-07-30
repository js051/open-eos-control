package dev.openeos.control.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

@Composable
fun Modifier.cameraControlRotation(): Modifier {
    if (LocalCameraContentRotationHandled.current) return this
    val rotation = LocalCameraControlRotation.current
    return graphicsLayer { rotationZ = rotation }
}

/**
 * Keeps the control's outer geometry stable while remeasuring its content for a quarter turn.
 * This mirrors the measure/layout behavior of AOSP Camera's RotateLayout.
 */
@Composable
fun CameraRotatingSlot(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    animateRotation: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier, contentAlignment = contentAlignment) {
        CompositionLocalProvider(LocalCameraContentRotationHandled provides true) {
            Box(
                Modifier
                    .fillMaxSize()
                    .cameraLayoutRotation(animateRotation),
                contentAlignment = contentAlignment,
                content = content,
            )
        }
    }
}

/**
 * Rotates one compact camera control without changing the geometry of its parent slot.
 * Square content is the camera-app equivalent of AOSP Camera's atomic Rotatable controls.
 */
@Composable
fun CameraRotatingSquareSlot(
    size: Dp,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    animateRotation: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier, contentAlignment = contentAlignment) {
        CameraRotatingSlot(
            modifier = Modifier.size(size),
            contentAlignment = contentAlignment,
            animateRotation = animateRotation,
            content = content,
        )
    }
}

/**
 * Rotates reading-heavy content while preserving its wide shape from the user's viewpoint.
 * Stable toolbar slots should continue to use [CameraRotatingSlot] so their positions do not move.
 */
@Composable
fun CameraReadableSlot(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    animateRotation: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val swapDimensions = cameraRotationSwapsDimensions(LocalCameraControlTargetRotation.current)
    CameraRotatingSlot(
        modifier = modifier
            .width(if (swapDimensions) height else width)
            .height(if (swapDimensions) width else height),
        contentAlignment = contentAlignment,
        animateRotation = animateRotation,
        content = content,
    )
}

@Composable
fun Modifier.cameraLayoutRotation(animateRotation: Boolean = true): Modifier {
    val animatedRotation = LocalCameraControlRotation.current
    val targetRotation = LocalCameraControlTargetRotation.current
    val swapDimensions = cameraRotationSwapsDimensions(targetRotation)
    val displayedRotation = if (animateRotation) animatedRotation else targetRotation

    return layout { measurable, constraints ->
        val placeable = measurable.measure(
            if (swapDimensions) constraints.swapAxes() else constraints,
        )
        val naturalWidth = if (swapDimensions) placeable.height else placeable.width
        val naturalHeight = if (swapDimensions) placeable.width else placeable.height
        val width = naturalWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = naturalHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, height) {
            placeable.placeWithLayer(
                x = (width - placeable.width) / 2,
                y = (height - placeable.height) / 2,
            ) {
                rotationZ = displayedRotation
                transformOrigin = TransformOrigin.Center
                clip = true
            }
        }
    }
}

internal fun cameraRotationSwapsDimensions(rotationDegrees: Float): Boolean {
    return cameraRotationQuadrant(rotationDegrees) % 2 == 1
}

private fun Constraints.swapAxes(): Constraints = Constraints(
    minWidth = minHeight,
    maxWidth = maxHeight,
    minHeight = minWidth,
    maxHeight = maxWidth,
)
