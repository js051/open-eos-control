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
    fun textMetadataPayloadMatchesLibgphoto2SetDevicePropValueExLayout() {
        assertArrayEquals(
            byteArrayOf(
                0x13, 0x00, 0x00, 0x00,
                0x15, 0xD1.toByte(), 0x00, 0x00,
                'T'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(),
                ' '.code.toByte(), 'O'.code.toByte(), 'W'.code.toByte(), 'N'.code.toByte(),
                'E'.code.toByte(), 'R'.code.toByte(), 0x00,
            ),
            CanonEosPtp.textPropertyPayload(CanonEosPropertyCode.OWNER, "TEST OWNER"),
        )
        assertArrayEquals(
            byteArrayOf(
                0x09, 0x00, 0x00, 0x00,
                0xD0.toByte(), 0xD1.toByte(), 0x00, 0x00,
                0x00,
            ),
            CanonEosPtp.textPropertyPayload(CanonEosPropertyCode.ARTIST, ""),
        )
        assertEquals(
            listOf("ownername", "artist", "copyright", "nickname"),
            CanonEosPtp.textSettingSpecs.map(CanonEosTextSettingSpec::key),
        )
    }

    @Test
    fun textMetadataRejectsUnsupportedNonPrintableNonAsciiAndOversizedValues() {
        assertFalse(CanonEosPtp.validTextMetadata("line\nbreak"))
        assertFalse(CanonEosPtp.validTextMetadata("測試"))
        assertFalse(CanonEosPtp.validTextMetadata("A".repeat(256)))
        assertTrue(CanonEosPtp.validTextMetadata(""))
        assertTrue(CanonEosPtp.validTextMetadata("A".repeat(255)))
        assertTrue(
            runCatching {
                CanonEosPtp.textPropertyPayload(CanonEosPropertyCode.ISO_SPEED, "TEST")
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                CanonEosPtp.textPropertyPayload(CanonEosPropertyCode.OWNER, "line\nbreak")
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun viewfinderParserExtractsDocumentedTypeOneAndElevenJpegBlocks() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        val metadata = block(type = 2, bytes = byteArrayOf(7, 8, 9))

        assertArrayEquals(jpeg, CanonEosPtp.liveViewJpeg(metadata + block(type = 1, bytes = jpeg)))
        assertArrayEquals(jpeg, CanonEosPtp.liveViewJpeg(block(type = 11, bytes = jpeg)))
    }

    @Test
    fun viewfinderParserRetainsJpegAndSensorGeometryRegardlessOfBlockOrder() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        val payload = block(type = 2, bytes = byteArrayOf(7, 8, 9)) +
            block(type = 1, bytes = jpeg) +
            block(type = 0x0E, bytes = u32Fields(6_000, 4_000))

        val parsed = CanonEosPtp.liveViewData(payload)

        assertArrayEquals(jpeg, parsed.jpeg)
        assertEquals(CanonEosLiveViewGeometry(width = 6_000, height = 4_000), parsed.geometry)
    }

    @Test
    fun viewfinderParserIgnoresIncompleteOrInvalidSensorGeometry() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        val incomplete = CanonEosPtp.liveViewData(
            block(type = 0x0E, bytes = u32Fields(6_000)) + block(type = 1, bytes = jpeg)
        )
        val invalid = CanonEosPtp.liveViewData(
            block(type = 0x0E, bytes = u32Fields(0, 4_000)) + block(type = 1, bytes = jpeg)
        )

        assertEquals(null, incomplete.geometry)
        assertEquals(null, invalid.geometry)
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
    fun objectTransferParserMatchesPinnedCanonEosEventOffsets() {
        val request = objectTransferBlock(
            eventCode = CanonEosEventCode.REQUEST_OBJECT_TRANSFER_64,
            handle = 0xA1B2C3D4L,
            objectFormat = PtpObjectFormat.CANON_CR3,
            sizeBytes = 0x0123_4567L,
            filename = "IMG_0042.CR3",
        )

        val parsed = CanonEosPtp.objectTransferRequests(request + block(type = 0, bytes = byteArrayOf())).single()

        assertEquals(CanonEosEventCode.REQUEST_OBJECT_TRANSFER_64, parsed.eventCode)
        assertEquals(0xA1B2C3D4L, parsed.handle)
        assertEquals(PtpObjectFormat.CANON_CR3, parsed.objectFormat)
        assertEquals(0x0123_4567L, parsed.sizeBytes)
        assertEquals("IMG_0042.CR3", parsed.filename)
        assertFalse(CanonEosPtp.containsCardCapturedObjectEvent(request))
    }

    @Test
    fun objectTransfer64LfnUsesMetadataWithoutGuessingAFilename() {
        val bytes = ByteArray(29)
        putU32(bytes, 0, 0x42)
        putU16(bytes, 4, PtpObjectFormat.EXIF_JPEG)
        putU32(bytes, 12, 4096)

        val parsed = CanonEosPtp.objectTransferRequests(
            block(CanonEosEventCode.REQUEST_OBJECT_TRANSFER_64_LFN, bytes)
        ).single()

        assertEquals(0x42L, parsed.handle)
        assertEquals(4096L, parsed.sizeBytes)
        assertEquals(null, parsed.filename)
    }

    @Test(expected = PtpProtocolException::class)
    fun objectTransferParserRejectsAnUnterminatedFilename() {
        val bytes = ByteArray(24) { 1 }
        putU32(bytes, 0, 0x42)
        putU16(bytes, 4, PtpObjectFormat.EXIF_JPEG)
        putU32(bytes, 12, 4)
        CanonEosPtp.objectTransferRequests(block(CanonEosEventCode.REQUEST_OBJECT_TRANSFER, bytes))
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

    @Test
    fun eosTextMetadataEventsExposeOnlyNulTerminatedPrintableAscii() {
        val owner = block(
            type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.OWNER) + "TEST OWNER".encodeToByteArray() + byteArrayOf(0),
        )
        val emptyCopyright = block(
            type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.COPYRIGHT) + byteArrayOf(0),
        )

        val updates = CanonEosPtp.propertyUpdates(owner + emptyCopyright + block(0, byteArrayOf()))

        assertEquals("TEST OWNER", updates.single { it.propertyCode == CanonEosPropertyCode.OWNER }.currentText)
        assertEquals("", updates.single { it.propertyCode == CanonEosPropertyCode.COPYRIGHT }.currentText)
        assertEquals("ownername", CanonEosPtp.settingKey(CanonEosPropertyCode.OWNER))
        assertEquals("nickname", CanonEosPtp.settingKey(CanonEosPropertyCode.CAMERA_NICKNAME))
    }

    @Test
    fun eosTextMetadataEventsRejectMissingNulNonPrintableAndOversizedValues() {
        val invalidPayloads = listOf(
            "NO NUL".encodeToByteArray(),
            byteArrayOf('A'.code.toByte(), '\n'.code.toByte(), 0),
            "A".repeat(256).encodeToByteArray() + byteArrayOf(0),
        )

        invalidPayloads.forEach { value ->
            val event = block(
                type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
                bytes = u32Fields(CanonEosPropertyCode.OWNER) + value,
            )
            assertTrue(runCatching { CanonEosPtp.propertyUpdates(event) }.exceptionOrNull() is PtpProtocolException)
        }
    }

    @Test
    fun canonClockEventsAndWritesUseTheLibgphoto2Uint32Layout() {
        val epochSeconds = 0xF123_4567L
        val payload = block(
            type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.UTC_TIME, epochSeconds.toInt()),
        ) + block(type = 0, bytes = byteArrayOf())

        assertEquals(epochSeconds, CanonEosPtp.propertyUpdates(payload).single().currentValue)
        assertEquals(4, CanonEosPtp.propertyValueBytes(CanonEosPropertyCode.UTC_TIME))
        assertEquals(4, CanonEosPtp.propertyValueBytes(CanonEosPropertyCode.CAMERA_TIME))
        assertArrayEquals(
            CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.UTC_TIME, epochSeconds),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.UTC_TIME, epochSeconds),
        )
    }

    @Test
    fun captureDestinationUsesCameraAdvertisedNonHostTarget() {
        val payload = block(
            type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
            bytes = u32Fields(
                CanonEosPropertyCode.CAPTURE_DESTINATION,
                CanonEosPtp.CAPTURE_DESTINATION_HOST.toInt(),
            ),
        ) + block(
            type = CanonEosEventCode.AVAILABLE_LIST_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.CAPTURE_DESTINATION, 3, 2, 4, 2),
        ) + block(type = 0, bytes = byteArrayOf())

        val updates = CanonEosPtp.propertyUpdates(payload)
        val currentValue = updates.single { it.currentValue != null }.currentValue
        val availableValues = updates.single { it.availableValues != null }.availableValues.orEmpty()

        assertEquals(CanonEosPtp.CAPTURE_DESTINATION_HOST, currentValue)
        assertEquals(2L, CanonEosPtp.captureDestinationCardValue(availableValues))
        assertEquals(null, CanonEosPtp.captureDestinationCardValue(listOf(4L)))
        assertEquals("Internal RAM", CanonEosPtp.propertyLabel(CanonEosPropertyCode.CAPTURE_DESTINATION, 4L))
        assertEquals("Memory card", CanonEosPtp.propertyLabel(CanonEosPropertyCode.CAPTURE_DESTINATION, 2L))
        assertArrayEquals(
            CanonEosPtp.uint32PropertyPayload(CanonEosPropertyCode.CAPTURE_DESTINATION, 2L),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.CAPTURE_DESTINATION, 2L),
        )
    }

    @Test
    fun currentStorageUsesUint32AndOnlyBuildsOptionsFromWritableCameraStorages() {
        val payload = block(
            type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.CURRENT_STORAGE, 0x00010001),
        ) + block(type = 0, bytes = byteArrayOf())
        val storages = listOf(
            storage(0x00010001, description = "CFe"),
            storage(0x00020001, description = "SD"),
            storage(0x00030001, description = "Read only", accessCapability = 1),
        )

        assertEquals(0x00010001L, CanonEosPtp.propertyUpdates(payload).single().currentValue)
        assertEquals(
            listOf(
                CanonEosPropertyOption(0x00010001, "CFe"),
                CanonEosPropertyOption(0x00020001, "SD"),
            ),
            CanonEosPtp.storageTargetOptions(storages),
        )
        assertEquals("capturestorage", CanonEosPtp.settingKey(CanonEosPropertyCode.CURRENT_STORAGE))
        assertArrayEquals(
            byteArrayOf(
                0x0C, 0x00, 0x00, 0x00,
                0x1E, 0xD1.toByte(), 0x00, 0x00,
                0x01, 0x00, 0x02, 0x00,
            ),
            CanonEosPtp.propertyPayload(CanonEosPropertyCode.CURRENT_STORAGE, 0x00020001),
        )
    }

    @Test
    fun storageOptionsUseUniqueVolumeLabelsThenStableCardFallbacks() {
        assertEquals(
            listOf("CFE_CARD", "SD_CARD"),
            CanonEosPtp.storageTargetOptions(
                listOf(
                    storage(1, description = "Removable", volumeLabel = "CFE_CARD"),
                    storage(2, description = "Removable", volumeLabel = "SD_CARD"),
                )
            ).map(CanonEosPropertyOption::label),
        )
        assertEquals(
            listOf("Card 1", "Card 2"),
            CanonEosPtp.storageTargetOptions(
                listOf(storage(1, description = "Removable"), storage(2, description = "Removable"))
            ).map(CanonEosPropertyOption::label),
        )
    }

    @Test
    fun availableShotsUsesCanonUint32ValueAndRejectsUnknownSentinel() {
        val payload = block(
            type = CanonEosEventCode.PROPERTY_VALUE_CHANGED,
            bytes = u32Fields(CanonEosPropertyCode.AVAILABLE_SHOTS, 46_822),
        ) + block(type = 0, bytes = byteArrayOf())

        val currentValue = CanonEosPtp.propertyUpdates(payload).single().currentValue

        assertEquals(46_822L, CanonEosPtp.availableShots(currentValue))
        assertEquals("46822", CanonEosPtp.propertyLabel(CanonEosPropertyCode.AVAILABLE_SHOTS, 46_822L))
        assertEquals(0L, CanonEosPtp.availableShots(0L))
        assertEquals(null, CanonEosPtp.availableShots(0xFFFF_FFFFL))
        assertEquals(null, CanonEosPtp.availableShots(-1L))
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
                "alomode",
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
        assertEquals("capturetarget", CanonEosPtp.settingKey(CanonEosPropertyCode.CAPTURE_DESTINATION))
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
    fun r6MarkIIIAutoLightingOptimizerUsesPinned32BitSafeValues() {
        val code = CanonEosPropertyCode.AUTO_LIGHTING_OPTIMIZER
        assertEquals(0xD1C1, code)
        assertEquals("Standard", CanonEosPtp.propertyLabel(code, 0x00010000))
        assertEquals("Low", CanonEosPtp.propertyLabel(code, 0x00010101))
        assertEquals("High", CanonEosPtp.propertyLabel(code, 0x00010202))
        assertEquals("Off", CanonEosPtp.propertyLabel(code, 0x00010303))
        assertEquals(
            "High (disabled in manual exposure)",
            CanonEosPtp.propertyLabel(code, 0x00000202),
        )
        assertEquals("x3", CanonEosPtp.propertyLabel(code, 3))
        assertEquals(
            listOf("Standard", "High", "x3"),
            CanonEosPtp.propertyOptions(code, listOf(0x00010000, 0x00010202, 3, 0x76543210))
                .map(CanonEosPropertyOption::label),
        )
        assertArrayEquals(
            CanonEosPtp.uint32PropertyPayload(code, 0x00010202),
            CanonEosPtp.propertyPayload(code, 0x00010202),
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
    fun movieModeSwitchRequiresBothOperationsAndAReadableBinaryState() {
        val operations = setOf(
            CanonEosOperationCode.SET_REMOTE_MODE,
            CanonEosOperationCode.SET_EVENT_MODE,
            CanonEosOperationCode.GET_EVENT,
            CanonEosOperationCode.MOVIE_SELECT_SWITCH_ON,
            CanonEosOperationCode.MOVIE_SELECT_SWITCH_OFF,
        )
        val complete = deviceInfo(operations)

        assertTrue(CanonEosPtp.supportsMovieModeSwitch(complete, 0L))
        assertTrue(CanonEosPtp.supportsMovieModeSwitch(complete, 1L))
        assertFalse(CanonEosPtp.supportsMovieModeSwitch(complete, 2L))
        assertFalse(
            CanonEosPtp.supportsMovieModeSwitch(
                deviceInfo(operations - CanonEosOperationCode.MOVIE_SELECT_SWITCH_OFF),
                0L,
            )
        )
    }

    @Test
    fun capabilitiesRequireCanonVendorAndCompleteAdvertisedSequences() {
        val operations = setOf(
            CanonEosOperationCode.SET_REMOTE_MODE,
            CanonEosOperationCode.SET_EVENT_MODE,
            CanonEosOperationCode.GET_EVENT,
            CanonEosOperationCode.REMOTE_RELEASE_ON,
            CanonEosOperationCode.REMOTE_RELEASE_OFF,
            CanonEosOperationCode.DO_AF,
            CanonEosOperationCode.AF_CANCEL,
            CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX,
            CanonEosOperationCode.GET_VIEWFINDER_DATA,
            CanonEosOperationCode.DRIVE_LENS,
            CanonEosOperationCode.CLICK_WHITE_BALANCE,
            CanonEosOperationCode.TOUCH_AF_POSITION,
        )
        val complete = deviceInfo(operations)

        assertTrue(CanonEosPtp.supportsRemoteRelease(complete))
        assertTrue(CanonEosPtp.supportsAutofocus(complete))
        assertTrue(CanonEosPtp.supportsLiveView(complete))
        assertTrue(CanonEosPtp.supportsFocusDrive(complete))
        assertTrue(CanonEosPtp.supportsTouchAutofocus(complete))
        assertTrue(CanonEosPtp.supportsClickWhiteBalance(complete))
        assertTrue(CanonEosPtp.supportsPropertyControl(complete))
        assertFalse(
            CanonEosPtp.supportsRemoteRelease(
                deviceInfo(operations - CanonEosOperationCode.REMOTE_RELEASE_OFF)
            )
        )
        assertFalse(
            CanonEosPtp.supportsAutofocus(
                deviceInfo(operations - CanonEosOperationCode.AF_CANCEL)
            )
        )
        assertFalse(
            CanonEosPtp.supportsTouchAutofocus(
                deviceInfo(operations - CanonEosOperationCode.TOUCH_AF_POSITION)
            )
        )
        assertFalse(
            CanonEosPtp.supportsClickWhiteBalance(
                deviceInfo(operations - CanonEosOperationCode.CLICK_WHITE_BALANCE)
            )
        )
        assertFalse(
            CanonEosPtp.supportsTouchAutofocus(
                deviceInfo(
                    operations - CanonEosOperationCode.AF_CANCEL - CanonEosOperationCode.REMOTE_RELEASE_OFF
                )
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

    private fun objectTransferBlock(
        eventCode: Int,
        handle: Long,
        objectFormat: Int,
        sizeBytes: Long,
        filename: String,
    ): ByteArray {
        val name = filename.encodeToByteArray() + byteArrayOf(0)
        return block(eventCode, ByteArray(20 + name.size).also { bytes ->
            putU32(bytes, 0, handle.toInt())
            putU16(bytes, 4, objectFormat)
            putU32(bytes, 12, sizeBytes.toInt())
            name.copyInto(bytes, destinationOffset = 20)
        })
    }

    private fun imageFormatData(propertyCode: Int, value: Int): ByteArray =
        CanonEosPtp.propertyPayload(propertyCode, value.toLong()).let { it.copyOfRange(8, it.size) }

    private fun putU32(destination: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> destination[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun putU16(destination: ByteArray, offset: Int, value: Int) {
        repeat(2) { index -> destination[offset + index] = (value ushr (index * 8)).toByte() }
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

    private fun storage(
        id: Long,
        description: String,
        volumeLabel: String = "",
        accessCapability: Int = 0,
    ) = PtpStorageInfo(
        storageId = id,
        storageType = 3,
        filesystemType = 2,
        accessCapability = accessCapability,
        maxCapacityBytes = 1UL,
        freeSpaceBytes = 1UL,
        freeSpaceImages = 1,
        description = description,
        volumeLabel = volumeLabel,
    )
}
