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
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor

private const val MAX_CCAPI_EVENT_BODY_BYTES = 256 * 1024
private const val MAX_CCAPI_EVENT_KEYS = 64
private const val MAX_CCAPI_EVENT_KEY_CHARS = 128
private const val MAX_DEVICE_STATUS_TEXT_CHARS = 512
private const val CCAPI_EVENT_READ_TIMEOUT_SECONDS = 40L
private const val CCAPI_EVENT_CALL_TIMEOUT_SECONDS = 45L
private const val CCAPI_NO_API_LIST_VALUE = "No list of APIs"
private const val CCAPI_DEVELOPER_API_PATH = "/ccapi/ver100/topurlfordev"
private val CANON_DATETIME_FORMATTER = DateTimeFormatter.ofPattern(
    "EEE, dd MMM yyyy HH:mm:ss xx",
    Locale.US,
)

private data class CcapiApiOperation(
    val method: String,
    val path: String,
)

private data class CcapiMultipartOperations(
    val startLiveView: CcapiApiOperation,
    val stopLiveView: CcapiApiOperation,
    val openStream: CcapiApiOperation,
    val closeStream: CcapiApiOperation,
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

private data class CcapiRecordableStatus(
    val shots: Long?,
    val remainingSeconds: Long?,
)

private data class CcapiLiveViewMagnificationSetting(
    val current: LiveViewMagnification,
    val abilities: List<LiveViewMagnification>,
)

private data class StrictNullableLong(
    val value: Long?,
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
    private val eventHttpClient = this.httpClient.newBuilder()
        .readTimeout(CCAPI_EVENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CCAPI_EVENT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val multipartHttpClient = this.httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val activeEventCall = AtomicReference<Call?>(null)

    var isRealCamera = false
        private set
    var apiVersionPrefix = "/ccapi/ver100"
        private set

    private var apiVersionPrefixes = listOf("/ccapi/ver100")
    private var isRecording: Boolean? = null
    private var bulbExposureActive = false
    private var latestTemperatureStatus: CameraTemperatureStatus? = null
    private val settingPathsByKey = mutableMapOf<String, String>()
    private val settingValuesByKey = mutableMapOf<String, Set<String>>()
    private val structuredSettingPathsByKey = mutableMapOf<String, String>()
    private val structuredSettingValuesByKey = mutableMapOf<String, Set<String>>()
    private val structuredSettingCurrentValues = mutableMapOf<String, JSONObject>()
    private var cameraSleepWritePath: String? = null
    private var fileNamingState: CameraFileNaming? = null
    private var fileNamingLoaded = false
    private var liveViewMagnificationSetting: CcapiLiveViewMagnificationSetting? = null
    private val apiOperations = linkedSetOf<CcapiApiOperation>()
    private val observedFeatures = mutableSetOf<CameraFeature>()
    private val discoveryTrace = mutableListOf<CameraDiscoveryAttempt>()
    private var discoveryTraceTruncated = false
    private var enforceAdvertisedOperations = false
    private var settingsLoaded = false
    private var discoverySource = "unknown"
    private var liveViewSizeControlSupported = true
    private var activeLiveViewSize = LiveViewSize.MEDIUM
    private var latestLiveViewGeometry: CcapiLiveViewGeometry? = null
    private var activeLiveViewSource: LiveViewSource? = null
    private var multipartLiveViewSession: CcapiMultipartLiveViewSession? = null
    private var simulatorEventSequence = 0L

    var nativeLiveViewSession: NativeLiveViewSession? = null
        private set

    fun observedFeatureSnapshot(): Set<CameraFeature> = observedFeatures.toSet()

    fun currentLiveViewSource(): LiveViewSource? = activeLiveViewSource

    suspend fun close() {
        runCatching { stopEventPolling() }
        if (bulbExposureActive) {
            runCatching { stopBulbExposure() }
        }
        runCatching { stopLiveView() }
    }

    suspend fun initialize() {
        discoveryTrace.clear()
        discoveryTraceTruncated = false
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
        val success1 = discoverApiAt("/ccapi", errors)

        if (success1) {
            isRealCamera = true
            return
        }

        // 2. Try GET /ccapi/
        val success2 = discoverApiAt("/ccapi/", errors)

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
                            val identity = response.body?.string()?.let { body ->
                                runCatching { JSONObject(body) }.getOrNull()
                            }
                            apiVersionPrefixes = listOf(prefix)
                            apiVersionPrefix = prefix
                            discoverySource = "GET $prefix/deviceinformation (identity fallback)"
                            recordDiscoveryResponse(
                                endpoint = "GET $prefix/deviceinformation",
                                outcome = "IDENTITY",
                                response = identity,
                                httpStatus = response.code,
                                operationCount = 0,
                            )
                            true
                        } else {
                            recordDiscoveryAttempt(
                                CameraDiscoveryAttempt(
                                    endpoint = "GET $prefix/deviceinformation",
                                    outcome = "HTTP_ERROR",
                                    httpStatus = response.code,
                                ),
                            )
                            errors.add("GET $prefix/deviceinformation: HTTP ${response.code}")
                            false
                        }
                    }
                }
            } catch (e: Exception) {
                recordDiscoveryFailure("GET $prefix/deviceinformation", e)
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

    private suspend fun discoverApiAt(path: String, errors: MutableList<String>): Boolean {
        return try {
            val rootDiscovery = try {
                getJson(path)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                recordDiscoveryFailure("GET $path", error)
                throw error
            }
            if (rootDiscovery.optString("value") != CCAPI_NO_API_LIST_VALUE) {
                parseDiscoveryResponse(rootDiscovery, "GET $path")
                recordDiscoveryResponse(
                    endpoint = "GET $path",
                    outcome = if (apiOperations.isNotEmpty()) "OPERATIONS" else "ZERO_OPERATIONS",
                    response = rootDiscovery,
                )
                if (apiOperations.isNotEmpty()) return true
            } else {
                recordDiscoveryResponse("GET $path", "NO_API_LIST", rootDiscovery)
            }

            val developerEndpoint = "GET $CCAPI_DEVELOPER_API_PATH"
            val developerDiscovery = try {
                getJson(CCAPI_DEVELOPER_API_PATH)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                recordDiscoveryFailure(developerEndpoint, error)
                throw error
            }
            parseDiscoveryResponse(
                developerDiscovery,
                "GET $CCAPI_DEVELOPER_API_PATH (Canon developer API fallback)",
            )
            recordDiscoveryResponse(
                endpoint = developerEndpoint,
                outcome = if (apiOperations.isNotEmpty()) "OPERATIONS" else "ZERO_OPERATIONS",
                response = developerDiscovery,
            )
            check(apiOperations.isNotEmpty()) {
                "Camera developer API $CCAPI_DEVELOPER_API_PATH did not advertise any valid operations."
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errors.add("GET $path failed: ${error.message}")
            false
        }
    }

    private fun parseDiscoveryResponse(json: JSONObject, source: String) {
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

    private fun recordDiscoveryResponse(
        endpoint: String,
        outcome: String,
        response: JSONObject?,
        httpStatus: Int? = 200,
        operationCount: Int = apiOperations.size,
    ) {
        val rawKeys = mutableListOf<String>()
        response?.keys()?.let { keys ->
            while (keys.hasNext()) {
                keys.next()
                    .takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) }
                    ?.let(rawKeys::add)
            }
        }
        val cleanKeys = rawKeys.distinct().sorted()
        val versions = linkedSetOf<String>()
        response?.optJSONArray("api")?.let { array ->
            repeat(array.length()) { index ->
                extractApiVersion(array.optString(index))?.let(versions::add)
            }
        }
        cleanKeys.filter { it.matches(Regex("""ver\d+""")) }.forEach(versions::add)
        response?.optString("version")
            ?.takeIf { it.matches(Regex("""ver\d+""")) }
            ?.let(versions::add)
        val cleanVersions = versions
            .map { it.take(MAX_DISCOVERY_TRACE_KEY_CHARS) }
            .distinct()
            .sortedDescending()
        recordDiscoveryAttempt(
            CameraDiscoveryAttempt(
                endpoint = endpoint.take(128),
                outcome = outcome.take(64),
                httpStatus = httpStatus,
                responseKeys = cleanKeys.take(MAX_DISCOVERY_TRACE_KEYS),
                protocolVersions = cleanVersions.take(MAX_DISCOVERY_TRACE_KEYS),
                advertisedOperationCount = operationCount.coerceAtLeast(0),
                truncated = cleanKeys.size > MAX_DISCOVERY_TRACE_KEYS ||
                    cleanVersions.size > MAX_DISCOVERY_TRACE_KEYS,
            ),
        )
    }

    private fun recordDiscoveryFailure(endpoint: String, error: Exception) {
        val status = (error as? CcapiHttpException)?.statusCode
        recordDiscoveryAttempt(
            CameraDiscoveryAttempt(
                endpoint = endpoint.take(128),
                outcome = if (status != null) "HTTP_ERROR" else "REQUEST_ERROR",
                httpStatus = status,
            ),
        )
    }

    private fun recordDiscoveryAttempt(attempt: CameraDiscoveryAttempt) {
        if (discoveryTrace.size < MAX_DISCOVERY_TRACE_ATTEMPTS) {
            discoveryTrace += attempt
        } else {
            discoveryTraceTruncated = true
        }
    }

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

            val recordableJson = apiOperation("GET", "/shooting/information/recordable")
                ?.let { operation -> getFirstJson(listOf(operation.path)) }
            val recordableStatus = recordableJson?.toCanonRecordableStatusOrNull()
            if (recordableStatus != null) {
                observedFeatures.add(CameraFeature.RECORDABLE_STATUS)
            } else {
                observedFeatures.remove(CameraFeature.RECORDABLE_STATUS)
            }

            val lensJson = apiOperation("GET", "/devicestatus/lens")
                ?.let { operation -> getFirstJson(listOf(operation.path)) }
            val lensStatus = lensJson?.toCanonLensStatusOrNull()
            if (lensStatus != null) {
                observedFeatures.add(CameraFeature.LENS_STATUS)
            } else {
                observedFeatures.remove(CameraFeature.LENS_STATUS)
            }

            val temperatureJson = apiOperation("GET", "/devicestatus/temperature")
                ?.let { operation -> getFirstJson(listOf(operation.path)) }
            temperatureJson?.toTemperatureStatusOrNull()?.let { temperatureStatus ->
                latestTemperatureStatus = temperatureStatus
                observedFeatures.add(CameraFeature.TEMPERATURE_STATUS)
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
                recordableShots = recordableStatus?.shots,
                remainingRecordingSeconds = recordableStatus?.remainingSeconds,
                rawBatteryJson = batteryJson?.toString() ?: "null",
                rawStorageJson = storageJson?.toString() ?: "null",
                rawRecordableJson = recordableJson?.toString() ?: "null",
                bulbExposureActive = bulbExposureActive,
                lens = lensStatus,
                temperature = latestTemperatureStatus,
            )
        } else {
            getJson("/ccapi/status").toCameraStatus().also {
                latestTemperatureStatus = it.temperature
                observedFeatures.addAll(setOf(CameraFeature.BATTERY_STATUS, CameraFeature.STORAGE_STATUS))
                if (it.recordableShots != null || it.remainingRecordingSeconds != null) {
                    observedFeatures.add(CameraFeature.RECORDABLE_STATUS)
                } else {
                    observedFeatures.remove(CameraFeature.RECORDABLE_STATUS)
                }
                if (it.lens != null) observedFeatures.add(CameraFeature.LENS_STATUS)
                else observedFeatures.remove(CameraFeature.LENS_STATUS)
                if (it.temperature != null) observedFeatures.add(CameraFeature.TEMPERATURE_STATUS)
                else observedFeatures.remove(CameraFeature.TEMPERATURE_STATUS)
            }
        }
    }

    suspend fun capabilities(): CameraCapabilities {
        return if (isRealCamera) {
            val settings = loadShootingSettings()
            val fileNaming = loadFileNaming()

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
            if (advancedSettings.any { it.key == ZOOM_SETTING_KEY }) {
                supportedFeatures.add(CameraFeature.ZOOM_CONTROL)
            }
            if (advancedSettings.any { it.key == MOVIE_MODE_SETTING_KEY }) {
                supportedFeatures.add(CameraFeature.MOVIE_MODE_CONTROL)
            }
            if (advancedSettings.any { it.key in CARD_SELECTION_SETTING_KEYS }) {
                supportedFeatures.add(CameraFeature.CARD_SELECTION_CONTROL)
            }
            if (advancedSettings.any { it.key in SOUND_RECORDING_SETTING_KEYS }) {
                supportedFeatures.add(CameraFeature.SOUND_RECORDING_CONTROL)
            }
            if (advancedSettings.any { it.key == SOUND_RECORDING_LEVEL_SETTING_KEY }) {
                supportedFeatures.add(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL)
            }
            if (advancedSettings.any { it.key == FOCUS_BRACKETING_SETTING_KEY }) {
                supportedFeatures.add(CameraFeature.FOCUS_BRACKETING_CONTROL)
            }
            if (advancedSettings.any { it.key in MOVIE_SETTING_KEYS }) {
                supportedFeatures.add(CameraFeature.MOVIE_SETTINGS_CONTROL)
            }
            if (
                advancedSettings.any { it.key == DIRECTORY_SELECTION_SETTING_KEY } &&
                directoryOperations() != null
            ) {
                supportedFeatures.add(CameraFeature.DIRECTORY_CONTROL)
            }
            if (fileNaming != null) supportedFeatures.add(CameraFeature.FILE_NAMING_CONTROL)
            if (advancedSettings.isNotEmpty()) supportedFeatures.add(CameraFeature.ADVANCED_SETTINGS)
            val supportsJpegLiveView = supportsCompleteLiveView()
            val supportsMultipartLiveView = supportsMultipartLiveView()
            val supportsRtpLiveView = supportsRtpLiveView()
            if (
                supportsJpegLiveView || supportsMultipartLiveView || supportsRtpLiveView ||
                CameraFeature.LIVE_VIEW in observedFeatures
            ) {
                supportedFeatures.add(CameraFeature.LIVE_VIEW)
            }
            if (supportsJpegLiveView) {
                supportedFeatures.add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
            }
            if (supportsRtpLiveView) {
                supportedFeatures.add(CameraFeature.LIVE_VIEW_RTP)
            }
            if (supportsMultipartLiveView) {
                supportedFeatures.add(CameraFeature.LIVE_VIEW_MULTIPART)
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
            if (liveViewMagnificationSetting != null) {
                supportedFeatures.add(CameraFeature.LIVE_VIEW_MAGNIFICATION)
            }
            if (supportsApi("GET", "/contents")) {
                supportedFeatures.add(CameraFeature.MEDIA_BROWSER)
                supportedFeatures.add(CameraFeature.MEDIA_THUMBNAIL)
                supportedFeatures.add(CameraFeature.MEDIA_PREVIEW)
                supportedFeatures.add(CameraFeature.MEDIA_DOWNLOAD)
            }
            if (supportsMediaDelete()) supportedFeatures.add(CameraFeature.MEDIA_DELETE)
            if (supportsMediaModify()) {
                supportedFeatures.add(CameraFeature.MEDIA_PROTECT)
                supportedFeatures.add(CameraFeature.MEDIA_RATING)
                supportedFeatures.add(CameraFeature.MEDIA_ROTATE)
            }
            if (eventPollingOperations() != null) {
                supportedFeatures.add(CameraFeature.EVENT_POLLING)
            }
            if (cameraClockOperations() != null) {
                supportedFeatures.add(CameraFeature.CAMERA_CLOCK_SYNC)
            }
            if (sensorCleaningOperation() != null) {
                supportedFeatures.add(CameraFeature.SENSOR_CLEANING)
            }
            if (cameraSleepWritePath != null) {
                supportedFeatures.add(CameraFeature.CAMERA_SLEEP)
            }

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
                fileNaming = fileNaming,
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

    suspend fun pollEvent(): CameraEvent {
        val event = if (isRealCamera) {
            val operations = eventPollingOperations()
                ?: throw UnsupportedOperationException(
                    "${CameraFeature.EVENT_POLLING.label} is not supported by this camera's advertised CCAPI.",
                )
            val pollOperation = operations.first
            val timeoutParameter = if (pollOperation.apiVersionNumber() >= 110) {
                "timeout" to "long"
            } else {
                "continue" to "on"
            }
            val url = "$baseUrl${pollOperation.path}".toHttpUrl().newBuilder()
                .addQueryParameter(timeoutParameter.first, timeoutParameter.second)
                .build()
            val json = requestEventJson(
                Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .build(),
            )
            CameraEvent(changedKeys = json.safeTopLevelKeys())
        } else {
            val url = "$baseUrl/ccapi/events".toHttpUrl().newBuilder()
                .addQueryParameter("after", simulatorEventSequence.toString())
                .build()
            val json = requestEventJson(Request.Builder().url(url).get().build())
            simulatorEventSequence = json.optLong("sequence", simulatorEventSequence)
                .coerceAtLeast(simulatorEventSequence)
            CameraEvent(
                changedKeys = json.optJSONArray("keys")?.toStringList()
                    .orEmpty()
                    .asSequence()
                    .map(String::safeEventKey)
                    .filter(String::isNotBlank)
                    .take(MAX_CCAPI_EVENT_KEYS)
                    .toSet(),
            )
        }
        observedFeatures.add(CameraFeature.EVENT_POLLING)
        return event
    }

    suspend fun stopEventPolling() {
        activeEventCall.getAndSet(null)?.cancel()
        if (!isRealCamera) return
        val (_, stopOperation) = eventPollingOperations() ?: return
        withContext(NonCancellable) {
            runCatching { deleteOk(stopOperation.path) }
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
            when {
                key.equals(ZOOM_SETTING_KEY, ignoreCase = true) -> postZoomValue(value)
                key.equals(MOVIE_MODE_SETTING_KEY, ignoreCase = true) -> postMovieModeValue(value)
                key.lowercase() in CARD_SELECTION_SETTING_KEYS -> {
                    loadShootingSettings()
                    putSettingValue(listOf(key.lowercase()), value)
                }
                key.lowercase() in DEVICE_FUNCTION_SETTING_KEYS -> {
                    loadShootingSettings()
                    putSettingValue(listOf(key.lowercase()), value)
                }
                key.lowercase() in SOUND_RECORDING_SETTING_KEYS -> {
                    loadShootingSettings()
                    putSettingValue(listOf(key.lowercase()), value)
                }
                key.equals(SOUND_RECORDING_LEVEL_SETTING_KEY, ignoreCase = true) -> {
                    loadShootingSettings()
                    putIntegerSettingValue(SOUND_RECORDING_LEVEL_SETTING_KEY, value)
                }
                key.lowercase() in FOCUS_BRACKETING_STRING_SETTING_KEYS -> {
                    loadShootingSettings()
                    putSettingValue(listOf(key.lowercase()), value)
                }
                key.lowercase() in FOCUS_BRACKETING_INTEGER_SETTING_KEYS -> {
                    loadShootingSettings()
                    putIntegerSettingValue(key.lowercase(), value)
                }
                key.lowercase() in MOVIE_SETTING_KEYS -> {
                    loadShootingSettings()
                    putSettingValue(listOf(key.lowercase()), value)
                }
                key.equals(DIRECTORY_SELECTION_SETTING_KEY, ignoreCase = true) -> {
                    loadShootingSettings()
                    putSettingValue(listOf(DIRECTORY_SELECTION_SETTING_KEY), value)
                }
                else -> putSettingValue(listOf(key), value)
            }
            status()
        } else {
            when {
                key.equals(ZOOM_SETTING_KEY, ignoreCase = true) -> {
                    val zoom = value.toIntOrNull()
                        ?.takeIf { it.toString() == value }
                        ?: error("Zoom value must be an integer advertised by the camera.")
                    postJson("/ccapi/zoom", JSONObject().put("value", zoom))
                }
                key.equals(MOVIE_MODE_SETTING_KEY, ignoreCase = true) -> {
                    require(value in MOVIE_MODE_VALUES) { "Movie mode must be on or off." }
                    postOk("/ccapi/movie-mode", JSONObject().put("action", value))
                }
                key.equals(STILL_CARD_SELECTION_SETTING_KEY, ignoreCase = true) -> {
                    require(value in CARD_SELECTION_VALUES) { "Still-image card value is not supported." }
                    putOk("/ccapi/card-selection/stillimage", JSONObject().put("value", value))
                }
                key.equals(MOVIE_CARD_SELECTION_SETTING_KEY, ignoreCase = true) -> {
                    require(value in CARD_SELECTION_VALUES) { "Movie card value is not supported." }
                    putOk("/ccapi/card-selection/movie", JSONObject().put("value", value))
                }
                key.lowercase() in DEVICE_FUNCTION_SETTING_KEYS -> {
                    val canonical = key.lowercase()
                    val endpoint = DEVICE_FUNCTION_SETTING_ENDPOINTS.getValue(canonical)
                    require(value in endpoint.settingValues) {
                        "${canonical.toSettingLabel()} value is not supported."
                    }
                    putOk(endpoint.simulatorPath, JSONObject().put("value", value))
                }
                key.lowercase() in SOUND_RECORDING_SETTING_KEYS -> {
                    val canonical = key.lowercase()
                    require(value in SOUND_RECORDING_ENDPOINTS.getValue(canonical).values) {
                        "${canonical.toSettingLabel()} value is not supported."
                    }
                    putOk(
                        "/ccapi/${SOUND_RECORDING_ENDPOINTS.getValue(canonical).simulatorPath}",
                        JSONObject().put("value", value),
                    )
                }
                key.equals(SOUND_RECORDING_LEVEL_SETTING_KEY, ignoreCase = true) -> {
                    val level = value.toIntOrNull()
                        ?.takeIf { it.toString() == value && value in SOUND_RECORDING_LEVEL_VALUES }
                        ?: error("Sound recording level is not supported.")
                    putOk("/ccapi/sound-recording-level", JSONObject().put("value", level))
                }
                key.lowercase() in FOCUS_BRACKETING_STRING_SETTING_KEYS -> {
                    val canonical = key.lowercase()
                    require(value in FOCUS_BRACKETING_STRING_ENDPOINTS.getValue(canonical).values) {
                        "${canonical.toSettingLabel()} value is not supported."
                    }
                    putOk(
                        "/ccapi/${FOCUS_BRACKETING_STRING_ENDPOINTS.getValue(canonical).simulatorPath}",
                        JSONObject().put("value", value),
                    )
                }
                key.lowercase() in FOCUS_BRACKETING_INTEGER_SETTING_KEYS -> {
                    val canonical = key.lowercase()
                    val integer = value.toIntOrNull()
                        ?.takeIf {
                            it.toString() == value &&
                                value in FOCUS_BRACKETING_SIMULATOR_VALUES.getValue(canonical)
                        }
                        ?: error("${canonical.toSettingLabel()} is not supported.")
                    putOk(
                        "/ccapi/${FOCUS_BRACKETING_INTEGER_ENDPOINTS.getValue(canonical).simulatorPath}",
                        JSONObject().put("value", integer),
                    )
                }
                key.lowercase() in MOVIE_SETTING_KEYS -> {
                    val canonical = key.lowercase()
                    require(value in MOVIE_SIMULATOR_VALUES.getValue(canonical)) {
                        "${canonical.toSettingLabel()} value is not supported."
                    }
                    putOk(
                        "/ccapi/${MOVIE_SETTING_ENDPOINTS.getValue(canonical).simulatorPath}",
                        JSONObject().put("value", value),
                    )
                }
                key.equals(DIRECTORY_SELECTION_SETTING_KEY, ignoreCase = true) -> {
                    require(DIRECTORY_SELECTION_PATTERN.matches(value)) {
                        "Capture directory must be one of the directories advertised by the camera."
                    }
                    putOk("/ccapi/directory-selection", JSONObject().put("value", value))
                }
                else -> throw UnsupportedOperationException(
                    "${featureForSetting(key).label} is not supported by the simulator.",
                )
            }
            status()
        }
        observedFeatures.add(featureForSetting(key))
        return status
    }

    suspend fun createDirectory(name: String): String {
        require(DIRECTORY_CREATE_NAME_PATTERN.matches(name)) {
            "Directory name must be empty or exactly five uppercase letters, numbers, or underscores."
        }
        val response = if (isRealCamera) {
            val operations = directoryOperations()
                ?: throw UnsupportedOperationException(
                    "${CameraFeature.DIRECTORY_CONTROL.label} is not supported by this camera's advertised CCAPI ability.",
                )
            postJson(
                operations.create.path,
                JSONObject().put("directoryname", name),
            )
        } else {
            postJson("/ccapi/directory", JSONObject().put("directoryname", name))
        }
        val created = response.opt("directoryname") as? String
        require(created != null && DIRECTORY_CREATED_NAME_PATTERN.matches(created)) {
            "Camera returned an invalid created directory name."
        }
        observedFeatures.add(CameraFeature.DIRECTORY_CONTROL)
        if (isRealCamera) loadShootingSettings()
        return created
    }

    suspend fun setFileNaming(field: CameraFileNamingField, value: String): CameraFileNaming {
        val current = loadFileNaming()
            ?: error("Camera did not advertise a valid complete Canon file-naming endpoint group.")
        require(current.accepts(field, value)) {
            "Value '$value' is not valid for Canon file-naming field ${field.wireName}."
        }
        val updated = if (isRealCamera) {
            val operations = fileNamingOperations()
                ?: error("Camera did not advertise the complete Canon file-naming endpoint group.")
            val operation = operations.getValue(field).second
            val responseKey = FILE_NAMING_ENDPOINTS.getValue(field).responseKey
            val requestValue: Any = when (field) {
                CameraFileNamingField.MOVIE_REEL_NUMBER,
                CameraFileNamingField.MOVIE_CLIP_NUMBER,
                -> value.toInt()
                else -> value
            }
            val response = putJson(operation.path, JSONObject().put(responseKey, requestValue))
            check(response.opt(responseKey) == requestValue) {
                "Canon file-naming control returned an invalid update response."
            }
            fileNamingLoaded = false
            loadFileNaming(force = true)
                ?: error("Canon file-naming control returned an invalid state after update.")
        } else {
            putJson(
                "/ccapi/file-naming/${field.wireName}",
                JSONObject().put("value", value),
            ).toValidatedFileNaming()
                ?: error("Simulator returned an invalid file-naming state.")
        }
        check(updated.value(field) == value) {
            "Canon file-naming control did not return the requested value on refresh."
        }
        fileNamingState = updated
        fileNamingLoaded = true
        observedFeatures.add(CameraFeature.FILE_NAMING_CONTROL)
        return updated
    }

    suspend fun sleepCamera() {
        if (isRealCamera) {
            runCatching { stopEventPolling() }
            stopLiveView()
            loadShootingSettings()
            val path = cameraSleepWritePath
                ?: throw UnsupportedOperationException(
                    "${CameraFeature.CAMERA_SLEEP.label} is not supported by this camera's advertised CCAPI ability.",
                )
            putOk(
                path = path,
                payload = JSONObject().put("value", AUTO_POWER_OFF_IMMEDIATELY),
                expectedStatusCode = 202,
            )
        } else {
            postOk("/ccapi/camera-sleep", JSONObject())
        }
        observedFeatures.add(CameraFeature.CAMERA_SLEEP)
    }

    suspend fun cleanSensor(autoPowerOff: Boolean) {
        if (isRealCamera) {
            val operation = sensorCleaningOperation()
                ?: throw UnsupportedOperationException(
                    "${CameraFeature.SENSOR_CLEANING.label} is not supported by this camera's advertised CCAPI.",
                )
            runCatching { stopEventPolling() }
            stopLiveView()
            postOk(
                operation.path,
                JSONObject().put("autopoweroff", autoPowerOff),
                expectedStatusCode = 200,
            )
        } else {
            postOk(
                "/ccapi/sensor-cleaning",
                JSONObject().put("autopoweroff", autoPowerOff),
            )
        }
        observedFeatures.add(CameraFeature.SENSOR_CLEANING)
    }

    suspend fun syncCameraClock(): CameraStatus {
        if (!isRealCamera) {
            return postJson("/ccapi/clock/sync", JSONObject()).toCameraStatus().also {
                observedFeatures.add(CameraFeature.CAMERA_CLOCK_SYNC)
            }
        }
        val (read, write) = cameraClockOperations()
            ?: throw UnsupportedOperationException(
                "${CameraFeature.CAMERA_CLOCK_SYNC.label} is not supported by this camera's advertised CCAPI.",
            )
        val requested = ZonedDateTime.now()
        val daylight = requested.zone.rules.isDaylightSavings(requested.toInstant())
        val payload = JSONObject()
            .put("datetime", CANON_DATETIME_FORMATTER.format(requested))
            .put("dst", daylight)
        parseCameraClock(putJson(write.path, payload))
        val (reported, reportedDaylight) = parseCameraClock(getJson(read.path))
        check(abs(Duration.between(requested.toInstant(), reported.toInstant()).toMillis()) <= 10_000L) {
            "The camera did not report the requested date and time."
        }
        check(reportedDaylight == daylight) {
            "The camera did not report the requested daylight-saving state."
        }
        observedFeatures.add(CameraFeature.CAMERA_CLOCK_SYNC)
        return status()
    }

    suspend fun startRecording(): CameraStatus {
        refreshTemperatureStatusForRestrictedCommand()
        requireMovieRecordingAllowed()
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
        refreshTemperatureStatusForRestrictedCommand()
        requireStillCaptureAllowed()
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
        requireStillCaptureAllowed()
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
            val requestedDirection = direction.name.lowercase()
            val requestedStep = step.name.lowercase()
            val response = postJson(
                "/ccapi/focus/drive",
                JSONObject()
                    .put("direction", requestedDirection)
                    .put("step", requestedStep),
            )
            check(response.optBoolean("ok")) { "Simulator rejected manual focus drive." }
            check(response.optString("direction") == requestedDirection) {
                "Simulator returned a mismatched focus direction."
            }
            check(response.optString("step") == requestedStep) {
                "Simulator returned a mismatched focus step."
            }
            observedFeatures.add(CameraFeature.FOCUS_DRIVE)
            return FocusDriveResult(ok = true, direction = direction, step = step)
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

    suspend fun setLiveViewMagnification(
        magnification: LiveViewMagnification,
    ): LiveViewMagnificationResult {
        if (!isRealCamera) {
            val response = postJson(
                "/ccapi/liveview/magnification",
                JSONObject().put("value", magnification.value),
            )
            val accepted = response.opt("accepted") as? Boolean
                ?: response.opt("ok") as? Boolean
                ?: false
            val returnedValue = response.opt("value") as? Int
            val returned = LiveViewMagnification.entries.firstOrNull { it.value == returnedValue }
                ?: error("Simulator returned an invalid Live View magnification value.")
            check(accepted && returned == magnification) {
                "Simulator did not confirm the requested Live View magnification."
            }
            observedFeatures.add(CameraFeature.LIVE_VIEW_MAGNIFICATION)
            return LiveViewMagnificationResult(ok = true, magnification = returned)
        }

        check(activeLiveViewSource != null) {
            "Canon Live View magnification requires an active Live View session."
        }
        if (!settingsLoaded) loadShootingSettings()
        val operations = liveViewMagnificationOperations()
            ?: error("Camera did not advertise same-version GET and PUT Canon Live View magnification control.")
        val setting = liveViewMagnificationSetting
            ?: error("Camera did not return a valid Canon Live View magnification ability list.")
        require(magnification in setting.abilities) {
            "${magnification.value}x Live View magnification is not advertised by this camera."
        }

        putOk(
            operations.second.path,
            JSONObject().put("value", magnification.value.toString()),
        )
        val readback = getJson(operations.first.path).toValidatedLiveViewMagnificationSetting()
            ?: error("Camera returned an invalid Live View magnification readback.")
        check(readback.current == magnification) {
            "Camera accepted Live View magnification but reported ${readback.current.value}x instead of ${magnification.value}x."
        }
        liveViewMagnificationSetting = readback
        observedFeatures.add(CameraFeature.LIVE_VIEW_MAGNIFICATION)
        return LiveViewMagnificationResult(ok = true, magnification = readback.current)
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

    suspend fun mediaInfo(item: CameraMediaItem): CameraMediaItem {
        if (isRealCamera && enforceAdvertisedOperations && !supportsApi("GET", "/contents")) {
            error("Camera did not advertise CCAPI media browsing.")
        }
        return parseMediaInfo(item, getJson("${mediaItemPath(item)}?kind=info"))
    }

    suspend fun setMediaProtection(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        modifyMedia(
            item = item,
            action = "protect",
            value = if (enabled) "enable" else "disable",
            feature = CameraFeature.MEDIA_PROTECT,
            matches = { it.protected == enabled },
        )

    suspend fun setMediaRating(item: CameraMediaItem, rating: Int): CameraMediaItem {
        require(rating in 0..5) { "Media rating must be from 0 through 5." }
        return modifyMedia(
            item = item,
            action = "rating",
            value = if (rating == 0) "off" else rating.toString(),
            feature = CameraFeature.MEDIA_RATING,
            matches = { it.rating == rating },
        )
    }

    suspend fun setMediaRotation(item: CameraMediaItem, degrees: Int): CameraMediaItem {
        require(degrees in MEDIA_ROTATIONS) { "Media rotation must be 0, 90, 180, or 270 degrees." }
        return modifyMedia(
            item = item,
            action = "rotate",
            value = degrees.toString(),
            feature = CameraFeature.MEDIA_ROTATE,
            matches = { it.rotationDegrees == degrees },
        )
    }

    private suspend fun modifyMedia(
        item: CameraMediaItem,
        action: String,
        value: String,
        feature: CameraFeature,
        matches: (CameraMediaItem) -> Boolean,
    ): CameraMediaItem {
        if (isRealCamera && enforceAdvertisedOperations && !supportsMediaModify()) {
            error("Camera did not advertise ${feature.label.lowercase()}.")
        }
        val path = mediaItemPath(item)
        putOk(path, JSONObject().put("action", action).put("value", value), expectedStatusCode = 200)
        var latest = item
        repeat(3) { attempt ->
            latest = mediaInfo(item)
            if (matches(latest)) {
                observedFeatures.add(feature)
                return latest
            }
            if (attempt < 2) delay(100)
        }
        error("Camera accepted media $action but did not report the requested value.")
    }

    private fun mediaItemPath(item: CameraMediaItem): String = if (isRealCamera) {
        normalizeCameraResource(item.id).substringBefore('?')
    } else {
        val encodedId = URLEncoder.encode(item.id, StandardCharsets.UTF_8.name()).replace("+", "%20")
        "/ccapi/media/$encodedId"
    }

    private fun parseMediaInfo(item: CameraMediaItem, body: JSONObject): CameraMediaItem {
        val ratingValue = body.optString("rating").let { value ->
            when (value) {
                "off" -> 0
                else -> value.toIntOrNull()?.takeIf { it in 1..5 }
            }
        }
        return item.copy(
            sizeBytes = body.optLong("filesize").takeIf { it > 0L } ?: item.sizeBytes,
            captureTime = body.optString("lastmodifieddate").takeIf { it.isNotBlank() } ?: item.captureTime,
            protected = when (body.optString("protect")) {
                "enable" -> true
                "disable" -> false
                else -> null
            },
            rating = ratingValue,
            rotationDegrees = body.optString("rotate").toIntOrNull()?.takeIf { it in MEDIA_ROTATIONS },
        )
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

    suspend fun uploadMedia(
        name: String,
        sizeBytes: Long,
        contentType: String?,
        source: InputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaUploadResult = withContext(Dispatchers.IO) {
        if (isRealCamera) {
            throw UnsupportedOperationException("Canon CCAPI did not advertise a verified media upload endpoint.")
        }
        require(sizeBytes in 1L..MAX_MEDIA_UPLOAD_BYTES) {
            "Simulator upload size must be from 1 through $MAX_MEDIA_UPLOAD_BYTES bytes."
        }
        val uploadUrl = "$baseUrl/ccapi/media".toHttpUrl().newBuilder()
            .addQueryParameter("filename", name)
            .build()
        val mediaType = (contentType ?: "application/octet-stream").substringBefore(';').trim().toMediaType()
        var bytesTransferred = 0L
        val body = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength(): Long = sizeBytes

            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(MEDIA_TRANSFER_BUFFER_BYTES)
                onProgress(CameraMediaTransferProgress(0L, sizeBytes))
                while (bytesTransferred < sizeBytes) {
                    val requested = minOf(buffer.size.toLong(), sizeBytes - bytesTransferred).toInt()
                    val count = source.read(buffer, 0, requested)
                    check(count >= 0) { "Upload source ended after $bytesTransferred of $sizeBytes bytes." }
                    if (count == 0) continue
                    sink.write(buffer, 0, count)
                    bytesTransferred += count
                    onProgress(CameraMediaTransferProgress(bytesTransferred, sizeBytes))
                }
                check(source.read() == -1) { "Upload source exceeds its declared $sizeBytes bytes." }
            }
        }
        val call = httpClient.newCall(Request.Builder().url(uploadUrl).post(body).build())
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
                throw exception
            }
            response.use {
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw CcapiHttpException(
                        response.code,
                        "Simulator media upload returned HTTP ${response.code}: ${responseBody.take(MAX_ERROR_BODY_CHARS)}",
                    )
                }
                check(bytesTransferred == sizeBytes) { "Simulator upload byte count did not match the source." }
                val item = parseSimulatorMediaItem(JSONObject(responseBody))
                    ?: error("Simulator returned invalid uploaded media information.")
                check(item.name == name && item.sizeBytes == sizeBytes) {
                    "Simulator did not verify the uploaded filename and size."
                }
                CameraMediaUploadResult(item, bytesTransferred).also {
                    observedFeatures.add(CameraFeature.MEDIA_UPLOAD)
                }
            }
        } finally {
            cancelCall.set(false)
            cancellationWatcher.cancel()
        }
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
        refreshTemperatureStatusForRestrictedCommand()
        requireLiveViewAllowed()
        if (isRealCamera) {
            latestLiveViewGeometry = null
            val sources = when (request.source) {
                LiveViewSource.AUTO -> buildList {
                    if (supportsRtpLiveView()) add(LiveViewSource.CCAPI_RTP)
                    if (supportsMultipartLiveView()) add(LiveViewSource.CCAPI_MULTIPART)
                    if (!enforceAdvertisedOperations || supportsCompleteLiveView()) {
                        add(LiveViewSource.CCAPI_JPEG_POLLING)
                    }
                }
                LiveViewSource.CCAPI_JPEG_POLLING,
                LiveViewSource.CCAPI_MULTIPART,
                LiveViewSource.CCAPI_RTP,
                -> listOf(request.source)
                else -> error("${request.source.label} is not available through the CCAPI network backend.")
            }
            check(sources.isNotEmpty()) { "Camera did not advertise a complete Live View CCAPI lifecycle." }
            val failures = mutableListOf<Throwable>()
            for (source in sources) {
                try {
                    when (source) {
                        LiveViewSource.CCAPI_RTP -> startRtpLiveView(request)
                        LiveViewSource.CCAPI_MULTIPART -> startMultipartLiveView(request)
                        LiveViewSource.CCAPI_JPEG_POLLING -> startJpegLiveView(request)
                        else -> error("${source.label} is not available through the CCAPI network backend.")
                    }
                    return
                } catch (exception: Exception) {
                    failures += exception
                    if (request.source != LiveViewSource.AUTO) throw exception
                }
            }
            throw IllegalStateException(
                "Every advertised CCAPI Live View source failed: ${failures.joinToString { it.message ?: it.javaClass.simpleName }}",
                failures.lastOrNull(),
            )
        }
    }

    suspend fun stopLiveView() {
        latestLiveViewGeometry = null
        if (isRealCamera) {
            when (activeLiveViewSource) {
                LiveViewSource.CCAPI_RTP -> stopRtpLiveView()
                LiveViewSource.CCAPI_MULTIPART -> stopMultipartLiveView()
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
        if (isRealCamera && activeLiveViewSource == LiveViewSource.CCAPI_MULTIPART) {
            return withContext(Dispatchers.IO) {
                checkNotNull(multipartLiveViewSession) { "Canon multipart Live View session is not active." }
                    .nextFrame()
            }.also {
                observedFeatures.add(CameraFeature.LIVE_VIEW)
                observedFeatures.add(CameraFeature.LIVE_VIEW_MULTIPART)
            }
        }
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

                LiveViewSource.CCAPI_MULTIPART ->
                    error("CCAPI multipart Live View renders through its persistent frame reader.")

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

    private fun cameraClockOperations(): Pair<CcapiApiOperation, CcapiApiOperation>? {
        val reads = apiOperations
            .filter { it.method == "GET" && it.path.endsWith("/functions/datetime") }
            .sortedByDescending { it.apiVersionNumber() }
        reads.forEach { read ->
            val prefix = read.path.removeSuffix("/functions/datetime")
            val write = CcapiApiOperation("PUT", "$prefix/functions/datetime")
            if (write in apiOperations) return read to write
        }
        return null
    }

    private fun sensorCleaningOperation(): CcapiApiOperation? = apiOperations
        .filter { it.method == "POST" && it.path.endsWith("/functions/sensorcleaning") }
        .maxByOrNull { it.apiVersionNumber() }

    private fun parseCameraClock(json: JSONObject): Pair<ZonedDateTime, Boolean> {
        val rawDateTime = json.opt("datetime") as? String
            ?: error("Canon date-time response is missing an RFC 1123 datetime string.")
        val daylight = json.opt("dst") as? Boolean
            ?: error("Canon date-time response is missing a boolean dst field.")
        val parsed = try {
            ZonedDateTime.parse(rawDateTime, CANON_DATETIME_FORMATTER)
        } catch (exception: DateTimeParseException) {
            throw IllegalStateException("Canon date-time response contains an invalid RFC 1123 value.", exception)
        }
        return parsed to daylight
    }

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
        val writableSettings = (
            settingPathsByKey.keys +
                (if (liveViewMagnificationSetting != null) setOf(LIVE_VIEW_MAGNIFICATION_SETTING_KEY) else emptySet()) +
                (if (cameraSleepWritePath != null) setOf(AUTO_POWER_OFF_SETTING_KEY) else emptySet()) +
                (if (fileNamingState != null) FILE_NAMING_ENDPOINTS.keys.map { it.wireName } else emptyList())
            )
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
            discoveryTrace = discoveryTrace.toList(),
            truncated = protocolVersions.size > MAX_CAPABILITY_EVIDENCE_ITEMS ||
                commands.size > MAX_CAPABILITY_EVIDENCE_ITEMS ||
                writableSettings.size > MAX_CAPABILITY_EVIDENCE_ITEMS ||
                discoveryTraceTruncated || discoveryTrace.any(CameraDiscoveryAttempt::truncated),
        )
    }

    private fun featureForSetting(key: String): CameraFeature = when (key.lowercase()) {
        "iso", "tv", "shutter", "shutterspeed", "av", "aperture" -> CameraFeature.EXPOSURE_CONTROL
        "wb", "whitebalance", "white_balance" -> CameraFeature.WHITE_BALANCE_CONTROL
        MOVIE_MODE_SETTING_KEY -> CameraFeature.MOVIE_MODE_CONTROL
        ZOOM_SETTING_KEY -> CameraFeature.ZOOM_CONTROL
        DIRECTORY_SELECTION_SETTING_KEY -> CameraFeature.DIRECTORY_CONTROL
        STILL_CARD_SELECTION_SETTING_KEY, MOVIE_CARD_SELECTION_SETTING_KEY ->
            CameraFeature.CARD_SELECTION_CONTROL
        in SOUND_RECORDING_SETTING_KEYS -> CameraFeature.SOUND_RECORDING_CONTROL
        SOUND_RECORDING_LEVEL_SETTING_KEY -> CameraFeature.SOUND_RECORDING_LEVEL_CONTROL
        in FOCUS_BRACKETING_SETTING_KEYS -> CameraFeature.FOCUS_BRACKETING_CONTROL
        in MOVIE_SETTING_KEYS -> CameraFeature.MOVIE_SETTINGS_CONTROL
        else -> CameraFeature.ADVANCED_SETTINGS
    }

    private fun zoomOperations(): Pair<CcapiApiOperation, CcapiApiOperation>? {
        val reads = apiOperations
            .filter { it.method == "GET" && it.path.endsWith(ZOOM_PATH_SUFFIX) }
            .sortedByDescending { it.apiVersionNumber() }
        return reads.firstNotNullOfOrNull { read ->
            apiOperations.firstOrNull { it.method == "POST" && it.path == read.path }
                ?.let { write -> read to write }
        }
    }

    private fun movieModeOperations(): Pair<CcapiApiOperation, CcapiApiOperation>? {
        val reads = apiOperations
            .filter { it.method == "GET" && it.path.endsWith(MOVIE_MODE_PATH_SUFFIX) }
            .sortedByDescending { it.apiVersionNumber() }
        return reads.firstNotNullOfOrNull { read ->
            apiOperations.firstOrNull { it.method == "POST" && it.path == read.path }
                ?.let { write -> read to write }
        }
    }

    private fun liveViewMagnificationOperations(): Pair<CcapiApiOperation, CcapiApiOperation>? =
        readWriteSettingOperations(LIVE_VIEW_MAGNIFICATION_PATH_SUFFIX)

    private fun cardSelectionOperations(pathSuffix: String): Pair<CcapiApiOperation, CcapiApiOperation>? {
        val reads = apiOperations
            .filter { it.method == "GET" && it.path.endsWith(pathSuffix) }
            .sortedByDescending { it.apiVersionNumber() }
        return reads.firstNotNullOfOrNull { read ->
            apiOperations.firstOrNull { it.method == "PUT" && it.path == read.path }
                ?.let { write -> read to write }
        }
    }

    private fun soundRecordingLevelOperations(): Pair<CcapiApiOperation, CcapiApiOperation>? {
        val reads = apiOperations
            .filter { it.method == "GET" && it.path.endsWith(SOUND_RECORDING_LEVEL_PATH_SUFFIX) }
            .sortedByDescending { it.apiVersionNumber() }
        return reads.firstNotNullOfOrNull { read ->
            apiOperations.firstOrNull { it.method == "PUT" && it.path == read.path }
                ?.let { write -> read to write }
        }
    }

    private fun soundRecordingOperations(pathSuffix: String): Pair<CcapiApiOperation, CcapiApiOperation>? {
        val reads = apiOperations
            .filter { it.method == "GET" && it.path.endsWith(pathSuffix) }
            .sortedByDescending { it.apiVersionNumber() }
        return reads.firstNotNullOfOrNull { read ->
            apiOperations.firstOrNull { it.method == "PUT" && it.path == read.path }
                ?.let { write -> read to write }
        }
    }

    private fun readWriteSettingOperations(pathSuffix: String): Pair<CcapiApiOperation, CcapiApiOperation>? {
        val reads = apiOperations
            .filter { it.method == "GET" && it.path.endsWith(pathSuffix) }
            .sortedByDescending { it.apiVersionNumber() }
        return reads.firstNotNullOfOrNull { read ->
            CcapiApiOperation("PUT", read.path)
                .takeIf(apiOperations::contains)
                ?.let { write -> read to write }
        }
    }

    private fun directoryOperations(): DirectoryOperations? {
        val reads = apiOperations
            .filter { it.method == "GET" && it.path.endsWith(DIRECTORY_SELECTION_PATH_SUFFIX) }
            .sortedByDescending { it.apiVersionNumber() }
        return reads.firstNotNullOfOrNull { read ->
            val prefix = read.path.removeSuffix(DIRECTORY_SELECTION_PATH_SUFFIX)
            val write = CcapiApiOperation("PUT", read.path)
            val create = CcapiApiOperation("POST", "$prefix$DIRECTORY_CREATE_PATH_SUFFIX")
            if (write in apiOperations && create in apiOperations) {
                DirectoryOperations(read, write, create)
            } else {
                null
            }
        }
    }

    private fun fileNamingOperations(): Map<CameraFileNamingField, Pair<CcapiApiOperation, CcapiApiOperation>>? {
        return apiVersionPrefixes
            .sortedByDescending { it.apiVersionNumber() }
            .firstNotNullOfOrNull { prefix ->
                buildMap {
                    FILE_NAMING_ENDPOINTS.forEach { (field, definition) ->
                        val path = "$prefix${definition.pathSuffix}"
                        val read = CcapiApiOperation("GET", path)
                        val write = CcapiApiOperation("PUT", path)
                        if (read !in apiOperations || write !in apiOperations) return@firstNotNullOfOrNull null
                        put(field, read to write)
                    }
                }
            }
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

    private fun requireLiveViewAllowed() {
        check(latestTemperatureStatus?.liveViewAllowed != false) {
            "Live View is unavailable because the camera reported a temperature restriction."
        }
    }

    private suspend fun refreshTemperatureStatusForRestrictedCommand() {
        if (!isRealCamera) return
        val operation = apiOperation("GET", "/devicestatus/temperature") ?: return
        getFirstJson(listOf(operation.path))?.toTemperatureStatusOrNull()?.let { temperatureStatus ->
            latestTemperatureStatus = temperatureStatus
            observedFeatures.add(CameraFeature.TEMPERATURE_STATUS)
        }
    }

    private fun requireStillCaptureAllowed() {
        check(latestTemperatureStatus?.stillCaptureAllowed != false) {
            "Still capture is unavailable because the camera reported a temperature restriction."
        }
    }

    private fun requireMovieRecordingAllowed() {
        check(latestTemperatureStatus?.movieRecordingAllowed != false) {
            "Movie recording is unavailable because the camera reported a temperature restriction."
        }
    }

    private fun directShutterOperation(): CcapiApiOperation? =
        apiOperation("POST", "/shooting/control/shutterbutton")

    private fun eventPollingOperations(): Pair<CcapiApiOperation, CcapiApiOperation>? {
        val pollingGets = apiOperations
            .filter { it.method == "GET" && it.path.endsWith("/event/polling") }
            .sortedByDescending { it.apiVersionNumber() }
        return pollingGets.firstNotNullOfOrNull { get ->
            val prefix = get.path.substringBeforeLast("/event/polling")
            apiOperations.firstOrNull {
                it.method == "DELETE" && it.path == "$prefix/event/polling"
            }?.let { delete -> get to delete }
        }
    }

    private fun CcapiApiOperation.apiVersionNumber(): Int =
        path.substringBefore("/event/polling").apiVersionNumber()

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

    private fun multipartLiveViewOperations(): CcapiMultipartOperations? {
        val reads = apiOperations
            .filter { it.method == "GET" && it.path.endsWith("/shooting/liveview/multipart") }
            .sortedByDescending { it.apiVersionNumber() }
        reads.forEach { read ->
            val prefix = read.path.removeSuffix("/shooting/liveview/multipart")
            val operations = CcapiMultipartOperations(
                startLiveView = CcapiApiOperation("POST", "$prefix/shooting/liveview"),
                stopLiveView = CcapiApiOperation("DELETE", "$prefix/shooting/liveview"),
                openStream = read,
                closeStream = CcapiApiOperation("DELETE", read.path),
            )
            if (
                operations.startLiveView in apiOperations &&
                operations.stopLiveView in apiOperations &&
                operations.closeStream in apiOperations
            ) return operations
        }
        return null
    }

    private fun supportsMultipartLiveView(): Boolean = multipartLiveViewOperations() != null

    private fun ccapiLiveViewCapabilities(): LiveViewCapabilities {
        val sources = buildList {
            if (supportsRtpLiveView()) add(LiveViewSource.CCAPI_RTP)
            if (supportsMultipartLiveView()) add(LiveViewSource.CCAPI_MULTIPART)
            if (supportsCompleteLiveView()) add(LiveViewSource.CCAPI_JPEG_POLLING)
        }
        return LiveViewCapabilities.ccapiNetwork().copy(
            sources = sources,
            defaultSource = sources.firstOrNull() ?: LiveViewSource.AUTO,
            magnifications = liveViewMagnificationSetting?.abilities.orEmpty(),
            currentMagnification = liveViewMagnificationSetting?.current,
        )
    }

    private suspend fun startJpegLiveView(request: LiveViewRequest) {
        if (enforceAdvertisedOperations && !supportsCompleteLiveView()) {
            error("Camera did not advertise a complete Live View JPEG start, frame, and stop lifecycle.")
        }
        startCcapiLiveView(request)
        activeLiveViewSource = LiveViewSource.CCAPI_JPEG_POLLING
        observedFeatures.add(CameraFeature.LIVE_VIEW)
        observedFeatures.add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
    }

    private suspend fun startCcapiLiveView(
        request: LiveViewRequest,
        path: String = apiPath("POST", "/shooting/liveview"),
    ) {
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
    }

    private suspend fun startMultipartLiveView(request: LiveViewRequest) {
        val operation = multipartLiveViewOperations()
            ?: error("Camera did not advertise matching Canon multipart Live View GET and DELETE endpoints.")
        startCcapiLiveView(request, operation.startLiveView.path)
        val sourceUrl = "$baseUrl${operation.openStream.path}"
        val streamRequest = Request.Builder()
            .url(sourceUrl)
            .get()
            .header("Accept", "multipart/x-mixed-replace")
            .header("Cache-Control", "no-cache")
            .build()
        try {
            val session = withContext(Dispatchers.IO) {
                val call = multipartHttpClient.newCall(streamRequest)
                val response = try {
                    call.execute()
                } catch (exception: Exception) {
                    call.cancel()
                    throw exception
                }
                if (response.code != 200) {
                    val preview = response.body?.string().orEmpty().trim().take(MAX_ERROR_BODY_CHARS)
                    response.close()
                    throw CcapiHttpException(
                        response.code,
                        "Camera request failed: GET $sourceUrl returned HTTP ${response.code}\nBody: $preview",
                    )
                }
                val boundary = try {
                    parseCcapiMultipartBoundary(response.header("content-type"))
                } catch (exception: Exception) {
                    response.close()
                    call.cancel()
                    throw exception
                }
                CcapiMultipartLiveViewSession(call, response, sourceUrl, boundary)
            }
            multipartLiveViewSession?.close()
            multipartLiveViewSession = session
            activeLiveViewSource = LiveViewSource.CCAPI_MULTIPART
        } catch (exception: Exception) {
            withContext(NonCancellable) {
                runCatching { deleteOk(operation.closeStream.path) }
                runCatching { deleteOk(operation.stopLiveView.path) }
            }
            throw exception
        }
    }

    private suspend fun stopMultipartLiveView() {
        multipartLiveViewSession?.close()
        multipartLiveViewSession = null
        val operations = multipartLiveViewOperations()
        try {
            if (operations != null) {
                requestOk(
                    Request.Builder().url("$baseUrl${operations.closeStream.path}").delete().build(),
                    expectedStatusCode = 200,
                )
            }
        } finally {
            if (!enforceAdvertisedOperations || supportsApi("DELETE", "/shooting/liveview")) {
                runCatching { deleteOk(operations?.stopLiveView?.path ?: apiPath("DELETE", "/shooting/liveview")) }
            }
        }
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

    private fun supportsMediaModify(): Boolean = apiOperations.any { operation ->
        operation.method == "PUT" &&
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
        return List(items.length()) { index -> parseSimulatorMediaItem(items.getJSONObject(index)) }.filterNotNull()
    }

    private fun parseSimulatorMediaItem(item: JSONObject): CameraMediaItem? {
        val id = item.optString("id").trim()
        val name = item.optString("name").trim()
        if (id.isBlank() || name.isBlank()) return null
        val kind = item.optString("kind", "other")
        return CameraMediaItem(
            id = id,
            name = name,
            kind = kind,
            sizeBytes = item.optLong("size_bytes").takeIf { item.has("size_bytes") },
            captureTime = item.optString("capture_time").takeIf { it.isNotBlank() },
            previewAvailable = kind.isCcapiPreviewKind(),
            protected = item.opt("protect") as? Boolean,
            rating = item.optInt("rating").takeIf { item.has("rating") && it in 0..5 },
            rotationDegrees = item.optInt("rotate").takeIf { item.has("rotate") && it in MEDIA_ROTATIONS },
            ratingWritable = true,
        )
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
                previewAvailable = path.mediaKind().isCcapiPreviewKind(),
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
        cameraSleepWritePath = null
        liveViewMagnificationSetting = null
        observedFeatures.remove(CameraFeature.LIVE_VIEW_MAGNIFICATION)
        observedFeatures.remove(CameraFeature.CARD_SELECTION_CONTROL)
        observedFeatures.remove(CameraFeature.SOUND_RECORDING_CONTROL)
        observedFeatures.remove(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL)
        observedFeatures.remove(CameraFeature.FOCUS_BRACKETING_CONTROL)
        observedFeatures.remove(CameraFeature.MOVIE_SETTINGS_CONTROL)
        observedFeatures.remove(CameraFeature.DIRECTORY_CONTROL)
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
                if (
                    key !in SOUND_RECORDING_SETTING_KEYS &&
                    key != SOUND_RECORDING_LEVEL_SETTING_KEY &&
                    key != LIVE_VIEW_MAGNIFICATION_SETTING_KEY &&
                    key !in DEVICE_FUNCTION_SETTING_KEYS &&
                    key !in FOCUS_BRACKETING_SETTING_KEYS &&
                    key !in MOVIE_SETTING_KEYS &&
                    (!enforceAdvertisedOperations || apiOperations.contains(CcapiApiOperation("PUT", settingPath)))
                ) {
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

        liveViewMagnificationOperations()?.let { (read, _) ->
            val setting = try {
                getJson(read.path).toValidatedLiveViewMagnificationSetting()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
            if (setting != null) {
                liveViewMagnificationSetting = setting
                observedFeatures.add(CameraFeature.LIVE_VIEW_MAGNIFICATION)
            }
        }

        zoomOperations()?.let { (read, write) ->
            val zoom = try {
                getJson(read.path).toValidatedZoomSetting()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
            if (zoom != null) {
                val values = zoom.getJSONArray("ability").toStringList().toSet()
                settingPathsByKey[ZOOM_SETTING_KEY] = write.path
                settingValuesByKey[ZOOM_SETTING_KEY] = values
                merged.put(ZOOM_SETTING_KEY, zoom)
            }
        }

        movieModeOperations()?.let { (read, write) ->
            val movieMode = try {
                getJson(read.path).toValidatedMovieModeSetting()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
            if (movieMode != null) {
                settingPathsByKey[MOVIE_MODE_SETTING_KEY] = write.path
                settingValuesByKey[MOVIE_MODE_SETTING_KEY] = MOVIE_MODE_VALUES
                merged.put(MOVIE_MODE_SETTING_KEY, movieMode)
            }
        }

        CARD_SELECTION_ENDPOINTS.forEach { (key, pathSuffix) ->
            cardSelectionOperations(pathSuffix)?.let { (read, write) ->
                val cardSelection = try {
                    getJson(read.path).toValidatedCardSelectionSetting()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    null
                }
                if (cardSelection != null) {
                    val values = cardSelection.getJSONArray("ability").toStringList().toSet()
                    settingPathsByKey[key] = write.path
                    settingValuesByKey[key] = values
                    merged.put(key, cardSelection)
                    observedFeatures.add(CameraFeature.CARD_SELECTION_CONTROL)
                }
            }
        }

        DEVICE_FUNCTION_SETTING_ENDPOINTS.forEach { (key, definition) ->
            readWriteSettingOperations(definition.pathSuffix)?.let { (read, write) ->
                val setting = try {
                    getJson(read.path).toValidatedStringAbilitySetting(definition.values)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    null
                }
                if (setting != null) {
                    val ability = setting.getJSONArray("ability").toStringList()
                    if (key == AUTO_POWER_OFF_SETTING_KEY && AUTO_POWER_OFF_IMMEDIATELY in ability) {
                        cameraSleepWritePath = write.path
                    }
                    val settingValues = ability.filter { it in definition.settingValues }
                    val current = setting.getString("value")
                    if (settingValues.size >= 2 && current in settingValues) {
                        settingPathsByKey[key] = write.path
                        settingValuesByKey[key] = settingValues.toSet()
                        merged.put(
                            key,
                            JSONObject()
                                .put("value", current)
                                .put("ability", org.json.JSONArray(settingValues)),
                        )
                    }
                }
            }
        }

        SOUND_RECORDING_ENDPOINTS.forEach { (key, definition) ->
            soundRecordingOperations(definition.pathSuffix)?.let { (read, write) ->
                val setting = try {
                    getJson(read.path).toValidatedStringAbilitySetting(definition.values)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    null
                }
                if (setting != null) {
                    settingPathsByKey[key] = write.path
                    settingValuesByKey[key] = setting.getJSONArray("ability").toStringList().toSet()
                    merged.put(key, setting)
                    observedFeatures.add(CameraFeature.SOUND_RECORDING_CONTROL)
                }
            }
        }

        soundRecordingLevelOperations()?.let { (read, write) ->
            val soundRecordingLevel = try {
                getJson(read.path).toValidatedIntegerRangeSetting()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
            if (soundRecordingLevel != null) {
                val values = soundRecordingLevel.getJSONArray("ability").toStringList().toSet()
                settingPathsByKey[SOUND_RECORDING_LEVEL_SETTING_KEY] = write.path
                settingValuesByKey[SOUND_RECORDING_LEVEL_SETTING_KEY] = values
                merged.put(SOUND_RECORDING_LEVEL_SETTING_KEY, soundRecordingLevel)
                observedFeatures.add(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL)
            }
        }

        var focusBracketingAvailable = false
        FOCUS_BRACKETING_STRING_ENDPOINTS.forEach { (key, definition) ->
            if (key != FOCUS_BRACKETING_SETTING_KEY && !focusBracketingAvailable) return@forEach
            readWriteSettingOperations(definition.pathSuffix)?.let { (read, write) ->
                val setting = try {
                    getJson(read.path).toValidatedStringAbilitySetting(definition.values)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    null
                }
                if (setting != null) {
                    settingPathsByKey[key] = write.path
                    settingValuesByKey[key] = setting.getJSONArray("ability").toStringList().toSet()
                    merged.put(key, setting)
                    if (key == FOCUS_BRACKETING_SETTING_KEY) {
                        focusBracketingAvailable = true
                        observedFeatures.add(CameraFeature.FOCUS_BRACKETING_CONTROL)
                    }
                }
            }
        }
        if (focusBracketingAvailable) {
            FOCUS_BRACKETING_INTEGER_ENDPOINTS.forEach { (key, definition) ->
                readWriteSettingOperations(definition.pathSuffix)?.let { (read, write) ->
                    val setting = try {
                        getJson(read.path).toValidatedIntegerRangeSetting(MAX_FOCUS_BRACKETING_OPTIONS)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        null
                    }
                    if (setting != null) {
                        settingPathsByKey[key] = write.path
                        settingValuesByKey[key] = setting.getJSONArray("ability").toStringList().toSet()
                        merged.put(key, setting)
                    }
                }
            }
        }

        MOVIE_SETTING_ENDPOINTS.forEach { (key, definition) ->
            readWriteSettingOperations(definition.pathSuffix)?.let { (read, write) ->
                val setting = try {
                    getJson(read.path).toValidatedStringAbilitySetting(definition.values)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    null
                }
                if (setting != null) {
                    settingPathsByKey[key] = write.path
                    settingValuesByKey[key] = setting.getJSONArray("ability").toStringList().toSet()
                    merged.put(key, setting)
                    observedFeatures.add(CameraFeature.MOVIE_SETTINGS_CONTROL)
                }
            }
        }

        directoryOperations()?.let { operations ->
            val selection = try {
                getJson(operations.read.path).toValidatedDirectorySelectionSetting()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
            if (selection != null) {
                val values = selection.getJSONArray("ability").toStringList().toSet()
                settingPathsByKey[DIRECTORY_SELECTION_SETTING_KEY] = operations.write.path
                settingValuesByKey[DIRECTORY_SELECTION_SETTING_KEY] = values
                merged.put(DIRECTORY_SELECTION_SETTING_KEY, selection)
                observedFeatures.add(CameraFeature.DIRECTORY_CONTROL)
            }
        }

        settingsLoaded = true
        return if (merged.length() > 0) merged else null
    }

    private suspend fun loadFileNaming(force: Boolean = false): CameraFileNaming? {
        if (fileNamingLoaded && !force) return fileNamingState
        observedFeatures.remove(CameraFeature.FILE_NAMING_CONTROL)
        val state = if (isRealCamera) {
            val operations = fileNamingOperations()
            if (operations == null) {
                null
            } else {
                val responses = buildMap {
                    for ((field, operation) in operations) {
                        val response = try {
                            getJson(operation.first.path)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            return@buildMap
                        }
                        put(field, response)
                    }
                }
                responses.toValidatedFileNaming()
            }
        } else {
            getJson("/ccapi/capabilities").optJSONObject("fileNaming")?.toValidatedFileNaming()
        }
        fileNamingState = state
        fileNamingLoaded = true
        if (state != null) observedFeatures.add(CameraFeature.FILE_NAMING_CONTROL)
        return state
    }

    private suspend fun postZoomValue(value: String) {
        if (!settingsLoaded) loadShootingSettings()
        val path = settingPathsByKey[ZOOM_SETTING_KEY]
            ?: error("Camera did not advertise writable Canon zoom control.")
        if (value !in settingValuesByKey[ZOOM_SETTING_KEY].orEmpty()) {
            error("Value '$value' is not advertised for zoom.")
        }
        val zoom = value.toIntOrNull()
            ?.takeIf { it.toString() == value }
            ?: error("Zoom value must be an integer advertised by the camera.")
        postJson(path, JSONObject().put("value", zoom))
    }

    private suspend fun postMovieModeValue(value: String) {
        if (!settingsLoaded) loadShootingSettings()
        val path = settingPathsByKey[MOVIE_MODE_SETTING_KEY]
            ?: error("Camera did not advertise writable Canon movie mode control.")
        if (value !in settingValuesByKey[MOVIE_MODE_SETTING_KEY].orEmpty()) {
            error("Value '$value' is not advertised for movie mode.")
        }
        postOk(path, JSONObject().put("action", value))
    }

    private suspend fun putIntegerSettingValue(key: String, value: String) {
        if (!settingsLoaded) loadShootingSettings()
        val path = settingPathsByKey[key]
            ?: error("Camera did not advertise writable ${key.toSettingLabel()} control.")
        if (value !in settingValuesByKey[key].orEmpty()) {
            error("Value '$value' is not advertised for ${key.toSettingLabel()}.")
        }
        val integer = value.toIntOrNull()
            ?.takeIf { it.toString() == value }
            ?: error("${key.toSettingLabel()} must be an integer advertised by the camera.")
        putOk(path, JSONObject().put("value", integer))
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

    private suspend fun postOk(
        path: String,
        payload: JSONObject,
        expectedStatusCode: Int? = null,
    ): Unit = requestOk(
        Request.Builder()
            .url("$baseUrl$path")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build(),
        expectedStatusCode = expectedStatusCode,
    )

    private suspend fun putOk(
        path: String,
        payload: JSONObject,
        expectedStatusCode: Int? = null,
    ): Unit = requestOk(
        Request.Builder()
            .url("$baseUrl$path")
            .put(payload.toString().toRequestBody(jsonMediaType))
            .build(),
        expectedStatusCode = expectedStatusCode,
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

    private suspend fun requestEventJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        val call = eventHttpClient.newCall(request)
        check(activeEventCall.compareAndSet(null, call)) { "A camera event polling request is already active." }
        val cancelCall = AtomicBoolean(true)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (cancelCall.get()) call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                val payload = response.body?.byteStream()?.readBoundedEventPayload() ?: ByteArray(0)
                val body = payload.toString(StandardCharsets.UTF_8)
                if (!response.isSuccessful) {
                    throw CcapiHttpException(
                        statusCode = response.code,
                        message = "Camera request failed: ${request.method} ${request.url} returned HTTP ${response.code}\n" +
                            "Body: ${body.take(MAX_ERROR_BODY_CHARS)}",
                    )
                }
                runCatching { JSONObject(body) }.getOrElse {
                    error("Camera event polling returned invalid JSON.")
                }
            }
        } finally {
            cancelCall.set(false)
            cancellationWatcher.cancel()
            activeEventCall.compareAndSet(call, null)
        }
    }

    private suspend fun requestOk(
        request: Request,
        expectedStatusCode: Int? = null,
    ): Unit = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CcapiHttpException(
                    statusCode = response.code,
                    message = "Camera request failed: ${request.method} ${request.url} returned HTTP ${response.code}\nBody: $body",
                )
            }
            if (expectedStatusCode != null && response.code != expectedStatusCode) {
                throw CcapiHttpException(
                    statusCode = response.code,
                    message = "Camera request failed: ${request.method} ${request.url} returned HTTP ${response.code}; " +
                        "expected HTTP $expectedStatusCode.\nBody: $body",
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
        const val MAX_MEDIA_UPLOAD_BYTES = UINT32_MAX
        const val MEDIA_SNIFF_BYTES = 64L
        val MEDIA_ROTATIONS = setOf(0, 90, 180, 270)
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

private fun String.isCcapiPreviewKind(): Boolean =
    equals("image", ignoreCase = true) || equals("raw", ignoreCase = true)

private fun JSONObject.toCameraInfo(): CameraInfo = CameraInfo(
    connected = optBoolean("connected"),
    model = optString("model", "Unknown camera"),
    serial = optString("serial", "unknown"),
    api = optString("api", "ccapi"),
)

private fun JSONObject.toCanonLensStatusOrNull(): LensStatus? {
    val mounted = opt("mount") as? Boolean ?: return null
    val name = opt("name") as? String ?: return null
    if (!name.isValidLensName(mounted)) return null
    return LensStatus(mounted = mounted, name = name.takeIf { mounted }.orEmpty())
}

private fun JSONObject.toBridgeLensStatusOrNull(): LensStatus? {
    val mounted = opt("mounted") as? Boolean ?: return null
    val name = opt("name") as? String ?: return null
    if (!name.isValidLensName(mounted)) return null
    return LensStatus(mounted = mounted, name = name.takeIf { mounted }.orEmpty())
}

private fun String.isValidLensName(mounted: Boolean): Boolean =
    length <= MAX_DEVICE_STATUS_TEXT_CHARS &&
        none { it.isISOControl() } &&
        (!mounted || isNotBlank())

private fun JSONObject.toTemperatureStatusOrNull(): CameraTemperatureStatus? =
    (opt("status") as? String)?.let(CameraTemperatureStatus::fromCcapiValue)

private fun JSONObject.toCanonRecordableStatusOrNull(): CcapiRecordableStatus? {
    val shots = strictNullableNonNegativeLong("recordableshots") ?: return null
    val remaining = strictNullableNonNegativeLong("remainingtime") ?: return null
    return CcapiRecordableStatus(shots = shots.value, remainingSeconds = remaining.value)
}

private fun JSONObject.strictNullableNonNegativeLong(key: String): StrictNullableLong? {
    if (!has(key)) return null
    val value = opt(key)
    if (value == JSONObject.NULL) return StrictNullableLong(null)
    val parsed = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> return null
    }
    return if (parsed >= 0L) StrictNullableLong(parsed) else null
}

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
        recordableShots = optNullableLong("recordable_shots") ?: optNullableLong("recordableShots"),
        remainingRecordingSeconds = optNullableLong("remaining_recording_seconds")
            ?: optNullableLong("remainingRecordingSeconds"),
        rawRecordableJson = optJSONObject("recordable")?.toString() ?: "null",
        bulbExposureActive = optNullableBoolean("bulb_exposure_active")
            ?: optNullableBoolean("bulbExposureActive"),
        lens = optJSONObject("lens")?.toBridgeLensStatusOrNull(),
        temperature = (opt("temperature") as? String)
            ?.let(CameraTemperatureStatus::fromCcapiValue),
    )
}

private fun JSONObject.toCameraCapabilities(): CameraCapabilities {
    val simulatorLiveViewMagnification = optJSONObject("liveView")
        ?.toValidatedSimulatorLiveViewMagnificationSetting()
    fun simulatorCardSelection(key: String): CameraSettingControl? = optJSONObject(key)
        ?.toValidatedCardSelectionSetting()
        ?.let { setting ->
            CameraSettingControl(
                key = key,
                label = key.toSettingLabel(),
                value = setting.getString("value"),
                values = setting.getJSONArray("ability").toStringList(),
            )
        }
    val stillCardSelection = simulatorCardSelection(STILL_CARD_SELECTION_SETTING_KEY)
    val movieCardSelection = simulatorCardSelection(MOVIE_CARD_SELECTION_SETTING_KEY)
    val deviceFunctionSettings = DEVICE_FUNCTION_SETTING_ENDPOINTS.mapNotNull { (key, definition) ->
        optJSONObject(key)
            ?.toValidatedStringAbilitySetting(definition.values)
            ?.let { key to it }
    }.toMap()
    val simulatorCameraSleepSupported = deviceFunctionSettings[AUTO_POWER_OFF_SETTING_KEY]
        ?.getJSONArray("ability")
        ?.toStringList()
        ?.contains(AUTO_POWER_OFF_IMMEDIATELY) == true
    val deviceFunctionControls = deviceFunctionSettings.mapNotNull { (key, setting) ->
        val definition = DEVICE_FUNCTION_SETTING_ENDPOINTS.getValue(key)
        val values = setting.getJSONArray("ability").toStringList().filter { it in definition.settingValues }
        val current = setting.getString("value")
        if (values.size < 2 || current !in values) {
            null
        } else {
            CameraSettingControl(
                key = key,
                label = key.toSettingLabel(),
                value = current,
                values = values,
            )
        }
    }
    val soundRecordingControls = SOUND_RECORDING_ENDPOINTS.mapNotNull { (key, definition) ->
        optJSONObject(key)
            ?.toValidatedStringAbilitySetting(definition.values)
            ?.let { setting ->
                CameraSettingControl(
                    key = key,
                    label = key.toSettingLabel(),
                    value = setting.getString("value"),
                    values = setting.getJSONArray("ability").toStringList(),
                )
            }
    }
    val soundRecordingLevel = optJSONObject(SOUND_RECORDING_LEVEL_SETTING_KEY)
        ?.toValidatedIntegerRangeSetting()
        ?.let { setting ->
            CameraSettingControl(
                key = SOUND_RECORDING_LEVEL_SETTING_KEY,
                label = SOUND_RECORDING_LEVEL_SETTING_KEY.toSettingLabel(),
                value = setting.getString("value"),
                values = setting.getJSONArray("ability").toStringList(),
            )
        }
    val focusBracketingControls = buildList {
        val rootDefinition = FOCUS_BRACKETING_STRING_ENDPOINTS.getValue(FOCUS_BRACKETING_SETTING_KEY)
        val root = optJSONObject(FOCUS_BRACKETING_SETTING_KEY)
            ?.toValidatedStringAbilitySetting(rootDefinition.values)
        if (root != null) {
            add(
                CameraSettingControl(
                    key = FOCUS_BRACKETING_SETTING_KEY,
                    label = FOCUS_BRACKETING_SETTING_KEY.toSettingLabel(),
                    value = root.getString("value"),
                    values = root.getJSONArray("ability").toStringList(),
                ),
            )
            FOCUS_BRACKETING_STRING_ENDPOINTS
                .filterKeys { it != FOCUS_BRACKETING_SETTING_KEY }
                .forEach { (key, definition) ->
                    optJSONObject(key)
                        ?.toValidatedStringAbilitySetting(definition.values)
                        ?.let { setting ->
                            add(
                                CameraSettingControl(
                                    key = key,
                                    label = key.toSettingLabel(),
                                    value = setting.getString("value"),
                                    values = setting.getJSONArray("ability").toStringList(),
                                ),
                            )
                        }
                }
            FOCUS_BRACKETING_INTEGER_ENDPOINTS.forEach { (key, _) ->
                optJSONObject(key)
                    ?.toValidatedIntegerRangeSetting(MAX_FOCUS_BRACKETING_OPTIONS)
                    ?.let { setting ->
                        add(
                            CameraSettingControl(
                                key = key,
                                label = key.toSettingLabel(),
                                value = setting.getString("value"),
                                values = setting.getJSONArray("ability").toStringList(),
                            ),
                        )
                    }
            }
        }
    }
    val movieSettingControls = MOVIE_SETTING_ENDPOINTS.mapNotNull { (key, definition) ->
        optJSONObject(key)
            ?.toValidatedStringAbilitySetting(definition.values)
            ?.let { setting ->
                CameraSettingControl(
                    key = key,
                    label = key.toSettingLabel(),
                    value = setting.getString("value"),
                    values = setting.getJSONArray("ability").toStringList(),
                )
            }
    }
    val movieModeControl = optJSONObject(MOVIE_MODE_SETTING_KEY)
        ?.toValidatedMovieModeSetting()
        ?.let { setting ->
            CameraSettingControl(
                key = MOVIE_MODE_SETTING_KEY,
                label = "Movie mode",
                value = setting.getString("value"),
                values = setting.getJSONArray("ability").toStringList(),
            )
        }
    val directorySelection = optJSONObject(DIRECTORY_SELECTION_SETTING_KEY)
        ?.toValidatedDirectorySelectionSetting()
        ?.let { setting ->
            CameraSettingControl(
                key = DIRECTORY_SELECTION_SETTING_KEY,
                label = DIRECTORY_SELECTION_SETTING_KEY.toSettingLabel(),
                value = setting.getString("value"),
                values = setting.getJSONArray("ability").toStringList(),
            )
        }
    val fileNaming = optJSONObject("fileNaming")?.toValidatedFileNaming()
    val zoomControl = optJSONObject(ZOOM_SETTING_KEY)
        ?.toValidatedZoomSetting()
        ?.let { setting ->
            CameraSettingControl(
                key = ZOOM_SETTING_KEY,
                label = "Zoom",
                value = setting.getString("value"),
                values = setting.getJSONArray("ability").toStringList(),
            )
        }
    val supported = CapabilityMatrix.ccapiNetwork().supported + setOf(
            CameraFeature.STILL_CAPTURE,
            CameraFeature.BULB_EXPOSURE,
            CameraFeature.AUTOFOCUS,
            CameraFeature.SHUTTER_HALF_PRESS,
            CameraFeature.MEDIA_BROWSER,
            CameraFeature.MEDIA_THUMBNAIL,
            CameraFeature.MEDIA_PREVIEW,
            CameraFeature.MEDIA_DOWNLOAD,
            CameraFeature.MEDIA_UPLOAD,
            CameraFeature.MEDIA_PROTECT,
            CameraFeature.MEDIA_RATING,
            CameraFeature.MEDIA_ROTATE,
            CameraFeature.MEDIA_DELETE,
            CameraFeature.EVENT_POLLING,
            CameraFeature.CAMERA_CLOCK_SYNC,
            CameraFeature.SENSOR_CLEANING,
            CameraFeature.FOCUS_DRIVE,
            CameraFeature.RECORDABLE_STATUS,
            CameraFeature.LENS_STATUS,
            CameraFeature.TEMPERATURE_STATUS,
        ) +
        (if (simulatorLiveViewMagnification != null) {
            setOf(CameraFeature.LIVE_VIEW_MAGNIFICATION)
        } else {
            emptySet()
        }) +
        (if (simulatorCameraSleepSupported) setOf(CameraFeature.CAMERA_SLEEP) else emptySet()) +
        (if (zoomControl != null) setOf(CameraFeature.ZOOM_CONTROL) else emptySet()) +
        (if (movieModeControl != null) setOf(CameraFeature.MOVIE_MODE_CONTROL) else emptySet()) +
        (if (stillCardSelection != null || movieCardSelection != null) {
            setOf(CameraFeature.CARD_SELECTION_CONTROL)
        } else {
            emptySet()
        }) +
        (if (soundRecordingControls.isNotEmpty()) {
            setOf(CameraFeature.SOUND_RECORDING_CONTROL)
        } else {
            emptySet()
        }) +
        (if (soundRecordingLevel != null) {
            setOf(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL)
        } else {
            emptySet()
        }) +
        (if (focusBracketingControls.any { it.key == FOCUS_BRACKETING_SETTING_KEY }) {
            setOf(CameraFeature.FOCUS_BRACKETING_CONTROL)
        } else {
            emptySet()
        }) +
        (if (movieSettingControls.isNotEmpty()) {
            setOf(CameraFeature.MOVIE_SETTINGS_CONTROL)
        } else {
            emptySet()
        }) +
        (if (directorySelection != null) {
            setOf(CameraFeature.DIRECTORY_CONTROL)
        } else {
            emptySet()
        }) +
        (if (fileNaming != null) {
            setOf(CameraFeature.FILE_NAMING_CONTROL)
        } else {
            emptySet()
        })
    return CameraCapabilities(
        iso = getJSONArray("iso").toStringList(),
        shutter = getJSONArray("shutter").toStringList(),
        aperture = getJSONArray("aperture").toStringList(),
        whiteBalance = getJSONArray("white_balance").toStringList(),
        advancedSettings = listOfNotNull(
            movieModeControl,
            zoomControl,
            directorySelection,
            stillCardSelection,
            movieCardSelection,
            *deviceFunctionControls.toTypedArray(),
            *soundRecordingControls.toTypedArray(),
            soundRecordingLevel,
            *focusBracketingControls.toTypedArray(),
            *movieSettingControls.toTypedArray(),
        ),
        fileNaming = fileNaming,
        matrix = CapabilityMatrix.ccapiNetwork(supported),
        liveView = LiveViewCapabilities.simulator().copy(
            magnifications = simulatorLiveViewMagnification?.abilities.orEmpty(),
            currentMagnification = simulatorLiveViewMagnification?.current,
        ),
    )
}

private fun JSONObject.toValidatedSimulatorLiveViewMagnificationSetting(): CcapiLiveViewMagnificationSetting? {
    val currentValue = opt("currentMagnification") as? Int ?: return null
    val rawAbility = optJSONArray("magnifications") ?: return null
    val values = (0 until rawAbility.length()).map { index ->
        rawAbility.opt(index) as? Int ?: return null
    }
    if (
        values.size !in 2..LiveViewMagnification.entries.size ||
        values.toSet().size != values.size ||
        1 !in values ||
        currentValue !in values
    ) return null
    val abilities = values.map { value ->
        LiveViewMagnification.entries.firstOrNull { it.value == value } ?: return null
    }
    val current = abilities.firstOrNull { it.value == currentValue } ?: return null
    return CcapiLiveViewMagnificationSetting(current = current, abilities = abilities)
}

private fun JSONObject.safeTopLevelKeys(): Set<String> {
    val result = linkedSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext() && result.size < MAX_CCAPI_EVENT_KEYS) {
        iterator.next().safeEventKey().takeIf(String::isNotBlank)?.let(result::add)
    }
    return result
}

private fun String.safeEventKey(): String =
    replace("\r", "").replace("\n", "").trim().take(MAX_CCAPI_EVENT_KEY_CHARS)

private fun InputStream.readBoundedEventPayload(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() <= MAX_CCAPI_EVENT_BODY_BYTES - count) {
            "Camera event response exceeded $MAX_CCAPI_EVENT_BODY_BYTES bytes."
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

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
        if (key == ZOOM_SETTING_KEY) {
            val values = setting.optJSONArray("ability")?.toStringList().orEmpty()
            val value = setting.optString("value")
            if (values.size < 2 || value !in values) continue
            controls.add(
                CameraSettingControl(
                    key = key,
                    label = "Zoom",
                    value = value,
                    values = values,
                )
            )
            continue
        }
        val values = setting.optJSONArray("ability")?.toStringList().orEmpty()
            .filter { it.isNotBlank() }
            .distinct()
        val value = setting.optString("value", "")
        val minimumValues = if (key == DIRECTORY_SELECTION_SETTING_KEY) 1 else 2
        if (values.size < minimumValues || value.isBlank()) continue

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
        MOVIE_MODE_SETTING_KEY -> "Movie mode"
        STILL_CARD_SELECTION_SETTING_KEY -> "Still-image card"
        MOVIE_CARD_SELECTION_SETTING_KEY -> "Movie card"
        BEEP_SETTING_KEY -> "Beep"
        DISPLAY_OFF_SETTING_KEY -> "Auto display off"
        AUTO_POWER_OFF_SETTING_KEY -> "Auto power off"
        SOUND_RECORDING_SETTING_KEY -> "Sound recording"
        WIND_FILTER_SETTING_KEY -> "Wind filter"
        ATTENUATOR_SETTING_KEY -> "Attenuator"
        SOUND_RECORDING_LEVEL_SETTING_KEY -> "Sound recording level"
        FOCUS_BRACKETING_SETTING_KEY -> "Focus bracketing"
        FOCUS_BRACKETING_NUMBER_SETTING_KEY -> "Focus bracketing shots"
        FOCUS_BRACKETING_INCREMENT_SETTING_KEY -> "Focus increment"
        FOCUS_BRACKETING_SMOOTHING_SETTING_KEY -> "Exposure smoothing"
        MOVIE_QUALITY_SETTING_KEY -> "Movie quality"
        HIGH_FRAME_RATE_SETTING_KEY -> "High frame rate"
        MOVIE_CROPPING_SETTING_KEY -> "Movie cropping"
        MOVIE_FORMAT_SETTING_KEY -> "Movie recording format"
        DIRECTORY_SELECTION_SETTING_KEY -> "Capture directory"
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
private const val ZOOM_SETTING_KEY = "zoom"
private const val ZOOM_PATH_SUFFIX = "/shooting/control/zoom"
private const val LIVE_VIEW_MAGNIFICATION_SETTING_KEY = "lvzoom"
private const val LIVE_VIEW_MAGNIFICATION_PATH_SUFFIX = "/shooting/settings/lvzoom"
private val LIVE_VIEW_MAGNIFICATION_VALUES = setOf("1", "5", "10")
private const val MOVIE_MODE_SETTING_KEY = "moviemode"
private const val MOVIE_MODE_PATH_SUFFIX = "/shooting/control/moviemode"
private val MOVIE_MODE_VALUES = linkedSetOf("off", "on")
private const val STILL_CARD_SELECTION_SETTING_KEY = "cardselectionstillimage"
private const val MOVIE_CARD_SELECTION_SETTING_KEY = "cardselectionmovie"
private val CARD_SELECTION_VALUES = linkedSetOf("none", "card1", "card2")
private val CARD_SELECTION_SETTING_KEYS = setOf(
    STILL_CARD_SELECTION_SETTING_KEY,
    MOVIE_CARD_SELECTION_SETTING_KEY,
)
private val CARD_SELECTION_ENDPOINTS = linkedMapOf(
    STILL_CARD_SELECTION_SETTING_KEY to "/functions/cardselection/stillimage",
    MOVIE_CARD_SELECTION_SETTING_KEY to "/functions/cardselection/movie",
)
private const val BEEP_SETTING_KEY = "beep"
private const val DISPLAY_OFF_SETTING_KEY = "displayoff"
private const val AUTO_POWER_OFF_SETTING_KEY = "autopoweroff"
private const val AUTO_POWER_OFF_IMMEDIATELY = "immediately"

private data class DeviceFunctionSettingEndpoint(
    val pathSuffix: String,
    val simulatorPath: String,
    val values: Set<String>,
) {
    val settingValues: Set<String>
        get() = values - AUTO_POWER_OFF_IMMEDIATELY
}

private val DEVICE_FUNCTION_SETTING_ENDPOINTS = linkedMapOf(
    BEEP_SETTING_KEY to DeviceFunctionSettingEndpoint(
        pathSuffix = "/functions/beep",
        simulatorPath = "/ccapi/device-settings/beep",
        values = setOf("enable", "disable", "disabletouch"),
    ),
    DISPLAY_OFF_SETTING_KEY to DeviceFunctionSettingEndpoint(
        pathSuffix = "/functions/displayoff",
        simulatorPath = "/ccapi/device-settings/display-off",
        values = setOf("10", "20", "30", "60", "120", "180"),
    ),
    AUTO_POWER_OFF_SETTING_KEY to DeviceFunctionSettingEndpoint(
        pathSuffix = "/functions/autopoweroff",
        simulatorPath = "/ccapi/device-settings/auto-power-off",
        values = setOf("30", "60", "120", "180", "300", "600", "disable", AUTO_POWER_OFF_IMMEDIATELY),
    ),
)
private val DEVICE_FUNCTION_SETTING_KEYS = DEVICE_FUNCTION_SETTING_ENDPOINTS.keys
private const val SOUND_RECORDING_LEVEL_SETTING_KEY = "soundrecordinglevel"
private const val SOUND_RECORDING_LEVEL_PATH_SUFFIX = "/shooting/settings/soundrecording/level"
private val SOUND_RECORDING_LEVEL_VALUES = (0..63).map(Int::toString).toSet()
private const val SOUND_RECORDING_SETTING_KEY = "soundrecording"
private const val WIND_FILTER_SETTING_KEY = "windfilter"
private const val ATTENUATOR_SETTING_KEY = "attenuator"
private data class SoundRecordingEndpoint(
    val pathSuffix: String,
    val simulatorPath: String,
    val values: Set<String>,
)
private val SOUND_RECORDING_ENDPOINTS = linkedMapOf(
    SOUND_RECORDING_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording",
        simulatorPath = "sound-recording",
        values = linkedSetOf("auto", "manual", "disable"),
    ),
    WIND_FILTER_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/windfilter",
        simulatorPath = "wind-filter",
        values = linkedSetOf("auto", "enable", "disable"),
    ),
    ATTENUATOR_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/attenuator",
        simulatorPath = "attenuator",
        values = linkedSetOf("enable", "disable", "auto", "manual"),
    ),
)
private val SOUND_RECORDING_SETTING_KEYS = SOUND_RECORDING_ENDPOINTS.keys
private const val MAX_STRUCTURED_SETTING_OPTIONS = 256
private const val MAX_FOCUS_BRACKETING_OPTIONS = 1024
private const val FOCUS_BRACKETING_SETTING_KEY = "focusbracketing"
private const val FOCUS_BRACKETING_NUMBER_SETTING_KEY = "focusbracketingnumberofshots"
private const val FOCUS_BRACKETING_INCREMENT_SETTING_KEY = "focusbracketingfocusincrement"
private const val FOCUS_BRACKETING_SMOOTHING_SETTING_KEY = "focusbracketingexposuresmoothing"
private data class FocusBracketingStringEndpoint(
    val pathSuffix: String,
    val simulatorPath: String,
    val values: Set<String>,
)
private data class FocusBracketingIntegerEndpoint(
    val pathSuffix: String,
    val simulatorPath: String,
)
private val FOCUS_BRACKETING_STRING_ENDPOINTS = linkedMapOf(
    FOCUS_BRACKETING_SETTING_KEY to FocusBracketingStringEndpoint(
        pathSuffix = "/shooting/settings/focusbracketing",
        simulatorPath = "focus-bracketing",
        values = linkedSetOf("enable", "disable"),
    ),
    FOCUS_BRACKETING_SMOOTHING_SETTING_KEY to FocusBracketingStringEndpoint(
        pathSuffix = "/shooting/settings/focusbracketing/exposuresmoothing",
        simulatorPath = "focus-bracketing/exposure-smoothing",
        values = linkedSetOf("enable", "disable"),
    ),
)
private val FOCUS_BRACKETING_INTEGER_ENDPOINTS = linkedMapOf(
    FOCUS_BRACKETING_NUMBER_SETTING_KEY to FocusBracketingIntegerEndpoint(
        pathSuffix = "/shooting/settings/focusbracketing/numberofshots",
        simulatorPath = "focus-bracketing/number-of-shots",
    ),
    FOCUS_BRACKETING_INCREMENT_SETTING_KEY to FocusBracketingIntegerEndpoint(
        pathSuffix = "/shooting/settings/focusbracketing/focusincrement",
        simulatorPath = "focus-bracketing/focus-increment",
    ),
)
private val FOCUS_BRACKETING_STRING_SETTING_KEYS = FOCUS_BRACKETING_STRING_ENDPOINTS.keys
private val FOCUS_BRACKETING_INTEGER_SETTING_KEYS = FOCUS_BRACKETING_INTEGER_ENDPOINTS.keys
private val FOCUS_BRACKETING_SETTING_KEYS =
    FOCUS_BRACKETING_STRING_SETTING_KEYS + FOCUS_BRACKETING_INTEGER_SETTING_KEYS
private val FOCUS_BRACKETING_SIMULATOR_VALUES = mapOf(
    FOCUS_BRACKETING_NUMBER_SETTING_KEY to (2..999).map(Int::toString).toSet(),
    FOCUS_BRACKETING_INCREMENT_SETTING_KEY to (1..10).map(Int::toString).toSet(),
)
private const val MAX_STRING_SETTING_OPTIONS = 256
private const val MAX_STRING_SETTING_VALUE_LENGTH = 128
private const val MOVIE_QUALITY_SETTING_KEY = "moviequality"
private const val HIGH_FRAME_RATE_SETTING_KEY = "highframerate"
private const val MOVIE_CROPPING_SETTING_KEY = "moviecropping"
private const val MOVIE_FORMAT_SETTING_KEY = "movieformat"
private data class MovieSettingEndpoint(
    val pathSuffix: String,
    val simulatorPath: String,
    val values: Set<String>? = null,
)
private val MOVIE_SETTING_ENDPOINTS = linkedMapOf(
    MOVIE_QUALITY_SETTING_KEY to MovieSettingEndpoint(
        pathSuffix = "/shooting/settings/moviequality",
        simulatorPath = "movie-settings/quality",
    ),
    HIGH_FRAME_RATE_SETTING_KEY to MovieSettingEndpoint(
        pathSuffix = "/shooting/settings/highframerate",
        simulatorPath = "movie-settings/high-frame-rate",
        values = linkedSetOf("enable", "disable"),
    ),
    MOVIE_CROPPING_SETTING_KEY to MovieSettingEndpoint(
        pathSuffix = "/shooting/settings/moviecropping",
        simulatorPath = "movie-settings/cropping",
        values = linkedSetOf("enable", "disable"),
    ),
    MOVIE_FORMAT_SETTING_KEY to MovieSettingEndpoint(
        pathSuffix = "/shooting/settings/movieformat",
        simulatorPath = "movie-settings/format",
        values = linkedSetOf("raw", "mp4"),
    ),
)
private val MOVIE_SETTING_KEYS = MOVIE_SETTING_ENDPOINTS.keys
private val MOVIE_SIMULATOR_VALUES = mapOf(
    MOVIE_QUALITY_SETTING_KEY to setOf(
        "3840x2160_5994_ipb_standard",
        "1920x1080_2997_ipb_standard",
    ),
    HIGH_FRAME_RATE_SETTING_KEY to setOf("enable", "disable"),
    MOVIE_CROPPING_SETTING_KEY to setOf("enable", "disable"),
    MOVIE_FORMAT_SETTING_KEY to setOf("raw", "mp4"),
)
private const val DIRECTORY_SELECTION_SETTING_KEY = "directoryselection"
private const val DIRECTORY_SELECTION_PATH_SUFFIX = "/functions/directory/directoryselection"
private const val DIRECTORY_CREATE_PATH_SUFFIX = "/functions/directory/createdirectory"
private val DIRECTORY_CREATE_NAME_PATTERN = Regex("^(?:[A-Z0-9_]{5})?$")
private val DIRECTORY_CREATED_NAME_PATTERN = Regex("^[A-Z0-9_]{5}$")
private val DIRECTORY_SELECTION_PATTERN = Regex("^[0-9]{3}[A-Z0-9_]{5}$")
private data class DirectoryOperations(
    val read: CcapiApiOperation,
    val write: CcapiApiOperation,
    val create: CcapiApiOperation,
)
private data class FileNamingEndpoint(
    val pathSuffix: String,
    val responseKey: String,
)
private val FILE_NAMING_ENDPOINTS = linkedMapOf(
    CameraFileNamingField.STILL_FILENAME_MODE to
        FileNamingEndpoint("/functions/filename/stills/filename", "value"),
    CameraFileNamingField.STILL_USER_SETTING_1 to
        FileNamingEndpoint("/functions/filename/stills/usersetting1", "usersetting1"),
    CameraFileNamingField.STILL_USER_SETTING_2 to
        FileNamingEndpoint("/functions/filename/stills/usersetting2", "usersetting2"),
    CameraFileNamingField.MOVIE_INDEX to
        FileNamingEndpoint("/functions/filename/movies/index", "index"),
    CameraFileNamingField.MOVIE_REEL_NUMBER to
        FileNamingEndpoint("/functions/filename/movies/reelnum", "value"),
    CameraFileNamingField.MOVIE_CLIP_NUMBER to
        FileNamingEndpoint("/functions/filename/movies/clipnum", "value"),
    CameraFileNamingField.MOVIE_USER_DEFINED to
        FileNamingEndpoint("/functions/filename/movies/userdefined", "userdefined"),
)
private val STILL_FILENAME_MODES = setOf("preset_code", "usersetting1", "usersetting2")

private fun Any?.toExactJsonInt(): Int? = when (this) {
    is Byte -> toInt()
    is Short -> toInt()
    is Int -> this
    is Long -> takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    else -> null
}

private fun JSONObject.toBoundedIntegerRangeValues(
    maximumOptions: Int = MAX_STRUCTURED_SETTING_OPTIONS,
): List<String> {
    val minimum = opt("min").toExactJsonInt() ?: return emptyList()
    val maximum = opt("max").toExactJsonInt() ?: return emptyList()
    val step = opt("step").toExactJsonInt() ?: return emptyList()
    if (step <= 0 || minimum > maximum) return emptyList()
    val count = ((maximum.toLong() - minimum.toLong()) / step.toLong()) + 1L
    if (count !in 1..maximumOptions.toLong()) return emptyList()
    return List(count.toInt()) { index -> (minimum.toLong() + index.toLong() * step).toString() }
}

private fun JSONObject.toValidatedMovieModeSetting(): JSONObject? {
    val status = opt("status") as? String ?: opt("value") as? String ?: return null
    if (status !in MOVIE_MODE_VALUES) return null
    return JSONObject()
        .put("value", status)
        .put("ability", org.json.JSONArray(MOVIE_MODE_VALUES.toList()))
}

private fun JSONObject.toValidatedCardSelectionSetting(): JSONObject? {
    val current = opt("value") as? String ?: return null
    val rawAbility = optJSONArray("ability") ?: return null
    val values = runCatching { rawAbility.toStringList() }.getOrNull() ?: return null
    if (
        current !in CARD_SELECTION_VALUES ||
        values.size < 2 ||
        values.toSet().size != values.size ||
        values.any { it !in CARD_SELECTION_VALUES } ||
        current !in values
    ) return null
    return JSONObject()
        .put("value", current)
        .put("ability", org.json.JSONArray(values))
}

private fun JSONObject.toValidatedDirectorySelectionSetting(): JSONObject? {
    val current = opt("value") as? String ?: return null
    val rawAbility = optJSONArray("ability") ?: return null
    val values = runCatching { rawAbility.toStringList() }.getOrNull() ?: return null
    if (
        values.isEmpty() ||
        values.size > MAX_STRING_SETTING_OPTIONS ||
        values.toSet().size != values.size ||
        values.any { !DIRECTORY_SELECTION_PATTERN.matches(it) } ||
        current !in values
    ) return null
    return JSONObject()
        .put("value", current)
        .put("ability", org.json.JSONArray(values))
}

private fun CameraFileNaming.value(field: CameraFileNamingField): String = when (field) {
    CameraFileNamingField.STILL_FILENAME_MODE -> stillFilenameMode
    CameraFileNamingField.STILL_USER_SETTING_1 -> stillUserSetting1
    CameraFileNamingField.STILL_USER_SETTING_2 -> stillUserSetting2
    CameraFileNamingField.MOVIE_INDEX -> movieIndex
    CameraFileNamingField.MOVIE_REEL_NUMBER -> movieReelNumber.toString()
    CameraFileNamingField.MOVIE_CLIP_NUMBER -> movieClipNumber.toString()
    CameraFileNamingField.MOVIE_USER_DEFINED -> movieUserDefined
}

private fun JSONObject.toValidatedFileRange(maximumAllowed: Int): Pair<Int, CameraIntegerRange>? {
    val current = opt("value").toExactJsonInt() ?: return null
    val ability = optJSONObject("ability") ?: return null
    val minimum = ability.opt("min").toExactJsonInt() ?: return null
    val maximum = ability.opt("max").toExactJsonInt() ?: return null
    val step = ability.opt("step").toExactJsonInt() ?: return null
    val range = CameraIntegerRange(minimum, maximum, step)
    if (
        minimum < 1 || maximum > maximumAllowed || minimum > maximum || step <= 0 ||
        !range.accepts(current.toString())
    ) return null
    return current to range
}

private fun JSONObject.toValidatedBridgeRange(maximumAllowed: Int): CameraIntegerRange? {
    val minimum = opt("minimum").toExactJsonInt() ?: return null
    val maximum = opt("maximum").toExactJsonInt() ?: return null
    val step = opt("step").toExactJsonInt() ?: return null
    if (minimum < 1 || maximum > maximumAllowed || minimum > maximum || step <= 0) return null
    return CameraIntegerRange(minimum, maximum, step)
}

private fun JSONObject.toValidatedFileNaming(): CameraFileNaming? {
    val mode = opt("stillFilenameMode") as? String ?: return null
    val options = runCatching { optJSONArray("stillFilenameModeOptions")?.toStringList() }.getOrNull()
        ?: return null
    val stillUserSetting1 = opt("stillUserSetting1") as? String ?: return null
    val stillUserSetting2 = opt("stillUserSetting2") as? String ?: return null
    val movieIndex = opt("movieIndex") as? String ?: return null
    val movieReelNumber = opt("movieReelNumber").toExactJsonInt() ?: return null
    val movieReelRange = optJSONObject("movieReelRange")?.toValidatedBridgeRange(9999) ?: return null
    val movieClipNumber = opt("movieClipNumber").toExactJsonInt() ?: return null
    val movieClipRange = optJSONObject("movieClipRange")?.toValidatedBridgeRange(999) ?: return null
    val movieUserDefined = opt("movieUserDefined") as? String ?: return null
    if (
        options.isEmpty() || options.size > STILL_FILENAME_MODES.size || options.toSet().size != options.size ||
        options.any { it !in STILL_FILENAME_MODES } || mode !in options
    ) return null
    val result = CameraFileNaming(
        stillFilenameMode = mode,
        stillFilenameModeOptions = options,
        stillUserSetting1 = stillUserSetting1,
        stillUserSetting2 = stillUserSetting2,
        movieIndex = movieIndex,
        movieReelNumber = movieReelNumber,
        movieReelRange = movieReelRange,
        movieClipNumber = movieClipNumber,
        movieClipRange = movieClipRange,
        movieUserDefined = movieUserDefined,
    )
    return result.takeIf {
        it.accepts(CameraFileNamingField.STILL_USER_SETTING_1, stillUserSetting1) &&
            it.accepts(CameraFileNamingField.STILL_USER_SETTING_2, stillUserSetting2) &&
            it.accepts(CameraFileNamingField.MOVIE_INDEX, movieIndex) &&
            it.accepts(CameraFileNamingField.MOVIE_REEL_NUMBER, movieReelNumber.toString()) &&
            it.accepts(CameraFileNamingField.MOVIE_CLIP_NUMBER, movieClipNumber.toString()) &&
            it.accepts(CameraFileNamingField.MOVIE_USER_DEFINED, movieUserDefined)
    }
}

private fun Map<CameraFileNamingField, JSONObject>.toValidatedFileNaming(): CameraFileNaming? {
    if (keys != FILE_NAMING_ENDPOINTS.keys) return null
    val modeResponse = getValue(CameraFileNamingField.STILL_FILENAME_MODE)
    val mode = modeResponse.opt("value") as? String ?: return null
    val options = runCatching { modeResponse.optJSONArray("ability")?.toStringList() }.getOrNull()
        ?: return null
    if (
        options.isEmpty() || options.size > STILL_FILENAME_MODES.size || options.toSet().size != options.size ||
        options.any { it !in STILL_FILENAME_MODES } || mode !in options
    ) return null
    val stillUserSetting1 = getValue(CameraFileNamingField.STILL_USER_SETTING_1).opt("usersetting1") as? String
        ?: return null
    val stillUserSetting2 = getValue(CameraFileNamingField.STILL_USER_SETTING_2).opt("usersetting2") as? String
        ?: return null
    val movieIndex = getValue(CameraFileNamingField.MOVIE_INDEX).opt("index") as? String ?: return null
    val reel = getValue(CameraFileNamingField.MOVIE_REEL_NUMBER).toValidatedFileRange(9999) ?: return null
    val clip = getValue(CameraFileNamingField.MOVIE_CLIP_NUMBER).toValidatedFileRange(999) ?: return null
    val movieUserDefined = getValue(CameraFileNamingField.MOVIE_USER_DEFINED).opt("userdefined") as? String
        ?: return null
    val result = CameraFileNaming(
        stillFilenameMode = mode,
        stillFilenameModeOptions = options,
        stillUserSetting1 = stillUserSetting1,
        stillUserSetting2 = stillUserSetting2,
        movieIndex = movieIndex,
        movieReelNumber = reel.first,
        movieReelRange = reel.second,
        movieClipNumber = clip.first,
        movieClipRange = clip.second,
        movieUserDefined = movieUserDefined,
    )
    return result.takeIf {
        it.accepts(CameraFileNamingField.STILL_USER_SETTING_1, stillUserSetting1) &&
            it.accepts(CameraFileNamingField.STILL_USER_SETTING_2, stillUserSetting2) &&
            it.accepts(CameraFileNamingField.MOVIE_INDEX, movieIndex) &&
            it.accepts(CameraFileNamingField.MOVIE_REEL_NUMBER, reel.first.toString()) &&
            it.accepts(CameraFileNamingField.MOVIE_CLIP_NUMBER, clip.first.toString()) &&
            it.accepts(CameraFileNamingField.MOVIE_USER_DEFINED, movieUserDefined)
    }
}

private fun JSONObject.toValidatedStringAbilitySetting(allowedValues: Set<String>?): JSONObject? {
    val current = opt("value") as? String ?: return null
    val rawAbility = optJSONArray("ability") ?: return null
    val values = runCatching { rawAbility.toStringList() }.getOrNull() ?: return null
    if (
        values.size < 2 ||
        values.size > MAX_STRING_SETTING_OPTIONS ||
        values.toSet().size != values.size ||
        values.any {
            it.isBlank() ||
                it.length > MAX_STRING_SETTING_VALUE_LENGTH ||
                (allowedValues != null && it !in allowedValues)
        } ||
        current !in values
    ) return null
    return JSONObject()
        .put("value", current)
        .put("ability", org.json.JSONArray(values))
}

private fun JSONObject.toValidatedLiveViewMagnificationSetting(): CcapiLiveViewMagnificationSetting? {
    val currentValue = opt("value") as? String ?: return null
    val rawAbility = optJSONArray("ability") ?: return null
    val values = (0 until rawAbility.length()).map { index ->
        rawAbility.opt(index) as? String ?: return null
    }
    if (
        values.size !in 2..LIVE_VIEW_MAGNIFICATION_VALUES.size ||
        values.toSet().size != values.size ||
        "1" !in values ||
        currentValue !in values ||
        values.any { it !in LIVE_VIEW_MAGNIFICATION_VALUES }
    ) return null
    val abilities = values.map { value ->
        LiveViewMagnification.entries.firstOrNull { it.value.toString() == value } ?: return null
    }
    val current = abilities.firstOrNull { it.value.toString() == currentValue } ?: return null
    return CcapiLiveViewMagnificationSetting(current = current, abilities = abilities)
}

private fun JSONObject.toValidatedZoomSetting(): JSONObject? {
    return toValidatedIntegerRangeSetting()
}

private fun JSONObject.toValidatedIntegerRangeSetting(
    maximumOptions: Int = MAX_STRUCTURED_SETTING_OPTIONS,
): JSONObject? {
    val current = opt("value").toExactJsonInt() ?: return null
    val ability = optJSONObject("ability") ?: return null
    val values = ability.toBoundedIntegerRangeValues(maximumOptions)
    val currentValue = current.toString()
    if (values.size < 2 || currentValue !in values) return null
    return JSONObject()
        .put("value", currentValue)
        .put("ability", org.json.JSONArray(values))
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
