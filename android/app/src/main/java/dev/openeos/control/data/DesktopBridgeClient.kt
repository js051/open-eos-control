package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
    override val statusCode: Int? = null,
    connectionReason: ConnectionFailureReason? = null,
) : IllegalStateException(message), ConnectionHttpFailure {
    val isRecordingCommandUnconfirmed: Boolean
        get() = statusCode == 502 && code == "RECORDING_COMMAND_UNCONFIRMED" &&
            feature == "VIDEO_RECORDING" && engine == "ccapi"

    // Optional camera evidence is valid only for this exact Bridge discovery contract.
    // Keep the original exception, code and actual HTTP status for every caller.
    val connectionReason: ConnectionFailureReason? = connectionReason?.takeIf {
        statusCode == 502 && code == "CCAPI_DISCOVERY_FAILED" && engine == "ccapi" &&
            it in setOf(
                ConnectionFailureReason.AUTHENTICATION_REJECTED,
                ConnectionFailureReason.TLS_ERROR,
                ConnectionFailureReason.HOST_NOT_FOUND,
                ConnectionFailureReason.TIMEOUT,
                ConnectionFailureReason.UNREACHABLE,
                ConnectionFailureReason.HTTP_ERROR,
                ConnectionFailureReason.DISCOVERY_FAILED,
                ConnectionFailureReason.ENDPOINT_NOT_FOUND,
            )
    }
}

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
    // Capture configuration only: no redirect, authentication replay, HTTP cache or retry.
    private val captureConfigurationHttpClient = this.httpClient.newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE).cache(null)
        .apply { if (this@DesktopBridgeClient.httpClient.callTimeoutMillis == 0 ||
            this@DesktopBridgeClient.httpClient.callTimeoutMillis > 15_000) callTimeout(15, TimeUnit.SECONDS) }
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code in setOf(408, 421, 503)) {
                val code = response.code
                // Gateway failures may hide a camera write. Only the lifecycle rejection allowlist is definite.
                if (code in setOf(400, 401, 403, 404, 405, 409, 410, 422, 429))
                    chain.request().tag(RecordingRequestEvidence::class.java)?.rejected = true
                response.close()
                throw IOException("Single-attempt request rejected (HTTP $code).")
            }
            response
        }.build()
    private val mutationHttpClient = this.httpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val eventHttpClient = this.httpClient.newBuilder()
        .readTimeout(EVENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(EVENT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val activeEventCall = AtomicReference<Call?>(null)
    private val listingHttpClient = this.httpClient.newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private class MediaListingOwner(val session: BridgeShutterReleaseSession, val call: Call)
    private val activeMediaListing = AtomicReference<MediaListingOwner?>(null)
    @Volatile private var closingMediaSession: BridgeShutterReleaseSession? = null
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private var sessionId: String? = null
    @Volatile var connectionRetirementUnconfirmed: Boolean = false
        private set
    @Volatile private var shutterSession: BridgeShutterReleaseSession? = null
    @Volatile private var heldAutofocusSession: BridgeHeldAutofocusSession? = null
    private class MaintenanceBridgeOwnerContext(val owner: BridgeShutterReleaseSession) :
        kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<MaintenanceBridgeOwnerContext>
    }
    private class LiveViewStopOwner(
        val session: BridgeShutterReleaseSession,
        val stopUrl: HttpUrl,
        var confirmedStart: Boolean = false,
        val maintenanceOwned: Boolean = false,
    )
    @Volatile private var liveViewStopOwner: LiveViewStopOwner? = null
    @Volatile private var liveViewRevision = 0L
    private val liveViewStateLock = Any()
    val liveViewStopRequired: Boolean
        get() = liveViewStopOwner?.let { it.session === shutterSession && !it.session.closed } == true
    val maintenanceStopOwned: Boolean
        get() = liveViewStopRequired && liveViewStopOwner?.maintenanceOwned == true
    var maintenanceSessionOwned: Boolean = false
        private set
    private var sessionEngine: String? = null
    private var sessionCameraModel: String? = null
    private var eventPollingSupported = false
    private var shutterAutofocusSupported = false
    private var heldAutofocusSupported = false
    private var focusInfoSupported = false
    private val focusInfoReadMutex = Mutex()
    private var liveViewMagnifications: List<LiveViewMagnification> = emptyList()
    private val observedFeatures = mutableSetOf(CameraFeature.DESKTOP_BRIDGE)

    fun observedFeatureSnapshot(): Set<CameraFeature> = observedFeatures.toSet()

    suspend fun discoverCameras(): List<DesktopBridgeCamera> {
        validateService()
        return parseCameras(getJson(endpoint("v1", "cameras"), cameraDiscovery = true))
    }

    suspend fun initialize() {
        if (connectionRetirementUnconfirmed) throw ConnectionRetirementBlockedException()
        check(sessionId == null) { "Desktop bridge session is already initialized." }
        observedFeatures.clear()
        observedFeatures.add(CameraFeature.DESKTOP_BRIDGE)
        eventPollingSupported = false
        shutterAutofocusSupported = false
        heldAutofocusSupported = false
        focusInfoSupported = false
        liveViewMagnifications = emptyList()
        sessionCameraModel = null
        sessionEngine = null
        lastRecordingDisplayStatus = null
        validateService()
        val payload = JSONObject().put("engine", cameraEngine ?: "auto")
        cameraId?.takeIf(String::isNotBlank)?.let { payload.put("cameraId", it) }
        profileHint?.takeIf(String::isNotBlank)?.let { payload.put("profileHint", it) }
        var creationDispatched = false
        try {
            acquireConnectionResource(acquire = {
                creationDispatched = true
                postJson(endpoint("v1", "session"), payload, cameraDiscovery = true, connectionSetup = true)
            }, retain = { created ->
                sessionId = bridgeCreatedSessionId(created.opt("id"))
                    ?: error("Desktop bridge did not return a valid session ID.")
                val session = BridgeShutterReleaseSession(requireNotNull(sessionId))
                shutterSession = session
                heldAutofocusSession = BridgeHeldAutofocusSession(command = { ownerId, start ->
                    focusCommand(session, ownerId, start)
                }, onTransition = session::invalidateObservations)
                sessionEngine = mediaDeleteBridgeEngine(created.opt("engine"), created.optJSONObject("camera")?.opt("engine"))
                sessionCameraModel = created.optJSONObject("camera")
                    ?.optNullableString("model")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            })
        } catch (exception: Exception) {
            // A known HTTP rejection did not acquire an owner. A lost/malformed creation
            // response can hide one, so retain uncertainty without replaying POST.
            if (creationDispatched && sessionId == null &&
                !(exception is DesktopBridgeException && bridgeSessionCreationRejected(
                    exception.statusCode, exception.code, exception.engine))) {
                connectionRetirementUnconfirmed = true
                if (exception is ShutterReleaseException || exception is AutofocusReleaseException) throw exception
                throw ConnectionRetirementUnconfirmedException(exception)
            }
            throw exception
        }
    }

    suspend fun retireMaintenanceLocally() { shutterSession?.let { retireMaintenanceOwnerLocally(it) } }

    private fun retireMaintenanceOwnerLocally(owner: BridgeShutterReleaseSession) {
        owner.close()
        if (shutterSession !== owner) return
        activeEventCall.getAndSet(null)?.cancel()
        activeMediaListing.getAndSet(null)?.call?.cancel()
        // A replacement session must never be retired by late cleanup of an original owner.
        shutterSession = null
        heldAutofocusSession = null
        sessionId = null
    }

    suspend fun closeMaintenanceSession(): Boolean {
        val owner = requireShutterSession()
        val focusOwner = heldAutofocusSession
        return maintenanceCloseOnce(liveViewStopRequired,
            releasePhysical = { maintenancePhysicalReleaseOnce(
                autofocus = { requireCurrentSession(owner); focusOwner?.close() },
                shutter = { requireCurrentSession(owner); if (owner.hasReleaseResponsibility) stopBulbExposure(owner) }) },
            stop = { maintenanceStopRecoveryScope {
                withContext(MaintenanceBridgeOwnerContext(owner)) { stopLiveViewOwned(owner) }
            } }, retireRemote = {
                maintenanceJson(Request.Builder().url(endpoint("v1", "session", owner.id, "maintenance", "control", "session"))
                    .delete().build(), owner, expected = 204, empty = true)
            }, retireLocal = { retireMaintenanceOwnerLocally(owner) })
    }

    suspend fun closeConnectionAttempt() = withContext(ConnectionAttemptCleanup) { close() }

    suspend fun close() {
        if (maintenanceSessionOwned) {
            check(!closeMaintenanceSession()) { "Live View Stop remains unconfirmed after Bridge session retirement." }
            return
        }
        val callerContext = currentCoroutineContext()
        withContext(NonCancellable) {
            val session = shutterSession ?: return@withContext
            closingMediaSession = session
            activeMediaListing.getAndSet(null)?.call?.cancel()
            // Disconnect drops this client. Still request server cleanup if its explicit focus
            // stop failed, and keep the failure visible instead of abandoning the remote session.
            val focusFailure = runCatching { heldAutofocusSession?.close() }.exceptionOrNull()
            session.mutex.withLock {
                if (session.closed) return@withLock
                try {
                    stopEventPolling()
                    val deleteRequest = Request.Builder().url(endpoint("v1", "session", session.id)).delete().build()
                    if (focusFailure != null || session.hasReleaseResponsibility) {
                        // Unresolved release still needs a remote cleanup attempt after caller
                        // cancellation. This bounded app cleanup policy is not a camera ACK.
                        requestOk(deleteRequest, recoveryCleanup = true)
                    } else {
                        // Ordinary DELETE remains cancellable throughout response-body reads.
                        withContext(callerContext) { requestOk(deleteRequest) }
                    }
                } catch (exception: Exception) {
                    focusFailure?.let(exception::addSuppressed)
                    throw exception
                } finally {
                    session.close()
                    if (shutterSession === session) {
                        if (liveViewStopOwner?.session === session) liveViewStopOwner = null
                        shutterSession = null
                        heldAutofocusSession = null
                        sessionId = null
                        sessionCameraModel = null
                        sessionEngine = null
                        eventPollingSupported = false
                        shutterAutofocusSupported = false
                        heldAutofocusSupported = false
                        focusInfoSupported = false
                        liveViewMagnifications = emptyList()
                    }
                }
            }
            if (focusFailure != null) throw focusFailure
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
            bridgeEngine = sessionEngine,
            bridgeEndpoint = mediaDeleteEndpoint(rootUrl.toString()),
        ).also { info ->
            sessionCameraModel = info.model
            observedFeatures.add(CameraFeature.CAMERA_IDENTITY)
        }
    }

    suspend fun status(): CameraStatus {
        val session = requireShutterSession()
        while (true) {
            requireCurrentSession(session)
            val revision = session.revision
            val liveRevision = liveViewRevision
            val focusSession = heldAutofocusSession
            val focusRevision = focusSession?.revision
            val status = readBridgeStatus(session)
            requireCurrentSession(session)
            val observed = synchronized(liveViewStateLock) {
                requireCurrentSession(session)
                if (liveRevision != liveViewRevision) return@synchronized false
                if (heldAutofocusSession !== focusSession || focusSession?.revision != focusRevision) return@synchronized false
                if (status.liveViewStopUnconfirmed == true) {
                    // Fresh uncertainty also revokes a known Start's legacy-stop exemption.
                    val owner = liveViewStopOwner?.takeIf { it.session === session }
                        ?: LiveViewStopOwner(session, endpoint("v1", "session", session.id, "liveview", "stop"))
                    owner.confirmedStart = false
                    liveViewStopOwner = owner
                    liveViewRevision += 1
                }
                observeBridgeStatus(session, status, revision)
            }
            if (observed) {
                val focus = heldAutofocusSession
                return session.decorate(if (focus?.stopUnconfirmed == true) {
                    status.copy(heldAutofocusActive = null, shutterReleaseUnconfirmed = true)
                } else status)
            }
            // A stop crossed this read. Re-read status, never a command. Observing this read's
            // own warning must not trigger another request that could hide valid safety data.
        }
    }

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
        val session = requireShutterSession()
        val body = getJson(sessionEndpoint("capabilities"))
        requireCurrentSession(session)
        val settings = body.optJSONArray("settings").objects().mapNotNull { setting ->
            val key = setting.optString("key").trim()
            val values = setting.optJSONArray("values").strings()
            val inputKind = when (setting.optString("inputKind", "choice").lowercase()) {
                "choice" -> CameraSettingInputKind.CHOICE
                "text" -> CameraSettingInputKind.TEXT
                else -> return@mapNotNull null
            }
            val maxLength = setting.optInt("maxLength")
                .takeIf { setting.has("maxLength") && it in 1..CanonEosPtp.MAX_TEXT_METADATA_BYTES }
            val current = setting.optString("value")
            val valid = when (inputKind) {
                CameraSettingInputKind.CHOICE -> values.isNotEmpty()
                CameraSettingInputKind.TEXT ->
                    values.isEmpty() && maxLength != null &&
                        current.length <= maxLength && current.all { it.code in 0x20..0x7E }
            }
            if (key.isBlank() || !valid) {
                null
            } else {
                CameraSettingControl(
                    key = key,
                    label = setting.optString("label", key),
                    value = current,
                    values = values,
                    inputKind = inputKind,
                    maxLength = maxLength,
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
        shutterAutofocusSupported = (body.opt("shutterAutofocusSupported") as? Boolean) == true &&
            CameraFeature.STILL_CAPTURE in supported
        heldAutofocusSupported = body.opt("heldAutofocusSupported") == true &&
            CameraFeature.AUTOFOCUS in supported
        focusInfoSupported = body.opt("focusInfoSupported") == true &&
            CameraFeature.LIVE_VIEW in supported && sources.isNotEmpty()
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
            shutterAutofocusSupported = shutterAutofocusSupported,
            heldAutofocusSupported = heldAutofocusSupported,
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
                focusInfoSupported = focusInfoSupported,
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

    fun shootingSettingAccess(request: ShootingSettingRequest): ShootingSettingAccess {
        val owner = requireShutterSession()
        val engine = sessionEngine
        return ShootingSettingAccess("Desktop bridge", write = { frozen, evidence ->
            require(frozen == request); writeShootingSetting(frozen, evidence, owner, engine)
        }, read = { frozen -> require(frozen == request); readShootingSetting(frozen, owner, engine) })
    }

    suspend fun writeShootingSetting(request: ShootingSettingRequest, evidence: ShootingSettingEvidence) =
        writeShootingSetting(request, evidence, requireShutterSession(), sessionEngine)

    private suspend fun writeShootingSetting(request: ShootingSettingRequest, evidence: ShootingSettingEvidence,
        owner: BridgeShutterReleaseSession, engine: String?) {
        require(evidence.request == request)
        requireCurrentSession(owner)
        val url = endpoint("v1", "session", owner.id, "settings", request.key, "outcome")
        val httpRequest = Request.Builder().url(url)
            .post(JSONObject().put("value", request.requestedValue).toString().toRequestBody(jsonMediaType)).build()
        shootingSettingJson(httpRequest, owner, evidence) { body ->
            require(applyShootingSettingBridgeOutcome(evidence, engine, 200, body.opt("version"), body.opt("engine"),
                body.opt("key"), body.opt("requestedValue"), body.opt("writeEvidence"), body.opt("readbackEvidence"),
                body.optJSONObject("setting")?.toStrictShootingSetting())) {
                "Bridge did not return a valid same-request setting outcome."
            }
        }
    }

    suspend fun readShootingSetting(request: ShootingSettingRequest): ShootingSettingObservation =
        readShootingSetting(request, requireShutterSession(), sessionEngine)

    private suspend fun readShootingSetting(request: ShootingSettingRequest, owner: BridgeShutterReleaseSession,
        engine: String?): ShootingSettingObservation {
        requireCurrentSession(owner)
        var observed: ShootingSettingObservation? = null
        val url = endpoint("v1", "session", owner.id, "settings", request.key)
        shootingSettingJson(Request.Builder().url(url).header("Cache-Control", "no-cache, no-store").get().build(), owner) { body ->
            require(engine != null && body.opt("version") is Int && body.optInt("version") == 1 && body.opt("engine") == engine)
            val setting = body.optJSONObject("setting")?.toStrictShootingSetting()
            require(setting != null && setting.key == request.key && acceptsShootingSettingObservation(setting))
            observed = ShootingSettingObservation(request.key, setting.value)
        }
        return requireNotNull(observed)
    }

    /** Separate from generic status parser: complete strict outcome is never mistaken for status. */
    private suspend fun shootingSettingJson(request: Request, owner: BridgeShutterReleaseSession,
        evidence: ShootingSettingEvidence? = null, accept: (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        val call = newBridgeCall(request, true)
        val cancellationWatcher = if (evidence == null) launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        } else null
        try {
            currentCoroutineContext().ensureActive()
            requireCurrentSession(owner)
            evidence?.beforeDispatch()
            call.execute().use { response ->
                check(response.code == 200) { "The Bridge setting operation did not return HTTP 200." }
                requireShootingSettingHttpResponse(response.code, response.headers("Content-Range"),
                    response.headers("Content-Length"), response.headers("Transfer-Encoding"))
                val body = response.body ?: error("The Bridge setting response is empty.")
                val text = body.byteStream().use { readCaptureConfigurationJsonText(it, body.contentLength()) }
                StrictMediaJson(text).validate()
                accept(JSONObject(text))
                currentCoroutineContext().ensureActive()
                requireCurrentSession(owner)
            }
        } finally { cancellationWatcher?.cancel() }
    }

    suspend fun createDirectory(name: String, evidence: CaptureConfigurationEvidence = CaptureConfigurationEvidence(
        CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, name),
    )): String = captureConfigurationOperation(evidence) {
        require(evidence.request == CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, name))
        require(Regex("^(?:[A-Z0-9_]{5})?$").matches(name)) {
            "Directory name must be empty or exactly five uppercase letters, numbers, or underscores."
        }
        // Freeze both routes and the exact session object before dispatching the write.
        val owner = requireShutterSession()
        val target = endpoint("v1", "session", owner.id, "directories")
        val readTarget = endpoint("v1", "session", owner.id, "capture-configuration", CaptureConfigurationOperation.CREATE_DIRECTORY.wireName)
        val created = postJson(target, JSONObject().put("name", name), captureEvidence = evidence, captureOwner = owner,
            captureResponse = { response ->
                val suffix = response.opt("name") as? String
                require(suffix != null && Regex("^[A-Z0-9_]{5}$").matches(suffix)) {
                    "Desktop Bridge returned an invalid created directory name."
                }
                evidence.acknowledge(suffix)
            }).getString("name")
        observedFeatures.add(CameraFeature.DIRECTORY_CONTROL)
        captureConfigurationPostAckReadback(evidence) {
            readCaptureConfiguration(CaptureConfigurationOperation.CREATE_DIRECTORY, readTarget, owner)
        }
        created
    }

    suspend fun setFileNaming(field: CameraFileNamingField, value: String,
        evidence: CaptureConfigurationEvidence = CaptureConfigurationEvidence(
            CaptureConfigurationRequest(CaptureConfigurationOperation.SET_FILE_NAMING, value, field),
        ),
    ): CameraFileNaming = captureConfigurationOperation(evidence) {
        require(evidence.request == CaptureConfigurationRequest(CaptureConfigurationOperation.SET_FILE_NAMING, value, field))
        // Freeze the exact session route before the capability await.
        val owner = requireShutterSession()
        val target = endpoint("v1", "session", owner.id, "file-naming", field.wireName)
        val current = capabilities().fileNaming
            ?: error("Desktop Bridge did not advertise Canon file-naming control.")
        require(current.accepts(field, value)) {
            "Value '$value' is not valid for Canon file-naming field ${field.wireName}."
        }
        val updated = putJson(target, JSONObject().put("value", value), captureEvidence = evidence, captureOwner = owner,
            captureResponse = { response ->
                val state = response.toBridgeFileNamingOrNull()
                    ?: error("Desktop Bridge returned an invalid file-naming state.")
                check(state.accepts(field, value) && state.fileNamingValue(field) == value) {
                    "Desktop Bridge did not return the requested file-naming value."
                }
                evidence.acknowledge(value)
                evidence.observe(CaptureConfigurationReadback(fileNaming = state))
            }).toBridgeFileNamingOrNull() ?: error("Desktop Bridge returned an invalid file-naming state.")
        observedFeatures.add(CameraFeature.FILE_NAMING_CONTROL)
        updated
    }

    suspend fun readCaptureConfiguration(operation: CaptureConfigurationOperation): CaptureConfigurationReadback {
        val owner = requireShutterSession()
        val target = endpoint("v1", "session", owner.id, "capture-configuration", operation.wireName)
        return readCaptureConfiguration(operation, target, owner)
    }

    private suspend fun readCaptureConfiguration(operation: CaptureConfigurationOperation, target: HttpUrl,
        owner: BridgeShutterReleaseSession): CaptureConfigurationReadback {
        return kotlinx.coroutines.withTimeout(15_000L) {
            currentCoroutineContext().ensureActive()
            requireCurrentSession(owner)
            val body = requestJson(Request.Builder().url(target).header("Cache-Control", "no-cache, no-store").get().build(),
                observeStatus = false, strictMediaJsonLimit = 65_536, captureRecovery = true, captureOwner = owner)
            check(body.opt("operation") == operation.wireName) { "Mismatched capture configuration readback." }
            when (operation) {
                CaptureConfigurationOperation.CREATE_DIRECTORY -> {
                    val selection = body.optJSONObject("directorySelection")
                    val values = selection?.optJSONArray("ability")
                    val selected = validCaptureDirectorySelection(selection?.opt("value"),
                        if (values == null) emptyList() else (0 until values.length()).map { values.opt(it) })
                        ?: error("Invalid directory selection readback.")
                    CaptureConfigurationReadback(directorySelection = selected,
                        directoryValues = (0 until requireNotNull(values).length()).map { values.getString(it) })
                }
                CaptureConfigurationOperation.SET_FILE_NAMING -> CaptureConfigurationReadback(
                    fileNaming = body.optJSONObject("fileNaming")?.toBridgeFileNamingOrNull()
                        ?: error("Complete file-naming readback unavailable."),
                )
            }
        }
    }

    fun maintenanceAccess(request: MaintenanceRequest, liveView: LiveViewRequest?): MaintenanceAccess {
        val owner = requireShutterSession()
        val engine = sessionEngine
        val root = endpoint("v1", "session", owner.id, "maintenance", request.action.wireName)
        val outcome = root.newBuilder().addPathSegment("outcome").build()
        if (request.needsStop) maintenanceSessionOwned = true
        return MaintenanceAccess("Desktop bridge", eventStopRequired = eventPollingSupported,
            liveViewStopRequired = liveViewStopRequired, stopEvents = {
            requireCurrentSession(owner)
            activeEventCall.getAndSet(null)?.cancel()
            // The legacy stop method swallows errors; maintenance needs a real phase result.
            maintenanceJson(Request.Builder().url(endpoint("v1", "session", owner.id, "maintenance", "control", "events")).delete().build(), owner, expected = 204, empty = true)
        }, stopLiveView = {
            requireCurrentSession(owner)
            withContext(MaintenanceBridgeOwnerContext(owner)) { stopLiveViewOwned(owner) }
        }, dispatch = { frozen, evidence ->
            require(frozen == request); requireCurrentSession(owner)
            val httpRequest = Request.Builder().url(outcome).post(JSONObject().put("autoPowerOff", request.autoPowerOff)
                .toString().toRequestBody(jsonMediaType)).build()
            maintenanceJson(httpRequest, owner, evidence) { body ->
                check(applyMaintenanceBridgeOutcome(evidence, engine, 200, body.toMaintenanceMap())) {
                    "Bridge maintenance outcome did not match the frozen request."
                }
            }
        }, read = { frozen ->
            require(frozen == request); requireCurrentSession(owner)
            var result: MaintenanceReadback? = null
            maintenanceJson(Request.Builder().url(root).header("Cache-Control", "no-cache, no-store").get().build(), owner) { body ->
                result = maintenanceBridgeReadback(engine, request, body.toMaintenanceMap())
            }
            requireNotNull(result) { "Bridge maintenance observation is unavailable." }
        }, restore = {
            requireCurrentSession(owner)
            if (liveView != null) withContext(MaintenanceBridgeOwnerContext(owner)) { startLiveViewOwned(liveView, owner) }
        })
    }

    private fun JSONObject.toMaintenanceMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        when (val value = opt(key)) { JSONObject.NULL -> null; is JSONObject -> value.toMaintenanceMap(); else -> value }
    }

    private suspend fun maintenanceJson(request: Request, owner: BridgeShutterReleaseSession,
        evidence: MaintenanceEvidence? = null, expected: Int = 200, empty: Boolean = false, accept: ((JSONObject) -> Unit)? = null) = withContext(Dispatchers.IO) {
        currentCoroutineContext()[MaintenanceIoContext]?.requireActive?.invoke()
        val call = newBridgeCall(request, true)
        val watchers = maintenanceCancellationWatchers { call.cancel() }
        try {
            currentCoroutineContext().ensureActive(); requireCurrentSession(owner); evidence?.beforeDispatch()
            call.execute().use { response ->
                requireShootingSettingHttpResponse(response.code, response.headers("Content-Range"),
                    response.headers("Content-Length"), response.headers("Transfer-Encoding"))
                check(response.code == expected)
                val text = response.body?.let { body -> body.byteStream().use { readShootingSettingResponseText(it, body.contentLength()) } }.orEmpty()
                if (empty) requireMaintenanceEmptyResponse(response.code, response.headers("Content-Length"), response.headers("Transfer-Encoding"), text)
                if (accept != null) accept(JSONObject(text))
                // A cancelled/retired attempt retains a complete original response in its history only.
                currentCoroutineContext().ensureActive(); requireCurrentSession(owner)
            }
        } finally { watchers.forEach { it.cancel() } }
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

    suspend fun captureStill(autofocus: Boolean = true): CameraStatus {
        check(autofocus || shutterAutofocusSupported) {
            "This bridge session does not advertise capture without autofocus."
        }
        return try {
            parseStatus(postJson(sessionEndpoint("capture", "still"), JSONObject().put("af", autofocus)))
                .also { observedFeatures.add(CameraFeature.STILL_CAPTURE) }
        } catch (exception: DesktopBridgeException) {
            if (exception.code != "CAPTURE_STATUS_READBACK_FAILED") throw exception
            throw CaptureStatusReadbackException(exception)
        }
    }

    suspend fun startBulbExposure(): CameraStatus {
        val session = requireShutterSession()
        return session.start(read = ::status, press = {
            parseBulbStatus(postJson(endpoint("v1", "session", session.id, "bulb", "start"), JSONObject(), shutterStart = true))
        }, admit = {
            check(heldAutofocusSession?.hasReleaseResponsibility != true) { "Stop held autofocus before starting Bulb." }
        })
    }

    suspend fun stopBulbExposure(): CameraStatus = stopBulbExposure(requireShutterSession())

    private suspend fun stopBulbExposure(session: BridgeShutterReleaseSession): CameraStatus {
        requireCurrentSession(session)
        val confirmedStart = session.hasConfirmedStart
        return session.stop(
            release = { parseBulbStatus(postJson(endpoint("v1", "session", session.id, "bulb", "stop"), JSONObject())) },
            read = { readBridgeStatus(session, recoveryRead = true) },
        ).also { if (confirmedStart) observedFeatures.add(CameraFeature.BULB_EXPOSURE) }
    }

    suspend fun retryShutterRelease() {
        val session = requireShutterSession()
        heldAutofocusSession?.retryStop()
        if (session.hasReleaseResponsibility) stopBulbExposure(session)
    }

    suspend fun holdAutofocus(whileHeld: suspend () -> Unit) {
        check(heldAutofocusSupported) { "Desktop Bridge did not advertise held autofocus." }
        val session = requireShutterSession()
        if (session.unconfirmed || session.hasReleaseResponsibility) {
            throw ShutterReleaseException(IllegalStateException("Release the shutter before held autofocus."))
        }
        requireNotNull(heldAutofocusSession).hold(whileHeld)
    }

    suspend fun retryAutofocusStop() {
        requireShutterSession()
        heldAutofocusSession?.retryStop()
    }

    private suspend fun focusCommand(session: BridgeShutterReleaseSession, ownerId: String, start: Boolean) {
        requireCurrentSession(session)
        val response = requestJson(
            Request.Builder()
                .url(endpoint("v1", "session", session.id, "focus", "auto", if (start) "start" else "stop"))
                .tag(BridgeFocusStart::class.java, if (start) BridgeFocusStart else null)
                .post(JSONObject().put("ownerId", ownerId).toString().toRequestBody(jsonMediaType))
                .build(),
            observeStatus = false,
            recoveryRead = true,
            maxResponseBytes = MAX_FOCUS_INFO_BYTES,
        )
        requireCurrentSession(session)
        check(response.opt("ownerId") == ownerId && response.opt("active") == start &&
            response.opt("stopUnconfirmed") == false) {
            "Desktop Bridge did not confirm ${if (start) "start" else "stop"} for this focus owner."
        }
        if (start) observedFeatures.add(CameraFeature.AUTOFOCUS)
    }

    suspend fun autofocus(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("focus", "auto"), JSONObject())
    ).also { observedFeatures.add(CameraFeature.AUTOFOCUS) }

    suspend fun halfPressShutter(): CameraStatus = parseStatus(
        postJson(sessionEndpoint("shutter", "half-press"), JSONObject())
    ).also { observedFeatures.add(CameraFeature.SHUTTER_HALF_PRESS) }

    private val recordingSequence = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var lastRecordingDisplayStatus: CameraStatus? = null

    suspend fun recordingObservation(): RecordingObservation? = recordingIoScope {
        val owner = requireShutterSession()
        val engine = requireNotNull(sessionEngine)
        val body = recordingLifecycleJson(Request.Builder()
            .url(endpoint("v1", "session", owner.id, "recording", "observation"))
            .header("Cache-Control", "no-cache, no-store").get().build(), owner)
        val observation = recordingWireObservation(body, engine)
            ?: error("Bridge recording observation schema is invalid.")
        observation.recording?.let { RecordingObservation(it, recordingSequence.incrementAndGet(), RecordingObservationSource.BRIDGE_STATUS) }
    }

    /** Additive lifecycle API. A legacy/missing endpoint never falls back to another POST. */
    suspend fun recordingLifecycleCommand(command: RecordingCommand): CameraStatus = recordingIoScope {
        val owner = requireShutterSession()
        val engine = requireNotNull(sessionEngine)
        val evidence = currentCoroutineContext()[RecordingRequestEvidence] ?: RecordingRequestEvidence(command)
        evidence.observationFloor = recordingSequence.get()
        val request = Request.Builder().url(endpoint("v1", "session", owner.id, "recording",
            command.name.lowercase(), "outcome")).post(JSONObject().toString().toRequestBody(jsonMediaType)).build()
        recordingLifecycleJson(request, owner, evidence) { body ->
            val outcome = recordingWireOutcome(body, engine, command)
                ?: error("Bridge recording outcome schema is invalid.")
            when (outcome.response) {
                RecordingResponseEvidence.RESPONSE_RECEIVED -> evidence.acknowledge()
                RecordingResponseEvidence.REJECTED -> evidence.rejected = true
                RecordingResponseEvidence.NOT_REQUIRED -> evidence.remoteNoOp()
                else -> Unit
            }
            outcome.observation.recording?.let { evidence.observe(RecordingObservation(it,
                recordingSequence.incrementAndGet(), RecordingObservationSource.BRIDGE_STATUS)) }
        }
        requireCurrentSession(owner)
        if (evidence.rejected) error("Bridge rejected the recording command.")
        if (evidence.responseEvidence() == RecordingResponseEvidence.UNKNOWN)
            throw RecordingCommandUnconfirmedException(command, IOException("Bridge did not confirm the recording command."))
        val display = lastRecordingDisplayStatus ?: CameraStatus(connected = true, batteryLevel = null,
            batteryStatus = "unknown", recording = null, mode = "unknown", mediaAvailable = null,
            remainingMinutes = null, exposure = ExposureState("-", "-", "-", "-"))
        display.copy(recording = evidence.observation?.recording ?: display.recording,
            recordingObservation = evidence.observation)
    }

    private suspend fun recordingLifecycleJson(request: Request, owner: BridgeShutterReleaseSession,
        evidence: RecordingRequestEvidence? = null, responseReceived: (Map<String, Any?>) -> Unit = {}): Map<String, Any?> = withContext(Dispatchers.IO) {
        requireCurrentSession(owner)
        val call = newBridgeCall(request.newBuilder().tag(RecordingRequestEvidence::class.java, evidence).build(), true)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try {
            currentCoroutineContext().ensureActive(); requireCurrentSession(owner); evidence?.beginRequest()
            call.execute().use { response ->
                if (response.code in setOf(400, 401, 403, 404, 405, 409, 410, 422, 429)) evidence?.rejected = true
                check(response.code == 200) { "Recording lifecycle endpoint returned HTTP ${response.code}; no fallback was sent." }
                requireShootingSettingHttpResponse(response.code, response.headers("Content-Range"),
                    response.headers("Content-Length"), response.headers("Transfer-Encoding"))
                val body = requireNotNull(response.body).let { body ->
                    body.byteStream().use { readShootingSettingResponseText(it, body.contentLength()) }
                }
                val parsed = recordingJsonMap(JSONObject(body))
                responseReceived(parsed) // Commit complete command receipt before cancellation/session checks.
                currentCoroutineContext().ensureActive(); requireCurrentSession(owner)
                parsed
            }
        } finally { watcher.cancel() }
    }

    private fun recordingJsonMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key ->
        when (val value = json.get(key)) {
            JSONObject.NULL -> null
            is JSONObject -> recordingJsonMap(value)
            else -> value
        }
    }

    suspend fun startRecording(): CameraStatus = recordingCommand(RecordingCommand.START)

    suspend fun stopRecording(): CameraStatus = recordingCommand(RecordingCommand.STOP)

    private suspend fun recordingCommand(command: RecordingCommand): CameraStatus {
        // Resolve the current session before entering the actual REC request boundary.
        val url = sessionEndpoint("recording", if (command == RecordingCommand.START) "start" else "stop")
        val evidence = currentCoroutineContext()[RecordingRequestEvidence] ?: RecordingRequestEvidence(command)
        return try {
            parseStatus(postJson(url, JSONObject(), recordingEvidence = evidence))
                .also { observedFeatures.add(CameraFeature.VIDEO_RECORDING) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: DesktopBridgeException) {
            when {
                exception.statusCode == 502 && exception.code == "RECORDING_STATUS_READBACK_FAILED" ->
                    throw RecordingStatusReadbackException(command, exception)
                exception.isRecordingCommandUnconfirmed -> throw RecordingCommandUnconfirmedException(command, exception)
                else -> throw exception
            }
        } catch (exception: Exception) {
            if (evidence.unresolved && (exception is IOException || exception is org.json.JSONException)) {
                throw RecordingCommandUnconfirmedException(command, exception)
            }
            throw exception
        }
    }

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

    suspend fun startLiveView(request: LiveViewRequest) = startLiveViewOwned(request, requireShutterSession())

    private suspend fun startLiveViewOwned(request: LiveViewRequest, session: BridgeShutterReleaseSession) {
        session.mutex.withLock {
            requireCurrentSession(session)
            currentCoroutineContext().ensureActive()
            val strict = currentCoroutineContext()[MaintenanceBridgeOwnerContext] != null
            val owner = LiveViewStopOwner(session,
                if (strict) endpoint("v1", "session", session.id, "maintenance", "control", "liveview", "stop")
                else endpoint("v1", "session", session.id, "liveview", "stop"), maintenanceOwned = strict)
            // Record the original endpoint before the Start request can reach Bridge.
            val startRevision = synchronized(liveViewStateLock) {
                check(!liveViewStopRequired) { "Retry stopping Live View before starting again." }
                liveViewStopOwner = owner
                liveViewRevision += 1
                liveViewRevision
            }
            val response = postJson(
                if (strict) endpoint("v1", "session", session.id, "maintenance", "control", "liveview", "start")
                else endpoint("v1", "session", session.id, "liveview", "start"),
                JSONObject().put("fps", request.fps).put("size", request.size.name).put("source", request.source.name),
            )
            requireCurrentSession(session)
            check(response.opt("active") == true) { "Desktop Bridge did not confirm Live View start." }
            check(if (strict) response.opt("liveViewStopUnconfirmed") == false else response.opt("liveViewStopUnconfirmed") != true) {
                "Desktop Bridge did not confirm Live View started without a pending stop. Retry Stop before starting again."
            }
            synchronized(liveViewStateLock) {
                // A strict Start response cannot discharge a newer same-session warning.
                if (strict) check(liveViewStopOwner === owner && liveViewRevision == startRevision) {
                    "Maintenance Live View Start was superseded by newer Stop evidence."
                }
                // A delayed Start acknowledgement cannot undo a newer Stop warning.
                if (liveViewStopOwner === owner && liveViewRevision == startRevision) owner.confirmedStart = true
                liveViewRevision += 1
            }
            observedFeatures.add(CameraFeature.LIVE_VIEW)
        }
    }

    suspend fun stopLiveView() {
        val session = requireShutterSession()
        if (maintenanceStopOwned) maintenanceStopRecoveryScope { stopLiveViewOwned(session) }
        else withContext(NonCancellable) { stopLiveViewOwned(session) }
    }

    private suspend fun stopLiveViewOwned(session: BridgeShutterReleaseSession) {
        session.mutex.withLock {
            requireCurrentSession(session)
            val requestedStrict = currentCoroutineContext()[MaintenanceBridgeOwnerContext] != null
            val (owner, legacyStopAllowed, stopRevision) = synchronized(liveViewStateLock) {
                var current = liveViewStopOwner?.takeIf { it.session === session }
                    ?: LiveViewStopOwner(session, endpoint("v1", "session", session.id, "liveview", "stop"))
                if (requestedStrict && !current.maintenanceOwned) current = LiveViewStopOwner(session,
                    endpoint("v1", "session", session.id, "maintenance", "control", "liveview", "stop"),
                    confirmedStart = current.confirmedStart, maintenanceOwned = true)
                val legacy = current.confirmedStart && !current.maintenanceOwned
                current.confirmedStart = false
                liveViewStopOwner = current
                liveViewRevision += 1
                Triple(current, legacy, liveViewRevision)
            }
            val response = if (owner.maintenanceOwned) maintenanceStopRecoveryScope {
                withContext(MaintenanceBridgeOwnerContext(session)) { postJson(owner.stopUrl, JSONObject()) }
            } else postJson(owner.stopUrl, JSONObject())
            requireCurrentSession(session)
            synchronized(liveViewStateLock) {
                requireCurrentSession(session)
                if (owner.maintenanceOwned) check(liveViewStopOwner === owner && liveViewRevision == stopRevision) {
                    "Maintenance Live View Stop was superseded by newer Stop evidence."
                }
                val explicit = response.opt("liveViewStopUnconfirmed")
                val legacyStillValid = legacyStopAllowed && liveViewRevision == stopRevision
                check(response.opt("active") == false && (explicit == false ||
                    (legacyStillValid && (!response.has("liveViewStopUnconfirmed") || response.isNull("liveViewStopUnconfirmed"))))) {
                    "Live View stop was not confirmed. Retry Stop; update an older Bridge if it cannot confirm the stop."
                }
                if (liveViewStopOwner === owner) {
                    liveViewStopOwner = null
                    liveViewRevision += 1
                }
            }
        }
    }

    fun liveViewFrameUrl(cacheKey: Long): String = frameUrl(cacheKey).toString()

    suspend fun liveViewFocusInfo(): CameraFocusInfo? = focusInfoReadMutex.withLock { readLiveViewFocusInfo() }

    private suspend fun readLiveViewFocusInfo(): CameraFocusInfo? = withContext(Dispatchers.IO) {
        if (!focusInfoSupported) return@withContext null
        val session = requireShutterSession()
        val sessionRevision = session.revision
        val (owner, revision) = synchronized(liveViewStateLock) {
            val active = liveViewStopOwner?.takeIf { it.session === session && it.confirmedStart }
                ?: return@withContext null
            active to liveViewRevision
        }
        val request = Request.Builder().url(endpoint("v1", "session", session.id, "liveview", "focus-info"))
            .header("Cache-Control", "no-cache, no-store").get().build()
        val call = httpClient.newCall(request)
        val maximum = TimeUnit.SECONDS.toNanos(RECOVERY_STATUS_CALL_TIMEOUT_SECONDS)
        if (call.timeout().timeoutNanos() == 0L || call.timeout().timeoutNanos() > maximum) {
            call.timeout().timeout(maximum, TimeUnit.NANOSECONDS)
        }
        val cancelCall = AtomicBoolean(true)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (cancelCall.get()) call.cancel() }
        }
        try {
            call.execute().use { response ->
                val body = response.body?.readBoundedUtf8(MAX_FOCUS_INFO_BYTES, strictUtf8 = true)
                    ?: error("Desktop Bridge returned empty focus information.")
                currentCoroutineContext().ensureActive()
                requireCurrentSession(session)
                if (!response.isSuccessful) throw bridgeError(response.code, body, "Live View focus information", session, sessionRevision)
                check('\u0000' !in body) { "Desktop Bridge returned invalid focus information encoding." }
                val json = JSONTokener(body)
                val value = json.nextValue()
                check(json.nextClean() == '\u0000') { "Desktop Bridge returned trailing focus information data." }
                val parsed = if (value === JSONObject.NULL) null else parseBridgeFocusInfo(
                    value as? JSONObject ?: error("Desktop Bridge returned invalid focus information."),
                )
                synchronized(liveViewStateLock) {
                    if (!focusInfoSupported || liveViewStopOwner !== owner || !owner.confirmedStart ||
                        liveViewRevision != revision) null else parsed
                }
            }
        } catch (exception: Exception) {
            currentCoroutineContext().ensureActive()
            throw exception
        } finally {
            cancelCall.set(false)
            cancellationWatcher.cancel()
        }
    }

    suspend fun liveViewFrame(cacheKey: Long): LiveViewFrame = withContext(Dispatchers.IO) {
        val sourceUrl = liveViewFrameUrl(cacheKey)
        val request = Request.Builder()
            .url(sourceUrl)
            .get()
            .header("Accept", "image/jpeg")
            .header("Cache-Control", "no-cache")
            .build()
        val session = sessionForRequest(request)
        val revision = session?.revision
        httpClient.newCall(request).execute().use { response ->
            session?.let(::requireCurrentSession)
            val body = response.body ?: error("Desktop Bridge returned an empty Live View response.")
            if (!response.isSuccessful) throw bridgeError(response.code, body.string(), "Live View frame", session, revision)
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

    suspend fun listMedia(
        maximumItems: Int? = null,
        onProgress: (List<CameraMediaItem>) -> Unit = {},
    ): List<CameraMediaItem> = withContext(Dispatchers.IO) {
        require(maximumItems == null || maximumItems in 1..1000) {
            "maximumItems must be from 1 through 1000 when provided."
        }
        currentCoroutineContext().ensureActive()
        val session = requireShutterSession()
        requireCurrentSession(session)
        val endpoint = endpoint("v1", "session", session.id, "media").newBuilder().apply {
            maximumItems?.let { addQueryParameter("limit", it.toString()) }
        }.build()
        val request = Request.Builder().url(endpoint).get()
            .header("Accept", BridgeMediaListingDecoder.MEDIA_TYPE)
            .header("Cache-Control", "no-cache, no-store").build()
        val call = listingHttpClient.newCall(request)
        val owner = MediaListingOwner(session, call)
        if (closingMediaSession === session) throw CancellationException("Desktop Bridge session is closing.")
        activeMediaListing.getAndSet(owner)?.call?.cancel()
        val context = currentCoroutineContext()
        fun checkOwner() {
            context.ensureActive()
            requireCurrentSession(session)
            if (activeMediaListing.get() !== owner || closingMediaSession === session) {
                throw CancellationException("Listing owner changed or session is closing.")
            }
        }
        val cancelCall = AtomicBoolean(true)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                if (cancelCall.get()) {
                    activeMediaListing.compareAndSet(owner, null)
                    call.cancel()
                }
            }
        }
        var completed = false
        try {
            // Close or replacement can retire ownership between admission and installation.
            checkOwner()
            call.execute().use { response ->
                checkOwner()
                val body = response.body ?: error("Desktop Bridge returned an empty listing response.")
                if (!response.isSuccessful) {
                    val text = body.readBoundedUtf8(2_000)
                    checkOwner()
                    throw bridgeError(response.code, text, "media listing", session, session.revision)
                }
                val mediaType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                val items = if (response.code == 200 && mediaType == BridgeMediaListingDecoder.MEDIA_TYPE) {
                    val decoder = BridgeMediaListingDecoder(session.id, maximumItems)
                    body.byteStream().use { input ->
                        val bytes = ByteArray(8 * 1024)
                        while (true) {
                            checkOwner()
                            val count = input.read(bytes)
                            checkOwner()
                            if (count < 0) break
                            for (index in 0 until count) {
                                decoder.consume(bytes[index], ::parseMediaItem)?.let { snapshot ->
                                    checkOwner()
                                    onProgress(snapshot)
                                    checkOwner()
                                }
                            }
                        }
                    }
                    checkOwner()
                    decoder.finish()
                } else {
                    // Old Bridge replies are consumed from this one response, never retried.
                    check(mediaType == null || mediaType == "application/json") { "Unexpected listing content type." }
                    val text = body.readBoundedUtf8(BridgeMediaListingDecoder.MAX_SNAPSHOT_BYTES, strictUtf8 = true)
                    StrictMediaJson(text).validate()
                    val json = JSONObject(text)
                    checkOwner()
                    val raw = json.opt("items") as? JSONArray ?: error("Invalid media listing JSON.")
                    check(raw.length() <= BridgeMediaListingDecoder.MAX_ITEMS &&
                        (maximumItems == null || raw.length() <= maximumItems)) {
                        "Desktop Bridge listing exceeds the requested or supported item limit."
                    }
                    val identities = HashSet<String>()
                    val legacy = ArrayList<CameraMediaItem>(raw.length())
                    for (index in 0 until raw.length()) {
                        val value = raw.opt(index) as? JSONObject ?: error("Invalid media listing item.")
                        val item = parseMediaItem(value) ?: error("Invalid media listing item.")
                        check(identities.add(item.id)) { "Duplicate media listing identity." }
                        legacy.add(item)
                    }
                    // Publish only after every row passed. Dropping rows or truncating
                    // an over-limit reply must never become successful completion.
                    checkOwner()
                    onProgress(legacy)
                    checkOwner()
                    legacy
                }
                checkOwner()
                observedFeatures.add(CameraFeature.MEDIA_BROWSER)
                completed = true
                items
            }
        } catch (exception: Exception) {
            checkOwner()
            throw exception
        } finally {
            if (!completed) call.cancel()
            cancelCall.set(false)
            cancellationWatcher.cancel()
            activeMediaListing.compareAndSet(owner, null)
        }
    }

    suspend fun mediaInfo(item: CameraMediaItem): CameraMediaItem =
        parseMediaItem(getJson(sessionEndpoint("media", item.id, "info"), strictMediaJsonLimit = MAX_MEDIA_METADATA_JSON_BYTES))
            ?.also { updated ->
                check(updated.id == item.id) {
                    "Desktop Bridge media response does not belong to the requested item ${item.name}."
                }
            }
            ?: error("Desktop Bridge returned invalid media information for ${item.name}.")

    suspend fun setMediaProtection(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        updateMediaMetadata(
            item,
            endpoint = "protection",
            payload = JSONObject().put("enabled", enabled),
            feature = CameraFeature.MEDIA_PROTECT,
            target = MediaMetadataTarget(MediaMetadataField.PROTECTION, if (enabled) 1 else 0),
        )

    suspend fun setMediaArchived(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        updateMediaMetadata(
            item,
            endpoint = "archive",
            payload = JSONObject().put("enabled", enabled),
            feature = CameraFeature.MEDIA_ARCHIVE,
            target = MediaMetadataTarget(MediaMetadataField.ARCHIVE, if (enabled) 1 else 0),
        )

    suspend fun setMediaRating(item: CameraMediaItem, rating: Int): CameraMediaItem {
        require(rating in 0..5) { "Media rating must be from 0 through 5." }
        return updateMediaMetadata(
            item,
            endpoint = "rating",
            payload = JSONObject().put("value", rating),
            feature = CameraFeature.MEDIA_RATING,
            target = MediaMetadataTarget(MediaMetadataField.RATING, rating),
        )
    }

    suspend fun setMediaRotation(item: CameraMediaItem, degrees: Int): CameraMediaItem {
        require(degrees in MEDIA_ROTATIONS) { "Media rotation must be 0, 90, 180, or 270 degrees." }
        return updateMediaMetadata(
            item,
            endpoint = "rotation",
            payload = JSONObject().put("degrees", degrees),
            feature = CameraFeature.MEDIA_ROTATE,
            target = MediaMetadataTarget(MediaMetadataField.ROTATION, degrees),
        )
    }

    private suspend fun updateMediaMetadata(
        item: CameraMediaItem,
        endpoint: String,
        payload: JSONObject,
        feature: CameraFeature,
        target: MediaMetadataTarget,
    ): CameraMediaItem = parseMediaItem(
        putJson(sessionEndpoint("media", item.id, endpoint), payload, strictMediaJsonLimit = MAX_MEDIA_METADATA_JSON_BYTES),
    )?.also { updated ->
        check(updated.id == item.id) {
            "Desktop Bridge media response does not belong to the requested item ${item.name}."
        }
        check(target.matches(item.id, MediaMetadataReadback(updated.id, updated.protected, updated.archived,
            updated.rating, updated.rotationDegrees))) {
            "Desktop Bridge media response did not confirm the requested ${if (target.field == MediaMetadataField.RATING) "rating" else endpoint} ${target.value} for ${item.name}."
        }
        observedFeatures.add(feature)
    }
        ?: error("Desktop Bridge returned invalid media information after updating ${item.name}.")

    suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail {
        require(item.isVisualMedia) { "Thumbnails require an unambiguous photo or video item." }
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
        require(item.isPhotoMedia && (item.kind.equals("image", ignoreCase = true) || item.kind.equals("raw", ignoreCase = true))) {
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

    suspend fun openMediaStream(item: CameraMediaItem): CameraMediaStreamSource {
        require(item.isVideoMedia) { "Media streaming is available only for video items." }
        val ticket = postEmptyJson(sessionEndpoint("media", item.id, "playback"))
        val expiresInSeconds = ticket.optInt("expiresInSeconds", -1)
        check(expiresInSeconds in 1..MAX_PLAYBACK_TICKET_LIFETIME_SECONDS) {
            "Desktop Bridge returned an invalid media playback ticket lifetime."
        }
        val playbackUrl = resolvePlaybackUrl(
            ticket.requireString("url", "Desktop Bridge did not return a media playback URL."),
        )
        val stream = OkHttpCameraMediaStreamSource(
            item = item,
            httpClient = httpClient,
            requestFactory = { position ->
                fun request() = Request.Builder()
                    .url(playbackUrl)
                    .header("Accept", "video/*,application/octet-stream;q=0.8")
                    .header("Cache-Control", "no-cache")
                buildList {
                    if (position > 0L) add(request().header("Range", "bytes=$position-").get().build())
                    add(request().get().build())
                }
            },
        )
        return CloseAwareCameraMediaStreamSource(stream) {
            revokePlaybackTicket(playbackUrl)
        }
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
        val session = sessionForRequest(request)
        val revision = session?.revision
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
            call.execute().use { response ->
                session?.let(::requireCurrentSession)
                val body = response.body ?: error("Desktop Bridge returned an empty $label response.")
                if (!response.isSuccessful) throw bridgeError(response.code, body.string(), "Media $label", session, revision)
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
        } catch (exception: Exception) {
            currentCoroutineContext().ensureActive()
            throw exception
        } finally {
            cancelCall.set(false)
            cancellationWatcher.cancel()
        }
    }

    suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaDownloadResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(sessionEndpoint("media", item.id)).get().build()
        val session = sessionForRequest(request)
        val revision = session?.revision
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
                session?.let(::requireCurrentSession)
                val body = response.body ?: error("Desktop Bridge returned an empty media response.")
                if (!response.isSuccessful) throw bridgeError(response.code, body.string(), "Media download", session, revision)
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
                validateOriginalMediaDownload(item, bytesTransferred, responseLength)
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

    suspend fun uploadMedia(
        name: String,
        sizeBytes: Long,
        contentType: String?,
        source: InputStream,
        evidence: MediaUploadEvidence = MediaUploadEvidence(),
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaUploadResult = withContext(Dispatchers.IO) {
        require(sizeBytes in 1L..MAX_MEDIA_UPLOAD_BYTES) {
            "Desktop Bridge upload size must be from 1 through $MAX_MEDIA_UPLOAD_BYTES bytes."
        }
        val mediaType = (contentType ?: "application/octet-stream").substringBefore(';').trim().toMediaType()
        val uploadUrl = sessionEndpoint("media").newBuilder().addQueryParameter("filename", name).build()
        val uploadContext = currentCoroutineContext()
        var bytesTransferred = 0L
        val body = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength(): Long = sizeBytes

            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                onProgress(CameraMediaTransferProgress(0L, sizeBytes))
                while (bytesTransferred < sizeBytes) {
                    uploadContext.ensureActive()
                    val requested = minOf(buffer.size.toLong(), sizeBytes - bytesTransferred).toInt()
                    val count = source.read(buffer, 0, requested)
                    check(count >= 0) {
                        "Upload source ended after $bytesTransferred of $sizeBytes bytes."
                    }
                    if (count == 0) continue
                    uploadContext.ensureActive()
                    sink.write(buffer, 0, count)
                    bytesTransferred += count
                    onProgress(CameraMediaTransferProgress(bytesTransferred, sizeBytes))
                }
                check(source.read() == -1) { "Upload source exceeds its declared $sizeBytes bytes." }
            }
        }
        val request = Request.Builder().url(uploadUrl).post(body).build()
        val session = sessionForRequest(request)
        val revision = session?.revision
        val call = newBridgeCall(request)
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
                session?.let(::requireCurrentSession)
                val responseBody = response.body?.let { readStrictMediaJsonText(it.byteStream(), it.contentLength(), MAX_UPLOAD_RESPONSE_BYTES) }.orEmpty()
                if (!response.isSuccessful) throw bridgeError(response.code, responseBody, "Media upload", session, revision)
                check(bytesTransferred == sizeBytes) {
                    "Desktop Bridge upload completed after $bytesTransferred of $sizeBytes bytes."
                }
                val strictReceipt = StrictMediaJson(responseBody).also { it.validate() }
                val receipt = JSONObject(responseBody)
                check(validUploadReceipt(response.code, name, sizeBytes, bytesTransferred,
                    receipt.opt("id"), receipt.opt("name"), receipt.opt("sizeBytes"), receipt.opt("kind"), strictReceipt.topLevelToken("sizeBytes"))) {
                    "Upload has no valid operation-specific completion receipt."
                }
                val item = CameraMediaItem(
                    id = receipt.getString("id"), name = receipt.getString("name"), kind = receipt.getString("kind"),
                    sizeBytes = sizeBytes, contentType = receipt.cameraMediaContentType(),
                )
                check(uploadReceiptNameMatches(name, item.name) && item.sizeBytes == sizeBytes) {
                    "Desktop Bridge did not verify the uploaded filename and size."
                }
                session?.let(::requireCurrentSession)
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
        val request = Request.Builder().url(sessionEndpoint("media", item.id)).delete().build()
        val session = sessionForRequest(request)
        executeMediaDeleteHttp(mutationHttpClient, request, evidence, checkOwner = {
            session?.let(::requireCurrentSession)
            requireBridgeRequestAllowed(request)
        })
        observedFeatures.add(CameraFeature.MEDIA_DELETE)
    }

    private fun featureForSetting(key: String): CameraFeature = when (key.lowercase()) {
        "iso", "shutter", "aperture" -> CameraFeature.EXPOSURE_CONTROL
        "whitebalance" -> CameraFeature.WHITE_BALANCE_CONTROL
        "cardselectionstillimage", "cardselectionmovie" -> CameraFeature.CARD_SELECTION_CONTROL
        "soundrecording", "soundrecordingmodeintmic", "soundrecordingmodeextmic", "soundrecordingmodeacc",
        "windfilter", "windfilterintmic", "windfilterextmic", "windfilteracc",
        "attenuator", "attenuatorintmic", "attenuatorextmic", "attenuatoracc" ->
            CameraFeature.SOUND_RECORDING_CONTROL
        "soundrecordinglevel", "soundrecordinglevelintmic", "soundrecordinglevelextmic",
        "soundrecordinglevelacc" -> CameraFeature.SOUND_RECORDING_LEVEL_CONTROL
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
        val id = item.opt("id") as? String ?: return null
        val name = item.opt("name") as? String ?: return null
        if (id.isBlank() || name.isBlank()) return null
        val kind = item.optString("kind", "other")
        val contentType = item.cameraMediaContentType()
        return CameraMediaItem(
            id = id,
            name = name,
            kind = kind,
            sizeBytes = item.optNullableLong("sizeBytes"),
            captureTime = item.optNullableString("captureTime"),
            previewAvailable = item.optBoolean("previewAvailable", false),
            protected = normalizedMediaMetadataBoolean(item.opt("protected")),
            archived = normalizedMediaMetadataBoolean(item.opt("archived")),
            rating = item.normalizedMediaRating(),
            rotationDegrees = normalizedMediaMetadataInteger(item.opt("rotationDegrees"), MEDIA_ROTATIONS),
            ratingWritable = normalizedMediaMetadataBoolean(item.opt("ratingWritable")),
            protectionWritable = normalizedMediaMetadataBoolean(item.opt("protectionWritable")),
            archiveWritable = normalizedMediaMetadataBoolean(item.opt("archiveWritable")),
            rotationWritable = normalizedMediaMetadataBoolean(item.opt("rotationWritable")),
            deleteWritable = normalizedMediaMetadataBoolean(item.opt("deleteWritable")),
            streamAvailable = cameraMediaHasVideoTransportHint(kind, name) && cameraMediaIsVideo(kind, name, contentType),
            contentType = contentType,
            widthPixels = item.optNullableInt("widthPixels")?.takeIf { it > 0 },
            heightPixels = item.optNullableInt("heightPixels")?.takeIf { it > 0 },
            folder = parseMediaFolder(item),
        )
    }

    private fun parseMediaFolder(item: JSONObject): CameraMediaFolder? {
        // Optional metadata must never make otherwise usable legacy media disappear.
        // In particular, do not coerce JSON values or decode the Bridge's opaque IDs.
        val id = item.opt("folderId") as? String ?: return null
        val label = item.opt("folderLabel") as? String ?: return null
        return cameraMediaFolderOrNull(id, label)
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
            recording = body.strictRecordingStatus(),
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
            bulbExposureActive = body.opt("bulbExposureActive") as? Boolean,
            shutterReleaseUnconfirmed = body.opt("shutterReleaseUnconfirmed") as? Boolean,
            liveViewStopUnconfirmed = body.opt("liveViewStopUnconfirmed") as? Boolean,
            heldAutofocusActive = body.opt("heldAutofocusActive") as? Boolean,
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
        ).also { lastRecordingDisplayStatus = it }
    }

    private fun parseBulbStatus(body: JSONObject): CameraStatus {
        check(!body.has("shutterReleaseUnconfirmed") || body.opt("shutterReleaseUnconfirmed") is Boolean) {
            "Desktop Bridge returned an invalid shutter release confirmation."
        }
        return parseStatus(body)
    }

    private suspend fun readBridgeStatus(
        session: BridgeShutterReleaseSession,
        recoveryRead: Boolean = false,
    ): CameraStatus = parseStatus(
        requestJson(Request.Builder()
            .url(endpoint("v1", "session", session.id, "status"))
            .header("Cache-Control", "no-cache, no-store")
            .get().build(), observeStatus = false, recoveryRead = recoveryRead),
    )

    private suspend fun getJson(url: HttpUrl, cameraDiscovery: Boolean = false, strictMediaJsonLimit: Int? = null): JSONObject = requestJson(
        Request.Builder().url(url).get().build(), cameraDiscovery = cameraDiscovery, strictMediaJsonLimit = strictMediaJsonLimit,
    )

    private suspend fun postJson(
        url: HttpUrl, payload: JSONObject, shutterStart: Boolean = false, cameraDiscovery: Boolean = false,
        connectionSetup: Boolean = false,
        recordingEvidence: RecordingRequestEvidence? = null,
        captureEvidence: CaptureConfigurationEvidence? = null, captureResponse: ((JSONObject) -> Unit)? = null,
        captureOwner: BridgeShutterReleaseSession? = null,
    ): JSONObject = requestJson(
        Request.Builder()
            .url(url)
            .tag(BridgeShutterStart::class.java, if (shutterStart) BridgeShutterStart else null)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build(), cameraDiscovery = cameraDiscovery, recordingEvidence = recordingEvidence,
        connectionSetup = connectionSetup,
        captureEvidence = captureEvidence, captureResponse = captureResponse, captureOwner = captureOwner,
    )

    private suspend fun postEmptyJson(url: HttpUrl): JSONObject = requestJson(
        Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .build(),
    )

    private suspend fun putJson(url: HttpUrl, payload: JSONObject, strictMediaJsonLimit: Int? = null,
        captureEvidence: CaptureConfigurationEvidence? = null, captureResponse: ((JSONObject) -> Unit)? = null,
        captureOwner: BridgeShutterReleaseSession? = null): JSONObject = requestJson(
        Request.Builder()
            .url(url)
            .put(payload.toString().toRequestBody(jsonMediaType))
            .build(), strictMediaJsonLimit = strictMediaJsonLimit, captureEvidence = captureEvidence, captureResponse = captureResponse,
        captureOwner = captureOwner,
    )

    private suspend fun requestJson(
        request: Request,
        observeStatus: Boolean = true,
        recoveryRead: Boolean = false,
        cameraDiscovery: Boolean = false,
        connectionSetup: Boolean = false,
        recordingEvidence: RecordingRequestEvidence? = null,
        maxResponseBytes: Int? = null,
        strictMediaJsonLimit: Int? = null,
        captureEvidence: CaptureConfigurationEvidence? = null, captureResponse: ((JSONObject) -> Unit)? = null,
        captureRecovery: Boolean = false, captureOwner: BridgeShutterReleaseSession? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val maintenanceOwner = currentCoroutineContext()[MaintenanceBridgeOwnerContext]?.owner
        if (maintenanceOwner != null) {
            val owner = maintenanceOwner
            var result: JSONObject? = null
            maintenanceJson(request, owner) { result = it }
            return@withContext requireNotNull(result)
        }
        val session = sessionForRequest(request)
        val captureSessionEngine = sessionEngine
        val revision = session?.revision
        val focusSession = heldAutofocusSession
        val focusRevision = focusSession?.revision
        val recordingIo = currentCoroutineContext()[RecordingIoContext] != null
        val call = newBridgeCall(request, captureEvidence != null || captureRecovery || recordingIo)
        val jsonLimit = strictMediaJsonLimit ?: 65_536.takeIf { captureEvidence != null || recordingIo || connectionSetup }
        if (connectionSetup) {
            // Bound protected acquisition, including a continuously paced response body.
            val maximum = TimeUnit.SECONDS.toNanos(15)
            val configured = call.timeout().timeoutNanos()
            if (configured == 0L || configured > maximum) call.timeout().timeout(maximum, TimeUnit.NANOSECONDS)
        }
        if (recoveryRead) {
            // Bound only the additional release-proof read, including a continuously paced body.
            // This is an app recovery policy, not a camera timing guarantee or a release ACK.
            val maximum = TimeUnit.SECONDS.toNanos(RECOVERY_STATUS_CALL_TIMEOUT_SECONDS)
            val configured = call.timeout().timeoutNanos()
            if (configured == 0L || configured > maximum) call.timeout().timeout(maximum, TimeUnit.NANOSECONDS)
        }
        val cancelCall = AtomicBoolean(true)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (cancelCall.get()) call.cancel() }
        }
        try {
            captureOwner?.let { owner ->
                currentCoroutineContext().ensureActive()
                requireCurrentSession(owner)
                check(session === owner) { "Capture configuration target does not belong to the frozen session." }
            }
            recordingEvidence?.let {
                currentCoroutineContext().ensureActive()
                it.beginRequest()
            }
            captureEvidence?.let {
                currentCoroutineContext().ensureActive()
                requireCurrentSession(requireNotNull(session) { "Capture configuration session changed before dispatch." })
                it.beforeDispatch("${request.method} ${request.url.encodedPath}")
            }
            call.execute().use { response ->
                // Non-502 rejection headers remain evidence even if the error body is lost.
                // A 502 may instead carry an acknowledged or unknown camera outcome.
                if (!response.isSuccessful && response.code != 502) recordingEvidence?.rejected = true
                val body = response.body?.let {
                    if (captureEvidence != null || captureRecovery) it.byteStream().use { input -> readCaptureConfigurationJsonText(input, it.contentLength()) }
                    else if (jsonLimit != null) it.byteStream().use { input -> readStrictMediaJsonText(input, it.contentLength(), jsonLimit) }
                    else if (maxResponseBytes == null) it.string() else it.readBoundedUtf8(maxResponseBytes)
                }.orEmpty()
                if (recordingEvidence == null && captureEvidence == null) session?.let(::requireCurrentSession)
                if (captureEvidence != null || recordingIo) StrictMediaJson(body).validate()
                if (!response.isSuccessful) {
                    captureEvidence?.let { evidence ->
                        val envelope = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
                        val receipt = envelope?.optJSONObject("captureConfigReceipt")
                        if (captureSessionEngine == "ccapi" && receipt != null && acceptsCaptureConfigurationBridgeReceipt(evidence.request,
                            response.code, envelope?.opt("code"), envelope?.opt("engine"), envelope?.opt("feature"),
                            receipt.opt("version"), receipt.opt("operation"), receipt.opt("stage"),
                            receipt.opt("requestedValue"), receipt.opt("acknowledgedValue"), receipt.has("field"), receipt.opt("field"))) {
                            evidence.acknowledge(receipt.getString("acknowledgedValue"))
                            evidence.unavailable()
                        }
                    }
                    val failure = bridgeError(response.code, body, request.url.encodedPath, session, revision,
                        cameraDiscovery = cameraDiscovery)
                    // A malformed/missing 502 body can hide either command uncertainty or
                    // acknowledged-readback evidence; it cannot prove camera rejection.
                    val errorEnvelope = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
                    val errorCode = errorEnvelope?.opt("code")
                    val errorMessage = errorEnvelope?.opt("message")
                    val missingRecordingOutcome = recordingEvidence != null && response.code == 502 &&
                        (errorCode !is String || errorCode.isBlank() ||
                            errorMessage !is String || errorMessage.isBlank())
                    // Preserve a complete rejection before session/cancellation checks can throw.
                    recordingEvidence?.rejected = !(missingRecordingOutcome || (response.code == 502 &&
                        failure is DesktopBridgeException && (failure.code == "RECORDING_STATUS_READBACK_FAILED" ||
                        failure.isRecordingCommandUnconfirmed)))
                    session?.let(::requireCurrentSession)
                    if (missingRecordingOutcome) {
                        throw RecordingCommandUnconfirmedException(requireNotNull(recordingEvidence).command, failure)
                    }
                    throw failure
                }
                if (captureEvidence == null) session?.let(::requireCurrentSession)
                if (strictMediaJsonLimit != null) StrictMediaJson(body).validate()
                val parsed = runCatching { JSONObject(body) }.getOrElse {
                    // Classify at the REC response boundary: coroutine stack recovery can
                    // wrap an IllegalStateException and change the immediate cause later.
                    if (recordingEvidence != null && it is org.json.JSONException) {
                        throw RecordingCommandUnconfirmedException(recordingEvidence.command, it)
                    }
                    throw IllegalStateException("Desktop Bridge returned invalid JSON for ${request.url.encodedPath}.", it)
                }
                captureResponse?.invoke(parsed)
                session?.let(::requireCurrentSession)
                if (observeStatus && session != null && revision != null &&
                    heldAutofocusSession === focusSession && focusSession?.revision == focusRevision) {
                    observeBridgeStatus(session, parseStatus(parsed), revision)
                }
                parsed
            }
        } catch (exception: Exception) {
            currentCoroutineContext().ensureActive()
            throw exception
        } finally {
            cancelCall.set(false)
            cancellationWatcher.cancel()
        }
    }

    private suspend fun requestEventJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        val session = sessionForRequest(request)
        val revision = session?.revision
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
                session?.let(::requireCurrentSession)
                if (!response.isSuccessful) throw bridgeError(response.code, body, request.url.encodedPath, session, revision)
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

    private suspend fun requestOk(request: Request, recoveryCleanup: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val session = sessionForRequest(request)
        val revision = session?.revision
        val call = newBridgeCall(request)
        if (recoveryCleanup || currentCoroutineContext()[ConnectionAttemptCleanup.Key] != null) {
            // Share the release-recovery budget, including continuously paced response bodies,
            // without extending a shorter transport deadline or changing ordinary requests.
            val maximum = TimeUnit.SECONDS.toNanos(RECOVERY_STATUS_CALL_TIMEOUT_SECONDS)
            val configured = call.timeout().timeoutNanos()
            if (configured == 0L || configured > maximum) call.timeout().timeout(maximum, TimeUnit.NANOSECONDS)
        }
        val cancelCall = AtomicBoolean(true)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (cancelCall.get()) call.cancel() }
        }
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                session?.let(::requireCurrentSession)
                if (!response.isSuccessful) throw bridgeError(response.code, body, request.url.encodedPath, session, revision)
                if (request.method == "DELETE" && session != null &&
                    request.url.encodedPath == endpoint("v1", "session", session.id).encodedPath) {
                    check(bridgeSessionRetirementAcknowledged(response.code, body)) { "Desktop Bridge session retirement was not acknowledged." }
                }

            }
        } catch (exception: Exception) {
            currentCoroutineContext().ensureActive()
            throw exception
        } finally {
            cancelCall.set(false)
            cancellationWatcher.cancel()
        }
    }

    private fun bridgeError(
        statusCode: Int, body: String, operation: String,
        session: BridgeShutterReleaseSession?,
        expectedRevision: Long? = null,
        cameraDiscovery: Boolean = false,
    ): IllegalStateException {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val code = error?.optString("code")?.takeIf(String::isNotBlank) ?: "HTTP_$statusCode"
        val message = error?.optString("message")?.takeIf(String::isNotBlank)
            ?: body.trim().take(MAX_ERROR_BODY_CHARS).ifBlank { "Desktop Bridge returned HTTP $statusCode." }
        val failure = DesktopBridgeException(
            code = code,
            feature = error?.optNullableString("feature"),
            engine = error?.optNullableString("engine"),
            message = "Desktop Bridge $operation failed [$code]: $message",
            statusCode = statusCode,
            // JSONObject.optString coerces non-strings. The additive evidence must
            // use exact JSON types and values, independently of legacy diagnostics.
            connectionReason = if (cameraDiscovery && error != null && statusCode == 502 &&
                error.opt("code") == "CCAPI_DISCOVERY_FAILED" && error.opt("engine") == "ccapi") {
                (error.opt("connectionReason") as? String)?.let { raw ->
                    ConnectionFailureReason.entries.firstOrNull { it.name == raw }
                }
            } else null,
        )
        if (code == "SHUTTER_RELEASE_UNCONFIRMED" &&
            heldAutofocusSession?.hasReleaseResponsibility != true &&
            (expectedRevision == null || session?.revision == expectedRevision)) {
            session?.markUnconfirmed()
            return ShutterReleaseException(failure)
        }
        return failure
    }

    private fun requireShutterSession() = requireNotNull(shutterSession) { "Desktop bridge session is not initialized." }

    private fun observeBridgeStatus(session: BridgeShutterReleaseSession, status: CameraStatus, revision: Long): Boolean =
        // The shared warning covers both controls. A locally owned focus stop must never
        // become an unrelated Bulb release or remain latched after its owner was released.
        session.observe(if (heldAutofocusSession?.hasReleaseResponsibility == true) {
            status.copy(shutterReleaseUnconfirmed = false)
        } else status, revision)

    private fun requireCurrentSession(session: BridgeShutterReleaseSession) {
        if (shutterSession !== session || session.closed) throw CancellationException("Desktop Bridge session changed.")
    }

    private fun sessionForRequest(request: Request): BridgeShutterReleaseSession? = shutterSession?.takeIf {
        val path = endpoint("v1", "session", it.id).encodedPath
        request.url.encodedPath == path || request.url.encodedPath.startsWith("$path/")
    }

    private object BridgeShutterStart
    private object BridgeFocusStart

    private fun requireBridgeRequestAllowed(request: Request) {
        val session = sessionForRequest(request)
        session?.let(::requireCurrentSession)
        if (request.method in setOf("GET", "HEAD")) return
        val path = request.url.encodedPath
        val root = session?.let { endpoint("v1", "session", it.id).encodedPath }
        val stopOnly = (request.method == "POST" && path in setOf(
            "$root/bulb/stop", "$root/liveview/stop", "$root/recording/stop", "$root/recording/stop/outcome", "$root/focus/auto/stop",
        )) || (request.method == "DELETE" && path in setOf(root, "$root/events"))
        if (session != null && heldAutofocusSession?.hasReleaseResponsibility == true &&
            request.tag(BridgeFocusStart::class.java) == null && !stopOnly) {
            throw AutofocusReleaseException(IllegalStateException("Stop held autofocus before another Bridge operation."))
        }
        if (session?.unconfirmed == true && request.tag(BridgeShutterStart::class.java) == null) {
            if (!stopOnly) throw ShutterReleaseException(IllegalStateException("Retry stopping the camera control before another Bridge operation."))
        }
    }

    private fun newBridgeCall(request: Request, captureConfiguration: Boolean = false): Call {
        requireBridgeRequestAllowed(request)
        if (request.method in setOf("GET", "HEAD")) return (if (captureConfiguration) captureConfigurationHttpClient else httpClient).newCall(request)
        val oneShot = request.body?.let { body ->
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

    private fun endpoint(vararg segments: String): HttpUrl = rootUrl.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()

    private fun sessionEndpoint(vararg segments: String): HttpUrl =
        endpoint("v1", "session", requireNotNull(sessionId) { "Desktop bridge session is not initialized." }, *segments)

    private fun frameUrl(cacheKey: Long): HttpUrl = sessionEndpoint("liveview", "frame").newBuilder()
        .addQueryParameter("t", cacheKey.toString())
        .build()

    private fun resolvePlaybackUrl(rawUrl: String): HttpUrl {
        check(rawUrl.length in 1..MAX_PLAYBACK_URL_CHARS) {
            "Desktop Bridge returned an invalid media playback URL."
        }
        val resolved = rootUrl.resolve(rawUrl)
            ?: error("Desktop Bridge returned an invalid media playback URL.")
        check(
            resolved.scheme == rootUrl.scheme &&
                resolved.host == rootUrl.host &&
                resolved.port == rootUrl.port &&
                resolved.username.isEmpty() &&
                resolved.password.isEmpty() &&
                resolved.query == null &&
                resolved.fragment == null
        ) {
            "Desktop Bridge returned a media playback URL outside its own origin."
        }
        val segments = resolved.pathSegments
        check(
            segments.size == 3 &&
                segments[0] == "v1" &&
                segments[1] == "media-playback" &&
                segments[2].length in 1..MAX_PLAYBACK_TOKEN_CHARS &&
                segments[2].all { it.isLetterOrDigit() || it == '-' || it == '_' }
        ) {
            "Desktop Bridge returned an invalid media playback URL."
        }
        return resolved
    }

    private fun revokePlaybackTicket(playbackUrl: HttpUrl) {
        newBridgeCall(Request.Builder().url(playbackUrl).delete().build()).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            },
        )
    }

    private companion object {
        const val RECOVERY_STATUS_CALL_TIMEOUT_SECONDS = 5L
        val SUPPORTED_LOCAL_ENGINES = setOf("libgphoto2", "edsdk")
        const val BRIDGE_SERVICE_NAME = "open-eos-control-bridge"
        const val MAX_LIVE_VIEW_FRAME_BYTES = 12 * 1024 * 1024L
        const val MAX_ERROR_BODY_CHARS = 2_000
        const val MAX_EVENT_BODY_BYTES = 256 * 1024
        const val MAX_UPLOAD_RESPONSE_BYTES = 32 * 1024
        const val MAX_EVENT_KEYS = 64
        const val MAX_EVENT_KEY_CHARS = 128
        const val EVENT_READ_TIMEOUT_SECONDS = 40L
        const val EVENT_CALL_TIMEOUT_SECONDS = 45L
        const val MAX_MEDIA_THUMBNAIL_BYTES = 8 * 1024 * 1024L
        const val MAX_MEDIA_PREVIEW_BYTES = 32 * 1024 * 1024L
        const val MAX_PLAYBACK_TICKET_LIFETIME_SECONDS = 3_600
        const val MAX_PLAYBACK_URL_CHARS = 2_048
        const val MAX_PLAYBACK_TOKEN_CHARS = 512
        const val TRANSFER_BUFFER_BYTES = 64 * 1024
        const val MEDIA_PROGRESS_INTERVAL_BYTES = 512 * 1024L
        const val MAX_MEDIA_UPLOAD_BYTES = UINT32_MAX
        val MEDIA_ROTATIONS = setOf(0, 90, 180, 270)
    }
}

