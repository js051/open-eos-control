package dev.openeos.control.data

object CanonEosOperationCode {
    const val SET_DEVICE_PROP_VALUE_EX = 0x9110
    const val SET_REMOTE_MODE = 0x9114
    const val SET_EVENT_MODE = 0x9115
    const val GET_EVENT = 0x9116
    const val TRANSFER_COMPLETE = 0x9117
    const val PC_HDD_CAPACITY = 0x911A
    const val REMOTE_RELEASE_ON = 0x9128
    const val REMOTE_RELEASE_OFF = 0x9129
    const val MOVIE_SELECT_SWITCH_ON = 0x9133
    const val MOVIE_SELECT_SWITCH_OFF = 0x9134
    const val GET_VIEWFINDER_DATA = 0x9153
    const val DO_AF = 0x9154
    const val DRIVE_LENS = 0x9155
    const val CLICK_WHITE_BALANCE = 0x9157
    const val ZOOM = 0x9158
    const val TOUCH_AF_POSITION = 0x915B
    const val AF_CANCEL = 0x9160
}

object CanonEosPropertyCode {
    const val APERTURE = 0xD101
    const val SHUTTER_SPEED = 0xD102
    const val ISO_SPEED = 0xD103
    const val EXPOSURE_COMPENSATION = 0xD104
    const val AUTO_EXPOSURE_MODE = 0xD105
    const val DRIVE_MODE = 0xD106
    const val METERING_MODE = 0xD107
    const val FOCUS_MODE = 0xD108
    const val WHITE_BALANCE = 0xD109
    const val COLOR_TEMPERATURE = 0xD10A
    const val WHITE_BALANCE_ADJUST_A = 0xD10B
    const val WHITE_BALANCE_ADJUST_B = 0xD10C
    const val COLOR_SPACE = 0xD10F
    const val PICTURE_STYLE = 0xD110
    const val CAMERA_TIME = 0xD113
    const val AUTO_POWER_OFF = 0xD114
    const val AVAILABLE_SHOTS = 0xD11B
    const val CAPTURE_DESTINATION = 0xD11C
    const val CURRENT_STORAGE = 0xD11E
    const val IMAGE_FORMAT = 0xD120
    const val IMAGE_FORMAT_CF = 0xD121
    const val IMAGE_FORMAT_SD = 0xD122
    const val POWER_ZOOM_SPEED = 0xD149
    const val HIGH_ISO_NOISE_REDUCTION = 0xD178
    const val MOVIE_SERVO_AF = 0xD179
    const val UTC_TIME = 0xD17C
    const val MULTI_ASPECT = 0xD194
    const val EVF_OUTPUT_DEVICE = 0xD1B0
    const val EVF_MODE = 0xD1B1
    const val EVF_RECORD_STATUS = 0xD1B8
    const val LIVE_VIEW_AF_SYSTEM = 0xD1BA
    const val AUTO_LIGHTING_OPTIMIZER = 0xD1C1
    const val FIXED_MOVIE = 0xD1C2
    const val CONTINUOUS_AF_MODE = 0xD1C9
    const val AEB = 0xD1D9
}

object CanonEosEventCode {
    const val OBJECT_ADDED_EX = 0xC181
    const val REQUEST_OBJECT_TRANSFER = 0xC186
    const val PROPERTY_VALUE_CHANGED = 0xC189
    const val AVAILABLE_LIST_CHANGED = 0xC18A
    const val OBJECT_ADDED_EX_64 = 0xC1A7
    const val REQUEST_OBJECT_TRANSFER_64 = 0xC1A9
    const val OBJECT_ADDED_EX_64_LFN = 0xC1B6
    const val REQUEST_OBJECT_TRANSFER_64_LFN = 0xC1B8
}

data class CanonEosPropertyUpdate(
    val propertyCode: Int,
    val currentValue: Long? = null,
    val availableValues: List<Long>? = null,
)

data class CanonEosObjectTransferRequest(
    val eventCode: Int,
    val handle: Long,
    val objectFormat: Int,
    val sizeBytes: Long,
    val filename: String?,
)

data class CanonEosPropertyOption(
    val value: Long,
    val label: String,
)

data class CanonEosSettingSpec(
    val propertyCode: Int,
    val key: String,
    val fallbackLabel: String,
)

data class CanonEosLiveViewGeometry(
    val width: Int,
    val height: Int,
)

data class CanonEosLiveViewData(
    val jpeg: ByteArray,
    val geometry: CanonEosLiveViewGeometry?,
)

object CanonEosPtp {
    const val VENDOR_EXTENSION_ID = 0x0000000BL
    const val VIEWFINDER_REQUEST_BYTES = 0x00200000L
    const val VIEWFINDER_NOT_READY_RESPONSE = 0xA102
    const val MOVIE_RECORD_TARGET_NONE = 0L
    const val MOVIE_RECORD_TARGET_SDRAM = 3L
    const val MOVIE_RECORD_TARGET_CARD = 4L
    const val CAPTURE_DESTINATION_HOST = 4L

    private const val VIEWFINDER_JPEG_BLOCK = 0x01L
    private const val VIEWFINDER_JPEG_BLOCK_ALTERNATE = 0x0BL
    private const val VIEWFINDER_SENSOR_GEOMETRY_BLOCK = 0x0EL
    private const val MAX_VIEWFINDER_SENSOR_DIMENSION = 100_000L

