package dev.openeos.control.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loads the same bounded repository resource used by PC and iOS, without regenerated expectations. */
internal fun loadLiveViewLutNumericFixture(): LiveViewLutNumericFixture {
    val bytes = requireNotNull(LiveViewLutNumericFixture::class.java.getResourceAsStream("/numeric-vectors.json")) {
        "Missing contracts/live-view-lut/v1/numeric-vectors.json test resource"
    }.use { it.readNBytes(64 * 1024 + 1) }
    require(bytes.size <= 64 * 1024) { "Numeric LUT contract exceeds 64 KiB" }
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }
    fun JSONArray.ints() = IntArray(length()) { index -> getInt(index).also { value ->
        require(getDouble(index) == value.toDouble()) { "Expected an integer byte" }
    } }
    fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> = List(length()) { transform(getJSONObject(it)) }
    return LiveViewLutNumericFixture(
        byteCount = bytes.size,
        schemaVersion = root.getInt("schemaVersion"),
        colorEncoding = root.getString("colorEncoding"),
        interpolation = root.getString("interpolation"),
        tableOrder = root.getString("tableOrder"),
        cpuAbsoluteTolerance = root.getDouble("cpuAbsoluteTolerance").toFloat(),
        uint8Rounding = root.getString("uint8Rounding"),
        luts = root.getJSONArray("luts").mapObjects { item ->
            val size = item.getInt("size")
            require(item.getDouble("size") == size.toDouble()) { "Expected an integer LUT size" }
            NumericLut(item.getString("id"), size, item.getJSONArray("domainMin").floats(),
                item.getJSONArray("domainMax").floats(), item.getJSONArray("values").floats())
        },
        vectors = root.getJSONArray("vectors").mapObjects { item -> NumericLutVector(
            item.getString("id"), item.getString("lutId"), item.getJSONArray("inputRgb").floats(),
            item.getJSONArray("expectedRgb").floats(), item.optJSONArray("inputRgb8")?.ints(),
            item.optJSONArray("expectedRgb8")?.ints(),
        ) },
        quantizationVectors = root.getJSONArray("quantizationVectors").mapObjects { item -> NumericQuantizationVector(
            item.getString("id"), item.getJSONArray("rgb").floats(), item.getJSONArray("expectedRgb8").ints(),
        ) },
    )
}

class LiveViewLutNumericJsonFixtureTest {
    @Test fun resourceMappingLoadsTheBoundedSharedJson() {
        val fixture = loadLiveViewLutNumericFixture()
        assertTrue(fixture.byteCount in 1..64 * 1024)
        assertEquals(12, fixture.vectors.size)
        assertEquals(6, fixture.luts.size)
    }
}
