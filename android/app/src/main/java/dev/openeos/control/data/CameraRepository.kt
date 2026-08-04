package dev.openeos.control.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream

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
    private var activeInfo: CameraInfo? = null
    private val connectionMutex = Mutex()

    fun isRealCamera(): Boolean = backend.prefersBitmapLiveViewFrames

    fun nativeLiveViewSession(): NativeLiveViewSession? = backend.nativeLiveViewSession

    fun configureAndroidNetworkRouting(context: Context) {
        check(!active) { "Camera network routing cannot change while connected." }
        backendFactory = CameraBackendFactory(
            httpTransportFactory = AndroidCameraHttpTransportFactory(context.applicationContext),
            ptpTransportFactory = AndroidUsbPtpTransportFactory(context.applicationContext),
            usbHostCaptureStore = AndroidUsbHostCaptureStore(context.applicationContext),
        )
    }

    suspend fun connect(
        baseUrl: String,
        username: String = "",
        password: String = "",
        simulatorMode: Boolean? = null,
        request: LiveViewRequest = liveViewRequest,
    ): CameraSession = connect(
        connection = CameraConnection.CcapiNetwork(
            baseUrl = baseUrl,
            username = username,
            password = password,
            simulatorMode = simulatorMode,
        ),
        request = request,
    )

    suspend fun connectUsb(
        deviceName: String,
        vendorId: Int,
        productId: Int,
        request: LiveViewRequest = liveViewRequest,
    ): CameraSession = connect(
        connection = CameraConnection.AndroidUsbPtp(
            deviceName = deviceName,
            vendorId = vendorId,
            productId = productId,
        ),
        request = request,
    )

    suspend fun discoverBridgeCameras(
        baseUrl: String,
        token: String = "",
    ): List<DesktopBridgeCamera> = backendFactory.discoverDesktopBridge(
        CameraConnection.DesktopBridge(baseUrl = baseUrl, token = token)
    )

    suspend fun connectBridge(
        baseUrl: String,
        token: String = "",
        cameraId: String? = null,
        request: LiveViewRequest = liveViewRequest,
    ): CameraSession = connect(
        connection = CameraConnection.DesktopBridge(
            baseUrl = baseUrl,
            token = token,
            cameraId = cameraId,
        ),
        request = request,
    )

    private suspend fun connect(
        connection: CameraConnection,
        request: LiveViewRequest,
    ): CameraSession = connectionMutex.withLock {
        if (active) disconnectLocked()
        try {
            backend = backendFactory.create(connection)
            backend.initialize()
            active = true
            frameVersion = 0L
            val info = backend.info()
            activeInfo = info
            val status = backend.status()
            val capabilities = backend.capabilities().forCamera(info)
            liveViewRequest = request.clampTo(capabilities.liveView)
            var liveViewFrameUrl: String? = null
            var liveViewStartError: String? = null
            if (capabilities.matrix.supports(CameraFeature.LIVE_VIEW)) {
                try {
                    backend.startLiveView(liveViewRequest)
                    if (!backend.prefersBitmapLiveViewFrames) {
                        liveViewFrameUrl = nextLiveViewFrameUrl()
                    }
                } catch (exception: Exception) {
                    // A session can still provide settings and status without live view.
                    liveViewStartError = "${exception.javaClass.simpleName}: ${exception.message ?: "Live View start failed"}"
                }
            }
            CameraSession(
                transport = backend.transport,
                connection = backend.connection,
                info = info,
                status = status,
                capabilities = capabilities,
                networkDiagnostics = backend.networkDiagnostics,
                liveViewFrameUrl = liveViewFrameUrl,
                liveViewRequest = liveViewRequest,
                nativeLiveViewSession = backend.nativeLiveViewSession,
                liveViewStartError = liveViewStartError,
            )
        } catch (exception: Exception) {
            runCatching { backend.close() }
            active = false
            activeInfo = null
            throw exception
        }
    }

    suspend fun disconnect() = connectionMutex.withLock {
        disconnectLocked()
    }

    private suspend fun disconnectLocked() {
        if (!active) return
        try {
            try {
                backend.stopLiveView()
            } catch (_: Exception) {
                // A backend without Live View still needs its session closed.
            }
            backend.close()
        } catch (_: Exception) {
            // ignore failure to stop live view
        } finally {
            active = false
            activeInfo = null
        }
    }

    suspend fun refreshStatus(): CameraStatus = backend.status()

    suspend fun setIso(value: String): CameraStatus = backend.setExposure(iso = value)

    suspend fun setShutter(value: String): CameraStatus = backend.setExposure(shutter = value)

    suspend fun setAperture(value: String): CameraStatus = backend.setExposure(aperture = value)

    suspend fun setWhiteBalance(value: String): CameraStatus = backend.setWhiteBalance(value)

    suspend fun setCameraSetting(key: String, value: String): CameraStatus = backend.setSetting(key, value)

    suspend fun syncCameraClock(): CameraStatus = backend.syncCameraClock()

    suspend fun sleepCamera() = backend.sleepCamera()

    suspend fun refreshCapabilities(): CameraCapabilities = backend.capabilities().forCamera(
        activeInfo ?: backend.info().also { activeInfo = it }
    )

    suspend fun pollEvent(): CameraEvent = backend.pollEvent()

    suspend fun stopEventPolling() = backend.stopEventPolling()

    fun observedFeatures(): Set<CameraFeature> = backend.observedFeatures()

    fun refreshNetworkDiagnostics(): CameraNetworkDiagnostics = backend.networkDiagnostics

    suspend fun toggleRecording(recording: Boolean?): CameraStatus =
        if (recording == true) backend.stopRecording() else backend.startRecording()

    suspend fun tapFocus(x: Double, y: Double): FocusResult = backend.tapFocus(x, y)

    suspend fun clickWhiteBalance(x: Double, y: Double): CameraStatus = backend.clickWhiteBalance(x, y)

    suspend fun captureStill(): CameraStatus = backend.captureStill()

    suspend fun startBulbExposure(): CameraStatus = backend.startBulbExposure()

    suspend fun stopBulbExposure(): CameraStatus = backend.stopBulbExposure()

    suspend fun autofocus(): CameraStatus = backend.autofocus()

    suspend fun halfPressShutter(): CameraStatus = backend.halfPressShutter()

    suspend fun driveFocus(
        direction: FocusDriveDirection,
        step: FocusDriveStep,
    ): FocusDriveResult = backend.driveFocus(direction, step)

    suspend fun setLiveViewMagnification(
        magnification: LiveViewMagnification,
    ): LiveViewMagnificationResult = backend.setLiveViewMagnification(magnification)

    suspend fun listMedia(): List<CameraMediaItem> = backend.listMedia()

    suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail = backend.mediaThumbnail(item)

    suspend fun mediaPreview(item: CameraMediaItem): CameraMediaPreview = backend.mediaPreview(item)

    suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaDownloadResult = backend.downloadMedia(item, destination, onProgress)

    suspend fun deleteMedia(item: CameraMediaItem) = backend.deleteMedia(item)

    suspend fun restartLiveView() {
        backend.stopLiveView()
        backend.startLiveView(liveViewRequest)
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
    val nativeLiveViewSession: NativeLiveViewSession? = null,
    val liveViewStartError: String? = null,
)

private fun CameraCapabilities.forCamera(info: CameraInfo): CameraCapabilities =
    copy(profile = CameraProfile.fromModelName(info.model))