    val settingSpecs = listOf(
        CanonEosSettingSpec(CanonEosPropertyCode.AUTO_EXPOSURE_MODE, "shootingmode", "Shooting mode"),
        CanonEosSettingSpec(
            CanonEosPropertyCode.EXPOSURE_COMPENSATION,
            "exposurecompensation",
            "Exposure compensation",
        ),
        CanonEosSettingSpec(CanonEosPropertyCode.COLOR_TEMPERATURE, "colortemperature", "Color temperature"),
        CanonEosSettingSpec(
            CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A,
            "whitebalanceadjusta",
            "White balance shift A",
        ),
        CanonEosSettingSpec(
            CanonEosPropertyCode.WHITE_BALANCE_ADJUST_B,
            "whitebalanceadjustb",
            "White balance shift B",
        ),
        CanonEosSettingSpec(CanonEosPropertyCode.COLOR_SPACE, "colorspace", "Color space"),
        CanonEosSettingSpec(CanonEosPropertyCode.MULTI_ASPECT, "aspectratio", "Aspect ratio"),
        CanonEosSettingSpec(CanonEosPropertyCode.POWER_ZOOM_SPEED, "zoomspeed", "Power zoom speed"),
        CanonEosSettingSpec(CanonEosPropertyCode.AUTO_POWER_OFF, "autopoweroff", "Auto power off"),
        CanonEosSettingSpec(CanonEosPropertyCode.FOCUS_MODE, "afoperation", "AF operation"),
        CanonEosSettingSpec(CanonEosPropertyCode.CONTINUOUS_AF_MODE, "continuousaf", "Continuous AF"),
        CanonEosSettingSpec(CanonEosPropertyCode.LIVE_VIEW_AF_SYSTEM, "afmethod", "AF method"),
        CanonEosSettingSpec(
            CanonEosPropertyCode.AUTO_LIGHTING_OPTIMIZER,
            "alomode",
            "Auto Lighting Optimizer",
        ),
        CanonEosSettingSpec(CanonEosPropertyCode.DRIVE_MODE, "drivemode", "Drive mode"),
        CanonEosSettingSpec(CanonEosPropertyCode.METERING_MODE, "meteringmode", "Metering mode"),
        CanonEosSettingSpec(
            CanonEosPropertyCode.HIGH_ISO_NOISE_REDUCTION,
            "highisonr",
            "High ISO noise reduction",
        ),
        CanonEosSettingSpec(CanonEosPropertyCode.AEB, "aeb", "Auto exposure bracketing"),
        CanonEosSettingSpec(CanonEosPropertyCode.PICTURE_STYLE, "picturestyle", "Picture style"),
        CanonEosSettingSpec(CanonEosPropertyCode.IMAGE_FORMAT, "stillimagequality", "Image quality"),
        CanonEosSettingSpec(CanonEosPropertyCode.IMAGE_FORMAT_SD, "stillimagequalitysd", "SD image quality"),
        CanonEosSettingSpec(
            CanonEosPropertyCode.IMAGE_FORMAT_CF,
            "stillimagequalitycf",
            "CF/CFexpress image quality",
        ),
        CanonEosSettingSpec(CanonEosPropertyCode.MOVIE_SERVO_AF, "movieservoaf", "Movie Servo AF"),
    )

    private val imageFormatPropertyCodes = setOf(
        CanonEosPropertyCode.IMAGE_FORMAT,
        CanonEosPropertyCode.IMAGE_FORMAT_CF,
        CanonEosPropertyCode.IMAGE_FORMAT_SD,
    )

    private val remotePreparationOperations = setOf(
        CanonEosOperationCode.SET_REMOTE_MODE,
        CanonEosOperationCode.SET_EVENT_MODE,
        CanonEosOperationCode.GET_EVENT,
    )

    private val capturedObjectEvents = setOf(
        CanonEosEventCode.OBJECT_ADDED_EX,
        CanonEosEventCode.REQUEST_OBJECT_TRANSFER,
        CanonEosEventCode.OBJECT_ADDED_EX_64,
        CanonEosEventCode.REQUEST_OBJECT_TRANSFER_64,
        CanonEosEventCode.OBJECT_ADDED_EX_64_LFN,
        CanonEosEventCode.REQUEST_OBJECT_TRANSFER_64_LFN,
    )

    private val cardCapturedObjectEvents = setOf(
        CanonEosEventCode.OBJECT_ADDED_EX,
        CanonEosEventCode.OBJECT_ADDED_EX_64,
        CanonEosEventCode.OBJECT_ADDED_EX_64_LFN,
    )

    private val objectTransferEvents = setOf(
        CanonEosEventCode.REQUEST_OBJECT_TRANSFER,
        CanonEosEventCode.REQUEST_OBJECT_TRANSFER_64,
        CanonEosEventCode.REQUEST_OBJECT_TRANSFER_64_LFN,
    )

    fun isCanonEos(info: PtpDeviceInfo): Boolean = info.vendorExtensionId == VENDOR_EXTENSION_ID

    fun supportsRemotePreparation(info: PtpDeviceInfo): Boolean =
        isCanonEos(info) && remotePreparationOperations.all(info::supports)

    fun supportsRemoteRelease(info: PtpDeviceInfo): Boolean =
        supportsRemotePreparation(info) &&
            info.supports(CanonEosOperationCode.REMOTE_RELEASE_ON) &&
            info.supports(CanonEosOperationCode.REMOTE_RELEASE_OFF)

    fun supportsAutofocus(info: PtpDeviceInfo): Boolean =
        supportsRemotePreparation(info) &&
            info.supports(CanonEosOperationCode.DO_AF) &&
            info.supports(CanonEosOperationCode.AF_CANCEL)

    fun supportsLiveView(info: PtpDeviceInfo): Boolean =
        supportsRemotePreparation(info) &&
            info.supports(CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX) &&
            info.supports(CanonEosOperationCode.GET_VIEWFINDER_DATA)

    fun supportsFocusDrive(info: PtpDeviceInfo): Boolean =
        supportsRemotePreparation(info) && info.supports(CanonEosOperationCode.DRIVE_LENS)

    fun supportsLiveViewMagnification(info: PtpDeviceInfo): Boolean =
        supportsLiveView(info) && info.supports(CanonEosOperationCode.ZOOM)

    fun supportsTouchAutofocus(info: PtpDeviceInfo): Boolean =
        supportsLiveView(info) &&
            info.supports(CanonEosOperationCode.TOUCH_AF_POSITION) &&
            (supportsAutofocus(info) || supportsRemoteRelease(info))

    fun supportsClickWhiteBalance(info: PtpDeviceInfo): Boolean =
        supportsLiveView(info) && info.supports(CanonEosOperationCode.CLICK_WHITE_BALANCE)

    fun supportsPropertyControl(info: PtpDeviceInfo): Boolean =
        supportsRemotePreparation(info) && info.supports(CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX)

    fun supportsMovieRecording(info: PtpDeviceInfo, availableValues: List<Long>): Boolean =
        supportsPropertyControl(info) &&
            MOVIE_RECORD_TARGET_NONE in availableValues &&
            MOVIE_RECORD_TARGET_CARD in availableValues

    fun supportsMovieModeSwitch(info: PtpDeviceInfo, currentValue: Long?): Boolean =
        supportsRemotePreparation(info) &&
            info.supports(CanonEosOperationCode.MOVIE_SELECT_SWITCH_ON) &&
            info.supports(CanonEosOperationCode.MOVIE_SELECT_SWITCH_OFF) &&
            currentValue in 0L..1L

    fun movieRecording(value: Long?): Boolean? = when (value) {
        MOVIE_RECORD_TARGET_CARD -> true
        MOVIE_RECORD_TARGET_NONE, MOVIE_RECORD_TARGET_SDRAM -> false
        else -> null
    }

