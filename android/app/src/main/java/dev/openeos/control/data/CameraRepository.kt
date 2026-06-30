package dev.openeos.control.data

class CameraRepository {
    private var client = CcapiClient(DEFAULT_CAMERA_BASE_URL)
    private var frameVersion = 0L

    suspend fun connect(baseUrl: String): CameraSession {
        client = CcapiClient(baseUrl)
        frameVersion = 0L
        return CameraSession(
            info = client.info(),
            status = client.status(),
            capabilities = client.capabilities(),
            liveViewFrameUrl = nextLiveViewFrameUrl(),
        )
    }

    suspend fun refreshStatus(): CameraStatus = client.status()

    suspend fun setIso(value: String): CameraStatus = client.setExposure(iso = value)

    suspend fun setShutter(value: String): CameraStatus = client.setExposure(shutter = value)

    suspend fun setAperture(value: String): CameraStatus = client.setExposure(aperture = value)

    suspend fun setWhiteBalance(value: String): CameraStatus = client.setWhiteBalance(value)

    suspend fun toggleRecording(recording: Boolean): CameraStatus =
        if (recording) client.stopRecording() else client.startRecording()

    suspend fun tapFocus(x: Double, y: Double): FocusResult = client.tapFocus(x, y)

    fun nextLiveViewFrameUrl(): String = client.liveViewFrameUrl(++frameVersion)

    companion object {
        const val DEFAULT_CAMERA_BASE_URL = "http://192.168.0.1:8080"
        const val DEV_EMULATOR_SIMULATOR_URL = "http://10.0.2.2:18080"
    }
}

data class CameraSession(
    val info: CameraInfo,
    val status: CameraStatus,
    val capabilities: CameraCapabilities,
    val liveViewFrameUrl: String,
)
