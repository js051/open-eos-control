package dev.openeos.control.ui

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.roundToInt

data class CubeLut(
    val name: String,
    val size: Int,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val values: FloatArray,
) {
    init {
        require(name.isNotBlank())
        require(size in MIN_CUBE_LUT_SIZE..MAX_CUBE_LUT_SIZE)
        require(domainMin.size == 3 && domainMax.size == 3)
        require(values.size == size * size * size * 3)
    }

    fun sample(red: Float, green: Float, blue: Float): FloatArray {
        val redCoordinate = coordinate(red, 0)
        val greenCoordinate = coordinate(green, 1)
        val blueCoordinate = coordinate(blue, 2)
        val r0 = floor(redCoordinate).toInt()
        val g0 = floor(greenCoordinate).toInt()
        val b0 = floor(blueCoordinate).toInt()
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)
        val rt = redCoordinate - r0
        val gt = greenCoordinate - g0
        val bt = blueCoordinate - b0
        return FloatArray(3) { channel -> sampleChannel(r0, g0, b0, r1, g1, b1, rt, gt, bt, channel) }
    }

    private fun coordinate(value: Float, channel: Int): Float =
        ((value - domainMin[channel]) / (domainMax[channel] - domainMin[channel]))
            .coerceIn(0f, 1f) * (size - 1)

    private fun value(red: Int, green: Int, blue: Int, channel: Int): Float =
        values[((blue * size * size + green * size + red) * 3) + channel]

    private fun sampleChannel(
        r0: Int,
        g0: Int,
        b0: Int,
        r1: Int,
        g1: Int,
        b1: Int,
        rt: Float,
        gt: Float,
        bt: Float,
        channel: Int,
    ): Float {
        val c00 = lerp(value(r0, g0, b0, channel), value(r1, g0, b0, channel), rt)
        val c10 = lerp(value(r0, g1, b0, channel), value(r1, g1, b0, channel), rt)
        val c01 = lerp(value(r0, g0, b1, channel), value(r1, g0, b1, channel), rt)
        val c11 = lerp(value(r0, g1, b1, channel), value(r1, g1, b1, channel), rt)
        return lerp(lerp(c00, c10, gt), lerp(c01, c11, gt), bt).coerceIn(0f, 1f)
    }

    fun sampleArgb(pixel: Int): Int {
        val redCoordinate = coordinate((pixel shr 16 and 0xff) / 255f, 0)
        val greenCoordinate = coordinate((pixel shr 8 and 0xff) / 255f, 1)
        val blueCoordinate = coordinate((pixel and 0xff) / 255f, 2)
        val r0 = floor(redCoordinate).toInt()
        val g0 = floor(greenCoordinate).toInt()
        val b0 = floor(blueCoordinate).toInt()
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)
        val rt = redCoordinate - r0
        val gt = greenCoordinate - g0
        val bt = blueCoordinate - b0
        val red = sampleChannel(r0, g0, b0, r1, g1, b1, rt, gt, bt, 0)
        val green = sampleChannel(r0, g0, b0, r1, g1, b1, rt, gt, bt, 1)
        val blue = sampleChannel(r0, g0, b0, r1, g1, b1, rt, gt, bt, 2)
        return (pixel and 0xff000000.toInt()) or
            ((red * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((green * 255f).roundToInt().coerceIn(0, 255) shl 8) or
            (blue * 255f).roundToInt().coerceIn(0, 255)
    }
}

