package dev.openeos.control.data

import org.json.JSONObject

internal fun loadNativeRtpMonitorFixture(): List<NativeRtpMonitorVector> {
    val text = requireNotNull(NativeRtpMonitorContractTest::class.java.classLoader
        ?.getResourceAsStream("monitor-vectors.json")).bufferedReader().use { it.readText() }
    check(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) } == "6809376250b9ffb91a74a4348d69b7fd03d397f03aecc47e95d5da3a0cc03e45")
    fun map(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key ->
        json.get(key).takeUnless { it === JSONObject.NULL }
    }
    val rows = JSONObject(text).getJSONArray("cases")
    return (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
        NativeRtpMonitorVector(it.getString("name"), map(it.getJSONObject("input")), map(it.getJSONObject("expected")))
    } }
}
