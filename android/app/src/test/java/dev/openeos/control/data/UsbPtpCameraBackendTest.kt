package dev.openeos.control.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class UsbPtpCameraBackendTest {
    @Test
    fun backendExposesOnlyAdvertisedStandardOperationsAndRunsMediaPath() = runTest {
        val transport = ScriptedTransport(advertiseCapture = true)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3", CANON_USB_VENDOR_ID, 0x1234),
            transportFactory = PtpTransportFactory { transport },
        )

        backend.initialize()
        val info = backend.info()
        val status = backend.status()
        val capabilities = backend.capabilities()
        val exposureStatus = backend.setExposure(iso = "800", shutter = "1/50", aperture = "2.8")
        val whiteBalanceStatus = backend.setWhiteBalance("daylight")
        val media = backend.listMedia()
        val thumbnail = backend.mediaThumbnail(media.single())
        val preview = backend.mediaPreview(media.single())
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<CameraMediaTransferProgress>()
        val download = backend.downloadMedia(media.single(), output, progress::add)
        backend.deleteMedia(media.single())
        backend.captureStill()
        val observedFeatures = backend.observedFeatures()
        backend.close()

        assertEquals("Canon EOS R6 Mark III", info.model)
        assertEquals("ptp-usb/1.00", info.api)
        assertEquals(true, status.mediaAvailable)
        assertEquals(64L * 1024L * 1024L * 1024L, status.storageTotalBytes)
        assertEquals(32L * 1024L * 1024L * 1024L, status.storageFreeBytes)
        assertEquals(1_234L, status.storageFreeImages)
        assertEquals(1, status.storageDeviceCount)
        assertEquals(82, status.batteryLevel)
        assertEquals("400", status.exposure.iso)
        assertTrue(status.rawStorageJson.contains("EOS_CARD"))
        assertTrue(status.rawTransportJson.contains("\"vendorExtensionId\":\"0x0000000B\""))
        assertTrue(status.rawTransportJson.contains("\"code\":\"0x500F\""))
        assertTrue(status.rawTransportJson.contains("\"writable\":true"))
        assertTrue(capabilities.matrix.supports(CameraFeature.USB_DIAGNOSTICS))
        assertTrue(capabilities.matrix.supports(CameraFeature.STORAGE_STATUS))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_BROWSER))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_THUMBNAIL))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_PREVIEW))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_DOWNLOAD))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_DELETE))
        assertTrue(capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(capabilities.matrix.supports(CameraFeature.BATTERY_STATUS))
        assertTrue(capabilities.matrix.supports(CameraFeature.EXPOSURE_CONTROL))
        assertTrue(capabilities.matrix.supports(CameraFeature.WHITE_BALANCE_CONTROL))
        assertFalse(capabilities.matrix.supports(CameraFeature.LIVE_VIEW))
        assertEquals("PTP GetDeviceInfo", capabilities.evidence.source)
        assertTrue("0x100E" in capabilities.evidence.advertisedCommands)
        assertTrue("iso" in capabilities.evidence.writableSettings)
        assertEquals(listOf("100", "400", "800"), capabilities.iso)
        assertEquals(listOf("1/50", "1/25"), capabilities.shutter)
        assertEquals(listOf("2.8", "4"), capabilities.aperture)
        assertEquals("800", exposureStatus.exposure.iso)
        assertEquals("daylight", whiteBalanceStatus.exposure.whiteBalance)
        assertTrue(
            observedFeatures.containsAll(
                setOf(
                    CameraFeature.USB_DIAGNOSTICS,
                    CameraFeature.CAMERA_IDENTITY,
                    CameraFeature.BATTERY_STATUS,
                    CameraFeature.STORAGE_STATUS,
                    CameraFeature.EXPOSURE_CONTROL,
                    CameraFeature.WHITE_BALANCE_CONTROL,
                    CameraFeature.MEDIA_BROWSER,
                    CameraFeature.MEDIA_THUMBNAIL,
                    CameraFeature.MEDIA_PREVIEW,
                    CameraFeature.MEDIA_DOWNLOAD,
                    CameraFeature.MEDIA_DELETE,
                    CameraFeature.STILL_CAPTURE,
                ),
            ),
        )
        assertEquals("IMG_0042.JPG", media.single().name)
        assertEquals("2026-07-21 14:30:25", media.single().captureTime)
        assertArrayEquals(THUMBNAIL_BYTES, thumbnail.bytes)
        assertEquals("image/jpeg", thumbnail.contentType)
        assertTrue(media.single().previewAvailable)
        assertArrayEquals(OBJECT_BYTES, preview.bytes)
        assertEquals("image/jpeg", preview.contentType)
        assertArrayEquals(OBJECT_BYTES, output.toByteArray())
        assertEquals(OBJECT_BYTES.size.toLong(), download.bytesTransferred)
        assertEquals(OBJECT_BYTES.size.toLong(), progress.last().bytesTransferred)
        assertArrayEquals(
            byteArrayOf(0x42, 0, 0, 0, 0, 0, 0, 0),
            transport.sentContainers.single {
                it.type == PtpContainerType.COMMAND && it.code == PtpOperationCode.DELETE_OBJECT
            }.payload,
        )
        assertTrue(PtpOperationCode.INITIATE_CAPTURE in transport.sentOperations)
        assertArrayEquals(
            ByteArray(8),
            transport.sentContainers.single { it.code == PtpOperationCode.INITIATE_CAPTURE }.payload,
        )
        assertTrue(
            transport.sentContainers.any {
                it.type == PtpContainerType.DATA &&
                    it.code == PtpOperationCode.SET_DEVICE_PROP_VALUE &&
                    it.payload.contentEquals(byteArrayOf(0x20, 0x03))
            }
        )
        assertTrue(transport.closed)
    }

    @Test
    fun captureRemainsPlannedWhenDeviceInfoDoesNotAdvertiseInitiateCapture() = runTest {
        val transport = ScriptedTransport(advertiseCapture = false)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )

        backend.initialize()
        val capabilities = backend.capabilities()

        assertFalse(capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.STILL_CAPTURE))
        backend.close()
    }

    @Test
    fun mediaDeletionRemainsUnavailableWithoutAdvertisedDeleteObject() = runTest {
        val transport = ScriptedTransport(advertiseCapture = false, advertiseDelete = false)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val capabilities = backend.capabilities()
        val failure = runCatching {
            backend.deleteMedia(CameraMediaItem("ptp:00000042", "IMG_0042.JPG", "image"))
        }.exceptionOrNull()

        assertFalse(capabilities.matrix.supports(CameraFeature.MEDIA_DELETE))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.MEDIA_DELETE))
        assertTrue(failure is UnsupportedOperationException)
        assertFalse(PtpOperationCode.DELETE_OBJECT in transport.sentOperations)
        backend.close()
    }

    @Test
    fun mediaThumbnailRemainsUnavailableWithoutAdvertisedGetThumb() = runTest {
        val transport = ScriptedTransport(advertiseCapture = false, advertiseThumbnail = false)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val capabilities = backend.capabilities()
        val failure = runCatching {
            backend.mediaThumbnail(CameraMediaItem("ptp:00000042", "IMG_0042.JPG", "image"))
        }.exceptionOrNull()

        assertFalse(capabilities.matrix.supports(CameraFeature.MEDIA_THUMBNAIL))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.MEDIA_THUMBNAIL))
        assertTrue(failure is UnsupportedOperationException)
        assertFalse(PtpOperationCode.GET_THUMB in transport.sentOperations)
        backend.close()
    }

    @Test
    fun mediaThumbnailRejectsOversizedMetadataBeforeGetThumb() = runTest {
        val transport = ScriptedTransport(
            advertiseCapture = false,
            advertisedThumbnailSize = MAX_PTP_THUMBNAIL_BYTES.toLong() + 1L,
        )
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()
        val item = backend.listMedia().single()

        val failure = runCatching { backend.mediaThumbnail(item) }.exceptionOrNull()

        assertTrue(failure is PtpProtocolException)
        assertTrue(failure?.message.orEmpty().contains("limit is $MAX_PTP_THUMBNAIL_BYTES bytes"))
        assertFalse(PtpOperationCode.GET_THUMB in transport.sentOperations)
        backend.close()
    }

    @Test
    fun oneRejectedPropertyDescriptorDoesNotBlockTheUsbSession() = runTest {
        val transport = ScriptedTransport(
            advertiseCapture = false,
            descriptorFailureCode = PtpDevicePropertyCode.WHITE_BALANCE,
        )
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )

        backend.initialize()
        val status = backend.status()
        val capabilities = backend.capabilities()

        assertEquals(82, status.batteryLevel)
        assertTrue(capabilities.matrix.supports(CameraFeature.BATTERY_STATUS))
        assertFalse(capabilities.matrix.supports(CameraFeature.WHITE_BALANCE_CONTROL))
        assertTrue(status.rawTransportJson.contains("\"code\":\"0x5005\""))
        assertTrue(status.rawTransportJson.contains("DevicePropNotSupported"))
        backend.close()
    }

    @Test
    fun canonEventPollingIsSupportedOnlyWhenGetEventIsAdvertised() = runTest {
        val supportedBackend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { CanonEosScriptedTransport() },
        )
        supportedBackend.initialize()

        val unavailableBackend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3-no-events"),
            transportFactory = PtpTransportFactory {
                CanonEosScriptedTransport(advertiseEventPolling = false)
            },
        )
        unavailableBackend.initialize()

        assertTrue(supportedBackend.capabilities().matrix.supports(CameraFeature.EVENT_POLLING))
        assertFalse(unavailableBackend.capabilities().matrix.supports(CameraFeature.EVENT_POLLING))
        assertTrue(unavailableBackend.capabilities().matrix.isPlanned(CameraFeature.EVENT_POLLING))
        val failure = runCatching { unavailableBackend.pollEvent() }.exceptionOrNull()
        assertTrue(failure is UnsupportedOperationException)
        supportedBackend.close()
        unavailableBackend.close()
    }

    @Test
    fun canonEventPollingAppliesPropertyUpdatesAndReturnsRefreshHints() = runTest {
        val transport = CanonEosScriptedTransport(
            scriptedEvents = listOf(
                eosPropertyValue(CanonEosPropertyCode.ISO_SPEED, 0x60) +
                    eosPropertyValue(
                        CanonEosPropertyCode.EVF_RECORD_STATUS,
                        CanonEosPtp.MOVIE_RECORD_TARGET_CARD.toInt(),
                    ) +
                    eosPropertyValue(CanonEosPropertyCode.AVAILABLE_SHOTS, 321) +
                    eosBlock(0, byteArrayOf()),
            ),
        )
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val event = backend.pollEvent()
        val status = backend.status()

        assertEquals(setOf("shootingsettings", "recording", "storage"), event.changedKeys)
        assertEquals("800", status.exposure.iso)
        assertTrue(status.recording == true)
        assertEquals(321L, status.storageFreeImages)
        assertTrue(CameraFeature.EVENT_POLLING in backend.observedFeatures())
        backend.close()
    }

    @Test
    fun canonEventPollingReturnsEmptyAfterBoundedImmediatePolls() = runTest {
        val transport = CanonEosScriptedTransport()
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val event = backend.pollEvent()

        assertTrue(event.changedKeys.isEmpty())
        assertEquals(
            11,
            transport.sentContainers.count {
                it.type == PtpContainerType.COMMAND && it.code == CanonEosOperationCode.GET_EVENT
            },
        )
        backend.close()
    }

    @Test
    fun canonEventPollingCompletesExternalHostCaptureTransfer() = runTest {
        val captured = HostCaptureObject(
            handle = 0x81,
            objectFormat = PtpObjectFormat.EXIF_JPEG,
            filename = "IMG_0081.JPG",
            bytes = byteArrayOf(8, 1, 8, 1),
        )
        val transport = CanonEosScriptedTransport(
            captureDestination = CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
            advertiseCardCaptureDestination = false,
            hostCaptureObjects = listOf(captured),
            scriptedEvents = listOf(eosHostTransfer(captured) + eosBlock(0, byteArrayOf())),
        )
        val store = MemoryHostCaptureStore()
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
            hostCaptureStore = store,
        )
        backend.initialize()

        val event = backend.pollEvent()
        val media = store.list().single()

        assertEquals(setOf("contents"), event.changedKeys)
        assertEquals("IMG_0081.JPG", media.name)
        assertArrayEquals(captured.bytes, store.bytes(media))
        assertTrue(
            transport.sentContainers.any {
                it.type == PtpContainerType.COMMAND &&
                    it.code == CanonEosOperationCode.TRANSFER_COMPLETE &&
                    it.parameters() == listOf(captured.handle)
            },
        )
        backend.close()
    }

    @Test
    fun canonAppCaptureOwnsObjectEventsWhileBackgroundPollingIsActive() = runTest {
        val transport = CanonEosScriptedTransport(delayAfterFullReleaseMillis = 100L)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()
        val polling = launch(start = CoroutineStart.UNDISPATCHED) { backend.pollEvent() }

        backend.captureStill()
        polling.cancelAndJoin()

        assertTrue(CameraFeature.STILL_CAPTURE in backend.observedFeatures())
        assertTrue(transport.hasOperation(CanonEosOperationCode.REMOTE_RELEASE_ON))
        backend.close()
    }

    @Test
    fun canonCaptureAppliesPendingHostDestinationEventBeforePreparation() = runTest {
        val captured = HostCaptureObject(
            handle = 0x82,
            objectFormat = PtpObjectFormat.EXIF_JPEG,
            filename = "IMG_0082.JPG",
            bytes = byteArrayOf(8, 2),
        )
        val transport = CanonEosScriptedTransport(
            captureDestination = 2,
            hostCaptureObjects = listOf(captured),
            scriptedEvents = listOf(
                eosPropertyValue(
                    CanonEosPropertyCode.CAPTURE_DESTINATION,
                    CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
                ) + eosBlock(0, byteArrayOf()),
            ),
        )
        val store = MemoryHostCaptureStore()
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
            hostCaptureStore = store,
        )
        backend.initialize()

        backend.captureStill()

        assertEquals("IMG_0082.JPG", store.list().single().name)
        assertTrue(transport.hasOperation(CanonEosOperationCode.TRANSFER_COMPLETE))
        backend.close()
    }

    @Test
    fun canonBulbStopOwnsObjectEventWhileBackgroundPollingIsActive() = runTest {
        val transport = CanonEosScriptedTransport(delayAfterFullReleaseMillis = 100L)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()
        backend.startBulbExposure()
        val polling = launch(start = CoroutineStart.UNDISPATCHED) { backend.pollEvent() }

        val status = backend.stopBulbExposure()
        polling.cancelAndJoin()

        assertFalse(status.bulbExposureActive == true)
        assertTrue(CameraFeature.BULB_EXPOSURE in backend.observedFeatures())
        backend.close()
    }

    @Test
    fun canonEosVendorOperationsProvideCaptureFocusAndJpegLiveViewWhenAdvertised() = runTest {
        val transport = CanonEosScriptedTransport()
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )

        backend.initialize()
        val capabilities = backend.capabilities()
        val initialStatus = backend.status()
        backend.setSetting("shootingmode", "AV")
        backend.setSetting("afoperation", "One Shot")
        backend.setSetting("continuousaf", "On")
        backend.setSetting("afmethod", "LiveSpotAF")
        backend.setSetting("drivemode", "Continuous high speed")
        backend.setSetting("meteringmode", "Spot")
        backend.setSetting("picturestyle", "Fine detail")
        backend.setSetting("stillimagequality", "cRAW + Large Fine JPEG")
        backend.setSetting("stillimagequalitysd", "Large Normal JPEG")
        backend.setSetting("stillimagequalitycf", "RAW")
        backend.setSetting("movieservoaf", "Off")
        backend.setSetting("exposurecompensation", "1.3")
        backend.setSetting("colortemperature", "5600")
        backend.setSetting("whitebalanceadjusta", "-9")
        backend.setSetting("whitebalanceadjustb", "9")
        backend.setSetting("colorspace", "AdobeRGB")
        backend.setSetting("aspectratio", "16:9")
        backend.setSetting("zoomspeed", "12")
        backend.setSetting("autopoweroff", "Disable")
        backend.setSetting("highisonr", "High")
        backend.setSetting("aeb", "+/- 2")
        val recordingStatus = backend.startRecording()
        val stoppedRecordingStatus = backend.stopRecording()
        val exposureStatus = backend.setExposure(iso = "800", shutter = "1/50", aperture = "4")
        val whiteBalanceStatus = backend.setWhiteBalance("Daylight")
        backend.startLiveView(LiveViewRequest(fps = 30, size = LiveViewSize.MEDIUM))
        val frame = backend.liveViewFrame(cacheKey = 27)
        val magnification = backend.setLiveViewMagnification(LiveViewMagnification.X5)
        backend.autofocus()
        backend.halfPressShutter()
        val focusDrive = backend.driveFocus(FocusDriveDirection.FAR, FocusDriveStep.MEDIUM)
        backend.captureStill()
        backend.stopLiveView()
        backend.close()

        assertTrue(capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(capabilities.matrix.supports(CameraFeature.EVENT_POLLING))
        assertTrue(capabilities.matrix.supports(CameraFeature.SHUTTER_HALF_PRESS))
        assertTrue(capabilities.matrix.supports(CameraFeature.AUTOFOCUS))
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW))
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_JPEG_POLLING))
        assertTrue(capabilities.matrix.supports(CameraFeature.FOCUS_DRIVE))
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_MAGNIFICATION))
        assertTrue(capabilities.matrix.supports(CameraFeature.EXPOSURE_CONTROL))
        assertTrue(capabilities.matrix.supports(CameraFeature.WHITE_BALANCE_CONTROL))
        assertFalse(capabilities.matrix.supports(CameraFeature.TAP_FOCUS))
        assertTrue(capabilities.matrix.supports(CameraFeature.VIDEO_RECORDING))
        assertTrue(capabilities.matrix.supports(CameraFeature.ADVANCED_SETTINGS))
        assertTrue("movierecordtarget" in capabilities.evidence.writableSettings)
        assertTrue(CanonEosPtp.settingSpecs.all { it.key in capabilities.evidence.writableSettings })
        assertEquals("Manual", initialStatus.mode)
        assertEquals(46_822L, initialStatus.storageFreeImages)
        assertTrue(initialStatus.rawTransportJson.contains("\"code\":\"0xD11B\""))
        assertEquals(listOf("100", "400", "800"), capabilities.iso)
        assertEquals(listOf("1/30", "1/50"), capabilities.shutter)
        assertEquals(listOf("2.8", "4"), capabilities.aperture)
        assertEquals(listOf("Auto", "Daylight", "Shadow"), capabilities.whiteBalance)
        val settings = capabilities.advancedSettings.associateBy(CameraSettingControl::key)
        assertEquals("Manual", settings.getValue("shootingmode").value)
        assertTrue("Movie" in settings.getValue("shootingmode").values)
        assertEquals("AI Servo", settings.getValue("afoperation").value)
        assertEquals("0", settings.getValue("exposurecompensation").value)
        assertEquals("5200", settings.getValue("colortemperature").value)
        assertEquals("0", settings.getValue("whitebalanceadjusta").value)
        assertEquals("-2", settings.getValue("whitebalanceadjustb").value)
        assertEquals(listOf("sRGB", "AdobeRGB"), settings.getValue("colorspace").values)
        assertEquals("1.6x", settings.getValue("aspectratio").value)
        assertEquals(listOf("3:2", "1:1", "4:3", "16:9", "1.6x"), settings.getValue("aspectratio").values)
        assertEquals("8", settings.getValue("zoomspeed").value)
        assertEquals("30 seconds", settings.getValue("autopoweroff").value)
        assertEquals(
            listOf(
                "15 seconds",
                "30 seconds",
                "1 minute",
                "3 minutes",
                "5 minutes",
                "10 minutes",
                "30 minutes",
                "Disable",
            ),
            settings.getValue("autopoweroff").values,
        )
        assertEquals(listOf("Off", "On"), settings.getValue("continuousaf").values)
        assertEquals("WholeAreaAF", settings.getValue("afmethod").value)
        assertEquals("Super high speed continuous shooting", settings.getValue("drivemode").value)
        assertEquals("Evaluative", settings.getValue("meteringmode").value)
        assertEquals(listOf("Off", "Low", "Normal", "High"), settings.getValue("highisonr").values)
        assertEquals("off", settings.getValue("aeb").value)
        assertEquals("Auto", settings.getValue("picturestyle").value)
        assertEquals("RAW", settings.getValue("stillimagequality").value)
        assertEquals("RAW", settings.getValue("stillimagequalitysd").value)
        assertEquals("RAW", settings.getValue("stillimagequalitycf").value)
        assertEquals(
            listOf(
                "Large Fine JPEG",
                "Large Normal JPEG",
                "Smaller JPEG",
                "cRAW + Large Fine JPEG",
                "cRAW + Large Normal JPEG",
                "RAW + Large Fine JPEG",
                "RAW + Large Normal JPEG",
                "cRAW + Smaller JPEG",
                "RAW + Smaller JPEG",
                "RAW",
                "cRAW",
            ),
            settings.getValue("stillimagequality").values,
        )
        assertEquals("On", settings.getValue("movieservoaf").value)
        assertEquals("800", exposureStatus.exposure.iso)
        assertEquals("1/50", exposureStatus.exposure.shutter)
        assertEquals("4", exposureStatus.exposure.aperture)
        assertEquals("Daylight", whiteBalanceStatus.exposure.whiteBalance)
        assertEquals(true, recordingStatus.recording)
        assertEquals(false, stoppedRecordingStatus.recording)
        assertTrue(whiteBalanceStatus.rawTransportJson.contains("\"canonVendorProperties\""))
        assertTrue(whiteBalanceStatus.rawTransportJson.contains("\"setting\":\"drivemode\""))
        assertTrue(whiteBalanceStatus.rawTransportJson.contains("\"valueBytes\":2"))
        assertTrue(whiteBalanceStatus.rawTransportJson.contains("\"rawOptions\""))
        assertTrue(whiteBalanceStatus.rawTransportJson.contains("0xFFFFFFFF"))
        assertEquals(listOf(LiveViewSource.USB_PTP_PREVIEW), capabilities.liveView.sources)
        assertEquals(30, capabilities.liveView.maxFps)
        assertArrayEquals(CANON_LIVE_VIEW_JPEG, frame.bytes)
        assertEquals("image/jpeg", frame.contentType)
        assertEquals("ptp-usb://canon-eos/viewfinder?frame=27", frame.sourceUrl)
        assertEquals(FocusDriveDirection.FAR, focusDrive.direction)
        assertEquals(FocusDriveStep.MEDIUM, focusDrive.step)
        assertTrue(focusDrive.ok)
        assertEquals(LiveViewMagnification.X5, magnification.magnification)

        val autofocusStart = transport.sentContainers.indexOfFirst { container ->
            container.type == PtpContainerType.COMMAND && container.code == CanonEosOperationCode.DO_AF
        }
        val autofocusCancel = transport.sentContainers.indexOfFirst { container ->
            container.type == PtpContainerType.COMMAND && container.code == CanonEosOperationCode.AF_CANCEL
        }
        assertTrue(autofocusStart >= 0)
        assertTrue(autofocusCancel > autofocusStart)

        val viewfinderCommand = transport.sentContainers.single {
            it.type == PtpContainerType.COMMAND && it.code == CanonEosOperationCode.GET_VIEWFINDER_DATA
        }
        assertEquals(listOf(0x00200000L, 0L, 0L), viewfinderCommand.parameters())
        assertTrue(
            transport.sentContainers.any {
                it.type == PtpContainerType.COMMAND &&
                    it.code == CanonEosOperationCode.DRIVE_LENS &&
                    it.parameters() == listOf(0x8002L)
            }
        )
        assertTrue(
            transport.sentContainers.any {
                it.type == PtpContainerType.COMMAND &&
                    it.code == CanonEosOperationCode.ZOOM &&
                    it.parameters() == listOf(5L)
            }
        )
        assertTrue(
            transport.sentContainers.any {
                it.type == PtpContainerType.COMMAND &&
                    it.code == CanonEosOperationCode.REMOTE_RELEASE_ON &&
                    it.parameters() == listOf(2L, 0L)
            }
        )
        assertTrue(
            transport.sentContainers.any {
                it.type == PtpContainerType.COMMAND &&
                    it.code == CanonEosOperationCode.REMOTE_RELEASE_OFF &&
                    it.parameters() == listOf(2L)
            }
        )
        val propertyWrites = transport.sentContainers.filter {
            it.type == PtpContainerType.DATA && it.code == CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX
        }.map(PtpContainer::payload)
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.EVF_MODE, 1))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.EVF_OUTPUT_DEVICE, 2))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.EVF_OUTPUT_DEVICE, 0))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.ISO_SPEED, 0x60))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.SHUTTER_SPEED, 0x65))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.APERTURE, 0x28))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint8PropertyPayload(CanonEosPropertyCode.WHITE_BALANCE, 1))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.FOCUS_MODE, 0))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.CONTINUOUS_AF_MODE, 1))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.LIVE_VIEW_AF_SYSTEM, 10))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.DRIVE_MODE, 4))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint8PropertyPayload(CanonEosPropertyCode.METERING_MODE, 1))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint8PropertyPayload(CanonEosPropertyCode.PICTURE_STYLE, 0x88))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.MOVIE_SERVO_AF, 0))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint8PropertyPayload(CanonEosPropertyCode.EXPOSURE_COMPENSATION, 0x0B))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.COLOR_TEMPERATURE, 5600))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.int32PropertyPayload(CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A, -9))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.int32PropertyPayload(CanonEosPropertyCode.WHITE_BALANCE_ADJUST_B, 9))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.COLOR_SPACE, 2))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.AUTO_EXPOSURE_MODE, 0x0002))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.MULTI_ASPECT, 7))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.POWER_ZOOM_SPEED, 12))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.AUTO_POWER_OFF, 0))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.HIGH_ISO_NOISE_REDUCTION, 3))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.AEB, 0x10))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.propertyPayload(CanonEosPropertyCode.IMAGE_FORMAT, 0x0B03))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.propertyPayload(CanonEosPropertyCode.IMAGE_FORMAT_SD, 0x02FF))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(CanonEosPtp.propertyPayload(CanonEosPropertyCode.IMAGE_FORMAT_CF, 0x0CFF))
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(
                    CanonEosPtp.uint16PropertyPayload(
                        CanonEosPropertyCode.EVF_RECORD_STATUS,
                        CanonEosPtp.MOVIE_RECORD_TARGET_CARD.toInt(),
                    )
                )
            }
        )
        assertTrue(
            propertyWrites.any {
                it.contentEquals(
                    CanonEosPtp.uint16PropertyPayload(
                        CanonEosPropertyCode.EVF_RECORD_STATUS,
                        CanonEosPtp.MOVIE_RECORD_TARGET_NONE.toInt(),
                    )
                )
            }
        )
        assertTrue(transport.closed)
    }

    @Test
    fun canonAvailableShotsUnknownSentinelFallsBackToStandardStorageCount() = runTest {
        val transport = CanonEosScriptedTransport(availableShots = -1)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val status = backend.status()

        assertEquals(1_234L, status.storageFreeImages)
        backend.close()
    }

    @Test
    fun canonLiveViewMagnificationRequiresAdvertisedOperationAndActiveLiveView() = runTest {
        val unavailableBackend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory {
                CanonEosScriptedTransport(advertiseLiveViewMagnification = false)
            },
        )
        unavailableBackend.initialize()

        assertFalse(unavailableBackend.capabilities().matrix.supports(CameraFeature.LIVE_VIEW_MAGNIFICATION))
        assertTrue(
            runCatching {
                unavailableBackend.setLiveViewMagnification(LiveViewMagnification.X5)
            }.exceptionOrNull() is UnsupportedOperationException
        )
        unavailableBackend.close()

        val availableBackend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { CanonEosScriptedTransport() },
        )
        availableBackend.initialize()

        assertTrue(
            runCatching {
                availableBackend.setLiveViewMagnification(LiveViewMagnification.X5)
            }.exceptionOrNull() is PtpProtocolException
        )
        availableBackend.close()
    }

    @Test
    fun canonMovieRecordingRemainsPlannedWithoutAdvertisedCardAndNoneTargets() = runTest {
        val transport = CanonEosScriptedTransport(advertiseMovieRecording = false)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val capabilities = backend.capabilities()
        val failure = runCatching { backend.startRecording() }.exceptionOrNull()

        assertFalse(capabilities.matrix.supports(CameraFeature.VIDEO_RECORDING))
        assertTrue(capabilities.matrix.isPlanned(CameraFeature.VIDEO_RECORDING))
        assertTrue(failure is UnsupportedOperationException)
        assertFalse(
            transport.sentContainers.any {
                it.type == PtpContainerType.DATA &&
                    it.code == CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX &&
                    it.payload.size >= 8 &&
                    it.payload[4] == 0xB8.toByte() &&
                    it.payload[5] == 0xD1.toByte()
            }
        )
        backend.close()
    }

    @Test
    fun canonMovieRecordingDoesNotReportSuccessWhenCameraRejectsThePropertyWrite() = runTest {
        val transport = CanonEosScriptedTransport(rejectMovieRecording = true)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()
        assertTrue(backend.capabilities().matrix.supports(CameraFeature.VIDEO_RECORDING))

        val failure = runCatching { backend.startRecording() }.exceptionOrNull()
        val status = backend.status()

        assertTrue(failure is PtpResponseException)
        assertEquals(PtpResponseCode.GENERAL_ERROR, (failure as PtpResponseException).responseCode)
        assertEquals(false, status.recording)
        backend.close()
    }

    @Test
    fun canonAdvancedSettingsRequireAdvertisedValuesAndPreserveStateAfterRejectedWrite() = runTest {
        val unavailableTransport = CanonEosScriptedTransport(advertiseAdvancedSettings = false)
        val unavailableBackend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { unavailableTransport },
        )
        unavailableBackend.initialize()

        val unavailableCapabilities = unavailableBackend.capabilities()
        val unavailableFailure = runCatching {
            unavailableBackend.setSetting("drivemode", "Continuous high speed")
        }.exceptionOrNull()

        assertFalse(unavailableCapabilities.matrix.supports(CameraFeature.ADVANCED_SETTINGS))
        assertTrue(unavailableCapabilities.advancedSettings.isEmpty())
        assertTrue(unavailableFailure is UnsupportedOperationException)
        assertFalse(unavailableTransport.hasCanonPropertyWrite(CanonEosPropertyCode.DRIVE_MODE))
        unavailableBackend.close()

        val rejectedTransport = CanonEosScriptedTransport(
            rejectPropertyCode = CanonEosPropertyCode.DRIVE_MODE,
        )
        val rejectedBackend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { rejectedTransport },
        )
        rejectedBackend.initialize()
        assertEquals(
            "Super high speed continuous shooting",
            rejectedBackend.capabilities().advancedSettings.first { it.key == "drivemode" }.value,
        )

        val rejectedFailure = runCatching {
            rejectedBackend.setSetting("drivemode", "Continuous high speed")
        }.exceptionOrNull()

        assertTrue(rejectedFailure is PtpResponseException)
        assertEquals(
            "Super high speed continuous shooting",
            rejectedBackend.capabilities().advancedSettings.first { it.key == "drivemode" }.value,
        )
        rejectedBackend.close()

        val rejectedImageTransport = CanonEosScriptedTransport(
            rejectPropertyCode = CanonEosPropertyCode.IMAGE_FORMAT,
        )
        val rejectedImageBackend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { rejectedImageTransport },
        )
        rejectedImageBackend.initialize()
        assertEquals(
            "RAW",
            rejectedImageBackend.capabilities().advancedSettings.first { it.key == "stillimagequality" }.value,
        )

        val rejectedImageFailure = runCatching {
            rejectedImageBackend.setSetting("stillimagequality", "cRAW + Large Fine JPEG")
        }.exceptionOrNull()

        assertTrue(rejectedImageFailure is PtpResponseException)
        assertEquals(
            "RAW",
            rejectedImageBackend.capabilities().advancedSettings.first { it.key == "stillimagequality" }.value,
        )
        rejectedImageBackend.close()
    }

    @Test
    fun canonCaptureDoesNotReportSuccessWhenTheConfirmationEventIsInvalid() = runTest {
        val transport = CanonEosScriptedTransport(malformedCaptureEvent = true)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val failure = runCatching { backend.captureStill() }.exceptionOrNull()

        assertTrue(failure is PtpProtocolException)
        assertTrue(failure?.message.orEmpty().contains("event block"))
        backend.close()
    }

    @Test
    fun canonHostCaptureDownloadsInBoundedChunksBeforeTransferComplete() = runTest {
        val objectBytes = ByteArray(1024 * 1024 + 17) { index -> (index % 251).toByte() }
        val captured = HostCaptureObject(
            handle = 0x42,
            objectFormat = PtpObjectFormat.EXIF_JPEG,
            filename = "IMG_0042.JPG",
            bytes = objectBytes,
        )
        val transport = CanonEosScriptedTransport(
            captureDestination = CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
            advertiseCardCaptureDestination = false,
            hostCaptureObjects = listOf(captured),
        )
        val store = MemoryHostCaptureStore()
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
            hostCaptureStore = store,
        )
        backend.initialize()

        backend.captureStill()
        val media = backend.listMedia().single()
        val output = ByteArrayOutputStream()
        backend.downloadMedia(media, output) {}

        assertEquals("IMG_0042.JPG", media.name)
        assertEquals("image", media.kind)
        assertArrayEquals(objectBytes, store.bytes(media))
        assertArrayEquals(objectBytes, output.toByteArray())
        val partialCommands = transport.sentContainers.filter {
            it.type == PtpContainerType.COMMAND && it.code == PtpOperationCode.GET_PARTIAL_OBJECT
        }
        assertEquals(2, partialCommands.size)
        assertEquals(listOf(0L, 1024L * 1024L), partialCommands.map { it.parameters()[1] })
        val transferComplete = transport.sentContainers.indexOfFirst {
            it.type == PtpContainerType.COMMAND && it.code == CanonEosOperationCode.TRANSFER_COMPLETE
        }
        val lastPartial = transport.sentContainers.indexOfLast {
            it.type == PtpContainerType.COMMAND && it.code == PtpOperationCode.GET_PARTIAL_OBJECT
        }
        assertTrue(transferComplete > lastPartial)
        assertFalse(transport.hasCanonPropertyWrite(CanonEosPropertyCode.CAPTURE_DESTINATION))

        backend.deleteMedia(media)
        assertTrue(backend.listMedia().isEmpty())
        backend.close()
    }

    @Test
    fun canonUsbCaptureTargetSelectsPhoneOrCameraCardOnlyWhenBothPathsAreSafe() = runTest {
        val captured = HostCaptureObject(
            handle = 0x49,
            objectFormat = PtpObjectFormat.EXIF_JPEG,
            filename = "IMG_0049.JPG",
            bytes = byteArrayOf(4, 9),
        )
        val transport = CanonEosScriptedTransport(
            captureDestination = CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
            hostCaptureObjects = listOf(captured),
        )
        val store = MemoryHostCaptureStore()
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
            hostCaptureStore = store,
        )
        backend.initialize()

        val initial = backend.capabilities()
        val target = initial.advancedSettings.single { it.key == "capturetarget" }
        assertEquals("Phone", target.value)
        assertEquals(listOf("Phone", "Memory card"), target.values)
        assertTrue("capturetarget" in initial.evidence.writableSettings)
        assertTrue(backend.status().rawTransportJson.contains("\"setting\":\"capturetarget\""))

        backend.setSetting("capturetarget", "Memory card")
        assertEquals(
            2L,
            transport.sentContainers.last {
                it.type == PtpContainerType.DATA &&
                    it.code == CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX &&
                    it.parameters().getOrNull(1)?.toInt() == CanonEosPropertyCode.CAPTURE_DESTINATION
            }.parameters()[2],
        )
        assertEquals(
            "Memory card",
            backend.capabilities().advancedSettings.single { it.key == "capturetarget" }.value,
        )

        backend.setSetting("capturetarget", "Phone")
        backend.captureStill()

        assertEquals(
            CanonEosPtp.CAPTURE_DESTINATION_HOST,
            transport.sentContainers.last {
                it.type == PtpContainerType.DATA &&
                    it.code == CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX &&
                    it.parameters().getOrNull(1)?.toInt() == CanonEosPropertyCode.CAPTURE_DESTINATION
            }.parameters()[2],
        )
        assertEquals("IMG_0049.JPG", store.list().single().name)
        assertTrue(transport.hasOperation(CanonEosOperationCode.TRANSFER_COMPLETE))
        backend.close()
    }

    @Test
    fun canonUsbCaptureTargetIsHiddenAndCannotWriteWithoutHostTransferSupport() = runTest {
        val transport = CanonEosScriptedTransport()
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
            hostCaptureStore = MemoryHostCaptureStore(),
        )
        backend.initialize()

        val capabilities = backend.capabilities()
        val failure = runCatching { backend.setSetting("capturetarget", "Phone") }.exceptionOrNull()

        assertTrue(capabilities.advancedSettings.none { it.key == "capturetarget" })
        assertTrue(failure is UnsupportedOperationException)
        assertFalse(transport.hasCanonPropertyWrite(CanonEosPropertyCode.CAPTURE_DESTINATION))
        backend.close()
    }

    @Test
    fun canonUsbCaptureTargetPreservesStateWhenCameraRejectsTheWrite() = runTest {
        val transport = CanonEosScriptedTransport(
            captureDestination = CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
            hostCaptureObjects = listOf(
                HostCaptureObject(0x4B, PtpObjectFormat.EXIF_JPEG, "IMG_0051.JPG", byteArrayOf(5, 1)),
            ),
            rejectPropertyCode = CanonEosPropertyCode.CAPTURE_DESTINATION,
        )
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
            hostCaptureStore = MemoryHostCaptureStore(),
        )
        backend.initialize()
        assertEquals(
            "Phone",
            backend.capabilities().advancedSettings.single { it.key == "capturetarget" }.value,
        )

        val failure = runCatching { backend.setSetting("capturetarget", "Memory card") }.exceptionOrNull()

        assertTrue(failure is PtpResponseException)
        assertEquals(
            "Phone",
            backend.capabilities().advancedSettings.single { it.key == "capturetarget" }.value,
        )
        backend.close()
    }

    @Test
    fun selectedPhoneCaptureTargetStopsBeforeShutterWhenCapacityPreparationFails() = runTest {
        val transport = CanonEosScriptedTransport(
            captureDestination = 2,
            availableShots = 0,
            hostCaptureObjects = listOf(
                HostCaptureObject(0x4A, PtpObjectFormat.EXIF_JPEG, "IMG_0050.JPG", byteArrayOf(5, 0)),
            ),
            rejectOperationCode = CanonEosOperationCode.PC_HDD_CAPACITY,
        )
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
            hostCaptureStore = MemoryHostCaptureStore(),
        )
        backend.initialize()
        assertTrue(backend.capabilities().advancedSettings.any { it.key == "capturetarget" })
        backend.setSetting("capturetarget", "Phone")

        val failure = runCatching { backend.captureStill() }.exceptionOrNull()

        assertTrue(failure is PtpProtocolException)
        assertTrue(failure?.message.orEmpty().contains("explicitly selected phone"))
        assertFalse(transport.hasOperation(CanonEosOperationCode.REMOTE_RELEASE_ON))
        backend.close()
    }

    @Test
    fun canonHostCaptureCompletesEveryRawAndJpegTransferRequest() = runTest {
        val jpeg = HostCaptureObject(
            handle = 0x51,
            objectFormat = PtpObjectFormat.EXIF_JPEG,
            filename = "IMG_0051.JPG",
            bytes = byteArrayOf(1, 2, 3, 4),
        )
        val raw = HostCaptureObject(
            handle = 0x52,
            objectFormat = PtpObjectFormat.CANON_CR3,
            filename = null,
            bytes = byteArrayOf(5, 6, 7, 8, 9),
            eventCode = CanonEosEventCode.REQUEST_OBJECT_TRANSFER_64_LFN,
        )
        val transport = CanonEosScriptedTransport(
            captureDestination = CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
            advertiseCardCaptureDestination = false,
            hostCaptureObjects = listOf(jpeg, raw),
        )
        val store = MemoryHostCaptureStore()
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
            hostCaptureStore = store,
        )
        backend.initialize()

        backend.captureStill()

        val media = backend.listMedia()
        assertEquals(2, media.size)
        assertTrue(media.any { it.name == "IMG_0051.JPG" && it.kind == "image" })
        assertTrue(media.any { it.name.endsWith(".cr3") && it.kind == "raw" })
        assertEquals(
            listOf(0x51L, 0x52L),
            transport.sentContainers.filter {
                it.type == PtpContainerType.COMMAND && it.code == CanonEosOperationCode.TRANSFER_COMPLETE
            }.map { it.parameters().single() },
        )
        backend.close()
    }

    @Test
    fun canonHostCaptureRefreshesPcCapacityBeforeShutterWhenShotsAreLow() = runTest {
        val transport = CanonEosScriptedTransport(
            captureDestination = CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
            advertiseCardCaptureDestination = false,
            availableShots = 0,
            hostCaptureObjects = listOf(
                HostCaptureObject(0x61, PtpObjectFormat.EXIF_JPEG, "IMG_0061.JPG", byteArrayOf(1)),
            ),
        )
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
            hostCaptureStore = MemoryHostCaptureStore(),
        )
        backend.initialize()

        backend.captureStill()

        val capacity = transport.sentContainers.indexOfFirst {
            it.type == PtpContainerType.COMMAND && it.code == CanonEosOperationCode.PC_HDD_CAPACITY
        }
        val shutter = transport.sentContainers.indexOfFirst {
            it.type == PtpContainerType.COMMAND && it.code == CanonEosOperationCode.REMOTE_RELEASE_ON
        }
        assertTrue(capacity >= 0)
        assertTrue(capacity < shutter)
        assertEquals(listOf(0x0FFF_FFFFL, 0x1000L, 1L), transport.sentContainers[capacity].parameters())
        backend.close()
    }

    @Test
    fun canonHostCaptureFailureLeavesNoFileAndDoesNotAcknowledgeTransfer() = runTest {
        val transport = CanonEosScriptedTransport(
            captureDestination = CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
            advertiseCardCaptureDestination = false,
            hostCaptureObjects = listOf(
                HostCaptureObject(0x71, PtpObjectFormat.EXIF_JPEG, "IMG_0071.JPG", byteArrayOf(1, 2, 3)),
            ),
            rejectPartialAtOffset = 0,
        )
        val store = MemoryHostCaptureStore()
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
            hostCaptureStore = store,
        )
        backend.initialize()

        val failure = runCatching { backend.captureStill() }.exceptionOrNull()

        assertTrue(failure is PtpResponseException)
        assertTrue(store.list().isEmpty())
        assertFalse(transport.hasOperation(CanonEosOperationCode.TRANSFER_COMPLETE))
        backend.close()
    }

    @Test
    fun canonCaptureRestoresCameraAdvertisedCardDestinationBeforeShutter() = runTest {
        val transport = CanonEosScriptedTransport(
            captureDestination = CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
        )
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        backend.captureStill()

        val destinationWrite = transport.sentContainers.indexOfFirst { container ->
            container.type == PtpContainerType.DATA &&
                container.code == CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX &&
                container.parameters().getOrNull(1)?.toInt() == CanonEosPropertyCode.CAPTURE_DESTINATION
        }
        val shutter = transport.sentContainers.indexOfFirst { container ->
            container.type == PtpContainerType.COMMAND &&
                container.code == CanonEosOperationCode.REMOTE_RELEASE_ON
        }

        assertTrue(destinationWrite >= 0)
        assertTrue(shutter >= 0)
        assertTrue(destinationWrite < shutter)
        val destinationFields = transport.sentContainers[destinationWrite].parameters()
        assertEquals(2L, destinationFields[2])
        backend.close()
    }

    @Test
    fun canonCaptureDoesNotRewriteAnExistingCardDestination() = runTest {
        val transport = CanonEosScriptedTransport(captureDestination = 2)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        backend.captureStill()

        assertFalse(transport.hasCanonPropertyWrite(CanonEosPropertyCode.CAPTURE_DESTINATION))
        backend.close()
    }

    @Test
    fun mediaPreviewRejectsOversizedObjectMetadataBeforeGetObject() = runTest {
        val transport = ScriptedTransport(
            advertiseCapture = false,
            advertisedObjectSize = 32L * 1024L * 1024L + 1L,
        )
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()
        val item = backend.listMedia().single()

        val failure = runCatching { backend.mediaPreview(item) }.exceptionOrNull()

        assertFalse(item.previewAvailable)
        assertTrue(failure is PtpProtocolException)
        assertFalse(PtpOperationCode.GET_OBJECT in transport.sentOperations)
        backend.close()
    }

    @Test
    fun canonBulbExposureUsesBalancedHalfAndFullRemoteReleaseSequence() = runTest {
        val transport = CanonEosScriptedTransport(captureDestination = 2)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        assertTrue(backend.capabilities().matrix.supports(CameraFeature.BULB_EXPOSURE))
        val started = backend.startBulbExposure()
        val stopped = backend.stopBulbExposure()

        val releaseCommands = transport.sentContainers.filter {
            it.type == PtpContainerType.COMMAND &&
                it.code in setOf(CanonEosOperationCode.REMOTE_RELEASE_ON, CanonEosOperationCode.REMOTE_RELEASE_OFF)
        }.map { it.code to it.parameters() }
        assertEquals(
            listOf(
                CanonEosOperationCode.REMOTE_RELEASE_ON to listOf(1L, 0L),
                CanonEosOperationCode.REMOTE_RELEASE_ON to listOf(2L, 0L),
                CanonEosOperationCode.REMOTE_RELEASE_OFF to listOf(2L),
                CanonEosOperationCode.REMOTE_RELEASE_OFF to listOf(1L),
            ),
            releaseCommands,
        )
        assertTrue(started.bulbExposureActive == true)
        assertTrue(stopped.bulbExposureActive == false)
        assertTrue(CameraFeature.BULB_EXPOSURE in backend.observedFeatures())
        backend.close()
    }

    @Test
    fun canonCloseReleasesAnActiveBulbExposure() = runTest {
        val transport = CanonEosScriptedTransport(captureDestination = 2)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        backend.startBulbExposure()
        backend.close()

        val releaseCommands = transport.sentContainers.filter {
            it.type == PtpContainerType.COMMAND && it.code == CanonEosOperationCode.REMOTE_RELEASE_OFF
        }.map { it.parameters() }
        assertEquals(listOf(listOf(2L), listOf(1L)), releaseCommands)
        assertTrue(transport.closed)
    }

    @Test
    fun canonFailedFullBulbPressAttemptsFullAndHalfRelease() = runTest {
        val transport = CanonEosScriptedTransport(rejectFullRemotePress = true)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val failure = runCatching { backend.startBulbExposure() }.exceptionOrNull()

        val releaseCommands = transport.sentContainers.filter {
            it.type == PtpContainerType.COMMAND && it.code == CanonEosOperationCode.REMOTE_RELEASE_OFF
        }.map { it.parameters() }
        assertTrue(failure is PtpResponseException)
        assertEquals(listOf(listOf(2L), listOf(1L)), releaseCommands)
        assertFalse(backend.status().bulbExposureActive == true)
        backend.close()
    }

    @Test
    fun canonFailedHalfBulbPressStillAttemptsFullAndHalfRelease() = runTest {
        val transport = CanonEosScriptedTransport(rejectHalfRemotePress = true)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val failure = runCatching { backend.startBulbExposure() }.exceptionOrNull()

        val releaseCommands = transport.sentContainers.filter {
            it.type == PtpContainerType.COMMAND && it.code == CanonEosOperationCode.REMOTE_RELEASE_OFF
        }.map { it.parameters() }
        assertTrue(failure is PtpResponseException)
        assertEquals(listOf(listOf(2L), listOf(1L)), releaseCommands)
        assertFalse(backend.status().bulbExposureActive == true)
        backend.close()
    }

    @Test
    fun canonCaptureRefusesHostDestinationWhenNoCardTargetIsAdvertised() = runTest {
        val transport = CanonEosScriptedTransport(
            captureDestination = CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
            advertiseCardCaptureDestination = false,
        )
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val failure = runCatching { backend.captureStill() }.exceptionOrNull()

        assertTrue(failure is PtpProtocolException)
        assertTrue(failure?.message.orEmpty().contains("memory-card capture destination"))
        assertFalse(transport.hasOperation(CanonEosOperationCode.REMOTE_RELEASE_ON))
        backend.close()
    }

    @Test
    fun canonCaptureStopsBeforeShutterWhenCardDestinationWriteIsRejected() = runTest {
        val transport = CanonEosScriptedTransport(
            captureDestination = CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
            rejectPropertyCode = CanonEosPropertyCode.CAPTURE_DESTINATION,
        )
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val failure = runCatching { backend.captureStill() }.exceptionOrNull()

        assertTrue(failure is PtpResponseException)
        assertFalse(transport.hasOperation(CanonEosOperationCode.REMOTE_RELEASE_ON))
        backend.close()
    }

    @Test
    fun canonAutofocusFallsBackToBalancedHalfPressWithoutDedicatedOperations() = runTest {
        val transport = CanonEosScriptedTransport(advertiseDedicatedAutofocus = false)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        assertTrue(backend.capabilities().matrix.supports(CameraFeature.AUTOFOCUS))
        backend.autofocus()

        assertFalse(transport.hasOperation(CanonEosOperationCode.DO_AF))
        assertFalse(transport.hasOperation(CanonEosOperationCode.AF_CANCEL))
        assertTrue(transport.hasOperation(CanonEosOperationCode.REMOTE_RELEASE_ON))
        assertTrue(transport.hasOperation(CanonEosOperationCode.REMOTE_RELEASE_OFF))
        backend.close()
    }

    @Test
    fun canonDedicatedAutofocusDoesNotRequireRemoteReleaseOperations() = runTest {
        val transport = CanonEosScriptedTransport(advertiseRemoteRelease = false)
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val capabilities = backend.capabilities()
        backend.autofocus()
        val halfPressFailure = runCatching { backend.halfPressShutter() }.exceptionOrNull()

        assertTrue(capabilities.matrix.supports(CameraFeature.AUTOFOCUS))
        assertFalse(capabilities.matrix.supports(CameraFeature.SHUTTER_HALF_PRESS))
        assertFalse(capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(transport.hasOperation(CanonEosOperationCode.DO_AF))
        assertTrue(transport.hasOperation(CanonEosOperationCode.AF_CANCEL))
        assertTrue(halfPressFailure is UnsupportedOperationException)
        backend.close()
    }

    @Test
    fun canonDedicatedAutofocusCancelsAfterStartFailure() = runTest {
        val transport = CanonEosScriptedTransport(
            rejectOperationCode = CanonEosOperationCode.DO_AF,
        )
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val failure = runCatching { backend.autofocus() }.exceptionOrNull()

        assertTrue(failure is PtpResponseException)
        assertTrue(transport.hasOperation(CanonEosOperationCode.DO_AF))
        assertTrue(transport.hasOperation(CanonEosOperationCode.AF_CANCEL))
        assertFalse(transport.hasOperation(CanonEosOperationCode.REMOTE_RELEASE_ON))
        backend.close()
    }

    @Test
    fun canonDedicatedAutofocusCancelsCameraWhenCoroutineIsCancelled() = runTest {
        val transport = CanonEosScriptedTransport()
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )
        backend.initialize()

        val autofocus = launch(start = CoroutineStart.UNDISPATCHED) {
            backend.autofocus()
        }
        assertTrue(transport.hasOperation(CanonEosOperationCode.DO_AF))

        autofocus.cancelAndJoin()

        assertTrue(transport.hasOperation(CanonEosOperationCode.AF_CANCEL))
        backend.close()
    }

    private class ScriptedTransport(
        private val advertiseCapture: Boolean,
        private val advertiseDelete: Boolean = true,
        private val advertiseThumbnail: Boolean = true,
        private val advertisedThumbnailSize: Long = THUMBNAIL_BYTES.size.toLong(),
        private val advertisedObjectSize: Long = OBJECT_BYTES.size.toLong(),
        private val descriptorFailureCode: Int? = null,
    ) : PtpTransport {
        private val incoming = ArrayDeque<PtpContainer>()
        val sentOperations = mutableListOf<Int>()
        val sentContainers = mutableListOf<PtpContainer>()
        var closed = false
        private var pendingPropertyWrite: Int? = null
        private val properties = propertyFixtures().toMutableMap()

        override suspend fun send(container: PtpContainer) {
            sentOperations += container.code
            sentContainers += container
            val transaction = container.transactionId
            when (container.code) {
                PtpOperationCode.GET_DEVICE_INFO -> {
                    incoming += data(
                        container.code,
                        transaction,
                        deviceInfoPayload(
                            advertiseCapture,
                            advertiseDelete,
                            advertiseThumbnail,
                            advertiseProperties = true,
                        ),
                    )
                    incoming += ok(transaction)
                }

                PtpOperationCode.OPEN_SESSION,
                PtpOperationCode.CLOSE_SESSION,
                PtpOperationCode.INITIATE_CAPTURE,
                PtpOperationCode.DELETE_OBJECT,
                -> incoming += ok(transaction)

                PtpOperationCode.GET_STORAGE_IDS -> {
                    incoming += data(container.code, transaction, Writer().apply {
                        u32(1)
                        u32(STORAGE_ID)
                    }.bytes())
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_STORAGE_INFO -> {
                    incoming += data(container.code, transaction, storageInfoPayload())
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_OBJECT_HANDLES -> {
                    incoming += data(container.code, transaction, Writer().apply {
                        u32(1)
                        u32(OBJECT_HANDLE)
                    }.bytes())
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_OBJECT_INFO -> {
                    incoming += data(
                        container.code,
                        transaction,
                        objectInfoPayload(advertisedThumbnailSize, advertisedObjectSize),
                    )
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_OBJECT -> {
                    incoming += data(container.code, transaction, OBJECT_BYTES)
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_THUMB -> {
                    incoming += data(container.code, transaction, THUMBNAIL_BYTES)
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_DEVICE_PROP_DESC -> {
                    val propertyCode = container.parameterU32().toInt()
                    if (propertyCode == descriptorFailureCode) {
                        incoming += PtpContainer(
                            PtpContainerType.RESPONSE,
                            PtpResponseCode.DEVICE_PROP_NOT_SUPPORTED,
                            transaction,
                        )
                    } else {
                        val fixture = properties[propertyCode]
                            ?: error("Unexpected property 0x${propertyCode.toString(16)}")
                        incoming += data(container.code, transaction, fixture.descriptorPayload())
                        incoming += ok(transaction)
                    }
                }

                PtpOperationCode.GET_DEVICE_PROP_VALUE -> {
                    val propertyCode = container.parameterU32().toInt()
                    val fixture = properties[propertyCode] ?: error("Unexpected property 0x${propertyCode.toString(16)}")
                    incoming += data(
                        container.code,
                        transaction,
                        PtpPropertyCodec.encodeValue(fixture.dataType, fixture.current),
                    )
                    incoming += ok(transaction)
                }

                PtpOperationCode.SET_DEVICE_PROP_VALUE -> {
                    if (container.type == PtpContainerType.COMMAND) {
                        pendingPropertyWrite = container.parameterU32().toInt()
                    } else {
                        val propertyCode = pendingPropertyWrite ?: error("Property data arrived without a command.")
                        val fixture = properties[propertyCode] ?: error("Unexpected property 0x${propertyCode.toString(16)}")
                        fixture.current = PtpPropertyCodec.decodeValue(fixture.dataType, container.payload)
                        pendingPropertyWrite = null
                        incoming += ok(transaction)
                    }
                }

                else -> error("Unexpected operation 0x${container.code.toString(16)}")
            }
        }

        override suspend fun receive(maxPayloadBytes: Int): PtpContainer =
            incoming.removeFirstOrNull() ?: error("No scripted response is queued.")

        override fun close() {
            closed = true
        }

        private fun data(operation: Int, transaction: Long, payload: ByteArray) =
            PtpContainer(PtpContainerType.DATA, operation, transaction, payload)

        private fun ok(transaction: Long) =
            PtpContainer(PtpContainerType.RESPONSE, PtpResponseCode.OK, transaction)

        private fun PtpContainer.parameterU32(): Long =
            payload[0].toUByte().toLong() or
                (payload[1].toUByte().toLong() shl 8) or
                (payload[2].toUByte().toLong() shl 16) or
                (payload[3].toUByte().toLong() shl 24)
    }

    private inner class CanonEosScriptedTransport(
        private val malformedCaptureEvent: Boolean = false,
        private val advertiseEventPolling: Boolean = true,
        private val advertiseMovieRecording: Boolean = true,
        private val rejectMovieRecording: Boolean = false,
        private val advertiseAdvancedSettings: Boolean = true,
        private val rejectPropertyCode: Int? = null,
        private val captureDestination: Int = 2,
        private val advertiseCardCaptureDestination: Boolean = true,
        private val advertiseDedicatedAutofocus: Boolean = true,
        private val advertiseRemoteRelease: Boolean = true,
        private val advertiseLiveViewMagnification: Boolean = true,
        private val rejectOperationCode: Int? = null,
        private val rejectHalfRemotePress: Boolean = false,
        private val rejectFullRemotePress: Boolean = false,
        private val availableShots: Int = 46_822,
        private val hostCaptureObjects: List<HostCaptureObject> = emptyList(),
        private val advertiseHostTransferOperations: Boolean = hostCaptureObjects.isNotEmpty(),
        private val rejectPartialAtOffset: Long? = null,
        private val delayAfterFullReleaseMillis: Long = 0L,
        scriptedEvents: List<ByteArray> = emptyList(),
    ) : PtpTransport {
        private val incoming = ArrayDeque<PtpContainer>()
        private val pendingScriptedEvents = ArrayDeque(scriptedEvents)
        val sentContainers = mutableListOf<PtpContainer>()
        var closed = false
        private var pendingPropertyWrite = false
        private var captureEventPending = false
        private var fullPressActive = false
        private var initialPropertyEventsPending = true
        private var moviePropertyEventPending: Int? = null

        override suspend fun send(container: PtpContainer) {
            sentContainers += container
            val transaction = container.transactionId
            when (container.code) {
                PtpOperationCode.GET_DEVICE_INFO -> {
                    incoming += data(
                        container.code,
                        transaction,
                        canonDeviceInfoPayload(
                            advertiseDedicatedAutofocus,
                            advertiseRemoteRelease,
                            advertiseHostTransferOperations,
                            advertiseLiveViewMagnification,
                            advertiseEventPolling,
                        ),
                    )
                    incoming += ok(transaction)
                }

                PtpOperationCode.OPEN_SESSION,
                PtpOperationCode.CLOSE_SESSION,
                CanonEosOperationCode.SET_REMOTE_MODE,
                CanonEosOperationCode.SET_EVENT_MODE,
                CanonEosOperationCode.DRIVE_LENS,
                CanonEosOperationCode.ZOOM,
                CanonEosOperationCode.DO_AF,
                CanonEosOperationCode.AF_CANCEL,
                CanonEosOperationCode.TRANSFER_COMPLETE,
                CanonEosOperationCode.PC_HDD_CAPACITY,
                -> incoming += if (container.code == rejectOperationCode) {
                    response(PtpResponseCode.GENERAL_ERROR, transaction)
                } else {
                    ok(transaction)
                }

                CanonEosOperationCode.REMOTE_RELEASE_ON -> {
                    val fullPress = container.parameters().firstOrNull() == 2L
                    val rejected =
                        (rejectHalfRemotePress && container.parameters().firstOrNull() == 1L) ||
                            (rejectFullRemotePress && container.parameters().firstOrNull() == 2L)
                    incoming += if (rejected) {
                        response(PtpResponseCode.GENERAL_ERROR, transaction)
                    } else {
                        if (fullPress) fullPressActive = true
                        ok(transaction)
                    }
                }

                CanonEosOperationCode.REMOTE_RELEASE_OFF -> {
                    val fullRelease = container.parameters().firstOrNull() == 2L
                    val rejected = container.code == rejectOperationCode
                    if (!rejected && fullRelease && fullPressActive) {
                        captureEventPending = true
                        fullPressActive = false
                    }
                    incoming += if (rejected) {
                        response(PtpResponseCode.GENERAL_ERROR, transaction)
                    } else {
                        ok(transaction)
                    }
                    if (fullRelease && delayAfterFullReleaseMillis > 0L) delay(delayAfterFullReleaseMillis)
                }

                CanonEosOperationCode.GET_EVENT -> {
                    val moviePropertyValue = moviePropertyEventPending
                    val payload = when {
                        initialPropertyEventsPending -> canonPropertyEvents(
                            advertiseMovieRecording,
                            advertiseAdvancedSettings,
                            captureDestination,
                            advertiseCardCaptureDestination,
                            availableShots,
                        ).also {
                            initialPropertyEventsPending = false
                        }
                        pendingScriptedEvents.isNotEmpty() -> pendingScriptedEvents.removeFirst()
                        moviePropertyValue != null ->
                            (eosPropertyValue(CanonEosPropertyCode.EVF_RECORD_STATUS, moviePropertyValue) +
                                eosBlock(0, byteArrayOf())).also {
                                moviePropertyEventPending = null
                            }
                        !captureEventPending -> eosBlock(0, byteArrayOf())
                        malformedCaptureEvent -> byteArrayOf(40, 0, 0, 0, 0x81.toByte(), 0xC1.toByte(), 0, 0)
                        hostCaptureObjects.isNotEmpty() -> hostCaptureObjects
                            .fold(byteArrayOf()) { events, item -> events + eosHostTransfer(item) } +
                            eosBlock(0, byteArrayOf())
                        else -> eosBlock(CanonEosEventCode.OBJECT_ADDED_EX, ByteArray(40)) + eosBlock(0, byteArrayOf())
                    }
                    captureEventPending = false
                    incoming += data(container.code, transaction, payload)
                    incoming += ok(transaction)
                }

                CanonEosOperationCode.GET_VIEWFINDER_DATA -> {
                    val payload = eosBlock(2, byteArrayOf(1, 2, 3)) + eosBlock(1, CANON_LIVE_VIEW_JPEG)
                    incoming += data(container.code, transaction, payload)
                    incoming += ok(transaction)
                }

                CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX -> {
                    if (container.type == PtpContainerType.COMMAND) {
                        pendingPropertyWrite = true
                    } else {
                        check(pendingPropertyWrite) { "Canon property data arrived without a command." }
                        pendingPropertyWrite = false
                        val fields = container.parameters()
                        val propertyCode = fields.getOrNull(1)?.toInt()
                        val value = fields.getOrNull(2)?.toInt()
                        if (
                            propertyCode == rejectPropertyCode ||
                            propertyCode == CanonEosPropertyCode.EVF_RECORD_STATUS && rejectMovieRecording
                        ) {
                            incoming += response(PtpResponseCode.GENERAL_ERROR, transaction)
                        } else {
                            if (propertyCode == CanonEosPropertyCode.EVF_RECORD_STATUS && value != null) {
                                moviePropertyEventPending = value and 0xFFFF
                            }
                            incoming += ok(transaction)
                        }
                    }
                }

                PtpOperationCode.GET_STORAGE_IDS -> {
                    incoming += data(container.code, transaction, Writer().apply {
                        u32(1)
                        u32(STORAGE_ID)
                    }.bytes())
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_STORAGE_INFO -> {
                    incoming += data(container.code, transaction, storageInfoPayload())
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_PARTIAL_OBJECT -> {
                    val parameters = container.parameters()
                    val handle = parameters[0]
                    val offset = parameters[1]
                    val requested = parameters[2].toInt()
                    val item = hostCaptureObjects.single { it.handle == handle }
                    if (offset == rejectPartialAtOffset) {
                        incoming += response(PtpResponseCode.GENERAL_ERROR, transaction)
                    } else {
                        val start = offset.toInt()
                        val end = minOf(start + requested, item.bytes.size)
                        incoming += data(container.code, transaction, item.bytes.copyOfRange(start, end))
                        incoming += ok(transaction)
                    }
                }

                else -> error("Unexpected Canon EOS operation 0x${container.code.toString(16)}")
            }
        }

        override suspend fun receive(maxPayloadBytes: Int): PtpContainer =
            incoming.removeFirstOrNull() ?: error("No Canon EOS scripted response is queued.")

        override fun close() {
            closed = true
        }

        fun hasCanonPropertyWrite(propertyCode: Int): Boolean = sentContainers.any { container ->
            container.type == PtpContainerType.DATA &&
                container.code == CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX &&
                container.parameters().getOrNull(1)?.toInt() == propertyCode
        }

        fun hasOperation(operationCode: Int): Boolean = sentContainers.any { container ->
            container.type == PtpContainerType.COMMAND && container.code == operationCode
        }

        private fun data(operation: Int, transaction: Long, payload: ByteArray) =
            PtpContainer(PtpContainerType.DATA, operation, transaction, payload)

        private fun ok(transaction: Long) =
            PtpContainer(PtpContainerType.RESPONSE, PtpResponseCode.OK, transaction)

        private fun response(code: Int, transaction: Long) =
            PtpContainer(PtpContainerType.RESPONSE, code, transaction)
    }

    private data class HostCaptureObject(
        val handle: Long,
        val objectFormat: Int,
        val filename: String?,
        val bytes: ByteArray,
        val eventCode: Int = CanonEosEventCode.REQUEST_OBJECT_TRANSFER,
    )

    private class MemoryHostCaptureStore : UsbHostCaptureStore {
        private val items = linkedMapOf<String, Pair<CameraMediaItem, ByteArray>>()

        override fun owns(item: CameraMediaItem): Boolean = item.id.startsWith("memory-host:")

        override suspend fun save(
            filename: String,
            kind: String,
            expectedSizeBytes: Long,
            writer: suspend (OutputStream) -> Long,
        ): CameraMediaItem {
            val output = ByteArrayOutputStream()
            val written = writer(output)
            assertEquals(expectedSizeBytes, written)
            assertEquals(expectedSizeBytes, output.size().toLong())
            val id = "memory-host:${items.size}:$filename"
            val item = CameraMediaItem(
                id,
                filename,
                kind,
                written,
                "test",
                previewAvailable = kind == "image" && filename.endsWith(".jpg", ignoreCase = true),
            )
            items[id] = item to output.toByteArray()
            return item
        }

        override suspend fun list(): List<CameraMediaItem> = items.values.map { it.first }

        override suspend fun thumbnail(item: CameraMediaItem): CameraMediaThumbnail = CameraMediaThumbnail(
            item = item,
            bytes = requireEntry(item).second,
            contentType = "image/jpeg",
        )

        override suspend fun preview(item: CameraMediaItem): CameraMediaPreview {
            val bytes = requireEntry(item).second
            check(item.previewAvailable)
            check(bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
            check(bytes[bytes.lastIndex - 1] == 0xFF.toByte() && bytes.last() == 0xD9.toByte())
            return CameraMediaPreview(item, bytes, "image/jpeg")
        }

        override suspend fun download(
            item: CameraMediaItem,
            destination: OutputStream,
            onProgress: (CameraMediaTransferProgress) -> Unit,
        ): CameraMediaDownloadResult {
            val bytes = requireEntry(item).second
            destination.write(bytes)
            onProgress(CameraMediaTransferProgress(bytes.size.toLong(), bytes.size.toLong()))
            return CameraMediaDownloadResult(item, bytes.size.toLong(), "application/octet-stream")
        }

        override suspend fun delete(item: CameraMediaItem) {
            check(items.remove(item.id) != null)
        }

        fun bytes(item: CameraMediaItem): ByteArray = requireEntry(item).second

        private fun requireEntry(item: CameraMediaItem): Pair<CameraMediaItem, ByteArray> =
            items[item.id] ?: error("Missing host capture ${item.id}")
    }

    private fun PtpContainer.parameters(): List<Long> = payload.asList().chunked(4).map { bytes ->
        bytes[0].toUByte().toLong() or
            (bytes[1].toUByte().toLong() shl 8) or
            (bytes[2].toUByte().toLong() shl 16) or
            (bytes[3].toUByte().toLong() shl 24)
    }

    private fun eosBlock(type: Int, data: ByteArray): ByteArray = ByteArray(data.size + 8).also { block ->
        repeat(4) { index -> block[index] = (block.size ushr (index * 8)).toByte() }
        repeat(4) { index -> block[4 + index] = (type ushr (index * 8)).toByte() }
        data.copyInto(block, destinationOffset = 8)
    }

    private fun eosHostTransfer(item: HostCaptureObject): ByteArray {
        val lfn = item.eventCode == CanonEosEventCode.REQUEST_OBJECT_TRANSFER_64_LFN
        val filename = if (lfn) byteArrayOf() else item.filename.orEmpty().encodeToByteArray() + byteArrayOf(0)
        val data = ByteArray(if (lfn) 29 else 20 + filename.size)
        repeat(4) { index -> data[index] = (item.handle ushr (index * 8)).toByte() }
        repeat(2) { index -> data[4 + index] = (item.objectFormat ushr (index * 8)).toByte() }
        repeat(4) { index -> data[12 + index] = (item.bytes.size ushr (index * 8)).toByte() }
        if (!lfn) filename.copyInto(data, destinationOffset = 20)
        return eosBlock(item.eventCode, data)
    }

    private fun canonPropertyEvents(
        advertiseMovieRecording: Boolean,
        advertiseAdvancedSettings: Boolean,
        captureDestination: Int,
        advertiseCardCaptureDestination: Boolean,
        availableShots: Int,
    ): ByteArray {
        var payload = eosPropertyValue(CanonEosPropertyCode.ISO_SPEED, 0x58) +
            eosAvailableValues(CanonEosPropertyCode.ISO_SPEED, 0x48, 0x58, 0x60) +
            eosPropertyValue(CanonEosPropertyCode.SHUTTER_SPEED, 0x60) +
            eosAvailableValues(CanonEosPropertyCode.SHUTTER_SPEED, 0x60, 0x65) +
            eosPropertyValue(CanonEosPropertyCode.APERTURE, 0x20) +
            eosAvailableValues(CanonEosPropertyCode.APERTURE, 0x20, 0x28) +
            eosPropertyValue(CanonEosPropertyCode.WHITE_BALANCE, 0) +
            eosAvailableValues(CanonEosPropertyCode.WHITE_BALANCE, 0, 1, 8)
        payload += eosPropertyValue(CanonEosPropertyCode.AVAILABLE_SHOTS, availableShots)
        payload += eosPropertyValue(CanonEosPropertyCode.CAPTURE_DESTINATION, captureDestination)
        payload += eosAvailableValues(
            CanonEosPropertyCode.CAPTURE_DESTINATION,
            *if (advertiseCardCaptureDestination) intArrayOf(4, 2) else intArrayOf(4),
        )
        if (advertiseAdvancedSettings) {
            payload += eosPropertyValue(CanonEosPropertyCode.EXPOSURE_COMPENSATION, 0)
            payload += eosAvailableValues(CanonEosPropertyCode.EXPOSURE_COMPENSATION, 0xE8, 0, 0x0B, 0x18)
            payload += eosPropertyValue(CanonEosPropertyCode.COLOR_TEMPERATURE, 5200)
            payload += eosAvailableValues(CanonEosPropertyCode.COLOR_TEMPERATURE, 2500, 5200, 5600, 10000)
            payload += eosPropertyValue(CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A, 0)
            payload += eosAvailableValues(CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A, -9, 0, 9)
            payload += eosPropertyValue(CanonEosPropertyCode.WHITE_BALANCE_ADJUST_B, -2)
            payload += eosAvailableValues(CanonEosPropertyCode.WHITE_BALANCE_ADJUST_B, -9, -2, 0, 9)
            payload += eosPropertyValue(CanonEosPropertyCode.AUTO_EXPOSURE_MODE, 0x0003)
            payload += eosAvailableValues(
                CanonEosPropertyCode.AUTO_EXPOSURE_MODE,
                0x0000,
                0x0001,
                0x0002,
                0x0003,
                0x0004,
                0x0014,
                0x0037,
            )
            payload += eosPropertyValue(CanonEosPropertyCode.COLOR_SPACE, 1)
            payload += eosAvailableValues(CanonEosPropertyCode.COLOR_SPACE, 1, 2)
            payload += eosPropertyValue(CanonEosPropertyCode.MULTI_ASPECT, 0x0D)
            payload += eosAvailableValues(CanonEosPropertyCode.MULTI_ASPECT, 0, 1, 2, 7, 0x0D)
            payload += eosPropertyValue(CanonEosPropertyCode.POWER_ZOOM_SPEED, 8)
            payload += eosAvailableValues(CanonEosPropertyCode.POWER_ZOOM_SPEED, *IntArray(15) { it + 1 })
            payload += eosPropertyValue(CanonEosPropertyCode.AUTO_POWER_OFF, 30)
            payload += eosAvailableValues(
                CanonEosPropertyCode.AUTO_POWER_OFF,
                15, 30, 60, 180, 300, 600, 1800, 0, -1,
            )
            payload += eosPropertyValue(CanonEosPropertyCode.FOCUS_MODE, 1)
            payload += eosAvailableValues(CanonEosPropertyCode.FOCUS_MODE, 0, 1, 2)
            payload += eosPropertyValue(CanonEosPropertyCode.CONTINUOUS_AF_MODE, 0)
            payload += eosAvailableValues(CanonEosPropertyCode.CONTINUOUS_AF_MODE, 0, 1)
            payload += eosPropertyValue(CanonEosPropertyCode.LIVE_VIEW_AF_SYSTEM, 14)
            payload += eosAvailableValues(
                CanonEosPropertyCode.LIVE_VIEW_AF_SYSTEM,
                10, 1, 5, 6, 11, 12, 13, 14, 15, 16, 17, 18,
            )
            payload += eosPropertyValue(CanonEosPropertyCode.DRIVE_MODE, 0x12)
            payload += eosAvailableValues(CanonEosPropertyCode.DRIVE_MODE, 0, 0x12, 4, 5, 0x10, 0x11, 7)
            payload += eosPropertyValue(CanonEosPropertyCode.METERING_MODE, 3)
            payload += eosAvailableValues(CanonEosPropertyCode.METERING_MODE, 3, 4, 1, 5)
            payload += eosPropertyValue(CanonEosPropertyCode.HIGH_ISO_NOISE_REDUCTION, 0)
            payload += eosAvailableValues(CanonEosPropertyCode.HIGH_ISO_NOISE_REDUCTION, 0, 1, 2, 3)
            payload += eosPropertyValue(CanonEosPropertyCode.AEB, 0)
            payload += eosAvailableValues(
                CanonEosPropertyCode.AEB,
                0, 0x03, 0x05, 0x08, 0x0B, 0x0D, 0x10, 0x13, 0x15, 0x18,
            )
            payload += eosPropertyValue(CanonEosPropertyCode.PICTURE_STYLE, 0x87)
            payload += eosAvailableValues(
                CanonEosPropertyCode.PICTURE_STYLE,
                0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x21, 0x22, 0x23,
            )
            val imageFormats = intArrayOf(
                0x03FF,
                0x02FF,
                0xE0FF,
                0x0B03,
                0x0B02,
                0x0C03,
                0x0C02,
                0x0BE0,
                0x0CE0,
                0x0CFF,
                0x0BFF,
            )
            payload += eosImageFormatPropertyValue(CanonEosPropertyCode.IMAGE_FORMAT, 0x0CFF)
            payload += eosImageFormatAvailableValues(CanonEosPropertyCode.IMAGE_FORMAT, *imageFormats)
            payload += eosImageFormatPropertyValue(CanonEosPropertyCode.IMAGE_FORMAT_SD, 0x0CFF)
            payload += eosImageFormatAvailableValues(CanonEosPropertyCode.IMAGE_FORMAT_SD, *imageFormats)
            payload += eosImageFormatPropertyValue(CanonEosPropertyCode.IMAGE_FORMAT_CF, 0x0CFF)
            payload += eosImageFormatAvailableValues(CanonEosPropertyCode.IMAGE_FORMAT_CF, *imageFormats)
            payload += eosPropertyValue(CanonEosPropertyCode.MOVIE_SERVO_AF, 1)
            payload += eosAvailableValues(CanonEosPropertyCode.MOVIE_SERVO_AF, 0, 1)
        }
        if (advertiseMovieRecording) {
            payload += eosPropertyValue(
                CanonEosPropertyCode.EVF_RECORD_STATUS,
                CanonEosPtp.MOVIE_RECORD_TARGET_SDRAM.toInt(),
            )
            payload += eosAvailableValues(
                CanonEosPropertyCode.EVF_RECORD_STATUS,
                CanonEosPtp.MOVIE_RECORD_TARGET_CARD.toInt(),
                CanonEosPtp.MOVIE_RECORD_TARGET_NONE.toInt(),
                CanonEosPtp.MOVIE_RECORD_TARGET_SDRAM.toInt(),
            )
        }
        return payload + eosBlock(0, byteArrayOf())
    }

    private fun eosPropertyValue(propertyCode: Int, value: Int): ByteArray = eosBlock(
        CanonEosEventCode.PROPERTY_VALUE_CHANGED,
        Writer().apply {
            u32(propertyCode)
            u32(value)
        }.bytes(),
    )

    private fun eosAvailableValues(propertyCode: Int, vararg values: Int): ByteArray = eosBlock(
        CanonEosEventCode.AVAILABLE_LIST_CHANGED,
        Writer().apply {
            u32(propertyCode)
            u32(3)
            u32(values.size)
            values.forEach(::u32)
        }.bytes(),
    )

    private fun eosImageFormatPropertyValue(propertyCode: Int, value: Int): ByteArray = eosBlock(
        CanonEosEventCode.PROPERTY_VALUE_CHANGED,
        u32Fields(propertyCode) + imageFormatData(propertyCode, value),
    )

    private fun eosImageFormatAvailableValues(propertyCode: Int, vararg values: Int): ByteArray {
        var data = u32Fields(propertyCode, 3, values.size)
        values.forEach { value -> data += imageFormatData(propertyCode, value) }
        return eosBlock(CanonEosEventCode.AVAILABLE_LIST_CHANGED, data)
    }

    private fun imageFormatData(propertyCode: Int, value: Int): ByteArray =
        CanonEosPtp.propertyPayload(propertyCode, value.toLong()).let { it.copyOfRange(8, it.size) }

    private fun u32Fields(vararg values: Int): ByteArray = Writer().apply {
        values.forEach(::u32)
    }.bytes()

    private class Writer {
        private val output = ByteArrayOutputStream()

        fun u16(value: Int) {
            output.write(value and 0xFF)
            output.write((value ushr 8) and 0xFF)
        }

        fun u8(value: Int) {
            output.write(value and 0xFF)
        }

        fun u32(value: Int) = u32(value.toLong())

        fun u32(value: Long) {
            repeat(4) { index -> output.write(((value ushr (index * 8)) and 0xFF).toInt()) }
        }

        fun u64(value: ULong) {
            repeat(8) { index -> output.write(((value shr (index * 8)) and 0xFFUL).toInt()) }
        }

        fun raw(bytes: ByteArray) {
            output.write(bytes)
        }

        fun u16Array(values: List<Int>) {
            u32(values.size)
            values.forEach(::u16)
        }

        fun string(value: String) {
            if (value.isEmpty()) {
                output.write(0)
                return
            }
            val encoded = value.toByteArray(StandardCharsets.UTF_16LE)
            output.write(encoded.size / 2 + 1)
            output.write(encoded)
            output.write(0)
            output.write(0)
        }

        fun bytes() = output.toByteArray()
    }

    companion object {
        private const val STORAGE_ID = 0x00010001L
        private const val OBJECT_HANDLE = 0x42L
        private val OBJECT_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 3, 3, 7, 9, 0xFF.toByte(), 0xD9.toByte())
        private val THUMBNAIL_BYTES = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 4, 2, 0xFF.toByte(), 0xD9.toByte(),
        )
        private val CANON_LIVE_VIEW_JPEG = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte(),
        )

        private fun canonDeviceInfoPayload(
            advertiseDedicatedAutofocus: Boolean,
            advertiseRemoteRelease: Boolean,
            advertiseHostTransferOperations: Boolean = false,
            advertiseLiveViewMagnification: Boolean = true,
            advertiseEventPolling: Boolean = true,
        ): ByteArray = Writer().apply {
            u16(100)
            u32(CanonEosPtp.VENDOR_EXTENSION_ID)
            u16(100)
            string("")
            u16(0)
            u16Array(
                buildList {
                    addAll(
                        listOf(
                            PtpOperationCode.GET_DEVICE_INFO,
                            PtpOperationCode.OPEN_SESSION,
                            PtpOperationCode.CLOSE_SESSION,
                            PtpOperationCode.GET_STORAGE_IDS,
                            PtpOperationCode.GET_STORAGE_INFO,
                            CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
                            CanonEosOperationCode.SET_REMOTE_MODE,
                            CanonEosOperationCode.SET_EVENT_MODE,
                            CanonEosOperationCode.GET_VIEWFINDER_DATA,
                            CanonEosOperationCode.DRIVE_LENS,
                        )
                    )
                    if (advertiseEventPolling) add(CanonEosOperationCode.GET_EVENT)
                    if (advertiseRemoteRelease) {
                        add(CanonEosOperationCode.REMOTE_RELEASE_ON)
                        add(CanonEosOperationCode.REMOTE_RELEASE_OFF)
                    }
                    if (advertiseDedicatedAutofocus) {
                        add(CanonEosOperationCode.DO_AF)
                        add(CanonEosOperationCode.AF_CANCEL)
                    }
                    if (advertiseLiveViewMagnification) {
                        add(CanonEosOperationCode.ZOOM)
                    }
                    if (advertiseHostTransferOperations) {
                        add(PtpOperationCode.GET_PARTIAL_OBJECT)
                        add(CanonEosOperationCode.TRANSFER_COMPLETE)
                        add(CanonEosOperationCode.PC_HDD_CAPACITY)
                    }
                }
            )
            u16Array(emptyList())
            u16Array(emptyList())
            u16Array(listOf(PtpObjectFormat.EXIF_JPEG))
            u16Array(listOf(PtpObjectFormat.EXIF_JPEG))
            string("Canon.Inc")
            string("Canon EOS R6 Mark III")
            string("3-1.0.0")
            string("TEST-SERIAL-0001")
        }.bytes()

        private fun deviceInfoPayload(
            advertiseCapture: Boolean,
            advertiseDelete: Boolean,
            advertiseThumbnail: Boolean,
            advertiseProperties: Boolean,
        ): ByteArray = Writer().apply {
            u16(100)
            u32(0x0000000B)
            u16(100)
            string("Canon extension")
            u16(0)
            u16Array(
                buildList {
                    add(PtpOperationCode.GET_DEVICE_INFO)
                    add(PtpOperationCode.OPEN_SESSION)
                    add(PtpOperationCode.CLOSE_SESSION)
                    add(PtpOperationCode.GET_STORAGE_IDS)
                    add(PtpOperationCode.GET_STORAGE_INFO)
                    add(PtpOperationCode.GET_OBJECT_HANDLES)
                    add(PtpOperationCode.GET_OBJECT_INFO)
                    add(PtpOperationCode.GET_OBJECT)
                    if (advertiseThumbnail) add(PtpOperationCode.GET_THUMB)
                    if (advertiseDelete) add(PtpOperationCode.DELETE_OBJECT)
                    if (advertiseCapture) add(PtpOperationCode.INITIATE_CAPTURE)
                    if (advertiseProperties) {
                        add(PtpOperationCode.GET_DEVICE_PROP_DESC)
                        add(PtpOperationCode.GET_DEVICE_PROP_VALUE)
                        add(PtpOperationCode.SET_DEVICE_PROP_VALUE)
                    }
                }
            )
            u16Array(emptyList())
            u16Array(if (advertiseProperties) propertyFixtures().keys.toList() else emptyList())
            u16Array(listOf(PtpObjectFormat.EXIF_JPEG))
            u16Array(listOf(PtpObjectFormat.EXIF_JPEG))
            string("Canon")
            string("EOS R6 Mark III")
            string("1.0.0")
            string("TEST-SERIAL-0001")
        }.bytes()

        private fun propertyFixtures(): Map<Int, PropertyFixture> = listOf(
            PropertyFixture(
                code = PtpDevicePropertyCode.BATTERY_LEVEL,
                dataType = PtpDataType(PtpDataType.UINT8),
                writable = false,
                defaultValue = PtpPropertyValue.Unsigned(100UL),
                current = PtpPropertyValue.Unsigned(82UL),
                values = emptyList(),
            ),
            unsignedProperty(PtpDevicePropertyCode.WHITE_BALANCE, PtpDataType.UINT16, 2, 2, 4, 6),
            unsignedProperty(PtpDevicePropertyCode.F_NUMBER, PtpDataType.UINT16, 400, 280, 400),
            unsignedProperty(PtpDevicePropertyCode.EXPOSURE_TIME, PtpDataType.UINT32, 400, 200, 400),
            unsignedProperty(PtpDevicePropertyCode.EXPOSURE_INDEX, PtpDataType.UINT16, 400, 100, 400, 800),
        ).associateBy(PropertyFixture::code)

        private fun unsignedProperty(
            code: Int,
            typeCode: Int,
            current: Long,
            vararg values: Long,
        ) = PropertyFixture(
            code = code,
            dataType = PtpDataType(typeCode),
            writable = true,
            defaultValue = PtpPropertyValue.Unsigned(values.first().toULong()),
            current = PtpPropertyValue.Unsigned(current.toULong()),
            values = values.map { PtpPropertyValue.Unsigned(it.toULong()) },
        )

        private fun storageInfoPayload(): ByteArray = Writer().apply {
            u16(3)
            u16(2)
            u16(0)
            u64(64UL * 1024UL * 1024UL * 1024UL)
            u64(32UL * 1024UL * 1024UL * 1024UL)
            u32(1234)
            string("SD")
            string("EOS_CARD")
        }.bytes()

        private fun objectInfoPayload(thumbnailSize: Long, objectSize: Long = OBJECT_BYTES.size.toLong()): ByteArray = Writer().apply {
            u32(STORAGE_ID)
            u16(PtpObjectFormat.EXIF_JPEG)
            u16(0)
            u32(objectSize)
            u16(PtpObjectFormat.EXIF_JPEG)
            u32(thumbnailSize)
            u32(160)
            u32(120)
            u32(6000)
            u32(4000)
            u32(24)
            u32(0)
            u16(0)
            u32(0)
            u32(42)
            string("IMG_0042.JPG")
            string("20260721T143025")
            string("20260721T143026")
            string("")
        }.bytes()

        private data class PropertyFixture(
            val code: Int,
            val dataType: PtpDataType,
            val writable: Boolean,
            val defaultValue: PtpPropertyValue,
            var current: PtpPropertyValue,
            val values: List<PtpPropertyValue>,
        ) {
            fun descriptorPayload(): ByteArray = Writer().apply {
                u16(code)
                u16(dataType.code)
                u8(if (writable) 1 else 0)
                raw(PtpPropertyCodec.encodeValue(dataType, defaultValue))
                raw(PtpPropertyCodec.encodeValue(dataType, current))
                if (values.isEmpty()) {
                    u8(0)
                } else {
                    u8(2)
                    u16(values.size)
                    values.forEach { raw(PtpPropertyCodec.encodeValue(dataType, it)) }
                }
            }.bytes()
        }
    }
}
