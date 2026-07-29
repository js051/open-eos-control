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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import kotlin.math.floor

private data class CcapiApiOperation(
    val method: String,
    val path: String,
)

private data class CcapiLiveViewGeometry(
    val positionX: Int,
    val positionY: Int,
    val positionWidth: Int,
    val positionHeight: Int,
) {
    fun cameraPosition(normalizedX: Double, normalizedY: Double): Pair<Int, Int> {
        val x = positionX + floor(normalizedX * positionWidth).toInt()
        val y = positionY + floor(normalizedY * positionHeight).toInt()
        return x.coerceIn(positionX, positionX + positionWidth - 1) to
            y.coerceIn(positionY, positionY + positionHeight - 1)
    }
}

private data class CcapiDetailedLiveView(
    val image: ByteArray?,
    val geometry: CcapiLiveViewGeometry?,
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
    private val rtpDestinationAddress: String? = null,
    private val rtpSessionFactory: CcapiRtpSessionFactory? = null,
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
    private var bulbExposureActive = false
    private val settingPathsByKey = mutableMapOf<String, String>()
    private val settingValuesByKey = mutableMapOf<String, Set<String>>()
    private val structuredSettingPathsByKey = mutableMapOf<String, String>()
    private val structuredSettingValuesByKey = mutableMapOf<String, Set<String>>()
    private val structuredSettingCurrentValues = mutableMapOf<String, JSONObject>()
    private val apiOperations = linkedSetOf<CcapiApiOperation>()
    private val observedFeatures = mutableSetOf<CameraFeature>()
    private var enforceAdvertisedOperations = false
    private var settingsLoaded = false
    private var discoverySource = "unknown"
    private var liveViewSizeControlSupported = true
    private var activeLiveViewSize = LiveViewSize.MEDIUM
    private var latestLiveViewGeometry: CcapiLiveViewGeometry? = null
    private var activeLiveViewSource: LiveViewSource? = null

    var nativeLiveViewSession: NativeLiveViewSession? = null
        private set

    fun observedFeatureSnapshot(): Set<CameraFeature> = observedFeatures.toSet()

    suspend fun close() {
        if (bulbExposureActive) {
            runCatching { stopBulbExposure() }
        }
        runCatching { stopLiveView() }
    }

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
            discoverySource = "simulator contract"
            return
        }

        val errors = mutableListOf<String>()

        // 1. Try GET /ccapi
        val success1 = try {
            val request = Request.Builder().url("$baseUrl/ccapi").get().build()
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        parseDiscoveryResponse(response.body?.string().orEmpty(), "GET /ccapi")
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
                        parseDiscoveryResponse(response.body?.string().orEmpty(), "GET /ccapi/")
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
                            discoverySource = "GET $prefix/deviceinformation (identity fallback)"
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

    private fun parseDiscoveryResponse(body: String, source: String) {
        val json = JSONObject(body)
        val versions = linkedSetOf<String>()
        apiOperations.clear()
        enforceAdvertisedOperations = true
        discoverySource = source
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
            val fullPath = advertisedOperationPath(version, entry) ?: continue
            CCAPI_HTTP_METHODS.forEach { method ->
                if (entry.has(method.lowercase()) && entry.methodIsSupported(method.lowercase())) {
                    apiOperations.add(CcapiApiOperation(method, fullPath))
                }
            }
        }
    }

    private fun advertisedOperationPath(version: String, entry: JSONObject): String? {
        listOf("path", "url").forEach { key ->
            val value = entry.opt(key) as? String ?: return@forEach
            val parsed = runCatching { URI(value.trim()) }.getOrNull() ?: return@forEach
            if (parsed.rawFragment != null || parsed.rawUserInfo != null) return@forEach
            val rawPath = if (parsed.isAbsolute) {
                val camera = runCatching { URI(baseUrl) }.getOrNull() ?: return null
                if (!parsed.hasSameOriginAs(camera)) return@forEach
                parsed.rawPath
            } else {
                if (parsed.rawAuthority != null) return@forEach
                parsed.rawPath
            }
            if (rawPath.isNullOrBlank() || rawPath.hasTraversalSegment()) return@forEach
            val normalized = if (rawPath.startsWith("/ccapi/")) {
                rawPath
            } else {
                "/ccapi/$version/${rawPath.trimStart('/')}"
            }
            if (normalized.startsWith("/ccapi/") && '\r' !in normalized && '\n' !in normalized) {
                return normalized
            }
        }
        return null
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
            getJson("/ccapi/info").toCameraInfo().also {
                observedFeatures.add(CameraFeature.CAMERA_IDENTITY)
            }
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
            val storageInfo = if (storageJson != null) {
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
                mediaAvailable = storageInfo?.available,
                remainingMinutes = null,
                exposure = ExposureState(
                    iso = isoVal ?: "-",
                    shutter = shutterVal ?: "-",
                    aperture = apertureVal ?: "-",
                    whiteBalance = wbVal ?: "-"
                ),
                storageTotalBytes = storageInfo?.totalBytes,
                storageFreeBytes = storageInfo?.freeBytes,
                storageFreeImages = storageInfo?.freeImages,
                storageDeviceCount = storageInfo?.devices,
                rawBatteryJson = batteryJson?.toString() ?: "null",
                rawStorageJson = storageJson?.toString() ?: "null",
                bulbExposureActive = bulbExposureActive,
            )
        } else {
            getJson("/ccapi/status").toCameraStatus().also {
                observedFeatures.addAll(setOf(CameraFeature.BATTERY_STATUS, CameraFeature.STORAGE_STATUS))
            }
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
            val supportsJpegLiveView = supportsCompleteLiveView()
            val supportsRtpLiveView = supportsRtpLiveView()
            if (supportsJpegLiveView || supportsRtpLiveView || CameraFeature.LIVE_VIEW in observedFeatures) {
                supportedFeatures.add(CameraFeature.LIVE_VIEW)
            }
            if (supportsJpegLiveView) {
                supportedFeatures.add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
            }
            if (supportsRtpLiveView) {
                supportedFeatures.add(CameraFeature.LIVE_VIEW_RTP)
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
                supportedFeatures.add(CameraFeature.BULB_EXPOSURE)
            }
            if (autofocusOperation() != null || manualShutterOperation() != null) {
                supportedFeatures.add(CameraFeature.AUTOFOCUS)
            }
            if (supportsCoordinateTapFocus()) {
                supportedFeatures.add(CameraFeature.TAP_FOCUS)
            }
            if (supportsCoordinateClickWhiteBalance()) {
                supportedFeatures.add(CameraFeature.CLICK_WHITE_BALANCE)
            }
            if (focusDriveOperation() != null) {
                supportedFeatures.add(CameraFeature.FOCUS_DRIVE)
            }
            if (supportsApi("GET", "/contents")) {
                supportedFeatures.add(CameraFeature.MEDIA_BROWSER)
                supportedFeatures.add(CameraFeature.MEDIA_THUMBNAIL)
                supportedFeatures.add(CameraFeature.MEDIA_PREVIEW)
                supportedFeatures.add(CameraFeature.MEDIA_DOWNLOAD)
            }
            if (supportsMediaDelete()) supportedFeatures.add(CameraFeature.MEDIA_DELETE)

            val liveViewCapabilities = ccapiLiveViewCapabilities().let { capabilities ->
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
                evidence = capabilityEvidence(),
            )
        } else {
            getJson("/ccapi/capabilities").toCameraCapabilities().copy(
                evidence = CameraCapabilityEvidence(
                    source = discoverySource,
                    observedFeatures = observedFeatures.toSet(),
                ),
            )
        }
    }

    suspend fun setExposure(
        iso: String? = null,
        shutter: String? = null,
        aperture: String? = null,
    ): CameraStatus {
        val status = if (isRealCamera) {
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
        observedFeatures.add(CameraFeature.EXPOSURE_CONTROL)
        return status
    }

    suspend fun setWhiteBalance(value: String): CameraStatus {
        val status = if (isRealCamera) {
            putSettingValue(listOf("wb", "whitebalance", "white_balance"), value)
            status()
        } else {
            patchJson("/ccapi/white-balance", JSONObject().put("white_balance", value)).toCameraStatus()
        }
        observedFeatures.add(CameraFeature.WHITE_BALANCE_CONTROL)
        return status
    }

    suspend fun setSetting(key: String, value: String): CameraStatus {
        val status = if (isRealCamera) {
            putSettingValue(listOf(key), value)
            status()
        } else {
            status()
        }
        observedFeatures.add(featureForSetting(key))
        return status
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
        observedFeatures.add(CameraFeature.VIDEO_RECORDING)
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
        observedFeatures.add(CameraFeature.VIDEO_RECORDING)
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
        } else {
            postJson("/ccapi/capture/still", JSONObject().put("af", true))
        }
        observedFeatures.add(CameraFeature.STILL_CAPTURE)
        return status()
    }

    suspend fun startBulbExposure(): CameraStatus {
        if (bulbExposureActive) return status()
        val baseline = status()
        if (isRealCamera) {
            val operation = manualShutterOperation()
            if (enforceAdvertisedOperations && operation == null) {
                error("Camera did not advertise manual shutter control for Bulb exposure.")
            }
            try {
                commandOk(
                    pathSuffix = "/shooting/control/shutterbutton/manual",
                    payload = JSONObject().put("af", false).put("action", "full_press"),
                    operation = operation,
                )
            } catch (exception: Throwable) {
                withContext(NonCancellable) {
                    runCatching {
                        commandOk(
                            pathSuffix = "/shooting/control/shutterbutton/manual",
                            payload = JSONObject().put("af", false).put("action", "release"),
                            operation = operation,
                        )
                    }.exceptionOrNull()?.let(exception::addSuppressed)
                }
                throw exception
            }
        } else {
            try {
                postJson("/ccapi/bulb/start", JSONObject())
            } catch (exception: Throwable) {
                withContext(NonCancellable) {
                    runCatching { postJson("/ccapi/bulb/stop", JSONObject()) }
                        .exceptionOrNull()
                        ?.let(exception::addSuppressed)
                }
                throw exception
            }
        }
        bulbExposureActive = true
        return baseline.copy(bulbExposureActive = true)
    }

    suspend fun stopBulbExposure(): CameraStatus {
        if (!bulbExposureActive) return status()
        if (isRealCamera) {
            val operation = manualShutterOperation()
            if (enforceAdvertisedOperations && operation == null) {
                error("Camera no longer advertises manual shutter control for Bulb release.")
            }
            withContext(NonCancellable) {
                commandOk(
                    pathSuffix = "/shooting/control/shutterbutton/manual",
                    payload = JSONObject().put("af", false).put("action", "release"),
                    operation = operation,
                )
            }
        } else {
            withContext(NonCancellable) { postJson("/ccapi/bulb/stop", JSONObject()) }
        }
        bulbExposureActive = false
        observedFeatures.add(CameraFeature.BULB_EXPOSURE)
        return status()
    }

    suspend fun autofocus(): CameraStatus {
        if (isRealCamera) {
            val operation = autofocusOperation()
            val manualOperation = manualShutterOperation()
            if (enforceAdvertisedOperations && operation == null && manualOperation == null) {
                error("Camera did not advertise autofocus or manual shutter control.")
            }
            if (operation != null || !enforceAdvertisedOperations) {
                withGuaranteedRelease(
                    press = {
                        commandOk(
                            pathSuffix = "/shooting/control/af",
                            payload = JSONObject().put("action", "start"),
                            operation = operation,
                        )
                    },
                    release = {
                        commandOk(
                            pathSuffix = "/shooting/control/af",
                            payload = JSONObject().put("action", "stop"),
                            operation = operation,
                        )
                    },
                    afterPress = { delay(HALF_PRESS_DURATION_MILLIS) },
                )
            } else {
                withGuaranteedRelease(
                    press = {
                        commandOk(
                            pathSuffix = "/shooting/control/shutterbutton/manual",
                            payload = JSONObject().put("af", true).put("action", "half_press"),
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
                    afterPress = { delay(HALF_PRESS_DURATION_MILLIS) },
                )
            }
        } else {
            withGuaranteedRelease(
                press = { postJson("/ccapi/shutter/half-press", JSONObject()) },
                release = { postJson("/ccapi/shutter/release", JSONObject()) },
                afterPress = { delay(HALF_PRESS_DURATION_MILLIS) },
            )
        }
        observedFeatures.add(CameraFeature.AUTOFOCUS)
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
        } else {
            withGuaranteedRelease(
                press = { postJson("/ccapi/shutter/half-press", JSONObject()) },
                release = { postJson("/ccapi/shutter/release", JSONObject()) },
                afterPress = { delay(HALF_PRESS_DURATION_MILLIS) },
            )
        }
        observedFeatures.add(CameraFeature.SHUTTER_HALF_PRESS)
        return status()
    }

    suspend fun driveFocus(
        direction: FocusDriveDirection,
        step: FocusDriveStep,
    ): FocusDriveResult {
        if (!isRealCamera) {
            error("Manual focus drive is not available in the simulator.")
        }
        val operation = focusDriveOperation()
            ?: error("Camera did not advertise manual focus drive control.")
        val stepNumber = when (step) {
            FocusDriveStep.SMALL -> 1
            FocusDriveStep.MEDIUM -> 2
            FocusDriveStep.LARGE -> 3
        }
        val value = "${direction.name.lowercase()}$stepNumber"
        commandOk(
            pathSuffix = "/shooting/control/drivefocus",
            payload = JSONObject().put("value", value),
            operation = operation,
        )
        observedFeatures.add(CameraFeature.FOCUS_DRIVE)
        return FocusDriveResult(ok = true, direction = direction, step = step)
    }

    suspend fun listMedia(): List<CameraMediaItem> {
        val items = if (isRealCamera) listRealMedia() else listSimulatorMedia()
        observedFeatures.add(CameraFeature.MEDIA_BROWSER)
        return items
    }

    suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail {
        val (bytes, contentType) = mediaImageRepresentation(
            item = item,
            kind = "thumbnail",
            maxBytes = MAX_MEDIA_THUMBNAIL_BYTES,
            label = "thumbnail",
        )
        observedFeatures.add(CameraFeature.MEDIA_THUMBNAIL)
        return CameraMediaThumbnail(item = item, bytes = bytes, contentType = contentType)
    }

    suspend fun mediaPreview(item: CameraMediaItem): CameraMediaPreview {
        require(item.kind.equals("image", ignoreCase = true) || item.kind.equals("raw", ignoreCase = true)) {
            "Display preview is available only for camera image items."
        }
        val (bytes, contentType) = mediaImageRepresentation(
            item = item,
            kind = "display",
            maxBytes = MAX_MEDIA_PREVIEW_BYTES,
            label = "display preview",
        )
        observedFeatures.add(CameraFeature.MEDIA_PREVIEW)
        return CameraMediaPreview(item = item, bytes = bytes, contentType = contentType)
    }

    private suspend fun mediaImageRepresentation(
        item: CameraMediaItem,
        kind: String,
        maxBytes: Int,
        label: String,
    ): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        if (isRealCamera && !supportsApi("GET", "/contents")) {
            error("Camera did not advertise CCAPI media browsing.")
        }
        val path = if (isRealCamera) {
            normalizeCameraResource(item.id).substringBefore('?')
        } else {
            val encodedId = URLEncoder.encode(item.id, StandardCharsets.UTF_8.name()).replace("+", "%20")
            "/ccapi/media/$encodedId"
        }
        val representationUrl = "$baseUrl$path".toHttpUrl().newBuilder()
            .query(null)
            .addQueryParameter("kind", kind)
            .build()
        val request = Request.Builder()
            .url(representationUrl)
            .header("Accept", "image/*,application/octet-stream;q=0.5")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = requireNotNull(response.body) { "Camera returned an empty $label response." }
            if (!response.isSuccessful) {
                val preview = body.string().take(MAX_ERROR_BODY_CHARS)
                error("Camera $label request failed: HTTP ${response.code}: $preview")
            }
            val contentLength = body.contentLength()
            check(contentLength < 0L || contentLength <= maxBytes) {
                "Camera $label exceeded $maxBytes bytes."
            }
            val bytes = body.byteStream().readBounded(maxBytes)
            check(bytes.isNotEmpty()) { "Camera returned an empty $label." }
            val responseContentType = response.header("content-type")?.substringBefore(';')?.trim()
            check(!responseContentType.isTextLikeContentType() && !bytes.looksLikeTextPayload()) {
                "Camera returned text instead of an image $label."
            }
            val contentType = responseContentType
                ?.takeIf { it.startsWith("image/", ignoreCase = true) }
                ?: bytes.detectImageContentType()
                ?: error("Camera did not return a recognized image $label.")
            bytes to contentType
        }
    }

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

    suspend fun deleteMedia(item: CameraMediaItem) {
        val path = if (isRealCamera) {
            if (enforceAdvertisedOperations && !supportsMediaDelete()) {
                error("Camera did not advertise media deletion.")
            }
            normalizeCameraResource(item.id).substringBefore('?')
        } else {
            val encodedId = URLEncoder.encode(item.id, StandardCharsets.UTF_8.name()).replace("+", "%20")
            "/ccapi/media/$encodedId"
        }
        deleteOk(path)
        observedFeatures.add(CameraFeature.MEDIA_DELETE)
    }

    suspend fun startLiveView(request: LiveViewRequest = LiveViewRequest()) {
        if (isRealCamera) {
            latestLiveViewGeometry = null
            val requestedSource = request.source
            val source = when (requestedSource) {
                LiveViewSource.AUTO -> if (supportsRtpLiveView()) {
                    LiveViewSource.CCAPI_RTP
                } else {
                    LiveViewSource.CCAPI_JPEG_POLLING
                }

                LiveViewSource.CCAPI_JPEG_POLLING,
                LiveViewSource.CCAPI_RTP,
                -> requestedSource

                else -> error("${requestedSource.label} is not available through the CCAPI network backend.")
            }

            if (source == LiveViewSource.CCAPI_RTP) {
                try {
                    startRtpLiveView(request)
                    return
                } catch (exception: Exception) {
                    if (requestedSource != LiveViewSource.AUTO || !supportsCompleteLiveView()) throw exception
                }
            }
            startJpegLiveView(request)
        }
    }

    suspend fun stopLiveView() {
        latestLiveViewGeometry = null
        if (isRealCamera) {
            when (activeLiveViewSource) {
                LiveViewSource.CCAPI_RTP -> stopRtpLiveView()
                LiveViewSource.CCAPI_JPEG_POLLING -> {
                    if (!enforceAdvertisedOperations || supportsApi("DELETE", "/shooting/liveview")) {
                        runCatching { deleteOk(apiPath("DELETE", "/shooting/liveview")) }
                    }
                }

                else -> Unit
            }
            activeLiveViewSource = null
        }
    }

    suspend fun tapFocus(x: Double, y: Double): FocusResult {
        return if (isRealCamera) {
            val operation = tapFocusOperation()
            if (enforceAdvertisedOperations && !supportsCoordinateTapFocus()) {
                error("Camera did not advertise Canon Live View AF frame position control with detailed Live View metadata.")
            }
            val (positionX, positionY) = cameraLiveViewPosition(x, y, CameraFeature.TAP_FOCUS)
            val payload = JSONObject()
                .put("positionx", positionX)
                .put("positiony", positionY)
            val selectedOperation = operation ?: CcapiApiOperation(
                method = "PUT",
                path = apiPath("PUT", "/shooting/liveview/afframeposition"),
            )
            commandOk("/shooting/liveview/afframeposition", payload, selectedOperation)
            observedFeatures.add(CameraFeature.TAP_FOCUS)
            FocusResult(ok = true, x = x, y = y)
        } else {
            val payload = JSONObject().put("x", x).put("y", y)
            val json = postJson("/ccapi/focus/tap", payload)
            FocusResult(
                ok = json.optBoolean("ok"),
                x = json.optDouble("x"),
                y = json.optDouble("y"),
            ).also { if (it.ok) observedFeatures.add(CameraFeature.TAP_FOCUS) }
        }
    }

    suspend fun clickWhiteBalance(x: Double, y: Double): CameraStatus {
        if (!isRealCamera) {
            val status = postJson(
                    "/ccapi/whitebalance/click",
                    JSONObject().put("x", x).put("y", y),
                ).toCameraStatus()
            observedFeatures.add(CameraFeature.CLICK_WHITE_BALANCE)
            return status
        }
        val operation = clickWhiteBalanceOperation()
        if (enforceAdvertisedOperations && !supportsCoordinateClickWhiteBalance()) {
            error("Camera did not advertise Canon Click WB control with detailed Live View metadata.")
        }
        val (positionX, positionY) = cameraLiveViewPosition(x, y, CameraFeature.CLICK_WHITE_BALANCE)
        val selectedOperation = operation ?: CcapiApiOperation(
            method = "POST",
            path = apiPath("POST", "/shooting/liveview/clickwb"),
        )
        commandOk(
            "/shooting/liveview/clickwb",
            JSONObject().put("positionx", positionX).put("positiony", positionY),
            selectedOperation,
        )
        observedFeatures.add(CameraFeature.CLICK_WHITE_BALANCE)
        return status()
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
                return requestLiveViewFrame(request, sourceUrl).also {
                    observedFeatures.add(CameraFeature.LIVE_VIEW)
                    observedFeatures.add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
                }
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
                LiveViewSource.AUTO -> if (activeLiveViewSource == LiveViewSource.CCAPI_RTP) {
                    error("CCAPI RTP Live View renders through the native H.264 surface, not the JPEG frame reader.")
                } else {
                    liveViewFramePaths().map { "$baseUrl$it" }
                }

                LiveViewSource.CCAPI_JPEG_POLLING -> liveViewFramePaths().map { "$baseUrl$it" }

                LiveViewSource.CCAPI_RTP -> error("CCAPI RTP Live View renders through the native H.264 surface, not the JPEG frame reader.")

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

    private fun capabilityEvidence(): CameraCapabilityEvidence {
        val protocolVersions = apiVersionPrefixes
            .map { it.substringAfterLast('/').replace("\r", "").replace("\n", "") }
            .distinct()
        val commands = apiOperations
            .asSequence()
            .map { operation ->
                val safePath = operation.path
                    .substringBefore('?')
                    .replace("\r", "")
                    .replace("\n", "")
                    .take(MAX_CAPABILITY_EVIDENCE_ITEM_CHARS)
                "${operation.method} $safePath".take(MAX_CAPABILITY_EVIDENCE_ITEM_CHARS)
            }
            .distinct()
            .sorted()
            .toList()
        val writableSettings = settingPathsByKey.keys
            .asSequence()
            .map { it.replace("\r", "").replace("\n", "").take(MAX_CAPABILITY_EVIDENCE_ITEM_CHARS) }
            .distinct()
            .sorted()
            .toList()
        return CameraCapabilityEvidence(
            source = discoverySource.replace("\r", "").replace("\n", "")
                .take(MAX_CAPABILITY_EVIDENCE_ITEM_CHARS),
            protocolVersions = protocolVersions.take(MAX_CAPABILITY_EVIDENCE_ITEMS),
            advertisedCommands = commands.take(MAX_CAPABILITY_EVIDENCE_ITEMS),
            writableSettings = writableSettings.take(MAX_CAPABILITY_EVIDENCE_ITEMS),
            observedFeatures = observedFeatures.toSet(),
            truncated = protocolVersions.size > MAX_CAPABILITY_EVIDENCE_ITEMS ||
                commands.size > MAX_CAPABILITY_EVIDENCE_ITEMS ||
                writableSettings.size > MAX_CAPABILITY_EVIDENCE_ITEMS,
        )
    }

    private fun featureForSetting(key: String): CameraFeature = when (key.lowercase()) {
        "iso", "tv", "shutter", "shutterspeed", "av", "aperture" -> CameraFeature.EXPOSURE_CONTROL
        "wb", "whitebalance", "white_balance" -> CameraFeature.WHITE_BALANCE_CONTROL
        else -> CameraFeature.ADVANCED_SETTINGS
    }

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
        apiOperation("PUT", "/shooting/liveview/afframeposition")

    private fun detailedLiveViewOperation(): CcapiApiOperation? =
        apiOperation("GET", "/shooting/liveview/flipdetail")

    private fun clickWhiteBalanceOperation(): CcapiApiOperation? =
        apiOperation("POST", "/shooting/liveview/clickwb")

    private fun supportsCoordinateTapFocus(): Boolean =
        tapFocusOperation() != null &&
            detailedLiveViewOperation() != null &&
            supportsApi("POST", "/shooting/liveview") &&
            supportsApi("DELETE", "/shooting/liveview")

    private fun supportsCoordinateClickWhiteBalance(): Boolean =
        clickWhiteBalanceOperation() != null &&
            detailedLiveViewOperation() != null &&
            supportsApi("POST", "/shooting/liveview") &&
            supportsApi("DELETE", "/shooting/liveview")

    private fun needsLiveViewGeometry(): Boolean =
        supportsCoordinateTapFocus() || supportsCoordinateClickWhiteBalance()

    private fun autofocusOperation(): CcapiApiOperation? =
        apiOperation("POST", "/shooting/control/af")

    private fun focusDriveOperation(): CcapiApiOperation? =
        apiOperation("POST", "/shooting/control/drivefocus")

    private fun liveViewFramePaths(): List<String> {
        if (!enforceAdvertisedOperations) {
            return listOf(
                apiPath("GET", "/shooting/liveview/flip"),
                "${apiPath("GET", "/shooting/liveview/flipdetail")}?kind=image",
                apiPath("GET", "/shooting/liveview"),
            )
        }
        return buildList {
            if (needsLiveViewGeometry()) {
                detailedLiveViewOperation()?.let { add("${it.path}?kind=both") }
            }
            apiOperation("GET", "/shooting/liveview/flip")?.let { add(it.path) }
            apiOperation("GET", "/shooting/liveview/flipdetail")?.let { add("${it.path}?kind=image") }
            apiOperation("GET", "/shooting/liveview")?.let { add(it.path) }
        }
    }

    private fun supportsCompleteLiveView(): Boolean =
        supportsApi("POST", "/shooting/liveview") &&
            supportsApi("DELETE", "/shooting/liveview") &&
            liveViewFramePaths().isNotEmpty()

    private fun supportsRtpLiveView(): Boolean =
        supportsApi("GET", "/shooting/liveview/rtpsessiondesc") &&
            supportsApi("POST", "/shooting/liveview/rtp") &&
            !rtpDestinationAddress.isNullOrBlank() &&
            rtpSessionFactory != null

    private fun ccapiLiveViewCapabilities(): LiveViewCapabilities {
        val sources = buildList {
            if (supportsRtpLiveView()) add(LiveViewSource.CCAPI_RTP)
            if (supportsCompleteLiveView()) add(LiveViewSource.CCAPI_JPEG_POLLING)
        }
        return LiveViewCapabilities.ccapiNetwork().copy(
            sources = sources,
            defaultSource = sources.firstOrNull() ?: LiveViewSource.AUTO,
        )
    }

    private suspend fun startJpegLiveView(request: LiveViewRequest) {
        if (enforceAdvertisedOperations && !supportsCompleteLiveView()) {
            error("Camera did not advertise a complete Live View JPEG start, frame, and stop lifecycle.")
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
        activeLiveViewSource = LiveViewSource.CCAPI_JPEG_POLLING
        observedFeatures.add(CameraFeature.LIVE_VIEW)
        observedFeatures.add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
    }

    private suspend fun startRtpLiveView(request: LiveViewRequest) {
        if (!supportsRtpLiveView()) {
            error("Canon RTP Live View needs advertised SDP/start endpoints and a reachable camera Wi-Fi IPv4 address.")
        }
        val descriptionPath = apiPath("GET", "/shooting/liveview/rtpsessiondesc")
        val controlPath = apiPath("POST", "/shooting/liveview/rtp")
        val description = CcapiRtpSessionDescriptionParser.parse(getText(descriptionPath))
        val session = checkNotNull(rtpSessionFactory).create(description, checkNotNull(rtpDestinationAddress))
        session.setTargetFps(request.fps)
        try {
            withContext(Dispatchers.IO) { session.start() }
            postOk(
                controlPath,
                JSONObject()
                    .put("action", "start")
                    .put("ipaddress", checkNotNull(rtpDestinationAddress)),
            )
        } catch (exception: Exception) {
            session.close()
            withContext(NonCancellable) {
                runCatching {
                    postOk(
                        controlPath,
                        JSONObject().put("action", "stop").put("ipaddress", ""),
                    )
                }
            }
            throw exception
        }
        nativeLiveViewSession?.close()
        nativeLiveViewSession = session
        activeLiveViewSource = LiveViewSource.CCAPI_RTP
        observedFeatures.add(CameraFeature.LIVE_VIEW)
        observedFeatures.add(CameraFeature.LIVE_VIEW_RTP)
    }

    private suspend fun stopRtpLiveView() {
        try {
            if (!enforceAdvertisedOperations || supportsApi("POST", "/shooting/liveview/rtp")) {
                postOk(
                    apiPath("POST", "/shooting/liveview/rtp"),
                    JSONObject().put("action", "stop").put("ipaddress", ""),
                )
            }
        } finally {
            nativeLiveViewSession?.close()
            nativeLiveViewSession = null
        }
    }

    private fun supportsMediaDelete(): Boolean = apiOperations.any { operation ->
        operation.method == "DELETE" &&
            (operation.path.endsWith("/contents") || "/contents/" in operation.path)
    }

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
        require(parsed.fragment == null && !parsed.path.hasTraversalSegment()) {
            "Camera returned an invalid media path: $value"
        }
        val normalized = if (parsed.isAbsolute) {
            val camera = URI(baseUrl)
            require(parsed.hasSameOriginAs(camera)) {
                "Camera returned a media URL outside the active camera origin."
            }
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
        structuredSettingPathsByKey.clear()
        structuredSettingValuesByKey.clear()
        structuredSettingCurrentValues.clear()
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
                    val setting = settings.optJSONObject(key)
                    val values = setting?.optJSONArray("ability")
                        ?.toStringList()
                        .orEmpty()
                        .filter { it.isNotBlank() }
                        .toSet()
                    if (values.isNotEmpty()) settingValuesByKey.putIfAbsent(key, values)
                    if (key == IMAGE_QUALITY_SETTING_KEY) {
                        val current = setting?.optJSONObject("value")
                        val ability = setting?.optJSONObject("ability")
                        if (current != null && ability != null) {
                            structuredSettingCurrentValues.putIfAbsent(key, JSONObject(current.toString()))
                            IMAGE_QUALITY_FIELDS.forEach { field ->
                                val fieldValues = ability.optJSONArray(field)
                                    ?.toStringList()
                                    .orEmpty()
                                    .filter { it.isNotBlank() }
                                    .distinct()
                                    .toSet()
                                if (fieldValues.isNotEmpty() && current.optString(field).isNotBlank()) {
                                    val virtualKey = "$key.$field"
                                    structuredSettingPathsByKey.putIfAbsent(virtualKey, settingPath)
                                    structuredSettingValuesByKey.putIfAbsent(virtualKey, fieldValues)
                                }
                            }
                        }
                    } else if (key == WB_SHIFT_SETTING_KEY) {
                        val current = setting?.optJSONObject("value")
                        val ability = setting?.optJSONObject("ability")
                        val currentValues = current?.let { value ->
                            WB_SHIFT_FIELDS.associateWith { field -> value.opt(field).toExactJsonInt() }
                        }
                        if (
                            current != null && ability != null && currentValues != null &&
                            currentValues.values.all { it != null }
                        ) {
                            structuredSettingCurrentValues.putIfAbsent(key, JSONObject(current.toString()))
                            WB_SHIFT_FIELDS.forEach { field ->
                                val fieldValues = ability.optJSONObject(field)?.toBoundedIntegerRangeValues().orEmpty()
                                val currentValue = currentValues.getValue(field)!!.toString()
                                if (fieldValues.size >= 2 && currentValue in fieldValues) {
                                    val virtualKey = "$key.$field"
                                    structuredSettingPathsByKey.putIfAbsent(virtualKey, settingPath)
                                    structuredSettingValuesByKey.putIfAbsent(virtualKey, fieldValues.toSet())
                                }
                            }
                        }
                    }
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

        candidateKeys.singleOrNull()
            ?.takeIf(structuredSettingPathsByKey::containsKey)
            ?.let { key ->
                putStructuredSettingValue(key, value)
                return
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

    private suspend fun putStructuredSettingValue(key: String, value: String) {
        val (baseKey, field) = key.splitStructuredSettingKey()
            ?: error("Unsupported structured camera setting '$key'.")
        val advertisedValues = structuredSettingValuesByKey[key].orEmpty()
        if (value !in advertisedValues) {
            error("Value '$value' is not advertised for $key.")
        }
        val current = structuredSettingCurrentValues[baseKey]
            ?: error("Camera did not return the current value for $baseKey.")
        val encodedValue: Any = if (baseKey == WB_SHIFT_SETTING_KEY) {
            WB_SHIFT_FIELDS.forEach { requiredField ->
                current.opt(requiredField).toExactJsonInt()
                    ?: error("Camera returned an invalid $WB_SHIFT_SETTING_KEY.$requiredField value.")
            }
            value.toIntOrNull() ?: error("Value '$value' is not an integer for $key.")
        } else {
            value
        }
        val updated = JSONObject(current.toString()).put(field, encodedValue)
        if (baseKey == IMAGE_QUALITY_SETTING_KEY) {
            val activeFormats = IMAGE_QUALITY_FIELDS.mapNotNull { format ->
                updated.optString(format).takeIf { it.isNotBlank() }
            }
            if (activeFormats.isNotEmpty() && activeFormats.all { it.equals("none", ignoreCase = true) }) {
                error("At least one still image format must remain enabled.")
            }
        }
        val path = structuredSettingPathsByKey.getValue(key)
        putOk(path, JSONObject().put("value", updated))
        structuredSettingCurrentValues[baseKey] = updated
    }

    private fun writableSetting(settings: JSONObject?, candidateKeys: List<String>): JSONObject? {
        if (settings == null) return null
        val key = candidateKeys.firstOrNull(settingPathsByKey::containsKey) ?: return null
        return settings.optJSONObject(key)
    }

    private suspend fun getJson(path: String): JSONObject = requestJson(
        Request.Builder().url("$baseUrl$path").get().build(),
    )

    private suspend fun getText(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl$path").get().header("Accept", "text/plain").build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CcapiHttpException(
                    statusCode = response.code,
                    message = "Camera request failed: ${request.method} ${request.url} returned HTTP ${response.code}\nBody: $body",
                )
            }
            body
        }
    }

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
            val isDetailedFrame = sourceUrl.contains("/shooting/liveview/flipdetail") && sourceUrl.contains("kind=both")
            if (isDetailedFrame) {
                latestLiveViewGeometry = null
            }
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

                val detailed = if (isDetailedFrame) {
                    parseDetailedLiveView(body.byteStream().readBoundedBytes(MAX_LIVE_VIEW_SCAN_BYTES))
                } else {
                    null
                }
                if (detailed?.geometry != null) {
                    latestLiveViewGeometry = detailed.geometry
                }

                LiveViewFrame(
                    bytes = when {
                        detailed?.image != null -> detailed.image
                        detailed != null -> error("Detailed Live View response did not contain an image packet.")
                        else -> readFirstJpegFrame(body.byteStream())
                    },
                    contentType = contentType,
                    sourceUrl = sourceUrl,
                )
            }
        }

    private fun parseDetailedLiveView(payload: ByteArray): CcapiDetailedLiveView {
        var offset = 0
        var image: ByteArray? = null
        var geometry: CcapiLiveViewGeometry? = null

        while (offset + CCAPI_DETAIL_OVERHEAD_BYTES <= payload.size) {
            if ((payload[offset].toInt() and 0xFF) != 0xFF || (payload[offset + 1].toInt() and 0xFF) != 0x00) break
            val type = payload[offset + 2].toInt() and 0xFF
            val size = ((payload[offset + 3].toInt() and 0xFF) shl 24) or
                ((payload[offset + 4].toInt() and 0xFF) shl 16) or
                ((payload[offset + 5].toInt() and 0xFF) shl 8) or
                (payload[offset + 6].toInt() and 0xFF)
            if (size < 0 || size > MAX_LIVE_VIEW_SCAN_BYTES) break
            val dataStart = offset + CCAPI_DETAIL_HEADER_BYTES
            val dataEnd = dataStart + size
            if (dataEnd + CCAPI_DETAIL_FOOTER_BYTES > payload.size) break
            if ((payload[dataEnd].toInt() and 0xFF) != 0xFF || (payload[dataEnd + 1].toInt() and 0xFF) != 0xFF) break
            val data = payload.copyOfRange(dataStart, dataEnd)
            when (type) {
                CCAPI_DETAIL_IMAGE_TYPE -> image = readFirstJpegFrame(data.inputStream())
                CCAPI_DETAIL_INFO_TYPE -> geometry = parseLiveViewGeometry(data)
            }
            offset = dataEnd + CCAPI_DETAIL_FOOTER_BYTES
        }

        if (image == null && geometry == null) {
            error("Detailed Live View response did not contain a valid Canon image or info packet.")
        }
        return CcapiDetailedLiveView(image = image, geometry = geometry)
    }

    private fun parseLiveViewGeometry(payload: ByteArray): CcapiLiveViewGeometry? {
        if (payload.size > MAX_LIVE_VIEW_INFO_BYTES) return null
        val root = runCatching { JSONObject(String(payload, StandardCharsets.UTF_8)) }.getOrNull() ?: return null

        fun find(node: JSONObject): CcapiLiveViewGeometry? {
            val image = node.optJSONObject("image")
            if (image != null) {
                val keys = listOf("positionx", "positiony", "positionwidth", "positionheight")
                if (keys.all(image::has)) {
                    val width = image.optInt("positionwidth", 0)
                    val height = image.optInt("positionheight", 0)
                    if (width > 0 && height > 0) {
                        return CcapiLiveViewGeometry(
                            positionX = image.optInt("positionx"),
                            positionY = image.optInt("positiony"),
                            positionWidth = width,
                            positionHeight = height,
                        )
                    }
                }
            }
            val keys = node.keys()
            while (keys.hasNext()) {
                val child = node.optJSONObject(keys.next()) ?: continue
                find(child)?.let { return it }
            }
            return null
        }

        return find(root)
    }

    private suspend fun cameraLiveViewPosition(
        x: Double,
        y: Double,
        feature: CameraFeature,
    ): Pair<Int, Int> {
        require(x in 0.0..1.0 && y in 0.0..1.0) {
            "${feature.label} coordinates must be normalized from 0 through 1."
        }
        if (latestLiveViewGeometry == null) {
            detailedLiveViewOperation()?.let { operation ->
                val sourceUrl = "$baseUrl${operation.path}?kind=both".withCacheBust(System.nanoTime())
                val request = Request.Builder()
                    .url(sourceUrl)
                    .get()
                    .header("Accept", "image/jpeg,image/*,*/*")
                    .header("Cache-Control", "no-cache")
                    .build()
                requestLiveViewFrame(request, sourceUrl)
            }
        }
        val geometry = latestLiveViewGeometry
            ?: error("${feature.label} needs Canon detailed Live View position metadata, but the camera returned none.")
        return geometry.cameraPosition(x, y)
    }

    private fun InputStream.readBoundedBytes(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) {
                error("Detailed Live View response exceeded $maxBytes bytes.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
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
        const val CCAPI_DETAIL_IMAGE_TYPE = 0x00
        const val CCAPI_DETAIL_INFO_TYPE = 0x01
        const val CCAPI_DETAIL_HEADER_BYTES = 7
        const val CCAPI_DETAIL_FOOTER_BYTES = 2
        const val CCAPI_DETAIL_OVERHEAD_BYTES = CCAPI_DETAIL_HEADER_BYTES + CCAPI_DETAIL_FOOTER_BYTES
        const val MAX_LIVE_VIEW_SCAN_BYTES = 16 * 1024 * 1024
        const val MAX_LIVE_VIEW_FRAME_BYTES = 12 * 1024 * 1024
        const val MAX_LIVE_VIEW_INFO_BYTES = 1024 * 1024
        const val MAX_ERROR_BODY_CHARS = 2_000
        const val HALF_PRESS_DURATION_MILLIS = 350L
        const val MAX_MEDIA_ITEMS = 500
        const val MAX_MEDIA_PAGES = 100
        const val MAX_MEDIA_TREE_DEPTH = 4
        const val MAX_MEDIA_THUMBNAIL_BYTES = 8 * 1024 * 1024
        const val MAX_MEDIA_PREVIEW_BYTES = 32 * 1024 * 1024
        const val MEDIA_TRANSFER_BUFFER_BYTES = 64 * 1024
        const val MEDIA_PROGRESS_INTERVAL_BYTES = 512 * 1024L
        const val MEDIA_SNIFF_BYTES = 64L
    }
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() <= maxBytes - count) {
            "Camera thumbnail exceeded $maxBytes bytes."
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun ByteArray.detectImageContentType(): String? = when {
    size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte() -> "image/jpeg"
    size >= 8 && copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
    size >= 6 && String(this, 0, 6, StandardCharsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
    size >= 12 && String(this, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
        String(this, 8, 4, StandardCharsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> null
}

private fun URI.effectivePort(): Int = when {
    port >= 0 -> port
    scheme.equals("https", ignoreCase = true) -> 443
    else -> 80
}

private fun URI.hasSameOriginAs(other: URI): Boolean =
    scheme.equals(other.scheme, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        effectivePort() == other.effectivePort()

private fun String.hasTraversalSegment(): Boolean =
    split('/').any { it == "." || it == ".." }

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
        storageTotalBytes = media.optNullableLong("total_bytes") ?: media.optNullableLong("totalBytes"),
        storageFreeBytes = media.optNullableLong("free_bytes") ?: media.optNullableLong("freeBytes"),
        storageFreeImages = media.optNullableLong("free_images") ?: media.optNullableLong("freeImages"),
        storageDeviceCount = media.optNullableInt("devices"),
        bulbExposureActive = optNullableBoolean("bulb_exposure_active")
            ?: optNullableBoolean("bulbExposureActive"),
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
            CameraFeature.BULB_EXPOSURE,
            CameraFeature.AUTOFOCUS,
            CameraFeature.SHUTTER_HALF_PRESS,
            CameraFeature.MEDIA_BROWSER,
            CameraFeature.MEDIA_THUMBNAIL,
            CameraFeature.MEDIA_PREVIEW,
            CameraFeature.MEDIA_DOWNLOAD,
            CameraFeature.MEDIA_DELETE,
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
        if (key == IMAGE_QUALITY_SETTING_KEY) {
            val current = setting.optJSONObject("value") ?: continue
            val ability = setting.optJSONObject("ability") ?: continue
            IMAGE_QUALITY_FIELDS.forEach { field ->
                val values = ability.optJSONArray(field)?.toStringList().orEmpty()
                    .filter { it.isNotBlank() }
                    .distinct()
                val value = current.optString(field)
                if (values.size >= 2 && value.isNotBlank()) {
                    val virtualKey = "$key.$field"
                    controls.add(
                        CameraSettingControl(
                            key = virtualKey,
                            label = virtualKey.toSettingLabel(),
                            value = value,
                            values = values,
                        )
                    )
                }
            }
            continue
        }
        if (key == WB_SHIFT_SETTING_KEY) {
            val current = setting.optJSONObject("value") ?: continue
            val ability = setting.optJSONObject("ability") ?: continue
            val currentValues = WB_SHIFT_FIELDS.associateWith { field -> current.opt(field).toExactJsonInt() }
            if (currentValues.values.any { it == null }) continue
            WB_SHIFT_FIELDS.forEach { field ->
                val values = ability.optJSONObject(field)?.toBoundedIntegerRangeValues().orEmpty()
                val value = currentValues.getValue(field)!!.toString()
                if (values.size >= 2 && value in values) {
                    val virtualKey = "$key.$field"
                    controls.add(
                        CameraSettingControl(
                            key = virtualKey,
                            label = virtualKey.toSettingLabel(),
                            value = value,
                            values = values,
                        )
                    )
                }
            }
            continue
        }
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
        "stillimagequality.raw" -> "RAW quality"
        "stillimagequality.jpeg" -> "JPEG quality"
        "stillimagequality.heif" -> "HEIF quality"
        "wbshift.ba" -> "WB shift B/A"
        "wbshift.mg" -> "WB shift M/G"
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

private fun String.splitStructuredSettingKey(): Pair<String, String>? {
    val separator = lastIndexOf('.')
    if (separator <= 0 || separator == lastIndex) return null
    val base = substring(0, separator)
    val field = substring(separator + 1)
    return when {
        base == IMAGE_QUALITY_SETTING_KEY && field in IMAGE_QUALITY_FIELDS -> base to field
        base == WB_SHIFT_SETTING_KEY && field in WB_SHIFT_FIELDS -> base to field
        else -> null
    }
}

private const val IMAGE_QUALITY_SETTING_KEY = "stillimagequality"
private val IMAGE_QUALITY_FIELDS = listOf("raw", "jpeg", "heif")
private const val WB_SHIFT_SETTING_KEY = "wbshift"
private val WB_SHIFT_FIELDS = listOf("ba", "mg")
private const val MAX_STRUCTURED_SETTING_OPTIONS = 256

private fun Any?.toExactJsonInt(): Int? = when (this) {
    is Byte -> toInt()
    is Short -> toInt()
    is Int -> this
    is Long -> takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    else -> null
}

private fun JSONObject.toBoundedIntegerRangeValues(): List<String> {
    val minimum = opt("min").toExactJsonInt() ?: return emptyList()
    val maximum = opt("max").toExactJsonInt() ?: return emptyList()
    val step = opt("step").toExactJsonInt() ?: return emptyList()
    if (step <= 0 || minimum > maximum) return emptyList()
    val count = ((maximum.toLong() - minimum.toLong()) / step.toLong()) + 1L
    if (count !in 1..MAX_STRUCTURED_SETTING_OPTIONS.toLong()) return emptyList()
    return List(count.toInt()) { index -> (minimum.toLong() + index.toLong() * step).toString() }
}

private fun String.splitCamelCaseWords(): List<String> =
    replace(Regex("([a-z])([A-Z])"), "$1 $2").split(" ")

private fun org.json.JSONArray.toStringList(): List<String> =
    List(length()) { index -> getString(index) }

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optNullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

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

private data class ParsedStorageInfo(
    val available: Boolean,
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
    val freeImages: Long? = null,
    val devices: Int = 0,
)

private fun parseStorageInfo(jsonStr: String): ParsedStorageInfo {
    return try {
        val trimmed = jsonStr.trim()
        val cards = mutableListOf<JSONObject>()
        if (trimmed.startsWith("[")) {
            val array = org.json.JSONArray(trimmed)
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let(cards::add)
            }
        } else {
            val obj = JSONObject(trimmed)
            for (key in listOf("storagelist", "storage")) {
                obj.optJSONArray(key)?.let { array ->
                    for (i in 0 until array.length()) array.optJSONObject(i)?.let(cards::add)
                }
            }
            if (obj.optJSONArray("path")?.length()?.let { it > 0 } == true) {
                return ParsedStorageInfo(available = true, devices = 1)
            }
            if (cards.isEmpty() && (obj.has("name") || obj.has("accesscapability") || obj.has("status"))) {
                cards += obj
            }
        }
        val usable = cards.filter(::isSingleCardReady)
        ParsedStorageInfo(
            available = usable.isNotEmpty(),
            totalBytes = sumStorageValues(usable, "maxsize", "capacity", "totalbytes", "totalsize"),
            freeBytes = sumStorageValues(usable, "spacesize", "free", "freebytes", "freespace"),
            freeImages = sumStorageValues(usable, "freeimages", "remainingimages", "numberofimages"),
            devices = usable.size,
        )
    } catch (_: Exception) {
        ParsedStorageInfo(available = false)
    }
}

private fun sumStorageValues(cards: List<JSONObject>, vararg keys: String): Long? {
    val values = cards.mapNotNull { card ->
        keys.firstNotNullOfOrNull { key -> positiveStorageValue(card, key) }
    }
    if (values.isEmpty()) return null
    return values.fold(0L) { total, value ->
        if (value > Long.MAX_VALUE - total) Long.MAX_VALUE else total + value
    }
}

private fun positiveStorageValue(card: JSONObject, key: String): Long? {
    if (!card.has(key) || card.isNull(key)) return null
    val numberValue = card.optLong(key, Long.MIN_VALUE)
    if (numberValue != Long.MIN_VALUE) return numberValue.takeIf { it > 0 }
    val text = card.optString(key, "").trim()
    if (text.startsWith('-')) return null
    return text.filter(Char::isDigit).toLongOrNull()?.takeIf { it > 0 }
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
    return positiveStorageValue(card, key) != null
}