    fun captureDestinationCardValue(availableValues: List<Long>): Long? =
        availableValues.distinct().firstOrNull { it != CAPTURE_DESTINATION_HOST }

    fun storageTargetOptions(storages: List<PtpStorageInfo>): List<CanonEosPropertyOption> {
        val writableStorages = storages
            .filter { it.accessCapability == PTP_STORAGE_READ_WRITE }
            .distinctBy(PtpStorageInfo::storageId)
        val candidates = writableStorages.map { storage ->
            listOf(storage.description.trim(), storage.volumeLabel.trim()).filter(String::isNotBlank)
        }
        return writableStorages.mapIndexed { index, storage ->
            val uniqueCameraLabel = candidates[index].firstOrNull { candidate ->
                candidates.count { labels -> labels.any { it.equals(candidate, ignoreCase = true) } } == 1
            }
            CanonEosPropertyOption(
                value = storage.storageId,
                label = uniqueCameraLabel ?: "Card ${index + 1}",
            )
        }
    }

    fun availableShots(value: Long?): Long? =
        value?.takeIf { it in 0L..0xFFFF_FFFEL }

    fun focusDriveAmount(direction: FocusDriveDirection, step: FocusDriveStep): Long {
        val magnitude = when (step) {
            FocusDriveStep.SMALL -> 1L
            FocusDriveStep.MEDIUM -> 2L
            FocusDriveStep.LARGE -> 3L
        }
        return if (direction == FocusDriveDirection.FAR) magnitude or 0x8000L else magnitude
    }

    fun uint16PropertyPayload(propertyCode: Int, value: Int): ByteArray {
        require(value in 0..0xFFFF) { "Canon EOS property value $value does not fit UINT16." }
        return propertyPayload(propertyCode, value.toLong(), 2)
    }

    fun uint8PropertyPayload(propertyCode: Int, value: Int): ByteArray {
        require(value in 0..0xFF) { "Canon EOS property value $value does not fit UINT8." }
        return propertyPayload(propertyCode, value.toLong(), 1)
    }

    fun uint32PropertyPayload(propertyCode: Int, value: Long): ByteArray {
        require(value in 0..UINT32_MAX) { "Canon EOS property value $value does not fit UINT32." }
        return propertyPayload(propertyCode, value, 4)
    }

