package dev.openeos.control.data

class CameraRepository(
    private val backendFactory: CameraBackendFactory = CameraBackendFactory(),
) {
    private var backend: CameraControlBackend = backendFactory.create(
        CameraConnection.CcapiNetwork(DEFAULT_CAMERA_BASE_URL)
    )
    private var frameVersion = 0L

    fun isRealCamera(): Boolean = backend.prefersBitmapLiveViewFrames

    suspend fun connect(baseUrl: String): CameraSession {
        val connection = CameraConnection.CcapiNetwork(baseUrl)
        backend = backendFactory.create(connection)
        backend.initialize()
        try {
            backend.startLiveView()
        } catch (e: Exception) {
            // ignore failure to start live view
        }
        frameVersion = 0L
        return CameraSession(
            transport = backend.transport,
            info = backend.info(),
            status = backend.status(),
            capabilities = backend.capabilities(),
            liveViewFrameUrl = nextLiveViewFrameUrl(),
        )
    }

    suspend fun disconnect() {
        try {
            backend.stopLiveView()
        } catch (e: Exception) {
            // ignore failure to stop live view
        }
    }

    suspend fun refreshStatus(): CameraStatus = backend.status()

    suspend fun setIso(value: String): CameraStatus = backend.setExposure(iso = value)

    suspend fun setShutter(value: String): CameraStatus = backend.setExposure(shutter = value)

    suspend fun setAperture(value: String): CameraStatus = backend.setExposure(aperture = value)

    suspend fun setWhiteBalance(value: String): CameraStatus = backend.setWhiteBalance(value)

    suspend fun setCameraSetting(key: String, value: String): CameraStatus = backend.setSetting(key, value)

    suspend fun refreshCapabilities(): CameraCapabilities = backend.capabilities()

    suspend fun toggleRecording(recording: Boolean): CameraStatus =
        if (recording) backend.stopRecording() else backend.startRecording()

    suspend fun tapFocus(x: Double, y: Double): FocusResult = backend.tapFocus(x, y)

    fun nextLiveViewFrameUrl(): String = backend.liveViewFrameUrl(++frameVersion)

    suspend fun fetchLiveViewFrame(): LiveViewFrame = backend.liveViewFrame(++frameVersion)

    companion object {
        const val DEFAULT_CAMERA_BASE_URL = "http://192.168.1.2:8080"
        const val DEFAULT_CAMERA_HTTPS_URL = "https://192.168.1.2:443"
        const val DEV_EMULATOR_SIMULATOR_URL = "http://10.0.2.2:18080"
    }
}

data class CameraSession(
    val transport: CameraTransport,
    val info: CameraInfo,
    val status: CameraStatus,
    val capabilities: CameraCapabilities,
    val liveViewFrameUrl: String,
)
