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
    fun vendorSettingMappingsAndPayloadWidthsMatchPinnedLibgphoto2Tables() {
        assertEquals(
            listOf(
                "shootingmode",
                "exposurecompensation",
                "colortemperature",
                "whitebalanceadjusta",
                "whitebalanceadjustb",
                "colorspace",
                "aspectratio",
                "zoomspeed",
                "autopoweroff",
                "afoperation",
                "continuousaf",
                "afmethod",
                "drivemode",
                "meteringmode",
                "highisonr",
                "aeb",
                "picturestyle",
                "stillimagequality",
                "stillimagequalitysd",
                "stillimagequalitycf",
                "movieservoaf",
            ),
            CanonEosPtp.settingSpecs.map(CanonEosSettingSpec::key),
        )
        assertEquals("AI Servo", CanonEosPtp.propertyLabel(CanonEosPropertyCode.FOCUS_MODE, 1))
        assertEquals("WholeAreaAF", CanonEosPtp.propertyLabel(CanonEosPropertyCode.LIVE_VIEW_AF_SYSTEM, 14))
        assertEquals("Continuous high speed", CanonEosPtp.propertyLabel(CanonEosPropertyCode.DRIVE_MODE, 4))
        assertEquals("Evaluative", CanonEosPtp.propertyLabel(CanonEosPropertyCode.METERING_MODE, 3))
        assertEquals("Fine detail", CanonEosPtp.propertyLabel(CanonEosPropertyCode.PICTURE_STYLE, 0x88))
        assertEquals("On", CanonEosPtp.propertyLabel(CanonEosPropertyCode.MOVIE_SERVO_AF, 1))
        assertEquals("0x00000012", CanonEosPtp.propertyLabel(CanonEosPropertyCode.LIVE_VIEW_AF_SYSTEM, 0x12))

        assertArrayEquals(
            CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.DRIVE_MODE, 4),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.DRIVE_MODE, 4),
        )
        assertArrayEquals(
            CanonEosPtp.uint8PropertyPayload(CanonEosPropertyCode.METERING_MODE, 1),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.METERING_MODE, 1),
        )
        assertArrayEquals(
            CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.FOCUS_MODE, 2),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.FOCUS_MODE, 2),
        )
        assertArrayEquals(
            CanonEosPtp.uint8PropertyPayload(CanonEosPropertyCode.PICTURE_STYLE, 0x87),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.PICTURE_STYLE, 0x87),
        )
        assertArrayEquals(
            CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.CONTINUOUS_AF_MODE, 1),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.CONTINUOUS_AF_MODE, 1),
        )
    }

    @Test
    fun r6MarkIIIExposureAndColorMappingsMatchPinnedLibgphoto2Tables() {
        assertEquals(0xD104, CanonEosPropertyCode.EXPOSURE_COMPENSATION)
        assertEquals(0xD10A, CanonEosPropertyCode.COLOR_TEMPERATURE)
        assertEquals(0xD10B, CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A)
        assertEquals(0xD10C, CanonEosPropertyCode.WHITE_BALANCE_ADJUST_B)
        assertEquals(0xD10F, CanonEosPropertyCode.COLOR_SPACE)
        assertEquals(0xD178, CanonEosPropertyCode.HIGH_ISO_NOISE_REDUCTION)
        assertEquals(0xD1D9, CanonEosPropertyCode.AEB)
        assertEquals("-3", CanonEosPtp.propertyLabel(CanonEosPropertyCode.EXPOSURE_COMPENSATION, 0xE8))
        assertEquals("1.3", CanonEosPtp.propertyLabel(CanonEosPropertyCode.EXPOSURE_COMPENSATION, 0x0B))
        assertEquals("5200", CanonEosPtp.propertyLabel(CanonEosPropertyCode.COLOR_TEMPERATURE, 5200))
        assertEquals("-9", CanonEosPtp.propertyLabel(CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A, -9))
        assertEquals("AdobeRGB", CanonEosPtp.propertyLabel(CanonEosPropertyCode.COLOR_SPACE, 2))
        assertEquals("High", CanonEosPtp.propertyLabel(CanonEosPropertyCode.HIGH_ISO_NOISE_REDUCTION, 3))
        assertEquals("+/- 2", CanonEosPtp.propertyLabel(CanonEosPropertyCode.AEB, 0x10))

        assertArrayEquals(
            CanonEosPtp.uint8PropertyPayload(CanonEosPropertyCode.EXPOSURE_COMPENSATION, 0x0B),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.EXPOSURE_COMPENSATION, 0x0B),
        )
        assertArrayEquals(
            CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.COLOR_TEMPERATURE, 5600),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.COLOR_TEMPERATURE, 5600),
        )
        assertArrayEquals(
            byteArrayOf(
                0x0C, 0x00, 0x00, 0x00,
                0x0B, 0xD1.toByte(), 0x00, 0x00,
                0xF7.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            ),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A, -9),
        )
        assertArrayEquals(
            CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.COLOR_SPACE, 2),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.COLOR_SPACE, 2),
        )
        assertArrayEquals(
            CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.HIGH_ISO_NOISE_REDUCTION, 3),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.HIGH_ISO_NOISE_REDUCTION, 3),
        )
        assertArrayEquals(
            CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.AEB, 0x10),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.AEB, 0x10),
        )
    }

    @Test
    fun r6MarkIIIAspectRatioAndPowerZoomMappingsMatchPinnedLibgphoto2Tables() {
        assertEquals(0xD194, CanonEosPropertyCode.MULTI_ASPECT)
        assertEquals(0xD149, CanonEosPropertyCode.POWER_ZOOM_SPEED)
        assertEquals("3:2", CanonEosPtp.propertyLabel(CanonEosPropertyCode.MULTI_ASPECT, 0))
        assertEquals("16:9", CanonEosPtp.propertyLabel(CanonEosPropertyCode.MULTI_ASPECT, 7))
        assertEquals("1.6x", CanonEosPtp.propertyLabel(CanonEosPropertyCode.MULTI_ASPECT, 0x0D))
        assertEquals("8", CanonEosPtp.propertyLabel(CanonEosPropertyCode.POWER_ZOOM_SPEED, 8))

        assertArrayEquals(
            CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.MULTI_ASPECT, 7),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.MULTI_ASPECT, 7),
        )
        assertArrayEquals(
            CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.POWER_ZOOM_SPEED, 12),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.POWER_ZOOM_SPEED, 12),
        )
    }

    @Test
    fun r6MarkIIIAutoPowerOffUsesOnlyDocumentedSelectableValues() {
        assertEquals(0xD114, CanonEosPropertyCode.AUTO_POWER_OFF)
        assertEquals("15 seconds", CanonEosPtp.propertyLabel(CanonEosPropertyCode.AUTO_POWER_OFF, 15))
        assertEquals("30 minutes", CanonEosPtp.propertyLabel(CanonEosPropertyCode.AUTO_POWER_OFF, 1800))
        assertEquals("Disable", CanonEosPtp.propertyLabel(CanonEosPropertyCode.AUTO_POWER_OFF, 0))
        assertEquals(
            listOf("15 seconds", "30 seconds", "1 minute", "3 minutes", "5 minutes", "10 minutes", "30 minutes", "Disable"),
            CanonEosPtp.propertyOptions(
                CanonEosPropertyCode.AUTO_POWER_OFF,
                listOf(15, 30, 60, 180, 300, 600, 1800, 0, 0xFFFFFFFFL),
            ).map(CanonEosPropertyOption::label),
        )
        assertArrayEquals(
            CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.AUTO_POWER_OFF, 600),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.AUTO_POWER_OFF, 600),
        )
    }

    @Test
    fun r6MarkIIIAutoExposureModeMappingMatchesPinnedLibgphoto2Table() {
        assertEquals(0xD105, CanonEosPropertyCode.AUTO_EXPOSURE_MODE)
        assertEquals("P", CanonEosPtp.propertyLabel(CanonEosPropertyCode.AUTO_EXPOSURE_MODE, 0x0000))
        assertEquals("TV", CanonEosPtp.propertyLabel(CanonEosPropertyCode.AUTO_EXPOSURE_MODE, 0x0001))
        assertEquals("AV", CanonEosPtp.propertyLabel(CanonEosPropertyCode.AUTO_EXPOSURE_MODE, 0x0002))
        assertEquals("Manual", CanonEosPtp.propertyLabel(CanonEosPropertyCode.AUTO_EXPOSURE_MODE, 0x0003))
        assertEquals("Movie", CanonEosPtp.propertyLabel(CanonEosPropertyCode.AUTO_EXPOSURE_MODE, 0x0014))
        assertEquals("Fv", CanonEosPtp.propertyLabel(CanonEosPropertyCode.AUTO_EXPOSURE_MODE, 0x0037))
        assertArrayEquals(
            CanonEosPtp.uint16PropertyPayload(CanonEosPropertyCode.AUTO_EXPOSURE_MODE, 0x0002),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.AUTO_EXPOSURE_MODE, 0x0002),
        )
    }

    @Test
    fun signedWhiteBalanceEventsPreserveNegativeCameraValues() {
        val payload = block(
            type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A, -2),
        ) + block(
            type = CanonEosEventCode.AVAILABLE_LIST_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A, 3, 3, -9, 0, 9),
        ) + block(type = 0, bytes = byteArrayOf())

        val updates = CanonEosPtp.propertyUpdates(payload)
        val values = updates.single { it.availableValues != null }.availableValues.orEmpty()

        assertEquals(-2L, updates.single { it.currentValue != null }.currentValue)
        assertEquals(listOf(-9L, 0L, 9L), values)
        assertEquals(
            listOf("-9", "0", "9"),
            CanonEosPtp.propertyOptions(
                CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A,
                values,
            ).map(CanonEosPropertyOption::label),
        )
        assertEquals(
            -9L,
            CanonEosPtp.propertyValue(
                CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A,
                values,
                "-9",
            ),
        )
    }

    @Test
    fun vendorSettingEventsExposeOnlyTheCameraAdvertisedChoicesInOrder() {
        val payload = block(
            type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.LIVE_VIEW_AF_SYSTEM, 14),
        ) + block(
            type = CanonEosEventCode.AVAILABLE_LIST_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.LIVE_VIEW_AF_SYSTEM, 3, 4, 10, 1, 14, 0x12),
        ) + block(type = 0, bytes = byteArrayOf())

        val updates = CanonEosPtp.propertyUpdates(payload)
        val options = CanonEosPtp.propertyOptions(
            CanonEosPropertyCode.LIVE_VIEW_AF_SYSTEM,
            updates.single { it.availableValues != null }.availableValues.orEmpty(),
        )

        assertEquals(14L, updates.single { it.currentValue != null }.currentValue)
        assertEquals(listOf("LiveSpotAF", "Live", "WholeAreaAF", "0x00000012"), options.map { it.label })
    }

    @Test
    fun imageFormatEventsAndWriterMatchThePinnedVariableLengthLayout() {
        val current = imageFormatData(CanonEosPropertyCode.IMAGE_FORMAT, 0x0CFF)
        val payload = block(
            type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.IMAGE_FORMAT) + current,
        ) + block(
            type = CanonEosEventCode.AVAILABLE_LIST_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.IMAGE_FORMAT, 3, 3) +
                imageFormatData(CanonEosPropertyCode.IMAGE_FORMAT, 0x03FF) +
                imageFormatData(CanonEosPropertyCode.IMAGE_FORMAT, 0x0B03) +
                imageFormatData(CanonEosPropertyCode.IMAGE_FORMAT, 0x0CFF),
        ) + block(type = 0, bytes = byteArrayOf())

        val updates = CanonEosPtp.propertyUpdates(payload)
        val values = updates.single { it.availableValues != null }.availableValues.orEmpty()

        assertEquals(0x0CFFL, updates.single { it.currentValue != null }.currentValue)
        assertEquals(listOf(0x03FFL, 0x0B03L, 0x0CFFL), values)
        assertEquals(
            listOf("Large Fine JPEG", "cRAW + Large Fine JPEG", "RAW"),
            CanonEosPtp.propertyOptions(CanonEosPropertyCode.IMAGE_FORMAT, values).map { it.label },
        )
        assertArrayEquals(
            byteArrayOf(
                0x2C, 0x00, 0x00, 0x00,
                0x20, 0xD1.toByte(), 0x00, 0x00,
                0x02, 0x00, 0x00, 0x00,
                0x10, 0x00, 0x00, 0x00,
                0x06, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x03, 0x00, 0x00, 0x00,
                0x10, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x03, 0x00, 0x00, 0x00,
            ),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.IMAGE_FORMAT, 0x0B03),
        )
        assertEquals(28, CanonEosPtp.propertyPayload(CanonEosPropertyCode.IMAGE_FORMAT, 0x0CFF).size)
    }

    @Test(expected = PtpProtocolException::class)
    fun imageFormatEventRejectsATruncatedEntry() {
        CanonEosPtp.propertyUpdates(
            block(
                type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
                bytes = u32Fields(CanonEosPropertyCode.IMAGE_FORMAT, 2, 0x10, 6),
            )
        )
    }

    @Test(expected = PtpProtocolException::class)
    fun imageFormatEventRejectsAnInvalidEntryLength() {
        CanonEosPtp.propertyUpdates(
            block(
                type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
                bytes = u32Fields(CanonEosPropertyCode.IMAGE_FORMAT, 1, 12, 6, 0, 4),
            )
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

    private fun imageFormatData(propertyCode: Int, value: Int): ByteArray =
        CanonEosPtp.propertyPayload(propertyCode, value.toLong()).let { it.copyOfRange(8, it.size) }

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
