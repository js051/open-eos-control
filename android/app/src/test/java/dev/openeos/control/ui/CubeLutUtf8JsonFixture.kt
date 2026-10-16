package dev.openeos.control.ui

import java.util.Base64
import org.json.JSONObject

/** The normal Gradle path reads the shared JSON; no test-only decoder/importer is substituted. */
internal fun loadCubeLutUtf8Vectors(): List<CubeLutUtf8Vector> {
    val bytes = requireNotNull(CubeLutUtf8Vector::class.java.getResourceAsStream("/utf8-vectors.json"))
        .use { it.readNBytes(64 * 1024 + 1) }
    require(bytes.size <= 64 * 1024)
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    require(root.getInt("schemaVersion") == 1 && root.getInt("maximumBytes") == MAX_CUBE_LUT_BYTES)
    val items = root.getJSONArray("vectors")
    return List(items.length()) { index ->
        val value = items.getJSONObject(index)
        CubeLutUtf8Vector(value.getString("id"), Base64.getDecoder().decode(value.getString("base64")),
            value.getBoolean("accepted"), value.optString("normalizedText").takeIf { value.has("normalizedText") })
    }
}
