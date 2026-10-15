package dev.openeos.control.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AutomaticJpegSharedContractTest {
    @Test fun fixedCrossPlatformOriginalByteAndMimeVectorsMatchTheStructuralGate() {
        val vectors = JSONObject(requireNotNull(javaClass.classLoader!!.getResourceAsStream("jpeg-original-vectors.json"))
            .bufferedReader().use { it.readText() })
        assertEquals(1, vectors.getInt("schemaVersion"))
        val mimes = vectors.getJSONArray("mimeVectors")
        for (index in 0 until mimes.length()) {
            val vector = mimes.getJSONObject(index)
            val raw = if (vector.isNull("raw")) null else vector.getString("raw")
            assertEquals(vector.getString("id"), vector.getBoolean("valid"), automaticJpegResponseMimeAdmitted(raw))
        }
        val headers = vectors.getJSONArray("headerVectors")
        for (index in 0 until headers.length()) {
            val vector = headers.getJSONObject(index)
            val values = vector.getJSONArray("values")
            assertEquals(vector.getString("id"), vector.getBoolean("valid"), automaticJpegResponseHeadersAdmitted(
                (0 until values.length()).map(values::getString)))
        }
        val bytes = vectors.getJSONArray("byteVectors")
        for (index in 0 until bytes.length()) {
            val vector = bytes.getJSONObject(index)
            val data = Base64.getDecoder().decode(vector.getString("base64"))
            val actual = runCatching {
                validateAutomaticJpegStructure(data.inputStream(), vector.getLong("byteCount"), vector.getString("sha256"))
            }
            assertEquals(vector.getString("id"), vector.getBoolean("structuralValid"), actual.isSuccess)
            if (actual.isSuccess) {
                assertEquals(vector.getInt("width"), actual.getOrThrow().width)
                assertEquals(vector.getInt("height"), actual.getOrThrow().height)
            }
        }
    }
}
