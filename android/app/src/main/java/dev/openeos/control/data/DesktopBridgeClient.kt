package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

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
    cameraEngine: String? = null,
    private val profileHint: String? = CameraProfile.R6_MARK_III.modelName,
) {
    private val cameraEngine = cameraEngine
        ?.trim()
        ?.takeIf { it in SUPPORTED_LOCAL_ENGINES }
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
    private val eventHttpClient = this.httpClient.newBuilder()
        .readTimeout(EVENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(EVENT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val activeEventCall = AtomicReference<Call?>(null)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private var sessionId: String? = null
    private var sessionCameraModel: String? = null
    private var eventPollingSupported = false
    private var liveViewMagnifications: List<LiveViewMagnification> = emptyList()
    private val observedFeatures = mutableSetOf(CameraFeature.DESKTOP_BRIDGE)

    fun observedFeatureSnapshot(): Set<CameraFeature> = observedFeatures.toSet()

    suspend fun discoverCameras(): List<DesktopBridgeCamera> {
        validateService()
        return parseCameras(getJson(endpoint("v1", "cameras")))
    }

    suspend fun initialize() {
        check(sessionId == null) { "Desktop bridge session is already initialized." }
        observedFeatures.clear()
        observedFeatures.add(CameraFeature.DESKTOP_BRIDGE)
        eventPollingSupported = false
        liveViewMagnifications = emptyList()
        sessionCameraModel = null
        validateService()
        val payload = JSONObject().put("engine", cameraEngine ?: "auto")
        cameraId?.takeIf(String::isNotBlank)?.let { payload.put("cameraId", it) }
        profileHint?.takeIf(String::isNotBlank)?.let { payload.put("profileHint", it) }
        val created = postJson(endpoint("v1", "session"), payload)
        sessionId = created.requireString("id", "Desktop bridge did not return a session ID.")
        sessionCameraModel = created.optJSONObject("camera")
            ?.optNullableString("model")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    suspend fun close() {
        val id = sessionId ?: return
        try {
            stopEventPolling()
            requestOk(Request.Builder().url(endpoint("v1", "session", id)).delete().build())
        } finally {
            sessionId = null
            sessionCameraModel = null
            eventPollingSupported = false
            liveViewMagnifications = emptyList()
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
        ).also { info ->
            sessionCameraModel = info.model
            observedFeatures.add(CameraFeature.CAMERA_IDENTITY)
        }
    }

    suspend fun status(): CameraStatus = parseStatus(getJson(sessionEndpoint("status")))

    suspend fun pollEvent(): CameraEvent {
        check(eventPollingSupported) { "Desktop Bridge did not advertise camera event polling." }
        val body = requestEventJson(Request.Builder().url(sessionEndpoint("events")).get().build())
        observedFeatures.add(CameraFeature.EVENT_POLLING)
        return CameraEvent(
            changedKeys = body.optJSONArray("changedKeys").strings()
                .asSequence()
                .map { it.replace("\r", "").replace("\n", "").trim().take(MAX_EVENT_KEY_CHARS) }
                .filter(String::isNotBlank)
                .take(MAX_EVENT_KEYS)
                .toSet(),
        )
    }

    suspend fun stopEventPolling() {
        activeEventCall.getAndSet(null)?.cancel()
        if (sessionId == null || !eventPollingSupported) return
        runCatching {
            requestOk(Request.Builder().url(sessionEndpoint("events")).delete().build())
        }
    }

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
        val advertisedSupported = body.optJSONArray("supported").cameraFeatures()
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
        val magnifications = liveView.optJSONArray("magnifications").liveViewMagnifications()
        val currentMagnification = (liveView.opt("currentMagnification") as? Int)
            ?.let { value -> LiveViewMagnification.entries.firstOrNull { it.value == value } }
            ?.takeIf { it in magnifications }
        val hasCurrentMagnification = liveView.has("currentMagnification") &&
            !liveView.isNull("currentMagnification")
        val validMagnificationCapability =
            magnifications.size >= 2 &&
                LiveViewMagnification.X1 in magnifications &&
                (!hasCurrentMagnification || currentMagnification != null)
        liveViewMagnifications = magnifications.takeIf { validMagnificationCapability }.orEmpty()
        val supported = if (validMagnificationCapability) {
            advertisedSupported
        } else {
            advertisedSupported - CameraFeature.LIVE_VIEW_MAGNIFICATION
        }
        eventPollingSupported = CameraFeature.EVENT_POLLING in supported
        val planned = (body.optJSONArray("planned").cameraFeatures() - supported) +
            if (CameraFeature.LIVE_VIEW_MAGNIFICATION in advertisedSupported && !validMagnificationCapability) {
                setOf(CameraFeature.LIVE_VIEW_MAGNIFICATION)
            } else {
                emptySet()
            }
        val profile = body.optJSONObject("profile") ?: JSONObject()
        val fileNaming = body.optJSONObject("fileNaming")?.toBridgeFileNamingOrNull()
        val profileModel = profile.optNullableString("modelName")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: sessionCameraModel
            ?: "Canon Camera"
        val inferredProfile = CameraProfile.fromModelName(profileModel)
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
        val evidenceObservedFeatures = (
            evidenceBody.optJSONArray("observedFeatures").cameraFeatures() + observedFeatures
        )
            .take(MAX_CAPABILITY_EVIDENCE_ITEMS)
            .toSet()
        val rawDiscoveryTrace = evidenceBody.optJSONArray("discoveryTrace")
        val evidenceDiscoveryTrace = rawDiscoveryTrace.objects()
            .take(MAX_DISCOVERY_TRACE_ATTEMPTS)
            .mapNotNull { attempt ->
                val endpoint = attempt.optString("endpoint")
                    .takeIf { it.matches(Regex("GET /ccapi(?:/[A-Za-z0-9._~-]+)*/?")) }
                    ?: return@mapNotNull null
                val outcome = attempt.optString("outcome")
                    .takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) }
                    ?: return@mapNotNull null
                val keys = attempt.optJSONArray("responseKeys").strings()
                    .filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) }
                    .distinct()
                    .take(MAX_DISCOVERY_TRACE_KEYS)
                val versions = attempt.optJSONArray("protocolVersions").strings()
                    .filter { it.matches(Regex("""ver\d+""")) }
                    .distinct()
                    .take(MAX_DISCOVERY_TRACE_KEYS)
                CameraDiscoveryAttempt(
                    endpoint = endpoint,
                    outcome = outcome,
                    httpStatus = attempt.optInt("httpStatus")
                        .takeIf { attempt.has("httpStatus") && it in 100..599 },
                    responseKeys = keys,
                    protocolVersions = versions,
                    advertisedOperationCount = attempt.optInt("advertisedOperationCount").coerceAtLeast(0),
                    truncated = attempt.optBoolean("truncated") ||
                        (attempt.optJSONArray("responseKeys")?.length() ?: 0) > keys.size ||
                        (attempt.optJSONArray("protocolVersions")?.length() ?: 0) > versions.size,
                )
            }
        observedFeatures.addAll(evidenceObservedFeatures)
        return CameraCapabilities(
            iso = settingsByKey["iso"]?.values.orEmpty(),
            shutter = settingsByKey["shutter"]?.values.orEmpty(),
            aperture = settingsByKey["aperture"]?.values.orEmpty(),
            whiteBalance = settingsByKey["whitebalance"]?.values.orEmpty(),
            advancedSettings = settings.filterNot { it.key.lowercase() in coreKeys },
            fileNaming = fileNaming,
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
                magnifications = liveViewMagnifications,
                currentMagnification = currentMagnification,
            ),
            profile = CameraProfile(
                modelName = profileModel,
                family = profile.optString("family").toEnumOrNull<CameraModelFamily>()
                    ?: inferredProfile.family,
                priority = profile.optString("priority").toEnumOrNull<CameraModelPriority>()
                    ?: inferredProfile.priority,
            ),
            evidence = CameraCapabilityEvidence(
                source = evidenceBody.optString("source", "unknown")
                    .replace("\r", "")
                    .replace("\n", "")
                    .take(MAX_CAPABILITY_EVIDENCE_ITEM_CHARS),
                protocolVersions = evidenceVersions,
                advertisedCommands = evidenceCommands,
                writableSettings = evidenceSettings,
                observedFeatures = evidenceObservedFeatures,
                discoveryTrace = evidenceDiscoveryTrace,
                truncated = evidenceBody.optBoolean("truncated") ||
                    (evidenceBody.optJSONArray("protocolVersions")?.length() ?: 0) > evidenceVersions.size ||
                    (evidenceBody.optJSONArray("advertisedCommands")?.length() ?: 0) > evidenceCommands.size ||
                    (evidenceBody.optJSONArray("writableSettings")?.length() ?: 0) > evidenceSettings.size ||
                    (evidenceBody.optJSONArray("observedFeatures")?.length() ?: 0) > evidenceObservedFeatures.size ||
                    (rawDiscoveryTrace?.length() ?: 0) > evidenceDiscoveryTrace.size ||
                    evidenceDiscoveryTrace.any(CameraDiscoveryAttempt::truncated),
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
    ).also { observedFeatures.add(featureForSetting(key)) }

    suspend fun createDirectory(name: String): String {
        require(Regex("^(?:[A-Z0-9_]{5})?$").matches(name)) {
            "Directory name must be empty or exactly five uppercase letters, numbers, or underscores."
        }
        val created = postJson(
            sessionEndpoint("directories"),
            JSONObject().put("name", name),
        ).opt("name") as? String
        require(created != null && Regex("^[A-Z0-9_]{5}$").matches(created)) {
            "Desktop Bridge returned an invalid created directory name."
        }
        observedFeatures.add(CameraFeature.DIRECTORY_CONTROL)
        return created
    }

    suspend fun setFileNaming(field: CameraFileNamingField, value: String): CameraFileNaming {
        val current = capabilities().fileNaming
            ?: error("Desktop Bridge did not advertise Canon file-naming control.")
        require(current.accepts(field, value)) {
            "Value '$value' is not valid for Canon file-naming field ${field.wireName}."
        }
        val updated = putJson(
            sessionEndpoint("file-naming", field.wireName),
            JSONObject().put("value", value),
        ).toBridgeFileNamingOrNull()
            ?: error("Desktop Bridge returned an invalid file-naming state.")
        check(updated.accepts(field, value) && updated.fileNamingValue(field) == value) {
            "Desktop Bridge did not return the requested file-naming value."
        }
        observedFeatures.add(CameraFeature.FILE_NAMING_CONTROL)
        return updated
    }

    suspend fun syncCameraClock(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("clock", "sync"), JSONObject())
    ).also { observedFeatures.add(CameraFeature.CAMERA_CLOCK_SYNC) }

    suspend fun cleanSensor(autoPowerOff: Boolean) {
        requestOk(
            Request.Builder()
                .url(sessionEndpoint("maintenance", "sensor-cleaning"))
                .post(JSONObject().put("autoPowerOff", autoPowerOff).toString().toRequestBody(jsonMediaType))
                .build(),
        )
        observedFeatures.add(CameraFeature.SENSOR_CLEANING)
    }

    suspend fun sleepCamera() {
        requestOk(
            Request.Builder()
                .url(sessionEndpoint("power", "sleep"))
                .post(JSONObject().toString().toRequestBody(jsonMediaType))
                .build(),
        )
        observedFeatures.add(CameraFeature.CAMERA_SLEEP)
    }

    suspend fun captureStill(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("capture", "still"), JSONObject())
    ).also { observedFeatures.add(CameraFeature.STILL_CAPTURE) }

    suspend fun startBulbExposure(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("bulb", "start"), JSONObject())
    )

    suspend fun stopBulbExposure(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("bulb", "stop"), JSONObject())
    ).also { observedFeatures.add(CameraFeature.BULB_EXPOSURE) }

    suspend fun autofocus(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("focus", "auto"), JSONObject())
    ).also { observedFeatures.add(CameraFeature.AUTOFOCUS) }

    suspend fun halfPressShutter(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("shutter", "half-press"), JSONObject())
    ).also { observedFeatures.add(CameraFeature.SHUTTER_HALF_PRESS) }

    suspend fun startRecording(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("recording", "start"), JSONObject())
    ).also { observedFeatures.add(CameraFeature.VIDEO_RECORDING) }

    suspend fun stopRecording(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("recording", "stop"), JSONObject())
    ).also { observedFeatures.add(CameraFeature.VIDEO_RECORDING) }

    suspend fun tapFocus(x: Double, y: Double): FocusResult {
        val body = postJson(
            sessionEndpoint("focus", "tap"),
            JSONObject().put("x", x).put("y", y),
        )
        return FocusResult(ok = body.optBoolean("accepted"), x = x, y = y).also {
            if (it.ok) observedFeatures.add(CameraFeature.TAP_FOCUS)
        }
    }

    suspend fun clickWhiteBalance(x: Double, y: Double): CameraStatus = parseStatus(
        postJson(
            sessionEndpoint("whitebalance", "click"),
            JSONObject().put("x", x).put("y", y),
        )
    ).also { observedFeatures.add(CameraFeature.CLICK_WHITE_BALANCE) }

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
        ).also { if (it.ok) observedFeatures.add(CameraFeature.FOCUS_DRIVE) }
    }

    suspend fun setLiveViewMagnification(
        magnification: LiveViewMagnification,
    ): LiveViewMagnificationResult {
        require(magnification in liveViewMagnifications) {
            "Desktop Bridge did not advertise ${magnification.value}x Live View magnification."
        }
        val body = postJson(
            sessionEndpoint("liveview", "magnification"),
            JSONObject().put("value", magnification.value),
        )
        val returned = LiveViewMagnification.entries.firstOrNull {
            it.value == body.optInt("value", -1)
        } ?: error("Desktop Bridge returned an invalid Live View magnification value.")
        return LiveViewMagnificationResult(
            ok = body.optBoolean("accepted"),
            magnification = returned,
        ).also { if (it.ok) observedFeatures.add(CameraFeature.LIVE_VIEW_MAGNIFICATION) }
    }

    suspend fun startLiveView(request: LiveViewRequest) {
        postJson(
            sessionEndpoint("liveview", "start"),
            JSONObject()
                .put("fps", request.fps)
                .put("size", request.size.name)
                .put("source", request.source.name),
        )
        observedFeatures.add(CameraFeature.LIVE_VIEW)
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
            observedFeatures.addAll(setOf(CameraFeature.LIVE_VIEW, CameraFeature.LIVE_VIEW_JPEG_POLLING))
            LiveViewFrame(
                bytes = bytes,
                contentType = response.header("content-type"),
                sourceUrl = sourceUrl,
            )
        }
    }

    suspend fun listMedia(): List<CameraMediaItem> {
        val items = getJson(sessionEndpoint("media")).optJSONArray("items").objects().mapNotNull(::parseMediaItem)
        observedFeatures.add(CameraFeature.MEDIA_BROWSER)
        return items
    }

    suspend fun mediaInfo(item: CameraMediaItem): CameraMediaItem =
        parseMediaItem(getJson(sessionEndpoint("media", item.id, "info")))
            ?: error("Desktop Bridge returned invalid media information for ${item.name}.")

    suspend fun setMediaProtection(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        updateMediaMetadata(
            item,
            endpoint = "protection",
            payload = JSONObject().put("enabled", enabled),
            feature = CameraFeature.MEDIA_PROTECT,
        )

    suspend fun setMediaRating(item: CameraMediaItem, rating: Int): CameraMediaItem {
        require(rating in 0..5) { "Media rating must be from 0 through 5." }
        return updateMediaMetadata(
            item,
            endpoint = "rating",
            payload = JSONObject().put("value", rating),
            feature = CameraFeature.MEDIA_RATING,
        )
    }

    suspend fun setMediaRotation(item: CameraMediaItem, degrees: Int): CameraMediaItem {
        require(degrees in MEDIA_ROTATIONS) { "Media rotation must be 0, 90, 180, or 270 degrees." }
        return updateMediaMetadata(
            item,
            endpoint = "rotation",
            payload = JSONObject().put("degrees", degrees),
            feature = CameraFeature.MEDIA_ROTATE,
        )
    }

    private suspend fun updateMediaMetadata(
        item: CameraMediaItem,
        endpoint: String,
        payload: JSONObject,
        feature: CameraFeature,
    ): CameraMediaItem = parseMediaItem(
        putJson(sessionEndpoint("media", item.id, endpoint), payload),
    )?.also { observedFeatures.add(feature) }
        ?: error("Desktop Bridge returned invalid media information after updating ${item.name}.")

    suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail {
        val (bytes, contentType) = mediaImageRepresentation(
            item = item,
            endpoint = "thumbnail",
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
            endpoint = "preview",
            maxBytes = MAX_MEDIA_PREVIEW_BYTES,
            label = "display preview",
        )
        observedFeatures.add(CameraFeature.MEDIA_PREVIEW)
        return CameraMediaPreview(item = item, bytes = bytes, contentType = contentType)
    }

    private suspend fun mediaImageRepresentation(
        item: CameraMediaItem,
        endpoint: String,
        maxBytes: Long,
        label: String,
    ): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(sessionEndpoint("media", item.id, endpoint))
            .header("Accept", "image/*")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body ?: error("Desktop Bridge returned an empty $label response.")
            if (!response.isSuccessful) throw bridgeError(response.code, body.string(), "Media $label")
            val contentLength = body.contentLength()
            check(contentLength <= maxBytes || contentLength < 0L) {
                "Desktop Bridge $label exceeded $maxBytes bytes."
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
            body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    check(output.size().toLong() <= maxBytes) {
                        "Desktop Bridge $label exceeded $maxBytes bytes."
                    }
                }
            }
            val bytes = output.toByteArray()
            val contentType = response.header("content-type")?.substringBefore(';')?.trim()
            check(bytes.isNotEmpty() && contentType?.startsWith("image/") == true) {
                "Desktop Bridge did not return an image $label."
            }
            bytes to contentType
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
                ).also { observedFeatures.add(CameraFeature.MEDIA_DOWNLOAD) }
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
        observedFeatures.add(CameraFeature.MEDIA_DELETE)
    }

    private fun featureForSetting(key: String): CameraFeature = when (key.lowercase()) {
        "iso", "shutter", "aperture" -> CameraFeature.EXPOSURE_CONTROL
        "whitebalance" -> CameraFeature.WHITE_BALANCE_CONTROL
        "cardselectionstillimage", "cardselectionmovie" -> CameraFeature.CARD_SELECTION_CONTROL
        "soundrecording", "windfilter", "attenuator" -> CameraFeature.SOUND_RECORDING_CONTROL
        "soundrecordinglevel" -> CameraFeature.SOUND_RECORDING_LEVEL_CONTROL
        "directoryselection" -> CameraFeature.DIRECTORY_CONTROL
        else -> CameraFeature.ADVANCED_SETTINGS
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

    private fun parseMediaItem(item: JSONObject): CameraMediaItem? {
        val id = item.optString("id").trim()
        val name = item.optString("name").trim()
        if (id.isBlank() || name.isBlank()) return null
        return CameraMediaItem(
            id = id,
            name = name,
            kind = item.optString("kind", "other"),
            sizeBytes = item.optNullableLong("sizeBytes"),
            captureTime = item.optNullableString("captureTime"),
            previewAvailable = item.optBoolean("previewAvailable", false),
            protected = item.optNullableBoolean("protected"),
            rating = item.optNullableInt("rating")?.takeIf { it in 0..5 },
            rotationDegrees = item.optNullableInt("rotationDegrees")?.takeIf { it in MEDIA_ROTATIONS },
        )
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
            storageTotalBytes = media.optNullableLong("totalBytes"),
            storageFreeBytes = media.optNullableLong("freeBytes"),
            storageFreeImages = media.optNullableLong("freeImages"),
            storageDeviceCount = media.optNullableInt("devices"),
            recordableShots = body.optNullableLong("recordableShots"),
            remainingRecordingSeconds = body.optNullableLong("remainingRecordingSeconds"),
            rawBatteryJson = battery.toString(),
            rawStorageJson = media.toString(),
            rawRecordableJson = raw.optJSONObject("recordable")?.toString() ?: "null",
            rawTransportJson = raw.toString(),
            bulbExposureActive = body.optNullableBoolean("bulbExposureActive"),
            lens = body.optJSONObject("lens")?.let { lens ->
                val mounted = lens.opt("mounted") as? Boolean
                val name = lens.opt("name") as? String
                if (
                    mounted != null && name != null && name.length <= 512 &&
                    name.none { it.isISOControl() } && (!mounted || name.isNotBlank())
                ) {
                    LensStatus(mounted = mounted, name = name.takeIf { mounted }.orEmpty())
                } else {
                    null
                }
            },
            temperature = body.optNullableString("temperature")
                ?.let(CameraTemperatureStatus::fromCcapiValue),
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

    private suspend fun putJson(url: HttpUrl, payload: JSONObject): JSONObject = requestJson(
        Request.Builder()
            .url(url)
            .put(payload.toString().toRequestBody(jsonMediaType))
            .build(),
    )

    private suspend fun requestJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw bridgeError(response.code, body, request.url.encodedPath)
            runCatching { JSONObject(body) }
                .getOrElse { throw IllegalStateException("Desktop Bridge returned invalid JSON for ${request.url.encodedPath}.", it) }
        }
    }

    private suspend fun requestEventJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        val call = eventHttpClient.newCall(request)
        check(activeEventCall.compareAndSet(null, call)) { "A Desktop Bridge event polling request is already active." }
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
                val responseBody = response.body
                val contentLength = responseBody?.contentLength() ?: 0L
                check(contentLength < 0L || contentLength <= MAX_EVENT_BODY_BYTES) {
                    "Desktop Bridge event response was too large."
                }
                val output = ByteArrayOutputStream()
                responseBody?.byteStream()?.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() <= MAX_EVENT_BODY_BYTES - count) {
                            "Desktop Bridge event response was too large."
                        }
                        output.write(buffer, 0, count)
                    }
                }
                val body = output.toByteArray().toString(Charsets.UTF_8)
                if (!response.isSuccessful) throw bridgeError(response.code, body, request.url.encodedPath)
                runCatching { JSONObject(body) }.getOrElse {
                    throw IllegalStateException("Desktop Bridge returned invalid event JSON.", it)
                }
            }
        } finally {
            cancelCall.set(false)
            cancellationWatcher.cancel()
            activeEventCall.compareAndSet(call, null)
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
        val SUPPORTED_LOCAL_ENGINES = setOf("libgphoto2", "edsdk")
        const val BRIDGE_SERVICE_NAME = "open-eos-control-bridge"
        const val MAX_LIVE_VIEW_FRAME_BYTES = 12 * 1024 * 1024L
        const val MAX_ERROR_BODY_CHARS = 2_000
        const val MAX_EVENT_BODY_BYTES = 256 * 1024
        const val MAX_EVENT_KEYS = 64
        const val MAX_EVENT_KEY_CHARS = 128
        const val EVENT_READ_TIMEOUT_SECONDS = 40L
        const val EVENT_CALL_TIMEOUT_SECONDS = 45L
        const val MAX_MEDIA_THUMBNAIL_BYTES = 8 * 1024 * 1024L
        const val MAX_MEDIA_PREVIEW_BYTES = 32 * 1024 * 1024L
        const val TRANSFER_BUFFER_BYTES = 64 * 1024
        const val MEDIA_PROGRESS_INTERVAL_BYTES = 512 * 1024L
        val MEDIA_ROTATIONS = setOf(0, 90, 180, 270)
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

private fun JSONObject.toBridgeIntegerRangeOrNull(maximumAllowed: Int): CameraIntegerRange? {
    val minimum = opt("minimum") as? Int ?: return null
    val maximum = opt("maximum") as? Int ?: return null
    val step = opt("step") as? Int ?: return null
    if (minimum < 1 || maximum > maximumAllowed || minimum > maximum || step <= 0) return null
    return CameraIntegerRange(minimum, maximum, step)
}

private fun JSONObject.toBridgeFileNamingOrNull(): CameraFileNaming? {
    val mode = opt("stillFilenameMode") as? String ?: return null
    val rawOptions = optJSONArray("stillFilenameModeOptions") ?: return null
    val options = (0 until rawOptions.length()).map { rawOptions.opt(it) }
    if (options.any { it !is String }) return null
    val modeOptions = options.filterIsInstance<String>()
    val allowedModes = setOf("preset_code", "usersetting1", "usersetting2")
    if (
        modeOptions.isEmpty() || modeOptions.size > allowedModes.size || modeOptions.toSet().size != modeOptions.size ||
        modeOptions.any { it !in allowedModes } || mode !in modeOptions
    ) return null
    val result = CameraFileNaming(
        stillFilenameMode = mode,
        stillFilenameModeOptions = modeOptions,
        stillUserSetting1 = opt("stillUserSetting1") as? String ?: return null,
        stillUserSetting2 = opt("stillUserSetting2") as? String ?: return null,
        movieIndex = opt("movieIndex") as? String ?: return null,
        movieReelNumber = opt("movieReelNumber") as? Int ?: return null,
        movieReelRange = optJSONObject("movieReelRange")?.toBridgeIntegerRangeOrNull(9999) ?: return null,
        movieClipNumber = opt("movieClipNumber") as? Int ?: return null,
        movieClipRange = optJSONObject("movieClipRange")?.toBridgeIntegerRangeOrNull(999) ?: return null,
        movieUserDefined = opt("movieUserDefined") as? String ?: return null,
    )
    return result.takeIf { state ->
        CameraFileNamingField.entries.all { field -> state.accepts(field, state.fileNamingValue(field)) }
    }
}

private fun CameraFileNaming.fileNamingValue(field: CameraFileNamingField): String = when (field) {
    CameraFileNamingField.STILL_FILENAME_MODE -> stillFilenameMode
    CameraFileNamingField.STILL_USER_SETTING_1 -> stillUserSetting1
    CameraFileNamingField.STILL_USER_SETTING_2 -> stillUserSetting2
    CameraFileNamingField.MOVIE_INDEX -> movieIndex
    CameraFileNamingField.MOVIE_REEL_NUMBER -> movieReelNumber.toString()
    CameraFileNamingField.MOVIE_CLIP_NUMBER -> movieClipNumber.toString()
    CameraFileNamingField.MOVIE_USER_DEFINED -> movieUserDefined
}

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

private fun JSONArray?.liveViewMagnifications(): List<LiveViewMagnification> {
    if (this == null || length() !in 2..LiveViewMagnification.entries.size) return emptyList()
    val values = (0 until length()).map { index ->
        val raw = opt(index) as? Int ?: return emptyList()
        LiveViewMagnification.entries.firstOrNull { it.value == raw } ?: return emptyList()
    }
    return values.takeIf { it.toSet().size == it.size && LiveViewMagnification.X1 in it }.orEmpty()
}

private fun ByteArray.isCompleteJpeg(): Boolean =
    size >= 4 &&
        (this[0].toInt() and 0xFF) == 0xFF &&
        (this[1].toInt() and 0xFF) == 0xD8 &&
        (this[lastIndex - 1].toInt() and 0xFF) == 0xFF &&
        (this[lastIndex].toInt() and 0xFF) == 0xD9
