package dev.openeos.control.data

import org.json.JSONObject

internal fun loadLiveViewLifecycleFixture(): List<LiveViewLifecycleVector> {
    val text = requireNotNull(LiveViewLifecycleContractTest::class.java.classLoader
        ?.getResourceAsStream("liveview-vectors.json")).bufferedReader().use { it.readText() }
    check(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) } == "ad47258a156a2934f90300807cbabcb73ea50a8f27d27c7ae8e0f7a9e91150e2")
    fun map(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key ->
        json.get(key).takeUnless { it === JSONObject.NULL }
    }
    val rows = JSONObject(text).getJSONArray("cases")
    return (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
        LiveViewLifecycleVector(it.getString("name"), map(it.getJSONObject("input")), map(it.getJSONObject("expected")))
    } }
}
