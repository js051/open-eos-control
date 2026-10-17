package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
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
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor

internal class CcapiLiveViewReleaseException(cause: Throwable) :
    IllegalStateException("Live View stop was not confirmed. Retry stopping Live View before starting again.", cause)

private const val MAX_CCAPI_EVENT_BODY_BYTES = 256 * 1024
// Local polling safeguards, not limits advertised by the Canon protocol.
private const val MAX_MEDIA_INVENTORY_REQUESTS = 512
private const val MAX_MEDIA_INVENTORY_CONTAINERS = 512

private const val MAX_CCAPI_EVENT_KEYS = 64
private const val MAX_CCAPI_EVENT_KEY_CHARS = 128
private const val MAX_DEVICE_STATUS_TEXT_CHARS = 512
private const val CCAPI_EVENT_READ_TIMEOUT_SECONDS = 40L
private const val CCAPI_EVENT_CALL_TIMEOUT_SECONDS = 45L
private const val MULTIPART_OPEN_TIMEOUT_MILLIS = 10_000L
private val MULTIPART_START_RETRY_DELAYS_MILLIS = longArrayOf(100L, 200L, 400L, 800L)
private val JPEG_FRAME_BUSY_RETRY_DELAYS_MILLIS = longArrayOf(50L, 100L)
private const val CCAPI_NO_API_LIST_VALUE = "No list of APIs"
private const val CCAPI_DEVELOPER_API_PATH = "/ccapi/ver100/topurlfordev"
private val CCAPI_CONTENTS_FOLDER_PATTERN = Regex("/ccapi/ver[0-9]+/contents(?:/.+)?")
private val CANON_DATETIME_FORMATTER = DateTimeFormatter.ofPattern(
    "EEE, dd MMM yyyy HH:mm:ss xx",
    Locale.US,
)

private data class CcapiApiOperation(
    val method: String,
    val path: String,
)

private enum class CcapiLiveViewStopMethod {
    DELETE,
    POST_OFF,
}

private data class CcapiJpegLiveViewOperations(
    val startLiveView: CcapiApiOperation,
    val stopLiveView: CcapiApiOperation,
    val stopMethod: CcapiLiveViewStopMethod,
    val framePaths: List<String>,
)

