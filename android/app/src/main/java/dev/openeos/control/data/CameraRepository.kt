package dev.openeos.control.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

class CameraRepository(
    backendFactory: CameraBackendFactory = CameraBackendFactory(),
) {
    private var backendFactory = backendFactory
    private var backend: CameraControlBackend = backendFactory.create(
        CameraConnection.CcapiNetwork(DEFAULT_CAMERA_BASE_URL)
    )
    private var frameVersion = 0L
    private var liveViewRequest = LiveViewRequest()
    private var active = false
    private var liveViewRunning = false
    private var liveViewAttemptGeneration = 0L
    private var activeInfo: CameraInfo? = null
    private val connectionMutex = Mutex()
    @Volatile var connectionRetirementRevision: Long = 0L
        private set
    @Volatile internal var connectionRetirementSafetyWarnings: Set<PreviousConnectionSafetyWarning> = emptySet()
        private set
    private val liveViewStarter = LiveViewStartOperation()

    internal fun cancelPendingLiveViewStart() = liveViewStarter.retire()

    private suspend fun startOwnedLiveView(): Boolean {
        liveViewAttemptGeneration += 1
        val owner = backend
        val request = liveViewRequest
        return liveViewStarter.run(start = { owner.startLiveView(request) },
            stopRequired = { owner.liveViewStopRequired }, stop = { owner.stopLiveView() })
    }

    fun isRealCamera(): Boolean = backend.prefersBitmapLiveViewFrames

    fun nativeLiveViewSession(): NativeLiveViewSession? = backend.nativeLiveViewSession

    fun isLiveViewRunning(): Boolean = liveViewRunning

    internal fun liveViewOwner() = CameraLiveViewOwner(backend, liveViewAttemptGeneration)

    internal fun ownsLiveView(owner: CameraLiveViewOwner): Boolean =
        active && owner.backend === backend && owner.attempt == liveViewAttemptGeneration

    /** Retire local health immediately; the backend retains its exact remote Stop responsibility. */
    internal fun retireLiveView(owner: CameraLiveViewOwner): Boolean {
        if (!ownsLiveView(owner)) return false
        liveViewRunning = false
        backend.retireLiveViewLocally()
        return true
    }

    fun activeLiveViewSource(): LiveViewSource? = backend.activeLiveViewSource

    // Stop ownership is distinct from confirmed running: a lost start response can leave only cleanup.
    fun isLiveViewStopRequired(): Boolean = backend.liveViewStopRequired

    fun configureAndroidNetworkRouting(context: Context) {
        check(!active) { "Camera network routing cannot change while connected." }
        backendFactory = CameraBackendFactory(
            httpTransportFactory = AndroidCameraHttpTransportFactory(context.applicationContext),
            ptpTransportFactory = AndroidUsbPtpTransportFactory(context.applicationContext),
            usbHostCaptureStore = AndroidUsbHostCaptureStore(context.applicationContext),
            jpegFrameValidator = ::androidDecodesLiveViewJpeg,
        )
    }

    suspend fun connect(
        baseUrl: String,
        username: String = "",
        password: String = "",
        simulatorMode: Boolean? = null,
        request: LiveViewRequest = liveViewRequest,
        startLiveView: Boolean = true,
    ): CameraSession = connect(
        connection = CameraConnection.CcapiNetwork(
            baseUrl = baseUrl,
            username = username,
            password = password,
            simulatorMode = simulatorMode,
        ),
        request = request,
        startLiveView = startLiveView,
    )

    suspend fun connectUsb(
        deviceName: String,
        vendorId: Int,
        productId: Int,
        request: LiveViewRequest = liveViewRequest,
        startLiveView: Boolean = true,
    ): CameraSession = connect(
        connection = CameraConnection.AndroidUsbPtp(
            deviceName = deviceName,
            vendorId = vendorId,
            productId = productId,
        ),
        request = request,
        startLiveView = startLiveView,
    )

    suspend fun discoverBridgeCameras(
        baseUrl: String,
        token: String = "",
    ): List<DesktopBridgeCamera> {
        // Local Bridge engine discovery can touch a USB owner. Never queue a stale scan.
        if (!connectionMutex.tryLock()) throw ConnectionRetirementPendingException()
        try {
            if (connectionRetirementRevision != 0L) throw ConnectionRetirementBlockedException()
            if (active) throw ConnectionRetirementPendingException()
            return backendFactory.discoverDesktopBridge(CameraConnection.DesktopBridge(baseUrl = baseUrl, token = token))
        } finally { connectionMutex.unlock() }
    }

    suspend fun connectBridge(
        baseUrl: String,
        token: String = "",
        cameraId: String? = null,
        cameraEngine: String? = null,
        request: LiveViewRequest = liveViewRequest,
        startLiveView: Boolean = true,
    ): CameraSession = connect(
        connection = CameraConnection.DesktopBridge(
            baseUrl = baseUrl,
            token = token,
            cameraId = cameraId,
            cameraEngine = cameraEngine,
        ),
        request = request,
        startLiveView = startLiveView,
    )

    private suspend fun connect(
        connection: CameraConnection,
        request: LiveViewRequest,
        startLiveView: Boolean,
    ): CameraSession = connectionMutex.withLock {
        if (connectionRetirementRevision != 0L) throw ConnectionRetirementBlockedException()
        if (active) disconnectLocked()
        if (connectionRetirementRevision != 0L) throw ConnectionRetirementBlockedException()
        try {
            backend = backendFactory.create(connection)
            backend.initialize()
            active = true
            liveViewRunning = false
            frameVersion = 0L
            val info = backend.info()
            activeInfo = info
            val status = backend.status()
            val capabilities = try {
                backend.capabilities().forCamera(info)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (status.shutterReleaseUnconfirmed != true) throw exception
                // Preserve this session's only safe control even when capability discovery fails.
                CameraCapabilities(emptyList(), emptyList(), emptyList(), emptyList()).forCamera(info)
            }
            liveViewRequest = request.clampTo(capabilities.liveView)
            var liveViewFrameUrl: String? = null
            var liveViewStartError: String? = null
            if (startLiveView && status.shutterReleaseUnconfirmed != true &&
                status.bulbExposureActive != true && capabilities.matrix.supports(CameraFeature.LIVE_VIEW)) {
                try {
                    liveViewRunning = startOwnedLiveView()
                    if (liveViewRunning && !backend.prefersBitmapLiveViewFrames) {
                        liveViewFrameUrl = nextLiveViewFrameUrl()
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    // A session can still provide settings and status without live view.
                    liveViewStartError = "${exception.javaClass.simpleName}: ${exception.message ?: "Live View start failed"}"
                }
            }
            coroutineContext.ensureActive()
            CameraSession(
                transport = backend.transport,
                connection = backend.connection,
                info = info,
                status = status,
                capabilities = capabilities,
                networkDiagnostics = backend.networkDiagnostics,
                liveViewFrameUrl = liveViewFrameUrl,
                liveViewRequest = liveViewRequest,
                activeLiveViewSource = backend.activeLiveViewSource,
                nativeLiveViewSession = backend.nativeLiveViewSession,
                liveViewStartError = liveViewStartError,
            )
        } catch (exception: Exception) {
            // Cancellation still owns any partly initialized backend until cleanup completes.
            connectionRetirementSafetyWarnings += previousConnectionSafetyWarnings(exception)
            if (exception is ConnectionRetirementUnconfirmedException || backend.connectionRetirementUnconfirmed)
                connectionRetirementRevision += 1
            withContext(NonCancellable) {
                runCatching { backend.closeConnectionAttempt() }.onFailure { failure ->
                    connectionRetirementRevision += 1
                    connectionRetirementSafetyWarnings += previousConnectionSafetyWarnings(failure)
                }
            }
            active = false
            liveViewRunning = false
            activeInfo = null
            throw exception
        }
    }

    fun maintenanceStopRecoveryAccess(): suspend () -> Unit {
        val owner = backend
        return {
            connectionMutex.withLock {
                check(active && backend === owner && owner.maintenanceStopOwned) { "Maintenance Stop owner changed." }
                if (backend === owner) liveViewRunning = false
                owner.stopLiveView()
            }
        }
    }

    fun maintenanceRetirementAccess(force: Boolean): (suspend () -> Unit)? {
        val owner = backend
        if (!force && !owner.maintenanceSessionOwned) return null
        return {
            connectionMutex.withLock {
                owner.retireMaintenanceLocally()
                if (backend === owner) {
                    active = false
                    liveViewRunning = false
                    activeInfo = null
                }
            }
        }
    }

    suspend fun disconnect() = connectionMutex.withLock {
        disconnectLocked()
    }

    private suspend fun disconnectLocked(): Boolean {
        if (!active) return false
        if (backend.maintenanceSessionOwned) {
            try { return backend.closeMaintenanceSession() }
            finally { active = false; liveViewRunning = false; activeInfo = null }
        }
        val hadLiveView = liveViewRunning || backend.liveViewStopRequired
        var liveViewStopUnconfirmed = false
        try {
            runCatching { backend.retryAutofocusStop() }
            try {
                backend.stopLiveView()
            } catch (_: Exception) {
                liveViewStopUnconfirmed = hadLiveView
                // A backend without Live View still needs its session closed.
            }
            backend.close()
        } catch (failure: Exception) {
            connectionRetirementSafetyWarnings += previousConnectionSafetyWarnings(failure)
            liveViewStopUnconfirmed = liveViewStopUnconfirmed || hadLiveView
            connectionRetirementRevision += 1
            // Session retirement cannot prove that an unreachable camera stopped.
        } finally {
            active = false
            liveViewRunning = false
            activeInfo = null
        }
        return liveViewStopUnconfirmed
    }

    suspend fun refreshStatus(): CameraStatus = backend.status()

    suspend fun setIso(value: String): CameraStatus = backend.setExposure(iso = value)

    suspend fun setShutter(value: String): CameraStatus = backend.setExposure(shutter = value)

    suspend fun setAperture(value: String): CameraStatus = backend.setExposure(aperture = value)

    suspend fun setWhiteBalance(value: String): CameraStatus = backend.setWhiteBalance(value)

    suspend fun setCameraSetting(key: String, value: String): CameraStatus = backend.setSetting(key, value)

    fun shootingSettingAccess(request: ShootingSettingRequest): ShootingSettingAccess {
        val owner = backend
        return owner.shootingSettingAccess(request)
    }

    /** The handle captures the actual backend, connection and routes before any await. */
    fun captureConfigurationAccess(): CaptureConfigurationAccess {
        val owner = backend
        val origin = when (val connection = owner.connection) {
            is CameraConnection.CcapiNetwork -> mediaDeleteEndpoint(connection.baseUrl)
            is CameraConnection.DesktopBridge -> mediaDeleteEndpoint(connection.baseUrl)
            else -> null
        }
        return CaptureConfigurationAccess(origin ?: owner.transport.label,
            write = { request, evidence ->
                when (request.operation) {
                    CaptureConfigurationOperation.CREATE_DIRECTORY -> {
                        owner.createDirectory(request.requestedValue, evidence)
                        null
                    }
                    CaptureConfigurationOperation.SET_FILE_NAMING -> owner.setFileNaming(
                        requireNotNull(request.field), request.requestedValue, evidence)
                }
            }, read = { operation -> owner.readCaptureConfiguration(operation) })
    }

    suspend fun createDirectory(name: String): String = backend.createDirectory(name)

    suspend fun setFileNaming(field: CameraFileNamingField, value: String): CameraFileNaming =
        backend.setFileNaming(field, value)

    fun maintenanceAccess(request: MaintenanceRequest, eventPollingActive: Boolean): MaintenanceAccess {
        val owner = backend
        val hadLiveView = liveViewRunning
        val preview = liveViewRequest.takeIf { hadLiveView }
        val access = owner.maintenanceAccess(request, preview)
        return MaintenanceAccess(access.origin, restoreAvailable = request.allowsRestore,
            eventStopRequired = eventPollingActive && access.eventStopRequired,
            liveViewStopRequired = (hadLiveView || owner.liveViewStopRequired) && access.liveViewStopRequired, stopEvents = access.stopEvents,
            stopLiveView = {
                if (backend === owner) liveViewRunning = false
                access.stopLiveView()
            }, dispatch = access.dispatch, read = access.read,
            restore = {
                if (backend === owner) {
                    liveViewAttemptGeneration += 1
                    liveViewRunning = false
                }
                access.restore()
                if (backend === owner) liveViewRunning = hadLiveView
            })
    }

    suspend fun syncCameraClock(): CameraStatus = backend.syncCameraClock()

    suspend fun cleanSensor(autoPowerOff: Boolean) = backend.cleanSensor(autoPowerOff)

    suspend fun sleepCamera() = backend.sleepCamera()

    suspend fun refreshCapabilities(): CameraCapabilities = backend.capabilities().forCamera(
        activeInfo ?: backend.info().also { activeInfo = it }
    )

    suspend fun pollEvent(): CameraEvent = backend.pollEvent()

    suspend fun stopEventPolling() = backend.stopEventPolling()

    fun observedFeatures(): Set<CameraFeature> = backend.observedFeatures()

    fun refreshNetworkDiagnostics(): CameraNetworkDiagnostics = backend.networkDiagnostics

    fun recordingAccess(): RecordingAccess {
        val owner = backend
        return RecordingAccess(dispatch = { command ->
            check(backend === owner && active) { "Recording owner changed." }
            recordingIoScope { owner.recordingCommand(command) }
        }, read = {
            check(backend === owner && active) { "Recording owner changed." }
            recordingIoScope { owner.recordingObservation() }
        })
    }

    suspend fun toggleRecording(recording: Boolean?): CameraStatus =
        if (recording == true) backend.stopRecording() else backend.startRecording()

    suspend fun tapFocus(x: Double, y: Double): FocusResult = backend.tapFocus(x, y)

    suspend fun clickWhiteBalance(x: Double, y: Double): CameraStatus = backend.clickWhiteBalance(x, y)

    suspend fun captureStill(autofocus: Boolean = true): CameraStatus = backend.captureStill(autofocus)

    suspend fun startBulbExposure(): CameraStatus = backend.startBulbExposure()

    suspend fun stopBulbExposure(): CameraStatus = backend.stopBulbExposure()

    suspend fun retryShutterRelease() = connectionMutex.withLock {
        check(active) { "Camera is disconnected." }
        backend.retryShutterRelease()
    }

    suspend fun autofocus(): CameraStatus = connectionMutex.withLock {
        check(active) { "Camera is disconnected." }
        backend.autofocus()
    }

    suspend fun holdAutofocus(whileHeld: suspend () -> Unit) = connectionMutex.withLock {
        check(active) { "Camera is disconnected." }
        backend.holdAutofocus(whileHeld)
    }

    suspend fun retryAutofocusStop() = connectionMutex.withLock {
        check(active) { "Camera is disconnected." }
        backend.retryAutofocusStop()
    }

    suspend fun halfPressShutter(): CameraStatus = connectionMutex.withLock {
        check(active) { "Camera is disconnected." }
        backend.halfPressShutter()
    }

    suspend fun driveFocus(
        direction: FocusDriveDirection,
        step: FocusDriveStep,
    ): FocusDriveResult = backend.driveFocus(direction, step)

    suspend fun setLiveViewMagnification(
        magnification: LiveViewMagnification,
    ): LiveViewMagnificationResult = backend.setLiveViewMagnification(magnification)

    suspend fun listMedia(
        maximumItems: Int? = null,
        onProgress: (List<CameraMediaItem>) -> Unit = {},
    ): List<CameraMediaItem> = backend.listMedia(maximumItems, onProgress)

    suspend fun listMediaIdentities(maximumItems: Int): CameraMediaInventory =
        backend.listMediaIdentities(maximumItems)

    suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail = backend.mediaThumbnail(item)

    suspend fun mediaPreview(item: CameraMediaItem): CameraMediaPreview = backend.mediaPreview(item)

    suspend fun openMediaStream(item: CameraMediaItem): CameraMediaStreamSource = backend.openMediaStream(item)

    suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaDownloadResult = backend.downloadMedia(item, destination, onProgress)

    suspend fun downloadMediaSingleAttempt(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaDownloadResult = backend.downloadMediaSingleAttempt(item, destination, onProgress)

    suspend fun uploadMedia(
        name: String,
        sizeBytes: Long,
        contentType: String?,
        source: InputStream,
        evidence: MediaUploadEvidence = MediaUploadEvidence(),
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaUploadResult = backend.uploadMedia(name, sizeBytes, contentType, source, evidence, onProgress)

    suspend fun mediaInfo(item: CameraMediaItem): CameraMediaItem = backend.mediaInfo(item)

    suspend fun setMediaProtection(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        backend.setMediaProtection(item, enabled)

    suspend fun setMediaArchived(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        backend.setMediaArchived(item, enabled)

    suspend fun setMediaRating(item: CameraMediaItem, rating: Int): CameraMediaItem =
        backend.setMediaRating(item, rating)

    suspend fun setMediaRotation(item: CameraMediaItem, degrees: Int): CameraMediaItem =
        backend.setMediaRotation(item, degrees)

    suspend fun deleteMedia(item: CameraMediaItem) = backend.deleteMedia(item)
    suspend fun deleteMedia(item: CameraMediaItem, evidence: MediaDeleteEvidence) = backend.deleteMedia(item, evidence)

    suspend fun restartLiveView(): LiveViewRequest = setLiveViewEnabled(true, restart = true)

    suspend fun setLiveViewEnabled(enabled: Boolean, restart: Boolean = false): LiveViewRequest =
        connectionMutex.withLock {
            check(active) { "Camera is not connected." }
            if ((liveViewRunning || backend.liveViewStopRequired) && (!enabled || restart)) {
                // Retain ownership on failure so a later stop/disconnect can retry cleanup.
                liveViewRunning = false
                backend.stopLiveView()
            }
            if (enabled && !liveViewRunning) {
                check(!backend.liveViewStopRequired) { "Live View must be stopped before starting again." }
                liveViewRunning = startOwnedLiveView()
                liveViewRequest = liveViewRequest.clampTo(backend.capabilities().liveView)
            }
            liveViewRequest
        }

    fun updateLiveViewRequest(
        fps: Int? = null,
        size: LiveViewSize? = null,
        source: LiveViewSource? = null,
    ) {
        liveViewRequest = liveViewRequest.copy(
            fps = fps ?: liveViewRequest.fps,
            size = size ?: liveViewRequest.size,
            source = source ?: liveViewRequest.source,
        )
        backend.nativeLiveViewSession?.setTargetFps(liveViewRequest.fps)
    }

    fun setNativeLiveViewRenderingEnabled(enabled: Boolean) {
        backend.nativeLiveViewSession?.setRenderingEnabled(enabled)
    }

    fun nextLiveViewFrameUrl(): String = backend.liveViewFrameUrl(++frameVersion, liveViewRequest)

    suspend fun fetchLiveViewFrame(): LiveViewFrame = backend.liveViewFrame(++frameVersion, liveViewRequest)

    suspend fun fetchLiveViewFocusInfo(): CameraFocusInfo? =
        if (active && liveViewRunning) backend.liveViewFocusInfo() else null

    companion object {
        const val DEFAULT_CAMERA_BASE_URL = "http://192.168.1.2:8080"
        const val DEFAULT_CAMERA_HTTPS_URL = "https://192.168.1.2:443"
        const val DEV_EMULATOR_SIMULATOR_URL = "http://10.0.2.2:18080"
        const val DEFAULT_DESKTOP_BRIDGE_URL = "http://10.0.2.2:18181"
    }
}

data class CameraSession(
    val transport: CameraTransport,
    val connection: CameraConnection,
    val info: CameraInfo,
    val status: CameraStatus,
    val capabilities: CameraCapabilities,
    val networkDiagnostics: CameraNetworkDiagnostics = CameraNetworkDiagnostics.Empty,
    val liveViewFrameUrl: String?,
    val liveViewRequest: LiveViewRequest,
    val activeLiveViewSource: LiveViewSource? = null,
    val nativeLiveViewSession: NativeLiveViewSession? = null,
    val liveViewStartError: String? = null,
)

private fun CameraCapabilities.forCamera(info: CameraInfo): CameraCapabilities =
    copy(profile = CameraProfile.fromModelName(info.model))
