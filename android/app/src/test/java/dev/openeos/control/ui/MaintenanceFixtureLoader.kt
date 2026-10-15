package dev.openeos.control.ui

import org.json.JSONObject

internal fun loadMaintenanceFixture(): MaintenanceFixture {
    val text = requireNotNull(MaintenanceSharedContractTest::class.java.classLoader?.getResourceAsStream("maintenance-vectors.json"))
        .bufferedReader().use { it.readText() }
    check(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) } ==
        "22bdd7fd4473db5a3ccfefb5c52c39e4141343658f0b5e75351082f6c243078b")
    val root = JSONObject(text)
    fun rows(key: String) = root.getJSONArray(key).let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    fun map(body: JSONObject): Map<String, Any?> = body.keys().asSequence().associateWith { key ->
        when (val value = body.opt(key)) { JSONObject.NULL -> null; is JSONObject -> map(value); else -> value }
    }
    return MaintenanceFixture(rows("clock").map { row -> MaintenanceClockVector(row.getString("name"),
        row.optJSONObject("reference")?.let(::map), row.optJSONObject("observation")?.let(::map), row.getBoolean("valid"), row.getBoolean("matched")) },
        rows("wire").map { row -> MaintenanceWireVector(row.getString("name"), row.getInt("status"), row.getString("engine"),
            row.getString("operation"), row.getBoolean("autoPowerOff"), map(row.getJSONObject("response")), row.getBoolean("expectedResponse"), row.getString("expectedClockRead")) },
        rows("ownership").map { row -> val i = row.getJSONObject("input"); MaintenanceOwnerVector(
            i.getBoolean("sameOwner"), i.getBoolean("sameAttempt"), i.getBoolean("cancelled"), i.getBoolean("retired"),
            i.getBoolean("responseReceived"), i.getBoolean("stopConfirmed"), i.getString("operation"), i.getBoolean("autoPowerOff"),
            i.getBoolean("wasLive"), i.getBoolean("freshReachable"), i.getBoolean("safetyClear"), row.getBoolean("canPublish"), row.getBoolean("canRestore")) })
}
