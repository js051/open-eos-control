package dev.openeos.control.data

import android.content.Context

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

    fun isRealCamera(): Boolean = backend.prefersBitmapLiveViewFrames

    fun configureAndroidNetworkRouting(context: Context) {
        check(!active) { "Camera network routing cannot change while connected." }
        backendFactory = CameraBackendFactory(AndroidCameraHttpTransportFactory(context.applicationContext))
    }

    suspend fun connect(
        baseUrl: String,
        username: String = "",
        password: String = "",
        request: LiveViewRequest = liveViewRequest,
    ): CameraSession {
        if (active) disconnect()
        val connection = CameraConnection.CcapiNetwork(
            baseUrl = baseUrl,
            username = username,
            password = password,
        )
        try {
            backend = backendFactory.create(connection)
            backend.initialize()
            active = true
            liveViewRequest = request
            try {
                backend.startLiveView(liveViewRequest)
            } catch (e: Exception) {
                // A session can still provide settings and status without live view.
            }
            frameVersion = 0L
            val info = backend.info()
            val status = backend.status()
            val capabilities = backend.capabilities().forCamera(info)
            return CameraSession(
                transport = backend.transport,
                connection = backend.connection,
                info = info,
                status = status,
                capabilities = capabilities,
                networkDiagnostics = backend.networkDiagnostics,
                liveViewFrameUrl = nextLiveViewFrameUrl(),
            )
        } catch (exception: Exception) {
            if (active) {
                try {
                    backend.stopLiveView()
                } catch (_: Exception) {
                    // Keep the original connection failure.
                }
            }
            active = false
            throw exception
        }
    }

    suspend fun disconnect() {
        if (!active) return
        try {
            backend.stopLiveView()
        } catch (e: Exception) {
            // ignore failure to stop live view
        } finally {
            active = false
        }
    }

    suspend fun refreshStatus(): CameraStatus = backend.status()

    suspend fun setIso(value: String): CameraStatus = backend.setExposure(iso = value)

    suspend fun setShutter(value: String): CameraStatus = backend.setExposure(shutter = value)

    suspend fun setAperture(value: String): CameraStatus = backend.setExposure(aperture = value)

    suspend fun setWhiteBalance(value: String): CameraStatus = backend.setWhiteBalance(value)

    suspend fun setCameraSetting(key: String, value: String): CameraStatus = backend.setSetting(key, value)

    suspend fun refreshCapabilities(): CameraCapabilities = backend.capabilities()

    suspend fun toggleRecording(recording: Boolean?): CameraStatus =
        if (recording == true) backend.stopRecording() else backend.startRecording()

    suspend fun tapFocus(x: Double, y: Double): FocusResult = backend.tapFocus(x, y)

    suspend fun captureStill(): CameraStatus = backend.captureStill()

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
    }

    fun nextLiveViewFrameUrl(): String = backend.liveViewFrameUrl(++frameVersion, liveViewRequest)

    suspend fun fetchLiveViewFrame(): LiveViewFrame = backend.liveViewFrame(++frameVersion, liveViewRequest)

    companion object {
        const val DEFAULT_CAMERA_BASE_URL = "http://192.168.1.2:8080"
        const val DEFAULT_CAMERA_HTTPS_URL = "https://192.168.1.2:443"
        const val DEV_EMULATOR_SIMULATOR_URL = "http://10.0.2.2:18080"
    }
}

data class CameraSession(
    val transport: CameraTransport,
    val connection: CameraConnection,
    val info: CameraInfo,
    val status: CameraStatus,
    val capabilities: CameraCapabilities,
    val networkDiagnostics: CameraNetworkDiagnostics = CameraNetworkDiagnostics.Empty,
    val liveViewFrameUrl: String,
)

private fun CameraCapabilities.forCamera(info: CameraInfo): CameraCapabilities =
    copy(profile = CameraProfile.fromModelName(info.model))
