package dev.openeos.control.data

object CanonEosOperationCode {
    const val SET_DEVICE_PROP_VALUE_EX = 0x9110
    const val SET_REMOTE_MODE = 0x9114
    const val SET_EVENT_MODE = 0x9115
    const val GET_EVENT = 0x9116
    const val REMOTE_RELEASE_ON = 0x9128
    const val REMOTE_RELEASE_OFF = 0x9129
    const val GET_VIEWFINDER_DATA = 0x9153
    const val DO_AF = 0x9154
    const val DRIVE_LENS = 0x9155
    const val TOUCH_AF_POSITION = 0x915B
    const val AF_CANCEL = 0x9160
}

object CanonEosPropertyCode {
    const val APERTURE = 0xD101
    const val SHUTTER_SPEED = 0xD102
    const val ISO_SPEED = 0xD103
    const val WHITE_BALANCE = 0xD109
    const val EVF_OUTPUT_DEVICE = 0xD1B0
    const val EVF_MODE = 0xD1B1
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

data class CanonEosPropertyOption(
    val value: Long,
    val label: String,
)

object CanonEosPtp {
    const val VENDOR_EXTENSION_ID = 0x0000000BL
    const val VIEWFINDER_REQUEST_BYTES = 0x00200000L
    const val VIEWFINDER_NOT_READY_RESPONSE = 0xA102

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

    fun isCanonEos(info: PtpDeviceInfo): Boolean = info.vendorExtensionId == VENDOR_EXTENSION_ID

    fun supportsRemotePreparation(info: PtpDeviceInfo): Boolean =
        isCanonEos(info) && remotePreparationOperations.all(info::supports)

    fun supportsRemoteRelease(info: PtpDeviceInfo): Boolean =
        supportsRemotePreparation(info) &&
            info.supports(CanonEosOperationCode.REMOTE_RELEASE_ON) &&
            info.supports(CanonEosOperationCode.REMOTE_RELEASE_OFF)

    fun supportsLiveView(info: PtpDeviceInfo): Boolean =
        supportsRemotePreparation(info) &&
            info.supports(CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX) &&
            info.supports(CanonEosOperationCode.GET_VIEWFINDER_DATA)

    fun supportsFocusDrive(info: PtpDeviceInfo): Boolean =
        supportsRemotePreparation(info) && info.supports(CanonEosOperationCode.DRIVE_LENS)

    fun supportsPropertyControl(info: PtpDeviceInfo): Boolean =
        supportsRemotePreparation(info) && info.supports(CanonEosOperationCode.SET_DEVICE_PROP_VALUE_EX)

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

    fun eventCodes(payload: ByteArray): Set<Int> {
        return eventBlocks(payload).mapTo(linkedSetOf(), CanonEosEventBlock::code)
    }

    fun containsCapturedObjectEvent(payload: ByteArray): Boolean =
        eventCodes(payload).any { it in capturedObjectEvents }

    fun propertyUpdates(payload: ByteArray): List<CanonEosPropertyUpdate> = buildList {
        eventBlocks(payload).forEach { block ->
            when (block.code) {
                CanonEosEventCode.PROPERTY_VALUE_CHANGED -> {
                    if (block.length < 12) malformedPropertyEvent(block, "property code")
                    val propertyCode = payload.u32Le(block.offset + 8).toInt()
                    val valueBytes = propertySpecs[propertyCode]?.valueBytes ?: return@forEach
                    if (block.length < 12 + valueBytes) malformedPropertyEvent(block, "property value")
                    add(
                        CanonEosPropertyUpdate(
                            propertyCode = propertyCode,
                            currentValue = payload.unsignedLe(block.offset + 12, valueBytes),
                        )
                    )
                }

                CanonEosEventCode.AVAILABLE_LIST_CHANGED -> {
                    if (block.length < 20) malformedPropertyEvent(block, "available-value header")
                    val propertyCode = payload.u32Le(block.offset + 8).toInt()
                    if (propertyCode !in propertySpecs) return@forEach
                    val listType = payload.u32Le(block.offset + 12)
                    if (listType != 3L) return@forEach
                    val count = payload.u32Le(block.offset + 16)
                    val requiredBytes = 20L + count * 4L
                    if (count > MAX_PROPERTY_OPTIONS || requiredBytes > block.length.toLong()) {
                        malformedPropertyEvent(block, "available-value list")
                    }
                    add(
                        CanonEosPropertyUpdate(
                            propertyCode = propertyCode,
                            availableValues = List(count.toInt()) { index ->
                                payload.u32Le(block.offset + 20 + index * 4)
                            },
                        )
                    )
                }
            }
        }
    }

