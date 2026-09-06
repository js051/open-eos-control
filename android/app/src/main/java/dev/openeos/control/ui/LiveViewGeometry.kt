package dev.openeos.control.ui

internal data class LiveViewRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

internal data class LiveViewDisplayPoint(
    val x: Float,
    val y: Float,
)

internal fun fittedLiveViewRect(
    containerWidth: Float,
    containerHeight: Float,
    sourceAspectRatio: Float,
): LiveViewRect {
    if (containerWidth <= 0f || containerHeight <= 0f || sourceAspectRatio <= 0f) {
        return LiveViewRect(0f, 0f, 0f, 0f)
    }

    val containerAspectRatio = containerWidth / containerHeight
    return if (containerAspectRatio > sourceAspectRatio) {
        val width = containerHeight * sourceAspectRatio
        LiveViewRect(
            left = (containerWidth - width) / 2f,
            top = 0f,
            width = width,
            height = containerHeight,
        )
    } else {
        val height = containerWidth / sourceAspectRatio
        LiveViewRect(
            left = 0f,
            top = (containerHeight - height) / 2f,
            width = containerWidth,
            height = height,
        )
    }
}

internal fun mapLiveViewTap(
    tapX: Float,
    tapY: Float,
    containerWidth: Float,
    containerHeight: Float,
    sourceAspectRatio: Float,
): FocusPoint? {
    val content = fittedLiveViewRect(containerWidth, containerHeight, sourceAspectRatio)
    if (content.width <= 0f || content.height <= 0f) return null
    if (tapX < content.left || tapX > content.left + content.width) return null
    if (tapY < content.top || tapY > content.top + content.height) return null

    return FocusPoint(
        x = ((tapX - content.left) / content.width).coerceIn(0f, 1f).toDouble(),
        y = ((tapY - content.top) / content.height).coerceIn(0f, 1f).toDouble(),
    )
}

internal fun mapFocusPointToDisplay(
    focusPoint: FocusPoint,
    containerWidth: Float,
    containerHeight: Float,
    sourceAspectRatio: Float,
): LiveViewDisplayPoint {
    val content = fittedLiveViewRect(containerWidth, containerHeight, sourceAspectRatio)
    return LiveViewDisplayPoint(
        x = content.left + focusPoint.x.coerceIn(0.0, 1.0).toFloat() * content.width,
        y = content.top + focusPoint.y.coerceIn(0.0, 1.0).toFloat() * content.height,
    )
}

internal fun focusIndicatorBounds(
    point: FocusPoint,
    containerWidth: Float,
    containerHeight: Float,
    sourceAspectRatio: Float,
    requestedEdge: Float,
): LiveViewRect {
    val content = fittedLiveViewRect(containerWidth, containerHeight, sourceAspectRatio)
    val edge = requestedEdge.coerceAtLeast(0f).coerceAtMost(minOf(content.width, content.height) * 0.8f)
    val center = mapFocusPointToDisplay(point, containerWidth, containerHeight, sourceAspectRatio)
    // Inset strokes at the image edges without changing the coordinate sent to the camera.
    val margin = edge * 0.05f
    return LiveViewRect(
        left = (center.x - edge / 2f).coerceIn(content.left + margin, content.left + content.width - edge - margin),
        top = (center.y - edge / 2f).coerceIn(content.top + margin, content.top + content.height - edge - margin),
        width = edge,
        height = edge,
    )
}
