package dev.openeos.control.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class UsbPtpCameraBackend(
    override val connection: CameraConnection.AndroidUsbPtp,
    private val transportFactory: PtpTransportFactory,
    private val hostCaptureStore: UsbHostCaptureStore? = null,
    private val currentEpochSeconds: () -> Long = { Instant.now().epochSecond },
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
    private var canonLiveViewGeometry: CanonEosLiveViewGeometry? = null
    private val canonProperties = mutableMapOf<Int, CanonEosPropertyState>()
    private var canonPropertyDiscoveryAttempted = false
    private var canonPropertyError: String? = null
    private var selectedCaptureDestination: Long? = null
    private var advertisedStorageTargets: Map<String, Long> = emptyMap()
    private var bulbExposureActive = false
    private var bulbHostTransferPrepared = false
    private val canonEventMutex = Mutex()
    private val observedFeatures = ConcurrentHashMap.newKeySet<CameraFeature>()

    override fun observedFeatures(): Set<CameraFeature> = observedFeatures.toSet()

    override suspend fun initialize() {
        if (session != null) return
        val transport = transportFactory.open(connection)
        val newSession = PtpSession(transport)
        try {
            val info = newSession.initialize()
            deviceInfo = info
            session = newSession
            loadPropertyDescriptors(newSession, info)
            observedFeatures.addAll(setOf(CameraFeature.USB_DIAGNOSTICS, CameraFeature.CAMERA_IDENTITY))
        } catch (exception: Exception) {
            runCatching { newSession.shutdown() }
            throw exception
        }
    }

    override suspend fun close() {
        val current = session
        if (current != null) {
            if (bulbExposureActive) runCatching { stopBulbExposure() }
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
        canonLiveViewGeometry = null
        synchronized(canonProperties) { canonProperties.clear() }
        canonPropertyDiscoveryAttempted = false
        canonPropertyError = null
        selectedCaptureDestination = null
        advertisedStorageTargets = emptyMap()
        bulbExposureActive = false
        bulbHostTransferPrepared = false
        observedFeatures.clear()
        current?.shutdown()
    }

    override suspend fun info(): CameraInfo {
        val info = requireDeviceInfo()
        observedFeatures.add(CameraFeature.CAMERA_IDENTITY)
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
        val storageResult = refreshStorageSnapshot(info)
        val batteryLevel = propertyValues[PtpDevicePropertyCode.BATTERY_LEVEL]
            ?.unsignedLong()
            ?.takeIf { it <= 100UL }
            ?.toInt()
        if (PtpDevicePropertyCode.BATTERY_LEVEL in propertyValues) {
            observedFeatures.add(CameraFeature.BATTERY_STATUS)
        }
        if (storageResult?.isSuccess == true) observedFeatures.add(CameraFeature.STORAGE_STATUS)
        val standardFreeImages = storageSnapshot.mapNotNull {
            it.freeSpaceImages.takeIf { value -> value >= 0 && value != 0xFFFF_FFFFL }
        }
            .takeIf(List<Long>::isNotEmpty)?.sum()
        val freeImages = CanonEosPtp.availableShots(
            canonPropertyState(CanonEosPropertyCode.AVAILABLE_SHOTS).currentValue,
        ) ?: standardFreeImages
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
            storageTotalBytes = storageSnapshot.sumUnsignedBytesOrNull(PtpStorageInfo::maxCapacityBytes),
            storageFreeBytes = storageSnapshot.sumUnsignedBytesOrNull(PtpStorageInfo::freeSpaceBytes),
            storageFreeImages = freeImages,
            storageDeviceCount = storageSnapshot.size,
            rawBatteryJson = batteryPropertyJson(batteryLevel),
            rawStorageJson = storageError?.let(::storageErrorJson) ?: storageSnapshot.toStorageJson(),
            rawTransportJson = ptpTransportJson(info),
            bulbExposureActive = bulbExposureActive,
        )
    }

    override suspend fun capabilities(): CameraCapabilities {
        val info = requireDeviceInfo()
        refreshCanonPropertyState(info)
        if (storageSnapshot.isEmpty() && supportsStorage(info)) refreshStorageSnapshot(info)
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
            info = info,
            canSetStandardProperties = canSetProperties,
            canSetCanonProperties = CanonEosPtp.supportsPropertyControl(info),
        )
        val supportsCanonRelease = CanonEosPtp.supportsRemoteRelease(info)
        val supportsCanonEvents = CanonEosPtp.supportsRemotePreparation(info)
        val supportsCanonAutofocus = CanonEosPtp.supportsAutofocus(info)
        val supportsCanonLiveView = CanonEosPtp.supportsLiveView(info)
        val supportsCanonFocusDrive = CanonEosPtp.supportsFocusDrive(info)
        val supportsCanonLiveViewMagnification = CanonEosPtp.supportsLiveViewMagnification(info)
        val supportsCanonTouchAutofocus = CanonEosPtp.supportsTouchAutofocus(info)
        val supportsCanonClickWhiteBalance = CanonEosPtp.supportsClickWhiteBalance(info)
        val supportsCanonMovieRecording = CanonEosPtp.supportsMovieRecording(
            info,
            canonPropertyState(CanonEosPropertyCode.EVF_RECORD_STATUS).availableValues,
        )
        val supportsCanonClockSync = canonClockPropertyCode(info) != null
        val supportsHostMedia = hostCaptureStore != null
        val supported = buildSet {
            add(CameraFeature.USB_DIAGNOSTICS)
            add(CameraFeature.CAMERA_IDENTITY)
            if (supportsCanonEvents) add(CameraFeature.EVENT_POLLING)
            if (PtpDevicePropertyCode.BATTERY_LEVEL in propertyDescriptors) add(CameraFeature.BATTERY_STATUS)
            if (supportsStorage(info)) add(CameraFeature.STORAGE_STATUS)
            if (supportsMediaBrowser(info) || supportsHostMedia) add(CameraFeature.MEDIA_BROWSER)
            if ((supportsMediaBrowser(info) && info.supports(PtpOperationCode.GET_THUMB)) || supportsHostMedia) {
                add(CameraFeature.MEDIA_THUMBNAIL)
            }
            if (info.supports(PtpOperationCode.GET_OBJECT) || supportsHostMedia) add(CameraFeature.MEDIA_PREVIEW)
            if (info.supports(PtpOperationCode.GET_OBJECT) || supportsHostMedia) add(CameraFeature.MEDIA_DOWNLOAD)
            if (info.supports(PtpOperationCode.DELETE_OBJECT) || supportsHostMedia) add(CameraFeature.MEDIA_DELETE)
            if (info.supports(PtpOperationCode.INITIATE_CAPTURE) || supportsCanonRelease) {
                add(CameraFeature.STILL_CAPTURE)
            }
            if (supportsCanonRelease) {
                add(CameraFeature.SHUTTER_HALF_PRESS)
                add(CameraFeature.BULB_EXPOSURE)
            }
            if (supportsCanonAutofocus || supportsCanonRelease) add(CameraFeature.AUTOFOCUS)
            if (supportsCanonLiveView) {
                add(CameraFeature.LIVE_VIEW)
                add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
            }
            if (supportsCanonTouchAutofocus) add(CameraFeature.TAP_FOCUS)
            if (supportsCanonClickWhiteBalance) add(CameraFeature.CLICK_WHITE_BALANCE)
            if (supportsCanonFocusDrive) add(CameraFeature.FOCUS_DRIVE)
            if (supportsCanonLiveViewMagnification) add(CameraFeature.LIVE_VIEW_MAGNIFICATION)
            if (supportsCanonMovieRecording) add(CameraFeature.VIDEO_RECORDING)
            if (iso.isNotEmpty() || shutter.isNotEmpty() || aperture.isNotEmpty()) {
                add(CameraFeature.EXPOSURE_CONTROL)
            }
            if (whiteBalance.isNotEmpty()) add(CameraFeature.WHITE_BALANCE_CONTROL)
            if (advancedSettings.isNotEmpty()) add(CameraFeature.ADVANCED_SETTINGS)
            if (supportsCanonClockSync) add(CameraFeature.CAMERA_CLOCK_SYNC)
        }
        val candidates = setOf(
            CameraFeature.BATTERY_STATUS,
            CameraFeature.STORAGE_STATUS,
            CameraFeature.EVENT_POLLING,
            CameraFeature.STILL_CAPTURE,
            CameraFeature.BULB_EXPOSURE,
            CameraFeature.AUTOFOCUS,
            CameraFeature.SHUTTER_HALF_PRESS,
            CameraFeature.VIDEO_RECORDING,
            CameraFeature.TAP_FOCUS,
            CameraFeature.CLICK_WHITE_BALANCE,
            CameraFeature.LIVE_VIEW,
            CameraFeature.LIVE_VIEW_JPEG_POLLING,
            CameraFeature.LIVE_VIEW_MAGNIFICATION,
            CameraFeature.FOCUS_DRIVE,
            CameraFeature.EXPOSURE_CONTROL,
            CameraFeature.WHITE_BALANCE_CONTROL,
            CameraFeature.ADVANCED_SETTINGS,
            CameraFeature.MEDIA_BROWSER,
            CameraFeature.MEDIA_THUMBNAIL,
            CameraFeature.MEDIA_PREVIEW,
            CameraFeature.MEDIA_DOWNLOAD,
            CameraFeature.MEDIA_DELETE,
            CameraFeature.CAMERA_CLOCK_SYNC,
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
                    CameraFeature.EVENT_POLLING to
                        "Uses Canon EOS GetEvent only when SetRemoteMode, SetEventMode, and GetEvent are all advertised.",
                    CameraFeature.STILL_CAPTURE to
                        "Uses standard InitiateCapture or Canon EOS RemoteReleaseOn/Off; advertised host-RAM transfers are downloaded in 1 MiB partial-object chunks before TransferComplete.",
                    CameraFeature.SHUTTER_HALF_PRESS to
                        "Uses Canon EOS RemoteReleaseOn/Off only when the camera advertises the full remote event sequence.",
                    CameraFeature.BULB_EXPOSURE to
                        "Holds Canon EOS RemoteReleaseOn half/full until explicit RemoteReleaseOff full/half cleanup.",
                    CameraFeature.AUTOFOCUS to
                        "Prefers advertised Canon EOS DoAf/AfCancel and falls back to a balanced half-press sequence.",
                    CameraFeature.TAP_FOCUS to
                        "Requires advertised Canon EOS TouchAfPosition, complete Live View, a balanced AF path, and sensor geometry from viewfinder block 0x0E.",
                    CameraFeature.CLICK_WHITE_BALANCE to
                        "Requires advertised Canon EOS ClickWB, complete Live View, and sensor geometry from viewfinder block 0x0E.",
                    CameraFeature.FOCUS_DRIVE to
                        "Uses Canon EOS DriveLens with the Near/Far 1-3 values documented by libgphoto2.",
                    CameraFeature.LIVE_VIEW_MAGNIFICATION to
                        "Uses the advertised Canon EOS Zoom operation with the libgphoto2-verified 1x and 5x values.",
                    CameraFeature.VIDEO_RECORDING to
                        "Uses Canon EOS EVFRecordStatus only when camera events advertise both Card and None values.",
                    CameraFeature.MEDIA_BROWSER to
                        "Uses standard camera object operations plus completed Canon EOS host captures stored in app-private media.",
                    CameraFeature.MEDIA_THUMBNAIL to
                        "Uses advertised standard PTP GetThumb for card media or Android decoding for app-private host captures.",
                    CameraFeature.MEDIA_PREVIEW to
                        "Uses bounded standard PTP GetObject or app-private host files only for complete JPEG/PNG images up to 32 MiB.",
                    CameraFeature.CAMERA_CLOCK_SYNC to
                        "Requires an advertised Canon EOS UTC/CameraTime event plus SetDevicePropValueEx; " +
                        "success requires a matching post-write event readback.",
                    CameraFeature.MEDIA_DOWNLOAD to
                        "Uses standard GetObject with bounded USB reads or streams a completed app-private host capture.",
                    CameraFeature.MEDIA_DELETE to
                        "Uses standard DeleteObject for card media and local deletion for app-private host captures.",
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
                    magnifications = if (supportsCanonLiveViewMagnification) {
                        listOf(LiveViewMagnification.X1, LiveViewMagnification.X5)
                    } else {
                        emptyList()
                    },
                    currentMagnification = if (supportsCanonLiveViewMagnification) {
                        LiveViewMagnification.X1
                    } else {
                        null
                    },
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
                observedFeatures = observedFeatures.toSet(),
                truncated = info.operations.size > MAX_CAPABILITY_EVIDENCE_ITEMS ||
                    writableSettings.size > MAX_CAPABILITY_EVIDENCE_ITEMS,
            ),
        )
    }

    override suspend fun pollEvent(): CameraEvent {
        if (!CanonEosPtp.supportsRemotePreparation(requireDeviceInfo())) {
            unsupported<CameraEvent>(CameraFeature.EVENT_POLLING)
        }
        ensureCanonRemoteMode()
        repeat(CANON_EVENT_LONG_POLL_ATTEMPTS) {
            val changedKeys = canonEventMutex.withLock {
                val payload = drainCanonEventsLocked()
                handleExternalCanonTransfersLocked(payload)
                canonEventChangeKeys(payload)
            }
            observedFeatures.add(CameraFeature.EVENT_POLLING)
            if (changedKeys.isNotEmpty()) return CameraEvent(changedKeys)
            delay(CANON_EVENT_POLL_INTERVAL_MILLIS)
        }
        return CameraEvent()
    }

    override suspend fun syncCameraClock(): CameraStatus {
        val info = requireDeviceInfo()
        refreshCanonPropertyState(info)
        val propertyCode = canonClockPropertyCode(info)
            ?: unsupported<Int>(CameraFeature.CAMERA_CLOCK_SYNC)
        val requested = currentEpochSeconds()
        if (requested !in 0L..UINT32_MAX) {
            throw PtpProtocolException("Current Unix time $requested does not fit the Canon EOS UINT32 clock property.")
        }

        ensureCanonRemoteMode()
        var lastReadback: Long? = null
        val verified = canonEventMutex.withLock {
            drainCanonEventsLocked()
            requireSession().executeDataOutOperation(
                operationCode = CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
                payload = CanonEosPtp.uint32PropertyPayload(propertyCode, requested),
            )
            withTimeoutOrNull(CANON_CLOCK_SYNC_VERIFY_TIMEOUT_MILLIS) {
                while (true) {
                    val payload = drainCanonEventsLocked()
                    CanonEosPtp.propertyUpdates(payload)
                        .lastOrNull { it.propertyCode == propertyCode && it.currentValue != null }
                        ?.currentValue
                        ?.let { readback ->
                            lastReadback = readback
                            if (abs(readback - requested) <= CANON_CLOCK_SYNC_TOLERANCE_SECONDS) {
                                return@withTimeoutOrNull readback
                            }
                        }
                    delay(CANON_EVENT_POLL_INTERVAL_MILLIS)
                }
            }
        }
        if (verified == null) {
            throw PtpProtocolException(
                "Canon EOS accepted the clock write but did not report a matching ${propertyCode.ptpHexCode()} readback " +
                    "within ${CANON_CLOCK_SYNC_VERIFY_TIMEOUT_MILLIS / 1_000} seconds " +
                    "(requested=$requested, last=${lastReadback ?: "none"})."
            )
        }
        observedFeatures.add(CameraFeature.CAMERA_CLOCK_SYNC)
        return status()
    }

    override suspend fun captureStill(): CameraStatus {
        val info = requireDeviceInfo()
        if (!CanonEosPtp.supportsRemoteRelease(info) && info.supports(PtpOperationCode.INITIATE_CAPTURE)) {
            requireSession().initiateCapture()
            observedFeatures.add(CameraFeature.STILL_CAPTURE)
            return status()
        }
        if (!CanonEosPtp.supportsRemoteRelease(info)) unsupported<Unit>(CameraFeature.STILL_CAPTURE)

        ensureCanonRemoteMode()
        val ptp = requireSession()
        canonEventMutex.withLock {
            drainCanonEventsLocked()
            val hostTransferPrepared = prepareCanonCaptureDestination()
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
            awaitCanonCapturedObjectLocked(hostTransferPrepared)
        }
        observedFeatures.add(CameraFeature.STILL_CAPTURE)
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
        observedFeatures.add(CameraFeature.SHUTTER_HALF_PRESS)
        return status()
    }

    override suspend fun startBulbExposure(): CameraStatus {
        if (bulbExposureActive) return status()
        if (!CanonEosPtp.supportsRemoteRelease(requireDeviceInfo())) {
            unsupported<Unit>(CameraFeature.BULB_EXPOSURE)
        }
        ensureCanonRemoteMode()
        val baseline = status()
        val ptp = requireSession()
        val hostTransferPrepared = canonEventMutex.withLock {
            drainCanonEventsLocked()
            val prepared = prepareCanonCaptureDestination()
            try {
                ptp.executeOperation(CanonEosOperationCode.REMOTE_RELEASE_ON, listOf(1L, 0L))
                ptp.executeOperation(CanonEosOperationCode.REMOTE_RELEASE_ON, listOf(2L, 0L))
            } catch (exception: Throwable) {
                withContext(NonCancellable) {
                    listOf(2L, 1L).forEach { stage ->
                        runCatching { ptp.executeOperation(CanonEosOperationCode.REMOTE_RELEASE_OFF, listOf(stage)) }
                            .exceptionOrNull()
                            ?.let(exception::addSuppressed)
                    }
                }
                throw exception
            }
            prepared
        }
        bulbHostTransferPrepared = hostTransferPrepared
        bulbExposureActive = true
        return baseline.copy(bulbExposureActive = true)
    }

    override suspend fun stopBulbExposure(): CameraStatus {
        if (!bulbExposureActive) return status()
        val ptp = requireSession()
        canonEventMutex.withLock {
            var primaryFailure: Throwable? = null
            try {
                withContext(NonCancellable) {
                    ptp.executeOperation(CanonEosOperationCode.REMOTE_RELEASE_OFF, listOf(2L))
                }
            } catch (exception: Throwable) {
                primaryFailure = exception
            } finally {
                try {
                    withContext(NonCancellable) {
                        ptp.executeOperation(CanonEosOperationCode.REMOTE_RELEASE_OFF, listOf(1L))
                    }
                } catch (releaseFailure: Throwable) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(releaseFailure)
                    } else {
                        primaryFailure = releaseFailure
                    }
                }
            }
            primaryFailure?.let { throw it }
            bulbExposureActive = false
            val hostTransferPrepared = bulbHostTransferPrepared
            bulbHostTransferPrepared = false
            awaitCanonCapturedObjectLocked(hostTransferPrepared)
        }
        observedFeatures.add(CameraFeature.BULB_EXPOSURE)
        return status()
    }

    override suspend fun autofocus(): CameraStatus {
        val info = requireDeviceInfo()
        if (!CanonEosPtp.supportsAutofocus(info)) {
            if (!CanonEosPtp.supportsRemoteRelease(info)) unsupported<Unit>(CameraFeature.AUTOFOCUS)
            return halfPressShutter().also { observedFeatures.add(CameraFeature.AUTOFOCUS) }
        }

        ensureCanonRemoteMode()
        drainCanonEvents()
        val ptp = requireSession()
        var primaryFailure: Throwable? = null
        try {
            ptp.executeOperation(CanonEosOperationCode.DO_AF)
            delay(CANON_AUTOFOCUS_HOLD_MILLIS)
            drainCanonEvents()
        } catch (exception: Throwable) {
            primaryFailure = exception
            throw exception
        } finally {
            try {
                withContext(NonCancellable) {
                    ptp.executeOperation(CanonEosOperationCode.AF_CANCEL)
                }
            } catch (releaseFailure: Throwable) {
                primaryFailure?.addSuppressed(releaseFailure) ?: throw releaseFailure
            }
        }
        drainCanonEvents()
        observedFeatures.add(CameraFeature.AUTOFOCUS)
        return status()
    }

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
        observedFeatures.add(CameraFeature.FOCUS_DRIVE)
        return FocusDriveResult(ok = true, direction = direction, step = step)
    }

    override suspend fun setLiveViewMagnification(
        magnification: LiveViewMagnification,
    ): LiveViewMagnificationResult {
        if (!CanonEosPtp.supportsLiveViewMagnification(requireDeviceInfo())) {
            unsupported<Unit>(CameraFeature.LIVE_VIEW_MAGNIFICATION)
        }
        if (!canonLiveViewActive) {
            throw PtpProtocolException("Canon EOS Live View magnification requires an active Live View session.")
        }
        requireSession().executeOperation(
            CanonEosOperationCode.ZOOM,
            listOf(magnification.value.toLong()),
        )
        drainCanonEvents()
        observedFeatures.add(CameraFeature.LIVE_VIEW_MAGNIFICATION)
        return LiveViewMagnificationResult(ok = true, magnification = magnification)
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
        observedFeatures.add(CameraFeature.EXPOSURE_CONTROL)
        return status()
    }

    override suspend fun setWhiteBalance(value: String): CameraStatus {
        setCoreProperty(
            CanonEosPropertyCode.WHITE_BALANCE,
            PtpDevicePropertyCode.WHITE_BALANCE,
            value,
            CameraFeature.WHITE_BALANCE_CONTROL,
        )
        observedFeatures.add(CameraFeature.WHITE_BALANCE_CONTROL)
        return status()
    }

    override suspend fun setSetting(key: String, value: String): CameraStatus {
        val info = requireDeviceInfo()
        refreshCanonPropertyState(info)
        if (key == USB_CAPTURE_TARGET_KEY) {
            setCanonCaptureTarget(info, value)
            observedFeatures.add(CameraFeature.ADVANCED_SETTINGS)
            return status()
        }
        if (key == USB_CAPTURE_STORAGE_KEY) {
            setCanonCaptureStorage(info, value)
            observedFeatures.add(CameraFeature.ADVANCED_SETTINGS)
            return status()
        }
        if (key == USB_MOVIE_MODE_KEY) {
            if (setCanonMovieMode(info, value)) {
                observedFeatures.add(CameraFeature.ADVANCED_SETTINGS)
            }
            return status()
        }
        val canonSpec = CanonEosPtp.settingSpecs.firstOrNull { it.key == key }
        if (canonSpec != null && setAdvertisedCanonProperty(canonSpec.propertyCode, value)) {
            observedFeatures.add(CameraFeature.ADVANCED_SETTINGS)
            return status()
        }
        val spec = PtpStandardProperties.advancedProperties.firstOrNull { it.key == key }
            ?: throw UnsupportedOperationException("USB PTP setting $key is not implemented.")
        setProperty(spec.propertyCode, value, CameraFeature.ADVANCED_SETTINGS)
        observedFeatures.add(CameraFeature.ADVANCED_SETTINGS)
        return status()
    }

    override suspend fun listMedia(): List<CameraMediaItem> {
        requireMediaBrowser()
        val hostItems = hostCaptureStore?.list().orEmpty()
        if (!supportsMediaBrowser(requireDeviceInfo())) {
            if (hostItems.isNotEmpty()) observedFeatures.add(CameraFeature.MEDIA_BROWSER)
            return hostItems
        }
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
        if (handles.isNotEmpty() && items.isEmpty() && firstFailure != null && hostItems.isEmpty()) throw firstFailure!!
        observedFeatures.add(CameraFeature.MEDIA_BROWSER)
        return (hostItems + items).take(MAX_USB_MEDIA_ITEMS)
    }

    override suspend fun mediaThumbnail(item: CameraMediaItem): CameraMediaThumbnail {
        hostCaptureStore?.takeIf { it.owns(item) }?.let { store ->
            return store.thumbnail(item).also { observedFeatures.add(CameraFeature.MEDIA_THUMBNAIL) }
        }
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
        ).also { observedFeatures.add(CameraFeature.MEDIA_THUMBNAIL) }
    }

    override suspend fun mediaPreview(item: CameraMediaItem): CameraMediaPreview {
        hostCaptureStore?.takeIf { it.owns(item) }?.let { store ->
            return store.preview(item).also { observedFeatures.add(CameraFeature.MEDIA_PREVIEW) }
        }
        requireOperation(PtpOperationCode.GET_OBJECT, CameraFeature.MEDIA_PREVIEW)
        val handle = item.ptpHandle()
        val objectInfo = mediaInfo[handle] ?: requireSession().objectInfo(handle).also { mediaInfo[handle] = it }
        val mediaItem = objectInfo.toMediaItem()
        if (!mediaItem.previewAvailable) {
            throw PtpProtocolException("${item.name} does not advertise a bounded JPEG or PNG preview object.")
        }
        val output = BoundedByteArrayOutputStream(MAX_PTP_MEDIA_PREVIEW_BYTES)
        requireSession().downloadObject(handle, output)
        val bytes = output.toByteArray()
        val contentType = mediaPreviewContentType(objectInfo.objectFormat, bytes)
            ?: throw PtpProtocolException("${item.name} is not a complete JPEG or PNG image.")
        return CameraMediaPreview(
            item = mediaItem,
            bytes = bytes,
            contentType = contentType,
        ).also { observedFeatures.add(CameraFeature.MEDIA_PREVIEW) }
    }

    override suspend fun downloadMedia(
        item: CameraMediaItem,
        destination: OutputStream,
        onProgress: (CameraMediaTransferProgress) -> Unit,
    ): CameraMediaDownloadResult {
        hostCaptureStore?.takeIf { it.owns(item) }?.let { store ->
            return store.download(item, destination, onProgress)
                .also { observedFeatures.add(CameraFeature.MEDIA_DOWNLOAD) }
        }
        requireOperation(PtpOperationCode.GET_OBJECT, CameraFeature.MEDIA_DOWNLOAD)
        val handle = item.ptpHandle()
        val bytesTransferred = requireSession().downloadObject(handle, destination) { transferred, total ->
            onProgress(CameraMediaTransferProgress(transferred, total.takeIf { it > 0L } ?: item.sizeBytes))
        }
        return CameraMediaDownloadResult(
            item = item,
            bytesTransferred = bytesTransferred,
            contentType = contentTypeFor(item.name, item.kind),
        ).also { observedFeatures.add(CameraFeature.MEDIA_DOWNLOAD) }
    }

    override suspend fun deleteMedia(item: CameraMediaItem) {
        hostCaptureStore?.takeIf { it.owns(item) }?.let { store ->
            store.delete(item)
            observedFeatures.add(CameraFeature.MEDIA_DELETE)
            return
        }
        requireOperation(PtpOperationCode.DELETE_OBJECT, CameraFeature.MEDIA_DELETE)
        requireSession().deleteObject(item.ptpHandle())
        observedFeatures.add(CameraFeature.MEDIA_DELETE)
    }

    override suspend fun startLiveView(request: LiveViewRequest) {
        if (!CanonEosPtp.supportsLiveView(requireDeviceInfo())) unsupported<Unit>(CameraFeature.LIVE_VIEW)
        if (canonLiveViewActive) return
        canonLiveViewGeometry = null
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
            observedFeatures.add(CameraFeature.LIVE_VIEW)
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
            canonLiveViewGeometry = null
        }
    }

    override suspend fun startRecording(): CameraStatus = setCanonMovieRecording(recording = true)

    override suspend fun stopRecording(): CameraStatus = setCanonMovieRecording(recording = false)

    override suspend fun tapFocus(x: Double, y: Double): FocusResult {
        val info = requireDeviceInfo()
        if (!CanonEosPtp.supportsTouchAutofocus(info)) unsupported<Unit>(CameraFeature.TAP_FOCUS)
        val (cameraX, cameraY) = canonLiveViewCoordinates(x, y, action = "Touch AF")

        ensureCanonRemoteMode()
        requireSession().executeOperation(
            CanonEosOperationCode.TOUCH_AF_POSITION,
            listOf(CANON_TOUCH_AF_MODE, cameraX, cameraY),
        )
        autofocus()
        observedFeatures.add(CameraFeature.TAP_FOCUS)
        return FocusResult(ok = true, x = x, y = y)
    }

    override suspend fun clickWhiteBalance(x: Double, y: Double): CameraStatus {
        val info = requireDeviceInfo()
        if (!CanonEosPtp.supportsClickWhiteBalance(info)) {
            unsupported<Unit>(CameraFeature.CLICK_WHITE_BALANCE)
        }
        val (cameraX, cameraY) = canonLiveViewCoordinates(x, y, action = "Click WB")

        ensureCanonRemoteMode()
        requireSession().executeOperation(
            CanonEosOperationCode.CLICK_WHITE_BALANCE,
            listOf(cameraX, cameraY),
        )
        observedFeatures.add(CameraFeature.CLICK_WHITE_BALANCE)
        return status()
    }

    override fun liveViewFrameUrl(cacheKey: Long, request: LiveViewRequest): String =
        throw UnsupportedOperationException("USB PTP Live View frames are returned as in-memory JPEG data.")

    override suspend fun liveViewFrame(cacheKey: Long, request: LiveViewRequest): LiveViewFrame {
        if (!canonLiveViewActive) throw PtpProtocolException("Canon EOS USB Live View is not running.")
        val payload = readCanonViewfinderData()
        val data = CanonEosPtp.liveViewData(payload)
        data.geometry?.let { canonLiveViewGeometry = it }
        return LiveViewFrame(
            bytes = data.jpeg,
            contentType = "image/jpeg",
            sourceUrl = "ptp-usb://canon-eos/viewfinder?frame=$cacheKey",
        ).also {
            observedFeatures.add(CameraFeature.LIVE_VIEW)
            observedFeatures.add(CameraFeature.LIVE_VIEW_JPEG_POLLING)
        }
    }

    private suspend fun ensureCanonRemoteMode() {
        if (canonRemotePrepared) return
        canonEventMutex.withLock { ensureCanonRemoteModeLocked() }
    }

    private suspend fun canonLiveViewCoordinates(
        x: Double,
        y: Double,
        action: String,
    ): Pair<Long, Long> {
        if (!canonLiveViewActive) {
            throw PtpProtocolException("Canon EOS USB $action requires an active Live View session.")
        }
        if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) {
            throw PtpProtocolException(
                "Canon EOS USB $action coordinates must be normalized to 0.0..1.0."
            )
        }

        val geometry = canonLiveViewGeometry ?: CanonEosPtp.liveViewData(readCanonViewfinderData())
            .geometry
            ?.also { canonLiveViewGeometry = it }
            ?: throw PtpProtocolException(
                "Canon EOS USB $action requires sensor geometry from Live View block 0x0E."
            )
        return (x * geometry.width.toDouble()).toLong() to
            (y * geometry.height.toDouble()).toLong()
    }

    private suspend fun ensureCanonRemoteModeLocked() {
        if (canonRemotePrepared) return
        val info = requireDeviceInfo()
        if (!CanonEosPtp.supportsRemotePreparation(info)) {
            throw PtpProtocolException("Camera does not advertise the Canon EOS remote/event mode sequence.")
        }
        val ptp = requireSession()
        ptp.executeOperation(CanonEosOperationCode.SET_REMOTE_MODE, listOf(1L))
        try {
            ptp.executeOperation(CanonEosOperationCode.SET_EVENT_MODE, listOf(1L))
            drainCanonEventsLocked()
            canonRemotePrepared = true
        } catch (exception: Exception) {
            runCatching { ptp.executeOperation(CanonEosOperationCode.SET_REMOTE_MODE, listOf(0L)) }
            throw exception
        }
    }

    private suspend fun drainCanonEvents(): ByteArray = canonEventMutex.withLock { drainCanonEventsLocked() }

    private suspend fun drainCanonEventsLocked(): ByteArray =
        requireSession().executeDataInOperation(CanonEosOperationCode.GET_EVENT).also(::applyCanonPropertyUpdates)

    private suspend fun refreshCanonPropertyState(info: PtpDeviceInfo) {
        if (!CanonEosPtp.supportsRemotePreparation(info)) return
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
                    if (hasCanonPropertyDiscoveryEvidence()) break
                    delay(CANON_PROPERTY_DISCOVERY_RETRY_MILLIS)
                    drainCanonEvents()
                }
            } else {
                drainCanonEvents()
            }
            canonPropertyError = if (hasCanonPropertyDiscoveryEvidence()) {
                null
            } else {
                "Canon EOS remote mode returned no supported property events."
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
                if (
                    update.propertyCode == CanonEosPropertyCode.CAPTURE_DESTINATION &&
                    update.currentValue != null &&
                    selectedCaptureDestination != null &&
                    update.currentValue != selectedCaptureDestination
                ) {
                    selectedCaptureDestination = null
                }
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

    private fun hasCanonPropertyDiscoveryEvidence(): Boolean =
        hasCanonCorePropertyOptions() ||
            canonPropertyState(CanonEosPropertyCode.FIXED_MOVIE).currentValue in 0L..1L

    private suspend fun awaitCanonCapturedObjectLocked(hostTransferPrepared: Boolean) {
        var deadline = System.currentTimeMillis() + CANON_CAPTURE_EVENT_TIMEOUT_MILLIS
        var hostTransferCount = 0
        var hostTransferQuietAt = Long.MAX_VALUE
        do {
            val payload = drainCanonEventsLocked()
            val transfers = CanonEosPtp.objectTransferRequests(payload)
            if (transfers.isNotEmpty()) {
                if (!hostTransferPrepared) {
                    throw PtpProtocolException(
                        "Canon EOS requested a host object transfer without the required advertised transfer operations."
                    )
                }
                transfers.forEach { transfer -> downloadCanonHostCapture(transfer) }
                hostTransferCount += transfers.size
                hostTransferQuietAt = System.currentTimeMillis() + CANON_HOST_TRANSFER_QUIET_MILLIS
                deadline = maxOf(deadline, hostTransferQuietAt)
            }
            if (CanonEosPtp.containsCardCapturedObjectEvent(payload)) return
            if (hostTransferCount > 0 && System.currentTimeMillis() >= hostTransferQuietAt) return
            delay(CANON_EVENT_POLL_INTERVAL_MILLIS)
        } while (System.currentTimeMillis() < deadline)
        throw PtpProtocolException(
            "Canon EOS shutter commands completed, but the camera did not report a captured object " +
                "within ${CANON_CAPTURE_EVENT_TIMEOUT_MILLIS / 1_000} seconds."
        )
    }

    private suspend fun handleExternalCanonTransfersLocked(payload: ByteArray) {
        val transfers = CanonEosPtp.objectTransferRequests(payload)
        if (transfers.isEmpty()) return
        val info = requireDeviceInfo()
        if (!supportsCanonHostCaptureTarget(info) || hostCaptureStore == null) {
            throw PtpProtocolException(
                "Canon EOS requested a host object transfer outside an app capture, but the verified host-transfer path is unavailable."
            )
        }
        if (!prepareCanonHostCapacity(info)) {
            throw PtpProtocolException("Canon EOS host capacity could not be prepared for an external camera capture.")
        }
        transfers.forEach { downloadCanonHostCapture(it) }
    }

    private fun canonEventChangeKeys(payload: ByteArray): Set<String> = buildSet {
        val eventCodes = CanonEosPtp.eventCodes(payload)
        val updates = CanonEosPtp.propertyUpdates(payload)
        if (
            CanonEosEventCode.PROPERTY_VALUE_CHANGED in eventCodes ||
            CanonEosEventCode.AVAILABLE_LIST_CHANGED in eventCodes
        ) {
            add("shootingsettings")
        }
        if (updates.any { it.propertyCode == CanonEosPropertyCode.EVF_RECORD_STATUS }) add("recording")
        if (
            updates.any {
                it.propertyCode == CanonEosPropertyCode.AVAILABLE_SHOTS ||
                    it.propertyCode == CanonEosPropertyCode.CAPTURE_DESTINATION ||
                    it.propertyCode == CanonEosPropertyCode.CURRENT_STORAGE
            }
        ) {
            add("storage")
        }
        if (CanonEosPtp.containsCapturedObjectEvent(payload)) add("contents")
    }

    private suspend fun downloadCanonHostCapture(request: CanonEosObjectTransferRequest) {
        if (request.handle == 0L || request.sizeBytes <= 0L || request.sizeBytes > UINT32_MAX) {
            throw PtpProtocolException(
                "Canon EOS host transfer advertised invalid handle/size " +
                    "0x${request.handle.toString(16)}/${request.sizeBytes}."
            )
        }
        val store = hostCaptureStore
            ?: throw PtpProtocolException("Android host-capture storage is unavailable.")
        val ptp = requireSession()
        val filename = request.filename ?: synthesizedCaptureFilename(request.objectFormat)
        val item = store.save(
            filename = filename,
            kind = mediaKind(filename, request.objectFormat),
            expectedSizeBytes = request.sizeBytes,
        ) { output ->
            var offset = 0L
            while (offset < request.sizeBytes) {
                val requested = minOf(CANON_HOST_TRANSFER_CHUNK_BYTES.toLong(), request.sizeBytes - offset).toInt()
                val chunk = ptp.partialObject(request.handle, offset, requested)
                if (chunk.size > requested || offset + chunk.size > request.sizeBytes) {
                    throw PtpProtocolException(
                        "Canon EOS host transfer returned ${chunk.size} bytes at offset $offset; " +
                            "$requested were requested."
                    )
                }
                output.write(chunk)
                offset += chunk.size
            }
            offset
        }
        try {
            ptp.executeOperation(CanonEosOperationCode.TRANSFER_COMPLETE, listOf(request.handle))
        } catch (exception: Exception) {
            runCatching { store.delete(item) }
            throw exception
        }
        observedFeatures.add(CameraFeature.MEDIA_BROWSER)
        observedFeatures.add(CameraFeature.MEDIA_DOWNLOAD)
    }

    private suspend fun prepareCanonCaptureDestination(): Boolean {
        val state = canonPropertyState(CanonEosPropertyCode.CAPTURE_DESTINATION)
        if (state.currentValue != CanonEosPtp.CAPTURE_DESTINATION_HOST) return false
        val info = requireDeviceInfo()
        val hostTransferReady = supportsCanonHostCaptureTarget(info) &&
            prepareCanonHostCapacity(info)
        if (hostTransferReady) return true
        if (selectedCaptureDestination == CanonEosPtp.CAPTURE_DESTINATION_HOST) {
            throw PtpProtocolException(
                "Canon EOS cannot prepare the explicitly selected phone capture destination."
            )
        }
        ensureCanonCaptureDestinationOnCard()
        return false
    }

    private suspend fun prepareCanonHostCapacity(info: PtpDeviceInfo): Boolean {
        val availableShots = CanonEosPtp.availableShots(
            canonPropertyState(CanonEosPropertyCode.AVAILABLE_SHOTS).currentValue,
        )
        if (availableShots != null && availableShots >= CANON_HOST_MIN_AVAILABLE_SHOTS) return true
        if (!info.supports(CanonEosOperationCode.PC_HDD_CAPACITY)) return false
        return try {
            requireSession().executeOperation(
                CanonEosOperationCode.PC_HDD_CAPACITY,
                listOf(CANON_HOST_CAPACITY_CLUSTERS, CANON_HOST_CAPACITY_CLUSTER_BYTES, 1L),
            )
            true
        } catch (exception: PtpResponseException) {
            exception.responseCode == PtpResponseCode.DEVICE_BUSY
        }
    }

    private suspend fun ensureCanonCaptureDestinationOnCard() {
        val state = canonPropertyState(CanonEosPropertyCode.CAPTURE_DESTINATION)
        if (state.currentValue != CanonEosPtp.CAPTURE_DESTINATION_HOST) return

        val cardTarget = CanonEosPtp.captureDestinationCardValue(state.availableValues)
            ?: throw PtpProtocolException(
                "Canon EOS is targeting host RAM but did not advertise a memory-card capture destination."
            )
        requireSession().executeDataOutOperation(
            operationCode = CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
            payload = CanonEosPtp.uint32PropertyPayload(
                CanonEosPropertyCode.CAPTURE_DESTINATION,
                cardTarget,
            ),
        )
        synchronized(canonProperties) {
            canonProperties[CanonEosPropertyCode.CAPTURE_DESTINATION] = state.copy(currentValue = cardTarget)
        }
        selectedCaptureDestination = null
    }

    private suspend fun setCanonCaptureTarget(info: PtpDeviceInfo, label: String) {
        val control = captureTargetControl(info)
            ?: unsupported<CameraSettingControl>(CameraFeature.ADVANCED_SETTINGS)
        if (label !in control.values) {
            throw PtpProtocolException("Value '$label' is not an available Android USB capture target.")
        }
        val state = canonPropertyState(CanonEosPropertyCode.CAPTURE_DESTINATION)
        val target = when (label) {
            USB_CAPTURE_TARGET_PHONE -> CanonEosPtp.CAPTURE_DESTINATION_HOST
            USB_CAPTURE_TARGET_CARD -> CanonEosPtp.captureDestinationCardValue(state.availableValues)
                ?: throw PtpProtocolException("Canon EOS did not advertise a memory-card capture destination.")
            else -> throw PtpProtocolException("Value '$label' is not an available Android USB capture target.")
        }
        ensureCanonRemoteMode()
        requireSession().executeDataOutOperation(
            operationCode = CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
            payload = CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.CAPTURE_DESTINATION, target),
        )
        synchronized(canonProperties) {
            canonProperties[CanonEosPropertyCode.CAPTURE_DESTINATION] = state.copy(currentValue = target)
        }
        selectedCaptureDestination = target
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
        observedFeatures.add(CameraFeature.VIDEO_RECORDING)
        return status()
    }

    private suspend fun setCanonMovieMode(info: PtpDeviceInfo, label: String): Boolean {
        val control = movieModeControl(info)
            ?: unsupported<CameraSettingControl>(CameraFeature.ADVANCED_SETTINGS)
        if (label !in control.values) {
            throw PtpProtocolException("Value '$label' is not an available Canon EOS movie mode.")
        }
        val target = if (label == USB_MOVIE_MODE_ON) 1L else 0L
        val state = canonPropertyState(CanonEosPropertyCode.FIXED_MOVIE)
        if (state.currentValue == target) return false

        ensureCanonRemoteMode()
        var lastReadback: Long? = null
        val verified = canonEventMutex.withLock {
            drainCanonEventsLocked()
            requireSession().executeOperation(
                operationCode = if (target == 1L) {
                    CanonEosOperationCode.MOVIE_SELECT_SWITCH_ON
                } else {
                    CanonEosOperationCode.MOVIE_SELECT_SWITCH_OFF
                },
                parameters = emptyList(),
            )
            withTimeoutOrNull(CANON_MOVIE_MODE_VERIFY_TIMEOUT_MILLIS) {
                while (true) {
                    val payload = drainCanonEventsLocked()
                    CanonEosPtp.propertyUpdates(payload)
                        .lastOrNull {
                            it.propertyCode == CanonEosPropertyCode.FIXED_MOVIE && it.currentValue != null
                        }
                        ?.currentValue
                        ?.let { readback ->
                            lastReadback = readback
                            if (readback == target) return@withTimeoutOrNull readback
                        }
                    delay(CANON_EVENT_POLL_INTERVAL_MILLIS)
                }
            }
        }
        if (verified == null) {
            throw PtpProtocolException(
                "Canon EOS accepted the movie-mode command but did not report FixedMovie=${target} " +
                    "within ${CANON_MOVIE_MODE_VERIFY_TIMEOUT_MILLIS / 1_000} seconds " +
                    "(last=${lastReadback ?: "none"})."
            )
        }
        return true
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

    private suspend fun refreshStorageSnapshot(info: PtpDeviceInfo): Result<List<PtpStorageInfo>>? {
        if (!supportsStorage(info)) return null
        return runCatching { readStorageSnapshot() }.also { result ->
            storageSnapshot = result.getOrDefault(emptyList())
            storageError = result.exceptionOrNull()?.message
        }
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
        info: PtpDeviceInfo,
        canSetStandardProperties: Boolean,
        canSetCanonProperties: Boolean,
    ): List<CameraSettingControl> {
        val controls = linkedMapOf<String, CameraSettingControl>()
        movieModeControl(info)?.let { controls[it.key] = it }
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
            captureTargetControl(info)?.let { controls[it.key] = it }
            captureStorageControl(info)?.let { controls[it.key] = it }
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

    private fun movieModeControl(info: PtpDeviceInfo): CameraSettingControl? {
        val current = canonPropertyState(CanonEosPropertyCode.FIXED_MOVIE).currentValue
        if (!CanonEosPtp.supportsMovieModeSwitch(info, current)) return null
        return CameraSettingControl(
            key = USB_MOVIE_MODE_KEY,
            label = "Movie mode",
            value = if (current == 1L) USB_MOVIE_MODE_ON else USB_MOVIE_MODE_OFF,
            values = listOf(USB_MOVIE_MODE_OFF, USB_MOVIE_MODE_ON),
        )
    }

    private fun captureTargetControl(info: PtpDeviceInfo): CameraSettingControl? {
        if (!CanonEosPtp.supportsRemoteRelease(info) || !supportsCanonHostCaptureTarget(info)) return null
        val state = canonPropertyState(CanonEosPropertyCode.CAPTURE_DESTINATION)
        if (CanonEosPtp.CAPTURE_DESTINATION_HOST !in state.availableValues) return null
        if (CanonEosPtp.captureDestinationCardValue(state.availableValues) == null) return null
        val current = when {
            state.currentValue == CanonEosPtp.CAPTURE_DESTINATION_HOST -> USB_CAPTURE_TARGET_PHONE
            state.currentValue != null && state.currentValue in state.availableValues -> USB_CAPTURE_TARGET_CARD
            else -> "-"
        }
        return CameraSettingControl(
            key = USB_CAPTURE_TARGET_KEY,
            label = "Capture target",
            value = current,
            values = listOf(USB_CAPTURE_TARGET_PHONE, USB_CAPTURE_TARGET_CARD),
        )
    }

    private fun captureStorageControl(info: PtpDeviceInfo): CameraSettingControl? {
        advertisedStorageTargets = emptyMap()
        if (!supportsStorage(info)) return null
        val current = canonPropertyState(CanonEosPropertyCode.CURRENT_STORAGE).currentValue ?: return null
        val options = CanonEosPtp.storageTargetOptions(storageSnapshot)
        if (options.size < 2 || options.none { it.value == current }) return null
        advertisedStorageTargets = options.associate { it.label to it.value }
        return CameraSettingControl(
            key = USB_CAPTURE_STORAGE_KEY,
            label = "Recording card",
            value = options.first { it.value == current }.label,
            values = options.map(CanonEosPropertyOption::label),
        )
    }

    private suspend fun setCanonCaptureStorage(info: PtpDeviceInfo, label: String) {
        if (!CanonEosPtp.supportsPropertyControl(info) || !supportsStorage(info)) {
            unsupported<Unit>(CameraFeature.ADVANCED_SETTINGS)
        }
        val current = canonPropertyState(CanonEosPropertyCode.CURRENT_STORAGE).currentValue
            ?: throw UnsupportedOperationException("The camera did not advertise its current recording card.")
        val storages = refreshStorageSnapshot(info)?.getOrThrow()
            ?: unsupported(CameraFeature.ADVANCED_SETTINGS)
        val options = CanonEosPtp.storageTargetOptions(storages)
        if (options.size < 2 || options.none { it.value == current }) {
            throw UnsupportedOperationException("The camera does not currently expose two writable recording cards.")
        }
        val targetId = advertisedStorageTargets[label]
            ?: throw PtpProtocolException("Recording card '$label' is no longer available.")
        if (options.none { it.value == targetId }) {
            throw PtpProtocolException("Recording card '$label' is no longer available.")
        }
        ensureCanonRemoteMode()
        requireSession().executeDataOutOperation(
            operationCode = CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
            payload = CanonEosPtp.propertyPayload(CanonEosPropertyCode.CURRENT_STORAGE, targetId),
        )
        synchronized(canonProperties) {
            val state = canonProperties[CanonEosPropertyCode.CURRENT_STORAGE] ?: CanonEosPropertyState()
            canonProperties[CanonEosPropertyCode.CURRENT_STORAGE] = state.copy(currentValue = targetId)
        }
    }

    private fun supportsCanonHostCaptureTarget(info: PtpDeviceInfo): Boolean {
        val availableShots = CanonEosPtp.availableShots(
            canonPropertyState(CanonEosPropertyCode.AVAILABLE_SHOTS).currentValue,
        )
        val capacityCanBePrepared = availableShots?.let { it >= CANON_HOST_MIN_AVAILABLE_SHOTS } == true ||
            info.supports(CanonEosOperationCode.PC_HDD_CAPACITY)
        return hostCaptureStore != null &&
            info.supports(PtpOperationCode.GET_PARTIAL_OBJECT) &&
            info.supports(CanonEosOperationCode.TRANSFER_COMPLETE) &&
            capacityCanBePrepared
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

    private fun canonClockPropertyCode(info: PtpDeviceInfo): Int? {
        if (!CanonEosPtp.supportsPropertyControl(info)) return null
        return listOf(CanonEosPropertyCode.UTC_TIME, CanonEosPropertyCode.CAMERA_TIME)
            .firstOrNull { canonPropertyState(it).currentValue != null }
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
                        "rawOptions",
                        JSONArray(
                            state.availableValues.map { value ->
                                "0x${value.toString(16).uppercase().padStart(8, '0')}"
                            }
                        ),
                    )
                    .put(
                        "options",
                        JSONArray(CanonEosPtp.propertyOptions(propertyCode, state.availableValues).map { it.label }),
                    )
            )
        }
        return JSONObject()
            .put("kind", "ptp-usb")
            .put("vendorExtensionId", info.vendorExtensionId.toInt().ptpHexCode(8))
            .put(
                "canonLiveViewGeometry",
                canonLiveViewGeometry?.let { geometry ->
                    JSONObject()
                        .put("width", geometry.width)
                        .put("height", geometry.height)
                } ?: JSONObject.NULL,
            )
            .put("operations", JSONArray(info.operations.sorted().map { it.ptpHexCode() }))
            .put("advertisedDeviceProperties", JSONArray(info.deviceProperties.sorted().map { it.ptpHexCode() }))
            .put("loadedProperties", properties)
            .put("canonVendorProperties", canonVendorProperties)
            .apply { canonPropertyError?.let { put("canonPropertyError", it) } }
            .toString()
    }

    private fun requireMediaBrowser() {
        val info = requireDeviceInfo()
        if (!supportsMediaBrowser(info) && hostCaptureStore == null) unsupported<Unit>(CameraFeature.MEDIA_BROWSER)
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
    previewAvailable = sizeBytes in 1..MAX_PTP_MEDIA_PREVIEW_BYTES.toLong() &&
        (objectFormat == PtpObjectFormat.EXIF_JPEG || objectFormat == PtpObjectFormat.PNG),
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

private fun mediaPreviewContentType(format: Int, bytes: ByteArray): String? = when {
    format == PtpObjectFormat.EXIF_JPEG && bytes.hasJpegMarkers() -> "image/jpeg"
    format == PtpObjectFormat.PNG && bytes.hasCompletePngMarkers() -> "image/png"
    else -> null
}

private class BoundedByteArrayOutputStream(private val maxBytes: Int) : OutputStream() {
    private val output = java.io.ByteArrayOutputStream()

    override fun write(value: Int) {
        ensureCapacity(1)
        output.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "Invalid media preview byte range."
        }
        ensureCapacity(length)
        output.write(bytes, offset, length)
    }

    fun toByteArray(): ByteArray = output.toByteArray()

    private fun ensureCapacity(additionalBytes: Int) {
        if (output.size() > maxBytes - additionalBytes) {
            throw PtpProtocolException("USB PTP media preview exceeded $maxBytes bytes.")
        }
    }
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

private fun ByteArray.hasCompletePngMarkers(): Boolean =
    hasPngSignature() && size >= 20 && copyOfRange(size - 12, size).contentEquals(
        byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()),
    )

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

