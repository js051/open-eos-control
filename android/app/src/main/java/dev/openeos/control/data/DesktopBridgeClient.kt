package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

data class DesktopBridgeCamera(
    val id: String,
    val model: String,
    val port: String,
    val engine: String,
)

internal class DesktopBridgeException(
    val code: String,
    val feature: String? = null,
    val engine: String? = null,
    message: String,
) : IllegalStateException(message)

class DesktopBridgeClient(
    baseUrl: String,
    httpClient: OkHttpClient? = null,
    token: String = "",
    private val cameraId: String? = null,
    private val profileHint: String? = CameraProfile.R6_MARK_III.modelName,
) {
    private val rootUrl = runCatching { baseUrl.trimEnd('/').toHttpUrl() }
        .getOrElse { throw IllegalArgumentException("Invalid desktop bridge URL: $baseUrl", it) }
        .also { url ->
            require(url.username.isEmpty() && url.password.isEmpty()) {
                "Desktop Bridge credentials must use the separate Bearer token field."
            }
            require(url.query == null && url.fragment == null) {
                "Desktop Bridge URL must not contain a query or fragment."
            }
        }
    private val httpClient = (httpClient ?: OkHttpClient()).newBuilder().apply {
        val bearerToken = token.trim()
        if (bearerToken.isNotEmpty()) {
            addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $bearerToken")
                        .build()
                )
            }
        }
    }.build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private var sessionId: String? = null

    suspend fun discoverCameras(): List<DesktopBridgeCamera> {
        validateService()
        return parseCameras(getJson(endpoint("v1", "cameras")))
    }

    suspend fun initialize() {
        check(sessionId == null) { "Desktop bridge session is already initialized." }
        validateService()
        val payload = JSONObject().put("engine", "auto")
        cameraId?.takeIf(String::isNotBlank)?.let { payload.put("cameraId", it) }
        profileHint?.takeIf(String::isNotBlank)?.let { payload.put("profileHint", it) }
        val created = postJson(endpoint("v1", "session"), payload)
        sessionId = created.requireString("id", "Desktop bridge did not return a session ID.")
    }

    suspend fun close() {
        val id = sessionId ?: return
        try {
            requestOk(Request.Builder().url(endpoint("v1", "session", id)).delete().build())
        } finally {
            sessionId = null
        }
    }

    suspend fun info(): CameraInfo {
        val body = getJson(sessionEndpoint("info"))
        return CameraInfo(
            connected = body.optBoolean("connected", true),
            model = body.requireString("model", "Bridge camera model is missing."),
            serial = body.optString("serial", "unknown"),
            api = body.optString("api", "desktop-bridge/v1"),
            manufacturer = body.optNullableString("manufacturer"),
            deviceVersion = body.optNullableString("deviceVersion"),
            engineVersion = body.optNullableString("engineVersion"),
        )
    }

    suspend fun status(): CameraStatus = parseStatus(getJson(sessionEndpoint("status")))

    suspend fun capabilities(): CameraCapabilities {
        val body = getJson(sessionEndpoint("capabilities"))
        val settings = body.optJSONArray("settings").objects().mapNotNull { setting ->
            val key = setting.optString("key").trim()
            val values = setting.optJSONArray("values").strings()
            if (key.isBlank() || values.isEmpty()) {
                null
            } else {
                CameraSettingControl(
                    key = key,
                    label = setting.optString("label", key),
                    value = setting.optString("value"),
                    values = values,
                )
            }
        }
        val settingsByKey = settings.associateBy { it.key.lowercase() }
        val coreKeys = setOf("iso", "shutter", "aperture", "whitebalance")
        val supported = body.optJSONArray("supported").cameraFeatures()
        val planned = body.optJSONArray("planned").cameraFeatures() - supported
        val reasonsObject = body.optJSONObject("reasons") ?: JSONObject()
        val reasons = buildMap {
            reasonsObject.keys().forEach { key ->
                key.toCameraFeatureOrNull()?.let { put(it, reasonsObject.optString(key)) }
            }
        }
        val liveView = body.optJSONObject("liveView") ?: JSONObject()
        val sources = liveView.optJSONArray("sources").enumValues<LiveViewSource>()
        val sizes = liveView.optJSONArray("sizes").enumValues<LiveViewSize>()
        val minFps = liveView.optInt("minFps", 1).coerceAtLeast(1)
        val maxFps = liveView.optInt("maxFps", minFps).coerceAtLeast(minFps)
        val profile = body.optJSONObject("profile") ?: JSONObject()
        val evidenceBody = body.optJSONObject("evidence") ?: JSONObject()
        val evidenceCommands = evidenceBody.optJSONArray("advertisedCommands").strings()
            .map { it.substringBefore('?').replace("\r", "").replace("\n", "").take(MAX_CAPABILITY_EVIDENCE_ITEM_CHARS) }
            .distinct()
            .take(MAX_CAPABILITY_EVIDENCE_ITEMS)
        val evidenceSettings = evidenceBody.optJSONArray("writableSettings").strings()
            .map { it.replace("\r", "").replace("\n", "").take(MAX_CAPABILITY_EVIDENCE_ITEM_CHARS) }
            .distinct()
            .take(MAX_CAPABILITY_EVIDENCE_ITEMS)
        val evidenceVersions = evidenceBody.optJSONArray("protocolVersions").strings()
            .map { it.replace("\r", "").replace("\n", "").take(MAX_CAPABILITY_EVIDENCE_ITEM_CHARS) }
            .distinct()
            .take(MAX_CAPABILITY_EVIDENCE_ITEMS)
        return CameraCapabilities(
            iso = settingsByKey["iso"]?.values.orEmpty(),
            shutter = settingsByKey["shutter"]?.values.orEmpty(),
            aperture = settingsByKey["aperture"]?.values.orEmpty(),
            whiteBalance = settingsByKey["whitebalance"]?.values.orEmpty(),
            advancedSettings = settings.filterNot { it.key.lowercase() in coreKeys },
            matrix = CapabilityMatrix(
                supported = supported,
                planned = planned,
                reasons = reasons,
            ),
            liveView = LiveViewCapabilities(
                sources = sources,
                defaultSource = liveView.optString("defaultSource").toEnumOrNull<LiveViewSource>()
                    ?.takeIf { it in sources }
                    ?: sources.firstOrNull()
                    ?: LiveViewSource.AUTO,
                sizes = sizes,
                defaultSize = liveView.optString("defaultSize").toEnumOrNull<LiveViewSize>()
                    ?.takeIf { it in sizes }
                    ?: sizes.firstOrNull()
                    ?: LiveViewSize.MEDIUM,
                minFps = minFps,
                maxFps = maxFps,
            ),
            profile = CameraProfile(
                modelName = profile.optString("modelName", CameraProfile.R6_MARK_III.modelName),
                family = profile.optString("family").toEnumOrNull<CameraModelFamily>()
                    ?: CameraModelFamily.UNKNOWN,
                priority = profile.optString("priority").toEnumOrNull<CameraModelPriority>()
                    ?: CameraModelPriority.RESEARCH,
            ),
            evidence = CameraCapabilityEvidence(
                source = evidenceBody.optString("source", "unknown")
                    .replace("\r", "")
                    .replace("\n", "")
                    .take(MAX_CAPABILITY_EVIDENCE_ITEM_CHARS),
                protocolVersions = evidenceVersions,
                advertisedCommands = evidenceCommands,
                writableSettings = evidenceSettings,
                truncated = evidenceBody.optBoolean("truncated") ||
                    (evidenceBody.optJSONArray("protocolVersions")?.length() ?: 0) > evidenceVersions.size ||
                    (evidenceBody.optJSONArray("advertisedCommands")?.length() ?: 0) > evidenceCommands.size ||
                    (evidenceBody.optJSONArray("writableSettings")?.length() ?: 0) > evidenceSettings.size,
            ),
        )
    }

    suspend fun setExposure(
        iso: String? = null,
        shutter: String? = null,
        aperture: String? = null,
    ): CameraStatus {
        val updates = listOfNotNull(
            iso?.let { "iso" to it },
            shutter?.let { "shutter" to it },
            aperture?.let { "aperture" to it },
        )
        require(updates.isNotEmpty()) { "At least one exposure value is required." }
        var updatedStatus: CameraStatus? = null
        updates.forEach { (key, value) -> updatedStatus = setSetting(key, value) }
        return requireNotNull(updatedStatus)
    }

    suspend fun setWhiteBalance(value: String): CameraStatus = setSetting("whitebalance", value)

    suspend fun setSetting(key: String, value: String): CameraStatus = parseStatus(
        postJson(
            sessionEndpoint("settings", key),
            JSONObject().put("value", value),
        )
    )

    suspend fun captureStill(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("capture", "still"), JSONObject())
    )

    suspend fun autofocus(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("focus", "auto"), JSONObject())
    )

    suspend fun halfPressShutter(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("shutter", "half-press"), JSONObject())
    )

    suspend fun startRecording(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("recording", "start"), JSONObject())
    )

    suspend fun stopRecording(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("recording", "stop"), JSONObject())
    )

    suspend fun tapFocus(x: Double, y: Double): FocusResult {
        val body = postJson(
            sessionEndpoint("focus", "tap"),
            JSONObject().put("x", x).put("y", y),
        )
        return FocusResult(ok = body.optBoolean("accepted"), x = x, y = y)
    }

    suspend fun clickWhiteBalance(x: Double, y: Double): CameraStatus = parseStatus(
        postJson(
            sessionEndpoint("whitebalance", "click"),
            JSONObject().put("x", x).put("y", y),
        )
    )

    suspend fun driveFocus(direction: FocusDriveDirection, step: FocusDriveStep): FocusDriveResult {
        val body = postJson(
            sessionEndpoint("focus", "drive"),
            JSONObject()
                .put("direction", direction.name)
                .put("step", step.name),
        )
        return FocusDriveResult(
            ok = body.optBoolean("accepted"),
            direction = body.optString("direction").toEnumOrNull<FocusDriveDirection>() ?: direction,
            step = body.optString("step").toEnumOrNull<FocusDriveStep>() ?: step,
        )
    }

    suspend fun startLiveView(request: LiveViewRequest) {
        postJson(
            sessionEndpoint("liveview", "start"),
            JSONObject()
                .put("fps", request.fps)
                .put("size", request.size.name)
                .put("source", request.source.name),
        )
    }

    suspend fun stopLiveView() {
        postJson(sessionEndpoint("liveview", "stop"), JSONObject())
    }

    fun liveViewFrameUrl(cacheKey: Long): String = frameUrl(cacheKey).toString()

    suspend fun liveViewFrame(cacheKey: Long): LiveViewFrame = withContext(Dispatchers.IO) {
        val sourceUrl = liveViewFrameUrl(cacheKey)
        val request = Request.Builder()
            .url(sourceUrl)
            .get()
            .header("Accept", "image/jpeg")
            .header("Cache-Control", "no-cache")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body ?: error("Desktop Bridge returned an empty Live View response.")
            if (!response.isSuccessful) throw bridgeError(response.code, body.string(), "Live View frame")
            val contentLength = body.contentLength()
            check(contentLength <= MAX_LIVE_VIEW_FRAME_BYTES || contentLength < 0) {
                "Desktop Bridge Live View frame exceeded $MAX_LIVE_VIEW_FRAME_BYTES bytes."
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    check(output.size() <= MAX_LIVE_VIEW_FRAME_BYTES) {
                        "Desktop Bridge Live View frame exceeded $MAX_LIVE_VIEW_FRAME_BYTES bytes."
                    }
                }
            }
            val bytes = output.toByteArray()
            check(bytes.isCompleteJpeg()) { "Desktop Bridge did not return a complete JPEG frame." }
            LiveViewFrame(
                bytes = bytes,
                contentType = response.header("content-type"),
                sourceUrl = sourceUrl,
            )
        }
    }

    suspend fun listMedia(): List<CameraMediaItem> =
        getJson(sessionEndpoint("media")).optJSONArray("items").objects().mapNotNull { item ->
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            if (id.isBlank() || name.isBlank()) {
                null
            } else {
                CameraMediaItem(
                    id = id,
                    name = name,
                    kind = item.optString("kind", "other"),
                    sizeBytes = item.optNullableLong("sizeBytes"),
                    captureTime = item.optNullableString("captureTime"),
                )
            }
        }

    suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(sessionEndpoint("media", item.id, "thumbnail"))
            .header("Accept", "image/*")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body ?: error("Desktop Bridge returned an empty thumbnail response.")
            if (!response.isSuccessful) throw bridgeError(response.code, body.string(), "Media thumbnail")
            val contentLength = body.contentLength()
            check(contentLength <= MAX_MEDIA_THUMBNAIL_BYTES || contentLength < 0L) {
                "Desktop Bridge thumbnail exceeded $MAX_MEDIA_THUMBNAIL_BYTES bytes."
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    check(output.size().toLong() <= MAX_MEDIA_THUMBNAIL_BYTES) {
                        "Desktop Bridge thumbnail exceeded $MAX_MEDIA_THUMBNAIL_BYTES bytes."
                    }
                }
            }
            val bytes = output.toByteArray()
            val contentType = response.header("content-type")?.substringBefore(';')?.trim()
            check(bytes.isNotEmpty() && contentType?.startsWith("image/") == true) {
                "Desktop Bridge did not return an image thumbnail."
            }
            CameraMediaThumbnail(item = item, bytes = bytes, contentType = contentType)
        }
    }

    suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaDownloadResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(sessionEndpoint("media", item.id)).get().build()
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
                throw exception
            }
            response.use {
                val body = response.body ?: error("Desktop Bridge returned an empty media response.")
                if (!response.isSuccessful) throw bridgeError(response.code, body.string(), "Media download")
                val responseLength = body.contentLength().takeIf { it >= 0L }
                val totalBytes = responseLength ?: item.sizeBytes
                var bytesTransferred = 0L
                var lastReportedBytes = 0L
                onProgress(CameraMediaTransferProgress(0L, totalBytes))
                val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
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
                    contentType = response.header("content-type"),
                )
            }
        } finally {
            cancelCall.set(false)
            cancellationWatcher.cancel()
        }
    }

    suspend fun deleteMedia(item: CameraMediaItem) {
        requestOk(
            Request.Builder()
                .url(sessionEndpoint("media", item.id))
                .delete()
                .build(),
        )
    }

    private suspend fun validateService() {
        val health = getJson(endpoint("health"))
        check(health.optString("service") == BRIDGE_SERVICE_NAME) {
            "The URL is not an Open EOS Control Desktop Bridge."
        }
    }

    private fun parseCameras(body: JSONObject): List<DesktopBridgeCamera> =
        body.optJSONArray("cameras").objects().mapNotNull { camera ->
            val id = camera.optString("id").trim()
            val model = camera.optString("model").trim()
            if (id.isBlank() || model.isBlank()) {
                null
            } else {
                DesktopBridgeCamera(
                    id = id,
                    model = model,
                    port = camera.optString("port"),
                    engine = camera.optString("engine", "libgphoto2"),
                )
            }
        }

    private fun parseStatus(body: JSONObject): CameraStatus {
        val battery = body.optJSONObject("battery") ?: JSONObject()
        val media = body.optJSONObject("media") ?: JSONObject()
        val exposure = body.optJSONObject("exposure") ?: JSONObject()
        val raw = body.optJSONObject("raw") ?: JSONObject()
        return CameraStatus(
            connected = body.optBoolean("connected", true),
            batteryLevel = battery.optNullableInt("level"),
            batteryStatus = battery.optString("status", "unknown"),
            recording = body.optNullableBoolean("recording"),
            mode = body.optString("mode", "unknown"),
            mediaAvailable = media.optNullableBoolean("available"),
            remainingMinutes = null,
            exposure = ExposureState(
                iso = exposure.optString("iso", "-"),
                shutter = exposure.optString("shutter", "-"),
                aperture = exposure.optString("aperture", "-"),
                whiteBalance = exposure.optString("whiteBalance", "-"),
            ),
            rawBatteryJson = battery.toString(),
            rawStorageJson = media.toString(),
            rawTransportJson = raw.toString(),
        )
    }

    private suspend fun getJson(url: HttpUrl): JSONObject = requestJson(
        Request.Builder().url(url).get().build()
    )

    private suspend fun postJson(url: HttpUrl, payload: JSONObject): JSONObject = requestJson(
        Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
    )

    private suspend fun requestJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw bridgeError(response.code, body, request.url.encodedPath)
            runCatching { JSONObject(body) }
                .getOrElse { throw IllegalStateException("Desktop Bridge returned invalid JSON for ${request.url.encodedPath}.", it) }
        }
    }

    private suspend fun requestOk(request: Request): Unit = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw bridgeError(response.code, body, request.url.encodedPath)
        }
    }

    private fun bridgeError(statusCode: Int, body: String, operation: String): DesktopBridgeException {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val code = error?.optString("code")?.takeIf(String::isNotBlank) ?: "HTTP_$statusCode"
        val message = error?.optString("message")?.takeIf(String::isNotBlank)
            ?: body.trim().take(MAX_ERROR_BODY_CHARS).ifBlank { "Desktop Bridge returned HTTP $statusCode." }
        return DesktopBridgeException(
            code = code,
            feature = error?.optNullableString("feature"),
            engine = error?.optNullableString("engine"),
            message = "Desktop Bridge $operation failed [$code]: $message",
        )
    }

    private fun endpoint(vararg segments: String): HttpUrl = rootUrl.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()

    private fun sessionEndpoint(vararg segments: String): HttpUrl =
        endpoint("v1", "session", requireNotNull(sessionId) { "Desktop bridge session is not initialized." }, *segments)

    private fun frameUrl(cacheKey: Long): HttpUrl = sessionEndpoint("liveview", "frame").newBuilder()
        .addQueryParameter("t", cacheKey.toString())
        .build()

    private companion object {
        const val BRIDGE_SERVICE_NAME = "open-eos-control-bridge"
        const val MAX_LIVE_VIEW_FRAME_BYTES = 12 * 1024 * 1024L
        const val MAX_ERROR_BODY_CHARS = 2_000
        const val MAX_MEDIA_THUMBNAIL_BYTES = 8 * 1024 * 1024L
        const val TRANSFER_BUFFER_BYTES = 64 * 1024
        const val MEDIA_PROGRESS_INTERVAL_BYTES = 512 * 1024L
    }
}

