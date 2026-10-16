package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import org.json.JSONArray
import org.json.JSONObject

/** Formal Gradle resource loader. The offline harness substitutes only this DATA-loading layer. */
internal fun loadMediaTypeFixture(): MediaTypeFixture {
    val bytes = requireNotNull(MediaTypeFixture::class.java.getResourceAsStream("/media-type-vectors.json"))
        .use { it.readNBytes(64 * 1024 + 1) }
    require(bytes.size <= 64 * 1024)
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    return MediaTypeFixture(
        root.getInt("schemaVersion"),
        root.getJSONArray("cases").objects().map { value ->
            val expected = value.getJSONObject("expected")
            MediaTypeVector(value.getString("id"), value.getJSONObject("item").item(),
                expected.getString("classification"), expected.getBoolean("isPhoto"),
                expected.getBoolean("isVideo"), expected.getBoolean("automaticJpegEligible"))
        },
        root.getJSONArray("freshMetadataCases").objects().map { value ->
            MediaTypeFreshVector(value.getString("id"), value.getJSONObject("original").item(),
                value.getJSONObject("metadata").item(), value.getBoolean("fresh"), value.getBoolean("expectedAdmitted"))
        },
        root.getJSONArray("selectionCases").objects().map { value ->
            MediaTypeSelectionVector(value.getString("id"), value.getString("intent"),
                value.getJSONArray("listingCases").strings(), value.getJSONArray("knownCaseIds").strings(),
                if (value.isNull("expectedCaseId")) null else value.getString("expectedCaseId"))
        },
        root.getJSONArray("mimeAliasCases").objects().map { value ->
            val fields = value.getJSONObject("fields")
            MediaTypeMimeAliasVector(value.getString("id"), fields.keys().asSequence().associateWith { key ->
                if (fields.isNull(key)) null else fields.get(key)
            }, value.getBoolean("expectedValid"), if (value.isNull("expectedValue")) null else value.getString("expectedValue"))
        },
    )
}
private fun JSONObject.item() = CameraMediaItem(getString("id"), getString("name"), getString("kind"),
    contentType = if (isNull("contentType")) null else getString("contentType"))
private fun JSONArray.objects(): List<JSONObject> {
    require(length() in 1..100)
    return (0 until length()).map(::getJSONObject)
}
private fun JSONArray.strings(): List<String> {
    require(length() <= 100)
    return (0 until length()).map(::getString)
}
