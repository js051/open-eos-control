package dev.openeos.control.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

@Composable
fun Modifier.cameraControlRotation(): Modifier {
    val rotation = LocalCameraControlRotation.current
    return graphicsLayer { rotationZ = rotation }
}

@Composable
fun Modifier.cameraLayoutRotation(): Modifier {
    val animatedRotation = LocalCameraControlRotation.current
    val targetRotation = LocalCameraControlTargetRotation.current
    val swapDimensions = cameraRotationSwapsDimensions(targetRotation)

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
                rotationZ = animatedRotation
                transformOrigin = TransformOrigin.Center
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
