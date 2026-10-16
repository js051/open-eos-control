package dev.openeos.control.ui

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Formal Gradle loader exercises org.json raw literal typing; offline evidence substitutes only this loader. */
internal fun loadMediaMutationFixture(): MediaMutationFixture {
    val bytes = requireNotNull(MediaMutationFixture::class.java.getResourceAsStream("/media-mutation-vectors.json"))
        .use { it.readNBytes(128 * 1024 + 1) }
    require(bytes.size <= 128 * 1024)
    require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
        "2d30173f8abb0538f1aa320cfce24e2fb0e01f86ef6fc54db93a052ed2fdd401")
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    require(root.getInt("schemaVersion") == 1)
    return MediaMutationFixture(
        root.getJSONArray("metadataVectors").objects().map { value ->
            MetadataVector(value.getString("id"), value.getString("profile"), value.getString("field"), value.raw(), value.nullable("value"))
        },
        root.getJSONArray("readbackVectors").objects().map { value ->
            val target = value.get("target")
            ReadbackVector(value.getString("id"), value.getString("profile"), value.getString("operation"),
                if (target is Boolean) { if (target) 1 else 0 } else (target as Number).toInt(),
                value.getString("requestedId"), value.getBoolean("returnedIdPresent"), value.nullable("returnedId"), value.raw(), value.getString("outcome"))
        },
        root.getJSONArray("batchVectors").objects().map { value ->
            val dispatched = value.getJSONArray("dispatchedIndices")
            BatchVector(value.getString("id"), value.getJSONArray("events").strings(), value.getJSONArray("outcomes").strings(),
                (0 until dispatched.length()).map(dispatched::getInt))
        },
        root.getJSONArray("rosterVectors").objects().map { value ->
            RosterVector(value.getString("id"), value.getJSONArray("selectedIds").strings(), value.getJSONArray("loadedIds").strings().toSet(),
                value.getJSONArray("visibleIds").strings().toSet(), value.getJSONArray("rosterIds").strings(), value.getJSONArray("eligibleIds").strings(),
                value.getInt("hiddenCount"), value.getInt("unloadedCount"))
        },
    )
}
private fun JSONObject.nullable(key: String): Any? = if (isNull(key)) null else get(key)
private fun JSONObject.raw(): Any? = if (isNull("rawValueJSON")) null else
    JSONObject("{\"value\":${getString("rawValueJSON")}}").nullable("value")
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

internal fun loadMediaMutationEligibilityFixture(): MediaMutationEligibilityFixture {
    val bytes = requireNotNull(MediaMutationFixture::class.java.getResourceAsStream("/media-mutation-eligibility-vectors.json"))
        .use { it.readNBytes(64 * 1024 + 1) }
    require(bytes.size <= 64 * 1024)
    require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
        "c7c6e27e7455ff12989cea4fab48f97fd103344ff442987a37cc4a32e3f99a1d")
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    require(root.getInt("schemaVersion") == 1)
    return MediaMutationEligibilityFixture(
        root.getJSONArray("vectors").objects().map { value ->
            EligibilityVector(value.getString("id"), value.getString("operation"), value.getString("requestedId"),
                value.getBoolean("loaded"), value.getBoolean("supported"), value.getJSONObject("item").fields(), value.getBoolean("eligible"))
        },
        root.getJSONArray("snapshotVectors").objects().map { value ->
            EligibilitySnapshot(value.getString("id"), value.getString("operation"), value.getJSONObject("selected").fields(),
                value.getJSONObject("loaded").fields(), value.getBoolean("eligible"))
        },
    )
}
private fun JSONObject.fields(): Map<String, Any?> = keys().asSequence().associateWith { nullable(it) }
