package dev.openeos.control.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.Locale

class UsbPtpCameraBackend(
    override val connection: CameraConnection.AndroidUsbPtp,
    private val transportFactory: PtpTransportFactory,
) : CameraControlBackend {
    override val transport: CameraTransport = CameraTransport.USB_PTP
    override val prefersBitmapLiveViewFrames: Boolean = false
    override val networkDiagnostics: CameraNetworkDiagnostics = CameraNetworkDiagnostics.Empty

    private var session: PtpSession? = null
    private var deviceInfo: PtpDeviceInfo? = null
    private var storageSnapshot: List<PtpStorageInfo> = emptyList()
    private var storageError: String? = null

    override suspend fun initialize() {
        if (session != null) return
        val transport = transportFactory.open(connection)
        val newSession = PtpSession(transport)
        try {
            deviceInfo = newSession.initialize()
            session = newSession
        } catch (exception: Exception) {
            runCatching { newSession.shutdown() }
            throw exception
        }
    }

    override suspend fun close() {
        val current = session
        session = null
        deviceInfo = null
        storageSnapshot = emptyList()
        storageError = null
        current?.shutdown()
    }

    override suspend fun info(): CameraInfo {
        val info = requireDeviceInfo()
        val model = listOf(info.manufacturer, info.model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("^(Canon\\s+)+", RegexOption.IGNORE_CASE), "Canon ")
            .ifBlank { connection.deviceName ?: "USB PTP camera" }
        return CameraInfo(
            connected = true,
            model = model,
            serial = info.serialNumber.ifBlank { "unknown" },
            api = "ptp-usb/${formatPtpVersion(info.standardVersion)}",
        )
    }

    override suspend fun status(): CameraStatus {
        val info = requireDeviceInfo()
        val storageResult = if (supportsStorage(info)) runCatching { readStorageSnapshot() } else null
        storageSnapshot = storageResult?.getOrDefault(emptyList()).orEmpty()
        storageError = storageResult?.exceptionOrNull()?.message
        return CameraStatus(
            connected = true,
            batteryLevel = null,
            batteryStatus = "unavailable",
            recording = null,
            mode = "PTP",
            mediaAvailable = storageSnapshot.isNotEmpty(),
            remainingMinutes = null,
            exposure = ExposureState(
                iso = "-",
                shutter = "-",
                aperture = "-",
                whiteBalance = "-",
            ),
            rawStorageJson = storageError?.let(::storageErrorJson) ?: storageSnapshot.toStorageJson(),
        )
    }

    override suspend fun capabilities(): CameraCapabilities {
        val info = requireDeviceInfo()
        val supported = buildSet {
            add(CameraFeature.USB_DIAGNOSTICS)
            add(CameraFeature.CAMERA_IDENTITY)
            if (supportsStorage(info)) add(CameraFeature.STORAGE_STATUS)
            if (supportsMediaBrowser(info)) add(CameraFeature.MEDIA_BROWSER)
            if (info.supports(PtpOperationCode.GET_OBJECT)) add(CameraFeature.MEDIA_DOWNLOAD)
            if (info.supports(PtpOperationCode.INITIATE_CAPTURE)) add(CameraFeature.STILL_CAPTURE)
        }
        val candidates = setOf(
            CameraFeature.BATTERY_STATUS,
            CameraFeature.STORAGE_STATUS,
            CameraFeature.STILL_CAPTURE,
            CameraFeature.SHUTTER_HALF_PRESS,
            CameraFeature.VIDEO_RECORDING,
            CameraFeature.TAP_FOCUS,
            CameraFeature.LIVE_VIEW,
            CameraFeature.FOCUS_DRIVE,
            CameraFeature.EXPOSURE_CONTROL,
            CameraFeature.WHITE_BALANCE_CONTROL,
            CameraFeature.ADVANCED_SETTINGS,
            CameraFeature.MEDIA_BROWSER,
            CameraFeature.MEDIA_DOWNLOAD,
        )
        return CameraCapabilities(
            iso = emptyList(),
            shutter = emptyList(),
            aperture = emptyList(),
            whiteBalance = emptyList(),
            matrix = CapabilityMatrix(
                supported = supported,
                planned = candidates - supported,
                reasons = mapOf(
                    CameraFeature.STILL_CAPTURE to
                        "Enabled only when DeviceInfo advertises standard PTP InitiateCapture (0x100E).",
                    CameraFeature.MEDIA_BROWSER to
                        "Uses standard GetStorageIDs, GetObjectHandles, and GetObjectInfo operations.",
                    CameraFeature.MEDIA_DOWNLOAD to
                        "Uses standard GetObject with bounded USB reads and streaming output.",
                    CameraFeature.EXPOSURE_CONTROL to
                        "Canon EOS property writes require model-specific vendor-operation validation.",
                    CameraFeature.LIVE_VIEW to
                        "Canon EOS USB Live View requires a validated vendor operation and frame format.",
                ),
            ),
            liveView = LiveViewCapabilities(),
            profile = CameraProfile.fromModelName(info.model),
        )
    }

    override suspend fun captureStill(): CameraStatus {
        requireOperation(PtpOperationCode.INITIATE_CAPTURE, CameraFeature.STILL_CAPTURE)
        requireSession().initiateCapture()
        return status()
    }

    override suspend fun listMedia(): List<CameraMediaItem> {
        requireMediaBrowser()
        val ptp = requireSession()
        val handles = ptp.storageIds()
            .flatMap { storageId -> ptp.objectHandles(storageId) }
            .distinct()
            .takeLast(MAX_USB_MEDIA_ITEMS)
            .reversed()

        var firstFailure: Exception? = null
        val items = handles.mapNotNull { handle ->
            try {
                ptp.objectInfo(handle)
                    .takeUnless { it.objectFormat == PtpObjectFormat.ASSOCIATION || it.filename.isBlank() }
                    ?.toMediaItem()
            } catch (exception: Exception) {
                if (firstFailure == null) firstFailure = exception
                null
            }
        }
        if (handles.isNotEmpty() && items.isEmpty() && firstFailure != null) throw firstFailure!!
        return items
    }

    override suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit,
    ): CameraMediaDownloadResult {
        requireOperation(PtpOperationCode.GET_OBJECT, CameraFeature.MEDIA_DOWNLOAD)
        val handle = item.ptpHandle()
        val bytesTransferred = requireSession().downloadObject(handle, destination) { transferred, total ->
            onProgress(CameraMediaTransferProgress(transferred, total.takeIf { it > 0L } ?: item.sizeBytes))
        }
        return CameraMediaDownloadResult(
            item = item,
            bytesTransferred = bytesTransferred,
            contentType = contentTypeFor(item.name, item.kind),
        )
    }

    override suspend fun startLiveView(request: LiveViewRequest) = unsupported<Unit>(CameraFeature.LIVE_VIEW)

    override suspend fun stopLiveView() = Unit

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
        throw UnsupportedOperationException("USB PTP Live View is not validated for this camera.")

    override suspend fun liveViewFrame(cacheKey: Long, request: LiveViewRequest): LiveViewFrame =
        unsupported(CameraFeature.LIVE_VIEW)

    private suspend fun readStorageSnapshot(): List<PtpStorageInfo> {
        val ptp = requireSession()
        return ptp.storageIds().map { storageId -> ptp.storageInfo(storageId) }
    }

    private fun requireMediaBrowser() {
        val info = requireDeviceInfo()
        if (!supportsMediaBrowser(info)) unsupported<Unit>(CameraFeature.MEDIA_BROWSER)
    }

    private fun requireOperation(operationCode: Int, feature: CameraFeature) {
        if (!requireDeviceInfo().supports(operationCode)) unsupported<Unit>(feature)
    }

    private fun requireSession(): PtpSession = session ?: throw PtpProtocolException("USB PTP backend is not connected.")

    private fun requireDeviceInfo(): PtpDeviceInfo = deviceInfo
        ?: throw PtpProtocolException("USB PTP DeviceInfo has not been loaded.")

    private fun supportsStorage(info: PtpDeviceInfo): Boolean =
        info.supports(PtpOperationCode.GET_STORAGE_IDS) && info.supports(PtpOperationCode.GET_STORAGE_INFO)

    private fun supportsMediaBrowser(info: PtpDeviceInfo): Boolean =
        info.supports(PtpOperationCode.GET_STORAGE_IDS) &&
            info.supports(PtpOperationCode.GET_OBJECT_HANDLES) &&
            info.supports(PtpOperationCode.GET_OBJECT_INFO)
}

