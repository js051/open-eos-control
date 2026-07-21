package dev.openeos.control.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonEosPtpTest {
    @Test
    fun vendorPropertyPayloadMatchesLibgphoto2SetDevicePropValueExLayout() {
        assertArrayEquals(
            byteArrayOf(
                0x0C, 0x00, 0x00, 0x00,
                0xB1.toByte(), 0xD1.toByte(), 0x00, 0x00,
                0x01, 0x00, 0x00, 0x00,
            ),
            CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.EVF_MODE, 1),
        )
        assertArrayEquals(
            byteArrayOf(
                0x0C, 0x00, 0x00, 0x00,
                0xB0.toByte(), 0xD1.toByte(), 0x00, 0x00,
                0x02, 0x00, 0x00, 0x00,
            ),
            CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.EVF_OUTPUT_DEVICE, 2),
        )
        assertArrayEquals(
            byteArrayOf(
                0x0C, 0x00, 0x00, 0x00,
                0xB8.toByte(), 0xD1.toByte(), 0x00, 0x00,
                0x04, 0x00, 0x00, 0x00,
            ),
            CanonEosPtp.uint16PropertyPayload(
                CanonEosPropertyCode.EVF_RECORD_STATUS,
                CanonEosPtp.MOVIE_RECORD_TARGET_CARD.toInt(),
            ),
        )
    }

    @Test
    fun viewfinderParserExtractsDocumentedTypeOneAndElevenJpegBlocks() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        val metadata = block(type = 2, bytes = byteArrayOf(7, 8, 9))

        assertArrayEquals(jpeg, CanonEosPtp.liveViewJpeg(metadata + block(type = 1, bytes = jpeg)))
        assertArrayEquals(jpeg, CanonEosPtp.liveViewJpeg(block(type = 11, bytes = jpeg)))
    }

    @Test(expected = PtpProtocolException::class)
    fun viewfinderParserRejectsMalformedBlockLength() {
        CanonEosPtp.liveViewJpeg(byteArrayOf(30, 0, 0, 0, 1, 0, 0, 0, 0xFF.toByte(), 0xD8.toByte()))
    }

    @Test
    fun eosEventParserRecognizesOnlyCapturedObjectEvents() {
        val propertyEvent = block(type = 0xC189, bytes = byteArrayOf(1, 2, 3, 4))
        val objectEvent = block(type = CanonEosEventCode.OBJECT_ADDED_EX, bytes = ByteArray(40))
        val terminator = block(type = 0, bytes = byteArrayOf())

        assertFalse(CanonEosPtp.containsCapturedObjectEvent(propertyEvent + terminator))
        assertTrue(CanonEosPtp.containsCapturedObjectEvent(propertyEvent + objectEvent + terminator))
    }

    @Test
    fun eosPropertyEventsExposeCurrentValueAndCameraAdvertisedChoices() {
        val payload = block(
            type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.ISO_SPEED, 0x58),
        ) + block(
            type = CanonEosEventCode.AVAILABLE_LIST_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.ISO_SPEED, 3, 3, 0x48, 0x58, 0x60),
        ) + block(type = 0, bytes = byteArrayOf())

        val updates = CanonEosPtp.propertyUpdates(payload)
        val options = CanonEosPtp.propertyOptions(
            CanonEosPropertyCode.ISO_SPEED,
            updates.single { it.availableValues != null }.availableValues.orEmpty(),
        )

        assertEquals(0x58L, updates.single { it.currentValue != null }.currentValue)
        assertEquals(listOf("100", "400", "800"), options.map(CanonEosPropertyOption::label))
        assertEquals(0x60L, CanonEosPtp.propertyValue(CanonEosPropertyCode.ISO_SPEED, options.map { it.value }, "800"))
    }

    @Test(expected = PtpProtocolException::class)
    fun eosPropertyParserRejectsTruncatedAvailableValueList() {
        CanonEosPtp.propertyUpdates(
            block(
                type = CanonEosEventCode.AVAILABLE_LIST_CHANGED,
                bytes = u32Fields(CanonEosPropertyCode.APERTURE, 3, 2, 0x20),
            )
        )
    }

    @Test
    fun corePropertyPayloadUsesTheCanonDataWidth() {
        assertArrayEquals(
            CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.SHUTTER_SPEED, 0x65),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.SHUTTER_SPEED, 0x65),
        )
        assertArrayEquals(
            byteArrayOf(
                0x0C, 0x00, 0x00, 0x00,
                0x09, 0xD1.toByte(), 0x00, 0x00,
                0x01, 0x00, 0x00, 0x00,
            ),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.WHITE_BALANCE, 1),
        )
    }

    @Test
    fun movieRecordingRequiresCameraAdvertisedCardAndNoneTargets() {
        val info = deviceInfo(
            setOf(
                CanonEosOperationCode.SET_REMOTE_MODE,
                CanonEosOperationCode.SET_EVENT_MODE,
                CanonEosOperationCode.GET_EVENT,
                CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
            )
        )

        assertTrue(
            CanonEosPtp.supportsMovieRecording(
                info,
                listOf(
                    CanonEosPtp.MOVIE_RECORD_TARGET_CARD,
                    CanonEosPtp.MOVIE_RECORD_TARGET_NONE,
                    CanonEosPtp.MOVIE_RECORD_TARGET_SDRAM,
                ),
            )
        )
        assertFalse(
            CanonEosPtp.supportsMovieRecording(
                info,
                listOf(CanonEosPtp.MOVIE_RECORD_TARGET_CARD),
            )
        )
        assertEquals(true, CanonEosPtp.movieRecording(CanonEosPtp.MOVIE_RECORD_TARGET_CARD))
        assertEquals(false, CanonEosPtp.movieRecording(CanonEosPtp.MOVIE_RECORD_TARGET_NONE))
        assertEquals(false, CanonEosPtp.movieRecording(CanonEosPtp.MOVIE_RECORD_TARGET_SDRAM))
        assertEquals(null, CanonEosPtp.movieRecording(9L))
    }

    @Test
    fun capabilitiesRequireCanonVendorAndCompleteAdvertisedSequences() {
        val operations = setOf(
            CanonEosOperationCode.SET_REMOTE_MODE,
            CanonEosOperationCode.SET_EVENT_MODE,
            CanonEosOperationCode.GET_EVENT,
            CanonEosOperationCode.REMOTE_RELEASE_ON,
            CanonEosOperationCode.REMOTE_RELEASE_OFF,
            CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
            CanonEosOperationCode.GET_VIEWFINDER_DATA,
            CanonEosOperationCode.DRIVE_LENS,
        )
        val complete = deviceInfo(operations)

        assertTrue(CanonEosPtp.supportsRemoteRelease(complete))
        assertTrue(CanonEosPtp.supportsLiveView(complete))
        assertTrue(CanonEosPtp.supportsFocusDrive(complete))
        assertTrue(CanonEosPtp.supportsPropertyControl(complete))
        assertFalse(
            CanonEosPtp.supportsRemoteRelease(
                deviceInfo(operations - CanonEosOperationCode.REMOTE_RELEASE_OFF)
            )
        )
        assertFalse(CanonEosPtp.supportsLiveView(complete.copy(vendorExtensionId = 0L)))
    }

    @Test
    fun focusDriveValuesMatchCanonNearAndFarOneToThreeEncoding() {
        assertEquals(1L, CanonEosPtp.focusDriveAmount(FocusDriveDirection.NEAR, FocusDriveStep.SMALL))
        assertEquals(3L, CanonEosPtp.focusDriveAmount(FocusDriveDirection.NEAR, FocusDriveStep.LARGE))
        assertEquals(0x8002L, CanonEosPtp.focusDriveAmount(FocusDriveDirection.FAR, FocusDriveStep.MEDIUM))
    }

    private fun block(type: Int, bytes: ByteArray): ByteArray = ByteArray(bytes.size + 8).also { block ->
        putU32(block, 0, block.size)
        putU32(block, 4, type)
        bytes.copyInto(block, destinationOffset = 8)
    }

    private fun u32Fields(vararg values: Int): ByteArray = ByteArray(values.size * 4).also { bytes ->
        values.forEachIndexed { index, value -> putU32(bytes, index * 4, value) }
    }

    private fun putU32(destination: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> destination[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun deviceInfo(operations: Set<Int>) = PtpDeviceInfo(
        standardVersion = 100,
        vendorExtensionId = CanonEosPtp.VENDOR_EXTENSION_ID,
        vendorExtensionVersion = 100,
        vendorExtensionDescription = "",
        functionalMode = 0,
        operations = operations,
        events = emptySet(),
        deviceProperties = emptySet(),
        captureFormats = emptySet(),
        imageFormats = emptySet(),
        manufacturer = "Canon.Inc",
        model = "Canon EOS R6 Mark III",
        deviceVersion = "3-1.0.0",
        serialNumber = "test",
    )
}