private data class CcapiMultipartOperations(
    val startLiveView: CcapiApiOperation,
    val stopLiveView: CcapiApiOperation,
    val stopMethod: CcapiLiveViewStopMethod,
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

private class MediaInventoryTraversalLimitException : IllegalStateException()

private data class MediaOrderingInfo(
    val captureTime: String?,
    val sizeBytes: Long?,
)

private class CcapiHttpException(
    override val statusCode: Int,
    message: String,
) : IllegalStateException(message), ConnectionHttpFailure

class CcapiClient(
    baseUrl: String,
    httpClient: OkHttpClient? = null,
    private val treatAsSimulator: Boolean? = null,
    username: String = "",
    password: String = "",
    private val rtpDestinationAddress: String? = null,
    private val rtpSessionFactory: CcapiRtpSessionFactory? = null,
    private val jpegFrameValidator: ((ByteArray) -> Boolean)? = null,
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
    // Bounded single-attempt commands and their reads: no redirect, authentication replay, HTTP cache or retry.
    private val captureConfigurationHttpClient = this.httpClient.newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE).cache(null)
        .apply { if (this@CcapiClient.httpClient.callTimeoutMillis == 0 ||
            this@CcapiClient.httpClient.callTimeoutMillis > 15_000) callTimeout(15, TimeUnit.SECONDS) }
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code in setOf(408, 421, 503)) {
                val code = response.code
                chain.request().tag(RecordingRequestEvidence::class.java)?.rejected = true
                response.close()
                throw IOException("Single-attempt request rejected (HTTP $code).")
            }
            response
        }.build()
    private val mutationHttpClient = this.httpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .build()
    private val singleAttemptDownloadHttpClient = this.httpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(okhttp3.Authenticator.NONE)
        .proxyAuthenticator(okhttp3.Authenticator.NONE)
        .cache(null)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!automaticJpegOriginalResponseAdmitted(response.code, response.headers("Content-Range").isNotEmpty())) {
                // Fail before OkHttp's follow-up interceptor can repeat a 503/Retry-After: 0.
                // Even a complete-looking JPEG in a 206/201/range response is not an original.
                response.close()
                throw IOException("Original media request did not return a complete HTTP 200 response.")
            }
            response
        }
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val eventHttpClient = captureConfigurationHttpClient.newBuilder()
        .readTimeout(CCAPI_EVENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CCAPI_EVENT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val multipartHttpClient = this.httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val activeEventCall = AtomicReference<Call?>(null)
    private val focusInfoHttpClient = this.httpClient.newBuilder()
        .callTimeout(1, TimeUnit.SECONDS)
        .build()

    var isRealCamera = false
        private set
    var apiVersionPrefix = "/ccapi/ver100"
        private set

    private var apiVersionPrefixes = listOf("/ccapi/ver100")
    private var isRecording: Boolean? = null
    private val recordingSequence = java.util.concurrent.atomic.AtomicLong()
    private val recordingEventFence = RecordingEventFence()
    private var retainedRecordingOperation: CcapiApiOperation? = null
    private var bulbExposureActive = false
    private val heldAutofocus = HeldAutofocusSession()
    private val heldShutter = ShutterReleaseSession()
    private var latestTemperatureStatus: CameraTemperatureStatus? = null
    private val settingPathsByKey = mutableMapOf<String, String>()
    private val settingReadPathsByKey = mutableMapOf<String, String>()
    private val settingValuesByKey = mutableMapOf<String, Set<String>>()
    private val structuredSettingPathsByKey = mutableMapOf<String, String>()
    private val structuredSettingValuesByKey = mutableMapOf<String, Set<String>>()
    private val structuredSettingCurrentValues = mutableMapOf<String, JSONObject>()
    private val shootingSettingResponseCache = mutableMapOf<String, JSONObject>()
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
    private val rejectedLiveViewSizes = mutableSetOf<LiveViewSize>()
    private var activeLiveViewSize = LiveViewSize.MEDIUM
    private val liveViewGeometry = LiveViewGeometryState<CcapiLiveViewGeometry>()
    private var activeLiveViewSource: LiveViewSource? = null
        set(value) {
            if (field != value) liveViewGeometry.reset()
            field = value
        }
    private var activeJpegLiveViewOperations: CcapiJpegLiveViewOperations? = null
    private var activeMultipartLiveViewOperations: CcapiMultipartOperations? = null
    private var multipartLiveViewSession: CcapiMultipartLiveViewSession? = null
    private var firstLiveViewFrame: LiveViewFrame? = null
    private var liveViewStartAttempt = 0L
    // Owned before sending a start: a missing response cannot prove that the camera stayed stopped.
    @Volatile private var pendingLiveViewStop: (suspend () -> Unit)? = null
    @Volatile private var pendingLiveViewLocalClose: (suspend () -> Unit)? = null
    @Volatile private var pendingLiveViewMaintenanceOwned = false
    private var simulatorEventSequence = 0L
    private var mediaDescendingOrderSupported: Boolean? = null
    private val mediaOrderingInfoCache = object : LinkedHashMap<String, MediaOrderingInfo>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaOrderingInfo>?): Boolean =
            size > MAX_MEDIA_ORDERING_CACHE_ITEMS
    }

    var nativeLiveViewSession: NativeLiveViewSession? = null
        private set

    fun observedFeatureSnapshot(): Set<CameraFeature> = observedFeatures.toSet()

    fun currentLiveViewSource(): LiveViewSource? = activeLiveViewSource

    val liveViewStopRequired: Boolean get() = pendingLiveViewStop != null
    val maintenanceStopOwned: Boolean get() = pendingLiveViewStop != null && pendingLiveViewMaintenanceOwned
    var maintenanceSessionOwned: Boolean = false
        private set

    fun retireLiveViewLocally() {
        firstLiveViewFrame = null
        liveViewGeometry.retire()
        multipartLiveViewSession?.close()
        multipartLiveViewSession = null
        nativeLiveViewSession?.close()
        nativeLiveViewSession = null
        activeLiveViewSource = null
        liveViewGeometry.retire()
        // pendingLiveViewStop retains its original remote routes/session until acknowledgement.
    }

    suspend fun retireMaintenanceLocally() {
        liveViewGeometry.retire()
        firstLiveViewFrame = null
        activeEventCall.getAndSet(null)?.cancel()
        pendingLiveViewLocalClose?.invoke()
        pendingLiveViewLocalClose = null
        closeCcapiMultipartSession(multipartLiveViewSession)
        multipartLiveViewSession = null
        nativeLiveViewSession?.close()
        nativeLiveViewSession = null
        // No remote Stop, event DELETE, retry, or mode write during lifecycle retirement.
    }

    suspend fun closeMaintenanceSession(): Boolean = maintenanceCloseOnce(liveViewStopRequired,
        releasePhysical = { maintenancePhysicalReleaseOnce(
            autofocus = { heldAutofocus.retryStop() },
            shutter = { if (heldShutter.hasPendingRelease) retryShutterRelease() }) },
        stop = { maintenanceStopRecoveryScope { stopLiveView() } }, retireLocal = { retireMaintenanceLocally() })

    suspend fun close() {
        liveViewGeometry.retire()
        if (maintenanceSessionOwned) {
            check(!closeMaintenanceSession()) { "Live View Stop remains unconfirmed after local retirement." }
            return
        }
        runCatching { heldAutofocus.retryStop() }
        runCatching { stopEventPolling() }
        runCatching { retryShutterRelease() }
        runCatching { stopLiveView() }
    }

    suspend fun initialize() {
        liveViewGeometry.reset()
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
        val failureReasons = linkedSetOf<ConnectionFailureReason>()

        // 1. Try GET /ccapi
        val success1 = discoverApiAt("/ccapi", errors, failureReasons)

        if (success1) {
            isRealCamera = true
            return
        }

        // 2. Try GET /ccapi/
        val success2 = discoverApiAt("/ccapi/", errors, failureReasons)

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
                    val call = newCameraCall(request)
                    val cancelCall = AtomicBoolean(true)
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { if (cancelCall.get()) call.cancel() }
                    }
                    try {
                        call.execute().use { response ->
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
                                failureReasons += httpConnectionFailureReason(response.code)
                                errors.add("GET $prefix/deviceinformation: HTTP ${response.code}")
                                false
                            }
                        }
                    } catch (exception: Exception) {
                        currentCoroutineContext().ensureActive()
                        throw exception
                    } finally {
                        cancelCall.set(false)
                        watcher.cancel()
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (e: Exception) {
                recordDiscoveryFailure("GET $prefix/deviceinformation", e)
                failureReasons += connectionFailureReason(e)
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
        throw CcapiDiscoveryException(errorMessage, failureReasons)
    }

    private suspend fun discoverApiAt(
        path: String,
        errors: MutableList<String>,
        failureReasons: MutableSet<ConnectionFailureReason>,
    ): Boolean {
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
            failureReasons += connectionFailureReason(error)
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
                recordingObservation = null, // Canon event queues provide change hints, not a fresh REC snapshot.
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
            getJson("/ccapi/status").toCameraStatus().let { value ->
                value.copy(recordingObservation = value.recording?.let { RecordingObservation(it,
                    recordingSequence.incrementAndGet(), RecordingObservationSource.SIMULATOR_STATUS) })
            }.also {
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
            if (advancedSettings.any { it.key in SOUND_RECORDING_LEVEL_SETTING_KEYS }) {
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
                supportedFeatures.add(CameraFeature.MEDIA_ARCHIVE)
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
                    val sizes = capabilities.sizes.filterNot(rejectedLiveViewSizes::contains)
                    capabilities.copy(
                        sizes = sizes,
                        defaultSize = LiveViewSize.MEDIUM.takeIf(sizes::contains)
                            ?: sizes.firstOrNull()
                            ?: activeLiveViewSize,
                    )
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
                heldAutofocusSupported = autofocusOperation() != null,
                shutterAutofocusSupported = directShutterOperation() != null || manualShutterOperation() != null,
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
            val recordingReadEpoch = recordingEventFence.capture()
            val json = requestEventJson(
                Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .build(),
            )
            recordingEventFence.accept(recordingReadEpoch) { applyRecordingEvent(json) }
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

    private fun applyRecordingEvent(event: JSONObject) {
        val recbutton = event.optJSONObject("recbutton") ?: return
        val fields = recbutton.keys().asSequence().associateWith { recbutton.opt(it) }
        val recording = recordingEventValue(fields) ?: return
        isRecording = recording
        // Preserve display-compatible event hints only. Queued delivery cannot prove current REC.
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
        writeSetting(key, value)
        return status()
    }

    fun shootingSettingAccess(request: ShootingSettingRequest): ShootingSettingAccess {
        val route = resolveShootingSettingRoute(request)
        return ShootingSettingAccess("Canon CCAPI", write = { frozen, evidence ->
            require(frozen == request); writeShootingSetting(frozen, evidence, route)
        }, read = { frozen -> require(frozen == request); readShootingSetting(frozen, route) })
    }

    private fun resolveShootingSettingRoute(request: ShootingSettingRequest): ShootingSettingRoute? {
        if (!isRealCamera) return null
        val base = request.key.splitStructuredSettingKey()?.first
        val actual = base ?: shootingSettingAliases(request.key).firstOrNull { it in settingReadPathsByKey }
            ?: error("The setting has no advertised read route.")
        val read = requireNotNull(settingReadPathsByKey[actual])
        val write = structuredSettingPathsByKey[request.key] ?: requireNotNull(settingPathsByKey[actual])
        return ShootingSettingRoute(actual, read, write, actual.takeIf { read.endsWith("/shooting/settings") })
    }

    suspend fun writeShootingSetting(request: ShootingSettingRequest, evidence: ShootingSettingEvidence) =
        writeShootingSetting(request, evidence, resolveShootingSettingRoute(request))

    private suspend fun writeShootingSetting(request: ShootingSettingRequest, evidence: ShootingSettingEvidence, route: ShootingSettingRoute?) {
        require(evidence.request == request)
        withContext(ShootingSettingWriteContext(evidence, route)) {
            val key = request.key
            val value = request.requestedValue
            if (isRealCamera) {
                val fresh = freshShootingSetting(request, route)
                require(acceptsShootingSettingValue(fresh, value)) { "The requested value is no longer advertised." }
                route?.key?.let { settingValuesByKey[it] = fresh.values.toSet() }
            }
            when (key) {
                "iso", "shutter", "aperture", "whitebalance" -> if (isRealCamera) {
                    putSettingValue(shootingSettingAliases(key), value)
                } else if (key == "whitebalance") {
                    patchJson("/ccapi/white-balance", JSONObject().put("white_balance", value))
                } else patchJson("/ccapi/exposure", JSONObject().put(key, value))
                else -> writeSetting(key, value)
            }
            observedFeatures.add(featureForSetting(key))
        }
    }

    /** One cache-bypassing GET for the selected advertised resource. Never enumerates contents. */
    suspend fun readShootingSetting(request: ShootingSettingRequest): ShootingSettingObservation =
        readShootingSetting(request, resolveShootingSettingRoute(request))

    private suspend fun readShootingSetting(request: ShootingSettingRequest, route: ShootingSettingRoute?): ShootingSettingObservation {
        val setting = freshShootingSetting(request, route)
        return ShootingSettingObservation(request.key, setting.value)
    }

    private suspend fun freshShootingSetting(request: ShootingSettingRequest, route: ShootingSettingRoute?): CameraSettingControl {
        val key = request.key
        val body = if (isRealCamera) {
            val frozen = requireNotNull(route)
            val response = getJson(frozen.readPath, strictMediaJsonLimit = 65_536, fresh = true, shootingRead = true)
            if (frozen.aggregateKey == null) response else requireNotNull(response.optJSONObject(frozen.aggregateKey))
        } else {
            val capabilities = getJson("/ccapi/capabilities", strictMediaJsonLimit = 65_536, fresh = true, shootingRead = true)
            if (key in setOf("iso", "shutter", "aperture", "whitebalance")) {
                val exposure = getJson("/ccapi/status", strictMediaJsonLimit = 65_536, fresh = true, shootingRead = true).getJSONObject("exposure")
                val field = if (key == "whitebalance") "white_balance" else key
                JSONObject().put("value", exposure.opt(field)).put("ability", capabilities.optJSONArray(field))
            } else requireNotNull(capabilities.optJSONObject(key))
        }
        val structured = key.splitStructuredSettingKey()
        val integer = key == ZOOM_SETTING_KEY || key in SOUND_RECORDING_LEVEL_SETTING_KEYS ||
            key in FOCUS_BRACKETING_INTEGER_SETTING_KEYS || structured?.first == WB_SHIFT_SETTING_KEY
        val rawValue = if (structured != null) body.optJSONObject("value")?.opt(structured.second)
            else if (key == MOVIE_MODE_SETTING_KEY) body.opt("status") ?: body.opt("value") else body.opt("value")
        val current = if (integer) rawValue.toExactJsonInt()?.toString() else rawValue as? String
        val rawAbility = if (structured != null) body.optJSONObject("ability")?.opt(structured.second) else body.opt("ability")
        val choices = if (key == MOVIE_MODE_SETTING_KEY && rawAbility == null) MOVIE_MODE_VALUES.toList()
        else if (integer && rawAbility is JSONObject) rawAbility.toBoundedIntegerRangeValues(1024)
        else if (rawAbility is org.json.JSONArray && rawAbility.length() <= 1024) (0 until rawAbility.length()).map { index ->
            val raw = rawAbility.opt(index)
            if (integer) raw.toExactJsonInt()?.toString() ?: raw as? String ?: error("Invalid numeric setting ability.")
            else raw as? String ?: error("Invalid setting ability.")
        } else error("The camera did not return a fresh setting ability.")
        return CameraSettingControl(key, key, requireNotNull(current), choices).also {
            require(acceptsShootingSettingObservation(it)) { "The camera did not return a valid current setting." }
        }
    }

    private fun shootingSettingAliases(key: String): List<String> = when (key) {
        "shutter" -> listOf("tv", "shutterspeed", "shutter")
        "aperture" -> listOf("av", "aperture")
        "whitebalance" -> listOf("wb", "whitebalance", "white_balance")
        else -> listOf(key)
    }

    private suspend fun writeSetting(key: String, value: String) {
        if (isRealCamera) {
            when {
                key.equals(ZOOM_SETTING_KEY, ignoreCase = true) -> postZoomValue(value)
                key.equals(MOVIE_MODE_SETTING_KEY, ignoreCase = true) -> postMovieModeValue(value)
                key.lowercase() in CARD_SELECTION_SETTING_KEYS -> {
                    if (currentCoroutineContext()[ShootingSettingWriteContext] == null) loadShootingSettings()
                    putSettingValue(listOf(key.lowercase()), value)
                }
                key.lowercase() in DEVICE_FUNCTION_SETTING_KEYS -> {
                    if (currentCoroutineContext()[ShootingSettingWriteContext] == null) loadShootingSettings()
                    putSettingValue(listOf(key.lowercase()), value)
                }
                key.lowercase() in SOUND_RECORDING_SETTING_KEYS -> {
                    if (currentCoroutineContext()[ShootingSettingWriteContext] == null) loadShootingSettings()
                    putSettingValue(listOf(key.lowercase()), value)
                }
                key.lowercase() in SOUND_RECORDING_LEVEL_SETTING_KEYS -> {
                    if (currentCoroutineContext()[ShootingSettingWriteContext] == null) loadShootingSettings()
                    putIntegerSettingValue(key.lowercase(), value)
                }
                key.lowercase() in FOCUS_BRACKETING_STRING_SETTING_KEYS -> {
                    if (currentCoroutineContext()[ShootingSettingWriteContext] == null) loadShootingSettings()
                    putSettingValue(listOf(key.lowercase()), value)
                }
                key.lowercase() in FOCUS_BRACKETING_INTEGER_SETTING_KEYS -> {
                    if (currentCoroutineContext()[ShootingSettingWriteContext] == null) loadShootingSettings()
                    putIntegerSettingValue(key.lowercase(), value)
                }
                key.lowercase() in MOVIE_SETTING_KEYS -> {
                    if (currentCoroutineContext()[ShootingSettingWriteContext] == null) loadShootingSettings()
                    putSettingValue(listOf(key.lowercase()), value)
                }
                key.equals(DIRECTORY_SELECTION_SETTING_KEY, ignoreCase = true) -> {
                    if (currentCoroutineContext()[ShootingSettingWriteContext] == null) loadShootingSettings()
                    putSettingValue(listOf(DIRECTORY_SELECTION_SETTING_KEY), value)
                }
                else -> putSettingValue(listOf(key), value)
            }
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
                key.lowercase() in SOUND_RECORDING_LEVEL_SETTING_KEYS -> {
                    val canonical = key.lowercase()
                    val level = value.toIntOrNull()
                        ?.takeIf { it.toString() == value && value in SOUND_RECORDING_LEVEL_VALUES }
                        ?: error("Sound recording level is not supported.")
                    putOk(
                        "/ccapi/${SOUND_RECORDING_LEVEL_SIMULATOR_PATHS.getValue(canonical)}",
                        JSONObject().put("value", level),
                    )
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
        }
        observedFeatures.add(featureForSetting(key))
    }

    suspend fun createDirectory(name: String, evidence: CaptureConfigurationEvidence = CaptureConfigurationEvidence(
        CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, name),
    )): String = captureConfigurationOperation(evidence) {
        require(evidence.request == CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, name))
        require(DIRECTORY_CREATE_NAME_PATTERN.matches(name)) {
            "Directory name must be empty or exactly five uppercase letters, numbers, or underscores."
        }
        val path = if (isRealCamera) {
            (directoryOperations() ?: throw UnsupportedOperationException(
                "${CameraFeature.DIRECTORY_CONTROL.label} is not supported by this camera's advertised CCAPI ability.",
            )).create.path
        } else "/ccapi/directory"
        val response = postJson(path, JSONObject().put("directoryname", name), captureEvidence = evidence,
            captureResponse = { body ->
                val created = body.opt("directoryname") as? String
                require(created != null && DIRECTORY_CREATED_NAME_PATTERN.matches(created)) {
                    "Camera returned an invalid created directory name."
                }
                evidence.acknowledge(created)
            })
        val created = response.getString("directoryname")
        observedFeatures.add(CameraFeature.DIRECTORY_CONTROL)
        if (isRealCamera) {
            // Preserve the existing read count, but never treat aggregate/cached settings
            // as proof that the canonical directory-selection GET succeeded.
            var observed = false
            loadShootingSettings(directoryReadback = { readback ->
                observed = true
                if (readback == null) evidence.unavailable() else evidence.observe(readback)
            })
            if (!observed) evidence.unavailable()
        }
        created
    }

    suspend fun setFileNaming(field: CameraFileNamingField, value: String,
        evidence: CaptureConfigurationEvidence = CaptureConfigurationEvidence(
            CaptureConfigurationRequest(CaptureConfigurationOperation.SET_FILE_NAMING, value, field),
        ),
    ): CameraFileNaming = captureConfigurationOperation(evidence) {
        require(evidence.request == CaptureConfigurationRequest(CaptureConfigurationOperation.SET_FILE_NAMING, value, field))
        val frozenOperations = if (isRealCamera) fileNamingOperations() else null
        val current = loadFileNaming()
            ?: error("Camera did not advertise a valid complete Canon file-naming endpoint group.")
        require(current.accepts(field, value)) {
            "Value '$value' is not valid for Canon file-naming field ${field.wireName}."
        }
        val updated = if (isRealCamera) {
            val operations = frozenOperations
                ?: error("Camera did not advertise the complete Canon file-naming endpoint group.")
            val operation = operations.getValue(field).second
            val responseKey = FILE_NAMING_ENDPOINTS.getValue(field).responseKey
            val requestValue: Any = when (field) {
                CameraFileNamingField.MOVIE_REEL_NUMBER, CameraFileNamingField.MOVIE_CLIP_NUMBER -> value.toInt()
                else -> value
            }
            putJson(operation.path, JSONObject().put(responseKey, requestValue), captureEvidence = evidence,
                captureResponse = { response ->
                    check(response.opt(responseKey) == requestValue) {
                        "Canon file-naming control returned an invalid update response."
                    }
                    evidence.acknowledge(value)
                })
            fileNamingLoaded = false
            kotlinx.coroutines.withTimeout(15_000L) { loadFileNaming(force = true, fresh = true) }
                ?: error("Canon file-naming control returned an invalid state after update.")
        } else {
            putJson("/ccapi/file-naming/${field.wireName}", JSONObject().put("value", value),
                captureEvidence = evidence, captureResponse = { response ->
                    val state = response.toValidatedFileNaming()
                        ?: error("Simulator returned an invalid file-naming state.")
                    check(state.value(field) == value) { "Simulator returned an invalid update response." }
                    evidence.acknowledge(value)
                    evidence.observe(CaptureConfigurationReadback(fileNaming = state))
                }).toValidatedFileNaming() ?: error("Simulator returned an invalid file-naming state.")
        }
        evidence.observe(CaptureConfigurationReadback(fileNaming = updated))
        check(updated.value(field) == value) {
            "Canon file-naming control did not return the requested value on refresh."
        }
        fileNamingState = updated
        fileNamingLoaded = true
        observedFeatures.add(CameraFeature.FILE_NAMING_CONTROL)
        updated
    }

    /** Explicit recovery is a fresh bounded read of only this configuration group. */
    suspend fun readCaptureConfiguration(operation: CaptureConfigurationOperation): CaptureConfigurationReadback =
        kotlinx.coroutines.withTimeout(15_000L) {
            when (operation) {
                CaptureConfigurationOperation.CREATE_DIRECTORY -> {
                    val body = if (isRealCamera) {
                        val read = directoryOperations()?.read ?: error("Directory selection is not advertised.")
                        getJson(read.path, strictMediaJsonLimit = 65_536, fresh = true)
                            .toValidatedDirectorySelectionSetting()
                    } else getJson("/ccapi/capabilities", strictMediaJsonLimit = 65_536, fresh = true)
                        .optJSONObject(DIRECTORY_SELECTION_SETTING_KEY)?.toValidatedDirectorySelectionSetting()
                    val selected = body?.opt("value") as? String
                    check(selected != null && DIRECTORY_SELECTION_PATTERN.matches(selected)) { "Directory readback unavailable." }
                    CaptureConfigurationReadback(directorySelection = selected,
                        directoryValues = requireNotNull(body).getJSONArray("ability").toStringList())
                }
                CaptureConfigurationOperation.SET_FILE_NAMING -> CaptureConfigurationReadback(
                    fileNaming = loadFileNaming(force = true, fresh = true) ?: error("Complete file-naming readback unavailable."),
                )
            }
        }

    /** One selected route per intent. Recovery bypasses cached status/capability aggregators. */
    fun maintenanceAccess(request: MaintenanceRequest, liveView: LiveViewRequest?): MaintenanceAccess {
        val clockOperations = if (isRealCamera) cameraClockOperations() else null
        val clockRead = clockOperations?.first?.path ?: "/ccapi/ver100/functions/datetime".takeUnless { isRealCamera }
        val clockWrite = clockOperations?.second?.path ?: "/ccapi/ver100/functions/datetime".takeUnless { isRealCamera }
        val cleaning = if (isRealCamera) sensorCleaningOperation()?.path else "/ccapi/sensor-cleaning"
        val sleep = if (isRealCamera) cameraSleepWritePath else "/ccapi/camera-sleep"
        val originalStop = pendingLiveViewStop
        val originalSource = activeLiveViewSource
        val restoreRequest = liveView?.copy(source = originalSource ?: liveView.source, size = activeLiveViewSize)
        val stopEventsPath = if (isRealCamera) eventPollingOperations()?.second?.path else null
        val probe = if (isRealCamera) (apiOperation("GET", "/devicestatus/batterylist")
            ?: apiOperation("GET", "/devicestatus/battery"))?.path else "/ccapi/status"
        when (request.action) {
            MaintenanceAction.CLOCK_SYNC -> check(clockRead != null && clockWrite != null)
            MaintenanceAction.SENSOR_CLEANING -> check(cleaning != null)
            MaintenanceAction.SLEEP -> check(sleep != null)
        }
        if (request.needsStop) maintenanceSessionOwned = true
        return MaintenanceAccess("Canon CCAPI", eventStopRequired = stopEventsPath != null,
            liveViewStopRequired = pendingLiveViewStop != null, stopEvents = {
            activeEventCall.getAndSet(null)?.cancel()
            stopEventsPath?.let { path -> maintenanceJson(Request.Builder().url("$baseUrl$path").delete().build(), 204, empty = true) }
        }, stopLiveView = {
            check(pendingLiveViewStop === originalStop) { "Live View stop owner changed." }
            if (originalStop != null) pendingLiveViewMaintenanceOwned = true
            maintenanceIoScope { stopLiveView() }
        }, dispatch = { frozen, evidence ->
            require(frozen == request)
            requireShutterReleased()
            check(isRecording != true && !bulbExposureActive) { "Stop recording and Bulb before maintenance." }
            val builder = Request.Builder()
            val expected: Int
            when (request.action) {
                MaintenanceAction.CLOCK_SYNC -> {
                    val target = requireNotNull(request.clock)
                    val instant = java.time.Instant.ofEpochMilli(target.epochMillis)
                    val time = instant.atZone(java.time.ZoneOffset.ofTotalSeconds(target.offsetSeconds))
                    evidence.reference(MaintenanceClockReference(instant.epochSecond - 10, instant.epochSecond + 10,
                        target.offsetSeconds / 60, target.daylightSaving))
                    builder.url("$baseUrl${requireNotNull(clockWrite)}").put(JSONObject()
                        .put("datetime", CANON_DATETIME_FORMATTER.format(time)).put("dst", target.daylightSaving)
                        .toString().toRequestBody(jsonMediaType))
                    expected = 200
                }
                MaintenanceAction.SENSOR_CLEANING -> {
                    builder.url("$baseUrl${requireNotNull(cleaning)}").post(JSONObject().put("autopoweroff", request.autoPowerOff)
                        .toString().toRequestBody(jsonMediaType)); expected = if (isRealCamera) 200 else 204
                }
                MaintenanceAction.SLEEP -> {
                    val payload = if (isRealCamera) JSONObject().put("value", AUTO_POWER_OFF_IMMEDIATELY) else JSONObject()
                    builder.url("$baseUrl${requireNotNull(sleep)}")
                    if (isRealCamera) builder.put(payload.toString().toRequestBody(jsonMediaType))
                    else builder.post(payload.toString().toRequestBody(jsonMediaType))
                    expected = if (isRealCamera) 202 else 204
                }
            }
            maintenanceJson(builder.build(), expected, evidence)
        }, read = { frozen ->
            require(frozen == request)
            if (request.action == MaintenanceAction.CLOCK_SYNC) {
                val body = maintenanceJson(Request.Builder().url("$baseUrl${requireNotNull(clockRead)}")
                    .header("Cache-Control", "no-cache, no-store").get().build(), 200)
                val (clock, daylight) = parseCameraClock(requireNotNull(body))
                MaintenanceReadback(MaintenanceClockObservation(clock.toEpochSecond(), clock.offset.totalSeconds / 60, daylight))
            } else {
                val body = maintenanceJson(Request.Builder().url("$baseUrl${requireNotNull(probe) { "No required maintenance probe advertised." }}")
                    .header("Cache-Control", "no-cache, no-store").get().build(), 200)
                if (isRealCamera) {
                    val required = requireNotNull(body)
                    val list = when {
                        probe?.endsWith("/batterylist") == true || required.has("batterylist") -> requireNotNull(required.optJSONArray("batterylist"))
                        required.has("battery") -> requireNotNull(required.optJSONArray("battery"))
                        else -> null
                    }
                    val levels = if (list != null) (0 until list.length()).map { list.optJSONObject(it)?.opt("level") }
                        else listOf(required.opt("level"))
                    check(validMaintenanceBatteryLevels(levels)) { "Required battery observation is malformed." }
                }
                else check(body?.opt("connected") == true)
                MaintenanceReadback()
            }
        }, restore = {
            requireShutterReleased(); check(isRecording != true && !bulbExposureActive)
            if (restoreRequest != null) maintenanceIoScope { startLiveView(restoreRequest) }
        })
    }

    private suspend fun maintenanceJson(request: Request, expected: Int? = null,
        evidence: MaintenanceEvidence? = null, empty: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        currentCoroutineContext()[MaintenanceIoContext]?.requireActive?.invoke()
        val call = newCameraCall(request, true)
        val watchers = maintenanceCancellationWatchers { call.cancel() }
        try {
            currentCoroutineContext().ensureActive(); evidence?.beforeDispatch()
            call.execute().use { response ->
                requireShootingSettingHttpResponse(response.code, response.headers("Content-Range"),
                    response.headers("Content-Length"), response.headers("Transfer-Encoding"))
                check(expected == null || response.code == expected) { "Maintenance response status was not accepted." }
                val text = response.body?.let { body -> body.byteStream().use { readShootingSettingResponseText(it, body.contentLength()) } }.orEmpty()
                if (empty) requireMaintenanceEmptyResponse(response.code, response.headers("Content-Length"), response.headers("Transfer-Encoding"), text)
                val body = if (text.isEmpty()) null else JSONObject(text)
                if (evidence?.request?.action == MaintenanceAction.CLOCK_SYNC) parseCameraClock(requireNotNull(body))
                evidence?.responseReceived()
                body
            }
        } finally { watchers.forEach { it.cancel() } }
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

    suspend fun recordingObservation(): RecordingObservation? = recordingIoScope {
        if (isRealCamera) null else {
            val value = getJson("/ccapi/status").strictRecordingStatus()
            value?.let { RecordingObservation(it, recordingSequence.incrementAndGet(), RecordingObservationSource.SIMULATOR_STATUS) }
        }
    }

    suspend fun startRecording(): CameraStatus = recordingIoScope {
        refreshTemperatureStatusForRestrictedCommand()
        requireMovieRecordingAllowed()
        recordingCommand(RecordingCommand.START)
    }

    suspend fun stopRecording(): CameraStatus = recordingCommand(RecordingCommand.STOP)

    private suspend fun recordingCommand(command: RecordingCommand): CameraStatus = recordingIoScope {
        val operation = if (isRealCamera) {
            if (command == RecordingCommand.STOP) retainedRecordingOperation ?: recordingOperation() else recordingOperation()
        } else null
        if (isRealCamera && enforceAdvertisedOperations && operation == null) {
            error("Camera did not advertise movie recording control.")
        }
        val evidence = currentCoroutineContext()[RecordingRequestEvidence] ?: RecordingRequestEvidence(command)
        if (operation != null) retainedRecordingOperation = operation
        evidence.onAdmission = { recordingEventFence.commandAdmission(); recordingSequence.get() }
        try {
            if (isRealCamera) {
                commandOk(
                    pathSuffix = "/shooting/control/recbutton",
                    payload = JSONObject().put("action", if (command == RecordingCommand.START) "start" else "stop"),
                    operation = operation,
                    recordingEvidence = evidence,
                )
            } else {
                postJson("/ccapi/record/${if (command == RecordingCommand.START) "start" else "stop"}", JSONObject(), evidence)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (evidence.unresolved && (exception is IOException || exception is JSONException)) {
                throw RecordingCommandUnconfirmedException(command, exception)
            }
            throw exception
        } finally {
            if (evidence.attempted) recordingEventFence.commandCompleted()
        }
        observedFeatures.add(CameraFeature.VIDEO_RECORDING)
        evidence.acknowledge()
        recordingStatusAfterAcknowledgement(command).also { evidence.observe(it.recordingObservation) }
    }

    private suspend fun recordingStatusAfterAcknowledgement(command: RecordingCommand): CameraStatus = try {
        status()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        currentCoroutineContext().ensureActive()
        throw RecordingStatusReadbackException(command, exception)
    }

    suspend fun captureStill(autofocus: Boolean = true): CameraStatus {
        requireShutterReleased()
        check(autofocus || (isRealCamera && (directShutterOperation() != null || manualShutterOperation() != null))) {
            "Camera did not advertise capture without autofocus."
        }
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
                    payload = JSONObject().put("af", autofocus),
                    operation = directOperation,
                    stillCapture = true,
                )
            } else {
                heldShutter.pressAndRelease(
                    start = {
                        commandOk(
                            pathSuffix = "/shooting/control/shutterbutton/manual",
                            payload = JSONObject().put("af", autofocus).put("action", "full_press"),
                            operation = manualOperation,
                            stillCapture = true,
                        )
                    },
                    stop = {
                        commandOk(
                            pathSuffix = "/shooting/control/shutterbutton/manual",
                            payload = JSONObject().put("af", false).put("action", "release"),
                            operation = manualOperation,
                            stillCapture = true,
                        )
                    },
                )
            }
        } else {
            postJson("/ccapi/capture/still", JSONObject().put("af", autofocus))
        }
        observedFeatures.add(CameraFeature.STILL_CAPTURE)
        return try {
            status()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Only this separate read follows an acknowledged command (and manual release).
            // Never reinterpret a command/release failure, or replay the shutter here.
            throw CaptureStatusReadbackException(exception)
        }
    }

    suspend fun startBulbExposure(): CameraStatus {
        check(!heldShutter.releaseUnconfirmed) { "The shutter must be released before starting again." }
        if (bulbExposureActive) return status()
        requireShutterReleased()
        val baseline = status()
        requireStillCaptureAllowed()
        val operation = if (isRealCamera) manualShutterOperation() else null
        if (isRealCamera && enforceAdvertisedOperations && operation == null) {
            error("Camera did not advertise manual shutter control for Bulb exposure.")
        }
        // A missing start acknowledgement still leaves a possible exposure to release.
        bulbExposureActive = true
        try {
            heldShutter.start(
                start = {
                    if (isRealCamera) {
                        commandOk(
                            "/shooting/control/shutterbutton/manual",
                            JSONObject().put("af", false).put("action", "full_press"),
                            operation,
                        )
                    } else postJson("/ccapi/bulb/start", JSONObject())
                },
                stop = {
                    if (isRealCamera) {
                        commandOk(
                            "/shooting/control/shutterbutton/manual",
                            JSONObject().put("af", false).put("action", "release"),
                            operation,
                        )
                    } else postJson("/ccapi/bulb/stop", JSONObject())
                },
            )
        } catch (exception: Throwable) {
            bulbExposureActive = heldShutter.hasPendingRelease
            throw exception
        }
        return baseline.copy(bulbExposureActive = true)
    }

    suspend fun stopBulbExposure(): CameraStatus {
        if (!bulbExposureActive) return status()
        retryShutterRelease()
        observedFeatures.add(CameraFeature.BULB_EXPOSURE)
        return status()
    }

    suspend fun retryShutterRelease() {
        heldShutter.retryRelease()
        bulbExposureActive = false
    }

    private fun requireShutterReleased() {
        check(!heldShutter.hasPendingRelease) { "The shutter must be released before another operation." }
    }

    suspend fun holdAutofocus(whileHeld: suspend () -> Unit) {
        check(isRealCamera) { "Held autofocus requires advertised Canon AF start/stop control." }
        val operation = autofocusOperation()
            ?: error("Camera did not advertise AF start/stop control.")
        heldAutofocus.hold(
            start = {
                commandOk("/shooting/control/af", JSONObject().put("action", "start"), operation)
                observedFeatures.add(CameraFeature.AUTOFOCUS)
            },
            stop = { commandOk("/shooting/control/af", JSONObject().put("action", "stop"), operation) },
            whileHeld = whileHeld,
        )
    }

    suspend fun retryAutofocusStop() = heldAutofocus.retryStop()

    suspend fun autofocus(): CameraStatus {
        if (isRealCamera) {
            val operation = autofocusOperation()
            val manualOperation = manualShutterOperation()
            if (enforceAdvertisedOperations && operation == null && manualOperation == null) {
                error("Camera did not advertise autofocus or manual shutter control.")
            }
            if (operation != null || !enforceAdvertisedOperations) {
                withRecoverableFocusRelease(
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
                withRecoverableFocusRelease(
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
            withRecoverableFocusRelease(
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
            withRecoverableFocusRelease(
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
            withRecoverableFocusRelease(
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
        val owner = liveViewGeometry.owner()
        if (!settingsLoaded) loadShootingSettings()
        liveViewGeometry.requireOwner(owner)
        val operations = liveViewMagnificationOperations()
            ?: error("Camera did not advertise same-version GET and PUT Canon Live View magnification control.")
        val setting = liveViewMagnificationSetting
            ?: error("Camera did not return a valid Canon Live View magnification ability list.")
        require(magnification in setting.abilities) {
            "${magnification.value}x Live View magnification is not advertised by this camera."
        }

        // Retire before dispatch, including lost/failed acknowledgements. Reads begun during
        // the mutation are not eligible for the post-command geometry revision.
        val change = liveViewGeometry.beginMutation(owner)
        val multipart = multipartLiveViewSession
        multipart?.discardPendingFrames()
        firstLiveViewFrame = null
        try {
            putOk(
                operations.second.path,
                JSONObject().put("value", magnification.value.toString()),
                checkOwner = { liveViewGeometry.requireRevision(change) },
            )
            liveViewGeometry.requireRevision(change)
            val readback = getJson(operations.first.path, fresh = true).toValidatedLiveViewMagnificationSetting()
                ?: error("Camera returned an invalid Live View magnification readback.")
            liveViewGeometry.requireRevision(change)
            check(readback.current == magnification) {
                "Camera accepted Live View magnification but reported ${readback.current.value}x instead of ${magnification.value}x."
            }
            liveViewMagnificationSetting = readback
            observedFeatures.add(CameraFeature.LIVE_VIEW_MAGNIFICATION)
            return LiveViewMagnificationResult(ok = true, magnification = readback.current)
        } finally {
            multipart?.discardPendingFrames()
            liveViewGeometry.finishMutation(change)
        }
    }

    suspend fun listMedia(
        maximumItems: Int? = null,
        onProgress: (List<CameraMediaItem>) -> Unit = {},
    ): List<CameraMediaItem> {
        require(maximumItems == null || maximumItems > 0) { "Media item limit must be positive." }
        val items = if (isRealCamera) {
            listRealMedia(maximumItems, onProgress)
        } else {
            listSimulatorMedia().let { listed -> maximumItems?.let(listed::take) ?: listed }.also(onProgress)
        }
        observedFeatures.add(CameraFeature.MEDIA_BROWSER)
        return items
    }

    /** Lists identities without the per-item metadata requests used to order recent media. */
    suspend fun listMediaIdentities(maximumItems: Int): CameraMediaInventory {
        require(maximumItems > 0) { "Media item limit must be positive." }
        val inventory = if (isRealCamera) {
            check(supportsApi("GET", "/contents")) { "Camera did not advertise CCAPI media browsing." }
            listRealMediaIdentities(maximumItems)
        } else {
            // Use the existing simulator endpoint, but reject malformed/omitted identities.
            val rawItems = getJson("/ccapi/media").getJSONArray("items")
            val items = linkedMapOf<String, CameraMediaItem>()
            for (index in 0 until rawItems.length()) {
                val rawItem = rawItems.getJSONObject(index)
                check(rawItem.opt("id") is String && rawItem.opt("name") is String) {
                    "Simulator returned an invalid media identity."
                }
                val item = checkNotNull(parseSimulatorMediaItem(rawItem)) {
                    "Simulator returned an invalid media identity."
                }
                val previous = items.putIfAbsent(item.id, item)
                check(previous == null || previous == item) { "Simulator returned conflicting media identities." }
                if (items.size >= maximumItems) break
            }
            CameraMediaInventory(items.values.toList(), complete = items.size < maximumItems)
        }
        observedFeatures.add(CameraFeature.MEDIA_BROWSER)
        return inventory
    }

    suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail {
        require(item.isVisualMedia) { "Thumbnails require an unambiguous photo or video item." }
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
        val previewEligible = if (isRealCamera) {
            item.kind.isCcapiPreviewKind() && mediaItemPath(item).isCcapiDisplayPreviewPath()
        } else {
            item.kind.isCcapiPreviewKind()
        }
        require(item.isPhotoMedia && previewEligible) {
            "CCAPI display preview is available only for JPEG or CR3 items."
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

    suspend fun openMediaStream(item: CameraMediaItem): CameraMediaStreamSource {
        require(item.isVideoMedia) { "Media streaming is available only for video items." }
        if (isRealCamera && !supportsApi("GET", "/contents")) {
            error("Camera did not advertise CCAPI media browsing.")
        }
        val playbackItem = if (isRealCamera && item.sizeBytes?.takeIf { it > 0L } == null) {
            try {
                mediaInfo(item)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                item
            }
        } else {
            item
        }
        require(playbackItem.id == item.id && playbackItem.name == item.name && playbackItem.isVideoMedia) {
            "Camera changed the media identity or type before playback."
        }
        val path = mediaItemPath(playbackItem)
        val paths = if (isRealCamera) listOf(path, "$path?kind=main", "$path?type=main") else listOf(path)
        return OkHttpCameraMediaStreamSource(
            item = playbackItem,
            httpClient = httpClient,
            requestFactory = { position ->
                paths.flatMap { candidate ->
                    fun request() = Request.Builder()
                        .url("$baseUrl$candidate")
                        .header("Accept", "video/*,application/octet-stream;q=0.8")
                        .header("Cache-Control", "no-cache")
                    buildList {
                        if (position > 0L) add(request().header("Range", "bytes=$position-").get().build())
                        add(request().get().build())
                    }
                }
            },
        )
    }

    suspend fun mediaInfo(item: CameraMediaItem): CameraMediaItem {
        if (isRealCamera && enforceAdvertisedOperations && !supportsApi("GET", "/contents")) {
            error("Camera did not advertise CCAPI media browsing.")
        }
        return parseMediaInfo(item, getJson("${mediaItemPath(item)}?kind=info", strictMediaJsonLimit = MAX_MEDIA_METADATA_JSON_BYTES))
    }

    suspend fun setMediaProtection(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        modifyMedia(
            item = item,
            action = "protect",
            value = if (enabled) "enable" else "disable",
            feature = CameraFeature.MEDIA_PROTECT,
            target = MediaMetadataTarget(MediaMetadataField.PROTECTION, if (enabled) 1 else 0),
        )

    suspend fun setMediaArchived(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        modifyMedia(
            item = item,
            action = "archive",
            value = if (enabled) "enable" else "disable",
            feature = CameraFeature.MEDIA_ARCHIVE,
            target = MediaMetadataTarget(MediaMetadataField.ARCHIVE, if (enabled) 1 else 0),
        )

    suspend fun setMediaRating(item: CameraMediaItem, rating: Int): CameraMediaItem {
        require(rating in 0..5) { "Media rating must be from 0 through 5." }
        return modifyMedia(
            item = item,
            action = "rating",
            value = if (rating == 0) "off" else rating.toString(),
            feature = CameraFeature.MEDIA_RATING,
            target = MediaMetadataTarget(MediaMetadataField.RATING, rating),
        )
    }

    suspend fun setMediaRotation(item: CameraMediaItem, degrees: Int): CameraMediaItem {
        require(degrees in MEDIA_ROTATIONS) { "Media rotation must be 0, 90, 180, or 270 degrees." }
        return modifyMedia(
            item = item,
            action = "rotate",
            value = degrees.toString(),
            feature = CameraFeature.MEDIA_ROTATE,
            target = MediaMetadataTarget(MediaMetadataField.ROTATION, degrees),
        )
    }

    private suspend fun modifyMedia(
        item: CameraMediaItem,
        action: String,
        value: String,
        feature: CameraFeature,
        target: MediaMetadataTarget,
    ): CameraMediaItem {
        if (isRealCamera && enforceAdvertisedOperations && !supportsMediaModify()) {
            error("Camera did not advertise ${feature.label.lowercase()}.")
        }
        val path = mediaItemPath(item)
        putOk(path, JSONObject().put("action", action).put("value", value), expectedStatusCode = 200)
        var latest = item
        repeat(3) { attempt ->
            latest = mediaInfo(item)
            if (target.matches(item.id, latest.metadataReadback())) {
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
        val canonicalName = if (isRealCamera) mediaItemPath(item).substringAfterLast('/') else item.name
        return item.copy(
            // Canon may omit identity/type fields. Explicit returned evidence must not be hidden.
            id = if (body.has("id")) body.opt("id") as? String ?: "" else item.id,
            name = if (body.has("name")) body.opt("name") as? String ?: "" else canonicalName,
            kind = if (body.has("kind")) body.opt("kind") as? String ?: "" else canonicalName.mediaKind(),
            contentType = body.cameraMediaContentType(),
            sizeBytes = body.optLong("filesize").takeIf { it > 0L } ?: item.sizeBytes,
            captureTime = body.optString("lastmodifieddate").takeIf { it.isNotBlank() } ?: item.captureTime,
            protected = canonicalMediaMetadataFlag(body.opt("protect")),
            deleteWritable = if (body.has("deleteWritable")) normalizedMediaMetadataBoolean(body.opt("deleteWritable")) else item.deleteWritable,
            archived = canonicalMediaMetadataFlag(body.opt("archive")),
            rating = canonicalMediaMetadataRating(body.opt("rating")),
            rotationDegrees = canonicalMediaMetadataRotation(body.opt("rotate")),
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

        val call = newCameraCall(request)
        val cancelCall = AtomicBoolean(true)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (cancelCall.get()) call.cancel() }
        }
        try {
            call.execute().use { response ->
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
        } catch (exception: Exception) {
            currentCoroutineContext().ensureActive()
            throw exception
        } finally {
            cancelCall.set(false)
            watcher.cancel()
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

    /** One canonical original GET, including at the HTTP transport layer; never a URL fallback. */
    suspend fun downloadMediaSingleAttempt(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaDownloadResult {
        if (isRealCamera) {
            check(supportsApi("GET", "/contents")) { "Camera did not advertise CCAPI media download." }
        }
        val path = mediaItemPath(item)
        if (isRealCamera) {
            require(CCAPI_CONTENTS_FOLDER_PATTERN.matches(path) && path.isMediaFilePath()) {
                "Camera media identity is not a CCAPI contents file."
            }
        }
        val result = requestMediaFile(listOf(path), item, destination, onProgress, singleAttempt = true)
        observedFeatures.add(CameraFeature.MEDIA_DOWNLOAD)
        return result
    }

    suspend fun uploadMedia(
        name: String,
        sizeBytes: Long,
        contentType: String?,
        source: InputStream,
        evidence: MediaUploadEvidence = MediaUploadEvidence(),
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
        val uploadContext = currentCoroutineContext()
        var bytesTransferred = 0L
        val body = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength(): Long = sizeBytes

            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(MEDIA_TRANSFER_BUFFER_BYTES)
                onProgress(CameraMediaTransferProgress(0L, sizeBytes))
                while (bytesTransferred < sizeBytes) {
                    uploadContext.ensureActive()
                    val requested = minOf(buffer.size.toLong(), sizeBytes - bytesTransferred).toInt()
                    val count = source.read(buffer, 0, requested)
                    check(count >= 0) { "Upload source ended after $bytesTransferred of $sizeBytes bytes." }
                    if (count == 0) continue
                    uploadContext.ensureActive()
                    sink.write(buffer, 0, count)
                    bytesTransferred += count
                    onProgress(CameraMediaTransferProgress(bytesTransferred, sizeBytes))
                }
                check(source.read() == -1) { "Upload source exceeds its declared $sizeBytes bytes." }
            }
        }
        val call = newCameraCall(Request.Builder().url(uploadUrl).post(body).build())
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
                currentCoroutineContext().ensureActive()
                evidence.beforeDispatch()
                call.execute()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                currentCoroutineContext().ensureActive()
                throw exception
            }
            response.use {
                val responseBody = response.body?.let { readStrictMediaJsonText(it.byteStream(), it.contentLength(), 65_536) }.orEmpty()
                if (!response.isSuccessful) {
                    throw CcapiHttpException(
                        response.code,
                        "Simulator media upload returned HTTP ${response.code}: ${responseBody.take(MAX_ERROR_BODY_CHARS)}",
                    )
                }
                check(bytesTransferred == sizeBytes) { "Simulator upload byte count did not match the source." }
                val strictReceipt = StrictMediaJson(responseBody).also { it.validate() }
                val receipt = JSONObject(responseBody)
                check(validUploadReceipt(response.code, name, sizeBytes, bytesTransferred,
                    receipt.opt("id"), receipt.opt("name"), receipt.opt("size_bytes"), receipt.opt("kind"), strictReceipt.topLevelToken("size_bytes"))) {
                    "Upload has no valid operation-specific completion receipt."
                }
                val item = CameraMediaItem(
                    id = receipt.getString("id"), name = receipt.getString("name"), kind = receipt.getString("kind"),
                    sizeBytes = sizeBytes, contentType = receipt.cameraMediaContentType(),
                )
                check(item.name == name && item.sizeBytes == sizeBytes) {
                    "Simulator did not verify the uploaded filename and size."
                }
                CameraMediaUploadResult(item, bytesTransferred).also {
                    evidence.onConfirmed(it)
                    observedFeatures.add(CameraFeature.MEDIA_UPLOAD)
                }
            }
        } finally {
            cancelCall.set(false)
            cancellationWatcher.cancel()
        }
    }

    suspend fun deleteMedia(item: CameraMediaItem, evidence: MediaDeleteEvidence = MediaDeleteEvidence()) {
        check(item.protected != true && item.deleteWritable != false) { "This media item cannot be deleted." }
        val path = if (isRealCamera) {
            if (enforceAdvertisedOperations && !supportsMediaDelete()) {
                error("Camera did not advertise media deletion.")
            }
            normalizeCameraResource(item.id).substringBefore('?')
        } else {
            val encodedId = URLEncoder.encode(item.id, StandardCharsets.UTF_8.name()).replace("+", "%20")
            "/ccapi/media/$encodedId"
        }
        executeMediaDeleteHttp(mutationHttpClient, Request.Builder().url("$baseUrl$path").delete().build(), evidence,
            checkOwner = { requireShutterReleased() })
        observedFeatures.add(CameraFeature.MEDIA_DELETE)
    }

    suspend fun startLiveView(request: LiveViewRequest = LiveViewRequest()) {
        requireShutterReleased()
        check(pendingLiveViewStop == null) { "Live View must be stopped before starting again." }
        refreshTemperatureStatusForRestrictedCommand()
        requireLiveViewAllowed()
        liveViewGeometry.reset()
        if (isRealCamera) {
            check(currentCoroutineContext()[MaintenanceIoContext] == null || request.source != LiveViewSource.AUTO) {
                "Maintenance restoration requires the frozen active Live View source."
            }
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
                val previousAttempt = liveViewStartAttempt
                try {
                    when (source) {
                        LiveViewSource.CCAPI_RTP -> startRtpLiveView(request)
                        LiveViewSource.CCAPI_MULTIPART -> startMultipartLiveView(request)
                        LiveViewSource.CCAPI_JPEG_POLLING -> startJpegLiveView(request)
                        else -> error("${source.label} is not available through the CCAPI network backend.")
                    }
                    currentCoroutineContext().ensureActive()
                    return
                } catch (exception: Exception) {
                    if (exception is CancellationException || exception is CcapiLiveViewReleaseException) throw exception
                    currentCoroutineContext().ensureActive()
                    failures += exception
                    val admitted = previousAttempt != liveViewStartAttempt
                    if (!LiveViewLifecycleEvidence(
                            startAdmitted = admitted, startAcknowledged = false, terminal = true,
                            sourceMode = if (request.source == LiveViewSource.AUTO) "AUTO" else "explicit",
                            stopEvidence = if (pendingLiveViewStop != null) "unconfirmed"
                                else if (admitted) "confirmed" else "not-needed",
                        ).evaluate().canFallback) throw exception
                }
            }
            throw IllegalStateException(
                "Every advertised CCAPI Live View source failed: ${failures.joinToString { it.message ?: it.javaClass.simpleName }}",
                failures.lastOrNull(),
            )
        }
    }

    suspend fun stopLiveView() {
        if (currentCoroutineContext()[MaintenanceIoContext] != null && pendingLiveViewStop != null) pendingLiveViewMaintenanceOwned = true
        if (maintenanceStopOwned) maintenanceStopRecoveryScope { stopLiveViewOwned() }
        else withContext(NonCancellable) { stopLiveViewOwned() }
    }

    private suspend fun stopLiveViewOwned() {
        firstLiveViewFrame = null
        liveViewGeometry.retire()
        val stop = pendingLiveViewStop ?: return
        try {
            stop()
        } catch (exception: Exception) {
            // Keep the exact original stop operation until its acknowledgement arrives.
            throw CcapiLiveViewReleaseException(exception)
        }
        pendingLiveViewStop = null
        pendingLiveViewLocalClose = null
        pendingLiveViewMaintenanceOwned = false
        activeLiveViewSource = null
        liveViewGeometry.retire()
        activeJpegLiveViewOperations = null
        activeMultipartLiveViewOperations = null
    }

    private suspend fun withLiveViewStartCleanup(
        stop: suspend () -> Unit,
        localClose: suspend () -> Unit = {},
        start: suspend () -> Unit,
    ) {
        check(pendingLiveViewStop == null) { "Live View must be stopped before starting again." }
        liveViewStartAttempt += 1
        firstLiveViewFrame = null
        pendingLiveViewStop = stop
        pendingLiveViewLocalClose = localClose
        pendingLiveViewMaintenanceOwned = currentCoroutineContext()[MaintenanceIoContext] != null
        try {
            start()
            currentCoroutineContext().ensureActive()
        } catch (failure: Throwable) {
            // Maintenance restoration never dispatches an automatic cleanup/retry after an uncertain Start.
            if (currentCoroutineContext()[MaintenanceIoContext] != null || failure is CcapiLiveViewReleaseException) throw failure
            try {
                stopLiveView()
            } catch (releaseFailure: CcapiLiveViewReleaseException) {
                releaseFailure.addSuppressed(failure)
                throw releaseFailure
            }
            throw failure
        }
    }

    suspend fun tapFocus(x: Double, y: Double): FocusResult {
        return if (isRealCamera) {
            val operation = tapFocusOperation()
            if (enforceAdvertisedOperations && !supportsCoordinateTapFocus()) {
                error("Camera did not advertise Canon Live View AF frame position control with detailed Live View metadata.")
            }
            val (positionX, positionY, geometryOwner) = cameraLiveViewPosition(x, y, CameraFeature.TAP_FOCUS)
            val payload = JSONObject()
                .put("positionx", positionX)
                .put("positiony", positionY)
            val selectedOperation = operation ?: CcapiApiOperation(
                method = "PUT",
                path = apiPath("PUT", "/shooting/liveview/afframeposition"),
            )
            commandOk("/shooting/liveview/afframeposition", payload, selectedOperation,
                checkOwner = { liveViewGeometry.requireSnapshot(geometryOwner) })
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
        val (positionX, positionY, geometryOwner) = cameraLiveViewPosition(x, y, CameraFeature.CLICK_WHITE_BALANCE)
        val selectedOperation = operation ?: CcapiApiOperation(
            method = "POST",
            path = apiPath("POST", "/shooting/liveview/clickwb"),
        )
        commandOk(
            "/shooting/liveview/clickwb",
            JSONObject().put("positionx", positionX).put("positiony", positionY),
            selectedOperation,
            checkOwner = { liveViewGeometry.requireSnapshot(geometryOwner) },
        )
        observedFeatures.add(CameraFeature.CLICK_WHITE_BALANCE)
        return status()
    }

    fun liveViewFrameUrl(cacheKey: Long, request: LiveViewRequest = LiveViewRequest()): String =
        liveViewFrameUrls(cacheKey, request).first()

    suspend fun liveViewFrame(cacheKey: Long, request: LiveViewRequest = LiveViewRequest()): LiveViewFrame {
        currentCoroutineContext().ensureActive()
        val frameOwner = liveViewGeometry.owner()
        if (activeLiveViewSource != null) firstLiveViewFrame?.let { frame ->
            if (activeLiveViewSource == LiveViewSource.CCAPI_MULTIPART) {
                checkNotNull(multipartLiveViewSession).checkUsable()
            }
            firstLiveViewFrame = null
            observedFeatures.add(CameraFeature.LIVE_VIEW)
            when (activeLiveViewSource) {
                LiveViewSource.CCAPI_MULTIPART -> observedFeatures.add(CameraFeature.LIVE_VIEW_MULTIPART)
                LiveViewSource.CCAPI_JPEG_POLLING -> observedFeatures.add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
                else -> Unit
            }
            return frame
        }
        if (isRealCamera && activeLiveViewSource == LiveViewSource.CCAPI_MULTIPART) {
            return runInterruptible(Dispatchers.IO) {
                checkNotNull(multipartLiveViewSession) { "Canon multipart Live View session is not active." }
                    .nextFrame()
            }.also {
                liveViewGeometry.requireOwner(frameOwner)
                observedFeatures.add(CameraFeature.LIVE_VIEW)
                observedFeatures.add(CameraFeature.LIVE_VIEW_MULTIPART)
            }
        }
        val errors = mutableListOf<String>()
        val sourceUrls = liveViewFrameUrls(cacheKey, request)
        for (attempt in 0..JPEG_FRAME_BUSY_RETRY_DELAYS_MILLIS.size) {
            var transientBusy = false
            for (sourceUrl in sourceUrls) {
                liveViewGeometry.requireOwner(frameOwner)
                val frameRequest = Request.Builder()
                    .url(sourceUrl)
                    .get()
                    .header("Accept", "multipart/x-mixed-replace,image/jpeg,image/*,*/*")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .header("Connection", "close")
                    .build()

                try {
                    return requestLiveViewFrame(frameRequest, sourceUrl).also {
                        liveViewGeometry.requireOwner(frameOwner)
                        observedFeatures.add(CameraFeature.LIVE_VIEW)
                        observedFeatures.add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    errors.add("$sourceUrl\n${exception.javaClass.simpleName}: ${exception.message ?: "Unknown error"}")
                    if (
                        exception is CcapiHttpException &&
                        exception.statusCode == 503 &&
                        exception.message.orEmpty().contains("Device busy", ignoreCase = true)
                    ) {
                        transientBusy = true
                        break
                    }
                }
            }
            if (transientBusy && attempt < JPEG_FRAME_BUSY_RETRY_DELAYS_MILLIS.size) {
                delay(JPEG_FRAME_BUSY_RETRY_DELAYS_MILLIS[attempt])
            } else {
                break
            }
        }

        error(
            "Live view frame failed on all candidate endpoints:\n" +
                errors.joinToString(separator = "\n\n") { "- $it" }
        )
    }

    suspend fun liveViewFocusInfo(): CameraFocusInfo? {
        if (!isRealCamera || activeLiveViewSource == null) return null
        val operation = detailedLiveViewOperation() ?: return null
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$baseUrl${operation.path}?kind=info")
                .header("Accept", "application/octet-stream").header("Cache-Control", "no-cache").get().build()
            val call = focusInfoHttpClient.newCall(request)
            val cancelCall = AtomicBoolean(true)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (cancelCall.get()) call.cancel() }
            }
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { "Canon focus information returned HTTP ${response.code}." }
                    val payload = response.body?.byteStream()?.readBoundedBytes(MAX_FOCUS_INFO_BYTES)
                        ?: error("Canon focus information returned an empty body.")
                    parseCcapiFocusInfoPacket(payload)
                }
            } finally {
                cancelCall.set(false)
                watcher.cancel()
            }
        }
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
            }.map { path ->
                if (path.contains("/shooting/liveview/flipdetail")) path else path.withCacheBust(cacheKey)
            }
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
        in SOUND_RECORDING_LEVEL_SETTING_KEYS -> CameraFeature.SOUND_RECORDING_LEVEL_CONTROL
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

    private fun soundRecordingLevelOperations(pathSuffix: String): Pair<CcapiApiOperation, CcapiApiOperation>? {
        val reads = apiOperations
            .filter { it.method == "GET" && it.path.endsWith(pathSuffix) }
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
            (supportsCompleteLiveView() || supportsMultipartLiveView())

    private fun supportsCoordinateClickWhiteBalance(): Boolean =
        clickWhiteBalanceOperation() != null &&
            detailedLiveViewOperation() != null &&
            (supportsCompleteLiveView() || supportsMultipartLiveView())

    private fun needsLiveViewGeometry(): Boolean =
        supportsCoordinateTapFocus() || supportsCoordinateClickWhiteBalance()

    private fun autofocusOperation(): CcapiApiOperation? =
        apiOperation("POST", "/shooting/control/af")

    private fun focusDriveOperation(): CcapiApiOperation? =
        apiOperation("POST", "/shooting/control/drivefocus")

    private fun liveViewFramePaths(): List<String> =
        activeJpegLiveViewOperations?.framePaths
            ?: jpegLiveViewOperations()?.framePaths
            ?: emptyList()

    private fun jpegLiveViewOperations(): CcapiJpegLiveViewOperations? {
        if (!enforceAdvertisedOperations) {
            return CcapiJpegLiveViewOperations(
                startLiveView = CcapiApiOperation("POST", apiPath("POST", "/shooting/liveview")),
                stopLiveView = CcapiApiOperation("DELETE", apiPath("DELETE", "/shooting/liveview")),
                stopMethod = CcapiLiveViewStopMethod.DELETE,
                framePaths = listOf(
                    apiPath("GET", "/shooting/liveview/flip"),
                    "${apiPath("GET", "/shooting/liveview/flipdetail")}?kind=image",
                    apiPath("GET", "/shooting/liveview"),
                ),
            )
        }
        val starts = apiOperations
            .filter { it.method == "POST" && it.path.endsWith("/shooting/liveview") }
            .sortedByDescending { it.path.apiVersionNumber() }
        return starts.firstNotNullOfOrNull { start ->
            val prefix = start.path.removeSuffix("/shooting/liveview")
            val detailed = apiOperations.firstOrNull {
                it.method == "GET" && it.path == "$prefix/shooting/liveview/flipdetail"
            }
            val coordinateMetadataNeeded = detailed != null && apiOperations.any { operation ->
                (operation.method == "PUT" && operation.path == "$prefix/shooting/liveview/afframeposition") ||
                    (operation.method == "POST" && operation.path == "$prefix/shooting/liveview/clickwb")
            }
            val framePaths = buildList {
                if (coordinateMetadataNeeded) add("${detailed?.path}?kind=both")
                apiOperations.firstOrNull {
                    it.method == "GET" && it.path == "$prefix/shooting/liveview/flip"
                }?.let { add(it.path) }
                detailed?.let { add("${it.path}?kind=image") }
                apiOperations.firstOrNull {
                    it.method == "GET" && it.path == "$prefix/shooting/liveview"
                }?.let { add(it.path) }
            }
            if (framePaths.isEmpty()) return@firstNotNullOfOrNull null
            val delete = apiOperations.firstOrNull {
                it.method == "DELETE" && it.path == start.path
            }
            CcapiJpegLiveViewOperations(
                startLiveView = start,
                stopLiveView = delete ?: start,
                stopMethod = if (delete != null) {
                    CcapiLiveViewStopMethod.DELETE
                } else {
                    CcapiLiveViewStopMethod.POST_OFF
                },
                framePaths = framePaths,
            )
        }
    }

    private fun supportsCompleteLiveView(): Boolean = jpegLiveViewOperations() != null

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
            val start = CcapiApiOperation("POST", "$prefix/shooting/liveview")
            val deleteStop = CcapiApiOperation("DELETE", "$prefix/shooting/liveview")
                .takeIf { it in apiOperations }
            val operations = CcapiMultipartOperations(
                startLiveView = start,
                stopLiveView = deleteStop ?: start,
                stopMethod = if (deleteStop != null) {
                    CcapiLiveViewStopMethod.DELETE
                } else {
                    CcapiLiveViewStopMethod.POST_OFF
                },
                openStream = read,
                closeStream = CcapiApiOperation("DELETE", read.path),
            )
            if (
                operations.startLiveView in apiOperations &&
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
            focusInfoSupported = sources.isNotEmpty() && detailedLiveViewOperation() != null,
        )
    }

    private suspend fun startJpegLiveView(request: LiveViewRequest) {
        val operations = jpegLiveViewOperations()
            ?: error("Camera did not advertise a complete Live View JPEG lifecycle.")
        withLiveViewStartCleanup(stop = {
            stopCcapiLiveView(operations.stopLiveView, operations.stopMethod)
        }) {
            activeJpegLiveViewOperations = operations
            startCcapiLiveView(request, operations.startLiveView.path)
            if (currentCoroutineContext()[MaintenanceIoContext] != null) {
                // Maintenance restores only the frozen endpoint, once, with strict transport.
                val sourceUrl = liveViewFrameUrls(System.nanoTime(), request).firstOrNull()
                    ?: error("The frozen JPEG source has no advertised frame endpoint.")
                firstLiveViewFrame = requestLiveViewFrame(Request.Builder().url(sourceUrl).get()
                    .header("Accept", "image/jpeg,image/*,*/*")
                    .header("Cache-Control", "no-cache, no-store").build(), sourceUrl)
            } else {
                try {
                    validateJpegLiveViewFrame(request)
                } catch (exception: Exception) {
                    if (exception is CancellationException || operations.stopMethod != CcapiLiveViewStopMethod.POST_OFF ||
                        request.size == LiveViewSize.SMALL || !exception.isRejectedLiveViewSize()) {
                        throw exception
                    }
                    try {
                        withContext(NonCancellable) {
                            stopCcapiLiveView(operations.stopLiveView, operations.stopMethod)
                        }
                    } catch (stopFailure: Exception) {
                        // A failed size-downgrade Stop is unknown cleanup. The outer Start
                        // transaction must not silently retry it or admit another Start.
                        throw CcapiLiveViewReleaseException(stopFailure).also { it.addSuppressed(exception) }
                    }
                    val fallback = request.copy(
                        size = LiveViewSize.SMALL,
                        source = LiveViewSource.CCAPI_JPEG_POLLING,
                    )
                    startCcapiLiveView(fallback, operations.startLiveView.path)
                    validateJpegLiveViewFrame(fallback)
                    rejectedLiveViewSizes.add(request.size)
                    activeLiveViewSize = LiveViewSize.SMALL
                }
            }
            validateFirstLiveViewJpeg(checkNotNull(firstLiveViewFrame))
            activeLiveViewSource = LiveViewSource.CCAPI_JPEG_POLLING
        }
    }

    private suspend fun validateFirstLiveViewJpeg(frame: LiveViewFrame) {
        val validator = jpegFrameValidator
        if (validator != null) {
            check(withContext(Dispatchers.Default) { validator(frame.bytes) }) {
                "The first Live View JPEG could not be decoded by Android."
            }
        }
        currentCoroutineContext().ensureActive()
        check(LiveViewLifecycleEvidence(frameEvidence = if (validator == null) "encoded-frame" else "decoded-frame")
            .evaluate().canPublishInputReady)
    }

    private suspend fun validateJpegLiveViewFrame(request: LiveViewRequest) {
        // Preserve encoded evidence for the first Android decode; Start ACK alone is not readiness.
        firstLiveViewFrame = try {
            withContext(Dispatchers.IO) { withTimeout(15_000L) { liveViewFrame(
                cacheKey = System.nanoTime(),
                request = request.copy(source = LiveViewSource.CCAPI_JPEG_POLLING),
            ) } }
        } catch (failure: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Timed out waiting for the first Canon JPEG Live View frame.", failure)
        }
    }

    private fun Throwable.isRejectedLiveViewSize(): Boolean {
        val details = generateSequence(this) { it.cause }
            .joinToString("\n") { it.message.orEmpty() }
            .lowercase(Locale.US)
        return "http 503" in details && "mode not supported" in details
    }

    private suspend fun startCcapiLiveView(
        request: LiveViewRequest,
        path: String = apiPath("POST", "/shooting/liveview"),
    ) {
        var effectiveSize = request.size
        val requestedPayload = JSONObject()
            .put("cameradisplay", "on")
            .put("liveviewsize", request.size.ccapiValue)
        try {
            postOk(path, requestedPayload)
            liveViewSizeControlSupported = true
        } catch (exception: CcapiHttpException) {
            if (currentCoroutineContext()[MaintenanceIoContext] != null || exception.statusCode != 400) throw exception
            try {
                postOk(path, JSONObject().put("cameradisplay", "on"))
                liveViewSizeControlSupported = false
            } catch (parameterException: CcapiHttpException) {
                if (parameterException.statusCode != 400) throw parameterException
                val fallbackSizes = when (request.size) {
                    LiveViewSize.LARGE -> listOf(LiveViewSize.MEDIUM, LiveViewSize.SMALL)
                    LiveViewSize.MEDIUM -> listOf(LiveViewSize.SMALL)
                    LiveViewSize.SMALL -> emptyList()
                }
                var fallbackSucceeded = false
                for (fallbackSize in fallbackSizes) {
                    try {
                        postOk(
                            path,
                            JSONObject()
                                .put("cameradisplay", "on")
                                .put("liveviewsize", fallbackSize.ccapiValue),
                        )
                        rejectedLiveViewSizes.add(request.size)
                        liveViewSizeControlSupported = true
                        effectiveSize = fallbackSize
                        fallbackSucceeded = true
                        break
                    } catch (fallbackException: CcapiHttpException) {
                        if (fallbackException.statusCode != 400) throw fallbackException
                        rejectedLiveViewSizes.add(fallbackSize)
                    }
                }
                if (!fallbackSucceeded) throw exception
            }
        }
        activeLiveViewSize = effectiveSize
    }

    private suspend fun startMultipartLiveView(request: LiveViewRequest) {
        val operation = multipartLiveViewOperations()
            ?: error("Camera did not advertise a complete Canon multipart Live View lifecycle.")
        var openedSession: CcapiMultipartLiveViewSession? = null
        withLiveViewStartCleanup(localClose = {
            closeCcapiMultipartSession(openedSession)
            closeCcapiMultipartSession(multipartLiveViewSession)
            multipartLiveViewSession = null
        }, stop = {
            closeCcapiMultipartSession(openedSession)
            closeCcapiMultipartSession(multipartLiveViewSession)
            multipartLiveViewSession = null
            // Canon's multipart DELETE closes the stream; the general stop owns Live View itself.
            if (currentCoroutineContext()[MaintenanceIoContext] != null) deleteOk(operation.closeStream.path)
            else runCatching { deleteOk(operation.closeStream.path) }
            stopCcapiLiveView(operation.stopLiveView, operation.stopMethod)
        }) {
            activeMultipartLiveViewOperations = operation
            startCcapiLiveView(request, operation.startLiveView.path)
            val sourceUrl = "$baseUrl${operation.openStream.path}"
            val streamRequest = Request.Builder()
                .url(sourceUrl)
                .get()
                .header("Accept", "multipart/x-mixed-replace")
                .header("Cache-Control", "no-cache")
                .build()
            val session = withContext(Dispatchers.IO) {
                var lastFailure: CcapiHttpException? = null
                repeat(MULTIPART_START_RETRY_DELAYS_MILLIS.size + 1) { attempt ->
                    val maintenance = currentCoroutineContext()[MaintenanceIoContext]
                    maintenance?.requireActive?.invoke()
                    val streamClient = if (maintenance == null) multipartHttpClient else captureConfigurationHttpClient.newBuilder()
                        .callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
                    val call = streamClient.newCall(streamRequest)
                    try {
                        val result = withTimeout(MULTIPART_OPEN_TIMEOUT_MILLIS) {
                            val cancelCall = AtomicBoolean(true)
                            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                                try { awaitCancellation() } finally { if (cancelCall.get()) call.cancel() }
                            }
                            val ownerWatchers = if (maintenance != null) maintenanceCancellationWatchers { if (cancelCall.get()) call.cancel() } else emptyList()
                            try {
                                val response = call.execute()
                                if (response.code == 200) {
                                    try {
                                        check(response.headers("Content-Range").isEmpty()) { "Multipart Live View cannot be a partial response." }
                                        val boundary = parseCcapiMultipartBoundary(response.header("content-type"))
                                        CcapiMultipartLiveViewSession(call, response, sourceUrl, boundary).also {
                                            // Keep ownership outside withContext: cancellation may discard its return value.
                                            openedSession = it
                                        }
                                    } catch (exception: Exception) {
                                        response.close()
                                        throw exception
                                    }
                                } else {
                                    response.use {
                                        val preview = response.body?.byteStream()
                                            ?.readBoundedBytes(MAX_ERROR_BODY_CHARS)?.toString(StandardCharsets.UTF_8).orEmpty()
                                        throw CcapiHttpException(
                                            response.code,
                                            "Camera request failed: GET $sourceUrl returned HTTP ${response.code}\nBody: $preview",
                                        )
                                    }
                                }
                            } finally {
                                cancelCall.set(false)
                                watcher.cancel(); ownerWatchers.forEach { it.cancel() }
                            }
                        }
                        return@withContext result
                    } catch (exception: Exception) {
                        call.cancel()
                        currentCoroutineContext().ensureActive()
                        if (exception is TimeoutCancellationException) {
                            throw IOException("Timed out opening the Canon multipart Live View stream.", exception)
                        }
                        if (maintenance != null || exception !is CcapiHttpException || exception.statusCode != 503 ||
                            !exception.message.orEmpty().contains("live view not started", ignoreCase = true) ||
                            attempt >= MULTIPART_START_RETRY_DELAYS_MILLIS.size
                        ) throw exception
                        lastFailure = exception
                        delay(MULTIPART_START_RETRY_DELAYS_MILLIS[attempt])
                    }
                }
                throw checkNotNull(lastFailure)
            }
            // HTTP 200 and a boundary are only transport admission. Fail inside the frozen
            // Stop transaction so AUTO can continue only after confirmed cleanup.
            firstLiveViewFrame = runInterruptible(Dispatchers.IO) { session.nextFrame() }
            currentCoroutineContext().ensureActive()
            validateFirstLiveViewJpeg(checkNotNull(firstLiveViewFrame))
            session.checkUsable() // Decoding may yield while the receiver reaches EOF/failure.
            multipartLiveViewSession = session
            activeLiveViewSource = LiveViewSource.CCAPI_MULTIPART
        }
    }

    private suspend fun stopCcapiLiveView(
        operation: CcapiApiOperation,
        stopMethod: CcapiLiveViewStopMethod,
    ) {
        when (stopMethod) {
            CcapiLiveViewStopMethod.DELETE -> deleteOk(operation.path)
            CcapiLiveViewStopMethod.POST_OFF -> postOk(
                operation.path,
                JSONObject()
                    .put("liveviewsize", "off")
                    .put("cameradisplay", "on"),
            )
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
        withLiveViewStartCleanup(localClose = {
            session.close()
            if (nativeLiveViewSession === session) nativeLiveViewSession = null
        }, stop = {
            try {
                postOk(controlPath, JSONObject().put("action", "stop").put("ipaddress", ""))
            } finally {
                session.close()
                if (nativeLiveViewSession === session) nativeLiveViewSession = null
            }
        }) {
            withContext(Dispatchers.IO) { session.start() }
            postOk(
                controlPath,
                JSONObject()
                    .put("action", "start")
                    .put("ipaddress", checkNotNull(rtpDestinationAddress)),
            )
            session.awaitReady(RTP_FIRST_VIDEO_TIMEOUT_MILLIS)
            currentCoroutineContext().ensureActive()
            check(LiveViewLifecycleEvidence(frameEvidence = "decoded-frame").evaluate().canPublishDecoded)
            nativeLiveViewSession = session
            activeLiveViewSource = LiveViewSource.CCAPI_RTP
            observedFeatures.add(CameraFeature.LIVE_VIEW)
            observedFeatures.add(CameraFeature.LIVE_VIEW_RTP)
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
        recordingEvidence: RecordingRequestEvidence? = null,
        checkOwner: (() -> Unit)? = null,
        stillCapture: Boolean = false,
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
        requestOk(request, recordingEvidence = recordingEvidence, checkOwner = checkOwner, stillCapture = stillCapture)
    }

    private suspend fun withRecoverableFocusRelease(
        press: suspend () -> Unit,
        release: suspend () -> Unit,
        afterPress: suspend () -> Unit,
    ) = heldAutofocus.hold(start = press, stop = release, whileHeld = afterPress)

    private fun apiPath(method: String, pathSuffix: String): String {
        val matching = apiOperations.filter { it.method == method && it.path.endsWith(pathSuffix) }
        return matching.firstOrNull { it.path.startsWith(apiVersionPrefix) }?.path
            ?: matching.maxByOrNull { it.path.substringBefore(pathSuffix).apiVersionNumber() }?.path
            ?: "$apiVersionPrefix$pathSuffix"
    }

    private suspend fun listSimulatorMedia(): List<CameraMediaItem> {
        // A successful HTTP response is not a successfully parsed listing. Missing,
        // mistyped or invalid identities must not become empty/no-candidate evidence.
        val items = getJson("/ccapi/media", strictMediaJsonLimit = BridgeMediaListingDecoder.MAX_SNAPSHOT_BYTES).getJSONArray("items")
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            check(item.opt("id") is String && item.opt("name") is String) {
                "Simulator returned an invalid media identity."
            }
            checkNotNull(parseSimulatorMediaItem(item)) { "Simulator returned an invalid media identity." }
        }
    }

    private fun parseSimulatorMediaItem(item: JSONObject): CameraMediaItem? {
        val id = item.optString("id")
        val name = item.optString("name")
        if (id.isBlank() || name.isBlank()) return null
        val kind = item.optString("kind", "other")
        val contentType = item.cameraMediaContentType()
        return CameraMediaItem(
            id = id,
            name = name,
            kind = kind,
            sizeBytes = item.optLong("size_bytes").takeIf { item.has("size_bytes") },
            captureTime = item.optString("capture_time").takeIf { it.isNotBlank() },
            previewAvailable = kind.isCcapiPreviewKind(),
            protected = item.opt("protect") as? Boolean,
            archived = item.opt("archive") as? Boolean,
            rating = item.normalizedMediaRating(),
            rotationDegrees = normalizedMediaMetadataInteger(item.opt("rotate"), MEDIA_ROTATIONS),
            ratingWritable = normalizedMediaMetadataBoolean(item.opt("ratingWritable")) ?: true,
            protectionWritable = normalizedMediaMetadataBoolean(item.opt("protectionWritable")),
            archiveWritable = normalizedMediaMetadataBoolean(item.opt("archiveWritable")),
            rotationWritable = normalizedMediaMetadataBoolean(item.opt("rotationWritable")),
            deleteWritable = normalizedMediaMetadataBoolean(item.opt("deleteWritable")),
            streamAvailable = cameraMediaHasVideoTransportHint(kind, name) && cameraMediaIsVideo(kind, name, contentType),
            contentType = contentType,
            widthPixels = item.optInt("width_pixels").takeIf { item.has("width_pixels") && it > 0 },
            heightPixels = item.optInt("height_pixels").takeIf { item.has("height_pixels") && it > 0 },
        )
    }

    private suspend fun listRealMediaIdentities(maximumItems: Int): CameraMediaInventory {
        val rootPath = apiPath("GET", "/contents")
        val pending = ArrayDeque<String>()
        // Record at enqueue time: duplicate references and cycles never repeat traversal.
        val containers = linkedSetOf(rootPath)
        val mediaPaths = linkedSetOf<String>()
        var complete = true
        var requests = 0
        pending.add(rootPath)
        try {
            while (pending.isNotEmpty() && complete) {
                currentCoroutineContext().ensureActive()
                listContentPaths(
                    containerPath = pending.removeFirst(),
                    strict = true,
                    beforeRequest = {
                        if (requests >= MAX_MEDIA_INVENTORY_REQUESTS) throw MediaInventoryTraversalLimitException()
                        requests += 1
                    },
                    onPage = { listedPaths ->
                        for (rawPath in listedPaths) {
                            val path = normalizeCameraResource(rawPath).substringBefore('?')
                            check(CCAPI_CONTENTS_FOLDER_PATTERN.matches(path)) {
                                "Camera returned an identity outside CCAPI contents."
                            }
                            if (path.isMediaFilePath()) {
                                mediaPaths.add(path)
                                if (mediaPaths.size >= maximumItems) {
                                    complete = false
                                    break
                                }
                            } else if (path !in containers) {
                                if (containers.size >= MAX_MEDIA_INVENTORY_CONTAINERS) {
                                    complete = false
                                    break
                                }
                                containers.add(path)
                                pending.add(path)
                            }
                        }
                        complete
                    },
                )
            }
        } catch (_: MediaInventoryTraversalLimitException) {
            complete = false
        }
        return CameraMediaInventory(mediaPaths.toList().toMediaItems(), complete)
    }

    private suspend fun listRealMedia(
        maximumItems: Int?,
        onProgress: (List<CameraMediaItem>) -> Unit,
    ): List<CameraMediaItem> {
        val rootPath = apiPath("GET", "/contents")
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        val mediaPathGroups = mutableListOf<List<String>>()
        pending.add(rootPath)

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val container = pending.removeFirst()
            val normalizedContainer = normalizeCameraResource(container).substringBefore('?')
            if (!visited.add(normalizedContainer)) continue

            val mediaPaths = mutableListOf<String>()
            mediaPathGroups.add(mediaPaths)
            val discoveredContainers = linkedSetOf<String>()
            fun publish(listedPaths: List<String>): Boolean {
                mediaPaths.clear()
                discoveredContainers.clear()
                listedPaths.forEach { rawPath ->
                    val path = normalizeCameraResource(rawPath).substringBefore('?')
                    if (path.isMediaFilePath()) {
                        mediaPaths.add(path)
                    } else if (path !in visited) {
                        discoveredContainers.add(path)
                    }
                }
                val merged = mergeMediaPathGroups(mediaPathGroups)
                if (maximumItems == null && mediaPaths.isNotEmpty()) {
                    onProgress(merged.takeMaximum(maximumItems).toMediaItems())
                }
                // Read enough from every sibling media container before merging them.
                // Canon commonly separates photos and movies into different trees.
                return maximumItems == null || mediaPaths.size < maximumItems
            }
            val listedPaths = listContentPaths(normalizedContainer, ::publish)
            if (mediaPaths != listedPaths) publish(listedPaths)
            discoveredContainers.forEach(pending::add)
            if (mediaPaths.isEmpty()) mediaPathGroups.remove(mediaPaths)
        }

        if (maximumItems == null) {
            return mergeMediaPathGroups(mediaPathGroups).toMediaItems()
        }

        val candidates = recentMediaCandidatePaths(mediaPathGroups, maximumItems).toMediaItems()
        val ordered = orderRecentMediaCandidates(hydrateRecentMediaCandidates(candidates) { hydrated ->
            onProgress(orderRecentMediaCandidates(hydrated).take(maximumItems))
        })
            .take(maximumItems)
        onProgress(ordered)
        return ordered
    }

    private fun recentMediaCandidatePaths(groups: List<List<String>>, maximumItems: Int): List<String> {
        val pathsByVideoKind = linkedMapOf<Boolean, MutableList<String>>()
        groups.forEach { group ->
            group.forEach { path ->
                val paths = pathsByVideoKind.getOrPut(path.mediaKind() == "video") { mutableListOf() }
                if (paths.size < maximumItems) paths += path
            }
        }
        return pathsByVideoKind.values.flatten()
    }

    private suspend fun hydrateRecentMediaCandidates(
        items: List<CameraMediaItem>,
        onProgress: (List<CameraMediaItem>) -> Unit,
    ): List<CameraMediaItem> {
        val hydrated = ArrayList<CameraMediaItem>(items.size)
        var consecutiveFailures = 0
        items.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            if (index > 0 && index % 8 == 0) onProgress(hydrated.toList())
            val cached = synchronized(mediaOrderingInfoCache) { mediaOrderingInfoCache[item.id] }
            if (cached != null) {
                hydrated += item.copy(captureTime = cached.captureTime, sizeBytes = cached.sizeBytes)
                consecutiveFailures = 0
                return@forEachIndexed
            }
            try {
                val info = mediaInfo(item)
                synchronized(mediaOrderingInfoCache) {
                    mediaOrderingInfoCache[item.id] = MediaOrderingInfo(info.captureTime, info.sizeBytes)
                }
                hydrated += info
                consecutiveFailures = 0
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IOException) {
                hydrated += item
                hydrated += items.drop(index + 1)
                return hydrated
            } catch (_: Exception) {
                hydrated += item
                consecutiveFailures += 1
                if (consecutiveFailures >= MAX_CONSECUTIVE_MEDIA_INFO_FAILURES) {
                    hydrated += items.drop(index + 1)
                    return hydrated
                }
            }
        }
        return hydrated
    }

    private fun orderRecentMediaCandidates(items: List<CameraMediaItem>): List<CameraMediaItem> =
        items.withIndex().sortedWith { left, right ->
            val leftTime = left.value.captureTime.toMediaOrderingInstant()
            val rightTime = right.value.captureTime.toMediaOrderingInstant()
            when {
                leftTime != null && rightTime != null ->
                    rightTime.compareTo(leftTime).takeIf { it != 0 } ?: left.index.compareTo(right.index)
                leftTime != null -> -1
                rightTime != null -> 1
                else -> left.index.compareTo(right.index)
            }
        }.map { it.value }

    private fun List<String>.toMediaItems(): List<CameraMediaItem> {
        // A large folder can contain thousands of items. Decode its display path once per
        // listing snapshot, without a cross-session cache or another camera request.
        val folders = mutableMapOf<String, CameraMediaFolder?>()
        return map { path ->
            val kind = path.mediaKind()
            val cached = synchronized(mediaOrderingInfoCache) { mediaOrderingInfoCache[path] }
            CameraMediaItem(
                id = path,
                name = path.substringAfterLast('/'),
                kind = kind,
                captureTime = cached?.captureTime,
                sizeBytes = cached?.sizeBytes,
                previewAvailable = path.isCcapiDisplayPreviewPath(),
                streamAvailable = kind == "video",
                folder = path.substringBeforeLast('/', "").let { parent ->
                    if (folders.containsKey(parent)) folders[parent]
                    else path.mediaFolder().also { folders[parent] = it }
                },
            )
        }
    }

    private fun String.mediaFolder(): CameraMediaFolder? {
        // Listing already normalizes and validates these paths against the active camera.
        val parentPath = substringBeforeLast('/', "")
        // An unrelated resource containing the word "contents" is not folder evidence.
        // Qualify the encoded API prefix before decoding the display-only segments.
        if (!CCAPI_CONTENTS_FOLDER_PATTERN.matches(parentPath)) return null
        // Decode display segments only. This URL is never requested; the identity remains
        // the original validated path, including encoded separators and literal plus signs.
        val segments = runCatching { "https://camera.invalid$parentPath".toHttpUrl().pathSegments }.getOrNull() ?: return null
        val label = segments.drop(3).joinToString("/").ifEmpty { "/" }
        return cameraMediaFolderOrNull(parentPath, label)
    }

    private suspend fun listContentPaths(
        containerPath: String,
        onPage: (List<String>) -> Boolean = { true },
        strict: Boolean = false,
        beforeRequest: () -> Unit = {},
    ): List<String> {
        val numberPaths = listOf(
            "$containerPath?kind=number",
            "$containerPath?type=all,kind=number",
        )
        val pageInfo = if (strict) {
            getInventoryPageInfo(numberPaths, beforeRequest)
        } else {
            getFirstJson(numberPaths)
        }
        // All listing consumers need valid page metadata; strict also controls
        // inventory transport fallback, independently of response validation.
        val pageCount = pageInfo?.inventoryPageCount() ?: 0
        check(pageCount >= 0) { "Camera returned a negative media page count at $containerPath." }
        val paths = linkedSetOf<String>()
        if (pageCount <= 0) {
            val response = if (strict) {
                beforeRequest()
                getJson(containerPath)
            } else {
                getFirstJsonRequired(listOf(containerPath), "Reading camera media page")
            }
            paths.addAll(response.contentPaths(strict = true))
            onPage(paths.toList())
        } else if (mediaDescendingOrderSupported == false) {
            for (page in pageCount downTo 1) {
                val response = getContentPage(containerPath, page, strict, beforeRequest)
                paths.addAll(response.contentPaths(reverse = true, strict = true))
                if (!onPage(paths.toList())) break
            }
        } else {
            val firstResponse = getContentPage(containerPath, 1, strict, beforeRequest)
            if (mediaDescendingOrderSupported == false) {
                for (page in pageCount downTo 1) {
                    val response = if (page == 1) firstResponse else getContentPage(containerPath, page, strict, beforeRequest)
                    paths.addAll(response.contentPaths(reverse = true, strict = true))
                    if (!onPage(paths.toList())) break
                }
            } else {
                paths.addAll(firstResponse.contentPaths(strict = true))
                if (onPage(paths.toList())) {
                    for (page in 2..pageCount) {
                        val response = getContentPage(containerPath, page, strict, beforeRequest)
                        paths.addAll(response.contentPaths(strict = true))
                        if (!onPage(paths.toList())) break
                    }
                }
            }
        }
        return paths.toList()
    }

    private suspend fun getInventoryPageInfo(paths: List<String>, beforeRequest: () -> Unit): JSONObject? {
        for (path in paths) {
            beforeRequest()
            try {
                return getJson(path)
            } catch (exception: CcapiHttpException) {
                // Only an explicitly unsupported query may fall back to the existing variant.
                // Authentication, busy, transport and malformed-JSON failures must not become empty baselines.
                if (exception.statusCode !in setOf(400, 404, 405)) throw exception
            }
        }
        return null
    }

    private fun JSONObject.inventoryPageCount(): Int {
        if (!has("pagenumber")) {
            // Some cameras ignore kind=number and return an unpaged path listing.
            contentPaths(strict = true)
            return 0
        }
        return validatedMediaPageCount(opt("pagenumber"))
    }

    private suspend fun getContentPage(
        containerPath: String,
        page: Int,
        strict: Boolean = false,
        beforeRequest: () -> Unit = {},
    ): JSONObject {
        val plainPath = "$containerPath?page=$page"
        if (mediaDescendingOrderSupported == false) {
            return if (strict) {
                beforeRequest()
                getJson(plainPath)
            } else {
                getFirstJsonRequired(listOf(plainPath), "Reading camera media page")
            }
        }

        val orderedPath = "$plainPath&order=desc"
        return try {
            beforeRequest()
            getJson(orderedPath).also { mediaDescendingOrderSupported = true }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (strict && (exception !is CcapiHttpException || exception.statusCode != 400)) throw exception
            val response = if (strict) {
                beforeRequest()
                getJson(plainPath)
            } else {
                getFirstJsonRequired(listOf(plainPath), "Reading camera media page")
            }
            response.also {
                if (exception is CcapiHttpException && exception.statusCode == 400) {
                    mediaDescendingOrderSupported = false
                }
            }
        }
    }

    private fun mergeMediaPathGroups(groups: List<List<String>>): List<String> {
        val positions = IntArray(groups.size)
        val merged = linkedSetOf<String>()
        while (true) {
            var advanced = false
            groups.forEachIndexed { index, group ->
                if (positions[index] >= group.size) return@forEachIndexed
                merged.add(group[positions[index]])
                positions[index] += 1
                advanced = true
            }
            if (!advanced) break
        }
        return merged.toList()
    }

    private fun String?.toMediaOrderingInstant(): Instant? {
        val value = this?.trim().orEmpty()
        if (value.isEmpty()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value, CANON_DATETIME_FORMATTER).toInstant() }.getOrNull()
    }

    private fun <T> List<T>.takeMaximum(maximumItems: Int?): List<T> =
        maximumItems?.let(::take) ?: this

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

    private fun JSONObject.contentPaths(reverse: Boolean = false, strict: Boolean = false): List<String> {
        val array = optJSONArray("path") ?: run {
            check(!strict) { "Camera returned an invalid media path listing." }
            return emptyList()
        }
        val indices = if (reverse) array.length() - 1 downTo 0 else 0 until array.length()
        return indices.mapNotNull { index ->
            if (strict) {
                val path = array.opt(index)
                check(path is String && path.isNotBlank()) { "Camera returned an invalid media identity." }
                path
            } else {
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }
    }

    private suspend fun requestMediaFile(
        paths: List<String>,
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit,
        singleAttempt: Boolean = false,
    ): CameraMediaDownloadResult =
        withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            var lastTransportFailure: Exception? = null
            for (path in paths) {
                currentCoroutineContext().ensureActive()
                val request = Request.Builder().url("$baseUrl$path").get().build()
                val call = if (singleAttempt) singleAttemptDownloadHttpClient.newCall(request) else newCameraCall(request)
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
                        lastTransportFailure = exception
                        errors.add("$path: ${exception.message ?: exception.javaClass.simpleName}")
                        continue
                    }

                    if (singleAttempt && !automaticJpegOriginalResponseAdmitted(
                            response.code, response.headers("Content-Range").isNotEmpty())) {
                        // Application interceptors can transform a response after the network
                        // interceptor. Recheck before peeking, writing, or reporting progress.
                        response.close()
                        throw IOException("Original media request did not return a complete HTTP 200 response.")
                    }
                    if (!response.isSuccessful) {
                        response.use {
                            val preview = response.body?.string().orEmpty().take(MAX_ERROR_BODY_CHARS)
                            errors.add("$path: HTTP ${response.code}: $preview")
                        }
                        continue
                    }

                    val contentType = response.header("content-type")
                    if (singleAttempt && !automaticJpegResponseHeadersAdmitted(response.headers("content-type"))) {
                        response.close()
                        throw InvalidAutomaticJpegException("conflicting original response content type")
                    }
                    val previewBytes = try {
                        response.peekBody(MEDIA_SNIFF_BYTES).bytes()
                    } catch (exception: Exception) {
                        response.close()
                        throw exception
                    }
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
                            validateOriginalMediaDownload(item, bytesTransferred, totalBytes)
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
            val message = "Media download failed for '${item.name}'. Tried:\n" +
                errors.joinToString(separator = "\n") { "  - $it" }
            lastTransportFailure?.let { throw IOException(message, it) }
            error(message)
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

    private suspend fun loadShootingSettings(directoryReadback: ((CaptureConfigurationReadback?) -> Unit)? = null): JSONObject? {
        settingPathsByKey.clear()
        settingReadPathsByKey.clear()
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
            val settings = getCachedShootingSettingJson(path) ?: return@forEach

            val prefix = path.removeSuffix("/shooting/settings")
            val keys = settings.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val settingPath = "$prefix/shooting/settings/$key"
                if (
                    key !in SOUND_RECORDING_SETTING_KEYS &&
                    key !in SOUND_RECORDING_LEVEL_SETTING_KEYS &&
                    key != LIVE_VIEW_MAGNIFICATION_SETTING_KEY &&
                    key !in DEVICE_FUNCTION_SETTING_KEYS &&
                    key !in FOCUS_BRACKETING_SETTING_KEYS &&
                    key !in MOVIE_SETTING_KEYS &&
                    (!enforceAdvertisedOperations || apiOperations.contains(CcapiApiOperation("PUT", settingPath)))
                ) {
                    settingPathsByKey.putIfAbsent(key, settingPath)
                    settingReadPathsByKey.putIfAbsent(key, if (!enforceAdvertisedOperations ||
                        apiOperations.contains(CcapiApiOperation("GET", settingPath))) settingPath else path)
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

        PRIMARY_SETTING_ENDPOINTS.forEach { (key, pathSuffix) ->
            if (settingValuesByKey[key].orEmpty().size >= 2) return@forEach
            readWriteSettingOperations(pathSuffix)?.let { (read, write) ->
                val setting = getCachedShootingSettingJson(read.path)
                    ?.toValidatedStringAbilitySetting(allowedValues = null)
                    ?: return@let
                val values = setting.getJSONArray("ability").toStringList().toSet()
                settingPathsByKey[key] = write.path
                settingReadPathsByKey[key] = read.path
                settingValuesByKey[key] = values
                merged.put(key, setting)
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
                settingReadPathsByKey[ZOOM_SETTING_KEY] = read.path
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
                settingReadPathsByKey[MOVIE_MODE_SETTING_KEY] = read.path
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
                settingReadPathsByKey[key] = read.path
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
                settingReadPathsByKey[key] = read.path
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
                settingReadPathsByKey[key] = read.path
                    settingValuesByKey[key] = setting.getJSONArray("ability").toStringList().toSet()
                    merged.put(key, setting)
                    observedFeatures.add(CameraFeature.SOUND_RECORDING_CONTROL)
                }
            }
        }

        SOUND_RECORDING_LEVEL_ENDPOINTS.forEach { (key, pathSuffix) ->
            soundRecordingLevelOperations(pathSuffix)?.let { (read, write) ->
                val soundRecordingLevel = try {
                    getJson(read.path).toValidatedIntegerRangeSetting()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    null
                }
                if (soundRecordingLevel != null) {
                    val values = soundRecordingLevel.getJSONArray("ability").toStringList().toSet()
                    settingPathsByKey[key] = write.path
                settingReadPathsByKey[key] = read.path
                    settingValuesByKey[key] = values
                    merged.put(key, soundRecordingLevel)
                    observedFeatures.add(CameraFeature.SOUND_RECORDING_LEVEL_CONTROL)
                }
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
                settingReadPathsByKey[key] = read.path
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
                settingReadPathsByKey[key] = read.path
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
                settingReadPathsByKey[key] = read.path
                    settingValuesByKey[key] = setting.getJSONArray("ability").toStringList().toSet()
                    merged.put(key, setting)
                    observedFeatures.add(CameraFeature.MOVIE_SETTINGS_CONTROL)
                }
            }
        }

        directoryOperations()?.let { operations ->
            val selection = try {
                getJson(operations.read.path, strictMediaJsonLimit = 65_536.takeIf { directoryReadback != null },
                    fresh = directoryReadback != null).toValidatedDirectorySelectionSetting()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            }
            directoryReadback?.invoke(selection?.let { CaptureConfigurationReadback(
                directorySelection = it.getString("value"), directoryValues = it.getJSONArray("ability").toStringList()) })
            if (selection != null) {
                val values = selection.getJSONArray("ability").toStringList().toSet()
                settingPathsByKey[DIRECTORY_SELECTION_SETTING_KEY] = operations.write.path
                settingReadPathsByKey[DIRECTORY_SELECTION_SETTING_KEY] = operations.read.path
                settingValuesByKey[DIRECTORY_SELECTION_SETTING_KEY] = values
                merged.put(DIRECTORY_SELECTION_SETTING_KEY, selection)
                observedFeatures.add(CameraFeature.DIRECTORY_CONTROL)
            }
        }

        settingsLoaded = true
        return if (merged.length() > 0) merged else null
    }

    private suspend fun getCachedShootingSettingJson(path: String): JSONObject? = try {
        getJson(path).also { response ->
            shootingSettingResponseCache[path] = JSONObject(response.toString())
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        shootingSettingResponseCache[path]?.let { cached -> JSONObject(cached.toString()) }
    }

    private suspend fun loadFileNaming(force: Boolean = false, fresh: Boolean = false): CameraFileNaming? {
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
                            getJson(operation.first.path, strictMediaJsonLimit = 65_536.takeIf { fresh }, fresh = fresh)
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
            getJson("/ccapi/capabilities", strictMediaJsonLimit = 65_536.takeIf { fresh }, fresh = fresh).optJSONObject("fileNaming")?.toValidatedFileNaming()
        }
        fileNamingState = state
        fileNamingLoaded = true
        if (state != null) observedFeatures.add(CameraFeature.FILE_NAMING_CONTROL)
        return state
    }

    private suspend fun postZoomValue(value: String) {
        if (!settingsLoaded) loadShootingSettings()
        val path = currentCoroutineContext()[ShootingSettingWriteContext]?.route?.writePath ?: settingPathsByKey[ZOOM_SETTING_KEY]
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
        val path = currentCoroutineContext()[ShootingSettingWriteContext]?.route?.writePath ?: settingPathsByKey[MOVIE_MODE_SETTING_KEY]
            ?: error("Camera did not advertise writable Canon movie mode control.")
        if (value !in settingValuesByKey[MOVIE_MODE_SETTING_KEY].orEmpty()) {
            error("Value '$value' is not advertised for movie mode.")
        }
        postOk(path, JSONObject().put("action", value))
    }

    private suspend fun putIntegerSettingValue(key: String, value: String) {
        if (!settingsLoaded) loadShootingSettings()
        val path = currentCoroutineContext()[ShootingSettingWriteContext]?.route?.writePath ?: settingPathsByKey[key]
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

        if (currentCoroutineContext()[ShootingSettingWriteContext] != null) {
            val frozen = requireNotNull(currentCoroutineContext()[ShootingSettingWriteContext]?.route)
            putOk(frozen.writePath, JSONObject().put("value", value))
            return
        }
        val errors = mutableListOf<String>()
        paths.forEach { path ->
            try {
                putOk(path, JSONObject().put("value", value))
                return
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
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
        var scopedCurrent: JSONObject? = null
        var scopedValues: Set<String>? = null
        if (currentCoroutineContext()[ShootingSettingWriteContext] != null) {
            val frozen = requireNotNull(currentCoroutineContext()[ShootingSettingWriteContext]?.route)
            val response = getJson(frozen.readPath, strictMediaJsonLimit = 65_536, fresh = true, shootingRead = true)
            val fresh = if (frozen.aggregateKey == null) response else requireNotNull(response.optJSONObject(frozen.aggregateKey))
            val current = requireNotNull(fresh.optJSONObject("value"))
            val ability = requireNotNull(fresh.optJSONObject("ability"))
            val fields = if (baseKey == WB_SHIFT_SETTING_KEY) WB_SHIFT_FIELDS.toSet() else current.keys().asSequence().toSet()
            require(fields.isNotEmpty() && field in fields &&
                (baseKey == WB_SHIFT_SETTING_KEY || fields.all { it in IMAGE_QUALITY_FIELDS }) &&
                current.keys().asSequence().toSet() == fields && ability.keys().asSequence().toSet() == fields)
            fields.forEach { sibling ->
                val observed = if (baseKey == WB_SHIFT_SETTING_KEY) current.opt(sibling).toExactJsonInt()?.toString()
                    else current.opt(sibling) as? String
                val allowed = if (baseKey == WB_SHIFT_SETTING_KEY) ability.optJSONObject(sibling)?.toBoundedIntegerRangeValues(1024)
                    else ability.optJSONArray(sibling)?.let { array ->
                        require(array.length() <= 1024)
                        (0 until array.length()).map { array.opt(it) as? String ?: error("Invalid sibling ability.") }
                    }
                require(observed != null && allowed != null && allowed.isNotEmpty() && allowed.distinct().size == allowed.size &&
                    observed in allowed && allowed.all { it.isNotEmpty() && validShootingSettingText(it) }) { "Invalid fresh structured sibling." }
            }
            val values = if (baseKey == WB_SHIFT_SETTING_KEY) ability.optJSONObject(field)?.toBoundedIntegerRangeValues()
                else ability.optJSONArray(field)?.toStringList()
            require(values != null && value in values) { "The requested structured value is no longer advertised." }
            scopedCurrent = current
            scopedValues = values.toSet()
        }
        val advertisedValues = scopedValues ?: structuredSettingValuesByKey[key].orEmpty()
        if (value !in advertisedValues) {
            error("Value '$value' is not advertised for $key.")
        }
        val current = scopedCurrent ?: structuredSettingCurrentValues[baseKey]
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
        val path = currentCoroutineContext()[ShootingSettingWriteContext]?.route?.writePath ?: structuredSettingPathsByKey.getValue(key)
        putOk(path, JSONObject().put("value", updated))
        if (currentCoroutineContext()[ShootingSettingWriteContext] == null) structuredSettingCurrentValues[baseKey] = updated
    }

    private fun writableSetting(settings: JSONObject?, candidateKeys: List<String>): JSONObject? {
        if (settings == null) return null
        val key = candidateKeys.firstOrNull(settingPathsByKey::containsKey) ?: return null
        return settings.optJSONObject(key)
    }

    private suspend fun getJson(path: String, strictMediaJsonLimit: Int? = null, fresh: Boolean = false, shootingRead: Boolean = false): JSONObject = requestJson(
        Request.Builder().url("$baseUrl$path").apply { if (fresh) header("Cache-Control", "no-cache, no-store") }.get().build(), strictMediaJsonLimit = strictMediaJsonLimit, captureRecovery = fresh, shootingRead = shootingRead,
    )

    private suspend fun getText(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl$path").get().header("Accept", "text/plain").build()
        val maintenance = currentCoroutineContext()[MaintenanceIoContext]
        maintenance?.requireActive?.invoke()
        val recordingIo = currentCoroutineContext()[RecordingIoContext] != null
        val call = newCameraCall(request, maintenance != null || recordingIo)
        val cancelCall = AtomicBoolean(true)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (cancelCall.get()) call.cancel() }
        }
        val ownerWatchers = if (maintenance != null) maintenanceCancellationWatchers { if (cancelCall.get()) call.cancel() } else emptyList()
        try {
            call.execute().use { response ->
                val body = response.body?.let { body ->
                    if (maintenance == null && !recordingIo) body.string() else {
                        requireShootingSettingHttpResponse(response.code, response.headers("Content-Range"),
                            response.headers("Content-Length"), response.headers("Transfer-Encoding"))
                        body.byteStream().use { readStrictMediaJsonText(it, body.contentLength(), 65_536) }.also {
                            check(body.contentLength() < 0 || it.toByteArray(Charsets.UTF_8).size.toLong() == body.contentLength())
                        }
                    }
                }.orEmpty()
                if (!response.isSuccessful) {
                    throw CcapiHttpException(
                        statusCode = response.code,
                        message = "Camera request failed: ${request.method} ${request.url} returned HTTP ${response.code}\nBody: $body",
                    )
                }
                body
            }
        } catch (exception: Exception) {
            currentCoroutineContext().ensureActive()
            throw exception
        } finally {
            cancelCall.set(false)
            watcher.cancel(); ownerWatchers.forEach { it.cancel() }
        }
    }

    private suspend fun postJson(path: String, payload: JSONObject, recordingEvidence: RecordingRequestEvidence? = null,
        captureEvidence: CaptureConfigurationEvidence? = null, captureResponse: ((JSONObject) -> Unit)? = null): JSONObject = requestJson(
        Request.Builder()
            .url("$baseUrl$path")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build(), recordingEvidence, captureEvidence = captureEvidence, captureResponse = captureResponse,
    )

    private suspend fun patchJson(path: String, payload: JSONObject): JSONObject = requestJson(
        Request.Builder()
            .url("$baseUrl$path")
            .patch(payload.toString().toRequestBody(jsonMediaType))
            .build(),
    )

    private suspend fun putJson(path: String, payload: JSONObject,
        captureEvidence: CaptureConfigurationEvidence? = null, captureResponse: ((JSONObject) -> Unit)? = null): JSONObject = requestJson(
        Request.Builder()
            .url("$baseUrl$path")
            .put(payload.toString().toRequestBody(jsonMediaType))
            .build(), captureEvidence = captureEvidence, captureResponse = captureResponse,
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
        checkOwner: (() -> Unit)? = null,
    ): Unit = requestOk(
        Request.Builder()
            .url("$baseUrl$path")
            .put(payload.toString().toRequestBody(jsonMediaType))
            .build(),
        expectedStatusCode = expectedStatusCode,
        checkOwner = checkOwner,
    )

    private suspend fun deleteOk(path: String): Unit = requestOk(
        Request.Builder()
            .url("$baseUrl$path")
            .delete()
            .build(),
    )

    private fun newCameraCall(request: Request, captureConfiguration: Boolean = false): Call {
        if (request.method == "GET" || request.method == "HEAD") return (if (captureConfiguration) captureConfigurationHttpClient else httpClient).newCall(request)
        val original = request.body
        val oneShot = original?.let { body ->
            object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
            }
        }
        return (if (captureConfiguration) captureConfigurationHttpClient else mutationHttpClient)
            .newCall(request.newBuilder().method(request.method, oneShot).build())
    }

    private suspend fun requestJson(request: Request, recordingEvidence: RecordingRequestEvidence? = null, strictMediaJsonLimit: Int? = null,
        captureEvidence: CaptureConfigurationEvidence? = null, captureResponse: ((JSONObject) -> Unit)? = null,
        captureRecovery: Boolean = false, shootingRead: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        if (currentCoroutineContext()[MaintenanceIoContext] != null) {
            return@withContext requireNotNull(maintenanceJson(request)) { "Maintenance dependency returned no JSON." }
        }
        val shootingEvidence = currentCoroutineContext()[ShootingSettingWriteContext]?.evidence
            ?.takeIf { request.method != "GET" && request.method != "HEAD" }
        val recordingIo = currentCoroutineContext()[RecordingIoContext] != null || recordingEvidence != null
        val call = newCameraCall(request.newBuilder().tag(RecordingRequestEvidence::class.java, recordingEvidence).build(), captureEvidence != null || captureRecovery || shootingEvidence != null || recordingIo)
        val jsonLimit = strictMediaJsonLimit ?: 65_536.takeIf { captureEvidence != null || recordingIo }
        val readOnly = request.method == "GET" || request.method == "HEAD"
        val cancelCall = AtomicBoolean(true)
        val watcher = if (readOnly || recordingIo) launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (cancelCall.get()) call.cancel() }
        } else null
        try {
            recordingEvidence?.let {
                currentCoroutineContext().ensureActive()
                it.beginRequest()
            }
            captureEvidence?.let {
                currentCoroutineContext().ensureActive()
                it.beforeDispatch("${request.method} ${request.url.encodedPath}")
            }
            shootingEvidence?.let { currentCoroutineContext().ensureActive(); it.beforeDispatch() }
            call.execute().use { response ->
                recordingEvidence?.rejected = !response.isSuccessful
                val body = response.body?.let {
                    if (recordingIo) it.byteStream().use { input -> readShootingSettingResponseText(input, it.contentLength()) }
                    else if (captureEvidence != null || captureRecovery || shootingEvidence != null) it.byteStream().use { input -> readCaptureConfigurationJsonText(input, it.contentLength()) }
                    else if (jsonLimit == null) it.string()
                    else it.byteStream().use { input -> readStrictMediaJsonText(input, it.contentLength(), jsonLimit) }
                }.orEmpty()
                if (captureEvidence != null || recordingIo) StrictMediaJson(body).validate()
                if (!response.isSuccessful) {
                    throw CcapiHttpException(
                        statusCode = response.code,
                        message = "Camera request failed: ${request.method} ${request.url} returned HTTP ${response.code}\nBody: $body",
                    )
                }
                if (strictMediaJsonLimit != null) StrictMediaJson(body).validate()
                JSONObject(body).also {
                    if (recordingIo) {
                        check(response.code == 200)
                        requireShootingSettingHttpResponse(response.code, response.headers("Content-Range"),
                            response.headers("Content-Length"), response.headers("Transfer-Encoding"))
                        recordingEvidence?.acknowledge()
                    }
                    if (shootingEvidence != null || shootingRead) {
                        if (shootingRead) check(response.code == 200)
                        requireShootingSettingHttpResponse(response.code, response.headers("Content-Range"),
                            response.headers("Content-Length"), response.headers("Transfer-Encoding"))
                        shootingEvidence?.responseReceived()
                    }
                    captureResponse?.invoke(it)
                }
            }
        } catch (exception: Exception) {
            if (readOnly) currentCoroutineContext().ensureActive()
            throw exception
        } finally {
            cancelCall.set(false)
            watcher?.cancel()
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
                check(response.code == 200) { "Camera event response requires HTTP 200." }
                requireShootingSettingHttpResponse(response.code, response.headers("Content-Range"),
                    response.headers("Content-Length"), response.headers("Transfer-Encoding"))
                val body = requireNotNull(response.body).let { body ->
                    body.byteStream().use { readStrictMediaJsonText(it, body.contentLength(), MAX_CCAPI_EVENT_BODY_BYTES) }
                        .also { check(body.contentLength() < 0 || it.toByteArray(Charsets.UTF_8).size.toLong() == body.contentLength()) }
                }
                StrictMediaJson(body).validate()
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
        recordingEvidence: RecordingRequestEvidence? = null,
        checkOwner: (() -> Unit)? = null,
        stillCapture: Boolean = false,
    ): Unit = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        checkOwner?.invoke()
        if (currentCoroutineContext()[MaintenanceIoContext] != null) {
            maintenanceJson(request, expectedStatusCode ?: 204, empty = true)
            return@withContext
        }
        recordingEvidence?.let {
            currentCoroutineContext().ensureActive()
            it.beginRequest()
        }
        val shootingEvidence = currentCoroutineContext()[ShootingSettingWriteContext]?.evidence
        shootingEvidence?.let { currentCoroutineContext().ensureActive(); it.beforeDispatch() }
        val recordingIo = currentCoroutineContext()[RecordingIoContext] != null || recordingEvidence != null
        // Still capture remains joined through the complete response and mandatory manual release.
        // Bound the synchronous producer itself; cancellation must not detach it from release.
        val call = newCameraCall(request.newBuilder().tag(RecordingRequestEvidence::class.java, recordingEvidence).build(), shootingEvidence != null || recordingIo || stillCapture)
        val watcher = if (shootingEvidence != null || recordingIo || checkOwner != null || stillCapture) launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        } else null
        try {
            currentCoroutineContext().ensureActive()
            checkOwner?.invoke()
            call.execute().use { response ->
            recordingEvidence?.rejected = !response.isSuccessful
            val body = response.body?.let { body -> if (shootingEvidence == null && !recordingIo && !stillCapture) body.string() else
                body.byteStream().use { readShootingSettingResponseText(it, body.contentLength()) }
            }.orEmpty()
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
            if (recordingEvidence != null) {
                check(response.code == 200 || response.code == 204)
                if (body.isNotBlank()) { StrictMediaJson(body).validate(); check(JSONObject(body).length() == 0) }
                requireShootingSettingHttpResponse(response.code, response.headers("Content-Range"),
                    response.headers("Content-Length"), response.headers("Transfer-Encoding"))
                if (response.code == 204) check(body.isEmpty() && response.headers("Content-Length").all { it == "0" } &&
                    response.headers("Transfer-Encoding").isEmpty())
                recordingEvidence.acknowledge()
            }
            if (shootingEvidence != null) {
                requireShootingSettingHttpResponse(response.code, response.headers("Content-Range"),
                    response.headers("Content-Length"), response.headers("Transfer-Encoding"))
                shootingEvidence.responseReceived()
            }
        } } finally { watcher?.cancel() }
    }

    private suspend fun requestLiveViewFrame(request: Request, sourceUrl: String): LiveViewFrame {
        val owner = liveViewGeometry.owner()
        val isDetailedFrame = sourceUrl.contains("/shooting/liveview/flipdetail") && sourceUrl.contains("kind=both")
        val geometryRead = if (isDetailedFrame) liveViewGeometry.beginRead(owner) else null
        return withContext(Dispatchers.IO) {
            liveViewGeometry.requireOwner(owner)
            val maintenance = currentCoroutineContext()[MaintenanceIoContext]
            maintenance?.requireActive?.invoke()
            val call = newCameraCall(request, captureConfiguration = maintenance != null)
            if (call.timeout().timeoutNanos() == 0L || call.timeout().timeoutNanos() > TimeUnit.SECONDS.toNanos(15)) {
                call.timeout().timeout(15, TimeUnit.SECONDS)
            }
            val cancelCall = AtomicBoolean(true)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (cancelCall.get()) call.cancel() }
            }
            val ownerWatchers = if (maintenance != null) maintenanceCancellationWatchers {
                if (cancelCall.get()) call.cancel()
            } else emptyList()
            try {
                call.execute().use { response ->
                    if (maintenance != null) {
                        check(response.code == 200) { "Maintenance JPEG readiness requires HTTP 200." }
                        requireShootingSettingHttpResponse(response.code, response.headers("Content-Range"),
                            response.headers("Content-Length"), response.headers("Transfer-Encoding"))
                    }
                    val contentType = response.header("content-type")
                    val body = response.body ?: error("Live view frame failed: empty response body")

                    if (!response.isSuccessful) {
                        val preview = body.byteStream().readBoundedBytes(MAX_ERROR_BODY_CHARS).toString(StandardCharsets.UTF_8).trim()
                        throw CcapiHttpException(
                            statusCode = response.code,
                            message = "Live view frame failed: ${request.method} ${request.url} returned HTTP ${response.code}\n" +
                                "Content-Type: ${contentType ?: "unknown"}\n" +
                                "Body: $preview",
                        )
                    }

                    check(response.code == 200 && response.headers("Content-Range").isEmpty()) {
                        "Live View frame requires a complete HTTP 200 representation without Content-Range."
                    }
                    if (contentType.isTextLikeContentType()) {
                        val preview = body.byteStream().readBoundedBytes(MAX_ERROR_BODY_CHARS).toString(StandardCharsets.UTF_8).trim()
                        error(
                            "Live view frame returned ${contentType ?: "text"} instead of image bytes.\n" +
                                "Body: $preview"
                        )
                    }

                    val completeBody = if (maintenance != null) body.byteStream().readBoundedBytes(MAX_LIVE_VIEW_SCAN_BYTES).also {
                        check(body.contentLength() < 0 || body.contentLength() == it.size.toLong()) {
                            "Maintenance JPEG response ended before its declared body length."
                        }
                        maintenance.requireActive()
                    } else null
                    val detailed = if (isDetailedFrame) {
                        parseDetailedLiveView(completeBody ?: body.byteStream().readBoundedBytes(MAX_LIVE_VIEW_SCAN_BYTES))
                    } else {
                        null
                    }
                    val frame = LiveViewFrame(
                        bytes = when {
                            detailed?.image != null -> detailed.image
                            detailed != null -> error("Detailed Live View response did not contain an image packet.")
                            else -> readFirstJpegFrame(completeBody?.inputStream() ?: body.byteStream())
                        },
                        contentType = contentType,
                        sourceUrl = sourceUrl,
                    )
                    currentCoroutineContext().ensureActive()
                    liveViewGeometry.requireOwner(owner)
                    if (geometryRead != null) liveViewGeometry.publish(geometryRead, detailed?.geometry)
                    frame
                }
            } catch (exception: Exception) {
                currentCoroutineContext().ensureActive()
                throw exception
            } finally {
                cancelCall.set(false)
                watcher.cancel()
                ownerWatchers.forEach { it.cancel() }
            }
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

        check(offset == payload.size) { "Detailed Live View response contained a truncated or malformed packet." }
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
                    val x = image.opt("positionx").toExactJsonInt() ?: return null
                    val y = image.opt("positiony").toExactJsonInt() ?: return null
                    val width = image.opt("positionwidth").toExactJsonInt() ?: return null
                    val height = image.opt("positionheight").toExactJsonInt() ?: return null
                    if (x >= 0 && y >= 0 && width > 0 && height > 0 &&
                        x.toLong() + width <= Int.MAX_VALUE && y.toLong() + height <= Int.MAX_VALUE) {
                        return CcapiLiveViewGeometry(
                            positionX = x,
                            positionY = y,
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
    ): Triple<Int, Int, LiveViewGeometryState.Snapshot<CcapiLiveViewGeometry>> {
        require(x in 0.0..1.0 && y in 0.0..1.0) {
            "${feature.label} coordinates must be normalized from 0 through 1."
        }
        val owner = liveViewGeometry.owner()
        if (liveViewGeometry.snapshot() == null) {
            detailedLiveViewOperation()?.let { operation ->
                val sourceUrl = "$baseUrl${operation.path}?kind=both"
                val request = Request.Builder()
                    .url(sourceUrl)
                    .get()
                    .header("Accept", "image/jpeg,image/*,*/*")
                    .header("Cache-Control", "no-cache, no-store")
                    .build()
                requestLiveViewFrame(request, sourceUrl)
            }
        }
        liveViewGeometry.requireOwner(owner)
        val geometry = liveViewGeometry.snapshot()
            ?: error("${feature.label} needs Canon detailed Live View position metadata, but the camera returned none.")
        val (positionX, positionY) = geometry.value.cameraPosition(x, y)
        return Triple(positionX, positionY, geometry)
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
        const val MAX_MEDIA_THUMBNAIL_BYTES = 8 * 1024 * 1024
        const val MAX_MEDIA_PREVIEW_BYTES = 32 * 1024 * 1024
        const val MAX_MEDIA_ORDERING_CACHE_ITEMS = 256
        const val MAX_CONSECUTIVE_MEDIA_INFO_FAILURES = 3
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

private fun String.isCcapiDisplayPreviewPath(): Boolean =
    substringBefore('?').substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "cr3")

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
        recording = strictRecordingStatus(),
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
    val soundRecordingLevels = SOUND_RECORDING_LEVEL_SETTING_KEYS.mapNotNull { key ->
        optJSONObject(key)
            ?.toValidatedIntegerRangeSetting()
            ?.let { setting ->
                CameraSettingControl(
                    key = key,
                    label = key.toSettingLabel(),
                    value = setting.getString("value"),
                    values = setting.getJSONArray("ability").toStringList(),
                )
            }
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
            CameraFeature.MEDIA_ARCHIVE,
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
        (if (soundRecordingLevels.isNotEmpty()) {
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
            *soundRecordingLevels.toTypedArray(),
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

private val PRIMARY_SETTING_ENDPOINTS = linkedMapOf(
    "iso" to "/shooting/settings/iso",
    "tv" to "/shooting/settings/tv",
    "av" to "/shooting/settings/av",
    "wb" to "/shooting/settings/wb",
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
        SOUND_RECORDING_MODE_INTMIC_SETTING_KEY -> "Internal microphone mode"
        SOUND_RECORDING_MODE_EXTMIC_SETTING_KEY -> "External microphone mode"
        SOUND_RECORDING_MODE_ACC_SETTING_KEY -> "Accessory microphone mode"
        WIND_FILTER_SETTING_KEY -> "Wind filter"
        WIND_FILTER_INTMIC_SETTING_KEY -> "Internal microphone wind filter"
        WIND_FILTER_EXTMIC_SETTING_KEY -> "External microphone wind filter"
        WIND_FILTER_ACC_SETTING_KEY -> "Accessory microphone wind filter"
        ATTENUATOR_SETTING_KEY -> "Attenuator"
        ATTENUATOR_INTMIC_SETTING_KEY -> "Internal microphone attenuator"
        ATTENUATOR_EXTMIC_SETTING_KEY -> "External microphone attenuator"
        ATTENUATOR_ACC_SETTING_KEY -> "Accessory microphone attenuator"
        SOUND_RECORDING_LEVEL_SETTING_KEY -> "Sound recording level"
        SOUND_RECORDING_LEVEL_INTMIC_SETTING_KEY -> "Internal microphone level"
        SOUND_RECORDING_LEVEL_EXTMIC_SETTING_KEY -> "External microphone level"
        SOUND_RECORDING_LEVEL_ACC_SETTING_KEY -> "Accessory microphone level"
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
private const val RTP_FIRST_VIDEO_TIMEOUT_MILLIS = 5_000L
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
private val SOUND_RECORDING_LEVEL_VALUES = (0..63).map(Int::toString).toSet()
private const val SOUND_RECORDING_SETTING_KEY = "soundrecording"
private const val SOUND_RECORDING_MODE_INTMIC_SETTING_KEY = "soundrecordingmodeintmic"
private const val SOUND_RECORDING_MODE_EXTMIC_SETTING_KEY = "soundrecordingmodeextmic"
private const val SOUND_RECORDING_MODE_ACC_SETTING_KEY = "soundrecordingmodeacc"
private const val WIND_FILTER_SETTING_KEY = "windfilter"
private const val WIND_FILTER_INTMIC_SETTING_KEY = "windfilterintmic"
private const val WIND_FILTER_EXTMIC_SETTING_KEY = "windfilterextmic"
private const val WIND_FILTER_ACC_SETTING_KEY = "windfilteracc"
private const val ATTENUATOR_SETTING_KEY = "attenuator"
private const val ATTENUATOR_INTMIC_SETTING_KEY = "attenuatorintmic"
private const val ATTENUATOR_EXTMIC_SETTING_KEY = "attenuatorextmic"
private const val ATTENUATOR_ACC_SETTING_KEY = "attenuatoracc"
private data class SoundRecordingEndpoint(
    val pathSuffix: String,
    val simulatorPath: String,
    val values: Set<String>,
)
private val SOUND_RECORDING_ENDPOINTS = linkedMapOf(
    SOUND_RECORDING_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording",
        simulatorPath = "sound-recording",
        values = linkedSetOf("auto", "manual", "enable", "disable"),
    ),
    SOUND_RECORDING_MODE_INTMIC_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/mode/intmic",
        simulatorPath = "sound-recording-mode/internal-mic",
        values = linkedSetOf("auto", "manual", "disable"),
    ),
    SOUND_RECORDING_MODE_EXTMIC_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/mode/extmic",
        simulatorPath = "sound-recording-mode/external-mic",
        values = linkedSetOf("auto", "manual", "disable"),
    ),
    SOUND_RECORDING_MODE_ACC_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/mode/acc",
        simulatorPath = "sound-recording-mode/accessory",
        values = linkedSetOf("auto", "manual", "disable"),
    ),
    WIND_FILTER_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/windfilter",
        simulatorPath = "wind-filter",
        values = linkedSetOf("auto", "enable", "disable"),
    ),
    WIND_FILTER_INTMIC_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/windfilter/intmic",
        simulatorPath = "wind-filter/internal-mic",
        values = linkedSetOf("auto", "enable", "disable"),
    ),
    WIND_FILTER_EXTMIC_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/windfilter/extmic",
        simulatorPath = "wind-filter/external-mic",
        values = linkedSetOf("auto", "enable", "disable"),
    ),
    WIND_FILTER_ACC_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/windfilter/acc",
        simulatorPath = "wind-filter/accessory",
        values = linkedSetOf("auto", "enable", "disable"),
    ),
    ATTENUATOR_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/attenuator",
        simulatorPath = "attenuator",
        values = linkedSetOf("enable", "disable", "auto", "manual"),
    ),
    ATTENUATOR_INTMIC_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/attenuator/intmic",
        simulatorPath = "attenuator/internal-mic",
        values = linkedSetOf("enable", "disable", "auto", "manual"),
    ),
    ATTENUATOR_EXTMIC_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/attenuator/extmic",
        simulatorPath = "attenuator/external-mic",
        values = linkedSetOf("enable", "disable", "auto", "manual"),
    ),
    ATTENUATOR_ACC_SETTING_KEY to SoundRecordingEndpoint(
        pathSuffix = "/shooting/settings/soundrecording/attenuator/acc",
        simulatorPath = "attenuator/accessory",
        values = linkedSetOf("enable", "disable", "auto", "manual"),
    ),
)
private val SOUND_RECORDING_SETTING_KEYS = SOUND_RECORDING_ENDPOINTS.keys
private const val SOUND_RECORDING_LEVEL_INTMIC_SETTING_KEY = "soundrecordinglevelintmic"
private const val SOUND_RECORDING_LEVEL_EXTMIC_SETTING_KEY = "soundrecordinglevelextmic"
private const val SOUND_RECORDING_LEVEL_ACC_SETTING_KEY = "soundrecordinglevelacc"
private val SOUND_RECORDING_LEVEL_ENDPOINTS = linkedMapOf(
    SOUND_RECORDING_LEVEL_SETTING_KEY to "/shooting/settings/soundrecording/level",
    SOUND_RECORDING_LEVEL_INTMIC_SETTING_KEY to "/shooting/settings/soundrecording/level/intmic",
    SOUND_RECORDING_LEVEL_EXTMIC_SETTING_KEY to "/shooting/settings/soundrecording/level/extmic",
    SOUND_RECORDING_LEVEL_ACC_SETTING_KEY to "/shooting/settings/soundrecording/level/acc",
)
private val SOUND_RECORDING_LEVEL_SETTING_KEYS = SOUND_RECORDING_LEVEL_ENDPOINTS.keys
private val SOUND_RECORDING_LEVEL_SIMULATOR_PATHS = linkedMapOf(
    SOUND_RECORDING_LEVEL_SETTING_KEY to "sound-recording-level",
    SOUND_RECORDING_LEVEL_INTMIC_SETTING_KEY to "sound-recording-level/internal-mic",
    SOUND_RECORDING_LEVEL_EXTMIC_SETTING_KEY to "sound-recording-level/external-mic",
    SOUND_RECORDING_LEVEL_ACC_SETTING_KEY to "sound-recording-level/accessory",
)
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
    val rawValues = (0 until rawAbility.length()).map { rawAbility.opt(it) }
    if (rawValues.any { it !is String }) return null
    val values = rawValues.filterIsInstance<String>()
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
    return result.takeIf(::validCaptureConfigurationNaming)
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
