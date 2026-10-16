package dev.openeos.control.ui

import dev.openeos.control.data.CameraMediaItem
import org.json.JSONArray
import org.json.JSONObject

/** Formal Gradle resource loader. Offline JVM harness replaces only this data-loading layer. */
internal fun loadMediaDateReviewFixture(): MediaDateReviewFixture {
    val bytes = requireNotNull(MediaDateReviewFixture::class.java.getResourceAsStream("/date-review-vectors.json"))
        .use { it.readNBytes(64 * 1024 + 1) }
    require(bytes.size <= 64 * 1024)
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    val zones = root.getJSONArray("displayZones").strings()
    fun items(key: String) = root.getJSONArray(key).objects(100).map { item ->
        CameraMediaItem(item.getString("id"), item.getString("name"), item.getString("kind"), captureTime = item.nullableString("captureTime"))
    }
    return MediaDateReviewFixture(
        schemaVersion = root.getInt("schemaVersion"), instantResolution = root.getString("instantResolution"),
        calendar = root.getString("calendar"), zones = zones, kinds = root.getJSONArray("kinds").strings(),
        precisionOrder = root.getJSONArray("dateSortPrecisionOrder").strings(), unknownAndTies = root.getString("unknownAndTies"),
        vectors = root.getJSONArray("vectors").objects(100).map { vector ->
            MediaDateVector(vector.getString("id"), vector.nullableString("raw"), vector.getString("kind"),
                if (vector.isNull("instantMillis")) null else vector.getLong("instantMillis"),
                zones.associateWith { zone ->
                    val display = vector.getJSONObject("display").getJSONObject(zone)
                    MediaDateDisplayVector(display.nullableString("dateKey"), display.nullableString("timeKey"))
                })
        },
        sortItems = items("sortItems"),
        sortCases = root.getJSONArray("sortCases").objects(100).map { case ->
            MediaDateSortVector(case.getString("zone"), case.getString("sort"), case.getJSONArray("expectedIds").strings())
        },
        reviewItems = items("reviewItems"),
        reviewCases = root.getJSONArray("reviewCases").objects(100).map { case ->
            MediaReviewVector(case.getString("kind"), case.getJSONArray("previousIds").strings().toSet(), case.nullableString("expectedId"))
        },
    )
}
private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONArray.objects(maximum: Int): List<JSONObject> {
    require(length() in 1..maximum)
    return (0 until length()).map(::getJSONObject)
}
private fun JSONArray.strings(): List<String> {
    require(length() <= 100)
    return (0 until length()).map(::getString)
}
