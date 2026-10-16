package dev.openeos.control.ui

import dev.openeos.control.data.*
import org.json.JSONObject

internal fun loadShootingSettingFixture(): ShootingFixture {
    val text = requireNotNull(ShootingSettingSharedContractTest::class.java.classLoader?.getResourceAsStream("shooting-setting-vectors.json"))
        .bufferedReader().use { it.readText() }
    check(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) } ==
        "31d0ee62c2875490a26d54dad0d435a990d074d7fc04598d34a52963a07d8864")
    val root = JSONObject(text)
    fun rows(key: String) = root.getJSONArray(key).let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    fun JSONObject.setting(): CameraSettingControl? {
        val body = optJSONObject("setting") ?: return null
        val key = body.opt("key") as? String ?: return null
        val value = body.opt("value") as? String ?: return null
        val label = body.opt("label") as? String ?: return null
        val values = body.optJSONArray("values") ?: return null
        val choices = (0 until values.length()).map { values.opt(it) as? String ?: return null }
        val kind = when (body.opt("inputKind")) { "choice" -> CameraSettingInputKind.CHOICE; "text" -> CameraSettingInputKind.TEXT; else -> return null }
        val max = if (!body.has("maxLength") || body.isNull("maxLength")) null else body.opt("maxLength") as? Int ?: return null
        return CameraSettingControl(key, label, value, choices, kind, max)
    }
    return ShootingFixture(rows("wire").map { row ->
        val i = row.getJSONObject("input"); val r = i.getJSONObject("response"); val e = row.getJSONObject("expected")
        ShootingWireVector(row.getString("id"), ShootingSettingRequest(i.getString("key"), i.getString("requestedValue")),
            i.getString("engine"), i.getInt("status"), r.opt("version"), r.opt("engine"), r.opt("key"), r.opt("requestedValue"),
            r.opt("writeEvidence"), r.opt("readbackEvidence"), r.setting(), e.getString("writeEvidence"), e.getString("readbackEvidence"))
    }, rows("ownership").map { row ->
        val i = row.getJSONObject("input"); val e = row.getJSONObject("expected")
        ShootingOwnerVector(i.getBoolean("sameOwner"), i.getBoolean("sameAttempt"), i.getBoolean("sameDraftRevision"),
            i.getBoolean("cancelled"), i.getBoolean("responseReceived"), i.getBoolean("readbackMatched"),
            e.getBoolean("canPublishCurrent"), e.getBoolean("canConsumeDraft"))
    }, rows("outcome").map { row ->
        val i = row.getJSONObject("input"); val e = row.getJSONObject("expected")
        ShootingOutcomeVector(i.getBoolean("dispatched"), i.getBoolean("responseReceived"), i.getString("readStatus"),
            i.getBoolean("readbackMatches"), e.getString("writeEvidence"), e.getString("readbackEvidence"))
    })
}