private fun JSONObject.requireString(key: String, message: String): String =
    optString(key).takeIf(String::isNotBlank) ?: error(message)

private fun JSONObject.optNullableString(key: String): String? =
    takeIf { has(key) && !isNull(key) }?.optString(key)?.takeIf(String::isNotBlank)

private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    takeIf { has(key) && !isNull(key) }?.optBoolean(key)

private fun JSONObject.optNullableInt(key: String): Int? =
    takeIf { has(key) && !isNull(key) }?.optInt(key)

private fun JSONObject.optNullableLong(key: String): Long? =
    takeIf { has(key) && !isNull(key) }?.optLong(key)

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

private fun JSONArray?.cameraFeatures(): Set<CameraFeature> = strings().mapNotNull(String::toCameraFeatureOrNull).toSet()

private fun String.toCameraFeatureOrNull(): CameraFeature? = toEnumOrNull<CameraFeature>()

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    runCatching { enumValueOf<T>(uppercase()) }.getOrNull()

private inline fun <reified T : Enum<T>> JSONArray?.enumValues(): List<T> =
    strings().mapNotNull { it.toEnumOrNull<T>() }.distinct()

private fun ByteArray.isCompleteJpeg(): Boolean =
    size >= 4 &&
        (this[0].toInt() and 0xFF) == 0xFF &&
        (this[1].toInt() and 0xFF) == 0xD8 &&
        (this[lastIndex - 1].toInt() and 0xFF) == 0xFF &&
        (this[lastIndex].toInt() and 0xFF) == 0xD9