    fun int32PropertyPayload(propertyCode: Int, value: Long): ByteArray {
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "Canon EOS property value $value does not fit INT32."
        }
        return propertyPayload(propertyCode, value, 4)
    }

    fun eventCodes(payload: ByteArray): Set<Int> {
        return eventBlocks(payload).mapTo(linkedSetOf(), CanonEosEventBlock::code)
    }

    fun containsCapturedObjectEvent(payload: ByteArray): Boolean =
        eventCodes(payload).any { it in capturedObjectEvents }

    fun containsCardCapturedObjectEvent(payload: ByteArray): Boolean =
        eventCodes(payload).any { it in cardCapturedObjectEvents }

    fun objectTransferRequests(payload: ByteArray): List<CanonEosObjectTransferRequest> = buildList {
        eventBlocks(payload).forEach { block ->
            if (block.code !in objectTransferEvents) return@forEach
            val minimumLength = if (block.code == CanonEosEventCode.REQUEST_OBJECT_TRANSFER_64_LFN) {
                OBJECT_TRANSFER_64_LFN_MIN_BYTES
            } else {
                OBJECT_TRANSFER_NAME_OFFSET + 1
            }
            if (block.length < minimumLength) {
                malformedObjectTransferEvent(block, "object metadata")
            }
            val sizeBytes = payload.u32Le(block.offset + OBJECT_TRANSFER_SIZE_OFFSET)
            val filename = if (block.code == CanonEosEventCode.REQUEST_OBJECT_TRANSFER_64_LFN) {
                null
            } else {
                payload.nullTerminatedAscii(
                    offset = block.offset + OBJECT_TRANSFER_NAME_OFFSET,
                    limit = block.offset + block.length,
                )
            }
            add(
                CanonEosObjectTransferRequest(
                    eventCode = block.code,
                    handle = payload.u32Le(block.offset + OBJECT_TRANSFER_HANDLE_OFFSET),
                    objectFormat = payload.u16Le(block.offset + OBJECT_TRANSFER_FORMAT_OFFSET),
                    sizeBytes = sizeBytes,
                    filename = filename?.takeIf(String::isNotBlank),
                )
            )
        }
    }

    fun propertyUpdates(payload: ByteArray): List<CanonEosPropertyUpdate> = buildList {
        eventBlocks(payload).forEach { block ->
            when (block.code) {
                CanonEosEventCode.PROPERTY_VALUE_CHANGED -> {
                    if (block.length < 12) malformedPropertyEvent(block, "property code")
                    val propertyCode = payload.u32Le(block.offset + 8).toInt()
                    if (propertyCode in imageFormatPropertyCodes) {
                        add(
                            CanonEosPropertyUpdate(
                                propertyCode = propertyCode,
                                currentValue = unpackImageFormat(
                                    payload,
                                    offset = block.offset + 12,
                                    limit = block.offset + block.length,
                                ).value,
                            )
                        )
                        return@forEach
                    }
                    val spec = propertySpecs[propertyCode] ?: return@forEach
                    if (block.length < 12 + spec.valueBytes) malformedPropertyEvent(block, "property value")
                    add(
                        CanonEosPropertyUpdate(
                            propertyCode = propertyCode,
                            currentValue = payload.numberLe(
                                offset = block.offset + 12,
                                size = spec.valueBytes,
                                signed = spec.signed,
                            ),
                        )
                    )
                }

                CanonEosEventCode.AVAILABLE_LIST_CHANGED -> {
                    if (block.length < 20) malformedPropertyEvent(block, "available-value header")
                    val propertyCode = payload.u32Le(block.offset + 8).toInt()
                    val spec = propertySpecs[propertyCode]
                    if (spec == null && propertyCode !in imageFormatPropertyCodes) return@forEach
                    val listType = payload.u32Le(block.offset + 12)
                    if (listType != 3L) return@forEach
                    val count = payload.u32Le(block.offset + 16)
                    if (count > MAX_PROPERTY_OPTIONS) malformedPropertyEvent(block, "available-value list")
                    if (propertyCode in imageFormatPropertyCodes) {
                        var valueOffset = block.offset + 20
                        val values = List(count.toInt()) {
                            unpackImageFormat(
                                payload,
                                offset = valueOffset,
                                limit = block.offset + block.length,
                            ).also { valueOffset += it.bytesRead }.value
                        }
                        add(CanonEosPropertyUpdate(propertyCode = propertyCode, availableValues = values))
                        return@forEach
                    }
                    val requiredBytes = 20L + count * 4L
                    if (requiredBytes > block.length.toLong()) {
                        malformedPropertyEvent(block, "available-value list")
                    }
                    add(
                        CanonEosPropertyUpdate(
                            propertyCode = propertyCode,
                            availableValues = List(count.toInt()) { index ->
                                payload.numberLe(
                                    offset = block.offset + 20 + index * 4,
                                    size = 4,
                                    signed = spec?.signed == true,
                                )
                            },
                        )
                    )
                }
            }
        }
    }

    fun propertyOptions(propertyCode: Int, values: List<Long>): List<CanonEosPropertyOption> {
        val selectableValues = propertySpecs[propertyCode]?.selectableValues
        return values.distinct().filter { value ->
            selectableValues == null || value in selectableValues
        }.map { value ->
            CanonEosPropertyOption(value = value, label = propertyLabel(propertyCode, value))
        }.distinctBy(CanonEosPropertyOption::label)
    }

    fun propertyLabel(propertyCode: Int, value: Long): String = when {
        propertyCode in imageFormatPropertyCodes -> imageFormatLabel(value)
        propertyCode == CanonEosPropertyCode.AVAILABLE_SHOTS -> value.toString()
        propertyCode == CanonEosPropertyCode.CAPTURE_DESTINATION ->
            if (value == CAPTURE_DESTINATION_HOST) "Internal RAM" else "Memory card"
        else -> propertySpecs[propertyCode]?.labels?.get(value)
            ?: value.hexLabel(propertySpecs[propertyCode]?.valueBytes ?: 4)
    }

    fun propertyValue(propertyCode: Int, values: List<Long>, label: String): Long? =
        propertyOptions(propertyCode, values).firstOrNull { it.label == label }?.value

    fun settingKey(propertyCode: Int): String? = when (propertyCode) {
        CanonEosPropertyCode.CAPTURE_DESTINATION -> "capturetarget"
        CanonEosPropertyCode.CURRENT_STORAGE -> "capturestorage"
        else -> settingSpecs.firstOrNull { it.propertyCode == propertyCode }?.key
    }

    fun propertyValueBytes(propertyCode: Int): Int? = propertySpecs[propertyCode]?.valueBytes

    fun propertyPayload(propertyCode: Int, value: Long): ByteArray {
        if (propertyCode in imageFormatPropertyCodes) return imageFormatPropertyPayload(propertyCode, value)
        val spec = propertySpecs[propertyCode]
        if (spec?.signed == true) {
            check(spec.valueBytes == 4) { "Only Canon EOS INT32 properties are currently supported." }
            return int32PropertyPayload(propertyCode, value)
        }
        return when (spec?.valueBytes) {
            1 -> uint8PropertyPayload(propertyCode, value.toInt())
            2 -> uint16PropertyPayload(propertyCode, value.toInt())
            4 -> uint32PropertyPayload(propertyCode, value)
            else -> throw IllegalArgumentException(
                "Canon EOS property 0x${propertyCode.toString(16)} is not writable."
            )
        }
    }

    private fun unpackImageFormat(payload: ByteArray, offset: Int, limit: Int): CanonEosImageFormatValue {
        if (offset < 0 || limit > payload.size || limit - offset < 4) {
            malformedImageFormat(offset, "missing entry count")
        }
        val entryCount = payload.u32Le(offset).toInt()
        if (entryCount !in 1..2) malformedImageFormat(offset, "entry count $entryCount is not 1 or 2")
        val bytesRead = 4 + entryCount * IMAGE_FORMAT_ENTRY_BYTES
        if (limit - offset < bytesRead) malformedImageFormat(offset, "truncated $entryCount-entry value")

        fun entryByte(entryOffset: Int): Int {
            val entryLength = payload.u32Le(entryOffset)
            if (entryLength != IMAGE_FORMAT_ENTRY_BYTES.toLong()) {
                malformedImageFormat(entryOffset, "entry length $entryLength is not $IMAGE_FORMAT_ENTRY_BYTES")
            }
            val type = payload.u32Le(entryOffset + 4)
            var size = payload.u32Le(entryOffset + 8).toInt()
            val compression = payload.u32Le(entryOffset + 12).toInt()
            if (size >= 0x0E) size--
            val typeAndCompression =
                (compression and 0x07) or (if (type == IMAGE_FORMAT_TYPE_RAW) 0x08 else 0)
            return ((size and 0x0F) shl 4) or typeAndCompression
        }

        val first = entryByte(offset + 4)
        var second = if (entryCount == 2) entryByte(offset + 4 + IMAGE_FORMAT_ENTRY_BYTES) else 0xFF
        if (entryCount == 2 && second == 0) second = 0xFF
        return CanonEosImageFormatValue(value = ((first shl 8) or second).toLong(), bytesRead = bytesRead)
    }

    private fun imageFormatPropertyPayload(propertyCode: Int, value: Long): ByteArray {
        require(value in 0..0xFFFF) { "Canon EOS image format value $value does not fit UINT16." }
        val packedValue = value.toInt()
        val entryCount = if ((packedValue and 0xFF) == 0xFF) 1 else 2
        val payload = ByteArray(8 + 4 + entryCount * IMAGE_FORMAT_ENTRY_BYTES)
        payload.putU32Le(0, payload.size.toLong())
        payload.putU32Le(4, propertyCode.toLong())
        payload.putU32Le(8, entryCount.toLong())

        fun writeEntry(offset: Int, formatByte: Int) {
            val condensedSize = (formatByte ushr 4) and 0x0F
            val wireSize = if (condensedSize >= 0x0D) condensedSize + 1 else condensedSize
            payload.putU32Le(offset, IMAGE_FORMAT_ENTRY_BYTES.toLong())
            payload.putU32Le(
                offset + 4,
                if ((formatByte and 0x08) != 0) IMAGE_FORMAT_TYPE_RAW else IMAGE_FORMAT_TYPE_JPEG,
            )
            payload.putU32Le(offset + 8, wireSize.toLong())
            payload.putU32Le(offset + 12, (formatByte and 0x07).toLong())
        }

        writeEntry(12, (packedValue ushr 8) and 0xFF)
        if (entryCount == 2) writeEntry(12 + IMAGE_FORMAT_ENTRY_BYTES, packedValue and 0xFF)
        return payload
    }

    private fun imageFormatLabel(value: Long): String {
        if (value !in 0..0xFFFF) return value.hexLabel(2)
        val first = ((value.toInt() ushr 8) and 0xFF).imageFormatEntryLabel()
        val secondValue = value.toInt() and 0xFF
        return if (secondValue == 0xFF) first else "$first + ${secondValue.imageFormatEntryLabel()}"
    }

    private fun Int.imageFormatEntryLabel(): String = singleImageFormatLabels[this]
        ?: "0x${toString(16).uppercase().padStart(2, '0')}"

    private fun malformedImageFormat(offset: Int, reason: String): Nothing =
        throw PtpProtocolException("Canon EOS image format at byte $offset is malformed: $reason.")

    fun liveViewData(payload: ByteArray): CanonEosLiveViewData {
        var offset = 0
        var jpeg: ByteArray? = null
        var geometry: CanonEosLiveViewGeometry? = null
        while (offset + 8 <= payload.size) {
            val length = payload.u32Le(offset)
            val type = payload.u32Le(offset + 4)
            if (length < 8L || length > Int.MAX_VALUE || length > payload.size - offset) {
                throw PtpProtocolException(
                    "Canon EOS viewfinder block at byte $offset declares invalid length $length " +
                        "for ${payload.size - offset} remaining bytes."
                )
            }
            if ((type == VIEWFINDER_JPEG_BLOCK || type == VIEWFINDER_JPEG_BLOCK_ALTERNATE) && jpeg == null) {
                val candidate = payload.copyOfRange(offset + 8, offset + length.toInt())
                if (candidate.size < 4 || candidate[0] != 0xFF.toByte() || candidate[1] != 0xD8.toByte()) {
                    throw PtpProtocolException("Canon EOS viewfinder block type $type did not contain JPEG data.")
                }
                jpeg = candidate
            } else if (type == VIEWFINDER_SENSOR_GEOMETRY_BLOCK && length >= 16L) {
                val width = payload.u32Le(offset + 8)
                val height = payload.u32Le(offset + 12)
                if (
                    width in 1..MAX_VIEWFINDER_SENSOR_DIMENSION &&
                    height in 1..MAX_VIEWFINDER_SENSOR_DIMENSION
                ) {
                    geometry = CanonEosLiveViewGeometry(width.toInt(), height.toInt())
                }
            }
            offset += length.toInt()
        }
        return CanonEosLiveViewData(
            jpeg = jpeg ?: throw PtpProtocolException(
                "Canon EOS viewfinder response did not contain a JPEG block."
            ),
            geometry = geometry,
        )
    }

    fun liveViewJpeg(payload: ByteArray): ByteArray = liveViewData(payload).jpeg

    private fun propertyPayload(propertyCode: Int, value: Long, valueBytes: Int): ByteArray =
        ByteArray(12).also { payload ->
            payload.putU32Le(0, payload.size.toLong())
            payload.putU32Le(4, propertyCode.toLong())
            repeat(valueBytes) { index -> payload[8 + index] = (value ushr (index * 8)).toByte() }
        }

    private fun eventBlocks(payload: ByteArray): List<CanonEosEventBlock> = buildList {
        var offset = 0
        while (offset + 8 <= payload.size) {
            val length = payload.u32Le(offset)
            val code = payload.u32Le(offset + 4)
            if (length == 8L && code == 0L) break
            if (length < 8L || length > Int.MAX_VALUE || length > payload.size - offset) {
                throw PtpProtocolException(
                    "Canon EOS event block at byte $offset declares invalid length $length " +
                        "for ${payload.size - offset} remaining bytes."
                )
            }
            add(CanonEosEventBlock(code = code.toInt(), offset = offset, length = length.toInt()))
            offset += length.toInt()
        }
    }

    private fun malformedPropertyEvent(block: CanonEosEventBlock, field: String): Nothing =
        throw PtpProtocolException(
            "Canon EOS property event 0x${block.code.toString(16)} at byte ${block.offset} " +
                "has an invalid $field payload (${block.length} bytes)."
        )

    private fun malformedObjectTransferEvent(block: CanonEosEventBlock, field: String): Nothing =
        throw PtpProtocolException(
            "Canon EOS object-transfer event 0x${block.code.toString(16)} at byte ${block.offset} " +
                "has invalid $field (${block.length} bytes)."
        )

    private data class CanonEosEventBlock(
        val code: Int,
        val offset: Int,
        val length: Int,
    )

    private data class CanonEosPropertySpec(
        val valueBytes: Int,
        val labels: Map<Long, String>,
        val signed: Boolean = false,
        val selectableValues: Set<Long>? = null,
    )

    private const val OBJECT_TRANSFER_HANDLE_OFFSET = 0x08
    private const val OBJECT_TRANSFER_FORMAT_OFFSET = 0x0C
    private const val OBJECT_TRANSFER_SIZE_OFFSET = 0x14
    private const val OBJECT_TRANSFER_NAME_OFFSET = 0x1C
    private const val OBJECT_TRANSFER_64_LFN_MIN_BYTES = 0x25

    private val isoLabels = mapOf(
        0x0000L to "Auto", 0x0001L to "Auto ISO", 0x0028L to "6", 0x0030L to "12",
        0x0038L to "25", 0x0040L to "50", 0x0043L to "64", 0x0045L to "80",
        0x0048L to "100", 0x004BL to "125", 0x004DL to "160", 0x0050L to "200",
        0x0053L to "250", 0x0055L to "320", 0x0058L to "400", 0x005BL to "500",
        0x005DL to "640", 0x0060L to "800", 0x0063L to "1000", 0x0065L to "1250",
        0x0068L to "1600", 0x006BL to "2000", 0x006DL to "2500", 0x0070L to "3200",
        0x0073L to "4000", 0x0075L to "5000", 0x0078L to "6400", 0x007BL to "8000",
        0x007DL to "10000", 0x0080L to "12800", 0x0083L to "16000", 0x0085L to "20000",
        0x0088L to "25600", 0x008BL to "32000", 0x008DL to "40000", 0x0090L to "51200",
        0x0093L to "64000", 0x0095L to "80000", 0x0098L to "102400", 0x00A0L to "204800",
        0x00A8L to "409600", 0x00B0L to "819200", 0xFFFFL to "Factory default",
    )

    private val apertureLabels = mapOf(
        0x0000L to "Auto", 0xFFFFL to "Auto", 0x00B0L to "Auto", 0x0008L to "1",
        0x000BL to "1.1", 0x000CL to "1.2", 0x000DL to "1.2", 0x0010L to "1.4",
        0x0013L to "1.6", 0x0014L to "1.8", 0x0015L to "1.8", 0x0018L to "2",
        0x001BL to "2.2", 0x001CL to "2.5", 0x001DL to "2.5", 0x0020L to "2.8",
        0x0023L to "3.2", 0x0024L to "3.5", 0x0025L to "3.5", 0x0028L to "4",
        0x002BL to "4.5", 0x002CL to "4.5", 0x002DL to "5", 0x0030L to "5.6",
        0x0033L to "6.3", 0x0034L to "6.7", 0x0035L to "7.1", 0x0038L to "8",
        0x003BL to "9", 0x003CL to "9.5", 0x003DL to "10", 0x0040L to "11",
        0x0043L to "13", 0x0044L to "13", 0x0045L to "14", 0x0048L to "16",
        0x004BL to "18", 0x004CL to "19", 0x004DL to "20", 0x0050L to "22",
        0x0053L to "25", 0x0054L to "27", 0x0055L to "29", 0x0058L to "32",
        0x005BL to "36", 0x005CL to "38", 0x005DL to "40", 0x0060L to "45",
        0x0063L to "51", 0x0064L to "54", 0x0065L to "57", 0x0068L to "64",
        0x006BL to "72", 0x006CL to "76", 0x006DL to "81", 0x0070L to "91",
    )

    private val shutterLabels = mapOf(
        0x0000L to "Auto", 0x0004L to "Bulb", 0x000CL to "Bulb", 0x0010L to "30",
        0x0013L to "25", 0x0014L to "20.3", 0x0015L to "20", 0x0018L to "15",
        0x001BL to "13", 0x001CL to "10", 0x001DL to "10.3", 0x0020L to "8",
        0x0023L to "6.3", 0x0024L to "6", 0x0025L to "5", 0x0028L to "4",
        0x002BL to "3.2", 0x002CL to "3", 0x002DL to "2.5", 0x0030L to "2",
        0x0033L to "1.6", 0x0034L to "1.5", 0x0035L to "1.3", 0x0038L to "1",
        0x003BL to "0.8", 0x003CL to "0.7", 0x003DL to "0.6", 0x0040L to "0.5",
        0x0043L to "0.4", 0x0044L to "0.3", 0x0045L to "0.3", 0x0048L to "1/4",
        0x004BL to "1/5", 0x004CL to "1/6", 0x004DL to "1/6", 0x0050L to "1/8",
        0x0053L to "1/10", 0x0054L to "1/10", 0x0055L to "1/13", 0x0058L to "1/15",
        0x005BL to "1/20", 0x005CL to "1/20", 0x005DL to "1/25", 0x0060L to "1/30",
        0x0063L to "1/40", 0x0064L to "1/45", 0x0065L to "1/50", 0x0068L to "1/60",
        0x006BL to "1/80", 0x006CL to "1/90", 0x006DL to "1/100", 0x0070L to "1/125",
        0x0073L to "1/160", 0x0074L to "1/180", 0x0075L to "1/200", 0x0078L to "1/250",
        0x007BL to "1/320", 0x007CL to "1/350", 0x007DL to "1/400", 0x0080L to "1/500",
        0x0083L to "1/640", 0x0084L to "1/750", 0x0085L to "1/800", 0x0088L to "1/1000",
        0x008BL to "1/1250", 0x008CL to "1/1500", 0x008DL to "1/1600", 0x0090L to "1/2000",
        0x0093L to "1/2500", 0x0094L to "1/3000", 0x0095L to "1/3200", 0x0098L to "1/4000",
        0x009BL to "1/5000", 0x009CL to "1/6000", 0x009DL to "1/6400", 0x00A0L to "1/8000",
        0x00A8L to "1/16000",
    )

    private val whiteBalanceLabels = mapOf(
        0L to "Auto", 1L to "Daylight", 2L to "Cloudy", 3L to "Tungsten",
        4L to "Fluorescent", 5L to "Flash", 6L to "Manual", 7L to "Unknown 7",
        8L to "Shadow", 9L to "Color Temperature", 10L to "Custom WB 1",
        11L to "Custom WB 2", 12L to "Custom WB 3", 15L to "Manual 2",
        16L to "Manual 3", 18L to "Manual 4", 19L to "Manual 5",
        20L to "Custom WB 4", 21L to "Custom WB 5", 23L to "AWB White",
    )

    private val movieRecordTargetLabels = mapOf(
        MOVIE_RECORD_TARGET_NONE to "None",
        MOVIE_RECORD_TARGET_SDRAM to "SDRAM",
        MOVIE_RECORD_TARGET_CARD to "Card",
    )

    private val driveModeLabels = mapOf(
        0x0000L to "Single",
        0x0001L to "Continuous",
        0x0002L to "Video",
        0x0004L to "Continuous high speed",
        0x0005L to "Continuous low speed",
        0x0006L to "Single: Silent shooting",
        0x0007L to "Continuous timer",
        0x0010L to "Timer 10 sec",
        0x0011L to "Timer 2 sec",
        0x0012L to "Super high speed continuous shooting",
        0x0013L to "Single silent",
        0x0014L to "Continuous silent",
        0x0015L to "Silent HS continuous",
        0x0016L to "Silent LS continuous",
    )

    private val meteringModeLabels = mapOf(
        0L to "Center-weighted",
        1L to "Spot",
        2L to "Average",
        3L to "Evaluative",
        4L to "Partial",
        5L to "Center-weighted average",
        6L to "Spot metering interlocked with AF frame",
        7L to "Multi spot",
    )

    private val focusModeLabels = mapOf(
        0L to "One Shot",
        1L to "AI Servo",
        2L to "AI Focus",
        3L to "Manual",
    )

    private val pictureStyleLabels = mapOf(
        0x81L to "Standard",
        0x82L to "Portrait",
        0x83L to "Landscape",
        0x84L to "Neutral",
        0x85L to "Faithful",
        0x86L to "Monochrome",
        0x87L to "Auto",
        0x88L to "Fine detail",
        0x21L to "User defined 1",
        0x22L to "User defined 2",
        0x23L to "User defined 3",
    )

    private val afMethodLabels = mapOf(
        0L to "Quick",
        1L to "Live",
        2L to "LiveFace",
        3L to "LiveMulti",
        4L to "LiveZone",
        5L to "LiveSingleExpandCross",
        6L to "LiveSingleExpandSurround",
        7L to "LiveZoneLargeH",
        8L to "LiveZoneLargeV",
        9L to "LiveCatchAF",
        10L to "LiveSpotAF",
        11L to "FlexibleZoneAF1",
        12L to "FlexibleZoneAF2",
        13L to "FlexibleZoneAF3",
        14L to "WholeAreaAF",
    )

    private val offOnLabels = mapOf(0L to "Off", 1L to "On")

    private val autoExposureModeLabels = mapOf(
        0x0000L to "P",
        0x0001L to "TV",
        0x0002L to "AV",
        0x0003L to "Manual",
        0x0004L to "Bulb",
        0x0005L to "A_DEP",
        0x0006L to "DEP",
        0x0007L to "Custom",
        0x0008L to "Lock",
        0x0009L to "Green",
        0x000AL to "Night Portrait",
        0x000BL to "Sports",
        0x000CL to "Portrait",
        0x000DL to "Landscape",
        0x000EL to "Closeup",
        0x000FL to "Flash Off",
        0x0010L to "C2",
        0x0011L to "C3",
        0x0013L to "Creative Auto",
        0x0014L to "Movie",
        0x0016L to "Auto",
        0x0017L to "Handheld Night Scene",
        0x0018L to "HDR Backlight Control",
        0x0019L to "SCN",
        0x001BL to "Food",
        0x001EL to "Grainy B/W",
        0x001FL to "Soft focus",
        0x0020L to "Toy camera effect",
        0x0021L to "Fish-eye effect",
        0x0022L to "Water painting effect",
        0x0023L to "Miniature effect",
        0x0024L to "HDR art standard",
        0x0025L to "HDR art vivid",
        0x0026L to "HDR art bold",
        0x0027L to "HDR art embossed",
        0x002DL to "Panning",
        0x0031L to "HDR",
        0x0032L to "Self Portrait",
        0x0033L to "Hybrid Auto",
        0x0034L to "Smooth skin",
        0x0037L to "Fv",
    )

    private val exposureCompensationLabels = mapOf(
        0x28L to "5", 0x25L to "4.6", 0x24L to "4.5", 0x23L to "4.3",
        0x20L to "4", 0x1DL to "3.6", 0x1CL to "3.5", 0x1BL to "3.3",
        0x18L to "3", 0x15L to "2.6", 0x14L to "2.5", 0x13L to "2.3",
        0x10L to "2", 0x0DL to "1.6", 0x0CL to "1.5", 0x0BL to "1.3",
        0x08L to "1", 0x05L to "0.6", 0x04L to "0.5", 0x03L to "0.3",
        0x00L to "0", 0xFDL to "-0.3", 0xFCL to "-0.5", 0xFBL to "-0.6",
        0xF8L to "-1", 0xF5L to "-1.3", 0xF4L to "-1.5", 0xF3L to "-1.6",
        0xF0L to "-2", 0xEDL to "-2.3", 0xECL to "-2.5", 0xEBL to "-2.6",
        0xE8L to "-3", 0xE5L to "-3.3", 0xE4L to "-3.5", 0xE3L to "-3.6",
        0xE0L to "-4", 0xDDL to "-4.3", 0xDCL to "-4.5", 0xDBL to "-4.6",
        0xD8L to "-5",
    )

    private val colorTemperatureLabels = (2500..10000 step 100).associate { value ->
        value.toLong() to value.toString()
    }

    private val whiteBalanceAdjustLabels = (-9..9).associate { value ->
        value.toLong() to value.toString()
    }

    private val colorSpaceLabels = mapOf(1L to "sRGB", 2L to "AdobeRGB")

    private val aspectRatioLabels = mapOf(
        0x0000L to "3:2",
        0x0001L to "1:1",
        0x0002L to "4:3",
        0x0007L to "16:9",
        0x000DL to "1.6x",
    )

    private val powerZoomSpeedLabels = (1..15).associate { value ->
        value.toLong() to value.toString()
    }

    private val autoPowerOffLabels = mapOf(
        15L to "15 seconds",
        30L to "30 seconds",
        60L to "1 minute",
        180L to "3 minutes",
        300L to "5 minutes",
        600L to "10 minutes",
        1800L to "30 minutes",
        0L to "Disable",
    )

    private val highIsoNoiseReductionLabels = mapOf(
        0L to "Off",
        1L to "Low",
        2L to "Normal",
        3L to "High",
        4L to "Multi-Shot",
    )

    private val aebLabels = mapOf(
        0x0000L to "off",
        0x0003L to "+/- 1/3",
        0x0004L to "+/- 1/2",
        0x0005L to "+/- 2/3",
        0x0008L to "+/- 1",
        0x000BL to "+/- 1 1/3",
        0x000CL to "+/- 1 1/2",
        0x000DL to "+/- 1 2/3",
        0x0010L to "+/- 2",
        0x0013L to "+/- 2 1/3",
        0x0014L to "+/- 2 1/2",
        0x0015L to "+/- 2 2/3",
        0x0018L to "+/- 3",
    )

    private val autoLightingOptimizerLabels = mapOf(
        0x00010000L to "Standard",
        0x00000000L to "Standard (disabled in manual exposure)",
        0x00010101L to "Low",
        0x00000101L to "Low (disabled in manual exposure)",
        0x00010303L to "Off",
        0x00000303L to "Off (disabled in manual exposure)",
        0x00010202L to "High",
        0x00000202L to "High (disabled in manual exposure)",
        0x00000001L to "x1",
        0x00000002L to "x2",
        0x00000003L to "x3",
    )

    private val singleImageFormatLabels = mapOf(
        0x0C to "RAW",
        0x1C to "mRAW",
        0x2C to "sRAW",
        0x0B to "cRAW",
        0x03 to "Large Fine JPEG",
        0x13 to "Medium Fine JPEG",
        0x23 to "Small Fine JPEG",
        0x02 to "Large Normal JPEG",
        0x12 to "Medium Normal JPEG",
        0x22 to "Small Normal JPEG",
        0xD3 to "Small 1 Fine JPEG",
        0xE3 to "Small 2 Fine JPEG",
        0xF3 to "Small 3 Fine JPEG",
        0xD2 to "Small 1 Normal JPEG",
        0xE2 to "Small 2 Normal JPEG",
        0xF2 to "Small 3 Normal JPEG",
        0x53 to "Medium 1 Fine JPEG",
        0x63 to "Medium 2 Fine JPEG",
        0x52 to "Medium 1 Normal JPEG",
        0x62 to "Medium 2 Normal JPEG",
        0x01 to "Large JPEG",
        0x51 to "Medium 1 JPEG",
        0x61 to "Medium 2 JPEG",
        0x21 to "Small JPEG",
        0x00 to "Large JPEG (custom)",
        0x10 to "Medium JPEG (custom)",
        0xE0 to "Smaller JPEG",
        0xD0 to "Small 2 JPEG",
    )

    private val propertySpecs = mapOf(
        CanonEosPropertyCode.APERTURE to CanonEosPropertySpec(2, apertureLabels),
        CanonEosPropertyCode.SHUTTER_SPEED to CanonEosPropertySpec(2, shutterLabels),
        CanonEosPropertyCode.ISO_SPEED to CanonEosPropertySpec(2, isoLabels),
        CanonEosPropertyCode.EXPOSURE_COMPENSATION to CanonEosPropertySpec(1, exposureCompensationLabels),
        CanonEosPropertyCode.AUTO_EXPOSURE_MODE to CanonEosPropertySpec(2, autoExposureModeLabels),
        CanonEosPropertyCode.DRIVE_MODE to CanonEosPropertySpec(2, driveModeLabels),
        CanonEosPropertyCode.METERING_MODE to CanonEosPropertySpec(1, meteringModeLabels),
        CanonEosPropertyCode.FOCUS_MODE to CanonEosPropertySpec(4, focusModeLabels),
        CanonEosPropertyCode.WHITE_BALANCE to CanonEosPropertySpec(1, whiteBalanceLabels),
        CanonEosPropertyCode.COLOR_TEMPERATURE to CanonEosPropertySpec(4, colorTemperatureLabels),
        CanonEosPropertyCode.WHITE_BALANCE_ADJUST_A to
            CanonEosPropertySpec(4, whiteBalanceAdjustLabels, signed = true),
        CanonEosPropertyCode.WHITE_BALANCE_ADJUST_B to
            CanonEosPropertySpec(4, whiteBalanceAdjustLabels, signed = true),
        CanonEosPropertyCode.COLOR_SPACE to CanonEosPropertySpec(2, colorSpaceLabels),
        CanonEosPropertyCode.CAMERA_TIME to CanonEosPropertySpec(4, emptyMap()),
        CanonEosPropertyCode.MULTI_ASPECT to CanonEosPropertySpec(4, aspectRatioLabels),
        CanonEosPropertyCode.POWER_ZOOM_SPEED to CanonEosPropertySpec(4, powerZoomSpeedLabels),
        CanonEosPropertyCode.AUTO_POWER_OFF to CanonEosPropertySpec(
            valueBytes = 4,
            labels = autoPowerOffLabels,
            selectableValues = autoPowerOffLabels.keys,
        ),
        CanonEosPropertyCode.AVAILABLE_SHOTS to CanonEosPropertySpec(4, emptyMap()),
        CanonEosPropertyCode.CAPTURE_DESTINATION to CanonEosPropertySpec(4, emptyMap()),
        CanonEosPropertyCode.CURRENT_STORAGE to CanonEosPropertySpec(4, emptyMap()),
        CanonEosPropertyCode.PICTURE_STYLE to CanonEosPropertySpec(1, pictureStyleLabels),
        CanonEosPropertyCode.HIGH_ISO_NOISE_REDUCTION to CanonEosPropertySpec(2, highIsoNoiseReductionLabels),
        CanonEosPropertyCode.MOVIE_SERVO_AF to CanonEosPropertySpec(4, offOnLabels),
        CanonEosPropertyCode.UTC_TIME to CanonEosPropertySpec(4, emptyMap()),
        CanonEosPropertyCode.EVF_RECORD_STATUS to CanonEosPropertySpec(2, movieRecordTargetLabels),
        CanonEosPropertyCode.LIVE_VIEW_AF_SYSTEM to CanonEosPropertySpec(4, afMethodLabels),
        // libgphoto2's Generic32 table and EOS event parser establish the wire width.
        CanonEosPropertyCode.AUTO_LIGHTING_OPTIMIZER to CanonEosPropertySpec(
            valueBytes = 4,
            labels = autoLightingOptimizerLabels,
            selectableValues = autoLightingOptimizerLabels.keys,
        ),
        CanonEosPropertyCode.FIXED_MOVIE to CanonEosPropertySpec(4, offOnLabels),
        CanonEosPropertyCode.CONTINUOUS_AF_MODE to CanonEosPropertySpec(4, offOnLabels),
        CanonEosPropertyCode.AEB to CanonEosPropertySpec(2, aebLabels),
    )

    private const val MAX_PROPERTY_OPTIONS = 4_096L
    private const val PTP_STORAGE_READ_WRITE = 0
    private const val IMAGE_FORMAT_ENTRY_BYTES = 0x10
    private const val IMAGE_FORMAT_TYPE_JPEG = 1L
    private const val IMAGE_FORMAT_TYPE_RAW = 6L
}

