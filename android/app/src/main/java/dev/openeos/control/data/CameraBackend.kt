package dev.openeos.control.data

import java.io.OutputStream

enum class CameraTransport(
    val label: String,
) {
    CCAPI_NETWORK("Canon CCAPI over network"),
    USB_PTP("USB PTP"),
    DESKTOP_BRIDGE("Desktop bridge"),
}

enum class CameraHostPlatform {
    ANDROID,
    IOS,
    WINDOWS,
    MACOS,
    LINUX,
    UNKNOWN,
}

sealed interface CameraConnection {
    val transport: CameraTransport
    val platform: CameraHostPlatform

    data class CcapiNetwork(
        val baseUrl: String,
        val username: String = "",
        val password: String = "",
        override val platform: CameraHostPlatform = CameraHostPlatform.ANDROID,
    ) : CameraConnection {
        override val transport: CameraTransport = CameraTransport.CCAPI_NETWORK
    }

    data class AndroidUsbPtp(
        val deviceName: String? = null,
        val vendorId: Int = CANON_USB_VENDOR_ID,
        val productId: Int? = null,
    ) : CameraConnection {
        override val transport: CameraTransport = CameraTransport.USB_PTP
        override val platform: CameraHostPlatform = CameraHostPlatform.ANDROID
    }

    data class DesktopBridge(
        val baseUrl: String,
        override val platform: CameraHostPlatform = CameraHostPlatform.UNKNOWN,
    ) : CameraConnection {
        override val transport: CameraTransport = CameraTransport.DESKTOP_BRIDGE
    }
}

interface CameraControlBackend {
    val transport: CameraTransport
    val connection: CameraConnection
    val prefersBitmapLiveViewFrames: Boolean
    val networkDiagnostics: CameraNetworkDiagnostics

    suspend fun initialize()
    suspend fun info(): CameraInfo
    suspend fun status(): CameraStatus
    suspend fun capabilities(): CameraCapabilities
    suspend fun startLiveView(request: LiveViewRequest = LiveViewRequest())
    suspend fun stopLiveView()
    suspend fun setExposure(iso: String? = null, shutter: String? = null, aperture: String? = null): CameraStatus
    suspend fun setWhiteBalance(value: String): CameraStatus
    suspend fun setSetting(key: String, value: String): CameraStatus
    suspend fun startRecording(): CameraStatus
    suspend fun stopRecording(): CameraStatus
    suspend fun tapFocus(x: Double, y: Double): FocusResult
    suspend fun captureStill(): CameraStatus = unsupported(CameraFeature.STILL_CAPTURE)
    suspend fun halfPressShutter(): CameraStatus = unsupported(CameraFeature.SHUTTER_HALF_PRESS)
    suspend fun driveFocus(direction: FocusDriveDirection, step: FocusDriveStep): FocusResult = unsupported(CameraFeature.FOCUS_DRIVE)
    suspend fun listMedia(): List<CameraMediaItem> = unsupported(CameraFeature.MEDIA_BROWSER)
    suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaDownloadResult = unsupported(CameraFeature.MEDIA_DOWNLOAD)
    fun liveViewFrameUrl(cacheKey: Long, request: LiveViewRequest = LiveViewRequest()): String
    suspend fun liveViewFrame(cacheKey: Long, request: LiveViewRequest = LiveViewRequest()): LiveViewFrame
}

class CcapiCameraBackend(
    override val connection: CameraConnection.CcapiNetwork,
    httpTransportFactory: CameraHttpTransportFactory = DefaultCameraHttpTransportFactory(),
) : CameraControlBackend {
    private val httpTransport = httpTransportFactory.create(connection.baseUrl)
    private val client = CcapiClient(
        baseUrl = connection.baseUrl,
        httpClient = httpTransport.client,
        username = connection.username,
        password = connection.password,
    )

    override val transport: CameraTransport = CameraTransport.CCAPI_NETWORK

    override val prefersBitmapLiveViewFrames: Boolean
        get() = client.isRealCamera

    override val networkDiagnostics: CameraNetworkDiagnostics = httpTransport.diagnostics

    override suspend fun initialize() = client.initialize()

    override suspend fun info(): CameraInfo = client.info()

    override suspend fun status(): CameraStatus = client.status()

    override suspend fun capabilities(): CameraCapabilities = client.capabilities()

    override suspend fun startLiveView(request: LiveViewRequest) = client.startLiveView(request)

    override suspend fun stopLiveView() = client.stopLiveView()

    override suspend fun setExposure(iso: String?, shutter: String?, aperture: String?): CameraStatus =
        client.setExposure(iso = iso, shutter = shutter, aperture = aperture)

    override suspend fun setWhiteBalance(value: String): CameraStatus = client.setWhiteBalance(value)

    override suspend fun setSetting(key: String, value: String): CameraStatus = client.setSetting(key, value)

    override suspend fun startRecording(): CameraStatus = client.startRecording()

    override suspend fun stopRecording(): CameraStatus = client.stopRecording()

    override suspend fun tapFocus(x: Double, y: Double): FocusResult = client.tapFocus(x, y)

    override suspend fun captureStill(): CameraStatus = client.captureStill()

    override suspend fun halfPressShutter(): CameraStatus = client.halfPressShutter()

    override suspend fun listMedia(): List<CameraMediaItem> = client.listMedia()

    override suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit,
    ): CameraMediaDownloadResult = client.downloadMedia(item, destination, onProgress)

    override fun liveViewFrameUrl(cacheKey: Long, request: LiveViewRequest): String = client.liveViewFrameUrl(cacheKey, request)

    override suspend fun liveViewFrame(cacheKey: Long, request: LiveViewRequest): LiveViewFrame = client.liveViewFrame(cacheKey, request)
}

