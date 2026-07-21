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
