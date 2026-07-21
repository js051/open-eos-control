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
    const val EVF_OUTPUT_DEVICE = 0xD1B0
    const val EVF_MODE = 0xD1B1
}

object CanonEosEventCode {
    const val OBJECT_ADDED_EX = 0xC181
    const val REQUEST_OBJECT_TRANSFER = 0xC186
    const val OBJECT_ADDED_EX_64 = 0xC1A7
    const val REQUEST_OBJECT_TRANSFER_64 = 0xC1A9
    const val OBJECT_ADDED_EX_64_LFN = 0xC1B6
    const val REQUEST_OBJECT_TRANSFER_64_LFN = 0xC1B8
}

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

    fun uint32PropertyPayload(propertyCode: Int, value: Long): ByteArray {
        require(value in 0..UINT32_MAX) { "Canon EOS property value $value does not fit UINT32." }
        return propertyPayload(propertyCode, value, 4)
    }

    fun eventCodes(payload: ByteArray): Set<Int> {
        val codes = linkedSetOf<Int>()
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
            codes += code.toInt()
            offset += length.toInt()
        }
        return codes
    }

    fun containsCapturedObjectEvent(payload: ByteArray): Boolean =
        eventCodes(payload).any { it in capturedObjectEvents }

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
}

private fun ByteArray.u32Le(offset: Int): Long =
    this[offset].toUByte().toLong() or
        (this[offset + 1].toUByte().toLong() shl 8) or
        (this[offset + 2].toUByte().toLong() shl 16) or
        (this[offset + 3].toUByte().toLong() shl 24)

private fun ByteArray.putU32Le(offset: Int, value: Long) {
    require(value in 0..UINT32_MAX) { "Value $value does not fit in an unsigned 32-bit field." }
    repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
}
