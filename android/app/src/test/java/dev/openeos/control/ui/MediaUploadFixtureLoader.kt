package dev.openeos.control.ui

import dev.openeos.control.data.StrictMediaJson
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Formal Gradle path. Offline typed adapters do not validate this platform JSON decoder. */
internal fun loadMediaUploadFixture(): MediaUploadFixture {
    val bytes = requireNotNull(MediaUploadFixture::class.java.getResourceAsStream("/upload-outcome-vectors.json"))
        .use { it.readNBytes(128 * 1024 + 1) }
    require(bytes.size <= 128 * 1024)
    require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
        "dcb90087f58db32166fa86291486190a1aca1ea368ff7e74cf3342a601c8b1fd")
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    require(root.getInt("schemaVersion") == 1 && root.getString("contract") == "media-upload-outcome-v1")
    return MediaUploadFixture(
        root.getJSONArray("outcomeCases").objects().map { UploadOutcomeVector(it.getString("id"), it.getBoolean("dispatchStarted"), it.getBoolean("verifiedReceiptForAttempt"), it.getBoolean("cancellationRequested"), it.getString("listingObservation"), it.getString("expectedOutcome")) },
        root.getJSONArray("admissionCases").objects().map { UploadAdmissionVector(it.getString("id"), it.getString("openingOwner"), it.nullableString("currentOwner"), it.getString("openingToken"), it.nullableString("currentToken"), it.getBoolean("singleUseAvailable"), it.getBoolean("featureSupported"), it.getBoolean("mediaBusy"), it.getBoolean("sourceSelected"), it.getBoolean("expectedAdmit")) },
        root.getJSONArray("publicationCases").objects().map { UploadPublicationVector(it.getString("id"), it.getString("stage"), it.getString("attemptOwner"), it.nullableString("currentOwner"), it.getString("attemptToken"), it.nullableString("currentToken"), it.getBoolean("expectedPublish")) },
        root.getJSONArray("receiptCases").objects().map { UploadReceiptVector(it.getString("id"), it.getInt("statusCode"), it.getBoolean("boundToAttempt"), it.getString("expectedFilename"), it.getLong("expectedSizeBytes"), it.getString("rawResponseJSON"), it.getBoolean("expectedVerified")) },
        root.getJSONArray("journeyCases").objects().map { UploadJourneyVector(it.getString("id"), it.getJSONArray("events").strings(), it.getInt("expectedDispatches"), it.getString("expectedOutcome"), it.getBoolean("mayPublishConfirmationForCurrentOwner"), it.getInt("automaticReplayCount")) },
    )
}
internal fun decodeUploadFixtureReceipt(raw: String): Map<String, Any?> {
    StrictMediaJson(raw).validate()
    val value = JSONObject(raw)
    return listOf("id", "name", "kind", "sizeBytes").associateWith { if (value.isNull(it)) null else value.opt(it) }
}
private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