    fun propertyOptions(propertyCode: Int, values: List<Long>): List<CanonEosPropertyOption> =
        values.distinct().map { value ->
            CanonEosPropertyOption(value = value, label = propertyLabel(propertyCode, value))
        }.distinctBy(CanonEosPropertyOption::label)

    fun propertyLabel(propertyCode: Int, value: Long): String =
        propertySpecs[propertyCode]?.labels?.get(value) ?: value.hexLabel(propertySpecs[propertyCode]?.valueBytes ?: 4)

    fun propertyValue(propertyCode: Int, values: List<Long>, label: String): Long? =
        propertyOptions(propertyCode, values).firstOrNull { it.label == label }?.value

    fun propertyPayload(propertyCode: Int, value: Long): ByteArray = when (propertySpecs[propertyCode]?.valueBytes) {
        1 -> uint8PropertyPayload(propertyCode, value.toInt())
        2 -> uint16PropertyPayload(propertyCode, value.toInt())
        4 -> uint32PropertyPayload(propertyCode, value)
        else -> throw IllegalArgumentException("Canon EOS property 0x${propertyCode.toString(16)} is not writable.")
    }

    fun liveViewJpeg(payload: ByteArray): ByteArray {
        var offset = 0
        while (offset + 8 <= payload.size) {
            val length = payload.u32Le(offset)
            val type = payload.u32Le(offset + 4)
            if (length < 8L || length > Int.MAX_VALUE || length > payload.size - offset) {
                throw PtpProtocolException(
                    "Canon EOS viewfinder block at byte $offset declares invalid length $length " +
                        "for ${payload.size - offset} remaining bytes."
                )
            }
            if (type == 1L || type == 11L) {
                val jpeg = payload.copyOfRange(offset + 8, offset + length.toInt())
                if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
                    throw PtpProtocolException("Canon EOS viewfinder block type $type did not contain JPEG data.")
                }
                return jpeg
            }
            offset += length.toInt()
        }
        throw PtpProtocolException("Canon EOS viewfinder response did not contain a JPEG block.")
    }

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

    private data class CanonEosEventBlock(
        val code: Int,
        val offset: Int,
        val length: Int,
    )

    private data class CanonEosPropertySpec(
        val valueBytes: Int,
        val labels: Map<Long, String>,
    )

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

    private val propertySpecs = mapOf(
        CanonEosPropertyCode.APERTURE to CanonEosPropertySpec(2, apertureLabels),
        CanonEosPropertyCode.SHUTTER_SPEED to CanonEosPropertySpec(2, shutterLabels),
        CanonEosPropertyCode.ISO_SPEED to CanonEosPropertySpec(2, isoLabels),
        CanonEosPropertyCode.WHITE_BALANCE to CanonEosPropertySpec(1, whiteBalanceLabels),
    )

    private const val MAX_PROPERTY_OPTIONS = 4_096L
}

private fun ByteArray.u32Le(offset: Int): Long =
    this[offset].toUByte().toLong() or
        (this[offset + 1].toUByte().toLong() shl 8) or
        (this[offset + 2].toUByte().toLong() shl 16) or
        (this[offset + 3].toUByte().toLong() shl 24)

private fun ByteArray.unsignedLe(offset: Int, size: Int): Long {
    require(size in 1..4)
    return (0 until size).fold(0L) { value, index ->
        value or (this[offset + index].toUByte().toLong() shl (index * 8))
    }
}

private fun Long.hexLabel(bytes: Int): String =
    "0x${toString(16).uppercase().padStart(bytes * 2, '0')}"

private fun ByteArray.putU32Le(offset: Int, value: Long) {
    require(value in 0..UINT32_MAX) { "Value $value does not fit in an unsigned 32-bit field." }
    repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
}