private fun ByteArray.u32Le(offset: Int): Long =
    this[offset].toUByte().toLong() or
        (this[offset + 1].toUByte().toLong() shl 8) or
        (this[offset + 2].toUByte().toLong() shl 16) or
        (this[offset + 3].toUByte().toLong() shl 24)

private fun ByteArray.u16Le(offset: Int): Int =
    this[offset].toUByte().toInt() or (this[offset + 1].toUByte().toInt() shl 8)

private fun ByteArray.nullTerminatedAscii(offset: Int, limit: Int): String {
    val end = (offset until limit).firstOrNull { this[it] == 0.toByte() }
        ?: throw PtpProtocolException("Canon EOS object-transfer filename is not null terminated.")
    return buildString(end - offset) {
        for (index in offset until end) append(this@nullTerminatedAscii[index].toInt().and(0xFF).toChar())
    }
}

private fun ByteArray.unsignedLe(offset: Int, size: Int): Long {
    require(size in 1..4)
    return (0 until size).fold(0L) { value, index ->
        value or (this[offset + index].toUByte().toLong() shl (index * 8))
    }
}

private fun ByteArray.numberLe(offset: Int, size: Int, signed: Boolean): Long {
    val value = unsignedLe(offset, size)
    if (!signed) return value
    val bits = size * 8
    val signBit = 1L shl (bits - 1)
    return if (value and signBit == 0L) value else value - (1L shl bits)
}

private fun Long.hexLabel(bytes: Int): String =
    "0x${toString(16).uppercase().padStart(bytes * 2, '0')}"

private fun ByteArray.putU32Le(offset: Int, value: Long) {
    require(value in 0..UINT32_MAX) { "Value $value does not fit in an unsigned 32-bit field." }
    repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
}

private data class CanonEosImageFormatValue(
    val value: Long,
    val bytesRead: Int,
)
