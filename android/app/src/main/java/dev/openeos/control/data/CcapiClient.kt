package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

private data class CcapiApiOperation(
    val method: String,
    val path: String,
)

private class CcapiHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

class CcapiClient(
    baseUrl: String,
    httpClient: OkHttpClient? = null,
    private val treatAsSimulator: Boolean? = null,
    username: String = "",
    password: String = "",
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val httpClient = (httpClient ?: OkHttpClient()).newBuilder().apply {
        if (username.isNotBlank()) {
            val authorization = Credentials.basic(username, password)
            addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", authorization)
                        .build()
                )
            }
        }
    }.build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    var isRealCamera = false
        private set
    var apiVersionPrefix = "/ccapi/ver100"
        private set

    private var apiVersionPrefixes = listOf("/ccapi/ver100")
    private var isRecording: Boolean? = null
    private val settingPathsByKey = mutableMapOf<String, String>()
    private val settingValuesByKey = mutableMapOf<String, Set<String>>()
    private val apiOperations = linkedSetOf<CcapiApiOperation>()
    private val observedFeatures = mutableSetOf<CameraFeature>()
    private var enforceAdvertisedOperations = false
    private var settingsLoaded = false
    private var liveViewSizeControlSupported = true
    private var activeLiveViewSize = LiveViewSize.MEDIUM

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

        if (treatAsSimulator ?: isLocalOrSim) {
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

        // 3. Try fallback device information endpoints.
        val fallbackVersions = listOf("/ccapi/ver110", "/ccapi/ver100")
        val success3 = fallbackVersions.firstOrNull { prefix ->
            try {
                val request = Request.Builder().url("$baseUrl$prefix/deviceinformation").get().build()
                withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            apiVersionPrefixes = listOf(prefix)
                            apiVersionPrefix = prefix
                            true
                        } else {
                            errors.add("GET $prefix/deviceinformation: HTTP ${response.code}")
                            false
                        }
                    }
                }
            } catch (e: Exception) {
                errors.add("GET $prefix/deviceinformation failed: ${e.message}")
                false
            }
        } != null

        if (success3) {
            isRealCamera = true
            enforceAdvertisedOperations = true
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
        val versions = linkedSetOf<String>()
        apiOperations.clear()
        enforceAdvertisedOperations = true
        val apiArray = json.optJSONArray("api")
        if (apiArray != null && apiArray.length() > 0) {
            for (index in 0 until apiArray.length()) {
                val path = apiArray.optString(index)
                extractApiVersion(path)?.let { versions.add(it) }
            }
        }

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.matches(Regex("ver\\d+"))) {
                versions.add(key)
                recordApiOperations(key, json.optJSONArray(key))
            }
        }

        val versionStr = json.optString("version", "")
        if (versionStr.matches(Regex("ver\\d+"))) {
            versions.add(versionStr)
        }

        if (versions.isEmpty()) {
            versions.add("ver100")
        }

        apiVersionPrefixes = versions.map { "/ccapi/$it" }.sortedByDescending { it.apiVersionNumber() }
        apiVersionPrefix = if (apiVersionPrefixes.contains("/ccapi/ver100")) "/ccapi/ver100" else apiVersionPrefixes.first()
    }

    private fun recordApiOperations(version: String, entries: org.json.JSONArray?) {
        if (entries == null) return
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val path = entry.optString("path", "").trim()
            if (path.isBlank()) continue
            val fullPath = if (path.startsWith("/ccapi/")) {
                path
            } else {
                "/ccapi/$version/${path.trimStart('/')}"
            }
            CCAPI_HTTP_METHODS.forEach { method ->
                if (entry.has(method.lowercase()) && entry.methodIsSupported(method.lowercase())) {
                    apiOperations.add(CcapiApiOperation(method, fullPath))
                }
            }
        }
    }

    private fun JSONObject.methodIsSupported(key: String): Boolean {
        val value = opt(key)
        return when (value) {
            null, JSONObject.NULL -> false
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.isNotBlank() && value.lowercase() !in setOf("false", "no", "none", "unsupported")
            else -> true
        }
    }

    private fun extractApiVersion(path: String): String? =
        Regex("""/ccapi/(ver\d+)(/|$)""").find(path)?.groupValues?.get(1)

    private fun String.apiVersionNumber(): Int =
        substringAfterLast("ver").toIntOrNull() ?: 0

    suspend fun info(): CameraInfo {
        return if (isRealCamera) {
            val json = getFirstJson(versionedPaths("/deviceinformation"))
            if (json != null) observedFeatures.add(CameraFeature.CAMERA_IDENTITY)
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
            val batteryJson = getFirstJson(
                versionedPaths("/devicestatus/batterylist") +
                    versionedPaths("/devicestatus/battery")
            )
            if (batteryJson != null) observedFeatures.add(CameraFeature.BATTERY_STATUS)
            val batteryInfo = batteryJson?.let { parseBatteryInfo(it.toString()) }
            val batteryLevel = batteryInfo?.first
            val batteryLevelStr = batteryInfo?.second ?: "unknown"

            val storageJson = getFirstJson(
                versionedPaths("/devicestatus/storage") +
                    versionedPaths("/devicestatus/currentstorage") +
                    versionedPaths("/contents")
            )
            val cardReady = if (storageJson != null) {
                observedFeatures.add(CameraFeature.STORAGE_STATUS)
                parseStorageInfo(storageJson.toString())
            } else {
                null
            }

            val settings = loadShootingSettings()

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
            val modeVal = settings?.optJSONObject("shootingmode")?.optString("value") ?: "unknown"

            CameraStatus(
                connected = true,
                batteryLevel = batteryLevel,
                batteryStatus = batteryLevelStr,
                recording = isRecording,
                mode = modeVal,
                mediaAvailable = cardReady,
                remainingMinutes = null,
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
            val settings = loadShootingSettings()

            val isoList = writableSetting(settings, listOf("iso"))
                ?.optJSONArray("ability")?.toStringList().orEmpty()
            val shutterList = writableSetting(settings, listOf("tv", "shutterspeed", "shutter"))
                ?.optJSONArray("ability")?.toStringList().orEmpty()
            val apertureList = writableSetting(settings, listOf("av", "aperture"))
                ?.optJSONArray("ability")?.toStringList().orEmpty()
            val wbList = writableSetting(settings, listOf("wb", "whitebalance", "white_balance"))
                ?.optJSONArray("ability")?.toStringList().orEmpty()
            val advancedSettings = settings
                ?.toAdvancedSettingControls(settingPathsByKey.keys)
                .orEmpty()
            val supportedFeatures = observedFeatures.toMutableSet()
            if (isoList.isNotEmpty() || shutterList.isNotEmpty() || apertureList.isNotEmpty()) {
                supportedFeatures.add(CameraFeature.EXPOSURE_CONTROL)
            }
            if (wbList.isNotEmpty()) {
                supportedFeatures.add(CameraFeature.WHITE_BALANCE_CONTROL)
            }
            if (advancedSettings.isNotEmpty()) supportedFeatures.add(CameraFeature.ADVANCED_SETTINGS)
            if (supportsCompleteLiveView() || CameraFeature.LIVE_VIEW in observedFeatures) {
                supportedFeatures.add(CameraFeature.LIVE_VIEW)
                supportedFeatures.add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
            }
            if (recordingOperation() != null) {
                supportedFeatures.add(CameraFeature.VIDEO_RECORDING)
            }
            if (
                directShutterOperation() != null ||
                manualShutterOperation() != null
            ) {
                supportedFeatures.add(CameraFeature.STILL_CAPTURE)
            }
            if (manualShutterOperation() != null) {
                supportedFeatures.add(CameraFeature.SHUTTER_HALF_PRESS)
            }
            if (tapFocusOperation() != null) {
                supportedFeatures.add(CameraFeature.TAP_FOCUS)
            }
            if (supportsApi("GET", "/contents")) {
                supportedFeatures.add(CameraFeature.MEDIA_BROWSER)
                supportedFeatures.add(CameraFeature.MEDIA_DOWNLOAD)
            }

            val liveViewCapabilities = LiveViewCapabilities.ccapiNetwork().let { capabilities ->
                if (liveViewSizeControlSupported) {
                    capabilities
                } else {
                    capabilities.copy(
                        sizes = listOf(activeLiveViewSize),
                        defaultSize = activeLiveViewSize,
                    )
                }
            }
            CameraCapabilities(
                iso = isoList,
                shutter = shutterList,
                aperture = apertureList,
                whiteBalance = wbList,
                advancedSettings = advancedSettings,
                matrix = CapabilityMatrix.ccapiNetwork(supportedFeatures),
                liveView = liveViewCapabilities,
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
            iso?.let { putSettingValue(listOf("iso"), it) }
            shutter?.let {
                putSettingValue(listOf("tv", "shutterspeed", "shutter"), it)
            }
            aperture?.let {
                putSettingValue(listOf("av", "aperture"), it)
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
            putSettingValue(listOf("wb", "whitebalance", "white_balance"), value)
            status()
        } else {
            patchJson("/ccapi/white-balance", JSONObject().put("white_balance", value)).toCameraStatus()
        }
    }

    suspend fun setSetting(key: String, value: String): CameraStatus {
        return if (isRealCamera) {
            putSettingValue(listOf(key), value)
            status()
        } else {
            status()
        }
    }

    suspend fun startRecording(): CameraStatus {
        if (isRealCamera) {
            val operation = recordingOperation()
            if (enforceAdvertisedOperations && operation == null) {
                error("Camera did not advertise movie recording control.")
            }
            commandOk(
                pathSuffix = "/shooting/control/recbutton",
                payload = JSONObject().put("action", "start"),
                operation = operation,
            )
            isRecording = true
        } else {
            postJson("/ccapi/record/start", JSONObject())
        }
        return status()
    }

    suspend fun stopRecording(): CameraStatus {
        if (isRealCamera) {
            val operation = recordingOperation()
            if (enforceAdvertisedOperations && operation == null) {
                error("Camera did not advertise movie recording control.")
            }
            commandOk(
                pathSuffix = "/shooting/control/recbutton",
                payload = JSONObject().put("action", "stop"),
                operation = operation,
            )
            isRecording = false
        } else {
            postJson("/ccapi/record/stop", JSONObject())
        }
        return status()
    }

    suspend fun captureStill(): CameraStatus {
        if (isRealCamera) {
            val directOperation = directShutterOperation()
            val manualOperation = manualShutterOperation()
            if (enforceAdvertisedOperations && directOperation == null && manualOperation == null) {
                error("Camera did not advertise a supported still-capture operation.")
            }
            if (directOperation != null || manualOperation == null) {
                commandOk(
                    pathSuffix = "/shooting/control/shutterbutton",
                    payload = JSONObject().put("af", true),
                    operation = directOperation,
                )
            } else {
                withGuaranteedRelease(
                    press = {
                        commandOk(
                            pathSuffix = "/shooting/control/shutterbutton/manual",
                            payload = JSONObject().put("af", true).put("action", "full_press"),
                            operation = manualOperation,
                        )
                    },
                    release = {
                        commandOk(
                            pathSuffix = "/shooting/control/shutterbutton/manual",
                            payload = JSONObject().put("af", false).put("action", "release"),
                            operation = manualOperation,
                        )
                    },
                )
            }
            observedFeatures.add(CameraFeature.STILL_CAPTURE)
        } else {
            postJson("/ccapi/capture/still", JSONObject().put("af", true))
        }
        return status()
    }

    suspend fun halfPressShutter(): CameraStatus {
        if (isRealCamera) {
            val operation = manualShutterOperation()
            if (enforceAdvertisedOperations && operation == null) {
                error("Camera did not advertise manual shutter control.")
            }
            withGuaranteedRelease(
                press = {
                    commandOk(
                        pathSuffix = "/shooting/control/shutterbutton/manual",
                        payload = JSONObject().put("af", true).put("action", "half_press"),
                        operation = operation,
                    )
                },
                release = {
                    commandOk(
                        pathSuffix = "/shooting/control/shutterbutton/manual",
                        payload = JSONObject().put("af", false).put("action", "release"),
                        operation = operation,
                    )
                },
                afterPress = { delay(HALF_PRESS_DURATION_MILLIS) },
            )
            observedFeatures.add(CameraFeature.SHUTTER_HALF_PRESS)
        } else {
            withGuaranteedRelease(
                press = { postJson("/ccapi/shutter/half-press", JSONObject()) },
                release = { postJson("/ccapi/shutter/release", JSONObject()) },
                afterPress = { delay(HALF_PRESS_DURATION_MILLIS) },
            )
        }
        return status()
    }

    suspend fun listMedia(): List<CameraMediaItem> =
        if (isRealCamera) listRealMedia() else listSimulatorMedia()

    suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaDownloadResult {
        val paths = if (isRealCamera) {
            val path = normalizeCameraResource(item.id).substringBefore('?')
            listOf(path, "$path?kind=main", "$path?type=main")
        } else {
            val encodedId = URLEncoder.encode(item.id, StandardCharsets.UTF_8.name()).replace("+", "%20")
            listOf("/ccapi/media/$encodedId")
        }
        val result = requestMediaFile(paths, item, destination, onProgress)
        observedFeatures.add(CameraFeature.MEDIA_DOWNLOAD)
        return result
    }

    suspend fun startLiveView(request: LiveViewRequest = LiveViewRequest()) {
        if (isRealCamera) {
            if (enforceAdvertisedOperations && !supportsCompleteLiveView()) {
                error("Camera did not advertise a complete Live View start, frame, and stop lifecycle.")
            }
            val path = apiPath("POST", "/shooting/liveview")
            val requestedPayload = JSONObject()
                .put("cameradisplay", "on")
                .put("liveviewsize", request.size.ccapiValue)
            try {
                postOk(path, requestedPayload)
                liveViewSizeControlSupported = true
            } catch (exception: CcapiHttpException) {
                if (exception.statusCode != 400) throw exception
                postOk(path, JSONObject().put("cameradisplay", "on"))
                liveViewSizeControlSupported = false
            }
            activeLiveViewSize = request.size
            observedFeatures.add(CameraFeature.LIVE_VIEW)
            observedFeatures.add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
        }
    }

    suspend fun stopLiveView() {
        if (isRealCamera) {
            if (enforceAdvertisedOperations && !supportsApi("DELETE", "/shooting/liveview")) return
            try {
                deleteOk(apiPath("DELETE", "/shooting/liveview"))
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    suspend fun tapFocus(x: Double, y: Double): FocusResult {
        return if (isRealCamera) {
            val operation = tapFocusOperation()
            if (enforceAdvertisedOperations && operation == null) {
                error("Camera did not advertise coordinate Tap AF control.")
            }
            val payload = JSONObject().put("x", x).put("y", y)
            commandOk("/shooting/control/afpoint", payload, operation)
            FocusResult(ok = true, x = x, y = y)
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

    fun liveViewFrameUrl(cacheKey: Long, request: LiveViewRequest = LiveViewRequest()): String =
        liveViewFrameUrls(cacheKey, request).first()

    suspend fun liveViewFrame(cacheKey: Long, request: LiveViewRequest = LiveViewRequest()): LiveViewFrame {
        val errors = mutableListOf<String>()

        liveViewFrameUrls(cacheKey, request).forEach { sourceUrl ->
            val request = Request.Builder()
                .url(sourceUrl)
                .get()
                .header("Accept", "multipart/x-mixed-replace,image/jpeg,image/*,*/*")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Connection", "close")
                .build()

            try {
                return requestLiveViewFrame(request, sourceUrl)
            } catch (exception: Exception) {
                errors.add("$sourceUrl\n${exception.javaClass.simpleName}: ${exception.message ?: "Unknown error"}")
            }
        }

        error(
            "Live view frame failed on all candidate endpoints:\n" +
                errors.joinToString(separator = "\n\n") { "- $it" }
        )
    }

    private fun liveViewFrameUrls(cacheKey: Long, request: LiveViewRequest): List<String> =
        if (isRealCamera) {
            when (request.source) {
                LiveViewSource.AUTO,
                LiveViewSource.CCAPI_JPEG_POLLING -> liveViewFramePaths().map { "$baseUrl$it" }

                LiveViewSource.CCAPI_RTP -> error("CCAPI RTP live view is planned but not implemented by the JPEG frame reader yet.")

                else -> error("${request.source.label} is not available through the CCAPI network backend.")
            }.map { it.withCacheBust(cacheKey) }
        } else {
            listOf("$baseUrl/ccapi/liveview/frame".withCacheBust(cacheKey))
        }

    private fun String.withCacheBust(cacheKey: Long): String {
        val separator = if (contains("?")) "&" else "?"
        return "$this${separator}t=$cacheKey"
    }

    private fun versionedPaths(pathSuffix: String): List<String> =
        apiVersionPrefixes.map { "$it$pathSuffix" }

    private fun supportsApi(method: String, pathSuffix: String): Boolean =
        apiOperations.any { it.method == method && it.path.endsWith(pathSuffix) }

    private fun advertisedApiPaths(method: String, pathSuffix: String): List<String> =
        apiVersionPrefixes.mapNotNull { prefix ->
            apiOperations.firstOrNull {
                it.method == method && it.path.startsWith(prefix) && it.path.endsWith(pathSuffix)
            }?.path
        }.distinct()

    private fun apiOperation(method: String, pathSuffix: String): CcapiApiOperation? {
        val matching = apiOperations.filter { it.method == method && it.path.endsWith(pathSuffix) }
        return matching.firstOrNull { it.path.startsWith(apiVersionPrefix) }
            ?: matching.maxByOrNull { it.path.substringBefore(pathSuffix).apiVersionNumber() }
    }

    private fun directShutterOperation(): CcapiApiOperation? =
        apiOperation("POST", "/shooting/control/shutterbutton")

    private fun manualShutterOperation(): CcapiApiOperation? =
        apiOperation("PUT", "/shooting/control/shutterbutton/manual")
            ?: apiOperation("POST", "/shooting/control/shutterbutton/manual")

    private fun recordingOperation(): CcapiApiOperation? =
        apiOperation("POST", "/shooting/control/recbutton")
            ?: apiOperation("PUT", "/shooting/control/recbutton")

    private fun tapFocusOperation(): CcapiApiOperation? =
        apiOperation("PUT", "/shooting/control/afpoint")
            ?: apiOperation("POST", "/shooting/control/afpoint")

    private fun liveViewFramePaths(): List<String> {
        if (!enforceAdvertisedOperations) {
            return listOf(
                apiPath("GET", "/shooting/liveview/flip"),
                "${apiPath("GET", "/shooting/liveview/flipdetail")}?kind=image",
                apiPath("GET", "/shooting/liveview"),
            )
        }
        return buildList {
            apiOperation("GET", "/shooting/liveview/flip")?.let { add(it.path) }
            apiOperation("GET", "/shooting/liveview/flipdetail")?.let { add("${it.path}?kind=image") }
            apiOperation("GET", "/shooting/liveview")?.let { add(it.path) }
        }
    }

    private fun supportsCompleteLiveView(): Boolean =
        supportsApi("POST", "/shooting/liveview") &&
            supportsApi("DELETE", "/shooting/liveview") &&
            liveViewFramePaths().isNotEmpty()

    private suspend fun commandOk(
        pathSuffix: String,
        payload: JSONObject,
        operation: CcapiApiOperation? = null,
    ) {
        val selected = operation ?: if (!enforceAdvertisedOperations) {
            CcapiApiOperation(method = "POST", path = "$apiVersionPrefix$pathSuffix")
        } else {
            error("Camera did not advertise a supported command for $pathSuffix.")
        }
        val requestBody = payload.toString().toRequestBody(jsonMediaType)
        val builder = Request.Builder().url("$baseUrl${selected.path}")
        val request = when (selected.method) {
            "PUT" -> builder.put(requestBody).build()
            "POST" -> builder.post(requestBody).build()
            else -> error("Unsupported CCAPI command method ${selected.method} for ${selected.path}")
        }
        requestOk(request)
    }

    private suspend fun withGuaranteedRelease(
        press: suspend () -> Unit,
        release: suspend () -> Unit,
        afterPress: suspend () -> Unit = {},
    ) {
        var primaryFailure: Throwable? = null
        try {
            press()
            afterPress()
        } catch (exception: Throwable) {
            primaryFailure = exception
            throw exception
        } finally {
            try {
                withContext(NonCancellable) { release() }
            } catch (releaseFailure: Throwable) {
                primaryFailure?.addSuppressed(releaseFailure) ?: throw releaseFailure
            }
        }
    }

    private fun apiPath(method: String, pathSuffix: String): String {
        val matching = apiOperations.filter { it.method == method && it.path.endsWith(pathSuffix) }
        return matching.firstOrNull { it.path.startsWith(apiVersionPrefix) }?.path
            ?: matching.maxByOrNull { it.path.substringBefore(pathSuffix).apiVersionNumber() }?.path
            ?: "$apiVersionPrefix$pathSuffix"
    }

    private suspend fun listSimulatorMedia(): List<CameraMediaItem> {
        val items = getJson("/ccapi/media").optJSONArray("items") ?: return emptyList()
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            CameraMediaItem(
                id = item.getString("id"),
                name = item.getString("name"),
                kind = item.optString("kind", "other"),
                sizeBytes = item.optLong("size_bytes").takeIf { item.has("size_bytes") },
                captureTime = item.optString("capture_time").takeIf { it.isNotBlank() },
            )
        }
    }

    private suspend fun listRealMedia(): List<CameraMediaItem> {
        val rootPath = apiPath("GET", "/contents")
        val pending = ArrayDeque<Pair<String, Int>>()
        val visited = mutableSetOf<String>()
        val mediaPaths = linkedSetOf<String>()
        pending.add(rootPath to 0)

        while (pending.isNotEmpty() && mediaPaths.size < MAX_MEDIA_ITEMS) {
            val (container, depth) = pending.removeFirst()
            val normalizedContainer = normalizeCameraResource(container).substringBefore('?')
            if (!visited.add(normalizedContainer) || depth > MAX_MEDIA_TREE_DEPTH) continue

            listContentPaths(normalizedContainer).forEach { rawPath ->
                val path = normalizeCameraResource(rawPath).substringBefore('?')
                if (path.isMediaFilePath()) {
                    mediaPaths.add(path)
                } else if (path !in visited) {
                    pending.add(path to depth + 1)
                }
            }
        }

        if (mediaPaths.isNotEmpty()) {
            observedFeatures.add(CameraFeature.MEDIA_BROWSER)
            observedFeatures.add(CameraFeature.MEDIA_DOWNLOAD)
        }
        return mediaPaths.take(MAX_MEDIA_ITEMS).map { path ->
            CameraMediaItem(
                id = path,
                name = path.substringAfterLast('/'),
                kind = path.mediaKind(),
            )
        }
    }

    private suspend fun listContentPaths(containerPath: String): List<String> {
        val pageInfo = getFirstJson(
            listOf(
                "$containerPath?kind=number",
                "$containerPath?type=all,kind=number",
            ),
        )
        val pageCount = pageInfo?.optInt("pagenumber", 0)?.coerceAtMost(MAX_MEDIA_PAGES) ?: 0
        val pages = if (pageCount > 0) 1..pageCount else 0..0
        val paths = mutableListOf<String>()
        pages.forEach { page ->
            val candidates = if (page == 0) {
                listOf(containerPath)
            } else {
                listOf(
                    "$containerPath?page=$page&order=desc",
                    "$containerPath?page=$page",
                )
            }
            val response = getFirstJsonRequired(candidates, "Reading camera media page")
            response.optJSONArray("path")?.let { array ->
                repeat(array.length()) { index ->
                    array.optString(index).takeIf { it.isNotBlank() }?.let(paths::add)
                }
            }
        }
        return paths.distinct()
    }

    private fun normalizeCameraResource(value: String): String {
        val parsed = URI(value)
        val normalized = if (parsed.isAbsolute) {
            val camera = URI(baseUrl)
            require(
                parsed.scheme.equals(camera.scheme, ignoreCase = true) &&
                    parsed.host.equals(camera.host, ignoreCase = true) &&
                    parsed.effectivePort() == camera.effectivePort(),
            ) { "Camera returned a media URL outside the active camera origin." }
            parsed.rawPath + parsed.rawQuery?.let { "?$it" }.orEmpty()
        } else {
            value
        }
        require(normalized.startsWith("/ccapi/")) {
            "Camera returned an invalid media path: $value"
        }
        return normalized
    }

    private suspend fun requestMediaFile(
        paths: List<String>,
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit,
    ): CameraMediaDownloadResult =
        withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            for (path in paths) {
                currentCoroutineContext().ensureActive()
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val call = httpClient.newCall(request)
                val cancelCall = AtomicBoolean(true)
                val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (cancelCall.get()) call.cancel()
                    }
                }
                try {
                    val response = try {
                        call.execute()
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        currentCoroutineContext().ensureActive()
                        errors.add("$path: ${exception.message ?: exception.javaClass.simpleName}")
                        continue
                    }

                    if (!response.isSuccessful) {
                        response.use {
                            val preview = response.body?.string().orEmpty().take(MAX_ERROR_BODY_CHARS)
                            errors.add("$path: HTTP ${response.code}: $preview")
                        }
                        continue
                    }

                    val contentType = response.header("content-type")
                    val previewBytes = response.peekBody(MEDIA_SNIFF_BYTES).bytes()
                    if (contentType.isTextLikeContentType() || previewBytes.looksLikeTextPayload()) {
                        response.use {
                            val preview = previewBytes.toString(StandardCharsets.UTF_8).take(MAX_ERROR_BODY_CHARS)
                            errors.add("$path: HTTP ${response.code}: $preview")
                        }
                        continue
                    }

                    try {
                        return@withContext response.use {
                            val body = requireNotNull(response.body) { "Camera returned an empty media response." }
                            val totalBytes = body.contentLength().takeIf { it >= 0L }
                            var bytesTransferred = 0L
                            var lastReportedBytes = 0L
                            onProgress(CameraMediaTransferProgress(0L, totalBytes))

                            val buffer = ByteArray(MEDIA_TRANSFER_BUFFER_BYTES)
                            body.byteStream().use { input ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    currentCoroutineContext().ensureActive()
                                    destination.write(buffer, 0, count)
                                    bytesTransferred += count
                                    if (bytesTransferred - lastReportedBytes >= MEDIA_PROGRESS_INTERVAL_BYTES) {
                                        lastReportedBytes = bytesTransferred
                                        onProgress(CameraMediaTransferProgress(bytesTransferred, totalBytes))
                                    }
                                }
                            }
                            destination.flush()
                            if (bytesTransferred != lastReportedBytes || bytesTransferred == 0L) {
                                onProgress(CameraMediaTransferProgress(bytesTransferred, totalBytes))
                            }
                            CameraMediaDownloadResult(
                                item = item.copy(sizeBytes = item.sizeBytes ?: bytesTransferred),
                                bytesTransferred = bytesTransferred,
                                contentType = contentType,
                            )
                        }
                    } catch (exception: Exception) {
                        currentCoroutineContext().ensureActive()
                        throw exception
                    }
                } finally {
                    cancelCall.set(false)
                    cancellationWatcher.cancel()
                }
            }
            error(
                "Media download failed for '${item.name}'. Tried:\n" +
                    errors.joinToString(separator = "\n") { "  - $it" },
            )
        }

    private suspend fun getFirstJson(paths: List<String>): JSONObject? {
        paths.forEach { path ->
            try {
                return getJson(path)
            } catch (exception: CancellationException) {
                throw exception
            } catch (e: Exception) {
                // Try the next API version or endpoint variant.
            }
        }
        return null
    }

    private suspend fun getFirstJsonRequired(paths: List<String>, operation: String): JSONObject {
        val errors = mutableListOf<String>()
        paths.forEach { path ->
            try {
                return getJson(path)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                errors.add("$path: ${exception.message ?: exception.javaClass.simpleName}")
            }
        }
        error(
            "$operation failed. Tried:\n" +
                errors.joinToString(separator = "\n") { "  - $it" },
        )
    }

    private suspend fun loadShootingSettings(): JSONObject? {
        settingPathsByKey.clear()
        settingValuesByKey.clear()
        settingsLoaded = false
        val merged = JSONObject()

        val paths = if (enforceAdvertisedOperations) {
            advertisedApiPaths("GET", "/shooting/settings")
        } else {
            versionedPaths("/shooting/settings")
        }
        paths.forEach { path ->
            val settings = try {
                getJson(path)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            } ?: return@forEach

            val prefix = path.removeSuffix("/shooting/settings")
            val keys = settings.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val settingPath = "$prefix/shooting/settings/$key"
                if (!enforceAdvertisedOperations || apiOperations.contains(CcapiApiOperation("PUT", settingPath))) {
                    settingPathsByKey.putIfAbsent(key, settingPath)
                    val values = settings.optJSONObject(key)
                        ?.optJSONArray("ability")
                        ?.toStringList()
                        .orEmpty()
                        .filter { it.isNotBlank() }
                        .toSet()
                    if (values.isNotEmpty()) settingValuesByKey.putIfAbsent(key, values)
                }
                if (!merged.has(key)) {
                    merged.put(key, settings.get(key))
                }
            }
        }

        settingsLoaded = true
        return if (merged.length() > 0) merged else null
    }

    private suspend fun putSettingValue(candidateKeys: List<String>, value: String) {
        if (!settingsLoaded) {
            loadShootingSettings()
        }

        val supportedCandidates = candidateKeys.filter(settingPathsByKey::containsKey)
        if (supportedCandidates.isEmpty()) {
            error("Camera did not advertise a writable setting for ${candidateKeys.joinToString()}.")
        }
        val advertisedValues = supportedCandidates.flatMap { settingValuesByKey[it].orEmpty() }.toSet()
        if (value !in advertisedValues) {
            error("Value '$value' is not advertised for ${supportedCandidates.first()}.")
        }
        val paths = supportedCandidates
            .mapNotNull(settingPathsByKey::get)
            .distinct()

        val errors = mutableListOf<String>()
        paths.forEach { path ->
            try {
                putOk(path, JSONObject().put("value", value))
                return
            } catch (exception: Exception) {
                errors.add("$path: ${exception.message ?: exception.javaClass.simpleName}")
            }
        }

        error(
            "Failed to set shooting setting to '$value'. Tried:\n" +
                errors.joinToString(separator = "\n") { "  - $it" }
        )
    }

    private fun writableSetting(settings: JSONObject?, candidateKeys: List<String>): JSONObject? {
        if (settings == null) return null
        val key = candidateKeys.firstOrNull(settingPathsByKey::containsKey) ?: return null
        return settings.optJSONObject(key)
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

    private suspend fun postOk(path: String, payload: JSONObject): Unit = requestOk(
        Request.Builder()
            .url("$baseUrl$path")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build(),
    )

    private suspend fun putOk(path: String, payload: JSONObject): Unit = requestOk(
        Request.Builder()
            .url("$baseUrl$path")
            .put(payload.toString().toRequestBody(jsonMediaType))
            .build(),
    )

    private suspend fun deleteOk(path: String): Unit = requestOk(
        Request.Builder()
            .url("$baseUrl$path")
            .delete()
            .build(),
    )

    private suspend fun requestJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CcapiHttpException(
                    statusCode = response.code,
                    message = "Camera request failed: ${request.method} ${request.url} returned HTTP ${response.code}\nBody: $body",
                )
            }
            JSONObject(body)
        }
    }

    private suspend fun requestOk(request: Request): Unit = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CcapiHttpException(
                    statusCode = response.code,
                    message = "Camera request failed: ${request.method} ${request.url} returned HTTP ${response.code}\nBody: $body",
                )
            }
        }
    }

    private suspend fun requestLiveViewFrame(request: Request, sourceUrl: String): LiveViewFrame =
        withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                val contentType = response.header("content-type")
                val body = response.body ?: error("Live view frame failed: empty response body")

                if (!response.isSuccessful) {
                    val preview = body.string().trim().take(MAX_ERROR_BODY_CHARS)
                    error(
                        "Live view frame failed: ${request.method} ${request.url} returned HTTP ${response.code}\n" +
                            "Content-Type: ${contentType ?: "unknown"}\n" +
                            "Body: $preview"
                    )
                }

                if (contentType.isTextLikeContentType()) {
                    val preview = body.string().trim().take(MAX_ERROR_BODY_CHARS)
                    error(
                        "Live view frame returned ${contentType ?: "text"} instead of image bytes.\n" +
                            "Body: $preview"
                    )
                }

                LiveViewFrame(
                    bytes = readFirstJpegFrame(body.byteStream()),
                    contentType = contentType,
                    sourceUrl = sourceUrl,
                )
            }
        }

    private fun readFirstJpegFrame(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        var foundStart = false
        var previous = -1
        var scannedBytes = 0

        while (true) {
            val current = input.read()
            if (current == -1) break

            scannedBytes += 1
            if (scannedBytes > MAX_LIVE_VIEW_SCAN_BYTES) {
                error("Live view response did not contain a complete JPEG frame within $MAX_LIVE_VIEW_SCAN_BYTES bytes.")
            }

            if (!foundStart) {
                if (previous == JPEG_MARKER_PREFIX && current == JPEG_START_MARKER) {
                    foundStart = true
                    output.write(JPEG_MARKER_PREFIX)
                    output.write(JPEG_START_MARKER)
                }
            } else {
                output.write(current)
                if (output.size() > MAX_LIVE_VIEW_FRAME_BYTES) {
                    error("Live view JPEG frame exceeded $MAX_LIVE_VIEW_FRAME_BYTES bytes.")
                }
                if (previous == JPEG_MARKER_PREFIX && current == JPEG_END_MARKER) {
                    return output.toByteArray()
                }
            }

            previous = current
        }

        if (foundStart) {
            error("Live view response ended before the JPEG frame was complete.")
        }
        error("Live view response did not contain a JPEG frame.")
    }

    private fun String?.isTextLikeContentType(): Boolean =
        this != null && (
            startsWith("text/", ignoreCase = true) ||
                contains("json", ignoreCase = true) ||
                contains("html", ignoreCase = true)
            )

    private fun ByteArray.looksLikeTextPayload(): Boolean {
        val first = firstOrNull { it.toInt().toChar() !in " \t\r\n" }?.toInt()?.toChar() ?: return false
        return first == '{' || first == '[' || first == '<'
    }

    private companion object {
        val CCAPI_HTTP_METHODS = listOf("GET", "PUT", "POST", "DELETE")
        const val JPEG_MARKER_PREFIX = 0xFF
        const val JPEG_START_MARKER = 0xD8
        const val JPEG_END_MARKER = 0xD9
        const val MAX_LIVE_VIEW_SCAN_BYTES = 16 * 1024 * 1024
        const val MAX_LIVE_VIEW_FRAME_BYTES = 12 * 1024 * 1024
        const val MAX_ERROR_BODY_CHARS = 2_000
        const val HALF_PRESS_DURATION_MILLIS = 350L
        const val MAX_MEDIA_ITEMS = 500
        const val MAX_MEDIA_PAGES = 100
        const val MAX_MEDIA_TREE_DEPTH = 4
        const val MEDIA_TRANSFER_BUFFER_BYTES = 64 * 1024
        const val MEDIA_PROGRESS_INTERVAL_BYTES = 512 * 1024L
        const val MEDIA_SNIFF_BYTES = 64L
    }
}

