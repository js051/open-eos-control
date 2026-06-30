package dev.openeos.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class CcapiClient(
    baseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun info(): CameraInfo = getJson("/ccapi/info").toCameraInfo()

    suspend fun status(): CameraStatus = getJson("/ccapi/status").toCameraStatus()

    suspend fun capabilities(): CameraCapabilities =
        getJson("/ccapi/capabilities").toCameraCapabilities()

    suspend fun setExposure(
        iso: String? = null,
        shutter: String? = null,
        aperture: String? = null,
    ): CameraStatus {
        val payload = JSONObject()
        iso?.let { payload.put("iso", it) }
        shutter?.let { payload.put("shutter", it) }
        aperture?.let { payload.put("aperture", it) }
        return patchJson("/ccapi/exposure", payload).toCameraStatus()
    }

    suspend fun setWhiteBalance(value: String): CameraStatus =
        patchJson("/ccapi/white-balance", JSONObject().put("white_balance", value)).toCameraStatus()

    suspend fun startRecording(): CameraStatus {
        postJson("/ccapi/record/start", JSONObject())
        return status()
    }

    suspend fun stopRecording(): CameraStatus {
        postJson("/ccapi/record/stop", JSONObject())
        return status()
    }

    suspend fun tapFocus(x: Double, y: Double): FocusResult {
        val payload = JSONObject().put("x", x).put("y", y)
        val json = postJson("/ccapi/focus/tap", payload)
        return FocusResult(
            ok = json.optBoolean("ok"),
            x = json.optDouble("x"),
            y = json.optDouble("y"),
        )
    }

    fun liveViewFrameUrl(cacheKey: Long): String =
        "$baseUrl/ccapi/liveview/frame?t=$cacheKey"

    private suspend fun getJson(path: String): JSONObject = requestJson(
        Request.Builder().url("$baseUrl$path").get().build(),
    )

    private suspend fun postJson(path: String, payload: JSONObject): JSONObject = requestJson(
        Request.Builder()
            .url("$baseUrl$path")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build(),
    )

    private suspend fun patchJson(path: String, payload: JSONObject): JSONObject = requestJson(
        Request.Builder()
            .url("$baseUrl$path")
            .patch(payload.toString().toRequestBody(jsonMediaType))
            .build(),
    )

    private suspend fun requestJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Camera request failed: HTTP ${response.code} $body")
            }
            JSONObject(body)
        }
    }
}

private fun JSONObject.toCameraInfo(): CameraInfo = CameraInfo(
    connected = optBoolean("connected"),
    model = optString("model", "Unknown camera"),
    serial = optString("serial", "unknown"),
    api = optString("api", "ccapi"),
)

private fun JSONObject.toCameraStatus(): CameraStatus {
    val battery = getJSONObject("battery")
    val media = getJSONObject("media")
    val exposure = getJSONObject("exposure")
    return CameraStatus(
        connected = optBoolean("connected"),
        batteryLevel = battery.optInt("level"),
        batteryStatus = battery.optString("status"),
        recording = optBoolean("recording"),
        mode = optString("mode"),
        mediaAvailable = media.optBoolean("available"),
        remainingMinutes = media.optInt("remaining_minutes"),
        exposure = ExposureState(
            iso = exposure.optString("iso"),
            shutter = exposure.optString("shutter"),
            aperture = exposure.optString("aperture"),
            whiteBalance = exposure.optString("white_balance"),
        ),
    )
}

private fun JSONObject.toCameraCapabilities(): CameraCapabilities = CameraCapabilities(
    iso = getJSONArray("iso").toStringList(),
    shutter = getJSONArray("shutter").toStringList(),
    aperture = getJSONArray("aperture").toStringList(),
    whiteBalance = getJSONArray("white_balance").toStringList(),
)

private fun org.json.JSONArray.toStringList(): List<String> =
    List(length()) { index -> getString(index) }
