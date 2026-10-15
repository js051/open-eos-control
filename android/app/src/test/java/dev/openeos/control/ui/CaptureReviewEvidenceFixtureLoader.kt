package dev.openeos.control.ui

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Formal Gradle resource loader; the offline JVM harness explicitly does not execute it. */
internal fun loadCaptureReviewEvidenceFixture(): CaptureReviewEvidenceFixture {
    val bytes = requireNotNull(CaptureReviewEvidenceFixture::class.java.getResourceAsStream("/review-evidence-vectors.json"))
        .use { it.readNBytes(64 * 1024 + 1) }
    require(bytes.size == 25_745)
    require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
        "aab83ace169997c2f9788ea45f2863513f0a0d99e3211143c0e60475eb7ec7b1")
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    return CaptureReviewEvidenceFixture(
        root.getInt("schemaVersion"), root.getJSONArray("previousIds").strings().toSet(),
        root.getJSONArray("retryDelaysMillis").let { array -> (0 until array.length()).map(array::getLong) },
        root.getInt("maximumReads"),
        root.getJSONArray("cases").objects(106).map { case ->
            CaptureReviewEvidenceVector(case.getString("id"), case.getString("intent"),
                case.getJSONArray("reads").strings(), case.getJSONObject("expected").expected())
        },
        root.getJSONArray("retryCases").objects(2).map { case ->
            CaptureReviewEvidenceRetryVector(case.getString("id"), case.getJSONArray("first").strings(),
                case.getJSONArray("second").strings(), case.getJSONObject("expected").expected())
        },
        root.getJSONArray("retirementCases").objects(4).map { case ->
            CaptureReviewEvidenceRetirementVector(case.getString("id"), case.getInt("completedReads"),
                case.getBoolean("publish"), case.getBoolean("preservePrevious"))
        },
    )
}
private fun JSONObject.expected() = CaptureReviewEvidenceExpected(
    getString("observation"), getInt("successfulReads"), getInt("failedReads"),
    nullableString("lastCompletedRead"), nullableString("candidateId"),
)
private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONArray.objects(count: Int): List<JSONObject> {
    require(length() == count)
    return (0 until length()).map(::getJSONObject)
}
private fun JSONArray.strings(): List<String> {
    require(length() <= 4)
    return (0 until length()).map(::getString)
}
