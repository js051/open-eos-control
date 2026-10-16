package dev.openeos.control.data

import org.json.JSONObject

internal fun loadRecordingFixture(): RecordingSharedFixture {
    val text = requireNotNull(RecordingLifecycleContractTest::class.java.classLoader?.getResourceAsStream("recording-vectors.json"))
        .bufferedReader().use { it.readText() }
    check(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) } ==
        "b96c731fee7e265ff8e7292df2e955ace44b726eb8e3fb09a90d34d1f4ff3a3c")
    val root = JSONObject(text)
    fun map(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key -> when (val value = json.get(key)) {
        JSONObject.NULL -> null
        is JSONObject -> map(value)
        else -> value
    } }
    fun rows(key: String) = root.getJSONArray(key).let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    return RecordingSharedFixture(rows("observations").map { RecordingObservationVector(it.getString("name"),
        map(it.getJSONObject("response")), it.getBoolean("valid")) }, rows("ownership").map { RecordingOwnerVector(it.getString("name"),
        map(it.getJSONObject("input")), map(it.getJSONObject("expected"))) })
}