private fun List<PtpStorageInfo>.sumUnsignedBytesOrNull(selector: (PtpStorageInfo) -> ULong): Long? {
    val values = map(selector).filter { it != ULong.MAX_VALUE }
    if (values.isEmpty()) return null
    var total = 0UL
    for (value in values) {
        total = if (ULong.MAX_VALUE - total < value) ULong.MAX_VALUE else total + value
    }
    return total.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
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
        extension in setOf("cr2", "cr3", "dng", "raw") || objectFormat in setOf(
            PtpObjectFormat.DNG,
            PtpObjectFormat.CANON_CRW,
            PtpObjectFormat.CANON_CRW3,
            PtpObjectFormat.CANON_CR3,
        ) -> "raw"
        extension in setOf("mp4", "mov", "avi", "mkv") || objectFormat == PtpObjectFormat.MP4 -> "video"
        else -> "image"
    }
}

private fun synthesizedCaptureFilename(objectFormat: Int): String {
    val extension = when (objectFormat) {
        PtpObjectFormat.CANON_CRW, PtpObjectFormat.CANON_CRW3 -> "cr2"
        PtpObjectFormat.CANON_CR3 -> "cr3"
        PtpObjectFormat.DNG -> "dng"
        PtpObjectFormat.PNG -> "png"
        else -> "jpg"
    }
    return "capture-${System.currentTimeMillis()}.$extension"
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
private const val MAX_PTP_MEDIA_PREVIEW_BYTES = 32 * 1024 * 1024
private const val PROPERTY_REFRESH_INTERVAL_MILLIS = 500L
private const val CANON_EVENT_POLL_INTERVAL_MILLIS = 100L
private const val CANON_CLOCK_SYNC_VERIFY_TIMEOUT_MILLIS = 3_000L
private const val CANON_MOVIE_MODE_VERIFY_TIMEOUT_MILLIS = 3_000L
private const val CANON_CLOCK_SYNC_TOLERANCE_SECONDS = 10L
private const val CANON_EVENT_LONG_POLL_ATTEMPTS = 10
private const val CANON_AUTOFOCUS_HOLD_MILLIS = 350L
private const val CANON_TOUCH_AF_MODE = 3L
private const val CANON_PROPERTY_DISCOVERY_ATTEMPTS = 10
private const val CANON_PROPERTY_DISCOVERY_RETRY_MILLIS = 50L
private const val CANON_CAPTURE_EVENT_TIMEOUT_MILLIS = 90_000L
private const val CANON_HOST_TRANSFER_QUIET_MILLIS = 1_000L
private const val CANON_HOST_TRANSFER_CHUNK_BYTES = 1 * 1024 * 1024
private const val CANON_HOST_MIN_AVAILABLE_SHOTS = 100L
private const val USB_CAPTURE_TARGET_KEY = "capturetarget"
private const val USB_CAPTURE_STORAGE_KEY = "capturestorage"
private const val USB_MOVIE_MODE_KEY = "moviemode"
private const val USB_MOVIE_MODE_OFF = "off"
private const val USB_MOVIE_MODE_ON = "on"
private const val USB_CAPTURE_TARGET_PHONE = "Phone"
private const val USB_CAPTURE_TARGET_CARD = "Memory card"
private const val CANON_HOST_CAPACITY_CLUSTERS = 0x0FFF_FFFFL
private const val CANON_HOST_CAPACITY_CLUSTER_BYTES = 0x0000_1000L
private const val CANON_LIVE_VIEW_READY_TIMEOUT_MILLIS = 3_000L