internal fun parseCubeLut(text: String, fallbackName: String): CubeLut {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_CUBE_LUT_BYTES) {
        "3D LUT exceeds the ${MAX_CUBE_LUT_BYTES / (1024 * 1024)} MiB limit."
    }
    var title: String? = null
    var size: Int? = null
    var domainMin = floatArrayOf(0f, 0f, 0f)
    var domainMax = floatArrayOf(1f, 1f, 1f)
    var hasDomainMin = false
    var hasDomainMax = false
    val values = ArrayList<Float>()

    text.lineSequence().forEachIndexed { index, sourceLine ->
        val line = sourceLine.substringBefore('#').trim()
        if (line.isEmpty()) return@forEachIndexed
        val tokens = line.split(Regex("\\s+"))
        when (tokens.first().uppercase()) {
            "TITLE" -> {
                require(title == null) { "Duplicate TITLE at line ${index + 1}." }
                title = line.substringAfter(' ', "").trim().trim('"').takeIf(String::isNotBlank)
            }
            "LUT_3D_SIZE" -> {
                require(size == null && values.isEmpty()) { "Duplicate or late LUT_3D_SIZE at line ${index + 1}." }
                require(tokens.size == 2) { "Invalid LUT_3D_SIZE at line ${index + 1}." }
                size = tokens[1].toIntOrNull()?.takeIf { it in MIN_CUBE_LUT_SIZE..MAX_CUBE_LUT_SIZE }
                    ?: error("3D LUT size must be between $MIN_CUBE_LUT_SIZE and $MAX_CUBE_LUT_SIZE.")
            }
            "DOMAIN_MIN" -> {
                require(!hasDomainMin && values.isEmpty()) { "Duplicate or late DOMAIN_MIN at line ${index + 1}." }
                domainMin = parseVector(tokens, index)
                hasDomainMin = true
            }
            "DOMAIN_MAX" -> {
                require(!hasDomainMax && values.isEmpty()) { "Duplicate or late DOMAIN_MAX at line ${index + 1}." }
                domainMax = parseVector(tokens, index)
                hasDomainMax = true
            }
            "LUT_3D_INPUT_RANGE" -> {
                require(!hasDomainMin && !hasDomainMax && values.isEmpty()) {
                    "Duplicate or conflicting input range at line ${index + 1}."
                }
                require(tokens.size == 3) { "Invalid LUT_3D_INPUT_RANGE at line ${index + 1}." }
                val minimum = tokens[1].toFiniteFloat(index)
                val maximum = tokens[2].toFiniteFloat(index)
                domainMin = FloatArray(3) { minimum }
                domainMax = FloatArray(3) { maximum }
                hasDomainMin = true
                hasDomainMax = true
            }
            "LUT_1D_SIZE" -> error("1D and shaper LUTs are not supported.")
            else -> {
                require(size != null) { "LUT_3D_SIZE must appear before table data (line ${index + 1})." }
                require(tokens.size == 3) { "Invalid 3D LUT row at line ${index + 1}." }
                tokens.forEach { values += it.toFiniteFloat(index) }
                require(values.size <= size!! * size!! * size!! * 3) {
                    "3D LUT contains more rows than LUT_3D_SIZE declares."
                }
            }
        }
    }

    val cubeSize = requireNotNull(size) { "LUT_3D_SIZE is required." }
    require(domainMin.indices.all { domainMin[it].isFinite() && domainMax[it].isFinite() && domainMin[it] < domainMax[it] }) {
        "Every LUT domain minimum must be lower than its maximum."
    }
    require(values.size == cubeSize * cubeSize * cubeSize * 3) {
        "3D LUT requires ${cubeSize * cubeSize * cubeSize} RGB rows; found ${values.size / 3}."
    }
    val safeName = title?.take(MAX_CUBE_LUT_NAME_LENGTH)
        ?: fallbackName.substringBeforeLast('.').trim().take(MAX_CUBE_LUT_NAME_LENGTH).ifBlank { "Imported LUT" }
    return CubeLut(safeName, cubeSize, domainMin, domainMax, values.toFloatArray())
}

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

private fun parseVector(tokens: List<String>, lineIndex: Int): FloatArray {
    require(tokens.size == 4) { "Invalid LUT domain at line ${lineIndex + 1}." }
    return FloatArray(3) { tokens[it + 1].toFiniteFloat(lineIndex) }
}

private fun String.toFiniteFloat(lineIndex: Int): Float =
    toFloatOrNull()?.takeIf(Float::isFinite) ?: error("Invalid finite number at line ${lineIndex + 1}.")

private fun lerp(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount

internal const val MAX_CUBE_LUT_BYTES = 16 * 1024 * 1024
private const val MIN_CUBE_LUT_SIZE = 2
private const val MAX_CUBE_LUT_SIZE = 64
private const val MAX_CUBE_LUT_NAME_LENGTH = 120
