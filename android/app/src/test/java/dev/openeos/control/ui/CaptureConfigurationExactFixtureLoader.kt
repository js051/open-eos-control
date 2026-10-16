package dev.openeos.control.ui

import dev.openeos.control.data.*
import java.security.MessageDigest
import org.json.JSONObject

/** Full Gradle loader is not replaced by the offline typed adapter for delivery claims. */
internal fun loadCaptureExactFixture(): CaptureExactFixture {
    val bytes = requireNotNull(CaptureExactFixture::class.java.getResourceAsStream("/exact-value-vectors.json"))
        .use { it.readNBytes(256 * 1024 + 1) }
    require(bytes.size <= 256 * 1024)
    require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
        "0ce969f4faaf795daae1fcc680807f1841313ef487ad3fefb1f8a682a92263c7")
    val root = JSONObject(bytes.toString(Charsets.UTF_8)); require(root.getInt("version") == 1)
    fun rows(key: String): List<JSONObject> { val array = root.getJSONArray(key); return (0 until array.length()).map(array::getJSONObject) }
    fun range(value: JSONObject) = CameraIntegerRange(value.getInt("minimum"), value.getInt("maximum"), value.getInt("step"))
    return CaptureExactFixture(
        rows("receipt").map { v -> val i = v.getJSONObject("input"); val r = i.optJSONObject("receipt")
            val field = (i.opt("field") as? String)?.let(CameraFileNamingField::fromWireName)
            val request = runCatching { CaptureConfigurationRequest(if (i.getString("operation") == "create-directory")
                CaptureConfigurationOperation.CREATE_DIRECTORY else CaptureConfigurationOperation.SET_FILE_NAMING, i.getString("requestedValue"), field) }.getOrNull()
            CaptureReceiptVector(v.getString("id"), request, i.getInt("status"), i.opt("code"), i.opt("engine"), i.opt("feature"),
                r?.opt("version"), r?.opt("operation"), r?.opt("stage"), r?.opt("requestedValue"), r?.opt("acknowledgedValue"),
                r?.has("field") == true, r?.opt("field"), v.getBoolean("valid")) },
        rows("naming").map { v -> val n = v.getJSONObject("value"); val modes = n.getJSONArray("stillFilenameModeOptions")
            CaptureNamingVector(v.getString("id"), CameraFileNaming(n.getString("stillFilenameMode"),
                (0 until modes.length()).map(modes::getString), n.getString("stillUserSetting1"), n.getString("stillUserSetting2"),
                n.getString("movieIndex"), n.getInt("movieReelNumber"), range(n.getJSONObject("movieReelRange")),
                n.getInt("movieClipNumber"), range(n.getJSONObject("movieClipRange")), n.getString("movieUserDefined")), v.getBoolean("valid")) },
        rows("directorySelection").map { v -> val d = v.getJSONObject("value"); val values = d.getJSONArray("ability")
            CaptureDirectoryVector(v.getString("id"), d.opt("value"), (0 until values.length()).map { values.opt(it) }, v.getBoolean("valid")) },
    )
}