private fun URI.effectivePort(): Int = when {
    port >= 0 -> port
    scheme.equals("https", ignoreCase = true) -> 443
    else -> 80
}

private fun String.isMediaFilePath(): Boolean {
    val name = substringAfterLast('/')
    return name.contains('.') && !name.endsWith('.')
}

private fun String.mediaKind(): String = when (substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "hif", "heif", "png" -> "image"
    "cr2", "cr3", "raw" -> "raw"
    "mp4", "mov" -> "video"
    else -> "other"
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
        batteryLevel = battery.optNullableInt("level"),
        batteryStatus = battery.optString("status"),
        recording = optNullableBoolean("recording"),
        mode = optString("mode"),
        mediaAvailable = media.optNullableBoolean("available"),
        remainingMinutes = media.optNullableInt("remaining_minutes"),
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
    matrix = CapabilityMatrix.ccapiNetwork(
        CapabilityMatrix.ccapiNetwork().supported + setOf(
            CameraFeature.STILL_CAPTURE,
            CameraFeature.SHUTTER_HALF_PRESS,
            CameraFeature.MEDIA_BROWSER,
            CameraFeature.MEDIA_DOWNLOAD,
        ),
    ),
    liveView = LiveViewCapabilities.simulator(),
)

private fun JSONObject.toAdvancedSettingControls(writableKeys: Set<String>): List<CameraSettingControl> {
    val controls = mutableListOf<CameraSettingControl>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key in PRIMARY_SETTING_KEYS || key !in writableKeys) continue

        val setting = optJSONObject(key) ?: continue
        val values = setting.optJSONArray("ability")?.toStringList().orEmpty()
            .filter { it.isNotBlank() }
            .distinct()
        val value = setting.optString("value", "")
        if (values.size < 2 || value.isBlank()) continue

        controls.add(
            CameraSettingControl(
                key = key,
                label = key.toSettingLabel(),
                value = value,
                values = values,
            )
        )
    }
    return controls.sortedWith(compareBy<CameraSettingControl> { it.label }.thenBy { it.key })
}

