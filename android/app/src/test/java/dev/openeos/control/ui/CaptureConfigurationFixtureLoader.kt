package dev.openeos.control.ui

import dev.openeos.control.data.*
import java.security.MessageDigest
import org.json.JSONObject

/** Real Gradle resource loader; the pure-JVM typed adapter does not execute org.json or packaging. */
internal fun loadCaptureConfigurationFixture(): CaptureConfigurationFixture {
    val bytes = requireNotNull(CaptureConfigurationFixture::class.java.getResourceAsStream("/capture-configuration-vectors.json"))
        .use { it.readNBytes(256 * 1024 + 1) }
    require(bytes.size <= 256 * 1024)
    require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
        "7a3496afc0e9f0523399a41f251d1e243f59318866549f37aeb29e6733a01aa5")
    val root = JSONObject(bytes.toString(Charsets.UTF_8)); require(root.getInt("version") == 1)
    fun rows(key: String): List<JSONObject> { val array = root.getJSONArray(key); return (0 until array.length()).map(array::getJSONObject) }
    return CaptureConfigurationFixture(
        rows("receipt").map { v -> val i = v.getJSONObject("input"); val r = i.optJSONObject("receipt")
            val field = (i.opt("field") as? String)?.let(CameraFileNamingField::fromWireName)
            val request = runCatching { CaptureConfigurationRequest(if (i.getString("operation") == "create-directory")
                CaptureConfigurationOperation.CREATE_DIRECTORY else CaptureConfigurationOperation.SET_FILE_NAMING, i.getString("requestedValue"), field) }.getOrNull()
            CaptureReceiptVector(v.getString("id"), request, i.getInt("status"), i.opt("code"), i.opt("engine"), i.opt("feature"),
                r?.opt("version"), r?.opt("operation"), r?.opt("stage"), r?.opt("requestedValue"), r?.opt("acknowledgedValue"),
                r?.has("field") == true, r?.opt("field"), v.getBoolean("valid")) },
        rows("outcome").map { v -> val i = v.getJSONObject("input"); val e = v.getJSONObject("expected")
            CaptureOutcomeVector(v.getString("id"), i.getString("operation") == "create-directory", i.getBoolean("dispatched"),
                i.getBoolean("validAcknowledgment"), i.getString("readStatus"), i.getBoolean("readbackMatches"),
                e.getString("writeEvidence"), e.getString("readbackEvidence")) },
        rows("ownership").map { v -> val i = v.getJSONObject("input"); val e = v.getJSONObject("expected")
            CaptureOwnershipVector(v.getString("id"), i.getBoolean("sameOwner"), i.getBoolean("sameAttempt"), i.getBoolean("retired"),
                i.getBoolean("sameDraftRevision"), i.getBoolean("validAcknowledgment"), i.getBoolean("readbackMatched"),
                i.getBoolean("cancelled"), e.getBoolean("canPublishCurrent"), e.getBoolean("canConsumeDraft")) },
        rows("recovery").map { v -> val i = v.getJSONObject("input")
            CaptureRecoveryVector(v.getString("id"), i.getBoolean("sameOwner"), i.getBoolean("writeActive"), i.getBoolean("readActive"),
                i.getBoolean("hasRecord"), v.getBoolean("allowed"), v.getInt("writes")) },
    )
}
