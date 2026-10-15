package dev.openeos.control.ui

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Real Gradle loader. The offline typed adapter does not validate org.json or Android packaging. */
internal fun loadMediaDeleteFixture(): DeleteFixture {
    val bytes = requireNotNull(DeleteFixture::class.java.getResourceAsStream("/delete-outcome-vectors.json"))
        .use { it.readNBytes(512 * 1024 + 1) }
    require(bytes.size <= 512 * 1024)
    require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
        "f42c67bc2b80185ac2198d00a59a9188b4424a4e6fa21a0f9df69f26fef15a68")
    val root = JSONObject(bytes.toString(Charsets.UTF_8)); require(root.getInt("schemaVersion") == 1)
    fun rows(key: String) = root.getJSONArray(key).objects()
    return DeleteFixture(
        rows("eligibility").map { v -> val i = v.getJSONObject("input"); val e = v.getJSONObject("expected")
            DeleteEligibilityVector(v.getString("id"), i.getString("source"), i.getBoolean("loaded"), i.nullableBoolean("capability"), i.nullableBoolean("protected"), i.nullableBoolean("writable"), e.getBoolean("allowed"), e.getString("reason"), e.getBoolean("qualificationUnknown")) },
        rows("admission").map { v -> val i = v.getJSONObject("input"); val e = v.getJSONObject("expected")
            DeleteAdmissionVector(v.getString("id"), i.getString("openingOwner"), i.nullableString("currentOwner"), i.getBoolean("confirmed"), i.getBoolean("consumed"), i.getBoolean("busy"), e.getBoolean("allowed")) },
        rows("outcome").map { v -> val i = v.getJSONObject("input"); val e = v.getJSONObject("expected")
            DeleteOutcomeVector(v.getString("id"), i.getBoolean("started"), i.getBoolean("dispatched"), i.getBoolean("confirmedReceipt"), i.getBoolean("rejectedReceipt"), !i.isNull("skippedReason"), e.getString("status")) },
        rows("httpReceipt").map { v -> val i = v.getJSONObject("input"); val e = v.getJSONObject("expected")
            DeleteHttpVector(v.getString("id"), i.getInt("status"), i.getBoolean("responseComplete"), i.getBoolean("framingValid"), i.getInt("bodyBytes"), e.getString("status")) },
        rows("selection").map { v -> val i = v.getJSONObject("input"); val e = v.getJSONObject("expected")
            DeleteSelectionVector(v.getString("id"), i.getString("operationOwner"), i.nullableString("currentOwner"), i.getString("operationSelectionToken"), i.nullableString("currentSelectionToken"), i.getJSONArray("selectedIds").strings(), i.getJSONArray("confirmedIds").strings(), e.getJSONArray("selectedIds").strings()) },
        rows("roster").map { v -> val i = v.getJSONObject("input"); val e = v.getJSONObject("expected")
            DeleteRosterVector(v.getString("id"), i.getJSONArray("selectedIds").strings(), e.getBoolean("allowed"), e.getJSONArray("ids").strings()) },
        rows("batch").map { v -> val i = v.getJSONObject("input"); val e = v.getJSONObject("expected")
            DeleteBatchVector(v.getString("id"), i.getJSONArray("eligible").booleans(), i.getJSONArray("responses").strings(), if (i.isNull("cancelBeforeIndex")) null else i.getInt("cancelBeforeIndex"), e.getJSONArray("statuses").strings(), e.getJSONArray("dispatches").ints()) },
    )
}
private fun JSONObject.nullableBoolean(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)
private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
private fun JSONArray.strings() = (0 until length()).map(::getString)
private fun JSONArray.booleans() = (0 until length()).map(::getBoolean)
private fun JSONArray.ints() = (0 until length()).map(::getInt)
