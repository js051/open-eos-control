package dev.openeos.control.data

enum class CameraTransport(
    val label: String,
) {
    CCAPI_NETWORK("Canon CCAPI over network"),
    USB_PTP("USB PTP"),
    DESKTOP_BRIDGE("Desktop bridge"),
}

sealed interface CameraConnection {
    val transport: CameraTransport

    data class CcapiNetwork(
        val baseUrl: String,
    ) : CameraConnection {
        override val transport: CameraTransport = CameraTransport.CCAPI_NETWORK
    }
}

interface CameraControlBackend {
    val transport: CameraTransport
    val prefersBitmapLiveViewFrames: Boolean

    suspend fun initialize()
    suspend fun info(): CameraInfo
    suspend fun status(): CameraStatus
    suspend fun capabilities(): CameraCapabilities
    suspend fun startLiveView()
    suspend fun stopLiveView()
    suspend fun setExposure(iso: String? = null, shutter: String? = null, aperture: String? = null): CameraStatus
    suspend fun setWhiteBalance(value: String): CameraStatus
    suspend fun setSetting(key: String, value: String): CameraStatus
    suspend fun startRecording(): CameraStatus
    suspend fun stopRecording(): CameraStatus
    suspend fun tapFocus(x: Double, y: Double): FocusResult
    fun liveViewFrameUrl(cacheKey: Long): String
    suspend fun liveViewFrame(cacheKey: Long): LiveViewFrame
}

class CcapiCameraBackend(
    baseUrl: String,
) : CameraControlBackend {
    private val client = CcapiClient(baseUrl)

    override val transport: CameraTransport = CameraTransport.CCAPI_NETWORK

    override val prefersBitmapLiveViewFrames: Boolean
        get() = client.isRealCamera

    override suspend fun initialize() = client.initialize()

    override suspend fun info(): CameraInfo = client.info()

    override suspend fun status(): CameraStatus = client.status()

    override suspend fun capabilities(): CameraCapabilities = client.capabilities()

    override suspend fun startLiveView() = client.startLiveView()

    override suspend fun stopLiveView() = client.stopLiveView()

    override suspend fun setExposure(iso: String?, shutter: String?, aperture: String?): CameraStatus =
        client.setExposure(iso = iso, shutter = shutter, aperture = aperture)

    override suspend fun setWhiteBalance(value: String): CameraStatus = client.setWhiteBalance(value)

    override suspend fun setSetting(key: String, value: String): CameraStatus = client.setSetting(key, value)

    override suspend fun startRecording(): CameraStatus = client.startRecording()

    override suspend fun stopRecording(): CameraStatus = client.stopRecording()

    override suspend fun tapFocus(x: Double, y: Double): FocusResult = client.tapFocus(x, y)

    override fun liveViewFrameUrl(cacheKey: Long): String = client.liveViewFrameUrl(cacheKey)

    override suspend fun liveViewFrame(cacheKey: Long): LiveViewFrame = client.liveViewFrame(cacheKey)
}

class CameraBackendFactory {
    fun create(connection: CameraConnection): CameraControlBackend =
        when (connection) {
            is CameraConnection.CcapiNetwork -> CcapiCameraBackend(connection.baseUrl)
        }
}
