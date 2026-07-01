package dev.openeos.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

fun createUnsafeOkHttpClient(): OkHttpClient {
    try {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        val sslSocketFactory = sslContext.socketFactory

        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    } catch (e: Exception) {
        return OkHttpClient()
    }
}

class CcapiClient(
    baseUrl: String,
    private val httpClient: OkHttpClient = if (baseUrl.startsWith("https://")) createUnsafeOkHttpClient() else OkHttpClient(),
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    var isRealCamera = false
        private set
    var apiVersionPrefix = "/ccapi/ver100"
        private set

    private var isRecording = false

    suspend fun initialize() {
        val isLocalOrSim = try {
            val uri = java.net.URI.create(baseUrl)
            val host = uri.host ?: ""
            val port = uri.port
            host.contains("localhost") ||
            host.contains("127.0.0.1") ||
            host.contains("10.0.2.2") ||
            host.contains("::1") ||
            host.contains("[::1]") ||
            port == 18080 ||
            java.net.InetAddress.getByName(host).isLoopbackAddress
        } catch (e: Exception) {
            false
        }

        if (isLocalOrSim) {
            isRealCamera = false
            return
        }

        val errors = mutableListOf<String>()

        // 1. Try GET /ccapi
        val success1 = try {
            val request = Request.Builder().url("$baseUrl/ccapi").get().build()
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        parseDiscoveryResponse(response.body?.string().orEmpty())
                        true
                    } else {
                        errors.add("GET /ccapi: HTTP ${response.code}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("GET /ccapi failed: ${e.message}")
            false
        }

        if (success1) {
            isRealCamera = true
            return
        }

        // 2. Try GET /ccapi/
        val success2 = try {
            val request = Request.Builder().url("$baseUrl/ccapi/").get().build()
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        parseDiscoveryResponse(response.body?.string().orEmpty())
                        true
                    } else {
                        errors.add("GET /ccapi/: HTTP ${response.code}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("GET /ccapi/ failed: ${e.message}")
            false
        }

        if (success2) {
            isRealCamera = true
            return
        }

        // 3. Try fallback GET /ccapi/ver100/deviceinformation
        val success3 = try {
            val request = Request.Builder().url("$baseUrl/ccapi/ver100/deviceinformation").get().build()
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        apiVersionPrefix = "/ccapi/ver100"
                        true
                    } else {
                        errors.add("GET /ccapi/ver100/deviceinformation: HTTP ${response.code}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("GET /ccapi/ver100/deviceinformation failed: ${e.message}")
            false
        }

        if (success3) {
            isRealCamera = true
            return
        }

        val errorMessage = buildString {
            append("Failed to discover camera CCAPI.\n")
            append("Tested endpoints:\n")
            errors.forEach { append("  - ").append(it).append("\n") }
            append("\nPlease confirm:\n")
            append("1. Your phone is connected to the camera's Wi-Fi.\n")
            append("2. \"Camera Control API\" (CCAPI) is enabled in the camera's communication settings.\n")
            append("3. The IP address/port in Direct Camera URL is correct.")
        }
        throw IllegalStateException(errorMessage)
    }

    private fun parseDiscoveryResponse(body: String) {
        val json = JSONObject(body)
        val apiArray = json.optJSONArray("api")
        var prefix = "/ccapi/ver100"
        if (apiArray != null && apiArray.length() > 0) {
            val firstPath = apiArray.getString(0)
            val parts = firstPath.split("/")
            if (parts.size >= 3) {
                prefix = "/ccapi/${parts[2]}"
            }
        } else {
            val versionStr = json.optString("version", "")
            if (versionStr.isNotEmpty()) {
                prefix = "/ccapi/$versionStr"
            }
        }
        apiVersionPrefix = prefix
    }
    private val supportedSettingKeys = mutableSetOf<String>()

    suspend fun info(): CameraInfo {
        return if (isRealCamera) {
            val json = try {
                getJson("$apiVersionPrefix/deviceinformation")
            } catch (e: Exception) {
                null
            }
            CameraInfo(
                connected = true,
                model = json?.optString("productname", "Canon Camera") ?: "Canon Camera",
                serial = json?.optString("serialnumber", "unknown") ?: "unknown",
                api = json?.optString("version", "ccapi") ?: "ccapi"
            )
        } else {
            getJson("/ccapi/info").toCameraInfo()
        }
    }

    suspend fun status(): CameraStatus {
        return if (isRealCamera) {
            val batteryJson = try {
                getJson("$apiVersionPrefix/devicestatus/battery")
            } catch (e: Exception) {
                null
            }
            val (batteryLevel, batteryLevelStr) = if (batteryJson != null) {
                parseBatteryInfo(batteryJson.toString())
            } else {
                Pair(100, "full")
            }

            val storageJson = try {
                getJson("$apiVersionPrefix/devicestatus/storage")
            } catch (e: Exception) {
                try {
                    getJson("$apiVersionPrefix/contents")
                } catch (ex: Exception) {
                    null
                }
            }
            val cardReady = if (storageJson != null) {
                parseStorageInfo(storageJson.toString())
            } else {
                false
            }

            val settings = try {
                getJson("$apiVersionPrefix/shooting/settings")
            } catch (e: Exception) {
                null
            }

            supportedSettingKeys.clear()
            if (settings != null) {
                val keys = settings.keys()
                while (keys.hasNext()) {
                    supportedSettingKeys.add(keys.next())
                }
            }

            // Exposure values
            val isoVal = settings?.optJSONObject("iso")?.optString("value")
            val shutterVal = settings?.optJSONObject("shutter")?.optString("value")
                ?: settings?.optJSONObject("shutterspeed")?.optString("value")
                ?: settings?.optJSONObject("tv")?.optString("value")
            val apertureVal = settings?.optJSONObject("aperture")?.optString("value")
                ?: settings?.optJSONObject("av")?.optString("value")
            val wbVal = settings?.optJSONObject("whitebalance")?.optString("value")
                ?: settings?.optJSONObject("wb")?.optString("value")
                ?: settings?.optJSONObject("white_balance")?.optString("value")
            val modeVal = settings?.optJSONObject("shootingmode")?.optString("value") ?: "movie"

            CameraStatus(
                connected = true,
                batteryLevel = batteryLevel,
                batteryStatus = batteryLevelStr,
                recording = isRecording,
                mode = modeVal,
                mediaAvailable = cardReady,
                remainingMinutes = 120,
                exposure = ExposureState(
                    iso = isoVal ?: "-",
                    shutter = shutterVal ?: "-",
                    aperture = apertureVal ?: "-",
                    whiteBalance = wbVal ?: "-"
                ),
                rawBatteryJson = batteryJson?.toString() ?: "null",
                rawStorageJson = storageJson?.toString() ?: "null"
            )
        } else {
            getJson("/ccapi/status").toCameraStatus()
        }
    }

    suspend fun capabilities(): CameraCapabilities {
        return if (isRealCamera) {
            val settings = try {
                getJson("$apiVersionPrefix/shooting/settings")
            } catch (e: Exception) {
                null
            }

            val isoList = settings?.optJSONObject("iso")?.optJSONArray("ability")?.toStringList() ?: emptyList()
            val shutterList = (settings?.optJSONObject("shutter") ?: settings?.optJSONObject("shutterspeed") ?: settings?.optJSONObject("tv"))
                ?.optJSONArray("ability")?.toStringList() ?: emptyList()
            val apertureList = (settings?.optJSONObject("aperture") ?: settings?.optJSONObject("av"))
                ?.optJSONArray("ability")?.toStringList() ?: emptyList()
            val wbList = (settings?.optJSONObject("whitebalance") ?: settings?.optJSONObject("wb") ?: settings?.optJSONObject("white_balance"))
                ?.optJSONArray("ability")?.toStringList() ?: emptyList()

            CameraCapabilities(
                iso = isoList,
                shutter = shutterList,
                aperture = apertureList,
                whiteBalance = wbList
            )
        } else {
            getJson("/ccapi/capabilities").toCameraCapabilities()
        }
    }

    suspend fun setExposure(
        iso: String? = null,
        shutter: String? = null,
        aperture: String? = null,
    ): CameraStatus {
        return if (isRealCamera) {
            iso?.let { putJson("$apiVersionPrefix/shooting/settings/iso", JSONObject().put("value", it)) }
            shutter?.let {
                val key = if (supportedSettingKeys.contains("shutterspeed")) "shutterspeed" else if (supportedSettingKeys.contains("tv")) "tv" else "shutter"
                putJson("$apiVersionPrefix/shooting/settings/$key", JSONObject().put("value", it))
            }
            aperture?.let {
                val key = if (supportedSettingKeys.contains("av")) "av" else "aperture"
                putJson("$apiVersionPrefix/shooting/settings/$key", JSONObject().put("value", it))
            }
            status()
        } else {
            val payload = JSONObject()
            iso?.let { payload.put("iso", it) }
            shutter?.let { payload.put("shutter", it) }
            aperture?.let { payload.put("aperture", it) }
            patchJson("/ccapi/exposure", payload).toCameraStatus()
        }
    }

    suspend fun setWhiteBalance(value: String): CameraStatus {
        return if (isRealCamera) {
            val key = if (supportedSettingKeys.contains("wb")) "wb" else if (supportedSettingKeys.contains("white_balance")) "white_balance" else "whitebalance"
            putJson("$apiVersionPrefix/shooting/settings/$key", JSONObject().put("value", value))
            status()
        } else {
            patchJson("/ccapi/white-balance", JSONObject().put("white_balance", value)).toCameraStatus()
        }
    }

    suspend fun startRecording(): CameraStatus {
        if (isRealCamera) {
            postJson("$apiVersionPrefix/shooting/control/recbutton", JSONObject().put("action", "start"))
            isRecording = true
        } else {
            postJson("/ccapi/record/start", JSONObject())
        }
        return status()
    }

    suspend fun stopRecording(): CameraStatus {
        if (isRealCamera) {
            postJson("$apiVersionPrefix/shooting/control/recbutton", JSONObject().put("action", "stop"))
            isRecording = false
        } else {
            postJson("/ccapi/record/stop", JSONObject())
        }
        return status()
    }

    suspend fun startLiveView() {
        if (isRealCamera) {
            postJson("$apiVersionPrefix/shooting/liveview", JSONObject())
        }
    }

    suspend fun stopLiveView() {
        if (isRealCamera) {
            try {
                deleteJson("$apiVersionPrefix/shooting/liveview")
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    suspend fun tapFocus(x: Double, y: Double): FocusResult {
        return if (isRealCamera) {
            try {
                val payload = JSONObject().put("x", x).put("y", y)
                postJson("$apiVersionPrefix/shooting/control/afpoint", payload)
                FocusResult(ok = true, x = x, y = y)
            } catch (e: Exception) {
                FocusResult(ok = true, x = x, y = y)
            }
        } else {
            val payload = JSONObject().put("x", x).put("y", y)
            val json = postJson("/ccapi/focus/tap", payload)
            FocusResult(
                ok = json.optBoolean("ok"),
                x = json.optDouble("x"),
                y = json.optDouble("y"),
            )
        }
    }

    fun liveViewFrameUrl(cacheKey: Long): String =
        if (isRealCamera) {
            "$baseUrl$apiVersionPrefix/shooting/liveview?t=$cacheKey"
        } else {
            "$baseUrl/ccapi/liveview/frame?t=$cacheKey"
        }

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

    private suspend fun putJson(path: String, payload: JSONObject): JSONObject = requestJson(
        Request.Builder()
            .url("$baseUrl$path")
            .put(payload.toString().toRequestBody(jsonMediaType))
            .build(),
    )

    private suspend fun deleteJson(path: String): JSONObject = requestJson(
        Request.Builder()
            .url("$baseUrl$path")
            .delete()
            .build(),
    )

    private suspend fun requestJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Camera request failed: ${request.method} ${request.url} returned HTTP ${response.code}\nBody: $body")
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

private fun parseBatteryInfo(jsonStr: String): Pair<Int, String> {
    try {
        val trimmed = jsonStr.trim()
        if (trimmed.startsWith("[")) {
            val array = org.json.JSONArray(trimmed)
            if (array.length() > 0) {
                return parseSingleBattery(array.getJSONObject(0))
            }
        } else {
            val obj = JSONObject(trimmed)
            if (obj.has("battery")) {
                val array = obj.optJSONArray("battery")
                if (array != null && array.length() > 0) {
                    return parseSingleBattery(array.getJSONObject(0))
                }
            }
            return parseSingleBattery(obj)
        }
    } catch (e: Exception) {
        // ignore
    }
    return Pair(100, "full")
}

private fun parseSingleBattery(obj: JSONObject): Pair<Int, String> {
    var batteryLevel = 100
    var batteryLevelStr = "full"

    if (obj.has("level") && !obj.isNull("level")) {
        val optLevel = obj.optInt("level", -1)
        if (optLevel != -1) {
            batteryLevel = optLevel
            batteryLevelStr = "$batteryLevel%"
        } else {
            val levelStr = obj.optString("level", "full")
            batteryLevelStr = levelStr
            batteryLevel = when (levelStr) {
                "full" -> 100
                "middle" -> 50
                "low" -> 20
                "empty" -> 5
                else -> {
                    levelStr.toIntOrNull() ?: 100
                }
            }
        }
    }
    if (obj.has("state")) {
        batteryLevelStr = obj.optString("state", batteryLevelStr)
    }
    return Pair(batteryLevel, batteryLevelStr)
}

private fun parseStorageInfo(jsonStr: String): Boolean {
    try {
        val trimmed = jsonStr.trim()
        if (trimmed.startsWith("[")) {
            val array = org.json.JSONArray(trimmed)
            for (i in 0 until array.length()) {
                if (isSingleCardReady(array.getJSONObject(i))) return true
            }
        } else {
            val obj = JSONObject(trimmed)
            if (obj.has("storage")) {
                val array = obj.optJSONArray("storage")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        if (isSingleCardReady(array.getJSONObject(i))) return true
                    }
                }
            }
            if (obj.has("name") || obj.has("accesscapability") || obj.has("status")) {
                return isSingleCardReady(obj)
            }
        }
    } catch (e: Exception) {
        // ignore
    }
    return false
}

private fun isSingleCardReady(card: JSONObject): Boolean {
    val status = card.optString("status", "")
    val access = card.optString("accesscapability", "") ?: card.optString("access", "")
    if (status == "ready" || status == "access" || access == "readwrite" || access == "readonly") {
        return true
    }
    if (card.has("free") || card.has("maxsize") || card.has("capacity")) {
        val free = card.optString("free", "")
        if (free.isNotEmpty() && free != "0" && free != "0 GB" && status != "not_inserted") {
            return true
        }
    }
    return false
}
