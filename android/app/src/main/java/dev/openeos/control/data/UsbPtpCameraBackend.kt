package dev.openeos.control.data

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.Locale

class UsbPtpCameraBackend(
    override val connection: CameraConnection.AndroidUsbPtp,
    private val transportFactory: PtpTransportFactory,
) : CameraControlBackend {
    override val transport: CameraTransport = CameraTransport.USB_PTP
    override val prefersBitmapLiveViewFrames: Boolean = true
    override val networkDiagnostics: CameraNetworkDiagnostics = CameraNetworkDiagnostics.Empty

    private var session: PtpSession? = null
    private var deviceInfo: PtpDeviceInfo? = null
    private var storageSnapshot: List<PtpStorageInfo> = emptyList()
    private var storageError: String? = null
    private val mediaInfo = mutableMapOf<Long, PtpObjectInfo>()
    private var propertyDescriptors: Map<Int, PtpDevicePropertyDescriptor> = emptyMap()
    private val propertyValues = mutableMapOf<Int, PtpPropertyValue>()
    private val propertyErrors = mutableMapOf<Int, String>()
    private var lastPropertyRefreshAtMillis = 0L
    private var canonRemotePrepared = false
    private var canonLiveViewActive = false
    private val canonProperties = mutableMapOf<Int, CanonEosPropertyState>()
    private var canonPropertyDiscoveryAttempted = false
    private var canonPropertyError: String? = null

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
        if (current != null) {
            runCatching { stopLiveView() }
            if (canonRemotePrepared) {
                runCatching {
                    current.executeOperation(CanonEosOperationCode.SET_REMOTE_MODE, listOf(0L))
                    current.executeOperation(CanonEosOperationCode.SET_REMOTE_MODE, listOf(1L))
                    current.executeOperation(CanonEosOperationCode.SET_EVENT_MODE, listOf(0L))
                }
            }
        }
        session = null
        deviceInfo = null
        storageSnapshot = emptyList()
        storageError = null
        mediaInfo.clear()
        propertyDescriptors = emptyMap()
        propertyValues.clear()
        propertyErrors.clear()
        lastPropertyRefreshAtMillis = 0L
        canonRemotePrepared = false
        canonLiveViewActive = false
        synchronized(canonProperties) { canonProperties.clear() }
        canonPropertyDiscoveryAttempted = false
        canonPropertyError = null
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
        refreshCanonPropertyState(info)
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
            recording = CanonEosPtp.movieRecording(
                canonPropertyState(CanonEosPropertyCode.EVF_RECORD_STATUS).currentValue,
            ),
            mode = corePropertyDisplay(
                CanonEosPropertyCode.AUTO_EXPOSURE_MODE,
                PtpDevicePropertyCode.EXPOSURE_PROGRAM_MODE,
            ).takeUnless { it == "-" } ?: "PTP",
            mediaAvailable = storageSnapshot.isNotEmpty(),
            remainingMinutes = null,
            exposure = ExposureState(
                iso = corePropertyDisplay(CanonEosPropertyCode.ISO_SPEED, PtpDevicePropertyCode.EXPOSURE_INDEX),
                shutter = corePropertyDisplay(
                    CanonEosPropertyCode.SHUTTER_SPEED,
                    PtpDevicePropertyCode.EXPOSURE_TIME,
                ),
                aperture = corePropertyDisplay(CanonEosPropertyCode.APERTURE, PtpDevicePropertyCode.F_NUMBER),
                whiteBalance = corePropertyDisplay(
                    CanonEosPropertyCode.WHITE_BALANCE,
                    PtpDevicePropertyCode.WHITE_BALANCE,
                ),
            ),
            rawBatteryJson = batteryPropertyJson(batteryLevel),
            rawStorageJson = storageError?.let(::storageErrorJson) ?: storageSnapshot.toStorageJson(),
            rawTransportJson = ptpTransportJson(info),
        )
    }

    override suspend fun capabilities(): CameraCapabilities {
        val info = requireDeviceInfo()
        refreshCanonPropertyState(info)
        val canSetProperties = info.supports(PtpOperationCode.SET_DEVICE_PROP_VALUE)
        val iso = corePropertyOptions(
            CanonEosPropertyCode.ISO_SPEED,
            PtpDevicePropertyCode.EXPOSURE_INDEX,
            canSetProperties,
        )
        val shutter = corePropertyOptions(
            CanonEosPropertyCode.SHUTTER_SPEED,
            PtpDevicePropertyCode.EXPOSURE_TIME,
            canSetProperties,
        )
        val aperture = corePropertyOptions(
            CanonEosPropertyCode.APERTURE,
            PtpDevicePropertyCode.F_NUMBER,
            canSetProperties,
        )
        val whiteBalance = corePropertyOptions(
            CanonEosPropertyCode.WHITE_BALANCE,
            PtpDevicePropertyCode.WHITE_BALANCE,
            canSetProperties,
        )
        val advancedSettings = advancedPropertyControls(
            canSetStandardProperties = canSetProperties,
            canSetCanonProperties = CanonEosPtp.supportsPropertyControl(info),
        )
        val supportsCanonRelease = CanonEosPtp.supportsRemoteRelease(info)
        val supportsCanonLiveView = CanonEosPtp.supportsLiveView(info)
        val supportsCanonFocusDrive = CanonEosPtp.supportsFocusDrive(info)
        val supportsCanonMovieRecording = CanonEosPtp.supportsMovieRecording(
            info,
            canonPropertyState(CanonEosPropertyCode.EVF_RECORD_STATUS).availableValues,
        )
        val supported = buildSet {
            add(CameraFeature.USB_DIAGNOSTICS)
            add(CameraFeature.CAMERA_IDENTITY)
            if (PtpDevicePropertyCode.BATTERY_LEVEL in propertyDescriptors) add(CameraFeature.BATTERY_STATUS)
            if (supportsStorage(info)) add(CameraFeature.STORAGE_STATUS)
            if (supportsMediaBrowser(info)) add(CameraFeature.MEDIA_BROWSER)
            if (supportsMediaBrowser(info) && info.supports(PtpOperationCode.GET_THUMB)) {
                add(CameraFeature.MEDIA_THUMBNAIL)
            }
            if (info.supports(PtpOperationCode.GET_OBJECT)) add(CameraFeature.MEDIA_DOWNLOAD)
            if (info.supports(PtpOperationCode.DELETE_OBJECT)) add(CameraFeature.MEDIA_DELETE)
            if (info.supports(PtpOperationCode.INITIATE_CAPTURE) || supportsCanonRelease) {
                add(CameraFeature.STILL_CAPTURE)
            }
            if (supportsCanonRelease) add(CameraFeature.SHUTTER_HALF_PRESS)
            if (supportsCanonRelease) add(CameraFeature.AUTOFOCUS)
            if (supportsCanonLiveView) {
                add(CameraFeature.LIVE_VIEW)
                add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
            }
            if (supportsCanonFocusDrive) add(CameraFeature.FOCUS_DRIVE)
            if (supportsCanonMovieRecording) add(CameraFeature.VIDEO_RECORDING)
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
            CameraFeature.AUTOFOCUS,
            CameraFeature.SHUTTER_HALF_PRESS,
            CameraFeature.VIDEO_RECORDING,
            CameraFeature.TAP_FOCUS,
            CameraFeature.LIVE_VIEW,
            CameraFeature.LIVE_VIEW_JPEG_POLLING,
            CameraFeature.FOCUS_DRIVE,
            CameraFeature.EXPOSURE_CONTROL,
            CameraFeature.WHITE_BALANCE_CONTROL,
            CameraFeature.ADVANCED_SETTINGS,
            CameraFeature.MEDIA_BROWSER,
            CameraFeature.MEDIA_THUMBNAIL,
            CameraFeature.MEDIA_DOWNLOAD,
            CameraFeature.MEDIA_DELETE,
        )
        val writableSettings = buildList {
            if (iso.isNotEmpty()) add("iso")
            if (shutter.isNotEmpty()) add("shutter")
            if (aperture.isNotEmpty()) add("aperture")
            if (whiteBalance.isNotEmpty()) add("whitebalance")
            if (supportsCanonMovieRecording) add("movierecordtarget")
            addAll(advancedSettings.map(CameraSettingControl::key))
        }.distinct().sorted()
        val advertisedCommands = info.operations
            .asSequence()
            .sorted()
            .map { "0x%04X".format(Locale.US, it) }
            .take(MAX_CAPABILITY_EVIDENCE_ITEMS)
            .toList()
        return CameraCapabilities(
            iso = iso,
            shutter = shutter,
            aperture = aperture,
            whiteBalance = whiteBalance,
            advancedSettings = advancedSettings,
            matrix = CapabilityMatrix(
                supported = supported,
                planned = candidates - supported,
                reasons = mapOf(
                    CameraFeature.STILL_CAPTURE to
                        "Uses standard InitiateCapture or the advertised Canon EOS RemoteReleaseOn/Off sequence.",
                    CameraFeature.SHUTTER_HALF_PRESS to
                        "Uses Canon EOS RemoteReleaseOn/Off only when the camera advertises the full remote event sequence.",
                    CameraFeature.AUTOFOCUS to
                        "Uses a balanced Canon EOS half-press sequence when the full remote event operation set is advertised.",
                    CameraFeature.FOCUS_DRIVE to
                        "Uses Canon EOS DriveLens with the Near/Far 1-3 values documented by libgphoto2.",
                    CameraFeature.VIDEO_RECORDING to
                        "Uses Canon EOS EVFRecordStatus only when camera events advertise both Card and None values.",
                    CameraFeature.MEDIA_BROWSER to
                        "Uses standard GetStorageIDs, GetObjectHandles, and GetObjectInfo operations.",
                    CameraFeature.MEDIA_THUMBNAIL to
                        "Uses standard PTP GetThumb only when operation 0x100A is advertised by DeviceInfo.",
                    CameraFeature.MEDIA_DOWNLOAD to
                        "Uses standard GetObject with bounded USB reads and streaming output.",
                    CameraFeature.MEDIA_DELETE to
                        "Uses standard DeleteObject only when the camera advertises operation 0x100B.",
                    CameraFeature.EXPOSURE_CONTROL to
                        "Uses writable standard PTP descriptors or Canon EOS PropValueChanged/AvailListChanged events with SetDevicePropValueEx.",
                    CameraFeature.LIVE_VIEW to
                        "Uses Canon EOS GetViewFinderData and extracts the documented type 1/11 JPEG block.",
                ),
            ),
            liveView = if (supportsCanonLiveView) {
                LiveViewCapabilities(
                    sources = listOf(LiveViewSource.USB_PTP_PREVIEW),
                    defaultSource = LiveViewSource.USB_PTP_PREVIEW,
                    sizes = listOf(LiveViewSize.MEDIUM),
                    defaultSize = LiveViewSize.MEDIUM,
                    minFps = 1,
                    maxFps = 30,
                )
            } else {
                LiveViewCapabilities()
            },
            profile = CameraProfile.fromModelName(info.model),
            evidence = CameraCapabilityEvidence(
                source = "PTP GetDeviceInfo",
                protocolVersions = listOf(
                    "PTP ${formatPtpVersion(info.standardVersion)}",
                    "vendor 0x%08X/%d".format(
                        Locale.US,
                        info.vendorExtensionId,
                        info.vendorExtensionVersion,
                    ),
                ),
                advertisedCommands = advertisedCommands,
                writableSettings = writableSettings.take(MAX_CAPABILITY_EVIDENCE_ITEMS),
                truncated = info.operations.size > MAX_CAPABILITY_EVIDENCE_ITEMS ||
                    writableSettings.size > MAX_CAPABILITY_EVIDENCE_ITEMS,
            ),
        )
    }

    override suspend fun captureStill(): CameraStatus {
        val info = requireDeviceInfo()
        if (info.supports(PtpOperationCode.INITIATE_CAPTURE)) {
            requireSession().initiateCapture()
            return status()
        }
        if (!CanonEosPtp.supportsRemoteRelease(info)) unsupported<Unit>(CameraFeature.STILL_CAPTURE)

        ensureCanonRemoteMode()
        drainCanonEvents()
        val ptp = requireSession()
        ptp.executeOperation(CanonEosOperationCode.REMOTE_RELEASE_ON, listOf(1L, 0L))
        try {
            ptp.executeOperation(CanonEosOperationCode.REMOTE_RELEASE_ON, listOf(2L, 0L))
            try {
                Unit
            } finally {
                ptp.executeOperation(CanonEosOperationCode.REMOTE_RELEASE_OFF, listOf(2L))
            }
        } finally {
            ptp.executeOperation(CanonEosOperationCode.REMOTE_RELEASE_OFF, listOf(1L))
        }
        awaitCanonCapturedObject()
        return status()
    }

    override suspend fun halfPressShutter(): CameraStatus {
        if (!CanonEosPtp.supportsRemoteRelease(requireDeviceInfo())) {
            unsupported<Unit>(CameraFeature.SHUTTER_HALF_PRESS)
        }
        ensureCanonRemoteMode()
        drainCanonEvents()
        val ptp = requireSession()
        ptp.executeOperation(CanonEosOperationCode.REMOTE_RELEASE_ON, listOf(1L, 0L))
        try {
            drainCanonEvents()
        } finally {
            ptp.executeOperation(CanonEosOperationCode.REMOTE_RELEASE_OFF, listOf(1L))
        }
        drainCanonEvents()
        return status()
    }

    override suspend fun autofocus(): CameraStatus = halfPressShutter()

    override suspend fun driveFocus(
        direction: FocusDriveDirection,
        step: FocusDriveStep,
    ): FocusDriveResult {
        if (!CanonEosPtp.supportsFocusDrive(requireDeviceInfo())) unsupported<Unit>(CameraFeature.FOCUS_DRIVE)
        ensureCanonRemoteMode()
        requireSession().executeOperation(
            CanonEosOperationCode.DRIVE_LENS,
            listOf(CanonEosPtp.focusDriveAmount(direction, step)),
        )
        drainCanonEvents()
        return FocusDriveResult(ok = true, direction = direction, step = step)
    }

    override suspend fun setExposure(iso: String?, shutter: String?, aperture: String?): CameraStatus {
        iso?.let {
            setCoreProperty(
                CanonEosPropertyCode.ISO_SPEED,
                PtpDevicePropertyCode.EXPOSURE_INDEX,
                it,
                CameraFeature.EXPOSURE_CONTROL,
            )
        }
        shutter?.let {
            setCoreProperty(
                CanonEosPropertyCode.SHUTTER_SPEED,
                PtpDevicePropertyCode.EXPOSURE_TIME,
                it,
                CameraFeature.EXPOSURE_CONTROL,
            )
        }
        aperture?.let {
            setCoreProperty(
                CanonEosPropertyCode.APERTURE,
                PtpDevicePropertyCode.F_NUMBER,
                it,
                CameraFeature.EXPOSURE_CONTROL,
            )
        }
        return status()
    }

    override suspend fun setWhiteBalance(value: String): CameraStatus {
        setCoreProperty(
            CanonEosPropertyCode.WHITE_BALANCE,
            PtpDevicePropertyCode.WHITE_BALANCE,
            value,
            CameraFeature.WHITE_BALANCE_CONTROL,
        )
        return status()
    }

    override suspend fun setSetting(key: String, value: String): CameraStatus {
        val info = requireDeviceInfo()
        refreshCanonPropertyState(info)
        val canonSpec = CanonEosPtp.settingSpecs.firstOrNull { it.key == key }
        if (canonSpec != null && setAdvertisedCanonProperty(canonSpec.propertyCode, value)) {
            return status()
        }
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
        mediaInfo.clear()
        val items = handles.mapNotNull { handle ->
            try {
                ptp.objectInfo(handle)
                    .takeUnless { it.objectFormat == PtpObjectFormat.ASSOCIATION || it.filename.isBlank() }
                    ?.also { mediaInfo[handle] = it }
                    ?.toMediaItem()
            } catch (exception: Exception) {
                if (firstFailure == null) firstFailure = exception
                null
            }
        }
        if (handles.isNotEmpty() && items.isEmpty() && firstFailure != null) throw firstFailure!!
        return items
    }

    override suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail {
        requireOperation(PtpOperationCode.GET_THUMB, CameraFeature.MEDIA_THUMBNAIL)
        val handle = item.ptpHandle()
        val objectInfo = mediaInfo[handle] ?: requireSession().objectInfo(handle).also { mediaInfo[handle] = it }
        if (objectInfo.thumbnailSizeBytes <= 0L || objectInfo.thumbnailFormat == 0) {
            throw PtpProtocolException("${item.name} does not advertise an embedded PTP thumbnail.")
        }
        if (objectInfo.thumbnailSizeBytes > MAX_PTP_THUMBNAIL_BYTES) {
            throw PtpProtocolException(
                "${item.name} advertises a ${objectInfo.thumbnailSizeBytes}-byte thumbnail; " +
                    "limit is $MAX_PTP_THUMBNAIL_BYTES bytes."
            )
        }
        val bytes = requireSession().objectThumbnail(handle)
        if (bytes.size > MAX_PTP_THUMBNAIL_BYTES) {
            throw PtpProtocolException(
                "${item.name} thumbnail is ${bytes.size} bytes; limit is $MAX_PTP_THUMBNAIL_BYTES bytes."
            )
        }
        return CameraMediaThumbnail(
            item = item,
            bytes = bytes,
            contentType = thumbnailContentType(objectInfo.thumbnailFormat, bytes),
        )
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

    override suspend fun deleteMedia(item: CameraMediaItem) {
        requireOperation(PtpOperationCode.DELETE_OBJECT, CameraFeature.MEDIA_DELETE)
        requireSession().deleteObject(item.ptpHandle())
    }

    override suspend fun startLiveView(request: LiveViewRequest) {
        if (!CanonEosPtp.supportsLiveView(requireDeviceInfo())) unsupported<Unit>(CameraFeature.LIVE_VIEW)
        if (canonLiveViewActive) return
        ensureCanonRemoteMode()
        val ptp = requireSession()
        ptp.executeDataOutOperation(
            operationCode = CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
            payload = CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.EVF_MODE, 1),
        )
        try {
            ptp.executeDataOutOperation(
                operationCode = CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
                payload = CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.EVF_OUTPUT_DEVICE, 2L),
            )
            canonLiveViewActive = true
            drainCanonEvents()
        } catch (exception: Exception) {
            runCatching {
                ptp.executeDataOutOperation(
                    operationCode = CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
                    payload = CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.EVF_OUTPUT_DEVICE, 0L),
                )
            }
            throw exception
        }
    }

    override suspend fun stopLiveView() {
        if (!canonLiveViewActive) return
        try {
            requireSession().executeDataOutOperation(
                operationCode = CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
                payload = CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.EVF_OUTPUT_DEVICE, 0L),
            )
        } finally {
            canonLiveViewActive = false
        }
    }

    override suspend fun startRecording(): CameraStatus = setCanonMovieRecording(recording = true)

    override suspend fun stopRecording(): CameraStatus = setCanonMovieRecording(recording = false)

    override suspend fun tapFocus(x: Double, y: Double): FocusResult = unsupported(CameraFeature.TAP_FOCUS)

    override fun liveViewFrameUrl(cacheKey: Long, request: LiveViewRequest): String =
        throw UnsupportedOperationException("USB PTP Live View frames are returned as in-memory JPEG data.")

    override suspend fun liveViewFrame(cacheKey: Long, request: LiveViewRequest): LiveViewFrame {
        if (!canonLiveViewActive) throw PtpProtocolException("Canon EOS USB Live View is not running.")
        val payload = readCanonViewfinderData()
        return LiveViewFrame(
            bytes = CanonEosPtp.liveViewJpeg(payload),
            contentType = "image/jpeg",
            sourceUrl = "ptp-usb://canon-eos/viewfinder?frame=$cacheKey",
        )
    }

    private suspend fun ensureCanonRemoteMode() {
        if (canonRemotePrepared) return
        val info = requireDeviceInfo()
        if (!CanonEosPtp.supportsRemotePreparation(info)) {
            throw PtpProtocolException("Camera does not advertise the Canon EOS remote/event mode sequence.")
        }
        val ptp = requireSession()
        ptp.executeOperation(CanonEosOperationCode.SET_REMOTE_MODE, listOf(1L))
        try {
            ptp.executeOperation(CanonEosOperationCode.SET_EVENT_MODE, listOf(1L))
            drainCanonEvents()
            canonRemotePrepared = true
        } catch (exception: Exception) {
            runCatching { ptp.executeOperation(CanonEosOperationCode.SET_REMOTE_MODE, listOf(0L)) }
            throw exception
        }
    }

    private suspend fun drainCanonEvents(): ByteArray =
        requireSession().executeDataInOperation(CanonEosOperationCode.GET_EVENT).also(::applyCanonPropertyUpdates)

    private suspend fun refreshCanonPropertyState(info: PtpDeviceInfo) {
        if (!CanonEosPtp.supportsPropertyControl(info)) return
        if (!canonRemotePrepared) {
            try {
                ensureCanonRemoteMode()
            } catch (exception: Exception) {
                canonPropertyError = exception.message ?: exception.javaClass.simpleName
                return
            }
        }

        try {
            if (!canonPropertyDiscoveryAttempted) {
                canonPropertyDiscoveryAttempted = true
                for (attempt in 1 until CANON_PROPERTY_DISCOVERY_ATTEMPTS) {
                    if (hasCanonCorePropertyOptions()) break
                    delay(CANON_PROPERTY_DISCOVERY_RETRY_MILLIS)
                    drainCanonEvents()
                }
            } else {
                drainCanonEvents()
            }
            canonPropertyError = if (hasCanonCorePropertyOptions()) {
                null
            } else {
                "Canon EOS remote mode returned no supported writable property events."
            }
        } catch (exception: Exception) {
            canonPropertyError = exception.message ?: exception.javaClass.simpleName
        }
    }

    private fun applyCanonPropertyUpdates(payload: ByteArray) {
        val updates = CanonEosPtp.propertyUpdates(payload)
        if (updates.isEmpty()) return
        synchronized(canonProperties) {
            updates.forEach { update ->
                val previous = canonProperties[update.propertyCode] ?: CanonEosPropertyState()
                canonProperties[update.propertyCode] = previous.copy(
                    currentValue = update.currentValue ?: previous.currentValue,
                    availableValues = update.availableValues ?: previous.availableValues,
                )
            }
        }
    }

    private fun hasCanonCorePropertyOptions(): Boolean = synchronized(canonProperties) {
        canonProperties.values.any { it.availableValues.isNotEmpty() }
    }

    private suspend fun awaitCanonCapturedObject() {
        val deadline = System.currentTimeMillis() + CANON_CAPTURE_EVENT_TIMEOUT_MILLIS
        do {
            if (CanonEosPtp.containsCapturedObjectEvent(drainCanonEvents())) return
            delay(CANON_EVENT_POLL_INTERVAL_MILLIS)
        } while (System.currentTimeMillis() < deadline)
        throw PtpProtocolException(
            "Canon EOS shutter commands completed, but the camera did not report a captured object " +
                "within ${CANON_CAPTURE_EVENT_TIMEOUT_MILLIS / 1_000} seconds."
        )
    }

    private suspend fun setCanonMovieRecording(recording: Boolean): CameraStatus {
        val info = requireDeviceInfo()
        refreshCanonPropertyState(info)
        val state = canonPropertyState(CanonEosPropertyCode.EVF_RECORD_STATUS)
        if (!CanonEosPtp.supportsMovieRecording(info, state.availableValues)) {
            unsupported<Unit>(CameraFeature.VIDEO_RECORDING)
        }

        val target = if (recording) {
            CanonEosPtp.MOVIE_RECORD_TARGET_CARD
        } else {
            CanonEosPtp.MOVIE_RECORD_TARGET_NONE
        }
        if (state.currentValue != target) {
            ensureCanonRemoteMode()
            requireSession().executeDataOutOperation(
                operationCode = CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
                payload = CanonEosPtp.uint16PropertyPayload(
                    CanonEosPropertyCode.EVF_RECORD_STATUS,
                    target.toInt(),
                ),
            )
            synchronized(canonProperties) {
                canonProperties[CanonEosPropertyCode.EVF_RECORD_STATUS] = state.copy(currentValue = target)
            }
        }
        return status()
    }

    private suspend fun readCanonViewfinderData(): ByteArray {
        val deadline = System.currentTimeMillis() + CANON_LIVE_VIEW_READY_TIMEOUT_MILLIS
        var retryDelay = 5L
        while (true) {
            try {
                return requireSession().executeDataInOperation(
                    operationCode = CanonEosOperationCode.GET_VIEWFINDER_DATA,
                    parameters = listOf(CanonEosPtp.VIEWFINDER_REQUEST_BYTES, 0L, 0L),
                )
            } catch (exception: PtpResponseException) {
                val retryable = exception.responseCode == PtpResponseCode.DEVICE_BUSY ||
                    exception.responseCode == CanonEosPtp.VIEWFINDER_NOT_READY_RESPONSE
                if (!retryable || System.currentTimeMillis() >= deadline) throw exception
                delay(retryDelay)
                retryDelay = (retryDelay + 5L).coerceAtMost(100L)
            }
        }
    }

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

    private suspend fun setCoreProperty(
        canonPropertyCode: Int,
        standardPropertyCode: Int,
        label: String,
        feature: CameraFeature,
    ) {
        if (setAdvertisedCanonProperty(canonPropertyCode, label)) return
        setProperty(standardPropertyCode, label, feature)
    }

    private suspend fun setAdvertisedCanonProperty(propertyCode: Int, label: String): Boolean {
        if (!CanonEosPtp.supportsPropertyControl(requireDeviceInfo())) return false
        val state = canonPropertyState(propertyCode)
        if (state.availableValues.isEmpty()) return false
        val value = CanonEosPtp.propertyValue(propertyCode, state.availableValues, label)
            ?: throw PtpProtocolException(
                "Value '$label' is not advertised for Canon EOS USB property " +
                    "0x${propertyCode.toString(16).uppercase().padStart(4, '0')}."
            )
        ensureCanonRemoteMode()
        requireSession().executeDataOutOperation(
            operationCode = CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
            payload = CanonEosPtp.propertyPayload(propertyCode, value),
        )
        synchronized(canonProperties) {
            canonProperties[propertyCode] = state.copy(currentValue = value)
        }
        return true
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

    private fun corePropertyOptions(
        canonPropertyCode: Int,
        standardPropertyCode: Int,
        canSetStandardProperties: Boolean,
    ): List<String> {
        val canonOptions = canonPropertyState(canonPropertyCode).availableValues
            .let { CanonEosPtp.propertyOptions(canonPropertyCode, it) }
            .map(CanonEosPropertyOption::label)
        return canonOptions.ifEmpty {
            writablePropertyOptions(standardPropertyCode, canSetStandardProperties).map(PtpPropertyOption::label)
        }
    }

    private fun advancedPropertyControls(
        canSetStandardProperties: Boolean,
        canSetCanonProperties: Boolean,
    ): List<CameraSettingControl> {
        val controls = linkedMapOf<String, CameraSettingControl>()
        if (canSetCanonProperties) {
            CanonEosPtp.settingSpecs.forEach { spec ->
                val state = canonPropertyState(spec.propertyCode)
                val options = CanonEosPtp.propertyOptions(spec.propertyCode, state.availableValues)
                if (options.isNotEmpty()) {
                    controls[spec.key] = CameraSettingControl(
                        key = spec.key,
                        label = spec.fallbackLabel,
                        value = state.currentValue?.let { CanonEosPtp.propertyLabel(spec.propertyCode, it) } ?: "-",
                        values = options.map(CanonEosPropertyOption::label),
                    )
                }
            }
        }
        if (!canSetStandardProperties) return controls.values.toList()
        PtpStandardProperties.advancedProperties.forEach { spec ->
            val descriptor = propertyDescriptors[spec.propertyCode]?.takeIf { it.writable } ?: return@forEach
            val options = PtpStandardProperties.options(descriptor)
            if (options.isNotEmpty() && spec.key !in controls) {
                controls[spec.key] = CameraSettingControl(
                    key = spec.key,
                    label = spec.fallbackLabel,
                    value = propertyDisplay(spec.propertyCode),
                    values = options.map(PtpPropertyOption::label),
                )
            }
        }
        return controls.values.toList()
    }

    private fun propertyDisplay(propertyCode: Int): String = propertyValues[propertyCode]
        ?.let { PtpStandardProperties.format(propertyCode, it) }
        ?: "-"

    private fun corePropertyDisplay(canonPropertyCode: Int, standardPropertyCode: Int): String =
        canonPropertyState(canonPropertyCode).currentValue
            ?.let { CanonEosPtp.propertyLabel(canonPropertyCode, it) }
            ?: propertyDisplay(standardPropertyCode)

    private fun canonPropertyState(propertyCode: Int): CanonEosPropertyState = synchronized(canonProperties) {
        canonProperties[propertyCode] ?: CanonEosPropertyState()
    }

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
        val canonVendorProperties = JSONArray()
        val canonSnapshot = synchronized(canonProperties) { canonProperties.toSortedMap() }
        canonSnapshot.forEach { (propertyCode, state) ->
            canonVendorProperties.put(
                JSONObject()
                    .put("code", propertyCode.ptpHexCode())
                    .put("setting", CanonEosPtp.settingKey(propertyCode) ?: JSONObject.NULL)
                    .put("valueBytes", CanonEosPtp.propertyValueBytes(propertyCode) ?: JSONObject.NULL)
                    .put(
                        "rawValue",
                        state.currentValue?.let { "0x${it.toString(16).uppercase().padStart(8, '0')}" }
                            ?: JSONObject.NULL,
                    )
                    .put(
                        "current",
                        state.currentValue?.let { CanonEosPtp.propertyLabel(propertyCode, it) } ?: JSONObject.NULL,
                    )
                    .put("optionCount", state.availableValues.size)
                    .put(
                        "options",
                        JSONArray(CanonEosPtp.propertyOptions(propertyCode, state.availableValues).map { it.label }),
                    )
            )
        }
        return JSONObject()
            .put("kind", "ptp-usb")
            .put("vendorExtensionId", info.vendorExtensionId.toInt().ptpHexCode(8))
            .put("operations", JSONArray(info.operations.sorted().map { it.ptpHexCode() }))
            .put("advertisedDeviceProperties", JSONArray(info.deviceProperties.sorted().map { it.ptpHexCode() }))
            .put("loadedProperties", properties)
            .put("canonVendorProperties", canonVendorProperties)
            .apply { canonPropertyError?.let { put("canonPropertyError", it) } }
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

private data class CanonEosPropertyState(
    val currentValue: Long? = null,
    val availableValues: List<Long> = emptyList(),
)

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

private fun thumbnailContentType(format: Int, bytes: ByteArray): String? = when {
    format == PtpObjectFormat.EXIF_JPEG || bytes.hasJpegMarkers() -> "image/jpeg"
    format == PtpObjectFormat.PNG || bytes.hasPngSignature() -> "image/png"
    else -> null
}

private fun ByteArray.hasJpegMarkers(): Boolean =
    size >= 4 &&
        this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() &&
        this[lastIndex - 1] == 0xFF.toByte() && this[lastIndex] == 0xD9.toByte()

private fun ByteArray.hasPngSignature(): Boolean =
    size >= 8 &&
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() && this[2] == 0x4E.toByte() &&
        this[3] == 0x47.toByte() && this[4] == 0x0D.toByte() && this[5] == 0x0A.toByte() &&
        this[6] == 0x1A.toByte() && this[7] == 0x0A.toByte()

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
private const val CANON_EVENT_POLL_INTERVAL_MILLIS = 100L
private const val CANON_PROPERTY_DISCOVERY_ATTEMPTS = 10
private const val CANON_PROPERTY_DISCOVERY_RETRY_MILLIS = 50L
private const val CANON_CAPTURE_EVENT_TIMEOUT_MILLIS = 90_000L
private const val CANON_LIVE_VIEW_READY_TIMEOUT_MILLIS = 3_000L