private val PRIMARY_SETTING_KEYS = setOf(
    "iso",
    "shutter",
    "shutterspeed",
    "tv",
    "aperture",
    "av",
    "whitebalance",
    "white_balance",
    "wb",
)

private fun String.toSettingLabel(): String =
    when (this) {
        "afmethod" -> "AF method"
        "afoperation" -> "AF operation"
        "drivemode" -> "Drive mode"
        "meteringmode" -> "Metering"
        "picturestyle" -> "Picture style"
        "shootingmode" -> "Shooting mode"
        "stillimagequality" -> "Image quality"
        "moviequality" -> "Movie quality"
        "colortemperature" -> "Color temperature"
        "exposurecompensation" -> "Exposure compensation"
        "ae" -> "AE mode"
        else -> split(Regex("[_\\-]"))
            .flatMap { token -> token.splitCamelCaseWords() }
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercaseChar() } }
            .ifBlank { this }
    }

private fun String.splitCamelCaseWords(): List<String> =
    replace(Regex("([a-z])([A-Z])"), "$1 $2").split(" ")

private fun org.json.JSONArray.toStringList(): List<String> =
    List(length()) { index -> getString(index) }

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

private fun parseBatteryInfo(jsonStr: String): Pair<Int?, String> {
    try {
        val trimmed = jsonStr.trim()
        if (trimmed.startsWith("[")) {
            val array = org.json.JSONArray(trimmed)
            if (array.length() > 0) {
                return parseSingleBattery(array.getJSONObject(0))
            }
        } else {
            val obj = JSONObject(trimmed)
            if (obj.has("batterylist")) {
                val array = obj.optJSONArray("batterylist")
                if (array != null && array.length() > 0) {
                    return parseSingleBattery(array.getJSONObject(0))
                }
            }
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
    return Pair(null, "unknown")
}

private fun parseSingleBattery(obj: JSONObject): Pair<Int?, String> {
    var batteryLevel: Int? = null
    var batteryLevelStr = "unknown"

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
                    levelStr.toIntOrNull()
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
            if (obj.has("storagelist")) {
                val array = obj.optJSONArray("storagelist")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        if (isSingleCardReady(array.getJSONObject(i))) return true
                    }
                }
            }
            if (obj.has("storage")) {
                val array = obj.optJSONArray("storage")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        if (isSingleCardReady(array.getJSONObject(i))) return true
                    }
                }
            }
            if (obj.has("path")) {
                val array = obj.optJSONArray("path")
                if (array != null && array.length() > 0) {
                    return true
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
    if (hasPositiveStorageValue(card, "spacesize") || hasPositiveStorageValue(card, "maxsize") || hasPositiveStorageValue(card, "capacity")) {
        return status != "not_inserted" && status != "none"
    }
    if (card.has("free") || card.has("maxsize") || card.has("capacity")) {
        val free = card.optString("free", "")
        if (free.isNotEmpty() && free != "0" && free != "0 GB" && status != "not_inserted") {
            return true
        }
    }
    return false
}

private fun hasPositiveStorageValue(card: JSONObject, key: String): Boolean {
    if (!card.has(key) || card.isNull(key)) return false
    val numberValue = card.optLong(key, Long.MIN_VALUE)
    if (numberValue > 0) return true
    val textValue = card.optString(key, "")
    return textValue
        .filter { it.isDigit() }
        .toLongOrNull()
        ?.let { it > 0 }
        ?: false
}