private fun ResponseBody.readBoundedUtf8(maxBytes: Int, strictUtf8: Boolean = false): String {
    val declaredBytes = contentLength()
    check(declaredBytes < 0L || declaredBytes <= maxBytes.toLong()) {
        "Desktop Bridge upload response exceeded $maxBytes bytes."
    }
    val output = ByteArrayOutputStream(minOf(maxBytes, declaredBytes.coerceAtLeast(0L).toInt()))
    byteStream().use { input ->
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            check(output.size() <= maxBytes - count) {
                "Desktop Bridge upload response exceeded $maxBytes bytes."
            }
            output.write(buffer, 0, count)
        }
    }
    return if (strictUtf8) Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output.toByteArray())).toString()
    else output.toString(Charsets.UTF_8.name())
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
    return result.takeIf(::validCaptureConfigurationNaming)
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

private fun JSONObject.toStrictShootingSetting(): CameraSettingControl? {
    val key = opt("key") as? String ?: return null
    val label = opt("label") as? String ?: return null
    val value = opt("value") as? String ?: return null
    val array = optJSONArray("values") ?: return null
    if (array.length() > 1024) return null
    val values = (0 until array.length()).map { array.opt(it) as? String ?: return null }
    val kind = when (opt("inputKind")) { "choice" -> CameraSettingInputKind.CHOICE; "text" -> CameraSettingInputKind.TEXT; else -> return null }
    val maxLength = if (!has("maxLength") || isNull("maxLength")) null else opt("maxLength") as? Int ?: return null
    return CameraSettingControl(key, label, value, values, kind, maxLength).takeIf(::acceptsShootingSettingObservation)
}
