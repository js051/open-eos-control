package dev.openeos.control.ui

internal data class LiveViewLutNumericFixture(
    val byteCount: Int,
    val schemaVersion: Int,
    val colorEncoding: String,
    val interpolation: String,
    val tableOrder: String,
    val cpuAbsoluteTolerance: Float,
    val uint8Rounding: String,
    val luts: List<NumericLut>,
    val vectors: List<NumericLutVector>,
    val quantizationVectors: List<NumericQuantizationVector>,
) {
    init {
        require(byteCount in 1..64 * 1024)
        require(luts.size in 1..16 && luts.map { it.id }.toSet().size == luts.size)
        require(vectors.size in 1..16 && vectors.map { it.id }.toSet().size == vectors.size)
        require(vectors.all { vector -> luts.any { it.id == vector.lutId } })
        require(quantizationVectors.size in 1..16)
    }
}

internal data class NumericLut(
    val id: String,
    val size: Int,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val values: FloatArray,
) {
    init {
        require(id.length in 1..120 && size in 2..3)
        require(domainMin.size == 3 && domainMax.size == 3)
        require(domainMin.all(Float::isFinite) && domainMax.all(Float::isFinite))
        require((0..2).all { domainMin[it] < domainMax[it] })
        require(values.size == size * size * size * 3 && values.all(Float::isFinite))
    }

    fun cubeText(): String = buildString {
        appendLine("LUT_3D_SIZE $size")
        appendLine("DOMAIN_MIN ${domainMin.joinToString(" ")}")
        appendLine("DOMAIN_MAX ${domainMax.joinToString(" ")}")
        values.asList().chunked(3).forEach { appendLine(it.joinToString(" ")) }
    }
}

internal data class NumericLutVector(
    val id: String,
    val lutId: String,
    val inputRgb: FloatArray,
    val expectedRgb: FloatArray,
    val inputRgb8: IntArray?,
    val expectedRgb8: IntArray?,
) {
    init {
        require(id.length in 1..120)
        require(inputRgb.size == 3 && inputRgb.all(Float::isFinite))
        require(expectedRgb.size == 3 && expectedRgb.all { it.isFinite() && it in 0f..1f })
        require((inputRgb8 == null) == (expectedRgb8 == null))
        for (bytes in listOfNotNull(inputRgb8, expectedRgb8)) require(bytes.size == 3 && bytes.all { it in 0..255 })
    }
}

internal data class NumericQuantizationVector(val id: String, val rgb: FloatArray, val expectedRgb8: IntArray) {
    init {
        require(id.length in 1..120 && rgb.size == 3 && rgb.all(Float::isFinite))
        require(expectedRgb8.size == 3 && expectedRgb8.all { it in 0..255 })
    }
}
