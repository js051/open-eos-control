package dev.openeos.control.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
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
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<CameraMediaTransferProgress>()
        val download = backend.downloadMedia(media.single(), output, progress::add)
        backend.captureStill()
        backend.close()

        assertEquals("Canon EOS R6 Mark III", info.model)
        assertEquals("ptp-usb/1.00", info.api)
        assertEquals(true, status.mediaAvailable)
        assertEquals(82, status.batteryLevel)
        assertEquals("400", status.exposure.iso)
        assertTrue(status.rawStorageJson.contains("EOS_CARD"))
        assertTrue(status.rawTransportJson.contains("\"vendorExtensionId\":\"0x0000000B\""))
        assertTrue(status.rawTransportJson.contains("\"code\":\"0x500F\""))
        assertTrue(status.rawTransportJson.contains("\"writable\":true"))
        assertTrue(capabilities.matrix.supports(CameraFeature.USB_DIAGNOSTICS))
        assertTrue(capabilities.matrix.supports(CameraFeature.STORAGE_STATUS))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_BROWSER))
        assertTrue(capabilities.matrix.supports(CameraFeature.MEDIA_DOWNLOAD))
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
        assertEquals("IMG_0042.JPG", media.single().name)
        assertEquals("2026-07-21 14:30:25", media.single().captureTime)
        assertArrayEquals(OBJECT_BYTES, output.toByteArray())
        assertEquals(OBJECT_BYTES.size.toLong(), download.bytesTransferred)
        assertEquals(OBJECT_BYTES.size.toLong(), progress.last().bytesTransferred)
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
    fun canonEosVendorOperationsProvideCaptureFocusAndJpegLiveViewWhenAdvertised() = runTest {
        val transport = CanonEosScriptedTransport()
        val backend = UsbPtpCameraBackend(
            connection = CameraConnection.AndroidUsbPtp("usb-r6m3"),
            transportFactory = PtpTransportFactory { transport },
        )

        backend.initialize()
        val capabilities = backend.capabilities()
        val recordingStatus = backend.startRecording()
        val stoppedRecordingStatus = backend.stopRecording()
        val exposureStatus = backend.setExposure(iso = "800", shutter = "1/50", aperture = "4")
        val whiteBalanceStatus = backend.setWhiteBalance("Daylight")
        backend.startLiveView(LiveViewRequest(fps = 30, size = LiveViewSize.MEDIUM))
        val frame = backend.liveViewFrame(cacheKey = 27)
        backend.halfPressShutter()
        val focusDrive = backend.driveFocus(FocusDriveDirection.FAR, FocusDriveStep.MEDIUM)
        backend.captureStill()
        backend.stopLiveView()
        backend.close()

        assertTrue(capabilities.matrix.supports(CameraFeature.STILL_CAPTURE))
        assertTrue(capabilities.matrix.supports(CameraFeature.SHUTTER_HALF_PRESS))
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW))
        assertTrue(capabilities.matrix.supports(CameraFeature.LIVE_VIEW_JPEG_POLLING))
        assertTrue(capabilities.matrix.supports(CameraFeature.FOCUS_DRIVE))
        assertTrue(capabilities.matrix.supports(CameraFeature.EXPOSURE_CONTROL))
        assertTrue(capabilities.matrix.supports(CameraFeature.WHITE_BALANCE_CONTROL))
        assertFalse(capabilities.matrix.supports(CameraFeature.TAP_FOCUS))
        assertTrue(capabilities.matrix.supports(CameraFeature.VIDEO_RECORDING))
        assertTrue("movierecordtarget" in capabilities.evidence.writableSettings)
        assertEquals(listOf("100", "400", "800"), capabilities.iso)
        assertEquals(listOf("1/30", "1/50"), capabilities.shutter)
        assertEquals(listOf("2.8", "4"), capabilities.aperture)
        assertEquals(listOf("Auto", "Daylight", "Shadow"), capabilities.whiteBalance)
        assertEquals("800", exposureStatus.exposure.iso)
        assertEquals("1/50", exposureStatus.exposure.shutter)
        assertEquals("4", exposureStatus.exposure.aperture)
        assertEquals("Daylight", whiteBalanceStatus.exposure.whiteBalance)
        assertEquals(true, recordingStatus.recording)
        assertEquals(false, stoppedRecordingStatus.recording)
        assertTrue(whiteBalanceStatus.rawTransportJson.contains("\"canonVendorProperties\""))
        assertEquals(listOf(LiveViewSource.USB_PTP_PREVIEW), capabilities.liveView.sources)
        assertEquals(30, capabilities.liveView.maxFps)
        assertArrayEquals(CANON_LIVE_VIEW_JPEG, frame.bytes)
        assertEquals("image/jpeg", frame.contentType)
        assertEquals("ptp-usb://canon-eos/viewfinder?frame=27", frame.sourceUrl)
        assertEquals(FocusDriveDirection.FAR, focusDrive.direction)
        assertEquals(FocusDriveStep.MEDIUM, focusDrive.step)
        assertTrue(focusDrive.ok)

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

    private class ScriptedTransport(
        private val advertiseCapture: Boolean,
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
                    incoming += data(container.code, transaction, deviceInfoPayload(advertiseCapture, advertiseProperties = true))
                    incoming += ok(transaction)
                }

                PtpOperationCode.OPEN_SESSION,
                PtpOperationCode.CLOSE_SESSION,
                PtpOperationCode.INITIATE_CAPTURE,
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
                    incoming += data(container.code, transaction, objectInfoPayload())
                    incoming += ok(transaction)
                }

                PtpOperationCode.GET_OBJECT -> {
                    incoming += data(container.code, transaction, OBJECT_BYTES)
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
        private val advertiseMovieRecording: Boolean = true,
        private val rejectMovieRecording: Boolean = false,
    ) : PtpTransport {
        private val incoming = ArrayDeque<PtpContainer>()
        val sentContainers = mutableListOf<PtpContainer>()
        var closed = false
        private var pendingPropertyWrite = false
        private var captureEventPending = false
        private var initialPropertyEventsPending = true
        private var moviePropertyEventPending: Int? = null

        override suspend fun send(container: PtpContainer) {
            sentContainers += container
            val transaction = container.transactionId
            when (container.code) {
                PtpOperationCode.GET_DEVICE_INFO -> {
                    incoming += data(container.code, transaction, canonDeviceInfoPayload())
                    incoming += ok(transaction)
                }

                PtpOperationCode.OPEN_SESSION,
                PtpOperationCode.CLOSE_SESSION,
                CanonEosOperationCode.SET_REMOTE_MODE,
                CanonEosOperationCode.SET_EVENT_MODE,
                CanonEosOperationCode.REMOTE_RELEASE_OFF,
                CanonEosOperationCode.DRIVE_LENS,
                -> incoming += ok(transaction)

                CanonEosOperationCode.REMOTE_RELEASE_ON -> {
                    if (container.parameters().firstOrNull() == 2L) captureEventPending = true
                    incoming += ok(transaction)
                }

                CanonEosOperationCode.GET_EVENT -> {
                    val moviePropertyValue = moviePropertyEventPending
                    val payload = when {
                        initialPropertyEventsPending -> canonPropertyEvents(advertiseMovieRecording).also {
                            initialPropertyEventsPending = false
                        }
                        moviePropertyValue != null ->
                            (eosPropertyValue(CanonEosPropertyCode.EVF_RECORD_STATUS, moviePropertyValue) +
                                eosBlock(0, byteArrayOf())).also {
                                moviePropertyEventPending = null
                            }
                        !captureEventPending -> eosBlock(0, byteArrayOf())
                        malformedCaptureEvent -> byteArrayOf(40, 0, 0, 0, 0x81.toByte(), 0xC1.toByte(), 0, 0)
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
                        if (propertyCode == CanonEosPropertyCode.EVF_RECORD_STATUS && rejectMovieRecording) {
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

                else -> error("Unexpected Canon EOS operation 0x${container.code.toString(16)}")
            }
        }

        override suspend fun receive(maxPayloadBytes: Int): PtpContainer =
            incoming.removeFirstOrNull() ?: error("No Canon EOS scripted response is queued.")

        override fun close() {
            closed = true
        }

        private fun data(operation: Int, transaction: Long, payload: ByteArray) =
            PtpContainer(PtpContainerType.DATA, operation, transaction, payload)

        private fun ok(transaction: Long) =
            PtpContainer(PtpContainerType.RESPONSE, PtpResponseCode.OK, transaction)

        private fun response(code: Int, transaction: Long) =
            PtpContainer(PtpContainerType.RESPONSE, code, transaction)
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

    private fun canonPropertyEvents(advertiseMovieRecording: Boolean): ByteArray {
        var payload = eosPropertyValue(CanonEosPropertyCode.ISO_SPEED, 0x58) +
            eosAvailableValues(CanonEosPropertyCode.ISO_SPEED, 0x48, 0x58, 0x60) +
            eosPropertyValue(CanonEosPropertyCode.SHUTTER_SPEED, 0x60) +
            eosAvailableValues(CanonEosPropertyCode.SHUTTER_SPEED, 0x60, 0x65) +
            eosPropertyValue(CanonEosPropertyCode.APERTURE, 0x20) +
            eosAvailableValues(CanonEosPropertyCode.APERTURE, 0x20, 0x28) +
            eosPropertyValue(CanonEosPropertyCode.WHITE_BALANCE, 0) +
            eosAvailableValues(CanonEosPropertyCode.WHITE_BALANCE, 0, 1, 8)
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
        private val OBJECT_BYTES = byteArrayOf(1, 3, 3, 7, 9)
        private val CANON_LIVE_VIEW_JPEG = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte(),
        )

        private fun canonDeviceInfoPayload(): ByteArray = Writer().apply {
            u16(100)
            u32(CanonEosPtp.VENDOR_EXTENSION_ID)
            u16(100)
            string("")
            u16(0)
            u16Array(
                listOf(
                    PtpOperationCode.GET_DEVICE_INFO,
                    PtpOperationCode.OPEN_SESSION,
                    PtpOperationCode.CLOSE_SESSION,
                    PtpOperationCode.GET_STORAGE_IDS,
                    PtpOperationCode.GET_STORAGE_INFO,
                    CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
                    CanonEosOperationCode.SET_REMOTE_MODE,
                    CanonEosOperationCode.SET_EVENT_MODE,
                    CanonEosOperationCode.GET_EVENT,
                    CanonEosOperationCode.REMOTE_RELEASE_ON,
                    CanonEosOperationCode.REMOTE_RELEASE_OFF,
                    CanonEosOperationCode.GET_VIEWFINDER_DATA,
                    CanonEosOperationCode.DRIVE_LENS,
                    CanonEosOperationCode.TOUCH_AF_POSITION,
                )
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

        private fun deviceInfoPayload(advertiseCapture: Boolean, advertiseProperties: Boolean): ByteArray = Writer().apply {
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

        private fun objectInfoPayload(): ByteArray = Writer().apply {
            u32(STORAGE_ID)
            u16(PtpObjectFormat.EXIF_JPEG)
            u16(0)
            u32(OBJECT_BYTES.size)
            u16(PtpObjectFormat.EXIF_JPEG)
            u32(0)
            u32(0)
            u32(0)
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