private fun PtpObjectInfo.toMediaItem(): CameraMediaItem = CameraMediaItem(
    id = "ptp:${handle.toString(16).uppercase(Locale.ROOT).padStart(8, '0')}",
    name = filename,
    kind = mediaKind(filename, objectFormat),
    sizeBytes = sizeBytes,
    captureTime = captureDate.toDisplayPtpDate(),
)

private fun CameraMediaItem.ptpHandle(): Long {
    val encoded = id.removePrefix("ptp:")
    return encoded.toLongOrNull(16)
        ?: throw PtpProtocolException("Media item $id is not a USB PTP object handle.")
}

private fun List<PtpStorageInfo>.toStorageJson(): String {
    val array = JSONArray()
    forEach { storage ->
        array.put(
            JSONObject()
                .put("id", "0x${storage.storageId.toString(16).uppercase(Locale.ROOT).padStart(8, '0')}")
                .put("description", storage.description)
                .put("volumeLabel", storage.volumeLabel)
                .put("maxCapacityBytes", storage.maxCapacityBytes.toString())
                .put("freeSpaceBytes", storage.freeSpaceBytes.toString())
                .put("freeSpaceImages", storage.freeSpaceImages)
                .put("accessCapability", storage.accessCapability)
        )
    }
    return array.toString()
}

private fun storageErrorJson(message: String): String = JSONObject().put("error", message).toString()

private fun mediaKind(filename: String, objectFormat: Int): String {
    val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        extension in setOf("cr2", "cr3", "dng", "raw") || objectFormat == PtpObjectFormat.DNG -> "raw"
        extension in setOf("mp4", "mov", "avi", "mkv") || objectFormat == PtpObjectFormat.MP4 -> "video"
        else -> "image"
    }
}

private fun contentTypeFor(filename: String, kind: String): String =
    when (filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "tif", "tiff" -> "image/tiff"
        "dng" -> "image/x-adobe-dng"
        "cr2", "cr3" -> "image/x-canon-raw"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> if (kind == "video") "video/*" else "application/octet-stream"
    }

private fun String.toDisplayPtpDate(): String? {
    if (length < 15 || getOrNull(8) != 'T') return takeIf { it.isNotBlank() }
    return "${substring(0, 4)}-${substring(4, 6)}-${substring(6, 8)} " +
        "${substring(9, 11)}:${substring(11, 13)}:${substring(13, 15)}"
}

private fun formatPtpVersion(value: Int): String =
    "${value / 100}.${(value % 100).toString().padStart(2, '0')}"

private const val MAX_USB_MEDIA_ITEMS = 500