class PlannedCameraBackend(
    override val connection: CameraConnection,
) : CameraControlBackend {
    override val transport: CameraTransport = connection.transport

    override val prefersBitmapLiveViewFrames: Boolean = false

    override val networkDiagnostics: CameraNetworkDiagnostics = CameraNetworkDiagnostics.Empty

    override suspend fun initialize() {
        throw UnsupportedOperationException("${transport.label} is planned but not implemented in the Android app yet.")
    }

    override suspend fun info(): CameraInfo = CameraInfo(
        connected = false,
        model = "Planned ${transport.label}",
        serial = "planned",
        api = transport.name.lowercase(),
    )

    override suspend fun status(): CameraStatus = CameraStatus(
        connected = false,
        batteryLevel = null,
        batteryStatus = "unknown",
        recording = null,
        mode = "planned",
        mediaAvailable = null,
        remainingMinutes = null,
        exposure = ExposureState(
            iso = "-",
            shutter = "-",
            aperture = "-",
            whiteBalance = "-",
        ),
    )

    override suspend fun capabilities(): CameraCapabilities {
        val matrix = when (transport) {
            CameraTransport.USB_PTP -> CapabilityMatrix.androidUsbPtpRoadmap()
            CameraTransport.DESKTOP_BRIDGE -> CapabilityMatrix.desktopBridgeRoadmap()
            CameraTransport.CCAPI_NETWORK -> CapabilityMatrix.ccapiNetwork()
        }
        return CameraCapabilities(
            iso = emptyList(),
            shutter = emptyList(),
            aperture = emptyList(),
            whiteBalance = emptyList(),
            matrix = matrix,
            liveView = LiveViewCapabilities(),
            profile = CameraProfile.R6_MARK_III,
        )
    }

    override suspend fun startLiveView(request: LiveViewRequest) = unsupported<Unit>(CameraFeature.LIVE_VIEW)

    override suspend fun stopLiveView() = unsupported<Unit>(CameraFeature.LIVE_VIEW)

    override suspend fun setExposure(iso: String?, shutter: String?, aperture: String?): CameraStatus =
        unsupported(CameraFeature.EXPOSURE_CONTROL)

    override suspend fun setWhiteBalance(value: String): CameraStatus =
        unsupported(CameraFeature.WHITE_BALANCE_CONTROL)

    override suspend fun setSetting(key: String, value: String): CameraStatus =
        unsupported(CameraFeature.ADVANCED_SETTINGS)

    override suspend fun startRecording(): CameraStatus = unsupported(CameraFeature.VIDEO_RECORDING)

    override suspend fun stopRecording(): CameraStatus = unsupported(CameraFeature.VIDEO_RECORDING)

    override suspend fun tapFocus(x: Double, y: Double): FocusResult = unsupported(CameraFeature.TAP_FOCUS)

    override fun liveViewFrameUrl(cacheKey: Long, request: LiveViewRequest): String =
        throw UnsupportedOperationException("${transport.label} live view is planned but not implemented yet.")

    override suspend fun liveViewFrame(cacheKey: Long, request: LiveViewRequest): LiveViewFrame =
        unsupported(CameraFeature.LIVE_VIEW)
}

class CameraBackendFactory(
    private val httpTransportFactory: CameraHttpTransportFactory = DefaultCameraHttpTransportFactory(),
) {
    fun create(connection: CameraConnection): CameraControlBackend =
        when (connection) {
            is CameraConnection.CcapiNetwork -> CcapiCameraBackend(connection, httpTransportFactory)
            is CameraConnection.AndroidUsbPtp -> PlannedCameraBackend(connection)
            is CameraConnection.DesktopBridge -> PlannedCameraBackend(connection)
        }
}

enum class FocusDriveDirection {
    NEAR,
    FAR,
}

enum class FocusDriveStep {
    SMALL,
    MEDIUM,
    LARGE,
}

fun <T> CameraControlBackend.unsupported(feature: CameraFeature): T {
    throw UnsupportedOperationException("${feature.label} is not supported by ${transport.label}.")
}

const val CANON_USB_VENDOR_ID = 0x04A9
