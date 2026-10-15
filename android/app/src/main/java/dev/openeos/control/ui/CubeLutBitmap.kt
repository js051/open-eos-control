package dev.openeos.control.ui

import android.graphics.Bitmap

internal fun applyCubeLut(
    bitmap: Bitmap,
    lut: CubeLut,
    checkCancellation: () -> Unit = {},
): Bitmap {
    require(bitmap.width > 0 && bitmap.height > 0)
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    pixels.indices.forEach { index ->
        if (index % (bitmap.width * 8).coerceAtLeast(1) == 0) checkCancellation()
        val pixel = pixels[index]
        pixels[index] = lut.sampleArgb(pixel)
    }
    val result = Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    return try {
        checkCancellation()
        result
    } catch (error: Throwable) {
        result.recycle()
        throw error
    }
}
