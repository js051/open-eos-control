package dev.openeos.control.data

/** MediaCodec crop edges are inclusive. Surface rendering applies crop/rotation before sampling. */
internal fun nativeVideoDisplayDimensions(
    codedWidth: Int,
    codedHeight: Int,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropRight: Int = -1,
    cropBottom: Int = -1,
    rotation: Int = 0,
): Pair<Int, Int> {
    val width = codedWidth.coerceAtLeast(1)
    val height = codedHeight.coerceAtLeast(1)
    val visibleWidth = if (cropLeft in 0 until width && cropRight in cropLeft until width) {
        cropRight - cropLeft + 1
    } else width
    val visibleHeight = if (cropTop in 0 until height && cropBottom in cropTop until height) {
        cropBottom - cropTop + 1
    } else height
    return if (((rotation % 360) + 360) % 360 in setOf(90, 270)) {
        visibleHeight to visibleWidth
    } else visibleWidth to visibleHeight
}
