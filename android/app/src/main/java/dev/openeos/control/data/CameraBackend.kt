package dev.openeos.control.data

import java.io.InputStream
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
        val simulatorMode: Boolean? = null,
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
        val token: String = "",
        val cameraId: String? = null,
        val cameraEngine: String? = null,
        val profileHint: String? = CameraProfile.R6_MARK_III.modelName,
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
    val nativeLiveViewSession: NativeLiveViewSession?
        get() = null
    val activeLiveViewSource: LiveViewSource?
        get() = null

    fun observedFeatures(): Set<CameraFeature> = emptySet()

    suspend fun initialize()
    suspend fun close() = Unit
    suspend fun info(): CameraInfo
    suspend fun status(): CameraStatus
    suspend fun capabilities(): CameraCapabilities
    suspend fun pollEvent(): CameraEvent = unsupported(CameraFeature.EVENT_POLLING)
    suspend fun stopEventPolling() = Unit
    suspend fun startLiveView(request: LiveViewRequest = LiveViewRequest())
    suspend fun stopLiveView()
    suspend fun setExposure(iso: String? = null, shutter: String? = null, aperture: String? = null): CameraStatus
    suspend fun setWhiteBalance(value: String): CameraStatus
    suspend fun setSetting(key: String, value: String): CameraStatus
    suspend fun syncCameraClock(): CameraStatus = unsupported(CameraFeature.CAMERA_CLOCK_SYNC)
    suspend fun createDirectory(name: String): String = unsupported(CameraFeature.DIRECTORY_CONTROL)
    suspend fun setFileNaming(field: CameraFileNamingField, value: String): CameraFileNaming =
        unsupported(CameraFeature.FILE_NAMING_CONTROL)
    suspend fun cleanSensor(autoPowerOff: Boolean) = unsupported<Unit>(CameraFeature.SENSOR_CLEANING)
    suspend fun sleepCamera() = unsupported<Unit>(CameraFeature.CAMERA_SLEEP)
    suspend fun startRecording(): CameraStatus
    suspend fun stopRecording(): CameraStatus
    suspend fun tapFocus(x: Double, y: Double): FocusResult
    suspend fun clickWhiteBalance(x: Double, y: Double): CameraStatus =
        unsupported(CameraFeature.CLICK_WHITE_BALANCE)
    suspend fun captureStill(): CameraStatus = unsupported(CameraFeature.STILL_CAPTURE)
    suspend fun startBulbExposure(): CameraStatus = unsupported(CameraFeature.BULB_EXPOSURE)
    suspend fun stopBulbExposure(): CameraStatus = unsupported(CameraFeature.BULB_EXPOSURE)
    suspend fun autofocus(): CameraStatus = unsupported(CameraFeature.AUTOFOCUS)
    suspend fun halfPressShutter(): CameraStatus = unsupported(CameraFeature.SHUTTER_HALF_PRESS)
    suspend fun driveFocus(
        direction: FocusDriveDirection,
        step: FocusDriveStep,
    ): FocusDriveResult = unsupported(CameraFeature.FOCUS_DRIVE)
    suspend fun setLiveViewMagnification(
        magnification: LiveViewMagnification,
    ): LiveViewMagnificationResult = unsupported(CameraFeature.LIVE_VIEW_MAGNIFICATION)
    suspend fun listMedia(
        maximumItems: Int? = null,
        onProgress: (List<CameraMediaItem>) -> Unit = {},
    ): List<CameraMediaItem> =
        unsupported(CameraFeature.MEDIA_BROWSER)
    suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail =
        unsupported(CameraFeature.MEDIA_THUMBNAIL)
    suspend fun mediaPreview(item: CameraMediaItem): CameraMediaPreview =
        unsupported(CameraFeature.MEDIA_PREVIEW)
    suspend fun openMediaStream(item: CameraMediaItem): CameraMediaStreamSource =
        unsupported(CameraFeature.MEDIA_DOWNLOAD)
    suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaDownloadResult = unsupported(CameraFeature.MEDIA_DOWNLOAD)
    suspend fun uploadMedia(
        name: String,
        sizeBytes: Long,
        contentType: String?,
        source: InputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit = {},
    ): CameraMediaUploadResult = unsupported(CameraFeature.MEDIA_UPLOAD)
    suspend fun mediaInfo(item: CameraMediaItem): CameraMediaItem = unsupported(CameraFeature.MEDIA_BROWSER)
    suspend fun setMediaProtection(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        unsupported(CameraFeature.MEDIA_PROTECT)
    suspend fun setMediaArchived(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        unsupported(CameraFeature.MEDIA_ARCHIVE)
    suspend fun setMediaRating(item: CameraMediaItem, rating: Int): CameraMediaItem =
        unsupported(CameraFeature.MEDIA_RATING)
    suspend fun setMediaRotation(item: CameraMediaItem, degrees: Int): CameraMediaItem =
        unsupported(CameraFeature.MEDIA_ROTATE)
    suspend fun deleteMedia(item: CameraMediaItem) = unsupported<Unit>(CameraFeature.MEDIA_DELETE)
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
        treatAsSimulator = connection.simulatorMode,
        rtpDestinationAddress = httpTransport.rtpDestinationAddress,
        rtpSessionFactory = httpTransport.rtpSessionFactory,
    )

    override val transport: CameraTransport = CameraTransport.CCAPI_NETWORK

    override val prefersBitmapLiveViewFrames: Boolean
        get() = client.isRealCamera

    override val networkDiagnostics: CameraNetworkDiagnostics
        get() = httpTransport.diagnosticsProvider()

    override val nativeLiveViewSession: NativeLiveViewSession?
        get() = client.nativeLiveViewSession

    override val activeLiveViewSource: LiveViewSource?
        get() = client.currentLiveViewSource()

    override fun observedFeatures(): Set<CameraFeature> = client.observedFeatureSnapshot()

    override suspend fun initialize() = client.initialize()

    override suspend fun close() = client.close()

    override suspend fun info(): CameraInfo = client.info()

    override suspend fun status(): CameraStatus = client.status()

    override suspend fun capabilities(): CameraCapabilities = client.capabilities()

    override suspend fun pollEvent(): CameraEvent = client.pollEvent()

    override suspend fun stopEventPolling() = client.stopEventPolling()

    override suspend fun startLiveView(request: LiveViewRequest) = client.startLiveView(request)

    override suspend fun stopLiveView() = client.stopLiveView()

    override suspend fun setExposure(iso: String?, shutter: String?, aperture: String?): CameraStatus =
        client.setExposure(iso = iso, shutter = shutter, aperture = aperture)

    override suspend fun setWhiteBalance(value: String): CameraStatus = client.setWhiteBalance(value)

    override suspend fun setSetting(key: String, value: String): CameraStatus = client.setSetting(key, value)

    override suspend fun syncCameraClock(): CameraStatus = client.syncCameraClock()

    override suspend fun createDirectory(name: String): String = client.createDirectory(name)

    override suspend fun setFileNaming(field: CameraFileNamingField, value: String): CameraFileNaming =
        client.setFileNaming(field, value)

    override suspend fun cleanSensor(autoPowerOff: Boolean) = client.cleanSensor(autoPowerOff)

    override suspend fun sleepCamera() = client.sleepCamera()

    override suspend fun startRecording(): CameraStatus = client.startRecording()

    override suspend fun stopRecording(): CameraStatus = client.stopRecording()

    override suspend fun tapFocus(x: Double, y: Double): FocusResult = client.tapFocus(x, y)

    override suspend fun clickWhiteBalance(x: Double, y: Double): CameraStatus =
        client.clickWhiteBalance(x, y)

    override suspend fun captureStill(): CameraStatus = client.captureStill()

    override suspend fun startBulbExposure(): CameraStatus = client.startBulbExposure()

    override suspend fun stopBulbExposure(): CameraStatus = client.stopBulbExposure()

    override suspend fun autofocus(): CameraStatus = client.autofocus()

    override suspend fun halfPressShutter(): CameraStatus = client.halfPressShutter()

    override suspend fun driveFocus(direction: FocusDriveDirection, step: FocusDriveStep): FocusDriveResult =
        client.driveFocus(direction, step)

    override suspend fun setLiveViewMagnification(
        magnification: LiveViewMagnification,
    ): LiveViewMagnificationResult = client.setLiveViewMagnification(magnification)

    override suspend fun listMedia(
        maximumItems: Int?,
        onProgress: (List<CameraMediaItem>) -> Unit,
    ): List<CameraMediaItem> = client.listMedia(maximumItems, onProgress)

    override suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail = client.mediaThumbnail(item)

    override suspend fun mediaPreview(item: CameraMediaItem): CameraMediaPreview = client.mediaPreview(item)

    override suspend fun openMediaStream(item: CameraMediaItem): CameraMediaStreamSource =
        client.openMediaStream(item)

    override suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit,
    ): CameraMediaDownloadResult = client.downloadMedia(item, destination, onProgress)

    override suspend fun uploadMedia(
        name: String,
        sizeBytes: Long,
        contentType: String?,
        source: InputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit,
    ): CameraMediaUploadResult = client.uploadMedia(name, sizeBytes, contentType, source, onProgress)

    override suspend fun mediaInfo(item: CameraMediaItem): CameraMediaItem = client.mediaInfo(item)

    override suspend fun setMediaProtection(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        client.setMediaProtection(item, enabled)

    override suspend fun setMediaArchived(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        client.setMediaArchived(item, enabled)

    override suspend fun setMediaRating(item: CameraMediaItem, rating: Int): CameraMediaItem =
        client.setMediaRating(item, rating)

    override suspend fun setMediaRotation(item: CameraMediaItem, degrees: Int): CameraMediaItem =
        client.setMediaRotation(item, degrees)

    override suspend fun deleteMedia(item: CameraMediaItem) = client.deleteMedia(item)

    override fun liveViewFrameUrl(cacheKey: Long, request: LiveViewRequest): String = client.liveViewFrameUrl(cacheKey, request)

    override suspend fun liveViewFrame(cacheKey: Long, request: LiveViewRequest): LiveViewFrame = client.liveViewFrame(cacheKey, request)
}

class DesktopBridgeCameraBackend(
    override val connection: CameraConnection.DesktopBridge,
    httpTransportFactory: CameraHttpTransportFactory = DefaultCameraHttpTransportFactory(),
) : CameraControlBackend {
    private val httpTransport = httpTransportFactory.create(connection.baseUrl)
    private val client = DesktopBridgeClient(
        baseUrl = connection.baseUrl,
        httpClient = httpTransport.client,
        token = connection.token,
        cameraId = connection.cameraId,
        cameraEngine = connection.cameraEngine,
        profileHint = connection.profileHint,
    )

    override val transport: CameraTransport = CameraTransport.DESKTOP_BRIDGE

    override val prefersBitmapLiveViewFrames: Boolean = true

    override val networkDiagnostics: CameraNetworkDiagnostics
        get() = httpTransport.diagnosticsProvider()

    override fun observedFeatures(): Set<CameraFeature> = client.observedFeatureSnapshot()

    override suspend fun initialize() = client.initialize()

    override suspend fun close() = client.close()

    override suspend fun info(): CameraInfo = client.info()

    override suspend fun status(): CameraStatus = client.status()

    override suspend fun capabilities(): CameraCapabilities = client.capabilities()

    override suspend fun pollEvent(): CameraEvent = client.pollEvent()

    override suspend fun stopEventPolling() = client.stopEventPolling()

    override suspend fun startLiveView(request: LiveViewRequest) = client.startLiveView(request)

    override suspend fun stopLiveView() = client.stopLiveView()

    override suspend fun setExposure(iso: String?, shutter: String?, aperture: String?): CameraStatus =
        client.setExposure(iso = iso, shutter = shutter, aperture = aperture)

    override suspend fun setWhiteBalance(value: String): CameraStatus = client.setWhiteBalance(value)

    override suspend fun setSetting(key: String, value: String): CameraStatus = client.setSetting(key, value)

    override suspend fun syncCameraClock(): CameraStatus = client.syncCameraClock()

    override suspend fun createDirectory(name: String): String = client.createDirectory(name)

    override suspend fun setFileNaming(field: CameraFileNamingField, value: String): CameraFileNaming =
        client.setFileNaming(field, value)

    override suspend fun cleanSensor(autoPowerOff: Boolean) = client.cleanSensor(autoPowerOff)

    override suspend fun sleepCamera() = client.sleepCamera()

    override suspend fun startRecording(): CameraStatus = client.startRecording()

    override suspend fun stopRecording(): CameraStatus = client.stopRecording()

    override suspend fun tapFocus(x: Double, y: Double): FocusResult = client.tapFocus(x, y)

    override suspend fun clickWhiteBalance(x: Double, y: Double): CameraStatus =
        client.clickWhiteBalance(x, y)

    override suspend fun captureStill(): CameraStatus = client.captureStill()

    override suspend fun startBulbExposure(): CameraStatus = client.startBulbExposure()

    override suspend fun stopBulbExposure(): CameraStatus = client.stopBulbExposure()

    override suspend fun autofocus(): CameraStatus = client.autofocus()

    override suspend fun halfPressShutter(): CameraStatus = client.halfPressShutter()

    override suspend fun driveFocus(direction: FocusDriveDirection, step: FocusDriveStep): FocusDriveResult =
        client.driveFocus(direction, step)

    override suspend fun setLiveViewMagnification(
        magnification: LiveViewMagnification,
    ): LiveViewMagnificationResult = client.setLiveViewMagnification(magnification)

    override suspend fun listMedia(
        maximumItems: Int?,
        onProgress: (List<CameraMediaItem>) -> Unit,
    ): List<CameraMediaItem> = client.listMedia()
        .let { items -> maximumItems?.let(items::take) ?: items }
        .also(onProgress)

    override suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail = client.mediaThumbnail(item)

    override suspend fun mediaPreview(item: CameraMediaItem): CameraMediaPreview = client.mediaPreview(item)

    override suspend fun openMediaStream(item: CameraMediaItem): CameraMediaStreamSource =
        client.openMediaStream(item)

    override suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit,
    ): CameraMediaDownloadResult = client.downloadMedia(item, destination, onProgress)

    override suspend fun uploadMedia(
        name: String,
        sizeBytes: Long,
        contentType: String?,
        source: InputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit,
    ): CameraMediaUploadResult = client.uploadMedia(name, sizeBytes, contentType, source, onProgress)

    override suspend fun mediaInfo(item: CameraMediaItem): CameraMediaItem = client.mediaInfo(item)

    override suspend fun setMediaProtection(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        client.setMediaProtection(item, enabled)

    override suspend fun setMediaArchived(item: CameraMediaItem, enabled: Boolean): CameraMediaItem =
        client.setMediaArchived(item, enabled)

    override suspend fun setMediaRating(item: CameraMediaItem, rating: Int): CameraMediaItem =
        client.setMediaRating(item, rating)

    override suspend fun setMediaRotation(item: CameraMediaItem, degrees: Int): CameraMediaItem =
        client.setMediaRotation(item, degrees)

    override suspend fun deleteMedia(item: CameraMediaItem) = client.deleteMedia(item)

    override fun liveViewFrameUrl(cacheKey: Long, request: LiveViewRequest): String = client.liveViewFrameUrl(cacheKey)

    override suspend fun liveViewFrame(cacheKey: Long, request: LiveViewRequest): LiveViewFrame =
        client.liveViewFrame(cacheKey)
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

    override suspend fun clickWhiteBalance(x: Double, y: Double): CameraStatus =
        unsupported(CameraFeature.CLICK_WHITE_BALANCE)

    override fun liveViewFrameUrl(cacheKey: Long, request: LiveViewRequest): String =
        throw UnsupportedOperationException("${transport.label} live view is planned but not implemented yet.")

    override suspend fun liveViewFrame(cacheKey: Long, request: LiveViewRequest): LiveViewFrame =
        unsupported(CameraFeature.LIVE_VIEW)
}

class CameraBackendFactory(
    private val httpTransportFactory: CameraHttpTransportFactory = DefaultCameraHttpTransportFactory(),
    private val ptpTransportFactory: PtpTransportFactory? = null,
    private val usbHostCaptureStore: UsbHostCaptureStore? = null,
) {
    fun create(connection: CameraConnection): CameraControlBackend =
        when (connection) {
            is CameraConnection.CcapiNetwork -> CcapiCameraBackend(connection, httpTransportFactory)
            is CameraConnection.AndroidUsbPtp -> ptpTransportFactory
                ?.let { UsbPtpCameraBackend(connection, it, usbHostCaptureStore) }
                ?: PlannedCameraBackend(connection)
            is CameraConnection.DesktopBridge -> DesktopBridgeCameraBackend(connection, httpTransportFactory)
        }

    suspend fun discoverDesktopBridge(connection: CameraConnection.DesktopBridge): List<DesktopBridgeCamera> {
        val httpTransport = httpTransportFactory.create(connection.baseUrl)
        return DesktopBridgeClient(
            baseUrl = connection.baseUrl,
            httpClient = httpTransport.client,
            token = connection.token,
            cameraId = connection.cameraId,
            cameraEngine = connection.cameraEngine,
            profileHint = connection.profileHint,
        ).discoverCameras()
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
