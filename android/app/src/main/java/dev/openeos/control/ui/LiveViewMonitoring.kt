package dev.openeos.control.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

enum class LiveViewFrameGuide(val aspectRatio: Float?) {
    OFF(null),
    RATIO_16_9(16f / 9f),
    RATIO_2_39(2.39f),
    RATIO_1_1(1f),
    RATIO_4_3(4f / 3f),
}

enum class LiveViewDesqueeze(val horizontalScale: Float) {
    OFF(1f),
    X1_33(1.33f),
    X1_5(1.5f),
    X1_8(1.8f),
    X2(2f),
}

data class LiveViewMonitorSettings(
    val histogramVisible: Boolean = false,
    val zebraThresholdPercent: Int? = null,
    val falseColorEnabled: Boolean = false,
    val focusPeakingEnabled: Boolean = false,
    val frameGuide: LiveViewFrameGuide = LiveViewFrameGuide.OFF,
    val safeAreaVisible: Boolean = false,
    val desqueeze: LiveViewDesqueeze = LiveViewDesqueeze.OFF,
) {
    val needsPixelAnalysis: Boolean
        get() = histogramVisible || zebraThresholdPercent != null || falseColorEnabled || focusPeakingEnabled
}

internal data class LiveViewMonitorAnalysis(
    val width: Int,
    val height: Int,
    val histogram: IntArray,
    val overlayPixels: IntArray?,
)

internal fun analyzeLiveViewBitmap(
    bitmap: Bitmap,
    zebraThresholdPercent: Int?,
    focusPeakingEnabled: Boolean,
    falseColorEnabled: Boolean = false,
): LiveViewMonitorAnalysis {
    val dimensions = analysisDimensions(bitmap.width, bitmap.height)
    val sampled = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
    Canvas(sampled).drawBitmap(
        bitmap,
        null,
        Rect(0, 0, dimensions.first, dimensions.second),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
    )
    return try {
        val pixels = IntArray(dimensions.first * dimensions.second)
        sampled.getPixels(pixels, 0, dimensions.first, 0, 0, dimensions.first, dimensions.second)
        analyzeLiveViewPixels(
            pixels = pixels,
            width = dimensions.first,
            height = dimensions.second,
            zebraThresholdPercent = zebraThresholdPercent,
            focusPeakingEnabled = focusPeakingEnabled,
            falseColorEnabled = falseColorEnabled,
        )
    } finally {
        sampled.recycle()
    }
}

internal fun analyzeLiveViewPixels(
    pixels: IntArray,
    width: Int,
    height: Int,
    zebraThresholdPercent: Int?,
    focusPeakingEnabled: Boolean,
    falseColorEnabled: Boolean = false,
): LiveViewMonitorAnalysis {
    require(width > 0 && height > 0)
    require(pixels.size == width * height)
    require(zebraThresholdPercent == null || zebraThresholdPercent in 50..100)

    val luminance = IntArray(pixels.size)
    val histogram = IntArray(HISTOGRAM_BUCKETS)
    pixels.forEachIndexed { index, pixel ->
        val red = pixel shr 16 and 0xff
        val green = pixel shr 8 and 0xff
        val blue = pixel and 0xff
        val value = (54 * red + 183 * green + 19 * blue) shr 8
        luminance[index] = value
        histogram[min(HISTOGRAM_BUCKETS - 1, value * HISTOGRAM_BUCKETS / 256)]++
    }

    val overlay = if (zebraThresholdPercent != null || falseColorEnabled || focusPeakingEnabled) {
        IntArray(pixels.size)
    } else {
        null
    }
    val zebraLuminance = zebraThresholdPercent?.let { it * 255 / 100 }

    if (overlay != null) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (falseColorEnabled) overlay[index] = falseColor(luminance[index])
                if (zebraLuminance != null && luminance[index] >= zebraLuminance) {
                    overlay[index] = if (((x + y) / 2) % 4 < 2) ZEBRA_LIGHT else ZEBRA_DARK
                }
            }
        }
        if (focusPeakingEnabled && width >= 3 && height >= 3) {
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val index = y * width + x
                    val gradient = abs(luminance[index + 1] - luminance[index - 1]) +
                        abs(luminance[index + width] - luminance[index - width])
                    if (gradient >= FOCUS_PEAKING_GRADIENT) overlay[index] = FOCUS_PEAKING_COLOR
                }
            }
        }
    }

    return LiveViewMonitorAnalysis(width, height, histogram, overlay)
}

private fun falseColor(luminance: Int): Int = when {
    luminance < 16 -> 0xE050167A.toInt()
    luminance < 40 -> 0xE0203DA0.toInt()
    luminance < 75 -> 0xE029B6D1.toInt()
    luminance < 140 -> 0xE04CAF50.toInt()
    luminance < 190 -> 0xE0F2D13D.toInt()
    luminance < 235 -> 0xE0F28C28.toInt()
    else -> 0xE0E83B35.toInt()
}

private fun analysisDimensions(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = min(1f, min(MAX_ANALYSIS_WIDTH.toFloat() / width, MAX_ANALYSIS_HEIGHT.toFloat() / height))
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

private const val MAX_ANALYSIS_WIDTH = 120
private const val MAX_ANALYSIS_HEIGHT = 80
private const val HISTOGRAM_BUCKETS = 64
private const val FOCUS_PEAKING_GRADIENT = 72
private const val ZEBRA_LIGHT = 0xB0FFFFFF.toInt()
private const val ZEBRA_DARK = 0x78000000
private const val FOCUS_PEAKING_COLOR = 0xFF28C5D9.toInt()
