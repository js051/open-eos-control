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
    private var propertyDescriptors: Map<Int, PtpDevicePropertyDescriptor> = emptyMap()
    private val propertyValues = mutableMapOf<Int, PtpPropertyValue>()
    private val propertyErrors = mutableMapOf<Int, String>()
    private var lastPropertyRefreshAtMillis = 0L

    override suspend fun initialize() {
        if (session != null) return
        val transport = transportFactory.open(connection)
        val newSession = PtpSession(transport)
        try {
            val info = newSession.initialize()
            deviceInfo = info
            session = newSession
            loadPropertyDescriptors(newSession, info)
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
        propertyDescriptors = emptyMap()
        propertyValues.clear()
        propertyErrors.clear()
        lastPropertyRefreshAtMillis = 0L
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
        refreshPropertyValuesIfNeeded(info)
        val storageResult = if (supportsStorage(info)) runCatching { readStorageSnapshot() } else null
        storageSnapshot = storageResult?.getOrDefault(emptyList()).orEmpty()
        storageError = storageResult?.exceptionOrNull()?.message
        val batteryLevel = propertyValues[PtpDevicePropertyCode.BATTERY_LEVEL]
            ?.unsignedLong()
            ?.takeIf { it <= 100UL }
            ?.toInt()
        return CameraStatus(
            connected = true,
            batteryLevel = batteryLevel,
            batteryStatus = batteryStatus(batteryLevel),
            recording = null,
            mode = propertyDisplay(PtpDevicePropertyCode.EXPOSURE_PROGRAM_MODE).takeUnless { it == "-" } ?: "PTP",
            mediaAvailable = storageSnapshot.isNotEmpty(),
            remainingMinutes = null,
            exposure = ExposureState(
                iso = propertyDisplay(PtpDevicePropertyCode.EXPOSURE_INDEX),
                shutter = propertyDisplay(PtpDevicePropertyCode.EXPOSURE_TIME),
                aperture = propertyDisplay(PtpDevicePropertyCode.F_NUMBER),
                whiteBalance = propertyDisplay(PtpDevicePropertyCode.WHITE_BALANCE),
            ),
            rawBatteryJson = batteryPropertyJson(batteryLevel),
            rawStorageJson = storageError?.let(::storageErrorJson) ?: storageSnapshot.toStorageJson(),
            rawTransportJson = ptpTransportJson(info),
        )
    }

    override suspend fun capabilities(): CameraCapabilities {
        val info = requireDeviceInfo()
        val canSetProperties = info.supports(PtpOperationCode.SET_DEVICE_PROP_VALUE)
        val iso = writablePropertyOptions(PtpDevicePropertyCode.EXPOSURE_INDEX, canSetProperties)
        val shutter = writablePropertyOptions(PtpDevicePropertyCode.EXPOSURE_TIME, canSetProperties)
        val aperture = writablePropertyOptions(PtpDevicePropertyCode.F_NUMBER, canSetProperties)
        val whiteBalance = writablePropertyOptions(PtpDevicePropertyCode.WHITE_BALANCE, canSetProperties)
        val advancedSettings = if (canSetProperties) advancedPropertyControls() else emptyList()
        val supported = buildSet {
            add(CameraFeature.USB_DIAGNOSTICS)
            add(CameraFeature.CAMERA_IDENTITY)
            if (PtpDevicePropertyCode.BATTERY_LEVEL in propertyDescriptors) add(CameraFeature.BATTERY_STATUS)
            if (supportsStorage(info)) add(CameraFeature.STORAGE_STATUS)
            if (supportsMediaBrowser(info)) add(CameraFeature.MEDIA_BROWSER)
            if (info.supports(PtpOperationCode.GET_OBJECT)) add(CameraFeature.MEDIA_DOWNLOAD)
            if (info.supports(PtpOperationCode.INITIATE_CAPTURE)) add(CameraFeature.STILL_CAPTURE)
            if (iso.isNotEmpty() || shutter.isNotEmpty() || aperture.isNotEmpty()) {
                add(CameraFeature.EXPOSURE_CONTROL)
            }
            if (whiteBalance.isNotEmpty()) add(CameraFeature.WHITE_BALANCE_CONTROL)
            if (advancedSettings.isNotEmpty()) add(CameraFeature.ADVANCED_SETTINGS)
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
            iso = iso.map(PtpPropertyOption::label),
            shutter = shutter.map(PtpPropertyOption::label),
            aperture = aperture.map(PtpPropertyOption::label),
            whiteBalance = whiteBalance.map(PtpPropertyOption::label),
            advancedSettings = advancedSettings,
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
                        "Standard properties are enabled only when DeviceInfo and writable DevicePropDesc datasets advertise them; Canon vendor properties remain unverified.",
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

    override suspend fun setExposure(iso: String?, shutter: String?, aperture: String?): CameraStatus {
        iso?.let { setProperty(PtpDevicePropertyCode.EXPOSURE_INDEX, it, CameraFeature.EXPOSURE_CONTROL) }
        shutter?.let { setProperty(PtpDevicePropertyCode.EXPOSURE_TIME, it, CameraFeature.EXPOSURE_CONTROL) }
        aperture?.let { setProperty(PtpDevicePropertyCode.F_NUMBER, it, CameraFeature.EXPOSURE_CONTROL) }
        return status()
    }

    override suspend fun setWhiteBalance(value: String): CameraStatus {
        setProperty(PtpDevicePropertyCode.WHITE_BALANCE, value, CameraFeature.WHITE_BALANCE_CONTROL)
        return status()
    }

    override suspend fun setSetting(key: String, value: String): CameraStatus {
        val spec = PtpStandardProperties.advancedProperties.firstOrNull { it.key == key }
            ?: throw UnsupportedOperationException("USB PTP setting $key is not implemented.")
        setProperty(spec.propertyCode, value, CameraFeature.ADVANCED_SETTINGS)
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

    private suspend fun loadPropertyDescriptors(ptp: PtpSession, info: PtpDeviceInfo) {
        if (!info.supports(PtpOperationCode.GET_DEVICE_PROP_DESC)) return
        val descriptors = mutableMapOf<Int, PtpDevicePropertyDescriptor>()
        PtpStandardProperties.knownPropertyCodes
            .filter { it in info.deviceProperties }
            .sorted()
            .forEach { propertyCode ->
                runCatching { ptp.devicePropertyDescriptor(propertyCode) }
                    .onSuccess { descriptor ->
                        descriptors[propertyCode] = descriptor
                        propertyValues[propertyCode] = descriptor.currentValue
                        propertyErrors.remove(propertyCode)
                    }
                    .onFailure { propertyErrors[propertyCode] = it.message ?: it.javaClass.simpleName }
            }
        propertyDescriptors = descriptors
        lastPropertyRefreshAtMillis = System.currentTimeMillis()
    }

    private suspend fun refreshPropertyValuesIfNeeded(info: PtpDeviceInfo) {
        val now = System.currentTimeMillis()
        if (
            !info.supports(PtpOperationCode.GET_DEVICE_PROP_VALUE) ||
            now - lastPropertyRefreshAtMillis < PROPERTY_REFRESH_INTERVAL_MILLIS
        ) return
        val ptp = requireSession()
        propertyDescriptors.forEach { (propertyCode, descriptor) ->
            runCatching { ptp.devicePropertyValue(propertyCode, descriptor.dataType) }
                .onSuccess { value ->
                    propertyValues[propertyCode] = value
                    propertyErrors.remove(propertyCode)
                }
                .onFailure { propertyErrors[propertyCode] = it.message ?: it.javaClass.simpleName }
        }
        lastPropertyRefreshAtMillis = now
    }

    private suspend fun setProperty(propertyCode: Int, label: String, feature: CameraFeature) {
        val info = requireDeviceInfo()
        if (!info.supports(PtpOperationCode.SET_DEVICE_PROP_VALUE)) unsupported<Unit>(feature)
        val descriptor = propertyDescriptors[propertyCode] ?: unsupported<PtpDevicePropertyDescriptor>(feature)
        if (!descriptor.writable) unsupported<Unit>(feature)
        val option = PtpStandardProperties.options(descriptor).firstOrNull { it.label == label }
            ?: throw PtpProtocolException(
                "Value '$label' is not advertised for USB PTP property " +
                    "0x${propertyCode.toString(16).uppercase().padStart(4, '0')}."
            )
        requireSession().setDevicePropertyValue(propertyCode, descriptor.dataType, option.value)
        propertyValues[propertyCode] = option.value
        propertyErrors.remove(propertyCode)
        lastPropertyRefreshAtMillis = System.currentTimeMillis()
    }

    private fun writablePropertyOptions(propertyCode: Int, canSetProperties: Boolean): List<PtpPropertyOption> {
        if (!canSetProperties) return emptyList()
        val descriptor = propertyDescriptors[propertyCode]?.takeIf { it.writable } ?: return emptyList()
        return PtpStandardProperties.options(descriptor)
    }

    private fun advancedPropertyControls(): List<CameraSettingControl> =
        PtpStandardProperties.advancedProperties.mapNotNull { spec ->
            val descriptor = propertyDescriptors[spec.propertyCode]?.takeIf { it.writable } ?: return@mapNotNull null
            val options = PtpStandardProperties.options(descriptor)
            if (options.isEmpty()) return@mapNotNull null
            CameraSettingControl(
                key = spec.key,
                label = spec.fallbackLabel,
                value = propertyDisplay(spec.propertyCode),
                values = options.map(PtpPropertyOption::label),
            )
        }

    private fun propertyDisplay(propertyCode: Int): String = propertyValues[propertyCode]
        ?.let { PtpStandardProperties.format(propertyCode, it) }
        ?: "-"

    private fun batteryPropertyJson(level: Int?): String = JSONObject()
        .put("kind", "ptp-device-property")
        .put("code", "0x5001")
        .put("level", level ?: JSONObject.NULL)
        .apply {
            propertyErrors[PtpDevicePropertyCode.BATTERY_LEVEL]?.let { put("error", it) }
        }
        .toString()

    private fun ptpTransportJson(info: PtpDeviceInfo): String {
        val properties = JSONArray()
        PtpStandardProperties.knownPropertyCodes.sorted().forEach { propertyCode ->
            val descriptor = propertyDescriptors[propertyCode]
            val error = propertyErrors[propertyCode]
            if (descriptor != null || error != null || propertyCode in info.deviceProperties) {
                properties.put(
                    JSONObject()
                        .put("code", propertyCode.ptpHexCode())
                        .put("advertised", propertyCode in info.deviceProperties)
                        .put("dataType", descriptor?.dataType?.code?.ptpHexCode() ?: JSONObject.NULL)
                        .put("writable", descriptor?.writable ?: false)
                        .put(
                            "current",
                            propertyValues[propertyCode]
                                ?.let { PtpStandardProperties.format(propertyCode, it) }
                                ?: JSONObject.NULL,
                        )
                        .put("optionCount", descriptor?.let { PtpStandardProperties.options(it).size } ?: 0)
                        .apply { error?.let { put("error", it) } }
                )
            }
        }
        return JSONObject()
            .put("kind", "ptp-usb")
            .put("vendorExtensionId", info.vendorExtensionId.toInt().ptpHexCode(8))
            .put("operations", JSONArray(info.operations.sorted().map { it.ptpHexCode() }))
            .put("advertisedDeviceProperties", JSONArray(info.deviceProperties.sorted().map { it.ptpHexCode() }))
            .put("loadedProperties", properties)
            .toString()
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

private fun Int.ptpHexCode(width: Int = 4): String =
    "0x${toUInt().toString(16).uppercase(Locale.ROOT).padStart(width, '0')}"

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

private fun PtpPropertyValue.unsignedLong(): ULong? = when (this) {
    is PtpPropertyValue.Unsigned -> value
    is PtpPropertyValue.Signed -> value.takeIf { it >= 0L }?.toULong()
    else -> null
}

private fun batteryStatus(level: Int?): String = when {
    level == null -> "unavailable"
    level <= 10 -> "critical"
    level <= 25 -> "low"
    level >= 95 -> "full"
    else -> "good"
}

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
private const val PROPERTY_REFRESH_INTERVAL_MILLIS = 500L
